// Self-signed X.509 certificate generation.
//
// Ported from AOSP packages/modules/adb/crypto/x509_generator.cpp
// (android15-qpr2). NDK-adapted: BoringSSL via NDK libcrypto.

#include "adb/crypto/x509_generator.h"

#include <android/log.h>

#include <cstring>
#include <vector>

#include <openssl/base.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {
namespace crypto {

namespace {

// 10-year certificate lifetime (AOSP x509_generator.cpp:37).
constexpr long kCertLifetimeSeconds = 10L * 365 * 24 * 60 * 60;

const char kBasicConstraints[] = "critical,CA:TRUE";
const char kKeyUsage[] = "critical,keyCertSign,cRLSign,digitalSignature";
const char kSubjectKeyIdentifier[] = "hash";

bool add_ext(X509* cert, int nid, const char* value) {
    size_t len = strlen(value) + 1;
    std::vector<char> mutableValue(value, value + len);
    X509V3_CTX context;
    X509V3_set_ctx_nodb(&context);
    X509V3_set_ctx(&context, cert, cert, nullptr, nullptr, 0);
    X509_EXTENSION* ex = X509V3_EXT_nconf_nid(nullptr, &context, nid, mutableValue.data());
    if (!ex) {
        return false;
    }
    X509_add_ext(cert, ex, -1);
    X509_EXTENSION_free(ex);
    return true;
}

}  // namespace

X509* GenerateX509Certificate(EVP_PKEY* pkey) {
    if (!pkey) {
        LOGE("GenerateX509Certificate: null pkey");
        return nullptr;
    }

    bssl::UniquePtr<X509> x509(X509_new());
    if (!x509) {
        LOGE("GenerateX509Certificate: X509_new failed");
        return nullptr;
    }

    // X.509 v3
    X509_set_version(x509.get(), 2);

    // Serial number = 1
    ASN1_INTEGER_set(X509_get_serialNumber(x509.get()), 1);

    // Validity: now .. now + 10 years
    X509_gmtime_adj(X509_get_notBefore(x509.get()), 0);
    X509_gmtime_adj(X509_get_notAfter(x509.get()), kCertLifetimeSeconds);

    // Public key
    if (!X509_set_pubkey(x509.get(), pkey)) {
        LOGE("GenerateX509Certificate: X509_set_pubkey failed");
        X509_free(x509.release());
        return nullptr;
    }

    // Subject DN: C=US, O=Android, CN=Adb
    // (AOSP x509_generator.cpp:82-87)
    X509_NAME* name = X509_get_subject_name(x509.get());
    X509_NAME_add_entry_by_txt(name, "C", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("US"), -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "O", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("Android"), -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("Adb"), -1, -1, 0);

    // Issuer = Subject (self-signed) (AOSP x509_generator.cpp:88).
    // BoringSSL lacks X509_NAME_copy; use X509_NAME_dup + X509_set_issuer_name.
    {
        bssl::UniquePtr<X509_NAME> dup(X509_NAME_dup(name));
        if (dup) {
            if (!X509_set_issuer_name(x509.get(), dup.get())) {
                LOGE("GenerateX509Certificate: X509_set_issuer_name failed");
            }
        }
    }

    // X.509 v3 extensions (AOSP x509_generator.cpp:93-95)
    add_ext(x509.get(), NID_basic_constraints, kBasicConstraints);
    add_ext(x509.get(), NID_key_usage, kKeyUsage);
    add_ext(x509.get(), NID_subject_key_identifier, kSubjectKeyIdentifier);

    // Sign with SHA-256 (AOSP x509_generator.cpp:97)
    if (!X509_sign(x509.get(), pkey, EVP_sha256())) {
        LOGE("GenerateX509Certificate: X509_sign failed");
        X509_free(x509.release());
        return nullptr;
    }

    LOGI("GenerateX509Certificate: self-signed cert (C=US,O=Android,CN=Adb, SHA-256, 10y)");
    return x509.release();
}

std::string X509ToPEMString(X509* x509) {
    if (!x509) return "";

    BIO* bio = BIO_new(BIO_s_mem());
    if (!bio) {
        LOGE("X509ToPEMString: BIO_new failed");
        return "";
    }
    bool ok = PEM_write_bio_X509(bio, x509) == 1;
    if (!ok) {
        BIO_free(bio);
        LOGE("X509ToPEMString: PEM_write_bio_X509 failed");
        return "";
    }

    char* data = nullptr;
    long len = BIO_get_mem_data(bio, &data);
    std::string pem(data, static_cast<size_t>(len));
    BIO_free(bio);
    return pem;
}

}  // namespace crypto
}  // namespace adb
