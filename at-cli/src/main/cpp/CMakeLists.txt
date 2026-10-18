# :at-cli — Reusable Shell-Visible ADB CLI Consumer (AT.CLI-CONSUMER-0).
#
# JNI shared lib `atcli_env` = native setenv/UDS helpers + the same-UID UDS
# bridge server (cli_bridge_server.cpp). Loaded by the host app's
# AdbCliBridgeServer. The bridge relays frames to a Kotlin ATCliBridgeHandler
# via JNI; the handler delegates to a host-provided ATCliExecution (NOT any
# :app concrete class).
#
# NOTE: the shell-visible `adb` frontend is the app_process-based
# AtCliMain (Kotlin, launched via the `adb()` shell function). A native ELF
# `adb` is NOT built here — SELinux blocks untrusted_app from executing a
# private files/bin ELF (RC=126); app_process (zygote_exec) is the verified
# launcher. This matches the AT.CLI-0.1 SELECTED_CLI_ENTRYPOINT = APP_PROCESS.
#
# App-layer / consumer composition only. Not an AT/ADB Engine change. Present
# in both debug and release builds.

cmake_minimum_required(VERSION 3.22.1)

project("atCliConsumer" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(atcli_env SHARED
    native_env.cpp
    cli_bridge_server.cpp
)
target_link_libraries(atcli_env log)
