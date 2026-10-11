// AdbStreamHub — post-ONLINE logical stream multiplexer (AT.V3-2.1).
//
// Ownership model (the fix for the AT.V3-2 Gate C blocker):
//
//   ONE ONLINE AdbConnection
//          │
//          ▼
//   SINGLE RX OWNER (this hub's rx thread; only caller of readPacket())
//          │  classify packet (OKAY / WRTE / CLSE)
//          ▼
//   STREAM REGISTRY (local_id -> AdbStreamSlot)
//          │
//          ├── rx queue + condvar per slot
//          ▼
//     AdbStream handles (one per logical ADB stream)
//
// Invariants:
//   - exactly ONE transport RX owner per ONLINE connection (the rx thread);
//   - every post-ONLINE packet read goes through AdbStreamHub::rxLoop;
//   - TX (OPEN/WRTE/OKAY/CLSE) is serialized by AdbConnection::writePacket's
//     connection-wide TX mutex — complete packets never interleave;
//   - stream readers block on their own slot queue, never on the transport,
//     so closing one stream (or the whole connection) wakes exactly the
//     readers that must stop.
//
// The hub does NOT re-implement the ADB protocol: it reuses AdbPacket
// encode/validate and AdbCommand classification.
//
// Deliberately minimal: no SessionManager, no reconnect, no history.

#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "adb_protocol/adb_message.h"

namespace adb {

class AdbConnection;
class AdbStreamHub;
class AdbStream;

// Shared lifetime guard: lets stream handles fail TX safely after the hub /
// connection has been torn down (alive=false is set during shutdown, before
// the owning AdbClient may be replaced).
struct AdbConnGuard {
    AdbConnection* conn = nullptr;
    AdbStreamHub* hub = nullptr;
    std::atomic<bool> alive{true};
};

// Per-stream shared state. Lives until the last AdbStream handle and the hub
// registry reference are gone (shared_ptr everywhere; no raw stream pointers).
struct AdbStreamSlot {
    enum class State { Opening, Active, Closed, Failed };

    uint32_t local_id = 0;
    uint32_t remote_id = 0;
    State state = State::Opening;
    bool ack_writes = false;      // send OKAY credit for each incoming WRTE
    bool clse_sent = false;
    bool clse_received = false;
    std::string close_reason;
    std::deque<std::vector<uint8_t>> rx_chunks;  // raw WRTE payload chunks
    std::shared_ptr<AdbConnGuard> guard;

    std::mutex mu;
    std::condition_variable cv;
};

// A handle over one logical ADB stream. One consumer reads; writers may be
// concurrent (TX serialized at the connection).
class AdbStream {
public:
    ~AdbStream();

    AdbStream(const AdbStream&) = delete;
    AdbStream& operator=(const AdbStream&) = delete;

    // Blocking read of raw WRTE payload bytes for this stream.
    //   >0 : bytes copied into buf
    //    0 : timeout (stream still open, no data)
    //   -1 : stream closed by peer, locally closed, or connection failed
    //        (see failed()/closeReason(); error carries a message).
    ssize_t read(uint8_t* buf, size_t max, int timeout_ms, std::string* error);

    // Send one WRTE packet carrying `len` raw bytes (caller frames them,
    // e.g. shell-v2 stdin frames). Fails when the stream is not ACTIVE.
    bool writeRaw(const uint8_t* data, size_t len, std::string* error);

    // CLSE the stream (only if the peer has not closed first), mark it
    // closed, wake readers, and unregister it. Safe to call repeatedly and
    // safe to call from another thread while a read() is blocked.
    void close();

    uint32_t localId() const { return slot_ ? slot_->local_id : 0; }
    uint32_t remoteId() const { return slot_ ? slot_->remote_id : 0; }
    bool isActive() const;
    bool failed() const;
    bool clseReceived() const;
    std::string closeReason() const;

private:
    friend class AdbStreamHub;
    explicit AdbStream(std::shared_ptr<AdbStreamSlot> slot)
        : slot_(std::move(slot)) {}

    std::shared_ptr<AdbStreamSlot> slot_;
};

// Owns the single RX owner thread + the stream registry for one ONLINE
// AdbConnection. Created (and started) by AdbClient right after ONLINE.
class AdbStreamHub {
public:
    explicit AdbStreamHub(AdbConnection* conn);
    ~AdbStreamHub();

    AdbStreamHub(const AdbStreamHub&) = delete;
    AdbStreamHub& operator=(const AdbStreamHub&) = delete;

    // Spawn the RX owner thread. Fails if the connection is not ONLINE.
    bool start(std::string* error);

    // Stop the RX owner (wakes it with a socket shutdown), fail + wake every
    // registered/in-flight stream. Idempotent. Does NOT close the connection.
    void shutdown();

    // Register a slot, send OPEN, and wait (bounded) for OKAY/CLSE.
    // `ack_writes` selects whether incoming WRTE packets get OKAY credit
    // (interactive shell: true; one-shot shell: false — v1 behavior parity).
    std::shared_ptr<AdbStream> openStream(const std::string& service,
                                          bool ack_writes, int timeout_ms,
                                          std::string* error);

    AdbConnection* connection() const { return conn_; }

    // Diagnostic: number of live registry entries (Opening/Active).
    size_t registeredStreamCount() const;

private:
    friend class AdbStream;

    void rxLoop();
    void dispatch(const AdbPacket& packet);
    void failAll(const std::string& reason);
    void unregister(uint32_t local_id);

    AdbConnection* conn_ = nullptr;
    std::shared_ptr<AdbConnGuard> guard_;

    mutable std::mutex mu_;  // protects slots_ (registry)
    std::unordered_map<uint32_t, std::shared_ptr<AdbStreamSlot>> slots_;

    std::atomic<bool> stopping_{false};
    std::atomic<bool> shutdown_done_{false};
    std::thread rx_thread_;
    bool started_ = false;
};

}  // namespace adb