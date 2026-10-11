# AT.CONTRACT-0.5 — Terminal Input Channel Clarification & Debug Automation

> 本轮任务：在不改变 Canonical Architecture / 不重新设计 AT Engine / 不改变 APK 产品定位的前提下，整理现有 Persistent Terminal 输入通路，并为 V3 Dev APK 建立可重复使用的程序化调试输入方式。
>
> 最终判定：**AT.CONTRACT-0.5 = COMPLETE**
>
> **INPUT_MODEL = BYTE_STREAM / INPUT_CONTRACT = CONFIRMED**
> **CONTRACT_STATUS = REVISED_DRAFT / CONTRACT_FREEZE = NO**
> **CONTRACT_CHANGE = NONE（无需修改 AT Engine Contract）**

---

## 1. Background

- AT.PERSIST-0：`AT Engine → Local Backend → Local PTY` 真机 11/11 PASS。
- AT.DEVAPP-0：V3 Dev APK 安装/启动，Human UI 经 `ATEngine → LocalPersistentBackend` 手动输入。
- 缺口：OpenCode 调试时只能依赖人工点击或 `adb input text`（已知受 IME 干扰）。本轮建立**可靠、可重复、非 IME** 的程序化调试输入通路。

## 2. Existing Verified Capabilities

| 能力 | 判定 | 来源 |
|---|---|---|
| AT Engine 无头调用（headless） | ALREADY VERIFIED | AT.PERSIST-0 / instrumentation |
| Human UI 输入 | VERIFIED | AT.DEVAPP-0 |
| session.write(bytes) | VERIFIED | AT.PERSIST-0 P1–P10 |
| persistent session / Ctrl-C / resize / cleanup | VERIFIED | AT.PERSIST-0 |

**AT_ENGINE_INDEPENDENT_CALL = ALREADY VERIFIED（本轮不重新实现，仅回归）。**

## 3. Current Human Input Path

```text
EditText + Send/Ctrl-C buttons
        ↓
MainActivity → AppTerminal（composition root）
        ↓
ATEngine → LocalPersistentBackend → Local PTY → /system/bin/sh
```

- Human UI 只经 `ATTerminalSession` 语义；不触碰 LocalPtyNative/JNI/fd/pid。
- Send = UTF-8 bytes + 显式 `\n`（byte-stream 语义）；Ctrl-C = `0x03`。
- **T1 Human 回归 PASS**（refactor 后 App 仍启动、进程存活、无 crash）。

## 4. Current Headless Input Path

- `:at-backend-local` androidTest（`LocalPersistentSliceTest`）：独立测试进程，程序化建 session + write + 捕获 → 11/11 PASS。
- `:test-host`（新增 `TestHostHeadlessInputTest`）：独立 APK consumer，无 UI，程序化建 session + write + 捕获 → **T2 PASS**（`HEADLESS_OK`）。

**两条 headless 路径都复用同一 `AT Engine → LocalPersistentBackend → Local PTY` 调用链。**

## 5. Unified Byte Input Semantics（确认）

Persistent Terminal 基础输入语义 = **Byte Stream**，非 Command String：

```kotlin
session.write(bytes)
```

- 能表达：普通文本、换行（显式 `\n`）、Ctrl-C（`0x03`）、Tab、Escape、方向键控制序列、非文本原始字节。
- **AT Engine 不自动加换行**：`write("pwd".toByteArray())` ≠ `write("pwd\n".toByteArray())`。
- `write(byteArrayOf(0x03))` = Ctrl-C 控制字节，**不是**字符串命令。

**证据**：T4（partial write 后显式 newline 才执行）、T5（stdin 原始字节）、T6（0x03 Ctrl-C）。

## 6. Debug Automation Entry

- **首选复用现有 instrumentation**（无新 Service / 无网络端口 / 无 exported receiver）。
- 为 `:app` 增加 debug-only instrumentation：`AppDebugInputTest`（7 cases）驱动 **App consumer 路径**。
- `:app` 新增 `AppTerminal`（composition root，Human UI 与测试共用同一路径）。
- `:test-host` 补 headless 输入回归（T2）。

### 区分两类自动化

```text
HEADLESS_SESSION_AUTOMATION    : 独立测试进程创建受控 Session（TestHost / at-backend-local test）
DEV_APP_SESSION_AUTOMATION     : instrumentation 在 Dev APK 测试生命周期内创建受控 Session，
                                 与 Human UI 走同一 AppTerminal 路径
```

**本轮两者均实现并验证；不把前者 PASS 冒充后者 PASS。**

## 7. Human APK Product Boundary

- Dev APK = **供人类使用的 Android Terminal**，非 Agent/Headless/控制服务器。
- 自动化只用于开发调试/回归/真机验证；**不改变 APK 产品定位**。
- 未把正式 UI 改造成面向 Agent 的控制面板。

## 8. Session Ownership

- 每个输入者管理自己的 Session（Human 的 UI session；测试的受控 session）。
- **不实现**多 consumer 共享 / attach / handoff / 输入所有权争夺。
- 自动化测试操作的是**自己创建的受控测试 Session**，不接管用户正在使用的 Session。

## 9. Output Capture for Debugging

- 输出经 `ATSessionEvent.Data(bytes)` 捕获；状态经 `ATSessionState`。
- Headless/Test：`Capture` listener 断言输出 + 状态。
- 调试日志与真实终端输出区分（本轮未混入用户显示区）。

## 10. Security Boundary

- 新增入口全部 = **测试专用、受控、非生产公开接口**（instrumentation 进程内）。
- 未新增：exported command receiver / TCP 控制端口 / 公网接口 / 常驻自动执行服务 / 绕过 AT Engine 的 shell 接口。
- `AppTerminal` / `AppDebugInputTest` 仅在 debug/test 构建中存在；不成为 Release 公开能力。

