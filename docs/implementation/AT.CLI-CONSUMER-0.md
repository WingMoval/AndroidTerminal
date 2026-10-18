# AT.CLI-CONSUMER-0 — Reusable AT CLI Consumer Capability（实现）

> 状态：**COMPLETE**（全 gate PASS，单次原子提交）
> 基线：V3 `7ccb1bf`（AT.PRE-EMULATOR-FREEZE-V1）← `700ca3b`（POSTCLI-0）← `9fe62fa`（CLI-0.2）
> 设备：HONOR ALI-AN00 `APLMUT4312005935`（Android 15）
> 约束遵守：只写 V3；V2 / AndroidTerminal 只读；不重开冻结 ADB/AT 边界；不改 ADB wire 协议；无外部 adb 二进制 / 5037 server / native ELF exec。

---

## 0. 目标

把 shell 里可见的 `adb` CLI 能力，从 **AndroidTerminal 产品 app（`:app`）私有的 product capability**，抽成一个**可复用的 AT consumer capability（`:at-cli` 模块）**，并证明一个**独立的（非 `:app` 的）第三方 consumer host** 能拿到完整的 **Local Terminal → shell 可见 `adb` → uid=2000(shell)** 环境。

一句话：**别人（第三方 app）也能用上 AT 的 `adb` CLI，且用的是自己独立的身份。**

---

## 1. 现状检查（§6 CLI 全审计）

提取前对 `:app` 私有 CLI 能力做了全量 owner 审计：

| 能力 | 原 owner（`:app`） | 性质 | 去向 |
|---|---|---|---|
| CLI 前端（app_process 入口） | `AdbCliMain`（object） | 可复用 | → `:at-cli` `AtCliMain` |
| 协议（帧 / HELLO / ONESHOT） | `AdbCliProtocol` | 可复用 | → `:at-cli` |
| 参数解析 | `AdbCliArgs` + `AdbCliParseResult` | 可复用 | → `:at-cli`（JVM 单测随迁） |
| UDS 桥（认证 / 路由 / 会话） | `AdbCliBridgeServer` | 可复用（host 无关） | → `:at-cli`（经 SPI 解耦） |
| shell `adb()` 函数生成 | `LocalSessionBootstrap` | 可复用 | → `:at-cli` `LocalCliBootstrap` |
| native `cli_bridge_server` + `native_env` | `adb_cli.cpp` / `cli_bridge_server.cpp` / `native_env.cpp` | 可复用 | → `:at-cli` `atcli_env`（JNI 改名 `Java_com_at_cli_*`） |
| **Human 状态投影**（`[adb] ...` fan-out 到绑定 Local session） | `AdbCliStatusHub` | **产品/UI 专属** | 留 `:app` |
| **Frontend 装配**（bootstrap 文件写入等） | `CliFrontendProvisioner` | 产品专属 | 留 `:app` |
| **App 专属 native**（setenv + UDS test helper） | `app_env.cpp`（新建） | 产品专属 | 留 `:app` `atapp_env` |
| 旧 ELF 前端 | `adb_cli.cpp`（SELinux 阻止 private-ELF exec，RC=126） | 死路径 | 移出构建（SELECTED_CLI_ENTRYPOINT=APP_PROCESS） |

**核心结论**：CLI 桥 / 协议 / 前端 / 参数 / shell 函数生成是**纯可复用**的；只有 Human 状态投影 + 产品装配是产品专属。抽取的**接缝（seam）**是：桥不再直接调 `AppRuntime`，而是调一个 host 提供的 **`ATCliExecution` SPI**。

---

## 2. 抽取设计

### 2.1 新模块 `:at-cli`（`com.androidlibrary`，package `com.at.cli`）

依赖：`:at-engine` + `:at-backend-local` + `:at-backend-adb` + `:adb-engine`。**不依赖 `:app`。**

