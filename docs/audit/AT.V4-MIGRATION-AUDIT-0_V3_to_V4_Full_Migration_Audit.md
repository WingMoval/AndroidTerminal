# AT.V4-MIGRATION-AUDIT-0 — V3 → V4 全量迁移完整性审计

> **Type**: DOCS / EVIDENCE ONLY（PRODUCTION_CHANGE = 0, GRADLE_CHANGE = 0, TEST_SOURCE_CHANGE = 0）
> **V3_BASELINE**: `75d5832` (tag `AT.V3-TO-V4-BASELINE-V1`, READ-ONLY)
> **V4_BASELINE**: `84936f8` (AT.V4-4 checkpoint)
> **Date**: 2026-10-21

---

## 1. Executive Verdict

**V3_FULL_MIGRATION_PARITY = PASS_AT_IMPLEMENTATION_LEVEL**

- V3 全部 355 个 tracked items 已逐层审计（module / production / native / resource / test / docs）。
- **MISSING_PRODUCTION_CAPABILITY_COUNT = 0**
- **UNCLASSIFIED_V3_ITEM_COUNT = 0**
- **UNEXPLAINED_PRODUCTION_LOSS_COUNT = 0**
- **OWNERSHIP_REGRESSION_COUNT = 0**
- **UNAUTHORIZED_DROP_COUNT = 0**

**DEVICE_PARITY = NOT_EVALUATED**（device 未连接；revalidation 属于 AT.V4-4.1 / V4-6）。
本审计只证明 **implementation-level parity**，不证明 device parity。

V3 的 26 项 handoff inherited capabilities + 8 项补充审计项（bootstrap 分类 / DebugBridge / UI / live status / restart identity）共 **34 项 capability**，全部 `IMPLEMENTATION_PRESENT`，semantic delta = NONE，device validation 全部 `PENDING (V4-4.1)`。

---

## 2. Baselines

| Repo | Path | Baseline | Mode |
|---|---|---|---|
| V3 | `D:\codebank\AndCode\AndroidTerminal-V3` | `75d5832` (= tag AT.V3-TO-V4-BASELINE-V1) | READ-ONLY（preflight: HEAD=75d5832, worktree CLEAN） |
| V4 | `D:\codebank\AndCode\AndroidTerminal-V4` | `84936f8` | WRITABLE（docs only 本轮） |

Preflight PASS。V3 工作树未做任何改变（审计全程使用 `git -C V3 show/ls-tree` 读取 baseline tree）。

---

## 3. Audit Method

1. `git ls-tree -r --name-only` 获取 V3 (75d5832) / V4 (84936f8) 全量 tracked inventory（355 / 371 文件）。
2. 按 top-level module 分组对比。
3. 逐文件 path-level diff：V3 有而 V4 同路径无的文件（6 个）+ V4 新增文件（22 个）。
4. 对 path-diff 命中的文件做 symbol/职责级语义映射（不判"路径不存在=MISSING"）。
5. 对 adb-engine / native / resources / test / docs 做 exact-set 对比（逐文件 path 一致性 + `git diff` 抽查）。
6. 以 `docs/handoff/AT_V3_TO_V4_HANDOFF.md` §3 inherited capability table（26 项）为最低集合，逐项在 V4 当前代码中定位实现。
7. 专项审计：bootstrap/identity 分类规则、recovery/lifecycle、CLI full path、Human UI、DebugBridge、test host、evidence/freeze。

审计对象以 **GIT TRACKED TREE** 为准；未扫描 .gradle/ / build/ / 本轮交付/ / untracked。

---

## 4. Classification Rules

| 分类 | 含义 |
|---|---|
| MIGRATED | V3 内容在 V4 存在（允许 module/path 改变），capability/semantic 保持 |
| REPLACED_BY_EQUIVALENT | V3 具体实现被 V4 新结构取代，有明确等价关系 |
| TEST_REFERENCE_ONLY | test harness / 每轮交付快照，非 production capability |
| INTENTIONALLY_DEFERRED | V3 自己 DEFER 或 Canonical/SSOT 明确 DEFER |
| INTENTIONALLY_DROPPED_AUTHORIZED | 有明确历史决议授权删除（本轮 0 例） |
| MISSING | V3 有效实现，V4 无代码/无等价/无授权删除（本轮 0 例） |
| UNCLASSIFIED | 无法判断（本轮 0 例） |

---

## 5. Module Inventory

V3 = 10 modules；V4 = 13 modules（+3）。

