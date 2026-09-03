#!/usr/bin/env python3
"""
Emit the Contour cluster artboards from the constants the app draws with.

The Contour is the concept that won the 2026-09 cluster contest
(`docs/cluster-contest-2026-09/`), with the jury's five corrections folded in,
and then the owner's own verdict on the first drawing folded in after that.

What the owner said, looking at the first cut: "выглядит неплохо, но
злоупотребление полосками; не понимаю, что такое потрачено / вернула / ДВС - ДВС
сначала принял за обороты, но там дробное число; жаль, что пожертвовали
температурами". Three real defects, and the second is the worst kind: a label
that reads as a different quantity than the one under it.

So there is exactly one bar left on the panel - the band - and everything that
used to be a second bar is a number with a word under it. The temperatures came
back as the left shelf, which also fills the hole the first drawing left in the
band's left flank. The trip's three figures are named in full, under a heading
that says what they are and what unit they are in.

Nothing here is typed. Every coordinate is derived the way `ContourPlan.kt` will
derive it - the apertures out of `ClusterModels.kt`'s own integer arithmetic,
everything else out of four decisions: the margin, the rhythm, the type ramp and
the cap height. Text is never measured: a number lives in a field sized by its
own digit count, and its unit hangs off the field rather than off the string, so
gaining a digit moves nothing.

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
# Measured in the real face rather than assumed: Roboto Mono advances half its
# size per digit, not the 0.6 the older generators guess at. Every reserve field
# on this board is that measurement times a digit count.
MONO = 0.5                       # what a monospaced digit advances
TRACKING = 0.12                  # InstrumentDensity.titleTracking

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


# ---------------------------------------------------------------- the skeleton

# Four steps, not one. A reserve field is the digits' advance; the box a browser
# and a Paint actually reserve runs wider still, and the audit kept catching
# "1780" touching its own "об/мин" until the gap reached this.
UNIT_GAP = STEP * 4

MARGIN = STEP * 6                       # the one outer margin: 48
LEFT_EDGE, RIGHT_EDGE = MARGIN, W - MARGIN

HERO_CLEARANCE = STEP * 3               # the jury's third correction
HERO_BASELINE = STOCK_TOP + HERO_CLEARANCE + CAP * HERO
HERO_CAP_TOP = HERO_BASELINE - CAP * HERO
# Three digits is the ceiling the scale can produce, so the field is knowable in
# advance. It is centred on the axis and the unit hangs off its right edge, which
# is what lets "9" and "300" leave every other coordinate where it was.
# The digits are right-aligned inside the field and the field, its gap and its
# unit are centred on the axis as one group. Centring the field alone left "кВт"
# stranded sixty units from a two-digit reading and touching a three-digit one;
# this way the unit is always the same short step from the last digit, the whole
# hero still stands on the car's axis, and the digits wander at most half a glyph
# either side of it.
HERO_FIELD_W = 3 * MONO * HERO
HERO_UNIT_W = 30.0                      # measured: "кВт" at 18 is 29.3
HERO_FIELD_RIGHT = AXIS + (HERO_FIELD_W + UNIT_GAP + HERO_UNIT_W) / 2 - UNIT_GAP - HERO_UNIT_W
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

# Each corner hangs its figure's field on the margin and puts the unit inboard of
# it. The field is a reserve sized by digit count, so the unit's anchor is a
# constant: "9" and "552" leave it exactly where "1780" does.
LEFT_FIELD_X = LEFT_EDGE                        # volts: three digits
LEFT_FIELD_RIGHT = LEFT_FIELD_X + 3 * MONO * FIGURE
LEFT_UNIT_X = LEFT_FIELD_RIGHT + UNIT_GAP

RIGHT_FIELD_RIGHT = RIGHT_EDGE                  # revolutions: four digits
RIGHT_FIELD_LEFT = RIGHT_FIELD_RIGHT - 4 * MONO * FIGURE
RIGHT_UNIT_X = RIGHT_FIELD_LEFT - UNIT_GAP

# ---- the two shelves, which are one family

# Both stand in the clear band's flanks, on one pair of baselines, in cells of one
# width. Only the figure size differs: the trip's kilowatt-hours are what the
# owner asked to see, the temperatures are what he asked to keep.
SHELF_HEADER = 180.0
SHELF_FIGURE = SHELF_HEADER + STEP * 2 + FIGURE
SHELF_CAPTION = SHELF_FIGURE + STEP * 2 + CAPTION

# The two shelves share their baselines, their caption size and their anatomy -
# a figure with its word under it - and that is what makes them one family. They
# do not share a cell width, because their payloads differ by half: three digits
# and a degree sign at 34 measure 82 units, "12,4" at 52 measures 125. Forcing one
# width on both would either crowd the trip's figures or leave the temperatures
# swimming, and the left shelf has to hold a fourth cell on an exception without
# reaching the hero's field.
LEFT_CELL_W, RIGHT_CELL_W, CELL_GAP = STEP * 14, STEP * 18, STEP
DEGREE_FIELD = 3 * MONO * READING       # three digits, then the degree sign
TRIP_FIELD = 4 * MONO * FIGURE          # "12,4" against the widest it can be

PETAL_UNIT_X = AXIS + STEP * 4
PETAL_FIGURE_RIGHT = PETAL_UNIT_X - STEP * 2
PETAL_BASELINE = 384.0
# Nothing is drawn below this: the petal reaches the panel edge, but its last
# twenty units are a narrowing sliver resting on an estimated boundary.
PETAL_FLOOR = 410.0

# EnergyScale: the band is the dial straightened out, same square root, same spans.
FULL_DISCHARGE_KW, FULL_REGEN_KW, FLOOR_KW = 300.0, 100.0, 0.5


def left_cell(index):
    """Left shelf cells run outward from the margin: battery, motors, inverter."""
    left = LEFT_EDGE + index * (LEFT_CELL_W + CELL_GAP)
    return left, left + LEFT_CELL_W


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
# definition it means "the live edge of the data" rather than "an interface".

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
            f'height="{f(h)}" fill="{colour}"{r}{o}/>')


def line(x0, y0, x1, y1, colour, width, opacity=None):
    o = f' opacity="{f(opacity)}"' if opacity is not None else ''
    return (f'<line x1="{f(x0)}" y1="{f(y0)}" x2="{f(x1)}" y2="{f(y1)}" '
            f'stroke="{colour}" stroke-width="{f(width)}"{o}/>')


def comma(value, digits=1):
    return f'{value:.{digits}f}'.replace('.', ',')


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


def band(kw, generation=None, peak_kw=None, alpha=1.0, on_band=True):
    """The one bar left on the panel, and the engine's share drawn behind its tip.

    ink is what the battery pays, blue is what the engine pays, and the tip is
    what the wheels asked for - the jury's second correction. That reading is only
    true if `GENERATION_KW` is not already inside `POWER_KW`, which has not been
    logged on this car, so [on_band] False draws the same fact without the claim.
    """
    out = []
    if kw is None:
        return out
    tip = band_x(kw)
    top = BAND_Y - BAND_BODY / 2
    returning = kw < -FLOOR_KW
    base = RETURN if returning else INK
    ident = 'bandfillr' if returning else 'bandfill'
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
    """The one figure read on the move, and now the one that says its unit."""
    if kw is None:
        return []
    c = colour or (RETURN_INK if kw < -FLOOR_KW else INK)
    return [
        txt('hr', HERO_FIELD_RIGHT, HERO_BASELINE, f'{abs(kw):.0f}', 'end', c, alpha),
        txt('un', HERO_UNIT_X, HERO_BASELINE, 'кВт', 'start', MUTED, alpha),
    ]


def left_corner(s):
    """БАТАРЕЯ: the pack's volts, and how far they fall under load."""
    a = s.get('left_alpha', 1.0)
    out = [txt('ttl', LEFT_EDGE, CORNER_TITLE, 'БАТАРЕЯ', 'start', MUTED_DEEP, a)]
    if s.get('volts') is not None:
        out.append(txt('fg', LEFT_FIELD_RIGHT, CORNER_FIGURE, f'{s["volts"]:.0f}', 'end', INK, a))
        out.append(txt('un', LEFT_UNIT_X, CORNER_FIGURE, 'В', 'start', MUTED, a))
    # No resting window, no sag line - an estimate written as a number is a lie
    # with a unit on it, and the first minute of a drive is when it would be
    # wrong. Nor is there a line at rest: sag is what a load does to the pack,
    # and "просадка 0 В" on a parked car is a row spent saying nothing.
    if s.get('sag') is not None:
        out.append(txt('cap', LEFT_EDGE, CORNER_LINE,
                       f'просадка {s["sag"]:.0f} В', 'start', MUTED_DEEP, a))
    return out


