// ATsh.T1 — minimal PTY experiment native interface (PROVISIONAL).
#pragma once

#include <cstddef>
#include <cstdint>
#include <sys/types.h>

namespace atsh {

// Create PTY master/slave, fork, attach controlling tty, exec /system/bin/sh -i.
int create_pty_shell(int* out_master, pid_t* out_pid, char* slave_path,
                     size_t slave_path_len);

// Blocking write of all bytes. Returns bytes written or -1.
int pty_write(int fd, const uint8_t* data, size_t len);

// Poll + read with timeout. Returns bytes read (0=timeout, -1=error/EOF).
ssize_t pty_read(int fd, uint8_t* buf, size_t len, int timeout_ms);

// TIOCGWINSZ / TIOCSWINSZ. Returns 0 on success.
int pty_get_winsize(int fd, int* rows, int* cols);
int pty_set_winsize(int fd, int rows, int cols);

// Close master, SIGHUP child, reap (SIGKILL fallback). Returns 0.
int close_pty(int fd, pid_t pid, int timeout_ms);

}  // namespace atsh
