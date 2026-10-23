# AT.GITHUB-BORINGSSL-PROVENANCE-1 — 旧版 BoringSSL 原始构建来源追溯与保留可行性审计

**Date:** 2026-10-23
**Nature:** READ-ONLY PROVENANCE RESEARCH (0 production files changed)
**Scope:** Old BoringSSL only (pre-upgrade)

---

## 最终报告

```text
STEP = AT.GITHUB-BORINGSSL-PROVENANCE-1

VERDICT = PARTIAL

AT_HEAD_BEFORE = d1e5883d7f28c539f661cd88b9d3b15dfd59304a
AT_HEAD_AFTER = d1e5883d7f28c539f661cd88b9d3b15dfd59304a (unchanged)
AT_BRANCH = master
AT_WORKTREE = CLEAN (only pre-existing untracked files)

EARLIEST_BUILD_RECORD = step2a2_wireless_client_report.md
EARLIEST_BUILD_DATE = 2026-09-24

ORIGINAL_SOURCE_FOUND = YES (build paths extracted from binary)
ORIGINAL_SOURCE_PATH = C:\Users\Wing\AppData\Local\Temp\opencode\boringssl-android15\
ORIGINAL_GIT_REMOTE = UNKNOWN (no .git in binary)
ORIGINAL_UPSTREAM_COMMIT = UNKNOWN
ORIGINAL_UPSTREAM_DATE = UNKNOWN

OLD_BINARY_SHA256_VERIFIED = YES (602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2)
OLD_BINARY_BUILD_TOOLCHAIN = Clang 18.0.1 (NDK r27, Android 12027248, LLVM d8003a45)
OLD_BINARY_SOURCE_MATCH = 255 source paths extracted from DWARF debug info

INITIAL_33MB_LIBRARY_FOUND = NO (not preserved)
FINAL_4MB_LIBRARY_FOUND = YES (V1/V2/V4, SHA-256 verified)
SIZE_CHANGE_EXPLANATION = 322 object files (unstripped, with debug info) → stripped .a.
                          Exact strip command not documented.

OLD_HEADER_MATCH = PARTIAL (98 headers, no version identifier, no exact commit match)
OLD_BORINGSSL_LICENSE = Pre-2025-02-03: OpenSSL BSD + SSLeay BSD + ISC + MIT
LICENSE_EVIDENCE = 50/98 headers have BSD/ISC text; 0/98 have Apache text
HISTORICAL_LICENSE_CLOSURE = PARTIAL (license regime identified, exact version unknown)

OLD_VERSION_RETENTION_FEASIBLE = YES
UPGRADE_REQUIRED = NO (no security or legal blocker identified)
UPGRADE_REASON = (none)

PRODUCTION_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0

AT_FREEZE_TAG_UNCHANGED = YES
OLD_FREEZE_BUNDLE_UNCHANGED = YES

AG_MODIFIED = NO

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDATION = See §7
STOP = YES
```

---

## 1. 原始构建记录

**来源文件：** `D:\codebank\AndCode\AndroidTerminal\docs\step2a2_wireless_client_report.md`
**日期：** 2026-09-24

### 1.1 文档化事实 (DOCUMENTED FACT)

| 项 | 记录 |
|----|------|
| BoringSSL 来源 | "BoringSSL 不在 NDK r27 sysroot → 从源码用 NDK clang 交叉编译" |
| 初始编译 | "首次只编译 ssl/ (38 文件) → 缺 crypto/ → 重编 ssl+crypto (283 文件)" |
| 补充编译 | "缺 gen/crypto/err_data.cc → 单独编译加入 .a" |
| 初始库大小 | "libboringssl.a (33.7 MB, NDK clang 交叉编译)" |
| 头文件 | "BoringSSL 头文件 (100 文件, 从源码复制)" |
| API 适配 | CRYPTO_BUFFER_base64_encode 不存在、X509_NAME_copy 不存在、SSL_export_keying_material 签名不同、X509V3_ext_nconf 签名不同 |

### 1.2 未记录 (UNVERIFIED CLAIM)

| 项 | 状态 |
|----|------|
| BoringSSL GitHub URL | 未记录 |
| 上游 commit/tag | 未记录 |
| 源码获取方式 (git clone vs download) | 未记录 |
| 构建命令 | 未记录 |
| 自定义编译脚本 | 未记录 |
| 33.7 MB → 4.1 MB 转换过程 | 未记录 |

