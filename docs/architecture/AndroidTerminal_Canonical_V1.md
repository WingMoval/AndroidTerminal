# AndroidTerminal Canonical Development Guide V1

**文档状态：CANONICAL V1**

**适用主线：AndroidTerminal-V3**

**文档角色：当前 AndroidTerminal 开发 SSOT / 总控说明**

---

## 0. 摘要：我们现在要做什么

AndroidTerminal 前一阶段已经完成了大量关键能力验证：

- ADB 配对、身份、TLS、连接与命令执行；
- ADB one-shot shell；
- ADB Remote PTY；
- Local one-shot；
- Local PTY；
- persistent shell、stdin、Ctrl-C、resize、cwd/env 持续状态；
- AT Runtime / Core 的现有执行链路；
- Terminal Execution 架构研究；
- Terminal Emulator、Screen State、Session Runtime、Human / Agent 消费模式等成熟方案调研。

这些工作已经证明：

> **我们现在缺的不是继续证明“Android 上能不能做 Terminal”，而是把已经验证过的能力按照最终职责重新组织成一个干净、可复用、可长期演进的 Terminal architecture。**

因此下一阶段不再继续围绕旧工程不断修补。

我们将：

```text
冻结 AndroidTerminal-V2
        ↓
把它保留为：
已验证实现 + Evidence Repository + Migration Reference
        ↓
建立新的 AndroidTerminal-V3
        ↓
按照新的 Canonical Architecture
重新组织已经验证过的能力
```

V3 不是从零重写。

它的核心原则是：

> **Verified migration，而不是 rewrite。**

即：

```text
V2 中已验证的实现
        ↓
识别真实职责
        ↓
迁入 V3 正确架构位置
        ↓
行为保持一致
        ↓
Regression / parity 验证
        ↓
再继续建设新能力
```

---

## 1. 我们准备构建什么

最终希望得到的不是一个只能运行 `/system/bin/sh` 的 Android App，也不是一个披着 Terminal 名字的 ADB Client。

我们要构建的是：

> **一套独立、可复用的 Android Terminal Execution Capability —— AT Engine。**

它能够被不同消费者使用：

```text
AndroidTerminal Human UI
AndroidAgent TerminalTool
Test Host
未来其他 Terminal 产品
```

并能够通过不同执行后端运行：

```text
Local Android Process / PTY
ADB
未来 SSH
未来其他 Terminal-compatible Provider
```

整体关系：

```text
Consumer / Product
        │
        ▼
AT Engine
        │
        ├── One-shot Execution
        │
        └── Persistent Terminal
                 │
                 ▼
          Backend Adapter
        ┌────────┼────────┐
        ▼        ▼        ▼
      Local     ADB      Future...
        │        │
        ▼        ▼
 Local Process  ADB Engine
 / PTY              │
                    ▼
                   adbd
```

其中：

- `AT Engine` 负责 Terminal / Command Execution 语义；
- `ADB Engine` 负责 ADB 本身；
- `Backend` 负责两者之间的适配；
- Android UI 负责人的显示和输入；
- AndroidAgent 自己决定何时使用 AT Engine。

---

## 2. 为什么现在建立 AndroidTerminal-V3

### 2.1 V2 没有失败

V3 的出现不是因为 V2 技术路线失败。

恰恰相反：

> V2 已经完成了大量最困难的底层验证。

问题在于 AndroidTerminal 的定义在开发过程中发生了演化。

早期项目实际上更接近：

```text
AndroidTerminal
≈ Android 内部 ADB Host
≈ Wireless ADB execution
```

因此逐渐形成：

```text
AT Core
AT Runtime
TerminalRuntime
AtRuntime
```

但这些名字下面相当一部分实际承担的是：

```text
ADB pairing
ADB identity
Wireless discovery
TLS
ADB connection
ADB protocol
ADB shell
ADB recovery
```

也就是说：

> **很多真正属于 ADB Engine 的能力，是在旧的 AT 概念下面成长起来的。**

随着 Local PTY、ADB Remote PTY、Terminal 架构研究逐渐完成，我们现在已经知道：

```text
ADB Engine ≠ AT Engine
```

这意味着继续在 V2 原地大规模 rename / move / split，会长期受到历史命名和依赖关系影响。

所以选择：

```text
V2 = Verified Reference
V3 = Canonical Production Mainline
```

比原地重构更干净。

---

## 3. V3 的构建原则

V3 必须遵守以下原则。

### 3.1 不重新实现已经验证的底层能力

例如：

```text
ADB pairing
ADB TLS
ADB connection
ADB shell-v2
ADB interactive stream
Local PTY
resize
Ctrl-C
cleanup
```

已经通过真实设备验证的代码，应优先迁移、封装和归位。

不要无理由重新实现。

---

### 3.2 按职责迁移，而不是按旧 Module 搬家

禁止：

```text
旧 :at-core
        ↓
整体复制
        ↓
新 :adb-engine
```

正确方式：

```text
旧组件
↓
判断真实职责
↓
迁移到 V3 正确位置
```

例如：

```text
AdbClient
→ ADB Engine

AdbInteractiveStream
→ ADB Engine

pairing / TLS / identity
→ ADB Engine

Local PTY implementation
→ Local Backend / Local Provider

execute(command) command semantics
→ AT Engine

TerminalSession
→ AT Engine

Terminal Emulator
→ AT Engine
```

