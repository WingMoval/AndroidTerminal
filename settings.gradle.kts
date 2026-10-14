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
include(":at-engine")
include(":at-backend-local")
include(":at-backend-adb")
include(":app")
include(":test-host")
// AT.V3-2.3: ADB-only Test Host (TEST / VALIDATION ONLY) — proves physical
// ADB-only composition (no :at-backend-local / :app dependency).
include(":adb-only-test-host")
// AT.TERM-0: Shellless Test Host (TEST / VALIDATION ONLY) — proves AT Engine
// persistent terminal is a shell-independent byte-stream session (no
// :at-backend-local / :at-backend-adb / :adb-engine / :app; no /system/bin/sh).
include(":at-shellless-test-host")