# AT.V3-0 — AndroidTerminal-V3 Clean Architecture Bootstrap

> 本轮任务（Canonical V1 §35）：创建 AndroidTerminal-V3，落盘 Canonical V1，建立 V3 最小工程骨架，建立 Legacy → V3 Migration Manifest，明确 V2 read-only reference 角色，盘点旧 `:at-core`/`:at-runtime` 职责，形成 V3 migration map，验证 clean build baseline。
>
> 最终判定：**AT.V3-0 = COMPLETE**
>
> **CODE_MIGRATION = NOT_STARTED（全部 INVENTORIED，未执行任何迁移）**
> **V2 = VERIFIED REFERENCE（未修改）**
> **NEXT = AT.V3-1（ADB Engine Extraction / Migration Baseline，需另行授权）**

---

## 1. Scope

- **IN SCOPE**（Canonical §35）：
  1. 创建 `D:\codebank\AndCode\AndroidTerminal-V3` + git baseline
  2. 落盘 Canonical V1 全文
  3. 建立 V3 最小工程骨架（按 5 层 + 双 Engine 职责组织）
  4. 建立 Legacy → V3 Migration Manifest
  5. 明确 V2 read-only reference 角色
  6. 盘点 V2 `:at-core` / `:at-runtime` / `:app` / `:atsh` 中 ADB Engine / AT Engine / legacy glue 归属
  7. 形成 V3 migration map
  8. clean build baseline 验证
- **OUT OF SCOPE**（明确未做）：代码迁移、Emulator/Session/UI 实现、SSH、AG 集成、`AT.V3-1` 及后续 Track。

## 2. Deliverables（V3 仓库）

| 产物 | 路径 |
|---|---|
| Canonical V1 架构 | `docs/architecture/AndroidTerminal_Canonical_V1.md` |
| V2 → V3 Migration Manifest | `docs/migration/Migration_Manifest_V2_to_V3.md` |
| V2 职责盘点（ADB/AT/legacy） | `docs/migration/V2_Responsibility_Inventory.md` |
| V3 Evidence Index | `docs/evidence/V3_Evidence_Index.md` |
| V3 工程骨架 | `:adb-engine` / `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:app` / `:test-host` |
| 根工程配置 | `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` / `local.properties`（gitignored）/ wrapper |

## 3. V3 工程骨架

### 3.1 模块与依赖方向（Canonical §19 / §7.1）

```text
:adb-engine        ← 独立 ADB Engine（无 AT 依赖）
:at-engine         ← 可复用 AT Engine（无 backend / UI 依赖）
:at-backend-local  → :at-engine
:at-backend-adb    → :at-engine + :adb-engine
:app               → :at-engine + :at-backend-local + :at-backend-adb  （不直接依赖 :adb-engine）
:test-host         → :at-engine + 两 backend
```

- 关键约束落实：**app 不直接依赖 adb-engine**（ADB 只经 ADB Backend 到达，consumer→provider 关系）；AT Engine 无 Android UI / ADB protocol 依赖。
- package / module 名 **PROVISIONAL**（Canonical §27 不提前锁死）。

### 3.2 Clean Build

```text
.\gradlew.bat assembleDebug
BUILD SUCCESSFUL in 28s
190 actionable tasks: 190 executed
```

- 环境：Gradle 8.7 wrapper（沿用 V2 缓存）、JDK 21（Microsoft）、compileSdk 34 / minSdk 26、AGP 8.5.2、Kotlin 1.9.24。

## 4. V2 职责盘点摘要（全量见 `V2_Responsibility_Inventory.md`）

