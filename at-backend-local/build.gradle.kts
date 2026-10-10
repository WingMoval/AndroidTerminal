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

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // AT.PERSIST-0: Local PTY native (at_local_backend).
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
    // Local Backend implements AT Engine backend contracts.
    implementation(project(":at-engine"))

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}