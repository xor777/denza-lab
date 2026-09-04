# Design canvas

The artboards behind the Denza Apps redesign, and the tooling that keeps
them honest. Published as a Claude Design canvas; these are the sources it is
seeded from.

## Why this is in the repo

An adversarial review of the first cut of these boards found about sixty defects
and almost none of them were judgement calls. A tick lying along an arc instead
of across it, a label naming 60 kW at an angle that meant 150, twenty-seven type
sizes where six were declared, four sibling boards on four different grids, a
readout with a rule through its own numeral: every one of those is a measurement,
and measurements should not be made by hand twice.

So the boards are not drawn any more. The instrument boards are *computed* from
the same constants the app draws with, and every board is *measured* by a script
that reports what collides.

## The files

| | |
| --- | --- |
| `*.dc.html` | the artboards, one sandboxed document each |
| `canvas.json` | positions, pages and the launch view |
| `gen_cluster.py` | emits the three cluster boards as they stand on the car today, with the current fixed 3 km consumption window |
| `gen_next.py` | emits the proposed cluster: one horizon, two histories, the gauge |
| `gen_contour.py` | emits the Contour - the cluster concept that won the 2026-09 contest - as the calm panel, eleven scenes, and the plan with every number on it |
| `gen_kit.py` | emits the two boards that describe the system, from the system |
| `gen_panes.py` | emits the dashboard's two pane boards from `Main.dc.html` and the pane geometry, and the dashboard underlay both sheet boards are drawn over |
| `panel_frame.py` | archived tooling for the four retired head-unit instrument concepts; rebuilds Energy |
| `normalize.py` | maps type, radii, borders and icon weights onto the scales |
| `audit.py` | opens every board in headless Chrome and reports what collides |
| `shot.py` | renders one board to a PNG at panel pixels, so it can be looked at |
| `compare.py` | lays a board against a screenshot of the car and reports what moved |

`Energy.dc.html`, `Battery.dc.html`, `Thermal.dc.html` and `Engine.dc.html` are
kept as historical design evidence. Those head-unit pages are retired and are not
a current app contract. The active contracts are the head-unit dashboard -
`Main.dc.html` and the two pane boards generated beside it - and the cluster
boards; the cluster consumption history has one fixed 3 km window and no
selector.

`OneThird.dc.html` and `TwoThirds.dc.html` used to hold the retired pager and now
hold the dashboard in the two pane widths. The names were the right names for
those boards and the wrong content was sitting under them, which is a worse
failure than a stale board with a stale name: anybody opening "1/3" to see what
the narrow pane looks like was shown a screen that had been deleted. The pager
compositions are in git history.

## Running the audit

```bash
python3 audit.py
```

It inlines each board into its own iframe, lets the page measure itself, and
reports five things: text past its artboard, text past the column it was placed
in, a label wider than the control it is printed in, text meeting text it has no
relationship with, a flat rule crossing a numeral - plus the type sizes, gaps,
radii and stroke weights each board actually uses. Nothing may sit outside the
ramps below.

`GAPS` is the newest of those four and was the last one missing. It reports every
flex or grid gap, padding and margin a board spends, which is the one ladder that
had no counterpart anywhere: the type sizes were measured, the radii were
measured, the strokes were measured, and `30`, `14` and `6` sat on the dashboard
for a wave because nothing counted them. Percentages and `auto` margins are left
out - both come back resolved to whatever the box happened to be, which measures
the layout rather than a decision - and so is zero. A derived pitch will still show
up there: the analyser's `9.39` is its column spacing, which comes out of the field
width and the band count and is on no ladder by design.

It knows about occlusion: text under a modal scrim is covered, not colliding, and
adjacent cells of a segmented control are meant to touch.

