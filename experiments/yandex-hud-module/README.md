# Embedded Navigator HUD experiment

Owner-only **debug** build of Navigator 29.8.1 with an independent 300×180 HUD
map and Binder/SOME-IP output. Branch: `experiment/yandex-hud-renderer`.
This module is embedded into the pinned owner APK by a host build tool; it is
not an independently installable app or a dependency of Denza Apps. The car
has not been changed by these build/test tools. Detailed evidence belongs to
[HUD findings](../../docs/hud-projection-findings.md#hud-yandex-embedded).

## Runtime

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
- `HudMap` owns a separate offscreen MapKit window. Geometry is created once per
  route; progress hides its completed prefix. Conditions events invalidate the
  traffic palette and the route's road events, with a 30-second fallback.
  Outdated/mismatched traffic uses UNKNOWN. White manoeuvre arrows
  (`addArrow`) mark the next two windshield manoeuvres; MapKit accepts arrows
  only in order along the line, so an earlier announcement rebuilds the line.
  Road events (`getEvents`: tags and the posted limit in m/s), speed bumps and
  railway crossings are cached per route and projected with `worldToScreen`
  after the camera move, nearest first. While MapKit still shows its loading
  placeholder (light grid, or the night clear colour with grid lines) the frame
  carries no map. It never moves the main map, sets a location provider,
  computes a second route or changes global MapKit start/stop state.
- `HudPainter` owns two bitmaps and an overlay and executes the theme's drawing
  templates op for op as `research/yandex-hud-renderer/hud-scene.js` paints them
  (exported by `tools/yandex_hud_build/theme.cjs`); it computes only placement:
  the lane corner (with hysteresis inside one sign) and which road events fit.
  The design itself is described in the
  [renderer README](../../research/yandex-hud-renderer/README.md#what-it-renders).
  Black is the HUD's optical background convention, not true PNG transparency.
- `HudModule` polls at up to 10 fps while active, 2.5 Hz while off. MapKit and
  composition stay on main; PNG encoding and Binder calls share one worker.
  Only one frame is in flight, with no frame queue. An epoch invalidates work
  already encoding after cancellation. Drawing resources are released after
  that worker finishes. The map-only main-thread timing counter excludes idle
  and status-only frames.
- Hidden Navigator continues rendering **only** if stock background guidance
  is enabled and guidance remains resumed. No Activity is kept visible; the
  existing navigation service keeps its own process alive. Disabled background
  guidance, cancellation and finish clear output. Missing/coarse/stale position
  removes the moving map; no frozen route remains on a valid running loop.

## Transport

`HudTransport` follows the wire contract in
[`HudSomeIpClient.java`](../../apps/denza-apps/src/main/java/dev/denza/apps/feature/hud/HudSomeIpClient.java).
It binds explicitly to `com.ts.car.someip.service` with the caller's package as
intent type and `ts.car.someip.sdk.ISomeIpServerInterface` as Binder descriptor.
Transactions 4/5 start/stop service `3097367205183488`; transaction 6 sends the
exact handle/topic/instance/length/byte-array layout. Topic `1127042368241665`
is the road packet: 2 = 2, 16 = 2 (navigating) and, while a moving map is
drawn, the hint in Denza Apps' field order — 3/4 remaining metres/seconds,
8 a 192×192 white manoeuvre picture (the theme's lane glyphs at 6×, roundabout
with its exit), 9 metres to the manoeuvre, 10 its street, 11 the posted limit
in km/h, 26/27 «3,4 км» / «10 мин», 28 the stock manoeuvre id. Everything comes
from guidance itself: windshield manoeuvres (`Action`, toponym, the roundabout
exit on the manoeuvre that leaves it), the route's `RoutePosition`
(`distanceToFinish`/`timeToFinish`) and `getSpeedLimit()` (m/s, 16.67 → 60).
`StreetName` compacts the toponym before field 10 in both foreground and
background: standalone Russian/English road types become `ул.`, `просп.`,
`пер.`, `ш.`, `наб.`, `пл.`, `б-р`, `пр-д`, `St`, `Ave`, `Blvd`, `Rd`,
`Ln`, `Hwy`, `Sq`. The proper name is not truncated; Unicode whitespace is
normalized and the result is cached until the input changes. No Activity,
TextView, accessibility or native UI presenter is needed. This is an explicit
compatibility dictionary, not a claim to reproduce every Yandex native rule.
Field 28 follows the stock BYD navigator: 13 enters and 24 leaves a roundabout
(Denza Apps sends 25), 48 finish, 45 waypoint. Without these fields the HUD
filled the manoeuvre window with its own Chinese captions. A changed hint goes
out at once, at most four times a second, else every 500 ms. Rerouting and a
lost position send only 2/16. Topic `1127042368241667` carries field 1 =
Base64 PNG, without the road envelope. **No vehicle-property or DiShare writes**:
the car's own speed sign (cluster and HUD) needs the `BYDAUTO_*` Setting writes
Denza Apps makes through local ADB; this process has no such permission, so
only field 11 carries the limit.

A connection generation rejects late callbacks. Binding has a three-second
retry deadline; failures retry after one second. Reconnection uses the next
fresh frame. If rendering stops providing frames for 1.5 seconds, the output
worker clears the slot. Normal shutdown sends black PNG + inactive road three
times at 120 ms intervals, then stops/unbinds at 350 ms. As with any in-process
watchdog, this cannot send a clear after process death or while the Binder
thread is blocked; receiver-side expiry and kill/restart behavior still need
car acceptance.

## Build

Requires JDK 17, Android SDK platform 35 / build-tools 36.0.0, Node, `apkanalyzer`
and the dexlib2 bundled in local JADX 1.5.5. Paths can be supplied via
`JAVA_HOME`, `ANDROID_HOME`, `--jadx-jar` and `--apk`.

```sh
python3 tools/yandex_hud_build/build.py \
  --out captures/yandex-hud-embedded-20261014/new-build
```

The input must be the owner's single APK 29.8.1 / code 739494300, SHA-256
`3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3`.
The script adds our module in a new dex and patches exactly two methods.
Because original `classes12.dex` already has 65,536 method references, its
patched Guidance class is relocated into a second new dex. No foreign source
or binary is committed.

Manifest changes are limited to `debuggable=true`, version name
`29.8.1-denza-hud-exp.1`, the label «Яндекс HUD» on the application and on
`MapActivity` (the launcher alias has no label of its own and Android takes
the target activity's, so the shortcut, the app list and recents show it), and
one package-visibility query for the HUD service. The common declaration
`<permission name="com.yandex.permission.READ_CREDENTIALS">` is removed: a fork
with our signer must not claim ownership of a signature permission shared by
official Yandex apps. Its `uses-permission` request, all component guards and
the package-specific AndroidX signature permission stay unchanged.
The original package and version code stay. Android SDK XML decoding verifies
that all other manifest declarations match; the builder verifies the signature,
16-KiB alignment and byte equality of all original ZIP entries except the two
patched dex files, manifest and `resources.arsc`. The sole resource change is
`passport_account_type` → `com.yandex.passport.denza`; an independent full `aapt2`
dump comparison rejects every other resource change. Resource IDs, XML
references and native libraries stay unchanged. `build-report.json` records
APK/module hashes and the resource check. The builder first runs the host
module and street-name tests, including foreground/background field-10 parity.

The dedicated local experimental key lives in ignored
`captures/yandex-hud-signing/experiment.p12`. It is **not Yandex's key**. Passport
already permits debug applications with a different signer; its check is not
patched. The release-mode attempt exits with a signature mismatch. The debug
candidate builds routes without signing in; account login, SSO, purchases and
signature-dependent integrations are unverified.

## Emulator checks

`test-receiver/` is a disposable fake of the IVI Binder endpoint. It validates
session state, topic IDs, protobuf, PNG dimensions and clear frames, parses the
road packet field by field (a clear carries nothing else; a manoeuvre has a
192×192 picture, metres and an id; a limit is 5–130 by 5), keeps the last hint
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
adb -s emulator-5580 install captures/yandex-hud-embedded-20261014/new-build/yandex-navigator-29.8.1-denza-hud-exp.1.apk
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
  --apk captures/yandex-hud-embedded-20261014/new-build/yandex-navigator-29.8.1-denza-hud-exp.1.apk \
  --out captures/yandex-hud-embedded-20261014/new-acceptance
```

The runner verifies QEMU and the installed APK hash. It checks foreground and
hidden rendering with actual moving PNGs, receiver-process death/rebind,
background-disabled clear, stable stopped counters and repeated cancellation
without a Navigator restart. Inspect saved PNGs as well as `report.json`.
`src/test/.../ModuleTest.java` covers exact wire bytes, preview/cancel/resume,
background preference and stale/coarse position on the host without the APK.
The renderer's Node tests (`node --test research/yandex-hud-renderer/*.test.cjs`)
cover the design and the theme contract, not native transport.

## Current candidate (2026-10-14)

The latest candidate is the **N9 compact street experiment** below. The earlier
embedded, packaging and glass-design builds are retained here as evidence.

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

**N9 compact street experiment (same day).** The owner reports that N9 shows
the street with Denza Apps but not with the embedded module. Length is a
hypothesis, not a measured N9 limit. The candidate formats the route's full
toponym with one `StreetName` instance in foreground/background, without reading
or keeping an Activity. It does not create a native UI presenter. The explicit
dictionary above approximates common abbreviations; exact parity with every
native Yandex rule is unproved. AR fields, roundabout IDs and the renderer stay
unchanged so this trial tests the street hypothesis alone.

APK: `captures/yandex-hud-street-20261014/build-02/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `44794e93e93c049b6a84174ee3fd99fd3789d5d0d4839dfeb1f9ff77503c098b`.
Only `classes17.dex` differs from the brighter-roads candidate (excluding signing
metadata). The original experimental signer and `com.yandex.passport.denza`
remain, so this updates the existing fork. 36 module checks and 30 string cases
pass; `delta.json` and build evidence are in `captures/yandex-hud-street-20261014/`.
`acceptance-03` passes: 9.71 fps foreground, 9.93 background, `Mokhovaya St`
in field 10 in both modes and after receiver restart, triple clear and stopped
frames when background guidance is disabled, zero wire errors and empty crash
log. The host runner now retries a JSON decode up to five times when it reads
evidence during a truncate/write; all acceptance conditions remain unchanged.
The passing run needed no retries. Transfer copy: `DenzaHUD-N9-streets.apk`
in that directory, with a SHA-256 sidecar. No car installation or N9 acceptance
is claimed.

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
