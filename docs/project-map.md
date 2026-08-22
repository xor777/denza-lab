# Project Map

This repository is a small monorepo: Android apps at different lifecycle stages,
one shared library, remote-access infrastructure, and research/tooling areas.
The repository is **Denza Lab** (`xor777/denza-lab`). An existing local checkout
may still use the historical `denza-gateway` directory name.

## Apps

| Path | APK / product | Purpose | Status |
| --- | --- | --- | --- |
| `legacy/denza-gateway/` | `denza-gateway` | SSH gateway from the car LAN to local ADB endpoints on the head unit. | **Legacy.** Maintenance-only; do not add features. Car ADB Gateway supersedes it for new remote-access work. |
| `legacy/denza-mirrors/` | `denza-mirrors` | Original driver-display side-camera enlargement. | **Legacy.** Frozen hardware-verified reference, removed from the root Gradle build after the accepted Denza Apps mirror scenarios were verified on the car. |
| `apps/denza-apps/` | `denza-apps` | Simulcast, side-camera mirrors, navigation and HUD guidance, explicit stock-IVI split sessions, a swipeable bottom panel (trip/spectrum plus vehicle instruments), native weather adaptation, passenger-screen app installation, and a hidden stock-settings Russian locale toggle. | **Active.** Version `0.5.4`; one installed APK contains both the permanent Denza Apps launcher and the toggle-controlled Split Screen alias plus its picker/host. Compose landscape shell, self-recovery, one display resolver, and one shared cluster scene. Mirror cycles, selectable navigation layouts, HUD guidance, trip inputs, native weather refresh, the former contextual split route, and FSE installation have been exercised on the author's car. One-APK split startup, saved-pair restore, native divider movement, full-screen control entry/return, toggle shutdown, and standalone-picker navigation projection/return are live-verified. The Russian locale toggle is locally verified but not yet exercised on the car. The processed front-DVR renderer remains compiled but product-disabled; rapid left-to-right mirror switching remains unsafe. |
| `apps/car-adb-gateway/` | `car-adb-gateway` | Generic relay-only remote ADB gateway. Fixed `adbgw.ru`, one trusted computer, background recovery, no LAN listener. | Product candidate. Local unit/build evidence and the verified relay deployment exist; live-head-unit E2E, API matrix, and soak remain required. |

## Shared Android Modules

| Path | Purpose | Rules |
| --- | --- | --- |
| `libraries/dishare-bridge/` | Raw DiShare binder bridge used by `denza-apps` for screen discovery and starting/stopping shares. | Keep API notes in `docs/dishare-api-notes.md` aligned with transaction behavior. This is the only place product apps may share car-access code. |

## Supporting Areas

| Path | Purpose | Rules |
| --- | --- | --- |
| `docs/` | Stable project knowledge, decisions, and investigation summaries. | Update when behavior, commands, or known limitations change. |
| `platform/relay/` | Car ADB Gateway relay state engine and restricted SSH/PAM commands. | Deploy only through `ops/ansible`; state updates must remain locked, atomic, and idempotent. |
| `platform/cli/` | Cross-platform `cag` developer CLI for macOS/Linux. | Do not edit user SSH config; keep relay and vehicle host-key pinning strict. |
| `ops/ansible/` | Repeatable relay host provisioning and verification. | Never place private keys/passwords in inventory; verify before any live deploy. |
| `tools/` | Host-side scripts for one-off live experiments, including isolated FSE cross-device probes. | Promotion into an app follows `docs/governance.md`. Passenger-screen findings belong in `docs/fse-app-installation.md`. |
| `experiments/night-vision-probe/` | Short-lived, host-driven front-camera source evaluation APK; the directory keeps its historical working name. | Research only. The AVC picture is a wide-angle parking-camera composition with little useful detail at distance. |
| `experiments/audio-probe/` | Short-lived, host-driven evaluation of audio capture paths for a spectrum analyser. | Research only, question answered. `Visualizer` on session 0 reads other apps' audio; `AudioPlaybackCapture` returns silence. See [audio-capture-findings.md](audio-capture-findings.md). |
| `experiments/single-package-split-probe/` | Disposable evaluation of one APK exposing a permanent control icon, a toggle-controlled split icon, and an INFO picker. | Research only. Launcher alias toggling, two same-component picker tasks, control isolation, close, and cold reopen are live-proven. Firmware pair persistence is not usable for restore. See [split-screen-findings.md](split-screen-findings.md). |
| `experiments/display-probe/` | Short-lived evaluation of hosting another app on a display this app owns. | Research only, question answered. A MediaProjection-created public display accepts third-party activities and touch; it cannot be trusted, so windows on it are not focusable. See [split-screen-findings.md](split-screen-findings.md). |
| `research/` | Parked experiments and deprecated modules that stay outside product builds. | Failed or permission-blocked probes live here instead of app source. Current examples are `research/simulcast-aliases/` and `research/vehicle-events/`. |
| `reverse/` | Local reverse-engineering input/output, often large. | APKs and extracted binaries must stay untracked. |

