// RSA-2048 key generation + android_pubkey encoding.
//
// Ported from AOSP packages/modules/adb/crypto/rsa_2048_key.cpp
// (android15-qpr2) + libcrypto_utils/android_pubkey.c.
// NDK-adapted:
//   - No libcrypto_utils dependency: android_pubkey_encode inlined here.
//   - No sysdeps/env dependency: comment passed as argument.
//   - BoringSSL via NDK libcrypto.

#include "adb/crypto/rsa_2048_key.h"

#include <android/log.h>

#include <cstring>
#include <vector>

#include <openssl/base.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/rsa.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {
namespace crypto {

namespace {

// android_pubkey binary layout — VERIFIED byte-for-byte against a device-
// trusted official key (PC adbkey) by decoding, all fields matched:
//   struct android_pubkey {         // sizeof = 524 for RSA-2048
//       uint32_t len;               // 4B LE, modulus length in 32-bit words (64)
//       uint32_t n0inv;             // 4B LE, -n^{-1} mod 2^32
//       uint8_t  n[256];            // modulus, LITTLE-ENDIAN
//       uint8_t  rr[256];           // R^2 mod n (R=2^2048), LITTLE-ENDIAN
//       uint32_t exponent;          // 4B LE, = 65537
//   };
// NOTE (AT.C-1 fix): the previous version wrote modulus big-endian starting at
// offset 4 with n0inv at 260, which the device could not parse into the same
// RSA public key as the client certificate -> CERTIFICATE_VERIFY_FAILED.
constexpr size_t kModulusSize = 256;                    // bytes
constexpr uint32_t kModulusSizeWords = 64;              // 32-bit words
constexpr size_t kAndroidPubkeyEncodedSize = 524;
constexpr size_t kAndroidPubkeyLenOffset = 0;
constexpr size_t kAndroidPubkeyN0invOffset = 4;
constexpr size_t kAndroidPubkeyModulusOffset = 8;
constexpr size_t kAndroidPubkeyRrOffset = 8 + 256;
constexpr size_t kAndroidPubkeyExponentOffset = 8 + 256 + 256;

// Base64-encode (standard, with padding). BoringSSL does not expose a simple
// base64 encode in its public headers, so we implement it directly.
std::string base64Encode(const uint8_t* data, size_t len) {
    static const char kB64Chars[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string b64;
    b64.reserve((len + 2) / 3 * 4);
    for (size_t i = 0; i < len; i += 3) {
        uint32_t triple = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) triple |= static_cast<uint32_t>(data[i + 1]) << 8;
        if (i + 2 < len) triple |= static_cast<uint32_t>(data[i + 2]);
        b64 += kB64Chars[(triple >> 18) & 0x3f];
        b64 += kB64Chars[(triple >> 12) & 0x3f];
        b64 += (i + 1 < len) ? kB64Chars[(triple >> 6) & 0x3f] : '=';
        b64 += (i + 2 < len) ? kB64Chars[triple & 0x3f] : '=';
    }
    return b64;
}

}  // namespace

EVP_PKEY* CreateRSA2048Key() {
    bssl::UniquePtr<EVP_PKEY> pkey(EVP_PKEY_new());
    if (!pkey) {
        LOGE("CreateRSA2048Key: EVP_PKEY_new failed");
        return nullptr;
    }

    bssl::UniquePtr<RSA> rsa(RSA_new());
    if (!rsa) {
        LOGE("CreateRSA2048Key: RSA_new failed");
        return nullptr;
    }

    bssl::UniquePtr<BIGNUM> exponent(BN_new());
    if (!exponent) {
        LOGE("CreateRSA2048Key: BN_new failed");
        return nullptr;
    }
    BN_set_word(exponent.get(), RSA_F4);  // 65537

    if (!RSA_generate_key_ex(rsa.get(), 2048, exponent.get(), nullptr)) {
        LOGE("CreateRSA2048Key: RSA_generate_key_ex(2048) failed");
        return nullptr;
    }

    if (!EVP_PKEY_set1_RSA(pkey.get(), rsa.get())) {
        LOGE("CreateRSA2048Key: EVP_PKEY_set1_RSA failed");
        return nullptr;
    }

    LOGI("CreateRSA2048Key: generated RSA-2048 key (e=65537)");
    return pkey.release();
}

std::string CalculatePublicKey(EVP_PKEY* pkey, const std::string& comment) {
    if (!pkey) return "";

    const RSA* rsa = EVP_PKEY_get0_RSA(pkey);
    if (!rsa) {
        LOGE("CalculatePublicKey: EVP_PKEY_get0_RSA failed");
        return "";
    }

    const BIGNUM* n = RSA_get0_n(rsa);
    const BIGNUM* e = RSA_get0_e(rsa);
    if (!n || !e) {
        LOGE("CalculatePublicKey: RSA_get0_n/e failed");
        return "";
    }

    int n_len = BN_num_bytes(n);
    int e_len = BN_num_bytes(e);
    if (n_len <= 0 || n_len > 256 || e_len <= 0 || e_len > 8) {
        LOGE("CalculatePublicKey: invalid n_len=%d e_len=%d", n_len, e_len);
        return "";
    }

    // Build the binary android_pubkey blob (524 bytes for RSA-2048).
    // Layout (VERIFIED): len(4 LE) | n0inv(4 LE) | n(256 LE) | rr(256 LE) | exp(4 LE)
    std::vector<uint8_t> blob(kAndroidPubkeyEncodedSize, 0);

    // 1. len (little-endian, in 32-bit words = 64 for RSA-2048).
    blob[kAndroidPubkeyLenOffset + 0] = static_cast<uint8_t>(kModulusSizeWords & 0xff);
    blob[kAndroidPubkeyLenOffset + 1] = static_cast<uint8_t>((kModulusSizeWords >> 8) & 0xff);
    blob[kAndroidPubkeyLenOffset + 2] = static_cast<uint8_t>((kModulusSizeWords >> 16) & 0xff);
    blob[kAndroidPubkeyLenOffset + 3] = static_cast<uint8_t>((kModulusSizeWords >> 24) & 0xff);

    // 2. n0inv = -n^{-1} mod 2^32 (little-endian at offset 4).
    {
        // n is an odd RSA modulus, so its low 32 bits are odd and invertible
        // mod 2^32. Newton's method: inv = inv*(2 - n*inv) mod 2^32.
        bssl::UniquePtr<BIGNUM> n_low32(BN_new());
        if (!n_low32) return "";
        BN_copy(n_low32.get(), n);
        BN_mask_bits(n_low32.get(), 32);
        uint32_t n_low = static_cast<uint32_t>(BN_get_word(n_low32.get()));
        uint32_t inv32 = 1;
        for (int i = 0; i < 5; ++i) {
            uint32_t prod = n_low * inv32;
            inv32 = inv32 * (2u - prod);
        }
        uint32_t n0inv = static_cast<uint32_t>(0u - inv32);  // -inv mod 2^32
        for (int i = 0; i < 4; ++i) {
            blob[kAndroidPubkeyN0invOffset + i] =
                static_cast<uint8_t>((n0inv >> (8 * i)) & 0xff);
        }
    }

    // 3. modulus (n, LITTLE-ENDIAN, 256 bytes).
    if (!BN_bn2le_padded(blob.data() + kAndroidPubkeyModulusOffset, kModulusSize, n)) {
        LOGE("CalculatePublicKey: modulus le_padded failed");
        return "";
    }

    // 4. rr = R^2 mod n, R = 2^2048 -> rr = 2^4096 mod n (LITTLE-ENDIAN, 256B).
    {
        bssl::UniquePtr<BN_CTX> bn_ctx(BN_CTX_new());
        bssl::UniquePtr<BIGNUM> rr(BN_new());
        if (!bn_ctx || !rr) {
            LOGE("CalculatePublicKey: rr alloc failed");
            return "";
        }
        if (!BN_set_bit(rr.get(), 2 * kModulusSize * 8)) {  // 2^4096
            LOGE("CalculatePublicKey: rr set_bit failed");
            return "";
        }
        if (!BN_mod(rr.get(), rr.get(), n, bn_ctx.get())) {
            LOGE("CalculatePublicKey: rr = 2^4096 mod n failed");
            return "";
        }
        if (!BN_bn2le_padded(blob.data() + kAndroidPubkeyRrOffset, kModulusSize, rr.get())) {
            LOGE("CalculatePublicKey: rr le_padded failed");
            return "";
        }
    }

    // 5. exponent (little-endian, 65537).
    uint64_t exp_val = 0;
    if (BN_is_word(e, RSA_F4)) {
        exp_val = 65537;
    } else {
        // General case: read as little-endian.
        bssl::UniquePtr<BIGNUM> e_copy(BN_dup(e));
        if (!e_copy) return "";
        for (int i = 0; i < 8 && BN_num_bits(e_copy.get()) > 0; ++i) {
            exp_val |= static_cast<uint64_t>(BN_get_word(e_copy.get()) & 0xff) << (8 * i);
            BN_rshift(e_copy.get(), e_copy.get(), 8);
        }
    }
    for (int i = 0; i < 4; ++i) {
        blob[kAndroidPubkeyExponentOffset + i] =
            static_cast<uint8_t>((exp_val >> (8 * i)) & 0xff);
    }

    // Base64-encode and append the comment.
    std::string result = base64Encode(blob.data(), blob.size()) + " " + comment;
    LOGI("CalculatePublicKey: android_pubkey blob=%zu b64=%zu (modulus 256B)",
         blob.size(), result.size());
    return result;
}

}  // namespace crypto
}  // namespace adb