内容（从 `:app` 迁出，package `com.androidterminal.app.cli` → `com.at.cli`）：
- `AtCliMain`（原 `AdbCliMain` object，app_process 前端）
- `AdbCliProtocol` / `AdbCliArgs` / `AdbCliParseResult`
- `AdbCliBridgeServer`（UDS 桥，**改为接受 `ATCliExecution`**）
- `LocalCliBootstrap`（原 `LocalSessionBootstrap`，shell `adb()` 函数生成，`mainClass` 构造参数化）
- `ATCliExecution`（**新 SPI**）
- native `atcli_env`（`cli_bridge_server.cpp` + `native_env.cpp`，JNI 符号 `Java_com_at_cli_*`）

### 2.2 接缝 SPI：`ATCliExecution`

```kotlin
interface ATCliExecution {
    fun adbEngine(): ATEngine
    fun ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean
    fun ensureReady(onStatusLine: (String) -> Unit): Boolean  // default
    fun adbIdentityDir(): String
}
```

**桥（`:at-cli`）host 无关**：它只做 UDS 认证（peer uid == 进程 uid）+ 帧路由 + 把 ONESHOT/interactive 命令交给 `ATCliExecution`。**桥从不创建第二个 AdbRuntime / AT Engine / identity** —— 只调 host 提供的执行核心。

### 2.3 两个 host 各自实现 SPI

| Host | SPI 实现 | identity 目录 | 说明 |
|---|---|---|---|
| **`:app`**（产品） | `AppCliExecution` | `AppRuntime.adbIdentityDir()` | 路由进产品自己的 AppRuntime/AdbRuntime/Human 状态投影 |
| **`:at-cli-consumer-host`**（第三方） | host 自己的组合根 | `filesDir/at-cli-identity`（**自己私有**） | 自己的 AdbRuntime + `:at-cli` 桥 + Local/ADB ATEngine，**不依赖 `:app`** |

### 2.4 新 consumer host `:at-cli-consumer-host`（app）

- 包 `com.androidterminal.atclihost`
- 组合根 `AtCliConsumerHost`：自己的 `AdbRuntime` + `filesDir/at-cli-identity` + `:at-cli` 桥 + Local/ADB `ATEngine`
- 测试 `AtCliConsumerTest`：C1–C10 + 身份门禁
- 依赖：`:at-cli` + backends + `:adb-engine`，**不依赖 `:app`**（TEST_CONSUMER_DEPENDS_ON_APP=NO）

### 2.5 `:app` 改为消费 `:at-cli`

- 依赖 `:at-cli`
- 新增 `AppCliExecution`（`:app` 的 SPI 适配器，做 Human 状态投影）
- `AppRuntime` / `MainActivity` 改用 `:at-cli` 的类
- `AdbCliStatusHub` + `CliFrontendProvisioner` + `NativeEnv` 留在 `:app`
- app native 瘦身到 `atapp_env`（`app_env.cpp`：setenv + UDS test helper only）
- 旧 ELF 前端 `adb_cli.cpp` + asset 移出构建

---

## 3. 关键 bug 修复（抽取过程中暴露）

### 3.1 `AppCliExecution` 丢失 fast-path "connected" backfill（回归）

**现象**：产品 Lifecycle L2/L4/L8 + LiveStatus S3 失败——"connected" 状态行缺失。

**根因**：旧 `AdbCliBridgeServer.ensureReady` 有 fast-path backfill：

```kotlin
val firstSubscribe = !AppRuntime.isAdbStatusSubscribed(sid)
AppRuntime.subscribeAdbStatus(sid)
if (firstSubscribe && AdbRuntime.readinessState().isReady) {
    emitStatus(fd, sid, "connected")   // 已 READY 时回填，避免 session 订阅晚于 transition
}
```

抽取时 `AppCliExecution` 在 fast-path（已 READY）直接 `return true`，丢了 backfill。

**修复**：`AppCliExecution.ensureReady` 补回 firstSubscribe + fast-path backfill。

### 3.2 状态投影双发（S3 "checking" 重复）

**现象**：S3 `noDuplicate` 失败——"checking connection..." 出现 2 次。

**根因**：初版 `AppCliExecution.emitBoth` 同时投到 Host 投影**和** T_STATUS fallback，session 看到两遍。

