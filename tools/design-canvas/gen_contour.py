#!/usr/bin/env python3
"""
Emit the Contour cluster artboards from the constants the app draws with.

The Contour is the concept that won the 2026-09 cluster contest
(`docs/cluster-contest-2026-09/`), with the jury's five corrections folded in. It
replaces the five islands, the arc, the bars and the sparkline with one line
across the whole panel, one 104 figure over it, and two wings that never both go
dark.

Nothing here is typed. Every coordinate is derived the way `ContourPlan.kt`
derives it - the apertures out of `ClusterModels.kt`'s own integer arithmetic,
everything else out of four decisions: the margin, the rhythm, the type ramp and
the cap height. `ContourBoardContractTest` reads the emitted board back and holds
it against the Kotlin, so neither record can move alone.

Text is never measured here. A number and its unit are separate <text> elements
placed at their own fixed anchors, which is exactly what the app does and exactly
why the old panel's "the graph jumps left and right" cannot happen: no coordinate
on this board is a function of a string.

    python3 gen_contour.py && python3 audit.py ClusterContour \
        ClusterContourStates ClusterContourPlan
"""
import math

import gen_cluster as g

f = g.f

# ---------------------------------------------------------------- the ramp

HERO, FIGURE, CAPTION = 104.0, 52.0, 18.0
NOTE = 13.0                      # the ramp's lowest rung a board is allowed to caption with
STEP = 8.0                       # InstrumentDensity.WIDE.step
CAP = 0.71                       # InstrumentPen.digitHeight / ContourPlan.CAP_HEIGHT
MONO = 0.6                       # what a monospaced digit advances
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

MARGIN = STEP * 6                       # the one outer margin: 48
LEFT_EDGE, RIGHT_EDGE = MARGIN, W - MARGIN

HERO_CLEARANCE = STEP * 3               # the jury's third correction
HERO_BASELINE = STOCK_TOP + HERO_CLEARANCE + CAP * HERO
HERO_CAP_TOP = HERO_BASELINE - CAP * HERO

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
# vehicle's own graphics can cut it. Centred on the *aperture* instead, the pool
# sat fifty units above the thing it belongs to and lit whatever the wings had put
# in the band's flanks, so the panel had two lit things and the rule is one.
GLOW_CY = BAND_Y
GLOW_RY = STOCK_BOTTOM - BAND_Y
GLOW_RX = 340.0

WING_TITLE = STEP * 4                   # 32
RAIL_A, RAIL_B = STEP * 7, STEP * 9     # 56, 72
RAIL_LEN = 200.0
WING_FIGURE = STEP * 16                 # 128
SHELF = STEP * 26                       # 208
# Three steps, not one. A rhythm step is measured baseline to cap top, and a
# figure's baseline is not its bottom: "12,4" hangs a comma below it, and Roboto
# Mono reserves a quarter of its size down there whatever the glyph does. At one
# step the audit found the number and its own word overlapping by nine pixels.
SHELF_CAPTION = SHELF + STEP * 3 + CAP * CAPTION
# Narrow enough that the three of them clear the hero. A 52 figure of four
# characters measures 125 units, and "ПОТРАЧЕНО" at 18 with tracking measures
# about the same, so 150 is the cell and the leftmost one still stops 146 units
# short of the hero's widest reading. At 180 it stopped 40 short and the panel
# read as four numbers of one family rather than one figure and its footnotes.
CELL_W, CELL_GAP = 150.0, STEP

PETAL_UNIT_X = AXIS + STEP * 4
PETAL_FIGURE_RIGHT = PETAL_UNIT_X - STEP * 2
PETAL_BASELINE = 384.0
RETURN_BAR_Y = 405.0
RETURN_BAR_H = 10.0
RETURN_BAR_HALF = 280.0
PETAL_FLOOR = 410.0

# EnergyScale: the band is the dial straightened out, same square root, same spans.
FULL_DISCHARGE_KW, FULL_REGEN_KW, FLOOR_KW = 300.0, 100.0, 0.5

# The sag rail reads the gap, not the pack's absolute volts - those are the 52
# under it. Its window is the resting voltage plus or minus this, because on the
# pack's own 480..620 V window a nine-volt sag is thirteen units of two hundred
# and the one detail nobody else's cluster has is invisible. Right is higher
# voltage, as physics has it and as the concept describes: under load the live
# mark falls left of rest, under regeneration it climbs past it.
SAG_WINDOW_V = 30.0
RPM_LOW, RPM_HIGH = 1000.0, 2600.0
GENERATION_FULL_KW = 100.0


