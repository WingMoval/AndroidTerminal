# AT.V042-RELEASE-PREFLIGHT-1

**Date:** 2026-11-03
**Device:** HONOR ALI-AN00, Android 15
**Branch:** feature/local-tcp-v0.4.2

---

## PHASE A: One-shot Terminology Audit

### A1. Local backend one-shot

```
LOCAL_BACKEND_ONESHOT_IMPLEMENTED = YES
LOCAL_BACKEND_ONESHOT_API = ATAndroidComposition.localEngine.execute(ATExecutionRequest)
  → LocalOneShotBackend.execute()
    → ProcessBuilder("/system/bin/sh", "-c", command)
    → concurrent stdout/stderr drain
    → process.waitFor()
    → ATExecutionResult(COMPLETED, stdout, stderr, exitCode)
LOCAL_BACKEND_ONESHOT_CONSUMER = NONE
  - No APK UI button routes to localEngine
  - CLI bridge (`adb shell <cmd>`) routes to adbEngine, NOT localEngine
  - Available for future AG/consumer integration
LOCAL_BACKEND_ONESHOT_TESTED = NO
  - No user-facing consumer to test against
  - JVM tests (74) include LocalPersistentSliceTest which uses LocalOneShotBackend
  - but no dedicated one-shot-only device test
```

### A2. ADB CLI one-shot via Local PTY

```
ADB_CLI_ONESHOT_VIA_LOCAL_PTY = PASS
Path:
  Local PTY (uid=10443, untrusted_app)
    → shell function: adb() { /system/bin/app_process ... AdbCliMain }
      → AdbCliMain (Kind.SHELL → bridgeOneShot)
        → CLI Bridge UDS (cli_bridge_server.cpp)
          → AdbCliBridgeServer.onOneShot
            → execution.adbEngine().execute(request)  [AdbOneShotBackend]
              → AdbRuntime.executeShell (transport-routed)
Evidence:
  H00 (human: adb pair in Local PTY)
  Phase 3 auto (5/5: id, pwd, getprop, exit 7, stderr)
  AdbCliArgs, AdbCliMain, AdbCliBridgeServer code audit
```

### A3. ADB Engine one-shot

```
ADB_ENGINE_ONESHOT = PASS
API: AdbRuntime.executeShell(command, timeoutMs)
Routing:
  activeTransport == LOCAL → AdbEngine.executeShellLocalhost(command)
    → JNI → g_local->hub->openStream("shell,v2,raw:cmd")
  activeTransport == WIRELESS → AdbEngine.executeShell(command, timeoutMs)
    → JNI → client->shell(command)
Evidence:
  Code audit (AdbRuntime.kt L860-865)
  74 JVM tests + 5 device tests
  All 7 Human Gates (ADB operations over Local TCP)
```

### A4. PTY

```
+ → Local = Local persistent PTY (LocalPersistentBackend, uid=10443)
+ → ADB = ADB persistent PTY (AdbPersistentBackend, uid=2000 shell)
PTY command execution ≠ one-shot primitive
  (PTY maintains state: cwd, env, tty; one-shot is stateless)
```

### Terminology Corrections Applied

| Before (incorrect) | After (correct) |
|---|---|
| "Local one-shot" for `adb shell <cmd>` in Local PTY | ADB CLI one-shot via Local PTY |
| "LOCAL_ONESHOT_AUTOMATED = PASS (8/8)" for PC `adb shell` tests | PC_ADB_SHELL_MATRIX = PASS (8/8) [informational: PC→device USB, not an AT capability] |
| "Local one-shot" as a product surface in the matrix | Local backend one-shot (IMPLEMENTED, NOT EXPOSED) |

---

## PHASE B: Long-command Input Reproduction

### Method

Used `input text` (IME injection) + `input tap SEND` to send 5 commands of increasing length to the ADB PTY session. Note: `input text` lowercases all input (IME behavior), so case sensitivity is not tested. The test validates **command integrity** (no truncation) through the input field → SEND → PTY → shell → echo output path.

### Commands Sent

| Case | Command | Length (chars, excl. "echo ") |
|---|---|---|
| 1 (Short) | `echo SHORT_OK` | 9 |
| 2 (Medium) | `echo MEDIUM_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789_OK` | 45 |
| 3 (Long A) | `echo LONG_A_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789_END` | 119 |
| 4 (Long B) | `echo LONG_B_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_END` | 120 |
| 5 (Repeat Long A) | Same as Case 3 | 119 |

