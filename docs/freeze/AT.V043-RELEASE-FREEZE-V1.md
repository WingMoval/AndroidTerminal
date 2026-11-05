# AndroidTerminal v0.4.3 — Release Freeze SSOT

**Date:** 2026-11-05
**Status:** FROZEN
**Type:** PATCH_RELEASE

---

## 1. Release Identity

| Field | Value |
|-------|-------|
| RELEASE | v0.4.3 |
| versionName | 0.4.3 |
| versionCode | 5 |
| package | com.androidterminal.app |
| BRANCH | feature/local-tcp-v0.4.2 |
| BASE_RELEASE | v0.4.2 |
| BASE_TAG | v0.4.2 (fd8fb7c) |
| ROUTING_FIX_COMMIT | 4ebabe5 |
| RELEASE_COMMIT | 5517fc0 |
| TAG | v0.4.3 (annotated) |
| TAG_TARGET | freeze evidence commit |

---

## 2. Root Cause

```
ROOT_CAUSE_CLASS = ADB_BACKEND_BYPASSES_RUNTIME_TRANSPORT_ROUTER
```

v0.4.2: `AdbOneShotBackend.execute()` → `AdbEngine.executeShell()` (Wireless-only JNI)
Bypassed: `AdbRuntime.executeShell()` (canonical LOCAL/WIRELESS router)

v0.4.3: `AdbOneShotBackend.execute()` → `AdbRuntime.executeShell()`
```
LOCAL    → AdbEngine.executeShellLocalhost()
WIRELESS → AdbEngine.executeShell()
```

---

## 3. Scope

PATCH_RELEASE. Exactly:
- 1-line routing fix in AdbBackend.kt (4ebabe5)
- Version bump: 0.4.2→0.4.3, code 4→5
- Release notes

No new feature, no architecture change, no native change, no API change.

---

## 4. Architecture Invariants (FROZEN)

```
AdbRuntime = unique ADB transport lifecycle / routing owner
AdbEngine  = low-level ADB primitives (executeShell, executeShellLocalhost)
AdbOneShotBackend = execution adapter (routes via AdbRuntime)

Local one-shot = primary local execution path (executeShellLocalhost)
Wireless = bootstrap / recovery transport (executeShell)
Local TCP = preferred steady-state ADB transport when available
PTY = interactive special path (separate from ADB one-shot)
```

---

## 5. activeTransport SSOT

```kotlin
@Volatile var activeTransport: AdbTransportMode
```

ACTIVE_TRANSPORT_VOLATILE = YES
VISIBILITY_FIX_IN_V043 = NO (already correct)

---

## 6. Validation Summary

| Gate | Result |
|------|--------|
| Automated tests | 163/163 PASS |
| Architecture gate | 13/13 PASS |
| Release build | PASS |
| APK smoke | PASS (launch, version, no crash) |
| AG consumer validation | PASS_LOCAL_WITH_WIRELESS_LIMITATION (cd5d190) |
| True Local Loss | DEVICE VERIFIED (adb usb) |
| Identity continuity | PASS (6b1949b4) |
| Transport ownership | PASS (MAX_ACTIVE=1, MAX_RX=1) |
| Router audit | 0 invalid bypasses |
| Native diff | NONE |
| Public API diff | NONE |

---

## 7. Wireless Evidence (Precise)

- Pairing TLS: device verified (AG validation)
- Execution path: unchanged from v0.4.2
- Direct one-shot: NOT_ISOLATED this round (auto-promotion to Local)
- Classification: CARRIED_FORWARD_FROM_V042_UNCHANGED_PATH
- Release blocker: NO

---

## 8. Known Limitations

1. `adb usb` (only validated true-loss method) disables WD → cannot test Wireless recovery in same session
2. Wireless→Local promotion not independently observed in closeout (recovery used Local-first)
3. Both are testability constraints, not product defects

---

## 9. G-b1 Exclusion

G_B1 = Agent long-running Local one-shot Stop / child-process termination semantics
Track: AG execution lifecycle (NOT AT routing)
G_B1_RETESTED = NO
G_B1_BLOCKS_AT_V043 = NO

---

## 10. Consumer Handoff

```
CURRENT FORMAL AT RELEASE = v0.4.3
AG CONSUMER ELIGIBILITY = YES
AG DEFAULT EXECUTION = LOCAL ONE-SHOT
SECONDARY ANDROID SHELL = ADB one-shot via AT
ADB ROUTER = AdbRuntime.executeShell
PTY = SPECIAL INTERACTIVE PATH
```

---

## 11. Verdict

**AT_V043_FORMAL_RELEASE_READY = YES**
**AG_CONSUMER_ELIGIBLE = YES**

AndroidTerminal v0.4.3 is formally frozen.
Next: AG.V1-RELEASE-FULL-CAPABILITY-VALIDATION-0
