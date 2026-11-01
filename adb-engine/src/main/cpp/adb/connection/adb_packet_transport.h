// AdbPacketTransport — abstract ADB packet transport seam.
//
// The minimal dependency-inversion seam that lets ONE AdbStreamHub multiplex
// streams over EITHER transport:
//
//   Wireless:  AdbConnection       (TCP + TLS + STLS + CNXN)
//   Local TCP: PlainTcpAdbConnection (TCP + CNXN + AUTH, no TLS)
//
// AdbStreamHub (and AdbConnGuard / AdbStream) depend ONLY on this interface,
// never on a concrete transport. The interface is exactly the 6-method surface
// the hub uses — no TLS / STLS / SPAKE2 / feature negotiation, so it does not
// leak any Wireless-specific semantics.
//
// Both transports speak the same wire format (24-byte ADB packet header +
// payload, same checksum/magic/version rules), so the hub's dispatch logic is
// transport-agnostic.
//
// AT.LOCAL-TCP-1A: product port of the verified AT.RESEARCH-LOCAL-TCP-1.7
// transport seam.

#pragma once

#include <cstdint>
#include <string>

#include "adb_protocol/adb_message.h"

namespace adb {

class AdbPacketTransport {
public:
    virtual ~AdbPacketTransport() = default;

    // Transport is ONLINE (post-CNXN). Hub refuses to start / open streams
    // when false.
    virtual bool isOnline() const = 0;

    // Read ONE ADB packet (24B header + payload).
    //   true  : packet filled
    //   false : EOF / error / timeout (see `error`).
    // Invariant: exactly ONE reader at a time (the hub RX owner post-ONLINE;
    // the connect thread pre-ONLINE).
    virtual bool readPacket(AdbPacket* packet, std::string* error) = 0;

    // Write ONE ADB packet (connection-wide TX serialized by the transport).
    virtual bool writePacket(const AdbPacket& packet, std::string* error) = 0;

    // Allocate the next local stream id (starts at 1, never 0).
    virtual uint32_t allocateLocalId() = 0;

    // Mark the transport as lost (liveness truth) WITHOUT closing the socket.
    // Called by the hub RX owner on a real read failure so isOnline() no
    // longer reports a stale ONLINE.
    virtual void markTransportLost() = 0;

    // fd-level half-shutdown that wakes a thread blocked in readPacket()
    // without closing the socket. Used by the hub to stop the single RX owner
    // before the transport is torn down.
    virtual void shutdownSocket() = 0;
};

}  // namespace adb
