// AT.CLI-0 — Product CLI Bridge protocol (length-prefixed binary frames).
//
// App-internal Product IPC between the shell-hosted `adb` CLI frontend (native,
// running as the app UID) and the in-app bridge server. NOT an AT Engine / ADB
// Engine public contract, NOT a Debug protocol, NOT a third-party stable API.
//
// Frame: [type:1][len:4 LE][payload:len]. Binary-safe (arbitrary bytes).

package com.at.cli

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object AdbCliProtocol {
    const val VERSION = 1

    // Frame type tags (must match adb_cli.cpp).
    const val T_HELLO = 1
    const val T_HELLO_ACK = 2
    const val T_ONESHOT = 3
    const val T_OUT = 4
    const val T_ERR = 5
    const val T_EXIT = 6
    const val T_INTERACTIVE = 7
    const val T_STDIN = 8
    const val T_RESIZE = 9
    const val T_CLOSE = 10
    const val T_ERROR = 11
    // AT.CLI-0.1A: server -> client human-readable lifecycle status, routed to
    // the CLI's stderr (status channel). Additive (tag 12 was free); existing
    // frames keep their tags/semantics. Backward-compatible: a client that does
    // not know tag 12 simply ignores it (its readFrame loop has an else-ignore).
    const val T_STATUS = 12
    // AT.CONSUMER-CONSUMER-BOOTSTRAP-0: client -> server `adb pair` request.
    // Payload = "host:port<0x00>code" (host+port as the user typed, NUL, code).
    // The bridge routes this to the host's AdbConsumerBootstrap.pair(...).
    // Reuses T_OUT / T_ERR / T_EXIT for the response (pair result text + exit).
    // Additive (tag 13 was free); existing frames keep their tags/semantics.
    const val T_PAIR = 13

    const val MAX_FRAME = 1 shl 20 // 1 MiB

    /** Write one frame. Returns false on IO failure. */
    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray?): Boolean {
        return try {
            val dos = DataOutputStream(out)
            dos.write(type)
            val len = payload?.size ?: 0
            dos.writeByte(len and 0xff)
            dos.writeByte((len shr 8) and 0xff)
            dos.writeByte((len shr 16) and 0xff)
            dos.writeByte((len shr 24) and 0xff)
            if (payload != null) dos.write(payload)
            dos.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Read one frame. Returns [Frame] on success, null on EOF, or throws
     * [ProtocolException] on malformed / oversized frames.
     */
    fun readFrame(inp: InputStream): Frame? {
        val dis = DataInputStream(inp)
        val type = dis.read()
        if (type < 0) return null
        val len = dis.readUnsignedByte() or
            (dis.readUnsignedByte() shl 8) or
            (dis.readUnsignedByte() shl 16) or
            (dis.readUnsignedByte() shl 24)
        if (len > MAX_FRAME) throw ProtocolException("frame too large: $len")
        val payload = if (len > 0) {
            val b = ByteArray(len)
            dis.readFully(b)
            b
        } else ByteArray(0)
        return Frame(type, payload)
    }

    class Frame(val type: Int, val payload: ByteArray) {
        fun le32(offset: Int = 0): Int =
            (payload[offset].toInt() and 0xff) or
            ((payload[offset + 1].toInt() and 0xff) shl 8) or
            ((payload[offset + 2].toInt() and 0xff) shl 16) or
            ((payload[offset + 3].toInt() and 0xff) shl 24)
    }

    class ProtocolException(msg: String) : RuntimeException(msg)

    fun le32Bytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )
}
