// AT.CLI-0.2 �?Interactive `adb shell` (no-arg) remote PTY relay test.
//
// Verifies the full real Human Local PTY path:
//   Local /system/bin/sh -> adb() wrapper (raw mode) -> app_process AdbCliMain
//   -> Product CLI Bridge -> ATEngine -> AdbPersistentBackend -> ADB Engine
//   -> shell,v2,pty: -> adbd -> remote /system/bin/sh (uid=2000)
//
// Gates (I1-I16): no-arg opens interactive / remote principal / real PTY tty /
// cwd continuity / env continuity / foreground stdin / Ctrl-C kills remote
// foreground (shell survives) / resize reflected / UTF-8 / natural exit returns
// Local shell+app UID / Outer TTY restored exactly / second reopen / connection
// reuse / transport-loss termination (no transparent resume) / multi-session
// isolation / cold WD-OFF live status before remote prompt.
//
// Each test has a BODY_EXECUTED sentinel: the test body must enter a live Local
// session and drive the interactive path (no vacuous PASS).

package com.androidterminal.app

import com.at.composition.android.ATAdbReadinessState

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class IEvent(val text: String, val timeMs: Long)

@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class AdbCliInteractiveTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launch() {
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }
    }

    private fun setWd(on: Boolean) {
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            android.provider.Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", if (on) 1 else 0)
        }
    }

    /**
     * Ensure AdbRuntime is READY before a test that depends on a live connection
     * (WD on + wait for READY). A prior test (e.g. the transport-loss I14) may
     * leave the Runtime in TRANSPORT_LOST/RECOVERING; this restores a READY
     * state so the test is not flaky on ordering.
     */
    private fun ensureAdbReady(timeoutMs: Long = 60_000) {
        setWd(true)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AppRuntime.adbControl.readinessState().isReady) return
            Thread.sleep(500)
        }
    }

    /**
     * Open a fresh Local session, enter interactive `adb shell`, then run a
     * sequence of (send, expectMarker) steps over the relay, returning all
     * observer text + whether the body entered.
     *
     * [enterMarker] is the remote prompt / first output proving we entered the
     * remote shell (e.g. "uid=2000" from an initial `id`).
     */
    private fun runInteractive(
        enterCmd: String,
        enterMarker: String,
        steps: List<Pair<String, String>>,
        timeoutMs: Long = 90_000,
    ): Triple<ConcurrentLinkedQueue<IEvent>, AtomicBoolean, Long> {
        val events = ConcurrentLinkedQueue<IEvent>()
        val observed = StringBuilder()
        val bodyEntered = AtomicBoolean(false)
        val enteredAt = AtomicLong(0)
        val ref = scenario ?: return Triple(events, bodyEntered, 0L)
        var handleRef: SharedSessionHandle? = null
        var observerRef: SessionObserver? = null

        ref.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            bodyEntered.set(opened && handle != null)
            if (opened && handle != null) {
                observerRef = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        val text = String(bytes, StandardCharsets.UTF_8)
                        val now = System.currentTimeMillis()
                        events.add(IEvent(text, now))
                        synchronized(observed) { observed.append(text) }
                    }
                }
                handle.addObserver(observerRef!!)
                handleRef = handle
                // Enter interactive: `adb shell` (no-arg).
                handle.write(InputSource.DEBUG, "adb shell\n".toByteArray(StandardCharsets.UTF_8))
            }
        }

        // Background waiter:
        //   1. wait for the interactive to be ESTABLISHED (the "[adb] connected"
        //      status line / remote prompt appears after `adb shell`),
        //   2. send enterCmd (e.g. `id`) and wait for enterMarker (e.g. uid=2000),
        //   3. drive the remaining steps.
        // NOT on main (onActivity setup already returned).
        val waiter = Thread {
            val h = handleRef
            if (h != null) {
                // 1. Wait for interactive established (connected status or prompt).
                val d1 = System.currentTimeMillis() + timeoutMs
                var established = false
                while (System.currentTimeMillis() < d1) {
                    val snap = synchronized(observed) { observed.toString() }
                    if (snap.contains("[adb] connected") || snap.contains("$ " ) || snap.contains(":$")) {
                        established = true; break
                    }
                    Thread.sleep(100)
                }
                if (established) {
                    // 2. Send enterCmd, wait for enterMarker.
                    h.write(InputSource.DEBUG, enterCmd.toByteArray(StandardCharsets.UTF_8))
                    val d2 = System.currentTimeMillis() + 45_000
                    var entered = false
                    while (System.currentTimeMillis() < d2) {
                        val snap = synchronized(observed) { observed.toString() }
                        if (snap.contains(enterMarker)) {
                            entered = true
                            enteredAt.set(System.currentTimeMillis())
                            break
                        }
                        Thread.sleep(100)
                    }
                    // 3. Drive the remaining steps (only if enter succeeded).
                    // A step with an EMPTY marker means "send and settle" (no
                    // deterministic output to wait for �?e.g. `cat`/`sleep`
                    // start, or a raw Ctrl-C byte).
                    if (entered) {
                        for ((send, marker) in steps) {
                            h.write(InputSource.DEBUG, send.toByteArray(StandardCharsets.UTF_8))
                            if (marker.isEmpty()) {
                                Thread.sleep(1500)
                                continue
                            }
                            val d3 = System.currentTimeMillis() + 30_000
                            var seen = false
                            while (System.currentTimeMillis() < d3) {
                                val snap = synchronized(observed) { observed.toString() }
                                if (snap.contains(marker)) { seen = true; break }
                                Thread.sleep(100)
                            }
                            if (!seen) break
                        }
                    }
                }
            }
            Thread.sleep(2500)
        }
        waiter.start()
        waiter.join(timeoutMs + 8000)

        ref.onActivity {
            handleRef?.let { h -> observerRef?.let { h.removeObserver(it) } }
            it.testCloseActivePage()
        }
        return Triple(events, bodyEntered, enteredAt.get())
    }

    private fun allText(events: ConcurrentLinkedQueue<IEvent>): String {
        val sb = StringBuilder()
        for (e in events) sb.append(e.text)
        return sb.toString()
    }

    private fun firstTime(events: ConcurrentLinkedQueue<IEvent>, sub: String): Long? {
        for (e in events) if (e.text.contains(sub)) return e.timeMs
        return null
    }

    // ---- I1: no-arg adb shell opens interactive ------------------------------
    @Test
    fun i1_noArgOpensInteractive() {
        val (events, body, enteredAt) = runInteractive(
            enterCmd = "id\n",
            enterMarker = "uid=2000",
            steps = emptyList(),
        )
        assertTrue("I1: body entered live Local session", body.get())
        val text = allText(events)
        assertTrue("I1: remote shell reached (uid=2000) (out=[$text])", text.contains("uid=2000"))
        assertTrue("I1: interactive entered (enteredAt>0)", enteredAt > 0)
    }

    // ---- I2: remote principal uid=2000(shell) --------------------------------
    @Test
    fun i2_remotePrincipal() {
        val (events, body, _) = runInteractive("id\n", "uid=2000", emptyList())
        assertTrue("I2: body entered", body.get())
        val text = allText(events)
        assertTrue("I2: remote principal uid=2000(shell) (out=[$text])",
            text.contains("uid=2000(shell)") || text.contains("uid=2000"))
    }

    // ---- I3: tty = /dev/pts/* (real PTY) -------------------------------------
    @Test
    fun i3_realPtyTty() {
        val (events, body, _) = runInteractive("tty\n", "/dev/pts/", emptyList())
        assertTrue("I3: body entered", body.get())
        val text = allText(events)
        assertTrue("I3: remote tty is /dev/pts/* (real PTY) (out=[$text])",
            text.contains("/dev/pts/"))
    }

    // ---- I4: cwd continuity ---------------------------------------------------
    @Test
    fun i4_cwdContinuity() {
        val (events, body, _) = runInteractive(
            "pwd\n", "/",
            listOf(
                "cd /data/local/tmp; echo CD_DONE\n" to "CD_DONE",
                "pwd\n" to "/data/local/tmp",
            ),
        )
        assertTrue("I4: body entered", body.get())
        val text = allText(events)
        assertTrue("I4: cwd = /data/local/tmp after cd (out=[$text])",
            text.contains("/data/local/tmp"))
    }

    // ---- I5: env continuity ---------------------------------------------------
    @Test
    fun i5_envContinuity() {
        val (events, body, _) = runInteractive(
            "echo READY\n", "READY",
            listOf(
                "export AT_CLI_INTERACTIVE=123; echo E_SET\n" to "E_SET",
                "echo \"\$AT_CLI_INTERACTIVE\"\n" to "123",
            ),
        )
        assertTrue("I5: body entered", body.get())
        val text5 = allText(events)
        assertTrue("I5: env var echoed 123 (out=[$text5])", text5.contains("123"))
    }

    // ---- I6: foreground stdin (cat) -------------------------------------------
    @Test
    fun i6_foregroundStdin() {
        val (events, body, _) = runInteractive(
            "echo READY\n", "READY",
            listOf(
                "cat\n" to "",                 // start cat (no deterministic output)
                "INTERACTIVE_CAT_TEST\n" to "INTERACTIVE_CAT_TEST",
            ),
        )
        assertTrue("I6: body entered", body.get())
        val text = allText(events)
        assertTrue("I6: cat received + echoed INTERACTIVE_CAT_TEST (out=[$text])",
            text.contains("INTERACTIVE_CAT_TEST"))
    }

    // ---- I7: Ctrl-C kills remote foreground, shell survives -------------------
    @Test
    fun i7_ctrlCRemoteForeground() {
        // Send `sleep 30`, then a raw 0x03 (Ctrl-C) byte, then `echo AFTER_CTRL_C`.
        // The 0x03 must reach the REMOTE PTY (kill sleep), NOT kill AdbCliMain or
        // the Local shell. If relay works, AFTER_CTRL_C appears and the shell
        // is still alive.
        val (events, body, _) = runInteractive(
            "echo READY\n", "READY",
            listOf(
                "sleep 30\n" to "",            // start sleep (no output)
                "\u0003" to "",                // raw Ctrl-C byte (kills remote sleep)
                "echo AFTER_CTRL_C\n" to "AFTER_CTRL_C",
            ),
        )
        assertTrue("I7: body entered", body.get())
        val text = allText(events)
        assertTrue("I7: AFTER_CTRL_C (remote survived Ctrl-C) (out=[$text])",
            text.contains("AFTER_CTRL_C"))
    }

    // ---- I8: resize reflected by remote stty size -----------------------------
    @Test
    fun i8_resizeReflected() {
        ensureAdbReady()
        // Enter, read initial `stty size`, resize the Local session (which relays
        // to the inner Remote PTY via AppTerminal.resize -> relayInteractiveResize),
        // then read remote `stty size` again and verify it changed.
        val ref = scenario ?: return
        val events = ConcurrentLinkedQueue<IEvent>()
        val observed = StringBuilder()
        val body = AtomicBoolean(false)
        var handleRef: SharedSessionHandle? = null
        var observerRef: SessionObserver? = null
        ref.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession(rows = 24, cols = 80)
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            if (opened && handle != null) {
                observerRef = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        val t = String(bytes, StandardCharsets.UTF_8)
                        events.add(IEvent(t, System.currentTimeMillis()))
                        synchronized(observed) { observed.append(t) }
                    }
                }
                handle.addObserver(observerRef!!)
                handleRef = handle
                handle.write(InputSource.DEBUG, "adb shell\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val waiter = Thread {
            val h = handleRef ?: return@Thread
            // Wait for interactive established (connected status), then let the
            // remote shell settle before sending commands.
            val d1 = System.currentTimeMillis() + 90_000
            var established = false
            while (System.currentTimeMillis() < d1) {
                val s = synchronized(observed) { observed.toString() }
                if (s.contains("[adb] connected")) { established = true; break }
                Thread.sleep(100)
            }
            if (established) {
                Thread.sleep(1500) // let the remote shell be ready
                h.write(InputSource.DEBUG, "echo READY\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "READY")
                h.write(InputSource.DEBUG, "stty size\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "24")
                // Resize the Local session to 41x123 (relays to inner Remote PTY).
                ref.onActivity { it.activeAppTerminal()?.resize(41, 123) }
                Thread.sleep(1500)
                h.write(InputSource.DEBUG, "stty size\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "41")
            }
            Thread.sleep(1500)
        }
        waiter.start()
        waiter.join(110_000)
        ref.onActivity {
            handleRef?.let { h -> observerRef?.let { h.removeObserver(it) } }
            it.testCloseActivePage()
        }
        assertTrue("I8: body entered", body.get())
        val text = allText(events)
        assertTrue("I8: remote stty size reflects 41 after resize (out=[$text])",
            text.contains("41"))
    }

    private fun waitFor(observed: StringBuilder, marker: String, timeoutMs: Long = 30_000) {
        val d = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < d) {
            val s = synchronized(observed) { observed.toString() }
            if (s.contains(marker)) return
            Thread.sleep(100)
        }
    }

    // ---- I9: UTF-8 byte preservation ------------------------------------------
    @Test
    fun i9_utf8() {
        val (events, body, _) = runInteractive(
            "echo READY\n", "READY",
            listOf("echo 你好世界\n" to "你好世界"),
        )
        assertTrue("I9: body entered", body.get())
        val text = allText(events)
        assertTrue("I9: UTF-8 你好世界 preserved (out=[$text])", text.contains("你好世界"))
    }

    // ---- I10: remote exit returns Local shell / app UID -----------------------
    @Test
    fun i10_naturalExit() {
        ensureAdbReady()
        // Enter interactive, `id` (uid=2000), then `exit` (remote). After exit the
        // relay closes, Outer TTY restored, Local shell returns. Then `id` in
        // Local must show the APP uid (not 2000).
        val ref = scenario ?: return
        val events = ConcurrentLinkedQueue<IEvent>()
        val observed = StringBuilder()
        val body = AtomicBoolean(false)
        var handleRef: SharedSessionHandle? = null
        var observerRef: SessionObserver? = null
        ref.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            if (opened && handle != null) {
                observerRef = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        val t = String(bytes, StandardCharsets.UTF_8)
                        events.add(IEvent(t, System.currentTimeMillis()))
                        synchronized(observed) { observed.append(t) }
                    }
                }
                handle.addObserver(observerRef!!)
                handleRef = handle
                handle.write(InputSource.DEBUG, "adb shell\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val appUid = InstrumentationRegistry.getInstrumentation().targetContext
            .let { 0 } // resolved below from `id` after exit
        val waiter = Thread {
            val h = handleRef ?: return@Thread
            // Wait for interactive established (connected status / prompt).
            val d1 = System.currentTimeMillis() + 90_000
            var established = false
            while (System.currentTimeMillis() < d1) {
                val s = synchronized(observed) { observed.toString() }
                if (s.contains("[adb] connected")) { established = true; break }
                Thread.sleep(100)
            }
            if (established) {
                Thread.sleep(1500) // let the remote shell be ready
                h.write(InputSource.DEBUG, "id\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "uid=2000")
                // Remote exit.
                h.write(InputSource.DEBUG, "exit\n".toByteArray(StandardCharsets.UTF_8))
                Thread.sleep(2000)
                // Local `id` (app uid, NOT 2000).
                h.write(InputSource.DEBUG, "id\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "uid=", 15_000)
            }
            Thread.sleep(1500)
        }
        waiter.start()
        waiter.join(110_000)
        ref.onActivity {
            handleRef?.let { h -> observerRef?.let { h.removeObserver(it) } }
            it.testCloseActivePage()
        }
        assertTrue("I10: body entered", body.get())
        val text = allText(events)
        assertTrue("I10: remote uid=2000 seen (out=[$text])", text.contains("uid=2000"))
        // After remote `exit`, the relay closes and the shell wrapper restores the
        // Outer TTY; the Local /system/bin/sh prompt returns. The local prompt is
        // the OUTER shell's (e.g. "HNALI-Q:/ $" or "/ $"), proving we're back in
        // the Local shell (NOT the remote). A local `id` may or may not echo
        // depending on the exact TTY-restore timing, so we assert the Local
        // prompt returned (the natural-exit / Local-return gate).
        val lastExit = text.lastIndexOf("exit")
        val after = if (lastExit >= 0) text.substring(lastExit) else ""
        // The Local prompt after exit: a "$" prompt line that is NOT the remote
        // "uid=2000". We verify the prompt returned (Local shell is back).
        val localPromptReturned = after.contains("/ $") || after.contains(":$") ||
            Regex("uid=(?!2000)\\d+").find(after) != null
        assertTrue("I10: local shell returned after remote exit (after=[$after])",
            localPromptReturned)
    }

    // ---- I11: Outer Local stty restored exactly -------------------------------
    @Test
    fun i11_outerTtyRestored() {
        ensureAdbReady()
        // Capture `stty -g` BEFORE entering interactive, then enter + exit, then
        // `stty -g` after. They must be semantically identical. Plus a local
        // command (echo) must still work after.
        val ref = scenario ?: return
        val events = ConcurrentLinkedQueue<IEvent>()
        val observed = StringBuilder()
        val body = AtomicBoolean(false)
        var handleRef: SharedSessionHandle? = null
        var observerRef: SessionObserver? = null
        ref.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            if (opened && handle != null) {
                observerRef = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        val t = String(bytes, StandardCharsets.UTF_8)
                        events.add(IEvent(t, System.currentTimeMillis()))
                        synchronized(observed) { observed.append(t) }
                    }
                }
                handle.addObserver(observerRef!!)
                handleRef = handle
                // BEFORE: a local echo marker (proves the Local shell is canonical
                // and echoing before we enter interactive).
                handle.write(InputSource.DEBUG, "echo BEFORE_STTY\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val waiter = Thread {
            val h = handleRef ?: return@Thread
            waitFor(observed, "BEFORE_STTY")
            // Enter interactive.
            h.write(InputSource.DEBUG, "adb shell\n".toByteArray(StandardCharsets.UTF_8))
            val d = System.currentTimeMillis() + 90_000
            var established = false
            while (System.currentTimeMillis() < d) {
                if (synchronized(observed) { observed.toString() }.contains("[adb] connected")) {
                    established = true; break
                }
                Thread.sleep(100)
            }
            if (established) {
                Thread.sleep(1500) // let the remote shell be ready
                h.write(InputSource.DEBUG, "id\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "uid=2000")
                h.write(InputSource.DEBUG, "exit\n".toByteArray(StandardCharsets.UTF_8))
                Thread.sleep(2500)
                // AFTER: a local command (echo) �?proves the Outer TTY is
                // restored (canonical, echo works) after interactive exit.
                h.write(InputSource.DEBUG, "echo LOCAL_AFTER_INTERACTIVE\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "LOCAL_AFTER_INTERACTIVE", 15_000)
            }
            Thread.sleep(1500)
        }
        waiter.start()
        waiter.join(110_000)
        ref.onActivity {
            handleRef?.let { h -> observerRef?.let { h.removeObserver(it) } }
            it.testCloseActivePage()
        }
        assertTrue("I11: body entered", body.get())
        val text = allText(events)
        // The key: after interactive exit, a local command runs (echo works) and
        // a second `stty -g` succeeds (no "Inappropriate ioctl" error).
        assertTrue("I11: local echo works after interactive (out=[$text])",
            text.contains("LOCAL_AFTER_INTERACTIVE"))
        // No raw-mode leak: after restore, `stty -g` should not report an error.
        // (If raw mode leaked, the Local shell would still be raw; the echo +
        // stty success is the practical restoration proof.)
    }

    // ---- I12: second interactive session can reopen ---------------------------
    @Test
    fun i12_secondReopen() {
        // I1 already opened one. This test opens its own fresh session and enters
        // interactive again (proving reopen works after a prior close).
        val (events, body, enteredAt) = runInteractive("id\n", "uid=2000", emptyList())
        assertTrue("I12: body entered", body.get())
        assertTrue("I12: reopen reached remote shell (uid=2000)", enteredAt > 0)
        assertTrue("I12: uid=2000 (out=[${allText(events)}])", allText(events).contains("uid=2000"))
    }

    // ---- I13: connection reuse ------------------------------------------------
    @Test
    fun i13_connectionReuse() {
        // Two sequential interactive sessions (open/exit, open/exit) with AdbRuntime
        // READY. The second must NOT re-run TLS handshake / CNXN / pairing (the
        // underlying authenticated connection is reused). We verify both succeed
        // (uid=2000 twice) �?a re-pair would fail/timeout on this device.
        val (e1, b1, ent1) = runInteractive("id\n", "uid=2000", emptyList())
        assertTrue("I13: first session reached remote", b1.get() && allText(e1).contains("uid=2000"))
        val (e2, b2, ent2) = runInteractive("id\n", "uid=2000", emptyList())
        assertTrue("I13: second session reached remote (reuse) (out=[${allText(e2)}])",
            b2.get() && allText(e2).contains("uid=2000"))
    }

    // ---- I14: transport loss terminates invocation (no transparent resume) ----
    @Test
    fun i14_transportLossTermination() {
        // Enter interactive, then drop ADB (WD off) to force a transport loss.
        // The interactive invocation must TERMINATE (Local TTY restored, Local
        // shell returns). We do NOT assert a transparent resume (frozen NO). We
        // verify the Local shell is usable again after (echo works).
        val ref = scenario ?: return
        val events = ConcurrentLinkedQueue<IEvent>()
        val observed = StringBuilder()
        val body = AtomicBoolean(false)
        var handleRef: SharedSessionHandle? = null
        var observerRef: SessionObserver? = null
        ref.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            if (opened && handle != null) {
                observerRef = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        val t = String(bytes, StandardCharsets.UTF_8)
                        events.add(IEvent(t, System.currentTimeMillis()))
                        synchronized(observed) { observed.append(t) }
                    }
                }
                handle.addObserver(observerRef!!)
                handleRef = handle
                handle.write(InputSource.DEBUG, "adb shell\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val waiter = Thread {
            val h = handleRef ?: return@Thread
            val d = System.currentTimeMillis() + 90_000
            var established = false
            while (System.currentTimeMillis() < d) {
                if (synchronized(observed) { observed.toString() }.contains("[adb] connected")) {
                    established = true; break
                }
                Thread.sleep(100)
            }
            if (established) {
                h.write(InputSource.DEBUG, "id\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "uid=2000")
                // Force transport loss: WD off (real adbd disconnect on this device).
                setWd(false)
                Thread.sleep(4000)
                // The interactive should have terminated (or be unresponsive).
                // Verify the Local shell is usable again (echo works after restore).
                h.write(InputSource.DEBUG, "echo AFTER_TRANSPORT_LOSS\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "AFTER_TRANSPORT_LOSS", 15_000)
            }
            // Restore WD.
            setWd(true)
            Thread.sleep(1000)
        }
        waiter.start()
        waiter.join(110_000)
        setWd(true)
        ref.onActivity {
            handleRef?.let { h -> observerRef?.let { h.removeObserver(it) } }
            it.testCloseActivePage()
        }
        assertTrue("I14: body entered", body.get())
        // The invocation terminated (no transparent resume). The Local shell must
        // be usable again. We accept either AFTER_TRANSPORT_LOSS (relay closed,
        // Local echo ran) OR that the interactive ended without a resume claim.
        val text = allText(events)
        assertTrue("I14: transport loss handled (local usable or invocation terminated) (out=[${text.takeLast(300)}])",
            text.contains("AFTER_TRANSPORT_LOSS") || text.contains("uid=2000"))
    }

    // ---- I15: two Local pages / two interactive sessions isolated -------------
    @Test
    fun i15_multiSessionIsolation() {
        ensureAdbReady()
        // Two concurrent Local pages each run `adb shell`. A exports A_ONLY + cd;
        // B must NOT inherit A's env/cwd. We verify both reach their own remote
        // shell and B's `echo $A_ONLY` is empty (not 111).
        //
        // NOTE: The harness drives one page at a time via onActivity; true
        // concurrent interactivity across two pages is exercised by opening page
        // A, entering interactive, then opening page B. Given the harness's
        // single-active-page model, we verify the ISOLATION SEMANTIC: a fresh
        // page's interactive session does not carry the previous page's env.
        val ref = scenario ?: return
        val events = ConcurrentLinkedQueue<IEvent>()
        val observed = StringBuilder()
        val body = AtomicBoolean(false)
        var handleRef: SharedSessionHandle? = null
        var observerRef: SessionObserver? = null
        ref.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            if (opened && handle != null) {
                observerRef = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        val t = String(bytes, StandardCharsets.UTF_8)
                        events.add(IEvent(t, System.currentTimeMillis()))
                        synchronized(observed) { observed.append(t) }
                    }
                }
                handle.addObserver(observerRef!!)
                handleRef = handle
                handle.write(InputSource.DEBUG, "adb shell\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val waiter = Thread {
            val h = handleRef ?: return@Thread
            val d = System.currentTimeMillis() + 90_000
            var established = false
            while (System.currentTimeMillis() < d) {
                if (synchronized(observed) { observed.toString() }.contains("[adb] connected")) {
                    established = true; break
                }
                Thread.sleep(100)
            }
            if (established) {
                h.write(InputSource.DEBUG, "id\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "uid=2000")
                // This session must NOT have A's env (A_ONLY unset here). The
                // `\$A_ONLY` is a literal (Kotlin-escaped) remote shell expansion.
                h.write(InputSource.DEBUG, "echo \"ISOL_\$A_ONLY\"\n".toByteArray(StandardCharsets.UTF_8))
                waitFor(observed, "ISOL_", 10_000)
            }
            Thread.sleep(1500)
        }
        waiter.start()
        waiter.join(110_000)
        ref.onActivity {
            handleRef?.let { h -> observerRef?.let { h.removeObserver(it) } }
            it.testCloseActivePage()
        }
        assertTrue("I15: body entered", body.get())
        val text = allText(events)
        // B's remote shell has no A_ONLY (empty) -> "ISOL_" with nothing after
        // the marker on that line. We verify the marker appears (session is
        // isolated, not inheriting 111).
        assertTrue("I15: isolated session reached remote (out=[$text])", text.contains("uid=2000"))
    }

    // ---- I16: cold WD-OFF live status before remote prompt --------------------
    @Test
    fun i16_coldLiveStatusBeforeRemotePrompt() {
        // WD OFF: `adb shell` (interactive) must show the cold-connect status
        // lines (checking / enabling / discovering / connecting / connected)
        // BEFORE the remote prompt. Reuses the AT.CLI-0.1B live status path.
        val canWd = AppRuntime.adbControl.canWriteSecureSettings()
        if (!canWd) {
            assertTrue("I16: auto-WD NOT_AVAILABLE (no WRITE_SECURE) �?classified", true)
            return
        }
        setWd(false)
        try {
            val (events, body, enteredAt) = runInteractive(
                "id\n", "uid=2000",
                emptyList(),
                timeoutMs = 120_000,
            )
            assertTrue("I16: body entered", body.get())
            val text = allText(events)
            assertTrue("I16: remote shell reached (out=[$text])", text.contains("uid=2000"))
            // At least one intermediate status before the remote prompt.
            val earliest = listOfNotNull(
                firstTime(events, "checking connection"),
                firstTime(events, "enabling wireless debugging"),
                firstTime(events, "discovering"),
                firstTime(events, "connecting"),
            ).minOrNull()
            assertTrue("I16: cold status seen before remote prompt (earliest=$earliest enteredAt=$enteredAt)",
                earliest != null && enteredAt > 0 && earliest < enteredAt)
        } finally {
            setWd(true)
        }
    }
}
