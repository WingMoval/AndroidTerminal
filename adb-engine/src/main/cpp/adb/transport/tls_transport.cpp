// TlsTransport implementation.
//
// Wraps TcpTransport + TlsConnection. Before TLS: raw TCP. After TLS: BoringSSL.

#include "adb/transport/tls_transport.h"

#include <android/log.h>

#include <cstring>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

TlsTransport::~TlsTransport() { close(); }

bool TlsTransport::connect(const std::string& host, int port) {
    close();
    if (!tcp_.connect(host, port)) {
        LOGE("TlsTransport::connect: TCP connect to %s:%d failed: %s",
             host.c_str(), port, tcp_.last_error().c_str());
        return false;
    }
    tcp_fd_ = tcp_.fd();
    LOGI("TlsTransport::connect: TCP connected to %s:%d (fd=%d)",
         host.c_str(), port, tcp_fd_);
    return true;
}

bool TlsTransport::startTls(const std::string& cert_pem,
                            const std::string& priv_key_pem) {
    if (!tcp_.is_open()) {
        LOGE("TlsTransport::startTls: TCP not connected");
        return false;
    }
    if (tls_) {
        LOGI("TlsTransport::startTls: TLS already active");
        return true;
    }

    LOGI("TlsTransport::startTls: initiating TLS 1.3 handshake (fd=%d)", tcp_fd_);

    tls_ = tls::TlsConnection::Create(tls::TlsConnection::Role::Client,
                                      cert_pem, priv_key_pem, tcp_fd_);
    if (!tls_) {
        LOGE("TlsTransport::startTls: TlsConnection::Create failed");
        return false;
    }

    // Accept any server certificate (AOSP transport.cpp:539).
    // The device is the authority; host does not pin adbd's cert.
    tls_->SetCertVerifyCallback([](X509_STORE_CTX*) { return 1; });

    // Certificate selection callback (AOSP transport.cpp:537, auth.cpp:510).
    // adbd sends a client CA list with SHA256 fingerprints of authorized keys.
    // For now, always return 1 (use the default cert loaded from adbkey).
    // Future: parse CA list and select matching key (like AOSP adb_tls_set_certificate).
    tls_->SetCertificateCallback([](SSL*) {
        LOGI("TlsTransport: cert callback invoked (using default cert)");
        return 1;
    });

    tls::TlsConnection::TlsError err = tls_->DoHandshake();
    if (err != tls::TlsConnection::TlsError::Success) {
        LOGE("TlsTransport::startTls: TLS handshake failed (err=%d)",
             static_cast<int>(err));
        tls_.reset();
        return false;
    }

    LOGI("TlsTransport::startTls: TLS 1.3 handshake success");
    return true;
}

bool TlsTransport::send(const uint8_t* data, size_t length) {
    if (tls_) {
        return tls_->WriteFully(std::string_view(
            reinterpret_cast<const char*>(data), length));
    }
    return tcp_.writeExact(data, length);
}

ssize_t TlsTransport::receive(uint8_t* buffer, size_t length) {
    if (tls_) {
        std::vector<uint8_t> data = tls_->ReadFully(length);
        if (data.empty()) return -1;
        std::memcpy(buffer, data.data(), data.size());
        return static_cast<ssize_t>(data.size());
    }
    return tcp_.receive(buffer, length);
}

bool TlsTransport::readExact(uint8_t* buffer, size_t length) {
    if (tls_) {
        return tls_->ReadFully(buffer, length);
    }
    return tcp_.readExact(buffer, length);
}

bool TlsTransport::writeExact(const uint8_t* data, size_t length) {
    if (tls_) {
        return tls_->WriteFully(std::string_view(
            reinterpret_cast<const char*>(data), length));
    }
    return tcp_.writeExact(data, length);
}

std::vector<uint8_t> TlsTransport::exportKeyingMaterial(size_t length) {
    if (!tls_) {
        LOGE("TlsTransport::exportKeyingMaterial: TLS not active");
        return {};
    }
    std::vector<uint8_t> out = tls_->ExportKeyingMaterial(length);
    if (out.empty()) {
        LOGE("TlsTransport::exportKeyingMaterial: export failed");
    }
    return out;
}

void TlsTransport::shutdownSocket() {
    // fd-level shutdown only: do NOT touch tls_ (the RX owner thread may be
    // blocked inside SSL_read on this SSL object; concurrent SSL_* calls are
    // undefined behavior). SHUT_RDWR wakes recv()/SSL_read() with EOF.
    tcp_.shutdown();
}

void TlsTransport::close() {
    if (tls_) {
        LOGI("TlsTransport::close: closing TLS connection");
        tls_.reset();  // TlsConnection destructor calls SSL_shutdown
    }
    tcp_.close();
    tcp_fd_ = -1;
}

}  // namespace adb
