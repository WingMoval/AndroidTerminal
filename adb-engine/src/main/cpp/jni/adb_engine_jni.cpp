// ADB Engine 鈥?JNI bridge (ADB-domain, canonical).
//
// AT.V3-1 鈥?wraps the migrated adb::AdbClient (connection + one-shot shell
// transport) and exposes it to Kotlin via com.adb.engine.AdbEngine.
//
// Canonical ADB Engine boundary. Deliberately does NOT include:
//   - legacy second-handshake path (V2 nativeExecute)
//   - at_core::Core / at_runtime::MinimalRuntime / AT result domain
//   - netDiag / network_probe / dead placeholder headers
//
// Marshal + forward only. Owns one adb::AdbClient, keyed by key_dir.

#include <jni.h>

#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "adb/client/adb_client.h"
#include "adb/client/adb_interactive_stream.h"
#include "adb/client/adb_key_store.h"
#include "adb/connection/adb_stream_hub.h"
#include "adb/connection/plain_tcp_connection.h"
#include "adb/pairing/pairing_client.h"
#include "adb/service/shell_v2_protocol.h"

#include <atomic>
#include <vector>

#define LOG_TAG "AdbEngineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
std::unique_ptr<adb::AdbClient> g_client;
std::string g_client_key_dir;

// Ensure a client for the given key dir exists (re-creates when key dir
// changes). AdbClient's constructor owns the key store for that dir.
//
// AT.V3-2 fix (real integration bug found during ADB backend slice): the
// previous implementation only created the client when null, so a second
// connect with a DIFFERENT keyDir silently reused the stale client (and its
// wrong identity), causing connect/key-load failures. Now the client is
// rebuilt whenever key_dir changes. No protocol/TLS/connection behavior is
// touched.
adb::AdbClient& ClientFor(const std::string& key_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_client && g_client_key_dir == key_dir) {
        return *g_client;
    }
    g_client = std::make_unique<adb::AdbClient>(key_dir);
    g_client_key_dir = key_dir;
    return *g_client;
}

// For operations that do not take a keyDir (executeShell / connectionState /
// interactive), return the client already bound by the last connect.
//
// AT.LOCAL-TCP-1A (null-safe fix, verified in AT.RESEARCH-LOCAL-TCP-1.7):
// return nullptr instead of auto-creating a fallback client. A null g_client
// means no wireless connection was ever established; callers must handle
// nullptr gracefully (e.g. connectionState returns Disconnected).
adb::AdbClient* ClientExisting() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_client.get();
}

jint ShellStatusOrdinal(adb::ShellStatus s) {
    switch (s) {
        case adb::ShellStatus::Completed: return 0;
        case adb::ShellStatus::TransportError: return 1;
        default: return 2;  // ProtocolError
    }
}

jint ConnectionStateOrdinal(adb::ConnectionState s) {
    switch (s) {
        case adb::ConnectionState::Disconnected: return 0;
        case adb::ConnectionState::Connecting: return 1;
        case adb::ConnectionState::WaitingStls: return 2;
        case adb::ConnectionState::TlsNegotiating: return 3;
        case adb::ConnectionState::WaitingPostTlsCnxn: return 4;
        case adb::ConnectionState::Online: return 5;
        case adb::ConnectionState::Closing: return 6;
        case adb::ConnectionState::Offline: return 7;
    }
    return 0;
}

// Build com.adb.engine.AdbShellResult(status, stdout, stderr, exitCode,
// hasError, error) 鈥?matches the data class primary constructor order.
jobject NewAdbShellResult(JNIEnv* env, const adb::ShellResult& r) {
    jclass clazz = env->FindClass("com/adb/engine/AdbShellResult");
    if (clazz == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(
        clazz, "<init>",
        "(ILjava/lang/String;Ljava/lang/String;IZLjava/lang/String;)V");
    if (ctor == nullptr) return nullptr;
    jstring j_out = env->NewStringUTF(r.stdout_data.c_str());
    jstring j_err = env->NewStringUTF(r.stderr_data.c_str());
    jstring j_err2 = r.has_error ? env->NewStringUTF(r.error.c_str()) : nullptr;
    return env->NewObject(clazz, ctor, ShellStatusOrdinal(r.status), j_out,
                          j_err, r.exit_code,
                          static_cast<jboolean>(r.has_error), j_err2);
}

}  // namespace

