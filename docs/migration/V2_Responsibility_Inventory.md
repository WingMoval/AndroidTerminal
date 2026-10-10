# V2 Responsibility Inventory — ADB Engine vs AT Engine vs Legacy Glue

> **AT.V3-0 deliverable** — 基于 V2 当前真实代码（`D:\codebank\AndCode\AndroidTerminal-V2`，HEAD `726a441` + 未提交已验证改动）的职责盘点。
>
> 目的：按 Canonical V1 §21/§3.2，把旧 `:at-core` / `:at-runtime` / `:app` / `:atsh` 中每个组件归类为：
> **ADB Engine 职责 / AT Engine 职责 / Legacy Glue（迁移后不保留归属）**。
>
> 这不是"复制旧 module"，而是"识别真实职责 → 归位 V3"。

---

## 0. 盘点方法

- 依据：V2 源码事实（`*.kt` / `*.cpp` / `*.h` / `build.gradle.kts` / `settings.gradle.kts`）+ 已 VERIFIED/CLOSED 报告（AT.C-1 / AT.RUNTIME-2A/2B/2D / AT.ENGINE-AG0 / ATsh.T0/T0.5/T1 / ADB.I0 / TERM.A0/A0.5）。
- 三分类：
  - **ADB ENGINE** = 与 ADB 协议/身份/发现/连接/流/恢复直接相关，与 Terminal 语义无关，独立可复用。
  - **AT ENGINE** = 命令执行语义 / 会话 / 终端域（one-shot + persistent + emulator/screen state + backend 抽象）。
  - **LEGACY GLUE / DEPRECATE** = 历史兼容、诊断、占位、旧命名下的 glue；迁移后不按原样进入 V3。

---

## 1. `:at-core`（Android library, namespace `com.androidterminal`）

### 1.1 Java/Kotlin facade

| 文件 | 真实职责 | 分类 | 目标 V3 归属 |
|---|---|---|---|
| `TerminalRuntime.kt` | 宿主 facing 的 Kotlin facade：connect / disconnect / connectionState / execute(command, timeout) | **AT ENGINE**（one-shot execution 语义面） | AT Engine public surface 的 one-shot 入口（保留为 AT 域；`TerminalRuntime` 名称保留 one-shot 路径） |
| `AdbManager.kt` | 内部 JNI bridge（`nativeShell` / `nativeConnectWireless` / `nativeDisconnect` / `nativeNetDiag` / legacy `nativeExecute`） | **ADB ENGINE（JNI marshal）** + **LEGACY**（nativeExecute = 第二套 handshake 诊断） | ADB Engine 的 JNI 层；legacy `execute` 归 DEPRECATE |
| `TerminalResult.kt` | `ShellOutcome` / `ConnectionState` / `TerminalResult`（one-shot 结构化结果） | **AT ENGINE（结果语义）**，`ConnectionState` 是 ADB 连接状态投影 | AT Engine 结果类型 + ADB Engine 连接状态投影（分层） |

### 1.2 Native C++

| 路径 | 真实职责 | 分类 | 目标 V3 归属 |
|---|---|---|---|
| `adb/crypto/rsa_2048_key.*` | RSA-2048 key gen + android_pubkey 编码 | **ADB ENGINE** | ADB Engine |
| `adb/crypto/x509_generator.*` | X.509 cert 生成 | **ADB ENGINE** | ADB Engine |
| `adb/tls/tls_connection.*` | TLS 1.3 client + EKM | **ADB ENGINE** | ADB Engine |
| `adb/transport/tls_transport.*` | TCP + TLS 封装 transport | **ADB ENGINE** | ADB Engine |
| `adb/connection/adb_connection.*` | CNXN/STLS/packet/stream 状态机 | **ADB ENGINE** | ADB Engine |
| `adb/connection/feature_set.*` | feature negotiation | **ADB ENGINE** | ADB Engine |
| `adb/client/adb_key_store.*` | 持久身份（adbkey / pub / cert） | **ADB ENGINE** | ADB Engine |
| `adb/client/adb_client.*` | AdbClient：connect + one-shot shell（`shell,v2,raw`） | **ADB ENGINE**（one-shot transport 能力） | ADB Engine（ADB Backend 消费） |
| `adb/client/adb_interactive_stream.*` | PROVISIONAL interactive stream（`shell,v2,pty` + winsize） | **ADB ENGINE**（交互流 transport） | ADB Engine（ADB Backend 消费） |
| `adb/pairing/*` | SPAKE2 / AES-GCM / PeerInfo / PairingClient | **ADB ENGINE** | ADB Engine |
| `adb/service/shell_v2_protocol.*` | shell-v2 帧编解码 | **ADB ENGINE** | ADB Engine |
| `adb_protocol/adb_packet.*` / `adb_message.h` / `shell_protocol.h` | packet framing / 消息结构 | **ADB ENGINE** | ADB Engine |
| `transport/tcp_transport.*` / `adb_transport.h` | TCP transport 抽象 | **ADB ENGINE** | ADB Engine |
| `at_core/at_core.*` | AT Core facade（`Core::connect/exec_shell`，one-shot 结果语义） | **AT ENGINE**（执行语义） | AT Engine 的 one-shot 执行内核（消费 ADB Engine） |
| `runtime/MinimalRuntime.*` | process-scoped runtime，唯一持有 Core，序列化 connect/disconnect/execute | **AT ENGINE**（执行生命周期） | AT Engine 的 runtime（consumes ADB Engine） |
| `jni/terminal_jni.cpp` | JNI bridge + legacy 第二套 handshake | **ADB ENGINE（JNI）** + **LEGACY**（第二套 handshake，AGENTS.md 已记债） | ADB Engine JNI；legacy handshake 归 DEPRECATE |
| `network_probe/network_probe.cpp` | 网络诊断 probe | **LEGACY DIAGNOSTIC** | 不进入 V3 生产（可留 V2 作 evidence） |
| `auth/auth_placeholder.h` / `shell/shell_placeholder.h` | dead placeholder headers | **LEGACY**（AGENTS.md 已记 4 个 dead header） | 不迁移 |
| `third_party/boringssl/*` | BoringSSL 静态库 + headers | **ADB ENGINE（依赖）** | ADB Engine 依赖（vendored） |

