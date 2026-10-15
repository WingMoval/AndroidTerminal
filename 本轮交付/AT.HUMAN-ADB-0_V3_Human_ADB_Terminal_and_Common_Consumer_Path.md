# AT.HUMAN-ADB-0 — V3 Human ADB Terminal + Common AT Consumer Path

> **Scope Recovery & Closure** — 纠正前半程在 HONOR pairing UX 上的 scope drift，
> 回到本轮真正目标，完成 Human ADB Preview + Common Consumer Path 的真实闭环。
> `STOP EXPANDING. CLOSE THE LOOP.`

## 1. Objective

完成真正可安装、可人工操作的 AndroidTerminal V3 ADB Preview APK：

```
Human
→ SessionPage → AppTerminal → ATEngine → AdbPersistentBackend → ADB Engine → adbd
→ remote PTY → uid=2000(shell)
```

同时证明 Human UI 和 Debug/Test 是**同一个 AT Engine consumer path**（不是两套 execution）。

## 2. Scope Recovery

前半程在 HONOR MagicOS 上尝试了 5 种 pairing UX（overlay / freeform / split-screen / Magic Window / Accessibility 自动读码），全部不可行：

| 方案 | 结果 | 证据 |
|---|---|---|
| overlay 悬浮窗 | ❌ | `mForceHideNonSystemOverlayWindow=true`，设置页前台强制隐藏 |
| AOSP freeform | ❌ | HONOR `am` Binder 异常 |
| 分屏程序化 | ❌ | 同上 |
| HONOR Magic Window | ❌ | 系统白名单 `/system/magic` 固化，普通 app 无权限 |
| Accessibility 自动读码 | ❌ | HONOR 无障碍列表发现不了服务 |

**纠正**：配对的**执行**是 `AdbEngine.pair(host, port, code, keyDir)` 主动连设备 pairing 服务；
配对码由用户从系统「使用配对码配对设备」弹窗**读出**，在 app 的**配对码输入窗口**（PairActivity，
普通 Activity，非悬浮窗）输入，程序拿到码后调 `AdbEngine.pair`。这**不是** First-run product
onboarding（那是 AT.FIRSTRUN-0），是本轮的一次性 developer/user provisioning 输入通道。

## 3. Baseline

```
BASELINE_V3 = ce48b7d（clean）
BASELINE_V2 = 726a441（43 会前 dirty，保持不动）
V2_WRITES = 0
```

外部任务 AT.LOCAL-TCP-0 对 `:adb-engine` 的改动已 stash 排除（`stash@{0}`），不 pop、不改、不入本轮 commit。

## 4. Pairing UX Scope Drift（已纠正）

- 移除：PairActivity（悬浮窗版）/ AutoPairAccessibilityService / freeform 代码 / manifest `SYSTEM_ALERT_WINDOW`
- 恢复：PairActivity（**普通 Activity** 配对码输入窗口）+ MainActivity「配对」按钮
- app 前台**不抢走**打开设置页（移除 `openWdSettings` 按钮 + 方法）—— Wi-Fi 门控是设备自身机制，
  用户自己开无线调试 + 自己配对，app 只反映 AdbRuntime 状态 + 提供 Retry（connectAuto）

## 5. Removed Experimental Paths

```
PAIRING_PRODUCT_UI = ABSENT（无 First-run / Settings 导航 / Dashboard）
ACCESSIBILITY_PAIRING_SERVICE = ABSENT
OVERLAY_PAIRING_UI = ABSENT
PAIRING_MAGIC_WINDOW_CODE = ABSENT
PAIRING_FREEFORM_CODE = ABSENT
```

## 6. Current V3 Architecture

```
                        Consumers
             Human                      Debug/Test
               |                            |
       SessionPage/AppTerminal          Test Adapter
               |                            |
               +-------------+--------------+
                             |
                          ATEngine
                             |
                      Backend Contract
                      /            \
                     /              \
          Local Backend          ADB Backend
               |                      |
           Local PTY              ADB Engine
               |                      |
       /system/bin/sh               adbd
                                        |
                                 remote PTY/program
                                        |
                                uid=2000(shell)
```

- `Local /system/bin/sh` 只属于 Local provider，**不属于** Human ADB path / Debug ADB path / ATEngine 本体
- ADB lifecycle = `AdbRuntime` owns
- Terminal execution = `ATEngine` owns
- Human presentation = `SessionPage` / `AppTerminal` owns
- Debug orchestration = test harness owns

## 7. AppRuntime Composition

