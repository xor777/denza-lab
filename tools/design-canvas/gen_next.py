#!/usr/bin/env python3
"""
The cluster, second pass: the middle is ours after all.

The first pass was built on a wrong premise. The vehicle's own car model sits
dead centre, so the layout moved everything out to the halves to keep clear of
it - but the car is only the parked state, the driving state is the ADAS road
rendering in the same place, and an opaque black ground covers both. Nothing of
the vehicle's centre composition survives under us. The middle is free, and a
layout that gives it away is giving away the best real estate on the panel.

So: the power gauge takes the middle as a half circle, its quiet numbers inside
it, and the two histories flank it. Every figure sits above its own history
rather than beside it, because a figure beside a chart changes width as it
counts and drags the chart left and right under the eye.

What is not drawn matters as much. The vehicle computes an average consumption
and shows a tank, so this shows neither. Titles over a number whose unit already
names it are labels a driver has no time for, and they are gone with the axis
captions. What is left has to be legible in one glance, which is the only test
this panel has to pass.
"""
import os

import gen_cluster as g

HERE = os.path.dirname(os.path.abspath(__file__))

W, H = 1507.6, 424.0          # the FULL placement's virtual space
PX = 720.0 / H                # virtual -> real pixels on the verified panel

BLACK = '#000000'
INK = g.INK
MUTED = g.MUTED
MUTED_DEEP = g.MUTED_DEEP
RETURN = g.RETURN
RETURN_INK = g.RETURN_INK
TRACK = g.TRACK
TRACK_MARK = g.TRACK_MARK
DANGER = '#FF4046'
WARNING = g.WARNING
GHOST = '#15181C'
GHOST_INK = '#2C3138'

f = g.f

# ---------------------------------------------------------------- the panel

# What the vehicle still puts on top of us, if anything. The centre is settled:
# the car model and the ADAS road rendering are both under our black ground and
# neither survives it. The two strips are the open question - the photograph that
# showed them was taken while our background was still transparent.
STOCK_TOP_H = 85.0
STOCK_TOP_X0, STOCK_TOP_X1 = 495.0, 1214.0
STOCK_BOTTOM_Y = 365.0

CALIBRATION = (
    'One question left: does the black ground cover the vehicle\'s own top row '
    'and bottom strip as it covers ADAS? If it does, this panel is 2560x720 of '
    'ours and both bands below are free. A numbered grid drawn on the black and '
    'one photograph answers it.'
)

# Rows. Both halves use the same three, so the eye finds the same thing at the
# same height on either side of the gauge.
CORNER_Y, CORNER_Y2 = 34.0, 62.0
FIGURE_Y = 150.0
CHART_TOP, CHART_BOTTOM = 178.0, 330.0

LEFT_X0, LEFT_X1 = 26.0, 470.0
RIGHT_X0, RIGHT_X1 = W - 470.0, W - 26.0

GAUGE_CX, GAUGE_CY, GAUGE_R = W / 2, 330.0, 196.0
FULL_DISCHARGE_KW, FULL_REGEN_KW = 300.0, 100.0


def ghost():
    return [
        f'<rect x="{f(STOCK_TOP_X0)}" y="0" width="{f(STOCK_TOP_X1 - STOCK_TOP_X0)}" '
        f'height="{f(STOCK_TOP_H)}" fill="{GHOST}"/>',
        f'<rect x="0" y="{f(STOCK_BOTTOM_Y)}" width="{f(W)}" height="{f(H - STOCK_BOTTOM_Y)}" '
        f'fill="{GHOST}"/>',
        f'<text class="ghost" x="{f(STOCK_TOP_X0 + 12)}" y="{f(STOCK_TOP_H - 9)}">'
        f'ШТАТНОЕ · ПЕРЕКРЫВАЕМ ЛИ</text>',
        f'<text class="ghost" x="{f(LEFT_X0)}" y="{f(H - 13)}">ШТАТНОЕ · ПЕРЕКРЫВАЕМ ЛИ</text>',
    ]


# ---------------------------------------------------------------- the gauge

