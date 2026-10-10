// TlsConnection implementation (NDK-adapted from AOSP tls/tls_connection.cpp).
//
// TLS 1.3 only, blocking handshake, BoringSSL via NDK libssl.

#include "adb/tls/tls_connection.h"

#include <android/log.h>

#include <cstring>
#include <cerrno>
#include <climits>
#include <memory>
#include <string>
#include <vector>

#include <sys/socket.h>

#include <openssl/base.h>
#include <openssl/bio.h>
#include <openssl/bytestring.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {
namespace tls {

namespace {

class TlsConnectionImpl : public TlsConnection {
public:
    TlsConnectionImpl(Role role, std::string_view cert,
                      std::string_view priv_key, int fd)
        : role_(role), fd_(fd) {
        // Parse PEM cert into CRYPTO_BUFFER.
        bssl::UniquePtr<BIO> cert_bio(
            BIO_new_mem_buf(cert.data(), static_cast<int>(cert.size())));
        if (!cert_bio) return;
        bssl::UniquePtr<X509> x509(PEM_read_bio_X509(cert_bio.get(), nullptr, nullptr, nullptr));
        if (!x509) {
            LOGE("TlsConnection: failed to parse PEM cert");
            return;
        }
        cert_pem_ = std::string(cert);

        bssl::UniquePtr<BIO> key_bio(
            BIO_new_mem_buf(priv_key.data(), static_cast<int>(priv_key.size())));
        if (!key_bio) return;
        key_pem_ = std::string(priv_key);

        cert_valid_ = true;
    }

    ~TlsConnectionImpl() override {
        if (ssl_) {
            SSL_shutdown(ssl_.get());
        }
        // SSL_free / SSL_CTX_free happen via UniquePtr reset at end of scope.
        // Don't close fd here — the transport owns it.
    }

    bool AddTrustedCertificate(std::string_view cert) override {
        trusted_certs_.push_back(std::string(cert));
        return true;
    }

    void SetCertVerifyCallback(CertVerifyCb cb) override {
        cert_verify_cb_ = std::move(cb);
    }

    void SetCertificateCallback(SetCertCb cb) override {
        set_cert_cb_ = std::move(cb);
    }

    std::vector<uint8_t> ExportKeyingMaterial(size_t length) override {
        if (!ssl_) return {};
        std::vector<uint8_t> out(length);
        // CRITICAL (AT.C-1): AOSP uses sizeof(kExportedKeyLabel) = 10 for the
        // label length — "adb-label" is a char ARRAY, so sizeof includes the
        // NUL terminator. The device derives its EKM with the 10-byte label
        // "adb-label\0"; using 9 would produce a DIFFERENT EKM -> different
        // SPAKE2 password -> AES-GCM PeerInfo decrypt fails (bad tag).
        constexpr char kExportedKeyLabel[] = "adb-label";
        if (!SSL_export_keying_material(ssl_.get(), out.data(), length,
                                        kExportedKeyLabel,
                                        sizeof(kExportedKeyLabel),
                                        nullptr, 0, 0)) {
            LOGE("ExportKeyingMaterial: SSL_export_keying_material failed");
            return {};
        }
        return out;
    }

    TlsError DoHandshake() override {
        if (!cert_valid_) {
            LOGE("DoHandshake: invalid cert/key");
            return TlsError::UnknownFailure;
        }

        bssl::UniquePtr<SSL_CTX> ssl_ctx(SSL_CTX_new(TLS_method()));
        if (!ssl_ctx) {
            LOGE("DoHandshake: SSL_CTX_new failed");
            return TlsError::UnknownFailure;
        }

        // TLS 1.3 only (AOSP tls_connection.cpp:221-222)
        if (!SSL_CTX_set_min_proto_version(ssl_ctx.get(), TLS1_3_VERSION) ||
            !SSL_CTX_set_max_proto_version(ssl_ctx.get(), TLS1_3_VERSION)) {
            LOGE("DoHandshake: failed to set TLS 1.3");
            return TlsError::UnknownFailure;
        }

        // Add trusted certs
        for (const auto& tc : trusted_certs_) {
            bssl::UniquePtr<BIO> bio(BIO_new_mem_buf(tc.data(), static_cast<int>(tc.size())));
            bssl::UniquePtr<X509> x509(PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr));
            if (x509) {
                X509_STORE_add_cert(SSL_CTX_get_cert_store(ssl_ctx.get()), x509.get());
            }
        }

