# Energy display contract: the cluster and the car page as one instrument

Normative for the Contour panel on the driver's display and for the car page of
the head-unit strip. Both draw from one `VehicleTelemetry` snapshot; this page
owns what they say about energy, in what words, in what shapes, and how that is
proved. Where `docs/instrument-display-findings.md` or the canvas README describe
the same things differently, this page wins; the two audit sections written into
the findings on 2026-09-05 and 2026-09-07 are absorbed here and the open items
they raised are the list at the end.

Written 2026-09-07 after the owner's first two drives with the panel. What those
drives showed was not five bugs but one method failure: two screens designed
one after the other, each with its own definitions, on signals that had been
read once with the car parked. So this page starts from the definitions, and the
code that follows it starts from a recording.

## 1. The rule above the rules

**One quantity, one definition, one set of words, on both screens.** A number
that appears on the cluster and on the car page is computed by one function from
one snapshot and printed through one formatter. The two renderers own geometry
and nothing else. `EnergyReadouts` (name binding; see §7) is that function, and a
test feeds it the same snapshots both renderers get and asserts the strings are
equal.

**A claim needs a recording.** Everything in §2 that depends on what a signal
means in motion is marked *open* and is drawn in the form that makes no claim
until `captures/vehicle-log/` holds a drive that settles it. `tools/vehicle_log.py`
is the recorder; it runs from the host and changes nothing in the product.

## 2. Quantities

### 2.1 Pack power, now

`P` = `POWER_KW` in `VehicleConvention.load`, positive out of the pack. Proven
parked (a wall charge read −2 against the charger's +2.4; the engine's own
generation read +8 against −8 on this id). Sign under acceleration: *open*.

The *direction* is a word and a colour, never a sign:

| |P| | word | colour of the figure |
| --- | --- | --- |
| below 3 kW (`ContourMotion.NEUTRAL_KW`, with its 3 kW hysteresis) | «БАТАРЕЯ» | `MUTED` |
| P ≥ 3, out | «ИЗ БАТАРЕИ» | `INK` |
| P ≤ −3, in | «В БАТАРЕЮ» | `RETURN_INK` |
| in, engine generating (§2.5) | «● В БАТАРЕЮ ОТ ДВС» | `RETURN_INK` |
| in, charger agreed (`VehicleTelemetry.charging`) | «● В БАТАРЕЮ ОТ ЗАРЯДКИ» | `RETURN_INK` |

The figure is `|P|` rounded to a whole kilowatt. **The minus is never printed for
power**, on either screen. The cluster already says direction with the band's
side and the hero's colour; the car page says it with the word and the colour.
The car page printed «В БАТАРЕЮ» over «−25 кВт» because the word and the sign
were decided in two places; there is one place now.

**While the charger has agreed (`VehicleTelemetry.charging`), `P` is
`−|CHARGE_KW|` on both screens.** The pack's own id reads zero or a small load on
a car standing on a charger - the board electronics - so the two would otherwise
be one event drawn twice: the cluster substituted and the car page printed the
raw id, and the same charge was 7 kW on one screen and 0 on the other. The
substitution is `EnergyReadouts.packKilowatts`, and it is the only place either
screen decides what the pack is doing.

**Drawn in the Luminofor palette (2026-09-23).** The words and their colours
above are the car page's, which prints them in its sentence case - «Из батареи»,
«● В батарею от ДВС» - derived from the capitals by `EnergyReadouts.sentence`, so
the two cases cannot become two sentences. The cluster prints no direction word:
the hero is ink out of the pack and while neutral (the stock flat white; a dimmed
hero read as a fault), blue coming back, and the «кВт» beside it is grey, blue
coming back. The side the beam takes says the rest.

The colour names on this page are roles, and since the Luminofor palette each is
the car's own stock colour: `INK` is the ink `#DAE1EB` on the cluster
(`ClusterInk.INK`) and the white on the head unit (`HeadInk.WHITE`); `RETURN` and
`RETURN_INK` are the stock blue (`ClusterInk.BLUE`, `HeadInk.BLUE`); `MUTED` is the
grey of a caption (`ClusterInk.GREY`); `MUTED_DEEP` is a faint field of the ink,
added over black. The palette entries that carried these names in the app
(`DenzaPalette.RETURN_INK` and its neighbours) went with the Contour's drawing.

