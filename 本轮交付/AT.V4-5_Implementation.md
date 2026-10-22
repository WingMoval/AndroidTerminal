# AT.V4-5 — Consumer Conformance and Architecture Enforcement

**Date:** 2026-10-22
**Baseline:** HEAD ae9f0ad (worktree CLEAN)
**Status:** COMPLETE

---

## Goal

Prove that a fully LEGAL consumer — depending ONLY on the frozen V4 public
Consumer Boundary — can complete the entire AndroidTerminal surface, AND upgrade
that boundary from "documentation rules" into MACHINE-ENFORCED architecture
gates that FAIL on violation.

No new AndroidTerminal feature is added. No Product / ADB / AT Engine semantics
are changed.

## Canonical Consumer Boundary

```
Consumer
├── :at-engine-api           (REQUIRED — public execution/session contract)
├── :at-composition-android  (REQUIRED — Android assembly root + ADB semantic facade)
└── :at-cli                  (OPTIONAL — CLI frontend)
```

Forbidden direct dependencies (CONSUMER_BOUNDARY_VIOLATION):
```
:at-engine / :at-engine-spi / :at-backend-local / :at-backend-adb / :adb-engine
```

A consumer MAY legally have `:adb-engine` on its runtime classpath via
`at-composition-android -> implementation(:adb-engine)` (transitive). Only a
consumer's OWN direct `implementation(project(":adb-engine"))` is a violation.

## Reference Consumer structure

New module `:at-consumer-conformance-host` (com.androidterminal.conformance):
- REFERENCE CONSUMER + CONFORMANCE FIXTURE (NOT a Product).
- `ConformanceHost.initialize(context)` → `ATAndroid.create(context, config)`
  (canonical composition entrypoint; identityProfile "conformance-identity").
- COMPOSE + CONSUME: localEngine / adbEngine / adbControl / cliBootstrap /
  cliExecution. NO `new DefaultATEngine` / `new AdbRuntime` / backend
  construction.
- Independent app-private identity (files/conformance-identity, self-generated).

## Allowed / Forbidden dependencies

| Module | Allowed | Forbidden (verified absent) |
|--------|---------|------------------------------|
| at-consumer-conformance-host | at-engine-api, at-composition-android, at-cli | at-engine, at-engine-spi, at-backend-local, at-backend-adb, adb-engine |

## Conformance results

All 8 gates PASS via direct `am instrument` (identity preserved, no UTP):

| Gate | Capability | Result |
|------|-----------|--------|
| A | Local one-shot | PASS |
| B | Local persistent | PASS |
| C | ADB one-shot (uid=2000) | PASS |
| D | ADB persistent (uid=2000) | PASS |
| E | Readiness (public facade) | PASS |
| F | Bootstrap (public facade + completeFirstUseBootstrap) | PASS |
| G | Identity (independent, self-generated) | PASS |
| H | CLI shell-visible adb (uid=2000) | PASS |

## Architecture enforcement implementation

New module `:at-architecture-gate` (pure JVM/Kotlin):
- `ArchitectureChecker` — pure functions over ModuleNode graph + source text.
- `RepoLoader` — reads the repo's OWN build.gradle.kts + consumer production
  sources (READ-ONLY).

Gates:
- **A — Direct dependency:** consumer's OWN direct project deps ∩ FORBIDDEN = ∅.
  Distinguishes DIRECT from LEGAL TRANSITIVE.
- **B — Source import:** consumer PRODUCTION source (src/main only) must not
  import com.adb.engine.* / com.at.engine.impl.* / com.at.engine.spi.* /
  com.at.backend.*. Implementation-module tests are NOT consumer violations.
- **C — API surface:** :at-engine-api public signature lines must not reference
  Context / AdbRuntime / AdbEngine / backends / SPI / identityDir. Comments
  excluded (avoids "NO Android Context" false positive).
- **D — DAG cycle:** DFS over the module graph; back-edge to in-stack node =
  cycle.
- **E — Negative proof:** synthetic fixtures MUST be detected (4 violation
  cases + 2 legal sanity cases).

## Negative proof

```
case1 consumer->:adb-engine      DETECTED (PASSED)
case2 consumer->:at-engine       DETECTED (PASSED)
case3 :at-engine-api AdbRuntime  DETECTED (PASSED)
case4 A->B->A cycle              DETECTED (PASSED)
legal consumer graph             CLEAN (no false positive)
transitive :adb-engine via composition  CLEAN (not a violation)
```

13/13 architecture gate tests PASS.

## Device validation strategy

- Manual `adb install -r` (NOT Gradle UTP — UTP uninstalls and destroys the
  persistent app-private identity).
- Conformance host first-use pairing via the public composition facade
  (adbControl.pairDiscovered) — authorized, ONE, independent identity.
- 8 conformance gates via direct `am instrument`.
- Product / ConsumerHost NOT uninstalled/cleared/re-paired.

## Contract gap result

```
CONSUMER_CONTRACT_GAP = NO
```
A legal consumer using only :at-engine-api + :at-composition-android + :at-cli
completes all 8 capabilities. No public API expansion was required.

## Final verdict

```
AT.V4-4.2 = CLOSED (unchanged)
AT.V4-4.3 = COMPLETE (unchanged)
AT.V4-5   = COMPLETE
CONSUMER_CONFORMANCE = CLOSED
ARCHITECTURE_ENFORCEMENT = CLOSED
AT_ENGINE_PUBLIC_API_CHANGE = NO
RECOMMENDED_NEXT = AT.V4-6
NEXT_OPEN_AUTHORIZED = NO
```
