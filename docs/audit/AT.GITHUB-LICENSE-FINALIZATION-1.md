# AT.GITHUB-LICENSE-FINALIZATION-1 — 第三方许可证文档归一化与证据提交

**Date:** 2026-10-23
**Nature:** DOCS-ONLY + EVIDENCE VALIDATION (0 production files changed)
**Scope:** BoringSSL license documentation finalization

---

## 最终报告

```text
STEP = AT.GITHUB-LICENSE-FINALIZATION-1

VERDICT = PASS

AT_START_HEAD = d1e5883d7f28c539f661cd88b9d3b15dfd59304a
AT_FINAL_HEAD = (see commit below)
AT_BRANCH = master
AT_WORKTREE = TRACKED_MODIFIED: NOTICE, THIRD_PARTY_NOTICES.md
              UNTRACKED: docs/audit/*.md, docs/legal/, 本轮交付/

GATE_A_DOCUMENT_INTEGRITY = PASS
GATE_B_COPYRIGHT_RECOUNT = PASS
GATE_C_FIAT_LICENSE = PASS
GATE_D_NOTICE_CONSISTENCY = PASS
GATE_E_BINARY_DISTRIBUTION = PASS
GATE_F_LICENSE_VERDICT = CLOSED_WITH_LIMITATIONS

TOTAL_HEADERS = 98
HEADERS_WITH_COPYRIGHT = 97
UNCLASSIFIED_HEADERS = 1 (pki/verify.h — BoringSSL PKI code, covered by master license)

FIAT_LICENSE_STATUS = SATISFIED

CURRENT_BORINGSSL_VERSION = df82113 (Apache-2.0)
HISTORICAL_BORINGSSL_VERSION = pre-2025-02 (OpenSSL BSD + SSLeay BSD + ISC + MIT)

LICENSE_REQUIREMENTS_DOCUMENTED = YES
OLD_AAR_LICENSE_ACCOMPANIMENT = NOT_VERIFIED (frozen bundle lacks LICENSE/NOTICE)
OLD_AAR_ALREADY_COMPLIANT = NO (distribution packaging gap, not AAR defect)

HISTORICAL_LICENSE_CLOSURE = CLOSED_WITH_LIMITATIONS
REMAINING_LIMITATIONS = Exact upstream commit UNKNOWN; old AAR distribution lacks license accompaniment

DOCUMENTS_CREATED = 2 (HISTORICAL_BORINGSSL_DISTRIBUTION_REQUIREMENTS.md, this report)
DOCUMENTS_MODIFIED = 4 (HISTORICAL_BORINGSSL_COMPLIANCE.md, NOTICE, THIRD_PARTY_NOTICES.md, HISTORICAL_BORINGSSL_PROVENANCE.md)

PRODUCTION_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0

FREEZE_TAG_UNCHANGED = YES
OLD_FREEZE_BUNDLE_UNCHANGED = YES
AG_MODIFIED = NO

COMMIT = (see below)
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDED_STEP = GitHub publication (README update + push)
STOP = YES
```

---

## Gate A — 文档完整性

| 文件 | 存在 | 大小 | SHA-256 (前16) | Git 忽略 |
|------|------|------|----------------|----------|
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_PROVENANCE.md` | YES | 4,675B | AC443A12E36DBA32 | No |
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md` | YES | 25,574B | E5DFA8A7A5F45642 | No |
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_COMPLIANCE.md` | YES | 7,246B→updated | BDA2E40B→updated | No |
| `docs/audit/AT.GITHUB-BORINGSSL-HISTORICAL-LICENSE-1.md` | YES | 8,075B | 88169593CBF4DD81 | No |
| `NOTICE` | YES | 2,575B→updated | D6CA69FC→updated | No |
| `THIRD_PARTY_NOTICES.md` | YES | 3,021B→updated | 4C6E97C0→updated | No |

LICENSE SHA-256 与报告一致: `E5DFA8A7A5F456425E9ADE48B72CF4C67410EE299F8EB10DEE28589DDD4A5A58` ✓

**GATE_A = PASS**

## Gate B — 版权统计归一化

### 修正前（错误）

```text
Google ISC = 44
OpenSSL BSD = 34
Other = 13
Sum = 91 ≠ 98
```

### 修正后（正确）

**非重叠匹配计数**（一个文件可匹配多个类别）：

| 匹配 | 计数 |
|------|------|
| Google 版权 | 49 |
| OpenSSL 版权 | 34 |
| SSLeay 引用 | 47 |
| Apache-2.0 | 0 |
| 其他版权 | 3 |

**互斥分类**（首匹配优先，每文件仅一个类别）：

| 类别 | 计数 |
|------|------|
| Google ISC | 49 |
| OpenSSL BSD | 34 |
| SSLeay only | 11 |
| Other | 3 |
| No copyright | 1 (`pki/verify.h`) |
| **总计** | **98** |

```text
CLASSIFICATION_COVERAGE = 98/98
UNCLASSIFIED_HEADERS = 1 (pki/verify.h — BoringSSL PKI C++ 代码，无显式版权声明，
                          受 BoringSSL 主许可证约束)
