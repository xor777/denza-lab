# HUD frames probe

Disposable normal-UID APK for one question from
[hud-projection-findings.md](../../docs/hud-projection-findings.md): can the
windshield HUD show *changing* pictures, and through which channel?

| Channel | Path | Known before | Asks |
| --- | --- | --- | --- |
| `icon` | SOME/IP event `0x8001`, field 8 (maneuver picture) | a still app-drawn PNG shows, also while driving | does the slot follow a new picture every frame, up to what rate? |
| `map` | SOME/IP event `0x8003`, `HudNavigationmap` | this HUD reports no map feature (`0x38B00030 = 65535`) | does the HUD show anything at all? |
| `yandex` | SOME/IP `0x8003`, fed by a crop of Yandex Navigator moved onto the probe's own virtual display | the map window follows 300×180 frames at 15 fps | is a real navigator map readable there? |
| `video` | DiShare `screen_hud`, the probe's `PatternActivity` on `BYD-Mirror` | app video shows, parked | smoothness of a known moving pattern |

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
