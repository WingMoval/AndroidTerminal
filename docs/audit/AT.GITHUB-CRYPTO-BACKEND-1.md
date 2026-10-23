# AT.GITHUB-CRYPTO-BACKEND-1 — BoringSSL 许可证最终判定与替代密码库迁移评估

**Date:** 2026-10-22
**Commit:** `db8a181d0e865efe43f7c588adca71bf1b4ade50`
**Nature:** LICENSE CLOSURE + CRYPTO BACKEND EVALUATION (3 files modified; 1 commit; no production code changed)
**Predecessors:** AT.GITHUB-PREPARATION-0, AT.GITHUB-LICENSE-AUDIT-0, AT.GITHUB-LICENSE-CLOSURE-1

---

## 最终报告

```text
STEP = AT.GITHUB-CRYPTO-BACKEND-1
VERDICT = PARTIAL

AT_START_HEAD = 1889181d4b96fdb6a222d677817b4283416d46b0
AT_FINAL_HEAD = db8a181d0e865efe43f7c588adca71bf1b4ade50
AT_WORKTREE = CLEAN (only pre-existing untracked audit/delivery files)

AT_FREEZE_TAG = AT.V4-CONSUMER-FREEZE-V1
AT_FREEZE_TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
AT_FREEZE_TAG_UNCHANGED = YES

BORINGSSL_LICENSE_STATUS = LICENSE_CLOSURE_UNCERTAIN (version UNKNOWN; pre/post Feb 2025 regimes both documented)
BORINGSSL_SOURCE_PROVENANCE = LIKELY (Google BoringSSL; identical binary V1→V2→V4)
BORINGSSL_EXACT_VERSION = UNKNOWN

BORINGSSL_API_USAGE_AUDIT = COMPLETED
SPAKE2_COMPATIBILITY = BORINGSSL_SPECIFIC (openssl/curve25519.h; NOT in OpenSSL 3.x)
TLS13_COMPATIBILITY = BORINGSSL (SSL_CTX/SSL_new/SSL_connect; CRYPTO_BUFFER for certs)

CRYPTO_BACKEND_SELECTED = BoringSSL (retained; replacement not feasible)
CRYPTO_BACKEND_VERSION = UNKNOWN
CRYPTO_BACKEND_SOURCE_SHA256 = 602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2
CRYPTO_BACKEND_LICENSE = Version-dependent (see below)

PRODUCTION_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
LICENSE_FILES_CHANGED = 3

BUILD = NOT_RUN (no code changes)
AUTOMATED_TESTS = NOT_RUN (no code changes)
DEVICE_TLS_ADB_TEST = NOT_RUN (no code changes)
PAIRING_PARITY = NOT_RUN (no code changes)
IDENTITY_PERSISTENCE = NOT_RUN (no code changes)
EXECUTION_SEMANTIC_PARITY = NOT_RUN (no code changes)

OLD_FREEZE_BUNDLE_UNCHANGED = YES
NEW_CANDIDATE_BUNDLE = NONE
NEW_BUNDLE_SHA256_VERIFIED = N/A

HISTORICAL_LICENSE_CLOSURE = DOCUMENTED
HISTORICAL_BINARY_DISTRIBUTION_RISK = LOW
PUBLICATION_BLOCKERS_REMAINING = 0

COMMIT = db8a181d0e865efe43f7c588adca71bf1b4ade50
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDED_STEP = Update README (stale V3-era content), then GitHub push
STOP = YES
```

---

## 1. BoringSSL 许可证最终判定

### 1.1 关键发现：许可证切换

BoringSSL 官方仓库在 **2025-02-03** (commit `33d1049b1f730d2725bb09b2256fd5fe4c46b17e`) 执行了许可证切换：

```text
"Switch the license to Apache 2.0, matching OpenSSL upstream"
```

