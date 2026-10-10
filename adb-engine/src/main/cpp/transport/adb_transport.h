// AdbTransport: abstract bidirectional byte channel.
//
// The ADB protocol layer sits on top of this interface and knows nothing
// about the underlying medium (loopback TCP in Step 1, unix domain socket
// in a future step). This mirrors the AOSP Connection abstraction
// (packages/modules/adb/transport.h:110-172).
//
// Step 0: interface only, no implementations.

#pragma once

#include <cstddef>
#include <string>

namespace adb {

class AdbTransport {
public:
    virtual ~AdbTransport() = default;

    // Open the channel to host:port. Returns false on failure.
    virtual bool connect(const std::string& host, int port) = 0;

    // Send raw bytes. Returns false on failure.
    virtual bool send(const uint8_t* data, size_t length) = 0;

    // Receive up to `length` bytes into `buffer`. Returns the number of
    // bytes read; negative on error/EOF.
    virtual ssize_t receive(uint8_t* buffer, size_t length) = 0;

    // Close the channel. Safe to call multiple times.
    virtual void close() = 0;
};

}  // namespace adb
