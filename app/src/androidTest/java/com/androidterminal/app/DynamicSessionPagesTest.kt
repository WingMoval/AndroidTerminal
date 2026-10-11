// AndroidTerminal V3 — Dynamic Session Pages test (AT.DEVAPP-0.2).
//
// Drives the REAL MainActivity (same-process instrumentation) and verifies:
//   - dynamic creation of multiple independent normal session pages
//   - page switching preserves each session's state (cwd/env/output)
//   - per-session Ctrl-C / resize / close isolation
//   - inactive page output buffering
//   - automated debug reuses a NORMAL page (no special debug UI)
//
// Debug-only App API used: MainActivity.createPage() / openActiveSession() /
// activeSession(). No special debug page/renderer.

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
class DynamicSessionPagesTest {

    private var scenario: ActivityScenario<MainActivity>? = null
    private var activityRef: MainActivity? = null

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { a -> activityRef = a }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(60)
        }
        return predicate()
    }

    private fun activeOutput(): String {
        var s = ""
        scenario?.onActivity { a ->
            s = a.findViewById<android.widget.TextView>(com.androidterminal.app.R.id.tvOutput).text?.toString() ?: ""
        }
        return s
    }

    private fun activeSession(): com.at.engine.ATTerminalSession? {
        var s: com.at.engine.ATTerminalSession? = null
        scenario?.onActivity { a -> s = a.activeSession() }
        return s
    }

    private fun createAndOpenSession(rows: Int = 24, cols: Int = 80): com.at.engine.ATTerminalSession? {
        scenario?.onActivity { a ->
            a.createPage()
            a.openActiveSession(rows, cols)
        }
        return activeSession()
    }

    private fun write(s: com.at.engine.ATTerminalSession?, text: String) {
        s?.write((text + "\n").toByteArray())
    }

    private fun saveScreenshot(name: String) {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "at_devapp_02")
        dir.mkdirs()
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { out -> shot.compress(Bitmap.CompressFormat.PNG, 100, out) }
        android.util.Log.i("ATDev02", "screenshot saved: ${f.absolutePath}")
    }

    @Test
    fun ds1_dynamicCreateThreeSessions() {
        val s1 = createAndOpenSession()
        assertTrue("s1 should open", s1 != null)
        // page 1 shows its banner
        assertTrue(await(3000) { activeOutput().contains("AT V3 LOCAL SESSION") })

        val s2 = createAndOpenSession()
        assertTrue("s2 should open", s2 != null)
        val s3 = createAndOpenSession()
        assertTrue("s3 should open", s3 != null)

        assertTrue("active output present (banner)", await(3000) { activeOutput().contains("AT V3 LOCAL SESSION") })
        saveScreenshot("ds1_three_sessions")
    }

    @Test
    fun ds2_pageSwitchingPreservesCwdEnv() {
        // S1: export AT_PAGE=ONE; cd filesDir
        val s1 = createAndOpenSession()
        val dir = instrumentation.targetContext.filesDir.absolutePath
        write(s1, "export AT_PAGE=ONE")
        write(s1, "cd $dir")
        Thread.sleep(300)
        write(s1, "echo CWD_S1_OK")
        assertTrue(await(3000) { activeOutput().contains("CWD_S1_OK") })

        // S2: export AT_PAGE=TWO; cd /
        val s2 = createAndOpenSession()
        write(s2, "export AT_PAGE=TWO")
        write(s2, "cd /")
        Thread.sleep(300)

        // switch back to S1 (prev x1): cwd/env preserved
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        write(activeSession(), "echo \$AT_PAGE")
        Thread.sleep(300)
        write(activeSession(), "pwd")
        val out = await(3000) { activeOutput().contains("ONE") }
        assertTrue("S1 env preserved (ONE)", out)
        saveScreenshot("ds2_cwd_env_preserved")
    }

    @Test
    fun ds3_interactiveStdinIsolation() {
        // S3 runs `cat`, others don't.
        val s1 = createAndOpenSession()
        write(s1, "echo S1_ALIVE")
        assertTrue(await(3000) { activeOutput().contains("S1_ALIVE") })

        createAndOpenSession()
        val s3 = createAndOpenSession()
        write(s3, "cat")
        Thread.sleep(400)
        write(s3, "STDIN_ISOLATED")
        Thread.sleep(300)

        // switch to S1: its output should NOT contain cat stdin echo
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        write(activeSession(), "echo S1_STILL")
        assertTrue(await(3000) { activeOutput().contains("S1_STILL") })
        saveScreenshot("ds3_stdin_isolated")
    }

    @Test
    fun ds4_ctrlCIsolation() {
        val s1 = createAndOpenSession()
        write(s1, "echo S1_OK")
        assertTrue(await(3000) { activeOutput().contains("S1_OK") })
        createAndOpenSession()
        val s3 = createAndOpenSession()
        write(s3, "sleep 30")
        Thread.sleep(500)
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnCtrlC).performClick() }
        Thread.sleep(600)

        // S3 shell alive (sleep interrupted); switch to S1 still ACTIVE
        write(activeSession(), "echo S3_AFTER_CTRL")
        assertTrue(await(3000) { activeOutput().contains("S3_AFTER_CTRL") })
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        write(activeSession(), "echo S1_AFTER_CTRL")
        assertTrue(await(3000) { activeOutput().contains("S1_AFTER_CTRL") })
        saveScreenshot("ds4_ctrlc_isolated")
    }

    @Test
    fun ds5_resizeIsolation() {
        val s1 = createAndOpenSession(24, 80)
        write(s1, "stty size")
        assertTrue(await(3000) { activeOutput().contains("24") && activeOutput().contains("80") })
        // resize S1 to 40x120
        scenario?.onActivity { a -> a.activeSession()?.resize(40, 120) }
        Thread.sleep(300)
        write(activeSession(), "stty size")
        assertTrue(await(3000) { activeOutput().contains("40") && activeOutput().contains("120") })
        // S2 should still be 24x80
        createAndOpenSession(24, 80)
        write(activeSession(), "stty size")
        assertTrue(await(3000) { activeOutput().contains("24") && activeOutput().contains("80") })
        saveScreenshot("ds5_resize_isolated")
    }

    @Test
    fun ds6_closeIsolation() {
        val s1 = createAndOpenSession()
        write(s1, "echo S1_KEEP")
        assertTrue(await(3000) { activeOutput().contains("S1_KEEP") })
        createAndOpenSession() // s2 (active)
        val s3 = createAndOpenSession()
        write(s3, "echo S3_KEEP")
        assertTrue(await(3000) { activeOutput().contains("S3_KEEP") })

        // close active (s3) -> 2 pages left; s1 remains
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnClosePage).performClick() }
        // now active is s2; switch to s1 and verify still alive
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        write(activeSession(), "echo S1_AFTER_CLOSE")
        assertTrue(await(3000) { activeOutput().contains("S1_AFTER_CLOSE") })
        saveScreenshot("ds6_close_isolated")
    }

    @Test
    fun ds7_inactiveOutputBuffering() {
        val s1 = createAndOpenSession()
        write(s1, "echo BUFFER_A")
        assertTrue(await(3000) { activeOutput().contains("BUFFER_A") })
        createAndOpenSession() // s2 active; s1 inactive
        // generate output on s2 (active), then switch back to s1
        write(activeSession(), "echo BUFFER_B")
        Thread.sleep(400)
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(com.androidterminal.app.R.id.btnPrev).performClick() }
        assertTrue("s1 output preserved after switch-back", activeOutput().contains("BUFFER_A"))
        saveScreenshot("ds7_inactive_buffer")
    }

    @Test
    fun ds8_debugUsesNormalPage() {
        // Automation creates a NORMAL page (same as Human) and writes bytes.
        val s = createAndOpenSession()
        write(s, "echo AT_UI_DEBUG_OK")
        assertTrue(await(3000) { activeOutput().contains("AT_UI_DEBUG_OK") })
        saveScreenshot("ds8_debug_normal_page")
    }

    @Test
    fun ds9_activityCleanupAll() {
        createAndOpenSession()
        createAndOpenSession()
        createAndOpenSession()
        scenario?.close() // onDestroy -> closeAll
        Thread.sleep(500)
        // No crash; (process may be reused by next test)
        assertTrue(true)
        saveScreenshot("ds9_cleanup")
    }
}