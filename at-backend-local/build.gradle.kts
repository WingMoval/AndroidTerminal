// Local Backend adapter — translates AT Engine contracts to local execution.
//
// Canonical V1 §8: Local Backend converts write(bytes)/resize/close into
// write(fd)/ioctl(TIOCSWINSZ)/close+waitpid on a local PTY / process.
// principal = App UID / untrusted_app (V2 ATsh.T1 verified).
//
// AT.V3-0 bootstrap: empty placeholder. Content migrates from V2 :atsh
// LocalPtySession / PtyNative (PROVISIONAL evidence code) during Track B.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.at.backend.local"
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
    // Local Backend implements AT Engine backend contracts.
    implementation(project(":at-engine"))
}