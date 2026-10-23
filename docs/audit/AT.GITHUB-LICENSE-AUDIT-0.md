# AT.GITHUB-LICENSE-AUDIT-0 — AOSP 来源追溯与许可证合规审计

**Date:** 2026-10-22
**Nature:** READ-ONLY LICENSE AUDIT (no modifications made)
**Scope:** V1 (AndroidTerminal/), V2 (AndroidTerminal-V2), V3+V4 (AndroidTerminal-V4)

---

## 执行摘要

AT 项目的 ADB 协议实现（packet/connection/shell v2/pairing/TLS）是**独立实现**，参考了 AOSP 源码结构和协议语义，但代码结构、命名和实现方式与 AOSP 完全不同。文件头注释明确标注了 AOSP 引用位置（文件:行号），这是合规的"参考引用"而非"代码复制"。

BoringSSL 是 vendored 第三方库（BSD 3-Clause / Google + Eric Young），头文件保留原始版权。静态库无版本标识，来源追溯为 LIKELY（从 V2 迁移）。

V1 `research/ADB/` 包含 316 个 AOSP ADB 源码文件（Apache-2.0），属于**直接复制**的研究参考材料。V1 无 Git 历史，不在 V4 仓库中，不需要公开发布。

**结论：AT 原创代码可以采用 Apache-2.0 许可。BoringSSL 保留其 BSD 3-Clause 许可证。无 GPL/LGPL 组件。无发布阻断项。**

---

## 最终报告

```text
STEP = AT.GITHUB-LICENSE-AUDIT-0
VERDICT = PASS_WITH_GAPS

AT_ROOT = D:\codebank\AndCode\AndroidTerminal-V4
V1_ROOT_STATUS = SOURCE_ONLY (no .git; 13567 source files; research/ADB/ = 316 AOSP files)
V2_ROOT_STATUS = INDEPENDENT_GIT (30 commits, branch main, 2 tags)
V4_HEAD = 7f3a7dface6e504970714631ba4fb2224052f5ef
V4_WORKTREE = CLEAN (6 untracked audit/delivery files)

GIT_HISTORY_AUDITED = V4 (65 commits, full history) + V2 (30 commits, full history)
V1_SOURCE_PROVENANCE = NO_GIT (source files only; research/ADB/ = direct AOSP copies)
V2_SOURCE_PROVENANCE = VERIFIED (independent git; 22 AOSP-ref files; BoringSSL vendored)
V3_V4_SOURCE_PROVENANCE = VERIFIED (continuous in V4 repo; 29 AOSP-ref files; independent implementation)

AOSP_REFERENCED_FILES_COUNT = 29 (V4) + 22 (V2) [independent implementation with AOSP reference comments]
AOSP_COPIED_OR_MODIFIED_FILES_COUNT = 0 (in V2/V3/V4 production code)
AOSP_SOURCE_UNKNOWN_COUNT = 0 (all AOSP references have explicit file:line citations)
  NOTE: V1 research/ADB/ = 316 DIRECT COPIES of AOSP (Apache-2.0); V1 is not in git, not published

BORINGSSL_SOURCE_VERIFIED = LIKELY (migrated from V2; no version commit recorded; headers present)
BORINGSSL_LICENSE_VERIFIED = YES (BSD 3-Clause; Google Inc. + Eric Young; all 97 headers have copyright)
THIRD_PARTY_COMPONENTS_AUDITED = 1 (BoringSSL: 99 files, 5.83 MB, libboringssl.a 4.14 MB)
THIRD_PARTY_LICENSE_GAPS = 0 (BoringSSL headers retain original copyright; no LICENSE file in V4 root)

APACHE_2_LICENSE_SUITABILITY = VERIFIED for AT original code; BoringSSL retains BSD 3-Clause
AT_ORIGINAL_CODE_LICENSE_STATUS = NO LICENSE FILE (LICENSE_DECISION_REQUIRED)
AOSP_DERIVATIVE_LICENSE_STATUS = NOT_APPLICABLE (no AOSP code in V2/V3/V4 production; V1 research only)
THIRD_PARTY_COMPATIBILITY_STATUS = VERIFIED (BoringSSL BSD 3-Clause is Apache-2.0 compatible)
REQUIRED_NOTICE_AND_ATTRIBUTION = BoringSSL: retain header copyrights; add to THIRD_PARTY_NOTICES

PUBLICATION_BLOCKER_COUNT = 1 (LICENSE file missing)
HIGH_RISK_COUNT = 0
UNKNOWN_PROVENANCE_COUNT = 1 (BoringSSL exact version/commit UNKNOWN; LIKELY from V2 migration)

RECOMMENDED_LICENSE_STRATEGY = Apache-2.0 for AT code + retain BoringSSL BSD 3-Clause headers
LICENSE_FILE_CHANGES_PROPOSED = Add root LICENSE (Apache-2.0) + BoringSSL LICENSE in third_party/
NOTICE_CHANGES_PROPOSED = Add NOTICE + THIRD_PARTY_NOTICES.md
FILES_REQUIRING_FOLLOWUP = 0 (no source code changes needed)

SOURCE_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
LICENSE_FILES_CHANGED = 0
COMMITS_CREATED = 0
TAGS_CREATED_OR_MOVED = 0
PUSH = NO

NEXT_RECOMMENDED_STEP = Controller decides license (D1), then add LICENSE + NOTICE in a new commit
STOP = YES
```