The third of those - TIGHT - was missing for a long time and this page used to say
so. `AdbGateNarrow.dc.html` drew its primary action's words running out past both
ends of their own pill and the audit called that board clean, because the text was
inside the artboard, inside its column, and touching nothing it was unrelated to:
the text *is* the button there, so its rect tells you nothing. What gives it away
is the element's own content being wider than the box it has. Only elements that
draw a box are asked - a bare label overflowing an invisible grid cell paints into
the gutter and is COLLIDE's business if it reaches anything, and SVG text has no
box of its own.

## Looking at a board

```bash
python3 shot.py Main --scale 2
```

PNGs land in `_shots/`, untracked. Scale 1 is the 1280x800 the head-unit boards
are drawn in; scale 2 is the 2560x1600 the screen actually has, which is what to
compare a screenshot of the car against.

A board is not just its `<x-dc>` body: the `<script data-dc-script>` block after
`</x-dc>` holds the data every `<sc-for>` loops over - the spectrum's bars, a
picker's applications. A renderer that takes the body alone shows an empty field
and gives no sign it dropped anything. That happened here, and the empty picture
was believed over the board: the owner was told the spectrum analyser had never
been designed while it sat in `Main.dc.html`, drawn, with its own data.

So every line `shot.py` prints ends with `loops: N left: M` - the loops the board
declares against the ones still standing in the rendered DOM. `left: 0` is the
only good answer. Anything else is marked HOLES, and the picture is not evidence.

## The canvas the app actually gets

Measured off the car, not assumed:

| | px | dp @2.0 |
| --- | --- | --- |
| screen | 2560 x 1600 | 1280 x 800 |
| app window | 2560 x 1472 | 1280 x 736 |
| status band over it | 112 tall | 56 |
| system dock below it | 128 tall | 64 |
| **what the app can draw** | **2560 x 1360** | **1280 x 680** |

The head-unit boards are 1280 x 800. The app never has 800 dp and never will: the
dock takes 64 off the bottom before the window even exists, and an opaque status
band takes 56 more off the top. So every one of those boards is drawn on 120 dp of
height that does not exist - fifteen per cent - and anything laid out to fill the
board arrives on the car compressed, scrolled, or off the bottom edge.

Horizontally there is nothing to correct: the background runs edge to edge and the
first tile starts at 96 px, which is the `48` side margin exactly.

This is the first thing to fix before "pixel perfect" means anything, and it is not
a fix any single board can carry - it changes what fits on all of them.

## Comparing a board with the car

```bash
python3 compare.py Main --car screenshot.png
python3 compare.py Main --capture          # takes one off the vehicle
```

It renders the board at the car's scale, lays the two side by side with a heat map
between them, and reports the cells whose mean colour moved, in board coordinates.
It compares over a grid rather than pixel by pixel on purpose: Skia and Chrome
rasterise the same glyph differently, and a per-pixel diff reports nothing but
that. A cell that moves is a layout or a colour that moved.

`--capture` is the only thing in this directory that touches the vehicle.

Read the number with the build in mind. A 62 % difference on a car running last
week's APK is last week's APK, not a design that has drifted - the comparison is
only as honest as the pair of things being compared, and it will not tell you which
one is stale.

## One screen, three widths

The head unit gives an app three window widths - 1280 dp full screen, 828 dp for
two thirds, 416 dp for one third - and the third of those is where a design fails
first. `AdbGate.dc.html` and `AdbGateNarrow.dc.html` are one screen drawn twice for
exactly that reason: same card, same components, same copy rules, 1280 against 416.

The dashboard is drawn three times, and `gen_panes.py` emits the two panes from
`Main.dc.html` so their glyphs and their states cannot drift from the full
screen's. A pane is not a smaller dashboard: it drops the tile.

| | 1280 | 828 | 416 |
| --- | --- | --- | --- |
| caption bar the system keeps | – | 24 | 24 |
| side margin | 48 | 20 | 12 |
| content | 1184 | 788 | 392 |
| a feature is | a tile 187 x 164 | a chip 60.7 | a chip 55.3 |
| eleven of them take | 2 rows, 340 | 1 row, 60.7 | 2 rows, 122.7 |
| the strip gets | 1184 x 296 | 788 x 531.3 | 392 x 469.3 |

