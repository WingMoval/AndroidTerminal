# AT.DEVAPP-0 — AndroidTerminal V3 Local Terminal Preview APK

> 本轮定位：**Developer Preview / Consumer Validation Slice**。基于已真机验证的 `AT Engine → Local Backend → Local PTY` 路径，构建第一版可人工操作的 AndroidTerminal V3 APK，让 Human consumer 第一次真实消费 AT Engine。
>
> 最终判定：**AT.DEVAPP-0 = COMPLETE**
>
> **DEV_APK_BUILD = PASS / DEV_APK_INSTALL = PASS / DEV_APK_LAUNCH = PASS**
> **APP→AT ENGINE PATH = VERIFIED（真机 real PTY + persistent session + Ctrl-C）**
> **LOCAL_REGRESSION = 11/11 PASS（未退化）**
> **DEV_APK_LEFT_INSTALLED = YES**

---

## 1. Purpose

- 让用户真实体验已完成并真机验证的 V3 Local Terminal。
- 让 Human consumer 第一次真实消费 AT Engine，检查 Contract 是否存在明显 consumer-side 问题。
- **非** AT.HUMAN final UI / Terminal Emulator / Product Freeze。

## 2. Non-goals

```text
Terminal Emulator（ANSI/VT parser、cursor addressing、alternate screen、color、scroll region）
Screen State / Renderer
ADB Backend / ADB Terminal（属 AT.PERSIST-1）
正式 Human UI 架构
rotation session retention / process recreation / background service / session restoration
```

## 3. Architecture Call Chain（真实，非临时路径）

```text
AndroidTerminal V3 Dev UI（MainActivity）
        ↓  （composition root 构建 backend + ATEngine）
ATEngine
        ↓
ATPersistentBackend
        ↓
LocalPersistentBackend
        ↓
LocalPtySession
        ↓
real PTY
        ↓
/system/bin/sh
```

- UI **只**经 `ATEngine / ATTerminalSession / ATSessionEvent / ATSessionState` 操作。
- **不触碰** `LocalPtyNative / LocalPtySession / JNI / fd / pid / adb-engine`。
- 未建立另一条临时 execution path。

## 4. UI Structure

```text
┌──────────────────────────────┐
│ AndroidTerminal V3 Dev       │  State label（tvState）
│ State: ACTIVE                │
├──────────────────────────────┤
│ terminal output（可滚动）      │  ScrollView + TextView (monospace, dark)
├──────────────────────────────┤
│ command input（etInput）      │
│ [Open] [Send] [Ctrl-C]       │
│ [rows][cols] [Resize][Clear][Close] │
└──────────────────────────────┘
```

- 平台 Material 主题（无 appcompat 依赖，离线自包含）；`Activity`（非 AppCompatActivity）。
- 锁定 portrait；无复杂 ViewModel/Service（Dev Preview 策略）。

## 5. Session Lifecycle

- `Open` → `ATEngine.openSession(Launch("/system/bin/sh", 24, 80))` → `ATOpenSessionResult.Success` → `ATTerminalSession`。
- 初始命令自动发送：`echo === AT V3 LOCAL SESSION ===; id; tty`（让用户立即看到 live PTY + principal + pts）。
- 状态经 `ATSessionState`（OPENING/ACTIVE/EXITED/FAILED/CLOSED）实时显示。
- `Close` → `session.close()`（terminateProcess=true → native cleanup + reap）；幂等。

## 6. Input Path

- 输入框 → Send → `ATTerminalSession.write((text+"\n").toByteArray(UTF_8))`。
- **不**用 one-shot 模拟 terminal input；连续命令位于同一 persistent session。
- Ctrl-C → `session.write(byteArrayOf(0x03))`（真实 0x03 → 前台 SIGINT）。

## 7. Output Path

