# AT.V3-1 — ADB Engine Extraction / Migration Baseline

> 本轮任务（Canonical V1 §22/§23 + AT.V3-1 指令）：把 V2 中已 VERIFIED、真实属于 ADB 的能力，按职责迁移到 V3 独立 `:adb-engine`，通过分批编译 + 真机运行时验证 + 最终 parity，证明 ADB Engine 脱离 Legacy AT 体系后独立成立。
>
> 最终判定：**AT.V3-1 = COMPLETE（native + JNI + Kotlin 迁移完成；真机 parity 关键项 PASS；部分项 COMPILED）**
>
> **ADB_ENGINE_EXTRACTION = COMPLETE**
> **ADB_ENGINE_INDEPENDENCE = VERIFIED（零 :at-engine/UI/AG 依赖）**
> **IDENTITY / CONNECT / ONESHOT / REUSE / INTERACTIVE_PRIMITIVE = PARITY PASS（真机）**
> **PAIRING / DISCOVERY / RECOVERY = COMPILED（真机受配对 UI / 后续 slice 门控）**

---

## 1. Scope

- **IN SCOPE**：按 §5 分批（A 底层 / B TLS+Connection / C Identity+AdbClient+shell-v2 / D Interactive Stream / E Pairing / F Kotlin ADB Runtime / G JNI 边界）把 V2 ADB 能力迁入 V3 `:adb-engine`；分批 compile/link checkpoint；真机 parity（identity/connect/one-shot/reuse/interactive）；更新 Migration Manifest；本报告 + 交付。
- **OUT OF SCOPE**（明确未做）：AT Engine public API、TerminalSession、Emulator、Screen State、Local Backend、ADB Backend、AT one-shot result domain、UI、AG TerminalTool、`AT.CONTRACT-0` 及后续 Track。

## 2. Git / 环境基线

```text
V3 HEAD  : d11cd3c（AT.V3-0）
V3 status: 开始含 1 个用户放置的 untracked canonical 副本（内容与已落盘版等价）
V2 HEAD  : 726a441（只读；本轮未改动）
设备     : APLMUT4312005935 / ALI-AN00 / Android 15 / arm64-v8a（USB authorized）
无线端口 : 127.0.0.1:39019（adbd 无线 connect 端口，uid 2000）
```

## 3. 迁移执行（按 Batch）

### Batch A — ADB 底层基础 → COMPILED→MIGRATED

- 迁入 `:adb-engine/src/main/cpp/`：`adb_protocol/adb_message.h`、`adb_packet.cpp`、`shell_protocol.h`、`transport/adb_transport.h`、`tcp_transport.*`、`adb/crypto/rsa_2048_key.*`、`x509_generator.*`、`third_party/boringssl/`。
- CMake 目标 `adbd_engine`（C++17，链接 BoringSSL + log + z）。
- **compile/link checkpoint PASS**（`:adb-engine:externalNativeBuildDebug`）。

### Batch B — TLS + Connection → MIGRATED

- 迁入：`adb/tls/tls_connection.*`、`adb/transport/tls_transport.*`、`adb/connection/adb_connection.*`、`feature_set.*`、`adb/client/adb_key_store.*`（`adb_connection.cpp` 依赖 key_store，故一并迁入）。
- **compile/link PASS**；协议状态机/握手语义未改动。

### Batch C — Identity + AdbClient + shell-v2 → MIGRATED

- 迁入：`adb/client/adb_client.*`、`adb/service/shell_v2_protocol.*`。
- **Golden Path 恢复**（真机）：key/identity → connect → TLS/CNXN → `shell,v2,raw` → stdout/stderr/exit。
- `AdbClient` 属 ADB transport/client 能力；`execute(command)→AT result` 语义**未**迁入（留 AT track）。

### Batch D — Interactive Stream Primitive → MIGRATED

- 迁入：`adb/client/adb_interactive_stream.*`。
- 仅恢复 **ADB interactive stream primitive**（open/writeStdin/read/resize/closeStdin/close）；**非** TerminalSession / ADB Backend。
- 为复用同一 ONLINE 连接，给 `AdbClient` 加 `connection()` accessor（非行为变更）。

### Batch E — Pairing → COMPILED

