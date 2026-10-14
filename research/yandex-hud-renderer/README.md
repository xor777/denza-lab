# Yandex HUD renderer experiment

An independent HUD map **inside the running Navigator process**, using its
existing MapKit and current Guidance. Full navigation stays in the original
main-screen Activity. This is a working emulator experiment, not a standalone
companion APK, a modified Navigator release, or a car-tested feature.

Findings and access boundaries live in
[HUD projection findings](../../docs/hud-projection-findings.md#hud-yandex-independent-renderer).
The branch starts from `d6253bae`, `experiment/yandex-hud-renderer`.

## What it renders

`renderer.js` creates `OffscreenMapWindow(300, 180, 1.0)`, configures only that
window, reads the one live `GuidanceBinding`, and draws its exact route geometry.
The camera follows the guidance location/heading at zoom 17, tilt 0, with its
focus at (150, 132). Gray roads, cyan route, white car marker, black background;
no map labels, POI, buildings or navigation UI. Black is optically transparent
in the owner's known HUD slot. It is **not alpha transparency**.

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

Inspect PNGs and `report.json`. `navigator-route` means both route and location
were available; `base-map-only` is explicitly **not** a synchronized route.
Normal stop clears only our map objects and releases our references, leaving
MapKit and the original map running. Native object reclamation and long-term
memory growth are not measured.

Expected observations:

1. Original Navigator remains in `MapActivity`, with its original map style and
   working guidance; save an emulator screenshot while the trial is active.
2. PNGs contain independently styled roads, the current route and our marker;
   route segment indices progress during simulation. Check actual pixels:
   successful RPC calls and `navigator-route` alone do not prove visible output.
3. Add `--cancel-route-after 5` with `--simulate --seconds 12` to cancel the
   test route in Navigator: subsequent frames must have no old line or arrow.
4. Cleanup must finish without `error` or `cleanup_error` in the report.

`--test-native-alpha` is a **negative-test reproducer**, not a supported theme.
In run-07 it made the native map and route invisible and left only the Canvas
arrow. The working output uses HUD black. Rerouting and location loss/staleness
need further acceptance; route lines currently include the passed section too.

Recorded evidence is under `captures/yandex-hud-renderer-20261014/`, including
the APK/Dex inspection, logs of failed iterations, final PNGs and source hashes.
Run-09 produced 300 PNGs in 30.00018 s (9.99994 fps), median preparation 8 ms.
299 frames had a route; the first awaited the simulation location. There were
291 different PNGs and segment indices 0–19. Run-10 cancelled the route after
5.05 s: 50 route frames followed by 70 base-map frames, with no cyan route or
car marker in the final PNG. `MapActivity` remained resumed; both trials ended
without an execution or cleanup error.
These are emulator-to-host measurements, not car CPU/GPU or HUD refresh claims.

Close only this disposable emulator and remove only this forward after testing:

```sh
adb -s emulator-5580 forward --remove tcp:27043
adb -s emulator-5580 emu kill
```

## Further work

The missing deployment decision is process access on the actual IVI. The
ordinary companion APK has no public access to this native Guidance instance.
An embedded module in an explicitly agreed experimental Navigator build could
publish frames to a companion/HUD receiver. That requires a reviewed APK patch,
signing/install/restore strategy and a separate car trial. This experiment does
not patch or replace the owner's Navigator, nor claim that Frida/root is
available on the car. Product APKs, Gradle modules and HUD senders are unchanged.