## 11. Contract Changes

**CONTRACT_CHANGE = NONE。**

确认 `ATTerminalSession.write(bytes)` 已满足统一 Persistent 输入边界，无需修改 Contract。未重新设计，未新增 TerminalInputManager/Bus/Registry 等抽象。

## 12. Real-device Evidence（设备 APLMUT4312005935 / ALI-AN00 / Android 15）

| 项 | 结果 |
|---|---|
| T1 Human input regression | **PASS**（App 启动、进程存活、无 crash） |
| T2 Headless input（TestHost） | **PASS**（`HEADLESS_OK`，1 test） |
| T3 App debug automated input | **PASS**（`APP_DEBUG_OK`） |
| T4 Byte-stream semantics | **PASS**（partial write + explicit newline 才执行） |
| T5 Interactive stdin | **PASS**（cat 前台收 `INPUT_TEST`） |
| T6 Ctrl-C | **PASS**（sleep 中断、shell ACTIVE、后续命令可执行） |
| T7 Session continuity | **PASS**（env 跨命令保持） |
| T8 Cleanup | **PASS**（close 幂等；close 后 write=-1） |
| T9 Existing regression | **PASS**（at-engine unit + at-backend-local P1–P10 11/11） |

## 13. Remaining Limitations

- Raw terminal（无 Emulator）：`vim`/`top` 全屏程序行为不正确（预期，属 AT.EMULATOR-0）。
- 调试入口为**预设测试用例**；当前不提供任意命令动态参数注入（instrumentation 固定用例集，含标准输入序列）。参数化需在测试类内加用例。
- rotation / process retention / background service = DEFERRED。

## 14. Final Verdict

```text
AT.CONTRACT-0.5 = COMPLETE

INPUT_MODEL                 = BYTE_STREAM
INPUT_CONTRACT              = CONFIRMED（ATTerminalSession.write(bytes) 足够）
CONTRACT_STATUS             = REVISED_DRAFT
CONTRACT_FREEZE             = NO

AT_ENGINE_HEADLESS          = PREVIOUSLY_VERIFIED
AT_ENGINE_REIMPLEMENTED     = NO

HUMAN_INPUT_PATH            = VERIFIED（T1）
HEADLESS_INPUT_PATH         = VERIFIED（T2 TestHost）
DEV_APP_AUTOMATED_INPUT     = VERIFIED（T3 AppDebugInputTest）
DEBUG_INPUT_ENTRY           = instrumentation（AppDebugInputTest / TestHostHeadlessInputTest）
DEBUG_INPUT_SCOPE           = 受控测试 Session（非用户 Session）
BYTE_STREAM_SEMANTICS       = VERIFIED（T4）
INTERACTIVE_STDIN           = VERIFIED（T5）
CTRL_C                      = VERIFIED（T6）
SESSION_CONTINUITY          = VERIFIED（T7）
OUTPUT_CAPTURE              = VERIFIED（listener + state 断言）
SESSION_CLEANUP             = VERIFIED（T8）

HUMAN_UI_REGRESSION         = PASS
LOCAL_REGRESSION            = 11/11 PASS
REAL_DEVICE_VALIDATION      = PASS
BUILD                       = PASS（clean assembleDebug + androidTest + unit）

APK_PRODUCT_POSITION        = HUMAN_TERMINAL
AG_INTEGRATION              = NOT_STARTED
SHARED_SESSION              = NOT_IMPLEMENTED

AT_ENGINE_BOUNDARY          = UNCHANGED
ADB_ENGINE_MODIFIED         = NO
V2_MODIFIED                 = NO

DEV_APK_LEFT_INSTALLED      = YES
GIT_COMMIT                  = （见 commit）
GIT_WORKTREE                = CLEAN

NEXT                        = AT.V3-2.0
```

### 明确回答

**A. OpenCode 能否不经人工键盘向 V3 TerminalSession 发送输入？** 能。通过 instrumentation（`AppDebugInputTest` / `TestHostHeadlessInputTest`）程序化 `session.write(bytes)`，不依赖人工键盘或 IME。

**B. 能否自动捕获输出和 Session 状态？** 能。经 `ATSessionEvent.Data` 捕获字节、`ATSessionState` 断言状态；测试用 `Capture` listener + `await` 断言。

**C. 自动化是独立 Headless Session 还是 Dev APK 受控测试 Session？** 两者都有：Headless（TestHost / at-backend-local test）独立；Dev App 自动化（AppDebugInputTest）在 instrumentation 内创建受控测试 Session，与 Human UI 走同一 `AppTerminal` 路径。**不接管用户正在使用的 Session。**

**D. 是否需修改 AT Engine Contract？** 不需要。`ATTerminalSession.write(bytes)` 已满足统一输入边界；`CONTRACT_CHANGE = NONE`。

**E. APK 是否保持 Human Terminal 定位？** 是。`APK_PRODUCT_POSITION = HUMAN_TERMINAL`；自动化仅 debug/test 用，未改产品定位。

**结论**：AT.CONTRACT-0.5 完成。Persistent 输入通路明确为 Byte Stream；Human UI、Headless、Dev App 自动化三条路径共用同一 `ATEngine → LocalPersistentBackend → Local PTY` 调用链；建立了可重复、非 IME 的 instrumentation 调试入口（T3 等 7 cases PASS，T2 headless PASS，T9 回归 11/11 PASS）。Contract 无需修改（保持 REVISED_DRAFT，未冻结）。V2 未改。

本轮未开始 `AT.V3-2.0` / `AT.PERSIST-1` / `AT.EMULATOR-0` / `AT.CONTRACT-1` / `AG.TERMINAL-*`。任务结束，STOP。