Eleven tiles at the width their names need are four rows at 828 dp and six at 416 -
692 and 1044 dp of a window that has 656 once the car has taken its caption bar.
So the page scrolled and the analyser sat below the fold, which is a strange way
to spend a pane: it is entered deliberately, with the other two thirds of the
screen already doing something, and the one thing on this screen that moves is
the analyser. The chip keeps the icon and both gestures and drops the caption,
carrying "is this on" in its border, its ink and a dot. Eleven chips cost one
60.7 dp row at 828, or two 55.3 dp rows plus their gap at 416.

That is the shape the archived boards under these two names always had: icons in
a row, one large panel below. What was in the panel was four vehicle instruments
that have since been retired, and the analyser goes where they were.

The figures change shape rather than size, which is the same rule the wide screen
follows - three blocks hung apart down a 320-wide column at 1280, three blocks
side by side with a rule between them at 828, three label-and-value rows at 416.
Two type sizes, all three widths, every one of them on the ramp.

**One anatomy, three shapes.** Each of the three readings is a tracked capital and
a large light figure with its unit against it, and the rate hangs off the reading
rather than off the edge of whatever box it is in. The sunset used to be the odd
one out in every width at once: a sun in the vehicle's own amber, a 34 where its
neighbours were 46, and its word set as a unit rather than as a label, so the one
coloured caption on the dashboard was the one saying the sun goes down at the usual
time. Amber is what the car draws when it wants a decision from the driver; the
label already says which event it is. And the narrow pane's three readings share a
left edge now: hung off the right they lined the last character of a clock up with
the last character of a distance and left the digits that matter in three different
places, which is the one thing a stack of rows is for.

**The analyser's texture does not scale with its columns.** A bar is 22.97 dp wide
at 1280, 21.4 at 828 and 10.8 at 416, and the crown on top of it stays 4, its
corner stays 2, the scanline pitch stays 11 and the ticker's dot cell stays 3.4 at
all three. That is deliberate and it follows from the paragraph above: every width
is laid out one unit to one dp, so a segment, a corner and a dot are the same size
on the same glass in all three, and only the column gets narrower as the field
does. Scaling the texture with the bar would halve the segment height in a narrow
pane, and the analyser would read as a different instrument seen from further away.

Neither pane is the wide composition rescaled. `PanelCanvas` scales type along
with everything else, and the wide strip squeezed into a two-thirds pane put the
ladder's 46, 24 and 15 on the screen at 28, 15 and 9. Each pane's strip derives
its virtual height from the width it was handed, so the two scale factors are
equal and one unit is one dp whatever height the chips left it.

**The caption bar is measured, not assumed.** A pane window is 680 dp tall and
BYD's freeform windowing keeps the top 24 for its drag handle; `safeDrawing`
reports it. The first cut of these boards spent that 24 on content and the foot
of the strip was drawn past the bottom edge of the window - the same failure, one
window width along, that the full screen's own panel height exists to prevent. The
app no longer does the arithmetic at all: the strip takes the remainder.

`PaneBoardContractTest` joins both boards to the Kotlin the same way
`MainBoardContractTest` joins the full screen: artboard size, the caption bar,
the chip's shape and its dot, the grids and the margins, both shapes of the
figures with their type sizes, the ticker band against the bar field, and that
the eleven glyphs are the ones `Main.dc.html` draws.

The preceding ten-tile boards were checked on the car on 2026-08-27 and measured
off screenshots. The generated eleven-tile boards preserve the same measured
window, margins and caption bar, pass the local geometry/overflow contracts, and
still await a live screenshot comparison after this feature is installed.

