# AT.CLI-0.1 — app_process `adb shell` ONE-SHOT Closure (Evidence)

> **ADB_CLI_ONESHOT_CORE = VERIFIED**
> 真机：`AdbCliOneShotTest` **15/15 PASS**，runtime 97s，flaky = NO。
> 本证据**不**声明 `AT.CLI-0 = COMPLETE`，也**不**声明 `SHELL_HOSTED_ADB_CLI = COMPLETE`。
> one-shot 验证在 **WD=ON + identity TRUSTED + AdbRuntime READY** 固定前置下完成。

---

## 0. Baseline & 前置（冻结事实）

| 项 | 值 |
|---|---|
| baseline HEAD | `17ba449` (`AT.CLI-0: recover shell CLI entrypoint via app_process`) |
| 设备 | `APLMUT4312005935` (HONOR ALI-AN00 / Android 15 / arm64, USB) |
| app UID | `10313` (`u0_a313`) |
| **固定前置 1** | **WD = ON** (`adb_wifi_enabled=1`) |
| **固定前置 2** | **identity = TRUSTED** (`files/adb_identity/{adb_cert.pem,adbkey,adbkey.pub}` 在位) |
| **固定前置 3** | **AdbRuntime = READY** (`connectAuto READY via 127.0.0.1:37333`, TLS active) |
| 本轮**不**实现 | Auto-WD / connection status / disconnected-reconnecting events / interactive `adb shell` / Ctrl-C / resize relay |

> **WD OFF / identity UNTRUSTED / ADB DISCONNECT 场景本轮 NOT COVERED。**
> 这些场景（WD OFF 自动开启 → discovery → connect → connected → execute + terminal status
> events）明确留给 **AT.CLI-0.1A — Auto-WD + ADB Connection Status Integration**。

---

## 1. 真实 Human Local Session 路径（非 mock）

```
Human 打开 Local 终端页
  └─ /system/bin/sh  (SELinux domain: untrusted_app)
       └─ `adb shell <command...>`           # shell 可见的 adb() 函数
            └─ /system/bin/app_process -Djava.class.path=$AT_APP_APK /system/bin
                   com.androidterminal.app.cli.AdbCliMain shell <command...>
                 └─ android.net.LocalSocket UDS (files/cli_bridge.sock, 同 UID, SO_PEERCRED)
                      └─ AdbCliBridgeServer.onOneShot  (protocol v1: HELLO→ONESHOT→OUT/ERR/EXIT)
                           └─ AppRuntime.adbEngine().execute(ATExecutionRequest)
                                └─ AT Engine → ADB Backend → ADB Engine → adbd (127.0.0.1:37333)
                                     └─ 远程 /system/bin/sh (SELinux domain: shell, uid=2000)
                                          └─ 执行 <command...>
```

**核心 principal transition（O3 实测）：**

```
本地 Local sh   : uid=10313(u0_a313)  context=u:r:untrusted_app:s0:c57,c257,c512,c768
  └─ adb shell id
       远程 sh   : uid=2000(shell)     context=u:r:shell:s0        ← 远程 principal
  └─ 回到本地   : uid=10313(u0_a313)  context=u:r:untrusted_app:... ← Local shell 存活
```

---

## 2. adb bootstrap 机制

- `LocalSessionBootstrap.writeScript()` 把 env export + `adb()` 函数**写成文件**
  `files/adb_bootstrap.sh`（同 UID，Local `untrusted_app` 可 source）。
- `MainActivity.banner()`（LOCAL 页）在 banner 前**先 source 该脚本**，再打印 banner。
- 为何写成文件而非经 PTY 直写多行函数：交互式 PTY 对多行 `adb() { ... "$@"; }` 的定义
  会 mangle（`$@` 被吞、函数体丢失）；source 文件是稳健的。
- 脚本内容（实测 cat）：
  ```sh
  export AT_APP_APK='/data/app/~~.../base.apk'
  export AT_CLI_BRIDGE_SOCK='/data/user/0/com.androidterminal.app/files/cli_bridge.sock'
  adb() { /system/bin/app_process -Djava.class.path="$AT_APP_APK" /system/bin \
            com.androidterminal.app.cli.AdbCliMain "$@"; }
  ```