Unavailable is not zero: no figure, the caption stays, exactly as the cluster's
staleness rule has it (`ContourScene`). And a read that did not land does not
move the colour's hysteresis: the neutral zone remembers where the screen was,
and a dropped sample is not a reading of zero.

### 2.2 Consumption over the last ten kilometres

`C₁₀` = net energy over road: `Σ Eᵢ / Σ dᵢ × 100` kWh/100 km over the closed
buckets of the window that have known energy, where `Eᵢ` is the signed integral
of `P` over bucket `i` and `dᵢ` its road. Signed: regeneration and the engine's
charge reduce it. It is the pack's own consumption, the same integral the trip's
«ЗА ПОЕЗДКУ» figure is, over the last ten kilometres instead of the trip. It is
**not** the hybrid's fuel-and-electricity consumption, and nothing calls it that.

`ConsumptionWindow.mean` used to drop returning buckets from both the sum and the
count, so `[10, −8, 30]` printed 20 where the road cost 10.7. That was a
definition nobody had written down, and it goes.

Printed whole on the move and to a tenth standing on P, on both screens
(`ContourReadout.consumption`). Negative - a long descent - prints with its
minus in `RETURN_INK`; it is the one signed figure on either screen and it is
signed because it is an exception.

**Energy while standing is the trip's, not the road's.** Two minutes of the engine
charging on P put 0.33 kWh into the pack on 2026-09-18, and a log that files
standing energy into the next hundred metres of road would have drawn that as a
blue shelf on the cut for the next kilometre. `ConsumptionLog` integrates `P`
into the road's bucket only while the car moves - `VehicleSignal.VEHICLE_SPEED`
(`0x94400008`, dev 1013, tx 7, polled hot so the question is asked of the
interval and not of a reading ten seconds stale) above
`ConsumptionLog.STANDING_KMH` = 0.5 - and a sample with no speed reading counts
as moving, because a missing read is not a stop. The trip (§2.4) keeps every
joule, standing or not; it is the one figure that is about time as well as road.

The window is the last `ConsumptionWindow.KM` = 10 km of **recorded road**: the
newest closed buckets that are readings (§2.6), taken back until their road sums
to ten kilometres, whatever the odometer says about the road between them. The
unit names it: «кВт·ч/100 км · за 10 км» once ten kilometres of readings are in
the log, and «· за 3,7 км» while it is still filling - the *known* road, not the
bucket count. The car page prints the same unit in one sentence over its chart,
«Расход 16,9 кВт·ч/100 км · за 10 км» (since the Luminofor strip, 2026-09-23; it
used to set «ЗА 10 КМ» in capitals under its own figure), and never rounds a
filling window to a whole number.

### 2.3 The history behind that figure

One chart, drawn twice. **The axis is recorded road, and a point is a hundred
metres of it.** One point per reading bucket (§2.6) - a hundred metres of road
with known energy - and the value at a point is the mean over the last ten
reading buckets ending at it, a kilometre of recorded road: `Σ E / Σ d × 100`
over those ten. A trailing mean rather than a centred one, so the newest point is
a figure with a meaning of its own: what the last kilometre cost. The newest
hundred points are the window, and they are the same buckets the figure of §2.2
is over. Drawn as **one line through the points with the field under it**:
`MUTED_DEEP` field under an `INK` line above the zero, `RETURN` field under a
`RETURN_INK` line below it, one silhouette that crosses the zero wherever a
kilometre gave back more than it took, and nothing stroked along the zero itself.

**There are no holes.** Road the log did not record does not exist on this axis:
the line is continuous from its first point to its last, a gap in the record is a
seam nothing marks, and the chart's width says the same thing as the unit -
«за 8,6 км» is 86 % of the box. The owner's rule, 2026-09-18: «есть данные -
график доливается, нет данных - не доливается». The third board stood its points
on the odometer's grid and drew the road nobody recorded as `NaN` points, and the
first thing it drew on the car was the 4.7 km the hub had slept through that
afternoon - a chart that was mostly the absence of a chart. What the odometer's
grid bought - a shape that does not re-phase - a point per bucket has for free:
a bucket's point is settled when its ten buckets exist, and a new bucket appends
one point and moves the rest one pitch left. The grid, the pro-rata filing, the
`NaN` points and the half-known-kilometre rule go with the holes.

**Filling.** While fewer than ten readings stand behind a point the mean is over
what there is, from five on: a fresh log draws its first point after half a
kilometre of readings and the run grows a point per hundred metres, right-anchored
where new road arrives. Nothing is ever drawn as an invented zero, and nothing is
drawn from fewer than five readings.

