# AT.CONSUMER-CAPABILITY-AUDIT-0 — Current AT V3 Consumer Capability Truth + Embedding Contract

> 性质：ARCHITECTURE AUDIT / CONSUMER CAPABILITY RECONCILIATION / EMBEDDING CONTRACT AUDIT。
> DOCS / EVIDENCE ONLY。PRODUCTION_CODE_CHANGE = NO / TEST_CODE_CHANGE = NO / AT_API_CHANGE = NO /
> DEVICE_TEST = NO / NEW_FEATURE = NO / NEW_TAG = NO / PUSH = NO。
> 不修改 AndroidAgent（DO NOT MODIFY / DESIGN / TEST）。
> 审计基线：`c9cee21`（AUTHORITATIVE_SOURCE_BASELINE，production tree 已验证等价当前 tree）。
>
> **状态**：`AT.CONSUMER-CAPABILITY-AUDIT-0 = CLOSED`（SUBSTANTIVE RESULT PRESERVED）。
> **规范化**：`AT.CONSUMER-CAPABILITY-AUDIT-0.1 = PASS`（docs-only，3 项口径修正 N1/N2/N3，
> 见 §5.1 / §17 / §18 / §16 / §20 的内联标注）。`AG_RECONCILIATION_INPUT = AUTHORITATIVE / READY`。

---

## 1. Executive Verdict

**VERDICT = PASS**

如果今天有一个全新第三方 Android APK 想「完整消费当前 AT V3 已冻结的 Consumer capability」，
标准答案（全部从 `c9cee21` 真实源码推出）：

- **AT 提供的是 capability，不是单一 artifact**。一个 consumer 可以按需组合
  Local one-shot / Local persistent PTY / ADB one-shot / ADB Remote PTY / shell-visible `adb`
  CLI / `AdbConsumerBootstrap` 配对。这些是**同一 AT Engine contract 下的 peer execution mode**，
  不是互相排斥的选项，也不是「要么全要么无」。
- **one-shot 与 persistent 是同一 `ATEngine` facade 的两个 peer mode**（`execute()` vs
  `openSession()`），各自注入不同 backend。Persistent capability 已存在 ≠ Consumer 必须现在
  建 persistent Agent Tool（那是 AG 产品策略，非 AT 完整性定义）。
- **`AdbRuntime` 是进程级 Kotlin `object` 单例**（UNIQUE ADB lifecycle owner），每个 consumer
  **进程**恰好 1 个。`AdbConsumerBootstrap` 是**纯 facade/projection**（不持 lifecycle、不创建
  AdbRuntime），可有多个轻量 facade 实例，但都共享唯一 AdbRuntime。
- **一个 consumer 合法拥有 2 个 ATEngine object**（`localEngine()` + `adbEngine()`），共享同一
  composition root + 同一 AdbRuntime 单例。**「one composition」≠「one ATEngine instance」**。
- **direct ADB backend 是 VALID CURRENT CAPABILITY**（未 deprecate）；shell-visible `adb` 是
  **同一 ADB capability 上的额外 terminal frontend**，**不替代** direct ADB backend。
- **`:at-cli` 嵌入是 host obligation 密集**：host 必须实现 `ATCliExecution`、启动
  `AdbCliBridgeServer`、提供 identity dir、provision shell `adb()` 函数（`LocalCliBootstrap`）、
  注入 APK/socket/session env、启动 app_process。「把 :at-cli AAR 加进去」≠ 完成 CLI 集成。
- **「能力未暴露给模型」≠「能力未集成」**。MODEL_VISIBLE 是 consumer 产品层决策，**非 AT
  responsibility**。本轮定义前 5 层 AT integration state（CAPABILITY_AVAILABLE_IN_AT →
  PHYSICALLY_CONSUMED_BY_HOST），PRODUCT_EXPOSED / MODEL_VISIBLE 明确划给 consumer 产品层。

**本轮不解决 AndroidAgent**。本轮只把「AT 是什么 / 有什么 / 怎么被完整嵌入」一次性审清楚。
AG 差集（AG 当前实际消费了哪些、缺哪些）留到下一轮 AG.AT-CONSUMER-RECONCILIATION-0。

---

## 2. Source / Freeze Identity

```
START_HEAD                 = a6f23d7112ca40c873b63cba4aef5686e0dd4405
CURRENT_TREE               = 789b20d3c79b9109f07a3e5f59cfc500b4bd403b
WORKTREE                   = CLEAN
AUTHORITATIVE_SOURCE_BASELINE = c9cee2126b3ebc54a62be27d1f7bdea8ded22a7f
SOURCE_BASELINE_MEANING    = AT.CONSUMER-BOOTSTRAP-0 CLOSED + AT.CLI-CONSUMER-0 CLOSED
FREEZE_PUBLICATION         = a6f23d7112ca40c873b63cba4aef5686e0dd4405
FREEZE_TAG                 = AT.AG-CONSUMER-FREEZE-V1  (-> a6f23d7)
PRE_EMULATOR_FREEZE_TAG    = AT.PRE-EMULATOR-FREEZE-V1 (-> 7ccb1bf, 未动)
SOURCE_TREE_MATCHES_BASELINE = YES
  （git diff --name-status c9cee21..a6f23d7 只有 docs/freeze/handoff/evidence/本轮交付 变化，
    0 production source 差异 → 当前 tree 的 production source == c9cee21）
PRODUCTION_CODE_CHANGED    = NO
TEST_CODE_CHANGED          = NO
DEVICE_TEST                = NO
```

审计基于 `c9cee21` 真实 production source（当前 tree 已证明与 c9cee21 production 等价，
直接读当前 tree）。关键源文件：

- `:at-engine` — `ATEngine.kt` / `ATSession.kt` / `ATBackend.kt` / `ATExecution.kt`
- `:at-backend-local` — `LocalOneShotBackend.kt` / `LocalPersistentBackend.kt` / `LocalPtySession.kt` / `LocalPtyNative.kt` + cpp `local_pty*.cpp`
- `:at-backend-adb` — `AdbBackend.kt`（AdbOneShotBackend / AdbPersistentBackend / AdbTerminalSession）
- `:adb-engine` — `AdbRuntime.kt` / `AdbRuntimeState.kt` / `AdbConsumerBootstrap.kt` / `AdbEngine.kt` / `AdbRecovery*.kt`
- `:at-cli` — `AtCliMain.kt` / `AdbCliBridgeServer.kt` / `ATCliExecution.kt` / `LocalSessionBootstrap.kt` / `AdbCliProtocol.kt` / `AdbCliArgs.kt` + cpp `cli_bridge_server.cpp` / `native_env.cpp`
- `:at-cli-consumer-host` — `AtCliConsumerHost.kt`（reference composition root）
- `:app` — `AppRuntime.kt` / `AppCliExecution.kt`（product composition reference）

---

## 3. Complete Consumer Capability Matrix

字段：CAPABILITY / SEMANTIC_OWNER / MODULE / CONSUMER_SURFACE / CONSTRUCTION_PATH /
HOST_RESPONSIBILITY / RUNTIME_PREREQUISITE / ADB_READY_REQUIRED / STATEFUL / PERSISTENT / PTY /
NATIVE_DEPENDENCY / EXISTING_EVIDENCE / CURRENT_STATUS / CONSUMER_OPTIONALITY。

