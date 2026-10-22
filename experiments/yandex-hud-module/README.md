# YandexHUD — embedded Navigator HUD experiment

Owner-only **debug** build of Navigator 29.8.1 with an independent 300×180 HUD
map and Binder/SOME-IP output. Developed on `experiment/yandex-hud-renderer`, merged into
main and the branch deleted on 2026-10-18.
This module is embedded into the pinned owner APK by a host build tool; it is
not an independently installable app or a dependency of Denza Apps. The car
installation is separate from these build/test tools. Detailed evidence belongs to
[HUD findings](../../docs/hud-projection-findings.md#hud-yandex-embedded).

## Runtime

The optional `stock-prototype` renderer profile is selected with
`build.py --renderer-profile stock-prototype`; see the
[profile and its limits](../../research/yandex-hud-renderer/README.md#stock-like-prototype-2026-10-22).
`HudMap` reads the theme's optional `camera` object for perspective, focus,
zoom bounds and MapKit fps; `HudModule` uses that fps for its active tick too.
Existing themes without `camera` keep 2D, zero tilt and 10 fps. The prototype
does not implement the stock dynamic camera law.
The recovered camera core (`StockCameraQueue`, `StockCameraRules`, generated
defaults and `StockCameraProjection`) is tested independently against original
native outputs and is not connected to the installed prototype yet. See the
[implementation status](../../research/yandex-hud-renderer/README.md#stock-camera-implementation-in-progress-2026-10-22).

- Two small hooks capture application context at MapKit initialization and the
  completed `GuidanceBinding` instance. Deferred work runs on the main thread.
  The original main Activity, navigation engine and foreground service retain
  their lifecycle. No Frida, root, laptop connection or screen capture is
  required by the resulting APK.
- `NavigatorSource` reads the actual selected/resumed route, native position,
  background-navigation preference, traffic, lane signs (bus/tram/taxi lanes keep
  their place) and the windshield for upcoming manoeuvres. It checks
  route/resume **before** accessing automotive route status: the native API is
  undefined before a route exists and can abort debug ART.
- `HudArSource`/`HudAr` supply the stock Navigation Fusion animation with real
  route geometry. The next manoeuvre's `RoutePosition.positionOnRoute(routeId)`
  is the guide point; the line starts at the current fractional polyline position,
  retains every vertex through that manoeuvre, and continues to the following
  manoeuvre (at most 120 m beyond the first). This follows BydLaunchermap's
  current-leg/next-leg contract, with a bounded exit. More than 256 vertices
  suppresses AR rather than inventing a simpler bend. Geometry is copied once
  per route id; only the local slice crosses to the existing output worker.
  Coordinates, heading and speed come from Navigator's classified location,
  without a second GPS listener, ADB, accessibility or new permissions.
  Missing heading/speed, accuracy worse than 20 m, fixes older than 2.5 s or
  over 1 s in the future, a pose over 30 m from the route, incompatible route
  positions, passed manoeuvres and missing exits suppress AR while preserving
  ordinary guidance. The worker checks expiry again after encoding. Mode or
  route changes invalidate queued frames. Ferry/finish/waypoint instructions
  do not request AR; real roundabout geometry uses the existing stock ids.
- `HudMap` owns a separate offscreen MapKit window. Geometry is created once per
  route; progress hides its completed prefix. Conditions events invalidate the
  traffic palette and the route's road events, with a 30-second fallback.
  Outdated/mismatched traffic uses UNKNOWN. White manoeuvre arrows
  (`addArrow`) mark the next two windshield manoeuvres; MapKit accepts arrows
  only in order along the line, so an earlier announcement rebuilds the line.
  Road events (`getEvents`: tags and the posted limit in m/s), speed bumps and
  railway crossings are cached per route and projected with `worldToScreen`
  after the camera move, nearest first. `HudEvents` places each icon beside its
  point, off the route projected ahead and off the cursor (cameras, closures,
  accidents and crossings first, at most four); each icon, with its lead line
  and the dot on the road, is a MapKit placemark anchored on the dot, so it
  turns with the map in MapKit's own frame. The camera's course is eased over
  ~300 ms. A position from before a rebuild or off the route dims the line to a
  third, hides the arrows and the icons. While MapKit still shows its loading
  placeholder (light grid, or the night clear colour with grid lines) the frame
  carries no map. It never moves the main map, sets a location provider,
  computes a second route or changes global MapKit start/stop state.
- `HudPainter` owns two bitmaps and an overlay and executes the theme's drawing
  templates op for op as `research/yandex-hud-renderer/hud-scene.js` paints them
  (exported by `tools/yandex_hud_build/theme.cjs`) and renders the event icons
  for HudMap's placemarks, Navigator's own pins by resource name in their day
  variant. It never prints text in the window. Lane guidance never occupies the map.
  The design itself is described in the
  [renderer README](../../research/yandex-hud-renderer/README.md#what-it-renders).
  Black is the HUD's optical background convention, not true PNG transparency.
- `StockHudSymbols` translates lane directions/recommendations and manoeuvres
  to the primary cyan BydLaunchermap resources. `StockHudArt` loads their original
  bytes from APK assets on the output worker, outside Android density scaling.
  Lane cells are 37×60 with 2 px side margins (41 px pitch), on optical black;
  only the current row is encoded and cached. Every lane gets a cell of BYD's
  pixels: a bus lane is BYD's bus, a tram lane the bus with a pantograph, a taxi
  lane BYD-grid checkers, a lane shift BYD's shifted-lane picture (mirrored for
  the right, recoloured grey when not the lane to take), an unknown arrow BYD's
  empty lane, a turn from the far lane the plain turn; a combination BYD never
  drew drops its U-turns, then its outer branches. Only an oversized sign is
  hidden. 45°/135° turns use the stock left/right symbol.
  Manoeuvres reuse original 80×80 PNG bytes without drawing or re-encoding.
- `HudModule` polls at up to 10 fps while active, 2.5 Hz while off. MapKit and
  composition stay on main; PNG encoding and Binder calls share one worker.
  Only one frame is in flight, with no frame queue. An epoch invalidates work
  already encoding after cancellation. Drawing resources are released after
  that worker finishes. The map-only main-thread timing counter excludes idle
  and status-only frames.
- Hidden Navigator continues rendering **only** if stock background guidance
  is enabled and guidance remains resumed. No Activity is kept visible; the
  existing navigation service keeps its own process alive. Disabled background
  guidance, cancellation and finish clear output. A missing, coarse or stale fix
  holds the last good one: for 30 s nothing changes (map, hint, lanes), then the
  map dims to a third and the icons go; AR runs only on a fresh fix.

## Transport

The module is a source of [`libraries/hud-link`](../../libraries/hud-link/src/main/java/dev/denza/hudlink/)
([architecture](../../docs/hud-output-architecture.md), 4–6); the builder compiles all of it
into the module dex, and its plain-Java part (scene, placement table, road packet) into the host
tests. Each output frame `HudModule` publishes into a `HudLink` on the output worker
(`ModuleFields`): the painter's PNG — the black static one included — as the map window's only
layer, whole and opaque, so it reaches the HUD as painted; and the road fields by role. MANEUVER
(3/4, 8/9/10/28, 26/27) and LANES (5 and 7, or the explicit 5 = 0) go with every frame of an
active route, MANEUVER even when empty: the module's route opens navigation by itself, as it
always did. ROAD_LIMIT (11) and AR (19–33) go when there is a limit and a fresh AR frame and are
withdrawn otherwise. Everything lives 1.5 s (`HudScene.LAYER_TTL_MS`), so a module that stops
publishing leaves the glass after 1.5 s. No route, a failed output and a failed frame withdraw
everything. The compositor writes the fields back in the module's old order (`HudRoadPacket`):
`ModuleTest` and `ArTest` (its `FULL_ROAD` golden comes from the encoder before the move) hold the
bytes, and `HudWire` composes them on the same path.

Hosted through Denza Apps or drawing by itself — `HudLink` decides, the module does not know:

- At the first publish, and every 5 s while there is no host, it binds Denza Apps' host
  service (`HudHostProtocol`; the manifest's package query for `dev.denza.apps` lets Android
  11+ see it). While that binding is under way nothing is written, for at most 1 s.
- Attached: the frame's calls go to Denza Apps as one one-way batch per worker turn, and Denza
  Apps alone writes the HUD; the module opens no SOME/IP session. If the module's process dies,
  Denza Apps drops what it published.
- No host — Denza Apps missing, without the service, refusing the module, silent for 1 s, or
  gone mid-route: the module writes itself at once through its own `HudOutput`, the same
  compositor and session policy Denza Apps uses, with everything still alive.
- Handover never writes twice and never clears: when Denza Apps attaches (the next try, ≤5 s),
  the module's own session stops and unbinds without the clear, and the host gets the whole
  picture in one batch. Whether the glass needs a black frame there is a live check.

The own session binds explicitly to `com.ts.car.someip.service` with the caller's package as
intent type and `ts.car.someip.sdk.ISomeIpServerInterface` as Binder descriptor.
Transactions 4/5 start/stop service `3097367205183488`; transaction 6 sends the
exact handle/topic/instance/length/byte-array layout. Topic `1127042368241665`
is the road packet: 2 = 2, 16 = 2 (navigating) and, while a moving map is
drawn, the hint in Denza Apps' field order — 3/4 remaining metres/seconds,
5 the lane count and 7 its raw PNG (independent of the hint),
8 the original cyan 80×80 manoeuvre PNG (including roundabout exits 1–10), 9 metres to the manoeuvre (never below 11: from 0 to 10 the HUD writes the Chinese «now»), 10 its street, 11 the posted limit
in km/h, 26/27 «3,4 км» / «10 мин», 28 the stock manoeuvre id. Everything comes
from guidance itself: windshield manoeuvres (`Action`, toponym, the roundabout
exit on the manoeuvre that leaves it), the route's `RoutePosition`
(`distanceToFinish`/`timeToFinish`) and `getSpeedLimit()` (m/s, 16.67 → 60).
Field 28 follows the stock BYD navigator: 13 enters and 24 leaves a roundabout
(Denza Apps sends 25), 48 finish, 45 waypoint. Without these fields the HUD
filled the manoeuvre window with its own Chinese captions. A changed hint goes
out at once, at most four times a second, else every 500 ms. A frame without a
manoeuvre repeats the last one shown (`ManeuverHold`), so the HUD never fills
an empty block in Chinese: up to 30 s while Navigator rebuilds the route or the
hint cannot be read, up to 3 s when guidance simply has none ahead. Rerouting
still sends explicit 5 = 0, without a lane image; a held position keeps both.
Before the first fix only 2/16 goes out. A missing
lane sign also sends 5 = 0 while the route remains active. Field 6 (road class)
is unchanged; no road is falsely labelled main road. Field 29 is unnecessary
for the recovered image slot. Topic `1127042368241667` carries field 1 =
Base64 PNG, without the road envelope. **No vehicle-property or DiShare writes**:
the car's own speed sign (cluster and HUD) needs the `BYDAUTO_*` Setting writes
Denza Apps makes through local ADB; this process has no such permission, so
only field 11 carries the limit.

AR shares that same complete road snapshot: doubles 19/20 are longitude/latitude,
integers 21/22 speed in m/s and altitude in metres, 30 is JSON `[[lon,lat,0],…]`,
31 is `lon,lat,0` for the manoeuvre, 32 is heading in degrees and 33 the fraction
of polyline length already covered. A compact or inactive snapshot omits **all**
AR fields. The HUD renders the animation itself; the map PNG does not carry it.
No Navigation Fusion setting or other persistent car property is written.

The own session follows `HudSession`: a connection generation rejects late
callbacks; a binding that has not started in 3 s, a refused bind, a lost service
and a failed send drop the binding and retry after 1 s. A changed road packet goes
at once, at most four times a second, else every 500 ms; a new map frame at most
12 times a second, the same frame again every second. When everything has died or
been withdrawn, the clear — black PNG + inactive road (5 = 0) three times at 120 ms
— then stop/unbind at 350 ms. As with any in-process watchdog, this cannot send a
clear after process death or while the Binder thread is blocked; receiver-side
expiry and kill/restart behavior still need car acceptance.

Logs, tag `DenzaYandexHud`, one line per event and none per frame: `HUD through
the host` / `HUD by itself: no host` / `HUD by itself: the host did not answer`
(where the frames go; nothing when there is no host to bind at all), `host
attached` and the host's failures (`host refused the source: N`, `host
disconnected`, `host died`); the own session's `HUD bind=…`, `HUD start ret=…`,
`transport started`, `transport <TROUBLE> …; retrying every 1000 ms` (once until it
starts), `clearing HUD`, `HUD stop ret=…`, `transport stopped`.

## Build

Requires JDK 17, Android SDK platform 35 / build-tools 36.0.0, Node, `apkanalyzer`
and the dexlib2 bundled in local JADX 1.5.5. Paths can be supplied via
`JAVA_HOME`, `ANDROID_HOME`, `--jadx-jar`, `--apk` and `--stock-apk`.
`--abi arm64-v8a` keeps only the 22 ARM64 native libraries; `--abi universal`
(the default) retains all original architectures. No other code or resources
are removed. The builder verifies the exact omitted entries, the remaining
ABIs and the byte equality of every retained original file outside the normal
module/manifest/account patch; it records the ABI and sizes in the report.
The stock artwork input is the pinned BydLaunchermap APK described in
[stock artwork findings](../../docs/hud-projection-findings.md#hud-stock-artwork).
The builder checks its hash and dimensions and copies 127 original PNGs
(176,502 bytes) into `assets/denza-hud/stock/`; no foreign binary is committed.
Every reachable symbol mapping must resolve to an asset. The build report
records and verifies byte equality of every copied image.

```sh
python3 tools/yandex_hud_build/build.py --abi arm64-v8a \
  --out captures/yandex-hud-arm64-20261015/new-build
```

The input must be the owner's single APK 29.8.1 / code 739494300, SHA-256
`3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3`.
The script adds our module in a new dex and patches exactly two methods.
Because original `classes12.dex` already has 65,536 method references, its
patched Guidance class is relocated into a second new dex. No foreign source
or binary is committed.

Manifest changes are limited to `debuggable=true`, version name/code from
`tools/yandex_hud_build/identity.py`, the label **YandexHUD** on the application and on
`MapActivity` (the launcher alias has no label of its own and Android takes
the target activity's, so the shortcut, the app list and recents show it), and
two package-visibility queries: the HUD service, and `dev.denza.apps`, whose host
service the module binds (Android 11+ hides it otherwise). The common declaration
`<permission name="com.yandex.permission.READ_CREDENTIALS">` is removed: a fork
with our signer must not claim ownership of a signature permission shared by
official Yandex apps. Its `uses-permission` request, all component guards and
the package-specific AndroidX signature permission stay unchanged.
The original package stays. Android SDK XML decoding verifies
that all other manifest declarations match; the builder verifies the signature,
16-KiB alignment and byte equality of all original ZIP entries except the two
patched dex files, manifest and `resources.arsc`. The sole resource change is
`passport_account_type` → `com.yandex.passport.denza`; an independent full `aapt2`
dump comparison rejects every other resource change. Resource IDs, XML
references and retained native libraries stay unchanged. `build-report.json` records
APK/module hashes and the resource check. The builder first runs 117 module and
152 AR host assertions, including real winding geometry, fractional junctions,
route identity, cache reuse, freshness, invalid positions, fixed64 wire fields and
the roles each frame publishes or withdraws.

The dedicated local experimental key lives in ignored
`captures/yandex-hud-signing/experiment.p12`. It is **not Yandex's key**. Passport
already permits debug applications with a different signer; its check is not
patched. The release-mode attempt exits with a signature mismatch. The debug
candidate builds routes without signing in; account login, SSO, purchases and
signature-dependent integrations are unverified.

## Build identification

`tools/yandex_hud_build/identity.py` is the single source of the published build
number. Numbering starts at **1**; earlier experiments keep their historical
names. Increment `BUILD_NUMBER` before distributing a changed build; rebuilding
the same source keeps its number. The builder does not increment a local counter.

- Launcher/app name: **YandexHUD**.
- Android app-info version: `29.8.1-YandexHUD.1` (upstream version + build number).
- Android version code: upstream `739494300` + build number, initially `739494301`.
- Output file: `YandexHUD-1-arm64-debug.apk` (or `universal`).
- Startup log (`DenzaYandexHud` tag) reads the installed version/name from
  PackageManager. `build-report.json` records the label, build number, version
  name/code, source hashes and APK SHA-256; a `.apk.sha256` sidecar is also saved.

The package, account type and signing key stay the same, so installing with
replacement preserves the app's data. Use the APK hash as the exact identity
for device testing; a build number alone is not a byte-for-byte check.

## Emulator checks

`test-receiver/` is a disposable fake of the IVI Binder endpoint. It validates
session state, topic IDs, protobuf, PNG dimensions and clear frames, parses the
road packet field by field (a clear has zero lanes and no hint; lane rows
are 41×count by 60, manoeuvres are 80×80 with metres and an id; a limit is 5–130 by 5), keeps the last hint
in `report.json` and records a bounded 60-frame ring. Acceptance also requires
hint packets in the foreground stretch. **Never install this APK on the car.** Both its service
and the candidate's private-file simulation harness are restricted to
`ranchu`/`goldfish`. The candidate exposes no new command receiver or socket.
Simulation commands are consumed once; repeated stop checks IDLE first because
native `stopSimulation()` otherwise aborts. For design work the same harness
takes `{"action":"record","every":N,"limit":M}` (every Nth guidance frame into
`files/denza-hud-record/`, at most 600) and `{"action":"speed","metresPerSecond":v}`
(simulation speed); a pushed `files/denza-hud-theme.json` replaces the APK theme
on the next frame, so colours, sizes and templates change without a rebuild.

```sh
python3 tools/yandex_hud_build/receiver.py captures/yandex-hud-embedded-20261014/new-receiver
adb -s emulator-5580 install captures/yandex-hud-embedded-20261014/new-receiver/emulator-only-hud-receiver.apk
adb -s emulator-5580 install captures/yandex-hud-arm64-20261015/new-build/YandexHUD-1-arm64-debug.apk
```

Use a disposable root-capable emulator. A fresh (`-read-only`) boot has no
runtime permissions: grant `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
`ACCESS_BACKGROUND_LOCATION` and `POST_NOTIFICATIONS` to `ru.yandex.yandexnavi`
with `pm grant`, otherwise Navigator suspends guidance when hidden and the
background check sees no frames. Start a route in Navigator's ordinary UI
first (`yandexnavi://build_route_on_map?lat_from=…&lon_from=…&lat_to=…&lon_to=…`,
then **Go**). Then:

```sh
python3 tools/yandex_hud_build/acceptance.py --serial emulator-5580 \
  --apk captures/yandex-hud-arm64-20261015/new-build/YandexHUD-1-arm64-debug.apk \
  --out captures/yandex-hud-embedded-20261014/new-acceptance
```

The runner verifies QEMU and the installed APK hash. It checks foreground and
hidden rendering with actual moving PNGs, receiver-process death/rebind,
background-disabled clear, stable stopped counters and repeated cancellation
without a Navigator restart. Inspect saved PNGs as well as `report.json`.
`src/test/.../ModuleTest.java` covers exact wire bytes, the roles each frame
publishes or withdraws, preview/cancel/resume, background preference and
stale/coarse position on the host without the APK. Use `--require-lanes` on the route above to
also require real lane packets in background guidance.
Add `--require-ar` to require AR in both stretches, a single route geometry
build and AR cleanup. The emulator-only harness independently exports MapKit's
native route; the receiver saves a bounded 60-sample ring of decoded AR.
The host runner verifies that every transmitted point lies on that native
polyline in forward order, within 0.1 m. Neither export runs on a car.
The renderer's Node tests (`node --test research/yandex-hud-renderer/*.test.cjs`)
cover the design and the theme contract, not native transport.

## Current candidate (2026-10-15)

**YandexHUD build 1, ARM64 debug:**
`captures/yandexhud-1-20261015/YandexHUD-1-arm64-debug.apk`, 219,762,615 bytes,
SHA-256 `c93b90f8f8553cba1415c500db7bcf81d20fe452875da39968e157a6e23feea7`.
Version `29.8.1-YandexHUD.1`, code `739494301`. This starts numbered builds;
the native AR behavior below is unchanged. Only the manifest (name/version)
and `classes17.dex` (startup version log read from PackageManager) differ from
the AR reference outside signing metadata, verified in `comparison.json`.
67 module / 148 AR assertions, SDK manifest/resource comparison, signature and
16-KiB alignment checks passed. All original native libraries, map/AR code,
stock images, account isolation and signing identity are preserved.
Installed as an update on the owner's car at 13:04 MSK on 2026-10-15:
`car-install-01/report.json` confirms the APK hash, version/code, ARM64/debug,
unchanged UID and first-install time, and no change in the crash log.
No route was started for this naming update; physical AR acceptance remains pending.

**AR reference before YandexHUD numbering (same navigation behavior):**
`captures/yandex-hud-ar-20261015/DenzaHUD-AR-arm64-debug.apk`, 219,762,615 bytes,
SHA-256 `11a882f29cebfe3b917a7b55860c4e167c7904f5846261ede551a97c445252c7`.
Only the embedded module (`classes17.dex`) differs from the previous ARM64
candidate outside signing metadata (`comparison.json`). Account isolation,
signature, manifest, original native libraries and stock artwork are unchanged.
`acceptance-01 --require-lanes --require-ar` passed: 9.91 fps foreground /
9.90 hidden, 25 AR packets during the foreground interval and 40 during the
background interval, 40 background lane packets, one geometry cache build,
receiver restart/reconnect, triple clear and zero protocol errors. Independent
host comparison of 43 foreground and 60 background AR samples against the
native MapKit route found a maximum point deviation of 0.0051 m (7-decimal
serialization). This measures wire geometry, **not** alignment on the glass.
Installed as an update on the owner's DiLink 5.1 / Android 13 at 12:13 MSK
on 2026-10-15 (`car-install-01/report.json`): the installed APK hash matches,
ARM64/debug confirmed, UID and first-install time preserved, crash log unchanged.
Physical AR appearance and route/background behavior on the car still await
owner testing. Navigation Fusion must be enabled; Denza Apps HUD guidance
must not send concurrently.

**Previous ARM64 reference (without AR):**
`captures/yandex-hud-arm64-20261015/DenzaHUD-arm64-debug.apk`, SHA-256
`c70d3300f0f1132d71e61d162d43d9a14bb084d464b5f29026a594ff15d1f30f`.
Its size is 219,758,519 bytes, versus 369,062,907 for the universal reference
below: 149,304,388 bytes smaller (40.45%). Only the 64 `armeabi-v7a`, `x86`
and `x86_64` libraries were removed. `comparison.json` proves that every other
non-signing ZIP entry is byte-identical to the reference, including all 22
ARM64 libraries, dex files, manifest, resource table and assets. Both APKs have
the same certificate; debug mode and `com.yandex.passport.denza` remain.
`upgrade-check.json` confirms an emulator update over that universal APK:
same UID, private-file marker preserved and `primaryCpuAbi=arm64-v8a`.
The exact ARM64 APK also passed `acceptance-02 --require-lanes`:
9.92 fps foreground / 9.91 fps hidden, 10 background lane packets,
one lane clear while the route continued, reconnect, triple clear and zero
wire errors. Its first acceptance attempt hit a partial JSON report read;
the host reader now retries that read at most three times, without changing
the APK. The failed report is retained separately. Car acceptance is pending.
This is an ARM64-only package, not an APK for 32-bit ARM or Intel devices.

**Universal reference (same artwork and runtime):**
`captures/yandex-hud-native-arrows-20261015/build-02/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `13047a8ef191ddca0647bd4870553d3ae08da9ec62536f5e751636de39801ab3`.
It keeps the bright roads/account fix and experimental signing key; it adds
native lane-slot output, replaces manoeuvre artwork and removes the blue map
badge. It is not installed or visually accepted on the car in this change.
Android pixel/cache fixtures (`artwork-check-02/`) pass for 1/3/6 lanes,
cyan/grey pixels, reserved cells, 1,000 unchanged frames without re-encoding,
disappearance/reappearance and manoeuvres. The fixture runs in a separate
emulator-only `app_process`, against the installed candidate assets, with no
Binder output or production test hook:

```sh
python3 tools/yandex_hud_build/artwork_test.py --serial emulator-5580 \
  --build captures/yandex-hud-native-arrows-20261015/build-02 \
  --out captures/yandex-hud-native-arrows-20261015/new-artwork-check
```

The actual-route emulator run (`acceptance-02`, `--require-lanes`) passed:
9.87 fps foreground / 9.91 fps hidden, 58/60 distinct frames, 40 lane packets
in the 12-second hidden interval, no wire errors, receiver death/reconnect,
three clear frames, stable stop and process survival. The route starts at
55.7513,37.6090 and ends at 55.7829,37.6290. PNGs include both native slots and
the map without a badge. The first build exposed a leftover lane-corner route
projection; it was removed from `HudMap` as well as the painter, and the final
APK passed the same route with real lanes. The failed `acceptance-01` report is
retained as regression evidence, not an accepted candidate.
`lane-lifecycle/` separately passed real background guidance changing 0 → 3 → 0
lanes while the route/map continued, then cancellation to inactive/zero lanes.

Physical acceptance remains: combined map/lanes, disappearance during guidance,
4–6 lane width, 80×80 manoeuvre scaling, background, motion and N9. The prior
car probe proved three lanes with road class 7; this module leaves that field
unchanged, so the dependence on road class is still unknown.

 The unproved N9 street-shortening experiment was reverted
on 2026-10-15; earlier builds are retained as evidence.

**Alice language check (2026-10-15).** Original and HUD APKs both show the plain
microphone / ordinary recognition widget in English and Alice in Russian. The
same HUD APK opened Alice and started unsilenced MIC after a locale change and
restart; no rebuild was needed. If language is automatic, select Russian
explicitly in Navigator and restart. This is emulator evidence; the car change,
spoken recognition and server answer remain unverified. See
[Alice language selection](../../docs/shortcuts-automation-findings.md#alice-language-selection-in-the-hud-build-2026-10-15).

`captures/yandex-hud-embedded-20261014/build-06/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `16a51953b9c263353d39dff4581ca416169546729553ce12b94515b8ff0926e6`.
`acceptance-06/report.json` passed: 9.76 fps foreground, 9.90 fps background,
59/60 unique PNGs, receiver reconnect, triple clear, repeated stop without a
process restart, zero protocol errors. `active-cancel-06` also verified cancel
while rendering, three black frames and unchanged counters after another stop.
These are emulator observations only.

**Glass design v3 (same day).** `captures/yandex-hud-visual-20261014/build-06/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `e821f4d79f5a52d7f33dea578050893e2ebe8c5927103a9ecb190b9373ab2d14`:
contrast route palette, red-orange vector cursor, own lane glyphs in the free
corner, road events, manoeuvre arrows, black land, loading guard. Its
`acceptance-06/report.json` passed: 9.65 fps foreground, 9.93 fps background,
receiver reconnect, triple clear, zero protocol errors. Recorded design runs
(`run-06` suburban, `run-07` central Moscow) and the frame sheet
`hud-visual-native.png` are in the same directory. Emulator only.

**Installation conflict fix (same day, superseded by the account fix below).** The candidate was
`captures/yandex-hud-install-conflict-20261014/build-01/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `264c6fcb51e49f31dcbf1655e0c30ff194c252a13fe619608410916a3b791dd1`.
Compared with the labelled v3 build-08, only `AndroidManifest.xml` changes
(excluding signing metadata); renderer, assets and native libraries are byte
identical. `check-02/report.json` reproduces `INSTALL_FAILED_DUPLICATE_PERMISSION`
with the old build and installs the new one alongside **two** inert fixture
apps signed with another key, both declaring Yandex's common signature
permission. They can also be installed after the fixed Navigator. Ownership
and signature protection remain with those apps.

`acceptance-01/report.json` also passes with both fixtures installed: route
guidance delivers 80 foreground frames and 120 background frames while the
Navigator is hidden. Receiver reconnect, clear and frame stop after disabling
background guidance pass; zero wire errors and an empty crash log. This uses
the emulator's HUD receiver, not the car's projector.

At the time of the initial conflict report, the owner reported Keyboard and
Kinopoisk on the car; their APKs and the PackageManager failure log were
unavailable. This was a reproduced packaging defect, not a claim that either
app was inspected. Do not remove those apps as a workaround. If installation
still conflicts, obtain the actual `INSTALL_FAILED_*` detail and per-user
package state before changing anything else.

**Car installation confirmed (same day).** The subsequent “package appears to
be invalid” failure was a corrupt USB copy: same byte count, different SHA-256,
a damaged compressed `classes11.dex` and failed signature digest. The known-good
APK was copied to the same USB drive as `DenzaHUD-verified.apk`, read back and
hash-checked, then installed through the stock File Manager / Package Installer
UI on Android 13. The installed `base.apk` matches the candidate SHA-256 above;
installer is `com.android.packageinstaller`. No `adb install`, rebuild or
signature changes were needed. Other Yandex apps remain installed. Navigator
was left unlaunched for the owner's runtime test; HUD behavior on the car is
still unverified. Evidence: `captures/yandex-hud-usb-install-20261014/`.

Reproduce on a **clean disposable emulator only** (refuses a car and existing
test packages):

```sh
python3 tools/yandex_hud_build/install_check.py --serial emulator-5580 \
  --before captures/yandex-hud-visual-20261014/build-08/yandex-navigator-29.8.1-denza-hud-exp.1.apk \
  --after captures/yandex-hud-install-conflict-20261014/build-01/yandex-navigator-29.8.1-denza-hud-exp.1.apk \
  --out captures/yandex-hud-install-conflict-20261014/new-check
```

`test-permission-owner/` has no code, components or requested permissions; the
runner creates a separate fixture key and two package names. Never install
these synthetic permission owners on the car.

## Isolated account type

Car diagnosis on 2026-10-14 confirmed that login fails while saving the account:
`uid 10138 cannot explicitly add accounts of type: com…`. Android registers the
shared `com.yandex.passport` authenticator to Yandex Keyboard (UID 10117); our
fork has a different signer and cannot add accounts under that type. This is
separate from the shared-permission installation fix.

The build now sets `passport_account_type` to exactly `com.yandex.passport.denza`.
The SDK reads this resource into its account-type configuration; authenticator
and sync adapter use the same resource. Android signature checks and the other
apps' accounts are unchanged. Login inside this app is independent of the
shared Yandex account type. Full server login still requires the owner's check.

`account_check.py` refuses non-emulators and existing test/target packages. It
builds an inert authenticator with a different key and an instrumentation APK
with the fork's key. Under Navigator's actual UID, the old build is denied when
adding the shared type; the new build resolves both resource and SDK type to
`com.yandex.passport.denza`, adds/removes a credential-free test account, and is
still denied access to the shared type. Both authenticators coexist. These test
APKs must never be installed on a car; they do not read user credentials.

```sh
python3 tools/yandex_hud_build/account_check.py --serial emulator-5580 \
  --before captures/yandex-hud-install-conflict-20261014/build-01/yandex-navigator-29.8.1-denza-hud-exp.1.apk \
  --after captures/yandex-hud-account-20261014/build-02/yandex-navigator-29.8.1-denza-hud-exp.1.apk \
  --out captures/yandex-hud-account-20261014/new-check
```

Evidence: diagnosis in `captures/yandex-hud-login-20261014/`, passing check in
`captures/yandex-hud-account-20261014/check-01/`. Candidate `build-02` SHA-256:
`f53c0f816c7198633db25a73212bce0269850d9d58b7e9bf02eb0bde813d2348`.
It differs from the installed pre-fix APK only in `resources.arsc`, excluding
signing metadata; all renderer, native and dex content remains byte-identical.

**Installed on the car (2026-10-14).** Current APK is the `build-02` above,
also present on USB as `DenzaHUD-account-denza.apk`. Stock File Manager → USB →
Package Installer → Update completed with `App installed`; the installed APK
matches the SHA-256 above. UID 10138 and existing app data were retained, with
no uninstall, data clear or `adb install` / `pm install` on the car. Android
now registers Navigator's authenticator and sync adapter under
`com.yandex.passport.denza`. Keyboard still owns `com.yandex.passport` and
Kinopoisk still owns `com.yandex.passport.kinopoisk`. Full server login awaits
the owner's check. Evidence: `captures/yandex-hud-account-20261014/car-report.json`,
`car-after-owners.txt`, `car-sync-adapter.txt` and `car-success.png`.

**Hints from the module (same day).** Candidate
`captures/yandex-hud-hints-20261014/build-01/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `556881b28a314e07358aa7e7e9623db7a25f317d1c3ecd0d1d6dfd6643143497`.
Against the installed account-fix `build-02` only `classes17.dex` (the module)
and `assets/denza-hud/theme.json` differ; signer and version code are the same,
so it installs as an update over the car's copy. `acceptance-01` passed: 9.78
fps foreground, 9.94 background, 26 hint packets with a limit in 8 s, receiver
reconnect, triple clear, zero protocol errors. `hint-sequence.txt` follows a
Dolgoprudny route at 25 m/s: metres count down to each manoeuvre, ids 1/2
alternate with the streets, the limit moves between 20 and 60. Emulator only;
what the glass draws from these fields is the car test.

**Brighter roads (same day).** On the glass the map was almost invisible while
route and cursor read well. `captures/yandex-hud-roads-20261014/build-01/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `d23c4054c9396ecc8c042f40b432c9667001e7cd590ddb284a1cb735dc1cb57e`,
differs from the hints build only in `assets/denza-hud/theme.json`: roads 80 %,
yards 54 %, both 1.35× wide. Emulator: theme applied (road pixels
`#C8CCD4`), hint packets flow with zero protocol errors; `roads-variants.png`
compares five steps with the glass model.

**Parked N9 street experiment (2026-10-15).** The owner corrected the premise:
N9 also lacks street names with Denza Apps. String length is therefore unproved.
Commit `9c7a4a5d` (formatter, tests and findings) stays in main's history,
reverted by `b21e293f`; evidence and its APK remain in
`captures/yandex-hud-street-20261014/`. The active branch again sends the full
toponym. The emulator checks proved only shortened field-10 transmission, not
a fix on N9. No car rollback was performed. Do not distribute that parked APK
as the current candidate.

## Before the car trial

The experimental signature prevents an update over the original Navigator.
Plan replacement and restoration of the original app/data before installing;
do not uninstall the owner's current copy as an automatic build step. Disable
Denza Apps' HUD navigation/map sender for the trial: two writers of the same
road/map topics are not arbitrated. Keep other vehicle functions unchanged.
Verify binding permissions, image on glass, native GPS and real background
service behavior, clear on cancel, receiver/process restart, sleep/wake,
long-run memory and CPU/GPU load on the actual IVI. Emulator rates do not prove
vehicle performance.
