# AT.V4-4.1A — Final Consumer Dependency Closure

> **Date**: 2026-10-21
> **Scope**: 最终 Consumer 物理依赖闭合（GRADLE / VISIBILITY ONLY）
> **TYPE**: DEPENDENCY AUDIT / PHYSICAL BOUNDARY CLOSURE / GRADLE VISIBILITY CLEANUP / PACKAGING VERIFICATION
> **DEVICE_TEST**: NO

---

## 1. Baseline

| Item | Value |
|---|---|
| START_HEAD | `e8372d3`（AT.V4-4.1: close consumer semantic type boundary） |
| START_WORKTREE | CLEAN |
| V3_BASELINE | `75d5832`（READ-ONLY，本轮 0 write） |

## 2. Existing Contradiction

V4-4.1 之后 SSOT 记录的实际 DAG 仍含：

```
:app → :at-backend-local / :at-backend-adb   （直接 implementation）
```

但 SSOT Forbidden Dependency Rules 规定 `:app` 不得直接依赖
`:at-engine` / `:at-engine-spi` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine`。

→ `APP_DIRECT_CONCRETE_BACKEND_DEP = YES` 与 `CONSUMER_BOUNDARY_IMPLEMENTATION = CLOSED` 矛盾。

第二个可疑点：`:at-composition-android` 使用 `api(:at-engine-spi)`，
而 `:at-engine-spi` 是 backend implementation contract，不是 normal Consumer contract。

## 3. App Backend Dependency Audit

| 问题 | 答案 |
|---|---|
| A. production source compile dependency？ | NO（`:app/src/main` 零 `com.at.backend.*` / `com.at.engine.spi.*` import） |
| B. test source compile dependency？ | NO（V4-4.1 迁移后 consumer test 全部走 `AppRuntime` / composition facade） |
| C. 只为 native .so packaging？ | NO — native .so 经 composition 的 `implementation(:at-backend-*)` **transitively** 进入 runtime（见 §5） |
| D. V3/V4 transition 遗留？ | **YES — HISTORICAL**（V3 无 composition，`:app` 直接构造 backend；V4-2 建立 composition 后该直接依赖失去 source 需求，V4-4 未删除） |

**APP_DIRECT_BACKEND_DEP_REASON_BEFORE = HISTORICAL**

## 4. Source Usage Audit

| Scope | `com.at.backend.local.*` | `com.at.backend.adb.*` | `com.at.engine.spi.*` | `com.adb.engine.*` | `com.at.engine.impl.*` |
|---|---|---|---|---|---|
| `:app/src/main` | 0 | 0 | 0 | 0 | 0 |
| `:app/src/test` | 0 | 0 | 0 | 0 | 0 |
| `:app/src/androidTest` | 0 | 0 | 0（V4-4.1A 移除 2 个 dead import `ATPersistentBackend`） | 0 | 0 |
| `:at-cli-consumer-host`（main+test） | 0 | 0 | 0（V4-4.1A 移除 1 个 dead import） | 0 | 0 |
| `:at-cli`（main+test） | 0 | 0 | 0 | 0 | 0 |

注：3 个 androidTest 文件存在 `import com.at.engine.ATPersistentBackend` **dead import**
（只 import 未使用；V3 遗留，当时 `ATPersistentBackend` 在 `com.at.engine` 包内）。
V4-1 后该类型移入 `:at-engine-spi`，import 路径失效 → 本轮删除（IMPORT-ONLY，无行为影响）。

## 5. Packaging Dependency Analysis

Native lib 加载方（全部在 composition 内部依赖链上）：

| .so | 构建方 | 加载方（`System.loadLibrary`） | 所在 module |
|---|---|---|---|
| `libat_local_backend.so` | `:at-backend-local` | `LocalPtyNative.kt` | `:at-backend-local` |
| `libadbd_engine.so` | `:adb-engine` | `AdbNative.kt` | `:adb-engine` |
| `libatcli_env.so` | `:at-cli` | `AdbCliBridgeServer.kt` | `:at-cli` |
| `libatapp_env.so` | `:app`（app 自身 CMake） | `NativeEnv.kt` | `:app` |

`:app` 的 runtime classpath 经 `:at-composition-android` 的
`implementation(:at-backend-local)` / `implementation(:at-backend-adb)` / `implementation(:adb-engine)`
**transitively** 获得全部 backend module 的 native libs（AGP 将 runtime classpath 上所有
library 的 jniLibs 合并进 APK）。删除 `:app` 直接 backend 依赖 **不影响 packaging**。

## 6. Composition SPI Visibility Audit

`:at-composition-android` 全部 public 声明（`ATAndroid` / `ATAndroidComposition` /
`ATAndroidConfig` / `ATAndroidAdbControl` / `ATAdbSemantic` 5 类型 / `ATAdbLifecycleListenerHandle`）：

- `ATOneShotBackend` / `ATPersistentBackend` / `com.at.engine.spi.*` = **0**
- `com.at.backend.*` = **0**
- `com.adb.engine.*`（public 签名）= **0**（V4-4.1 已闭合；`from()` mapper 为 `internal`）
- `com.at.engine.impl.*` = **0**

**COMPOSITION_PUBLIC_SPI_TYPE_USAGE = 0**

`ATAndroidComposition` 的 public properties（`localEngine` / `adbEngine`）类型是
`ATEngine`（`:at-engine-api` 类型）→ `api(:at-engine-api)` **保留**（§12：区别对待，不机械降级）。

## 7. Changes

| 文件 | 变更 | 类别 |
|---|---|---|
| `app/build.gradle.kts` | 删除 `implementation(project(":at-backend-local"))` + `implementation(project(":at-backend-adb"))`；更新注释 | DEPENDENCY_CHANGE |
| `at-composition-android/build.gradle.kts` | `api(project(":at-engine-spi"))` → `implementation(project(":at-engine-spi"))`；更新注释（`:at-engine-api` 保持 `api`） | DEPENDENCY_CHANGE / VISIBILITY |
| `app/src/androidTest/.../CommonConsumerPathTest.kt` | 删除 dead import `com.at.engine.ATPersistentBackend` | IMPORT_ONLY |
| `app/src/androidTest/.../ProgrammaticAdbConsumerTest.kt` | 删除 dead import `com.at.engine.ATPersistentBackend` | IMPORT_ONLY |
| `at-cli-consumer-host/src/androidTest/.../AtCliConsumerTest.kt` | 删除 dead import `com.at.engine.ATPersistentBackend` | IMPORT_ONLY |
| `docs/AT.V4_MASTER_SSOT.md` | DAG / Allowed / Forbidden / Consumer Boundary 一致性修正 | DOC_CHANGE |
| `docs/roadmap/AT.V4_ROADMAP.md` | V4-4.1A = COMPLETE 记录 | DOC_CHANGE |
| 本文档 | 新增 | DOC_CHANGE |

**无** ADB protocol / TLS / pairing / bootstrap / AdbRuntime / recovery / PTY /
shell-v2 / CLI protocol / DebugBridge / ATEngine semantics / identity / session semantics 变更。

## 8. Final DAG

```
:at-engine-api        → none
:at-engine-spi        → :at-engine-api
:at-engine            → :at-engine-api, :at-engine-spi
:at-backend-local     → :at-engine-api, :at-engine-spi
:at-backend-adb       → :at-engine-api, :at-engine-spi, :adb-engine
:adb-engine           → none

