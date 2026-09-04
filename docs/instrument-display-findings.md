# Instrument Display Findings

This page tracks the instrument-display scene shared by Mirrors and navigation.
The implementation summary was last checked against the code on 2026-09-04;
live-car evidence is current through 2026-09-04.

## Product architecture

`denza-apps` owns two transparent presentations in one `ClusterSceneService`,
matching the two-layer Denza display composition verified on the car:

- a transparent, positioned `SurfaceView` on
  `shared_fission_bg_XDJAScreenProjection_0` is the base layer for the Yandex
  Navigator virtual display and can occupy the full, left, center, or right
  instrument region;
- a separate `TextureView` presentation on
  `shared_fission_bg_XDJAScreenProjection_1` is the stock-compatible camera
  overlay layer used by the AVC side-camera renderer; the retired Camera2 DVR
  renderer no longer ships in Denza Apps;
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

## App-owned instrument dashboard

Added 2026-08-25, and run on the car the same evening. The first live run is
recorded under "What the first live run changed" below.

The same base presentation now also hosts a dashboard of this app's own
instruments, as a plain `View` drawing on a `Canvas`. It is a third mode
alongside the map and the camera overlay, and it is by far the cheapest of the
three: **no virtual display is created and nothing is projected.** The map path
only ever produced a `Surface` because a `MapSurfaceConsumer` had been staged
for it; with no consumer registered, `dispatchMapSurface` is a no-op and
`NavigationProxyClient` is never reached. So the dashboard needs no shell
command, no task move, and no vendor surface.

Entry points are `ClusterSceneService.showDashboard(context, placement)` and
`hideDashboard(context)`, on the actions `dev.denza.apps.cluster.SHOW_DASHBOARD`
and `...HIDE_DASHBOARD`. The dashboard layer sits **after** the shade in the
presentation's `FrameLayout`, so the shade never darkens it - the dashboard
protects instrument data by placing its blocks off them, not by dimming itself.

### What the first live run changed

2026-08-25, owner at the wheel, car parked. Three things came back, and all three
were things no unit test and no board could have found.

**The surface was transparent, and that was wrong.** It was drawn as islands over
the vehicle's own graphics, each with a radial scrim under it for legibility.
On the panel that reads as our numbers lying on top of somebody else's, and the
owner's instruction was immediate: opaque, black. So the renderer now fills its
whole box with `#000000` first — pure black rather than the design system's
`BACKGROUND` (`#07080A`), because the panel's own ground is black and three per
cent of grey is a visible rectangle there while it is invisible on a board viewed
in a browser. The scrims went with the thing they were compensating for.

The keep-outs in `ClusterDashboardLayout` still matter, but for the other half of
their reason: the vehicle draws **above** this window as well, so a block placed
under stock graphics is hidden by them rather than covering them. An opaque
background is also the cheapest way to finally measure that boundary, which is
the capture this page has been asking for: whatever the vehicle still draws is
what is left over the black.

**8191 об/мин on a stopped engine.** Engine speed answered `0x1FFF`, the CAN
"signal not available" pattern, and the panel printed it as a number. Full
account, and the general rule it produced, in
[vehicle-data-findings.md](vehicle-data-findings.md) under "A resting reading is
not the same as a resting ECU".

**A half-full tank in the alert colour.** The id read as the vehicle's low-fuel
alarm had flipped `0` → `1` while the tank stayed at `53 %`. It is out of the
allowlist. The remaining experimental fuel figure also left the product with
the retired head-unit pages; the stock cluster owns level, range and alarm.

The resolver picked display **`4`** for this run, not `3`.

### How the driver reaches it

Wired into the product on 2026-08-25. The dashboard is **one of the choices in
the navigation picker**, beside Яндекс Навигатор and the rest, under the label
`Приборы`. That is the honest place for it: the picker answers one question -
what the driver's display shows - and this is one more answer to it, not a
second feature that would have to explain how it relates to the first.

It is addressed by this app's own application id (`BuildConfig.APPLICATION_ID`,
which is why `buildConfig` is enabled for the module) rather than by an invented
token, so the picker resolves its label, its icon and its "is it installed"
through the same `PackageManager` call it already makes for every other tile. It
is deliberately *not* part of `NavigationSettings.installedApps`, which is what
the fallback in `selectedPackage` reads: a car with no navigator installed should
still say so and ask for one, rather than quietly settling on our instruments
because they cannot be missing.

One thing is on the cluster at a time, and it comes off the way it was put
there. Choosing the dashboard while a navigator is projected returns that
navigator to the head unit first; choosing a navigator while the dashboard is up
takes the dashboard off first. `NavigationSession` carries a `NavigationTarget`
so the card's one button knows which of the two it is acting on - `На приборку`
and `Убрать` for the dashboard, `Открыть` / `На приборку` / `Вернуть` for a
navigator. The target is stamped in `NavigationCoordinator.update`, from the
current selection, so no call site that builds a fresh session can forget it.

