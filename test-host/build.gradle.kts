// Test Host — independent consumer of the AT Engine.
//
// Canonical V1 §19 / V2 precedent (:at-test-host): an independent Android
// application that consumes the AT Engine public boundary only, proving the
// engine is embeddable without any product UI. No launcher activity.
//
// AT.V3-0 bootstrap: empty placeholder application.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidterminal.testhost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidterminal.testhost"
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
    // Test Host consumes the AT Engine public boundary + backends.
    implementation(project(":at-engine"))
    implementation(project(":at-backend-local"))
    implementation(project(":at-backend-adb"))
}