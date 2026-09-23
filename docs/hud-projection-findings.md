# HUD projection findings

Status: **firmware read and read-only car reads done 2026-09-23; the motion
question and both live picture tests are owed.** Corpus-first read
of the owner's OTA `Di5.1_34.1.33.2605218.1.34.2.3.2605202.2` (the same image
as the split and telematics reads) to answer one question: can Denza Apps put
an arbitrary picture on the windshield projection — a navigator, or the
turn-signal camera? Nothing in this pass touched the car. Agent reports with
full file:line tables are in ignored
`captures/hud-firmware-20260923/reports/` (`hud-stock-content.md`,
`dishare-hud-pipeline.md`, `camera-to-hud.md`); the decompiled trees are next
to them under `jadx/`.

This page owns the HUD as a *display*. The turn-by-turn road packet and the AR
arrow stay in [instrument-display-findings.md](instrument-display-findings.md),
"HUD turn-by-turn guidance"; DiShare's control Binder and the Simulcast drag
stay in [dishare-api-notes.md](dishare-api-notes.md).

## Answer

- **Two stock channels carry pictures to the HUD, and a normal app can reach
  both on the IVI side.**
  - **Map window, SOME/IP event `0x8003`.** A small still picture (about
    300×180, PNG or JPEG, up to 5 fps) that the stock navigator sends while
    driving. It shows inside the stock HUD layout. The IVI does not check who
    sends it.
  - **Full video, DiShare `screen_hud`.** Any app's window, H.264 at 30 fps,
    already seen on the HUD while parked. Whether the HUD shows it while
    moving, and whether it replaces the stock speed/arrow picture, is decided
    in the HUD ECU. Its firmware is not in the image.
- **All three channels show moving pictures on this car** (live, parked,
  2026-09-23 17:39–17:42, see "Moving-frames test"). The maneuver slot follows a
  new picture every frame. The map window appears (on the right) even though
  the HUD reports no map feature (`0x38B00030 = 65535`). DiShare video plays
  smoothly. The maneuver slot is proven to render while driving; the map window
  is the stock navigator's own in-motion channel; video in motion is unknown.
- **A live Yandex Navigator map shows in the map window** (parked, 2026-09-23
  18:50, see "Yandex map in the map window"): a 300×180 crop of Yandex drawn on
  an app-owned display, sent as PNG at 5 fps, appears next to the speed. The
  window needs a "navigating" road packet beside the map.
- **Turn-signal camera:** the only frame source is AVC `initDisplay`, the same
  one Mirrors uses. It can reach the HUD only through DiShare video, so it
  inherits both the Mirrors renderer contention and the unknown motion rule.
  The stock has no camera-to-HUD path.

## What was already proven before this pass

- **Arbitrary app video reaches the HUD.** DiShare
  `start(screen_ivi, [screen_hud], <app>, com.byd.dishare)` from a normal
  `/data/app` APK creates the `BYD-Mirror` virtual display on the IVI and the
  owner saw Bilibili (2026-06) and VK Video (2026-06-28) on the projection,
  parked.
- **Generated frames and Camera2 ids 0/1** were rendered to the HUD by the
  probe era's media-stream path (`legacy/denza-mirrors`,
  `HudDiShareActivity`, `BydMediaStreamServer`).
- **Side cameras are not open to a normal app.** Camera2 ids 2 and 10 throw;
  AVC AIDL `initDisplay()` straight into the DiShare encoder input surface gave
  a black HUD.
- **The compact road packet and the AR arrow** go over SOME/IP to
  `com.ts.car.someip.service` and render while driving; field 8 already
  carries an app-drawn PNG.

## Who draws the HUD picture

The HUD is its own ECU and draws its picture itself. In normal driving the IVI
sends it data, not video:

- **The IVI has no HUD display.** Its displays are `ivi`, the XDJA cluster
  projection `fission_bg_XDJAScreenProjection` and its two `shared_…`
  overlays, plus DiShare's transient `BYD-Mirror`. The DiLink display-name
  table has no HUD entry, and the IVI `framework.jar` has no
  `DirectBufferInterface`.
- **HUD type is a car signal.** `SET_HUD_CONFIG` `0x38B00015`: `1` W-HUD, `2`
  AR-HUD (`DiCarServer` `HudConfigModel`, `:27-29`, `:139-175`). The Navigation
  Fusion and navigation-map feature bits exist only in the AR-HUD branch
  (`:147-168`); this car renders the Fusion arrow, so it reports AR-HUD.
- **Styling is enums; the artwork lives in the HUD.** Arrow type
  DEFAULT/PHOENIX/BUTTERFLY, theme CLASSIC/SNOW, layout SIMPLE/STANDARD/OFF_ROAD
  (`SET_HUD_MODE_CHOICE_SET`). Speed, time, gear and ACC reach it as dedicated
  `…_ARHUD` CAN signals that no IVI app writes.
