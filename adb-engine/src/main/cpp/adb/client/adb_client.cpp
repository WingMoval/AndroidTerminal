// AdbClient implementation: service-level operations (OPEN/WRTE/CLSE/shell).
//
// Connection management (TCP/TLS/CNXN/negotiation) is delegated to AdbConnection.
// Reference: AOSP adb.cpp + client/auth.cpp + transport.cpp.

#include "adb/client/adb_client.h"

#include <android/log.h>

#include <chrono>
#include <cstring>
#include <ctime>
#include <memory>
#include <vector>

#include "adb/connection/adb_connection.h"
#include "adb/client/adb_key_store.h"
#include "adb/service/shell_v2_protocol.h"
#include "adb_protocol/adb_message.h"

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

struct AdbClient::Impl {
    explicit Impl(std::string dir)
        : keys(std::move(dir)),
          transport(),
          connection(&transport, &keys) {}
    AdbKeyStore keys;
    TlsTransport transport;
    AdbConnection connection;
};

AdbClient::AdbClient(std::string key_dir) : impl_(new Impl(std::move(key_dir))) {}

AdbClient::~AdbClient() {
    disconnect();
    delete impl_;
}

bool AdbClient::connect(const std::string& host, int port) {
    LOGI("AdbClient::connect: %s:%d", host.c_str(), port);

    // 1. Load or create the RSA key.
    if (!impl_->keys.loadOrCreate()) {
        LOGE("AdbClient::connect: key loadOrCreate failed");
        return false;
    }

    // 2. Delegate full connection to AdbConnection.
    std::string error;
    if (!impl_->connection.connect(host, port, &error)) {
        LOGE("AdbClient::connect: AdbConnection failed: %s", error.c_str());
        return false;
    }

    LOGI("AdbClient::connect: ADB session established (online, TLS active)");
    return true;
}

bool AdbClient::is_connected() const {
    return impl_->connection.isOnline();
}

adb::ConnectionState AdbClient::connection_state() const {
    return impl_->connection.state();
}

adb::AdbConnection* AdbClient::connection() {
    return &impl_->connection;
}

ShellResult AdbClient::shell(const std::string& command) {
    if (!impl_->connection.isOnline()) {
        LOGE("AdbClient::shell: not connected");
        ShellResult r;
        r.status = ShellStatus::TransportError;
        r.has_error = true;
        r.error = "not connected (call connect() first)";
        return r;
    }
    LOGI("AdbClient::shell: executing \"%s\"", command.c_str());
    return runShell(command);
}

bool AdbClient::sendOpen(const std::string& service, uint32_t& local_id) {
    local_id = impl_->connection.allocateLocalId();

    AdbPacket open;
    open.msg.command = static_cast<uint32_t>(AdbCommand::kOpen);
    open.msg.arg0 = local_id;
    // AOSP sockets.cpp:571-577: arg1 = send buffer for delayed ack.
    // 0 when delayed_ack feature is not negotiated (default).
    open.msg.arg1 = 0;
    open.payload.assign(service.begin(), service.end());
    open.payload.push_back(0);  // null-terminate (AOSP backward compat)
    open.msg.data_length = static_cast<uint32_t>(open.payload.size());

    LOGI("TX OPEN service=\"%s\" local_id=%u", service.c_str(), local_id);

    std::string err;
    bool ok = impl_->connection.writePacket(open, &err);
    if (!ok) {
        LOGE("sendOpen: writePacket failed: %s", err.c_str());
        return false;
    }
    return true;
}

bool AdbClient::sendClse(uint32_t local_id, uint32_t remote_id) {
    AdbPacket clse;
    clse.msg.command = static_cast<uint32_t>(AdbCommand::kClse);
    clse.msg.arg0 = local_id;
    clse.msg.arg1 = remote_id;

    LOGI("TX CLSE local_id=%u remote_id=%u", local_id, remote_id);

    std::string err;
    if (!impl_->connection.writePacket(clse, &err)) {
        LOGE("sendClse: writePacket failed: %s", err.c_str());
        return false;
    }
    return true;
}

