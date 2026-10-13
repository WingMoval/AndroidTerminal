# AT.V3-2.3 — ADB-only Composition & Auto-Connection / Recovery Parity Closure

**ADB 独立组合、自动连接与恢复链路闭环**

- 日期：2026-10-13
- 基线：V3 `83a0664`（AT.V3-2.2 完成后 clean；任务文本预期 `234df9d` + hash 记录 `83a0664`）
- 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）—— 稳定 Wi-Fi / hotspot
- 身份：已受信 adb identity（`run-as` 注入 ADB-only host `files/adbkey`，未提交）
- 结论：**STATUS = COMPLETE**（见 §26 Final Verdict）

---

## 1. Objective

在不恢复 V2 混合 `AtRuntime`、也不修改 AT public execution contract 的前提下，把已迁入 V3 的
ADB readiness、mDNS discovery、endpoint lifecycle、auto-connect 和基础 connection recovery
按新的 **ADB Engine / ADB Backend / AT Engine** 边界真正接完整；并通过一个**物理上完全不包含
Local Backend** 的 ADB-only Host 证明：

- **目标 A**：Host / Composition 可以只组合 `:at-engine` + `:at-backend-adb` + `:adb-engine`
  （不含 `:at-backend-local` / Local PTY native），`AT_ENGINE_ADB_ONLY_COMPOSITION = VERIFIED`。
- **目标 B**：在已存在 trusted adb identity 的前提下，`readiness → discovery → endpoint
  selection → connect → READY → AT execution`，以及连接损失后的 `detect → invalidate →
  rediscovery/reconnect → READY 或 bounded failure`，关闭 ADB post-trust connection lifecycle。

连接失败时允许 ADB Runtime 做**有界** connection reconnect attempts，但 **connection retry ≠
command replay**：远端执行状态未知的 operation 底层**不得透明重放**。连接最终不可用时，AT
execution 仍须提供明确 `TRANSPORT_ERROR` / error，保留 V2 已验证的“未来 ToolResult 把连接错误
交给 LLM 解释”的能力。成熟 reconnect UI / manual reconnect API / background recovery /
operation retry policy 全部 DEFERRED。

**本轮不做**：重写 ADB / V2 AtRuntime、开发 Emulator、开发 AndroidAgent、开发成熟 reconnect
UI、pairing（OUT_OF_SCOPE）。

## 2. Baseline

```text
V3 HEAD  = 83a0664（AT.V3-2.2: record audit commit hash in verdict）
V3 status = CLEAN（本轮开始前）
V2 HEAD  = 726a441
V2 status = 43 项会前 dirty（本轮 V2 零写入；结束仍 43）
设备状态 = 任务开始时未接入（用户稍后连手机再测）
```

## 3. V2 Oracle（只读引用）

| V2 来源 | 已验证事实（本轮 parity 基准） |
|---|---|
| AT.RUNTIME-2A | `AtRuntime` 门面；readiness 状态迁移；`connect/execute/disconnect/readinessState`；Core 0 改动 |
| AT.RUNTIME-2B | readiness（`adb_wifi_enabled` 1→ENABLED / 0→DISABLED / 异常→UNKNOWN）；mDNS `_adb-tls-connect._tcp.`；**多候选全试**（stale+fresh）；loopback-first；endpoint store TTL 60s；readiness≠discovery |
| AT.RUNTIME-2D | **operation-driven** loss detection；`LossClassifier`（仅 TRANSPORT_ERROR 触发，COMPLETED/非零退出**不**触发）；`ConnectionIntent`（None/Auto/Explicit）；bounded `RecoveryCoordinator`（maxAttempts/backoff）；AUTO 走 rediscover，EXPLICIT **只重连同端点、绝不 mDNS fallback**；**no command replay**（失败命令原样返回）；R1–R7 真机 PASS（30 checks 0 failed） |
| AT.ENGINE-AG0 | 独立 consumer 只经 stable contract；H1 cold-start（consumer 不知端口）/ H3 recovery（consumer 不直接调 recovery API）/ H4 WD OFF（`USER_ACTION_REQUIRED_WD_DISABLED`）；Golden Chain 不变量 |

V2 是**行为 Oracle**，不是模块搬迁模板：本轮按 V3 新边界重新落位，**不复制整个 V2
AtRuntime.kt**（§6 红线）。

## 4. V3 Pre-Audit（修改前真实源码审计，13 问）

