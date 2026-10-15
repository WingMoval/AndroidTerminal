// AndroidTerminal App — Human UI consumer of AT Engine.
//
// Canonical V1 §32: AndroidTerminal = AT Engine + Human UI + selected
// Backends/Providers. Human UI owns Android rendering (outside AT Engine);
// the App is a consumer of the AT Engine public boundary.
//
// AT.V3-0 bootstrap: empty placeholder application. UI work arrives via
// AT.HUMAN-* track.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidterminal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidterminal.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.3.0"

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
    // The App consumes the AT Engine public boundary and selected backends.
    implementation(project(":at-engine"))
    implementation(project(":at-backend-local"))
    implementation(project(":at-backend-adb"))
    // AT.HUMAN-ADB-0: the App composes the ADB lifecycle (AdbRuntime) as the
    // authoritative ADB connection owner (Canonical V1 §7.2; AT.V3-2.3
    // composition). Terminal execution still goes ONLY through ATEngine ->
    // ADB Backend -> ADB Engine.
    implementation(project(":adb-engine"))

    // AT.CONTRACT-0.5: debug-only automated input test (AppDebugInputTest).
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // AT.DEVAPP-0.1: real Activity UI visibility test (ActivityScenario).
    androidTestImplementation("androidx.test:core:1.5.0")
}