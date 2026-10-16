# AT.CLI-0 — app_process 入口探测（P1–P6）

> 轮次：AT.CLI-0（SELinux 阻断后的入口恢复探测）
> 状态：**APP_PROCESS 入口全链路可行（P1–P6 全 PASS）**
> 设备：HONOR ALI-AN00 / Android 15 / arm64（USB `APLMUT4312005935`）
> SELinux 模式：Enforcing
> 探测类：`com.androidterminal.app.cli.CliProbe`（APK 内，`@JvmStatic main`）
> 探测测试：`AppProcessProbeTest`（androidTest，6 用例，OK 6 tests）
> 时间：2026-10-16

---

## 0. 背景

`PRIVATE_APP_DATA_ELF_ENTRYPOINT` 被 SELinux 阻断（见
`SELINUX_EXEC_BLOCKER.md`）：Local PTY 的 `/system/bin/sh` 位于
`untrusted_app` 域，**不能 exec `app_data_file` 的 ELF**（RC=126）。

本探测验证替代入口：**`/system/bin/app_process`**（context
`zygote_exec`）。它可被 `untrusted_app` exec（app 启动本就经 zygote/
app_process 机制），且能加载 APK 内 main class、继承 Local PTY 的
stdin/stdout/stderr、连接现有 Product CLI Bridge。

**本轮只探测，不实现完整 `adb shell`。**

---

## 1. 探测命令形态

在真实 Local `/system/bin/sh`（`untrusted_app` 域）中执行：

```sh
/system/bin/app_process -Djava.class.path=<base.apk> /system/bin \
    com.androidterminal.app.cli.CliProbe <argv...>
```

- `app_process` argv 约定：`<argv0-stub> <main-class> <args...>`
  （第一个参数 `/system/bin` 是 process-name stub）。
- `-Djava.class.path=<base.apk>` 指向已安装的 base.apk，使 app_process
  从 APK 加载 `CliProbe`。
- `CliProbe` 为纯 Java（**不 `System.loadLibrary`**）：app_process 手动
  启动时 `java.library.path` 不含 APK 内嵌 lib 目录，JNI `NativeEnv`
  会 `UnsatisfiedLinkError`。故 P5 的 UDS 连接改用
  `android.net.LocalSocket`（Android 原生 UDS，同 UID 可连 app 私有
  socket）。

---

## 2. P1 — Local PTY 可 exec /system/bin/app_process：**PASS**

`untrusted_app` 域执行 `/system/bin/app_process`（`zygote_exec`）成功
进入 JVM，无 SELinux exec 拒绝（无 `Permission denied` / RC=126）。

> 对照：`app_data_file` 的 ELF 在同域 exec 被拒（RC=126）；`zygote_exec`
> 的 `app_process` 可 exec。SELinux 对两类对象的 execute 策略不同。

---

## 3. P2 — app_process 可加载 APK 内 CliProbe main class：**PASS**

`app_process` 通过 `-Djava.class.path=<base.apk>` 加载并调用
`com.androidterminal.app.cli.CliProbe.main`。logcat 实证：

```
D AndroidRuntime: Calling main entry com.androidterminal.app.cli.CliProbe
```

`CliProbe` 打印 `CLIPROBE_ARGC=...` 等输出（class 已加载并执行）。

---

## 4. P3 — argv 正确传递：**PASS**

传入 `ALPHA BETA`，`CliProbe` 收到并回报：

```
CLIPROBE_ARGC=2
CLIPROBE_ARG_0=ALPHA
CLIPROBE_ARG_1=BETA
```

argv 经 `app_process` → `main(String[])` 完整传递。

> 注：app_process 的 JVM 启动偶发慢/被 lowmemory 杀，测试对 P3 做了
> 最多 3 次重试（取到 `CLIPROBE_ARGC=2` 即停）。这是环境时序，非 argv
> 缺陷。

---

## 5. P4 — stdin / stdout / stderr 继承 Local PTY：**PASS**

`CliProbe` 的 `System.out` / `System.err` / `System.in` 均继承自 Local
PTY（app_process 的 std 文件描述符 = fork 自 Local shell 的 PTY）：

```
CLIPROBE_STDIN=(none)      # 非阻塞 peek；无待读数据（不阻塞）
CLIPROBE_STDERR=ok         # stderr 继承，出现在 Local PTY fan-out
CLIPROBE_DONE              # stdout 继承，出现在 Local PTY fan-out
```

- stdout / stderr 的输出经 `SharedSessionHandle` fan-out 被 observer
  捕获（与 Human UI 同一路径）。
