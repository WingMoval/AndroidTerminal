AT.V4-5 — DAG Cycle Enforcement (Gate D)
Date: 2026-10-22

## Gate: verifyModuleDag
Checks the module dependency graph for cycles (DFS with in-stack detection).

## Modules covered
```
at-engine-api / at-engine-spi / at-engine / at-backend-local / at-backend-adb /
adb-engine / at-composition-android / at-cli / app / at-cli-consumer-host /
at-consumer-conformance-host
```

## Implementation
`ArchitectureChecker.checkDagCycles(nodes)`:
- Builds adjacency from each module's DIRECT project deps (only known modules).
- DFS with visited + in-stack sets; a back-edge to an in-stack node = cycle.
- Reports the cycle path for human-readable output.

## Result (real repo)
```
MODULE_DAG_CYCLE_COUNT = 0
moduleDagCycleCount = PASSED
```

The Gradle project dependency graph is acyclic. The gate provides a
human-readable architecture verification result (not just relying on Gradle's
own cycle prevention).