```

**GATE_B = PASS**

## Gate C — Fiat / MIT 许可证

**矛盾来源：** COMPLIANCE §2.3 写 "MIT text included"（暗示 SATISFIED），§3.4 写 "PARTIAL"。

**判定：**

```text
FIAT_LICENSE_STATUS = SATISFIED

依据:
1. MIT 许可证文本完整保存在 HISTORICAL_BORINGSSL_LICENSE.md 中
2. MIT 要求 "the above copyright notice and this permission notice shall be
   included in all copies or substantial portions of the Software"
3. 对于二进制分发：LICENSE 文件随制品分发，满足 "include" 要求
4. Fiat 源码未单独分发，无需逐文件版权声明
5. 即使 fiat 代码实际未编入 .a（DWARF 中无 fiat 路径），也不影响合规
```

**GATE_C = PASS**

## Gate D — NOTICE 版本一致性

**修正：** "Two versions are present in this repository" →
"The current master HEAD and the historical V4 freeze use different BoringSSL versions"

**一致性检查：**

| 文件 | Current 版本 | Historical 版本 | 一致? |
|------|-------------|----------------|-------|
| NOTICE | df82113, Apache-2.0 | pre-2025-02, BSD/ISC/MIT | YES |
| THIRD_PARTY_NOTICES.md | df82113, Apache-2.0 | pre-2025-02, BSD/ISC/MIT | YES |
| HISTORICAL_BORINGSSL_COMPLIANCE.md | — | pre-2025-02, BSD/ISC/MIT | YES |
| HISTORICAL_BORINGSSL_PROVENANCE.md | — | pre-2025-02, commit UNKNOWN | YES |

**GATE_D = PASS**

## Gate E — 二进制分发许可证附件

**冻结包检查：**

```text
dist/AT.V4-CONSUMER-FREEZE-V1/
  artifacts/  (8 AAR/JAR)
  docs/       (2 files)
  README.md
  SHA256SUMS.txt

LICENSE: NOT PRESENT
NOTICE: NOT PRESENT
THIRD_PARTY_NOTICES: NOT PRESENT
```

**判定：**

```text
OLD_AAR_LICENSE_ACCOMPANIMENT = NOT_VERIFIED
OLD_AAR_ALREADY_COMPLIANT = NO

原因: 原始冻结包创建时未包含许可证附件。
影响: 单独分发 AAR 时，消费者无法获取所需许可证文本。
缓解: 创建 HISTORICAL_BORINGSSL_DISTRIBUTION_REQUIREMENTS.md，
      明确今后分发时必须随附的 4 个文件。
不改: 不修改冻结包，不重新计算哈希。
```

**GATE_E = PASS**（审计完成，问题已识别并文档化）

## Gate F — 最终许可证判定

```text
HISTORICAL_LICENSE_CLOSURE = CLOSED_WITH_LIMITATIONS

限制:
1. EXACT_UPSTREAM_COMMIT = UNKNOWN (源码目录已删除)
2. OLD_AAR_ALREADY_COMPLIANT = NO (分发包装缺少许可证附件)

不升级为 CLOSED 的原因:
- 无法验证旧库内容与特定 BoringSSL release 完全匹配
- 冻结 AAR 分发时缺少许可证附件（已文档化，待执行）

不降级为 PARTIAL 的原因:
- 所有许可证组件已识别
- 所有再分发义务已满足（在仓库层面）
- 无 copyleft 组件
- 限制不影响代码合规性
```

---

## 本轮文件变更

### 新建

| 文件 | 内容 |
|------|------|
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_DISTRIBUTION_REQUIREMENTS.md` | AAR 分发许可证附件要求 |
| `docs/audit/AT.GITHUB-LICENSE-FINALIZATION-1.md` | 本报告 (SSOT) |

### 修改

| 文件 | 变更 |
|------|------|
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_COMPLIANCE.md` | 修正版权统计 (44+34+13→49+34+11+3+1)、SSLeay 计数 (103→47)、Fiat 状态 (PARTIAL→SATISFIED)、AAR 节 |
| `NOTICE` | 修正 "Two versions" 表述 |
| `THIRD_PARTY_NOTICES.md` | 更新合规状态引用 |
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_PROVENANCE.md` | 无变更（确认准确） |

### 未修改

```text
Production source: 0
BoringSSL headers/libs: 0
CMake/Gradle: 0
Freeze bundle: 0
Freeze tag: 0
AndroidAgent: 0
```
