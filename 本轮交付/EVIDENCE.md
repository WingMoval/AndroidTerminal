# AT.TERM-0 Evidence — Shell-independent AT Engine Validation

> 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）
> 状态：**COMPLETE**（构建/审计/代码 + 真机 G2-G19 + 全回归 全 PASS）
> 日期：2026-10-13

## 0. 基线

```text
V3 HEAD = d81e6ca（clean）
V2 HEAD = 726a441（43 项会前 dirty，零写入）
```

## 1. Artifact Isolation（G0）— PASS

### Gradle dependency graph（debugRuntimeClasspath）

```text
Project ':at-shellless-test-host'
\--- project :at-engine
（无 :at-backend-local / :at-backend-adb / :adb-engine / :app）
```

### APK native libs

```text
lib/arm64-v8a/libat_shellless_probe.so   （唯一）
forbidden (at_local_backend / at_adb / libadb) = (none)
```

### DEX string audit（classes.dex .. classes4.dex）

```text
com/at/backend/local     = (none)
com/at/backend/adb       = (none)
com/adb/engine           = (none)
com/androidterminal/app  = (none)
/system/bin/sh           = (none)
sh -c                    = (none)
toybox sh                = (none)
/bin/sh                  = (none)
```

### Source shell-string audit

```text
at-shellless-test-host/src 内 /system/bin/sh|sh -c|toybox|/bin/sh|ProcessBuilder|
Runtime.exec|system(|popen|execve|kShell 共 8 处，全部为注释（描述"不执行 shell"）。
实际可执行/runtime path = 0。
SYSTEM_BIN_SH_RUNTIME_DEPENDENCY = ABSENT
```

## 2. AT Engine unit（JVM，无需设备）

```text
ATExecutionContractTest 3/3 + ATSessionContractTest 3/3 = 6/6 PASS
```

## 3. 构建

```text
:at-shellless-test-host:assembleDebug            BUILD SUCCESSFUL（libat_shellless_probe.so）
:at-shellless-test-host:assembleDebugAndroidTest BUILD SUCCESSFUL
APK:
  at-shellless-test-host-debug.apk              1213804B
  at-shellless-test-host-debug-androidTest.apk   417618B
```

## 4. 真机 Gate（G2-G19）— 全 PASS

```text
ShelllessRealDeviceTest = OK (15 tests)  [14 G-gates + 1 /proc capture]

G2  child not /system/bin/sh        PASS（/proc 证据见 §6）
G3  real PTY isatty 0/1/2 = 1       PASS（READY: tty=/dev/pts/<n> isatty0/1/2=1）
G4  initial 33x99 (TIOCGWINSZ)      PASS（child 自身读出 rows=33 cols=99）
G5  write->child->read roundtrip    PASS（PING alpha -> PONG alpha）
G6  chunked input                   PASS（"PING "+"beta+"\n" -> PONG beta）
G7  UTF-8 byte preservation         PASS（PING 中文测试 + split-utf8 -> byte-exact）
G8  resize 41x123 child-observed    PASS（RESIZE rows=41 cols=123 + Resized event）
G9  control byte 0x03 -> SIGINT     PASS（0x03 -> SIGINT marker；ISIG 信号路径）
G10 natural EXIT 7 -> Exited(7)     PASS（state EXITED）
G11 explicit close -> CLOSED        PASS（write 后 close 返回 -1，无残留）
G12 pull mode                       PASS（READY/PONG；timeout 空数组；EOF null）
G13 push/listener mode              PASS（Data/Resized/Exited/Closed）
G14 3 concurrent sessions           PASS（PING A/B/C 无串流）
G15 session resize isolation        PASS（A=20x70 / B=40x120 / C 无 RESIZE）
G16 close-one isolation             PASS（close B 不影响 A/C）
G17 sequential lifecycle 20/20      PASS（无 leak；post-cycle 新 session 正常）
G18 natural exit -> reopen          PASS（A EXIT 7 后 B 正常 PONG）
G19 bounded failure path            PASS（未知 selector "rm -rf /" -> Failure，无 shell）
```

## 5. Regression — 全 PASS

```text
Local (LocalPersistentSliceTest)        OK (11)
App (app.test)                          OK (16)
Headless (TestHostHeadlessInputTest)    OK (1)
ADB Backend (AdbBackendHeadlessTest)    OK (25)   [port 42173, PC key, WD=1]
Backend Parity (BackendParityAuditTest) OK (34)   [port 42173]
AdbRuntimeLifecycle (12)                OK (12)
Pairing deterministic (pairing_selftest) 54 PASS / 0 FAIL（真机 /data/local/tmp）
AT Engine unit (JVM)                    6/6
```

## 6. 运行证据（真机 /proc + logcat）

### child /proc 身份（g2_procCapture，child 存活时采集）

```text
CHILD_PID   = 17038
CMDLINE     = com.androidterminal.shelllesshost（fork 继承原 cmdline，未 exec 任何 shell）
EXE         = /system/bin/app_process64   ← NOT /system/bin/sh
TTY_FD0     = /dev/pts/1                  ← 真实 PTY
NAME        = ...roidJUnitRunner（instrumentation 进程名，fork 继承）
PPid        = 16696（= test 进程；child 是其子进程）
Uid         = 10357（= shelllesshost app uid，非 shell uid 2000）
```

### READY 行（G2/G3/G4）

```text
READY pid=14404 tty=/dev/pts/0 isatty0=1 isatty1=1 isatty2=1 rows=33 cols=99
```

### logcat（resize 因果）

```text
AtShelllessPty: parent TIOCSWINSZ master=90 -> 41x123 rc=0 errno=0
AtShelllessPty: parent readback master=90 = 41x123
AtShelllessPty: child winsize changed 33x99 -> 41x123
```

```text
CHILD_EXECVE_USED = NO
CHILD_PROGRAM = in-process native probe after fork
CHILD_EXE = /system/bin/app_process64（非 shell）
```

## 7. 设备清理

```text
shellless 测试文件 (shellless_proc/child/pid.txt)  = 已删
shellless APK (main + test)                        = 已卸载
/data/local/tmp (pairing_selftest, ps_out, atkey*, aokey*) = 已删
残留 shellless 进程                                = 无
残留 zombie (defunct)                              = 0
残留 /dev/pts                                      = 0
主 App (com.androidterminal.app)                   = 保留
WD 恢复开局值                                      = 1
（本轮未新增 pairing trust residue；ADB 回归用已有 PC key）
```

## 8. 安全

```text
本轮无需 ADB private key / pairing code（shellless 路径 ADB 完全 ABSENT）。
ADB 回归用 PC key（设备已信任，DEBT-1 前既有 trust）。
无用户敏感数据。测试 identity 未 commit。
```