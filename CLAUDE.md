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
- [tools/design-canvas/README.md](tools/design-canvas/README.md) — the
  artboards the head unit and cluster are drawn from, the two type ramps, how to
  render a board and how to measure one, and the unit tests that fail when a
  board and the app disagree. Read it before changing anything under
  `apps/denza-apps/src/main/java/dev/denza/apps/ui/` or `.../design/`.
- [docs/instrument-display-findings.md](docs/instrument-display-findings.md) — cluster scene, Mirrors, and navigation status.
- [docs/dishare-api-notes.md](docs/dishare-api-notes.md) — DiShare/HUD findings.
- [docs/fse-app-installation.md](docs/fse-app-installation.md) — verified passenger-screen app installation path.
- [docs/audio-capture-findings.md](docs/audio-capture-findings.md) — what a normal app can observe of played audio (spectrum analyser feasibility).
- [docs/split-screen-findings.md](docs/split-screen-findings.md) — live-proven BYD split substrate, retired router, and the explicit two-picker product flow.
- [docs/split-screen-product-contract.md](docs/split-screen-product-contract.md) — normative Split Screen contract: user-visible combinatorics, invariants, single-automaton core, delete-first policy, test-audit verdict, and the live acceptance protocol. Owns the product contract where it diverges from findings.
- [docs/stock-russian-locale.md](docs/stock-russian-locale.md) — captured BYD Settings locale behavior and the narrow native-resource toggle.
- [docs/adb-authorization-recovery.md](docs/adb-authorization-recovery.md) — passive local-ADB startup gate and bounded recovery flow.
- [docs/vehicle-data-findings.md](docs/vehicle-data-findings.md) — GNSS/IMU for a normal APK; `autoservice` FID protocol for shell-UID BMS/HV/12V reads.
- [docs/weather-adapter-findings.md](docs/weather-adapter-findings.md) — native weather-provider contract and adapter status.
- [docs/shortcuts-automation-findings.md](docs/shortcuts-automation-findings.md) — Shortcuts If/Then catalog; the live-proven navigation, music, and video PersonBean roles; and the firmware-specific actions that honor them; PersonBean itself is readable and writable from the app UID through `ContentResolver` (live-proven 2026-09-03).
- [docs/speaker-lift-findings.md](docs/speaker-lift-findings.md) — Devialet pop-out covers. On the Z9GT `AUDIO_RLSA_STATE_SET` (`0x16300025`) drives the motor both ways as an edge, `1` out / `2` in, with no audio. On the N9 the same property is the stock auto-lift enable flag: `2` retracts, `1` never raises, and the raise correlates with a stock playback-state transition rather than with audio.

## Modules

| Gradle | Path | App id / namespace |
| --- | --- | --- |
| `:denza-gateway` | `legacy/denza-gateway/` | `dev.denza.gateway` (legacy/maintenance-only) |
| `:denza-apps` | `apps/denza-apps/` | `dev.denza.apps` (active consolidation app), depends on `:dishare-bridge` |
| `:dishare-bridge` | `libraries/dishare-bridge/` | `dev.denza.disharebridge` (library) |
| `:night-vision-probe` | `experiments/night-vision-probe/` | `dev.denza.nightvision.probe` (isolated front-camera source evaluation) |
| `:audio-probe` | `experiments/audio-probe/` | `dev.denza.audio.probe` (isolated audio capture path evaluation) |
| `:display-probe` | `experiments/display-probe/` | `dev.denza.display.probe` (isolated app-owned display evaluation) |
| `:single-package-split-probe` | `experiments/single-package-split-probe/` | `dev.denza.singlepackage.probe` (disposable launcher-alias and same-package picker evaluation) |
| `:adb-rescue-probe` | `experiments/adb-rescue-probe/` | `dev.denza.adbrescue.probe` (second ADB identity for a car whose prompt never renders) |
| `:speaker-lift-yandex-probe` | `experiments/speaker-lift-yandex-probe/` | `dev.denza.speakerlift.yandexprobe` (disposable Yandex-open → stock LOCAL pulse evaluation) |
| `:personbean-provider-probe` | `experiments/personbean-provider-probe/` | `dev.denza.personbean.probe` (disposable app-UID PersonBean ContentResolver evaluation) |
| `:dicar-media-probe` | `experiments/dicar-media-probe/` | `dev.denza.dicarmedia.probe` (disposable app-UID car media service evaluation for the speaker lift) |
| `:car-adb-gateway` | `apps/car-adb-gateway/` | `ru.adbgw.gateway` (active product candidate) |

The frozen Denza Mirrors source lives at `legacy/denza-mirrors/` and is not
included in the root Gradle build.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-apps:assembleDebug
./gradlew :night-vision-probe:assembleDebug
./gradlew :audio-probe:assembleDebug
./gradlew :display-probe:assembleDebug
./gradlew :single-package-split-probe:assembleDebug
./gradlew :speaker-lift-yandex-probe:assembleDebug
./gradlew :personbean-provider-probe:assembleDebug
./gradlew :dicar-media-probe:assembleDebug
./gradlew :adb-rescue-probe:testDebugUnitTest :adb-rescue-probe:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

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
- UI work starts at the board, not at the screen. `tools/design-canvas/` holds
  the design; render the board with `shot.py` and put it beside a screenshot of
  the car before calling a screen finished. Numbers copied off a board are not
  the same as a screen that looks like it - the first cut matched every value
  and matched nothing that could be seen.
- When docs and implementation disagree, follow the code, manifests, and Gradle
  files, then correct the relevant page. A design board is the exception: it and
  the code are both normative, they are joined by `MainBoardContractTest` and
  `SpectrumBoardContractTest`, and they move in one change or neither moves.
- Record durable findings in the closest existing doc, not only in chat. Create a
  new `.md` only when the topic has a durable owner. Parked code → `research/`.
- Never commit APKs, reverse-engineered APKs, or large extracted binaries
  (`reverse/`, `captures/`, build outputs are git-ignored).
- Treat a `com.byd.avc` crash as an escalation alert. Capture
  `logcat -b crash -v time`, tell the user once, and continue safe in-scope work
  without repeating the suspected trigger until it is isolated.
