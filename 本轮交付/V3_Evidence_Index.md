# V3 Evidence Index

> **AT.V3-0** — Evidence Repository 指针。
>
> V3 不重新保存 V2 已验证证据；V2 作为 Evidence Repository（READ-ONLY）保留全部原始报告与证据。本目录仅登记 V3 产生的证据/决策与指向 V2 的索引。

## 本阶段（AT.V3-0）

| 项 | 位置 |
|---|---|
| Canonical V1 架构 | `docs/architecture/AndroidTerminal_Canonical_V1.md` |
| V2 → V3 Migration Manifest | `docs/migration/Migration_Manifest_V2_to_V3.md` |
| V2 职责盘点（ADB/AT/legacy） | `docs/migration/V2_Responsibility_Inventory.md` |
| V3 骨架 clean build | 6 modules `assembleDebug` BUILD SUCCESSFUL（190 tasks） |

## 后续阶段

| 项 | 位置 |
|---|---|
| AT.DEVAPP-0.1 UI 截图证据（7 张，真机） | `docs/evidence/at_devapp_01/*.png` |
| AT.DEVAPP-0.2 动态 Session 分页截图证据（9 张，真机） | `docs/evidence/at_devapp_02/*.png` |
| AT.V3-2 ADB Backend 纵向切片证据（one-shot/persistent PASS + multi-session BLOCKED） | `docs/evidence/at_v3_2/EVIDENCE.md` |
| AT.V3-2.1 concurrent ADB stream closure 证据（Gate C 关闭：1 conn / 1 TLS / 3 并发 stream / 整类 25 tests PASS） | `docs/evidence/at_v3_2_1/EVIDENCE.md` |
| AT.V3-3 backend parity / execution contract closure 审计证据（one-shot O1–O5 双后端 / ADB 大输出 blocker / persistent p1–p8 / multi-session / cross-mode / transport-loss t1 / 回归 6 套） | `docs/evidence/at_v3_3/EVIDENCE.md` |
| AT.V3-2.2 ADB one-shot flow-control closure 证据（WRTE/OKAY 1:1 credit / 64K 66560B / 256K 266240B / 1M 分块 1048582B / mixed stdout+stderr+nonzero / reuse / cross-mode large / failure-path f1 / 整类 25+34 / 回归 Local 11 / AT 6 / App 7 / Headless 1 / Dynamic 9） | `docs/evidence/at_v3_2_2/EVIDENCE.md` |
| AT.V3-2.3 ADB-only composition & auto-connection / recovery closure 证据（ADB-only artifact isolation Gradle/dex/native / deterministic lifecycle 12/12 / 真机 G-gates：cold-start connectAuto / reuse / persistent+multi / dynamic endpoint recovery 42713→40437 / no transparent replay / post-recovery command+session / WD OFF / error observability / 回归 Local 11 + AT 6 + App 16 + Headless 1 + ADB 25 + Parity 34） | `docs/evidence/at_v3_2_3/EVIDENCE.md` |
| AT.V3-DEBT-0 V2 verified debt inventory & first-run closure plan 证据（V2 pairing E4 = VERIFIED/CLOSED；V2 pairing/crypto native 与 V3 逐字节 IDENTICAL 10/10；V2 E1 pairing-discovery / E2 Accessibility / E3 auto-WD mechanism = VERIFIED；V3 缺口：pairing parity / pairing-discovery / Accessibility / WRITE_SECURE_SETTINGS / auto-WD；reconnect migration debt = 0） | `docs/audit/AT.V3-DEBT-0_V2_Verified_Debt_Inventory_and_First_Run_Closure_Plan.md` |
| AT.V3-DEBT-1 V2 verified ADB capability parity closure 证据（pairing source 10/10 IDENTICAL / pairing_selftest 54/54 / E1 pairing discovery _adb-tls-pairing + lifecycle / E4 fresh identity→pair→trust→connectAuto→uid=2000 + identity continuity + restart reuse / E2 Accessibility code com.android.settings:id/pairing_code / E3 WRITE_SECURE_SETTINGS grant + app-process adb_wifi_enabled 0/1 + post-enable connectAuto / 回归 Local 11 + App 16 + Headless 1 + ADB 25 + Parity 34 + Runtime 12 + AT 6 → V2_VERIFIED_MIGRATION_DEBT=0） | `docs/evidence/at_v3_debt_1/EVIDENCE.md` |
| AT.TERM-0 shell-independent AT Engine validation 证据（:at-shellless-test-host 物理隔离 Gradle/dex/native ABSENT local/adb/app + shell 字符串 ABSENT / shellless native probe fork-without-exec（/proc EXE=app_process64 非 sh）/ 真机 G2-G19 15/15 PASS（real PTY isatty=1 / 33x99 / PING-PONG / UTF-8 / resize 41x123 / 0x03→SIGINT / EXIT 7 / close / pull+push / 3-session isolation / 20 sequential / reopen / failure）/ 回归 Local 11+App 16+Headless 1+ADB 25+Parity 34+Runtime 12+Pairing 54+AT 6 → AT_ENGINE_SHELL_SEMANTIC_INDEPENDENCE=VERIFIED） | `docs/evidence/at_term_0/EVIDENCE.md` |
| AT.HUMAN-ADB-0 Human ADB Terminal + Common AT Consumer Path 证据（Scope Recovery：HONOR pairing UX 5 种实验移除 / PairActivity 普通 Activity 配对码输入 / AppRuntime 单 AdbRuntime owner + Local+ADB ATEngine / Human path id→uid=2000(shell) / Debug path 无 UI/IME/uiautomator 结果来自 AT session output / Human-Debug parity PASS / identity 持久化 + process restart PASS 无需 re-pair / AdbHumanPreview 6/6 + CommonConsumer 3/3 + ProgrammaticAdbConsumer 7/7 / 回归 Local 11+App 7+AT 6+Shellless 15+Pairing 54 / raw terminal 无 emulator 无 CLI bridge 无 First-run 无 AndroidAgent） | `docs/evidence/at_human_adb_0/EVIDENCE.md` |
| AT.DEBUG-0 UI-visible Programmatic Terminal Bridge 证据（:app host 层 SharedSessionHandle 单一 session listener + serialized write + 双向 fan-out / AppSessionRegistry pageId→handle / DebugBridge debug-only loopback 127.0.0.1:45679 TCP JSONL + BuildConfig.DEBUG gate / PC client adb forward stdlib-only / Human 已打开 TerminalSession 被 Debug attach 不创建第二 session / 真机 B1-B14 13/13 PASS（attach 既有 session / DEBUG_INPUT_VISIBLE_IN_UI / SHARED_OUTPUT_FANOUT / SHARED_CWD / SHARED_ENV / SHARED_CTRL_C / HUMAN_INPUT_VISIBLE_TO_DEBUG / MULTI_SESSION_TARGET_ISOLATION / DISCONNECT_SURVIVES / REATTACH / HUMAN_CLOSE_PROPAGATES / CANNOT_RESURRECT / ADB attach）/ 回归 Local 11+App 7+AT 6+Shellless 15+Pairing 54+Common 3+HumanPreview 6+Programmatic 7 / Release isolation PASS debug PRESENT release ABSENT / AT Engine 0 改动 / ADB Engine 0 改动 / 非 AG protocol 非 AT.CLI → PROGRAMMATIC_HUMAN_SHARED_SESSION=VERIFIED，HUMAN_AGENT_SHARED_SESSION=DEFERRED） | `docs/evidence/at_debug_0/EVIDENCE.md` |
| AT.CLI-0 SELinux EXEC blocker 证据（shell-hosted `adb` CLI 入口恢复：real Local PTY `/system/bin/sh` = `untrusted_app` 域 / private `files/bin/adb`（`app_data_file`）在该域 `exec` → **RC=126 Permission denied** → PRIVATE_ELF_ENTRYPOINT=REJECTED / `runas_app` 域同 ELF PASS 但不代表真实 Local PTY / c8-c12 旧 PASS 因 ActivityScenario `onActivity` 对第 2+ 测试 no-op + 误用 listener 激活下返回 null 的 pull `read()` → 断言从未执行 → **C8_C12_PREVIOUS_RESULT=INVALIDATED**，TEST_HARNESS_BUG=VERIFIED / AT Engine 0 改动 / ADB Engine 0 改动） | `docs/evidence/at_cli_0/SELINUX_EXEC_BLOCKER.md` |
| AT.CLI-0 app_process entrypoint probe 证据（`/system/bin/app_process`（`zygote_exec`）真实 Local PTY **P1-P6 6/6 PASS**：P1 exec / P2 加载 APK 内 CliProbe main class / P3 argv 传递 / P4 继承 Local PTY stdin/stdout/stderr / P5 连既有 Product CLI Bridge（同 UID UDS，HELLO/ACK type=2 ver=1）/ P6 正常返回 Local shell TTY intact → **SELECTED_ENTRYPOINT=APP_PROCESS** / Product CLI Bridge 复用 EXISTING UDS + protocol v1 / AT Engine 0 改动 / ADB Engine 0 改动 / shell-hosted `adb shell` 完整能力仍 **NOT_YET_VERIFIED**） | `docs/evidence/at_cli_0/APP_PROCESS_ENTRYPOINT_PROBE.md` |

