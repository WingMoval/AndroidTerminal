// AT.LOCAL-TCP-1A — Local TCP CORE sanity test (NOT a zero-state product test).
//
// Exercises the minimal product port of the verified AT.RESEARCH-LOCAL-TCP-1.7
// Local TCP core:
//   1. connectLocalhost        (plain TCP + CNXN + AUTH + ONLINE + shared hub)
//   2. executeShellLocalhost   (hub one-shot "shell,v2,raw")
//   3. openInteractiveLocalhost (Local PTY reuses the Wireless AdbInteractiveStream)
//   4. two concurrent streams  (ONE connection, N streams over the shared hub)
//   5. disconnectLocalhost     (transport teardown)
//
// Run ONLY with explicit args (port + keyDir). The local adbd must be in
// legacy TCP mode listening on `port` (e.g. `adb tcpip 38800`). This is a CORE
// sanity check, not a full product / zero-state (Z0-Z16) test.
package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTcpCoreSanityTest {

    private val engine = AdbEngine

    private val port: Int =
        (InstrumentationRegistry.getArguments().getString("port") ?: "38800").toInt()
    private val keyDir: String =
        InstrumentationRegistry.getArguments().getString("keyDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath

    @Test
    fun localTcpCoreSanity() {
        // 1. Connect (idempotent: CNXN + AUTH + ONLINE + shared hub started).
        val ok = engine.connectLocalhost(port, keyDir)
        assertTrue("connectLocalhost should reach ONLINE", ok)
        assertTrue("localhostConnected should be true after connect",
            engine.localhostConnected())

        // 2. One-shot shell (hub one-shot "shell,v2,raw").
        val r = engine.executeShellLocalhost("echo at_local_tcp_1a")
        assertFalse("one-shot should not have a transport/protocol error: ${r.error}",
            r.hasError)
        assertTrue("stdout should contain the marker; got: '${r.stdout.trim()}'",
            r.stdout.contains("at_local_tcp_1a"))
        println("[1A] one-shot exit=${r.exitCode} stdout='${r.stdout.trim()}'")

        // 3 + 4. Two concurrent streams on the SAME connection (shared hub).
        val pty = engine.openInteractiveLocalhost("shell,v2,pty:")
            ?: error("Local PTY should open over the shared hub")
        val raw = engine.openInteractiveLocalhost("shell,v2,raw:echo two_streams")
            ?: error("second stream should open over the SAME connection")

        // The raw stream carries the echo output; read it (may need a couple
        // of reads as frames arrive). The PTY is held open concurrently to
        // prove ONE connection / N streams over the shared hub.
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        var seen = false
        repeat(20) {
            val n = raw.read(buf, 1000)
            if (n > 0) {
                sb.append(String(buf, 0, n))
                if (sb.toString().contains("two_streams")) { seen = true; return@repeat }
            }
            // n == 0: no data yet (keep waiting); n < 0: closed by peer.
        }
        assertTrue("second stream output should contain 'two_streams'; got: '$sb'",
            seen)
        println("[1A] two-streams raw output='$sb'")

        // 5. Teardown.
        pty.close()
        raw.close()
        engine.disconnectLocalhost()
        assertFalse("localhostConnected should be false after disconnect",
            engine.localhostConnected())
    }
}
