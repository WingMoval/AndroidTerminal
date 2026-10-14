// AT.TERM-0 — JNI bridge for the shellless PTY validation probe (VALIDATION ONLY).

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cstddef>
#include <sys/types.h>
#include <string>
#include <vector>

#define LOG_TAG "AtShelllessPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// namespace atsl (from shellless_pty.cpp). Declared OUTSIDE extern "C" so the
// C++ name mangling matches the definitions in shellless_pty.cpp.
namespace atsl {
int create_shellless_pty(int* out_master, pid_t* out_pid, char* slave_path,
                         size_t slave_path_len, int rows, int cols);
int shellless_write(int fd, const uint8_t* data, size_t len);
ssize_t shellless_read(int fd, uint8_t* buf, size_t len, int timeout_ms);
int shellless_set_winsize(int fd, int rows, int cols);
int shellless_get_winsize(int fd, int* rows, int* cols);
int close_shellless(int fd, pid_t pid, int timeout_ms);
int shellless_waitpid_nonblock(pid_t pid, int* status);
}

extern "C" {

// Start a shellless PTY (child runs the in-process probe, NO exec/sh).
// Returns String[] {slavePath, pid, masterFd} or null on failure.
JNIEXPORT jobjectArray JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeStart(
    JNIEnv* env, jclass, jint rows, jint cols) {
    char slave_path[128];
    int master = -1;
    pid_t pid = -1;
    if (atsl::create_shellless_pty(&master, &pid, slave_path, sizeof(slave_path),
                                   rows, cols) != 0) {
        return nullptr;
    }
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    jobjectArray out = env->NewObjectArray(3, strCls, nullptr);
    if (!out) return nullptr;
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(slave_path));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(std::to_string((int)pid).c_str()));
    env->SetObjectArrayElement(out, 2, env->NewStringUTF(std::to_string(master).c_str()));
    return out;
}

// Write bytes to the PTY master. Returns bytes written or -1.
JNIEXPORT jint JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeWrite(
    JNIEnv* env, jclass, jint fd, jbyteArray data) {
    if (!data) return -1;
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf((size_t)len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    return (jint)atsl::shellless_write(fd, buf.data(), buf.size());
}

// Read available bytes with timeout. Returns bytes read (0=timeout, -1=error/EOF).
JNIEXPORT jint JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeRead(
    JNIEnv* env, jclass, jint fd, jbyteArray buf, jint timeout_ms) {
    if (!buf) return -1;
    jsize cap = env->GetArrayLength(buf);
    std::vector<uint8_t> tmp((size_t)cap);
    ssize_t n = atsl::shellless_read(fd, tmp.data(), tmp.size(), timeout_ms);
    if (n <= 0) return (jint)n;
    env->SetByteArrayRegion(buf, 0, (jsize)n,
                            reinterpret_cast<const jbyte*>(tmp.data()));
    return (jint)n;
}

// Set winsize. Returns 0 on success.
JNIEXPORT jint JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeSetWinsize(
    JNIEnv*, jclass, jint fd, jint rows, jint cols) {
    return (jint)atsl::shellless_set_winsize(fd, rows, cols);
}

// Get winsize. Returns int[] {rows, cols} or null.
JNIEXPORT jintArray JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeGetWinsize(
    JNIEnv* env, jclass, jint fd) {
    int rows = 0, cols = 0;
    if (atsl::shellless_get_winsize(fd, &rows, &cols) != 0) return nullptr;
    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    jint vals[2] = {(jint)rows, (jint)cols};
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

// Non-blocking waitpid. Returns 1 if exited (status filled), 0 if running, -1 err.
JNIEXPORT jint JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeWaitpidNonblock(
    JNIEnv* env, jclass, jint pid, jintArray status) {
    int st = 0;
    int rc = atsl::shellless_waitpid_nonblock((pid_t)pid, &st);
    if (rc == 1 && status) {
        jint s[1] = {(jint)st};
        env->SetIntArrayRegion(status, 0, 1, s);
    }
    return (jint)rc;
}

// Close master + signal + reap. Returns child wait status or -1.
JNIEXPORT jint JNICALL
Java_com_androidterminal_shelllesshost_ShelllessPtyNative_nativeClose(
    JNIEnv*, jclass, jint fd, jint pid, jint timeout_ms) {
    return (jint)atsl::close_shellless(fd, (pid_t)pid, timeout_ms);
}

}  // extern "C"