## How to read this repository

For current behavior, start with the implementation:

- Gradle modules live in `settings.gradle.kts`.
- App ids, exported components, and product/probe grouping live in each
  `AndroidManifest.xml`.
- Package boundaries define product vs on-device research code. The historical
  `dev.denza.mirrors` / `dev.denza.mirrors.probe` split is frozen under
  `legacy/denza-mirrors/`.
- CarPlay hardware, stock-software, Fission-boundary, and third-party MFi
  evidence lives in [docs/carplay-findings.md](carplay-findings.md).
- Docs explain direction and preserve field findings. If a page has drifted,
  correct that page instead of adding another status file.

`reverse/` is an untracked local workbench. Keep raw APKs, JADX outputs, captures,
and extracted binaries there; move only distilled, reusable conclusions into the
nearest existing doc.

## Where experiments live

Car experiments have a predictable home:

- **Host-side probes** → `tools/` (shell/python scripts run from the laptop).
- **On-device probes for an existing product's domain** → an isolated
  `…​.probe` subpackage of that product module. Historical Mirrors probes remain
  frozen under `legacy/denza-mirrors/`.
- **Parked / non-built code** → `research/<topic>/` with a README explaining why
  it is not built.

Rule of thumb: keep `…​.probe` code out of product dependencies. Denza Apps has
no dependency on the frozen Mirrors source.
Legacy Denza Mirrors retains one historical violation:
`SideCameraOverlayMonitorService` can drive the experimental
`HudDiShareActivity` HUD path. Do not copy that dependency into active code.

When poking expands to a genuinely different area (not camera/DiShare), promote
the relevant probes into a dedicated experiment module rather than reviving the
legacy Denza Mirrors app.

## Component Inventory

### `legacy/denza-gateway/` (`denza-gateway`)

| Component | Status |
| --- | --- |
| `MainActivity`, `GatewayService`, `SshGatewayServer` | Product path for LAN SSH forwarding to local ADB. |
| `AdbProbe`, `ProbePlan`, `ForwardingPolicy` | Product support code with unit tests. |

### `apps/car-adb-gateway/` (`car-adb-gateway`)

| Component | Status |
| --- | --- |
| `GatewayService`, `GatewaySupervisor`, `GatewayBootReceiver` | `specialUse` foreground lifecycle, independent ADB/relay recovery, boot and package-update restart. |
| `InnerGatewayServer`, `RelayClient` | Loopback-only end-to-end SSH, fixed relay pin, one-computer pairing and relay tunnel. |
| `AdbEndpointDetector`, `AdbProvisioner` | Smart/raw endpoint discovery with own-IPv4 fallback and normal Android ADB-key approval. |
| `MainActivity` | Landscape-first nontechnical onboarding, status/activity, pair/replace, persistent disconnect, hidden support details. |

### `platform/relay/` and `platform/cli/`

| Component | Status |
| --- | --- |
| `platform/relay/cag_state.py` + wrappers | Atomic state, expiring codes, source lockout, device enrollment, pending/commit replacement, dynamic restricted keys. Provisioned on `adbgw.ru` through Ansible and live-verified on 2026-07-18. |
| `platform/cli/cmd/cag` | Go client for `pair`, `connect`, ADB execution, `status`, and `disconnect`; Darwin/Linux builds verified locally. |

