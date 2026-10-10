// ADB.I0 — minimal interactive ADB stream implementation (PROVISIONAL).

#include "adb/client/adb_interactive_stream.h"

#include <android/log.h>

#include <chrono>
#include <cstring>

#include "adb/connection/adb_connection.h"
#include "adb_protocol/adb_message.h"

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

namespace {

// Shell v2 frame ids (AOSP shell_protocol.h).
constexpr uint8_t kIdStdin = 0;
constexpr uint8_t kIdStdout = 1;
constexpr uint8_t kIdStderr = 2;
constexpr uint8_t kIdExit = 3;
constexpr uint8_t kIdCloseStdin = 4;
constexpr uint8_t kIdWindowSizeChange = 5;

std::vector<uint8_t> frameWithId(uint8_t id, const uint8_t* data, size_t len) {
    std::vector<uint8_t> frame;
    frame.reserve(5 + len);
    frame.push_back(id);
    frame.push_back(static_cast<uint8_t>(len & 0xff));
    frame.push_back(static_cast<uint8_t>((len >> 8) & 0xff));
    frame.push_back(static_cast<uint8_t>((len >> 16) & 0xff));
    frame.push_back(static_cast<uint8_t>((len >> 24) & 0xff));
    if (len > 0 && data) {
        frame.insert(frame.end(), data, data + len);
    }
    return frame;
}

}  // namespace

std::vector<uint8_t> AdbInteractiveStream::encodeFrame(uint8_t id,
                                                       const uint8_t* data,
                                                       size_t len) {
    return frameWithId(id, data, len);
}

bool AdbInteractiveStream::open(AdbConnection* conn, const std::string& service,
                                std::string* error) {
    if (!conn || !conn->isOnline()) {
        if (error) *error = "connection not online";
        return false;
    }
    if (open_) {
        if (error) *error = "stream already open";
        return false;
    }

    conn_ = conn;
    local_id_ = conn_->allocateLocalId();

    AdbPacket open_pkt;
    open_pkt.msg.command = static_cast<uint32_t>(AdbCommand::kOpen);
    open_pkt.msg.arg0 = local_id_;
    open_pkt.msg.arg1 = 0;
    open_pkt.payload.assign(service.begin(), service.end());
    open_pkt.payload.push_back(0);  // null-terminated service string
    open_pkt.msg.data_length = static_cast<uint32_t>(open_pkt.payload.size());
    open_pkt.SetMagic();

    if (!conn_->writePacket(open_pkt, error)) {
        if (error) *error = "OPEN write failed: " + *error;
        return false;
    }
    LOGI("[I0] TX OPEN service=\"%s\" local_id=%u", service.c_str(), local_id_);

    // Wait for OKAY to learn remote_id.
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (std::chrono::steady_clock::now() < deadline) {
        AdbPacket rx;
        std::string err;
        if (!conn_->readPacket(&rx, &err)) {
            if (error) *error = "OPEN read failed: " + err;
            return false;
        }
        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kOkay) &&
            rx.msg.arg1 == local_id_) {
            remote_id_ = rx.msg.arg0;
            open_ = true;
            LOGI("[I0] RX OKAY remote_id=%u local_id=%u stream OPEN",
                 remote_id_, local_id_);
            return true;
        }
        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kClse) &&
            rx.msg.arg1 == local_id_) {
            if (error) *error = "OPEN rejected (CLSE before OKAY)";
            return false;
        }
    }
    if (error) *error = "OPEN timeout waiting for OKAY";
    return false;
}

bool AdbInteractiveStream::writeStdin(const uint8_t* data, size_t len,
                                      std::string* error) {
    if (!open_) {
        if (error) *error = "stream not open";
        return false;
    }
    std::vector<uint8_t> frame = encodeFrame(kIdStdin, data, len);

    AdbPacket wrte;
    wrte.msg.command = static_cast<uint32_t>(AdbCommand::kWrte);
    // AOSP adb.cpp:616 — WRITE(local-id, remote-id, <data>). Host sends its
    // own stream id in arg0 and the peer's id in arg1.
    wrte.msg.arg0 = local_id_;
    wrte.msg.arg1 = remote_id_;
    wrte.payload = frame;
    wrte.msg.data_length = static_cast<uint32_t>(frame.size());
    wrte.SetMagic();

    if (!conn_->writePacket(wrte, error)) {
        if (error) *error = "WRTE(stdin) write failed: " + *error;
        return false;
    }
    LOGI("[I0] TX WRTE stdin local=%u remote=%u bytes=%zu", local_id_,
         remote_id_, len);

    // Classic flow control: the peer credits each WRTE with an OKAY. We do
    // NOT block here waiting for it (readPacket is blocking and has no true
    // timeout); the credit OKAY is consumed by the read loop (pumpPacket).
    // The probe always reads between writes, so one in-flight write is safe.
    return true;
}

ssize_t AdbInteractiveStream::read(uint8_t* buf, size_t max, int timeout_ms,
                                   std::string* error) {
    if (!open_) {
        if (error) *error = "stream not open";
        return -1;
    }

    auto deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);

    for (;;) {
        // Serve already-parsed combined data first.
        if (pending_off_ < pending_.size()) {
            size_t n = std::min(max, pending_.size() - pending_off_);
            std::memcpy(buf, pending_.data() + pending_off_, n);
            pending_off_ += n;
            return static_cast<ssize_t>(n);
        }
        if (exit_received_ || !open_) {
            return -1;  // stream done
        }

        int remaining = static_cast<int>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now())
                .count());
        if (remaining <= 0) {
            return 0;  // timeout
        }

        // One blocking-ish packet read with bounded timeout via poll is not
        // exposed by AdbConnection; readPacket blocks on TLS. We therefore
        // rely on per-read timeouts at a higher layer. Here we do a plain
        // blocking read; callers use bounded windows.
        (void)remaining;
        int rc = pumpPacket(error);
        if (rc < 0) return -1;
        if (rc == 0) {
            // No data produced; loop again (exit/clse handled above).
            if (exit_received_ || !open_) return -1;
        }
    }
}

