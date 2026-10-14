# AT.TERM-0 — shellless PTY validation probe native (VALIDATION ONLY).
#
# Duplicates the minimal PTY primitives (posix_openpt/grantpt/unlockpt/ptsname_r/
# fork/setsid/TIOCSCTTY/dup2/TIOCSWINSZ/TIOCGWINSZ/waitpid) so this module has
# ZERO dependency on :at-backend-local. The child does NOT exec any shell; it
# runs the in-process probe loop. VALIDATION_DUPLICATION_ONLY — NOT a third
# production backend.
#
# Links only liblog. No third-party terminal library, no shell.

cmake_minimum_required(VERSION 3.22.1)

project("at_shellless_probe" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(
    at_shellless_probe
    SHARED
    shellless_pty.cpp
    shellless_pty_jni.cpp
)

target_include_directories(at_shellless_probe PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(at_shellless_probe ${log-lib})
