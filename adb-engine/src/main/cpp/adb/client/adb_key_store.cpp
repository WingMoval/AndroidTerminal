// AdbKeyStore implementation.
//
// Ported from AOSP client/auth.cpp + crypto/rsa_2048_key.cpp + x509_generator.cpp.

#include "adb/client/adb_key_store.h"

#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <sys/stat.h>

#include <openssl/base.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/x509.h>

#include "adb/crypto/rsa_2048_key.h"
#include "adb/crypto/x509_generator.h"

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

namespace {

constexpr const char* kAdbKeyFile = "adbkey";
constexpr const char* kAdbKeyPubFile = "adbkey.pub";
constexpr const char* kCertFile = "adb_cert.pem";
constexpr const char* kComment = "androidterminal@app";

}  // namespace

struct AdbKeyStore::Impl {
    std::string dir;
    EVP_PKEY* pkey = nullptr;
    std::string priv_pem;
    std::string pub_key;
    std::string cert_pem;

    ~Impl() {
        if (pkey) EVP_PKEY_free(pkey);
    }
};

AdbKeyStore::AdbKeyStore(std::string dir) : impl_(new Impl()) {
    impl_->dir = std::move(dir);
}

AdbKeyStore::~AdbKeyStore() {
    delete impl_;
}

bool AdbKeyStore::loadOrCreate() {
    if (loaded_) return true;

    std::string key_path = impl_->dir + "/" + kAdbKeyFile;
    std::string pub_path = impl_->dir + "/" + kAdbKeyPubFile;
    std::string cert_path = impl_->dir + "/" + kCertFile;

    // Check if key exists
    {
        std::ifstream f(key_path);
        if (!f.good()) {
            // Generate new key
            LOGI("AdbKeyStore: %s not found, generating RSA-2048", key_path.c_str());
            impl_->pkey = crypto::CreateRSA2048Key();
            if (!impl_->pkey) {
                LOGE("AdbKeyStore: CreateRSA2048Key failed");
                return false;
            }

            // Write private key (PKCS#8 PEM)
            BIO* bio = BIO_new_file(key_path.c_str(), "w");
            if (!bio) {
                LOGE("AdbKeyStore: cannot open %s for write", key_path.c_str());
                return false;
            }
            PEM_write_bio_PrivateKey(bio, impl_->pkey, nullptr, nullptr, 0, nullptr, nullptr);
            BIO_free(bio);
            chmod(key_path.c_str(), 0600);

            // Compute + write public key
            impl_->pub_key = crypto::CalculatePublicKey(impl_->pkey, kComment);
            {
                std::ofstream pf(pub_path);
                pf << impl_->pub_key << "\n";
            }

            // Generate + write cert
            X509* x509 = crypto::GenerateX509Certificate(impl_->pkey);
            if (x509) {
                impl_->cert_pem = crypto::X509ToPEMString(x509);
                {
                    std::ofstream cf(cert_path);
                    cf << impl_->cert_pem;
                }
                X509_free(x509);
            }

            // Read back private key PEM
            std::ifstream kf(key_path);
            std::ostringstream ss;
            ss << kf.rdbuf();
            impl_->priv_pem = ss.str();

            LOGI("AdbKeyStore: generated new key, pub=%zu chars, cert=%zu chars",
                 impl_->pub_key.size(), impl_->cert_pem.size());
            loaded_ = true;
            return true;
        }
        f.close();
    }

    // Load existing key
    LOGI("AdbKeyStore: loading existing key from %s", key_path.c_str());
    BIO* bio = BIO_new_file(key_path.c_str(), "r");
    if (!bio) {
        LOGE("AdbKeyStore: cannot open %s", key_path.c_str());
        return false;
    }
    impl_->pkey = PEM_read_bio_PrivateKey(bio, nullptr, nullptr, nullptr);
    BIO_free(bio);
    if (!impl_->pkey) {
        LOGE("AdbKeyStore: PEM_read_bio_PrivateKey failed");
        return false;
    }

    // Read public key
    {
        std::ifstream pf(pub_path);
        std::ostringstream ss;
        ss << pf.rdbuf();
        impl_->pub_key = ss.str();
        if (!impl_->pub_key.empty() && impl_->pub_key.back() == '\n')
            impl_->pub_key.pop_back();
    }

    // Read cert (or regenerate)
    {
        std::ifstream cf(cert_path);
        std::ostringstream ss;
        ss << cf.rdbuf();
        impl_->cert_pem = ss.str();
    }
    if (impl_->cert_pem.empty()) {
        X509* x509 = crypto::GenerateX509Certificate(impl_->pkey);
        if (x509) {
            impl_->cert_pem = crypto::X509ToPEMString(x509);
            X509_free(x509);
        }
    }

    // Read private key PEM
    {
        std::ifstream kf(key_path);
        std::ostringstream ss;
        ss << kf.rdbuf();
        impl_->priv_pem = ss.str();
    }

    LOGI("AdbKeyStore: loaded existing key, pub=%zu chars, cert=%zu chars",
         impl_->pub_key.size(), impl_->cert_pem.size());
    loaded_ = true;
    return true;
}

std::string AdbKeyStore::privateKeyPem() const {
    return impl_->priv_pem;
}

std::string AdbKeyStore::publicKey() const {
    return impl_->pub_key;
}

std::string AdbKeyStore::certificate() const {
    return impl_->cert_pem;
}

std::string AdbKeyStore::signSha1(const uint8_t* token, size_t token_len) const {
    if (!impl_->pkey || token_len != 20) {
        LOGE("AdbKeyStore::signSha1: invalid token len=%zu", token_len);
        return "";
    }

    const RSA* rsa = EVP_PKEY_get0_RSA(impl_->pkey);
    if (!rsa) {
        LOGE("AdbKeyStore::signSha1: EVP_PKEY_get0_RSA failed");
        return "";
    }

    uint8_t sig[256];
    unsigned int sig_len = 0;
    if (!RSA_sign(NID_sha1, token, token_len, sig, &sig_len,
                  const_cast<RSA*>(rsa))) {
        LOGE("AdbKeyStore::signSha1: RSA_sign failed");
        return "";
    }

    LOGI("AdbKeyStore::signSha1: signed %zu bytes -> %u bytes", token_len, sig_len);
    return std::string(reinterpret_cast<char*>(sig), sig_len);
}

}  // namespace adb