| CAPABILITY | SEMANTIC_OWNER | MODULE | CONSUMER_SURFACE | CONSTRUCTION_PATH | HOST_RESPONSIBILITY | PREREQ | ADB_READY | STATEFUL | PERSISTENT | PTY | NATIVE | EVIDENCE | STATUS | OPTIONALITY |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Local one-shot | AT Engine | :at-backend-local | `ATEngine.execute(ATExecutionRequest)` | `ATEngine(oneShot=LocalOneShotBackend(), ...)` | 注入 backend | none | NO | NO | NO | NO | NO | PRE-EMULATOR + ATsh.T0 | VERIFIED | OPTIONAL |
| Local persistent | AT Engine | :at-backend-local | `ATEngine.openSession(Launch)` → `ATTerminalSession` | `ATEngine(persistent=LocalPersistentBackend(), ...)` | 注入 backend | none | NO | YES | YES | YES(real PTY) | libat_local_backend.so | PRE-EMULATOR + ATsh.T1 + AT.TERM-0 | VERIFIED | OPTIONAL |
| Local raw byte I/O | AT Engine | :at-backend-local | `session.write(bytes)` / `session.read()` / `setListener` | 同上 | — | none | NO | YES | YES | YES | libat_local_backend.so | 同上 | VERIFIED | OPTIONAL |
| Local resize | AT Engine | :at-backend-local | `session.resize(rows,cols)` | 同上 | — | none | NO | YES | YES | YES | libat_local_backend.so (TIOCSWINSZ) | 同上 | VERIFIED | OPTIONAL |
| Local Ctrl-C/signal | AT Engine + Local PTY | :at-backend-local | write 0x03 byte → PTY 信号 | 同上 | — | none | NO | YES | YES | YES | libat_local_backend.so | 同上 | VERIFIED | OPTIONAL |
| ADB one-shot | ADB Backend → ADB Engine | :at-backend-adb | `ATEngine.execute(ATExecutionRequest)` (shell,v2,raw) | `ATEngine(oneShot=AdbOneShotBackend(conn), ...)` | 建 conn(placeholder) | ADB READY | YES | NO | NO | NO | libadbd_engine.so | PRE-EMULATOR + AT.V3-2 | VERIFIED | OPTIONAL |
| ADB persistent | ADB Backend → ADB Engine | :at-backend-adb | `ATEngine.openSession(Launch)` → `ATTerminalSession` (shell,v2,pty:) | `ATEngine(persistent=AdbPersistentBackend(conn), ...)` | 建 conn(placeholder) | ADB READY | YES | YES | YES | YES(remote PTY) | libadbd_engine.so | PRE-EMULATOR + ADB.I0 | VERIFIED | OPTIONAL |
| ADB Remote PTY stdin/Ctrl-C/resize | ADB Backend → ADB Engine | :at-backend-adb | `session.write/resize` | 同上 | — | ADB READY | YES | YES | YES | YES | libadbd_engine.so | AT.CLI-0.2 (I1-I16) | VERIFIED | OPTIONAL |
| ADB multi-session | AT Engine + ADB Engine | :at-backend-adb | 多个 `openSession`（共享 1 连接） | 同上 | — | ADB READY | YES | YES | YES | YES | libadbd_engine.so | PRE-EMULATOR (multi) | VERIFIED | OPTIONAL |
| natural exit | AT Engine | :at-backend-* | `ATSessionEvent.Exited/Eof` | 同上 | — | — | — | YES | YES | — | — | 同上 | VERIFIED | — |
| explicit close | AT Engine | :at-backend-* | `session.close(terminate)` | 同上 | — | — | — | YES | YES | — | — | 同上 | VERIFIED | — |
| connection reuse | ADB Runtime + ADB Engine | :adb-engine | AdbRuntime 持 1 连接，backend 复用 | `AdbRuntime.connectAuto` 后 backend short-circuit ONLINE | 调 connectAuto | ADB READY | YES | YES | — | — | libadbd_engine.so | AT.V3-2.3 | VERIFIED | — |
| transport-loss semantics | ADB Runtime | :adb-engine | `AdbRuntimeState.TRANSPORT_LOST/RECOVERING/RECOVERY_FAILED` | `AdbRuntime.onOperationResult(transportLost)` | 不主动调（backend 触发） | ADB READY | YES | YES | — | — | libadbd_engine.so | AT.V3-2.3 | VERIFIED | — |
| shell-visible adb | :at-cli + host | :at-cli | Local shell 内 `adb` 函数 | `LocalCliBootstrap.writeScript` + host 启动 bridge | provision + 启动 bridge | bridge up | NO(one-shot 需 ADB READY) | — | — | — | libatcli_env.so | AT.CLI-CONSUMER-0 (C1-C10) | VERIFIED | OPTIONAL |
| adb shell one-shot | :at-cli | :at-cli | `adb shell <cmd...>` → T_ONESHOT | app_process AtCliMain → bridge → host adbEngine().execute | — | ADB READY | YES | NO | NO | NO | libatcli_env.so | AT.CLI-CONSUMER-0 C3 | VERIFIED | OPTIONAL |
| adb shell interactive | :at-cli | :at-cli | `adb shell` (no-arg) → T_INTERACTIVE → remote PTY relay | app_process AtCliMain → bridge → host adbEngine().openSession | — | ADB READY | YES | YES | YES | YES | libatcli_env.so | AT.CLI-CONSUMER-0 C4 + AT.CLI-0.2 | VERIFIED | OPTIONAL |
| adb pair | AdbConsumerBootstrap | :adb-engine | `adb pair <host>:<port> <code>` → T_PAIR → bootstrap.pair | app_process AtCliMain → bridge onPair → execution.consumerBootstrap().pair | 提供 consumerBootstrap | 配对服务可达 | NO(配对前) | NO | NO | NO | libadbd_engine.so | AT.CONSUMER-BOOTSTRAP-0 P1-P3 | VERIFIED | OPTIONAL |
| bootstrapState | AdbConsumerBootstrap | :adb-engine | `bootstrap.bootstrapState()` → AdbBootstrapState | `AdbConsumerBootstrap(identityDir).bootstrapState()` | 提供 identityDir | — | — | NO | NO | NO | — | AT.CONSUMER-BOOTSTRAP-0 B1 | VERIFIED | OPTIONAL |
| pairing discovery | AdbRuntime（NSD） | :adb-engine | `bootstrap.discoverPairing()` → AdbPairingDiscovery | 同上 | 提供 identityDir | 配对服务可达 | NO | NO | NO | NO | — | AT.CONSUMER-BOOTSTRAP-0 B2 | VERIFIED | OPTIONAL |
| pairing | AdbEngine.pair | :adb-engine | `bootstrap.pair(host,port,code)` / `pairDiscovered(code)` → AdbPairingResultV2 | 同上 | 提供 identityDir + code | 配对服务可达 | NO | NO | NO | NO | libadbd_engine.so | AT.CONSUMER-BOOTSTRAP-0 B3 | VERIFIED | OPTIONAL |
| identity | AdbEngine key store | :adb-engine | `bootstrap.hasIdentity()`；key 材料在 identityDir | `AdbConsumerBootstrap(identityDir)` | **提供 app-private identityDir** | — | — | YES | — | — | libadbd_engine.so | AT.CLI-CONSUMER-0 §4.3 | VERIFIED | MANDATORY(for ADB) |
| connectAuto | AdbRuntime | :adb-engine | `AdbRuntime.connectAuto(keyDir)` → AdbRuntimeDiagnostic | host 调（或经 ensureReady） | 提供 keyDir | WD ON | → READY | NO | — | — | libadbd_engine.so | AT.V3-2.3 | VERIFIED | MANDATORY(for ADB) |
| ensureReady | AdbRuntime | :adb-engine | `AdbRuntime.ensureReady(keyDir, statusListener)` → AdbRuntimeState | host 调（CLI 冷路径经 ATCliExecution） | 提供 keyDir + listener | WD ON | → READY | NO | — | — | libadbd_engine.so | AT.CLI-0.1A | VERIFIED | MANDATORY(for ADB) |
| recovery | AdbRuntime + AdbRecoveryCoordinator | :adb-engine | `AdbRuntime.onOperationResult(transportLost)` → bounded recover | backend 自动触发（operation-driven） | 不主动调 | ADB 曾 READY | YES | YES | — | — | libadbd_engine.so | AT.V3-2.3 | VERIFIED | — |
| lifecycle/status projection | AdbRuntime（owner）+ host（投影） | :adb-engine + host | `AdbRuntime.addLifecycleListener` / host status hub | host 注册 listener（:app AdbCliStatusHub / consumer host T_STATUS） | 注册 listener + 投影策略 | — | — | — | — | — | — | AT.CLI-0.1A/0.1B | VERIFIED | OPTIONAL(Human UI) |
| **Terminal Emulator** | AT Engine（未来） | :at-engine | **不存在** | — | — | — | — | — | — | — | — | — | **NOT_IMPLEMENTED / DEFERRED** | — |
| **ScreenState** | AT Engine（未来） | :at-engine | **不存在** | — | — | — | — | — | — | — | — | — | **NOT_IMPLEMENTED / DEFERRED** | — |
| **Screen Snapshot** | AT Engine（未来） | :at-engine | **不存在** | — | — | — | — | — | — | — | — | — | **NOT_IMPLEMENTED / DEFERRED** | — |

**CONSUMER_OPTIONALITY 说明**：除 ADB 路径必需的 identity/connectAuto/ensureReady（标记 MANDATORY
for ADB）外，所有 execution capability 对 consumer 都是 **OPTIONAL**——consumer 按产品需求选择
暴露哪些。Local 路径对 consumer 总是 OPTIONAL（无 ADB 的纯 Local consumer 合法存在）。

---

## 4. Integration State Taxonomy（§9）

正式定义并区分 7 层。**本轮只定义前 5 层的 AT-side 语义**；后 2 层（PRODUCT_EXPOSED /
MODEL_VISIBLE）明确划给 consumer 产品层，**非 AT responsibility**。

| 层 | 名称 | 语义 | AT/Consumer |
|---|---|---|---|
| 1 | `CAPABILITY_AVAILABLE_IN_AT` | AT source 中存在该 capability 的实现（已验证） | **AT** |
| 2 | `PHYSICALLY_PACKAGED` | capability 的物理 artifact（AAR/native .so/class）可生成并随 consumer 打包 | AT（build） |
| 3 | `PHYSICALLY_CONSUMED_BY_HOST` | consumer 的 dependency graph 实际包含该 module/artifact | **Consumer** |
| 4 | `WIRED_IN_COMPOSITION` | host composition root 实际构造/连接了该 capability 的对象 | **Consumer** |
| 5 | `CALLABLE_BY_HOST` | host 代码可实际调用该 capability 的 public surface | **Consumer** |
| 6 | `PRODUCT_EXPOSED` | consumer 产品 UI / 功能把该 capability 暴露给最终用户 | Consumer 产品层（**非 AT**） |
| 7 | `MODEL_VISIBLE` | consumer 把该 capability 暴露给 AI 模型 / agent tool | Consumer 产品层（**非 AT**） |

**核心纪律**（防止历史描述冲突 A-E 复发）：

- `MODEL_VISIBLE = NO` **绝不**等于 `AT_INTEGRATED = NO`。第 6/7 层是 consumer 产品策略，
  与第 1-5 层（AT 集成）正交。
- 反之，`CAPABILITY_AVAILABLE_IN_AT = YES` **绝不**等于 consumer 已经 wired/callable。
  AT 提供 capability（层 1-2），consumer 决定是否消费（层 3-5）。
- 「Local not consumed / Persistent not consumed / AT CLI not consumed」这类描述，必须明确
  它说的是**哪一层**：是 AT 没有（层 1，本轮证明 AT **有**），还是 consumer 没接（层 3-5，
  是 consumer 的 wiring gap，非 AT 缺陷）。
- 历史冲突的根因是**把层 6/7 的「未暴露给模型」误写成层 1 的「AT 没有」**。本轮冻结此区分。

---

## 5. Composition Object Cardinality（§13/§14/§15 — HARD QUESTIONS）

### 5.1 ATEngine cardinality — role vs instance（N2 规范化，reference = `:at-cli-consumer-host`）

源码证据（`AtCliConsumerHost.kt`）：

```kotlin
object AtCliConsumerHost {                       // 1 个 composition root（process-scoped object）
    fun localEngine(): ATEngine = ATEngine(oneShot = null, persistent = LocalPersistentBackend())
    fun adbEngine(): ATEngine = ATEngine(oneShot = AdbOneShotBackend(placeholder),
                                         persistent = AdbPersistentBackend(placeholder))
}
```

