// AdbStreamHub implementation (AT.V3-2.1).
//
// The single RX owner reads every post-ONLINE packet and dispatches it to the
// owning stream slot. Stream readers wait on their own slot condvar; transport
// reads are never performed by stream consumers. TX goes through
// AdbConnection::writePacket (connection-wide TX mutex).
//
// Packet routing (host side, peer->host packets):
//   OKAY/WRTE/CLSE in from device: arg1 = our local id, arg0 = remote id.

#include "adb/connection/adb_stream_hub.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "adb/connection/adb_connection.h"

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

AdbStreamHub::AdbStreamHub(AdbConnection* conn) : conn_(conn) {
    guard_ = std::make_shared<AdbConnGuard>();
    guard_->conn = conn;
    guard_->hub = this;
}

AdbStreamHub::~AdbStreamHub() { shutdown(); }

bool AdbStreamHub::start(std::string* error) {
    if (started_) return true;
    if (!conn_ || !conn_->isOnline()) {
        if (error) *error = "connection not online";
        return false;
    }
    try {
        rx_thread_ = std::thread([this] { rxLoop(); });
    } catch (const std::system_error& e) {
        if (error) *error = std::string("RX thread spawn failed: ") + e.what();
        return false;
    }
    started_ = true;
    LOGI("[HUB01] RX_OWNER_START (single transport reader, "
         "post-ONLINE rx owner count = 1)");
    return true;
}

void AdbStreamHub::shutdown() {
    if (shutdown_done_.exchange(true)) return;
    stopping_.store(true);
    // Wake stream readers/waiters first (fast, no transport dependency).
    failAll("connection closing");
    // Wake the RX owner if it is blocked in readPacket()/SSL_read().
    if (conn_) conn_->shutdownSocket();
    if (rx_thread_.joinable()) rx_thread_.join();
    if (guard_) guard_->alive.store(false);
    LOGI("[HUB06] RX_OWNER_STOPPED");
}

void AdbStreamHub::rxLoop() {
    LOGI("[HUB01] RX_LOOP_ENTER");
    while (!stopping_.load()) {
        AdbPacket pkt;
        std::string err;
        if (!conn_->readPacket(&pkt, &err)) {
            if (stopping_.load()) break;  // normal teardown (our shutdown)
            LOGE("[HUB02] RX_OWNER_READ_FAIL: %s", err.c_str());
            failAll("connection failed: " + err);
            break;
        }
        dispatch(pkt);
    }
    LOGI("[HUB05] RX_OWNER_EXIT");
}

