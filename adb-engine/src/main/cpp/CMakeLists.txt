# :adb-engine native — ADB Engine (independent execution provider).
#
# AT.V3-1 — Verified migration from V2 at-core/src/main/cpp.
# Batch A: ADB 底层基础 (adb_protocol/packet framing, TCP transport,
#          crypto RSA-2048 + X.509, BoringSSL dependency).
#
# This library contains ONLY ADB Engine responsibilities (Canonical V1 §7.2).
# It MUST NOT depend on / include any AT Engine, TerminalSession, Emulator,
# Screen State, AndroidTerminal UI, or AndroidAgent code.

cmake_minimum_required(VERSION 3.22.1)

project("adbdEngine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# BoringSSL vendored static libraries + headers.
# Upstream: https://github.com/google/boringssl commit df82113 (2026-10-22)
# License: Apache-2.0. Built with NDK 26.1.10909125, Clang 17.0.2.
# See third_party/boringssl/UPSTREAM_VERSION.md for build details.
set(BORINGSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/boringssl)
set(BORINGSSL_INCLUDE ${BORINGSSL_DIR}/include)
set(BORINGSSL_LIB_DIR ${BORINGSSL_DIR}/lib/${ANDROID_ABI})
set(BORINGSSL_CRYPTO_LIB ${BORINGSSL_LIB_DIR}/libcrypto.a)
set(BORINGSSL_SSL_LIB ${BORINGSSL_LIB_DIR}/libssl.a)

if(NOT EXISTS ${BORINGSSL_CRYPTO_LIB} OR NOT EXISTS ${BORINGSSL_SSL_LIB})
    message(FATAL_ERROR
        "BoringSSL static libraries not found at ${BORINGSSL_LIB_DIR}\n"
        "Expected: libcrypto.a and libssl.a\n"
        "Build from source: see UPSTREAM_VERSION.md for instructions.")
endif()

add_library(
    adbd_engine
    SHARED
    # --- Batch A: ADB protocol / packet framing ---
    adb_protocol/adb_packet.cpp

    # --- Batch A: transport ---
    transport/tcp_transport.cpp

    # --- Batch A: crypto ---
    adb/crypto/rsa_2048_key.cpp
    adb/crypto/x509_generator.cpp

    # --- Batch B: TLS + Connection + Identity(key store) ---
    adb/tls/tls_connection.cpp
    adb/transport/tls_transport.cpp
    adb/connection/adb_connection.cpp
    adb/connection/feature_set.cpp
    adb/client/adb_key_store.cpp

    # --- Batch C: AdbClient + shell-v2 (one-shot transport) ---
    adb/client/adb_client.cpp
    adb/service/shell_v2_protocol.cpp

    # --- Batch D: Interactive Stream Primitive ---
    adb/client/adb_interactive_stream.cpp

    # --- AT.V3-2.1: post-ONLINE stream multiplexer (single RX owner) ---
    adb/connection/adb_stream_hub.cpp

    # --- Batch E: Pairing ---
    adb/pairing/pairing_aes_gcm.cpp
    adb/pairing/pairing_auth.cpp
    adb/pairing/pairing_client.cpp

    # --- Batch G: canonical JNI bridge (ADB-domain) ---
    jni/adb_engine_jni.cpp
)

target_include_directories(adbd_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${BORINGSSL_INCLUDE}
)

target_link_libraries(adbd_engine
    ${log-lib}
    ${BORINGSSL_SSL_LIB}
    ${BORINGSSL_CRYPTO_LIB}
)

# BoringSSL depends on libz for some crypto ops (not needed for our use case,
# no compression), but link it to be safe.
find_library(z-lib z)
if(z-lib)
    target_link_libraries(adbd_engine ${z-lib})
endif()

# --- AT.V3-DEBT-1: pairing deterministic self-test (device-free, V2 AT.C-1) ---
# Restored from V2 (at-core pairing_selftest, IDENTICAL source). Covers
# PairingPacket framing, PeerInfo 8192B layout, SPAKE2 64B key material
# (guards the historical 32B-truncation regression), AES-128-GCM roundtrip +
# bad-tag/wrong-key rejection, pairing-code validation. Not packaged into the
# app; run as a standalone native binary on device/emulator.
add_executable(pairing_selftest
    adb/pairing/pairing_selftest.cpp
    adb/pairing/pairing_aes_gcm.cpp
    adb/pairing/pairing_auth.cpp
)
target_include_directories(pairing_selftest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${BORINGSSL_INCLUDE}
)
set_target_properties(pairing_selftest PROPERTIES
    LINK_FLAGS "-static-libstdc++ -static-libgcc"
)
target_link_libraries(pairing_selftest
    ${log-lib}
    ${BORINGSSL_SSL_LIB}
    ${BORINGSSL_CRYPTO_LIB}
)
if(z-lib)
    target_link_libraries(pairing_selftest ${z-lib})
endif()

message(STATUS "BoringSSL: linking ${BORINGSSL_SSL_LIB} + ${BORINGSSL_CRYPTO_LIB}")