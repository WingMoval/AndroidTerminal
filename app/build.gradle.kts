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
    // The App does NOT depend on :adb-engine directly — ADB reaches it only
    // through the ADB Backend (Canonical V1 §7.1 consumer-provider rule).
}