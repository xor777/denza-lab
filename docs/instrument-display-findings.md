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
recorded under "What the first live run changed" below. **The composition was
replaced on 2026-09-04** by the Contour, which won the cluster contest; what is
on the panel now, and why, starts at "What it shows, and what it deliberately
does not". Everything in the two sections before that - how the presentation
hosts it, how the driver reaches it, and where it may draw - is unchanged.

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
protects instrument data by placing itself off the stock zones, not by dimming
itself.

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

**Placement: `FULL` only.** The hero on the axis, the band from margin to margin,
the two corners inside the stock apertures and the two shelves in the clear
band's flanks are one composition measured against the whole panel, so a third of
it is not a smaller version of this instrument - it is a different one, and this
product does not offer that one. A choice of one is not a choice, so the card
shows no placement row at all for the dashboard rather than a row with one live
cell and three dead ones (`NavigationPlacementPolicy`). The navigator's own saved
placement is left untouched while the dashboard is chosen and is still there when
a navigator is chosen again.

Since the Contour, `RIGHT` is refused by `ClusterDashboardLayout.supported` as
well. It had been a capability of the renderer that nothing called - a second
ramp, a second virtual space and their tests, all describing a screen the product
had never offered - and the Contour is not a composition that survives being cut
to a third. Reinstating it would be a redesign, not a flag.

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

`RIGHT`, `CENTER` and `LEFT` are all refused by
`ClusterDashboardLayout.supported`. `RIGHT` (`1023x524` at
`Rect(1537, 95 - 2560, 619)`) carries no shade at all - its protection is the crop
- which once made it the safest placement to draw into; it is refused now because
the Contour is one full-width composition. `CENTER` is the only zone with no
successful live render of any navigator on record and additionally spends its top
`260 px` on a near-opaque gradient; `LEFT`'s keep-out is a quarter-disc rather
than a band.

Those three apertures are the panel's own anchors, not just its keep-outs.
`ClusterDashboardLayout` exposes their radii and `ContourPlan` places the corners,
the petal's history box and its unit against the curves themselves, with a guard
of 8 units - so the composition is measured against the boundary rather than
against the panel edge, and it gains room if that boundary turns out to be
shallower.

**The one number still owed a measurement:** these boundaries were tuned by eye
against live captures when the shade was built, not measured. Confirm them with
the display `3` capture described above before treating them as exact. If the
stock band turns out to be shallower, the dashboard gains room and nothing
breaks - every block is placed against these values, never against the panel
edge.

### What it shows, and what it deliberately does not: the Contour

Replaced on 2026-09-04. The panel described here until then - a square-root arc
with the consumption bars nested inside it, two columns of readings either side,
a sparkline beside the revolutions and a grid of eight fluid lamps - is gone. What
is on the driver's display now is **"Контур"**, which won the five-way cluster
contest in `docs/cluster-contest-2026-09/`: `VERDICT.md` picked it with five
binding corrections, `CRITIQUE.md` roasted the third drawing of it with three
blockers, fifteen majors and twelve minors, and the boards in
`tools/design-canvas/` are the ninth drawing - the second one made against the
panel running rather than against a still. `gen_contour.py` is the design and
`ContourPlan` is the same numbers in Kotlin; `ContourBoardContractTest` fails in
both directions if either moves alone.

The owner's own interests decided the contest - voltages, the battery,
revolutions, consumption - and one question of his decided the sixth and seventh
passes:
*«что означает 0,0 от ДВС, когда ДВС заглушен?»*, and then the sentence behind it,
that if the question occurred to him the element is unclear to anybody. So the
panel has one rule that outranks the tidy ones: **is this understood at first
glance, by somebody who has never seen it and has no legend?**

**What is on it.**

| | |
| --- | --- |
| the hero | instantaneous pack power in kilowatts, on the axis, at 88 with its unit at 34 - the one figure read on the move and the one place a unit has to be readable |
| the band | that same reading as a bar across the whole clear width, zero in the middle, `EnergyScale`'s square root over 300 kW out and 100 kW back, with a peak hold |
| the glow | one pool of light centred on zero: hue is the direction, brightness is `0.18·√(P/120 kW)` by magnitude |
| the left corner | «БАТАРЕЯ · В» over the traction voltage, at 52 |
| the right corner | «ДВС · об/мин» over the revolutions while the engine runs, «ДВС · мин за поездку» over its minutes once it has stopped, and **empty** if it never started this trip |
| the left shelf | five temperatures at 34 - pack, front motor, rear left, rear right, inverter - each over a glyph rather than a word, plus a sixth cell «мВ / РАЗБРОС ЯЧЕЕК» that exists only at `WATCH` or `ALERT` |
| the right shelf | what the trip cost, as a phrase: «9,3 кВт·ч» over «42 км · ЗА ПОЕЗДКУ», plus «ДАЛ ДВС» if the engine ran, plus «● РЕКУПЕРАЦИЯ» when the car is standing in P |
| the engine's box | while the engine has been alive in the last two minutes, that shelf is its own history instead: what it put back into the pack, as twenty-four five-second steps linear to 30 kW, under one sentence - «● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН» |
| the petal | the last three kilometres of consumption, as a stepped field standing on the figure's own baseline, «кВт·ч/100 км · за 3 км» - or a countdown to full while a gun is in |

**What is not on it, and why.** State of charge, range, fuel level and the fuel
alarm are all on the stock cluster a few centimetres away; spending the best real
estate on a duplicate is the whole point missed. The charge rate is not repeated
either - a gun in the socket is drawn as energy arriving and the band already
reads those kilowatts. The 12 V rail is a diagnostic rather than a driving fact.
Pack health and insulation resistance left with the old composition: neither
changes inside a drive, and the shelf they would take is the trip's.

The eight fluid lamps are **not drawn**. A grid of dots that are green almost
every second of every drive is an inventory, and a driver's display shows
exceptions. Their ids are still polled - they are cold signals costing one shell
round trip every ten seconds - and `VehicleTelemetry.lamp` still decodes them, so
an exception channel has somewhere to read from.

**The sag rail is deleted** (CRITIQUE M9). Its reference was an EWMA of the pack
at rest, and on a motorway there is no rest: the board electronics pull a kilowatt
or two permanently, so after half an hour the reference has aged into the pack's
own discharge and «просадка 14 В» is ten per cent of the state of charge wearing a
unit it does not have.

### The temperature row is five glyphs and one word

The second bench pass, and it is about the row nobody had complained about. Three
figures under one `МОТОРЫ` never said **which motor is which**, and naming the
three positions in Russian - `ПЕРЕД / ЗАД Л / ЗАД П` - was tried and thrown out on
the sound of it. So the row is five cells with no words at all: a two-digit figure
with its own `°` over a drawing of the thing the figure is about.

| cell | glyph | signal |
| --- | --- | --- |
| 1 | a battery: case, terminal, one lit cell inside | `PACK_TEMP_AVG` |
| 2 | the car from above, a bar across the front axle | `MOTOR_FRONT_C` |
| 3 | the same car, half a bar on the left of the rear axle | `MOTOR_REAR_LEFT_C` |
| 4 | the same car, half a bar on the right | `MOTOR_REAR_RIGHT_C` |
| 5 | a case with one period of alternating current in it | `INVERTER_C` |
| 6 | «РАЗБРОС ЯЧЕЕК», and it is the only word in the row | `CELL_MAX_MV − CELL_MIN_MV` |