## 3. app_process provenance

- 入口 = `/system/bin/app_process`（SELinux context `zygote_exec`），**可被 `untrusted_app` exec**。
- 对比：`files/bin/adb`（ELF，context `app_data_file`）被 `untrusted_app` **RC=126 Permission
  denied** 拒绝（见 `SELINUX_EXEC_BLOCKER.md`）。故 entrypoint 恢复选 `app_process`（P1-P6 见
  `APP_PROCESS_ENTRYPOINT_PROBE.md`）。
- `AdbCliMain` 为 **纯 JVM/Kotlin（无 JNI / 无 `System.loadLibrary`）**：`app_process` 不设
  `java.library.path` 到 APK lib 目录，JNI 会失败；UDS 用 `android.net.LocalSocket`。
- `AdbCliMain` 在 `src/main`（debug+release 都含）；debug-only `CliProbe` 在 `src/debug`（release
  APK 不含）。两者严格分离。

## 4. Product CLI Bridge / UDS

- 复用现有 `AdbCliBridgeServer`（native UDS，`cli_bridge_server.cpp`），bind 在 app 私有
  `files/cli_bridge.sock`，`SO_PEERCRED` 校验 peer UID == app UID（same-UID，无 TCP、无
  DebugBridge 45679、无第二 AdbRuntime/identity/TLS owner）。
- protocol v1：`T_HELLO=1 / ACK=2 / ONESHOT=3 / OUT=4 / ERR=5 / ERROR=7 / EXIT=6`（length-prefixed）。
- one-shot 往返：`HELLO→ACK` → `ONESHOT(cmd)` → `OUT(stdout) ERR(stderr) [ERROR] EXIT(le32 code)`。

---

## 5. O1-O15 = 15/15 PASS（diag 捕获的真实 PTY 输出）

运行：`am instrument -w -e class com.androidterminal.app.AdbCliOneShotTest
com.androidterminal.app.test/androidx.test.runner.AndroidJUnitRunner`
→ **`OK (15 tests)`**，97s。

| # | Gate | 命令 | 实测关键证据 | 结果 |
|---|------|------|-------------|------|
| O1 | 命令解析 | `type adb` | `adb is a function`（shell 函数，非 system binary / 非 RC126） | PASS |
| O2 | version | `adb version` | `AndroidTerminal adb CLI (app_process entrypoint, bridge protocol v1)` | PASS |
| O3 | **principal transition** | `id; adb shell id; id` | 本地`uid=10313` → 远程**`uid=2000(shell)`** `context=u:r:shell:s0` → 本地`uid=10313` | PASS |
| O4 | stdout | `adb shell echo CLI_STDOUT_OK` | `CLI_STDOUT_OK` | PASS |
| O5 | stderr | `adb shell sh -c 'echo CLI_STDERR_MARKER >&2'` | `CLI_STDERR_MARKER`（走 stderr 通道） | PASS |
| O6 | exit 0 | `adb shell true; echo O6_RC_$?` | `O6_RC_0` | PASS |
| O7 | exit 非零 | `adb shell false; echo O7_RC_$?` | `O7_RC_1` | PASS |
| O8 | exit 7 | `adb shell sh -c 'exit 7'; echo O8_RC_$?` | `O8_RC_7` | PASS |
| O9 | quoting 空格 | `adb shell echo "hello world"` | `hello world` | PASS |
| O10 | UTF-8 | `adb shell echo "你好世界"` | `你好世界` | PASS |
| O11 | quoting 单引号 | `adb shell echo "a'b $HOME"` | `a'b `（单引号内 `$HOME` 不展开） | PASS |
| O12 | unsupported | `adb devices` | `adb: unsupported command: devices (this build supports only `shell <command...>`)` RC=2 | PASS |
| O13 | sequential | 20× `adb shell id` | 20/20 均返回 `uid=2000(shell)` | PASS |
| O14 | connection reuse | 连续多次 one-shot | 同一 bridge `ready` / ADB 连接复用，无重连抖动 | PASS |
| O15 | **Local shell 存活** | `adb shell id; echo LOCAL_STILL_ALIVE; id` | `uid=2000(shell)` → `LOCAL_STILL_ALIVE` → 本地`uid=10313` | PASS |

