// :at-backend-local — JNI bridge for the Local PTY provider.
//
// AT.PERSIST-0 — adapted from V2 atsh local_pty_jni.cpp. The native PTY core
// is migrated verbatim (local_pty.cpp); this JNI layer adapts the V2
// com.androidterminal.atsh.pty.PtyNative symbols to the V3 Local Backend
// package (com.at.backend.local). JNI namespace adaptation only — no pty
// behavior change.

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "local_pty.h"

#define LOG_TAG "ATLocalPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Start a PTY shell. Returns String[] {slavePath, pid, masterFd} or null.
JNIEXPORT jobjectArray JNICALL
Java_com_at_backend_local_LocalPtyNative_nativeStartPty(JNIEnv* env, jclass) {
    char slave_path[128];
    int master = -1;
    pid_t pid = -1;
    if (atsh::create_pty_shell(&master, &pid, slave_path,
                               sizeof(slave_path)) != 0) {
        return nullptr;
    }
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    jobjectArray out = env->NewObjectArray(3, strCls, nullptr);
    if (!out) return nullptr;
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(slave_path));
    env->SetObjectArrayElement(out, 1,
                               env->NewStringUTF(std::to_string(pid).c_str()));
    env->SetObjectArrayElement(out, 2,
                               env->NewStringUTF(std::to_string(master).c_str()));
    return out;
}

// Write bytes to the PTY master. Returns bytes written or -1.
JNIEXPORT jint JNICALL
Java_com_at_backend_local_LocalPtyNative_nativeWrite(JNIEnv* env, jclass,
                                                     jint fd, jbyteArray data) {
    if (!data) return -1;
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len,
                            reinterpret_cast<jbyte*>(buf.data()));
    return static_cast<jint>(atsh::pty_write(fd, buf.data(), buf.size()));
}

// Read available bytes with timeout. Returns bytes read (0=timeout, -1=error).
JNIEXPORT jint JNICALL
Java_com_at_backend_local_LocalPtyNative_nativeRead(JNIEnv* env, jclass,
                                                    jint fd, jbyteArray buf,
                                                    jint timeout_ms) {
    if (!buf) return -1;
    jsize cap = env->GetArrayLength(buf);
    std::vector<uint8_t> tmp(static_cast<size_t>(cap));
    ssize_t n = atsh::pty_read(fd, tmp.data(), tmp.size(), timeout_ms);
    if (n <= 0) return static_cast<jint>(n);
    env->SetByteArrayRegion(buf, 0, static_cast<jsize>(n),
                            reinterpret_cast<const jbyte*>(tmp.data()));
    return static_cast<jint>(n);
}

// Get winsize. Returns int[] {rows, cols} or null.
JNIEXPORT jintArray JNICALL
Java_com_at_backend_local_LocalPtyNative_nativeGetWinsize(JNIEnv* env, jclass,
                                                          jint fd) {
    int rows = 0, cols = 0;
    if (atsh::pty_get_winsize(fd, &rows, &cols) != 0) return nullptr;
    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    jint vals[2] = {static_cast<jint>(rows), static_cast<jint>(cols)};
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

// Set winsize. Returns 0 on success.
JNIEXPORT jint JNICALL
Java_com_at_backend_local_LocalPtyNative_nativeSetWinsize(JNIEnv*, jclass,
                                                          jint fd, jint rows,
                                                          jint cols) {
    return static_cast<jint>(atsh::pty_set_winsize(fd, rows, cols));
}

// Close master + signal + reap child. Returns 0 on success.
JNIEXPORT jint JNICALL
Java_com_at_backend_local_LocalPtyNative_nativeClosePty(JNIEnv*, jclass,
                                                        jint fd, jint pid,
                                                        jint timeout_ms) {
    return static_cast<jint>(atsh::close_pty(fd, pid, timeout_ms));
}

}  // extern "C"