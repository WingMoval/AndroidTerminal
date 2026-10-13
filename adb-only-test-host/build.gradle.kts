// ADB-only Test Host — TEST / VALIDATION ONLY (AT.V3-2.3).
//
// Purpose: prove the PHYSICAL ADB-only composition (Goal A). This is a minimal
// Android application that depends ONLY on:
//     :at-engine + :at-backend-adb + :adb-engine
// and MUST NOT depend on :at-backend-local / :app / any Local PTY native lib.
//
// It is NOT a production APK and NOT an AG project. It has two paths:
//   (A) Host / Composition lifecycle  -> AdbRuntime (ADB-domain)
//   (B) business execution            -> ATEngine -> ADB Backend -> ADB Engine
//
// Physical isolation gate (AT.V3-2.3 §10) checks the Gradle dependency graph,
// APK dex contents, and native libs of THIS module's APK.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidterminal.adbonlyhost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidterminal.adbonlyhost"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ADB-only composition: AT Engine + ADB Backend + ADB Engine.
    // DELIBERATELY ABSENT: :at-backend-local, :app (Local Backend isolation).
    implementation(project(":at-engine"))
    implementation(project(":at-backend-adb"))
    implementation(project(":adb-engine"))

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