审计对象：`:adb-engine` / `:at-backend-adb` / `:at-engine` / `:test-host`。关键发现（file:line）：

```text
1  readiness（AdbAndroidWirelessDebuggingReadinessBackend, AdbRuntimeBackends.kt:93）
   COMPILED，无 production caller（仅作为 lambda 类型被 dead AdbRecoveryCoordinator 引用）
2  discovery（AdbAndroidNsdDiscoveryBackend, AdbRuntimeBackends.kt:40）
   COMPILED，无 production caller
3  AdbEndpointStore（AdbRecovery.kt:60）
   COMPILED / DEAD CODE：真实 connect 路径（AdbEngine.connect→JNI ClientFor）从不读取它
4  AdbRecoveryCoordinator（AdbRecoveryCoordinator.kt:23）+ AdbRecoveryPolicy（AdbRecovery.kt:28）
   COMPILED，无 production caller（manifest 已标 “真机 recovery 未跑”）
5  AdbConnectionIntent（AdbRecovery.kt:12）
   死概念：无 producer；真实路径 backend 持有的是 AdbConnectionRequest（不同类型）
6  connectAuto
   不存在（全仓 0 命中）。真实入口只有 explicit AdbEngine.connect(host,port,keyDir)
7  loss detection
   WIRED（native）：RX owner read-fail → failAll（adb_stream_hub.cpp:70-73）；one-shot OPEN/read
   fail → TransportError（adb_client.cpp:134,152）。但从不更新 connection state
8  stale connectionState==ONLINE
   根因：rxLoop 真实失败分支调 failAll() 只 fail stream slots，从不改 AdbConnection::state_
   → 掉线后仍 ONLINE → backend ensureConnected() 误判已连、跳过重连
9  AdbOneShotBackend/AdbPersistentBackend（AdbBackend.kt:39/83）
   不持有 AdbEngine 实例——AdbEngine 是 Kotlin object 单例 + native 进程全局 g_client
10 共享 engine instance
   天然共享（Kotlin object + 单一 native g_client）；无显式注入
11 两个独立 AdbEngine 风险
   Kotlin 层无风险（object 唯一）；native g_client 按 keyDir 区分——keyDir 不一致会重建（潜在）
12 explicit-endpoint test bootstrap
   全部走 AdbConnectionRequest(host=127.0.0.1, port) + backend lazy ensureConnected()→connect；无 auto
13 compiled vs wired
   WIRED：仅 explicit connect + native loss detection。
   COMPILED-ONLY：readiness / discovery / endpoint store / recovery coordinator / connectAuto（不存在）
```

结论：**ADB lifecycle 几乎全是 compiled 未接线**；需要一个窄的 ADB-domain lifecycle facade
把死件接成真实链路，并修 stale-ONLINE。

## 5. Architecture Decisions

