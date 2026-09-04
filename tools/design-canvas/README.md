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
| `gen_contour.py` | emits the Contour - the cluster concept that won the 2026-09 contest - as the calm panel, fourteen scenes, and the plan with every number on it |
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
| Cluster (virtual units; 1.70 panel px, 0.2123 mm) | 88 · 52 · 34 · 24 · 18 · 13 · 11 | 8 |
| Head unit (pixels, 1:1) | 62 · 46 · 34 · 24 · 19 · 15 | see below |

`82` used to head that second row, here and in `gen_kit.py` and in `normalize.py`,
while `DenzaMetrics.Type.RUNGS` had six rungs from 62 down and no active board drew
it. Same shape as the cluster's headline numeral one level along, same answer: a
constant nothing reads is a promise, not a rung. It is off all three records.
`Battery.dc.html` still draws it and stays as it is - that board is retired
evidence, not a contract.

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
it; `gen_cluster.py` is the one place to check that they still agree, and since
2026-09-04 `ContourBoardContractTest` is what makes them. The narrow `COMPACT`
density is gone with the right-third composition nothing offered: there is one
cluster ramp now and one density on it.

**The headline numeral is `88`, and it is `104` that is gone.** The Contour's own
ramp on the boards is `88 · 52 · 34 · 18` - four rungs, and 24 and 13 are not used
on the panel at all. `104` was chosen when the glass was a guess: at the estimated
25 cm it made 52 look illegal and a doubling look necessary. On the measured 320 mm
it is a 16 mm numeral, and 52 is a comfortable 36'. `88` is `1.69 × 52`, which
clears this page's own 1.2x rule with room, and it still reads at 61'. It is not
"exactly twice 52" and does not need to be: the rule is a visible step, not an
octave. `104` survives only on `gen_next.py`, which draws a concept the contest did
not pick, and it is not a proposal any more.

`88` used to be on the boards and not on `InstrumentDensity.RAMP`, for the same
reason `104` never was: a constant nothing reads is a promise, and it joins the
ramp on the day the renderer draws it, in the same change, or not at all. That day
was 2026-09-04. It is on the ramp now, in the same commit that drew it, and the
row above no longer carries it in brackets.

The heading rung moved with it. `18` used to head a caption and `13` its own
title, on the reasoning that capitals read larger; at the measured distance a rung
under 18 is 9′, which is board furniture. `InstrumentDensity.title` is `18` now
and what separates a heading from a caption is weight and tracking, which is what
separates them typographically anyway.

The first draft of those boards ran at `58` and `19` beside the ramp's own `52`
and `18`, which is the drift the ramp exists to prevent: a difference you can
measure and cannot see. This page then claimed the two records agreed while the
headline numeral sat in one and not the other, which is the same failure one level
up - caught by the parallel session reading both, not by anything here.

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
| `ClusterContourStates.dc.html` | fourteen scenes as a column: first seconds, a traffic jam whose engine ran earlier and stopped long ago, calm, an acceleration, regeneration, the engine generating both ways it can be drawn, the engine forty seconds dead, standing on P, charging, a single null, link lost, an exception, and the missing ADB key |
| `ClusterContourPlan.dc.html` | the skeleton alone, over the three apertures and both cell grids, with every anchor measured - and, under the panel, the physical constants and the ramp they produce |

The panel exists now: `dev.denza.apps.feature.cluster.dashboard` draws these three
boards, `ContourPlan` is their arithmetic in Kotlin, and
`ContourBoardContractTest` joins the two records so neither can move alone. What
follows is why each element is what it is, and it is still the reason to read this
section rather than only the concept.

The boards were drawn before a line of the panel was written, shown to the owner,
redrawn twice against what he said, then roasted by an independent review
(`CRITIQUE.md`: three blockers, fifteen majors, twelve minors), redrawn again
against that, redrawn a fifth time when the owner looked at the scenes and found
both graphs flattened, redrawn a sixth when he asked what one of the numbers
meant, and a seventh so that every figure says which window it is true over.
Then the panel was built, put on a live bench, and watched moving - and the
eighth pass is what came back from that, the ninth from the bench run after it.
Those nine passes are the reason to read this section rather than only the
concept.

### The test every element now has to pass

The owner, on the fifth drawing: *"что означает 0,0 от ДВС, когда ДВС заглушен?"* -
and then the sentence that decided the sixth pass: if the question occurred to him,
the element is unclear to anybody. `0,0 ОТ ДВС` was drawn on purpose - three fixed
seats, a zero in its own seat so no figure would move when a value arrived - and
every decision behind it was defensible on its own. Together they printed an
accountant's way of saying "the engine did not run".

