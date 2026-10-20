// AT Composition Android — Android assembly root (V4-2).
//
// This module provides the Android-specific composition root that wires:
//   - AdbRuntime initialization
//   - Identity directory binding
//   - Local backend construction
//   - ADB backend construction
//   - DefaultATEngine construction
//
// Provider Wiring Model = A (direct dependency on :adb-engine)
//
// This is an ASSEMBLY ROOT, NOT a new lifecycle owner.
// AdbRuntime remains the UNIQUE lifecycle owner.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.at.composition.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    // V4-2: Composition root dependencies
    api(project(":at-engine-api"))
    api(project(":at-engine-spi"))
    implementation(project(":at-engine"))
    implementation(project(":at-backend-local"))
    implementation(project(":at-backend-adb"))
    
    // Provider Wiring Model = A: direct dependency on :adb-engine
    implementation(project(":adb-engine"))
    
    testImplementation("junit:junit:4.13.2")
}
