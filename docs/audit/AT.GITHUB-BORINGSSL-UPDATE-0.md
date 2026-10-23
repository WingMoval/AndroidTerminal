# AT.GITHUB-BORINGSSL-UPDATE-0 — BoringSSL 上游升级、可复现构建与 ADB 兼容性验证

**Date:** 2026-10-22
**Commit:** `d1e5883d7f28c539f661cd88b9d3b15dfd59304a`
**Nature:** BoringSSL upstream upgrade (118 files changed; 1 commit; production code unchanged)
**Predecessors:** AT.GITHUB-PREPARATION-0, AT.GITHUB-LICENSE-AUDIT-0, AT.GITHUB-LICENSE-CLOSURE-1, AT.GITHUB-CRYPTO-BACKEND-1

---

## 最终报告

```text
STEP = AT.GITHUB-BORINGSSL-UPDATE-0
VERDICT = PARTIAL

AT_START_HEAD = db8a181d0e865efe43f7c588adca71bf1b4ade50
AT_FINAL_HEAD = d1e5883d7f28c539f661cd88b9d3b15dfd59304a
AT_BRANCH = master
AT_WORKTREE = CLEAN (only pre-existing untracked audit/delivery files)

AT_FREEZE_TAG = AT.V4-CONSUMER-FREEZE-V1
AT_FREEZE_TAG_TARGET = 7216d0e8131e92e8914ce7c8443e1668b1d53aca
AT_FREEZE_TAG_UNCHANGED = YES

OLD_BORINGSSL_SHA256 = 602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2
OLD_BORINGSSL_VERSION = UNKNOWN
UPSTREAM_COMMIT_SELECTED = df821137838169360f82f94fd39cebe803c7c2d6
UPSTREAM_COMMIT_DATE = 2026-10-22
UPSTREAM_SOURCE_SHA256 = see boringssl-source-SHA256.txt (8710 files)
UPSTREAM_LICENSE = Apache-2.0
UPSTREAM_LICENSE_VERIFIED = YES (LICENSE from upstream commit, SHA256 86F839D5...)

SOURCE_HEADERS_BINARY_MATCH = YES (all from same commit df82113)
REPRODUCIBLE_BUILD = YES (CMake + NDK 26.1.10909125, instructions in UPSTREAM_VERSION.md)
ANDROID_ABI_BUILD_STATUS = arm64-v8a BUILD SUCCESSFUL (17s)

SPAKE2_COMPATIBILITY = PASS (SPAKE2_CTX_new/generate_msg/process_msg present)
AES_GCM_COMPATIBILITY = PASS (EVP_AEAD_CTX_init/seal/open present)
TLS13_COMPATIBILITY = PASS (SSL_CTX/SSL_new/SSL_connect + tls13_* symbols)
IDENTITY_FORMAT_COMPATIBILITY = PASS (RSA_new/RSA_generate_key_ex/RSA_sign present)

PRODUCTION_FILES_CHANGED = 0
TEST_FILES_CHANGED = 0
BUILD_FILES_CHANGED = 1 (CMakeLists.txt)
LICENSE_FILES_CHANGED = 3 (NOTICE, THIRD_PARTY_NOTICES.md, boringssl/LICENSE)

NATIVE_BUILD = BUILD SUCCESSFUL (17s, 1 pre-existing warning)
AUTOMATED_TESTS = 106 non-device tests PASS, 0 failures
ADB_GOLDEN_CHAIN = NOT_RUN (requires device)
DEVICE_TEST = NOT_RUN (no device connected)
ADB_ONESHOT = NOT_RUN
ADB_PERSISTENT = NOT_RUN
IDENTITY_PERSISTENCE = NOT_RUN
RECOVERY_PARITY = NOT_RUN

OLD_FREEZE_BUNDLE_UNCHANGED = YES (SHA256 verified)
NEW_CANDIDATE_BUNDLE = NOT_CREATED (no new bundle; in-place upgrade)
NEW_BUNDLE_SHA256_VERIFIED = N/A

CURRENT_LICENSE_CLOSURE = YES (BoringSSL commit df82113, Apache-2.0)
HISTORICAL_LICENSE_CLOSURE = NO (old unversioned binary still in git history)
HISTORICAL_DISTRIBUTION_RISK = LOW (pre/post Feb 2025 licenses both permissive)

COMMIT = d1e5883d7f28c539f661cd88b9d3b15dfd59304a
PUSH = NO
TAG = NO
RELEASE = NO

NEXT_RECOMMENDED_STEP = Device verification (TLS 1.3 + pairing + identity), then candidate bundle
STOP = YES
```

