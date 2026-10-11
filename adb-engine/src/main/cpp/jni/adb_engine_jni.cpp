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
#include "adb/pairing/pairing_client.h"

#define LOG_TAG "AdbEngineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
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
// interactive), reuse the client already bound by the last connect. If none
// exists, fall back to a default dir (test-only; production always connects
// first).
adb::AdbClient& ClientExisting() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_client) {
        g_client = std::make_unique<adb::AdbClient>("/data/local/tmp/adbkeys");
        g_client_key_dir = "/data/local/tmp/adbkeys";
    }
    return *g_client;
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
        adb::AdbClient& client = ClientExisting();
    client.disconnect();
}

// com.adb.engine.internal.AdbNative.connectionState():int
JNIEXPORT jint JNICALL Java_com_adb_engine_internal_AdbNative_connectionState(
    JNIEnv* /* env */, jobject /* thiz */) {
        adb::AdbClient& client = ClientExisting();
    return ConnectionStateOrdinal(client.connection_state());
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
        adb::AdbClient& client = ClientExisting();
    // timeout_ms is forwarded for interface parity; the ADB transport does not
    // implement a real exec timeout yet (same as V2 Core v0.1 semantics).
    (void)j_timeout_ms;
    const adb::ShellResult r = client.shell(command);
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
    adb::AdbClient& client = ClientExisting();
    adb::AdbStreamHub* hub = client.streamHub();
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

}  // extern "C"
