// AdbConnection implementation.
//
// CNXN/STLS/TLS handshake, protocol negotiation, local_id allocation.
// Reference: AOSP transport.cpp:512-556, adb.cpp:267-290, adb.cpp:498-623.

#include "adb/connection/adb_connection.h"

#include <android/log.h>

#include <cstring>

#include "adb/client/adb_key_store.h"
#include "adb_protocol/adb_message.h"

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

AdbConnection::AdbConnection(TlsTransport* transport, AdbKeyStore* keys)
    : transport_(transport), keys_(keys) {}

AdbConnection::~AdbConnection() {
    disconnect();
}

FeatureSet AdbConnection::localSupportedFeatures() {
    // Only declare features that AndroidTerminal actually implements.
    // Do NOT copy AOSP's full feature list.
    // Reference v1: shell_v2 is the only feature we truly support.
    FeatureSet set;
    // We don't have a public API to add features, so build via banner parse.
    (void)set;
    return FeatureSet::parseBanner("host::features=shell_v2");
}

std::string AdbConnection::buildLocalBanner() {
    return "host::features=shell_v2";
}

bool AdbConnection::sendCnxn() {
    AdbPacket cnxn;
    cnxn.msg.command = static_cast<uint32_t>(AdbCommand::kCnxn);
    cnxn.msg.arg0 = kAdbVersion;
    cnxn.msg.arg1 = kMaxPayload;
    const std::string banner = buildLocalBanner();
    cnxn.payload.assign(banner.begin(), banner.end());
    cnxn.msg.data_length = static_cast<uint32_t>(cnxn.payload.size());

    const char* phase = transport_->is_tls() ? "post-TLS" : "pre-TLS";
    LOGI("[C03] TX_CNXN_%s version=0x%08x maxdata=%u banner=\"%s\"",
          phase, cnxn.msg.arg0, cnxn.msg.arg1, banner.c_str());

    std::string err;
    if (!writePacket(cnxn, &err)) {
        LOGE("[C03] TX_CNXN_%s failed: %s", phase, err.c_str());
        return false;
    }
    return true;
}

bool AdbConnection::waitStls() {
    for (int i = 0; i < 10; ++i) {
        AdbPacket rx;
        std::string err;
        if (!readPacket(&rx, &err)) {
            LOGE("[C04] RX_STLS read failed: %s", err.c_str());
            return false;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kStls)) {
            LOGI("[C04] RX_STLS (device requests TLS upgrade)");
            return true;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kAuth)) {
            LOGE("[C04] PROTOCOL_ERROR: unexpected pre-TLS AUTH (arg0=%u)",
                 rx.msg.arg0);
            return false;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kCnxn)) {
            LOGE("[C04] PROTOCOL_ERROR: unexpected pre-TLS CNXN");
            return false;
        }

        LOGI("[C04] pre-TLS unexpected %s, continuing",
             CommandToString(rx.msg.command));
    }
    LOGE("[C04] no STLS received after 10 packets");
    return false;
}

bool AdbConnection::sendStlsReply() {
    AdbPacket stls;
    stls.msg.command = static_cast<uint32_t>(AdbCommand::kStls);
    stls.msg.arg0 = 0x01000000u;  // A_STLS_VERSION
    stls.msg.arg1 = 0;
    stls.msg.data_length = 0;

    LOGI("[C05] TX_STLS (reply to device, pre-TLS)");

    std::string err;
    if (!writePacket(stls, &err)) {
        LOGE("[C05] TX_STLS failed: %s", err.c_str());
        return false;
    }
    return true;
}

bool AdbConnection::doTlsHandshake(std::string* error) {
    LOGI("[C06] TLS_START");
    if (!transport_->startTls(keys_->certificate(), keys_->privateKeyPem())) {
        if (error) *error = "TLS handshake failed: " + transport_->last_error();
        LOGE("[C06] TLS_ERROR: %s", transport_->last_error().c_str());
        return false;
    }
    LOGI("[C07] TLS_OK");
    return true;
}

