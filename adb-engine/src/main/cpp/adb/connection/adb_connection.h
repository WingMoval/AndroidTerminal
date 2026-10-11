// AdbConnection: ADB transport-level connection.
//
// Owns TlsTransport, CNXN/STLS/TLS handshake, protocol negotiation
// (version, max payload, features), local_id allocation, and generic
// packet I/O.
//
// Does NOT handle: OPEN, OKAY, WRTE, CLSE, shell service, ShellResult.
//
// Reference v1: adb_protocol_reference.md, transport.cpp:512-556 (TLS),
// adb.cpp:267-290 (banner), adb.cpp:498-623 (CNXN/STLS).

#pragma once

#include <cstdint>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "adb/connection/feature_set.h"
#include "adb/transport/tls_transport.h"
#include "adb_protocol/adb_message.h"

namespace adb {

class AdbKeyStore;

enum class ConnectionState {
    Disconnected,
    Connecting,
    WaitingStls,
    TlsNegotiating,
    WaitingPostTlsCnxn,
    Online,
    Closing,
    Offline,
};

class AdbConnection {
public:
    explicit AdbConnection(TlsTransport* transport, AdbKeyStore* keys);
    ~AdbConnection();

    AdbConnection(const AdbConnection&) = delete;
    AdbConnection& operator=(const AdbConnection&) = delete;

    // Full connect: TCP -> pre-TLS CNXN -> RX STLS -> TX STLS ->
    // TLS handshake -> post-TLS CNXN -> RX device CNXN -> ONLINE.
    bool connect(const std::string& host, int port, std::string* error);

    void disconnect();

    // fd-level shutdown that wakes a thread blocked in readPacket() without
    // closing the socket. Used by the stream hub to stop the single RX owner
    // before disconnect() tears the transport down (AT.V3-2.1).
    void shutdownSocket();

    ConnectionState state() const { return state_; }
    bool isOnline() const { return state_ == ConnectionState::Online; }

    // Generic packet I/O (24B header + payload).
    // readPacket: ONE reader at a time. Before ONLINE this is the connect
    // thread; after ONLINE only the AdbStreamHub RX thread may call it.
    // writePacket: connection-wide serialized (TX mutex) — multiple logical
    // streams may write full ADB packets concurrently.
    bool readPacket(AdbPacket* packet, std::string* error);
    bool writePacket(const AdbPacket& packet, std::string* error);

    // Negotiated values.
    uint32_t protocolVersion() const { return protocol_version_; }
    uint32_t maxPayload() const { return max_payload_; }

    // Feature negotiation.
    bool canUseFeature(const std::string& feature) const;
    const FeatureSet& remoteFeatures() const { return remote_features_; }
    const FeatureSet& localFeatures() const { return local_features_; }

    // Local ID allocation (starts at 1, never 0).
    uint32_t allocateLocalId();

private:
    bool sendCnxn();
    bool waitStls();
    bool sendStlsReply();
    bool doTlsHandshake(std::string* error);
    bool waitPostTlsCnxn(std::string* error);

    static std::string buildLocalBanner();
    static FeatureSet localSupportedFeatures();

    TlsTransport* transport_;
    AdbKeyStore* keys_;

    ConnectionState state_ = ConnectionState::Disconnected;

    // Negotiated values.
    uint32_t protocol_version_ = 0;
    uint32_t max_payload_ = 0;
    FeatureSet remote_features_;
    FeatureSet local_features_;

    // Local ID allocation.
    uint32_t next_local_id_ = 1;

    // Connection-wide TX serialization: guarantees complete ADB packets are
    // never interleaved on the wire when multiple logical streams write
    // concurrently (AT.V3-2.1 §15).
    std::mutex tx_mutex_;

    // Diagnostic: packet sequence counter.
    mutable uint32_t packet_seq_ = 0;

    // Diagnostic: log full packet trace.
    void tracePacket(const char* dir, const AdbPacket& pkt) const;
};

}  // namespace adb
