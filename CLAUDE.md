# CLAUDE.md

Working notes for anyone changing this repository. `AGENTS.md` is a symlink to
this file, so Claude Code and Codex load the same rules. Keep it a router: one
line per doc and no status, which lives in each doc's Current state table. Keep
it under 32 KiB, the size Codex loads by default.

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

## Where to start

- [docs/project-map.md](docs/project-map.md) — structure and per-component status.
- [docs/README.md](docs/README.md) — index of the findings docs.
- [docs/governance.md](docs/governance.md) — product/prototype/research lanes,
  where experiments live, promotion checklist, live-car debugging rules, the
  firmware behavior method, and how a findings doc is laid out.

Every findings doc opens with a **Current state** table (claim, status, section)
and a contents list. Read that first; then `rg -n '^#{2,3} ' <doc>` and
`sed -n 'a,bp'`. The docs are 40–240 KB, so never `cat` one whole.

## Read before touching

| You are changing | Read first |
| --- | --- |
| `ui/`, `design/`, `feature/trip`, `feature/cluster/dashboard` | [tools/design-canvas/luminofor/README.md](tools/design-canvas/luminofor/README.md) — the Luminofor design, normative: how to render a board and lay a `LuminoforFixtureActivity` screenshot over it with `compare.py`. [tools/design-canvas/README.md](tools/design-canvas/README.md) is the method and the boards before Luminofor. |
| `feature/vehicle`, `feature/cluster/dashboard`, `VehiclePageRenderer` | [docs/energy-display-contract.md](docs/energy-display-contract.md) — normative energy contract for the Contour and the car page. [docs/vehicle-data-findings.md](docs/vehicle-data-findings.md) — GNSS/IMU and the `autoservice` FID protocol. |
| `feature/split` | [docs/split-screen-product-contract.md](docs/split-screen-product-contract.md) — normative; wins over [docs/split-screen-findings.md](docs/split-screen-findings.md). |
| `feature/media`, `feature/defaultapps` | [docs/shortcuts-automation-findings.md](docs/shortcuts-automation-findings.md) — PersonBean roles and the normative steering-wheel Play/Pause contract. |
| `feature/cloud` | [docs/telematics/README.md](docs/telematics/README.md) — the car's own cloud client (`cloudmanager`) over Wi-Fi, the «Облако» tile, result codes. |
| `feature/hud`, `feature/fse` | [docs/hud-projection-findings.md](docs/hud-projection-findings.md) — the HUD as a display and the working recipe on this car; [docs/fse-app-installation.md](docs/fse-app-installation.md) — installing on the passenger computer; [research/fse-firmware/README.md](research/fse-firmware/README.md), with BydHud's direct-drive internals (inactive on this car) in `bydhud-direct-drive.md` there. Turn-by-turn guidance: [docs/instrument-display-findings.md](docs/instrument-display-findings.md), "HUD turn-by-turn guidance". |
| `feature/cluster`, `feature/mirrors`, `feature/navigation` | [docs/instrument-display-findings.md](docs/instrument-display-findings.md) — cluster scene, Contour, Mirrors, navigation projection. |
| `feature/simulcast`, DiShare in `:dishare-bridge` | [docs/dishare-api-notes.md](docs/dishare-api-notes.md) |
| `feature/speaker` | [docs/speaker-lift-findings.md](docs/speaker-lift-findings.md) |
| `feature/locale` | [docs/system-language.md](docs/system-language.md) |
| `feature/weather` | [docs/weather-adapter-findings.md](docs/weather-adapter-findings.md) |
| `feature/adb`, `LocalAdbClient`, the startup gate | [docs/adb-authorization-recovery.md](docs/adb-authorization-recovery.md) |
| The spectrum analyser, audio capture | [docs/audio-capture-findings.md](docs/audio-capture-findings.md) |
| The car's own map | [docs/stock-map-findings.md](docs/stock-map-findings.md) |
| CarPlay | [docs/carplay-findings.md](docs/carplay-findings.md) |
| `:car-adb-gateway`, `platform/relay/` | [docs/car-adb-gateway-decision-log.md](docs/car-adb-gateway-decision-log.md) — a precondition for any change — and [docs/car-adb-gateway-architecture.md](docs/car-adb-gateway-architecture.md). |

Package paths are under `apps/denza-apps/src/main/java/dev/denza/apps/`.

## Firmware corpus (local, untracked)

The owner's IVI OTA `Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip` and FSE OTA
`Di5.1_FSE_42.1.8.2605219.1.42.2.3.2605250.2.zip` sit in `~/Downloads`. They have
already been extracted and decompiled several times. Look before extracting again:

- `captures/split-firmware-20260923/`, `hud-firmware-20260923/`,
  `ambient-light-20260923/`, `speaker-firmware-20260923/`,
  `washer-firmware-20260923/`, `adb-firmware-20261006/` (init/adbd/USB scripts
  and BYD developer tools) — parts of the IVI OTA, with `jadx/` trees and,
  where present, `extraction.json` / `files-*.txt` listings.
  `captures/fse-firmware-20260924/` is the FSE OTA.
  `reverse/*-jadx` holds older decompiles; `reverse/dishare-jadx` is an older
  DiShare build than the car's.
- Readers: `research/split-firmware/`, `research/telematics-firmware/`,
  `research/fse-firmware/`. The first two take `DENZA_FIRMWARE_ARCHIVE` and
  `DENZA_FIRMWARE_OUTPUT`; the output directory must contain
  `Config-readable.xml`, which only `captures/telematics-20260923/readable-firmware/`
  has.
- Feature IDs: jadx cannot evaluate the `BYDAutoFeatureIds` initializer. The
  resolved table is `captures/ambient-light-20260923/data/fids-canfd.tsv` (and
  `fids-can-classic.tsv`, built by `resolve_clinit.py` beside them).
- `rg` skips the git-ignored `captures/` and `reverse/`; search them with
  `rg --no-ignore`.

## Parked work

- Branch `archive/cloudmanager-runtime` — the custom-SIM runtime for
  `cloudmanager` (`tools/telematics/runtime/`,
  `research/telematics-firmware/persistent_runtime.c`,
  `docs/cloud-custom-runtime-contract.md`, the CUSTOM mode of `feature/cloud`),
  moved off main in `eed3a411`.

## Shell

- The shell is zsh: quote globs (`grep -r --include='*.kt'`) or use
  `rg -g '*.kt'`. An unquoted `*.kt` fails with "no matches found".
- `grep` is ugrep; a missing file is a warning, not an error.
- There is no `timeout` or `gtimeout`. Bound a command with
  `perl -e 'alarm shift; exec @ARGV' 20 <command>`.

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
| `:hud-frames-probe` | `experiments/hud-frames-probe/` | `dev.denza.hudframes.probe` (disposable moving-frames test of the HUD's picture slots and DiShare video) |
| `:fse-hud-inspector` | `experiments/fse-hud-inspector/` | `dev.denza.fsehud.probe` (FSE snapshot/export and a manual 20-second local arhud Activity; no vehicle setters or DiShare) |
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
  Update the doc's Current state table in the same change. When a finding
  overturns an earlier one, mark the earlier one where it stands
  (`docs/governance.md`, "Findings Documents"); a correction appended further
  down is not enough.
- Never commit APKs, reverse-engineered APKs, or large extracted binaries
  (`reverse/`, `captures/`, build outputs are git-ignored).
- Treat a `com.byd.avc` crash as an escalation alert. Capture
  `logcat -b crash -v time`, tell the user once, and continue safe in-scope work
  without repeating the suspected trigger until it is isolated.
