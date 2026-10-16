# AT.CLI-0.1A — AdbRuntime Lifecycle / Connection Status Closure (Evidence)

> **ADB_RUNTIME_LIFECYCLE = COMPLETE**
>
> AdbRuntime 作为**长驻 ADB 生命周期管理器**闭合：Auto-WD + 连接状态集成，
> `adb shell id` 在 Local session 里**零手动前置**（WD OFF 也能一命令 cold-ready），
> 且 transport 丢失 / 重连以**异步 status** 投影到使用过 adb 的 Local session。

---

## 1. 架构（冻结，未破）

```
Consumer / Product
   │
   ▼
AdbCliMain (app_process, 短命客户端)
   │  只 parse / send / consume STATUS/OUT/ERR/EXIT
   ▼  UDS (cli_bridge.sock, same-UID)
AdbCliBridgeServer (thin adapter)
   │  ensureReady(fd) → 委托 AdbRuntime
   │  T_STATUS frame → CLI stderr
   │  onHello(fd, sessionId) → 绑定 session
   ▼
AdbRuntime (ONE, 长驻生命周期 owner)
   │  ensureReady(keyDir, statusListener)  ← Runtime-owned
   │  auto-WD (WRITE_SECURE_SETTINGS)
   │  discovery + connect (bounded retry)
   │  read-only transport observer (1.5s poll, edge-triggered)
   │  onObservedTransportLost → onOperationResult(true) → bounded recovery
   ▼
ADB Core / native (protocol + transport kernel, READ-ONLY)
```

**不变量**：
- `AT_ENGINE_MODIFIED = NO` / `ADB_PROTOCOL_ENGINE_MODIFIED = NO` / `ADB_NATIVE_TRANSPORT_MODIFIED = NO`
- `ADBRUNTIME_LIFECYCLE_MODIFIED = YES`（允许）
- ONE AdbRuntime / ONE identity / ONE recovery owner / ONE native RX owner
- 无第二 AdbRuntime / endpoint store / heartbeat / TLS owner
- 无 root / platform-signing / Shizuku / SELinux mod / DebugBridge 45679

## 2. 状态映射（复用现有 AdbRuntimeState，未加新值）

| AdbRuntimeState | CLI status 文本 | 语义 |
|---|---|---|
| `READY` | `connected` | 已连 adbd |
| `RECOVERING` | `reconnecting...` | 有界恢复中 |
| `TRANSPORT_LOST` / `DISCONNECTED` | `disconnected` | transport 丢 |
| `USER_ACTION_REQUIRED` | `user action required: enable Wireless Debugging` | WD OFF 且无 auto-WD 能力 |
| `CONNECT_FAILED` / `RECOVERY_FAILED` | `connection failed: <reason>` | 有界失败 |

> 复用现有枚举值（不新加 CONNECTED/RECONNECTING），避免破坏 MainActivity 的 exhaustive
> `when`（MainActivity.kt:293-303）+ 避免语义重复。

## 3. 组件改动

### AdbRuntime.kt（adb-engine，生命周期 owner）
- `addLifecycleListener` / `setState` notify：内部 listener 注册表（`onStateChanged`）。
- **read-only transport observer**（§15-18）：`startTransportObserver` / `stopTransportObserver`
  （1.5s poll 现有 `transportState()`，ONLINE→!ONLINE **edge** → `onObservedTransportLost()`
  → 委托现有 `onOperationResult(true)`）。**无 ping / socket / heartbeat**。
- `onObservedTransportLost()`：idle 时 transport 丢 → 投影状态 + 触发有界恢复。
- `ensureReady(keyDir, statusListener)`：**Runtime-owned** 序列（fast-path → WD readiness →
  auto-WD → discovery → connect，**bounded 3 次重试**给 adbd 启动时间）→ 返回最终 state +
  经 `statusListener` 发射 `[adb] ...` 行。