Everything the projection path needs and the dashboard does not is skipped: no
transfer overlay, no split routing lease, no `bypassExternalTaskMoves`, no task
discovery, and no five-second projection health check. The former hidden
automatic stock-Map follower was removed from the product. Two things are
**not** skipped. The cluster display is resolved by the coordinator before the
intent is sent, because a scene service that cannot find the display writes a
line in its own notification and stops, which the card would never hear about;
resolving first leaves exactly two outcomes, the instruments or the display
picker. And the `SYSTEM_ALERT_WINDOW` appop is granted over local ADB first,
as every feature of this app that opens a window on the cluster does.

**Placement: `FULL` only.** The dial, the two corner blocks and the columns
beside them are one composition measured against the whole panel, so a third of
it is not a smaller version of this instrument - it is a different one, and this
product does not offer that one. A choice of one is not a choice, so the card
shows no placement row at all for the dashboard rather than a row with one live
cell and three dead ones (`NavigationPlacementPolicy`). The navigator's own saved
placement is left untouched while the dashboard is chosen and is still there when
a navigator is chosen again.

`RIGHT` therefore remains a capability of the renderer - the `COMPACT` ramp, the
`308`-unit virtual space and their tests all still describe it - without being
something the product offers. Reinstating it is a one-line change to
`NavigationPlacementPolicy.offered`.

### Where it may draw

`ClusterDashboardLayout` derives the keep-outs from `ClusterMapLayout`'s own
shade fields rather than restating them, so the two cannot drift. Wherever the
shade blacks the map out, something stock lives; wherever it cuts a reveal, the
map was allowed through. On the verified `2560x720` panel that gives, in the
`FULL` placement:

- a clear band across the whole width from `272 px` to `570 px`;
- the bottom-centre reveal, radius `600 x 330 px` centred `120 px` above the
  lower edge, which extends the centre column down to the panel edge;
- the two top-corner reveals, `614 x 272 px` on the left and `512 x 272 px` on
  the right.

`RIGHT` (`1023x524` at `Rect(1537, 95 - 2560, 619)`) carries no shade at all -
its protection is the crop - so all of it is usable. That made it the safest
placement to draw into, and it is why the narrow ramp exists; the product now
offers `FULL` alone, for the reason given above.

`CENTER` and `LEFT` are refused by `ClusterDashboardLayout.supported`. `CENTER`
is the only zone with no successful live render of any navigator on record and
additionally spends its top `260 px` on a near-opaque gradient; `LEFT`'s keep-out
is a quarter-disc rather than a band, which no block in this design fits.

**The one number still owed a measurement:** these boundaries were tuned by eye
against live captures when the shade was built, not measured. Confirm them with
the display `3` capture described above before treating them as exact. If the
stock band turns out to be shallower, the dashboard gains room and nothing
breaks - every block is placed against these values, never against the panel
edge.

### What it shows, and what it deliberately does not

State of charge and remaining range are **not** drawn. Both are already on the
stock cluster a few centimetres away, so spending the best real estate on a
duplicate would be the whole point missed. The same rule removed two more
things later: the charge rate, because a gun in the socket is drawn as energy
arriving and the dial's own figure already reads those kilowatts; and the 12 V
rail, which is a diagnostic rather than a driving fact. It had been shown on the
now-retired vehicle page and was deliberately not promoted to the cluster.

What the car does not show is drawn instead: traction voltage on its own
`500-600 V` window, cell spread in millivolts, pack health and insulation
resistance. Fuel level and range stay on the stock cluster; the experimental
duplicate left with the retired head-unit pages.

The centre carries instantaneous power on a square-root dial - zero at the top,
discharge one way, regeneration the other - with the consumption history nested
inside it and the average stated underneath. That history is fixed at the latest
**3 km**, drawn directly from the log's 100 m buckets. The former head-unit panel
offered a 3/10/30 km tap selector, but the panel, selector, and saved preference
were deleted on 2026-08-27; the cluster never inherits a hidden choice from an
older installation. The average is computed from the same thirty hundred-metre
buckets that are drawn, so it describes exactly the visible road. Left of it is
the pack, right of it the combustion half, and the two top reveals carry five
temperatures and the eight fluid lamps.

**The dial's marks carry numbers.** The two sides get the same arc but not the
same span - this car spends up to `300 kW` and recovers about `100` - so equal
deflection means very different figures left and right, and an unlabelled
two-sided arc across the top of a cluster is read as a speedometer. The flanking
pair, `60` one way and `20` the other at identical deflection, says both things
at a glance. Zero stays unlabelled: it is where the fill vanishes, and a label
above it reached two pixels into the vehicle's own graphics.

A mark that cannot be labelled is not drawn: `InstrumentDensity.dialMarks` is
`2` wide and `1` narrow, because in the right third the second mark's number
lands inside the combustion column. Three tests hold this geometry - the gauge
radius against `EnergyGauge.topReach` for the crown, the outermost labelled mark
against `engineBlock.left` for the flank, and `markReach` carries half the
number's own height so what the rhythm buys is a gap between the mark's tip and
the number's *edge*. Without that last term the nearly-horizontal discharge mark
put its tip about four pixels inside the first digit.

