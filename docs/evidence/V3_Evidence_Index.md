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

## V2 Evidence 索引（READ-ONLY 来源）

| Evidence | V2 路径 |
|---|---|
| Wireless pairing 原语（VERIFIED） | `AndroidTerminal-V2/docs/implementation/v2/AT.C-1_Core_Pairing_Primitive.md` |
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