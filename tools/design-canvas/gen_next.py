#!/usr/bin/env python3
"""
The cluster, third pass: one horizon, and a rule for the colour.

The second pass put the right facts at defensible coordinates and never made a
picture. Rendered at panel size the failure is plain: five objects floating on
black at positions that do not relate to each other, the negative space pooled
in one huge empty bowl under the arc and another in the top right, the two
"symmetric" histories sitting at different heights on different baselines, and
the largest object on the panel - a 666-pixel arc - saying one number that is
also printed inside it.

So this pass starts from a skeleton instead of from a list of facts:

  one hairline runs the whole width at y=318. Everything rests on it. The two
  histories sit on it, the power gauge springs from it, every figure stands
  above it, and nothing is placed anywhere the line does not explain.

And one rule for the colour, which is the same rule everywhere on the panel:
  ink is energy leaving the pack, blue is energy coming back. Above the line is
  spending, below the line is returning. Regeneration dips below it in blue and
  so does the engine's generation, because they are the same event.

Two boards, differing in exactly one decision - the middle. `ClusterNext` keeps
the half circle, sized so its interior is filled by the numeral rather than
hollow around it. `ClusterBar` spends that height on the histories instead and
lays the power flat on the horizon itself, growing right to spend and left to
recover. Everything else is identical, so the choice is the only thing being
asked.
Type is the canvas ramp, not a new one. The first draft of these boards ran at
104 / 58 / 19 / 18 / 14, which broke the rule the ramp exists for twice over: 19
against 18 is a difference you can measure and cannot see, and 58 against the
ramp's own 52 is the same drift one board away. The headline numeral is the only
new rung, and it is exactly twice the old top - so the proposal extends the
ladder rather than starting a second one beside it.
"""
import math
import os

import gen_cluster as g

HERE = os.path.dirname(os.path.abspath(__file__))

W, H = 1507.6, 424.0          # the FULL placement's virtual space

BLACK = '#000000'
INK = g.INK
MUTED = g.MUTED
MUTED_DEEP = g.MUTED_DEEP
RETURN = g.RETURN
RETURN_INK = g.RETURN_INK
TRACK = g.TRACK
DANGER = '#FF4046'
WARNING = g.WARNING
GHOST_INK = '#2C3138'
QUIET = 'rgba(218,225,235,0.26)'

f = g.f

# ---------------------------------------------------------------- the skeleton

M = 30.0                      # the margin every outer edge keeps
CORNER_Y = 42.0               # the small row, hard against the top
FIGURE_Y = 132.0              # every large figure stands on this baseline
BAND_TOP = 174.0              # nothing drawn by a history reaches above this
HORIZON = 344.0               # the line the whole panel rests on
DIP = 50.0                    # how far below it anything returning may reach

GAUGE_CX = W / 2
GAUGE_R = HORIZON - BAND_TOP  # so the arc's crown lands on the histories' ceiling
CENTRE_HALF = GAUGE_R         # the middle column both boards reserve
GAP = 44.0

LEFT_X0, LEFT_X1 = M, GAUGE_CX - CENTRE_HALF - GAP
RIGHT_X0, RIGHT_X1 = GAUGE_CX + CENTRE_HALF + GAP, W - M

FULL_DISCHARGE_KW, FULL_REGEN_KW = 300.0, 100.0
USE_CEILING, USE_FLOOR = 16.0, 10.0
RPM_CEILING, GEN_FLOOR = 1800.0, 8.0


def defs():
    """The two blurs the whole panel borrows.

    The owner kept one accident from the first live run - a faint halo the dial
    threw when the background was still transparent - and it is the only thing
    on this panel that gives the black any material. So it is deliberate now: a
    wide bloom for the gauge, a narrow one under every drawn line. On the car
    these are a BlurMaskFilter on the same Paint, which is what makes them worth
    designing with rather than admiring in a browser.
    """
    return [
        '<defs>',
        '<filter id="soft" x="-40%" y="-40%" width="180%" height="180%">'
        '<feGaussianBlur stdDeviation="4.5"/></filter>',
        '<filter id="bloom" x="-45%" y="-45%" width="190%" height="190%">'
        '<feGaussianBlur stdDeviation="11"/></filter>',
        '</defs>',
    ]


def horizon():
    """The one line. Drawn first, under everything, and never interrupted."""
    return [f'<line x1="{f(M)}" y1="{f(HORIZON)}" x2="{f(W - M)}" y2="{f(HORIZON)}" '
            f'stroke="rgba(218,225,235,0.10)" stroke-width="1.1"/>']


