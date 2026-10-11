# AT.DEVAPP-0.1 — Test Session UI Visibility（自动化测试输出界面可视化）

> 本轮任务：在保持 APK Human Terminal 定位、保持 Headless 测试独立、保持 AT Engine/Backend 架构不变的前提下，让自动化测试所用的独立 TerminalSession 的输入、真实输出与状态在 AndroidTerminal APK 的真实 MainActivity 界面中实时显示。
>
> 最终判定：**AT.DEVAPP-0.1 = COMPLETE**
>
> **TEST_SESSION_INDEPENDENT = YES / HUMAN_SESSION_INDEPENDENT = YES**
> **REAL_PTY_OUTPUT_VISIBLE_IN_UI = PASS / UI_ASSERTION = PASS / REAL_DEVICE_SCREENSHOT = PASS**
> **CONTRACT_CHANGED = NO / CONTRACT_FREEZE = NO**

---

## 1. 原始问题

- 自动化测试（AppDebugInputTest / TestHostHeadlessInputTest）能程序化向独立 TerminalSession 发字节并捕获输出，但**输入/输出/状态不显示在 APK 界面**。
- OpenCode 调试时看不到测试 Session 的实时运行情况。
- 目标：同一界面能同时观察 Human Session 与独立 Test Session，但两者保持独立、不共享。

## 2. 两条 Session 通路的真实关系

```text
人工：MainActivity ── AppTerminal ── Human TerminalSession ── ATEngine ── LocalPersistentBackend ── Local PTY
测试：AppDebugSessionVisibilityTest ── AppTerminal ── Test TerminalSession ── ATEngine ── LocalPersistentBackend ── Local PTY
```

- 两条通路共用同一 consumer 代码路径（AppTerminal + ATEngine + LocalPersistentBackend）。
- **各自创建独立 Session 实例**；Human 与 Test 不共享、不接管。
- 本轮不变更这一事实。

## 3. instrumentation 实际进程模型（§4 审计）

- `com.androidterminal.app.test` instrumentation target = `com.androidterminal.app`（标准 Android instrumentation）。
- **测试与 MainActivity 同进程**运行 → 测试可直接持有真实 Activity 实例（`ActivityScenario.launch(MainActivity::class.java)` + `onActivity` 捕获引用），访问其 UI，无需 IPC。
- 审计结论：**无需引入 Binder / Socket / ContentProvider**。同一进程直接调用 Activity 的 debug-only 方法即可。

## 4. MainActivity 生命周期

- `ActivityScenario.launch` 创建真实 Activity；`onActivity` 捕获实例引用。
- 测试结束 `scenario.close()` 销毁 Activity（`onDestroy` → `AppTerminal.close()` → native cleanup）。
- 每用例重新 launch（独立 Activity 生命周期），不依赖跨用例状态。

## 5. Test Session 所有权

- 测试创建自己的 `AppTerminal`（Test Session），在测试生命周期内打开/关闭。
- MainActivity 的 `attachTestObserver(observer)` 仅把 observer 的输出接到 UI 测试观察区；**不持有、不控制 Test Session 生命周期**。
- Human Session（MainActivity 自己的 `AppTerminal`）不受影响。

## 6. 输出消费方式（§5 注意）

- Test Session 使用 **单一输出消费者**：`setListener` → `TestSessionObserver` → 同时做（a）UI 渲染（appendTestLine）与（b）测试日志记录（log）。
- **未**同时使用 `read()` + listener（遵守 AT.CONTRACT-0.5 C1 互斥规则）。
- 渲染与断言共用 observer 的 `log` / UI 文本，避免输出竞争。

## 7. Debug UI 最小实现

- MainActivity 新增 debug-only：
  - `tvTestOutput`（测试观察 TextView，默认 GONE）
  - `btnTestObserve`（Toggle Test Observation）
  - `attachTestObserver(observer)` / `detachTestObserver()` / `testObservationText()` / `appendTestLine(line)`
