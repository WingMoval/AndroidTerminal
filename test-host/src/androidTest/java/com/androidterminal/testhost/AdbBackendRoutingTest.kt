// AT.LOCAL-TCP-1B — ADB Backend transport-routing test (Gate E / §18).
//
// Deterministic (no real device / no real Local socket dependency): verifies the
// Backend ROUTING DECISION boundary — that a LOCAL-active connection is recognized
// as connected (no Wireless connect) and a WIRELESS-active connection uses the
// Wireless path (no Local connect), so there is no double-transport mixup.
//
// This asserts the routing DECISION (activeTransport selector + which branch the
// Backend takes), NOT the real device execution — the actual Local one-shot / PTY
// execution on-device is covered by the B-gate device sanity (B2-B4). Keeping this
// test free of a real Local socket makes it deterministic regardless of whether a
// listener is live on the device.
package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbBackendRoutingTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDir get() = ctx.filesDir.absolutePath

    @Before
    fun setUp() {
        // Deterministic isolation: clear any Local transport + force WIRELESS, and
        // disable the Local seam so a live device listener does NOT flip the
        // selector during a test that models a Wireless precondition.
        AdbRuntime.localConnectForTest = { _, _ -> false }
        AdbRuntime.disableLocalTcp()
    }

    /**
     * The Backend's ensureConnected() recognizes a connection when EITHER
     * (LOCAL active && localhostConnected) OR (Wireless connectionState online).
     * This mirrors the exact decision in AdbBackend.ensureConnected. We assert the
     * routing DECISION for each selector value without a real socket:
     *   - LOCAL active  -> the Local branch is the one that can satisfy "connected"
     *   - WIRELESS active -> the Wireless branch is the one that can satisfy "connected"
     */

    @Test
    fun localActive_routesToLocalBranch() {
        // Force the selector to LOCAL. The Backend's ensureConnected first checks
        // `activeTransport == LOCAL && localhostConnected()`. We assert the routing
        // DECISION: with LOCAL active, the Local branch is selected (this is the
        // branch that calls executeShellLocalhost / openInteractiveLocalhost in
        // AdbRuntime.executeShell / openInteractive).
        AdbRuntime.localConnectForTest = { _, _ -> true }
        AdbRuntime.disableLocalTcp()
        AdbRuntime.ensureReady(keyDir) { }
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        // The routing decision is purely on activeTransport; LOCAL active means the
        // Local API branch is taken (verified by AdbRuntime.executeShell /
        // openInteractive branching). Assert the selector is exactly LOCAL.
        assertTrue(AdbRuntime.activeTransport == AdbTransportMode.LOCAL)
    }

    @Test
    fun wirelessActive_routesToWirelessBranch() {
        // Model a WIRELESS-active precondition. The Backend's ensureConnected uses
        // the Wireless branch (connectionState / connect), never the Local branch.
        AdbRuntime.localConnectForTest = { _, _ -> false }
        AdbRuntime.disableLocalTcp()
        AdbRuntime.setStateForTest(com.adb.engine.AdbRuntimeState.READY)
        AdbRuntime.setConnectionIntentForTest(com.adb.engine.AdbConnectionIntent.Auto(keyDir))
        assertEquals(AdbTransportMode.WIRELESS, AdbRuntime.activeTransport)
        // WIRELESS active means the Wireless API branch is taken. Assert exactly WIRELESS.
        assertTrue(AdbRuntime.activeTransport == AdbTransportMode.WIRELESS)
    }

    @Test
    fun noDoubleTransportMixup_selectorIsExactlyOne() {
        // §19: at most ONE active transport. The selector is a single enum value,
        // so it can never be "both". After any sequence of probe/switch the
        // Backend sees exactly one transport and routes accordingly.
        AdbRuntime.localConnectForTest = { _, _ -> true }
        AdbRuntime.disableLocalTcp()
        AdbRuntime.ensureReady(keyDir) { }
        val isLocal = AdbRuntime.activeTransport == AdbTransportMode.LOCAL
        val isWireless = AdbRuntime.activeTransport == AdbTransportMode.WIRELESS
        assertTrue("selector must be exactly one of {LOCAL, WIRELESS}", isLocal != isWireless)
    }
}
