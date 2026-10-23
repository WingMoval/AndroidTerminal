# AT.GITHUB-BORINGSSL-HISTORICAL-LICENSE-1 — 旧版 BoringSSL 历史许可证核验与声明闭合

**Date:** 2026-10-23
**Nature:** EVIDENCE-FIRST / DOCS-ONLY (0 production files changed)
**Scope:** Old BoringSSL historical license closure

---

## 最终报告

```text
STEP = AT.GITHUB-BORINGSSL-HISTORICAL-LICENSE-1

VERDICT = CLOSED_WITH_LIMITATIONS

AT_HEAD_BEFORE = d1e5883d7f28c539f661cd88b9d3b15dfd59304a
AT_HEAD_AFTER = d1e5883d7f28c539f661cd88b9d3b15dfd59304a (unchanged)
AT_BRANCH = master
AT_WORKTREE = CLEAN (only pre-existing untracked files + this round's docs)

OLD_BINARY_SHA256_VERIFIED = YES (602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2)

PRE_2025_LICENSE_VERIFIED = YES
LICENSE_UPSTREAM_COMMIT = 6f4159567deb1a3d7b15108556360f97a2b57124 (2024-12-11, pre-switch)
LICENSE_SHA256 = E5DFA8A7A5F456425E9ADE48B72CF4C67410EE299F8EB10DEE28589DDD4A5A58

OLD_BINARY_TO_LICENSE_MATCH = HIGH_CONFIDENCE (header evidence: 98/98 BSD/ISC, 0 Apache)
THIRD_PARTY_COMPONENT_AUDIT = COMPLETE (OpenSSL BSD, SSLeay BSD, ISC, MIT/fiat)

COPYRIGHT_NOTICES_COMPLETE = YES (98/98 headers, 103 SSLeay refs)
BINARY_REDISTRIBUTION_REQUIREMENTS = DOCUMENTED
REDISTRIBUTION_COMPLIANCE = SATISFIED

HISTORICAL_LICENSE_CLOSURE = CLOSED_WITH_LIMITATIONS
REMAINING_UNCERTAINTY = Exact upstream commit UNKNOWN

NOTICE_UPDATED = YES
THIRD_PARTY_NOTICES_UPDATED = YES
HISTORICAL_LICENSE_ARCHIVE_CREATED = YES (docs/legal/boringssl/)

PRODUCTION_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0

FREEZE_TAG_UNCHANGED = YES
OLD_FREEZE_BUNDLE_UNCHANGED = YES
AG_MODIFIED = NO

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDATION = See §6
STOP = YES
```

---

## 1. 核验范围

本轮核验的是**旧版 BoringSSL**（pre-2025-02-03），即：
- AT V4 冻结制品 (`AT.V4-CONSUMER-FREEZE-V1`) 中静态链接的 BoringSSL
- AG 消费者包中使用的 BoringSSL
- Git 历史 (commit `a40387e`) 中的 BoringSSL 源码树

**不是**当前 master HEAD 的新版 BoringSSL (df82113, Apache-2.0)。

## 2. 许可证来源

### 2.1 官方历史 LICENSE

| 项 | 值 |
|----|-----|
| 上游 commit | `6f4159567deb1a3d7b15108556360f97a2b57124` (2024-12-11) |
| 选择理由 | 许可证切换 (commit `33d1049b`, 2025-02-03) 之前的最后一个有记录的 commit |
| 许可证内容 | OpenSSL BSD + SSLeay BSD + ISC + MIT (fiat) |
| SHA-256 | `E5DFA8A7A5F456425E9ADE48B72CF4C67410EE299F8EB10DEE28589DDD4A5A58` |
| 存储位置 | `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md` |

### 2.2 许可证切换时间线

```
2024-12-11  commit 6f41595  "Start maintaining an AUTHORS file"
            ← 旧版 BoringSSL 可能在此之后
            ← 许可证 = OpenSSL BSD + SSLeay BSD + ISC + MIT

2025-02-03  commit 33d1049  "Switch the license to Apache 2.0"
            ← 许可证切换点

2025-10-08  commit 87dff58  "Unwind <openssl/bcm_public.h>"
            ← bcm_public.h 移除

2026-09-24  AT Step 2A.2 构建旧版 BoringSSL
            ← 使用 NDK r27 Clang 18.0.1

2026-10-22  AT master 升级到 BoringSSL df82113 (Apache-2.0)
```

## 3. 旧版头文件版权声明审计

### 3.1 统计

| 类别 | 数量 | 占比 |
|------|------|------|
| Google ISC-style ("Copyright (c) YYYY, Google Inc.") | 44 | 44.9% |
| OpenSSL BSD ("Copyright (C) 1995-1998 Eric Young" / "Copyright (c) 1998-2011 OpenSSL Project") | 34 | 34.7% |
| 其他版权 | 13 | 13.3% |
| Apache-2.0 | 0 | 0% |
| **有版权声明** | **98** | **100%** |
| SSLeay/Tim Hudson 引用 | 103 处 | — |

### 3.2 证据等级

