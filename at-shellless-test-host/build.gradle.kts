// AT.TERM-0 — Shellless Test Host (TEST / VALIDATION ONLY).
//
// Purpose: prove the AT Engine persistent terminal abstraction is a
// backend-neutral, shell-independent byte-stream session. This is a minimal
// Android application that depends ONLY on:
//     :at-engine
// plus its own validation-only native PTY/probe lib (libat_shellless_probe.so).
//
// It MUST NOT depend on :at-backend-local / :at-backend-adb / :adb-engine /
// :app. The child is a NON-shell interactive program (in-process native probe);
// /system/bin/sh is NEVER launched.
//
// NOT a production APK. NOT an AG project.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidterminal.shelllesshost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidterminal.shelllesshost"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

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
        }
    }
}

dependencies {
    // AT Engine ONLY. DELIBERATELY ABSENT: :at-backend-local, :at-backend-adb,
    // :adb-engine, :app (physical isolation gate AT.TERM-0 §36).
    // V4-1: TRANSITIONAL_DIRECT_DEPENDENCIES (will be removed in AT.V4-4)
    implementation(project(":at-engine-api"))
    implementation(project(":at-engine-spi"))
    implementation(project(":at-engine"))
    implementation(project(":at-backend-local"))

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