extern "C" {

// com.adb.engine.internal.AdbNative.connect(String, int, String):boolean
JNIEXPORT jboolean JNICALL Java_com_adb_engine_internal_AdbNative_connect(
    JNIEnv* env, jobject /* thiz */, jstring j_host, jint j_port,
    jstring j_key_dir) {
    std::string host;
    std::string key_dir;
    if (j_host) {
        const char* c = env->GetStringUTFChars(j_host, nullptr);
        if (c) { host = c; env->ReleaseStringUTFChars(j_host, c); }
    }
    if (j_key_dir) {
        const char* c = env->GetStringUTFChars(j_key_dir, nullptr);
        if (c) { key_dir = c; env->ReleaseStringUTFChars(j_key_dir, c); }
    }
    LOGI("AdbNative.connect %s:%d keyDir=%s", host.c_str(), j_port,
         key_dir.c_str());
        adb::AdbClient& client = ClientFor(key_dir);
    return static_cast<jboolean>(client.connect(host, j_port));
}

// com.adb.engine.internal.AdbNative.disconnect():void
JNIEXPORT void JNICALL Java_com_adb_engine_internal_AdbNative_disconnect(
    JNIEnv* /* env */, jobject /* thiz */) {
    LOGI("AdbNative.disconnect");
    adb::AdbClient* client = ClientExisting();
    if (client) client->disconnect();
}

// com.adb.engine.internal.AdbNative.connectionState():int
JNIEXPORT jint JNICALL Java_com_adb_engine_internal_AdbNative_connectionState(
    JNIEnv* /* env */, jobject /* thiz */) {
    adb::AdbClient* client = ClientExisting();
    if (!client) return 0;  // Disconnected (client not yet created)
    return ConnectionStateOrdinal(client->connection_state());
}

// com.adb.engine.internal.AdbNative.lastConnectTrustRejected():boolean
// AT.TRUST-LOSS-RECOVERY-1: true if the last connect() was rejected by the
// device at the TLS layer (device no longer trusts our key).
JNIEXPORT jboolean JNICALL
Java_com_adb_engine_internal_AdbNative_lastConnectTrustRejected(
    JNIEnv* /* env */, jobject /* thiz */) {
    adb::AdbClient* client = ClientExisting();
    if (!client) return JNI_FALSE;
    return static_cast<jboolean>(client->last_connect_trust_rejected());
}

// com.adb.engine.internal.AdbNative.executeShell(String, int):AdbShellResult
JNIEXPORT jobject JNICALL Java_com_adb_engine_internal_AdbNative_executeShell(
    JNIEnv* env, jobject /* thiz */, jstring j_command, jint j_timeout_ms) {
    std::string command;
    if (j_command) {
        const char* c = env->GetStringUTFChars(j_command, nullptr);
        if (c) { command = c; env->ReleaseStringUTFChars(j_command, c); }
    }
    LOGI("AdbNative.executeShell: \"%s\" timeout_ms=%d", command.c_str(),
         j_timeout_ms);
    adb::AdbClient* client = ClientExisting();
    if (!client) {
        adb::ShellResult r;
        r.status = adb::ShellStatus::TransportError;
        r.has_error = true;
        r.error = "not connected (client not initialized)";
        return NewAdbShellResult(env, r);
    }
    // timeout_ms is forwarded for interface parity; the ADB transport does not
    // implement a real exec timeout yet (same as V2 Core v0.1 semantics).
    (void)j_timeout_ms;
    const adb::ShellResult r = client->shell(command);
    return NewAdbShellResult(env, r);
}

// ---------------------------------------------------------------------------
// Interactive stream primitive (ADB Engine, Batch D).
//
// AT.V3-2.1: handles are shared_ptr so that a blocking interactiveRead() on
// one thread stays memory-safe when interactiveClose() runs on another thread.
// close() wakes the blocked read via the stream slot condvar.
// ---------------------------------------------------------------------------

namespace {
std::mutex g_streams_mutex;
std::unordered_map<jlong, std::shared_ptr<adb::AdbInteractiveStream>> g_streams;
jlong g_next_handle = 1;

std::shared_ptr<adb::AdbInteractiveStream> FindStream(jlong handle) {
    std::lock_guard<std::mutex> lock(g_streams_mutex);
    auto it = g_streams.find(handle);
    return it == g_streams.end() ? nullptr : it->second;
}
}  // namespace

// com.adb.engine.internal.AdbNative.interactiveOpen(String):long
JNIEXPORT jlong JNICALL Java_com_adb_engine_internal_AdbNative_interactiveOpen(
    JNIEnv* env, jobject /* thiz */, jstring j_service) {
    std::string service;
    if (j_service) {
        const char* c = env->GetStringUTFChars(j_service, nullptr);
        if (c) { service = c; env->ReleaseStringUTFChars(j_service, c); }
    }
    adb::AdbClient* client = ClientExisting();
    adb::AdbStreamHub* hub = client ? client->streamHub() : nullptr;
    if (hub == nullptr) {
        LOGE("interactiveOpen: connection not ONLINE (no stream hub)");
        return 0;
    }
    auto stream = std::make_shared<adb::AdbInteractiveStream>();
    std::string err;
    if (!stream->open(hub, service, &err)) {
        LOGE("interactiveOpen: open failed: %s", err.c_str());
        return 0;
    }
    jlong handle = 0;
    {
        std::lock_guard<std::mutex> lock(g_streams_mutex);
        handle = g_next_handle++;
        g_streams[handle] = stream;
    }
    LOGI("interactiveOpen: handle=%lld service=%s local=%u remote=%u",
         (long long)handle, service.c_str(), stream->localId(),
         stream->remoteId());
    return handle;
}

// com.adb.engine.internal.AdbNative.interactiveWrite(long, byte[]):int
JNIEXPORT jint JNICALL Java_com_adb_engine_internal_AdbNative_interactiveWrite(
    JNIEnv* env, jobject /* thiz */, jlong j_handle, jbyteArray j_data) {
    auto stream = FindStream(j_handle);
    if (!stream) return -1;
    jsize len = env->GetArrayLength(j_data);
    jbyte* buf = env->GetByteArrayElements(j_data, nullptr);
    std::string err;
    bool ok = stream->writeStdin(
        reinterpret_cast<const uint8_t*>(buf), static_cast<size_t>(len), &err);
    env->ReleaseByteArrayElements(j_data, buf, JNI_ABORT);
    return ok ? static_cast<jint>(len) : -1;
}

// com.adb.engine.internal.AdbNative.interactiveRead(long, byte[], int):int
JNIEXPORT jint JNICALL Java_com_adb_engine_internal_AdbNative_interactiveRead(
    JNIEnv* env, jobject /* thiz */, jlong j_handle, jbyteArray j_buf,
    jint j_timeout_ms) {
    auto stream = FindStream(j_handle);
    if (!stream) return -1;
    jsize cap = env->GetArrayLength(j_buf);
    jbyte* buf = env->GetByteArrayElements(j_buf, nullptr);
    std::string err;
    ssize_t n = stream->read(
        reinterpret_cast<uint8_t*>(buf), static_cast<size_t>(cap),
        static_cast<int>(j_timeout_ms), &err);
    env->ReleaseByteArrayElements(j_buf, buf,
                                  n > 0 ? 0 : JNI_ABORT);
    return static_cast<jint>(n);
}

// com.adb.engine.internal.AdbNative.interactiveResize(long,int,int):boolean
JNIEXPORT jboolean JNICALL Java_com_adb_engine_internal_AdbNative_interactiveResize(
    JNIEnv* /* env */, jobject /* thiz */, jlong j_handle, jint j_rows, jint j_cols) {
    auto stream = FindStream(j_handle);
    if (!stream) return JNI_FALSE;
    std::string err;
    return static_cast<jboolean>(stream->resize(j_rows, j_cols, &err));
}

// com.adb.engine.internal.AdbNative.interactiveCloseStdin(long):boolean
JNIEXPORT jboolean JNICALL Java_com_adb_engine_internal_AdbNative_interactiveCloseStdin(
    JNIEnv* /* env */, jobject /* thiz */, jlong j_handle) {
    auto stream = FindStream(j_handle);
    if (!stream) return JNI_FALSE;
    std::string err;
    return static_cast<jboolean>(stream->closeStdin(&err));
}

// com.adb.engine.internal.AdbNative.interactiveClose(long):void
JNIEXPORT void JNICALL Java_com_adb_engine_internal_AdbNative_interactiveClose(
    JNIEnv* /* env */, jobject /* thiz */, jlong j_handle) {
    std::shared_ptr<adb::AdbInteractiveStream> stream;
    {
        std::lock_guard<std::mutex> lock(g_streams_mutex);
        auto it = g_streams.find(j_handle);
        if (it != g_streams.end()) {
            stream = it->second;
            g_streams.erase(it);
        }
    }
    if (stream) {
        // Wakes any in-flight interactiveRead() on this stream; the object
        // stays alive until that call releases its shared_ptr.
        stream->close();
    }
}

// com.adb.engine.internal.AdbNative.pair(String,int,String,String):String
JNIEXPORT jstring JNICALL Java_com_adb_engine_internal_AdbNative_pair(
    JNIEnv* env, jobject /* thiz */, jstring j_host, jint j_port,
    jstring j_code, jstring j_key_dir) {
    std::string host;
    std::string code;
    std::string key_dir;
    if (j_host) {
        const char* c = env->GetStringUTFChars(j_host, nullptr);
        if (c) { host = c; env->ReleaseStringUTFChars(j_host, c); }
    }
    if (j_code) {
        const char* c = env->GetStringUTFChars(j_code, nullptr);
        if (c) { code = c; env->ReleaseStringUTFChars(j_code, c); }
    }
    if (j_key_dir) {
        const char* c = env->GetStringUTFChars(j_key_dir, nullptr);
        if (c) { key_dir = c; env->ReleaseStringUTFChars(j_key_dir, c); }
    }
    LOGI("AdbNative.pair %s:%d code=%s keyDir=%s", host.c_str(), j_port,
         code.c_str(), key_dir.c_str());

    // Pairing uses its OWN AdbKeyStore (same keyDir as connect identity).
    adb::AdbKeyStore keys(key_dir);
    if (!keys.loadOrCreate()) {
        return env->NewStringUTF("status=1;success=0;error=key load failed");
    }
    adb::PairingClient pc(&keys);
    adb::PairingResult pr = pc.pair(host, j_port, code);
    std::string out = "status=" + std::to_string(static_cast<int>(pr.status)) +
                      ";success=" + (pr.success() ? "1" : "0") +
                      ";guid=" + pr.device_guid +
                      ";error=" + pr.error;
    LOGI("AdbNative.pair -> %s", out.c_str());
    return env->NewStringUTF(out.c_str());
}

// ---------------------------------------------------------------------------
// AT.LOCAL-TCP-1A: plain TCP localhost ADB (product core).
//
// ONE shared AdbStreamHub multiplexes all streams over ONE plain TCP
// connection. connectLocalhost is IDEMPOTENT (reuses an ONLINE transport).
// one-shot + interactive all go through the hub (ONE connection, N streams).
// ---------------------------------------------------------------------------

namespace {

struct LocalTcpState {
    adb::TcpTransport transport;
    adb::PlainTcpAdbConnection* conn = nullptr;
    adb::AdbKeyStore* keys = nullptr;
    std::unique_ptr<adb::AdbStreamHub> hub;
};

std::mutex g_local_mutex;
std::unique_ptr<LocalTcpState> g_local;
std::string g_local_last_error;

// Internal diagnostic counters (NOT public product API).
std::atomic<int> g_local_connect_count{0};
std::atomic<int> g_local_auth_count{0};
std::atomic<int> g_local_online_count{0};
std::atomic<int> g_local_stream_open_count{0};

// NOTE: Local interactive streams are registered in the SHARED g_streams
// registry (same as wireless) so the generic interactiveRead / interactiveWrite
// / interactiveClose dispatch works for Local PTY handles with no Local-specific
// wrappers. The Local context does not keep a second registry (AT.LOCAL-TCP-1A:
// no duplicated stream semantics).

}  // namespace

// com.adb.engine.internal.AdbNative.connectLocalhost(int, String):boolean
JNIEXPORT jboolean JNICALL Java_com_adb_engine_internal_AdbNative_connectLocalhost(
    JNIEnv* env, jobject /* thiz */, jint j_port, jstring j_key_dir) {
    std::string key_dir;
    if (j_key_dir) {
        const char* c = env->GetStringUTFChars(j_key_dir, nullptr);
        if (c) { key_dir = c; env->ReleaseStringUTFChars(j_key_dir, c); }
    }
    LOGI("connectLocalhost port=%d keyDir=%s", j_port, key_dir.c_str());

    std::lock_guard<std::mutex> lock(g_local_mutex);

    // Idempotent: if the shared transport is already ONLINE, reuse it.
    if (g_local && g_local->conn && g_local->conn->isOnline()) {
        LOGI("connectLocalhost: ALREADY ONLINE, reusing shared transport");
        return JNI_TRUE;
    }

    // Teardown any stale (non-online) state before a fresh connect.
    if (g_local) {
        if (g_local->hub) { g_local->hub->shutdown(); g_local->hub.reset(); }
        if (g_local->conn) { delete g_local->conn; g_local->conn = nullptr; }
        if (g_local->keys) { delete g_local->keys; g_local->keys = nullptr; }
        g_local.reset();
    }

    auto state = std::make_unique<LocalTcpState>();
    // NOTE: the key directory is expected to already exist (the app passes its
    // files dir, e.g. /data/data/<pkg>/files). AdbKeyStore::loadOrCreate()
    // generates the keypair when absent (no mkdir here; the dir is the
    // caller's responsibility, matching the Wireless connect() contract).
    state->keys = new adb::AdbKeyStore(key_dir);
    if (!state->keys->loadOrCreate()) {
        g_local_last_error = "key loadOrCreate failed (dir=" + key_dir + ")";
        LOGE("connectLocalhost: %s", g_local_last_error.c_str());
        g_local.reset();
        return JNI_FALSE;
    }
    state->conn = new adb::PlainTcpAdbConnection(&state->transport, state->keys);
    g_local_connect_count.fetch_add(1);
    std::string error;
    bool ok = state->conn->connect("127.0.0.1", j_port, &error);
    if (!ok) {
        g_local_last_error = "PlainTcp: " + error;
        LOGE("connectLocalhost failed: %s", error.c_str());
        delete state->conn; state->conn = nullptr;
        delete state->keys; state->keys = nullptr;
        g_local.reset();
        return JNI_FALSE;
    }
    g_local_last_error.clear();
    g_local_auth_count.fetch_add(1);
    g_local_online_count.fetch_add(1);
    g_local = std::move(state);

    // ONLINE => attach + start the SHARED stream hub.
    g_local->hub = std::make_unique<adb::AdbStreamHub>(g_local->conn);
    std::string hub_err;
    if (!g_local->hub->start(&hub_err)) {
        LOGE("connectLocalhost: hub start failed: %s", hub_err.c_str());
        g_local->hub.reset();
        delete g_local->conn; g_local->conn = nullptr;
        delete g_local->keys; g_local->keys = nullptr;
        g_local.reset();
        return JNI_FALSE;
    }
    LOGI("connectLocalhost: ONLINE + SHARED HUB STARTED");
    return JNI_TRUE;
}

// com.adb.engine.internal.AdbNative.executeShellLocalhost(String):AdbShellResult
JNIEXPORT jobject JNICALL Java_com_adb_engine_internal_AdbNative_executeShellLocalhost(
    JNIEnv* env, jobject /* thiz */, jstring j_command) {
    std::string command;
    if (j_command) {
        const char* c = env->GetStringUTFChars(j_command, nullptr);
        if (c) { command = c; env->ReleaseStringUTFChars(j_command, c); }
    }
    LOGI("executeShellLocalhost: \"%s\"", command.c_str());

    // Copy-out pattern (deadlock avoidance): brief lock, copy hub, release.
    adb::AdbStreamHub* hub = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_local_mutex);
        if (g_local && g_local->conn && g_local->conn->isOnline() &&
            g_local->hub) {
            hub = g_local->hub.get();
        }
    }
    if (!hub) {
        adb::ShellResult r;
        r.status = adb::ShellStatus::TransportError;
        r.has_error = true;
        r.error = "local TCP not connected";
        return NewAdbShellResult(env, r);
    }

    adb::ShellResult result;
    std::string service = "shell,v2,raw:" + command;
    std::string err;
    // Same one-shot semantics as the validated Wireless AdbClient::runShell:
    // an ordinary logical stream on the single RX owner, ack_writes=true
    // (WRTE/OKAY credit), block until CLSE / failure.
    auto stream = hub->openStream(service, /*ack_writes=*/true, 5000, &err);
    if (!stream) {
        LOGE("executeShellLocalhost: OPEN failed: %s", err.c_str());
        result.status = adb::ShellStatus::TransportError;
        result.has_error = true;
        result.error = "failed to OPEN shell service: " + err;
        return NewAdbShellResult(env, result);
    }
    g_local_stream_open_count.fetch_add(1);
    LOGI("executeShellLocalhost: stream open local=%u remote=%u",
         stream->localId(), stream->remoteId());

    // Shell v2 protocol parser (persistent buffer, independent of ADB packets).
    adb::shell_v2::ShellV2Protocol protocol;
    uint8_t buf[4096];
    int consecutive_timeouts = 0;
    for (;;) {
        std::string rerr;
        ssize_t n = stream->read(buf, sizeof(buf), 5000, &rerr);
        if (n < 0) {
            if (stream->failed()) {
                result.status = adb::ShellStatus::TransportError;
                result.has_error = true;
                result.error = stream->closeReason().empty() ? rerr
                                                             : stream->closeReason();
                LOGE("executeShellLocalhost: stream failed: %s",
                     result.error.c_str());
            }
            break;  // remote CLSE / local close: normal termination
        }
        if (n == 0) {
            // Still active, no data within the read window. Keep waiting:
            // one-shot semantics = block until CLSE / failure (v1 parity).
            if (++consecutive_timeouts > 240) {  // ~20 min safeguard
                result.status = adb::ShellStatus::ProtocolError;
                result.has_error = true;
                result.error =
                    "stream terminated without CLSE (no data for 20 min)";
                break;
            }
            continue;
        }
        consecutive_timeouts = 0;

        std::vector<adb::shell_v2::Frame> frames;
        std::string parse_error;
        if (!protocol.feed(buf, static_cast<size_t>(n), &frames, &parse_error)) {
            LOGE("executeShellLocalhost: shell v2 parse error: %s",
                 parse_error.c_str());
            result.status = adb::ShellStatus::ProtocolError;
            result.has_error = true;
            result.error = "protocol error: " + parse_error;
            break;
        }
        for (const auto& frame : frames) {
            switch (static_cast<adb::shell_v2::Id>(frame.id)) {
                case adb::shell_v2::Id::kStdout:
                    result.stdout_data.append(
                        reinterpret_cast<const char*>(frame.payload.data()),
                        frame.payload.size());
                    break;
                case adb::shell_v2::Id::kStderr:
                    result.stderr_data.append(
                        reinterpret_cast<const char*>(frame.payload.data()),
                        frame.payload.size());
                    break;
                case adb::shell_v2::Id::kExit: {
                    int code;
                    if (adb::shell_v2::ShellV2Protocol::exitCode(frame, &code)) {
                        result.exit_code = code;
                    } else {
                        LOGE("executeShellLocalhost: invalid EXIT frame (payload=%zu)",
                             frame.payload.size());
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    // CLSE the stream (skipped when the peer closed first).
    stream->close();

    LOGI("executeShellLocalhost: done exit_code=%d stdout=%zu bytes stderr=%zu bytes",
         result.exit_code, result.stdout_data.size(), result.stderr_data.size());
    return NewAdbShellResult(env, result);
}

// com.adb.engine.internal.AdbNative.interactiveOpenLocalhost(String):long
JNIEXPORT jlong JNICALL Java_com_adb_engine_internal_AdbNative_interactiveOpenLocalhost(
    JNIEnv* env, jobject /* thiz */, jstring j_service) {
    std::string service;
    if (j_service) {
        const char* c = env->GetStringUTFChars(j_service, nullptr);
        if (c) { service = c; env->ReleaseStringUTFChars(j_service, c); }
    }
    LOGI("interactiveOpenLocalhost: \"%s\"", service.c_str());

    adb::AdbStreamHub* hub = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_local_mutex);
        if (g_local && g_local->conn && g_local->conn->isOnline() &&
            g_local->hub) {
            hub = g_local->hub.get();
        }
    }
    if (!hub) {
        LOGE("interactiveOpenLocalhost: local TCP not connected");
        return 0;
    }

    auto stream = std::make_shared<adb::AdbInteractiveStream>();
    std::string err;
    if (!stream->open(hub, service, &err)) {
        LOGE("interactiveOpenLocalhost: open failed: %s", err.c_str());
        return 0;
    }
    g_local_stream_open_count.fetch_add(1);
    // Register in the SHARED g_streams registry so the generic
    // interactiveRead / interactiveWrite / interactiveClose dispatch serves
    // this Local PTY handle (no Local-specific wrappers; AT.LOCAL-TCP-1A).
    jlong handle = 0;
    {
        std::lock_guard<std::mutex> lock(g_streams_mutex);
        handle = g_next_handle++;
        g_streams[handle] = stream;
    }
    LOGI("interactiveOpenLocalhost: handle=%lld service=%s local=%u remote=%u",
         (long long)handle, service.c_str(), stream->localId(),
         stream->remoteId());
    return handle;
}

// com.adb.engine.internal.AdbNative.requestTcpip(int):String
//
// AT.LOCAL-TCP-1C (PRECHECK-B fix): request the adbd `tcpip:<port>` DAEMON
// SERVICE (an ADB OPEN of the "tcpip:<port>" service), NOT an Android shell
// command. This re-arms adbd in legacy TCP mode on `port`.
//
// Success semantics: the daemon may RESTART / switch transport when it accepts
// tcpip:, so the Wireless transport dropping immediately after the OPEN is
// EXPECTED. We therefore treat "the OPEN was accepted" (or an OPEN failure that
// is NOT a transport error) as success — we do NOT require a normal shell exit
// code. The result string is "accepted" / "rejected:<reason>" for diagnostics.
JNIEXPORT jstring JNICALL Java_com_adb_engine_internal_AdbNative_requestTcpip(
    JNIEnv* env, jobject /* thiz */, jint j_port) {
    std::string service = "tcpip:" + std::to_string(j_port);
    LOGI("requestTcpip: OPEN service=\"%s\" (adbd daemon service, not shell)",
         service.c_str());
    adb::AdbClient* client = ClientExisting();
    adb::AdbStreamHub* hub = client ? client->streamHub() : nullptr;
    if (hub == nullptr) {
        LOGE("requestTcpip: wireless connection not ONLINE (no stream hub)");
        return env->NewStringUTF("rejected:no_online_wireless_hub");
    }
    // OPEN the daemon service directly (the same OPEN/OKAY mechanism the hub
    // uses for any logical stream). No shell,v2,raw: framing.
    std::string err;
    auto stream = hub->openStream(service, /*ack_writes=*/true, 5000, &err);
    if (!stream) {
        // OPEN was rejected by the daemon (CLSE) or the transport already
        // dropped. Either way the request was delivered; report the reason.
        LOGW("requestTcpip: OPEN not accepted: %s", err.c_str());
        return env->NewStringUTF(("rejected:" + err).c_str());
    }
    // OPEN accepted (OKAY). Optionally drain a short response / the expected
    // transport drop, then CLSE. The daemon restarts adbd; the Wireless
    // transport will fall off — that is the expected outcome.
    uint8_t buf[1024];
    for (int i = 0; i < 20; ++i) {  // up to ~2s
        std::string rerr;
        ssize_t n = stream->read(buf, sizeof(buf), 100, &rerr);
        if (n < 0) break;  // transport dropped / stream closed: expected
    }
    stream->close();
    LOGI("requestTcpip: OPEN accepted for \"%s\"", service.c_str());
    return env->NewStringUTF("accepted");
}

// com.adb.engine.internal.AdbNative.requestUsb():String
//
// AT.LOCAL-TCP-1D (Gate D): request the adbd `usb:` DAEMON SERVICE (an ADB
// OPEN of the "usb:" service), NOT an Android shell command. This re-arms adbd
// in USB mode, which REMOVES the legacy TCP listener (e.g. :38800) and closes
// the Local TCP transport.
//
// Symmetric to requestTcpip(port): a NARROW product capability for the adbd
// mode switch only (NOT a generic raw-service API).
//
// Success semantics: when adbd accepts `usb:` it switches back to USB mode, so
// the Local TCP transport dropping immediately after the OPEN is EXPECTED. We
// therefore treat "the OPEN was accepted" (or an OPEN failure that is NOT a
// transport error) as success — we do NOT require a normal shell exit code.
// The result string is "accepted" / "rejected:<reason>" for diagnostics.
JNIEXPORT jstring JNICALL Java_com_adb_engine_internal_AdbNative_requestUsb(
    JNIEnv* env, jobject /* thiz */) {
    const std::string service = "usb:";
    LOGI("requestUsb: OPEN service=\"%s\" (adbd daemon service, not shell)",
         service.c_str());
    // The `usb:` request must go over the ACTIVE transport's hub. When the Local
    // TCP transport is active, that is the SEPARATE g_local hub (NOT the Wireless
    // ClientExisting() hub, which is not ONLINE in Local mode). Fall back to the
    // Wireless hub if Local is not active.
    adb::AdbStreamHub* hub = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_local_mutex);
        if (g_local && g_local->conn && g_local->conn->isOnline() && g_local->hub) {
            hub = g_local->hub.get();
            LOGI("requestUsb: using LOCAL hub (Local TCP transport active)");
        }
    }
    if (hub == nullptr) {
        adb::AdbClient* client = ClientExisting();
        hub = client ? client->streamHub() : nullptr;
        if (hub) LOGI("requestUsb: using WIRELESS hub");
    }
    if (hub == nullptr) {
        LOGE("requestUsb: no active ADB hub (connection not ONLINE)");
        return env->NewStringUTF("rejected:no_online_hub");
    }
    // OPEN the daemon service directly (same OPEN/OKAY mechanism the hub uses).
    // No shell,v2,raw: framing.
    std::string err;
    auto stream = hub->openStream(service, /*ack_writes=*/true, 5000, &err);
    if (!stream) {
        // OPEN was rejected (CLSE) or the transport already dropped. Either way
        // the request was delivered; report the reason.
        LOGW("requestUsb: OPEN not accepted: %s", err.c_str());
        return env->NewStringUTF(("rejected:" + err).c_str());
    }
    // OPEN accepted (OKAY). adbd switches to USB mode; the Local TCP transport
    // will fall off — that is the expected outcome. Drain a short response /
    // the expected transport drop, then CLSE.
    uint8_t buf[1024];
    for (int i = 0; i < 20; ++i) {  // up to ~2s
        std::string rerr;
        ssize_t n = stream->read(buf, sizeof(buf), 100, &rerr);
        if (n < 0) break;  // transport dropped / stream closed: expected
    }
    stream->close();
    LOGI("requestUsb: OPEN accepted for \"%s\" (adbd -> USB mode, listener removed)",
         service.c_str());
    return env->NewStringUTF("accepted");
}

