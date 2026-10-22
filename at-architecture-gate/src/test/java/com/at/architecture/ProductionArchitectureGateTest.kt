// AT.V4-5 — Production Architecture Gate (reads the REAL repository).
//
// Verifies the frozen V4 Consumer Boundary against the actual build files and
// sources. If any gate fails, the test FAILs (machine-enforced, not
// documentation).

package com.at.architecture

import org.junit.Assert
import org.junit.Test

class ProductionArchitectureGateTest {

    private val root = RepoLoader.repoRoot()

    // All modules in the graph (consumer + internal).
    private val allModules = listOf(
        "adb-engine", "at-engine-api", "at-engine-spi", "at-engine",
        "at-backend-local", "at-backend-adb", "at-composition-android",
        "at-cli", "app", "at-cli-consumer-host", "at-consumer-conformance-host",
    )

    // ---- Gate A: direct consumer dependency --------------------------------
    @Test
    fun forbiddenDirectDependencyCount() {
        val nodes = RepoLoader.buildModuleGraph(root, allModules)
        val violations = ArchitectureChecker.checkDirectDependencies(nodes)
        val forbidden = violations.filterIsInstance<Violation.ForbiddenDirectDependency>()
        for (v in forbidden) {
            println("VIOLATION: ${v.description}")
        }
        Assert.assertTrue(
            "FORBIDDEN_DIRECT_DEPENDENCY_COUNT must be 0; got ${forbidden.size}: $forbidden",
            forbidden.isEmpty()
        )
    }

    // ---- Gate B: consumer source import boundary ---------------------------
    @Test
    fun forbiddenConsumerImportCount() {
        val consumers = ConsumerBoundary.CONSUMERS.toList()
        val sources = RepoLoader.consumerProductionSources(root, consumers)
        val violations = ArchitectureChecker.checkConsumerImports(sources)
        val forbidden = violations.filterIsInstance<Violation.ForbiddenConsumerImport>()
        for (v in forbidden) {
            println("VIOLATION: ${v.description}")
        }
        Assert.assertTrue(
            "FORBIDDEN_CONSUMER_IMPORT_COUNT must be 0; got ${forbidden.size}: $forbidden",
            forbidden.isEmpty()
        )
    }

    // ---- Gate C: :at-engine-api public surface leak ------------------------
    @Test
    fun atEngineApiPublicLeakCount() {
        val lines = RepoLoader.engineApiPublicSourceLines(root)
        val violations = ArchitectureChecker.checkEngineApiSurface(lines)
        for (v in violations) {
            println("VIOLATION: ${v.description}")
        }
        Assert.assertTrue(
            "AT_ENGINE_API_PUBLIC_LEAK_COUNT must be 0; got ${violations.size}: $violations",
            violations.isEmpty()
        )
    }

    // ---- Gate D: module dependency DAG cycle -------------------------------
    @Test
    fun moduleDagCycleCount() {
        val nodes = RepoLoader.buildModuleGraph(root, allModules)
        val violations = ArchitectureChecker.checkDagCycles(nodes)
        for (v in violations) {
            println("VIOLATION: ${v.description}")
        }
        Assert.assertTrue(
            "MODULE_DAG_CYCLE_COUNT must be 0; got ${violations.size}: $violations",
            violations.isEmpty()
        )
    }

    // ---- Current consumer regression (app + at-cli-consumer-host) ----------
    @Test
    fun appConsumerBoundary() {
        val nodes = RepoLoader.buildModuleGraph(root, listOf("app"))
        val v = ArchitectureChecker.checkDirectDependencies(nodes)
        Assert.assertTrue("APP_CONSUMER_BOUNDARY must PASS; got $v", v.isEmpty())
    }

    @Test
    fun atCliConsumerHostBoundary() {
        val nodes = RepoLoader.buildModuleGraph(root, listOf("at-cli-consumer-host"))
        val v = ArchitectureChecker.checkDirectDependencies(nodes)
        Assert.assertTrue(
            "AT_CLI_CONSUMER_HOST_BOUNDARY must PASS; got $v",
            v.isEmpty()
        )
    }

    @Test
    fun conformanceHostBoundary() {
        val nodes = RepoLoader.buildModuleGraph(root, listOf("at-consumer-conformance-host"))
        val v = ArchitectureChecker.checkDirectDependencies(nodes)
        Assert.assertTrue(
            "CONFORMANCE_HOST_BOUNDARY must PASS; got $v",
            v.isEmpty()
        )
    }
}
