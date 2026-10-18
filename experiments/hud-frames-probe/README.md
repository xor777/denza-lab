# HUD frames probe

## N9 street field (2026-10-16)

`HudStreetActivity` isolates `HudRoadInfoNotifyStruct.nextRoadName`, field 10.
The N9's BydLaunchermap APK, SomeIpService APK and someip_stack.json match the
reviewed corpus by SHA-256 (`captures/n9-hud-street-20261016/baseline/`). Stock
`k.e.j.d.a/c` sends this as a string; the protobuf schema declares field 10.
No font/length hypothesis is treated as established.

On N9 the owner saw the changing 100/200/300 m and a symbol, but all three
native street names were blank. A separate `picture-control` trial confirmed
the arbitrary field-8 PNG: a magenta square with a white Cyrillic `А` appeared
despite field 28 requesting a left arrow. Small `МОСКВА` below an arrow was
not seen; large two-line `МОС` / `КВА` was seen inside the picture slot. Those
image trials are diagnostic controls, **not a native street-field solution**.
The owner explicitly requires the native field. FSE Inspector subsequently
confirmed direct.config=1 and the exact corpus BydHud APK, whose scene does
not create the street-name surface. Full evidence and limits:
[N9 native street](../../docs/hud-projection-findings.md#hud-n9-native-street).

Preconditions: P, speed 0, HUD enabled, no active route or other road sender.
The host reads the service bindings and crash log and announces the trial.
The probe clears the navigation packet, waits 800 ms, then sends at 2 Hz:
100 m + `TEST`, 200 m + `МОСКВА`, 300 m + `北京路`, each for 12 s by default.
The field-8 80×80 marker is a positive control for receipt; distances identify
phases even if text is blank. Stock speed and vehicle properties are untouched.
Expected result is each name beside its distance; visible marker/distances
with blank names falsifies text rendering for these exact packets. A return
code 0 alone is not glass evidence. Normal completion, Stop and destruction
clear three times, withdraw the offer and unbind. No persistent setting changes.

```sh
adb -s 127.0.0.1:5555 shell am start -n dev.denza.hudframes.probe/.HudStreetActivity \
  --es mode sweep --ei seconds 12
# Individual phases: latin / cyrillic / chinese. Each is capped at 20 s.
# Picture controls (200 m + native МОСКВА, still only 20 s maximum):
# image: arrow with small word; picture-control: magenta А versus native ID 1;
# street-card: large white МОС / КВА. These write pixels into field 8 only.
# Optional roadClass 0..10 (default 7), counter 1 or 2 (default 2) isolate stock gates.
adb -s 127.0.0.1:5555 shell am start -n dev.denza.hudframes.probe/.HudStreetActivity --es mode stop
```

Never force-stop a live trial: that skips clearing. If the process dies, run
a one-second `latin` trial to rebind and clear; verify the normal scene and
remove the disposable probe when testing ends. This probe does not alter the
YandexHUD APK, Denza Apps or the parked street-shortening patch.

Evidence: `captures/n9-hud-street-20261016/`, including each installed APK's
hash, live logs and the owner's separate observations. The final tested APK
is `hud-street-card-probe.apk`, SHA-256
`1e08b08aa7b20b6e30767bf16050a390d6dc3741c91be05e8867bb0e65857344`.

## Native lane slot (2026-10-11)

Separate `HudLanesActivity`, ordinary UID, no new permissions. This asks whether the
owner's native HUD renders navigation lanes through service 266 / event `0x8001`.
It never writes vehicle/ADAS settings and never casts video or sends map frames.
Transport `ret=0` proves acceptance at the IVI service, **not** a visible result.
Starting accepts `0` and `13` (`ALREADY_STARTED`): firmware registers another
client when service 266 already has an owner. Stop/unbind removes our client;
other owners keep their offer. Denza Apps can retain an idle offer after a route
stops, so zero alone is an incorrect start criterion. This was the first trial's
failure, before any frame was sent; the trace is in `captures/hud-lanes-20261011/`.

Before a trial: P, speed 0, HUD on; stop Yandex and stock navigation routes and any
other HUD probe. Confirm the normal stock scene. Denza Apps can remain installed:
without a route it does not own the road packet. No persistent HUD key is changed.
Only one session installs or starts the probe. Save the previous probe APK if one
is installed, identify both APKs by SHA-256, and capture crash/service dumps.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew -Pexperiments :hud-frames-probe:testDebugUnitTest :hud-frames-probe:assembleDebug
tools/hud_frames_probe.sh install
HUD_PROBE_OUT="$PWD/captures/hud-lanes-20261011" tools/hud_frames_probe.sh lanes control 12
# Observe each trial separately; do not infer success from its log.
HUD_PROBE_OUT="$PWD/captures/hud-lanes-20261011" tools/hud_frames_probe.sh lanes ids 12
HUD_PROBE_OUT="$PWD/captures/hud-lanes-20261011" tools/hud_frames_probe.sh lanes image 12
HUD_PROBE_OUT="$PWD/captures/hud-lanes-20261011" tools/hud_frames_probe.sh lanes both 12
HUD_PROBE_OUT="$PWD/captures/hud-lanes-20261011" tools/hud_frames_probe.sh lanes both-city 12
tools/hud_frames_probe.sh lanes-stop
```

Each trial sends at 2 Hz for 1–30 s and includes a square-cross in the known field-8
maneuver slot. Field 2 = 2, state 16 = 2, distance 9 = 300 m, maneuver 28 = 11.
Expected native scene: stock speed plus the marker, with any lanes in a separate
area. Seeing only the marker falsifies lane rendering for that exact packet in P;
seeing neither leaves that trial inconclusive. Do not extrapolate to motion.

| Mode | Count 5 | Picture 7 | IDs 29 | Road class 6 | What it separates |
| --- | --- | --- | --- | --- | --- |
| control | 0 | absent | absent | 7 | known road/maneuver channel |
| ids | 3 | absent | `1,255\|0,0\|3,255\|` | 7 | hardware draws its own lane glyphs |
| image | 3 | PNG | absent | 7 | picture slot alone |
| both | 3 | PNG | same IDs | 7 | stock sender's combined inputs |
| both-city | 3 | PNG | same IDs | 6 | road-class gate reported on other BYDs |

Directions are left / ahead / right, with only ahead recommended. PNG is 123×60,
matching three stock layout cells (37 px + 2 px margins each, 60 px high). Left
and right arrows are gray, ahead white; background black. Source: this IVI's
`BydLaunchermap`, `k/e/j/d.java.n`, `LaneAction.java`, and APK resource
`res/layout/layout_hud_laneinfo_arrow.xml` (not the inactive FSE BydHud).

Normal completion, Stop, Back/destroy and replacement by another lane trial all
send three clear packets 120 ms apart (state 1, count 0, road class -1, empty
picture/IDs), then withdraw the service offer and unbind. A force-stop/process
kill skips app cleanup: do not use it while a trial is running. On interruption
run `lanes-stop` and confirm `offer withdrawn`; if the process already died,
start a one-second control trial to rebind and clear. Confirm the normal scene
afterward and uninstall the disposable probe when done.

Disposable normal-UID APK for one question from
[hud-projection-findings.md](../../docs/hud-projection-findings.md): can the
windshield HUD show *changing* pictures, and through which channel?

| Channel | Path | Known before | Asks |
| --- | --- | --- | --- |
| `icon` | SOME/IP event `0x8001`, field 8 (maneuver picture) | a still app-drawn PNG shows, also while driving | does the slot follow a new picture every frame, up to what rate? |
| `map` | SOME/IP event `0x8003`, `HudNavigationmap` | this HUD reports no map feature (`0x38B00030 = 65535`) | does the HUD show anything at all? |
| `yandex` | SOME/IP `0x8003`, fed by a crop of Yandex Navigator moved onto the probe's own virtual display | the map window follows 300×180 frames at 15 fps | is a real navigator map readable there? |
| `video` | DiShare `screen_hud`, the probe's `PatternActivity` on `BYD-Mirror` | app video shows, parked | smoothness of a known moving pattern |

All four were answered, parked, on 2026-09-23: every channel shows moving
pictures; the `map` window appears despite `65535`, but only beside a
"navigating" road packet (`road=1` when no route runs); 300×180 fits and
600×360 is cropped; DiShare video takes the whole projection and ends in D
even at standstill (2026-09-24, exit `605`). See the doc's
[working recipe](../../docs/hud-projection-findings.md#рабочий-рецепт-на-машине-владельца)
and [moving-frames test](../../docs/hud-projection-findings.md#hud-moving-frames).

Each `icon`/`map` frame changes in ways readable off the glass: a hand turning
30°, a digit counting 0–9, and (map) a sweeping bar. Field 9 (distance) and
field 27 carry the frame number as text, so "text updates but the picture does
not" is visible too. The `video` pattern moves continuously: a bar sweeping
once every two seconds, a hand turning once a second, a frame counter and a
millisecond clock.

The APK requests no permission. `SomeIpFramesActivity` binds
`com.ts.car.someip.service` exactly like Denza Apps' `HudSomeIpClient`, offers
service 266, plays the plan, then sends the stock "not navigating" packet and
withdraws its offer. It logs, per rate, frames sent and accepted, achieved
rate, build+fire time and payload size (tag `DenzaHudFramesProbe`). The log
proves only what left the IVI; what the HUD showed is for the person watching.
The `video` channel uses Denza Apps' debug-only `SimulcastDebugReceiver`
(`START_SIMULCAST_TARGET`), so it needs a debug build of Denza Apps on the car.

```bash
./gradlew -Pexperiments :hud-frames-probe:assembleDebug
tools/hud_frames_probe.sh flags          # read-only: gear, speed, HUD type/switch, video input, map feature
tools/hud_frames_probe.sh install
tools/hud_frames_probe.sh icon 1,2,5,10 10
tools/hud_frames_probe.sh map 1,2,5 10
tools/hud_frames_probe.sh yandex 5 60     # Yandex open on the main screen first; yandex-return recovers
tools/hud_frames_probe.sh video-start    # then video-stop
tools/hud_frames_probe.sh uninstall
```

Preconditions: parked in P, HUD on, stock navigation off. For `icon`/`map`, no
active Yandex route (Denza Apps publishes on the same event while one runs,
and the last write wins); for `yandex`, the opposite: Yandex Navigator open on
the main screen, ideally with a route, and not projected to the cluster. The probe does not touch AVC, Denza Apps or any setting.
