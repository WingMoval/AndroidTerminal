// TcpTransport: AdbTransport over a TCP socket.
//
// Step 1A: real loopback TCP transport. connect() opens an AF_INET socket
// to host:port (127.0.0.1:5555 by default), send()/receive() move raw
// bytes, and readExact()/writeExact() guarantee whole-buffer transfers
// (partial read/write + EINTR retry). Socket errors are surfaced through
// last_error() so the protocol layer can report errno/strerror.
//
// The ADB protocol layer (Step 1A) reads a 24-byte header then a payload;
// this class deliberately does NOT assume one read() returns a full packet.

#pragma once

#include "transport/adb_transport.h"

#include <sys/types.h>

#include <string>

namespace adb {

class TcpTransport : public AdbTransport {
public:
    TcpTransport() = default;
    ~TcpTransport() override;

    bool connect(const std::string& host, int port) override;
    bool send(const uint8_t* data, size_t length) override;
    ssize_t receive(uint8_t* buffer, size_t length) override;
    void close() override;

    // Half-close the socket in both directions WITHOUT closing the fd.
    // Wakes any thread blocked in recv()/SSL_read() on this fd (recv
    // returns 0 / SSL_read returns EOF). Used by the ADB stream hub to
    // stop the single connection RX owner before the transport is torn
    // down. Safe to call multiple times. Unlike close(), the fd stays
    // valid, so the owner thread can still observe a clean failure.
    void shutdown();

    // Read exactly `length` bytes (loops over partial reads, retries EINTR).
    // Returns true on success; false on EOF/error (see last_error()).
    bool readExact(uint8_t* buffer, size_t length);

    // Write exactly `length` bytes (loops over partial writes, retries EINTR).
    // Returns true on success; false on error (see last_error()).
    bool writeExact(const uint8_t* data, size_t length);

    bool is_open() const { return fd_ != -1; }

    // Expose the raw fd (for TlsConnection / TlsTransport).
    int fd() const { return fd_; }

    // "errno: strerror" string describing the most recent failed operation.
    const std::string& last_error() const { return last_error_; }

private:
    void set_error(int err);

    int fd_ = -1;
    std::string last_error_;
};

}  // namespace adb