| V3 Module | V3 Role | V4 Target | Status |
|---|---|---|---|
| :adb-engine | ADB provider | :adb-engine（unchanged） | MIGRATED |
| :at-engine | AT Engine（API+impl mixed） | :at-engine-api + :at-engine-spi + :at-engine（impl only） | REPLACED_BY_EQUIVALENT（V4-1 物理拆分） |
| :at-backend-local | Local backend | :at-backend-local（unchanged classes） | MIGRATED |
| :at-backend-adb | ADB backend | :at-backend-adb（unchanged classes） | MIGRATED |
| :app | Human UI product | :app（glue 保留，common wiring → composition） | MIGRATED |
| :test-host | Test host | :test-host | MIGRATED |
| :adb-only-test-host | ADB-only test host | :adb-only-test-host | MIGRATED |
| :at-shellless-test-host | Shellless test host | :at-shellless-test-host | MIGRATED |
| :at-cli | Reusable CLI capability | :at-cli（SPI 简化） | MIGRATED |
| :at-cli-consumer-host | CLI consumer test host | :at-cli-consumer-host（→ composition） | MIGRATED |
| —（V3 无） | — | :at-engine-api（V4 新） | MIGRATED（V4-1 新增） |
| —（V3 无） | — | :at-engine-spi（V4 新） | MIGRATED（V4-1 新增） |
| —（V3 无） | — | :at-composition-android（V4 新） | MIGRATED（V4-2/4 新增） |

**Module coverage = 10/10 + 3 new。无 module 丢失。**

---

## 6. Production Source Coverage

V3 production Kotlin/Java = 55 files（含 4 个 at-engine contract 文件）。

**Path-level diff 结果**：V3 有而 V4 同路径无 = 6 个文件：
1-4. `at-engine/src/main/java/com/at/engine/{ATBackend,ATEngine,ATExecution,ATSession}.kt` → **REPLACED_BY_EQUIVALENT / EXACT_RELOCATED**：
   - ATExecution.kt → `:at-engine-api`（verbatim 搬迁）
   - ATSession.kt → `:at-engine-api`（verbatim + `ATSessionLaunch` 提取，SEMANTIC_DELTA=NONE）
   - ATBackend.kt → `:at-engine-spi`（verbatim，`openSession` 参数改为 `ATSessionLaunch`）
   - ATEngine.kt → `:at-engine-api` interface + `:at-engine/impl/DefaultATEngine`（公开面 execute/openSession 不变）
5. `本轮交付/AT.CLI-PATH-FACTCHECK-0.md` → **TEST_REFERENCE_ONLY**（每轮交付快照，按 AGENTS.md §4 每轮刷新，V4 有自己的 本轮交付）
6. `本轮交付/AT.CONSUMER-PHYSICAL-0_AT_Engine_Physical_Boundary_Design.md` → **TEST_REFERENCE_ONLY**（设计内容已在 V4-0/V4-0.1 physical freeze docs 中承接）

其余 49 个 V3 production 文件全部在 V4 同路径存在，其中：
- `adb-engine` 14 文件：**EXACT_UNCHANGED**（`git diff 75d5832..84936f8 -- adb-engine` 为空）
- `at-backend-local` 5 / `at-backend-adb` 2：MIGRATED（仅 import 修复，类语义不变）
- `at-cli` 6 文件：4 EXACT_UNCHANGED + AdbCliBridgeServer/ATCliExecution MIGRATED（V4-4 SPI 简化，协议不变）
- `app` 14 文件：10 EXACT_UNCHANGED + AppRuntime/AppCliExecution/AppTerminal MIGRATED（V4-4 → composition，product glue 保留）
- `AtCliConsumerHost` / `AdbOnlyHost` / `ShelllessHost` / `ShelllessPersistentBackend`：MIGRATED（import 修复 / → composition）

**Production source: MISSING = 0, UNCLASSIFIED = 0。**

---

## 7. Native/JNI Coverage

V3 native = 144 files（37 个 adb-engine 自有 cpp/h + BoringSSL headers + 5 组 CMakeLists）。V4 native 文件集 = **逐路径完全一致**。

| V3 Native Target | Source | V4 Target | Classification |
|---|---|---|---|
| libadbd_engine.so | adb-engine/cpp（client/connection/crypto/pairing/service/tls/transport/adb_protocol/jni + BoringSSL） | 同 | EXACT_UNCHANGED |
| libat_local_backend.so | at-backend-local/cpp（local_pty + jni） | 同 | EXACT_UNCHANGED |
| libatcli_env.so | at-cli/cpp（cli_bridge_server + native_env） | 同 | EXACT_UNCHANGED |
| libatapp_env.so（product-only） | app/cpp（app_env） | 同 | EXACT_UNCHANGED |
| libshellless_pty.so（test host） | at-shellless-test-host/cpp | 同 | EXACT_UNCHANGED |

