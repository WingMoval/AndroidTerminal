// AdbKeyStore: RSA-2048 key management for the Android ADB host.
//
// Ported from AOSP client/auth.cpp (load_userkey, generate_key, load_key)
// + crypto/rsa_2048_key.cpp + crypto/x509_generator.cpp.
//
// NDK-adapted:
//   - Key stored in app files dir (e.g. /data/data/<pkg>/files/adbkey)
//   - PKCS#8 PEM private key + android_pubkey public key + self-signed cert
//   - No ~/.android path, no adb server dependency

#pragma once

#include <string>

namespace adb {

class AdbKeyStore {
public:
    // `dir` is the directory to store adbkey / adbkey.pub / cert.pem.
    // Typically: Context#getFilesDir() (e.g. /data/data/com.androidterminal/files)
    explicit AdbKeyStore(std::string dir);
    ~AdbKeyStore();

    // Load the key from disk, or generate a new RSA-2048 keypair if not present.
    // Returns false on failure.
    bool loadOrCreate();

    // PEM-encoded PKCS#8 private key.
    std::string privateKeyPem() const;

    // android_pubkey format public key (base64 + " user@host").
    std::string publicKey() const;

    // Self-signed X.509 certificate PEM (CN=Adb, O=Android, C=US).
    std::string certificate() const;

    // Sign a 20-byte token with SHA-1 + RSA (for legacy USB AUTH).
    // Returns the signature bytes, or empty on failure.
    std::string signSha1(const uint8_t* token, size_t token_len) const;

    bool is_loaded() const { return loaded_; }

private:
    struct Impl;
    Impl* impl_;
    bool loaded_ = false;
};

}  // namespace adb