Both draw the gate at `AWAITING_CONFIRMATION`, which is the busiest state it has:
a title, an instruction, what the car answered about its own debugging switch, and
all three actions. That is the point of the pair and it is the part the pair used to
get wrong - it drew two *different* states, with two different icons and one action
against three, which shows nothing about width at all. What changes between them
now is only what width changes: the row of two becomes a stack of three, and the
card takes the narrower padding rung.

The narrow one is where the row of two died. A Row measures its children in order,
so the outlined action took the width it asked for, the primary action was handed
what was left, and its label ran out past both ends of its own button. Neither
button lost in the end: a card 312 dp wide has room for one on a line and no room
for two, so each takes a line, primary first, because a stack is read from the top.

A fullscreen-only board cannot show any of that, which is why the pair exists.
Any screen that can appear in a pane deserves the same treatment.

## The two ramps

The cluster and the head unit are different screens at different distances, so
they get different ladders. What they share is the rule: a size not on the ramp
is not available, and no two rungs sit closer than about 1.2x - a difference you
can measure and cannot see is a difference that will be drifted into.

| | rungs | step |
| --- | --- | --- |
| Cluster (virtual units, 1.70 on the panel) | 52 · 34 · 24 · 18 · 13 · 11 (+ `104`, proposal only) | 8 wide, 6 narrow |
| Head unit (pixels, 1:1) | 62 · 46 · 34 · 24 · 19 · 15 | see below |

`82` used to head that second row, here and in `gen_kit.py` and in `normalize.py`,
while `DenzaMetrics.Type.RUNGS` had six rungs from 62 down and no active board drew
it. Same shape as `104` one level along, same answer: a constant nothing reads is a
promise, not a rung. It is off all three records. `Battery.dc.html` still draws it
and stays as it is - that board is retired evidence, not a contract.

Spacing on the head unit is `48 · 32 · 20 · 12 · 8 · 4`, six rungs none closer
than one and a half times the one below: the screen margin, the gap between two
groups, the padding inside a surface, the gap between neighbours in a group, the
gap between two lines of one thought, and the gap between a glyph and its word.
That ladder is newer than the others and it is the one that had no counterpart
in the app at all - the head unit's screen was spending seventeen adjacent
values between 4 and 32.

Radii are `22 · 12 · 6 · 2` plus a track's own half-height. Borders are one pixel
- selection is carried by fill and ink, never by a thicker edge. Icons carry one
optical weight of `2.0`, so a stroke is `2.0 × 24 ÷ rendered size`.

Sizes that belong to one component rather than to a ladder are named rather than
placed: the tile is `164`, its icon `30`, an application tile `96` with a `44`
icon, a settings panel `480` on the right edge with a `62` action under it, a
picker's grid stops growing at `360`, a row a finger has to hit is `56` and a
segmented control `42`. They live beside the ladders in `DenzaMetrics` so
that a fixed height is a decision with a name on it rather than a number typed
where it was needed.

The head unit's ladders live in `dev.denza.apps.design.DenzaMetrics`, and
`DenzaMetricsTest` measures the gaps rather than the values - a rung a few per
cent from its neighbour cannot be chosen deliberately, so the gap is the thing
worth testing. `Main.dc.html` is drawn on those same numbers.

The cluster ramp is `InstrumentDensity.RAMP` in the app and the boards restate
it; `gen_cluster.py` is the one place to check that they still agree. That ramp
is the six rungs from `52` down to `11`, and the app draws nothing else.

`104` is not on it yet. It is the headline numeral of the proposed cluster and it
lives on the `gen_next.py` and `gen_contour.py` boards alone, because the app does
not draw that cluster yet and a constant nothing reads is not a ramp rung, it is a
promise. It is sized as one deliberately - exactly twice `52`, so that adopting the
proposal extends the ladder rather than starting a second one beside it - and
it joins `InstrumentDensity.RAMP` on the day the proposal is adopted, in the
same change, or not at all.

