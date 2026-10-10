// ADB packet encode/read — Step 1A.
//
// Reference (AOSP packages/modules/adb):
//   - types.h:148-161            (amessage 24-byte layout)
//   - adb.cpp:113-119            (calculate_apacket_checksum: byte sum)
//   - transport.cpp:564-571      (send_packet: magic + checksum rules)
//   - transport.cpp:1693         (receive: magic verification)
//   - apacket_reader.cpp:33-113  (receive framing: 24B header then payload)
//
// The wire format is little-endian. On the ARM/x86 Android targets this
// struct is memcpy'd directly (AOSP transport_fd.cpp does the same). For
// correctness and portability we still serialize field-by-field below.

#include "adb_protocol/adb_message.h"

#include <android/log.h>

#include <arpa/inet.h>

#include "transport/tcp_transport.h"

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

namespace {
constexpr size_t kHeaderSize = 24;

// Put a uint32 into `out` in little-endian byte order.
void PutLe32(uint8_t* out, uint32_t v) {
    out[0] = static_cast<uint8_t>(v & 0xff);
    out[1] = static_cast<uint8_t>((v >> 8) & 0xff);
    out[2] = static_cast<uint8_t>((v >> 16) & 0xff);
    out[3] = static_cast<uint8_t>((v >> 24) & 0xff);
}

// Read a little-endian uint32 from `in`.
uint32_t GetLe32(const uint8_t* in) {
    return static_cast<uint32_t>(in[0]) |
           (static_cast<uint32_t>(in[1]) << 8) |
           (static_cast<uint32_t>(in[2]) << 16) |
           (static_cast<uint32_t>(in[3]) << 24);
}
}  // namespace

const char* CommandToString(uint32_t cmd) {
    switch (cmd) {
        case 0x434e5953u: return "SYNC";
        case 0x4e584e43u: return "CNXN";
        case 0x48545541u: return "AUTH";
        case 0x4e45504fu: return "OPEN";
        case 0x59414b4fu: return "OKAY";
        case 0x45534c43u: return "CLSE";
        case 0x45545257u: return "WRTE";
        case 0x534c5453u: return "STLS";
        default:          return "UNKNOWN";
    }
}

size_t EncodePacket(const AdbPacket& packet, std::vector<uint8_t>& out,
                    bool skip_checksum) {
    const uint32_t len = static_cast<uint32_t>(packet.payload.size());
    // AOSP transport.cpp:564-571: when protocol version >= A_VERSION_SKIP_CHECKSUM
    // (0x01000001), checksum is 0. Pre-TLS uses version 0x01000000 (with checksum),
    // post-TLS uses 0x01000001 (skip checksum).
    uint32_t checksum = skip_checksum ? 0 : AdbPacket::ComputeChecksum(packet.payload.data(), len);

    out.resize(kHeaderSize + len);
    PutLe32(out.data() + 0, packet.msg.command);
    PutLe32(out.data() + 4, packet.msg.arg0);
    PutLe32(out.data() + 8, packet.msg.arg1);
    PutLe32(out.data() + 12, len);
    PutLe32(out.data() + 16, checksum);
    PutLe32(out.data() + 20, packet.msg.command ^ 0xffffffffu);  // magic
    if (len > 0) std::memcpy(out.data() + kHeaderSize, packet.payload.data(), len);
    return out.size();
}

bool ReadPacket(TcpTransport* transport, AdbPacket& out, bool check_checksum) {
    if (!transport->is_open()) {
        LOGE("ReadPacket: transport not open");
        return false;
    }

    uint8_t header[kHeaderSize];
    if (!transport->readExact(header, kHeaderSize)) {
        LOGE("ReadPacket: failed to read 24-byte header: %s",
             transport->last_error().c_str());
        return false;
    }

    AdbMessage& m = out.msg;
    m.command = GetLe32(header + 0);
    m.arg0 = GetLe32(header + 4);
    m.arg1 = GetLe32(header + 8);
    m.data_length = GetLe32(header + 12);
    m.checksum = GetLe32(header + 16);
    m.magic = GetLe32(header + 20);

    const uint32_t len = m.data_length;
    if (len > kMaxPayload) {
        LOGE("ReadPacket: data_length %u exceeds max %u", len,
             static_cast<unsigned>(kMaxPayload));
        return false;
    }

    out.payload.assign(len, 0);
    if (len > 0) {
        if (!transport->readExact(out.payload.data(), len)) {
            LOGE("ReadPacket: failed to read %u-byte payload: %s", len,
                 transport->last_error().c_str());
            return false;
        }
    }

    if (!out.MagicValid()) {
        LOGE("ReadPacket: bad magic for %s (magic=0x%08x, expect 0x%08x)",
             CommandToString(m.command), m.magic,
             m.command ^ 0xffffffffu);
        return false;
    }

    if (check_checksum && len > 0) {
        const uint32_t computed =
            AdbPacket::ComputeChecksum(out.payload.data(), len);
        if (computed != m.checksum) {
            LOGE("ReadPacket: checksum mismatch for %s (got 0x%08x, expect 0x%08x)",
                 CommandToString(m.command), m.checksum, computed);
            return false;
        }
    }

    LOGI("ReadPacket OK: %s arg0=0x%08x arg1=0x%08x len=%u",
         CommandToString(m.command), m.arg0, m.arg1, len);
    return true;
}

}  // namespace adb