## V2 Evidence 索引（READ-ONLY 来源）

| Evidence | V2 路径 |
|---|---|
| Wireless pairing 原语（VERIFIED） | `AndroidTerminal-V2/docs/implementation/v2/AT.C-1_Core_Pairing_Primitive.md` |
| First-run wireless bootstrap（E1 pairing-discovery / E2 Accessibility / E3 auto-WD mechanism VERIFIED） | `AndroidTerminal-V2/docs/AT_FIRST_RUN_WIRELESS_BOOTSTRAP_AUDIT.md` |
| Local one-shot（VERIFIED） | `.../ATsh.T0_Local_Shell_OneShot.md` |
| Local 能力边界 / MODEL_2（VERIFIED） | `.../ATsh.T0.5_Local_Shell_Capability_Boundary_Audit.md` |
| Local PTY persistent shell（VERIFIED） | `.../ATsh.T1_Local_PTY_Feasibility_Proof.md` |
| ADB remote PTY / interactive stream（VERIFIED） | `.../ADB.I0_Interactive_Shell_Remote_PTY_Feasibility_Proof.md` |
| Terminal Execution 架构综合（COMPLETE） | `AndroidTerminal-V2/docs/architecture/TERM.A0_Terminal_Execution_Architecture_Synthesis.md` |
| AT Engine 五层边界审计（COMPLETE） | `AndroidTerminal-V2/docs/architecture/TERM.A0.5_AT_Engine_Architecture_Synthesis.md` |
| AT Runtime slices / AG handoff | `.../docs/implementation/v2/AT.RUNTIME-2*.md`、`AT.ENGINE-AG0_*.md` |

## 术语注意（Canonical V1 §6）

- 历史"AT Engine"（= AT Runtime + AT Core）按当时语义理解；
- 不自动等同 Canonical AT Engine（one-shot + persistent terminal + emulator + screen state + backend abstraction）。