def right_corner(s):
    """ДВС: the heading is always there, dimmed while the engine sleeps."""
    a = s.get('right_alpha', 1.0)
    live = bool(s.get('engine'))
    out = [txt('ttl', RIGHT_EDGE, CORNER_TITLE, 'ДВС', 'end', MUTED_DEEP,
               a if live else a * 0.55)]
    if not live:
        return out
    if s.get('rpm') is not None:
        out.append(txt('fg', RIGHT_FIELD_RIGHT, CORNER_FIGURE, f'{s["rpm"]:.0f}', 'end', INK, a))
        out.append(txt('un', RIGHT_UNIT_X, CORNER_FIGURE, 'об/мин', 'end', MUTED, a))
    if s.get('generation'):
        out.append(txt('cap', RIGHT_EDGE, CORNER_LINE,
                       f'{s["generation"]:.0f} кВт в батарею', 'end', RETURN_INK, a))
    return out


def left_shelf(s):
    """Temperatures, back on the panel because the owner missed them.

    The exception is the figure itself changing colour, not a sentence somewhere
    else: a driver who has learned that these three are normally ink does not need
    to be told in words that one of them is not.
    """
    a = s.get('left_alpha', 1.0)
    cells = list(s.get('temps') or ())
    spread = s.get('spread')
    if spread:
        cells = cells + [spread]
    out = [txt('ttl', LEFT_EDGE, SHELF_HEADER, 'ТЕМПЕРАТУРЫ', 'start', MUTED_DEEP, a)]
    for index, cell in enumerate(cells):
        value, word, level, unit = cell
        left, _ = left_cell(index)
        anchor = left + DEGREE_FIELD
        out.append(txt('rd', anchor, SHELF_FIGURE, value, 'end', LEVEL[level], a))
        # A degree sign belongs against its digits; a word does not. "44" and
        # "мВ" set flush read as one token, which is the opposite of what the
        # exception cell is for.
        gap = 0.0 if unit == '°' else STEP
        out.append(txt('un2', anchor + gap, SHELF_FIGURE, unit, 'start', MUTED, a))
        out.append(txt('cl', left, SHELF_CAPTION, word, 'start', MUTED_DEEP, a))
    return out


