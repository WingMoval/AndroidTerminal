// PairingAesGcm implementation.
//
// Ported from AOSP pairing_auth/aes_128_gcm.cpp (HKDF-SHA256 key derivation +
// EVP_AEAD AES-128-GCM with a 64-bit incrementing nonce zero-padded to 12 bytes).

#include "adb/pairing/pairing_aes_gcm.h"

#include <android/log.h>

#include <cstring>

#include <openssl/aead.h>
#include <openssl/evp.h>
#include <openssl/hkdf.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {
namespace pairing {

namespace {

// AOSP pairing_auth/aes_128_gcm.cpp:42-43 — HKDF info + empty salt.
constexpr const char* kHkdfInfo = "adb pairing_auth aes-128-gcm key";

// AES-128 key length.
constexpr size_t kAesKeyLen = 16;
// GCM nonce length (12 bytes for AES-GCM).
constexpr size_t kGcmNonceLen = 12;
// 64-bit sequence zero-padded into the 12-byte nonce (AOSP aes_128_gcm.cpp:52-53).
constexpr size_t kNonceCounterLen = 8;

// Build the 12-byte GCM nonce from a 64-bit counter.
// Matches AOSP aes_128_gcm.cpp:52-53,68-69 EXACTLY:
//   std::vector<uint8_t> nonce(12, 0);
//   memcpy(nonce.data(), &seq, sizeof(seq));
// i.e. the sequence is copied NATIVE-endian into the FIRST 8 bytes of the
// 12-byte nonce; the last 4 bytes stay zero. (On ARM64 native == little-endian.)
void makeNonce(uint8_t nonce12[12], uint64_t seq) {
    std::memset(nonce12, 0, 12);
    nonce12[0] = static_cast<uint8_t>(seq & 0xff);
    nonce12[1] = static_cast<uint8_t>((seq >> 8) & 0xff);
    nonce12[2] = static_cast<uint8_t>((seq >> 16) & 0xff);
    nonce12[3] = static_cast<uint8_t>((seq >> 24) & 0xff);
    nonce12[4] = static_cast<uint8_t>((seq >> 32) & 0xff);
    nonce12[5] = static_cast<uint8_t>((seq >> 40) & 0xff);
    nonce12[6] = static_cast<uint8_t>((seq >> 48) & 0xff);
    nonce12[7] = static_cast<uint8_t>((seq >> 56) & 0xff);
}

}  // namespace

struct PairingAesGcm::Impl {
    EVP_AEAD_CTX ctx;
    bool ready = false;
    uint64_t enc_seq = 0;
    uint64_t dec_seq = 0;

    ~Impl() { EVP_AEAD_CTX_cleanup(&ctx); }
};

PairingAesGcm::PairingAesGcm() : impl_(new Impl()) {}
PairingAesGcm::~PairingAesGcm() { delete impl_; }

bool PairingAesGcm::init(const uint8_t* key_material, size_t key_material_len) {
    if (!impl_ || !key_material || key_material_len == 0) {
        LOGE("PairingAesGcm::init: bad input (len=%zu)", key_material_len);
        return false;
    }
    uint8_t aes_key[kAesKeyLen];
    // AOSP aes_128_gcm.cpp:43-46 — HKDF-SHA256, empty salt, fixed info.
    if (!HKDF(aes_key, kAesKeyLen, EVP_sha256(),
              key_material, key_material_len,
              nullptr, 0,
              reinterpret_cast<const uint8_t*>(kHkdfInfo), std::strlen(kHkdfInfo))) {
        LOGE("PairingAesGcm::init: HKDF failed");
        return false;
    }
    // AOSP aes_128_gcm.cpp:46-47 — AES-128-GCM, default tag length.
    if (!EVP_AEAD_CTX_init(&impl_->ctx, EVP_aead_aes_128_gcm(),
                           aes_key, kAesKeyLen, EVP_AEAD_DEFAULT_TAG_LENGTH,
                           nullptr)) {
        LOGE("PairingAesGcm::init: EVP_AEAD_CTX_init failed");
        return false;
    }
    // Zero the key from the stack.
    std::memset(aes_key, 0, sizeof(aes_key));
    impl_->ready = true;
    impl_->enc_seq = 0;
    impl_->dec_seq = 0;
    LOGI("PairingAesGcm::init: AES-128-GCM ready (from %zu-byte SPAKE2 key)",
         key_material_len);
    return true;
}

std::vector<uint8_t> PairingAesGcm::encrypt(const uint8_t* plain, size_t plain_len) {
    if (!impl_ || !impl_->ready || !plain) return {};
    uint8_t nonce[12];
    makeNonce(nonce, impl_->enc_seq);
    const size_t max_out = plain_len + EVP_AEAD_max_overhead(EVP_aead_aes_128_gcm());
    std::vector<uint8_t> out(max_out);
    size_t out_len = 0;
    int rc = EVP_AEAD_CTX_seal(&impl_->ctx, out.data(), &out_len, max_out,
                               nonce, kGcmNonceLen,
                               plain, plain_len,
                               nullptr, 0);  // no AAD (AOSP)
    impl_->enc_seq++;
    if (rc != 1) {
        LOGE("PairingAesGcm::encrypt: EVP_AEAD_CTX_seal failed");
        return {};
    }
    out.resize(out_len);
    return out;
}

std::vector<uint8_t> PairingAesGcm::decrypt(const uint8_t* cipher, size_t cipher_len) {
    if (!impl_ || !impl_->ready || !cipher) return {};
    uint8_t nonce[12];
    makeNonce(nonce, impl_->dec_seq);
    std::vector<uint8_t> out(cipher_len);  // decrypted <= cipher_len
    size_t out_len = 0;
    int rc = EVP_AEAD_CTX_open(&impl_->ctx, out.data(), &out_len, cipher_len,
                               nonce, kGcmNonceLen,
                               cipher, cipher_len,
                               nullptr, 0);  // no AAD (AOSP)
    impl_->dec_seq++;
    if (rc != 1) {
        LOGE("PairingAesGcm::decrypt: EVP_AEAD_CTX_open failed (bad tag?)");
        return {};
    }
    out.resize(out_len);
    return out;
}

void PairingAesGcm::reset() {
    if (impl_) {
        impl_->enc_seq = 0;
        impl_->dec_seq = 0;
    }
}

}  // namespace pairing
}  // namespace adb
