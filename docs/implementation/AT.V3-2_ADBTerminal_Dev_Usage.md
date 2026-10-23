# AT.V3-2 �?ADBTerminal Dev Usage（headless ADB Backend 验证使用说明�?

> 供后�?OpenCode 复用：如何在真实设备上验�?AT Engine �?ADB Backend �?ADB Engine �?adbd 链路�?
>
> 本轮采用 **headless instrumentation**（无 UI APK）。后续如需 UI �?ADB Terminal，可基于本说�?+ AT.DEVAPP-0.2 SessionPage 复用�?

---

## 0. 前置

```powershell
$adb = "C:\android\platform-tools\adb.exe"
& $adb devices -l     # 预期：APLMUT4312005935  device

# 启用无线调试（若关闭�?
& $adb shell settings put global adb_wifi_enabled 1

# 确认 adbd 无线 connect 端口（uid 2000 监听 *:port�?
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
# 注入 PC 已信任的 adbkey（设备已信任 developer@HOST�?
& $adb push "$env:USERPROFILE\.android\adbkey" /data/local/tmp/th_adbkey
& $adb push "$env:USERPROFILE\.android\adbkey.pub" /data/local/tmp/th_adbkey.pub
& $adb shell "run-as com.androidterminal.testhost sh -c 'mkdir -p files; cp /data/local/tmp/th_adbkey files/adbkey; cp /data/local/tmp/th_adbkey.pub files/adbkey.pub'"
```

## 3. 运行验证（headless�?

```powershell
# 单个用例
$port = "38793"   # �?adbd 实际无线端口为准
$key = "/data/user/0/com.androidterminal.testhost/files"

# Gate A one-shot
& $adb shell am instrument -w -e class "com.androidterminal.testhost.AdbBackendHeadlessTest#a2_principal" -e port $port -e keyDir $key com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
# Gate B persistent
& $adb shell am instrument -w -e class "com.androidterminal.testhost.AdbBackendHeadlessTest#b7_ttyAndResize" -e port $port -e keyDir $key com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
```

用例列表（Gate A）：a1_echo / a2_principal / a3_pwd / a4_stderrNonZero / a5_connectionReuse
（Gate B）：b1_openActive / b2_writeOutput / b3_cwdContinuity / b4_envContinuity / b5_stdin / b6_ctrlC / b7_ttyAndResize / b8_exitCloseReopen
（Reader cleanup，AT.V3-2.1 新增）：b9_readerExitAfterClose / b10_blockedReadWakesOnClose
（Gate C，AT.V3-2.1 新增）：c1_threeConcurrentSessionsActive / c2_cwdEnvIsolation / c3_stdinIsolation / c4_ctrlCIsolation / c5_resizeIsolation / c6_closeIsolation / c7_reopenWithOtherStreamsActive / c8_naturalExitIsolation
（Stability，AT.V3-2.1 新增）：s1_sequentialLifecycle / s2_concurrentLifecycle

```powershell
# 整类运行（AT.V3-2.1 起安全：single RX owner + per-stream queue，实�?25 tests 同进�?PASS�?
& $adb shell am instrument -w -e class "com.androidterminal.testhost.AdbBackendHeadlessTest" -e port $port -e keyDir $key com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
```

> **AT.V3-2.1 更新**：此前「不要整类运行」（reader 残留导致后续崩溃）的限制已修复并真机证伪�?
> 整类运行现在会复用同一条物理连接（1 connect / 1 TLS / 1 RX owner），当前 25 个用例全 PASS�?
> 单用例运行（`#caseName`）仍可用于定位�?

## 4. 查看 transport / reuse / multiplexing 证据

```powershell
& $adb logcat -d -s AndroidTerminalADB | Select-String "CONNECT_START|TLS_START|C13|HUB0|STREAM_ACTIVE"
# 期望（Gate C）：1x CONNECT_START / 1x TLS_START / 1x [HUB01] RX_OWNER_START /
#                  3x [HUB03] STREAM_ACTIVE（local=1/2/3，remote 各不相同�?
& $adb logcat -d -s ATV31   # b9/b10 reader cleanup 计时（close() returned in 1ms 等）
```

## 5. 结果判断

- `OK (N test)` = PASS；`FAILURES!!!` + AssertionError = 失败；`Process crashed` = 崩溃（应已不复现）�?
- a2 应输�?`uid=2000(shell)`（ADB shell principal）�?
- Gate C 期望：c1 �?3 �?session 同时 ACTIVE，且 logcat �?`RX_OWNER_START` �?1 次�?

## 6. 清理

```powershell
& $adb shell "rm -f /data/local/tmp/th_adbkey /data/local/tmp/th_adbkey.pub"
& $adb uninstall com.androidterminal.testhost   # 可�?
# AndroidTerminal Dev（com.androidterminal.app）保�?
```

## 7. 已知限制（AT.V3-2.1 后更新）

- ~~多会话并发：第二个并�?interactive open 失败并可能崩溃~~ �?**已修�?*（AdbStreamHub
  �?RX owner；c1–c8 真机 PASS，AT.V3-2 Gate C = CLOSED）�?
- ~~persistent read 无超时，close �?reader 残留~~ �?**已修�?*（per-stream queue condvar�?
  close 1ms 内唤醒；b9/b10 PASS）�?
- 遗留（非阻塞）：transport 失联�?`AdbEngine.connectionState()` 可能仍投�?Online 直到显式
  disconnect（`AdbConnection` 状态机未改）；自动重连/recovery 未做�?
- pairing / discovery / recovery / Human adb CLI = OUT_OF_SCOPE（未真机）