Why a line and why a kilometre. The owner's own 34 km of road, the journal read
off the car on 2026-09-18, say what the second board's twenty steps of 500 m could
not: 

| window | p50 | p95 | p98 | max | share past 40 | min | jump between neighbours, p90 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 100 m | 21 | 87 | 133 | 266 | 23 % | −119 | 71 |
| 500 m | 20 | 68 | 86 | 106 | 17 % | −43 | 20 |
| 1 km | 22 | 51 | 61 | 76 | 15 % | −18 | 9 |
| 2 km | 23 | 39 | 43 | 49 | 4 % | 1 | - |

Twenty steps of 500 m were «огромные ступеньки» (2026-09-18): neighbouring steps
differ by 20 kWh/100 km at the median and every launch stood cut at 40 with a
tick over it, so no drive ever drew an uncut shape. A hundred steps of 100 m were
the comb, and a hundred *points* of 500 m are a comb again - a jump of 20 between
points 2.3 units apart. At a kilometre the jump is 9, which is a curve; at two the
return is gone from the road entirely and the shape lags it by a kilometre. A
trailing kilometre rather than a centred one because a centred mean ends half a
kilometre behind the car.

**Scale: linear, 0…60 up and 0…20 down, clamped, with a mark.** Closed by the same
34 km: over a kilometre 2 % of the road passes 60 - a launch - and nothing passes
−20, where over 500 m 17 % passed 40, which is why every drive wore ticks. On the
cluster's 37 units up and 13 down, 60 and 20 are 1.6 and 1.5 kWh/100 km per unit:
the same slope either side of the zero, so the line crosses it without a kink. A
run of points past a ceiling is drawn along the ceiling with one three-unit tick
standing just outside the box at the run's centre - the reader sees it was cut.
The two ceilings are one pair in `spec.json` for both screens - `upTo` and
`downTo` under `cluster.trace` and `head.chart`, which `StripBoardContractTest`
holds equal (they were `ContourPlan.PETAL_FULL` and `PETAL_RETURN_FULL`, printed
by the plan board, before the Luminofor panel).

**Form: a line.** The fourth board's steps («он как бы дискретный ступеньками»)
were chosen when a step was a closed bucket and a step said so; a trailing mean is
a continuous function of the road, and a line is what says that. The engine's box
keeps its steps: its slots are closed five-second buckets and it is not this
chart.

**Where.** On the cluster left of the axis beside its figure, zero on the
figure's baseline, 37 units up and 13 down (`LuminoforSpec.Cluster.Trace`,
`ContourGeometry.TRACE_*`); on the car page the strip's lower band, zero three
quarters down (`LuminoforSpec.Head.Chart`). Same points, same scale law, same
form; the pixel height differs, and the inks are each screen's own - ink and the
cluster's blue there, the head unit's white and blue here.

**Drawn (2026-09-23).** `Silhouette` draws it on both screens, and
`silhouette()` in `tools/design-canvas/luminofor/luminofor.js` on both boards,
line for line. A full window runs edge to edge, a pitch of a ninety-ninth of the
box; a filling one grows leftward from the right edge at the same pitch, so
«за 3,7 км» is thirty-six pitches and 36 % of the box - the contract's «37 %» to
a pitch, since thirty-seven points have thirty-six gaps. The field and the line
are drawn twice, clipped above the zero and below it, so the colour changes
exactly where the line crosses and nothing is stroked along the zero. On the
cluster the line keeps the Luminofor persistence: ten equal runs of the window,
each a step dimmer than the next, drawn as one stroke under a stepped gradient.
The car page's axis gutter - «60» and «−20» - is gone: the approved board has no
gutter, and a scale the driver never reads is furniture on a page that was
already called a mess. The ceilings are the spec's constants and the plan is in
this section.

### 2.4 The trip

Unchanged in definition (`TripEnergyLedger`): net `∫P dt` over the trip, road
from the odometer under one `OdometerGate`, recuperation only with the engine
off, the engine's share `∫G dt` while it runs, its minutes. A trip quantity a
fresh packet does not carry did not happen this trip, and its caption goes with
it (`ContourValue.ledger`). Seat order on P: «ЗА ПОЕЗДКУ» at the edge, then
«ДАЛ ДВС», then «● РЕКУПЕРАЦИЯ» at the far end, packed from the edge.

