plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode = providers
    .gradleProperty("VERSION_CODE")
    .orElse("1")
    .get()
    .toInt()

val appVersionName = providers
    .gradleProperty("VERSION_NAME")
    .orElse("0.1.0-dev")
    .get()

android {
    namespace = "com.archimedeprojects.volaflex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.archimedeprojects.volaflex"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