def right_shelf(s):
    """The trip's energy, named so it needs no context.

    The first drawing wrote ПОТРАЧЕНО / ВЕРНУЛА / ДВС over three figures and the
    owner read the third as revolutions - a fair reading, since "ДВС" beside a
    number is what a rev counter looks like, and the only thing saying otherwise
    was the fraction. The heading now carries both the subject and the unit, and
    each word says which direction the energy went.
    """
    a = s.get('right_alpha', 1.0)
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


def petal(s):
    """What the instant cost, and the one sentence the panel is still allowed."""
    a = s.get('petal_alpha', 1.0)
    if s.get('hint'):
        return [txt('cap', AXIS, PETAL_BASELINE, s['hint'], 'middle', MUTED, a)]
    value, unit = s.get('petal'), s.get('petal_unit')
    if value is None:
        return []
    colour = RETURN_INK if s.get('petal_returning') else INK
    return [
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
                 s.get('peak'), s.get('alpha', 1.0), s.get('on_band', True))
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

TEMPS = [('28', 'БАТАРЕЯ', 'normal', '°'),
         ('31', 'МОТОРЫ', 'normal', '°'),
         ('32', 'ИНВЕРТОР', 'normal', '°')]

CALM = dict(
    kw=34.0, glow=0.16, peak=68.0,
    volts=552.0, sag=9.0, temps=TEMPS,
    trip=dict(spent=12.4, regen=3.1),
    petal='16,8', petal_unit='кВт·ч/100 км',
)