bool AdbInteractiveStream::resize(int rows, int cols, std::string* error) {
    if (!open_) {
        if (error) *error = "stream not open";
        return false;
    }
    char ascii[64];
    snprintf(ascii, sizeof(ascii), "%dx%d,0x0", rows, cols);
    // AOSP send_window_size_change sends l+1 bytes (includes trailing NUL).
    size_t len = strlen(ascii) + 1;
    std::vector<uint8_t> frame =
        encodeFrame(kIdWindowSizeChange,
                    reinterpret_cast<const uint8_t*>(ascii), len);

    AdbPacket wrte;
    wrte.msg.command = static_cast<uint32_t>(AdbCommand::kWrte);
    // AOSP: WRITE(local-id, remote-id).
    wrte.msg.arg0 = local_id_;
    wrte.msg.arg1 = remote_id_;
    wrte.payload = frame;
    wrte.msg.data_length = static_cast<uint32_t>(frame.size());
    wrte.SetMagic();

    if (!conn_->writePacket(wrte, error)) {
        if (error) *error = "WRTE(winsize) write failed: " + *error;
        return false;
    }
    LOGI("[I0] TX WRTE winsize \"%s\"", ascii);
    // Do not block waiting for the OKAY credit here (readPacket is blocking
    // with no true timeout); the credit is consumed by read()/pumpPacket.
    return true;
}

bool AdbInteractiveStream::closeStdin(std::string* error) {
    if (!open_) {
        if (error) *error = "stream not open";
        return false;
    }
    std::vector<uint8_t> frame = encodeFrame(kIdCloseStdin, nullptr, 0);

    AdbPacket wrte;
    wrte.msg.command = static_cast<uint32_t>(AdbCommand::kWrte);
    // AOSP: WRITE(local-id, remote-id).
    wrte.msg.arg0 = local_id_;
    wrte.msg.arg1 = remote_id_;
    wrte.payload = frame;
    wrte.msg.data_length = static_cast<uint32_t>(frame.size());
    wrte.SetMagic();
    if (!conn_->writePacket(wrte, error)) {
        if (error) *error = "WRTE(closeStdin) write failed: " + *error;
        return false;
    }
    LOGI("[I0] TX WRTE closeStdin");
    return true;
}

void AdbInteractiveStream::close() {
    if (!open_ || !conn_) return;
    AdbPacket clse;
    clse.msg.command = static_cast<uint32_t>(AdbCommand::kClse);
    clse.msg.arg0 = local_id_;
    clse.msg.arg1 = remote_id_;
    clse.SetMagic();
    std::string err;
    if (!conn_->writePacket(clse, &err)) {
        LOGE("[I0] CLSE write failed: %s", err.c_str());
    }
    LOGI("[I0] TX CLSE local=%u remote=%u", local_id_, remote_id_);
    open_ = false;
}

int AdbInteractiveStream::pumpPacket(std::string* error) {
    AdbPacket rx;
    if (!conn_->readPacket(&rx, error)) {
        if (error) *error = "readPacket failed: " + *error;
        return -1;
    }

    if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kOkay)) {
        // credit ack; nothing to surface
        return 0;
    }

    if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kWrte) &&
        rx.msg.arg1 == local_id_) {
        // Parse shell v2 frame(s) in the payload.
        const uint8_t* p = rx.payload.data();
        size_t remain = rx.payload.size();
        size_t off = 0;
        while (remain - off >= 5) {
            uint8_t id = p[off];
            uint32_t len = static_cast<uint32_t>(p[off + 1]) |
                           (static_cast<uint32_t>(p[off + 2]) << 8) |
                           (static_cast<uint32_t>(p[off + 3]) << 16) |
                           (static_cast<uint32_t>(p[off + 4]) << 24);
            off += 5;
            if (len > remain - off) break;  // partial; keep rest for next read
            const uint8_t* data = p + off;
            if (id == kIdStdout || id == kIdStderr) {
                pending_.insert(pending_.end(), data, data + len);
            } else if (id == kIdExit) {
                exit_received_ = true;
                if (len >= 1) exit_code_ = static_cast<int8_t>(data[0]);
                LOGI("[I0] RX shell-v2 EXIT code=%d", exit_code_);
            } else {
                LOGI("[I0] RX shell-v2 frame id=%u len=%u", id, len);
            }
            off += len;
        }

        // Acknowledge the WRTE (required to keep the stream flowing).
        AdbPacket okay;
        okay.msg.command = static_cast<uint32_t>(AdbCommand::kOkay);
        okay.msg.arg0 = local_id_;
        okay.msg.arg1 = remote_id_;
        okay.SetMagic();
        std::string err;
        if (!conn_->writePacket(okay, &err)) {
            if (error) *error = "OKAY(wrte) write failed: " + err;
            return -1;
        }
        return pending_.size() > pending_off_ || exit_received_ ? 1 : 0;
    }

    if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kClse) &&
        rx.msg.arg1 == local_id_) {
        open_ = false;
        LOGI("[I0] RX CLSE remote=%u local=%u", rx.msg.arg0, local_id_);
        return 1;
    }

    LOGI("[I0] ignoring packet %s arg0=%08x arg1=%08x",
         CommandToString(rx.msg.command), rx.msg.arg0, rx.msg.arg1);
    return 0;
}

}  // namespace adb
