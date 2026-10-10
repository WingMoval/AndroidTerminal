// ADB.I0 — minimal interactive ADB stream (PROVISIONAL).
//
// Reuses an existing ONLINE AdbConnection (same TCP/TLS/ADB transport, same
// identity, no re-handshake) to open and drive ONE persistent shell stream.
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

#include <cstdint>
#include <string>
#include <vector>

namespace adb {

class AdbConnection;

class AdbInteractiveStream {
public:
    AdbInteractiveStream() = default;
    ~AdbInteractiveStream() = default;

    AdbInteractiveStream(const AdbInteractiveStream&) = delete;
    AdbInteractiveStream& operator=(const AdbInteractiveStream&) = delete;

    // OPEN the service stream on an already-ONLINE connection.
    bool open(AdbConnection* conn, const std::string& service, std::string* error);

    // Write stdin bytes as a shell-v2 kIdStdin frame (blocking, waits credit).
    bool writeStdin(const uint8_t* data, size_t len, std::string* error);

    // Read and parse WRTE packets for this stream until some shell data
    // (stdout+stderr combined into one byte stream) is available, a shell-v2
    // exit frame arrives, CLSE is received, or timeout_ms elapses.
    // Returns bytes written to buf (>0), 0 on timeout, -1 on error/close.
    ssize_t read(uint8_t* buf, size_t max, int timeout_ms, std::string* error);

    // Send shell-v2 WindowSizeChange frame (rows, cols; pixels 0).
    bool resize(int rows, int cols, std::string* error);

    // Send shell-v2 CloseStdin frame (EOF semantics).
    bool closeStdin(std::string* error);

    // CLSE the stream (does not close the ADB connection).
    void close();

    bool isOpen() const { return open_; }
    uint32_t localId() const { return local_id_; }
    uint32_t remoteId() const { return remote_id_; }
    bool exitReceived() const { return exit_received_; }
    int exitCode() const { return exit_code_; }

private:
    static std::vector<uint8_t> encodeFrame(uint8_t id,
                                            const uint8_t* data, size_t len);

    // Drain one ADB packet belonging to this stream. Returns:
    //   1 = produced output (pending_data_ non-empty or exit/clse)
    //   0 = no relevant data (timeout handled by caller loop)
    //  -1 = error
    int pumpPacket(std::string* error);

    AdbConnection* conn_ = nullptr;
    uint32_t local_id_ = 0;
    uint32_t remote_id_ = 0;
    bool open_ = false;
    bool exit_received_ = false;
    int exit_code_ = -1;
    std::vector<uint8_t> pending_;  // combined stdout+stderr bytes
    size_t pending_off_ = 0;
};

}  // namespace adb
