// AT.CLI-0 鈥?Product CLI Bridge server (native UDS).
//
// Binds an AF_UNIX SOCK_STREAM socket in the app's private files dir, accepts
// connections, verifies peer UID == app UID (SO_PEERCRED), and speaks the
// length-prefixed Product CLI Bridge protocol. ATEngine delegation (one-shot /
// interactive) is done by a Kotlin CliBridgeHandler:
//   - native reads a frame -> calls handler.onFrame / onInteractiveOpen
//   - Kotlin pushes output back via nativeSendFrame(fd, type, payload)
//     (safe to call from the ADB RX thread; writes are serialized per fd).
//
// App-layer composition only. Not an AT/ADB Engine change. Present in both
// debug and release builds.

#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>

#define MAX_FRAME (256u * 1024u)

enum {
    T_HELLO = 1, T_HELLO_ACK = 2, T_ONESHOT = 3, T_OUT = 4, T_ERR = 5,
    T_EXIT = 6, T_INTERACTIVE = 7, T_STDIN = 8, T_RESIZE = 9, T_CLOSE = 10,
    T_ERROR = 11,
};

struct Bridge {
    jobject handler;       // global ref
    int listen_fd;
    volatile int running;
    pthread_t thread;
    jmethodID onClientAuth;
    jmethodID onOneShot;          // (int fd, byte[] cmd)
    jmethodID onInteractiveOpen;  // (int fd, int rows, int cols) -> boolean
    jmethodID onInteractiveData;  // (int fd, int type, byte[] payload)
    jmethodID onInteractiveClose; // (int fd)
    jmethodID pushError;          // (int fd, String msg)
};

static Bridge* g_bridge = nullptr;
static JavaVM* g_jvm = nullptr;

#define LOGI_ATCLI(...) __android_log_print(ANDROID_LOG_INFO, "AdbCliBridge", __VA_ARGS__)
#define LOGE_ATCLI(...) __android_log_print(ANDROID_LOG_ERROR, "AdbCliBridge", __VA_ARGS__)

// ---- frame IO (per-fd, serialized by a mutex per active fd) ----------------
#include <map>
#include <mutex>
static std::map<int, std::unique_ptr<std::mutex>> g_fd_locks;
static std::mutex g_locks_guard;

static std::mutex& fd_lock(int fd) {
    std::lock_guard<std::mutex> g(g_locks_guard);
    auto it = g_fd_locks.find(fd);
    if (it == g_fd_locks.end()) {
        g_fd_locks[fd] = std::unique_ptr<std::mutex>(new std::mutex());
        it = g_fd_locks.find(fd);
    }
    return *it->second;
}

static void release_fd_lock(int fd) {
    std::lock_guard<std::mutex> g(g_locks_guard);
    g_fd_locks.erase(fd);
}

static int write_all(int fd, const void* buf, size_t n) {
    const uint8_t* p = (const uint8_t*)buf;
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, p + off, n - off);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        off += (size_t)w;
    }
    return 0;
}
static int read_all(int fd, void* buf, size_t n) {
    uint8_t* p = (uint8_t*)buf;
    size_t off = 0;
    while (off < n) {
        ssize_t r = read(fd, p + off, n - off);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        if (r == 0) return -1;
        off += (size_t)r;
    }
    return 0;
}

// Public: send a frame to a client fd (thread-safe, used by Kotlin push).
extern "C"
JNIEXPORT void JNICALL
Java_com_androidterminal_app_cli_AdbCliBridgeServer_nativeSendFrame(
    JNIEnv* env, jobject, jint fd, jint type, jbyteArray payload) {
    uint32_t len = payload ? (uint32_t)env->GetArrayLength(payload) : 0;
    jbyte* pb = nullptr;
    if (payload) pb = env->GetByteArrayElements(payload, nullptr);
    {
        std::lock_guard<std::mutex> lk(fd_lock((int)fd));
        uint8_t hdr[5];
        hdr[0] = (uint8_t)type;
        hdr[1] = (uint8_t)(len & 0xff);
        hdr[2] = (uint8_t)((len >> 8) & 0xff);
        hdr[3] = (uint8_t)((len >> 16) & 0xff);
        hdr[4] = (uint8_t)((len >> 24) & 0xff);
        if (write_all((int)fd, hdr, 5) == 0 && len > 0) {
            write_all((int)fd, pb, len);
        }
    }
    if (pb) env->ReleaseByteArrayElements(payload, pb, JNI_ABORT);
}

