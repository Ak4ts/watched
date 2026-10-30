package dev.watched.shared

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where probe output goes.
 *
 * Phase 0 writes to local disk only. The interface exists so that swapping to cloud object
 * storage at production time is one implementation and no call-site changes — see the plan's
 * "Storage during spikes" note. Do not grow this beyond what a blob store can honour.
 */
interface Sink {
    /** Append one record. Must be safe to call at 25 Hz. */
    fun write(envelope: Envelope)

    /** Persist the run summary. */
    fun writeReport(report: RunReport)

    /** Flush and release. */
    fun close()
}

internal val probeJson = Json {
    encodeDefaults = true
    prettyPrint = false
}

private val reportJson = Json {
    encodeDefaults = true
    prettyPrint = true
}

/**
 * Appends JSON Lines to `<root>/<runId>/<surface>.<type>.jsonl`, one file per type so that a
 * failure in one sweep cannot corrupt another, and so per-type byte counts fall out for free.
 */
class JsonlFileSink(private val root: File, private val runId: String) : Sink {

    private val writers = mutableMapOf<String, java.io.BufferedWriter>()
    private val byteCounts = mutableMapOf<String, Long>()

    private val runDir: File = File(root, runId).apply { mkdirs() }

    override fun write(envelope: Envelope) {
        val key = "${envelope.surface}.${envelope.type}"
        val writer = writers.getOrPut(key) {
            File(runDir, "$key.jsonl").bufferedWriter()
        }
        val line = probeJson.encodeToString(Envelope.serializer(), envelope)
        writer.write(line)
        writer.newLine()
        byteCounts[key] = (byteCounts[key] ?: 0L) + line.length + 1
    }

    /** Bytes written for one surface+type, for the volume findings. */
    fun bytesFor(surface: String, type: String): Long = byteCounts["$surface.$type"] ?: 0L

    override fun writeReport(report: RunReport) {
        File(runDir, "run-report.json")
            .writeText(reportJson.encodeToString(RunReport.serializer(), report))
    }

    override fun close() {
        writers.values.forEach { runCatching { it.flush(); it.close() } }
        writers.clear()
    }
}
