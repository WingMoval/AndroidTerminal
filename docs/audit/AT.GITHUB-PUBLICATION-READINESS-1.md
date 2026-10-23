# AT.GITHUB-PUBLICATION-READINESS-1 �?GitHub 发布前审计与 BoringSSL 版本策略冻结

**Date:** 2026-10-23
**Nature:** READ-ONLY + DOCS-ONLY (1 minor doc fix; 0 production changes)
**Scope:** Publication readiness + BoringSSL version strategy

---

## 最终报�?
```text
STEP = AT.GITHUB-PUBLICATION-READINESS-1

VERDICT = PARTIAL

AT_START_HEAD = 15394b7bd0e65f414d5af6aeca1035dd87d5cfc6
AT_FINAL_HEAD = 15394b7bd0e65f414d5af6aeca1035dd87d5cfc6 (1 uncommitted doc fix)
AT_BRANCH = master
AT_WORKTREE = 1 modified (THIRD_PARTY_NOTICES.md stat fix) + untracked docs

CURRENT_MASTER_BORINGSSL = df82113 (libcrypto.a + libssl.a, Apache-2.0)
STABLE_FREEZE_BORINGSSL = UNKNOWN (libboringssl.a, SHA 602A6D6C..., pre-2025-02)
BORINGSSL_UPGRADE_ACTIVE_IN_MASTER = YES

REMOTE_MASTER_HEAD = 75d5832 (origin/master = V3 baseline)
UNPUSHED_COMMITS = 24 (V4 architecture + license docs)

GATE_A_VERSION_IDENTITY = PASS
GATE_B_GIT_SECURITY = PASS
GATE_C_LICENSE = PASS (after minor stat fix in THIRD_PARTY_NOTICES.md)
GATE_D_REVERT_DECISION = PASS
GATE_E_BINARY_DISTRIBUTION = PASS
GATE_F_README = FAIL (README is V3-era, title "AndroidTerminal-V3")

REVERT_RECOMMENDED = YES
REVERT_REASON = Master should match V4 freeze (proven BoringSSL). New version is candidate-only.

AG_INTEGRATION_CHANGE_REQUIRED = NO

FROZEN_BUNDLE_UNCHANGED = YES
FREEZE_TAG_UNCHANGED = YES

PRODUCTION_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0

PUBLICATION_READINESS = BLOCKED
PUBLICATION_BLOCKERS =
  1. README is V3-era (title "AndroidTerminal-V3", V3 architecture)
  2. BoringSSL version strategy not finalized (master has unvalidated new version)
  3. Historical PII (hostname developer@HOST, IP <LAN_IP>) in docs

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDATION = See §7
STOP = YES
```

---

## Gate A �?BoringSSL 版本确认

### Master (15394b7)

| �?| �?|
|----|-----|
| CMakeLists | Links `libcrypto.a` + `libssl.a` |
| Comment | "Upstream: https://github.com/google/boringssl commit df82113 (2026-10-22)" |
| libcrypto.a | 30,514,318 B, SHA256: `4CF2E4FA...` |
| libssl.a | 11,658,154 B, SHA256: `8E684C63...` |
| 许可�?| Apache-2.0 |
| 真机验证 | **NOT DONE** |

### Freeze (7216d0e)

| �?| �?|
|----|-----|
| CMakeLists | Links `libboringssl.a` |
| Comment | "BoringSSL vendored static library + headers (migrated from V2)" |
| libboringssl.a | 4,343,398 B, SHA256: `602A6D6C...` (verified via V1 original) |
| 许可�?| pre-2025-02 (OpenSSL BSD + SSLeay BSD + ISC + MIT) |
| 真机验证 | **DONE** (V4 device regression) |

```text
BORINGSSL_UPGRADE_ACTIVE_IN_MASTER = YES
```

**GATE_A = PASS**

## Gate B �?Git 安全扫描

| �?| 结果 |
|----|------|
| 本地 master | `15394b7` |
| origin/master | `75d5832` (V3 baseline) |
| 未推送提�?| **24** (V4 architecture + license docs) |
| Keystore 文件 | 0 |
| local.properties 跟踪 | NO |
| 私钥 (BEGIN ... PRIVATE KEY) | 0 |
| GCP API Key (AIza...) | 0 |
| GitHub Token (ghp_...) | 0 |
| AWS Key (AKIA...) | 0 |
| **SECRET_SCAN** | **PASS** |

**注意�?* 未推送的 24 个提交包含完�?V4 架构 + 许可证文档。推送到 GitHub 后全部公开�?
**GATE_B = PASS**

## Gate C �?许可证一致�?
### 修正

| 文件 | 旧�?| 新�?|
|------|------|------|
| THIRD_PARTY_NOTICES.md L27 | "44 Google ISC, 34 OpenSSL BSD, 13 other" | "49 Google ISC, 34 OpenSSL BSD, 11 SSLeay, 3 other, 1 no notice" |
| THIRD_PARTY_NOTICES.md L46 | "98/98 headers... 50 BSD/ISC, 103 SSLeay" | "97/98 headers... 49 Google ISC, 34 OpenSSL BSD, 47 SSLeay" |

### 验证

| 指标 | �?|
|------|-----|
| TOTAL_HEADERS | 98 |
| HEADERS_WITH_COPYRIGHT | 97 |
| NO_COPYRIGHT_HEADER | `pki/verify.h` |
| SSLEAY_HEADERS | 47 |
| FIAT_LICENSE_STATUS | SATISFIED |