**The reading is a magnitude.** No minus sign at full regeneration, and that is
a decision: which way the energy is going is already said twice and said better,
by the side the fill runs to and by its colour, so a sign would be a third and
weaker copy. It also costs a whole character where the dial is tightest -
measured against the arc's chord at the digits' cap line, `-100` left about eight
pixels of clearance on the full-width layout where `100` leaves thirty.

Three digits is the ceiling the scale can produce, so the worst case is knowable
in advance rather than on the car: `EnergyGauge.widestReading` and
`chordAtReading` state it, and a test holds the two against each other for both
densities.

Section titles carry `0.12em` of tracking through `InstrumentPen.title`, which
exists as its own method rather than a parameter so the tracking cannot be
applied at one call site and forgotten at the next.

The lamps report exceptions, not an inventory. The full layout shows one state
dot for each of the eight lamps and uses the line below to name any alerts or an
incomplete read. The narrow renderer omits the dot grid; its line names the first
fault and counts the rest (`давление масла +2`) rather than running past the
block.

### Rest states

Most of what this dashboard shows is, most of the time, nothing happening. Three
cases were drawn out rather than left to a shared "no data":

- **The chart before the first bucket closes.** It draws nothing at all, not even
  its zero line - a bare rule with no bars inside a dial reads as a chart that
  failed. The sentence underneath carries the case instead: `стоим` standing,
  `считаю расход` moving, the average and its distance once there is one.
  Since the journal survives a restart this is now a short state rather than the
  first thing seen after every launch - see "Consumption journal" in
  docs/vehicle-data-findings.md.
- **A stopped engine.** The rpm figure already says `0`, so the sentence below
  stays empty. It speaks only for generation or for an engine signal that never
  answered; fuel range remains on the stock cluster.
- **A charge.** The pack's supporting sentence is taken over entirely by
  `заряжается · осталось 2 ч 15 мин`; insulation resistance goes back to being
  interesting when the cable comes out.

### The instrument system underneath

The dashboard is built on a small design layer rather than on constants chosen
per method, and that layer exists because of what an adversarial audit of the
design boards found: twenty-seven distinct type sizes where six were declared,
eighteen radii, thirteen optical stroke weights for one flat-line icon family,
and four sibling layouts whose first label started at four different heights.

- `InstrumentDensity` is the whole type ramp and rhythm - six rungs, none closer
  than `1.18x`, so two sizes can never read as the same one. `WIDE` and
  `COMPACT` are the same ladder at different rungs, not two invented sets, and
  `InstrumentDensityTest` refuses a size that is not on it.
- `InstrumentPen` is a drawing surface a component is *handed* rather than
  inherits, which is what lets `EnergyGauge` stay one control across the
  cluster's `WIDE` and `COMPACT` renderer densities.
- `ClusterBlockPlan` states each block as rows before anything is drawn, so the
  block is centred in its box instead of hung from its top, and
  `ClusterBlockPlanTest` adds the plan up against the box. That test has already
  earned its place twice: it caught a corner block measured against the wrong
  virtual space, and it is what says a ninth fluid lamp will not fit the reveal.

Both placements land on the panel at the same `1.70` scale - `424` units into
`720 px` across the full width, `308` into `524` in the right third - which is
why one ramp serves both. The virtual space belongs to the layout, not to the
density: a corner reveal is drawn with the narrow ramp inside the *wide* space.

### Telemetry ownership

Since 2026-08-27 the cluster dashboard is the only UI owner of
`VehicleTelemetryHub`. `setDashboardActive(true)` starts polling when its view
is attached and visible, and hiding or detaching that view releases the sole
activity claim. The retired vehicle/engine page flags and their page-dependent
signal filter are gone. The hub therefore reads the complete six-signal hot set
and thirty-signal cold set the cluster needs while it is visible, including
revolutions, generation and the lamps. The view redraws at ten frames a second,
already faster than the sweep that feeds it.

The tank joined the cold set on 2026-08-25: `FUEL_PERCENT` (`0x4A507040`),
`FUEL_RANGE_KM` (`0x4A504038`) and, briefly, `FUEL_LOW` (`0x4A507027`). The first
two were readable in the 2026-08-23 read-only sweep - `53 %` and `491 km`, steady
across a start/stop cycle. `FUEL_LOW` was removed after the first cluster run;
the level and range followed the retired pages on 2026-08-27 because the current
cluster renderer does not display them and the stock cluster already does.

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

