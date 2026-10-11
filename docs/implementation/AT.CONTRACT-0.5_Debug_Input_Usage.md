# AT.CONTRACT-0.5 — Debug Input Usage（OpenCode 可直接执行的自动化调试命令）

> 用途：为后续 OpenCode 调试 AT Engine / TerminalSession 提供**可重复、非 IME** 的程序化输入与结果捕获方式。
>
> 实现形态：**Android instrumentation 测试**（无网络端口 / 无 Service / 无 exported receiver / 无 UI 点击 / 无模拟键盘）。

---

## 0. 前置：确认设备

```powershell
$adb = "C:\android\platform-tools\adb.exe"
& $adb devices -l
# 预期：APLMUT4312005935  device  （HONOR ALI-AN00）
```

## 1. 三条调试入口（当前能力）

| 入口 | 路径 | 能力 |
|---|---|---|
| **Headless**（独立 consumer） | `:test-host` → `TestHostHeadlessInputTest` | 程序化建 session + write + 捕获输出/状态；1 个预设用例（`HEADLESS_OK` + 多写） |
| **Local 全量**（headless，独立） | `:at-backend-local` → `LocalPersistentSliceTest` | P1–P10 + one-shot，11 用例，真机回归 |
| **Dev App consumer**（instrumentation） | `:app` → `AppDebugInputTest` | 与 Human UI 同一 `AppTerminal` 路径；7 用例（T1/T3–T8） |

> **当前均为预设测试用例**，不支持任意命令动态参数注入。要新增命令：在测试类内加一个 case（见 §6）。不要声称支持任意动态输入。

## 2. 一键运行（clean build + 全部相关测试）

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot"
cd D:\codebank\AndCode\AndroidTerminal-V3
.\gradlew.bat clean assembleDebug :app:assembleDebugAndroidTest `
  :test-host:assembleDebug :test-host:assembleDebugAndroidTest `
  :at-backend-local:assembleDebugAndroidTest :at-engine:testDebugUnitTest --console=plain
```

## 3. 运行 Dev App 自动化输入测试（T3 主入口）

```powershell
$adb = "C:\android\platform-tools\adb.exe"
# 1) 先安装/更新 Dev APK（AppDebugInputTest 依赖 App 内 AppTerminal 类）
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
# 2) 安装测试 APK
& $adb install -r -t "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
# 3) 运行
& $adb shell am instrument -w com.androidterminal.app.test/androidx.test.runner.AndroidJUnitRunner
```

**预期输出**：

```text
com.androidterminal.app.AppDebugInputTest:.......
OK (7 tests)
```

用例含义：

| Case | 验证 |
|---|---|
| `t1_humanPathShared` | Human 与 Debug 共用 AppTerminal 语义；`HUMAN_OK` |
| `t3_appDebugAutomatedInput` | `write("echo APP_DEBUG_OK\n")` → 输出出现，State ACTIVE |
| `t4_byteStreamSemantics` | `write("echo PARTIAL")` + `write("\n")` → newline 才执行 |
| `t5_interactiveStdin` | `cat` 前台 + 写 `INPUT_TEST\n` → 回显 |
| `t6_ctrlC` | `write(0x03)` 中断 `sleep 30`，shell 存活，后续命令可执行 |
| `t7_sessionContinuity` | env 跨命令保持 |
| `t8_cleanup` | close 幂等；close 后 write=-1 |

## 4. 运行 Headless 输入回归（T2）

```powershell
& $adb install -r "test-host\build\outputs\apk\debug\test-host-debug.apk"
& $adb install -r -t "test-host\build\outputs\apk\androidTest\debug\test-host-debug-androidTest.apk"
& $adb shell am instrument -w com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
```

**预期**：`TestHostHeadlessInputTest:.` → `OK (1 test)`（`HEADLESS_OK`）。

## 5. 运行 Local 全量真机回归（P1–P10）

```powershell
& $adb install -r -t "at-backend-local\build\outputs\apk\androidTest\debug\at-backend-local-debug-androidTest.apk"
& $adb shell am instrument -w com.at.backend.local.test/androidx.test.runner.AndroidJUnitRunner
```

**预期**：`OK (11 tests)`。

## 6. 如何发送原始字节 / 新增用例

输入统一为 `session.write(bytes)`（Byte Stream 语义，AT Engine 不加换行）：

```kotlin
// 普通文本 + 显式换行
session.write("echo OK\n".toByteArray())
// 不自动换行（分段）
session.write("echo PARTIAL".toByteArray()); session.write("\n".toByteArray())
// Ctrl-C
session.write(byteArrayOf(0x03))
// 前台 stdin
session.write("cat\n".toByteArray()); session.write("hello\n".toByteArray()); session.write(byteArrayOf(0x04))
```

新增调试命令：在 `AppDebugInputTest` / `TestHostHeadlessInputTest` 内加一个 `@Test`，用 `openWithCapture()`（或等价）建 session，`write(...)`，`await(...)` 断言输出。然后按 §3/§4 重装重跑。

## 7. 结果判断

- **成功**：输出含预期标记（`APP_DEBUG_OK` / `HEADLESS_OK` / `PARTIAL` 等）+ State 断言通过。
- **失败**：`FAILURES!!!` + 具体 AssertionError；按用例定位。
- **不要**以 `write()` 不抛异常当作执行成功——用输出/状态断言（测试内已如此）。

## 8. 清理与避免干扰

- 测试 APK 可卸载；**Dev APK 保留**：

```powershell
& $adb uninstall com.androidterminal.app.test     # 调试测试 APK
& $adb uninstall com.androidterminal.testhost      # 如已装
& $adb uninstall com.at.backend.local.test         # 如已装
# Dev APK：com.androidterminal.app 保留（不卸载）
& $adb shell pm list packages | Select-String "com.androidterminal.app"
```

- 自动化测试创建**受控测试 Session**，不接管用户正在使用的 Session；测试结束后 session close + native cleanup。
- 若需观察 Dev APK 手动使用，直接点开 App 即可（Human UI 未受影响）。

## 9. 安全边界（已满足）

- 无 exported receiver / 无 TCP 控制端口 / 无公网接口 / 无常驻服务 / 无绕过 AT Engine 的 shell 接口。
- `AppTerminal` / `AppDebugInputTest` 仅存在于 debug/test 构建；不成为 Release 公开能力。

## 10. 限制

- 调试入口为**预设测试用例**；不支持任意命令运行时注入（参数化需加测试用例）。
- Raw terminal（无 Emulator）：`vim`/`top` 全屏行为不正确（预期）。