On the Luminofor cluster the trip is the right group's figure - «42 км · ЗА
ПОЕЗДКУ» over «9,3 кВт·ч» - and «ДАЛ ДВС» and «РЕКУПЕРАЦИЯ» stand on the detail
line under it, «ДАЛ ДВС» whenever the ledger carries the engine's share and
«РЕКУПЕРАЦИЯ» on P. The car page carries the same trip in its own cell, flush
right on the full screen: «42 км · за поездку» over «9,3 кВт·ч».

### 2.5 The engine

Running = `ENGINE_RUNNING ≥ 1`, proven 0/3 through a full start/stop cycle on
2026-08-23 and 0/1/3/0 through the recorded one of 2026-09-18, where `1` was the
cranking second and the flag fell three seconds after the kilowatts. The
revolutions (`ENGINE_RPM`, the stepped set-point id; its `_20D` twin is the
measured figure, within 40 rpm) are shown only while it runs. `G` =
`GENERATION_KW`, proven parked - twice, the second time on the recorder - to be
kilowatts and to equal the pack's charge exactly while parked. **What `G` is in motion is open**: the two
drives so far say the engine ran with this id flat, which is consistent with `G`
being the pack's charge from the engine (zero while the engine drives the wheels
or feeds the motor) and inconsistent with it being the generator's output. Until
the recording says which:

- the engine's box on the cluster exists **only while the flag is up** and only
  if `G` was above zero somewhere in its window. A running engine that gives the
  pack nothing keeps the trip's cells on the shelf and the revolutions in the
  corner; that is the truth, and a flat box was its caricature. The box leaves
  ten seconds after the flag drops - hysteresis against a dropped read, not a
  two-minute afterlife of zeros drawn in blue;
- its sentence is **«ДВС ДАЁТ 8 кВт · ПОСЛЕДНИЕ 1:22»**, set on two lines on the
  Luminofor cluster - «ДВС ДАЁТ 8 кВт» on the caption line over the box and
  «ПОСЛЕДНИЕ 1:22» under it: what the engine is giving, not where it goes. «В БАТАРЕЮ» was a claim about `G`; «даёт» is true
  under either meaning and is the same verb the trip's «ДАЛ ДВС» uses. No dot:
  the blue mark means «into the pack» everywhere else on the panel;
- its field is the history colour - `MUTED_DEEP` under `INK`, like the petal -
  not `RETURN`, for the same reason. The Luminofor concept drew the box blue and
  was brought back to this before it shipped (2026-09-23): an ink line, a faint
  ink field, the sentence grey like every caption;
- **nothing about the engine is drawn on the band.** The line under the band on
  the return span, and the seam behind the tip, both said `G` is or is not
  inside `P`; neither is known. `VehicleConvention.GENERATION_INSIDE_PACK_POWER`
  stays as the recorded assumption and draws nothing;
- the car page's headline «● В БАТАРЕЮ ОТ ДВС» stays: it is printed only when
  the pack is charging *and* the engine is generating, which is true under
  either meaning.

If the recording shows `G` is the generator's output, the box becomes worth
keeping on the move and its sentence and colour may say «В БАТАРЕЮ» again, with
the band's seam drawn from `P + G`. If it shows `G` is the pack's charge, the box
is a parked-charging instrument and the move needs a different engine figure or
none. Either way it is one drive and one decision, not a series of patches.

### 2.6 Unknown energy

An interval with no power reading, or longer than `OdometerGate.MAX_GAP_SECONDS`,
contributes **unknown** energy over its road. The log carries, per bucket, the
road and the road with known energy; the bucket's value is energy over known
road, and a bucket with less than half its road known is **not a reading**. A
bucket that is not a reading is out of everything - out of the figure's sum, out
of the road the unit names, off the chart's axis - which is what makes «за 3,7 км»
a promise about the number beside it and about the chart beside that. An odometer
step longer than one bucket closes one bucket of that road, and it is not a
reading. Restart continuity keeps working through the journal, which carries the
road per bucket.