### `legacy/denza-mirrors/` (`denza-mirrors`, frozen)

Product package `dev.denza.mirrors`:

| Component | Status |
| --- | --- |
| `MainActivity` | Product UI for Denza Mirrors (was `ProjectionProbeActivity`). |
| `SideCameraOverlayMonitorService`, `SideCameraBootReceiver` | Historical dashboard camera monitor path. |
| `AvcAidlDashActivity` | Historical dashboard AVC display path. |
| `LocalAdbClient`, `AdbKeyStore` | Required support for local ADB commands from the app. |

Research package `dev.denza.mirrors.probe` (not product; promote before relying):

| Component | Status |
| --- | --- |
| `HudDiShareActivity`, `HudImageActivity`, `DiShareProbeActivity`, `DiShareProbeReceiver`, `MediaStreamProbeActivity`, `HudSomeIpProbeActivity` | DiShare/HUD research probes. |
| `AvcSurfaceClient`, `CameraStreamSource`, `CameraGlStreamSource`, `BydMediaStreamServer` | Probe support code for the HUD/media-stream experiments. |
| `AvcTurnSignalMonitorService`, `AvcTurnSignalMonitorActivity` | Legacy direct BYD light API probe. Permission-blocked in normal app tests; not a production trigger. |
| `AvcPipHookActivity`, `DashCameraActivity`, `DashPresentationActivity`, `ProjectionTargetActivity`, `ProjectionCommand*`, map demo activities | Historical probes/demos. Confirm live value before editing or invoking. |

### `apps/denza-apps/`

