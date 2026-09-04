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
| Cluster (virtual units; 1.70 panel px, 0.2123 mm) | 52 · 34 · 24 · 18 · 13 · 11 (+ `88`, boards only) | 8 wide, 6 narrow |
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
it; `gen_cluster.py` is the one place to check that they still agree. That ramp
is the six rungs from `52` down to `11`, and the app draws nothing else.

**The headline numeral is `88`, and it is `104` that is gone.** The Contour's own
ramp on the boards is `88 · 52 · 34 · 18` - four rungs, and 24 and 13 are not used
on the panel at all. `104` was chosen when the glass was a guess: at the estimated
25 cm it made 52 look illegal and a doubling look necessary. On the measured 320 mm
it is a 16 mm numeral, and 52 is a comfortable 36'. `88` is `1.69 × 52`, which
clears this page's own 1.2x rule with room, and it still reads at 61'. It is not
"exactly twice 52" and does not need to be: the rule is a visible step, not an
octave. `104` survives only on `gen_next.py`, which draws a concept the contest did
not pick, and it is not a proposal any more.

`88` is not on `InstrumentDensity.RAMP` either, for the same reason `104` never
was: the app does not draw this cluster yet, and a constant nothing reads is a
promise. It joins the ramp on the day the renderer draws it, in the same change,
or not at all. Until then this row still reads `52 · 34 · 24 · 18 · 13 · 11` for
the app and carries `88` in brackets for the boards.

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
| `ClusterContourStates.dc.html` | fourteen scenes as a column: first seconds, a traffic jam, calm, an acceleration, regeneration, the engine generating both ways it can be drawn, the engine forty seconds dead, standing on P, charging, a single null, link lost, an exception, and the missing ADB key |
| `ClusterContourPlan.dc.html` | the skeleton alone, over the three apertures and the cell grid, with every anchor measured - and, under the panel, the physical constants and the ramp they produce |

The boards were drawn before a line of the panel was written, shown to the owner,
redrawn twice against what he said, then roasted by an independent review
(`CRITIQUE.md`: three blockers, fifteen majors, twelve minors) and redrawn again
against that. Those four passes are the reason to read this section rather than
only the concept.

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

### Two guards, and they are the same number

The jury asked for 24 units between the hero's cap and the stock speedometer's
zone. The review found the shelves' headings standing 7 from that same boundary
and the band's limit labels 10 from the other one - the first things a boundary
nobody has photographed would cut off. Now `stockTop + 24` and `stockBottom − 24`
are two constants and everything hangs off one of them: the hero's cap top, both
shelves' cap tops, the engine box's top edge, and the lower edge of the band's
body, which clears its guard by 18. The plan board draws both in red.

That budget is what paid for the rest. The shelves lost their headings entirely -
five degree signs say "temperatures" without the word, and the trip's unit is
written once at a fixed anchor - and the band lost `100 кВт / 300 кВт`, which were
two 12' lines claiming a scale that a square root over two different spans is not.

### What the panel says, and in what order

**One heavy thing.** `INK` belongs to the hero and to the petal's figure and to
nothing else. Both corners and both shelves are `MUTED`; headings, captions and
units are `MUTED_DEEP`; `WARNING` and `DANGER` are the exception only. That is the
second half of the hierarchy the review was right to ask for: five equal 52s were
the owner's original complaint wearing a new suit, and size alone was not enough
to separate them.

**One lit thing, and it stands still.** The glow is centred on zero, its brightness
is `sqrt(|P| / span)` capped at 0.18 - exactly the band's own travel fraction - its
hue is the sign, and τ is 1.5 s. Riding the band's tip at 400 ms, it put a 73 mm
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
box's area, and a marker in front of `РЕКУПЕРАЦИЯ`, `ОТ ДВС` and `ГЕНЕРАЦИЯ` -
dot-sized, because saturated blue at 12' on black is the one colour ISO 15008 
names for small glyphs and thin rules.

**Units are case-sensitive.** `«БАТАРЕЯ · В»`, `«ДВС · об/мин»`, `«кВт·ч»` - a
tracked capital is a heading, a unit is not one, and a tracked heading does not get
to rewrite ГОСТ 8.417.

### Three histories, one rule each

**The petal** keeps three kilometres and always three kilometres: standing on P the
denominator does not change under the figure, only the tenth appears, because at
100 km/h a tenth moves three times a second. Thirty bars 0.65 mm wide were 0.9' at
750 mm - below the eye's resolution - so the history is a stepped line at 2 units
with a 30 % field, 8 units to a bucket, in a box 240 × 36 whose zero line is the
figure's own baseline. There is no dashed mean any more: the mean is the figure
standing next to it.

**The engine box** grows from the right, its width is the number of filled seconds,
and it is never drawn empty. It leaves when the last non-zero slot falls off the
left edge, which is 120 s of hysteresis with no timer of its own - the trace's
length *is* the timer - so a winter jam that restarts the engine every ninety
seconds never swaps the shelf back and forth. It occupies exactly the ink box of
the digits it replaces, which is what keeps it off the top guard by construction.

**The band's tail is gone.** Two memories on one instrument was one too many, and
the ten-second smear was drawn in a single colour across zero, so a sweep from −58
to +9 came out blue including the +9. The peak hold stays.

### The right shelf has three seats, always

