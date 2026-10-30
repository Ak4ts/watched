plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The Samsung Health Data SDK is shipped as a loose AAR with no Gradle coordinates (see
// docs/access-gates.md). Rather than make the whole project unbuildable until someone has
// downloaded it, we detect it and swap in a stub source set when it is absent. That keeps the
// Health Connect sweep runnable on day one.
val samsungDataAar = rootProject.file("libs/samsung-health-data-api.aar")
val hasSamsungDataSdk = samsungDataAar.exists()

android {
    namespace = "dev.watched.probe.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.watched.probe.phone"
        // Samsung Health Data SDK floor.
        minSdk = 29
        // Deliberately NOT 36: targeting Android 16+ pulls in Samsung's
        // READ_ADDITIONAL_HEALTH_DATA requirement. Keep the probe on the simpler gate first,
        // then bump and re-measure.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-probe"
        buildConfigField("boolean", "HAS_SAMSUNG_DATA_SDK", hasSamsungDataSdk.toString())
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].kotlin.srcDir(
        if (hasSamsungDataSdk) "src/samsung/kotlin" else "src/nosamsung/kotlin"
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug {
            // Probe builds are debug-signed on purpose: both Samsung SDKs only accept an
            // unregistered signature while developer mode is on.
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
    implementation(libs.health.connect)
    implementation(libs.androidx.work)
    implementation(libs.play.wearable)

    // The Samsung AARs ship as loose files with no POM, so none of their transitive
    // dependencies are declared. Scanning the bytecode shows exactly three external packages:
    // kotlinx.parcelize, kotlinx.coroutines (already present) and gson. Without these the SDK
    // dies at runtime with NoClassDefFoundError, not at compile time.
    if (hasSamsungDataSdk) {
        implementation(files(samsungDataAar))
        implementation(libs.kotlin.parcelize.rt)
        implementation(libs.gson)
    }
}

tasks.register("samsungSdkStatus") {
    group = "watched"
    description = "Reports whether the Samsung Health Data SDK AAR has been dropped into libs/."
    doLast {
        if (hasSamsungDataSdk) {
            println("Samsung Health Data SDK: PRESENT at ${samsungDataAar.path}")
        } else {
            println("Samsung Health Data SDK: ABSENT. Surface B sweep is stubbed out.")
            println("  Download the SDK zip and copy libs/samsung-health-data-api.aar to ${samsungDataAar.path}")
        }
    }
}
