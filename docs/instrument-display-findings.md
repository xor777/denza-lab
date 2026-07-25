# Instrument Display Findings

This page tracks the instrument-display scene shared by Mirrors and navigation.
The implementation summary was last checked against the code on 2026-07-25.

## Product architecture

`denza-apps` owns two transparent presentations in one `ClusterSceneService`,
matching the two-layer Denza display composition verified on the car:

- a transparent, positioned `SurfaceView` on
  `shared_fission_bg_XDJAScreenProjection_0` is the base layer for the Yandex
  Navigator virtual display and can occupy the full, left, center, or right
  instrument region;
- a separate `TextureView` presentation on
  `shared_fission_bg_XDJAScreenProjection_1` is the stock-compatible camera
  overlay layer shared by the mutually exclusive AVC side-camera and Camera2
  DVR renderers;
- camera diagnostics use the same overlay display and appear after the user
  presses **Проверить камеры** or chooses a display in hidden diagnostics.

`ClusterDisplayResolver` accepts a saved manual override, the exact known Denza
display name
`shared_fission_bg_XDJAScreenProjection_0`, `cluster`/`fission` name evidence,
real dimensions, and display characteristics. The camera overlay is selected
separately by the exact known name
`shared_fission_bg_XDJAScreenProjection_1`. It excludes IVI, rear/RSE, overhead,
DiShare, and Denza Apps' own virtual displays. An absent or ambiguous match
leaves the feature unavailable instead of guessing a numeric display ID.

## Mirrors behavior preserved in Denza Apps

The migrated product path preserves the standalone Denza Mirrors renderer as
the reference behavior:

- frame width is one third of the real display plus 20 percent;
- camera position is left/right in **Sides** mode or centered in **Center** mode;
- the left camera keeps its wider left crop while the right camera remains
  uncropped;
- processing off is the normal image, while processing on uses the verified
  contrast `1.62`, brightness `28`, and saturation `0.80` matrix;
- independent top and bottom black gradients cover 20 percent of the frame and
  peak at alpha `179`;
- camera shutdown waits up to 250 ms and a failed start is retried no sooner
  than 1,500 ms;
- shutdown dismisses the overlay window first, allowing Android to destroy the
  `TextureView` surface, and calls AVC `freeDisplay()` only afterward. This is
  the lifecycle order used by the standalone Denza Mirrors implementation;
- the colored manual check is temporary and does not start AVC.

The monitor compares the stock left-camera window with the camera-overlay
display chosen by `ClusterDisplayResolver`; the old unconditional
`mDisplayId=4` match is gone. It uses the shared `dishare-bridge` local ADB
client and does not import probe code or the abandoned HUD camera path.

## Navigation projection

Denza Apps owns the navigation `VirtualDisplay` and its `Surface` in the app
process. Short-lived `app_process` commands run under shell UID through the
shared local ADB client and exit after one fixed operation. They can only find,
move, resize, focus, or background a task from the closed navigation allowlist:
Yandex Navigator, Yandex Maps, Google Maps, Waze, and 2GIS. Package identity is
checked again inside the shell-UID boundary before every task mutation. Binder
objects and `Surface` stay in the app process; the shell side exposes only the
fixed task operations listed above.

The persisted map placement has four live-switchable layouts on the verified
`2560x720` instrument display:

- **Full** uses the whole display at `272 dpi`. Its shade leaves 5 percent map
  visibility at the top center, fades fully clear by `272 px`, stays clear
  through the middle, then fades to 5 percent map visibility over `60 px` above
  a `90 px`, 95-percent-black footer. Soft alpha cutouts expose the map in both
  top corners and at bottom center: left/right top radii are `614/512 px`, their
  common depth is `272 px`, and the bottom radius is `600 px` with its center
  `120 px` above the lower edge;
- **Center** uses `Rect(768, 0 - 1791, 720)` at `320 dpi`, with a stronger
  `130 dp` top gradient peaking at alpha `250`;
- **Left** uses `Rect(0, 0 - 1023, 609)` at `272 dpi`, with an alpha-`250`
  radial shade of radius `192 dp` in the inner top-right corner;
- **Right** uses `Rect(1537, 95 - 2560, 619)` at `272 dpi` and no gradient,
  preserving the useful navigation content at the top of that layout.

Changing a placement button while navigation is already projected returns the
task without focusing it, recreates the virtual display, and projects the same
task into the new geometry. Camera gradients are a separate layer and keep
their already verified Mirrors parameters.