So the panel has one more rule, and it outranks the tidy ones: **is this element
understood at first glance, by somebody who has never seen the panel and has no
legend?** What it changed:

- the right shelf stopped being a ledger and became a phrase (below);
- **a zero is never drawn.** A quantity that did not happen this trip has no cell;
- the engine's corner gave up its third line and the generation figure went to its
  own word, under the box: `ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт`. A number in one place
  and its noun in another is the same defect as a caption naming another quantity,
  which is what the owner found on the very first drawing. That line did not
  survive the bench either - see the eighth pass below, where the words go and the
  sentence arrives;
- the petal dropped `· батарея`. While the engine runs the figure is `MUTED` and
  says nothing else: colour already says "not the main number", and the caveat was
  five characters of footnote at 12' on the one line read in a glance;
- `РАЗБРОС` became `РАЗБРОС ЯЧЕЕК`, because the spread of *what* is exactly the
  question that started the pass.

### A figure names the window it is true over

Four things still failed that test on the sixth drawing, and they failed it the
same way: **a number integrated over some interval that does not say which
interval gets read against the interval the reader has in mind**, which is almost
never the right one. The seventh pass is those four:

- **the petal reads `кВт·ч/100 км · за 3 км`.** Three kilometres had been the rule
  since the fourth pass and was drawn nowhere; a consumption figure with no window
  on it is read as the trip average, which is the one thing it has never been. The
  unit is where that belongs - already the line under the figure, already
  `MUTED_DEEP` - and it costs 74.7 units into a cut-out with 178 free to its right;
- **the sleeping engine's corner reads `ДВС · мин за поездку`** over the same 52.
  `ДВС · мин` alone was six minutes of *something*: this stop, this hour, this
  trip. The fifth pass wanted exactly these words and tried them on a third
  baseline, where the aperture left eight units; at the heading's own y 24 the
  corner ellipse is 298.1 wide, so 224.9 of heading has 25.2 to spare;
- **the trip's figure is the net that left the battery**, `tripNetKwh = ∫P dt` in
  the pack's own units, and the two cells beside it got verbs. What regeneration
  returned and what the engine generated are already out of the first figure, so
  `9,3` is what went for good over those 42 km - and three unsigned numbers under
  three nouns had been an invitation to add them up;
- **the kilometres lead the phrase**, `42 км · ЗА ПОЕЗДКУ`. The odometer's field
  holds three digits and the printed number is right-aligned inside it either way;
  trailing, that reserve stood between the `·` and the number, and 10.1 units of
  nothing after a separator reads as a value that failed to arrive. Leading, the
  same reserve is the caption's left margin.

Only one of the two verbs fits. `ВЕРНУЛА РЕКУПЕРАЦИЯ` measures 253.5 units against
`РЕКУПЕРАЦИЯ`'s 150.7, which puts the three seats of a car standing on P at 629.7
and their left edge 41 units *inside* the hero's own `кВт` - 65 more than the shelf
has anywhere. So the middle caption keeps `● РЕКУПЕРАЦИЯ` and its dot says what the
verb would have; `ДАЛ ДВС` is 94.4 against a payload of 116.1, so that cell was
already as wide as its own figure and the verb was free.

### The bench, and what a running panel said that a still could not

The first seven passes were drawings. The eighth is the first one drawn against
the panel *moving* - built, put on a live bench and watched - and everything that
came back was about the two graphs again. Not their size this time: what they were
saying.

- **`легенда непонятна`.** The engine box's `ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт` was a
  key to a picture with two runs in it, and a panel read at 90 km/h does not get to
  need a key. Both words are gone and so is the run one of them named: the box
  draws generation alone under one sentence, `● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН`,
  and the revolutions keep the number they already had in the corner - which is
  where they were being read anyway;
- **`сплющен`**, said of that same box a second time. Its height had not moved and
  could not: it is both rows of the shelf, with the guard above it and the
  sentence's caps below. What was flat was the *scale*. A square root to 100 kW put
  this car's ordinary 14 kW at a third of the box; linear to 30 kW with a clamp puts
  it at a half;
- **`ноль должен быть у цифры`.** The petal's box hung beside its figure on a ladder
  of its own - 56 units tall, zero four fifths down - and neither number meant
  anything to anything next to it. The zero line is the figure's own baseline now,
  spending rises through the digits' cap height and a return hangs under it the
  depth of a descender, so the box and the numeral are one typographic object;
- **`беспорядочно`**, of the same petal. One field crossing a zero line in one
  colour drew the same grey above and below it, and the blue rule that was meant to
  say "this came back" ran the whole width whether anything had come back or not.
  Spending is one continuous grey field that lies on the zero on a return bucket;
  the return is blue, one shape per run, with its own posts - and only where it
  happened.

