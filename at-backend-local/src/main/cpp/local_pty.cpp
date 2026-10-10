// ATsh.T1 — minimal local PTY experiment (PROVISIONAL).
//
// Creates a real PTY (master/slave) with bionic POSIX primitives, forks,
// attaches the slave as controlling terminal, and execs /system/bin/sh -i.
// Every step logs errno so the real-device feasibility can be evidenced
// without third-party terminal libraries.

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <cstddef>
#include <cstdio>
#include <cstring>

#define LOG_TAG "ATshPtyNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace atsh {

namespace {

constexpr const char* kShell = "/system/bin/sh";

}  // namespace

// Create a PTY pair + fork + attach + exec /system/bin/sh -i.
// Returns: 0 on success, -1 on error. On success:
//   *out_master = master fd (parent side)
//   *out_pid    = child pid
//   slave_path  = the slave pts path (out buffer)
int create_pty_shell(int* out_master, pid_t* out_pid, char* slave_path,
                     size_t slave_path_len) {
    if (!out_master || !out_pid || !slave_path || slave_path_len == 0) {
        LOGE("create_pty_shell: bad args");
        return -1;
    }

    // 1. PTY master.
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("posix_openpt OK master=%d", master);

    // 2. grantpt / unlockpt / ptsname.
    int rc = grantpt(master);
    if (rc != 0) {
        int e = errno;
        LOGE("grantpt failed errno=%d (%s)", e, strerror(e));
        close(master);
        return -1;
    }
    LOGI("grantpt OK");
    rc = unlockpt(master);
    if (rc != 0) {
        int e = errno;
        LOGE("unlockpt failed errno=%d (%s)", e, strerror(e));
        close(master);
        return -1;
    }
    LOGI("unlockpt OK");
    rc = ptsname_r(master, slave_path, slave_path_len);
    if (rc != 0) {
        int e = errno;
        LOGE("ptsname_r failed errno=%d (%s)", e, strerror(e));
        close(master);
        return -1;
    }
    LOGI("ptsname OK slave=%s", slave_path);

    // 3. Fork.
    pid_t pid = fork();
    if (pid < 0) {
        int e = errno;
        LOGE("fork failed errno=%d (%s)", e, strerror(e));
        close(master);
        return -1;
    }

    if (pid == 0) {
        // ---- child ----
        // New session + controlling tty.
        if (setsid() < 0) {
            int e = errno;
            LOGE("child setsid failed errno=%d (%s)", e, strerror(e));
            _exit(126);
        }
        LOGI("child setsid OK (new session)");

        int slave = open(slave_path, O_RDWR);
        if (slave < 0) {
            int e = errno;
            LOGE("child open(slave) failed errno=%d (%s)", e, strerror(e));
            _exit(126);
        }
        LOGI("child open(slave) OK fd=%d", slave);

        if (ioctl(slave, TIOCSCTTY, 0) < 0) {
            int e = errno;
            LOGE("child ioctl(TIOCSCTTY) failed errno=%d (%s)", e, strerror(e));
            _exit(126);
        }
        LOGI("child TIOCSCTTY OK");

        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);

        // exec /system/bin/sh -i
        char* const argv[] = {const_cast<char*>(kShell),
                              const_cast<char*>("-i"), nullptr};
        // Keep inherited environment (PATH etc); add a sane TERM.
        setenv("TERM", "xterm", 1);
        execve(kShell, argv, environ);
        int e = errno;
        LOGE("child execve(%s) failed errno=%d (%s)", kShell, e, strerror(e));
        _exit(127);
    }

    // ---- parent ----
    LOGI("fork OK pid=%d", pid);
    *out_master = master;
    *out_pid = pid;
    LOGI("create_pty_shell OK master=%d pid=%d slave=%s", master, pid,
         slave_path);
    return 0;
}

int pty_write(int fd, const uint8_t* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, data + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            int e = errno;
            LOGE("pty_write failed errno=%d (%s)", e, strerror(e));
            return -1;
        }
        off += static_cast<size_t>(n);
    }
    return static_cast<int>(off);
}

// Read up to len bytes with timeout (ms). Returns bytes read, 0 on timeout,
// -1 on error/EOF.
ssize_t pty_read(int fd, uint8_t* buf, size_t len, int timeout_ms) {
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    int prc = poll(&pfd, 1, timeout_ms);
    if (prc < 0) {
        if (errno == EINTR) return 0;
        int e = errno;
        LOGE("pty_read poll failed errno=%d (%s)", e, strerror(e));
        return -1;
    }
    if (prc == 0) return 0;  // timeout
    if (!(pfd.revents & POLLIN)) {
        if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
            LOGI("pty_read poll hangup revents=0x%x", pfd.revents);
            return -1;
        }
        return 0;
    }
    ssize_t n = read(fd, buf, len);
    if (n < 0) {
        if (errno == EINTR) return 0;
        int e = errno;
        LOGE("pty_read read failed errno=%d (%s)", e, strerror(e));
        return -1;
    }
    return n;
}

int pty_get_winsize(int fd, int* rows, int* cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(fd, TIOCGWINSZ, &ws) < 0) {
        int e = errno;
        LOGE("ioctl(TIOCGWINSZ) failed errno=%d (%s)", e, strerror(e));
        return -1;
    }
    *rows = ws.ws_row;
    *cols = ws.ws_col;
    LOGI("TIOCGWINSZ rows=%d cols=%d", *rows, *cols);
    return 0;
}

int pty_set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) {
        int e = errno;
        LOGE("ioctl(TIOCSWINSZ) failed errno=%d (%s)", e, strerror(e));
        return -1;
    }
    LOGI("TIOCSWINSZ rows=%d cols=%d", rows, cols);
    return 0;
}

// Close master, signal child (SIGHUP first, then SIGKILL fallback), reap.
int close_pty(int fd, pid_t pid, int timeout_ms) {
    int rc = close(fd);
    LOGI("close(master=%d) rc=%d errno=%d", fd, rc, errno);
    if (pid > 0) {
        if (kill(pid, SIGHUP) < 0) {
            int e = errno;
            LOGI("kill(SIGHUP) failed errno=%d (%s)", e, strerror(e));
        } else {
            LOGI("kill(pid=%d, SIGHUP) sent", pid);
        }
        // Wait for the child with a bounded window; reap zombie.
        int deadline = timeout_ms;
        while (deadline > 0) {
            int status = 0;
            pid_t wr = waitpid(pid, &status, WNOHANG);
            if (wr == pid) {
                LOGI("waitpid reaped pid=%d status=%d", pid, status);
                return 0;
            }
            if (wr < 0) {
                int e = errno;
                LOGI("waitpid error errno=%d (%s)", e, strerror(e));
                return -1;
            }
            usleep(50000);
            deadline -= 50;
        }
        LOGI("waitpid timeout: child %d not reaped in %d ms; killing", pid,
             timeout_ms);
        if (kill(pid, SIGKILL) == 0) {
            int status = 0;
            waitpid(pid, &status, 0);
            LOGI("SIGKILL + reap pid=%d status=%d", pid, status);
        }
    }
    return 0;
}

}  // namespace atsh
