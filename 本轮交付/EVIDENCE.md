# AT.DEBUG-0 Evidence

## Baseline

```
BASELINE_V3 = 4f8fbe2（clean）
BASELINE_V2 = 726a441（43 会前 dirty）
V2_WRITES = 0
```

## Dependency Graph

```
:app → :at-engine, :at-backend-local, :at-backend-adb, :adb-engine
本轮新增（:app host 层）：
  SharedSessionHandle / AppSessionRegistry / debug/DebugBridge / debug/DebugProtocol
:at-engine diff = 0
:adb-engine diff = 0
```

## Session Ownership Before

- Human 创建 session：`ATEngine.openSession` → `AppTerminal.session`
- 单 listener：`AppTerminal` 构造传入的 eventListener lambda
- Human/Debug 各自独立 session（不共享）

## Session Ownership After

- Human 创建 session：同（`ATEngine.openSession` → `AppTerminal.session`）
- `AppTerminal.open` 创建 `SharedSessionHandle(sessionId, kind, session)` + `register` 到 `AppSessionRegistry`
- `SharedSessionHandle.attachSessionListener()` = **单一** session listener（RX_OWNER=1）
- Human observer（eventListener）+ Debug bridge observer 都 `addObserver` 到 handle
- write：Human/Debug 都进 `SharedSessionHandle.write(source, bytes)`（serialized，单线程）
- DebugBridge：attach(sessionId) → `registry.lookup` → handle（**不**创建新 session）

## Session ID Mapping

`SessionPage.pageId`（AtomicInteger，稳定）→ `SharedSessionHandle.sessionId` → `AppSessionRegistry` key → DebugBridge `attach(sessionId)`。

## Human-created Session + Debug attach

`SharedSessionBridgeTest`：
- `createLocalPage()` / `createAdbPage()` 走 MainActivity（Human 路径）→ 注册到 registry
- `BridgeClient`（loopback socket，扮演 PC）→ `list` 看到 session → `attach(sessionId)`
- `ATTACH_EXISTING_SESSION = PASS`
- `SESSION_INSTANCE_SHARED = YES`（同一 ATTerminalSession，单 listener）
- `DEBUG_CREATED_SECOND_SESSION = NO`（attach 前后 session 数量不变）

## Debug write → UI

B4：Debug `write(id=1, "echo FROM_DEBUG\n")` → Human UI 显示 `DEBUG > echo FROM_DEBUG`（annotation）+ `FROM_DEBUG`（remote output）。
`DEBUG_INPUT_VISIBLE_IN_UI = PASS` / `DEBUG_OUTPUT_VISIBLE_IN_UI = PASS`。

## Shared output fan-out

同一 ATTerminalSession output → SharedSessionHandle fan-out → Human observer（outputBuffer）+ Debug bridge observer（broadcast 到 PC client）。
`SHARED_OUTPUT_FANOUT = PASS`。

## Shared cwd

B5：Debug `cd /data/local/tmp` → Human `pwd` → `/data/local/tmp`。
`SHARED_SESSION_CWD = PASS`。

## Shared env

B6：Human `export AT_SHARED=OK` → Debug `echo $AT_SHARED` → `OK`（UI 也看到）。
`SHARED_SESSION_ENV = PASS`。

## Ctrl-C

B8：Human `sleep 30` → Debug `write_raw(0x03)` → 同 session SIGINT → Human `echo AFTER_SHARED_CTRL_C` 仍工作。
`SHARED_CTRL_C = PASS`。

## Human input → Debug

B7：Human `echo FROM_HUMAN`（UI）→ Debug 收到 `input{source=HUMAN}` + output `FROM_HUMAN`。
`HUMAN_INPUT_VISIBLE_TO_DEBUG = PASS` / `HUMAN_OUTPUT_VISIBLE_TO_DEBUG = PASS`。

## Multi-session target isolation