The UI state is contextual: **Open**, **To cluster**, then **Return**. The
picker re-reads the installed subset of the navigation allowlist whenever it is
opened, and the selected package is checked again before an automatic launch.
The selected package is saved. Projection sessions stay in memory and end with
the process. The automatic **Map mode** implementation also remains in code,
but its unfinished UI switch is hidden in the current build.

The optional **Steering-wheel button** switch binds the Denza configurable
left-hand key to the contextual navigation action and the front DVR overlay.
It is off by default.
On the tested DiLink 5.1 firmware, host-side
`adb shell getevent -lt /dev/input/event0` identifies the device as
`simulate-keys`: Linux input code `300` (`AUTO_CUSTOM_KEY`) maps through
`/system/usr/keylayout/simulate-keys.kl` to vendor Android key code `321`.
Code `301` (`AUTO_CUSTOM_KEY_LP`) maps separately to Android key code `322` for
the stock long-press settings flow.

The existing Denza Apps accessibility service requests key-event filtering.
When the switch is enabled it consumes both phases of key code `321` and counts
first, non-repeated `DOWN` events in a `500 ms` inter-press window. One press
runs the navigation action after that window; two presses consume the full
sequence and toggle the processed Camera2 DVR overlay. Key code `322` and every
unrelated key remain untouched. When disabled, Denza Apps does not consume
`321`, so the stock action continues normally. Denza Apps does not rewrite the
global `byd_map_package` setting. That stock alternative was observed but
rejected: `CustomKeyHandler` action `7` reads
`byd_map_package=com.byd.launchermap` and sends the package-scoped
`CUSTOM_NAVI_STANDARD_BROADCAST_RECV` broadcast.

On 2026-07-24, a non-consuming probe received key code `321` before
`CustomKeyHandler` and still allowed the stock map to open. A consuming probe
prevented the stock handler from running. With the product switch enabled, the
wheel key moved Yandex Navigator task `131` to Denza Apps virtual display `13`
at `2560x720`; the next press returned it to display `0` and removed display
`13`. The user confirmed both directions worked correctly. These task and
display IDs belong only to that run.

When the hidden automatic mode is enabled in a development build, Denza Apps
checks the selected instrument display once per second. A visible exact
`com.byd.launchermap/com.byd.automap.meter.MeterActivity` task means the stock
**Map** mode is active; its disappearance means the mode was left. The detector
uses live root/display relationships and never stores a task, root, or display
ID. Entering Map projects the selected navigator. Leaving it returns the task
to display `0`, restores normal bounds, and backgrounds it so the previous IVI
scene remains visible. A failed command or missing task releases the map
surface and enters recovery; releasing the virtual display is the final
fallback that lets Android return its task to the default display.

## HUD turn-by-turn guidance

The compact **HUD hints / Guidance on projection** switch is independent of
the full Yandex instrument projection. When enabled, the existing Denza Apps
accessibility service reads only visible, named Yandex Navigator guidance
nodes across every accessibility display. The primary layout exposes the
maneuver description and distance,
remaining route distance, remaining route time, and arrival time. Alternate
named maneuver nodes cover the second Yandex layout. `text_nextstreet` and
`text_jointballoon_nextstreet` are used when Yandex makes a next-road label
visible; otherwise field 10 stays empty instead of repeating the maneuver text.

The app can also use Yandex Navigator's maneuver drawable from its active
navigation notification. An optional `NotificationListenerService` applies
Yandex's public `RemoteViews`, accepts only a validated maneuver `ImageView`,
and normalizes its shape to the white transparent PNG already supported by HUD
field 8. Accessibility remains authoritative for the maneuver, distance, road,
and route summary. Missing notification access, an incompatible layout, stale
artwork, or the internal kill switch all fall back silently to the existing
Canvas renderer; none of those conditions makes the HUD card require action.
When HUD guidance is enabled, Denza Apps checks the listener grant and restores
it through the existing local ADB channel:

```shell
cmd notification allow_listener \
  dev.denza.apps/dev.denza.apps.feature.hud.YandexNotificationArtworkListener
```

The same idempotent repair runs after boot, APK replacement, app startup, and a
listener disconnect. A failed repair remains diagnostic-only and does not stop
guidance or replace the Canvas fallback.
On the tested Yandex build, the foreground notification can collapse to
`contentView=null`, while moving Yandex fully into the background produces the
rich navigation `RemoteViews`. Denza Apps therefore retains the last compatible
notification artwork across a transient minimal notification. A maneuver change
still invalidates old artwork and immediately uses the Canvas fallback.

