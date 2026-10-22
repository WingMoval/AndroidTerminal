AT.V4-5 — Negative Enforcement Tests (Gate E)
Date: 2026-10-22

## Purpose
Prove the architecture checkers are NOT always-PASS scripts. Synthetic
violation fixtures are fed through the SAME checkers used on the real repo;
each MUST be detected (FAIL).

## Negative cases (all PASSED = detected)

### Case 1: consumer -> :adb-engine
```
fake-consumer direct deps = {at-engine-api, at-composition-android, adb-engine}
checkDirectDependencies -> ForbiddenDirectDependency(adb-engine) DETECTED
case1_consumerAdbEngineDepIsDetected = PASSED
```

### Case 2: consumer -> :at-engine
```
fake-consumer direct deps = {at-engine-api, at-engine}
checkDirectDependencies -> ForbiddenDirectDependency(at-engine) DETECTED
case2_consumerAtEngineDepIsDetected = PASSED
```

### Case 3: :at-engine-api public surface -> AdbRuntime
```
public lines = [interface BadEngine {, fun connect(runtime: AdbRuntime): Unit, }]
checkEngineApiSurface -> ApiSurfaceLeak(AdbRuntime) DETECTED
case3_engineApiAdbRuntimeLeakIsDetected = PASSED
```

### Case 4: Module A -> B -> A (cycle)
```
mod-a deps = {mod-b}; mod-b deps = {mod-a}
checkDagCycles -> DagCycle DETECTED
case4_moduleDagCycleIsDetected = PASSED
```

## Sanity (legal graph NOT flagged)

### Legal consumer graph is clean
```
legal-consumer deps = {at-engine-api, at-composition-android, at-cli}
checkDirectDependencies + checkDagCycles -> EMPTY (no false positive)
legalConsumerGraphIsClean = PASSED
```

### Transitive :adb-engine via composition is NOT a violation
```
legal-consumer deps = {at-engine-api, at-composition-android}
at-composition-android deps = {adb-engine}   (transitive, legal)
checkDirectDependencies -> EMPTY (consumer does NOT directly depend on adb-engine)
transitiveAdbEngineIsNotAConsumerViolation = PASSED
```

## Result
```
ARCHITECTURE_NEGATIVE_TESTS = PASS
(4 negative cases detected + 2 sanity cases clean)
```

Full test output persisted: 07_consumer_dependency_enforcement.txt
