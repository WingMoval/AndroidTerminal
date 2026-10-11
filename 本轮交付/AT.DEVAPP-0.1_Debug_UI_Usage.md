# AT.DEVAPP-0.1 — Debug UI Usage（自动化测试界面可视化操作说明）

> 为后续 OpenCode 复用：如何在真实 AndroidTerminal APK 界面中观察自动化测试 Session 的输入/输出/状态，并获取截图。

---

## 0. 前置：确认设备 + 构建

```powershell
$adb = "C:\android\platform-tools\adb.exe"
& $adb devices -l   # 预期：APLMUT4312005935  device

$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot"
cd D:\codebank\AndCode\AndroidTerminal-V3
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

## 1. 安装 Dev APK（含测试观察 UI）

```powershell
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

## 2. 启动 UI 可视化测试

```powershell
& $adb install -r -t "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
& $adb shell am instrument -w -e class com.androidterminal.app.AppDebugSessionVisibilityTest `
  com.androidterminal.app.test/androidx.test.runner.AndroidJUnitRunner
```

**预期**：`OK (7 tests)`。

## 3. 查看测试正在运行的界面

- 测试通过 `ActivityScenario` 启动真实 MainActivity；测试运行期间界面会显示测试观察区（`tvTestOutput`，绿色背景）。
- 测试期间界面实时更新，结束后 Activity 关闭。可运行单个用例观察：

```powershell
& $adb shell am instrument -w -e class "com.androidterminal.app.AppDebugSessionVisibilityTest#ui1_basicCommandVisibleInUi" `
  com.androidterminal.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 4. 区分 Human Session 与 Test Session

- **Human Session**：MainActivity 自己的 `AppTerminal` → `tvOutput`（黑底白字，无标签）。
- **Test Session**：测试创建的独立 `AppTerminal` → `tvTestOutput`（深蓝底绿字，带 `[TEST INPUT]` / `[PTY OUTPUT]` / `[STATE]` 标签）。
- 两者独立；Test Session 不接管 Human Session。

## 5. 发送测试输入

- 测试内通过 `AppTerminal.write(bytes)`（Byte Stream 语义）：
  - 文本+换行：`t.writeText("echo X"); t.newline()`
  - 原始字节：`t.write(byteArrayOf(0x03))`（Ctrl-C）
  - 前台 stdin：`t.writeText("cat"); t.newline(); t.writeText("abc"); t.newline()`
- 测试调用 `observer.onTestInput(bytes)` 记录发送内容（显示为 `[TEST INPUT]`）。

## 6. 捕获测试输出

- 真实 PTY 输出经 `ATSessionEvent.Data` → `TestSessionObserver` → `[PTY OUTPUT]` 行 → `MainActivity.appendTestLine`。
- 测试用 `activityRef.testObservationText()` 读回 UI 文本断言。
- observer 的 `log` 累积完整日志（可用于报告）。

## 7. 断言实际 UI 内容

```kotlin
// 在 AppDebugSessionVisibilityTest 内：
val ui = activityRef.testObservationText()
assertTrue(ui.contains("AT_UI_DEBUG_OK") && ui.contains("[PTY OUTPUT]"))
```

## 8. 获取真机截图

- 测试内 `saveScreenshot(name)` 调 `uiAutomation.takeScreenshot()`，存到设备：
  `/sdcard/Android/data/com.androidterminal.app/files/at_devapp_01/<name>.png`
- 拉取到仓库：

```powershell
& $adb pull "/sdcard/Android/data/com.androidterminal.app/files/at_devapp_01/." "docs\evidence\at_devapp_01\"
```

- 已保存证据：`docs/evidence/at_devapp_01/ui1_basic.png ... ui7_human.png`（7 张，真机截取）。

## 9. 获取测试报告

- instrumentation 输出（`OK (7 tests)` / `FAILURES!!!` + 断言详情）。
- 失败时用例断言内会 `Log.e("ATDev01", ...)` 打印实际 UI 文本：

```powershell
& $adb logcat -d -s ATDev01:E
```

## 10. 清理测试 Session

- 测试 `tearDown`：`testTerminal.close()`（native cleanup + reap）+ `scenario.close()`（Activity onDestroy → 关闭 Session）。
- 测试内 `ui6_cleanupNoLeak` 验证 close 幂等、close 后 write=-1、detach observer。
- 手动清理测试 APK（保留 Dev APK）：

```powershell
& $adb uninstall com.androidterminal.app.test
& $adb shell pm list packages | Select-String "com.androidterminal.app"   # 保留
```

## 11. 保留 Dev APK

- **不得卸载** `com.androidterminal.app`。测试结束后它应保持可人工使用。

## 12. Headless 测试仍可不经 UI

- `TestHostHeadlessInputTest`、`LocalPersistentSliceTest` 仍为独立 headless 测试，无需 UI。
- 只有需要"测试输出可视化"时才跑 UI 测试（AppDebugSessionVisibilityTest）。

## 13. 安全边界

- 测试观察能力 = debug-only instrumentation；无 exported receiver / 网络端口 / 常驻 Service / 绕过 AT Engine 的接口。
- `TestSessionObserver` / `attachTestObserver` 仅存在于 app 代码（debug 构建）；不成为 Release 公开能力。