bool AdbConnection::waitPostTlsCnxn(std::string* error) {
    for (int i = 0; i < 10; ++i) {
        AdbPacket rx;
        std::string err;
        if (!readPacket(&rx, &err)) {
            if (error) *error = "post-TLS read failed: " + err;
            LOGE("[C09] RX_CNXN_POST_TLS read failed: %s", err.c_str());
            return false;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kCnxn)) {
            // Parse device CNXN banner for remote features.
            std::string banner(rx.payload.begin(), rx.payload.end());
            remote_features_ = FeatureSet::parseBanner(banner);

            // Version negotiation: min(local, remote).
            uint32_t remote_version = rx.msg.arg0;
            protocol_version_ = (kAdbVersion < remote_version) ? kAdbVersion
                                                               : remote_version;
            LOGI("[C10] NEGOTIATED_VERSION=0x%08x (local=0x%08x remote=0x%08x)",
                 protocol_version_, kAdbVersion, remote_version);

            // Max payload negotiation: min(local, remote).
            uint32_t remote_maxdata = rx.msg.arg1;
            max_payload_ = (kMaxPayload < remote_maxdata) ? kMaxPayload
                                                          : remote_maxdata;
            LOGI("[C11] NEGOTIATED_MAX_PAYLOAD=%u (local=%u remote=%u)",
                 max_payload_, kMaxPayload, remote_maxdata);

            // Log remote features.
            std::string feat_str;
            for (const auto& f : remote_features_.values()) {
                if (!feat_str.empty()) feat_str += ",";
                feat_str += f;
            }
            LOGI("[C12] REMOTE_FEATURES=[%s] (count=%zu)",
                  feat_str.c_str(), remote_features_.values().size());
            LOGI("[C09] RX_CNXN_POST_TLS arg0=0x%08x banner=%zu bytes",
                  rx.msg.arg0, rx.payload.size());
            return true;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kAuth)) {
            if (error) *error = "NEGOTIATION_ERROR: AUTH received (key not authorized?)";
            LOGE("[C09] NEGOTIATION_ERROR: post-TLS AUTH (arg0=%u)", rx.msg.arg0);
            return false;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kOpen) ||
            rx.msg.command == static_cast<uint32_t>(AdbCommand::kWrte) ||
            rx.msg.command == static_cast<uint32_t>(AdbCommand::kClse)) {
            if (error) *error = "PROTOCOL_ERROR: " + std::string(CommandToString(rx.msg.command)) + " before ONLINE";
            LOGE("[C09] PROTOCOL_ERROR: %s before ONLINE",
                 CommandToString(rx.msg.command));
            return false;
        }

        LOGI("[C09] unexpected %s, continuing", CommandToString(rx.msg.command));
    }
    if (error) *error = "no device CNXN after 10 packets";
    LOGE("[C09] no device CNXN after 10 packets");
    return false;
}

bool AdbConnection::connect(const std::string& host, int port,
                            std::string* error) {
    LOGI("[C01] CONNECT_START %s:%d", host.c_str(), port);
    state_ = ConnectionState::Connecting;

    // 1. TCP connect.
    if (!transport_->connect(host, port)) {
        if (error) *error = "TCP_ERROR: " + transport_->last_error();
        LOGE("[C02] TCP_ERROR: %s", transport_->last_error().c_str());
        state_ = ConnectionState::Disconnected;
        return false;
    }
    LOGI("[C02] TCP_CONNECTED %s:%d", host.c_str(), port);

    // 2. Pre-TLS CNXN.
    state_ = ConnectionState::WaitingStls;
    if (!sendCnxn()) {
        if (error) *error = "PROTOCOL_ERROR: pre-TLS CNXN failed";
        transport_->close();
        state_ = ConnectionState::Disconnected;
        return false;
    }

    // 3. Wait for STLS.
    if (!waitStls()) {
        if (error) *error = "PROTOCOL_ERROR: no STLS from device";
        transport_->close();
        state_ = ConnectionState::Disconnected;
        return false;
    }

    // 4. Reply STLS.
    if (!sendStlsReply()) {
        if (error) *error = "PROTOCOL_ERROR: STLS reply failed";
        transport_->close();
        state_ = ConnectionState::Disconnected;
        return false;
    }

    // 5. TLS handshake.
    state_ = ConnectionState::TlsNegotiating;
    if (!doTlsHandshake(error)) {
        transport_->close();
        state_ = ConnectionState::Disconnected;
        return false;
    }

    // 6. DO NOT send post-TLS CNXN (AOSP client does not send post-TLS CNXN;
    //    only the server/device sends it). Wait for device CNXN instead.
    state_ = ConnectionState::WaitingPostTlsCnxn;
    LOGI("[C08] POST_TLS_HOST_CNXN_SKIPPED (waiting for device CNXN)");

    // 7. Wait for device CNXN + negotiate.
    if (!waitPostTlsCnxn(error)) {
        transport_->close();
        state_ = ConnectionState::Disconnected;
        return false;
    }

    // 8. ONLINE.
    state_ = ConnectionState::Online;
    local_features_ = localSupportedFeatures();
    LOGI("[C13] ONLINE protocol_version=0x%08x max_payload=%u",
          protocol_version_, max_payload_);
    return true;
}

