# AT V4 — Consumer Handoff

**Date:** 2026-10-22
**Freeze Tag:** AT.V4-CONSUMER-FREEZE-V1
**Parity Validation Commit:** 532f816
**Freeze Commit:** 7216d0e8131e92e8914ce7c8443e1668b1d53aca

This document is the formal entry point for any Android Consumer
(AndroidTerminal Product, AndroidAgent Android Host, or other Android
Consumer) integrating with AT Engine.

---

## 1. Frozen Consumer Boundary

```
Consumer
├── :at-engine-api           (REQUIRED — public execution/session contract)
├── :at-composition-android  (REQUIRED — Android assembly root + ADB semantic facade)
└── :at-cli                  (OPTIONAL — CLI frontend)
```

### Forbidden Direct Dependencies (CONSUMER_BOUNDARY_VIOLATION)
```
:at-engine / :at-engine-spi / :at-backend-local / :at-backend-adb / :adb-engine
```

A Consumer MAY legally have `:adb-engine` on its runtime classpath via
`at-composition-android -> implementation(:adb-engine)` (transitive). Only a
Consumer's OWN direct `implementation(project(":adb-engine"))` is a violation.

---

## 2. Initialization

### Canonical Entry
```kotlin
val composition = ATAndroid.create(context, ATAndroidConfig(identityProfile))
```

- `context` — Android `Context` (application context recommended). Context
  belongs at the composition root; the Consumer does NOT pass Context into
  engine APIs.
- `identityProfile` — the app-private identity directory name (e.g.
  "adb_identity"). The composition owns identity location; the Consumer does
  NOT manage `identityDir` directly.

### What the Consumer Gets
```kotlin
composition.localEngine   // ATEngine — Local one-shot + persistent
composition.adbEngine     // ATEngine — ADB one-shot + persistent
composition.adbControl    // ATAndroidAdbControl — readiness/bootstrap/pairing facade
```

---

## 3. Local Execution

### One-shot
```kotlin
val result = composition.localEngine.execute(ATExecutionRequest(command = "id"))
// result.outcome == ATExecutionOutcome.COMPLETED
// result.stdout, result.stderr, result.exitCode
```

### Persistent
```kotlin
val openResult = composition.localEngine.openSession(
    ATSessionLaunch(command = "/system/bin/sh", initialRows = 24, initialCols = 80)
)
// openResult.session — ATTerminalSession
// session.write(bytes) — stdin
// session.setListener { event -> ... } — stdout/events
// session.close() — explicit close
```

---

## 4. ADB Execution

The Consumer does NOT directly call `AdbRuntime` or `AdbEngine`. All ADB
semantics go through the composition facade (`adbControl`) and the ADB
`ATEngine` (`adbEngine`).

### Readiness
```kotlin
val state: ATAdbReadinessState = composition.adbControl.readinessState()
// state == ATAdbReadinessState.READY when connected
```

### Ensure Ready (drives connection)
```kotlin
val ready = composition.adbControl.ensureReady { line ->
    // status line callback
}
```

### One-shot
```kotlin
val result = composition.adbEngine.execute(ATExecutionRequest(command = "id"))
// result.stdout contains "uid=2000(shell)"
```

### Persistent
```kotlin
val openResult = composition.adbEngine.openSession(
    ATSessionLaunch(command = "sh", initialRows = 24, initialCols = 80)
)
```

---

## 5. First-Use Bootstrap

### First Time
```
NO IDENTITY → PAIRING_REQUIRED → user pairs once → self-grant WRITE_SECURE_SETTINGS → READY
```

1. `adbControl.hasIdentity()` returns `false`
2. User opens Settings → Wireless Debugging → Pair device with pairing code
3. `adbControl.pairDiscovered(code)` or `adbControl.pair(host, port, code)`
4. `adbControl.ensureReady()` → `READY`
5. `adbControl.completeFirstUseBootstrap()` → self-grants WRITE_SECURE_SETTINGS
   (via embedded ADB shell, uid=2000)

### Subsequent (Auto-WD)
```
WD OFF → ensureReady → auto-enable WD → discovery → connect → READY
```

