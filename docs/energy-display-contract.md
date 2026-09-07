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

The window is `ConsumptionWindow.KM` = 10 and the unit names it:
«кВт·ч/100 км · за 10 км» once ten kilometres of known road are in the log, and
«· за 3,7 км» while it is still filling - the *known* road, not the bucket count.
The car page prints the same two forms in its own case, «ЗА 10 КМ» / «ЗА 3,7 КМ»,
and never rounds a filling window to a whole number.

### 2.3 The history behind that figure

One chart, drawn twice. **Bins of 500 m anchored to the odometer's own half
kilometre**, so a bin's membership never changes after it closes and the shape
does not re-phase every hundred metres. Twenty bins are the window; the newest is
partial and is drawn at the width of the road it has.

**A bucket belongs to the bins its road covers**, `[odometer − km, odometer)`,
not to the bin its closing odometer falls in - a bucket closing at 100.5 covers
the bin that *ends* there. Its energy and its known road go to those bins pro
rata by road, which is the only division a bucket supports: it holds one integral
over one stretch and no record of where inside it anything happened. A bin's
value is then `Σ E / Σ d × 100` over the road it holds.

A bin whose known road is under half its road is a **hole**, and that is a
*drawing* rule: it is drawn as nothing, and the road under it is still counted
for the axis. It says nothing about the figure. The figure's own exclusion is
§2.6's, one level down and about buckets: the mean is over the closed buckets
that are readings, so a known bucket inside a hole bin is in the figure and a
hole bucket inside a drawn bin is not. Nothing is ever drawn as an invented zero.

**Scale: linear, 0…40 up and 0…20 down, clamped, with a mark.** Linear because a
trend is read as proportion; 40 because what the chart is for is the difference
between 15 and 25 kWh/100 km, which the cluster's 37-unit box shows as 9 units at
40 and 6 at 60, and a 500 m bin past 40 is spirited driving while a launch is far
past it; 20 down because a half kilometre of regeneration rarely returns more. A
bin past either ceiling is drawn to the ceiling with a three-unit `INK` tick
standing just outside the box over it - the reader sees it was cut. If the
recording says ordinary driving clamps often, the ceilings move; they are two
constants in one place and the plan board prints them.

**Form: steps**, which the owner chose on the fourth board («он как бы дискретный
ступеньками»). Twenty steps in the cluster's 232 units are 11.6 units each,
2.5 mm of glass, 10.7′ from 800 mm - a step the eye can count. A hundred steps of
2.3 units were the comb. Spending is one continuous `MUTED_DEEP` field under an
`INK` edge; return is a `RETURN` patch on its own posts, one per run of returning
bins, standing on the zero line; a hole breaks both.

**Where.** On the cluster the chart is the petal's history box, unchanged in
place and size (`ContourPlan.petalBox…`, zero on the figure's baseline, 37 up,
13 down). On the car page it replaces the two-minute power trace at the trace's
own size, and the axis gutter names the ceilings, «40» and «−20». Same bins,
same scale law, same colours; the pixel height differs and nothing else.

The two-minute power trace (`PowerTrace`) leaves the product with this. It was a
second history of a quantity the headline already shows, and it was the reason
the two screens' graphs could not be the same graph.

### 2.4 The trip

Unchanged in definition (`TripEnergyLedger`): net `∫P dt` over the trip, road
from the odometer under one `OdometerGate`, recuperation only with the engine
off, the engine's share `∫G dt` while it runs, its minutes. A trip quantity a
fresh packet does not carry did not happen this trip, and its caption goes with
it (`ContourValue.ledger`). Seat order on P: «ЗА ПОЕЗДКУ» at the edge, then
«ДАЛ ДВС», then «● РЕКУПЕРАЦИЯ» at the far end, packed from the edge.

### 2.5 The engine

Running = `ENGINE_RUNNING ≥ 1`, proven 0/3 through a full start/stop cycle. The
revolutions (`ENGINE_RPM`, real rpm on the same cycle) are shown only while it
runs. `G` = `GENERATION_KW`, proven parked to be kilowatts and to equal the
pack's charge exactly while parked. **What `G` is in motion is open**: the two
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
- its sentence is **«ДВС ДАЁТ 8 кВт · ПОСЛЕДНИЕ 1:22»**: what the engine is
  giving, not where it goes. «В БАТАРЕЮ» was a claim about `G`; «даёт» is true
  under either meaning and is the same verb the trip's «ДАЛ ДВС» uses. No dot:
  the blue mark means «into the pack» everywhere else on the panel;
- its field is the history colour - `MUTED_DEEP` under `INK`, like the petal -
  not `RETURN`, for the same reason;
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
road, and a bucket with less than half its road known is a hole. **A hole bucket
is out of the figure whole** - out of the sum, out of the road the unit names -
which is what makes «за 3,7 км» a promise about the number beside it. An odometer
step longer than one bucket closes one bucket of that road with that road
recorded - the axis stays the road, never the record count. Restart continuity
keeps working through the journal, which gains the road per bucket.