ShellResult AdbClient::runShell(const std::string& command) {
    ShellResult result;

    // TODO: Step 2B.3/2B.4 use connection.canUseFeature("shell_v2") to
    // decide whether to use shell v2 or fall back to plain shell.
    std::string service = "shell,v2,raw:" + command;

    uint32_t local_id = 0;
    if (!sendOpen(service, local_id)) {
        // Core-layer failure: MUST NOT be reported as device stderr (Step 4B.R2).
        result.status = ShellStatus::TransportError;
        result.has_error = true;
        result.error = "failed to OPEN shell service";
        return result;
    }

    // Read packets until CLSE.
    uint32_t remote_id = 0;
    bool got_remote_id = false;

    // Shell v2 protocol parser (persistent buffer, independent of ADB packets).
    shell_v2::ShellV2Protocol protocol;

    // Diagnostic: 5-second timeout for OPEN response (Step 2B.2.2 only).
    auto open_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    bool got_first_packet = false;

    for (int i = 0; i < 1000; ++i) {
        // Check timeout after OPEN if we haven't received any response.
        if (!got_first_packet &&
            std::chrono::steady_clock::now() > open_deadline) {
            LOGE("WAIT_OPEN_TIMEOUT: no response after OPEN (5s)");
            // Core-layer failure, not device stderr (Step 4B.R2).
            result.status = ShellStatus::TransportError;
            result.has_error = true;
            result.error = "no response from device after OPEN (5s)";
            break;
        }

        AdbPacket rx;
        std::string err;
        if (!impl_->connection.readPacket(&rx, &err)) {
            LOGE("runShell: readPacket failed: %s", err.c_str());
            result.status = ShellStatus::TransportError;
            result.has_error = true;
            result.error = "read error: " + err;
            break;
        }
        got_first_packet = true;

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kOkay)) {
            if (rx.msg.arg1 == local_id) {
                remote_id = rx.msg.arg0;
                got_remote_id = true;
                LOGI("RX OKAY remote_id=%u local_id=%u", remote_id, local_id);
            }
            continue;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kWrte)) {
            if (rx.msg.arg1 == local_id) {
                if (!got_remote_id) remote_id = rx.msg.arg0;
                LOGI("RX WRTE remote_id=%u local_id=%u len=%zu",
                     remote_id, local_id, rx.payload.size());
                std::vector<shell_v2::Frame> frames;
                std::string parse_error;
                if (!protocol.feed(rx.payload.data(), rx.payload.size(),
                                   &frames, &parse_error)) {
                    LOGE("runShell: shell v2 parse error: %s", parse_error.c_str());
                    // Core-layer protocol failure, not device stderr (Step 4B.R2).
                    result.status = ShellStatus::ProtocolError;
                    result.has_error = true;
                    result.error = "protocol error: " + parse_error;
                    break;
                }
                for (const auto& frame : frames) {
                    switch (static_cast<shell_v2::Id>(frame.id)) {
                        case shell_v2::Id::kStdout:
                            result.stdout_data.append(
                                reinterpret_cast<const char*>(frame.payload.data()),
                                frame.payload.size());
                            LOGI("shell v2: stdout +%zu bytes (total %zu)",
                                 frame.payload.size(), result.stdout_data.size());
                            break;
                        case shell_v2::Id::kStderr:
                            result.stderr_data.append(
                                reinterpret_cast<const char*>(frame.payload.data()),
                                frame.payload.size());
                            LOGI("shell v2: stderr +%zu bytes (total %zu)",
                                 frame.payload.size(), result.stderr_data.size());
                            break;
                        case shell_v2::Id::kExit: {
                            int code;
                            if (shell_v2::ShellV2Protocol::exitCode(frame, &code)) {
                                result.exit_code = code;
                                LOGI("shell v2: exit_code=%d", code);
                            } else {
                                LOGE("shell v2: invalid EXIT frame (payload=%zu)",
                                     frame.payload.size());
                            }
                            break;
                        }
                        default:
                            LOGI("shell v2: id=%u ignored (%zu bytes)",
                                 frame.id, frame.payload.size());
                            break;
                    }
                }
            }
            continue;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kClse)) {
            if (rx.msg.arg1 == local_id) {
                remote_id = rx.msg.arg0;
                LOGI("RX CLSE remote_id=%u local_id=%u", remote_id, local_id);
                break;
            }
            continue;
        }

        LOGI("runShell: ignoring unexpected packet %s arg0=0x%08x arg1=0x%08x",
             CommandToString(rx.msg.command), rx.msg.arg0, rx.msg.arg1);
    }

    // Loop budget exhausted without a terminal CLSE for this stream: the ADB
    // stream never completed. This is a core-layer protocol failure, distinct
    // from a device command that merely exits non-zero (Step 4B.R2).
    if (!result.has_error && !got_remote_id) {
        result.status = ShellStatus::ProtocolError;
        result.has_error = true;
        result.error = "stream terminated without CLSE (packet budget exhausted)";
    }

    sendClse(local_id, remote_id);

    LOGI("runShell: done exit_code=%d stdout=%zu bytes stderr=%zu bytes",
         result.exit_code, result.stdout_data.size(), result.stderr_data.size());
    return result;
}

void AdbClient::disconnect() {
    impl_->connection.disconnect();
}

}  // namespace adb
