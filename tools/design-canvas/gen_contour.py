#!/usr/bin/env python3
"""
Emit the Contour cluster artboards from the constants the app draws with.

The Contour won the 2026-09 cluster contest (`docs/cluster-contest-2026-09/`).
Three drawings went to the owner, an independent review roasted the third
(`CRITIQUE.md`, blockers B1-B3, majors M1-M15, minors m1-m12), the fourth was the
answer to that review point by point, the fifth gave both graphs the height they
were missing, the sixth turned the right shelf from a ledger into a phrase, the
seventh made every figure name the window it is true over, the eighth was the
first one drawn against the panel *running* - and this is the ninth, which is the
second.

**The ninth pass: the temperature row says which motor.** The owner, at the bench
again, on the row he had not complained about before:

1. *«точно мотор с колёсами не путаешь?»* - and before that, the thing three
   figures under one word could never answer: **which of the three motors is
   which.** `ПЕРЕД / ЗАД Л / ЗАД П` was tried and thrown out in the same breath -
   «по-русски не звучат» - so the row is **five cells and no words at all**: a
   battery, three cars each with one motor lit, an inverter, each 24 units tall
   on the caption baseline under its own two-digit figure. A drawing of the car
   from above says «задний левый» in one glance and in no language;
2. *«беспомощно»*, of the first battery drawn - a dot inside a pill. A battery is
   an outline with a terminal and something inside it, and the something is the
   part that carries the reading's colour;
3. *«если символы, то и батарея, и инвертор, и хорошие»*. Half a row of pictures
   and half a row of words is worse than either, so the family is complete: the
   pack, the three motors and the inverter are one set of five, drawn with one
   stroke (`DATA_LINE`, the weight nothing carrying data goes under), one outline
   colour (`MUTED`) and one lit component (`INK`, or the exception's own colour
   when that cell is hot);
4. *«при 18 единицах жучков не видно»*. They were drawn at the caption's own size
   first, which is 2.7 mm of glass for a shape with four wheels in it. **24 units
   - 5.1 mm** - is what a glyph gets, and it stands *on* the caption baseline
   rather than hanging from it, so nothing else on the shelf moved;
5. and the motor is **the motor, not the wheel it drives**. Four hollow wheels
   stand off the body in every one of the three; what changes is a filled block on
   an axle - a bar across the front one, half a bar on the left or right of the
   rear one. The wheels are furniture and are never filled.

The one word left in that row is the exception's, `РАЗБРОС ЯЧЕЕК`, which appears
only when the pack is misbehaving - so a word in the temperature row now *means*
something is wrong, which is the second thing the glyphs bought.

**And the engine's sentence loses its hole when the engine stops.** The figure
lives in a two-digit reserve so that 9 kW and 14 kW start the phrase in the same
place; when the engine stops the figure is removed by the staleness rule and the
reserve was staying behind as 22 units of nothing between the dot and the words -
`● ⎵⎵ В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН`. With no figure there is no field: the dot
closes up against the words. That is one shift per engine stop, not a jitter -
the reserve is what keeps the phrase still while a *number* changes, and there is
no number left to change.

**The eighth pass: the owner watched it move.** The panel was built, put on a
live bench and looked at, and what came back was about the two graphs again -
not their size this time, but what they were saying:

1. *«легенда непонятна»*. `ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт` was a key to a picture
   with two lines in it, and a key is what a panel with no legend is not allowed
   to need. Both words are gone from the panel, and so is the line one of them
   named: **the engine's box draws generation and nothing else**, under one
   sentence - `● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН` - which says what the shape
   is, what it is worth right now, and how far back it goes, in that order. The
   revolutions keep the number they already have in the corner, `ДВС · об/мин`
   over `1780`, which is where a driver was reading them anyway;
2. *«сплющен»*, said of the same box a second time. The height did not change and
   could not: it is both rows of the shelf and the legend's caps are under it.
   What changed is the scale. A square root to 100 kW put this car's ordinary
   14 kW at a third of the box; **linear to 30 kW with a clamp** puts it at a
   half, and 30 kW is the top of what this generator does rather than a ceiling
   borrowed from the band;
3. *«ноль должен быть у цифры»*. The petal's box hung beside its figure on a
   ladder of its own, with a zero line four fifths down that meant nothing to
   anything next to it. **The zero line is the figure's own baseline now**:
   spending rises through the digits' cap height, a return hangs under it the
   depth of a descender, and the box and the numeral are one typographic object
   rather than two things that happen to be adjacent;
4. *«беспорядочно»*, of the same petal. One field crossing a zero line in one
   colour drew the same grey above and below it, and the blue rule that was
   supposed to say "this came back" ran the whole width whether anything came
   back or not. **Spending is one continuous grey field that lies on the zero on
   a return bucket; the return is blue, and only where it happened** - one shape
   per run of return buckets, with its own posts standing on the zero line.

Both graphs are steps of a fixed duration now, which is the other half of "what
is this saying": the petal's thirty buckets are a hundred metres each, and the
engine's twenty-four are five seconds each, averaged over the samples that
arrived. A per-second line through a two-minute box was 120 points inside 526
units - 4.4 units apart, which is 0.9 mm of glass for a sample.

**The sixth pass, and the question that caused it.** The owner, looking at the
fifth drawing: «что означает 0,0 от ДВС, когда ДВС заглушен?» - and then the part
that decides everything after it: раз вопрос возник у него, элемент непонятен
любому. `0,0 ОТ ДВС` was an accountant's way of saying "the engine did not run",
and a panel read at 750 mm in traffic does not get to make its reader translate.
So the panel has one rule that outranks the tidy ones: **is this understood at
first glance, by somebody who has never seen it and has no legend?** The sixth
pass answered it for the shelf - one cell on the move, three on P, a zero never
drawn, the generation figure moved to its own word under the box, the petal's
`«· батарея»` dropped for colour, `«РАЗБРОС»` grown into `«РАЗБРОС ЯЧЕЕК»`.

**The seventh pass: a figure names the window it is true over.** Four things
still failed that test, and they failed it in one way. A number integrated over
some interval that does not say which interval gets read against the interval the
reader has in mind, and that is almost never the right one.

1. the petal reads `«кВт·ч/100 км · за 3 км»`. Three kilometres was a rule written
   in this file and drawn nowhere: a consumption figure with no window on it is
   read as the trip average, which is the one thing it has never been. The unit is
   the place to say so - it is already the line under the figure, it is already
   `MUTED_DEEP`, and it costs 74.7 units into a cut-out that had 178 to spare;
2. the sleeping engine's corner reads `«ДВС · мин за поездку»` over its 52. The
   fifth pass wanted exactly those words and tried to put them on a third baseline,
   where the aperture left eight units. In the heading the aperture leaves 25.2,
   because a heading sits at y 24 where the corner ellipse is still 298 wide;
3. the trip's own figure is the **net** that left the battery - `tripNetKwh =
   ∫P dt` in the pack's own units - and the two cells beside it get verbs:
   `«ВЕРНУЛА РЕКУПЕРАЦИЯ»`, `«ДАЛ ДВС»`. Three unsigned numbers under three nouns
   is an invitation to add them up, and their sum is not a quantity. What the
   verbs say is the direction the nouns were missing: those two are what came
   back, and they are already inside the first figure rather than beside it;
4. the kilometres lead the phrase - `«42 км · ЗА ПОЕЗДКУ»`. With the odometer last
   its reserve for a third digit sat between the `«·»` and the number, and 10.1
   units of nothing after a separator reads as a value that failed to arrive.
   Leading, the same reserve is at the caption's left edge, where it is margin.

**What the third edit cost, and what it could not buy.** Both verbs were asked for
and only one fits. `«ВЕРНУЛА РЕКУПЕРАЦИЯ»` measures 253.5 units against
`«РЕКУПЕРАЦИЯ»`'s 150.7, which puts the three seats of a car standing on P at
629.7 and their left edge 41 units *inside* the hero's own `«кВт»`. The cell would
have to lose 65 units it does not have, so the middle caption stays `«● РЕКУПЕРАЦИЯ»`
and the dot keeps saying what the verb would have: at 164.7 the shelf ends 61.8
clear of the hero's unit. `«ДАЛ ДВС»` is 94.4 against a payload of 116.1, so that
verb is free - the cell was already as wide as its own figure.

**Every string here was measured again, and this time they reproduce.** The sixth
pass's caption table does not. All ten of its captions come back different in the
faces and at the sizes these boards set, and so do `«кВт·ч»` and `«км»` - from 3.02
units too wide (`«ОБОРОТЫ ·»`) to 0.89 too narrow (`«БАТАРЕЯ»`), with no consistent
sign, which is a table typed from more than one run rather than one systematic
error. What did reproduce to the digit is every *unit* and every corner heading,
which is why no anchor on the panel moved: only the cells did, and the left shelf
by 2. `W_CAPTION`, `W_KWH` and `W_KM` are one `getComputedTextLength()` run of
2026-09-04, checked at 18, 180 and 360 px and agreeing to 0.01.

**The panel is now measured rather than guessed.** M1 was right that every
ergonomic claim on the first three boards stood on an estimated 25 cm of glass.
The owner took a tape to the car on 2026-09-04: the active area of the cluster
glass is 320 mm wide and his eyes sit 750 mm from it. Both are constants here,
`GLASS_WIDTH_MM` and `EYE_DISTANCE_MM`, and the whole type ladder falls out of
them - one board unit is 0.2123 mm, a Roboto cap is 0.71 em, and one arc minute
at 750 mm is 0.2182 mm, so a cap subtends `size x 0.691` minutes:

    88 -> 13.3 mm -> 61'      the hero, read on the move
    52 ->  7.8 mm -> 36'      comfortable (ISO 15008 comfort is 30')
    34 ->  5.1 mm -> 23'      legal for a deliberate glance (the floor is 20')
    18 ->  2.7 mm -> 12'      furniture: words that name what is above them
    13 ->  2.0 mm ->  9'      board furniture only, never on the car

So the cluster's ramp on these boards is **88 - 52 - 34 - 18**. 104 is gone: at
320 mm it is a 16 mm numeral and it was chosen against an arithmetic that made 52
look illegal. 88 is 1.69x 52, which clears the ramp's own 1.2x rule, and it still
reads at 61'. 24 and 13 are not used on the panel at all.

**Numbers are Roboto with tabular figures, not Roboto Mono** (M14). Measured in
headless Chrome in the faces and sizes these boards set: a Roboto digit advances
0.5620 of its size at Regular and 0.5547 at Light, and - this is the part worth
writing down - `0`, `1` and `4` all advance identically *without* `tnum`. Roboto's
own figures are already tabular; the feature is set anyway, because a `Paint` on
the car may resolve a different face and a reserve field is a contract rather than
a hope. What `tnum` never bought is punctuation: a comma advances 0.197 and a
colon 0.242 against a monospaced cell's 0.600, which is why «12 , 4» and «2 : 15»
fell apart into groups on the last board and read as one token here.

The rule that survives from the last pass unchanged: **no coordinate depends on
data**. Every number lives in a reserve field sized by its maximum digit count
times a measured advance, its unit hangs off the field rather than off the string,
and neighbours are set against the field's edge. Gaining a digit moves nothing.

**What the review changed, item by item.**

Closed:

- B1  while the engine runs the petal's figure goes `MUTED` and says nothing (the
      sixth pass dropped «· батарея»); the trip's `РЕКУПЕРАЦИЯ` cell is defined to
      integrate only over intervals with the engine off, and the engine's share
      is the `ДАЛ ДВС` cell. Written here as a data rule the renderer follows.
      Since the seventh pass the first cell is the net rather than the gross, so
      those two are what has already been subtracted from it.
- B2  the shelves lost their headings entirely and now hang from the same 24-unit
      guard the hero has, so the vertical budget has 33 units of slack instead of
      7. The band's limit labels are gone (M10) and the right shelf carries its
      unit once, on a fixed anchor.
- B3  the petal is always the last three kilometres; standing on P it keeps the
      same denominator and only gains its tenth (m5). It no longer swaps to a
      trip average, so a figure never changes meaning without changing place.
- M1  measured; see above.
- M2  the hero is 88 with its unit at 34 - the one place a unit has to be read on
      the move - and the cap top keeps the jury's 24 units off `stockTop`.
- M3  34 is legal at 320 mm (23'), so it stays, but as Regular rather than Light:
      Light at 34 puts a 1:11 stem on black. Both shelves are 34 now.
- M4  hierarchy is colour as well as size. `INK` belongs to the hero, to the
      petal's figure, and - at 70 %, since the fifth pass - to the two history
      lines, which are those figures' own traces and read as nothing at MUTED;
      corners and shelves are `MUTED`; headings, captions and the fields under the
      histories are `MUTED_DEEP`; `WARNING`/`DANGER` are the exception only.
- M5  alpha is not a state channel. One rule: a stale value is removed after two
      seconds and its caption stays. Link loss is that rule applied to every
      value at once, a single null is that rule applied to one, and neither dims
      anything. The only users of alpha left are the glow, the two history fills
      and the peak hold.
- M6  the glow no longer travels. It is centred on zero, its hue is the sign, and
      its brightness is `0.18·sqrt(|P| / 120 kW)`, saturated at 120 kW - the fifth
      pass took it off the band's two spans, which had 42 kW back outshining
      100 kW out. τ = 1.5 s.
- M7  the engine box grows from the right, its width is the number of filled
      seconds, and it is never drawn empty. It leaves when the last non-zero slot
      falls off the left edge, which is 120 s of hysteresis with no timer of its
      own: the trace's own length is the timer.
- M8  fixed seats on the right shelf, counted right to left from the shelf's own
      edge, so a cell that appears moves nothing. What the fifth board did with
      that rule - drawing `0,0` in `MUTED_DEEP` so the seat would never be empty -
      is what the owner's question killed: **a zero is never drawn now**, and the
      seat it would have taken simply does not exist that trip.
- M9  the sag line is deleted. «552 В» is what the owner asked for.
- M11 no small blue text anywhere. Blue is the band's body, the generation seam,
      the engine box's area, the petal's return runs, and a marker dot the size of
      a dot at the head of `РЕКУПЕРАЦИЯ` and of the engine's own sentence - the
      places the car gives something back.
- M12 neutral zone of 3 kW: inside it the hero and the band body are `MUTED` and
      carry no colour at all, and the colour changes with 3 kW of hysteresis.
- M13 the scenes the brief asked for are drawn: a traffic jam, an acceleration, an
      engine that stopped forty seconds ago, a single null.
- M14 Roboto with `tnum`; see above.
- M15 the petal's history is a stepped line, not thirty bars 0.65 mm wide. The
      fourth board's 30 % field under a 2-unit MUTED line was the flattening the
      owner then found: it is a 55 % field under a 2.5-unit INK line at 70 % now,
      in a box 56 tall, and the engine box's two runs are the same weight.
- m1  unit symbols are case-sensitive: «БАТАРЕЯ · В», «ДВС · об/мин», «кВт·ч».
- m2  a dim «ДВС» over an empty corner was furniture. If the engine has not run
      this trip the corner is empty - no heading.
- m3  the ten-second tail is gone; the peak hold stays.
- m4  a heading appears with its first value, so the first seconds are the band's
      skeleton and nothing else.
- m5  the petal prints an integer on the move and a tenth only when parked.
- m6  the hero's figure is limited to 4 Hz with 0.5 kW of hysteresis (a rule for
      the renderer, not something a still can show). The critique asked for 2; the
      owner, who drove with the previous panel, asked for the live figures to be
      about twice as quick, and the bus is polled every 100 ms for the same reason.
- m7  the night scene is removed. The cluster's own dimmer already darkens our
      window; whether it does is a measurement on the car, not a board.
- m8  an exception is a 34 figure changing colour on a shelf whose figures are all
      34, so it is the same glance as reading the temperature.
- m9  generation is drawn twice, not three times: the line under the band (the
      seam, if the log ever says it may be one) and the area in the engine box.
      Its figure is written once, and since the eighth pass it is written inside
      the sentence under that area.
- m11 closed by the ninth pass, and it took a picture rather than a caption. Three
      motors under one word in `motorTemps` order was learnable and named as such
      for five drawings; the shared degree sign the sixth pass gave them bought the
      78 units «РАЗБРОС ЯЧЕЕК» needed and bought nothing else. Now each of the five
      readings has its own cell, its own «°» and a glyph saying what it is *of* -
      and the three cars differ by which axle carries the lit block, which is a
      distinction the reader does not have to learn.

Rejected, with the owner's reason:

- M10 *"an honest scale - regeneration a third the length"*. The square root and
      the two spans stay. He accepted the root deliberately: the band is an
      ambient, its two directions are two different physical limits, and equal
      travel meaning "as hard as this car goes that way" is the reading he wants.
      What M10 was right about is the labels, and those are gone.
- M10 *"available regeneration and the power ceiling"*. Wanted, and parked until
      somebody finds a FID for the BMS limits. Drawing a limit we cannot read
      would be the one thing this panel has never done.
- m12 *"lowercase captions"*. Tracked capitals are what the head unit uses, and
      one house style across two screens beats a slightly better silhouette on
      one of them.
- (M2's fallback) *"move the hero into the petal"*. Only after a photograph of the
      hero under the stock speedometer. Until then the hero stays on the axis.

Nothing here is typed. Every coordinate is derived the way `ContourPlan.kt` will
derive it - the apertures out of `ClusterMapLayout`'s own integer arithmetic,
everything else out of five decisions: the margin, the rhythm, the ramp, the cap
height, and the two guards of 24 units off the stock zones, top and bottom.

    python3 gen_contour.py && python3 audit.py ClusterContour \
        ClusterContourStates ClusterContourPlan
"""
import math