**GATE_C = PASS**（修正后�?
## Gate D �?BoringSSL 版本策略

### 三方案比�?
| 维度 | A: 回退旧版 | B: 保留新版 | C: 双轨 |
|------|-----------|-----------|---------|
| **STABILITY** | �?master = 已验�?| �?master = 未验�?| �?冻结�?= 已验�?|
| **COMPATIBILITY** | �?�?AG 一�?| �?AG 需重新验证 | �?AG 用冻结版 |
| **LICENSE** | �?pre-2025-02 已闭�?| �?Apache-2.0 | �?两者都已闭�?|
| **SECURITY** | �?已验�?| ⚠️ 未验�?| �?冻结版已验证 |
| **MAINTENANCE** | �?无额外工�?| ⚠️ 需真机验证 | ⚠️ 需维护两版本文�?|
| **AG_RISK** | �?零风�?| �?需 AG 重新集成 | �?AG 用冻结版 |
| **GITHUB** | �?发布即稳�?| ⚠️ 发布含未验证代码 | �?冻结版可发布 |

### 推荐

```text
REVERT_RECOMMENDED = YES
REVERT_REASON = 
  master 应代表已验证的稳定版本�?  新版 BoringSSL (df82113) 仅完成构建验证，未真机验证�?  发布未验证的加密库到 GitHub 主分支不符合"Verified migration, not rewrite"原则�?  回退�?master = V4 冻结�?BoringSSL = AG 消费�?= 真机验证版�?
REVERT_SCOPE = 
  1 �?commit: 恢复 libboringssl.a + �?CMakeLists + 旧头文件
  不影响冻结标签、冻结包、AG

NEW_DEVICE_VALIDATION_REQUIRED = NO (旧版已验�?
```

### 回退操作（未来执行，非本轮）

```bash
# 从冻结标签恢复旧 BoringSSL
git checkout AT.V4-CONSUMER-FREEZE-V1 -- \
  adb-engine/src/main/cpp/third_party/boringssl/ \
  adb-engine/src/main/cpp/CMakeLists.txt
git commit -m "Revert BoringSSL to V4 freeze version (proven, device-verified)"
```

新版 BoringSSL (df82113) 保留�?`d1e5883` 提交中，作为未来候选�?
## Gate E �?冻结包分�?
```text
FROZEN_AAR_UNCHANGED = YES (SHA256 verified)
DISTRIBUTION_LICENSE_GAP = YES (no LICENSE/NOTICE in bundle)
```

### 建议发布包结�?
```text
dist/AT.V4-PUBLICATION-1/           �?新目录（不修改原冻结包）
  artifacts/                         �?从原冻结包复制（哈希不变�?    adb-engine-debug.aar
    at-backend-adb-debug.aar
    at-backend-local-debug.aar
    at-cli-debug.aar
    at-composition-android-debug.aar
    at-engine-api.jar
    at-engine-debug.aar
    at-engine-spi.jar
  LICENSE                             �?从仓库根目录复制
  NOTICE                              �?从仓库根目录复制
  THIRD_PARTY_NOTICES.md              �?从仓库根目录复制
  HISTORICAL_BORINGSSL_LICENSE.md     �?�?docs/legal/boringssl/ 复制
  README.md                           �?发布说明
  SHA256SUMS.txt                      �?新清单（含许可证文件�?```

**GATE_E = PASS**

## Gate F �?README

```text
README_ACCURACY = FAIL
```

| �?| 当前 | 应为 |
|----|------|------|
| 标题 | "AndroidTerminal-V3" | "AndroidTerminal" �?"AndroidTerminal-V4" |
| 角色 | "V3 = Canonical Production Mainline" | V4 是生产主�?|
| 架构 | V3 时代描述 | V4 消费者边�?|
| BoringSSL | 未提�?| 应区分当前版和历史版 |
| License | 未提�?| 应引�?LICENSE + THIRD_PARTY_NOTICES |
| 构建方法 | 未提�?| 应包�?Gradle 命令 |

**需要更�?README，但本轮不执行（避免大范围重写）�?*

## 发布放行检�?
| 标准 | 状�?|
|------|------|
| GIT_HISTORY_AUDITED | PASS |
| SECRET_SCAN | PASS |
| LICENSE_DOCUMENTATION | PASS |
| VERSION_IDENTITY | PASS |
| FROZEN_BUNDLE_IMMUTABLE | PASS |
| README_ACCURACY | **FAIL** |
| NO_PRODUCTION_CHANGE | YES |

```text
PUBLICATION_READINESS = BLOCKED
```

### 阻塞�?
1. **README 过期** �?标题 "AndroidTerminal-V3"，内容全部是 V3 时代
2. **BoringSSL 版本策略** �?master 含未验证新版，需回退或明确标�?3. **历史 PII** �?3 �?`developer@HOST`�? �?`<LAN_IP>`（在 docs/evidence 中）

## 7. 后续步骤

1. **Controller 决策 BoringSSL 版本策略**（推�?Option A: 回退�?2. **更新 README**（V4 身份 + 架构 + License + 构建方法�?3. **PII 脱敏**�? 处主机名 + 2 �?IP�?4. **回退 BoringSSL**（如�?Option A�?5. **GitHub 仓库创建 + 首次推�?*
6. **发布包打�?*（如需要）

**STOP.**
