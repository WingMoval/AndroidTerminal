# AT.CLI-PATH-FACTCHECK-0 — Local Execution vs shell-visible ADB CLI 静态事实核查

> 性质：STATIC FACT CHECK / CLI ENTRYPOINT VERIFICATION / EXECUTION PATH SEMANTICS AUDIT。
> DOCS ONLY。PRODUCTION_CODE_CHANGE = NO / TEST_CODE_CHANGE = NO / DEVICE_TEST = NO /
> ARCHITECTURE_CHANGE = NO / API_CHANGE = NO / CLI_CHANGE = NO / BACKEND_CHANGE = NO /
> NEW_TAG = NO / PUSH = NO。
> 审计基线：`f55973d`（AT.CONSUMER-CAPABILITY-AUDIT-0.1）。

---

## 1. Executive Verdict

**VERDICT = PASS**

三个核心事实问题已解决：

1. **JVM 主类** = `com.at.cli.AtCliMain`（Kotlin object 名，非文件名 `AdbCliMain.kt`）
2. **bare `id`** 不属于 AtCliMain 的合法 CLI command surface（parser 返回 `Kind.UNSUPPORTED`）
3. **Local `id`** 与 **`adb shell id`** 是两条完全不同的 execution path：
   - **PATH A**（Local `id`）：Local PTY → `/system/bin/sh` → `id` → **app UID**（untrusted_app）
   - **PATH B**（`adb shell id`）：Local PTY → `/system/bin/sh` → `adb()` function → `app_process com.at.cli.AtCliMain` → CLI bridge → `adbEngine()` → ADB Backend → adbd → **uid=2000(shell)**

**关键澄清**：Local 执行 **不依赖** ADB readiness / identity / Wireless Debugging。CLI 只负责 `adb shell` / `adb pair` 等 ADB 操作，不覆盖 Local 命令执行。

---

## 2. Preflight

```
START_HEAD = f55973d
WORKTREE   = CLEAN（无预存修改）
```

---

## 3. JVM 主类验证（§5/§6）

### 3.1 Kotlin 声明

**文件**：`at-cli/src/main/java/com/at/cli/AdbCliMain.kt`

```kotlin
package com.at.cli

object AtCliMain {
    fun main(args: Array<String>) { ... }
}
```

- **FILE_NAME** = `AdbCliMain.kt`
- **OBJECT_NAME** = `AtCliMain`
- **PACKAGE** = `com.at.cli`
- **MAIN_METHOD** = `fun main(args: Array<String>)`

### 3.2 编译 Artifact 证据

**JAR 文件**：`at-cli/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar`

**`jar tf classes.jar`**：
```
com/at/cli/AtCliMain.class
com/at/cli/AtCliMain$Frame.class
com/at/cli/AtCliMain$OneShotResult.class
com/at/cli/AtCliMain$WhenMappings.class
```

**`javap -cp classes.jar com.at.cli.AtCliMain`**：
```
Compiled from "AdbCliMain.kt"
public final class com.at.cli.AtCliMain {
  public static final com.at.cli.AtCliMain INSTANCE;
  public static final int BRIDGE_PROTOCOL_VERSION;
  public static final void main(java.lang.String[]);
  static {};
}
```

**`javap -cp classes.jar com.at.cli.AdbCliMain`**：
```
Error: Could not find class com.at.cli.AdbCliMain
```

### 3.3 结论

```
COMPILED_CLASS_PRESENT = com.at.cli.AtCliMain
COMPILED_CLASS_ABSENT  = com.at.cli.AdbCliMain
APP_PROCESS_ENTRYPOINT = com.at.cli.AtCliMain
ENTRYPOINT_VERIFIED_BY_CLASSES_JAR = YES
ENTRYPOINT_VERIFIED_BY_JAVAP       = YES
```

**Kotlin 规则**：object 的 JVM 类名 = **object 声明名**（`AtCliMain`），**不是**文件名（`AdbCliMain.kt`）。

---

## 4. LocalCliBootstrap 验证（§7）