        // Cert verify callback (AOSP tls_connection.cpp:236-238)
        if (cert_verify_cb_) {
            auto* cb_ptr = &cert_verify_cb_;
            SSL_CTX_set_cert_verify_callback(
                ssl_ctx.get(),
                [](X509_STORE_CTX* ctx, void* arg) -> int {
                    auto& cb = *static_cast<CertVerifyCb*>(arg);
                    return cb(ctx);
                },
                static_cast<void*>(cb_ptr));
        }

        // Cert selection callback at CTX level (AOSP tls_connection.cpp:241-243).
        // Must be set before SSL_new so it's available during ClientHello
        // construction in TLS 1.3.
        if (set_cert_cb_) {
            auto* cb_ptr = &set_cert_cb_;
            SSL_CTX_set_cert_cb(
                ssl_ctx.get(),
                [](SSL* s, void* arg) -> int {
                    auto& cb = *static_cast<SetCertCb*>(arg);
                    return cb(s);
                },
                static_cast<void*>(cb_ptr));
        }

        // Set cert + key using SSL_CTX_set_chain_and_key (AOSP tls_connection.cpp:252-262).
        // This ensures the cert is available during ClientHello construction in TLS 1.3.
        bssl::UniquePtr<BIO> cert_bio(
            BIO_new_mem_buf(cert_pem_.data(), static_cast<int>(cert_pem_.size())));
        bssl::UniquePtr<X509> x509(PEM_read_bio_X509(cert_bio.get(), nullptr, nullptr, nullptr));
        bssl::UniquePtr<BIO> key_bio(
            BIO_new_mem_buf(key_pem_.data(), static_cast<int>(key_pem_.size())));
        bssl::UniquePtr<EVP_PKEY> pkey(PEM_read_bio_PrivateKey(key_bio.get(), nullptr, nullptr, nullptr));

        if (!x509 || !pkey) {
            LOGE("DoHandshake: failed to parse cert/key");
            return TlsError::UnknownFailure;
        }

        // Convert X509 to DER for CRYPTO_BUFFER
        unsigned char* der = nullptr;
        int der_len = i2d_X509(x509.get(), &der);
        if (der_len <= 0) {
            LOGE("DoHandshake: i2d_X509 failed");
            return TlsError::UnknownFailure;
        }
        bssl::UniquePtr<CRYPTO_BUFFER> cert_buf(
            CRYPTO_BUFFER_new(der, der_len, nullptr));
        OPENSSL_free(der);

        if (!cert_buf) {
            LOGE("DoHandshake: CRYPTO_BUFFER_new failed for cert");
            return TlsError::UnknownFailure;
        }

        // Set chain (single cert) + key
        std::vector<CRYPTO_BUFFER*> chain = {cert_buf.get()};
        if (!SSL_CTX_set_chain_and_key(ssl_ctx.get(), chain.data(), chain.size(),
                                        pkey.get(), nullptr)) {
            LOGE("DoHandshake: SSL_CTX_set_chain_and_key failed");
            return TlsError::UnknownFailure;
        }
        LOGI("DoHandshake: cert+key set via SSL_CTX_set_chain_and_key "
             "(cert=%d bytes DER, key=OK)", der_len);

        // Verify peer (AOSP tls_connection.cpp:264)
        SSL_CTX_set_verify(ssl_ctx.get(),
                           SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT,
                           nullptr);

        bssl::UniquePtr<SSL> ssl(SSL_new(ssl_ctx.get()));
        if (!ssl) {
            LOGE("DoHandshake: SSL_new failed");
            return TlsError::UnknownFailure;
        }

        SSL_set_fd(ssl.get(), fd_);

        if (role_ == Role::Server) {
            SSL_set_accept_state(ssl.get());
        } else {
            SSL_set_connect_state(ssl.get());
        }