**关键区分（N2）**：上面是 **factory-style**（`fun localEngine(): ATEngine = ATEngine(...)`）。
每次调用 getter 都会**new 一个新的 `ATEngine` facade object**。因此真正稳定、可冻结的是
**engine ROLES**（逻辑角色），**不是** heap 上的 `ATEngine` instance count。

| 项 | 值 | 证据 / 说明 |
|---|---|---|
| CONSUMER_COMPOSITION_ROOT | **1** logical owner per host process | `object AtCliConsumerHost`（process-scoped） |
| ATENGINE_ROLE_COUNT_REFERENCE_COMPOSITION | **2** | local-engine role + adb-engine role（逻辑角色） |
| LOCAL_ENGINE_ROLE | **1** | `localEngine()` 角色 → `ATEngine(null, LocalPersistentBackend)` |
| ADB_ENGINE_ROLE | **1** | `adbEngine()` 角色 → `ATEngine(AdbOneShotBackend, AdbPersistentBackend)` |
| LOCAL_AND_ADB_SAME_ENGINE_ROLE | **NO** | local 与 adb 是两个不同的逻辑角色（不同 backend 组合） |
| LOCAL_AND_ADB_SAME_COMPOSITION_ROOT | **YES** | 两个角色都由 `AtCliConsumerHost` 构造 |
| **ATENGINE_INSTANCE_COUNT** | **NOT_A_LIFECYCLE_INVARIANT** | factory/getter 可反复 new 新 facade object；instance 数不是不变量 |
| **ATENGINE_INSTANCE_CARDINALITY_GUARANTEE** | **NONE** | 进程内 `ATEngine` 对象数量无保证（可 0/1/2/N，取决于调用） |

**为什么 instance count 不是 invariant**：`ATEngine` 是 **thin facade**（`class ATEngine` 只持有
`oneShot: ATOneShotBackend?` + `persistent: ATPersistentBackend?` 两个 backend 字段，**无 lifecycle
state / 无连接 / 无资源**）。backend-injected、无状态，调用 factory 产生新 facade 对象没有副作用。
所以「一个 consumer 进程永远只能有两个 ATEngine instance」**不成立**，也不应被读出来。

**真正需要冻结的 cardinality invariants**（§10）：

```
CONSUMER_COMPOSITION_ROOT       = 1 logical owner per host process
ADBRUNTIME_LIFECYCLE_OWNER      = 1 process-scoped singleton（object AdbRuntime）
RECOVERY_OWNER                  = 1（AdbRecoveryCoordinator，AdbRuntime 持有，单 ReentrantLock）
TRANSPORT_OBSERVER_OWNER        = 1（read-only，bound to AdbRuntime lifecycle）
IDENTITY_OWNER                  = 1 per consumer app-private identity location（语义 owner，不要求一个 File object）
ATEngine object count           = 不属于 invariant（NOT_A_LIFECYCLE_INVARIANT）
```

**`SECOND_AT_ENGINE = NO` 的准确历史含义**（§11，统一解释，不重写历史 evidence）：

```
SECOND_AT_ENGINE = NO  应理解为：
  ✓ NO second independent AT execution subsystem（没有第二套独立 AT 执行子系统）
  ✓ NO second ownership island（没有绕过 host composition 的第二个 engine 所有权孤岛）
  ✗ 不是「process may only contain one ATEngine object」
  ✗ 不是「must contain exactly two ATEngine objects」
```

`:app`（`AppRuntime`）结构完全相同：`localEngine()` + `adbEngine()` 两个**角色**，1 个
composition root（`object AppRuntime`），同样 instance count 非 invariant。

### 5.2 AdbRuntime cardinality

源码证据（`AdbRuntime.kt`）：`object AdbRuntime { ... }` —— **Kotlin `object` = JVM 进程级单例**。

| 项 | 值 | 证据 |
|---|---|---|
| ADBRUNTIME_OWNER | `AdbRuntime`（Kotlin object） | `object AdbRuntime` |
| ADBRUNTIME_MODEL | **process-scoped singleton object**（conceptual owner = singleton object = per-host-process instance 三者一致） | `object AdbRuntime` + `initialize()` idempotent（`if (readinessBackend != null) return`） |
| ADBRUNTIME_COUNT_PER_CONSUMER_PROCESS | **1** | object 单例 |
| RECOVERY_OWNER_COUNT | **1**（`AdbRecoveryCoordinator`，由 AdbRuntime 持有，单 ReentrantLock 串行化） | `AdbRuntime.recoveryCoordinator` + `lock` |
| TRANSPORT_OBSERVER_OWNER | **1**（read-only，bound to AdbRuntime lifecycle） | `AdbRuntime.startTransportObserver`（`AtomicBoolean` 幂等） |
| PAIRING_IMPLEMENTATION_COUNT | **1**（`AdbConsumerBootstrap` 是唯一调 `AdbEngine.pair` 处） | `AdbConsumerBootstrap.pair` |

**「unique lifecycle owner」的准确含义**：它是**进程级 singleton object**（不是 conceptual-only，
不是 per-connection）。一个 consumer 进程内，`AdbRuntime.initialize()` 幂等，全局只有一个
lifecycle 状态机 + 一个 recovery coordinator + 一个 transport observer。localEngine 和 adbEngine
两个 ATEngine 都**复用**这同一个 AdbRuntime 建立的连接（`AdbOneShotBackend.ensureConnected`
short-circuit on `AdbEngine.connectionState().isOnline`）。

### 5.3 AdbConsumerBootstrap cardinality / semantics（§15）

源码证据（`AdbConsumerBootstrap.kt`）：`class AdbConsumerBootstrap(private val identityDir: String)`
—— **普通 class**（非 object），构造只存 `identityDir`，**无任何 lifecycle 字段**。

| 项 | 值 | 证据 |
|---|---|---|
| BOOTSTRAP_OBJECT_COUNT_CONSTRAINT | **无约束**（可创建多个轻量 facade 实例；典型 host 持 1 个） | `class`（非 object），构造无副作用 |
| BOOTSTRAP_LIFECYCLE_OWNER | **NO**（纯 facade/projection；不创建 AdbRuntime、不驱动 lifecycle） | 文件头注释 + `bootstrapState()` 只读 `AdbRuntime.readinessState()` |
| 是否创建 AdbRuntime | **NO** | `bootstrapState()` 调 `AdbRuntime.isInitialized()`/`readinessState()`（读，不建） |
| 是否只是 facade/projection | **YES** | `bootstrapState` = AdbRuntime 状态 + identity 存在性的**只读投影**；`discoverPairing`/`pair`/`pairDiscovered` 委托 AdbRuntime/AdbEngine |
| 多 facade 是否仍共享唯一 AdbRuntime | **YES** | 所有实例的 `bootstrapState()`/`discoverPairing()` 都读同一个 `AdbRuntime` 单例 |

`pair()` / `pairDiscovered()` 是**唯一**调 `AdbEngine.pair` 的地方（PAIRING_IMPLEMENTATION_COUNT=1）。

---

## 6. Local PTY Contract（§11）

```
LOCAL_PERSISTENT_IS_REAL_PTY = YES
```

源码证据：

| 项 | 值 |
|---|---|
| 谁创建 PTY | `LocalPtySession.start()`（:at-backend-local，经 `LocalPtyNative` JNI → `local_pty.cpp`） |
| backend class | `LocalPersistentBackend.openSession()` → `LocalPtySession` → `LocalTerminalSession`（ATTerminalSession impl） |
| shell executable | `/system/bin/sh -i`（interactive；`LocalOneShotBackend` 用 `/system/bin/sh -c <cmd>`） |
| rows/cols 初始化 | `LocalPtySession` PTY 建立时的 winsize；后续 `resize(rows,cols)` 经 `pty.setWinsize`（ioctl TIOCSWINSZ） |
| write/input path | `ATTerminalSession.write(bytes)` → `pty.write(bytes)`（PTY master fd） |
| raw byte output | `ATSessionEvent.Data(bytes)`（merged stdout+stderr，PTY 单流）；push（setListener reader thread）或 pull（read）单一消费者 |
| Ctrl-C 语义 | 写入 0x03 byte 到 PTY → 内核向 foreground process group 发 SIGINT（真实 PTY 信号语义） |
| resize path | `ATTerminalSession.resize(rows,cols)` → `pty.setWinsize` → `ATSessionEvent.Resized` |
| cwd continuity | 同一 PTY 子进程 shell 持续（`sh -i` 不退出，cwd 保持） |
| env continuity | 同一 PTY 子进程环境持续 |
| natural EOF | PTY 子进程退出 → `pty.read` 返回 <0 → `state=EXITED` + `ATSessionEvent.Eof` |
| explicit close | `ATTerminalSession.close(terminateProcess)` → `pty.close()` + `ATSessionEvent.Closed` |
| multi-session semantics | 每个 `openSession` 独立 PTY 子进程，互不干扰 |
| native library | `libat_local_backend.so`（arm64-v8a，CMake `at_local_backend`，`c++_static`） |

**明确**：Local persistent PTY capability（real PTY + raw bytes + 信号 + resize）**与**
Terminal Emulator / ScreenState **完全不同**。前者是**已验证的 byte-stream PTY**；后者
（ANSI 解析 / cursor / screen grid / snapshot）**NOT_IMPLEMENTED / DEFERRED**。
raw PTY bytes **不是** ScreenState（不能把 raw escape bytes 当 screen grid）。

---

## 7. ADB Remote PTY Contract（§12）

```
ADB_PERSISTENT_IS_REMOTE_PTY = YES
```

源码证据（`AdbBackend.kt`）：