def cell_right(index):
    return RIGHT_EDGE - index * (CELL_W + CELL_GAP)


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
GHOST = g.TRACK_MARK
BG = g.CLUSTER_BG

# The concept's one deliberate omission: champagne ACCENT is not drawn on this
# panel at all. Yellow means "decide something" in this car, and a permanent warm
# mark on an instrument is a false signal. DATA_PEAK plays its part, and by
# definition it means "the live edge of the data" rather than "an interface".


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


# ---------------------------------------------------------------- the pieces

def keepout():
    """Where the vehicle draws over us. Hatched, so the composition is judged against it."""
    lx, rx = LEFT_RX, RIGHT_RX
    return [
        '<defs>',
        '<pattern id="hatch" width="10" height="10" patternUnits="userSpaceOnUse" '
        'patternTransform="rotate(45)">'
        '<line x1="0" y1="0" x2="0" y2="10" stroke="rgba(218,225,235,0.07)" stroke-width="1.4"/>'
        '</pattern>',
        f'<mask id="topmask"><rect x="0" y="0" width="{f(W)}" height="{f(STOCK_TOP)}" '
        f'fill="#fff"/><ellipse cx="0" cy="0" rx="{f(lx)}" ry="{f(STOCK_TOP)}" fill="#000"/>'
        f'<ellipse cx="{f(W)}" cy="0" rx="{f(rx)}" ry="{f(STOCK_TOP)}" fill="#000"/></mask>',
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
    """The one pool of light: brightness is the magnitude, hue is the direction.

    Its vertical radius is exactly half the clear band, so the alpha reaches zero
    on the aperture's own boundary and the vehicle's graphics never cut it off.
    """
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
    """Drawn in every state, including the ones with no data at all.

    A panel that answers "am I alive" with a shape rather than with the words
    "no data" needs the shape to be there before the data is.
    """
    return [
        line(LEFT_EDGE, BAND_Y, RIGHT_EDGE, BAND_Y, MUTED_DEEP, BAND_HAIRLINE, alpha),
        line(AXIS, BAND_Y - ZERO_HALF, AXIS, BAND_Y + ZERO_HALF, MUTED_DEEP, ZERO_WIDTH, alpha),
        txt('cap', LEFT_EDGE, LIMIT_BASELINE, '100 кВт', 'start', MUTED_DEEP, alpha),
        txt('cap', RIGHT_EDGE, LIMIT_BASELINE, '300 кВт', 'end', MUTED_DEEP, alpha),
    ]


def band(kw, generation=None, peak_kw=None, alpha=1.0, on_band=True):
    """The pack's flow as a length, a side and a colour, with the engine's share behind it.

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
    """The one figure read on the move: monospaced, centred on the axis of the car.

    Centred is the only anchor that costs nothing when a digit is gained or lost -
    it grows half a glyph each way, and it has no neighbours to push.
    """
    if kw is None:
        return []
    c = colour or (RETURN_INK if kw < -FLOOR_KW else INK)
    return [txt('hr', AXIS, HERO_BASELINE, f'{abs(kw):.0f}', 'middle', c, alpha)]


def rail(x, y, length, fraction, colour, alpha=1.0, track=True):
    out = []
    h = 7.0
    if track:
        out.append(rect(x, y - h / 2, length, h, TRACK, rx=h / 2, opacity=alpha))
    if fraction:
        out.append(rect(x, y - h / 2, length * max(0.0, min(1.0, fraction)), h,
                        colour, rx=h / 2, opacity=alpha))
    return out


def sag_rail(volts, resting, alpha=1.0):
    """Rest at the centre, now beside it, and the gap between them lit.

    Nothing but the track until a resting window has actually closed. The scale
    *is* the reference: with no rest there is no centre, so a live mark would be a
    position on a ruler with no zero. That is the jury's fourth correction taken
    one step further, because it is the same argument.
    """
    centre = LEFT_EDGE + RAIL_LEN / 2
    parts = [rect(LEFT_EDGE, RAIL_A - 3.5, RAIL_LEN, 7.0, TRACK, rx=3.5, opacity=alpha)]
    if volts is None or resting is None:
        return parts

    delta = max(-SAG_WINDOW_V, min(SAG_WINDOW_V, volts - resting))
    live = centre + RAIL_LEN / 2 * (delta / SAG_WINDOW_V)
    lo, hi = min(centre, live), max(centre, live)
    colour = RETURN if delta > 0 else INK
    parts.append(rect(lo, RAIL_A - 3.5, hi - lo, 7.0, colour, rx=3.5, opacity=alpha))
    parts.append(line(centre, RAIL_A - 7, centre, RAIL_A + 7, MUTED_DEEP, 2.0, alpha))
    parts.append(line(live, RAIL_A - 7, live, RAIL_A + 7, INK, 2.5, alpha))
    return parts


def left_wing(s):
    """State of the pack, and the panel's only place for an exception."""
    a = s.get('left_alpha', 1.0)
    out = [txt('ttl', LEFT_EDGE, WING_TITLE, 'НАПРЯЖЕНИЕ · В', 'start', MUTED_DEEP, a)]
    if s.get('sag_as_rail', True):
        out += sag_rail(s.get('volts'), s.get('resting'), a)
    if s.get('volts') is not None:
        out.append(txt('fg', LEFT_EDGE, WING_FIGURE, f'{s["volts"]:.0f}', 'start', INK, a))
    exception = s.get('exception')
    if exception:
        colour = DANGER if s.get('exception_alert') else WARNING
        out.append(txt('fw', LEFT_EDGE, SHELF, exception, 'start', colour, a))
    elif not s.get('sag_as_rail', True) and s.get('sag_volts') is not None:
        out.append(txt('fg', LEFT_EDGE, SHELF, f'{s["sag_volts"]:.0f}', 'start', INK, a))
        out.append(txt('cap', LEFT_EDGE, SHELF_CAPTION, 'ПРОСАДКА В', 'start', MUTED_DEEP, a))
    return out


def right_wing(s):
    """Never dark. The engine while it is alive, the trip's balance while it sleeps.

    The jury's first correction, and the concept's own self-criticism before it:
    an asymmetrically dark panel is the empty-graph complaint turned inside out.
    """
    a = s.get('right_alpha', 1.0)
    out = []
    if s.get('engine'):
        out.append(txt('ttl', RIGHT_EDGE, WING_TITLE, 'ДВС · ОБ/МИН', 'end', MUTED_DEEP, a))
        rpm = s.get('rpm')
        share = None if rpm is None else (rpm - RPM_LOW) / (RPM_HIGH - RPM_LOW)
        out += rail(RIGHT_EDGE - RAIL_LEN, RAIL_A, RAIL_LEN, share, INK, a)
        gen = s.get('generation')
        gshare = None if not gen else math.sqrt(min(gen / GENERATION_FULL_KW, 1.0))
        out += rail(RIGHT_EDGE - RAIL_LEN, RAIL_B, RAIL_LEN, gshare, RETURN, a)
        if rpm is not None:
            out.append(txt('fg', RIGHT_EDGE, WING_FIGURE, f'{rpm:.0f}', 'end', INK, a))
        if gen is not None:
            out.append(txt('fg', cell_right(0), SHELF, f'{gen:.0f}', 'end', RETURN_INK, a))
            out.append(txt('cap', cell_right(0), SHELF_CAPTION, 'ГЕНЕРАЦИЯ КВТ', 'end',
                           MUTED_DEEP, a))
        return out

    out.append(txt('ttl', RIGHT_EDGE, WING_TITLE, 'ПОЕЗДКА · КВТ·Ч', 'end', MUTED_DEEP, a))
    balance = s.get('balance')
    if balance:
        # Left to right, in the order the sentence is read: what the trip spent,
        # what braking gave back, what the engine put in. Counting the cells from
        # the outside in and filling them in order printed it backwards, which is
        # the sort of thing only a picture catches.
        for index, (value, word) in enumerate(balance):
            cell = cell_right(len(balance) - 1 - index)
            # Blue is the panel's one rule about direction: energy going back into
            # the pack. A zero is not energy going anywhere, so it does not get to
            # claim the colour - and on a hybrid that has not started its engine
            # this is otherwise the loudest thing on the panel.
            if index == 0:
                colour = INK
            elif value:
                colour = RETURN_INK
            else:
                colour = MUTED
            out.append(txt('fg', cell, SHELF, f'{value:.1f}'.replace('.', ','), 'end', colour, a))
            out.append(txt('cap', cell, SHELF_CAPTION, word, 'end', MUTED_DEEP, a))
    return out


def petal(s):
    """What the instant cost, and the one sentence the panel is still allowed."""
    a = s.get('petal_alpha', 1.0)
    out = []
    if s.get('hint'):
        return [txt('cap', AXIS, PETAL_BASELINE, s['hint'], 'middle', MUTED, a)]
    value, unit = s.get('petal'), s.get('petal_unit')
    if value is not None:
        colour = RETURN_INK if s.get('petal_returning') else INK
        out.append(txt('fg', PETAL_FIGURE_RIGHT, PETAL_BASELINE, value, 'end', colour, a))
        out.append(txt('un', PETAL_UNIT_X, PETAL_BASELINE, unit, 'start', MUTED, a))
    share = s.get('return_share')
    if share is not None:
        left = AXIS - RETURN_BAR_HALF
        out.append(rect(left, RETURN_BAR_Y - RETURN_BAR_H / 2, RETURN_BAR_HALF * 2,
                        RETURN_BAR_H, TRACK, rx=RETURN_BAR_H / 2, opacity=a))
        # Ambient, not a reading: it carries no number and must not out-shout the
        # one figure in the petal that does.
        out.append(rect(left, RETURN_BAR_Y - RETURN_BAR_H / 2,
                        RETURN_BAR_HALF * 2 * max(0.0, min(1.0, share)), RETURN_BAR_H,
                        RETURN, rx=RETURN_BAR_H / 2, opacity=a * 0.72))
    return out


def scene(s):
    """One complete panel, in the order the app paints it."""
    body = keepout()
    body += glow(s.get('kw') or 0.0, s.get('glow', 0.0), s.get('glow_colour'),
                 s.get('alpha', 1.0))
    body += skeleton(s.get('skeleton_alpha', 1.0))
    body += band(s.get('kw'), s.get('generation') if s.get('engine') else None,
                 s.get('peak'), s.get('alpha', 1.0), s.get('on_band', True))
    body += hero(s.get('kw'), s.get('hero_colour'), s.get('alpha', 1.0))
    body += left_wing(s)
    body += right_wing(s)
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
    /* The exception is the one 52 on the panel that is words rather than a
       quantity, so it takes the sans face. Set in the mono face beside it, a
       Russian phrase reads as a console line. */
    .fw { font-weight:300; font-size:%(figure)spx; fill:%(ink)s; }
    .ttl { font-size:%(caption)spx; font-weight:500; letter-spacing:%(tracking)sem; fill:%(muted_deep)s; }
    .cap { font-size:%(caption)spx; font-weight:400; letter-spacing:%(tracking)sem; fill:%(muted_deep)s; }
    .un { font-size:%(caption)spx; font-weight:400; fill:%(muted)s; }
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
                       hero=f(HERO), figure=f(FIGURE), caption=f(CAPTION),
                       note=f(NOTE), tracking=TRACKING)


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

