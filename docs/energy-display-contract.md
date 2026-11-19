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
| in, engine running (§2.5; until 2026-11-19 «engine generating», `G > 0`) | «● В БАТАРЕЮ ОТ ДВС» | `RETURN_INK` |
| in, charger agreed (`VehicleTelemetry.charging`) | «● В БАТАРЕЮ ОТ ЗАРЯДКИ» | `RETURN_INK` |

**The engine's row outranks the first one** (since 2026-11-19): with the engine
running, any charge the whole figure prints - from 0.5 kW into the pack
(`EnergyReadouts.ENGINE_CHARGE_KW`) - is «● В БАТАРЕЮ ОТ ДВС» in `RETURN_INK`,
inside the neutral zone too, because the engine's block counts every kilowatt of
it (§2.5). A charge that prints «0» names nothing and stays «БАТАРЕЯ».

> **Superseded 2026-11-19:** the first row held for a running engine as well, so
> 4 kW into the pack from the engine, entered from neutral, read «Батарея» on the
> car page while the cluster's block credited the same kilowatts to the run
> (`EnergyReadoutsTest.aSmallChargeFromARunningEngineStillNamesTheEngine`).

The figure is `|P|` rounded to a whole kilowatt. **The minus is never printed for
power**, on either screen. The cluster already says direction with the band's
side and the hero's colour; the car page says it with the word and the colour.
The car page printed «В БАТАРЕЮ» over «−25 кВт» because the word and the sign
were decided in two places; there is one place now.

The cluster's hero is rewritten at most four times a second and holds its glyph
until the followed reading is a whole kilowatt away, so a reading swinging
across a rounding boundary does not flicker. **A reading that has stood within
0.05 kW for a second prints the ordinary rounding**, the car page's
(`ContourMotion.STEADY_KW`, `SETTLED_S`; since 2026-11-19): a wall charge that
settled from 2.4 to 2.6 kW printed 2 on the cluster and 3 on the car page for
the rest of the charge
(`EnergyReadoutsTest.aSettledChargeOnTheOtherSideOfARoundingBoundaryIsOneFigureOnBothScreens`).

**The band has one scale both ways** (since 2026-11-19): equal power is equal
length whether it flows out of the pack or into it, on the square root, full at
300 kW (`ContourGeometry.reach`, `spec.json` → `cluster.band.fullKw`); past 300 kW
the beam stops at its end with a clip tick beyond the head. Until then the return
had its own 100 kW span and 14 kW back drew longer than 26 kW out. The zero's
glow follows the same span on both sides.

**While the charger has agreed (`VehicleTelemetry.charging`), `P` is
`−|CHARGE_KW|` on both screens.** The pack's own id reads zero, or less than the
charger delivers, on a car standing on a charger - the board electronics take
their share first - so the two would otherwise be one event drawn twice: the cluster substituted and the car page printed the
raw id, and the same charge was 7 kW on one screen and 0 on the other. The
substitution is `EnergyReadouts.packKilowatts`, and it is the only place either
screen decides what the pack is doing. **It is made only over a pack reading of
the same sweep** (since 2026-11-19): the charger's fields are cold and outlive
the hot ones, and an expired or dropped snapshot rebuilt «7 кВт» under «В
батарею от зарядки» on the car page after the cluster's power had gone. With no
pack reading there is no figure and the neutral caption, as for any other
direction
(`EnergyReadoutsTest.aChargeLeftStandingLeavesBothScreensOnOneHorizonToo`).

*Corrected 2026-10-09:* this paragraph said the id reads «zero or a small load»
on a charger. No recording shows a load: over both charges recorded on
2026-09-22 (`vehicle-20260922-171228.csv` and `vehicle-20260922-172447-car.csv`,
about 2,800 rows with the AC charger delivering) it read −3 to 0 kW, never above
zero. `VehicleTelemetry.charging` requires exactly that (`loadKw ≤ 0`), and a
load would have kept the gate shut.

