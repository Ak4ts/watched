package dev.watched.probe.watch

import android.content.Context
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.TypeReport

/** Compiled when libs/samsung-health-sensor-api.aar is absent. */
class SamsungSensorProbe(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") sink: JsonlFileSink,
    @Suppress("UNUSED_PARAMETER") runId: String,
) : SensorProbe {

    private val why =
        "Samsung Health Sensor SDK AAR not present. Copy samsung-health-sensor-api.aar into " +
            "libs/ and re-sync. Check with ./gradlew :probe-watch:sensorSdkStatus"

    override fun availability() = "STUB — $why"

    override suspend fun policySmokeTest(log: (String) -> Unit): PolicyCheck {
        log(why)
        return PolicyCheck.Failed(why)
    }

    override suspend fun enumerateTrackers(log: (String) -> Unit): List<String> {
        log(why); return emptyList()
    }

    override suspend fun sampleContinuous(tracker: String, seconds: Int, log: (String) -> Unit) =
        stubReport(tracker, log)

    override suspend fun measureOnDemand(tracker: String, log: (String) -> Unit) =
        stubReport(tracker, log)

    private fun stubReport(tracker: String, log: (String) -> Unit): TypeReport {
        log(why)
        return TypeReport(
            surface = SensorProbe.SURFACE,
            type = tracker,
            available = false,
            permissionGranted = false,
            error = why,
        )
    }
}
