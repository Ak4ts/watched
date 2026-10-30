package dev.watched.probe.watch

import android.content.Context
import android.os.BatteryManager
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import dev.watched.shared.Envelope
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.TypeReport
import dev.watched.shared.countFieldPresence
import dev.watched.shared.medianInterval
import dev.watched.shared.reflectToJson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.resume

/**
 * Surface C: Samsung Health Sensor SDK v1.4.1, on-watch.
 *
 * Every signature here was read out of the shipped AAR with `javap`; the SDK's bundled docs/ and
 * sample-codes/ folders are only HTML redirects to the web.
 *
 * The one thing worth knowing: **`SDK_POLICY_ERROR` is a `HealthTracker.TrackerError` enum value,
 * not a code on `HealthTrackerException`.** The exception only carries `PACKAGE_NOT_INSTALLED`
 * and `OLD_PLATFORM_VERSION`. So a policy block arrives asynchronously through
 * `TrackerEventListener.onError`, never as a throw — which means you cannot detect it with a
 * try/catch and must wait for the callback.
 */
class SamsungSensorProbe(
    private val context: Context,
    private val sink: JsonlFileSink,
    private val runId: String,
) : SensorProbe {

    private var service: HealthTrackingService? = null
    private var connectionError: String? = null

    override fun availability(): String = when {
        service != null -> "connected"
        connectionError != null -> "failed: $connectionError"
        else -> "not connected"
    }

    private suspend fun connect(log: (String) -> Unit): HealthTrackingService? {
        service?.let { return it }
        return try {
            val ok = withTimeout(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : ConnectionListener {
                        override fun onConnectionSuccess() {
                            log("sensor service: connected")
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onConnectionEnded() {
                            log("sensor service: connection ended")
                        }

                        override fun onConnectionFailed(e: HealthTrackerException) {
                            connectionError = describe(e)
                            log("sensor service: connection FAILED ${describe(e)}")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val svc = HealthTrackingService(listener, context)
                    service = svc
                    svc.connectService()
                }
            }
            if (ok) service else null
        } catch (t: TimeoutCancellationException) {
            connectionError = "timed out after ${CONNECT_TIMEOUT_MS}ms"
            log("sensor service: connect timed out")
            null
        } catch (t: Throwable) {
            connectionError = "${t::class.simpleName}: ${t.message}"
            log("sensor service: connect threw $connectionError")
            null
        }
    }

    override suspend fun policySmokeTest(log: (String) -> Unit): PolicyCheck {
        log("=== SDK_POLICY_ERROR smoke test ===")
        log("Requires: watch developer mode ON. See docs/access-gates.md section C.")

        val svc = connect(log) ?: return PolicyCheck.Failed(connectionError ?: "could not connect")

        val tracker = try {
            svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        } catch (e: HealthTrackerException) {
            return PolicyCheck.Failed(describe(e))
        } catch (t: Throwable) {
            return PolicyCheck.Failed("${t::class.simpleName}: ${t.message}")
        }

        return try {
            when (val first = awaitFirstBatch(tracker, SMOKE_TIMEOUT_MS, log)) {
                is BatchResult.Data -> {
                    log("POLICY OK: received ${first.points.size} data point(s) in developer mode")
                    log("Surface C is open. Raw PPG / accelerometer / ECG / IBI are reachable.")
                    PolicyCheck.Ok("HEART_RATE_CONTINUOUS delivered ${first.points.size} points")
                }

                is BatchResult.Failed -> when (first.trackerError) {
                    HealthTracker.TrackerError.SDK_POLICY_ERROR -> {
                        log("POLICY BLOCKED: SDK_POLICY_ERROR")
                        log("Developer mode is NOT sufficient. Raw sensors need a Samsung partnership.")
                        PolicyCheck.PolicyBlocked(first.detail)
                    }

                    HealthTracker.TrackerError.PERMISSION_ERROR -> {
                        log("PERMISSION_ERROR — grant BODY_SENSORS and retry. Not a policy block.")
                        PolicyCheck.Failed(first.detail)
                    }

                    else -> PolicyCheck.Failed(first.detail)
                }

                null -> PolicyCheck.Failed("no data and no error within ${SMOKE_TIMEOUT_MS}ms")
            }
        } finally {
            runCatching { tracker.unsetEventListener() }
        }
    }

    /** `hasResolution()`/`resolve()` let the user install or update the Health platform. */
    private fun describe(e: HealthTrackerException): String =
        "errorCode=${e.errorCode} hasResolution=${runCatching { e.hasResolution() }.getOrNull()} msg=${e.message}"

    override suspend fun enumerateTrackers(log: (String) -> Unit): List<String> {
        val svc = connect(log) ?: return emptyList()
        return try {
            val capability = svc.trackingCapability
            val names = capability.supportHealthTrackerTypes.map { it.name }
            log("platform version: ${runCatching { capability.version }.getOrNull()}")
            log("watch exposes ${names.size} tracker(s):")
            names.sorted().forEach { log("  $it") }
            HealthTrackerType.values()
                .map { it.name }
                .filter { it !in names }
                .sorted()
                .forEach { log("  ABSENT: $it") }
            names
        } catch (t: Throwable) {
            log("enumerate failed: ${t::class.simpleName}: ${t.message}")
            emptyList()
        }
    }

    override suspend fun sampleContinuous(
        tracker: String,
        seconds: Int,
        log: (String) -> Unit,
    ): TypeReport {
        val svc = connect(log) ?: return failReport(tracker, connectionError ?: "not connected")

        val type = HealthTrackerType.values().firstOrNull { it.name == tracker }
            ?: return failReport(tracker, "no such HealthTrackerType in this SDK")

        val batteryBefore = batteryPercent()
        val timestamps = mutableListOf<Long>()
        val sample = mutableListOf<JsonElement>()
        var count = 0
        var error: String? = null

        val healthTracker = try {
            svc.getHealthTracker(type)
        } catch (e: HealthTrackerException) {
            return failReport(tracker, describe(e))
        } catch (t: Throwable) {
            return failReport(tracker, "${t::class.simpleName}: ${t.message}")
        }

        try {
            healthTracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(list: MutableList<DataPoint>) {
                    for (point in list) {
                        // Reflected rather than read through ValueKey constants: which keys a
                        // Watch 7 populates per tracker is exactly what we are here to find out.
                        val json = reflectToJson(point)
                        sink.write(
                            Envelope(SensorProbe.SURFACE, tracker, runId, System.currentTimeMillis(), json)
                        )
                        if (sample.size < SCHEMA_SAMPLE) sample += json
                        runCatching { timestamps += point.timestamp }
                        count++
                    }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(trackerError: HealthTracker.TrackerError?) {
                    error = "TrackerError=$trackerError"
                    log("$tracker: onError $trackerError")
                }
            })

            log("$tracker: streaming for ${seconds}s ...")
            delay(seconds * 1000L)
            runCatching { healthTracker.flush() }
        } finally {
            runCatching { healthTracker.unsetEventListener() }
        }

        val batteryAfter = batteryPercent()
        val bytes = sink.bytesFor(SensorProbe.SURFACE, tracker)
        val achievedHz = if (seconds > 0) count.toDouble() / seconds else 0.0

        log("$tracker: $count points in ${seconds}s = %.1f Hz, ${bytes / 1024} KiB".format(achievedHz))

        return TypeReport(
            surface = SensorProbe.SURFACE,
            type = tracker,
            available = count > 0,
            permissionGranted = true,
            recordCount = count,
            oldestRecord = timestamps.minOrNull(),
            newestRecord = timestamps.maxOrNull(),
            medianIntervalMs = medianInterval(timestamps),
            fieldPresence = countFieldPresence(sample),
            bytes = bytes,
            error = error,
            notes = listOf(
                "achievedHz=%.2f".format(achievedHz),
                "windowSeconds=$seconds",
                "battery ${batteryBefore}% -> ${batteryAfter}%",
                "projectedBytesPerDay=${if (seconds > 0) bytes * 86400 / seconds else 0}",
            ),
        )
    }

    override suspend fun measureOnDemand(tracker: String, log: (String) -> Unit): TypeReport =
        // On-demand trackers run one at a time, foreground, for roughly 30 seconds.
        sampleContinuous(tracker, ON_DEMAND_SECONDS, log)

    private fun failReport(tracker: String, why: String) = TypeReport(
        surface = SensorProbe.SURFACE,
        type = tracker,
        available = false,
        permissionGranted = false,
        error = why,
    )

    private fun batteryPercent(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private sealed interface BatchResult {
        data class Data(val points: List<DataPoint>) : BatchResult
        data class Failed(
            val trackerError: HealthTracker.TrackerError?,
            val detail: String,
        ) : BatchResult
    }

    private suspend fun awaitFirstBatch(
        tracker: HealthTracker,
        timeoutMs: Long,
        log: (String) -> Unit,
    ): BatchResult? = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(list: MutableList<DataPoint>) {
                        if (cont.isActive) cont.resume(BatchResult.Data(list.toList()))
                    }

                    override fun onFlushCompleted() = Unit

                    override fun onError(trackerError: HealthTracker.TrackerError?) {
                        log("smoke test onError: $trackerError")
                        if (cont.isActive) {
                            cont.resume(BatchResult.Failed(trackerError, "TrackerError=$trackerError"))
                        }
                    }
                })
            }
        }
    } catch (t: TimeoutCancellationException) {
        null
    } catch (e: HealthTrackerException) {
        BatchResult.Failed(null, describe(e))
    }

    fun disconnect() {
        runCatching { service?.disconnectService() }
        service = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SMOKE_TIMEOUT_MS = 20_000L
        const val ON_DEMAND_SECONDS = 35
        const val SCHEMA_SAMPLE = 200
    }
}
