package dev.watched.probe.phone

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.RunReport
import dev.watched.shared.TypeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Phase 0 phone probe. Deliberately ugly: this is a diagnostic tool, not a product.
 *
 * Output goes to the app's external files dir so it can be pulled without root:
 *   adb pull /sdcard/Android/data/dev.watched.probe.phone/files/dumps ./dumps
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val runId: String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private val dumpRoot: File by lazy { File(getExternalFilesDir(null), "dumps") }
    private val sink: JsonlFileSink by lazy { JsonlFileSink(dumpRoot, runId) }

    private val hc: HealthConnectProbe by lazy { HealthConnectProbe(this, sink, runId) }
    private val samsung: SamsungProbe by lazy { SamsungDataProbe(this, sink, runId) }

    private val collected = mutableListOf<TypeReport>()

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val requested = HealthConnectProbe.readPermissions() + BACKGROUND_PERM + HISTORY_PERM
        log("Health Connect: ${granted.size}/${requested.size} permissions granted")
        // What was refused matters as much as what was granted -- a silently refused type is
        // itself a row in findings.md.
        (requested - granted).sorted().forEach { log("  DENIED ${it.substringAfterLast('.')}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 0)
        }

        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        button("1. Status") { showStatus() }
        button("2. Request Health Connect permissions") {
            permissionLauncher.launch(
                HealthConnectProbe.readPermissions() + BACKGROUND_PERM + HISTORY_PERM
            )
        }
        button("3. Health Connect sweep (400d)") { runSweep(400) }
        button("3b. Health Connect sweep (30d)") { runSweep(30) }
        button("4. Probe history wall (400d)") { runHistoryWall() }
        button("5. Probe changes token") { runChangesToken() }
        button("6. Start background-read probe") { startBackgroundProbe() }
        button("7. Read background-read log") { readBackgroundLog() }
        button("8. Samsung Data SDK permissions") { runSamsungPermissions() }
        button("8b. Samsung Data SDK sweep (400d)") { runSamsungSweep(400) }
        button("9. Write run report") { writeReport() }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        })

        showStatus()
    }

    private fun showStatus() {
        log("=== watched probe, run $runId ===")
        log("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("dumps:  ${dumpRoot.absolutePath}/$runId")
        log("Health Connect: ${hc.availability()}")
        log("background read feature: ${hc.backgroundReadFeatureStatus()}")
        log("Samsung Data SDK: ${if (BuildConfig.HAS_SAMSUNG_DATA_SDK) "AAR present" else "AAR ABSENT (stubbed)"}")
        lifecycleScope.launch {
            val granted = runCatching { hc.grantedPermissions() }.getOrDefault(emptySet())
            log("granted HC permissions: ${granted.size}")
        }
    }

    private fun runSweep(days: Long) = lifecycleScope.launch {
        log("--- Health Connect sweep, last $days days ---")
        log("    (window opens ${java.time.LocalDate.now().minusDays(days)})")
        val reports = withContext(Dispatchers.IO) { hc.sweep(days) { msg -> postLog(msg) } }
        collected += reports
        val withData = reports.count { it.available }
        log("--- done: $withData/${reports.size} types returned data ---")
        if (withData == 0) {
            // Distinguish "nothing synced" from "nothing in range" -- they need different fixes.
            log("    all empty. Either Samsung Health is not connected to Health Connect,")
            log("    or it only syncs forward from the moment you connect it and never backfills.")
        }
    }

    private fun runHistoryWall() = lifecycleScope.launch {
        collected += withContext(Dispatchers.IO) { hc.probeHistoryWall { postLog(it) } }
    }

    private fun runChangesToken() = lifecycleScope.launch {
        collected += withContext(Dispatchers.IO) { hc.probeChangesToken { postLog(it) } }
    }

    private fun runSamsungPermissions() = lifecycleScope.launch {
        log("--- Samsung Data SDK: ${samsung.availability()} ---")
        val granted = runCatching { samsung.requestPermissions(this@MainActivity) }
            .getOrElse { t ->
                log("requestPermissions threw ${t::class.simpleName}: ${t.message}")
                emptySet()
            }
        log("Samsung granted ${granted.size} type(s)")
        granted.sorted().forEach { log("  $it") }
        if (granted.isEmpty()) {
            log("Zero granted. Most likely Developer Mode for Data Read is OFF:")
            log("  Samsung Health > ... > Settings > About Samsung Health")
            log("  > tap version 10x > Developer mode > Developer Mode for Data Read")
        }
    }

    private fun runSamsungSweep(days: Long) = lifecycleScope.launch {
        log("--- Samsung Data SDK: ${samsung.availability()} ---")
        val reports = runCatching {
            withContext(Dispatchers.IO) { samsung.sweep(days) { postLog(it) } }
        }.getOrElse { t ->
            log("Samsung sweep threw ${t::class.simpleName}: ${t.message}")
            emptyList()
        }
        collected += reports
    }

    private fun startBackgroundProbe() {
        // 15 minutes is WorkManager's floor for periodic work; nothing shorter is honoured.
        val request = PeriodicWorkRequestBuilder<BackgroundReadWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BackgroundReadWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
        log("background-read probe enqueued (every 15 min).")
        log("Now background the app and leave it. Check button 7 in an hour.")
    }

    private fun readBackgroundLog() {
        val f = File(getExternalFilesDir(null), BackgroundReadWorker.LOG_NAME)
        if (!f.exists()) { log("no background-read log yet"); return }
        log("--- ${BackgroundReadWorker.LOG_NAME} ---")
        f.readLines().takeLast(20).forEach { log("  $it") }
    }

    private fun writeReport() {
        sink.writeReport(
            RunReport(
                runId = runId,
                startedAt = 0,
                finishedAt = System.currentTimeMillis(),
                device = mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "androidRelease" to Build.VERSION.RELEASE,
                    "sdkInt" to Build.VERSION.SDK_INT.toString(),
                    "healthConnect" to hc.availability(),
                    "backgroundReadFeature" to hc.backgroundReadFeatureStatus(),
                    "samsungDataSdkPresent" to BuildConfig.HAS_SAMSUNG_DATA_SDK.toString(),
                ),
                reports = collected.toList(),
            )
        )
        sink.close()
        log("report written: ${dumpRoot.absolutePath}/$runId/run-report.json")
        log("pull with:")
        log("  adb pull ${dumpRoot.absolutePath} ./dumps")
    }

    private fun postLog(msg: String) = runOnUiThread { log(msg) }

    private fun log(msg: String) {
        logView.append(msg + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        const val BACKGROUND_PERM = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        const val HISTORY_PERM = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    }
}
