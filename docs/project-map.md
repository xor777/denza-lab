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
| `apps/denza-apps/` | `denza-apps` | Simulcast, side-camera mirrors, navigation and HUD guidance on the instrument display, stock IVI split routing, and passenger-screen app installation. | **Active.** Version `0.3.0`; Compose landscape shell, self-recovery, one display resolver, and one shared cluster scene. Mirror cycles, selectable navigation layouts, HUD guidance, contextual stock split routing, and FSE installation have all been exercised on the author's car. Rapid left-to-right mirror switching remains unsafe. |
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
| `MainActivity`, `ui/DenzaAppsScreen` | Landscape-first Compose shell with three main cards (Navigation, Simulcast, Mirrors) and three compact cards (Split screen, HUD hints, passenger-screen installation). Technical diagnostics are hidden in Help. |
| `DenzaAppRepository`, `core/FeatureModels`, `DenzaRuntimeCoordinator` | Separate desired/observed feature state, short user-facing status, boot/package-update recovery, and detailed Help diagnostics. The hidden diagnostic view captures raw DiShare receivers, stock Simulcast receiver-card bounds, their usable intersection, and every Android display for N9 rear-screen investigation. |
| Compose app picker | Six-column grid of installed apps; tap to choose up to six for casting. Defaults to the installed subset of VK Video / Rutube / Kinopoisk / Yandex Navigator / VLC / YouTube. |
| `SimulcastApps` | Persists the chosen casting packages (prefs) and seeds defaults. |
| `SimulcastAccessibilityService`, `ScreenTarget` | Active visual path. Draws the selected app row and only accepts drop zones present in both the accessibility tree and runtime-available `DiShareScreens`; includes HUD, FSE, left/right RSE, overhead, and the single-rear `screen_tv` alias while keeping IVI as source. |
| `SimulcastDialogGeometry` | Reads live row and receiver geometry from the dialog's accessibility tree instead of assuming fixed HUD/FSE rectangles. |
| `SimulcastOverlayService` | Casting controller: launches the target through `dishare-bridge` at `2560x1440`, stops it, and shows the floating native exit control over the casting app. No longer draws the dialog overlay. |
| `SimulcastBootReceiver` | Forwards DiShare dialog actions and invokes runtime recovery after boot or APK replacement. |
| `feature.cluster` | Fail-closed cluster display resolver, real-display geometry, and the shared map-base/camera-overlay scene. No fallback display IDs. |
| `feature.hud` | Optional Yandex turn-by-turn bridge. Reads validated visible guidance across all accessibility displays and publishes maneuver, next-road, remaining route distance/time, and optional road text to the stock HUD SOME/IP road topic; unknown or stale guidance fails closed and clears the projection. |
| `feature.mirrors` | Migrated AVC renderer and window monitor. Uses the shared local ADB client, keeps verified Mirrors geometry/image treatment, and has no probe dependency. |
| `feature.navigation` | Public app-owned virtual display, fixed shell operations for task movement, an installed-app picker, and saved full/left/center/right placement. Projection, live layout switching, return, and HUD guidance while projected are live-car verified. Automatic following of the stock Map mode remains implemented but its unfinished UI control is hidden. |
| `feature.split` | Contextual two-step router for the stock BYD `byd-freeform` roots. Normal launches stay fullscreen; from the stock application picker, the first selected app fills the empty pane and the second replaces the picker through fixed local-ADB commands. |
| `feature.fse` | Lists suitable launcher apps from the IVI, copies a monolithic APK over the mounted FSE storage, sends the stock wallpaper installation request, and reports copy/install progress. Split APKs are shown but cannot be installed yet. |

### `libraries/dishare-bridge/`

| Component | Status |
| --- | --- |
| `DiShareProjectionBridge` | Active raw binder wrapper for DiShare API/control services. Casts are launched at `2560x1440` so the app renders at native resolution (not the old `1024x576`). |
| `DiShareScreens` | Screen-discovery wrapper for `getScreens` (available receivers). |
| `LocalAdbClient`, `AdbKeyStore` | Shared `adbd` shell client for app-side provisioning commands after the user authorizes the generated ADB key. Tries loopback first, then local non-loopback IPv4 addresses because some firmwares expose ADB on WLAN but not `127.0.0.1`. |

### `research/simulcast-aliases/` (deprecated)

| Component | Status |
| --- | --- |
| `launcher` flavors | DEPRECATED and parked. Belonged to the old FLAG_NOT_TOUCHABLE + alias-launch path. Dropped from `settings.gradle.kts`; the accessibility overlay replaces it. Only the `denza-apps` APK is required on a fresh car. |

## Current Product Direction

- `car-adb-gateway` is the active generic relay-only connectivity app. It must
  not grow a LAN mode.
- `denza-apps` is the single active Denza feature app. Simulcast, migrated camera
  rendering, navigation and HUD guidance, contextual stock split routing, and
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
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

Useful local APK paths:

```text
legacy/denza-gateway/build/outputs/apk/debug/denza-gateway.apk
apps/denza-apps/build/outputs/apk/debug/denza-apps.apk
apps/car-adb-gateway/build/outputs/apk/debug/car-adb-gateway.apk
```

Do not stage APK files. If a large APK appears in `git status`, fix `.gitignore`
first.