**And the window is bounded by the odometer as well as by the road.** Ten
kilometres of buckets from yesterday are still ten kilometres of road; what they
are not is the ten kilometres behind the car. The journal retains thirty for
restart continuity, and a restore anchors `OdometerGate`, so what the screens see
is the tail whose own readings are inside `ConsumptionWindow.KM` of the newest
one. The buckets from before a re-anchor leave the window by the same rule.

## 3. Words

| where | says | never |
| --- | --- | --- |
| direction of `P` | «ИЗ БАТАРЕИ» · «В БАТАРЕЮ» · «● В БАТАРЕЮ ОТ ДВС» · «● В БАТАРЕЮ ОТ ЗАРЯДКИ» · «БАТАРЕЯ» | a sign |
| the figure's window | «кВт·ч/100 км · за 10 км» / «за 3,7 км»; car page «ЗА 10 КМ» / «ЗА 3,7 КМ» | «за 3 км», a whole-number rounding of a filling window, «ПОСЛЕДНИЕ 2 МИНУТЫ» |
| the trip | «42 км · ЗА ПОЕЗДКУ» · «ДАЛ ДВС» · «● РЕКУПЕРАЦИЯ» | a zero, a caption over nothing |
| the engine, live | «ДВС · об/мин» + rpm; box «ДВС ДАЁТ 8 кВт · ПОСЛЕДНИЕ 1:22» | «В БАТАРЕЮ» on the box, a dot on the box, «ГЕНЕРАЦИЯ», «ОБОРОТЫ» |
| the engine, stopped | «ДВС · мин за поездку» + minutes | a box of zeros |

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
  known road;
- **a hole**: a gap in the chart, the road under it still counted.

## 5. Layout invariants

- The car page's narrow (416 dp) layout keeps the consumption figure and its
  window; if the stack does not fit the pane's height, the layout's unit shrinks
  until it does. Content is never drawn past the pane.
- The cluster's geometry is `ContourPlan` as of 2026-09-05: the petal's block
  one unit gap right of the axis, the box 232 × 49.92 on the figure's baseline,
  the seats packed from the edge. This page changes what is drawn in the box,
  not where the box is.
- Glass 320 mm wide, eyes **800 mm** (owner, 2026-09-07; 750 was the tape on
  2026-09-04). A cap of `size` units subtends `size × 0.648′`: 52 is 33.7′, 34
  is 22.0′, 18 is 11.7′. The ladder 88·52·34·18 stands; 18 is furniture, read
  by a deliberate look, and no figure the driver needs on the move is set in it.

## 6. What the boards show

`tools/design-canvas/ClusterContour*.dc.html` and `StripPages.dc.html` are the
drawings of this page: the whole cluster with the stock zones as a labelled
schematic, and the car page inside the strip at both widths. The stock zones on
the cluster board are a model; the two numbers the model is checked against are
in the generator (`RANGE_BADGE_SEEN`, `POWER_FIGURE_SEEN`) and the grid
photograph that replaces the model is still owed. A board can establish geometry
and consistency; it cannot establish glance readability at 800 mm, and nothing
here claims it does - the ladder's arithmetic is the argument, and the car is the
test.

## 7. Proof

- **One source.** `EnergyReadouts` (or the name the implementation settles on)
  turns a `VehicleTelemetry` into every energy string and chart both screens
  draw: direction word, power figure and colour, consumption figure and colour,
  window caption, the twenty bins with their widths and holes, the engine's
  sentence. Both renderers call it and format nothing themselves.
- **Cross-screen equality.** A test drives both surfaces' readouts from one list
  of snapshots - electric drive, return, engine giving, engine running and
  giving nothing, engine just stopped, standing, charging, filling window, a
  hole, a bin past 60, link lost - and asserts equal strings and equal bins.
- **Independent arithmetic.** The expected consumption figure and bin values in
  those tests are computed in the test from the raw buckets, not through the
  production helpers.
- **Replay.** `captures/vehicle-log/*.csv` (the recorder's output) is fed through
  the hub's own log, ledger and traces in a JVM test; the test asserts the
  invariants that do not depend on what a signal means: road under the chart
  equals odometer travelled, the figure equals energy over known road, no bin
  is drawn where the log had no energy, the engine's box is never up with the
  flag down.
- **The boards.** `ContourBoardContractTest` and `StripPagesBoardContractTest`
  hold the generators to `ContourPlan` and the page's constants, including the
  bin count, the ceilings and the sentence.
- **Mutations** on the arithmetic (§2.2, §2.3, §2.6) before the merge, as on
  every wave before.

## 8. Open, and what closes each

| open | closes with |
| --- | --- |
| what `GENERATION_KW` is in motion | one recorded drive with the engine running at speed, `tools/vehicle_log.py` |
| the sign of `POWER_KW` under acceleration | the same recording |
| whether `ENGINE_RPM` reports anything while the engine is off in motion | the same recording |
| the chart's ceilings, 40 and 20 | the distribution of 500 m bins in the recording |
| the stock zones' true edges | the grid photograph |

Until the first three are closed the engine is drawn as §2.5 says and no other
way.
