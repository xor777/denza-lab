# Repository Governance

This page explains where a change belongs and what evidence it needs.

## Lifecycle and Change Lanes

Choose the lane that matches the change:

| Lane | Allowed paths | Rule |
| --- | --- | --- |
| Active product | `apps/car-adb-gateway/`, `apps/denza-apps/`, `libraries/dishare-bridge/`, `platform/cli/`, `platform/relay/`, `ops/` | Build/test the affected path, verify hardware-dependent behavior on the car, and update the closest doc. |
| Legacy | `legacy/denza-gateway/`, `legacy/denza-mirrors/` | Maintenance, frozen reference, and safe-retirement work only. Do not add features or create new dependencies on legacy code. |
| Prototype | Experimental features in `apps/denza-apps/`, dedicated probe modules, `tools/` | Isolate behind flags, settings, or explicit commands. Document the live-test result. |
| Research | `research/`, reverse-engineering notes, host-only scripts | Keep it outside product APKs until it has passed the promotion checklist. |

If an experiment fails, keep the finding in docs or `research/`; do not leave dead
services, manifest entries, or permissions in the product app.

## Where experiments live

Put experiments where another contributor will expect to find them:

- **Host-side probes** → `tools/` (shell/python run from the laptop).
- **On-device probes for an existing product's domain** → an isolated
  `…​.probe` subpackage or dedicated experiment module. The old
  `dev.denza.mirrors.probe` package is frozen with the legacy app.
- **Parked / non-built code** → `research/<topic>/` with a README.

Rules:

- Keep probe and legacy packages out of active product dependencies. The frozen
  Denza Mirrors source retains one historical exception —
  `SideCameraOverlayMonitorService` drives `HudDiShareActivity` — but no active
  module depends on that source.
- When poking expands to a genuinely different area (not camera/DiShare), create a
  dedicated experiment module instead of overloading `denza-mirrors`. Extract any
  helper shared with a product app into a library module (e.g. alongside
  `dishare-bridge`).

## Knowledge Rules

Record durable knowledge in the repo, not only in chat.

Use docs for navigation and field evidence. If they disagree with current
structure or behavior, check the code and fix the affected page:

- Product behavior and user-facing workflows: update `README.md` or a focused doc
  under `docs/`.
- Reverse-engineering findings: update `docs/*notes*.md`.
- Instrument-display, camera, and navigation findings: update
  `docs/instrument-display-findings.md`.
- Research code that may be useful later: move it under `research/<topic>/` with a
  README explaining why it is not built.
- One-off host scripts: keep under `tools/` and state whether they are production
  candidates or probes.
- Prefer updating the closest existing doc over creating a new `.md`. Create a
  new doc only when the topic has a durable owner and would otherwise make an
  existing file hard to scan.

Every durable note should include:

- date or firmware context when known;
- exact working command, component, or API name;
- result: working, blocked, flaky, or unknown;
- next action or reason to stop.

## Promotion Checklist

Before moving research/prototype code into a product APK (or out of a `…​.probe`
package into product code):

- The code path has been tested on the car in the target scenario.
- The failure mode is understood and documented.
- Required permissions are available to a normal `/data/app` APK, or the
  limitation is explicit.
- The feature can be disabled from the UI or by stopping the service.
- It does not crash or restart stock components such as `com.byd.avc`.
- The README or relevant doc says how to build, install, start, stop, and
  diagnose it.

## Live Car Debugging Rules

- Treat a `com.byd.avc` crash as an escalation alert. Capture
  `logcat -b crash -v time`, document the trigger, and tell the user once.
  Continue safe in-scope work, but avoid the suspected trigger until the change
  is reverted or isolated.
- Keep the last known working APK behavior easy to restore before trying a risky
  experiment.
- Prefer host-side scripts in `tools/` for speculative probes before adding code
  to the Android app.
- Do not add BYD/system permissions to `AndroidManifest.xml` unless the car has
  proven they are granted to this APK.
- Do not commit generated APKs, reverse-engineered APKs, or large extracted
  binaries.

## DiShare/Simulcast Rules

- Denza Apps exposes Simulcast through the **Трансляция** card. Its
  accessibility layer draws the chosen installed apps over the native App
  Change row, then translates a drop into a `DiShareProjectionBridge` start.
- Runtime receiver support must come from `DiShareScreens.getScreens`. Do not
  hard-code a rear/HUD/passenger screen as usable just because its coordinates
  are known in reverse-engineered resources. A drop target must also be visible
  in the current accessibility tree; `screen_ivi` remains the source.
- Debug service/broadcast actions are allowed for verification only:
  `dev.denza.apps.START_SIMULCAST_TARGET` and
  `dev.denza.apps.STOP_SIMULCAST_TARGET`. The normal user flow is the stock
  Simulcast screen with the Denza Apps drag layer.
- Native App Change metadata remains research. Run `videoList`, proxy, and cloud
  cache experiments from `tools/dishare_native_metadata_probe.py`, record setup
  and rollback commands, and keep them out of the APK until the normal network
  and DiShare flows pass on the car.
- If an ADB/SSH tunnel drops during a live test, mark runtime evidence as
  inconclusive. Reconnect and repeat the exact test before updating the verified
  behavior section.

## Instrument Display Rules

- Resolve the cluster with `ClusterDisplayResolver`; never restore unconditional
  display IDs such as `2` or `4`. If the result is ambiguous, show it in
  diagnostics and leave the feature unavailable.
- Navigation owns the full-size base surface. Side cameras are overlays and must
  not resize, restart, or duplicate the Yandex task.
- Navigation shell commands are internal, fixed, short-lived operations. Keep
  the package allowlist, never pass app-owned Binder objects across processes,
  and never expose arbitrary commands to the UI.
- Navigation sessions stay in memory and never resume after boot. When a
  command, display, or task disappears, release the virtual display and return
  the task to display `0` when possible.
- Do not run an installed legacy Denza Mirrors monitor and the Denza Apps monitor
  together. Denza Mirrors is excluded from the root Gradle build after accepted
  live-car checks and an explicit retirement decision.

## IVI Split-Screen Rules

- Use the stock BYD split roots and divider; do not draw a replacement split UI
  over the central screen.
- Resolve panes from the `com.android.launcher3` and `com.byd.launchermap`
  anchors and their live bounds. Root/task IDs are observations, never constants.
- A normal launch outside the visible stock split scene must remain fullscreen.
  Route only the immediate launch context that originated from the stock split
  scene.
- Treat the visible stock application picker as a short two-step session. Route
  only the foreground task created or resumed by the immediate picker action:
  first to the empty anchored pane, then to the pane that contained the picker.
  Do not use app package names to choose a pane.
- Explicit Denza Apps task operations are outside that session. Navigation
  projection/return and Simulcast start/stop must atomically cancel any pending
  picker selection and hold split routing until their task changes settle.
- Split commands remain fixed `am stack move-task` / `am task resize`
  operations through the shared local ADB client; do not expose arbitrary shell
  text to the UI. Exclude Denza Apps and the stock picker/pane packages from
  candidate routing.
- The toggle changes routing only. Turning it off returns routed tasks to the
  current fullscreen root and restores the stock pane anchors.

## Git Hygiene

- Keep unrelated product changes and research changes in separate commits.
- Put code parked for later under `research/` and document it.
- If a feature is not working, mark it as blocked or experimental in docs before pushing.
- Run at least the relevant Gradle build before publishing code changes:

```bash
./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-apps:testDebugUnitTest :denza-apps:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```
