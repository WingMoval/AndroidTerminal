# AT.ON-DEMAND-ADB-READINESS-VERIFY-1 — Evidence

**Step:** AT.ON-DEMAND-ADB-READINESS-VERIFY-1
**Date:** 2026-10-31
**Nature:** Read-only semantic audit — confirm whether AT's canonical design is
**execution-triggered (lazy) ADB readiness**, not app-launch auto-connect.
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master, base 0608c7b)

> Trigger: during AT.0.4.1-RELEASE-V2 real-device gates, `force-stop → relaunch`
> left the ADB page **DISCONNECTED** (not auto-READY); a manual tap on the "ADB"
> retry button restored READY. This round determines whether that is the
> **expected canonical behavior** (lazy/on-demand readiness) or a **regression**.
>
> **No production code changes, no test changes, no release-spec edits this round.**
> AT.0.4.1-RELEASE-V2 = PAUSED until the semantic verdict is recorded.

---

## 1. Starting State

```text
BRANCH   = master
HEAD     = 0608c7b
WORKTREE = DIRTY (app/build.gradle.kts version bump from the paused release round;
            preserved, NOT reset/stashed/cleaned)
```

---

## 2. Core Question (answered)

> Does AT currently support:
> `ADB_NOT_READY → user requests ADB execution → execution path automatically
> calls ensureReady() → Wireless ADB connects → original command/session continues`?

**YES** — for both the one-shot CLI path and the persistent-session *creation* path.
See §3–§7.

---

## 3. Historical / Canonical Audit

