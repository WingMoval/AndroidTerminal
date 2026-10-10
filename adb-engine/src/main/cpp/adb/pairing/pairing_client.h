// PairingClient — AOSP Wireless ADB pairing primitive (host = client role).
//
// Performs the full pairing flow against a target device's *pairing* port
// (the `_adb-tls-pairing._tcp` endpoint), NOT the connect port and NOT the
// localhost:5037 adb server:
//
//   1. TCP connect to (host, pairing_port)
//   2. TLS 1.3 handshake (accept-any peer cert; device cert is random/self-signed)
//   3. SPAKE2 (Curve25519) using password = pairing_code || 64-byte TLS EKM
//   4. AES-128-GCM PeerInfo exchange:
//        - host sends ADB_RSA_PUB_KEY (the adb public key, android_pubkey fmt)
//        - host receives ADB_DEVICE_GUID
//   5. Success => device has stored the host RSA pub key and now trusts this
//      identity for the subsequent connect port.
//
// The identity used here MUST equal the identity used by AdbClient::connect()
// (same AdbKeyStore / keyDir). That is the core of the first-run bootstrap:
// pair(identity A) -> device trusts A -> connectAuto(A) -> shell.
//
// This class is pure mechanism (Core). It does NOT do mDNS discovery, read the
// pairing code from the UI, or navigate the device's settings screen — those
// are Runtime/Host concerns. It receives (host, port, code) explicitly.
//
// Reference: AOSP pairing_connection.cpp (DoExchangeMsgs 295-330,
// DoExchangePeerInfo 332-405) + pairing_auth.cpp (SPAKE2) + aes_128_gcm.cpp.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace adb {

class AdbKeyStore;

// Structured pairing error classification (Core/protocol level only).
// Runtime/Host-level conditions (e.g. pairing page not open, code not read)
// are NOT represented here — Core receives the code + endpoint as inputs.
enum class PairingStatus : uint8_t {
    Success = 0,
    InvalidCode = 1,           // pairing code not a 6-digit decimal string
    TcpConnectFailed = 2,
    TlsHandshakeFailed = 3,
    TlsExporterFailed = 4,     // ExportKeyingMaterial(64) failed
    Spake2AuthFailed = 5,      // wrong code / protocol mismatch
    Spake2ExchangeFailed = 6,  // I/O or framing error during SPAKE2_MSG
    PeerInfoEncryptFailed = 7,
    PeerInfoDecryptFailed = 8, // bad tag / wrong key
    PeerInfoTypeMismatch = 9,  // device did not return ADB_DEVICE_GUID
    ProtocolError = 10,        // bad version/type/length in a packet
    Timeout = 11,
    IoError = 12,
};

struct PairingResult {
    PairingStatus status = PairingStatus::Success;
    bool success() const { return status == PairingStatus::Success; }
    std::string device_guid;   // set on success
    std::string error;         // human-readable diagnostic (never device output)
};

class PairingClient {
public:
    explicit PairingClient(AdbKeyStore* keys);
    ~PairingClient();

    PairingClient(const PairingClient&) = delete;
    PairingClient& operator=(const PairingClient&) = delete;

    // Perform the full pairing flow (blocking).
    //   host, port    : the device's pairing endpoint (from mDNS `_adb-tls-pairing._tcp`)
    //   pairing_code  : the 6-digit code shown on the device's pairing screen
    // Returns a structured PairingResult. On success, device_guid is populated.
    PairingResult pair(const std::string& host, int port,
                       const std::string& pairing_code);

private:
    AdbKeyStore* keys_;
};

}  // namespace adb