BoringSSL static link、Local PTY JNI、CLI native helper、Product-only native helper 全部存在。

**Native: MISSING = 0。**

---

## 8. Manifest/Resource/Asset Coverage

| Item | V3 | V4 | Classification |
|---|---|---|---|
| app/AndroidManifest.xml | permissions: INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / WRITE_SECURE_SETTINGS / ACCESS_FINE_LOCATION(maxSdk 35)；activities: MainActivity + PairActivity | 逐字节相同 | EXACT_UNCHANGED |
| app/assets/cli/adb | shell-visible adb 脚本 asset | 相同 | EXACT_UNCHANGED |
| app/res (layout/strings/themes) | 3 files | 相同 | EXACT_UNCHANGED |
| adb-only-test-host/res (strings + accessibility_probe_config) | 2 files | 相同 | EXACT_UNCHANGED |

V3 resource/asset = 6 files，V4 = 6 files，逐路径一致。**无 permission 丢失。**

---

## 9. Entrypoint Coverage

| V3 Entrypoint | V4 Entrypoint | Status |
|---|---|---|
| Android Product launch（MainActivity LAUNCHER） | 同 | MIGRATED |
| PairActivity（pairing code input） | 同 | MIGRATED |
| AtCliMain（app_process CLI entry, `com.at.cli.AtCliMain`） | 同 | MIGRATED |
| LocalCliBootstrap shell function（`adb`） | 同 | MIGRATED |
| CLI UDS bridge（AdbCliBridgeServer, same-UID + SO_PEERCRED） | 同 | MIGRATED |
| DebugBridge（127.0.0.1:45679, developer-only） | 同 | MIGRATED |
| Test host entries（test-host/adb-only/shellless/cli-consumer） | 同 | MIGRATED |

**V3 verified entrypoint 无一无解释消失。**

---

## 10. Capability Matrix Summary

34 项 capability（handoff 26 项 + C30-C34 补充审计项）：

- **IMPLEMENTATION_PRESENT = 34/34**
- **SEMANTIC_DELTA ≠ NONE = 0**
- **DEVICE_REVALIDATION_PENDING = 34/34**（V4-4.1 / V4-6）
- **MISSING_CAPABILITY = 0**
- **UNCLASSIFIED_CAPABILITY = 0**

详见 `AT.V4-MIGRATION-AUDIT-0_Capability_Matrix.csv`。

---

## 11. Bootstrap / Identity Audit

- `AdbConsumerBootstrap`（first-use bootstrap implementation）：`:adb-engine` 内 **EXACT_UNCHANGED**。
- `bootstrapState()` / `hasIdentity()` / `discoverPairing()` / `pair()` / `pairDiscovered()`：全部存在且语义未变。
- **诚实分类规则保持**：
  - NO IDENTITY → `PAIRING_REQUIRED`（evidence-backed）✓
  - EXISTING IDENTITY + generic connect failure → `CONNECT_FAILED`（**不**自动升级为 PAIRING_REQUIRED）✓
  - V4-4 新增的 `ATAndroidAdbControl` 只是 **delegation projection**，未重实现分类逻辑（`bootstrapState()` 直接转发 `AdbConsumerBootstrap.bootstrapState()`）。
- **Identity**：
  - App: `filesDir/adb_identity`（`ATAndroidConfig` 默认值）— 原路径保持
  - ConsumerHost: `filesDir/at-cli-identity`（`ATAndroidConfig(identityProfile = "at-cli-identity")`）— 原路径保持
  - SELF_GENERATED = YES；KEY_COPY = NO；**IDENTITY_PATH_SEMANTIC_CHANGED = NO**
  - restart identity reuse：identity 物理路径未变 → 行为预期不变（device 验证 PENDING）。

---

## 12. Runtime / Recovery Audit