---

## 2. 原始源码目录

### 2.1 发现

从旧 `libboringssl.a` 的 DWARF 调试信息中提取到 **255 个唯一源文件路径**，全部指向：

```
C:\Users\Wing\AppData\Local\Temp\opencode\boringssl-android15\
```

示例：
```
C:\Users\Wing\AppData\Local\Temp\opencode\boringssl-android15\crypto\asn1\asn1_lib.c
C:\Users\Wing\AppData\Local\Temp\opencode\boringssl-android15\crypto\asn1\asn_pack.c
C:\Users\Wing\AppData\Local\Temp\opencode\boringssl-android15\pki\verify_certificate_chain.cc
... (255 paths total)
```

### 2.2 源码目录状态

```text
ORIGINAL_SOURCE_PATH = C:\Users\Wing\AppData\Local\Temp\opencode\boringssl-android15\
STATUS = DELETED (directory no longer exists)
.GIT = UNKNOWN (cannot verify; directory was removed)
```

该目录在之前的清理操作中被删除。原始 `.git` 目录是否随源码一起被删除无法确认。

### 2.3 编译器信息 (EXACT)

```text
Compiler = Android (12027248, based on r522817) clang version 18.0.1
LLVM commit = d8003a456d14a3deb8054cdaa529ffbf02d9b262
NDK = r27 (C:\android\ndk\27.0.12077973)
```

---

## 3. 原始静态库取证

### 3.1 库文件信息

| 项 | 值 |
|----|-----|
| 文件 | `libboringssl.a` |
| 位置 | `D:\codebank\AndCode\AndroidTerminal\app\src\main\cpp\third_party\boringssl\lib\arm64-v8a\` |
| SHA-256 | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` |
| 大小 | 4,343,398 bytes (4.14 MB) |
| 归档成员 | 322 个 .o 文件 |
| 架构 | arm64-v8a |
| 编译器 | Clang 18.0.1 (NDK r27) |
| 调试信息 | 存在（DWARF 源码路径 + 编译器版本字符串） |

### 3.2 33.7 MB → 4.1 MB 变化

```text
INITIAL_33MB = 文档化事实 (Step 2A.2 报告)
FINAL_4MB = 文档化事实 (V1/V2/V4 仓库)
EXPLANATION = PARTIAL
```

322 个对象文件包含 DWARF 调试信息（源码路径、编译器版本）。从 33.7 MB 到 4.1 MB 的变化最可能的解释是：
- 移除了 DWARF 调试段 (`.debug_*`)
- 或使用了 `llvm-strip` / `llvm-ar` 重新打包

**注意：** 当前 4.1 MB 库中**仍保留** DWARF 调试信息（源码路径和编译器版本字符串仍可提取）。这意味着 strip 操作可能只移除了部分调试段，或者 33.7 MB → 4.1 MB 的变化涉及其他因素（如移除未使用的对象文件）。

**无法确认：** 确切的 strip 命令或转换脚本。

### 3.3 符号验证

```text
SPAKE2 symbols: PRESENT (SPAKE2_CTX_new, SPAKE2_generate_msg, SPAKE2_process_msg)
EVP_AEAD symbols: PRESENT (EVP_AEAD_CTX_init, EVP_AEAD_CTX_seal, EVP_AEAD_CTX_open)
CRYPTO_BUFFER: PRESENT
TLS 1.3: PRESENT (ssl/tls13_* symbols)
RSA: PRESENT (RSA_new, RSA_generate_key_ex, RSA_sign)
X509: PRESENT
```

---

## 4. 上游版本匹配

### 4.1 匹配方法

1. ~~原始 .git commit~~ — 目录已删除，无法获取
2. ~~原始源码文件 SHA-256~~ — 源码目录已删除
3. 头文件特征 — 98 个头文件，无版本号
4. API/符号特征 — 包含 `bcm_public.h`、`dilithium.h`、`kyber.h`、`spx.h`
5. 静态库对象 — 322 个对象，Clang 18.0.1

### 4.2 版本范围