```text
D1  新增窄 ADB-domain lifecycle facade：com.adb.engine.AdbRuntime（object，process-scoped）。
    它 wire 现有死件（readiness backend / NSD discovery backend / AdbEndpointStore /
    AdbRecoveryCoordinator / AdbRecoveryPolicy），暴露：
      initialize(context)   平台 bootstrap（接 Android readiness+NSD backends，:app 无关）
      readiness()           AdbWirelessDebuggingState（detect only，绝不 auto-enable）
      connectAuto(keyDir)   readiness gate → rediscover → 多候选 connect（loopback-first）→ READY/有界失败
      connect(request)      explicit 端点（记 Explicit intent，无 mDNS）
      disconnect()          手动断开（清 intent + invalidate，非 loss，不 auto-recover）
      onOperationResult(transportLost)  operation-driven 有界恢复（单 owner，synchronized）
      state()/readinessState()/transportState()  生命周期 + 事实投影
      executeShell/openInteractive   ADB-domain passthrough（供 host 用；consumer 执行走 ATEngine）
    命名依据当前源码（AdbRecoveryCoordinator.kt:21 注释本就指向“ADB Runtime facade delegates”）。

D2  新增 AdbRuntimeState（生命周期投影）：DISCONNECTED/CONNECTING/READY/CONNECT_FAILED/
    ENDPOINT_NOT_FOUND/USER_ACTION_REQUIRED/TRANSPORT_LOST/RECOVERING/RECOVERY_FAILED。
    它是 ADB 生命周期态，≠ native transport state（AdbConnectionState），≠ AT outcome。
    对齐 V2 AtReadinessState 词表（pairing 态不发，本轮 post-trust）。

D3  AdbRuntimeDiagnostic（code/message/readiness/transport），对齐 V2 AtRuntimeDiagnostic。

D4  ownership 分离（Canonical V1 + V2 2D 重新落位）：
    ADB lifecycle ownership  = AdbRuntime + AdbEngine（ADB domain）
    AT execution ownership   = ATEngine -> AdbBackend -> AdbEngine（AT domain）
    consumer 执行只经 ATEngine；AdbRuntime 只建立/维护 connection。
    :adb-engine 仍零依赖 :at-engine（D5）。

D5  依赖方向不变：:at-backend-adb -> :at-engine + :adb-engine；:adb-engine 零 AT 依赖。
    AdbRuntime 只 import com.adb.engine.*（无 com.at.*）。

D6  stale-ONLINE 最小 native 修复：AdbConnection::markTransportLost()（仅 Online→Offline，幂等，
    不关 socket）；rxLoop 真实失败分支（非 stopping_）调用它。这是 ADB-domain liveness truth，
    不改 AdbStreamHub / TLS / CNXN / transport reader ownership（§49 边界内）。

D7  operation-driven recovery（V2 2D 语义）：backend 在 ADB 结果为 transport 失败（非非零退出）
    时调 AdbRuntime.onOperationResult(transportLost=true)；AdbRuntime 恢复 connection（有界），
    backend **原样返回**失败结果 → 无透明 command replay。

D8  新增 :adb-only-test-host（TEST/VALIDATION ONLY）：仅依赖 :at-engine + :at-backend-adb +
    :adb-engine（无 :at-backend-local / :app / Local PTY native）。两条路径分离：
      (A) Host/Composition lifecycle → AdbRuntime
      (B) 业务执行 → ATEngine(AdbOneShotBackend, AdbPersistentBackend)

D9  ADB-only host 的 backend 用 placeholder AdbConnectionRequest(127.0.0.1, 0, keyDir)：
    auto 路径由 AdbRuntime.connectAuto 建立 ONLINE 后，backend ensureConnected() 见 ONLINE
    直接复用（不 per-command discovery/reconnect）；placeholder 端点仅 explicit 路径有意义。

D10 不引入 ATEngine.connectAuto/discoverAdb/reconnectAdb/adbState（§13 红线）——AT public
    contract 本轮 0 改动。
```

## 6. 红线核查（未违反）

```text
V2 AtRuntime 整段复制            = NO（按 V3 边界新建 AdbRuntime，行为 parity）
:adb-engine -> :at-engine        = NO（零 import com.at.*；build.gradle 无 project 依赖）
ATEngine.connectAuto/discover/reconnect/adbState = NO（未加任何 ADB-specific AT API）
ATEngine 知道 AdbEndpoint/mDNS/dynamic port = NO
AdbStreamHub / TLS / CNXN / transport reader ownership 重写 = NO（仅加 markTransportLost 状态更新）
AT public Contract 改动          = NO
Local Backend 改动               = NO
```

## 7. ADB-only Composition（目标 A）

`:adb-only-test-host` 消费者组合：

```text
Host / Composition (AdbOnlyHost.kt)
  ├─ bootstrap(context) → AdbRuntime.initialize(app)      [lifecycle path A]
  └─ engine() → ATEngine(AdbOneShotBackend, AdbPersistentBackend)  [execution path B]

执行 consumer（harness）
  → AdbRuntime.connectAuto(keyDir)   （不知端口；mDNS 发现）
  → ATEngine.execute / openSession   （只知“ADB provider”）
```

consumer 不 import：AdbClient / AdbConnection / TLS / CNXN / stream id / WRTE / OKAY /
mDNS endpoint / dynamic port（harness 仅 import AdbRuntime 生命周期 + ATEngine + 结果类型）。

## 8. Artifact Isolation（G1/G2，已执行）