**修复**：恢复旧 `AdbCliBridgeServer.emitStatus` 的**单投影语义**——

```kotlin
private fun emitStatus(sid: Int?, onStatusLine: (String) -> Unit, line: String) {
    if (sid != null && AppRuntime.isAdbStatusSubscribed(sid)) {
        AppRuntime.emitAdbCommandStatus(sid, line)   // Host 投影（XOR）
    } else {
        onStatusLine(line)                            // T_STATUS fallback
    }
}
```

### 3.3 consumer host 测试 `runCmd` 冷路径 pre-output 误判

**现象**：C2 `adb shell id` 只拿到 echo，拿不到远程 `uid=2000`。

**根因**：每个 instrumentation 是新进程，AdbRuntime 冷态；第一条 adb 命令触发完整冷路径（WD enable + discover + connect），**输出到达前有静默期**，原 `runCmd` 的稳定性检测在 pre-output 静默期提前 break。

**修复**：
1. `coreFunctionalGates` 开头 **pre-warm**（先 `ensureReady` 把 AdbRuntime 带到 READY，镜像产品真实"已连接"态）
2. `runCmd` 改为"**见到输出后**再等稳定尾"（pre-output 静默期不 break）

### 3.4 `CONSUMER_KEY_COPIED` 误报（comment 标签）

**现象**：consumer host 新生成的 key 被判 `CONSUMER_KEY_COPIED=YES`。

**根因**：`AdbEngine` 给**每个**生成的 key 写死 comment 标签 `androidterminal@app`；旧检测 `pub.readText().contains("androidterminal")` 匹配到的是 comment，不是 key 材料。

**修复**：改为**对比公钥主体**（comment 前的真正 key 材料）：

```kotlin
val hostBody = pub.readText().trim().split(" ")[0]
val appBody  = File(appPubPath).readText().trim().split(" ")[0]
val copied = hostBody.isNotEmpty() && hostBody == appBody
```

实测：consumer `QAAAAO/zNzLx...` ≠ app `QAAAAPsYTM3N...` → **NO**（全新生成）。

---

## 4. 真机验证（设备 `APLMUT4312005935`）

### 4.1 Consumer Host 功能门禁 C1–C10

| Gate | 结果 | 证据 |
|---|---|---|
| C1 Local `id` | **PASS** | uid=10381(u0_a381)（consumer host 自身 UID，**非** 2000） |
| **C2 `adb shell id`** | **PASS** | **uid=2000(shell)**（核心：第三方 host 真能用 adb） |
| C3 one-shot | **PASS** | `CLI_ONE_SHOT_OK` |
| C4 interactive | **PASS** | uid=2000(shell) |
| C5 cwd+env 连续 | **PASS** | /data/local/tmp + AT_CLI_TEST=OK |
| C6 前台 stdin | **PASS** | AT_CLI_STDIN_OK |
| C7 Ctrl-C | **PASS** | sleep 被终止，AFTER_CTRL_C |
| C8 resize | **PASS** | 30×100 反映到远程 PTY |
| C9 返回本地 | **PASS** | uid=10381 再现 |
| C10 session 连续 | **PASS** | LOCAL_SESSION_RECREATED=NO |
| ADB_IS_FUNCTION | true | `adb is a function`（shell 可见函数） |

### 4.2 架构门禁（冻结边界不重开）

| Gate | 结果 |
|---|---|
| SECOND_AT_ENGINE | NO（桥路由进 host 单一 ATEngine） |
| SECOND_ADB_RUNTIME | NO（AdbRuntime 是进程单例） |
| EXTERNAL_ADB_BINARY | NO（app_process AtCliMain，无 ELF） |
| ADB_SERVER_5037 | NO（无 AOSP adb server） |
| NESTED_ENGINE_DEADLOCK | NO（Local→adb→ADB 经桥线程异步） |
| CLI_SHARED_AT_RUNTIME | PASS |

### 4.3 身份门禁（§26 偏差关闭）