CALM = dict(
    kw=34.0, glow=0.16, peak=68.0,
    volts=552.0, resting=561.0,
    balance=[(12.4, 'ПОТРАЧЕНО'), (3.1, 'ВЕРНУЛА'), (0.0, 'ДВС')],
    petal='16,8', petal_unit='кВт·ч/100 км', return_share=0.25,
)

STATES = [
    ('Первые секунды · шина ещё не ответила', dict(
        kw=None, glow=0.05, volts=None, resting=None, balance=None,
        petal=None, return_share=None, skeleton_alpha=1.0)),
    ('Спокойная езда · ДВС спит, крыло держит баланс поездки', CALM),
    ('Рекуперация · сторона и цвет меняются, ничего не появляется', dict(
        kw=-42.0, glow=0.18, peak=-58.0,
        volts=573.0, resting=561.0,
        balance=[(12.6, 'ПОТРАЧЕНО'), (3.4, 'ВЕРНУЛА'), (0.0, 'ДВС')],
        petal='11,2', petal_unit='кВт·ч/100 км', return_share=0.27)),
    ('ДВС генерирует · синий шов за ink-концом ленты', dict(
        kw=28.0, glow=0.16, peak=52.0, engine=True, rpm=1780.0, generation=14.0,
        volts=548.0, resting=556.0,
        petal='17,4', petal_unit='кВт·ч/100 км', return_share=0.31)),
    ('ДВС генерирует · запасное рисование, если генерация уже внутри POWER_KW', dict(
        kw=28.0, glow=0.16, peak=52.0, engine=True, rpm=1780.0, generation=14.0,
        on_band=False, volts=548.0, resting=556.0,
        petal='17,4', petal_unit='кВт·ч/100 км', return_share=0.31)),
    ('Стоим на P · «на 100 км» без километров — ложь, поэтому кВт·ч за поездку', dict(
        kw=1.4, glow=0.06,
        volts=561.0, resting=561.0,
        balance=[(12.4, 'ПОТРАЧЕНО'), (3.1, 'ВЕРНУЛА'), (0.0, 'ДВС')],
        petal='12,4', petal_unit='кВт·ч за поездку', return_share=0.25)),
    ('Зарядка от розетки · единственная циклическая анимация во всём концепте', dict(
        kw=-7.0, glow=0.20, glow_colour=RETURN, hero_colour=RETURN_INK,
        volts=584.0, resting=561.0,
        balance=[(12.4, 'ПОТРАЧЕНО'), (3.1, 'ВЕРНУЛА'), (0.0, 'ДВС')],
        petal='2:15', petal_unit='до полной', return_share=0.25)),
    ('Потеря связи · значения замирают, скелет остаётся, текста нет', dict(
        kw=34.0, glow=0.07, alpha=0.45, left_alpha=0.45, right_alpha=0.45,
        petal_alpha=0.45, volts=552.0, resting=561.0,
        balance=[(12.4, 'ПОТРАЧЕНО'), (3.1, 'ВЕРНУЛА'), (0.0, 'ДВС')],
        petal='16,8', petal_unit='кВт·ч/100 км', return_share=0.25)),
    ('Ночь · падает яркость, оттенки не меняются никогда', dict(
        kw=34.0, glow=0.10, left_alpha=0.85, right_alpha=0.85, petal_alpha=0.85,
        skeleton_alpha=0.85, peak=68.0,
        volts=552.0, resting=561.0,
        balance=[(12.4, 'ПОТРАЧЕНО'), (3.1, 'ВЕРНУЛА'), (0.0, 'ДВС')],
        petal='16,8', petal_unit='кВт·ч/100 км', return_share=0.25)),
    ('Исключение · сервисные величины возвращаются одной строкой 52-го кегля', dict(
        kw=34.0, glow=0.16, peak=68.0,
        volts=552.0, resting=561.0, exception='разброс 44 мВ', exception_alert=True,
        balance=[(12.4, 'ПОТРАЧЕНО'), (3.1, 'ВЕРНУЛА'), (0.0, 'ДВС')],
        petal='16,8', petal_unit='кВт·ч/100 км', return_share=0.25)),
    ('Нет ADB-ключа · указание, что сделать — не сообщение об ошибке', dict(
        kw=None, glow=0.0, volts=None, resting=None, balance=None,
        petal=None, return_share=None,
        hint='ADB-ключ не подтверждён · Помощь → Диагностика')),
]