---

### 3.3 架构先固定，接口通过实现验证后再冻结

当前已经具备冻结**职责边界**的条件。

但还不适合凭空冻结最终 Kotlin / C++ API。

因此遵循：

```text
Canonical Architecture Freeze
        ↓
Minimum Contract v0 Draft
        ↓
真实 Vertical Slice
        ↓
Local / ADB 两种 Backend 验证
        ↓
修正 Contract
        ↓
Contract v0 Freeze
```

而不是：

```text
先设计完所有最终 API
↓
然后强迫实现适配 API
```

当前生产代码尚不存在正式 TerminalSession、Terminal Emulator、Screen State 和统一 Interactive Backend，因此 Contract 必须经过实际实现验证。

---

## 4. Canonical Architecture

V1 正式采用以下架构：

```text
┌──────────────────────────────────────────────────┐
│ Consumer / Product                               │
│                                                  │
│ AndroidTerminal Human UI                         │
│ AndroidAgent TerminalTool                        │
│ Test Host                                        │
│ Future Product                                   │
└────────────────────────┬─────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────┐
│ AT Engine Public Boundary                        │
│                                                  │
│ One-shot Execution                               │
│ Persistent Terminal                              │
│ Session / Screen Observation                     │
└────────────────────────┬─────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────┐
│ AT Engine                                         │
│                                                   │
│ ┌──────────────────┐   ┌───────────────────────┐ │
│ │ One-shot         │   │ Persistent Terminal   │ │
│ │ Execution        │   │                       │ │
│ │                  │   │ TerminalSession       │ │
│ │ command          │   │ Terminal Emulator     │ │
│ │ stdout           │   │ Screen State          │ │
│ │ stderr           │   │ Lifecycle             │ │
│ │ exitCode         │   │ Input / Resize        │ │
│ └─────────┬────────┘   └──────────┬────────────┘ │
│           │                       │              │
│           └────────────┬──────────┘              │
└────────────────────────┼─────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────┐
│ Backend Adapter                                  │
│                                                  │
│ Local Backend                                    │
│ ADB Backend                                      │
│ Future SSH Backend                               │
└──────────────┬───────────────────┬───────────────┘
               │                   │
               ▼                   ▼
┌──────────────────────┐  ┌────────────────────────┐
│ Local Execution      │  │ ADB Engine             │
│ Provider             │  │                        │
│                      │  │ Pairing                │
│ Process              │  │ Identity               │
│ PTY                  │  │ Discovery              │
│ Android Kernel       │  │ TLS / Connection       │
└──────────────────────┘  │ Protocol / Streams     │
                          └────────────┬───────────┘
                                       │
                                       ▼
                                      adbd
                                       │
                                       ▼
                                  Remote Process
```

这里最重要的一点是：

> **One-shot 和 Persistent 是 AT Engine 内的两种执行语义，但最终都通过 Backend / Provider 执行。**

ADB Engine 并不只服务 Persistent Terminal。

当前成熟的 ADB one-shot 本身就是：

```text
One-shot
↓
ADB execution
↓
ADB Engine
↓
adbd
```

这一现实路径。

---

## 5. AT Engine 的正式定义

Canonical AT Engine 定义为：

> **独立于具体 Human UI、Agent、ADB 协议和具体 Shell 的可复用 Terminal Execution Capability。**

AT Engine 主要包含：

```text
AT Engine
│
├─ One-shot Execution
│
└─ Persistent Terminal
   ├─ TerminalSession
   ├─ Terminal Emulator
   ├─ Screen State
   └─ Session Lifecycle
```

AT Engine 不等于：

```text
ADB Engine
/system/bin/sh
Terminal Emulator
AndroidTerminal UI
AndroidAgent
```

其中任何一个单独组件。

---

## 6. Historical Terminology Migration

这是 V3 必须明确的新规则。

历史项目曾定义：

```text
Legacy AT Engine
= AT Runtime + AT Core
```

该历史概念主要代表当前已经实现并验证的：

```text
ADB-dominant Android execution capability
```

而新的 Canonical 定义已经不同：

```text
Canonical AT Engine
=
One-shot Execution
+ Persistent Terminal
+ TerminalSession
+ Emulator
+ Screen State
+ Backend abstraction
```

因此正式规定：

> **历史文档中的“AT Engine”不得自动解释成新的 Canonical AT Engine。**

历史：

```text
AT Engine Handoff v0
third_party/at-engine-handoff-v0
```

继续按照当时语义理解：

```text
Legacy AT Engine Handoff
≈ 已有 Android / ADB execution capability
```

不能因为 V3 重定义了 AT Engine，就认为历史 Handoff 已经具备：

```text
Persistent Terminal
TerminalSession
Terminal Emulator
Screen State
```

这些不存在的能力。

这是名称迁移，不是历史文档回写。

---

## 7. AT Engine 与 ADB Engine

### 7.1 架构关系

正式关系：

```text
AT Engine
    │
    ▼
ADB Backend
    │
    ▼
ADB Engine
```

这是：

> **Consumer → Provider**

关系。

不是：

```text
AT Engine
└─ ADB Engine
```

这种架构包含关系。

---

### 7.2 ADB Engine 负责什么

ADB Engine 独立负责：

```text
Pairing
Identity
Key / Trust
Wireless discovery
Endpoint
TLS
CNXN
OPEN / WRTE / OKAY / CLSE
Connection lifecycle
ADB stream
shell-v2 transport
recovery
future multiplexing
ADB-specific diagnostics
```

