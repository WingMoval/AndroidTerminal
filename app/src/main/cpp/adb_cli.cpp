// AT.CLI-0 — shell-hosted `adb` CLI frontend (product-internal).
//
// A native PIE executable extracted by the app into its private files/bin dir
// and placed on the Local /system/bin/sh PATH. It is the ONLY thing the user
// types as `adb`. It does NOT implement ADB itself: it speaks the Product CLI
// Bridge protocol over a Unix Domain Socket in the app's private files dir and
// delegates to the app process (AppRuntime -> ATEngine/ADB Backend -> ADB Engine).
//
// Scope this round: `shell` subcommand only (one-shot + interactive), plus
// minimal `--help` / `version`. Everything else is a clear "unsupported".
//
// Security: runs as the app UID (forked from the Local PTY). The bridge server
// verifies SO_PEERCRED uid == app uid. No network, no second ADB stack.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/select.h>
#include <sys/ioctl.h>

#define CLI_BRIDGE_PROTOCOL_VERSION 1
#define MAX_FRAME 1u * 1024u * 1024u  // 1 MiB cap per frame

// Frame type tags (1 byte).
enum {
    T_HELLO = 1,        // client -> server: protocol version
    T_HELLO_ACK = 2,    // server -> client: protocol version
    T_ONESHOT = 3,      // client -> server: one-shot shell request (payload = command string)
    T_OUT = 4,          // server -> client: stdout bytes (one-shot or interactive)
    T_ERR = 5,          // server -> client: stderr bytes
    T_EXIT = 6,         // server -> client: exit code (int32 LE)
    T_INTERACTIVE = 7,  // client -> server: open interactive (payload = rows:int32,cols:int32)
    T_STDIN = 8,        // client -> server: stdin bytes (interactive)
    T_RESIZE = 9,       // client -> server: rows:int32, cols:int32
    T_CLOSE = 10,       // client -> server: close interactive
    T_ERROR = 11,       // server -> client: error string
};

static int g_sock = -1;

// ---- little-endian frame IO (binary-safe) ---------------------------------
static int write_all(int fd, const void* buf, size_t n) {
    const uint8_t* p = (const uint8_t*)buf;
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, p + off, n - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t)w;
    }
    return 0;
}

static int read_all(int fd, void* buf, size_t n) {
    uint8_t* p = (uint8_t*)buf;
    size_t off = 0;
    while (off < n) {
        ssize_t r = read(fd, p + off, n - off);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return -1; // EOF
        off += (size_t)r;
    }
    return 0;
}

// Send a frame: [type:1][len:4 LE][payload:len].
static int send_frame(uint8_t type, const void* payload, uint32_t len) {
    uint8_t hdr[5];
    hdr[0] = type;
    hdr[1] = (uint8_t)(len & 0xff);
    hdr[2] = (uint8_t)((len >> 8) & 0xff);
    hdr[3] = (uint8_t)((len >> 16) & 0xff);
    hdr[4] = (uint8_t)((len >> 24) & 0xff);
    if (write_all(g_sock, hdr, 5) < 0) return -1;
    if (len > 0 && write_all(g_sock, payload, len) < 0) return -1;
    return 0;
}

// Recv a frame header + payload. Returns 0 ok, -1 error/EOF. *out_len set.
// payload buffer must be >= MAX_FRAME.
static int recv_frame(uint8_t* type, uint8_t* payload, uint32_t* out_len) {
    uint8_t hdr[5];
    if (read_all(g_sock, hdr, 5) < 0) return -1;
    *type = hdr[0];
    *out_len = (uint32_t)hdr[1] | ((uint32_t)hdr[2] << 8) | ((uint32_t)hdr[3] << 16) | ((uint32_t)hdr[4] << 24);
    if (*out_len > MAX_FRAME) return -1;
    if (*out_len > 0 && read_all(g_sock, payload, *out_len) < 0) return -1;
    return 0;
}

static uint32_t le32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void put_le32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff);
    p[3] = (uint8_t)((v >> 24) & 0xff);
}

// ---- socket connect --------------------------------------------------------
static int connect_bridge(void) {
    // The bridge path is passed via env (set by the launcher) or defaults to
    // the app files dir. We receive it through ANDROID_CLI_BRIDGE env.
    const char* dir = getenv("AT_CLI_BRIDGE_DIR");
    if (!dir || !*dir) dir = "/data/data/com.androidterminal.app/files";
    char path[512];
    snprintf(path, sizeof(path), "%s/cli_bridge.sock", dir);
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) { fprintf(stderr, "adb: socket: %s\n", strerror(errno)); return -1; }
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof(a.sun_path) - 1);
    if (connect(s, (struct sockaddr*)&a, sizeof(a)) < 0) {
        int e = errno;
        close(s);
        fprintf(stderr, "adb: cannot reach app bridge: %s\n", strerror(e));
        return -1;
    }
    return s;
}

