// AT Engine — reusable terminal execution capability.
//
// Canonical V1: AT Engine owns one-shot execution + persistent terminal
// (TerminalSession / Terminal Emulator / Screen State / lifecycle) +
// backend abstraction. It must NOT depend on Android UI, ADB protocol, or a
// specific shell.
//
// AT.V3-0 bootstrap: empty placeholder module. Real contracts arrive via
// AT.CONTRACT-0 (minimum contract v0 draft) after bootstrap. Names PROVISIONAL.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.at.engine"
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
    // AT Engine depends on NO concrete backend and NO Android UI.
    // It defines only the execution / terminal domain contracts.

    testImplementation("junit:junit:4.13.2")
}