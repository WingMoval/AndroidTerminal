// TcpTransport — Step 1A real implementation.
//
// Loopback TCP to on-device adbd. See tcp_transport.h for the contract.
// Logging tag: AndroidTerminalADB (per Step 1A requirement). No secrets are
// logged; this layer only sees raw bytes and socket state.

#include "transport/tcp_transport.h"

#include <android/log.h>

#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#define LOG_TAG "AndroidTerminalADB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace adb {

namespace {
constexpr int kConnectTimeoutMs = 5000;
constexpr int kIoTimeoutMs = 5000;
}  // namespace

void TcpTransport::set_error(int err) {
    last_error_ = std::to_string(err) + ": " + std::strerror(err);
}

TcpTransport::~TcpTransport() { close(); }

bool TcpTransport::connect(const std::string& host, int port) {
    close();

    struct addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    std::string port_str = std::to_string(port);
    struct addrinfo* res = nullptr;
    int gai = getaddrinfo(host.c_str(), port_str.c_str(), &hints, &res);
    if (gai != 0 || res == nullptr) {
        int err = (gai != 0) ? EAI_SYSTEM : EADDRNOTAVAIL;
        std::string msg = "getaddrinfo failed: ";
        msg += (gai != 0) ? gai_strerror(gai) : strerror(err);
        last_error_ = msg;
        LOGE("TCP CONNECT %s:%d FAILED %s", host.c_str(), port, msg.c_str());
        freeaddrinfo(res);
        return false;
    }

    fd_ = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd_ < 0) {
        int err = errno;
        set_error(err);
        LOGE("TCP CONNECT %s:%d socket() failed %s", host.c_str(), port,
             last_error_.c_str());
        freeaddrinfo(res);
        return false;
    }

    // Non-blocking connect with a timeout so a closed port fails fast
    // instead of hanging the UI thread.
    int flags = fcntl(fd_, F_GETFL, 0);
    if (flags >= 0) fcntl(fd_, F_SETFL, flags | O_NONBLOCK);

    int rc = ::connect(fd_, res->ai_addr, res->ai_addrlen);
    freeaddrinfo(res);

    if (rc != 0) {
        if (errno == EINPROGRESS) {
            fd_set wfds;
            FD_ZERO(&wfds);
            FD_SET(fd_, &wfds);
            struct timeval tv{kConnectTimeoutMs / 1000, (kConnectTimeoutMs % 1000) * 1000};
            rc = ::select(fd_ + 1, nullptr, &wfds, nullptr, &tv);
            if (rc > 0) {
                int sockerr = 0;
                socklen_t len = sizeof(sockerr);
                getsockopt(fd_, SOL_SOCKET, SO_ERROR, &sockerr, &len);
                rc = (sockerr == 0) ? 0 : -1;
                if (rc != 0) errno = sockerr;
            } else if (rc == 0) {
                errno = ETIMEDOUT;
            }
        }
        if (rc != 0) {
            int err = errno;
            set_error(err);
            LOGE("TCP CONNECT %s:%d connect() failed %s", host.c_str(), port,
                 last_error_.c_str());
            ::close(fd_);
            fd_ = -1;
            return false;
        }
    }

    // Back to blocking mode with an I/O timeout for the protocol exchange.
    if (flags >= 0) fcntl(fd_, F_SETFL, flags);
    struct timeval tv{kIoTimeoutMs / 1000, (kIoTimeoutMs % 1000) * 1000};
    setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd_, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    int one = 1;
    setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    last_error_.clear();
    LOGI("TCP CONNECT %s:%d OK (fd=%d)", host.c_str(), port, fd_);
    return true;
}

bool TcpTransport::writeExact(const uint8_t* data, size_t length) {
    size_t written = 0;
    while (written < length) {
        ssize_t n = ::send(fd_, data + written, length - written, MSG_NOSIGNAL);
        if (n > 0) {
            written += static_cast<size_t>(n);
        } else if (n < 0) {
            if (errno == EINTR) continue;
            set_error(errno);
            LOGE("TCP send() failed after %zu/%zu bytes: %s", written, length,
                 last_error_.c_str());
            return false;
        } else {
            set_error(ECONNRESET);
            LOGE("TCP send() peer closed after %zu/%zu bytes", written, length);
            return false;
        }
    }
    return true;
}

bool TcpTransport::readExact(uint8_t* buffer, size_t length) {
    size_t total = 0;
    while (total < length) {
        ssize_t n = ::recv(fd_, buffer + total, length - total, 0);
        if (n > 0) {
            total += static_cast<size_t>(n);
        } else if (n < 0) {
            if (errno == EINTR) continue;
            set_error(errno);
            LOGE("TCP recv() failed after %zu/%zu bytes: %s", total, length,
                 last_error_.c_str());
            return false;
        } else {
            // n == 0: peer closed cleanly (FIN). Query SO_ERROR to confirm
            // there is no pending RST/other error behind the FIN.
            int so_err = 0;
            socklen_t so_len = sizeof(so_err);
            if (getsockopt(fd_, SOL_SOCKET, SO_ERROR, &so_err, &so_len) == 0 &&
                so_err != 0) {
                last_error_ = "recv()=0 (FIN) but SO_ERROR=" + std::to_string(so_err) +
                              " (" + std::strerror(so_err) + ") after " +
                              std::to_string(total) + "/" + std::to_string(length);
            } else {
                last_error_ = "recv()=0 => clean EOF (FIN, no SO_ERROR) after " +
                              std::to_string(total) + "/" + std::to_string(length) +
                              " bytes";
            }
            LOGE("TCP %s", last_error_.c_str());
            return false;
        }
    }
    return true;
}

bool TcpTransport::send(const uint8_t* data, size_t length) {
    if (fd_ < 0) {
        last_error_ = "send() on closed socket";
        return false;
    }
    return writeExact(data, length);
}

ssize_t TcpTransport::receive(uint8_t* buffer, size_t length) {
    if (fd_ < 0) {
        last_error_ = "receive() on closed socket";
        return -1;
    }
    for (;;) {
        ssize_t n = ::recv(fd_, buffer, length, 0);
        if (n >= 0) return n;
        if (errno == EINTR) continue;
        set_error(errno);
        return -1;
    }
}

void TcpTransport::close() {
    if (fd_ != -1) {
        ::close(fd_);
        fd_ = -1;
        LOGI("TCP socket closed");
    }
}

}  // namespace adb