void AdbStreamHub::dispatch(const AdbPacket& pkt) {
    const uint32_t cmd = pkt.msg.command;
    const uint32_t peer_id = pkt.msg.arg1;   // our local id
    const uint32_t remote_id = pkt.msg.arg0;

    if (cmd != static_cast<uint32_t>(AdbCommand::kOkay) &&
        cmd != static_cast<uint32_t>(AdbCommand::kWrte) &&
        cmd != static_cast<uint32_t>(AdbCommand::kClse)) {
        LOGI("[HUB] dispatch: ignoring %s arg0=0x%08x arg1=0x%08x",
             CommandToString(cmd), pkt.msg.arg0, pkt.msg.arg1);
        return;
    }

    std::shared_ptr<AdbStreamSlot> slot;
    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = slots_.find(peer_id);
        if (it != slots_.end()) slot = it->second;
    }
    if (!slot) {
        LOGI("[HUB] dispatch: %s for unknown stream local=%u (ignored)",
             CommandToString(cmd), peer_id);
        return;
    }

    if (cmd == static_cast<uint32_t>(AdbCommand::kOkay)) {
        bool opened = false;
        {
            std::lock_guard<std::mutex> sl(slot->mu);
            if (slot->state == AdbStreamSlot::State::Opening) {
                slot->remote_id = remote_id;
                slot->state = AdbStreamSlot::State::Active;
                opened = true;
            }
        }
        if (opened) {
            LOGI("[HUB03] STREAM_ACTIVE local=%u remote=%u", slot->local_id,
                 slot->remote_id);
        } else {
            LOGI("[HUB] dispatch: OKAY credit local=%u remote=%u (ignored)",
                 peer_id, remote_id);
        }
        slot->cv.notify_all();
        return;
    }

    if (cmd == static_cast<uint32_t>(AdbCommand::kWrte)) {
        bool ack = false;
        {
            std::lock_guard<std::mutex> sl(slot->mu);
            if (slot->state == AdbStreamSlot::State::Active) {
                slot->rx_chunks.push_back(pkt.payload);
                ack = slot->ack_writes;
            }
        }
        if (ack) {
            // Classic flow control: credit the peer's WRTE with an OKAY.
            // Sent from the dispatcher (the correct post-ONLINE TX position)
            // so no stream reader ever touches the transport.
            AdbPacket okay;
            okay.msg.command = static_cast<uint32_t>(AdbCommand::kOkay);
            okay.msg.arg0 = slot->local_id;
            okay.msg.arg1 = slot->remote_id;
            okay.SetMagic();
            LOGI("[HUB07] RX_WRTE local=%u remote=%u bytes=%zu -> TX_OKAY_CREDIT",
                 slot->local_id, slot->remote_id, pkt.payload.size());
            std::string werr;
            if (!conn_->writePacket(okay, &werr)) {
                LOGE("[HUB] TX OKAY(wrte credit) failed: %s", werr.c_str());
            }
        }
        slot->cv.notify_all();
        return;
    }

    // CLSE
    {
        std::lock_guard<std::mutex> sl(slot->mu);
        slot->clse_received = true;
        if (slot->state == AdbStreamSlot::State::Active) {
            slot->state = AdbStreamSlot::State::Closed;
            if (slot->close_reason.empty()) slot->close_reason = "stream closed by peer";
        } else if (slot->state == AdbStreamSlot::State::Opening) {
            slot->state = AdbStreamSlot::State::Closed;
            if (slot->close_reason.empty())
                slot->close_reason = "OPEN rejected (CLSE before OKAY)";
        }
    }
    LOGI("[HUB04] STREAM_CLOSE_PEER local=%u remote=%u", peer_id, remote_id);
    unregister(peer_id);
    slot->cv.notify_all();
}

void AdbStreamHub::failAll(const std::string& reason) {
    std::unordered_map<uint32_t, std::shared_ptr<AdbStreamSlot>> drained;
    {
        std::lock_guard<std::mutex> lock(mu_);
        drained.swap(slots_);
    }
    for (auto& kv : drained) {
        auto& slot = kv.second;
        {
            std::lock_guard<std::mutex> sl(slot->mu);
            if (slot->state != AdbStreamSlot::State::Closed) {
                slot->state = AdbStreamSlot::State::Failed;
            }
            if (slot->close_reason.empty()) slot->close_reason = reason;
        }
        slot->cv.notify_all();
    }
    if (!drained.empty()) {
        LOGI("[HUB] failAll: %zu stream(s) failed (%s)", drained.size(),
             reason.c_str());
    }
}

void AdbStreamHub::unregister(uint32_t local_id) {
    std::lock_guard<std::mutex> lock(mu_);
    slots_.erase(local_id);
    LOGI("[HUB07] STREAM_UNREGISTER local=%u registry=%zu", local_id,
         slots_.size());
}

size_t AdbStreamHub::registeredStreamCount() const {
    std::lock_guard<std::mutex> lock(mu_);
    return slots_.size();
}

