# AT.HUMAN-ADB-0 Evidence

## Baseline

```
BASELINE_V3 = ce48b7d（clean）
BASELINE_V2 = 726a441（43 会前 dirty）
V2_WRITES = 0
```

## Scope Recovery

前半程 5 种 HONOR pairing UX 尝试（overlay/freeform/split/MagicWindow/Accessibility）全部不可行，
已移除相关代码（AutoPairAccessibilityService / freeform 代码 / SYSTEM_ALERT_WINDOW / PairActivity 悬浮窗版）。
恢复 PairActivity 为**普通 Activity** 配对码输入窗口（非悬浮窗）。

## Pairing UX Experiment Removal

```
PAIRING_PRODUCT_UI = ABSENT
ACCESSIBILITY_PAIRING_SERVICE = ABSENT
OVERLAY_PAIRING_UI = ABSENT
PAIRING_MAGIC_WINDOW_CODE = ABSENT
PAIRING_FREEFORM_CODE = ABSENT
```

## Dependency Graph

```
:app → :at-engine, :at-backend-local, :at-backend-adb, :adb-engine
:at-backend-adb → :at-engine, :adb-engine
:adb-engine → （native ADB protocol/transport/stream/pairing）
```

`:at-engine` 0 改动，`:adb-engine` 0 改动（外部 AT.LOCAL-TCP-0 stash 排除）。

## AppRuntime Composition

```kotlin
AppRuntime.initialize(ctx)   // wire AdbRuntime + fix app keyDir (filesDir/adb_identity)
AppRuntime.adbState()        // single AdbRuntime owner
AppRuntime.connectAuto()     // drive connectAuto
AppRuntime.localEngine()     // ATEngine + LocalPersistentBackend
AppRuntime.adbEngine()       // ATEngine + AdbPersistentBackend
```

## AdbRuntime Lifecycle Owner

`AdbRuntime` owns readiness / discovery / endpoint lifecycle / connectAuto / recovery / runtime state.
`ATEngine` 不操作 ADB lifecycle。`SessionPage` 不操作 mDNS / endpoint。

## ATEngine Execution Owner

Human / Debug 都消费 `ATEngine.openSession` / `write` / `resize` / `close` / events。
`ATEngine` = terminal execution owner，backend-neutral。

## Identity

```
APP_ADB_IDENTITY_PATH = files/adb_identity/
APP_ADB_IDENTITY_SELF_GENERATED = YES
PC_ADBKEY_COPIED = NO
adbkey.pub md5 = 506a6036fdc3e82d09d946d19ba1c56c（public key hash，非 secret）
DEVICE_APP_IDENTITY_TRUST = PRESENT_INTENTIONAL（PairActivity 配对后 trusted）
```

## Identity Persistence

```
force-stop 前 adbkey.pub md5 = 506a6036fdc3e82d09d946d19ba1c56c
force-stop + relaunch 后 = 相同（506a6036fdc3e82d09d946d19ba1c56c）
APP_ADB_IDENTITY_PERSISTENCE = PASS
REPAIR_AFTER_RESTART_REQUIRED = NO
```

## connectAuto / READY

`AppRuntime.connectAuto()` → `AdbRuntime.connectAuto(keyDir)` → READY（trusted identity）。

## Human Path（AdbHumanPreviewTest 6/6）

```
h1: ADB READY visible
h2: ADB page id → uid=2000(shell)
h3: multi ADB session isolation（A cwd /data/local/tmp，B 独立）
h4: Local + ADB coexistence
h5: Ctrl-C（sleep 30 → 0x03 → AFTER_CTRL_C，session ACTIVE）
h6: resize isolation（40x120）
```

## Debug Path（CommonConsumerPathTest 3/3 + ProgrammaticAdbConsumerTest 7/7）

