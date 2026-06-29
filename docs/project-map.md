# Project Map

This repository is a small monorepo: a few Android product apps, one shared
library, plus research/tooling areas. Directory names match product names.

## Apps

| Path | APK / product | Purpose | Status |
| --- | --- | --- | --- |
| `denza-gateway/` | `denza-gateway` | SSH gateway from the car LAN to local ADB endpoints on the head unit. | Product app. Keep changes conservative and test with unit tests. |
| `denza-mirrors/` | `denza-mirrors` | Driver-display side-camera enlargement for turn-signal camera windows. | Prototype/product app. Product code lives in `dev.denza.mirrors`; research probes are isolated in `dev.denza.mirrors.probe`. |
| `denza-apps/` | `denza-apps` | Head app for Denza features. Adds Russian apps to the native Simulcast App Change row via an accessibility overlay, with a configurable app picker. | Prototype app. Self-contained: only this APK + overlay permission + enabling its accessibility service are needed on any car. |

## Shared Android Modules

| Path | Purpose | Rules |
| --- | --- | --- |
| `dishare-bridge/` | Raw DiShare binder bridge used by `denza-apps` for screen discovery and starting/stopping shares. | Keep API notes in `docs/dishare-api-notes.md` aligned with transaction behavior. This is the only place product apps may share car-access code. |

## Supporting Areas

| Path | Purpose | Rules |
| --- | --- | --- |
| `docs/` | Stable project knowledge, decisions, and investigation summaries. | Update when behavior, commands, or known limitations change. |
| `tools/` | Host-side scripts for one-off live experiments. | Scripts are not production paths until promoted through `docs/governance.md`. |
| `research/` | Code that is not built into product APKs (parked experiments, deprecated modules). | Keep failed or permission-blocked probes here, not in app source. Includes `research/simulcast-aliases/` (deprecated) and `research/vehicle-events/` (parked probe). |
| `reverse/` | Local reverse-engineering input/output, often large. | APKs and extracted binaries must stay untracked. |

## Source Of Truth

Use the code layout as the source of truth for current behavior:

- Gradle modules live in `settings.gradle.kts`.
- App ids, exported components, and product/probe grouping live in each
  `AndroidManifest.xml`.
- Package boundaries (`dev.denza.mirrors` vs `dev.denza.mirrors.probe`) define
  product vs on-device research code.
- Docs explain direction and durable findings. If docs disagree with code, fix
  the closest existing doc instead of adding another status file.

`reverse/` is an untracked local workbench. Keep raw APKs, JADX outputs, captures,
and extracted binaries there; move only distilled, reusable conclusions into the
nearest existing doc.

## Where experiments live

On-car experiments ("poking the car") have a defined home so they don't leak
into product code:

- **Host-side probes** → `tools/` (shell/python scripts run from the laptop).
- **On-device probes for an existing product's domain** → a `…​.probe`
  subpackage of that product module (today: `dev.denza.mirrors.probe`).
- **Parked / non-built code** → `research/<topic>/` with a README explaining why
  it is not built.

Rule of thumb: product code must not depend on `…​.probe` code. There is one
known violation today — `SideCameraOverlayMonitorService` (product) drives the
experimental `HudDiShareActivity` HUD path. It is deliberately left visible (a
cross-package import) as a cleanup TODO. Resolve that coupling before extracting
probes into their own module.

When poking expands to a genuinely different area (not camera/DiShare), promote
the relevant probes into a dedicated experiment module rather than overloading
`denza-mirrors`.

## Component Inventory

### `denza-gateway/` (`denza-gateway`)

| Component | Status |
| --- | --- |
| `MainActivity`, `GatewayService`, `SshGatewayServer` | Product path for LAN SSH forwarding to local ADB. |
| `AdbProbe`, `ProbePlan`, `ForwardingPolicy` | Product support code with unit tests. |

### `denza-mirrors/` (`denza-mirrors`)

Product package `dev.denza.mirrors`:

| Component | Status |
| --- | --- |
| `MainActivity` | Product UI for Denza Mirrors (was `ProjectionProbeActivity`). |
| `SideCameraOverlayMonitorService`, `SideCameraBootReceiver` | Active dashboard camera monitor path. |
| `AvcAidlDashActivity` | Active dashboard AVC display path. |
| `LocalAdbClient`, `AdbKeyStore` | Required support for local ADB commands from the app. |

