// AT.V4-5 — Architecture Negative Proof (synthetic fixtures MUST fail).
//
// Proves the architecture checkers are NOT always-PASS scripts. Feeds
// synthetic violation fixtures through the SAME checkers used on the real
// repo and asserts each is detected (FAIL).
//
//   Case 1: consumer -> :adb-engine          (forbidden direct dep)
//   Case 2: consumer -> :at-engine           (forbidden direct dep)
//   Case 3: :at-engine-api public AdbRuntime (API surface leak)
//   Case 4: Module A -> B -> A               (DAG cycle)

package com.at.architecture

import org.junit.Assert
import org.junit.Test

class ArchitectureNegativeTest {

    // ---- Case 1: consumer directly depends on :adb-engine -> FAIL ----------
    @Test
    fun case1_consumerAdbEngineDepIsDetected() {
        val nodes = mapOf(
            "fake-consumer" to ModuleNode(
                name = "fake-consumer",
                isConsumer = true,
                directProjectDeps = setOf("at-engine-api", "at-composition-android", "adb-engine"),
            ),
            "adb-engine" to ModuleNode("adb-engine", false, emptySet()),
        )
        val violations = ArchitectureChecker.checkDirectDependencies(nodes)
        val forbidden = violations.filterIsInstance<Violation.ForbiddenDirectDependency>()
        Assert.assertTrue(
            "Case 1: consumer->:adb-engine MUST be detected; got $violations",
            forbidden.any { it.forbiddenDep == "adb-engine" }
        )
    }

    // ---- Case 2: consumer directly depends on :at-engine -> FAIL -----------
    @Test
    fun case2_consumerAtEngineDepIsDetected() {
        val nodes = mapOf(
            "fake-consumer" to ModuleNode(
                name = "fake-consumer",
                isConsumer = true,
                directProjectDeps = setOf("at-engine-api", "at-engine"),
            ),
            "at-engine" to ModuleNode("at-engine", false, emptySet()),
        )
        val violations = ArchitectureChecker.checkDirectDependencies(nodes)
        val forbidden = violations.filterIsInstance<Violation.ForbiddenDirectDependency>()
        Assert.assertTrue(
            "Case 2: consumer->:at-engine MUST be detected; got $violations",
            forbidden.any { it.forbiddenDep == "at-engine" }
        )
    }

    // ---- Case 3: :at-engine-api public surface leaks AdbRuntime -> FAIL ----
    @Test
    fun case3_engineApiAdbRuntimeLeakIsDetected() {
        val publicLines = listOf(
            "interface BadEngine {",
            "    fun connect(runtime: AdbRuntime): Unit",
            "}",
        )
        val violations = ArchitectureChecker.checkEngineApiSurface(publicLines)
        Assert.assertTrue(
            "Case 3: :at-engine-api public AdbRuntime MUST be detected; got $violations",
            violations.any { it is Violation.ApiSurfaceLeak && it.symbol == "AdbRuntime" }
        )
    }

    // ---- Case 4: Module A -> B -> A (cycle) -> FAIL ------------------------
    @Test
    fun case4_moduleDagCycleIsDetected() {
        val nodes = mapOf(
            "mod-a" to ModuleNode("mod-a", false, setOf("mod-b")),
            "mod-b" to ModuleNode("mod-b", false, setOf("mod-a")),
        )
        val violations = ArchitectureChecker.checkDagCycles(nodes)
        Assert.assertTrue(
            "Case 4: A->B->A cycle MUST be detected; got $violations",
            violations.any { it is Violation.DagCycle }
        )
    }

    // ---- Sanity: a LEGAL consumer graph must NOT be flagged ----------------
    @Test
    fun legalConsumerGraphIsClean() {
        val nodes = mapOf(
            "legal-consumer" to ModuleNode(
                name = "legal-consumer",
                isConsumer = true,
                directProjectDeps = setOf("at-engine-api", "at-composition-android", "at-cli"),
            ),
            "at-engine-api" to ModuleNode("at-engine-api", false, emptySet()),
            "at-composition-android" to ModuleNode(
                "at-composition-android", false,
                setOf("at-engine-api", "adb-engine", "at-backend-adb"),
            ),
            "at-cli" to ModuleNode("at-cli", false, setOf("at-engine-api")),
            "adb-engine" to ModuleNode("adb-engine", false, emptySet()),
            "at-backend-adb" to ModuleNode("at-backend-adb", false, setOf("adb-engine")),
        )
        val depViolations = ArchitectureChecker.checkDirectDependencies(nodes)
        val cycleViolations = ArchitectureChecker.checkDagCycles(nodes)
        Assert.assertTrue(
            "A LEGAL consumer (at-engine-api + at-composition-android + at-cli) " +
                "MUST NOT be flagged; dep=$depViolations cycle=$cycleViolations",
            depViolations.isEmpty() && cycleViolations.isEmpty()
        )
    }

    // ---- Sanity: transitive :adb-engine via composition is NOT a violation --
    @Test
    fun transitiveAdbEngineIsNotAConsumerViolation() {
        // The consumer depends on at-composition-android, which depends on
        // adb-engine. The consumer does NOT directly depend on adb-engine.
        val nodes = mapOf(
            "legal-consumer" to ModuleNode(
                name = "legal-consumer",
                isConsumer = true,
                directProjectDeps = setOf("at-engine-api", "at-composition-android"),
            ),
            "at-engine-api" to ModuleNode("at-engine-api", false, emptySet()),
            "at-composition-android" to ModuleNode(
                "at-composition-android", false, setOf("adb-engine"),
            ),
            "adb-engine" to ModuleNode("adb-engine", false, emptySet()),
        )
        val violations = ArchitectureChecker.checkDirectDependencies(nodes)
        Assert.assertTrue(
            "Consumer -> at-composition-android -> adb-engine is LEGAL (transitive); " +
                "MUST NOT be flagged; got $violations",
            violations.isEmpty()
        )
    }
}
