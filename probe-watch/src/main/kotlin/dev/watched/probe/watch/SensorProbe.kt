package dev.watched.probe.watch

import dev.watched.shared.TypeReport

/** Outcome of the one test that gates the entire raw-sensor branch of the project. */
sealed interface PolicyCheck {
    /** Developer mode is enough. Surface C is open. */
    data class Ok(val detail: String) : PolicyCheck
    /** SDK_POLICY_ERROR. Raw sensors need a Samsung partnership after all. */
    data class PolicyBlocked(val detail: String) : PolicyCheck
    /** Something else went wrong -- not connected, no permission, service missing. */
    data class Failed(val detail: String) : PolicyCheck
}

/**
 * Surface C, behind an interface so the module builds without the Sensor SDK AAR.
 * Real implementation in src/samsung/kotlin, stub in src/nosamsung/kotlin.
 */
interface SensorProbe {
    fun availability(): String

    /**
     * THE critical test. Starts one HEART_RATE_CONTINUOUS tracker and reports whether
     * SDK_POLICY_ERROR fires in developer mode.
     *
     * Samsung documents the policy scope check but never says whether it applies to developer
     * mode. If it does, there is no raw PPG, no accelerometer, no ECG and no IBI without a
     * partnership, and the project falls back to Surfaces A and B. Run this before anything else.
     */
    suspend fun policySmokeTest(log: (String) -> Unit): PolicyCheck

    /** Which trackers this watch actually exposes. Expect EDA and MF-BIA to be absent on a Watch 7. */
    suspend fun enumerateTrackers(log: (String) -> Unit): List<String>

    /** Stream one continuous tracker for [seconds], measuring achieved rate and byte volume. */
    suspend fun sampleContinuous(tracker: String, seconds: Int, log: (String) -> Unit): TypeReport

    /** Run one on-demand measurement and dump the full sample array. */
    suspend fun measureOnDemand(tracker: String, log: (String) -> Unit): TypeReport

    companion object {
        const val SURFACE = "samsung_sensor_sdk"

        /** From docs/capability-matrix.md section 4. */
        val CONTINUOUS = listOf(
            "HEART_RATE_CONTINUOUS",
            "ACCELEROMETER_CONTINUOUS",
            "PPG_CONTINUOUS",
            "SKIN_TEMPERATURE_CONTINUOUS",
            // Documented as Watch 8+. Listed so the probe can confirm it is genuinely absent
            // here rather than us assuming it from a docs note.
            "EDA_CONTINUOUS",
        )

        val ON_DEMAND = listOf(
            "ECG_ON_DEMAND",
            "PPG_ON_DEMAND",
            "BIA_ON_DEMAND",
            "SPO2_ON_DEMAND",
            "SKIN_TEMPERATURE_ON_DEMAND",
            "MF_BIA_ON_DEMAND", // Watch 8+; confirm absence.
        )
    }
}