无论 Terminal 是否存在，这些能力都成立。

---

### 7.3 AT Engine 负责什么

AT Engine 只关心：

```text
我要执行一条命令
我要得到 stdout/stderr/exitCode

或者

我要打开一个 TerminalSession
我要读写 terminal bytes
我要 resize
我要维护 terminal screen
我要知道 session exit/error
```

它不应该知道：

```text
WRTE
OKAY
CNXN
TLS record
ADB stream id
SPAKE2
```

这些 ADB 协议细节。

TERM.A0.5 已将 AT Engine 与 ADB Engine 关系明确为 composition / consumer-provider。

---

### 7.4 打包关系

架构独立不代表 APK 不能同时包含两者。

例如：

```text
AndroidTerminal.apk
├─ AT Engine
├─ ADB Engine
└─ Human UI
```

这是部署关系。

不是架构 ownership。

---

## 8. Backend Adapter

Backend 可以通俗理解成：

> **AT Engine 与具体执行方式之间的翻译层。**

例如 AT 说：

```text
write(bytes)
resize(rows, cols)
close()
```

Local Backend 将其转换为：

```text
write(fd)
ioctl(TIOCSWINSZ)
close / waitpid
```

ADB Backend 则转换为：

```text
ADB interactive stream write
shell-v2 winsize
stream close
```

因此：

```text
AT Engine
```

不需要知道：

```text
ioctl
PTY fd
WRTE
shell-v2 frame id
```

---

## 9. One-shot Execution

One-shot 是正式的一等能力。

语义：

```text
command
↓
execute
↓
stdout
stderr
exitCode
↓
结束
```

主要特征：

```text
一次调用对应一次任务
stdout/stderr 可分离
exitCode 是 Result
通常不保持 cwd/env
通常不需要 Terminal Emulator
```

但 One-shot **仍然经过 Backend**。

例如：

```text
AT Engine.execute()
↓
ADB Backend
↓
ADB Engine
↓
shell,v2,raw
↓
adbd
```

或者：

```text
AT Engine.execute()
↓
Local Backend
↓
Local Process
```

---

## 10. Persistent Terminal

AndroidTerminal 产品的主要新增建设方向是 Persistent Terminal。

典型生命周期：

```text
open session
↓
启动 shell / program
↓
session 长期存在
↓
持续 read/write
↓
cwd/env 保持
↓
Ctrl-C / resize
↓
vim / top / REPL / TUI
↓
close / exit
```

Persistent Terminal 需要：

```text
TerminalSession
Terminal Emulator
Screen State
Session Lifecycle
Backend
```

ATsh.T1 与 ADB.I0 已经分别证明 Local PTY 和 ADB remote PTY 可以支持 persistent shell 所需的核心交互语义。

---

## 11. TerminalSession

TerminalSession 可以简单理解为：

> **一个正在活着的终端。**

逻辑上关联：

```text
TerminalSession
├─ Backend instance
├─ Terminal Emulator
├─ Screen State
├─ terminal size
├─ session lifecycle
└─ exit / error state
```

注意它不是严格：

```text
TerminalSession
↓
Emulator
↓
Screen State
```

的三个串行模块。

更准确地说：

> TerminalSession 是一个聚合这些终端状态和能力的逻辑对象。

---

## 12. Terminal Emulator 与 Screen State

正式冻结：

```text
Terminal Emulator
Screen State
cursor
terminal modes
scrollback
```

属于 AT Engine。

Android Renderer 不属于 AT Engine。

数据路径：

```text
Program
↓
PTY / remote PTY
↓
raw bytes
↓
Terminal Emulator
↓
Screen State
```

然后：

```text
Screen State
├─ Android Human Renderer
└─ Future Agent Observer
```

TERM.A0.5 已经修订 TERM.A0 原来的 Presentation 边界，将 Emulator / Screen State 移入 AT Engine。

---

## 13. Shell / Program

正式禁止：

```text
Terminal = Shell
```

Shell 只是 Terminal 中运行的程序之一。

例如：

```text
PTY
↓
/system/bin/sh
```

只是当前默认情况。

也可能是：

```text
PTY
↓
python
```

或者：

```text
PTY
↓
vim
```

长期概念：

```text
TerminalSession
↓
Backend
↓
Launch Target
```

当前不需要提前实现复杂的通用 `LaunchTarget` API。

只要求架构不再将 `/system/bin/sh` 视为 Terminal 的定义。

---

## 14. Headless Terminal

Headless Terminal 不等于 AT Engine。

更准确：

```text
AT Engine
= 可复用能力
```

而：

```text
AndroidTerminal
= AT Engine + Human UI
```

```text
Headless Terminal
= AT Engine + 非 Human UI consumer
```

所以 Headless Terminal 是一种基于 AT Engine 构建的产品/运行形态。

---

## 15. AndroidAgent 与 AT Engine

AndroidAgent 不定义为：

```text
AndroidAgent
= AG Engine + AT Engine
```

长期正确关系是：

```text
AndroidAgent Product
=
AG Engine
+ Tool Set
+ Tool-specific capabilities
```

例如：

```text
                    AG Engine
                       │
                  Tool Registry
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
   TerminalTool   AndroidApiTool   HttpTool
          │            │            │
          ▼            ▼            ▼
      AT Engine     Framework      HTTP
```

