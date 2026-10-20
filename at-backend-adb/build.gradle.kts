// ADB Backend adapter — translates AT Engine contracts to ADB execution.
//
// Canonical V1 §8 / §7.1: ADB Backend implements the same thin interactive
// contract (open/read/write/resize/close/events) plus one-shot, translating
// to ADB interactive stream / shell-v2 frames, consuming ADB Engine as the
// provider. principal = shell UID / shell domain (V2 ADB.I0 verified).
//
// AT.V3-0 bootstrap: empty placeholder. Content migrates from V2
// AdbInteractiveStream (PROVISIONAL native) + AdbClient one-shot during
// AT.V3-1 / Track A-B.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.at.backend.adb"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    // ADB Backend implements AT Engine contracts and consumes ADB Engine.
    // V4-1: ADB backend depends on public API + backend SPI + ADB engine
    implementation(project(":at-engine-api"))
    implementation(project(":at-engine-spi"))
    implementation(project(":adb-engine"))
}