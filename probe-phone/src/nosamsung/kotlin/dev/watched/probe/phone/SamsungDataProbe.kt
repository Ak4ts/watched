package dev.watched.probe.phone

import android.app.Activity
import android.content.Context
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.TypeReport

/**
 * Compiled when libs/samsung-health-data-api.aar is absent, so the Health Connect sweep stays
 * runnable without first obtaining the Samsung SDK. See docs/access-gates.md section B.
 */
class SamsungDataProbe(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") sink: JsonlFileSink,
    @Suppress("UNUSED_PARAMETER") runId: String,
) : SamsungProbe {

    private val why =
        "Samsung Health Data SDK AAR not present. Copy samsung-health-data-api.aar into libs/ " +
            "and re-sync. Run ./gradlew :probe-phone:samsungSdkStatus to check."

    override fun availability() = "STUB — $why"

    override suspend fun requestPermissions(activity: Activity): Set<String> = emptySet()

    override suspend fun sweep(daysBack: Long, log: (String) -> Unit): List<TypeReport> {
        log(why)
        return listOf(
            TypeReport(
                surface = SamsungProbe.SURFACE,
                type = "<sdk-absent>",
                available = false,
                permissionGranted = false,
                error = why,
            )
        )
    }
}
