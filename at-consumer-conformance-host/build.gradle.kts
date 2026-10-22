// :at-consumer-conformance-host — Reference Consumer + Conformance Fixture (AT.V4-5).
//
// This is NOT a new Product. It is a REFERENCE CONSUMER that proves a fully
// legal consumer — depending ONLY on the V4 public Consumer Boundary — can
// complete the full AndroidTerminal surface:
//
//     Local one-shot / Local persistent
//     ADB one-shot / ADB persistent
//     Readiness / Bootstrap / Identity semantic
//     CLI shell-visible adb
//
// ALLOWED DIRECT DEPENDENCIES (the frozen V4 Consumer Boundary):
//     :at-engine-api           (REQUIRED — public execution/session contract)
//     :at-composition-android  (REQUIRED — Android assembly root + ADB semantic facade)
//     :at-cli                  (OPTIONAL — CLI frontend)
//
// FORBIDDEN DIRECT DEPENDENCIES (enforced by :verifyConsumerArchitecture):
//     :at-engine / :at-engine-spi / :at-backend-local / :at-backend-adb / :adb-engine
//
// The host COMPOSES (ATAndroid.create) + CONSUMES (ATEngine / adbControl). It
// does NOT reimplement AT Engine / backend / ADB lifecycle / pairing / recovery
// / PTY. It owns its OWN app-private identity (filesDir/conformance-identity,
// self-generated) — independent from Product and ConsumerHost identities.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidterminal.conformance"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidterminal.conformance"
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
    // The FROZEN V4 Consumer Boundary. NOTHING ELSE.
    // DELIBERATELY ABSENT: :at-engine / :at-engine-spi / :at-backend-local /
    // :at-backend-adb / :adb-engine (CONSUMER_BOUNDARY_VIOLATION if present).
    implementation(project(":at-engine-api"))
    implementation(project(":at-composition-android"))
    implementation(project(":at-cli"))

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
