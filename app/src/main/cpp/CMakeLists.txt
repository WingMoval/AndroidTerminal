# :app native — AT.CLI-0 shell-hosted `adb` CLI frontend.
#
# A standalone PIE executable (`adb`) extracted by the app into its private
# files/bin dir and placed on the Local /system/bin/sh PATH. It speaks the
# Product CLI Bridge protocol over a Unix Domain Socket to the app process.
# It does NOT implement ADB; it delegates to AppRuntime -> ATEngine/ADB Backend
# -> ADB Engine. Shell subcommand only this round.

cmake_minimum_required(VERSION 3.22.1)

project("atAdbCli" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_executable(atadb_cli
    adb_cli.cpp
)

# Static libstdc++ so the executable is self-contained (no host libstdc++ dep).
set_target_properties(atadb_cli PROPERTIES
    LINK_FLAGS "-static-libstdc++ -static-libgcc -Wl,-z,relro"
)

target_link_libraries(atadb_cli
    log
)

# JNI helper (setenv + UDS server) loaded by the app. The UDS server binds the
# Product CLI Bridge socket in the app's private files dir and relays frames to
# a Kotlin CliBridgeHandler via JNI.
add_library(atcli_env SHARED
    native_env.cpp
    cli_bridge_server.cpp
)
target_link_libraries(atcli_env log)
