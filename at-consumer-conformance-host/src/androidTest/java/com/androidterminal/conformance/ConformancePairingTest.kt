// AT.V4-5 — One-shot first-use pairing for the conformance host (temporary).
//
// The conformance host owns an independent app-private identity
// (files/conformance-identity). Its FIRST device trust is established here via
// the canonical public composition facade (adbControl.pairDiscovered) — NOT by
// copying the Product key, NOT via PC adb. The pairing code is supplied by the
// orchestrator via the instrumentation argument `-e paircode <6digits>`.
//
// This is a VALIDATION-ONLY first-use trust establishment (CONFORMANCE_HOST_
// FIRST_USE_PAIRING_COUNT = 1). It does NOT touch Product / ConsumerHost.

package com.androidterminal.conformance

import com.at.composition.android.ATAdbPairingResult

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConformancePairingTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstUsePairing() {
        val args = InstrumentationRegistry.getArguments()
        val code = args.getString("paircode")
        checkNotNull(code) { "paircode argument required (6 digits)" }
        check(code.length == 6) { "paircode must be 6 digits, got: $code" }

        ConformanceHost.initialize(ctx)
        val comp = ConformanceHost.composition()!!

        val result: ATAdbPairingResult = comp.adbControl.pairDiscovered(code)
        println("AT.V4-5 CONFORMANCE PAIRING: outcome=${result.outcome} " +
            "success=${result.success} guid=${result.deviceGuid} error=${result.error}")

        // Pairing must succeed (this is the authorized first-use trust).
        org.junit.Assert.assertTrue(
            "conformance first-use pairing must succeed; outcome=${result.outcome} " +
                "error=${result.error}",
            result.success
        )

        // After pairing, drive ensureReady to establish the connection.
        val ready = comp.adbControl.ensureReady { line ->
            println("  [ensureReady] $line")
        }
        println("AT.V4-5 CONFORMANCE PAIRING: ensureReady=$ready " +
            "state=${comp.adbControl.readinessState()}")
    }
}