**文件**：`at-cli/src/main/java/com/at/cli/LocalCliBootstrap.kt`（注意：实际文件名是 `LocalSessionBootstrap.kt`，类名是 `LocalCliBootstrap`）

### 4.1 生成的 shell script

```bash
export AT_APP_APK='/data/app/.../base.apk'
export AT_CLI_BRIDGE_SOCK='/data/data/com.androidterminal.app/files/cli_bridge.sock'
export AT_SESSION_ID='0'

adb() {
  if [ "$1" = shell ] && [ "$#" -eq 1 ]; then
    # INTERACTIVE: save termios, raw mode, stty size, launch AtCliMain, restore termios
    local _old _sz _r _c
    _old="$(stty -g 2>/dev/null)"
    _sz="$(stty size 2>/dev/null)"
    _r=$(printf '%s' "$_sz" | awk '{print $1}')
    _c=$(printf '%s' "$_sz" | awk '{print $2}')
    stty raw -echo 2>/dev/null
    AT_CLI_ROWS="$_r" AT_CLI_COLS="$_c" /system/bin/app_process -Djava.class.path="$AT_APP_APK" /system/bin com.at.cli.AtCliMain "$@"
    local _rc=$?
    [ -n "$_old" ] && stty "$_old" 2>/dev/null
    return $_rc
  fi
  # ONE-SHELL: plain launch, no raw mode
  /system/bin/app_process -Djava.class.path="$AT_APP_APK" /system/bin com.at.cli.AtCliMain "$@"
}
```

### 4.2 关键确认

- **LOCAL_CLI_BOOTSTRAP_MAIN_CLASS** = `com.at.cli.AtCliMain`
- **GENERATED_ADB_FUNCTION** = `adb() { ... }`（shell function，非 ELF / 非 alias）
- **`adb ...` 转换逻辑**：`adb shell id` → `app_process -Djava.class.path=$AT_APP_APK /system/bin com.at.cli.AtCliMain shell id`

---

## 5. CLI Parser Surface 验证（§8/§9）

**文件**：`at-cli/src/main/java/com/at/cli/AdbCliArgs.kt`

### 5.1 Parser 逻辑

```kotlin
fun parse(args: Array<String>): AdbCliParseResult {
    if (args.isEmpty()) return Kind.NO_ARGS
    val sub = args[0]
    return when (sub) {
        "shell" -> {
            val rest = args.copyOfRange(1, args.size)
            if (rest.isEmpty()) Kind.INTERACTIVE  // `adb shell`（无命令）
            else Kind.SHELL                       // `adb shell <cmd...>`
        }
        "pair" -> parsePair(...)                   // `adb pair <host>:<port> <code>`
        "--help", "-h", "help" -> Kind.HELP
        "version" -> Kind.VERSION
        else -> Kind.UNSUPPORTED                  // 其他所有命令
    }
}
```

### 5.2 测试用例

| ARGS | RESULT |
|---|---|
| `["shell", "id"]` | `Kind.SHELL`，`remoteCommand = "'id'"` |
| `["shell"]` | `Kind.INTERACTIVE`（remote PTY relay） |
| `["pair", "192.168.1.1:5555", "123456"]` | `Kind.PAIR`，`pairHost = "192.168.1.1"`，`pairPort = 5555`，`pairCode = "123456"` |
| `["id"]` | `Kind.UNSUPPORTED`，`message = "adb: unsupported command: id (this build supports 'shell <command...>' and 'pair <host>:<port> <code>')"` |

### 5.3 CLI Command Surface

```
CLI_COMMAND_SURFACE = {
  shell <command...>      (one-shot ADB execution)
  shell                   (interactive remote PTY)
  pair <host>:<port> <code>  (pairing)
  --help / -h / help
  version
}

UNSUPPORTED = { push, pull, install, devices, forward, reverse, bare arbitrary command, ... }
```

**bare `id` 不属于 CLI command surface**。

---

## 6. Bridge One-shot 语义验证（§10）

**追踪 `adb shell id` 真实路径**：

