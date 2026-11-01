// PlainTcpAdbConnection implementation.
//
// AT.LOCAL-TCP-1A: product transport.
//
// Plain TCP ADB: CNXN -> AUTH (RSA) -> device CNXN -> ONLINE.
// No STLS, no TLS. For legacy adbd TCP mode (tcpip service).
//
// Reference: AOSP adb.cpp:498-623 (legacy AUTH path),
//            transport.cpp (packet framing).
//
// This class is the TRANSPORT ONLY. The stream layer (OPEN/WRTE/OKAY/CLSE
// dispatch) is owned by the shared AdbStreamHub, NOT here. The single-stream
// caller-socket methods from the research spike (openService/readStream/
// writeStream/closeStream/sendOkay) are intentionally ABSENT.

#include "adb/connection/plain_tcp_connection.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <chrono>
#include <thread>

#define LOG_TAG "PlainTcpADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

PlainTcpAdbConnection::PlainTcpAdbConnection(TcpTransport* transport,
                                             AdbKeyStore* keys)
    : transport_(transport), keys_(keys) {}

PlainTcpAdbConnection::~PlainTcpAdbConnection() {
    disconnect();
}

bool PlainTcpAdbConnection::connect(const std::string& host, int port,
                                    std::string* error) {
    LOGI("CONNECT_START %s:%d (plain TCP, no TLS)", host.c_str(), port);

    // 1. TCP connect.
    if (!transport_->connect(host, port)) {
        std::string err = "TCP_ERROR: " + transport_->last_error();
        if (error) *error = err;
        LOGE("TCP_ERROR: %s", transport_->last_error().c_str());
        return false;
    }
    LOGI("TCP_CONNECTED %s:%d (fd=%d)", host.c_str(), port, transport_->fd());

    // 2. Send our CNXN.
    AdbPacket cnxn;
    cnxn.msg.command = static_cast<uint32_t>(AdbCommand::kCnxn);
    cnxn.msg.arg0 = 0x01000000u;  // A_VERSION (with checksum, legacy)
    cnxn.msg.arg1 = 1048576;      // 1MB max payload
    const std::string banner = "host::A35";
    cnxn.payload.assign(banner.begin(), banner.end());
    cnxn.msg.data_length = static_cast<uint32_t>(cnxn.payload.size());
    cnxn.SetMagic();
    LOGI("TX_CNXN version=0x%08x maxdata=%u banner=\"%s\"", cnxn.msg.arg0,
         cnxn.msg.arg1, banner.c_str());

    std::vector<uint8_t> buf;
    size_t len = EncodePacket(cnxn, buf, /*skip_checksum=*/false);
    if (len == 0) {
        if (error) *error = "CNXN encode failed";
        transport_->close();
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(tx_mutex_);
        if (!transport_->writeExact(buf.data(), buf.size())) {
            if (error) *error = transport_->last_error();
            LOGE("TX_CNXN failed: %s", error->c_str());
            transport_->close();
            return false;
        }
    }

    // Give adbd time to process the CNXN and set up the AUTH challenge.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    // 3. Handle AUTH exchange (device may challenge with token).
    if (!handleAuth(error)) {
        if (!error || error->empty()) *error = "AUTH exchange failed";
        transport_->close();
        return false;
    }

    online_ = true;
    LOGI("ONLINE protocol_version=0x%08x max_payload=%u", protocol_version_,
         max_payload_);
    return true;
}

void PlainTcpAdbConnection::disconnect() {
    if (transport_) transport_->close();
    online_ = false;
}

