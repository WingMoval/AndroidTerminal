// PairingAesGcm — AES-128-GCM helper for AOSP Wireless ADB pairing PeerInfo.
//
// Reference: AOSP pairing_auth/aes_128_gcm.cpp.
//   - HKDF-SHA256 derives a 16-byte AES key from the SPAKE2-derived key material.
//       info  = "adb pairing_auth aes-128-gcm key"
//       salt  = empty
//   - EVP_AEAD_CTX_init with EVP_aead_aes_128_gcm(), default tag length (16).
//   - Nonce = 64-bit sequence number zero-padded to a 12-byte GCM nonce,
//       incremented per message (starts at 0 for the first message each role).
//   - No AAD (ad_len = 0) in AOSP pairing_auth.
//
// This is a thin wrapper over BoringSSL EVP_AEAD. Do not reimplement GCM.

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace adb {
namespace pairing {

class PairingAesGcm {
public:
    PairingAesGcm();
    ~PairingAesGcm();

    PairingAesGcm(const PairingAesGcm&) = delete;
    PairingAesGcm& operator=(const PairingAesGcm&) = delete;

    // Derive the AES-128 key from SPAKE2 output |key_material| (32 bytes) via
    // HKDF-SHA256 and initialize the AEAD context. Returns false on failure.
    bool init(const uint8_t* key_material, size_t key_material_len);

    // Encrypt |plain| with the current (then-incremented) nonce. Appends the
    // 16-byte GCM tag. Returns empty on failure.
    std::vector<uint8_t> encrypt(const uint8_t* plain, size_t plain_len);

    // Decrypt |cipher| (cipher || tag) with the current (then-incremented)
    // nonce. Returns empty on failure (incl. bad tag).
    std::vector<uint8_t> decrypt(const uint8_t* cipher, size_t cipher_len);

    // Reset the per-direction nonce counters (used at the start of a session).
    void reset();

private:
    struct Impl;
    Impl* impl_ = nullptr;
};

}  // namespace pairing
}  // namespace adb