The same rich notification is now a secondary guidance source while Yandex has
no visible accessibility window. Its named distance, road, remaining-distance,
remaining-time, and arrival fields are read from the rendered `RemoteViews`;
the maneuver resource name is read opportunistically from `RemoteViews`
actions. Reflection failure is harmless: visible Accessibility guidance remains
authoritative and unsupported background layouts still clear after a three
second transition grace. A plain `Навигатор запущен` notification is never
treated as an active route. Notification removal, listener loss, and stale
background data clear the secondary state.

The artwork and background-guidance paths are locally tested and built but
still need a live minimized-route check on the car.

The app-owned navigation `VirtualDisplay` includes `VIRTUAL_DISPLAY_FLAG_PUBLIC`
in addition to `PRESENTATION | OWN_CONTENT_ONLY`. Without `PUBLIC`, Android kept
the projected Yandex window out of `AccessibilityService.getWindowsOnAllDisplays()`
and HUD guidance stopped as soon as the task moved away from display `0`. The
reader still falls back to the default-display window list on pre-Android 11
devices.

The stock HUD road endpoint is
`com.ts.car.someip.service/.manager.SomeIpServerService`, service ID
`3097367205183488`, topic `1127042368241665`. Its protobuf-like
`HudRoadInfoNotifyStruct` accepts total distance (`car2Dest`, field 3), total
remaining time (`timeOfCar2Dest`, field 4), maneuver PNG (field 8), distance to
the intersection (field 9), next road (field 10), navigation state (field 16),
ETA text (field 26), remaining-time text (field 27), and maneuver ID (field
28). The same contract has later candidates for lane recommendations, speed
limits, cameras, route progress, and destination text; those are research
inputs until their stock rendering and Yandex source are independently live
verified.

Yandex Navigator 29.8.1 also contains a structured AndroidX Car App path. Its
own projected guidance constructs a `Trip` from destination address, a
`TravelEstimate` from remaining distance, arrival time, and remaining time,
and a `Step` from next-road/direction-sign text, maneuver metadata, roundabout
exit number, and lanes. Yandex protects that path with an Android Auto
host-certificate allowlist. Denza Apps leaves it untouched and reads the visible
accessibility semantics; there is no OCR or private-code injection.

Static inspection of the installed Yandex Navigator 29.8.1 build on 2026-07-20
confirmed that its projected maneuver mapper reads
`ActionMetadata.getLeaveRoundaboutMetadata().getExitNumber()` and passes that
ordinal to AndroidX Car App. It does not set `roundaboutExitAngle`; the projected
step receives Yandex's regular maneuver image as a separate icon. The normal
Yandex UI also has a named `exit_number_text` accessibility view, and its own
debug fixtures cover at least exits 1, 5, and 7. Denza Apps now reads that view
with Russian/English instruction parsing as a fallback and draws a schematic
roundabout: passed exits are thin branches and the target remains the prominent
arrow. The target moves to the conventional right/straight/left position for
the first three exits; larger exit counts are distributed around the circle.
This is an ordinal aid rather than claimed road geometry because Yandex does
not provide an exit angle in this path. Local tests and the APK build pass for
exits 1, 2, 3, 4, and 7. On-device visual confirmation of the dynamic artwork
and live-road verification on a real roundabout are still pending.

On 2026-07-19 the live Yandex route exposed `56 km`, ETA `19:34`, `53 min`, a
right turn in `20 m`, current speed `0`, and speed limit `20`. Denza Apps bound
the stock SOME/IP service, started the HUD navigation service, and published
the live right-turn update without a crash. The user confirmed that this HUD
firmware renders the arrow, distance to maneuver, scrolling field-10 text,
remaining time, and ETA. It did not render numeric `car2Dest` as total-distance
text. Denza Apps therefore puts formatted remaining route distance in the
confirmed field-26 summary slot instead of the redundant arrival clock, while
field 27 keeps remaining travel time. Field 10 is reserved for a real next-road
name and stays empty when Yandex does not expose one; `car2Dest` is still sent
in meters exactly as in the stock navigation implementation. The firmware adds
a Chinese label beside the summary independently of the strings supplied by
Denza Apps. The final build was then visually accepted with `51 km` in the
former ETA slot and `47 min` alongside it.

