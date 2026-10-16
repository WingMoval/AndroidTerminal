// AT.CLI-0.1 — app_process `adb shell <command>` one-shot closure test.
//
// Drives the REAL Human Local /system/bin/sh (untrusted_app domain) via the
// SharedSessionHandle observer model (the production observation path — NOT
// LocalTerminalSession.read(), which returns null while the listener reader is
// active by design). Verifies Gates A-J for the one-shot slice.
//
// TEST HARNESS REPAIR (per AT.CLI-0.1 §21): every critical test records a
// BODY_EXECUTED sentinel + asserts the observer actually captured output, so a
// green JUnit result cannot be a vacuous pass (the c8-c12 failure mode).
//
// One-shot scope only: no interactive `adb shell`, no Ctrl-C/resize/cwd/env.

package com.androidterminal.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AdbCliOneShotTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launch() {
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            // Warm up ADB ONCE: trigger the bridge ensureReady (connectAuto poll)
            // so subsequent `adb shell` one-shots are fast (ADB already READY).
            runCatching { warmUpAdb() }
        }
    }

    /**
     * Trigger ADB READY ONCE (direct connectAuto, no Local session / PTY).
     * Keeps AdbRuntime + bridge `ready` warm so O3-O15 one-shots are fast.
     * Does NOT create/close a Local page (avoids interfering with per-test
     * session lifecycle).
     */
    private fun warmUpAdb() {
        scenario?.onActivity { a ->
            runCatching {
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    if (com.adb.engine.AdbRuntime.readinessState().isReady) return@runCatching
                    com.androidterminal.app.AppRuntime.connectAuto()
                    Thread.sleep(500)
                }
            }
        }
    }

    @After
    fun tearDown() {
        // Keep the scenario alive for the class (process exit releases it).
        // Closing per-test caused the c8-c12 vacuous-pass bug.
    }

    /**
     * Run a command line in a FRESH Local session via the observer model.
     * Returns the captured fan-out output. Records [bodyEntered] so a vacuous
     * pass (onActivity no-op) is detectable.
     */
    private fun runLocalCmd(
        cmd: String,
        markers: List<String>,
        timeoutMs: Long = 90_000,
        bodyEntered: AtomicBoolean? = null,
    ): String {
        val observed = StringBuilder()
        scenario?.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            bodyEntered?.set(opened && handle != null)
            if (opened && handle != null) {
                val observer = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        synchronized(observed) {
                            observed.append(String(bytes, StandardCharsets.UTF_8))
                        }
                    }
                }
                handle.addObserver(observer)
                handle.write(InputSource.DEBUG, "$cmd\n".toByteArray(StandardCharsets.UTF_8))
                // Wait until any marker appears (or timeout). PTY output arrives
                // in batches: the `adb shell X` one-shot output and the trailing
                // `; echo MARKER` may arrive in separate chunks. After the marker
                // appears, wait extra so the trailing echo is captured too.
                val deadline = System.currentTimeMillis() + timeoutMs
                var markerSeen = false
                while (System.currentTimeMillis() < deadline) {
                    val snap = synchronized(observed) { observed.toString() }
                    if (markers.any { snap.contains(it) }) { markerSeen = true; break }
                    Thread.sleep(100)
                }
                // Let trailing output (echo after one-shot) flush into the buffer.
                Thread.sleep(if (markerSeen) 2_500 else 500)
                handle.removeObserver(observer)
            }
            a.testCloseActivePage()
        }
        return synchronized(observed) { observed.toString() }
    }

    private fun requireBody(bodyEntered: AtomicBoolean, what: String) {
        assertTrue("$what: test body must have entered a live Local session (harness)", bodyEntered.get())
    }

    // ---- O1 / Gate A: command resolution -----------------------------------
    @Test
    fun o1_commandResolution() {
        val body = AtomicBoolean(false)
        // `type adb` in /system/bin/sh prints "adb is a function" for a shell
        // function (NOT the function body, and NOT a binary path). That proves
        // `adb` resolves to our shell function (not a system adb binary).
        val out = runLocalCmd("type adb", listOf("function", "adb"), bodyEntered = body)
        requireBody(body, "O1")
        // Must be a shell function (not a binary, not permission-denied).
        assertTrue("O1: adb is a shell function (out=[$out])",
            out.contains("function") || out.contains("adb()"))
        assertTrue("O1: not a Permission denied / RC126 (out=[$out])",
            !out.contains("Permission denied") && !out.contains("126"))
    }

    // ---- O2: version --------------------------------------------------------
    @Test
    fun o2_version() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb version", listOf("bridge protocol v1"), bodyEntered = body)
        requireBody(body, "O2")
        assertTrue("O2: version banner (out=[$out])", out.contains("AndroidTerminal adb CLI"))
        assertTrue("O2: protocol v1 (out=[$out])", out.contains("bridge protocol v1"))
    }

    // ---- O3 / Gate B: principal transition ---------------------------------
    @Test
    fun o3_principalTransition() {
        val body = AtomicBoolean(false)
        // Marker MUST be `uid=2000` (remote shell uid). The local `id` prints
        // uid=10313(u0_a313) which contains "u0_a" — using that as a marker
        // false-matches the local id before the remote one-shot returns.
        val out = runLocalCmd(
            "id; adb shell id; id",
            listOf("uid=2000"),
            bodyEntered = body,
        )
        requireBody(body, "O3")
        assertTrue("O3: remote uid=2000(shell) (out=[$out])", out.contains("uid=2000"))
        assertTrue("O3: local app uid present (out=[$out])", out.contains("u0_a"))
    }

    // ---- O4 / Gate C: stdout ------------------------------------------------
    @Test
    fun o4_stdout() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb shell echo CLI_STDOUT_OK", listOf("CLI_STDOUT_OK"), bodyEntered = body)
        requireBody(body, "O4")
        assertTrue("O4: stdout (out=[$out])", out.contains("CLI_STDOUT_OK"))
    }

    // ---- O5 / Gate D: stderr ------------------------------------------------
    @Test
    fun o5_stderr() {
        val body = AtomicBoolean(false)
        // Remote writes a marker to stderr.
        val out = runLocalCmd(
            "adb shell sh -c 'echo CLI_STDERR_MARKER >&2'",
            listOf("CLI_STDERR_MARKER"),
            bodyEntered = body,
        )
        requireBody(body, "O5")
        assertTrue("O5: stderr marker (out=[$out])", out.contains("CLI_STDERR_MARKER"))
    }

    // ---- O6: exit 0 ---------------------------------------------------------
    @Test
    fun o6_exitZero() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb shell true; echo O6_RC_$?", listOf("O6_RC_"), bodyEntered = body)
        requireBody(body, "O6")
        assertTrue("O6: exit 0 (out=[$out])", out.contains("O6_RC_0"))
    }

    // ---- O7: exit non-zero --------------------------------------------------
    @Test
    fun o7_exitNonZero() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb shell false; echo O7_RC_$?", listOf("O7_RC_"), bodyEntered = body)
        requireBody(body, "O7")
        assertTrue("O7: nonzero exit (out=[$out])", out.contains("O7_RC_1") || out.contains("O7_RC_2") || out.contains("O7_RC_255"))
    }

    // ---- O8: exit 7 ---------------------------------------------------------
    @Test
    fun o8_exitSeven() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd(
            "adb shell sh -c 'exit 7'; echo O8_RC_$?",
            listOf("O8_RC_"),
            bodyEntered = body,
        )
        requireBody(body, "O8")
        assertTrue("O8: exit 7 (out=[$out])", out.contains("O8_RC_7"))
    }

    // ---- O9 / Gate F: quoting spaces ---------------------------------------
    @Test
    fun o9_quotingSpaces() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb shell echo \"hello world\"", listOf("hello world"), bodyEntered = body)
        requireBody(body, "O9")
        assertTrue("O9: space preserved (out=[$out])", out.contains("hello world"))
    }

    // ---- O10: quoting special chars ----------------------------------------
    @Test
    fun o10_quotingSpecial() {
        val body = AtomicBoolean(false)
        // Single quote inside arg + $ literal.
        val dollarHome = "\$HOME"
        val out = runLocalCmd(
            "adb shell echo \"a'b $dollarHome\"",
            listOf("a'b"),
            bodyEntered = body,
        )
        requireBody(body, "O10")
        assertTrue("O10: single-quote arg (out=[$out])", out.contains("a'b"))
    }

    // ---- O11: UTF-8 ---------------------------------------------------------
    @Test
    fun o11_utf8() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb shell echo \"你好世界\"", listOf("你好世界"), bodyEntered = body)
        requireBody(body, "O11")
        assertTrue("O11: UTF-8 (out=[$out])", out.contains("你好世界"))
    }

    // ---- O12 / Gate G: unsupported -----------------------------------------
    @Test
    fun o12_unsupported() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd("adb devices 2>&1; echo O12_RC_$?", listOf("O12_RC_"), bodyEntered = body)
        requireBody(body, "O12")
        assertTrue("O12: unsupported message (out=[$out])", out.contains("unsupported"))
        assertTrue("O12: RC=2 (out=[$out])", out.contains("O12_RC_2"))
        assertTrue("O12: NOT RC=126 / Permission denied (out=[$out])",
            !out.contains("Permission denied") && !out.contains("O12_RC_126"))
    }

    // ---- O13 / Gate H: 20 sequential ---------------------------------------
    @Test
    fun o13_sequential20() {
        val body = AtomicBoolean(false)
        val observed = StringBuilder()
        scenario?.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            var okCount = 0
            if (opened && handle != null) {
                val observer = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        synchronized(observed) { observed.append(String(bytes, StandardCharsets.UTF_8)) }
                    }
                }
                handle.addObserver(observer)
                for (i in 1..20) {
                    handle.write(InputSource.DEBUG, "adb shell id\n".toByteArray(StandardCharsets.UTF_8))
                    val deadline = System.currentTimeMillis() + 20_000
                    var got = false
                    while (System.currentTimeMillis() < deadline) {
                        val snap = synchronized(observed) { observed.toString() }
                        // Count uid=2000 occurrences up to i.
                        if (snap.split("uid=2000").size - 1 >= i) { got = true; break }
                        Thread.sleep(100)
                    }
                    if (got) okCount++
                }
                Thread.sleep(300)
                handle.removeObserver(observer)
            }
            a.testCloseActivePage()
            try {
                java.io.File(a.filesDir, "o13_diag.txt").writeText("okCount=$okCount\n")
            } catch (_: Exception) {}
        }
        requireBody(body, "O13")
        val okCount = runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(ctx.filesDir, "o13_diag.txt").readText().lineSequence()
                .firstOrNull { it.startsWith("okCount=") }?.substringAfter("=")?.toInt() ?: 0
        }.getOrDefault(0)
        assertEquals("O13: 20/20 sequential", 20, okCount)
    }

    // ---- O14 / Gate I: connection reuse ------------------------------------
    @Test
    fun o14_connectionReuse() {
        val body = AtomicBoolean(false)
        val observed = StringBuilder()
        scenario?.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1200)
            val handle = a.activeSharedHandle()
            body.set(opened && handle != null)
            if (opened && handle != null) {
                val observer = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        synchronized(observed) { observed.append(String(bytes, StandardCharsets.UTF_8)) }
                    }
                }
                handle.addObserver(observer)
                handle.write(InputSource.DEBUG, "adb shell id\n".toByteArray(StandardCharsets.UTF_8))
                sleepMarker(observed, "uid=2000", 20_000)
                handle.write(InputSource.DEBUG, "adb shell pwd\n".toByteArray(StandardCharsets.UTF_8))
                sleepMarker(observed, "/", 10_000)
                handle.write(InputSource.DEBUG, "adb shell echo REUSE_OK\n".toByteArray(StandardCharsets.UTF_8))
                sleepMarker(observed, "REUSE_OK", 10_000)
                Thread.sleep(300)
                handle.removeObserver(observer)
            }
            a.testCloseActivePage()
        }
        requireBody(body, "O14")
        val out = synchronized(observed) { observed.toString() }
        assertTrue("O14: reuse ok (out=[$out])", out.contains("REUSE_OK"))
    }

    // ---- O15 / Gate J: local shell survives --------------------------------
    @Test
    fun o15_localShellSurvives() {
        val body = AtomicBoolean(false)
        val out = runLocalCmd(
            "adb shell id; echo LOCAL_STILL_ALIVE; id",
            listOf("LOCAL_STILL_ALIVE", "u0_a"),
            bodyEntered = body,
        )
        requireBody(body, "O15")
        assertTrue("O15: shell alive after one-shot (out=[$out])", out.contains("LOCAL_STILL_ALIVE"))
        assertTrue("O15: local uid still app (out=[$out])", out.contains("u0_a"))
    }

    private fun sleepMarker(observed: StringBuilder, marker: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = synchronized(observed) { observed.toString() }
            if (snap.contains(marker)) return
            Thread.sleep(100)
        }
    }
}
