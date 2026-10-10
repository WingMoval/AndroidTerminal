// ADB Engine — independent ADB capability provider.
//
// Canonical V1: ADB Engine owns pairing / identity / key-trust / discovery /
// endpoint / TLS / connection / protocol / shell-v2 / interactive stream /
// recovery / diagnostics. It must be independently usable without any AT
// Engine or Terminal component.
//
// AT.V3-0 bootstrap: empty placeholder module. Real content arrives via
// AT.V3-1 (ADB Engine Extraction / Migration) from V2 verified source.
// Package / API names are PROVISIONAL.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adb.engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // AT.V3-1: ADB Engine native (adbd_engine).
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
    // ADB Engine has NO dependency on :at-engine, :at-backend-*, or any UI.

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}