static int recv_frame(int fd, uint8_t* type, uint8_t* payload, uint32_t* out_len) {
    uint8_t hdr[5];
    if (read_all(fd, hdr, 5) < 0) return -1;
    *type = hdr[0];
    *out_len = (uint32_t)hdr[1] | ((uint32_t)hdr[2] << 8) | ((uint32_t)hdr[3] << 16) | ((uint32_t)hdr[4] << 24);
    if (*out_len > MAX_FRAME) return -1;
    if (*out_len > 0 && read_all(fd, payload, *out_len) < 0) return -1;
    return 0;
}

static int get_peer_uid(int fd) {
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) == 0) return (int)cred.uid;
    return -1;
}

static void ensure_env(JNIEnv** out_env, bool* attached) {
    void* penv = nullptr;
    if (g_jvm->GetEnv(&penv, JNI_VERSION_1_6) == JNI_OK) {
        *out_env = (JNIEnv*)penv;
        *attached = false;
        return;
    }
    JNIEnv* penv2 = nullptr;
    if (g_jvm->AttachCurrentThread(&penv2, nullptr) == JNI_OK) {
        *out_env = penv2;
        *attached = true;
    }
}

static void* client_thread(void* arg) {
    int fd = (int)(intptr_t)arg;
    JNIEnv* env = nullptr; bool attached = false;
    ensure_env(&env, &attached);
    if (!env) { close(fd); release_fd_lock(fd); return nullptr; }

    int peerUid = get_peer_uid(fd);
    jboolean authed = env->CallBooleanMethod(
        g_bridge->handler, g_bridge->onClientAuth, (jint)fd, (jint)peerUid);
    LOGI_ATCLI("client fd=%d peerUid=%d authed=%d", fd, peerUid, (int)authed);
    if (!authed) {
        jstring m = env->NewStringUTF("peer uid mismatch");
        env->CallVoidMethod(g_bridge->handler, g_bridge->pushError, (jint)fd, m);
        env->DeleteLocalRef(m);
        close(fd); release_fd_lock(fd);
        if (attached) g_jvm->DetachCurrentThread();
        return nullptr;
    }

    uint8_t type; uint32_t len;
    uint8_t* payload = (uint8_t*)malloc(MAX_FRAME);
    if (!payload) { close(fd); release_fd_lock(fd); return nullptr; }
    if (recv_frame(fd, &type, payload, &len) < 0 || type != T_HELLO || len < 1) {
        jstring m = env->NewStringUTF("expected hello");
        env->CallVoidMethod(g_bridge->handler, g_bridge->pushError, (jint)fd, m);
        env->DeleteLocalRef(m);
        close(fd); release_fd_lock(fd);
        free(payload);
        if (attached) g_jvm->DetachCurrentThread();
        return nullptr;
    }
    // Reply HELLO_ACK (echo version).
    LOGI_ATCLI("client fd=%d got HELLO v=%d, sending HELLO_ACK", fd, (int)payload[0]);
    {
        std::lock_guard<std::mutex> lk(fd_lock(fd));
        uint8_t hdr[5] = {(uint8_t)T_HELLO_ACK, 1, 0, 0, 0};
        write_all(fd, hdr, 5);
        write_all(fd, &payload[0], 1);
    }

    // Serve loop.
    for (;;) {
        if (recv_frame(fd, &type, payload, &len) < 0) break;
        switch (type) {
            case T_ONESHOT: {
                jbyteArray ja = env->NewByteArray(len);
                if (len) env->SetByteArrayRegion(ja, 0, len, (const jbyte*)payload);
                env->CallVoidMethod(g_bridge->handler, g_bridge->onOneShot, (jint)fd, ja);
                env->DeleteLocalRef(ja);
                break;
            }
            case T_INTERACTIVE: {
                int rows = (int)((uint32_t)payload[0] | ((uint32_t)payload[1] << 8) |
                                 ((uint32_t)payload[2] << 16) | ((uint32_t)payload[3] << 24));
                int cols = (int)((uint32_t)payload[4] | ((uint32_t)payload[5] << 8) |
                                 ((uint32_t)payload[6] << 16) | ((uint32_t)payload[7] << 24));
                jboolean ok = env->CallBooleanMethod(
                    g_bridge->handler, g_bridge->onInteractiveOpen, (jint)fd, (jint)rows, (jint)cols);
                if (!ok) break;
                for (;;) {
                    uint8_t t2; uint32_t l2;
                    // Reuse the heap payload buffer for the inner relay frames.
                    if (recv_frame(fd, &t2, payload, &l2) < 0) break;
                    if (t2 == T_STDIN || t2 == T_RESIZE) {
                        jbyteArray ja2 = env->NewByteArray(l2);
                        if (l2) env->SetByteArrayRegion(ja2, 0, l2, (const jbyte*)payload);
                        env->CallVoidMethod(g_bridge->handler, g_bridge->onInteractiveData,
                                               (jint)fd, (jint)t2, ja2);
                        env->DeleteLocalRef(ja2);
                    } else if (t2 == T_CLOSE) {
                        env->CallVoidMethod(g_bridge->handler, g_bridge->onInteractiveClose, (jint)fd);
                        break;
                    } else break;
                }
                env->CallVoidMethod(g_bridge->handler, g_bridge->onInteractiveClose, (jint)fd);
                break;
            }
            case T_CLOSE: break;
            default:
                jstring m = env->NewStringUTF("unexpected frame");
                env->CallVoidMethod(g_bridge->handler, g_bridge->pushError, (jint)fd, m);
                env->DeleteLocalRef(m);
                break;
        }
    }
    close(fd);
    release_fd_lock(fd);
    free(payload);
    if (attached) g_jvm->DetachCurrentThread();
    return nullptr;
}