| 分类 | V2 组件 |
|---|---|
| **ADB ENGINE** | adb_key_store / rsa_2048_key / x509 / tls_connection / tls_transport / adb_connection / feature_set / adb_client（one-shot + interactive stream）/ pairing / shell_v2 / adb_protocol / tcp_transport / AdbManager(JNI) / terminal_jni(JNI) / boringssl / AtConnectionRequest / AtEndpoint / DiscoveryBackend / NsdDiscoveryBackend / WD readiness / EndpointStore / ConnectionIntent / RecoveryCoordinator / RecoveryPolicy / LossClassifier |
| **AT ENGINE** | TerminalRuntime（one-shot facade）/ TerminalResult / at_core::Core（exec 语义）/ MinimalRuntime / AtExecutionResult / AtExecutionOutcome / AtRuntimeDiagnostic（诊断面）/ AtRuntime（execute 语义部分） |
| **LOCAL EXECUTION（→ Local Backend）** | LocalShellExecutor / LocalPtySession / PtyNative / local_pty.* |
| **CONSUMER / PRODUCT** | MainActivity / AtWirelessConnector / TestHostController / AgHandoff |
| **LEGACY / DEPRECATE / SUPERSEDED** | AdbManager.execute（第二 handshake）/ terminal_jni 第二 handshake / network_probe / ProbeReceiver / TerminalService / WirelessAdbDiscovery / dead headers / atsh evidence UI |

**关键判断**：`:at-runtime` 是"AT 外壳 + ADB 内芯"的混合体，必须按职责拆分（Canonical §21），**不是整体搬入 V3**。

## 5. V3 Migration Map 摘要（全量见 `Migration_Manifest_V2_to_V3.md`）

```text
AT.V3-0  Canonical Bootstrap（本轮）
AT.V3-1  ADB Engine Extraction / Migration Baseline   → 3.1 类项 EXTRACTED
AT.CONTRACT-0  Minimum Contract v0 Draft
AT.PERSIST-0   Local Persistent Vertical Slice
AT.PERSIST-1   ADB Persistent Vertical Slice
AT.EMULATOR-0  Minimal Emulator + Screen State
AT.CONTRACT-1  Contract Freeze
AT.HUMAN-* / AG.TERMINAL-*
```

- 全部迁移项当前状态 = **INVENTORIED**（仅盘点）。
- 不迁移项明确登记（legacy 第二 handshake、network_probe、dead headers 等留 V2 作 evidence/回归 oracle）。

## 6. Git Baseline

```text
repo     : D:\codebank\AndCode\AndroidTerminal-V3
branch   : master（新初始化）
commit   : cf66e53  AT.V3-0: canonical bootstrap baseline
files    : 32 tracked（+ gradle-wrapper.jar 例外于 *.jar 规则）
tree     : clean
V2       : 未修改（只读 reference；HEAD 726a441 已记录）
```

## 7. 术语纪律（Canonical §6）

- 历史"AT Engine"（= AT Runtime + AT Core）按当时语义理解，**不得**自动解释为 Canonical AT Engine。
- 历史 `at-engine-handoff-v0` / `dist/at-engine-handoff-v0` 继续按其当时语义（legacy ADB-dominant execution capability）。

## 8. Final Verdict

```text
AT.V3-0 = COMPLETE

CANONICAL_V1            = LANDED（docs/architecture/AndroidTerminal_Canonical_V1.md）
V3_SKELETON             = CREATED + COMPILED（6 modules, assembleDebug BUILD SUCCESSFUL 190 tasks）
MIGRATION_MANIFEST      = CREATED（docs/migration/Migration_Manifest_V2_to_V3.md）
V2_RESPONSIBILITY       = INVENTORIED（docs/migration/V2_Responsibility_Inventory.md）
V2_ROLE                 = VERIFIED REFERENCE（READ-ONLY, 未修改）
CODE_MIGRATION          = NOT_STARTED（全部 INVENTORIED）
REWRITE                 = NO
NEXT                    = AT.V3-1 ADB Engine Extraction / Migration Baseline（需另行授权）
```

**结论**：AT.V3-0 完成。V3 基线建立、Canonical V1 冻结、骨架 clean build 通过、V2→V3 migration map 就绪；未执行任何代码迁移，未触碰 V2。任务结束，STOP。