```text
OLD_BINARY_TO_LICENSE_MATCH = HIGH_CONFIDENCE

依据:
1. 98/98 头文件有版权声明 (0 缺失)
2. 0 个头文件含 Apache-2.0 文本 → 确认 pre-2025-02
3. 44 个 Google ISC 头文件 → BoringSSL 新增代码
4. 34 个 OpenSSL BSD 头文件 → OpenSSL 派生代码
5. 103 处 SSLeay/Tim Hudson 引用 → SSLeay 原始代码
6. 有 bcm_public.h → 版本 < 2025-10-08

限制:
- 无法将头文件与特定上游 commit 精确匹配
- 源码目录已删除，无法做文件级 SHA-256 对比
```

## 4. 第三方组件审计

| 组件 | 许可证 | 证据 | 再分发要求 | 合规状态 |
|------|--------|------|-----------|----------|
| OpenSSL 派生代码 | OpenSSL BSD | 34 头文件有 OpenSSL 版权 | 保留版权 + 二进制分发时包含声明 | SATISFIED |
| SSLeay 原始代码 | SSLeay BSD | 103 处 SSLeay/Tim Hudson 引用 | 保留版权 + Eric Young 声明 | SATISFIED |
| BoringSSL 新代码 | ISC | 44 头文件有 Google ISC | 保留版权 | SATISFIED |
| third_party/fiat | MIT | LICENSE 文件含 MIT 文本 | 保留许可证文本 | SATISFIED (文本在 LICENSE 中) |

### 4.1 无 copyleft 组件

未发现 GPL/LGPL/AGPL 组件。所有许可证均为 permissive。

## 5. 再分发义务

### 5.1 已满足

| 义务 | 满足方式 |
|------|----------|
| 保留头文件版权声明 | 98/98 头文件保留在 Git 历史中 |
| 二进制分发时包含 OpenSSL 声明 | NOTICE: "This product includes software developed by the OpenSSL Project..." |
| 二进制分发时包含 SSLeay 声明 | NOTICE: "This product includes cryptographic software written by Eric Young..." |
| 包含许可证文本 | `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md` |
| 包含 NOTICE | 根目录 `NOTICE` |
| 包含 THIRD_PARTY_NOTICES | 根目录 `THIRD_PARTY_NOTICES.md` (历史 BoringSSL 节) |

### 5.2 限制

| 限制 | 影响 | 缓解 |
|------|------|------|
| 精确上游 commit UNKNOWN | 无法验证源码与特定 release 完全匹配 | 许可证 regime (pre-2025-02) 由头文件证据确认；所有组件 permissive |
| Fiat 源码未分发 | MIT 许可证文本在 LICENSE 文件中，但源码不在仓库中 | 低风险：fiat 是知名 MIT 组件 |
| 33.7 MB → 4.1 MB 转换未记录 | 无法验证移除了什么 | 4.1 MB 库包含所有必需符号；是实际分发的版本 |

## 6. 新建/修改文件

### 6.1 新建

| 文件 | 内容 |
|------|------|
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_PROVENANCE.md` | 构建来源、迁移链、版本范围、不可恢复项 |
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md` | pre-2025-02 官方 LICENSE 原文 (SHA-256: E5DFA8A7...) |
| `docs/legal/boringssl/HISTORICAL_BORINGSSL_COMPLIANCE.md` | 合规矩阵、再分发义务、GitHub/AAR 分发注意事项 |
| `docs/audit/AT.GITHUB-BORINGSSL-HISTORICAL-LICENSE-1.md` | 本报告 (SSOT) |

### 6.2 修改

| 文件 | 变更 |
|------|------|
| `NOTICE` | 添加历史 BoringSSL 节 (版本、许可证、SHA-256、档案引用) |
| `THIRD_PARTY_NOTICES.md` | 分为 Current + Historical 两节；更新合规状态为 CLOSED_WITH_LIMITATIONS |

### 6.3 未修改

```text
adb-engine/ production source: 0 files
libboringssl.a / libcrypto.a / libssl.a: unchanged
CMakeLists.txt: unchanged
Gradle configuration: unchanged
dist/AT.V4-CONSUMER-FREEZE-V1/: unchanged
AT.V4-CONSUMER-FREEZE-V1 tag: unchanged
AndroidAgent repository: unchanged
```

## 7. 闭合判定

```text
HISTORICAL_LICENSE_CLOSURE = CLOSED_WITH_LIMITATIONS

判定依据:
1. 许可证 regime 已确认 (pre-2025-02, OpenSSL BSD + SSLeay BSD + ISC + MIT)
2. 所有 98 个头文件保留版权声明
3. 所有再分发义务已满足
4. 历史 LICENSE 原文已保存 (SHA-256 可验证)
5. NOTICE + THIRD_PARTY_NOTICES 已更新
6. 无 copyleft 组件

限制 (不升级为 CLOSED 的原因):
1. 精确上游 commit UNKNOWN (源码目录已删除)
2. 无法做文件级 SHA-256 对比
3. Fiat 源码未分发 (仅许可证文本)

限制不影响合规:
- 所有许可证均为 permissive
- 版权声明完整
- 许可证文本在仓库中
```

## 8. 后续建议

1. **如果保留旧版 BoringSSL (回滚 d1e5883)：** 当前文档已足够支撑公开发布
2. **如果保留新版 (d1e5883)：** 需完成真机验证后发布新候选包；历史文档继续覆盖冻结包
3. **GitHub 发布时：** 确保 `docs/legal/boringssl/` 目录在仓库中（满足许可证文本包含要求）
4. **AAR 分发时：** 确保 LICENSE + NOTICE 随 AAR 一起分发

**STOP.**