---

## 2. `:at-runtime`（Android library, namespace `com.androidterminal.runtime`）

| 文件 | 真实职责 | 分类 | 目标 V3 归属 |
|---|---|---|---|
| `AtRuntime.kt` | 引擎对外公共边界 facade：initialize / checkReadiness / discover / connectAuto / connect / disconnect / execute / recovery（readiness/discovery/endpoint/recovery/lifecycle） | **AT ENGINE（执行生命周期层）+ ADB ENGINE（discovery/endpoint/readiness/recovery 是 ADB 侧事实）** | 拆为两层：ADB Engine Runtime（discovery/endpoint/readiness/recovery）+ AT Engine public boundary（execute/session 语义）。**不整体搬迁**（Canonical §21） |
| `AtConnectionRequest.kt` | explicit endpoint 输入（host/port/keyDir） | **ADB ENGINE**（连接请求） | ADB Engine |
| `AtEndpoint.kt` / `AtEndpointSource.kt` / `AtDiscoveredService.kt` | 发现端点数据 | **ADB ENGINE** | ADB Engine |
| `DiscoveryBackend.kt` / `AndroidNsdDiscoveryBackend.kt` | mDNS 发现接口 + NSD 实现 | **ADB ENGINE** | ADB Engine |
| `WirelessDebuggingReadinessBackend.kt` / `AndroidWirelessDebuggingReadinessBackend.kt` | WD 状态只读检测 | **ADB ENGINE** | ADB Engine |
| `EndpointStore.kt` | 端点缓存/过期/rediscover | **ADB ENGINE** | ADB Engine |
| `ConnectionIntent.kt` / `RecoveryPolicy.kt` / `RecoveryCoordinator.kt` / `LossClassifier.kt` | 连接恢复机制 | **ADB ENGINE** | ADB Engine |
| `AtExecutionResult.kt` / `AtExecutionOutcome.kt` | one-shot 执行结果（Runtime-owned 镜像） | **AT ENGINE（结果语义）** | AT Engine |
| `AtReadinessState.kt` / `AtTransportState.kt` / `AtRuntimeDiagnostic.kt` | readiness / transport 状态 + 诊断 | **ADB ENGINE（transport/readiness）+ AT ENGINE（diagnostic 面）** | 分层：transport/readiness 归 ADB Engine；diagnostic 归 AT Engine public |

> **结论**：`:at-runtime` 是"AT 外壳 + ADB 内芯"的混合体。Canonical §21 明确要求拆分，不是整体搬入 V3。

---

## 3. `:app`（AT.apk, namespace `com.androidterminal`）

| 文件 | 真实职责 | 分类 | 目标 V3 归属 |
|---|---|---|---|
| `MainActivity.kt` | 散按钮产品 UI（connect/shell/diag/discover） | **AT ENGINE（consumer）→ V3 `:app`** | V3 App（Human UI consumer）；按钮 UI 待 AT.HUMAN-* 重建 |
| `AtWirelessConnector.kt` | Host 便利 wrapper（keyDir 派生 + initialize + connectAuto/connect） | **AT ENGINE（consumer helper）** | V3 App consumer helper（演进为走 AT Engine public boundary） |
| `TerminalService.kt` | 占位（未 wired） | **LEGACY / DEFERRED** | 不迁移；V3 视需要重建 |
| `ProbeReceiver.kt` | 诊断 receiver | **LEGACY DIAGNOSTIC** | 不进入 V3 生产 |
| （已删）`WirelessAdbDiscovery.kt` | 旧 mDNS 实现（已被 :at-runtime 取代） | **SUPERSEDED** | 不迁移 |

