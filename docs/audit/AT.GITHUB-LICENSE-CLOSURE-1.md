# AT.GITHUB-LICENSE-CLOSURE-1 — BoringSSL 来源追溯与正式许可证闭合

**Date:** 2026-10-22
**Commit:** `1889181d4b96fdb6a222d677817b4283416d46b0`
**Nature:** LICENSE CLOSURE (4 files added; 1 commit; no production code changed)
**Predecessors:** AT.GITHUB-PREPARATION-0, AT.GITHUB-LICENSE-AUDIT-0

---

## 最终报告

```text
STEP = AT.GITHUB-LICENSE-CLOSURE-1
VERDICT = PASS

AT_ROOT = D:\codebank\AndCode\AndroidTerminal-V4
AT_START_HEAD = 7f3a7dface6e504970714631ba4fb2224052f5ef
AT_FINAL_HEAD = 1889181d4b96fdb6a222d677817b4283416d46b0
AT_WORKTREE = CLEAN (only pre-existing untracked audit/delivery files)

AT_FREEZE_TAG = AT.V4-CONSUMER-FREEZE-V1
AT_FREEZE_TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
AT_FREEZE_TAG_UNCHANGED = YES

BORINGSSL_LOCAL_FILES_VERIFIED = YES (99 files + 1 LICENSE added)
BORINGSSL_SOURCE_PROVENANCE = LIKELY (identical binary V1→V2→V4; no version identifier)
BORINGSSL_EXACT_VERSION = UNKNOWN
BORINGSSL_BINARY_SOURCE_MATCH = YES (SHA256 602A6D6C... identical across V1/V2/V4)
BORINGSSL_LICENSE_CONCLUSION = Apache-2.0 (per BoringSSL official LICENSE)
BORINGSSL_LICENSE_EVIDENCE = Official BoringSSL LICENSE + 97 header copyright notices
BORINGSSL_REDISTRIBUTION_REQUIREMENTS = Retain header copyrights + include LICENSE file

AT_ORIGINAL_CODE_LICENSE = APACHE-2.0
ROOT_LICENSE_CREATED = YES
BORINGSSL_LICENSE_CREATED = YES
NOTICE_CREATED = YES
THIRD_PARTY_NOTICES_CREATED = YES

LICENSE_SCOPE_CLEAR = YES
COPYRIGHT_NOTICES_PRESERVED = YES
THIRD_PARTY_LICENSE_CLOSURE = YES
LICENSE_FILES_SELF_CONSISTENT = YES

PRODUCTION_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
FROZEN_ARTIFACTS_CHANGED = 0
FROZEN_TAG_CHANGED = NO

COMMIT = 1889181d4b96fdb6a222d677817b4283416d46b0
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDED_STEP = Update README (stale V3-era content), then GitHub push
STOP = YES
```

---

## 1. BoringSSL 来源追溯

### 1.1 二进制一致性

| 仓库 | 路径 | SHA256 |
|------|------|--------|
| V1 | `app/src/main/cpp/third_party/boringssl/lib/arm64-v8a/libboringssl.a` | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` |
| V2 | `at-core/src/main/cpp/third_party/boringssl/lib/arm64-v8a/libboringssl.a` | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` |
| V4 | `adb-engine/src/main/cpp/third_party/boringssl/lib/arm64-v8a/libboringssl.a` | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` |

**三个版本的静态库字节完全一致。**

### 1.2 迁移历史

```text
V1 (AndroidTerminal/, no git)
  app/src/main/cpp/third_party/boringssl/  ← 原始来源（无版本记录）
  ↓ 迁移
V2 commit a96664e (AT.V2-4A, 2026-10-05)
  "extract AT Core from AT.apk"
  at-core/src/main/cpp/third_party/boringssl/
  ↓ 迁移
V4 commit a40387e (AT.V3-1)
  "extract and validate independent ADB engine"
  adb-engine/src/main/cpp/third_party/boringssl/