因此：

> **AT Engine 是 AndroidAgent 可以通过 TerminalTool 使用的一项重要执行能力，但不是 AndroidAgent 的统一执行总线。**

AG Core / Agent Runtime 不直接依赖：

```text
ADB Engine
PTY
AT internal implementation
```

---

## 16. AG 的 one-shot / Persistent 策略

AT 不替 AG 决定策略。

AT 只提供：

```text
One-shot
Persistent Session
```

AG 最终可以：

```text
普通 ToolCall
→ one-shot
```

需要交互时：

```text
REPL / top / vim / TUI
→ persistent session
```

具体什么时候使用 persistent、是否复用 session、是否读取 Screen State：

> 由 AndroidAgent 的 Agent 架构与成熟 Agent 调研决定。

AT 只保证能力边界正确。

---

## 17. Human 与 Agent Shared Session

当前不假设 Human 和 Agent 默认共享 TerminalSession。

默认模型可以是：

```text
Human
→ Human TerminalSession
```

而：

```text
Agent
→ one-shot
```

或者：

```text
Agent
→ Agent-owned TerminalSession
```

未来如果出现明确需求：

```text
Agent观察Human当前vim
Agent接管当前Terminal
Human重新取得控制
```

再研究：

```text
attach
observer
handoff
input ownership
multi-client
```

因此：

```text
Human-Agent Shared Session
= DEFERRED
```

> **AT.DEBUG-0 更新（2026-10-16）**：真实需求已触发，并完成一个
> **developer-only Debug vertical slice** —— UI-visible Programmatic Terminal Bridge
> （debug build only，loopback `127.0.0.1:45679`），让 PC/OpenCode **attach** 到 Human UI
> 已打开的真实 TerminalSession（不创建第二个隐藏 session），Human 与 Debug 共享同一个
> ATTerminalSession（单 listener、单 RX owner、serialized write、双向 input-activity +
> output + state fan-out）。这是 **HUMAN_DEBUG_SHARED_SESSION**，不是完整
> **HUMAN_AGENT_SHARED_SESSION**。
>
> ```text
> PROGRAMMATIC_HUMAN_SHARED_SESSION = VERIFIED（AT.DEBUG-0）
> HUMAN_DEBUG_SHARED_SESSION        = VERIFIED（AT.DEBUG-0）
> HUMAN_AGENT_SHARED_SESSION        = DEFERRED / NOT_STARTED
> ATTACH / HANDOFF / TAKEOVER       = DEFERRED（Agent ownership 未设计）
> ```
>
> DebugBridge 是 developer-only transport，**不是** AT Engine feature、**不是** future AG
> protocol、**不是** AT.CLI。AT Engine public contract 与 ADB Engine 均未改。

---

## 18. Input System

当前只要求 Persistent Session 能支持：

```text
write(bytes)
```

测试阶段完全可以通过：

```text
文本输入
Send 按钮
Ctrl-C 按钮
方向键按钮
resize callback
```

完成。

不提前建设完整：

```text
Android KeyEvent abstraction
advanced keyboard protocol
terminal mode-aware input engine
复杂 IME mapping
```

这些等真正建设成熟 Human UI 时再设计。

AG 输入方式同样由 AG 实际需求决定。

---

## 19. AndroidTerminal-V3 的物理工程原则

五层 Canonical Architecture 是职责边界。

并不要求：

```text
五层 = 五个 Gradle Module
```

但有两个独立 Engine 的物理边界应该从 V3 开始就明确：

```text
AT Engine
ADB Engine
```

推荐的 V3 初始工程结构可以围绕以下职责组织：

```text
AndroidTerminal-V3/
│
├─ docs/
│  ├─ architecture/
│  ├─ migration/
│  └─ evidence/
│
├─ AT Engine
│
├─ ADB Engine
│
├─ Local Backend
│
├─ ADB Backend
│
├─ AndroidTerminal App
│
└─ Test Host
```

是否全部对应独立 Gradle Module，由 V3 Bootstrap 阶段根据依赖关系决定。

核心要求只有：

```text
ADB Engine 可以独立存在
AT Engine 不依赖 Android UI
ADB protocol 不进入 AT domain
Backend 依赖方向清楚
```

---

## 20. V2 → V3 Migration Model

V2 从 V3 开始承担：

```text
Verified Reference Implementation
Evidence Repository
Regression Oracle
Migration Source
```

原则上不再承担新的 Canonical Architecture 主线开发。

迁移流程：

```text
V2 known-good implementation
        ↓
确认真实职责
        ↓
记录 Migration Manifest
        ↓
迁入 V3
        ↓
编译验证
        ↓
行为 parity
        ↓
必要时真机 regression
        ↓
MIGRATED / VERIFIED
```

---

## 21. 第一项重要迁移：ADB Engine Extraction

V3 最重要的前置整理之一，是：

> **把旧 AT 体系中实际属于 ADB 的能力重新归位为独立 ADB Engine。**

典型内容：

```text
ADB Engine
├─ Pairing
├─ Identity
├─ Key / Trust
├─ Discovery
├─ Endpoint
├─ TLS
├─ Connection
├─ Protocol
├─ shell-v2
├─ Interactive Stream
├─ Recovery
└─ Diagnostics
```

旧 `AtRuntime` / `at-core` 等组件不能整体搬迁。

