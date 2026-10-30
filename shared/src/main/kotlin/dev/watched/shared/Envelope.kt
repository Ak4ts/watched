package dev.watched.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One line of probe output.
 *
 * Deliberately schema-light: the whole point of Phase 0 is that we do NOT yet know what fields
 * Samsung returns, so the payload stays an opaque [JsonElement] and we discover its shape from
 * the dumps rather than asserting it up front.
 */
@Serializable
data class Envelope(
    /** Which surface produced this: "health_connect", "samsung_data_sdk", "samsung_sensor_sdk". */
    val surface: String,
    /** Type name as the surface itself calls it, e.g. "HeartRateRecord" or "PPG_CONTINUOUS". */
    val type: String,
    /** Probe run that produced this line, so findings.md can cite it. */
    val runId: String,
    /** Wall-clock capture time, epoch millis. Not the record's own timestamp. */
    val capturedAt: Long,
    /** The record exactly as the surface gave it, with no normalisation. */
    val payload: JsonElement,
)

/**
 * Per-type result of a probe sweep. This is what becomes a row in docs/findings.md.
 */
@Serializable
data class TypeReport(
    val surface: String,
    val type: String,
    /** Did we get a usable response at all? */
    val available: Boolean,
    /** Permission actually granted, as opposed to requested. */
    val permissionGranted: Boolean,
    val recordCount: Int = 0,
    /** Epoch millis of the oldest and newest record seen. */
    val oldestRecord: Long? = null,
    val newestRecord: Long? = null,
    /** Median gap between consecutive records, millis. Tells us the real cadence. */
    val medianIntervalMs: Long? = null,
    /** Field name -> how many records had it non-null. Reveals the real schema. */
    val fieldPresence: Map<String, Int> = emptyMap(),
    /** Serialised size of everything dumped for this type. Feeds the volume estimate. */
    val bytes: Long = 0,
    /** Exception class and message if the read failed. The error IS the finding. */
    val error: String? = null,
    val notes: List<String> = emptyList(),
)

/** Everything one probe run learned. */
@Serializable
data class RunReport(
    val runId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val device: Map<String, String>,
    val reports: List<TypeReport>,
)
