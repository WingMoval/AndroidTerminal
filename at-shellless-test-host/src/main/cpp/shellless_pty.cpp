// AT.TERM-0 — Shellless PTY validation probe (VALIDATION ONLY).
//
// Proves a real Android PTY + a NON-shell interactive child can drive an
// AT TerminalSession. The child does NOT exec /system/bin/sh (or any shell):
// after fork + setsid + TIOCSCTTY it runs the probe loop IN-PROCESS using only
// bionic read/write/ioctl/poll/signal/_exit. No shell parser, no PATH lookup,
// no command grammar, no system()/popen()/execve().
//
// This is VALIDATION_DUPLICATION_ONLY: the PTY primitives (posix_openpt /
// grantpt / unlockpt / ptsname_r / TIOCSWINSZ / TIOCGWINSZ) are duplicated
// here from the Local PTY behavior reference so this module has ZERO dependency
// on :at-backend-local. It is NOT a third production backend.
//
// Probe protocol (a tiny validation protocol, NOT a shell language):
//   child startup  -> READY <pid> <tty> <isatty0> <isatty1> <isatty2> <rows> <cols>\n
//   stdin "PING x" -> PONG x\n
//   stdin "EXIT n" -> child _exit(n)
//   SIGWINCH       -> RESIZE rows=<r> cols=<c>\n  (re-read TIOCGWINSZ)
//   SIGINT (ISIG)  -> SIGINT\n  (a SIGINT handler, NOT shell semantics)
//
// No shell is ever launched. The child is the native probe itself.

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
#include <string>
#include <vector>

#define LOG_TAG "AtShelllessPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace atsl {

namespace {

// ---- child probe loop (runs IN-PROCESS after fork; no execve) --------------

// Global (child-only) state kept in C globals so the signal handler is safe.
volatile sig_atomic_t g_sigint_flag = 0;

static void sigint_handler(int) { g_sigint_flag = 1; }

// Write exactly n bytes to fd, tolerating EINTR. Returns bytes written or -1.
static size_t write_all(int fd, const char* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, data + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return (size_t)-1;
        }
        off += (size_t)n;
    }
    return off;
}

static void emit_line(int fd, const std::string& s) {
    std::string line = s;
    if (line.empty() || line.back() != '\n') line.push_back('\n');
    write_all(fd, line.data(), line.size());
}

// The shellless probe loop. stdin/stdout/stderr are all the PTY slave (fd 0/1/2).
static void probe_loop() {
    // Install a SIGINT handler (ISIG is on by default on a fresh PTY) so we can
    // PROVE a control byte (0x03) is delivered through the real terminal path as
    // a signal, NOT interpreted by any shell. The handler only sets a flag.
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigint_handler;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT, &sa, nullptr);

    // Startup report: pid, tty, isatty(0/1/2), initial winsize.
    char tty[128] = "?";
    if (ttyname_r(0, tty, sizeof(tty)) != 0) strcpy(tty, "?");
    int r0 = isatty(0), r1 = isatty(1), r2 = isatty(2);
    struct winsize ws;
    int rows = 0, cols = 0;
    if (ioctl(0, TIOCGWINSZ, &ws) == 0) { rows = ws.ws_row; cols = ws.ws_col; }

    char hdr[256];
    int hl = snprintf(hdr, sizeof(hdr),
                      "READY pid=%d tty=%s isatty0=%d isatty1=%d isatty2=%d rows=%d cols=%d\n",
                      (int)getpid(), tty, r0, r1, r2, rows, cols);
    if (hl > 0) write_all(1, hdr, (size_t)hl);

    std::string buf;
    char tmp[256];
    for (;;) {
        // Check SIGINT first (a real SIGINT arrived via the PTY line discipline).
        if (g_sigint_flag) {
            g_sigint_flag = 0;
            emit_line(1, "SIGINT");
        }

        // Proactively re-read winsize every iteration and report any change.
        // This is robust: TIOCSWINSZ on the master updates the slave winsize the
        // child reads here, regardless of whether SIGWINCH interrupted poll().
        {
            struct winsize w2;
            if (ioctl(0, TIOCGWINSZ, &w2) == 0 &&
                (w2.ws_row != rows || w2.ws_col != cols)) {
                LOGI("child winsize changed %dx%d -> %dx%d", rows, cols,
                     w2.ws_row, w2.ws_col);
                rows = w2.ws_row; cols = w2.ws_col;
                char rl[128];
                int rl2 = snprintf(rl, sizeof(rl), "RESIZE rows=%d cols=%d\n", rows, cols);
                if (rl2 > 0) write_all(1, rl, (size_t)rl2);
            }
        }

        struct pollfd pfd;
        pfd.fd = 0;
        pfd.events = POLLIN;
        int prc = poll(&pfd, 1, 50);
        if (prc < 0) {
            if (errno == EINTR) continue;  // a signal interrupted; loop to re-check
            return;  // real error
        }
        if (prc == 0) continue;  // timeout; loop to re-check winsize/signals

        if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
            // stdin closed (master gone) -> exit cleanly.
            return;
        }

        if (pfd.revents & POLLIN) {
            ssize_t n = read(0, tmp, sizeof(tmp));
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;
                return;  // EOF / closed
            }
            buf.append(tmp, (size_t)n);

            // Process complete lines from the accumulated buffer.
            size_t nl;
            while ((nl = buf.find('\n')) != std::string::npos) {
                std::string line = buf.substr(0, nl);
                buf.erase(0, nl + 1);
                // strip trailing \r (PTY may emit \r\n on some line modes)
                if (!line.empty() && line.back() == '\r') line.pop_back();

                // Tiny validation protocol (NOT a shell):
                if (line.rfind("PING ", 0) == 0) {
                    std::string arg = line.substr(5);
                    emit_line(1, "PONG " + arg);
                } else if (line.rfind("EXIT ", 0) == 0) {
                    int code = atoi(line.c_str() + 5);
                    _exit(code);
                } else if (line == "SIZE") {
                    struct winsize w;
                    if (ioctl(0, TIOCGWINSZ, &w) == 0) {
                        char sl[128];
                        int sl2 = snprintf(sl, sizeof(sl), "SIZE rows=%d cols=%d\n",
                                           w.ws_row, w.ws_col);
                        if (sl2 > 0) write_all(1, sl, (size_t)sl2);
                    }
                } else if (line == "RAWCTRL") {
                    // No-op marker; control-byte behavior is observed separately.
                }
            }
        }
    }
}

}  // namespace

