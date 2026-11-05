# AT v0.4.3 Consumer Freeze Bundle V1

## Release Identity

| Field | Value |
|-------|-------|
| RELEASE | v0.4.3 |
| SOURCE_COMMIT | 5517fc0 |
| TAG_TARGET | v0.4.3 (pending) |
| BASE_RELEASE | v0.4.2 |
| FIX_COMMIT | 4ebabe5 |
| BUILD_VARIANT | release |
| versionName | 0.4.3 |
| versionCode | 5 |

## GAV Convention

GAV version 4.0.0 = stable API artifact version, independent of app release.
App release v0.4.3 uses artifact version 4.0.0 (unchanged from v0.4.2).
Bundle identity is by directory name + SHA256 manifest, NOT GAV bump.

## Artifacts

| Artifact | GAV | Size (bytes) | SHA256 |
|----------|-----|-------------|--------|
| adb-engine-4.0.0.aar | com\adb\engine\adb-engine\4.0.0\adb-engine-4.0.0.aar | 1192391 | 1787C505CE56BB2AFF9F0E745973DFE3FF0BE552BA99EFB3C7D0783E54AC6645 |
| at-backend-adb-4.0.0.aar | com\at\backend\adb\at-backend-adb\4.0.0\at-backend-adb-4.0.0.aar | 8933 | E50BC44BEE80668240DB1A504887E4A579D6480A6A1E3A38CA98633C5BA6846D |
| at-backend-local-4.0.0.aar | com\at\backend\local\at-backend-local\4.0.0\at-backend-local-4.0.0.aar | 117556 | FCF7C0C4A0EA5089F303827D26952AA5F88BE97CFAB1F230708820F1B4771A7E |
| at-cli-4.0.0.aar | com\at\cli\at-cli\4.0.0\at-cli-4.0.0.aar | 174038 | C19AB9E4FDFB7400A6587B83F26143A242DDCE5A73D114AB52391E8D2AF82D92 |
| at-composition-android-4.0.0.aar | com\at\composition\android\at-composition-android\4.0.0\at-composition-android-4.0.0.aar | 30224 | 11DED571BDE5415BEF56E0CFD49F61FB3871002E8DEF96F616D610083E22922D |
| at-engine-api-4.0.0.jar | artifacts/at-engine-api-4.0.0.jar | 23079 | 9E3F26D15F2BD2D152739A7D80B8161E2E37D5D1041E006C21CD547AE50D0EB7 |

## Release APK

| Field | Value |
|-------|-------|
| APK | app-release.apk |
| Size | 4465528 bytes |
| SHA256 | ECCD59F7BA6CE8C00E87C820384E14B238B24C1CD9F09BDFA130EACF595C1CFC |
| Signer | 7bdcba7781e6fecfe078eb9741e7bfeb380f0a7aa64530007991250b07835cde |
| ABI | arm64-v8a |

## Native

Native source: UNCHANGED from v0.4.2 (no .so in source tree; built by CMake at build time)

## Compatibility

| Check | Result |
|-------|--------|
| PUBLIC_API_DIFF | NONE |
| ROUTING_FIX | AdbRuntime.executeShell (canonical) |
| AG_COMPAT_VALIDATION | PASS_LOCAL_WITH_WIRELESS_LIMITATION (cd5d190) |
| WIRELESS_EVIDENCE | CARRIED_FORWARD_FROM_V042_UNCHANGED_PATH |
| TRUE_LOCAL_LOSS | DEVICE VERIFIED (adb usb) |

## Provenance

- Built from exact final release commit (clean worktree)
- No artifact copied from old candidate without rebuild
- All SHA256 computed post-build