def pool(cx, cy, rx, ry, kw, strength=0.20):
    """The soft ground the owner liked, kept because black alone has no material."""
    tint = RETURN_INK if kw < 0 else INK
    ident = f'pool{abs(int(cx))}'
    return [
        '<defs>',
        f'<radialGradient id="{ident}">'
        f'<stop offset="0" stop-color="{tint}" stop-opacity="{strength}"/>'
        f'<stop offset="0.5" stop-color="{tint}" stop-opacity="{strength * 0.36:.3f}"/>'
        f'<stop offset="1" stop-color="{tint}" stop-opacity="0"/></radialGradient>',
        '</defs>',
        f'<ellipse cx="{f(cx)}" cy="{f(cy)}" rx="{f(rx)}" ry="{f(ry)}" fill="url(#{ident})"/>',
    ]


# ---------------------------------------------------------------- the middle

def gauge_degrees(kw):
    """Zero at the top, spending clockwise, recovering anticlockwise.

    Square root, as every energy scale here works: the car pulls three hundred
    kilowatts and spends most of its life under thirty, so a linear half circle
    would leave every ordinary reading inside the first few degrees.
    """
    if kw >= 0:
        return 90.0 - 90.0 * min(1.0, kw / FULL_DISCHARGE_KW) ** 0.5
    return 90.0 + 90.0 * min(1.0, -kw / FULL_REGEN_KW) ** 0.5


def power_arc(kw, reading):
    """The half circle, sized so the numeral fills it.

    The previous arc was a third larger and held a small number rattling around
    in the middle of it - the biggest object on the panel enclosing the biggest
    void. Radius now equals the height of the histories, which lands its crown
    exactly on their ceiling and makes the interior a place for the figure to
    live rather than a hole. The value arc is a solid colour, not a gradient:
    the colour is carrying the rule, and a gradient across it says nothing and
    muddies the one thing the shape is for.
    """
    cx, cy, r = GAUGE_CX, HORIZON, GAUGE_R
    colour = RETURN if kw < 0 else INK
    out = pool(cx, cy - r * 0.30, r * 2.15, r * 1.55, kw)
    out += [
        f'<path d="{g.arc_path(cx, cy, r, 180.0, 0.0)}" fill="none" stroke="{TRACK}" '
        f'stroke-width="5" stroke-linecap="round"/>',
    ]
    deg = gauge_degrees(kw)
    if abs(deg - 90.0) > 0.4:
        value = g.arc_path(cx, cy, r, 90.0, deg)
        out.append(f'<path d="{value}" fill="none" stroke="{colour}" stroke-width="15" '
                   f'stroke-linecap="round" filter="url(#bloom)" opacity="0.85"/>')
        out.append(f'<path d="{value}" fill="none" stroke="{colour}" stroke-width="13" '
                   f'stroke-linecap="round"/>')
    out += hero(cx, reading)
    return out


def hero(cx, reading):
    """The one numeral the panel is built around, lit like the arc around it."""
    run = g.pair(cx, HORIZON - 34, [('hero', reading, 0), ('un', 'кВт', 12)], anchor='middle')
    glow = run.replace('<text ', '<text filter="url(#soft)" opacity="0.55" ', 1)
    return [glow, run]


TICKS = 37


def power_dial(kw, reading):
    """The same half circle drawn as marks instead of as a band.

    A ring of fine marks lit up to the value reads as an instrument rather than
    as a progress bar, and it gives the shape a texture that a solid band on
    black cannot have. Two lonely unlabelled ticks looked like dirt in the last
    pass; forty-six of them, evenly spaced, read as craft - the difference is
    whether the eye can see the system they belong to.
    """
    cx, cy, r = GAUGE_CX, HORIZON, GAUGE_R
    colour = RETURN if kw < 0 else INK
    value_deg = gauge_degrees(kw)
    out = pool(cx, cy - r * 0.30, r * 2.15, r * 1.55, kw)
    lit = []
    for i in range(TICKS):
        deg = 180.0 - 180.0 * i / (TICKS - 1)
        on = (90.0 >= deg >= value_deg) if kw >= 0 else (90.0 <= deg <= value_deg)
        long = abs(deg - 90.0) < 0.5
        x0, y0 = g.polar(cx, cy, r - (20 if on else (15 if long else 10)), deg)
        x1, y1 = g.polar(cx, cy, r, deg)
        mark = (f'<path d="M{f(x0)} {f(y0)} L{f(x1)} {f(y1)}" stroke="{colour if on else TRACK}" '
                f'stroke-width="{3.6 if on else 1.9}" stroke-linecap="round"/>')
        (lit if on else out).append(mark)
    if lit:
        out.append(f'<g filter="url(#bloom)" opacity="0.8">{"".join(lit)}</g>')
        out += lit
    out += hero(cx, reading)
    return out


