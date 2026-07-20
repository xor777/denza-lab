# Instrument Display Findings

This page tracks the instrument-display scene shared by Mirrors and navigation.
The implementation summary was last checked against the code on 2026-07-20.

## Product architecture

`denza-apps` owns two transparent presentations in one `ClusterSceneService`,
matching the two-layer Denza display composition verified on the car:

- a transparent, positioned `SurfaceView` on
  `shared_fission_bg_XDJAScreenProjection_0` is the base layer for the Yandex
  Navigator virtual display and can occupy the full, left, center, or right
  instrument region;
- a separate `TextureView` presentation on
  `shared_fission_bg_XDJAScreenProjection_1` is the stock-compatible camera
  overlay layer;
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
arrow. Local tests, APK build, and on-device PNG rendering passed for exits 1,
2, 3, and 7; live road verification on a real roundabout is still pending.

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

Updates are deduplicated with a five-second heartbeat. If no valid visible
route is found for 1.8 seconds, Denza Apps clears the road guidance. Disabling
the switch clears, stops, and unbinds the stock service. Unknown maneuver text
is never guessed as a straight arrow: text and distance may continue, but the
directional image is omitted.

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
  not fix it; pause-based operation remains a known compatibility limitation.

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