:at-composition-android   （assembly root）
  → api(:at-engine-api)
  → implementation(:at-engine-spi)        ← V4-4.1A 降级
  → implementation(:at-engine)
  → implementation(:at-backend-local)
  → implementation(:at-backend-adb)
  → implementation(:adb-engine)

:at-cli               → :at-engine-api (ONLY)

:app                  → :at-engine-api, :at-composition-android, :at-cli
  （NO :at-backend-local / :at-backend-adb / :adb-engine / :at-engine-spi / :at-engine）

:at-cli-consumer-host → :at-engine-api, :at-composition-android, :at-cli
```

NO CYCLES。

## 9. Compile / Runtime Dependency Evidence

**`:app` debugCompileClasspath 顶级 project 依赖**（`gradlew :app:dependencies`）：

```
+--- project :at-engine-api
+--- project :at-composition-android
+--- project :at-cli
```

（compile classpath 零 `:at-backend-*` / `:adb-engine` / `:at-engine-spi` / `:at-engine`。）

**`:app` debugRuntimeClasspath**：backend 全部 **transitively via composition**：

```
+--- project :at-engine-api
+--- project :at-composition-android
|    +--- project :at-engine-spi
|    +--- project :at-engine
|    +--- project :at-backend-local
|    +--- project :at-backend-adb
|    |    \--- project :adb-engine
|    \--- project :adb-engine
\--- project :at-cli
     \--- project :at-engine-api
