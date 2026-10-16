# AT.CLI-0 — SELinux EXEC Blocker（阻断性发现）

> 轮次：AT.CLI-0（shell-hosted `adb` CLI vertical slice）
> 状态：**PRIVATE_APP_DATA_ELF_ENTRYPOINT = REJECTED**
> 设备：HONOR ALI-AN00 / Android 15 / arm64（USB `APLMUT4312005935`）
> SELinux 模式：**Enforcing**
> 结论时间：2026-10-16

---

## 0. 一句话结论

AT.CLI-0 原设计的入口（把 `adb` CLI 作为 ELF 二进制放进 app 私有
`files/bin/adb`，注入 Local `/system/bin/sh` 的 PATH 由 shell `exec`）
**被 SELinux 阻断**：Local PTY 的 `/system/bin/sh` 位于
`untrusted_app` domain，**没有对 `app_data_file` 类文件的 `execute` 权限**，
`exec files/bin/adb` 返回 **Permission denied / RC=126**。

因此：

- `PRIVATE_APP_DATA_ELF_ENTRYPOINT = REJECTED`
- c8–c12 旧结果 **全部 INVALIDATED**（见 §6）
- 需要改入口（app_process 方向）或改范围，**不 root、不改 SELinux、
  不改 AT/ADB Engine、不复用 DebugBridge 作产品协议**。

---

## 1. 真实 Local PTY 的 UID / context

Local PTY 的 `/system/bin/sh` 由 **app 进程 fork**（`local_pty.cpp`
`create_pty_shell` → `fork()` → `execve("/system/bin/sh", ...)`），
继承 app 进程的 UID 与 SELinux domain：

| 项 | 值 |
|---|---|
| UID | `10313`（`u0_a313`）= app UID |
| SELinux context（app 进程 = Local PTY shell） | `u:r:untrusted_app:s0:c57,c257,c512,c768` |
| 采集方式 | `cat /proc/<appPid>/attr/current`（appPid = `pidof com.androidterminal.app`） |

app 进程自身 context 实测（`/proc/19570/attr/current`）：

```
u:r:untrusted_app:s0:c57,c257,c512,c768
```

Local PTY shell 与 app 进程同域（fork 继承，无 domain transition 到可
exec `app_data_file` 的域）。

---

## 2. run-as 的 UID / context

`run-as com.androidterminal.app` 进入的 shell 与 app 进程**不同域**：

| 项 | 值 |
|---|---|
| UID | `10313`（`u0_a313`）= app UID（同） |
| SELinux context（run-as shell） | `u:r:runas_app:s0:c57,c257,c512,c768` |
| 采集方式 | `run-as com.androidterminal.app sh -c 'id; cat /proc/self/attr/current'` |

`runas_app` domain **有**对 `app_data_file` 的 execute 权限（系统为
`run-as` 调试场景放行），因此 run-as 下能执行 app 私有 ELF。

---

## 3. adb 二进制的 path / mode / context

| 项 | 值 |
|---|---|
| path | `/data/user/0/com.androidterminal.app/files/bin/adb` |
| mode | `-rwxr-xr-x`（exec 位已设） |
| owner | `u0_a313 u0_a313` |
| SELinux context | `u:object_r:app_data_file:s0:c57,c257,c512,c768` |
| 类型 | ELF 64-bit LSB arm64, dynamic (`/system/bin/linker64`), Android 26, NDK r26b, 33200 bytes |
| 采集方式 | `run-as ... ls -laZ .../files/bin/adb`、`run-as ... file .../files/bin/adb` |

二进制本身**完全正常**（可执行位、ELF、arm64 均正确），问题不在二进制，
在执行它的 **domain 权限**。

---

## 4. run-as 执行：PASS

`run-as`（`runas_app` domain）执行该 ELF **成功**：

```
$ run-as com.androidterminal.app /data/user/0/com.androidterminal.app/files/bin/adb version
AndroidTerminal adb CLI (bridge protocol v1)

$ run-as com.androidterminal.app sh -c '/data/.../files/bin/adb --help'
usage: adb shell [command...]
       adb shell            (interactive remote shell)
       adb --help | adb version
AndroidTerminal product CLI (shell subcommand only).
```

→ 二进制可执行、逻辑正确、bridge 协议 v1 标识正常。**排除二进制缺陷。**

---

## 5. 真实 Local PTY 执行：RC=126（Permission denied）

通过 AT.CLI-0 的 observer 模型（`SharedSessionHandle` fan-out，**非**
`LocalTerminalSession.read()`）在真实 Local `/system/bin/sh` 中执行：

```
输入:  adb devices 2>&1; echo RC_MARK=$?
输出:  adb devices 2>&1; echo RC_MARK=$?
       /system/bin/sh: /data/user/0/com.androidterminal.app/files/bin/adb: Permission denied
       RC_MARK=126
```