Five rules carry the family, and `ContourGlyphs` is where they live:

- **all five or none.** «если символы, то и батарея, и инвертор, и хорошие» - half
  a row of pictures under half a row of words is worse than either;
- **24 units, not the caption's 18.** At 18 they were 2.7 mm of glass for a shape
  with four wheels in it and the owner could not see them. They stand *on* the
  caption baseline rather than hanging from it, so neither of the shelf's two rows
  moved and ten units still separate a glyph's top from the figures' baseline;
- **one outline, one component.** The outline is `MUTED`, the same as a caption
  was; the component inside carries `INK`, one step brighter than the figure above
  it, and takes `WARNING`/`DANGER` **with** the figure on an exception - so a hot
  cell lights as one object rather than as a red number beside a grey picture.
  This is the panel's one exception to "`INK` is the hero alone", and it is what
  makes the mark findable at 12′;
- **the motor is the motor, not the wheel it drives** («точно мотор с колёсами не
  путаешь?»). All four wheels are hollow outlines in all three cars, at 1.6 rather
  than the 2.5 everything carrying data is drawn at; what moves is the filled block
  on an axle;
- **every proportion is in caption units**, so the family scales with its height
  alone. `gen_contour.py` states the same constants the same way and
  `ContourBoardContractTest` reads the drawn rectangles out of `ClusterContour.dc.html`
  and holds them against `ContourGlyphs`, in both directions.

What it cost and what it bought: five cells of 50.9 where the three words were
91.9 · 143.4 · 110.0, so the row is 58 units narrower even with each cell carrying
its own `°` - the three motors shared one sign from the sixth pass to the eighth
because `РАЗБРОС ЯЧЕЕК` needed those 25 units, and the captions paid them back
twice over. The left shelf now stops 270 units clear of the hero's field, or 86 with
the exception cell up. And **a word in the temperature row now means something is
wrong**, because it is the only word in it.

This row breaks the house icon rule on purpose. `DenzaIcons` is one optical weight
throughout, because those are labels on tiles; a glyph here is half of a reading,
and inside one mark the case, the lit component and the wheels are three different
kinds of thing.

### The four rules the panel is built on

**One heavy thing.** `INK` belongs to the hero and to the petal's figure and to
nothing else. Both corners and both shelves are `MUTED`; headings, captions and
units are `MUTED_DEEP`; `WARNING` and `DANGER` are the exception only. Five equal
52s were the owner's original complaint wearing a new suit, and size alone was not
enough to separate them.

**One lit thing, and it stands still.** The glow does not travel. It used to ride
the band's tip at 400 ms, which put a 73 mm pool of light through 50-100 mm of
travel every time the pedal moved in a traffic jam - precisely what peripheral
vision is built to catch. Centred on zero, with its own 120 kW span, calm driving
sits at 0.10 of its alpha and an acceleration saturates.

**Alpha is not a state channel.** It had seven meanings on the third drawing -
night, jam, sleeping engine, link loss, a single null, an area fill, a history
fade - and a driver cannot tell "dim because it is dark" from "dim because the bus
died". One rule now: **a stale value is removed after two seconds and its caption
stays.** Link loss is that rule applied to every value at once, a single null is
that rule applied to one, and neither dims anything. There is no night scene: the
cluster's own dimmer already darkens our window, and whether it does is a
measurement on the car. `ContourScene` is where all of this is decided, away from
the canvas.

**A zero is never drawn.** A quantity that did not happen this trip has no cell -
no «0,0 ДАЛ ДВС», no empty seat holding its place. Seats are counted right to left
from the shelf's own edge, so the one that does exist is always in the same place
and the second one appearing moves nothing.

And under all four, the structural rule that answers the owner's original
complaint about numbers jumping: **no coordinate depends on data.** Every figure
lives in a reserve field sized by its maximum digit count times a measured
advance, its unit hangs off the field rather than off the string, and neighbours
are set against the field's edge. 34 becomes 128 and nothing moves.

### The type, and the tape measure behind it

Every ergonomic claim on the first three boards stood on the brief's "порядка
25 см (оценка)". The owner took a tape to the car on 2026-09-04: **the active area
of the cluster glass is 320 mm wide and his eyes sit 750 mm from it.** One virtual
unit is 0.2123 mm, a Roboto cap is 0.71 em, one arc minute at 750 mm is
0.2182 mm, so a cap of `size` units subtends `size × 0.691` minutes:

| rung | mm | arc min | where |
| --- | --- | --- | --- |
| 88 | 13.26 | 61 | the hero |
| 52 | 7.84 | 36 | the corners, the petal - ISO 15008 calls 30′ comfortable |
| 34 | 5.12 | 23 | both shelves - legal for a deliberate glance, the floor is 20′ |
| 18 | 2.71 | 12 | headings, captions, units: furniture |

`104` is gone with the estimate that produced it: at 320 mm it is a 16 mm numeral,
and it had been chosen against an arithmetic that made 52 look illegal. `88` is
`1.69 × 52`, which clears the ramp's own 1.2× rule with room.

**Numbers are Roboto with tabular figures, not Roboto Mono.** A monospaced face
gives a comma, a colon and a degree the same 0.6 em cell it gives a digit, so
«12,4» and «2:15» fell apart into groups. Measured in headless Chrome: a Roboto
digit advances 0.5620 of its size at Regular and 0.5547 at Light, a comma 0.1969,
a colon 0.2422 - and `0`, `1` and `4` all advance identically *without* the
feature, because Roboto's figures are already tabular. `tnum` is asked for anyway:
a `Paint` on the car may resolve a different face, and a reserve field is a
contract rather than a hope.

### Movement

Frames are 30 a second and data arrives three times a second, and the gap is
closed by a **critically damped second-order follower** per animated quantity
rather than by waiting or by linear interpolation. The step is the analytic
solution rather than Euler: with a 120 ms rise and a 33 ms frame `ω·dt` is above
one, where explicit integration diverges - the panel would oscillate harder the
faster the pedal moved.

| | rise | fall |
| --- | --- | --- |
| the band and the hero | 120 ms | 300 ms |
| the glow | 1.5 s | 1.5 s |
| the revolutions | 250 ms | 400 ms |

