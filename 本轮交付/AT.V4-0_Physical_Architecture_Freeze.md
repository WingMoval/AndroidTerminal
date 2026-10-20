# AT.V4-0 — V4 Physical Architecture Freeze

> **Date**: 2026-10-21
> **Status**: V4-0 baseline established / physical architecture frozen
> **Baseline**: `75d5832`（AT.V3-TO-V4-BASELINE-V1）

---

## 1. Why V4 Exists

V3 **canonical architecture** 已验证完成（AT Engine / ADB Engine / Backend 的 responsibilities / ownership / semantics 全部 PASS），但 **post-V3 capability placement** 存在 drift：

1. **POST_V3_CAPABILITY_PLACEMENT_DRIFT**：Consumer 可以直接 import `AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / backend classes，没有 compile-time boundary。
2. **CONSUMER_BOUNDARY_NOT_FULLY_PHYSICALIZED**：Consumer 自己构造 `ATEngine(oneShot = AdbOneShotBackend(...), persistent = AdbPersistentBackend(...))`，需要理解 ADB lifecycle / backend construction。
3. **COMMON_COMPOSITION_DUPLICATION**：`AppRuntime` 与 `AtCliConsumerHost` ~70% 逻辑重复（`AdbRuntime.initialize` / `identityDir` / `localEngine()` / `adbEngine()` / `connectAuto()` / `consumerBootstrap()`）。

**V4 目标**：在不改变 V3 canonical architecture（AT Engine / ADB Engine / Backend 的 responsibilities / ownership / semantics）的前提下，物理化 consumer boundary + 消除 composition duplication + 建立正确的 dependency graph。

**V4 不重新讨论**：
- AT Engine / ADB Engine / Backend 的 canonical responsibilities
- AdbRuntime UNIQUE lifecycle ownership
- AdbRecoveryCoordinator UNIQUE recovery ownership
- AdbEngine.pair pairing primitive
- Local / ADB / CLI semantics
- PTY semantics
- Pairing semantics
- Recovery semantics

---

## 2. What V4 Does NOT Change

V4 **不改变**以下 V3 已冻结的语义：

- Execution semantics（one-shot / persistent / Local / ADB / CLI）
- ADB protocol（TLS 1.3 / shell-v2 / CNXN / STLS）
- PTY semantics（Local real PTY / ADB remote PTY）
- Pairing semantics（`AdbEngine.pair` / `AdbConsumerBootstrap.pair`）
- Recovery semantics（`AdbRecoveryCoordinator` bounded recovery）
- CLI command semantics（`adb shell <cmd>` / `adb shell` interactive / `adb pair`）
- Identity semantics（PER_CONSUMER_APP_PRIVATE / SELF_GENERATED / NO_KEY_COPY）
- Ownership（AdbRuntime UNIQUE lifecycle owner / AdbRecoveryCoordinator UNIQUE recovery owner / AdbEngine.pair pairing primitive）

---

## 3. V3 Source Baseline

```
V3_SOURCE_BASELINE = 75d5832
V3_BASELINE_TAG    = AT.V3-TO-V4-BASELINE-V1
V3_REPO            = D:\codebank\AndCode\AndroidTerminal-V3
```

---

## 4. V3 Known-Good Capabilities

见 `AT_V3_TO_V4_HANDOFF.md` §3（27 项 inherited verified capabilities）。

---

## 5. V3 Physical Debt

见 `AT_V3_TO_V4_HANDOFF.md` §6（5 项 known V3 physical debt）。

---

## 6. Previous Candidate A Contradictions

`AT.CONSUMER-PHYSICAL-0` 的 Candidate A 设计存在两个结构矛盾：

### 矛盾 A：Backend SPI cycle

旧 proposed design 同时写：
- `:at-engine` → `:at-backend-local` / `:at-backend-adb`（implementation 依赖）
- Backend implements `ATOneShotBackend` / `ATPersistentBackend` owned by `:at-engine`（SPI 依赖）

这会形成潜在 `:at-engine` ↔ `:at-backend-*` cycle。

### 矛盾 B：Factory visibility

旧 proposed design 同时写：
- Normal Consumer 禁止依赖 `:at-engine`
- `ATEngineFactory` 位于 `:at-engine`
- 要求 Consumer 调用 Factory

这两个条件不能同时成立。

---

## 7. Corrected Candidates

### V4 Candidate A：API + Engine(SPI) + Backends + Composition

```
:at-engine-api (public consumer contract)
:at-engine (engine implementation + backend SPI)
:at-backend-local (depends on :at-engine, implements SPI)
:at-backend-adb (depends on :at-engine + :adb-engine, implements SPI)
:at-composition-android (depends on :at-engine + :at-backend-local + :at-backend-adb)
```

**问题**：`:at-engine` 同时包含 engine implementation + backend SPI，如果 `:at-engine` 依赖 concrete backend（用于 wiring），会形成 cycle。

### V4 Candidate B：API + SPI + Engine + Backends + Composition

```
:at-engine-api (public consumer contract)
:at-engine-spi (backend implementation contract)
:at-engine (depends on :at-engine-api + :at-engine-spi, engine implementation)
:at-backend-local (depends on :at-engine-api + :at-engine-spi, implements SPI)
:at-backend-adb (depends on :at-engine-api + :at-engine-spi + :adb-engine, implements SPI)
:at-composition-android (depends on :at-engine-api + :at-engine + :at-backend-local + :at-backend-adb)
```

**优点**：
- **NO_DEPENDENCY_CYCLE = YES**（`:at-engine` 不依赖 concrete backend，backend 依赖 `:at-engine-spi`，composition 依赖所有）
- **ENGINE_BACKEND_DECOUPLING = YES**（engine implementation 与 backend implementation 完全不互相依赖）
- **NORMAL_CONSUMER_HIDING = YES**（Consumer 只依赖 `:at-engine-api` + `:at-composition-android`，不依赖 `:at-engine` / `:at-backend-*` / `:adb-engine`）

### V4 Candidate C：（未提出，Candidate B 已满足所有要求）

---

## 8. Candidate Comparison

| 维度 | Candidate A | Candidate B |
|---|---|---|
| NO_DEPENDENCY_CYCLE | RISK（`:at-engine` 可能依赖 concrete backend） | YES（`:at-engine` 不依赖 concrete backend） |
| NORMAL_CONSUMER_HIDING | YES | YES |
| ENGINE_BACKEND_DECOUPLING | PARTIAL（`:at-engine` 包含 SPI + implementation） | YES（`:at-engine-spi` 独立） |
| FUTURE_SSH_BACKEND | YES | YES |
| TESTABILITY | MEDIUM | HIGH |
| MODULE_COUNT | 5 | 6 |
| MIGRATION_COST | MEDIUM | MEDIUM |
| PUBLIC_SURFACE | CLEAR | CLEAR |
| SPI_VISIBILITY | PARTIAL（SPI 在 `:at-engine`，Consumer 可能看到） | CLEAR（SPI 在 `:at-engine-spi`，Consumer 不需要依赖） |
| PLUGIN_EXTENSIBILITY | YES | YES |
| CLI_INTEGRATION | YES | YES |
| ANDROID_COMPOSITION | YES | YES |
| AG_INTEGRATION | YES | YES |
| BINARY_PACKAGING | NEUTRAL | NEUTRAL |
| LONG_TERM_MAINTAINABILITY | MEDIUM | HIGH |

**SELECTED_DAG_CANDIDATE = B**

**理由**：
- **NO_DEPENDENCY_CYCLE = YES**（消除矛盾 A）
- **ENGINE_BACKEND_DECOUPLING = YES**（engine implementation 与 backend implementation 完全不互相依赖）
- **SPI_VISIBILITY = CLEAR**（`:at-engine-spi` 独立 module，Consumer 不需要依赖）
- **LONG_TERM_MAINTAINABILITY = HIGH**（clear separation between API / SPI / engine / backends / composition）

---

## 9. Selected Canonical DAG

```
:at-engine-api
  └── (no project dependencies)