- `RC_MARK=126` = "command found but not executable"（SELinux exec 拒绝的典型返回码）。
- **不是** `RC=2`（unsupported 子命令路径）。即：`adb` 根本没能被 exec，
  连 parser 的 unsupported 分支都没走到。
- 同样地，`adb shell`（c8–c12 的目标）在真实 Local PTY 也会命中同一
  SELinux 阻断（`adb` 无法 exec）。

**根因**：`untrusted_app` domain 对 `app_data_file` 类对象**无 `execute`
权限**（Enforcing）。Local PTY shell = `untrusted_app` → exec `files/bin/adb`
被拒。`runas_app`（run-as）有该权限 → 通过。

> 对照：`run-as` 域连 `/data/local/tmp` 都写不进（`runas_app` 亦受限），
> 说明这是 domain 级策略，而非单纯 exec 位问题。

---

## 6. c8–c12 旧结果 INVALIDATED

**c8–c12（interactive `adb shell` 系列）此前的"PASS"全部作废，
不得继续引用为 PASS。**

两条独立原因叠加：

1. **test observation model 不一致 / 空通过**
   - `createPage` 必给 Local 会话挂 listener
     （`AppTerminal.open` → `SharedSessionHandle.attachSessionListener`
     → `session.setListener{...}`）。
   - `LocalTerminalSession.read()` 在 `listener != null` 时**返回 null**
     （`LocalPersistentBackend.kt:79`；readerThread 独占 `pty.read()`，
     这是 by-design 的单一消费者语义，**不修改**）。
   - c8–c12 用 `readUntil` → `session.read()` 取输出。
   - 共享 `ActivityScenario` 对第 2+ 个测试的 `onActivity` **未实际执行
     body**（诊断 `c8_audit.txt` 从未写入 = body 从未运行 = 断言从未执行）。
   - 因此 c8–c12 的断言**从未真正运行**，属于**空通过**，不是 PASS。

2. **即便 body 真跑，也会撞 SELinux 阻断**
   - `adb shell` 需要 Local PTY shell exec `files/bin/adb`，
     与 §5 同一 `untrusted_app` exec `app_data_file` 拒绝（RC=126）。
   - 所以 c8–c12 即使修复 observation model，也无法在真实 Local PTY
     验证 `adb shell`（除非改入口）。

**处置**：c8–c12 标记 `INVALIDATED`。c13 改用 observer 模型后**成功读到
真实输出**（证明 observer 模型正确、reader fan-out 可用），但读到的是
SELinux 阻断（RC=126），不是 unsupported（RC=2）。

---

## 7. PRIVATE_APP_DATA_ELF_ENTRYPOINT = REJECTED

| 入口方案 | 结论 |
|---|---|
| app 私有 `files/bin/adb`（ELF）+ Local shell PATH exec | **REJECTED**（SELinux `untrusted_app` 无 `app_data_file` execute） |

不在本轮尝试的规避（受约束禁止）：

- ❌ root / 改 SELinux policy / `setenforce 0`
- ❌ 改 AT Engine / 改 ADB Engine
- ❌ 把 AT.DEBUG-0 的 `DebugBridge`(45679) 复用为产品 CLI 协议

**下一步（本轮已授权的最小探测）**：验证 `app_process` 入口可行性
（`/system/bin/app_process` 可被 `untrusted_app` exec，可加载 APK 内
main class，argv/stdin/stdout/stderr 继承 Local PTY，可连 Product CLI
Bridge，返回后 TTY 正常）。见
`APP_PROCESS_ENTRYPOINT_PROBE.md`（P1–P6）。

---

## 附：采集命令（可复现）

```sh
# app 进程 domain / uid
adb shell pidof com.androidterminal.app            # -> appPid
adb shell cat /proc/<appPid>/attr/current          # -> u:r:untrusted_app:...
adb shell stat -c '%u %n' /proc/<appPid>           # -> 10313

# run-as domain
adb shell run-as com.androidterminal.app sh -c 'id; cat /proc/self/attr/current'
#   -> uid=10313 ... context=u:r:runas_app:...

# 二进制
adb shell run-as com.androidterminal.app ls -laZ /data/user/0/com.androidterminal.app/files/bin/adb
adb shell run-as com.androidterminal.app file /data/user/0/com.androidterminal.app/files/bin/adb

# run-as 执行（PASS）
adb shell run-as com.androidterminal.app /data/user/0/com.androidterminal.app/files/bin/adb version

# 真实 Local PTY 执行（RC=126）：经 app 的 Local /system/bin/sh + observer 模型
#   输入: adb devices 2>&1; echo RC_MARK=$?
#   输出: /system/bin/sh: .../files/bin/adb: Permission denied ; RC_MARK=126
```