- **Navigation is one SOME/IP service, `266`,** offered by the IVI
  (`/system/etc/someip/someip_stack.json`, unicast `192.168.195.2`, UDP `52001`,
  SOME/IP-TP, all three events in eventgroup `4353`):

  | Event | Topic | Payload | Pictures |
  | --- | --- | --- | --- |
  | `0x8001` | `1127042368241665` | `HudRoadInfoNotifyStruct` | field 7 lane picture, field 8 maneuver picture |
  | `0x8002` | `1127042368241666` | EHP/ADASIS map path (JSON) | none; no sender found |
  | `0x8003` | `1127042368241667` | `HudNavigationmap{1: string}` | **Base64 map image** |

- **`ICarHudService`** (`com.byd.car.server`, handed out by the exported
  `CarServiceProvider`) only switches settings: HUD on/off, layout, theme,
  arrow, brightness, height, angle and the fusion/map toggles. It has no image or
  content call, and every write passes the `BYDAUTO_*_SET` permission check that
  an app UID fails (the same layering refused the app in the speaker-lift work).
- **The cluster projection manager never reaches the HUD.** `BydProjectionService`
  (`com.example.amapservice`) launches a client's declared activity on the
  `shared_fission_bg_XDJAScreenProjection_0/1` cluster displays, only for nine
  configured system packages pinned to fixed (position, type) pairs
  (`checkPermissions`, `:234-236`; `checkScreenPositionAndContentType`,
  `:242-273`); an unlisted caller gets `3`.
- **PHUD is not on this car.** The newer projection SDK binds
  `com.byd.projection.management/.service.HudProjectionService` when
  `ro.build.byd.phud.type` contains `RCD`/`PHUD`. Neither that APK nor the
  property exists anywhere in the image (system, vendor, odm, product props and
  all init `.rc`), and no app calls the PHUD API.

### The HUD's video input and this car's wiring

The HUD advertises one external video input, which the firmware calls
"intelligent projection". DiShare's `DeviceConfigHelper` (`r/d/o/n/v.java`)
reads it:

- `display_38b` = `SET_INTELLIGENT_PROIECTION_CONFIG` `0x38B0003A` (input
  present) and `available_38b` = `SET_INTELLIGENT_PROIECTION_SWITCH`
  `0x38B00036` (input available when `2`). `display_4c5`/`available_4c5` are the
  same pair for the FSE-integrated HUD variant.
- `getHudAccessType` = `SETTING_HUD_INTEGRATE_CONFIG_FLAG` `0x34C00010`
  (`:236-243`, `:344-350`) selects the receiver branch in `HudScreenControl`:
  - `1`: the FSE renders into a vendor `DirectBufferInterface.getSurface(1)`
    through `com.byd.mirror.MIRROR_CLIENT_SERVICE`;
  - `2`: HUD on the IVI, unimplemented (`55`);
  - anything else: `HudClientActivity` on a local Android `Display` whose name
    contains `hud` (`HudScreenControl.java:259-287`).

A 2026-06-26 car log (`captures/turn-signal/display4-success-20260626-154804.log`,
`:3588-3596`, `:3844`) reads `display_4c5 = 65535` (FSE variant absent),
`display_38b = 1, available_38b = 2` and `getHudAccessType: 3`. So this car has
the standalone AR-HUD ECU, and the receiver on the FSE most likely runs
`HudClientActivity` on an FSE display named `*hud*`, not the DirectBuffer
surface. The FSE firmware is not in the image; one FSE log line
(`getHudAccessType` or `startHudFromLeftDoMain hudDisplay=`) would settle it.
DiShare lists `screen_hud` under device `fse` (`v.java:445-454`); the HUD can
never be a share source.

## The stock map picture on the HUD

The stock navigator (`com.byd.launchermap`) already puts a picture on the HUD
while driving:

- `NaviArHud` renders a separate 800×800 map (map device 3, 5 fps, pitch 45°,
  pbuffer-only EGL surface, `:342-366`, `:596-604`). It is enabled when the HUD
  reports AR-HUD and the platform is not DiLink 5.0 (`:608-612`).
- A screenshot rectangle is read back, scaled and flipped
  (`NaviEGLScreenshotObserver`, `:81-151`). The rectangle depends on the
  resolution code the HUD reports in `0x34C0000B`: 400×300, 300×180, 120×120 or
  300×300. The DiLink 5.1 fallback is 300×180 (`MapController`, `:1587-1634`).
