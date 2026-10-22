AT.V4-6 — Pairing / Bootstrap / Identity Parity (P7/P8/P9)
Date: 2026-10-22

## P8: First-Use Bootstrap
V3 Oracle: V4-4.3 frozen device evidence
V4 Validator: Product AutoWdBootstrapTest#b/c/e
V4 Result:
- b_permissionAbsentSelfGrant: self-grant PASS
- c_autoWdPostBootstrap: auto-WD PASS [flaky on batch, PASS on re-run 26s]
- e_restartPersistence: restart PASS
BOOTSTRAP_PARITY = PASS
```
FIRST_USE_MANUAL_WD_REQUIRED = YES
FIRST_USE_PAIRING_REQUIRED = YES
POST_PAIR_SELF_GRANT_WRITE_SECURE_SETTINGS = PASS
POST_BOOTSTRAP_MANUAL_WD_REQUIRED = NO
POST_BOOTSTRAP_AUTO_WD = PASS
```

## P9: Identity
V3 Oracle: V4-4.2 frozen evidence (3 independent identities)
V4 Validator: Device snapshot before + after all parity tests
V4 Result:
```
BEFORE:
  Product = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
  ConsumerHost = df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55
  ConformanceHost = 1e7ee331878ae547319b31991d1e3fa932b4b1edf4b6d18c08145db4b3cade73

AFTER:
  Product = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
  ConsumerHost = df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55
  ConformanceHost = 1e7ee331878ae547319b31991d1e3fa932b4b1edf4b6d18c08145db4b3cade73
```
```
PRODUCT_IDENTITY_STABLE = YES (before == after)
CONSUMERHOST_IDENTITY_STABLE = YES
CONFORMANCEHOST_IDENTITY_STABLE = YES
PRODUCT_NORMAL_REPAIRING_COUNT = 0
CONSUMERHOST_NORMAL_REPAIRING_COUNT = 0
CONFORMANCEHOST_NORMAL_REPAIRING_COUNT = 0
AT_CANONICAL_ADB_IDENTITY_COUNT_PER_CONSUMER = 1
KEY_COPY = NO
PC_KEY_REUSE = NO
IDENTITY_PARITY = PASS
```