```kotlin
object AppRuntime {
    fun initialize(context: Context)          // wire AdbRuntime + fix app keyDir
    fun adbIdentityDir(): String              // app's own private identity dir
    fun adbState(): AdbRuntimeState           // single AdbRuntime owner
    fun connectAuto(): AdbRuntimeDiagnostic   // drive connectAuto
    fun localEngine(): ATEngine               // Local backend
    fun adbEngine(): ATEngine                 // ADB backend (reuses AdbRuntime conn)
}
```

- App 自有 persistent ADB identity 在 `filesDir/adb_identity`（loadOrCreate，非 PC adbkey 复制/打包）
- 单 `AdbRuntime` owner per process
- `SessionBackendKind{LOCAL,ADB}` 仅 App/UI 层（provider identity），不入 `:at-engine`

## 8. ADB Runtime Ownership

`AdbRuntime` 继续负责 readiness / discovery / endpoint lifecycle / connectAuto / recovery / runtime state。
禁止 `ATEngine.connectAuto()` / `ATEngine.discover()` / `ATEngine.pair()`。
`SessionPage` 不直接操作 mDNS / endpoint。

## 9. AT Engine Boundary

```
AT_ENGINE_MODIFIED = NO
AT_ENGINE_PUBLIC_CONTRACT_CHANGED = NO
```

当前需求由 `ATEngine` / `ATPersistentBackend` / `ATTerminalSession` 表达，无需新增
`ATEngine.connectAdb()` / `ATEngine.pair()` / `ATEngine.debugExecute()`。

## 10. Shell Independence Boundary

AT.TERM-0 已 CLOSED：`ATTerminalSession` = backend-neutral / shell-independent / byte-stream session。
`/system/bin/sh` 不是 ATEngine 本体，只是 Local backend 的 launch target。
本轮 Human ADB 集成**未重新打开**该问题（Shellless 回归 15/15 PASS 再次确认）。

## 11. Human Consumer Path

```
Human → SessionPage → AppTerminal(kind=ADB) → ATEngine → AdbPersistentBackend → ADB Engine → adbd
```

- Local / ADB 共用**同一个** SessionPage（`backendKind` 固定 at creation，切 page 不重建/reconnect/re-TLS）
- 创建 ADB page：`AdbRuntime.state != READY` → `connectAuto` → READY → `ATEngine.openSession`
- SessionPage 不知 TLS / CNXN / WRTE / OKAY / stream ID / dynamic port / mDNS / endpoint

## 12. Debug Consumer Path

```
Debug/Test → thin test adapter → AppRuntime → ATEngine → AdbPersistentBackend → adbd
```

- 不依赖 MainActivity / SessionPage / EditText / Button / IME / uiautomator / UI coordinates
- 结果来自 ATTerminalSession output/events（非 logcat scraping）
- `ProgrammaticAdbConsumerTest`（7/7）+ `CommonConsumerPathTest`（3/3）验证

## 13. Local Provider Role

Local Backend 本轮只用于 regression / Local+ADB coexistence / provider isolation verification。
**不能**成为 ADB Preview 的前置。`LOCAL_BACKEND_REQUIRED_BY_ADB_PREVIEW = NO`。

## 14. ADB Provider Role

ADB Backend → ADB Engine → adbd → remote PTY → remote shell（uid=2000）。
Remote shell 是 ADB provider 后面的 remote program，允许。
`LOCAL_SYSTEM_SH_REQUIRED_BY_HUMAN_ADB_PATH = NO` / `LOCAL_SYSTEM_SH_REQUIRED_BY_DEBUG_ADB_PATH = NO`。

## 15. App Identity

```
APP_ADB_IDENTITY_PATH = files/adb_identity/（app private storage）
APP_ADB_IDENTITY_SELF_GENERATED = YES（loadOrCreate，非 PC adbkey 复制/打包/assets）
PC_ADBKEY_COPIED = NO
```

## 16. Developer Provisioning

唯一保留的 provisioning 路径：`AppAdbProvisionTest`（debug/androidTest harness）。
职责：用 app 自己的 private filesDir → loadOrCreate identity → discoverPairing →
接收临时 pairing code（`-e code`）→ `AdbEngine.pair` → connectAuto → verify uid=2000。
code 只作为 instrumentation 临时参数，**不写** docs/evidence/git/log。

实际本轮配对通过 **PairActivity**（用户手动输入码）完成 —— 同一条 `AdbEngine.pair` 通路。

## 17. Human UI