bool PlainTcpAdbConnection::handleAuth(std::string* error) {
    // Legacy ADB AUTH protocol:
    //   1. Device sends AUTH(token) -> we sign and send AUTH(signature)
    //   2. If signature accepted -> device sends CNXN
    //   3. If signature rejected (key not authorized) -> device sends
    //      AUTH(RSA_PUBLIC_KEY) requesting our public key
    //   4. If we send our public key -> device shows "Allow debugging?"
    //      dialog -> user approves -> device adds key -> sends CNXN
    //
    // Strategy: try signature first (up to 2 rounds). If still rejected,
    // send our public key to trigger the user authorization dialog.
    int auth_rounds = 0;
    bool sent_pubkey = false;

    auto auth_deadline = std::chrono::steady_clock::now() +
                         std::chrono::milliseconds(30000);
    for (;;) {
        AdbPacket rx;
        std::string rerr;
        if (!readPacket(&rx, &rerr)) {
            // Transient: retry until deadline.
            if (rerr.find("Try again") != std::string::npos ||
                rerr.find("Interrupted") != std::string::npos ||
                rerr.find("Temporary") != std::string::npos ||
                rerr.find("Operation timed out") != std::string::npos) {
                if (std::chrono::steady_clock::now() > auth_deadline) {
                    if (error) *error = "AUTH timeout";
                    return false;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            LOGE("AUTH read failed: %s", rerr.c_str());
            if (error) *error = rerr;
            return false;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kCnxn)) {
            std::string banner(rx.payload.begin(), rx.payload.end());
            LOGI("RX_CNXN banner=\"%s\"", banner.c_str());
            protocol_version_ = kAdbVersion;
            max_payload_ = kMaxPayload;
            if (rx.msg.arg0 > 0) protocol_version_ = rx.msg.arg0;
            if (rx.msg.arg1 > 0) max_payload_ = rx.msg.arg1;
            LOGI("Negotiated: version=0x%08x max_payload=%u",
                 protocol_version_, max_payload_);
            return true;
        }

        if (rx.msg.command == static_cast<uint32_t>(AdbCommand::kAuth)) {
            auth_rounds++;
            uint32_t auth_type = rx.msg.arg0;
            LOGI("RX_AUTH type=%u len=%u (round %d)", auth_type,
                 rx.msg.data_length, auth_rounds);

            if (auth_type == static_cast<uint32_t>(AuthType::kToken)) {
                if (rx.msg.data_length < 20 || rx.payload.size() < 20) {
                    if (error) *error = "AUTH: malformed token";
                    return false;
                }

                // If we've already tried signature twice, send pubkey instead.
                if (auth_rounds > 2 && !sent_pubkey) {
                    sent_pubkey = true;
                    LOGI("AUTH: signature rejected twice, sending public key");
                    std::string pub = keys_->publicKey();
                    if (pub.empty()) {
                        if (error) *error = "AUTH: no public key";
                        return false;
                    }
                    AdbPacket auth_reply;
                    auth_reply.msg.command = static_cast<uint32_t>(AdbCommand::kAuth);
                    auth_reply.msg.arg0 = static_cast<uint32_t>(AuthType::kRsaPublicKey);
                    auth_reply.msg.arg1 = 0;
                    auth_reply.payload.assign(pub.begin(), pub.end());
                    auth_reply.msg.data_length = static_cast<uint32_t>(auth_reply.payload.size());
                    auth_reply.SetMagic();
                    LOGI("TX_AUTH public_key len=%u (triggering user dialog)",
                         auth_reply.msg.data_length);
                    if (!writePacket(auth_reply, error)) return false;
                    continue;
                }

                // Sign the token with RSA (raw signature bytes).
                std::string signature = keys_->signSha1(rx.payload.data(), 20);
                if (signature.empty()) {
                    if (error) *error = "AUTH: RSA sign failed";
                    return false;
                }
                LOGI("AUTH sig len=%zu (raw RSA bytes)", signature.size());

                AdbPacket auth_reply;
                auth_reply.msg.command = static_cast<uint32_t>(AdbCommand::kAuth);
                auth_reply.msg.arg0 = static_cast<uint32_t>(AuthType::kSignature);
                auth_reply.msg.arg1 = 0;
                auth_reply.payload.assign(signature.begin(), signature.end());
                auth_reply.msg.data_length = static_cast<uint32_t>(auth_reply.payload.size());
                auth_reply.SetMagic();
                LOGI("TX_AUTH signature len=%u", auth_reply.msg.data_length);
                if (!writePacket(auth_reply, error)) return false;
                continue;
            }

            if (auth_type == static_cast<uint32_t>(AuthType::kRsaPublicKey)) {
                std::string pub = keys_->publicKey();
                if (pub.empty()) {
                    if (error) *error = "AUTH: no public key";
                    return false;
                }
                AdbPacket auth_reply;
                auth_reply.msg.command = static_cast<uint32_t>(AdbCommand::kAuth);
                auth_reply.msg.arg0 = static_cast<uint32_t>(AuthType::kRsaPublicKey);
                auth_reply.msg.arg1 = 0;
                auth_reply.payload.assign(pub.begin(), pub.end());
                auth_reply.msg.data_length = static_cast<uint32_t>(auth_reply.payload.size());
                auth_reply.SetMagic();
                LOGI("TX_AUTH public_key len=%u (requested by device)",
                     auth_reply.msg.data_length);
                if (!writePacket(auth_reply, error)) return false;
                sent_pubkey = true;
                continue;
            }

            LOGE("AUTH: unknown type=%u", auth_type);
            if (error) *error = "AUTH: unknown type";
            return false;
        }

        LOGE("AUTH: unexpected packet %s before CNXN",
             CommandToString(rx.msg.command));
        if (error) *error = "unexpected packet before CNXN";
        return false;
    }
}

void PlainTcpAdbConnection::waitDeviceCnxn() {
    // In legacy mode, handleAuth() already consumed the device CNXN.
    LOGI("waitDeviceCnxn: CNXN already consumed in handleAuth");
}

bool PlainTcpAdbConnection::readPacket(AdbPacket* packet, std::string* error) {
    // Use negotiated version for checksum validation.
    // Before negotiation (AUTH phase), use legacy (with checksum).
    bool check_checksum = (protocol_version_ < 0x01000001u);
    if (!ReadPacket(transport_, *packet, check_checksum)) {
        if (error) *error = transport_->last_error();
        return false;
    }
    return true;
}

bool PlainTcpAdbConnection::writePacket(const AdbPacket& packet,
                                        std::string* error) {
    // Use negotiated protocol version to decide checksum.
    bool skip;
    if (packet.msg.command == static_cast<uint32_t>(AdbCommand::kCnxn)) {
        skip = false;  // CNXN: always checksum (legacy)
    } else {
        skip = (protocol_version_ >= 0x01000001u);
    }
    std::vector<uint8_t> buf;
    size_t len = EncodePacket(packet, buf, skip);
    if (len == 0) {
        if (error) *error = "encode failed";
        return false;
    }
    std::lock_guard<std::mutex> lock(tx_mutex_);
    if (!transport_->writeExact(buf.data(), buf.size())) {
        if (error) *error = transport_->last_error();
        return false;
    }
    return true;
}

uint32_t PlainTcpAdbConnection::allocateLocalId() {
    return next_local_id_++;
}

void PlainTcpAdbConnection::markTransportLost() {
    if (online_) {
        LOGI("markTransportLost: plain TCP transport lost");
        online_ = false;
    }
}

void PlainTcpAdbConnection::shutdownSocket() {
    if (transport_) {
        LOGI("shutdownSocket (transport half-shutdown)");
        transport_->shutdown();
    }
}

}  // namespace adb
