package dev.watched.probe.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.watched.shared.Envelope
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.TypeReport
import dev.watched.shared.countFieldPresence
import dev.watched.shared.medianInterval
import dev.watched.shared.reflectToJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Surface A sweep: everything Health Connect will give us about this Galaxy Watch 7.
 *
 * Answers questions 6, 7 and 10 of docs/capability-matrix.md.
 */
class HealthConnectProbe(
    private val context: Context,
    private val sink: JsonlFileSink,
    private val runId: String,
) {
    companion object {
        const val SURFACE = "health_connect"
        private const val PAGE_SIZE = 1000

        /**
         * Types Samsung publishes as synced. If any of these come back empty, that is a finding
         * about this device, not about the API.
         */
        val MAPPED: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            HeartRateRecord::class,
            SleepSessionRecord::class,
            OxygenSaturationRecord::class,
            BloodGlucoseRecord::class,
            BloodPressureRecord::class,
            ExerciseSessionRecord::class,
            TotalCaloriesBurnedRecord::class,
            DistanceRecord::class,
            PowerRecord::class,
            SpeedRecord::class,
            Vo2MaxRecord::class,
            NutritionRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
            BasalMetabolicRateRecord::class,
            HeightRecord::class,
        )

        /**
         * Types Health Connect defines but Samsung's mapping table omits. These are the
         * interesting ones -- especially HRV, which has no confirmed source anywhere.
         */
        val UNMAPPED_CANDIDATES: List<KClass<out Record>> = listOf(
            HeartRateVariabilityRmssdRecord::class,
            RestingHeartRateRecord::class,
            SkinTemperatureRecord::class,
            RespiratoryRateRecord::class,
            BodyTemperatureRecord::class,
            BodyWaterMassRecord::class,
            BoneMassRecord::class,
            LeanBodyMassRecord::class,
            ActiveCaloriesBurnedRecord::class,
            FloorsClimbedRecord::class,
            ElevationGainedRecord::class,
            HydrationRecord::class,
        )

        val ALL: List<KClass<out Record>> = MAPPED + UNMAPPED_CANDIDATES

        fun readPermissions(): Set<String> =
            ALL.map { HealthPermission.getReadPermission(it) }.toSet()
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "SDK_AVAILABLE"
        HealthConnectClient.SDK_UNAVAILABLE -> "SDK_UNAVAILABLE"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED"
        else -> "UNKNOWN"
    }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** Is the background-read feature actually present on this device? Docs disagree; ask it. */
    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    fun backgroundReadFeatureStatus(): String = try {
        when (client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        )) {
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE -> "FEATURE_STATUS_AVAILABLE"
            HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE -> "FEATURE_STATUS_UNAVAILABLE"
            else -> "UNKNOWN"
        }
    } catch (t: Throwable) {
        "ERROR:${t::class.simpleName}:${t.message}"
    }

    /** Dumps every type over [daysBack] and reports what came back. */
    suspend fun sweep(daysBack: Long, log: (String) -> Unit): List<TypeReport> {
        val granted = grantedPermissions()
        val end = Instant.now()
        val start = end.minus(daysBack, ChronoUnit.DAYS)

        return ALL.map { kclass ->
            val label = kclass.simpleName ?: kclass.toString()
            val perm = HealthPermission.getReadPermission(kclass)
            if (perm !in granted) {
                log("$label: permission NOT granted")
                return@map TypeReport(SURFACE, label, available = false, permissionGranted = false)
            }
            readType(kclass, label, start, end, log)
        }
    }

    private suspend fun readType(
        kclass: KClass<out Record>,
        label: String,
        start: Instant,
        end: Instant,
        log: (String) -> Unit,
    ): TypeReport {
        val timestamps = mutableListOf<Long>()
        val payloads = mutableListOf<JsonElement>()
        var count = 0
        var pageToken: String? = null

        try {
            do {
                @Suppress("UNCHECKED_CAST")
                val request = ReadRecordsRequest(
                    recordType = kclass as KClass<Record>,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                )
                val response = client.readRecords(request)
                for (record in response.records) {
                    val json = reflectToJson(record)
                    sink.write(
                        Envelope(
                            surface = SURFACE,
                            type = label,
                            runId = runId,
                            capturedAt = System.currentTimeMillis(),
                            payload = json,
                        )
                    )
                    // Keep a bounded sample in memory for the schema report; the full dump is on disk.
                    if (payloads.size < SCHEMA_SAMPLE) payloads += json
                    recordTime(json)?.let { timestamps += it }
                    count++
                }
                pageToken = response.pageToken
            } while (pageToken != null)
        } catch (t: Throwable) {
            log("$label: ERROR ${t::class.simpleName}: ${t.message}")
            return TypeReport(
                surface = SURFACE,
                type = label,
                available = false,
                permissionGranted = true,
                recordCount = count,
                error = "${t::class.simpleName}: ${t.message}",
            )
        }

        log("$label: $count records")
        return TypeReport(
            surface = SURFACE,
            type = label,
            available = count > 0,
            permissionGranted = true,
            recordCount = count,
            oldestRecord = timestamps.minOrNull(),
            newestRecord = timestamps.maxOrNull(),
            medianIntervalMs = medianInterval(timestamps),
            fieldPresence = countFieldPresence(payloads),
            bytes = sink.bytesFor(SURFACE, label),
            notes = if (count == 0) listOf("permission granted but zero records in window") else emptyList(),
        )
    }

    /**
     * Record timestamps come out of the reflected payload rather than the record object.
     *
     * Health Connect marks InstantaneousRecord and IntervalRecord `internal`, so there is no
     * public supertype exposing `time` / `startTime`. reflectToJson has already rendered those
     * java.time.Instant fields as ISO-8601 strings, so read them back from there.
     */
    private fun recordTime(json: JsonElement): Long? {
        if (json !is JsonObject) return null
        val raw = (json["time"] ?: json["startTime"]) as? JsonPrimitive ?: return null
        return runCatching { Instant.parse(raw.content).toEpochMilli() }.getOrNull()
    }

    /**
     * Question 6b: where exactly is the history wall?
     *
     * Reads one type at 400 days back. Without READ_HEALTH_DATA_HISTORY this is documented to
     * error; the error text itself is the finding.
     */
    suspend fun probeHistoryWall(log: (String) -> Unit): TypeReport {
        val end = Instant.now()
        val start = end.minus(400, ChronoUnit.DAYS)
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1,
                )
            )
            log("history wall: 400d read SUCCEEDED, ${response.records.size} record(s)")
            TypeReport(
                SURFACE, "history-wall-400d",
                available = true, permissionGranted = true,
                recordCount = response.records.size,
                notes = listOf("400-day read succeeded; history permission appears granted or unenforced"),
            )
        } catch (t: Throwable) {
            log("history wall: 400d read FAILED ${t::class.simpleName}: ${t.message}")
            TypeReport(
                SURFACE, "history-wall-400d",
                available = false, permissionGranted = true,
                error = "${t::class.simpleName}: ${t.message}",
                notes = listOf("expected when READ_HEALTH_DATA_HISTORY is not granted"),
            )
        }
    }

    /**
     * Question: is the incremental path usable? Tokens expire unused after 30 days, so the
     * eventual collector needs this to work or it needs a full-resync fallback.
     */
    suspend fun probeChangesToken(log: (String) -> Unit): TypeReport = try {
        val token = client.getChangesToken(ChangesTokenRequest(recordTypes = setOf(HeartRateRecord::class)))
        val response = client.getChanges(token)
        val upserts = response.changes.count { it is UpsertionChange }
        val deletes = response.changes.count { it is DeletionChange }
        log("changes token: ok, $upserts upsert / $deletes delete, hasMore=${response.hasMore}")
        TypeReport(
            SURFACE, "changes-token-HeartRateRecord",
            available = true, permissionGranted = true,
            recordCount = response.changes.size,
            notes = listOf(
                "token obtained",
                "upsertions=$upserts deletions=$deletes hasMore=${response.hasMore}",
            ),
        )
    } catch (t: Throwable) {
        log("changes token: FAILED ${t::class.simpleName}: ${t.message}")
        TypeReport(
            SURFACE, "changes-token-HeartRateRecord",
            available = false, permissionGranted = true,
            error = "${t::class.simpleName}: ${t.message}",
        )
    }
}

private const val SCHEMA_SAMPLE = 200
