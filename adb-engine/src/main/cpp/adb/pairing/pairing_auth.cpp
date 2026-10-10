// PairingAuth implementation (SPAKE2, AOSP pairing_auth/pairing_auth.cpp).

#include "adb/pairing/pairing_auth.h"

#include <android/log.h>

#include <cstring>

#include <openssl/base.h>
#include <openssl/curve25519.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {
namespace pairing {

namespace {

// AOSP pairing_auth.cpp:37-38 — role + party name literals.
// CRITICAL: AOSP passes sizeof(kClientName)=15 (14 chars + NUL) to
// SPAKE2_CTX_new, i.e. the NUL terminator IS part of the SPAKE2 transcript.
// Use sizeof(), NOT strlen(), to match byte-for-byte.
constexpr char kClientName[] = "adb pair client";  // sizeof = 15 (incl NUL)
constexpr char kServerName[] = "adb pair server";  // sizeof = 15 (incl NUL)
constexpr size_t kSpake2MsgSize = SPAKE2_MAX_MSG_SIZE;  // 32
// CRITICAL (AT.C-1): AOSP pairing_auth.cpp:151-153 uses a
// SPAKE2_MAX_KEY_SIZE(64)-byte buffer + sizeof(buffer) as max_out_key_len.
// Match AOSP exactly: some BoringSSL builds emit 64-byte key material, and
// truncating to 32 would produce a DIFFERENT AES-GCM key -> bad tag.
constexpr size_t kSpake2KeySize = SPAKE2_MAX_KEY_SIZE;  // 64

}  // namespace

struct PairingAuth::Impl {
    SPAKE2_CTX* ctx = nullptr;  // opaque type; freed explicitly
    bool used = false;
    bool client;

    explicit Impl(bool is_client) : client(is_client) {
        // AOSP pairing_auth.cpp:116 — SPAKE2_CTX_new with role + party names.
        // client = alice (first message), server = bob.
        if (is_client) {
            ctx = SPAKE2_CTX_new(spake2_role_alice,
                                 reinterpret_cast<const uint8_t*>(kClientName),
                                 sizeof(kClientName),
                                 reinterpret_cast<const uint8_t*>(kServerName),
                                 sizeof(kServerName));
        } else {
            ctx = SPAKE2_CTX_new(spake2_role_bob,
                                 reinterpret_cast<const uint8_t*>(kServerName),
                                 sizeof(kServerName),
                                 reinterpret_cast<const uint8_t*>(kClientName),
                                 sizeof(kClientName));
        }
    }

    ~Impl() {
        if (ctx) {
            SPAKE2_CTX_free(ctx);
            ctx = nullptr;
        }
    }
};

PairingAuth::PairingAuth(bool role_is_client) : impl_(new Impl(role_is_client)) {}
PairingAuth::~PairingAuth() { delete impl_; }

std::vector<uint8_t> PairingAuth::generateMsg(const uint8_t* password,
                                              size_t password_len) {
    if (!impl_ || !impl_->ctx || impl_->used) return {};
    std::vector<uint8_t> out(kSpake2MsgSize);
    size_t out_len = 0;
    // AOSP pairing_auth.cpp:125-126 — SPAKE2_generate_msg.
    if (!SPAKE2_generate_msg(impl_->ctx, out.data(), &out_len,
                             kSpake2MsgSize, password, password_len)) {
        LOGE("PairingAuth::generateMsg: SPAKE2_generate_msg failed");
        return {};
    }
    impl_->used = true;
    out.resize(out_len);
    LOGI("PairingAuth::generateMsg: %zu-byte SPAKE2 msg (role=%s)",
         out_len, impl_->client ? "alice/client" : "bob/server");
    return out;
}

std::vector<uint8_t> PairingAuth::processMsg(const uint8_t* their_msg,
                                             size_t their_msg_len) {
    if (!impl_ || !impl_->ctx || !impl_->used) return {};
    std::vector<uint8_t> key(kSpake2KeySize);
    size_t key_len = 0;
    // AOSP pairing_auth.cpp:152-153 — SPAKE2_process_msg.
    if (!SPAKE2_process_msg(impl_->ctx, key.data(), &key_len,
                            kSpake2KeySize, their_msg, their_msg_len)) {
        LOGE("PairingAuth::processMsg: SPAKE2_process_msg failed (wrong code?)");
        return {};
    }
    key.resize(key_len);
    LOGI("PairingAuth::processMsg: derived %zu-byte key material", key_len);
    return key;
}

bool PairingAuth::valid() const {
    return impl_ && impl_->ctx;
}

}  // namespace pairing
}  // namespace adb