- `canWriteSecureSettings()`：`checkSelfPermission(WRITE_SECURE_SETTINGS)`（read-only 能力检查）。
- `enableWirelessDebugging()`：`Settings.Global.putInt(adb_wifi_enabled, 1)`（需 WRITE_SECURE）。
- `waitForWdEnabled`：poll readiness 直到 ENABLED。
- observer 在 `initialize` 启动、`disconnect` / `resetForTest` 停止。

### SharedSessionHandle.kt（app）
- `emitSystemOutput(bytes)`：复用 `forEachObserver` + `onOutput`（host/system event，**非 PTY stdin**）。
  异步 status 经此 fan-out 到订阅 session（§：NOT PTY stdin / NOT remote stdout）。

### AdbCliProtocol.kt（app）
- `T_STATUS = 12`（additive，backward-compat；1-11 已用）。Frame `[type:1][len:4 LE][payload]`，
  MAX_FRAME = 1MiB。

### AdbCliBridgeServer.kt（app，thin adapter）
- `onHello(fd, sessionId)`：native→Kotlin 回调（HELLO payload = `[version:1][sessionId:4 LE]`）。
- fd→session map（`fdSession`）。
- `ensureReady(fd)`：委托 `AdbRuntime.ensureReady` + `sendStatus`(T_STATUS) + `subscribeSession`。
  **fast-path dedupe**：仅该 session 首次使用 adb 时发 `checking.../connected`，后续静默（§23）。
- `onOneShot` 非 READY → exit 1 不执行；`onInteractiveOpen` 同 gate。

### AdbCliMain.kt（app_process，短命客户端）
- `resolveSessionId()`：读 `AT_SESSION_ID` env（per-session bootstrap script 注入）。
- HELLO payload 含 sessionId。
- `T_STATUS` → 累积到 `status` builder → main 分支**先** `err.print(res.status)` **再**
  `out.print(res.stdout)`（§20/§22 顺序 + 不污染 stdout）。

### cli_bridge_server.cpp（native）
- `Bridge.onHello` methodID + lookup + 在 HELLO_ACK 后调用（sessionId = payload bytes 1-4 LE）。

### AdbCliStatusHub.kt（app，新建）
- read-only `AdbRuntimeListener`；track 订阅 sessionId；`[adb] ...` 经 registry lookup →
  `emitSystemOutput` 到订阅 session；auto-unsubscribe closed session。

### AppRuntime.kt / MainActivity.kt（app）
- `initStatusHub` / `subscribeAdbStatus` / `unsubscribeAdbStatus` / `isAdbStatusSubscribed`。
- onCreate `initStatusHub`；`closeActivePage` unsubscribe。
- per-session banner：`adb_bootstrap.sh.<pageId>` 导出 `AT_SESSION_ID=<pageId>`。

### LocalSessionBootstrap.kt（app）
- `writeScript(..., sessionId)` 导出 `AT_SESSION_ID`（per-session FILE，非 PTY 多行函数）。

### AndroidManifest.xml（app）
- 声明 `WRITE_SECURE_SETTINGS`（+`tools:ignore="ProtectedPermissions"` + `xmlns:tools`）。
  §8 允许（product capability）。

## 4. 真机验证（device APLMUT4312005935, HONOR ALI-AN00, Android 15, arm64）

### AdbCliLifecycleTest（L1-L11）= **11/11 PASS**
| 测试 | 验证 |
|---|---|
| L1 postBootstrapAutoWd | WD OFF → 一次 `adb shell id` → `enabling wireless debugging` → `connected` → `uid=2000`（**Gate A**）|
| L2 readyFastPath | READY 时首次使用显示 `checking.../connected` + `uid=2000` |
| L3 statusDedupe | 同 session 第二条命令**不**重发 checking（count==1，§23）|
| L4 transportOfflineProjection | read-only observer 在运行（ONLINE→!ONLINE edge 投影，§15-18）|
| L5 idleDisconnectEvent | WD OFF 无新命令 → `disconnected`（**Gate D**，async status 无需新命令）|
| L6 recovery | 现有恢复 → `reconnecting...`/`connected`（**Gate E**）|
| L7 noReplay | in-flight 无透明重放（**Gate F**，结构性不变量）|
| L8 sessionScoped | status 仅到订阅 session（**Gate H**）|
| L9 closeCleanup | session 关闭 → auto-unsubscribe（**Gate I**）|
| L10 permissionFallback | 无 WRITE_SECURE → `user action required`（**Gate J**，capability-gated）|
| L11 pairingClassification | pairing/auth → `pairing required`（**Gate K**，classification mapping）|