| 项 | 值 |
|---|---|
| 真实 service string | **`shell,v2,pty:`**（`AdbPersistentBackend.openSession` → `AdbEngine.openInteractive("shell,v2,pty:")`；空 command after "pty:"，AOSP services.cpp 语义） |
| protocol path | ADB shell-v2 over TLS（`libadbd_engine.so`，vendored BoringSSL）；`AdbEngine.openInteractive(service)` → `AdbInteractiveStream` |
| backend class | `AdbPersistentBackend.openSession()` → `AdbInteractiveStream` → `AdbTerminalSession`（ATTerminalSession impl） |
| stdin | `AdbTerminalSession.write(bytes)` → `stream.write(bytes)`（shell-v2 WRTE） |
| stdout/stderr | shell-v2 单流（merged）→ `ATSessionEvent.Data`；push（reader thread） |
| Ctrl-C | write 0x03 byte → 远程 PTY SIGINT（同 Local 语义，远端 shell 处理） |
| resize | `AdbTerminalSession.resize(rows,cols)` → `stream.resize`（shell-v2 resize frame）；open 时 `launch.initialRows/initialCols` 设初值（remote PTY 默认 0x0，必须设） |
| cwd/env | 远程 shell 进程持续（remote PTY 不退出则 cwd/env 保持） |
| EOF | 远程 shell 退出 → stream end（read 返回 -1）→ `state=EXITED` + `ATSessionEvent.Eof`（shell-v2 facade 不暴露 exit code，stream end 视为 EXITED） |
| close | `AdbTerminalSession.close` → `stream.close()`（CLSE；**不**关 ADB 连接，连接复用） |
| multi-session | 多个 `openInteractive("shell,v2,pty:")` 共享同一 ADB 连接（多 stream） |
| transport loss | open/execute 失败 + 连接曾 ONLINE → `AdbRuntime.onOperationResult(transportLost=true)` → bounded recovery（no replay） |
| reconnect semantics | operation-driven：AdbRuntime 单 recovery owner 重建**连接**，不重执行失败操作 |

**明确**：Remote PTY（`shell,v2,pty:` 真实远程 PTY）**≠** ScreenState。Remote PTY 是
**已验证的远程 byte-stream PTY**；ScreenState（cursor/grid/snapshot）NOT_IMPLEMENTED / DEFERRED。

---

## 8. Direct ADB Capability Status（§19 — HARD QUESTION）

```
DIRECT_ADB_ONE_SHOT   = VALID CURRENT CAPABILITY
DIRECT_ADB_PERSISTENT = VALID CURRENT CAPABILITY
SHELL_VISIBLE_ADB     = VALID CURRENT CAPABILITY（额外 terminal frontend，非替代）
DIRECT_ADB_SUPERSEDED_BY_CLI = NO
```

源码证据 + 推理：

- `AdbOneShotBackend` / `AdbPersistentBackend`（:at-backend-adb）是 **AT Engine backend
  contract 的正常实现**，由 `ATEngine.openSession`/`execute` 直接调用。**没有任何 source /
  doc 标记它们 deprecated / legacy / internal-only**。
- `:at-cli` 的 shell-visible `adb` 路径**最终也路由到同一个 ADB capability**：
  `AtCliMain` → `AdbCliBridgeServer.onOneShot/onInteractiveOpen` →
  `execution.adbEngine().execute/openSession`（host 的 `AdbOneShotBackend`/`AdbPersistentBackend`）
  → ADB Engine。即 shell-visible `adb` 是**同一 ADB capability 上的一个 terminal frontend**，
  它**不替代** direct ADB backend，而是**复用**它。
- 两者关系：

```
                 ┌─────────────────────────────────────────────┐
                 │           同一个 ADB capability              │
                 │  AdbOneShotBackend / AdbPersistentBackend    │
                 │  → AdbEngine → AdbRuntime(连接) → adbd       │
                 └─────────────────────────────────────────────┘
                       ▲                                ▲
                       │ 直接调用（programmatic）          │ 经 :at-cli bridge 路由
        ┌──────────────┴──────────┐        ┌─────────────┴──────────────┐
        │ Consumer 直接            │        │ shell-visible `adb`         │
        │ ATEngine.execute/        │        │ (Local sh 内 `adb` 函数)     │
        │ openSession（direct ADB）│        │ → AtCliMain → bridge →      │
        │                          │        │   execution.adbEngine()     │
        └──────────────────────────┘        └────────────────────────────┘
```

**结论**：`SHELL_VISIBLE_ADB` **IS AN ADDITIONAL TERMINAL FRONTEND OVER THE SAME ADB
CAPABILITY**，**不是** REPLACES direct ADB backend。AT 没有 deprecation evidence，
**不得**把 direct ADB capability 标成 legacy。consumer 可以选择直接 programmatic ADB
（`ATEngine` + ADB backend）和/或嵌入 shell-visible `adb` CLI，两者都合法、都路由到同一
AdbRuntime 连接。

---

## 9. AdbRuntime / Bootstrap Ownership（§16 — Formal Boundary）

```
FIRST_USE_BOOTSTRAP_OWNER     = AdbConsumerBootstrap（facade：bootstrapState/discoverPairing/pair）
NORMAL_COMMAND_READINESS_OWNER = AdbRuntime（ensureReady / connectAuto）
PAIRING_OWNER                 = AdbEngine.pair（primitive）；consumer-facing 入口 = AdbConsumerBootstrap.pair
CONNECT_AUTO_OWNER            = AdbRuntime.connectAuto
RECOVERY_OWNER                = AdbRuntime（+ AdbRecoveryCoordinator，单 owner）
LIFECYCLE_OWNER               = AdbRuntime（UNIQUE，process-scoped singleton）
```

**正常已配对后的 ADB readiness path**（关键）：

```
consumer 已配对（identity 存在 + device trusted）
    ↓
adb shell <cmd> / ADB backend execute
    ↓
ATCliExecution.ensureReady（host）
    ↓
AdbRuntime.ensureReady(keyDir, statusListener)   ← 正常 readiness owner
    ├─ fast path: AdbRuntime.state == READY → 立即返回
    └─ cold path: WD readiness → (auto-WD if WRITE_SECURE_SETTINGS) → connectAuto(keyDir) → READY
    ↓
ATEngine.execute / openSession（复用 AdbRuntime 已建立的连接）
```

**禁止得出的结论**：`AdbConsumerBootstrap supersedes AdbRuntime` —— **源码不支持**。
`AdbConsumerBootstrap` 只在**首次配对**（无 identity）时是 consumer-facing 入口；
配对完成后，正常命令 readiness **完全由 `AdbRuntime.ensureReady`/`connectAuto` 拥有**。
`AdbConsumerBootstrap.bootstrapState()` 只是 `AdbRuntime` 状态的**只读投影**（用于 consumer
判断「要不要配对 / WD 是否关 / 是否已 READY」），它**不驱动** AdbRuntime lifecycle。

**首次 pairing vs 正常 readiness 分工**：

| 阶段 | owner | 路径 |
|---|---|---|
| 首次（无 identity） | AdbConsumerBootstrap | `bootstrapState()=PAIRING_REQUIRED` → `discoverPairing()` → `pair()/pairDiscovered()`（调 AdbEngine.pair，创建 identity） |
| 首次配对后连接 | AdbRuntime | `connectAuto(keyDir)`（用刚创建的 identity 连接 → READY） |
| 正常命令（已配对） | AdbRuntime | `ensureReady(keyDir)`（fast/cold path → READY）→ ATEngine execute/openSession |
| transport loss | AdbRuntime | `onOperationResult(transportLost)` → bounded recovery |

---

## 10. CLI Embedding Contract（§21 — HARD QUESTION）

**「consumer depends on :at-cli」不等于完成 CLI 集成。** 下面是 host 的**真实 obligations**
（每项 EXACT SOURCE + SEMANTICS）：