def gauge_degrees(kw):
    """Zero at the top, discharge clockwise, recovery anticlockwise.

    Square root, the way the energy scale has always worked here: this car pulls
    three hundred kilowatts and spends most of its life under thirty, so a linear
    half circle would leave every ordinary reading in the first few degrees.
    """
    if kw >= 0:
        return 90.0 - 90.0 * min(1.0, (kw / FULL_DISCHARGE_KW)) ** 0.5
    return 90.0 + 90.0 * min(1.0, (-kw / FULL_REGEN_KW)) ** 0.5


def power_gauge(kw, readings):
    """The half circle, and the three quiet numbers standing inside it.

    A half circle rather than the old 220-degree arc because a flat base is what
    makes it read as one shape at a glance instead of a ring with a bite out of
    it, and because its ends land exactly on the horizon - full recovery to the
    left, full power to the right, no arithmetic to do.
    """
    cx, cy, r = GAUGE_CX, GAUGE_CY, GAUGE_R
    out = [
        '<defs>',
        f'<linearGradient id="gauge" x1="0" y1="0" x2="1" y2="0">'
        f'<stop offset="0" stop-color="{RETURN}"/>'
        f'<stop offset="0.5" stop-color="{INK}" stop-opacity="0.55"/>'
        f'<stop offset="1" stop-color="{INK}"/></linearGradient>',
        f'<radialGradient id="pool">'
        f'<stop offset="0" stop-color="{RETURN_INK if kw < 0 else INK}" stop-opacity="0.20"/>'
        f'<stop offset="0.55" stop-color="{RETURN_INK if kw < 0 else INK}" stop-opacity="0.08"/>'
        f'<stop offset="1" stop-color="{RETURN_INK if kw < 0 else INK}" stop-opacity="0"/>'
        f'</radialGradient>',
        '</defs>',
        f'<ellipse cx="{f(cx)}" cy="{f(cy - r * 0.28)}" rx="{f(r * 2.3)}" ry="{f(r * 1.7)}" '
        f'fill="url(#pool)"/>',
        f'<path d="{g.arc_path(cx, cy, r, 180.0, 0.0)}" fill="none" stroke="{TRACK}" '
        f'stroke-width="10" stroke-linecap="round"/>',
    ]

    # One mark either side of the top, and nothing labelled: the ends are the
    # span and the top is zero, which is every number this shape owes.
    for mark in (60.0, -20.0):
        deg = gauge_degrees(mark)
        x0, y0 = g.polar(cx, cy, r + 4, deg)
        x1, y1 = g.polar(cx, cy, r + 13, deg)
        colour = RETURN if mark < 0 else TRACK_MARK
        out.append(f'<path d="M{f(x0)} {f(y0)} L{f(x1)} {f(y1)}" stroke="{colour}" '
                   f'stroke-width="1.8" stroke-linecap="round"/>')

    deg = gauge_degrees(kw)
    if abs(deg - 90.0) > 0.5:
        out.append(f'<path d="{g.arc_path(cx, cy, r, 90.0, deg)}" fill="none" '
                   f'stroke="url(#gauge)" stroke-width="10" stroke-linecap="round"/>')
    hx, hy = g.polar(cx, cy, r, deg)
    out.append(f'<circle cx="{f(hx)}" cy="{f(hy)}" r="7" fill="{RETURN if kw < 0 else INK}"/>')

    out.append(g.pair(cx, cy - 46, [('fg', readings['kw'], 0), ('un', 'кВт', 10)],
                      anchor='middle'))
    out.append(g.pair(cx, cy - 12, [('rd2', readings['volts'], 0), ('bd2', 'В', 5),
                                    ('rd2', readings['spread'], 18), ('bd2', 'мВ', 5),
                                    ('rd2', readings['cells'], 18), ('bd2', 'ячеек', 5)],
                      anchor='middle'))
    return out


# ---------------------------------------------------------------- the histories