static void* accept_thread(void* arg) {
    (void)arg;
    LOGI_ATCLI("accept_thread started");
    while (g_bridge->running) {
        int cfd = accept(g_bridge->listen_fd, nullptr, nullptr);
        if (cfd < 0) { if (!g_bridge->running) break; continue; }
        LOGI_ATCLI("accepted client fd=%d", cfd);
        pthread_t t;
        if (pthread_create(&t, nullptr, client_thread, (void*)(intptr_t)cfd) == 0) {
            pthread_detach(t);
        } else close(cfd);
    }
    LOGI_ATCLI("accept_thread exited");
    return nullptr;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_androidterminal_app_cli_AdbCliBridgeServer_nativeStart(JNIEnv* env, jobject, jstring path, jobject handler) {
    if (g_bridge) return -1;
    if (!path) return -1;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return -1;

    unlink(p);
    int lfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (lfd < 0) { env->ReleaseStringUTFChars(path, p); return -1; }
    struct sockaddr_un a; memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, p, sizeof(a.sun_path) - 1);
    if (bind(lfd, (struct sockaddr*)&a, sizeof(a)) < 0 || listen(lfd, 8) < 0) {
        close(lfd); env->ReleaseStringUTFChars(path, p); return -1;
    }
    env->ReleaseStringUTFChars(path, p);

    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    g_jvm = vm;

    Bridge* b = (Bridge*)calloc(1, sizeof(Bridge));
    b->listen_fd = lfd;
    b->running = 1;
    b->handler = env->NewGlobalRef(handler);
    jclass cls = env->GetObjectClass(handler);
    b->onClientAuth = env->GetMethodID(cls, "onClientAuth", "(II)Z");
    b->onOneShot = env->GetMethodID(cls, "onOneShot", "(I[B)V");
    b->onInteractiveOpen = env->GetMethodID(cls, "onInteractiveOpen", "(III)Z");
    b->onInteractiveData = env->GetMethodID(cls, "onInteractiveData", "(IIB[V)V");
    b->onInteractiveClose = env->GetMethodID(cls, "onInteractiveClose", "(I)V");
    b->pushError = env->GetMethodID(cls, "pushError", "(ILjava/lang/String;)V");
    g_bridge = b;

    if (pthread_create(&b->thread, nullptr, accept_thread, nullptr) != 0) {
        env->DeleteGlobalRef(b->handler); free(b); g_bridge = nullptr; close(lfd);
        LOGE_ATCLI("nativeStart: pthread_create failed");
        return -1;
    }
    LOGI_ATCLI("nativeStart OK listen_fd=%d path=%s", lfd, p);
    return lfd;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_androidterminal_app_cli_AdbCliBridgeServer_nativeStop(JNIEnv* env, jobject) {
    if (!g_bridge) return;
    g_bridge->running = 0;
    close(g_bridge->listen_fd);
    env->DeleteGlobalRef(g_bridge->handler);
    Bridge* old = g_bridge;
    g_bridge = nullptr;
    (void)old;
}






