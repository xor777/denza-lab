# Docs Index

Use this folder for durable project knowledge.

Every findings page opens with a **Current state** table (claim, status, section)
and a contents list; read that before the dated sections below it. How a page is
laid out and how an overturned finding is marked: `governance.md`, "Findings
Documents".

| File | Use for |
| --- | --- |
| `project-map.md` | Repo structure, app boundaries, build outputs, product direction. |
| `feature-map.md` | Each dashboard tile and each surface that is not a tile, as the driver names it, mapped to its code, panel, runtime, settings, docs, Luminofor fixtures and tests; and the checklist for adding a tile. Held to the code by `FeatureMapContractTest`. |
| `governance.md` | Rules for product/prototype/research changes and promotion. |
| `adb-authorization-recovery.md` | Denza Apps local-ADB startup gate, one-shot authorization flow, stuck-queue boundary, and acceptance status. |
| `instrument-display-findings.md` | The driver's display: the instrument panel (Contour, drawn as Luminofor since 2026-09-23), Mirrors following the stock turn-signal camera, navigation projection, HUD turn-by-turn guidance, and open issues. |
| `energy-display-contract.md` | Normative energy contract for the cluster's Contour and the head unit's car page: pack power's direction and words, the ten kilometres of recorded road behind the consumption figure, the hundred-point chart, the trip and the engine's box, and how each is proved. Owns those where it diverges from the findings or the canvas README. |
| `audio-capture-findings.md` | Verified output-mix spectrum source, calibration, permissions, product adoption, and remaining audio checks. |
| `speaker-lift-findings.md` | Devialet flip covers. Owns the product contract in "Product contract v2" (2026-09-04): the lever is the playback report `INSTRUMENT_MUSIC_STATE_SET` (`0x43E0000A`) = `1`, live-proven on both cars, and the app never writes the stock auto-lift flag `AUDIO_RLSA_STATE_SET` (`0x16300025`). The direct motor edges, the stock-auto latch side effect and the superseded trigger hypotheses are kept as history. |
| `vehicle-data-findings.md` | Live-car matrix of usable GNSS/IMU/journey data, blocked DiCar getters, `autoservice` FID protocol and where the resolved Feature-ID tables are, widget allowlist, and product boundaries. |
| `telematics/` | The car's own cloud client (`cloudmanager`) and the official Denza phone app: what it takes for the app to see the car online over Wi-Fi, the «Облако» tile, the cloud's command and result codes, the 2026-09-22 investigation, other vehicles. Start at `telematics/README.md`; `telematics-findings.md` is a stub mapping old headings to the pages. |
| `system-language.md` | The firmware's forty languages, the unlisted picker that reaches them, the vendor HAL that applies one live, and the Denza Apps tile. |
| `dishare-api-notes.md` | DiShare/HUD reverse-engineering notes and raw API findings. |
| `hud-projection-findings.md` | The windshield HUD as a display: the working recipe on the owner's car (SOME/IP map window `0x8003` with the road packet, DiShare video in P), what closes it, and the search for a path in motion. BydHud's direct-drive internals, inactive on this car, are in `../research/fse-firmware/bydhud-direct-drive.md`. |
| `fse-app-installation.md` | Passenger-screen Android discovery, SMB delivery, stock cross-device install trigger, verification, and limitations. |
| `split-screen-findings.md` | Live-proven BYD split substrate, the firmware's split read from the 2026-09-23 OTA, the explicit two-picker flow, acceptance evidence, and retired approaches. |
| `split-screen-product-contract.md` | Normative Split Screen contract: user-visible combinatorics, invariants, single-automaton core, delete-first policy, test-audit verdict, live acceptance protocol. Owns the product contract where it diverges from findings. |
| `weather-adapter-findings.md` | Stock BYD weather-provider contract, MET Norway adapter, cache/write behavior, and live proof. |
| `stock-map-findings.md` | What the car's own map is (Amap AutoSDK `GBL 9.810` inside `com.byd.launchermap`), what it draws in Russia (nothing but the car arrow), and the evidence that no setting, offline pack, USB import or tile source can change that. |
| `shortcuts-automation-findings.md` | Shortcuts If/Then catalog, direct PersonBean navigation/music/video roles, live Yandex Navigator/Music and VK Video checks, the retired single-package proxy experiment, the proposed package-replacement recovery path, and the normative steering-wheel Play/Pause resume contract. |
| `carplay-findings.md` | Vehicle hardware/software evidence around CarPlay, PhoneLink/Fission boundaries, and unsupported hypotheses. |
| `car-adb-gateway-architecture.md` | Normative relay-only Car ADB Gateway design and verification status. |
| `car-adb-gateway-decision-log.md` | ADR-lite product/architecture decisions, rationale, evidence, and revisit conditions. |
| `cluster-contest-2026-09/` | Design contest for the driver-display instrument panel: the brief, five concepts (A–E), the jury verdict that chose «Контур» with five binding amendments, and an independent HMI-ergonomics critique of the third board. |

If an investigation produces something worth keeping, update the nearest page.
Add a new document only when the subject has a clear long-term home and would
make an existing page unwieldy. For current behavior, check the code, manifests,
and build files; these pages explain the layout and preserve field evidence.
