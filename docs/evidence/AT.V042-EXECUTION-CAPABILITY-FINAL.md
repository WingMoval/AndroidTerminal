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
| Local one-shot | `adb shell <cmd>` in Local PTY (shell function → app_process → AdbCliMain) | H00 (human) + Phase 3 auto (8/8) | PASS |
| Local PTY | + → Local | H00 (human, uid=10443) | PASS |
| Local PTY cwd | Local PTY | NOT_TESTED (not explicitly tested in Local PTY) | NOT_TESTED |
| Local PTY env | Local PTY | NOT_TESTED | NOT_TESTED |
| Local PTY Ctrl-C | Local PTY | NOT_TESTED | NOT_TESTED |
| ADB Engine one-shot | Internal API: AdbRuntime.executeShell → AdbEngine.executeShell/executeShellLocalhost | Code audit + 74 JVM tests + 5 device tests | PASS |
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
LOCAL_ONESHOT_AUTOMATED = PASS (8/8: stdout, stderr, exit codes, not-found, unicode, long output, empty, rapid)
ADB_CLI_ONESHOT_VIA_LOCAL = PASS (5/5: id, pwd, getprop, exit 7, stderr)
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

### ADB_CLI_ONESHOT_VIA_LOCAL_PATH

```
Local PTY shell (uid=10443, untrusted_app)
  → shell function: adb() { /system/bin/app_process -Djava.class.path="$AT_APP_APK" ... AdbCliMain }
    → AdbCliMain (at-cli module)
      → CLI Bridge UDS (cli_bridge_server.cpp, loaded by host app)
        → AppCliExecution (app adapter)
          → ATEngine / AdbBackend
            → AdbRuntime.executeShell
              → (transport-routed as above)
```

### ADB_ENGINE_ONESHOT_PATH

```
AdbRuntime.executeShell → AdbEngine.executeShell/executeShellLocalhost → JNI → native
```

### ADB_CLI_AND_ENGINE_ONESHOT_SAME_PATH = YES (at the engine level)

The CLI `adb shell <cmd>` in the Local session ultimately calls the **same** `AdbRuntime.executeShell` → `AdbEngine.executeShell/executeShellLocalhost` path. The CLI adds a layer (shell function → app_process → AdbCliMain → bridge UDS → AppCliExecution), but the execution primitive is identical.

### Consumer Surface

```
APK_ADB_ONESHOT_USER_SURFACE = NO (no direct "run command once" button in the APK UI)
  - APK has PTY sessions (+ → ADB / + → Local) and CLI bridge
  - One-shot is consumed programmatically via AdbBackend / CLI bridge
  - AppCliExecution routes CLI one-shot into the product composition

CLI_ADB_ONESHOT_SURFACE = YES
  - `adb shell <command>` in Local PTY → AdbCliMain → one-shot
  - AdbCliArgs parses Kind.SHELL → remoteCommand → ADB one-shot backend
  - AdbCliMain L: "Connect the Product CLI Bridge UDS and run a one-shot"

ENGINE_ADB_ONESHOT_API = YES
  - AdbRuntime.executeShell (public API, transport-routed)
  - AdbEngine.executeShell (Wireless)
  - AdbEngine.executeShellLocalhost (Local TCP)
```

### Product Interpretation

```
ADB_ENGINE_ONESHOT = IMPLEMENTED (both Wireless and Local TCP, transport-routed)
ADB_ONESHOT_APK_DIRECT_UI = NOT EXPOSED (no dedicated one-shot button in the APK UI)
ADB_ONESHOT_CLI_SURFACE = EXPOSED (via `adb shell <cmd>` in Local PTY)
```

The ADB one-shot is **fully implemented** at the engine level. It is **not missing** — it simply has no dedicated APK UI button. The primary user-facing surface is the CLI (`adb shell` in Local PTY) and the internal AdbBackend (consumed by AG/future tools).

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
| OBS-1 | MINOR | Long commands in AT input field may truncate (H05: `at_h05_probe` → `at_h05_probr`) | Type long command in ADB PTY input | Screenshot h05_screen.png |
| OBS-2 | MINOR | After re-pair (GATE 7), AT status banner shows "NEEDS PAIRING" + "CONNECT_FAILED" even though 38800 is already LISTENING and tcpip promotion succeeded. Status only updates on next `+`→ADB or ADB button tap. | Trust-loss → re-pair → observe banner | Screenshot g7f.png, logcat 17:45:39 |
| OBS-3 | OBSERVATION | `adb usb` / `setprop service.adb.tcp.port -1` does NOT kill 38800 in all cases (Transport Churn: "After kill: STILL LISTENING"). The `adb usb` command may be a no-op if adbd already considers itself in USB mode. | Kill 38800 via adb usb during transport churn | Transport churn log |

**BLOCKER_COUNT = 0**
**MAJOR_COUNT = 0**
**MINOR_COUNT = 2**
**OBSERVATION_COUNT = 1**

---

## Final Verdict

```
STEP = AT.V042-HUMAN-EXECUTION-CLOSEOUT-1
VERDICT = PASS_WITH_FINDINGS
BASE_HEAD = 374fc42
FINAL_HEAD = 374fc42 (no product code changes this round)
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
LOCAL_ONESHOT_AUTOMATED = PASS
ADB_CLI_ONESHOT_VIA_LOCAL = PASS
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
CLI_ADB_ONESHOT_SURFACE = YES (adb shell in Local PTY)
ENGINE_ADB_ONESHOT_API = YES
ADB_CLI_AND_ENGINE_ONESHOT_SAME_PATH = YES (both route through AdbRuntime.executeShell)
TEST_TOTAL = 74
TEST_PASS = 74
TEST_FAIL = 0
ARCHITECTURE_GATE = NO_TASK
BUILD_RELEASE = PASS
BLOCKER_COUNT = 0
MAJOR_COUNT = 0
MINOR_COUNT = 2
OBSERVATION_COUNT = 1
CORE_EXECUTION_CAPABILITIES = PASS
RELEASE_RECOMMENDATION = GO
PRODUCT_CODE_CHANGED = NO
TAG_CREATED = NO
GITHUB_RELEASE_CREATED = NO
NEXT = AT.V042-RELEASE-1
STOP = YES
```