```

CMakeLists.txt 注释：`"BoringSSL vendored static library + headers (migrated from V2)"`

### 1.3 版本判定

```text
BORINGSSL_EXACT_VERSION = UNKNOWN
- 头文件无版本号
- 二进制无版本字符串
- 无上游 commit/tag 记录
- 无构建日志
- 无压缩包校验和
```

### 1.4 来源判定

```text
BORINGSSL_SOURCE_PROVENANCE = LIKELY (Google BoringSSL)
- 97/98 头文件有 "Copyright (c) 2014/2017, Google Inc." 版权
- 部分文件保留原始 OpenSSL 版权 (Eric Young, Tim Hudson)
- 头文件结构与 BoringSSL 官方仓库一致
- 但无法确定具体上游 commit 或 release tag
```

---

## 2. 官方许可证核验

### 2.1 关键修正

**上一轮审计（LICENSE-AUDIT-0）将 BoringSSL 标记为 BSD 3-Clause。本轮修正为 Apache-2.0。**

| 项 | 上一轮（错误） | 本轮（修正） |
|----|---------------|-------------|
| BoringSSL 许可证 | BSD 3-Clause | **Apache-2.0** |
| 依据 | 头文件中的 BSD 风格版权文本 | BoringSSL 官方 LICENSE 文件 |

**解释：** BoringSSL 头文件中保留的 `Copyright (C) 1995-1998 Eric Young` BSD 风格文本是**原始 OpenSSL 版权保留**，不是 BoringSSL 本身的许可证。BoringSSL 官方许可证（github.com/google/boringssl/blob/main/LICENSE）是 **Apache-2.0**，其中明确包含 "Licenses for support code" 部分说明原始 OpenSSL/Go 版权的处理。

### 2.2 官方 LICENSE 内容

BoringSSL 官方 LICENSE 包含：
1. Apache License 2.0 全文
2. "Licenses for support code" 部分：TLS test suite 使用 Go license（不影响 libcrypto/libssl 分发）

### 2.3 许可证适用范围

```text
BoringSSL (99 files under third_party/boringssl/)
  → Apache-2.0（BoringSSL 官方 LICENSE）
  → 原始 OpenSSL BSD 版权保留在头文件中（不删除、不修改）
  → NOT re-licensed by AT root LICENSE

AT original code (all other files)
  → Apache-2.0（root LICENSE）
```

---

## 3. 许可证文件

### 3.1 新增文件

| 文件 | 大小 | 内容 |
|------|------|------|
| `LICENSE` | 11,110B | Apache-2.0 全文（AT 原创代码） |
| `NOTICE` | 1,143B | 项目身份 + BoringSSL 第三方声明 + AOSP 参考说明 |
| `THIRD_PARTY_NOTICES.md` | 2,030B | BoringSSL 来源、版本(UNKNOWN)、许可证、再分发义务、证据 |
| `adb-engine/.../boringssl/LICENSE` | 12,663B | BoringSSL 官方许可证全文（Apache-2.0 + OpenSSL BSD 保留说明） |

### 3.2 NOTICE 内容要点

- 项目名称：AndroidTerminal (AT)
- AT 原创代码：Apache-2.0
- BoringSSL：第三方组件，Apache-2.0，原始 OpenSSL 版权保留在头文件
- ADB 协议实现：独立实现，参考 AOSP 协议语义，**不分发 AOSP 源码**

### 3.3 THIRD_PARTY_NOTICES.md 内容要点

- Component: BoringSSL
- Location: `adb-engine/src/main/cpp/third_party/boringssl/`
- Version: UNKNOWN
- Source: Google BoringSSL (binary identical V1→V2→V4)
- License: Apache-2.0 (with OpenSSL BSD copyright in headers)
- Binary distribution: libboringssl.a statically linked into adb-engine AAR
- Required notices: retain header copyrights + include LICENSE (both satisfied)
- Evidence: header copyrights + CMakeLists comment + V2/V4 commit SHAs

---

## 4. 验证结果

| 检查项 | 结果 |
|--------|------|
| 4 个新文件存在 | PASS |
| BoringSSL 头文件未修改 | PASS (evp.h 版权头完整) |
| libboringssl.a 未修改 | PASS (SHA256 一致) |
| 冻结制品未修改 | PASS (8/8 SHA256) |
| 冻结标签未修改 | PASS |
| 无虚构版本号/commit/作者 | PASS |
| 许可证文件自洽 | PASS |
| 生产代码/测试/构建配置未修改 | PASS |

---

## 5. Git 操作

```text
COMMIT = 1889181d4b96fdb6a222d677817b4283416d46b0
MESSAGE = "AT.GITHUB-LICENSE-CLOSURE-1: add Apache-2.0 LICENSE, NOTICE,
          THIRD_PARTY_NOTICES, BoringSSL LICENSE"
FILES = 4 (all new)
INSERTIONS = 507
PUSH = NO
TAG = NO
```

---

## 6. 剩余事项

| # | 项 | 级别 | 说明 |
|---|-----|------|------|
| 1 | README 过期 | MEDIUM | V3 时代内容（标题 "AndroidTerminal-V3"），需单独更新 |
| 2 | BoringSSL 精确版本 | LOW | UNKNOWN，已记录在 THIRD_PARTY_NOTICES.md |
| 3 | 个人主机名/IP | LOW | 3 处 evidence/test 文件（非许可证问题） |

**STOP.**
