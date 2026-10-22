// AT.V4-5 — Repository model loader (pure JVM, READ-ONLY).
//
// Reads the repository's OWN build.gradle.kts files and consumer production
// sources to build the [ModuleNode] graph + consumer source files for the
// architecture checkers. No modification; no Gradle invocation.

package com.at.architecture

import java.io.File

/**
 * Locate the repository root (the directory containing settings.gradle.kts).
 *
 * The architecture gate module is at `<root>/at-architecture-gate`. Walking up
 * from the module dir finds the root. Falls back to the working directory.
 */
object RepoLoader {

    /**
     * The module name -> directory name mapping (Gradle `include(":name")`
     * without an explicit path uses `<root>/<name>`).
     */
    private val MODULE_DIRS = mapOf(
        "adb-engine" to "adb-engine",
        "at-engine-api" to "at-engine-api",
        "at-engine-spi" to "at-engine-spi",
        "at-engine" to "at-engine",
        "at-backend-local" to "at-backend-local",
        "at-backend-adb" to "at-backend-adb",
        "at-composition-android" to "at-composition-android",
        "at-cli" to "at-cli",
        "app" to "app",
        "test-host" to "test-host",
        "adb-only-test-host" to "adb-only-test-host",
        "at-shellless-test-host" to "at-shellless-test-host",
        "at-cli-consumer-host" to "at-cli-consumer-host",
        "at-consumer-conformance-host" to "at-consumer-conformance-host",
        "at-architecture-gate" to "at-architecture-gate",
    )

    fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return File("").absoluteFile
    }

    /**
     * Parse a module's DIRECT project dependencies from its build.gradle.kts.
     *
     * Matches `implementation(project(":X"))`, `api(project(":X"))`,
     * `compileOnly(project(":X"))`, etc. Returns the dependency module names
     * (without the leading colon).
     */
    fun directProjectDeps(moduleDir: File): Set<String> {
        val buildFile = File(moduleDir, "build.gradle.kts")
        if (!buildFile.exists()) return emptySet()
        val text = buildFile.readText()
        val pattern = Regex("""(implementation|api|compileOnly|runtimeOnly|debugImplementation)\s*\(\s*project\s*\(\s*":([a-zA-Z0-9_-]+)""""")
        return pattern.findAll(text).map { it.groupValues[2] }.toSet()
    }

    /**
     * Build the module graph for the given module names.
     */
    fun buildModuleGraph(root: File, moduleNames: List<String>): Map<String, ModuleNode> =
        moduleNames.associateWith { name ->
            val dirName = MODULE_DIRS[name] ?: name
            val dir = File(root, dirName)
            ModuleNode(
                name = name,
                isConsumer = name in ConsumerBoundary.CONSUMERS,
                directProjectDeps = if (dir.exists()) directProjectDeps(dir) else emptySet(),
            )
        }

    /**
     * Read a consumer module's PRODUCTION source files (src/main/java/**/*.kt).
     *
     * Returns module -> list of (relativePath, content). Only PRODUCTION
     * source (src/main) — NOT androidTest / test (implementation-level tests
     * are NOT consumer violations).
     */
    fun consumerProductionSources(root: File, consumerModules: List<String>): Map<String, List<Pair<String, String>>> =
        consumerModules.associateWith { name ->
            val dirName = MODULE_DIRS[name] ?: name
            val srcMain = File(root, "$dirName/src/main/java")
            if (!srcMain.exists()) {
                emptyList()
            } else {
                srcMain.walkTopDown()
                    .filter { it.extension == "kt" }
                    .map { f ->
                        f.relativeTo(root).path to f.readText()
                    }
                    .toList()
            }
        }

    /**
     * Read the :at-engine-api public source lines (all src/main/java/**/*.kt).
     *
     * For the API surface leak gate, we inspect the public source of the
     * contract module. The checker filters to public signature lines and
     * checks for forbidden symbols.
     */
    fun engineApiPublicSourceLines(root: File): List<String> {
        val srcMain = File(root, "at-engine-api/src/main/java")
        if (!srcMain.exists()) return emptyList()
        return srcMain.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> f.readLines() }
            .toList()
    }
}