- 迁入：`adb/pairing/pairing_aes_gcm.*`、`pairing_auth.*`、`pairing_client.*`、`pairing_packet.h`、`pairing_peerinfo.h`。
- **compile/link PASS** + JNI 暴露（`AdbEngine.pair`）；SPAKE2/AES-GCM/PeerInfo 协议未改。
- 真机 pairing 需无线调试**配对 UI（6 位码）**，本轮配对端口未监听 → **PARITY = COMPILED / BLOCKED_BY_PAIRING_UI**（不伪称 VERIFIED）。

### Batch F — Kotlin ADB Runtime 能力 → COMPILED（部分 MIGRATED）

- 从旧 `:at-runtime` 拆出 ADB-domain 类型迁入 `:adb-engine`（`com.adb.engine`）：
  - `AdbConnectionRequest`（← AtConnectionRequest）
  - `AdbEndpoint` / `AdbEndpointSource` / `AdbDiscoveredService`（← AtEndpoint 等）
  - `AdbRuntimeBackends.kt`（`AdbDiscoveryBackend` / `AdbAndroidNsdDiscoveryBackend` / `AdbWirelessDebuggingReadinessBackend` / `AdbAndroidWirelessDebuggingReadinessBackend` / `AdbWirelessDebuggingState`）
  - `AdbEndpointStore` / `AdbConnectionIntent` / `AdbRecoveryPolicy`（← EndpointStore / ConnectionIntent / RecoveryPolicy）
  - `AdbRecoveryCoordinator`（← RecoveryCoordinator，**改走 AdbEngine**，不再依赖 AT `TerminalRuntime`）
- `LossClassifier` 的 AT-result 耦合（`AtExecutionResult`）本轮未迁——属 AT result domain，留 AT track（记录原因：recovery 触发判据绑定 AT 结果语义）。
- `AtRuntime.kt` **未整体迁移**（§5 Batch F 要求）；`execute(command) semantics` 属 Canonical AT Engine，留待后续。

### Batch G — JNI/Kotlin Boundary Consolidation → MIGRATED

- 新增 canonical JNI：`jni/adb_engine_jni.cpp`（连接 / disconnect / state / executeShell / interactive / pair），**剥离** legacy 第二 handshake、network_probe、dead headers、AT facade。
- Kotlin bridge：`internal/AdbNative.kt`（`external fun`，与 JNI symbol 一一对应）。
- 结果类型：`AdbShellResult`（ADB-domain：status/stdout/stderr/exitCode，**无** AT outcome 语义）。
- 状态投影：`AdbConnectionState`（native `adb::ConnectionState` 8 态）。
- Facade：`AdbEngine.kt`（connect/disconnect/connectionState/executeShell/openInteractive/pair）。
- **JNI ABI 静态门禁 PASS**：`llvm-readelf` 导出 11 个 `Java_com_adb_engine_internal_AdbNative_*` symbols，与 Kotlin externals 一一匹配（mangled 0）。

## 4. 独立性审计（§11）→ VERIFIED

- `:adb-engine/build.gradle.kts` **零依赖**（无 `:at-engine` / `:at-backend-*` / `:app` / `:test-host`）。
- 源码扫描：无 `com.at.engine` / `TerminalSession` / `Emulator` / `ScreenState` / `AndroidAgent` / `com.androidterminal`（命中均为注释/文档字符串，非 import）。
- 依赖方向正确：`:at-backend-adb → :adb-engine`（consumer→provider）；`:app` 不直接依赖 `:adb-engine`。

## 5. Level 3 真机 Parity（设备 APLMUT4312005935）

`AdbEngineDeviceTest`（`:adb-engine/src/androidTest`，注入已信任 PC key 至测试 keyDir，连 `127.0.0.1:39019`）：

| 项 | 结果 |
|---|---|
| **A. Identity**（key load，非 generate） | **PASS**（注入 PC key，`id` 走通） |
| **B. Golden Chain**（TCP→STLS→TLS1.3→post-TLS CNXN→ONLINE） | **PASS**（connect 成功，state=ONLINE） |
| **C. One-shot**（`shell,v2,raw`） | **PASS**（`id`→`uid=2000(shell)`；exit 0；`exit 7`→exitCode=7；`echo err >&2`→stderr 分离；组合输出） |
| **D. Connection reuse** | **PASS**（同一连接两次 execute，`REUSE_OK`） |
| **E. Interactive primitive**（`shell,v2,pty`） | **PASS**（open / persistent I/O / PTY_FIRST+SECOND / resize / close） |
| **F. Pairing** | **COMPILED / BLOCKED_BY_PAIRING_UI**（需设备配对码 UI） |
| **G. Discovery/readiness/recovery** | **COMPILED**（Kotlin 已迁移；真机 recovery 触发需 transport-loss 场景，留后续 slice） |