void AdbConnection::disconnect() {
    if (state_ == ConnectionState::Disconnected) return;

    state_ = ConnectionState::Closing;
    LOGI("AdbConnection::disconnect: closing");
    transport_->close();
    remote_features_ = FeatureSet();
    protocol_version_ = 0;
    max_payload_ = 0;
    state_ = ConnectionState::Disconnected;
}

void AdbConnection::shutdownSocket() { transport_->shutdownSocket(); }

void AdbConnection::markTransportLost() {
    if (state_ != ConnectionState::Online) return;
    state_ = ConnectionState::Offline;
    LOGE("[C14] TRANSPORT_LOST: connection marked Offline (real transport failure)");
}

bool AdbConnection::readPacket(AdbPacket* packet, std::string* error) {
    if (!transport_->is_open()) {
        if (error) *error = "transport not open";
        return false;
    }

    uint8_t header[24];
    if (!transport_->readExact(header, 24)) {
        if (error) *error = "failed to read 24-byte header: " + transport_->last_error();
        return false;
    }

    auto getLe32 = [](const uint8_t* in) -> uint32_t {
        return static_cast<uint32_t>(in[0]) |
               (static_cast<uint32_t>(in[1]) << 8) |
               (static_cast<uint32_t>(in[2]) << 16) |
               (static_cast<uint32_t>(in[3]) << 24);
    };

    AdbMessage& m = packet->msg;
    m.command = getLe32(header + 0);
    m.arg0 = getLe32(header + 4);
    m.arg1 = getLe32(header + 8);
    m.data_length = getLe32(header + 12);
    m.checksum = getLe32(header + 16);
    m.magic = getLe32(header + 20);

    const uint32_t len = m.data_length;
    uint32_t max = max_payload_;
    if (max == 0) max = kMaxPayload;
    if (len > max) {
        if (error) *error = "data_length " + std::to_string(len) + " exceeds max " + std::to_string(max);
        return false;
    }

    packet->payload.assign(len, 0);
    if (len > 0) {
        if (!transport_->readExact(packet->payload.data(), len)) {
            if (error) *error = "failed to read " + std::to_string(len) + "-byte payload: " + transport_->last_error();
            return false;
        }
    }

    if (!packet->MagicValid()) {
        if (error) *error = "bad magic for " + std::string(CommandToString(m.command));
        return false;
    }

    // Checksum: skip if version >= A_VERSION_SKIP_CHECKSUM or TLS active.
    // AOSP transport.cpp:564-571.
    bool effective_check = len > 0 && !transport_->is_tls();
    if (effective_check && protocol_version_ < kAdbVersion) {
        const uint32_t computed =
            AdbPacket::ComputeChecksum(packet->payload.data(), len);
        if (computed != m.checksum) {
            if (error) *error = "checksum mismatch for " + std::string(CommandToString(m.command));
            return false;
        }
    }

    LOGI("RX %s arg0=0x%08x arg1=0x%08x len=%u tls=%d",
          CommandToString(m.command), m.arg0, m.arg1, len,
          transport_->is_tls() ? 1 : 0);
    tracePacket("RX", *packet);
    return true;
}

