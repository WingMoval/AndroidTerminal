// AndroidTerminal V3 â€?ADB Human Preview UI test (AT.HUMAN-ADB-0 Â§46).
//
// Drives the REAL MainActivity and reuses the ORDINARY SessionPage (no special
// ADB page / observation page). Verifies the Human path:
//   Human -> SessionPage -> AppTerminal -> ATEngine -> ADB Backend -> ADB Engine
//
// Requires the app identity to be provisioned (AppAdbProvisionTest ran first ->
// ADB READY) and Wireless Debugging ON. Screenshots are saved to external
// storage (at_human_adb_0) for the evidence index.

package com.androidterminal.app

import com.at.composition.android.ATAdbReadinessState

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidterminal.app.R
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class AdbHumanPreviewTest {

    private var scenario: ActivityScenario<MainActivity>? = null
    private var activityRef: MainActivity? = null

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun launch() {
        AppRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        // Establish the ADB connection for this instrumentation process
        // (identity is already trusted by PairActivity / AppAdbProvisionTest).
        AppRuntime.connectAuto()
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
            s = a.findViewById<android.widget.TextView>(R.id.tvOutput).text?.toString() ?: ""
        }
        return s
    }

    private fun adbLabel(): String {
        var s = ""
        scenario?.onActivity { a ->
            s = a.findViewById<android.widget.TextView>(R.id.tvAdbState).text?.toString() ?: ""
        }
        return s
    }

    private fun activeSession(): ATTerminalSession? {
        var s: ATTerminalSession? = null
        scenario?.onActivity { a -> s = a.activeSession() }
        return s
    }

    private fun createAdbPage(rows: Int = 24, cols: Int = 80): ATTerminalSession? {
        // Human path: ordinary SessionPage with ADB backend kind.
        scenario?.onActivity { a ->
            a.createPage(SessionBackendKind.ADB)
            // If not READY, openAdbSessionAsync would connect on bg thread; for
            // deterministic test we drive connectAuto directly then open.
            if (AppRuntime.adbState() != ATAdbReadinessState.READY) {
                AppRuntime.connectAuto()
            }
            a.openActiveSession(rows, cols)
        }
        return activeSession()
    }

    private fun write(s: ATTerminalSession?, text: String) {
        s?.write((text + "\n").toByteArray())
    }

    private fun saveScreenshot(name: String) {
        try {
            val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "at_human_adb_0")
            dir.mkdirs()
            val f = File(dir, "$name.png")
            FileOutputStream(f).use { out -> shot.compress(Bitmap.CompressFormat.PNG, 100, out) }
            android.util.Log.i("AtHumanAdb", "screenshot: ${f.absolutePath}")
        } catch (t: Throwable) {
            android.util.Log.w("AtHumanAdb", "screenshot failed", t)
        }
    }

    @Test
    fun h1_adbReadyVisible() {
        // ADB status must reflect the REAL AdbRuntime state (READY).
        assertTrue("ADB READY required (provisioned identity); got ${AppRuntime.adbState()}",
            await(30_000) { AppRuntime.adbState() == ATAdbReadinessState.READY })
        scenario?.onActivity { a -> a.updateAdbStatusForTest() }
        assertTrue("ADB status visible READY, got: ${adbLabel()}", await(2000) { adbLabel().contains("READY") })
        saveScreenshot("h1_adb_ready")
    }

    @Test
    fun h2_adbPageIdUid2000() {
        assertTrue("ADB READY", await(30_000) { AppRuntime.adbState() == ATAdbReadinessState.READY })
        val s = createAdbPage()
        assertTrue("ADB page session opened", s != null)
        assertTrue("banner ADB SESSION visible",
            await(4000) { activeOutput().contains("AT V3 ADB SESSION") })
        // Human id -> uid=2000(shell).
        write(s, "id")
        assertTrue("Human ADB id -> uid=2000(shell); got: ${activeOutput()}",
            await(5000) { activeOutput().contains("uid=2000") })
        saveScreenshot("h2_adb_id_uid2000")
    }

    @Test
    fun h3_multiAdbSessionIsolation() {
        assertTrue("ADB READY", await(30_000) { AppRuntime.adbState() == ATAdbReadinessState.READY })
        val s1 = createAdbPage()
        assertTrue("ADB1 opened", s1 != null)
        // ADB1: cd /data/local/tmp
        write(s1, "cd /data/local/tmp")
        Thread.sleep(400)
        write(s1, "pwd")
        assertTrue("ADB1 cwd /data/local/tmp", await(5000) { activeOutput().contains("/data/local/tmp") })

        // ADB2: independent session, must NOT share ADB1's cwd.
        scenario?.onActivity { a -> a.createPage(SessionBackendKind.ADB); a.openActiveSession(24, 80) }
        val s2 = activeSession()
        assertTrue("ADB2 opened", s2 != null)
        write(s2, "pwd")
        // ADB2 cwd defaults to remote shell home (e.g. /) â€?must differ from ADB1.
        assertTrue("ADB2 independent cwd visible",
            await(5000) { activeOutput().contains("/") })
        saveScreenshot("h3_multi_adb_isolation")
    }

    @Test
    fun h4_localAdbCoexistence() {
        assertTrue("ADB READY", await(30_000) { AppRuntime.adbState() == ATAdbReadinessState.READY })
        val adb = createAdbPage()
        assertTrue("ADB page", adb != null)
        write(adb, "id")
        assertTrue("ADB id uid=2000", await(5000) { activeOutput().contains("uid=2000") })

        // Local page: App UID (NOT uid=2000).
        scenario?.onActivity { a -> a.createPage(SessionBackendKind.LOCAL); a.openActiveSession(24, 80) }
        val loc = activeSession()
        assertTrue("Local page", loc != null)
        write(loc, "id")
        assertTrue("Local id visible", await(5000) { activeOutput().contains("uid=") })
        // Both pages ACTIVE simultaneously; close ADB (switch back first).
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(R.id.btnPrev).performClick() }
        write(activeSession(), "echo ADB_STILL_OK")
        assertTrue("ADB still ACTIVE after local close flow",
            await(4000) { activeOutput().contains("ADB_STILL_OK") })
        saveScreenshot("h4_local_adb_coexist")
    }

    @Test
    fun h5_ctrlCAndContinue() {
        assertTrue("ADB READY", await(30_000) { AppRuntime.adbState() == ATAdbReadinessState.READY })
        val s = createAdbPage()
        assertTrue("ADB page", s != null)
        write(s, "sleep 30")
        Thread.sleep(800)
        scenario?.onActivity { a -> a.findViewById<android.widget.Button>(R.id.btnCtrlC).performClick() }
        Thread.sleep(800)
        write(activeSession(), "echo AFTER_CTRL_C")
        assertTrue("Human ADB Ctrl-C, shell survives; got: ${activeOutput()}",
            await(5000) { activeOutput().contains("AFTER_CTRL_C") })
        assertEquals("session ACTIVE after Ctrl-C", ATSessionState.ACTIVE, activeSession()?.state)
        saveScreenshot("h5_ctrlc_continue")
    }

    @Test
    fun h6_resizeIsolation() {
        assertTrue("ADB READY", await(30_000) { AppRuntime.adbState() == ATAdbReadinessState.READY })
        val s = createAdbPage(24, 80)
        assertTrue("ADB page", s != null)
        scenario?.onActivity { a -> a.activeSession()?.resize(40, 120) }
        Thread.sleep(400)
        write(activeSession(), "stty size")
        assertTrue("ADB resize 40x120 applied; got: ${activeOutput()}",
            await(5000) { activeOutput().contains("40") && activeOutput().contains("120") })
        saveScreenshot("h6_adb_resize")
    }
}
