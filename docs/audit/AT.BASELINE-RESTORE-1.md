# AT.BASELINE-RESTORE-1 — GitHub Public Baseline 恢复与 BoringSSL Upgrade 废弃清理

**Date:** 2026-10-25
**Nature:** BASELINE RESTORATION + ABANDONED EXPERIMENT CLEANUP

---

## 最终报告

```text
STEP = AT.BASELINE-RESTORE-1

VERDICT = PASS

START_HEAD = 17fbb261412e0082ff1a7f68db8ba5ed0905e915
START_BRANCH = master

PUBLIC_REMOTE_BRANCH = github/master
PUBLIC_REMOTE_HEAD = 17fbb261412e0082ff1a7f68db8ba5ed0905e915

EXPECTED_PUBLIC_BASE = 17fbb26
GATE_A_INVENTORY = PASS
GATE_B_PUBLIC_BASELINE = PASS (local HEAD = github/master = 17fbb26)
GATE_C_UPGRADE_IDENTIFICATION = PASS (no active upgrade branch/worktree/files)
GATE_D_RESET_ELIGIBILITY = N/A (already at baseline)
GATE_E_WORKTREE_CLASSIFICATION = PASS (single worktree, clean)
GATE_F_MAINLINE_RESTORE = N/A (already at 17fbb26)
GATE_G_BRANCH_CLEANUP = N/A (no upgrade branch exists)
GATE_H_WORKTREE_CLEANUP = N/A (no upgrade worktree exists)
GATE_I_UNTRACKED_CLEANUP = N/A (no upgrade-only untracked files)
GATE_K_PRODUCTION_EQUIVALENCE = PASS (0332674→17fbb26 = docs-only)
GATE_L_BORINGSSL_BASELINE = PASS (libboringssl.a unchanged)
GATE_M_BUILD = PASS (release build + unit tests)
GATE_N_FINAL_STATE = PASS

PRODUCTION_BASE_COMMIT = 17fbb261412e0082ff1a7f68db8ba5ed0905e915
APK_VERIFIED_SOURCE = 0332674dd6d751620b191097b070f10561259417

CURRENT_HEAD = 17fbb261412e0082ff1a7f68db8ba5ed0905e915

BORINGSSL_UPGRADE_COMMITS = (in published history only: d1e5883, 1e34530)
BORINGSSL_UPGRADE_BRANCH_REMOVED = N/A (never existed as separate branch)
BORINGSSL_UPGRADE_REMOTE_BRANCH_REMOVED = N/A (never pushed)
BORINGSSL_UPGRADE_WORKTREE_REMOVED = N/A (never created)
BORINGSSL_UPGRADE_FILES_REMOVED = N/A (none present)
BORINGSSL_UPGRADE_DOCS_REMOVED = N/A (none present)

BORINGSSL_UPGRADE_ACTIVE_REFS = 0
BORINGSSL_UPGRADE_PRESENT_IN_ACTIVE_CODE = NO

CURRENT_BORINGSSL_BASELINE = v0.4.0 device-verified (libboringssl.a, 4,343,398B)

GLADB_RESEARCH_PRESERVED = YES (separate repo: D:\codebank\AndCode\AndroidTerminal\research\GLADB)

PRODUCTION_FILES_CHANGED = 0

BUILD_NATIVE = PASS
BUILD_APP = PASS
TESTS = PASS

V040_TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
FREEZE_TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
V040_TAG_UNCHANGED = YES
FREEZE_TAG_UNCHANGED = YES
GITHUB_RELEASE_UNCHANGED = YES

AUDIT_COMMIT = (this file, committed as docs-only)
PUSH = YES

BASELINE_RESTORED = YES

NEXT = AT.RESEARCH-LOCAL-TCP-1

STOP = YES
```

---

## BASELINE POLICY

```text
AndroidTerminal development has been restored to the
published GitHub v0.4.0 stable production baseline.

PUBLIC_BASE_COMMIT = 17fbb261412e0082ff1a7f68db8ba5ed0905e915
APK_VERIFIED_SOURCE = 0332674dd6d751620b191097b070f10561259417
SDK_FREEZE_SOURCE = 7216d0e8131e92e8914ce7c8443e1668b1d53aca

The later BoringSSL upgrade experiment is ABANDONED.

It is NOT:
- an active branch
- an active worktree
- a supported baseline
- a roadmap item
- a dependency candidate
- a source for future implementation

Do not restore, cherry-pick, merge, reapply, or use the
abandoned BoringSSL upgrade unless explicitly reopened
by Controller.

CURRENT CRYPTO BASELINE = v0.4.0 device-verified BoringSSL dependency
(libboringssl.a, arm64-v8a, pre-2025-02 upstream)
```

---

## Findings

### BoringSSL "Upgrade" 实际状态

所谓 "BoringSSL upgrade" 并非独立实验分支，而是已发布历史中的两个 commit：

```text
d1e5883 AT.GITHUB-BORINGSSL-UPDATE-0: upgrade BoringSSL to upstream commit df82113
1e34530 AT.GITHUB-STABLE-BASELINE-1: restore V4 BoringSSL dependency
```

`d1e5883` 升级了 BoringSSL，`1e34530` **恢复了原始 V4 冻结版本**。
当前基线 `17fbb26` 包含的是**恢复后的原始 BoringSSL**（设备验证版本）。

不存在：
- 独立 upgrade branch
- 独立 worktree
- 未提交的 upgrade 文件
- 远端 upgrade branch
- 活跃的 upgrade 文档

### 工作树状态

```text
HEAD = 17fbb26 (= github/master)
Branch: master (single)
Worktree: single (main)
Tags: v0.4.0 → 7216d0e, AT.V4-CONSUMER-FREEZE-V1 → 7216d0e
Untracked: 本轮交付/, docs/audit/*, dist/AT.V4-PUBLICATION-1/ (合法发布产物)
```

### 下一步方向（不实施）

```text
AT.RESEARCH-LOCAL-TCP-1

Public v0.4.0 baseline
↓
existing Wireless ADB ONLINE
↓
ADB service tcpip:<port>
↓
adbd local TCP listener
↓
127.0.0.1:<port>
↓
ADB RSA
↓
No-WiFi steady-state transport
```

**STOP.**
