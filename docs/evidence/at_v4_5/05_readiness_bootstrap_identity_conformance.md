AT.V4-5 — Readiness / Bootstrap / Identity Conformance (Gates E/F/G)
Date: 2026-10-22

## Gate E: Readiness
```
e_readiness = PASS
Consumer observes ADB readiness via the public semantic facade:
  comp.adbControl.readinessState() -> ATAdbReadinessState.READY
Consumer does NOT import AdbRuntime; does NOT need identityDir.
READINESS_CONFORMANCE = PASS
```

## Gate F: Bootstrap
```
f_bootstrap = PASS
Consumer observes bootstrap state via the public facade:
  comp.adbControl.bootstrapState() -> ATAdbBootstrapState.READY
First-use bootstrap completion accessible via public facade:
  comp.adbControl.completeFirstUseBootstrap() -> FirstUseBootstrapResult
(idempotent; SKIPPED_ALREADY_GRANTED when already granted)
Consumer does NOT import AdbConsumerBootstrap.
BOOTSTRAP_CONFORMANCE = PASS
```

## Gate G: Identity
```
g_identity = PASS
ONE consumer -> ONE canonical identity (self-generated):
  comp.adbControl.hasIdentity() -> true
  ConformanceHost.identityDir() -> files/conformance-identity (exists)
  adbkey.pub present (self-generated)
Identity is INDEPENDENT: profile "conformance-identity", fingerprint
1e7ee331... (NOT Product a026370d..., NOT ConsumerHost df365556..., NOT PC key).
No Product/ConsumerHost/PC key copied.
Consumer does NOT directly manage identityDir/key file/AdbKeyStore as a provider
operation — it observes via the public facade + reads its own private dir to
verify independence (test-only verification, not a provider API).
IDENTITY_CONFORMANCE = PASS
```

## V4-4.3 Auto-WD conformance (Gate F includes)
The reference consumer accesses the canonical public bootstrap path
(completeFirstUseBootstrap) — NOT AdbRuntime.selfGrantWriteSecureSettings()
directly. No CONSUMER_CONTRACT_GAP.
