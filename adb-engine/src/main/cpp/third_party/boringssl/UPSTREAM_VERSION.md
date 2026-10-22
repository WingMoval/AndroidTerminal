# BoringSSL Upstream Version

- **Commit:** df821137838169360f82f94fd39cebe803c7c2d6
- **Date:** 2026-10-22
- **Branch:** main (HEAD)
- **Source:** https://github.com/google/boringssl
- **License:** Apache-2.0 (see LICENSE)
- **Built with:** NDK 26.1.10909125, Clang 17.0.2, CMake 3.22.1
- **Target:** arm64-v8a, android-28, Release
- **Build date:** 2026-10-22

## Library Hashes

| File | SHA-256 |
|------|---------|
| libcrypto.a | 4CF2E4FA34D6A6CF33747AE295E658B45A4EEA82179E77ACF50A41737FBF4493 |
| libssl.a | 8E684C63899E1310D1EE4318525005732A0D2306274206469D42462040179F03 |
| LICENSE | 86F839D5FD287D9B155EF222BA39CCDA32E5515B4573A9A1F35B5DB03FF7E0A3 |

## Reproducibility

To rebuild:
1. Clone https://github.com/google/boringssl at commit df821137838169360f82f94fd39cebe803c7c2d6
2. cmake -S . -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
3. cmake --build build --target crypto ssl --parallel 8
4. Copy libcrypto.a and libssl.a to lib/arm64-v8a/
