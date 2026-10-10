// PeerInfo — AOSP Wireless ADB pairing peer identity representation.
//
// Reference: AOSP pairing_connection/include/adb/pairing/pairing_connection.h:40-46
//   enum PeerInfoType { ADB_RSA_PUB_KEY = 0, ADB_DEVICE_GUID = 1 };
//   struct PeerInfo {
//       uint8_t type;
//       uint8_t data[kMaxPeerInfoSize - 1];   // kMaxPeerInfoSize = 8192
//   };
// sizeof(PeerInfo) == 8192 (1 type byte + 8191 data bytes).
//
// The on-wire PeerInfo is the full 8192-byte struct (zero-padded to kMax),
// NOT length-prefixed. Do not shorten the struct or add padding changes.

#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "adb/pairing/pairing_packet.h"

namespace adb {
namespace pairing {

// PeerInfoType (AOSP pairing_connection.h).
enum class PeerInfoType : uint8_t {
    ADB_RSA_PUB_KEY = 0,   // host sends its adb RSA public key (android_pubkey fmt)
    ADB_DEVICE_GUID = 1,   // device sends its GUID
};

// Fixed-size wire representation (8192 bytes total: 1 type + 8191 data).
struct PeerInfo {
    uint8_t type = 0;
    uint8_t data[kMaxPeerInfoSize - 1] = {0};

    size_t size() const { return sizeof(PeerInfo); }

    // Serialize to the exact 8192-byte wire form (no length prefix).
    std::vector<uint8_t> serialize() const {
        std::vector<uint8_t> buf(sizeof(PeerInfo));
        std::memcpy(buf.data(), this, sizeof(PeerInfo));
        return buf;
    }

    // Parse the exact 8192-byte wire form. Returns false if len != 8192.
    bool parse(const uint8_t* in, size_t len) {
        if (!in || len != sizeof(PeerInfo)) return false;
        std::memcpy(this, in, sizeof(PeerInfo));
        return true;
    }

    // Build a PeerInfo carrying the given payload (must fit in 8191 bytes).
    static PeerInfo make(PeerInfoType type, const uint8_t* payload, size_t payload_len) {
        PeerInfo info;
        info.type = static_cast<uint8_t>(type);
        if (payload_len > kMaxPeerInfoSize - 1) return info;  // leave zero
        std::memcpy(info.data, payload, payload_len);
        return info;
    }

    static PeerInfo make(PeerInfoType type, const std::string& payload) {
        return make(type, reinterpret_cast<const uint8_t*>(payload.data()),
                    payload.size());
    }

    // The meaningful payload (up to 8191 bytes; trailing zeros are padding).
    const uint8_t* payload() const { return data; }
    size_t payload_capacity() const { return kMaxPeerInfoSize - 1; }
};

}  // namespace pairing
}  // namespace adb