The Contour has now won the contest, so that day is scheduled rather than
hypothetical - but the boards were drawn first, deliberately, and the app is
untouched until the owner has looked at them. Until the renderer changes, this
row still reads `52 · 34 · 24 · 18 · 13 · 11` and the sentence above still holds.

The first draft of those boards ran at `58` and `19` beside the ramp's own `52`
and `18`, which is the drift the ramp exists to prevent: a difference you can
measure and cannot see. This page then claimed the two records agreed while
`104` sat in one and not the other, which is the same failure one level up -
caught by the parallel session reading both, not by anything here.

## What a sheet is drawn over

`Config.dc.html` and `DefaultApps.dc.html` both draw a panel over the dashboard,
and both used to carry their own copy of it. Config's was a real copy of
`Main.dc.html` at the moment somebody pasted it; DefaultApps' was a mock - three
tiles in the wrong order, three empty boxes where the other eight go, no second
row, and three grey bars where the analyser is. So the board answering "what does
this cover" was showing a screen that has never existed, and the board answering it
honestly was one edit of `Main.dc.html` away from being wrong too.

`gen_panes.py` emits that underlay into a marked region of both boards, scoped to
`.underlay` and with its loops already expanded, so a sheet board needs no data
block of its own. Only what sits behind the scrim is generated; the panel over it
is still drawn by hand.

Three things they had two of, and now have one:

| | |
| --- | --- |
| the scrim | `rgba(7,8,10,0.72)` - the ground at 0.72, which is `DenzaColors.Scrim` and what every modal surface in the app is drawn over. Config dimmed the dashboard to 0.30 and put black at 0.55 over it; DefaultApps used 0.48 under black at 0.54. Three depths of dark mean the screen behind a window changes brightness depending on which window opened. |
| a chosen application | a hairline edge in the accent, the well behind the icon tinted with it, the name set in it - `DenzaAppTile`, and nothing else. Config added a glow, DefaultApps added a check badge, and neither is anything the code draws. |
| where the action sits | `DenzaSheet` gives the settings `weight(1f)` and hangs the one action under them, so it is under the same thumb whatever the panel was opened from. Config used to hang a spacer between the two as a flex item of its own, which bought a 32 either side of it and read as two clumps at the ends of an empty panel. |

`DefaultApps.dc.html` also draws what the sheet actually holds now - the status line
where the code puts it, the "обновить список" action under the grid, and the
Shortcuts note in full. In a 416 pane that is taller than the window and the
settings scroll, so the note runs on under the fold; the board draws the first
screenful, which is what a still can show of a scroll and is not a shorter note.

## Application icons come off the car

The boards used to draw an application as its initial in a well - "Я", "Н", "2".
It looked tidy in a way the screen never will: real icons are square, saturated
and drawn by six different vendors with six different ideas about padding, and a
row of them is the thing that actually has to hold together. A board that draws
letters is not showing the problem.

So the icons on `Config.dc.html` are the car's own, cropped out of a screenshot of
the panel and embedded as base64. Not pulled from the APKs and re-rendered - taken
as the head unit draws them, adaptive-icon shaping and all. The code was never at
fault here: `DenzaAppTile` has always taken a `Drawable` from the PackageManager
and only falls back to the initial when there is genuinely no icon.

Comparing the two also settled an order. The board put our own instruments first;
the app puts them last, on the reasoning that they are the one choice that can
never be missing, so leading with them pushes whichever navigator the driver
actually uses one tile along. That is an argument about the driver's hand rather
than about implementation, so the board gave way.

## The Contour boards

`docs/cluster-contest-2026-09/` ran five cluster concepts against one brief and
`VERDICT.md` picked "Контур" with five binding corrections. `gen_contour.py` is
that concept drawn: three boards, all from the same constants.