:at-engine-spi
  └── implementation(:at-engine-api)

:at-engine
  ├── implementation(:at-engine-api)
  └── implementation(:at-engine-spi)

:at-backend-local
  ├── implementation(:at-engine-api)
  └── implementation(:at-engine-spi)

:at-backend-adb
  ├── implementation(:at-engine-api)
  ├── implementation(:at-engine-spi)
  └── implementation(:adb-engine)

:adb-engine
  └── (no project dependencies)

:at-composition-android
  ├── implementation(:at-engine-api)
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  └── implementation(:at-backend-adb)

:at-cli
  ├── implementation(:at-engine-api)
  └── implementation(:at-composition-android)

:app
  ├── implementation(:at-engine-api)
  ├── implementation(:at-composition-android)
  └── implementation(:at-cli)  [optional]

:at-cli-consumer-host
  ├── implementation(:at-engine-api)
  ├── implementation(:at-composition-android)
  └── implementation(:at-cli)  [optional]
```

**NO CYCLES** ✓

---

## 10. Module Responsibilities

| Module | Responsibility | Layer |
|---|---|---|
| `:at-engine-api` | Public consumer contract（execution request/result / session contract） | Layer-2 |
| `:at-engine-spi` | Backend SPI（**contracts/interfaces ONLY**：`ATOneShotBackend` / `ATPersistentBackend` / future backend/provider SPI） | Layer-3/4 implementation contract |
| `:at-engine` | Engine implementation（execution orchestration / session implementation，**不依赖 concrete backends**） | Layer-3 |
| `:at-backend-local` | Local backend **concrete implementations**（`LocalOneShotBackend` / `LocalPersistentBackend` / `LocalPtySession`，实现 `:at-engine-spi`） | Layer-4 |
| `:at-backend-adb` | ADB backend **concrete implementations**（`AdbOneShotBackend` / `AdbPersistentBackend`，实现 `:at-engine-spi`） | Layer-4 |
| `:adb-engine` | ADB provider engine（`AdbEngine` / `AdbRuntime` / `AdbConsumerBootstrap` / `AdbRecoveryCoordinator`） | Layer-5 |
| `:at-composition-android` | Android composition root（Context / identity / AdbRuntime init / backend construction / engine construction） | Assembly |
| `:at-cli` | CLI frontend（shell-visible `adb` / same-UID UDS bridge / Local shell `adb` function bootstrap） | Frontend |
| `:app` | AT Product App（Human UI / Product logic） | Layer-1 |
| `:at-cli-consumer-host` | Reference consumer（TEST / VALIDATION ONLY） | Layer-1 |

**关键区分**：
- `:at-engine-spi` = **contracts/interfaces ONLY**（不包含 concrete implementations）
- `:at-backend-local` / `:at-backend-adb` = **concrete implementations**（实现 `:at-engine-spi` 的 interfaces）

---

## 11. Public API Boundary

**`:at-engine-api`** 作为 Layer-2 public consumer contract。

**普通 Consumer 只应该看到**（V3 已验证的 existing public contract）：
- Execution request / result（`ATExecutionRequest` / `ATExecutionResult` / `ATExecutionOutcome`）
- Terminal session contract（`ATTerminalSession` / `ATSessionEvent` / `ATSessionState`）
- Execution target / intention（`Launch` / `ATOpenSessionResult`）

**PROVISIONAL / DESIGN_REQUIRED**（V3 无对应已验证 public contract，V4-1 不实现）：
- Readiness / status（`ATStatus`）
- Structured user-action-required
- Bootstrap / pairing semantic result（`ATPairingResult` / `ATPairingDiscovery`）
- Diagnostic semantic projection（`ATDiagnostic`）
- `ATProviderControl` / prepare/status/control-plane 新接口

**不得看到**：
- `AdbRuntime` / `AdbEngine` / `AdbConsumerBootstrap`
- `AdbConnectionRequest` / `AdbEndpoint` / `AdbInteractiveStream`
- `LocalPtySession` / `AdbOneShotBackend` / `AdbPersistentBackend` / `LocalPersistentBackend`
- `identityDir` / `Context` in runtime methods

---

## 12. Backend SPI Boundary

**`:at-engine-spi`** 作为 backend implementation contract。

**包含**：
- `ATOneShotBackend`（one-shot execution backend SPI）
- `ATPersistentBackend`（persistent terminal backend SPI）
- Future provider control SPI（`ATProviderControl` / `ATProviderCapability`）

**物理位置**：`:at-engine-spi`（独立 module）。

**证明**：
1. Engine implementation（`:at-engine`）能依赖 SPI ✓（`implementation(:at-engine-spi)`）
2. Backend implementations（`:at-backend-local` / `:at-backend-adb`）能实现 SPI ✓（`implementation(:at-engine-spi)`）
3. Engine 不依赖 concrete backend ✓（`:at-engine` 不依赖 `:at-backend-local` / `:at-backend-adb`）
4. No cycle ✓（DAG 无环）
5. 普通 Consumer 不需要依赖 SPI module ✓（Consumer 只依赖 `:at-engine-api` + `:at-composition-android`）

---

## 13. Engine Implementation Boundary

**`:at-engine`** 作为 engine implementation。

**职责**：
- Execution orchestration（`execute()` / `openSession()`）
- Session implementation（`ATTerminalSession` 的具体实现）
- Backend wiring（通过 `:at-composition-android` 注入 backend instances）

**不得依赖**：
- Concrete backend（`:at-backend-local` / `:at-backend-adb`）

---

## 14. Local Backend Boundary

**`:at-backend-local`** 作为 Local backend implementation。

**职责**：
- `LocalOneShotBackend`（one-shot execution via `/system/bin/sh -c`）
- `LocalPersistentBackend`（persistent terminal via `LocalPtySession` → `/system/bin/sh -i`）
- `LocalPtySession`（real Local PTY primitive）

**依赖**：
- `:at-engine-api`（public contract types）
- `:at-engine-spi`（backend SPI）

**不得依赖**：
- `:adb-engine`（Local 不依赖 ADB）

---

## 15. ADB Backend Boundary

**`:at-backend-adb`** 作为 ADB backend implementation。

**职责**：
- `AdbOneShotBackend`（one-shot execution via `AdbEngine.executeShell()`）
- `AdbPersistentBackend`（persistent terminal via `AdbEngine.openInteractive()`）

**依赖**：
- `:at-engine-api`（public contract types）
- `:at-engine-spi`（backend SPI）
- `:adb-engine`（ADB provider engine）

**不得依赖**：
- `:at-backend-local`（ADB 不依赖 Local）

---

## 16. ADB Engine Boundary

**`:adb-engine`** 作为 ADB provider engine。

**职责**：
- `AdbEngine`（ADB protocol / TLS / CNXN / shell-v2 / stream semantics）
- `AdbRuntime`（UNIQUE ADB lifecycle owner）
- `AdbConsumerBootstrap`（first-use bootstrap facade）
- `AdbRecoveryCoordinator`（UNIQUE recovery owner）

**依赖**：
- (none)

**不得依赖**：
- `:at-engine-api` / `:at-engine-spi` / `:at-engine` / `:at-backend-*`（ADB Engine 不依赖 AT）

---

## 17. Android Composition Boundary

**`:at-composition-android`** 作为 Android composition root。

**职责**：
- 接收 Android Context
- 决定 app-private identity location
- 初始化 AdbRuntime
- 构造 provider adapters
- 构造 Local backend
- 构造 ADB backend
- 构造 AT Engine implementation
- 返回 `:at-engine-api` contract

**不得拥有**：
- ADB protocol
- Recovery ownership
- Terminal execution semantics
- Product UI
- AG logic

---

## 18. CLI Boundary

**`:at-cli`** 保持 optional frontend。

**职责**：
- Shell-visible `adb`（`AtCliMain` / `AdbCliBridgeServer` / `LocalCliBootstrap`）

**不是**：
- Local executor
- ADB lifecycle owner
- Composition root

**依赖**：
- `:at-engine-api`（public contract types）
- `:at-composition-android`（composition root）

**不得直接依赖**：
- `:adb-engine` / `:at-backend-adb` / `:at-backend-local`

---

## 19. Product Boundary

**`:app`** 作为 AT Product App。

**依赖**：
- `:at-engine-api`（public contract types）
- `:at-composition-android`（composition root）
- `:at-cli`（optional frontend）

**不得直接依赖**：
- `:at-engine` / `:at-engine-spi` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine`