STATE_LABEL = 34.0
STATE_PITCH = H + STATE_LABEL + STEP * 4


# ---------------------------------------------------------------- the plan board

def plan_board():
    """The skeleton with every number on it, and the apertures it is placed against."""
    body = keepout()
    body += skeleton()

    # The three apertures, outlined rather than hatched: this board is about where
    # the room is, not about where somebody else's pixels are.
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

    # Every anchor the composition owns, named where it stands.
    marks = [
        (LEFT_EDGE, WING_TITLE, f'заголовок крыла · 18 · y {WING_TITLE:.0f}'),
        (LEFT_EDGE, RAIL_A, f'рейка A · y {RAIL_A:.0f} · длина {RAIL_LEN:.0f}'),
        (LEFT_EDGE, RAIL_B, f'рейка B · y {RAIL_B:.0f}'),
        (LEFT_EDGE, WING_FIGURE, f'цифра крыла · 52 · y {WING_FIGURE:.0f}'),
        (LEFT_EDGE, SHELF, f'полка · 52 · y {SHELF:.0f} · верх капители {SHELF - CAP * FIGURE:.1f}'),
        (LEFT_EDGE, SHELF_CAPTION, f'подпись полки · 18 · y {SHELF_CAPTION:.1f}'),
        (AXIS + 120, HERO_BASELINE, f'герой · 104 · базовая {HERO_BASELINE:.1f} · '
                                    f'капитель {HERO_CAP_TOP:.1f} · запас {HERO_CLEARANCE:.0f}'),
        (AXIS + 120, BAND_Y, f'лента · y {BAND_Y:.1f} · тело {BAND_BODY:.0f} · '
                             f'корень, 300 вправо / 100 влево'),
        (AXIS + 120, LIMIT_BASELINE, f'подписи пределов · 18 · y {LIMIT_BASELINE:.1f}'),
        (AXIS + 120, GLOW_CY, f'свечение · центр на ленте · ry {GLOW_RY:.1f} = '
                              f'до нижней границы, гаснет ровно на ней'),
        (AXIS + 120, PETAL_BASELINE, f'лепесток · 52 · базовая {PETAL_BASELINE:.0f} · '
                                     f'якорь — единица на x {PETAL_UNIT_X:.1f}'),
        (AXIS + 120, RETURN_BAR_Y, f'полоса возврата · y {RETURN_BAR_Y:.0f} · '
                                   f'пол композиции {PETAL_FLOOR:.0f}'),
    ]
    # Two anchors ten units apart cannot both carry a 13-unit line, so the words
    # are pushed apart and an elbow keeps each one attached to the height it is
    # about. The alternative - moving the anchors - would be a plan board lying
    # about the plan.
    lanes = {}
    for x, y, words in sorted(marks, key=lambda m: (m[0], m[1])):
        floor = lanes.get(x)
        text_y = y if floor is None else max(y, floor + NOTE * 1.5)
        lanes[x] = text_y
        body.append(line(x - 14, y, x - 8, y, WARNING, 1.2, 0.8))
        body.append(line(x - 8, y, x - 8, text_y, WARNING, 1.2, 0.4))
        body.append(line(x - 8, text_y, x - 3, text_y, WARNING, 1.2, 0.4))
        body.append(txt('nt', x, text_y + NOTE * 0.36, words))

    # The one empty quarter of the panel is the band's regeneration side, which is
    # exactly where a legend can stand without standing on anything.
    legend_x = LEFT_EDGE + 260
    body.append(txt('nt', legend_x, 196,
                    'синим пунктиром — апертуры: где нас видно'))
    body.append(txt('nt', legend_x, 216,
                    'штриховкой — где рисует машина'))
    body.append(txt('nt', legend_x, 236,
                    f'панель {W:.1f} × {H:.0f} · шаг {STEP:.0f} · '
                    f'лесенка 104 · 52 · 18'))
    body.append(txt('nt', legend_x, 256,
                    'поля фиксированной разрядности: ни одна координата не '
                    'считается из ширины строки'))
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
    print(f'apertures: top {STOCK_TOP:.4f}  bottom {STOCK_BOTTOM:.4f}  '
          f'left rx {LEFT_RX:.4f}  right rx {RIGHT_RX:.4f}')
    print(f'hero baseline {HERO_BASELINE:.4f}, cap top {HERO_CAP_TOP:.4f}, '
          f'clearance {HERO_CAP_TOP - STOCK_TOP:.4f}')
    print(f'band y {BAND_Y:.4f}, limits {LIMIT_BASELINE:.4f}, '
          f'glow cy {GLOW_CY:.4f} ry {GLOW_RY:.4f}')
    print(f'shelf {SHELF:.0f} (cap top {SHELF - CAP * FIGURE:.2f} vs stock top '
          f'{STOCK_TOP:.2f}), caption {SHELF_CAPTION:.2f}')
    right_figure_left = RIGHT_EDGE - 4 * MONO * FIGURE
    print(f'right wing figure left edge {right_figure_left:.2f} against aperture '
          f'{W - aperture_reach(WING_FIGURE, right=True):.2f}')
    print(f'right rail inner tip {RIGHT_EDGE - RAIL_LEN:.2f} against aperture '
          f'{W - aperture_reach(RAIL_B, right=True):.2f}')
    print(f'shelf cells left {cell_right(2) - CELL_W:.2f} against hero right edge '
          f'{AXIS + 3 * MONO * HERO / 2:.2f}')
    for kw in (-100.0, -20.0, 0.0, 34.0, 60.0, 150.0, 300.0):
        print(f'  {kw:7.1f} kW -> x {band_x(kw):.2f}')
