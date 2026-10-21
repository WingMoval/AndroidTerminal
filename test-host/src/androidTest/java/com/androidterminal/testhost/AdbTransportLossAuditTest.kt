// Test Host 鈥?AT.V3-3 ADB transport-loss audit (run ONLY with host-side
// orchestration).
//
// Scenario:
//   1. test connects + opens a persistent remote PTY (listener active);
//   2. test logs "READY_TO_DROP" and waits (up to 150s);
//   3. the HOST (USB adb shell) disables wireless debugging:
//        adb shell settings put global adb_wifi_enabled 0
//      which drops the app's loopback ADB transport;
//   4. the test records how the loss surfaces at the AT boundary and how the
//      ADB Engine connection state projects afterwards;
//   5. the host restores wireless debugging afterwards.
//
// This test MUST NOT be part of whole-class runs (it intentionally breaks the
// ADB connection).

package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbEngine
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbTransportLossAuditTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host get() = "127.0.0.1"
    private val port get() = (InstrumentationRegistry.getArguments().getString("port") ?: "38793").toInt()
    private val keyDir get() = InstrumentationRegistry.getArguments().getString("keyDir")
        ?: ctx.filesDir.absolutePath

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    private class Cap : (ATSessionEvent) -> Unit {
        val out = StringBuilder()
        var eof = false
        var failed: String? = null
        var exited: Int? = null
        var closed = false
        override fun invoke(ev: ATSessionEvent) {
            when (ev) {
                is ATSessionEvent.Data -> out.append(String(ev.bytes))
                is ATSessionEvent.Eof -> eof = true
                is ATSessionEvent.Failed -> failed = ev.error
                is ATSessionEvent.Exited -> exited = ev.exitCode
                is ATSessionEvent.Closed -> closed = true
                else -> {}
            }
        }
    }

    @Test(timeout = 240_000)
    fun t1_adbTransportLossSessionSignal() {
        assertTrue("connect", AdbEngine.connect(host, port, keyDir))
        val engine = com.at.engine.impl.DefaultATEngine(
            AdbOneShotBackend(AdbConnectionRequest(host, port, keyDir)),
            AdbPersistentBackend(AdbConnectionRequest(host, port, keyDir)),
        )
        val r = engine.openSession(ATSessionLaunch(command = ""))
        assertTrue("open session", r is ATOpenSessionResult.Success)
        val s = (r as ATOpenSessionResult.Success).session
        val cap = Cap()
        s.setListener(cap)
        s.write("echo LOSS_PRE\n".toByteArray())
        assertTrue("session works before loss", await(6000) { cap.out.contains("LOSS_PRE") })
        assertEquals(ATSessionState.ACTIVE, s.state)
        android.util.Log.i("ATV33_LOSS", "before_loss: state=${s.state} connOnline=${AdbEngine.connectionState().isOnline}")

        // --- host drops wireless debugging now ---
        repeat(3) { android.util.Log.i("ATV33_LOSS", "READY_TO_DROP") }

        val lost = await(150_000) { s.state != ATSessionState.ACTIVE }
        android.util.Log.i(
            "ATV33_LOSS",
            "after_loss: observed=$lost state=${s.state} eof=${cap.eof} " +
                "failed=${cap.failed} exited=${cap.exited} closed=${cap.closed} " +
                "connOnline=${AdbEngine.connectionState().isOnline}",
        )
        assertTrue("session must observe the transport loss", lost)

        // Record current contract behavior (audit evidence):
        // (1) listener state after loss
        android.util.Log.i("ATV33_LOSS", "AT_STATE_AFTER_LOSS=${s.state}")
        // (2) write after loss
        val wrc = s.write("echo AFTER_LOSS\n".toByteArray())
        android.util.Log.i("ATV33_LOSS", "WRITE_AFTER_LOSS=$wrc")
        // (3) stale connection-state projection
        android.util.Log.i("ATV33_LOSS", "CONN_STATE_AFTER_LOSS=${AdbEngine.connectionState()}")

        // (4) a NEW execution after loss must NOT be silently accepted as success.
        val t0 = System.currentTimeMillis()
        val one = engine.execute(ATExecutionRequest(command = "echo AFTER_LOSS_EXEC"))
        val dt = System.currentTimeMillis() - t0
        android.util.Log.i(
            "ATV33_LOSS",
            "NEW_EXEC_AFTER_LOSS outcome=${one.outcome} hasError=${one.hasError} " +
                "error=${one.error} tookMs=$dt",
        )
        assertTrue(
            "new execution after loss must not report COMPLETED",
            one.outcome != ATExecutionOutcome.COMPLETED,
        )
    }
}



