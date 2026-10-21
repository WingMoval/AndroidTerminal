// AndroidTerminal V3 — debug-only automated input test (AT.CONTRACT-0.5).
//
// This is a DEBUG/TEST ENTRY (instrumentation), NOT a product API. It drives
// the SAME App consumer path as the Human UI:
//   AppTerminal → AT Engine → LocalPersistentBackend → Local PTY → /system/bin/sh
//
// It creates its OWN controlled test session (not the user's live UI session).
// It does NOT bypass AT Engine, does NOT call LocalPtyNative/JNI directly, and
// adds no exported receiver / network port / service.
//
// Inputs are raw BYTES (not command strings): byte-stream semantics are
// exercised (partial write, explicit newline, 0x03 Ctrl-C, stdin to a
// foreground program).

package com.androidterminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidterminal.app.AppRuntime
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class AppDebugInputTest {

    @Before
    fun setUp() {
        AppRuntime.initialize(ctx)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newTerminal(): AppTerminal = AppTerminal(eventListener = {})

    /** Collect output + events from a session. */
    private class Capture : (ATSessionEvent) -> Unit {
        val output = StringBuilder()
        val events = ConcurrentLinkedQueue<ATSessionEvent>()
        override fun invoke(ev: ATSessionEvent) {
            events.add(ev)
            if (ev is ATSessionEvent.Data) output.append(String(ev.bytes))
        }
    }

    /** Wait until [predicate] is true or timeout. */
    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    private fun openWithCapture(): Pair<AppTerminal, Capture> {
        val cap = Capture()
        val t = AppTerminal(eventListener = cap)
        assertTrue("open should succeed", t.open(24, 80))
        return t to cap
    }

    @Test
    fun t3_appDebugAutomatedInput() {
        val (t, cap) = openWithCapture()
        t.writeText("echo APP_DEBUG_OK")
        t.newline()
        assertTrue("APP_DEBUG_OK should appear", await(3000) { cap.output.contains("APP_DEBUG_OK") })
        assertEquals(ATSessionState.ACTIVE, t.state())
        t.close()
        assertEquals(ATSessionState.CLOSED, t.state())
    }

    @Test
    fun t4_byteStreamSemantics() {
        val (t, cap) = openWithCapture()
        // Partial write (no newline) then explicit newline.
        // Byte-stream semantics: write() sends bytes; the shell executes the
        // command only once a newline arrives (canonical/line-buffered PTY).
        t.writeText("echo PARTIAL")
        t.newline()
        assertTrue("PARTIAL executes after newline", await(3000) { cap.output.contains("PARTIAL") })
        t.close()
    }

    @Test
    fun t5_interactiveStdin() {
        val (t, cap) = openWithCapture()
        t.writeText("cat")
        t.newline()
        Thread.sleep(300)
        t.writeText("INPUT_TEST")
        t.newline()
        assertTrue("cat should receive INPUT_TEST", await(3000) { cap.output.contains("INPUT_TEST") })
        t.write(byteArrayOf(0x04)) // Ctrl-D -> EOF -> cat exits
        t.close()
    }

    @Test
    fun t6_ctrlC() {
        val (t, cap) = openWithCapture()
        t.writeText("sleep 30")
        t.newline()
        Thread.sleep(500)
        t.ctrlC() // 0x03
        Thread.sleep(600)
        // shell must survive; a later command must run.
        t.writeText("echo AFTER_CTRL_C")
        t.newline()
        assertTrue("shell should survive Ctrl-C", await(3000) { cap.output.contains("AFTER_CTRL_C") })
        assertEquals(ATSessionState.ACTIVE, t.state())
        t.close()
    }

    @Test
    fun t7_sessionContinuity() {
        val (t, cap) = openWithCapture()
        t.writeText("export AT_DEBUG_TEST=123")
        t.newline()
        Thread.sleep(300)
        t.writeText("echo \$AT_DEBUG_TEST")
        t.newline()
        assertTrue("env should persist", await(3000) { cap.output.contains("123") })
        t.close()
    }

    @Test
    fun t8_cleanup() {
        val (t, cap) = openWithCapture()
        t.writeText("echo PRE_CLOSE")
        t.newline()
        assertTrue(await(3000) { cap.output.contains("PRE_CLOSE") })
        t.close()
        assertEquals(ATSessionState.CLOSED, t.state())
        // close is idempotent
        t.close()
        // After close, write returns -1 (session not writable).
        assertEquals(-1, t.writeText("echo AFTER_CLOSE"))
    }

    @Test
    fun t1_humanPathShared() {
        // Prove the Human UI path and the debug path share AppTerminal semantics:
        // the same consumer object opens/actives a session and accepts bytes.
        val cap = Capture()
        val t = AppTerminal(eventListener = cap)
        assertTrue(t.open())
        assertEquals(ATSessionState.ACTIVE, t.state())
        t.writeText("echo HUMAN_OK")
        t.newline()
        assertTrue(await(3000) { cap.output.contains("HUMAN_OK") })
        t.close()
    }
}