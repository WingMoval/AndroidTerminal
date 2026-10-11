// TlsTransport: AdbTransport over TCP + TLS 1.3.
//
// Wraps TcpTransport (raw TCP) + TlsConnection (BoringSSL TLS 1.3).
// Before TLS handshake: raw TCP I/O.
// After TLS handshake: all I/O through TlsConnection (BoringSSL).
//
// Reference: AOSP transport.cpp FdConnection (transport.h:201,
// transport.cpp:451-472 DispatchRead/DispatchWrite, transport.cpp:512-556
// DoTlsHandshake).

#pragma once

#include "transport/adb_transport.h"
#include "transport/tcp_transport.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "adb/tls/tls_connection.h"

namespace adb {

class TlsTransport : public AdbTransport {
public:
    TlsTransport() = default;
    ~TlsTransport() override;

    // Connect TCP to host:port. Does NOT do TLS yet.
    bool connect(const std::string& host, int port) override;

    // Raw bytes (TCP before TLS, TLS after handshake).
    bool send(const uint8_t* data, size_t length) override;
    ssize_t receive(uint8_t* buffer, size_t length) override;
    void close() override;

    // Perform the TLS 1.3 handshake using the given cert/key (PEM).
    // After this, all I/O goes through TlsConnection.
    bool startTls(const std::string& cert_pem, const std::string& priv_key_pem);

    // Read exactly `length` bytes (TCP readExact or TLS ReadFully).
    bool readExact(uint8_t* buffer, size_t length);

    // Write exactly `length` bytes.
    bool writeExact(const uint8_t* data, size_t length);

    // Wake a thread blocked in readExact()/SSL_read() WITHOUT touching the
    // TLS state machine (shutdown(2) on the underlying fd only). The reader
    // observes a clean EOF. Required before destroying the transport while
    // the stream hub RX owner may still be blocked on this socket
    // (AT.V3-2.1). Safe to call multiple times.
    void shutdownSocket();

    // Export TLS keying material (SPAKE2 binding). Only valid after startTls.
    // Returns empty if TLS is not active or export fails.
    std::vector<uint8_t> exportKeyingMaterial(size_t length);

    bool is_open() const { return tcp_.is_open(); }
    bool is_tls() const { return tls_ != nullptr; }
    const std::string& last_error() const { return tcp_.last_error(); }

    // Access the underlying TCP fd (for TlsConnection).
    int tcp_fd() const { return tcp_fd_; }

private:
    int tcp_fd_ = -1;
    TcpTransport tcp_;
    std::unique_ptr<tls::TlsConnection> tls_;
};

}  // namespace adb