```
Local PTY
  → /system/bin/sh
  → 用户输入：adb shell id
  → shell 解析：adb() function 被调用，args = ["shell", "id"]
  → adb() function 执行：app_process -Djava.class.path=$AT_APP_APK /system/bin com.at.cli.AtCliMain shell id
  → AtCliMain.main(["shell", "id"])
  → AdbCliParseResult.parse(["shell", "id"]) → Kind.SHELL, remoteCommand = "'id'"
  → bridgeOneShot("'id'")
  → 连接 UDS（/data/data/com.androidterminal.app/files/cli_bridge.sock）
  → 发送 T_HELLO + T_ONESHOT（payload = "'id'"）
  → AdbCliBridgeServer.onOneShot(fd, "'id'")
  → execution.ensureReady(sid) { ... }  // AppCliExecution
  → AdbRuntime.ensureReady(identityDir) { ... }  // cold path: readiness → connectAuto
  → AdbRuntime.connectAuto(identityDir)  // discovery + connect
  → AdbEngine.connect(host, port, keyDir)  // TLS handshake
  → AdbEngine.executeShell("'id'")  // shell,v2,raw
  → adbd → remote /system/bin/sh -c 'id'
  → uid=2000(shell)
  → 返回 stdout/stderr/exitCode
  → T_OUT / T_ERR / T_EXIT frames
  → AtCliMain 输出到 Local PTY
  → 用户看到：uid=2000(shell) gid=2000(shell) ...
```

**确认**：

```
CLI_ONE_SHOT_IS_ADB_OPERATION = YES
```

**`adb shell id` 是 ADB 操作**，不是 Local 操作。

---

## 7. Local 执行路径验证（§11）

**追踪 Local terminal 中用户直接输入 `id`**：

```
Local PTY
  → /system/bin/sh -i  (由 LocalPersistentBackend.openSession() 启动)
  → 用户输入：id
  → /system/bin/sh 直接执行：id
  → 返回：uid=10xxx(com.androidterminal.app) gid=10xxx(com.androidterminal.app) ...
```

**逐项确认**：

| 问题 | 答案 |
|---|---|
| 是否经过 `AtCliMain`？ | **NO** |
| 是否经过 `AdbCliBridgeServer`？ | **NO** |
| 是否经过 `ATCliExecution.ensureReady`？ | **NO** |
| 是否经过 `AdbRuntime`？ | **NO** |
| 是否经过 `AdbConsumerBootstrap`？ | **NO** |
| 是否经过 ADB Backend？ | **NO** |

**最终输出**：

```
LOCAL_COMMAND_REQUIRES_ADB_READY = NO
LOCAL_COMMAND_REQUIRES_IDENTITY  = NO
LOCAL_COMMAND_REQUIRES_WIRELESS_DEBUGGING = NO
```

**Local 命令执行完全独立于 ADB**。

---

## 8. Local One-shot 验证（§12）

### 8.1 LocalOneShotBackend 存在性

**文件**：`at-backend-local/src/main/java/com/at/backend/local/LocalOneShotBackend.kt`

```kotlin
class LocalOneShotBackend : ATOneShotBackend {
    override fun execute(request: ATExecutionRequest): ATExecutionResult {
        // 使用 ProcessBuilder 执行 /system/bin/sh -c <command>
    }
}
```

**存在**：`LocalOneShotBackend` 类已实现，可构造。

### 8.2 Wiring 状态

**`AppRuntime.localEngine()`**（`app/src/main/java/com/androidterminal/app/AppRuntime.kt` 第 131 行）：
```kotlin
fun localEngine(): ATEngine = ATEngine(oneShot = null, persistent = LocalPersistentBackend())
```

**`AtCliConsumerHost.localEngine()`**（`at-cli-consumer-host/src/main/java/com/androidterminal/atclihost/AtCliConsumerHost.kt` 第 100 行）：
```kotlin
fun localEngine(): ATEngine = ATEngine(oneShot = null, persistent = LocalPersistentBackend())
```

**确认**：