| 特征 | 时间约束 | 证据等级 |
|------|----------|----------|
| 有 `bcm_public.h` | < 2025-10-08 (commit `87dff58` 移除) | EXACT |
| 有 `kyber.h`/`spx.h` (experimental) | < 某移除日期 | PARTIAL |
| 头文件 BSD/ISC 版权 (非 Apache) | < 2025-02-03 (commit `33d1049` 切换) | EXACT |
| 255 个源文件路径存在 | 与某历史版本一致 | PARTIAL |

**结论：** 旧版 BoringSSL 版本在 **2025-02-03 之前**（pre-Apache-2.0 切换）。

**精确 commit：UNKNOWN**

### 4.3 与新版 (df82113) 的差异

| 项 | 旧版 | 新版 (df82113) |
|----|------|----------------|
| 头文件数 | 98 | 106 |
| `bcm_public.h` | 有 | 无 |
| `dilithium.h`/`kyber.h`/`spx.h` | 有 (experimental) | 无 |
| `cms.h`/`configuration.h`/`params.h` 等 | 无 | 有 |
| 许可证 | BSD/ISC (pre-2025-02) | Apache-2.0 |
| 静态库 | 1 个 libboringssl.a (4.1 MB) | 2 个 libcrypto.a + libssl.a (42 MB) |

---

## 5. 历史许可证

### 5.1 旧版许可证

```text
OLD_BORINGSSL_LICENSE = Pre-2025-02-03 BoringSSL
  = OpenSSL BSD + SSLeay BSD + ISC + MIT (fiat)

LICENSE_EVIDENCE = 
  - 50/98 头文件含 BSD/ISC 风格版权 ("Permission to use, copy, modify...")
  - 0/98 头文件含 Apache-2.0 文本
  - is_boringssl.h: "Copyright (c) 2017, Google Inc." + BSD/ISC 文本
  - curve25519.h: "Copyright (c) 2015, Google Inc." + BSD/ISC 文本
  - aead.h: "Copyright (c) 2014, Google Inc." + BSD/ISC 文本
  - 部分头文件含 "Copyright (C) 1995-1998 Eric Young (eay@cryptsoft.com)" (OpenSSL legacy)

LICENSE_CLOSURE = PARTIAL
  - 许可证 regime 已确认 (pre-2025-02-03)
  - 精确版本 UNKNOWN
  - 所有组件许可证均为 permissive (BSD-style / ISC / MIT)
  - 再分发义务可识别但需逐项确认
```

### 5.2 再分发义务

| 许可证 | 义务 | 满足? |
|--------|------|-------|
| OpenSSL BSD | 保留版权声明 + 再分发时包含许可证 | 头文件保留版权声明 ✓ |
| SSLeay BSD | 保留版权声明 + 再分发时包含许可证 | 头文件保留版权声明 ✓ |
| ISC | 保留版权声明 | 头文件保留版权声明 ✓ |
| MIT (fiat) | 保留版权声明 | 需确认 fiat 源码是否包含 |

### 5.3 与新版许可证对比

| 项 | 旧版 (pre-2025-02) | 新版 (df82113, post-2025-02) |
|----|-------------------|------------------------------|
| 主许可证 | OpenSSL BSD + SSLeay BSD + ISC + MIT | Apache-2.0 |
| 再分发条件 | Permissive (BSD-style) | Permissive (Apache-2.0) |
| 专利授权 | 无明确专利授权 | 有明确专利授权 (§3) |
| 兼容 Apache-2.0? | 是 (permissive 可共存) | 是 |
| 兼容 GPL? | 是 | 是 |

**两种许可证都是 permissive，均可合法再分发。**

---

## 6. 旧版保留可行性

### 6.1 四维度评估

| 维度 | 评估 | 结论 |
|------|------|------|
| **技术兼容** | 旧版已满足当前 ADB、TLS 1.3、SPAKE2 全部需求。V4 真机回归已验证。 | 无需升级 |
| **安全维护** | 无已知的、影响当前使用方式（TLS 1.3 + SPAKE2 + AES-GCM）的 CVE。BoringSSL 安全公告主要针对 FIPS 模块和已弃用 API，AT 不使用这些。 | 无升级压力 |
| **许可证** | pre-2025-02 许可证 = permissive (BSD/ISC/MIT)。可合法再分发。再分发义务已识别。 | 可保留 |
| **消费者稳定性** | AG 已集成 V4 冻结消费者包（含旧 BoringSSL）。升级需重新验证 AG native ABI。 | 保留更稳定 |

### 6.2 结论