| Item | V3 | V4 | Semantic Delta |
|---|---|---|---|
| AdbRuntime.initialize / singleton | `:adb-engine` AdbRuntime（object） | 同（EXACT_UNCHANGED） | NONE |
| AdbRuntime = UNIQUE lifecycle owner | YES | YES（composition 只调 initialize，不持有） | NONE |
| AdbRecoveryCoordinator = UNIQUE recovery owner | YES | YES（`git diff adb-engine` 为空） | NONE |
| transport-loss projection（onOperationResult） | AdbPersistentBackend/AdbOneShotBackend 调用 | 同 | NONE |
| connection reuse（1 连接） | YES | YES | NONE |
| ensureReady cold path | Runtime-owned sequence | 同（经 adbControl.ensureReady delegation） | NONE |
| no transparent command replay | V3 语义 | 同（backend 代码未改） | NONE |

**SECOND_ADB_RUNTIME = NO；SECOND_RECOVERY_OWNER = NO。**

---

## 13. CLI Audit

V3 已验证 CLI 能力在 V4 的实现状态：

| CLI Capability | V4 Status |
|---|---|
| adb shell \<command\>（T_ONESHOT） | IMPLEMENTATION_PRESENT（AdbCliProtocol 未变；bridge → ATCliExecution.adbEngine()） |
| adb shell interactive（T_INTERACTIVE） | IMPLEMENTATION_PRESENT |
| stdin / stdout/stderr/exit | IMPLEMENTATION_PRESENT |
| Ctrl-C / resize | IMPLEMENTATION_PRESENT（backend 未变） |
| cwd/env | IMPLEMENTATION_PRESENT |
| natural exit / return to same Local shell | IMPLEMENTATION_PRESENT |
| connection reuse | IMPLEMENTATION_PRESENT |
| bad args | IMPLEMENTATION_PRESENT（AdbCliArgs 未变） |
| bad pairing code | IMPLEMENTATION_PRESENT（pair 路径经 host ATCliExecution.pair()） |
| adb pair \<host\>:\<port\> \<code\>（T_PAIR） | IMPLEMENTATION_PRESENT（bridge → host pair()；pairing primitive = AdbEngine.pair 不变） |
| status projection（T_STATUS） | IMPLEMENTATION_PRESENT |

明确仍 DEFERRED（V3 即未实现，不计入迁移缺失）：`adb pair <code>` shorthand、multiple endpoint chooser、push/pull/install/forward/reverse/devices。

CLI bridge protocol、same-UID UDS、SO_PEERCRED peer UID check、app_process 路径：全部未变。

---

## 14. Human Product Audit

V3 Product 全部用户能力在 V4-4 迁移后：

| Capability | V4 Status |
|---|---|
| Session page / Local session / ADB session | IMPLEMENTATION_PRESENT（MainActivity/SessionPage/SessionPageController EXACT_UNCHANGED） |
| Dynamic sessions/tabs | IMPLEMENTATION_PRESENT（DynamicSessionPagesTest 保留） |
| Command/input UI | IMPLEMENTATION_PRESENT |
| Ctrl-C UI / resize relay | IMPLEMENTATION_PRESENT（AppRuntime.relayInteractiveResize 保留为 product glue） |
| Status presentation（live ADB command status） | IMPLEMENTATION_PRESENT（AdbCliStatusHub EXACT_UNCHANGED；fast-path backfill + live cold path 语义保留） |
| PairActivity | IMPLEMENTATION_PRESENT（EXACT_UNCHANGED；仍经 consumerBootstrap 单一路径） |
| CLI provisioning（CliFrontendProvisioner） | IMPLEMENTATION_PRESENT（EXACT_UNCHANGED） |
| return-to-local behavior | IMPLEMENTATION_PRESENT |

**V4-4 architecture cleanup 未误删任何 UI capability。**（AppRuntime 删除的仅为 common wiring：AdbRuntime init / identity dir construction / backend constructor / DefaultATEngine construction — 这些由 :at-composition-android 承接。）

---

## 15. DebugBridge Audit

**DEBUG_BRIDGE_STATUS = MIGRATED（EXACT_UNCHANGED）**

- `app/src/main/java/com/androidterminal/app/debug/DebugBridge.kt` + `DebugProtocol.kt` 在 V4 逐路径存在且内容未变。
- developer-only、127.0.0.1:45679、attach 到 Human UI 已打开的真实 TerminalSession、single RX/listener owner、serialized write、input activity/output/state fan-out — 全部保持。
- `DebugBridgeScreenshotTest`（androidTest）保留。
- V3 evidence：`docs/evidence/at_debug_0/EVIDENCE.md` + d1-d6 png 保留。
- 边界保持：DebugBridge ≠ AT Engine public feature；≠ AG protocol；≠ CLI。

---

## 16. Test Host Audit