def power_bar(kw, reading):
    """The same flow laid flat on the horizon, for a panel shaped like this one.

    Three and a half to one is a letterbox, and a letterbox wants horizontal
    instruments. Spending grows right of the centre in ink, recovering grows
    left of it in blue - the same rule the histories obey a metre either side.
    """
    cx, y = GAUGE_CX, HORIZON
    colour = RETURN if kw < 0 else INK
    half, thick = CENTRE_HALF, 13.0
    share = min(1.0, abs(kw) / (FULL_DISCHARGE_KW if kw >= 0 else FULL_REGEN_KW)) ** 0.5
    end = cx + half * share * (1 if kw >= 0 else -1)
    out = pool(cx, y - 26, half * 2.1, 96.0, kw, strength=0.16)
    out += [
        f'<rect x="{f(cx - half)}" y="{f(y - 2.0)}" width="{f(half * 2)}" height="4" rx="2" '
        f'fill="{TRACK}"/>',
        f'<rect x="{f(min(cx, end))}" y="{f(y - thick / 2)}" width="{f(abs(end - cx))}" '
        f'height="{f(thick)}" rx="{f(thick / 2)}" fill="{colour}"/>',
        f'<circle cx="{f(end)}" cy="{f(y)}" r="9" fill="{colour}"/>',
    ]
    out.append(g.pair(cx, FIGURE_Y, [('fig', reading, 0), ('un', 'кВт', 11)], anchor='middle'))
    return out


# ---------------------------------------------------------------- the histories

def split_sign(points):
    """Cut a run wherever its character changes, so each piece has one colour.

    Three characters, not two. Above the line is spending and below it is
    returning - that rule only means anything if the drawing obeys it, and the
    pass before last drew a recovery dip in the same white as the climb above
    it. The third is rest: an exact nought is a reading, not a gap, and drawing
    it brightly along the horizon put a second, brighter horizon across the
    right of the panel. It is drawn faintly instead, which is what nothing
    happening should look like.

    A sign change is interpolated so the two colours meet exactly on the line;
    a change in or out of rest already sits on the line and only needs the two
    pieces to share their vertex.
    """
    def character(value):
        return 0 if value == 0 else (1 if value > 0 else -1)

    segments, run, kind = [], [], None
    for i, (x, value) in enumerate(points):
        this = character(value)
        if kind is None:
            kind, run = this, [(x, value)]
        elif this == kind:
            run.append((x, value))
        elif this == 0:
            run.append((x, value))
            segments.append((kind, run))
            kind, run = 0, [(x, value)]
        elif kind == 0:
            segments.append((0, run))
            kind, run = this, [points[i - 1], (x, value)]
        else:
            px, pv = points[i - 1]
            cross = px + (x - px) * (pv / (pv - value))
            run.append((cross, 0.0))
            segments.append((kind, run))
            kind, run = this, [(cross, 0.0), (x, value)]
    if len(run) > 1:
        segments.append((kind, run))
    return segments