std::shared_ptr<AdbStream> AdbStreamHub::openStream(const std::string& service,
                                                    bool ack_writes,
                                                    int timeout_ms,
                                                    std::string* error) {
    if (stopping_.load()) {
        if (error) *error = "connection closing";
        return nullptr;
    }

    auto slot = std::make_shared<AdbStreamSlot>();
    slot->ack_writes = ack_writes;
    slot->guard = guard_;

    {
        std::lock_guard<std::mutex> lock(mu_);
        if (stopping_.load() || !conn_->isOnline()) {
            if (error) *error = "connection not online";
            return nullptr;
        }
        slot->local_id = conn_->allocateLocalId();
        slots_[slot->local_id] = slot;
        LOGI("[HUB07] STREAM_REGISTER local=%u registry=%zu", slot->local_id,
             slots_.size());
    }

    AdbPacket open_pkt;
    open_pkt.msg.command = static_cast<uint32_t>(AdbCommand::kOpen);
    open_pkt.msg.arg0 = slot->local_id;
    open_pkt.msg.arg1 = 0;
    open_pkt.payload.assign(service.begin(), service.end());
    open_pkt.payload.push_back(0);  // null-terminated service string
    open_pkt.msg.data_length = static_cast<uint32_t>(open_pkt.payload.size());
    open_pkt.SetMagic();

    std::string werr;
    if (!conn_->writePacket(open_pkt, &werr)) {
        unregister(slot->local_id);
        if (error) *error = "OPEN write failed: " + werr;
        return nullptr;
    }
    LOGI("[HUB02] STREAM_OPEN_TX local=%u service=\"%s\" ack_writes=%d",
         slot->local_id, service.c_str(), ack_writes ? 1 : 0);

    bool timed_out = false;
    {
        std::unique_lock<std::mutex> lk(slot->mu);
        auto deadline = std::chrono::steady_clock::now() +
                        std::chrono::milliseconds(timeout_ms);
        while (slot->state == AdbStreamSlot::State::Opening) {
            if (slot->cv.wait_until(lk, deadline) == std::cv_status::timeout) {
                if (slot->state == AdbStreamSlot::State::Opening) {
                    timed_out = true;
                    break;
                }
            }
        }
    }

    if (slot->state == AdbStreamSlot::State::Active) {
        return std::shared_ptr<AdbStream>(new AdbStream(slot));
    }

    unregister(slot->local_id);
    if (error) {
        if (timed_out) {
            *error = "OPEN timeout waiting for OKAY";
        } else if (!slot->close_reason.empty()) {
            *error = slot->close_reason;
        } else {
            *error = "OPEN failed";
        }
    }
    LOGE("[HUB02] STREAM_OPEN_FAILED local=%u: %s", slot->local_id,
         error ? error->c_str() : "?");
    return nullptr;
}

// ---------------------------------------------------------------------------
// AdbStream
// ---------------------------------------------------------------------------

AdbStream::~AdbStream() { close(); }

ssize_t AdbStream::read(uint8_t* buf, size_t max, int timeout_ms,
                        std::string* error) {
    if (!slot_) {
        if (error) *error = "null stream";
        return -1;
    }
    auto slot = slot_;
    std::unique_lock<std::mutex> lk(slot->mu);
    auto deadline = std::chrono::steady_clock::now() +
                    std::chrono::milliseconds(timeout_ms > 0 ? timeout_ms : 0);
    for (;;) {
        if (!slot->rx_chunks.empty()) {
            auto& front = slot->rx_chunks.front();
            size_t n = std::min(max, front.size());
            std::memcpy(buf, front.data(), n);
            if (n == front.size()) {
                slot->rx_chunks.pop_front();
            } else {
                front.erase(front.begin(),
                            front.begin() + static_cast<std::ptrdiff_t>(n));
            }
            return static_cast<ssize_t>(n);
        }
        if (slot->state == AdbStreamSlot::State::Closed ||
            slot->state == AdbStreamSlot::State::Failed) {
            if (error) {
                *error = slot->state == AdbStreamSlot::State::Failed
                             ? slot->close_reason
                             : (slot->close_reason.empty()
                                    ? "stream closed"
                                    : slot->close_reason);
            }
            return -1;
        }
        if (timeout_ms <= 0) return 0;  // poll semantics
        if (slot->cv.wait_until(lk, deadline) == std::cv_status::timeout) {
            if (slot->rx_chunks.empty() &&
                slot->state == AdbStreamSlot::State::Active) {
                return 0;
            }
        }
    }
}

