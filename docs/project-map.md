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
| `apps/denza-apps/` | `denza-apps` | Simulcast, side-camera mirrors, navigation and HUD guidance, explicit stock-IVI split sessions, Contour instruments, a bottom trip/spectrum strip, native weather adaptation, passenger-screen app installation, Shortcuts default-app role selection, and a hidden stock-settings Russian locale toggle. | **Active.** Version `0.6.1`; one installed APK contains the ordinary Denza Apps launcher and the toggle-controlled Split Screen alias and picker. Compose landscape shell, self-recovery, one display resolver, and one shared cluster scene. Mirror cycles, selectable navigation layouts, HUD guidance, trip inputs, native weather refresh, the former contextual split route, and FSE installation have been exercised on the author's car. The rebuilt Split Screen core still awaits the acceptance protocol in [split-screen-product-contract.md](split-screen-product-contract.md). Shortcuts roles store the selected application directly; update recovery is deferred after the single-package navigation proxy failed live routing. The latest Mirrors cancellation fix and startup-notification change have focused live checks; the wider reversal/same-side/stress matrix remains open. Signal-hub delivery hardening has host-only validation. See [instrument-display-findings.md](instrument-display-findings.md) and [vehicle-data-findings.md](vehicle-data-findings.md) for the exact build boundaries. |
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
| `experiments/speaker-lift-yandex-probe/` | No-Activity normal-UID APK that observes entry into Yandex Music and issues the verified one-second stock MediaCenter LOCAL pulse. | Prototype only. Locally built and manifest-audited; normal-UID behavior awaits a clean post-reboot car test. See [speaker-lift-findings.md](speaker-lift-findings.md). |
| `experiments/display-probe/` | Short-lived evaluation of hosting another app on a display this app owns. | Research only, question answered. A MediaProjection-created public display accepts third-party activities and touch; it cannot be trusted, so windows on it are not focusable. See [split-screen-findings.md](split-screen-findings.md). |
| `experiments/adb-rescue-probe/` | A second ADB identity, for a car whose authorization prompt never renders and whose owner is the only person who can reach it. Reads what any app may read, can spend its own prompt slot, and once trusted drains the queue that holds Denza Apps' request. | Research only, question answered. Run live 2026-08-29: eight public-key submissions on a car with `adb_enabled = 1` and adbd listening produced no authorization dialog, which establishes the failure as system-side and not Denza Apps. It never reads or clicks the system dialog, and never clears Denza Apps' data. See [adb-authorization-recovery.md](adb-authorization-recovery.md). |
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
| `MainActivity`, `ui/DenzaAppsScreen` | Adaptive Compose shell with eleven dashboard tiles, including the `Приложения` entry for navigation, music and video defaults used by stock Shortcuts. Its panel reads and writes the three AutoVoice PersonBean roles directly through `ContentResolver` (live-proven 2026-09-03), and every role stores the selected application's real package. There is no default-app runtime proxy or background role listener. Every installed launcher remains selectable, but media command compatibility still depends on the application's firmware media integration. Fullscreen keeps the original landscape dashboard; the measured 2/3 pane exposes that dashboard through horizontal scrolling, while the measured 1/3 pane stacks every tile in one vertical list. Choosing an application is one chooser page everywhere: a panel shows what is chosen on a row and turns into the grid when the row is pressed (`tools/design-canvas/README.md`, "Choosing an application"); the grid fits what fits in the narrow pane, and the trip spectrum spans the pane above vertically stacked trip figures. Below the tiles sits the trip strip, whose **field has two pages and whose dots under it say so**: the analyser, and the car's own readings — what the pack is doing, two minutes of it as twenty-four five-second steps, five temperatures against the cluster's own thresholds, and what the last three kilometres cost. A horizontal swipe over the field moves between them, the choice is remembered, and the three trip figures beside the field are on both pages and never move. The car's page claims `VehicleTelemetryHub` only while it is on screen. This is not the return of `BottomPanelPager`, which had four pages nobody could see and was deleted on 2026-08-27: two pages, one gesture, and an indicator that is drawn whether or not anybody swipes (`tools/design-canvas/README.md`, "The strip is two pages"). Settings use standard Material 3 switches, segmented selectors, and action buttons; the selected Simulcast apps are a strip of their icons on the panel's row, and «Экран справа» opens its chooser from both gestures on the tile. The Navigation tile includes an opt-in steering-wheel control: every ★ press independently requests one immediate navigation action, with no double-press behavior. Its picker also offers this app's own instruments (`Приборы`) beside the navigators; that choice has one placement, so the tile drops its placement row instead of showing three dead cells. Attention states show a concrete instruction and reuse existing action slots; technical diagnostics remain hidden in Help. |
| `DenzaAppRepository`, `core/FeatureModels`, `DenzaRuntimeCoordinator` | Separate desired/observed feature state, short user-facing status, boot/package-update recovery, and detailed Help diagnostics. The hidden diagnostic view captures raw DiShare receivers, stock Simulcast receiver-card bounds, their usable intersection, and every Android display for N9 rear-screen investigation. |
| Compose app picker | Six-column grid of installed apps; tap to choose up to six for casting. Defaults to the installed subset of VK Video / Rutube / Kinopoisk / Yandex Navigator / VLC / YouTube. |
| `SimulcastApps` | Persists the chosen casting packages (prefs) and seeds defaults. |
| `SimulcastAccessibilityService`, `ScreenTarget` | Active visual path. Draws the selected app row and only accepts drop zones present in both the accessibility tree and runtime-available `DiShareScreens`; includes HUD, FSE, left/right RSE, overhead, and the single-rear `screen_tv` alias while keeping IVI as source. |
| `SimulcastVideoSizeResolver`, `SimulcastVideoBoundsResolver` | Match a DiShare receiver to its physical Android display, retain that target's pixel viewport, and compute centered aspect-fit bounds around DiShare's firmware-enforced minimum 16:9 stream. The whole frame remains visible with correct proportions and unused target space is divided symmetrically. Unmatched targets use the proven `2560x1440` stream against the default IVI viewport; the chosen size, viewport, bounds, and match reason are logged. Rear-screen geometry is unit-tested but not yet live-car verified. |
| `SimulcastDialogGeometry` | Reads live row and receiver geometry from the dialog's accessibility tree instead of assuming fixed HUD/FSE rectangles. |
| `SimulcastOverlayService` | Casting controller: launches the target through `dishare-bridge` with the per-target video size and centered aspect-fit bounds, stops it, and shows the floating native exit control over the casting app. No longer draws the dialog overlay. |
| `RuntimeRecoveryReceiver` | Non-exported receiver that invokes runtime recovery only after boot or APK replacement. DiShare dialog visibility comes from the already-required accessibility observer, not app-spoofable vendor broadcasts. |
| `feature.cluster` | Fail-closed cluster display resolver, real-display geometry, and the shared map-base/camera-overlay scene, plus `feature.cluster.dashboard`: an app-owned instrument dashboard drawn as a plain view in the base presentation, with no virtual display and no projection. Its keep-outs are derived from the map shade so the two cannot drift; the renderer supports `FULL` and `RIGHT` and refuses `CENTER`/`LEFT`, while the product offers `FULL` alone. State of charge and range are deliberately absent as stock duplicates. Reached from the Navigation card as one more choice in its picker, beside the navigators; the projection machinery — transfer overlay, split routing, task discovery, and health checks — is skipped, but the display is resolved and the overlay appop granted before the scene is asked for. Built, unit-tested, and run on the car on 2026-08-25. The overlay owns AVC side-camera rendering. The retired Camera2 DVR implementation and its camera capability were removed from the product on 2026-08-26; its hardware findings remain documented for isolated research. No fallback display IDs. |
| `feature.hud` | Optional Yandex turn-by-turn bridge. Reads validated visible guidance across all accessibility displays and publishes maneuver, next-road, remaining route distance/time, and optional road text to the stock HUD SOME/IP road topic; unknown or stale guidance fails closed and clears the projection. |
| `feature.mirrors` | Migrated AVC renderer and stock-window monitor, using shared local ADB and typed signal demand leases, with no probe dependency. The stock-window observer is the only Show authority. A raw opposite-side onset can tear down an active Denza camera early; a same-side pulse does not. Reopening waits for idle runtime and completed vendor teardown. Ordinary starts and opposite/new stock windows require no CAN confirmation. Only reuse of a continuously surviving preempted-side window requires a fresh matching mode observation newer than the preempt, then five settling polls; window absence instead ends the old cycle. This prevents the multi-second stock cancellation tail from reopening our camera. Startup timing distinguishes initialization from the first texture update; success notifications leave the startup critical path. Focused cancellation/startup checks passed on the car, but broader reversal/same-side/stress acceptance and the integrated hub-hardening build remain unverified. See [vehicle-data-findings.md](vehicle-data-findings.md#window-only-show-onset-only-teardown-2026-09-04-late). |
| `feature.trip` | Always-present trip/spectrum strip. While visible, it uses standard Android GNSS at approximately 1 Hz, the validated Yandex guidance runtime, session-0 `Visualizer`, and the active media session. GNSS, audio/media inputs, and rendering stop when the panel is detached or the Activity is paused. The retired mirror-toy, compass, journey-thread, event, and IMU pipeline was removed from product code on 2026-08-26. |
| `feature.vehicle` | Vehicle-telemetry backend used by `feature.cluster.dashboard`, plus the process-local typed `VehicleSignalHub`. Telemetry still uses its bounded `autoservice` allowlist and existing lifecycle. The Hub's first source is a separately scheduled, event-driven BYD light listener shared through demand leases; each activation registers only the union of keys currently requested by consumers. It exposes typed, freshness-bounded state, never arbitrary FIDs, frames, shell commands, or vehicle actions. |
| `feature.weather` | Always-enabled `:weather` process adapter. It obtains standard Android location, fetches MET Norway with bounded caching, atomically updates the stock BYD weather provider, and asks stock components to refresh their widgets. See [weather-adapter-findings.md](weather-adapter-findings.md). |
| `feature.adb`, `adb/DenzaLocalAdb` | Live-verified blocking startup gate, compact ADB Rescue flow, and the canonical passive client for Denza Apps. The normal trusted probe is visually silent; unavailable/untrusted states keep the dashboard inert and suppress feature-runtime retries. Only the user-owned one-shot action can enqueue an authorization request. Queue draining remains disabled until the vehicle acceptance gate in [adb-authorization-recovery.md](adb-authorization-recovery.md) passes. |
| `feature.locale` | Hidden Diagnostics switch for the stock `com.byd.carsettings` `ru-RU` app-locale override. It grants only `CHANGE_CONFIGURATION` once through the passive local ADB client, then uses the package-aware Android 13 `LocaleManager` directly for both on and off. It saves its choice because reading another package's app-locale requires a signature-only permission. No translation overlay or global locale mutation. See [stock-russian-locale.md](stock-russian-locale.md). |
| `feature.navigation` | Public app-owned virtual display, fixed shell operations for task movement, an installed-app picker, saved full/left/center/right placement, and an opt-in accessibility-filtered steering-wheel key binding. Every ★ press is immediate and independent; its complete key sequence is consumed only when the navigation state machine accepts the command. The persisted toggle repairs and reports its accessibility readiness independently of Simulcast. Projection, live layout switching, steering-wheel project/return, and HUD guidance while projected are live-car verified. Automatic following of the stock Map mode remains implemented but its unfinished UI control is hidden. |
| `feature.split` | Explicit «Разделить экран» launcher plus two standalone, resizeable Denza picker tasks, one per BYD root. Exact `START_IVI_PRIMARY` / `START_IVI_SECOND` categories create and target the native roots without transaction 115; unresizeable Launcher3/SR bootstrap tasks are not retained as hosts. The dedicated accessibility observer only primes a background shell when the stock picker appears; it creates no window and performs no task mutation until BYD reports balanced area `3`, the active divider pointer is released, and that released state is stable. A picker tap launches exactly the chosen app as an app-owned task above that picker, including Denza Apps itself, which has no special-case logic of any kind; ordinary apps use independent multiple tasks, while `singleTask`/`singleInstance` duplicates remain rejected by their Android contract. Since 2026-08-23 the feature runs on the contract core of [split-screen-product-contract.md](split-screen-product-contract.md), which is the normative source for split screen: where this table and that document disagree, that document wins. The core is four pieces behind one Android boundary. `SplitAutomaton` is a pure `(state, settled fact) -> (state', plans)` function and the only writer of semantic state; it knows nothing of ADB, time, threads or task ids, and an impossible fact is a strict no-op. `SplitActor` owns the single mutation queue with priority preemption rather than a FIFO executor, so `DISABLE` and `HOME` cancel and overtake what they outrank, a repeated launcher tap joins the live operation instead of starting a second one, and passive window hints coalesce to one. `SplitOperations` executes the ten steps of contract section 7 over the live-proven `SplitPickerShellSession` recipes, whose commands, settle pauses and identity checks are unchanged: every command and every pause passes a cancellation fence, a mutation journal records what may be undone and where the point of no return is, and a failure replays that journal backwards. `SplitStore` is one atomic snapshot behind one preferences key - toggle, revision and one package slot per pane - with a one-shot migration that reads the keys of the previous generations once and deletes them; task and root ids cannot appear there by construction, so a reboot has no numbers to trust. `SplitScreenCoordinator` is only the Android boundary: a persistent shell, the waiting window, the launcher catalog, the two global leases and a main-thread callback. Every product input enters `SplitCoordinatorCore` and becomes exactly one operation, which is what makes the mandatory K1-K15 scenarios of appendix B.3 ordinary unit tests (`SplitScenarioTest`). The retired router, transparent app host, placeholder and their persisted task ids are gone from the tree. Direct category placement, picker dismissal/reopen, divider movement including the coalesced-base boundary, collapse/reopen restoration, toggle shutdown, standalone-picker navigation projection/return, post-reboot recovery, and Home isolation are live-proven for the previous generation; the rebuilt core has no live acceptance yet - it runs as section 12 of the contract, by an independent agent, and is waiting for the car. Simulcast still needs its own focused cross-feature acceptance pass. |
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
  always-present trip/spectrum bottom strip, native weather adaptation, and
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
./gradlew :speaker-lift-yandex-probe:assembleDebug
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
experiments/speaker-lift-yandex-probe/build/outputs/apk/debug/speaker-lift-yandex-probe.apk
experiments/display-probe/build/outputs/apk/debug/display-probe.apk
apps/car-adb-gateway/build/outputs/apk/debug/car-adb-gateway.apk
```

Do not stage APK files. If a large APK appears in `git status`, fix `.gitignore`
first.
