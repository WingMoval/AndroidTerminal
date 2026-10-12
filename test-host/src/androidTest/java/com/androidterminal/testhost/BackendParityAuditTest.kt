// Test Host — AT.V3-3 Backend Parity Audit (Local vs ADB).
//
// Audit-only harness: the SAME AT Engine contract checks are executed against
// BOTH real Execution Providers (Local Backend -> Local PTY, ADB Backend ->
// ADB Engine -> adbd). Nothing here changes the AT contract; the purpose is to
// produce a truthful parity matrix + evidence for AT.V3-3.
//
// Semantics exercised through the AT Engine facade only:
//   ATEngine.execute / openSession  ->  ATExecutionResult / ATTerminalSession
//
// PTY-echo note: both providers are real PTYs, so typed command lines are
// echoed. Assertions therefore use arithmetic expansion ($((1+1))) or
// command-substitution forms whose RESOLVED value never appears in the echoed
// literal command text.

package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbEngine
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.backend.local.LocalOneShotBackend
import com.at.backend.local.LocalPersistentBackend
import com.at.engine.ATEngine
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATOneShotBackend
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackendParityAuditTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext

    private val host get() = "127.0.0.1"
    private val port get() = (InstrumentationRegistry.getArguments().getString("port") ?: "38793").toInt()
    private val keyDir get() = InstrumentationRegistry.getArguments().getString("keyDir")
        ?: ctx.filesDir.absolutePath

    private fun conn() = AdbConnectionRequest(host, port, keyDir)

    // --- AT Engine facades (composition root = test consumer) ---

    private fun localEngine() = ATEngine(LocalOneShotBackend(), LocalPersistentBackend())
    private fun adbEngine() = ATEngine(AdbOneShotBackend(conn()), AdbPersistentBackend(conn()))

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    private class Cap : (ATSessionEvent) -> Unit {
        val out = StringBuilder()
        var eof = false
        var failed: String? = null
        var exited: Int? = null
        var closed = false
        var resized: Pair<Int, Int>? = null
        override fun invoke(ev: ATSessionEvent) {
            when (ev) {
                is ATSessionEvent.Data -> out.append(String(ev.bytes))
                is ATSessionEvent.Eof -> eof = true
                is ATSessionEvent.Failed -> failed = ev.error
                is ATSessionEvent.Exited -> exited = ev.exitCode
                is ATSessionEvent.Closed -> closed = true
                is ATSessionEvent.Resized -> resized = ev.rows to ev.cols
            }
        }
    }

    // --- provider specs for persistent parity ---

    private data class PSpec(
        val label: String,
        val engine: () -> ATEngine,
        val dirA: String,
        val dirB: String,
    )

    private fun localSpec() = PSpec("LOCAL", ::localEngine, ctx.filesDir.absolutePath, "/")
    private fun adbSpec() = PSpec("ADB", ::adbEngine, "/data/local/tmp", "/system")

    private fun openWithListener(spec: PSpec): Triple<ATTerminalSession, Cap, ATEngine> {
        val engine = spec.engine()
        val r = engine.openSession(ATPersistentBackend.Launch(command = "", initialRows = 24, initialCols = 80))
        assertTrue("${spec.label}: open should succeed, got $r", r is ATOpenSessionResult.Success)
        val s = (r as ATOpenSessionResult.Success).session
        val cap = Cap()
        s.setListener(cap)
        return Triple(s, cap, engine)
    }

    private fun write(s: ATTerminalSession, text: String) { s.write((text + "\n").toByteArray()) }

    // ==================================================================
    // One-shot parity — O1..O5 (both providers)
    // ==================================================================

    private fun o1(label: String, e: ATEngine) {
        val r = e.execute(ATExecutionRequest(command = "echo hello"))
        assertEquals("$label O1 outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("$label O1 stdout", r.stdout.contains("hello"))
        assertEquals("$label O1 exit", 0, r.exitCode)
        assertEquals("$label O1 stderr empty", "", r.stderr)
        assertTrue("$label O1 !hasError", !r.hasError)
        assertEquals("$label O1 error null", null, r.error)
    }

    private fun o2(label: String, e: ATEngine) {
        val r = e.execute(ATExecutionRequest(command = "printf ERRONLY 1>&2"))
        assertEquals("$label O2 outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("$label O2 stdout empty", "", r.stdout)
        assertEquals("$label O2 stderr", "ERRONLY", r.stderr)
        assertEquals("$label O2 exit", 0, r.exitCode)
    }

    private fun o3(label: String, e: ATEngine) {
        val r = e.execute(ATExecutionRequest(command = "exit 7"))
        assertEquals("$label O3 non-zero exit != failure", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("$label O3 exit", 7, r.exitCode)
        assertTrue("$label O3 !hasError", !r.hasError)
    }

    private fun o4(label: String, e: ATEngine) {
        val r = e.execute(ATExecutionRequest(command = "printf OUTO4; printf ERRO4 1>&2; exit 9"))
        assertEquals("$label O4 outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("$label O4 stdout", "OUTO4", r.stdout)
        assertEquals("$label O4 stderr", "ERRO4", r.stderr)
        assertEquals("$label O4 exit", 9, r.exitCode)
        assertTrue("$label O4 !hasError", !r.hasError)
    }

    private fun o5(label: String, e: ATEngine) {
        val r = e.execute(ATExecutionRequest(command = "true"))
        assertEquals("$label O5 outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("$label O5 stdout empty", "", r.stdout)
        assertEquals("$label O5 stderr empty", "", r.stderr)
        assertEquals("$label O5 exit", 0, r.exitCode)
    }

    @Test fun o1_smallStdout() { o1("LOCAL", localEngine()); o1("ADB", adbEngine()) }
    @Test fun o2_stderrSeparation() { o2("LOCAL", localEngine()); o2("ADB", adbEngine()) }
    @Test fun o3_explicitNonZero() { o3("LOCAL", localEngine()); o3("ADB", adbEngine()) }
    @Test fun o4_stdoutStderrNonzero() { o4("LOCAL", localEngine()); o4("ADB", adbEngine()) }
    @Test fun o5_noOutputSuccess() { o5("LOCAL", localEngine()); o5("ADB", adbEngine()) }

    // ==================================================================
    // Large output (ADB one-shot targeted audit) + Local baseline
    // ==================================================================

    private fun gen(n: Int) = "i=0; while [ \$i -lt $n ]; do printf '%064d\\n' \$i; i=\$((i+1)); done"

    private fun largeOutputCheck(label: String, e: ATEngine, n: Int) {
        val expectedBytes = n * 65 // 64 digits + newline
        val lastLine = (n - 1).toString().padStart(64, '0')  // loop runs i=0..n-1
        val r = e.execute(ATExecutionRequest(command = gen(n)))
        assertEquals("$label large($n) outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("$label large($n) exit", 0, r.exitCode)
        assertEquals("$label large($n) byte count", expectedBytes, r.stdout.length)
        assertEquals("$label large($n) line count", n, r.stdout.count { it == '\n' })
        assertTrue("$label large($n) tail intact", r.stdout.endsWith(lastLine + "\n"))
        // connection/provider remains reusable after the large transfer
        val again = e.execute(ATExecutionRequest(command = "echo after_large_$n"))
        assertEquals("$label reuse outcome", ATExecutionOutcome.COMPLETED, again.outcome)
        assertTrue("$label reuse stdout", again.stdout.contains("after_large_$n"))
    }

    @Test fun l1_localLargeOutputBaseline64k() { largeOutputCheck("LOCAL", localEngine(), 1024) }

    // AT.V3-2.2: bounded timeouts (flow-control fixed; the old 300s windows
    // existed to wait out the stall and are no longer appropriate).
    @Test(timeout = 60_000)
    fun l2_adbLargeOutput64k() { largeOutputCheck("ADB", adbEngine(), 1024) }

    @Test(timeout = 180_000)
    fun l3_adbLargeOutput256k() { largeOutputCheck("ADB", adbEngine(), 4096) }

    // 1 MiB: the device's adbd delivers ~70B per WRTE with a per-WRTE credit
    // round trip of ~19ms (measured), so 16384 lines take ~5 min. The timeout
    // is the device's normal bounded completion time, not a deadlock wait.
    @Test(timeout = 600_000)
    fun l4_adbLargeOutput1m() { largeOutputCheck("ADB", adbEngine(), 16384) }

    // Mixed large stdout + non-empty stderr + non-zero exit: the flow-control
    // fix must not disturb shell-v2 frame mapping (stdout/stderr/exit frames).
    @Test(timeout = 90_000)
    fun l6_adbMixedLargeStdoutStderrNonzero() {
        val e = adbEngine()
        val n = 512
        val expectedBytes = n * 65
        val lastLine = (n - 1).toString().padStart(64, '0')
        val r = e.execute(ATExecutionRequest(
            command = gen(n) + "; printf ERRLARGE 1>&2; exit 5"))
        assertEquals("L6 outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("L6 stdout bytes", expectedBytes, r.stdout.length)
        assertEquals("L6 stdout lines", n, r.stdout.count { it == '\n' })
        assertTrue("L6 tail intact", r.stdout.endsWith(lastLine + "\n"))
        assertEquals("L6 stderr", "ERRLARGE", r.stderr)
        assertEquals("L6 exit", 5, r.exitCode)
        assertTrue("L6 non-zero exit != transport failure", !r.hasError)
    }

    // Connection reuse across interleaved small/large one-shots: must stay on
    // ONE physical connection / ONE TLS handshake (counted from logcat).
    @Test(timeout = 300_000)
    fun l7_adbConnectionReuseWithLargeOutput() {
        val e = adbEngine()
        val seq: List<Pair<String, Int>> = listOf(
            "S1" to 0, "large" to 1024, "S2" to 0, "large" to 4096, "S3" to 0)
        for ((name, n) in seq) {
            if (n == 0) {
                val r = e.execute(ATExecutionRequest(command = "echo $name"))
                assertEquals("L7 $name outcome", ATExecutionOutcome.COMPLETED, r.outcome)
                assertTrue("L7 $name stdout", r.stdout.contains(name))
            } else {
                val expectedBytes = n * 65
                val lastLine = (n - 1).toString().padStart(64, '0')
                val r = e.execute(ATExecutionRequest(command = gen(n)))
                assertEquals("L7 large($n) outcome", ATExecutionOutcome.COMPLETED, r.outcome)
                assertEquals("L7 large($n) bytes", expectedBytes, r.stdout.length)
                assertTrue("L7 large($n) tail", r.stdout.endsWith(lastLine + "\n"))
            }
        }
        assertTrue("L7 connection still ONLINE", AdbEngine.connectionState().isOnline)
    }

    // 1 MiB via a single pipe stream (large WRTE chunks). The line-based 1 MiB
    // (16384 lines) is bounded by the device's wireless debugging session
    // lifetime (~2-4 min) at ~19ms/line credit round trip (~5-6 min needed) —
    // an environmental limit recorded in evidence. This chunked variant
    // delivers 1 MiB in ~16 large WRTE and proves the credit flow at 1 MiB
    // scale with full completeness checks.
    @Test(timeout = 120_000)
    fun l9_adbLargeOutput1mChunked() {
        val e = adbEngine()
        val r = e.execute(ATExecutionRequest(
            command = "dd if=/dev/zero bs=1048576 count=1 2>/dev/null | tr '\\000' 'A'; printf TAIL1M"))
        assertEquals("L9 outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        assertEquals("L9 bytes", 1048582, r.stdout.length)
        assertTrue("L9 tail marker", r.stdout.endsWith("TAIL1M"))
        assertEquals("L9 exit", 0, r.exitCode)
        val again = e.execute(ATExecutionRequest(command = "echo after_1m_chunked"))
        assertEquals("L9 reuse outcome", ATExecutionOutcome.COMPLETED, again.outcome)
        assertTrue("L9 reuse stdout", again.stdout.contains("after_1m_chunked"))
    }

    // Differential control for the one-shot stall: the SAME device command is
    // executed through the PERSISTENT path (interactive stream, ack_writes=true).
    // If this delivers the full 64 KiB while the one-shot stalls, the missing
    // WRTE/OKAY credit on the one-shot path is proven as the root cause.
    @Test(timeout = 300_000)
    fun l5_adbLargeOutputViaPersistentPath64k() {
        val engine = adbEngine()
        val r = engine.openSession(ATPersistentBackend.Launch(command = ""))
        assertTrue("open", r is ATOpenSessionResult.Success)
        val s = (r as ATOpenSessionResult.Success).session
        val cap = Cap()
        s.setListener(cap)
        Thread.sleep(500) // let prompt arrive
        s.write((genDone(1024) + "\n").toByteArray())
        // Wait for the RESOLVED marker ("LARGE_DONE=2"); the echoed command
        // line only contains the literal "$((1+1))".
        assertTrue("persistent large output completes",
            await(120_000) { cap.out.contains("LARGE_DONE=2") })
        val bytes = cap.out.length
        android.util.Log.i("ATV33", "l5: persistent-path large output bytes=$bytes")
        assertTrue("persistent path delivered >= 64KiB data (got $bytes)", bytes >= 65536)
        s.close()
    }

    private fun genDone(n: Int) = gen(n) + "; echo LARGE_DONE=\$((1+1))"

    // ==================================================================
    // Persistent parity — p1..p7 (both providers)
    // ==================================================================

    private fun p1_openActiveWrite(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        assertEquals("${spec.label} P1 ACTIVE", ATSessionState.ACTIVE, s.state)
        write(s, "echo PP1=\$((1+1))")
        assertTrue("${spec.label} P1 output", await(5000) { cap.out.contains("PP1=2") })
        s.close()
        assertEquals("${spec.label} P1 closed", ATSessionState.CLOSED, s.state)
    }

    @Test fun p1_openActiveWrite() { p1_openActiveWrite(localSpec()); p1_openActiveWrite(adbSpec()) }

    private fun p2_cwdEnvContinuity(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        write(s, "export AT_V33=VAL_${spec.label}")
        write(s, "cd ${spec.dirA}")
        Thread.sleep(400)
        // string probe (PTY echo hides only the literal, not the resolved value)
        write(s, "echo ENVS=[\$AT_V33]")
        write(s, "echo CWDP=[\$(pwd)]")
        assertTrue("${spec.label} P2 env", await(5000) { cap.out.contains("ENVS=[VAL_${spec.label}]") })
        assertTrue("${spec.label} P2 cwd", await(5000) { cap.out.contains("CWDP=[${spec.dirA}]") })
        s.close()
    }

    @Test fun p2_cwdEnvContinuity() { p2_cwdEnvContinuity(localSpec()); p2_cwdEnvContinuity(adbSpec()) }

    private fun p3_stdin(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        write(s, "cat")
        Thread.sleep(400)
        write(s, "STDIN_MARKER_${spec.label}")
        assertTrue("${spec.label} P3 stdin", await(5000) { cap.out.contains("STDIN_MARKER_${spec.label}") })
        s.write(byteArrayOf(0x04)) // Ctrl-D ends cat
        s.close()
    }

    @Test fun p3_stdin() { p3_stdin(localSpec()); p3_stdin(adbSpec()) }

    private fun p4_ctrlC(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        write(s, "sleep 30")
        Thread.sleep(600)
        s.write(byteArrayOf(0x03))
        Thread.sleep(400)
        write(s, "echo CC=\$((3+3))")
        assertTrue("${spec.label} P4 Ctrl-C recovery", await(5000) { cap.out.contains("CC=6") })
        assertEquals("${spec.label} P4 still ACTIVE", ATSessionState.ACTIVE, s.state)
        s.close()
    }

    @Test fun p4_ctrlC() { p4_ctrlC(localSpec()); p4_ctrlC(adbSpec()) }

    private fun p5_resize(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        assertTrue("${spec.label} P5 resize accepted", s.resize(40, 120))
        Thread.sleep(300)
        write(s, "echo RSZ=[\$(stty size)]")
        assertTrue("${spec.label} P5 stty", await(5000) { cap.out.contains("RSZ=[40 120]") })
        s.close()
    }

    @Test fun p5_resize() { p5_resize(localSpec()); p5_resize(adbSpec()) }

    private fun p6_naturalExit(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        write(s, "echo PRE=[\$(pwd)]")
        assertTrue("${spec.label} P6 pre", await(5000) { cap.out.contains("PRE=[") })
        write(s, "exit")
        assertTrue("${spec.label} P6 EXITED", await(5000) { s.state == ATSessionState.EXITED })
        assertTrue("${spec.label} P6 Eof event", cap.eof)
        assertTrue("${spec.label} P6 no Failed event", cap.failed == null)
        android.util.Log.i("ATV33", "p6 ${spec.label}: exitedEvent=${cap.exited} eof=${cap.eof} failed=${cap.failed}")
        s.close()
    }

    @Test fun p6_naturalExit() { p6_naturalExit(localSpec()); p6_naturalExit(adbSpec()) }

    private fun p7_closeReopen(spec: PSpec) {
        val engine = spec.engine()
        val cap = Cap()
        val r1 = engine.openSession(ATPersistentBackend.Launch(command = ""))
        val s1 = (r1 as ATOpenSessionResult.Success).session
        s1.setListener(cap)
        write(s1, "echo CL1=\$((5+5))")
        assertTrue("${spec.label} P7 first", await(5000) { cap.out.contains("CL1=10") })
        s1.close()
        assertEquals("${spec.label} P7 CLOSED", ATSessionState.CLOSED, s1.state)

        val cap2 = Cap()
        val r2 = engine.openSession(ATPersistentBackend.Launch(command = ""))
        assertTrue("${spec.label} P7 reopen succeeds", r2 is ATOpenSessionResult.Success)
        val s2 = (r2 as ATOpenSessionResult.Success).session
        s2.setListener(cap2)
        write(s2, "echo CL2=\$((6+6))")
        assertTrue("${spec.label} P7 reopen works", await(5000) { cap2.out.contains("CL2=12") })
        s2.close()
    }

    @Test fun p7_closeReopen() { p7_closeReopen(localSpec()); p7_closeReopen(adbSpec()) }

    // Boundary probe: does openSession(Launch(initialRows/Cols)) actually apply
    // the requested size at open? (Contract text: "Terminal size at open".)
    @Test fun p8_launchInitialSizeApplied() {
        // ADB: AdbPersistentBackend applies launch size at open.
        run {
            val engine = adbEngine()
            val r = engine.openSession(ATPersistentBackend.Launch(command = "", initialRows = 33, initialCols = 99))
            val s = (r as ATOpenSessionResult.Success).session
            val cap = Cap()
            s.setListener(cap)
            Thread.sleep(500)
            write(s, "echo SZ=[\$(stty size)]")
            assertTrue("ADB applies launch initial size", await(5000) { cap.out.contains("SZ=[33 99]") })
            s.close()
        }
        // LOCAL: record actual behavior (finding: LocalPersistentBackend does
        // not apply launch sizes; explicit resize() works — see P5).
        run {
            val engine = localEngine()
            val r = engine.openSession(ATPersistentBackend.Launch(command = "", initialRows = 33, initialCols = 99))
            val s = (r as ATOpenSessionResult.Success).session
            val cap = Cap()
            s.setListener(cap)
            Thread.sleep(500)
            write(s, "echo SZ=[\$(stty size)]")
            Thread.sleep(1500)
            android.util.Log.i("ATV33", "p8 LOCAL launch-size raw output: " + cap.out.toString().replace("\n", "\\n").replace("\r", "\\r"))
            // explicit resize still works as the documented workaround
            assertTrue(s.resize(33, 99))
            Thread.sleep(300)
            cap.out.setLength(0)
            write(s, "echo SZ2=[\$(stty size)]")
            assertTrue("LOCAL explicit resize works", await(5000) { cap.out.contains("SZ2=[33 99]") })
            s.close()
        }
    }

    // ==================================================================
    // EOF / state and close semantics — e1/e2
    // ==================================================================

    private fun e1_eofState(spec: PSpec) {
        // pull mode (no listener) to observe read()==null semantics directly
        val engine = spec.engine()
        val r = engine.openSession(ATPersistentBackend.Launch(command = ""))
        val s = (r as ATOpenSessionResult.Success).session
        // drain prompt
        s.read(500)
        s.write("echo EPRE\n".toByteArray())
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !sb.contains("EPRE")) {
            val b = s.read(200)
            if (b != null && b.isNotEmpty()) sb.append(String(b))
        }
        assertTrue("${spec.label} E1 pre", sb.contains("EPRE"))
        s.write("exit\n".toByteArray())
        var sawNull = false
        val deadline2 = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline2) {
            val b = s.read(200)
            if (b == null) { sawNull = true; break }
        }
        assertTrue("${spec.label} E1 read()==null after natural exit", sawNull)
        assertEquals("${spec.label} E1 state EXITED", ATSessionState.EXITED, s.state)
        val after = s.read(200)
        assertEquals("${spec.label} E1 read null persists", null, after)
        assertEquals("${spec.label} E1 write rejected", -1, s.write("x\n".toByteArray()))
        s.close()
    }

    @Test fun e1_eofState() { e1_eofState(localSpec()); e1_eofState(adbSpec()) }

    private fun e2_closeSemantics(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        write(s, "echo CLOSEPRE")
        assertTrue("${spec.label} E2 pre", await(5000) { cap.out.contains("CLOSEPRE") })

        val t0 = System.currentTimeMillis()
        s.close()
        val dt = System.currentTimeMillis() - t0
        android.util.Log.i("ATV33", "e2 ${spec.label}: close() bounded in ${dt}ms")

        assertEquals("${spec.label} E2 state CLOSED", ATSessionState.CLOSED, s.state)
        assertTrue("${spec.label} E2 close bounded (${dt}ms)", dt < 2500)
        assertTrue("${spec.label} E2 Closed event", cap.closed)
        assertEquals("${spec.label} E2 read after close null", null, s.read(100))
        assertEquals("${spec.label} E2 write after close -1", -1, s.write("x\n".toByteArray()))
        assertTrue("${spec.label} E2 resize after close false", !s.resize(30, 90))
        s.close() // repeated close: idempotent, must not crash
        assertEquals("${spec.label} E2 still CLOSED", ATSessionState.CLOSED, s.state)
    }

    @Test fun e2_closeSemantics() { e2_closeSemantics(localSpec()); e2_closeSemantics(adbSpec()) }

    // ==================================================================
    // Multi-session parity — m1/m2/m3
    // ==================================================================

    private fun m1_activeEnvCwdIsolation(spec: PSpec) {
        val engine = spec.engine()
        val sessions = (0..2).map {
            val r = engine.openSession(ATPersistentBackend.Launch(command = ""))
            assertTrue("${spec.label} M1 open $it", r is ATOpenSessionResult.Success)
            val s = (r as ATOpenSessionResult.Success).session
            val cap = Cap()
            s.setListener(cap)
            s to cap
        }
        val (a, capA) = sessions[0]
        val (b, capB) = sessions[1]
        val (c, capC) = sessions[2]
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, b.state)
        assertEquals(ATSessionState.ACTIVE, c.state)

        write(a, "export AT_REMOTE=11")
        write(a, "cd ${spec.dirA}")
        write(b, "export AT_REMOTE=22")
        write(b, "cd ${spec.dirB}")
        Thread.sleep(400)
        write(a, "echo ENVA=\$((AT_REMOTE+0))")
        write(a, "echo CWDA=[\$(pwd)]")
        write(b, "echo ENVB=\$((AT_REMOTE+0))")
        write(b, "echo CWDB=[\$(pwd)]")
        write(c, "echo ENVC=[\$AT_REMOTE]")

        assertTrue("${spec.label} M1 A env", await(5000) { capA.out.contains("ENVA=11") })
        assertTrue("${spec.label} M1 A cwd", await(5000) { capA.out.contains("CWDA=[${spec.dirA}]") })
        assertTrue("${spec.label} M1 B env", await(5000) { capB.out.contains("ENVB=22") })
        assertTrue("${spec.label} M1 B cwd", await(5000) { capB.out.contains("CWDB=[${spec.dirB}]") })
        assertTrue("${spec.label} M1 C untouched", await(5000) { capC.out.contains("ENVC=[]") })
        assertTrue("${spec.label} M1 no A<-B env", !capA.out.contains("ENVB="))
        assertTrue("${spec.label} M1 no C<-A env", !capC.out.contains("ENVA="))
        sessions.forEach { it.first.close() }
    }

    @Test fun m1_activeEnvCwdIsolation() { m1_activeEnvCwdIsolation(localSpec()); m1_activeEnvCwdIsolation(adbSpec()) }

    private fun m2_stdinCtrlCResizeIsolation(spec: PSpec) {
        val engine = spec.engine()
        val sessions = (0..2).map {
            val r = engine.openSession(ATPersistentBackend.Launch(command = ""))
            val s = (r as ATOpenSessionResult.Success).session
            val cap = Cap()
            s.setListener(cap)
            s to cap
        }
        val (a, capA) = sessions[0]
        val (b, capB) = sessions[1]
        val (c, capC) = sessions[2]

        // stdin isolation: only C runs cat
        write(c, "cat")
        Thread.sleep(400)
        write(c, "CATONLY_MARK")
        assertTrue("${spec.label} M2 C cat", await(5000) { capC.out.contains("CATONLY_MARK") })
        c.write(byteArrayOf(0x04))
        assertTrue("${spec.label} M2 A no cat data", !capA.out.contains("CATONLY_MARK"))
        assertTrue("${spec.label} M2 B no cat data", !capB.out.contains("CATONLY_MARK"))

        // Ctrl-C isolation: only C
        write(c, "sleep 30")
        Thread.sleep(600)
        c.write(byteArrayOf(0x03))
        Thread.sleep(300)
        write(c, "echo CT=\$((7+7))")
        assertTrue("${spec.label} M2 C ctrl-c", await(5000) { capC.out.contains("CT=14") })
        write(a, "echo AA=\$((8+8))")
        write(b, "echo BB=\$((9+9))")
        assertTrue("${spec.label} M2 A alive", await(5000) { capA.out.contains("AA=16") })
        assertTrue("${spec.label} M2 B alive", await(5000) { capB.out.contains("BB=18") })

        // resize isolation
        assertTrue(a.resize(40, 120))
        assertTrue(b.resize(30, 100))
        Thread.sleep(300)
        write(a, "echo SZA=[\$(stty size)]")
        write(b, "echo SZB=[\$(stty size)]")
        assertTrue("${spec.label} M2 A size", await(5000) { capA.out.contains("SZA=[40 120]") })
        assertTrue("${spec.label} M2 B size", await(5000) { capB.out.contains("SZB=[30 100]") })
        sessions.forEach { it.first.close() }
    }

    @Test fun m2_stdinCtrlCResizeIsolation() { m2_stdinCtrlCResizeIsolation(localSpec()); m2_stdinCtrlCResizeIsolation(adbSpec()) }

    private fun m3_closeReopenExitIsolation(spec: PSpec) {
        val engine = spec.engine()
        val sessions = (0..2).map {
            val r = engine.openSession(ATPersistentBackend.Launch(command = ""))
            val s = (r as ATOpenSessionResult.Success).session
            val cap = Cap()
            s.setListener(cap)
            s to cap
        }
        val (a, capA) = sessions[0]
        val (b, _) = sessions[1]
        val (c, _) = sessions[2]

        // close isolation
        b.close()
        assertEquals(ATSessionState.CLOSED, b.state)
        write(a, "echo AC=\$((2+3))")
        write(c, "echo CC=\$((3+4))")
        assertTrue("${spec.label} M3 A survives", await(5000) { capA.out.contains("AC=5") })

        // reopen while A/C alive
        val capD = Cap()
        val rd = engine.openSession(ATPersistentBackend.Launch(command = ""))
        assertTrue("${spec.label} M3 reopen", rd is ATOpenSessionResult.Success)
        val d = (rd as ATOpenSessionResult.Success).session
        d.setListener(capD)
        write(d, "echo DD=\$((4+5))")
        assertTrue("${spec.label} M3 D works", await(5000) { capD.out.contains("DD=9") })

        // natural exit isolation: D exits
        write(d, "exit")
        assertTrue("${spec.label} M3 D exited", await(5000) { d.state == ATSessionState.EXITED })
        write(a, "echo AAL=\$((6+6))")
        assertTrue("${spec.label} M3 A alive after D exit", await(5000) { capA.out.contains("AAL=12") })
        assertEquals(ATSessionState.ACTIVE, a.state)
        assertEquals(ATSessionState.ACTIVE, c.state)
        a.close(); c.close(); d.close()
    }

    @Test fun m3_closeReopenExitIsolation() { m3_closeReopenExitIsolation(localSpec()); m3_closeReopenExitIsolation(adbSpec()) }

    // ==================================================================
    // Cross-mode: persistent + one-shot coexistence — x1/x2
    // ==================================================================

    @Test fun x1_localCrossMode() {
        val engine = localEngine()
        val (s, cap, _) = openWithListener(localSpec().copy(engine = { engine }))
        write(s, "echo PERSIST_BEFORE=\$((1+2))")
        assertTrue("LOCAL X persistent before", await(5000) { cap.out.contains("PERSIST_BEFORE=3") })

        val one = engine.execute(ATExecutionRequest(command = "echo ONESHOT_X"))
        assertEquals("LOCAL X one-shot outcome", ATExecutionOutcome.COMPLETED, one.outcome)
        assertTrue("LOCAL X one-shot stdout", one.stdout.contains("ONESHOT_X"))

        assertEquals("LOCAL X session still ACTIVE", ATSessionState.ACTIVE, s.state)
        write(s, "echo PERSIST_AFTER=\$((2+3))")
        assertTrue("LOCAL X persistent after", await(5000) { cap.out.contains("PERSIST_AFTER=5") })
        s.close()
    }

    @Test fun x2_adbCrossMode() {
        val engine = adbEngine()
        val (s, cap, _) = openWithListener(adbSpec().copy(engine = { engine }))
        write(s, "echo PERSIST_BEFORE=\$((1+2))")
        assertTrue("ADB X persistent before", await(5000) { cap.out.contains("PERSIST_BEFORE=3") })

        val one = engine.execute(ATExecutionRequest(command = "echo ONESHOT_X"))
        assertEquals("ADB X one-shot outcome", ATExecutionOutcome.COMPLETED, one.outcome)
        assertTrue("ADB X one-shot stdout", one.stdout.contains("ONESHOT_X"))

        assertEquals("ADB X session still ACTIVE", ATSessionState.ACTIVE, s.state)
        write(s, "echo PERSIST_AFTER=\$((2+3))")
        assertTrue("ADB X persistent after", await(5000) { cap.out.contains("PERSIST_AFTER=5") })
        s.close()
    }

    // AT.V3-2.2 cross-mode with a LARGE one-shot: persistent session A stays
    // ACTIVE while one-shot B streams 64 KiB, then A keeps executing. Proves
    // the credit path does not disturb the shared connection / RX owner.
    @Test(timeout = 120_000)
    fun x3_adbCrossModeLargeOneShot() {
        val engine = adbEngine()
        val (s, cap, _) = openWithListener(adbSpec().copy(engine = { engine }))
        write(s, "echo PERSIST_X3=\$((1+2))")
        assertTrue("X3 persistent before", await(5000) { cap.out.contains("PERSIST_X3=3") })

        val n = 1024
        val expectedBytes = n * 65
        val lastLine = (n - 1).toString().padStart(64, '0')
        val one = engine.execute(ATExecutionRequest(command = gen(n)))
        assertEquals("X3 one-shot outcome", ATExecutionOutcome.COMPLETED, one.outcome)
        assertEquals("X3 one-shot bytes", expectedBytes, one.stdout.length)
        assertTrue("X3 one-shot tail", one.stdout.endsWith(lastLine + "\n"))

        assertEquals("X3 session still ACTIVE", ATSessionState.ACTIVE, s.state)
        write(s, "echo PERSIST_AFTER_X3=\$((2+3))")
        assertTrue("X3 persistent after", await(5000) { cap.out.contains("PERSIST_AFTER_X3=5") })
        s.close()
    }

    // ==================================================================
    // read() / listener consumption invariant — r1/r2
    // ==================================================================

    private fun r1_listenerOwnsStream(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        // While a listener is set, pull read is not a concurrent consumer.
        assertEquals("${spec.label} R1 read null while listener", null, s.read(200))
        write(s, "echo LISTENED")
        assertTrue("${spec.label} R1 data via listener", await(5000) { cap.out.contains("LISTENED") })
        assertEquals("${spec.label} R1 read still null", null, s.read(200))
        s.close()
    }

    @Test fun r1_listenerOwnsStream() { r1_listenerOwnsStream(localSpec()); r1_listenerOwnsStream(adbSpec()) }

    private fun r2_pullMode(spec: PSpec) {
        val engine = spec.engine()
        val r = engine.openSession(ATPersistentBackend.Launch(command = ""))
        val s = (r as ATOpenSessionResult.Success).session
        // drain any startup prompt bytes; then a read with no data must
        // return an EMPTY array (timeout), never null and never an error.
        var guard = 0
        var empty = s.read(300)
        while (empty != null && empty.isNotEmpty() && guard++ < 30) {
            empty = s.read(300)
        }
        assertNotNull("${spec.label} R2 timeout != null", empty)
        assertEquals("${spec.label} R2 timeout empty array", 0, empty!!.size)
        s.write("echo PULLMARK\n".toByteArray())
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 5000
        var b: ByteArray? = null
        while (System.currentTimeMillis() < deadline && !sb.contains("PULLMARK")) {
            b = s.read(200)
            if (b != null && b.isNotEmpty()) sb.append(String(b))
        }
        assertTrue("${spec.label} R2 pull data", sb.contains("PULLMARK"))
        s.close()
    }

    @Test fun r2_pullMode() { r2_pullMode(localSpec()); r2_pullMode(adbSpec()) }

    // ==================================================================
    // Byte-stream input contract — b1
    // ==================================================================

    private fun b1_byteStream(spec: PSpec) {
        val (s, cap, _) = openWithListener(spec)
        val payload = "echo BYTES=\$((9+1))\n".toByteArray()
        val n = s.write(payload)
        assertEquals("${spec.label} B1 write returns byte count", payload.size, n)
        assertTrue("${spec.label} B1 output", await(5000) { cap.out.contains("BYTES=10") })
        // raw control byte (single byte write)
        val nc = s.write(byteArrayOf(0x03))
        assertEquals("${spec.label} B1 control byte accepted", 1, nc)
        Thread.sleep(200)
        s.close()
    }

    @Test fun b1_byteStream() { b1_byteStream(localSpec()); b1_byteStream(adbSpec()) }

    // ==================================================================
    // Principal / capability difference evidence — c1
    // ==================================================================

    @Test fun c1_principalDifference() {
        val local = localEngine().execute(ATExecutionRequest(command = "id"))
        val adb = adbEngine().execute(ATExecutionRequest(command = "id"))
        android.util.Log.i("ATV33", "principal LOCAL: ${local.stdout.trim()}")
        android.util.Log.i("ATV33", "principal ADB: ${adb.stdout.trim()}")
        assertTrue("LOCAL principal = app/untrusted_app",
            local.stdout.contains("untrusted_app") || local.stdout.contains("uid=1"))
        assertTrue("ADB principal = shell uid 2000", adb.stdout.contains("uid=2000(shell)"))
    }

    // ==================================================================
    // AT.V3-2.2 — one-shot mid-stream abnormal close (failure path)
    // ==================================================================

    // Device closes its stdout/stderr while the command is still running
    // (adbd sees EOF on the shell socketpair and closes the stream before the
    // process exits). Purpose: bounded failure, no hang, no crash, no stale
    // reader, connection stays usable. Not a recovery test.
    @Test(timeout = 30_000)
    fun f1_adbOneShotMidStreamClose() {
        val e = adbEngine()
        val t0 = System.currentTimeMillis()
        val r = e.execute(ATExecutionRequest(command = "echo PARTIAL; exec 1>&- 2>&-; sleep 2"))
        val dt = System.currentTimeMillis() - t0
        android.util.Log.i("ATV322", "f1: returned in ${dt}ms outcome=${r.outcome} " +
            "exit=${r.exitCode} stdout=${r.stdout.trim()} stderr=${r.stderr.trim()} " +
            "hasError=${r.hasError} err=${r.error}")
        assertTrue("f1 bounded (${dt}ms)", dt < 15_000)
        assertEquals("f1 outcome (peer CLSE = normal termination)", ATExecutionOutcome.COMPLETED, r.outcome)
        assertTrue("f1 partial stdout delivered", r.stdout.contains("PARTIAL"))
        // connection remains usable after the abnormal close
        val again = e.execute(ATExecutionRequest(command = "echo AFTER_F1"))
        assertEquals("f1 reuse outcome", ATExecutionOutcome.COMPLETED, again.outcome)
        assertTrue("f1 reuse stdout", again.stdout.contains("AFTER_F1"))
    }
}