def history(ident, x0, x1, values, ceiling, floor=None, root=False, fill=0.13,
            up_colour=INK, down_colour=RETURN, dot=True):
    """One run of readings resting on the horizon, oldest at the left.

    Above the line is drawn against `ceiling` and below it against `floor`,
    because the panel has more room above the line than below and pretending
    otherwise ran every recovery dip out of the bottom of the box two passes
    ago. The fade under the line is anchored to the horizon and strongest there,
    which is the only way an area a third of the way up the box is visible at
    all - a fade anchored to the ceiling spends its opacity on empty air.

    A `None` breaks the run instead of being drawn through, so a stretch nobody
    watched looks like one.
    """
    if len(values) < 2:
        return []
    step = (x1 - x0) / (len(values) - 1)
    up, down = HORIZON - BAND_TOP, DIP

    # The span is the run's own, never smaller than the one asked for. A fixed
    # ceiling picked from the physics - six thousand revolutions, forty kilowatt
    # hours - is honest and draws every ordinary run as a low ripple along the
    # bottom of a tall empty box, which is what the last pass looked like. The
    # floor keeps a quiet run quiet instead of amplifying its noise to full
    # height, and the headroom keeps a peak off the ceiling.
    seen = [abs(x) for x in values if x is not None]
    peak_up = max([x for x in values if x is not None and x > 0] or [0.0])
    peak_down = max([-x for x in values if x is not None and x < 0] or [0.0])
    ceiling = max(ceiling, peak_up * 1.12)
    floor = max(floor or ceiling, peak_down * 1.18)
    del seen

    def height(value):
        if value >= 0:
            share = min(1.0, value / ceiling)
            return HORIZON - up * (share ** 0.5 if root else share)
        share = min(1.0, -value / (floor or ceiling))
        return HORIZON + down * (share ** 0.5 if root else share)

    out = ['<defs>']
    for suffix, colour, y_far in (('u', up_colour, BAND_TOP), ('d', down_colour, HORIZON + DIP)):
        out.append(f'<linearGradient id="{ident}{suffix}" gradientUnits="userSpaceOnUse" '
                   f'x1="0" y1="{f(HORIZON)}" x2="0" y2="{f(y_far)}">'
                   f'<stop offset="0" stop-color="{colour}" stop-opacity="{fill}"/>'
                   f'<stop offset="0.45" stop-color="{colour}" stop-opacity="{fill * 0.34:.3f}"/>'
                   f'<stop offset="1" stop-color="{colour}" stop-opacity="0"/>'
                   f'</linearGradient>')
    out.append('</defs>')

    runs, run = [], []
    for i, value in enumerate(values):
        if value is None:
            if len(run) > 1:
                runs.append(run)
            run = []
            continue
        run.append((x0 + i * step, value))
    if len(run) > 1:
        runs.append(run)

    last = None
    for run in runs:
        for sign, part in split_sign(run):
            colour = QUIET if sign == 0 else (up_colour if sign > 0 else down_colour)
            shade = f'{ident}d' if sign < 0 else f'{ident}u'
            d = 'M ' + ' L '.join(f'{f(x)} {f(height(v))}' for x, v in part)
            out.append(f'<path d="{d} L {f(part[-1][0])} {f(HORIZON)} '
                       f'L {f(part[0][0])} {f(HORIZON)} Z" fill="url(#{shade})"/>')
            out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2.2" '
                       f'stroke-linejoin="round" stroke-linecap="round" '
                       f'filter="url(#soft)"/>')
            out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2.2" '
                       f'stroke-linejoin="round" stroke-linecap="round"/>')
        last = run[-1]
    if last and dot:
        colour = (QUIET if last[1] == 0 else
                  (up_colour if last[1] > 0 else down_colour))
        out.append(f'<circle cx="{f(last[0])}" cy="{f(height(last[1]))}" r="4.5" '
                   f'fill="{colour}"/>')
    return out


def energy_side(v, inward=False):
    """Temperatures, the figure, and three kilometres of what it cost.

    `inward` moves the type from the panel's outer edge to the inner end of its
    own history. Two and a half thousand pixels is wider than a driver's central
    vision, and a numeral parked at the far edge is read by the part of the eye
    that cannot read numerals; a shape parked there is read perfectly well. So
    the inward arrangement puts every digit within one glance of the centre and
    spends the periphery on the curves, which is what the periphery is good at.
    """
    x = LEFT_X1 if inward else LEFT_X0
    anchor = 'end' if inward else 'start'
    out = [g.pair(x, CORNER_Y,
                  [('rd', v['pack_c'] + '°', 0), ('bd', 'батарея', 7),
                   ('rd', v['inverter_c'] + '°', 26), ('bd', 'инвертор', 7),
                   ('rd', v['motor_c'] + '°', 26), ('bd', 'моторы', 7)], anchor=anchor),
           g.pair(x, FIGURE_Y, [('fig', v['use'], 0), ('un', 'кВт·ч/100 км', 11)],
                  anchor=anchor)]
    out += history('use', LEFT_X0, LEFT_X1, v['use_trace'], USE_CEILING, floor=USE_FLOOR)
    return out


def engine_side(v, inward=False):
    x = RIGHT_X0 if inward else RIGHT_X1
    anchor = 'start' if inward else 'end'
    out = list(fluids(v['fluids'], v, x_right=x, anchor=anchor))
    out.append(g.pair(x, FIGURE_Y, [('fig', v['rpm'], 0), ('un', 'об/мин', 11)],
                      anchor=anchor))
    out += history('rpm', RIGHT_X0, RIGHT_X1, v['rpm_trace'], RPM_CEILING, fill=0.22)
    out += history('gen', RIGHT_X0, RIGHT_X1, [None if x is None else -x for x in v['gen_trace']],
                   GEN_FLOOR, floor=GEN_FLOOR, fill=0.34, dot=False)
    return out