| # | 项 | EXACT SOURCE | SEMANTICS |
|---|---|---|---|
| 1 | 谁启动 AdbCliBridgeServer | `AtCliConsumerHost.initialize()` → `AdbCliBridgeServer(dir, cliExecution()).start()`；`AppRuntime.initialize()` 同 | **host 启动**（在 host initialize 时，idempotent）。bridge 是 host 进程内的 UDS server |
| 2 | bridge lifecycle | `AdbCliBridgeServer.start()`（native `nativeStart`，daemon thread pool）/ `stop()` | 与 host 进程同生命周期；`initialize` 启动，`stopCliBridge` 关闭。bridge 是 thin frontend，**不创建** AdbRuntime/ATEngine/identity |
| 3 | UDS path 谁决定 | `AdbCliBridgeServer.socketPath = File(filesDir, "cli_bridge.sock").absolutePath` | **host 的 app-private files dir**（`cli_bridge.sock`）。host 通过 `cliBridgeSocketPath()` 暴露给 LocalCliBootstrap 注入 |
| 4 | peer UID 认证 | `ATCliBridgeHandler.onClientAuth(fd, peerUid) = peerUid == Process.myUid()` | **same-UID only**（SO_PEERCRED）。CLI 以 host UID 运行（app_process 继承 host 进程 UID），所以只能连自己 host 的 bridge |
| 5 | ATCliExecution 由谁实现 | **host 实现**（`AtCliConsumerHost.cliExecution()` / `:app` 的 `AppCliExecution`） | `:at-cli` 的 bridge 依赖 `ATCliExecution` **SPI**（host-provided），**不依赖任何 :app 具体类**。这是 host-agnostic 的接缝 |
| 6 | ATCliExecution exact methods | `ATCliExecution.kt` | `adbEngine(): ATEngine` / `ensureReady(onStatusLine): Boolean`（default 调 2-arg）/ `ensureReady(sessionId, onStatusLine): Boolean` / `adbIdentityDir(): String` / `consumerBootstrap(): AdbConsumerBootstrap`（default `AdbConsumerBootstrap(adbIdentityDir())`） |
| 7 | adbEngine() 指向什么 | `AtCliConsumerHost.adbEngine()` = `ATEngine(AdbOneShotBackend(placeholder), AdbPersistentBackend(placeholder))` | host 的 ADB ATEngine（placeholder `AdbConnectionRequest("127.0.0.1", 0, keyDir)`，port 0 = 复用 AdbRuntime 连接） |
| 8 | ensureReady() 如何接入 AdbRuntime | `AtCliConsumerHost.cliExecution().ensureReady` → `AdbRuntime.ensureReady(adbIdentityDir()){...}`（host 无 Human UI，全走 onStatusLine）；`:app` 版先 subscribe session + fast-path backfill | 委托 AdbRuntime（UNIQUE readiness owner）。host 决定是否 live-project（:app）或 T_STATUS fallback（consumer host） |
| 9 | consumerBootstrap() 如何提供 | `ATCliExecution.consumerBootstrap()` default = `AdbConsumerBootstrap(adbIdentityDir())` | host 用**自己的 app-private identityDir** 构造 bootstrap。bridge `onPair` 调 `execution.consumerBootstrap().pair(...)`（**不直接** AdbEngine.pair） |
| 10 | identityDir 如何提供 | `AtCliConsumerHost.initialize` 建 `File(app.filesDir, "at-cli-identity")`；`adbIdentityDir()` 返回 | **host 的 app-private dir**（self-generated，非 :app key） |
| 11 | AtCliMain 如何经 app_process 启动 | `LocalCliBootstrap` 生成 `adb()` 函数 → `/system/bin/app_process -Djava.class.path="$AT_APP_APK" /system/bin <mainClass> "$@"` | app_process（zygote_exec，untrusted_app 可 exec）加载 host APK 内的 `com.at.cli.AtCliMain` |
| 12 | app_process classpath/APK path | `AT_APP_APK` env（host 注入 `applicationInfo.sourceDir`，`AtCliConsumerHost.appApkPath()`/`AppRuntime.appApkPath()`） | host 在 LocalCliBootstrap.writeScript 时把**自己的 base.apk 绝对路径**注入 `$AT_APP_APK`（无 `pm path` per command，无硬编码路径） |
| 13 | LocalCliBootstrap 如何把 adb() 放入 shell | `LocalCliBootstrap.writeScript(scriptPath, apkPath, sockPath, sessionId)` 写 `adb_bootstrap.sh`（export env + `adb()` 函数）；`sourceLine()` 生成 `. 'adb_bootstrap.sh'` 行 | host 把脚本写到**自己的 filesDir**，Local session 启动时 source。`adb()` 函数引用 `$AT_APP_APK`/`$AT_CLI_BRIDGE_SOCK`/`$AT_SESSION_ID` shell 变量 |
| 14 | 每 session source 还是进程级 provision | **per-session source**（每个 Local session source 一次 `adb_bootstrap.sh`，带该 session 的 `AT_SESSION_ID`） | 脚本是 per-session 文件（多行函数经 PTY 会被 mangle，source 文件 robust）。`AT_SESSION_ID` 是 Local session 的稳定 id |
| 15 | 使用哪些 environment variables | `AT_APP_APK` / `AT_CLI_BRIDGE_SOCK` / `AT_SESSION_ID` / `AT_CLI_ROWS` / `AT_CLI_COLS`（后两个仅 interactive no-arg 时由 shell wrapper `stty size` 注入） | `LocalCliBootstrap` 常量定义；`AtCliMain` `resolveSocketPath`/`resolveSessionId`/`resolveIntEnv` 读取 |
| 16 | AT_SESSION_ID 如何传播 | `LocalCliBootstrap` export `$AT_SESSION_ID`（per-session）→ `AtCliMain.resolveSessionId()` 读 env → HELLO payload（version + sessionId）→ bridge `onHello(fd, sessionId)` 绑 fd→session | 让 host 能把 ADB lifecycle status **live-project 到 bound Local session**（:app）；consumer host 用 T_STATUS fallback（sessionId 0/忽略） |
| 17 | rows/cols 如何传播 | interactive no-arg：shell wrapper `stty size` → `$AT_CLI_ROWS/$AT_CLI_COLS` → `AtCliMain` 读 → `T_INTERACTIVE` payload `[rows:4LE][cols:4LE]` → bridge `onInteractiveOpen` → `openSession(Launch(initialRows,initialCols))` → `session.resize` | 初始 remote PTY size（remote PTY 默认 0x0，必须设） |
| 18 | interactive resize 如何 relay | host 层 `AdbCliBridgeServer.relayInteractiveResize(sessionId, rows, cols)`（`AppRuntime.relayInteractiveResize` 调）→ 若该 Local session 正 hosting active `adb shell` interactive → forward 到 inner Remote PTY | host/CLI composition；**AT Engine 不知 nested adb shell**。`interactiveForSession` map（CLI-connection 映射，非第二 registry） |
| 19 | Host status 与 remote stdout 隔离 | one-shot：`T_STATUS`（stderr 通道，先 emit）vs `T_OUT`（remote stdout）/`T_ERR`（remote stderr）分离；interactive：`T_OUT`（remote output）/`T_STATUS`（lifecycle）/`T_ERROR`（error）分离 | host status 走 `T_STATUS`（CLI 客户端 stderr），remote stdout 走 `T_OUT`。`:app` 版 bound Human session 走 Host projection（`AdbCliStatusHub`），否则 T_STATUS fallback。**status 永不混进 remote stdout** |
| 20 | process restart 后谁重新 provision | **host 重新 `initialize()`**（AppRuntime/AtCliConsumerHost initialize 在 Application/Activity onCreate 跑）→ 重建 bridge + 重写 bootstrap script + Local session 重新 source | AdbRuntime.initialize 幂等；bridge 重启；Local session 是新进程，重新 source `adb_bootstrap.sh`。identity 持久化在 filesDir（重启复用，无需 re-pair） |

**关键**：`ATCliExecution` 是 host-agnostic 接缝。host 必须**自己**提供 `adbEngine()`（自己的
ATEngine）、`ensureReady`（接自己的 AdbRuntime）、`adbIdentityDir()`（自己的 app-private dir）、
`consumerBootstrap()`（自己的 bootstrap）。bridge（:at-cli）只做 UDS 认证 + 帧路由 + 把命令交给
host 的 `ATCliExecution`。

---

## 11. Identity Contract（§25）

| 项 | 值 | 证据 |
|---|---|---|
| IDENTITY_OWNER | **PER_CONSUMER_APP_PRIVATE** | consumer host: `filesDir/at-cli-identity`；:app: `filesDir/adb_identity` |
| IDENTITY_STORAGE_CONTRACT | app-private files dir 下 `adbkey` / `adbkey.pub`（+ `adb_cert.pem`）；`AdbConsumerBootstrap.hasIdentity()` = 任一存在 | `AdbConsumerBootstrap.hasIdentity`（`File(identityDir,"adbkey").exists() || adbkey.pub`） |
| IDENTITY_CREATION_OWNER | **AdbEngine**（key store，native `adb_key_store.cpp` / `rsa_2048_key.cpp` / `x509_generator.cpp`）；经 `AdbEngine.pair(host,port,code,keyDir)` 在首次配对时创建/更新 | `AdbEngine.pair` 签名含 `keyDir`；consumer 不自己生成 key |
| 何时生成 | 首次 pairing 成功时（`AdbEngine.pair` 写入 keyDir） | AT.CLI-CONSUMER-0 §4.3 + AT.CONSUMER-BOOTSTRAP-0 B3 |
| 何时复用 | 之后每次 `connectAuto`/`ensureReady`/`connect` 用同一 keyDir（self-generated key 复用，无需 re-pair） | `AdbRuntime.connectAuto(keyDir)` / `AdbEngine.connect(host,port,keyDir)`；AT.CLI-CONSUMER-0 CONSUMER_IDENTITY_REUSE_AFTER_RESTART=PASS |
| 谁可以删除 | consumer（host 可清空自己的 identityDir 以恢复全新身份）；测试用（清空触发 FIRST_USE） | AT.CLI-CONSUMER-0 §4.4 演练步骤 1 |
| 是否 per-consumer private | **YES** | 每个 consumer 用自己的 app-private dir |
| 是否允许共享 | **NO**（不共享 identityDir；不共享 key） | consumer isolation（§25 dex 0 :app 引用） |
| 是否允许复制其它 App key | **NO**（`CONSUMER_KEY_COPIED = NO`，按公钥主体对比证明 self-generated） | AT.CLI-CONSUMER-0 §4.3（`QAAAAO/zNzLx...` ≠ app `QAAAAPsYTM3N...`）；`KEY_COPY_SUPPORTED = NO` |

**KEY_COPY_SUPPORTED = NO**。consumer 必须 self-generate（经 AdbEngine.pair），**不得**复制
AndroidTerminal 或其它 App 的 adbkey。

---

## 12. Consumer Construction Recipe（§26 — reference = `:at-cli-consumer-host`）

全新第三方 APK 的 canonical construction sequence（**exact order / objects 由源码得出**，
非机械照抄任务文本）：

```
1. Application / Composition Root（process-scoped object，如 AtCliConsumerHost）
   │
2. identityDir（host app-private：File(filesDir, "<my>-identity")，mkdirs）
   │
3. AdbRuntime.initialize(context)   ← 幂等，wire readiness + NSD discovery + endpoint store
   │                                  + recovery coordinator + transport observer（唯一 lifecycle owner）
   │
4. Local backend：LocalPersistentBackend()   （若要 Local persistent；LocalOneShotBackend 可选）
   │
5. ADB backend：AdbOneShotBackend(AdbConnectionRequest("127.0.0.1", 0, keyDir))
   │             AdbPersistentBackend(same placeholder)   （port 0 = 复用 AdbRuntime 连接）
   │
6. AT Engine objects：
   │   localEngine = ATEngine(oneShot=null, persistent=LocalPersistentBackend())
   │   adbEngine   = ATEngine(oneShot=AdbOneShotBackend, persistent=AdbPersistentBackend)
   │   （2 个 ATEngine object，共享 1 个 composition root + 1 个 AdbRuntime 单例）
   │
7. AdbConsumerBootstrap(identityDir)   ← 轻量 facade（首次配对用；正常 readiness 用 AdbRuntime）
   │
8. ATCliExecution host impl（若嵌入 shell-visible adb）：
   │   adbEngine() -> adbEngine
   │   ensureReady(sid, onStatusLine) -> AdbRuntime.ensureReady(keyDir){...}
   │   adbIdentityDir() -> identityDir
   │   consumerBootstrap() -> AdbConsumerBootstrap(identityDir)
   │
9. CLI Bridge：AdbCliBridgeServer(filesDir, cliExecution()).start()   （same-UID UDS，host-private）
   │
10. LocalCliBootstrap(mainClass="com.at.cli.AtCliMain")：
   │   writeScript(scriptPath, appApkPath, cliBridgeSocketPath, sessionId)  → adb_bootstrap.sh
   │   sourceLine(scriptPath) → ". 'adb_bootstrap.sh'\n"（写进每个 Local session）
   │
11. Local sessions：openSession(LocalPersistentBackend) → source bootstrap line → `adb` 函数可用
   │
12. ADB sessions：AdbRuntime.connectAuto(keyDir)（首次/冷启动）→ adbEngine.openSession/execute
```

