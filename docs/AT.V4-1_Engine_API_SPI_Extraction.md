# AT.V4-1 — Engine API + Backend SPI Extraction

## Overview

V4-1 completes the physical extraction of AT Engine public API and backend SPI contracts
from `:at-engine` into two new modules:

- `:at-engine-api` — Public consumer contract (Layer-2)
- `:at-engine-spi` — Backend SPI implementation contract (Layer-3/4)

**SEMANTIC_DELTA = NONE** (types are moved, not redesigned)

## Changes

### 1. New Modules

#### :at-engine-api (Layer-2 Public API)

Contains ONLY the verified execution/session semantic types:

- `ATExecutionOutcome` (enum)
- `ATExecutionResult` (data class)
- `ATExecutionRequest` (data class)
- `ATSessionState` (enum)
- `ATSessionEvent` (sealed class)
- `ATTerminalSession` (interface)
- `ATOpenSessionResult` (sealed class)
- `ATSessionLaunch` (data class, extracted from `ATPersistentBackend.Launch`)
- `ATEngine` (interface, public contract)

**Dependencies**: NONE (pure Kotlin/JVM)

#### :at-engine-spi (Layer-3/4 Backend SPI)

Contains ONLY the backend SPI interfaces:

- `ATOneShotBackend` (interface)
- `ATPersistentBackend` (interface, now references `ATSessionLaunch`)

**Dependencies**: `:at-engine-api`

### 2. Engine Implementation (:at-engine)

- Added `com.at.engine.impl.DefaultATEngine` (concrete implementation of `ATEngine` interface)
- Removed old concrete `ATEngine` class
- Now depends on `:at-engine-api` + `:at-engine-spi`

### 3. Backend Modules

- `:at-backend-local` — Now depends on `:at-engine-api` + `:at-engine-spi` (NOT `:at-engine`)
- `:at-backend-adb` — Now depends on `:at-engine-api` + `:at-engine-spi` + `:adb-engine`

### 4. Consumer Modules (TRANSITIONAL_DIRECT_DEPENDENCIES)

These modules temporarily retain direct dependencies on internal modules
(will be removed in AT.V4-4 when `:at-composition-android` is created):

- `:app`
- `:at-cli`
- `:at-cli-consumer-host`
- `:adb-only-test-host`
- `:at-shellless-test-host`
- `:test-host`

### 5. Type Extraction

**OLD**: `ATPersistentBackend.Launch` (nested data class in SPI)
**NEW**: `ATSessionLaunch` (top-level data class in API)

**SEMANTIC_DELTA = NONE** (same fields, same defaults, same semantics)

This extraction eliminates the SPI leak in `ATEngine.openSession()`:

```kotlin
// OLD (SPI leak):
fun openSession(launch: ATPersistentBackend.Launch): ATOpenSessionResult

// NEW (clean API):
fun openSession(launch: ATSessionLaunch): ATOpenSessionResult
```

### 6. ATEngine Split

**OLD**: Concrete class `ATEngine` in `:at-engine`
**NEW**: 
- Interface `ATEngine` in `:at-engine-api` (public contract)
- Class `DefaultATEngine` in `:at-engine` (implementation)

**SEMANTIC_DELTA = NONE** (same public surface: execute + openSession)

## Build Verification

```text
assembleDebug: BUILD SUCCESSFUL
test: BUILD SUCCESSFUL
```

## Deliverables

- New module: `:at-engine-api` (3 files)
- New module: `:at-engine-spi` (1 file)
- Updated: `:at-engine` (added DefaultATEngine, removed old types)
- Updated: 12 consumer files (imports + ATEngine → DefaultATEngine)
- Updated: 10 build.gradle.kts files (dependency updates)
- Updated: `settings.gradle.kts` (new module includes)

## Next Steps

- AT.V4-2: Create `:at-composition-android` (composition root)
- AT.V4-4: Remove TRANSITIONAL_DIRECT_DEPENDENCIES from consumers

## Status

**COMPLETED** — Ready for commit
