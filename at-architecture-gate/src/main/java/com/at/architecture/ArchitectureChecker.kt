// AT.V4-5 — Architecture gate core (pure JVM).
//
// Encapsulates the frozen V4 Consumer Boundary invariants and the checkers
// that verify them against the repository's OWN build files and sources
// (READ-ONLY). The production gate reads the real repo; the negative tests
// feed synthetic fixtures through the SAME checkers to prove they really
// fail on violations (not always-PASS scripts).

package com.at.architecture

/**
 * A directed module-dependency edge (consumer -> dependency).
 */
data class DepEdge(val from: String, val to: String)

/**
 * A module node in the dependency graph.
 *
 * @param name module name (e.g. "app", "at-composition-android")
 * @param isConsumer true when the module is a Consumer subject to the boundary
 *        (app / at-cli-consumer-host / at-consumer-conformance-host)
 * @param directProjectDeps the module's DIRECT project dependencies
 */
data class ModuleNode(
    val name: String,
    val isConsumer: Boolean,
    val directProjectDeps: Set<String>,
)

/**
 * The frozen V4 Consumer Boundary.
 *
 * Allowed direct consumer dependencies.
 */
object ConsumerBoundary {
    /** Consumers subject to the direct-dependency gate. */
    val CONSUMERS = setOf(
        "app",
        "at-cli-consumer-host",
        "at-consumer-conformance-host",
    )

    /** ALLOWED direct project dependencies for any consumer. */
    val ALLOWED_DIRECT = setOf(
        "at-engine-api",
        "at-composition-android",
        "at-cli",
    )

    /** FORBIDDEN direct project dependencies for any consumer. */
    val FORBIDDEN_DIRECT = setOf(
        "at-engine",
        "at-engine-spi",
        "at-backend-local",
        "at-backend-adb",
        "adb-engine",
    )
}

/**
 * Architecture violation classification.
 */
sealed class Violation {
    abstract val module: String
    abstract val description: String

    data class ForbiddenDirectDependency(
        override val module: String,
        val forbiddenDep: String,
    ) : Violation() {
        override val description: String
            get() = "consumer '$module' directly depends on forbidden module '$forbiddenDep'"
    }

    data class ForbiddenConsumerImport(
        override val module: String,
        val file: String,
        val importLine: String,
    ) : Violation() {
        override val description: String
            get() = "consumer '$module' source '$file' imports forbidden type: $importLine"
    }

    data class ApiSurfaceLeak(
        override val module: String,
        val symbol: String,
    ) : Violation() {
        override val description: String
            get() = "':at-engine-api' public surface leaks forbidden symbol: $symbol"
    }

    data class DagCycle(
        override val module: String,
        val cycle: List<String>,
    ) : Violation() {
        override val description: String
            get() = "module dependency cycle: ${cycle.joinToString(" -> ")}"
    }
}

/**
 * Result of an architecture gate run.
 */
data class GateResult(
    val violations: List<Violation>,
) {
    val passed: Boolean get() = violations.isEmpty()
}

/**
 * Architecture checker (pure functions over [ModuleNode] graph + source text).
 */
object ArchitectureChecker {

    /**
     * Gate A — direct consumer dependency.
     *
     * Checks ONLY DIRECT project dependencies (not transitive). A consumer may
     * legally have `:adb-engine` on its runtime classpath via
     * `:at-composition-android -> implementation(:adb-engine)`; that is NOT a
     * violation. Only a consumer's OWN `implementation(project(":adb-engine"))`
     * is.
     */
    fun checkDirectDependencies(nodes: Map<String, ModuleNode>): List<Violation> =
        nodes.values
            .filter { it.isConsumer }
            .flatMap { node ->
                node.directProjectDeps
                    .filter { dep -> dep in ConsumerBoundary.FORBIDDEN_DIRECT }
                    .map { dep -> Violation.ForbiddenDirectDependency(node.name, dep) }
            }