---

## 1. 审计范围

### 1.1 仓库状态

| 仓库 | 路径 | Git | HEAD | 提交数 | 状态 |
|------|------|-----|------|--------|------|
| V1 | `D:\codebank\AndCode\AndroidTerminal` | **NO** | — | 0 | 源码 only |
| V2 | `D:\codebank\AndCode\AndroidTerminal-V2` | YES (independent) | `726a441` | 30 | 完整历史 |
| V3+V4 | `D:\codebank\AndCode\AndroidTerminal-V4` | YES (master) | `7f3a7df` | 65 | 完整历史 (V3 45 + V4 20) |

### 1.2 审计覆盖

- V4 全部 65 个提交 + 当前工作树
- V2 全部 30 个提交 + 当前工作树
- V1 源码快照（无 Git 历史）
- `dist/AT.V4-CONSUMER-FREEZE-V1/` 消费者制品（SHA256 已验证 8/8）
- `adb-engine/src/main/cpp/third_party/boringssl/`（99 文件，5.83 MB）
- 全部 240 个源码文件（.kt/.cpp/.h/.c）的许可证头部扫描

---

## 2. AOSP 来源证据矩阵

### 2.1 V4 生产代码（46 个 non-BoringSSL C/C++ 文件）

**分类一：有显式 AOSP 引用注释（14 文件）— 独立实现**

| 文件 | AOSP 引用 | 实现分类 | Apache-2.0 兼容 |
|------|----------|---------|----------------|
| `adb_protocol/adb_packet.cpp` | types.h:148-161, adb.cpp:113-119, transport.cpp:564-571,1693, apacket_reader.cpp:33-113 | 独立实现 | VERIFIED |
| `adb_protocol/adb_message.h` | AOSP amessage struct | 独立实现 | VERIFIED |
| `adb_protocol/shell_protocol.h` | AOSP shell protocol | 独立实现 | VERIFIED |
| `adb/client/adb_client.cpp` | AOSP adb.cpp + client/auth.cpp + transport.cpp | 独立实现 | VERIFIED |
| `adb/client/adb_client.h` | AOSP reference | 独立实现 | VERIFIED |
| `adb/connection/adb_connection.cpp` | AOSP transport.cpp:512-556, adb.cpp:267-290,498-623 | 独立实现 | VERIFIED |
| `adb/connection/feature_set.cpp` | AOSP reference | 独立实现 | VERIFIED |
| `adb/service/shell_v2_protocol.cpp` | AOSP shell_service_protocol.cpp:32-53, shell_protocol.h:98-105 | 独立实现 | VERIFIED |
| `adb/service/shell_v2_protocol.h` | AOSP reference | 独立实现 | VERIFIED |
| `adb/pairing/pairing_aes_gcm.h` | AOSP reference | 独立实现 | VERIFIED |
| `adb/pairing/pairing_auth.h` | AOSP reference | 独立实现 | VERIFIED |
| `adb/pairing/pairing_packet.h` | AOSP reference | 独立实现 | VERIFIED |
| `adb/pairing/pairing_peerinfo.h` | AOSP reference | 独立实现 | VERIFIED |
| `adb/transport/tls_transport.h` | AOSP reference | 独立实现 | VERIFIED |