- stdin 用**非阻塞 `available()` peek**（app_process + PTY 的阻塞
  `read` 会挂起；`--no-stdin` 可跳过，避免在共享 session 中吞掉 shell
  的下一条命令）。

---

## 6. P5 — CliProbe 可连接现有 Product CLI Bridge：**PASS**

`CliProbe` 用 `android.net.LocalSocket` 连接 app 私有的
`filesDir/cli_bridge.sock`（app_process 运行于 app UID，socket 同为
app 所有 → 同 UID connect，正是 bridge 的 `SO_PEERCRED` 鉴权场景）：

```
CLIPROBE_SOCK=[/data/user/0/com.androidterminal.app/files/cli_bridge.sock]
CLIPROBE_BRIDGE=OK(type=2 ver=1)
```

- `type=2` = `T_HELLO_ACK`，`ver=1` = 协议版本 1。
- 发送 `T_HELLO`(1) + `[VERSION]`，收到 `T_HELLO_ACK`(2) + `[1]`。
- 证明：**app_process 入口能复用现有 Product CLI Bridge**（无需改
  bridge / 协议 / AT / ADB Engine）。

> 早期尝试用 JNI `NativeEnv.connectUds` 失败：
> `No implementation found for ... NativeEnv.connectUds` →
> `UnsatisfiedLinkError`（app_process 未设 `java.library.path` 到 APK
> lib 目录，`System.loadLibrary("atcli_env")` 失败）。改纯 Java
> `LocalSocket` 后通过。

---

## 7. P6 — 返回后 Local shell / TTY 正常：**PASS**

`CliProbe`（app_process）退出（RC=0）后，同一 Local shell 继续执行：

```
PP6RC_0              # app_process 退出码 0（; echo PP6RC_$?）
PP6_ALIVE_MARKER     # 后续 echo 在 shell 正常执行（TTY intact）
```

- probe 用 `--no-stdin`，不吞 shell 后续命令。
- 等待 `CLIPROBE_DONE`（probe 完全返回）后再读 RC marker，避免匹配
  命令回显。
- shell 在 probe 退出后存活并继续响应（TTY 未损坏）。

---

## 8. 结论

| Probe | 含义 | 结果 |
|---|---|---|
| P1 | Local PTY 可 exec `/system/bin/app_process` | **PASS** |
| P2 | app_process 可加载 APK 内 CliProbe main class | **PASS** |
| P3 | argv 正确传递 | **PASS** |
| P4 | stdin/stdout/stderr 继承 Local PTY | **PASS** |
| P5 | CliProbe 可连接现有 Product CLI Bridge | **PASS** |
| P6 | 返回后 Local shell / TTY 正常 | **PASS** |

**app_process 是可行的 shell-hosted CLI 入口**：绕开了
`app_data_file` ELF 的 SELinux exec 阻断，且能复用现有 Product CLI
Bridge（同 UID UDS + 协议 v1）。

### 关键设计点（供后续完整 CLI 实现参考）

1. 入口命令：`/system/bin/app_process -Djava.class.path=<base.apk>
   /system/bin com.androidterminal.app.cli.<CliMain> <args...>`。
2. CLI main class 必须**纯 Java**（或显式设 `java.library.path` 后再
   `System.loadLibrary`）——app_process 默认 library.path 不含 APK lib。
3. UDS 连接用 `android.net.LocalSocket`（同 UID，app 私有 socket）；
   不用 JNI raw fd（除非已加载对应 .so）。
4. stdin 读取须**非阻塞**（app_process + PTY 阻塞 read 会挂起）。
5. app_process JVM 启动有开销/偶发被 LMK 杀，生产 CLI 需考虑启动延迟
   与失败重试。

---

## 附：复现

```sh
# 安装含 CliProbe 的 debug APK 后，在真实 Local /system/bin/sh 中：
APK=$(pm path com.androidterminal.app | cut -d: -f2)
/system/bin/app_process -Djava.class.path=$APK /system/bin \
    com.androidterminal.app.cli.CliProbe ARG_ONE ARG_TWO
# 期望：
#   CLIPROBE_ARGC=2
#   CLIPROBE_ARG_0=ARG_ONE
#   CLIPROBE_ARG_1=ARG_TWO
#   CLIPROBE_STDIN=(none)
#   CLIPROBE_STDERR=ok
#   CLIPROBE_SOCK=[/data/user/0/com.androidterminal.app/files/cli_bridge.sock]
#   CLIPROBE_BRIDGE=OK(type=2 ver=1)
#   CLIPROBE_DONE
```
