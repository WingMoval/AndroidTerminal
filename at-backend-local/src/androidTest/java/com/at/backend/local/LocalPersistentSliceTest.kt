// Local Backend 鈥?persistent terminal vertical slice instrumentation test.
//
// AT.PERSIST-0. Exercises the REAL call chain on-device:
//
//   Test -> ATEngine -> ATPersistentBackend -> Local Backend -> Local PTY
//          -> /system/bin/sh -i
//
// Verifies P1鈥揚10 (real PTY / principal / persistent I/O / cwd / env / stdin /
// Ctrl-C / resize / process exit / explicit close + cleanup).
//
// Requires a connected device. Status is recorded in the report; this file is
// the executable evidence harness.

package com.at.backend.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.at.engine.ATEngine
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionState
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPersistentSliceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openEngine(): ATEngine = DefaultATEngine(
        oneShot = LocalOneShotBackend(),
        persistent = LocalPersistentBackend(),
    )

    private fun openSession(engine: ATEngine): com.at.engine.ATTerminalSession {
        val r = engine.openSession(
            ATSessionLaunch(
                command = "/system/bin/sh",
                initialRows = 24,
                initialCols = 80,
            ),
        )
        assertTrue("open should succeed, got $r", r is ATOpenSessionResult.Success)
        return (r as ATOpenSessionResult.Success).session
    }

    private fun write(s: com.at.engine.ATTerminalSession, cmd: String) {
        s.write((cmd + "\n").toByteArray())
    }

    private fun drain(s: com.at.engine.ATTerminalSession, ms: Long = 1000): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val b = s.read(100)
            if (b != null && b.isNotEmpty()) sb.append(String(b))
        }
        return sb.toString()
    }

    @Test
    fun p1_realPty() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "tty")
        val out = drain(s)
        assertTrue("tty should be /dev/pts/*, got: $out", out.contains("/dev/pts/"))
        s.close()
    }

    @Test
    fun p2_principal() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "id")
        val out = drain(s)
        // Local path: app principal (untrusted_app), NOT uid=2000(shell).
        assertTrue("id should run in app principal, got: $out", out.contains("u:r:untrusted_app"))
        s.close()
    }

    @Test
    fun p3_persistentIo() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "echo FIRST")
        val out1 = drain(s, 800)
        assertTrue("should get FIRST on same session, got: $out1", out1.contains("FIRST"))
        write(s, "echo SECOND")
        val out2 = drain(s, 800)
        assertTrue("should get SECOND on same session (no respawn), got: $out2", out2.contains("SECOND"))
        s.close()
    }

    @Test
    fun p4_cwdContinuity() {
        val engine = openEngine()
        val s = openSession(engine)
        val dir = ctx.filesDir.absolutePath
        write(s, "cd $dir")
        drain(s, 400)
        write(s, "pwd")
        val out = drain(s, 800)
        assertTrue("pwd should be $dir, got: $out", out.contains(dir))
        s.close()
    }

    @Test
    fun p5_envContinuity() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "export AT_V3_TEST=LOCAL_PERSIST")
        drain(s, 400)
        write(s, "echo \$AT_V3_TEST")
        val out = drain(s, 800)
        assertTrue("env should persist, got: $out", out.contains("LOCAL_PERSIST"))
        s.close()
    }

    @Test
    fun p6_stdin() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "cat")
        drain(s, 400)
        write(s, "hello-stdin")
        s.write("\u0004".toByteArray()) // Ctrl-D
        val out = drain(s, 800)
        assertTrue("cat should receive stdin, got: $out", out.contains("hello-stdin"))
        s.close()
    }

    @Test
    fun p7_ctrlC() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "sleep 30")
        drain(s, 400)
        s.write(byteArrayOf(0x03)) // Ctrl-C
        val out = drain(s, 1200)
        // shell should survive; prompt should return (mksh prints ^C / 130).
        write(s, "echo AFTER_INT")
        val out2 = drain(s, 800)
        assertTrue("shell should survive Ctrl-C, got: $out2", out2.contains("AFTER_INT"))
        s.close()
    }

    @Test
    fun p8_resize() {
        val engine = openEngine()
        val s = openSession(engine)
        assertTrue("resize should succeed", s.resize(40, 120))
        write(s, "stty size")
        val out = drain(s, 800)
        assertTrue("stty size should reflect 40 120, got: $out",
            out.contains("40") && out.contains("120"))
        s.close()
    }

    @Test
    fun p9_processExit() {
        val engine = openEngine()
        val s = openSession(engine)
        write(s, "exit")
        val out = drain(s, 1200)
        // After exit, session should be EXITED (not FAILED/CLOSED).
        assertEquals(ATSessionState.EXITED, s.state)
        s.close()
    }

    @Test
    fun p10_explicitCloseAndCleanup() {
        val engine = openEngine()
        val s = openSession(engine)
        s.close()
        assertEquals(ATSessionState.CLOSED, s.state)
        // Second close is idempotent (no crash).
        s.close()
    }

    @Test
    fun oneshot_local() {
        val engine = openEngine()
        val r = engine.execute(ATExecutionRequest(command = "echo LOCAL_OK"))
        assertEquals(ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("stdout LOCAL_OK, got: ${r.stdout}", r.stdout.contains("LOCAL_OK"))
    }
}



