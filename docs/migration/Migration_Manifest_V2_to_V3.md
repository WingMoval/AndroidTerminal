# Migration Manifest — AndroidTerminal V2 → V3

> **AT.V3-0 deliverable** — 建立 Legacy → V3 Migration Manifest 与 V3 migration map。
>
> 原则（Canonical V1 §3.2 / §20）：**按职责迁移，不是按旧 Module 搬家；Verified migration，不是 rewrite。**
>
> V2 = Verified Reference / Evidence / Regression Oracle / Migration Source（READ-ONLY）。
> V3 = New Production Mainline。

---

## 1. Migration 状态模型

每个迁移项状态：

```text
NOT_STARTED  尚未开始
INVENTORIED  V2 职责已盘点（本 Manifest 状态）
EXTRACTED    已从 V2 拆出并放入 V3 正确模块
COMPILED     V3 编译通过
PARITY       行为与 V2 对照一致（含真机 regression）
MIGRATED     EXTRACTED + COMPILED + PARITY
```

当前（AT.V3-0 结束）全部真实代码迁移项 = **INVENTORIED**（仅盘点，未迁移）。

---

## 2. 目标模块结构（V3 初始，PROVISIONAL）

```text
AndroidTerminal-V3/
├─ :adb-engine          ADB Engine（独立执行 provider）
├─ :at-engine           AT Engine（可复用终端执行能力）
├─ :at-backend-local    Local Backend（→ Local execution provider）
├─ :at-backend-adb      ADB Backend（→ ADB Engine）
├─ :app                 AndroidTerminal Human UI（consumer）
└─ :test-host           Test Host（独立 consumer）
```

模块数 / package 名 **PROVISIONAL**（Canonical §27 不提前锁死）。

---

## 3. V3 Migration Map

来源文件（V2） → 目标模块（V3） → 状态 → 迁移方式

### 3.1 → `:adb-engine`（AT.V3-1 实际状态）

| V2 来源 | 职责 | 方式 | 状态 |
|---|---|---|---|
| `at-core/.../adb/crypto/*` | RSA-2048 + X.509 + android_pubkey | 迁 native 源码 | **MIGRATED**（编译+链接；identity 真机 parity PASS） |
| `at-core/.../adb/tls/*` | TLS 1.3 client + EKM | 迁 native | **MIGRATED**（编译+链接；Golden Chain 真机 PASS） |
| `at-core/.../adb/transport/*` | TCP + TLS transport | 迁 native | **MIGRATED**（编译+链接；connect 真机 PASS） |
| `at-core/.../adb/connection/*` | CNXN/STLS/packet/stream + feature | 迁 native | **MIGRATED**（编译+链接；Golden Chain 真机 PASS） |
| `at-core/.../adb/client/adb_key_store.*` | 持久身份 | 迁 native | **MIGRATED**（编译+链接；identity/key load 真机 PASS） |
| `at-core/.../adb/client/adb_client.*` | one-shot shell + connect | 迁 native（ADB Backend 消费）| **MIGRATED**（编译+链接；connect/one-shot/reuse 真机 PASS） |
| `at-core/.../adb/client/adb_interactive_stream.*` | PROVISIONAL interactive stream | 迁 native（ADB Backend 消费）| **MIGRATED**（编译+链接；interactive primitive 真机 PASS；加 `connection()` accessor） |
| `at-core/.../adb/pairing/*` | SPAKE2/AES-GCM/PeerInfo/PairingClient | 迁 native | **COMPILED**（编译+链接+JNI 暴露；真机 pairing 需配对 UI，未跑） |
| `at-core/.../adb/service/shell_v2_protocol.*` | shell-v2 帧 | 迁 native | **MIGRATED**（编译+链接；one-shot stdout/stderr/exit 真机 PASS） |
| `at-core/.../adb_protocol/*` | packet framing | 迁 native | **MIGRATED**（编译+链接；Golden Chain 真机 PASS） |
| `at-core/.../transport/*` | TCP transport | 迁 native | **MIGRATED**（编译+链接；connect 真机 PASS） |
| `at-core/.../jni/terminal_jni.cpp`（JNI 部分） | JNI marshal | 迁为 **`jni/adb_engine_jni.cpp`**（新 canonical JNI，剥离 legacy handshake）| **MIGRATED**（编译+链接；11 JNI symbols == Kotlin externals；真机 PASS） |
| `at-core/.../AdbManager.kt`（JNI bridge 部分） | JNI bridge | 迁为 **`internal/AdbNative.kt`**（ADB-domain，无 AT 类型）| **MIGRATED**（编译；真机 PASS） |
| `at-runtime/.../AtConnectionRequest.kt` | explicit 连接输入 | 迁为 **`AdbConnectionRequest.kt`** | **MIGRATED**（编译） |
| `at-runtime/.../AtEndpoint.kt` / `AtEndpointSource.kt` / `AtDiscoveredService.kt` | 端点数据 | 迁为 **`AdbEndpoint.kt`** | **COMPILED**（编译；未真机） |
| `at-runtime/.../DiscoveryBackend.kt` / `AndroidNsdDiscoveryBackend.kt` | mDNS 发现 | 迁为 **`AdbRuntimeBackends.kt`（AdbDiscoveryBackend / AdbAndroidNsdDiscoveryBackend）** | **COMPILED**（编译；mDNS 真机未跑） |
| `at-runtime/.../WirelessDebuggingReadinessBackend.kt` / `AndroidWirelessDebuggingReadinessBackend.kt` | WD 状态 | 迁为 **`AdbRuntimeBackends.kt`（AdbWirelessDebuggingReadinessBackend / AdbAndroid...Backend）** | **COMPILED**（编译；WD 查询真机未跑） |
| `at-runtime/.../EndpointStore.kt` | 端点缓存 | 迁为 **`AdbEndpointStore`** | **COMPILED**（编译；未真机） |
| `at-runtime/.../ConnectionIntent.kt` / `RecoveryCoordinator.kt` / `RecoveryPolicy.kt` / `LossClassifier.kt` | 连接恢复 | 迁为 **`AdbConnectionIntent` / `AdbRecoveryPolicy` / `AdbRecoveryCoordinator`**（recovery 改走 **AdbEngine**，不再依赖 AT `TerminalRuntime`）；`LossClassifier` 的 AT-result 耦合留待 AT track | **COMPILED**（编译；真机 recovery 未跑） |
| `at-core/.../third_party/boringssl/*` | BoringSSL 依赖 | 迁 vendored | **MIGRATED**（编译+链接+真机 PASS） |