**The window is bounded by recorded road alone.** Ten kilometres of readings from
yesterday are ten kilometres of readings: they stay on the chart and in the
figure until today's road pushes them out, which is what a history is. The
journal retains thirty kilometres for restart continuity and a restore anchors
`OdometerGate`; a journal the gate refuses is dropped whole, as before, and that
is the only way road leaves other than being pushed out. The odometer floor the
second review added to the window («yesterday's road in the window») goes: it
was a rule about the odometer's ten kilometres, and the axis is not the odometer
any more.

### 2.7 The road is recorded whether or not anyone looks

The hub polled the car only while a screen watched it - `VehicleWatcher.CLUSTER`
while the panel was on the driver's display, `VehicleWatcher.STRIP` while the
strip showed the car's page. The owner's photograph of 2026-09-18 had 1.4 km of
its ten missing, and 4.7 km more went missing the moment it was taken: the car's
page was swiped away and the cluster had not been brought up since the build was
replaced a week earlier. A ten-kilometre history that exists only while it is
being looked at is not a history.

The hub holds a claim of its own, `VehicleWatcher.LEDGER`, from the application's
start for the life of the process. Alone, it sweeps once a second - the integral
needs no more, and a shell round trip is the cost - and the moment a screen claims
the hub the cadence is the screens' own 100 ms, as now. Unavailable answers back
off as they do now, so a car asleep costs the backoff and nothing else. The trip's
ledger (§2.4) is fed by the same sweep and stops losing road for the same reason.

## 3. Words

| where | says | never |
| --- | --- | --- |
| direction of `P` | «ИЗ БАТАРЕИ» · «В БАТАРЕЮ» · «● В БАТАРЕЮ ОТ ДВС» · «● В БАТАРЕЮ ОТ ЗАРЯДКИ» · «БАТАРЕЯ» | a sign |
| the figure's window | «кВт·ч/100 км · за 10 км» / «за 3,7 км»; car page «Расход 16,9 кВт·ч/100 км · за 10 км» | «за 3 км», a whole-number rounding of a filling window, «ПОСЛЕДНИЕ 2 МИНУТЫ» |
| the trip | «42 км · ЗА ПОЕЗДКУ» · «ДАЛ ДВС» · «РЕКУПЕРАЦИЯ»; car page «42 км · за поездку» | a zero, a caption over nothing |
| the engine, live | «ДВС · об/мин» + rpm; box «ДВС ДАЁТ 8 кВт» over «ПОСЛЕДНИЕ 1:22»; car page «ДВС» + rpm | «В БАТАРЕЮ» on the box, a dot on the box, «ГЕНЕРАЦИЯ», «ОБОРОТЫ» |
| the engine, stopped | «ДВС · мин за поездку» + minutes; car page «ДВС за поездку» + «мин» | a box of zeros |
| charging, the countdown | «2:15 до полной»; ten hours or more «12:30» | a day count |
| the car page, closed | «Питание от машины» over the instruction | a row of empty captions |

The car page prints every one of these in sentence case (§2.1), the cluster in
its capitals; the Luminofor cluster sets its captions in Jura and the car page
its words in Roboto, as the stock screens do.

The blue mark «●» means *into the pack* and nothing else. `RETURN` colour means
energy coming back. `WARNING`/`DANGER` are temperature exceptions only.

## 4. States, on both screens

- **first seconds**: skeleton, no captions, no figures;
- **link lost / a dropped read**: figures leave after their horizon, captions
  stay; a trip caption leaves when a fresh packet says the quantity is zero;
- **moving**: hero + band (cluster) / headline + figure (car page); the ten
  kilometre chart and its whole-kilowatt-hour figure; the trip's phrase; the
  engine's revolutions while it runs;
- **standing on P**: the same, plus the tenth on the consumption figure and the
  third seat; the chart does not leave - ten kilometres of road are still ten
  kilometres of road when the car stops;
- **engine giving**: the cluster's box replaces the trip's cells while the flag
  is up and `G > 0`; the car page's headline gains «ОТ ДВС» while the pack takes
  charge;
- **charging**: the cluster's petal figure becomes the countdown; the car page's
  headline says «ОТ ЗАРЯДКИ» and the chart stays;
- **window filling**: the chart grows from its right edge, the unit names the
  known road, and the two are one number - thirty-seven points and «за 3,7 км»;
- **closed to us** (the shell cannot read the car): the cluster draws its
  skeleton and the reason in the ten kilometres' place; the car page says
  «Питание от машины» on its caption line and the instruction under it, and
  nothing else;
- **a gap in the record**: nothing to see. The chart is shorter by the road
  nobody recorded, the points either side of it are neighbours, and the figure is
  the mean of what is known.

## 5. Layout invariants

- The car page's narrow (416 dp) layout keeps the consumption sentence and its
  chart. The strip's box is the spec's (`LuminoforSpec.Head.*.STRIP_BOX`); a box
  shorter than the board's moves the analyser's floor and the page dots up and
  nothing else, and `StripGeometry.minimumHeight` is how far that can go before
  the dots meet the chart - `StripGeometryTest` holds every composition's box
  above it. Content is never drawn past the pane.
- The cluster's geometry is the Luminofor triptych (`LuminoforSpec.Cluster`,
  `ContourGeometry`), approved 2026-09-23: the battery and its five temperatures
  left, the hero on the axis, the engine and the trip - or the engine's box -
  right, one beam along the axis at 296 units, and under it the ten kilometres
  left of the axis beside their figure, zero on the figure's baseline at 364.
  `ContourPlan` was the petal's plan and went with the petal.
