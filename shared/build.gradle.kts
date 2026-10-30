plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.watched.shared"
    compileSdk = 35

    defaultConfig {
        // Samsung Health Data SDK requires Android 10 (API 29) or later.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines)
    // The whole probe strategy is "we don't know the schema, so reflect it".
    implementation(kotlin("reflect"))
}
