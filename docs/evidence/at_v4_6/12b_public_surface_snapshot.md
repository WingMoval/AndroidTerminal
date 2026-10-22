AT.V4-6 — Public Surface Snapshot
Date: 2026-10-22
Freeze Tag: AT.V4-CONSUMER-FREEZE-V1

This snapshot records the Consumer-visible public surface of the V4
modules at the freeze point. Deterministic source/public-signature manifest.

## :at-engine-api (PURE CONSUMER CONTRACT)
- ATEngine interface: execute(ATExecutionRequest): ATExecutionResult / openSession(ATSessionLaunch): ATOpenSessionResult
- ATExecutionOutcome: COMPLETED / FAILED / TIMEOUT / UNSUPPORTED
- ATExecutionRequest(command, timeoutMs) / ATExecutionResult(outcome, stdout, stderr, exitCode, tookMs, hasError, error)
- ATSessionState: OPENING / ACTIVE / EXITED / FAILED / CLOSED
- ATSessionLaunch(command, initialRows, initialCols, env)
- ATOpenSessionResult: Success(session) / Failure(error)
- ATTerminalSession: write / resize / close / setListener / state
- ATSessionEvent: Data / StateChanged / Eof / Closed

## :at-composition-android (Android Assembly Root)
- ATAndroid.create(context, config): ATAndroidComposition
- ATAndroidConfig(identityProfile)
- ATAndroidComposition: localEngine / adbEngine / adbControl
- ATAndroidAdbControl: readinessState / bootstrapState / hasIdentity / discoverPairing / pair / pairDiscovered / ensureReady / completeFirstUseBootstrap
- ATAdbReadinessState: READY / CONNECTING / DISCONNECTED / RECOVERING
- ATAdbBootstrapState: READY / PAIRING_REQUIRED / CONNECT_FAILED / NOT_READY
- ATAdbPairingDiscovery(endpoints)
- ATAdbPairingResult(success, outcome, deviceGuid, error)
- FirstUseBootstrapResult(writeSecureSettingsGranted, alreadyGranted, error)

## :at-cli (Optional Frontend)
- ATCliExecution: adbEngine / ensureReady / pairingState / pair / pairDiscovered
- AdbCliBridgeServer(filesDir, execution): start / stop / socketPath
- LocalCliBootstrap(mainClass): writeScript / sourceLine
- ATCliPairingState: READY / PAIRING_REQUIRED / CONNECT_FAILED / NOT_READY
- ATCliPairingResult(success, outcome, deviceGuid, error)

## Surface Leak Check
AT_ENGINE_API_PUBLIC_LEAK_COUNT = 0
No Context / AdbRuntime / AdbEngine / backend / SPI / identityDir in public signatures

PUBLIC_SURFACE_SNAPSHOT = PRESENT