```text
LOCAL_BACKEND_GRADLE_DEPENDENCY = ABSENT
  :adb-only-test-host:dependencies debugRuntimeClasspath
    = :at-engine + :at-backend-adb + :adb-engine（无 :at-backend-local / :app）
LOCAL_BACKEND_DEX = ABSENT
  APK classes*.dex（6 个）ASCII 扫描：at/backend/local、LocalOneShotBackend、
  LocalPersistentBackend、LocalTerminalSession、LocalPtyNative、liblocal_pty = 0 命中
LOCAL_PTY_NATIVE_LIB = ABSENT
  APK lib/ = 仅 libadbd_engine.so（无 liblocal_pty.so）
AT_ENGINE / ADB_BACKEND / ADB_ENGINE / AdbRuntime = PRESENT（对照，非空 artifact）

ADB_ONLY_COMPOSITION = PASS
ADB_ONLY_ARTIFACT_AUDIT = PASS
LOCAL_BACKEND_REQUIRED = NO
```

## 9. Lifecycle Ownership

```text
Host / Composition（AdbRuntime）
  initialize / readiness / discovery / connectAuto / explicit connect
  state / diagnostic / recovery / disconnect
  ≠ AT execution ownership（ATEngine.execute/openSession）

关键不变量：
  - 单 recovery owner（AdbRuntime.lock 串行 connect/disconnect/recover）
  - 无并行 RecoveryCoordinator / mDNS scan 风暴 / connection 风暴
  - consumer 不拥有 ADB connection lifecycle
```

## 10. Runtime Wiring（接线前后）

```text
readiness   COMPILED → WIRED（AdbRuntime.readiness / connectAuto gate / recovery gate）
discovery   COMPILED → WIRED（AdbRuntime.connectAuto 经 AdbEndpointStore.rediscover 调 NSD scan）
endpoint    COMPILED/DEAD → WIRED（connectAuto/recovery 经 AdbEndpointStore set/invalidate/rediscover）
recovery    COMPILED/DEAD → WIRED（AdbRuntime.onOperationResult → AdbRecoveryCoordinator.recover）
connectAuto 不存在 → 新增（AdbRuntime.connectAuto）
loss detect  WIRED(native) + 现更新 liveness（markTransportLost）
```

## 11. Readiness

```text
readiness = detect + report（AdbAndroidWirelessDebuggingReadinessBackend，Settings.Global
  adb_wifi_enabled：1→ENABLED / 0→DISABLED / 异常→UNKNOWN）
绝不 auto-enable（无 putInt / shell / su / Shizuku / root / privileged）。
readiness ≠ discovery（mDNS 空 ≠ WD OFF；二者严格分开，见 §12）。
```

## 12. Discovery

```text
mDNS `_adb-tls-connect._tcp.`（AdbAndroidNsdDiscoveryBackend，仅 connect service；pairing 不做）
返回【全部】候选（去重，loopback-resolved 优先）——保留 V2 2B 多候选全试策略。
dynamic port 只写 evidence，绝不 hardcode 到 production；身份私钥不落 evidence。

【迁移 bug 发现（真机验证暴露）】V3 首次移植的 scan() 直接读 onServiceFound 的
host/port（Android 给的是未 resolve service：host=null/port=0）→ filter port!=0 全滤掉 →
永远 ENDPOINT_NOT_FOUND。按 V2 AT.RUNTIME-2B Oracle 修正：onServiceFound →
resolveService() 取真实 host/port + 收集全候选 + loopback-first 排序。
（COMPILED 未真机验证的典型迁移 bug；G3 真机暴露。）
```

## 13. Endpoint Lifecycle

```text
AdbEndpointStore（process-local，TTL 60s）：current/set/setExplicit/rediscover(invalidate→scan)/invalidate/clear
cache scope = process-scoped（不持久化 disk）；restart 后重新 discovery。
connectAuto 成功 → set(MDNS_LOOPBACK/MDNS_RESOLVED)；loss → invalidate（recovery 前强制）
```

## 14. Cold-start Auto Connection（G3/G4/G5/G6/G7，真机）

```text
Host/Composition → AdbRuntime.connectAuto(keyDir) → readiness → mDNS discovery →
  endpoint selection（loopback-first）→ connect → READY
consumer → ATEngine.execute("id") → uid=2000(shell)
CONSUMER_KNEW_PORT = NO；EXPLICIT_ENDPOINT_INJECTED = NO；AUTO_DISCOVERY_USED = YES
```

## 15. Connection Reuse（G10）

```text
connectAuto 后 A/B/C 多次 execute → 同一 physical connection / 同一 TLS；
无 per-command discovery / reconnect（ensureConnected 见 ONLINE 直接复用）。
```

## 16. Loss Classification（§31）

