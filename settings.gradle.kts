// AndroidTerminal-V3 — Canonical Production Mainline.
// AT.V3-0 minimal bootstrap skeleton.
//
// Logical module layout follows Canonical V1 responsibility areas:
//   :adb-engine        — ADB Engine (independent execution provider)
//   :at-engine         — AT Engine (reusable terminal execution capability)
//   :at-backend-local  — Local Backend adapter (depends on :at-engine)
//   :at-backend-adb    — ADB Backend adapter (depends on :at-engine + :adb-engine)
//   :app               — AndroidTerminal Human UI (consumer)
//   :test-host         — Test Host (independent consumer)
//
// Module count / package names are PROVISIONAL for bootstrap; they will be
// finalized through AT.V3-1 migration and Track A/B work (Canonical V1 §27).

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidTerminal"

include(":adb-engine")
include(":at-engine-api")  // V4-1: Public consumer contract (Layer-2)
include(":at-engine-spi")  // V4-1: Backend SPI (Layer-3/4 implementation contract)
include(":at-engine")      // V4-1: Engine implementation (Layer-3)
include(":at-backend-local")
include(":at-backend-adb")
include(":at-composition-android")  // V4-2: Android assembly root
include(":app")
include(":test-host")
// AT.V3-2.3: ADB-only Test Host (TEST / VALIDATION ONLY) — proves physical
// ADB-only composition (no :at-backend-local / :app dependency).
include(":adb-only-test-host")
// AT.TERM-0: Shellless Test Host (TEST / VALIDATION ONLY) — proves AT Engine
// persistent terminal is a shell-independent byte-stream session (no
// :at-backend-local / :at-backend-adb / :adb-engine / :app; no /system/bin/sh).
include(":at-shellless-test-host")
// AT.CLI-CONSUMER-0: Reusable Shell-Visible ADB CLI Consumer Capability.
// Consumer-side module (NOT :app). Hosts the app_process CLI main, the
// same-UID UDS bridge server, and the Local shell `adb` function bootstrap.
include(":at-cli")
// AT.CLI-CONSUMER-0: Independent CLI Consumer Test Host (TEST / VALIDATION
// ONLY). Proves a NON-AndroidTerminal consumer can obtain the full
// Local Terminal -> shell-visible adb -> uid=2000(shell) environment without
// depending on :app or copying the AndroidTerminal identity.
include(":at-cli-consumer-host")
// AT.V4-5: Reference Consumer + Conformance Fixture. Proves a fully LEGAL
// consumer (only :at-engine-api + :at-composition-android + :at-cli) can
// complete the full AndroidTerminal surface (Local/ADB one-shot + persistent,
// Readiness/Bootstrap/Identity, CLI). Not a Product; a conformance fixture.
include(":at-consumer-conformance-host")
// AT.V4-5: Automated Architecture Enforcement (pure JVM). Machine-enforces the
// frozen V4 Consumer Boundary (direct deps / source imports / API surface /
// DAG cycles / negative proof). Verification-only; modifies no Product code.
include(":at-architecture-gate")