// ---- parser (no shell out, no recursion) ----------------------------------
// argv[0] = adb. We only accept:
//   adb shell
//   adb shell <cmd...>
//   adb --help | adb -h | adb help
//   adb version
// Everything else -> unsupported (exit 2).
static int parse_args(int argc, char** argv, int* want_interactive, char** out_cmd) {
    if (argc >= 2) {
        if (strcmp(argv[1], "shell") == 0) {
            *want_interactive = (argc == 2);
            if (argc > 2) {
                // Rejoin argv[2..] with single spaces (outer sh already split).
                size_t total = 0;
                for (int i = 2; i < argc; i++) total += strlen(argv[i]) + 1;
                char* cmd = (char*)malloc(total + 1);
                cmd[0] = 0;
                for (int i = 2; i < argc; i++) {
                    if (i > 2) strcat(cmd, " ");
                    strcat(cmd, argv[i]);
                }
                *out_cmd = cmd;
            } else {
                *out_cmd = NULL;
            }
            return 0;
        }
        if (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0 ||
            strcmp(argv[1], "help") == 0) {
            printf("usage: adb shell [command...]\n"
                   "       adb shell            (interactive remote shell)\n"
                   "       adb --help | adb version\n"
                   "AndroidTerminal product CLI (shell subcommand only).\n");
            return 1; // handled
        }
        if (strcmp(argv[1], "version") == 0) {
            printf("AndroidTerminal adb CLI (bridge protocol v%d)\n", CLI_BRIDGE_PROTOCOL_VERSION);
            return 1;
        }
        // unsupported subcommand
        fprintf(stderr, "adb: unsupported command: %s (this build supports only `shell`)\n", argv[1]);
        return 2;
    }
    fprintf(stderr, "adb: missing command (try `adb --help`)\n");
    return 2;
}

static int do_handshake(void) {
    uint8_t v = CLI_BRIDGE_PROTOCOL_VERSION;
    if (send_frame(T_HELLO, &v, 1) < 0) { fprintf(stderr, "adb: send hello failed\n"); return -1; }
    uint8_t type; uint8_t payload[MAX_FRAME]; uint32_t len;
    if (recv_frame(&type, payload, &len) < 0) { fprintf(stderr, "adb: no hello ack\n"); return -1; }
    if (type != T_HELLO_ACK || len < 1) { fprintf(stderr, "adb: bad hello ack\n"); return -1; }
    if (payload[0] != CLI_BRIDGE_PROTOCOL_VERSION) {
        fprintf(stderr, "adb: bridge protocol version mismatch (got %d)\n", payload[0]);
        return -1;
    }
    return 0;
}

static int do_oneshot(const char* cmd) {
    if (send_frame(T_ONESHOT, cmd, (uint32_t)strlen(cmd)) < 0) return -1;
    int exit_code = -1;
    uint8_t type; uint8_t payload[MAX_FRAME]; uint32_t len;
    for (;;) {
        if (recv_frame(&type, payload, &len) < 0) break;
        switch (type) {
            case T_OUT:  if (len) fwrite(payload, 1, len, stdout);  break;
            case T_ERR:  if (len) fwrite(payload, 1, len, stderr);  break;
            case T_EXIT: if (len >= 4) exit_code = (int)le32(payload); break;
            case T_ERROR:
                payload[len < MAX_FRAME ? len : MAX_FRAME - 1] = 0;
                fprintf(stderr, "adb: %s\n", (char*)payload);
                if (exit_code < 0) exit_code = 1;
                break;
            default: break;
        }
        fflush(stdout); fflush(stderr);
        if (type == T_EXIT || type == T_ERROR) break;
    }
    return exit_code;
}

// ---- interactive: byte relay between local PTY and bridge -----------------
static int get_term_size(int* rows, int* cols) {
    struct winsize ws;
    if (ioctl(0, TIOCGWINSZ, &ws) == 0 && ws.ws_row > 0 && ws.ws_col > 0) {
        *rows = ws.ws_row; *cols = ws.ws_col; return 0;
    }
    *rows = 24; *cols = 80;
    return -1;
}

static void install_sigwinch_handler(void) {
    // SIGWINCH: recompute local size; we poll in the relay loop (bounded).
    signal(SIGWINCH, SIG_IGN); // we handle via TIOCGWINSZ polling, not a pipe
}

