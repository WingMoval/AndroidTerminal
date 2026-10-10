// ADB protocol wire format.
//
// Reference: AOSP packages/modules/adb
//   - types.h        (amessage / apacket layout)
//   - adb.h          (command constants, A_VERSION)
//   - transport.cpp  (send_packet: magic + checksum rules)
//   - apacket_reader.cpp (receive framing)
//   - docs/dev/protocol.md (packet semantics)
//
// Step 1A: header + encode/read (24B little-endian header, checksum).
// No AUTH / OPEN / WRTE / shell state machine yet.

#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "transport/adb_transport.h"

namespace adb {

class TcpTransport;

// Maximum payload advertised to adbd in CNXN.arg1. Matches AOSP adb.h
// MAX_PAYLOAD (1 MiB, adb.h:34). adbd caps the effective value to its own
// max (transport.cpp:1174), so 1 MiB is the safe client-side figure.
constexpr uint32_t kMaxPayload = 1024 * 1024;

// Connect/auth packets are limited to this because we don't yet know the
// peer's max (AOSP adb.cpp:338-343, MAX_PAYLOAD_V1).
constexpr uint32_t kMaxPayloadV1 = 4 * 1024;

// Protocol version, matches AOSP adb.h A_VERSION (0x01000001).
constexpr uint32_t kAdbVersion = 0x01000001u;

// ADB message command identifiers. Values are the ASCII strings read
// little-endian, exactly as defined in AOSP adb.h:43-48.
enum class AdbCommand : uint32_t {
    kSync  = 0x434e5953u,  // "SYNS" - obsolete, kept for completeness
    kCnxn  = 0x4e584e43u,  // "CNXN" - CONNECT
    kAuth  = 0x48545541u,  // "AUTH" - authentication
    kOpen  = 0x4e45504fu,  // "OPEN" - open a service stream
    kOkay  = 0x59414b4fu,  // "OKAY" - READY / credit ack
    kClse  = 0x45534c43u,  // "CLSE" - CLOSE
    kWrte  = 0x45545257u,  // "WRTE" - WRITE
    kStls  = 0x534c5453u,  // "STLS" - TLS handshake request
};

// AUTH packet arg0 sub-types (AOSP adb_auth.h:29-32).
enum class AuthType : uint32_t {
    kToken = 1,          // device -> client: random challenge
    kSignature = 2,      // client -> device: RSA signature over token
    kRsaPublicKey = 3,   // client -> device: register new public key
};

// 24-byte ADB packet header. Wire format is little-endian on the wire;
// on little-endian hosts this struct can be memcpy'd directly (AOSP does
// exactly this in transport_fd.cpp).
#pragma pack(push, 1)
struct AdbMessage {
    uint32_t command;      // AdbCommand
    uint32_t arg0;         // meaning depends on command
    uint32_t arg1;         // meaning depends on command
    uint32_t data_length;  // payload byte count
    uint32_t checksum;     // crc32 of payload (0 when data_length == 0)
    uint32_t magic;        // command ^ 0xffffffff
};
#pragma pack(pop)

static_assert(sizeof(AdbMessage) == 24, "AdbMessage must be 24 bytes");

// A full ADB packet: 24-byte header + payload.
struct AdbPacket {
    AdbMessage msg{};
    std::vector<uint8_t> payload;

    // magic = command ^ 0xffffffff (AOSP transport.cpp:565).
    void SetMagic() { msg.magic = msg.command ^ 0xffffffffu; }

    // Byte-wise sum of the payload (AOSP calculate_apacket_checksum,
    // adb.cpp:113-119). Required for CNXN/AUTH because the client starts
    // at A_VERSION_MIN, below A_VERSION_SKIP_CHECKSUM (transport.cpp:567-571).
    static uint32_t ComputeChecksum(const uint8_t* data, uint32_t length) {
        uint32_t sum = 0;
        for (uint32_t i = 0; i < length; ++i) sum += data[i];
        return sum;
    }

    bool MagicValid() const { return msg.magic == (msg.command ^ 0xffffffffu); }
};

// Human-readable command name (AOSP command_to_string, adb.cpp:121-142).
const char* CommandToString(uint32_t cmd);

// Serialize `packet` into a 24-byte header + payload buffer. The header is
// written little-endian. `packet.msg.checksum` and `packet.msg.magic` are
// filled in here (checksum computed from the payload, magic from command).
// Returns the total byte count (24 + payload.size()) and writes it to
// `out` (which is resized).
size_t EncodePacket(const AdbPacket& packet, std::vector<uint8_t>& out,
                    bool skip_checksum = false);

// Read exactly one packet from a TcpTransport. Reads the 24-byte header
// first, then the payload (handling partial reads). Verifies the magic and,
// when the payload is non-empty and `check_checksum` is true, the checksum.
// Returns false on socket error / EOF / bad framing; the transport's
// last_error() carries the errno/strerror detail.
bool ReadPacket(TcpTransport* transport, AdbPacket& out, bool check_checksum);

}  // namespace adb