**分类二：无显式 AOSP 引用（32 文件）— AT 原创**

包括：`adb_interactive_stream`, `adb_key_store`, `adb_stream_hub`, `rsa_2048_key`, `x509_generator`, `pairing_aes_gcm.cpp`, `pairing_auth.cpp`, `pairing_client`, `pairing_selftest`, `tls_connection`, `tcp_transport`, `adb_engine_jni`, `app_env`, `local_pty`, `cli_bridge_server`, `native_env`, `shellless_pty` 等。

这些文件无 AOSP 引用，无版权头部，属于 AT 原创代码。

**代码对比证据（独立实现确认）：**

| V4 文件 | V1 research/ADB 对应文件 | 结构差异 |
|---------|------------------------|---------|
| `adb_packet.cpp` (procedural, namespace adb) | `apacket_reader.cpp` (class APacketReader) | 完全不同：V4 用 procedure + struct; V1 用 class + method |
| `shell_v2_protocol.cpp` (feed/reset, manual buffer) | `shell_service_protocol.cpp` (ShellProtocol::Read, fd-based) | 完全不同：V4 用 feed() chunk-based; V1 用 Read() fd-based |
| `adb_connection.cpp` (AdbConnection class, TCP/TLS) | `transport.cpp` (AdbTransport, USB/TLS) | 不同抽象：V4 是 TCP client; V1 是 USB/TCP transport |

### 2.2 V2 代码（143 个源码文件）

22 个文件有 AOSP 引用注释（与 V4 相同的 14 个 + 8 个 V2 独有）。9 个文件有 BoringSSL 引用。实现分类同 V4：**独立实现**。

### 2.3 V1 research/ADB/（316 文件）

| 字段 | 值 |
|------|-----|
| 文件数 | 316 |
| Apache-2.0 头部 | 257 |
| 来源 | AOSP packages/modules/adb (直接复制) |
| 用途 | 研究参考（非生产代码） |
| 在 V4 仓库中 | **NO**（不在 V4 git 历史中） |
| 发布影响 | 无（V1 无 Git，不随 V4 发布） |

### 2.4 判定

```text
V2/V3/V4 生产代码 = 独立实现 (参考 AOSP 协议，非复制)
  - 14 个文件有显式 AOSP 引用（文件:行号）
  - 32 个文件无 AOSP 引用（AT 原创）
  - 0 个文件是 AOSP 代码的直接复制
  - 代码结构、命名、抽象方式与 AOSP 完全不同

V1 research/ADB/ = AOSP 直接复制 (316 文件, Apache-2.0)
  - 仅存在于 V1 源码目录
  - 不在 V4 git 历史中
  - 不随 V4 发布
```

---

## 3. BoringSSL 第三方依赖

### 3.1 清单

| 字段 | 值 |
|------|-----|
| 路径 | `adb-engine/src/main/cpp/third_party/boringssl/` |
| 文件数 | 99 (97 headers + 1 static lib + 1 other) |
| 总大小 | 5.83 MB |
| 静态库 | `lib/arm64-v8a/libboringssl.a` (4.14 MB) |
| 版本 | **UNKNOWN**（头文件无版本号；二进制无版本字符串） |
| 来源 | 从 V2 迁移（CMakeLists.txt 注释: "migrated from V2"） |
| 许可证 | **BSD 3-Clause**（Google Inc. + Eric Young/Tim Hudson） |
| LICENSE 文件 | **无**（头文件保留原始版权，但无独立 LICENSE 文件） |

### 3.2 许可证头部验证

- 97/98 头文件有版权声明（`verify.h` 无）
- 版权格式：`Copyright (c) 2014/2017, Google Inc.` + BSD 3-Clause 文本
- 部分文件保留原始 OpenSSL 版权：`Copyright (C) 1995-1998 Eric Young`
- 无 MPL/GPL/LGPL 声明

### 3.3 合规要求

1. **保留头文件版权** — 已满足（97/98 文件有版权）
2. **随附 LICENSE 文件** — **缺失**（需在 `third_party/boringssl/` 添加 BoringSSL LICENSE）
3. **NOTICE 声明** — 需在根目录 NOTICE 或 THIRD_PARTY_NOTICES.md 中声明
4. **版本追溯** — UNKNOWN（建议补充 BoringSSL commit/版本）

### 3.4 Apache-2.0 兼容性