    /**
     * Gate B — consumer source import boundary.
     *
     * [consumerSourceFiles] maps module -> list of (relativeFile, content).
     * Only CONSUMER PRODUCTION source is checked (NOT implementation-module
     * tests, NOT the architecture gate's own fixtures).
     */
    fun checkConsumerImports(
        consumerSourceFiles: Map<String, List<Pair<String, String>>>,
    ): List<Violation> {
        // Forbidden import prefixes (implementation / provider / backend / SPI).
        val forbiddenPrefixes = listOf(
            "com.adb.engine.",          // AdbRuntime / AdbEngine / AdbConsumerBootstrap / backends
            "com.at.engine.impl.",      // DefaultATEngine (implementation)
            "com.at.engine.spi.",       // backend SPI (implementation contract)
            "com.at.backend.local.",    // LocalOneShotBackend / LocalPersistentBackend
            "com.at.backend.adb.",      // AdbOneShotBackend / AdbPersistentBackend
        )
        return consumerSourceFiles.entries.flatMap { (module, files) ->
            files.flatMap { (file, content) ->
                content.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("import ") }
                    .filter { line ->
                        forbiddenPrefixes.any { line.contains("import " + it) || line.contains(it) }
                    }
                    .map { line -> Violation.ForbiddenConsumerImport(module, file, line) }
                    .toList()
            }
        }
    }

    /**
     * Gate C — :at-engine-api public surface leak.
     *
     * [apiSourceLines] are ALL source lines of :at-engine-api. The checker
     * filters to REAL public API signature lines (excluding comments) and
     * checks that none reference forbidden provider / backend / SPI / Context
     * types.
     *
     * A line is a "public signature line" when (after trimming) it starts with
     * a declaration keyword (`fun`, `val`, `var`, `class`, `interface`,
     * `object`, `data class`, `sealed class`, `enum class`, `typealias`) or is
     * a parameter/return-type fragment of such a declaration. COMMENT lines
     * (`//` or `*`) are EXCLUDED — a comment mentioning `Context` is NOT an
     * API leak.
     */
    fun checkEngineApiSurface(apiSourceLines: List<String>): List<Violation> {
        val forbiddenSymbols = listOf(
            "android.content.Context",
            "AdbRuntime",
            "AdbEngine",
            "AdbConsumerBootstrap",
            "AdbConnectionRequest",
            "AdbEndpoint",
            "AdbInteractiveStream",
            "LocalPtySession",
            "AdbOneShotBackend",
            "AdbPersistentBackend",
            "LocalPersistentBackend",
            "LocalOneShotBackend",
            "DefaultATEngine",
            "identityDir",
            // Bare "Context" is only a leak when it appears as a TYPE in a
            // signature (parameter/return), not as a comment word. We match
            // "Context" preceded by a type-context character to avoid the
            // comment "NO Android Context" false positive.
        )
        val declarationKeywords = listOf(
            "fun ", "val ", "var ", "class ", "interface ", "object ",
            "data class ", "sealed class ", "enum class ", "typealias ",
        )
        val violations = mutableListOf<Violation>()
        for (raw in apiSourceLines) {
            val line = raw.trim()
            // Skip comments and blank lines.
            if (line.isEmpty()) continue
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue
            // Only inspect declaration/signature lines.
            val isSignature = declarationKeywords.any { line.startsWith(it) } ||
                line.startsWith(")") || line.startsWith("override fun")
            if (!isSignature) continue
            // Check forbidden symbols as whole tokens (word-boundary).
            for (symbol in forbiddenSymbols) {
                // "Context" handled specially: require it to be a standalone
                // type token (not the comment word), e.g. ": Context" or
                // "context: Context" or "Context" as a type.
                if (symbol == "android.content.Context") {
                    if (line.contains("android.content.Context")) {
                        violations.add(Violation.ApiSurfaceLeak("at-engine-api", symbol))
                    }
                    continue
                }
                val regex = Regex("\\b${Regex.escape(symbol)}\\b")
                if (regex.containsMatchIn(line)) {
                    violations.add(Violation.ApiSurfaceLeak("at-engine-api", symbol))
                }
            }
        }
        return violations.distinct()
    }

    /**
     * Gate D — module dependency DAG cycle detection (DFS).
     *
     * [nodes] is the full module graph (consumer + internal). A cycle in the
     * DIRECT dependency edges is a violation.
     */
    fun checkDagCycles(nodes: Map<String, ModuleNode>): List<Violation> {
        val adjacency = nodes.mapValues { (_, n) ->
            n.directProjectDeps.filter { it in nodes } // only known modules
        }
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()
        val violations = mutableListOf<Violation>()
        val stack = mutableListOf<String>()

        fun dfs(node: String) {
            visited.add(node)
            inStack.add(node)
            stack.add(node)
            for (next in adjacency[node].orEmpty()) {
                if (next !in visited) {
                    dfs(next)
                } else if (next in inStack) {
                    // Cycle found: extract from the stack.
                    val cycleStart = stack.indexOf(next)
                    val cycle = stack.subList(cycleStart, stack.size).toList() + next
                    violations.add(Violation.DagCycle(next, cycle))
                }
            }
            stack.removeAt(stack.lastIndex)
            inStack.remove(node)
        }

        for (node in nodes.keys.sorted()) {
            if (node !in visited) dfs(node)
        }
        return violations.distinct()
    }
}
