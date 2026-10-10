# :at-backend-local native — Local execution provider.
#
# AT.PERSIST-0 — Verified migration of the ATsh.T1 Local PTY primitive
# (posix_openpt/grantpt/unlockpt/fork/setsid/TIOCSCTTY/dup2/execve/TIOCSWINSZ/
# wait+cleanup). NOT a rewrite: the core pty behavior is migrated verbatim.
#
# This library links only liblog (native logging for evidence). No third-party
# terminal library. Local Backend depends ONLY on AT Contract + Android/bionic/
# kernel execution primitives (Canonical V1 §19 + AT.PERSIST-0 §17).

cmake_minimum_required(VERSION 3.22.1)

project("at_local_backend" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(
    at_local_backend
    SHARED
    local_pty.cpp
    local_pty_jni.cpp
)

target_include_directories(at_local_backend PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(at_local_backend ${log-lib})