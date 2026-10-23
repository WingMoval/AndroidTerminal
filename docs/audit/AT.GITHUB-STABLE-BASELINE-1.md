# AT.GITHUB-STABLE-BASELINE-1 — V4 稳定依赖恢复与 GitHub 文档归一化

**Date:** 2026-10-23
**Nature:** DEPENDENCY RESTORATION + DOC NORMALIZATION
**Decision:** Option A — 恢复旧版 BoringSSL 到 master (Controller 确认)

---

## 最终报告

```text
STEP = AT.GITHUB-STABLE-BASELINE-1

VERDICT = PASS

AT_START_HEAD = 15394b7bd0e65f414d5af6aeca1035dd87d5cfc6
AT_FINAL_HEAD = (see commits below)
AT_BRANCH = master
AT_WORKTREE = CLEAN (committed)

GATE_A_BASELINE = PASS
GATE_B_DEPENDENCY_RESTORATION = PASS
GATE_C_LICENSE = PASS
GATE_D_README = PASS
GATE_E_BUILD = PASS
GATE_F_FREEZE_PROTECTION = PASS

OLD_BORINGSSL_RESTORED = YES
OLD_BORINGSSL_SHA256_MATCH = YES (602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2)
BORINGSSL_HEADERS_MATCH_FREEZE = YES (98 headers from freeze tag)
BORINGSSL_LINKAGE = OLD_ONLY (libboringssl.a, no libcrypto.a/libssl.a)

CURRENT_BORINGSSL_VERSION = UNKNOWN (pre-2025-02, restored from freeze)
CURRENT_BORINGSSL_LICENSE = OpenSSL BSD + SSLeay BSD + ISC + MIT

README_VERSION = V4
README_ARCHITECTURE_ACCURATE = YES

NATIVE_BUILD = BUILD SUCCESSFUL (15s)
ANDROID_APP_BUILD = BUILD SUCCESSFUL (18s)
NON_DEVICE_TESTS = 106 passing, 0 failing
CONSUMER_ARTIFACT_BUILD = BUILD SUCCESSFUL

CURRENT_HEAD_FULL_DEVICE_PARITY = NOT_RUN

FREEZE_TAG_UNCHANGED = YES (7216d0e)
FREEZE_BUNDLE_UNCHANGED = YES (SHA256 verified)
AG_MODIFIED = NO

PRODUCTION_FILES_CHANGED = 0 (CMakeLists restored to freeze state)
BUILD_FILES_CHANGED = 1 (CMakeLists.txt — restored to freeze)
DOCUMENT_FILES_CHANGED = 5 (NOTICE, THIRD_PARTY_NOTICES, README, BoringSSL LICENSE, audit doc)

COMMIT_1 = (dependency restoration)
COMMIT_2 = (doc normalization)

PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDATION = PII 脱敏 + GitHub 仓库创建 + 首次推送
STOP = YES
```

---

## Gate A — 基线核验

| 项 | 值 |
|----|-----|
| HEAD | `15394b7` ✓ |
| 冻结标签 | `7216d0e` ✓ |
| 升级 commit | `d1e5883` (116 files in boringssl/ + CMakeLists) |
| 升级后额外修改 | 无 ✓ |
| 工作树遗留 | `THIRD_PARTY_NOTICES.md` 统计修正（合法输入） |

**GATE_A = PASS**

## Gate B — 依赖恢复

| 操作 | 结果 |
|------|------|
| 删除 libcrypto.a (30.5 MB) | ✓ |
| 删除 libssl.a (11.7 MB) | ✓ |
| 删除新 include/ (106 headers) | ✓ |
| 恢复旧 include/ 从冻结标签 (98 headers) | ✓ |
| 恢复 libboringssl.a 从冻结标签 | ✓ SHA256 匹配 |
| 恢复 CMakeLists.txt 从冻结标签 | ✓ |
| 删除 UPSTREAM_VERSION.md (新版专用) | ✓ |
| 替换 LICENSE 为历史版本 (pre-2025-02) | ✓ |

**GATE_B = PASS**

## Gate C — 许可证归一化

| 文件 | 变更 |
|------|------|
| `NOTICE` | 重写：单一版本描述（旧版 BoringSSL），移除"Current/Historical"双版本 |
| `THIRD_PARTY_NOTICES.md` | 重写：单一 BoringSSL 节 + 历史升级注释 |
| `adb-engine/.../boringssl/LICENSE` | 替换为 pre-2025-02 官方许可证 |
| `adb-engine/.../boringssl/UPSTREAM_VERSION.md` | 删除（新版专用） |

**GATE_C = PASS**

## Gate D — README

| 项 | 旧值 | 新值 |
|----|------|------|
| 标题 | "AndroidTerminal-V3" | "AndroidTerminal" |
| 定位 | V3 Canonical Production Mainline | Android Execution Subsystem |
| 架构 | V3 时代 | V4 模块表 |
| 版本状态 | 无 | V4 freeze + BoringSSL 说明 |
| 构建 | 无 | Gradle 命令 + 前提 |
| License | 无 | LICENSE + NOTICE + THIRD_PARTY + legal/ |
| AG 集成 | 无 | 消费者边界说明 |

**GATE_D = PASS**

## Gate E — 构建回归

| 项 | 结果 |
|----|------|
| Native build | BUILD SUCCESSFUL (15s, 1 pre-existing warning) |
| Full APK | BUILD SUCCESSFUL (18s) |
| Non-device tests | 106 passing, 0 failing |
| libadbd_engine.so SPAKE2 | 4 symbols present (BoringSSL linked) |
| Device test | NOT_RUN |

**GATE_E = PASS**

## Gate F — 冻结保护

| 项 | 结果 |
|----|------|
| 冻结标签 | `7216d0e` (未变) |
| 冻结 AAR SHA256 | 匹配 |
| AG | 未修改 |

**GATE_F = PASS**

---

## 提交

### Commit 1: 依赖恢复

```text
AT.GITHUB-STABLE-BASELINE-1: restore V4 BoringSSL dependency
```

文件：
- `adb-engine/src/main/cpp/third_party/boringssl/` (98 headers + libboringssl.a + LICENSE)
- `adb-engine/src/main/cpp/CMakeLists.txt`

### Commit 2: 文档归一化

```text
AT.GITHUB-STABLE-BASELINE-1: normalize V4 README and license notices
```

文件：
- `README.md`
- `NOTICE`
- `THIRD_PARTY_NOTICES.md`
- `docs/audit/AT.GITHUB-STABLE-BASELINE-1.md`
- `docs/audit/AT.GITHUB-PUBLICATION-READINESS-1.md`

**STOP.**