- Glass 320 mm wide, eyes **800 mm** (owner, 2026-09-07; 750 was the tape on
  2026-09-04). A cap of `size` units subtends `size × 0.648′`: 52 is 33.7′, 34
  is 22.0′, 18 is 11.7′. The ladder 88·52·34·18 stands; 18 is furniture, read
  by a deliberate look, and no figure the driver needs on the move is set in it.

## 6. What the boards show

**Since 2026-09-23 the drawings of this page are the Luminofor boards**,
`tools/design-canvas/luminofor/`: `cluster-*` for the driver's display and
`main-car*`, `two-car`, `one-car` for the car page at three widths, drawn by
`luminofor.js` from `spec.json` and the frozen scenes in `fixtures.js`. The
debug build draws the same scenes (`LuminoforFixtureActivity`) and
`compare.py` lays its screenshot over the board. The `cluster-launch` and
`cluster-regen` scenes each carry a run past a ceiling, so the tick is on a
board; `cluster-filling` is thirty-seven points under «за 3,7 км».

What follows describes the boards they replaced and stays as their record.
`tools/design-canvas/ClusterContour*.dc.html` and `StripPages.dc.html` were the
drawings of this page: the whole cluster with the stock zones as a labelled
schematic, and the car page inside the strip at both widths. Both draw the
owner's own road of 2026-09-18, scaled to whatever average a scene names, and
both have a **filling** scene where they used to have a hole: thirty-seven
recorded buckets under «за 3,7 км» / «ЗА 3,7 КМ», the run anchored at the right
edge. The cluster keeps the dropped-link state and draws it the way §2.3 says -
ninety points and «за 9,0 км», with nothing marking where the kilometre went.
The plan board prints the smoothing, its floor of five readings and the two
ceilings. The stock zones on
the cluster board are a model; the two numbers the model is checked against are
in the generator (`RANGE_BADGE_SEEN`, `POWER_FIGURE_SEEN`) and the grid
photograph that replaces the model is still owed. A board can establish geometry
and consistency; it cannot establish glance readability at 800 mm, and nothing
here claims it does - the ladder's arithmetic is the argument, and the car is the
test.

## 7. Proof

- **One source.** `EnergyReadouts` turns a `VehicleTelemetry` into every energy
  string and chart both screens draw: direction word, power figure and colour,
  consumption figure and colour, window caption, the hundred points, the engine's
  sentence. Both renderers call it and format nothing themselves.
- **Cross-screen equality.** A test drives both surfaces' readouts from one list
  of snapshots - electric drive, return, engine giving, engine running and
  giving nothing, engine just stopped, standing, charging, filling window, a seam
  in the record, a kilometre past 60, link lost - and asserts equal strings, equal
  points and an equal span.
- **The caption is the chart.** The same test asserts, at every filling width from
  the fifth reading to the full window and across a seam, that the road
  `ConsumptionWindow.coveredKm` names is the run's own width - `span × 0.1 km`.
  «за 8,6 км» is 86 % of the box because the two are one number, not because they
  were compared once.
- **Independent arithmetic.** The expected consumption figure and point values in
  those tests are computed in the test from the raw buckets - the readings picked
  out by the same half-known rule, the trailing ten of them summed - not through
  the production helpers.
- **Replay.** `captures/vehicle-log/*.csv` (the recorder's output, `speed_kmh`
  included, so the standing rule is replayed too) is fed through the hub's own
  log, ledger and traces in a JVM test; the test asserts the invariants that do
  not depend on what a signal means: the road under the chart is a point per
  reading bucket and equals the road the unit names, the figure equals energy over
  known road, every point equals the trailing ten readings computed a second time,
  no point is ever a `NaN`, and the engine's box is never up with the flag down.
