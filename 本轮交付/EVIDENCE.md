# AT.TERM-0 Evidence — Shell-independent AT Engine Validation

> 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）
> 状态：构建/审计/代码 COMPLETE；**真机 G2-G19 + 回归待设备接入**
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

## 4. 真机 Gate（待设备）

```text
G2  child not /system/bin/sh        PENDING（/proc/<pid>/exe + cmdline）
G3  real PTY isatty 0/1/2 = 1       PENDING（READY line: tty=/dev/pts/<n> isatty*=1）
G4  initial 33x99 (TIOCGWINSZ)      PENDING
G5  write->child->read roundtrip    PENDING
G6  chunked input                   PENDING
G7  UTF-8 byte preservation         PENDING
G8  resize 41x123 child-observed    PENDING
G9  control byte 0x03 -> SIGINT     PENDING
G10 natural EXIT 7 -> Exited(7)     PENDING
G11 explicit close -> CLOSED        PENDING
G12 pull mode                       PENDING
G13 push/listener mode              PENDING
G14 3 concurrent sessions           PENDING
G15 session resize isolation        PENDING
G16 close-one isolation             PENDING
G17 sequential lifecycle 20/20      PENDING
G18 natural exit -> reopen          PENDING
G19 bounded failure path            PENDING
```

## 5. Regression（待设备）

```text
Local 11 / App 16 / Headless 1 / ADB 25 / Parity 34 / Runtime 12 / Pairing 54
（AT 6/6 已在 JVM 复跑 PASS）
```

## 6. 待采集运行证据（真机）

```text
child PID / /proc/<pid>/exe / /proc/<pid>/cmdline / /proc/<pid>/status
TTY_NAME / isatty 0/1/2 / initial 33x99 / PING-PONG / chunked / UTF-8
resize 41x123 / 0x03 behavior / EXIT 7 / close / pull / push / multi / sequential
（设备接入后回填本节）
```

## 7. 安全

```text
本轮无需 ADB private key / pairing code（ADB 完全 ABSENT）。
无用户敏感数据。
```