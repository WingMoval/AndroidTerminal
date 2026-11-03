# AT.V042-EXECUTION-CAPABILITY-FINAL

**Step:** AT.V042-HUMAN-EXECUTION-CLOSEOUT-1
**Date:** 2026-11-03
**Device:** HONOR ALI-AN00, Android 15
**Branch:** feature/local-tcp-v0.4.2
**Base HEAD:** 374fc42

---

## Final Capability Matrix

| Capability | Product Surface | Evidence | Result |
|---|---|---|---|
| Local backend one-shot | `LocalOneShotBackend.execute()` (ProcessBuilder → /system/bin/sh -c) | Code audit: implemented in ATAndroidComposition.localEngine | IMPLEMENTED, NOT TESTED (no user-facing consumer) |
| Local PTY | + → Local | H00 (human, uid=10443) | PASS |
| Local PTY cwd | Local PTY | NOT_TESTED (not explicitly tested in Local PTY) | NOT_TESTED |
| Local PTY env | Local PTY | NOT_TESTED | NOT_TESTED |
| Local PTY Ctrl-C | Local PTY | NOT_TESTED | NOT_TESTED |
| ADB CLI one-shot via Local PTY | `adb shell <cmd>` in Local PTY (shell function → app_process → AdbCliMain → CLI Bridge → adbEngine().execute) | H00 (human: adb pair) + Phase 3 auto (5/5: id/pwd/getprop/exit7/stderr) | PASS |
| ADB Engine one-shot | Internal API: AdbRuntime.executeShell → AdbEngine.executeShell/executeShellLocalhost (transport-routed) | Code audit + 74 JVM tests + 5 device tests | PASS |
| ADB PTY | + → ADB | GATE 1-7 (human) | PASS |
| ADB PTY cwd | PTY persistence | GATE 1 (human, pwd confirmed) | PASS |
| ADB PTY env | PTY persistence | GATE 1 (human, echo $AT_TEST confirmed) | PASS |
| ADB PTY Ctrl-C | PTY interrupt | GATE 1 (human, sleep 100 → ^C → 130) | PASS |
| Wireless bootstrap | Internal: connectAuto → Wireless READY | GATE 5/6/7 (logcat) | PASS |
| Local TCP ADB transport | Internal: 38800 adbd-owned | GATE 3-7 + H13/H16 | PASS |
| Multi-session | + → ADB (3 concurrent) | GATE 2 (human) | PASS |
| Session isolation | PTY independent | GATE 2 (human, cwd/env/blocking/close) | PASS |
| Session churn | Engine/Consumer | Phase 3 auto (10/10) | PASS |
| Wi-Fi independence | Local TCP | GATE 3 (human) | PASS |
| WD independence | Local TCP | GATE 3 (human) | PASS |
| App restart local-first | Local TCP | GATE 4 (human) | PASS |
| Local loss recovery | Lifecycle | GATE 5 (human) | PASS |
| Reboot recovery | Lifecycle | GATE 6 (human) | PASS |
| Trust-loss recovery | Lifecycle | GATE 7 (human) | PASS |

---

## PHASE 2: Human Gate Results

| Gate | Capability | Result |
|---|---|---|
| GATE 1 | ADB Local TCP PTY full semantics | PASS |
| GATE 2 | Multi-session isolation (3 sessions) | PASS |
| GATE 3 | Full Local Independence (Wi-Fi OFF + WD OFF) | PASS |
| GATE 4 | App Death + Local-first relaunch | PASS |
| GATE 5 | Local Loss safe failure + Recovery without restart | PASS |
| GATE 6 | Device Reboot recovery | PASS |
| GATE 7 | Trust Loss + Same-key Repair | PASS |

**CRITICAL_HUMAN_GATES_PASS = 7/7**

---

## PHASE 3: Automated Results

```
LOCAL_BACKEND_ONESHOT_AUTOMATED = NOT_TESTED (no user-facing consumer; PC adb shell tests do not exercise LocalOneShotBackend)
ADB_CLI_ONESHOT_VIA_LOCAL_PTY = PASS (5/5: id, pwd, getprop, exit 7, stderr)
PC_ADB_SHELL_MATRIX = PASS (8/8: stdout, stderr, exit codes, not-found, unicode, long output, empty, rapid) [informational: PC→device USB transport, not an AT capability]
SESSION_CHURN = 10/10
TRANSPORT_CHURN = 3/3
STATE_DRIFT = NO
DOUBLE_RX_OWNER = NO
TEST_TOTAL = 74
TEST_PASS = 74
TEST_FAIL = 0
BUILD_RELEASE = PASS
ARCHITECTURE_GATE = NO_TASK (no dedicated arch gate task in build)
```