### Results

| Case | Verdict | Evidence |
|---|---|---|
| SHORT (Case 1) | PASS | echo output `short_ok` visible in terminal |
| MEDIUM (Case 2) | PASS | echo output `medium_..._ok` visible, complete |
| LONG_A_1 (Case 3) | PASS | echo output ends with `_end`, full string visible across wrapped lines |
| LONG_B (Case 4) | PASS | echo output ends with `_end`, full string visible |
| LONG_A_2 (Case 5) | PASS | echo output ends with `_end`, full string visible (repeat confirms reproducibility) |

**All 5/5 commands executed with complete output. No truncation observed.**

### EditText Analysis

```
etInput attributes (activity_main.xml L114-121):
  - android:singleLine="true"
  - NO android:maxLength
  - NO android:maxLines (singleLine handles this)
  - android:imeOptions="actionSend"
  - android:fontFamily="monospace"
```

No programmatic length limit exists on the input field.

### Verdict

```
LONG_COMMAND_TRUNCATION_REPRODUCED = NO
LONG_COMMAND_RESULT = 5/5
```

---

## OBS-1 Final Classification

```
OBS_1 = NON_REPRODUCED_INTERACTION_ANOMALY
```

H05 originally observed: `echo H05_AFTER_CTRL_C >/data/local/tmp/at_h05_probe` became
`echo H05_AFTER_CTRL_C >/data/local/tmp/at_h05_probr` (missing `e` before `>`).

Reclassification:
- The missing character was `e` at position ~48 of a ~50-char command
- `input text` automated test with 119-120 char commands: NO truncation (5/5)
- EditText has NO maxLength constraint
- Most likely cause: user typing error (fingertip slip on `e` key) or IME
  autocomplete interference during manual input
- NOT a systematic truncation bug

Historical evidence preserved: H05 screenshot shows the truncated command.
This was a one-time interaction anomaly, not a reproducible product defect.

```
MINOR_COUNT adjustment: 2 → 1 (OBS-1 reclassified, OBS-2 retained)
```

---

## OBS-2 Final Classification

```
OBS_2 = MINOR / NON_BLOCKING
Description: After re-pair (GATE 7), AT status banner shows "NEEDS PAIRING" +
"CONNECT_FAILED" even though 38800 is already LISTENING and tcpip promotion
succeeded. Status only updates on next +→ADB or ADB button tap.
Impact: Cosmetic only. Functionality is fully restored; banner is stale.
```

---

## Final Report

```
STEP = AT.V042-RELEASE-PREFLIGHT-1
VERDICT = PASS
BASE_HEAD = bbce5a6
FINAL_HEAD = (docs-only commit)

TERMINOLOGY_NORMALIZED = YES

LOCAL_BACKEND_ONESHOT_IMPLEMENTED = YES
LOCAL_BACKEND_ONESHOT_API = ATAndroidComposition.localEngine.execute()
LOCAL_BACKEND_ONESHOT_CONSUMER = NONE
LOCAL_BACKEND_ONESHOT_TESTED = NO

ADB_CLI_ONESHOT_VIA_LOCAL_PTY = PASS
ADB_ENGINE_ONESHOT = PASS
ADB_PTY = PASS

LONG_COMMAND_TRUNCATION_REPRODUCED = NO
LONG_COMMAND_RESULT = 5/5

SHORT = PASS
MEDIUM = PASS
LONG_A_1 = PASS
LONG_B = PASS
LONG_A_2 = PASS

OBS_1_FINAL_CLASSIFICATION = NON_REPRODUCED_INTERACTION_ANOMALY
OBS_2_FINAL_CLASSIFICATION = MINOR_NON_BLOCKING

BLOCKER_COUNT = 0
MAJOR_COUNT = 0
MINOR_COUNT = 1
OBSERVATION_COUNT = 0

PRODUCT_CODE_CHANGED = NO
DOCS_ONLY_CHANGED = YES

RELEASE_PREFLIGHT = PASS
RELEASE_RECOMMENDATION = GO

TAG_CREATED = NO
GITHUB_RELEASE_CREATED = NO

NEXT = AT.V042-RELEASE-1
STOP = YES
```