| Component | Status |
| --- | --- |
| `MainActivity`, `ui/DenzaAppsScreen` | Adaptive Compose shell with three main cards (Navigation, Simulcast, Mirrors) and three compact cards (Split screen, HUD hints, passenger-screen installation). Fullscreen keeps the original landscape dashboard; the measured 2/3 pane exposes that dashboard through horizontal scrolling, while the measured 1/3 pane stacks every card in one vertical list. Pickers use adaptive grids in the narrow pane, and the trip spectrum spans the pane above vertically stacked trip figures. Below the cards sits `ui/BottomPanelPager`: the whole panel swipes between the trip page and the vehicle page, with two dots as its only chrome. Main-card settings use standard Material 3 switches, segmented selectors, and action buttons; selected Simulcast apps stay in one compact horizontal summary. The Navigation card includes an opt-in steering-wheel control: every ★ press independently requests one immediate navigation action, with no double-press behavior. Attention states show a concrete instruction and reuse existing action slots; technical diagnostics remain hidden in Help. |
| `DenzaAppRepository`, `core/FeatureModels`, `DenzaRuntimeCoordinator` | Separate desired/observed feature state, short user-facing status, boot/package-update recovery, and detailed Help diagnostics. The hidden diagnostic view captures raw DiShare receivers, stock Simulcast receiver-card bounds, their usable intersection, and every Android display for N9 rear-screen investigation. |
| Compose app picker | Six-column grid of installed apps; tap to choose up to six for casting. Defaults to the installed subset of VK Video / Rutube / Kinopoisk / Yandex Navigator / VLC / YouTube. |
| `SimulcastApps` | Persists the chosen casting packages (prefs) and seeds defaults. |
| `SimulcastAccessibilityService`, `ScreenTarget` | Active visual path. Draws the selected app row and only accepts drop zones present in both the accessibility tree and runtime-available `DiShareScreens`; includes HUD, FSE, left/right RSE, overhead, and the single-rear `screen_tv` alias while keeping IVI as source. |
| `SimulcastVideoSizeResolver`, `SimulcastVideoBoundsResolver` | Match a DiShare receiver to its physical Android display, retain that target's pixel viewport, and compute centered aspect-fit bounds around DiShare's firmware-enforced minimum 16:9 stream. The whole frame remains visible with correct proportions and unused target space is divided symmetrically. Unmatched targets use the proven `2560x1440` stream against the default IVI viewport; the chosen size, viewport, bounds, and match reason are logged. Rear-screen geometry is unit-tested but not yet live-car verified. |
| `SimulcastDialogGeometry` | Reads live row and receiver geometry from the dialog's accessibility tree instead of assuming fixed HUD/FSE rectangles. |
| `SimulcastOverlayService` | Casting controller: launches the target through `dishare-bridge` with the per-target video size and centered aspect-fit bounds, stops it, and shows the floating native exit control over the casting app. No longer draws the dialog overlay. |
| `SimulcastBootReceiver` | Forwards DiShare dialog actions and invokes runtime recovery after boot or APK replacement. |
| `feature.cluster` | Fail-closed cluster display resolver, real-display geometry, and the shared map-base/camera-overlay scene. The overlay owns AVC side-camera rendering and retains the Camera2 id `0` DVR renderer for bounded research. The DVR trigger is product-disabled through `ClusterDvrFlag` because the vendor source can flip orientation without an observable state signal. No fallback display IDs. |
| `feature.hud` | Optional Yandex turn-by-turn bridge. Reads validated visible guidance across all accessibility displays and publishes maneuver, next-road, remaining route distance/time, and optional road text to the stock HUD SOME/IP road topic; unknown or stale guidance fails closed and clears the projection. |
| `feature.mirrors` | Migrated AVC renderer and window monitor. Uses the shared local ADB client, keeps verified Mirrors geometry/image treatment, and has no probe dependency. |
| `feature.trip` | Compile-time-enabled trip/spectrum panel, now the first page of the swipeable bottom panel. While visible, it uses standard Android gravity/gyro/accelerometer sensors at approximately 30 Hz, GNSS at approximately 1 Hz, the validated Yandex guidance runtime, session-0 `Visualizer`, and the active media session. Sensors, GNSS, audio capture, and rendering stop when the panel is detached or the Activity is paused; swiping to the vehicle page stops only the drawing, so the trip keeps recording. The older mirror-toy/compass/journey-thread presentation is compiled but hidden by `LEGACY_INSTRUMENTS=false`. |
| `feature.vehicle` | Second page of the bottom panel: battery fill, traction and 12 V voltages, a temperature stack with normal bands, and consumption per half-kilometre. Reads a 35-entry `autoservice` allowlist through the passive local ADB client — read transacts only, no `BYDAUTO_*` permission, no `app_process` proxy — batching one command per sweep with an echoed index per call so an unanswered feature id cannot shift the other answers. Hot signals refresh at panel cadence and are never carried over stale; cold signals refresh every 10–30 s. Values that cannot be true for their unit are dropped rather than drawn, and an untrusted ADB key leaves the page empty with the reason. The hub only opens a shell after the page has been opened once. Distance for the histogram comes from the vehicle odometer, so the page needs no location permission. Pack-power sign, the remaining-energy and odometer tenths, and the histogram axis still await the moving capture in [vehicle-data-findings.md](vehicle-data-findings.md); the sign lives in one flippable constant. Not yet exercised on the car. |
| `feature.weather` | Always-enabled `:weather` process adapter. It obtains standard Android location, fetches MET Norway with bounded caching, atomically updates the stock BYD weather provider, and asks stock components to refresh their widgets. See [weather-adapter-findings.md](weather-adapter-findings.md). |
| `feature.adb`, `adb/DenzaLocalAdb` | Live-verified blocking startup gate, compact ADB Rescue flow, and the canonical passive client for Denza Apps. The normal trusted probe is visually silent; unavailable/untrusted states keep the dashboard inert and suppress feature-runtime retries. Only the user-owned one-shot action can enqueue an authorization request. Queue draining remains disabled until the vehicle acceptance gate in [adb-authorization-recovery.md](adb-authorization-recovery.md) passes. |
| `feature.locale` | Hidden Diagnostics switch for the stock `com.byd.carsettings` `ru-RU` app-locale override. It uses fixed commands through the passive local ADB client, verifies every read/write, and clears the override on toggle-off. No translation overlay or global locale mutation. See [stock-russian-locale.md](stock-russian-locale.md). |
| `feature.navigation` | Public app-owned virtual display, fixed shell operations for task movement, an installed-app picker, saved full/left/center/right placement, and an opt-in accessibility-filtered steering-wheel key binding. Every ★ press is immediate and independent; its complete key sequence is consumed only when the navigation state machine accepts the command. The persisted toggle repairs and reports its accessibility readiness independently of Simulcast. Projection, live layout switching, steering-wheel project/return, and HUD guidance while projected are live-car verified. Automatic following of the stock Map mode remains implemented but its unfinished UI control is hidden. |
| `feature.split` | Explicit «Разделить экран» launcher plus two standalone, resizeable Denza picker tasks, one per BYD root. Exact `START_IVI_PRIMARY` / `START_IVI_SECOND` categories create and target the native roots without transaction 115; unresizeable Launcher3/SR bootstrap tasks are not retained as hosts. A picker tap moves exactly the chosen app above that picker; Denza Apps does not route ordinary foreground launches. The last pair and automaton ownership are persisted, and task cleanup is component-validated through the local-ADB shell helper. Because BYD applies split capability to the whole package, the full-screen control entry has an explicit return transition: close its exact task, normalize through Home, discard only invalidated Denza picker identities, then rebuild the saved pair. Direct category placement, picker dismissal/reopen, native divider movement, control return, toggle shutdown, and standalone-picker navigation projection/return are live-proven. Simulcast still needs its own focused cross-feature acceptance pass. |
| `feature.fse` | Lists suitable launcher apps from the IVI, copies a monolithic APK over the mounted FSE storage, sends the stock wallpaper installation request, and reports copy/install progress. Split APKs are shown but cannot be installed yet. |