STATES = [
    ('Первые секунды · шина ещё не ответила', dict(
        kw=None, glow=0.05, volts=None, sag=None, temps=None, trip=None, petal=None)),
    ('Спокойная езда · ДВС спит, его заголовок приглушён', CALM),
    ('Рекуперация · сторона и цвет меняются, ничего не появляется', dict(
        kw=-42.0, glow=0.18, peak=-58.0,
        volts=573.0, sag=-12.0, temps=TEMPS,
        trip=dict(spent=12.6, regen=3.4),
        petal='11,2', petal_unit='кВт·ч/100 км')),
    ('ДВС генерирует · синий шов за ink-концом ленты', dict(
        kw=28.0, glow=0.16, peak=52.0, engine=True, rpm=1780.0, generation=14.0,
        volts=548.0, sag=13.0,
        temps=[('29', 'БАТАРЕЯ', 'normal', '°'), ('44', 'МОТОРЫ', 'normal', '°'),
               ('51', 'ИНВЕРТОР', 'normal', '°')],
        trip=dict(spent=17.9, regen=4.0, ice=1.1),
        petal='17,4', petal_unit='кВт·ч/100 км')),
    ('ДВС генерирует · запасное рисование, если генерация уже внутри POWER_KW', dict(
        kw=28.0, glow=0.16, peak=52.0, engine=True, rpm=1780.0, generation=14.0,
        on_band=False, volts=548.0, sag=13.0,
        temps=[('29', 'БАТАРЕЯ', 'normal', '°'), ('44', 'МОТОРЫ', 'normal', '°'),
               ('51', 'ИНВЕРТОР', 'normal', '°')],
        trip=dict(spent=17.9, regen=4.0, ice=1.1),
        petal='17,4', petal_unit='кВт·ч/100 км')),
    ('Стоим на P · «на 100 км» за эту секунду — ложь, поэтому средний за поездку', dict(
        kw=1.4, glow=0.06,
        volts=561.0, sag=None, temps=TEMPS,
        trip=dict(spent=12.4, regen=3.1),
        petal='14,2', petal_unit='кВт·ч/100 км за поездку')),
    ('Зарядка от розетки · единственная циклическая анимация во всём концепте', dict(
        kw=-7.0, glow=0.20, glow_colour=RETURN, hero_colour=RETURN_INK,
        volts=584.0, sag=None,
        temps=[('31', 'БАТАРЕЯ', 'normal', '°'), ('24', 'МОТОРЫ', 'normal', '°'),
               ('26', 'ИНВЕРТОР', 'normal', '°')],
        trip=dict(spent=12.4, regen=3.1),
        petal='2:15', petal_unit='до полной')),
    ('Потеря связи · значения замирают, скелет остаётся, текста нет', dict(
        kw=34.0, glow=0.07, alpha=0.45, left_alpha=0.45, right_alpha=0.45,
        petal_alpha=0.45, volts=552.0, sag=9.0, temps=TEMPS,
        trip=dict(spent=12.4, regen=3.1),
        petal='16,8', petal_unit='кВт·ч/100 км')),
    ('Ночь · падает яркость, оттенки не меняются никогда', dict(
        kw=34.0, glow=0.10, left_alpha=0.85, right_alpha=0.85, petal_alpha=0.85,
        skeleton_alpha=0.85, peak=68.0,
        volts=552.0, sag=9.0, temps=TEMPS,
        trip=dict(spent=12.4, regen=3.1),
        petal='16,8', petal_unit='кВт·ч/100 км')),
    ('Исключение · это сама цифра, сменившая цвет, плюс четвёртая ячейка', dict(
        kw=34.0, glow=0.16, peak=68.0,
        volts=552.0, sag=9.0,
        temps=[('33', 'БАТАРЕЯ', 'normal', '°'), ('68', 'МОТОРЫ', 'watch', '°'),
               ('92', 'ИНВЕРТОР', 'alert', '°')],
        spread=('44', 'РАЗБРОС', 'alert', 'мВ'),
        trip=dict(spent=12.4, regen=3.1),
        petal='16,8', petal_unit='кВт·ч/100 км')),
    ('Нет ADB-ключа · указание, что сделать — не сообщение об ошибке', dict(
        kw=None, glow=0.0, volts=None, sag=None, temps=None, trip=None, petal=None,
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

    # The cell grid both shelves stand on, drawn rather than described.
    for index in range(4):
        left, right = left_cell(index)
        body.append(rect(left, SHELF_FIGURE - CAP * FIGURE, right - left,
                         SHELF_CAPTION - SHELF_FIGURE + CAP * FIGURE + 6,
                         'none', opacity=None).replace('fill="none"',
                                                       f'fill="none" stroke="{RETURN}" '
                                                       f'stroke-width="1.2" '
                                                       f'stroke-dasharray="4 5" opacity="0.45"'))
    for index in range(3):
        left, right = right_cell(index)
        body.append(rect(left, SHELF_FIGURE - CAP * FIGURE, right - left,
                         SHELF_CAPTION - SHELF_FIGURE + CAP * FIGURE + 6,
                         'none', opacity=None).replace('fill="none"',
                                                       f'fill="none" stroke="{RETURN}" '
                                                       f'stroke-width="1.2" '
                                                       f'stroke-dasharray="4 5" opacity="0.45"'))

    marks = [
        (LEFT_EDGE, CORNER_TITLE, f'заголовок угла · 18 · y {CORNER_TITLE:.0f}'),
        (LEFT_EDGE, CORNER_FIGURE, f'цифра угла · 52 · y {CORNER_FIGURE:.0f} · '
                                   f'поле 3 знака {LEFT_FIELD_X:.0f}…{LEFT_FIELD_RIGHT:.1f} · '
                                   f'единица на {LEFT_UNIT_X:.1f}'),
        (LEFT_EDGE, CORNER_LINE, f'строка угла · 18 · y {CORNER_LINE:.1f}'),
        (LEFT_EDGE, SHELF_FIGURE, f'полка · слева 34, справа 52 · y {SHELF_FIGURE:.1f} · '
                                  f'ячейки {LEFT_CELL_W:.0f} / {RIGHT_CELL_W:.0f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'подписи полки · 18 · y {SHELF_CAPTION:.1f}'),
        (AXIS + 120, HERO_BASELINE, f'герой · 104 · базовая {HERO_BASELINE:.1f} · '
                                    f'капитель {HERO_CAP_TOP:.1f} · запас {HERO_CLEARANCE:.0f} · '
                                    f'поле 3 знака, «кВт» на {HERO_UNIT_X:.1f}'),
        (AXIS + 120, SHELF_HEADER, f'заголовок правой полки · 18 · y {SHELF_HEADER:.0f}'),
        (AXIS + 120, BAND_Y, f'лента · y {BAND_Y:.1f} · тело {BAND_BODY:.0f} · '
                             f'корень, 300 вправо / 100 влево · единственная полоска'),
        (AXIS + 120, LIMIT_BASELINE, f'подписи пределов · 18 · y {LIMIT_BASELINE:.1f}'),
        (AXIS + 120, GLOW_CY, f'свечение · центр на ленте · ry {GLOW_RY:.1f} = '
                              f'до нижней границы, гаснет ровно на ней'),
        (AXIS + 120, PETAL_BASELINE, f'лепесток · 52 · базовая {PETAL_BASELINE:.0f} · '
                                     f'якорь — единица на x {PETAL_UNIT_X:.1f}'),
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

    legend_x = 480.0
    body.append(txt('nt', legend_x, 60, 'синим пунктиром — апертуры: где нас видно,'))
    body.append(txt('nt', legend_x, 80, 'и сетка ячеек, на которой стоят обе полки;'))
    body.append(txt('nt', legend_x, 100, 'штриховкой — где рисует машина'))
    body.append(txt('nt', legend_x, 120,
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
    print(f'  left field {LEFT_FIELD_X:.2f}…{LEFT_FIELD_RIGHT:.2f}, unit {LEFT_UNIT_X:.2f}, '
          f'aperture at figure {aperture_reach(CORNER_FIGURE):.2f}')
    print(f'  right field {RIGHT_FIELD_LEFT:.2f}…{RIGHT_FIELD_RIGHT:.2f}, '
          f'unit {RIGHT_UNIT_X:.2f}, aperture limit '
          f'{W - aperture_reach(CORNER_FIGURE, right=True):.2f}')
    print(f'shelf header {SHELF_HEADER:.0f}, figures {SHELF_FIGURE:.2f} '
          f'(cap 52 {SHELF_FIGURE - CAP * FIGURE:.2f} / 34 '
          f'{SHELF_FIGURE - CAP * READING:.2f}), captions {SHELF_CAPTION:.2f}')
    print(f'  left cells  {left_cell(0)[0]:.0f}…{left_cell(3)[1]:.0f} against hero field '
          f'{HERO_FIELD_LEFT:.2f}')
    print(f'  right cells {right_cell(2)[0]:.0f}…{right_cell(0)[1]:.0f} against hero unit '
          f'{HERO_UNIT_X + HERO_UNIT_W:.2f}')
    print(f'band y {BAND_Y:.4f}, limits {LIMIT_BASELINE:.4f}, glow ry {GLOW_RY:.4f}')
    for kw in (-100.0, -20.0, 0.0, 34.0, 60.0, 150.0, 300.0):
        print(f'  {kw:7.1f} kW -> x {band_x(kw):.2f}')
