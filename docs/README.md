# Docs Index

Use this folder for durable project knowledge.

| File | Use for |
| --- | --- |
| `project-map.md` | Repo structure, app boundaries, build outputs, product direction. |
| `governance.md` | Rules for product/prototype/research changes and promotion. |
| `adb-authorization-recovery.md` | Denza Apps local-ADB startup gate, one-shot authorization flow, stuck-queue boundary, and acceptance status. |
| `instrument-display-findings.md` | Instrument-display selection, Mirrors geometry, navigation projection, verification status, and open issues. |
| `audio-capture-findings.md` | Verified output-mix spectrum source, calibration, permissions, product adoption, and remaining audio checks. |
| `vehicle-data-findings.md` | Live-car matrix of usable GNSS/IMU/journey data, blocked BYD/CAN getters, frequencies, probes, and product boundaries. |
| `dishare-api-notes.md` | DiShare/HUD reverse-engineering notes and raw API findings. |
| `fse-app-installation.md` | Passenger-screen Android discovery, SMB delivery, stock cross-device install trigger, verification, and limitations. |
| `split-screen-findings.md` | Live-proven BYD split substrate, explicit one-package picker flow, acceptance evidence, and retired approaches. |
| `weather-adapter-findings.md` | Stock BYD weather-provider contract, MET Norway adapter, cache/write behavior, and live proof. |
| `shortcuts-automation-findings.md` | Stock Shortcuts limits, map-role switches, reversible shell probe, and safety boundary. |
| `carplay-findings.md` | Vehicle hardware/software evidence around CarPlay, PhoneLink/Fission boundaries, and unsupported hypotheses. |
| `CLOUD-ARCHITECTURE.md` | Normative relay-only Car ADB Gateway design and verification status. |
| `CAR-ADB-GATEWAY-DECISIONS.md` | ADR-lite product/architecture decisions, rationale, evidence, and revisit conditions. |

If an investigation produces something worth keeping, update the nearest page.
Add a new document only when the subject has a clear long-term home and would
make an existing page unwieldy. For current behavior, check the code, manifests,
and build files; these pages explain the layout and preserve field evidence.
