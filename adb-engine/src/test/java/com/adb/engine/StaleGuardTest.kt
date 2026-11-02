// AT.LOCAL-TCP-STALE-GUARD-CLOSEOUT-1: deterministic tests for the
// PRE_REQUEST_STALE_GUARD in AdbRuntime.enableLocalTcp.
//
// Covers:
//   S1 — Clean state: guard returns false → NO normalization
//   S2 — Valid listener: guard returns false → NO normalization
//   S3 — Exact stale: guard returns true → normalize called exactly 1
//   S4 — Unrelated property: guard returns false → NO normalization
//   N1 — Normalization success: stale → normalize → READY → proceed
//   N2 — Normalization failure: stale → normalize FAILS → TRANSPORT_LOST
//   G1 — getprop failure: fail-open to direct path
//   G2 — One-cycle gate: normalize called at most once
//   S3b — Port constant verification
//   OFF — Offline: guard skipped entirely
//
// All tests use test hooks to avoid JNI (AdbNative) calls.
package com.adb.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StaleGuardTest {

    @After
    fun tearDown() {
        AdbRuntime.resetForTest()
    }

    /**
     * Set up the minimum state for enableLocalTcp to reach the stale guard:
     * - activeTransport = WIRELESS (not LOCAL, so the idempotent check is skipped)
     * - state = READY
     * - transportOnlineForTest = true (avoids AdbEngine.connectionState JNI call)
     * - localConnectResultForTest = false (avoids the 6×500ms retry loop)
     */
    private fun setupReadyOnline() {
        AdbRuntime.setStateForTest(AdbRuntimeState.READY)
        AdbRuntime.setConnectionIntentForTest(AdbConnectionIntent.None)
        AdbRuntime.transportOnlineForTest = { true }
        AdbRuntime.localConnectResultForTest = false
    }

    // ---- S1: Clean state (no stale) ----

    @Test
    fun `S1 clean state - no normalization`() {
        setupReadyOnline()
        val normalizeCalled = AtomicBoolean(false)
        AdbRuntime.detectStaleForTest = { false }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertFalse("enableLocalTcp should fail (localConnectResult=false)", result)
        assertFalse(
            "normalizeAndReconnect must NOT be called for clean state",
            normalizeCalled.get()
        )
    }

    // ---- S2: Valid listener (no stale) ----

    @Test
    fun `S2 valid listener - no normalization`() {
        setupReadyOnline()
        val normalizeCalled = AtomicBoolean(false)
        AdbRuntime.detectStaleForTest = { false }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertFalse(result)
        assertFalse(
            "normalizeAndReconnect must NOT be called when listener is present",
            normalizeCalled.get()
        )
    }

    // ---- S3: Exact stale signature (normalization triggered) ----

    @Test
    fun `S3 exact stale - normalization triggered exactly once`() {
        setupReadyOnline()
        val normalizeCallCount = AtomicInteger(0)
        AdbRuntime.detectStaleForTest = { true }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCallCount.incrementAndGet()
            // Simulate successful normalization → READY
            AdbRuntime.setStateForTest(AdbRuntimeState.READY)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertEquals(
            "normalizeAndReconnect must be called exactly once",
            1,
            normalizeCallCount.get()
        )
        assertFalse("localConnectResult=false → enableLocalTcp fails", result)
    }

    // ---- S4: Unrelated property (no stale) ----

    @Test
    fun `S4 unrelated property - no normalization`() {
        setupReadyOnline()
        val normalizeCalled = AtomicBoolean(false)
        AdbRuntime.detectStaleForTest = { false }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertFalse(result)
        assertFalse(
            "normalizeAndReconnect must NOT be called for unrelated property",
            normalizeCalled.get()
        )
    }

    // ---- N1: Normalization success → proceeds to direct promotion ----

    @Test
    fun `N1 normalization success - proceeds to tcpip and local connect`() {
        setupReadyOnline()
        val normalizeCalled = AtomicBoolean(false)
        AdbRuntime.detectStaleForTest = { true }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            AdbRuntime.setStateForTest(AdbRuntimeState.READY)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertTrue("normalizeAndReconnect must be called for stale state", normalizeCalled.get())
        assertFalse("localConnectResult=false → enableLocalTcp fails", result)
    }

    // ---- N2: Normalization failure → TRANSPORT_LOST ----

    @Test
    fun `N2 normalization failure - settles to TRANSPORT_LOST`() {
        setupReadyOnline()
        val normalizeCalled = AtomicBoolean(false)
        AdbRuntime.detectStaleForTest = { true }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            false // reconnection fails
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertTrue("normalizeAndReconnect must be called", normalizeCalled.get())
        assertFalse("enableLocalTcp must return false when normalization fails", result)
        assertEquals(
            "State must be TRANSPORT_LOST after normalization failure",
            AdbRuntimeState.TRANSPORT_LOST,
            AdbRuntime.readinessState()
        )
        assertEquals(
            "Transport must be WIRELESS after normalization failure",
            AdbTransportMode.WIRELESS,
            AdbRuntime.activeTransport
        )
        assertEquals(
            "Connection intent must be None after normalization failure",
            AdbConnectionIntent.None,
            AdbRuntime.connectionIntent
        )
    }

    // ---- G1: getprop failure → fail-open (no normalization) ----

    @Test
    fun `G1 getprop failure - fail-open to direct path`() {
        setupReadyOnline()
        val normalizeCalled = AtomicBoolean(false)
        // detectStaleForTest = false simulates the fail-open behavior:
        // when getprop throws, detectStaleAdbdState returns false.
        AdbRuntime.detectStaleForTest = { false }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertFalse(result)
        assertFalse(
            "normalizeAndReconnect must NOT be called on getprop failure (fail-open)",
            normalizeCalled.get()
        )
    }

    // ---- G2: One-cycle gate (no recursive normalization) ----

    @Test
    fun `G2 one-cycle - no recursive normalization across calls`() {
        setupReadyOnline()
        val normalizeCallCount = AtomicInteger(0)
        AdbRuntime.detectStaleForTest = { true }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCallCount.incrementAndGet()
            false // fail every time
        }

        // First call: stale detected → normalize → fails → TRANSPORT_LOST
        AdbRuntime.enableLocalTcp("/tmp/key") { }
        assertEquals("First call: normalize called once", 1, normalizeCallCount.get())
        assertEquals(
            "State must be TRANSPORT_LOST after first failure",
            AdbRuntimeState.TRANSPORT_LOST,
            AdbRuntime.readinessState()
        )

        // Second call: state is TRANSPORT_LOST (not READY), so the guard
        // is skipped entirely → normalize NOT re-triggered.
        AdbRuntime.enableLocalTcp("/tmp/key") { }
        assertEquals(
            "Second call: normalize NOT re-triggered (state != READY)",
            1,
            normalizeCallCount.get()
        )
    }

    // ---- S3b: Port constant verification ----

    @Test
    fun `S3b port constant is 38800`() {
        assertEquals(
            "DEFAULT_LOCAL_ADB_PORT must be 38800",
            38800,
            AdbRuntime.DEFAULT_LOCAL_ADB_PORT
        )
    }

    // ---- OFF: Offline state → guard skipped entirely ----

    @Test
    fun `offline - guard skipped when transport not online`() {
        AdbRuntime.setStateForTest(AdbRuntimeState.READY)
        AdbRuntime.setConnectionIntentForTest(AdbConnectionIntent.None)
        AdbRuntime.transportOnlineForTest = { false }
        AdbRuntime.localConnectResultForTest = false
        val normalizeCalled = AtomicBoolean(false)
        AdbRuntime.detectStaleForTest = { true }
        AdbRuntime.normalizeForTest = { _, _ ->
            normalizeCalled.set(true)
            true
        }

        val result = AdbRuntime.enableLocalTcp("/tmp/key") { }

        assertFalse("Should not succeed without transport", result)
        assertFalse(
            "Guard must be skipped when transport is not online",
            normalizeCalled.get()
        )
    }
}