> 说明：`AdbClient` 新增 `connection()` accessor（ADB Engine 内部，供 interactive stream 复用同一 ONLINE 连接）。非行为变更。

### 3.2 → `:at-engine`

| V2 来源 | 职责 | 方式 | 状态 |
|---|---|---|---|
| （新建 contract domain）`ATExecution` / `ATSession` / `ATBackend` / `ATEngine` | one-shot + persistent 语义契约、backend capability、极薄 facade | 按 Canonical V1 + AT.CONTRACT-0 新建（PROVISIONAL）| **DRAFT（contract types landed；unit tests 6/6 PASS）** |
| `at-core/.../TerminalRuntime.kt`（one-shot facade） | one-shot 执行语义入口 | 迁/重构为 AT Engine public surface（待 slice 验证后）| NOT_STARTED（runtime 未迁） |
| `at-core/.../TerminalResult.kt`（ShellOutcome/TerminalResult） | one-shot 结果语义 | **语义保留在 `ATExecutionResult`**（新 shape；旧 Kotlin 不复制）| DRAFT（语义已表达，runtime 未迁） |
| `at-core/.../at_core/at_core.*`（exec 语义） | one-shot 执行内核 | 迁（consumes ADB Engine）| NOT_STARTED（runtime 未迁） |
| `at-core/.../runtime/MinimalRuntime.*` | process-scoped 执行生命周期 | 迁（consumes ADB Engine）| NOT_STARTED（runtime 未迁） |
| `at-runtime/.../AtExecutionResult.kt` / `AtExecutionOutcome.kt` | one-shot 结果（Runtime 面） | **语义并入 `ATExecutionResult/Outcome`**（新 shape）| DRAFT（语义已表达，runtime 未迁） |
| `at-runtime/.../AtRuntimeDiagnostic.kt`（诊断面） | 诊断 | 待 slice 验证后决定 | NOT_STARTED |
| `at-runtime/.../AtRuntime.kt`（execute 语义部分） | one-shot 执行语义 | 拆出（ADB 部分归 ADB Engine；execute 语义待 slice）| NOT_STARTED |
| （未来）TerminalSession / Emulator / Screen State | persistent terminal | **TerminalSession contract DRAFT 已落地**（`ATSession.kt`，无 Emulator/ScreenState 实现）| DRAFT（contract）/ NOT_STARTED（runtime） |

### 3.3 → `:at-backend-local`

| V2 来源 | 职责 | 方式 | 状态 |
|---|---|---|---|
| `atsh/.../shell/LocalShellExecutor.kt` + `ShellResult.kt` | Local one-shot（ProcessBuilder → sh -c） | 按 backend contract 重建为 `LocalOneShotBackend`（ATOneShotBackend）| **MIGRATED**（真机 PASS：stdout/stderr/exitCode、非零 exit ≠ failure） |
| `atsh/.../pty/LocalPtySession.kt` / `PtyNative.kt` / `local_pty.*` | Local PTY persistent shell | native `local_pty.{h,cpp}` **迁入** `:at-backend-local/src/main/cpp/`（忠实迁移，非重写）；JNI 适配为 `LocalPtyNative`；`LocalPtySession` 适配 V3 package；`LocalPersistentBackend` + `LocalTerminalSession` 实现 | **MIGRATED**（真机 P1–P10 PASS：real PTY/principal/persistent I/O/cwd/env/stdin/Ctrl-C/resize/exit/close/cleanup） |