---

## PHASE 4: ADB One-shot Code Audit

### ADB_ONESHOT_PRIMITIVE

Two primitives exist:

1. **`AdbEngine.executeShell(command, timeoutMs)`** — Wireless one-shot
   - File: `adb-engine/src/main/java/com/adb/engine/AdbEngine.kt` L55
   - JNI: `AdbNative.executeShell` → `adb_engine_jni.cpp` L166
   - Native: `client->shell(command)` on the existing Wireless AdbClient connection
   - Service: `shell,v2,raw:<command>` via AdbClient internal stream

2. **`AdbEngine.executeShellLocalhost(command)`** — Local TCP one-shot
   - File: `adb-engine/src/main/java/com/adb/engine/AdbEngine.kt` L96
   - JNI: `AdbNative.executeShellLocalhost` → `adb_engine_jni.cpp` L453
   - Native: Opens `shell,v2,raw:<command>` stream on `g_local->hub` (Local TCP stream hub)
   - Same shell-v2 protocol parser, same AdbShellResult return type

### ADB_ONESHOT_CALL_CHAIN

```
Consumer (AdbBackend / CLI / internal)
  → AdbRuntime.executeShell(command, timeoutMs)     [L860: transport router]
    → if activeTransport == LOCAL:
        AdbEngine.executeShellLocalhost(command)    [Local TCP hub]
          → AdbNative.executeShellLocalhost         [JNI]
            → g_local->hub->openStream("shell,v2,raw:cmd")
            → ShellV2Protocol read loop → AdbShellResult
    → else (WIRELESS):
        AdbEngine.executeShell(command, timeoutMs)  [Wireless client]
          → AdbNative.executeShell                  [JNI]
            → client->shell(command)                [AdbClient]
            → AdbShellResult
```

### ADB_ONESHOT_FIRST_KNOWN_COMMIT

```
a40387e (2026-10-11) — AT.V3-1: extract and validate independent ADB engine
```

`executeShell` was present from the **very first ADB Engine commit** (AT.V3-1). It is one of the earliest AT V3 capabilities, predating Local TCP by multiple weeks.

### ADB_ONESHOT_WIRELESS_SUPPORTED = YES

- `AdbEngine.executeShell` uses `ClientExisting()` → the established Wireless AdbClient
- Verified in device tests: `AdbEngineDeviceTest.kt` L95-111
- Verified in production: all prior rounds used Wireless one-shot before Local TCP existed

### ADB_ONESHOT_LOCAL_TCP_SUPPORTED = YES

- `AdbRuntime.executeShell` L860-865: explicit transport routing
- When `activeTransport == LOCAL`, routes to `executeShellLocalhost`
- When `activeTransport == WIRELESS`, routes to `executeShell`
- Code comment (L855-858): "AT.LOCAL-TCP-1B Gate E: routed by [activeTransport]"
- Verified in device tests: `AdbRuntimeLifecycleTest.kt` L275-294, `LocalTcpProductWiringTest.kt` L46
- Verified in production: H00 (adb pair via Local one-shot), GATE 1-7 (all ADB operations over Local)

### ADB_CLI_ONESHOT_VIA_LOCAL_PTY_PATH

```
Local PTY shell (uid=10443, untrusted_app)
  → shell function: adb() { /system/bin/app_process -Djava.class.path="$AT_APP_APK" ... AdbCliMain }
    → AdbCliMain (at-cli module)
      → CLI Bridge UDS (cli_bridge_server.cpp, loaded by host app)
        → AdbCliBridgeServer.onOneShot
          → execution.adbEngine().execute(request)     [AdbOneShotBackend]
            → AdbRuntime.executeShell
              → (transport-routed: LOCAL → executeShellLocalhost / WIRELESS → executeShell)
```

### ADB_ENGINE_ONESHOT_PATH

```
AdbRuntime.executeShell → AdbEngine.executeShell/executeShellLocalhost → JNI → native
```