No manual WD required. No re-pairing. No re-granting.

---

## 6. Identity

- ONE canonical identity per Consumer (self-generated, app-private, persistent)
- Located in the Consumer's own `files/<identityProfile>/` directory
- NEVER copied from Product / PC / another Consumer
- The Consumer does NOT manually select or manage `identityDir`

```
KEY_COPY = NO
PC_KEY_REUSE = NO
SHARED_PRIVATE_KEY = NO
```

---

## 7. CLI

`:at-cli` is an OPTIONAL frontend. It provides:
- `adb shell <cmd>` — one-shot remote execution
- `adb shell` — interactive remote PTY
- `adb pair <host>:<port> <code>` — pairing

### Host Obligations
The embedding host must:
1. Provide an `ATCliExecution` implementation (routes CLI into the composition)
2. Start the `AdbCliBridgeServer` (same-UID UDS)
3. Inject the shell-visible `adb` function via `LocalCliBootstrap`

The CLI does NOT become a lifecycle owner. It does NOT directly depend on
`:adb-engine`. It reuses the host's existing ATEngine + AdbRuntime.

---

## 8. Error / Readiness Semantics

Consumer should handle these states (from `ATAndroidAdbControl`):

| State | Meaning |
|-------|---------|
| `ATAdbReadinessState.READY` | ADB connection established, commands can execute |
| `ATAdbReadinessState.DISCONNECTED` | No active ADB connection |
| `ATAdbBootstrapState.PAIRING_REQUIRED` | No usable identity; user must pair |
| `ATAdbBootstrapState.CONNECT_FAILED` | Identity present but no usable connection |
| `ATAdbBootstrapState.READY` | Identity present + connection ready |

Pairing results:
- `ATAdbPairingResult.Outcome.SUCCESS` — paired successfully
- `ATAdbPairingResult.Outcome.REJECTED` — pairing code rejected
- `ATAdbPairingResult.Outcome.SERVICE_NOT_FOUND` — no pairing service found
- `ATAdbPairingResult.Outcome.MULTIPLE_ENDPOINTS` — multiple pairing endpoints
- `ATAdbPairingResult.Outcome.FAILED` — pairing failed

---

## 9. Frozen Ownership

```
AdbRuntime              = UNIQUE lifecycle owner (connect/disconnect/recover)
AdbRecoveryCoordinator  = UNIQUE recovery owner (transport loss recovery)
AdbEngine.pair          = pairing primitive (single place that calls pair)
AdbConsumerBootstrap    = first-use bootstrap implementation
AT Engine               = execution semantics (one-shot + persistent)
```

The Consumer does NOT obtain these concrete internals. All interaction is
through the composition facade (`ATAndroidAdbControl`) and the public
`ATEngine` contract.

---

## 10. AG Handoff Boundary

For future AndroidAgent integration:

```
AG Core
    → NO concrete AT dependency (AG Core stays AT-agnostic)

AG integration adapter
    → :at-engine-api (depends only on the public contract)

AGA Android host composition root
    → :at-composition-android
    → inject ATEngine into the AG adapter
```

The AG adapter receives an `ATEngine` instance (injected by the host
composition root) and uses the public contract. It does NOT depend on
`:at-engine` (implementation) or any backend module.

This is a HANDOFF CONTRACT only. The AG adapter is NOT implemented in V4-6.

---

## 11. Freeze Semantics

`AT.V4-CONSUMER-FREEZE-V1` means:
- V4 physical architecture is COMPLETE
- Consumer dependency boundary is FROZEN
- V4 device parity is ACCEPTED
- Consumer handoff is ACCEPTED
- Artifact bundle is ACCEPTED

It does NOT mean:
- AT Contract is permanently final (V5 may evolve)
- No future API additions
- Maven publication is final
- Terminal Emulator is complete
- AndroidAgent is complete

Post-V4 phases (AT.EMULATOR-0, AT.ARTIFACT-BUNDLE-0, AG.INTEGRATION-0,
AT.CONTRACT-1) remain DEFERRED for Controller decision.