| Gate | 结果 | 证据 |
|---|---|---|
| CONSUMER_IDENTITY_ORIGIN | SELF_GENERATED | 公钥主体 `QAAAAO/zNzLx...`（全新） |
| **CONSUMER_KEY_COPIED** | **NO** | 公钥主体 ≠ AndroidTerminal app 的 `QAAAAPsYTM3N...` |
| CONSUMER_IDENTITY_DIR | `/data/user/0/com.androidterminal.atclihost/files/at-cli-identity` | host 自己私有 |
| CONSUMER_IDENTITY_REUSE_AFTER_RESTART | **PASS** | ADB_STATE=READY（全新 key 重启复用） |
| **FIRST_USE_PAIRING** | **PASS** | 手动配对端到端（见 4.4） |

### 4.4 手动配对端到端演练（FIRST_USE_PAIRING）

**正式配对 surface**：`AdbRuntime.discoverPairing` → `AdbEngine.pair(host, port, code, identityDir)` → `connectAuto` → READY。

演练步骤（用户手动 + 自动化结合）：
1. 清空 consumer host `at-cli-identity`（恢复全新身份，`FIRST_USE_STATE=UNPAIRED/NEW_IDENTITY`）
2. 用户在设备 **Settings → 无线调试 → 使用配对码配对** 生成 6 位码 + 端口（`42433` / `975708`）
3. 把配对码写入 consumer host `atcli_pairing_code.txt`
4. 跑 `firstUsePairing`：
   - `discoverPairing(10_000)` 发现配对服务
   - `AdbEngine.pair("127.0.0.1", 42433, "975708", at-cli-identity)` 用 **consumer host 自己全新的 key** 配对
   - `connectAuto` → **READY**
5. 结果：**`FIRST_USE_PAIRING=PASS`**，`PAIRING_DEVICE_GUID=adb-APLMUT4312005935-J4nb5z`
6. 复跑 C2/C4：全新 key 也能 `adb shell id`=uid=2000 ✓

**结论**：consumer host 用**自己独立的 SELF_GENERATED 身份**走正式配对 surface 完成手动配对，§26 的 SELF_GENERATED/NO 要求**完全满足**。

> 注：`firstUsePairing` 在"设备 UI 配对弹窗已关 / 码过期"后重跑会失败（"pairing service must be discoverable"）——这是**预期的安全行为**（配对服务只在弹窗打开时可达），非代码缺陷。配对本身已在 4.4 第 4 步证明。

### 4.5 产品 `:app` 回归（提取后无回归）

| 套件 | 结果 |
|---|---|
| AdbCliBridgeTest（C1-C15） | **13/13** |
| AdbCliLifecycleTest（L1-L11） | **11/11**（L2/L4/L8 backfill 修复后过） |
| AdbCliLiveStatusTest（S1-S9） | **9/9**（S3 单投影修复后过） |
| AdbCliInteractiveTest（I1-I16） | **16/16** |

### 4.6 JVM 单元测试 + 构建

- `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli` / `:adb-engine` `testDebugUnitTest`：**PASS**
- `:app:assembleDebug` + `:at-cli-consumer-host:assembleDebug`：**BUILD SUCCESSFUL**
- `:at-cli:testDebugUnitTest`（含迁出的 `AdbCliArgsTest`）：**PASS**

> ADB Golden Chain 真机验证由 consumer host C2/C4 门禁经真实 `AdbEngine.connect → shell → uid=2000` 覆盖。独立 `AdbEngineDeviceTest` 需手动 host/port/keyDir 参数（setup 前置），非自动回归 oracle。

---

## 5. Consumer 隔离门禁（§25）

- consumer host dex 中 `com/androidterminal/app` 类引用 = **0**（唯一 1 处 `com.androidterminal.app` 字符串是 `AtCliMain.resolveSocketPath()` 的 socket 路径 fallback 字面量，**非类依赖**）
- native lib 集合：`libadbd_engine.so` / `libat_local_backend.so` / `libatcli_env.so`（无 `:app` native）
- **TEST_CONSUMER_DEPENDS_ON_APP = NO** ✓

---

## 6. 依赖关系（composition only，无环）