| Test Host | Validates | V4 Status |
|---|---|---|
| :test-host | ADB backend headless / transport loss / parity / headless input（4 androidTest） | MIGRATED（import 修复；device PENDING） |
| :adb-only-test-host | ADB-only 物理 composition（无 :at-backend-local/:app）+ AdbRuntime lifecycle + DEBT-1（3 androidTest） | MIGRATED（import 修复；device PENDING） |
| :at-shellless-test-host | AT persistent terminal = shell-independent byte-stream（无 sh/backend/adb）（1 androidTest） | MIGRATED（import 修复；device PENDING） |
| :at-cli-consumer-host | 非 AndroidTerminal consumer 全路径（Local → shell-visible adb → uid=2000）（1 androidTest） | MIGRATED（→ composition；test compat 方法保留；device PENDING） |

4 个 test host 全部保留，验证责任有归宿，无未授权删除。V4-5 conformance host 是**补充**（architecture enforcement），不替代上述已有验证。

---

## 17. Test Responsibility Mapping

V3 test source = 27 files；V4 = 28 files（+1 `ATAndroidTest`）。

- 27 个 V3 test 文件全部在 V4 同路径存在（MIGRATED / EXACT_UNCHANGED）。
- 修改仅：import 修复（`DefaultATEngine` / `ATSessionLaunch`）+ `ATContractTest` 构造方式（`ATEngine.empty()` → `DefaultATEngine(null, null)`）。
- 无 V3 test 被删除。验证责任 100% 有归宿。

---

## 18. Evidence / Freeze Mapping