On 2026-09-04 a targeted BYDAutoLight listener was live-proven for the raw lever
phase and confirmed flash-mode FIDs. Denza Apps now starts that listener through
a separate passive local-ADB resident lane only while Mirrors is enabled. The
raw edge is side-agnostic and may only preempt the old Denza camera surface; a
later same-epoch confirmed mode and stable matching stock window may reopen the
new side after full vendor teardown. Manual lever cancellation live-produced a
transient opposite-direction event, so it never selects the camera side. The
existing window observer and `MirrorTransitionReducer` remain the camera-command
authority. Full evidence, resource measurements, and the remaining acceptance matrix are in
[vehicle-data-findings.md](vehicle-data-findings.md#targeted-turn-signal-events-2026-09-04).
After the original direct left-to-right run crashed stock AVC, an instrumented
guarded candidate detached the old local surface 4 ms after the next right onset,
completed vendor release after 121 ms, and reopened the Denza right view only
after confirmed mode/window stability. The user saw both sides correctly, AVC
kept the same PID, and no new crash or exit record appeared.
An ordinary later left-off-pause-right run exposed a separate false negative:
the idle reducer quarantined a 454 ms transient ambiguous stock-window interval
before the right window became stable. The corrected reducer may wait through
that interval only with a pending live edge and idle Denza runtime; it still
cannot Show until the normal confirmed-mode/stable-window gate passes. The final
separated cycle showed both Denza sides and kept AVC PID `17977`, although the
ambiguous interval itself did not recur and that exact branch remains unit-only.

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

### Capturing navigation and the Waze layout experiment

For GL navigators, a screenshot of the physical cluster display is not enough
to establish what the app rendered. The navigation output is nested through an
app-owned `SurfaceView`; mirroring display `3` can therefore produce a black or
stale frame while the `Denza Navigation` virtual display is still presenting
new buffers. The repeatable diagnostic sequence is:

1. resolve the current `Denza Navigation` display id from `dumpsys display`;
2. capture that logical display directly with
   `tools/surface_control_mirror_probe.sh <display-id> ... 1` to inspect the
   navigator layout;
3. capture display `3` separately only to check the final placement against the
   stock cluster composition.

This method captured Waze 5.22.0.3 directly on the live car on 2026-08-19.
With an active route, **Full** (`2560x720@272`) and **Right**
(`1023x524@272`) rendered the route, map controls, vehicle marker, and bottom
bar. The marker was above the bar in both direct captures. **Center**
(`1023x720@320`) remained black at 5, 15, 30, 45, and 60 seconds, despite the
Waze `SurfaceView` and the virtual-display sink both continuing to queue
frames. **Left** (`1023x609@272`) also produced black source frames and could
leave later projections black until a normal return to the IVI forced Waze
through another display configuration.

Host-only overrides tested `1920x720`, `1536x1080`, and `1440x1014` with
densities from 240 through 320. A same-aspect enlarged viewport could
occasionally render a frame with the marker above the lower bar, but the result
did not reproduce on the next clean run and could become black after the next
input or lifecycle transition. The wide viewport also letterboxed inside the
center region. All overrides were reset before return. No Waze-specific product
change is justified by this experiment: neither a different resolution nor a
density alone produced a deterministic center layout. An attempt to precreate
the viewport with an Activity-backed root hit the already documented vendor
`ActivityRecord`-to-`Task` cast failure and was abandoned immediately; that
topology remains forbidden and is not a Waze candidate.

When the selected navigator is a child of a native IVI split root, moving its
containing root also carries the pane geometry to the instrument display and
leaves an empty split container on the IVI. The projection proxy instead asks
the stock task organizer to create a genuinely empty fullscreen root on the
app-owned virtual display, resolves the navigator's containing root through
`getAllRootTaskInfos`, and reparents only the navigator task into that empty
root. The organizer-created root already inherits the full virtual-display
bounds; only the nested navigator task needs an explicit resize. A standalone
navigator root keeps the already proven whole-root move path.

An earlier development build used an Activity host as the projection root.
That topology is forbidden. It put an `ActivityRecord` and the reparented
navigator `Task` under the same root. At 22:08:50 on 2026-08-14, a tap outside
focus reached the vendor `Task.resumeTopActivityUncheckedLocked` path, which
blindly cast the host `ActivityRecord` to `Task`. The resulting
`ClassCastException` killed `system_server`; the apparent head-unit reboot and
the subsequent application `DeadSystemException`s were consequences of that
single failure. The host Activity was removed from the product. Root discovery
now fails closed unless exactly one new root exists on the expected `Denza
Navigation` display and its organizer report contains only the root's own task
id before the navigator is moved. On this firmware an empty organizer root is
reported as `childTaskIds=[rootTaskId]`, not as an empty array.

Before detaching the navigator, the proxy records its native source root and
the visible companion task/root. Split routing is held only while the task move
and its configuration changes are in flight. Once projection has settled, the
remaining IVI app becomes the router's new baseline, allowing a later launch
to form an independent IVI pair while navigation remains projected. Return
acquires the hold again and restores the recorded companion and navigator to
their original native roots before routing resumes.

The corrected topology was exercised on the live car on 2026-08-14 from a
native split pane. The navigator moved alone into newly created instrument
roots at both `2560x720` and the selected partial-map geometry `1023x524`; the
central companion remained available, `system_server` stayed alive, and the
user-initiated return restored the navigator to the IVI. Both virtual displays
were then removed normally, with no crash-buffer entry.

The UI state is contextual: **Open**, **To cluster**, then **Return**. The
picker re-reads the installed subset of the navigation allowlist whenever it is
opened, and the selected package is checked again before an automatic launch.
The selected package is saved. Projection sessions stay in memory and end with
the process. The automatic **Map mode** implementation also remains in code,
but its unfinished UI switch is hidden in the current build.

Both directions now expose the otherwise quiet task-move delay on the main IVI
display. While Denza Apps' `MainActivity` is not resumed, a centered,
non-interactive **Переносим…** window with an indeterminate progress indicator
appears at the start of projection and return. It stays through the return
settle/reopen check and disappears on success or failure. When the Denza Apps
window is active, the overlay remains hidden because the Navigation card
already shows the current transition. This lifecycle is locally unit-tested and
built; the window still needs visual confirmation on the car.

The overlay's visual tokens match the text-toast resources in this vehicle's
exact `com.android.systemui.apk` (`text_toast.xml` and
`toast_background.xml`, SHA-256
`e6fe427a5668a483cbe699292dad155edb525aa2034e7110f0930843b217ccd4`):
18sp normal sans-serif text in `#E6FFFFFF`, an opaque `#343942` background,
8dp corners, 16dp horizontal and 10dp vertical padding, and a 48dp minimum
height. The live progress indicator occupies the system's 24dp icon slot.

The optional **Steering-wheel button** switch binds the Denza configurable
left-hand key only to the contextual navigation action. It is off by default.
On the tested DiLink 5.1 firmware, host-side
`adb shell getevent -lt /dev/input/event0` identifies the device as
`simulate-keys`: Linux input code `300` (`AUTO_CUSTOM_KEY`) maps through
`/system/usr/keylayout/simulate-keys.kl` to vendor Android key code `321`.
Code `301` (`AUTO_CUSTOM_KEY_LP`) maps separately to Android key code `322` for
the stock long-press settings flow.

The existing Denza Apps accessibility service requests key-event filtering.
When the switch is enabled, each first, non-repeated `DOWN` for key code `321`
immediately asks navigation to accept one contextual action. There is no timer,
sequence counter, or double-press behavior. The app consumes that press's
`DOWN`, repeats, and `UP` only after navigation accepts the action; while a
transfer is already pending, during a transition, or while an app/display choice
is required, the complete press remains available to the stock handler. Key code
`322` and every unrelated key remain untouched. When disabled, Denza Apps does
not consume `321`, so the stock action continues normally.

The persisted switch also owns readiness of the shared accessibility service.
Turning it on, boot/package recovery, and an observed service disconnect all
request the same serialized self-repair independently of whether Simulcast is
enabled. The card distinguishes the saved switch state from observed readiness.
Denza Apps does not rewrite the global `byd_map_package` setting. That stock
alternative was observed but rejected: `CustomKeyHandler` action `7` reads
`byd_map_package=com.byd.launchermap` and sends the package-scoped
`CUSTOM_NAVI_STANDARD_BROADCAST_RECV` broadcast.

On 2026-07-24, a non-consuming probe received key code `321` before
`CustomKeyHandler` and still allowed the stock map to open. A consuming probe
prevented the stock handler from running. With the product switch enabled, the
wheel key moved Yandex Navigator task `131` to Denza Apps virtual display `13`
at `2560x720`; the next press returned it to display `0` and removed display
`13`. The user confirmed both directions worked correctly. These task and
display IDs belong only to that run.

The former hidden automatic mode checked the selected instrument display once
per second. A visible exact
`com.byd.launchermap/com.byd.automap.meter.MeterActivity` task means the stock
**Map** mode is active; its disappearance means the mode was left. The detector
uses live root/display relationships and never stores a task, root, or display
ID. Entering Map projects the selected navigator. Leaving it returns the task
to display `0`, restores normal bounds, and backgrounds it so the previous IVI
scene remains visible. This implementation, its persistent ADB shell, and the
one-second poll were removed on 2026-08-26 because no product UI could enable
the mode. Manual projection and return remain supported.

Starting or stopping DiShare video creates and removes a separate BYD mirror
display. During that display churn, `ActivityManager.getRunningTasks()` can
temporarily omit the task that still belongs to the live app-owned navigation
display. On 2026-08-14 the old watchdog interpreted one such negative lookup
as an ended projection, released `Denza Navigation`, and Android consequently
removed the navigator task with its display. The failure was local to Denza
Apps; `system_server` stayed alive.

Display teardown now fails closed. A negative lookup or shell error preserves
both the virtual display and map surface and reconnects only the shell. An
external task move is accepted only after the same positive different display
is observed twice. Projection-error cleanup follows the same rule: it releases
a live display only after the task was positively found elsewhere or was
successfully returned to its recorded native root. If task location or return
is uncertain, the UI keeps a **Return** action instead of destroying the task.

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

The accessibility tree itself is a blocking Binder API and must not be read on
the app's main looper. A 2026-08-16 live profile with Yandex Navigator beside
the Denza split picker found the old 350 ms poll spending `12.58 s` of a
`14.00 s` capture inside `YandexGuidanceAccessibilityReader`, including 1,289
synchronous waits; the picker consequently missed input and produced repeated
`500..550 ms` frames. Guidance reads now run on one dedicated worker with a
single in-flight request, one coalesced trailing request, and lifecycle
generation checks that discard late results after disable/detach. Only the
small validated-result transition runs on the main looper. In the repeated
live scroll profile the reader consumed `0 ms` on the main thread, no frame
exceeded `57 ms`, the user confirmed smooth scrolling, and the `com.byd.avc`
crash-buffer fingerprint stayed unchanged.

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

On 2026-08-14 the updated firmware's native **Navigation Fusion** AR arrow was
isolated on the same road topic; topic `1127042368241667` is not required. In
addition to the compact fields above, the working packet carries vehicle
longitude/latitude (double fields 19/20), speed and altitude (integer fields
21/22), remaining-route JSON `guideLine` (string field 30), the current
segment endpoint as `lon,lat,0` in `guidePoint` (string field 31), vehicle
heading (double field 32), and route ratio (double field 33). The user visually
confirmed the new flying arrow. Corrected live left/right probes, with the
maneuver ID, regular icon, and geometry synchronized, were visually
distinguishable in the expected directions. Arrow colour remains controlled by
firmware; the road structure has no colour field.

Yandex Navigator 29.8.1 owns a real route polyline internally through MapKit,
but does not export the active route geometry cross-package. Its private
guidance service/provider is not callable by Denza Apps, and its exported
AndroidX Car App step carries maneuver, road, lane, time, and distance data but
no polyline. Denza Apps therefore uses an explicitly bounded approximation:

- GNSS runs only while HUD guidance is enabled. A course is acquired only from
  a moving fix at or above `1.5 m/s`, with no more than `20 m` horizontal and
  `45 degrees` bearing uncertainty. A measured course may be held for 15
  seconds at a light; the app never invents an initial parked heading.
- AR activates from 3 through 150 metres for straight, slight, normal, sharp,
  and U-turn left/right maneuvers. A U-turn has its own progressive 175-degree
  curve, so it can only be emitted from an explicit Yandex U-turn instruction;
  the regular turn guards remain capped below reversal. Unknown maneuvers,
  roundabouts, stale/future fixes, and poor accuracy fail closed to the
  already verified compact HUD packet. So does a guide point whose bearing
  diverges from the vehicle nose beyond half the commanded turn angle
  (15 degrees for straight, capped at 70 for U-turns): the firmware projects
  the polyline against the live vehicle pose, so a divergence larger than the
  maneuver's own angle used to let a slight left render as a right-bending
  arrow while every approach-frame guard passed.
- The inferred turn point is anchored in world coordinates, so repeated GNSS
  fixes cannot drag it forward while Yandex's displayed distance is unchanged.
  Small distance updates are blended; a distance increase of at least 30
  metres resets the anchor as a recalculation. A large lateral course mutation
  now rebases the approximation and suppresses that sample instead of folding
  an old anchor across the car. The next stable sample may resume AR. Leaving
  the activation window clears the anchor.
- The historical anchor course and the live approach must agree within 45
  degrees. The exit is derived from the live approach rather than blended with
  the stale anchor course. Immediately before serialization, a semantic guard
  rejects degenerate segments, a bend in the direction opposite the maneuver,
  a non-progressing exit, or a terminal deflection inconsistent with the
  requested 35/90/135/175-degree maneuver — and, for non-U-turn maneuvers,
  a terminal segment that does not keep the commanded side of the vehicle
  nose (U-turns are exempt only because shortest-angle sign aliases near
  180 degrees; their tighter cone and progression checks stay authoritative).
  Only an explicit U-turn may exceed the regular 165-degree reversal ceiling.
- Field 33 remains `0.0` because proximity is not whole-route progress. AR
  refreshes every 350 ms. Losing an eligible pose immediately emits a compact
  packet so the firmware cannot retain stale geometry.

Four synthetic straight/slight/normal/sharp paths and the corrected left/right
pair were accepted by the live SOME/IP service, cleared, and stopped without
an Android or AVC crash. The disposable probe was uninstalled. The product
registered a 200 ms GNSS request on the car. Twenty-two JVM mutation tests
cover the activation boundaries, unsupported maneuvers,
stale/imprecise/out-of-order fixes, parked-course hold, north/dateline wrap,
left/right mirroring, U-turn direction and return heading, turn severity,
anchor stability, rerouting, lateral rebasing, stale-course rejection,
wire-boundary turn semantics in both the approach and the nose frame, and
exact protobuf field numbers/wire types. End-to-end visual validation during a
real moving Yandex route, including the new U-turn geometry, is intentionally
still pending.

A live 2026-08 route produced the predicted failure of the old flat 70-degree
gate: on a left-curving approach to a slight left, the world-anchored guide
point drifts to the outside of the curve, every guard measured only against
the approach leg still passed, and the blue AR arrow rendered as a right
turn. The per-maneuver nose cone above is the fix; the JVM regression test
`left-curving approach cannot slant a slight left onto the right of the nose`
reproduces the drive. The trade-off is intentional: on strongly curved
approaches AR now drops to the compact packet more often instead of drawing a
semantically wrong bend. The live re-check is recorded in the next paragraph.

On 2026-09-03 the live re-check reported the same failure after the nose-cone
fix, while the flat maneuver PNG was always correct. That points away from
parsing and geometry and at field 28 (`recommendedDrivingDirectionsId`). The
original ID table (left 1, right 2, straight 3, slight 5/7, sharp 9/11, U-turn
45/13, roundabout 46/47) had no recorded source, and only 1/2 agree with the
empirically named HUD icon table recovered from OpenBYD: 1 left, 2 right, 3/4
slight left, 5/6 slight right, 7 sharp left, 8 sharp right, 9/10 U-turn
left/right, 11/12 straight, 13/14 detour right/left, 15-24 roundabout exit
variants, 25-34 counter-clockwise roundabout 1-10, 35-44 clockwise roundabout
1-10, 45 stop, 46 parking, 47 tollbooth, 48 destination, 49 tunnel. A slight
left was therefore sent as the slight-right ID and a slight right as the
sharp-left ID. The earlier left/right probes could not expose this because 1/2
coincide in both tables, and the slight probe was only checked for packet
acceptance. The product now sends the recovered table (roundabouts as 25, exit
number not encoded). A live drive on 2026-09-03 confirmed it: with the
recovered IDs, slight exits render on the commanded side. The AR glyph side
therefore follows field 28, and the 2026-08 left-curving failure above was this
ID mismatch rather than frame divergence; the nose-cone guard stays as a
geometry safety net. Left, right, and both slight IDs are live verified; sharp,
U-turn, straight, and roundabout IDs come from the same table but have not been
seen on the car yet. A parked ID sweep remains the way to verify them.

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
- fast left-to-right turn-signal switching was a confirmed crash path while
  Denza Apps kept the old AVC display surface. The persistent-Surface candidate
  did not fix it. The CAN-edge guard has now passed one instrumented stationary
  left-to-right canary: local detach preceded stock rebuild, vendor teardown
  completed before one delayed opposite-side reopen, and AVC survived. The
  right-to-left, cancellation, hazard, sleep/wake, repeated stress, moving-speed,
  and second-firmware matrix remains open.

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
listed above remain open Denza Apps work; one guarded left-to-right transition
is evidence for this car, not a general rapid-switch guarantee.

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

### DVR Camera2 source: verified renderer, product path retired

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

Denza Apps `0.5.1` used the same centered camera frame and render transform.
Its neutral monochrome runtime shader adaptively favored the cleaner green
channel only for low-saturation shadows, used nine spatial samples with
luminance-edge-aware weights to reduce noise without crossing object edges,
then applied a shadow-only tone curve, a second shadow-contrast curve, and a
soft highlight shoulder. Saturated red or blue lights retained
perceptual-luminance weighting instead of being discarded by the green-channel
preference. The DVR renderer and AVC side-camera renderer were mutually
exclusive on the shared camera overlay. The evaluated Camera2 stream exposed
camera `0` in its declared sensor orientation and used
the live-accepted `-90°` rotation with a `2x` / `3x` crop. A briefly visible
left-rotated image after one APK replacement was a frozen buffer: CameraService
had no active client at that moment. `SurfaceTexture` metadata did not
distinguish that stale state and must not be used to choose product geometry.
Camera `0` advertises only `SCALER_ROTATE_AND_CROP_NONE`, which Denza Apps now
requests explicitly. Rotation and crop are applied as outer View properties
after the texture is composed; the internal `TextureView` matrix remains
identity so it cannot combine differently with a recreated buffer surface.
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
accepted deep-shadow lift unchanged. The first isolated noise profile was not
visibly distinguishable on the live display. The next calibration profile uses
the existing nine-sample edge-aware blend at `0.88` in deep shadows and `0.34`
through the lower midtones, raises the noise threshold from `0.004` to `0.012`,
and retains `0.50` of detail outside that threshold. It still reaches zero by
luminance `0.62` and does not change either local-contrast band.

After an APK replacement, applying the unrotated path's `2x` / `4x` scales
together with `-90°` produced the correct orientation but excessive cropping.
The uniform `2x` sensor-path scale then retained too much of the wide frame and
looked compressed in the center window. The accepted sensor-oriented candidate
keeps `-90°` and uses the midpoint `2x` / `3x` scale.

The `2026-08-14` live session resolved why the required rotation flip-flopped
across the earlier calibrations: the DVR stream's delivered orientation depends
on whether the factory driving recorder is actively recording camera `0` at the
moment Denza Apps opens it. With the recorder recording, frames arrive
pre-rotated and the fixed `-90°` shows the image sideways; with the recorder
off, frames arrive sensor-oriented and `-90°` is correct. Toggling the recorder
off restored the upright image immediately on the next open. The gear itself is
not the trigger: shifting `D` → `P` with the camera open does not flip the live
image, a fresh open in `P` stayed sideways, and cycling the stock 360 view does
not reset the state. The recorder toggle is delivered as the
`android.intent.action.AUTO_VIDEO_BUTTON` broadcast, received by `com.byd.avc`
(the same fragile AVC process; do not poke it as a probe). Recorder state is
not readable from system properties or settings (`getprop` / `settings list`
diffs across the toggle are clean), so a product selector must either observe
the delivered frames (e.g. `SurfaceTexture#getTransformMatrix`, untested for
this distinction) or listen for the button broadcast, which is an event, not a
state. The vendor `bmmcameraserver` runs its own camera3 stack below the public
camera service (it streamed HAL cameras `2`, `3`, and `10` continuously through
the whole session), and the camera service event log shows HAL-level
open/close activity on camera `0` with no matching public client, so camera `0`
is effectively multiplexed with the vendor pipeline. MediaTek vendor tags in
the `com.mediatek.singlehwsetting` section (`module`, `sourcecrop`,
`transform`, `videostream`, `warpmap*`) are the HAL-side geometry knobs that
plausibly implement the recorder-dependent transform.

Continued live A/B later the same evening falsified the recorder correlation
itself: the dashcam (`com.byd.cdr`, writing 2-minute segments with an
in-progress `.cpy` file under the stick's `Recorder/Normal/`) recorded
continuously through upright and sideways activations alike, gear `D` produced
both orientations across app reinstalls, and four consecutive parked
activations stayed upright. The vendor state machine is not externally
predictable, and the delivered frames are metadata-identical in both states,
so no in-app selector survives contact with the evidence. The bounded
per-session probe in `DvrCameraRenderer` (`files/dvr_probe.log` plus
`dvr_frame_*.png`, readable via `run-as`; logcat is suppressed wholesale for
app processes on this firmware) collected orientation evidence while that
renderer remained compiled. On 2026-08-26 the renderer, probe, product toggle,
and Denza Apps camera capability were deleted instead of retaining an
unreachable research path. Any future frame-content experiment belongs in an
isolated probe rather than the product APK.

The app-side shader remains spatial and has no frame history. Camera `0`
advertises all standard Android noise-reduction modes and its live request
already reports MediaTek 3DNR enabled. Denza Apps now explicitly requests
Android `HIGH_QUALITY` noise reduction instead of leaving the preview template
at `FAST`; whether the vendor implementation adds more temporal accumulation is
opaque and must be judged from motion and noise on the live stream.

The live `HIGH_QUALITY` request was accepted but produced no visible change
against `FAST`. The following aggressive calibration also requests the
camera-specific `com.byd.camera.mfnr.mode=1` multi-frame path. Its app-side
fallback blends the existing edge-aware neighborhood with four protected
samples at the `9 px` radius, uses full strength in deep shadows and `0.55` in
lower midtones, and retains only `0.10` of detail above a `0.018` luminance
threshold. This is intentionally an upper-bound profile for visual comparison,
not yet an accepted final setting.

That upper-bound profile also produced no obvious live difference. The next
diagnostic `noise-crush` profile turns camera edge sharpening off, adds eight
unprotected samples out to `18 px`, blends `95%` toward that broad mean, removes
all recovered fine detail, and applies at `1.0` / `0.88` strength through
luminance `0.82`. Local-contrast gain is suppressed by up to `85%` wherever the
noise filter is active. This deliberately sacrifices texture and may smear
low-contrast object boundaries; it exists to prove whether the visible grain is
inside the app's render stage.

The live result showed obvious heavy blur, proving that the visible grain is
inside the app's render stage. The next midpoint profile keeps the `18 px`
samples and camera edge sharpening off, but blends only `55%` toward the broad
mean, restores `25%` of detail above a `0.012` threshold, reduces lower-mid
strength to `0.65`, ends the filter by luminance `0.72`, and suppresses local
contrast by at most `55%`.

That midpoint retained a visible artificial ripple from its sparse `18 px`
sampling and still looked overprocessed. The next balanced profile removes the
far ring entirely, replaces it with eight symmetric `4.5 px` samples, restores
camera `FAST` edge processing, and uses `0.78` / `0.36` strength with `42%`
detail retention. Only `12%` of the protected `9 px` mean is mixed in, the
effect ends by luminance `0.66`, and local contrast is reduced by at most `20%`.

The balanced profile still created false texture in low-contrast areas even
though strong edges looked cleaner. All app-side spatial denoising was therefore
removed. The accepted monochrome fusion, shadow curve, two-band local contrast,
highlight roll-off, and geometry remain unchanged; Camera2 `HIGH_QUALITY`,
MediaTek 3DNR, and BYD MFNR stay enabled as the hardware baseline. Any further
software denoising must use a separate motion-aware temporal pipeline rather
than sparse single-frame sampling.

### ADAS cameras: status signals found, no video endpoint found

The system vocabulary contains health/fault signals for front-view `30` and
`120` cameras, but Android CameraService exposes only ids `0`, `1`, `2`, `3`,
and `10`. The installed privileged `AdasAgentService` exposes ADAS
position/event services and BYDAUTO ADAS permissions; no reusable camera
Surface, Camera2 id, or video Binder endpoint was identified. Treat access to
ADAS imagery as unavailable through supported app APIs unless new direct
evidence appears.
