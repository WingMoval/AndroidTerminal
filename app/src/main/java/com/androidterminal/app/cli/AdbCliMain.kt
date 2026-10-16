// AT.CLI-0.1 — Production `adb` CLI main (app_process entrypoint).
//
// Launched from the real Local /system/bin/sh via the shell-visible `adb`
// function (see LocalSessionBootstrap):
//
//   adb shell <command...>
//     -> /system/bin/app_process -Djava.class.path=$AT_APP_APK /system/bin
//            com.androidterminal.app.cli.AdbCliMain shell <command...>
//
// This is a THIN frontend + IPC client. It does NOT initialize AdbRuntime,
// read any adb identity, or open any ADB connection itself. It connects the
// existing Product CLI Bridge UDS (same-UID, app-private) and delegates the
// one-shot to the app process (AppRuntime -> ATEngine/ADB Backend -> ADB
// Engine -> adbd), then maps remote stdout/stderr/exitCode onto its own
// stdout/stderr/exit and exits.
//
// Pure JVM/Kotlin (no JNI / no System.loadLibrary): app_process does not set
// java.library.path to the APK native lib dir, so JNI would fail. UDS uses
// android.net.LocalSocket (same-UID connect to the app's private socket — the
// exact SO_PEERCRED same-UID case the bridge authenticates).
//
// Production: present in BOTH debug and release (src/main). Distinct from the
// debug-only CliProbe (src/debug).