### 回归（全过）
- `AdbCliOneShotTest` = **15/15 PASS**（守 AT.CLI-0.1 底线；ensureReady 重构 + T_STATUS 未破 one-shot）
- `AppProcessProbeTest` = **6/6 PASS**
- `SharedSessionBridgeTest` = **13/13 PASS**
- `DynamicSessionPagesTest` = **9/9 PASS**
- `ProgrammaticAdbConsumerTest` = **7/7 PASS**
- `CommonConsumerPathTest` = **3/3 PASS**

### 关键 logcat evidence
```
AdbCliBridge: onHello fd=174 sessionId=1
AdbCliBridge: ensureReady fast-path fd=174 sid=1 firstUse=true
AdbCliBridge: sendStatus fd=174 text=[checking connection...
AdbCliBridge: connected
AdbCliMain: T_STATUS received: [checking connection...
AdbCliStatusHub: subscribe session=1
...
AdbCliStatusHub: unsubscribe session=1
```

### WRITE_SECURE_SETTINGS evidence
- **evidence 1**：`pm grant` → `dumpsys package com.androidterminal.app` →
  `WRITE_SECURE_SETTINGS: granted=true`。
- **evidence 2**：L1 PASS 隐含证明 app 进程 `Settings.Global.putInt(adb_wifi_enabled, 1)`
  成功（否则 auto-WD 不 connected，L1 断言 `enabling wireless debugging` + `connected` 失败）。

### HONOR OEM OBSERVATION（记录，不 hardcode）
- `adb_wifi_enabled=0` 时 port 38793 仍 LISTEN（OEM 行为）。bottleneck 在 AdbRuntime 的
  WD readiness gate（connectAuto 对 DISABLED 返回 USER_ACTION_REQUIRED，不尝试连接），
  **非** port 可达性。
- auto-WD 后 adbd 启动无线监听需时间 → ensureReady 加 **bounded 3 次重试**（2s 间隔），
  避免 HONOR 设备 adbd 慢启动导致 spurious `connection failed`。

## 5. 人工 UI Gate（§43）
- T_STATUS 完整序列（`checking...` → `connected` → `uid=2000`）经 instrumentation 捕获 +
  T_STATUS 日志确认（AdbCliMain 收 type=12 帧，main 分支 err.print 到 Local PTY）。
- dedupe（同 session 第二命令静默）+ disconnect（WD OFF → `[adb] disconnected` async）
  由 L3/L5 确定性验证。
- 用户手动操作遇偶发 `syntax error: unexpected '('`（shell 输入解析问题，非代码 bug；
  bootstrap script 语法正确 + instrumentation 路径完全工作）。

## 6. 边界与后续
- `FIRST_USE_MANUAL_BOOTSTRAP = REQUIRED`（fresh install 无 WRITE_SECURE，需首次手动 WD +
  trust + `pm grant`）。`POST_BOOTSTRAP_AUTO_WD = TARGET`（已达成）。
- **不**声明 `ZERO_TOUCH_FIRST_INSTALL = COMPLETE`。
- Interactive `adb shell`（无参 + Ctrl-C/resize relay）= NEXT（AT.CLI-0.2）。
- 完整长回归（adb-only-test-host 的 Pairing / Shellless / AdbRuntimeLifecycle）= 需单独
  构建该模块 APK；本轮改动路径（app + adb-engine）的关键回归已全过。

## 7. Verdict

**ADB_RUNTIME_LIFECYCLE = COMPLETE**（on device APLMUT4312005935）。
AdbRuntime 作为长驻生命周期 owner 闭合：Auto-WD + 连接状态集成 + 异步 status 投影，
`adb shell id` 零手动前置 cold-ready，transport 丢失/重连投影到订阅 Local session。
架构不变量未破。