def line(ident, x0, x1, y0, y1, values, ceiling, colour, root=False, signed=False,
         floor=None, fill=0.22):
    """One history as a line with a fade under it, oldest at the left.

    A line rather than the bars the first pass drew: bars count things and this is
    a continuous quantity, and at this width thirty of them read as a fence rather
    than as a shape. A `None` breaks the run instead of being drawn through, so a
    stretch nobody watched looks like one.

    A signed run gets its own scale each way. The first draft gave both directions
    the same span from a zero line a third of the way up, so every recovery dip
    ran straight out of the bottom of the box - the sort of thing that is obvious
    the moment it is rendered and invisible in the arithmetic.
    """
    n = len(values)
    if n < 2:
        return []
    step = (x1 - x0) / (n - 1)
    if signed:
        base = y1 - (y1 - y0) * 0.34
        up, down = base - y0, y1 - base
    else:
        base = y1
        up, down = y1 - y0, 0.0
    out = ['<defs>',
           f'<linearGradient id="{ident}" x1="0" y1="0" x2="0" y2="1">'
           f'<stop offset="0" stop-color="{colour}" stop-opacity="{fill}"/>'
           f'<stop offset="1" stop-color="{colour}" stop-opacity="0"/></linearGradient>',
           '</defs>']
    runs, run = [], []
    for i, value in enumerate(values):
        if value is None:
            if len(run) > 1:
                runs.append(run)
            run = []
            continue
        if value >= 0:
            share = min(1.0, value / ceiling)
            if root:
                share = share ** 0.5
            y = base - up * share
        else:
            share = min(1.0, -value / (floor or ceiling))
            y = base + down * share
        run.append((x0 + i * step, y))
    if len(run) > 1:
        runs.append(run)
    last = None
    for run in runs:
        d = 'M ' + ' L '.join(f'{f(x)} {f(y)}' for x, y in run)
        out.append(f'<path d="{d} L {f(run[-1][0])} {f(base)} L {f(run[0][0])} {f(base)} Z" '
                   f'fill="url(#{ident})"/>')
        out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2" '
                   f'stroke-linejoin="round" stroke-linecap="round"/>')
        last = run[-1]
    if signed:
        out.insert(3, f'<line x1="{f(x0)}" y1="{f(base)}" x2="{f(x1)}" y2="{f(base)}" '
                      f'stroke="rgba(218,225,235,0.14)" stroke-width="1.1"/>')
    if last:
        out.append(f'<circle cx="{f(last[0])}" cy="{f(last[1])}" r="4" fill="{colour}"/>')
    return out


def energy_half(v):
    out = [g.pair(LEFT_X0, CORNER_Y, [('rd2', v['pack_c'], 0), ('bd2', '° батарея', 5),
                                      ('rd2', v['inverter_c'], 20), ('bd2', '° инвертор', 5)]),
           g.pair(LEFT_X0, CORNER_Y2, [('rd2', v['motors_c'], 0), ('bd2', '° моторы', 5)])]
    out.append(g.pair(LEFT_X0, FIGURE_Y, [('fg', v['use'], 0), ('un', 'кВт·ч/100 км', 10)]))
    out += line('uses', LEFT_X0, LEFT_X1, CHART_TOP, CHART_BOTTOM, v['use_trace'], 40.0,
                INK, signed=True, floor=30.0)
    return out


def engine_half(v):
    out = fluids(v['fluids'])
    out.append(g.pair(RIGHT_X1, FIGURE_Y, [('fg', v['rpm'], 0), ('un', 'об/мин', 10)],
                      anchor='end'))
    out += line('rpms', RIGHT_X0, RIGHT_X1, CHART_TOP, CHART_BOTTOM, v['rpm_trace'], 6000.0,
                'rgba(218,225,235,0.62)')
    out += line('gens', RIGHT_X0, RIGHT_X1, CHART_TOP, CHART_BOTTOM, v['gen_trace'], 60.0,
                RETURN, fill=0.30)
    return out


# ---------------------------------------------------------------- the exception

FLUID_ROW = 26.0