import gen_cluster as g

f = g.f

# ---------------------------------------------------------------- the glass

# Measured by the owner on 2026-09-04 with a tape: the active area of the cluster
# glass, and the distance from his eyes to it in his own driving position. Every
# ergonomic claim on the first three boards stood on "порядка 25 см (оценка)",
# which is what CRITIQUE M1 found and what made 104 look necessary.
GLASS_WIDTH_MM = 320.0
EYE_DISTANCE_MM = 750.0

# ---------------------------------------------------------------- the ramp

HERO, FIGURE, READING, CAPTION = 88.0, 52.0, 34.0, 18.0
NOTE = 13.0                      # board furniture only: keep-out words, plan notes
STEP = 8.0                       # InstrumentDensity.WIDE.step
CAP = 0.71                       # InstrumentPen.digitHeight / ContourPlan.CAP_HEIGHT
TRACKING = 0.12                  # InstrumentDensity.titleTracking

# Measured in headless Chrome, in the faces and at the sizes these boards set
# them. Roboto's digits are already tabular - "00000000", "11111111" and
# "44444444" all come back at 233.7969 at 52 - so `tnum` changes nothing in this
# face and is set anyway, because a reserve field is a contract. Light is a
# narrower digit than Regular, and the hero is the only Light thing on the panel.
DIGIT = 0.5620                   # Roboto 400: 29.2246 at 52, 19.1094 at 34
DIGIT_LIGHT = 0.5547             # Roboto 300: 48.8125 at 88
COMMA = 0.1969                   # 10.2344 at 52, 6.7031 at 34 - a mono cell is 0.6
COLON = 0.2422                   # 12.5938 at 52
MONO = 0.6                       # what Roboto Mono advanced, kept for the record

# The handful of places where a *word* decides a coordinate - a unit hanging off a
# field, a caption deciding how wide its cell must be - measured the same way, with
# the tracking the class actually sets.
W_DEGREE = 12.7031               # «°» at 34
W_MILLIVOLT = 46.4063            # «мВ» at 34
W_KW34 = 55.9219                 # «кВт» at 34, the hero's unit
W_KW = 29.6094                   # «кВт» at 18
W_KWH = 44.1094                  # «кВт·ч» at 18, once per cell since the sixth pass
W_KM = 23.0938                   # «км» at 18, the odometer's unit in a caption
W_PER_100KM = 109.4219           # «кВт·ч/100 км» at 18, the figure with no window
W_PETAL_WINDOW = 184.1094        # «кВт·ч/100 км · за 3 км» - the unit names it
# And the widest that unit ever is, which is the window still filling. The figures
# are tabular, so «за 0,3 км» and «за 2,7 км» come out at one width and this
# measures every one of them. Nothing is anchored off it: the unit is left-aligned
# against the figure's own reserve and there is nothing to its right but the
# cut-out, which it clears by 89.9 even here.
W_PETAL_FILLING = 197.7656       # «кВт·ч/100 км · за 1,2 км», measured 2026-09-04
W_TITLE = {'БАТАРЕЯ · В': 126.0781, 'ДВС · об/мин': 137.9844,
           'ДВС · мин за поездку': 224.9375}
# Every string re-measured in one `getComputedTextLength()` run on 2026-09-04, in
# the faces and at the sizes these boards set them, and checked at 18, 180 and 360
# px - the three agree to 0.01, so what is written here is the face's own advance
# and not a rounding of one size. The sixth pass's table did not reproduce: all ten
# of its captions were out, from 3.02 units too wide («ОБОРОТЫ ·») to 0.89 too
# narrow («БАТАРЕЯ»), with no consistent sign - a table typed from more than one
# run rather than one systematic error. The units above and `W_TITLE` came back
# exactly; `W_KWH`, `W_KM` and the captions did not, and are corrected here.
#
# «БАТАРЕЯ», «МОТОРЫ» and «ИНВЕРТОР» are off this table with the ninth pass: the
# temperature row is five glyphs now and the only word left in it is the
# exception's. A caption that decides no coordinate does not belong here.
W_CAPTION = {'РАЗБРОС ЯЧЕЕК': 167.7344, 'РЕКУПЕРАЦИЯ': 150.6719,
             'ДАЛ ДВС': 94.3750, '· ЗА ПОЕЗДКУ': 144.5469,
             # The eighth pass's own two, from a run of the same method on the
             # same day the panel went on the bench. «ОБОРОТЫ ·» and «ГЕНЕРАЦИЯ»
             # are off the table with the legend they belonged to.
             # The ninth pass's phrase carried a literal two minutes, which is the
             # box's capacity and not its reach: it printed «ПОСЛЕДНИЕ 2 МИН» from
             # the first second of an engine run, over a box one step wide. The
             # window is «м:сс» now, measured 2026-09-04 in the same run as the two
             # rows above. Every value of it is this wide - the figures are tabular
             # and a «м:сс» is four glyphs and a mark - so the template decides the
             # anchors and the drawn duration moves none of them.
             'В БАТАРЕЮ · ПОСЛЕДНИЕ 0:00': 315.4375,
             'В БАТАРЕЮ · 0:00': 180.0156}
# Measured and not used: «ВЕРНУЛА РЕКУПЕРАЦИЯ» is 253.5469, which is 102.9 more
# than the noun alone and 65 more than the shelf has. It is why the middle caption
# on P keeps its dot instead of taking the verb the other two got.
#
# One honest note about the two new rows. Re-measuring the whole table in the same
# run put every Cyrillic *word* between 0.9 and 2.4 units under what the seventh
# pass wrote, while every digit, the comma, the colon and «°» came back to the
# ten-thousandth - which is Google's Roboto having moved under the same name
# rather than a second method. It is inside the 2 % the contract test allows an
# advance, and it decides nothing here but whether this one phrase keeps its long
# window, so the seven rows above are left as the panel was laid out from them.

# ---------------------------------------------------------------- the panel

DISPLAY_W, DISPLAY_H = 2560, 720
H = 424.0
W = H * DISPLAY_W / DISPLAY_H
AXIS = W / 2

UNIT_MM = GLASS_WIDTH_MM / W
ARCMIN_MM = EYE_DISTANCE_MM * math.tan(math.radians(1.0 / 60.0))

