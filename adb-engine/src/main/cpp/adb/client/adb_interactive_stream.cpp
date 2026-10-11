// ADB.I0 — minimal interactive ADB stream implementation (PROVISIONAL).
//
// AT.V3-2.1: consumes a hub-managed logical stream. All transport reads are
// owned by the hub RX dispatcher; this class only parses shell-v2 frames from
// its own stream queue and encodes stdin/winsize/close-stdin frames.

#include "adb/client/adb_interactive_stream.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "adb/connection/adb_stream_hub.h"

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

bool AdbInteractiveStream::open(AdbStreamHub* hub, const std::string& service,
                                std::string* error) {
    if (!hub) {
        if (error) *error = "connection not online";
        return false;
    }
    if (open_) {
        if (error) *error = "stream already open";
        return false;
    }

    // Interactive streams acknowledge incoming WRTE packets with OKAY credits
    // (AOSP shell client behavior), as verified in AT.V3-2 Gate B.
    stream_ = hub->openStream(service, /*ack_writes=*/true, 5000, error);
    if (!stream_) return false;

    local_id_ = stream_->localId();
    remote_id_ = stream_->remoteId();
    open_ = true;
    LOGI("[I0] OPEN service=\"%s\" local_id=%u remote_id=%u", service.c_str(),
         local_id_, remote_id_);
    return true;
}

bool AdbInteractiveStream::writeStdin(const uint8_t* data, size_t len,
                                      std::string* error) {
    if (!open_ || !stream_) {
        if (error) *error = "stream not open";
        return false;
    }
    std::vector<uint8_t> frame = encodeFrame(kIdStdin, data, len);
    std::string werr;
    if (!stream_->writeRaw(frame.data(), frame.size(), &werr)) {
        if (error) *error = "WRTE(stdin) write failed: " + werr;
        return false;
    }
    LOGI("[I0] TX WRTE stdin local=%u remote=%u bytes=%zu", local_id_,
         remote_id_, len);
    return true;
}

ssize_t AdbInteractiveStream::read(uint8_t* buf, size_t max, int timeout_ms,
                                   std::string* error) {
    if (!open_ || !stream_) {
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

        // Blocking wait on THIS stream's queue only (AT.V3-2.1): the hub's
        // single RX owner delivers chunks; close/failure wakes immediately.
        uint8_t chunk[4096];
        std::string rerr;
        ssize_t n = stream_->read(chunk, sizeof(chunk), remaining, &rerr);
        if (n < 0) {
            open_ = false;
            if (error) *error = rerr.empty() ? "stream closed" : rerr;
            return -1;
        }
        if (n == 0) {
            continue;  // read window elapsed; loop recomputes remaining
        }
        parseFrames(chunk, static_cast<size_t>(n));
    }
}

void AdbInteractiveStream::parseFrames(const uint8_t* data, size_t len) {
    std::vector<shell_v2::Frame> frames;
    std::string perr;
    if (!protocol_.feed(data, len, &frames, &perr)) {
        LOGE("[I0] shell-v2 parse error: %s", perr.c_str());
        return;
    }
    for (const auto& frame : frames) {
        switch (static_cast<shell_v2::Id>(frame.id)) {
            case shell_v2::Id::kStdout:
            case shell_v2::Id::kStderr:
                pending_.insert(pending_.end(), frame.payload.begin(),
                                frame.payload.end());
                break;
            case shell_v2::Id::kExit: {
                int code = -1;
                if (shell_v2::ShellV2Protocol::exitCode(frame, &code)) {
                    exit_code_ = code;
                    exit_received_ = true;
                    LOGI("[I0] RX shell-v2 EXIT code=%d", code);
                } else {
                    LOGE("[I0] invalid EXIT frame (payload=%zu)",
                         frame.payload.size());
                }
                break;
            }
            default:
                LOGI("[I0] RX shell-v2 frame id=%u len=%zu", frame.id,
                     frame.payload.size());
                break;
        }
    }
}

bool AdbInteractiveStream::resize(int rows, int cols, std::string* error) {
    if (!open_ || !stream_) {
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

    std::string werr;
    if (!stream_->writeRaw(frame.data(), frame.size(), &werr)) {
        if (error) *error = "WRTE(winsize) write failed: " + werr;
        return false;
    }
    LOGI("[I0] TX WRTE winsize \"%s\"", ascii);
    return true;
}

bool AdbInteractiveStream::closeStdin(std::string* error) {
    if (!open_ || !stream_) {
        if (error) *error = "stream not open";
        return false;
    }
    std::vector<uint8_t> frame = encodeFrame(kIdCloseStdin, nullptr, 0);

    std::string werr;
    if (!stream_->writeRaw(frame.data(), frame.size(), &werr)) {
        if (error) *error = "WRTE(closeStdin) write failed: " + werr;
        return false;
    }
    LOGI("[I0] TX WRTE closeStdin");
    return true;
}

void AdbInteractiveStream::close() {
    if (stream_) {
        stream_->close();  // wakes a blocked read() on this stream
    }
    open_ = false;
}

}  // namespace adb