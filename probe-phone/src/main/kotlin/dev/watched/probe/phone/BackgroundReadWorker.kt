package dev.watched.probe.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Settles question 6: do Health Connect background reads actually work?
 *
 * Google's own docs contradict each other -- the sync guide says foreground-only, the read guide
 * documents this exact WorkManager pattern. Whichever is true decides whether `watched` can
 * collect unattended or has to be a foreground app, so we test it rather than pick a side.
 *
 * Appends one line per attempt to background-read-log.txt. Leave it running, background the app,
 * and read the file later: if the lines keep appearing with non-zero counts, background reads work.
 */
class BackgroundReadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val logFile = File(applicationContext.getExternalFilesDir(null), LOG_NAME)
        val stamp = Instant.now().toString()

        val line = try {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val end = Instant.now()
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(end.minus(1, ChronoUnit.DAYS), end),
                    pageSize = 10,
                )
            )
            "$stamp OK records=${response.records.size}"
        } catch (t: Throwable) {
            // A SecurityException here is the answer: background reads are not permitted.
            "$stamp FAIL ${t::class.simpleName}: ${t.message}"
        }

        logFile.appendText(line + "\n")
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "watched_background_read_probe"
        const val LOG_NAME = "background-read-log.txt"
    }
}
