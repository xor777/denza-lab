# CLAUDE.md

Working notes for anyone changing this repository.

## What this is

Denza Lab contains apps for a Denza / BYD head unit, the infrastructure around
them, and the research that made those apps possible. The tree has three broad
areas:

- **Apps** — Car ADB Gateway and Denza Apps are active; Denza Mirrors and Denza
  Gateway are frozen under `legacy/`.
- **Experiments** — host scripts in `tools/` and isolated on-device probes;
  historical Mirrors probes stay with the legacy source.
- **What we learned** — durable findings in `docs/` and parked code in
  `research/`.

The GitHub repository is `xor777/denza-lab`. An existing local checkout may
still use the historical `denza-gateway` directory name.

## Read before changing code

- [docs/project-map.md](docs/project-map.md) — structure and per-component status.
- [docs/README.md](docs/README.md) — index of topic-specific durable findings.
- [docs/governance.md](docs/governance.md) — product/prototype/research lanes,
  where experiments live, promotion checklist, live-car debugging rules, and
  the firmware behavior method (corpus-first, reset procedure, one owning
  session).
- [tools/design-canvas/luminofor/README.md](tools/design-canvas/luminofor/README.md)
  — the Luminofor design (approved 2026-09-23), normative for the head unit's
  dashboard, both panes, the strip's two pages and the cluster: `spec.json`,
  the board renderer, the frozen scenes, how to render a board, and how to lay a
  screenshot of the debug build's `LuminoforFixtureActivity` over it with
  `compare.py`. Read it before changing anything under
  `apps/denza-apps/src/main/java/dev/denza/apps/ui/`, `.../design/`,
  `feature/trip` or `feature/cluster/dashboard`.
