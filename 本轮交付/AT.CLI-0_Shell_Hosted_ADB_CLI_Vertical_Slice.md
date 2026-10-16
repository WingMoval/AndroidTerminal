# AT.CLI-0 — Shell-Hosted ADB CLI Vertical Slice (Implementation)

> **AT_CLI_0_OVERALL_STATUS = IN_PROGRESS**
>
> | 阶段 | 状态 |
> |---|---|
> | EntryPoint Recovery | **COMPLETE** |
> | One-shot Core (`adb shell <cmd...>`) | **VERIFIED** |
> | Auto-WD / connection lifecycle | NEXT (AT.CLI-0.1A) |
> | Interactive `adb shell` | NOT_STARTED |
>
> 本 slice **不**声明 `SHELL_HOSTED_ADB_CLI = COMPLETE`：one-shot 已 verified，interactive
> 与 connection lifecycle 未开始。

---

## 0. 目标

在真实 Human Local `/system/bin/sh`（SELinux `untrusted_app`）里，让 `adb shell <command...>`
作为**产品可见的 one-shot CLI** 工作：用户在 Local 终端敲 `adb shell id` → 得到
`uid=2000(shell)`，stdout/stderr/exitCode 正确映射回 Local 终端。

约束（不可破）：
- `AT_ENGINE_MODIFIED = NO` / `ADB_ENGINE_MODIFIED = NO`
- 不引入第二 AdbRuntime / identity / TLS owner / DebugBridge 依赖
- 不改 root / SELinux
- 复用现有 Product CLI Bridge（same-UID UDS，protocol v1）

## 1. 阶段 A — EntryPoint Recovery（COMPLETE）

**问题**：原 `files/bin/adb`（ELF，SELinux `app_data_file`）被 `untrusted_app` exec 时
**RC=126 Permission denied**（SELinux 阻止 `untrusted_app` 执行 `app_data_file` 二进制）。
`run-as`（`runas_app` 域）能 exec 同一 ELF，但不代表真实 Local PTY。
→ `PRIVATE_APP_DATA_ELF_ENTRYPOINT = REJECTED`。

**恢复**：入口改 `/system/bin/app_process`（context `zygote_exec`），可被 `untrusted_app` exec。
命令形如：
```
/system/bin/app_process -Djava.class.path=$AT_APP_APK /system/bin \
  com.androidterminal.app.cli.AdbCliMain <args...>
```
P1-P6（exec / class load / argv / PTY stdio / 连现有 Product CLI Bridge / clean return）
**6/6 PASS**（见 `docs/evidence/at_cli_0/APP_PROCESS_ENTRYPOINT_PROBE.md` 与
`SELINUX_EXEC_BLOCKER.md`）。commit `17ba449`。

## 2. 阶段 B — One-shot Core（VERIFIED）

`adb shell <command...>` one-shot 闭合，真机 **15/15 PASS**（见
`docs/evidence/at_cli_0/ONESHOT_CLOSURE.md`）。

### 组件
- `cli/AdbCliMain.kt`（`src/main`，debug+release）：app_process 入口，纯 JVM/Kotlin（无 JNI），
  `android.net.LocalSocket` UDS 客户端，one-shot 往返（HELLO→ONESHOT→OUT/ERR/EXIT→映射
  stdout/stderr/exitCode）。
- `cli/AdbCliArgs.kt`（`src/main`）：纯 JVM 参数解析 + POSIX 单引号 shell quoting 助手
  （`quoteSingle` / `serializeToRemoteCommand`）。
- `cli/LocalSessionBootstrap.kt`（`src/main`）：写 `adb_bootstrap.sh`（env export + `adb()` 函数）
  到 app filesDir + source 行。
- `cli/AdbCliBridgeServer.kt`（`src/main`，**已存在**，本轮仅加 `ensureReady` 轮询 + 诊断日志）：
  复用 native UDS server，same-UID `SO_PEERCRED`，protocol v1。
- `cli/CliProbe.kt`（`src/debug`，**仅 debug**）：调试探针，release APK 不含。
- `AppRuntime.kt`：新增 `appApkPath()`（`applicationInfo.sourceDir`）/ `cliBridgeSocketPath()` /
  `appContextRef`。
- `MainActivity.kt`：LOCAL 页 `banner()` 前置 source bootstrap；`activeSharedHandle()` 访问器。

### 真实路径
```
Local sh (untrusted_app)
  → adb shell id            # adb() 函数 (adb_bootstrap.sh)
  → /system/bin/app_process ... AdbCliMain shell id
  → LocalSocket UDS (cli_bridge.sock, same-UID)
  → AdbCliBridgeServer.onOneShot
  → AppRuntime.adbEngine().execute (AT Engine → ADB Backend → ADB Engine → adbd)
  → 远程 sh (shell, uid=2000) 执行 id
```

### principal transition（O3 实测）
```
本地 uid=10313(u0_a313)  →  adb shell id  →  远程 uid=2000(shell)  →  本地 uid=10313
```

### 本轮实现 bug fixes（1-6）
1. `AT_APP_APK` 空 → bootstrap 改文件 + source（PTY 多行函数 mangle）。
2. Kotlin 模板展开 `$ENV_APK`→常量名 → `"$"+ENV_APK` 输出字面量 `$AT_APP_APK`。
3. `ensureReady` 单次 `connectAuto` 不足 → 轮询 15s。
4. O1 断言（`type adb` 对函数只返回名字）→ 含 `function`。
5. O3 marker 误匹配（本地 `id` 含 `u0_a`）→ 只用 `uid=2000`。
6. observer 捕获 flaky（PTY 分批）→ marker 后多等 2.5s。

## 3. 阶段 C — Auto-WD / connection lifecycle（NEXT = AT.CLI-0.1A）

本轮 one-shot 验证**依赖** WD=ON + trusted + READY 固定前置。前置不满足（WD OFF / untrusted /
disconnect）时 ADB 进入 `USER_ACTION_REQUIRED`，one-shot 无法 READY。

**AT.CLI-0.1A** 将实现：
```
adb command
  → ensureReady
  → WD OFF 自动开启
  → discovery
  → connect
  → connected
  → execute
```
以及 `disconnected / reconnecting / connected` terminal status events。

## 4. Interactive `adb shell`（NOT_STARTED）

无参 `adb shell`（交互）+ Ctrl-C / resize relay 未开始。one-shot 不杀 Local shell（O15 已证
PTY 存活），但交互式 relay 是独立后续工作。

## 5. 测试与回归

- `AdbCliOneShotTest`（androidTest）：O1-O15，**15/15 PASS**（97s，flaky NO）。
- `AppProcessProbeTest`（androidTest）：P1-P6，**6/6 PASS**。
- 相关回归（WD=ON 前置）：`SharedSessionBridgeTest` 13/13、`DynamicSessionPagesTest` 9/9、
  `ProgrammaticAdbConsumerTest` 7/7、`CommonConsumerPathTest` 3/3。
- JVM：`AdbCliArgsTest`（`src/test`）20/20。
- 完整长回归（Pairing 54 / 完整 Shellless / 历史 ADB backend 大套件）**DEFERRED 到
  AT.CLI-0.1A final**。

> **回归注意**：ADB persistent 类测试（ProgrammaticAdbConsumerTest / CommonConsumerPathTest）
> 依赖 WD=ON；WD OFF 时会 `USER_ACTION_REQUIRED` 失败（前置问题，非代码 regression）。