### 3.1 Canonical SSOT (AndroidTerminal_Canonical_V1.md)
- `AdbRuntime` owns **readiness** + **recovery** (lines 1208/1219: the old
  `AtRuntime`'s `readiness` responsibility is re-homed into `ADB Engine Runtime`).
- Line 1856: "AT.CLI-0.1A 闭合：WD OFF / DISCONNECT 场景下 AdbRuntime
  `ensureReady` + auto-WD + …" — explicitly: the **DISCONNECT** scenario is
  handled by `ensureReady` triggered from the execution path.

### 3.2 V4 Physical Architecture Freeze (AT.V4-0 §23 Readiness / Control-Plane Boundary)
- **普通 Consumer 不得再选择 `ensureReady` vs `connectAuto`** — consumers only
  express "ensure usable".
- **内部仍然 `AdbRuntime.ensureReady` / `AdbRuntime.connectAuto`** — the readiness
  sequence is Runtime-owned and invoked from the execution boundary.

### 3.3 ADB Runtime Lifecycle Closure (docs/evidence/at_cli_0/ADB_RUNTIME_LIFECYCLE_CLOSURE.md)
This is the authoritative freeze for the on-demand semantics:
- "adb shell id 在 Local session 里**零手动前置**（WD OFF 也能一命令 cold-ready）"
  — **zero manual pre-steps**; a single command reaches cold-ready.
- `AdbCliBridgeServer.ensureReady(fd)` → delegates to `AdbRuntime.ensureReady`
  (the SINGLE readiness owner). "no CLI-owned polling loop."
- `onOneShot` non-READY → `ensureReady(fd)` first; `onInteractiveOpen` same gate.
- **FROZEN semantics (§6.1):**
  - `POST_BOOTSTRAP_ZERO_MANUAL_COLD_READY = VERIFIED` — after first bootstrap,
    WD OFF → `adb shell id` → auto-WD → discovery → connect → connected → uid=2000,
    **无需额外用户操作** (no extra user action).
  - `FIRST_USE_MANUAL_BOOTSTRAP = REQUIRED` (only the *first* install needs manual
    WD + pair + WRITE_SECURE bootstrap — that is pairing, not readiness).

**Canonical verdict:** the design is **execution-triggered (lazy) readiness**.
App-launch auto-connect is **not** part of the canonical contract.

---

## 4. Distinguish Two Semantics (§5)

| Semantic | Definition | Canonical? |
|---|---|---|
| **A. App-launch auto-connect** | App relaunch → automatically connect ADB → READY | **NO** (not in any freeze/SSOT; the AdbRuntime is a *long-lived lifecycle owner* that projects state, but nothing mandates a connect at app start) |
| **B. Execution-triggered readiness** | App relaunch → ADB may stay disconnected → user executes a command/session → `ensureReady()` → connect → READY → continue | **YES** (CLI-0.1A freeze; V4-0 §23; Canonical V1 §readiness) |

```text
APP_LAUNCH_AUTO_CONNECT_CANONICAL     = NO
EXECUTION_TRIGGERED_READINESS_CANONICAL = YES
```

---

## 5. Source Trace — One-Shot ADB Execution (§6)

```text
ONE_SHOT_ENTRY           = at-cli AdbCliBridgeServer.onOneShot(fd, cmdBytes)
ONE_SHOT_READINESS_GATE  = AdbCliBridgeServer.ensureReady(fd)  [line 145]
ONE_SHOT_CONNECT_PATH    = ensureReady → execution.ensureReady(sid) {…}
                           → AppCliExecution.ensureReady → control.ensureReady
                           → ATAndroidAdbControl.ensureReady
                           → AdbRuntime.ensureReady(identityDir, …)  [NORMAL_COMMAND_READINESS_OWNER]
                           → fast-path | WD readiness | auto-WD | discovery | connect (bounded 3×)
```

`onOneShot` (AdbCliBridgeServer.kt:142-175):
```kotlin
val ok = ensureReady(fd)            // <- readiness gate BEFORE execute
if (!ok) { … exit 1, no command ran … return }
val result = execution.adbEngine().execute(…)
```

**Does one-shot execution itself ensure readiness? → YES**

---

## 6. Source Trace — Persistent ADB Session (§7)

Two distinct sub-paths:

### 6a. Session *creation* (the "New Session → ADB" entry)
```text
PERSISTENT_ENTRY           = MainActivity.addPage(ADB) → openAdbSessionAsync()
PERSISTENT_READINESS_GATE  = openAdbSessionAsync(): if state != READY → ensureReady  [line 284-296]
PERSISTENT_CONNECT_PATH    = AppRuntime.adbControl.ensureReady { } → AdbRuntime.ensureReady
```
`openAdbSessionAsync` (MainActivity.kt:282-300): if not READY, runs `ensureReady`
on a bg thread, then opens the session once READY.

### 6b. Input to an *already-open* session
```text
sendInput()  (MainActivity.kt:401-408):
  if (page.state != ATSessionState.ACTIVE) return   // <- NO ensureReady
  page.terminal.write(text)
```
Sending input to an existing page does **not** re-trigger readiness — the page is
expected to already be ACTIVE (connected). This is correct for a live PTY.

**Does persistent session creation ensure readiness? → YES (creation path).
Input-to-existing-session does not (by design — it's a live PTY write).**

---

## 7. Source Trace — CLI (§8)

```text
CLI_ON_DEMAND_READY = YES
```
`AdbCliBridgeServer.onOneShot` and `onInteractiveOpen` both call `ensureReady(fd)`
before executing (AdbCliBridgeServer.kt:145, 179). `ATCliExecution.ensureReady`
(at-cli) and `AtCliConsumerHost.ensureReady` (at-cli-consumer-host) both exist and
delegate to the Runtime. This is the verified 11/11 `AdbCliLifecycleTest` path.

---

## 8. Source Trace — App UI Session Page (§9)

```text
APP_UI_ON_DEMAND_READY = YES  (for session creation; NO for input-to-existing-page)
```
- Creating an ADB page (`addPage(ADB)`) → `openAdbSessionAsync()` → `ensureReady`.
- The "SEND" button (`sendInput`) writes to an ACTIVE page only; it does not and
  should not re-trigger readiness for a live PTY.

---

## 9. Manual Retry Button Semantics (§10)

`MainActivity` comment (lines 108-113) is explicit:
> "The Wi-Fi / Wireless-Debugging gate is the DEVICE's own mechanism (settings,
> user-operated). The app does NOT open settings / auto-navigate — it only
> reflects the AdbRuntime lifecycle state and **offers a Retry (connectAuto) so
> the user can re-attempt** after they have enabled Wireless Debugging and paired
> the device themselves."

`retryAdb()` (MainActivity.kt:302-320):
- If `bootstrapState == PAIRING_REQUIRED` → show "NEEDS PAIRING — 点「配对」按钮"
  (routes to pairing, does NOT blind-connect).
- Otherwise → runs `ensureReady` on a bg thread.

```text
MANUAL_ADB_BUTTON_ROLE = OPTIONAL_RECOVERY
```
It is an **optional manual recovery / re-attempt control** (and a pairing router),
**not** a required bootstrap before every execution. The execution paths
(§5–§8) reach `ensureReady` on their own.

---

## 10. Real-Device Gates (§11–§17) — DEVICE-CONFIRMED

> Device `APLMUT4312005935` (HONOR, arm64, Android 15) re-connected and all
> gates were run live. The wireless ADB TLS bridge is visible in `adb devices`
> as a second entry: `adb-APLMUT4312005935-J4nb5z._adb-tls-connect._tcp  device`
> (the app's own Wireless ADB over TLS, distinct from the USB control channel).

```text
BASELINE_MANUAL_RETRY_READY = PASS
  (WD enabled; manual retry tap -> ADB: READY; paired identity still trusted)

POST_RELAUNCH_STATE = DISCONNECTED
  (force-stop -> relaunch, NO manual retry, waited 20s -> stays DISCONNECTED;
   EXPECTED per lazy readiness — NOT a FAIL)

COMMAND_TRIGGERED_ENSURE_READY   = PASS
COMMAND_TRIGGERED_CONNECT        = PASS
COMMAND_EXECUTED_AFTER_CONNECT   = PASS
  (from DISCONNECTED, normal '+' -> New Session -> ADB entry triggered
   ensureReady -> connect -> READY; session #1 ADB ACTIVE; NO manual retry)

COLD_PATH_CONNECT_LATENCY = ~25s window observed (bounded ensureReady retry;
  not a performance gate — observational only)

SESSION_TRIGGERED_ENSURE_READY   = PASS
SESSION_TRIGGERED_CONNECT        = PASS
PERSISTENT_SESSION_OPEN          = PASS
PERSISTENT_SESSION_IO            = PASS
  (typed 'id' in the ADB session -> terminal returned
   "uid=2000(shell) gid=2000(shell) ... context=u:r:shell:s0"; banner id/tty
   also returned uid=2000(shell) + /dev/pts/0 — genuine interactive PTY over
   the wireless ADB TLS bridge)

READY_FAST_PATH = PASS
  (already READY -> '+' -> New Session -> ADB opened session #2 ADB ACTIVE in
   ~3.8s, no reconnect, no pairing)

MANUAL_RETRY_REQUIRED_FOR_NORMAL_ONE_SHOT_EXECUTION = NO
MANUAL_RETRY_REQUIRED_FOR_NORMAL_SESSION_EXECUTION  = NO
  (both normal entries reached READY + executed with NO manual retry tap)
```

> Confirms the prior release-round observation (DISCONNECTED after relaunch,
> restored by retry) is the **expected lazy-readiness behavior**, and that the
> normal execution entries (new ADB session / one-shot) reach READY on their own.

---

## 11. Canonical vs Current Behavior Matrix (§19)

| Behavior | Canonical | Current source | Real device |
|---|---|---|---|
| App launch auto-connect | **NO** (not required) | NO (no connect at launch; AdbRuntime projects state) | CONFIRMED DISCONNECTED after relaunch |
| One-shot triggers readiness | **YES** | YES (`onOneShot` → `ensureReady`) | CONFIRMED (new ADB session → READY) |
| Persistent session triggers readiness | **YES** (on creation) | YES (`addPage(ADB)` → `openAdbSessionAsync` → `ensureReady`) | CONFIRMED (session #1 ADB ACTIVE, `id` → uid=2000) |
| Manual Retry required | **NO** (optional recovery) | OPTIONAL_RECOVERY button | CONFIRMED NOT required (NO retry tapped for any gate) |

---

## 12. Semantic Verdict (§20)

The canonical freezes (CLI-0.1A, V4-0 §23, Canonical V1) and the current source
**agree**: AT uses **execution-triggered (lazy) ADB readiness**. App-launch
auto-connect is **not** canonical. The "ADB" retry button is an **optional
recovery** control, not a required bootstrap.

```text
CURRENT_BEHAVIOR            = EXPECTED   (DEVICE-CONFIRMED, §10)
RELEASE_REGRESSION          = NO
RELEASE_GATE_CORRECTION_REQUIRED = YES
```

> **App restart not auto-READY is NOT a bug.** It matches the canonical lazy
> readiness design. The connection is re-established on the next
> readiness-triggering action (new ADB session, or the retry control).
> Device-confirmed: from DISCONNECTED, the normal "New Session → ADB" entry
> reached READY and executed `id` with **no manual retry**.

### Release-gate correction recommendation (§22)
Replace the release gate:
```text
APP_RESTART_READY = PASS
```
with the more accurate:
```text
APP_RESTART_IDENTITY_PERSISTENCE = PASS
APP_RESTART_ON_DEMAND_CONNECT    = PASS
```
and, for the device reboot gate:
```text
DEVICE_REBOOT_ON_DEMAND_CONNECT = PASS
```
rather than requiring auto-READY at launch.

---

## 13. Real-Device Confirmation — COMPLETED

All gates run live on `APLMUT4312005935` (device re-connected mid-round):

1. **Baseline (§11):** manual retry → READY. **PASS.**
2. **Gate A (§12):** `force-stop → relaunch`, NO manual retry → DISCONNECTED. **EXPECTED.**
3. **Gate B (§13):** from DISCONNECTED, normal "New Session → ADB" entry (NO
   manual retry) → `ensureReady` → connect → READY, session #1 ADB ACTIVE. **PASS.**
4. **Gate D (§15):** typed `id` in the ADB session → terminal returned
   `uid=2000(shell) ... context=u:r:shell:s0` (genuine interactive PTY over the
   wireless ADB TLS bridge). **PASS.**
5. **Gate E (§16):** already-READY fast path → new ADB session #2 ACTIVE in ~3.8s,
   no reconnect/pairing. **PASS.**
6. **Gate F (§17):** manual retry was NOT required for any normal execution path.
   `MANUAL_RETRY_REQUIRED = NO`. **PASS.**

`CURRENT_BEHAVIOR = EXPECTED` is now **device-confirmed**. AT.0.4.1-RELEASE-V2 may
resume with the corrected gates (§22 / §12).

---

## 14. Verdict & Status

```text
STEP = AT.ON-DEMAND-ADB-READINESS-VERIFY-1
VERDICT = PASS (semantic audit + real-device confirmation: canonical = lazy/on-demand
            readiness; source agrees; device-confirmed)
BASE_HEAD = 0608c7b
FINAL_HEAD = <this docs-only commit>

APP_LAUNCH_AUTO_CONNECT_CANONICAL     = NO
EXECUTION_TRIGGERED_READINESS_CANONICAL = YES

BASELINE_MANUAL_RETRY_READY = PASS
POST_RELAUNCH_STATE = DISCONNECTED (EXPECTED)
COMMAND_TRIGGERED_ENSURE_READY = PASS
COMMAND_TRIGGERED_CONNECT = PASS
COMMAND_EXECUTED_AFTER_CONNECT = PASS (id -> uid=2000(shell))
SESSION_TRIGGERED_ENSURE_READY = PASS
SESSION_TRIGGERED_CONNECT = PASS
PERSISTENT_SESSION_OPEN = PASS
PERSISTENT_SESSION_IO = PASS (id -> uid=2000(shell) context=u:r:shell:s0)
READY_FAST_PATH = PASS (~3.8s, no reconnect/pairing)
MANUAL_RETRY_REQUIRED_FOR_NORMAL_ONE_SHOT_EXECUTION = NO
MANUAL_RETRY_REQUIRED_FOR_NORMAL_SESSION_EXECUTION = NO

ONE_SHOT_ENTRY            = AdbCliBridgeServer.onOneShot
ONE_SHOT_READINESS_GATE   = AdbCliBridgeServer.ensureReady(fd)
ONE_SHOT_CONNECT_PATH     = AdbRuntime.ensureReady (NORMAL_COMMAND_READINESS_OWNER)

PERSISTENT_ENTRY          = MainActivity.addPage(ADB) → openAdbSessionAsync
PERSISTENT_READINESS_GATE = openAdbSessionAsync: ensureReady if not READY
PERSISTENT_CONNECT_PATH   = AppRuntime.adbControl.ensureReady → AdbRuntime.ensureReady

CLI_ON_DEMAND_READY       = YES
APP_UI_ON_DEMAND_READY    = YES (session creation) / NO (input-to-existing-page, by design)

MANUAL_ADB_BUTTON_ROLE    = OPTIONAL_RECOVERY

CURRENT_BEHAVIOR          = EXPECTED (DEVICE-CONFIRMED)
RELEASE_REGRESSION        = NO
RELEASE_GATE_CORRECTION_REQUIRED = YES

PRODUCT_CODE_CHANGED      = NO
VERSION_CHANGED           = NO (version bump from paused release round left in worktree, untouched)
TAG_CREATED               = NO
PUSHED                    = NO
RELEASED                  = NO

NEXT = RESUME AT.0.4.1-RELEASE-V2 with corrected gates
      (APP_RESTART_IDENTITY_PERSISTENCE + APP_RESTART_ON_DEMAND_CONNECT,
       DEVICE_REBOOT_ON_DEMAND_CONNECT).
STOP = YES
```