The same route was then moved to app-owned display `77` (`1023 x 524`, `272
dpi`). Accessibility registered `Yandex Navi` task `345` on that display, and
Denza Apps continued publishing the live right-turn update (`30 m`, `51 km`,
`48 min`) to the HUD. The user visually confirmed that guidance remained on the
projection while Yandex was shown on the instrument display; the crash buffer
remained empty.

Updates are deduplicated with a five-second heartbeat. If neither a valid
visible route nor a fresh rich-notification route is found for three seconds,
Denza Apps clears the road guidance. Disabling the switch clears, stops, and
unbinds the stock service. Unknown maneuver text is never guessed as a straight
arrow: text and distance may continue, but the directional image is omitted.

## Central IVI split routing

The central screen uses BYD's stock `byd-freeform` split scene. On the tested
firmware it contains a large left root
anchored by `com.android.launcher3` at `Rect(24, 112 - 1680, 1472)` and a small
right root anchored by `com.byd.launchermap` at
`Rect(1704, 112 - 2536, 1472)`. Root and task IDs are runtime state and are not
hard-coded.

The compact **Split screen** switch enables contextual routing through the
shared local ADB client. Normal launches outside the stock split scene remain
fullscreen. The stock application picker stays in one root while the other is
initially empty. Its first selection is moved into the empty root; its second
selection replaces the picker in the remaining root. The choice is derived
from the foreground task transition rather than an application allowlist, so an
already-running task is handled the same way as a new task. The router accepts
only the immediate transition from the visible picker session, reparents the
task with fixed `am stack move-task` and `am task resize` commands, and leaves
the stock divider and controls in charge.

On 2026-07-19 this sequence was live-verified with Yandex Navigator selected
first and RUTUBE second. Navigator appeared in the initially empty small right
root while the picker stayed open in the large left root; RUTUBE then replaced
the picker on the left. Both applications remained visible and interactive in
the stock split scene.

Turning the switch off moves routed non-shell tasks back to the fullscreen root
that contains Denza Apps and restores the stock launcher/map anchors. The toggle
only changes routing; it does not launch an app. The card keeps this mechanism
out of its user-facing text.

Pane identity is not derived from geometry. The stock divider can expand its
launcher root to the full `2560 x 1600` display while Android keeps a separate
fullscreen Home root under the same `com.android.launcher3` package. Denza Apps
therefore matches the exact stock anchor activities and rejects Home roots by
activity type; an ambiguous snapshot is left untouched. Already-restored
anchors are also left in place. On 2026-07-24 this was live-verified with the
stock launcher expanded fullscreen: switching routing off preserved root `3`,
ignored Home root `1`, changed the stored/UI state to off, and produced no task
move error or `com.byd.avc` crash.

Navigation and Simulcast own their task transitions independently of this
router. Starting, projecting, returning, or stopping either feature cancels the
short-lived picker session before issuing task commands. On 2026-07-19 this was
live-verified with Split screen still enabled: 2GIS opened fullscreen, moved to
the app-owned navigation display, and returned through a new fullscreen task
without entering either stock split pane. 2GIS exits its process
during display changes, so navigation revalidates the task and reopens it on the
central display when Android removes the old task.

## OpenBYD research boundary

The locally inspected APK is `com.sr.openbyd`, version `1.0` (version code `1`),
SHA-256
`6eac698da9be9009ae14b9c53acaef070fad160b53286350e27ede08c2fc9669`.
It moves application tasks to a virtual display from a shell process. Its
display selection looks for the first `fission`/`cluster`-like display and does
not coordinate a map layer with side-camera overlays. The inspected APK
contained no project license. We used it only to understand the approach and
copied no decompiled code into Denza Apps.

Denza Mirrors remains the hardware-tested reference for camera geometry and
central placement. OpenBYD is supporting research evidence.

## Recorded car runs and escalation alerts

Local unit tests and `:denza-apps:assembleDebug` pass. The following hashes and
runtime IDs identify individual acceptance runs; they are historical evidence,
not current release metadata. APK
`dbdabeb12811b05889ea8caff52ce19d13892be46033a50fc6b25537b96cb62e`
was installed on the car on 2026-07-18. With **Sides** and processing enabled,
one isolated left cycle and one isolated right cycle both opened and closed the
enlarged image; the monitor ended at `stopped right: window hidden`, the AVC PID
remained `14737`, and the clean post-install crash buffer stayed empty.

Yandex Navigator task `37` was moved to an app-owned `2560x720` virtual display
and rendered visibly on the instrument panel. **Return** moved it back to
display `0`; Android then restored its `2560x1600` bounds and removed the
virtual display. The task was projected again after installing the gradient
build. The AVC PID remained `14737` and the crash buffer stayed empty.