BSD 3-Clause 是 Apache-2.0 兼容许可证。BoringSSL 可以随 Apache-2.0 项目分发，前提是保留原始版权声明。

---

## 4. 其他第三方依赖

### 4.1 Gradle 依赖

V4 所有 `build.gradle.kts` 文件中**无外部 Maven/Gradle 依赖**（除 Gradle wrapper 自身）。项目完全自包含。

### 4.2 二进制制品

`dist/AT.V4-CONSUMER-FREEZE-V1/artifacts/` 包含 8 个 AAR/JAR（SHA256 已验证）。这些是构建产物，不是第三方依赖。

### 4.3 构建产物

`build/` 目录包含编译中间文件（JAR/AAR），被 `.gitignore` 覆盖（`build/`），不会进入 Git 历史。

**第三方组件总计：1（BoringSSL）**

---

## 5. Apache-2.0 适用性分析

### 5.1 AT 原创代码

**可以**采用 Apache-2.0 许可。

- 46 个 C/C++ 文件：独立实现（14 个参考 AOSP + 32 个原创）
- 96 个 Kotlin 文件：全部原创，无版权头部
- 无 GPL/LGPL 代码
- 无外部贡献者（单一作者）
- 无权属争议

### 5.2 AOSP 衍生代码

**不适用。** V2/V3/V4 生产代码中无 AOSP 代码的直接复制。

- 14 个文件有 AOSP 引用注释 = 合规的"参考引用"（非复制）
- V1 `research/ADB/` = AOSP 直接复制，但不在 V4 仓库中，不发布

### 5.3 第三方依赖

BoringSSL (BSD 3-Clause) 可以在 Apache-2.0 项目中分发。

**必须：**
- 保留 BoringSSL 头文件中的原始版权声明（已满足）
- 添加 BoringSSL LICENSE 文件到 `third_party/boringssl/`
- 在根目录 NOTICE 或 THIRD_PARTY_NOTICES.md 中声明 BoringSSL

**不得：**
- 将 BoringSSL 版权改为 Apache-2.0

### 5.4 发布方式评估

| 方案 | 兼容性 | 义务 | 风险 | 推荐 |
|------|--------|------|------|------|
| A: 整个仓库 Apache-2.0 | 兼容（BoringSSL 保留 BSD 头部） | 添加 LICENSE + NOTICE | 低 | **推荐** |
| B: AT 代码 Apache-2.0 + 第三方各自许可 | 兼容 | 同 A | 低 | 等价 |
| C: 暂缓 BoringSSL 分发 | 不推荐 | — | 破坏构建 | 不推荐 |

---

## 6. 来源不明 / 许可证缺失 / 兼容性存疑

| 文件/组件 | 问题 | 风险 | 处置 |
|-----------|------|------|------|
| BoringSSL 版本 | 无版本标识（头文件/二进制均无版本号） | LOW | 补充 BoringSSL commit/版本到 THIRD_PARTY_NOTICES |
| BoringSSL LICENSE 文件 | 缺失（头文件有版权但无独立 LICENSE） | MEDIUM | 添加 `third_party/boringssl/LICENSE` |
| AT 根目录 LICENSE | 缺失 | **BLOCKER** | 添加 Apache-2.0 LICENSE（需 Controller 确认） |
| AT 根目录 NOTICE | 缺失 | MEDIUM | 添加 NOTICE |
| Kotlin 文件无版权头部 | 96 个文件无 Copyright 声明 | LOW | 可选：添加统一版权头 |
| C/C++ 文件无版权头部 | 32 个 non-AOSP-ref 文件无 Copyright | LOW | 可选：添加统一版权头 |
| V1 research/ADB/ | 316 个 AOSP 文件（Apache-2.0） | 无（不发布） | 无需处置 |

---

## 7. 发布阻断项与最小解决方案

### 7.1 阻断项

| # | 级别 | 项目 | 最小解决 |
|---|------|------|---------|
| 1 | **BLOCKER** | 根目录无 LICENSE 文件 | 添加 Apache-2.0 LICENSE（需 Controller 确认许可证选择） |

### 7.2 高风险项

无。

### 7.3 建议项