B9：Human 建 2 个 session → Debug attach session2 → `export DEBUG_TARGET=ADB2` → session1 `echo $DEBUG_TARGET` 空（不污染）。
`SHARED_MULTI_SESSION_TARGET_ISOLATION = PASS`。

## Debug disconnect / reattach

B10：debug client close → session 仍 ACTIVE（`DEBUG_DISCONNECT_SESSION_SURVIVES = PASS`）。
B11：reconnect + reattach same sessionId → `echo REATTACHED` 可见（`DEBUG_REATTACH = PASS`）。

## Human close propagates

B12：Human close page → Debug 收到 `closed`（`HUMAN_CLOSE_PROPAGATES_TO_DEBUG = PASS`）。
B13：closed 后 write → `rejected(SESSION_CLOSED)`（`DEBUG_CANNOT_RESURRECT_CLOSED_SESSION = PASS`）。

## Physical ADB connection / TLS / RX owner

- `PHYSICAL_ADB_CONNECTION_COUNT = 1`（单 AdbRuntime owner）
- `TLS_HANDSHAKE_COUNT = 1`
- `RX_OWNER_COUNT = 1`（session 单一 listener = SharedSessionHandle）
- `ADB_STREAM_COUNT_DELTA_ON_DEBUG_ATTACH = 0`（attach 不新增 ADB stream）
- `TLS_HANDSHAKE_DELTA_ON_DEBUG_ATTACH = 0`

## Local shared session

SharedSessionHandle provider-neutral。Local session 同样 fan-out（B 测试用 Local 验证机制）。
`LOCAL_SYSTEM_SH_REQUIRED_BY_DEBUG_SHARED_ADB_SESSION = NO`。

## Debug port / adb forward role

```
DEBUG_PORT = 45679
DEBUG_TRANSPORT = USB_ADB_FORWARD_TO_LOOPBACK_TCP_JSONL
PC_ADB_ROLE = DEBUG_TRANSPORT_ONLY
PC_ADB_EXECUTES_TERMINAL_COMMAND = NO
EMBEDDED_ADB_ENGINE_EXECUTES_TERMINAL_COMMAND = YES
```

## Release bridge absence

```
DEBUG_BUILD_BRIDGE = PRESENT（debug APK 45679 LISTEN）
RELEASE_DEBUG_BRIDGE_SURFACE = ABSENT（release APK 无 45679 监听）
```

release APK 签名 + 安装 + 启动后 `netstat` 无 45679。`BuildConfig.DEBUG` gate 生效。

## Regression

```
LOCAL_REGRESSION = 11/11
APP_REGRESSION = 7/7
AT_ENGINE_TESTS = 6/6
SHELLLESS_REGRESSION = 15/15
PAIRING_REGRESSION = 54/54
COMMON_CONSUMER_PATH_REGRESSION = 3/3
ADB_HUMAN_PREVIEW_REGRESSION = 6/6
PROGRAMMATIC_CONSUMER_REGRESSION = 7/7
SHARED_SESSION_BRIDGE_TEST = 13/13
RELEASE_ISOLATION_TEST = PASS
HOST_DEBUG_CLIENT = PASS（tools/at_debug_client.py，stdlib-only）
```

## Screenshot Index

```
docs/evidence/at_debug_0/
  d1_debug_connected.png（SessionPage + DebugBridge CONNECTED）
  d2_debug_input_ui.png（DEBUG > id + uid=2000(shell)）
  d3_shared_cwd.png（Debug cd + Human pwd → /data/local/tmp）
  d4_shared_env.png（Human export + Debug echo → OK）
  d5_shared_ctrlc.png（Human sleep + Debug Ctrl-C + echo AFTER）
  d6_multi_session_target.png（多 session）
```

## Security

- loopback-only bind（127.0.0.1）
- debug-build gate
- 不保存 secret key / pairing code / sensitive commands
- 测试只用 id/pwd/echo/export/sleep 等非敏感命令
