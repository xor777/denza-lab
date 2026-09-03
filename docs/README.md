# Docs Index

Use this folder for durable project knowledge.

| File | Use for |
| --- | --- |
| `project-map.md` | Repo structure, app boundaries, build outputs, product direction. |
| `governance.md` | Rules for product/prototype/research changes and promotion. |
| `adb-authorization-recovery.md` | Denza Apps local-ADB startup gate, one-shot authorization flow, stuck-queue boundary, and acceptance status. |
| `instrument-display-findings.md` | Instrument-display selection, Mirrors geometry, navigation projection, verification status, and open issues. |
| `audio-capture-findings.md` | Verified output-mix spectrum source, calibration, permissions, product adoption, and remaining audio checks. |
| `speaker-lift-findings.md` | Devialet flip covers; live-proven direct motor edges, the stock-auto latch side effect, Denza Apps app/MediaSession/output-mix automation, and superseded trigger hypotheses. |
| `vehicle-data-findings.md` | Live-car matrix of usable GNSS/IMU/journey data, blocked DiCar getters, `autoservice` FID protocol, widget allowlist, and product boundaries. |
| `stock-russian-locale.md` | Captured BYD Settings locale behavior, stock Russian resources, and the narrow verified Denza Apps toggle. |
| `dishare-api-notes.md` | DiShare/HUD reverse-engineering notes and raw API findings. |
| `fse-app-installation.md` | Passenger-screen Android discovery, SMB delivery, stock cross-device install trigger, verification, and limitations. |
| `split-screen-findings.md` | Live-proven BYD split substrate, explicit one-package picker flow, acceptance evidence, and retired approaches. |
| `split-screen-product-contract.md` | Normative Split Screen contract: user-visible combinatorics, invariants, single-automaton core, delete-first policy, test-audit verdict, live acceptance protocol. Owns the product contract where it diverges from findings. |
| `weather-adapter-findings.md` | Stock BYD weather-provider contract, MET Norway adapter, cache/write behavior, and live proof. |
| `shortcuts-automation-findings.md` | Shortcuts If/Then catalog, PersonBean navigation/music/video roles, live Yandex Navigator/Music and VK Video checks, the built-in single-APK navigation proxy, and the firmware-specific actions that honor each role. |
| `carplay-findings.md` | Vehicle hardware/software evidence around CarPlay, PhoneLink/Fission boundaries, and unsupported hypotheses. |
| `car-adb-gateway-architecture.md` | Normative relay-only Car ADB Gateway design and verification status. |
| `car-adb-gateway-decision-log.md` | ADR-lite product/architecture decisions, rationale, evidence, and revisit conditions. |

If an investigation produces something worth keeping, update the nearest page.
Add a new document only when the subject has a clear long-term home and would
make an existing page unwieldy. For current behavior, check the code, manifests,
and build files; these pages explain the layout and preserve field evidence.
