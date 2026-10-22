// :at-architecture-gate — Automated Architecture Enforcement (AT.V4-5).
//
// PURE JVM/Kotlin module (no Android). Implements MACHINE-ENFORCED
// architecture gates that upgrade the SSOT-documented V4 Consumer Boundary
// from "documentation rules" to "automated FAIL-on-violation":
//
//   A — Direct consumer dependency gate (forbidden project deps)
//   B — Consumer source import boundary (forbidden implementation imports)
//   C — :at-engine-api public surface leak (Context / AdbRuntime / backends)
//   D — Module dependency DAG cycle check
//   E — Negative proof (synthetic fixtures that MUST fail)
//
// This module does NOT modify any Product / ADB / AT Engine code. It is a
// build-time verification tool. It reads the repository's own build files and
// sources (READ-ONLY) and asserts the frozen architecture invariants.

plugins {
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
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