**最小 Local-only consumer**（无 ADB）：只需 1-4 + 6(localEngine) + 11（无 5/7/8/9/10/12）。
**最小 ADB-direct consumer**（无 CLI）：只需 1-6 + 12（无 8/9/10/11）。
**Full terminal consumer**：全部 1-12。

---

## 13. Consumer Lifecycle Recipe（§27）

| 步骤 | 谁负责 | mandatory | lazy/on-demand |
|---|---|---|---|
| PROCESS START | OS | — | — |
| APPLICATION INIT（composition root initialize：AdbRuntime.initialize + identityDir + 可选 bridge start） | host（Application/Activity onCreate） | YES（若要 ADB/CLI） | AdbRuntime.initialize 幂等 |
| CLI BRIDGE START（AdbCliBridgeServer.start） | host（initialize 内） | 仅若要 shell-visible adb | 与进程同生命周期 |
| LOCAL SESSION OPEN（openSession(LocalPersistentBackend) + source adb_bootstrap.sh） | host（用户/程序开 Local terminal） | 仅若要 Local | on-demand（per session） |
| LOCAL COMMAND（LocalOneShotBackend.execute / LocalTerminalSession） | ATEngine → Local backend | — | on-demand |
| ADB COMMAND ON DEMAND（ensureReady → AdbRuntime.ensureReady → ATEngine.execute/openSession） | AdbRuntime（readiness）+ ATEngine（execution） | 仅若跑 ADB 命令 | 首次冷启动 connectAuto；之后 fast-path |
| FIRST-USE PAIRING（bootstrapState=PAIRING_REQUIRED → discoverPairing → pair/pairDiscovered） | AdbConsumerBootstrap（facade）→ AdbEngine.pair（创建 identity） | 仅首次（无 identity） | 一次性（之后复用 identity） |
| ADB READY（connectAuto → READY） | AdbRuntime | 仅若 ADB | 首次配对后 + 每次冷启动 |
| REMOTE PTY OPEN（adbEngine.openSession → AdbPersistentBackend → shell,v2,pty:） | ATEngine → ADB backend → ADB Engine | 仅若 interactive | on-demand |
| SESSION CLOSE（ATTerminalSession.close；CLI 关 T_CLOSE） | ATEngine / bridge | — | on-demand |
| PROCESS STOP | OS（bridge stop / session close） | — | — |
| PROCESS RESTART | OS；host 重新 initialize（AdbRuntime 幂等 + bridge 重启 + Local session 重新 source） | — | identity 持久化复用（无需 re-pair） |

**关键**：ADB 命令是 **on-demand**（ensureReady fast/cold path），不是 process start 时强制。
Local 命令完全独立于 ADB（不需 identity/WD/READY）。首次配对是**一次性**（无 identity 时）。

---

## 14. Module / Artifact Map（§30）

以 `settings.gradle.kts` + 各 `build.gradle.kts` 为准：

| MODULE | TYPE | DIRECT_DEPS | TRANSITIVE_DEPS | LIB/APP | NATIVE_LIBS | RESOURCES | MANIFEST_REQUIREMENTS | PROGUARD |
|---|---|---|---|---|---|---|---|---|
| :at-engine | android library | (none) | — | LIB | none | none | minSdk 26 | — |
| :at-backend-local | android library | :at-engine | :at-engine | LIB | libat_local_backend.so | none | minSdk 26, arm64-v8a | — |
| :at-backend-adb | android library | :at-engine, :adb-engine | :at-engine, :adb-engine | LIB | none（纯 JVM adapter） | none | minSdk 26 | — |
| :adb-engine | android library | (none) | — | LIB | libadbd_engine.so（含 vendored BoringSSL static） | none | minSdk 26, arm64-v8a | — |
| :at-cli | android library | :at-engine, :at-backend-local, :at-backend-adb, :adb-engine | 全部 4 + transitives | LIB | libatcli_env.so | none | minSdk 26, arm64-v8a | — |
| :app | android application | :at-engine, :at-backend-local, :at-backend-adb, :adb-engine, :at-cli | 全部 | APP | libatapp_env.so（product-only） | assets/cli（旧 ELF asset） | INTERNET/NETWORK/WIFI/CHANGE_WIFI/**WRITE_SECURE_SETTINGS**/FINE_LOCATION(maxSdk35) | — |
| :at-cli-consumer-host | android application | :at-cli, :at-engine, :at-backend-local, :at-backend-adb, :adb-engine | 全部（**无 :app**） | APP | 经 :at-cli/:at-backend-local/:adb-engine（无 :app native） | none | 同 :app 权限集（**无 :app 依赖**） | — |
| :test-host | android application | :at-engine, :at-backend-local, :at-backend-adb (+adb-engine androidTest) | — | APP | 经 backends | none | — | — |
| :adb-only-test-host | android application | :at-engine, :at-backend-adb, :adb-engine（**无 local/:app**） | — | APP | 经 adb backend | none | — | — |
| :at-shellless-test-host | android application | :at-engine only（+ 自 probe native）（**无 backends/:app**） | :at-engine | APP | libat_shellless_probe.so | none | — | — |

**consumer-relevant 核心 module**（第三方 consumer 实际消费）：`:at-engine` /
`:at-backend-local` / `:at-backend-adb` / `:adb-engine` / `:at-cli`。`:app` 与 4 个 test host
**不是** consumer production 依赖（见 §16）。

---

## 15. Native Library Map（§32）

以 CMakeLists + AAR inspection 为准（全 arm64-v8a，`c++_static`）：

| module | native library | ABI | purpose |
|---|---|---|---|
| :at-backend-local | `libat_local_backend.so` | arm64-v8a | Local PTY（fork/exec `/system/bin/sh`、PTY master、TIOCSWINSZ resize、read/write）+ `local_pty_jni`（JNI `Java_com_at_backend_local_*`） |
| :adb-engine | `libadbd_engine.so` | arm64-v8a | ADB 协议（CNXN/auth/TLS via vendored BoringSSL `libboringssl.a` static）+ pairing（SPAKE2/AES-GCM）+ key store（RSA-2048/X.509）+ shell-v2 + interactive stream + stream hub + transport |
| :at-cli | `libatcli_env.so` | arm64-v8a | CLI bridge UDS server（`cli_bridge_server.cpp`：SO_PEERCRED 认证 + 帧路由）+ native env helper（`native_env.cpp`，JNI `Java_com_at_cli_*`） |
| :app（product-only） | `libatapp_env.so` | arm64-v8a | product-only native（`app_env.cpp`：setenv + UDS test helper only） |
| :at-shellless-test-host（test-only） | `libat_shellless_probe.so` | arm64-v8a | shellless PTY probe（test/validation only） |

**AAR 携带情况**（AAR inspection 实测）：

| AAR | classes.jar | AndroidManifest.xml | R.txt | native .so |
|---|---|---|---|---|
| at-engine-debug.aar | ✅ | ✅ | ✅ | ❌（pure JVM） |
| at-backend-local-debug.aar | ✅ | ✅ | ✅ | ✅ `jni/arm64-v8a/libat_local_backend.so` |
| at-backend-adb-debug.aar | ✅ | ✅ | ✅ | ❌（pure JVM adapter） |
| adb-engine-debug.aar | ✅ | ✅ | ✅ | ✅ `jni/arm64-v8a/libadbd_engine.so`（~1.2MB，含 BoringSSL） |
| at-cli-debug.aar | ✅ | ✅ | ✅ | ✅ `jni/arm64-v8a/libatcli_env.so` |

---

## 16. Physical Consumption Mode Audit（§33 — HARD QUESTION）

AT Freeze 冻结的是 **capability**，**不是永久 packaging ABI**。当前各消费方式状态：

| 消费方式 | 状态 | 证据 |
|---|---|---|
| A. Gradle project dependency（`implementation(project(":at-engine"))` 等） | **VERIFIED** | 全部 10 个 module 都用 `project(...)` 依赖构建成功（consumer host + :app + test hosts 全 BUILD SUCCESSFUL） |
| B. Gradle composite build（`includeBuild`） | **POSSIBLE_BUT_NOT_FORMALLY_VERIFIED** | 技术上可行（标准 Gradle），但当前 repo **无 composite build 配置/验证**；未正式验证 |
| C. raw AAR vendoring（拷贝 .aar 到另一 repo） | **POSSIBLE_BUT_NOT_FORMALLY_VERIFIED**（有 GAP，见下） | AAR 可生成（§14），但 AAR **不携带 transitive module deps**（无 POM）；consumer 必须**手动同时 vendor 全部 5 个 AAR**（at-engine/at-backend-local/at-backend-adb/adb-engine/at-cli）+ 处理 native .so 合并 + manifest merge |
| D. local Maven repository | **NOT_CONFIGURED** | 无 Maven publish 配置（build.gradle.kts 无 `maven-publish` plugin） |
| E. published Maven artifact | **NOT_CONFIGURED** | 无坐标 / 无 publish |

**关键 GAP（§34 AAR Transitivity）**：

