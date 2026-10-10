// Local Backend — one-shot execution backend (real).
//
// AT.PERSIST-0 — migrates the V2 ATsh.T0/T0.5 verified semantics:
//   ProcessBuilder -> /system/bin/sh -c -> stdout / stderr / exitCode
// Applies the AT Contract: non-zero process exit != backend failure.

package com.at.backend.local

import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATOneShotBackend
import java.nio.charset.StandardCharsets

/**
 * Local one-shot backend.
 *
 * Spawns `/system/bin/sh -c <command>` via ProcessBuilder (same principal:
 * the app's own UID / untrusted_app — V2 ATsh.T0 evidence). stdout/stderr are
 * drained concurrently to avoid pipe deadlock (V2 LocalShellExecutor design).
 */
class LocalOneShotBackend : ATOneShotBackend {

    private companion object {
        const val SHELL = "/system/bin/sh"
    }

    override fun execute(request: ATExecutionRequest): ATExecutionResult {
        val pb = ProcessBuilder(SHELL, "-c", request.command)
        val process = try {
            pb.start()
        } catch (t: Throwable) {
            // Infrastructure failure (spawn) — NOT a process exit.
            return ATExecutionResult(
                outcome = ATExecutionOutcome.TRANSPORT_ERROR,
                stdout = "",
                stderr = "",
                exitCode = -1,
                hasError = true,
                error = "spawn failed: ${t.message}",
            )
        }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val stdoutDrain = Thread({
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = r.read(chunk)
                    if (n < 0) break
                    stdoutBuf.append(chunk, 0, n)
                }
            }
        }, "at-local-stdout")

        val stderrDrain = Thread({
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = r.read(chunk)
                    if (n < 0) break
                    stderrBuf.append(chunk, 0, n)
                }
            }
        }, "at-local-stderr")

        stdoutDrain.start()
        stderrDrain.start()

        val exitCode = try {
            process.waitFor()
        } catch (t: Throwable) {
            return ATExecutionResult(
                outcome = ATExecutionOutcome.TRANSPORT_ERROR,
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
                exitCode = -1,
                hasError = true,
                error = "waitFor failed: ${t.message}",
            )
        }
        stdoutDrain.join()
        stderrDrain.join()

        // Non-zero process exit is COMPLETED (device/process outcome), not a
        // backend failure (AT.CONTRACT-0 §4.2).
        return ATExecutionResult(
            outcome = ATExecutionOutcome.COMPLETED,
            stdout = stdoutBuf.toString(),
            stderr = stderrBuf.toString(),
            exitCode = exitCode,
            hasError = false,
            error = null,
        )
    }
}