必须拆分真实职责。

例如：

```text
旧 AtRuntime
│
├─ discovery
├─ endpoint
├─ readiness
├─ recovery
└─ execute(command)
```

在新架构中可能分别落入：

```text
ADB Engine Runtime
├─ discovery
├─ endpoint
├─ readiness
└─ recovery
```

以及：

```text
AT Engine
└─ execute(command) execution semantics
```

这是 V3 的核心“重新归位”，不是重新发明功能。

---

## 22. V3 开发路线

V3 不采用单一路线。

总体首先完成：

```text
V3-0
Canonical Bootstrap
```

包括：

```text
建立 AndroidTerminal-V3
建立新的 Git / project baseline
落盘本文
建立 Migration Manifest
建立最小工程骨架
验证 clean build
保护 V2 不被修改
```

随后完成第一轮责任归位：

```text
V3-1
ADB Engine Extraction / Migration Baseline
```

然后进入相对独立的三条 Track。

---

## 23. Track A — ADB Engine

ADB Engine 继续独立演进：

```text
ADB ENGINE Track
│
├─ Pairing
├─ Identity
├─ Wireless Bootstrap
├─ Discovery
├─ Connection
├─ Recovery
├─ Streams
└─ Future Multiplexing
```

它不需要等待 AT Persistent Terminal。

具体从哪个任务继续：

> **必须以 V2 当前最新 SSOT / 实际 repo 状态为准。**

不得因为旧报告、旧 NEXT 或历史审查文字重新开启已经 VERIFIED/CLOSED 的阶段。

---

## 24. Track B — AT Engine

AT Engine 是 AndroidTerminal 当前最重要的新建设主线。

建议：

```text
AT.CANONICAL-0
Canonical Architecture
本文
        ↓
AT.CONTRACT-0
Minimum Contract v0 Draft
        ↓
AT.PERSIST-0
Local Persistent Vertical Slice
        ↓
AT.PERSIST-1
ADB Persistent Vertical Slice
        ↓
AT.EMULATOR-0
Minimal Terminal Emulator
+ Minimal Screen State
        ↓
AT.CONTRACT-1
真实行为验证后的
Common Session / Backend Contract Freeze
        ↓
        ┌─────────────────────┐
        ▼                     ▼
AT.HUMAN-*              AG.TERMINAL-*
Human UI                TerminalTool
Renderer                Agent consumption
TUI verification        Agent scenarios
```

注意：

> `AT.CONTRACT-0` 是 Draft，不是 Final Freeze。

---

## 25. Track C — AndroidAgent

AndroidAgent 继续自己的独立主线。

不会出现：

```text
AT全部完成
↓
AG才能继续
```

正确关系：

```text
AG Engine Track ────────────────┐
                                │
                                ├─ Versioned Tool Contract
                                │
AT Engine Track ────────────────┘
```

当 AT Persistent capability 足够成熟以后：

```text
TerminalTool
↓
consume AT Engine
```

这一步称为：

```text
AG Terminal Capability Integration
```

而不是笼统的：

```text
AG integration
```

Human UI 同样不是 Agent Terminal integration 的前置条件。Human 和 Agent 是平级 consumer。

---

## 26. 当前冻结的内容

Canonical V1 从现在开始冻结以下架构判断：

```text
AT Engine 与 ADB Engine 架构独立
```

```text
AT Engine
├─ One-shot
└─ Persistent Terminal
```

```text
One-shot 与 Persistent
都通过 Backend / Provider
```

```text
ADB Backend
→ ADB Engine
```

```text
Local Backend
→ Local execution mechanism
```

```text
Terminal Emulator
+ Screen State
→ AT Engine
```

```text
Android Renderer
→ Product / Human UI
```

```text
Shell / Program
→ Launch Target
```

```text
AG execution strategy
→ AG responsibility
```

```text
Human-Agent shared session
→ DEFERRED
```

```text
SSH implementation
→ DEFERRED
```

---

## 27. 当前不冻结的内容

以下内容暂时不得因为“Canonical”三个字而提前锁死：

```text
具体 Kotlin interface name
具体 C++ class name
package name
最终 Gradle module 数量
callback / coroutine / Flow
TerminalSession 最终 API
ScreenState 最终数据结构
Emulator library / implementation
multi-session API
attach / handoff API
SSH API
高级 Keyboard/Input API
```

这些通过 Vertical Slice 和真实场景逐步确定。

---

## 28. 当前明确后置的能力

```text
Human-Agent shared session
attach / detach
observer / handoff
multi-client
session migration
record / replay
screen diff optimization
复杂 multiplexer
完整 keyboard abstraction
SSH implementation
复杂 daemon architecture
workspace abstraction
plugin system
```

原则：

> 为未来保留正确边界，不为未来提前实现系统。

---

## 29. E1 / E2 / E3

TERM.A0.5 已确认：

```text
TERM.E1
Activity death / session survival
= IMPLEMENTATION VALIDATION
```

```text
TERM.E2
same ADB connection concurrent interactive streams
= BACKEND CAPABILITY VALIDATION
```

```text
TERM.E3
旧 Presentation / ANSI framing
= SUPERSEDED
```

三者均不再阻塞 Canonical Architecture。

真正需要时，在对应 implementation slice 中验证。

---

## 30. 开发纪律

每个 V3 任务继续遵守：

