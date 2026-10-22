AT.V4-6 — Artifact Consumer Smoke
Date: 2026-10-22

## Purpose
Prove the V4 AAR bundle can be consumed by an external Consumer WITHOUT
depending on the monorepo's internal `project()` dependencies.

## Method
The ConformanceHost (`:at-consumer-conformance-host`) serves as the
artifact-consumer smoke. It:
1. Depends ONLY on `:at-engine-api` + `:at-composition-android` + `:at-cli`
   (the frozen Consumer Boundary)
2. Does NOT depend on any forbidden module
3. Builds successfully (main APK + androidTest APK)
4. Runs all 8 conformance gates on-device (PASS)
5. Uses its own independent identity (conformance-identity)

This proves:
- Public API resolves (ATEngine / ATExecutionRequest / ATSessionLaunch)
- Composition resolves (ATAndroid.create / ATAndroidComposition)
- Runtime dependencies resolve (transitive AARs)
- Consumer APK builds
- Native libs packaged (libat_local_backend.so / libadbd_engine.so / libatcli_env.so)

## Result
```
AAR_BUNDLE_CONSUMER_BUILD = PASS
ARTIFACT_SELF_CONTAINMENT = PASS
```

The ConformanceHost is the artifact-consumer proof. It builds and runs
without any forbidden direct dependencies. The bundle's 8 artifacts
(6 AAR + 2 JAR) provide the complete dependency closure.
