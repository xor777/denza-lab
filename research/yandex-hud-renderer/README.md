# Yandex HUD renderer experiment

An independent HUD map **inside the running Navigator process**, using its
existing MapKit and current Guidance. Full navigation stays in the original
main-screen Activity. This is a working emulator experiment, not a standalone
companion APK, a modified Navigator release, or a car-tested feature.

Findings and access boundaries live in
[HUD projection findings](../../docs/hud-projection-findings.md#hud-yandex-independent-renderer).
The branch starts from `d6253bae`, `experiment/yandex-hud-renderer`.

## What it renders

`renderer.js` creates a 300×180 offscreen MapKit window for the active route.
The primary Navigator map keeps its original UI and camera. The HUD contains
only roads, a golden cursor, the traffic-colored remaining route and a compact
blue lane sign at the upper right. **No maneuver icon, distance or reserved
header.** This is the owner's corrected design, superseding the first cyan
route / white cursor / maneuver-header draft.

`hud-scene.js` is shared by the browser stand and `android-painter.js`:

- Traffic color families come from the pinned APK's
  `mapkit_styling_automotive_jam_*` resources: FREE `#82EA0E`, LIGHT `#FFFF41`,
  HARD `#FF5413`, VERY_HARD `#932100`, UNKNOWN `#A0A0A0`, BLOCKED `#1A1A1A`.
  The HUD raises HSL lightness, preserving hue and saturation. Very hard traffic
  stays darker than hard traffic; unknown traffic never turns green.
- `DrivingRoute.getJamSegments()` supplies a class for each original geometry
  segment. Cache refresh is at most one second; route changes and outdated
  conditions invalidate immediately. A length mismatch or outdated conditions
  produces UNKNOWN, never fabricated free flow. Palette indices are reapplied
  after every `setGeometry`, which resets them.
- The cursor is Navigator's `notification_arrow` drawable, rotated north and
  brightened ×1.12. This is a stock 2D cursor resource, not the main map's 3D
  cursor model. Lane glyphs are its `context_lane_*_large` drawables, with white
  recommended directions and 40% alpha secondary directions, on the slightly
  brightened `balloon_new_blue` color. These are native resource components
  assembled in our corner badge, **not a captured/cropped on-screen balloon**.
- The lane badge is 36 px high, at most 126 px wide, 6 px from the top/right;
  it overlays the map. Unsupported lane kinds/directions and signs exceeding six
  lanes are omitted. The experimental adapter currently supports plain lanes.
- Neutral gray roads are brightened; structures, labels, POI, landscape, public
  transport, pedestrian paths, markings and crosswalks are hidden. The actual
  MapKit tag is `road_minor` (the old `road.minor` rule did nothing).
- Focus is (150,143); heading follows guidance, zoom 14.6–16.7 uses the upcoming
  maneuver distance internally to choose context. No maneuver is drawn.
- Black is optically transparent in the owner's HUD slot, **not PNG alpha**.
  Brightness, especially the blue patch, still needs calibration on the glass.

The renderer requires resumed guidance, an active route, and an unfinished
route status. Selection/preview is not navigation. Cancellation/finish produces
an entirely black frame, without capturing the map. Lost/off-route status clears
old guidance and displays `Маршрут…`; missing/coarse/outdated position, or a
sample not refreshed for three seconds, displays `Нет позиции` without a frozen
map. The research runner polls this state; it is not an installed startup service.


`captureScreenshot()` belongs to our new offscreen window. There is no screen
mirror, pixel crop, task move, second route calculation, API-key extraction,
host-validation change or vehicle/HUD write. This reuses the original native
renderer with our own scene; it does not reimplement `libmaps-mobile.so`.

The PNG producer runs on the Navigator main thread, as its MapKit requires.
This is suitable for a bounded experiment, not yet a production lifecycle or
performance design. Host-side Base64 transfer exists only to collect evidence.

## Pinned inputs

- Navigator `ru.yandex.yandexnavi` 29.8.1, versionCode 739494300.
- APK: `captures/hud-lanes-20261011/yandex-installed.apk`.
- SHA-256: `3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3`.
- Engine observed: `2026062515.9-aed2f3febc6e406-29.8-navi`.
- Root-capable disposable ARM64 Android 15 emulator; tested with a read-only
  instance of the existing `denza_hu` AVD, port 5580. Never run two owners of it.
- Host Python and Android `adb`; Frida Python and server **16.7.19**.

The Python runner refuses non-emulator serials, non-QEMU targets, split APKs,
an APK hash mismatch and a mismatched ADB-forward. It attaches only to the
already-running package. It neither starts a Frida server nor installs apps.

## Setup on a disposable emulator

Use an unused emulator port and an unused host forward. All commands must keep
an explicit emulator serial; no command in this protocol targets a car.

```sh
.venv-firmware/bin/python -m pip install -r research/yandex-hud-renderer/requirements.txt
/opt/homebrew/share/android-commandlinetools/emulator/emulator \
  -avd denza_hu -port 5580 -read-only -no-snapshot-save -no-window -no-audio -memory 3072
```

Run the emulator command in its own terminal/process. Obtain the matching
`frida-server-16.7.19-android-arm64.xz` from the
[official Frida release](https://github.com/frida/frida/releases/tag/16.7.19),
decompress into the git-ignored capture directory, record its hash, then:

```sh
adb -s emulator-5580 install --no-streaming captures/hud-lanes-20261011/yandex-installed.apk
adb -s emulator-5580 root
adb -s emulator-5580 wait-for-device
adb -s emulator-5580 push captures/yandex-hud-renderer-20261014/frida-server /data/local/tmp/denza-frida-server
adb -s emulator-5580 shell chmod 755 /data/local/tmp/denza-frida-server
adb -s emulator-5580 shell /data/local/tmp/denza-frida-server -l 127.0.0.1:27042
```

Leave the last command running in its own terminal. Then:

```sh
adb -s emulator-5580 forward tcp:27043 tcp:27042
adb -s emulator-5580 shell am start -n ru.yandex.yandexnavi/ru.yandex.yandexmaps.app.MapActivity
```

Let the app load. Build and start a test route in its ordinary UI, without
signing in. The recorded route was Moscow (55.751244, 37.618423) to
(55.7705, 37.5958), opened with `yandexnavi://build_route_on_map` and **Go**.
For simulation the phone's GPS is unnecessary. Existing simulation must be
stopped before the probe can start its own.

## Trial and acceptance

```sh
.venv-firmware/bin/python tools/yandex_hud_renderer_probe.py \
  --serial emulator-5580 --seconds 30 --fps 10 --simulate \
  --out captures/yandex-hud-renderer-20261014/new-run
```

Output directories must be new. Duration is 1–60 s, requested frequency 1–10 Hz;
each Frida operation has a 10 s host timeout. `--simulate` explicitly starts the
Navigator's simulation of its already-selected route and stops it on exit.
Without that flag the probe only reads guidance. On host/process interruption,
discard the disposable emulator; a host timeout cannot cancel a native call
already executing inside Navigator or guarantee remote cleanup.

Inspect PNGs and `report.json`. Current modes are `guidance`, `off`,
`rerouting` and `waiting-location`. `guidance` alone is not pixel evidence;
inspect the PNGs as well. Normal stop clears only our map objects and releases
our references, leaving MapKit and the original map running. Native object
reclamation and long-term memory growth are not measured.

Expected observations:

1. Original Navigator remains in `MapActivity`, with its original map style and
   working guidance; save an emulator screenshot while the trial is active.
2. PNGs contain independently styled roads, the current route and our marker;
   route segment indices progress during simulation. Check actual pixels:
   successful RPC calls and `navigator-route` alone do not prove visible output.
3. Add `--cancel-route-after 5` with `--simulate --seconds 12` to cancel the
   test route in Navigator: subsequent frames must be entirely black, with no road, line, arrow or lane sign.
4. Cleanup must finish without `error` or `cleanup_error` in the report.

`--test-native-alpha` is a **negative-test reproducer**, not a supported theme.
In run-07 it made the native map and route invisible and left only the Canvas
arrow. The working output uses HUD black. Rerouting and location loss/staleness
are covered on the deterministic stand; real driving acceptance is still owed.

The original geometric proof (before the dedicated HUD layout) is under `captures/yandex-hud-renderer-20261014/`, including
the APK/Dex inspection, logs of failed iterations, final PNGs and source hashes.
Run-09 produced 300 PNGs in 30.00018 s (9.99994 fps), median preparation 8 ms.
299 frames had a route; the first awaited the simulation location. There were
291 different PNGs and segment indices 0–19. Run-10 cancelled the route after
5.05 s: 50 route frames followed by 70 base-map frames, with no cyan route or
car marker in the final PNG. `MapActivity` remained resumed; both trials ended
without an execution or cleanup error.
Those historic runs used `navigator-route` / `base-map-only`; the current renderer
replaces the baseline map with an all-black `off` frame. These are emulator-to-host
measurements, not car CPU/GPU or HUD refresh claims.

Close only this disposable emulator and remove only this forward after testing:

```sh
adb -s emulator-5580 forward --remove tcp:27043
adb -s emulator-5580 emu kill
```

## Design stand (2026-10-14)

Generate the portable stand using the already-decompiled, owner-provided APK
corpus. No extracted Yandex artwork is committed. The generator reads nine lane
VectorDrawables and one cursor PNG; the native backend loads the corresponding
resources directly from the installed Navigator.

```sh
node --test research/yandex-hud-renderer/hud-scene.test.cjs
# Requires Playwright + Chrome; set NODE_PATH to the available Node packages if needed.
node tools/yandex_hud_stand_capture.cjs captures/yandex-hud-design-20261014/v2
```

Open the resulting `stand.html`. It embeds scripts, stock resources and the repo's
Roboto font. The source `stand/index.html` is a template: it needs this resource
export before viewing. Select one of 15 situations, compare **Штатная палитра**
and **Яндекс ярче**, start/stop guidance, or export a native 300×180 PNG.

Road geometry and traffic in these cases are explicitly synthetic. The overlay,
stock glyphs, cursor and palette are the real shared rendering code. Cases cover
straight, right/left, exit, roundabout, U-turn, arrival, six lanes, mixed traffic,
severe congestion, unknown traffic, rerouting, lost position, preview and finish.
The exporter writes native PNGs, `situations.png`, `situations-nine.png` and a
browser report. Checks cover native-size export equality, all-black stop and
restart, no header occlusion, and the distinct traffic colors in the mixed scene.

Live-process evidence is under `captures/yandex-hud-design-20261014/`:
`native-preview` remained off in route selection. `native-stock-01` produced
198 frames in 20.09 s (9.86 fps), with 146 guidance frames and 50 black frames
after cancellation. It read **186 real jam segments for 186 geometry segments**:
114 LIGHT, 6 HARD, 63 FREE, 3 UNKNOWN. Preparation median 27 ms, p95 98 ms.
The final `native-stock-final` trial with non-road geometry hidden produced
214 frames / 30.06 s, **7.12 fps**, median 68 ms / p95 224 ms: 165 guidance
frames and 48 all-black off frames, verified pixel by pixel. Traffic was 186/186
segments (125 LIGHT, 6 HARD, 52 FREE, 3 UNKNOWN). Neither final trial had execution
or cleanup errors. The 10 fps target is therefore **not stable** for this richer
renderer in the emulator; profiling and optimization remain open. The concurrent
browser export in the final run is a possible source of host load, not a proven
explanation; the lower measured rate must not be dropped from the findings. This is emulator-to-host output, not HUD refresh or
vehicle performance acceptance. Earlier `native-guidance-*` trials preserve the
superseded cyan/header draft and camera measurements; they are not the final look.

API references: [segment palette](https://yandex.com/maps-api/docs/mapkit/com/yandex/mapkit/map/PolylineMapObject.html),
[route traffic](https://yandex.com/maps-api/docs/mapkit/com/yandex/mapkit/directions/driving/DrivingRoute.html),
[map style tags](https://yandex.com/maps-api/docs/mapkit/style.html).
The embedded Roboto font license is in `tools/design-canvas/luminofor/fonts/`.


## Further work

The missing deployment decision is process access on the actual IVI. The
ordinary companion APK has no public access to this native Guidance instance.
An embedded module in an explicitly agreed experimental Navigator build could
publish frames to a companion/HUD receiver. That requires a reviewed APK patch,
signing/install/restore strategy and a separate car trial. This experiment does
not patch or replace the owner's Navigator, nor claim that Frida/root is
available on the car. Product APKs, Gradle modules and HUD senders are unchanged.