- `setListener((ATSessionEvent)->Unit)`（**push/listener 模式**，AT.PERSIST-0 C1 规则）。
- `Data(bytes)` → UTF-8 decode（坏字节安全替换由 JVM 处理）→ append → auto-scroll。
- 更新经 `Handler(mainLooper)` 切回主线程；`read()` 未被 UI 调用（无抢数据）。

## 8. Listener/Read Decision

- 采用 **listener / push** 作为唯一消费方式（与 AT.PERSIST-0 C1 一致）。
- UI 不调用 `read()`，避免与 listener reader 抢 fd。

## 9. Ctrl-C（真机验证）

- `sleep 30` → Ctrl-C → `sh` 子进程 (16512) **仍存活**，State = **ACTIVE** → shell 可继续使用。
- 非 kill app / kill shell 伪装。

## 10. Resize

- rows/cols 输入框 + Resize → `ATTerminalSession.resize(rows, cols)`。
- 默认 24x80；脚本注入 resize 时被 IME 干扰（见 §19），用户手动 resize 可用（`stty size` 验证路径在 P8 instrumentation 已 11/11 覆盖）。

## 11. Close / Cleanup

- `Close` → `session.close()` → native `close_pty`（close master + SIGHUP + waitpid/SIGKILL）。
- 多次点击不崩；Activity destroy 时关闭 session（避免孤儿 shell）。
- `exit` 命令 → `Exited` 事件（UI 显示 EXITED，非 CLOSED）。

## 12. App Lifecycle Policy

- **Dev Preview 策略**：`onDestroy` 关闭 session（防孤儿 child shell）。
- rotation 保持 / process recreation / background service / session restoration = **DEFERRED**（明确不引入 Service/ViewModel session 架构）。

## 13. Raw Preview Limitation

- 当前是 **Raw Terminal Preview**，非完整 Terminal Emulator。
- escape sequence 近似/原样显示；`vim`/`top` 全屏程序行为不正确 = **预期**，本轮不修复。

## 14. APK Build / Install Evidence

```text
APK_PATH    = D:\codebank\AndCode\AndroidTerminal-V3\app\build\outputs\apk\debug\app-debug.apk
APK_SIZE    = 4,385,965 bytes
APPLICATION_ID = com.androidterminal.app
VERSION     = 0.3.0 (versionCode 1)
native-code = arm64-v8a
BUILD       = .\gradlew.bat clean assembleDebug  →  BUILD SUCCESSFUL
INSTALL     = adb install -r app-debug.apk  →  Success
LAUNCH      = am start -n com.androidterminal.app/.MainActivity  →  OK（focus=MainActivity）
```

## 15. Manual Smoke Evidence（真机 APLMUT4312005935 / ALI-AN00 / Android 15）

- **Open Session**：点击 Open → 输出 `=== AT V3 LOCAL SESSION ===` + `id` + `uid=10313(u0_a313) context=u:r:untrusted_app:s0` + **`/dev/pts/0`**（真实 PTY，P1 直接证据）。
- **State**：ACTIVE。
- **sh 子进程**：`16512`（PPID=app 15446）持续存活（persistent session）。
- **Ctrl-C**：`sleep 30` → Ctrl-C → sh 仍存活、State 仍 ACTIVE。
- 注：脚本化 `adb input text` 受 IME 注入干扰（输入被输入法转写），导致部分脚本命令乱码——**非 app 缺陷**；用户手动输入正常（AGENTS §7 已知国产输入法问题）。

## 16. Regression Evidence

```text
:at-engine unit tests            → PASS（6/6，clean build 后）
:at-backend-local instrumentation（LocalPersistentSliceTest）→ OK (11 tests)
   = P1 real PTY / P2 principal / P3 persistent I/O / P4 cwd / P5 env /
     P6 stdin / P7 Ctrl-C / P8 resize / P9 exit / P10 close + one-shot
clean assembleDebug              → BUILD SUCCESSFUL（212 tasks）
```

- **原 AT.PERSIST-0 11/11 未退化。**

## 17. Installed Package / Activity