| 时期 | 许可证 | 来源 |
|------|--------|------|
| Pre-2025-02-03 | OpenSSL BSD + SSLeay BSD + ISC + MIT | BoringSSL 旧 LICENSE |
| Post-2025-02-03 | Apache-2.0 | BoringSSL 新 LICENSE |

### 1.2 本地库版本

```text
BORINGSSL_EXACT_VERSION = UNKNOWN
- 头文件无版本号
- 二进制无版本字符串
- 无上游 commit/tag 记录
- 无构建日志
- V1 → V2 → V4 迁移未记录版本
```

### 1.3 判定

```text
BORINGSSL_LICENSE_STATUS = LICENSE_CLOSURE_UNCERTAIN
- 无法确定本地库属于 pre 还是 post Feb 2025
- 两种许可证都是 permissive (BSD-style 或 Apache-2.0)
- 再分发义务在两种 regime 下均可满足
- 处置：LICENSE 文件同时记录两种许可证 + 再分发义务
```

### 1.4 与上一轮（LICENSE-CLOSURE-1）的差异

| 项 | LICENSE-CLOSURE-1 (1889181) | CRYPTO-BACKEND-1 (db8a181) |
|----|---------------------------|---------------------------|
| BoringSSL 许可证 | Apache-2.0 | **Version-dependent** (pre: BSD+ISC+MIT; post: Apache-2.0) |
| 依据 | 当前官方主分支 LICENSE | 官方 LICENSE 历史 + 版本 UNKNOWN |
| NOTICE | "BoringSSL is licensed under Apache-2.0" | "Version-dependent; both regimes documented" |
| 再分发义务 | Apache-2.0 义务 | OpenSSL BSD + SSLeay BSD + ISC + MIT + Apache-2.0 义务 |

---

## 2. 密码学 API 使用清单

### 2.1 完整映射表

| 功能 | 实际 API | 文件 | 次数 | BoringSSL 专有? | OpenSSL 3.x 替代 |
|------|---------|------|:----:|:-:|:-:|
| TLS 上下文 | `SSL_CTX_new/free` | tls_connection.cpp | 15 | 否 | 有 |
| TLS 握手/IO | `SSL_new/connect/read/write/free` | tls_connection.cpp | 9 | 否 | 有 |
| 证书加载 | `CRYPTO_BUFFER_new_mem` | tls_connection.cpp | 6 | **是** | 无直接等价 |
| RSA 密钥生成 | `RSA_new/generate_key_ex` | rsa_2048_key.cpp | 9 | 否 (deprecated) | 有 (EVP_PKEY) |
| RSA 签名 | `RSA_sign` | adb_key_store.cpp | 2 | 否 (deprecated) | 有 (EVP_DigestSign) |
| X.509 证书 | `X509_new/sign` | x509_generator.cpp | 4 | 否 | 有 |
| BIO 文件/内存 | `BIO_new/free/mem_buf/file` | x509_generator.cpp, adb_key_store.cpp | 8 | 否 | 有 |
| **SPAKE2** | `SPAKE2_CTX_new/...` | pairing_auth.cpp, pairing_client.cpp | 34 | **是** (curve25519.h) | **无** |
| **AES-128-GCM** | `EVP_AEAD_CTX_init/seal/open` | pairing_aes_gcm.cpp | 11 | **是** | 无 (EVP_CIPHER_CTX) |
| SHA-256 | `SHA256` | pairing_aes_gcm.cpp, tls_transport.cpp | 3 | 否 | 有 |
| EVP_PKEY 操作 | `EVP_PKEY_*` | rsa_2048_key.cpp, adb_key_store.cpp | 14 | 否 | 有 |

### 2.2 SPAKE2 详情

