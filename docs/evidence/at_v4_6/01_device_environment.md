AT.V4-6 — Device Environment Snapshot
Date: 2026-10-22

## Device
DEVICE_MODEL = ALI-AN00
ANDROID_VERSION = 15
SDK = 35
ABI = arm64-v8a
SELINUX = enforcing

## Installed packages
com.androidterminal.app = package:com.androidterminal.conformance.test package:com.androidterminal.app package:com.androidterminal.atsh package:com.androidterminal.testhost.test package:com.androidterminal.app.test package:com.androidterminal.atclihost package:com.androidterminal.atclihost.test package:com.androidterminal.testhost package:com.androidterminal package:com.androidterminal.conformance package:com.androidterminal.adbonlyhost

## Identity fingerprints (BEFORE parity)
Product (files/adb_identity/adbkey.pub sha256):
  a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd  -
ConsumerHost (files/at-cli-identity/adbkey.pub sha256):
  df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55  -
ConformanceHost (files/conformance-identity/adbkey.pub sha256):
  1e7ee331878ae547319b31991d1e3fa932b4b1edf4b6d18c08145db4b3cade73  -

## WRITE_SECURE_SETTINGS (Product)
    permission:android.permission.WRITE_SECURE_SETTINGS   permission:com.hihonor.uikit.phone.hwimageview.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION   permission:android.permission.WRITE_SETTINGS_HOMEPAGE_DATA
  granted=android.permission.WRITE_SECURE_SETTINGS: granted=true

## Wireless Debugging state
  adb_wifi_enabled = 0

## INDEPENDENT_IDENTITIES
  Product = a026370d... (V4_4_2_FINAL_CLOSURE_IDENTITY)
  ConsumerHost = df365556...
  ConformanceHost = 1e7ee331...
  INDEPENDENT_IDENTITIES = YES (3 distinct fingerprints)
  PRODUCT_IDENTITY_STABLE = YES (matches V4-4.2 final closure)
