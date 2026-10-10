// Self-signed X.509 certificate generation.
//
// Ported from AOSP packages/modules/adb/crypto/x509_generator.cpp
// (android15-qpr2). NDK-adapted: BoringSSL via NDK libcrypto.
//
// Reference:
//   - crypto/x509_generator.cpp:59-104  (GenerateX509Certificate)
//   - crypto/x509_generator.cpp:37       (kCertLifetimeSeconds)
//   - crypto/x509_generator.cpp:82-97    (subject, issuer, sign)

#pragma once

#include <string>
#include <string_view>

#include <openssl/evp.h>
#include <openssl/x509.h>

namespace adb {
namespace crypto {

// Generate a self-signed X.509 v3 certificate from the given key.
//
// Subject/Issuer DN: C=US, O=Android, CN=Adb
// Validity: 10 years
// Extensions: basicConstraints=CA:TRUE, keyUsage=keyCertSign,cRLSign,digitalSignature
// Signature: SHA-256
//
// Returns nullptr on failure. Caller owns the returned X509*.
X509* GenerateX509Certificate(EVP_PKEY* pkey);

// Convert an X509 certificate to a PEM string.
// Returns empty string on failure.
std::string X509ToPEMString(X509* x509);

}  // namespace crypto
}  // namespace adb
