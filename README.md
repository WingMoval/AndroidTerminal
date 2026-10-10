# AndroidTerminal-V3

**Canonical Production Mainline** for the AndroidTerminal / AT Engine platform.

> V2 = Verified Reference / Evidence Repository / Migration Source (READ-ONLY).
> V3 = New Production Mainline. **Verified migration, not rewrite.**

## Roles

| Repo | Role |
|---|---|
| `AndroidTerminal` | Legacy + SSOT source authority (READ-ONLY) |
| `AndroidTerminal-Public` | Known-good production source (READ-ONLY) |
| `AndroidTerminal-V2` | Verified reference implementation / evidence repo / migration source (READ-ONLY) |
| `AndroidTerminal-V3` | **Canonical production mainline (this repo)** |

## Canonical Architecture (V1)

The controlling architecture document is:
`docs/architecture/AndroidTerminal_Canonical_V1.md`

Summary:

```text
Consumer / Product
        │
        ▼
AT Engine Public Boundary
        │
        ▼
AT Engine
   ├─ One-shot Execution
   └─ Persistent Terminal (TerminalSession / Emulator / Screen State)
        │
        ▼
Backend Adapter
   ├─ Local Backend
   ├─ ADB Backend
   └─ Future SSH Backend
        │
        ▼
Execution Provider
   ├─ Local Process / PTY
   └─ ADB Engine → adbd
```

- **AT Engine** = reusable Terminal Execution Capability (one-shot + persistent), independent of UI / Agent / ADB protocol / specific shell.
- **ADB Engine** = independent ADB capability provider (pairing / identity / discovery / TLS / connection / protocol / streams / recovery).
- **AT Engine → ADB Backend → ADB Engine** is a *consumer → provider* relationship, NOT containment.
- **Terminal Emulator + Screen State** belong to AT Engine. **Android Renderer** belongs to the Product / Human UI.

## Development Discipline

- Do NOT reimplement already-verified V2 primitives (pairing / TLS / connection / shell-v2 / interactive stream / Local PTY / resize / Ctrl-C / cleanup).
- Migrate by responsibility, not by copying old modules wholesale.
- Architecture frozen first; final Kotlin/C++/Gradle API frozen only after real vertical slices.
- No unauthorized auto-start of the next phase. STOP after each task.

## Track Map

- **AT.V3-0** — Canonical Bootstrap (this repo baseline).
- **AT.V3-1** — ADB Engine Extraction / Migration Baseline.
- **Track A** — ADB Engine evolution.
- **Track B** — AT Engine (one-shot → persistent → emulator → contract freeze → Human UI / AG TerminalTool).
- **Track C** — AndroidAgent (AG Engine / Tool Set / TerminalTool as a Tool branch).

See `docs/migration/Migration_Manifest_V2_to_V3.md` and `docs/migration/V2_Responsibility_Inventory.md`.