- `TestSessionObserver`（`:app`，debug adapter）：把 Test Session 事件渲染为带标签行：
  - `[TEST INPUT] ...`（测试发送的字节）
  - `[PTY OUTPUT] ...`（真实 Local PTY 返回字节）
  - `[STATE] ...`（Session 状态）
- 不新增 Session Registry / Bus / Multi-client Manager / Remote Terminal Service。

## 8. 输入与输出的关联方式

- 测试 `observer.onTestInput(bytes)` 记录"发送了什么"。
- 真实 PTY 输出经 `ATSessionEvent.Data` → `observer.onEvent` → `[PTY OUTPUT]`。
- 输入记录与 PTY 输出**明确区分**（不同标签），禁止把人工输入日志伪装成 PTY 输出。
- 保留原始字节语义；展示用 UTF-8 解码。

## 9. 真机测试步骤（设备 APLMUT4312005935 / ALI-AN00 / Android 15）

1. `adb install -r app-debug.apk`（更新 Dev APK，含测试观察 UI）
2. `adb install -r -t app-debug-androidTest.apk`
3. `adb shell am instrument -w -e class com.androidterminal.app.AppDebugSessionVisibilityTest com.androidterminal.app.test/androidx.test.runner.AndroidJUnitRunner`

## 10. UI 断言结果（7/7 PASS）

| Case | 验证 | 结果 |
|---|---|---|
| ui1_basicCommandVisibleInUi | `echo AT_UI_DEBUG_OK` → UI 出现 `AT_UI_DEBUG_OK` + `[PTY OUTPUT]` | **PASS** |
| ui2_partialThenNewline | 分段写入（无自动换行）→ newline 后执行，UI 显示 PARTIAL | **PASS** |
| ui3_interactiveStdin | `cat` + `TEST_STDIN` → UI 显示真实回显 | **PASS** |
| ui4_ctrlCVisibleAndShellAlive | `sleep 30` + `0x03` → shell ACTIVE + `AFTER_UI_CTRL_C` 可见 | **PASS** |
| ui5_stateVisible | `[STATE] ACTIVE`（attach 后）→ close → `[STATE] CLOSED` | **PASS** |
| ui6_cleanupNoLeak | close 幂等；close 后 write=-1；detach observer | **PASS** |
| ui7_humanTerminalUnaffected | Human session（MainActivity 自己的 Session）仍可 Send，输出到 tvOutput | **PASS** |

## 11. 截图证据（真实设备截图，非合成）

保存到 `docs/evidence/at_devapp_01/`（拉取自设备 `/sdcard/Android/data/com.androidterminal.app/files/at_devapp_01/`）：

```text
docs/evidence/at_devapp_01/ui1_basic.png   127326 B
docs/evidence/at_devapp_01/ui2_partial.png 124401 B
docs/evidence/at_devapp_01/ui3_stdin.png   117351 B
docs/evidence/at_devapp_01/ui4_ctrlc.png   142045 B
docs/evidence/at_devapp_01/ui5_state.png   108319 B
docs/evidence/at_devapp_01/ui6_cleanup.png 127091 B
docs/evidence/at_devapp_01/ui7_human.png   182762 B
```

> 注：本运行环境模型不支持直接查看图像，但截图由 `InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()` 在真机截取，且 UI 文本断言已通过，截图作为可审计证据保留。

## 12. 回归结果（无退化）

| 测试 | 结果 |
|---|---|
| AppDebugInputTest（App 自动化输入） | **7/7 PASS** |
| TestHostHeadlessInputTest（headless） | **1/1 PASS** |
| LocalPersistentSliceTest（Local P1–P10） | **11/11 PASS** |
| at-engine unit tests | **6/6 PASS** |
| clean assembleDebug | **PASS** |

## 13. 已知限制