def fluids(state, x_right=None, y0=CORNER_Y):
    """Silent while nothing is wrong, and the fault itself when something is.

    Eight anonymous dots in a grid was an inventory, and a driver's display is
    not for inventories: a red dot in the fifth position of a grid nobody
    memorised says only "something", two say "two somethings", and the sentence
    that named them ran out of the corner. Nothing at all is the right drawing
    for a healthy car - there is no reading to take - so the corner stays empty
    and names the faults, worst first, the moment there are any.
    """
    x = RIGHT_X1 if x_right is None else x_right
    kind, names, answered, total = state
    if kind == 'ok':
        return []
    if kind in ('partial', 'silent'):
        text = f'жидкости · {answered} из {total}' if kind == 'partial' else 'жидкости молчат'
        return [f'<text class="bd2" x="{f(x)}" y="{f(y0)}" text-anchor="end" '
                f'style="fill:{WARNING}">{text}</text>']
    out = []
    for i, name in enumerate(names[:3]):
        row = y0 + i * FLUID_ROW
        out.append(f'<circle cx="{f(x - 5)}" cy="{f(row - 5)}" r="4.4" fill="{DANGER}"/>')
        out.append(f'<text class="rd2" x="{f(x - 18)}" y="{f(row)}" text-anchor="end" '
                   f'style="fill:{DANGER}">{name}</text>')
    if len(names) > 3:
        out.append(f'<text class="bd2" x="{f(x)}" y="{f(y0 + 3 * FLUID_ROW)}" '
                   f'text-anchor="end" style="fill:{DANGER}">и ещё {len(names) - 3}</text>')
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
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500&amp;family=Roboto+Mono:wght@200;300;400&amp;display=swap" rel="stylesheet">
  <style>
    body { margin:0; background:%(bg)s; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    a { color:#FEEFAB; } a:hover { color:#FFF7D2; }
    .fg { font-family:'Roboto Mono',monospace; font-weight:200; font-size:52px; fill:%(ink)s; }
    .rd2 { font-family:'Roboto Mono',monospace; font-weight:300; font-size:18px; fill:%(ink)s; }
    .un { font-size:18px; fill:%(muted)s; }
    .bd2 { font-size:13px; fill:%(muted_deep)s; }
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

def engine_history(sleeping=34, slots=120):
    rpm, gen = [], []
    for i in range(slots):
        if i < sleeping:
            rpm.append(None)
            gen.append(None)
            continue
        t = (i - sleeping) / float(slots - sleeping)
        rpm.append(round(900 + 1500 * min(1.0, t * 4) + 120 * ((i * 7) % 5) / 4.0, 1))
        gen.append(round(max(0.0, 11.0 * min(1.0, (t - 0.10) * 6) + 1.4 * ((i * 5) % 4) / 3.0), 2))
    return rpm, gen


def values():
    return dict(
        kw='34', volts='550', spread='3', cells='96',
        use='16,8', use_trace=g.fold(g.DRIVING_BARS, 12.0),
        rpm='2418', rpm_trace=engine_history()[0], gen_trace=engine_history()[1],
        pack_c='18', inverter_c='17', motors_c='20 · 25 · 25',
        fluids=('ok', [], 8, 8),
    )


FLUID_NAMES = ['давление масла', 'уровень ОЖ', 'масло КПП', 'уровень масла']


def build():
    v = values()
    body = ghost() + power_gauge(34.0, v) + energy_half(v) + engine_half(v)
    open(os.path.join(HERE, 'ClusterNext.dc.html'), 'w').write(page(body))

    cell_w, cell_h = 380.0, 190.0
    states = [('ok', [], 8, 8), ('partial', [], 5, 8),
              ('alert', FLUID_NAMES[:1], 8, 8), ('alert', FLUID_NAMES, 8, 8)]
    labels = ['всё в норме · угол пуст', 'часть не ответила', 'одна неисправность', 'четыре']
    body = []
    for i, state in enumerate(states):
        ox, oy = 24 + (i % 2) * (cell_w + 24), 24 + (i // 2) * (cell_h + 24)
        body.append(f'<g transform="translate({f(ox)},{f(oy)})">')
        body.append(f'<rect x="0" y="0" width="{f(cell_w)}" height="{f(cell_h)}" rx="14" '
                    f'fill="#0A0B0D" stroke="rgba(218,225,235,0.10)"/>')
        body += fluids(state, x_right=cell_w - 22, y0=44.0)
        body.append(f'<text class="ghost" x="22" y="{f(cell_h - 16)}">{labels[i]}</text>')
        body.append('</g>')
    open(os.path.join(HERE, 'ClusterFluids.dc.html'), 'w').write(
        page(body, width=24 + 2 * (cell_w + 24), height=24 + 2 * (cell_h + 24)))
    print('ClusterNext, ClusterFluids')


if __name__ == '__main__':
    build()