static int do_interactive(void) {
    int rows = 24, cols = 80;
    get_term_size(&rows, &cols);

    uint8_t hdr[8];
    put_le32(hdr, (uint32_t)rows);
    put_le32(hdr + 4, (uint32_t)cols);
    if (send_frame(T_INTERACTIVE, hdr, 8) < 0) { fprintf(stderr, "adb: open interactive failed\n"); return -1; }

    int is_tty = isatty(0) && isatty(1);
    struct termios orig;
    int saved = 0;
    if (is_tty) {
        if (tcgetattr(0, &orig) == 0) {
            saved = 1;
            struct termios raw = orig;
            // near-raw: byte relay, no line buffering, no echo (remote echoes).
            cfmakeraw(&raw);
            raw.c_cc[VMIN] = 1;
            raw.c_cc[VTIME] = 0;
            tcsetattr(0, TCSANOW, &raw);
        }
    }
    install_sigwinch_handler();

    int last_rows = rows, last_cols = cols;
    int rc = 0;
    uint8_t type; uint8_t payload[MAX_FRAME]; uint32_t len;
    uint8_t inbuf[4096];

    for (;;) {
        // Poll local terminal size (bounded, not a tight spin).
        int pr = 24, pc = 80;
        if (get_term_size(&pr, &pc) == 0 && (pr != last_rows || pc != last_cols)) {
            uint8_t rh[8];
            put_le32(rh, (uint32_t)pr);
            put_le32(rh + 4, (uint32_t)pc);
            if (send_frame(T_RESIZE, rh, 8) == 0) { last_rows = pr; last_cols = pc; }
        }

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(0, &rfds);
        FD_SET(g_sock, &rfds);
        int maxfd = (0 > g_sock ? 0 : g_sock) + 1;
        struct timeval tv; tv.tv_sec = 0; tv.tv_usec = 50 * 1000;
        int sel = select(maxfd, &rfds, NULL, NULL, &tv);
        if (sel < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (sel == 0) continue;

        if (FD_ISSET(0, &rfds)) {
            ssize_t n = read(0, inbuf, sizeof(inbuf));
            if (n <= 0) {
                // stdin EOF -> signal close
                send_frame(T_CLOSE, NULL, 0);
                break;
            }
            if (send_frame(T_STDIN, inbuf, (uint32_t)n) < 0) { rc = 1; break; }
        }
        if (FD_ISSET(g_sock, &rfds)) {
            // Peek a frame; if it's OUT, write to stdout.
            uint8_t hdr[5];
            ssize_t got = read(g_sock, hdr, 5);
            if (got <= 0) break; // remote closed
            // We read the header inline here (not via recv_frame) to avoid
            // blocking on payload if partial; reconstruct with recv of payload.
            uint32_t flen = (uint32_t)hdr[1] | ((uint32_t)hdr[2] << 8) |
                            ((uint32_t)hdr[3] << 16) | ((uint32_t)hdr[4] << 24);
            if (flen > MAX_FRAME) { rc = 1; break; }
            uint32_t off = 0;
            while (off < flen) {
                ssize_t r = read(g_sock, payload + off, flen - off);
                if (r <= 0) { rc = 1; goto done; }
                off += (uint32_t)r;
            }
            switch (hdr[0]) {
                case T_OUT: if (flen) { fwrite(payload, 1, flen, stdout); fflush(stdout); } break;
                case T_ERR: if (flen) { fwrite(payload, 1, flen, stderr); fflush(stderr); } break;
                case T_EXIT: if (flen >= 4) { rc = (int)le32(payload); goto done; } break;
                case T_ERROR:
                    if (flen < MAX_FRAME) payload[flen] = 0;
                    fprintf(stderr, "adb: %s\n", (char*)payload);
                    goto done;
                default: break;
            }
        }
    }
done:
    send_frame(T_CLOSE, NULL, 0);
    if (saved) tcsetattr(0, TCSANOW, &orig); // restore termios ALWAYS
    return rc;
}

int main(int argc, char** argv) {
    int want_interactive = 0;
    char* cmd = NULL;
    int prc = parse_args(argc, argv, &want_interactive, &cmd);
    if (prc == 1) return 0;          // help/version handled
    if (prc == 2) return 2;          // unsupported
    if (cmd != NULL && want_interactive) { /* impossible */ }

    g_sock = connect_bridge();
    if (g_sock < 0) return 3;
    if (do_handshake() < 0) { close(g_sock); return 3; }

    int rc;
    if (cmd != NULL) {
        rc = do_oneshot(cmd);
    } else {
        rc = do_interactive();
    }
    close(g_sock);
    free(cmd);
    return rc;
}