---

## 20. AG Boundary

- **AG Core** → NO concrete AT dependency
- **AG integration adapter** → `:at-engine-api` ONLY
- **AGA / Android Host Composition Root** → `:at-composition-android` → 构造 ATEngine → 注入 integration adapter

**AG integration 不需要**：Context / identityDir / AdbRuntime / backend classes。

---

## 21. Identity Boundary

**普通 Consumer 不应**：
- 在每次 `ensureReady` / `pair` / `connect` / backend construction 重复传 `identityDir`

**Identity 应当**：
- 在 Android composition time 根据 consumer app-private storage 绑定一次

**优先研究**：
- Consumer 甚至不需要提供 String identityDir，而由 composition layer 根据 Context 创建其 private identity location

**保持**：
- PER_CONSUMER_APP_PRIVATE
- SELF_GENERATED
- NO_KEY_COPY

---

## 22. Context Boundary

**Context 允许出现在**：
- Android composition creation（例如 `ATAndroid.create(context, config)`）

**构造完成后**：
- `ATEngine` / `ATTerminalSession` / `ATProviderControl` runtime methods 不再要求 Context

---

## 23. Readiness / Control-Plane Boundary

**普通 Consumer 不得再选择**：
- `ensureReady` vs `connectAuto`

**普通 Consumer 只表达**：
- `status` / `prepare` / `ensure usable`
- `pairing required`
- `user action required`
- `pair`
- `diagnostics`

