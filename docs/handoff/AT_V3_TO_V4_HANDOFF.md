# AT V3 → V4 Handoff

> **Date**: 2026-10-21
> **Status**: V4-0 baseline established

---

## 1. V3 Source Baseline

```
V3_SOURCE_BASELINE = 75d5832 (AT.V3: close consumer-boundary audits before V4)
V3_BASELINE_TAG    = AT.V3-TO-V4-BASELINE-V1
V3_REPO            = D:\codebank\AndCode\AndroidTerminal-V3
V3_MODE            = READ_ONLY KNOWN-GOOD REFERENCE
```

---

## 2. V4 Start Head

```
V4_START_HEAD = 75d5832
V4_REPO       = D:\codebank\AndCode\AndroidTerminal-V4
V4_WORKTREE   = CLEAN
```

---

## 3. Inherited Verified Capabilities

以下能力从 V3 继承，视为 V4 migration oracle：

| Capability | V3 Reference | V4 Status |
|---|---|---|
| One-shot execution | `ATEngine.execute()` → `ATOneShotBackend` | INHERITED |
| Persistent terminal | `ATEngine.openSession()` → `ATPersistentBackend` | INHERITED |
| Local backend | `LocalOneShotBackend` / `LocalPersistentBackend` | INHERITED |
| ADB backend | `AdbOneShotBackend` / `AdbPersistentBackend` | INHERITED |
| Local real PTY | `LocalPtySession` → `/system/bin/sh -i` | INHERITED |
| ADB remote PTY | `AdbPersistentBackend` → `shell,v2,pty:` | INHERITED |
| Raw byte I/O | `ATTerminalSession.write()` / `.read()` | INHERITED |
| Stdin / stdout / stderr | `ATSessionEvent.Data` / `ATExecutionResult.stdout` / `.stderr` | INHERITED |
| Exit code | `ATExecutionResult.exitCode` / `ATSessionEvent.Exited` | INHERITED |
| Ctrl-C | Local PTY `0x03` → SIGINT / ADB remote PTY Ctrl-C | INHERITED |
| Resize | `ATTerminalSession.resize()` / ADB remote PTY resize | INHERITED |
| CWD / ENV | Local PTY persistent cwd + env / ADB remote PTY cwd + env | INHERITED |
| Multi-session | `ATEngine.openSession()` 多次调用 | INHERITED |
| Natural exit | `ATSessionEvent.Exited` / `ATSessionEvent.Eof` | INHERITED |
| Explicit close | `ATTerminalSession.close()` | INHERITED |
| ADB connection reuse | `AdbRuntime` 持 1 连接，backend 复用 | INHERITED |
| Wireless ADB TLS 1.3 | `AdbEngine` → BoringSSL static | INHERITED |
| ADB shell-v2 | `AdbEngine.executeShell()` / `openInteractive()` | INHERITED |
| Pairing | `AdbEngine.pair()` / `AdbConsumerBootstrap.pair()` | INHERITED |
| Persistent per-consumer identity | `identityDir` (app-private, self-generated) | INHERITED |
| CLI `adb shell <cmd>` | `AtCliMain` → `T_ONESHOT` → `AdbOneShotBackend` | INHERITED |
| CLI `adb shell` interactive | `AtCliMain` → `T_INTERACTIVE` → `AdbPersistentBackend` | INHERITED |
| CLI `adb pair` | `AtCliMain` → `T_PAIR` → `AdbConsumerBootstrap.pair()` | INHERITED |
| Local → adb CLI → remote shell → return Local | `LocalCliBootstrap` → `AtCliMain` → bridge → ADB | INHERITED |
| AdbRuntime lifecycle ownership | `AdbRuntime` = UNIQUE ADB lifecycle owner | INHERITED |
| AdbRecoveryCoordinator recovery ownership | `AdbRecoveryCoordinator` = UNIQUE recovery owner | INHERITED |

---

## 4. Inherited Frozen Ownership

| Owner | Scope | V4 Status |
|---|---|---|
| `AdbEngine` | ADB protocol | FROZEN |
| `AdbRuntime` | UNIQUE ADB lifecycle owner | FROZEN |
| `AdbRuntime` + `AdbRecoveryCoordinator` | UNIQUE ADB recovery owner | FROZEN |
| `AdbEngine.pair` | Pairing primitive | FROZEN |
| `AdbConsumerBootstrap` | First-use bootstrap implementation | FROZEN |
| Local backend | Local PTY | FROZEN |
| ADB backend / ADB Engine | Remote ADB stream | FROZEN |
| AT Engine | Terminal execution semantics | FROZEN |
| AG | Intelligence (outside AT) | FROZEN |

---

## 5. Not Inherited as Final API

以下 V3 类型/接口 **不作为 V4 final API 继承**，需要重新设计物理位置：

