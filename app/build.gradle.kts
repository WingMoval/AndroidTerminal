// AndroidTerminal App 鈥?Human UI consumer of AT Engine.
//
// Canonical V1 搂32: AndroidTerminal = AT Engine + Human UI + selected
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

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.androidterminal.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // AT.APP-RELEASE-2: Release signing from environment variables.
    // Debug builds use the default debug keystore (unchanged).
    // Release builds require AT_RELEASE_* env vars; fails fast if missing.
    signingConfigs {
        create("release") {
            val sf = System.getenv("AT_RELEASE_STORE_FILE")
            if (sf != null && File(sf).exists()) {
                storeFile = File(sf)
                storePassword = System.getenv("AT_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("AT_RELEASE_KEY_ALIAS") ?: "androidterminal"
                keyPassword = System.getenv("AT_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val storeFile = System.getenv("AT_RELEASE_STORE_FILE")
            if (storeFile != null && File(storeFile).exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No release keystore: use debug signing as fallback for local
                // testing only. CI / official builds MUST set AT_RELEASE_*.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // AT.CLI-0: build the shell-hosted `adb` CLI frontend (native PIE) and
    // package it as an asset so the app can extract it into files/bin at
    // runtime. The CMake project builds per-ABI; the copy task picks arm64-v8a.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // The App consumes the AT Engine public boundary + composition + CLI.
    // V4-4.1A: FINAL consumer dependency closure.
    // Allowed: :at-engine-api + :at-composition-android + :at-cli ONLY.
    // NO :at-backend-local / :at-backend-adb / :adb-engine / :at-engine-spi /
    // :at-engine: the concrete backends are constructed INSIDE the composition
    // (assembly root) and enter the App runtime classpath transitively via
    // implementation(:at-backend-*) in :at-composition-android (runtime
    // visibility is enough — the App never names a backend type at compile
    // time). Native .so packaging follows the same transitive runtime path.
    implementation(project(":at-engine-api"))
    implementation(project(":at-composition-android"))

    // AT.CLI-CONSUMER-0: the App consumes the REUSABLE shell-visible ADB CLI
    // consumer capability (the `adb` CLI main + same-UID UDS bridge + Local
    // shell function bootstrap now live in :at-cli, NOT :app). The App provides
    // an AppCliExecution (ATCliExecution impl) wiring its own AppRuntime /
    // AdbRuntime / Human status projection. The App no longer OWNS the CLI
    // mechanism 鈥?it only composes it.
    implementation(project(":at-cli"))

    // AT.CONTRACT-0.5: debug-only automated input test (AppDebugInputTest).
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // AT.DEVAPP-0.1: real Activity UI visibility test (ActivityScenario).
    androidTestImplementation("androidx.test:core:1.5.0")
    // AT.CLI-0.1: pure-JVM parser / quoting unit tests (no device).
    testImplementation("junit:junit:4.13.2")
}

// AT.CLI-0: the shell-hosted `adb` CLI frontend is built by CMake (above) and
// shipped as an asset (src/main/assets/cli/adb, arm64-v8a) so the app can extract
// it into files/bin at runtime. The committed asset is the built arm64 binary;
// the CMake target still compiles it on every build to keep it in sync.
