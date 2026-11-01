// PlainTcpAdbConnection: ADB over plain TCP (no TLS, no STLS).
//
// Connects to a legacy-mode adbd (created by "tcpip:<port>" service) on
// 127.0.0.1. Protocol: CNXN -> AUTH (RSA) if challenged -> device CNXN
// -> ONLINE. No STLS, no TLS, no SPAKE2.
//
// Reuses: TcpTransport, AdbPacket, AdbKeyStore (RSA signSha1).
// Reference: AOSP adb.cpp:498-623 (legacy USB/TCP AUTH path).
//
// AT.LOCAL-TCP-1A: product transport. Implements AdbPacketTransport so the
// shared AdbStreamHub can multiplex streams over this Local TCP transport
// (same 24B ADB packet wire format as the Wireless AdbConnection). The
// stream layer (OPEN/WRTE/OKAY/CLSE dispatch) is owned by the hub, NOT here.

#pragma once

#include <cstdint>
#include <mutex>
#include <string>

#include "adb/connection/adb_packet_transport.h"
#include "transport/tcp_transport.h"
#include "adb/client/adb_key_store.h"
#include "adb_protocol/adb_message.h"

namespace adb {

class PlainTcpAdbConnection : public AdbPacketTransport {
public:
    PlainTcpAdbConnection(TcpTransport* transport, AdbKeyStore* keys);
    ~PlainTcpAdbConnection();

    PlainTcpAdbConnection(const PlainTcpAdbConnection&) = delete;
    PlainTcpAdbConnection& operator=(const PlainTcpAdbConnection&) = delete;

    // Full connect: TCP -> CNXN -> AUTH (if challenged) -> device CNXN -> ONLINE.
    bool connect(const std::string& host, int port, std::string* error);

    void disconnect();

    // --- AdbPacketTransport interface ---
    bool isOnline() const override { return online_; }
    bool readPacket(AdbPacket* packet, std::string* error) override;
    bool writePacket(const AdbPacket& packet, std::string* error) override;
    uint32_t allocateLocalId() override;
    void markTransportLost() override;
    void shutdownSocket() override;

private:
    bool handleAuth(std::string* error);
    void waitDeviceCnxn();

    TcpTransport* transport_;
    AdbKeyStore* keys_;

    bool online_ = false;
    uint32_t protocol_version_ = 0;
    uint32_t max_payload_ = 0;
    uint32_t next_local_id_ = 1;

    // Connection-wide TX serialization.
    std::mutex tx_mutex_;
};

}  // namespace adb
