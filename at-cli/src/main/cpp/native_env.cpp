// AT.CLI-0 — minimal JNI helpers for the Product CLI Bridge.
//
// 1) setEnv: Android Java has no public setenv(); the Local PTY child inherits
//    the app process `environ`, so setting PATH / AT_CLI_BRIDGE_DIR here makes
//    them visible to the /system/bin/sh the Local backend spawns.
// 2) createUdsListen: android.net.LocalServerSocket only binds /dev/socket
//    (String ctor) or an existing fd. We create an AF_UNIX listening socket in
//    the app's private files dir and hand the fd to LocalServerSocket(fd).
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
Java_com_at_cli_NativeEnv_setEnv(JNIEnv* env, jclass, jstring key, jstring value) {
    if (!key || !value) return;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (k && v) setenv(k, v, 1);
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

// Create an AF_UNIX SOCK_STREAM listening socket bound to [path].
// Returns the fd (>=0) on success, or -1 on failure.
extern "C"
JNIEXPORT jint JNICALL
Java_com_at_cli_NativeEnv_createUdsListen(JNIEnv* env, jclass, jstring path) {
    if (!path) return -1;
    const char* p = env->GetStringUTFChars(path, nullptr);
    jint rc = -1;
    if (p) {
        unlink(p);
        int s = socket(AF_UNIX, SOCK_STREAM, 0);
        if (s >= 0) {
            struct sockaddr_un a;
            memset(&a, 0, sizeof(a));
            a.sun_family = AF_UNIX;
            strncpy(a.sun_path, p, sizeof(a.sun_path) - 1);
            if (bind(s, (struct sockaddr*)&a, sizeof(a)) == 0 &&
                listen(s, 8) == 0) {
                rc = s;
            } else {
                close(s);
                rc = -1;
            }
        }
        env->ReleaseStringUTFChars(path, p);
    }
    return rc;
}

// Return the peer UID of a connected AF_UNIX socket [fd] via SO_PEERCRED,
// or -1 on failure. Used for same-UID peer authentication.
extern "C"
JNIEXPORT jint JNICALL
Java_com_at_cli_NativeEnv_peerUid(JNIEnv*, jclass, jint fd) {
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) == 0) {
        return (jint)cred.uid;
    }
    return -1;
}

// Connect a new AF_UNIX SOCK_STREAM socket to [path] (a UDS file path).
// Returns the connected fd (>=0) or -1 on failure. Used by the instrumented
// test to play the role of the shell-hosted `adb` CLI client (proven to work
// on HONOR Android 15, unlike android.net.LocalSocket for arbitrary paths).
extern "C"
JNIEXPORT jint JNICALL
Java_com_at_cli_NativeEnv_connectUds(JNIEnv* env, jclass, jstring path) {
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
// A non-blocking-ish read: uses a 15s SO_RCVTIMEO set once per fd (best effort).
extern "C"
JNIEXPORT jint JNICALL
Java_com_at_cli_NativeEnv_udsRead(JNIEnv* env, jclass, jint fd, jbyteArray buf) {
    jsize len = env->GetArrayLength(buf);
    jbyte* b = env->GetByteArrayElements(buf, nullptr);
    int n = (int)read((int)fd, b, (size_t)len);
    env->ReleaseByteArrayElements(buf, b, JNI_ABORT);
    return n;
}

// Write buf to fd; returns bytes written or -1.
extern "C"
JNIEXPORT jint JNICALL
Java_com_at_cli_NativeEnv_udsWrite(JNIEnv* env, jclass, jint fd, jbyteArray buf) {
    jsize len = env->GetArrayLength(buf);
    jbyte* b = env->GetByteArrayElements(buf, nullptr);
    int off = 0;
    while (off < len) {
        int w = (int)write((int)fd, b + off, (size_t)(len - off));
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
Java_com_at_cli_NativeEnv_udsSetTimeout(JNIEnv*, jclass, jint fd, jint ms) {
    struct timeval tv;
    tv.tv_sec = ms / 1000;
    tv.tv_usec = (ms % 1000) * 1000;
    setsockopt((int)fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

// Close a raw fd.
extern "C"
JNIEXPORT void JNICALL
Java_com_at_cli_NativeEnv_udsClose(JNIEnv*, jclass, jint fd) {
    close((int)fd);
}
