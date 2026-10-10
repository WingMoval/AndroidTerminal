// PairingClient implementation (AOSP pairing_connection.cpp client path).

#include "adb/pairing/pairing_client.h"

#include <android/log.h>

#include <cstring>

#include "adb/client/adb_key_store.h"
#include "adb/pairing/pairing_aes_gcm.h"
#include "adb/pairing/pairing_auth.h"
#include "adb/pairing/pairing_packet.h"
#include "adb/pairing/pairing_peerinfo.h"
#include "adb/transport/tls_transport.h"

#include <openssl/curve25519.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {
using namespace adb::pairing;

namespace {

// Read one framed PairingPacket over the transport. Returns the payload and
// sets *type on success. Returns false on I/O or framing error.
bool readPacket(TlsTransport& t, PairingPacketType* type,
                std::vector<uint8_t>* payload, std::string* error) {
    uint8_t header[6];
    if (!t.readExact(header, 6)) {
        *error = "failed to read pairing packet header: " + t.last_error();
        return false;
    }
    PairingPacketHeader h;
    if (!decodeHeader(header, 6, &h)) {
        *error = "invalid pairing packet header (version=" +
                 std::to_string(h.version) + ", type=" + std::to_string(h.type) +
                 ", payload_size=" + std::to_string(h.payload_size) + ")";
        return false;
    }
    *type = static_cast<PairingPacketType>(h.type);
    payload->resize(h.payload_size);
    if (h.payload_size > 0 &&
        !t.readExact(payload->data(), h.payload_size)) {
        *error = "failed to read pairing packet payload: " + t.last_error();
        return false;
    }
    return true;
}

// Write one framed PairingPacket over the transport.
bool writePacket(TlsTransport& t, PairingPacketType type,
                 const uint8_t* payload, size_t payload_len, std::string* error) {
    std::vector<uint8_t> buf = buildPacket(type, payload, payload_len);
    if (buf.empty()) {
        *error = "failed to build pairing packet (payload too large)";
        return false;
    }
    if (!t.writeExact(buf.data(), buf.size())) {
        *error = "failed to write pairing packet: " + t.last_error();
        return false;
    }
    return true;
}

// Validate the pairing code: must be exactly 6 ASCII decimal digits.
bool validPairingCode(const std::string& code) {
    if (code.size() != 6) return false;
    for (char c : code) {
        if (c < '0' || c > '9') return false;
    }
    return true;
}

}  // namespace

PairingClient::PairingClient(AdbKeyStore* keys) : keys_(keys) {}
PairingClient::~PairingClient() = default;

