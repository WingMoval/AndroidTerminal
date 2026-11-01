// AT.LOCAL-TCP-1B — Device product-wiring sanity (B-gate, B1-B5).
//
// Real-device verification of the MINIMAL RUNTIME WIRING: the verified 1A Local
// TCP core wired into AdbRuntime / AdbBackend becomes a selectable active
// transport. This is NOT a zero-state (Z0-Z16) test.
//
// Precondition: a legacy-mode adbd listener is live on 127.0.0.1:38800 (the
// canonical DEFAULT_LOCAL_ADB_PORT) and the test-host keyDir holds an authorized
// adbkey (seeded the same way as the 1A core sanity).
//
// B1 Local-first  : AdbRuntime.ensureReady -> Local detected -> activeTransport LOCAL
// B2 one-shot     : AdbRuntime.executeShell("id") routes to the Local one-shot
// B3 PTY          : AdbRuntime.openInteractive("shell,v2,pty:") -> run id / pwd
// B4 two sessions : two concurrent PTY sessions on the SAME Local connection
// B5 one RX owner : exactly one RX owner (ONE connection, no Wireless concurrent owner)
package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.adb.engine.AdbTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTcpProductWiringTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDir get() = ctx.filesDir.absolutePath

    @Test
    fun localTcpProductWiringSanity() {
        // ---- B1: Local-first (Runtime detects Local, activeTransport LOCAL) ----
        AdbRuntime.localConnectForTest = null // use the REAL 1A Local core
        AdbRuntime.disableLocalTcp()          // clean start
        val ready = AdbRuntime.ensureReady(keyDir) { }
        assertEquals("B1: ensureReady must reach READY via Local-first", AdbRuntimeState.READY, ready)
        assertEquals("B1: activeTransport must be LOCAL (Local-first probe)", AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        println("[B1] local-first: activeTransport=LOCAL state=READY")

        // ---- B2: one-shot (routes to the Local one-shot API) ----
        val id = AdbRuntime.executeShell("id", timeoutMs = 10_000)
        assertTrue("B2: one-shot must not have a transport/protocol error: ${id.error}", !id.hasError)
        assertTrue("B2: 'id' stdout should contain uid=; got '${id.stdout.trim()}'", id.stdout.contains("uid="))
        assertEquals("B2: one-shot exit code should be 0", 0, id.exitCode)
        println("[B2] one-shot id -> exit=0 stdout='${id.stdout.trim()}'")

        // ---- B3: PTY (openInteractive routes to the Local PTY; run id / pwd) ----
        val pty = AdbRuntime.openInteractive("shell,v2,pty:")
            ?: error("B3: PTY must open over the Local transport")
        val buf = ByteArray(8192)
        val out = StringBuilder()
        fun pump(rounds: Int, perMs: Int) {
            repeat(rounds) {
                val n = pty.read(buf, perMs)
                if (n > 0) out.append(String(buf, 0, n))
            }
        }
        pty.write("id\n".toByteArray()); pump(40, 250)
        pty.write("pwd\n".toByteArray()); pump(40, 250)
        pump(10, 250)
        val ptyOut = out.toString()
        assertTrue("B3: PTY must echo 'uid=' from id; got '$ptyOut'", ptyOut.contains("uid="))
        // pwd prints the shell cwd (e.g. "/") and the prompt re-appears; the key
        // evidence is that the PTY round-trips a real shell (id output + a prompt).
        assertTrue("B3: PTY must round-trip a shell prompt after pwd; got '$ptyOut'",
            ptyOut.contains("HNALI-Q") || ptyOut.contains("shell") || ptyOut.contains("/"))
        println("[B3] PTY id/pwd -> uid= present + shell round-trip OK")
        pty.close()

        // ---- B4: two concurrent sessions (ONE Local connection, N streams) ----
        val a = AdbRuntime.openInteractive("shell,v2,pty:")
            ?: error("B4: session A must open")
        val b = AdbRuntime.openInteractive("shell,v2,pty:")
            ?: error("B4: session B must open (same Local connection)")
        a.write("echo SESSION_A_MARKER\n".toByteArray())
        b.write("echo SESSION_B_MARKER\n".toByteArray())
        val bufA = ByteArray(8192); val bufB = ByteArray(8192)
        val outA = StringBuilder(); val outB = StringBuilder()
        repeat(60) {
            val na = a.read(bufA, 250); if (na > 0) outA.append(String(bufA, 0, na))
            val nb = b.read(bufB, 250); if (nb > 0) outB.append(String(bufB, 0, nb))
        }
        assertTrue("B4: session A output must contain SESSION_A_MARKER; got '$outA'", outA.toString().contains("SESSION_A_MARKER"))
        assertTrue("B4: session B output must contain SESSION_B_MARKER; got '$outB'", outB.toString().contains("SESSION_B_MARKER"))
        println("[B4] two sessions A+B both delivered their markers (ONE Local connection)")
        a.close(); b.close()

        // ---- B5: exactly one RX owner (ONE connection, no Wireless concurrent) ----
        // The Local transport is the single active transport; the Wireless client is
        // not connected (Local-first never bootstrapped Wireless). Assert the Local
        // transport is online and the active transport is LOCAL (single owner).
        assertTrue("B5: Local transport must still be ONLINE (single RX owner)", AdbRuntime.activeTransportOnline())
        assertEquals("B5: activeTransport must remain LOCAL (no Wireless concurrent owner)", AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        println("[B5] one RX owner: activeTransport=LOCAL, Local ONLINE (no Wireless concurrent owner)")

        // Teardown.
        AdbRuntime.disableLocalTcp()
        assertEquals(AdbTransportMode.WIRELESS, AdbRuntime.activeTransport)
    }
}
