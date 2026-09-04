#!/usr/bin/env python3
"""
Emit the Contour cluster artboards from the constants the app draws with.

The Contour is the concept that won the 2026-09 cluster contest
(`docs/cluster-contest-2026-09/`), with the jury's five corrections folded in,
and then the owner's verdict on each drawing folded in after that.

**First drawing.** "выглядит неплохо, но злоупотребление полосками; не понимаю,
что такое потрачено / вернула / ДВС; жаль, что пожертвовали температурами." One
bar left on the panel - the band - everything else became a number with a word
under it, and the temperatures came back as the left shelf.

**Second drawing.** "Намного симпатичнее! Только моторы одной цифрой - не очень,
три не влезают? И хочется графики: динамика расхода, как меняются обороты, как
меняется отдача заряда." So this pass adds the three things the data already has
and the panel was not showing:

- the car has three drive motors and `motorTemps` reports them separately, so the
  МОТОРЫ cell shows three figures under one word, each coloured by its own level;
- `ConsumptionLog` closes a bucket every 100 m and the cluster keeps thirty of
  them, so the petal gets those thirty as a fixed box beside its figure;
- `EngineTrace` keeps 120 one-second slots of revolutions and generation, so
  while the engine is running the right shelf shows that history instead of the
  trip balance.

Adding three histories to a panel whose whole argument is "one bar" needs a rule,
and it is this: a history is a *box*, it is small, it never carries an axis or a
number of its own, and it stands beside the figure it explains. The band stays
the only lit thing and the hero stays the only heavy one.

Nothing here is typed. Every coordinate is derived the way `ContourPlan.kt` will
derive it - the apertures out of `ClusterModels.kt`'s own integer arithmetic,
everything else out of four decisions: the margin, the rhythm, the type ramp and
the cap height. A number lives in a field sized by its own digit count, and its
unit hangs off the field rather than off the string, so gaining a digit moves
nothing.

    python3 gen_contour.py && python3 audit.py ClusterContour \
        ClusterContourStates ClusterContourPlan
"""
import math

import gen_cluster as g

f = g.f

# ---------------------------------------------------------------- the ramp

HERO, FIGURE, READING, CAPTION = 104.0, 52.0, 34.0, 18.0
NOTE = 13.0                      # board furniture only: keep-out words, plan notes
STEP = 8.0                       # InstrumentDensity.WIDE.step
CAP = 0.71                       # InstrumentPen.digitHeight / ContourPlan.CAP_HEIGHT
# Measured in the real face. The previous pass wrote 0.5 here and called it a
# measurement; Roboto Mono advances 0.6 of its size per character, at every size
# the board sets - "28" at 34 is 40.81, "1780" at 52 is 124.83, "300" at 104 is
# 187.23. Every reserve field was therefore a sixth too narrow, and it showed:
# "552" hung three units past the left margin, out into the panel's own edge.
MONO = 0.6                       # what a monospaced digit advances
TRACKING = 0.12                  # InstrumentDensity.titleTracking

# Measured the same way, in headless Chrome, in the faces and at the sizes this
# board sets them. A reserve field is arithmetic and needs no measuring; a word is
# not, and the handful of places where a word decides a coordinate - a unit
# hanging off a field, a legend hung on the margin, a caption deciding how wide
# its cell has to be - are measured once and written down here.
W_KW = 29.61                     # «кВт» at 18
W_PER_100KM = 109.42             # «кВт·ч/100 км» at 18
W_DEGREE = 20.41                 # «°» at 34, Roboto Mono
W_MILLIVOLT = 40.81              # «мВ» at 34, Roboto Mono
W_GENERATION = 102.67            # «ГЕНЕРАЦИЯ» at 18
W_CAPTION = {'БАТАРЕЯ': 76.80, 'МОТОРЫ': 77.67, 'ИНВЕРТОР': 92.72,
             'РАЗБРОС': 79.38, 'ИЗ БАТАРЕИ': 106.17, 'РЕКУПЕРАЦИЯ': 126.91,
             'ОТ ДВС': 63.83, 'ОБОРОТЫ': 85.92, 'ГЕНЕРАЦИЯ': W_GENERATION}

# ---------------------------------------------------------------- the panel

DISPLAY_W, DISPLAY_H = 2560, 720
H = 424.0
W = H * DISPLAY_W / DISPLAY_H
AXIS = W / 2