- **AAR assemble 成功 ≠ 「拷贝几个 AAR 到另一 repo 就完整工作」**。
- AAR 是**自含**的（classes.jar + 自己的 .so + manifest + R.txt），但**不携带 transitive
  Android module 依赖**（无 POM/dependency metadata）。
- 因此 raw AAR vendoring 时，consumer 必须**显式 vendor 完整 AAR 集合**：

```
REQUIRED_RAW_AAR_SET = {          （N3 规范化：原 SELF_CONTAINED_AAR_SET 改名）
  at-engine-debug.aar,        （pure JVM）
  at-backend-local-debug.aar, （+ libat_local_backend.so）
  at-backend-adb-debug.aar,   （pure JVM）
  adb-engine-debug.aar,       （+ libadbd_engine.so，含 BoringSSL）
  at-cli-debug.aar            （+ libatcli_env.so）
}
```

> **命名语义（N3）**：`REQUIRED_RAW_AAR_SET` 表示「raw AAR vendoring 时**需要**的完整 5-AAR
> 集合」，**不是**「AT 已正式建立并验证的 self-contained binary bundle」。配套语义标志：
>
> ```
> RAW_AAR_SET_CONTENT_KNOWN        = YES   （5 件内容已知，见上）
> RAW_AAR_CONSUMPTION_VERIFIED     = NO    （raw AAR 消费未正式验证）
> FORMAL_SELF_CONTAINED_AAR_BUNDLE = NOT_ESTABLISHED（无正式 bundle / 无 POM / 无 publication）
> AAR_TRANSITIVITY_METADATA        = ABSENT（AAR 不携带 transitive module dep metadata）
> BASE_APK_MUST_CONTAIN_ATCLIMAIN  = YES   （CLI 要求 host base.apk 含 com.at.cli.AtCliMain class）
> ```

  5 个 AAR 的 native .so 在 APK merge 时按 ABI 合并（同 arm64-v8a 不冲突）。manifest merge
  由 AGP 处理。JNI symbol / class package 匹配（`Java_com_at_cli_*` / `Java_com_at_backend_local_*`
  / `Java_com_adb_engine_*`，各自 AAR 的 classes.jar 与 .so 对应）。

- **CLI app_process 加载的额外要求**：`AtCliMain` 经 `app_process -Djava.class.path=$AT_APP_APK`
  加载，所以 consumer 的 **base.apk 必须包含 `com.at.cli.AtCliMain` class**（即 :at-cli 的
  classes 必须在 host APK 的 classpath 上）。Gradle project dependency 自动满足；raw AAR
  vendoring 必须确保 :at-cli classes 进入 host APK。

**结论**：当前**正式验证**的消费方式是 **Gradle project dependency**（A）。raw AAR vendoring
（C）**技术上可能但非正式验证**，有「必须手动 vendor 完整 5-AAR 集合 + base.apk 含 AtCliMain」
的 GAP。Maven（D/E）**未配置**。

> **本轮不修改 packaging**（§35）。GAP 记录如下，留待后续任务：
> GAP = AAR transitivity metadata 缺失 + 无 Maven publication + CLI base.apk classpath 要求未文档化为正式 bundle
> IMPACT = 第三方 consumer 若不用 Gradle project dependency（如跨 repo vendor AAR），需手动处理 5-AAR 集合 + classpath
> RECOMMENDED_NEXT_TASK = AT.ARTIFACT-BUNDLE-0（可选）：建立正式 AAR bundle / Maven publication + CLI embedding packaging 契约

---

## 17. Required / Optional Consumer Profiles（§28）

从真实 dependency graph 导出（非强造）：

### LOCAL_ONLY_CONSUMER

```
required modules : :at-engine + :at-backend-local
optional modules : （无）
native libs      : libat_local_backend.so
host obligations : LocalPersistentBackend 注入 + ATEngine 构造 + （可选）LocalCliBootstrap 若要 shell 函数
unsupported      : 所有 ADB capability（one-shot/persistent/pairing/identity/WD/READY）
identity         : 不需要（Local 不依赖 ADB identity）
```

### ADB_DIRECT_CONSUMER（programmatic ADB，无 shell-visible CLI）

```
required modules : :at-engine + :at-backend-adb + :adb-engine
optional modules : （无）
native libs      : libadbd_engine.so
host obligations : AdbRuntime.initialize + identityDir（app-private, self-generated）
                   + AdbOneShotBackend/AdbPersistentBackend(placeholder) 注入 + ATEngine 构造
                   + connectAuto/ensureReady（ADB readiness）+ AdbConsumerBootstrap（首次配对）
unsupported      : Local PTY（无 :at-backend-local）；shell-visible adb CLI（无 :at-cli）
identity         : MANDATORY（app-private, self-generated, 经 AdbEngine.pair 创建）
```

### FULL_TERMINAL_ENVIRONMENT_PROFILE（reference = :at-cli-consumer-host）

> **命名规范化（N1/§7）**：原 `FULL_TERMINAL_CONSUMER` 改名为 **`FULL_TERMINAL_ENVIRONMENT_PROFILE`**。
> `FULL_TERMINAL` 描述的是**完整 terminal-environment capability profile**（Local + ADB + CLI 的
> 完整环境形态），**不是** §18 `FULL_CURRENT_AT_V3_CONSUMER_INTEGRATION` 的 normative
> completeness proof。两者不同，不得混用。

```
required modules : :at-engine + :at-backend-local + :at-backend-adb + :adb-engine + :at-cli
optional modules : （无）
native libs      : libat_local_backend.so + libadbd_engine.so + libatcli_env.so
host obligations : 全部 §12 construction recipe（Local + ADB + AdbRuntime + AdbConsumerBootstrap
                   + ATCliExecution impl + AdbCliBridgeServer + LocalCliBootstrap + env 注入）
unsupported      : （无 AT-side unsupported；Product UI / Model tool 是 consumer 产品层选择）
identity         : MANDATORY（app-private, self-generated）
```

**reference host 角色（§23 最终定义）**：

```
AT_CLI_CONSUMER_HOST_ROLE =
    REFERENCE_EMBEDDING_HOST        （证明 CLI embedding recipe 可行）
  + REFERENCE_TERMINAL_ENVIRONMENT  （当前最完整的 AT terminal-environment reference）
```

它验证：Local persistent real PTY / ADB one-shot / ADB persistent Remote PTY / shell-visible
adb CLI / Bootstrap / identity / AdbRuntime lifecycle / CLI bridge / Local → adb → remote shell
path。

**它不是 `FINAL_CONSUMER_CONFORMANCE_TEST_HOST`**（除非未来专门创建对应任务）。它是
**embedding reference**，**不是** normative conformance suite。

#### REFERENCE_HOST_CURRENT_WIRING（§6 显式 reference host matrix — 严格用层 1-5）

| CAPABILITY | AT AVAILABLE（层1-2） | REFERENCE HOST WIRED（层4） | REFERENCE HOST CALLABLE（层5） |
|---|---|---|---|
| Local one-shot | YES | **NO**（`localEngine().oneShot = null`，未 wire `LocalOneShotBackend`） | **NO** |
| Local persistent PTY | YES | YES | YES |
| ADB one-shot | YES | YES | YES |
| ADB persistent Remote PTY | YES | YES | YES |
| AT CLI（shell-visible adb） | YES | YES | YES |
| Bootstrap | YES | YES | YES |
| Identity（per-consumer） | YES | YES | YES |
| AdbRuntime lifecycle | YES | YES | YES |

**关键**：`AT AVAILABLE = YES` **不等于** `REFERENCE HOST WIRED = YES`。Local one-shot 在 AT
**可用**（层 1-2），但当前 reference host **没有 wire** `LocalOneShotBackend`（`oneShot = null`，
层 4 = NO）。**不得**把不存在的 Local one-shot wiring 写成 PASS，也**不得**因 reference host 没
wire 就反推「Local one-shot 不是 Full Integration requirement」（§21）。

**说明**：Local 与 ADB 是**正交** capability，consumer 按需组合。FULL_TERMINAL_ENVIRONMENT_PROFILE
是 reference consumer（`:at-cli-consumer-host`）的完整 terminal-environment 形态。Profile 划分与
真实 design 一致（consumer host 确实同时 wire Local persistent + ADB + CLI）。

---

## 18. Full Current AT V3 Consumer Integration Definition（§29 — normative）

```
FULL_CURRENT_AT_V3_CONSUMER_INTEGRATION =
  一个 consumer APK 满足以下 AT-side 条件（层 1-5 全 TRUE）：

  1.  Local one-shot      CALLABLE_BY_HOST   （ATEngine.execute 经 LocalOneShotBackend）
  2.  Local persistent    CALLABLE_BY_HOST   （ATEngine.openSession 经 LocalPersistentBackend → real PTY）
  3.  ADB one-shot        CALLABLE_BY_HOST   （ATEngine.execute 经 AdbOneShotBackend）
  4.  ADB persistent      CALLABLE_BY_HOST   （ATEngine.openSession 经 AdbPersistentBackend → remote PTY）
  5.  AT CLI embedded     WIRED_IN_COMPOSITION（AdbCliBridgeServer 启动 + ATCliExecution impl + LocalCliBootstrap provision）
  6.  Bootstrap callable  CALLABLE_BY_HOST   （AdbConsumerBootstrap.bootstrapState/discoverPairing/pair）
  7.  identity owned correctly  （app-private identityDir, self-generated, 非 copy）
  8.  AdbRuntime lifecycle correctly owned（host 经 AdbRuntime.initialize/ensureReady/connectAuto；
                                          UNIQUE lifecycle owner；无第二 recovery owner）

  即：consumer 能 callable Local + ADB 的 one-shot + persistent（4 个 execution mode），
  嵌入 shell-visible adb CLI，能用 bootstrap 完成首次配对，正确拥有 per-consumer identity，
  正确把 ADB lifecycle 交给唯一的 AdbRuntime。
```

**明确不属于此 AT integration 定义**（consumer 产品层 / 未来，非 AT completeness）：

