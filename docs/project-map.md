# Project Map

This repository is a small monorepo: Android apps at different lifecycle stages,
one shared library, remote-access infrastructure, and research/tooling areas.
The repository identity is **Denza Lab**; the historical GitHub/local directory
name may still be `denza-gateway` during the rename.

## Apps

| Path | APK / product | Purpose | Status |
| --- | --- | --- | --- |
| `legacy/denza-gateway/` | `denza-gateway` | SSH gateway from the car LAN to local ADB endpoints on the head unit. | **Legacy.** Maintenance-only; do not add features. Car ADB Gateway supersedes it for new remote-access work. |
| `apps/denza-mirrors/` | `denza-mirrors` | Driver-display side-camera enlargement for turn-signal camera windows. | **Transition.** Source for the camera migration into Denza Apps. Product code lives in `dev.denza.mirrors`; research probes are isolated in `dev.denza.mirrors.probe`. |
| `apps/denza-apps/` | `denza-apps` | Consolidated head-unit app for Denza features. Adds supported apps to the native Simulcast App Change row and will absorb Denza Mirrors behavior. | **Active.** Self-contained: the APK performs its local ADB setup after key authorization, grants the overlay app-op, and enables its accessibility service. |
| `apps/car-adb-gateway/` | `car-adb-gateway` | Generic relay-only remote ADB gateway. Fixed `adbgw.ru`, one trusted computer, background recovery, no LAN listener. | Product candidate. Local unit/build evidence exists; relay deployment, live-head-unit E2E, API matrix, and soak remain required. |

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
| `platform/relay/cag_state.py` + wrappers | Atomic state, expiring codes, source lockout, device enrollment, pending/commit replacement, dynamic restricted keys. Provisioning not yet deployed. |
| `platform/cli/cmd/cag` | Go client for `pair`, `connect`, ADB execution, `status`, and `disconnect`; Darwin/Linux builds verified locally. |

### `apps/denza-mirrors/` (`denza-mirrors`)

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

### `apps/denza-apps/`

| Component | Status |
| --- | --- |
| `MainActivity` | Start/Stop control plus the app picker; in-app local-ADB setup grants the overlay app-op and enables the Simulcast accessibility service. |
| `AppPickerActivity` | App selection UI — a horizontal slider of all installed apps; tap to mark up to 6 for casting. Defaults to installed-subset of VK Video / Rutube / Kinopoisk / Yandex Navi / VLC / YouTube. |
| `SimulcastApps` | Persists the chosen casting packages (prefs) and seeds defaults. |
| `SimulcastAccessibilityService` | Active visual path. Watches the native DiShare `ShareDialogActivity` via accessibility, reads live node bounds, and erases+redraws the App Change row + central preview with the chosen Russian apps; drag → launch through `dishare-bridge`. |
| `SimulcastDialogGeometry` | Reads live geometry (row container, screen cards, App Change button) from the dialog's accessibility node tree. |
| `SimulcastOverlayService` | Casting controller: launches the target through `dishare-bridge` at `2560x1440`, stops it, and shows the floating native exit control over the casting app. No longer draws the dialog overlay. |
| `SimulcastBootReceiver` | Forwards DiShare dialog broadcasts (to sync the exit control) and debug START/STOP actions. |

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
- `denza-apps` is the single active Denza feature app. Its working Simulcast path
  uses an accessibility overlay that redraws the native App Change row with the
  user's chosen apps. Supported camera behavior should move here behind a clear
  feature boundary.
- `denza-mirrors` is in transition. Use it as the verified source for the camera
  migration and for fixes needed to complete that migration; do not grow it as a
  separate product.
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

## Planned Directory Cleanup

Keep Denza Mirrors under `apps/` while it is being merged into Denza Apps. Its
Gradle module name stays stable until the migration is verified.

After the migration has been verified on a real head unit:

1. move the supported camera behavior into Denza Apps;
2. verify the consolidated app on a real head unit;
3. remove Denza Mirrors from the default Gradle build;
4. move its frozen source under `legacy/`.

## Build Outputs

Generated APKs are intentionally ignored by Git.

```bash
./gradlew :denza-gateway:assembleDebug
./gradlew :denza-mirrors:assembleDebug
./gradlew :denza-apps:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

Useful local APK paths:

```text
legacy/denza-gateway/build/outputs/apk/debug/denza-gateway.apk
apps/denza-mirrors/build/outputs/apk/debug/denza-mirrors.apk
apps/denza-apps/build/outputs/apk/debug/denza-apps.apk
apps/car-adb-gateway/build/outputs/apk/debug/car-adb-gateway.apk
```

Do not stage APK files. If a large APK appears in `git status`, fix `.gitignore`
first.
