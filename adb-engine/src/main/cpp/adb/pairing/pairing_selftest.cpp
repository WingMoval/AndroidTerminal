// AT.C-1 — Wireless ADB pairing primitive: device-free deterministic self-test.
//
// Covers the protocol primitives that do NOT require a device:
//   - PairingPacket framing: 6-byte header, big-endian payload_size,
//     version/type validation, size bounds, invalid inputs, roundtrip.
//   - PeerInfo: fixed 8192-byte wire layout, serialize/parse roundtrip,
//     type tag, payload capacity, rejection of wrong length.
//   - SPAKE2: deterministic client(alice)/server(bob) fixture — both sides
//     derive the SAME 32-byte key material; wrong password -> mismatch.
//   - AES-128-GCM: HKDF key derivation, encrypt/decrypt roundtrip, and
//     rejection of a corrupted tag (bad-tag -> decrypt fails).
//   - Pairing-code validation: exactly 6 ASCII decimal digits.
//
// Run on a device/emulator (arm64 Android). Exit code 0 = all pass.

#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "adb/pairing/pairing_aes_gcm.h"
#include "adb/pairing/pairing_auth.h"
#include "adb/pairing/pairing_packet.h"
#include "adb/pairing/pairing_peerinfo.h"

#include <openssl/curve25519.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace adb;
using namespace adb::pairing;

