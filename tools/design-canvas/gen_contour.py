#!/usr/bin/env python3
"""
Emit the Contour cluster artboards from the constants the app draws with.

The Contour won the 2026-09 cluster contest (`docs/cluster-contest-2026-09/`).
Three drawings went to the owner, an independent review roasted the third
(`CRITIQUE.md`, blockers B1-B3, majors M1-M15, minors m1-m12), the fourth was the
answer to that review point by point, and this is the fifth: one complaint, five
edits, nothing else touched.

**The fifth pass, and what it is for.** The owner on the fourth drawing:
"Сценарии выглядят гораздо лучше, действительно удалось сделать хорошо. Но
графики очень сильно сплющены по вертикали и очень слабо читаются." Both graphs
were the same mistake twice - each one had been given the ink box of the digits it
stood beside, 36 units in the petal and 24 on the shelf, which is 7.6 mm and 5 mm
of glass for a shape with thirty steps or two curves in it. Height is the whole
fix, and both boxes now take every unit their neighbours can spare:

1. the petal's box is 56 units instead of 36, drawn as a 70 % INK line 2.5 thick
   over a 55 % MUTED_DEEP field instead of a 2-unit MUTED line over 30 %, on a
   fixed 0…40 kW·h/100 km ladder with the zero four fifths down, so the return
   keeps the bottom fifth and no bucket changes height because another one did;
2. the petal's figure centres on the axis by itself - two digits, right-aligned on
   a fixed anchor - and the box hangs off the field's widest reserve at 24, which
   is the jury's answer to "what does the box hang on now": asymmetry, deliberately;
3. the engine's box takes both rows of the shelf, 54 units from the top guard to a
   hair over the captions' baseline, and its legend moves below it onto the band's
   own 24-unit guard. No neighbour's baseline moves in either direction of the
   cells↔box swap;
4. an engine that ran and stopped reads «ДВС · мин» over «6» and nothing else. The
   corner's aperture leaves 138.2 units at that third baseline and «мин за поездку»
   asked for 130.4, which is eight units of margin on a boundary nobody has
   photographed - and the heading was already carrying the unit for the other two
   states, so it can carry this one;
5. the glow's span is its own now - `0.18·√(|P| / 120 kW)`, saturated at 120 -
   rather than the band's 300 out and 100 back, which made the same alpha mean two
   different powers by direction and left the whole calm half of the range dark.

One number in that list could not be had. The jury asked for a 270-unit petal box;
with the figure on the axis the petal's own cut-out leaves 238.5 at the box's lower
left corner once the 8-unit guard is taken, so the box is 232 - the next whole
rhythm step under the aperture. The width was never the complaint.

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

- B1  while the engine runs the petal's figure goes `MUTED` and its unit reads
      «кВт·ч/100 км · батарея»; the trip's `РЕКУПЕРАЦИЯ` cell is defined to
      integrate only over intervals with the engine off, and the engine's share
      is the `ОТ ДВС` cell. Written here as a data rule the renderer follows.
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
- M8  three fixed places on the right shelf, always. A zero is drawn, in
      `MUTED_DEEP`, in its own seat.
- M9  the sag line is deleted. «552 В» is what the owner asked for.
- M11 no small blue text anywhere. Blue is the band's body, the generation seam,
      the engine box's area, and a marker dot the size of a dot in front of
      `РЕКУПЕРАЦИЯ`, `ОТ ДВС` and `ГЕНЕРАЦИЯ`.
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
- m6  the hero's figure is limited to 2 Hz with 0.5 kW of hysteresis (a rule for
      the renderer, not something a still can show).
- m7  the night scene is removed. The cluster's own dimmer already darkens our
      window; whether it does is a measurement on the car, not a board.
- m8  an exception is a 34 figure changing colour on a shelf whose figures are all
      34, so it is the same glance as reading the temperature.
- m9  generation is drawn twice, not three times: the seam on the band and the
      area in the engine box. The corner's line is a number, not a picture.
- m11 unchanged and still true: three motors under one word in `motorTemps`
      order. Naming three positions costs three captions at 12'.

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
W_KWH = 44.1094                  # «кВт·ч» at 18, the trip shelf's one unit
W_PER_100KM = 109.4219           # «кВт·ч/100 км» at 18
W_TITLE = {'БАТАРЕЯ · В': 126.08, 'ДВС · об/мин': 137.98, 'ДВС · мин': 104.16}
W_CAPTION = {'БАТАРЕЯ': 91.92, 'МОТОРЫ': 90.63, 'ИНВЕРТОР': 110.00,
             'РАЗБРОС': 94.50, 'ИЗ БАТАРЕИ': 127.77, 'РЕКУПЕРАЦИЯ': 150.67,
             'ОТ ДВС': 76.78, 'ОБОРОТЫ': 101.05, 'ГЕНЕРАЦИЯ': 122.11}

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


def rungs(width):
    """The next whole rhythm step at or past [width]: a cell is never a string."""
    return math.ceil(width / STEP - 1e-9) * STEP


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

# ---- the corners: one heading, one figure, one line

# A row advances by the lead plus the *full* type size. A cap height is what the
# ink occupies; the box a browser and a Paint both reserve runs from ascent to
# descent, and spacing rows by cap height puts a heading's descenders inside the
# digits underneath it.
CORNER_TITLE = STEP * 3                 # 24
CORNER_FIGURE = CORNER_TITLE + STEP * 2 + FIGURE
CORNER_LINE = CORNER_FIGURE + STEP * 2 + CAPTION

LEFT_FIELD_X = LEFT_EDGE                        # volts: three digits
LEFT_FIELD_RIGHT = LEFT_FIELD_X + 3 * DIGIT * FIGURE

RIGHT_FIELD_RIGHT = RIGHT_EDGE                  # revolutions: four digits
RIGHT_FIELD_LEFT = RIGHT_FIELD_RIGHT - 4 * DIGIT * FIGURE
ICE_MINUTES_LEFT = RIGHT_EDGE - 3 * DIGIT * FIGURE

# ---- the two shelves, which are now one family in every respect

# Both hang from the same guard as the hero, both carry a 34 figure over an 18
# word, both stand on one pair of baselines. The last board gave them headings and
# two different figure sizes, and the headings were the first thing that would
# have been cut off by a boundary nobody has measured yet.
SHELF_FIGURE = GUARD_TOP + CAP * READING
SHELF_CAPTION = SHELF_FIGURE + STEP * 2 + CAPTION

CELL_GAP = STEP * 2
MOTOR_GAP = STEP                        # half the gap between cells, on purpose
MARK_R = 3.0                            # the blue marker: a dot, and dot-sized
MARK_GAP = STEP
MARK_W = 2 * MARK_R + MARK_GAP

TEMP_FIELD = 2 * DIGIT * READING        # two digits; 100+ is an alert and may hang
MOTOR_PITCH = TEMP_FIELD + W_DEGREE + MOTOR_GAP
MOTOR_RUN = 3 * (TEMP_FIELD + W_DEGREE) + 2 * MOTOR_GAP
SPREAD_PAYLOAD = TEMP_FIELD + STEP + W_MILLIVOLT

# A cell is as wide as the wider of the two things it has to hold - its caption or
# its payload - rounded up to the rhythm.
LEFT_CELLS = [rungs(max(W_CAPTION['БАТАРЕЯ'], TEMP_FIELD + W_DEGREE)),
              rungs(max(W_CAPTION['МОТОРЫ'], MOTOR_RUN)),
              rungs(max(W_CAPTION['ИНВЕРТОР'], TEMP_FIELD + W_DEGREE)),
              rungs(max(W_CAPTION['РАЗБРОС'], SPREAD_PAYLOAD))]
LEFT_SHELF_RIGHT = LEFT_EDGE + sum(LEFT_CELLS) + 3 * CELL_GAP

# Three seats, always, counted from the outside in - so the row reads out of the
# battery, back from the brakes, in from the engine, and none of the three moves
# when another gains or loses a value. The unit is written once, on a fixed anchor
# after the last digit of the row.
TRIP_FIELD = 3 * DIGIT * READING + COMMA * READING      # "12,4" at its widest
TRIP_CELLS = [rungs(max(TRIP_FIELD, MARK_W + W_CAPTION['ОТ ДВС'])),
              rungs(max(TRIP_FIELD, MARK_W + W_CAPTION['РЕКУПЕРАЦИЯ'])),
              rungs(max(TRIP_FIELD, W_CAPTION['ИЗ БАТАРЕИ']))]
TRIP_UNIT_X = RIGHT_EDGE
TRIP_ROW_RIGHT = RIGHT_EDGE - W_KWH - UNIT_GAP
RIGHT_SHELF_LEFT = TRIP_ROW_RIGHT - sum(TRIP_CELLS) - 2 * CELL_GAP

# ---- the engine's own two minutes

# EngineTrace keeps 120 one-second slots. The box's right edge is fixed and its
# left edge is wherever the filled slots reach, so it grows from the right as the
# engine's history fills and is never drawn empty (M7). It leaves the panel when
# the last non-zero slot falls off the left edge: 120 s of hysteresis with no
# timer of its own, which is also why a winter traffic jam does not flicker the
# trip balance in and out every ninety seconds.
ENGINE_SLOTS = 120
ENGINE_BOX_RIGHT = RIGHT_EDGE
ENGINE_BOX_FULL_LEFT = RIGHT_SHELF_LEFT
ENGINE_PITCH = (ENGINE_BOX_RIGHT - ENGINE_BOX_FULL_LEFT) / (ENGINE_SLOTS - 1)
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
# One rhythm step between the box's own zero rule and the legend's caps. Half a
# step was drawn first and the picture settled it: generation is an area that
# stands *on* that rule, so at 4 units the blue and «ГЕНЕРАЦИЯ» were one object.
ENGINE_BOX_BOTTOM = ENGINE_LEGEND - CAP * CAPTION - STEP
ENGINE_RPM_FULL = 3000.0                # not 6000: this engine is a generator
ENGINE_GEN_FULL = 100.0                 # ClusterReadout.GENERATION_FULL_KW, root

# ---- the petal, and the three kilometres behind its figure

PETAL_BASELINE = 384.0
PETAL_FLOOR = 410.0                     # nothing is drawn below this
PETAL_BUCKETS = 30                      # 3 km of ConsumptionLog's 100 m buckets
# "16,8" and "2:15" are both three digits and one mark, so one field holds either,
# and two digits is what the panel actually prints while the car is moving.
PETAL_FIELD_W = 3 * DIGIT * FIGURE + max(COMMA, COLON) * FIGURE
PETAL_BASE_FIELD_W = 2 * DIGIT * FIGURE
PETAL_UNIT_W = W_PER_100KM
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
# next whole step under that. All of the gain the owner asked for is in the
# height, where there was room: 36 -> 56, and the scale that fills it is fixed.
PETAL_BOX_W = STEP * 29
PETAL_BOX_H = STEP * 7                  # 56
PETAL_BOX_X = PETAL_BOX_RIGHT - PETAL_BOX_W
# The box hangs from the figure's own cap top - the jury's "not above 348" is that
# line, 347.08 - and 56 units later it stops 6.9 short of the floor.
PETAL_BOX_TOP = PETAL_BASELINE - CAP * FIGURE
PETAL_BOX_BOTTOM = PETAL_BOX_TOP + PETAL_BOX_H
# A fixed ladder, not an autoscale: 0…40 kW·h/100 km with the zero line four
# fifths down, which leaves the bottom fifth for the return and makes it 10 the
# same way - 1.12 units per kW·h in both directions. Autoscaling to each window's
# own ceiling meant a bucket changed height when a *different* bucket changed
# value, so the shape of the last three kilometres was never twice the same shape.
PETAL_ZERO_SHARE = 0.8
PETAL_ZERO_Y = PETAL_BOX_TOP + PETAL_ZERO_SHARE * PETAL_BOX_H
PETAL_FULL = 40.0
PETAL_RETURN_FULL = PETAL_FULL * (1.0 - PETAL_ZERO_SHARE) / PETAL_ZERO_SHARE
# Contrast, the other half of the owner's verdict. A 30 % field under a 2-unit
# line at MUTED was a texture; the line is the figure's own INK at 70 % over a
# MUTED_DEEP field at 55 %, and the return keeps that same field below the zero
# line rather than turning blue - blue on this panel is the engine and the band.
AREA_ALPHA = 0.55
LINE_ALPHA = 0.70
GEN_AREA_ALPHA = 0.55
PEAK_ALPHA = 0.85

# EnergyScale: the band is the dial straightened out, same square root, same spans.
FULL_DISCHARGE_KW, FULL_REGEN_KW, FLOOR_KW = 300.0, 100.0, 0.5


def left_cell(index):
    """Left shelf cells run outward from the margin, each as wide as it must be."""
    left = LEFT_EDGE + sum(LEFT_CELLS[:index]) + index * CELL_GAP
    return left, left + LEFT_CELLS[index]


def trip_cell(index):
    """Trip cells are counted from the outside in: 0 is the one nearest the unit."""
    right = TRIP_ROW_RIGHT - sum(TRIP_CELLS[:index]) - index * CELL_GAP
    return right - TRIP_CELLS[index], right


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


def dot(cx, cy, colour=RETURN):
    return f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(MARK_R)}" fill="{colour}"/>'


def comma(value, digits=1):
    return f'{value:.{digits}f}'.replace('.', ',')


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


def engine_history(filled, stopped=0, rpm_now=1780.0, gen_now=14.0):
    """[filled] seconds of the engine's history, the last [stopped] of them dead.

    No front padding with nulls any more: the box is exactly as wide as the
    history it has, anchored at the right edge, so it grows leftward as the
    engine runs and is never drawn empty. After the engine stops the slots keep
    arriving at zero, which is what walks the last live sample off the left edge
    120 seconds later and takes the box with it.
    """
    rpm, gen = [], []
    running = filled - stopped
    for i in range(filled):
        if i >= running:
            rpm.append(0.0)
            gen.append(0.0)
            continue
        t = i / float(max(running - 1, 1))
        wake = min(1.0, t * 3.0)
        rpm.append(900.0 + (rpm_now - 900.0) * wake
                   + 120.0 * math.sin(i * 0.55) * (1.0 - t))
        share = max(0.0, min(1.0, (t - 0.06) * 3.4))
        gen.append(max(0.0, gen_now * share + 2.4 * math.sin(i * 0.41) * (1.0 - t)))
    return rpm, gen


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
    logged on this car, so `seam_on_band` False draws the same fact without the
    claim, as a separate line under the body.
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
        if s.get('seam_on_band', True):
            far = band_x(kw + generation)
            out.append(rect(tip, top, far - tip, BAND_BODY, RETURN))
        else:
            far = AXIS + sweep(generation) * BAND_HALF
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

    Running: the heading carries the unit, the figure is the revolutions, and the
    line under it is what the engine is putting back - a blue dot and «14 кВт»,
    because a dot is the smallest blue thing that still reads on black and a 12'
    blue word is not one.

    Asleep after running: «ДВС · мин» over «6», which is the question a hybrid's
    driver actually asks and the answer nothing on this panel gave. The line under
    it is gone: «мин за поездку» said in fourteen characters what the heading says
    in one, and it said it at the panel's tightest baseline - the aperture leaves
    138.2 there and the words asked for 130.4, eight units from a boundary that
    has never been photographed. The other two states already carry their unit in
    the heading, so this one does too.

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
        if s.get('generation') is not None:
            unit_x = RIGHT_EDGE - W_KW
            field_right = unit_x - SMALL_GAP
            field_left = field_right - 2 * DIGIT * CAPTION
            out += [
                dot(field_left - MARK_GAP - MARK_R, CORNER_LINE - CAP * CAPTION / 2),
                txt('cl0', field_right, CORNER_LINE, f'{s["generation"]:.0f}',
                    'end', MUTED),
                txt('un', unit_x, CORNER_LINE, 'кВт', 'start', MUTED),
            ]
        return out
    out = [txt('ttl', RIGHT_EDGE, CORNER_TITLE, 'ДВС · мин', 'end', MUTED_DEEP)]
    if s.get('ice_minutes') is not None:
        out.append(txt('fig', RIGHT_EDGE, CORNER_FIGURE, f'{s["ice_minutes"]:.0f}',
                       'end', MUTED))
    return out


def left_shelf(s):
    """Temperatures: three cells, and a fourth that only exists on an exception.

    The exception is the figure itself changing colour, at the same size as every
    other figure on the shelf - so noticing it and reading it are one glance. The
    three motors are three figures under one word, in `motorTemps` order, each
    coloured by its own level: the rear pair is per-side and one reading threw two
    thirds of what the car reports away.
    """
    if not s['temps_known']:
        return []
    out = []
    temps = s.get('temps')

    def cell(index, word):
        left, _ = left_cell(index)
        out.append(txt('cl', left, SHELF_CAPTION, word, 'start', MUTED_DEEP))
        return left

    def degrees(x, value, level):
        # A degree belongs against its digits and Roboto sets it there: the field
        # is two characters wide and the sign starts exactly at its right edge.
        out.append(txt('rd', x, SHELF_FIGURE, value, 'end', LEVEL[level]))
        out.append(txt('rd', x, SHELF_FIGURE, '°', 'start', MUTED))

    left = cell(0, 'БАТАРЕЯ')
    if temps:
        degrees(left + TEMP_FIELD, *temps['pack'])
    left = cell(1, 'МОТОРЫ')
    if temps:
        for index, (value, level) in enumerate(temps['motors']):
            degrees(left + index * MOTOR_PITCH + TEMP_FIELD, value, level)
    left = cell(2, 'ИНВЕРТОР')
    if temps:
        degrees(left + TEMP_FIELD, *temps['inverter'])

    spread = s.get('spread')
    if spread:
        value, level = spread
        left = cell(3, 'РАЗБРОС')
        out.append(txt('rd', left + TEMP_FIELD, SHELF_FIGURE, value, 'end', LEVEL[level]))
        out.append(txt('rd', left + TEMP_FIELD + STEP, SHELF_FIGURE, 'мВ',
                       'start', MUTED))
    return out


def engine_box(s):
    """Two minutes of the combustion half, where the trip balance stands otherwise.

    It takes both rows of the shelf now - the figures' cap top down to a hair
    above their captions' baseline, 54 units - and the legend moves below it, onto
    the band's own 24-unit guard. In 24 units the two runs were a blue thread with
    a grey thread lying on it. Revolutions run linearly against 3000 - this engine
    is a generator, not a redline - and generation by the same square root
    `ClusterReadout.generationFraction` uses. Neither run carries an axis; the two
    words under the box are the whole legend, and the blue one carries the dot.

    While the box is up the three balance cells are hidden. That is not "куда
    делся баланс": the box only leaves 120 s after the last live sample, so the
    balance comes back once, not once per engine cycle, and a winter jam that
    cycles the engine every ninety seconds never gets the swap at all.
    """
    rpm, gen = s['trace']
    x1 = ENGINE_BOX_RIGHT
    x0 = x1 - (len(rpm) - 1) * ENGINE_PITCH
    top, bottom = ENGINE_BOX_TOP, ENGINE_BOX_BOTTOM
    height = bottom - top
    xs = [x0 + ENGINE_PITCH * i for i in range(len(rpm) + 1)]

    def y_of(value, full, root=False):
        share = min(1.0, max(0.0, value / full))
        if root:
            share = math.sqrt(share)
        return bottom - height * share

    out = [line(x0, bottom, x1, bottom, MUTED_DEEP, BAND_HAIRLINE)]

    gen_ys = [y_of(v, ENGINE_GEN_FULL, root=True) for v in gen]
    outline, field = step_path(xs, gen_ys, bottom)
    out.append(f'<path d="{field}" fill="{RETURN}" opacity="{f(GEN_AREA_ALPHA)}"/>')
    out.append(f'<path d="{outline}" fill="none" stroke="{RETURN}" '
               f'stroke-width="{f(AREA_EDGE)}" stroke-linejoin="round"/>')

    run = [(x0 + ENGINE_PITCH * i, y_of(v, ENGINE_RPM_FULL)) for i, v in enumerate(rpm)]
    d = 'M ' + ' L '.join(f'{f(x)} {f(y)}' for x, y in run)
    out.append(f'<path d="{d}" fill="none" stroke="{INK}" '
               f'stroke-width="{f(DATA_LINE)}" stroke-linejoin="round" '
               f'stroke-linecap="round" opacity="{f(LINE_ALPHA)}"/>')

    out.append(txt('cl', RIGHT_EDGE, ENGINE_LEGEND, 'ГЕНЕРАЦИЯ', 'end', MUTED_DEEP))
    gen_left = RIGHT_EDGE - W_CAPTION['ГЕНЕРАЦИЯ']
    out.append(dot(gen_left - MARK_GAP - MARK_R, ENGINE_LEGEND - CAP * CAPTION / 2))
    out.append(txt('cl', gen_left - MARK_W - CELL_GAP, ENGINE_LEGEND,
                   'ОБОРОТЫ', 'end', MUTED_DEEP))
    return out


def right_shelf(s):
    """The trip's energy in three fixed seats - or the engine's own two minutes.

    A zero is drawn, in MUTED_DEEP, in its own seat. The last board packed the
    cells against the margin and skipped a zero, so the first braking of a trip
    slid two figures sideways and the engine's first start slid them again: a
    coordinate that depends on data, which is the one thing this concept exists to
    cure. The unit is written once, at a fixed anchor after the last digit.
    """
    if s.get('trace'):
        return engine_box(s)
    if not s['trip_known']:
        return []
    trip = s.get('trip')
    out = [txt('un', TRIP_UNIT_X, SHELF_FIGURE, 'кВт·ч', 'end', MUTED_DEEP)]
    for index, (key, word, marked) in enumerate((('ice', 'ОТ ДВС', True),
                                                 ('regen', 'РЕКУПЕРАЦИЯ', True),
                                                 ('spent', 'ИЗ БАТАРЕИ', False))):
        _, right = trip_cell(index)
        out.append(txt('cl', right, SHELF_CAPTION, word, 'end', MUTED_DEEP))
        if marked:
            word_left = right - W_CAPTION[word]
            out.append(dot(word_left - MARK_GAP - MARK_R,
                           SHELF_CAPTION - CAP * CAPTION / 2))
        if trip is None:
            continue
        value = trip[key]
        out.append(txt('rd', right, SHELF_FIGURE, comma(value), 'end',
                       MUTED if value else MUTED_DEEP))
    return out


def petal_history(bars):
    """Three kilometres of closed buckets, as one stepped line beside its figure.

    Two things the owner said about the fourth board are answered here and they
    are the same thing twice: "графики очень сильно сплющены по вертикали и очень
    слабо читаются". The box is 56 units rather than 36 - the tallest it can be
    without rising over the cap of the figure beside it - and the drawing is a
    70 % INK line 2.5 units thick over a 55 % MUTED_DEEP field, where it was a
    2-unit MUTED line over a 30 % one and read as a texture.

    The scale is a fixed ladder: 0…40 kW·h/100 km up, 0…10 back, one line per
    1.12 units in both directions, and the zero four fifths down. An autoscale
    meant one bucket changing value redrew the height of all thirty, so the same
    three kilometres never came back the same shape. There is no dashed mean: the
    mean is the figure standing next to the box.
    """
    if not bars:
        return []
    zero, top, bottom = PETAL_ZERO_Y, PETAL_BOX_TOP, PETAL_BOX_BOTTOM
    pitch = PETAL_BOX_W / len(bars)
    xs = [PETAL_BOX_X + pitch * i for i in range(len(bars) + 1)]
    ys = [zero - min(v / PETAL_FULL, 1.0) * (zero - top) if v >= 0
          else zero + min(-v / PETAL_RETURN_FULL, 1.0) * (bottom - zero) for v in bars]
    outline, field = step_path(xs, ys, zero)
    return [
        f'<path d="{field}" fill="{MUTED_DEEP}" opacity="{f(AREA_ALPHA)}"/>',
        f'<path d="{outline}" fill="none" stroke="{INK}" opacity="{f(LINE_ALPHA)}" '
        f'stroke-width="{f(DATA_LINE)}" stroke-linejoin="round"/>',
        line(PETAL_BOX_X, zero, PETAL_BOX_X + PETAL_BOX_W, zero,
             MUTED_DEEP, BAND_HAIRLINE),
    ]


def petal(s):
    """What the last three kilometres cost - always the last three kilometres.

    The denominator never changes under the figure: standing on P it is still
    three kilometres and only the tenth appears, because at 100 km/h a tenth
    changes three times a second and a figure that flickers is a figure nobody
    reads. While the engine is running the figure goes MUTED and says «батарея»,
    because `ConsumptionLog` integrates pack power alone and nobody has logged
    whether `GENERATION_KW` is already inside `POWER_KW` (B1).
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
       «кВт·ч», «об/мин», «мин за поездку» are set as themselves. */
    .un { font-size:%(caption)spx; font-weight:400; fill:%(muted_deep)s; }
    .cl0 { font-size:%(caption)spx; font-weight:400; fill:%(muted)s;
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

PER_100 = 'кВт·ч/100 км'
PER_100_BATTERY = 'кВт·ч/100 км · батарея'

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
    s.setdefault('petal_unit', PER_100_BATTERY if s.get('ice') == 'running' else PER_100)
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


CALM = sc(kw=34.0, peak=68.0, volts=552.0, temps=COOL,
          trip=dict(spent=12.4, regen=3.1, ice=0.0),
          bars=CALM_BARS, petal='17')

STATES = [
    ('Первые секунды · шина ещё не ответила: скелет ленты, и больше ничего',
     sc(kw=None)),
    ('Пробка · 2 кВт внутри мёртвой зоны: цвета нет, лента — засечка, цифра стоит',
     sc(kw=2.0, volts=548.0, temps=COOL,
        trip=dict(spent=8.1, regen=2.2, ice=0.0),
        bars=consumption_history(21.4), petal='21')),
    ('Спокойная езда · 34 кВт, ДВС в этой поездке не запускался — правый угол пуст',
     CALM),
    ('Разгон · 128 кВт, пик-холд стоит впереди кончика и сползает к нему',
     sc(kw=128.0, peak=163.0, volts=531.0, temps=WORKED,
        trip=dict(spent=13.1, regen=3.1, ice=0.0),
        bars=consumption_history(20.4), petal='20')),
    ('Рекуперация · сторона и цвет меняются, не появляется ничего',
     sc(kw=-42.0, peak=-58.0, volts=573.0, temps=COOL,
        trip=dict(spent=12.6, regen=3.4, ice=0.0),
        bars=consumption_history(11.2), petal='11')),
    ('ДВС генерирует 82 с · шов за флагом, коробка выросла справа, угол занят',
     sc(kw=28.0, peak=52.0, ice='running', rpm=1780.0, generation=14.0,
        trace=engine_history(82), volts=548.0, temps=WORKED,
        bars=consumption_history(17.4), petal='17')),
    ('ДВС заглох 40 с назад · коробка ещё здесь, обороты у правого края на нуле',
     sc(kw=34.0, peak=68.0, ice='slept', ice_minutes=6.0,
        trace=engine_history(120, stopped=40), volts=551.0, temps=WORKED,
        bars=consumption_history(17.1), petal='17')),
    ('Запасное рисование шва · если генерация уже внутри POWER_KW — отдельной линией',
     sc(kw=28.0, peak=52.0, ice='running', rpm=1780.0, generation=14.0,
        seam_on_band=False, trace=engine_history(82), volts=548.0, temps=WORKED,
        bars=consumption_history(17.4), petal='17')),
    ('Стоянка на P · те же 3 км, у цифры появилась десятая',
     sc(kw=1.4, volts=561.0, temps=COOL,
        trip=dict(spent=12.4, regen=3.1, ice=0.0),
        bars=CALM_BARS, petal='16,8')),
    ('Зарядка от розетки · километров нет, коробка расхода остаётся прежней',
     sc(kw=-7.0, volts=584.0, temps=PARKED,
        trip=dict(spent=12.4, regen=3.1, ice=0.0),
        bars=CALM_BARS, petal='2:15', petal_unit='до полной')),
    ('Одиночный null · напряжение снято через 2 с, заголовок «БАТАРЕЯ · В» стоит',
     sc(kw=34.0, peak=68.0, volts=None, volts_known=True, temps=COOL,
        trip=dict(spent=12.4, regen=3.1, ice=0.0),
        bars=CALM_BARS, petal='17')),
    ('Потеря связи · через 2 с сняты все значения; подписи остались и не потускнели',
     sc(kw=None, power_known=True, volts=None, volts_known=True,
        temps=None, temps_known=True, trip=None, trip_known=True,
        ice=None, ice_known=False, bars=None, petal=None, petal_known=True)),
    ('Исключение · моторы 68° оранжевым, инвертор 92° красным, четвёртая ячейка',
     sc(kw=34.0, peak=68.0, volts=552.0, temps=HOT, spread=('44', 'alert'),
        trip=dict(spent=12.4, regen=3.1, ice=0.0),
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

    # The cell grid both shelves stand on, drawn rather than described.
    cell_top = SHELF_FIGURE - CAP * READING
    cell_h = SHELF_CAPTION - SHELF_FIGURE + CAP * READING + 6
    for index in range(4):
        left, right = left_cell(index)
        body.append(outline(left, cell_top, right - left, cell_h))
    for index in range(3):
        left, right = trip_cell(index)
        body.append(outline(left, cell_top, right - left, cell_h))
    motors_left, _ = left_cell(1)
    for index in range(3):
        body.append(outline(motors_left + index * MOTOR_PITCH, cell_top,
                            TEMP_FIELD + W_DEGREE, cell_h, dash='2 4', opacity=0.7))

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
                                  f'единица живёт здесь: «БАТАРЕЯ · В», «ДВС · об/мин»'),
        (LEFT_EDGE, CORNER_FIGURE, f'цифра угла · 52 · y {CORNER_FIGURE:.0f} · вольты '
                                   f'3 знака {LEFT_FIELD_X:.0f}…{LEFT_FIELD_RIGHT:.1f} · '
                                   f'обороты 4 знака {RIGHT_FIELD_LEFT:.1f}…'
                                   f'{RIGHT_FIELD_RIGHT:.1f}'),
        (LEFT_EDGE, CORNER_LINE, f'строка угла · 18 · y {CORNER_LINE:.0f} · только '
                                 f'«● N кВт» под оборотами: апертура даёт справа '
                                 f'{RIGHT_EDGE - (W - aperture_reach(CORNER_LINE, True)):.1f}, '
                                 f'«мин за поездку» просила 130.4 и снята'),
        (LEFT_EDGE, GUARD_TOP, f'запас {CLEARANCE:.0f} · stockTop {STOCK_TOP:.2f} → '
                               f'{GUARD_TOP:.2f} · на нём стоят герой, обе полки и '
                               f'коробка ДВС'),
        (LEFT_EDGE, SHELF_FIGURE, f'цифры полок · 34 Regular · y {SHELF_FIGURE:.2f} · '
                                  f'ячейки {" / ".join(f"{w:.0f}" for w in LEFT_CELLS)} и '
                                  f'{" / ".join(f"{w:.0f}" for w in TRIP_CELLS)} · зазор '
                                  f'{CELL_GAP:.0f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'подписи полок · 18 капителью · y '
                                   f'{SHELF_CAPTION:.2f} · моторы: шаг {MOTOR_PITCH:.1f}, '
                                   f'поле {TEMP_FIELD:.1f} + «°» {W_DEGREE:.1f}'),
        (right_lane, ENGINE_BOX_TOP, f'коробка ДВС · оба ряда полки, '
                                     f'{ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP:.1f} высотой · '
                                     f'{ENGINE_SLOTS} слотов по {ENGINE_PITCH:.2f} '
                                     f'справа'),
        (right_lane, ENGINE_BOX_TOP, 'обороты 0…3000 линейно, генерация корнем до '
                                     '100 кВт · осей нет'),
        (right_lane, ENGINE_LEGEND, f'легенда ДВС · 18 · базовая {ENGINE_LEGEND:.2f} = '
                                    f'запас {CLEARANCE:.0f} над лентой'),
        (right_lane, HERO_BASELINE, f'герой · 88 Light · базовая {HERO_BASELINE:.2f} · '
                                    f'поле {HERO_FIELD_LEFT:.1f}…{HERO_FIELD_RIGHT:.1f} · '
                                    f'«кВт» 34 на {HERO_UNIT_X:.1f}'),
        (right_lane, BAND_Y, f'лента · y {BAND_Y:.2f} · тело {BAND_BODY:.0f} · корень, '
                             f'300 / 100 · мёртвая зона {NEUTRAL_KW:.0f} кВт'),
        (right_lane, GLOW_CY, f'свечение · центр на нуле · rx {GLOW_RX:.0f}, ry '
                              f'{GLOW_RY:.2f} · {GLOW_MAX:.2f}·√(|P|/'
                              f'{GLOW_FULL_KW:.0f} кВт), насыщение на '
                              f'{GLOW_FULL_KW:.0f}, τ 1,5 с'),
        (right_lane, GUARD_BOTTOM, f'запас {CLEARANCE:.0f} снизу · stockBottom '
                                   f'{STOCK_BOTTOM:.2f} → {GUARD_BOTTOM:.2f} · низ ленты '
                                   f'{BAND_Y + BAND_BODY / 2:.2f}'),
        (right_lane, PETAL_BOX_TOP, f'коробка расхода · {PETAL_BOX_W:.0f} × '
                                    f'{PETAL_BOX_H:.0f} на {PETAL_BOX_X:.1f}…'
                                    f'{PETAL_BOX_RIGHT:.1f} · {PETAL_BUCKETS} корзин по '
                                    f'{PETAL_BOX_W / PETAL_BUCKETS:.2f}'),
        (right_lane, PETAL_BOX_TOP, f'шкала 0…{PETAL_FULL:.0f} вверх и 0…'
                                    f'{PETAL_RETURN_FULL:.0f} вниз, нуль на 4/5 · '
                                    f'{PETAL_ZERO_SHARE * PETAL_BOX_H / PETAL_FULL:.2f} '
                                    f'единицы на кВт·ч — не автоподгон'),
        (right_lane, PETAL_BASELINE, f'лепесток · 52 · два знака на оси, поле до '
                                     f'{PETAL_FIELD_W:.1f} влево, якорь единицы '
                                     f'{PETAL_UNIT_X:.1f}'),
        (right_lane, PETAL_FLOOR, f'пол композиции · y {PETAL_FLOOR:.0f} · низ коробки '
                                  f'{PETAL_BOX_BOTTOM:.1f}, до выреза '
                                  f'{PETAL_BOX_X - petal_room(PETAL_BOX_BOTTOM):.1f}'),
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
          f'line {CORNER_LINE:.0f}')
    print(f'  left  field {LEFT_FIELD_X:.2f}…{LEFT_FIELD_RIGHT:.2f}, '
          f'aperture at the figure {aperture_reach(CORNER_FIGURE):.2f}')
    print(f'  right field {RIGHT_FIELD_LEFT:.2f}…{RIGHT_FIELD_RIGHT:.2f}, '
          f'aperture leaves {RIGHT_EDGE - (W - aperture_reach(CORNER_FIGURE, True)):.2f} '
          f'for {4 * DIGIT * FIGURE:.2f}; the line leaves '
          f'{RIGHT_EDGE - (W - aperture_reach(CORNER_LINE, True)):.2f} for 130.44')
    print(f'shelves figures {SHELF_FIGURE:.2f} (cap top '
          f'{SHELF_FIGURE - CAP * READING:.2f}), captions {SHELF_CAPTION:.2f}')
    print(f'  left  cells {[f"{w:.0f}" for w in LEFT_CELLS]} -> '
          f'{LEFT_EDGE:.0f}…{LEFT_SHELF_RIGHT:.0f}, clear of the hero field by '
          f'{HERO_FIELD_LEFT - LEFT_SHELF_RIGHT:.2f}')
    print(f'  right cells {[f"{w:.0f}" for w in TRIP_CELLS]} -> '
          f'{RIGHT_SHELF_LEFT:.0f}…{TRIP_ROW_RIGHT:.0f}, «кВт·ч» ends at '
          f'{TRIP_UNIT_X:.0f}, clear of the hero unit by '
          f'{RIGHT_SHELF_LEFT - (HERO_UNIT_X + HERO_UNIT_W):.2f}')
    print(f'engine  box {ENGINE_BOX_FULL_LEFT:.2f}…{ENGINE_BOX_RIGHT:.2f} x '
          f'{ENGINE_BOX_TOP:.2f}…{ENGINE_BOX_BOTTOM:.2f} = '
          f'{ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP:.2f} tall, pitch {ENGINE_PITCH:.2f}, '
          f'82 s wide = {81 * ENGINE_PITCH:.1f}')
    print(f'  legend  {ENGINE_LEGEND:.2f}, {CLEARANCE:.0f} over the band body, caps '
          f'from {ENGINE_LEGEND - CAP * CAPTION:.2f}, box stops '
          f'{ENGINE_LEGEND - CAP * CAPTION - ENGINE_BOX_BOTTOM:.2f} above them; the '
          f'shelf baselines {SHELF_FIGURE:.2f}/{SHELF_CAPTION:.2f} do not move')
    print(f'petal   figure {PETAL_BASE_FIELD_W:.2f} centred on the axis: ends '
          f'{PETAL_FIGURE_RIGHT:.2f} against the hero\'s {HERO_FIELD_RIGHT:.2f} '
          f'({PETAL_FIGURE_RIGHT - HERO_FIELD_RIGHT:+.2f}), reserve to '
          f'{PETAL_FIGURE_RIGHT - PETAL_FIELD_W:.2f}, unit {PETAL_UNIT_X:.2f}…'
          f'{PETAL_UNIT_X + PETAL_UNIT_W:.2f}')
    print(f'  box {PETAL_BOX_X:.2f}…{PETAL_BOX_RIGHT:.2f} x {PETAL_BOX_TOP:.2f}…'
          f'{PETAL_BOX_BOTTOM:.2f} = {PETAL_BOX_W:.0f} x {PETAL_BOX_H:.0f}, gap '
          f'{PETAL_BOX_GAP:.0f} to the reserve, bucket '
          f'{PETAL_BOX_W / PETAL_BUCKETS:.2f}, floor leaves '
          f'{PETAL_FLOOR - PETAL_BOX_BOTTOM:.2f}')
    print(f'  aperture at the box top {petal_room(PETAL_BOX_TOP):.2f} -> clear by '
          f'{PETAL_BOX_X - petal_room(PETAL_BOX_TOP):.2f}, at its bottom '
          f'{petal_room(PETAL_BOX_BOTTOM):.2f} -> clear by '
          f'{PETAL_BOX_X - petal_room(PETAL_BOX_BOTTOM):.2f} (the guard is 8; 270 wide '
          f'would be {PETAL_BOX_RIGHT - 270 - petal_room(PETAL_BOX_BOTTOM):.2f})')
    print(f'  zero {PETAL_ZERO_Y:.2f} = {PETAL_ZERO_SHARE:.1f} of the box, scale 0…'
          f'{PETAL_FULL:.0f} up and 0…{PETAL_RETURN_FULL:.0f} back at '
          f'{PETAL_ZERO_SHARE * PETAL_BOX_H / PETAL_FULL:.3f} units per kW·h/100 km')
    print(f'fields  digit {DIGIT * READING:.2f}/34 {DIGIT * FIGURE:.2f}/52 '
          f'{DIGIT_LIGHT * HERO:.2f}/88, comma {COMMA * FIGURE:.2f}/52, '
          f'trip {TRIP_FIELD:.2f}, temp {TEMP_FIELD:.2f}, petal {PETAL_FIELD_W:.2f}')
    for kw in (-100.0, -42.0, -3.0, 0.0, 3.0, 34.0, 128.0, 300.0):
        print(f'  {kw:7.1f} kW -> x {band_x(kw):8.2f}  glow '
              f'{glow_alpha(kw):.3f}  {flow_colour(kw)}')