Both boxes are steps of a fixed duration as a result: the petal's thirty buckets
are a hundred metres each and the engine's twenty-four are five seconds each, a bin
being the mean of the samples that arrived in it.

### The temperature row is five glyphs, and no words

The second bench pass, and it is about the row nobody had complained about. Three
figures under one `МОТОРЫ` never said **which motor is which** - `m11` in the
review, left standing and named as such for five drawings, on the argument that
three captions at 12′ cost more than the ambiguity. Naming them was then tried
properly, `ПЕРЕД / ЗАД Л / ЗАД П`, and the owner threw it out on the sound of it:
*«по-русски не звучат»*. A drawing of the car from above with one axle lit says the
same thing in one glance and in no language, so the whole row is glyphs.

| cell | glyph | what changes |
| --- | --- | --- |
| 1 | a battery | the case, the terminal, and one lit cell inside |
| 2 | the car from above | a bar across the **front** axle |
| 3 | the same car | half a bar on the **left** of the rear axle |
| 4 | the same car | half a bar on the **right** |
| 5 | a case with one period of alternating current in it | - |
| 6 | `РАЗБРОС ЯЧЕЕК`, the only word left in the row | appears at `WATCH` or `ALERT` |

Five rules, and every one of them is his:

- **all five or none.** *«если символы, то и батарея, и инвертор, и хорошие»* - half
  a row of pictures under half a row of words is worse than either. The first cut
  drew a dot inside a pill for the pack and left the other four as captions;
  *«беспомощно»*, and it was;
- **24 units, not the caption's 18.** *«при 18 единицах жучков не видно»* - 2.7 mm
  of glass for a shape with four wheels in it. At 24 they are 5.1 mm, and they
  stand **on** the caption baseline rather than hanging from it, so neither of the
  shelf's two rows moved and ten units still separate a glyph's top from the
  figures' own baseline;
- **one outline, one component.** The outline is `MUTED`, the same as a caption
  was; the component inside is `INK`, one step brighter than the figure above it,
  and it takes `WARNING`/`DANGER` **with** the figure on an exception - so a hot
  cell lights as one object rather than as a red number beside a grey picture. That
  is the panel's one exception to "`INK` is the hero alone", and it is what makes
  the mark findable at all: an outline round a grey blob reads as one grey shape;
- **the motor is the motor, not the wheel it drives.** *«точно мотор с колёсами не
  путаешь?»* - said of a first drawing that filled the wheels each motor drives.
  All four wheels are hollow in all three cars, and they are the one thing in the
  family drawn at 1.6 rather than the 2.5 everything carrying data uses: a wheel is
  furniture saying "this is a car from above" and the block on the axle is the
  reading;
- **every proportion is in caption units** and multiplied up by `k = 24/18`, so the
  family scales with its height alone and nothing in it is typed.

What it cost and what it bought: five cells of **50.9** where `БАТАРЕЯ · МОТОРЫ ·
ИНВЕРТОР` were `91.9 · 143.4 · 110.0`, so the row is 58 units narrower **even with
each cell carrying its own `°`** - the three motors shared one sign from the sixth
pass to the eighth because `РАЗБРОС ЯЧЕЕК` needed exactly those 25 units, and the
captions paid them back twice over. The left shelf now stops **270** clear of the
hero's field, or **86** with the exception cell up. And a word in that row now
*means* the pack is misbehaving, because it is the only word in it.

This row breaks the house icon rule on purpose. `DenzaIcons` is one optical weight
throughout - `ICON_WEIGHT` 2.0 in a 24 viewport - because those are labels on
tiles. A glyph here is half of a reading, and inside one mark the case, the lit
component and the wheels are three different kinds of thing; the family says which
is which with two weights, and `audit.py` reports the new `1.6` in `STROKES` for
that reason rather than by accident.

### And the engine's sentence closes up when the engine stops

The same bench run, one line along. The generation figure lives in a two-digit
reserve so that 9 kW and 14 kW start the phrase in the same place; when the engine
stops the figure and its unit are removed by the staleness rule, and the eighth
pass left the reserve standing - `● ⎵⎵ В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН` for the whole
two minutes the box outlives the engine. 22 units of nothing after a dot reads as a
value that failed to arrive, which is the same defect the seventh pass moved the
odometer's reserve to fix.

With no figure there is no field: the phrase is assembled without it and the dot
closes up against the words. **One shift per engine stop, not a jitter** - what a
reserve buys is stillness while a *number* changes, and by then there is no number
left to change. The words themselves never move, in either state. `ContourPlan`
carries both anchors, `legendMarkX` and `legendMarkQuietX`, and the states board
draws both scenes.

### The glass is measured now