### `experiments/night-vision-probe/`

| Component | Status |
| --- | --- |
| `NightVisionProbeActivity` | Historical class name for a short-lived source evaluator. Live work proved AVC `SUB_CAMERA_FRONT` (`2001`) can be handed to the Denza Apps/Mirrors presentation shape, then cropped to the rightmost `57%` in the centered `1023x720` camera frame. The source is the wide-angle surround-view composition and contains little useful detail at distance. |
| `tools/night_vision_probe.sh` | Research safety wrapper, not a product/operator feature. Its original `start` flow predates the accepted stock warm-handoff sequence; retain it for build/install/preflight/status evidence until the experiment is either repurposed for the DVR source or removed. |

### `experiments/audio-probe/`

| Component | Status |
| --- | --- |
| `AudioCaptureProbeActivity` | Answers whether a non-privileged app can observe what the car is playing. `Visualizer` on audio session 0 works and is source-agnostic; verified against VLC to a hundredth of a decibel and against live Yandex Music. Carries a reference-tone mode used to calibrate the misreported sample rate. |
| `PlaybackCaptureService` | `AudioPlaybackCapture` via `MediaProjection`. Initialises and reads frames, but every sample is zero while audio is audibly playing — the path is unusable here, and any recheck must assert on levels rather than status. |

### `experiments/single-package-split-probe/`

| Component | Status |
| --- | --- |
| `ProbeControlActivity`, `SplitEntryAlias` | One-package launcher topology probe. The control entry is permanent and the disabled-by-default alias can be added or removed immediately by the package itself without restarting Launcher3. The off/on cycle and alias tap are verified in the real app center UI. |
| `ProbePickerActivity` | Pane-neutral `MAIN + INFO` target. Two simultaneous tasks of this exact component are live-proven in roots 2/3, including fullscreen control isolation and clean picker close. SmartMulti does not persist the moved same-package pair, so product restore must remain app-owned. |
| `tools/single_package_split_probe.sh` | Host safety wrapper. Recreates collapsed native geometry through the exact baseline, transaction 115 and focus; creates, identifies, moves and resizes the two probe tasks; removes only exact bootstrap tasks; and never invokes runtime allowlist transaction 125. |

### `libraries/dishare-bridge/`