- `k/e/j/d.java` compresses it to PNG, or JPEG when the HUD reports format
  `0x34C00026 == 2` (`:171-180`), and publishes
  `SomeIpData(1127042368241667, HudNavigationmap{Base64})` (`:186-188`).
  `SomeIPDataHudManager` sends only while the HUD switch is not off and resends
  the last map every second (`k/e/j/g/e.java`, `:160-178`, `:290-304`).
- The user map toggle is `INSTRUMENT_HUD_NAVIGATION_MAP_SET` `0x32B1102E`
  (`2` on, `1` off; status `0x38B0002E`, feature present `0x38B00030`). The
  navigator does not read it; the HUD decides whether to show the frames.
- **Nothing on the IVI checks the sender.** `SomeIpServerService` is exported
  without a permission; `startSomeIpService`/`fireEvent` check neither the
  caller, the topic nor the size (`:463-491`, `:551-560`). It is the same
  Binder Denza Apps already publishes the road packet on. The HUD sees one
  service offered by the IVI and cannot tell the navigator from Denza Apps.
  While a stock route runs, both write the same events and the last write wins.
- No IVI code on this path checks speed or gear. The road packet and AR arrow
  Denza Apps sends are already live-proven while driving. Whether the HUD shows
  the map window in motion, and from a sender other than the stock route, is
  decided in HUD firmware that this image does not contain.

In Russia the stock map draws nothing but the car arrow
([stock-map-findings.md](stock-map-findings.md)), so on this car the stock map
window would carry an empty map even if the navigator ran.

**Where a Denza Apps map picture would come from.** Yandex Navigator exports
no route geometry (see "HUD turn-by-turn guidance"). The product already runs
Yandex on its own `Denza Navigation` virtual display for the cluster
(`NavigationProxyClient.createVirtualDisplay`), but that display renders into
the cluster's `SurfaceView` (`ClusterSceneService`, `:516`), which cannot be
read back. Tapping those frames needs a GL relay: the virtual display draws
into a `SurfaceTexture`, which is drawn both to the cluster surface and to a
small offscreen buffer for the HUD. The other option is a map the app draws
itself, which has no Yandex route line.

The navigator also sends what Denza Apps does not: the lane picture (field 7,
PNG/JPEG q80), speed limits and cameras (11–15, 17–18), danger signs (23), POI
JSON (24) and destination (25). It uses field 2 as a flag, `2` normally and `1`
while its junction image is shown (`d.java:137`, `:473-476`). Denza Apps writes
an incrementing sequence number there (`HudSomeIpClient`, `:113`, `:204`, `:337`);
the HUD tolerates it on the live car, but the value is outside the stock domain.

**Unused camera-on-HUD signals.** `SETTING_AVM_SCREEN_PROJECTION_CONFIGURATION`
`0x34C00008` (HUD side, with feedback `0x34C00009`),
`SETTING_HUD_AVM_SCREEN_CASTING_REQUEST_SWITCH_SET` `0x4EF3402C` and
`SETTING_HUD_PROJECTION_STATUS_34C_SET` `0x34C0002C` exist in `DiCarServer`
`Setting.java` (`:189-190`, `:973`, `:979`) and only in constant tables in every
decompiled app. No IVI code drives them. Reading `0x34C00008` would tell whether
the HUD itself advertises a surround-view input.

## DiShare video to the HUD

The HUD path is identical in the June car pull (`1.5.1.1.23102ef`) and this
image (`1.5.1.1.1b1f648`), native libraries included. Paths below are in
`jadx/DiShare/sources`.

- **Source (IVI).** `BYD-Mirror`, created by DiShare (the framework lets only
  UID 1000 create that name, `DisplayManagerService`, `:1100-1103`), flags
  `1293`, 1280×720 dp × IVI density by default, aspect clamped to at least
  16:9 (`r/d/o/d/p.java:110-160`).
- **Encoder.** Hardware H.264 High, 12 Mbit/s CBR, 30 fps cap, repeat the
  previous frame after 33 ms, `i-frame-interval -1` (one IDR, then only on
  request), no low-latency key (`r/d/r/i/o.java:75-94`).
- **Transport.** TCP control on IVI port `13132` (handshake, add stream,
  key-frame request, touch). Video goes over UDP in 1472-byte datagrams with a
  15-byte header, no FEC, retransmission or pacing, DSCP CS6
  (`r/d/r/i/k.java:24-56`, `m.java:43-50`).
- **Receiver (FSE).** Reassembly without a jitter buffer: a lost fragment
  drops the frame and requests an IDR every second until one arrives
  (`r/d/r/c/z0.java:223-302`). The decoder uses `low-latency=1` and decode-order
  output and renders immediately; a GL thread aspect-fits the frame, centred,
  into the output (`x0.java:162-202`, `:423-432`; `v0.java:107-160`).
