# AT.DEVAPP-0.2 — Dynamic Session Pages

> 本轮任务：将 Dev APK 从"单 Human Session + 特殊 Test Observation"整理为可动态创建 / 切换 / 关闭任意数量普通 Terminal Session 的分页 UI；Debug 自动化直接复用普通分页。不修改 AT Engine / Backend / Contract。
>
> 最终判定：**AT.DEVAPP-0.2 = COMPLETE**
>
> **DYNAMIC_SESSION_CREATE/CLOSE/SWITCHING = PASS**
> **SESSION_PAGES_EQUIVALENT = YES（Human 与 Debug 同一 SessionPage）**
> **SPECIAL_DEBUG_PAGE = NO（旧 Test Observation UI 已退役）**
> **MULTI-SESSION ISOLATION = PASS（9/9）**

---

## 1. Motivation

- 为后续 Local / ADB Backend 调试提供方便。
- 统一 Human Session 与 Debug Test Session 的界面逻辑。
- 支持动态创建多个独立 Terminal Session。
- 简化 Dev APK 控件布局。
- 不修改 AT Engine / Backend / Contract（纯 App/UI 层）。

## 2. App/UI Boundary

- 本轮逻辑全部位于 `:app`。
- 新增 `SessionPage`、`SessionPageController`（App/UI 层概念）。
- **不**将 Tab / Page / ActivePage / SessionPageManager / UI Session Index 放入 AT Engine。
- AT Engine 仍只提供 `ATTerminalSession`；App 决定创建几个、显示哪个、如何分页、何时关闭。

## 3. SessionPage Model

```kotlin
class SessionPage(
    val pageId: Int,
    var terminal: AppTerminal,   // -> ATTerminalSession -> ATEngine -> Backend
) {
    val outputBuffer: StringBuilder  // 本页独立输出缓存
    var rows / cols
    val state: ATSessionState
    fun close() = terminal.close()
}
```

- 每页对应一个**真正独立**的 AppTerminal / ATTerminalSession / backend。
- 字段按最小实现，无多余 DTO。

## 4. Session Lifecycle

- `SessionPageController.add()` 创建新页 + 设为 active；`openActiveSession()` open PTY。
- `closeActive()`：close 当前页 Session（backend cleanup）+ 移除页。
- `closeAll()`：Activity onDestroy 关闭所有 Session。
- **切换页只改 active 索引，不 close / recreate / reset 任何 Session**（PTY / cwd / env / 输出保持）。

## 5. Active Session Semantics

- 所有终端操作（Send / Ctrl-C / Resize / Clear / Close）作用于 **active page**。
- MainActivity 不再假定"只有一个 terminal"；通过 `pages.activePage` 访问。

## 6. Output Buffering

- 每页自有 `outputBuffer`。
- 非 active 页收到输出：追加到自己的 buffer，不显示；切回后可见。
- 不把所有 Session 输出塞进一个全局 buffer。
- 未实现 Emulator / ScreenState / ANSI parser / scrollback。

## 7. Resize Dialog

- 主界面移除 rows/cols 输入框，仅保留 `[Resize]` 按钮 → AlertDialog。
- 默认值 = 当前 Session 的 rows/cols；Apply → `activeSession.resize(rows, cols)`。
- Resize 只影响当前页。

## 8. Debug Automation Reuse

- 删除特殊 Test Observation UI（`tvTestOutput` / `btnTestObserve` / `attachTestObserver` / `TestSessionObserver`）。
- 自动化测试改用普通分页：
  ```text
  instrumentation → MainActivity.createPage() → openActiveSession()
                   → activeSession().write(bytes)
                   → 普通终端页面显示 PTY 输出
  ```
- Debug-only App API（`createPage` / `openActiveSession` / `activeSession`）仅测试用；无 exported receiver / TCP / Binder / attach 服务。

## 9. Removal of Test Observation Special UI

- 先完成新分页自动化验证（DynamicSessionPagesTest 9/9 PASS），再退役旧设施。
- 已删除：`TestSessionObserver.kt`、`AppDebugSessionVisibilityTest.kt`、测试观察布局/按钮。
- `AppDebugInputTest` 保留（不依赖 UI 按钮，走 AppTerminal）。

## 10. Multi-Session Isolation Evidence（真机 9/9 PASS）

