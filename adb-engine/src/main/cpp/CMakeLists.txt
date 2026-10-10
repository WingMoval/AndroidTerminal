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

# BoringSSL vendored static library + headers (migrated from V2).
set(BORINGSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/boringssl)
set(BORINGSSL_INCLUDE ${BORINGSSL_DIR}/include)
set(BORINGSSL_LIB_DIR ${BORINGSSL_DIR}/lib/${ANDROID_ABI})
set(BORINGSSL_LIB ${BORINGSSL_LIB_DIR}/libboringssl.a)

if(NOT EXISTS ${BORINGSSL_LIB})
    message(FATAL_ERROR
        "BoringSSL static library not found at ${BORINGSSL_LIB}\n"
        "Migrate it from V2: at-core/src/main/cpp/third_party/boringssl")
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
    ${BORINGSSL_LIB}
)

# BoringSSL depends on libz for some crypto ops (not needed for our use case,
# no compression), but link it to be safe.
find_library(z-lib z)
if(z-lib)
    target_link_libraries(adbd_engine ${z-lib})
endif()

message(STATUS "BoringSSL: linking ${BORINGSSL_LIB}")