- **Latency, inferred from the stages:** about 35–80 ms from app draw to the
  FSE output, 60–120 ms glass to glass before the HUD ECU's own delay. The code
  logs `average encode delay`, `sent N frames in X ms` and `FrameAnalyzer`
  timings, which give a live measurement without extra instrumentation.

**Limits as the IVI implements them:**

- **No speed or driving-state check anywhere in DiShare.** Gear is read in two
  places only: on cars flagged overseas any IVI-sourced share is refused
  (`13`) or ended outside P (`r/d/o/n/e0.java:126-134`), and an IVI *receiving*
  a share outside P shows a dialog. The owner's car's overseas flag is not in
  any capture.
- **The HUD's own availability flag.** `screen_hud` is offered only while
  `0x38B00036 == 2`. If the HUD withdraws it, DiShare ends the share with exit
  reason `605` and the stock UI says "AR-HUD simulcasting unavailable".
- A phone call refuses a share (`1` on the IVI, `53` on the receiver);
  first-boot guide mode refuses on the receiver (`51`).
- Exit `598` is a hand-over: a new share names the same receiver, and the HUD
  keeps its client.

**What the HUD shows.** DiShare's only instruction to the HUD is the play state
`SET_FSE_INTELLIGENT_PROIECTION_SWITCH_SET_1B6` `0x1B60A010`: `2` at start,
`1` at the end (`HudScreenControl.java:238-253`, `HudClientActivity.java:30-36`).
It draws no overlay on the HUD video. Whether the video replaces the stock
picture or sits in a window beside it is the HUD ECU's decision.
`HudClientActivity` pads the video to a window on vehicle id 152 and on cabin
styles 4/10 (`:38-56`); this car is model 170.

So a "no video while driving" rule, if this car has one, is in the HUD ECU
(dropping `0x38B00036`, or ignoring its input while `1B6 = 2`) or in the FSE
vendor layer. None of these is in the image.

**App-supplied stream.** `IDiShareApiService` tx 16
`setMirrorSourceClient(IMirrorSourceClient)` makes DiShare skip `BYD-Mirror`
and its own encoder. The app must send H.264 in DiShare's UDP framing to the
address and port that DiShare passes to `IMirrorSourceClient` tx 2, and answer
key-frame requests (tx 1, event 0). Tx 3 *removes* a stream; the probe code
labels it "support" (`legacy/.../HudDiShareActivity.java:1094-1100`). The API
takes no Surface; the probe got one by loading DiShare's native
`SessionServer`. For a product, casting an app window through the control
service is the simpler route.

## The turn-signal camera and the HUD

How the stock camera itself works (trigger, modes, the two-second tail, the
single renderer surface, why the fast switch crashed) is owned by
[instrument-display-findings.md](instrument-display-findings.md), "The stock
turn-signal camera, read from the firmware". What matters for the HUD, read in
`com.byd.avc` and the IVI framework from this image:

- **No stock path puts a camera on the HUD.** The only HUD token in the whole
  AVC package is a `hudDisplay` flag in `SettingsController` (`:63`, `:850-856`)
  that is saved to and loaded from `initSettingParam.json` (`:1156`, `:1231`)
  and read by nobody. The stock picture goes to the cluster card (a display whose
  name starts with `fission_`, `PIPViewAlertController.findDisplayForProjection`,
  `:526`) or to a head-unit alert window. The projection SDK's `ScreenPosition`
  has only cluster regions.
- **A normal app has exactly one handle on the side picture: AVC AIDL
  `initDisplay(surface)`.** The side view is the AVM composite cropped to one
  side, not a Camera2 device. The IVI `CameraManager.isInvalidCamera`
  (`framework`, `:127-146`) limits a non-system app to the third-party camera
  count and `openCameraForUid` throws `cameraId was N` (`:1031-1037`) — the
  exact refusal seen live for ids 2 and 10. `AVCAIDLService.initDisplay` passes
  any `Surface` straight to the renderer with no caller, display or surface
  check (`:56-61`, `:114-118`).
- **The HUD route is therefore the Mirrors renderer on another display.** The
  product `AvcCameraRenderer` already draws the side camera into its own
  `TextureView`. The same `TextureView` inside an activity on DiShare's
  `BYD-Mirror` display (receiver `screen_hud`) is composited by SurfaceFlinger
  and encoded by DiShare like any app window. The 2026-06 black HUD came from
  handing the renderer DiShare's encoder input surface directly; a composited
  `TextureView` does not do that. Inferred, not yet tried.
- **It costs the stock card its picture.** The renderer has one output surface.
  While ours holds it, the stock PIP draws nothing, and every stock side change
  re-grabs it (`PIPViewAlertController.modeChange`, `:352-366`) — the documented
  crash class, which the Mirrors early-teardown and window-only Show contracts
  already manage. A HUD camera would need those same contracts, and if the
  cluster card should keep an image it must be redrawn from our own frames
  (one `SurfaceTexture`, two outputs).
