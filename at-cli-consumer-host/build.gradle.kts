// :at-cli-consumer-host 鈥?Independent CLI Consumer Test Host (AT.CLI-CONSUMER-0).
//
// TEST / VALIDATION ONLY. Proves a NON-AndroidTerminal consumer can obtain the
// full "Local Terminal -> shell-visible adb -> uid=2000(shell)" environment
// using ONLY:
//     :at-cli + :at-engine + :at-backend-local + :at-backend-adb + :adb-engine
// and MUST NOT depend on :app or copy the AndroidTerminal identity.
//
// This host:
//   - builds its OWN AT composition (Local + ADB backends);
//   - owns its OWN AdbRuntime identity (filesDir/at-cli-identity, self-generated);
//   - starts the :at-cli bridge (same-UID UDS) and injects the shell-visible
//     `adb` function into a Local /system/bin/sh;
//   - provides its own ATCliExecution (no :app class).
//
// It is NOT a production APK and NOT an AG project.
//
// Physical isolation gate (AT.CLI-CONSUMER-0 搂25): the Gradle dependency graph,
// APK dex contents, and native libs of THIS module's APK MUST NOT reference
// :app (com.androidterminal.app.*) or the AndroidTerminal identity path.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidterminal.atclihost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidterminal.atclihost"
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
    // Reusable CLI consumer capability + AT composition.
    // DELIBERATELY ABSENT: :app (consumer isolation gate 搂25).
    // V4-4: Consumer boundary closure
    // Allowed: :at-engine-api + :at-composition-android + :at-cli
    implementation(project(":at-cli"))
    implementation(project(":at-engine-api"))
    implementation(project(":at-composition-android"))
    
    // TRANSITIONAL: :adb-engine needed for AdbRuntimeState/AdbConsumerBootstrap types
    // (will be removed when these are projected in :at-composition-android)
    implementation(project(":adb-engine"))

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

