# AT.V3-2 — ADBTerminal Dev Usage（headless ADB Backend 验证使用说明）

> 供后续 OpenCode 复用：如何在真实设备上验证 AT Engine → ADB Backend → ADB Engine → adbd 链路。
>
> 本轮采用 **headless instrumentation**（无 UI APK）。后续如需 UI 版 ADB Terminal，可基于本说明 + AT.DEVAPP-0.2 SessionPage 复用。

---

## 0. 前置

```powershell
$adb = "C:\android\platform-tools\adb.exe"
& $adb devices -l     # 预期：APLMUT4312005935  device

# 启用无线调试（若关闭）
& $adb shell settings put global adb_wifi_enabled 1

# 确认 adbd 无线 connect 端口（uid 2000 监听 *:port）
& $adb shell "cat /proc/net/tcp6 2>/dev/null" | Select-String " 2000 "   # 记下 hex 端口
```

## 1. 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot"
cd D:\codebank\AndCode\AndroidTerminal-V3
.\gradlew.bat :test-host:assembleDebug :test-host:assembleDebugAndroidTest --console=plain
```

## 2. 安装 test-host + 注入 trusted identity

```powershell
& $adb install -r "test-host\build\outputs\apk\debug\test-host-debug.apk"
& $adb install -r -t "test-host\build\outputs\apk\androidTest\debug\test-host-debug-androidTest.apk"
# 注入 PC 已信任的 adbkey（设备已信任 Wing@DESKTOP-PG78M2N）
& $adb push "$env:USERPROFILE\.android\adbkey" /data/local/tmp/th_adbkey
& $adb push "$env:USERPROFILE\.android\adbkey.pub" /data/local/tmp/th_adbkey.pub
& $adb shell "run-as com.androidterminal.testhost sh -c 'mkdir -p files; cp /data/local/tmp/th_adbkey files/adbkey; cp /data/local/tmp/th_adbkey.pub files/adbkey.pub'"
```

## 3. 运行验证（headless）

```powershell
# 单个用例
$port = "38793"   # 以 adbd 实际无线端口为准
$key = "/data/user/0/com.androidterminal.testhost/files"

# Gate A one-shot
& $adb shell am instrument -w -e class "com.androidterminal.testhost.AdbBackendHeadlessTest#a2_principal" -e port $port -e keyDir $key com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
# Gate B persistent
& $adb shell am instrument -w -e class "com.androidterminal.testhost.AdbBackendHeadlessTest#b7_ttyAndResize" -e port $port -e keyDir $key com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
```

用例列表（Gate A）：a1_echo / a2_principal / a3_pwd / a4_stderrNonZero / a5_connectionReuse
（Gate B）：b1_openActive / b2_writeOutput / b3_cwdContinuity / b4_envContinuity / b5_stdin / b6_ctrlC / b7_ttyAndResize / b8_exitCloseReopen

> **注意**：不要整类运行（`-e class AdbBackendHeadlessTest`）——多个 persistent 用例共享 AdbEngine 单例连接，前一个 reader 线程残留会导致后续崩溃。**每个用例单独运行**。

## 4. 查看 transport / reuse 证据

```powershell
& $adb logcat -d | Select-String "ONLINE|OPEN service|state=Online tls=1|TLS_START|AdbClient::connect"
# 期望：多次 one-shot 显示同一 Online connection、每次新 OPEN、无重 TLS
```

## 5. 结果判断

- `OK (1 test)` = PASS；`FAILURES!!!` + AssertionError = 失败；`Process crashed` = ADB Engine 崩溃（多会话并发限制，见报告）。
- a2 应输出 `uid=2000(shell)`（ADB shell principal）。

## 6. 清理

```powershell
& $adb shell "rm -f /data/local/tmp/th_adbkey /data/local/tmp/th_adbkey.pub"
& $adb uninstall com.androidterminal.testhost   # 可选
# AndroidTerminal Dev（com.androidterminal.app）保留
```

## 7. 已知限制（本轮记录）

- **多会话并发**：ADB Engine 单连接一次只允许一个活跃 reader；第二个并发 interactive open 失败并可能崩溃（BoringSSL SIGABRT）。多会话 ADB 验证被阻塞，需先解决 ADB Engine 并发读。
- persistent read 无超时：reader 线程在 close 时 join 超时，残留线程会占用连接。
- pairing / discovery / recovery / Human adb CLI = OUT_OF_SCOPE（本轮未实现）。