```text
SPAKE2 = BoringSSL 专有 API (openssl/curve25519.h)
- 用途：ADB Wireless Debugging 配对核心
- 角色：client = alice, server = bob
- 密码：pairing_code bytes || 64-byte TLS ExportKeyingMaterial
- 输出：32-byte key material → PairingAesGcm
- 使用位置：
  - pairing_auth.cpp (18 处)
  - pairing_client.cpp (16 处)
  - pairing_auth.h (7 处)
  - pairing_selftest.cpp (22 处)
  - tls_connection.cpp/h (2 处)
  - tls_transport.h (1 处)

OpenSSL 3.x 不提供 SPAKE2。
自行实现 = 未经验证的密码学原语（任务禁止）。
```

### 2.3 EVP_AEAD_CTX 详情

```text
EVP_AEAD_CTX = BoringSSL 专有 API
- 用途：配对 AES-128-GCM 加密/解密
- 使用位置：pairing_aes_gcm.cpp (11 处), pairing_aes_gcm.h (3 处)
- OpenSSL 3.x 等价：EVP_CIPHER_CTX + EVP_EncryptInit/Update/Final
  （API 不同，需要重写）
```

---

## 3. 替换可行性判定

```text
OpenSSL 3.x 替换 = NOT FEASIBLE

阻断原因：
1. SPAKE2：BoringSSL 专有，OpenSSL 3.x 不支持
   - ADB Wireless Debugging 配对核心
   - 无可靠替代实现
   - 自行实现被任务禁止

2. EVP_AEAD_CTX：BoringSSL 专有
   - 配对 AES-128-GCM 依赖
   - 需要重写为 EVP_CIPHER_CTX

3. CRYPTO_BUFFER：BoringSSL 专有
   - TLS 证书加载依赖

替换需要重写全部 TLS + 配对 + 密码学代码。
违反不可变约束：
- ADB 配对协议与配对码语义
- TLS 1.3 握手流程
- identity 生成、保存、复用和持久性
- 首次配对一次、正常运行不重复配对

结论：保留 BoringSSL。
```

---

## 4. 历史发布范围

| 项 | 状态 |
|----|------|
| Git 历史中的 BoringSSL | 存在（commit `a40387e` AT.V3-1 引入，唯一引入点） |
| 冻结标签中的 BoringSSL | 存在（`AT.V4-CONSUMER-FREEZE-V1` 包含完整 BoringSSL 源码树） |
| 冻结 AAR 中的 BoringSSL | 存在（`adb-engine-debug.aar` 静态链接 BoringSSL 代码到 libadbd_engine.so） |
| 许可证覆盖 | LICENSE 文件覆盖 pre/post Feb 2025 两种许可证 |
| 再分发义务 | 满足（保留版权 + 包含 LICENSE + OpenSSL/SSLeay 声明） |
| 历史风险 | **LOW**（两种许可证都是 permissive） |

---

## 5. 修改文件清单

| 文件 | 变更 |
|------|------|
| `adb-engine/.../boringssl/LICENSE` | 重写：覆盖 pre/post Feb 2025 两种许可证 + 再分发义务（247 行） |
| `NOTICE` | 更新：版本依赖许可证说明 + OpenSSL/SSLeay 声明（+4 行） |
| `THIRD_PARTY_NOTICES.md` | 更新：许可证字段改为版本依赖（+2 行） |

---

## 6. 发布判定

```text
LICENSE_RISK = LOW
- 两种许可证都是 permissive (BSD-style / Apache-2.0)
- 再分发义务已满足
- 无 GPL/LGPL 组件

REPLACEMENT_RISK = HIGH (if attempted)
- SPAKE2 无 OpenSSL 等价
- 需要重写全部密码学代码
- 违反不可变约束

PUBLICATION_READY = YES (with caveats)
- 许可证已文档化（两种 regime）
- 版本 UNKNOWN 是已知限制，已记录
- README 仍需更新（V3 时代内容）
- 历史 BoringSSL 已覆盖
- 无发布阻断项
```

---

## 操作记录

```text
SOURCE_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 0
LICENSE_FILES_CHANGED = 3
COMMITS_CREATED = 1 (db8a181)
TAGS_CREATED_OR_MOVED = 0
PUSH = NO
```

**STOP.**
