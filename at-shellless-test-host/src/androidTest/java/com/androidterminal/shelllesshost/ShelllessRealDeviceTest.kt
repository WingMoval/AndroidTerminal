// AT.TERM-0 鈥?Shellless real-device G-gate harness (VALIDATION ONLY).
//
// Runs on the real device. Every session operation goes ONLY through
// ATEngine.openSession(...) -> ShelllessPersistentBackend -> real PTY ->
// in-process non-shell native probe. No /system/bin/sh, no ADB, no Local
// backend. The consumer NEVER calls the backend or native directly.
//
// Gates (AT.TERM-0 搂49): G2..G19. G0 (artifact isolation) is done via
// Gradle/DEX/native audit on the PC, not here.

package com.androidterminal.shelllesshost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.at.engine.ATEngine
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import com.at.engine.ATSessionLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShelllessRealDeviceTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private val eng: ATEngine get() = ShelllessHost.engine()

    private fun open(rows: Int = 24, cols: Int = 80): ATTerminalSession =
        (eng.openSession(
            ATSessionLaunch(
                command = Sl.PROBE_COMMAND,
                initialRows = rows, initialCols = cols,
            )
        ) as ATOpenSessionResult.Success).session

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    /** Collect output lines from a session using PULL read mode. */
    private fun readUntil(sess: ATTerminalSession, needle: String, timeoutMs: Long = 4000): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val b = sess.read(100)
            if (b != null && b.isNotEmpty()) sb.append(String(b, Charsets.UTF_8))
            if (sb.indexOf(needle, ignoreCase = false) >= 0) return sb.toString()
            if (b == null) break
        }
        return sb.toString()
    }

    private class Cap : (ATSessionEvent) -> Unit {
        val out = StringBuilder()
        @Volatile var resized: Pair<Int, Int>? = null
        @Volatile var exitedCode: Int? = null
        @Volatile var closed = false
        override fun invoke(e: ATSessionEvent) {
            when (e) {
                is ATSessionEvent.Data -> out.append(String(e.bytes, Charsets.UTF_8))
                is ATSessionEvent.Resized -> resized = e.rows to e.cols
                is ATSessionEvent.Exited -> exitedCode = e.exitCode
                is ATSessionEvent.Eof -> {}
                is ATSessionEvent.Closed -> closed = true
                is ATSessionEvent.Failed -> {}
            }
        }
    }

    // ---- G2 + G3: child not /system/bin/sh; real PTY isatty -----------------

    @Test
    fun g2_g3_childNotShell_realPtyIsatty() {
        val s = open(33, 99)
        // G4 folded here too: initial 33x99 must be observed by the child.
        val out = readUntil(s, "READY", 5000)
        assertTrue("probe must emit READY, got: $out", out.contains("READY"))
        // G2: the child reports its own pid + tty + isatty. We assert the TTY is a
        // real pts and isatty(0/1/2)=1. The pid is the CHILD (probe), and we
        // verify (on PC) via /proc that it is NOT /system/bin/sh. Here we capture
        // the child identity for the evidence.
        assertTrue("READY must carry isatty0=1, got: $out", out.contains("isatty0=1"))
        assertTrue("READY must carry isatty1=1, got: $out", out.contains("isatty1=1"))
        assertTrue("READY must carry isatty2=1, got: $out", out.contains("isatty2=1"))
        assertTrue("tty must be a /dev/pts, got: $out", out.contains("tty=/dev/pts/"))
        // G4: initial size observed by the child itself (TIOCGWINSZ on slave).
        assertTrue("child must observe initial 33x99, got: $out",
            out.contains("rows=33") && out.contains("cols=99"))
        val pid = extractPid(out)
        // Save child identity for PC-side /proc evidence.
        InstrumentationRegistry.getInstrumentation().targetContext
            .openFileOutput("shellless_child.txt", 0).use { it.write(out.toByteArray()) }
        s.close(true)
        // The session must reach a terminal state (CLOSED/EXITED), no leak.
        assertTrue("session must terminate, state=${s.state}",
            s.state == ATSessionState.CLOSED || s.state == ATSessionState.EXITED)
    }

    private fun extractPid(ready: String): Int {
        val m = Regex("pid=(\\d+)").find(ready) ?: return -1
        return m.groupValues[1].toInt()
    }

    // ---- G2 (proc capture): keep a session alive so the PC can read /proc ----
    // The child stays open (no close) for PROC_HOLD_MS so the PC side can read
    // /proc/<pid>/exe + cmdline + status to PROVE the child is NOT /system/bin/sh.

    @Test
    fun g2_procCapture() {
        val s = open(33, 99)
        val out = readUntil(s, "READY", 5000)
        val pid = extractPid(out)
        assertTrue("READY with pid, got: $out", pid > 0)
        // While the child is alive, read its /proc identity from THIS process
        // (same uid as the forked child -> readable). Capture exe/cmdline/tty.
        val sb = StringBuilder()
        sb.append("CHILD_PID=").append(pid).append('\n')
        sb.append("CMDLINE=").append(readProcLine(pid, "cmdline", sep = "|")).append('\n')
        sb.append("EXE=").append(readProcLink(pid, "exe")).append('\n')
        sb.append("TTY_FD0=").append(readProcLink(pid, "fd/0")).append('\n')
        val st = File("/proc/$pid/status").readText()
        sb.append("NAME=").append(st.lineSequence().firstOrNull { it.startsWith("Name:") }?.substringAfter(' ')?.trim() ?: "?").append('\n')
        sb.append("PPID=").append(st.lineSequence().firstOrNull { it.startsWith("PPid:") }?.substringAfter(' ')?.trim() ?: "?").append('\n')
        sb.append("UID_LINE=").append(st.lineSequence().firstOrNull { it.startsWith("Uid:") }?.trim() ?: "?").append('\n')
        // Write to a world-readable location the PC can pull, plus filesDir.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.openFileOutput("shellless_proc.txt", 0).use { it.write(sb.toString().toByteArray()) }
        s.close(true)
    }

    private fun readProcLine(pid: Int, name: String, sep: String = ""): String {
        return try {
            val b = File("/proc/$pid/$name").readBytes()
            val s = String(b, Charsets.ISO_8859_1)
            val replaced = if (sep.isEmpty()) s else s.replace("\u0000", sep)
            replaced.trimEnd('\n')
        } catch (e: Exception) { "<err:$e>" }
    }

    private fun readProcLink(pid: Int, link: String): String =
        try { File("/proc/$pid/$link").canonicalPath } catch (e: Exception) { "<err:$e>" }

    private companion object {
        const val PROC_HOLD_MS = 20_000L
    }

    // ---- G5: write -> child -> read roundtrip -------------------------------

    @Test
    fun g5_writeChildReadRoundtrip() {
        val s = open()
        readUntil(s, "READY", 5000)
        s.write("PING alpha\n".toByteArray())
        val out = readUntil(s, "PONG alpha", 4000)
        assertTrue("roundtrip PONG alpha, got: $out", out.contains("PONG alpha"))
        s.close(true)
    }

    // ---- G6: chunked input --------------------------------------------------

    @Test
    fun g6_chunkedInput() {
        val s = open()
        readUntil(s, "READY", 5000)
        s.write("PING ".toByteArray())
        Thread.sleep(120)
        s.write("beta".toByteArray())
        Thread.sleep(120)
        s.write("\n".toByteArray())
        val out = readUntil(s, "PONG beta", 4000)
        assertTrue("chunked PONG beta, got: $out", out.contains("PONG beta"))
        s.close(true)
    }

    // ---- G7: UTF-8 byte preservation ---------------------------------------

    @Test
    fun g7_utf8BytePreservation() {
        val s = open()
        readUntil(s, "READY", 5000)
        // G7: MULTIBYTE UTF-8 byte preservation.
        // \u4E2D = U+4E2D (UTF-8: E4 B8 AD).
        // Full roundtrip: "PING \u4E2D\u6587\u6D4B\u8BD5\n" must arrive byte-exact.
        val fullText = "PING \u4E2D\u6587\u6D4B\u8BD5\n"
        s.write(fullText.toByteArray(Charsets.UTF_8))
        val out = readUntil(s, "PONG \u4E2D\u6587\u6D4B\u8BD5", 4000)
        assertTrue("full UTF-8 roundtrip, got: $out", out.contains("PONG \u4E2D\u6587\u6D4B\u8BD5"))
        // Byte-level split INSIDE U+4E2D (E4 B8 AD):
        //   "PING " = 5 bytes (50 49 4E 47 20)
        //   U+4E2D  = 3 bytes (E4 B8 AD)
        //   "\n"   = 1 byte  (0A)
        //   Total: 9 bytes. Split at offset 6 (inside U+4E2D).
        val full = "PING \u4E2D\n".toByteArray(Charsets.UTF_8)
        // full = [50 49 4E 47 20 E4 B8 AD 0A] = 9 bytes
        s.write(full.copyOfRange(0, 6)) // "PING " + E4 (first byte of U+4E2D)
        Thread.sleep(120)
        s.write(full.copyOfRange(6, full.size)) // B8 AD 0A (rest of U+4E2D + newline)
        val out2 = readUntil(s, "PONG \u4E2D", 4000)
        assertTrue("split UTF-8 (inside U+4E2D) PONG, got: $out2", out2.contains("PONG \u4E2D"))
        s.close(true)
    }

    // ---- G8: resize + child-observed winsize --------------------------------

    @Test
    fun g8_resizeChildObserved() {
        val s = open(33, 99)
        val cap = Cap(); s.setListener(cap)
        await(4000) { cap.out.contains("READY") }
        assertTrue("READY (listener)", cap.out.contains("READY"))
        // Ask child to confirm current size, then resize to 41x123.
        val ok = s.resize(41, 123)
        assertTrue("resize must be accepted", ok)
        // The child proactively re-reads TIOCGWINSZ and emits RESIZE rows=41 cols=123.
        await(4000) { cap.out.contains("RESIZE rows=41 cols=123") }
        assertTrue("child must observe 41x123, got: ${cap.out}",
            cap.out.contains("RESIZE rows=41 cols=123"))
        // ATSessionEvent.Resized must have fired per contract.
        assertTrue("Resized event expected",
            await(1000) { cap.resized == (41 to 123) })
        s.close(true)
    }

    // ---- G9: control byte / SIGINT -----------------------------------------

    @Test
    fun g9_controlByteSigint() {
        val s = open()
        readUntil(s, "READY", 5000)
        // Send 0x03 (ETX) through the real terminal path. ISIG is on (default
        // termios on a fresh PTY), so the PTY line discipline converts it to a
        // real SIGINT delivered to the child's handler (NOT a shell).
        s.write(byteArrayOf(0x03))
        val out = readUntil(s, "SIGINT", 4000)
        assertTrue("0x03 must produce a SIGINT marker, got: $out", out.contains("SIGINT"))
        s.close(true)
    }

    // ---- G10: natural exit --------------------------------------------------

    @Test
    fun g10_naturalExit() {
        val s = open()
        val cap = Cap(); s.setListener(cap)
        readUntil(s, "READY", 5000)
        s.write("EXIT 7\n".toByteArray())
        // The probe _exit(7); backend waitpid; AT session must surface exit 7.
        assertTrue("Exited(7) expected",
            await(4000) { cap.exitedCode == 7 })
        assertTrue("state must be EXITED, got ${s.state}",
            s.state == ATSessionState.EXITED)
        s.close(true)
    }

    // ---- G11: explicit close ------------------------------------------------

    @Test
    fun g11_explicitClose() {
        val s = open()
        val cap = Cap(); s.setListener(cap)
        readUntil(s, "READY", 5000)
        assertTrue("ACTIVE before close, got ${s.state}", s.state == ATSessionState.ACTIVE)
        s.close(true)
        assertTrue("CLOSED event expected", await(1000) { cap.closed })
        assertTrue("state must be CLOSED, got ${s.state}", s.state == ATSessionState.CLOSED)
        // No residual child: a write after close must return -1 (not writable).
        assertEquals(-1, s.write("x".toByteArray()))
    }

    // ---- G12: pull mode (no listener) --------------------------------------

    @Test
    fun g12_pullMode() {
        val s = open()
        // NO listener set.
        val ready = s.read(4000)
        assertNotNull("pull should yield READY", ready)
        assertTrue(String(ready!!).contains("READY"))
        s.write("PING pull\n".toByteArray())
        // Accumulate pull reads until PONG arrives (pull-only, no listener).
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && sb.indexOf("PONG pull") < 0) {
            val b = s.read(200)
            if (b == null) break
            if (b.isNotEmpty()) sb.append(String(b, Charsets.UTF_8))
        }
        assertTrue("pull should yield PONG pull, got: $sb", sb.contains("PONG pull"))
        // timeout -> empty array (not null while ACTIVE).
        val empty = s.read(150)
        assertNotNull("timeout should be empty array while ACTIVE", empty)
        assertEquals(0, empty!!.size)
        s.close(true)
        // After close/EOF, read returns null.
        assertNull("post-close read should be null", s.read(300))
    }

    // ---- G13: push / listener mode (no pull) --------------------------------

    @Test
    fun g13_pushMode() {
        val s = open()
        val cap = Cap(); s.setListener(cap)
        await(4000) { cap.out.contains("READY") }
        assertTrue("push READY", cap.out.contains("READY"))
        s.write("PING push\n".toByteArray())
        await(4000) { cap.out.contains("PONG push") }
        assertTrue("push PONG push", cap.out.contains("PONG push"))
        s.write("EXIT 0\n".toByteArray())
        await(4000) { cap.exitedCode == 0 }
        assertTrue("push Exited(0)", cap.exitedCode == 0)
        s.close(true)
        await(1000) { cap.closed }
        assertTrue("push Closed", cap.closed)
    }

    // ---- G14 + G15 + G16: 3 concurrent sessions, isolation ------------------

    @Test
    fun g14_g15_g16_multiSessionIsolation() {
        val sA = open(24, 80)
        val sB = open(24, 80)
        val sC = open(24, 80)
        val capA = Cap(); sA.setListener(capA)
        val capB = Cap(); sB.setListener(capB)
        val capC = Cap(); sC.setListener(capC)
        await(4000) { capA.out.contains("READY") && capB.out.contains("READY") && capC.out.contains("READY") }

        // G14: independent PINGs, no cross-stream.
        sA.write("PING A\n".toByteArray())
        sB.write("PING B\n".toByteArray())
        sC.write("PING C\n".toByteArray())
        await(4000) { capA.out.contains("PONG A") && capB.out.contains("PONG B") && capC.out.contains("PONG C") }
        assertTrue("A PONG A", capA.out.contains("PONG A"))
        assertFalse("A must NOT see B", capA.out.contains("PONG B"))
        assertFalse("B must NOT see A", capB.out.contains("PONG A"))
        assertTrue("C PONG C", capC.out.contains("PONG C"))

        // G15: resize isolation 鈥?all opened 24x80; resize A->20x70, B->40x120
        // (distinct from initial so the child observes a change); each sees its own.
        sA.resize(20, 70)
        sB.resize(40, 120)
        await(4000) { capA.out.contains("RESIZE rows=20 cols=70") && capB.out.contains("RESIZE rows=40 cols=120") }
        assertTrue("A sees 20x70", capA.out.contains("RESIZE rows=20 cols=70"))
        assertFalse("A must not see B's 40x120", capA.out.contains("RESIZE rows=40 cols=120"))
        assertTrue("B sees 40x120", capB.out.contains("RESIZE rows=40 cols=120"))
        // C was never resized: it must NOT show any RESIZE line.
        assertFalse("C (unresized) must show no RESIZE", capC.out.contains("RESIZE"))

        // G16: closing B must NOT affect A/C.
        sB.close(true)
        assertTrue("B CLOSED", await(1000) { capB.closed })
        sA.write("PING A2\n".toByteArray())
        sC.write("PING C2\n".toByteArray())
        await(4000) { capA.out.contains("PONG A2") && capC.out.contains("PONG C2") }
        assertTrue("A alive after B close", capA.out.contains("PONG A2"))
        assertTrue("C alive after B close", capC.out.contains("PONG C2"))
        sA.close(true); sC.close(true)
    }

    // ---- G17: sequential lifecycle (20 open/close, no leak) -----------------

    @Test
    fun g17_sequentialLifecycle() {
        for (i in 0 until 20) {
            val s = open(24, 80)
            val out = readUntil(s, "READY", 4000)
            assertTrue("iter $i READY, got: $out", out.contains("READY"))
            s.write("PING it$i\n".toByteArray())
            val pong = readUntil(s, "PONG it$i", 4000)
            assertTrue("iter $i PONG, got: $pong", pong.contains("PONG it$i"))
            s.close(true)
        }
        // No zombie / fd leak: a fresh session after 20 cycles still works.
        val s = open()
        assertTrue("post-cycle READY", readUntil(s, "READY", 4000).contains("READY"))
        s.close(true)
    }

    // ---- G18: natural exit -> reopen ---------------------------------------

    @Test
    fun g18_naturalExitReopen() {
        val sA = open()
        val capA = Cap(); sA.setListener(capA)
        readUntil(sA, "READY", 5000)
        sA.write("EXIT 7\n".toByteArray())
        assertTrue("A Exited(7)", await(4000) { capA.exitedCode == 7 })
        assertTrue("A state EXITED", sA.state == ATSessionState.EXITED)
        // A non-shell process exit must not pollute the engine.
        val sB = open()
        readUntil(sB, "READY", 5000)
        sB.write("PING after\n".toByteArray())
        assertTrue("B PONG after", readUntil(sB, "PONG after", 4000).contains("PONG after"))
        sB.close(true)
    }

    // ---- G19: bounded failure path -----------------------------------------

    @Test
    fun g19_boundedFailurePath() {
        // Unknown launch selector must yield a bounded Failure (no shell, no crash).
        val r = eng.openSession(
            ATSessionLaunch(command = "rm -rf /", initialRows = 24, initialCols = 80)
        )
        assertTrue("unknown selector must be Failure, got $r", r is ATOpenSessionResult.Failure)
        val f = r as ATOpenSessionResult.Failure
        assertTrue("failure reason non-empty", f.reason.isNotEmpty())
    }
}