```text
现状检查
↓
确定 Source of Truth
↓
确认迁移/实现职责
↓
最小修改
↓
build/test
↓
必要时真机验证
↓
文档
↓
STOP
```

禁止：

```text
未经授权自动启动下一阶段

看到旧类名就自动 rename

为了架构图整齐而大规模拆 module

无理由重新实现 V2 已验证 primitive

把旧文档里的 NEXT 当成当前 Controller NEXT

把打包关系误认为架构 ownership

为了未来 AG 猜测需求而提前过度设计 AT

重新打开已经 CLOSED 的 Evidence Task
```

---

## 31. V3 的成功标准

V3 成功不是：

```text
目录比 V2 漂亮
```

也不是：

```text
所有类都换了名字
```

真正成功标准是：

```text
职责边界正确
```

```text
ADB Engine 可以独立使用
```

```text
AT Engine 可以不依赖具体 ADB protocol
```

```text
Local / ADB 都可以作为 AT Backend
```

```text
One-shot 与 Persistent 都工作
```

```text
Terminal Emulator / Screen State 可以无 UI 工作
```

```text
AndroidTerminal UI 可以只是 AT Engine 的消费者
```

```text
AndroidAgent 可以通过 TerminalTool 消费 AT Engine，
但其它 Tool 不经过 AT
```

以及：

> **V2 已验证能力迁入 V3 后不存在功能倒退。**

---

## 32. 当前项目定义

### AndroidTerminal

```text
AndroidTerminal
=
AT Engine
+ Human UI
+ selected Backends / Providers
```

---

### AT Engine

```text
AT Engine
=
One-shot Execution
+
Persistent Terminal Capability
+
Terminal semantics
+
Backend abstraction
```

---

### ADB Engine

```text
ADB Engine
=
ADB protocol
+
Pairing / Identity
+
Wireless discovery
+
TLS / Connection
+
Streams
+
ADB lifecycle
```

---

### AndroidAgent

```text
AndroidAgent Product
=
AG Engine
+
Tool Set
+
Tool-specific capabilities
```

其中：

```text
TerminalTool
→ AT Engine
```

只是 Tool Set 的一个分支。

---

## 33. 一句话架构定义

> **AndroidTerminal 是一个基于 AT Engine 构建的人类终端产品；AT Engine 提供独立于具体 UI 和传输协议的 one-shot execution 与 persistent terminal 能力，通过 Local / ADB 等 Backend 使用相应 Execution Provider。ADB Engine 是独立的 ADB 能力提供者，通过 ADB Backend 被 AT Engine 使用；AndroidAgent 则通过 TerminalTool 按需消费 AT Engine，而不以 AT Engine 作为统一执行总线。**

---

## 34. 当前阶段结论

```text
AT_CANONICAL_ARCHITECTURE = V1 FROZEN

ARCHITECTURE_DIRECTION     = APPROVED

AT_ENGINE                  = CANONICAL TERMINAL EXECUTION CAPABILITY

ADB_ENGINE                 = INDEPENDENT EXECUTION PROVIDER

V2_ROLE                    = VERIFIED REFERENCE / EVIDENCE / MIGRATION SOURCE

V3_ROLE                    = NEW PRODUCTION MAINLINE

REWRITE                     = NO

VERIFIED_MIGRATION          = YES

IMMEDIATE_LARGE_REFACTOR    = NO

FINAL_API_FREEZE            = NO

MINIMUM_CONTRACT_DRAFT      = NEXT AFTER V3 BOOTSTRAP

PERSISTENT_TERMINAL         = PRIMARY NEW AT CAPABILITY

AG_MAINLINE                 = PARALLEL

 HUMAN_AGENT_SHARED_SESSION  = DEFERRED
 ```

### 34.1 AT.CLI-0 当前状态（shell-hosted `adb` CLI 入口恢复）