- [tools/design-canvas/README.md](tools/design-canvas/README.md) — the method
  (boards computed from the code's constants and measured) and the boards before
  Luminofor, kept as their record.
- [docs/energy-display-contract.md](docs/energy-display-contract.md) — normative
  energy contract for the cluster's Contour and the head unit's car page: one
  definition, one set of words and one chart on both screens, and how each is
  proved. It owns pack power's direction, the ten-kilometre consumption, the
  hundred-point chart and the engine's box where it diverges from the findings or
  the canvas README. Read it before touching `feature/vehicle`,
  `feature/cluster/dashboard` or `VehiclePageRenderer`.
- [docs/instrument-display-findings.md](docs/instrument-display-findings.md) — cluster scene, the Contour instrument panel (drawn as Luminofor since 2026-09-23), Mirrors, and navigation status.
- [docs/dishare-api-notes.md](docs/dishare-api-notes.md) — DiShare/HUD findings.
- [docs/fse-app-installation.md](docs/fse-app-installation.md) — verified passenger-screen app installation path.
- [docs/audio-capture-findings.md](docs/audio-capture-findings.md) — what a normal app can observe of played audio (spectrum analyser feasibility).
- [docs/split-screen-findings.md](docs/split-screen-findings.md) — live-proven BYD split substrate, retired router, and the explicit two-picker product flow.
- [docs/split-screen-product-contract.md](docs/split-screen-product-contract.md) — normative Split Screen contract: user-visible combinatorics, invariants, single-automaton core, delete-first policy, test-audit verdict, and the live acceptance protocol. Owns the product contract where it diverges from findings.
- [docs/system-language.md](docs/system-language.md) — the car's language: the stock picker's
  gated list, the unlisted `LOCALE_SETTINGS1` screen with all forty, the vendor
  HAL that applies one without a reboot, how far a switch actually reaches, and
  the Denza Apps tile that opens it. Read it before touching `feature/locale`.
- [docs/adb-authorization-recovery.md](docs/adb-authorization-recovery.md) — passive local-ADB startup gate and bounded recovery flow.
- [docs/vehicle-data-findings.md](docs/vehicle-data-findings.md) — GNSS/IMU for a normal APK; `autoservice` FID protocol for shell-UID BMS/HV/12V reads.
- [docs/weather-adapter-findings.md](docs/weather-adapter-findings.md) — native weather-provider contract and adapter status.
- [docs/shortcuts-automation-findings.md](docs/shortcuts-automation-findings.md) — Shortcuts If/Then catalog; the live-proven navigation, music, and video PersonBean roles; and the firmware-specific actions that honor them; PersonBean itself is readable and writable from the app UID through `ContentResolver` (live-proven 2026-09-03). It also owns the normative steering-wheel Play/Pause contract: identity is the package, the last-played package is persisted, a session that leaves the active list stays addressable, and a package with no live session is reconnected through `MediaBrowser` or its own exported media-button receiver. Read it before touching `feature/media`.
- [docs/speaker-lift-findings.md](docs/speaker-lift-findings.md) — Devialet pop-out covers. On the Z9GT `AUDIO_RLSA_STATE_SET` (`0x16300025`) drives the motor both ways as an edge, `1` out / `2` in, with no audio. On the N9 the same property is the stock auto-lift enable flag: `2` retracts, `1` never raises. The product lever on both cars is the playback report `INSTRUMENT_MUSIC_STATE_SET` (`0x43E0000A`) = `1`, live-proven on the Z9GT (2026-09-03) and the N9 (2026-09-04); the app never touches the flag.
- [docs/car-adb-gateway-architecture.md](docs/car-adb-gateway-architecture.md) and
  [docs/car-adb-gateway-decision-log.md](docs/car-adb-gateway-decision-log.md) — the
  relay-only design of Car ADB Gateway and the decisions behind it. The decision log
  is a precondition for any change to `:car-adb-gateway` or `platform/relay/`.

## Modules

The default build configures the products and the library they share:

| Gradle | Path | App id / namespace |
| --- | --- | --- |
| `:denza-apps` | `apps/denza-apps/` | `dev.denza.apps` (active consolidation app), depends on `:dishare-bridge` |
| `:dishare-bridge` | `libraries/dishare-bridge/` | `dev.denza.disharebridge` (library) |
| `:car-adb-gateway` | `apps/car-adb-gateway/` | `ru.adbgw.gateway` (active product candidate) |

The on-device probes and the frozen legacy app are configured only when the
`experiments` Gradle property is set (`./gradlew -Pexperiments <task>`):

| Gradle | Path | App id / namespace |
| --- | --- | --- |
| `:denza-gateway` | `legacy/denza-gateway/` | `dev.denza.gateway` (legacy/maintenance-only) |
| `:night-vision-probe` | `experiments/night-vision-probe/` | `dev.denza.nightvision.probe` (isolated front-camera source evaluation) |
| `:audio-probe` | `experiments/audio-probe/` | `dev.denza.audio.probe` (isolated audio capture path evaluation) |
| `:display-probe` | `experiments/display-probe/` | `dev.denza.display.probe` (isolated app-owned display evaluation) |
| `:single-package-split-probe` | `experiments/single-package-split-probe/` | `dev.denza.singlepackage.probe` (disposable launcher-alias and same-package picker evaluation) |
| `:adb-rescue-probe` | `experiments/adb-rescue-probe/` | `dev.denza.adbrescue.probe` (second ADB identity for a car whose prompt never renders) |
| `:speaker-lift-yandex-probe` | `experiments/speaker-lift-yandex-probe/` | `dev.denza.speakerlift.yandexprobe` (disposable Yandex-open → stock LOCAL pulse evaluation) |
| `:personbean-provider-probe` | `experiments/personbean-provider-probe/` | `dev.denza.personbean.probe` (disposable app-UID PersonBean ContentResolver evaluation) |
| `:dicar-media-probe` | `experiments/dicar-media-probe/` | `dev.denza.dicarmedia.probe` (disposable app-UID car media service evaluation for the speaker lift) |
| `:split-events-probe` | `experiments/split-events-probe/` | `dev.denza.splitevents.probe` (disposable app-UID split area push, `homekey` and gate evaluation) |
| `:avc-stock-probe` | `experiments/avc-stock-probe/` | `dev.denza.avcstock.probe` (disposable app-UID read and write of the stock AVC mode and turn-camera choice) |

The frozen Denza Mirrors source lives at `legacy/denza-mirrors/` and is not
included in the root Gradle build.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :denza-apps:testDebugUnitTest :denza-apps:assembleDebug
./gradlew :dishare-bridge:testDebugUnitTest
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

Probes and the legacy app are not in the default build; ask for them with
the `experiments` property:

```bash
./gradlew -Pexperiments :adb-rescue-probe:testDebugUnitTest :adb-rescue-probe:assembleDebug
./gradlew -Pexperiments :night-vision-probe:assembleDebug
./gradlew -Pexperiments :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
```

The same form works for every module in the second table above.

## Conventions

- Keep `…​.probe` code out of product dependencies. Denza Apps has no probe or
  Denza Mirrors dependency. The frozen standalone Mirrors source retains one
  documented historical product-to-probe exception.
- Product apps share car-access code only via `:dishare-bridge`.
- Do not add features to `:denza-gateway`. Limit changes to maintenance or work
  required to retire it safely.
- New camera behavior belongs in `:denza-apps`; use
  `legacy/denza-mirrors/` only as a frozen historical reference.
- `:car-adb-gateway` is relay-only. Do not add a LAN listener or configurable
  relay without updating the CAG decision log first.
- Deploy `platform/relay/` only through `ops/ansible`; keep code/grant transitions locked,
  atomic, and covered by relay tests.
- New "poke the car" code goes to `tools/` (host) or a `…​.probe` package
  (on-device), never into a product package.
- Establish firmware behavior corpus-first: read the decompiled
  framework/SystemUI from this vehicle and read-only car dumps before a live
  install. Vendor controllers keep persistent state; a live run is a
  hypothesis test that starts from a documented reset, owned by exactly one
  session at a time. Full rules: `docs/governance.md`, "Firmware Behavior
  Method".
- UI work starts at the board, not at the screen. `tools/design-canvas/luminofor/`
  holds the design; render the board with its `shot.py` and put it beside a
  screenshot of the app before calling a screen finished. Numbers copied off a
  board are not the same as a screen that looks like it - the first cut matched
  every value and matched nothing that could be seen.
- When docs and implementation disagree, follow the code, manifests, and Gradle
  files, then correct the relevant page. A design board is the exception: it and
  the code are both normative, they are joined by `LuminoforSpecContractTest`,
  `LuminoforScreenContractTest`, `StripBoardContractTest`, `StripGeometryTest`,
  `ContourGeometryTest`, `ContourFrameBuilderTest` and
  `ContourFixturesContractTest`, and they move in one change or neither moves;
  a screen is finished when `luminofor/compare.py` finds its screenshot and its
  board agree to antialiasing.
  Energy is joined once more on top of that: `EnergyReadoutsTest` holds the
  cluster and the car page to one answer about every energy string either of them
  prints, and `VehicleLogReplayTest` holds the arithmetic to whatever
  `captures/vehicle-log/` records — files written by the host recorder
  `tools/vehicle_log.py` or by the car's own `VehicleCapture`, which the replay
  reads without knowing which of the two made them.
- Record durable findings in the closest existing doc, not only in chat. Create a
  new `.md` only when the topic has a durable owner. Parked code → `research/`.
- Never commit APKs, reverse-engineered APKs, or large extracted binaries
  (`reverse/`, `captures/`, build outputs are git-ignored).
- Treat a `com.byd.avc` crash as an escalation alert. Capture
  `logcat -b crash -v time`, tell the user once, and continue safe in-scope work
  without repeating the suspected trigger until it is isolated.