| V3 Evidence/Freeze | V4 Status |
|---|---|
| docs/evidence/V3_Evidence_Index.md | EXACT_UNCHANGED |
| docs/evidence/at_cli_0/*（6 docs） | EXACT_UNCHANGED |
| docs/evidence/at_debug_0/*（EVIDENCE + 6 png） | EXACT_UNCHANGED |
| docs/evidence/at_devapp_01/*（7 png）、at_devapp_02/*（9 png） | EXACT_UNCHANGED |
| docs/evidence/at_human_adb_0/*（EVIDENCE + 5 png）、at_term_0、at_v3_2*、at_v3_debt_1 | EXACT_UNCHANGED |
| docs/freeze/AT.AG-CONSUMER-FREEZE.md（tag AT.AG-CONSUMER-FREEZE-V1 → a6f23d7） | EXACT_UNCHANGED |
| docs/freeze/AT.PRE-EMULATOR-FREEZE.md（tag AT.PRE-EMULATOR-FREEZE-V1 → 7ccb1bf） | EXACT_UNCHANGED |
| docs/handoff/AT_TO_AG_HANDOFF_V1.md + AT_TO_AG_CONSUMER_HANDOFF_V1.md | EXACT_UNCHANGED |

V3 capability freeze 未被误解释为 final ABI freeze；无 capability 遗漏。

---

## 19. Deferred Inventory

以下 V3 DEFERRED 项在 V4 保持 DEFERRED（不计入 MISSING）：

| V3 Deferred | Evidence | V4 Status |
|---|---|---|
| Terminal Emulator / ScreenState / ScreenSnapshot | Canonical V1 §27 DEFERRED | DEFERRED（:at-engine-api 未发明其 API） |
| cursor model / xterm-VT completeness | V3 未实现 | DEFERRED |
| Human-Agent shared session / attach/handoff/takeover | V3 未实现 | DEFERRED |
| push / pull / install / forward / reverse | V3 CLI 未实现 | DEFERRED |
| adb pair \<code\> shorthand | V3 DEFERRED | DEFERRED |
| multiple pairing endpoint UI | V3 DEFERRED | DEFERRED |
| future SSH backend | Canonical §7 DEFERRED | DEFERRED |
| full final AT Contract | AT_CONTRACT_STATUS = DRAFT | DEFERRED |

---

## 20. Missing Items

**MISSING = 0。** 无 V3 有效 production capability 在 V4 缺失且无授权删除。

## 21. Unclassified Items

**UNCLASSIFIED = 0。** 全部 355 个 V3 tracked items 已分类。

---

## 22. Current V4 Debt vs Migration Loss

必须区分三类（本审计结论）：

| 类别 | 内容 | 是 Migration Loss 吗？ |
|---|---|---|
| **V4_CURRENT_BOUNDARY_DEBT** | `:app` / `:at-cli-consumer-host` 仍保留 transitional `:adb-engine` 依赖（for `AdbRuntimeState` / `AdbBootstrapState` / `AdbPairingResultV2` / `AdbConsumerBootstrap` 类型）；PairActivity 仍直接持有 `AdbConsumerBootstrap`；`ATCliExecution` 用 String 而非结构化 pairing 结果 | **NO** — 物理边界未完全闭合（V4-4.1 scope），非能力丢失 |
| **DEVICE_REVALIDATION_PENDING** | 全部 34 项 capability 的 device smoke（L1-L7 / A1-A10 / C1-C6 / H1-H7 / restart gate） | **NO** — implementation 存在，device 未连接（环境 blocker） |
| **MIGRATION_LOSS** | 无 | **N/A** |

三者不混淆：capability 未缺失；边界债和 device 债是 V4-4.1 的明确 scope。

---

## 23. Device Revalidation Pending Matrix

| Group | Items | Status |
|---|---|---|
| Product Local smoke | L1-L7 | PENDING（device 未连接） |
| Product ADB smoke | A1-A10 | PENDING |
| CLI smoke | C1-C6 | PENDING |
| ConsumerHost smoke | H1-H7 | PENDING |
| Restart identity reuse | App + Host | PENDING |
| Runtime ownership device evidence | second-runtime/recovery/transport 观察 | PENDING |

全部归入 **AT.V4-4.1（Consumer Semantic Type Closure + Device Closure）** 或 V4-6 完整 parity。

---

## 24. Migration Completeness Verdict

```text
MISSING_PRODUCTION_CAPABILITY_COUNT      = 0
UNCLASSIFIED_PRODUCTION_ITEM_COUNT       = 0
UNEXPLAINED_PRODUCTION_LOSS_COUNT        = 0
OWNERSHIP_REGRESSION_COUNT               = 0
UNAUTHORIZED_DROP_COUNT                  = 0
```

→ **V3_FULL_MIGRATION_PARITY = PASS_AT_IMPLEMENTATION_LEVEL**

**DEVICE_PARITY = NOT_EVALUATED**（不在本轮 scope；V4-4.1 / V4-6）。

---

## 25. Recommended Next Task

- MISSING = 0 且 UNCLASSIFIED = 0 → 无需 AT.V4-MIGRATION-FIX-0。
- **RECOMMENDED_NEXT = AT.V4-4.1：Consumer Semantic Type Closure + Device Closure**
  - 闭合 `:app` / `:at-cli-consumer-host` 的 transitional `:adb-engine` 依赖（把 `AdbRuntimeState` / `AdbBootstrapState` / `AdbPairingResultV2` 等以 semantic projection 形式收进 `:at-composition-android` 或 `:at-engine-api`）
  - PairActivity / AtCliConsumerTest 收口到 composition 语义
  - device smoke L/A/C/H + restart identity gate
- 本轮不自动开始 V4-4.1（NEXT_OPEN_AUTHORIZED = NO）。

---

## File Coverage Metric（§22 of task）

| Metric | Count |
|---|---|
| V3_TRACKED_TOTAL | 355 |
| V3_PRODUCTION_SOURCE_TOTAL（kt/java/cpp/c/h/hpp, src/main） | 197 |
| V3_TEST_SOURCE_TOTAL（kt/java, src/test+androidTest） | 27 |
| V3_NATIVE_SOURCE_TOTAL（cpp/c/h/hpp 含 BoringSSL headers） | 144 |
| V3_RESOURCE_ASSET_TOTAL（src/main/res + assets） | 6 |
| V3_DOC_EVIDENCE_TOTAL（docs/） | 81 |

| Classification | Count（V3 tracked items） |
|---|---|
| MIGRATED（含 EXACT_UNCHANGED / EXACT_RELOCATED / 修改迁移） | 347 |
| REPLACED_BY_EQUIVALENT | 2（at-engine ATEngine.kt 拆分；AppRuntime/AtCliConsumerHost wiring 归入 composition 计为 production mapping 行，文件级仍为 MIGRATED） |
| TEST_REFERENCE_ONLY | 2（V3 本轮交付 2 个快照文档） |
| INTENTIONALLY_DEFERRED | 0（deferred 项是 capability 级，非 V3 tracked file 丢失） |
| INTENTIONALLY_DROPPED_AUTHORIZED | 0 |
| MISSING | 0 |
| UNCLASSIFIED | 0 |

注：文件级 MIGRATED 347 + TEST_REFERENCE_ONLY 2 + 4 个 at-engine contract 文件（计为 REPLACED_BY_EQUIVALENT/EXACT_RELOCATED，已含在 347 的语义映射内）= 355。分类按"能力归宿"而非 1:1 文件数。