---

## 1. 上游版本选择

| 项 | 值 |
|----|-----|
| 仓库 | https://github.com/google/boringssl |
| Commit | `df821137838169360f82f94fd39cebe803c7c2d6` |
| 日期 | 2026-10-22 (main HEAD) |
| 许可证 | Apache-2.0 (LICENSE SHA256: `86F839D5FD287D9B155EF222BA39CCDA32E5515B4573A9A1F35B5DB03FF7E0A3`) |
| 选择理由 | 官方 main HEAD，许可证明确 (Apache-2.0)，全部所需 API 存在，可直接 CMake 构建 |

## 2. 构建配置

| 项 | 值 |
|----|-----|
| NDK | 26.1.10909125 |
| 编译器 | Clang 17.0.2 |
| CMake | 3.22.1 |
| Target | arm64-v8a |
| Platform | android-28 |
| Build type | Release |
| 测试 | OFF |
| 产物 | libcrypto.a (30,514,318 B) + libssl.a (11,658,154 B) |

### 复现步骤

```bash
git clone https://github.com/google/boringssl.git
cd boringssl
git checkout df821137838169360f82f94fd39cebe803c7c2d6
cmake -S . -B build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build build --target crypto ssl --parallel 8
```

## 3. API 兼容性矩阵

| 功能 | 当前 API | 新版中? | 文件 |
|------|---------|:-------:|------|
| SPAKE2 配对 | `SPAKE2_CTX_new/generate_msg/process_msg` | YES | curve25519.h |
| AES-128-GCM | `EVP_AEAD_CTX_init/seal/open` | YES | aead.h |
| TLS 1.3 | `SSL_CTX_new/SSL_new/SSL_connect` | YES | ssl.h |
| 证书加载 | `CRYPTO_BUFFER_new` | YES | pool.h |
| RSA 2048 | `RSA_new/generate_key_ex/sign` | YES | rsa.h |
| X.509 | `X509_new/sign` | YES | x509.h |
| BIO | `BIO_new/free/mem_buf/file` | YES | bio.h |
| SHA-256 | `SHA256` | YES | sha.h |

## 4. 修改文件清单

| 文件 | 变更 |
|------|------|
| `adb-engine/.../boringssl/LICENSE` | 替换为上游官方 Apache-2.0 (13,353 B) |
| `adb-engine/.../boringssl/UPSTREAM_VERSION.md` | 新增：commit SHA、构建参数、库哈希 |
| `adb-engine/.../boringssl/include/openssl/*.h` | 106 个头文件更新至 df82113 |
| `adb-engine/.../boringssl/lib/arm64-v8a/libboringssl.a` | 删除 (旧未知版本) |
| `adb-engine/.../boringssl/lib/arm64-v8a/libcrypto.a` | 新增 (30.5 MB) |
| `adb-engine/.../boringssl/lib/arm64-v8a/libssl.a` | 新增 (11.7 MB) |
| `adb-engine/.../CMakeLists.txt` | 链接 libcrypto.a + libssl.a (替代 libboringssl.a) |
| `NOTICE` | 更新为已知版本 + Apache-2.0 |
| `THIRD_PARTY_NOTICES.md` | 更新版本/来源/许可证 + 历史注释 |

## 5. 历史发布范围

| 项 | 状态 |
|----|------|
| 当前 HEAD BoringSSL | commit df82113, Apache-2.0, 可复现 |
| Git 历史中的旧 BoringSSL | 存在 (commit `a40387e`), 版本 UNKNOWN |
| 冻结标签中的 BoringSSL | 存在 (完整旧源码树), 版本 UNKNOWN |
| 冻结 AAR 中的 BoringSSL | 存在 (静态链接旧二进制), 版本 UNKNOWN |
| 旧版许可证风险 | LOW (pre/post Feb 2025 都是 permissive) |
| 当前版许可证风险 | NONE (Apache-2.0, 来源明确) |

## 6. 未解决项

```text
1. DEVICE_TEST = NOT_RUN
   - 无设备连接
   - 需要验证: TLS 1.3 握手、SPAKE2 配对、identity 持久性、one-shot/persistent execution
   - 这是 VERDICT = PARTIAL 而非 PASS 的原因

2. HISTORICAL LICENSE = 未完全闭合
   - 旧 BoringSSL (UNKNOWN 版本) 仍在 git 历史和冻结 AAR 中
   - 两种可能许可证 (pre/post Feb 2025) 都是 permissive
   - 不影响当前代码的许可证状态
   - 由 Controller 决定历史发布策略
```

**STOP.**