The central split build with SHA-256
`05db25a5d7b22eef04ecccc30568ac0f656a728b77638ec17a4c9faed7b9662f`
was then installed. A normal Yandex Navigator launch stayed fullscreen. From
the visible stock split launcher, Navigator task `37` was routed to the large
left root and Yandex Music task `47` to the small right root; both rendered at
the same time under the stock divider. Switching the feature off moved both
tasks back to the fullscreen root, and switching it on again succeeded without
launching either app. The AVC PID remained `14737`, and the post-fix crash
buffer was empty.

The automatic-navigation build with SHA-256
`e8f7909a2bfaa1ac2013dbac334e36627378cbaf5b3fdb51d035b9cb012a7326`
was installed and accepted on the same car. Switching the stock instrument
theme to **Map** created visible `MeterActivity` task `73` on display `3`; the
live detector created app-owned display `13` and projected Yandex Navigator
task `37` in about 1.4 seconds. Switching back produced a new visible stock
ADAS task `74`, returned task `37` to display `0`, hid it behind the unchanged
car-settings scene, and removed display `13` in about 2.8 seconds. These task
and display IDs belong to that run. The AVC PID
remained `14737`, the crash buffer was empty, and the user confirmed both
directions worked well.

The selectable-layout build with SHA-256
`7fbe9ff97c9775991fbade2c42d5e5d5b0a1920ddafc46facd1372d30b67cae1`
was installed and accepted on 2026-07-19. Center, left, and right layouts were
visually tuned on the car. A live left-to-right button switch recreated Yandex
Navigator task `93` first on `1023x609` display `23`, then on `1023x509`
display `24`, both at `272 dpi`, without a separate Return/Project action.
Those task and display IDs belong to that run. The accepted left-gradient build
rendered task `101` on `1023x609` display `28`.
The accepted Full shade rendered task `123` on `2560x720` display `40` at
`272 dpi`. The AVC PID remained `14737` and the crash buffer stayed empty
throughout.

Hardware-dependent checks still open:

- N9 rear/overhead Simulcast receivers are implemented by contract but need
  `getScreens`, accessibility-tree, and one-receiver-at-a-time captures;
- Mirror Center placement, processing off, manual preview, and camera-over-map
  behavior must be repeated on the car;
- navigation command failure, lost ADB, and APK restart recovery require live
  testing;
- fast left-to-right turn-signal switching is a confirmed crash path while
  Denza Apps owns the AVC display surface. The persistent-Surface candidate did
  not fix it. A locally tested two-phase close now removes the app window before
  releasing AVC and reports `STOPPING` until teardown completes; its visual
  close still needs a live-car check. Automatic opposite-side opening remains
  disabled, so pause-based operation is still the compatibility limitation.

A `com.byd.avc` crash is an escalation alert. Save the evidence, tell the user
once, and continue safe work. Avoid repeating the same suspected trigger until
it has been isolated. Collect:

```bash
adb logcat -b crash -d -v time
adb logcat -d -v time | rg "Denza|PIP2MeterActivity|CompactAlertActivity|Fatal signal"
```

Do not run an installed legacy Denza Mirrors monitor and the Denza Apps monitor
at the same time. After the isolated mirror scenarios passed and the standalone
app was retired, its frozen source moved to
`legacy/denza-mirrors` and was removed from the root Gradle build on 2026-07-19.
Denza Apps has no source or Gradle dependency on it. The unaccepted scenarios
listed above and the rapid side-switch limitation remain open Denza Apps work.

## Failed or research-only paths

- Direct BYD vehicle/light getters are permission-blocked for an ordinary debug
  APK or did not deliver useful callbacks.
- HUD camera streaming through DiShare can render generated or app-accessible
  Camera2 frames, but protected AVC/side-camera frames were black or unavailable.
- The stock cluster projection Binder is package-allowlisted and exposes only a
  left PIP card for `com.byd.avc`; it cannot provide a right-card API to Denza
  Apps.
- Shell `IWindowManager.mirrorDisplay` captured the normal IVI, the stock cluster
  display, a live left-camera display, and the right-camera window on the IVI
  without calling AVC AIDL. Product embedding was rejected: the left stock card
  remained physically composited above the copy, the right copy required the
  stock IVI window and included its controls/text, and the color-transform
  experiment produced black output. The tools remain host-side research only.
- The old `HudDiShareActivity`, map demos, and `.probe` camera paths are not part
  of the Denza Apps product implementation.