```text
触发恢复：operation 返回 transport 失败（TCP/TLS read-fail / OPEN on dead conn）
不触发：normal exit / non-zero exit / remote stderr / explicit disconnect / WD disabled（=user-action）
实现：backend 仅当 AdbShellResult.status != COMPLETED（或 openInteractive 失败且非 ONLINE）
  时调 onOperationResult(transportLost=true)；非零退出 status==COMPLETED 不触发。
绝不“任何 AT 非 COMPLETED → reconnect”；绝不引入 ATExecutionResult 进入 :adb-engine。
```

## 17. Connection Recovery（§25/§26）

```text
有界：AdbRecoveryPolicy.production = maxAttempts 3 / backoff 1s,3s / discoveryTimeout 10s
单 owner：AdbRuntime.lock 串行；无并行 coordinator / scan 风暴 / connection 风暴
AUTO → rediscover + 多候选；EXPLICIT → 只重连同端点（无 mDNS fallback）
WD DISABLED → USER_ACTION_REQUIRED（立即停，不 scan/不重连）
耗尽 → RECOVERY_FAILED（bounded，可观察，不 hang / 不无限重连）
```

## 18. Retry vs Replay Semantics（§23/§24）

```text
CONNECTION_RECOVERY_RETRY != COMMAND_REPLAY
允许：连接恢复多次 attempt（有界）
TRANSPARENT_UNKNOWN_OUTCOME_REPLAY = 0（远端执行状态未知的 operation 底层不透明重放）
OPERATION_RETRY_POLICY = DEFERRED（本轮不冻结“永远禁 retry”，也不实现“自动重放”）
失败 operation 原样返回（FAILED_OPERATION_RESULT_PRESERVED = PASS）
```

## 19. Error Observability（§28/§30/§54）

```text
连接不可用时：ATEngine.execute → ATExecutionResult.outcome=TRANSPORT_ERROR + hasError + 有意义 error
（不 silent false / 不 generic null exception）→ 可直接映射未来 ToolResult.success=false/error
Host 状态（AdbRuntime.state/diagnostic）与 Tool 错误（ATExecutionResult）是两条独立通道，
都不污染 remote PTY byte stream。
AG_ERROR_OBSERVABILITY = PASS（§G18 真机）
```

## 20. Persistent Loss（§42）

```text
session 所属 transport 丢失：旧 session 按 Contract 结束（EXITED+Eof；GAP-1 本轮不改）
不神奇恢复原 session；recovery 成功后允许开新 session B
OLD_SESSION_RESURRECTION = NO；NEW_SESSION_AFTER_RECOVERY = PASS
```

## 21. Multi-session Loss（§43）

```text
A/B/C 共 transport 丢失 → 旧 A/B/C 均失效 transport；不自动重建
recovery 后开新 D 正常工作（不做 attach/detach/session resurrection）
```

## 22. Stale Connection State（§32）

```text
BEFORE：掉线后 AdbEngine.connectionState()==ONLINE（stale；rxLoop failAll 不改 state_）
修复：AdbConnection::markTransportLost()（Online→Offline，仅真实失败分支触发，幂等，不关 socket）
AFTER：真实 transport loss 后 connectionState()==OFFLINE → ensure-ready 正确跳过 / recovery 触发
STALE_CONNECTION_STATE_STATUS = CLOSED
（G12 dynamic recovery 真机确认恢复后新 connection 回到 ONLINE 并执行成功）
```

## 23. V2 -> V3 Parity Matrix

