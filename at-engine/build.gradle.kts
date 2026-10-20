plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.at.engine"
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
    // V4-1: Engine implementation depends on public API + backend SPI
    implementation(project(":at-engine-api"))
    implementation(project(":at-engine-spi"))
    
    testImplementation("junit:junit:4.13.2")
}