- **Timing, inferred, not measured.** Starting a DiShare session creates the
  virtual display, connects the FSE client and switches the HUD play state; it
  is unlikely to fit inside a turn-signal onset. A HUD camera probably means a
  session kept up for the whole drive, with the HUD showing our stream for that
  whole time, in place of or beside the stock picture (unknown, see above). The
  latency on top of the renderer is the 60–120 ms above.

## Read-only reads on the car (2026-09-23 17:11)

Shell UID over `adb -s 127.0.0.1:5555`, `service call autoservice` transacts
5/7 only, no writes, no install. Build
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226`,
`sys.car.protocol=CANFD`; parked, gear P (`GEARBOX_AUTO_MODE_TYPE` `0x21200038` = 1 on dev 1011), speed
`0.0`. Raw results: ignored `captures/hud-live-20260923/fids-1.json`.

| Signal | dev | FID | Value | Reading |
| --- | --- | --- | --- | --- |
| `SET_HUD_CONFIG` | 1023 | `0x38B00015` | `2` | AR-HUD |
| `SET_HUD_SWITCH_STATUS_FEEDBACK` | 1023 | `0x38B0001C` | `1` | HUD on (`2` is off) |
| `SET_HUD_MODE_FEEDBACK` / `SET_HUD_MODE_CHOICE` | 1023 | `0x38B0000D` / `0x38B00038` | `1` / `1` | STANDARD |
| `SET_INTELLIGENT_PROIECTION_CONFIG` | 1023 | `0x38B0003A` | `1` | video input present |
| `SET_INTELLIGENT_PROIECTION_SWITCH` | 1023 | `0x38B00036` | `2` | video input available, parked in P |
| `SETTING_HUD_INTEGRATE_CONFIG_FLAG` | 1023 | `0x34C00010` | `3` | `HudClientActivity` branch on the FSE |
| `SETTING_AVM_SCREEN_PROJECTION_CONFIGURATION` / feedback | 1023 | `0x34C00008` / `0x34C00009` | `0` / `0` | no surround-view input advertised |
| `SETTING_HUD_PROJECTION_STATUS_34C_SET` | 1023 | `0x34C0002C` | −10011 | not on this car |
| `SET_FSE_HUD_CONFIG_SET` | 1023 | `0x4C50000B` | `0` | not the FSE-integrated HUD |
| `SET_FSE_INTELLIGENT_PROIECTION_{CONFIG,SWITCH}_SET`, `…_1B6` | 1023 | `0x4C50000F`, `0x4C500014`, `0x1B60A010` | `65535` | no data |
| `INSTRUMENT_HUD_NAVIGATION_MAP_CONFIG` | 1007 | `0x38B00030` | `65535` | **map feature not reported** |
| `INSTRUMENT_HUD_NAVIGATION_MAP_STATUS` | 1007 | `0x38B0002E` | `65535` | no map toggle state |
| `INSTRUMENT_ARHUD_NAVIGATION_MAP_RESOLUTION_STATUS` | 1007 | `0x34C0000B` | `65535` | no map resolution |
| `INSTRUMENT_HUD_MAP_FORMAT_34C_SET` / `INSTRUMENT_MAP_ICON_COLOR_FLAG_SET` | 1007 | `0x34C00026` / `0x34C00032` | `0` / `0` | PNG, default colour set |
| `INSTRUMENT_HUD_NAVIGATION_ARROW_STYLE` / `_FLAG` | 1007 | `0x34C00042` / `0x34C00040` | `7` / `3` | |
| `INSTRUMENT_HUD_*` on the cluster message `0x301` | 1007 | `0x3010000D`, `…15`, `…1C`, `…30` | `65535` | the cluster does not relay HUD state |

What this settles:

- **The wiring is live-confirmed:** standalone AR-HUD ECU, one external video
  input that is present and available, fed by the FSE through
  `HudClientActivity` (access type `3`).
- **The HUD does not report the navigation-map feature.** `0x38B00030` and the
  resolution `0x34C0000B` answer `65535` while their neighbours in the same
  `0x38B` and `0x34C` messages answer real values. `HudConfigModel` sets the
  `NAVIGATION_MAP` bit only for `0x38B00030 == 1` (`:147-168`), so the stock
  settings have no map switch for this HUD. The stock navigator does not check
  that bit (`NaviArHud.isSupportArHudMap` only checks the HUD type), so it
  would still send frames. The prediction drawn from this, that the HUD would
  ignore them, was **falsified** the same day: the moving-frames test showed a
  map window. The signal says only what the stock settings offer.
- **The HUD advertises no surround-view input** (`0x34C00008 = 0`).
- The logcat main buffer held 16 seconds (17:11:55–17:12:11) and no stock
  route was running, so the navigator's own HUD log lines were not captured.
  They matter less now: they would report the same `65535` codes.
- One malformed `service call` (arguments collapsed by a zsh loop) went to
  transacts 5 and 7 and returned sentinels; `autoservice` kept PID 128 and the
  crash buffer gained nothing.

## The motion rule: what the IVI image can and cannot say

Searched on 2026-09-23 for any statement of "HUD video only when parked":

- **DiShare** reads no speed and no driving state; the only gear rule is the
  overseas "simulcast only in P" (`%s仅在 P 挡下可用联播`), with `%s` = PAD. Its
  only HUD refusal string is "AR-HUD simulcasting unavailable" (`AR-HUD 联播不可用`),
  shown when the HUD's own availability flag is not `2`.
- **Car settings** (`CarSettingPlatform`, `CarSettingsPluginsPlatform`) have only
  the HUD on/off, height and Fusion-style strings; nothing about video.
- **The owner's manual app** (`BydAutoManual`) ships no content; it loads the
  manual online.
- **The voice assistant** (old pull, `reverse/autovoice-jadx`,
  `CastScreenToFunction`, `:551`, `:615`, `:628`) never casts to the HUD: it has
  no spoken name for it and skips `screen_hud` when it picks a target. No reason
  is given in code.
- The FSE-on-IVI apps in the image (`ScreenProject`, `DeputyMap`) carry HUD
  SOME/IP and AR-lane types only.

So the IVI does not block HUD video in motion, and the rule, if there is one,
lives in the HUD ECU, whose firmware is not in any package we have. The FSE's
half (the `*hud*` display and its vendor layer) is also not in the image; a
read-only probe on the FSE could copy its `/system` framework out over the
existing SMB share.

**What "custom pictures on the HUD" means elsewhere.** BYDMate
([README](https://github.com/AndyShaman/BYDMate/blob/main/README.en.md)) puts
Yandex Navigator guidance on the factory HUD over "the HUD's own factory
channel": maneuver icon, distance, street, arrival time, speed limit, and a
speed-camera icon in place of the arrow. That is the `0x8001` road packet with
an app-drawn picture in field 8, the same path Denza Apps already uses. It is
not a map or video.

## Open questions

- **Is the video input available while moving?** A passive read of
  `0x38B00036` (with speed and gear) during a drive answers it without showing
  anything on the HUD: if it leaves `2` above some speed, DiShare ends any HUD
  share with `605` and that is the firmware's rule. If it stays `2`, the HUD
  may still blank its input in motion; only a shown picture can tell.
- Whether DiShare video replaces the stock speed and arrow picture or sits
  beside it.

## Moving-frames test (run 2026-09-23, parked)

Run 17:39–17:42 by this session, owner watching the windshield. Car in P,
speed 0, HUD on, no Yandex route. Probe APK SHA-256 `2d124e9e…`; Denza Apps on
the car was another session's debug build installed at 17:38:44 (not touched).
Logs: ignored `captures/hud-live-20260923/frames-icon-20260923-173936.log`,
`frames-map-20260923-174027.log`, `video-start.log`.

| Step | IVI side (log) | Owner, on the glass |
| --- | --- | --- |
| `icon 1,2,5,10` × 10 s | 180/180 frames accepted; 10.1 fps achieved; build+fire 5 ms (13 ms at the first); 5.5 KB per packet | the maneuver picture changed frame by frame (hand and digit), not only the distance text |
| `map 1,2,5` × 10 s | 80/80 accepted; 5.1 fps achieved; 11.6 KB per map packet | **a map window appeared on the right**, and the pattern also showed in the maneuver window |
| `video` | DiShare `start` → `{screen_hud=0, screen_ivi=0}`; `PatternActivity` on display 5 `BYD-Mirror` 2560×1440; virtual display sink 60 fps, DiShare preview 30 fps; stop → `finish share ok`, `BYD-Mirror` removed | pattern on the HUD and on the main screen (DiShare's preview), **smooth** |

Before and after every step: gear P, `video_available = 2`, map config
`65535`, crash buffer unchanged (last entry 15:20, unrelated).

**Stop leaves the cast app on the main screen.** After `STOP_SIMULCAST_TARGET`
removed `BYD-Mirror`, the pattern task (#152) was found on the IVI inside root
task #4 in `byd-freeform` mode, still animating; the owner saw it. The probe
was force-stopped by hand, and `video-stop` now does it. A product that casts
its own HUD picture has to close that Activity itself when the share ends.

What this settles:

- **The `65535` map reading was wrong as a prediction.** The HUD renders
  `0x8003` frames although `0x38B00030` does not report the feature. The stock
  settings hide the map switch for this HUD, but the HUD itself draws the map
  window.
- **The maneuver slot is a moving-picture channel** at least up to the rates
  the owner saw change; it is the slot already proven to render while
  driving.
- **DiShare video is smooth end to end** (IVI encode → FSE → HUD) with a
  known pattern.

The owner's answers to follow-up questions:

- **Maneuver slot:** kept up at every rate, 10 fps included ("seemed all
  fine"), by eye.
- **Map window:** to the right of the speed number, in the right third of the
  projection.
- **Video:** took the whole projection; the speed and the stock picture were
  gone for the duration.

So there are two different kinds of picture on this HUD: the SOME/IP slots are
drawn *inside* the stock layout next to the speed, while DiShare video
*replaces* the whole layout.

Not yet known: the map window's highest rate and its native size (only
300×180 at up to 5 fps was sent); what the maneuver window showed during the
`map` step (that step sent no field-8 picture, and the owner does not remember
what was there); and all of it in motion.

### Second run: map window rate and size (17:49–17:51, parked)

Probe updated to `23c4d618…` (adds `marker`: a still square-and-cross in
field 8 beside the moving map, to tell the two windows apart). Logs:
`captures/hud-live-20260923/frames-map-20260923-174956.log` and the two after
it.

| Plan | Accepted | Achieved | Build+fire avg | Map packet |
| --- | --- | --- | --- | --- |
| `map 5,10,15` 300×180 | 300/300 | 5.1 / 10.1 / 15.2 fps | 8 ms | 13.6 KB |
| `map 5,10` 400×300 | 150/150 | 5.1 / 10.1 fps | 11 ms | 20.1 KB |
| `map 5,10` 600×360 | 150/150 | 5.1 / 10.1 fps | 17 ms | 26.5 KB |

The IVI side sustains 15 fps at the stock size and 10 fps at 600×360 with no
refusal; flags and the crash buffer were unchanged throughout. On the glass,
the owner saw the text fields in the maneuver area on the left: the `N fps`
labels (fields 10, 26, 27) updating. **At 600×360 the picture was cropped**:
it did not fit the window, so the HUD draws the frame into a fixed window
without scaling it down. The window's native size lies below 600×360; the
stock sends 300×180 on DiLink 5.1 (resolution code 2).

### Third run: what each window shows, and the window size (18:32–18:33, parked)

Probe `eb6e5cd0…` (adds `grid`: a still 600×360 calibration frame, grid every
20 px, coordinates along the top and left edges and along the middle row and
column, a cross at the centre). `map 5,10,15` at 300×180 with the marker:
300/300 accepted; `grid` at 2 fps for 15 s: 30/30 accepted. Owner's reading:

- **300×180 fits the window whole**; its edges look slightly soft.
- **The maneuver window showed the still square-and-cross**, with every text
  line filled as during real maneuver hints. The two windows are independent:
  field 8 goes to the maneuver window, `0x8003` to the map window. The earlier
  "pattern in the maneuver window" was not reproduced.
- **Grid:** the largest numbers seen were `200` and `300`; the centre cross was
  not seen. Inferred: the HUD anchors the frame at its top-left corner and cuts
  the rest. A window of about the stock 300×180 shows the `100`/`200` column
  marks, the edge of the `300` label drawn at x = 304, and puts the centre
  cross (300, 180) exactly in its bottom-right corner, where it disappears.
  The exact limit is not needed: the stock 300×180 is the size to send.

The owner's question after the reads: can the HUD show *changing* pictures at
all? `experiments/hud-frames-probe` and `tools/hud_frames_probe.sh` play a
known moving pattern into each channel (see the probe README). One session
owns the car for the run; preconditions: parked in P, HUD on, no active Yandex
route, stock navigation off, `flags` recorded before and after, crash buffer
tail recorded before and after. The probe touches neither AVC nor Denza Apps.

| Step | Expected, from the corpus and reads | Falsified by |
| --- | --- | --- |
| `icon 1,2,5,10` | every frame accepted (`ret=0`); the maneuver slot shows the turning hand and counting digit at 1–2 fps at least; the distance text counts | the picture freezes while the text counts (the HUD caches pictures), or nothing shows |
| `map 1,2,5` | frames accepted; nothing on the HUD, because it reports no map feature | any map window appearing |
| `video-start` | the pattern on the HUD within a few seconds; bar and hand move smoothly at up to 30 fps | a black or frozen HUD, or a share refused by DiShare |
| after each | `video_available` still `2`, crash tail unchanged | a new `com.byd.avc`, `autoservice` or `com.ts.car.someip.service` crash |

The `icon` step is the one that matters for driving: that slot is already
proven to render in motion, so if it follows a changing picture, a small
moving picture while driving needs no video path at all. The rate it sustains
bounds what can go there (a mini-map at 1–2 fps, a camera at 10+).

A test of the motion rule itself follows only after these: with the owner
driving and a static card, not moving video, does the DiShare picture stay up?
If the HUD drops it in motion, that is the firmware's safety decision and the
answer, not something to work around.

### Yandex map in the map window (run 2026-09-23, parked)

`tools/hud_frames_probe.sh yandex [fps] [secs] [cx] [cy] [cw] [invert]`, probe
`db759df1…`. The probe creates a `Denza Navigation` virtual display (960×576,
160 dpi, the product's name and flags) and sends a crop of it, scaled to
300×180, to `0x8003` only; the road event stays with Denza Apps' guidance.
The script moves the running Yandex Navigator task onto that display and back
with the shell half of Denza Apps' own cluster projection (`ClusterProxyMain`
from the installed APK: `find-task`, `task-display`, `projection-origin`,
`create-root` for a split child, `project-task`, `return-task`), saving the
origin first and returning on Ctrl-C. The probe saves the full frame (crop
box in red) and the sent crop every 2 s; the script pulls both.

Preconditions: Yandex Navigator open on the main screen (not projected to
the cluster), ideally with a route so the left side carries real hints; P.

| Expected | Falsified by |
| --- | --- |
| `project-task → true`; Yandex leaves the main screen; the probe's preview shows its map with the red crop box | `false`, or a black/unchanging preview (`repeated` ≈ `sent`): Yandex does not draw on an unseen display |
| the HUD's right window shows the cropped map at 5 fps next to the speed | a blank window |
| after `secs`, Yandex back in its original place (split pane included), map window blank | Yandex elsewhere, a lost split pane, or a stale map left on the HUD |

First run, 18:44–18:45: Yandex task 167 (in split root 3, companion task 141
in root 2) went to display 6 through a new projection root 169 and came back
(`return-task → true`, display 0 after). Yandex kept drawing on the unseen
display (night theme; saved frames
`captures/hud-live-20260923/20260923-184558-yandex-{full,hud}.png`), the crop
around the car arrow was right, and 324/324 map frames were accepted at 5 fps
(grab+crop+PNG+fire 16 ms, 27–34 KB, opaque). **The HUD showed nothing.** Yandex
had no route, so nobody sent the road event; every map-window run that did
show a picture sent a "navigating" road packet beside it. Working hypothesis:
the HUD shows the map window only in navigation state. The probe now has
`road=1` to send that packet itself when no route runs. No crash.

Second run, 18:50–18:51, same crop, `road=1` (the probe sends a
"navigating" road packet beside each map frame; still no Yandex route):
326/326 map frames accepted at 5 fps, 16–18 ms per frame, ~34 KB; Yandex went
to display 7 through root 171 and back (`return-task → true`). **The owner saw
the Yandex map in the HUD's map window, as intended.** This confirms the
hypothesis: the map window shows only while the road event says
"navigating". A live Yandex map on the windshield, next to the stock speed,
needs no system privilege: an app-owned display, a crop, and two SOME/IP
events. Parked only so far.

### Using it: one task, one display, but the picture can be copied

A task lives on one display, so the probe's way of moving Yandex away from the
main screen is not a product shape. The picture does not need the task,
though. Two ways to feed the map window while Yandex stays where the owner
wants it (design notes, not built):

- **Yandex on the cluster.** Denza Apps already runs Yandex on its own
  `Denza Navigation` display for the cluster; that display renders into the
  cluster's `SurfaceView`. A GL relay (the display draws into a
  `SurfaceTexture`, which is drawn to the cluster surface and to a small
  offscreen buffer) gives the HUD the same frames. No new permission.
- **Yandex on the main screen.** Mirror the main display with
  `MediaProjection` into a small `ImageReader` and crop Yandex's pane (its
  bounds are already known from the accessibility reader). On this firmware
  the consent dialog is skipped for a package whose `PROJECT_MEDIA` app-op
  (op 46) is allowed: SystemUI's `MediaProjectionPermissionActivity` (`:51-55`)
  asks `MediaProjectionManagerService.hasProjectionPermission`, which returns
  true for `CAPTURE_VIDEO_OUTPUT` or op 46 `MODE_ALLOWED` (`:229-237`). Denza
  Apps' local ADB can set that op once, as it already grants accessibility.
  What is captured is what is visible: with another app over Yandex, the HUD
  picture must pause, which the accessibility reader can tell.

Either way the road event must say "navigating" while the map shows; with a
Yandex route that is Denza Apps' own guidance, without one the app would send
it itself.
