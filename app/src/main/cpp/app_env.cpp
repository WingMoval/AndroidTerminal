// :app — minimal native helpers for CLI frontend provisioning + debug UDS test.
//
// AT.CLI-CONSUMER-0: the bridge server (cli_bridge_server.cpp) moved to :at-cli
// (atcli_env). :app keeps only:
//   1) setenv — Android Java has no public setenv(); the Local PTY child
//      inherits the app process `environ`, so setting PATH / AT_CLI_BRIDGE_DIR
//      here makes them visible to the /system/bin/sh the Local backend spawns
//      (used by CliFrontendProvisioner).
//   2) UDS connect/read/write/timeout/close — used by the debug-only CliProbe
//      (instrumentation plays the shell-hosted `adb` CLI client role).
//
// App-layer composition only (not an AT Engine change).

#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

extern "C"
JNIEXPORT void JNICALL
Java_com_androidterminal_app_cli_NativeEnv_setEnv(JNIEnv* env, jclass, jstring key, jstring value) {
    if (!key || !value) return;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (k && v) setenv(k, v, 1);
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

// Connect a new AF_UNIX SOCK_STREAM socket to [path] (a UDS file path).
// Returns the connected fd (>=0) or -1 on failure. Used by the instrumented
// test to play the role of the shell-hosted `adb` CLI client.
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidterminal_app_cli_NativeEnv_connectUds(JNIEnv* env, jclass, jstring path) {
    if (!path) return -1;
    const char* p = env->GetStringUTFChars(path, nullptr);
    jint rc = -1;
    if (p) {
        int c = socket(AF_UNIX, SOCK_STREAM, 0);
        if (c >= 0) {
            struct sockaddr_un a;
            memset(&a, 0, sizeof(a));
            a.sun_family = AF_UNIX;
            strncpy(a.sun_path, p, sizeof(a.sun_path) - 1);
            if (connect(c, (struct sockaddr*)&a, sizeof(a)) == 0) {
                rc = c;
            } else {
                close(c);
            }
        }
        env->ReleaseStringUTFChars(path, p);
    }
    return rc;
}

// Read up to buf.length bytes from fd into buf; returns bytes read or -1.
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidterminal_app_cli_NativeEnv_udsRead(JNIEnv* env, jclass, jint fd, jbyteArray buf) {
    jsize len = env->GetArrayLength(buf);
    jbyte* b = env->GetByteArrayElements(buf, nullptr);
    int n = (int)read((int)fd, b, (size_t)len);
    env->ReleaseByteArrayElements(buf, b, JNI_ABORT);
    return n;
}

// Write buf to fd; returns bytes written or -1.
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidterminal_app_cli_NativeEnv_udsWrite(JNIEnv* env, jclass, jint fd, jbyteArray buf) {
    jsize len = env->GetArrayLength(buf);
    jbyte* b = env->GetByteArrayElements(buf, nullptr);
    int off = 0;
    while (off < len) {
        int w = (int)write((int)fd, b + off, len - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            env->ReleaseByteArrayElements(buf, b, JNI_ABORT);
            return -1;
        }
        off += w;
    }
    env->ReleaseByteArrayElements(buf, b, JNI_ABORT);
    return off;
}

// Set a socket recv timeout (ms) so reads cannot block forever.
extern "C"
JNIEXPORT void JNICALL
Java_com_androidterminal_app_cli_NativeEnv_udsSetTimeout(JNIEnv*, jclass, jint fd, jint ms) {
    struct timeval tv;
    tv.tv_sec = ms / 1000;
    tv.tv_usec = (ms % 1000) * 1000;
    setsockopt((int)fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

// Close a raw fd.
extern "C"
JNIEXPORT void JNICALL
Java_com_androidterminal_app_cli_NativeEnv_udsClose(JNIEnv*, jclass, jint fd) {
    close((int)fd);
}