---

## 4. `:atsh`（evidence host, namespace `com.androidterminal.atsh`）

| 文件 | 真实职责 | 分类 | 目标 V3 归属 |
|---|---|---|---|
| `shell/LocalShellExecutor.kt` + `ShellResult.kt` | Local one-shot（ProcessBuilder `/system/bin/sh -c`） | **AT ENGINE（Local Backend 的 one-shot 语义）+ LOCAL EXECUTION** | V3 `:at-backend-local` 的 one-shot 实现（基于 V2 evidence 重写为 backend contract） |
| `pty/LocalPtySession.kt` + `pty/PtyNative.kt` + `cpp/local_pty.*` | Local PTY（PROVISIONAL）| **LOCAL EXECUTION（Local Backend 底层）** | V3 `:at-backend-local`（PTY provider）；**不是直接原样宣布为 API**（TERM.A0 §14） |
| `MainActivity.kt` | evidence UI | **LEGACY（证据 host）** | 不进入 V3 生产（留 V2 作 evidence） |

> `:atsh` 依赖方向 = 零 AT 依赖（framework + bionic），验证了 Local execution 可独立存在 → 正好支撑 V3 `:at-backend-local` 独立模块。

---

## 5. `:at-test-host` / `:at-ag-handoff-host`（independent consumers）

| 文件 | 真实职责 | 分类 | 目标 V3 归属 |
|---|---|---|---|
| `at-test-host` TestHostController | 独立 consumer（经 AtRuntime）| **AT ENGINE consumer（验证 embeddability）** | V3 `:test-host`（consumer） |
| `at-ag-handoff-host` AgHandoff | AG-like consumer（仅 :at-runtime 稳定 contract）| **AT ENGINE consumer（AG handoff v0 验证）** | V3 未来 AG TerminalTool 的边界参考；AG0 稳定 contract 继续有效（按当时语义） |

---

## 6. 分类汇总（Migration 输入）

| 分类 | 组件 |
|---|---|
| **ADB ENGINE** | adb_key_store / rsa_2048_key / x509 / tls_connection / tls_transport / adb_connection / feature_set / adb_client（one-shot + interactive stream）/ pairing / shell_v2 / adb_protocol / tcp_transport / AtConnectionRequest / AtEndpoint / DiscoveryBackend / NsdDiscoveryBackend / WD readiness backend / EndpointStore / ConnectionIntent / RecoveryCoordinator / RecoveryPolicy / LossClassifier / AtReadinessState(transport 部分) / AdbManager(JNI) / terminal_jni(JNI) / boringssl |
| **AT ENGINE** | TerminalRuntime（one-shot facade）/ TerminalResult / at_core::Core（exec 语义）/ MinimalRuntime / AtExecutionResult / AtExecutionOutcome / AtRuntimeDiagnostic(诊断面) / AtRuntime(execute 语义部分) |
| **LOCAL EXECUTION（→ AT Backend）** | LocalShellExecutor / LocalPtySession / PtyNative / local_pty.* |
| **CONSUMER / PRODUCT** | MainActivity / AtWirelessConnector / TestHostController / AgHandoff |
| **LEGACY / DEPRECATE / SUPERSEDED** | AdbManager.execute（legacy 第二 handshake）/ terminal_jni 第二 handshake / network_probe / ProbeReceiver / TerminalService / WirelessAdbDiscovery / auth_placeholder / shell_placeholder / atsh MainActivity（evidence UI） |

---

## 7. 迁移要点（对应 Canonical §21）

1. **ADB Engine Extraction（AT.V3-1 主线）**：把 §6 ADB ENGINE 类从 `:at-core`/`:at-runtime` 中按真实职责拆出，独立为 V3 `:adb-engine`。
2. **AT Engine 执行语义**：one-shot 结果/执行语义（TerminalRuntime / at_core::Core / AtExecutionResult）进入 V3 `:at-engine`。
3. **Local Backend**：从 `:atsh` 证据迁移为 V3 `:at-backend-local`，且**按 backend contract 重写**，非原样拷贝（PROVISIONAL 接口不得直接冻结）。
4. **Legacy 不迁**：第二 handshake、network_probe、dead headers、占位 service 等留在 V2 作为 evidence/回归 oracle，不进入 V3 生产。
5. **命名纪律**：Canonical §6——历史"AT Engine"（=AT Runtime + AT Core）按当时语义理解，不得自动等同 Canonical AT Engine。