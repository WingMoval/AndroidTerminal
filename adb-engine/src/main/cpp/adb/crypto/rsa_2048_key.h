// RSA-2048 key generation + android_pubkey encoding.
//
// Ported from AOSP packages/modules/adb/crypto/rsa_2048_key.cpp
// (android15-qpr2). NDK-adapted: uses BoringSSL (NDK libcrypto),
// no libcrypto_utils / sysdeps/env dependency.
//
// Reference:
//   - crypto/rsa_2048_key.cpp:52-66  (CreateRSA2048Key)
//   - crypto/rsa_2048_key.cpp:28-50  (CalculatePublicKey)

#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>

#include <openssl/evp.h>
#include <openssl/rsa.h>

namespace adb {
namespace crypto {

// Generate a new RSA-2048 keypair (e = 65537).
// Returns nullptr on failure.
EVP_PKEY* CreateRSA2048Key();

// Encode the public key of `pkey` into the android_pubkey base64 string
// (the format used in ~/.android/adbkey.pub on PC adb).
// Format: base64(0x01 || n[256] || e[4-8]) + " " + comment
// Returns empty string on failure.
std::string CalculatePublicKey(EVP_PKEY* pkey, const std::string& comment);

}  // namespace crypto
}  // namespace adb
