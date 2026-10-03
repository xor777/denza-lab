# FSE HUD Inspector

Isolated ordinary-UID diagnostic APK for the Android 12 FSE. It answers the
architecture question in [hud-projection-findings.md](../../docs/hud-projection-findings.md#engineering-yandex-motion):
is this car using FSE's BydHud renderer, or FSE's video output to a separate
HUD controller, and which observed availability signal changes?

Version 0.1.0 was installed and read on the vehicle on 2026-09-24; installed APK hash
matched the pinned local build. Four system properties, package/file hashes,
display inventory and MediaStore-to-SMB export worked. All 13 BYDAuto getters
returned `SecurityException: [getInt] permission deny!` from app UID 10063.
The car has `sys.hud.direct.config=0`, no registered `com.byd.hud`, and an
`arhud` display at 1280×640 / 60 Hz. See the
[live evidence](../../docs/hud-projection-findings.md#fse-inspector-live).
Version 0.2.0 added an explicit local display test. Its exported installed hash
matched; `launch_check.allowed=true` and `activity_create` on display 2 `arhud`
were recorded, followed by a crash before any draw. Version 0.2.1 creates
window content before requesting its insets controller, and retains its own
Java crash and Android process-exit metadata. Sent to FSE 2026-09-24 17:11 MSK;
matching stock install response `-7` received. The repeated test confirmed the
installed hash, a focused 1280×640 View on arhud, 397 draws and frame callbacks,
and normal closure after 20 seconds. The owner saw **no change on the HUD**.
Independent Android rendering works; physical video-input selection remains
unresolved. It does not project navigation and has not been tested in D.
[Evidence](../../docs/hud-projection-findings.md#fse-local-probe).

Version **0.2.2 / code 5** adds a one-button read-only FSE access report.
Built and linted on 2026-09-24; FSE's stock installer confirmed the update at
about 18:20 MSK (`result=-7`, matching `res_id=924151925`). Local and staged
APK hashes match. The new access report, including the running APK's own
hash, is still pending. See
[the Cross input candidate and next read](../../docs/hud-projection-findings.md#hud-cross-input-candidate).

The 0.1.1 correction (included in 0.2.0) changes scalar speed to `Double.TYPE` / `doubleValue`,
as required by FSE `AbsBYDAutoDevice`, and recognizes the native error sentinels.
Version 0.1.1 was not installed. The old speed attempt is not a valid
test of the floating-point getter; its recorded `[getInt]` failure must not be
reported as a successful invocation of that getter. Historical reports remain unchanged.

The app requests **no permissions**, has no service or boot receiver, and
never sets a vehicle property, starts DiShare,
changes system properties or contacts the network. The only exported
component is its launcher Activity; Intent extras do not trigger collection.

## Use

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew -Pexperiments :fse-hud-inspector:assembleDebug :fse-hud-inspector:lintDebug
```

Artifact: `build/outputs/apk/debug/fse-hud-inspector.apk`.
Package: `dev.denza.fsehud.probe`, version `0.2.2` / code 5, min/target SDK 32.
Install on **FSE**, following [the existing FSE installation procedure](../../docs/fse-app-installation.md).
Installing on the IVI produces IVI observations and cannot answer the FSE
question; the JSON includes fingerprint, SDK and UID to distinguish them.

## Access report (0.2.2; next vehicle read)

Press **Доступ FSE → JSON** once. It reads fixed system properties, fingerprint,
UID, installed HAL/CrossService and diagnostic package hashes, metadata for
five diagnostic Activities, current permission grants and displays. It does
not invoke BYDAuto getters, open the diagnostic UI, enable ADB or start HUD.
Empty properties and file errors are reported as such; they are not proof of
absence or authority. Package visibility entries are queries, not permissions.

The report is saved internally as `access-last.json`, then immediately exported
to `Download/FseHudInspector/fse-access-<epoch-ms>.json`. **Повторить экспорт
доступа** exports the saved report again after an export failure. Neither action
launches the existing renderer.

Pinned build: `captures/fse-hud-access-20260924/fse-hud-inspector-0.2.2.apk`,
2,669,571 bytes, SHA-256
`7682c1764b32fedd7f5308d9004dfd485e0ccfbd5f0cea1405cbec6cc26c2447`.
This mode needs a first on-car report; build/lint and APK manifest inspection
do not establish that every system file is readable from the ordinary UID.

## Direct HUD test (0.2.1)

Open on FSE, remain parked in P, press **HUD 20 с**, then **Экспорт HUD**
after it ends. **Стоп HUD** ends it early. Merely opening the app does not
launch the renderer. Observe whether the grid and changing seconds appear
on the glass, whether the outer frame fits, and whether the stock picture
returns after the test. The first test does not request a gear change.

The resolver requires the FSE fingerprint and exactly one active display named
`arhud`, public and trusted. `ActivityManager.isActivityStartAllowedOnDisplay`
is checked before a non-exported `LocalHudActivity` starts in a separate task
affinity. Its actual display is checked before drawing and at every draw;
fallback to a different display aborts the test. No stock task is moved or killed.

The 20-second deadline is monotonic and is not restarted by Activity recreation.
The session closes its own task on timeout, Stop HUD, display loss/off, or
Activity stop/destroy. It does not restart. As with any Activity, a stalled
main looper may delay timer handling; this is not a hardware watchdog. If the
process dies, the Surface disappears and a recreated Activity cannot resume
the test without its in-memory session token.

The separate `local-hud-last.json` records launch eligibility, display inventory,
actual display, lifecycle/focus/display events, view size, draw calls and
`OnFrameMetricsAvailableListener` counts at one-second intervals. These are
Android-side observations, not proof of visibility on the glass. Slow vehicle
getters run on the original snapshot worker and do not hold the render session's
timer or file writer. No BYDAuto reads or writes are part of this direct test.

**Экспорт HUD** writes `Download/FseHudInspector/fse-local-hud-<epoch-ms>.json`.
It includes the exporting APK's version, size and SHA-256, its own process-exit
history, and any retained Java crash with its original version/time/PID.
`session_live_in_exporting_process` reports whether this process has an active
session; a persisted `active=true` alone does not prove a running session.
Reports can be
exported after reopening; a new test replaces only the previous local HUD
report. The original **Экспорт JSON** remains for architecture/state snapshots.
Export after completion to include the terminal lifecycle events.

No video-input command is sent. If Android renders on arhud but the glass is
unchanged, input selection remains unresolved; do not equate that result with
an Android launch failure. See [the route analysis](../../docs/hud-projection-findings.md#hud-independent-route).

## Architecture/state snapshot

1. Open **FSE HUD Inspector** on the passenger screen, while parked.
2. **Снимок** reads metadata and one state sample. **Запись 60 с** collects
   at most 60 samples, with a one-second delay after each completed sample.
   It stops scheduling after the 60-second observation window. Actual times
   are recorded; this is not a precise 1 Hz sampler.
3. **Стоп** or leaving the Activity cancels further sampling. A vendor Binder
   read already in progress may finish; the partial sample is marked
   `interrupted`. A stuck vendor call has no guaranteed cancellation deadline.
4. **Экспорт JSON** saves the current report into
   `Download/FseHudInspector/fse-hud-<epoch-ms>.json` using MediaStore, without
   a storage permission. Live-verified on IVI at
   `/storage/FFFF-FFFC/Download/FseHudInspector/`; verify each exported file
   before treating transfer as successful.

Each new capture replaces the app's previous internal report; export it first
if needed. Samples are saved atomically after collection. Reopening the app
loads the last saved report without reading the vehicle again. Export during
recording is a snapshot of the report at that moment. Export again after stop
for a complete session. Deleting the app removes the internal report;
exported files remain in Downloads.

## What the report contains

- PackageManager result for `com.byd.hud` and `com.byd.dishare`, version and
  SHA-256 of each readable base APK, plus the inspector's own installed APK
  identity. Separately checks and hashes
  `/system/app/BydHud/BydHud.apk`: a file in the image is not proof that the
  package passed the firmware's `sys.hud.direct.config` scan gate.
- Four fixed `getprop` reads: `sys.hud.direct.config`, `sys.piex.light.type`,
  `persist.dilink.hud.id`, `sys.dilink.hud.online`. Commands run as the app UID,
  without a shell; an empty result is `unknown`, not the value zero.
- Display inventory per sample: ID, name, flags, state, rotation, logical
  metrics, physical mode and refresh rate. Only displays visible to this UID
  are returned. This snapshot mode samples inventories; it does not register a
  display-event listener and may miss brief changes between samples.
- Thirteen fixed BYDAuto getter calls: access type, both pairs of HUD
  present/available, play state, HUD configuration/on, gear, speed and power.
  Uses the exact FIDs and classes found in the supplied FSE firmware through
  reflection. No vendor SDK binaries are packaged and no access is elevated.
- Wall-clock and monotonic times, including the start of each property read
  and the end of a sample. FSE and IVI clocks are not assumed synchronized.

Permission failures, absent classes and getter failures are evidence and are
saved verbatim as error type/message. Known invalid values (`65535`, vendor
invalid int/float sentinels, nonfinite floats) are marked invalid. Other
returned numbers remain **uninterpreted**; successful API return does not
establish freshness or semantic validity. There is no automatic architecture
verdict based on missing information.

The probe does not obtain system-wide logcat, SurfaceFlinger layers,
other apps' windows, private BydHud files, DiShare session callbacks or a HUD
screenshot. Those require a separate authorized access path or observation.
It does not start a drive or ask the driver to operate its UI in motion.