        LOGI("DoHandshake: calling SSL_do_handshake (client mode, TLS 1.3 only, "
              "cert_verify=PEER|FAIL_IF_NO_PEER, cert_cb=%s)",
              set_cert_cb_ ? "set" : "none");
        int rc = SSL_do_handshake(ssl.get());
        LOGI("DoHandshake: SSL_do_handshake returned rc=%d", rc);
        if (rc != 1) {
            int ssl_err = SSL_get_error(ssl.get(), rc);
            unsigned long bssl_err = ERR_peek_last_error();
            char err_buf[256];
            ERR_error_string_n(bssl_err, err_buf, sizeof(err_buf));

            int so_err = 0;
            int so_err_len = sizeof(so_err);
            getsockopt(fd_, SOL_SOCKET, SO_ERROR, &so_err, (socklen_t*)&so_err_len);
            int errno_val = errno;
            char errno_buf[256];
            strncpy(errno_buf, strerror(errno_val), sizeof(errno_buf) - 1);
            errno_buf[sizeof(errno_buf) - 1] = 0;

            LOGE("DoHandshake: SSL_do_handshake failed "
                 "ssl_error=%d boringssl_error=%lu (%s) "
                 "errno=%d (%s) so_error=%d",
                 ssl_err, bssl_err, err_buf,
                 errno_val, errno_buf, so_err);

            // Classify error
            if (ssl_err == SSL_ERROR_SSL) {
                if (bssl_err == SSL_R_CERTIFICATE_VERIFY_FAILED) {
                    return TlsError::CertificateRejected;
                }
            }
            ERR_clear_error();
            return TlsError::UnknownFailure;
        }

        ssl_ = std::move(ssl);
        ssl_ctx_ = std::move(ssl_ctx);
        LOGI("DoHandshake: TLS 1.3 handshake success (role=%s)",
              role_ == Role::Client ? "Client" : "Server");
        return TlsError::Success;
    }

    std::vector<uint8_t> ReadFully(size_t size) override {
        std::vector<uint8_t> buf(size);
        if (!ReadFully(buf.data(), size)) buf.clear();
        return buf;
    }

    bool ReadFully(void* buf, size_t size) override {
        if (!ssl_ || size == 0) return false;
        size_t total = 0;
        while (total < size) {
            int n = SSL_read(ssl_.get(), static_cast<char*>(buf) + total,
                              static_cast<int>(size - total));
            if (n > 0) {
                total += static_cast<size_t>(n);
            } else {
                int err = SSL_get_error(ssl_.get(), n);
                if (err == SSL_ERROR_WANT_READ) continue;
                LOGE("ReadFully: SSL_read failed after %zu/%zu: err=%d",
                      total, size, err);
                return false;
            }
        }
        return true;
    }

    bool WriteFully(std::string_view data) override {
        if (!ssl_ || data.empty()) return false;
        size_t total = 0;
        while (total < data.size()) {
            int n = SSL_write(ssl_.get(), data.data() + total,
                               static_cast<int>(data.size() - total));
            if (n > 0) {
                total += static_cast<size_t>(n);
            } else {
                int err = SSL_get_error(ssl_.get(), n);
                if (err == SSL_ERROR_WANT_WRITE) continue;
                LOGE("WriteFully: SSL_write failed after %zu/%zu: err=%d",
                      total, data.size(), err);
                return false;
            }
        }
        return true;
    }

    int fd() const override { return fd_; }

private:
    Role role_;
    int fd_;
    std::string cert_pem_;
    std::string key_pem_;
    bool cert_valid_ = false;
    std::vector<std::string> trusted_certs_;
    CertVerifyCb cert_verify_cb_;
    SetCertCb set_cert_cb_;
    bssl::UniquePtr<SSL> ssl_;
    bssl::UniquePtr<SSL_CTX> ssl_ctx_;
};

}  // namespace

std::unique_ptr<TlsConnection> TlsConnection::Create(Role role,
                                                     std::string_view cert,
                                                     std::string_view priv_key,
                                                     int fd) {
    if (cert.empty() || priv_key.empty()) {
        LOGE("TlsConnection::Create: empty cert or key");
        return nullptr;
    }
    return std::make_unique<TlsConnectionImpl>(role, cert, priv_key, fd);
}

}  // namespace tls
}  // namespace adb
