# Repository Governance

This file is the lightweight operating manual for future work in this repo.

## Lifecycle and Change Lanes

Use one of these lanes before editing code:

| Lane | Allowed paths | Rule |
| --- | --- | --- |
| Active product | `apps/car-adb-gateway/`, `apps/denza-apps/`, `libraries/dishare-bridge/`, `platform/cli/`, `platform/relay/`, `ops/` | Build/test the affected path, verify hardware-dependent behavior on the car, and update the closest doc. |
| Migration | Production parts of `apps/denza-mirrors/` and corresponding new code in `apps/denza-apps/` | Preserve verified behavior while moving it behind a Denza Apps feature boundary. Avoid unrelated standalone Mirrors features. |
| Legacy | `legacy/denza-gateway/`; later, the retired Denza Mirrors app | Maintenance and safe-retirement work only. Do not add features or create new dependencies on legacy code. |
| Prototype | `apps/denza-mirrors/` probe package (`dev.denza.mirrors.probe`), experimental features in `apps/denza-apps/`, `tools/` | Isolate behind flags, settings, or explicit commands. Document the live-test result. |
| Research | `research/`, `docs/*notes*`, host-only scripts | Must not be compiled into product APKs unless promoted. |

If an experiment fails, keep the finding in docs or `research/`; do not leave dead
services, manifest entries, or permissions in the product app.

## Where experiments live

"Poking the car" must not leak into product code. Pick the right home:

- **Host-side probes** → `tools/` (shell/python run from the laptop).
- **On-device probes for an existing product's domain** → a `…​.probe` subpackage
  of that product module. Today that is `dev.denza.mirrors.probe`; its components
  are grouped under an EXPERIMENTAL section in the manifest.
- **Parked / non-built code** → `research/<topic>/` with a README.

Rules:

- Product code (`dev.denza.mirrors`) must not depend on `dev.denza.mirrors.probe`.
  The one current exception — `SideCameraOverlayMonitorService` driving
  `HudDiShareActivity` — is left as a visible cross-package import and a cleanup
  TODO; resolve it before extracting probes into a separate module.
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
- Camera/turn-signal findings: update `docs/side-camera-findings.md`.
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

- Treat `com.byd.avc` crashes as a hard stop. Capture `logcat -b crash -v time`,
  document the trigger, then revert or isolate the change.
- Keep the last known working APK behavior easy to restore before trying a risky
  experiment.
- Prefer host-side scripts in `tools/` for speculative probes before adding code
  to the Android app.
- Do not add BYD/system permissions to `AndroidManifest.xml` unless the car has
  proven they are granted to this APK.
- Do not commit generated APKs, reverse-engineered APKs, or large extracted
  binaries.

## DiShare/Simulcast Rules

- The current product candidate is `denza-apps` + `SimulcastOverlayService`:
  Denza Apps has one Start/Stop button, then the monitor waits for Simulcast.
  It does not draw immediately. When the user presses the native App Change
  button, a touchable Russian app row is drawn with real installed app icons,
  and drops are translated to `DiShareProjectionBridge` receiver starts.
- Runtime receiver support must come from `DiShareScreens.getScreens`. Do not
  hard-code a rear/HUD/passenger screen as usable just because its coordinates
  are known in reverse-engineered resources.
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

## Git Hygiene

- Keep unrelated product changes and research changes in separate commits.
- If code is intentionally parked for later, put it under `research/` and document it.
- If a feature is not working, mark it as blocked or experimental in docs before pushing.
- Run at least the relevant Gradle build before publishing code changes:

```bash
./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-mirrors:assembleDebug
./gradlew :denza-apps:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```
