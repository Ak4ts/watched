package dev.watched.probe.phone

import android.app.Activity
import dev.watched.shared.TypeReport

/**
 * Surface B, behind an interface so the app builds whether or not the Samsung Health Data SDK
 * AAR has been dropped into libs/. The real implementation lives in src/samsung/kotlin and a
 * stub in src/nosamsung/kotlin; probe-phone/build.gradle.kts picks one by detecting the AAR.
 */
interface SamsungProbe {
    /** Human-readable state of the Samsung Health platform, or why we cannot reach it. */
    fun availability(): String

    /** Opens Samsung Health's consent popup. Returns the permissions actually granted. */
    suspend fun requestPermissions(activity: Activity): Set<String>

    suspend fun sweep(daysBack: Long, log: (String) -> Unit): List<TypeReport>

    companion object {
        const val SURFACE = "samsung_data_sdk"
    }
}
