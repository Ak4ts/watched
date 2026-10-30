package dev.watched.probe.watch

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.concurrent.futures.await
import dev.watched.shared.TypeReport

/**
 * Google's Health Services on Wear OS — the complement to Samsung's Sensor SDK.
 *
 * Why this exists alongside `SamsungSensorProbe`: Samsung's documentation never says whether its
 * Sensor SDK can stream with the screen off and the app backgrounded, and that question decides
 * whether unattended collection is possible at all. Health Services *does* document passive
 * monitoring, so it is the fallback if Samsung's path turns out to be foreground-only.
 *
 * The trade-off is fidelity: Health Services delivers **processed** values (`HEART_RATE_BPM`,
 * steps, calories, distance) and never raw PPG, IBI or ECG. It does not replace the Sensor SDK
 * for anything HRV-related — it covers a different axis.
 *
 * This probe only inventories capability. It answers "what could we collect, and in which mode"
 * without committing to a collection design.
 */
class HealthServicesProbe(private val context: Context) {

    companion object {
        const val SURFACE = "health_services"
    }

    private val client: HealthServicesClient by lazy { HealthServices.getClient(context) }

    /**
     * Three separate capability surfaces, each answering a different question:
     *  - passive monitoring: what can be collected in the background, app closed
     *  - passive goals: what can fire a threshold event (the "wake me when X" primitive)
     *  - exercise: what is available during an active, foreground workout session
     */
    suspend fun inventory(log: (String) -> Unit): List<TypeReport> {
        val reports = mutableListOf<TypeReport>()

        reports += capability(
            label = "passive-monitoring",
            log = log,
        ) {
            val caps = client.passiveMonitoringClient.getCapabilitiesAsync().await()
            buildList {
                add("supportedDataTypesPassiveMonitoring=" +
                    caps.supportedDataTypesPassiveMonitoring.joinToString(",") { it.name })
                add("supportedDataTypesPassiveGoals=" +
                    caps.supportedDataTypesPassiveGoals.joinToString(",") { it.name })
                add("supportedUserActivityStates=" +
                    caps.supportedUserActivityStates.joinToString(","))
            }
        }

        reports += capability(
            label = "exercise",
            log = log,
        ) {
            val caps = client.exerciseClient.getCapabilitiesAsync().await()
            buildList {
                add("supportedExerciseTypes=" +
                    caps.supportedExerciseTypes.joinToString(",") { it.name })
                // Walking is a reasonable stand-in for "what a session gives us".
                caps.getExerciseTypeCapabilities(
                    androidx.health.services.client.data.ExerciseType.WALKING
                ).let { w ->
                    add("WALKING.supportedDataTypes=" +
                        w.supportedDataTypes.joinToString(",") { it.name })
                }
            }
        }

        reports += capability(
            label = "measure",
            log = log,
        ) {
            val caps = client.measureClient.getCapabilitiesAsync().await()
            listOf("supportedDataTypesMeasure=" +
                caps.supportedDataTypesMeasure.joinToString(",") { it.name })
        }

        return reports
    }

    private inline fun capability(
        label: String,
        log: (String) -> Unit,
        block: () -> List<String>,
    ): TypeReport = try {
        val notes = block()
        log("--- $label ---")
        notes.forEach { log("  $it") }
        TypeReport(
            surface = SURFACE,
            type = label,
            available = true,
            permissionGranted = true,
            notes = notes,
        )
    } catch (t: Throwable) {
        log("$label: ERROR ${t::class.simpleName}: ${t.message}")
        TypeReport(
            surface = SURFACE,
            type = label,
            available = false,
            permissionGranted = false,
            error = "${t::class.simpleName}: ${t.message}",
        )
    }
}
