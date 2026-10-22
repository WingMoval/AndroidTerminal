AT.V4-5 — API Surface Enforcement (Gate C)
Date: 2026-10-22

## Gate: verifyAtEngineApiSurface
Checks that :at-engine-api public surface does NOT leak:
- android.content.Context
- AdbRuntime / AdbEngine / AdbConsumerBootstrap
- backend concrete classes (Local/Adb OneShot/Persistent)
- SPI/internal implementation types
- identityDir-oriented implementation contract

## Implementation
`ArchitectureChecker.checkEngineApiSurface(sourceLines)`:
- Filters to REAL public signature lines (declaration keywords: fun/val/var/
  class/interface/object/data class/sealed class/enum class/typealias).
- EXCLUDES comment lines (`//`, `*`, `/*`) — a comment mentioning "Context" is
  NOT an API leak (avoids the "NO Android Context" false positive).
- Matches forbidden symbols with word-boundary regex.

## Result (real repo)
```
AT_ENGINE_API_PUBLIC_LEAK_COUNT = 0
atEngineApiPublicLeakCount = PASSED
```

## Verification
:at-engine-api public surface is PURE CONSUMER CONTRACT:
- ATEngine (interface): execute / openSession
- ATExecutionRequest / ATExecutionResult / ATExecutionOutcome
- ATSessionLaunch / ATTerminalSession / ATSessionEvent / ATSessionState /
  ATOpenSessionResult
- NO Context / AdbRuntime / AdbEngine / backend / SPI / identityDir in signatures.
