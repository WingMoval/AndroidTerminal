// PairingPacket — AOSP Wireless ADB pairing wire framing.
//
// Reference: AOSP packages/modules/adb pairing_connection/pairing_connection.cpp:44-50
// and proto/pairing.proto (only the Type enum is meaningful; payload is NOT
// protobuf-serialized — it is a hand-written binary header).
//
// Wire format (6-byte header, little-endian host -> big-endian on the wire):
//   uint8_t  version      // = 1
//   uint8_t  type         // 0 = SPAKE2_MSG, 1 = PEER_INFO
//   uint32_t payload_size // BIG-ENDIAN (htonl), number of payload bytes
//
// This is intentionally NOT a protobuf framing. Do not "simplify" to protobuf.

#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace adb {
namespace pairing {

// PairingPacket.Type (AOSP pairing.proto).
enum class PairingPacketType : uint8_t {
    SPAKE2_MSG = 0,
    PEER_INFO  = 1,
};

// AOSP pairing_connection.cpp:46-50 — the 6-byte header.
struct PairingPacketHeader {
    uint8_t  version;     // = 1
    uint8_t  type;        // PairingPacketType
    uint32_t payload_size;  // big-endian on the wire
};

// kMaxPeerInfoSize (AOSP pairing_connection.h) — maximum PeerInfo payload.
constexpr size_t kMaxPeerInfoSize = 8192;
// kMaxPayloadSize (AOSP pairing_connection.cpp:44) — 2 * kMaxPeerInfoSize.
constexpr size_t kMaxPayloadSize  = kMaxPeerInfoSize * 2;
// Header length in bytes.
constexpr size_t kHeaderSize = 6;
// The only valid protocol version.
constexpr uint8_t kPairingVersion = 1;
// TLS exporter label (AOSP tls_connection.cpp:36 / pairing_connection.cpp:192).
constexpr const char* kAdbLabel = "adb-label";
// Length of the TLS ExportKeyingMaterial appended to the pairing code.
constexpr size_t kTlsExporterLength = 64;

// Encode a 6-byte header to a buffer (writes exactly 6 bytes, big-endian
// payload_size). Returns the number of bytes written (6) or 0 on error.
inline size_t encodeHeader(const PairingPacketHeader& h, uint8_t* out) {
    if (!out) return 0;
    out[0] = h.version;
    out[1] = h.type;
    out[2] = static_cast<uint8_t>((h.payload_size >> 24) & 0xff);
    out[3] = static_cast<uint8_t>((h.payload_size >> 16) & 0xff);
    out[4] = static_cast<uint8_t>((h.payload_size >> 8) & 0xff);
    out[5] = static_cast<uint8_t>(h.payload_size & 0xff);
    return kHeaderSize;
}

// Decode a 6-byte header from a buffer. Returns true on success.
// Enforces version == 1 and a known type.
inline bool decodeHeader(const uint8_t* in, size_t in_len, PairingPacketHeader* out) {
    if (!in || !out || in_len < kHeaderSize) return false;
    out->version = in[0];
    out->type = in[1];
    out->payload_size =
        (static_cast<uint32_t>(in[2]) << 24) |
        (static_cast<uint32_t>(in[3]) << 16) |
        (static_cast<uint32_t>(in[4]) << 8) |
        (static_cast<uint32_t>(in[5]));
    if (out->version != kPairingVersion) return false;
    if (out->type != static_cast<uint8_t>(PairingPacketType::SPAKE2_MSG) &&
        out->type != static_cast<uint8_t>(PairingPacketType::PEER_INFO)) {
        return false;
    }
    if (out->payload_size > kMaxPayloadSize) return false;
    return true;
}

// Build a full framed packet (header + payload) ready to send.
inline std::vector<uint8_t> buildPacket(PairingPacketType type,
                                        const uint8_t* payload, size_t payload_len) {
    if (payload_len > kMaxPayloadSize) return {};
    PairingPacketHeader h;
    h.version = kPairingVersion;
    h.type = static_cast<uint8_t>(type);
    h.payload_size = static_cast<uint32_t>(payload_len);
    std::vector<uint8_t> buf(kHeaderSize + payload_len);
    encodeHeader(h, buf.data());
    if (payload_len > 0 && payload) {
        std::memcpy(buf.data() + kHeaderSize, payload, payload_len);
    }
    return buf;
}

}  // namespace pairing
}  // namespace adb
