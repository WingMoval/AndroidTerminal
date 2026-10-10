// PairingAuth — SPAKE2 wrapper for AOSP Wireless ADB pairing.
//
// Reference: AOSP pairing_auth/pairing_auth.cpp.
//   - Client role = spake2_role_alice, server role = spake2_role_bob.
//   - Party name literals: "adb pair client" / "adb pair server".
//   - SPAKE2 password = pairing_code bytes || 64-byte TLS ExportKeyingMaterial
//       (AOSP pairing_connection.cpp:192-198: pswd_.insert(exported_key, 64)).
//   - The SPAKE2 output key material (32 bytes) feeds PairingAesGcm.
//
// Thin wrapper over BoringSSL SPAKE2 (openssl/curve25519.h). Do not reimplement.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace adb {
namespace pairing {

// Result of one side of the SPAKE2 exchange.
struct Spake2Result {
    bool ok = false;
    std::vector<uint8_t> my_msg;        // 32-byte SPAKE2 message to send
    std::vector<uint8_t> key_material;  // 32-byte derived key (after process)
};

class PairingAuth {
public:
    // role_is_client: true = alice (host), false = bob (device).
    explicit PairingAuth(bool role_is_client);
    ~PairingAuth();

    PairingAuth(const PairingAuth&) = delete;
    PairingAuth& operator=(const PairingAuth&) = delete;

    // Compute our SPAKE2 message from |password| (pairing code || 64-byte EKM).
    // Returns the 32-byte message to send, or empty on failure.
    std::vector<uint8_t> generateMsg(const uint8_t* password, size_t password_len);

    // Complete the exchange given the peer's 32-byte message. On success,
    // returns the 32-byte derived key material (feeds PairingAesGcm). Empty on
    // failure (wrong password / protocol error).
    std::vector<uint8_t> processMsg(const uint8_t* their_msg, size_t their_msg_len);

    bool valid() const;

private:
    struct Impl;
    Impl* impl_;
};

}  // namespace pairing
}  // namespace adb
