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
#include "adb/connection/adb_stream_hub.h"
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
    // AT.V3-2.1: post-ONLINE stream multiplexer (single RX owner).
    std::unique_ptr<AdbStreamHub> hub;
};

AdbClient::AdbClient(std::string key_dir) : impl_(new Impl(std::move(key_dir))) {}

AdbClient::~AdbClient() {
    disconnect();
    delete impl_;
}

bool AdbClient::connect(const std::string& host, int port) {
    LOGI("AdbClient::connect: %s:%d", host.c_str(), port);

    // Defensive: drop any previous session (stops its RX owner first).
    impl_->hub.reset();

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

    // 3. AT.V3-2.1: spawn the single post-ONLINE RX owner. All logical
    // streams (one-shot + interactive) multiplex over this connection.
    impl_->hub = std::make_unique<AdbStreamHub>(&impl_->connection);
    std::string hub_error;
    if (!impl_->hub->start(&hub_error)) {
        LOGE("AdbClient::connect: stream hub start failed: %s",
             hub_error.c_str());
        impl_->hub.reset();
        impl_->connection.disconnect();
        return false;
    }

    LOGI("AdbClient::connect: ADB session established (online, TLS active, "
         "stream hub RX owner running)");
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

adb::AdbStreamHub* AdbClient::streamHub() { return impl_->hub.get(); }

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

ShellResult AdbClient::runShell(const std::string& command) {
    ShellResult result;

    // TODO: Step 2B.3/2B.4 use connection.canUseFeature("shell_v2") to
    // decide whether to use shell v2 or fall back to plain shell.
    std::string service = "shell,v2,raw:" + command;

    if (!impl_->hub) {
        // Core-layer failure: MUST NOT be reported as device stderr (Step 4B.R2).
        result.status = ShellStatus::TransportError;
        result.has_error = true;
        result.error = "no online session (call connect() first)";
        return result;
    }

    // AT.V3-2.1: one-shot shells are ordinary logical streams on the single
    // RX owner. ack_writes=false preserves the v1 one-shot WRTE behavior.
    std::string open_err;
    std::shared_ptr<AdbStream> stream =
        impl_->hub->openStream(service, /*ack_writes=*/false, 5000, &open_err);
    if (!stream) {
        // Core-layer failure, not device stderr (Step 4B.R2).
        result.status = ShellStatus::TransportError;
        result.has_error = true;
        result.error = "failed to OPEN shell service: " + open_err;
        return result;
    }
    LOGI("runShell: stream open local=%u remote=%u", stream->localId(),
         stream->remoteId());

    // Shell v2 protocol parser (persistent buffer, independent of ADB packets).
    shell_v2::ShellV2Protocol protocol;

    uint8_t buf[4096];
    int consecutive_timeouts = 0;
    for (;;) {
        std::string rerr;
        ssize_t n = stream->read(buf, sizeof(buf), 5000, &rerr);
        if (n < 0) {
            if (stream->failed()) {
                // Core-layer failure, not device stderr (Step 4B.R2).
                result.status = ShellStatus::TransportError;
                result.has_error = true;
                result.error = stream->closeReason().empty() ? rerr
                                                             : stream->closeReason();
                LOGE("runShell: stream failed: %s", result.error.c_str());
            }
            break;  // remote CLSE / local close: normal termination
        }
        if (n == 0) {
            // Still active, no data within the read window. Keep waiting:
            // one-shot semantics = block until CLSE / failure (v1 parity).
            if (++consecutive_timeouts > 240) {  // ~20 min safeguard
                result.status = ShellStatus::ProtocolError;
                result.has_error = true;
                result.error =
                    "stream terminated without CLSE (no data for 20 min)";
                break;
            }
            continue;
        }
        consecutive_timeouts = 0;

        std::vector<shell_v2::Frame> frames;
        std::string parse_error;
        if (!protocol.feed(buf, static_cast<size_t>(n), &frames, &parse_error)) {
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
                    LOGI("shell v2: id=%u ignored (%zu bytes)", frame.id,
                         frame.payload.size());
                    break;
            }
        }
    }

    // CLSE the stream (skipped when the peer closed first).
    stream->close();

    LOGI("runShell: done exit_code=%d stdout=%zu bytes stderr=%zu bytes",
         result.exit_code, result.stdout_data.size(), result.stderr_data.size());
    return result;
}

void AdbClient::disconnect() {
    // AT.V3-2.1 teardown order: stop the RX owner + fail/wake all streams
    // FIRST (this also shutdown(2)s the socket so a blocked SSL_read wakes),
    // then tear the TLS/TCP transport down with no concurrent reader.
    if (impl_->hub) {
        impl_->hub->shutdown();
        impl_->hub.reset();
    }
    impl_->connection.disconnect();
}

}  // namespace adb
