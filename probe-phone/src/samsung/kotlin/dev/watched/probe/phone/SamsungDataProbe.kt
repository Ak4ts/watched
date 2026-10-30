package dev.watched.probe.phone

import android.app.Activity
import android.content.Context
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.TypeReport

/**
 * Surface B: Samsung Health Data SDK v1.1.0.
 *
 * Compiled only when libs/samsung-health-data-api.aar is present.
 *
 * ============================= READ THIS BEFORE EDITING =============================
 * The permission and error-handling flow below is taken from Samsung's documented sample
 * and should be correct. The *read* call is NOT: Samsung's read-data reference page was not
 * available during research, so the request-building shape marked UNVERIFIED below is a best
 * guess at the API, not a transcription of it.
 *
 * The SDK zip ships its own API reference. Before the first run: open it, confirm the read
 * request builder, time filter and result accessor, and fix `readType` accordingly. The
 * compiler will reject a wrong guess the moment the AAR lands, so this cannot ship silently
 * broken -- but do not assume it is right.
 * ====================================================================================
 */
class SamsungDataProbe(
    private val context: Context,
    private val sink: JsonlFileSink,
    private val runId: String,
) : SamsungProbe {

    /**
     * Every readable type from docs/capability-matrix.md section 3, by its DataTypes constant
     * name. Resolved reflectively so that a constant renamed between SDK versions degrades to
     * one missing row instead of a build failure.
     *
     * STEPS and SLEEP_GOAL are included deliberately: Samsung documents them as aggregate-only
     * yet their own permission sample requests READ on both. Settling that contradiction is
     * question 5 in the capability matrix.
     */
    private val typeNames = listOf(
        "HEART_RATE", "STEPS", "SLEEP", "SLEEP_GOAL", "BLOOD_OXYGEN", "BLOOD_GLUCOSE",
        "BLOOD_PRESSURE", "BODY_COMPOSITION", "BODY_TEMPERATURE", "EXERCISE", "FLOORS_CLIMBED",
        "NUTRITION", "WATER_INTAKE", "USER_PROFILE",
        // B-exclusive: these exist on no other surface, so they are the reason this SDK is worth its gate.
        "ENERGY_SCORE", "SKIN_TEMPERATURE", "SLEEP_APNEA", "IRREGULAR_HEART_RHYTHM_NOTIFICATION",
        // Aggregates.
        "ACTIVITY_SUMMARY", "STEP_GOAL", "ACTIVE_TIME_GOAL", "ACTIVE_CALORIES_BURNED_GOAL",
        "NUTRITION_GOAL", "WATER_INTAKE_GOAL",
    )

    override fun availability(): String = TODO(
        "Call HealthDataService.getStore(context) and report reachability. " +
            "Map ResolvablePlatformException error codes per docs/access-gates.md section B: " +
            "ERR_PLATFORM_NOT_INSTALLED, ERR_OLD_VERSION_PLATFORM, ERR_PLATFORM_DISABLED, " +
            "ERR_PLATFORM_NOT_INITIALIZED."
    )

    override suspend fun requestPermissions(activity: Activity): Set<String> = TODO(
        "store.getGrantedPermissions(permSet), then store.requestPermissions(permSet, activity) " +
            "for the shortfall. Build permSet as typeNames.map { Permission.of(DataTypes.<it>, AccessType.READ) }. " +
            "Handle ResolvablePlatformException via hasResolution / resolve(activity)."
    )

    override suspend fun sweep(daysBack: Long, log: (String) -> Unit): List<TypeReport> = TODO(
        "For each type: read over the window, dump each record through reflectToJson into the " +
            "sink under surface=${SamsungProbe.SURFACE}, and build a TypeReport exactly as " +
            "HealthConnectProbe.readType does. UNVERIFIED request shape -- check the SDK's own " +
            "API reference first."
    )
}