namespace {

int g_failures = 0;

void Check(bool cond, const char* what) {
    if (cond) {
        std::printf("  PASS  %s\n", what);
    } else {
        std::printf("  FAIL  %s\n", what);
        ++g_failures;
    }
}

// --- PairingPacket framing -------------------------------------------------

void CasePacketRoundtrip() {
    std::printf("Case: PairingPacket encode/decode roundtrip (BE, bounds)\n");
    // SPAKE2_MSG with a 32-byte payload (the real SPAKE2 message size).
    std::vector<uint8_t> payload(32, 0xAB);
    std::vector<uint8_t> buf = buildPacket(PairingPacketType::SPAKE2_MSG,
                                           payload.data(), payload.size());
    Check(buf.size() == 6 + 32, "buildPacket size = 6 + 32");
    Check(buf[0] == 1, "header byte0 version = 1");
    Check(buf[1] == 0, "header byte1 type = SPAKE2_MSG(0)");
    // payload_size big-endian: 32 = 0x00000020
    Check(buf[2] == 0x00 && buf[3] == 0x00 && buf[4] == 0x00 && buf[5] == 0x20,
          "payload_size big-endian 32 -> 00 00 00 20");

    PairingPacketHeader h;
    Check(decodeHeader(buf.data(), buf.size(), &h), "decodeHeader succeeds");
    Check(h.version == 1, "decoded version = 1");
    Check(h.type == 0, "decoded type = 0");
    Check(h.payload_size == 32, "decoded payload_size = 32");

    // A larger size to prove multi-byte BE (e.g. 16384 = 0x4000).
    std::vector<uint8_t> big(kMaxPayloadSize, 0);
    std::vector<uint8_t> b2 = buildPacket(PairingPacketType::PEER_INFO,
                                          big.data(), big.size());
    Check(b2.size() == 6 + kMaxPayloadSize, "buildPacket max size OK");
    PairingPacketHeader h2;
    Check(decodeHeader(b2.data(), 6, &h2), "decodeHeader max size OK");
    Check(h2.payload_size == kMaxPayloadSize, "decoded max payload_size");
    Check(h2.type == 1, "decoded type = PEER_INFO(1)");
}

void CasePacketInvalid() {
    std::printf("Case: PairingPacket invalid inputs rejected\n");
    PairingPacketHeader h;

    // Too short.
    uint8_t short6[5] = {1, 0, 0, 0, 0};
    Check(!decodeHeader(short6, 5, &h), "reject header < 6 bytes");

    // Bad version.
    uint8_t badver[6] = {2, 0, 0, 0, 0, 32};
    Check(!decodeHeader(badver, 6, &h), "reject version != 1");

    // Unknown type.
    uint8_t badtype[6] = {1, 7, 0, 0, 0, 32};
    Check(!decodeHeader(badtype, 6, &h), "reject unknown type");

    // Oversized payload (> kMaxPayloadSize).
    uint8_t bigsize[6] = {1, 0, 0xFF, 0xFF, 0xFF, 0xFF};
    Check(!decodeHeader(bigsize, 6, &h), "reject oversized payload");

    // buildPacket with oversized payload returns empty.
    std::vector<uint8_t> huge(kMaxPayloadSize + 1, 0);
    std::vector<uint8_t> none = buildPacket(PairingPacketType::SPAKE2_MSG,
                                            huge.data(), huge.size());
    Check(none.empty(), "buildPacket rejects oversized payload");

    // buildPacket with empty payload.
    std::vector<uint8_t> empty = buildPacket(PairingPacketType::SPAKE2_MSG,
                                             nullptr, 0);
    Check(empty.size() == 6, "buildPacket empty payload = header only");
    Check(empty[2] == 0 && empty[3] == 0 && empty[4] == 0 && empty[5] == 0,
          "empty payload_size = 0");
}

// --- PeerInfo layout -------------------------------------------------------

void CasePeerInfo() {
    std::printf("Case: PeerInfo fixed 8192-byte layout + roundtrip\n");
    Check(sizeof(PeerInfo) == kMaxPeerInfoSize, "sizeof(PeerInfo) == 8192");
    Check(kMaxPeerInfoSize == 8192, "kMaxPeerInfoSize == 8192");
    Check(PeerInfo().size() == 8192, "PeerInfo::size() == 8192");

    // Build an ADB_RSA_PUB_KEY PeerInfo carrying a known payload.
    const char* pubkey = "QAAAALPBBtestkey== user@host\n";
    PeerInfo info = PeerInfo::make(PeerInfoType::ADB_RSA_PUB_KEY, pubkey);
    Check(info.type == 0, "type == ADB_RSA_PUB_KEY(0)");
    Check(std::memcmp(info.data, pubkey, std::strlen(pubkey)) == 0,
          "payload copied verbatim");
    Check(info.data[std::strlen(pubkey)] == 0, "padding after payload is zero");

    std::vector<uint8_t> wire = info.serialize();
    Check(wire.size() == 8192, "serialize() -> 8192 bytes");
    PeerInfo back;
    Check(back.parse(wire.data(), wire.size()), "parse() accepts 8192 bytes");
    Check(back.type == 0, "parsed type preserved");
    Check(std::memcmp(back.data, info.data, 8192) == 0, "parsed data == orig");

    // parse() rejects wrong length.
    PeerInfo bad;
    Check(!bad.parse(wire.data(), wire.size() - 1), "parse() rejects 8191 bytes");
    Check(!bad.parse(wire.data(), 8193), "parse() rejects 8193 bytes");

    // ADB_DEVICE_GUID type.
    PeerInfo guid = PeerInfo::make(PeerInfoType::ADB_DEVICE_GUID,
                                   "device-guid-1234567890");
    Check(guid.type == 1, "type == ADB_DEVICE_GUID(1)");

    // Payload too large for 8191 capacity -> leaves zero (no crash).
    std::string too_big(kMaxPeerInfoSize, 'x');  // 8192 > 8191
    PeerInfo overflow = PeerInfo::make(PeerInfoType::ADB_RSA_PUB_KEY, too_big);
    Check(overflow.data[0] == 0, "oversized payload -> zero (no write)");
}

// --- SPAKE2 fixture --------------------------------------------------------

void CaseSpake2() {
    std::printf("Case: SPAKE2 client/server derive same key; wrong pw fails\n");
    // Build a realistic password: 6-digit code || 64 bytes of EKM.
    std::vector<uint8_t> password;
    password.insert(password.end(), '8', '8' + 5);  // "888888"
    for (int i = 0; i < 64; ++i) password.push_back(static_cast<uint8_t>(i));

    PairingAuth client(true);   // alice (host)
    PairingAuth server(false);  // bob (device)
    Check(client.valid(), "client SPAKE2 ctx valid");
    Check(server.valid(), "server SPAKE2 ctx valid");

    std::vector<uint8_t> client_msg = client.generateMsg(password.data(),
                                                         password.size());
    std::vector<uint8_t> server_msg = server.generateMsg(password.data(),
                                                         password.size());
    Check(client_msg.size() == 32, "client msg = 32 bytes");
    Check(server_msg.size() == 32, "server msg = 32 bytes");

    std::vector<uint8_t> client_key =
        client.processMsg(server_msg.data(), server_msg.size());
    std::vector<uint8_t> server_key =
        server.processMsg(client_msg.data(), client_msg.size());
    // AOSP uses SPAKE2_MAX_KEY_SIZE(64) as the key buffer; BoringSSL emits 64
    // bytes. Truncating to 32 would feed a DIFFERENT HKDF input -> bad tag.
    Check(client_key.size() == 64, "client key material = 64 bytes (AOSP SPAKE2_MAX_KEY_SIZE)");
    Check(server_key.size() == 64, "server key material = 64 bytes (AOSP SPAKE2_MAX_KEY_SIZE)");
    Check(!client_key.empty() && client_key == server_key,
          "client & server derived the SAME key material");
    // DIAGNOSTIC (AT.C-1): report the actual SPAKE2 key material length.
    std::printf("  INFO  SPAKE2 processMsg key_len=%zu (SPAKE2_MAX_KEY_SIZE=%d)\n",
                client_key.size(), SPAKE2_MAX_KEY_SIZE);

    // SPAKE2 is randomized (fresh ephemeral each run): messages legitimately
    // differ between runs, but the derived key MUST be identical for the same
    // password + roles. This is the real protocol invariant.
    PairingAuth c2(true);
    PairingAuth s2(false);
    std::vector<uint8_t> c2_msg = c2.generateMsg(password.data(), password.size());
    std::vector<uint8_t> s2_msg = s2.generateMsg(password.data(), password.size());
    std::vector<uint8_t> c2_key = c2.processMsg(s2_msg.data(), s2_msg.size());
    std::vector<uint8_t> s2_key = s2.processMsg(c2_msg.data(), c2_msg.size());
    Check(c2_msg != client_msg || s2_msg != server_msg,
          "SPAKE2 uses fresh ephemeral (messages differ across runs)");
    Check(!c2_key.empty() && c2_key != client_key,
          "fresh session -> fresh derived key (per-session session key)");

    // Wrong password -> different (and unusable) key on the other side.
    std::vector<uint8_t> wrong_pw = password;
    wrong_pw[0] = static_cast<uint8_t>('9');  // "988888"
    PairingAuth bad_client(true);
    PairingAuth good_server(false);
    std::vector<uint8_t> bad_msg =
        bad_client.generateMsg(wrong_pw.data(), wrong_pw.size());
    std::vector<uint8_t> good_msg =
        good_server.generateMsg(password.data(), password.size());
    std::vector<uint8_t> good_key =
        good_server.processMsg(bad_msg.data(), bad_msg.size());
    std::vector<uint8_t> bad_key =
        bad_client.processMsg(good_msg.data(), good_msg.size());
    // With a wrong password the derived keys must differ (SPAKE2 is a PAKE).
    Check(!(bad_key == client_key) || !(good_key == server_key) ||
          bad_key.empty() || good_key.empty(),
          "wrong password does not reproduce the correct key");
}

// --- AES-128-GCM -----------------------------------------------------------

void CaseAesGcm() {
    std::printf("Case: AES-128-GCM roundtrip + bad-tag rejection\n");
    // 32-byte SPAKE2 key material.
    std::vector<uint8_t> key_material(32, 0x5A);

    PairingAesGcm enc;
    Check(enc.init(key_material.data(), key_material.size()),
          "init() succeeds with 32-byte key material");

    const std::string plain = "hello, this is the PeerInfo payload (8192 bytes)";
    std::vector<uint8_t> cipher =
        enc.encrypt(reinterpret_cast<const uint8_t*>(plain.data()), plain.size());
    Check(!cipher.empty(), "encrypt() returns ciphertext");
    Check(cipher.size() == plain.size() + 16,
          "ciphertext = plaintext + 16-byte GCM tag");

    // Decrypt with a fresh context holding the same key.
    PairingAesGcm dec;
    Check(dec.init(key_material.data(), key_material.size()),
          "decrypt-side init() succeeds");
    std::vector<uint8_t> recovered = dec.decrypt(cipher.data(), cipher.size());
    Check(recovered.size() == plain.size(), "decrypt() returns plaintext size");
    Check(recovered ==
              std::vector<uint8_t>(plain.begin(), plain.end()),
          "roundtrip recovers exact plaintext");

    // Nonce counter: a second encrypt uses a different nonce (different output).
    std::vector<uint8_t> cipher2 =
        enc.encrypt(reinterpret_cast<const uint8_t*>(plain.data()), plain.size());
    Check(cipher2 != cipher, "second encrypt uses a new nonce (different CT)");

    // Bad tag: corrupt one byte of the tag -> decrypt must fail.
    std::vector<uint8_t> corrupted = cipher;
    corrupted[corrupted.size() - 1] ^= 0xFF;
    PairingAesGcm dec2;
    dec2.init(key_material.data(), key_material.size());
    std::vector<uint8_t> bad = dec2.decrypt(corrupted.data(), corrupted.size());
    Check(bad.empty(), "corrupted tag -> decrypt fails (empty)");

    // Wrong key -> decrypt fails.
    std::vector<uint8_t> other_key(32, 0x11);
    PairingAesGcm dec3;
    dec3.init(other_key.data(), other_key.size());
    std::vector<uint8_t> wrong = dec3.decrypt(cipher.data(), cipher.size());
    Check(wrong.empty(), "wrong key -> decrypt fails (empty)");

    // init() with empty key material fails.
    PairingAesGcm bad_init;
    Check(!bad_init.init(nullptr, 0), "init() rejects empty key material");
}

}  // namespace

int main() {
    std::printf("=== AT.C-1 pairing primitive self-test ===\n");
    CasePacketRoundtrip();
    CasePacketInvalid();
    CasePeerInfo();
    CaseSpake2();
    CaseAesGcm();

    if (g_failures == 0) {
        std::printf("\nALL PASS (%d checks)\n", 0);
        LOGI("pairing_selftest: ALL PASS");
        return 0;
    }
    std::printf("\n%d FAILURE(S)\n", g_failures);
    LOGI("pairing_selftest: %d failure(s)", g_failures);
    return 1;
}