```
LOCAL_ONESHOT_CAPABILITY_EXISTS = YES（LocalOneShotBackend 类存在）
REFERENCE_HOST_LOCAL_ONESHOT_WIRED = NO（AtCliConsumerHost.localEngine().oneShot = null）
CURRENT_PRODUCT_LOCAL_ONESHOT_WIRED = NO（AppRuntime.localEngine().oneShot = null）
```

**注意**：Local one-shot **能力存在**（`LocalOneShotBackend` 类），但**未被 Product / reference host wired**。这是 AT.CONSUMER-CAPABILITY-AUDIT-0.1 已记录的已知状态（Local one-shot AVAILABLE 但 REFERENCE HOST WIRED = NO）。

---

## 9. 三条路径语义区分（§13/§14）

### 9.1 PATH A — Local terminal command

```
Local PTY
  → /system/bin/sh
  → id
  → app principal（uid=10xxx，untrusted_app 域）
```

**Principal** = consumer app UID（例如 `uid=10234(com.androidterminal.app)`）

**验证状态** = VERIFIED_BY_SOURCE（`LocalPersistentBackend` → `LocalPtySession.start()` → `/system/bin/sh -i`）

### 9.2 PATH B — shell-visible adb command

```
Local PTY
  → /system/bin/sh
  → adb shell id
  → AT CLI frontend（app_process com.at.cli.AtCliMain）
  → ADB execution（AdbEngine.executeShell）
  → adbd → remote /system/bin/sh -c 'id'
  → uid=2000(shell)
  → 返回 Local shell
```

**Remote principal** = `uid=2000(shell)`
**Return** = original Local shell（`adb shell id` 执行完毕后，用户仍停留在 Local shell）

**验证状态** = VERIFIED_BY_SOURCE（`AdbCliMain` → `bridgeOneShot` → `AdbCliBridgeServer` → `AdbEngine.executeShell`）

### 9.3 PATH C — direct programmatic ADB

```
Consumer
  → ATEngine(ADB backend)
  → AdbRuntime / AdbEngine
  → adbd → remote /system/bin/sh -c <command>
  → uid=2000(shell)
```

**Principal** = `uid=2000(shell)`

**验证状态** = VERIFIED_BY_SOURCE（`AdbOneShotBackend` → `AdbEngine.executeShell`）

### 9.4 关键区分

**PATH A / B / C 不得合并**：

- **PATH A** 是 Local 执行，**不经过** ADB。
- **PATH B** 是 shell-visible ADB CLI，**必须经过** ADB（`adbEngine()`）。
- **PATH C** 是 programmatic ADB，**必须经过** ADB（`AdbEngine`）。

**PATH B 使用 `adbEngine()` 是设计正确的**，因为 `adb shell` 的语义就是 ADB 操作。

---

## 10. 上一轮结论重新分类（§15）

### STATEMENT 1

> "Kotlin object 的类名以文件名为准，所以主类是 `com.at.cli.AdbCliMain`"

**VERDICT** = **FALSE**

**EVIDENCE**：
- Kotlin object 的 JVM 类名 = **object 声明名**（`AtCliMain`），**不是**文件名（`AdbCliMain.kt`）。
- Artifact 证据：`jar tf` 显示 `com/at/cli/AtCliMain.class`，`javap` 确认 `com.at.cli.AtCliMain` 存在且含 `static main`。
- `com.at.cli.AdbCliMain` 不存在（`javap` 返回 "Could not find class"）。

### STATEMENT 2

> "bare `id` 被 AdbCliMain parse 为 Kind.SHELL"

**VERDICT** = **FALSE**

**EVIDENCE**：
- Parser 逻辑：`args[0] = "id"` → `when (sub)` 不匹配 `"shell"` / `"pair"` / `"help"` / `"version"` → `Kind.UNSUPPORTED`。
- `Kind.SHELL` 只在 `args[0] = "shell"` 且 `args.size > 1` 时返回。
- bare `id` 是 **unsupported command**，不是 shell 命令。

### STATEMENT 3

> "`id` 在 Local 执行时实际上走 ADB"