# ClusterMapLayout's own arithmetic, integer division and all.
STOCK_TOP = (DISPLAY_W * 20 // 100 * 40 // 100 * 4 // 3) / DISPLAY_H * H
STOCK_BOTTOM = (1.0 - (90 + 60) / DISPLAY_H) * H
LEFT_RX = (DISPLAY_W * 24 // 100) / DISPLAY_W * W
RIGHT_RX = (DISPLAY_W * 20 // 100) / DISPLAY_W * W
APERTURE_RY = STOCK_TOP
PETAL_RX = 600 / DISPLAY_W * W
PETAL_RY = (600 * 55 // 100) / DISPLAY_H * H
PETAL_CY = (1.0 - 120 / DISPLAY_H) * H


def aperture_reach(y, right=False):
    """How much room a corner aperture still has at baseline [y]."""
    rx = RIGHT_RX if right else LEFT_RX
    t = 1.0 - (y / APERTURE_RY) ** 2
    return rx * math.sqrt(t) if t > 0 else 0.0


def petal_reach(y):
    t = 1.0 - ((y - PETAL_CY) / PETAL_RY) ** 2
    return PETAL_RX * math.sqrt(t) if t > 0 else 0.0


def rungs(width):
    """The next whole rhythm step at or past [width]: a cell is never a string."""
    return math.ceil(width / STEP - 1e-9) * STEP


# ---------------------------------------------------------------- the skeleton

# Four steps, not one. A reserve field is the digits' advance; the box a browser
# and a Paint actually reserve runs wider still, and the audit kept catching
# "1780" touching its own unit until the gap reached this.
UNIT_GAP = STEP * 4

MARGIN = STEP * 6                       # the one outer margin: 48
LEFT_EDGE, RIGHT_EDGE = MARGIN, W - MARGIN

HERO_CLEARANCE = STEP * 3               # the jury's third correction
HERO_BASELINE = STOCK_TOP + HERO_CLEARANCE + CAP * HERO
HERO_CAP_TOP = HERO_BASELINE - CAP * HERO
# Three digits is the ceiling the scale can produce, so the field is knowable in
# advance. The digits are right-aligned inside the field and the field, its gap
# and its unit are centred on the axis as one group. Centring the field alone left
# "кВт" stranded from a two-digit reading and touching a three-digit one; this way
# the unit is always the same short step from the last digit, the whole hero still
# stands on the car's axis, and the digits wander at most half a glyph either side.
HERO_FIELD_W = 3 * MONO * HERO
HERO_UNIT_W = W_KW
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
LIMIT_BASELINE = BAND_Y + BAND_BODY / 2 + STEP + CAP * CAPTION
GEN_LINE_Y = BAND_Y + BAND_BODY / 2 + 4.0
GEN_LINE_H = 4.0
# Where the tip has been over the last ten seconds, as one smear under the live
# body. It is the band's second memory and the cheapest one: no samples are kept,
# only the two ends of the interval the tip has swept.
TAIL_ALPHA = 0.12

# The light belongs to the band, so it is centred on the band and its vertical
# radius is the distance to the nearer aperture edge - which is what the concept's
# "ry = half the clear band" was buying: an alpha that reaches zero before the
# vehicle's own graphics can cut it.
GLOW_CY = BAND_Y
GLOW_RY = STOCK_BOTTOM - BAND_Y
GLOW_RX = 340.0

# ---- the corners: one heading, one figure, one line

# A row advances by the lead plus the *full* type size, which is the convention
# every other generator here follows and the one this board first got wrong. A
# cap height is what the ink occupies; the box a browser and a Paint both reserve
# runs from the ascent to the descent, and spacing rows by cap height put a
# heading's descenders inside the digits underneath it.
CORNER_TITLE = STEP * 3                 # 24
CORNER_FIGURE = CORNER_TITLE + STEP * 2 + FIGURE
CORNER_LINE = CORNER_FIGURE + STEP * 2 + CAPTION

# Both corners now carry their unit in the heading - «БАТАРЕЯ · В», «ДВС · ОБ/МИН»
# - which is the concept's own Tufte rule and what the right shelf's heading has
# always done. It is also the only way the right corner fits: a corner aperture is
# 301 units wide at the top and 199 usable at the figure's baseline, and a
# four-digit field with «об/мин» beside it wants 219. The unit sat twenty units
# behind the vehicle's own graphics, invisible, and the correct mono advance was
# what made that measurable.
LEFT_FIELD_X = LEFT_EDGE                        # volts: three digits
LEFT_FIELD_RIGHT = LEFT_FIELD_X + 3 * MONO * FIGURE

RIGHT_FIELD_RIGHT = RIGHT_EDGE                  # revolutions: four digits
RIGHT_FIELD_LEFT = RIGHT_FIELD_RIGHT - 4 * MONO * FIGURE

# ---- the two shelves, which are one family

# Both stand in the clear band's flanks, on one pair of baselines, with one
# anatomy: a figure with its word under it. Only the figure size differs - the
# trip's kilowatt-hours are what the owner asked to see, the temperatures are what
# he asked to keep.
SHELF_HEADER = 180.0
SHELF_FIGURE = SHELF_HEADER + STEP * 2 + FIGURE
SHELF_CAPTION = SHELF_FIGURE + STEP * 2 + CAPTION

# A cell is as wide as the wider of the two things it has to hold - its caption or
# its payload - rounded up to the rhythm. The previous pass gave every cell on a
# shelf one width, which was fine while every cell held one number; three motor
# temperatures under one word do not fit a width chosen for "32°", and stretching
# every cell to fit them would push the shelf into the hero.
CELL_GAP = STEP * 2
TEMP_FIELD = 3 * MONO * READING         # three characters: "-8" and "102" both fit
MOTOR_FIELD = 2 * MONO * READING        # a drive motor's own two
MOTOR_GAP = STEP
MOTOR_PITCH = MOTOR_FIELD + W_DEGREE + MOTOR_GAP
MOTOR_RUN = 3 * (MOTOR_FIELD + W_DEGREE) + 2 * MOTOR_GAP
SPREAD_PAYLOAD = TEMP_FIELD + STEP + W_MILLIVOLT

# Left shelf, outward from the margin: pack, the three drive motors, inverter, and
# the cell-spread cell that only exists on an exception.
LEFT_CELLS = [rungs(max(W_CAPTION['БАТАРЕЯ'], TEMP_FIELD + W_DEGREE)),
              rungs(max(W_CAPTION['МОТОРЫ'], MOTOR_RUN)),
              rungs(max(W_CAPTION['ИНВЕРТОР'], TEMP_FIELD + W_DEGREE)),
              rungs(max(W_CAPTION['РАЗБРОС'], SPREAD_PAYLOAD))]
# The gap between two cells is twice the gap between two motors inside one, which
# is what keeps the inverter from reading as a fourth motor.
LEFT_SHELF_RIGHT = LEFT_EDGE + sum(LEFT_CELLS) + 3 * CELL_GAP

TRIP_FIELD = 4 * MONO * FIGURE          # "12,4" against the widest it can be
RIGHT_CELL_W = rungs(max(TRIP_FIELD, W_CAPTION['РЕКУПЕРАЦИЯ']))
RIGHT_SHELF_LEFT = RIGHT_EDGE - 3 * RIGHT_CELL_W - 2 * CELL_GAP

# ---- the engine's own two minutes

# EngineTrace keeps 120 one-second slots and hands the renderer both runs
# front-padded, so the box is a fixed width with a fixed slot count and the trace
# starts wherever the engine woke. It cannot be empty by construction: the shelf
# only shows it while revolutions are arriving.
ENGINE_SLOTS = 120
ENGINE_BOX_LEFT, ENGINE_BOX_RIGHT = RIGHT_SHELF_LEFT, RIGHT_EDGE
ENGINE_BOX_BOTTOM = SHELF_FIGURE        # the shelf's own figure baseline
ENGINE_BOX_TOP = SHELF_FIGURE - FIGURE  # and the row box that figure would have had
ENGINE_RPM_FULL = 3000.0                # not 6000: this engine is a generator
ENGINE_WINDOW = (1000.0, 2600.0)        # where it sits while it is generating
ENGINE_GEN_FULL = 100.0                 # ClusterReadout.GENERATION_FULL_KW, square root

# ---- the petal, and the thirty buckets behind its figure

PETAL_BASELINE = 384.0
# Nothing is drawn below this: the petal reaches the panel edge, but its last
# twenty units are a narrowing sliver resting on an estimated boundary.
PETAL_FLOOR = 410.0
PETAL_BARS = 30                         # 3 km of ConsumptionLog's 100 m buckets
PETAL_FIELD_W = 4 * MONO * FIGURE
PETAL_UNIT_W = W_PER_100KM
PETAL_BOX_GAP = STEP * 3
# The history balances the unit. Make the box and its gap add up to the unit and
# its gap and two things come out true at once: the group - box, figure, unit - is
# centred on the axis, and so are the digits inside it. Sizing the box by eye
# instead put the petal's figure twenty units to the right of the hero's, and two
# large numerals one above the other are exactly where that is visible.
PETAL_BOX_W = UNIT_GAP + PETAL_UNIT_W - PETAL_BOX_GAP
PETAL_GROUP_W = PETAL_BOX_W + PETAL_BOX_GAP + PETAL_FIELD_W + UNIT_GAP + PETAL_UNIT_W
PETAL_BOX_X = AXIS - PETAL_GROUP_W / 2
PETAL_FIGURE_RIGHT = PETAL_BOX_X + PETAL_BOX_W + PETAL_BOX_GAP + PETAL_FIELD_W
PETAL_UNIT_X = PETAL_FIGURE_RIGHT + UNIT_GAP
# ChartScale.ABOVE_ZERO_SHARE, and the zero line is the figure's own baseline -
# which is what ties a box of thirty anonymous bars to the number it explains.
PETAL_BOX_H = STEP * 5
PETAL_BOX_TOP = PETAL_BASELINE - g.ABOVE_ZERO_SHARE * PETAL_BOX_H
PETAL_BOX_BOTTOM = PETAL_BASELINE + (1.0 - g.ABOVE_ZERO_SHARE) * PETAL_BOX_H
BAR_SHARE = 0.78                        # ClusterDashboardRenderer's own bar share
# The oldest edge fades rather than being cropped, so the box reads in one
# direction without a word, an arrow or an axis saying so. The newest bar stops
# short of full ink on purpose: a history stands beside its figure, and a box of
# thirty bars at the brightness of the band would be a second lit thing.
FADE_BARS = 8.0
FADE_FLOOR = 0.22
BAR_ALPHA = 0.80

# EnergyScale: the band is the dial straightened out, same square root, same spans.
FULL_DISCHARGE_KW, FULL_REGEN_KW, FLOOR_KW = 300.0, 100.0, 0.5


def left_cell(index):
    """Left shelf cells run outward from the margin, each as wide as it must be."""
    left = LEFT_EDGE + sum(LEFT_CELLS[:index]) + index * CELL_GAP
    return left, left + LEFT_CELLS[index]


def right_cell(index):
    """Right shelf cells are counted from the outside in: 0 hugs the margin.

    The block packs against the margin rather than holding an empty seat for a
    value that may never arrive. An engine that starts mid-trip therefore shifts
    the two figures already there one cell inboard - once per trip, under the
    same crossfade the engine's own corner uses. The alternative was a permanent
    hole at the panel edge directly under the heading, which is the emptiness the
    owner objected to in the first place.
    """
    right = RIGHT_EDGE - index * (RIGHT_CELL_W + CELL_GAP)
    return right - RIGHT_CELL_W, right


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
TRACK = g.TRACK
BG = g.CLUSTER_BG

# The concept's one deliberate omission: champagne ACCENT is not drawn on this
# panel at all. Yellow means "decide something" in this car, and a permanent warm
# mark on an instrument is a false signal. DATA_PEAK plays its part, and by
# definition it means "the live edge of the data" rather than "an interface" - so
# it stays on the band, where the live edge is, and none of the three history
# boxes uses it. A box that marked its newest bar warm would put a third warm spot
# on a panel whose whole rule is that the lit thing is the band.

LEVEL = {'normal': INK, 'watch': WARNING, 'alert': DANGER}


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


def comma(value, digits=1):
    return f'{value:.{digits}f}'.replace('.', ',')


# ---------------------------------------------------------------- the histories

# One deterministic run of 100 m buckets, written as multiples of the window's own
# average so a scene names the average it wants and the bars, the dashed mean and
# the figure cannot disagree. Three buckets are negative: the chart needs a zero
# line rather than a floor, because a descent gives energy back.
SHAPE = [1.22, 1.18, 1.12, 1.14, 1.21, 1.21, 1.07, 0.83, 0.64, -0.22,
         -0.41, -0.12, 0.92, 0.92, 0.97, 1.14, 1.38, 1.54, 1.53, 1.36,
         1.18, 1.08, 1.06, 1.00, 0.85, 0.64, 0.51, 0.57, 0.77, 0.97]


def consumption_history(average):
    """Thirty closed buckets whose spending mean is exactly [average]."""
    spending = [m for m in SHAPE if m > 0]
    norm = sum(spending) / len(spending)
    return [round(average * m / norm, 1) for m in SHAPE]


def engine_history(sleeping=38, slots=ENGINE_SLOTS, rpm_now=1780.0, gen_now=14.0):
    """Two minutes ending exactly at the numbers the corner is showing.

    Front-padded with the slots the engine slept through, the way
    `EngineTrace.snapshot()` pads a history shorter than its capacity. The ripple
    is scaled by the distance from "now" so the last sample is the corner's own.
    """
    rpm, gen = [], []
    for i in range(slots):
        if i < sleeping:
            rpm.append(None)
            gen.append(None)
            continue
        t = (i - sleeping) / float(slots - 1 - sleeping)
        wake = min(1.0, t * 3.0)
        rpm.append(900.0 + (rpm_now - 900.0) * wake + 120.0 * math.sin(i * 0.55) * (1.0 - t))
        share = max(0.0, min(1.0, (t - 0.06) * 3.4))
        gen.append(gen_now * share + 2.4 * math.sin(i * 0.41) * (1.0 - t))
    return rpm, gen


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


def glow(kw, strength, colour=None, alpha=1.0):
    """The one pool of light: brightness is the magnitude, hue is the direction."""
    if strength <= 0:
        return []
    c = colour or (RETURN if kw < 0 else INK)
    s = strength * alpha
    return [
        f'<defs><radialGradient id="bandglow">'
        f'<stop offset="0" stop-color="{c}" stop-opacity="{f(s)}"/>'
        f'<stop offset="0.5" stop-color="{c}" stop-opacity="{f(s * 0.45)}"/>'
        f'<stop offset="1" stop-color="{c}" stop-opacity="0"/>'
        f'</radialGradient></defs>',
        f'<ellipse cx="{f(band_x(kw))}" cy="{f(GLOW_CY)}" rx="{f(GLOW_RX)}" '
        f'ry="{f(GLOW_RY)}" fill="url(#bandglow)"/>',
    ]


def skeleton(alpha=1.0):
    """Drawn in every state, including the ones with no data at all."""
    return [
        line(LEFT_EDGE, BAND_Y, RIGHT_EDGE, BAND_Y, MUTED_DEEP, BAND_HAIRLINE, alpha),
        line(AXIS, BAND_Y - ZERO_HALF, AXIS, BAND_Y + ZERO_HALF, MUTED_DEEP, ZERO_WIDTH, alpha),
        txt('cap', LEFT_EDGE, LIMIT_BASELINE, '100 кВт', 'start', MUTED_DEEP, alpha),
        txt('cap', RIGHT_EDGE, LIMIT_BASELINE, '300 кВт', 'end', MUTED_DEEP, alpha),
    ]


def band(kw, generation=None, peak_kw=None, alpha=1.0, on_band=True, tail=None):
    """The one bar left on the panel, and the engine's share drawn behind its tip.

    ink is what the battery pays, blue is what the engine pays, and the tip is
    what the wheels asked for - the jury's second correction. That reading is only
    true if `GENERATION_KW` is not already inside `POWER_KW`, which has not been
    logged on this car, so [on_band] False draws the same fact without the claim.

    [tail] is where the tip has been over the last ten seconds, as one smear under
    the live body. It is the third history on the panel and the only one that is
    not a box, because the band already has an axis and a zero: the interval can
    be drawn on the instrument itself rather than beside it.
    """
    out = []
    if kw is None:
        return out
    tip = band_x(kw)
    top = BAND_Y - BAND_BODY / 2
    returning = kw < -FLOOR_KW
    base = RETURN if returning else INK
    ident = 'bandfillr' if returning else 'bandfill'
    if tail:
        x0, x1 = sorted((band_x(tail[0]), band_x(tail[1])))
        out.append(rect(x0, top, x1 - x0, BAND_BODY, base, opacity=TAIL_ALPHA * alpha))
    if abs(kw) > FLOOR_KW:
        x0, x1 = (tip, AXIS) if returning else (AXIS, tip)
        out.append(
            f'<defs><linearGradient id="{ident}" gradientUnits="userSpaceOnUse" '
            f'x1="{f(AXIS)}" y1="0" x2="{f(tip)}" y2="0">'
            f'<stop offset="0" stop-color="{base}" stop-opacity="0.55"/>'
            f'<stop offset="1" stop-color="{PEAK if not returning else RETURN_INK}" '
            f'stop-opacity="1"/></linearGradient></defs>')
        out.append(f'<rect x="{f(x0)}" y="{f(top)}" width="{f(x1 - x0)}" '
                   f'height="{f(BAND_BODY)}" fill="url(#{ident})" opacity="{f(alpha)}"/>')
    if generation:
        if on_band:
            far = band_x((kw or 0.0) + generation)
            out.append(rect(tip, top, far - tip, BAND_BODY, RETURN, opacity=alpha))
        else:
            far = AXIS + sweep(generation) * BAND_HALF
            out.append(rect(AXIS, GEN_LINE_Y, far - AXIS, GEN_LINE_H, RETURN, opacity=alpha))
    if peak_kw is not None and abs(peak_kw) > FLOOR_KW:
        px = band_x(peak_kw)
        out.append(line(px, BAND_Y - BAND_BODY / 2 - 3, px, BAND_Y + BAND_BODY / 2 + 3,
                        PEAK, 3.0, alpha))
    return out


def hero(kw, colour=None, alpha=1.0):
    """The one figure read on the move, and the one that keeps its unit beside it."""
    if kw is None:
        return []
    c = colour or (RETURN_INK if kw < -FLOOR_KW else INK)
    return [
        txt('hr', HERO_FIELD_RIGHT, HERO_BASELINE, f'{abs(kw):.0f}', 'end', c, alpha),
        txt('un', HERO_UNIT_X, HERO_BASELINE, 'кВт', 'start', MUTED, alpha),
    ]


def left_corner(s):
    """БАТАРЕЯ · В: the pack's volts, and how far they fall under load."""
    a = s.get('left_alpha', 1.0)
    live = s.get('volts') is not None
    out = [txt('ttl', LEFT_EDGE, CORNER_TITLE, 'БАТАРЕЯ · В' if live else 'БАТАРЕЯ',
               'start', MUTED_DEEP, a)]
    if live:
        out.append(txt('fg', LEFT_FIELD_RIGHT, CORNER_FIGURE, f'{s["volts"]:.0f}', 'end', INK, a))
    # No resting window, no sag rail - an estimate written as a number is a lie
    # with a unit on it, and the first minute of a drive is when it would be
    # wrong. Nor is there a line at rest: sag is what a load does to the pack,
    # and "просадка 0 В" on a parked car is a row spent saying nothing. And a
    # negative sag is not a sag: under regeneration the pack sits *above* its
    # resting volts, and "просадка −12 В" was the panel reporting a fall upward.
    sag = s.get('sag')
    if sag is not None and sag > 0:
        out.append(txt('cap', LEFT_EDGE, CORNER_LINE,
                       f'просадка {sag:.0f} В', 'start', MUTED_DEEP, a))
    return out


def right_corner(s):
    """ДВС: the heading is always there, dimmed while the engine sleeps.

    It gains its unit when there is something to measure. A dimmed «ДВС · ОБ/МИН»
    over an empty corner would be advertising an instrument that is not there.
    """
    a = s.get('right_alpha', 1.0)
    live = bool(s.get('engine'))
    out = [txt('ttl', RIGHT_EDGE, CORNER_TITLE, 'ДВС · ОБ/МИН' if live else 'ДВС', 'end',
               MUTED_DEEP, a if live else a * 0.55)]
    if not live:
        return out
    if s.get('rpm') is not None:
        out.append(txt('fg', RIGHT_FIELD_RIGHT, CORNER_FIGURE, f'{s["rpm"]:.0f}', 'end', INK, a))
    # «+14 кВт», not «14 кВт в батарею». The sentence needed 180 units and this
    # corner has 138 at that baseline, so four of its five words were being drawn
    # behind the vehicle's own graphics. The plus and the RETURN blue say the same
    # thing in the panel's own language, and the shelf below names it in full.
    if s.get('generation'):
        out.append(txt('cap', RIGHT_EDGE, CORNER_LINE,
                       f'+{s["generation"]:.0f} кВт', 'end', RETURN_INK, a))
    return out


def left_shelf(s):
    """Temperatures, back on the panel because the owner missed them.

    The exception is the figure itself changing colour, not a sentence somewhere
    else: a driver who has learned that these are normally ink does not need to be
    told in words that one of them is not.

    The three motors are three figures under one word, in `motorTemps` order -
    front, rear left, rear right - each coloured by its own level. One number was
    the owner's complaint about this shelf and the answer was already in the data:
    the rear pair is per-side, so a single reading was throwing two thirds of what
    the car reports away, and the interesting case is exactly the one where they
    differ.
    """
    a = s.get('left_alpha', 1.0)
    temps = s.get('temps')
    out = [txt('ttl', LEFT_EDGE, SHELF_HEADER, 'ТЕМПЕРАТУРЫ', 'start', MUTED_DEEP, a)]
    if not temps:
        return out

    def cell(index, word):
        left, _ = left_cell(index)
        out.append(txt('cl', left, SHELF_CAPTION, word, 'start', MUTED_DEEP, a))
        return left

    def degrees(x, value, level):
        # A degree sign belongs against its digits, so it rides at the reading's
        # own size and touches the field's right edge; the field is three
        # characters wide, which is what "-8" in January and "102" in July both need.
        out.append(txt('rd', x, SHELF_FIGURE, value, 'end', LEVEL[level], a))
        out.append(txt('un2', x, SHELF_FIGURE, '°', 'start', MUTED, a))

    left = cell(0, 'БАТАРЕЯ')
    degrees(left + TEMP_FIELD, *temps['pack'])

    left = cell(1, 'МОТОРЫ')
    for index, (value, level) in enumerate(temps['motors']):
        degrees(left + index * MOTOR_PITCH + MOTOR_FIELD, value, level)

    left = cell(2, 'ИНВЕРТОР')
    degrees(left + TEMP_FIELD, *temps['inverter'])

    spread = s.get('spread')
    if spread:
        value, level = spread
        left = cell(3, 'РАЗБРОС')
        out.append(txt('rd', left + TEMP_FIELD, SHELF_FIGURE, value, 'end', LEVEL[level], a))
        # "44" and "мВ" set flush read as one token, which is the opposite of what
        # the exception cell is for; a degree has no such problem and no gap.
        out.append(txt('un2', left + TEMP_FIELD + STEP, SHELF_FIGURE, 'мВ', 'start', MUTED, a))
    return out


def engine_box(s):
    """Two minutes of the combustion half, where the trip balance stands otherwise.

    `EngineTrace` has kept this history all along and nothing has ever drawn it on
    the cluster. It answers the two questions a number cannot - how long the engine
    has been holding a generating speed, and whether the kilowatts it puts back are
    steady or sagging - and it costs the panel nothing while the engine sleeps,
    because then it is not there at all.

    Both runs share one box height and neither carries an axis: revolutions
    linearly against 3000 (this engine is a generator, not a redline), generation
    by the same square root `ClusterReadout.generationFraction` uses. The window
    the generator actually works in is drawn as a faint zone rather than two
    labelled rules, so the line has somewhere to be without another bar on the panel.
    """
    a = s.get('right_alpha', 1.0)
    rpm, gen = s['trace']
    x0, x1 = ENGINE_BOX_LEFT, ENGINE_BOX_RIGHT
    top, bottom = ENGINE_BOX_TOP, ENGINE_BOX_BOTTOM
    height = bottom - top
    step = (x1 - x0) / float(len(rpm) - 1)

    def y_rpm(value):
        return bottom - height * min(1.0, max(0.0, value / ENGINE_RPM_FULL))

    out = [rect(x0, y_rpm(ENGINE_WINDOW[1]), x1 - x0,
                y_rpm(ENGINE_WINDOW[0]) - y_rpm(ENGINE_WINDOW[1]), MUTED_DEEP, opacity=0.06 * a),
           line(x0, bottom, x1, bottom, MUTED_DEEP, BAND_HAIRLINE, a)]

    area = [(x0 + step * i, bottom - height * math.sqrt(
        min(1.0, max(0.0, v / ENGINE_GEN_FULL))))
        for i, v in enumerate(gen) if v is not None]
    if len(area) > 1:
        # The edge carries the shape and the fill only says "this is an area".
        # Drawn as a solid blue slab it was the second heaviest thing on a panel
        # whose whole argument is that the band is the only lit one.
        path = (f'M {f(area[0][0])} {f(bottom)} L ' +
                ' L '.join(f'{f(x)} {f(y)}' for x, y in area) +
                f' L {f(area[-1][0])} {f(bottom)} Z')
        out.append(f'<path d="{path}" fill="{RETURN}" opacity="{f(0.30 * a)}"/>')
        edge = 'M ' + ' L '.join(f'{f(x)} {f(y)}' for x, y in area)
        out.append(f'<path d="{edge}" fill="none" stroke="{RETURN_INK}" stroke-width="1.2" '
                   f'stroke-linejoin="round" opacity="{f(a)}"/>')

    run = [(x0 + step * i, y_rpm(v)) for i, v in enumerate(rpm) if v is not None]
    if len(run) > 1:
        d = 'M ' + ' L '.join(f'{f(x)} {f(y)}' for x, y in run)
        out.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="1.8" '
                   f'stroke-linejoin="round" stroke-linecap="round" opacity="{f(a)}"/>')
        out.append(f'<circle cx="{f(run[-1][0])}" cy="{f(run[-1][1])}" r="3.4" '
                   f'fill="{INK}" opacity="{f(a)}"/>')

    # The two runs are named once, on the shelf's own caption row, in their own
    # colours - which is the whole legend a two-series box needs on a panel where
    # blue has meant "back into the pack" since the first drawing.
    out.append(txt('cl', RIGHT_EDGE, SHELF_CAPTION, 'ГЕНЕРАЦИЯ', 'end', RETURN_INK, a))
    out.append(txt('cl', RIGHT_EDGE - W_GENERATION - CELL_GAP, SHELF_CAPTION,
                   'ОБОРОТЫ', 'end', MUTED_DEEP, a))
    return out


def right_shelf(s):
    """The trip's energy - or, while the engine runs, the engine's own two minutes.

    The first drawing wrote ПОТРАЧЕНО / ВЕРНУЛА / ДВС over three figures and the
    owner read the third as revolutions - a fair reading, since "ДВС" beside a
    number is what a rev counter looks like. The heading now carries both the
    subject and the unit, and each word says which direction the energy went.

    A running engine takes the shelf because the trip balance is the one block on
    the panel that is equally true a minute later: it is an integral, it moves
    slowly, and it is back five seconds after the engine stops. The engine's own
    history is true only while the engine is running.
    """
    a = s.get('right_alpha', 1.0)
    if s.get('trace'):
        return [txt('ttl', RIGHT_EDGE, SHELF_HEADER, 'ДВС · 2 МИН', 'end', MUTED_DEEP,
                    a)] + engine_box(s)
    trip = s.get('trip')
    out = [txt('ttl', RIGHT_EDGE, SHELF_HEADER, 'ЗА ПОЕЗДКУ · КВТ·Ч', 'end', MUTED_DEEP, a)]
    if not trip:
        return out
    # Cell 2 is the leftmost, so the row reads out of the battery, back from the
    # brakes, in from the engine. A zero is never drawn: on a car whose engine has
    # not started, "0,0" is the loudest thing on the panel and says nothing.
    present = [(word, colour, trip[key]) for key, word, colour in (
        ('spent', 'ИЗ БАТАРЕИ', INK),
        ('regen', 'РЕКУПЕРАЦИЯ', RETURN_INK),
        ('ice', 'ОТ ДВС', RETURN_INK),
    ) if trip.get(key)]
    for order, (word, colour, value) in enumerate(present):
        _, right = right_cell(len(present) - 1 - order)
        out.append(txt('fg', right, SHELF_FIGURE, comma(value), 'end', colour, a))
        out.append(txt('cl', right, SHELF_CAPTION, word, 'end', MUTED_DEEP, a))
    return out


def petal_history(bars, alpha=1.0):
    """Three kilometres of closed buckets, beside the figure they average to.

    `ConsumptionLog` closes one every 100 m and the cluster's window is thirty of
    them, so the box has a fixed slot count and a fixed width and does not stretch
    as the history fills - the front is simply empty until the road is there.

    The dashed rule is the same number the figure prints, which is what makes a
    box of thirty anonymous bars readable without an axis: the reader is not asked
    what a bar is worth, only whether it is above or below the line they can
    already read. The rule is MUTED_DEEP, not champagne - the head unit's chart
    uses ACCENT there and this panel has none.
    """
    if not bars:
        return []
    zero, top, bottom = PETAL_BASELINE, PETAL_BOX_TOP, PETAL_BOX_BOTTOM
    pos, neg = g.ceilings(bars)
    pitch = PETAL_BOX_W / len(bars)
    width = pitch * BAR_SHARE
    out = [line(PETAL_BOX_X, zero, PETAL_BOX_X + PETAL_BOX_W, zero,
                MUTED_DEEP, BAND_HAIRLINE, alpha)]
    for index, value in enumerate(bars):
        room = (zero - top) if value >= 0 else (bottom - zero)
        height = min(abs(value) / (pos if value >= 0 else neg), 1.0) * room
        if height <= 0:
            continue
        x = PETAL_BOX_X + pitch * index + (pitch - width) / 2
        y = zero - height if value >= 0 else zero
        fade = min(BAR_ALPHA, FADE_FLOOR + (BAR_ALPHA - FADE_FLOOR) * index / FADE_BARS)
        out.append(rect(x, y, width, height, RETURN if value < 0 else INK,
                        opacity=alpha * fade))
    average = g.average_consumption(bars)
    if average:
        y = zero - min(average / pos, 1.0) * (zero - top)
        out.append(line(PETAL_BOX_X, y, PETAL_BOX_X + PETAL_BOX_W, y,
                        MUTED_DEEP, BAND_HAIRLINE, alpha, dash='4 4'))
    return out


def petal(s):
    """What the last three kilometres cost, and the one sentence the panel allows."""
    a = s.get('petal_alpha', 1.0)
    if s.get('hint'):
        return [txt('cap', AXIS, PETAL_BASELINE, s['hint'], 'middle', MUTED, a)]
    out = petal_history(s.get('bars'), a)
    value, unit = s.get('petal'), s.get('petal_unit')
    if value is None:
        return out
    colour = RETURN_INK if s.get('petal_returning') else INK
    return out + [
        txt('fg', PETAL_FIGURE_RIGHT, PETAL_BASELINE, value, 'end', colour, a),
        txt('un', PETAL_UNIT_X, PETAL_BASELINE, unit, 'start', MUTED, a),
    ]


def scene(s):
    """One complete panel, in the order the app paints it."""
    body = keepout()
    body += glow(s.get('kw') or 0.0, s.get('glow', 0.0), s.get('glow_colour'),
                 s.get('alpha', 1.0))
    body += skeleton(s.get('skeleton_alpha', 1.0))
    body += band(s.get('kw'), s.get('generation') if s.get('engine') else None,
                 s.get('peak'), s.get('alpha', 1.0), s.get('on_band', True), s.get('tail'))
    body += hero(s.get('kw'), s.get('hero_colour'), s.get('alpha', 1.0))
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
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500&amp;family=Roboto+Mono:wght@200;300;400&amp;display=swap" rel="stylesheet">
  <style>
    body { margin:0; background:%(bg)s; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    .hr { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(hero)spx; fill:%(ink)s; }
    .fg { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(figure)spx; fill:%(ink)s; }
    .rd { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(reading)spx; fill:%(ink)s; }
    .ttl { font-size:%(caption)spx; font-weight:500; letter-spacing:%(tracking)sem; fill:%(muted_deep)s; }
    .cap { font-size:%(caption)spx; font-weight:400; letter-spacing:%(tracking)sem; fill:%(muted_deep)s; }
    /* A word standing under its own number is a caption, not a heading, and
       tracking is what ran "РЕКУПЕРАЦИЯ" out of its cell into the next one. */
    .cl { font-size:%(caption)spx; font-weight:400; fill:%(muted_deep)s; }
    .un { font-size:%(caption)spx; font-weight:400; fill:%(muted)s; }
    /* The degree sign rides with the reading it belongs to, so it takes the
       reading's own face and size - set at the caption size it read as a
       footnote stuck to a number. */
    .un2 { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(reading)spx; fill:%(muted)s; }
    .keep { font-size:%(note)spx; letter-spacing:%(tracking)sem; fill:#3F434D; }
    /* Used on an HTML div as well as in SVG, and `fill` does nothing to a div:
       the scene captions rendered black on black and the states board came back
       looking like eleven unlabelled panels. */
    .sn { font-size:%(note)spx; letter-spacing:%(tracking)sem; fill:%(muted)s; color:%(muted)s; }
    /* Annotations name the very lines they stand on, so each one carries a black
       halo through paint-order rather than a measured backing rect - the plan
       board is the one place where text and geometry are meant to coincide. */
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

CALM_BARS = consumption_history(16.8)
ENGINE_TRACE = engine_history()

CALM = dict(
    kw=34.0, glow=0.16, peak=68.0, tail=(12.0, 68.0),
    volts=552.0, sag=9.0, temps=COOL,
    trip=dict(spent=12.4, regen=3.1),
    bars=CALM_BARS, petal='16,8', petal_unit='кВт·ч/100 км',
)

STATES = [
    ('Первые секунды · шина ещё не ответила, корзин расхода тоже нет', dict(
        kw=None, glow=0.05, volts=None, sag=None, temps=None, trip=None,
        bars=None, petal=None)),
    ('Спокойная езда · ДВС спит, в лепестке 3 км по 100 м', CALM),
    ('Рекуперация · сторона и цвет меняются, ничего не появляется', dict(
        kw=-42.0, glow=0.18, peak=-58.0, tail=(-58.0, 9.0),
        volts=573.0, sag=-12.0, temps=COOL,
        trip=dict(spent=12.6, regen=3.4),
        bars=consumption_history(11.2), petal='11,2', petal_unit='кВт·ч/100 км')),
    ('ДВС генерирует · правая полка отдана его двум минутам, синий шов за ink-концом', dict(
        kw=28.0, glow=0.16, peak=52.0, tail=(9.0, 52.0),
        engine=True, rpm=1780.0, generation=14.0, trace=ENGINE_TRACE,
        volts=548.0, sag=13.0, temps=WORKED,
        bars=consumption_history(17.4), petal='17,4', petal_unit='кВт·ч/100 км')),
    ('ДВС генерирует · запасное рисование, если генерация уже внутри POWER_KW', dict(
        kw=28.0, glow=0.16, peak=52.0, tail=(9.0, 52.0),
        engine=True, rpm=1780.0, generation=14.0, trace=ENGINE_TRACE, on_band=False,
        volts=548.0, sag=13.0, temps=WORKED,
        bars=consumption_history(17.4), petal='17,4', petal_unit='кВт·ч/100 км')),
    ('Стоим на P · коробка остаётся, цифра — средний за поездку', dict(
        kw=1.4, glow=0.06,
        volts=561.0, sag=None, temps=COOL,
        trip=dict(spent=12.4, regen=3.1),
        bars=CALM_BARS, petal='14,2', petal_unit='кВт·ч/100 км за поездку')),
    ('Зарядка от розетки · километров нет, коробки тоже; дышит только свечение', dict(
        kw=-7.0, glow=0.20, glow_colour=RETURN, hero_colour=RETURN_INK,
        volts=584.0, sag=None, temps=PARKED,
        trip=dict(spent=12.4, regen=3.1),
        bars=None, petal='2:15', petal_unit='до полной')),
    ('Потеря связи · значения замирают, скелет остаётся, текста нет', dict(
        kw=34.0, glow=0.07, alpha=0.45, left_alpha=0.45, right_alpha=0.45,
        petal_alpha=0.45, tail=(12.0, 68.0), volts=552.0, sag=9.0, temps=COOL,
        trip=dict(spent=12.4, regen=3.1),
        bars=CALM_BARS, petal='16,8', petal_unit='кВт·ч/100 км')),
    ('Ночь · падает яркость, оттенки не меняются никогда', dict(
        kw=34.0, glow=0.10, left_alpha=0.85, right_alpha=0.85, petal_alpha=0.85,
        skeleton_alpha=0.85, peak=68.0, tail=(12.0, 68.0),
        volts=552.0, sag=9.0, temps=COOL,
        trip=dict(spent=12.4, regen=3.1),
        bars=CALM_BARS, petal='16,8', petal_unit='кВт·ч/100 км')),
    ('Исключение · это сама цифра, сменившая цвет, плюс четвёртая ячейка', dict(
        kw=34.0, glow=0.16, peak=68.0, tail=(12.0, 68.0),
        volts=552.0, sag=9.0, temps=HOT, spread=('44', 'alert'),
        trip=dict(spent=12.4, regen=3.1),
        bars=CALM_BARS, petal='16,8', petal_unit='кВт·ч/100 км')),
    ('Нет ADB-ключа · указание, что сделать — не сообщение об ошибке', dict(
        kw=None, glow=0.0, volts=None, sag=None, temps=None, trip=None,
        bars=None, petal=None,
        hint='ADB-ключ не подтверждён · Помощь → Диагностика')),
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
        line(0, PETAL_FLOOR, W, PETAL_FLOOR, WARNING, 1.2, 0.5),
    ]

    def outline(x, y, w, h, dash='4 5', colour=None, opacity=0.45):
        return (f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" fill="none" '
                f'stroke="{colour or RETURN}" stroke-width="1.2" stroke-dasharray="{dash}" '
                f'opacity="{f(opacity)}"/>')

    # The cell grid both shelves stand on, drawn rather than described.
    cell_top = SHELF_FIGURE - CAP * FIGURE
    cell_h = SHELF_CAPTION - SHELF_FIGURE + CAP * FIGURE + 6
    for index in range(4):
        left, right = left_cell(index)
        body.append(outline(left, cell_top, right - left, cell_h))
    for index in range(3):
        left, right = right_cell(index)
        body.append(outline(left, cell_top, right - left, cell_h))
    # The three motors inside their one cell.
    motors_left, _ = left_cell(1)
    for index in range(3):
        body.append(outline(motors_left + index * MOTOR_PITCH, cell_top,
                            MOTOR_FIELD + W_DEGREE, cell_h, dash='2 4', opacity=0.7))

    # The two boxes the histories live in, and the hero's own field.
    body.append(outline(ENGINE_BOX_LEFT, ENGINE_BOX_TOP, ENGINE_BOX_RIGHT - ENGINE_BOX_LEFT,
                        ENGINE_BOX_BOTTOM - ENGINE_BOX_TOP, dash='2 4', colour=WARNING,
                        opacity=0.8))
    body.append(outline(PETAL_BOX_X, PETAL_BOX_TOP, PETAL_BOX_W,
                        PETAL_BOX_BOTTOM - PETAL_BOX_TOP, dash='2 4', colour=WARNING,
                        opacity=0.8))
    body.append(outline(HERO_FIELD_LEFT, HERO_CAP_TOP, HERO_FIELD_W,
                        HERO_BASELINE - HERO_CAP_TOP))
    body.append(outline(PETAL_FIGURE_RIGHT - PETAL_FIELD_W, PETAL_BASELINE - CAP * FIGURE,
                        PETAL_FIELD_W, CAP * FIGURE))

    marks = [
        (LEFT_EDGE, CORNER_TITLE, f'заголовок угла · 18 · y {CORNER_TITLE:.0f} · '
                                  f'единица живёт здесь, а не рядом с цифрой'),
        (LEFT_EDGE, CORNER_FIGURE, f'цифра угла · 52 · y {CORNER_FIGURE:.0f} · '
                                   f'поле 3 знака {LEFT_FIELD_X:.0f}…{LEFT_FIELD_RIGHT:.1f}'),
        (LEFT_EDGE, CORNER_LINE, f'строка угла · 18 · y {CORNER_LINE:.1f} · '
                                 f'апертура справа даёт здесь только '
                                 f'{RIGHT_EDGE - (W - aperture_reach(CORNER_LINE, True)):.0f}'),
        (LEFT_EDGE, SHELF_FIGURE, f'полка · слева 34, справа 52 · y {SHELF_FIGURE:.1f} · '
                                  f'ячейки {" / ".join(f"{w:.0f}" for w in LEFT_CELLS)} '
                                  f'· справа {RIGHT_CELL_W:.0f} · зазор {CELL_GAP:.0f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'подписи полки · 18 · y {SHELF_CAPTION:.1f} · '
                                   f'моторы: шаг {MOTOR_PITCH:.1f}, три поля по 2 знака'),
        (AXIS + 120, HERO_BASELINE, f'герой · 104 · базовая {HERO_BASELINE:.1f} · '
                                    f'капитель {HERO_CAP_TOP:.1f} · запас {HERO_CLEARANCE:.0f} · '
                                    f'поле 3 знака {HERO_FIELD_LEFT:.1f}…{HERO_FIELD_RIGHT:.1f}, '
                                    f'«кВт» на {HERO_UNIT_X:.1f}'),
        (AXIS + 120, SHELF_HEADER, f'заголовок правой полки · 18 · y {SHELF_HEADER:.0f} · '
                                   f'при живом ДВС — «ДВС · 2 МИН»'),
        (AXIS + 120, ENGINE_BOX_TOP, f'коробка ДВС · {ENGINE_BOX_LEFT:.0f}…{ENGINE_BOX_RIGHT:.0f} '
                                     f'· y {ENGINE_BOX_TOP:.0f}…{ENGINE_BOX_BOTTOM:.0f} · '
                                     f'{ENGINE_SLOTS} слотов по секунде'),
        (AXIS + 120, ENGINE_BOX_BOTTOM, 'обороты 0…3000 линейно, генерация корень до 100 кВт'),
        (AXIS + 120, BAND_Y, f'лента · y {BAND_Y:.1f} · тело {BAND_BODY:.0f} · '
                             f'корень, 300 вправо / 100 влево · '
                             f'след кончика за 10 с под телом'),
        (AXIS + 120, LIMIT_BASELINE, f'подписи пределов · 18 · y {LIMIT_BASELINE:.1f}'),
        (AXIS + 120, GLOW_CY, f'свечение · центр на ленте · ry {GLOW_RY:.1f} — '
                              f'гаснет ровно на нижней границе'),
        (AXIS + 120, PETAL_BOX_TOP, f'коробка расхода · {PETAL_BOX_X:.1f}…'
                                    f'{PETAL_BOX_X + PETAL_BOX_W:.1f} · '
                                    f'{PETAL_BARS} корзин по 100 м · шаг '
                                    f'{PETAL_BOX_W / PETAL_BARS:.2f} · ноль = базовая цифры'),
        (AXIS + 120, PETAL_BASELINE, f'лепесток · 52 · базовая {PETAL_BASELINE:.0f} · группа '
                                     f'«коробка + цифра + единица» {PETAL_GROUP_W:.1f} на оси'),
        (AXIS + 120, PETAL_FLOOR, f'пол композиции · y {PETAL_FLOOR:.0f}'),
    ]
    # Two anchors ten units apart cannot both carry a 13-unit line, so the words
    # are pushed apart and an elbow keeps each one attached to the height it is
    # about. Moving the anchors instead would be a plan board lying about the plan.
    lanes = {}
    for x, y, words in sorted(marks, key=lambda m: (m[0], m[1])):
        floor = lanes.get(x)
        text_y = y if floor is None else max(y, floor + NOTE * 1.5)
        lanes[x] = text_y
        body.append(line(x - 14, y, x - 8, y, WARNING, 1.2, 0.8))
        body.append(line(x - 8, y, x - 8, text_y, WARNING, 1.2, 0.4))
        body.append(line(x - 8, text_y, x - 3, text_y, WARNING, 1.2, 0.4))
        body.append(txt('nt', x, text_y + NOTE * 0.36, words))

    legend_x = 560.0
    body.append(txt('nt', legend_x, 60, 'синим пунктиром — апертуры: где нас видно,'))
    body.append(txt('nt', legend_x, 80, 'и сетка ячеек, на которой стоят обе полки;'))
    body.append(txt('nt', legend_x, 100, 'оранжевым — три коробки историй;'))
    body.append(txt('nt', legend_x, 120, 'штриховкой — где рисует машина'))
    body.append(txt('nt', legend_x, 140,
                    f'панель {W:.1f} × {H:.0f} · шаг {STEP:.0f} · лесенка 104 · 52 · 34 · 18'))
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
    return page(W, H, panel(plan_board()))


if __name__ == '__main__':
    open('ClusterContour.dc.html', 'w').write(board_contour())
    open('ClusterContourStates.dc.html', 'w').write(board_states())
    open('ClusterContourPlan.dc.html', 'w').write(board_plan())

    print(f'panel {W:.4f} x {H:.0f}, axis {AXIS:.4f}')
    print(f'hero baseline {HERO_BASELINE:.4f}, cap top {HERO_CAP_TOP:.4f}, '
          f'clearance {HERO_CAP_TOP - STOCK_TOP:.4f}, field '
          f'{HERO_FIELD_LEFT:.2f}…{HERO_FIELD_RIGHT:.2f}, «кВт» at {HERO_UNIT_X:.2f}')
    print(f'corner title {CORNER_TITLE:.0f}, figure {CORNER_FIGURE:.0f}, '
          f'line {CORNER_LINE:.2f}')
    print(f'  left field {LEFT_FIELD_X:.2f}…{LEFT_FIELD_RIGHT:.2f}, '
          f'aperture at figure {aperture_reach(CORNER_FIGURE):.2f}')
    print(f'  right field {RIGHT_FIELD_LEFT:.2f}…{RIGHT_FIELD_RIGHT:.2f}, '
          f'aperture limit {W - aperture_reach(CORNER_FIGURE, right=True):.2f}, '
          f'line room {RIGHT_EDGE - (W - aperture_reach(CORNER_LINE, right=True)):.2f}')
    print(f'shelf header {SHELF_HEADER:.0f}, figures {SHELF_FIGURE:.2f} '
          f'(cap 52 {SHELF_FIGURE - CAP * FIGURE:.2f} / 34 '
          f'{SHELF_FIGURE - CAP * READING:.2f}), captions {SHELF_CAPTION:.2f}')
    print(f'  left cells {[f"{w:.0f}" for w in LEFT_CELLS]} -> '
          f'{left_cell(0)[0]:.0f}…{LEFT_SHELF_RIGHT:.0f}, hero field at '
          f'{HERO_FIELD_LEFT:.2f}, clear by {HERO_FIELD_LEFT - LEFT_SHELF_RIGHT:.2f}')
    print(f'  motors run {MOTOR_RUN:.2f} in a cell of {LEFT_CELLS[1]:.0f}, '
          f'pitch {MOTOR_PITCH:.2f}')
    print(f'  right cells {right_cell(2)[0]:.0f}…{right_cell(0)[1]:.0f} against hero unit '
          f'{HERO_UNIT_X + HERO_UNIT_W:.2f}')
    print(f'engine box {ENGINE_BOX_LEFT:.0f}…{ENGINE_BOX_RIGHT:.0f} x '
          f'{ENGINE_BOX_TOP:.0f}…{ENGINE_BOX_BOTTOM:.0f}, slot '
          f'{(ENGINE_BOX_RIGHT - ENGINE_BOX_LEFT) / (ENGINE_SLOTS - 1):.2f}')
    print(f'petal group {PETAL_GROUP_W:.2f} centred: box {PETAL_BOX_X:.2f}…'
          f'{PETAL_BOX_X + PETAL_BOX_W:.2f}, figure ends {PETAL_FIGURE_RIGHT:.2f}, '
          f'unit {PETAL_UNIT_X:.2f}')
    print(f'  box y {PETAL_BOX_TOP:.2f}…{PETAL_BOX_BOTTOM:.2f} against stock '
          f'{STOCK_BOTTOM:.2f} and floor {PETAL_FLOOR:.0f}, bar pitch '
          f'{PETAL_BOX_W / PETAL_BARS:.2f}, petal half width at top '
          f'{petal_reach(PETAL_BOX_TOP):.1f}')
    print(f'band y {BAND_Y:.4f}, limits {LIMIT_BASELINE:.4f}, glow ry {GLOW_RY:.4f}')
    print(f'calm bars mean {g.average_consumption(CALM_BARS):.2f}, '
          f'ceilings {g.ceilings(CALM_BARS)}')
    for kw in (-100.0, -20.0, 0.0, 34.0, 60.0, 150.0, 300.0):
        print(f'  {kw:7.1f} kW -> x {band_x(kw):.2f}')