```text
AT_CLI_0_OVERALL_STATUS        = IN_PROGRESS
AT_CLI_0_ENTRYPOINT_RECOVERY   = COMPLETE
ADB_CLI_ONESHOT_CORE           = VERIFIED
ADB_RUNTIME_LIFECYCLE          = COMPLETE          # AT.CLI-0.1A
LIVE_COMMAND_STATUS            = VERIFIED          # AT.CLI-0.1B

SELINUX_EXEC_BLOCKER           = VERIFIED
PRIVATE_ELF_ENTRYPOINT         = REJECTED
C8_C12_PREVIOUS_RESULT         = INVALIDATED
TEST_HARNESS_BUG               = VERIFIED

APP_PROCESS_P1_P6              = 6/6 PASS
SELECTED_CLI_ENTRYPOINT        = APP_PROCESS
ADB_CLI_ONESHOT_TEST           = 15/15 PASS (O1-O15, 97s, flaky NO)
ADB_CLI_LIFECYCLE_TEST         = 11/11 PASS (L1-L11, AT.CLI-0.1A)
ADB_CLI_LIVE_STATUS_TEST       = 9/9 PASS (S1-S9, AT.CLI-0.1B)

PRODUCT_CLI_BRIDGE             = EXISTING UDS + PROTOCOL V1 + T_STATUS=12 (REUSE)
AT_ENGINE_MODIFIED             = NO
ADB_PROTOCOL_ENGINE_MODIFIED   = NO
ADB_NATIVE_TRANSPORT_MODIFIED  = NO
ADBRUNTIME_LIFECYCLE_MODIFIED  = YES

SHELL_HOSTED_ADB_CLI           = ONESHOT+LIFECYCLE+LIVE_STATUS_VERIFIED_INTERACTIVE_NOT_STARTED
FULL_ADB_CLI                   = INTERACTIVE_NOT_STARTED
AUTO_WD_PRODUCT_INTEGRATION    = COMPLETE          # WRITE_SECURE auto-enable + bounded retry
CONNECTION_STATUS_TERMINAL_EVENTS = COMPLETE       # T_STATUS async -> Local PTY, session-scoped

ADB_RUNTIME_ROLE               = LONG_LIVED_LIFECYCLE_OWNER
AUTO_READY                     = RUNTIME_OWNED_ENSURE_READY
TRANSPORT_LOSS_PROJECTION      = READ_ONLY_OBSERVER_EDGE (1.5s poll, no ping/socket/heartbeat)
RECOVERY_OWNER                 = ADB_RUNTIME (ONE, bounded 3-attempt)
TERMINAL_STATUS_PROJECTION     = T_STATUS -> CLI STDERR -> LOCAL PTY (session-scoped, dedupe)
LIVE_COMMAND_STATUS_PROJECTION = HOST emitSystemOutput (synchronous, command-time, before EXIT)
LIVE_COMMAND_STATUS_TEST       = 9/9 PASS (S1-S9, AT.CLI-0.1B)
STATUS_BEFORE_COMMAND_EXIT     = REQUIRED (S1 timestamp proof)
STATUS_DUPLICATE               = NO (single projection path: emitStatus)
T_STATUS_FALLBACK              = PRESERVED (non-Human client, buffered)

# --- fresh-install / post-bootstrap 语义（AT.CLI-0.1A.R 归一化，正式冻结）---
FRESH_INSTALL_ZERO_TOUCH           = NOT_COMPLETE
FIRST_USE_MANUAL_BOOTSTRAP         = REQUIRED
POST_BOOTSTRAP_AUTO_WD             = VERIFIED
POST_BOOTSTRAP_ONE_COMMAND_COLD_READY = VERIFIED
POST_BOOTSTRAP_ZERO_MANUAL_COLD_READY = VERIFIED   # 限定：post-bootstrap 后，非 fresh install
FINAL_REGRESSION_CLOSURE           = PASS          # 本轮改动路径关键回归全过；PC-orchestration suite 失败非 causal
  ```

> **one-shot 前置（AT.CLI-0.1 冻结）**：WD=ON + identity TRUSTED + AdbRuntime READY。
> **AT.CLI-0.1A 闭合**：WD OFF / DISCONNECT 场景由 AdbRuntime ensureReady + auto-WD +
> read-only transport observer + async T_STATUS 覆盖（L1-L11 = 11/11 PASS）。
>
> **fresh-install / post-bootstrap 语义（归一化）**：
> - `FRESH_INSTALL_ZERO_TOUCH = NOT_COMPLETE`：fresh install 无 WRITE_SECURE，需首次**手动**
>   WD + trust/pair + WRITE_SECURE bootstrap（`pm grant`）。
> - `FIRST_USE_MANUAL_BOOTSTRAP = REQUIRED`。
> - `POST_BOOTSTRAP_AUTO_WD = VERIFIED` + `POST_BOOTSTRAP_ONE_COMMAND_COLD_READY = VERIFIED`：
>   完成首次 bootstrap **以后**，WD OFF → `adb shell id` → auto-WD → discovery → connect →
>   connected → uid=2000，无需额外用户操作。
> - `POST_BOOTSTRAP_ZERO_MANUAL_COLD_READY = VERIFIED`：**限定语义**——"零手动"仅指
>   post-bootstrap 状态（非 fresh install）。避免与 `FRESH_INSTALL_ZERO_TOUCH` 混淆。

要点（详见 `docs/evidence/at_cli_0/`）：

- 原 `files/bin/adb` private ELF 入口被真实 SELinux
  `untrusted_app → app_data_file execute` 阻断（真实 Local PTY 中
  `exec` 返回 **RC=126 / Permission denied**），正式 **REJECTED**。
- `runas_app` 域执行同一 ELF 虽 PASS，但 **不能代表真实 Local PTY**
  （Local PTY 的 `/system/bin/sh` 位于 `untrusted_app` 域，非
  `runas_app`）。
- c8–c12 旧 PASS 因 `ActivityScenario`/test harness bug（`onActivity`
  对第 2+ 测试未实际执行 body + 误用 listener 激活下返回 null 的 pull
  `read()`）**未实际执行**，正式 **INVALIDATED**。
- `/system/bin/app_process` 入口（context `zygote_exec`）在真实 Local
  PTY（`untrusted_app` 域）**P1–P6 全 PASS**：可 exec、可加载 APK 内
  main class、传 argv、继承 Local PTY stdin/stdout/stderr、连接既有
  Product CLI Bridge（同 UID UDS + protocol v1）、正常返回 Local shell。
- **Selected CLI entrypoint = APP_PROCESS**（`/system/bin/app_process
  -Djava.class.path=<base.apk> /system/bin <CliMain> <args...>`）。
- AT Engine / ADB Engine **均未修改**；Product CLI Bridge 继续复用现有
  同 UID UDS + protocol v1。
