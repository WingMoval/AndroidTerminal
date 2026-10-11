// AdbClient: high-level ADB host client.
//
// Delegates connection management to AdbConnection (TCP/TLS/CNXN/negotiation).
// Handles service-level operations: OPEN, WRTE read loop, CLSE, shell.
//
// Key/cert is generated on first run and cached in key_dir.
//
// Reference: AOSP adb.cpp + client/auth.cpp + transport.cpp.

#pragma once

#include <cstdint>
#include <string>

#include "adb/connection/adb_connection.h"

namespace adb {

class TlsTransport;
class AdbKeyStore;
class AdbStreamHub;

// Transport-level classification of a one-shot shell execution.
//
// CRITICAL (Step 4B.R2): a non-zero *device* exit code is NOT a failure of this
// layer. `Completed` means "the ADB execution itself ran to completion", which
// includes commands that exit non-zero. Transport/protocol problems are reported
// separately and MUST NOT be mixed into `stderr_data`, which is reserved for the
// device process' own stderr bytes.
enum class ShellStatus {
    Completed = 0,       // stream completed (exit code may be anything)
    TransportError = 1,  // connect/read/write/OPEN failure, EOF
    ProtocolError = 2,   // malformed frame, bad magic/length, unterminated stream
};

struct ShellResult {
    // --- Device process output (verbatim from the device) ---
    std::string stdout_data;
    std::string stderr_data;

    // --- Device process exit status ---
    int exit_code = -1;   // -1 = unknown / not reported

    // --- AT Core / transport layer diagnostics (never device output) ---
    ShellStatus status = ShellStatus::Completed;
    bool has_error = false;
    std::string error;
};

class AdbClient {
public:
    explicit AdbClient(std::string key_dir);
    ~AdbClient();

    AdbClient(const AdbClient&) = delete;
    AdbClient& operator=(const AdbClient&) = delete;

    // Connect to a wireless-debugging adbd (already paired, no re-pairing).
    // Returns false on TCP/TLS/CNXN failure.
    bool connect(const std::string& host, int port);

    // Run a shell command. Blocks until CLSE or stream termination.
    ShellResult shell(const std::string& command);

    void disconnect();

    bool is_connected() const;

    // Factual underlying connection state. Exposed so that higher layers (e.g.
    // AT Core) can project a real state instead of guessing from a bool.
    ConnectionState connection_state() const;

    // Access the underlying AdbConnection (for state projection).
    // ADB Engine internal use only. May be null when never connected.
    AdbConnection* connection();

    // The post-ONLINE stream multiplexer (single RX owner for the connection).
    // Null before connect() succeeds / after disconnect(). This is the entry
    // point for interactive streams (AT.V3-2.1).
    AdbStreamHub* streamHub();

private:
    ShellResult runShell(const std::string& command);

    struct Impl;
    Impl* impl_;
};

}  // namespace adb
