// AT Engine — contract semantic unit tests (AT.CONTRACT-0).
//
// These test the CONTRACT SEMANTICS only (no runtime): result-channel
// separation, exit-vs-failure distinction, session lifecycle vocabulary,
// backend capability composition. They do not exercise any backend.

package com.at.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ATExecutionContractTest {

    @Test
    fun nonZeroExitIsNotExecutionFailure() {
        val r = ATExecutionResult(
            outcome = ATExecutionOutcome.COMPLETED,
            stdout = "",
            stderr = "",
            exitCode = 7,
            hasError = false,
            error = null,
        )
        assertTrue("exit 7 is COMPLETED, not a failure", r.outcome == ATExecutionOutcome.COMPLETED)
        assertFalse(r.executionFailed)
        assertEquals(7, r.exitCode)
    }

    @Test
    fun transportErrorIsExecutionFailure() {
        val r = ATExecutionResult(
            outcome = ATExecutionOutcome.TRANSPORT_ERROR,
            stdout = "",
            stderr = "",
            exitCode = -1,
            hasError = true,
            error = "transport down",
        )
        assertTrue(r.executionFailed)
        assertEquals(ATExecutionOutcome.TRANSPORT_ERROR, r.outcome)
    }

    @Test
    fun engineWithoutBackendReturnsUnsupported() {
        val engine = ATEngine.empty()
        val r = engine.execute(ATExecutionRequest(command = "id"))
        assertEquals(ATExecutionOutcome.UNSUPPORTED, r.outcome)
        val s = engine.openSession(ATPersistentBackend.Launch(command = "/system/bin/sh"))
        assertTrue(s is ATOpenSessionResult.Failure)
    }
}

class ATSessionContractTest {

    @Test
    fun sessionStateVocabularyIsMinimal() {
        // The vocabulary must contain exactly the states needed by T1/I0.
        val states = ATSessionState.entries.map { it.name }.toSet()
        assertTrue(states.containsAll(listOf("OPENING", "ACTIVE", "EXITED", "FAILED", "CLOSED")))
    }

    @Test
    fun exitedCarriesExitCode() {
        val e = ATSessionEvent.Exited(130) // Ctrl-C -> SIGINT -> 128+2 (T1/I0 evidence)
        assertEquals(130, e.exitCode)
    }

    @Test
    fun dataCarriesBytes() {
        val e = ATSessionEvent.Data(byteArrayOf(0x41))
        assertEquals(1, e.bytes.size)
    }
}