| | |
| --- | --- |
| `ClusterContour.dc.html` | calm driving, engine asleep - the state the panel is in most of the time |
| `ClusterContourStates.dc.html` | eleven scenes as a column: first seconds, calm, regeneration, the engine generating both ways it can be drawn, standing on P, charging, link lost, night, an exception, and the missing ADB key |
| `ClusterContourPlan.dc.html` | the skeleton alone, over the three apertures and the cell grid, with every anchor named and measured |

The boards were drawn before a line of the panel was written, shown to the owner,
and redrawn twice against what he said. Those two passes are the reason to read
this section rather than only the concept.

**What the first drawing got wrong.** "Злоупотребление полосками" - it had five
bars: the band, a sag rail, a revolutions rail, a generation rail and a return
strip. "Не понимаю, что такое потрачено / вернула / ДВС" - three trip figures
whose captions named directions rather than quantities, and "ДВС" over a number
reads as a rev counter, which is exactly how it was read. "Жаль, что пожертвовали
температурами" - they had been moved off the panel into an exception line.

So: one bar on the panel, and it is the band. Everything that used to be a second
bar is now a number with its word under it. The temperatures are back as a shelf
of their own. The trip's figures are headed `ЗА ПОЕЗДКУ · КВТ·Ч` and named
`ИЗ БАТАРЕИ`, `РЕКУПЕРАЦИЯ`, `ОТ ДВС`, and a zero is never drawn.

**What the second drawing was missing.** "Намного симпатичнее! Только моторы
одной цифрой - не очень, три не влезают? И хочется графики: динамика расхода, как
меняются обороты, как меняется отдача заряда." All three were already in the data
and nothing was drawing them: `motorTemps` reports three drive motors and the
shelf showed one, `ConsumptionLog` closes a 100 m bucket at a time and the cluster
keeps thirty, `EngineTrace` keeps 120 one-second slots of revolutions and
generation. So the third pass adds three histories, under one rule that keeps the
panel's own argument intact: **a history is a box.** It is small, it stands beside
the figure it explains, it carries no axis and no number of its own, and it never
outshines the band. Three of them:

| | |
| --- | --- |
| the petal | thirty closed 100 m buckets - three kilometres - left of the consumption figure, with the figure's own baseline as the zero line and the dashed rule at the number the figure prints |
| the right shelf | the engine's last two minutes while it is running, revolutions as an ink line against 0…3000 and generation as a soft `RETURN` area under it, replacing the trip balance until five seconds after the engine stops |
| the band | where the tip has been over the last ten seconds, as one 12 % smear under the live body - the only history that is not a box, because the band already has an axis and a zero |

**Three motors, one word.** The rear pair is per-side, so a single reading threw
two thirds of what the car reports away and hid the one case the row exists for -
one of them running hotter than the others. Each of the three is coloured by its
own level. They cost the shelf a cell 200 units wide, which is why a cell is now
as wide as the wider of its caption and its payload rather than one width for the
whole shelf, and why the gap between two cells is twice the gap between two motors
inside one - without that the inverter read as a fourth motor.

**The apertures are computed, not drawn.** `ClusterMapLayout`'s integer division
is restated exactly - `2560 * 20 // 100 * 40 // 100 * 4 // 3` and the rest - so
the clear band, the two corner quarter-ellipses and the petal come out where the
app already believes they are. Every anchor is then derived from four decisions:
one margin of `48`, the rhythm of `8`, the rungs `104 · 52 · 34 · 18`, and text
metrics measured in the real faces rather than guessed at.

**Two shelves, one family.** Each corner aperture holds a heading, one 52 figure
and one 18 line, and that is all it can hold - it is 301 units wide at the top
and narrows to nothing by 160, and a row costs its lead plus its full type size.
So the shelves stand below, in the clear band's flanks, on one pair of baselines
with one anatomy: a figure with its word under it. Temperatures on the left at
34, the trip's kilowatt-hours on the right at 52; every cell is as wide as the
wider of its own caption and its own payload, rounded up to the rhythm, so the
four left cells are `88 · 200 · 96 · 112` and the right ones are `128` each. The
whole left shelf, fourth cell and all, still stops 37 units clear of the hero's
field. The left shelf is also what filled the empty quarter of the band the first
drawing left.

