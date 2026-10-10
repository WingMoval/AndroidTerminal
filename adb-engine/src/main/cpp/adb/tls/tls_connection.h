// TlsConnection: TLS 1.3 connection over a raw fd.
//
// Ported from AOSP packages/modules/adb/tls/tls_connection.cpp
// (android15-qpr2). NDK-adapted:
//   - int fd instead of borrowed_fd
//   - Android log instead of android-base/logging
//   - BoringSSL via NDK libssl
//
// Reference:
//   - tls/tls_connection.cpp:214-304  (DoHandshake: TLS 1.3 only)
//   - tls/tls_connection.cpp:221-222  (min/max proto = TLS1_3)
//   - tls/tls_connection.cpp:264      (SSL_VERIFY_PEER | FAIL_IF_NO_PEER_CERT)
//   - tls/tls_connection.cpp:320-359  (ReadFully/WriteFully: blocking drain)

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include <openssl/ssl.h>
#include <openssl/x509.h>

namespace adb {
namespace tls {

class TlsConnection {
public:
    enum class Role { Server, Client };

    enum class TlsError : uint8_t {
        Success = 0,
        CertificateRejected,
        PeerRejectedCertificate,
        UnknownFailure,
    };

    using CertVerifyCb = std::function<int(X509_STORE_CTX*)>;
    using SetCertCb = std::function<int(SSL*)>;

    // Factory: create a TlsConnection over the given fd.
    // `cert` and `priv_key` are PEM-encoded.
    static std::unique_ptr<TlsConnection> Create(Role role,
                                                 std::string_view cert,
                                                 std::string_view priv_key,
                                                 int fd);

    virtual ~TlsConnection() = default;

    // Add a trusted CA certificate (PEM).
    virtual bool AddTrustedCertificate(std::string_view cert) = 0;

    // Override the server-cert verification callback.
    // Return 1 to accept, 0 to reject.
    virtual void SetCertVerifyCallback(CertVerifyCb cb) = 0;

    // Set the certificate-selection callback (client only).
    virtual void SetCertificateCallback(SetCertCb cb) = 0;

    // Export TLS keying material (for SPAKE2 binding in pairing).
    virtual std::vector<uint8_t> ExportKeyingMaterial(size_t length) = 0;

    // Perform the blocking TLS handshake.
    // All pre-conditions (cert, key, callbacks) must be set before this call.
    virtual TlsError DoHandshake() = 0;

    // Blocking read: read exactly `size` bytes, or return empty on error.
    virtual std::vector<uint8_t> ReadFully(size_t size) = 0;
    virtual bool ReadFully(void* buf, size_t size) = 0;

    // Blocking write: write all bytes, or return false on error.
    virtual bool WriteFully(std::string_view data) = 0;

    // Get the underlying fd (for the transport layer to close).
    virtual int fd() const = 0;
};

}  // namespace tls
}  // namespace adb
