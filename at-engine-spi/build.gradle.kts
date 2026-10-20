plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Backend SPI depends on public API (for ATExecutionRequest / ATSessionLaunch / etc.)
    api(project(":at-engine-api"))
    
    testImplementation("junit:junit:4.13.2")
}