- shell-hosted `adb shell <command...>` **one-shot 已 VERIFIED**（`AdbCliOneShotTest`
  15/15，真机 Human Local 路径：Local sh → `adb()` 函数 → app_process → AdbCliMain →
  既有 Product CLI Bridge（同 UID UDS，protocol v1）→ AppRuntime → AT Engine → ADB
  Backend → ADB Engine → adbd → 远程 sh uid=2000）。principal transition 实测
  `uid=10313 → uid=2000(shell) → uid=10313`（O3），Local shell 存活（O15）。
  详见 `docs/evidence/at_cli_0/ONESHOT_CLOSURE.md`。
- **AT.CLI-0.1A（Auto-WD + connection lifecycle）= COMPLETE**：AdbRuntime 作为长驻
  生命周期 owner，`adb command` 零手动前置 cold-ready（WD OFF → auto-WD → connected），
  transport 丢失/重连以 async T_STATUS 投影到订阅 Local session（session-scoped + dedupe +
  no-replay）。`AdbCliLifecycleTest` L1-L11 = **11/11 PASS**（真机 HONOR ALI-AN00）。
  详见 `docs/evidence/at_cli_0/ADB_RUNTIME_LIFECYCLE_CLOSURE.md`。
- **AT.CLI-0.1B（Live command-time ADB status）= VERIFIED**：0.1A 缺口修复——cold-connect
  命令时刻中间状态（`checking`/`enabling`/`discovering`/`connecting`）原本走短命 AdbCliMain
  PTY stderr（ensureReady 阻塞期间不 live，EXIT 才刷出）；现经
  `AdbCliStatusHub.emitCommandStatus`（单一命令时刻投影 owner，同步非 main.post）+
  `AdbCliBridgeServer.emitStatus`（有效 Local session→Host 投影 live / 否则 T_STATUS
  fallback，单投影路径）+ `ensureReady` subscribe 提前 + statusListener 跳过 `connected`
  （lifecycle transition 拥有，避免重复）+ fast path backfill + `onTransition` 改同步。
  `AdbCliLiveStatusTest` S1-S9 = **9/9 PASS**（真机 HONOR ALI-AN00），核心 S1 证明中间 status
  在 `uid=2000` 之前被 observer 观察到（时间戳，STATUS_BEFORE_COMMAND_EXIT=REQUIRED）。
  STATUS=HOST/SYSTEM TERMINAL EVENT（emitSystemOutput）/ STATUS_DUPLICATE=NO / T_STATUS 保留
  （非 Human fallback）。详见 `docs/evidence/at_cli_0/LIVE_COMMAND_STATUS_CLOSURE.md`。
- **interactive `adb shell`（无参）+ Ctrl-C/resize relay = NOT_STARTED**（AT.CLI-0.2）。
- 故 `SHELL_HOSTED_ADB_CLI = ONESHOT+LIFECYCLE+LIVE_STATUS_VERIFIED_INTERACTIVE_NOT_STARTED`，
  **不**标 COMPLETE。

---

## 35. Immediate Next Step

下一步不继续讨论“AT 到底是什么”。

也不立即开始 Terminal Emulator 或 Persistent Terminal。

首先执行：

```text
AT.V3-0 — AndroidTerminal-V3 Clean Architecture Bootstrap
```

目标：

```text
创建 AndroidTerminal-V3
↓
落盘本 Canonical V1
↓
建立 V3 最小工程骨架
↓
建立 Legacy → V3 Migration Manifest
↓
明确 V2 read-only reference 角色
↓
盘点旧 :at-core / :at-runtime 中
哪些属于 ADB Engine
哪些属于 AT Engine
哪些属于 legacy glue
↓
形成 V3 migration map
↓
clean build baseline
↓
STOP
```

然后再进入：

```text
AT.V3-1 — ADB Engine Extraction / Migration
```

由此开始真正的 V3 实现主线。

### 35.1 当前实际 NEXT（AT.CLI-0.2）

AT.CLI-0 入口恢复 COMPLETE，one-shot core VERIFIED（15/15），**connection lifecycle
（AT.CLI-0.1A）COMPLETE**（`ADB_RUNTIME_LIFECYCLE = COMPLETE`，`AdbCliLifecycleTest`
L1-L11 = 11/11），**live command-time status（AT.CLI-0.1B）VERIFIED**（`LIVE_COMMAND_STATUS
= VERIFIED`，`AdbCliLiveStatusTest` S1-S9 = 9/9）。当前实际 NEXT：

```text
AT.CLI-0.2
  — Interactive `adb shell` (无参) + Ctrl-C / resize relay
```

目标：无参 `adb shell` 进入交互式 relay（stdin/stdout/resize 透传到远程 shell），
Ctrl-C 信号 relay。（不将 AT.CLI-0 整体标 COMPLETE：interactive 未开始。）

---

## Final Decision

AndroidTerminal 已经完成从：

```text
“以内置 ADB 为核心的 Android 执行工具”
```

向：

```text
“拥有独立 AT Engine、
支持多种执行 Backend、
可以同时服务 Human 与 Agent 的
可复用 Terminal 平台”
```

的架构转变。

V2 的价值在于证明这些关键技术能够工作。

V3 的任务则是：

> **不丢弃 V2 已经获得的工程证据，把这些能力重新放回它们真正应该存在的位置，并在干净的 Canonical Architecture 上继续开发。**

从 V3 开始：

> **不再围绕历史命名修补架构，而围绕已经冻结的职责边界迁移和实现。**