PairingResult PairingClient::pair(const std::string& host, int port,
                                  const std::string& pairing_code) {
    PairingResult result;
    std::string error;

    LOGI("PairingClient::pair: %s:%d code=%s",
         host.c_str(), port, pairing_code.c_str());

    // 0. Validate the code (Core input validation, not a UI concern).
    if (!validPairingCode(pairing_code)) {
        result.status = PairingStatus::InvalidCode;
        result.error = "pairing code must be exactly 6 decimal digits (got " +
                       std::to_string(pairing_code.size()) + " chars)";
        LOGE("PairingClient::pair: invalid code (%zu chars)",
             pairing_code.size());
        return result;
    }

    // 1. Ensure the identity is loaded (pair key == connect key).
    if (!keys_ || !keys_->is_loaded()) {
        if (!keys_ || !keys_->loadOrCreate()) {
            result.status = PairingStatus::IoError;
            result.error = "failed to load/create adb identity";
            LOGE("PairingClient::pair: key loadOrCreate failed");
            return result;
        }
    }

    // 2. TCP connect to the pairing port.
    TlsTransport transport;
    if (!transport.connect(host, port)) {
        result.status = PairingStatus::TcpConnectFailed;
        result.error = "TCP connect to pairing port " + host + ":" +
                       std::to_string(port) + " failed: " + transport.last_error();
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }

    // 3. TLS 1.3 handshake (accept-any peer cert).
    if (!transport.startTls(keys_->certificate(), keys_->privateKeyPem())) {
        result.status = PairingStatus::TlsHandshakeFailed;
        result.error = "TLS 1.3 handshake with device failed: " +
                       transport.last_error();
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }

    // 4. SPAKE2 password = pairing_code bytes || 64-byte TLS ExportKeyingMaterial.
    std::vector<uint8_t> ekm = transport.exportKeyingMaterial(kTlsExporterLength);
    if (ekm.size() != kTlsExporterLength) {
        result.status = PairingStatus::TlsExporterFailed;
        result.error = "TLS ExportKeyingMaterial(64) failed (got " +
                       std::to_string(ekm.size()) + " bytes)";
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }
    std::vector<uint8_t> password;
    password.reserve(pairing_code.size() + ekm.size());
    password.insert(password.end(), pairing_code.begin(), pairing_code.end());
    password.insert(password.end(), ekm.begin(), ekm.end());

    // 5. SPAKE2 (client = alice). Send our message, read the peer's.
    PairingAuth auth(/*role_is_client=*/true);
    if (!auth.valid()) {
        result.status = PairingStatus::Spake2ExchangeFailed;
        result.error = "SPAKE2 context creation failed";
        return result;
    }
    std::vector<uint8_t> my_msg = auth.generateMsg(password.data(), password.size());
    if (my_msg.empty()) {
        result.status = PairingStatus::Spake2ExchangeFailed;
        result.error = "SPAKE2 generateMsg failed";
        LOGE("PairingClient::pair: SPAKE2 generateMsg failed");
        return result;
    }
    // Wipe the password (it contains the 6-digit code).
    std::memset(password.data(), 0, password.size());

    if (!writePacket(transport, PairingPacketType::SPAKE2_MSG,
                     my_msg.data(), my_msg.size(), &error)) {
        result.status = PairingStatus::Spake2ExchangeFailed;
        result.error = "write SPAKE2_MSG: " + error;
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }

    PairingPacketType rx_type;
    std::vector<uint8_t> rx_payload;
    if (!readPacket(transport, &rx_type, &rx_payload, &error)) {
        result.status = PairingStatus::Spake2ExchangeFailed;
        result.error = "read peer SPAKE2_MSG: " + error;
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }
    if (rx_type != PairingPacketType::SPAKE2_MSG ||
        rx_payload.size() != SPAKE2_MAX_MSG_SIZE) {
        result.status = PairingStatus::ProtocolError;
        result.error = "expected SPAKE2_MSG peer packet (got type=" +
                       std::to_string(static_cast<int>(rx_type)) +
                       ", len=" + std::to_string(rx_payload.size()) + ")";
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }

    // 6. Derive the SPAKE2 key material (fails if the code was wrong).
    std::vector<uint8_t> key_material =
        auth.processMsg(rx_payload.data(), rx_payload.size());
    if (key_material.empty()) {
        result.status = PairingStatus::Spake2AuthFailed;
        result.error = "SPAKE2 authentication failed (wrong pairing code?)";
        LOGE("PairingClient::pair: SPAKE2 auth failed (code mismatch)");
        return result;
    }

    // 7. Init AES-128-GCM from the SPAKE2 key material.
    PairingAesGcm cipher;
    if (!cipher.init(key_material.data(), key_material.size())) {
        result.status = PairingStatus::PeerInfoEncryptFailed;
        result.error = "AES-128-GCM init from SPAKE2 key failed";
        return result;
    }
    std::memset(key_material.data(), 0, key_material.size());

    // 8. PeerInfo exchange. Host sends ADB_RSA_PUB_KEY, receives ADB_DEVICE_GUID.
    //    (AOSP pairing_connection.cpp:332-405: each side sends then receives.)
    std::string pub_key = keys_->publicKey();
    PeerInfo my_info = PeerInfo::make(PeerInfoType::ADB_RSA_PUB_KEY, pub_key);
    std::vector<uint8_t> my_wire = my_info.serialize();
    std::vector<uint8_t> my_enc = cipher.encrypt(my_wire.data(), my_wire.size());
    if (my_enc.empty()) {
        result.status = PairingStatus::PeerInfoEncryptFailed;
        result.error = "AES-GCM encrypt of PeerInfo(ADB_RSA_PUB_KEY) failed";
        return result;
    }
    if (!writePacket(transport, PairingPacketType::PEER_INFO,
                     my_enc.data(), my_enc.size(), &error)) {
        result.status = PairingStatus::PeerInfoEncryptFailed;
        result.error = "write PEER_INFO: " + error;
        return result;
    }

    if (!readPacket(transport, &rx_type, &rx_payload, &error)) {
        result.status = PairingStatus::PeerInfoDecryptFailed;
        result.error = "read peer PEER_INFO: " + error;
        return result;
    }
    if (rx_type != PairingPacketType::PEER_INFO) {
        result.status = PairingStatus::ProtocolError;
        result.error = "expected PEER_INFO peer packet (got type=" +
                       std::to_string(static_cast<int>(rx_type)) + ")";
        return result;
    }

    std::vector<uint8_t> their_wire =
        cipher.decrypt(rx_payload.data(), rx_payload.size());
    if (their_wire.empty() || their_wire.size() != sizeof(PeerInfo)) {
        result.status = PairingStatus::PeerInfoDecryptFailed;
        result.error = "AES-GCM decrypt of peer PEER_INFO failed (bad tag?)";
        LOGE("PairingClient::pair: PEER_INFO decrypt failed");
        return result;
    }

    PeerInfo their_info;
    if (!their_info.parse(their_wire.data(), their_wire.size()) ||
        their_info.type != static_cast<uint8_t>(PeerInfoType::ADB_DEVICE_GUID)) {
        result.status = PairingStatus::PeerInfoTypeMismatch;
        result.error = "device did not return ADB_DEVICE_GUID (type=" +
                       std::to_string(their_info.type) + ")";
        LOGE("PairingClient::pair: %s", result.error.c_str());
        return result;
    }

    // 9. Extract the device GUID (trim trailing NUL padding).
    std::string guid(reinterpret_cast<const char*>(their_info.payload()),
                     their_info.payload_capacity());
    while (!guid.empty() && guid.back() == '\0') guid.pop_back();

    transport.close();

    result.status = PairingStatus::Success;
    result.device_guid = guid;
    LOGI("PairingClient::pair: SUCCESS device_guid=%s", guid.c_str());
    return result;
}

}  // namespace adb