- **The boards.** `LuminoforSpecContractTest` holds every number in
  `spec.json` to `LuminoforSpec`; `StripBoardContractTest` holds the board's
  inline numbers and scenes to the strip's renderers, including one ceiling and
  one point count and tick for both charts; `ContourGeometryTest` holds the
  trace's ladder, its slope either side of the zero and a filling window's width;
  `ContourFixturesContractTest` holds the cluster's scenes to what
  `ContourFrameBuilder` would print. And the pictures themselves are compared on
  the emulator with `compare.py` - every board within about one per cent of its
  pixels, the rest antialiasing. (`ContourBoardContractTest` and
  `StripPagesBoardContractTest` held the boards before these, to `ContourPlan`,
  the smoothing, its floor of five, the ceilings and the sentence.)
- **Mutations** on the arithmetic (§2.2, §2.3, §2.6) before the merge, as on
  every wave before: the smoothing, its floor, the skipping of non-readings, the
  window's ten kilometres, the standing threshold and the null-speed rule.

## 8. Open, and what closes each

| open | closes with |
| --- | --- |
| what `GENERATION_KW` is in motion | one recorded drive with the engine running at speed, `tools/vehicle_log.py` or the car's own `VehicleCapture` |
| whether a DC charge (`CHARGE_GUN` = 3) reads «● В БАТАРЕЮ ОТ ЗАРЯДКИ» | the owner's word, and a second DC stop recorded |
| the stock zones' true edges | the grid photograph |

**Closed by the first recorded drive, 2026-09-22** -
`captures/vehicle-log/vehicle-20260922-172447-car.csv`, written on the car by a
shell loop while the head unit was out of the home Wi-Fi: two drives, 3.2 km and
3.5 km, up to 71 km/h, the engine never started, and a six-minute DC stop.

| question | answer |
| --- | --- |
| the sign of `POWER_KW` in motion | **positive out of the pack, proven**: 27 samples accelerating above 0.7 m/s² with the pedal past 20 % read 10…61 kW, median 29, none below zero; 41 decelerating with the pedal up read median −12, 73 % below zero. `VehicleConvention`'s discharge-positive sign holds at speed, not only on a charger |
| whether `ENGINE_RPM` reports anything with the engine off in motion | **no**: over 748 moving samples the primary id answered `0x1FFF` - the invalid pattern the decode already refuses - and its `_20D` twin, `GENERATION_KW` and `GENERATION_STATE` all read `0`. Neither is the id that stood the box up on 2026-09-05 in this build's decoding |
| does `0x2ED00010` mean anything of its own | no, a third time: it equals `POWER_KW` on 65 % of rows and differs only where the two reads were a second apart |
| what the road cost | 23.5 and 30.2 kWh/100 km moving; the standing energy of the second drive, −3.76 kWh, is the DC stop, and the standing rule of §2.2 kept it off the road |

The DC stop is the new question. `CHARGE_GUN` read `3` for six minutes with the
car in P, `POWER_KW` −46…−57 kW, 3.9 kWh into the pack, and `CHARGE_KW` - the AC
charger's own figure - read 24.6…25 over the same minutes. `VehicleTelemetry.
charging` is the AC gun (`2`) alone, so the car page said «В БАТАРЕЮ» over the
true −50 kW of `POWER_KW`, which is honest. What a DC stop must not do is
substitute `CHARGE_KW`: it would print 25 over a 50 kW charge. The word
«ОТ ЗАРЯДКИ» for gun `3` is a one-line change once the owner says the stop was a
DC charger and a second stop agrees.

The first three want a drive and a drive wants no laptop, so since 2026-09-22 the
car can record the sweep itself: a marker file turns `VehicleCapture` on, it
writes the same columns under the same names, and `VehicleLogReplayTest` reads a
pulled file without knowing which recorder made it
(`docs/instrument-display-findings.md`, «The car's own recorder»).

The parked half of §2.5 is closed twice, the second time on the recorder
(2026-09-18, `captures/vehicle-log/vehicle-20260918-183009.csv`, the first file
`VehicleLogReplayTest` has run against); none of the rows above moved, because
the car stood in P.

The chart's ceilings and its form were closed by the car's own journal on
2026-09-18 (§2.3).

Until the first three are closed the engine is drawn as §2.5 says and no other
way.