最小 UI：
- 顶部：ADB 状态（DISCONNECTED/CONNECTING/READY/CONNECT_FAILED/USER_ACTION_REQUIRED/RECOVERING/RECOVERY_FAILED）+ 引导文字
- 控制：New（ADB/Local 选择）/ Retry ADB / 配对 / Session tabs / input / Send / Ctrl-C / Resize / Clear / Close
- **无**：Pairing product page / Accessibility setup / Dashboard / Navigation Drawer / Settings Center

## 18. Persistent ADB Session

`Send` → `"id\n"` → `ATTerminalSession.write(bytes)`（非 `ATEngine.execute` / `AdbEngine.executeShell`）。
验证 persistent（非 one-shot console）：cwd / env 跨命令保持。

## 19. Multi-session

3 个独立 ATTerminalSession / 3 个 logical ADB streams / 共享 1 physical ADB connection / 1 TLS / 1 RX owner。
cwd / env / stdin / output / Ctrl-C / resize / close 隔离 + inactive output buffering。

## 20. Local + ADB Coexistence

Local 1（app UID）+ ADB 1（uid=2000）同时 ACTIVE，关一个另一个继续。
`LOCAL_ADB_COEXISTENCE = PASS`，`LOCAL_BACKEND_REQUIRED_BY_ADB_PREVIEW = NO`。

## 21. Human / Debug Parity

```
Human: ATEngine → ADB Backend → id → uid=2000(shell)
Debug: ATEngine → ADB Backend → id → uid=2000(shell)
HUMAN_DEBUG_CONSUMER_PARITY = PASS
```

Common 的是 execution semantics，不是 frontend / session instance / UI。

## 22. Process Restart

```
launch → ADB READY → ADB page works → force-stop → relaunch → same identity
→ connectAuto → READY → new ADB page → id → uid=2000(shell)
ADB_PREVIEW_PROCESS_RESTART = PASS
REPAIR_AFTER_RESTART_REQUIRED = NO
```

## 23. Recovery

本轮只验证已有 runtime 状态呈现。未安全制造 transport loss（避免扩大任务）。
`RECOVERY_STATE_PRESENTATION = NOT_TESTED`。

## 24. Raw Terminal Limits

`RAW_TERMINAL_PREVIEW = YES`，`TERMINAL_EMULATOR = NOT_STARTED`，`SCREEN_STATE = NOT_STARTED`。
vim / top / nano / full-screen curses/TUI 显示不保证。

## 25. APK

```
APK_PATH = D:\codebank\AndCode\AndroidTerminal-V3\app\build\outputs\apk\debug\app-debug.apk
APK_SIZE = 7817845
APK_SHA256 = 17858358592E5962A9B16B32F41D0B884B522B78A431D14F4FF4439CED3C8B43
APP_PACKAGE = com.androidterminal.app
APK_INSTALLED = YES
```

不 commit APK，不复制到本轮交付。

## 26. Real-device Validation

设备 `APLMUT4312005935`（HONOR ALI-AN00 / Android 15 SDK 35 / arm64），USB。
WD=1（用户手动开）。配对通过 PairActivity（用户手动输码）完成，identity trusted。

## 27. Regression

```
Local 11/11 · AppDebugInput 7/7 · AT Engine unit 6/6 · Shellless 15/15
Pairing deterministic 54/54
CommonConsumerPath 3/3（C1-C11）
AdbHumanPreview 6/6（h1-h6）
ProgrammaticAdbConsumer 7/7（principal/cwd/env/Ctrl-C/resize/multi/coexist）
test-host ADB Headless/Parity/Runtime 25/34/12 —— 设备本地回环 adbd key 认证受限
  （fresh 设备无 root，/data/misc/adb 不可写；独立 ADB provider regression，
  非本轮 Human ADB 集成问题；Human ADB 走远程 WD 配对已验证）
```

## 28. Security

- 不保存 private ADB key / pairing code 到 docs/evidence/git/log
- 允许记录：public key hash（`adbkey.pub` md5 = `506a6036fdc3e82d09d946d19ba1c56c`）/ non-secret key dir description

## 29. Cleanup

```
TEST_APKS_REMOVED = 收尾时执行
MAIN_APP_LEFT_INSTALLED = YES
APP_PREVIEW_IDENTITY_PERSISTED = YES
WD_FINAL_STATE = 1
```

## 30. Final Verdict

见 Final Verdict 块（本轮最后输出）。

## 31. Next

`NEXT = AT.CLI-0`（Local PTY → /system/bin/sh → adb/at → CLI facade/bridge → embedded ADB Engine/ATEngine）。
之后 `AT.FIRSTRUN-0`（fresh install → identity → WD → pairing guidance → pairing → READY）。
