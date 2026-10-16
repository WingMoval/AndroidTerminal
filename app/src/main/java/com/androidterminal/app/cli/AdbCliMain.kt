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
            AdbCliParseResult.Kind.INTERACTIVE -> {
                // AT.CLI-0.2: interactive remote PTY relay (no-arg `adb shell`).
                // The shell wrapper (LocalSessionBootstrap) has already put the
                // Outer Local PTY into raw/no-echo mode and will restore it after
                // we exit. We relay Local stdin <-> remote PTY byte-oriented.
                val code = bridgeInteractive()
                return processExit(code)
            }
            AdbCliParseResult.Kind.SHELL -> {
                // One-shot via the Product CLI Bridge.
                val res = bridgeOneShot(parsed.remoteCommand)
                if (res == null) {
                    err.println("adb: bridge unavailable (is the AndroidTerminal app running?)")
                    return processExit(1)
                }
                // AT.CLI-0.1A: lifecycle status (stderr channel) is emitted FIRST
                // (before remote stdout) so the Human sees
                //   [adb] checking... / connected
                // then the command output — matching the §20/§22 ordering.
                if (res.status.isNotEmpty()) err.print(res.status)
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
        val status: String,
    )

    /**
     * Connect the Product CLI Bridge UDS and run a one-shot. Returns null on
     * transport failure. Reuses protocol v1 (T_HELLO / T_ONESHOT / T_OUT /
     * T_ERR / T_ERROR / T_EXIT). No new protocol, no new port, no second
     * AdbRuntime.
     */
    private fun bridgeOneShot(remoteCommand: String): OneShotResult? {
        val sock = resolveSocketPath()
        val sessionId = resolveSessionId()
        android.util.Log.i(TAG, "bridgeOneShot: connect $sock cmd=[$remoteCommand] session=$sessionId")
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

            // HELLO: payload = [version:1][sessionId:4 LE]. Additive; the server
            // reads version (byte 0) and, if present, the session id for ADB
            // status subscription binding.
            val sessionIdBytes = if (sessionId != null) le32(sessionId) else byteArrayOf()
            val helloPayload = ByteArray(1 + sessionIdBytes.size)
            helloPayload[0] = BRIDGE_PROTOCOL_VERSION.toByte()
            System.arraycopy(sessionIdBytes, 0, helloPayload, 1, sessionIdBytes.size)
            writeFrame(outp, AdbCliProtocol.T_HELLO, helloPayload)
            val ack = readFrame(inp) ?: return fail("no HELLO_ACK")
            if (ack.type != AdbCliProtocol.T_HELLO_ACK) return fail("bad HELLO_ACK type=${ack.type}")

            // ONESHOT.
            writeFrame(outp, AdbCliProtocol.T_ONESHOT, remoteCommand.toByteArray(Charsets.UTF_8))
            android.util.Log.i(TAG, "bridgeOneShot: ONESHOT sent, waiting for frames")

            var stdout = StringBuilder()
            var stderr = StringBuilder()
            var status = StringBuilder()
            var exitCode = 1
            var error: String? = null
            while (true) {
                val f = readFrame(inp) ?: break
                android.util.Log.d(TAG, "bridgeOneShot: frame type=${f.type} len=${f.payload.size}")
                when (f.type) {
                    AdbCliProtocol.T_OUT -> stdout.append(String(f.payload, Charsets.UTF_8))
                    AdbCliProtocol.T_ERR -> stderr.append(String(f.payload, Charsets.UTF_8))
                    // AT.CLI-0.1A: lifecycle status -> stderr channel. Kept in its
                    // own builder and emitted via the main-branch err.print path
                    // (proven to reach the Local PTY observer), BEFORE remote
                    // stdout.
                    AdbCliProtocol.T_STATUS -> {
                        val s = String(f.payload, Charsets.UTF_8)
                        if (s.isNotEmpty()) status.append(s)
                    }
                    AdbCliProtocol.T_ERROR -> error = String(f.payload, Charsets.UTF_8)
                    AdbCliProtocol.T_EXIT -> {
                        exitCode = if (f.payload.size >= 4) f.le32(0) else 1
                        break
                    }
                    else -> { /* ignore unknown */ }
                }
            }
            return OneShotResult(stdout.toString(), stderr.toString(), exitCode, error, status.toString())
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { ls.close() }
        }
    }

    /**
     * AT.CLI-0.2: interactive remote PTY relay (no-arg `adb shell`).
     *
     * Bidirectional, BYTE-ORIENTED (never line-buffered, never waits for
     * newline):
     *   Local stdin  -> T_STDIN frames  -> Bridge  -> ATTerminalSession.write
     *   remote output -> T_OUT frames    -> stdout  -> Outer Local PTY
     *
     * Concurrency model (one stdin relay thread + one bridge read loop):
     *   - a dedicated thread reads System.in and pushes T_STDIN frames;
     *   - the main thread reads UDS frames and writes System.out / System.err.
     * Socket frame writes are serialized by the UDS itself (single write path
     * from this process: only the stdin thread writes frames here; the read loop
     * only reads), so no frame interleave.
     *
     * Returns the process exit code (0 on clean remote exit, 1 on transport
     * failure). The shell wrapper restores the Outer TTY regardless.
     */
    private fun bridgeInteractive(): Int {
        val out = System.out
        val err = System.err
        val sock = resolveSocketPath()
        val sessionId = resolveSessionId()
        // Initial remote PTY size from the Outer Local PTY (shell wrapper reads
        // `stty size` and injects AT_CLI_ROWS / AT_CLI_COLS). Fall back to a
        // sane default if unavailable (the Human can still resize later).
        val rows = resolveIntEnv("AT_CLI_ROWS") ?: 24
        val cols = resolveIntEnv("AT_CLI_COLS") ?: 80
        android.util.Log.i(TAG, "bridgeInteractive: connect $sock session=$sessionId rows=$rows cols=$cols")
        val ls = LocalSocket()
        try {
            ls.connect(LocalSocketAddress(sock, LocalSocketAddress.Namespace.FILESYSTEM))
            // No timeout: interactive is open-ended; the read loop is the liveness.
            ls.soTimeout = 0
        } catch (e: Exception) {
            android.util.Log.w(TAG, "bridgeInteractive: connect failed: ${e.message}")
            runCatching { ls.close() }
            err.println("adb: bridge unavailable (is the AndroidTerminal app running?)")
            return 1
        }
        var stdinThread: Thread? = null
        try {
            val outp: OutputStream = ls.outputStream
            val inp: InputStream = ls.inputStream

            // HELLO (same as one-shot): version + sessionId.
            val sessionIdBytes = if (sessionId != null) le32(sessionId) else byteArrayOf()
            val helloPayload = ByteArray(1 + sessionIdBytes.size)
            helloPayload[0] = BRIDGE_PROTOCOL_VERSION.toByte()
            System.arraycopy(sessionIdBytes, 0, helloPayload, 1, sessionIdBytes.size)
            writeFrame(outp, AdbCliProtocol.T_HELLO, helloPayload)
            val ack = readFrame(inp) ?: return failInteractive("no HELLO_ACK")
            if (ack.type != AdbCliProtocol.T_HELLO_ACK) return failInteractive("bad HELLO_ACK type=${ack.type}")

            // INTERACTIVE open: payload = [rows:4 LE][cols:4 LE].
            val openPayload = le32(rows) + le32(cols)
            writeFrame(outp, AdbCliProtocol.T_INTERACTIVE, openPayload)

            // Start the stdin relay thread (byte-oriented, no newline wait).
            val stdin: InputStream = System.`in`
            stdinThread = Thread({
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = stdin.read(buf)
                        if (n < 0) break // EOF
                        if (n == 0) continue
                        if (!writeFrame(outp, AdbCliProtocol.T_STDIN, buf.copyOf(n))) break
                    }
                } catch (_: Throwable) {
                    // stdin read error: stop relaying input.
                } finally {
                    // Signal close so the bridge tears down the inner session.
                    runCatching { writeFrame(outp, AdbCliProtocol.T_CLOSE, ByteArray(0)) }
                }
            }, "at-cli-stdin-relay").also { it.isDaemon = true; it.start() }

            // Bridge read loop: remote output / status / exit.
            var exitCode = 1
            while (true) {
                val f = readFrame(inp) ?: break // EOF / transport loss
                when (f.type) {
                    AdbCliProtocol.T_OUT -> {
                        out.write(f.payload)
                        out.flush()
                    }
                    AdbCliProtocol.T_ERR -> {
                        err.write(f.payload)
                        err.flush()
                    }
                    // AT.CLI-0.1B/0.2: lifecycle status (checking/connected) on
                    // the stderr channel, BEFORE the remote prompt (cold path).
                    AdbCliProtocol.T_STATUS -> {
                        val s = String(f.payload, Charsets.UTF_8)
                        if (s.isNotEmpty()) { err.print(s); err.flush() }
                    }
                    AdbCliProtocol.T_ERROR -> {
                        err.println("adb: ${String(f.payload, Charsets.UTF_8)}")
                    }
                    AdbCliProtocol.T_EXIT -> {
                        exitCode = if (f.payload.size >= 4) f.le32(0) else 0
                        break
                    }
                    else -> { /* ignore unknown */ }
                }
            }
            return exitCode
        } catch (e: Exception) {
            return failInteractive(e.message ?: e.javaClass.simpleName)
        } finally {
            stdinThread?.let { runCatching { it.join(2000) } }
            runCatching { ls.close() }
        }
    }

    private fun failInteractive(msg: String): Int {
        System.err.println("adb: interactive bridge error: $msg")
        return 1
    }

    /** Resolve the Local Session id (AT_SESSION_ID env, set by the bootstrap). */
    private fun resolveSessionId(): Int? = try {
        val raw = System.getenv("AT_SESSION_ID")
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    } catch (_: Throwable) {
        null
    }

    /** Resolve an int env var (AT_CLI_ROWS / AT_CLI_COLS), null if unset/invalid. */
    private fun resolveIntEnv(name: String): Int? = try {
        val raw = System.getenv(name)
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )

    private fun fail(msg: String): OneShotResult? {
        System.err.println("adb: bridge error: $msg")
        return null
    }

    // ---- frame IO (matches AdbCliProtocol / adb_cli.cpp) --------------------

    /** Write one frame. Returns false on IO failure (used by the interactive
     *  stdin relay to stop pushing input when the bridge is gone). */
    private fun writeFrame(out: OutputStream, type: Int, payload: ByteArray): Boolean = try {
        out.write(type)
        val len = payload.size
        out.write(len and 0xff)
        out.write((len shr 8) and 0xff)
        out.write((len shr 16) and 0xff)
        out.write((len shr 24) and 0xff)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
        true
    } catch (_: Exception) {
        false
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
