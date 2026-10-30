package dev.watched.probe.phone

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.DataPoint
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.UserDataPoint
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import dev.watched.shared.Envelope
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.TypeReport
import dev.watched.shared.countFieldPresence
import dev.watched.shared.medianInterval
import dev.watched.shared.reflectToJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Surface B: Samsung Health Data SDK v1.1.0.
 *
 * The API shape here was read directly out of the shipped AAR with `javap`, not guessed from
 * prose. The SDK's bundled docs/ folder is only HTML redirects to the web, so the bytecode is
 * the authoritative reference.
 *
 * Two things the bytecode settled that the published docs got wrong or left ambiguous:
 *  - `StepsType` does NOT implement `DataType.Readable`. Steps really is aggregate-only, despite
 *    Samsung's own permission sample requesting `READ` on it. Same for the *Goal types.
 *  - There is no HRV data type anywhere in the SDK. Not undocumented -- absent.
 */
class SamsungDataProbe(
    private val context: Context,
    private val sink: JsonlFileSink,
    private val runId: String,
) : SamsungProbe {

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }

    companion object {
        /**
         * The 16 types that implement `DataType.Readable`, confirmed from the AAR.
         *
         * Excluded because they are aggregate-only: STEPS, STEPS_GOAL, SLEEP_GOAL,
         * ACTIVITY_SUMMARY, ACTIVE_TIME_GOAL, ACTIVE_CALORIES_BURNED_GOAL, NUTRITION_GOAL,
         * WATER_INTAKE_GOAL, EXERCISE_LOCATION.
         */
        private val READABLE: List<DataType> = listOf(
            DataTypes.HEART_RATE,
            DataTypes.SLEEP,
            DataTypes.BLOOD_OXYGEN,
            DataTypes.BLOOD_GLUCOSE,
            DataTypes.BLOOD_PRESSURE,
            DataTypes.BODY_COMPOSITION,
            DataTypes.BODY_TEMPERATURE,
            DataTypes.EXERCISE,
            DataTypes.FLOORS_CLIMBED,
            DataTypes.NUTRITION,
            DataTypes.WATER_INTAKE,
            DataTypes.USER_PROFILE,
            // The four that exist on no other surface. These are why Surface B earns its gate.
            DataTypes.ENERGY_SCORE,
            DataTypes.SKIN_TEMPERATURE,
            DataTypes.SLEEP_APNEA,
            DataTypes.IRREGULAR_HEART_RHYTHM_NOTIFICATION,
        )

        private const val PAGE_SIZE = 1000
    }

    private fun permissionsFor(types: List<DataType>): Set<Permission> =
        types.map { Permission.of(it, AccessType.READ) }.toSet()

    override fun availability(): String = try {
        store // touching the lazy forces construction
        "store created (developer mode state unknown until first read)"
    } catch (e: HealthDataException) {
        describe(e)
    } catch (t: Throwable) {
        "${t::class.simpleName}: ${t.message}"
    }

    override suspend fun requestPermissions(activity: Activity): Set<String> {
        val wanted = permissionsFor(READABLE)
        return try {
            val granted = store.getGrantedPermissions(wanted)
            val missing = wanted - granted
            val finalGranted = if (missing.isEmpty()) {
                granted
            } else {
                store.requestPermissions(missing, activity) + granted
            }
            finalGranted.map { it.dataType.name }.toSet()
        } catch (e: ResolvablePlatformException) {
            // The user can often fix this themselves -- Samsung Health missing, disabled, or
            // never initialised. Hand them the resolution instead of just failing.
            if (e.hasResolution) runCatching { e.resolve(activity) }
            emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    override suspend fun sweep(daysBack: Long, log: (String) -> Unit): List<TypeReport> {
        val granted: Set<Permission> = try {
            store.getGrantedPermissions(permissionsFor(READABLE))
        } catch (t: Throwable) {
            log("getGrantedPermissions failed: ${detail(t)}")
            log("If this is ERR_INVALID_CALLER or a signature error, Developer Mode for Data Read")
            log("is probably OFF. See docs/access-gates.md section B.")
            return READABLE.map {
                TypeReport(SamsungProbe.SURFACE, it.name, false, false, error = detail(t))
            }
        }

        log("granted: ${granted.size}/${READABLE.size} types")

        val end = LocalDateTime.now()
        val start = end.minusDays(daysBack)

        return READABLE.map { type ->
            if (Permission.of(type, AccessType.READ) !in granted) {
                log("${type.name}: permission NOT granted")
                TypeReport(SamsungProbe.SURFACE, type.name, false, false)
            } else {
                readType(type, start, end, log)
            }
        }
    }

    private suspend fun readType(
        type: DataType,
        start: LocalDateTime,
        end: LocalDateTime,
        log: (String) -> Unit,
    ): TypeReport {
        val timestamps = mutableListOf<Long>()
        val sample = mutableListOf<JsonElement>()
        var count = 0
        var pageToken: String? = null

        try {
            do {
                val request = buildRequest(type, start, end, pageToken)
                    ?: return TypeReport(
                        SamsungProbe.SURFACE, type.name, false, true,
                        error = "no supported read builder for this type",
                    )

                @Suppress("UNCHECKED_CAST")
                val response = store.readData(request as ReadDataRequest<DataPoint>)

                for (point in response.dataList) {
                    val json = toJson(point, type)
                    sink.write(
                        Envelope(
                            surface = SamsungProbe.SURFACE,
                            type = type.name,
                            runId = runId,
                            capturedAt = System.currentTimeMillis(),
                            payload = json,
                        )
                    )
                    if (sample.size < SCHEMA_SAMPLE) sample += json
                    (point as? HealthDataPoint)?.let { timestamps += it.startTime.toEpochMilli() }
                    count++
                }
                pageToken = response.pageToken
            } while (pageToken != null)
        } catch (t: Throwable) {
            log("${type.name}: ERROR ${detail(t)}")
            return TypeReport(
                SamsungProbe.SURFACE, type.name,
                available = false, permissionGranted = true,
                recordCount = count, error = detail(t),
            )
        }

        log("${type.name}: $count records")
        return TypeReport(
            surface = SamsungProbe.SURFACE,
            type = type.name,
            available = count > 0,
            permissionGranted = true,
            recordCount = count,
            oldestRecord = timestamps.minOrNull(),
            newestRecord = timestamps.maxOrNull(),
            medianIntervalMs = medianInterval(timestamps),
            fieldPresence = countFieldPresence(sample),
            bytes = sink.bytesFor(SamsungProbe.SURFACE, type.name),
            notes = buildList {
                add("declaredFields=" + type.allFields.joinToString(",") { it.name })
                if (count == 0) add("permission granted but zero records in window")
            },
        )
    }

    /**
     * Three builder flavours, keyed off the type: most take a dual time filter, energy score
     * takes a date filter, and the user profile takes none at all.
     */
    private fun buildRequest(
        type: DataType,
        start: LocalDateTime,
        end: LocalDateTime,
        pageToken: String?,
    ): ReadDataRequest<*>? {
        val readable = type as? DataType.Readable<*, *> ?: return null
        return when (val builder = readable.readDataRequestBuilder) {
            is ReadDataRequest.DualTimeBuilder<*> -> builder
                .setLocalTimeFilter(LocalTimeFilter.of(start, end))
                .setPageSize(PAGE_SIZE)
                .apply { pageToken?.let { setPageToken(it) } }
                .build()

            is ReadDataRequest.LocalDateBuilder<*> -> builder
                .setLocalDateFilter(LocalDateFilter.of(start.toLocalDate(), end.toLocalDate()))
                .setPageSize(PAGE_SIZE)
                .apply { pageToken?.let { setPageToken(it) } }
                .build()

            is ReadDataRequest.UserProfileBuilder -> builder.build()

            else -> null
        }
    }

    /**
     * Dumps a point using the type's own declared field list, so we capture exactly what Samsung
     * defines rather than whatever we guessed. Metadata keys are `_`-prefixed to keep them from
     * colliding with Samsung's snake_case field names.
     */
    private fun toJson(point: DataPoint, type: DataType): JsonElement {
        val out = mutableMapOf<String, JsonElement>()

        when (point) {
            is HealthDataPoint -> {
                out["_uid"] = JsonPrimitive(point.uid)
                out["_startTime"] = JsonPrimitive(point.startTime.toString())
                out["_endTime"] = JsonPrimitive(point.endTime.toString())
                out["_updateTime"] = JsonPrimitive(point.updateTime.toString())
                out["_zoneOffset"] = JsonPrimitive(point.zoneOffset?.toString())
                out["_appId"] = JsonPrimitive(point.dataSource?.appId)
                out["_deviceId"] = JsonPrimitive(point.dataSource?.deviceId)
                for (field in type.allFields) {
                    @Suppress("UNCHECKED_CAST")
                    val value = runCatching { point.getValue(field as Field<Any>) }.getOrNull()
                    out[field.name] = reflectToJson(value)
                }
            }

            is UserDataPoint -> {
                for (field in type.allFields) {
                    @Suppress("UNCHECKED_CAST")
                    val value = runCatching { point.getValue(field as Field<Any>) }.getOrNull()
                    out[field.name] = reflectToJson(value)
                }
            }

            // Unknown DataPoint implementation: fall back to reflection rather than drop it.
            else -> return reflectToJson(point)
        }
        return JsonObject(out)
    }

    private fun describe(e: HealthDataException): String =
        "${e::class.simpleName}(code=${e.errorCode}): ${e.errorMessage ?: e.message}"

    private fun detail(t: Throwable): String =
        if (t is HealthDataException) describe(t) else "${t::class.simpleName}: ${t.message}"
}

private const val SCHEMA_SAMPLE = 200
