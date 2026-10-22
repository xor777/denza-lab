# Yandex HUD renderer experiment

## Stock-like prototype (2026-10-22)

The embedded builder has an explicit `--renderer-profile stock-prototype` option.
It renders Yandex's actual selected route with a separate perspective camera,
the recovered BYD HUD traffic fills, no traffic gradients, an approximately
stock-shaped blue cursor and focus `(150,135)`. The existing profile remains
the default and its exported theme is byte-identical. The preset is owned by
[`stock-prototype.js`](stock-prototype.js); it is not loaded by default builds.

The camera uses MapKit tilt 45, numerical zoom caps 14..17, course smoothing
300 ms and a 5 fps target. **The stock camera behaviour is not reproduced.**
The distance-to-manoeuvre zoom formula and 0.8 zoom/s easing remain ours. The
GBL pitch is not assumed equivalent to MapKit tilt. Route width 9 px, outline
1.5 px and the cursor vector are visual starting points, not recovered stock
pixel measurements. Road shades and road-event signs retain the existing
YandexHUD design. Stock traffic borders and the original skeleton cursor model
are not reproduced by this preset.

```sh
python3 tools/yandex_hud_build/build.py --abi arm64-v8a \
  --renderer-profile stock-prototype \
  --out captures/yandex-hud-stock-prototype-20261022/build
```

This produces a separate `*-stock-prototype-arm64-debug.apk`; omission of the
option builds the existing design. For its isolated emulator acceptance use
`tools/yandex_hud_build/acceptance.py --target-fps 5` with the normal explicit
emulator serial/APK/output options. The 10 fps profile retains the old default
acceptance thresholds. AR cache verification compares the count across the
same measured native route id, since the process can build more than one route
before the measurement starts.

Native MapKit output with a real Moscow route was obtained from the embedded
APK on AOSP Android 14. Final acceptance passed: 39 map packets in the roughly
8-second foreground interval, 60 in 12 seconds in background, real lane/AR
packets, reconnect, three black clears and no wire errors. Two earlier runs
failed the old process-wide `arGeometryBuilds == 1` assertion; the native
counter was constant throughout each measured route but included prior route
builds. The corrected checker verifies both the native route id and a constant
positive build count. Four regression tests cover reuse, extra builds, changed
route and missing geometry.

At 13:15 MSK the owner-authorized update from YandexHUD.2 was installed on the
car using the existing ADB connection. Installed SHA-256:
`83d0ba566eaea5d304604a7e145896787cef9239c85dea4c36cf7820b486f225`.
Signer, UID and first-install time were preserved; this proves installation,
not appearance or driving behaviour on the physical glass. Evidence and the
known previous APK are retained in `captures/yandex-hud-stock-prototype-20261022/`
and `captures/yandex-hud-release-2/build-01/` respectively.

A complete stock HUD road frame remains unavailable
because that separate OEM SDK rejected data requests as unactivated. Therefore
this is a prototype for visual comparison, not a claim of pixel equivalence.

## Stock camera implementation in progress (2026-10-22)

The installed YandexHUD.3 still uses the prototype camera described above.
The next camera's confirmed components are implemented as portable Java, with
no runtime connection yet to `HudMap`:

- `StockCameraQueue`: original one-second candidate interpolation and signed
  per-evaluation clamps, sentinel and retarget semantics. Original ARM64 queue
  traces cover both directions and different evaluation intervals.
- `StockCameraRules` and generated `StockCameraDefaults`: first-match selection
  of the 43 recovered new-3D default rules, plus the distinct SimpleZoom and
  SimplePitch absent-target behavior. The independent original config-manager
  factory returned mask63; 384 original matcher probes agree exactly. This
  is not a measurement of the car's cloud-selected live guide singleton.