package com.androidterminal.app.cli

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object AdbCliMain {

    private const val TAG = "AdbCliMain"

    const val BRIDGE_PROTOCOL_VERSION = AdbCliProtocol.VERSION

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        val err = System.err

        val parsed = AdbCliParseResult.parse(args)
        when (parsed.kind) {
            AdbCliParseResult.Kind.NO_ARGS,
            AdbCliParseResult.Kind.UNSUPPORTED -> {
                if (parsed.toStderr) err.println(parsed.message) else out.println(parsed.message)
                return processExit(parsed.exitCode)
            }
            AdbCliParseResult.Kind.HELP -> {
                // `adb shell` (no command) reuses HELP kind with an error message.
                if (parsed.exitCode != 0) {
                    err.println(parsed.message)
                    return processExit(parsed.exitCode)
                }
                out.print(parsed.message)
                return processExit(0)
            }
            AdbCliParseResult.Kind.VERSION -> {
                out.println(parsed.message)
                return processExit(0)
            }
            AdbCliParseResult.Kind.SHELL -> {
                // One-shot via the Product CLI Bridge.
                val res = bridgeOneShot(parsed.remoteCommand)
                if (res == null) {
                    err.println("adb: bridge unavailable (is the AndroidTerminal app running?)")
                    return processExit(1)
                }
                // remote stdout -> System.out ; remote stderr -> System.err
                if (res.stdout.isNotEmpty()) out.print(res.stdout)
                if (res.stderr.isNotEmpty()) err.print(res.stderr)
                if (res.error != null) err.println("adb: $res.error")
                return processExit(res.exitCode)
            }
        }
    }

    private fun processExit(code: Int) {
        // Flush before exit so the Local PTY sees all output.
        runCatching { System.out.flush() }
        runCatching { System.err.flush() }
        System.exit(code)
    }

    /** Result of a bridge one-shot round-trip. */
    private data class OneShotResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val error: String?,
    )

    /**
     * Connect the Product CLI Bridge UDS and run a one-shot. Returns null on
     * transport failure. Reuses protocol v1 (T_HELLO / T_ONESHOT / T_OUT /
     * T_ERR / T_ERROR / T_EXIT). No new protocol, no new port, no second
     * AdbRuntime.
     */
    private fun bridgeOneShot(remoteCommand: String): OneShotResult? {
        val sock = resolveSocketPath()
        android.util.Log.i(TAG, "bridgeOneShot: connect $sock cmd=[$remoteCommand]")
        val ls = LocalSocket()
        try {
            ls.connect(LocalSocketAddress(sock, LocalSocketAddress.Namespace.FILESYSTEM))
            ls.soTimeout = 120_000 // generous: remote one-shot may take a while
            android.util.Log.i(TAG, "bridgeOneShot: connected")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "bridgeOneShot: connect failed: ${e.message}")
            runCatching { ls.close() }
            return null
        }
        try {
            val outp: OutputStream = ls.outputStream
            val inp: InputStream = ls.inputStream

            // HELLO.
            writeFrame(outp, AdbCliProtocol.T_HELLO, byteArrayOf(BRIDGE_PROTOCOL_VERSION.toByte()))
            val ack = readFrame(inp) ?: return fail("no HELLO_ACK")
            if (ack.type != AdbCliProtocol.T_HELLO_ACK) return fail("bad HELLO_ACK type=${ack.type}")

            // ONESHOT.
            writeFrame(outp, AdbCliProtocol.T_ONESHOT, remoteCommand.toByteArray(Charsets.UTF_8))
            android.util.Log.i(TAG, "bridgeOneShot: ONESHOT sent, waiting for frames")

            var stdout = StringBuilder()
            var stderr = StringBuilder()
            var exitCode = 1
            var error: String? = null
            while (true) {
                val f = readFrame(inp) ?: break
                android.util.Log.d(TAG, "bridgeOneShot: frame type=${f.type} len=${f.payload.size}")
                when (f.type) {
                    AdbCliProtocol.T_OUT -> stdout.append(String(f.payload, Charsets.UTF_8))
                    AdbCliProtocol.T_ERR -> stderr.append(String(f.payload, Charsets.UTF_8))
                    AdbCliProtocol.T_ERROR -> error = String(f.payload, Charsets.UTF_8)
                    AdbCliProtocol.T_EXIT -> {
                        exitCode = if (f.payload.size >= 4) f.le32(0) else 1
                        break
                    }
                    else -> { /* ignore unknown */ }
                }
            }
            return OneShotResult(stdout.toString(), stderr.toString(), exitCode, error)
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { ls.close() }
        }
    }

    private fun fail(msg: String): OneShotResult? {
        System.err.println("adb: bridge error: $msg")
        return null
    }

    // ---- frame IO (matches AdbCliProtocol / adb_cli.cpp) --------------------

    private fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(type)
        val len = payload.size
        out.write(len and 0xff)
        out.write((len shr 8) and 0xff)
        out.write((len shr 16) and 0xff)
        out.write((len shr 24) and 0xff)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readFrame(inp: InputStream): Frame? {
        val type = inp.read()
        if (type < 0) return null
        val b0 = inp.read(); if (b0 < 0) return null
        val b1 = inp.read(); if (b1 < 0) return null
        val b2 = inp.read(); if (b2 < 0) return null
        val b3 = inp.read(); if (b3 < 0) return null
        val len = (b0 and 0xff) or ((b1 and 0xff) shl 8) or ((b2 and 0xff) shl 16) or ((b3 and 0xff) shl 24)
        if (len > AdbCliProtocol.MAX_FRAME) throw RuntimeException("frame too large: $len")
        val payload = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(payload, off, len - off)
            if (n < 0) throw RuntimeException("short read")
            off += n
        }
        return Frame(type, payload)
    }

    private class Frame(val type: Int, val payload: ByteArray) {
        fun le32(offset: Int): Int =
            (payload[offset].toInt() and 0xff) or
                ((payload[offset + 1].toInt() and 0xff) shl 8) or
                ((payload[offset + 2].toInt() and 0xff) shl 16) or
                ((payload[offset + 3].toInt() and 0xff) shl 24)
    }

    /** Best-effort resolve of the app filesDir/cli_bridge.sock path. */
    private fun resolveSocketPath(): String {
        // Prefer the app-injected path (the shell function sets AT_CLI_BRIDGE_SOCK).
        val envSock = System.getenv("AT_CLI_BRIDGE_SOCK")
        if (!envSock.isNullOrBlank()) return envSock
        val pkg = "com.androidterminal.app"
        for (base in listOf("/data/user/0/$pkg/files", "/data/data/$pkg/files")) {
            val f = File(base, "cli_bridge.sock")
            if (f.exists()) return f.absolutePath
        }
        return File("/data/user/0/$pkg/files", "cli_bridge.sock").absolutePath
    }
}
