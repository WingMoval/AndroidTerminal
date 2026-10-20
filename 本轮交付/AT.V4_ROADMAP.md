# AndroidTerminal V4 — Roadmap

> **Date**: 2026-10-21
> **Status**: V4-0 COMPLETE / V4-1 COMPLETE / V4-2 COMPLETE

---

## V4 Phases

### AT.V4-0：基线建立与物理架构冻结

**Status**: COMPLETE

**Scope**:
- V3 transition closeout（docs-only commit + baseline tag）
- V4 repository creation（local git clone --no-hardlinks）
- V4 physical architecture design（Candidate A/B 比较 + 选择）
- V4 canonical DAG freeze
- V4 Master SSOT / Handoff / Architecture Freeze / Roadmap 创建

**Deliverables**:
- `AT.V4_MASTER_SSOT.md`
- `AT_V3_TO_V4_HANDOFF.md`
- `AT.V4-0_Physical_Architecture_Freeze.md`
- `AT.V4_ROADMAP.md`

**Gate**:
- V3 production source parity = PASS
- V4 worktree = CLEAN
- V4 HEAD = V3 transition HEAD
- V4 baseline tag = `AT.V3-TO-V4-BASELINE-V1`

---

### AT.V4-1：公共 API / SPI 骨架

**Status**: COMPLETE

**第一目标**：**PHYSICAL EXTRACTION OF EXISTING VERIFIED CONTRACTS**（只移动 V3 已存在的 verified contracts，不设计新 public semantic API）。

**Scope**:
- 创建 `:at-engine-api` module（pure contract / facade）
- 创建 `:at-engine-spi` module（backend SPI — **contracts/interfaces ONLY**）
- 移动 V3 已存在的 public execution/session contract 到 `:at-engine-api`：
  - `ATEngine`（facade）
  - `ATExecutionRequest` / `ATExecutionResult` / `ATExecutionOutcome`
  - `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`
  - `Launch` / `ATOpenSessionResult`
- 移动 V3 已存在的 backend SPI 到 `:at-engine-spi`：
  - `ATOneShotBackend`
  - `ATPersistentBackend`
- **不得在 V4-1 实现**（PROVISIONAL / DESIGN_REQUIRED）：
  - `ATProviderControl` / `ATStatus` / `ATPairingResult` / `ATPairingDiscovery` / `ATDiagnostic`
  - prepare/status/control-plane 新接口
- 更新 `settings.gradle.kts`（新增 `:at-engine-api` / `:at-engine-spi`）
- 更新 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli` 的 `build.gradle.kts`（依赖新 module）
- 验证：`:at-engine-api` 不包含 `:adb-engine` / `:at-backend-*` types
- 验证：`:at-engine-spi` 不包含 Android Context / ADB internals / concrete implementations

**Deliverables**:
- `:at-engine-api` module（Kotlin source + build.gradle.kts）
- `:at-engine-spi` module（Kotlin source + build.gradle.kts，**contracts/interfaces ONLY**）
- 更新后的 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli`（依赖新 module）
- 更新后的 `settings.gradle.kts`

**Gate**:
- `:at-engine-api` / `:at-engine-spi` 可以正常构建
- `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli` 可以正常构建（依赖新 module）
- `:at-engine-api` 不包含 forbidden types
- `:at-engine-spi` 不包含 Android Context / ADB internals / concrete implementations
- **V4-1 不引入新 public semantic API**（只物理迁移 existing verified contracts）

---

### AT.V4-2：Android Composition Root

**Status**: COMPLETE

**Scope**:
- 创建 `:at-composition-android` module（Android composition root）
- 实现 `ATAndroid.create(context, config)`（返回 `ATEngine`）
- `ATAndroid.create()` 内部：
  - 决定 app-private identity location（根据 Context）
  - 初始化 `AdbRuntime`（`AdbRuntime.initialize(context)`）
  - 构造 Local backend（`LocalOneShotBackend` / `LocalPersistentBackend`）
  - 构造 ADB backend（`AdbOneShotBackend` / `AdbPersistentBackend`）
  - 构造 AT Engine implementation（`ATEngine` with backends）
  - 返回 `ATEngine`（`:at-engine-api` contract）
- 更新 `:at-composition-android` 的 `build.gradle.kts`（依赖 `:at-engine-api` / `:at-engine` / `:at-backend-local` / `:at-backend-adb`）
- 验证：`:at-composition-android` 可以正常构建
- 验证：`ATAndroid.create()` 可以正确 wiring Local / ADB backends

**Deliverables**:
- `:at-composition-android` module（Kotlin source + build.gradle.kts）
- `ATAndroid` class（composition entrypoint）
- 更新后的 `settings.gradle.kts`

**Gate**:
- `:at-composition-android` 可以正常构建
- `ATAndroid.create()` 可以正确 wiring Local / ADB backends
- `ATAndroid.create()` 返回的 `ATEngine` 可以执行 one-shot / persistent 命令

---

### AT.V4-3：Local / ADB Backend 迁移 + Injection

**Status**: ABSORBED_BY_V4_1_AND_V4_2

**Note**: V4-3 的 scope 已在 V4-1 + V4-2 中完成：
- V4-1: Backend SPI extraction（`:at-engine-spi` 创建，backend modules 依赖 SPI）
- V4-2: Android composition root（`:at-composition-android` 创建，backend injection）