```
PRODUCT_UI_PART_OF_FULL_INTEGRATION          = NO（:app 的 Human UI 是 product 选择，非 consumer 必需）
MODEL_TOOL_PART_OF_FULL_INTEGRATION          = NO（暴露给 AI 模型是 AG 产品策略，非 AT 定义）
AG_SESSION_PERSISTENCE_PART_OF_FULL_INTEGRATION = NO（agent session persistence 是 AG 未来能力，非 AT）
EMULATOR_PART_OF_CURRENT_INTEGRATION         = NO（NOT_IMPLEMENTED / DEFERRED）
SCREENSTATE_PART_OF_CURRENT_INTEGRATION      = NO（NOT_IMPLEMENTED / DEFERRED）
```

**关键**：不把 consumer 上层产品功能（UI / model tool / agent session）混进 AT integration
completeness。AT integration = 层 1-5（capability 可被 consumer 正确嵌入 + 调用）；层 6-7
（product expose / model visible）是 consumer 产品层。

**reference host 与 normative definition 的关系（N1/§22，最终形式）**：

`FULL_CURRENT_AT_V3_CONSUMER_INTEGRATION` 是 **NORMATIVE CAPABILITY COMPLETENESS DEFINITION**
（consumer 自己在层 3-5 完整消费当前 AT 提供的 current capability set 的标准）。它**保留**全部
8 条，**尤其保留** `Local one-shot CALLABLE_BY_HOST`——因为 Local one-shot 是 AT 已提供的
current capability（层 1-2 = AVAILABLE）。**不得**为了让 reference host 对得上定义而删除
Local one-shot requirement（本轮修的是 reference implementation wording，不是 definition）。

```
REFERENCE_HOST_FULL_CURRENT_CONFORMANCE = NOT_EVALUATED_AS_FULL_CURRENT_CONFORMANCE
```

即：`:at-cli-consumer-host` 是 **embedding reference**（REFERENCE_TERMINAL_ENVIRONMENT），
**不是** normative conformance suite。它当前 `localEngine().oneShot = null`（Local one-shot
未 wire），所以**不能**被读成「reference host 已逐项满足 Full Current Integration」。
definition 与 reference implementation 是两回事：definition 要求 8 条（含 Local one-shot），
reference host 实现了其中 7 条（Local one-shot 除外）。**不得再暗示 reference host = YES conformance。**

---

## 19. Known Gaps（CURRENT GAP — 不含未来 Emulator 的产品功能）

| Gap | 状态 | 说明 |
|---|---|---|
| Terminal Emulator | NOT_IMPLEMENTED / DEFERRED | ANSI 解析 / screen grid / wrap / erase / alt-screen / scroll 不存在 |
| ScreenState | NOT_IMPLEMENTED / DEFERRED | cursor / terminal mode / 权威 screen 状态不存在 |
| Screen Snapshot | NOT_IMPLEMENTED / DEFERRED | 不存在 |
| cursor semantic model | NOT_IMPLEMENTED / DEFERRED | 不存在 |
| TUI semantic model | NOT_IMPLEMENTED / DEFERRED | 不存在 |
| renderer | NOT_AT（Human UI 域，非 AT Engine） | Android renderer 属于 Product / Human UI |
| Human-Agent shared session contract | DEFERRED | 技术基础存在（SharedSessionHandle/DebugBridge/CLI bridge），产品/架构决策未定 |
| `adb pair <code>` shorthand | DEFERRED | 只实现标准 `adb pair <host>:<port> <code>` |
| MULTIPLE endpoint Product UI chooser | DEFERRED | 代码保证不 silent pick（返回 MULTIPLE_ENDPOINTS），UI 选择器未做 |
| AAR transitivity / Maven publication | NOT_CONFIGURED（packaging gap） | 见 §16 GAP；**本轮不修**（§35） |

**不得**把 raw PTY / raw bytes 描述成 Emulator / ScreenState / cursor / TUI / renderer 能力。
raw byte-stream PTY（Local/Remote）**已验证存在**，但它**不是** screen-oriented semantics。

---

## 20. AG Reconciliation Input（§45 — 纯 AT-side checklist）

**EXPECTED CURRENT AT CONSUMER CAPABILITY**（AT 侧标准答案；下一轮 AG 自己填
PACKAGED / WIRED / CALLABLE / PRODUCT-EXPOSED / MODEL-VISIBLE 列）。

> **两个独立信息维度（§21）**：**AT AVAILABLE**（层 1-2，AT 是否提供）与
> **REFERENCE HOST CURRENT WIRING**（reference consumer 当前是否 wire，层 4）**正交**。
> 不得因 reference host 某项 WIRED=NO 就反推该项「不是 Full Integration requirement」，
> 也**不得**因 AT AVAILABLE=YES 就假设 reference host 已 wire。

| CAPABILITY | AT AVAILABLE（层1-2） | REFERENCE HOST WIRED（层4, :at-cli-consumer-host） | AG 填：PACKAGED | AG 填：WIRED | AG 填：CALLABLE | AG 填：PRODUCT-EXPOSED | AG 填：MODEL-VISIBLE |
|---|---|---|---|---|---|---|---|
| Local one-shot | AVAILABLE（ATEngine.execute → LocalOneShotBackend） | **NO**（`localEngine().oneShot = null`） | ? | ? | ? | ? | ? |
| Local persistent PTY | AVAILABLE（ATEngine.openSession → real PTY） | YES | ? | ? | ? | ? | ? |
| ADB one-shot | AVAILABLE（ATEngine.execute → AdbOneShotBackend） | YES | ? | ? | ? | ? | ? |
| ADB persistent (Remote PTY) | AVAILABLE（ATEngine.openSession → shell,v2,pty:） | YES | ? | ? | ? | ? | ? |
| AT CLI（shell-visible adb） | AVAILABLE（:at-cli bridge + LocalCliBootstrap；需 host wiring） | YES | ? | ? | ? | ? | ? |
| Bootstrap（pairing/discovery/state） | AVAILABLE（AdbConsumerBootstrap facade） | YES | ? | ? | ? | ? | ? |
| Identity（per-consumer） | AVAILABLE（app-private, self-generated；consumer 必须拥有） | YES | ? | ? | ? | ? | ? |
| Lifecycle（AdbRuntime UNIQUE owner） | AVAILABLE（AdbRuntime.initialize/ensureReady/connectAuto；consumer 必须正确交 owner） | YES | ? | ? | ? | ? | ? |
| Recovery（operation-driven bounded） | AVAILABLE（AdbRuntime.onOperationResult；consumer 不需主动管） | YES（AdbRuntime 内置） | ? | ? | ? | ? | ? |
| Local PTY raw I/O / resize / Ctrl-C | AVAILABLE（real PTY semantics） | YES（Local persistent 承载） | ? | ? | ? | ? | ? |
| ADB stdin / Ctrl-C / resize / multi-session | AVAILABLE（shell-v2 remote PTY） | YES（ADB persistent 承载） | ? | ? | ? | ? | ? |
| connection reuse | AVAILABLE（AdbRuntime 持 1 连接，backend 复用） | YES | ? | ? | ? | ? | ? |
| transport-loss semantics | AVAILABLE（TRANSPORT_LOST/RECOVERING/RECOVERY_FAILED） | YES（AdbRuntime 内置） | ? | ? | ? | ? | ? |
| `adb pair <host>:<port> <code>` | AVAILABLE（T_PAIR → bootstrap.pair） | YES | ? | ? | ? | ? | ? |
| `adb pair <code>` shorthand | **NOT AVAILABLE**（DEFERRED） | — | — | — | — | — |
| Terminal Emulator | **NOT AVAILABLE**（DEFERRED） | — | — | — | — | — |
| ScreenState | **NOT AVAILABLE**（DEFERRED） | — | — | — | — | — |
| Screen Snapshot | **NOT AVAILABLE**（DEFERRED） | — | — | — | — | — |
| cursor / TUI / renderer | **NOT AVAILABLE**（DEFERRED / Human UI 域） | — | — | — | — | — |
| Human-Agent shared session | **NOT AVAILABLE as contract**（DEFERRED） | — | — | — | — | — |

**给 AG 的纪律**（防止历史冲突 A-E 复发）：

1. AT 侧「AVAILABLE」**只**表示层 1-2（capability 存在 + 可打包）。它**不**表示 AG 已经
   PACKAGED/WIRED/CALLABLE（层 3-5，AG 的 wiring 责任），更**不**表示 PRODUCT-EXPOSED /
   MODEL-VISIBLE（层 6-7，AG 产品策略）。
2. 若 AG 某 capability 是 `MODEL_VISIBLE = NO`，**不得**反推「AT 没集成该 capability」。
   必须逐层核对：是 AT 没有（层 1，本表已给答案），还是 AG 没接（层 3-5，AG wiring gap），
   还是 AG 产品层选择不暴露（层 6-7，AG 策略）。
3. 「direct ADB backend」与「shell-visible adb」都 AVAILABLE 且**都合法**；二者是同一 ADB
   capability 的两个 frontend，**不互斥**（§8）。AG 不应因「模型只暴露一个 shell tool」就
   删除 direct ADB integration。
4. 「一个 consumer composition」可合法有 2 个 ATEngine object（local + adb），共享 1 个
   AdbRuntime 单例（§5）。AG 不应把「one composition」误等价为「one ATEngine」。
5. AdbRuntime 是**进程级 singleton**（每 consumer 进程 1 个）；AdbConsumerBootstrap 是
   **纯 facade**（可多个，共享唯一 AdbRuntime）（§5）。AG 不应创建第二 AdbRuntime / recovery owner。
6. 首次配对后，正常 ADB readiness 走 **AdbRuntime.ensureReady**（不是 AdbConsumerBootstrap）（§9）。
   AG 不应把 bootstrap 当正常 readiness owner。
7. identity 必须 **per-consumer app-private + self-generated**，**不得** copy AndroidTerminal
   或其它 App 的 adbkey（§11）。
8. 「能力未暴露给模型」≠「能力未集成」。本轮已冻结此区分（§4）。
