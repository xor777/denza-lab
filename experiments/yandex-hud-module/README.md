# Embedded Navigator HUD experiment

Owner-only **debug** build of Navigator 29.8.1 with an independent 300×180 HUD
map and Binder/SOME-IP output. Branch: `experiment/yandex-hud-renderer`.
This module is embedded into the pinned owner APK by a host build tool; it is
not an independently installable app or a dependency of Denza Apps. The car
has not been changed. Detailed evidence belongs to
[HUD findings](../../docs/hud-projection-findings.md#hud-yandex-embedded).

## Runtime

- Two small hooks capture application context at MapKit initialization and the
  completed `GuidanceBinding` instance. Deferred work runs on the main thread.
  The original main Activity, navigation engine and foreground service retain
  their lifecycle. No Frida, root, laptop connection or screen capture is
  required by the resulting APK.
- `NavigatorSource` reads the actual selected/resumed route, native position,
  background-navigation preference, traffic and plain lane signs. It checks
  route/resume **before** accessing automotive route status: the native API is
  undefined before a route exists and can abort debug ART.
- `HudMap` owns a separate offscreen MapKit window. Geometry is created once per
  route; progress hides its completed prefix. Conditions events invalidate the
  traffic palette, with a 30-second fallback. Outdated/mismatched traffic uses
  UNKNOWN. It never moves the main map, sets a location provider, computes a
  second route or changes global MapKit start/stop state.
- `HudPainter` owns two bitmaps, cached drawables and an overlay. The build
  exports the existing `research/yandex-hud-renderer/hud-scene.js` theme into an
  APK asset. Roads, brighter stock traffic colors, golden stock cursor and
  compact blue lane badge remain; no maneuver panel. Black is the HUD's optical
  background convention, not true PNG transparency.
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
activates the navigation slot (road fields 2=2, 16=2); topic
`1127042368241667` carries field 1 = Base64 PNG, without the road envelope.
No maneuver, speed, vehicle-property or DiShare writes are made.

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
`29.8.1-denza-hud-exp.1`, and one package-visibility query for the HUD service.
The original package and version code stay. Android SDK XML decoding verifies
that all other manifest declarations match; the builder verifies the signature,
16-KiB alignment and byte equality of all original ZIP entries except the two
patched dex files and manifest. `build-report.json` records APK and module
source hashes. Resources and native libraries are unchanged. The builder first
runs the 16 host assertions.

The dedicated local experimental key lives in ignored
`captures/yandex-hud-signing/experiment.p12`. It is **not Yandex's key**. Passport
already permits debug applications with a different signer; its check is not
patched. The release-mode attempt exits with a signature mismatch. The debug
candidate builds routes without signing in; account login, SSO, purchases and
signature-dependent integrations are unverified.

## Emulator checks

`test-receiver/` is a disposable fake of the IVI Binder endpoint. It validates
session state, topic IDs, protobuf, PNG dimensions and clear frames; records a
bounded 60-frame ring. **Never install this APK on the car.** Both its service
and the candidate's private-file simulation harness are restricted to
`ranchu`/`goldfish`. The candidate exposes no new command receiver or socket.
Simulation commands are consumed once; repeated stop checks IDLE first because
native `stopSimulation()` otherwise aborts.

```sh
python3 tools/yandex_hud_build/receiver.py captures/yandex-hud-embedded-20261014/new-receiver
adb -s emulator-5580 install captures/yandex-hud-embedded-20261014/new-receiver/emulator-only-hud-receiver.apk
adb -s emulator-5580 install captures/yandex-hud-embedded-20261014/new-build/yandex-navigator-29.8.1-denza-hud-exp.1.apk
```

Use a disposable root-capable emulator; start a route in Navigator's ordinary
UI first. Then:

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
The original renderer's 14 Node tests remain a visual/algorithm baseline, not
native transport acceptance.

## Current candidate (2026-10-14)

`captures/yandex-hud-embedded-20261014/build-06/yandex-navigator-29.8.1-denza-hud-exp.1.apk`,
SHA-256 `16a51953b9c263353d39dff4581ca416169546729553ce12b94515b8ff0926e6`.
`acceptance-06/report.json` passed: 9.76 fps foreground, 9.90 fps background,
59/60 unique PNGs, receiver reconnect, triple clear, repeated stop without a
process restart, zero protocol errors. `active-cancel-06` also verified cancel
while rendering, three black frames and unchanged counters after another stop.
These are emulator observations only.

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