# ClusterMapLayout's own arithmetic, integer division and all.
STOCK_TOP = (DISPLAY_W * 20 // 100 * 40 // 100 * 4 // 3) / DISPLAY_H * H
STOCK_BOTTOM = (1.0 - (90 + 60) / DISPLAY_H) * H
LEFT_RX = (DISPLAY_W * 24 // 100) / DISPLAY_W * W
RIGHT_RX = (DISPLAY_W * 20 // 100) / DISPLAY_W * W
APERTURE_RY = STOCK_TOP
PETAL_RX = 600 / DISPLAY_W * W
PETAL_RY = (600 * 55 // 100) / DISPLAY_H * H
PETAL_CY = (1.0 - 120 / DISPLAY_H) * H


def millimetres(units):
    return units * UNIT_MM


def arcminutes(size):
    """What a cap of [size] board units subtends from the owner's seat."""
    return millimetres(size * CAP) / ARCMIN_MM


def type_table():
    """The ramp as the eye gets it. ISO 15008: 20' is the floor, 30' is comfort."""
    return [(s, millimetres(s * CAP), arcminutes(s))
            for s in (HERO, FIGURE, READING, CAPTION, NOTE)]


def aperture_reach(y, right=False):
    """How much room a corner aperture still has at baseline [y]."""
    rx = RIGHT_RX if right else LEFT_RX
    t = 1.0 - (y / APERTURE_RY) ** 2
    return rx * math.sqrt(t) if t > 0 else 0.0


def petal_reach(y):
    t = 1.0 - ((y - PETAL_CY) / PETAL_RY) ** 2
    return PETAL_RX * math.sqrt(t) if t > 0 else 0.0


def petal_room(y):
    """Where the petal's cut-out has its left edge at [y] - the box's own limit."""
    return AXIS - petal_reach(y)


def petal_edge(y):
    """And its right edge, which is what the petal's unit has to stay inside."""
    return AXIS + petal_reach(y)


# ---------------------------------------------------------------- the skeleton

UNIT_GAP = STEP * 4                     # between a large figure and its unit
SMALL_GAP = STEP                        # between an 18 figure and its unit

MARGIN = STEP * 6                       # the one outer margin: 48
LEFT_EDGE, RIGHT_EDGE = MARGIN, W - MARGIN

# The two guards, and they are the same number. The jury asked for three rhythm
# steps between the hero's cap and the stock speedometer's own zone; CRITIQUE B2
# found the shelves standing 7 units from the same boundary and the band's labels
# 10 from the other one. Everything on the panel now hangs off one of these two.
CLEARANCE = STEP * 3                    # 24
GUARD_TOP = STOCK_TOP + CLEARANCE
GUARD_BOTTOM = STOCK_BOTTOM - CLEARANCE

HERO_BASELINE = GUARD_TOP + CAP * HERO
HERO_CAP_TOP = HERO_BASELINE - CAP * HERO
# Three digits is the ceiling the scale can produce, so the field is knowable in
# advance. The digits are right-aligned inside the field and the field, its gap
# and its unit are centred on the axis as one group: centring the field alone left
# «кВт» stranded from a two-digit reading and touching a three-digit one.
HERO_FIELD_W = 3 * DIGIT_LIGHT * HERO
HERO_UNIT_W = W_KW34
HERO_GROUP_W = HERO_FIELD_W + UNIT_GAP + HERO_UNIT_W
HERO_FIELD_RIGHT = AXIS + HERO_GROUP_W / 2 - UNIT_GAP - HERO_UNIT_W
HERO_FIELD_LEFT = HERO_FIELD_RIGHT - HERO_FIELD_W
HERO_UNIT_X = HERO_FIELD_RIGHT + UNIT_GAP

BAND_Y = HERO_BASELINE + STEP * 5
BAND_HALF = AXIS - MARGIN
BAND_BODY = 14.0
BAND_HAIRLINE = 1.2
ZERO_HALF = BAND_BODY
ZERO_WIDTH = 1.8
GEN_LINE_Y = BAND_Y + BAND_BODY / 2 + 4.0
GEN_LINE_H = 4.0
DATA_LINE = 2.5                         # nothing that carries data is thinner
AREA_EDGE = 1.8

# One pool of light, and it does not move (M6). Brightness is the magnitude by a
# square root, hue is the sign, τ = 1.5 s. A pool 73 mm across sliding 50-100 mm
# with the pedal was the strongest peripheral stimulus on the panel and the one
# thing here that no production cluster does.
GLOW_CX = AXIS
GLOW_CY = BAND_Y
GLOW_RX = 340.0
GLOW_RY = STOCK_BOTTOM - BAND_Y         # reaches zero exactly on the lower edge
GLOW_MAX = 0.18
# The glow has its own span now, and it is the same one both ways: alpha is
# 0.18·√(|P| / 120 kW) and it saturates at 120 kW. Tying it to the band's spans
# made the two directions two different lights - 42 kW back was brighter than 100
# kW out - and left the calm 34 kW at 0.06, which is a pool nobody sees.
GLOW_FULL_KW = 120.0

# The dead band around zero. Inside it the hero and the band body are MUTED and
# carry no colour at all, and the colour change carries the same 3 kW of
# hysteresis - on a coast P swings +-2 kW and a 13 mm numeral in the fovea was
# flickering between ink and blue.
NEUTRAL_KW = 3.0

# ---- the corners: one heading and one figure, and that is the whole corner

# A row advances by the lead plus the *full* type size. A cap height is what the
# ink occupies; the box a browser and a Paint both reserve runs from ascent to
# descent, and spacing rows by cap height puts a heading's descenders inside the
# digits underneath it.
#
# The third baseline is gone with the sixth pass. It carried one line, «● 14 кВт»
# under the revolutions, and that line was a number standing away from its own
# noun: the reader had to know that the blue dot meant generation and that the
# word for it was two hundred units away, under the box. The figure moved to the
# word instead. What is left is a corner that reads «ДВС · об/мин» over «1780» -
# a heading, a number, and nothing to work out.
CORNER_TITLE = STEP * 3                 # 24
CORNER_FIGURE = CORNER_TITLE + STEP * 2 + FIGURE

LEFT_FIELD_X = LEFT_EDGE                        # volts: three digits
LEFT_FIELD_RIGHT = LEFT_FIELD_X + 3 * DIGIT * FIGURE

RIGHT_FIELD_RIGHT = RIGHT_EDGE                  # revolutions: four digits
RIGHT_FIELD_LEFT = RIGHT_FIELD_RIGHT - 4 * DIGIT * FIGURE
ICE_MINUTES_LEFT = RIGHT_EDGE - 3 * DIGIT * FIGURE

# ---- the five glyphs the temperature row is named by
#
# One family, one stroke, one outline colour, and one component inside each that
# carries the reading's own colour. The owner asked for exactly that - «если
# символы, то и батарея, и инвертор, и хорошие» - after a first cut that drew a
# dot inside a pill for the pack («беспомощно») and left the other four as words.
#
# **They are 24 units tall and they stand on the caption baseline**, so the shelf's
# two rows did not move: 24 above `SHELF_CAPTION` leaves 10 clear of the figures'
# own baseline at 208.32. Drawn at the caption's own 18 first, they were 2.7 mm of
# glass for a shape with four wheels in it - «при 18 единицах жучков не видно» - so
# `GLYPH` is a decision and `GLYPH_K` is what carries every proportion up with it.
GLYPH = STEP * 3                        # 24 units, 5.1 mm, 12' of arc for the whole mark
GLYPH_K = GLYPH / CAPTION               # every proportion below is in caption units
GLYPH_W = 17 * GLYPH_K                  # the widest a glyph is allowed to be
GLYPH_INSET = 1.0                       # and where it starts inside its own cell
GLYPH_STROKE = DATA_LINE                # a glyph carries data, so it is not thinner
# Except the wheels, which are the one thing here that is furniture: they say "this
# is a car from above" and they never say anything else. A wheel at the data weight
# competed with the block on the axle, which is the part that means something.
WHEEL_STROKE = 1.6

# The pack: the outline every battery has, its terminal, and one cell inside it.
PACK_W, PACK_H = 17 * GLYPH_K, 10 * GLYPH_K
PACK_R = 2 * GLYPH_K
PACK_NUB = 2.5 * GLYPH_K                # the terminal, and it is MUTED like the outline
PACK_NUB_INSET = 3 * GLYPH_K            # off the top and the bottom of the case
PACK_CELL_INSET = 2.8 * GLYPH_K         # the lit cell's own margin inside the outline
PACK_CELL_TRIM = 8.3 * GLYPH_K          # off the width, so the cell stops short of the terminal

# The car, from above, in three states that differ by one block. Four wheels stand
# off the body in all three and none of them is ever filled: **the motor is the
# motor, not the wheel it drives**, which is the correction the owner made to the
# first drawing of this glyph («точно мотор с колёсами не путаешь?»).
BODY_W, BODY_H = 9 * GLYPH_K, 14 * GLYPH_K
BODY_X = 4.5 * GLYPH_K                  # from the glyph's left edge
BODY_TOP = 2 * GLYPH_K                  # from the glyph's own top
BODY_R = 2.5 * GLYPH_K
WHEEL_W, WHEEL_H = 3 * GLYPH_K, 5 * GLYPH_K
WHEEL_R = GLYPH_K
WHEEL_GAP = GLYPH_K                     # the standoff from the body, sideways and down
MOTOR_H = 4 * GLYPH_K                   # the block on the axle: the lit part
MOTOR_R = 0.8 * GLYPH_K
MOTOR_INSET = 1.2 * GLYPH_K             # how far inside the body a block starts
MOTOR_SPLIT = 0.4 * GLYPH_K             # half the gap between the two rear blocks

# The inverter: a case with the alternating current it makes drawn inside it.
INVERTER_S = 16 * GLYPH_K
INVERTER_R = 3 * GLYPH_K
WAVE_INSET = 3 * GLYPH_K                # where the period starts and stops inside the case
WAVE_AMPLITUDE = 3.2 * GLYPH_K
WAVE_SAMPLES = 20

# The row, in the order it reads: the pack, the three motors in `motorTemps` order,
# the inverter. `front`, `rl` and `rr` are the same glyph with the block moved.
GLYPHS = ('pack', 'front', 'rl', 'rr', 'inverter')

# ---- the two shelves, which are now one family in every respect

# Both hang from the same guard as the hero, both carry a 34 figure over a mark 24
# units tall, both stand on one pair of baselines. The last board gave them headings
# and two different figure sizes, and the headings were the first thing that would
# have been cut off by a boundary nobody has measured yet.
SHELF_FIGURE = GUARD_TOP + CAP * READING
SHELF_CAPTION = SHELF_FIGURE + STEP * 2 + CAPTION

CELL_GAP = STEP * 2
MARK_R = 3.0                            # the blue marker: a dot, and dot-sized
MARK_GAP = STEP
MARK_W = 2 * MARK_R + MARK_GAP

TEMP_FIELD = 2 * DIGIT * READING        # two digits; 100+ is an alert and may hang
# And every one of the five carries its own «°», which the ninth pass could afford
# because the three motors stopped being one cell. From the sixth to the eighth they
# shared one sign at the end of the run - «61 68 64°» - and those 25.4 units were
# exactly what «РАЗБРОС ЯЧЕЕК» needed over «РАЗБРОС». What paid for them this time is
# the three captions the glyphs replaced: the row is 58 units narrower than the words
# were even with five signs in it.
SPREAD_PAYLOAD = TEMP_FIELD + STEP + W_MILLIVOLT

# **A cell is exactly as wide as the wider of the two things it has to hold** - its
# payload or the thing that names it. Until the sixth pass that width was rounded up
# to the next rhythm step, which was 19.5 units of air across four cells and 78 units
# less than «РАЗБРОС ЯЧЕЕК» costs over «РАЗБРОС». The rounding was the cheapest thing
# on the panel to sell: nothing on the shelf is a rectangle, so the rhythm was
# quantising a distance nobody can see, while the clearance to the hero's field is
# a distance the reader would have seen the moment a three-digit power arrived.
#
# Since the ninth pass the thing that names a temperature cell is a glyph rather than
# a word, so the max is against `GLYPH_W` and the figures win it: five identical
# cells of 50.9, where «ИНВЕРТОР» alone was 110. The sixth cell is the exception's
# and it is the only one still sized by a caption.
TEMP_CELL = max(TEMP_FIELD + W_DEGREE, GLYPH_W)
SPREAD_CELL = max(W_CAPTION['РАЗБРОС ЯЧЕЕК'], SPREAD_PAYLOAD)
LEFT_CELLS = [TEMP_CELL] * len(GLYPHS) + [SPREAD_CELL]
LEFT_SHELF_RIGHT = LEFT_EDGE + sum(LEFT_CELLS) + (len(LEFT_CELLS) - 1) * CELL_GAP

# ---- the right shelf: a phrase, not a ledger
#
# The owner: «что означает 0,0 от ДВС, когда ДВС заглушен?» A cell is a figure with
# its own unit against it and a caption underneath that says what the figure is
# *of* - «9,3 кВт·ч» over «42 км · ЗА ПОЕЗДКУ» - and there are one or two of them
# on the move and three on P. The unit is written per cell now rather than once at
# the end of the row: a shared unit is what makes a row a row, and the row was the
# unreadable part.
#
# Seats are counted right to left from the shelf's own edge and the first one is
# always at it, so the second appearing moves nothing. They are per state rather
# than per panel: standing still adds `РЕКУПЕРАЦИЯ` between the two, and that is a
# gear change rather than a value arriving.
#
# The kilometres lead the caption since the seventh pass. The odometer's field
# holds three digits and the printed number is right-aligned inside it, so with
# «42 км» last the reserve for a third digit stood between the separator and the
# number - 10.1 units of nothing after a «·», which reads as a value that failed
# to arrive rather than as a field with room in it. Leading, the same reserve is
# at the caption's own left edge, where a reader takes it for margin.
TRIP_FIELD = 3 * DIGIT * READING + COMMA * READING      # "12,4" at its widest
ODO_FIELD = 3 * DIGIT * CAPTION                         # "42" with room for "999"
TRIP_PAYLOAD = TRIP_FIELD + SMALL_GAP + W_KWH
TRIP_LEAD = (ODO_FIELD + SMALL_GAP + W_KM + SMALL_GAP
             + W_CAPTION['· ЗА ПОЕЗДКУ'])

TRIP_CELL = max(TRIP_PAYLOAD, TRIP_LEAD)
# «ВЕРНУЛА РЕКУПЕРАЦИЯ» was asked for and does not fit: 253.5 units of caption put
# the three seats at 629.7 and their left edge 41 inside the hero's «кВт», against
# a rule that wants 24 clear. The dot in front of the noun is what the verb would
# have said, and it costs 14. «ДАЛ ДВС» is free by comparison - 94.4 against a
# payload of 116.1, so that cell was already as wide as its own figure.
REGEN_CELL = max(TRIP_PAYLOAD, MARK_W + W_CAPTION['РЕКУПЕРАЦИЯ'])
ICE_CELL = max(TRIP_PAYLOAD, W_CAPTION['ДАЛ ДВС'])
DRIVE_SEATS = [TRIP_CELL, ICE_CELL]
PARK_SEATS = [TRIP_CELL, REGEN_CELL, ICE_CELL]
# The widest the shelf ever is, which is also the engine box's own left limit.
RIGHT_SHELF_LEFT = RIGHT_EDGE - sum(PARK_SEATS) - (len(PARK_SEATS) - 1) * CELL_GAP

# ---- the engine's own two minutes

# EngineTrace keeps 120 one-second slots. The box's right edge is fixed and its
# left edge is wherever the filled slots reach, so it grows from the right as the
# engine's history fills and is never drawn empty (M7). It leaves the panel when
# the last non-zero slot falls off the left edge: 120 s of hysteresis with no
# timer of its own, which is also why a winter traffic jam does not flicker the
# trip balance in and out every ninety seconds.
ENGINE_SLOTS = 120
# And the box draws them as steps of five seconds, the way the petal draws its
# hundred-metre buckets. A per-second line across this box was 120 points inside
# 526 units - 4.4 apart, 0.9 mm of glass for one sample of a quantity that moves
# on the scale of a traffic light. A bin is the mean of the samples that arrived
# in it, so a poll the shell missed costs resolution rather than a hole.
ENGINE_BIN_SECONDS = 5
ENGINE_BINS = ENGINE_SLOTS // ENGINE_BIN_SECONDS
ENGINE_BOX_RIGHT = RIGHT_EDGE
ENGINE_BOX_FULL_LEFT = RIGHT_SHELF_LEFT
ENGINE_PITCH = (ENGINE_BOX_RIGHT - ENGINE_BOX_FULL_LEFT) / ENGINE_BINS
# The box takes both of the shelf's rows now, not the digits' ink box alone. Two
# runs inside 24 units came out as a blue thread with a grey thread on top of it -
# "графики очень сильно сплющены по вертикали", the owner's whole verdict on the
# fourth drawing - and 24 units is 5 mm on this glass for two curves.
#
# Top: the same guard everything else on the panel hangs off, which is also the
# shelf figures' own cap top, so the swap moves no neighbour's baseline. Bottom:
# as far down as the legend leaves it, and the legend hangs off the band's guard
# from below. Everything between is the graph.
ENGINE_BOX_TOP = GUARD_TOP
ENGINE_LEGEND = BAND_Y - BAND_BODY / 2 - CLEARANCE
# One rhythm step between the box's own zero rule and the phrase's caps. Half a
# step was drawn first and the picture settled it: generation is an area that
# stands *on* that rule, so at 4 units the blue and the words were one object.
ENGINE_BOX_BOTTOM = ENGINE_LEGEND - CAP * CAPTION - STEP
# Linear to 30 kW with a clamp, and 30 is what this generator does rather than a
# number borrowed from the band. The root to 100 kW was the fourth pass's and the
# owner read the result as flat twice: at 14 kW - the ordinary figure on this car -
# a root over 100 fills a third of the box, and linear over 30 fills a half. What
# a root buys is resolution near zero, and near zero this quantity is *off*.
ENGINE_GEN_FULL = 30.0
# «● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН», laid out right to left off the shelf's
# own edge: the window, «кВт», two digits, the dot. The eighth pass's answer to
# «легенда непонятна» - there is no legend now, there is a sentence, and the only
# reason it can be one sentence is that the box has one shape in it.
#
# The figure lives in a reserve field like every other number on this panel, so
# 9 kW and 14 kW start the phrase in the same place, and when the engine stops the
# figure and its unit leave together and the words do not move. That is the
# odometer's own arrangement inside «42 км · ЗА ПОЕЗДКУ», one shelf along.
GEN_FIELD = 2 * DIGIT * CAPTION
# The window is the box's own reach, «м:сс», and these are what it is measured
# from. It used to be a literal two minutes - the trace's capacity - printed from
# the first second of an engine run over a box one step wide.
LEGEND_WINDOW = 'В БАТАРЕЮ · ПОСЛЕДНИЕ 0:00'
LEGEND_WINDOW_SHORT = 'В БАТАРЕЮ · 0:00'

# ---- the petal, and the three kilometres behind its figure

PETAL_BASELINE = 384.0
PETAL_FLOOR = 410.0                     # nothing is drawn below this
PETAL_BUCKETS = 30                      # 3 km of ConsumptionLog's 100 m buckets
# "16,8" and "2:15" are both three digits and one mark, so one field holds either,
# and two digits is what the panel actually prints while the car is moving.
PETAL_FIELD_W = 3 * DIGIT * FIGURE + max(COMMA, COLON) * FIGURE
PETAL_BASE_FIELD_W = 2 * DIGIT * FIGURE
# The unit names the window the figure is true over: «кВт·ч/100 км · за 3 км»
# when the log has three kilometres, «· за 1,2 км» while it is still filling.
# Three kilometres has been the rule since the fourth pass and it was written
# nowhere on the glass, so the figure was read against the interval the reader
# brought - the trip, usually, which is the one thing it has never been. And the
# literal was the same defect one level down: a five-hundred-metre history printed
# under «за 3 км» is a figure read against a road it is not the mean of. The width
# below is the widest form, and nothing is anchored off it.
PETAL_UNIT_W = max(W_PETAL_WINDOW, W_PETAL_FILLING)
PETAL_BOX_GAP = STEP * 3
# **The figure centres on the axis, and the box hangs off it.** The last board
# centred the whole composition - box, gap, field, gap, unit - so the digits
# landed 82 units right of the hero's and the box carried the panel's midpoint on
# its back. Now the *printed* figure is what centres: two digits, right-aligned on
# a fixed anchor, so standing on P the tenth grows the field leftward and moves
# neither the unit nor the box.
#
# The accident worth keeping: a two-digit 52 centred on the axis ends at 783.00,
# and the hero's three-digit field ends at 783.04. The two figures share a right
# edge to within 0.04 units, and «кВт» and «кВт·ч/100 км» start on the same x.
PETAL_FIGURE_RIGHT = AXIS + PETAL_BASE_FIELD_W / 2
PETAL_UNIT_X = PETAL_FIGURE_RIGHT + UNIT_GAP
# The box hangs off the *widest* the field ever gets, not off the printed digits,
# so the 24-unit gap is a floor rather than an average: on P it closes to 26, on
# the move it opens to 66, and the box does not move between the two.
PETAL_BOX_RIGHT = PETAL_FIGURE_RIGHT - PETAL_FIELD_W - PETAL_BOX_GAP
# 232 is 29 rhythm steps and it is the aperture's number, not a taste. The jury
# asked for 270; with the figure on the axis the petal's ellipse leaves 238.5 at
# the box's lower left corner once the 8-unit guard is taken, so the width is the
# next whole step under that.
PETAL_BOX_W = STEP * 29
PETAL_BOX_X = PETAL_BOX_RIGHT - PETAL_BOX_W
# **The box's zero line is the figure's own baseline** (the eighth pass: «ноль
# должен быть у цифры»). The fifth pass gave the box 56 units and a zero line four
# fifths down, and both numbers were the box's alone - a ladder standing next to a
# 52 and agreeing with nothing on it. Now the three lines that bound the history
# are the three lines of the numeral beside it: spending rises from the baseline
# through the cap height, a return hangs under it by a descender, and the box is
# 49.92 tall because that is what a 52 occupies. There is nothing left to choose.
PETAL_DESCENDER = 0.25                  # of the figure, which is Roboto's own
PETAL_BOX_TOP = PETAL_BASELINE - CAP * FIGURE
PETAL_ZERO_Y = PETAL_BASELINE
PETAL_BOX_BOTTOM = PETAL_BASELINE + PETAL_DESCENDER * FIGURE
PETAL_BOX_H = PETAL_BOX_BOTTOM - PETAL_BOX_TOP
# A fixed ladder, not an autoscale: 0…30 kW·h/100 km up the cap and 0…10 back down
# the descender, both clamped. Autoscaling to each window's own ceiling meant a
# bucket changed height when a *different* bucket changed value, so the shape of
# the last three kilometres was never twice the same shape. 30 rather than the
# fifth pass's 40 because the two spans no longer share a divisor: what set 40 was
# a zero line at four fifths, and the zero line is the baseline now.
PETAL_FULL = 30.0
PETAL_RETURN_FULL = 10.0
# Contrast, and then what the two colours mean. A 30 % field under a 2-unit line at
# MUTED was a texture; spending is a MUTED_DEEP field at 55 % under the figure's
# own INK at 70 %, drawn across every bucket so the silhouette is continuous and a
# return bucket simply lies on the zero. The return is the *only* blue thing here,
# one shape per run of buckets that gave energy back, standing on the zero line on
# its own posts - the fifth pass drew that field grey and a blue rule along the
# whole zero line whether anything had come back or not, which is the «беспорядочно»
# the owner saw: two quantities in one silhouette and a colour claiming a third.
AREA_ALPHA = 0.55
LINE_ALPHA = 0.70
RETURN_AREA_ALPHA = 0.50
GEN_AREA_ALPHA = 0.55
PEAK_ALPHA = 0.85

# EnergyScale: the band is the dial straightened out, same square root, same spans.
FULL_DISCHARGE_KW, FULL_REGEN_KW, FLOOR_KW = 300.0, 100.0, 0.5


def left_cell(index):
    """Left shelf cells run outward from the margin, each as wide as it must be."""
    left = LEFT_EDGE + sum(LEFT_CELLS[:index]) + index * CELL_GAP
    return left, left + LEFT_CELLS[index]


def trip_cell(index, seats):
    """Right shelf seats, right to left: seat 0 is always against the shelf's edge."""
    right = RIGHT_EDGE - sum(seats[:index]) - index * CELL_GAP
    return right - seats[index], right


def legend_phrase(window):
    """How wide «● 14 кВт [window]» comes out, laid out right to left."""
    return MARK_W + GEN_FIELD + SMALL_GAP + W_KW + SMALL_GAP + W_CAPTION[window]


def legend_anchors():
    """The phrase's anchors, right to left off the shelf's own edge - and both dots.

    The words never move. The figure lives in a two-digit reserve so that 9 kW and
    14 kW start the sentence in the same place, and «кВт» hangs off that field
    rather than off the string.

    **`quiet` is where the dot stands once the engine has stopped.** The figure and
    its unit are removed by the staleness rule and the reserve has nothing left to
    reserve, so it goes with them and the dot closes up against the words:
    «● В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН» rather than 22 units of hole after the dot. It
    is one shift per engine stop rather than a jitter - a reserve keeps a phrase
    still while a *number* changes, and there is no number left to change.
    """
    window_x = RIGHT_EDGE - W_CAPTION[legend_window()]
    unit_x = window_x - SMALL_GAP - W_KW
    field_right = unit_x - SMALL_GAP
    return dict(window=window_x, unit=unit_x, field=field_right,
                mark=field_right - GEN_FIELD - MARK_GAP - MARK_R,
                quiet=window_x - MARK_GAP - MARK_R)


def legend_window():
    """The long window, unless the face in use crowds the phrase against its box.

    The test is the phrase's own measured width plus one guard against the width
    of the box it stands under, so a face wider than Chrome's Roboto - which the
    car's may be - drops «ПОСЛЕДНИЕ» rather than running the sentence out past the
    graph it belongs to. Nothing else in the phrase can go: the figure is the
    reading, the unit is what makes it one, and «В БАТАРЕЮ» is the whole point.
    """
    room = ENGINE_BOX_RIGHT - ENGINE_BOX_FULL_LEFT
    if legend_phrase(LEGEND_WINDOW) + CLEARANCE <= room:
        return LEGEND_WINDOW
    return LEGEND_WINDOW_SHORT


def legend_text(seconds):
    """The window as it is drawn: how far back the box actually reaches.

    Every value of it is one width - tabular figures, four glyphs and a mark - so
    the template above decides every anchor in the phrase and the duration drawn
    inside it moves none of them.
    """
    bounded = max(0, min(int(seconds), 9 * 60 + 59))
    clock = f'{bounded // 60}:{bounded % 60:02d}'
    return legend_window().replace('0:00', clock)


def per_100(bars):
    """The petal's unit, naming the road the figure beside it is the mean of.

    Three kilometres is what the log holds when it has them. A history that has
    just started - a fresh install, a reset journal, the first minutes of a drive -
    is a few hundred metres printed under «за 3 км», which is the same defect the
    seventh pass added this window to fix, one level down.
    """
    covered = len(bars or []) * 0.1
    if covered >= PETAL_BUCKETS * 0.1 - 1e-6:
        return f'кВт·ч/100 км · за {PETAL_BUCKETS * 0.1:.0f} км'
    return f'кВт·ч/100 км · за {covered:.1f} км'.replace('.', ',')


def sweep(kw):
    m = abs(kw)
    if m <= FLOOR_KW:
        return 0.0
    span = FULL_REGEN_KW if kw < 0 else FULL_DISCHARGE_KW
    return math.sqrt(min(m / span, 1.0))


def band_x(kw):
    """Where a reading lands on the band, in board coordinates."""
    travel = sweep(kw) * BAND_HALF
    return AXIS - travel if kw < 0 else AXIS + travel


# ---------------------------------------------------------------- the colour

INK = g.INK
MUTED = g.MUTED
MUTED_DEEP = g.MUTED_DEEP
RETURN = g.RETURN
RETURN_INK = g.RETURN_INK
PEAK = g.DATA_PEAK
WARNING = g.WARNING
DANGER = '#FF4046'
BG = g.CLUSTER_BG

# The hierarchy is colour before it is size (M4). INK is the hero, the petal's
# figure, and at 70 % the two history lines - a figure's own trace in a figure's
# own colour, one step down. The corners and both shelves are MUTED; headings,
# captions, units and the fields under the histories are MUTED_DEEP; WARNING and
# DANGER are the exception only.
# Champagne is not on this panel: yellow means "decide something" in this car, and
# DATA_PEAK is the live edge of the data, which is the band's tip and the peak
# hold - never a history.
LEVEL = {'normal': MUTED, 'watch': WARNING, 'alert': DANGER}
# A glyph's outline is MUTED like a caption; the component inside it is one step
# brighter than the figure above, so the eye finds *what* the number is about before
# it reads the number. On an exception the component takes the figure's own colour,
# so the cell lights up as one object rather than as a number beside a grey picture.
GLYPH_LEVEL = {'normal': INK, 'watch': WARNING, 'alert': DANGER}


def flow_colour(kw, ink=INK):
    """Neutral inside the dead band, ink out, blue back."""
    if kw is None or abs(kw) <= NEUTRAL_KW:
        return MUTED
    return RETURN if kw < 0 else ink


# ---------------------------------------------------------------- svg helpers

def txt(cls, x, y, text, anchor='start', colour=None, opacity=None):
    a = f' text-anchor="{anchor}"' if anchor != 'start' else ''
    style = []
    if colour:
        style.append(f'fill:{colour}')
    if opacity is not None:
        style.append(f'opacity:{f(opacity)}')
    s = f' style="{";".join(style)}"' if style else ''
    return f'<text class="{cls}" x="{f(x)}" y="{f(y)}"{a}{s}>{text}</text>'


def rect(x, y, w, h, colour, rx=None, opacity=None):
    r = f' rx="{f(rx)}"' if rx else ''
    o = f' opacity="{f(opacity)}"' if opacity is not None else ''
    return (f'<rect x="{f(x)}" y="{f(y)}" width="{f(max(w, 0))}" '
            f'height="{f(max(h, 0))}" fill="{colour}"{r}{o}/>')


def line(x0, y0, x1, y1, colour, width, opacity=None, dash=None):
    o = f' opacity="{f(opacity)}"' if opacity is not None else ''
    d = f' stroke-dasharray="{dash}"' if dash else ''
    return (f'<line x1="{f(x0)}" y1="{f(y0)}" x2="{f(x1)}" y2="{f(y1)}" '
            f'stroke="{colour}" stroke-width="{f(width)}"{o}{d}/>')


def frame(x, y, w, h, r, colour, width):
    """A rounded rectangle drawn as an outline - which is what a glyph is made of."""
    return (f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(r)}" '
            f'fill="none" stroke="{colour}" stroke-width="{f(width)}"/>')


def dot(cx, cy, colour=RETURN):
    return f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(MARK_R)}" fill="{colour}"/>'


def comma(value, digits=1):
    return f'{value:.{digits}f}'.replace('.', ',')


# ---------------------------------------------------------------- the glyphs, drawn

def glyph_pack(x, baseline, colour):
    """A battery: the outline, the terminal, and one cell inside carrying the colour.

    The first cut was a dot inside a pill and the owner's word for it was
    «беспомощно». What makes this one a battery rather than a rounded rectangle is
    the terminal hanging off its right edge, and what makes it a *reading* is that
    the cell inside is the only part that ever changes colour.
    """
    top = baseline - GLYPH + (GLYPH - PACK_H) / 2
    return [
        frame(x, top, PACK_W - PACK_NUB, PACK_H, PACK_R, MUTED, GLYPH_STROKE),
        rect(x + PACK_W - PACK_NUB, top + PACK_NUB_INSET, PACK_NUB,
             PACK_H - 2 * PACK_NUB_INSET, MUTED),
        rect(x + PACK_CELL_INSET, top + PACK_CELL_INSET, PACK_W - PACK_CELL_TRIM,
             PACK_H - 2 * PACK_CELL_INSET, colour),
    ]


def glyph_car(x, baseline, where, colour):
    """The car from above, with one motor lit - and a motor is not a wheel.

    The first drawing filled the wheels the motor drives, and the owner asked
    whether it was a motor or a wheel. It is a motor: four hollow wheels stand off
    the body in all three glyphs, and what moves is a filled block on an axle - a
    bar across the front one, half a bar on the left or right of the rear one.
    Which axle and which side is the whole reading, and it needs no caption in any
    language, which is what killed «ПЕРЕД / ЗАД Л / ЗАД П».
    """
    bx = x + BODY_X
    by = baseline - GLYPH + BODY_TOP
    out = [frame(bx, by, BODY_W, BODY_H, BODY_R, MUTED, GLYPH_STROKE)]
    for wx in (bx - WHEEL_GAP - WHEEL_W, bx + BODY_W + WHEEL_GAP):
        for wy in (by + WHEEL_GAP, by + BODY_H - WHEEL_H - WHEEL_GAP):
            out.append(frame(wx, wy, WHEEL_W, WHEEL_H, WHEEL_R, MUTED, WHEEL_STROKE))
    front = by + WHEEL_GAP + WHEEL_H / 2 - MOTOR_H / 2
    rear = by + BODY_H - WHEEL_GAP - WHEEL_H / 2 - MOTOR_H / 2
    half = BODY_W / 2 - MOTOR_INSET - MOTOR_SPLIT
    if where == 'front':
        mx, my, mw = bx + MOTOR_INSET, front, BODY_W - 2 * MOTOR_INSET
    elif where == 'rl':
        mx, my, mw = bx + MOTOR_INSET, rear, half
    else:
        mx, my, mw = bx + BODY_W / 2 + MOTOR_SPLIT, rear, half
    out.append(rect(mx, my, mw, MOTOR_H, colour, rx=MOTOR_R))
    return out


def glyph_inverter(x, baseline, colour):
    """A case with one period of alternating current inside it, which is what it makes."""
    top = baseline - GLYPH + (GLYPH - INVERTER_S) / 2
    mid = top + INVERTER_S / 2
    x0, x1 = x + WAVE_INSET, x + INVERTER_S - WAVE_INSET
    points = []
    for i in range(WAVE_SAMPLES + 1):
        t = i / WAVE_SAMPLES
        points.append((x0 + (x1 - x0) * t,
                       mid - math.sin(t * 2 * math.pi) * WAVE_AMPLITUDE))
    d = 'M ' + ' L '.join(f'{f(px)} {f(py)}' for px, py in points)
    return [
        frame(x, top, INVERTER_S, INVERTER_S, INVERTER_R, MUTED, GLYPH_STROKE),
        f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{f(GLYPH_STROKE)}" '
        f'stroke-linejoin="round" stroke-linecap="round"/>',
    ]


def glyph(kind, x, baseline, colour):
    """One of the five, at [x] on the caption baseline, with its component in [colour]."""
    if kind == 'pack':
        return glyph_pack(x, baseline, colour)
    if kind == 'inverter':
        return glyph_inverter(x, baseline, colour)
    return glyph_car(x, baseline, kind, colour)


# ---------------------------------------------------------------- the histories

# One deterministic run of 100 m buckets, written as multiples of the window's own
# average so a scene names the average it wants and the history and the figure
# cannot disagree. Three buckets are negative: the chart needs a zero line rather
# than a floor, because a descent gives energy back.
SHAPE = [1.22, 1.18, 1.12, 1.14, 1.21, 1.21, 1.07, 0.83, 0.64, -0.22,
         -0.41, -0.12, 0.92, 0.92, 0.97, 1.14, 1.38, 1.54, 1.53, 1.36,
         1.18, 1.08, 1.06, 1.00, 0.85, 0.64, 0.51, 0.57, 0.77, 0.97]


def consumption_history(average):
    """Thirty closed buckets whose spending mean is exactly [average]."""
    spending = [m for m in SHAPE if m > 0]
    norm = sum(spending) / len(spending)
    return [round(average * m / norm, 1) for m in SHAPE]


def engine_history(filled, stopped=0, gen_now=14.0):
    """[filled] seconds of what the engine put back, the last [stopped] of them dead.

    One series since the eighth pass. The revolutions were the other line in the
    box and the box has one shape in it now, so nothing keeps a second trace: the
    number in the corner is where the revolutions were being read anyway.

    No front padding with nulls: the box is exactly as wide as the history it has,
    anchored at the right edge, so it grows leftward as the engine runs and is
    never drawn empty. After the engine stops the slots keep arriving at zero,
    which is what walks the last live sample off the left edge 120 seconds later
    and takes the box with it.
    """
    gen = []
    running = filled - stopped
    for i in range(filled):
        if i >= running:
            gen.append(0.0)
            continue
        t = i / float(max(running - 1, 1))
        share = max(0.0, min(1.0, (t - 0.06) * 3.4))
        gen.append(max(0.0, gen_now * share + 2.4 * math.sin(i * 0.41) * (1.0 - t)))
    return gen


def engine_bins(seconds):
    """One-second slots as five-second steps, oldest first, at most [ENGINE_BINS].

    A bin is the mean of the samples that actually arrived in it, so a poll the
    shell missed costs resolution rather than a hole, and a bin nothing answered
    in at all is `None` - which breaks the run rather than being drawn through.
    The last bin is the newest and is the one that can be short: the grouping runs
    from the oldest slot, so a history 82 seconds long is sixteen full steps and
    one of two, at the right-hand edge, where the newest data is.
    """
    bins = []
    for start in range(0, len(seconds), ENGINE_BIN_SECONDS):
        window = [v for v in seconds[start:start + ENGINE_BIN_SECONDS] if v is not None]
        bins.append(sum(window) / len(window) if window else None)
    return bins[-ENGINE_BINS:]


def runs(values, keep):
    """The stretches of [values] that [keep] accepts, as (start, stop) pairs."""
    out, i, n = [], 0, len(values)
    while i < n:
        if not keep(values[i]):
            i += 1
            continue
        start = i
        while i < n and keep(values[i]):
            i += 1
        out.append((start, i))
    return out


def step_path(xs, ys, floor):
    """A stepped line and the field under it, as two paths sharing one outline."""
    edge = [f'M {f(xs[0])} {f(ys[0])}']
    for i in range(len(ys)):
        edge.append(f'L {f(xs[i + 1])} {f(ys[i])}')
        if i + 1 < len(ys):
            edge.append(f'L {f(xs[i + 1])} {f(ys[i + 1])}')
    outline = ' '.join(edge)
    field = (f'M {f(xs[0])} {f(floor)} ' + outline[2:] +
             f' L {f(xs[-1])} {f(floor)} Z')
    return outline, field


def step_patch(xs, ys, zero):
    """One stepped shape standing on [zero], posts and all, as a single path.

    Not `step_path`: that gives a field closed along a floor and an outline that
    is open at both ends, which is what a continuous history wants. A return is a
    *stretch* - it has two ends, and the eye has to see where it starts and stops -
    so the posts up from the zero line are part of the drawing rather than the
    edge of a fill. Filled and stroked from the same path, unclosed, so nothing
    blue is ever drawn along the zero line itself.
    """
    d = [f'M {f(xs[0])} {f(zero)}']
    for i in range(len(ys)):
        d.append(f'L {f(xs[i])} {f(ys[i])}')
        d.append(f'L {f(xs[i + 1])} {f(ys[i])}')
    d.append(f'L {f(xs[-1])} {f(zero)}')
    return ' '.join(d)


# ---------------------------------------------------------------- the pieces

def keepout():
    """Where the vehicle draws over us. Hatched, so the composition is judged against it."""
    return [
        '<defs>',
        '<pattern id="hatch" width="10" height="10" patternUnits="userSpaceOnUse" '
        'patternTransform="rotate(45)">'
        '<line x1="0" y1="0" x2="0" y2="10" stroke="rgba(218,225,235,0.07)" stroke-width="1.4"/>'
        '</pattern>',
        f'<mask id="topmask"><rect x="0" y="0" width="{f(W)}" height="{f(STOCK_TOP)}" '
        f'fill="#fff"/><ellipse cx="0" cy="0" rx="{f(LEFT_RX)}" ry="{f(STOCK_TOP)}" fill="#000"/>'
        f'<ellipse cx="{f(W)}" cy="0" rx="{f(RIGHT_RX)}" ry="{f(STOCK_TOP)}" fill="#000"/></mask>',
        f'<mask id="botmask"><rect x="0" y="{f(STOCK_BOTTOM)}" width="{f(W)}" '
        f'height="{f(H - STOCK_BOTTOM)}" fill="#fff"/><ellipse cx="{f(AXIS)}" '
        f'cy="{f(PETAL_CY)}" rx="{f(PETAL_RX)}" ry="{f(PETAL_RY)}" fill="#000"/></mask>',
        '</defs>',
        f'<rect x="0" y="0" width="{f(W)}" height="{f(STOCK_TOP)}" fill="url(#hatch)" '
        f'mask="url(#topmask)"/>',
        f'<rect x="0" y="{f(STOCK_BOTTOM)}" width="{f(W)}" height="{f(H - STOCK_BOTTOM)}" '
        f'fill="url(#hatch)" mask="url(#botmask)"/>',
        txt('keep', AXIS, 26, 'ШТАТНЫЕ ПРИБОРЫ', 'middle'),
        txt('keep', LEFT_EDGE, (STOCK_BOTTOM + H) / 2 + NOTE * 0.36, 'ШТАТНАЯ ПОЛОСА'),
    ]


def glow_alpha(kw):
    """`0.18·√(|P| / 120 kW)`, saturated at 120 kW, and the same both ways.

    The fourth board took the band's own travel fraction, which is a square root
    over 300 kW out and 100 kW back. That gave the glow two different meanings by
    direction - 42 kW of braking outshone 100 kW of pulling - and it spent the
    whole calm half of its range in the dark: 34 kW came to 0.06 of an alpha that
    only reaches 0.18 at the car's absolute limit, which nothing on a commute ever
    sees. One span of 120 kW, the pedal's own working range, is the fix: calm sits
    at 0.10, an acceleration saturates, and everything above 120 is the same light.
    """
    m = abs(kw)
    if m <= FLOOR_KW:
        return 0.0
    return GLOW_MAX * math.sqrt(min(m / GLOW_FULL_KW, 1.0))


def glow(kw):
    """The one pool of light, and it stands still.

    Hue is the sign, brightness is `glow_alpha`, and the centre never leaves zero.
    The concept had it riding the band's tip with τ = 400 ms, which put a 73 mm
    pool through 50-100 mm of travel every time the pedal moved in a traffic jam.
    """
    if kw is None:
        return []
    strength = glow_alpha(kw)
    if strength <= 0:
        return []
    c = flow_colour(kw)
    return [
        f'<defs><radialGradient id="bandglow">'
        f'<stop offset="0" stop-color="{c}" stop-opacity="{f(strength)}"/>'
        f'<stop offset="0.5" stop-color="{c}" stop-opacity="{f(strength * 0.45)}"/>'
        f'<stop offset="1" stop-color="{c}" stop-opacity="0"/>'
        f'</radialGradient></defs>',
        f'<ellipse cx="{f(GLOW_CX)}" cy="{f(GLOW_CY)}" rx="{f(GLOW_RX)}" '
        f'ry="{f(GLOW_RY)}" fill="url(#bandglow)"/>',
    ]


def skeleton():
    """Drawn in every state, including the ones with no data at all.

    Two lines and nothing else: the limit captions are gone. A band whose two
    directions run on a square root over two different spans is an ambient, not a
    scale, and «100 кВт / 300 кВт» were two 12' lines saying otherwise.
    """
    return [
        line(LEFT_EDGE, BAND_Y, RIGHT_EDGE, BAND_Y, MUTED_DEEP, BAND_HAIRLINE),
        line(AXIS, BAND_Y - ZERO_HALF, AXIS, BAND_Y + ZERO_HALF, MUTED_DEEP, ZERO_WIDTH),
    ]


def band(s):
    """The one bar left on the panel, and the engine's share drawn behind its tip.

    ink is what the battery pays, blue is what the engine pays, and the tip is
    what the wheels asked for - the jury's second correction. That reading is only
    true if `GENERATION_KW` is not already inside `POWER_KW`, which has not been
    logged on this car, so the default is `seam_on_band` False: the same fact
    without the claim, as a separate line under the body on the return side's own
    scale. `VehicleConvention.GENERATION_INSIDE_PACK_POWER` is the same decision in
    the app and `ContourBoardContractTest` holds the two together; one state below
    draws the seam so that the alternative is on record.
    """
    kw = s.get('kw')
    if kw is None:
        return []
    out = []
    tip = band_x(kw)
    top = BAND_Y - BAND_BODY / 2
    neutral = abs(kw) <= NEUTRAL_KW
    returning = kw < -NEUTRAL_KW
    base = flow_colour(kw)
    edge = MUTED if neutral else (RETURN_INK if returning else PEAK)
    ident = 'bandfillr' if returning else ('bandfilln' if neutral else 'bandfill')
    if abs(kw) > FLOOR_KW:
        x0, x1 = (tip, AXIS) if kw < 0 else (AXIS, tip)
        out.append(
            f'<defs><linearGradient id="{ident}" gradientUnits="userSpaceOnUse" '
            f'x1="{f(AXIS)}" y1="0" x2="{f(tip)}" y2="0">'
            f'<stop offset="0" stop-color="{base}" stop-opacity="0.55"/>'
            f'<stop offset="1" stop-color="{edge}" stop-opacity="1"/>'
            f'</linearGradient></defs>')
        out.append(f'<rect x="{f(x0)}" y="{f(top)}" width="{f(x1 - x0)}" '
                   f'height="{f(BAND_BODY)}" fill="url(#{ident})"/>')
    generation = s.get('generation') if s.get('ice') == 'running' else None
    if generation:
        # The seam behind the tip reads `wheels = pack + generation`, which is only
        # true if GENERATION_KW is not already inside POWER_KW - and nobody has
        # logged this car on a flat cruise with the engine running. So the default
        # is the drawing that makes no claim, a separate line under the body, and it
        # is the same default `VehicleConvention.GENERATION_INSIDE_PACK_POWER` sets:
        # this flag defaulted the other way for a while and the board's canonical
        # engine state was the one picture the app never drew.
        #
        # The line is measured on the RETURN side's own span, not the discharge one.
        # `sweep` picks its span from the sign, so a positive argument here put 14 kW
        # of generation at 0.216 of the half-band while 14 kW of regeneration on the
        # band above it sat at 0.374 - the same kilowatts into the same pack, in the
        # same blue, 1.73 times apart.
        if s.get('seam_on_band', False):
            far = band_x(kw + generation)
            out.append(rect(tip, top, far - tip, BAND_BODY, RETURN))
        else:
            far = AXIS + sweep(-generation) * BAND_HALF
            out.append(rect(AXIS, GEN_LINE_Y, far - AXIS, GEN_LINE_H, RETURN))
    peak = s.get('peak')
    if peak is not None and abs(peak) > NEUTRAL_KW:
        px = band_x(peak)
        out.append(line(px, BAND_Y - BAND_BODY / 2 - 3, px, BAND_Y + BAND_BODY / 2 + 3,
                        PEAK, 3.0, PEAK_ALPHA))
    return out


def hero(s):
    """The one figure read on the move, and the one that keeps its unit beside it.

    The unit is 34 - 23', a size that can be read - because this is the one place
    on the panel where a unit has to be. A 12' «кВт» under the stock speedometer
    was the only thing telling the driver that «34» was not 34 km/h.
    """
    if not s['power_known']:
        return []
    kw = s.get('kw')
    out = [txt('un34', HERO_UNIT_X, HERO_BASELINE, 'кВт', 'start', MUTED)]
    if kw is None:
        return out
    colour = flow_colour(kw, ink=INK)
    if kw < -NEUTRAL_KW:
        colour = RETURN_INK
    return [txt('hero', HERO_FIELD_RIGHT, HERO_BASELINE, f'{abs(kw):.0f}', 'end',
                colour)] + out


def left_corner(s):
    """БАТАРЕЯ · В - the pack's volts, and nothing else.

    The sag line is deleted. Its reference was an EWMA of the pack at rest, and on
    a motorway there is no rest: the board electronics pull a kilowatt or two
    permanently, so after half an hour the reference has aged into the pack's own
    discharge and «просадка 14 В» is 10 % of SOC wearing a unit it does not have.
    """
    if not s['volts_known']:
        return []
    out = [txt('ttl', LEFT_EDGE, CORNER_TITLE, 'БАТАРЕЯ · В', 'start', MUTED_DEEP)]
    if s.get('volts') is not None:
        out.append(txt('fig', LEFT_FIELD_RIGHT, CORNER_FIGURE,
                       f'{s["volts"]:.0f}', 'end', MUTED))
    return out


def right_corner(s):
    """ДВС, in the three states it has, one of which is not being there at all.

    Running: the heading carries the unit and the figure is the revolutions. That
    is the whole corner since the sixth pass, and since the eighth it is the only
    place the revolutions are drawn at all - the line the box used to carry for
    them was half of what made its legend unreadable, and this is where a driver
    was reading them anyway. The line that used to stand under this figure,
    «● 14 кВт», was a number parked away from its own noun; it is inside the
    engine box's own sentence now, and «1780 об/мин» is left saying one thing.

    Asleep after running: «ДВС · мин за поездку» over «6», which is the question a
    hybrid's driver actually asks and the answer nothing on this panel gave. The
    unit is in the heading for all three states, so nothing hangs under this one
    either - and since the seventh pass the heading says which window those minutes
    are counted over. «ДВС · мин» alone was six minutes of *something*: this stop,
    this hour, this trip, the odometer. The fifth pass wanted the same words and
    put them on a third baseline, where the aperture left eight units; up at the
    heading's own y 24 the corner ellipse is 298.1 wide, so 224.9 of heading has
    25.2 to spare and the figure under it does not move.

    Never started this trip: empty. A dimmed heading over an empty corner is
    advertising an instrument that is not there.
    """
    if not s['ice_known']:
        return []
    mode = s.get('ice')
    if mode == 'running':
        out = [txt('ttl', RIGHT_EDGE, CORNER_TITLE, 'ДВС · об/мин', 'end', MUTED_DEEP)]
        if s.get('rpm') is not None:
            out.append(txt('fig', RIGHT_FIELD_RIGHT, CORNER_FIGURE,
                           f'{s["rpm"]:.0f}', 'end', MUTED))
        return out
    out = [txt('ttl', RIGHT_EDGE, CORNER_TITLE, 'ДВС · мин за поездку', 'end',
               MUTED_DEEP)]
    if s.get('ice_minutes') is not None:
        out.append(txt('fig', RIGHT_EDGE, CORNER_FIGURE, f'{s["ice_minutes"]:.0f}',
                       'end', MUTED))
    return out


def left_shelf(s):
    """Temperatures: five cells named by pictures, and a sixth that is an exception.

    **No words.** Until the ninth pass this row was three cells - `БАТАРЕЯ`,
    `МОТОРЫ`, `ИНВЕРТОР` - and the middle one carried three figures under one
    caption, so which motor was which was something the reader had to learn. Naming
    the positions in Russian was tried and the owner threw it out on the sound of
    it; a drawing of the car with one axle lit says the same thing in one glance and
    in no language. So the whole row is glyphs, because half a row of pictures under
    half a row of words is worse than either.

    Each cell is a two-digit figure with its own «°» over a 24-unit glyph standing
    on the caption baseline. The exception is the figure changing colour - and now
    the glyph's own component with it, so the cell lights up as one object - at the
    same size as every other figure on the shelf, so noticing it and reading it are
    one glance.

    The sixth cell is the only word left in the row, which is the second thing the
    glyphs bought: a word here now *means* something is wrong.
    """
    if not s['temps_known']:
        return []
    out = []
    temps = s.get('temps')
    readings = None
    if temps:
        readings = [temps['pack']] + list(temps['motors']) + [temps['inverter']]

    for index, kind in enumerate(GLYPHS):
        left, _ = left_cell(index)
        reading = readings[index] if readings else None
        level = reading[1] if reading else 'normal'
        # The glyph is this cell's caption, so it obeys the caption rule: it is drawn
        # as soon as the reading has ever arrived and it stays when the reading goes.
        out += glyph(kind, left + GLYPH_INSET, SHELF_CAPTION, GLYPH_LEVEL[level])
        if reading:
            # A degree belongs against its digits and Roboto sets it there: the field
            # is two characters wide and the sign starts exactly at its right edge.
            out.append(txt('rd', left + TEMP_FIELD, SHELF_FIGURE, reading[0], 'end',
                           LEVEL[level]))
            out.append(txt('rd', left + TEMP_FIELD, SHELF_FIGURE, '°', 'start', MUTED))

    spread = s.get('spread')
    if spread:
        value, level = spread
        left, _ = left_cell(len(GLYPHS))
        out.append(txt('cl', left, SHELF_CAPTION, 'РАЗБРОС ЯЧЕЕК', 'start', MUTED_DEEP))
        out.append(txt('rd', left + TEMP_FIELD, SHELF_FIGURE, value, 'end', LEVEL[level]))
        out.append(txt('rd', left + TEMP_FIELD + STEP, SHELF_FIGURE, 'мВ',
                       'start', MUTED))
    return out


def engine_box(s):
    """Two minutes of what the engine put back, where the trip balance stands otherwise.

    **One quantity, one sentence.** The fourth pass drew two runs in here and the
    sixth gave them a legend to tell them apart; the owner's verdict on the built
    panel was that the legend was not understandable, which is the whole game lost -
    a driver's display that needs a key needs it at 90 km/h. So the second run is
    gone, to the corner where the same number was already printed, and what is left
    is generation as an area under a phrase that names it: «● 14 кВт В БАТАРЕЮ ·
    ПОСЛЕДНИЕ 2 МИН». No word on this panel is «ГЕНЕРАЦИЯ» any more, or «ОБОРОТЫ».

    The area is **linear to 30 kW, clamped**, which is the second half of the same
    verdict - «сплющен», said of a box whose height cannot grow: it is both rows of
    the shelf, the guard above it and the phrase's caps below. A root over 100 kW
    put this car's ordinary 14 at a third of that height; linear over 30 puts it at
    a half, and 30 is what this generator does rather than a span borrowed from the
    band. Twenty-four steps of five seconds, oldest first, growing from the right.

    While the box is up the trip's cells are hidden. That is not "куда делся
    баланс": the box only leaves 120 s after the last live sample, so the balance
    comes back once, not once per engine cycle, and a winter jam that cycles the
    engine every ninety seconds never gets the swap at all.

    **When the engine stops the sentence closes up.** The figure and its unit are
    removed by the staleness rule and the reserve that held the figure's place goes
    with them, so the dot stands against the words rather than 22 units off them.
    That hole was the ninth pass's second finding, and it is one shift per engine
    stop: a reserve keeps a phrase still while a number changes, and by then there
    is no number left to change.
    """
    bins = engine_bins(s['trace'])
    x1 = ENGINE_BOX_RIGHT
    x0 = x1 - len(bins) * ENGINE_PITCH
    top, bottom = ENGINE_BOX_TOP, ENGINE_BOX_BOTTOM
    height = bottom - top
    xs = [x0 + ENGINE_PITCH * i for i in range(len(bins) + 1)]
    ys = [bottom - height * min(1.0, max(0.0, v / ENGINE_GEN_FULL)) for v in bins]

    out = [line(x0, bottom, x1, bottom, MUTED_DEEP, BAND_HAIRLINE)]
    outline, field = step_path(xs, ys, bottom)
    out.append(f'<path d="{field}" fill="{RETURN}" opacity="{f(GEN_AREA_ALPHA)}"/>')
    out.append(f'<path d="{outline}" fill="none" stroke="{RETURN}" '
               f'stroke-width="{f(AREA_EDGE)}" stroke-linejoin="round"/>')

    # The phrase, laid out right to left off the shelf's own edge: the window, «кВт»,
    # the figure in its reserve field, the dot. The words never move; the figure and
    # its unit leave together when the engine stops, which is the panel's one rule
    # for a stale reading applied to a number living inside a sentence.
    at = legend_anchors()
    out.append(txt('cl', at['window'], ENGINE_LEGEND, legend_text(len(s['trace'])),
                   'start', MUTED_DEEP))
    generation = s.get('generation') if s.get('ice') == 'running' else None
    if generation is None:
        # No figure, so no field: the dot closes up against the words rather than
        # standing 22 units off them with the reserve between (the ninth pass).
        out.append(dot(at['quiet'], ENGINE_LEGEND - CAP * CAPTION / 2))
        return out
    out.append(dot(at['mark'], ENGINE_LEGEND - CAP * CAPTION / 2))
    out.append(txt('un', at['field'], ENGINE_LEGEND, f'{generation:.0f}',
                   'end', MUTED_DEEP))
    out.append(txt('un', at['unit'], ENGINE_LEGEND, 'кВт', 'start', MUTED_DEEP))
    return out


def trip_seat(left, value, word, marked=False, odometer=None):
    """One cell of the right shelf: a figure with its unit, over a phrase.

    The figure is right-aligned inside its own reserve field and «кВт·ч» hangs off
    the field's edge rather than off the string, so the tenth and the second digit
    move nothing. Everything in the cell is left-aligned against the cell, the same
    way the temperature shelf's cells are: a phrase is read from its left.

    The unit belongs to the figure. If the value has gone stale it leaves with it
    and the words stay, which is the panel's one rule for a missing reading (M5) -
    the same reason the odometer's «42 км» is drawn only when the odometer has
    answered, and the caption is then «ЗА ПОЕЗДКУ» alone with nothing in front of it.

    The kilometres come first since the seventh pass. Their field holds three
    digits and the printed number is right-aligned inside it either way, so with
    «42 км» last the reserve stood between the «·» and the number and read as a
    value that had failed to arrive. Leading, that reserve is the caption's own
    left margin, and the phrase reads the way it is spoken: forty-two kilometres,
    this trip.
    """
    out = []
    if value is not None:
        out.append(txt('rd', left + TRIP_FIELD, SHELF_FIGURE, comma(value),
                       'end', MUTED))
        out.append(txt('un', left + TRIP_FIELD + SMALL_GAP, SHELF_FIGURE,
                       'кВт·ч', 'start', MUTED_DEEP))
    x = left
    if marked:
        out.append(dot(left + MARK_R, SHELF_CAPTION - CAP * CAPTION / 2))
        x = left + MARK_W
    if odometer is None:
        out.append(txt('cl', x, SHELF_CAPTION, word, 'start', MUTED_DEEP))
        return out
    out += [txt('un', x + ODO_FIELD, SHELF_CAPTION, f'{odometer:.0f}', 'end',
                MUTED_DEEP),
            txt('un', x + ODO_FIELD + SMALL_GAP, SHELF_CAPTION, 'км', 'start',
                MUTED_DEEP),
            txt('cl', x + ODO_FIELD + SMALL_GAP + W_KM + SMALL_GAP, SHELF_CAPTION,
                '· ' + word, 'start', MUTED_DEEP)]
    return out


def right_shelf(s):
    """What the trip cost, as a sentence - or the engine's own two minutes.

    On the move it is one cell, «9,3 кВт·ч» over «42 км · ЗА ПОЕЗДКУ», and a
    second one only if the engine actually ran: «1,1 кВт·ч» over «ДАЛ ДВС».
    Standing on P the same anatomy pays out in full and «● РЕКУПЕРАЦИЯ» comes
    between them, because a car that is not moving is the one place where three
    numbers cost nothing to read.

    **The first figure is the net, and the other two are what came back.**
    `tripNetKwh = ∫P dt` over the trip in the pack's own units: what regeneration
    returned and what the engine generated are already out of it, so `9,3` is what
    left the battery for good over those 42 km. The seventh pass gave the other two
    verbs for exactly that reason - three unsigned numbers under three nouns invite
    a reader to add them up, and their sum names nothing. «ДАЛ ДВС» carries its
    verb; «● РЕКУПЕРАЦИЯ» could not afford «ВЕРНУЛА» and leans on its dot instead.

    **A zero is never drawn.** The fifth board drew `0,0 ОТ ДВС` so that a seat
    would never be empty, and the owner asked what it meant - which is the whole
    reason for the sixth pass. A quantity that does not exist this trip has no
    cell, and the seats are counted from the shelf's own edge so the one that does
    exist is always in the same place.
    """
    if s.get('trace'):
        return engine_box(s)
    if not s['trip_known']:
        return []
    trip = s.get('trip') or {}
    parked = bool(s.get('parked'))
    seats = PARK_SEATS if parked else DRIVE_SEATS
    row = [('net', 'ЗА ПОЕЗДКУ', False)]
    if parked:
        row.append(('regen', 'РЕКУПЕРАЦИЯ', True))
    row.append(('ice', 'ДАЛ ДВС', False))
    out = []
    for index, (key, word, marked) in enumerate(row):
        value = trip.get(key)
        # Seat 0 is the trip itself and it exists as soon as the trip does, so it
        # keeps its caption when the bus goes quiet. The other two answer "did this
        # happen at all", and a zero is that question answered "no": no cell.
        if not value and index:
            continue
        left, _ = trip_cell(index, seats)
        out += trip_seat(left, value or None, word, marked,
                         odometer=trip.get('km') if key == 'net' else None)
    return out


def petal_history(bars):
    """Three kilometres of closed buckets, as one stepped line beside its figure.

    Two things the owner said about the fourth board are answered here and they
    are the same thing twice: "графики очень сильно сплющены по вертикали и очень
    слабо читаются". The box grew from 36 units to the figure's own height, and
    the drawing is a 70 % INK line 2.5 units thick over a 55 % MUTED_DEEP field,
    where it was a 2-unit MUTED line over a 30 % one and read as a texture.

    **The zero line is the figure's own baseline** since the eighth pass, and the
    box's two edges are the numeral's cap top and the depth of its descender. The
    scale is a fixed ladder on those three lines: 0…30 kW·h/100 km up the cap,
    0…10 back down the descender, both clamped. An autoscale meant one bucket
    changing value redrew the height of all thirty, so the same three kilometres
    never came back the same shape. There is no dashed mean: the mean is the figure
    standing next to the box.

    **Two series, and the second one is only where it happened.** Spending is one
    continuous grey field across all thirty buckets - on a bucket that gave energy
    back it lies on the zero line, because what was spent there is nothing - and the
    return is a blue shape per run of return buckets, hanging under the zero on its
    own posts. The fifth pass drew one field crossing the zero in one colour, with a
    blue rule along the whole width whether anything had come back or not; that is
    the «беспорядочно» the owner saw.
    """
    if not bars:
        return []
    zero, top, bottom = PETAL_ZERO_Y, PETAL_BOX_TOP, PETAL_BOX_BOTTOM
    # The pitch is the box divided by the window rather than by what has arrived, and
    # the run is anchored at the box's right edge, so a history still filling grows
    # leftward into its box instead of stretching across it. Stretched, three hundred
    # metres of road would be drawn as three kilometres.
    pitch = PETAL_BOX_W / PETAL_BUCKETS
    left = PETAL_BOX_X + (PETAL_BUCKETS - len(bars)) * pitch
    xs = [left + pitch * i for i in range(len(bars) + 1)]
    spend = [zero - min(max(v, 0.0) / PETAL_FULL, 1.0) * (zero - top) for v in bars]
    outline, field = step_path(xs, spend, zero)
    out = [
        f'<path d="{field}" fill="{MUTED_DEEP}" opacity="{f(AREA_ALPHA)}"/>',
        f'<path d="{outline}" fill="none" stroke="{INK}" opacity="{f(LINE_ALPHA)}" '
        f'stroke-width="{f(DATA_LINE)}" stroke-linejoin="round"/>',
    ]
    for start, stop in runs(bars, lambda v: v < 0):
        back = [zero + min(-bars[i] / PETAL_RETURN_FULL, 1.0) * (bottom - zero)
                for i in range(start, stop)]
        d = step_patch(xs[start:stop + 1], back, zero)
        out.append(f'<path d="{d}" fill="{RETURN}" opacity="{f(RETURN_AREA_ALPHA)}"/>')
        out.append(f'<path d="{d}" fill="none" stroke="{RETURN_INK}" '
                   f'stroke-width="{f(DATA_LINE)}" stroke-linejoin="round"/>')
    out.append(line(PETAL_BOX_X, zero, PETAL_BOX_X + PETAL_BOX_W, zero,
                    MUTED_DEEP, BAND_HAIRLINE))
    return out


def petal(s):
    """What the last three kilometres cost - always the last three kilometres.

    The denominator never changes under the figure: standing on P it is still
    three kilometres and only the tenth appears, because at 100 km/h a tenth
    changes three times a second and a figure that flickers is a figure nobody
    reads.

    **Since the seventh pass the unit says so, and since the tenth it says which
    three kilometres: «кВт·ч/100 км · за 1,2 км» while the log is still filling.**
    The
    window was a rule in this file and on none of the six drawings, and a
    consumption figure with nothing naming its interval is read against whatever
    interval the reader has in mind - the trip, mostly, which is the one thing this
    number has never been. The unit is where that belongs: already the line under
    the figure, already MUTED_DEEP, and the words cost 74.7 units of a cut-out that
    had 178 free to the right of them.

    While the engine is running the figure goes MUTED and says nothing else.
    `ConsumptionLog` integrates pack power alone and nobody has logged whether
    `GENERATION_KW` is already inside `POWER_KW` (B1), so until that log exists
    this number is the battery's alone - and «кВт·ч/100 км · батарея» was five
    characters of footnote at 12', on the one line of the panel that has to be read
    in a glance. Colour says the same thing without asking anybody to read it: the
    figure that is INK the rest of the time has stepped down, and a reader who
    never works out why has lost nothing.
    """
    if s.get('hint'):
        return [txt('un', AXIS, PETAL_BASELINE, s['hint'], 'middle', MUTED)]
    if not s['petal_known']:
        return []
    out = petal_history(s.get('bars'))
    out.append(txt('un', PETAL_UNIT_X, PETAL_BASELINE, s['petal_unit'],
                   'start', MUTED_DEEP))
    value = s.get('petal')
    if value is not None:
        colour = MUTED if s.get('ice') == 'running' else INK
        out.append(txt('fig', PETAL_FIGURE_RIGHT, PETAL_BASELINE, value, 'end', colour))
    return out


def scene(s):
    """One complete panel, in the order the app paints it."""
    body = keepout()
    body += glow(s.get('kw'))
    body += skeleton()
    body += band(s)
    body += hero(s)
    body += left_corner(s)
    body += right_corner(s)
    body += left_shelf(s)
    body += right_shelf(s)
    body += petal(s)
    return body


# ---------------------------------------------------------------- the page

HEAD = """<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500&amp;display=swap" rel="stylesheet">
  <style>
    body { margin:0; background:%(bg)s; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    /* Every figure on the panel is Roboto with tabular numerals. Roboto's own
       digits are already fixed-width - measured, all ten advance the same - so
       `tnum` is a contract rather than a fix, and what it never had to fix is
       punctuation: the comma that a monospaced face gave a full cell, breaking
       "12,4" into "12 , 4", is a fifth of a digit here. */
    .num { font-variant-numeric:tabular-nums; font-feature-settings:'tnum'; }
    .hero { font-weight:300; font-size:%(hero)spx; fill:%(ink)s;
            font-variant-numeric:tabular-nums; font-feature-settings:'tnum'; }
    .fig { font-weight:400; font-size:%(figure)spx; fill:%(muted)s;
           font-variant-numeric:tabular-nums; font-feature-settings:'tnum'; }
    .rd { font-weight:400; font-size:%(reading)spx; fill:%(muted)s;
          font-variant-numeric:tabular-nums; font-feature-settings:'tnum'; }
    /* The hero's unit is the one unit that has to be read on the move, so it is
       the one unit at a readable size and one step brighter than the rest. */
    .un34 { font-weight:400; font-size:%(reading)spx; fill:%(muted)s; }
    .ttl { font-size:%(caption)spx; font-weight:500; letter-spacing:%(tracking)sem;
           fill:%(muted_deep)s; }
    .cl { font-size:%(caption)spx; font-weight:400; letter-spacing:%(tracking)sem;
          fill:%(muted_deep)s; }
    /* A tracked capital is a heading; a unit is case-sensitive and is not one.
       «кВт·ч», «об/мин», «км» are set as themselves - and so is the odometer's own
       figure inside «42 км · ЗА ПОЕЗДКУ», which is a number living in a caption
       rather than a reading of its own, so it takes the caption's face and the
       tabular figures every number on this panel has. */
    .un { font-size:%(caption)spx; font-weight:400; fill:%(muted_deep)s;
          font-variant-numeric:tabular-nums; font-feature-settings:'tnum'; }
    .keep { font-size:%(note)spx; letter-spacing:%(tracking)sem; fill:#3F434D; }
    /* Used on an HTML div as well as in SVG, and `fill` does nothing to a div. */
    .sn { font-size:%(note)spx; letter-spacing:%(tracking)sem; fill:%(muted_deep)s;
          color:%(muted_deep)s; }
    /* Annotations name the very lines they stand on, so each one carries a black
       halo through paint-order rather than a measured backing rect. */
    .nt { font-size:%(note)spx; fill:%(muted_deep)s; stroke:%(bg)s; stroke-width:4px;
          paint-order:stroke fill; }
  </style>
</helmet>
"""


def css():
    return HEAD % dict(bg=BG, ink=INK, muted=MUTED, muted_deep=MUTED_DEEP,
                       hero=f(HERO), figure=f(FIGURE), reading=f(READING),
                       caption=f(CAPTION), note=f(NOTE), tracking=TRACKING)


def panel(body, width=None, height=None):
    w, h = f(width or W), f(height or H)
    return (f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}">\n      ' +
            '\n      '.join(body) + '\n    </svg>')


def page(width, height, inner):
    w, h = f(width), f(height)
    return (css() +
            f'<div style="width:{w}px; height:{h}px; background:{BG}; position:relative;">\n  ' +
            inner + '\n</div>\n</x-dc>\n'
            f'<script data-dc-script data-props=\'{{"$preview":{{"width":{w},"height":{h}}}}}\'>\n'
            'class Component extends DCLogic { renderVals() { return {}; } }\n'
            '</script>\n</body>\n</html>\n')


# ---------------------------------------------------------------- the states

def temps(pack, motors, inverter):
    """Three cells' worth of temperature, each value with its own level."""
    return dict(pack=pack, motors=motors, inverter=inverter)


COOL = temps(('28', 'normal'),
             (('31', 'normal'), ('29', 'normal'), ('31', 'normal')),
             ('32', 'normal'))
WORKED = temps(('29', 'normal'),
               (('44', 'normal'), ('41', 'normal'), ('46', 'normal')),
               ('51', 'normal'))
PARKED = temps(('31', 'normal'),
               (('24', 'normal'), ('23', 'normal'), ('24', 'normal')),
               ('26', 'normal'))
HOT = temps(('33', 'normal'),
            (('61', 'normal'), ('68', 'watch'), ('64', 'normal')),
            ('92', 'alert'))

# The unit carries the window. «кВт·ч/100 км» alone was a rate with no interval on
# it, and a rate with no interval is read against the trip - and «за 3 км» over a
# history five hundred metres long is the same defect one level down, which is why
# `per_100` names the road the log actually has.
PER_100 = 'кВт·ч/100 км · за 3 км'

CALM_BARS = consumption_history(16.8)


def sc(**kw):
    """One scene, with the "has this ever arrived" flags filled in.

    Alpha is not a state channel any more (M5). A value that has gone stale is
    removed after two seconds and its caption stays, which is one rule covering a
    single null, a dead bus and a sensor that never woke - and a heading appears
    with its first value, so the first seconds of a drive are the band's skeleton
    and nothing else rather than four headings over emptiness (m4).
    """
    s = dict(kw)
    s.setdefault('petal_unit', per_100(s.get('bars')))
    defaults = {
        'power_known': s.get('kw') is not None,
        'volts_known': s.get('volts') is not None,
        'temps_known': s.get('temps') is not None,
        'trip_known': s.get('trip') is not None or bool(s.get('trace')),
        'ice_known': s.get('ice') is not None,
        'petal_known': s.get('petal') is not None or bool(s.get('bars')),
    }
    for key, value in defaults.items():
        s.setdefault(key, value)
    return s


# `net` is what left the pack for good: the gross 12,4 less the 3,1 regeneration
# gave back. The engine's own share, when there is one, is out of it too - the
# other two cells report what came back, not what adds up.
CALM = sc(kw=34.0, peak=68.0, volts=552.0, temps=COOL,
          trip=dict(net=9.3, regen=3.1, ice=0.0, km=42),
          bars=CALM_BARS, petal='17')

STATES = [
    ('Первые секунды · шина ещё не ответила: скелет ленты, и больше ничего',
     sc(kw=None)),
    ('Пробка · ДВС отработал раньше и заглох давно: полка — две ячейки, коробки нет',
     sc(kw=2.0, volts=548.0, temps=COOL, ice='slept', ice_minutes=3.0,
        trip=dict(net=5.9, regen=2.2, ice=0.6, km=27),
        bars=consumption_history(21.4), petal='21')),
    ('Спокойная езда · ДВС не запускался: одна фраза справа, «ДАЛ ДВС» нет вовсе',
     CALM),
    # The window is what the log has closed, not what it is sized for. Twelve buckets
    # is 1,2 km of road, the history grows leftward into its box rather than
    # stretching across it, and the unit says which road the figure is the mean of.
    ('Первые километры · закрыто 12 буферов: единица называет 1,2 км, а не 3',
     sc(kw=22.0, peak=41.0, volts=549.0, temps=COOL,
        trip=dict(net=1.4, regen=0.4, ice=0.0, km=6),
        bars=consumption_history(18.6)[:12], petal='19')),
    ('Разгон · 128 кВт, пик-холд стоит впереди кончика и сползает к нему',
     sc(kw=128.0, peak=163.0, volts=531.0, temps=WORKED,
        trip=dict(net=10.0, regen=3.1, ice=0.0, km=45),
        bars=consumption_history(20.4), petal='20')),
    ('Рекуперация · сторона и цвет меняются, не появляется ничего',
     sc(kw=-42.0, peak=-58.0, volts=573.0, temps=COOL,
        trip=dict(net=9.2, regen=3.4, ice=0.0, km=43),
        bars=consumption_history(11.2), petal='11')),
    ('ДВС генерирует 82 с · генерация отдельной линией под лентой, по шкале возврата; '
     'коробка выросла справа на 17 ступеней по 5 с',
     sc(kw=28.0, peak=52.0, ice='running', rpm=1780.0, generation=14.0,
        trace=engine_history(82), volts=548.0, temps=WORKED,
        bars=consumption_history(17.4), petal='17')),
    ('ДВС заглох 40 с назад · коробка ещё здесь, у правого края ступени на нуле; '
     'цифра, «кВт» и её резерв ушли — точка встала к словам',
     sc(kw=34.0, peak=68.0, ice='slept', ice_minutes=6.0,
        trace=engine_history(120, stopped=40), volts=551.0, temps=WORKED,
        bars=consumption_history(17.1), petal='17')),
    ('Шов на ленте · как это выглядит, если генерация НЕ входит в мощность пакета',
     sc(kw=28.0, peak=52.0, ice='running', rpm=1780.0, generation=14.0,
        seam_on_band=True, trace=engine_history(82), volts=548.0, temps=WORKED,
        bars=consumption_history(17.4), petal='17')),
    ('Стоянка на P · полный расклад тремя ячейками, у лепестка появилась десятая',
     sc(kw=1.4, volts=561.0, temps=COOL, parked=True,
        ice='slept', ice_minutes=6.0,
        trip=dict(net=9.3, regen=3.1, ice=1.1, km=42),
        bars=CALM_BARS, petal='16,8')),
    ('Зарядка от розетки · полка как на ходу, коробка расхода остаётся прежней',
     sc(kw=-7.0, volts=584.0, temps=PARKED,
        trip=dict(net=9.3, regen=3.1, ice=0.0, km=42),
        bars=CALM_BARS, petal='2:15', petal_unit='до полной')),
    # Two things at once, and both of them are what the seat does when the ordinary
    # case is absent. A car that has stood on P since the app was installed has moved
    # no odometer, so there are no closed buckets and no history to draw - and the
    # countdown is not about the road, so it stands anyway. And an estimate of ten
    # hours or more is written in hours alone: «12:30» is five glyphs against a field
    # of three and a mark, and the field cannot grow without pushing the history box
    # out of the petal's own cut-out.
    ('Зарядка без истории · буферов расхода ещё нет, отсчёт всё равно стоит; '
     'оценка больше десяти часов — часы без минут',
     sc(kw=-3.5, volts=571.0, temps=PARKED,
        trip=dict(net=9.3, regen=3.1, ice=0.0, km=42),
        bars=None, petal='12 ч', petal_unit='до полной')),
    ('Одиночный null · напряжение снято через 2 с, заголовок «БАТАРЕЯ · В» стоит',
     sc(kw=34.0, peak=68.0, volts=None, volts_known=True, temps=COOL,
        trip=dict(net=9.3, regen=3.1, ice=0.0, km=42),
        bars=CALM_BARS, petal='17')),
    ('Потеря связи · через 2 с сняты все значения; подписи остались и не потускнели',
     sc(kw=None, power_known=True, volts=None, volts_known=True,
        temps=None, temps_known=True, trip=None, trip_known=True,
        ice=None, ice_known=False, bars=None, petal=None, petal_known=True,
        petal_unit=PER_100)),
    ('Исключение · моторы 68° оранжевым, инвертор 92° красным, «РАЗБРОС ЯЧЕЕК» — чей',
     sc(kw=34.0, peak=68.0, volts=552.0, temps=HOT, spread=('44', 'alert'),
        trip=dict(net=9.3, regen=3.1, ice=0.0, km=42),
        bars=CALM_BARS, petal='17')),
    ('Нет ADB-ключа · указание, что сделать — не сообщение об ошибке',
     sc(kw=None, hint='ADB-ключ не подтверждён · Помощь → Диагностика')),
]

STATE_LABEL = 34.0
STATE_PITCH = H + STATE_LABEL + STEP * 4


# ---------------------------------------------------------------- the plan board

def plan_board():
    """The skeleton with every number on it, and the apertures it is placed against."""
    body = keepout()
    body += skeleton()

    body += [
        f'<ellipse cx="0" cy="0" rx="{f(LEFT_RX)}" ry="{f(APERTURE_RY)}" fill="none" '
        f'stroke="{RETURN}" stroke-width="1.2" stroke-dasharray="6 6" opacity="0.7"/>',
        f'<ellipse cx="{f(W)}" cy="0" rx="{f(RIGHT_RX)}" ry="{f(APERTURE_RY)}" fill="none" '
        f'stroke="{RETURN}" stroke-width="1.2" stroke-dasharray="6 6" opacity="0.7"/>',
        f'<ellipse cx="{f(AXIS)}" cy="{f(PETAL_CY)}" rx="{f(PETAL_RX)}" ry="{f(PETAL_RY)}" '
        f'fill="none" stroke="{RETURN}" stroke-width="1.2" stroke-dasharray="6 6" '
        f'opacity="0.7"/>',
        line(0, STOCK_TOP, W, STOCK_TOP, RETURN, 1.2, 0.5),
        line(0, STOCK_BOTTOM, W, STOCK_BOTTOM, RETURN, 1.2, 0.5),
        line(0, GUARD_TOP, W, GUARD_TOP, DANGER, 1.2, 0.55),
        line(0, GUARD_BOTTOM, W, GUARD_BOTTOM, DANGER, 1.2, 0.55),
        line(0, PETAL_FLOOR, W, PETAL_FLOOR, WARNING, 1.2, 0.5),
    ]

    def outline(x, y, w, h, dash='4 5', colour=None, opacity=0.45):
        return (f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" fill="none" '
                f'stroke="{colour or RETURN}" stroke-width="1.2" stroke-dasharray="{dash}" '
                f'opacity="{f(opacity)}"/>')

    # The cell grid both shelves stand on, drawn rather than described. On the right
    # there are two grids over one edge: the three seats of a car standing on P in
    # solid dashes, and the pair it keeps on the move - «ЗА ПОЕЗДКУ» and «ДАЛ ДВС» -
    # in the finer one. Seat 0 is the same cell in both, which is the rule.
    cell_top = SHELF_FIGURE - CAP * READING
    cell_h = SHELF_CAPTION - SHELF_FIGURE + CAP * READING + 6
    for index in range(len(LEFT_CELLS)):
        left, right = left_cell(index)
        body.append(outline(left, cell_top, right - left, cell_h))
    for index in range(len(PARK_SEATS)):
        left, right = trip_cell(index, PARK_SEATS)
        body.append(outline(left, cell_top, right - left, cell_h))
    left, right = trip_cell(1, DRIVE_SEATS)
    body.append(outline(left, cell_top - 6, right - left, cell_h + 12, dash='2 4',
                        opacity=0.7))
    # And the box each glyph stands in: 17k wide, 24 tall, on the caption baseline,
    # one unit inside its own cell. The five are identical and only the drawing differs.
    for index in range(len(GLYPHS)):
        left, _ = left_cell(index)
        body.append(outline(left + GLYPH_INSET, SHELF_CAPTION - GLYPH, GLYPH_W, GLYPH,
                            dash='2 4', opacity=0.7))

    # The two boxes the histories live in, and the two big fields.
    body.append(outline(ENGINE_BOX_FULL_LEFT, ENGINE_BOX_TOP,
                        ENGINE_BOX_RIGHT - ENGINE_BOX_FULL_LEFT,
                        ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP, dash='2 4', colour=WARNING,
                        opacity=0.8))
    body.append(outline(PETAL_BOX_X, PETAL_BOX_TOP, PETAL_BOX_W,
                        PETAL_BOX_BOTTOM - PETAL_BOX_TOP, dash='2 4', colour=WARNING,
                        opacity=0.8))
    body.append(outline(HERO_FIELD_LEFT, HERO_CAP_TOP, HERO_FIELD_W,
                        HERO_BASELINE - HERO_CAP_TOP))
    # Two fields, one anchor: the two digits that centre on the axis, and the
    # reserve the tenth grows into on P. The box hangs off the outer one.
    body.append(outline(PETAL_FIGURE_RIGHT - PETAL_FIELD_W, PETAL_BASELINE - CAP * FIGURE,
                        PETAL_FIELD_W, CAP * FIGURE))
    body.append(outline(PETAL_FIGURE_RIGHT - PETAL_BASE_FIELD_W,
                        PETAL_BASELINE - CAP * FIGURE, PETAL_BASE_FIELD_W,
                        CAP * FIGURE, dash='2 4', opacity=0.7))
    body.append(line(PETAL_BOX_X, PETAL_ZERO_Y, PETAL_BOX_X + PETAL_BOX_W,
                     PETAL_ZERO_Y, WARNING, 1.2, 0.5))

    right_lane = AXIS + 140
    marks = [
        (LEFT_EDGE, CORNER_TITLE, f'заголовок угла · 18 · y {CORNER_TITLE:.0f} · '
                                  f'единица и окно живут здесь'),
        (LEFT_EDGE, CORNER_TITLE, f'«ДВС · мин за поездку» '
                                  f'{W_TITLE["ДВС · мин за поездку"]:.1f} · вырез на '
                                  f'этой базовой даёт '
                                  f'{aperture_reach(CORNER_TITLE, True) - MARGIN:.1f} → '
                                  f'запас '
                                  f'{aperture_reach(CORNER_TITLE, True) - MARGIN - W_TITLE["ДВС · мин за поездку"]:.1f}'),
        (LEFT_EDGE, CORNER_FIGURE, f'цифра угла · 52 · y {CORNER_FIGURE:.0f} · вольты '
                                   f'3 знака {LEFT_FIELD_X:.0f}…{LEFT_FIELD_RIGHT:.1f} · '
                                   f'обороты 4 знака {RIGHT_FIELD_LEFT:.1f}…'
                                   f'{RIGHT_FIELD_RIGHT:.1f}'),
        (LEFT_EDGE, CORNER_FIGURE, 'третьей строки угла больше нет; обороты живут '
                                   'здесь одни — линии в коробке для них нет'),
        (LEFT_EDGE, GUARD_TOP, f'запас {CLEARANCE:.0f} · stockTop {STOCK_TOP:.2f} → '
                               f'{GUARD_TOP:.2f} · на нём стоят герой, обе полки и '
                               f'коробка ДВС'),
        (LEFT_EDGE, SHELF_FIGURE, f'цифры полок · 34 Regular · y {SHELF_FIGURE:.2f} · '
                                  f'ячейка = шире из нагрузки и того, что её называет, '
                                  f'без округления к ритму · зазор {CELL_GAP:.0f}'),
        (LEFT_EDGE, SHELF_FIGURE, f'слева пять по {TEMP_CELL:.1f} = поле {TEMP_FIELD:.1f} '
                                  f'+ «°» {W_DEGREE:.1f} (глиф {GLYPH_W:.1f} уже), плюс '
                                  f'исключение {SPREAD_CELL:.1f} → до поля героя '
                                  f'{HERO_FIELD_LEFT - LEFT_SHELF_RIGHT:.1f}'),
        (LEFT_EDGE, SHELF_FIGURE, f'справа на P {" / ".join(f"{w:.1f}" for w in PARK_SEATS)}, '
                                  f'на ходу {" / ".join(f"{w:.1f}" for w in DRIVE_SEATS)} · '
                                  f'место 0 всегда у края {RIGHT_EDGE:.1f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'подписи полок · 18 капителью · y '
                                   f'{SHELF_CAPTION:.2f} · слов в строке температур '
                                   f'нет: пять глифов {GLYPH_W:.1f} × {GLYPH:.0f} стоят '
                                   f'на этой базовой, штрих {GLYPH_STROKE:.1f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'глиф от базовой цифр {SHELF_FIGURE:.2f} отделяют '
                                   f'{SHELF_CAPTION - GLYPH - SHELF_FIGURE:.1f} · контур '
                                   f'MUTED, компонент INK или цвет исключения вместе '
                                   f'с цифрой'),
        (LEFT_EDGE, SHELF_CAPTION, f'километры впереди: одометр {ODO_FIELD:.1f} + «км» '
                                   f'{W_KM:.1f} + «· ЗА ПОЕЗДКУ» '
                                   f'{W_CAPTION["· ЗА ПОЕЗДКУ"]:.1f} = {TRIP_LEAD:.1f}; '
                                   f'цифра — нетто, нагрузка {TRIP_PAYLOAD:.1f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'«● РЕКУПЕРАЦИЯ» '
                                   f'{MARK_W + W_CAPTION["РЕКУПЕРАЦИЯ"]:.1f} и «ДАЛ ДВС» '
                                   f'{W_CAPTION["ДАЛ ДВС"]:.1f} — что вернулось; '
                                   f'«ВЕРНУЛА РЕКУПЕРАЦИЯ» 253.5 не влезает'),
        (right_lane, ENGINE_BOX_TOP, f'коробка ДВС · оба ряда полки, '
                                     f'{ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP:.1f} высотой · '
                                     f'{ENGINE_BINS} ступеней по '
                                     f'{ENGINE_BIN_SECONDS} с, шаг '
                                     f'{ENGINE_PITCH:.2f}, растёт справа'),
        (right_lane, ENGINE_BOX_TOP, f'только генерация: 0…{ENGINE_GEN_FULL:.0f} кВт '
                                     f'линейно с ограничением · осей нет · '
                                     f'14 кВт = {14 / ENGINE_GEN_FULL:.0%} высоты'),
        (right_lane, ENGINE_LEGEND, f'фраза под коробкой · 18 · базовая '
                                    f'{ENGINE_LEGEND:.2f} = запас {CLEARANCE:.0f} над '
                                    f'лентой'),
        (right_lane, ENGINE_LEGEND, f'«● 14 кВт {LEGEND_WINDOW}» '
                                    f'{legend_phrase(LEGEND_WINDOW):.1f} + {CLEARANCE:.0f} '
                                    f'в {ENGINE_BOX_RIGHT - ENGINE_BOX_FULL_LEFT:.1f}; '
                                    f'уже — «· 2 МИН»'),
        (right_lane, ENGINE_LEGEND, f'точка на {legend_anchors()["mark"]:.1f} при цифре и '
                                    f'на {legend_anchors()["quiet"]:.1f} без неё: резерв '
                                    f'{GEN_FIELD:.1f} уходит вместе с числом, слова стоят'),
        (right_lane, HERO_BASELINE, f'герой · 88 Light · базовая {HERO_BASELINE:.2f} · '
                                    f'поле {HERO_FIELD_LEFT:.1f}…{HERO_FIELD_RIGHT:.1f} · '
                                    f'«кВт» 34 на {HERO_UNIT_X:.1f}'),
        (right_lane, BAND_Y, f'лента · y {BAND_Y:.2f} · тело {BAND_BODY:.0f} · корень, '
                             f'300 / 100 · мёртвая зона {NEUTRAL_KW:.0f} кВт'),
        (right_lane, GLOW_CY, f'свечение · центр на нуле · rx {GLOW_RX:.0f}, ry '
                              f'{GLOW_RY:.2f} · {GLOW_MAX:.2f}·√(|P|/'
                              f'{GLOW_FULL_KW:.0f} кВт), насыщение на '
                              f'{GLOW_FULL_KW:.0f}, τ 1,5 с'),
        # Two marks that name a line running the whole width hang off the left lane,
        # because the right one is the crowded one: it gained the legend's line in
        # the sixth pass and the petal's window in the seventh, and each time its
        # last mark was the one falling off the panel's floor.
        (LEFT_EDGE, GUARD_BOTTOM, f'запас {CLEARANCE:.0f} снизу · stockBottom '
                                  f'{STOCK_BOTTOM:.2f} → {GUARD_BOTTOM:.2f} · низ ленты '
                                  f'{BAND_Y + BAND_BODY / 2:.2f}'),
        (LEFT_EDGE, PETAL_BOX_TOP, 'серое — расход по всем корзинам, непрерывно; '
                                   'синее — только там, где вернули, со стойками '
                                   'от нуля'),
        (LEFT_EDGE, PETAL_FLOOR, f'пол композиции · y {PETAL_FLOOR:.0f} · низ коробки '
                                 f'{PETAL_BOX_BOTTOM:.1f}, до выреза '
                                 f'{PETAL_BOX_X - petal_room(PETAL_BOX_BOTTOM):.1f}'),
        (right_lane, PETAL_BOX_TOP, f'коробка расхода · {PETAL_BOX_W:.0f} × '
                                    f'{PETAL_BOX_H:.2f} на {PETAL_BOX_X:.1f}…'
                                    f'{PETAL_BOX_RIGHT:.1f} · {PETAL_BUCKETS} корзин по '
                                    f'{PETAL_BOX_W / PETAL_BUCKETS:.2f}'),
        (right_lane, PETAL_BOX_TOP, f'нуль = базовая цифры {PETAL_ZERO_Y:.0f} · вверх '
                                    f'капитель {CAP * FIGURE:.2f} на 0…{PETAL_FULL:.0f}, '
                                    f'вниз выносной {PETAL_DESCENDER * FIGURE:.2f} на 0…'
                                    f'{PETAL_RETURN_FULL:.0f}'),
        (right_lane, PETAL_BASELINE, f'лепесток · 52 · два знака на оси, поле '
                                     f'{PETAL_FIELD_W:.1f} влево'),
        (right_lane, PETAL_BASELINE, f'«кВт·ч/100 км · за 1,2 км» {PETAL_UNIT_W:.1f} на '
                                     f'{PETAL_UNIT_X:.1f}…'
                                     f'{PETAL_UNIT_X + PETAL_UNIT_W:.1f}, до выреза '
                                     f'{petal_edge(PETAL_BASELINE) - PETAL_UNIT_X - PETAL_UNIT_W:.1f}'),
    ]
    lanes = {}
    for x, y, words in sorted(marks, key=lambda m: (m[0], m[1])):
        floor = lanes.get(x)
        text_y = y if floor is None else max(y, floor + NOTE * 1.5)
        lanes[x] = text_y
        body.append(line(x - 14, y, x - 8, y, WARNING, 1.2, 0.8))
        body.append(line(x - 8, y, x - 8, text_y, WARNING, 1.2, 0.4))
        body.append(line(x - 8, text_y, x - 3, text_y, WARNING, 1.2, 0.4))
        body.append(txt('nt', x, text_y + NOTE * 0.36, words))
    return body


LEGEND_H = 184.0


def plan_legend():
    """The physical constants and the ramp they produce, under the panel.

    They used to sit inside the artboard and they were the first thing every
    annotation ran into. A plan board is allowed a margin the panel does not have.
    """
    body = [
        txt('nt', LEFT_EDGE, 30, f'панель {W:.1f} × {H:.0f} · шаг {STEP:.0f} · поле '
                                 f'{MARGIN:.0f} · лесенка кластера 88 · 52 · 34 · 18'),
        txt('nt', LEFT_EDGE, 52, f'стекло {GLASS_WIDTH_MM:.0f} мм, глаз '
                                 f'{EYE_DISTANCE_MM:.0f} мм — рулетка владельца '
                                 f'04.09.2026 → 1 единица = {UNIT_MM:.4f} мм'),
        txt('nt', LEFT_EDGE, 74, f'капитель {CAP:.2f} em · 1′ на {EYE_DISTANCE_MM:.0f} мм '
                                 f'= {ARCMIN_MM:.4f} мм · угловой размер капители = '
                                 f'кегль × {arcminutes(1.0):.3f}′'),
        txt('nt', LEFT_EDGE, 104, 'ни одна координата не зависит от данных: поля по '
                                  'максимальной разрядности, соседи — к границе поля'),
        txt('nt', LEFT_EDGE, 126, f'цифра Roboto табличная: {DIGIT:.4f} кегля Regular, '
                                  f'{DIGIT_LIGHT:.4f} Light · запятая {COMMA:.4f} · '
                                  f'двоеточие {COLON:.4f} · моноширинная была '
                                  f'{MONO:.4f} на всё'),
        txt('nt', LEFT_EDGE, 156, 'синим пунктиром — апертуры и сетка ячеек · оранжевым '
                                  '— коробки историй · красным — оба запаса 24 · '
                                  'штриховкой — где рисует машина'),
    ]
    table_x = 1000.0
    head = (('кегль', table_x + 60, 'end'), ('мм', table_x + 150, 'end'),
            ('угл. мин', table_x + 250, 'end'), ('где', table_x + 274, 'start'))
    for words, x, anchor in head:
        body.append(txt('nt', x, 30, words, anchor))
    where = {HERO: 'герой', FIGURE: 'углы, лепесток', READING: 'полки',
             CAPTION: 'заголовки, подписи', NOTE: 'только доски'}
    for index, (size, mm, minutes) in enumerate(type_table()):
        y = 52 + index * 22
        body.append(txt('nt', table_x + 60, y, f'{size:.0f}', 'end'))
        body.append(txt('nt', table_x + 150, y, f'{mm:.2f}', 'end'))
        body.append(txt('nt', table_x + 250, y, f'{minutes:.1f}', 'end'))
        body.append(txt('nt', table_x + 274, y, where[size], 'start'))
    body.append(txt('nt', table_x, 52 + len(type_table()) * 22 + 8,
                    'ISO 15008: порог 20′, комфорт 30′'))
    return body


# ---------------------------------------------------------------- emit

def board_contour():
    return page(W, H, panel(scene(CALM)))


def board_states():
    height = STATE_PITCH * len(STATES)
    parts = []
    for index, (label, s) in enumerate(STATES):
        top = index * STATE_PITCH
        parts.append(
            f'<div style="position:absolute; left:0; top:{f(top)}px; '
            f'width:{f(W)}px; height:{f(STATE_PITCH)}px;">'
            f'<div class="sn" style="padding:{f(STEP * 2)}px 0 0 {f(LEFT_EDGE)}px;">'
            f'{label}</div>'
            + panel(scene(s)) + '</div>')
    return page(W, height, '\n  '.join(parts))


def board_plan():
    inner = (f'<div style="position:absolute; left:0; top:0;">'
             + panel(plan_board()) + '</div>\n  '
             f'<div style="position:absolute; left:0; top:{f(H)}px;">'
             + panel(plan_legend(), W, LEGEND_H) + '</div>')
    return page(W, H + LEGEND_H, inner)


if __name__ == '__main__':
    open('ClusterContour.dc.html', 'w').write(board_contour())
    open('ClusterContourStates.dc.html', 'w').write(board_states())
    open('ClusterContourPlan.dc.html', 'w').write(board_plan())

    print(f'panel {W:.4f} x {H:.0f}, axis {AXIS:.4f}, unit {UNIT_MM:.4f} mm, '
          f'1 arcmin {ARCMIN_MM:.4f} mm')
    print('ramp    size      mm    arcmin')
    for size, mm, minutes in type_table():
        print(f'      {size:6.0f}  {mm:6.2f}  {minutes:8.1f}')
    print(f'guards  top {GUARD_TOP:.2f} (stock {STOCK_TOP:.2f}), '
          f'bottom {GUARD_BOTTOM:.2f} (stock {STOCK_BOTTOM:.2f})')
    print(f'hero    baseline {HERO_BASELINE:.2f}, cap top {HERO_CAP_TOP:.2f} '
          f'(= guard), field {HERO_FIELD_LEFT:.2f}…{HERO_FIELD_RIGHT:.2f}, '
          f'«кВт» at {HERO_UNIT_X:.2f}, group {HERO_GROUP_W:.2f}')
    print(f'band    y {BAND_Y:.2f}, body {BAND_Y - BAND_BODY / 2:.2f}…'
          f'{BAND_Y + BAND_BODY / 2:.2f}, clear of the lower guard by '
          f'{GUARD_BOTTOM - (BAND_Y + BAND_BODY / 2):.2f}, glow ry {GLOW_RY:.2f}')
    print(f'corners title {CORNER_TITLE:.0f}, figure {CORNER_FIGURE:.0f}, '
          f'no third line')
    print(f'  left  field {LEFT_FIELD_X:.2f}…{LEFT_FIELD_RIGHT:.2f}, '
          f'aperture at the figure {aperture_reach(CORNER_FIGURE):.2f}')
    print(f'  right field {RIGHT_FIELD_LEFT:.2f}…{RIGHT_FIELD_RIGHT:.2f}, '
          f'aperture leaves {RIGHT_EDGE - (W - aperture_reach(CORNER_FIGURE, True)):.2f} '
          f'for {4 * DIGIT * FIGURE:.2f}')
    _title_room = RIGHT_EDGE - (W - aperture_reach(CORNER_TITLE, True))
    print(f'  title «ДВС · мин за поездку» {W_TITLE["ДВС · мин за поездку"]:.2f} in '
          f'{_title_room:.2f} of aperture at y {CORNER_TITLE:.0f} -> clear by '
          f'{_title_room - W_TITLE["ДВС · мин за поездку"]:.2f} '
          f'(«ДВС · об/мин» {W_TITLE["ДВС · об/мин"]:.2f})')
    print(f'shelves figures {SHELF_FIGURE:.2f} (cap top '
          f'{SHELF_FIGURE - CAP * READING:.2f}), captions {SHELF_CAPTION:.2f}')
    print(f'  left  cells {[f"{w:.2f}" for w in LEFT_CELLS]} -> '
          f'{LEFT_EDGE:.0f}…{LEFT_SHELF_RIGHT:.2f}, clear of the hero field by '
          f'{HERO_FIELD_LEFT - LEFT_SHELF_RIGHT:.2f} (the jury asks 24)')
    _plain = LEFT_EDGE + len(GLYPHS) * TEMP_CELL + (len(GLYPHS) - 1) * CELL_GAP
    print(f'        five glyph cells alone reach {_plain:.2f}, clear by '
          f'{HERO_FIELD_LEFT - _plain:.2f}; with «РАЗБРОС ЯЧЕЕК» '
          f'{HERO_FIELD_LEFT - LEFT_SHELF_RIGHT:.2f}')
    print(f'glyphs  {GLYPH_W:.2f} x {GLYPH:.0f} at k {GLYPH_K:.4f}, stroke '
          f'{GLYPH_STROKE:.1f} (wheels {WHEEL_STROKE:.1f}), inset {GLYPH_INSET:.0f} '
          f'in a cell of {TEMP_CELL:.2f}')
    print(f'        on the caption baseline {SHELF_CAPTION:.2f}: top '
          f'{SHELF_CAPTION - GLYPH:.2f}, which is '
          f'{SHELF_CAPTION - GLYPH - SHELF_FIGURE:.2f} under the figures\' own '
          f'baseline {SHELF_FIGURE:.2f}')
    print(f'        pack {PACK_W - PACK_NUB:.2f} + {PACK_NUB:.2f} x {PACK_H:.2f}, '
          f'body {BODY_W:.2f} x {BODY_H:.2f}, wheel {WHEEL_W:.2f} x {WHEEL_H:.2f}, '
          f'block {MOTOR_H:.2f} tall, inverter {INVERTER_S:.2f}')
    print(f'  right seats on P {[f"{w:.2f}" for w in PARK_SEATS]} -> '
          f'{RIGHT_SHELF_LEFT:.2f}…{RIGHT_EDGE:.0f}, clear of the hero unit by '
          f'{RIGHT_SHELF_LEFT - (HERO_UNIT_X + HERO_UNIT_W):.2f}')
    print(f'        on the move {[f"{w:.2f}" for w in DRIVE_SEATS]} -> '
          f'{trip_cell(len(DRIVE_SEATS) - 1, DRIVE_SEATS)[0]:.2f}…{RIGHT_EDGE:.0f}; '
          f'seat 0 lead {TRIP_LEAD:.2f} against a payload of {TRIP_PAYLOAD:.2f}')
    _verb = max(TRIP_PAYLOAD, MARK_W + 253.5469)
    _verb_left = RIGHT_EDGE - (TRIP_CELL + _verb + ICE_CELL) - 2 * CELL_GAP
    print(f'        «● ВЕРНУЛА РЕКУПЕРАЦИЯ» would be {_verb:.2f} -> shelf at '
          f'{_verb_left:.2f}, clear of the hero unit by '
          f'{_verb_left - (HERO_UNIT_X + HERO_UNIT_W):.2f}: rejected, the rule asks 24')
    print(f'engine  box {ENGINE_BOX_FULL_LEFT:.2f}…{ENGINE_BOX_RIGHT:.2f} x '
          f'{ENGINE_BOX_TOP:.2f}…{ENGINE_BOX_BOTTOM:.2f} = '
          f'{ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP:.2f} tall, {ENGINE_BINS} steps of '
          f'{ENGINE_BIN_SECONDS} s at {ENGINE_PITCH:.2f}, 82 s wide = '
          f'{len(engine_bins(engine_history(82))) * ENGINE_PITCH:.1f}')
    print(f'  scale   generation only, linear 0…{ENGINE_GEN_FULL:.0f} kW clamped: '
          f'14 kW is {14 / ENGINE_GEN_FULL * (ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP):.1f} '
          f'of {ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP:.1f} '
          f'(a root over 100 gave {math.sqrt(0.14) * (ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP):.1f})')
    print(f'  phrase  {ENGINE_LEGEND:.2f}, {CLEARANCE:.0f} over the band body, caps '
          f'from {ENGINE_LEGEND - CAP * CAPTION:.2f}, box stops '
          f'{ENGINE_LEGEND - CAP * CAPTION - ENGINE_BOX_BOTTOM:.2f} above them; the '
          f'shelf baselines {SHELF_FIGURE:.2f}/{SHELF_CAPTION:.2f} do not move')
    print(f'          «● 14 кВт {LEGEND_WINDOW}» {legend_phrase(LEGEND_WINDOW):.2f} + '
          f'{CLEARANCE:.0f} in {ENGINE_BOX_RIGHT - ENGINE_BOX_FULL_LEFT:.2f} -> '
          f'{legend_window()} (short would be {legend_phrase(LEGEND_WINDOW_SHORT):.2f})')
    _at = legend_anchors()
    print(f'          dot at {_at["mark"]:.2f} with the figure, {_at["quiet"]:.2f} '
          f'without it: the {GEN_FIELD:.2f} reserve leaves with the number and the '
          f'words do not move')
    print(f'petal   figure {PETAL_BASE_FIELD_W:.2f} centred on the axis: ends '
          f'{PETAL_FIGURE_RIGHT:.2f} against the hero\'s {HERO_FIELD_RIGHT:.2f} '
          f'({PETAL_FIGURE_RIGHT - HERO_FIELD_RIGHT:+.2f}), reserve to '
          f'{PETAL_FIGURE_RIGHT - PETAL_FIELD_W:.2f}, unit {PETAL_UNIT_X:.2f}…'
          f'{PETAL_UNIT_X + PETAL_UNIT_W:.2f}')
    print(f'  «кВт·ч/100 км · за 1,2 км» {PETAL_UNIT_W:.2f} (the widest) ends '
          f'{PETAL_UNIT_X + PETAL_UNIT_W:.2f}, the cut-out\'s right edge at the '
          f'baseline {petal_edge(PETAL_BASELINE):.2f} -> clear by '
          f'{petal_edge(PETAL_BASELINE) - PETAL_UNIT_X - PETAL_UNIT_W:.2f} '
          f'(at the descender {petal_edge(PETAL_BASELINE + 0.271 * CAPTION) - PETAL_UNIT_X - PETAL_UNIT_W:.2f}; '
          f'the guard is 8)')
    print(f'  box {PETAL_BOX_X:.2f}…{PETAL_BOX_RIGHT:.2f} x {PETAL_BOX_TOP:.2f}…'
          f'{PETAL_BOX_BOTTOM:.2f} = {PETAL_BOX_W:.0f} x {PETAL_BOX_H:.2f}, gap '
          f'{PETAL_BOX_GAP:.0f} to the reserve, bucket '
          f'{PETAL_BOX_W / PETAL_BUCKETS:.2f}, floor leaves '
          f'{PETAL_FLOOR - PETAL_BOX_BOTTOM:.2f}')
    print(f'  aperture at the box top {petal_room(PETAL_BOX_TOP):.2f} -> clear by '
          f'{PETAL_BOX_X - petal_room(PETAL_BOX_TOP):.2f}, at its bottom '
          f'{petal_room(PETAL_BOX_BOTTOM):.2f} -> clear by '
          f'{PETAL_BOX_X - petal_room(PETAL_BOX_BOTTOM):.2f} (the guard is 8; 270 wide '
          f'would be {PETAL_BOX_RIGHT - 270 - petal_room(PETAL_BOX_BOTTOM):.2f})')
    print(f'  zero {PETAL_ZERO_Y:.2f} = the figure\'s own baseline, scale 0…'
          f'{PETAL_FULL:.0f} up the cap ({CAP * FIGURE:.2f}) at '
          f'{CAP * FIGURE / PETAL_FULL:.3f} units per kW·h/100 km, 0…'
          f'{PETAL_RETURN_FULL:.0f} down the descender '
          f'({PETAL_DESCENDER * FIGURE:.2f}) at '
          f'{PETAL_DESCENDER * FIGURE / PETAL_RETURN_FULL:.3f}')
    print(f'fields  digit {DIGIT * READING:.2f}/34 {DIGIT * FIGURE:.2f}/52 '
          f'{DIGIT_LIGHT * HERO:.2f}/88, comma {COMMA * FIGURE:.2f}/52, '
          f'trip {TRIP_FIELD:.2f}, temp {TEMP_FIELD:.2f}, petal {PETAL_FIELD_W:.2f}')
    for kw in (-100.0, -42.0, -3.0, 0.0, 3.0, 34.0, 128.0, 300.0):
        print(f'  {kw:7.1f} kW -> x {band_x(kw):8.2f}  glow '
              f'{glow_alpha(kw):.3f}  {flow_colour(kw)}')