- `StockCameraProjection`: measured fa3 zoom scale, FOV, pitch ceiling and full
  800×800 projection before crop `(100,290,600,360)` and downsample0.5. Native
  projection samples include fractional zoom, fixed pitch and points500m away.
  Maximum error is0.1273 raw px, or0.0637 final HUD px, on this corpus.

`CameraTest` passes1942 assertions; the build also checks the generated defaults
against [`stock-camera-defaults.json`](stock-camera-defaults.json). The three
`stock-camera-*-oracle.csv` files contain actual outputs of original functions,
not expectations generated from the Java implementation. Native source hash,
function addresses, scope and gaps are recorded in
[`stock-camera-evidence-20261022.json`](stock-camera-evidence-20261022.json).

The original new-3D route-record selection/priority, Action/Complex/End geometry,
roll/reset behavior and playback cadence still need completion. Public Yandex
route APIs expose highway ranges but no confirmed one-to-one GBL road-class or
formway mapping. The guidance-provided ClassifiedLocation is already native
processed data; it must not be relabeled as raw GPS. These gaps prevent calling
the current code a complete stock camera or enabling its partial controller.

An independent HUD map **inside the running Navigator process**, using its
existing MapKit and current Guidance. Full navigation stays in the original
main-screen Activity. This directory is the original instrumented emulator experiment. The
[embedded debug APK and HUD transport](../../experiments/yandex-hud-module/README.md)
now have a separate owner. The latest native lane/manoeuvre slots await car
acceptance; this is not an ordinary companion APK.

Findings and access boundaries live in
[HUD projection findings](../../docs/hud-projection-findings.md#hud-yandex-independent-renderer).
The branch starts from `d6253bae`, `experiment/yandex-hud-renderer`.

## What it renders

`renderer.js` creates a 300×180 offscreen MapKit window for the active route.
The primary Navigator map keeps its original UI and camera. **No manoeuvre
panel, distance or reserved header**; the map uses all 180 pixels.

`hud-scene.js` is the one design source. The browser stand paints it directly;
the [embedded module](../../experiments/yandex-hud-module/README.md) receives it
as `assets/denza-hud/theme.json` (`tools/yandex_hud_build/theme.cjs`) and only
positions its templates. Current map design is v3 with native HUD arrows
(2026-10-15), tuned for the
additive glass:

- **Route classes** are saturated primaries with zeroed spare channels: FREE
  `#00F050`, LIGHT `#FFE600`, HARD `#FF1F3D`, VERY_HARD `#FF1FC8` (magenta),
  UNKNOWN `#00C8FF` (cyan, never a shade of free), BLOCKED `#D0D4DC`. Over
  daylit asphalt every colour loses about half its saturation (glass model of
  2026-10-12 in the findings), and a dark colour is no colour, so Yandex's
  burgundy for very hard would read as a dim red. MapKit draws the line 8 px
  wide with a 2 px black gap, 24 px gradients between classes and its own white
  manoeuvre arrows (`PolylineMapObject.addArrow`) on the next two manoeuvres.
- **Cursor**: Yandex's faceted arrow, drawn as vectors in red-orange (lit facet
  `#FF7A33`, shaded `#E8480E`) on a black underlay. Orange sits about 35° from
  the jam red, so the car never reads as a traffic segment.
- **Roads**: about 80 % neutral white (`#C8CCD4`) at 1.35× MapKit's width
  (style `scale`), `road_minor` (yards, driveways) about 54 % (`#8A8E96`).
  **Corrected on the owner's glass, 2026-10-14:** the first v3 roads, 45 % and
  22 %, were almost invisible while the route and cursor read well. Labels, POI, paths,
  markings and crosswalks are hidden. **Land is painted black, not hidden**:
  hidden land shows MapKit's night clear colour (13,13,13), a faint glowing
  rectangle on the glass.