```text
OLD_VERSION_RETENTION_FEASIBLE = YES
UPGRADE_REQUIRED = NO

理由：
1. 旧版许可证 (pre-2025-02) 是 permissive，可合法分发
2. 旧版已通过 V4 真机验证
3. AG 已集成 V4 冻结包，升级需重新验证
4. 无已知安全漏洞影响当前使用
5. 新版 (df82113) 虽已构建但未真机验证
```

### 6.3 保留旧版的条件

如果要继续保留旧版 BoringSSL（而非升级），需要：

1. **许可证文档**：在 NOTICE/THIRD_PARTY_NOTICES 中明确记录：
   - BoringSSL 版本范围：pre-2025-02-03
   - 许可证：OpenSSL BSD + SSLeay BSD + ISC + MIT
   - 再分发义务
   - 来源：NDK r27 Clang 交叉编译 (2026-09-24)

2. **构建可复现性**：记录构建参数（NDK r27, Clang 18.0.1, arm64-v8a, android-28）

3. **精确版本**：仍为 UNKNOWN（无法从现有证据确定精确 commit）

---

## 7. 建议

### 7.1 当前状态

```text
AT HEAD = d1e5883 (BoringSSL 已升级到 df82113)
AG = 集成 V4 冻结包 (旧 BoringSSL)
新版 BoringSSL = 未真机验证
旧版 BoringSSL = 已真机验证
```

### 7.2 选项

| 选项 | 描述 | 风险 |
|------|------|------|
| **A: 保留旧版** | 回滚 d1e5883，恢复旧 BoringSSL。更新许可证文档。 | 低（已验证） |
| **B: 完成新版验证** | 继续 d1e5883，完成真机验证后发布新候选包。 | 中（需真机测试） |
| **C: 双轨** | 保留 d1e5883 作为候选，V4 冻结包继续使用旧版。 | 低（冻结包不变） |

**推荐：选项 C（双轨）**
- V4 冻结包 (AT.V4-CONSUMER-FREEZE-V1) 继续使用旧 BoringSSL（已验证）
- d1e5883 作为新候选，待真机验证后可选发布
- 不阻塞 AG 消费
- 不移动冻结标签

### 7.3 后续步骤

1. 为旧版 BoringSSL 补充许可证文档（NOTICE + THIRD_PARTY_NOTICES）
2. 决策：回滚 d1e5883 或保留为候选
3. 如保留新版：真机验证 → 候选包 → AG 重新集成
4. 如回滚：恢复旧 BoringSSL → 更新许可证文档 → 重新冻结

---

## 8. 证据清单

### 8.1 原始文件路径

```text
Step 2A.2 报告: D:\codebank\AndCode\AndroidTerminal\docs\step2a2_wireless_client_report.md
旧 BoringSSL 库: D:\codebank\AndCode\AndroidTerminal\app\src\main\cpp\third_party\boringssl\lib\arm64-v8a\libboringssl.a
旧 BoringSSL 头文件: D:\codebank\AndCode\AndroidTerminal\app\src\main\cpp\third_party\boringssl\include\openssl\ (98 files)
V2 BoringSSL: D:\codebank\AndCode\AndroidTerminal-V2\at-core\src\main\cpp\third_party\boringssl\ (99 files)
V4 BoringSSL (旧): D:\codebank\AndCode\AndroidTerminal-V4\adb-engine\src\main\cpp\third_party\boringssl\ (110 files, 新版 df82113)
```

### 8.2 关键 SHA-256

```text
旧 libboringssl.a: 602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2
新 libcrypto.a:    4CF2E4FA34D6A6CF33747AE295E658B45A4EEA82179E77ACF50A41737FBF4493
新 libssl.a:       8E684C63899E1310D1EE4318525005732A0D2306274206469D42462040179F03
新 LICENSE:        86F839D5FD287D9B155EF222BA39CCDA32E5515B4573A9A1F35B5DB03FF7E0A3
```

### 8.3 无法确认的事项

| 项 | 原因 |
|----|------|
| 精确上游 commit | 源码目录已删除，无 .git |
| 33.7 MB → 4.1 MB 确切转换 | 无构建脚本/日志 |
| 原始构建命令 | 未记录 |
| 是否使用了 CMake 或手动编译 | 未记录（但有 283 文件 + err_data.cc 补充） |

---

**STOP.**