Every ergonomic claim on the first three boards stood on the brief's "порядка
25 см (оценка)", which is what the review's M1 found. The owner took a tape to the
car on 2026-09-04: **the active area of the cluster glass is 320 mm wide and his
eyes sit 750 mm from it.** Both are constants in the generator, and the whole
ladder falls out of them - one board unit is 0.2123 mm, a Roboto cap is 0.71 em,
one arc minute at 750 mm is 0.2182 mm, so a cap subtends `size × 0.691` minutes.

| rung | mm | arc min | where |
| --- | --- | --- | --- |
| 88 | 13.26 | 61 | the hero |
| 52 | 7.84 | 36 | the corners, the petal - ISO 15008 calls 30' comfortable |
| 34 | 5.12 | 23 | both shelves - legal for a deliberate glance, the floor is 20' |
| 18 | 2.71 | 12 | headings, captions, units: furniture |
| 13 | 1.96 | 9 | board furniture only; never on the car |

So the cluster's ramp **on these boards** is `88 · 52 · 34 · 18`, and 24 and 13 are
not used on the panel at all. What changed with the tape is not one number but the
argument: 34 is legal rather than illegal, so temperatures can be a shelf instead
of a compromise; 52 is comfortable rather than borderline, so the corners need no
promotion; and the hero does not need to be twice 52, so 104 loses its reason to
exist. See "The two ramps" for what that means for `InstrumentDensity.RAMP`.

### Numbers are Roboto with `tnum`, not Roboto Mono

`«12 , 4»`, `«28 °»`, `«2 : 15»`. A monospaced face gives a comma, a degree and a
colon the same 0.6 em cell it gives a digit, so every number on the third drawing
fell apart into groups. Measured in headless Chrome, in the faces and at the sizes
these boards set them:

| | advance |
| --- | --- |
| Roboto Regular digit | 0.5620 (29.2246 at 52, 19.1094 at 34) |
| Roboto Light digit | 0.5547 (48.8125 at 88) - the hero is the only Light thing here |
| comma | 0.1969 · colon 0.2422 · degree 0.3736 |
| Roboto Mono, any glyph | 0.6000 |

The measurement worth keeping: **`0`, `1` and `4` all advance identically without
`tnum`.** Roboto's own figures are already tabular, so the feature buys nothing in
this face and is set anyway, because a `Paint` on the car may resolve something
else and a reserve field is a contract rather than a hope. The rule the mono face
was there to serve survives intact: no coordinate depends on data, every number
lives in a field sized by its maximum digit count times a measured advance, and a
unit hangs off the field rather than off the string.

The seventh pass re-measured every string it needed and found the sixth pass's
caption table did not reproduce: **all ten of its captions came back different**,
and so did `кВт·ч` and `км` - from 3.02 units too wide (`ОБОРОТЫ ·`) to 0.89 too
narrow (`БАТАРЕЯ`), with no consistent sign, which is a table typed from more than
one run rather than one systematic error. Every *unit* and every corner heading
reproduced to the digit, which is why no anchor moved and only the cells did: the
left shelf by two units. What is in the generator now is one
`getComputedTextLength()` run, checked at 18, 180 and 360 px and agreeing to 0.01,
so those numbers are the face's own advances rather than a rounding of one size.

### Two guards, and they are the same number

The jury asked for 24 units between the hero's cap and the stock speedometer's
zone. The review found the shelves' headings standing 7 from that same boundary
and the band's limit labels 10 from the other one - the first things a boundary
nobody has photographed would cut off. Now `stockTop + 24` and `stockBottom − 24`
are two constants and everything hangs off one of them: the hero's cap top, both
shelves' cap tops, the engine box's top edge, and the lower edge of the band's
body, which clears its guard by 18. The plan board draws both in red.

That budget is what paid for the rest. The shelves lost their headings entirely -
one degree sign per cell says "temperatures" without the word, and the trip's unit
went to the cell it belongs to rather than a heading over the row - and the band
lost `100 кВт / 300 кВт`, which were two 12' lines claiming a scale that a square
root over two different spans is not.

### What the panel says, and in what order

**One heavy thing.** `INK` belongs to the hero and to the petal's figure and to
nothing else. Both corners and both shelves are `MUTED`; headings, captions and
units are `MUTED_DEEP`; `WARNING` and `DANGER` are the exception only. That is the
second half of the hierarchy the review was right to ask for: five equal 52s were
the owner's original complaint wearing a new suit, and size alone was not enough
to separate them.

