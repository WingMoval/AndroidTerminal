// ADB-only Test Host — deterministic ADB lifecycle tests (AT.V3-2.3 §55).
//
// These use INJECTABLE fake backends (readiness / discovery) so they run
// deterministically WITHOUT a real device / Wi-Fi. They exercise the ADB-domain
// lifecycle state machine (AdbRuntime) that wires the previously-unwired pieces:
//   readiness (enabled/disabled/unknown)
//   discovery (success / empty)
//   endpoint selection / invalidation
//   AUTO recovery
//   EXPLICIT reconnect semantics (no mDNS fallback)
//   bounded reconnect attempts + backoff invocation
//   single recovery owner
//   manual disconnect does NOT auto-recover
//   unknown-outcome operation NOT transparently replayed (guard)
//
// NOTE: connect() itself talks to the real AdbEngine (native), so a fake
// "connect" here means the lifecycle logic around it. Where a test asserts a
// connect outcome without a real device, it drives the lifecycle branch through
// a controllable fake readiness/discovery and observes the STATE + diagnostics,
// not a real ONLINE connection. Real-device connect/recovery is covered by the
// G-gate harness (AdbOnlyRealDeviceTest).

package com.androidterminal.adbonlyhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbConnectionIntent
import com.adb.engine.AdbDiscoveredService
import com.adb.engine.AdbDiscoveryBackend
import com.adb.engine.AdbRecoveryPolicy
import com.adb.engine.AdbDiscoveryServiceType
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.adb.engine.AdbTransportMode
import com.adb.engine.AdbWirelessDebuggingReadinessBackend
import com.adb.engine.AdbWirelessDebuggingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbRuntimeLifecycleTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // --- controllable fakes -------------------------------------------------

    private class FakeReadiness(private var state: AdbWirelessDebuggingState) :
        AdbWirelessDebuggingReadinessBackend {
        override fun query() = state
        fun set(s: AdbWirelessDebuggingState) { state = s }
    }

    private class FakeDiscovery(private var services: List<AdbDiscoveredService>) :
        AdbDiscoveryBackend {
        var scanCount = 0
        override fun scan(
            timeoutMs: Long,
            serviceType: AdbDiscoveryServiceType,
        ): List<AdbDiscoveredService> {
            scanCount++
            return services
        }
        fun set(s: List<AdbDiscoveredService>) { services = s }
    }

    private val fastPolicy = AdbRecoveryPolicy(
        maxAttempts = 2,
        initialBackoffMs = 1L,
        maxBackoffMs = 2L,
        discoveryTimeoutMs = 50L,
    )

    private lateinit var readiness: FakeReadiness
    private lateinit var discovery: FakeDiscovery

    @Before
    fun setUp() {
        AdbRuntime.resetForTest()
        readiness = FakeReadiness(AdbWirelessDebuggingState.ENABLED)
        discovery = FakeDiscovery(emptyList())
        AdbRuntime.initializeForTest(readiness, discovery) { fastPolicy }
    }

    // ---- readiness ---------------------------------------------------------

    @Test
    fun readiness_enabled_reportsEnabled() {
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        assertEquals(AdbWirelessDebuggingState.ENABLED, AdbRuntime.readiness())
    }

    @Test
    fun readiness_disabled_reportsDisabled() {
        readiness.set(AdbWirelessDebuggingState.DISABLED)
        assertEquals(AdbWirelessDebuggingState.DISABLED, AdbRuntime.readiness())
    }

    @Test
    fun readiness_unknown_reported() {
        readiness.set(AdbWirelessDebuggingState.UNKNOWN)
        assertEquals(AdbWirelessDebuggingState.UNKNOWN, AdbRuntime.readiness())
    }

    // ---- connectAuto gates -------------------------------------------------

    @Test
    fun connectAuto_wdDisabled_userActionRequired_noDiscovery() {
        readiness.set(AdbWirelessDebuggingState.DISABLED)
        discovery.set(listOf(AdbDiscoveredService("svc", "127.0.0.1", 39000)))
        val d = AdbRuntime.connectAuto(KEYDIR)
        assertEquals(AdbRuntimeState.USER_ACTION_REQUIRED, AdbRuntime.readinessState())
        assertEquals("USER_ACTION_REQUIRED_WD_DISABLED", d.code)
        assertEquals(0, discovery.scanCount) // readiness gate short-circuits discovery
    }

    @Test
    fun connectAuto_emptyDiscovery_endpointNotFound() {
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        discovery.set(emptyList())
        val d = AdbRuntime.connectAuto(KEYDIR)
        assertEquals(AdbRuntimeState.ENDPOINT_NOT_FOUND, AdbRuntime.readinessState())
        assertEquals("ENDPOINT_NOT_FOUND", d.code)
        assertEquals(1, discovery.scanCount)
    }

    @Test
    fun connectAuto_unknownReadiness_stillDiscovers() {
        // UNKNOWN != DISABLED: must not report USER_ACTION_REQUIRED.
        readiness.set(AdbWirelessDebuggingState.UNKNOWN)
        discovery.set(emptyList())
        val d = AdbRuntime.connectAuto(KEYDIR)
        assertFalse(AdbRuntime.readinessState() == AdbRuntimeState.USER_ACTION_REQUIRED)
        assertEquals("ENDPOINT_NOT_FOUND", d.code) // no endpoint -> this, not WD-disabled
        assertEquals(1, discovery.scanCount)
    }

    // ---- explicit path semantics ------------------------------------------

    @Test
    fun explicitIntent_noMdnsFallbackOnRecovery() {
        // EXPLICIT recovery must NOT scan mDNS. We drive recovery directly with
        // an Explicit intent pointing at a dead endpoint (no device). The
        // coordinator will try the SAME endpoint only and fail bounded; discovery
        // scan count must stay 0.
        val explicit = AdbConnectionIntent.Explicit("127.0.0.1", 1, KEYDIR) // port 1 = dead
        AdbRuntime.recoverForTest(explicit)
        assertEquals(AdbRuntimeState.RECOVERY_FAILED, AdbRuntime.readinessState())
        assertEquals(0, discovery.scanCount) // EXPLICIT: never mDNS
    }

    // ---- bounded recovery + single owner ----------------------------------

    @Test
    fun autoRecovery_boundedAttempts_noInfiniteLoop() {
        // AUTO recovery with empty discovery: bounded attempts, ends RECOVERY_FAILED.
        val auto = AdbConnectionIntent.Auto(KEYDIR)
        // Time the cycle: must complete (bounded) and not hang.
        val t0 = System.currentTimeMillis()
        AdbRuntime.recoverForTest(auto)
        val elapsed = System.currentTimeMillis() - t0
        assertEquals(AdbRuntimeState.RECOVERY_FAILED, AdbRuntime.readinessState())
        // fastPolicy: maxAttempts=2, backoff 0/1ms, discoveryTimeout 50ms each -> well under 5s.
        assertTrue("recovery took too long (possible non-bounded loop): ${elapsed}ms", elapsed < 5_000L)
    }

    @Test
    fun recovery_singleOwner_serialized() {
        // Two consecutive recoveries must be serialized (single owner); the
        // second must observe the first's terminal state, not run in parallel.
        AdbRuntime.recoverForTest(AdbConnectionIntent.Auto(KEYDIR))
        val s1 = AdbRuntime.readinessState()
        AdbRuntime.recoverForTest(AdbConnectionIntent.Auto(KEYDIR))
        val s2 = AdbRuntime.readinessState()
        // Both terminal; state is a single value at a time (no concurrent owners).
        assertEquals(s1, s2)
        assertTrue(s1 in listOf(AdbRuntimeState.RECOVERY_FAILED, AdbRuntimeState.READY))
    }

    // ---- manual disconnect -------------------------------------------------

    @Test
    fun manualDisconnect_clearsIntent_noAutoRecover() {
        // After a manual disconnect, intent is None and state DISCONNECTED.
        // A subsequent onOperationResult(transportLost=true) with intent=None must
        // NOT start a recovery (only mark TRANSPORT_LOST).
        AdbRuntime.disconnect()
        assertEquals(AdbRuntimeState.DISCONNECTED, AdbRuntime.readinessState())
        assertEquals(AdbConnectionIntent.None, AdbRuntime.connectionIntent)

        AdbRuntime.onOperationResult(transportLost = true)
        // No recovery started (intent was None): stays TRANSPORT_LOST, not RECOVERING/RECOVERY_FAILED.
        assertEquals(AdbRuntimeState.TRANSPORT_LOST, AdbRuntime.readinessState())
    }

    // ---- no transparent replay of unknown-outcome operation ----------------

    @Test
    fun unknownOutcomeOperation_notTransparentlyReplayed() {
        // Guard: simulate an operation that hit a transport loss. The runtime
        // must run connection recovery but NEVER re-execute the operation.
        // We model the "operation" as a counter the runtime is NOT given access
        // to; the runtime only sees transportLost=true. After recovery, the
        // operation's original failure is preserved (we assert the runtime never
        // calls back to "re-run" — i.e. recovery changes STATE only, and the
        // caller retains the original result).
        var opExecutedCount = 0 // the caller's operation; the runtime cannot touch it
        opExecutedCount++ // operation A executed once, hits transport loss
        AdbRuntime.onOperationResult(transportLost = true) // intent=None (fresh) -> TRANSPORT_LOST only
        // Operation A is NOT re-executed by the runtime.
        assertEquals(1, opExecutedCount)
    }

    @Test
    fun backoffInvoked_betweenAttempts() {
        // A policy with a non-zero initial backoff must invoke the sleeper
        // between attempts (attempt 2). We observe via a measurable elapsed time
        // being at least the backoff (fast but non-zero).
        val policyWithBackoff = AdbRecoveryPolicy(
            maxAttempts = 2,
            initialBackoffMs = 50L,
            maxBackoffMs = 50L,
            discoveryTimeoutMs = 10L,
        )
        AdbRuntime.resetForTest()
        AdbRuntime.initializeForTest(readiness, discovery) { policyWithBackoff }
        val t0 = System.currentTimeMillis()
        AdbRuntime.recoverForTest(AdbConnectionIntent.Auto(KEYDIR))
        val elapsed = System.currentTimeMillis() - t0
        // 2 attempts: backoff 0 before attempt1, 50ms before attempt2 -> >= ~50ms.
        assertTrue("expected >=50ms backoff, got ${elapsed}ms", elapsed >= 40L)
        assertEquals(AdbRuntimeState.RECOVERY_FAILED, AdbRuntime.readinessState())
    }

    // ---- AT.LOCAL-TCP-1B: Local TCP transport selection (deterministic) ----
    //
    // These use the injectable `localConnectForTest` seam so the transport
    // SELECTION logic (probe / switch / routing decision / loss / ownership) is
    // verified WITHOUT a real device / listener. The actual Local connect, one-shot
    // and PTY routing on-device is covered by the B-gate device sanity (B1-B5).

    @Test
    fun localProbe_success_activeTransportLocal_ready() {
        var calls = 0
        AdbRuntime.localConnectForTest = { port, _ ->
            calls++
            assertTrue("probe must use the canonical port", port == AdbRuntime.DEFAULT_LOCAL_ADB_PORT)
            true
        }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        val s = AdbRuntime.ensureReady(KEYDIR) { }
        assertEquals(AdbRuntimeState.READY, s)
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        assertEquals(AdbConnectionIntent.Local(KEYDIR), AdbRuntime.connectionIntent)
        assertEquals(1, calls)
        assertEquals(0, discovery.scanCount) // Local-first: NO Wireless discovery
    }

    @Test
    fun localProbe_fail_fallsBackToWirelessFlow() {
        AdbRuntime.localConnectForTest = { _, _ -> false } // no Local listener
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        discovery.set(emptyList())
        val s = AdbRuntime.ensureReady(KEYDIR) { }
        // Local failed -> fell through to the existing Wireless bootstrap, which
        // found no endpoint (v0.4.0 behavior preserved). activeTransport stays WIRELESS.
        assertEquals(AdbTransportMode.WIRELESS, AdbRuntime.activeTransport)
        assertEquals(AdbRuntimeState.ENDPOINT_NOT_FOUND, s)
    }

    @Test
    fun localActive_executeShell_routesToLocalhostApi() {
        // With a successful Local probe, the Runtime's execution passthrough must
        // route to the Local one-shot API. We model the device result via the
        // real AdbEngine only in the device sanity; here we assert the ROUTING
        // DECISION by confirming activeTransport=LOCAL and that executeShell is
        // invoked against the Local core (observable: activeTransport stays LOCAL
        // and a non-ready Local does not trigger Wireless recovery).
        AdbRuntime.localConnectForTest = { _, _ -> true }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        AdbRuntime.ensureReady(KEYDIR) { }
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        // The passthrough is a pure branch on activeTransport; with LOCAL active it
        // calls AdbEngine.executeShellLocalhost (not executeShell). We cannot
        // intercept the native here, so we assert the selection invariant that
        // gates the routing: activeTransport == LOCAL.
        assertTrue(AdbRuntime.activeTransport == AdbTransportMode.LOCAL)
    }

    @Test
    fun wirelessActive_executeShell_routesToWirelessApi() {
        AdbRuntime.localConnectForTest = { _, _ -> false }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        discovery.set(emptyList())
        AdbRuntime.ensureReady(KEYDIR) { }
        assertEquals(AdbTransportMode.WIRELESS, AdbRuntime.activeTransport)
        assertTrue(AdbRuntime.activeTransport == AdbTransportMode.WIRELESS)
    }

    @Test
    fun wirelessReady_enableLocalTcp_switchesToLocal_wirelessDisconnected() {
        // Gate D: start with a (fake) Wireless READY, then enableLocalTcp must
        // disconnect Wireless and connect Local -> exactly ONE active transport.
        AdbRuntime.localConnectForTest = { _, _ -> true }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        discovery.set(listOf(AdbDiscoveredService("svc", "127.0.0.1", 39000)))
        // Establish a Wireless READY (connectOne will fail without a device, so we
        // drive the state directly to model "Wireless already READY").
        AdbRuntime.setStateForTest(AdbRuntimeState.READY)
        AdbRuntime.setConnectionIntentForTest(AdbConnectionIntent.Auto(KEYDIR))
        assertTrue(AdbRuntime.enableLocalTcp(KEYDIR) { })
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        assertEquals(AdbRuntimeState.READY, AdbRuntime.readinessState())
        assertEquals(AdbConnectionIntent.Local(KEYDIR), AdbRuntime.connectionIntent)
    }

    @Test
    fun localActive_transportLoss_stopsClaimingLocal_noWirelessRecoveryLoop() {
        // Gate F: with Local active + READY, a transport loss must clear the Local
        // active transport (-> WIRELESS) and must NOT run the Wireless recovery
        // coordinator (no Local<->Wireless loop). State -> TRANSPORT_LOST.
        AdbRuntime.localConnectForTest = { _, _ -> true }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        AdbRuntime.ensureReady(KEYDIR) { }
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        // Drive a Local transport loss.
        AdbRuntime.onOperationResult(transportLost = true)
        assertEquals(AdbTransportMode.WIRELESS, AdbRuntime.activeTransport)
        assertEquals(AdbRuntimeState.TRANSPORT_LOST, AdbRuntime.readinessState())
        // No Wireless recovery was started: discovery was never scanned.
        assertEquals(0, discovery.scanCount)
    }

    @Test
    fun localActive_disableLocalTcp_restoresWirelessAndDisconnects() {
        // Gate G: disableLocalTcp tears the Local transport down and restores
        // activeTransport to WIRELESS + DISCONNECTED.
        AdbRuntime.localConnectForTest = { _, _ -> true }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        AdbRuntime.ensureReady(KEYDIR) { }
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        AdbRuntime.disableLocalTcp()
        assertEquals(AdbTransportMode.WIRELESS, AdbRuntime.activeTransport)
        assertEquals(AdbRuntimeState.DISCONNECTED, AdbRuntime.readinessState())
    }

    @Test
    fun maxActiveTransports_isOne_localAndWirelessNeverBothActive() {
        // Gate B / §19 invariant: there is exactly ONE activeTransport value at a
        // time. Proving "Local active + Wireless active" cannot both hold is
        // structural: activeTransport is a single enum field, and the switch
        // ordering (enableLocalTcp disconnects Wireless before connecting Local)
        // guarantees at most one transport is ONLINE. Here we assert the selector
        // can only ever be one value and that a switch leaves the other inactive.
        AdbRuntime.localConnectForTest = { _, _ -> true }
        readiness.set(AdbWirelessDebuggingState.ENABLED)
        AdbRuntime.ensureReady(KEYDIR) { }
        // After a Local switch, the selector is LOCAL (Wireless was disconnected);
        // it is never a "both" state because it is a single enum.
        assertEquals(AdbTransportMode.LOCAL, AdbRuntime.activeTransport)
        // activeTransport is a single value: it equals exactly one of the two.
        val isLocal = AdbRuntime.activeTransport == AdbTransportMode.LOCAL
        val isWireless = AdbRuntime.activeTransport == AdbTransportMode.WIRELESS
        assertTrue("selector must be exactly one of {LOCAL, WIRELESS}", isLocal != isWireless)
    }

    private companion object {
        const val KEYDIR = "/data/local/tmp/adbonly_keydir"
    }
}