- 测试结果：`com.adb.engine.AdbEngineDeviceTest` 真机 **OK (2 tests)**。
- 设备清理：临时 key / 测试 APK 已删；`adb_wifi_enabled` 恢复为 0。

## 6. 最终 Build

```text
.\gradlew.bat clean assembleDebug
BUILD SUCCESSFUL（6 modules；:adb-engine 含 native + androidTest）
```

- `libadbd_engine.so` 正常产出；11 个 JNI symbols 与 Kotlin 匹配。

## 7. 本轮修改清单（V3）

| 类别 | 内容 |
|---|---|
| `:adb-engine` native | 迁移 `adb_protocol/transport/crypto/tls/connection/client/service/pairing` + `third_party/boringssl` + 新 `jni/adb_engine_jni.cpp` + `CMakeLists.txt` |
| `:adb-engine` Kotlin | `AdbEngine.kt` / `AdbShellResult.kt` / `AdbConnectionState.kt` / `AdbConnectionRequest.kt` / `AdbEndpoint.kt` / `AdbInteractiveStream.kt` / `AdbRecovery.kt` / `AdbRecoveryCoordinator.kt` / `AdbRuntimeBackends.kt` / `internal/AdbNative.kt` |
| `:adb-engine` build | Gradle native + androidTest 配置 |
| `:adb-engine` test | `AdbEngineDeviceTest.kt` + androidTest manifest |
| docs | `Migration_Manifest_V2_to_V3.md` 更新；`AT.V3-1` 报告（本文） |
| V2 | **未修改（只读）** |

## 8. 对原 V2 Classification 的修正

- 无职责边界修正；一处**实现边界修正**：旧 `RecoveryCoordinator` 依赖 AT `TerminalRuntime`——本轮按 Canonical §21 拆分，`AdbRecoveryCoordinator` 改走 `AdbEngine`（证明 ADB recovery 可脱离 AT 独立）。
- `LossClassifier` 因绑定 AT result 类型（`AtExecutionResult`）**未迁**，记录为 AT result domain 耦合，留 AT track 解耦。

## 9. Final Verdict

```text
AT.V3-1 = COMPLETE

ADB_ENGINE_EXTRACTION     = COMPLETE（Batch A–G 全部迁移）
ADB_ENGINE_INDEPENDENCE   = VERIFIED（:adb-engine 零 :at-engine/UI/AG 依赖）
NATIVE_BUILD              = PASS（libadbd_engine.so；compile/link/ABI 全过）
KOTLIN_RUNTIME            = COMPILED（ADB-domain facade + runtime types）

IDENTITY_PARITY           = PASS（真机；key load）
CONNECT_PARITY            = PASS（真机；Golden Chain + ONLINE）
ONESHOT_PARITY            = PASS（真机；stdout/stderr/exitCode/失败命令/组合）
INTERACTIVE_PRIMITIVE     = PASS（真机；pty read/write/resize/close）
PAIRING_PARITY            = COMPILED / BLOCKED_BY_PAIRING_UI（未伪称 VERIFIED）
DISCOVERY_PARITY          = COMPILED（未真机）
RECOVERY_PARITY           = COMPILED（未真机）

V2_MODIFIED               = NO（只读）
AT_TRACK_CHANGED          = NO（未触 :at-engine/:at-backend/*/:app/:test-host 逻辑）

MIGRATION_STATUS          = ADB ENGINE EXTRACTED；native+JNI = MIGRATED（真机 parity）；
                            pairing/discovery/recovery = COMPILED（待后续 slice / 配对 UI）
NEXT                      = AT.CONTRACT-0（Minimum Contract v0 Draft）→ AT.PERSIST-*（需 Controller 授权）
```

**结论**：AT.V3-1 完成。V3 `:adb-engine` 已从 V2 按职责抽取并独立成立——native ADB 全链路（identity/TLS/connection/one-shot/interactive/pairing）+ canonical JNI/Kotlin facade 全部迁移，真机验证 connect/one-shot/reuse/interactive 关键 parity PASS；`:adb-engine` 零 AT/UI/AG 依赖。V2 全程只读未改。

本轮未开始 `AT.CONTRACT-0` / `AT.PERSIST-0` / `AT.PERSIST-1` / `AT.EMULATOR-0` / `AG.TERMINAL-*`。任务结束，STOP。