**VERDICT** = **MISFRAMED**

**EVIDENCE**：
- Local 执行（PATH A）：**不经过** `AtCliMain` / `AdbCliBridgeServer` / `AdbRuntime` / ADB Backend。
- `adb shell id`（PATH B）：**必须经过** ADB（`adbEngine()`）。
- 问题在于**混淆了 PATH A 和 PATH B**：
  - PATH A（Local `id`）→ Local 执行，**不走 ADB**。
  - PATH B（`adb shell id`）→ ADB 执行，**走 ADB**。
- "Local 执行时走 ADB" 是**错误前提**：Local 执行根本不调用 CLI / ADB。

### STATEMENT 4

> "当前 CLI 不支持 Local path，因此 Local execution 不支持"

**VERDICT** = **CATEGORY_ERROR**

**EVIDENCE**：
- CLI 的职责是**提供 shell-visible `adb` frontend**，不是提供 Local 命令执行。
- Local 命令执行由 **Local Backend**（`LocalPersistentBackend` / `LocalOneShotBackend`）提供，**不经过 CLI**。
- "CLI 不支持 Local path" 是**正确的**（CLI 只处理 `adb shell` / `adb pair` 等 ADB 操作），但**不能推出** "Local execution 不支持"。
- Local execution **支持**（`LocalPersistentBackend` 已实现，Product / reference host 已 wired persistent；one-shot 能力存在但未 wired）。

---

## 11. 架构影响（§16）

| 边界 | 影响 |
|---|---|
| AT Engine boundary | **UNCHANGED**（Local / ADB backend 分离，ATEngine facade 不变） |
| Local backend boundary | **UNCHANGED**（`LocalPersistentBackend` / `LocalOneShotBackend` 独立于 CLI） |
| ADB backend boundary | **UNCHANGED**（`AdbOneShotBackend` / `AdbPersistentBackend` 独立于 CLI） |
| CLI frontend boundary | **CLARIFIED**（CLI 只处理 ADB 操作，不覆盖 Local 命令执行） |
| AdbRuntime ownership | **UNCHANGED**（AdbRuntime 仍是 UNIQUE ADB lifecycle owner） |
| Consumer Boundary problem | **STILL_PRESENT**（Consumer 仍需自行编排 AdbRuntime / AdbConsumerBootstrap / identityDir） |

**关键**：即使 CLI / Local 分割本身正确，**Consumer lifecycle/control-plane leakage 仍可能存在**。本轮 CLI fact-check **不取消** AT.CONSUMER-BOUNDARY-0。

---

## 12. 文档策略（§17）

- **创建**：`docs/audit/AT.CLI-PATH-FACTCHECK-0.md`（本文件）
- **不修改**：TERM.A0.5 / freeze docs / handoff docs / AG docs / production SSOT / architecture contract
- **EVIDENCE_INDEX_CORRECTION_REQUIRED** = **NO**（无历史 evidence 错误需修正）

---

## 13. 无生产修复（§18）

```
ACTUAL_BUG = NO
RECOMMENDED_NEXT = RESUME_AT.CONSUMER-BOUNDARY-0
```

**未发现生产 bug**（主类名 / parser / bootstrap / CLI route 均正确）。

---

## 14. Final Report（§19）