bool AdbStream::writeRaw(const uint8_t* data, size_t len, std::string* error) {
    if (!slot_) {
        if (error) *error = "null stream";
        return false;
    }
    auto slot = slot_;
    uint32_t local_id = 0;
    uint32_t remote_id = 0;
    {
        std::lock_guard<std::mutex> lk(slot->mu);
        if (slot->state != AdbStreamSlot::State::Active) {
            if (error) {
                *error = slot->close_reason.empty() ? "stream not active"
                                                    : slot->close_reason;
            }
            return false;
        }
        local_id = slot->local_id;
        remote_id = slot->remote_id;
    }
    auto guard = slot->guard;
    if (!guard || !guard->alive.load()) {
        if (error) *error = "connection closed";
        return false;
    }

    AdbPacket wrte;
    wrte.msg.command = static_cast<uint32_t>(AdbCommand::kWrte);
    wrte.msg.arg0 = local_id;
    wrte.msg.arg1 = remote_id;
    wrte.payload.assign(data, data + len);
    wrte.msg.data_length = static_cast<uint32_t>(len);
    wrte.SetMagic();

    std::string werr;
    if (!guard->conn->writePacket(wrte, &werr)) {
        if (error) *error = "WRTE write failed: " + werr;
        return false;
    }
    LOGI("[HUB02] TX WRTE local=%u remote=%u bytes=%zu", local_id, remote_id,
         len);
    return true;
}

void AdbStream::close() {
    if (!slot_) return;
    auto slot = slot_;
    bool send_clse = false;
    uint32_t local_id = 0;
    uint32_t remote_id = 0;
    {
        std::lock_guard<std::mutex> lk(slot->mu);
        if (slot->state == AdbStreamSlot::State::Opening ||
            slot->state == AdbStreamSlot::State::Active) {
            if (!slot->clse_sent && !slot->clse_received) send_clse = true;
            slot->clse_sent = true;
            slot->state = AdbStreamSlot::State::Closed;
        }
        local_id = slot->local_id;
        remote_id = slot->remote_id;
    }
    auto guard = slot->guard;
    if (guard && guard->alive.load()) {
        if (send_clse) {
            AdbPacket clse;
            clse.msg.command = static_cast<uint32_t>(AdbCommand::kClse);
            clse.msg.arg0 = local_id;
            clse.msg.arg1 = remote_id;
            clse.SetMagic();
            std::string err;
            if (!guard->conn->writePacket(clse, &err)) {
                LOGE("[HUB] TX CLSE(local close) failed: %s", err.c_str());
            } else {
                LOGI("[HUB04] STREAM_CLOSE_LOCAL local=%u remote=%u", local_id,
                     remote_id);
            }
        }
        if (guard->hub) guard->hub->unregister(local_id);
    }
    slot->cv.notify_all();
}

bool AdbStream::isActive() const {
    if (!slot_) return false;
    std::lock_guard<std::mutex> lk(slot_->mu);
    return slot_->state == AdbStreamSlot::State::Active;
}

bool AdbStream::failed() const {
    if (!slot_) return true;
    std::lock_guard<std::mutex> lk(slot_->mu);
    return slot_->state == AdbStreamSlot::State::Failed;
}

bool AdbStream::clseReceived() const {
    if (!slot_) return false;
    std::lock_guard<std::mutex> lk(slot_->mu);
    return slot_->clse_received;
}

std::string AdbStream::closeReason() const {
    if (!slot_) return "null stream";
    std::lock_guard<std::mutex> lk(slot_->mu);
    return slot_->close_reason;
}

}  // namespace adb