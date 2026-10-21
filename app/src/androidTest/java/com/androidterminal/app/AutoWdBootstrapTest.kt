// AT.V4-4.3 — First-use Auto-WD permission self-grant bootstrap test.
//
// Validates the complete FIRST-USE BOOTSTRAP lifecycle:
//   pair success -> connect READY -> self-grant WRITE_SECURE_SETTINGS
//   -> auto-WD (WD OFF -> ensureReady -> auto-enable -> connect -> uid=2000)
//   -> restart persistence (same identity, permission retained, no re-grant)
//
// Execution: direct `adb shell am instrument` (NOT Gradle connectedDebugAndroidTest).
// The Product app + androidTest APK are manually installed ONCE and remain
// installed (no UTP uninstall).

package com.androidterminal.app

import com.at.composition.android.ATAdbReadinessState
import com.at.composition.android.ATAndroidAdbControl
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionLaunch
import com.at.engine.ATTerminalSession

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class AutoWdBootstrapTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun b_permissionAbsentSelfGrant() {
        AppRuntime.initialize(ctx)
        AppRuntime.connectAuto()
        val ready = AppRuntime.adbState() == ATAdbReadinessState.READY
        Assert.assertTrue("ADB must be READY before self-grant", ready)

        // Check current permission state
        val before = AppRuntime.adbControl.canWriteSecureSettings()
        println("AT.V4-4.3 Gate B: WRITE_SECURE_SETTINGS before = $before")

        // Execute self-grant (idempotent)
        val result = AppRuntime.adbControl.completeFirstUseBootstrap()
        println("AT.V4-4.3 Gate B: self-grant result = $result")

        // Verify permission after
        val after = AppRuntime.adbControl.canWriteSecureSettings()
        println("AT.V4-4.3 Gate B: WRITE_SECURE_SETTINGS after = $after")

        // Identity must be unchanged
        val idDir = java.io.File(ctx.filesDir, "adb_identity")
        val pubKey = java.io.File(idDir, "adbkey.pub")
        Assert.assertTrue("identity must exist", pubKey.exists())

        Assert.assertTrue(
            "WRITE_SECURE_SETTINGS must be granted after self-grant " +
            "(before=$before, after=$after, result=$result)",
            after
        )
    }

    @Test
    fun c_autoWdPostBootstrap() {
        AppRuntime.initialize(ctx)

        // Turn WD OFF
        android.provider.Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 0)
        Thread.sleep(1000)
        val wdOff = android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 1) == 0
        println("AT.V4-4.3 Gate C: WD OFF = $wdOff")

        // ensureReady should auto-enable WD (WRITE_SECURE_SETTINGS now granted)
        val ready = AppRuntime.connectAuto()
        Thread.sleep(3000)
        val state = AppRuntime.adbState()
        println("AT.V4-4.3 Gate C: after ensureReady, state = $state")

        val wdOn = android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
        println("AT.V4-4.3 Gate C: WD auto-enabled = $wdOn")

        // Verify remote principal
        val c = Consumer()
        Assert.assertTrue("open ADB session", c.open(AppRuntime.adbEngine(), "sh"))
        c.write("id")
        Assert.assertTrue(
            "uid=2000 after auto-WD; got: ${c.out()}",
            await(15000) { c.out().contains("uid=2000") }
        )
        c.close()

        Assert.assertTrue("state must be READY after auto-WD", state == ATAdbReadinessState.READY)
        Assert.assertTrue("WD must be auto-enabled", wdOn)
    }

    @Test
    fun e_restartPersistence() {
        AppRuntime.initialize(ctx)
        AppRuntime.connectAuto()
        val ready = AppRuntime.adbState() == ATAdbReadinessState.READY
        Assert.assertTrue("ADB READY before restart check", ready)

        // Permission must be retained (no re-grant needed)
        val permGranted = AppRuntime.adbControl.canWriteSecureSettings()
        println("AT.V4-4.3 Gate E: permission after restart = $permGranted")

        // Self-grant should be SKIPPED (idempotent)
        val result = AppRuntime.adbControl.completeFirstUseBootstrap()
        println("AT.V4-4.3 Gate E: self-grant result (should be SKIPPED) = $result")

        Assert.assertTrue("permission must be retained", permGranted)
        Assert.assertEquals(
            "self-grant must be SKIPPED_ALREADY_GRANTED on restart",
            ATAndroidAdbControl.FirstUseBootstrapResult.SKIPPED_ALREADY_GRANTED,
            result
        )
    }

    // ---- helpers (same pattern as ProgrammaticAdbConsumerTest) ----

    private inline fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val d = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < d) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    private inner class Consumer {
        private val buf = StringBuilder()
        private var session: ATTerminalSession? = null

        fun open(engine: com.at.engine.ATEngine, command: String): Boolean {
            val r = engine.openSession(
                ATSessionLaunch(command = command, initialRows = 24, initialCols = 80),
            )
            return when (r) {
                is ATOpenSessionResult.Success -> {
                    session = r.session
                    r.session.setListener { ev ->
                        if (ev is ATSessionEvent.Data) {
                            synchronized(buf) { buf.append(String(ev.bytes)) }
                        }
                    }
                    true
                }
                is ATOpenSessionResult.Failure -> false
            }
        }

        fun write(text: String): Int = session?.write((text + "\n").toByteArray()) ?: -1
        fun out(): String = synchronized(buf) { buf.toString() }
        fun close() { runCatching { session?.close() } }
    }
}