**One lit thing, and it stands still.** The glow is centred on zero, its hue is the
sign, τ is 1.5 s, and its brightness is `0.18·sqrt(|P| / 120 kW)`, **saturated at
120 kW**. It used the band's own travel fraction until the fifth pass, and that
gave one alpha two meanings by direction - 42 kW of braking outshone 100 kW of
pulling - while leaving calm driving at 0.06 of a scale that only fills at the
car's absolute limit. One span, the pedal's own working range, puts calm at 0.10
and saturates an acceleration. Riding the band's tip at 400 ms, it put a 73 mm
pool through 50-100 mm of travel every time the pedal moved in a jam, which is
precisely what peripheral vision is built to catch.

**A dead band of 3 kW.** Inside it the hero and the band's body are `MUTED` and
carry no colour at all, and the colour changes with 3 kW of hysteresis. On a coast
`POWER_KW` swings ±2 kW and a 13 mm numeral was flickering between ink and blue.

**Alpha is not a state channel.** It had seven meanings - night, jam, sleeping
engine, link loss, single null, area fill, history fade - and a driver cannot tell
"dim because it is dark" from "dim because the bus died". One rule now: **a stale
value is removed after two seconds and its caption stays.** Link loss is that rule
applied to every value at once, a single null is that rule applied to one, and
neither dims anything; the states board draws both, at full brightness, with the
skeleton and the words still standing. The night scene is gone with the channel -
the cluster's own dimmer already darkens our window, and whether it does is a
measurement on the car rather than a board. What still uses alpha: the glow, the
two history fills, the peak hold.

**Blue is a dot.** `RETURN` is the band's body, the generation seam, the engine
box's area, the petal's return runs, and a marker at the head of `РЕКУПЕРАЦИЯ` and
of the engine's own sentence - the places the car gives something back - dot-sized,
because saturated blue at 12' on black is the one colour ISO 15008 names for small
glyphs and thin rules.

**Units are case-sensitive.** `«БАТАРЕЯ · В»`, `«ДВС · об/мин»`, `«кВт·ч»`, `«км»` -
a tracked capital is a heading, a unit is not one, and a tracked heading does not
get to rewrite ГОСТ 8.417. The odometer's own figure inside `42 км · ЗА ПОЕЗДКУ` is
set in the caption's face for the same reason: it is a number living in a phrase
rather than a reading of its own.

### Three histories, one rule each

Both boxes were redrawn in the fifth pass. The owner on the fourth: *"Сценарии
выглядят гораздо лучше... Но графики очень сильно сплющены по вертикали и очень
слабо читаются."* Each had been given the ink box of the digits it stood beside -
36 units in the petal, 24 on the shelf, which is 7.6 mm and 5 mm of glass for a
shape with thirty steps or two curves in it.

**The petal** keeps three kilometres and always three kilometres - and since the
seventh pass its unit says so, `кВт·ч/100 км · за 3 км`. Standing on P the
denominator does not change under the figure, only the tenth appears, because at
100 km/h a tenth moves three times a second. Thirty bars 0.65 mm wide were 0.9' at
750 mm - below the eye's resolution - so the history is a stepped line beside the
figure, and since the fifth pass that line is `INK` at 70 % and 2.5 units thick
over a `MUTED_DEEP` field at 55 %, where it was a 2-unit `MUTED` line over 30 %
and read as a texture. It is **232 wide**, because that is what the petal's own
cut-out leaves at the box's lower left corner once the 8-unit guard is taken.

**Its zero line is the figure's own baseline, and that decides its height.** The
owner, on the built panel: *«ноль должен быть у цифры»*. The fifth pass had given
the box 56 units and a zero line four fifths down, and both numbers were the box's
alone - a ladder standing next to a 52 and agreeing with nothing on it. The three
lines that bound the history are the three lines of the numeral beside it now: the
cap top at 347.08, the baseline at 384, and a descender's depth under it at 397.
The box is 49.92 tall because that is what a 52 occupies, and there is nothing left
to choose. Its scale is a **fixed ladder, 0…30 kW·h/100 km up the cap and 0…10 back
down the descender**, both clamped - 30 rather than the fifth pass's 40, because
what set 40 was the zero line at four fifths and the zero line is a baseline now.
A bucket does not change height because a *different* bucket changed value, which
is what the ladder buys: on the states board the traffic jam's history is visibly
taller than calm driving's, which under the old autoscale it was not. There is no
dashed mean: the mean is the figure standing next to it.

**Two series, and the blue one is only where it happened.** Spending is one
continuous `MUTED_DEEP` field under its `INK` edge, drawn across all thirty
buckets: on a bucket that gave energy back it lies on the zero line, because what
was spent there is nothing. The return is `RETURN` at 50 % with a `RETURN_INK`
edge, one shape per run of return buckets, standing on the zero line on its own
posts, and nothing blue is drawn along that line anywhere else. Until the eighth
pass it was one field crossing the zero in a single colour with a blue rule running
the whole width whether anything had come back or not - *«беспорядочно»*, which is
two quantities in one silhouette and a colour claiming a third.

