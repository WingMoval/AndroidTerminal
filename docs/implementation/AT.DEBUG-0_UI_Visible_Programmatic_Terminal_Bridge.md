# AT.DEBUG-0 — UI-visible Programmatic Terminal Bridge

> 参考已验证的 AG.DEBUG-0 模式，为 V3 增加一个**仅存在于 debug build** 的
> UI-visible Programmatic Terminal Bridge：PC/OpenCode 通过 `adb forward` +
> loopback DebugBridge attach 到 Human UI 已打开的真实 Terminal Session，
> 程序化输入写入**同一个** ATTerminalSession，Human UI 与 Debug 同时观察。

## 1. Objective

让程序化 Debug consumer **attach** 到 Human UI 已存在的真实 Terminal Session
（不创建第二个隐藏 session），实现：

- OpenCode 输入 `id` → 手机同一个 ADB1 页面实时看到 `DEBUG > id` + `uid=2000(shell)`
- Debug `cd /data/local/tmp` 后 Human `pwd` → `/data/local/tmp`（同 session 同 cwd）
- Human `export` 后 Debug `echo` → 同值（同 session 同 env）
- Human/Debug 双向 observable（input activity + output + state）

## 2. Baseline

```
BASELINE_V3 = 4f8fbe2（clean）
BASELINE_V2 = 726a441（43 会前 dirty）
V2_WRITES = 0
```

## 3. AG.DEBUG-0 Reference Audit

AG.DEBUG-0（`AndroidAgent/app/.../debug/DebugBridge.kt`）已验证模式：

- **DebugBridge**：127.0.0.1:45678 TCP JSONL server，loopback-only，release gate（`BuildConfig.DEBUG`），无 nonce（结构性安全）
- **两层 fan-out**：`CompositeSink`（per-event N observers，fault-isolated）→ `DebugBridge`（per-line M sockets，单线程 ordered send executor）
- **provider-lambda DI**
- **单线程 send executor**（修 NetworkOnMainThreadException + 保 JSONL 序）
- **stdlib-only PC client**（reader thread + main thread stdin→JSONL）
- **ClientWriter**（writer-only，`send` 返回 bool，dead writer 就地驱逐）

AT 本轮采用**相同设计原则**，但**不复制 AG Agent semantics**（AT 仍是 Terminal execution subsystem，DebugBridge 不是 execution backend）。

## 4. Existing Session Ownership Audit（S1-S12）

| 项 | 结论 |
|---|---|
| S1 谁创建 ATTerminalSession | `ATEngine.openSession(Launch)` → `ATOpenSessionResult.Success(session)` |
| S2 AppTerminal 持有 session? | 是（`AppTerminal.session` var） |
| S3 SessionPageController 持有 session? | 通过 `SessionPage.terminal`（AppTerminal）间接 |
| S4 SessionPage close | `page.close()` → `terminal.close()` → `session.close()` |
| S5 MainActivity onDestroy | `pages.closeAll()` |
| S6 active page ID | `activeIndex`（int，切换变） |
| S7 稳定 page/session identity | `SessionPage.pageId`（AtomicInteger，生命周期内不变）✅ 复用 |
| S8 output listener 注册 | `AppTerminal.open` 里 `session.setListener { eventListener }` |
| S9 Human input 进 write | `MainActivity.sendInput` → `page.terminal.write(...)` |
| S10 多 observer 原生支持? | **否**（`setListener` 单 listener） |
| S11 listener ownership | 在 `AppTerminal`（构造传入的 eventListener lambda） |
| S12 不改 AT contract 的 fan-out | App 层 `SharedSessionHandle` 注册**单一** session listener，fan-out 给 Human + Debug |

**关键**：`ATTerminalSession.setListener` 是**单 listener**，所以 App 层
`SharedSessionHandle` 作为**唯一** listener，把事件 fan-out 给 Human observer +
Debug observers。RX_OWNER = 1（session 只有一个 listener = handle）。

## 5. Current Human/Debug Separation

AT.HUMAN-ADB-0 后：Human 和 Debug **各自** `AppRuntime.adbEngine().openSession` 创建
**独立** ATTerminalSession（共享 ATEngine contract + 单 ADB 物理连接，但 session 独立）。
Debug 输入不在 Human UI 可见。

## 6. Target Architecture