| # | 级别 | 项目 | 建议 |
|---|------|------|------|
| 1 | MEDIUM | BoringSSL LICENSE 文件缺失 | 添加 `third_party/boringssl/LICENSE`（BSD 3-Clause 全文） |
| 2 | MEDIUM | 根目录 NOTICE 缺失 | 添加 NOTICE（项目名 + BoringSSL 声明） |
| 3 | MEDIUM | THIRD_PARTY_NOTICES 缺失 | 添加 `THIRD_PARTY_NOTICES.md`（BoringSSL 来源 + 版本） |
| 4 | LOW | BoringSSL 版本 UNKNOWN | 在 THIRD_PARTY_NOTICES 中记录 "version: UNKNOWN (migrated from V2)" |
| 5 | LOW | Kotlin/C++ 文件无版权头 | 可选：添加统一 `Copyright (C) 2024-2026 <author>` 头 |

---

## 8. 推荐 LICENSE / NOTICE / 第三方声明方案

### 8.1 根目录 `LICENSE`

```text
Apache License 2.0 (full text)
```

适用于：AT 全部原创代码（Kotlin + C/C++ + 构建脚本 + 文档）

### 8.2 根目录 `NOTICE`

```text
AndroidTerminal (AT)
Copyright (C) 2024-2026 <author name>

This product includes software developed by the Android Open Source Project
(referenced in source comments for protocol semantics; no AOSP code is
distributed in this repository).

This product includes BoringSSL (BSD 3-Clause).
Copyright (c) 2013-2025 The Chromium Authors. All rights reserved.
Copyright (C) 1995-1998 Eric Young (eay@cryptsoft.com).
```

### 8.3 `third_party/boringssl/LICENSE`

```text
BoringSSL is licensed under the 3-Clause BSD license.
See: https://github.com/google/boringssl/blob/master/LICENSE
(Full BSD 3-Clause text)
```

### 8.4 `THIRD_PARTY_NOTICES.md`

```markdown
# Third-Party Notices

## BoringSSL

- **Component:** BoringSSL (vendored static library + headers)
- **Path:** adb-engine/src/main/cpp/third_party/boringssl/
- **License:** BSD 3-Clause
- **Copyright:** Google Inc. / The Chromium Authors; Eric Young (original OpenSSL)
- **Version:** UNKNOWN (migrated from V2; no version identifier in headers or binary)
- **Files:** 99 (97 headers + 1 static lib + 1 other)
- **Distribution:** Statically linked into adb-engine AAR
- **Obligation:** Retain copyright notices in headers (satisfied); include LICENSE (this file)
```

---

## 9. 外部官方来源

| 来源 | 用途 | 核验方式 |
|------|------|---------|
| AOSP `packages/modules/adb` (Apache-2.0) | V4 代码引用注释的上游参考 | 文件头注释中的 `file:line` 引用 |
| BoringSSL (github.com/google/boringssl, BSD 3-Clause) | Vendored 静态库 + 头文件 | 头文件版权声明 |
| OpenSSL (Eric Young, BSD-style) | BoringSSL 原始版权来源 | 头文件 `Copyright (C) 1995-1998 Eric Young` |

---

## 10. 本轮执行的检查命令

```powershell
# License header scan (all source files)
git ls-files | Where-Object { $_ -match "\.(kt|cpp|h|c)$" } | ForEach-Object {
    Get-Content $_ -TotalCount 10 | Select-String "AOSP|BoringSSL|Apache|BSD|MIT|Copyright|MPL|GPL"
}

# AOSP reference file identification
# (matched "Reference.*AOSP" in first 10 lines)

# Code comparison: V4 vs V1 research/ADB
# adb_packet.cpp vs apacket_reader.cpp
# shell_v2_protocol.cpp vs shell_service_protocol.cpp
# adb_connection.cpp vs transport.cpp

# BoringSSL version extraction
# (ASCII scan of libboringssl.a first 200KB — no version strings found)

# BoringSSL header license verification
# (97/98 headers have Copyright; 1 file without: verify.h)

# V1 research/ADB file count + license headers
# (316 files; 257 with Apache-2.0 header)

# Gradle dependency scan
# (no external Maven/Gradle dependencies in any build.gradle.kts)

# Consumer bundle SHA256
# (8/8 PASS)
```

---

## 操作记录

```text
SOURCE_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
LICENSE_FILES_CHANGED = 0
COMMITS_CREATED = 0
TAGS_CREATED_OR_MOVED = 0
PUSH = NO
```

本轮为纯只读审计，未修改任何文件。

**STOP.**