**The petal's figure centres on the axis** and the box hangs off it. Two digits,
right-aligned on a fixed anchor, so the tenth that appears on P grows the field
leftward and moves neither the unit nor the box; the box hangs off the field's
widest reserve at 24, which is asymmetric on purpose. The accident worth keeping:
a two-digit 52 centred on the axis ends at 783.00 and the hero's three-digit field
ends at 783.04, so the two figures share a right edge and `«кВт»` and
`«кВт·ч/100 км»` start on the same x.

**The engine box** grows from the right and is never drawn empty. It leaves when
the last live slot falls off the left edge, which is 120 s of hysteresis with no
timer of its own - the trace's length *is* the timer - so a winter jam that
restarts the engine every ninety seconds never swaps the shelf back and forth. It
takes **both rows of the shelf**, 50.7 units from the top guard down to a rhythm
step above its caption's caps, and that caption sits below the box on the band's
own 24-unit guard, so the swap moves no neighbour's baseline in either direction.

**One quantity, one sentence** - the eighth pass, and the owner's word for what was
there before was *«легенда непонятна»*. `ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт` was a key to
a picture with two runs in it, and a display read at 90 km/h does not get to need a
key. The revolutions' line is gone to the corner where the same number was already
printed, and what is left is generation as a `RETURN` area at 55 % with a 1.8
`RETURN` edge, under **`● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН`**: what the shape is,
what it is worth now, and how far back it goes, in that order. Neither `ГЕНЕРАЦИЯ`
nor `ОБОРОТЫ` is a word on this panel any more.

The sentence is laid out right to left off the shelf's own edge - the window, then
`кВт`, then the figure in a two-digit reserve, then the dot - so 9 kW and 14 kW
start it in the same place, and when the engine stops the figure and its unit leave
together while the words stay. It is the odometer's arrangement inside
`42 км · ЗА ПОЕЗДКУ`, one shelf along. If the face in use makes the phrase plus one
guard wider than the box it stands under, the window shortens to `· 2 МИН`; nothing
else in it may go.

**The area is linear to 30 kW, clamped**, and 30 is what this generator does rather
than a span borrowed from the band. That is the second half of the same verdict -
*«сплющен»*, said of a box whose height had already taken every unit there is
between the two guards. At the 14 kW this car ordinarily returns, a square root over
100 kW filled a third of the box; linear over 30 fills a half. What a root buys is
resolution near zero, and near zero this quantity is off.

**Both boxes are steps of a fixed duration.** The petal's thirty buckets are a
hundred metres each, and the engine's twenty-four are five seconds each, averaged
over the samples that arrived - a bin nothing answered in breaks the area rather
than being drawn through. The per-second line the box used to carry was 120 points
across 526 units, 4.4 apart, which is 0.9 mm of glass for one sample of a quantity
that moves on the scale of a traffic light.

**The band's tail is gone.** Two memories on one instrument was one too many, and
the ten-second smear was drawn in a single colour across zero, so a sweep from −58
to +9 came out blue including the +9. The peak hold stays.

### The right shelf is a phrase

One cell on the move: **`9,3 кВт·ч` over `42 км · ЗА ПОЕЗДКУ`** - a figure with
its own unit against it, and under it what the figure is *of*, with the kilometres
that make it a sentence and lead it. A second cell, `1,1 кВт·ч` over `ДАЛ ДВС`,
only if the engine actually ran this trip. Nothing else, and never a zero.

**The first figure is the net that left the battery** - `tripNetKwh = ∫P dt` in the
pack's own units. What regeneration returned and what the engine generated are
already out of it, which is what the other two cells' verbs are there to say: they
are what came back, not what adds. Until the seventh pass all three were nouns and
all three were unsigned, and the panel was inviting a sum that names nothing.

Standing on P the same anatomy pays out in full - `42 км · ЗА ПОЕЗДКУ`,
`● РЕКУПЕРАЦИЯ`, `ДАЛ ДВС` - because a car that is not moving is the one place
where reading three numbers costs nothing. **That is where regeneration lives now:
on P, and on P only.** It is a number you look at when the trip is over, not one
you read at 90 km/h, and the two hundred units it used to hold on the move were
what made the row a row. Its caption is the one that could not take a verb: the
dot in front of the noun does that work instead, for 14 units against 102.9.

Seats are counted right to left from the shelf's own edge and the first is always
against it, so the second appearing moves nothing. They are per state rather than
per panel: standing still adds a seat between the two, which is a gear change and
not a value arriving. The unit is written once *per cell* now, not once per row -
a shared unit at the end of a row is what makes three numbers read as a table, and
the table was the unreadable part.

