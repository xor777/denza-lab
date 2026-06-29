# Project Map

This repository has several Android apps/modules and a small research/tooling area.

## Apps

| Path | APK | Purpose | Status |
| --- | --- | --- | --- |
| `app/` | `denza-gateway` | SSH gateway from the car LAN to local ADB endpoints on the head unit. | Product app. Keep changes conservative and test with unit tests. |
| `projection-probe/` | `denza-mirrors` | Driver-display side-camera enlargement for turn-signal camera windows. Also still contains older probe activities. | Prototype/product app. Working dashboard path is active; HUD and low-level vehicle APIs remain research. |
| `denza-apps/` | `denza-apps` | Head app for Denza features. Adds Russian apps to the native Simulcast App Change row via an accessibility overlay, with a configurable app picker. | Prototype app. Self-contained: only this APK + overlay permission + enabling its accessibility service are needed on any car. |

## Shared Android Modules

| Path | Purpose | Rules |
| --- | --- | --- |
| `dishare-bridge/` | Raw DiShare binder bridge used by `denza-apps` for screen discovery and starting/stopping shares. | Keep API notes in `docs/dishare-api-notes.md` aligned with transaction behavior. |
| `simulcast-aliases/` | DEPRECATED helper APKs from the old alias-launch path. No longer built or required (dropped from `settings.gradle.kts`); kept on disk for reference only. | Do not depend on these; the accessibility overlay replaces them. |

## Supporting Areas

| Path | Purpose | Rules |
| --- | --- | --- |
| `docs/` | Stable project knowledge, decisions, and investigation summaries. | Update when behavior, commands, or known limitations change. |
| `research/` | Code that is not built into product APKs. | Keep failed or permission-blocked probes here, not in app source. |
| `tools/` | Host-side scripts for one-off live experiments. | Scripts are not production paths until promoted through `docs/governance.md`. |
| `reverse/` | Local reverse-engineering input/output, often large. | APKs and extracted binaries must stay untracked. |

## Component Inventory

### `app/` (`denza-gateway`)

| Component | Status |
| --- | --- |
| `MainActivity`, `GatewayService`, `SshGatewayServer` | Product path for LAN SSH forwarding to local ADB. |
| `AdbProbe`, `ProbePlan`, `ForwardingPolicy` | Product support code with unit tests. |

### `projection-probe/` (`denza-mirrors`)

| Component | Status |
| --- | --- |
| `ProjectionProbeActivity` | Product/prototype UI for Denza Mirrors. |
| `SideCameraOverlayMonitorService`, `SideCameraBootReceiver` | Active dashboard camera monitor path. |
| `AvcAidlDashActivity` | Active dashboard AVC display path. |
| `LocalAdbClient`, `AdbKeyStore` | Required support for local ADB commands from the app. |
| `HudDiShareActivity`, `HudImageActivity`, `DiShareProbeActivity`, `MediaStreamProbeActivity`, `HudSomeIpProbeActivity` | Research/probe components. Do not treat as product without promotion. |
| `AvcTurnSignalMonitorService`, `AvcTurnSignalMonitorActivity` | Legacy direct BYD light API probe. Permission-blocked in normal app tests; not a production trigger. |
| `AvcPipHookActivity`, `DashCameraActivity`, `DashPresentationActivity`, map demo activities | Historical probes/demos. Confirm live value before editing or invoking. |

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

### `simulcast-aliases/` (deprecated)

| Component | Status |
| --- | --- |
| `launcher` flavors | DEPRECATED. Belonged to the old FLAG_NOT_TOUCHABLE + alias-launch path. The accessibility overlay no longer needs them (the native row comes from DiShare's own cloud metadata), so the module is dropped from `settings.gradle.kts`. Only the `denza-apps` APK is required on a fresh car. |

## Current Product Direction

- `denza-gateway` is the connectivity app. It should not contain camera or HUD experiments.
- `denza-mirrors` is the camera app. The supported path is dashboard/instrument display enlargement via the AVC AIDL dashboard overlay.
- `denza-apps` is the head app for miscellaneous car features. The working feature
  path is Simulcast for Russian video apps via an accessibility overlay that
  redraws the native App Change row with the user's chosen apps.
- For Simulcast, normal app uid is enough for direct DiShare launches. The native
  `ShareApp` visual metadata is solved at the UI layer: the accessibility overlay
  erases the stock row and paints the chosen apps over it (no metadata injection,
  no helper APKs). The old alias/`SourceKeeperService` path is removed.
- HUD camera output is not a supported product path from a normal debug APK. DiShare can show generated frames and some app-accessible Camera2 feeds, but protected side/AVC feeds remain blocked.
- Vehicle event APIs are research-only for now. Normal app uid access to direct BYD getters/listeners was permission-blocked or did not deliver useful callbacks.

## Build Outputs

Generated APKs are intentionally ignored by Git.

```bash
./gradlew :app:assembleDebug
./gradlew :projection-probe:assembleDebug
./gradlew :denza-apps:assembleDebug
```

Useful local APK paths:

```text
app/build/outputs/apk/debug/app-debug.apk
projection-probe/build/outputs/apk/debug/denza-mirrors.apk
denza-apps/build/outputs/apk/debug/denza-apps.apk
```

Do not stage APK files. If a large APK appears in `git status`, fix `.gitignore` first.