| V3 Type | V3 Location | V4 Target | Reason |
|---|---|---|---|
| `ATEngine` | `:at-engine` | `:at-engine-api` | Public consumer contract |
| `ATExecutionRequest` / `ATExecutionResult` / `ATExecutionOutcome` | `:at-engine` | `:at-engine-api` | Public execution contract |
| `ATTerminalSession` / `ATSessionEvent` / `ATSessionState` | `:at-engine` | `:at-engine-api` | Public session contract |
| `ATOneShotBackend` / `ATPersistentBackend` | `:at-engine` | `:at-engine-spi` | Backend SPI (not public consumer contract) |
| `AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` | `:adb-engine` | `:adb-engine` (unchanged) | ADB provider internals (not public) |
| `LocalOneShotBackend` / `LocalPersistentBackend` | `:at-backend-local` | `:at-backend-local` (unchanged) | Local backend implementation (not public) |
| `AdbOneShotBackend` / `AdbPersistentBackend` | `:at-backend-adb` | `:at-backend-adb` (unchanged) | ADB backend implementation (not public) |
| `ATCliExecution` | `:at-cli` | `:at-cli` (simplified) | Host-provided SPI (simplified to `engine(): ATEngine`) |

---

## 6. Known V3 Physical Debt

| Debt | Description | V4 Resolution |
|---|---|---|
| `CURRENT_PHYSICAL_LAYER2 = PARTIAL` | Consumer 可以直接 import `AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / backend classes | `:at-engine-api` + `:at-engine-spi` + `:at-composition-android` 物理隔离 |
| `CONSUMER_CURRENTLY_CONSTRUCTS_BACKENDS = YES` | Consumer 自己构造 `ATEngine(oneShot = AdbOneShotBackend(...), persistent = AdbPersistentBackend(...))` | `:at-composition-android` 内部构造 backend，Consumer 只调用 `ATAndroid.create(context, config)` |
| `CONSUMER_CURRENTLY_PROPAGATES_IDENTITYDIR = YES` | Consumer 在多个地方传播 `identityDir`（`AdbRuntime.connectAuto` / `AdbConsumerBootstrap` / `AdbConnectionRequest` / CLI execution） | `:at-composition-android` composition time 绑定一次，Consumer 不传播 `identityDir` |
| `AT_ENGINE_MODULE_ROLE = C. API + implementation mixed module` | `:at-engine` 同时包含 public contract types + backend-facing abstractions | `:at-engine-api`（public contract）+ `:at-engine-spi`（backend SPI）+ `:at-engine`（implementation） |
| `APP_RUNTIME_VS_CONSUMER_HOST_DUPLICATION_MATRIX = HIGH` | `AppRuntime` 与 `AtCliConsumerHost` ~70% 逻辑重复 | `:at-composition-android` 统一 composition logic，`:app` / `:at-cli-consumer-host` 只保留 product-specific logic |

---

## 7. V4 Scope

**V4 范围**：PHYSICAL_ARCHITECTURE_ONLY

- Breaking module repartition
- Consumer boundary physicalization
- Composition root refoundation
- Dependency graph cleanup

**V4 不是**：
- Rewrite
- New execution engine
- New ADB implementation
- New PTY implementation
- New CLI semantics
- New pairing semantics
- New recovery semantics

---

## 8. V4 Prohibited Reinterpretations

以下 V3 结论 **不得在 V4 重新解释**：

1. **AG = Intelligence / AT = Execution**（不得改变 AG/AT 分工）
2. **AT Engine = reusable UI-free Android execution / terminal domain**（不得把 UI 混入 AT Engine）
3. **AT Engine Public Interface = Consumer / Product 的正式消费边界**（不得削弱 Layer-2 boundary）
4. **ADB Engine = independent protocol / transport provider**（不得把 ADB Engine 并入 AT Engine）
5. **AdbRuntime = UNIQUE ADB lifecycle owner**（不得创建第二 lifecycle owner）
6. **AdbRecoveryCoordinator = UNIQUE recovery owner**（不得创建第二 recovery owner）
7. **AdbConsumerBootstrap = first-use/bootstrap facade, NOT second lifecycle owner**（不得把 Bootstrap 变成 lifecycle owner）
8. **Pairing primitive owner = AdbEngine.pair**（不得改变 pairing ownership）
9. **Local Backend = independent of ADB readiness / identity / Wireless Debugging**（不得让 Local 依赖 ADB）
10. **shell-visible adb = additional ADB CLI frontend, NOT Local executor, NOT replacement for direct ADB**（不得改变 CLI/Local boundary）
11. **Direct ADB execution = VALID current capability**（不得 deprecate direct ADB）
12. **Local persistent = real Local PTY / ADB persistent = real Remote PTY**（不得改变 PTY semantics）
13. **Local / CLI split = CORRECT**（不得合并 Local 和 CLI）
14. **AT CLI JVM entry = `com.at.cli.AtCliMain`**（不得改变 CLI entrypoint）
15. **bare `id` = NOT AT CLI command**（不得把 bare command 加入 CLI surface）
16. **Terminal Emulator / ScreenState = future AT Engine capability**（不得提前实现 Emulator）
17. **final AT API freeze = NOT AUTHORIZED**（不得在 V4-0 宣布 final API）
18. **no external adb / no adb server 5037 / no second ADB lifecycle owner**（不得引入 external adb / 5037 / 第二 lifecycle owner）
