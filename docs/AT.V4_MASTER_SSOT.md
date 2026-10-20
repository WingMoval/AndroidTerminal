# AndroidTerminal V4 — Master SSOT

> **Version**: 4.0.0-bootstrap
> **Date**: 2026-10-21
> **Status**: V4-0 baseline established / physical architecture frozen

---

## A. V4 Mission

V4 的首要目标不是增加能力，而是让以下架构在 **Gradle module / package visibility / dependency direction / composition ownership / compile-time enforcement** 层面真正成立：

```
Consumer / Product
        ↓
AT Engine Public API
        ↓
AT Engine Implementation
        ↓
Backend SPI
        ↓
Concrete Backend
        ↓
Execution Provider
```

**V4 原因**：BREAKING_MODULE_REPARTITION + CONSUMER_BOUNDARY_PHYSICALIZATION + COMPOSITION_ROOT_REFOUNDATION + DEPENDENCY_GRAPH_CLEANUP。

**V4 不是**：rewrite / new execution engine / new ADB implementation / new PTY implementation。

**V4 是**：PHYSICAL ARCHITECTURE GENERATION。

---

## B. V3 Inherited Capabilities

以下能力全部视为 V4 migration oracle，不得重新设计：

- ONE_SHOT_EXECUTION / PERSISTENT_TERMINAL
- LOCAL_BACKEND / ADB_BACKEND
- LOCAL_REAL_PTY / ADB_REMOTE_PTY
- RAW_BYTE_IO / STDIN / STDOUT / STDERR / EXIT_CODE
- CTRL_C / RESIZE / CWD / ENV
- MULTI_SESSION / NATURAL_EXIT / EXPLICIT_CLOSE
- ADB_CONNECTION_REUSE
- WIRELESS_ADB_TLS_1_3 / ADB shell-v2
- PAIRING / PERSISTENT_PER_CONSUMER_IDENTITY
- CLI `adb shell <cmd>` / `adb shell` interactive / `adb pair <host>:<port> <code>`
- LOCAL → adb CLI → remote shell → return Local
- AdbRuntime lifecycle ownership / AdbRecoveryCoordinator recovery ownership

---

## C. Frozen Ownership

以下 ownership 在 V4 不允许改变：

| Owner | Scope |
|---|---|
| `AdbEngine` | ADB protocol |
| `AdbRuntime` | UNIQUE ADB lifecycle owner |
| `AdbRuntime` + `AdbRecoveryCoordinator` | UNIQUE ADB recovery owner |
| `AdbEngine.pair` | Pairing primitive |
| `AdbConsumerBootstrap` | First-use bootstrap implementation |
| Local backend | Local PTY |
| ADB backend / ADB Engine | Remote ADB stream |
| AT Engine | Terminal execution semantics |
| AG | Intelligence (outside AT) |

---

## D. V4 Canonical Layers

```
Layer 1: Consumer / Product Shell
  ├── :app (AT Product App)
  ├── :at-cli-consumer-host (reference consumer)
  └── future AG integration adapter

Layer 2: AT Engine Public API
  └── :at-engine-api (public consumer contract)

Layer 3: AT Engine Implementation
  └── :at-engine (engine implementation + backend SPI)

Layer 4: Backend Adapter
  ├── :at-backend-local (Local backend)
  └── :at-backend-adb (ADB backend)

Layer 5: Execution Provider
  ├── Local PTY (/system/bin/sh -i)
  └── ADB Engine → adbd (:adb-engine)

Android Composition Layer (assembly root):
  └── :at-composition-android (Context / identity / AdbRuntime init / backend construction)
```

---

## E. V4 Physical Module DAG

**Selected Candidate: B（API + SPI + Engine + Backends + Composition）**

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

:at-composition-android
  ├── implementation(:at-engine-api)
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  └── implementation(:at-backend-adb)

:at-cli
  ├── implementation(:at-engine-api)
  └── implementation(:at-composition-android)  [optional frontend]

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

## F. Allowed Dependency Rules

| Module | Allowed Dependencies |
|---|---|
| `:at-engine-api` | (none) |
| `:at-engine-spi` | `:at-engine-api` |
| `:at-engine` | `:at-engine-api`, `:at-engine-spi` |
| `:at-backend-local` | `:at-engine-api`, `:at-engine-spi` |
| `:at-backend-adb` | `:at-engine-api`, `:at-engine-spi`, `:adb-engine` |
| `:adb-engine` | (none) |
| `:at-composition-android` | `:at-engine-api`, `:at-engine`, `:at-backend-local`, `:at-backend-adb` |
| `:at-cli` | `:at-engine-api`, `:at-composition-android` |
| `:app` | `:at-engine-api`, `:at-composition-android`, `:at-cli` (optional) |
| `:at-cli-consumer-host` | `:at-engine-api`, `:at-composition-android`, `:at-cli` (optional) |