bool AdbConnection::writePacket(const AdbPacket& packet, std::string* error) {
    // AT.V3-2.1 §15: connection-wide TX serialization. All post-ONLINE packet
    // writes (OPEN/WRTE/OKAY/CLSE from any logical stream or the hub RX
    // dispatcher) go through this mutex so complete packets never interleave.
    std::lock_guard<std::mutex> tx_lock(tx_mutex_);

    // Post-TLS or version >= A_VERSION_SKIP_CHECKSUM: checksum = 0.
    bool skip_checksum = transport_->is_tls() ||
                         (protocol_version_ >= kAdbVersion && protocol_version_ != 0);
    std::vector<uint8_t> wire;
    EncodePacket(packet, wire, skip_checksum);

    char hex[64] = {0};
    for (int i = 0; i < 24 && i < (int)wire.size(); i++) {
        sprintf(hex + i * 2, "%02x", wire[i]);
    }
    LOGI("TX %s skip_checksum=%d header=[%s] len=%zu",
         CommandToString(packet.msg.command), skip_checksum ? 1 : 0,
         hex, wire.size());

    tracePacket("TX", packet);
    if (!transport_->writeExact(wire.data(), wire.size())) {
        if (error) *error = "write failed: " + transport_->last_error();
        return false;
    }
    return true;
}

bool AdbConnection::canUseFeature(const std::string& feature) const {
    // Reference v1: remote contains feature AND local contains feature.
    return remote_features_.contains(feature) &&
           local_features_.contains(feature);
}

uint32_t AdbConnection::allocateLocalId() {
    uint32_t id = next_local_id_;
    next_local_id_++;
    if (next_local_id_ == 0) next_local_id_ = 1;  // Skip 0 on overflow.
    return id;
}

void AdbConnection::tracePacket(const char* dir, const AdbPacket& pkt) const {
    uint32_t seq = ++packet_seq_;
    char state_str[32];
    switch (state_) {
        case ConnectionState::Disconnected: strcpy(state_str, "Disconnected"); break;
        case ConnectionState::Connecting: strcpy(state_str, "Connecting"); break;
        case ConnectionState::WaitingStls: strcpy(state_str, "WaitingStls"); break;
        case ConnectionState::TlsNegotiating: strcpy(state_str, "TlsNegotiating"); break;
        case ConnectionState::WaitingPostTlsCnxn: strcpy(state_str, "WaitingPostTlsCnxn"); break;
        case ConnectionState::Online: strcpy(state_str, "Online"); break;
        case ConnectionState::Closing: strcpy(state_str, "Closing"); break;
        case ConnectionState::Offline: strcpy(state_str, "Offline"); break;
        default: strcpy(state_str, "?"); break;
    }
    LOGI("[P%03u] %s state=%s tls=%d %s arg0=0x%08x arg1=0x%08x len=%u cksum=0x%08x magic=0x%08x",
         seq, dir, state_str, transport_->is_tls() ? 1 : 0,
         CommandToString(pkt.msg.command), pkt.msg.arg0, pkt.msg.arg1,
         pkt.msg.data_length, pkt.msg.checksum, pkt.msg.magic);

    // For OPEN: dump payload ASCII + hex.
    if (pkt.msg.command == static_cast<uint32_t>(AdbCommand::kOpen)) {
        std::string ascii;
        std::string hex;
        for (size_t i = 0; i < pkt.payload.size(); i++) {
            uint8_t b = pkt.payload[i];
            ascii += (b >= 32 && b < 127) ? static_cast<char>(b) : '.';
            char hb[4];
            sprintf(hb, "%02x ", b);
            hex += hb;
        }
        LOGI("[P%03u] OPEN service: \"%s\" hex=[%s] len=%zu",
             seq, ascii.c_str(), hex.c_str(), pkt.payload.size());
    }
}

}  // namespace adb