While the engine's own box is up the shelf is the box, unchanged from the fourth
pass: the box leaves 120 s after the last live sample, so the phrase comes back
once rather than once per engine cycle.

### What the data still owes the panel

Two rules are written on the boards and in the generator's docstring because the
code will have to follow them, and the measurement that would settle them has not
been taken (`VERDICT.md`, check 3):

- while the engine runs the petal's figure is `MUTED` and carries no caveat.
  `ConsumptionLog` integrates pack power alone, and nobody has logged whether
  `GENERATION_KW` is already inside `POWER_KW`; until somebody does, that figure is
  the battery's alone and colour is what says so;
- `РЕКУПЕРАЦИЯ` integrates only over intervals with the engine off. Under
  generation a negative pack flow is indistinguishable from braking, and the
  engine's share belongs in `ДАЛ ДВС`. Both are subtracted from the net the first
  cell prints, so whichever way that log lands, the trip's own figure does not
  double-count.

### The review, answered

One line per finding. "waits" means the answer is a measurement on the car, not a
drawing decision.

| | | |
| --- | --- | --- |
| B1 | consumption and balance undefined under the engine | closed as a data rule; the number waits for one log |
| B2 | vertical budget exhausted, headings 7 units from the boundary | closed - headings dropped, both guards at 24 |
| B3 | the P scene contradicts `TripEngine` | closed - the petal is always the last 3 km, and since the seventh pass its unit says so |
| M1 | the ladder stood on a guessed glass width | closed - measured, 320 mm at 750 mm |
| M2 | the hero under the stock speedometer, unit unreadable | closed - 88 with its unit at 34; the photograph still waits |
| M3 | 34 Light on black, a size the concept forbade | closed - 34 is legal at this distance, and Regular |
| M4 | five equal 52s | closed - colour carries the hierarchy, `INK` is the hero alone |
| M5 | alpha means seven things | closed - one rule: stale goes after 2 s, the caption stays |
| M6 | the glow travels | closed - centred on zero, brightness and hue only, τ 1.5 s; the fifth pass gave it its own 120 kW span |
| M7 | the engine box is born empty, the shelf flickers | closed - grows from the right, 120 s of self-timed hysteresis |
| M8 | the right shelf's coordinates depend on data | closed - seats counted from the shelf's edge; the zero that came with it was undone by the sixth pass, and the odometer's reserve moved to the caption's left edge in the seventh |
| M9 | «просадка N В» lies on a motorway | closed - deleted |
| M10 | the band is labelled as a scale it is not | half closed - labels gone; the square root stays, the owner accepts it |
| M10 | available regeneration and the power ceiling | waits - no FID found for the BMS limits |
| M11 | small blue text and blue hairlines | closed - blue is a dot at the head of `РЕКУПЕРАЦИЯ` and of the engine's sentence, and the areas are 55 % and 50 % |
| M12 | colour hysteresis of 1 kW | closed - a 3 kW dead band, 3 kW of hysteresis |
| M13 | four required scenes missing | closed - jam, acceleration, engine dead 40 s, single null |
| M14 | Roboto Mono breaks numbers apart | closed - Roboto with `tnum`, measured, and re-measured in the seventh pass when the caption table would not reproduce |
| M15 | three histories are textures | closed three times - a stepped line, then 2.5-unit `INK` runs in boxes of 56 and 51, then two boxes of fixed-duration steps on scales that fill them |
| m1 | unit symbols set as tracked capitals | closed |
| m2 | a dim «ДВС» over an empty corner | closed - the corner is simply empty |
| m3 | two memories on the band | closed - the tail is gone |
| m4 | headings standing over emptiness for the first seconds | closed - a heading arrives with its first value |
| m5 | the petal's tenth moves three times a second | closed - integer on the move |
| m6 | the hero updates at 3 Hz | closed as a renderer rule: 2 Hz, ±0.5 kW |
| m7 | night by ephemeris, not by light | closed - the scene is removed; the dimmer waits for the car |
| m8 | an alert at 34 in a shelf of 52s | closed - every shelf figure is 34 now |
| m9 | generation drawn three times | closed - twice: the seam and the box, with the figure inside the box's own sentence |
| m10 | "104 reads peripherally" | closed - the claim is not made; the band is the ambient |
| m11 | the motors' order is learnable, not obvious | closed by the ninth pass, and with a picture rather than a caption: five cells, five glyphs, three cars differing by which axle is lit |
| m12 | lowercase captions | rejected - one house style with the head unit wins |

### The apertures, and everything else