// Create a shellless PTY pair + fork + attach + run the probe IN-PROCESS (no exec).
// Returns 0 on success. On success:
//   *out_master = master fd (parent side)
//   *out_pid    = child pid
//   slave_path  = slave pts path
// If rows/cols > 0 the initial winsize is set on the master BEFORE the child
// reads it (so the child's TIOCGWINSZ sees the requested size).
int create_shellless_pty(int* out_master, pid_t* out_pid, char* slave_path,
                         size_t slave_path_len, int rows, int cols) {
    if (!out_master || !out_pid || !slave_path || slave_path_len == 0) {
        LOGE("create_shellless_pty: bad args");
        return -1;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    if (grantpt(master) != 0) { close(master); return -1; }
    if (unlockpt(master) != 0) { close(master); return -1; }
    int rc = ptsname_r(master, slave_path, slave_path_len);
    if (rc != 0) { close(master); return -1; }
    LOGI("ptsname OK slave=%s", slave_path);

    // Set initial winsize on the MASTER before fork so the child inherits it.
    if (rows > 0 && cols > 0) {
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short)rows;
        ws.ws_col = (unsigned short)cols;
        if (ioctl(master, TIOCSWINSZ, &ws) < 0) {
            LOGE("TIOCSWINSZ(initial) failed errno=%d (%s)", errno, strerror(errno));
        }
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed errno=%d (%s)", errno, strerror(errno));
        close(master);
        return -1;
    }

    if (pid == 0) {
        // ---- child (NO execve; runs the probe in-process) ----
        if (setsid() < 0) _exit(126);

        int slave = open(slave_path, O_RDWR);
        if (slave < 0) _exit(126);

        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);

        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);

        // Close any inherited fds > 2 that might keep the master open elsewhere.
        // (Best-effort; the parent owns the master.)
        probe_loop();  // never returns except via _exit or return
        _exit(0);
    }

    // ---- parent ----
    LOGI("fork OK pid=%d (shellless, no exec)", (int)pid);
    *out_master = master;
    *out_pid = pid;
    return 0;
}

int shellless_write(int fd, const uint8_t* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, data + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t)n;
    }
    return (int)off;
}

// Read up to len bytes with timeout (ms). Returns bytes read, 0 on timeout,
// -1 on error/EOF.
ssize_t shellless_read(int fd, uint8_t* buf, size_t len, int timeout_ms) {
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    int prc = poll(&pfd, 1, timeout_ms);
    if (prc < 0) { if (errno == EINTR) return 0; return -1; }
    if (prc == 0) return 0;
    if (!(pfd.revents & POLLIN)) {
        if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) return -1;
        return 0;
    }
    ssize_t n = read(fd, buf, len);
    if (n < 0) { if (errno == EINTR) return 0; return -1; }
    return n;
}

int shellless_set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    int rc = ioctl(fd, TIOCSWINSZ, &ws);
    LOGI("parent TIOCSWINSZ master=%d -> %dx%d rc=%d errno=%d", fd, rows, cols, rc,
         rc < 0 ? errno : 0);
    // Read back from the SAME fd to confirm.
    struct winsize rb;
    memset(&rb, 0, sizeof(rb));
    if (ioctl(fd, TIOCGWINSZ, &rb) == 0) {
        LOGI("parent readback master=%d = %dx%d", fd, rb.ws_row, rb.ws_col);
    }
    return rc < 0 ? -1 : 0;
}

int shellless_get_winsize(int fd, int* rows, int* cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(fd, TIOCGWINSZ, &ws) < 0) return -1;
    *rows = ws.ws_row; *cols = ws.ws_col;
    return 0;
}

// Close master + signal + reap. Returns the child exit status (or -1 on error).
int close_shellless(int fd, pid_t pid, int timeout_ms) {
    close(fd);
    if (pid > 0) {
        kill(pid, SIGHUP);
        int deadline = timeout_ms;
        while (deadline > 0) {
            int status = 0;
            pid_t wr = waitpid(pid, &status, WNOHANG);
            if (wr == pid) return status;
            if (wr < 0) return -1;
            usleep(20000);
            deadline -= 20;
        }
        if (kill(pid, SIGKILL) == 0) {
            int status = 0;
            waitpid(pid, &status, 0);
            return status;
        }
    }
    return -1;
}

// Non-blocking waitpid: returns 1 if child exited (status filled), 0 if still
// running, -1 on error. Used to observe natural exit without blocking.
int shellless_waitpid_nonblock(pid_t pid, int* status) {
    int st = 0;
    pid_t wr = waitpid(pid, status, WNOHANG);
    if (wr == pid) return 1;
    if (wr < 0) return -1;
    return 0;
}

}  // namespace atsl