```
                          ATTerminalSession（唯一实例，Human 创建）
                                       ↑ setListener（单一 listener）
                              SharedSessionHandle（:app host 层）
                                - serialized write path（FIFO，Human/Debug 不交叉）
                                - fan-out：output/state → Human observer + Debug observers
                                - input-activity event（HUMAN/DEBUG source）
                                       ↑
                              SessionPage（Human lifecycle authority）
                                       ↑
                              AppSessionRegistry（:app，sessionId → SharedSessionHandle）
                                       ↑
                              DebugBridge（debug-only，127.0.0.1:45679 TCP JSONL）
                                - attach(sessionId) → 获取 SharedSessionHandle（不创建新 session）
                                - write → SharedSessionHandle.write(DEBUG, bytes)
                                - fan-out → broadcast 到 PC clients
```

只有**一个**真实 ATTerminalSession。Human 和 Debug 是同一个 Session 的两个 consumer。

## 7. AppSessionRegistry / Host

`:app` 进程作用域薄 registry：`sessionId → SharedSessionHandle`。
职责仅限 list/lookup/register/unregister。**不** discover/connect/pair/管理 TLS/执行 ADB/创建关闭 session。
DebugBridge 用它 list/attach Human-visible session（不创建隐藏 session）。

## 8. SharedSessionHandle

`:app` host 层，包裹**一个** existing ATTerminalSession：

- **单一 session listener**，fan-out 给所有 registered observers（Human UI + Debug bridge）
- **serialized write path**：`write(source, bytes)` 进单线程 writer，每次 `write(bytes)` 是一个不交叉的 write unit（Human/Debug 不 byte-interleave）
- **input-activity event**（`source = HUMAN/DEBUG`）用于 UI annotation（**不是** remote stdout）
- session lifecycle authority 仍是 Human page；handle 只 reference，不 create/own/destroy

## 9. Session Identity

复用 `SessionPage.pageId`（稳定、唯一、生命周期内不变）。不增加 `ATTerminalSession.id` 到 AT Engine contract。

## 10. Observer Fan-out

`SharedSessionHandle.attachSessionListener()` 注册单一 session listener。
每个 observer（Human UI / Debug bridge）通过 `addObserver` 注册。
fan-out 时 **fault isolation**（一个 observer 异常不影响其他）。
RX_OWNER = 1（session 只有一个 listener）。

## 11. Input Activity Events

`SharedSessionHandle.write(source, bytes)` 后发 `input-activity` 给 observers。
Human UI 对 **DEBUG** source 显示 `DEBUG > <text>` annotation（本地 UI annotation，
**不是** terminal output，不塞回 ATTerminalSession output stream）。
Human source 不显示 annotation（用户看到自己的 EditText + remote echo）。
Debug client 也能收到 **HUMAN** source 的 input event（双向 observable）。

## 12. Write Serialization

所有 write（Human + Debug）进 `SharedSessionHandle` 的**单线程 writer**。
每次 `write(bytes)` 是一个原子 unit，不 byte-interleave。
不实现 command semantic locking / ownership state machine（本轮只解决 write serialization）。

## 13. DebugBridge

debug-only，`127.0.0.1:45679` TCP JSONL server（loopback-only，避免与 AG 45678 冲突）。

- **start()**：bind loopback + accept loop（daemon thread）
- **handleClient**：每 client 一个 reader thread + ClientWriter；`finally` 清理（disconnect 只 detach，**不** close session）
- **sendExecutor**：单线程 ordered broadcast（修 NetworkOnMainThreadException + 保 JSONL 序）
- **ClientWriter**：writer-only，`send` 返回 bool，dead writer 就地驱逐
- **release gate**：`MainActivity.onCreate` 里 `if (BuildConfig.DEBUG) { debugBridge?.start() }`

## 14. Developer Transport

```
PC: adb forward tcp:45679 tcp:45679
    python tools/at_debug_client.py list
    python tools/at_debug_client.py attach <sessionId|active>
    python tools/at_debug_client.py send-text <sessionId> "id\n"
```

`adb forward` 是 **PC→loopback 隧道**（developer transport only），**不**执行 terminal command。
embedded ADB Engine 执行。`PC_ADB_ROLE = DEBUG_TRANSPORT_ONLY`。

## 15. Protocol

JSONL，developer-only，non-canonical。消息：

