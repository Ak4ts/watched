package dev.watched.probe.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.watched.shared.JsonlFileSink
import dev.watched.shared.RunReport
import dev.watched.shared.TypeReport
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 watch probe.
 *
 * Run order matters: button 1 first. If it reports POLICY BLOCKED, stop -- the raw-sensor
 * branch of the project is not available and the rest of these buttons are moot.
 *
 * Output:
 *   adb -s <watch> pull /sdcard/Android/data/dev.watched.probe.watch/files/dumps ./dumps-watch
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val runId: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val dumpRoot: File by lazy { File(getExternalFilesDir(null), "dumps") }
    private val sink: JsonlFileSink by lazy { JsonlFileSink(dumpRoot, runId) }
    private val probe: SensorProbe by lazy { SamsungSensorProbe(this, sink, runId) }
    // Google's API, for contrast: processed data but documented background collection.
    private val healthServices: HealthServicesProbe by lazy { HealthServicesProbe(this) }

    private val collected = mutableListOf<TypeReport>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (perm, granted) ->
            log("${perm.substringAfterLast('.')}: ${if (granted) "granted" else "DENIED"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            textSize = 10f
            setPadding(12, 12, 12, 12)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 40, 8, 40)
        }

        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { action() }
            })
        }

        button("0. Permissions") { requestPermissions() }
        button("1. POLICY SMOKE TEST") { runSmokeTest() }
        button("2. Enumerate trackers") { runEnumerate() }
        button("3. HR 60s") { runContinuous("HEART_RATE_CONTINUOUS", 60) }
        button("4. Accel 60s") { runContinuous("ACCELEROMETER_CONTINUOUS", 60) }
        button("5. PPG 60s") { runContinuous("PPG_CONTINUOUS", 60) }
        button("6. All continuous 60s") { runAllContinuous(60) }
        button("7. All on-demand") { runAllOnDemand() }
        button("8. Health Services inventory") { runHealthServices() }
        button("9. Write report") { writeReport() }

        content.addView(logView)
        scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        log("=== watched sensor probe, run $runId ===")
        log("watch: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Sensor SDK: ${if (BuildConfig.HAS_SENSOR_SDK) "AAR present" else "AAR ABSENT (stubbed)"}")
        log("dumps: ${dumpRoot.absolutePath}/$runId")
        log("")
        log("Run button 1 FIRST. Everything depends on it.")
    }

    private fun requestPermissions() {
        val needed = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) log("all runtime permissions already granted")
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun runSmokeTest() = lifecycleScope.launch {
        when (val result = probe.policySmokeTest { postLog(it) }) {
            is PolicyCheck.Ok ->
                collected += TypeReport(
                    SensorProbe.SURFACE, "policy-smoke-test",
                    available = true, permissionGranted = true,
                    notes = listOf("POLICY_OK", result.detail),
                )
            is PolicyCheck.PolicyBlocked ->
                collected += TypeReport(
                    SensorProbe.SURFACE, "policy-smoke-test",
                    available = false, permissionGranted = false,
                    error = "SDK_POLICY_ERROR: ${result.detail}",
                    notes = listOf("Surface C BLOCKED without partnership"),
                )
            is PolicyCheck.Failed ->
                collected += TypeReport(
                    SensorProbe.SURFACE, "policy-smoke-test",
                    available = false, permissionGranted = false,
                    error = result.detail,
                    notes = listOf("inconclusive — not a policy error"),
                )
        }
    }

    private fun runEnumerate() = lifecycleScope.launch {
        val names = probe.enumerateTrackers { postLog(it) }
        collected += TypeReport(
            SensorProbe.SURFACE, "tracker-enumeration",
            available = names.isNotEmpty(), permissionGranted = true,
            recordCount = names.size,
            notes = names.sorted(),
        )
    }

    private fun runContinuous(tracker: String, seconds: Int) = lifecycleScope.launch {
        collected += probe.sampleContinuous(tracker, seconds) { postLog(it) }
    }

    private fun runAllContinuous(seconds: Int) = lifecycleScope.launch {
        for (tracker in SensorProbe.CONTINUOUS) {
            collected += probe.sampleContinuous(tracker, seconds) { postLog(it) }
        }
        log("--- continuous sweep done ---")
    }

    private fun runAllOnDemand() = lifecycleScope.launch {
        // Samsung documents on-demand trackers as one-at-a-time and foreground-only, so these
        // run strictly in sequence. Keep the watch on the wrist and the screen on.
        for (tracker in SensorProbe.ON_DEMAND) {
            log("--- $tracker: keep still, ~35s ---")
            collected += probe.measureOnDemand(tracker) { postLog(it) }
        }
        log("--- on-demand sweep done ---")
    }

    private fun runHealthServices() = lifecycleScope.launch {
        log("--- Google Health Services ---")
        collected += healthServices.inventory { postLog(it) }
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
                    "sensorSdkPresent" to BuildConfig.HAS_SENSOR_SDK.toString(),
                    "sensorService" to probe.availability(),
                ),
                reports = collected.toList(),
            )
        )
        sink.close()
        log("report written. pull with:")
        log("  adb pull ${dumpRoot.absolutePath} ./dumps-watch")
    }

    private fun postLog(msg: String) = runOnUiThread { log(msg) }

    private fun log(msg: String) {
        logView.append(msg + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