```
AT.CLI-PATH-FACTCHECK-0 FINAL REPORT

VERDICT = PASS
START_HEAD = f55973d
FINAL_HEAD = f55973d（无新 commit，仅创建本审计文档）
PRODUCTION_CODE_CHANGED = NO
TEST_CODE_CHANGED = NO
DEVICE_TEST = NO

FILE_NAME = AdbCliMain.kt
KOTLIN_OBJECT_NAME = AtCliMain
COMPILED_JVM_CLASS = com.at.cli.AtCliMain
APP_PROCESS_ENTRYPOINT = com.at.cli.AtCliMain
ENTRYPOINT_VERIFIED_BY_CLASSES_JAR = YES
ENTRYPOINT_VERIFIED_BY_JAVAP = YES

BARE_ID_PARSE_RESULT = Kind.UNSUPPORTED
CLI_COMMAND_SURFACE = { shell <command...>, shell (interactive), pair <host>:<port> <code>, help, version }

ADB_SHELL_ID_PATH = Local PTY → /system/bin/sh → adb() → app_process com.at.cli.AtCliMain → T_ONESHOT → AdbCliBridgeServer → ensureReady → adbEngine → ADB Backend → AdbEngine → adbd → uid=2000(shell)
LOCAL_ID_PATH = Local PTY → /system/bin/sh → id → app UID（untrusted_app）

LOCAL_COMMAND_USES_ATCLIMAIN = NO
LOCAL_COMMAND_USES_CLI_BRIDGE = NO
LOCAL_COMMAND_USES_ADBRUNTIME = NO
LOCAL_COMMAND_REQUIRES_ADB_READY = NO
LOCAL_COMMAND_REQUIRES_IDENTITY = NO
LOCAL_COMMAND_REQUIRES_WD = NO
ADB_SHELL_ID_REQUIRES_ADB_READY = YES

PATH_A_LOCAL = VERIFIED_BY_SOURCE
PATH_B_SHELL_VISIBLE_ADB = VERIFIED_BY_SOURCE
PATH_C_DIRECT_ADB = VERIFIED_BY_SOURCE

PREVIOUS_STATEMENT_MAINCLASS = FALSE
PREVIOUS_STATEMENT_BARE_ID_IS_SHELL = FALSE
PREVIOUS_STATEMENT_LOCAL_ID_GOES_ADB = MISFRAMED
PREVIOUS_STATEMENT_CLI_LACKS_LOCAL_SO_LOCAL_UNSUPPORTED = CATEGORY_ERROR

CLI_LOCAL_BOUNDARY = CORRECT
CONSUMER_CONTROL_PLANE_BOUNDARY_PROBLEM = STILL_PRESENT
AT_CONSUMER_BOUNDARY_0_STILL_REQUIRED = YES

ACTUAL_BUG_FOUND = NO
RECOMMENDED_NEXT = RESUME_AT.CONSUMER-BOUNDARY-0
NEXT_OPEN_AUTHORIZED = NO
TAG = NO
PUSH = NO
STOP = YES
```

---

## 15. Controller Summary（§20，≤10 条）

1. **JVM 主类到底是什么**：`com.at.cli.AtCliMain`（Kotlin object 名，非文件名 `AdbCliMain.kt`）。
2. **文件名是否曾被错误当成类名**：**YES**，上一轮分析错误地使用了文件名 `AdbCliMain` 作为类名。
3. **bare `id` 是否属于 CLI surface**：**NO**，parser 返回 `Kind.UNSUPPORTED`。
4. **Local `id` 的真实执行路径**：Local PTY → `/system/bin/sh` → `id` → **app UID**（不经过 ADB / CLI / AdbRuntime）。
5. **`adb shell id` 的真实执行路径**：Local PTY → `/system/bin/sh` → `adb()` → `app_process com.at.cli.AtCliMain` → CLI bridge → `adbEngine()` → ADB Backend → adbd → **uid=2000(shell)**。
6. **Local 是否依赖 ADB readiness**：**NO**，Local 执行完全独立于 ADB。
7. **shell-visible adb 是否本来就应该走 `adbEngine`**：**YES**，`adb shell` 的语义就是 ADB 操作。
8. **当前 CLI / Local 分割本身是否有问题**：**NO**，分割正确（CLI 只处理 ADB 操作，Local 命令由 Local Backend 处理）。
9. **Consumer lifecycle boundary 问题是否仍存在**：**YES**，Consumer 仍需自行编排 AdbRuntime / AdbConsumerBootstrap / identityDir。
10. **下一步是否恢复 AT.CONSUMER-BOUNDARY-0**：**YES**，本轮 fact-check 不取消 Consumer Boundary 任务。

**STOP**（按任务要求，等待 Controller Review；不自动开始 AT.CONSUMER-BOUNDARY-0 或其他任务）。