- **Lanes and manoeuvre block**: the embedded module now sends original cyan
  stock HUD artwork into the separate native slots. No blue badge, lane glyphs
  or lane exclusion rectangle remain on the map. Details, matching rules and
  pixel/cache checks live in the module README linked above. The old badge
  helpers remain exported only for historical research consumers; the scene
  builder and embedded JSON theme no longer use or ship them.
- **Road events on the route**, upright, about 20 px, each on a black halo:
  camera (with the posted limit as a red-ringed sign when the event carries
  one), police, accident and danger triangles, roadworks barrier, closed road,
  speed bump (amber hump on its line) and railway crossing. Nearest first, at
  most four; an icon that would cover the cursor or a nearer icon is
  left out. Chat, feedback and «other» events never reach the glass.
- **Finish**: a white target ring where the route ends.
- Focus is (150,143); heading follows guidance, zoom 14.6–16.7 uses the upcoming
  manoeuvre distance internally to choose context.
- Black is optically transparent in the owner's HUD slot, **not PNG alpha**.
  Brightness on the real glass is still uncalibrated for this design.

**Superseded, v2 (earlier on 2026-10-14):** a golden stock `notification_arrow`
cursor, stock `context_lane_*_large` glyphs stretched into 23×28 cells on the
full `balloon_new_blue`, a badge fixed at the top right, and stock jam colours
with raised HSL lightness. On the glass model lime and yellow merge, the golden
cursor shares the yellow route's hue, the fixed badge covered right turns, and
stretched glyphs lost their proportions. Stand PNGs of v2 stay in `captures/yandex-hud-design-20261014/v2/`.

`DrivingRoute.getJamSegments()` supplies a class for each original geometry
segment. The route layer subscribes to `ConditionsListener`; changes are read
on the next frame, with a 30-second fallback refresh. Route changes and the
outdated flag invalidate immediately. A length mismatch or outdated conditions
produces UNKNOWN. One full geometry retains its original traffic indices;
`hide(Subpolyline)` advances the visible start without `setGeometry` or a new
palette upload on every frame.

**The Frida runner below is historical.** `android-painter.js` executes the v2
command set (`roundRect`, `text`, `path`, `drawable`); v3 adds `group`, `shape`
and `circle`. To reproduce the Frida trials, check out `e9240849`.

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

MapKit access and bitmap composition run on Navigator's main thread. PNG
compression and Base64 conversion run on the Frida worker thread, verified by
thread names in the report. A single in-flight frame owns the shared buffers
until encoding completes. Host-side transfer exists only to collect evidence;
this historical Frida runner has no HUD transport or startup service. The
embedded module linked above adds automatic startup, background guidance and
Binder/SOME-IP transport without process instrumentation.

## Pinned inputs

### Stock HUD artwork reference (2026-10-15)