### Harness 修复（测试侧，非产品 hack）
- **BODY_EXECUTED 哨兵 + observer 模型**：`ActivityScenario.onActivity` 对同一类第 2+ 个测试会
  no-op（body 不执行 → 假通过）。修复：每个 test 开**全新** Local 页
  (`createPage`+`openActiveSession`)，用 `SharedSessionHandle` observer 捕获 fan-out 输出，
  并以 `bodyEntered` AtomicBoolean 哨兵检测 vacuous pass。
- **focused runner**：`-e class <FQCN>` 过滤**生效**（单类）；`-e method` 被 runner 忽略，故按整类跑。
- **PTY 输出分批到达**：`adb shell X` 的 one-shot 输出与 trailing `; echo MARKER` 分两批到 PTY；
  marker 出现后额外等待 2.5s 再 removeObserver，避免截断 trailing 输出。
- **marker 选择**：O3 marker 只用 `uid=2000`（远程唯一），避免本地 `id` 的 `u0_a` 误匹配提前 break。

### 历史作废声明
- **previous c8-c12 (AdbCliBridgeTest) PASS 记录 INVALIDATED**（当时 `session.read()` 在
  listener 激活时恒返回 null → vacuous pass）。**不得复用** c8-c12 的旧 PASS 结论。
- 本轮 one-shot 结论基于修复后的 observer 模型 + BODY_EXECUTED 哨兵，独立成立。

---

## 6. 本轮实现 bug fixes（产品/测试代码）

1. **`AT_APP_APK` 空 → app_process ClassNotFoundException/Aborted**：bootstrap 改为写
   `adb_bootstrap.sh` 文件 + `source`（多行函数经 PTY 直写被 mangle）。
2. **Kotlin 字符串模板展开 bug**：`"-Djava.class.path=\"\$ENV_APK\""` 里 `$ENV_APK` 被 Kotlin
   展开成常量值 `AT_APP_APK`（而非 shell 变量 `$AT_APP_APK`）→ 改 `"$" + ENV_APK` 输出字面量
   `$AT_APP_APK`。
3. **`ensureReady` 只调一次 `connectAuto`**：NSD/TLS 异步，一次调用不足 → 改为轮询至多 15s
   （每 500ms 检查 `AdbRuntime.readinessState().isReady`）。
4. **O1 断言错误**：`command -v adb` / `type adb` 对 shell 函数只返回名字/“is a function”，
   不返回函数体 → 断言改为 `type adb` 含 `function`。
5. **O3 marker 误匹配**：本地 `id` 输出含 `u0_a`，作为 marker 会在远程 one-shot 返回前误匹配
   提前 break → marker 只用 `uid=2000`。
6. **observer 捕获 flaky**：PTY 输出分批到达，one-shot 输出与 trailing `echo` 分两批 →
   marker 出现后多等 2.5s 再 removeObserver。

---

## 7. 明确 NOT COVERED（留 AT.CLI-0.1A）

| 场景 | 本轮状态 | 归属 |
|---|---|---|
| WD = OFF（自动开启） | NOT COVERED | AT.CLI-0.1A Auto-WD |
| identity = UNTRUSTED（重配对） | NOT COVERED | AT.CLI-0.1A |
| ADB DISCONNECT / RECONNECT | NOT COVERED | AT.CLI-0.1A connection status |
| disconnected/reconnecting/connected terminal status events | NOT STARTED | AT.CLI-0.1A |
| interactive `adb shell`（无参） | NOT STARTED | 后续 |
| Ctrl-C / resize interactive relay | NOT STARTED | 后续 |

> 本轮 one-shot 验证**依赖** WD=ON + trusted + READY 前置；一旦前置不满足（如 WD OFF），
> ADB 进入 `USER_ACTION_REQUIRED`，one-shot 无法 READY。该生命周期/状态集成正是
> **AT.CLI-0.1A** 的目标：`adb command → ensureReady → WD OFF 自动开启 → discovery →
> connect → connected → execute` + `disconnected/reconnecting/connected` terminal status。
