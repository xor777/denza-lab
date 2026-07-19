# Repository Governance

This file is the lightweight operating manual for future work in this repo.

## Lifecycle and Change Lanes

Use one of these lanes before editing code:

| Lane | Allowed paths | Rule |
| --- | --- | --- |
| Active product | `apps/car-adb-gateway/`, `apps/denza-apps/`, `libraries/dishare-bridge/`, `platform/cli/`, `platform/relay/`, `ops/` | Build/test the affected path, verify hardware-dependent behavior on the car, and update the closest doc. |
| Legacy | `legacy/denza-gateway/`, `legacy/denza-mirrors/` | Maintenance, frozen reference, and safe-retirement work only. Do not add features or create new dependencies on legacy code. |
| Prototype | Experimental features in `apps/denza-apps/`, dedicated probe modules, `tools/` | Isolate behind flags, settings, or explicit commands. Document the live-test result. |
| Research | `research/`, `docs/*notes*`, host-only scripts | Must not be compiled into product APKs unless promoted. |

If an experiment fails, keep the finding in docs or `research/`; do not leave dead
services, manifest entries, or permissions in the product app.

## Where experiments live

"Poking the car" must not leak into product code. Pick the right home:

- **Host-side probes** → `tools/` (shell/python run from the laptop).
- **On-device probes for an existing product's domain** → a deliberately
  isolated `…​.probe` subpackage or dedicated experiment module. The old
  `dev.denza.mirrors.probe` package is frozen with the legacy app.
- **Parked / non-built code** → `research/<topic>/` with a README.

Rules:

- Active product code must not depend on probe or legacy packages. The frozen
  Denza Mirrors source retains one historical exception —
  `SideCameraOverlayMonitorService` drives `HudDiShareActivity` — but no active
  module depends on that source.
- When poking expands to a genuinely different area (not camera/DiShare), create a
  dedicated experiment module instead of overloading `denza-mirrors`. Extract any
  helper shared with a product app into a library module (e.g. alongside
  `dishare-bridge`).

## Knowledge Rules

Record durable knowledge in the repo, not only in chat.

Code is the source of truth for current structure and behavior. Keep docs as
navigation and evidence, not as a second implementation model:

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

- Treat `com.byd.avc` crashes as escalation alerts, not automatic hard stops.
  Capture `logcat -b crash -v time`, document the trigger, and notify the user
  briefly. Continue safe in-scope work, but do not repeat the suspected trigger
  until the change is reverted or isolated.
- Keep the last known working APK behavior easy to restore before trying a risky
  experiment.
- Prefer host-side scripts in `tools/` for speculative probes before adding code
  to the Android app.
- Do not add BYD/system permissions to `AndroidManifest.xml` unless the car has
  proven they are granted to this APK.
- Do not commit generated APKs, reverse-engineered APKs, or large extracted
  binaries.

## DiShare/Simulcast Rules

- Denza Apps has an independent **Apps** feature card, not a global Start/Stop.
  Its accessibility layer draws selected installed apps over the native App
  Change row, and drops are translated to `DiShareProjectionBridge` starts.
- Runtime receiver support must come from `DiShareScreens.getScreens`. Do not
  hard-code a rear/HUD/passenger screen as usable just because its coordinates
  are known in reverse-engineered resources. A drop target must also be visible
  in the current accessibility tree; `screen_ivi` remains the source.
- Debug service/broadcast actions are allowed for verification only:
  `dev.denza.apps.START_SIMULCAST_TARGET` and
  `dev.denza.apps.STOP_SIMULCAST_TARGET`. Product UX should remain the normal
  Simulcast drag flow unless deliberately redesigned.
- Native App Change metadata remains research. Any `videoList`/proxy/cloud-cache
  experiment must be run from `tools/dishare_native_metadata_probe.py`, must
  document setup and revert commands, and must not be baked into the APK until it
  is verified on the car without breaking normal network or DiShare behavior.
- If an ADB/SSH tunnel drops during a live test, mark runtime evidence as
  inconclusive. Reconnect and repeat the exact test before updating the verified
  behavior section.

## Instrument Display Rules

- Resolve the cluster with `ClusterDisplayResolver`; never restore unconditional
  display IDs such as `2` or `4`. Ambiguity is a Support action, not permission to
  guess.
- Navigation owns the full-size base surface. Side cameras are overlays and must
  not resize, restart, or duplicate the Yandex task.
- Navigation shell commands are internal, fixed, short-lived operations. Keep
  the package allowlist, never pass app-owned Binder objects across processes,
  and never expose arbitrary commands to the UI.
- Navigation is memory-only and must not start after boot. When a command,
  display, or task disappears, release the virtual display and prefer returning
  the task to display `0`.
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
- Toggling the feature must not launch an app. Turning it off must return routed
  tasks to the current fullscreen root and restore the stock pane anchors.

## Git Hygiene

- Keep unrelated product changes and research changes in separate commits.
- If code is intentionally parked for later, put it under `research/` and document it.
- If a feature is not working, mark it as blocked or experimental in docs before pushing.
- Run at least the relevant Gradle build before publishing code changes:

```bash
./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-apps:testDebugUnitTest :denza-apps:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```
