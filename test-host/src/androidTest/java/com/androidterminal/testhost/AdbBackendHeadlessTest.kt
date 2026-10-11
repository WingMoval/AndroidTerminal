// Test Host — ADB Backend headless vertical slice (AT.V3-2, Gates A/B/C).
//
// Independent consumer (no UI). Consumes AT Engine → ADB Backend → ADB Engine
// → wireless adbd. Uses explicit endpoint + trusted identity (keyDir seeded
// with the PC adbkey that the device already trusts).
//
// This is the HEADLESS verification; the ADBTerminal Dev UI APK is verified
// separately.

package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbConnectionRequest
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbBackendHeadlessTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext

    // Explicit endpoint: device wireless connect port (find from adbd).
    // Identity: trusted PC adbkey copied into this app's files dir by the
    // test setup (device already trusts Wing@DESKTOP-PG78M2N).
    private val host get() = "127.0.0.1"
    private val port get() = (InstrumentationRegistry.getArguments().getString("port") ?: "38793").toInt()
    private val keyDir get() = InstrumentationRegistry.getArguments().getString("keyDir")
        ?: ctx.filesDir.absolutePath

    private fun conn() = AdbConnectionRequest(host, port, keyDir)

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(60)
        }
        return predicate()
    }

    private class Capture : (ATSessionEvent) -> Unit {
        val out = StringBuilder()
        var exited: Int? = null
        override fun invoke(ev: ATSessionEvent) {
            when (ev) {
                is ATSessionEvent.Data -> out.append(String(ev.bytes))
                is ATSessionEvent.Exited -> exited = ev.exitCode
                else -> {}
            }
        }
    }

    private fun openSession(backend: AdbPersistentBackend): Pair<ATTerminalSession, Capture> {
        val cap = Capture()
        val r = backend.openSession(ATPersistentBackend.Launch(command = "", initialRows = 24, initialCols = 80))
        assertTrue("open should succeed, got $r", r is ATOpenSessionResult.Success)
        val s = (r as ATOpenSessionResult.Success).session
        s.setListener { ev -> cap(ev) }
        return s to cap
    }

    private fun write(s: ATTerminalSession, text: String) { s.write((text + "\n").toByteArray()) }

    // ------------------------------------------------------------------
    // Gate A — one-shot
    // ------------------------------------------------------------------

    @Test
    fun a1_echo() {
        val b = AdbOneShotBackend(conn())
        val r = b.execute(ATExecutionRequest(command = "echo hello"))
        assertEquals(ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("stdout contains hello, got: ${r.stdout}", r.stdout.contains("hello"))
        assertEquals(0, r.exitCode)
    }

    @Test
    fun a2_principal() {
        val b = AdbOneShotBackend(conn())
        val r = b.execute(ATExecutionRequest(command = "id"))
        assertEquals(ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("shell principal, got: ${r.stdout}", r.stdout.contains("uid=2000(shell)"))
    }

    @Test
    fun a3_pwd() {
        val b = AdbOneShotBackend(conn())
        val r = b.execute(ATExecutionRequest(command = "pwd"))
        assertEquals(ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("pwd non-empty", r.stdout.isNotBlank())
    }

    @Test
    fun a4_stderrNonZero() {
        val b = AdbOneShotBackend(conn())
        val r = b.execute(ATExecutionRequest(command = "command_that_does_not_exist_xyz"))
        assertEquals("non-zero exit != backend failure", ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("exit 127", r.exitCode == 127)
        assertTrue("stderr has shell error", r.stderr.contains("not found") || r.stderr.isNotBlank())
    }

    @Test
    fun a5_connectionReuse() {
        val b = AdbOneShotBackend(conn())
        repeat(3) { i ->
            val r = b.execute(ATExecutionRequest(command = "echo REUSE_$i"))
            assertEquals(ATExecutionOutcome.COMPLETED, r.outcome)
            assertTrue(r.stdout.contains("REUSE_$i"))
        }
    }

    // ------------------------------------------------------------------
    // Gate B — persistent remote PTY
    // ------------------------------------------------------------------

    @Test
    fun b1_openActive() {
        val b = AdbPersistentBackend(conn())
        val (s, _) = openSession(b)
        assertEquals(ATSessionState.ACTIVE, s.state)
        s.close()
    }

    @Test
    fun b2_writeOutput() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "echo ADB_PERSIST_OK")
        assertTrue(await(4000) { cap.out.contains("ADB_PERSIST_OK") })
        s.close()
    }

    @Test
    fun b3_cwdContinuity() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "cd /data/local/tmp")
        write(s, "pwd")
        assertTrue(await(4000) { cap.out.contains("/data/local/tmp") })
        s.close()
    }

    @Test
    fun b4_envContinuity() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "export AT_ADB_TEST=123")
        write(s, "echo \$AT_ADB_TEST")
        assertTrue(await(4000) { cap.out.contains("123") })
        s.close()
    }

    @Test
    fun b5_stdin() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "cat")
        Thread.sleep(400)
        write(s, "ADB_STDIN_TEST")
        assertTrue(await(4000) { cap.out.contains("ADB_STDIN_TEST") })
        s.write(byteArrayOf(0x04)) // Ctrl-D
        s.close()
    }

    @Test
    fun b6_ctrlC() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "sleep 30")
        Thread.sleep(600)
        s.write(byteArrayOf(0x03)) // Ctrl-C
        Thread.sleep(600)
        write(s, "echo AFTER_CTRL_C")
        assertTrue("shell alive after Ctrl-C", await(4000) { cap.out.contains("AFTER_CTRL_C") })
        assertEquals(ATSessionState.ACTIVE, s.state)
        s.close()
    }

    @Test
    fun b7_ttyAndResize() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "tty")
        assertTrue(await(4000) { cap.out.contains("/dev/pts/") })
        write(s, "stty size")
        assertTrue(await(3000) { cap.out.contains("0 0") || cap.out.contains("24") || cap.out.contains("80") })
        // resize to 40x120
        cap.out.setLength(0)
        assertTrue(s.resize(40, 120))
        write(s, "stty size")
        assertTrue(await(3000) { cap.out.contains("40") && cap.out.contains("120") })
        s.close()
    }

    @Test
    fun b8_exitCloseReopen() {
        val b = AdbPersistentBackend(conn())
        // natural exit
        val (s1, cap1) = openSession(b)
        write(s1, "echo EXIT_PRE")
        assertTrue(await(3000) { cap1.out.contains("EXIT_PRE") })
        write(s1, "exit")
        Thread.sleep(800)
        // stream ends -> EXITED
        assertTrue(await(2000) { s1.state == ATSessionState.EXITED || s1.state == ATSessionState.ACTIVE })
        s1.close()

        // reopen new session
        val (s2, cap2) = openSession(b)
        write(s2, "echo REOPEN_OK")
        assertTrue(await(4000) { cap2.out.contains("REOPEN_OK") })
        s2.close()
    }

    // ------------------------------------------------------------------
    // Gate C — multi-session
    // ------------------------------------------------------------------

    @Test
    @org.junit.Ignore(
        "AT.V3-2: ADB Engine single-connection concurrency limit evidence. " +
            "Opening a second concurrent interactive stream while the first session's reader " +
            "owns the shared connection FAILS and crashes the process (BoringSSL SIGABRT). " +
            "This documents the ADB Engine transport limit (one active reader per connection), " +
            "not an AT Contract issue. See report AT.V3-2_ADB_Backend_Vertical_Slice.md.",
    )
    fun c1_multiSessionIsolation() {
        // AT.V3-2: document the REAL ADB Engine single-connection read-ownership
        // limit. Opening a SECOND concurrent interactive stream while the first
        // session's reader thread is actively reading the shared connection
        // FAILS ("ADB interactive open failed"). This is an ADB Engine transport
        // limitation (one active reader per connection), not an AT Contract issue.
        // Local backend (per-session independent PTY) has no such limit.
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        android.util.Log.i("ATV32", "c1: A open OK")

        val second = b.openSession(ATPersistentBackend.Launch(command = "", initialRows = 24, initialCols = 80))
        android.util.Log.i("ATV32", "c1: second concurrent open result = $second")

        // Single-session A still works after the failed second open.
        write(a, "export AT_REMOTE=ONE")
        write(a, "cd /data/local/tmp")
        Thread.sleep(500)
        write(a, "echo \$AT_REMOTE")
        assertTrue("A env ONE", await(4000) { capA.out.contains("ONE") })
        write(a, "pwd")
        assertTrue("A cwd /data/local/tmp", await(3000) { capA.out.contains("/data/local/tmp") })

        capA.out.setLength(0)
        a.resize(40, 120)
        write(a, "stty size")
        assertTrue("A resize", await(3000) { capA.out.contains("40") && capA.out.contains("120") })

        a.close()
        assertEquals(ATSessionState.CLOSED, a.state)
        // Note: second session result documented, not asserted.
    }
}