Three rules against jitter: a dead band of half a kilowatt at zero, a neutral zone
of three kilowatts with three kilowatts of hysteresis around its own boundary
(inside it the hero and the band's body carry no colour at all), and the hero's
glyph rewritten at 4 Hz with half a kilowatt of rounding hysteresis. (The critique
asked for 2 Hz; the owner, who had driven with the previous panel, asked on
2026-09-04 for the live figures to answer about twice as fast, so the glyph rate
doubled and the hot poll interval went from 300 ms to 100 ms - about four
readings a second against two, with the shell busy some sixty per cent of the
time while the dashboard is up. Whether the car sustains that is on the list
below.) The peak hold
stays where it landed for three seconds and comes back at sixty kilowatts a
second. All of it is in `ContourMotion`, deterministic in `dt`.

The view runs at 30 fps and drops to 5 when nothing is travelling, which on a
parked car is most of the time; the followers are integrated exactly, so a 200 ms
frame is as correct as a 33 ms one. Nothing is allocated in a frame: the band's
gradient is built once over the span `0…1` and placed with a local matrix, the
glow's is built once at full alpha and dimmed with `Paint.alpha`, and the two
history buffers are fields.

### The trip on the right shelf

The first figure is the **net that left the battery**, `∫P dt` over the trip in
the pack's own units. Regeneration is a negative pack flow and is subtracted by
the integral itself, and so is whatever the engine put back, which is what the
other two cells' verbs are there to say: they are what came back, not what adds.
Three unsigned numbers under three nouns had been an invitation to sum them, and
their sum is not a quantity.

`РЕКУПЕРАЦИЯ` integrates **only over intervals with the engine off**. Under
generation a negative pack flow is indistinguishable from braking on this bus, and
the engine's share belongs in `ДАЛ ДВС`.

A trip starts with the first movement after P and ends at the next first movement
after P, so the finished trip stands on the shelf in full for as long as the car
does - which is when it is read. That needs the selector, and `GEARBOX_PARK`
(device `1011`, feature id `89129008`) is the same live-proven id the trip panel's
own `TripParkSignal` has read since it shipped; the cluster asks for it inside the
batch it already sends to that device four times a second rather than opening a
second shell. The figures survive a process restart through `TripJournal`, one record
written whole through a temp file and a rename, and restored only when the
odometer says it is about the road we are on.

### The two boxes, and what a running panel said about them

The panel was built, put on a live bench and watched, and everything that came
back was about the two histories - not their size this time, but what they were
saying. Four decisions, and they are the eighth drawing; the fifth, about the hole
the engine's sentence keeps when the engine stops, came from the second bench run
and is the ninth.

**The engine's box draws one quantity and speaks one sentence.** It carried two
runs and a legend telling them apart - «ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт» - and the
owner's verdict was that the legend was not understandable, which is the game lost:
a display read at 90 km/h does not get to need a key. The revolutions' line is gone
to the corner where the same number was already printed, and what is left is what
the engine put back, under **«● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН»** - what the
shape is, what it is worth now, how far back it goes. Neither «ГЕНЕРАЦИЯ» nor
«ОБОРОТЫ» is a word on this panel any more. The sentence is laid out right to left
off the shelf's edge with the figure in a two-digit reserve, so it and its unit
leave together when the engine stops while the words stay put; a face too wide for
the box shortens the window to «· 2 МИН» and nothing else in the phrase may go.

**And the reserve leaves with them.** The box outlives the engine by two minutes,
and for that whole time the eighth drawing kept the field standing empty:
«● ⎵⎵ В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН», which is 22 units of hole after a dot and
reads as a value that failed to arrive rather than as a field with room in it. With
no figure there is nothing for a reserve to reserve, so the phrase is assembled
without it and the dot closes up against the words. That is **one shift per engine
stop, not a jitter** - a reserve buys stillness while a *number* changes, and by
then there is no number left to change. `ContourPlan.legendMarkQuietX` is the
second anchor, and it is the only coordinate on the panel that depends on whether
a value is there rather than on what it is.

**And its scale is linear to 30 kW, clamped.** «Сплющен», said of a box whose
height had already taken every unit between the two guards, is a verdict on the
scale rather than on the geometry: at the 14 kW this car ordinarily returns, a
square root over 100 kW filled a third of the box and linear over 30 fills a half.
30 kW is what this generator does rather than a span borrowed from the band.

**The petal's zero line is its figure's own baseline.** It hung beside that figure
on a ladder of its own - 56 units tall with the zero four fifths down - and neither
number meant anything to anything next to it. Now the three lines bounding the
history are the three lines of the numeral: cap top, baseline, descender. Spending
rises through the cap on 0…30, a return hangs under the baseline on 0…10, both
clamped, and the box is 49.92 tall because that is what a 52 occupies.

**And the return is drawn only where it happened.** One field crossing a zero line
in one colour drew the same grey above and below it, and the blue rule meant to say
"this came back" ran the whole width whether anything had. Spending is one
continuous grey field that lies on the zero on a return bucket, because what was
spent there is nothing; the return is blue, one shape per run of return buckets,
standing on the zero on its own posts.

**Both boxes are steps of a fixed duration.** The petal's thirty buckets are a
hundred metres each; the engine's twenty-four are five seconds each, a bin being
the mean of the samples that arrived in it, and a bin nothing answered in breaks
the area rather than being drawn through. `EngineTraceSnapshot.bins` is that
resampling, and the bin that can be short is the newest one, at the edge where the
new data arrives.

### Why the engine's box does not flicker

`EngineTrace.snapshot()` returns the run from the oldest slot the engine was alive
in to now, rather than a padded 120. That one property is the box's whole
behaviour: its right edge is fixed and its width is the list's length, so it grows
from the right as the history fills, is never drawn empty, holds its width through
the two minutes after the engine stops, and leaves when the last live slot walks
off the left edge. **120 seconds of hysteresis with no timer anywhere** - the
trace's own length is the timer, so a winter jam restarting the engine every
ninety seconds never swaps the shelf back and forth.

The trace keeps one series since the eighth pass, and **the revolutions still
decide one thing in it**: whether a slot counts as one the engine was alive in. An
engine on a direct drive can turn for a minute returning nothing, and a box that
left in the middle of an engine run would flicker in exactly the way that property
exists to prevent. So `sample` takes the rpm reading and stores a flag, not a
curve.

### Rest states

Most of what this panel shows is, most of the time, nothing happening. They are
scenes rather than messages, and none of them is a sentence apologising for the
others:

- **the first seconds** are the band's hairline and its zero mark, and nothing
  else. A heading arrives with its first value rather than standing over emptiness;
- **link lost** removes every value and keeps every caption, at full brightness;
- **a single null** is the same rule applied to one reading;
- **standing in P** pays the trip's phrase out in full, three cells instead of one
  or two, and the petal grows its tenth - at 100 km/h a tenth changes three times a
  second, and standing still it is worth the resolution;
- **a charge** puts a countdown in the petal's seat, «2:15» over «до полной», and
  leaves the consumption history where it is;
- **no ADB key** is the skeleton and one line at 18 in the petal, and it is an
  instruction rather than an error: `ADB-ключ не подтверждён · Помощь →
  Диагностика`.

### The instrument system underneath

The panel is built on a small design layer rather than on constants chosen per
method, and that layer exists because of what an adversarial audit of the design
boards found: twenty-seven distinct type sizes where six were declared, eighteen
radii, thirteen optical stroke weights for one flat-line icon family, and four
sibling layouts whose first label started at four different heights.

- `InstrumentDensity` is the type ramp and the rhythm - `88 · 52 · 34 · 24 · 18 ·
  13 · 11`, none closer than `1.18×`, so two rungs can never read as the same
  size. The Contour draws four of them;
- `InstrumentFace` is the six ways this cluster sets type - a size, a weight and a
  tracking - and there is no seventh. It is an enum rather than three parameters at
  every call site because that is how the boards ended up with the same heading set
  two different ways on adjacent artboards;
- `InstrumentPen` is a drawing surface a renderer is *handed* rather than
  inherits, and it owns one `Paint` per face;
- `ContourPlan` is every coordinate the panel has, derived from five decisions and
  nothing else: one margin of 48, a rhythm of 8, the rungs, the cap height, and
  **two guards of 24 units off the stock zones, top and bottom.** The hero's cap
  top, both shelves' cap tops and the engine box's top edge are all the upper one;
  the band's lower edge clears the other by 18.

`ContourType` is how a plan learns how wide a string is, and it has two
implementations on purpose. A cell on either shelf is exactly as wide as the wider
of its caption and its payload, so a caption is a coordinate: `ContourType.BOARD`
is what headless Chrome measured for the boards and is the record the contract
test pins, and `ContourType.of(pen)` is the car's own `Paint`. The two are allowed
to differ by the face; the arithmetic between them is not.

### Mutation run

The panel is drawn, tested and not yet installed, so the only thing that can be
said about it before the car is whether its tests bite. **1,108 unit tests; 80
deliberate mutations**, one at a time, each reverted before the next. What was
mutated, branch by branch:

- **`ContourMotion`** (9) - attack and release swapped on the band; the half-
  kilowatt dead band removed; the neutral zone 3 → 2 kW; the colour hysteresis
  removed; the hero rewritten at 3 Hz instead of 2; the rounding hysteresis
  removed; the peak's hold 3 s → 1 s and its decay 60 → 120 kW/s; the sign of the
  follower's tracking term, which is a spring that overshoots;
- **`ContourScene`** (9) - staleness 2 s → 1 s; the engine box following the
  engine instead of the trace's last live slot; `CHARGING` with no dwell and with
  a half-second one; `LINK_LOST` on any missed packet; `PARKED` from a switch
  that never answered; P winning the scene over the box; revolutions read from a
  resting engine; a trip figure of zero earning a cell;
- **`TripEnergyLedger` and `TripJournal`** (12) - net becoming gross; recovery
  counted under generation; the trip cleared on entering P rather than at the
  first movement after it; the engine's minutes never accumulating; restoring
  from the journal skipped; the odometer taken absolute instead of as a delta;
  both re-anchor guards dropped; the restart gap 1 → 100 km; the engine's cell
  integrating a non-positive generation; the armed flag not persisted; the record
  written to whole units;
- **`ContourReadout`** (13) - a whole number on P; a point for the comma; «км»,
  «кВт·ч» and «· за 3 км» dropped; «ДВС · мин за поездку» shortened to «ДВС ·
  мин»; the trip phrase losing its leading separator; the average taking the
  returns in; an ordinary spread earning a cell; the hot margin 15 → 30 °C; the
  clock losing its zero padding; the generation scale turned back into a square
  root; the spread alert 40 → 60 mV;
- **`ContourPlan`, `ContourRuns`, `ContourGlyphs`** (24) - the guard 24 → 16; the
  petal's zero 384 → 397; its ladder 0…30 → 0…40; the engine bin 5 s → 4 s; trip
  seats counted from the wrong edge; the engine's sentence not closing up when its
  figure leaves; the petal box hung off the printed digits; the temperature cell
  forgetting the glyph; the exception's cell grown 100 units into the hero's
  field; the hero's field centred without its unit; the glow reaching past the
  lower edge; the band ignoring the sign of a reading; both petal series allowed
  through the zero; the run walk inverted and its runs cut to length one; the
  front motor's block moved to the rear axle; the wheels filled; the wheel stroke
  1.6 → 2.5; the family's height 24 → 18; the lit component drawn in the case's
  colour, in all three marks that have one;
- **`EngineTrace`, the hub and the signal table** (10) - a bin of nothing becoming
  a zero; a slot alive by generation alone; missed slots drawn through; the bins
  kept from the wrong end; the trace starting at the newest live slot; the park
  switch accepting `2`; P read from a zero; the cold map merged rather than
  rebuilt and then filled from the whole sweep; `RIGHT` offered again;
- **the board↔code join** (3, and the known pair among them) - one constant moved
  in Kotlin alone (`BAND_BODY` 14 → 16), one moved on the board alone (the reading
  rung 34 → 36 px in `ClusterContour.dc.html`), and one measured advance moved in
  `ContourType.BOARD`. All three failed, in both directions, which is what
  `ContourBoardContractTest` is for.

Seventy-six of those were written before any test was added and **sixty-seven of
them died at once. Nine survived**, and every one of them was a hole rather than a
bug - the behaviour was right, and nothing stated it:

1. **the band's own asymmetry.** Swapping `BAND_RISE_S` and `BAND_FALL_S` where
   the band is built changed nothing, because every follower test built its own
   follower. Killed by measuring the same 60 kW journey up and down through
   `ContourMotion` itself;
2. **the peak's decay rate.** The existing test only asked where the mark ends up,
   and it ends up on the tip either way. Killed by the slope between two samples
   taken inside the decay: thirty kilowatts in half a second;
3. **a generation of nothing.** «ДАЛ ДВС» integrated whatever the id answered
   while the engine ran, including the zero of a shutdown transition. Killed by a
   running engine returning `0.0` and then `-2.0` for an hour each;
4. **which side of zero the band leaves on.** Nothing asked. Killed by a test that
   states direction, the two spans (300 kW out against 100 kW back) and the clamp
   at both margins;
5. **P read from a switch that answered zero.** `parked` has three answers and
   only two were tested. Killed in `VehicleTelemetryTest`;
6. **the cold map's rebuild** - the rule the whole staleness design rests on, and
   it lived inside the poll loop where nothing could reach it;
7. **the front motor's block on the rear axle**, 8. **filled wheels**, and
   9. **the lit component drawn in the case's colour**. Three of the owner's four
   rules for the glyph family, and all three were decided inside `Canvas` calls.

The last four needed a seam before they could be tested at all, and both seams are
the `ContourRuns` argument applied again - a decision that lives inside a `Canvas`
call is a decision nothing can state:

- **`GlyphSurface`**, which `ContourGlyphs` now draws into. `InstrumentPen`
  satisfies it through one kept adapter, so a frame still allocates nothing, and
  `ContourGlyphsTest` satisfies it with a recorder and reads back what was drawn.
  `ContourGlyphs.onRearAxle` is the axle rule as a sentence rather than a boolean
  at a call site;
- **`VehicleColdSweep.rebuild`**, lifted out of the poll loop the way
  `VehiclePollLoopGate` was, with `VehicleColdSweepTest` beside it.

The remaining four mutations were written once those seams existed - every block
on the rear axle as a rule rather than at a call site, and the case's colour given
to the pack's cell and to the inverter's current - and all four died on arrival.

**No behavioural defect was found.** The nine survivors were all coverage, and the
sixteen tests added for them state what the panel promises rather than what it
happens to compute.

**And that verdict did not hold.** A four-agent review of the same tree on
2026-09-04 returned **twenty-eight confirmed findings** - among them a refused
frame swallowing a sweep, a cold value that could not survive one flaky read, a
trip that grew while the car was parked, a colour held across zero, a charge
countdown that needed a road behind it, engine bins anchored to the run instead
of the clock, and two captions naming a window they did not have. Eighty
deliberate mutations had found none of them.

That is not an argument against mutation testing; it is what mutation testing
measures. A mutation asks *does any test notice this line changing*, and the
answer stays yes as long as some test computes the same thing the code computes.
Every one of the twenty-eight lived where the tests agreed with the
implementation and neither of them agreed with the *situation* - the engine
starting five seconds ago, the log holding twelve buckets, the car standing on P
with the trace still warm, the shell timing out mid-sweep. Sixty-seven mutations
died at once because the arithmetic was pinned; the arithmetic was never what was
wrong.

So the rule the ninth pass took from this: **a test that restates the
implementation cannot fail for the reason the panel is wrong.** Write the
scenario - a state the car can be in, and what the driver should see in it - and
let the arithmetic follow. The tests added for the twenty-eight are all of that
shape, and they are why the tenth pass's own defects were reproducible before
they were fixed.

### What still waits for the car

From `CRITIQUE.md` §5 and `VERDICT.md`, in the order they matter to this panel:

1. **the boundaries of the stock graphics** - a white board with an 8-unit grid on
   the whole cluster, one photograph, and a second with the bulb check lit,
   indicators on and ADAS active, because the corner apertures may be lamp zones.
   All five concepts named this first;
2. **the sign of `POWER_KW`** under a known acceleration and a known braking. It is
   inferred from one parked charge, not proven (`VehicleConvention`);
3. **whether `GENERATION_KW` is inside `POWER_KW`**, from one engine run on a flat
   cruise. `ClusterDashboardRenderer.GENERATION_ON_BAND` is false until it is
   answered: the engine's share is drawn as a separate line under the band rather
   than as a seam behind its tip, which says the same thing without the claim;
4. **a photograph of the hero under the stock speedometer** at 30-40 km/h, to see
   whether «34» and «34 км/ч» merge;
5. **whether the stock dimmer darkens our window at night.** If it does,
   `ClusterDashboardRenderer.NIGHT_DIM` stays at 1.0 forever;
6. **how often the engine starts per hour in a winter jam**, which is what says
   whether 120 s of shelf hysteresis is enough;
7. **frames per second inside the `Presentation`**, and whether the vendor
   composites over our edges.

Nothing on this panel has been in front of the owner yet: it is drawn, tested and
built, and it has not been installed.

### Telemetry ownership

Since 2026-08-27 the cluster dashboard is the only UI owner of
`VehicleTelemetryHub`. `setDashboardActive(true)` starts polling when its view
is attached and visible, and hiding or detaching that view releases the sole
activity claim. The retired vehicle/engine page flags and their page-dependent
signal filter are gone. The hub therefore reads the complete hot set and cold set
the cluster needs while it is visible, including revolutions, generation and the
lamps.

The hot set gained `GEARBOX_PARK` with the Contour, which is what bounds a trip.
It is not a new discovery and not a new channel: device `1011`, feature id
`89129008`, the same one `TripParkSignal` has read since the trip panel shipped.
That reader keeps its own shell because it runs on the head unit without the
cluster; the cluster asks in the batch it already sends to that device, which
costs about five milliseconds of a sweep that already spends a hundred and thirty
on shell overhead.

**Cold values are rebuilt from each cold sweep** (`VehicleColdSweep.rebuild`)
rather than merged into a map
that kept them forever. That changed with the Contour and it is what makes its one
staleness rule mean anything: a value is removed two seconds after its last
sample, so "absent from the snapshot" has to mean the same thing on a signal
answering three times a second and on one answering every ten.

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
phase and confirmed flash-mode FIDs. Denza Apps starts that listener through a
separate passive local-ADB resident lane only while Mirrors is enabled. The
same evening the guard went through three contracts on the car, and the current
one is the simplest: the 10 Hz stock-window observer is again the only Show
authority, exactly as before the listener existed; a raw onset pulse (`2` or
`4`) is only an early-teardown trigger for an active Denza camera of the other
side; a pulse for the side already starting or showing is ignored; nothing the
listener does or fails to do can open a camera, and hub unavailability never
touches an active one. Every quarantine the stock caused ends on a clean stock window: two
consecutive polls of one unambiguous side with the runtime idle and the vendor
teardown complete, or five such polls for the side that was torn down, whose
old window outlives the switch; only our own failures still wait for neutral. Manual lever cancellation
live-produced an opposite-direction pulse, which is why the raw phase never
selects a side. Full evidence, resource measurements, the two retired contracts
and the remaining acceptance matrix are in
[vehicle-data-findings.md](vehicle-data-findings.md#targeted-turn-signal-events-2026-09-04).

The early teardown itself is the live-proven part: after the original direct
left-to-right run crashed stock AVC, the instrumented guard detached the old
local surface 3 to 4 ms after the next right onset, completed vendor release in
105 to 121 ms, and AVC kept its PID across every later run. The two retired
contracts gated Show on the listener (a same-epoch confirmed mode event after
the onset, plus a stable window match), which is what made the camera appear
"randomly": the raw FID emits several pulses per lever movement, a second
same-side pulse could re-arm the gate after the mode event had already passed,
and any helper reconnect or the 2 s pending timeout then left the turn dark.
The window-only Show contract passed its first drive on 2026-09-04 (three fast
left/right switches, AVC PID unchanged); the one dark signal it produced, a
re-engaged lever 125 ms after the stock closed its window, is fixed by the
recovery rule above and that revision has not been driven yet. Details are in
vehicle-data-findings.md under "First drive of the window-only contract".

### Startup timing baseline (2026-09-04, instrumentation-only candidate)

The startup worktree starts at `90821f086cd17cd7568dd6f583a38438818b960a`.
Before installing the timing candidate, the car's APK and the main checkout's
APK both hashed to
`851bab49b76420728d79d45a76c733b684f5e341a440dd6b16f886d6cc121585`.
An exact pulled rollback copy is retained in ignored
`captures/mirrors-startup-baseline/rollback-851bab49.apk` in the main checkout.
No performance improvement has been measured yet.

This candidate keeps both diagnostic AVC transactions (`getName`, buffer type),
`initDisplay`/`setViewpoint` order, the reducer, the 100 ms fixed-delay polling
schedule, CAN subscriptions and teardown barriers unchanged. It adds monotonic
timestamps on stock-window changes and camera commands, and one first-buffer
callback per renderer startup. It does not perform additional vehicle queries.
The callback records each Binder call's duration, surface/bind readiness and
READY-to-first-update time. READY still means initialization acknowledgement;
the new callback means a TextureView buffer update, not physical scanout or
proof that the pixels show the expected camera. Subsequent frames do not read
the clock or allocate timing strings.

Run protocol, with this session as the only installer:

1. Record the installed hash, AVC PID, enabled Mirrors settings and crash buffer.
   Preserve the existing tunnel and log buffer. Start from indicators off,
   no visible stock camera, Mirrors enabled and runtime `ready`. Do not reset
   firmware keys or force-stop stock services.
2. Announce and install the instrumentation APK with `adb install -r`, recheck
   its hash and monitor readiness, then start a narrow logcat capture containing
   `DenzaMirrorMonitor`, `DenzaClusterScene` and `AndroidRuntime`.
3. Owner operates a stationary car, brake held, in the same gear in which the
   stock side cameras normally appear. First ordinary left activation: expect
   stock window, Show request, READY, then exactly one first-update record.
   No first update, stock-only video or a wrong/disappearing camera falsifies
   the successful-start assumption and must stay in the result, not be excluded.
4. After indicators off and both windows closed, wait at least five seconds.
   Repeat ordinary right; then collect three starts per side if the initial
   canary succeeds. No rapid reversals or reboots in this baseline. Label the
   first post-install start separately from later starts; this is not a
   cold-boot benchmark. Stop on an AVC crash and preserve the crash trace.
5. Recheck APK hash, AVC PID and crash buffer. Report all attempts, failures,
   median and range by side and stage. Six starts are exploratory, not enough
   for a stable tail percentile. Only then select an optimization and repeat
   exactly the same protocol and instrumentation.

The polling timestamps bound when our observer could have detected the stock
window; they do not establish the exact lever-to-visible-image latency. An
external high-frame-rate recording would be required for that user-visible
end-to-end claim; do not load the head unit with screen recording for this run.

Host check: `python3 tools/check_mirrors_startup.py --red-ref 90821f0` compiles the
actual renderer against small Android API shims. It checks transaction-order
preservation, rejection, stage arithmetic, first-update once-only delivery and
cancellation. These are software contracts, not simulated firmware evidence.

First live sample, 23:03:50–23:03:58: instrumentation APK SHA256
`2d03702afbd4bb582cf9f580928f8c1795336b524189647d8b585a043e7d22ff` was verified
on the car before and after the run. The owner confirmed the left camera
appeared. Its first post-install startup (generation 1) took **391 ms from our
first observation of the stock window to the first TextureView update**,
390 ms from Show request. The sequential stages after request were 13 ms to
scene dispatch, 110 ms to renderer start, 84 ms until Binder connection,
1 + 0 ms for diagnostic reads, 170 ms for `initDisplay`, 0 ms for viewpoint and
12 ms from READY to first update. Surface readiness at renderer +38 ms overlaps
the Binder wait, so it must not be added to this sum. The window query itself
took 31 ms and is outside the 391 ms above. This single sample does not support
the proposed diagnostic-read removal as a material latency improvement; most
time was in scene creation, binding and vendor initialization.

The left/off gesture also produced an opposite-onset guard event at
23:03:54.330 while the observed stock window remained left. Local detach took
5 ms and vendor release 125 ms from the onset timestamp. The reducer reopened
left (generation 3) at 23:03:55.004 before confirmed mode became OFF and before
the stock window closed. That recovery took 143 ms from request to first
update, with a 59 ms `initDisplay`. It is recorded separately, not counted as
another ordinary left-start sample. The raw onset log does not prove the user
activated right. No AVC/application crash was recorded, AVC PID stayed 5050,
and the monitor returned to IDLE at 23:03:57.914. More ordinary left/right
samples are still required; no optimization has been installed.

Second live sample, 23:05:28–23:05:35, ordinary RIGHT from IDLE (generation 5):
276 ms from stock-window observation to first update, 272 ms from request.
After request: scene dispatch 5 ms, scene setup 52 ms, bind readiness 46 ms,
diagnostic reads 1 + 0 ms, `initDisplay` 157 ms, viewpoint 1 ms and READY-to-update
10 ms. The window query took 35 ms, outside the 276 ms. The same-side onset
during startup was ignored without a duplicate Show. During the requested off
gesture, an opposite/left onset again detached and freed the camera while the
stock right window persisted (3 ms and 98 ms from onset, respectively), followed
by a same-side reopen (generation 7, 144 ms request-to-update). Both recovery
events remain separate from the ordinary-start statistics. The owner replied
"готово" for right without separately describing the picture; the trace confirms
our first buffer update, not its visual content. APK hash and both process PIDs
were unchanged, crash buffer empty, monitor IDLE at 23:05:34.962. Two ordinary
starts are insufficient to infer side asymmetry or stable typical latency;
four repeat starts remain in the planned baseline.

#### Completed baseline and confirmed cancellation flicker

The full capture ends at 23:08:22.798 and contains **ten ordinary starts and ten
unwanted same-side reopens** (the owner performed more repetitions than the six
planned; all are retained). The ordinary observation-to-first-update times, in
order, were LEFT 391, RIGHT 276, LEFT 260, RIGHT 273, LEFT 271, RIGHT 330,
LEFT 244, RIGHT 276, LEFT 250, RIGHT 243 ms. Combined median **272 ms**, range
243–391; LEFT n=5 median 260, RIGHT n=5 median 276. The first post-install start
was 391 ms; the subsequent nine had median 271 ms, range 243–330. These are
exploratory observations, not a cold-boot/tail-latency benchmark or proof of a
side-specific difference. All ten ordinary requests reached first update.
`initDisplay` accounted for 150–232 ms (median 157); the two diagnostic reads
combined took only 0–1 ms in every ordinary start. Removing those reads is not
a meaningful optimization target in this baseline.

The owner explicitly reported: cancelling the signal makes the camera disappear,
then reappear briefly, then disappear again. The trace confirms this sequence
after all ten ordinary starts. Example on RIGHT: local surface detached at
23:08:20.128; mode OFF observed at 20.238 while the stock right window remained;
the reducer issued another RIGHT Show at 20.857; first buffer update at 20.975;
the stock window finally closed at 22.302. Thus our second image starts about
0.85 s after detach and lasts about 1.33 s until the observed stock close.
Across the series, the residual stock window survived roughly 2.18–3.06 s after
the opposite-onset guard event, not the 100–300 ms presumed by the same-side
reopen comment and unit test.

**Cause is in our recovery logic, not a recorded AVC crash.**
`MirrorTransitionReducer.reduceQuarantined` treats five consecutive observations
of the preempted side as proof that the lever came back, despite the window
never having closed. Cancellation produces an opposite onset, so the guard
removes our surface for switch safety; the old window then satisfies the
five-poll reopen rule. The next actual stock-window close removes our camera
again. `theStalePreemptedSideWindowCannotReopenBeforeTheLongerRun` currently
encodes that false fifth-sample assumption rather than the new cancellation
trace. This rule and the guard are unchanged from the baseline commit; the
instrumentation did not add them.

Acceptance for cancellation is therefore **failed**, even though every initial
startup reached a buffer update and no crash was recorded. Fix same-side rearm
before pursuing startup acceleration: a surviving old window is not a new
camera request. Preserve the early opposite-side teardown and require regression
coverage for ordinary off, rapid opposite-side changes and real same-side
re-engagement. Merely raising the poll count or disabling the crash-protection
guard is not the proposed fix. No behavior changes have been made from this
finding yet.

Final installed hash remains `2d03702a…`, app PID 24607 and AVC PID 5050 unchanged,
crash buffer empty. Capture was stopped without touching the tunnel. The ignored
335-line trace in the main checkout,
`captures/mirrors-startup-baseline/baseline-timing.log`, hashes to
`c6e5bde03d34defac614ae6a384bd07b675c15367fbbb11e949c6f39e8e54018`.
The exact rollback APK remains available alongside the trace.

#### Cancellation-fix candidate (before acceleration)

The recorded cancellation became a regression test: 35 consecutive clean
observations of the surviving preempted-side window must emit no Show. It failed
on the baseline at the fifth sample, then passed with the fix. The old test that
treated five polls as proof of a returning lever was corrected, not retained as
a firmware truth.

The reducer now rearms a surviving same-side window only with a fresh matching
confirmed-mode observation strictly after the preempt. OFF/hazard/unknown,
missing, pre-preempt, equal-time and future data cannot do so. A later verification
timestamp does not refresh the original observation. Mode loss resets that
path's settling count, not the whole camera state machine. An unambiguous absent
stock window ends the old cycle; the next window can reopen after two clean
polls without CAN evidence. Fresh same-side evidence retains the existing
five-sample settling requirement. Opposite-side recovery, ordinary first starts,
early CAN teardown and the vendor-free barrier are unchanged.

This consumes the already-cached diagnostic mode read; there are no additional
vehicle calls, helper processes or leases. Startup timing instrumentation and
both diagnostic Binder reads are unchanged for comparison. Four targeted
behavioral mutants were tested and each failed its corresponding assertion:
remove the same-side guard; use verification instead of observation time; forget
the window-gap rearm; skip the preemption-in-flight barrier. Every mutant was
restored before the final build. These are local software checks, not car acceptance.

Live acceptance starts from indicators off, monitor ready and no stock camera.
First left/off and right/off must each show once and then stay hidden until the
next actual activation (no generation for the old stock tail). Only after that
passes, check re-engagement of the same side and direct side changes; a missing
new camera or AVC crash fails acceptance. Stop on a crash and retain its trace.
Acceleration remains a separate next step after this fix's car check; no new
polling rate, persistent surface or vendor initialization order is introduced.

Cancellation canary result, 23:20:04–23:20:27: fix-only APK SHA256
`f9ade56e954a568f4107bb86a2ab3412bd476099047a81ba6859c2df01f045db` verified before
and after. One LEFT and one RIGHT activation each reached first update (332 ms
and 284 ms after stock-window observation). Both off gestures tore the camera
down but produced **zero repeat Shows** while the old stock window remained.
The owner explicitly confirmed the repeat appearances disappeared. AVC PID 5050
and app PID 30720 stayed unchanged; crash buffer empty. This accepts the two
ordinary cancellation canaries, not the wider quick-re-engagement/reversal
matrix. The saved trace `cancellation-fix.log` hashes to
`9328977acdc345766505faba8b95de6208c3a98f6005a8b13a2f2587232e8fbc`.

#### Acceleration candidate: cosmetic notification work off the startup path

Only `ClusterSceneService` notification scheduling changes relative to the
fix-only APK: remove the intermediate "Camera display is ready" notification
before renderer startup and the "Starting ... mirror" notification after bind
is requested. They each build a PendingIntent and call NotificationManager on
the UI thread. The foreground-service notification in onCreate is unchanged.
One "Showing ... mirror" update is posted after the first TextureView-update
callback; at execution it requires the same command generation and READY state.
Superseding Hide/Show/failure/destroy drops it, and a notification error does not
fail the camera session. Base-scene notifications and camera error/teardown
notifications are unchanged. There is no extra executor/thread, timer, retained
surface, CAN subscription, polling frequency change or native AVC call change.

This is a conservative latency candidate, not a measured improvement yet.
The old and new logs retain identical startup timestamps. Compare ordinary
left/right starts, scene-setup/bind time and vendor-init time separately; a
faster vendor init alone is not evidence that moving notifications helped.
Check absence of cancellation flicker again, and do not claim physical
lever-to-scanout timing from a TextureView update callback.

Acceleration-candidate live result, 23:26:02–23:27:43: APK SHA256
`7c3e3aa3d089fa09b92380089d16293acfbeefb0968a8e22c498346b69a77193`, nine first
updates, no recorded cancellation-tail reopen or crash, AVC PID 5050/app PID
15823 unchanged. The owner described a small subjective speedup. Four starts
were from IDLE and five were recovery onto new/opposite stock windows; the latter
waited 125–139 ms from first stock observation to Show because of the unchanged
two-clean-poll rule. This is not a matched comparison to the ten ordinary
baseline starts. Request-to-first-update times were 389, 253, 226, 252, 243,
355, 307, 351, 237 ms (median 253); first-stock-observation-to-update times were
392, 382, 228, 380, 250, 492, 310, 490, 362 ms. Do not mix these metric origins.
The first post-install start remained 392 ms versus the original 391 ms.
`initDisplay` still took 151–212 ms. Repeated-start scene setup took 26–76 ms
and binding 26–42 ms. One READY-to-first-update outlier was 127 ms; cause unknown,
not excluded. A speedup percentage is not established by this mixed series.

Potential next targets are bounded preparation of app-owned UI before Show and
investigation of the roughly 130 ms recovery wait. Idle Binder preconnection
would be a separate lifecycle experiment, not assumed safe here. No faster
polling, reduced safety interval or retained vendor surface was implemented.
The raw 170-line capture `notification-startup.log` hashes to
`aff61efdd8988687eb58d441a4d26b5e551506d4b13ac9b568886a3454a187cd`;
capture stopped with the existing tunnel preserved.

#### Acceleration candidates: skip unused camera-start work (2026-09-05)

This local candidate starts at
`9671d56cc4f9524e80123b284ee73bd3ce181ab4`. The exact control APK retained as
`captures/mirrors-camera-scene/baseline-9671d56.apk` hashes to
`c956b51b4eea01170b84d763bfcec4e6c8ffd1eed89bb41980162e6e1f9c4f99`; it is
the APK built from that base and the verified current `0.6.1-alpha` release
asset. The camera-scene-only candidate APK is
`captures/mirrors-camera-scene/candidate-camera-scene-9671d56.apk`, SHA256
`f910a83de9e28c496cd0fb1259d28e89a9b6c145091ed57e710e6311139dc177`.
The combined camera-start candidate, which also includes the display-lookup
change below, is
`captures/mirrors-camera-scene/candidate-camera-startup-9671d56.apk`, SHA256
`0ffdaf8c3d4f101c00bce501b95bbf1c0043fbd414597436802598223316e21b`.
All three are ignored local artifacts. `versionCode` remains 44 and neither
candidate has been installed from this worktree.

`ClusterSceneService.prepareScene` now passes the selected `cameraLayer` into
its `ClusterPresentation`. The presentation creates and attaches the invisible
map `SurfaceView`, map shade and dashboard container through
`createBaseLayers(root)` only for the base presentation. Camera presentations
still create the camera frame, `TextureView`, camera shade, diagnostic layer
and `AvcCameraRenderer` in their original order. Base presentations retain the
original map callback and map/shade/dashboard order.

`ClusterDisplayResolver.resolveCameraOverlay` now shortlists live displays by
non-default id and the exact known camera-overlay name before calling the
descriptor path that reads real metrics, type and flags. Only those exact live
candidates pay for the expensive description. The unchanged final selector
still rejects an app-owned display whose descriptor changed, keeps duplicate
exact matches ambiguous and fails closed when no valid match remains. This adds
no display cache and does not change the exact-name policy or the selected
display.

These changes are limited to construction and attachment of unused app-owned
base views and to deferring unnecessary display description. They do not change
camera texture/frame/crop, the diagnostic layer, vendor
`initDisplay`/`setViewpoint`/free ordering, teardown or generation fences, CAN,
the reducer, the 100 ms polling schedule, standby behavior, or the base scene.
They add no idle prewarming, surface cache or Binder preconnection. The earlier
26–76 ms figure covered the whole scene-setup stage of a previous live build,
not the cost of the removed layers or the avoided descriptor reads. It therefore
neither predicts this candidate's saving nor proves a current improvement.

The new `CameraSceneContentContractTest` contains four **source-wiring checks**,
not an Android window or `SurfaceView` runtime simulation. On the base source,
three assertions failed and the existing-caller control passed; the retained
evidence is under `captures/mirrors-camera-scene/scene-evidence/`. The candidate
then passed all four. Three compiling structural mutants were also exercised
one at a time: unconditional base-layer creation, forced
`cameraLayer = false` at presentation construction, and routing the base factory
to `cameraLayer = true`. Each run completed four tests with exactly one failure
and no errors. All mutants were restored before the final checks. This proves
the checks reject those wiring mistakes; it does not prove real Android view
creation behavior or performance.

The resolver's initial test source did not compile against the base because its
live-selection seam did not exist; that is compile-only RED evidence, not a
behavioral failure. Final targeted resolver checks passed 13/13. Four compiling
mutants then failed as intended: restoring eager description of every display
failed four tests, selecting the first duplicate failed one, relaxing the exact
name failed one, and removing the default-display prefilter failed two. Stable
logs are under `captures/mirrors-camera-scene/resolver-evidence/`. These are
host-side selection/callback checks, not measurements of Android display calls.

Scene-only local evidence before integration passed 1276 tests with zero
failures, errors or skips. Final integrated evidence for both changes passed
`:denza-apps:testDebugUnitTest` with 1283 tests and zero failures, errors or
skips; `:denza-apps:assembleDebug` passed; `:denza-apps:lintDebug` passed with
zero errors and 44 existing warnings.
`python3 tools/check_mirrors_startup.py` compiled the actual renderer against
the host API shims and passed its nine tests. Those renderer tests preserve the
startup/vendor contracts but do not exercise this private Android presentation.
The retained integrated Gradle log and reports are under
`captures/mirrors-camera-scene/scene-evidence/` and
`captures/mirrors-camera-scene/final-full-validation/`.

`python3 tools/analyze_mirrors_startup.py` now parses saved logcat captures
offline and can emit human-readable or JSON summaries and matched A/B reports;
it never connects to the car. Its 19 synthetic parser/correlation tests pass.
As a fixture check, it exactly reconciles the three 2026-09-04 captures: the
baseline contains ten ordinary starts plus ten potential continuous-window
reopens, the cancellation-fix canary contains two ordinary starts and no repeat,
and the notification capture contains four IDLE starts plus five recoveries
with the already documented 253 ms mixed request-to-first-update median. The
retained reports are in `captures/mirrors-camera-scene/timing-evidence/`.

The analyzer deliberately does not infer `first_after_install`; that label
requires externally verified `PID:generation` metadata. A comparison pools only
an exact origin, side, cohort and window-context match, reports excluded or
inconclusive attempts and warns on small samples or unverified metadata. Its
first-update endpoint is still only the TextureView callback, not correct pixels
or physical scanout. Thus the old captures validate the parser, not the speed of
either candidate in this section.

Live acceptance and any speed claim remain pending because the car was not
available. Use a matched A/B on the same car configuration, verifying the
control and candidate hashes before and after their respective runs. Label the
first start after each install separately, then collect three ordinary starts
per side. After every start, turn the signal off, wait for the stock window to
disappear completely, then wait at least five seconds before the next start.
Require exactly one generation and one first-update per activation and no
repeat Show during off. Compare dispatch-to-renderer-start as the primary
candidate-sensitive interval; report request-to-first-update and vendor init
separately. After timing, smoke-test camera CENTER and SIDES crops, then, with
the camera off, base navigation and dashboard. Do not add rapid reversals to the
initial performance canary; they remain a separate safety run after ordinary
behavior passes.

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

Since 2026-09-05 the navigator action is contextual: **На приборку**, then
**Вернуть**. If the selected navigator has no running task, the first action
opens it on display `0`, waits `900 ms`, makes at most five more bounded checks
`700 ms` apart, and continues the same projection automatically once its task
appears. A package-and-launch token fence rejects delayed discovery from an old
selection or superseded launch; changing the selection also closes that launch's
transfer state. Dashboard actions, missing-app/display barriers, transition and
duplicate-action rejection, and return/reprojection recovery are unchanged.
Policy, source-wiring, cancellation, and stale-callback tests pass locally. One
cold/missing-task canary passed on the car on 2026-09-05 with APK SHA-256
`ba9a43094296c98ebbd0eeb1e4185ac46bfff656ad977dea176e6fdf5e7eb034`: after
Yandex Navigator was force-stopped and its task was confirmed absent, one press
started task `158` at `09:55:27.796`, created projection root `159` on display
`7` at `09:55:35.788`, and completed the focused projection at `09:55:36.564`.
The owner visually confirmed the automatic transfer, then confirmed the return
of task `158` to root `4` on display `0` at `09:56:59.504`; projection root `159`
was removed at `09:56:59.649`. With Yandex Navigator already running, the next
press created root `161` on display `8` at `09:57:12.270` and completed the
focused re-projection at `09:57:13.120`. Focused live acceptance therefore
passed all three intended action states: one-tap launch and projection with a
missing task, return, and warm re-projection. The installed APK hash remained
the same, the app and AVC processes stayed unchanged, and the final crash buffer
was empty. Selection changes and launch-discovery timeout paths have not been
exercised live, so this is not acceptance of every navigation scenario. The
picker re-reads the installed allowlist whenever it opens, the selected package
is saved, and projection sessions stay in memory and end with the process. The
automatic **Map mode** implementation also remains in code, but its unfinished
UI switch is hidden in the current build.

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
  did not fix it. The CAN-onset early teardown passed two instrumented
  stationary left-to-right canaries on the gated builds: local detach preceded
  stock rebuild, vendor teardown completed before the opposite-side reopen, and
  AVC survived. The window-only Show contract passed the same canary on
  2026-09-04 at 22:17 (three fast switches in both directions, AVC PID
  unchanged); its revised reopen rule has not been driven yet. The
  cancellation, hazard, sleep/wake, repeated stress, moving-speed, and
  second-firmware matrix remains open.

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
listed above remain open Denza Apps work; two guarded left-to-right transitions
on the retired gated builds are evidence for this car, not a general
rapid-switch guarantee.

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
