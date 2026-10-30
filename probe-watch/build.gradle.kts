plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Same conditional-AAR pattern as :probe-phone. The Samsung Health Sensor SDK ships as a loose
// AAR gated behind a download, so the module builds (stubbed) without it.
val sensorAar = rootProject.file("libs/samsung-health-sensor-api.aar")
val hasSensorSdk = sensorAar.exists()

android {
    namespace = "dev.watched.probe.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.watched.probe.watch"
        // Wear OS 3+ (Galaxy Watch4 and later, which is the Sensor SDK's own floor).
        minSdk = 30
        // NOT 36 on purpose: targeting Android 16+ brings in Samsung's
        // READ_ADDITIONAL_HEALTH_DATA requirement (Sensor SDK v1.4.0). Establish a baseline
        // on the simpler gate, then bump and re-measure.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-probe"
        buildConfigField("boolean", "HAS_SENSOR_SDK", hasSensorSdk.toString())
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].kotlin.srcDir(
        if (hasSensorSdk) "src/samsung/kotlin" else "src/nosamsung/kotlin"
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug {
            // Debug-signed on purpose: the Sensor SDK only accepts an unregistered signature
            // while watch developer mode is on. See docs/access-gates.md section C.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    implementation(libs.androidx.wear)
    // Google's Wear OS health API. Unlike Samsung's Sensor SDK it is a plain Gradle
    // dependency, and it is the documented route to *passive* background collection --
    // which is the open question the Samsung SDK's docs never answer.
    implementation(libs.health.services)
    // Health Services returns ListenableFuture; this supplies it and `await()`.
    implementation(libs.concurrent.futures)
    // androidx pulls in guava's EMPTY listenablefuture placeholder
    // (9999.0-empty-to-avoid-conflict-with-guava), so ListenableFuture has no
    // class file unless real guava is present. Health Services returns it.
    implementation(libs.guava)
    implementation(libs.play.wearable)

    // The Samsung AARs ship as loose files with no POM, so none of their transitive
    // dependencies are declared. Scanning the bytecode shows exactly three external packages:
    // kotlinx.parcelize, kotlinx.coroutines (already present) and gson. Without these the SDK
    // dies at runtime with NoClassDefFoundError, not at compile time.
    if (hasSensorSdk) {
        implementation(files(sensorAar))
        implementation(libs.kotlin.parcelize.rt)
        implementation(libs.gson)
    }
}

tasks.register("sensorSdkStatus") {
    group = "watched"
    description = "Reports whether the Samsung Health Sensor SDK AAR has been dropped into libs/."
    doLast {
        if (hasSensorSdk) {
            println("Samsung Health Sensor SDK: PRESENT at ${sensorAar.path}")
        } else {
            println("Samsung Health Sensor SDK: ABSENT. Surface C probe is stubbed out.")
            println("  Download the SDK zip and copy the AAR to ${sensorAar.path}")
        }
    }
}