## Front-camera source evaluation (2026-07-25)

### AVC surround-view source

The isolated module whose historical Gradle name is `:night-vision-probe`
proved that the parked-car AVC source can be shown without restarting
`com.byd.avc`. The accepted sequence warmed the stock PIP route, transferred
the Surface to the same ordinary `Presentation` fallback and black
`cameraFrame` used by Denza Apps/Mirrors, selected
`SUB_CAMERA_FRONT=2001`, closed the stock card, and rendered the rightmost
`57%` into the centered `1023x720` frame. The AVC PID stayed `12288`.

The source is not a useful long-range vision feed. It is the wide-angle
surround-view/parking composition: bird's-eye occupies the left part and the
front parking camera occupies the right part. Tone mapping can lift shadows,
but it cannot recover distant angular detail that the optics did not capture.

The current host wrapper predates the successful stock warm handoff and must
not be cited as an accepted operator start path. The APK remains short-lived,
has no launcher/boot entry, and is useful only as source-evaluation evidence.

### DVR Camera2 source: next bounded candidate

Android camera `0` identifies itself through the BYD metadata as `dvr` and was
already opened successfully by an ordinary debug APK in an earlier live test.
Current characteristics report `1920x1080` at `30 fps`, aperture `f/1.79`,
focal length `4.71 mm`, and a `6.4 mm` sensor width, implying roughly a
`68-degree` horizontal field of view. This is materially narrower than the
AVM parking source and is the next candidate for evaluation.

Re-verify visually that camera `0` is the forward road-facing DVR view, then
judge useful distance and low-light detail in a raw centered presentation.
The 2026-07-25 live evaluation confirmed that this is a forward DVR view. On
the raw probe its preview needed a `2.0` vertical pixel-aspect correction. A
centered render transform at `2x` provided the requested crop; the camera
accepted a matching `SCALER_CROP_REGION` request but did not visibly apply it
to the preview stream.

Denza Apps `0.5.0` uses the same centered camera frame and render transform.
Its neutral monochrome runtime shader adaptively favors the cleaner green
channel only for low-saturation shadows, uses nine spatial samples with
luminance-edge-aware weights to reduce noise without crossing object edges,
then applies a shadow-only tone curve, a second shadow-contrast curve, and a
soft highlight shoulder. Saturated red or blue lights retain
perceptual-luminance weighting instead of being discarded by the green-channel
preference. The DVR renderer and AVC side-camera
renderer are mutually exclusive on the shared camera overlay. On the current
Camera2/TextureView path the product receives camera `0` already aligned with
the landscape display; an additional `-90` degree transform rotated the result
left after the surface was recreated and was removed. The live stream remains
`1920x1080`, but its content needs the same `2.0` vertical pixel-aspect
correction accepted in the raw probe. In the unrotated product coordinates the
requested centered `2x` crop therefore uses `2x` horizontal and `4x` vertical
render scales.
This first smart monochrome profile was installed and rendered live on the car
without a shader compilation error or missed vsync. The first tone profile
flattened the distinction between deep shadow and penumbra; the second profile
reduces denoising, preserves luminance differences above `0.004`, and applies a
stronger S-curve within the lifted shadow range. A stronger follow-up
(`shadowStrength=0.84`, `shadowContrast=0.68`) was rejected in live twilight:
the frame became nearly uniform gray and a dark car body merged with the shadow
under it even though fine tree texture remained visible. The product returned
to `shadowStrength=0.78` and `shadowContrast=0.55`. The next bounded profile
keeps that global curve and the existing denoising unchanged, samples a second
local scale at a `9 px` radius, and adds at most `0.038` luminance of local
detail across the lower-mid tone window. A second, weaker `0.20` gain reuses
the same local structure from `0.50` through the upper midtones, while the
highlight shoulder now starts at `0.84` instead of `0.72`. This keeps the
accepted deep-shadow lift unchanged and still needs live comparison before any
noise-profile change.

### ADAS cameras: status signals found, no video endpoint found

The system vocabulary contains health/fault signals for front-view `30` and
`120` cameras, but Android CameraService exposes only ids `0`, `1`, `2`, `3`,
and `10`. The installed privileged `AdasAgentService` exposes ADAS
position/event services and BYDAUTO ADAS permissions; no reusable camera
Surface, Camera2 id, or video Binder endpoint was identified. Treat access to
ADAS imagery as unavailable through supported app APIs unless new direct
evidence appears.