- Client→device：`ping` / `list` / `attach{sessionId}` / `detach` / `write{sessionId,text}` / `write_raw{sessionId,base64}`
- Device→client：`hello{protocol}` / `sessions[...]` / `attached{sessionId}` / `detached` / `input{sessionId,source,text}` / `output{sessionId,data(base64)}` / `state{sessionId,state}` / `closed{sessionId}` / `pong` / `error` / `accepted` / `rejected`

`DEBUG_PROTOCOL_STATUS = DEVELOPER_ONLY / NON_CANONICAL`。

## 16. Human UI Visibility

最小 UI 改动：

- `tvDebugBridge`：DebugBridge 状态（OFF/LISTENING/CONNECTED + 端口），debug build 可见
- SessionPage output：DEBUG source 的 input 显示 `DEBUG > <text>` annotation（本地，非 remote stdout）

无 Debug dashboard / session manager screen / remote control center。

## 17. ADB Shared Session

```
Debug: id → SharedSessionHandle.write(DEBUG) → ATTerminalSession → AdbPersistentBackend
→ ADB Engine → adbd → remote PTY → uid=2000(shell)
```

Debug attach **不**创建新 ADB stream / TLS / PTY（attach 的就是 existing session）。
`LOCAL_SYSTEM_SH_REQUIRED_BY_DEBUG_SHARED_ADB_SESSION = NO`。

## 18. Local Shared Session

Local session 同样走 SharedSessionHandle（provider-neutral）。
Debug `export LOCAL_SHARED=YES` → Human `echo $LOCAL_SHARED` → PASS。
Local regression 不成为 ADB shared-session 依赖。

## 19. Multi-session Isolation

Human 建 ADB1/ADB2/ADB3 → DebugBridge `list` 看到 3 个 sessionId。
attach ADB2 → 改 ADB2 cwd/env → ADB1/ADB3 不受影响（target isolation）。

## 20. Lifecycle

- Human page close → `SharedSessionHandle.close()` → session close → Debug 收到 `closed`
- Debug disconnect → detach（**不** close session）→ Human 继续用
- Debug reconnect → re-attach same sessionId → 继续观察 + write
- Activity destroy → `pages.closeAll()` + registry unregister + bridge stop
- Process death → shared session 死亡（不做 cross-process restore）

## 21. Disconnect / Reattach

B10/B11 验证：debug client close 后 session 存活；重新 attach same sessionId 继续工作。

## 22. Close Semantics

B12/B13 验证：Human close → Debug 收到 `closed`；closed 后 write → `rejected(SESSION_CLOSED)`（handle 保留在 registry，closed 状态，直到 Activity destroy unregister）。

## 23. Release Isolation

```
DEBUG_BUILD_BRIDGE = PRESENT（debug APK 45679 监听）
RELEASE_DEBUG_BRIDGE_SURFACE = ABSENT（release APK 无 45679 监听）
```

`BuildConfig.DEBUG` gate 保证 release 不 start bridge。

## 24. Security

- loopback-only bind（127.0.0.1，非 0.0.0.0/LAN）
- debug-build gate
- single client（多 client DEFERRED）
- 无 nonce/handshake/authorization（结构性安全，参考 AG.DEBUG-0）
- input-activity 不持久化；测试只用 id/pwd/echo/export/sleep 等非敏感命令

## 25. Regression

```
Local 11/11 · AppDebugInput 7/7 · AT Engine 6/6 · Shellless 15/15
Pairing 54/54 · CommonConsumer 3/3 · AdbHumanPreview 6/6 · ProgrammaticAdbConsumer 7/7
SharedSessionBridgeTest 13/13（B1-B14，b14 ADB 路径）
Release isolation PASS（debug PRESENT / release ABSENT）
```

## 26. Real-device Validation

设备 `APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64），USB + WD=1。
`adb forward tcp:45679 tcp:45679`。SharedSessionBridgeTest B1-B14 真机 PASS。
DebugBridgeScreenshotTest d1-d6 截图（Human ADB session + Debug attach + write + 共享 cwd/env/Ctrl-C）。

## 27. Final Verdict

见 Final Verdict 块。

## 28. Next

`NEXT = AT.CLI-0`（out-of-process CLI frontend）。AT.DEBUG-0 是 developer-only shared-session bridge，与 AT.CLI-0 是两个不同协议，不能混。
之后 AT.FIRSTRUN-0（正式 first-run onboarding）。