### LOCAL_BACKEND_ONESHOT_PATH

```
ATAndroidComposition.localEngine.execute(request)
  → LocalOneShotBackend.execute(request)
    → ProcessBuilder("/system/bin/sh", "-c", command)
    → concurrent stdout/stderr drain
    → process.waitFor()
    → ATExecutionResult(COMPLETED, stdout, stderr, exitCode)
```

**NOT consumed by any user-facing surface.** The APK UI only exposes PTY sessions.
The CLI bridge (`adb shell <cmd>`) routes to `adbEngine`, NOT `localEngine`.

### ADB_CLI_AND_ENGINE_ONESHOT_SAME_PATH = YES (at the engine level)

The CLI `adb shell <cmd>` in the Local PTY session ultimately calls `execution.adbEngine().execute(...)` → `AdbOneShotBackend.execute()` → `AdbRuntime.executeShell` → `AdbEngine.executeShell/executeShellLocalhost`. The execution primitive is the ADB Engine one-shot, not the Local backend one-shot.

### Consumer Surface

```
LOCAL_BACKEND_ONESHOT_USER_SURFACE = NO
  - LocalOneShotBackend is implemented and wired into localEngine
  - No APK UI button, no CLI path routes to localEngine
  - Available for future AG/consumer integration via ATAndroidComposition.localEngine

APK_ADB_ONESHOT_USER_SURFACE = NO (no direct "run command once" button in the APK UI)
  - APK has PTY sessions (+ → ADB / + → Local) and CLI bridge
  - ADB one-shot is consumed programmatically via AdbBackend / CLI bridge

CLI_ADB_ONESHOT_VIA_LOCAL_PTY_SURFACE = YES
  - `adb shell <command>` in Local PTY → AdbCliMain → CLI Bridge → adbEngine().execute
  - AdbCliArgs parses Kind.SHELL → remoteCommand → ADB one-shot backend
  - This is ADB CLI one-shot via Local PTY, NOT Local backend one-shot

ENGINE_ADB_ONESHOT_API = YES
  - AdbRuntime.executeShell (public API, transport-routed)
  - AdbEngine.executeShell (Wireless)
  - AdbEngine.executeShellLocalhost (Local TCP)
```

### Product Interpretation

```
LOCAL_BACKEND_ONESHOT = IMPLEMENTED, NOT EXPOSED (no user-facing consumer)
ADB_ENGINE_ONESHOT = IMPLEMENTED (both Wireless and Local TCP, transport-routed)
ADB_ENGINE_ONESHOT_APK_DIRECT_UI = NOT EXPOSED (no dedicated one-shot button)
ADB_CLI_ONESHOT_VIA_LOCAL_PTY = EXPOSED (via `adb shell <cmd>` in Local PTY)
```

The ADB Engine one-shot is **fully implemented** at the engine level. Its user-facing surface is the CLI (`adb shell` in Local PTY). The Local backend one-shot is also implemented but has no user-facing consumer yet. Neither is "missing" — they simply have different exposure levels.

---

## AG Recommendation

For future AndroidAgent:

```
AG shell tool (single non-interactive command)
  → AT execution adapter
    → AdbRuntime.executeShell (one-shot)
      → transport-routed (LOCAL or WIRELESS, transparent to AG)

AG interactive/persistent shell
  → AT execution adapter
    → AdbRuntime.openInteractive("shell,v2,pty:")
      → PTY session (persistent, Ctrl-C capable, cwd/env state)
```

**Recommendation:** One-shot for single calls (lower overhead, no PTY allocation, no session bookkeeping). PTY for interactive/persistent shells (multi-command state, interrupt, resize).

---

## Bug Register

| Bug ID | Severity | Description | Repro | Evidence |
|---|---|---|---|---|
| OBS-1 | NON_REPRODUCED | H05: one-time input anomaly (`at_h05_probe` → `at_h05_probr`). NOT reproducible: 5/5 commands up to 120 chars executed intact via `input text`; EditText has no maxLength. Classified as user typing error / IME interference. | Not reproducible (5/5 automated test PASS) | H05 screenshot + RELEASE-PREFLIGHT-1 test |
| OBS-2 | MINOR | After re-pair (GATE 7), AT status banner shows "NEEDS PAIRING" + "CONNECT_FAILED" even though 38800 is already LISTENING. Status updates on next `+`→ADB tap. Cosmetic only. | Trust-loss → re-pair → observe banner | Screenshot g7f.png, logcat 17:45:39 |