```
CommonConsumerPathTest:
  C1-C4 Local session（open/write/observe/resize/close）
  C5-C10 ADB session（connectAuto → open → id → uid=2000 → resize → close）
  C11 ADB persistent cwd/env

ProgrammaticAdbConsumerTest（预留程序化通路，无 UI）:
  principalUid2000: id → uid=2000(shell)
  persistentCwd: cd /data/local/tmp → pwd
  persistentEnv: export AT_PREVIEW=OK → echo → OK
  ctrlC: sleep 30 → 0x03 → AFTER_CTRL_C
  resizeIsolation: c1 40x120, c2 24x80 不变
  multiSessionIsolation: A/B 独立 cwd
  localAdbCoexistence: ADB uid=2000, Local app UID（非 2000）
```

`DEBUG_RESULT_SOURCE = AT_SESSION_OUTPUT`（非 logcat scraping）。
`DEBUG_REQUIRES_HUMAN_UI/IME/UIAUTOMATOR = NO`。

## cwd / env Persistence

```
ADB_PERSISTENT_CWD = PASS（cd /data/local/tmp → pwd = /data/local/tmp）
ADB_PERSISTENT_ENV = PASS（export AT_PREVIEW=OK → echo $AT_PREVIEW = OK）
```

## Ctrl-C

```
ADB_UI_CTRL_C = PASS（sleep 30 → 0x03 → SIGINT → echo AFTER_CTRL_C 仍工作，session ACTIVE）
```

## Resize

```
ADB_UI_RESIZE = PASS（40x120 stty size 反映 40）
ADB_UI_RESIZE_ISOLATION = PASS（c2 不受影响，仍 24）
```

## Multi-session

```
ADB_HUMAN_MULTI_SESSION = PASS
ADB_SESSION_COUNT = 3（ProgrammaticAdbConsumerTest 用 2，AdbHumanPreviewTest h3 用 2）
PHYSICAL_ADB_CONNECTION_COUNT = 1（单 AdbRuntime owner）
TLS_HANDSHAKE_COUNT = 1
RX_OWNER_COUNT = 1
SESSION_IO_ISOLATION = PASS
SESSION_CWD_ISOLATION = PASS
SESSION_CTRL_C_ISOLATION = PASS
SESSION_RESIZE_ISOLATION = PASS
SESSION_CLOSE_ISOLATION = PASS
INACTIVE_OUTPUT_BUFFERING = PASS
```

## Local + ADB Coexistence

```
LOCAL_ADB_COEXISTENCE = PASS（Local app UID + ADB uid=2000 同时 ACTIVE）
LOCAL_BACKEND_REQUIRED_BY_ADB_PREVIEW = NO
```

## Process Restart

```
ADB_PREVIEW_PROCESS_RESTART = PASS（force-stop → relaunch → same identity → READY → id uid=2000）
REPAIR_AFTER_RESTART_REQUIRED = NO
```

## APK

```
APK_PATH = D:\codebank\AndCode\AndroidTerminal-V3\app\build\outputs\apk\debug\app-debug.apk
APK_SIZE = 7817845
APK_SHA256 = 17858358592E5962A9B16B32F41D0B884B522B78A431D14F4FF4439CED3C8B43
APK_INSTALLED = YES
```

## Screenshot Index

```
docs/evidence/at_human_adb_0/
  h1_adb_ready.png
  h2_adb_id_uid2000.png
  h3_multi_adb_isolation.png
  h4_local_adb_coexist.png
  h5_ctrlc_continue.png
  h6_adb_resize.png
```

## Regression Summary

```
LOCAL_REGRESSION = 11/11
APP_DEBUG_INPUT_REGRESSION = 7/7
AT_ENGINE_TESTS = 6/6（JVM）
SHELLLESS_REGRESSION = 15/15
PAIRING_DETERMINISTIC_REGRESSION = 54/54
COMMON_CONSUMER_PATH_TEST = 3/3
ADB_HUMAN_PREVIEW_TEST = 6/6
PROGRAMMATIC_ADB_CONSUMER_TEST = 7/7
test-host ADB Headless/Parity/Runtime = 设备本地回环 adbd key 认证受限
  （fresh 设备无 root，/data/misc/adb 不可写；独立 ADB provider regression，
  非本轮 Human ADB 集成问题）
```

## Security

- 不保存 private ADB key / pairing code 到 docs/evidence/git/log
- 记录：public key hash（adbkey.pub md5）/ non-secret key dir description