// com.adb.engine.internal.AdbNative.disconnectLocalhost():void
JNIEXPORT void JNICALL Java_com_adb_engine_internal_AdbNative_disconnectLocalhost(
    JNIEnv* /* env */, jobject /* thiz */) {
    LOGI("disconnectLocalhost");
    std::lock_guard<std::mutex> lock(g_local_mutex);
    if (g_local) {
        if (g_local->hub) {
            g_local->hub->shutdown();
            g_local->hub.reset();
        }
        if (g_local->conn) {
            g_local->conn->disconnect();
            delete g_local->conn;
        }
        if (g_local->keys) delete g_local->keys;
        g_local.reset();
    }
    LOGI("disconnectLocalhost: done (transport teardown)");
}

// com.adb.engine.internal.AdbNative.localhostConnected():boolean
JNIEXPORT jboolean JNICALL Java_com_adb_engine_internal_AdbNative_localhostConnected(
    JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_local_mutex);
    return (g_local && g_local->conn && g_local->conn->isOnline())
               ? JNI_TRUE : JNI_FALSE;
}

// com.adb.engine.internal.AdbNative.localTcpDebugCounters():String
// Internal diagnostic (NOT public product API).
JNIEXPORT jstring JNICALL Java_com_adb_engine_internal_AdbNative_localTcpDebugCounters(
    JNIEnv* env, jobject /* thiz */) {
    std::string out = "connect=" + std::to_string(g_local_connect_count.load()) +
                      ";auth=" + std::to_string(g_local_auth_count.load()) +
                      ";online=" + std::to_string(g_local_online_count.load()) +
                      ";streamOpen=" + std::to_string(g_local_stream_open_count.load());
    LOGI("localTcpDebugCounters: %s", out.c_str());
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
