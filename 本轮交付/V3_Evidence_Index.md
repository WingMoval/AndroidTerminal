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