# ---------------------------------------------------------------- the exception

FLUID_ROW = 27.0


def fluids(state, v, x_right=None, y0=CORNER_Y, anchor='end'):
    """The pack's own numbers, until a fluid has something more urgent to say.

    The corner used to be empty while the car was healthy, which left the top
    right of the panel a void against a dense top left. It carries the three
    readings nothing else on this car reports - pack voltage, cell spread, cell
    count - and a fault takes the corner from them, because a fault outranks
    them and there is no honest way to show both in one corner.
    """
    x = RIGHT_X1 if x_right is None else x_right
    side = -1 if anchor == 'end' else 1
    kind, names, answered, total = state
    if kind == 'ok':
        return [g.pair(x, y0, [('rd', v['volts'], 0), ('bd', 'В', 5),
                               ('rd', v['spread'], 26), ('bd', 'мВ', 5),
                               ('rd', v['cells'], 26), ('bd', 'ячеек', 5)], anchor=anchor)]
    if kind in ('partial', 'silent'):
        text = f'жидкости · {answered} из {total}' if kind == 'partial' else 'жидкости молчат'
        return [f'<text class="bd" x="{f(x)}" y="{f(y0)}" text-anchor="{anchor}" '
                f'style="fill:{WARNING}">{text}</text>']
    out = []
    for i, name in enumerate(names[:3]):
        row = y0 + i * FLUID_ROW
        out.append(f'<circle cx="{f(x + side * 5)}" cy="{f(row - 5)}" r="4.4" fill="{DANGER}"/>')
        out.append(f'<text class="rd" x="{f(x + side * 18)}" y="{f(row)}" '
                   f'text-anchor="{anchor}" style="fill:{DANGER}">{name}</text>')
    if len(names) > 3:
        out.append(f'<text class="bd" x="{f(x)}" y="{f(y0 + 3 * FLUID_ROW)}" '
                   f'text-anchor="{anchor}" style="fill:{DANGER}">и ещё {len(names) - 3}</text>')
    return out


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
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@200;300;400;500&amp;display=swap" rel="stylesheet">
  <style>
    body { margin:0; background:%(bg)s; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    a { color:#FEEFAB; } a:hover { color:#FFF7D2; }
    text { font-feature-settings:'tnum' 1; font-variant-numeric:tabular-nums; }
    .hero { font-weight:200; font-size:104px; fill:%(ink)s; letter-spacing:-0.02em; }
    .fig { font-weight:200; font-size:52px; fill:%(ink)s; letter-spacing:-0.01em; }
    .rd { font-weight:300; font-size:18px; fill:%(ink)s; }
    .un { font-weight:400; font-size:18px; fill:%(muted)s; }
    .bd { font-weight:400; font-size:13px; fill:%(muted_deep)s; }
    .ghost { font-size:11px; letter-spacing:0.12em; fill:%(ghost_ink)s; }
  </style>
</helmet>
"""


def page(body, width=W, height=H):
    css = HEAD % dict(bg=BLACK, ink=INK, muted=MUTED, muted_deep=MUTED_DEEP, ghost_ink=GHOST_INK)
    w, h = f(width), f(height)
    return (css +
            f'<div style="width:{w}px; height:{h}px; background:{BLACK}; position:relative;">\n'
            f'  <svg width="{w}" height="{h}" viewBox="0 0 {w} {h}">\n    ' +
            '\n    '.join(body) +
            f'\n  </svg>\n</div>\n</x-dc>\n'
            f'<script data-dc-script data-props=\'{{"$preview":{{"width":{w},"height":{h}}}}}\'>\n'
            'class Component extends DCLogic { renderVals() { return {}; } }\n'
            '</script>\n</body>\n</html>\n')


# ---------------------------------------------------------------- the data

def use_history(n=36):
    """Three kilometres of city driving, with the braking actually in it.

    The last pass folded a synthetic run into thirty raw buckets and drew the
    noise: an oscilloscope trace, not a shape. Recovery is a ramp, not a spike,
    so the dips here are shaped like the braking that causes them - which is the
    only way the blue below the line means anything on a mock-up.
    """
    out = []
    for i in range(n):
        t = i / (n - 1.0)
        v = 18.4 + 5.4 * math.sin(t * 4.6 + 0.6) + 2.2 * math.sin(t * 11.0 + 1.9)
        for centre, depth, width in ((0.21, 36.0, 0.045), (0.57, 30.0, 0.055),
                                     (0.87, 22.0, 0.038)):
            v -= depth * math.exp(-((t - centre) ** 2) / (2 * width ** 2))
        out.append(round(v, 2))
    return out


def engine_history(sleeping=30, slots=90):
    """Two minutes of the engine, including the minute it spent switched off.

    Nought is a reading and the last pass drew it as a gap, which left the right
    half of the panel visibly less occupied than the left for no reason a driver
    could see. A stopped engine is a flat run along the horizon - true, and it
    fills its box.
    """
    rpm, gen = [], []
    for i in range(slots):
        if i < sleeping:
            rpm.append(0.0)
            gen.append(0.0)
            continue
        t = (i - sleeping) / float(slots - sleeping)
        ramp = min(1.0, t * 3.2)
        rpm.append(round(940 + 1480 * ramp + 55 * math.sin(i * 0.31), 1))
        gen.append(round(max(0.0, 12.4 * min(1.0, (t - 0.08) * 4.6)
                             + 0.6 * math.sin(i * 0.44)), 2))
    return rpm, gen


def values():
    rpm_trace, gen_trace = engine_history()
    return dict(
        kw='34', volts='550', spread='3', cells='96',
        use='16,8', use_trace=use_history(),
        rpm='2418', rpm_trace=rpm_trace, gen_trace=gen_trace,
        pack_c='18', inverter_c='17', motor_c='25',
        fluids=('ok', [], 8, 8),
    )


def recovering():
    """The same panel while the car is giving energy back, and the engine asleep.

    Worth drawing because it is the state that proves the rule: one glance and
    everything that means "coming back" is blue at once - the lit run on the
    dial, the tail of the consumption history, and the engine's flat nought
    where its generation would be. Nothing has to be read.
    """
    v = values()
    trace = list(v['use_trace'])
    for i in range(len(trace) - 7, len(trace)):
        t = (i - (len(trace) - 8)) / 7.0
        trace[i] = round(-6.0 - 20.0 * t, 2)
    v.update(kw='38', use='9,4', use_trace=trace, rpm='0',
             rpm_trace=[0.0] * 90, gen_trace=[0.0] * 90)
    return v


FLUID_NAMES = ['давление масла', 'уровень ОЖ', 'масло КПП', 'уровень масла']


def build():
    v = values()
    kw = 34.0

    r = recovering()
    boards = [
        ('ClusterNext', power_arc(kw, v['kw']), True, v),
        ('ClusterDial', power_dial(kw, v['kw']), True, v),
        ('ClusterEdge', power_arc(kw, v['kw']), False, v),
        ('ClusterRegen', power_dial(-38.0, r['kw']), True, r),
    ]
    for name, middle, inward, data in boards:
        body = (defs() + horizon() + middle
                + energy_side(data, inward) + engine_side(data, inward))
        open(os.path.join(HERE, name + '.dc.html'), 'w').write(page(body))

    cell_w, cell_h = 380.0, 190.0
    states = [('ok', [], 8, 8), ('partial', [], 5, 8),
              ('alert', FLUID_NAMES[:1], 8, 8), ('alert', FLUID_NAMES, 8, 8)]
    labels = ['всё в норме · угол держит батарею', 'часть не ответила',
              'одна неисправность', 'четыре']
    body = []
    for i, state in enumerate(states):
        ox, oy = 24 + (i % 2) * (cell_w + 24), 24 + (i // 2) * (cell_h + 24)
        body.append(f'<g transform="translate({f(ox)},{f(oy)})">')
        body.append(f'<rect x="0" y="0" width="{f(cell_w)}" height="{f(cell_h)}" rx="14" '
                    f'fill="#0A0B0D" stroke="rgba(218,225,235,0.10)"/>')
        body += fluids(state, v, x_right=cell_w - 22, y0=44.0)
        body.append(f'<text class="ghost" x="22" y="{f(cell_h - 16)}">{labels[i]}</text>')
        body.append('</g>')
    open(os.path.join(HERE, 'ClusterFluids.dc.html'), 'w').write(
        page(body, width=24 + 2 * (cell_w + 24), height=24 + 2 * (cell_h + 24)))
    print('ClusterNext, ClusterDial, ClusterEdge, ClusterFluids')


if __name__ == '__main__':
    build()