`ClusterMapLayout`'s integer division is restated exactly - `2560 * 20 // 100 * 40
// 100 * 4 // 3` and the rest - so the clear band, the two corner quarter-ellipses
and the petal come out where the app already believes they are. Every other anchor
is derived from five decisions: one margin of `48`, the rhythm of `8`, the rungs,
the cap height, and the two guards. **Each corner holds a heading and one 52 figure,
and that is all it holds** - the aperture is 301 units wide at the top and narrows
to nothing by 160, and the third baseline it used to allow is gone with the line
that stood on it. An engine that ran and stopped reads `ДВС · мин за поездку` over
`6` - 224.9 units of heading in the 250.1 the aperture leaves at y 24, clear by
25.2; one that is running reads `ДВС · об/мин` over `1780`; one that never started
leaves the corner empty.

Both shelves stand below the apertures, in the clear band's flanks, on one pair of
baselines with one anatomy: a 34 figure over the thing that names it - an 18 word
on the right, a 24-unit glyph on the left. **A cell is exactly as wide as the wider
of its payload and that** - since the sixth pass the width is no longer rounded up
to the rhythm, because `РАЗБРОС ЯЧЕЕК` needed the 19.5 units of air that rounding
was spending across four cells, and a rounded cell was quantising a distance nobody
can see while the clearance to the hero's field is one the reader would meet the
moment a three-digit power arrived. The left cells come out `50.9` five times, plus
`167.7` when the exception is up, and stop **270** clear of the hero's field, or
**86** with the exception; the right seats are `214.0 · 164.7 · 116.1` on P and
`214.0 · 116.1` on the move, and stop **61.8** clear of the hero's unit.

One thing to look at rather than measure: the petal's *figure* centres on the axis
and its box hangs off the field's widest reserve, so the group is asymmetric on
purpose - a 232-unit history cannot balance a 109-unit unit, and centring the whole
group instead put the panel's midpoint on the box's back and the digits 82 units
right of the hero's. The boards draw the figure on the axis; the two large numerals
one above the other are where either choice is visible.

## The boards and the code are joined

A board and the screen it designs are two records of one decision, and for a
while nothing compared them. `audit.py` measures the boards against themselves,
`DenzaMetricsTest` measures the ladders against themselves, and the only thing
joining the two was somebody remembering. It did not hold: the first cut of the
head-unit screen carried every number off `Main.dc.html` and looked nothing like
it, because the board hangs a tile's words off the bottom edge and the code
stacked them from the top.

So three unit tests in `:denza-apps` parse the boards at test time and assert the
Kotlin matches:

| | |
| --- | --- |
| `MainBoardContractTest` | reads `Main.dc.html` - tile height, padding, radius, the `space-between` that hangs the words apart, both text styles with their leading, icon size and stroke, grid columns and gap, page margins |
| `SpectrumBoardContractTest` | reads the analyser out of the same board - band count, bar width fraction, that the columns add up to the field they are drawn in, peak height, corner radius, gradient stop, reflection crop and opacity, scanline pitch |
| `ContourBoardContractTest` | reads `ClusterContour.dc.html`, `ClusterContourStates.dc.html`, `ClusterContourPlan.dc.html` **and `gen_contour.py` itself** against `ContourPlan` - the four rungs with their weights and tracking, the panel, the band's hairline, body and zero mark, the glow's centre, radii and its `0.18·√(P/120)`, the hero's baseline and its field, both corner baselines, both shelf baselines, all six temperature cells with the box each glyph stands in, every rectangle the five glyphs are drawn from - the pack's case, terminal and lit cell, each car's body, four hollow wheels and one block, the inverter's case and the wave inside it - all three trip seats on P and the pair on the move, both history boxes with their scales and their steps, the engine's sentence with every anchor in it including both places its dot stands, that the petal's blue is one patch on the one run of return buckets, both guards drawn in red, and the measured advances every one of those cells is sized from |

The third row reads the generator as well as the boards because a caption is a
coordinate on the cluster: a cell there is exactly as wide as the wider of its
caption and its payload, so `W_CAPTION` in `gen_contour.py` and the strings in
`ContourReadout` are one record in two files. Coordinates are compared to `0.05`,
which is the tenth the boards are written to; the advances get two per cent,
because Chrome's Roboto and the car's Roboto are two fonts with one name and a
cell *should* follow the face it is actually set in. What must not drift is the
arithmetic between them, and that is everything else the row checks. Two mutations
of one constant each - the petal's box by one rhythm step, the guard by half a
step - brought down fourteen of its cases.

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
carries the same value, so the records meet: this paragraph said it did not for a
wave after it already did. The obvious-looking `#8A929C` does not work: at 5.59 it
is brighter than the ink above it.

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