- Debug UI 为最小观察区域；正式 Human UI 未改动产品结构。
- Raw terminal（无 Emulator）：`vim`/`top` 全屏行为不正确（预期）。
- 测试观察区在 debug 构建中可用；Release 构建不暴露自动化执行入口。
- 模型环境不能直接查看截图图像（已以文件 + UI 断言作为证据）。

## 14. Final Verdict

```text
AT.DEVAPP-0.1 = COMPLETE

EXISTING_HUMAN_PATH = PASS
EXISTING_TEST_PATH = PASS

HUMAN_SESSION_INDEPENDENT = YES
TEST_SESSION_INDEPENDENT = YES

INSTRUMENTATION_PROCESS_VERIFIED = YES（测试与 Activity 同进程）
ACTIVITY_REAL_UI_USED = YES

TEST_INPUT_VISIBLE_IN_UI = PASS
REAL_PTY_OUTPUT_VISIBLE_IN_UI = PASS
SESSION_STATE_VISIBLE_IN_UI = PASS
UI_REALTIME_UPDATE = PASS
UI_ASSERTION = PASS
REAL_DEVICE_SCREENSHOT = PASS（7 张）

OUTPUT_CONSUMPTION_SAFE = PASS（单一消费者）
SESSION_CLEANUP = PASS
HUMAN_UI_REGRESSION = PASS

APP_DEBUG_INPUT_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
LOCAL_PERSISTENT_REGRESSION = 11/11
AT_ENGINE_TESTS = PASS

AT_ENGINE_MODIFIED = NO
ADB_ENGINE_MODIFIED = NO
CONTRACT_CHANGED = NO
CONTRACT_FREEZE = NO

DEBUG_ONLY_BOUNDARY = PASS
DEV_APK_LEFT_INSTALLED = YES

GIT_COMMIT = （见 commit）
GIT_WORKTREE = CLEAN

NEXT = AT.V3-2.0
```

### 明确回答

**A. 自动化测试是否仍使用自己的独立 TerminalSession？** 是。`AppDebugSessionVisibilityTest` 创建独立 `AppTerminal`（Test Session），与 Human Session 互不共享。

**B. 测试发出的输入是否能在 MainActivity 实际界面观察？** 是。`[TEST INPUT]` 标签实时显示在 `tvTestOutput`（ui1/ui3 断言 + 截图）。

**C. Local PTY 返回的真实输出是否能在 MainActivity 实时显示？** 是。`ATSessionEvent.Data` → observer → `[PTY OUTPUT]` → `appendTestLine` → UI（ui1/ui2/ui3 PASS + 截图）。

**D. 这些 UI 内容是否由真实 Session 事件驱动，而非测试伪造？** 是。所有 UI 行均来自 `setListener` 收到的真实 `ATSessionEvent`（Data/State）；测试仅记录自己发送的输入字节（`onTestInput`）。

**E. 是否引入新跨进程通信？** 否。instrumentation 与 Activity 同进程，直接调用 Activity 的 debug-only 方法；无 Binder/Socket/ContentProvider。

**F. 测试结束后如何查看真实界面证据？** 截图保存于 `docs/evidence/at_devapp_01/*.png`（真机 `uiAutomation.takeScreenshot`）；`AppDebugSessionVisibilityTest` 的 `ATDev01` logcat 有截图路径日志；测试报告可复跑获取。

**结论**：AT.DEVAPP-0.1 完成。自动化测试所用的独立 Test Session 的输入、真实 PTY 输出与状态现在能在真实 MainActivity 界面实时观察，且 Human Session 独立不受影响；7/7 UI 断言 + 7 张真机截图 + 全部回归 PASS。未修改 AT Engine / Backend / Contract（保持 REVISED_DRAFT 不冻结）。

本轮未开始 `AT.V3-2.0` / `AT.PERSIST-1` / `AT.EMULATOR-0` / `AT.CONTRACT-1` / `AG.TERMINAL-*`。任务结束，STOP。