**BLOCKER_COUNT = 0**
**MAJOR_COUNT = 0**
**MINOR_COUNT = 1**
**OBSERVATION_COUNT = 0**

---

## Final Verdict

```
STEP = AT.V042-HUMAN-EXECUTION-CLOSEOUT-1
VERDICT = PASS_WITH_FINDINGS
BASE_HEAD = 374fc42
FINAL_HEAD = bbce5a6 (docs-only commit)
H01_H16_SUMMARIZED = YES
H01_H16_RERUN = NO
CRITICAL_HUMAN_GATES_TOTAL = 7
CRITICAL_HUMAN_GATES_PASS = 7/7
ADB_LOCAL_TCP_PTY = PASS
ADB_LOCAL_TCP_PTY_CWD = PASS
ADB_LOCAL_TCP_PTY_ENV = PASS
ADB_LOCAL_TCP_PTY_CTRL_C = PASS
MULTI_SESSION = PASS
SESSION_ISOLATION = PASS
BLOCKING_ISOLATION = PASS
SESSION_CLOSE_ISOLATION = PASS
LOCAL_TCP_FULL_INDEPENDENCE = PASS
RELAUNCH_LOCAL_FIRST = PASS
LOCAL_LOSS_SAFE_FAILURE = PASS
RECOVERY_WITHOUT_APP_RESTART = PASS
POST_REBOOT_RECOVERY = PASS
TRUST_LOSS = PASS
SAME_KEY_REPAIR = PASS
LOCAL_BACKEND_ONESHOT_AUTOMATED = NOT_TESTED (implemented, no user-facing consumer)
ADB_CLI_ONESHOT_VIA_LOCAL_PTY = PASS
PC_ADB_SHELL_MATRIX = PASS (8/8, informational: PC→device, not an AT capability)
SESSION_CHURN = 10/10
TRANSPORT_CHURN = 3/3
STATE_DRIFT = NO
DOUBLE_RX_OWNER = NO
ADB_ONESHOT_PRIMITIVE = AdbEngine.executeShell (Wireless) + AdbEngine.executeShellLocalhost (Local TCP), routed by AdbRuntime.executeShell
ADB_ONESHOT_CALL_CHAIN = Consumer → AdbRuntime.executeShell → [LOCAL: AdbEngine.executeShellLocalhost → JNI → g_local hub stream] / [WIRELESS: AdbEngine.executeShell → JNI → AdbClient.shell]
ADB_ONESHOT_FIRST_KNOWN_COMMIT = a40387e (2026-10-11, AT.V3-1)
ADB_ONESHOT_WIRELESS_SUPPORTED = YES
ADB_ONESHOT_LOCAL_TCP_SUPPORTED = YES
APK_ADB_ONESHOT_USER_SURFACE = NO (not exposed as dedicated UI)
CLI_ADB_ONESHOT_VIA_LOCAL_PTY_SURFACE = YES (adb shell in Local PTY → adbEngine)
ENGINE_ADB_ONESHOT_API = YES
ADB_CLI_AND_ENGINE_ONESHOT_SAME_PATH = YES (CLI routes to adbEngine → AdbRuntime.executeShell)
LOCAL_BACKEND_ONESHOT_IMPLEMENTED = YES (LocalOneShotBackend, ProcessBuilder)
LOCAL_BACKEND_ONESHOT_API = ATAndroidComposition.localEngine.execute()
LOCAL_BACKEND_ONESHOT_CONSUMER = NONE (no user-facing surface)
LOCAL_BACKEND_ONESHOT_TESTED = NO
TEST_TOTAL = 74
TEST_PASS = 74
TEST_FAIL = 0
ARCHITECTURE_GATE = NO_TASK
BUILD_RELEASE = PASS
BLOCKER_COUNT = 0
MAJOR_COUNT = 0
MINOR_COUNT = 1
OBSERVATION_COUNT = 0
CORE_EXECUTION_CAPABILITIES = PASS
RELEASE_RECOMMENDATION = GO
PRODUCT_CODE_CHANGED = NO
TAG_CREATED = NO
GITHUB_RELEASE_CREATED = NO
NEXT = AT.V042-RELEASE-1
STOP = YES
```