---

## G. Forbidden Dependency Rules

| Module | Forbidden Dependencies |
|---|---|
| `:app` | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| `:at-cli-consumer-host` | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| AG integration adapter | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| `:at-engine-api` | `android.content.Context`, `AdbRuntime`, `AdbEngine`, `AdbConsumerBootstrap`, backend classes |
| `:at-engine` | `:at-backend-local`, `:at-backend-adb` (no concrete backend dependency) |

---

## H. Consumer Boundary

**Normal Product Consumer**（`:app` / `:at-cli-consumer-host` / future AG adapter）只应该看到：

- Execution request / result
- Terminal session contract
- Session state / events
- Execution target / intention
- Readiness / status
- Structured user-action-required
- Bootstrap / pairing semantic result
- Diagnostic semantic projection

**不得看到**：
- `AdbRuntime` / `AdbEngine` / `AdbConsumerBootstrap`
- `AdbConnectionRequest` / `AdbEndpoint` / `AdbInteractiveStream`
- `LocalPtySession` / `AdbOneShotBackend` / `AdbPersistentBackend` / `LocalPersistentBackend`
- `identityDir` / `Context` in runtime methods

---

## I. Composition Boundary

**Android Composition Layer**（`:at-composition-android`）职责：

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

## J. CLI Boundary

`:at-cli` 保持 **optional frontend**，职责仍然是 shell-visible `adb`。

**不是**：Local executor / ADB lifecycle owner / composition root。

**TO-BE**：`:at-cli` 只依赖 `:at-engine-api` + `:at-composition-android`（最多再依赖真正必要的 frontend utility module）。

**不得直接依赖**：`:adb-engine` / `:at-backend-adb` / `:at-backend-local`。

---

## K. AG Boundary

- **AG Core** → NO concrete AT dependency
- **AG integration adapter** → `:at-engine-api` ONLY
- **AGA / Android Host Composition Root** → `:at-composition-android` → 构造 ATEngine → 注入 integration adapter

**AG integration 不需要**：Context / identityDir / AdbRuntime / backend classes。

---

## L. Emulator Relationship

**Terminal Emulator / ScreenState** 属于 **AT Engine domain**（Layer-3/4），不属于 UI / ADB Engine / AG。

**V4 DAG 必须预留位置，但不实现**（`AT.EMULATOR-0` 仍然暂停，直到 V4 physical closure 完成）。

---

## M. Current Phase

**V4-0**：基线建立与物理架构冻结（COMPLETE）。

---

## N. Closed Tasks

| Task | Status |
|---|---|
| AT.V4-0 | COMPLETE（V4 baseline established / physical architecture frozen） |

---

## O. Open Gaps

| Gap | Status |
|---|---|
| `:at-engine-api` module creation | OPEN（V4-1） |
| `:at-engine-spi` module creation | OPEN（V4-1） |
| `:at-composition-android` module creation | OPEN（V4-2） |
| Backend SPI implementation migration | OPEN（V4-3） |
| CLI / Product App migration | OPEN（V4-4） |
| Consumer Conformance / architecture enforcement | OPEN（V4-5） |
| Device parity / V4 Consumer Freeze | OPEN（V4-6） |

---

## P. Next Task

**AT.V4-1**：公共 API / SPI 骨架（创建 `:at-engine-api` + `:at-engine-spi` module，移动 public types）。

**NEXT_OPEN_AUTHORIZED = NO**（等待 Controller Review）。

---

## Q. Version Provenance

- **V3_SOURCE_BASELINE** = `75d5832`（AT.V3: close consumer-boundary audits before V4）
- **V3_BASELINE_TAG** = `AT.V3-TO-V4-BASELINE-V1`
- **V4_START_HEAD** = `75d5832`
- **V4_REPO** = `D:\codebank\AndCode\AndroidTerminal-V4`

---

## R. Known-Good References

- `D:\codebank\AndCode\AndroidTerminal-V3`（READ_ONLY KNOWN-GOOD REFERENCE）
- `D:\codebank\AndCode\AndroidTerminal-V2`（READ_ONLY Verified Reference / Evidence / Regression Oracle / Migration Source）
- `D:\codebank\AndCode\AndroidTerminal`（legacy + SSOT 来源）
- `D:\codebank\AndCode\AndroidTerminal-Public`（known-good production 来源）