**Drawn in the Luminofor palette (2026-09-23).** The neutral row's `MUTED` is
retired on both screens: a pack with no direction is named by its word and drawn
white like any calm figure (`main-car-neutral`), because a dimmed figure read as a
fault and the owner's rule is that calm is flat white. The words and their colours
above are the car page's, which prints them in its sentence case - «Из батареи»,
«● В батарею от ДВС». `EnergyReadouts.Direction` is the one decision, and each
direction has its sentence in `strings.xml`. The cluster prints no direction word,
so nothing draws the capitals and the app does not hold them: the table's capitals
live in the test helper `EnergyReadoutsCase.kt`, where `EnergyReadoutsTest` holds
each pair together. The hero is ink out of the pack and while neutral (the stock flat white; a dimmed
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
the log, and «· за 3,7 км» while it is still filling - the road of the readings,
a hundred metres each (§2.6), not every bucket's.

> **Superseded 2026-10-09:** the unit used to name the readings' *known* road.
> It names their road: a reading stands for its whole hundred metres, so the
> unit is the chart's width whatever share of each reading was watched — see
> [§2.6](#26-unknown-energy).

The car page prints the same unit in one sentence over its chart,
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
a continuous function of the road, and a line is what says that. Until 2026-11-19
the engine's box kept its steps, its slots being closed five-second buckets; the
engine's block that replaced it is a trailing mean too and is drawn by the same
`Silhouette` (§2.5).

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
Since 2026-11-19 the line is a monotone cubic through the points rather than a
polyline between them (`MonotoneCurve`, `monotoneCurve()` on the board,
Fritsch-Carlson tangents): it passes through every point, never overshoots a
neighbour, and between two points on one side of the zero it does not cross it,
so the colour split above is unchanged. The points are the same points; at 2x a
polyline of a hundred points showed a corner at each of them.
The car page's axis gutter - «60» and «−20» - is gone: the approved board has no
gutter, and a scale the driver never reads is furniture on a page that was
already called a mess. The ceilings are the spec's constants and the plan is in
this section.

### 2.4 The trip

`TripEnergyLedger`: net `∫P dt` over the trip, road from the odometer under one
`OdometerGate`, recuperation `∫max(0, −P) dt` only with the engine off, the
engine's charge `∫max(0, −P) dt` while it runs, its minutes. A trip quantity a
fresh packet does not carry did not happen this trip, and its caption goes with
it (`ContourValue.ledger`). Seat order on P: the road «42 км» at the edge, then
«ЗАРЯД ОТ ДВС», then «● РЕКУПЕРАЦИЯ» at the far end, packed from the edge.

**The engine's charge is what the pack took while the engine ran** (since
2026-11-19), the same integral as the engine block's figure (§2.5). It counts
regeneration during an engine run too: braking with the engine turning puts
energy into the pack, and the pack cannot tell the two apart. Recuperation is
counted with the engine off only, so nothing is counted twice.

> **Superseded 2026-11-19:** the engine's share was `∫G dt` while it ran, under
> «ДАЛ ДВС». `G` reads zero on the move while the engine charges the pack, so the
> seat stayed at zero on every drive; it is the pack's own charge now, under
> «ЗАРЯД ОТ ДВС» — see [§2.5](#25-the-engine).

**The record survives a restart and an upgrade** (`TripJournal`, `trip.log`,
2026-11-19). It starts with its version and says whether its engine charge is
known and whether a trip was opened at all. 0.7.1's record had seven fields and
its third was `∫G dt`: restored across the upgrade, its net, recuperation,
minutes and road are kept, and its engine charge is **unknown** - zero, not
printed, not added to (`TripEnergy.engineKwhKnown`) - until the next trip
starts, rather than a sum of two quantities under one caption. A checkpoint that
cannot be written leaves the last good record on disk, and an unchanged record
(the car standing in P) is not written again.

**P ends the trip whatever the odometer said.** A P read in a sweep whose
odometer did not answer used to be dropped with the sweep, so the energy trip ran
on into the next drive while the strip's trip clock, reading the same P, had ended
its own. P is latched on its own now; the odometer still decides when the next
movement starts the new trip.

**A ledger that has opened no trip has none** (`TripEnergy.opened`): true from the
first movement after the process started, or for a restored trip, and still true
in P after it. A car that answers its pack and not its odometer has no trip,
which is not a trip of nothing: both screens read this flag before they print a
trip figure - the cluster's «ЗА ПОЕЗДКУ» is a ledger quantity present only with
it (`ContourValue.TRIP_NET`), the car page's cell is empty without it
(`EnergyReadouts.readTrip`) - and a restored trip, this build's record or
0.7.1's, carries it (`EnergyReadoutsTest.aLedgerThatOpenedNoTripPrintsNoneOnEitherScreen`).

> **Superseded 2026-11-19:** this paragraph said the screens «are to read» the
> flag; they did not, and every READY packet put «0,0 кВт·ч» on both.

On the Luminofor cluster the trip is the right group's figure - «ЗА ПОЕЗДКУ»
over «9,3 кВт·ч» - and the detail line under it reads right to left from the
edge, every reading caption, figure, unit: the road «42 км» (no caption: it is the
trip's own), «ЗАРЯД ОТ ДВС 0,8 кВт·ч» whenever the ledger carries the engine's
charge, and «● РЕКУПЕРАЦИЯ 3,1 кВт·ч» on P. A stale road leaves with the trip's
figure. While the engine's block stands (§2.5) the trip gives it the right group
and comes back when the block leaves. The car page carries the same trip in its
own cell, flush right on the full screen: «42 км · за поездку» over «9,3 кВт·ч»;
`EnergyReadoutsTest` holds its caption to the cluster's road and name.

> **Superseded 2026-11-19:** the cluster's caption was the phrase «42 км · ЗА
> ПОЕЗДКУ» and the detail line read figure, unit, caption («0,8 кВт·ч ЗАРЯД ОТ
> ДВС»). A caption is a name only now, in one case and one tracking (0.08), with
> the unit beside the figure; the road moved under the figure as its own reading.

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

> **Superseded 2026-11-19:** neither reading holds on the move. On the 2026-09-24 drive the engine ran decoupled from the wheels and charged the pack (about 0.82 kWh in while it ran) with `G` and its state at `0` on every row; `G` is above zero only during forced generation standing in P — see [the findings](vehicle-data-findings.md#the-engine-charges-on-the-move-with-generation-at-zero-2026-11-19). So the engine's figures stopped reading `G` the same day: [the engine's block](#the-engines-block-since-2026-11-19) below.

#### The engine's block (since 2026-11-19)

**What it shows: the charge the engine gives the battery.** The quantity is the
pack's charge, `max(0, −P)`, while the flag is up - moving or standing - and not
`G`. Regeneration during an engine run is inside it, as in the trip's figure
(§2.4); the screens say nothing about that.

- **A run** starts when the flag goes up and ends when it reads down; a flag
  that did not answer neither ends nor counts toward one - not in its figure,
  its time or its chart (until 2026-11-19 such a sweep still went into the
  chart's second). A run that starts again within ten seconds of its stop is the
  same run. It carries the energy it put into the pack and the seconds the engine
  ran, integrated sweep by sweep on the ledger's own intervals (`EngineCharge`),
  with the trip's formula: the block's figure and the trip's «ЗАРЯД ОТ ДВС» are
  the same integral **wherever their intervals overlap**, which is an open trip
  with the engine running. They are not one figure: the block counts every
  interval of its run, in P too - forced generation standing after a finished
  trip goes into the block - while the trip integrates nothing in P, so the
  finished trip stays the finished trip. A run longer than the trip, or one that
  started before it, differs from it the same way.

  > **Superseded 2026-11-19:** this item said the two «are one integral». They
  > share the formula and the intervals of an open trip; in P after a finished
  > trip the block counts forced generation and the trip does not
  > (`TripEnergyLedgerTest.anEngineRunningInParkIsNotThisTripsEngineEither`).
- **The chart**: a sample a second (the mean of that second's sweeps; a second no
  sweep landed in keeps the newest reading for up to `MAX_GAP_SECONDS`), each
  drawn point the trailing mean of the last ten samples - the ten kilometres'
  rule and constant (`ConsumptionChart.SMOOTH_STEPS`) - over the run's last two
  minutes, a point a second. A younger run fills from the right edge at the full
  window's pitch. Drawn by `Silhouette` in the return blue, one monotone curve
  and its field, zero on the figures' baseline and upward only.
- **The scale** is 0…50 kW, or 0…25 kW while the window's highest point is under
  20 (`EngineCharge.ceilingKw`, the board computes the same); a point past it lies
  along the ceiling with the chart's tick. The engine's ordinary standing charge
  is 8 kW, a third of the lower box.
- **The figure** is the run's kilowatt-hours, a tenth («0,4»; whole from ten,
  where the column runs out), at the chart's right end on the figures' baseline,
  with the run's time under it: «ЗА 2:52». The caption «ЗАРЯД ОТ ДВС» stands over
  the chart from its left edge. **Superseded 2026-11-19:** the run's time is
  `AppDuration`'s, «ЗА 41 с», «ЗА 2 мин», «ЗА 1 ч 05 мин», and keeps counting; the
  clock it was stopped at «9:59» while the kilowatt-hours over it went on growing.
- **Lifetime**: on the cluster while the engine runs, moving and parked, with or
  without a charge (a hard pull draws a line on zero and «0,0»); it holds ten
  seconds after the flag drops - hysteresis against a dropped read - and then
  leaves, the trip comes back with «ЗАРЯД ОТ ДВС 0,4 кВт·ч» on its detail line.
  A link gone quiet takes it on the packet's age, like every reading. The ten
  seconds are the run's, not the view's (since 2026-11-19): a Contour opened
  inside them takes the stop from the model (`EngineChargeSnapshot.stoppedSeconds`)
  and keeps the block for what is left; until then a reopened panel dropped it.
- **The engine's cell** beside it: «ДВС» over «1650 об/мин» while the engine
  runs, empty through the ten-second hold - the block's figure and time are the
  run's, and the trip's minutes beside them would be a second answer - and «ДВС ЗА
  ПОЕЗДКУ» over «6 мин» after it, for two minutes after a stop
  (`EngineChargeSnapshot.stoppedRecently`). The unit stands beside the figure and
  leaves with it; the car page writes the same name in sentence case («ДВС», «ДВС
  за поездку»). One rule on both screens (`EnergyReadouts.readEngineCell`). Until
  2026-11-19 the cluster's caption carried the unit, «ДВС · об/мин» and «ДВС · мин
  за поездку», over a bare figure. The block narrowed to 296 with a 128 column the
  same day, so «8888 об/мин» stands two trace gaps clear of its line.
- **The car page** draws no chart. Its headline «● В БАТАРЕЮ ОТ ДВС» is printed
  whenever the pack takes charge with the engine running - the block's condition -
  inside the neutral zone too, from the first kilowatt its figure prints (§2.1,
  since 2026-11-19). Were it to gain the chart, the place is the trip cell's slot on the full
  screen's second row, the same way the cluster gives the block the trip's group.
- **Nothing about the engine is drawn on the band**, as before.

#### Before 2026-11-19

> **Superseded 2026-11-19:** everything in this subsection — the box, its sentence, its ink colour and its `G > 0` condition — is replaced by [the engine's block](#the-engines-block-since-2026-11-19) above.

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
contributes **unknown** energy. The log carries, per bucket, the road and the
road with known energy; the bucket's value is energy over known road, and a
bucket with less than half its road known is **not a reading**. A bucket that is
not a reading is out of everything - out of the figure's sum, out of the road the
unit names, off the chart's axis - which is what makes «за 3,7 км» a promise
about the number beside it and about the chart beside that. Restart continuity
keeps working through the journal, which carries the road per bucket.

**A bucket's known road is its road times the share of its moving time that had
power** (2026-10-09): `knownKm = km × known seconds / moving seconds`, over the
intervals that are moving by §2.2's rule, an interval being known when it had a
power reading and was no longer than `MAX_GAP_SECONDS`. Standing time is in
neither side of the share - its energy is the trip's, with or without a power
answer - and a bucket with no moving time knows none of its road. The odometer
steps in whole tenths, which is the bucket, so a bucket's road arrives in the one
poll that closes it; the time the road took is the only measure of how much of it
was watched.

> **Superseded 2026-10-09:** an interval used to contribute unknown energy "over
> its road", so known road was the road of the intervals that had power. On this
> odometer that is all of a bucket or none of it, decided by the closing poll: a
> power read dropped inside a bucket lost its energy from a bucket that stayed a
> full reading, an underestimate, and one dropped on the closing poll threw away
> a bucket whose other seconds were known. The rule above replaces it; the drives
> recorded by then have no moving row without power and replay to the same
> figures.

**A reading stands for its whole road.** The walk to ten kilometres (§2.2), the
unit and the chart count a reading's road - one tick, a hundred metres - and only
the figure divides by its known road. So «за 3,7 км» is thirty-seven readings
whatever share of each was watched, and the chart beside it is thirty-seven
points.

**A bucket the sweeps were interrupted inside is not a reading** (2026-11-19). A
failed read, its backoff and the reconnect - or the backoff after a bus gone
quiet (§2.7) - are time nobody watched, and the first sweep back has no interval
behind it (`VehicleSweepClock`). That zero interval said nothing to the log: a
bucket with three quarters of a second of power in it, closed by the first sweep
back, was a full reading of the partial energy, drawn and journalled. The clock
now says the sweep follows an interruption (`VehicleSweepClock.resumed`), and the
open bucket knows none of its road (`ConsumptionLog.interrupted`). The trip's
ledger refuses the zero interval's road and energy together, as it refuses any
interval it was not given, so its kilowatt-hours and kilometres stay one drive.

**An odometer step longer than one bucket closes one bucket of that road, and it
is not a reading**: none of its road is known, because nobody watched it being
covered. So every reading is one tick, which is what lets a pitch per point be
the road under the chart, and `VehicleLogReplayTest` holds every recorded drive
to it. (Until 2026-10-09 the code did not follow this sentence: a 0.2-0.3 km step
in one slow sweep was a reading of its whole road, one point of the chart and two
or three ticks of the unit, and `ConsumptionLogTest` pinned it. No recorded drive
has such a step.)

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
off as they do now, so a car asleep costs the backoff and nothing else.

**The backoff follows fresh decoded telemetry** (2026-11-19, `VehicleQuietBus`).
A read that threw backed off; a shell that answered every id with a sentinel - a
bus asleep under a head unit that is not - reset the backoff because a reply had
come, and was polled at the full cadence for as long as the bus slept. A sweep is
fresh when it decoded a reading of its own (not a carried cold value, not the
warning slot). Quiet sweeps keep the cadence for the link's horizon
(`VehicleLink.CLOSE_AFTER_MS`), so a blip on a live bus costs nothing; past it
each waits out the backoff, capped at `MAX_GAP_SECONDS` so a car switched on is
not missed for a minute, and the wait is an interruption (§2.6). The trip's
ledger (§2.4) is fed by the same sweep and stops losing road for the same reason.

A screen that claims the hub ends whatever backoff it finds and gets its attempt
at once (`VehicleBackoff.wake`, 2026-10-08). With `LEDGER` the loop never stops,
so a new claim no longer starts a fresh loop, and from 2026-09-18 until then a
screen brought up after a run of failures waited out the rest of a backoff of up
to a minute, on its last snapshot, after the car had come back.

A claim that draws nothing leaves the cadence at the ledger's second and is not
owed a cold sweep when it appears (`VehicleSweepCadence.coldAtOnce`): the ledger's
own, and since 2026-10-09 `VehicleWatcher.TRIP`, held while the head unit's strip
runs. The strip's trip clock ends its trip on the park switch this sweep reads,
instead of polling the same id over a second shell, so both screens and the clock
answer one reading of P. Like any new claim it ends a backoff it finds.

## 3. Words

| where | says | never |
| --- | --- | --- |
| direction of `P` | «ИЗ БАТАРЕИ» · «В БАТАРЕЮ» · «● В БАТАРЕЮ ОТ ДВС» · «● В БАТАРЕЮ ОТ ЗАРЯДКИ» · «БАТАРЕЯ» | a sign |
| the figure's window | «кВт·ч/100 км · за 10 км» / «за 3,7 км»; car page «Расход 16,9 кВт·ч/100 км · за 10 км» | «за 3 км», a whole-number rounding of a filling window, «ПОСЛЕДНИЕ 2 МИНУТЫ» |
| the pack's volts | «НАПРЯЖЕНИЕ» over «412 В»; car page «Напряжение» + «В» | «БАТАРЕЯ · В» (until 2026-11-19), a unit in a caption |
| the trip | «ЗА ПОЕЗДКУ» over «9,3 кВт·ч», under it «42 км» · «ЗАРЯД ОТ ДВС 0,8 кВт·ч» · «● РЕКУПЕРАЦИЯ 3,1 кВт·ч»; car page «42 км · за поездку» | a zero, a caption over nothing, «ДАЛ ДВС» (until 2026-11-19), «42 км · ЗА ПОЕЗДКУ» and «0,8 кВт·ч ЗАРЯД ОТ ДВС» (until 2026-11-19) |
| the engine, live | «ДВС» over «1650 об/мин»; block «ЗАРЯД ОТ ДВС» over its chart, «0,4 кВт·ч» over «ЗА 2 мин» (a clock, «ЗА 2:52», until 2026-11-19); car page «ДВС» + rpm | a dot on the block, «ГЕНЕРАЦИЯ», «ОБОРОТЫ», «ДВС ДАЁТ» (until 2026-11-19), «ДВС · об/мин» (until 2026-11-19), a note on what the charge includes |
| the engine, stopped | an empty corner for the block's ten seconds, then «ДВС ЗА ПОЕЗДКУ» over «6 мин»; car page «ДВС за поездку» + «мин» | a block of zeros after its hold, «ДВС · мин за поездку» (until 2026-11-19) |
| charging, the countdown | «2 ч 15 мин до полной», «45 мин до полной», «12 ч 30 мин до полной»: `AppDuration`'s figure-unit pairs (since 2026-11-19) | a day count, a clock - «2:15» (until 2026-11-19) |
| the car page, closed | «Данные машины» over the state - «Нет доступа», «Нет связи», «Нет ответа» (2026-11-19; until then «Питание от машины» over the instruction) | a row of empty captions, an instruction, a «·» |

**The stock countdown is hours and minutes, not a clock** (2026-09-23). The car's
own charging screen prints the estimate as two figure-unit pairs - «1 h 57 min»
in English, the units small on the figures' baseline
(`captures/split-live-acceptance/v22-baseline-screen.png`); the app that draws it
is not among the firmware files extracted so far, so its Russian units are not
read, and «ч» / «мин» is the expectation, not a reading. The proposal is the
car's form on the cluster too - «1 ч 57 мин до полной», and «12 ч 30 мин» past ten
hours instead of a clock that reads as a time of day. The owner kept «2:15» and
«12:30» for now; it is one formatter (`ContourFigures.chargeLeft`) and one board
scene when it moves.

> **Superseded 2026-11-19:** it moved. The countdown is `AppDuration`'s, the one
> rule every duration in the app is printed by - under an hour «45 мин», otherwise
> «2 ч 15 мин», the minutes two figures behind hours - and the petal draws it as the
> strip draws a duration: each figure in the wide figures, its unit after it in the
> petal unit's size and grey, the second pair `cluster.trace.pairGap` (14) after the
> first, «до полной» after the last unit. `ContourReadout.chargeLeft` returns the
> pairs and `ContourFigures.chargeLeft` remembers them by the minutes; the widest,
> «99 ч 59 мин до полной», is held inside the petal by `ContourGeometryTest` and
> `DrawnWordsFitTest`. A countdown of nought minutes prints «0 с», as `AppDuration`
> prints any duration under a minute.

The car page prints every one of these in sentence case (§2.1), the cluster in
its capitals; the Luminofor cluster sets its captions in Jura and the car page
its words in Roboto, as the stock screens do.

**A caption is a name only** (since 2026-11-19): what the figure is, in capitals,
tracked 0.08 wherever it stands (`spec.json` → `cluster.grid.captionTrack`,
`cellCaptionTrack`, `detailTrack`, one value); the unit stands small beside the
figure and leaves with it; every detail reading is caption, figure, unit.
`ContourReadoutTest.aCaptionIsANameOnly` and `ContourGeometryTest.everyCaptionHasOneTracking`
hold it.

The blue mark «●» means *into the pack* and nothing else. `RETURN` colour means
energy coming back. `WARNING`/`DANGER` are temperature exceptions only.

## 4. States, on both screens

- **first seconds**: skeleton, no captions, no figures;
- **link lost / a dropped read**: figures leave after their horizon, captions
  stay; a trip caption leaves when a fresh packet says the quantity is zero. On
  the cluster the beam and its glow go with the power's figure, the glyphs stay
  without their degrees, and the ten kilometres stay with their unit where the
  last figure left it (`ContourFrame.consumptionHeld`, board `cluster-stale`); the
  first seconds are the axis alone (`cluster-waking`). A shell call that fails -
  a timeout, a reset, an answer of nothing but sentinels - is this state, not the
  closed one (2026-10-08; until then any one of them closed both screens for at
  least the four seconds of the first backoff): the hub publishes a *dropped read*
  (`VehicleDroppedRead`: no hot value, the cold ones carried, the history and the
  trip kept, `VehicleTelemetry.dropped`), which the Contour does not count as a
  packet, so its ages run on as for a hub gone quiet, and on which the car page
  keeps its captions and loses its live figures at once. **A hub that publishes
  nothing** - its reader blocked behind a helper's start or a shell's timeout -
  leaves its last snapshot standing, and both screens age it by one horizon
  (since 2026-11-19): the cluster on its own clock, the car page by the sweep's
  stamp (`VehicleTelemetry.hotReadAtMillis`, `hotExpired`), both
  `VehiclePoll.HOT`'s two seconds; past it the car page draws the snapshot as the
  dropped read it has become (`StripReadings.car`,
  `EnergyReadoutsTest.aSnapshotLeftStandingLeavesBothScreensOnOneHorizon`).
  Until then the car page printed the last «80 кВт» for as long as the reader
  blocked, after the cluster had taken its figure down;
- **moving**: hero + band (cluster) / headline + figure (car page); the ten
  kilometre chart and its whole-kilowatt-hour figure; the trip's phrase; the
  engine's revolutions while it runs;
- **standing on P**: the same, plus the tenth on the consumption figure and the
  third seat; the chart does not leave - ten kilometres of road are still ten
  kilometres of road when the car stops;
- **engine running**: the cluster's block replaces the trip's cells while the
  flag is up and for ten seconds after, moving or parked (§2.5); the car page's
  headline gains «ОТ ДВС» while the pack takes charge with the engine running.
  (Until 2026-11-19 the box wanted `G > 0`, which on this firmware happens only
  during forced generation in P);
- **charging**: the cluster's petal figure becomes the countdown; the car page's
  headline says «ОТ ЗАРЯДКИ» and the chart stays;
- **window filling**: the chart grows from its right edge, the unit names the
  readings' road (§2.6), and the two are one number - thirty-seven points and
  «за 3,7 км»;
- **closed to us** (the shell cannot read the car): the cluster draws its
  skeleton and the reason in the ten kilometres' place; the car page says
  «Данные машины» on its caption line and under it what it has of the car -
  «Нет доступа», «Нет связи» or «Нет ответа» (`VehiclePageRenderer.closedState`,
  from the hub's reason) - and nothing else. Until 2026-11-19 it said «Питание от
  машины» over the cluster's instruction, which on this page the ADB gate already
  stands over; the cluster keeps the reason as written below. `VehicleLink` declares it: at once for a missing ADB key, and
  otherwise only once the reads have failed twice in a row and nothing has
  answered for two hot horizons (4 s); an answer opens it again. The reason is a
  state in the driver's words: «ADB-ключ не подтверждён · откройте Denza Apps»
  for the key, «Нет связи с машиной» for a shell that fails, «Машина не отвечает»
  for one that answers only sentinels (`VehicleTelemetryHub`; until 2026-10-09
  «Нет связи с локальным ADB» and «Машина не ответила ни на один запрос»);
- **a gap in the record**: nothing to see. The chart is shorter by the road
  nobody recorded, the points either side of it are neighbours, and the figure is
  the mean of what is known.

## 5. Layout invariants

- The car page's narrow (416 dp) layout keeps the consumption sentence and its
  chart. The strip's box is the spec's (`LuminoforSpec.Head.*.STRIP_BOX`); a box
  shorter than the board's moves the analyser's floor and the page dots up and
  nothing else, and `StripGeometry.minimumHeight` is how far that can go before
  the dots meet the chart - `StripGeometryTest` holds every composition's box
  above it. It is also the pane strip's floor in `DashboardLayoutPolicy.page`
  (until 2026-10-08 a 300 dp constant, under both panes' least box): a window
  too short for it scrolls the page rather than squeezing the strip, which
  `DashboardLayoutPolicyTest` holds. Content is never drawn past the pane.
  The full screen's car row needs its 1184 dp: only the 1280 window resolves to
  it, and at the policy's threshold between the two wider windows (1055) its
  slots would collide - no window the car sends is there.
  `StripGeometryTest.everyWindowTheCarHandsIsABoxThisFileHolds` lays the row out
  at every measured window that resolves to it (2026-11-19).
- The cluster's geometry is the Luminofor triptych (`LuminoforSpec.Cluster`,
  `ContourGeometry`), approved 2026-09-23: the battery and its five temperatures
  left, the hero on the axis, the engine and the trip - or the engine's block -
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
board; `cluster-filling` is thirty-seven points under «за 3,7 км». The engine's
block is on five boards drawn from two recorded drives, as `EngineCharge`
replays them (`VehicleLogReplayTest` holds the numbers): `cluster-engine`
charging on the move (2026-09-24 18:40:04, «0,2» «ЗА 41 с»), `-kickdown` at
226 kW out fifteen seconds into the same run, `-stopped` four seconds after the
19:25 stop with the corner empty, `-gone` past the hold with «ЗАРЯД ОТ ДВС 0,4
кВт·ч» under the trip, and `-parked`, forced charge in P on 2026-09-18 on the lower
ceiling.

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
  consumption figure and colour, window caption, the hundred points, the engine
  block's figure and time. Both renderers call it and format nothing themselves.
- **Cross-screen equality.** `EnergyReadoutsTest` runs one list of snapshots -
  electric drive, return, engine charging, engine charging lightly (inside the neutral zone), engine running and charging nothing,
  engine just stopped, engine stopped past the hold, engine ran yesterday, standing, charging, a charge the
  pack's own id reads as nothing, filling window, a road that gave back more, a
  seam in the record, a kilometre past 60, link lost, a dropped read - through
  the cluster as it draws (scene, followers, `ContourFrameBuilder`) and through
  the car page's `StripReadings`, and asserts that what the two print agrees: the
  power, the volts, the engine's cell, the trip, the consumption with its window
  and its minus, and the hundred points. Where they differ by design it says so
  and compares nothing: the cluster's countdown takes the consumption's seat
  while charging, its consumption is grey while the engine runs, its engine block
  takes the trip's place, and the car page's sentence that names a source is
  blue whatever its magnitude while the cluster's hero keeps the band's colour.
  A second test holds the list to reaching every one of those readings at least
  once. (Until 2026-10-08 this paragraph described a test that compared two
  `EnergyReadouts` instances on the same input, which agree by construction;
  neither renderer was in it.)
- **The caption is the chart.** The same file asserts, at every filling width
  from the fifth reading to the full window and across a seam, that the road the
  window string prints is the run's own width - a hundred metres a point, worked
  out in the test. «за 8,6 км» is 86 % of the box because the two are one number,
  not because they were compared once. `ConsumptionLogTest` asserts the same on
  the car's own ticks with a power read dropped in every bucket and a two-tick
  step every kilometre, and `ConsumptionWindowTest` over readings known for five
  sixths of their road (2026-10-09).
- **Independent arithmetic.** The expected consumption figure and point values in
  those tests are computed in the test from the raw buckets - the readings picked
  out by the same half-known rule, the trailing ten of them summed - not through
  the production helpers.
- **Replay.** `captures/vehicle-log/*.csv` (the recorder's output, `speed_kmh`
  included, so the standing rule is replayed too) is fed through the hub's own
  sweep step, `VehicleAnsweredSweep` - the lines the poll loop runs, park flag
  and all, not a copy of them as before 2026-10-08 - in a JVM test; the test
  asserts the invariants that do
  not depend on what a signal means: the road under the chart is a point per
  reading bucket and equals the road the unit names, every reading is one
  odometer tick, the figure equals energy over known road, every point equals
  the trailing ten readings computed a second time, no point is ever a `NaN`,
  and the engine's block is never up with the flag down past its hold.
  It replays the three newest drives and the newest one the engine ran in, and a
  drive the engine ran in has to raise the block at least once, or the last
  invariant would hold over nothing (until 2026-11-19: «gave in», by `G`). A
  second test replays the two drives the engine block's boards are drawn from to
  those rows and holds each board's chart, figure, time and ceiling, and the
  trip's detail past the hold, to what the replay prints. With no drive on the machine it is skipped,
  not passed; the drives are inputs of the test task, so a new one reruns it.
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
  window's ten kilometres, the standing threshold and the null-speed rule; since
  2026-10-09 also the moving-time share, standing time kept out of it, the
  multi-tick step and the unit counting a reading's road.

## 8. Open, and what closes each

| open | closes with |
| --- | --- |
| whether a DC charge (`CHARGE_GUN` = 3) reads «● В БАТАРЕЮ ОТ ЗАРЯДКИ» | a recorded stop at a charger known to be DC (the owner does not recall what the 2026-09-22 stop was, asked 2026-10-09) |
| the stock zones' true edges | the grid photograph |
| ~~what the engine box, «ДАЛ ДВС» and «ОТ ДВС» do on the move, where `GENERATION_KW` reads zero while the engine charges the pack~~ | closed 2026-11-19: the block, the trip's «ЗАРЯД ОТ ДВС» and «ОТ ДВС» read the pack's charge while the engine runs (§2.4, §2.5). Not yet seen on the car |
| the engine block through a long run and through P/D transitions | a drive with an engine run past ten minutes, a stop in P with the engine still running, and a start-off after it |
| the upgrade from 0.7.1 over an open trip | install over 0.7.1 within a kilometre of its last checkpoint: the trip carries on with no «ЗАРЯД ОТ ДВС» until the next trip, then the new trip's |
| the quiet-bus backoff | the head unit left on with the car switched off: the sweeps fall to one per eight seconds after four of silence, and the first drive afterwards loses no more than that |
| whether a firmware fails the helper's Java path while `service call` works | the resident helper on the other DiLink generations, with missing service data and with a Binder that refuses it; a full drive on the shell fallback, including an in-drive helper retry |
| whether the signals mean the same on another model | `AutoType` and the FID answers on an N9 (`168`), the other Z9-family ids and a pure EV: an integer in range does not establish a meaning |

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

**Closed by the second recorded drive, 2026-09-24** -
`captures/vehicle-log/vehicle-20260924-182412-car.csv`, 12.2 km, the owner
starting the engine on purpose. It started three times, every time in motion:

| run | length | speed | pack power | rpm | `GENERATION_KW`, state |
| --- | --- | --- | --- | --- | --- |
| 18:39 | 76 s | 18…68 km/h | −38…+226 kW | 1304…2985 | 0, state 0 throughout |
| 19:09 | 22 s | 44…65 km/h | −50…+229 kW | 1451…1885 | 0, state 0 throughout |
| 19:22 | 171 s | 9…62 km/h | −51…+291 kW | 1346…2034 | 0, state 0 throughout |

**`GENERATION_KW` is the engine's charge into the pack, not the generator's
output.** Each start came with a demand past 220 kW and the engine drove the
wheels beside the pack; not one sample of 186 had the id or its state off zero.
So §2.5's box was right as drawn: it stood only while the engine was charging,
and a running engine that drove the wheels showed its revolutions in the corner and
nothing on the shelf.

> **Superseded 2026-11-19:** the same 186 rows show the engine charging the pack, not only driving the wheels: `P` ran −51…+291 kW (median +13), 84 rows were at −3 kW or below, 44 of them with the accelerator pressed and the brake off, about 0.82 kWh went in, and the revolutions wandered 23…150 per km/h on set-points near 1350–1500 rpm, which is the series mode. `GENERATION_KW` and its state read `0` throughout. So the id is not the engine's charge into the pack on the move; it is above zero only during forced generation in P, and the box does not stand while the engine charges on the move — see [the findings](vehicle-data-findings.md#the-engine-charges-on-the-move-with-generation-at-zero-2026-11-19). What it gives the wheels has no reading on this firmware -
torque, fuel flow and boost are the silent `0x324`/`0x387`/`0x30D` families
(`docs/vehicle-data-findings.md`). `ENGINE_RPM` is real in motion (1304…2985),
and its `_20D` twin agrees once the engine is past cranking.

The same drive proved §2.7 live: the product's own journal holds 122 buckets over
the 12.2 km, no gap, every one a reading, with no screen asked to watch.

The DC stop is the new question. `CHARGE_GUN` read `3` for six minutes with the
car in P, `POWER_KW` −46…−57 kW, 3.9 kWh into the pack, and `CHARGE_KW` - the AC
charger's own figure - read 24.6…25 over the same minutes. `VehicleTelemetry.
charging` is the AC gun (`2`) alone, so the car page said «В БАТАРЕЮ» over the
true −50 kW of `POWER_KW`, which is honest. What a DC stop must not do is
substitute `CHARGE_KW`: it would print 25 over a 50 kW charge. The word
«ОТ ЗАРЯДКИ» for gun `3` is a one-line change once a stop at a charger known to be
DC is recorded and agrees. The owner does not recall what this one was (asked
2026-10-09), so it cannot be that stop.

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

### 8.1 Known limits at release (2026-11-19)

The second external review of this zone found narrow races and rare failure
combinations that the release accepts rather than restructures for. Each is
traced to the code; none is fixed. Where one belongs to the hub's reading of
the car, [vehicle-data-findings.md](vehicle-data-findings.md#known-limits-of-the-vehicle-hub-2026-11-19)
carries it too.

| # | scenario | what the driver may see | car check |
| --- | --- | --- | --- |
| 5 | 0.7.1 reinstalled over this build with app data kept | the trip starts again: 0.7.1 cannot read the versioned `trip.log` and deletes it | none; a downgrade is not a supported path |
| 6 | the in-drive helper retry (5 min after a failed start) takes under 8 s to come back | up to one retry's interval, about 0.3 kWh at 200 kW, credited to the trip, the engine's run and one bucket at the last reading | a full drive on the shell fallback with a retry under load: the log's gap and the bucket around it |
| 7 | the odometer misses sweeps inside one 100 m bucket while speed or P answers | that bucket counts as fully measured on part of its time; both screens use it | a drive recording (`VehicleCapture`) with odometer gaps: compare `knownKm` with the time covered |
| 8 | `POWER_KW` answers a sentinel for all or part of a trip while the odometer and P answer | the trip's kilometres are exact and its kWh is short - «0,0 кВт·ч» for a trip with no power at all | none known on this car; any drive where `POWER_KW` drops |
| 10 | the cluster panel is switched on while the hub is blocked on a read with an 80 kW snapshot still published | the cluster shows that power for up to two more seconds after the car page has dropped it | a reader stall (helper start) with the Contour toggled on: the two screens side by side |
| 11 | the engine flag is missing for several sweeps around a stop, then the Contour is reopened | the engine block's hold and the minutes cell can end a second apart in an open and a reopened panel; a restart inside that second may count as a new run | a stop with dropped `ENGINE_RUNNING` reads and the panel reopened inside ten seconds |
| 14 | `VehicleCapture` is on (host marker) and the data volume refuses the CSV header | a partial file per minute while the marker stays; nothing on screen | none; capture is a host-placed diagnostic |
| 16 | the resident helper answers `Parcel(NULL)` or only optional ids while `service call` would answer | the hub keeps the helper and its quiet-bus backoff; the panels show no live figures | the helper on another firmware generation: does `Parcel(NULL)` occur with the primary ids |
| 17 | `setDataCaptureListener` fails while `setEnabled(true)` succeeds | idle bars, and the effect rebuilt about every six seconds | listener registration result on the installed audio firmware |
| 19 | a stock DiShare share of the same app is up, and our start of it times out after creating its client | the stock share may stop | a same-target start against an active stock share (`docs/dishare-api-notes.md`) |
