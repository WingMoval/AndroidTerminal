// AndroidTerminal V3 — Test Session UI Visibility test (AT.DEVAPP-0.1).
//
// Drives a REAL MainActivity (same-process instrumentation) and shows an
// INDEPENDENT Test TerminalSession in the Activity's debug test-observation
// area. Verifies:
//   - test sends bytes via AppTerminal.write(bytes)
//   - real Local PTY output arrives as ATSessionEvent.Data
//   - the UI (tvTestOutput) shows tagged input / PTY output / state
//   - session lifecycle + cleanup
//
// Human session (MainActivity's own AppTerminal) is untouched.

package com.androidterminal.app

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class AppDebugSessionVisibilityTest {

    private var scenario: ActivityScenario<MainActivity>? = null
    private var testTerminal: AppTerminal? = null
    private var observer: TestSessionObserver? = null
    private var activityRef: MainActivity? = null

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun launchRealActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { a -> activityRef = a }
    }

    @After
    fun tearDown() {
        testTerminal?.close()
        scenario?.close()
    }

    /** Await until [predicate] true or timeout. Runs on instrumentation thread. */
    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(60)
        }
        return predicate()
    }

    private fun uiText(): String = activityRef?.testObservationText() ?: ""

    /** Create an independent Test Session, wire its observer into the real Activity. */
    private fun openTestSessionOnUi(testName: String): AppTerminal {
        val t = AppTerminal(eventListener = {})
        // Single output consumer: observer forwards to UI AND records log.
        val act = activityRef!!
        val obs = TestSessionObserver(uiSink = { line -> act.appendTestLine(line) })
        observer = obs
        obs.onTestStart(testName)
        t.open(24, 80)
        // Re-point the session listener to the observer (single consumer).
        t.session?.setListener { ev ->
            obs.onEvent(ev)
            if (ev is ATSessionEvent.Exited) obs.onState(ATSessionState.EXITED)
        }
        act.attachTestObserver(obs)
        obs.onState(t.state()) // emit initial ACTIVE after attach (attach clears)
        testTerminal = t
        return t
    }

    private fun saveScreenshot(name: String) {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "at_devapp_01")
        dir.mkdirs()
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { out -> shot.compress(Bitmap.CompressFormat.PNG, 100, out) }
        android.util.Log.i("ATDev01", "screenshot saved: ${f.absolutePath}")
    }

    @Test
    fun ui1_basicCommandVisibleInUi() {
        val t = openTestSessionOnUi("UI-1")
        observer?.onTestInput("echo AT_UI_DEBUG_OK\n".toByteArray())
        t.writeText("echo AT_UI_DEBUG_OK")
        t.newline()
        val ui = await(4000) {
            val s = uiText()
            s.contains("AT_UI_DEBUG_OK") && s.contains("[PTY OUTPUT]")
        }
        if (!ui) {
            android.util.Log.e("ATDev01", "ui1 FAILED, ui text:\n" + uiText())
        }
        assertTrue("real PTY output + [PTY OUTPUT] tag in UI, got:\n" + uiText(), ui)
        saveScreenshot("ui1_basic")
    }

    @Test
    fun ui2_partialThenNewline() {
        val t = openTestSessionOnUi("UI-2")
        observer?.onTestInput("echo PARTIAL".toByteArray())
        t.writeText("echo PARTIAL")
        Thread.sleep(150)
        t.newline()
        assertTrue("PARTIAL executes after newline in UI", await(4000) { uiText().contains("PARTIAL") })
        saveScreenshot("ui2_partial")
    }

    @Test
    fun ui3_interactiveStdin() {
        val t = openTestSessionOnUi("UI-3")
        t.writeText("cat")
        t.newline()
        Thread.sleep(300)
        observer?.onTestInput("TEST_STDIN\n".toByteArray())
        t.writeText("TEST_STDIN")
        t.newline()
        assertTrue("stdin echoed in UI", await(4000) { uiText().contains("TEST_STDIN") })
        t.write(byteArrayOf(0x04)) // Ctrl-D
        saveScreenshot("ui3_stdin")
    }

    @Test
    fun ui4_ctrlCVisibleAndShellAlive() {
        val t = openTestSessionOnUi("UI-4")
        t.writeText("sleep 30")
        t.newline()
        Thread.sleep(500)
        observer?.onTestInput(byteArrayOf(0x03))
        t.ctrlC()
        Thread.sleep(600)
        t.writeText("echo AFTER_UI_CTRL_C")
        t.newline()
        assertTrue("shell alive, after-cmd visible", await(4000) { uiText().contains("AFTER_UI_CTRL_C") })
        assertEquals(ATSessionState.ACTIVE, t.state())
        saveScreenshot("ui4_ctrlc")
    }

    @Test
    fun ui5_stateVisible() {
        val t = openTestSessionOnUi("UI-5")
        assertTrue("ACTIVE in UI", await(2000) {
            uiText().contains("ACTIVE") || uiText().contains("[STATE]")
        })
        t.close()
        assertTrue("CLOSED in UI", await(2000) { uiText().contains("CLOSED") })
        saveScreenshot("ui5_state")
    }

    @Test
    fun ui6_cleanupNoLeak() {
        val t = openTestSessionOnUi("UI-6")
        t.writeText("echo PRE_CLOSE")
        t.newline()
        assertTrue(await(3000) { uiText().contains("PRE_CLOSE") })
        t.close()
        assertEquals(ATSessionState.CLOSED, t.state())
        assertEquals(-1, t.writeText("echo AFTER_CLOSE")) // not writable after close
        scenario?.onActivity { a -> a.detachTestObserver() }
        saveScreenshot("ui6_cleanup")
    }

    @Test
    fun ui7_humanTerminalUnaffected() {
        // Open the REAL Human session via the Activity and confirm it still works.
        scenario?.onActivity { a ->
            a.runOnUiThread { a.findViewById<android.widget.Button>(R.id.btnOpen).performClick() }
        }
        Thread.sleep(1500)
        scenario?.onActivity { a ->
            val input = a.findViewById<android.widget.EditText>(R.id.etInput)
            input.setText("echo HUMAN_STILL_OK")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        // Human output goes to tvOutput (not the test area).
        var human = ""
        await(3000) {
            scenario?.onActivity { a ->
                human = a.findViewById<android.widget.TextView>(R.id.tvOutput).text?.toString() ?: ""
            }
            human.contains("HUMAN_STILL_OK")
        }
        assertTrue("human session still works", human.contains("HUMAN_STILL_OK"))
        saveScreenshot("ui7_human")
    }
}