| V2 behavior | V3 ownership | V3 implementation | V3 evidence | status | 分类 |
|---|---|---|---|---|---|
| 2B readiness | AdbRuntime（ADB domain） | AdbAndroidWirelessDebuggingReadinessBackend + AdbRuntime.readiness/connectAuto gate | G4 真机 | PASS | PARITY |
| 2B discovery（多候选） | AdbRuntime + AdbEndpointStore | AdbAndroidNsdDiscoveryBackend + rediscover 多候选 loopback-first | G5/G6 真机 | PASS | PARITY |
| 2B endpoint lifecycle | AdbEndpointStore | set/invalidate/rediscover + TTL | G6 真机 | PASS | PARITY |
| 2D R1 正常路径 | AdbRuntime + backend | connectAuto→20 命令，0 recovery | G10 真机 | PASS | PARITY |
| 2D R2 动态端口恢复 | AdbRuntime recovery | markTransportLost→detect→invalidate→rediscover→reconnect | G12/G13/G14 真机 | PASS | PARITY |
| 2D R3 无透明重放 | backend + AdbRuntime | onOperationResult 恢复 connection，原样返回失败 | G13 guard 真机 | PASS | PARITY |
| 2D R4 WD OFF | AdbRuntime gate | readiness DISABLED→USER_ACTION_REQUIRED，bounded | G16 真机 | PASS | PARITY |
| 2D R5 no endpoint | AdbRuntime | 空 discovery→ENDPOINT_NOT_FOUND/RECOVERY_NO_ENDPOINT，bounded | 确定性测试 | PASS | PARITY |
| 2D R6 bounded attempts | AdbRecoveryCoordinator | maxAttempts/backoff 有界，RECOVERY_FAILED | 确定性测试 | PASS | PARITY |
| 2D R7 explicit 语义 | AdbRecoveryCoordinator | EXPLICIT 只重连同端点，无 mDNS fallback | 确定性测试（scanCount=0） | PASS | PARITY |
| AG0 H1 cold-start | AdbRuntime + consumer | consumer 不知端口 connectAuto→id | G3 真机 | PASS | PARITY |
| AG0 H3 recovery via stable API | AdbRuntime（consumer 不直接调 recovery） | onOperationResult 自动恢复 | G14 真机 | PASS | PARITY |
| AG0 H4 WD OFF | AdbRuntime | USER_ACTION_REQUIRED_WD_DISABLED | G16 真机 | PASS | PARITY |
| AGA disconnected ToolResult | ATExecutionResult（TRANSPORT_ERROR + error） | G18 真机 | PASS | PARITY |
| V2 混合 AtRuntime 单模块 | 拆分（AdbRuntime=ADB domain / ATEngine=AT domain） | D1/D4 | 架构 | INTENTIONAL_V3_ARCHITECTURE_DIFFERENCE | 分类 |

## 24. Real-device Evidence（G3–G18）

> 真机执行于稳定 Wi-Fi / hotspot（Wireless Debugging ON + trusted identity）。
> 详细 logcat / 端点 / 字节数见 `docs/evidence/at_v3_2_3/EVIDENCE.md`。

```text
G1  ADB-only physical composition        PASS（Gradle/dex/native 隔离）
G2  ADB-only artifact audit              PASS（Local 全 ABSENT；AT/ADB Engine PRESENT）
G3  cold-start connectAuto（无 explicit） PASS（READY；consumer 不知端口；uid=2000）
G4  real readiness（ON）                 PASS
G5  real mDNS discovery                  PASS（修复 resolveService 后；发现 _adb-tls-connect._tcp.；dynamic port 仅记 evidence）
G6  endpoint selection/lifecycle         PASS（loopback-first；set/invalidate）
G7  one-shot after auto-connect          PASS（uid=2000）
G8  persistent session after auto-connect PASS（ACTIVE/tty/write/read/Ctrl-C/resize/close）
G9  multi-session after auto-connect     PASS（1 conn / 1 TLS / 多 logical stream / isolation）
G10 connection reuse                     PASS（多次 execute 同连接）
G11 process restart cold-start           PASS（force-stop 后 fresh 进程无旧 endpoint，connectAuto→READY）
G12 dynamic endpoint recovery            PASS（OLD 42713→NEW 40437；OLD invalidated；fresh rediscovery）
G13 unknown-outcome 无透明重放           PASS（guard TOKEN=0；TRANSPARENT_UNKNOWN_OUTCOME_REPLAY=0）
G14 post-recovery new command            PASS（B_OK COMPLETED）
G15 post-recovery new persistent session PASS（PERSIST_RECOVERED 会话正常）
G16 WD OFF behavior                      PASS（USER_ACTION_REQUIRED；bounded，无风暴）
G17 explicit connection intent semantics PASS（connectAuto 后 intent=Auto；EXPLICIT no-mDNS-fallback 确定性测试覆盖）
G18 AT error propagation                 PASS（TRANSPORT_ERROR + hasError + 有意义 error）
```

## 25. Regression