`stock_reference.py` is a host-only inspection tool for the owner's pinned
IVI `BydLaunchermap.apk`. It uses Pillow, copies 254 original lane/manoeuvre
assets into ignored captures, records hashes/dimensions/pixel colours and
builds labelled example sheets. It does not modify the HUD module or a car.
The original primary palette is cyan `#15C6FF` / grey `#91959C`, with 37×60 px
lane cells and 2 px side margins; compound arrows highlight the recommended
branch. The alternative blue set is selected by the car's colour flag.
Full source trace and limits: [stock artwork findings](../../docs/hud-projection-findings.md#hud-stock-artwork).

```sh
# Use a Python with Pillow (also provided by the Codex workspace runtime).
python3 research/yandex-hud-renderer/stock_reference.py \
  --apk captures/hud-firmware-20260923/system/system/app/BydLaunchermap/BydLaunchermap.apk \
  --out captures/yandex-hud-stock-style-20261015
```

The APK must match SHA-256
`3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793`.
`--font` overrides the macOS default Arial path for labelled sheets.
Outputs are offline resource examples, not glass or Android rendering acceptance.

### Navigator experiment inputs

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
each Frida operation has a 10 s host timeout. `--cycles 3` exercises repeated
start/stop in the same script (total rendering duration is capped at 120 s).
Cancellation, if requested, happens in the final cycle. `--simulate` explicitly starts the
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

The stand needs no extracted Yandex artwork any more: every glyph, icon and the
cursor are our vectors in `hud-scene.js`.

```sh
node --test research/yandex-hud-renderer/hud-scene.test.cjs
# Requires Playwright + Chrome; set NODE_PATH to the available Node packages if needed.
node tools/yandex_hud_stand_capture.cjs captures/yandex-hud-design-20261014/v3
```

Open the resulting `stand.html`; it embeds the scripts and the repo's Roboto.
Select one of 18 situations, compare the glass palette with **Штатная палитра**
(stock jam colours and a golden cursor), start/stop guidance, or export a native
300×180 PNG.

Road geometry, traffic and events in these cases are explicitly synthetic. The
stand draws roads, the route and MapKit's manoeuvre arrow in the map style's
colours as a stand-in for the native layer; the overlay over it is the same
command stream the module draws. Cases cover straight, right/left, exit,
roundabout, U-turn, arrival, six lanes, mixed, severe and unknown traffic,
speed bumps, enforcement (camera, police, bus lane), railway and danger,
rerouting, lost position, preview and finish. The exporter writes native PNGs,
`situations.png`, `situations-guidance.png` and a browser report.

Live-process evidence is under `captures/yandex-hud-design-20261014/`:
`native-preview` remained off in route selection. `native-stock-01` produced
198 frames in 20.09 s (9.86 fps), with 146 guidance frames and 50 black frames
after cancellation. It read **186 real jam segments for 186 geometry segments**:
114 LIGHT, 6 HARD, 63 FREE, 3 UNKNOWN. Preparation median 27 ms, p95 98 ms.
The final `native-stock-final` trial with non-road geometry hidden produced
214 frames / 30.06 s, **7.12 fps**, median 68 ms / p95 224 ms: 165 guidance
frames and 48 all-black off frames, verified pixel by pixel. Traffic was 186/186
segments (125 LIGHT, 6 HARD, 52 FREE, 3 UNKNOWN). Neither final trial had execution
or cleanup errors. These are **pre-optimization measurements**, superseded by
the profiled implementation below; they did not establish a stable 10 fps. The concurrent
browser export in the final run is a possible source of host load, not a proven
explanation; the lower measured rate must not be dropped from the findings. This is emulator-to-host output, not HUD refresh or
vehicle performance acceptance. Earlier `native-guidance-*` trials preserve the
superseded cyan/header draft and camera measurements; they are not the final look.

API references: [segment palette](https://yandex.com/maps-api/docs/mapkit/com/yandex/mapkit/map/PolylineMapObject.html),
[route traffic](https://yandex.com/maps-api/docs/mapkit/com/yandex/mapkit/directions/driving/DrivingRoute.html),
[map style tags](https://yandex.com/maps-api/docs/mapkit/style.html).
The embedded Roboto font license is in `tools/design-canvas/luminofor/fonts/`.


## Runtime structure and performance (2026-10-14)

The visual model stays in `hud-scene.js`; all 15 stand PNGs are byte-identical to
v2 after this change. Backend responsibilities are explicit:

- `navigator-adapter.js`: guidance validity, camera distance, native lane data,
  and an owned route layer. Geometry is installed once per route ID; progress
  hides the passed subpolyline. Conditions listeners are retained and removed
  with their route. Traffic colors are uploaded only when their indices change.
  The obsolete maneuver-annotation traversal and per-frame geometry copying
  are removed. Invalid progress clears the route rather than drawing stale data.
- `android-painter.js`: two reusable ARGB buffers (432,000 bytes in total,
  excluding native map and resources), cached drawables, and a reusable PNG
  stream. The overlay is rebuilt only when drawing commands change. Static
  off/error frames reuse encoded bytes. The native screenshot is copied into
  the owned output buffer and recycled in `finally`.
- `renderer.js`: main-thread map work, worker-thread encoding, a guarded
  idle/starting/running/stopping lifecycle, and single-flight frame ownership.
  Partial initialization rolls back; cleanup attempts every release even after
  one fails. Only owned simulation/resources are stopped. Zoom smoothing uses
  elapsed time instead of the number of frames.
- `tools/yandex_hud_renderer_probe.py`: bounded RPCs, frame acknowledgements,
  numbered multi-cycle captures, source snapshot/hash, mode-specific timings,
  and cleanup covering attach/load/start failures. Missed deadlines are dropped,
  never accumulated into a render backlog.

Evidence: `captures/yandex-hud-performance-20261014/`. Browser export did not
run during these timing trials. Same APK, AVD, 300×180 output and 10 Hz target:

| Trial | Frames / elapsed | Actual fps | Active preparation median / p95 | Main-thread median / p95 |
| --- | --- | --- | --- | --- |
| `baseline` (previous code + stage timers) | 196 / 20.03 s | 9.79 | 28 / 98 ms | 28 / 98 ms; encoding was on main |
| `optimized-01` (cached geometry/overlay, polling traffic) | 200 / 20.00 s | 10.00 | 14 / 62 ms | See per-stage report |
| `optimized-03` (conditions events, final source) | 298 / 30.01 s | 9.93 | 16 / 32 ms | 8 / 12 ms |

The final 30-second trial contains 297 guidance frames and one rerouting frame.
It built geometry once, uploaded traffic once, painted the overlay three times,
and loaded four drawable resources. The route stage p95 is 1 ms. Encoding ran
on `Thread-29`, while map work ran on `main`. The newer preparation metric also
includes dispatch latency; `rpcMs` additionally includes host transport.
First-frame initialization still causes occasional missed deadlines: maximum
preparation was 211 ms (main work 94 ms). This is approximately 10 fps on the
emulator, **not a guarantee of an uninterrupted 10 fps on the car**.

`lifecycle` runs three start/stop cycles in one script: 118, 120 and 119 frames
in 12 seconds each, no execution/cleanup errors. Cancelling in the final cycle
produced 40 entirely black PNGs, verified pixel by pixel; 39 reused the encoded
black frame. Each cycle built geometry/uploaded colors once; normal stop and a
second idempotent stop succeeded. Navigator's original `MapActivity` remained
resumed afterward. Tested source SHA-256:
`2eeeed842a00e68f922f50f9618bbd5060afb46db9ae828c75c636c25a9c507c`.

`optimized-02` is retained as a failed-start record: no main map was found and
no frames were produced. The emulator had restarted Android's system process
between trials (`zygote64` abort in `crash-after-01.txt`), leaving the launcher
foreground. Reopening Navigator restored the preconditions for `optimized-03`;
that system event is not attributed to a renderer cause. No car was connected.

```sh
node --test research/yandex-hud-renderer/*.test.cjs
```

Fourteen tests cover scene gates/palette, geometry reuse, route replacement,
traffic events/staleness/fallback, invalid progress, late old-route callbacks,
partial-start rollback, concurrent-frame rejection and cleanup failure. Real
conditions updates did not occur during the short native trials; event-driven
invalidation and route replacement are unit-tested, not live-driving acceptance.
Long-duration native memory/GPU behavior, background/foreground transitions,
real rerouting and the transport to the glass remain unmeasured.

## Further work

The missing deployment decision is process access on the actual IVI. The
ordinary companion APK has no public access to this native Guidance instance.
An embedded module in an explicitly agreed experimental Navigator build could
publish frames to a companion/HUD receiver. That requires a reviewed APK patch,
signing/install/restore strategy and a separate car trial. This experiment does
not patch or replace the owner's Navigator, nor claim that Frida/root is
available on the car. Product APKs, Gradle modules and HUD senders are unchanged.