> AT.PERSIST-0 实现落地；真机 P1–P10 验证待设备连接（当前 BLOCKED_BY_NO_DEVICE）。

### 3.4 → `:at-backend-adb`

| V2 来源 | 职责 | 方式 | 状态 |
|---|---|---|---|
| （ADB Backend 由 :at-engine 契约 + :adb-engine 能力组装） | 交互/one-shot backend | 新建 adapter | NOT_STARTED（runtime 未接线）；contract probe 已落地（STUB，compile-only） |

### 3.5 → `:app`

| V2 来源 | 职责 | 方式 | 状态 |
|---|---|---|---|
| `app/.../MainActivity.kt` | Human UI consumer | 重建（AT.HUMAN-*）| NOT_STARTED |
| `app/.../AtWirelessConnector.kt` | Host helper | 演进（走 AT Engine public boundary）| INVENTORIED |

### 3.6 → `:test-host`

| V2 来源 | 职责 | 方式 | 状态 |
|---|---|---|---|
| `at-test-host/.../TestHostController.kt` | 独立 consumer | 迁 | INVENTORIED |

---

## 4. 不迁移项（LEGACY / DEPRECATE / SUPERSEDED）

```text
AdbManager.execute（legacy 第二套 handshake）        → DEPRECATE（留在 V2 evidence）
terminal_jni.cpp 第二套 handshake                    → DEPRECATE（AGENTS.md 已记债）
network_probe / ProbeReceiver                        → 不进入 V3 生产（诊断留 V2）
TerminalService 占位                                  → 不迁移（V3 视需要重建）
WirelessAdbDiscovery.kt（已删，被 :at-runtime 取代）   → SUPERSEDED
auth_placeholder.h / shell_placeholder.h（dead headers）→ 不迁移
atsh MainActivity（evidence UI）                      → 留 V2 evidence
```

---

## 5. Migration Sequence（Canonical §22 / §24）

```text
AT.V3-0  Canonical Bootstrap                                    ← COMPLETE
AT.V3-1  ADB Engine Extraction / Migration Baseline             ← 本轮（3.1 全部 EXTRACTED，native+JNI=MIGRATED/COMPILED）
AT.CONTRACT-0  Minimum Contract v0 Draft                        → 3.2/3.4 契约草稿
AT.PERSIST-0   Local Persistent Vertical Slice                  → 3.3 + at-engine session
AT.PERSIST-1   ADB Persistent Vertical Slice                    → 3.4 + 3.1 interactive
AT.EMULATOR-0  Minimal Emulator + Screen State                  → at-engine 新增
AT.CONTRACT-1  Contract Freeze（真实行为验证后）
AT.HUMAN-* / AG.TERMINAL-*                                     → 3.5 / AG 消费
```

---

## 6. Regression / Parity 策略（Canonical §20 / §31）

- **行为 parity**：每个迁移项在 V3 编译通过后，用 V2 已验证行为做对照（one-shot stdout/stderr/exitCode、Golden Chain、pairing、interactive stream、Local PTY、recovery/no-replay）。
- **真机 regression**：必要时（JNI/ABI/Kotlin 形状变更）在真机验证，禁止仅凭 build 宣称 PASS（V2 AGENTS §5）。
- **回归 oracle**：V2 保留为 Regression Oracle；AT.V3-0 已记录 V2 HEAD `726a441` + 工作树未提交已验证改动。

---

## 7. 当前状态（AT.V3-0 结束）

```text
MIGRATION_MANIFEST   = CREATED（本文档）
V2_INVENTORY         = COMPLETE（docs/migration/V2_Responsibility_Inventory.md）
V3_SKELETON          = COMPILED（6 modules assembleDebug BUILD SUCCESSFUL）
CODE_MIGRATION       = ADB ENGINE EXTRACTION COMPLETE（AT.V3-1；native+JNI MIGRATED/COMPILED）
ADB_ENGINE_INDEP     = VERIFIED（:adb-engine 零依赖 :at-engine/UI/AG）
ADB_ENGINE_PARITY    = PARTIAL（connect/one-shot/reuse/interactive 真机 PASS；
                       pairing/discovery/recovery = COMPILED，真机待配对 UI/后续 slice）
AT_CONTRACT          = REVISED_DRAFT（AT.CONTRACT-0 + AT.PERSIST-0 C1/C2/C3 修订）
AT_ENGINE_INDEP      = VERIFIED（:at-engine 零依赖 backend/adb/UI）
LOCAL_BACKEND        = MIGRATED（AT.PERSIST-0 真机 P1–P10 PASS）
NEXT                 = AT.PERSIST-1（ADB Persistent，需 Controller 授权）
```

> 本文档为 migration map 的记录与总控输入；AT.V3-1 迁移已按授权执行（详见 `docs/implementation/AT.V3-1_ADB_Engine_Extraction_Migration_Baseline.md`）。