`ИЗ БАТАРЕИ`, `● РЕКУПЕРАЦИЯ`, `● ОТ ДВС`, counted from the outside in, each in a
cell as wide as the wider of its caption and its field, and **a zero is drawn**, in
`MUTED_DEEP`, in its own seat. The last board packed the cells against the margin
and skipped zeros, so the first braking of a trip slid two figures sideways and the
engine's first start slid them again - a coordinate depending on data, which is the
one thing this concept exists to cure. The owner objected to an empty *graph*, not
to a zero.

### What the data still owes the panel

Two rules are written on the boards and in the generator's docstring because the
code will have to follow them, and the measurement that would settle them has not
been taken (`VERDICT.md`, check 3):

- while the engine runs the petal's figure is `MUTED` and its unit reads
  `кВт·ч/100 км · батарея`. `ConsumptionLog` integrates pack power alone, and
  nobody has logged whether `GENERATION_KW` is already inside `POWER_KW`;
- `РЕКУПЕРАЦИЯ` integrates only over intervals with the engine off. Under
  generation a negative pack flow is indistinguishable from braking, and the
  engine's share belongs in `ОТ ДВС`.

### The review, answered

One line per finding. "waits" means the answer is a measurement on the car, not a
drawing decision.

| | | |
| --- | --- | --- |
| B1 | consumption and balance undefined under the engine | closed as a data rule; the number waits for one log |
| B2 | vertical budget exhausted, headings 7 units from the boundary | closed - headings dropped, both guards at 24 |
| B3 | the P scene contradicts `TripEngine` | closed - the petal is always the last 3 km |
| M1 | the ladder stood on a guessed glass width | closed - measured, 320 mm at 750 mm |
| M2 | the hero under the stock speedometer, unit unreadable | closed - 88 with its unit at 34; the photograph still waits |
| M3 | 34 Light on black, a size the concept forbade | closed - 34 is legal at this distance, and Regular |
| M4 | five equal 52s | closed - colour carries the hierarchy, `INK` is the hero alone |
| M5 | alpha means seven things | closed - one rule: stale goes after 2 s, the caption stays |
| M6 | the glow travels | closed - centred on zero, brightness and hue only, τ 1.5 s |
| M7 | the engine box is born empty, the shelf flickers | closed - grows from the right, 120 s of self-timed hysteresis |
| M8 | the right shelf's coordinates depend on data | closed - three seats always, a zero is drawn |
| M9 | «просадка N В» lies on a motorway | closed - deleted |
| M10 | the band is labelled as a scale it is not | half closed - labels gone; the square root stays, the owner accepts it |
| M10 | available regeneration and the power ceiling | waits - no FID found for the BMS limits |
| M11 | small blue text and blue hairlines | closed - blue is a dot, the area is 50 % |
| M12 | colour hysteresis of 1 kW | closed - a 3 kW dead band, 3 kW of hysteresis |
| M13 | four required scenes missing | closed - jam, acceleration, engine dead 40 s, single null |
| M14 | Roboto Mono breaks numbers apart | closed - Roboto with `tnum`, measured |
| M15 | three histories are textures | closed - a stepped line and 2-unit runs |
| m1 | unit symbols set as tracked capitals | closed |
| m2 | a dim «ДВС» over an empty corner | closed - the corner is simply empty |
| m3 | two memories on the band | closed - the tail is gone |
| m4 | headings standing over emptiness for the first seconds | closed - a heading arrives with its first value |
| m5 | the petal's tenth moves three times a second | closed - integer on the move |
| m6 | the hero updates at 3 Hz | closed as a renderer rule: 2 Hz, ±0.5 kW |
| m7 | night by ephemeris, not by light | closed - the scene is removed; the dimmer waits for the car |
| m8 | an alert at 34 in a shelf of 52s | closed - every shelf figure is 34 now |
| m9 | generation drawn three times | closed - twice: the seam and the box |
| m10 | "104 reads peripherally" | closed - the claim is not made; the band is the ambient |
| m11 | the motors' order is learnable, not obvious | left standing, and named as such |
| m12 | lowercase captions | rejected - one house style with the head unit wins |

### The apertures, and everything else

`ClusterMapLayout`'s integer division is restated exactly - `2560 * 20 // 100 * 40
// 100 * 4 // 3` and the rest - so the clear band, the two corner quarter-ellipses
and the petal come out where the app already believes they are. Every other anchor
is derived from five decisions: one margin of `48`, the rhythm of `8`, the rungs,
the cap height, and the two guards. Each corner aperture holds a heading, one 52
figure and one 18 line and no more - it is 301 units wide at the top and narrows to
nothing by 160 - so both shelves stand below it, in the clear band's flanks, on one
pair of baselines with one anatomy: a 34 figure over an 18 word. The left cells
come out `96 · 176 · 112 · 96` and the right ones `96 · 168 · 128`, each as wide as
the wider of its own caption and its own payload, and the whole left shelf stops 61
units clear of the hero's field.

One thing to look at rather than measure: the petal's group - box, figure, unit -
is centred on the axis as a group, which is the same rule the hero's group follows.
But a 240-unit history cannot balance a 109-unit unit, so the petal's digits land
82 units right of the hero's, and two large numerals one above the other is exactly
where that is visible. The alternative is to centre the figure and let the group
hang asymmetrically. The boards draw the rule as written.

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
