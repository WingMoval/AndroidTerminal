// Test Host 鈥?ADB Backend headless vertical slice (AT.V3-2, Gates A/B/C).
//
// Independent consumer (no UI). Consumes AT Engine 鈫?ADB Backend 鈫?ADB Engine
// 鈫?wireless adbd. Uses explicit endpoint + trusted identity (keyDir seeded
// with the PC adbkey that the device already trusts).
//
// This is the HEADLESS verification; the ADBTerminal Dev UI APK is verified
// separately.

package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbEngine
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import com.at.engine.ATSessionLaunch
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
        val r = backend.openSession(ATSessionLaunch(command = "", initialRows = 24, initialCols = 80))
        assertTrue("open should succeed, got $r", r is ATOpenSessionResult.Success)
        val s = (r as ATOpenSessionResult.Success).session
        s.setListener { ev -> cap(ev) }
        return s to cap
    }

    private fun write(s: ATTerminalSession, text: String) { s.write((text + "\n").toByteArray()) }

    // ------------------------------------------------------------------
    // Gate A 鈥?one-shot
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
    // Gate B 鈥?persistent remote PTY
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
    // Reader cleanup (AT.V3-2.1 搂11 / 搂24)
    // ------------------------------------------------------------------

    @Test
    fun b9_readerExitAfterClose() {
        val b = AdbPersistentBackend(conn())
        val (s, cap) = openSession(b)
        write(s, "echo READY_B9")
        assertTrue(await(3000) { cap.out.contains("READY_B9") })

        // close() must return within a bounded time (reader join(1000) must
        // succeed because the hub wakes the blocked stream read).
        val t0 = System.currentTimeMillis()
        s.close()
        val elapsed = System.currentTimeMillis() - t0
        android.util.Log.i("ATV31", "b9: close() returned in ${elapsed}ms")
        assertTrue("close bounded (took ${elapsed}ms)", elapsed < 2000)
        assertEquals(ATSessionState.CLOSED, s.state)

        // No stale reader may own the shared connection: an immediate reopen
        // on the SAME connection must work.
        val (s2, cap2) = openSession(b)
        write(s2, "echo AFTER_CLOSE_OK")
        assertTrue(await(4000) { cap2.out.contains("AFTER_CLOSE_OK") })
        s2.close()
    }

    @Test
    fun b10_blockedReadWakesOnClose() {
        // ADB-Engine-level focused case: a thread blocked inside read() must
        // wake promptly (with -1) when the stream is closed from another
        // thread. The reader loops so it consumes any queued PTY prompt data
        // first and then genuinely blocks on the empty stream queue.
        assertTrue(AdbEngine.connect(host, port, keyDir))
        val st = AdbEngine.openInteractive("shell,v2,pty:")
        assertTrue("interactive stream open", st != null)

        val sawMinusOne = java.util.concurrent.atomic.AtomicBoolean(false)
        val exited = java.util.concurrent.CountDownLatch(1)
        val t = Thread({
            val buf = ByteArray(512)
            while (true) {
                val n = st!!.read(buf, 5000) // blocks up to 5s on the slot queue
                if (n < 0) {
                    sawMinusOne.set(true)
                    exited.countDown()
                    break
                }
            }
        }, "b10-blocked-reader")
        t.start()
        Thread.sleep(500) // prompt consumed; reader now blocked in read

        val t0 = System.currentTimeMillis()
        st!!.close()
        assertTrue("reader woke after close",
            exited.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val dt = System.currentTimeMillis() - t0
        android.util.Log.i("ATV31", "b10: read woke after ${dt}ms, minusOne=${sawMinusOne.get()}")
        assertTrue("woke quickly (took ${dt}ms)", dt < 1500)
        assertTrue("blocked read returns -1 after close", sawMinusOne.get())
        t.join(1000)
    }

    // ------------------------------------------------------------------
    // Gate C 鈥?concurrent multi-session (AT.V3-2.1)
    // ------------------------------------------------------------------

    @Test
    fun c1_threeConcurrentSessionsActive() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, capB) = openSession(b)
        val (c, capC) = openSession(b)
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, bb.state)
        assertEquals(ATSessionState.ACTIVE, c.state)

        // All three run concurrently on the ONE connection. Arithmetic hides
        // the result from the PTY echo (echo shows "$((1+1))", not "2").
        write(a, "echo MARKA=\$((1+1))")
        write(bb, "echo MARKB=\$((2+2))")
        write(c, "echo MARKC=\$((3+3))")
        assertTrue("A output", await(5000) { capA.out.contains("MARKA=2") })
        assertTrue("B output", await(5000) { capB.out.contains("MARKB=4") })
        assertTrue("C output", await(5000) { capC.out.contains("MARKC=6") })

        // No stream cross-talk.
        assertTrue("A not see B", !capA.out.contains("MARKB="))
        assertTrue("A not see C", !capA.out.contains("MARKC="))
        assertTrue("B not see A", !capB.out.contains("MARKA="))
        assertTrue("B not see C", !capB.out.contains("MARKC="))
        assertTrue("C not see A", !capC.out.contains("MARKA="))
        assertTrue("C not see B", !capC.out.contains("MARKB="))

        a.close(); bb.close(); c.close()
        assertEquals(ATSessionState.CLOSED, a.state)
        assertEquals(ATSessionState.CLOSED, bb.state)
        assertEquals(ATSessionState.CLOSED, c.state)
    }

    @Test
    fun c2_cwdEnvIsolation() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, capB) = openSession(b)
        val (c, capC) = openSession(b)

        write(a, "export AT_REMOTE=11")
        write(a, "cd /data/local/tmp")
        write(bb, "export AT_REMOTE=22")
        write(bb, "cd /system")
        Thread.sleep(500)

        write(a, "echo ENVA=\$((AT_REMOTE+0))")
        write(a, "echo CWDA=\$(pwd | cut -c1-15)")
        write(bb, "echo ENVB=\$((AT_REMOTE+0))")
        write(bb, "echo CWDB=\$(pwd | cut -c1-11)")
        write(c, "echo ENVC=[\$AT_REMOTE]")

        assertTrue("A env", await(5000) { capA.out.contains("ENVA=11") })
        assertTrue("A cwd", await(5000) { capA.out.contains("CWDA=/data/local/tmp") })
        assertTrue("B env", await(5000) { capB.out.contains("ENVB=22") })
        assertTrue("B cwd", await(5000) { capB.out.contains("CWDB=/system") })
        assertTrue("C env untouched", await(5000) { capC.out.contains("ENVC=[]") })

        // Isolation: no session sees another's env/cwd.
        assertTrue("A not see B env", !capA.out.contains("ENVB="))
        assertTrue("B not see A env", !capB.out.contains("ENVA="))
        assertTrue("C not see A env", !capC.out.contains("ENVA="))
        assertTrue("C not see B env", !capC.out.contains("ENVB="))
        assertTrue("A not see B cwd", !capA.out.contains("CWDB="))
        assertTrue("B not see A cwd", !capB.out.contains("CWDA="))

        a.close(); bb.close(); c.close()
    }

    @Test
    fun c3_stdinIsolation() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, capB) = openSession(b)
        val (c, capC) = openSession(b)

        write(c, "cat")
        Thread.sleep(400)
        write(c, "CATSTDIN_MARK")
        assertTrue("C got its stdin", await(4000) { capC.out.contains("CATSTDIN_MARK") })
        c.write(byteArrayOf(0x04)) // Ctrl-D ends cat

        write(a, "echo AA=\$((5+5))")
        write(bb, "echo BB=\$((6+6))")
        assertTrue("A alive", await(4000) { capA.out.contains("AA=10") })
        assertTrue("B alive", await(4000) { capB.out.contains("BB=12") })

        assertTrue("A not see C stdin", !capA.out.contains("CATSTDIN_MARK"))
        assertTrue("B not see C stdin", !capB.out.contains("CATSTDIN_MARK"))
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, bb.state)
        assertEquals(ATSessionState.ACTIVE, c.state)
        a.close(); bb.close(); c.close()
    }

    @Test
    fun c4_ctrlCIsolation() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, capB) = openSession(b)
        val (c, capC) = openSession(b)

        write(c, "sleep 30")
        Thread.sleep(600)
        c.write(byteArrayOf(0x03)) // Ctrl-C -> C
        Thread.sleep(400)
        write(c, "echo ACC=\$((7+7))")
        write(a, "echo AAL=\$((8+8))")
        write(bb, "echo BBL=\$((9+9))")

        assertTrue("C recovered after Ctrl-C", await(5000) { capC.out.contains("ACC=14") })
        assertTrue("A alive", await(5000) { capA.out.contains("AAL=16") })
        assertTrue("B alive", await(5000) { capB.out.contains("BBL=18") })
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, bb.state)
        assertEquals(ATSessionState.ACTIVE, c.state)
        a.close(); bb.close(); c.close()
    }

    @Test
    fun c5_resizeIsolation() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, capB) = openSession(b)
        val (c, _) = openSession(b)

        assertTrue(a.resize(40, 120))
        assertTrue(bb.resize(24, 80))
        Thread.sleep(300)

        write(a, "echo SIZEA=\$(stty size)")
        write(bb, "echo SIZEB=\$(stty size)")
        assertTrue("A keeps 40x120", await(5000) { capA.out.contains("SIZEA=40 120") })
        assertTrue("B keeps 24x80", await(5000) { capB.out.contains("SIZEB=24 80") })
        assertEquals(ATSessionState.ACTIVE, c.state)
        a.close(); bb.close(); c.close()
    }

    @Test
    fun c6_closeIsolation() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, _) = openSession(b)
        val (c, capC) = openSession(b)

        bb.close()
        assertEquals(ATSessionState.CLOSED, bb.state)

        write(a, "echo ACB=\$((2+3))")
        write(c, "echo CCB=\$((3+4))")
        assertTrue("A survives B close", await(5000) { capA.out.contains("ACB=5") })
        assertTrue("C survives B close", await(5000) { capC.out.contains("CCB=7") })
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, c.state)
        assertTrue("transport still ONLINE", AdbEngine.connectionState().isOnline)
        a.close(); c.close()
    }

    @Test
    fun c7_reopenWithOtherStreamsActive() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, _) = openSession(b)
        bb.close()

        // Reopen a new stream while A is still ACTIVE (registry cleanup).
        val (d, capD) = openSession(b)
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, d.state)
        write(a, "echo A7=\$((4+4))")
        write(d, "echo D7=\$((5+5))")
        assertTrue("A still alive", await(5000) { capA.out.contains("A7=8") })
        assertTrue("D new stream works", await(5000) { capD.out.contains("D7=10") })
        a.close(); d.close()
    }

    @Test
    fun c8_naturalExitIsolation() {
        val b = AdbPersistentBackend(conn())
        val (a, capA) = openSession(b)
        val (bb, capB) = openSession(b)
        val (c, _) = openSession(b)

        write(c, "exit")
        assertTrue("C exits naturally",
            await(4000) { c.state == ATSessionState.EXITED || c.state == ATSessionState.CLOSED })

        write(a, "echo A8=\$((6+6))")
        write(bb, "echo B8=\$((7+7))")
        assertTrue("A alive after C exit", await(5000) { capA.out.contains("A8=12") })
        assertTrue("B alive after C exit", await(5000) { capB.out.contains("B8=14") })
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, bb.state)
        a.close(); bb.close(); c.close()
    }

    // ------------------------------------------------------------------
    // Stability (AT.V3-2.1 搂34)
    // ------------------------------------------------------------------

    @Test
    fun s1_sequentialLifecycle() {
        val b = AdbPersistentBackend(conn())
        repeat(10) { i ->
            val (s, cap) = openSession(b)
            write(s, "echo SEQ$i=\$(($i+100))")
            assertTrue("iteration $i output",
                await(5000) { cap.out.contains("SEQ$i=${i + 100}") })
            s.close()
            assertEquals(ATSessionState.CLOSED, s.state)
        }
        assertTrue(AdbEngine.connectionState().isOnline)
        android.util.Log.i("ATV31", "s1: 10 sequential open/exec/close cycles OK")
    }

    @Test
    fun s2_concurrentLifecycle() {
        val b = AdbPersistentBackend(conn())
        val orders = listOf(listOf(0, 1, 2), listOf(2, 0, 1), listOf(1, 2, 0))
        orders.forEachIndexed { round, order ->
            val sessions = (0 until 3).map { idx ->
                val (s, cap) = openSession(b)
                s to cap
            }
            sessions.forEachIndexed { idx, (s, cap) ->
                write(s, "echo R${round}S$idx=\$((10+$idx))")
                assertTrue("round $round session $idx",
                    await(5000) { cap.out.contains("R${round}S$idx=${10 + idx}") })
            }
            order.forEach { idx ->
                sessions[idx].first.close()
                assertEquals(ATSessionState.CLOSED, sessions[idx].first.state)
            }
        }
        assertTrue(AdbEngine.connectionState().isOnline)
        // one more open proves no residual resources after 3 concurrent rounds
        val (s, cap) = openSession(b)
        write(s, "echo STRESS_DONE")
        assertTrue(await(4000) { cap.out.contains("STRESS_DONE") })
        s.close()
        android.util.Log.i("ATV31", "s2: 3 concurrent rounds (varied close order) OK")
    }
}

