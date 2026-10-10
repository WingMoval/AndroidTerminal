// ADB Engine — interactive stream primitive (ADB-domain).
//
// Migrated from V2 AdbInteractiveStream. This is the ADB interactive stream
// primitive only: open / writeStdin / read / resize / closeStdin / close.
// It is NOT a TerminalSession, NOT an ADB Backend, NOT a Terminal Emulator.
// (Canonical V1 §7.2 + AT.V3-1 §5 Batch D / §8.)

package com.adb.engine

import com.adb.engine.internal.AdbNative
import java.nio.charset.StandardCharsets

/**
 * A persistent ADB interactive stream primitive.
 *
 * Owns one native `adb::AdbInteractiveStream` handle. Stream close does NOT
 * close the ADB connection (connection reuse, ADB.I0 verified).
 */
class AdbInteractiveStream internal constructor(private var handle: Long) {

    val isOpen: Boolean get() = handle > 0

    /** Write UTF-8 bytes to stdin. Returns bytes written or -1. */
    fun write(s: String): Int = write(s.toByteArray(StandardCharsets.UTF_8))

    fun write(data: ByteArray): Int = AdbNative.interactiveWrite(handle, data)

    /** Read up to [buf] bytes; >0 bytes, 0 timeout, -1 error/close. */
    fun read(buf: ByteArray, timeoutMs: Int): Int =
        AdbNative.interactiveRead(handle, buf, timeoutMs)

    fun resize(rows: Int, cols: Int): Boolean =
        AdbNative.interactiveResize(handle, rows, cols)

    fun closeStdin(): Boolean = AdbNative.interactiveCloseStdin(handle)

    /** CLSE the stream (does not close the ADB connection). */
    fun close() {
        if (handle > 0) {
            AdbNative.interactiveClose(handle)
            handle = 0
        }
    }
}

/**
 * Structured result of a one-shot ADB pairing attempt (ADB-domain).
 */
data class AdbPairingResult(
    val success: Boolean,
    val deviceGuid: String,
    val error: String,
)