```

APP_DEBUG_COMPILE_CLASSPATH = PASS（无需直接 concrete backend contract）
APP_DEBUG_RUNTIME_CLASSPATH = PASS（backend 经 composition 进入）

## 10. APK Native Packaging Evidence

`:app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` 静态 inspection：

| Library | In APK |
|---|---|
| `lib/arm64-v8a/libat_local_backend.so` | YES |
| `lib/arm64-v8a/libadbd_engine.so` | YES |
| `lib/arm64-v8a/libatcli_env.so` | YES |
| `lib/arm64-v8a/libatapp_env.so`（+ armeabi-v7a / x86 / x86_64） | YES |

APK_BACKEND_PACKAGING_PATH = **TRANSITIVE_VIA_COMPOSITION**

## 11. Import Audit（最终）

| Scope | forbidden imports（spi / backend.* / adb.engine / engine.impl） |
|---|---|
| `:app` main + test + androidTest | 0 |
| `:at-cli-consumer-host` main + test + androidTest | 0 |
| `:at-cli` main + test | 0 |

CONSUMER_FORBIDDEN_IMPORTS = 0

## 12. Public API Leak Audit（最终）

`:at-composition-android` public 声明中：

- `com.at.engine.spi.*` = 0
- `com.at.backend.*` = 0
- `com.adb.engine.*` = 0
- `com.at.engine.impl.*` = 0

COMPOSITION_PUBLIC_SPI_LEAK = 0 / COMPOSITION_PUBLIC_BACKEND_LEAK = 0 /
COMPOSITION_PUBLIC_ADB_INTERNAL_LEAK = 0 / COMPOSITION_PUBLIC_IMPL_LEAK = 0

## 13. Build / Test Results

| Gate | 结果 |
|---|---|
| `:at-engine-api:test` / `:at-engine-spi:test` / `:at-engine:test` / `:at-backend-local:test` / `:at-backend-adb:test` / `:at-composition-android:test` / `:at-cli:test` / `:app:test` / `:at-cli-consumer-host:test` | PASS |
| `gradlew test`（全量） | PASS |
| `gradlew assembleDebug`（全量） | PASS |
| `:app:assembleDebugAndroidTest` / `:at-cli-consumer-host:assembleDebugAndroidTest` | PASS |

## 14. Semantic Preservation

GRADLE / VISIBILITY ONLY + 3 个 dead-import 删除：

- ATEngine_API_CHANGED = NO
- ADB_LOGIC_CHANGED = NO / PTY_LOGIC_CHANGED = NO
- PAIRING_LOGIC_CHANGED = NO / RECOVERY_LOGIC_CHANGED = NO
- IDENTITY_LOGIC_CHANGED = NO / CLI_WIRE_PROTOCOL_CHANGED = NO
- SEMANTIC_DELTA = NONE

## 15. Final Verdict

| Gate | 结果 |
|---|---|
| APP_DIRECT_LOCAL_BACKEND_DEP | NO |
| APP_DIRECT_ADB_BACKEND_DEP | NO |
| APP_DIRECT_ADB_ENGINE_DEP | NO |
| APP_DIRECT_ENGINE_SPI_DEP | NO |
| APP_DIRECT_ENGINE_IMPL_DEP | NO |
| COMPOSITION_ENGINE_SPI_CONFIGURATION | implementation（从 api 降级） |
| CONSUMER_FORBIDDEN_DIRECT_DEPS | 0 |
| CONSUMER_FORBIDDEN_IMPORTS | 0 |
| BUILD / UNIT_TEST / ANDROID_TEST_COMPILE | PASS / PASS / PASS |
| DEVICE_TEST / DEVICE_PARITY | NOT_EVALUATED |

**CONSUMER_BOUNDARY_IMPLEMENTATION = CLOSED**（Gradle dependency + source import 双维度满足）。

## 16. Handoff to V4-4.2

AT.V4-4.2（Device Closure）scope 不变：Product Local L1-L7 / Product ADB A1-A10 /
CLI C1-C6 / ConsumerHost H1-H7 / App+Host restart identity / connection reuse /
runtime ownership device evidence。
