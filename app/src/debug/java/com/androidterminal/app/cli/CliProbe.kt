// AT.CLI-0 — app_process entrypoint PROBE (P1–P6). NOT production CLI.
//
// Launched from the real Local /system/bin/sh (untrusted_app domain) via:
//   /system/bin/app_process -Djava.class.path=<base.apk> <stub> \
//       com.androidterminal.app.cli.CliProbe <args...>
//
// Verifies, with NO full adb shell implementation:
//   P3 argv passthrough
//   P4 stdin / stdout / stderr inherit the Local PTY
//   P5 can connect the existing Product CLI Bridge (UDS) + HELLO/HELLO_ACK
//   P6 returns cleanly (Local shell / TTY intact)
//
// P1 (app_process exec) + P2 (class load) are proven by this class running at
// all (reached from the Local shell). Kept minimal and side-effect-free.

package com.androidterminal.app.cli

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

object CliProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        val err = System.err
        // --no-stdin: skip the stdin peek (so a probe in a shared session does
        // not consume the shell's next command). Excluded from argv reporting.
        val noStdin = args.any { it == "--no-stdin" }
        val realArgs = args.filter { it != "--no-stdin" }
        // P3: argv passthrough.
        out.println("CLIPROBE_ARGC=${realArgs.size}")
        for (i in realArgs.indices) out.println("CLIPROBE_ARG_$i=${realArgs[i]}")

        // P4: stdin inherit — best-effort NON-BLOCKING peek. The Local PTY
        // stdin may have no data ready; never block (app_process + PTY stdin
        // can hang on a blocking read).
        if (noStdin) {
            out.println("CLIPROBE_STDIN=(skipped)")
        } else {
            try {
                val inStream = System.`in`
                val ready = inStream.available()
                if (ready > 0) {
                    val buf = ByteArray(ready)
                    val got = inStream.read(buf)
                    val line = if (got > 0) String(buf, 0, got, Charsets.UTF_8).trimEnd('\n', '\r') else ""
                    out.println("CLIPROBE_STDIN=[$line]")
                } else {
                    out.println("CLIPROBE_STDIN=(none)")
                }
            } catch (e: Exception) {
                err.println("CLIPROBE_STDIN_ERR=${e.javaClass.simpleName}")
            }
        }

        // P4: stdout + stderr inherit (this print is stdout; this is stderr).
        err.println("CLIPROBE_STDERR=ok")

        // P5: connect the existing Product CLI Bridge UDS + HELLO/HELLO_ACK.
        // Uses android.net.LocalSocket (Android-native UDS). app_process runs as
        // the app UID (same owner as the socket in app filesDir), so the connect
        // is same-UID — the exact case the bridge authenticates.
        try {
            val sock = resolveSocketPath()
            out.println("CLIPROBE_SOCK=[$sock]")
            val ls = LocalSocket()
            ls.connect(LocalSocketAddress(sock, LocalSocketAddress.Namespace.FILESYSTEM))
            ls.soTimeout = 3000
            val hello = byteArrayOf(
                AdbCliProtocol.T_HELLO.toByte(),
                1, 0, 0, 0,
                AdbCliProtocol.VERSION.toByte(),
            )
            ls.outputStream.write(hello)
            ls.outputStream.flush()
            val inp = ls.inputStream
            val hdr = ByteArray(5)
            var got = 0
            while (got < 5) {
                val n = inp.read(hdr, got, 5 - got)
                if (n < 0) break
                got += n
            }
            if (got >= 5) {
                val type = hdr[0].toInt() and 0xff
                val len = (hdr[1].toInt() and 0xff) or
                    ((hdr[2].toInt() and 0xff) shl 8) or
                    ((hdr[3].toInt() and 0xff) shl 16) or
                    ((hdr[4].toInt() and 0xff) shl 24)
                val body = if (len > 0) ByteArray(len) else ByteArray(0)
                var b = 0
                while (b < len) {
                    val n = inp.read(body, b, len - b)
                    if (n < 0) break
                    b += n
                }
                val ver = if (body.isNotEmpty()) body[0].toInt() and 0xff else -1
                out.println("CLIPROBE_BRIDGE=OK(type=$type ver=$ver)")
            } else {
                err.println("CLIPROBE_BRIDGE=READ_FAIL(got=$got)")
            }
            ls.close()
        } catch (e: Exception) {
            err.println("CLIPROBE_BRIDGE_ERR=${e.javaClass.simpleName}:${e.message}")
        }

        // P6: clean return (exit 0). Local shell / TTY must be intact after.
        out.println("CLIPROBE_DONE")
    }

    /** Best-effort resolve of the app filesDir/cli_bridge.sock path. */
    private fun resolveSocketPath(): String {
        // app_process has no Context; reconstruct the standard path.
        // The app uid's data dir: /data/user/0/<pkg> (or /data/data/<pkg>).
        val pkg = "com.androidterminal.app"
        for (base in listOf("/data/user/0/$pkg/files", "/data/data/$pkg/files")) {
            val f = File(base, "cli_bridge.sock")
            if (f.exists()) return f.absolutePath
        }
        // Fallback: let the caller see the intended path even if not found.
        return File("/data/user/0/$pkg/files", "cli_bridge.sock").absolutePath
    }
}
