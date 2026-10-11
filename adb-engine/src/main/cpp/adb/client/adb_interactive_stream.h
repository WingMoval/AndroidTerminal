// ADB.I0 — minimal interactive ADB stream (PROVISIONAL).
//
// AT.V3-2.1: rebased onto AdbStreamHub. The interactive stream no longer
// reads the shared connection directly; it consumes its own logical stream
// (queue + condvar) owned by the hub's single RX dispatcher. Reuses the same
// ONLINE TCP/TLS/ADB connection, same identity, no re-handshake.
//
// Service: "shell,v2,pty:"  (AOSP services.cpp:86-123 — empty command => kPty;
// "pty" forces PTY; "v2" selects shell v2 protocol).
//
// Shell v2 frame (AOSP shell_protocol.h): 1-byte id + 4-byte LE length + payload.
//   kIdStdin=0, kIdStdout=1, kIdStderr=2, kIdExit=3, kIdCloseStdin=4,
//   kIdWindowSizeChange=5.
//   WindowSizeChange payload = ASCII "%dx%d,%dx%d" (rows,cols,xpix,ypix);
//   adbd parses with sscanf and ioctl(TIOCSWINSZ) (shell_service.cpp:701-710).
//
// Deliberately minimal (no SessionManager / backend / history / multiplexer).
// NOT a frozen public API.

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "adb/service/shell_v2_protocol.h"

namespace adb {

class AdbStreamHub;
class AdbStream;

class AdbInteractiveStream {
public:
    AdbInteractiveStream() = default;
    ~AdbInteractiveStream() = default;

    AdbInteractiveStream(const AdbInteractiveStream&) = delete;
    AdbInteractiveStream& operator=(const AdbInteractiveStream&) = delete;

    // OPEN the service stream on the hub's ONLINE connection.
    bool open(AdbStreamHub* hub, const std::string& service, std::string* error);

    // Write stdin bytes as a shell-v2 kIdStdin frame (WRTE, TX-serialized).
    bool writeStdin(const uint8_t* data, size_t len, std::string* error);

    // Read shell data (stdout+stderr combined) from this stream's queue.
    // Blocks up to timeout_ms; close/EOF/TX-failure wakes it immediately.
    // Returns bytes written to buf (>0), 0 on timeout, -1 on error/close.
    ssize_t read(uint8_t* buf, size_t max, int timeout_ms, std::string* error);

    // Send shell-v2 WindowSizeChange frame (rows, cols; pixels 0).
    bool resize(int rows, int cols, std::string* error);

    // Send shell-v2 CloseStdin frame (EOF semantics).
    bool closeStdin(std::string* error);

    // CLSE the stream (does not close the ADB connection).
    void close();

    bool isOpen() const { return open_.load(); }
    uint32_t localId() const { return local_id_; }
    uint32_t remoteId() const { return remote_id_; }
    bool exitReceived() const { return exit_received_; }
    int exitCode() const { return exit_code_; }

private:
    static std::vector<uint8_t> encodeFrame(uint8_t id,
                                            const uint8_t* data, size_t len);

    // Feed raw WRTE payload bytes into the persistent shell-v2 parser and
    // accumulate stdout+stderr into pending_ / exit into exit_received_.
    void parseFrames(const uint8_t* data, size_t len);

    std::shared_ptr<AdbStream> stream_;
    uint32_t local_id_ = 0;
    uint32_t remote_id_ = 0;
    std::atomic<bool> open_{false};
    bool exit_received_ = false;
    int exit_code_ = -1;
    std::vector<uint8_t> pending_;  // combined stdout+stderr bytes
    size_t pending_off_ = 0;
    shell_v2::ShellV2Protocol protocol_;
};

}  // namespace adb