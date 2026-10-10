// ADB Engine — one-shot shell result (ADB transport domain).
//
// Canonical V1 §7.2: ADB Engine owns one-shot shell transport. This result is
// ADB-domain only: verbatim device stdout/stderr + exit code + a transport
// status. It deliberately does NOT carry Canonical AT Engine result semantics
// (outcome vocabulary is AT domain and lives in :at-engine later).

package com.adb.engine

/**
 * Transport-level classification of a one-shot ADB shell execution.
 *
 * CRITICAL: a non-zero *device* exit code is NOT a failure of this layer.
 * `COMPLETED` means "the ADB execution itself ran to completion", which
 * includes commands that exit non-zero. Transport/protocol problems are
 * reported separately and MUST NOT be mixed into [stderr].
 */
enum class AdbShellStatus {
    COMPLETED,        // stream completed (exit code may be anything)
    TRANSPORT_ERROR,  // connect/read/write/OPEN failure, EOF
    PROTOCOL_ERROR,   // malformed frame, bad magic/length, unterminated stream
}

/**
 * Result of a one-shot ADB shell execution (ADB Engine domain).
 *
 * Mirrors native `adb::ShellResult`. Three strictly separated channels:
 *  - [stdout] / [stderr] = verbatim device process output
 *  - [status] / [hasError] / [error] = ADB transport / protocol layer
 */
data class AdbShellResult(
    val status: AdbShellStatus,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val hasError: Boolean,
    val error: String?,
) {
    /**
     * JNI marshalling constructor. The native layer transfers [AdbShellStatus]
     * as its int ordinal, so the generated JVM signature is
     * `(ILjava/lang/String;Ljava/lang/String;IZLjava/lang/String;)V`.
     * Without this constructor the JNI `GetMethodID` lookup fails at runtime
     * with NoSuchMethodError (the V2 AT.R2V lesson).
     */
    constructor(
        statusOrdinal: Int,
        stdout: String,
        stderr: String,
        exitCode: Int,
        hasError: Boolean,
        error: String?,
    ) : this(
        status = when (statusOrdinal) {
            0 -> AdbShellStatus.COMPLETED
            1 -> AdbShellStatus.TRANSPORT_ERROR
            else -> AdbShellStatus.PROTOCOL_ERROR
        },
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        hasError = hasError,
        error = error,
    )

    /** True when the ADB execution path itself failed (not merely a non-zero exit). */
    val transportFailed: Boolean get() = status != AdbShellStatus.COMPLETED
}