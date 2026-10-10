# AndroidTerminal-V3 — 开发约束与环境速查

> 仓库角色：**Canonical Production Mainline**
> 原则：`Verified migration, not rewrite` / `Architecture first, contract after implementation` / 无顺手重构

---

## 0. 仓库保护（最重要）

```text
可写 WRITE:
  D:\codebank\AndCode\AndroidTerminal-V3\        ← 本仓库（canonical mainline）

只读 READ-ONLY（禁止修改 / 删除 / rename / move / commit）:
  D:\codebank\AndCode\AndroidTerminal-V2\        # Verified Reference / Evidence / Regression Oracle / Migration Source
  D:\codebank\AndCode\AndroidTerminal\           # legacy + SSOT 来源
  D:\codebank\AndCode\AndroidTerminal-Public\    # known-good production 来源
  D:\codebank\AndCode\_archives\                 # 迁移安全点
```

- V2 承担：Verified Reference Implementation / Evidence Repository / Regression Oracle / Migration Source。
- **V3 不修改 V2**；迁移只从 V2 读取（README-only），迁入 V3 后做 parity / regression。

---

## 1. Canonical Architecture（SSOT）

控制文档：`docs/architecture/AndroidTerminal_Canonical_V1.md`

```text
Consumer / Product
        │
        ▼
AT Engine Public Boundary
        │
        ▼
AT Engine
   ├─ One-shot Execution
   └─ Persistent Terminal (TerminalSession / Emulator / Screen State)
        │
        ▼
Backend Adapter
   ├─ Local Backend
   ├─ ADB Backend
   └─ Future SSH Backend
        │
        ▼
Execution Provider
   ├─ Local Process / PTY
   └─ ADB Engine → adbd
```

- **AT Engine ≠ ADB Engine**。AT Engine 是消费方，ADB Engine 是 provider（consumer→provider，非包含）。
- **Terminal Emulator + Screen State 属于 AT Engine**；**Android Renderer 属于 Product / Human UI**。
- **Shell ≠ Terminal**；Shell/Program 是 launch target。
- **AG 经 TerminalTool 按需消费 AT Engine**，不以 AT Engine 为统一执行总线。

---

## 2. 术语纪律（Canonical §6）

```text
历史 "AT Engine"（= AT Runtime + AT Core）  ≠  Canonical AT Engine
```

- 历史文档 / `at-engine-handoff-v0` 中的 "AT Engine" 按当时语义（legacy ADB-dominant execution capability）理解。
- 不得因 Canonical 重定义，就认为历史实现已具备 Persistent Terminal / TerminalSession / Emulator / Screen State（这些尚不存在）。

---

## 3. 开发纪律

每个任务必须：

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
文档 + 本轮交付
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

## 4. 本轮交付（每轮必须执行）

```text
本轮交付/
```

- **扁平目录**：`本轮交付/` 下**只允许文件，不允许子文件夹**。
- **每轮任务结束**（文档落盘 + commit 前）：
  1. **先清空** `本轮交付/` 内全部内容；
  2. **再把本轮修改产生 / 交付的文档**（报告、清单、Manifest、SSOT 等）**复制**进去（用文件名区分，如 `AT.V3-0_Canonical_Bootstrap.md`、`Migration_Manifest_V2_to_V3.md`）。
- 目的：每轮结束后 `本轮交付/` 恰好反映"本轮"产出，避免历史堆积。
- 根因：Canonical V1 §35 与各 Track 每轮都以"文档 + 交付"收尾；本目录即交付面。

---

## 5. 构建

```powershell
cd D:\codebank\AndCode\AndroidTerminal-V3
.\gradlew.bat assembleDebug --console=plain
```

- Gradle 8.7 wrapper（distribution 已缓存）。
- `local.properties` 机器特定（sdk.dir），**不提交**。
- 模块：`:adb-engine` / `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:app` / `:test-host`（PROVISIONAL）。

## 工具链路径

| 组件 | 路径 |
|---|---|
| adb | `C:\android\platform-tools\adb.exe` |
| SDK root | `C:\android`（platform-34 / build-tools 34,35） |
| NDK | `C:\android\ndk\27.0.12077973` |
| CMake | `C:\android\cmake\3.22.1` |
| JDK 21 | `C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot` |

---

## 6. Device evidence

```text
BUILD SUCCESSFUL        != runtime verified
没有设备                 != real-device PASS
```

JNI / Kotlin ABI 变更必须经真机运行时验证才能称为 verified（V2 AT.R2V 教训）。

---

## 7. Git

```text
默认分支 : master
禁止 PUSH（除明确授权）
```

- V2 现有 tag / HEAD 不得移动（V2 只读）。
- V3 commit 前确保 `本轮交付/` 已按 §4 刷新。

---

## 8. 文档

- Canonical SSOT：`docs/architecture/AndroidTerminal_Canonical_V1.md`
- V3 报告：`docs/implementation/*`
- Migration：`docs/migration/*`
- Evidence 索引：`docs/evidence/*`
- 每轮交付：`本轮交付/*`（扁平，见 §4）
- V2 SSOT / 研究报告：指向 `AndroidTerminal-V2/docs/...`（只读引用）