```text
（真机执行，本轮改动后全绿）

Local (at.backend.local.test)                11/11   PASS
AT Engine unit (ATExecutionContractTest 3 + ATSessionContractTest 3)  6/6  PASS
App Debug (com.androidterminal.app.test)     16/16   PASS（7 debug input + 9 dynamic pages）
Headless (TestHostHeadlessInputTest)          1/1    PASS
ADB Backend (AdbBackendHeadlessTest)         25/25   PASS（显式端口注入）
Backend Parity (BackendParityAuditTest)      34/34   PASS（781.5s；覆盖 flow-control/大输出/persistent/multi/cross-mode/failure-path）
ADB-only deterministic (AdbRuntimeLifecycleTest) 12/12 PASS

ADB flow-control（64K one-shot）不退化 + persistent/one-shot cross-mode 不退化：由 Parity 34/34 覆盖。
RX_OWNER_COUNT=1 / TX_SERIALIZATION=PASS / STREAM_LEAK=NO / STALE_READER=NO / CROSS_TALK=NO：不退化。
```

## 26. Final Verdict

```text
AT.V3-2.3 FINAL VERDICT

STATUS = COMPLETE

BASELINE = V3 83a0664（clean）/ V2 726a441（43 会前 dirty，零写入）

ADB_ONLY_COMPOSITION = PASS
ADB_ONLY_HOST = :adb-only-test-host（TEST/VALIDATION ONLY）
ADB_ONLY_GRADLE_LOCAL_DEP = ABSENT
ADB_ONLY_DEX_LOCAL_BACKEND = ABSENT
ADB_ONLY_LOCAL_PTY_NATIVE = ABSENT
LOCAL_BACKEND_REQUIRED = NO

CONSUMER_EXECUTION_PATH = ATEngine -> ADB Backend -> ADB Engine
CONSUMER_DIRECT_ADB_ENGINE_EXECUTION = NO

HOST_LIFECYCLE_PATH = AdbOnlyHost.bootstrap -> AdbRuntime.initialize/connectAuto/state
AT_ENGINE_MODIFIED = NO
AT_ENGINE_PUBLIC_CONTRACT_CHANGED = NO
ADB_BACKEND_MODIFIED = YES（最小：onOperationResult loss-signal，无 AT contract 改动）
ADB_ENGINE_RUNTIME_MODIFIED = YES（新增 AdbRuntime/AdbRuntimeState/AdbRuntimeDiagnostic；
  native 仅加 AdbConnection::markTransportLost + rxLoop 调用；
  discovery backend 按 V2 Oracle 修正 resolveService——迁移 bug 修复）
ADB_ENGINE_NATIVE_MODIFIED = YES（最小：markTransportLost 状态更新；无 TLS/CNXN/StreamHub 重写）
LOCAL_BACKEND_MODIFIED = NO

V2_ATRUNTIME_COPIED_WHOLESALE = NO

READINESS_PARITY = PASS
DISCOVERY_PARITY = PASS
ENDPOINT_LIFECYCLE_PARITY = PASS

COLD_START_AUTO_CONNECT = PASS
CONSUMER_KNEW_ENDPOINT = NO
EXPLICIT_ENDPOINT_INJECTED = NO
MDNS_DISCOVERY_USED = YES
DISCOVERED_ENDPOINT_SOURCE = mDNS _adb-tls-connect._tcp.（loopback-first）
DISCOVERY_TIME_MS = （见 evidence）

AUTO_CONNECTED_PRINCIPAL = uid=2000(shell)
AUTO_CONNECTED_ONE_SHOT = PASS
AUTO_CONNECTED_PERSISTENT = PASS
AUTO_CONNECTED_MULTI_SESSION = PASS

CONNECTION_REUSE = PASS
PHYSICAL_CONNECTION_COUNT = 1
TLS_HANDSHAKE_COUNT = 1
RX_OWNER_COUNT = 1

PROCESS_RESTART_AUTO_CONNECT = PASS
TRANSPORT_LOSS_DETECTION = PASS

STALE_CONNECTION_STATE_BEFORE = ONLINE（掉线后 stale）
STALE_CONNECTION_STATE_AFTER = OFFLINE（markTransportLost）
STALE_CONNECTION_STATE_STATUS = CLOSED

DYNAMIC_ENDPOINT_RECOVERY = PASS
OLD_ENDPOINT = 42713（G13/G14 编排；无线 TLS listener）
NEW_ENDPOINT = 40437（rediscover 发现）
OLD_ENDPOINT_INVALIDATED = YES
FRESH_REDISCOVERY = YES

CONNECTION_RECOVERY = PASS
RECOVERY_ATTEMPTS_BOUNDED = PASS
RECOVERY_OWNER_COUNT = 1
RECOVERY_STORM = NO

TRANSPARENT_UNKNOWN_OUTCOME_REPLAY = 0
OPERATION_RETRY_POLICY = DEFERRED
FAILED_OPERATION_RESULT_PRESERVED = PASS
POST_RECOVERY_COMMAND = PASS

PERSISTENT_SESSION_ON_LOSS = EXITED+Eof（GAP-1 未改）
OLD_SESSION_RESURRECTION = NO
NEW_SESSION_AFTER_RECOVERY = PASS

WD_OFF_BEHAVIOR = PASS
NO_ENDPOINT_BEHAVIOR = PASS
EXPLICIT_INTENT_NO_MDNS_FALLBACK = PASS

AT_TRANSPORT_ERROR_OBSERVABILITY = PASS
AG_ERROR_OBSERVABILITY = PASS

ADB_RUNTIME_READINESS = VERIFIED
ADB_RUNTIME_DISCOVERY = VERIFIED
ADB_RUNTIME_ENDPOINT_LIFECYCLE = VERIFIED
ADB_RUNTIME_AUTO_CONNECTION = VERIFIED
ADB_RUNTIME_BASIC_RECOVERY = VERIFIED
ADB_RUNTIME_ERROR_OBSERVABILITY = VERIFIED

ADB_MATURE_RECONNECT_POLICY = DEFERRED
ADB_RECONNECT_UI = DEFERRED
ADB_MANUAL_RECONNECT_API = DEFERRED
ADB_BACKGROUND_RECOVERY = DEFERRED

PAIRING = OUT_OF_SCOPE

V2_R1_PARITY = PASS
V2_R2_PARITY = PASS
V2_R3_PARITY = PASS
V2_R4_PARITY = PASS
V2_R5_PARITY = PASS
V2_R6_PARITY = PASS
V2_R7_PARITY = PASS
V2_AG0_H1_PARITY = PASS
V2_AG0_H3_PARITY = PASS
V2_AG0_H4_PARITY = PASS

ADB_FLOW_CONTROL_REGRESSION = PASS
ADB_MULTI_STREAM_REGRESSION = PASS
CROSS_MODE_REGRESSION = PASS

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_DEBUG_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
DYNAMIC_SESSION_REGRESSION = 9/9
ADB_BACKEND_REGRESSION = 25/25
BACKEND_PARITY_REGRESSION = 34/34
ADB_RUNTIME_NEW_TESTS = 12/12（AdbRuntimeLifecycleTest 确定性）
ADB_ONLY_HOST_TESTS = G3/G10/G8G9/G17/G18/G16/G11/G13G14/G15（真机 G-gates）

GAP_1_EOF_FAILED = DEFERRED
GAP_2_PERSISTENT_EXITCODE = DEFERRED
GAP_3_READ_LISTENER = DEFERRED
LOCAL_INITIAL_SIZE_BUG = DEFERRED

AT_EXECUTION_CLOSURE = PASS

ADB_RUNTIME_CLOSURE = PASS_POST_TRUST_CONNECTION_LIFECYCLE

AT_ENGINE_ADB_ONLY_COMPOSITION = VERIFIED

WIRELESS_DEBUGGING_TEST_ENVIRONMENT = STABLE_WIFI
REAL_DEVICE_VALIDATION = PASS

EVIDENCE = docs/evidence/at_v3_2_3/

GIT_COMMIT = （commit 后回填）
GIT_WORKTREE = CLEAN
V2_WRITES = 0

NEXT = AT.EMULATOR-0
```

## 27. Deferred（本轮明确不做）

```text
ADB_MATURE_RECONNECT_POLICY / ADB_RECONNECT_UI / ADB_MANUAL_RECONNECT_API = DEFERRED
ADB_BACKGROUND_RECOVERY = DEFERRED
OPERATION_RETRY_POLICY = DEFERRED（connection retry 与 command replay 分开；本轮不设计完整 policy）
Pairing / identity enrollment UI / mDNS pairing service = OUT_OF_SCOPE
Terminal Emulator / ScreenState / ANSI = NOT_STARTED（NEXT = AT.EMULATOR-0）
AT.CONTRACT-1 = NOT_STARTED
GAP-1 / GAP-2 / GAP-3 = DEFERRED
Local initialRows/Cols = DEFERRED（AT.EMULATOR-0）
```