**Fields, not strings.** Every number lives in a reserve field sized by its digit
count times a *measured* advance, and its unit hangs off the field, so `9` and
`300` leave the unit exactly where `34` does. The hero goes one step further: its
digits are right-aligned in the field and the field, its gap and its unit are
centred on the axis as one group, because centring the field alone left `кВт`
stranded sixty units away from a two-digit reading.

That advance is `0.6`, and this page said `0.5` for a wave. It was written down as
a measurement and it is not one: Roboto Mono advances six tenths of its size per
character at every size these boards set - `28` at 34 is 40.81, `1780` at 52 is
124.83, `300` at 104 is 187.23. Every reserve field on the second drawing was
therefore a sixth too *narrow*, which is the opposite of the correction that
introduced it, and it was visible in two places at once: `552` hung three units
past the left margin into the panel's own edge, and `об/мин` sat twenty units
behind the vehicle's graphics in a corner that has 199 usable units at that
baseline and wanted 219. Both corners carry their unit in the heading now -
`БАТАРЕЯ · В`, `ДВС · ОБ/МИН` - which is the concept's own Tufte rule and what the
right shelf's heading always did. The hero keeps its `кВт` beside it: it is the
one figure read on the move.

The lesson is narrower than "measure things". Both drawings measured; the second
one wrote the measurement down backwards and nothing here could catch it, because
`audit.py` measures whether text collides and not whether a reserve matches the
face. Three numbers in a comment - what `28`, `1780` and `300` actually advance -
would have caught it, and they are in the generator now.

**Rows advance by type size, not cap height.** This is the convention the other
generators here already follow and the one this board first got wrong. A cap
height is what the ink occupies; the box a browser and a `Paint` both reserve
runs from ascent to descent, so spacing rows by cap height put a heading's
descenders inside the digits underneath. Every collision `audit.py` found on the
first pass was that mistake.

**The one lit thing is the band.** The concept put the glow's centre in the middle
of the clear band with `ry` half its height; drawn, that pool sat fifty units
above the instrument it belongs to and lit whatever the flanks held, so the panel
had two lit things. It is centred on the band now, with `ry` the distance to the
lower aperture edge - which is what the original number was buying: an alpha that
reaches zero before the vehicle's own graphics can cut it.

Three histories are three chances to break that, and each is held back on purpose:
the consumption bars stop at 80 % ink and fade to 22 % at the oldest edge, the
generation area is a 30 % field with its shape carried by a hairline rather than a
solid blue slab, and none of the three uses `DATA_PEAK`. Warm ink means "the live
edge of the data" on this panel, and the live edge is the band's tip.

**The petal's box balances the unit.** Make the box and its gap add up to the
unit's gap and the unit's own reserve, and two things come out true at once: the
group - box, figure, unit - is centred on the axis, and so are the digits inside
it. Sized by eye instead, the box pushed the petal's figure twenty units right of
the hero's, and two large numerals one above the other is exactly where that is
visible. The box's zero line is the figure's baseline, and its dashed rule is the
number the figure prints, which is what makes thirty anonymous bars readable
without an axis: nobody is asked what a bar is worth, only whether it is above or
below a line they can already read.

## The boards and the code are joined

A board and the screen it designs are two records of one decision, and for a
while nothing compared them. `audit.py` measures the boards against themselves,
`DenzaMetricsTest` measures the ladders against themselves, and the only thing
joining the two was somebody remembering. It did not hold: the first cut of the
head-unit screen carried every number off `Main.dc.html` and looked nothing like
it, because the board hangs a tile's words off the bottom edge and the code
stacked them from the top.

So two unit tests in `:denza-apps` parse the boards at test time and assert the
Kotlin matches:

| | |
| --- | --- |
| `MainBoardContractTest` | reads `Main.dc.html` - tile height, padding, radius, the `space-between` that hangs the words apart, both text styles with their leading, icon size and stroke, grid columns and gap, page margins |
| `SpectrumBoardContractTest` | reads the analyser out of the same board - band count, bar width fraction, that the columns add up to the field they are drawn in, peak height, corner radius, gradient stop, reflection crop and opacity, scanline pitch |

That middle clause in the second row is new and is there for a defect every other
check passed. The board set 26 bars of 22 with 9 between them - 797 of the 834 it
had - so the analyser stopped 37 dp short of its own right edge and the strip read
as a rendering that had run out of data. Every ratio on the board was right; the
sum was not. The columns are `22.97` and `9.39` now, written to the hundredth,
because the one pitch that both keeps `BAR_WIDTH_FRACTION` and lands the last
column on the right edge is not a whole number.

They fail in both directions on purpose. Editing a board without the code breaks
them, and so does editing the code without the board - which means neither record
can move alone, and a design change that never reached the app cannot pass CI
looking finished. What they cannot do is run Compose: they prove the constants
and the declarations agree, not that the drawn result does. That still takes
`shot.py` beside a screenshot of the car, which is the step that was skipped.

## Left standing, and why

Four things a review of these boards found that are still here on purpose. Each is
cheap to change and wrong to change quietly.

**The narrow pane's second row of chips has five of six.** Eleven features in two
rows of six leaves a hole at the right of the second, and the feature that ends the
row is switched off, so the band fades to the right. Neither is the board's to fix:
the count comes from the registry, the order comes from `DashboardTiles`, and the
hole closes on its own at twelve. `ChipDensity.dc.html` draws all four counts.

**The hint tile's glyph reads as an eject symbol.** A chevron over a rule is what
`DenzaIcons.Hud` draws and what every board draws with it. It is a fair complaint -
the glyph says "eject" before it says "something projected onto the glass" - and
the fix is one change to `DenzaIcons` and eleven boards in the same commit, not a
board drawing a glyph the app has never had.

**The deep muted ink is under contrast at 15 px.** `#6E767F` on the sheet's own
`#15181F` measures 3.86:1 where 4.5 is the floor, and 4.35:1 on the page ground.
The boards and the generators now use `#7C858F`, which measures 4.70 and 5.31 and
stays a clear step below `MUTED` at 5.43 so the two are still two. `DenzaTokens`
has not moved yet; when it does, `MUTED_DEEP` takes that value and the records meet
again. The obvious-looking `#8A929C` does not work: at 5.59 it is brighter than the
ink above it.

**The ticker's two records name a track in opposite orders.** The board writes
"M83 · MIDNIGHT CITY" and `SpectrumRenderer` builds `"$title · $artist"`, so the
car shows "MIDNIGHT CITY · M83". One of them is wrong and neither is obviously so;
it is a product decision rather than a measurement, and it is written down here
rather than settled by whichever record was edited last.

## Regenerating and republishing

```bash
python3 gen_cluster.py && python3 gen_next.py && python3 gen_contour.py \
  && python3 gen_kit.py && python3 gen_panes.py && python3 audit.py
```

`gen_panes.py` belongs in that line: it emits both pane boards *and* the dashboard
underlay inside the two sheet boards, so a change to `Main.dc.html` reaches four
files rather than two.

Then seed a fresh payload with the `design` skill's `seed-canvas.mjs` and publish
to the existing canvas, pinned to the runtime it was built for:

    https://claude.ai/code/artifact/f97891c4-0dd3-4467-a879-6a1d59ea8f73
    contract 0.1.31

That page is the owner's private artifact - the link is here so the canvas can be
updated rather than forked, not because it opens for everyone. Publishing to a new
URL leaves two canvases drifting apart, which is the failure this whole directory
exists to prevent. `normalize.py` and `panel_frame.py` are one-shot
migrations kept for the record; running them again is harmless but unnecessary.