| Component | Status |
| --- | --- |
| `DiShareProjectionBridge` | Active raw binder wrapper for DiShare API/control services. Callers pass the share video size and optional target-view bounds per cast; Denza Apps uses the bounds for centered aspect-fit. Video or bounds dimensions outside `180..4096` fall back to the legacy safe paths. |
| `DiShareScreens` | Screen-discovery wrapper for `getScreens` (available receivers). |
| `LocalAdbClient`, `AdbKeyStore` | Shared `adbd` shell client with explicit automatic/passive authorization policy and a one-shot request API. The ADB identity is stored atomically under a cross-process file lock with migration from the legacy preferences. Tries loopback first, then local non-loopback IPv4 addresses because some firmwares expose ADB on WLAN but not `127.0.0.1`. See [adb-authorization-recovery.md](adb-authorization-recovery.md). |

### `research/simulcast-aliases/` (deprecated)

| Component | Status |
| --- | --- |
| `launcher` flavors | DEPRECATED and parked. Belonged to the old FLAG_NOT_TOUCHABLE + alias-launch path. Dropped from `settings.gradle.kts`; the accessibility overlay replaces it. Only the `denza-apps` APK is required on a fresh car. |

## Current Product Direction

- `car-adb-gateway` is the active generic relay-only connectivity app. It must
  not grow a LAN mode.
- `denza-apps` is the single active Denza feature app. Simulcast, migrated camera
  rendering, navigation and HUD guidance, explicit stock split sessions, the
  swipeable trip/vehicle bottom panel, native weather adaptation, and
  passenger-screen installation share one UI and runtime state model.
- `denza-mirrors` is legacy and excluded from the root Gradle build. Use its
  frozen source only as a hardware-verified comparison point.
- `denza-gateway` is legacy and maintenance-only. The source remains buildable
  for existing installations, but new connectivity work belongs in Car ADB
  Gateway.
- For Simulcast, normal app uid is enough for direct DiShare launches. The native
  `ShareApp` visual metadata is solved at the UI layer: the accessibility overlay
  erases the stock row and paints the chosen apps over it (no metadata injection,
  no helper APKs). The old alias/`SourceKeeperService` path is removed.
- HUD camera output is not a supported product path from a normal debug APK.
  DiShare can show generated frames and some app-accessible Camera2 feeds, but
  protected side/AVC feeds remain blocked.
- Vehicle event APIs are research-only for now. Normal app uid access to direct
  BYD getters/listeners was permission-blocked or did not deliver useful
  callbacks.

## Completed Denza Mirrors retirement

On 2026-07-19, after isolated mirror scenarios passed on the car and the
standalone app was retired, its source moved to
`legacy/denza-mirrors/` and was removed from the root Gradle build. Before the
move, `:denza-mirrors:assembleDebug` passed. The active `:denza-apps` module
depends only on `:dishare-bridge`; neither its sources nor the shared library
reference `dev.denza.mirrors` or `:denza-mirrors`.

The fast left-to-right AVC crash is still documented. The frozen app remains a
useful hardware reference; new camera work belongs in Denza Apps.

## Build Outputs

Git ignores generated APKs.

```bash
./gradlew :denza-gateway:assembleDebug
./gradlew :denza-apps:testDebugUnitTest :denza-apps:assembleDebug
./gradlew :night-vision-probe:assembleDebug
./gradlew :audio-probe:assembleDebug
./gradlew :single-package-split-probe:assembleDebug
./gradlew :display-probe:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

Useful local APK paths:

```text
legacy/denza-gateway/build/outputs/apk/debug/denza-gateway.apk
apps/denza-apps/build/outputs/apk/debug/denza-apps.apk
experiments/night-vision-probe/build/outputs/apk/debug/night-vision-probe.apk
experiments/audio-probe/build/outputs/apk/debug/audio-probe.apk
experiments/single-package-split-probe/build/outputs/apk/debug/single-package-split-probe.apk
experiments/display-probe/build/outputs/apk/debug/display-probe.apk
apps/car-adb-gateway/build/outputs/apk/debug/car-adb-gateway.apk
```

Do not stage APK files. If a large APK appears in `git status`, fix `.gitignore`
first.