Research package `dev.denza.mirrors.probe` (not product; promote before relying):

| Component | Status |
| --- | --- |
| `HudDiShareActivity`, `HudImageActivity`, `DiShareProbeActivity`, `DiShareProbeReceiver`, `MediaStreamProbeActivity`, `HudSomeIpProbeActivity` | DiShare/HUD research probes. |
| `AvcSurfaceClient`, `CameraStreamSource`, `CameraGlStreamSource`, `BydMediaStreamServer` | Probe support code for the HUD/media-stream experiments. |
| `AvcTurnSignalMonitorService`, `AvcTurnSignalMonitorActivity` | Legacy direct BYD light API probe. Permission-blocked in normal app tests; not a production trigger. |
| `AvcPipHookActivity`, `DashCameraActivity`, `DashPresentationActivity`, `ProjectionTargetActivity`, `ProjectionCommand*`, map demo activities | Historical probes/demos. Confirm live value before editing or invoking. |

### `denza-apps/`

| Component | Status |
| --- | --- |
| `MainActivity` | Start/Stop control plus "Выбрать приложения", overlay-permission and accessibility-enable buttons. |
| `AppPickerActivity` | App selection UI — a horizontal slider of all installed apps; tap to mark up to 6 for casting. Defaults to installed-subset of VK Video / Rutube / Kinopoisk / Yandex Navi / VLC / YouTube. |
| `SimulcastApps` | Persists the chosen casting packages (prefs) and seeds defaults. |
| `SimulcastAccessibilityService` | Active visual path. Watches the native DiShare `ShareDialogActivity` via accessibility, reads live node bounds, and erases+redraws the App Change row + central preview with the chosen Russian apps; drag → launch through `dishare-bridge`. |
| `SimulcastDialogGeometry` | Reads live geometry (row container, screen cards, App Change button) from the dialog's accessibility node tree. |
| `SimulcastOverlayService` | Casting controller: launches the target through `dishare-bridge` at `2560x1440`, stops it, and shows the floating native exit control over the casting app. No longer draws the dialog overlay. |
| `SimulcastBootReceiver` | Forwards DiShare dialog broadcasts (to sync the exit control) and debug START/STOP actions. |

### `dishare-bridge/`

| Component | Status |
| --- | --- |
| `DiShareProjectionBridge` | Active raw binder wrapper for DiShare API/control services. Casts are launched at `2560x1440` so the app renders at native resolution (not the old `1024x576`). |
| `DiShareScreens` | Screen-discovery wrapper for `getScreens` (available receivers). |

### `research/simulcast-aliases/` (deprecated)

| Component | Status |
| --- | --- |
| `launcher` flavors | DEPRECATED and parked. Belonged to the old FLAG_NOT_TOUCHABLE + alias-launch path. Dropped from `settings.gradle.kts`; the accessibility overlay replaces it. Only the `denza-apps` APK is required on a fresh car. |

## Current Product Direction

- `denza-gateway` is the connectivity app. It should not contain camera or HUD
  experiments.
- `denza-mirrors` is the camera app. The supported path is dashboard/instrument
  display enlargement via the AVC AIDL dashboard overlay. Experimental code stays
  in `dev.denza.mirrors.probe`.
- `denza-apps` is the head app for miscellaneous car features. The working path is
  Simulcast for Russian video apps via an accessibility overlay that redraws the
  native App Change row with the user's chosen apps.
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

## Build Outputs

Generated APKs are intentionally ignored by Git.

```bash
./gradlew :denza-gateway:assembleDebug
./gradlew :denza-mirrors:assembleDebug
./gradlew :denza-apps:assembleDebug
```

Useful local APK paths:

```text
denza-gateway/build/outputs/apk/debug/denza-gateway.apk
denza-mirrors/build/outputs/apk/debug/denza-mirrors.apk
denza-apps/build/outputs/apk/debug/denza-apps.apk
```

Do not stage APK files. If a large APK appears in `git status`, fix `.gitignore`
first.