```
:at-cli-consumer-host ──┐
:app                    ├──▶ :at-cli ──▶ :at-engine
                        │           ├──▶ :at-backend-local
                        │           ├──▶ :at-backend-adb
                        │           └──▶ :adb-engine
                        └──▶ (backends, :adb-engine)
```

- `:at-cli` **不依赖** `:app` ✓
- `:app` 依赖 `:at-cli`（composition）✓
- `:at-cli-consumer-host` 依赖 `:at-cli`，**不依赖** `:app` ✓

---

## 7. 冻结边界遵守（PRE-EMULATOR-FREEZE）

- 未修改冻结执行层（AT Engine / AdbRuntime / AdbEngine）
- 未重开 ADB/AT 边界：无第二个 AdbRuntime / AT Engine / recovery owner
- 未改 ADB wire 协议
- 无外部 adb 二进制 / adb server 5037 / native ELF exec
- AT 最终契约保持 **DRAFT**（CLI_CONSUMER_API_STATUS=PROVISIONAL/DRAFT）
- 未实现 Emulator / ScreenState

---

## 8. 已知 NON_BLOCKING 债务（继承，本轮未触碰）

- `UI_STATE_PROJECTION_DEBT`
- `STATUS_DEDUPE_UX_DEBT`（S3 已在本轮单投影修复后稳定过）
- `FIRST_RUN_PRODUCTIZATION=DEFERRED`
- `DEBUGBRIDGE=NOT PLANNED`

---

## 9. 文档与 SSOT

- 新实现文档：`docs/implementation/AT.CLI-CONSUMER-0.md`（本文件）
- Canonical SSOT 状态不变；AT Contract 保持 DRAFT
- 未修改 PRE-EMULATOR Freeze V1 / Handoff V1（历史文档）
- 本轮**不创建** Handoff V2
- 本轮交付（`本轮交付/`）已按 AGENTS.md §4 刷新

---

## 10. 提交

- 单次原子提交：`AT.CLI-CONSUMER-0: extract reusable AT CLI consumer capability`
- TAG=NO，PUSH=NO
- 不 amend `700ca3b` / `7ccb1bf`；不动 AT.PRE-EMULATOR-FREEZE-V1 tag

---

## 11. Gate 总表

| 类别 | Gate | 状态 |
|---|---|---|
| 功能 | C1 Local id | PASS |
| 功能 | C2 adb shell id (uid=2000) | PASS |
| 功能 | C3 one-shot | PASS |
| 功能 | C4 interactive | PASS |
| 功能 | C5 cwd+env | PASS |
| 功能 | C6 stdin | PASS |
| 功能 | C7 Ctrl-C | PASS |
| 功能 | C8 resize | PASS |
| 功能 | C9 返回本地 | PASS |
| 功能 | C10 session 连续 | PASS |
| 架构 | SECOND_AT_ENGINE=NO | PASS |
| 架构 | SECOND_ADB_RUNTIME=NO | PASS |
| 架构 | EXTERNAL_ADB_BINARY=NO | PASS |
| 架构 | ADB_SERVER_5037=NO | PASS |
| 架构 | NESTED_ENGINE_DEADLOCK=NO | PASS |
| 架构 | CLI_SHARED_AT_RUNTIME | PASS |
| 身份 | CONSUMER_IDENTITY_ORIGIN=SELF_GENERATED | PASS |
| 身份 | CONSUMER_KEY_COPIED=NO | PASS |
| 身份 | CONSUMER_IDENTITY_REUSE_AFTER_RESTART | PASS |
| 身份 | FIRST_USE_PAIRING（手动配对） | PASS |
| 隔离 | §25 consumer 无 :app 类依赖 | PASS |
| 隔离 | TEST_CONSUMER_DEPENDS_ON_APP=NO | PASS |
| 回归 | 产品 Bridge 13/13 | PASS |
| 回归 | 产品 Lifecycle 11/11 | PASS |
| 回归 | 产品 LiveStatus 9/9 | PASS |
| 回归 | 产品 Interactive 16/16 | PASS |
| 回归 | JVM 单测全模块 | PASS |

**全 gate PASS → 单次原子提交。**
