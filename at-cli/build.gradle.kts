// :at-cli — Reusable Shell-Visible ADB CLI Consumer Capability (AT.CLI-CONSUMER-0).
//
// Canonical V1: the shell-visible `adb` CLI (app_process entrypoint + same-UID
// UDS bridge) is a REUSABLE AT CONSUMER CAPABILITY, NOT an AndroidTerminal
// :app product feature. Any embedding consumer (product app, test host, future
// AG adapter host) can:
//
//   1. build its own AT composition (ATEngine + selected backends);
//   2. start the CLI bridge (UDS, app-private files dir);
//   3. inject the shell-visible `adb` function into a Local /system/bin/sh
//      (via LocalCliBootstrap);
//   4. have `adb shell ...` route back into the SAME shared AT / AdbRuntime
//      composition (no second AdbRuntime, no second AT Engine, no external adb).
//
// Module boundary (AT.CLI-CONSUMER-0 §8/§11):
//   - DEPENDS on :at-backend-local / :at-backend-adb / :adb-engine (transitively
//     :at-engine). Does NOT depend on :app.
//   - The bridge server depends on the ATCliExecution SPI (host-provided),
//     NOT on any AndroidTerminal app concrete class.
//   - Host UI (pairing code Activity, product status projection) stays in :app.
//
// Provisional consumer boundary: CLI_CONSUMER_API_STATUS = PROVISIONAL / DRAFT.
// This is NOT a final AT public API freeze (AT_CONTRACT_STATUS = DRAFT).

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.at.cli"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // The CLI bridge routes one-shot / interactive into a host-provided
    // ATEngine (ADB backend). Local bootstrap needs the Local backend package
    // only for the host to build its Local terminal (the CLI module itself
    // does not create Local sessions — the host does).
    implementation(project(":at-engine"))
    implementation(project(":at-backend-local"))
    implementation(project(":at-backend-adb"))
    implementation(project(":adb-engine"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