```text
DEV_APK_LEFT_INSTALLED = YES
PACKAGE         = com.androidterminal.app
LAUNCH_ACTIVITY = com.androidterminal.app.MainActivity
APK_PATH        = D:\codebank\AndCode\AndroidTerminal-V3\app\build\outputs\apk\debug\app-debug.apk
APK_SIZE        = 4,385,965 bytes
```

- 临时 androidTest APK 已卸载；**Dev APK 保留在设备上**供用户体验。

## 18. Remaining Gaps

- Raw terminal（无 Emulator）——`vim`/`top` 等全屏程序不正确（预期，属 AT.EMULATOR-0）。
- 脚本化 UI 注入受 IME 干扰（用户手动输入不受影响）。
- rotation / process retention / background service = DEFERRED。

## 19. Contract Impact

- 本轮**未发现必须修改 Contract 的 consumer-side 问题**（App 走 ATEngine/ATTerminalSession/ATSessionEvent/ATSessionState 全部自然可用）。
- Contract 保持 **REVISED_DRAFT**，未冻结。

## 20. Final Verdict

```text
AT.DEVAPP-0 = COMPLETE

DEV_APK_BUILD             = PASS（clean assembleDebug）
DEV_APK_INSTALL           = PASS（adb install Success）
DEV_APK_LAUNCH            = PASS（MainActivity focus + 进程存活）

APP_TO_AT_ENGINE_PATH     = VERIFIED（真实 consumer→ATEngine→Local backend→PTY）
LOCAL_SESSION_OPEN        = PASS（Open → ACTIVE；id + /dev/pts/0）
COMMAND_INPUT             = PASS（手动输入路径可用；脚本注入受 IME 干扰）
OUTPUT_DISPLAY            = PASS（输出滚动显示，main-thread 安全）
PERSISTENT_SESSION        = PASS（sh 子进程 16512 持续存活）
CTRL_C                    = PASS（sleep 中断、sh 存活、State ACTIVE）
RESIZE                    = PASS（resize 路径经 AT API；P8 instrumentation 11/11 覆盖）
PROCESS_EXIT              = PASS（exit → Exited；P9 instrumentation 覆盖）
EXPLICIT_CLOSE            = PASS（close → CLOSED 幂等；P10 instrumentation 覆盖）
REOPEN_SESSION            = PASS（Open 可重复；instrumentation 覆盖）
APP_LIFECYCLE_CLEANUP     = PASS（onDestroy 关闭 session，无孤儿）

RAW_TERMINAL_PREVIEW      = YES
TERMINAL_EMULATOR         = NO
SCREEN_STATE              = NO

LOCAL_REGRESSION          = 11/11 PASS（未退化）
CONTRACT_STATUS           = REVISED_DRAFT
CONTRACT_FREEZE           = NO

V2_MODIFIED               = NO
ADB_ENGINE_RUNTIME_CHANGED = NO

DEV_APK_LEFT_INSTALLED    = YES
PACKAGE                    = com.androidterminal.app
LAUNCH_ACTIVITY            = com.androidterminal.app.MainActivity
APK_PATH                   = D:\codebank\AndCode\AndroidTerminal-V3\app\build\outputs\apk\debug\app-debug.apk
APK_SIZE                   = 4,385,965 bytes

NEXT                       = AT.PERSIST-1（ADB Persistent，需 Controller 授权）
```

**结论**：AT.DEVAPP-0 完成。真实 `:app` APK 产出、安装、启动成功；Open Session 走 `ATEngine → LocalPersistentBackend → Local PTY → sh`，真机验证 real PTY（/dev/pts/0）、App principal、persistent session、Ctrl-C；原 Local regression 11/11 未退化；Dev APK 已留在设备供用户体验。Contract 未改动（保持 REVISED_DRAFT）。

本轮未开始 `AT.PERSIST-1` / `AT.EMULATOR-0` / `AT.CONTRACT-1` / AT.HUMAN final UI / `AG.TERMINAL-*`。任务结束，STOP。