**内部仍然**：
- `AdbRuntime.ensureReady` / `AdbRuntime.connectAuto` / `AdbConsumerBootstrap` / `AdbRecoveryCoordinator`
- Ownership 完全不变

---

## 24. Forbidden Dependency Rules

| Module | Forbidden Dependencies |
|---|---|
| `:app` | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| `:at-cli-consumer-host` | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| AG integration adapter | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| `:at-engine-api` | `android.content.Context`, `AdbRuntime`, `AdbEngine`, `AdbConsumerBootstrap`, backend classes |
| `:at-engine` | `:at-backend-local`, `:at-backend-adb` (no concrete backend dependency) |

---

## 25. Architecture Enforcement Plan

V4 后续必须建立：

1. **Gradle module boundary**（primary）
2. **Forbidden dependency rule**（Gradle dependency verification）
3. **Public signature leak test**（验证 `:at-engine-api` 不包含 forbidden types）
4. **Architecture dependency test**（验证 DAG 无环 + forbidden dependencies 不存在）

**未来必须失败**：
- `:app` → `:adb-engine`
- `:app` → `:at-backend-adb`
- AG integration → `:adb-engine`
- `:at-engine-api` → `android.content.Context`
- `:at-engine-api` → `AdbRuntime`

---

## 26. Migration Plan