| Case | 验证 |
|---|---|
| ds1_dynamicCreateThreeSessions | 动态创建 3 个独立 Session |
| ds2_pageSwitchingPreservesCwdEnv | S1 cwd/env（AT_PAGE=ONE + cd filesDir）切换后保持 |
| ds3_interactiveStdinIsolation | S3 `cat` 前台 stdin；S1 输出不混入 |
| ds4_ctrlCIsolation | 对 S3 Ctrl-C 只中断 S3；S1 仍 ACTIVE |
| ds5_resizeIsolation | S1 resize 40x120；S2 仍 24x80 |
| ds6_closeIsolation | 关闭 S3；S1/S2 仍 ACTIVE，页数减少 |
| ds7_inactiveOutputBuffering | S1 非 active 输出缓存，切回可见 |
| ds8_debugUsesNormalPage | 自动化用普通页发 `AT_UI_DEBUG_OK` 输出可见 |
| ds9_activityCleanupAll | closeAll 无 crash |

截图：`docs/evidence/at_devapp_02/*.png`（9 张，真机）。

## 11. Regression

| 测试 | 结果 |
|---|---|
| AppDebugInputTest | 7/7 PASS |
| TestHostHeadlessInputTest | 1/1 PASS |
| LocalPersistentSliceTest | 11/11 PASS |
| at-engine unit tests | 6/6 PASS |
| Dev APK 安装/启动 | PASS（新分页 UI 正常） |

## 12. Known Limitations

- Raw terminal（无 Emulator）：`vim`/`top` 全屏行为不正确（预期）。
- rotation retention / Service 持久化 / Activity 重建恢复 = DEFERRED。
- Page 内不显示 pageId 全局标识（仅页标签）；未来可加。

## 13. Final Verdict

```text
AT.DEVAPP-0.2 = COMPLETE

DYNAMIC_SESSION_CREATE = PASS
DYNAMIC_SESSION_CLOSE = PASS
PAGE_SWITCHING = PASS

SESSION_COUNT_FIXED = NO
SESSION_PAGES_EQUIVALENT = YES

ACTIVE_SESSION_SEND = PASS
ACTIVE_SESSION_CTRL_C = PASS
ACTIVE_SESSION_RESIZE = PASS
RESIZE_DIALOG = PASS

CWD_ENV_ISOLATION = PASS
STDIN_ISOLATION = PASS
CTRL_C_ISOLATION = PASS
RESIZE_ISOLATION = PASS
CLOSE_ISOLATION = PASS

INACTIVE_OUTPUT_BUFFERING = PASS
PAGE_STATE_PRESERVED = PASS

DEBUG_USES_NORMAL_PAGE = YES
SPECIAL_DEBUG_PAGE = NO
OLD_TEST_OBSERVATION_UI = REMOVED

ACTIVITY_CLEANUP_ALL_SESSIONS = PASS

HUMAN_UI_REGRESSION = PASS
APP_DEBUG_INPUT_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
LOCAL_PERSISTENT_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6

AT_ENGINE_MODIFIED = NO
LOCAL_BACKEND_MODIFIED = NO
ADB_ENGINE_MODIFIED = NO
CONTRACT_CHANGED = NO

APK_PRODUCT_POSITION = HUMAN_TERMINAL

REAL_DEVICE_VALIDATION = PASS
SCREENSHOT_EVIDENCE = PASS（9 张）

DEV_APK_LEFT_INSTALLED = YES
GIT_COMMIT = （见 commit）
GIT_WORKTREE = CLEAN

NEXT = AT.V3-2.0
```

**结论**：AT.DEVAPP-0.2 完成。Dev APK 现在可动态创建/切换/关闭任意数量独立 Terminal Session（SessionPage + SessionPageController）；Human 与 Debug 自动化共用同一普通分页（无特殊 Debug UI）；多 Session 隔离（cwd/env/stdin/Ctrl-C/resize/close/输出缓存）真机 9/9 PASS；旧 Test Observation 特殊 UI 已退役。AT Engine / Backend / Contract 未改。

本轮未开始 `AT.V3-2.0` / `AT.PERSIST-1` / `AT.EMULATOR-0` / `AT.CONTRACT-1` / `AG.TERMINAL-*`。任务结束，STOP。