**RECOMMENDED_NEXT** = AT.V4-4

---

### AT.V4-4：CLI / Product App 迁移

**Status**: OPEN

**Scope**:
- `:at-cli` 改依赖 `:at-engine-api` + `:at-composition-android`（删除 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine` 直接依赖）
- `:app` 改依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli`（删除 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine` 直接依赖）
- `:at-cli-consumer-host` 改依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli`（删除 internal module 直接依赖）
- 简化 `ATCliExecution` SPI（`engine(): ATEngine`，不再要求 `adbEngine()` / `ensureReady()` / `adbIdentityDir()` / `consumerBootstrap()`）
- 验证：`:at-cli` / `:app` / `:at-cli-consumer-host` 可以正常构建
- 验证：CLI `adb shell` / `adb pair` 可以正常工作

**Deliverables**:
- 更新后的 `:at-cli` / `:app` / `:at-cli-consumer-host`（依赖 `:at-engine-api` + `:at-composition-android`）
- 简化后的 `ATCliExecution` SPI
- 更新后的 `:at-composition-android`（支持 CLI wiring）

**Gate**:
- `:at-cli` / `:app` / `:at-cli-consumer-host` 可以正常构建
- CLI `adb shell` / `adb pair` 可以正常工作
- `:app` / `:at-cli-consumer-host` 不直接依赖 `:at-engine` / `:at-backend-*` / `:adb-engine`

---

### AT.V4-5：Consumer Conformance + Architecture Enforcement

**Status**: OPEN

**Scope**:
- 创建 `:at-consumer-conformance-host` module（只依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli` optional）
- `:at-consumer-conformance-host` 覆盖：
  - Local one-shot / Local persistent
  - ADB one-shot / ADB persistent
  - Readiness / Bootstrap / Identity
  - CLI（shell-visible adb）
- 建立 Gradle dependency rules（验证 Consumer 不依赖 forbidden modules）
- 建立 architecture test（验证 `:at-engine-api` 不包含 forbidden types + DAG 无环）
- 验证：`:at-consumer-conformance-host` 可以正常构建 + 运行 Local / ADB / CLI 全路径

**Deliverables**:
- `:at-consumer-conformance-host` module（Kotlin source + build.gradle.kts）
- Gradle dependency rules（forbidden dependencies）
- Architecture test（public signature leak test + DAG cycle test）

**Gate**:
- `:at-consumer-conformance-host` 可以正常构建
- `:at-consumer-conformance-host` 只依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli`
- Gradle dependency rules 验证 `:app` / `:at-cli-consumer-host` / `:at-consumer-conformance-host` 不依赖 forbidden modules
- Architecture test 验证 `:at-engine-api` 不包含 forbidden types + DAG 无环

---

### AT.V4-6：Device Parity + V4 Consumer Freeze

**Status**: OPEN

**Scope**:
- 真机全量 parity 验证（V3 vs V4）：
  - Local one-shot / Local persistent
  - ADB one-shot / ADB persistent
  - CLI `adb shell` / `adb shell` interactive / `adb pair`
  - Pairing / Bootstrap / Identity
  - Recovery / Connection reuse
  - Multi-session / Resize / Ctrl-C
- 发布 V4 consumer handoff（`AT_V4_CONSUMER_HANDOFF.md`）
- 发布 V4 AAR bundle（`at-engine-api-debug.aar` + 其他 AAR）
- 冻结 V4 consumer boundary（`AT.V4-CONSUMER-FREEZE-V1` tag）

**Deliverables**:
- 真机 parity 验证报告
- `AT_V4_CONSUMER_HANDOFF.md`
- V4 AAR bundle
- `AT.V4-CONSUMER-FREEZE-V1` tag

**Gate**:
- 真机全量 parity = PASS（V3 vs V4 语义 delta = NONE）
- V4 consumer handoff 发布
- V4 AAR bundle 发布
- V4 consumer boundary frozen

---

## Post-V4 Phases（V4 Physical Closure 后）

| Phase | Scope | Status |
|---|---|---|
| `AT.EMULATOR-0` | Terminal Emulator / ScreenState（AT Engine domain） | DEFERRED（直到 V4 physical closure 完成） |
| `AT.ARTIFACT-BUNDLE-0` | Maven publication / AAR bundle redesign | DEFERRED |
| `AG.INTEGRATION-0` | AG integration adapter（只依赖 `:at-engine-api`） | DEFERRED |
| `AT.CONTRACT-1` | AT Contract freeze（final API） | DEFERRED（AT Contract 仍 DRAFT） |

---

## Migration Principle

**COPY / MOVE VERIFIED IMPLEMENTATION**，NOT REIMPLEMENT FROM MEMORY。

每一个 capability 迁移都必须：

```
V3 source reference
  → V4 implementation
  → static parity
  → build
  → targeted test
  → eventual device parity
```

**禁止**"顺便优化算法"。

---

## Parity Oracle

V3 是 V4 migration oracle。

所有 V4 functional task 必须能回答：

```
V3 reference path = ...
V4 path = ...
semantic delta = NONE / EXPLICIT
```

如果 `semantic delta != NONE`，必须单独授权。