| Phase | Scope | Status |
|---|---|---|
| V4-0 | Baseline established / physical architecture frozen | COMPLETE |
| V4-1 | Public API / SPI skeleton（创建 `:at-engine-api` + `:at-engine-spi`，移动 public types） | OPEN |
| V4-2 | Engine implementation + Android composition（创建 `:at-composition-android`，实现 `ATAndroid.create()`） | OPEN |
| V4-3 | Local / ADB backend migration + injection（迁移 `LocalOneShotBackend` / `LocalPersistentBackend` / `AdbOneShotBackend` / `AdbPersistentBackend` 到 `:at-engine-spi`） | OPEN |
| V4-4 | CLI / Product App migration（`:at-cli` / `:app` 改依赖 `:at-engine-api` + `:at-composition-android`） | OPEN |
| V4-5 | Consumer Conformance + architecture enforcement（创建 `:at-consumer-conformance-host`，建立 Gradle dependency rules + architecture test） | OPEN |
| V4-6 | Device parity + V4 Consumer Freeze（真机全量 parity 验证，发布 V4 consumer handoff） | OPEN |

---

## 27. V4 Roadmap

见 `AT.V4_ROADMAP.md`。

---

## 28. Controller Decisions

1. **V4 = AUTHORIZED**（BREAKING_MODULE_REPARTITION + CONSUMER_BOUNDARY_PHYSICALIZATION + COMPOSITION_ROOT_REFOUNDATION + DEPENDENCY_GRAPH_CLEANUP）
2. **SELECTED_DAG_CANDIDATE = B**（API + SPI + Engine + Backends + Composition）
3. **NEW_MODULES_PLANNED = 3**（`:at-engine-api` / `:at-engine-spi` / `:at-composition-android`）
4. **V4-0 = COMPLETE**（baseline established / physical architecture frozen）
5. **V4-1 = OPEN**（public API / SPI skeleton）
6. **NEXT_OPEN_AUTHORIZED = NO**（等待 Controller Review）

---

## 29. Next Task

**AT.V4-1**：公共 API / SPI 骨架（创建 `:at-engine-api` + `:at-engine-spi` module，移动 public types）。

**NEXT_OPEN_AUTHORIZED = NO**（等待 Controller Review）。
