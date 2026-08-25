#!/usr/bin/env python3
"""
The cluster, rethought around the one thing the first live run showed.

The vehicle draws its own car model in the middle of the panel - roughly 160 by
350 real pixels, dead centre - and it draws it *above* our window. Our energy
dial is centred on exactly that spot, so its own reading sits behind the car and
cannot be read. Every layout that puts anything of ours in the middle band loses
it. So the middle stops being ours: the two data columns move out to the halves,
and what is left in the centre is light.

That is the whole idea of ClusterNext. The pool of light the owner asked for
around the dial stops being decoration and becomes the instrument: its colour and
its strength are the energy flowing, so the panel answers "how hard am I pulling,
am I getting anything back" in peripheral vision, around the vehicle's own car.
The exact number is a number, and numbers live in the columns where there is room
to set them.

The ghost layer on these boards is an *estimate*, read off one photograph of the
live panel. It is drawn because a layout argued without it is an argument about
the wrong panel - not because these bounds are measured. `CALIBRATION` below says
what would measure them.
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
DANGER = '#FF4046'
WARNING = g.WARNING
GHOST = '#1C1F24'
GHOST_INK = '#33383F'

f = g.f

# ---------------------------------------------------------------- the obstacle

# Estimated from the 2026-08-25 photograph, in real pixels, then carried into the
# virtual space. Every one of these wants confirming; see CALIBRATION.
STOCK_TOP_H = 85.0                      # the icon row, only across the middle
STOCK_TOP_X0, STOCK_TOP_X1 = 495.0, 1214.0
STOCK_BOTTOM_Y = 365.0                  # 89 % · 488 km · 655 km EV · 1 kW · P · ODO
CAR_X0, CAR_X1 = 707.0, 801.0
CAR_Y0, CAR_Y1 = 115.0, 321.0

CALIBRATION = (
    'Draw a numbered 100 px grid on the black dashboard, photograph the panel '
    'once, and read off where the stock composition covers it. One run, exact '
    'answers, and it replaces every estimate on this board.'
)

# The two halves the car leaves. Nothing of ours may cross the middle.
LEFT_X0, LEFT_X1 = 26.0, CAR_X0 - 18.0
RIGHT_X0, RIGHT_X1 = CAR_X1 + 18.0, W - 26.0


def ghost():
    """What the vehicle draws over us, so a layout can be argued against it."""
    return [
        f'<rect x="{f(STOCK_TOP_X0)}" y="0" width="{f(STOCK_TOP_X1 - STOCK_TOP_X0)}" '
        f'height="{f(STOCK_TOP_H)}" fill="{GHOST}" opacity="0.75"/>',
        f'<rect x="0" y="{f(STOCK_BOTTOM_Y)}" width="{f(W)}" height="{f(H - STOCK_BOTTOM_Y)}" '
        f'fill="{GHOST}" opacity="0.75"/>',
        f'<rect x="{f(CAR_X0)}" y="{f(CAR_Y0)}" width="{f(CAR_X1 - CAR_X0)}" '
        f'height="{f(CAR_Y1 - CAR_Y0)}" rx="18" fill="{GHOST}" opacity="0.9"/>',
        f'<text class="ghost" x="{f((CAR_X0 + CAR_X1) / 2)}" y="{f(CAR_Y0 - 10)}" '
        f'text-anchor="middle">ШТАТНАЯ МАШИНА</text>',
        f'<text class="ghost" x="{f(STOCK_TOP_X0 + 10)}" y="{f(STOCK_TOP_H - 8)}">ШТАТНАЯ СТРОКА</text>',
        f'<text class="ghost" x="{f(LEFT_X0)}" y="{f(H - 12)}">ШТАТНАЯ ПОЛОСА</text>',
    ]


# ---------------------------------------------------------------- the light

def bloom(kw):
    """The pool of light, doing a job.

    Colour says direction and strength says magnitude, which is the pair a driver
    reads without looking: blue and rising means the car is getting energy back,
    warm and rising means it is spending it. It replaces a dial rather than
    decorating one, because a dial in the middle of this panel is unreadable.
    """
    strength = min(1.0, (abs(kw) / 150.0) ** 0.5)
    tint = RETURN_INK if kw < 0 else INK
    peak = 0.12 + 0.34 * strength
    cx, cy = (CAR_X0 + CAR_X1) / 2, (CAR_Y0 + CAR_Y1) / 2 + 20
    rx, ry = 470.0, 330.0
    return [
        '<defs>',
        f'<radialGradient id="bloom">'
        f'<stop offset="0" stop-color="{tint}" stop-opacity="{peak:.3f}"/>'
        f'<stop offset="0.5" stop-color="{tint}" stop-opacity="{peak * 0.45:.3f}"/>'
        f'<stop offset="1" stop-color="{tint}" stop-opacity="0"/>'
        f'</radialGradient>',
        '</defs>',
        f'<ellipse cx="{f(cx)}" cy="{f(cy)}" rx="{f(rx)}" ry="{f(ry)}" fill="url(#bloom)"/>',
    ]


# ---------------------------------------------------------------- charts

def area(ident, x0, x1, y0, y1, values, ceiling, colour, root=False, width=1.9):
    """A trace with the modern thing under it: a fill that fades out downward.

    A line alone on black is thin at this distance. The fade gives it a body
    without giving it an edge, and it is the one gradient on this panel that is
    not the light itself.
    """
    pts, n = [], len(values)
    if n < 2:
        return []
    step = (x1 - x0) / (n - 1)
    out = ['<defs>',
           f'<linearGradient id="{ident}" x1="0" y1="0" x2="0" y2="1">'
           f'<stop offset="0" stop-color="{colour}" stop-opacity="0.30"/>'
           f'<stop offset="1" stop-color="{colour}" stop-opacity="0"/></linearGradient>',
           '</defs>']
    runs, run = [], []
    for i, value in enumerate(values):
        if value is None:
            if len(run) > 1:
                runs.append(run)
            run = []
            continue
        share = min(1.0, max(0.0, value / ceiling))
        if root:
            share = share ** 0.5
        run.append((x0 + i * step, y1 - (y1 - y0) * share))
    if len(run) > 1:
        runs.append(run)
    for run in runs:
        d = 'M ' + ' L '.join(f'{f(x)} {f(y)}' for x, y in run)
        out.append(f'<path d="{d} L {f(run[-1][0])} {f(y1)} L {f(run[0][0])} {f(y1)} Z" '
                   f'fill="url(#{ident})"/>')
        out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{width}" '
                   f'stroke-linejoin="round" stroke-linecap="round"/>')
        pts = run
    if pts:
        out.append(f'<circle cx="{f(pts[-1][0])}" cy="{f(pts[-1][1])}" r="3.6" fill="{colour}"/>')
    return out


def bars(x0, x1, y0, y1, values, average):
    """The consumption run, which used to sit inside the dial and now has a column."""
    out = []
    ceiling = max(max(values), 1.0)
    floor = min(min(values), 0.0)
    span = ceiling - floor
    zero = y1 - (y1 - y0) * (-floor / span)
    slot = (x1 - x0) / len(values)
    width = slot * 0.62
    for i, value in enumerate(values):
        height = (y1 - y0) * (abs(value) / span)
        x = x0 + i * slot + (slot - width) / 2
        top = zero - height if value >= 0 else zero
        colour = INK if value >= 0 else RETURN
        opacity = '1' if value >= 0 else '0.9'
        out.append(f'<rect x="{f(x)}" y="{f(top)}" width="{f(width)}" height="{f(max(height, 1.2))}" '
                   f'rx="1.6" fill="{colour}" opacity="{opacity}"/>')
    out.append(f'<line x1="{f(x0)}" y1="{f(zero)}" x2="{f(x1)}" y2="{f(zero)}" '
               f'stroke="rgba(218,225,235,0.16)" stroke-width="1.1"/>')
    if average is not None:
        ay = y1 - (y1 - y0) * ((average - floor) / span)
        out.append(f'<line x1="{f(x0)}" y1="{f(ay)}" x2="{f(x1)}" y2="{f(ay)}" '
                   f'stroke="{g.DATA_PEAK}" stroke-width="1.1" stroke-dasharray="5 5" '
                   f'opacity="0.55"/>')
    return out


# ---------------------------------------------------------------- the columns

# Two columns and nothing between them. The rows are stated once here because the
# panel's whole calm depends on both columns using the same four.
#
# The corner blocks are gone as separate objects: the stock icon row only spans
# the middle of the panel, so each column owns its own outer corner all the way to
# the top edge and can carry its quiet reading there. That is one block fewer to
# place, and it puts everything of a column on one vertical line.
CORNER_TITLE_Y, CORNER_ROW_Y = 30.0, 58.0
CORNER_ROW2_Y = 84.0
TITLE_Y, FIGURE_Y = 132.0, 196.0
CHART_TOP, CHART_BOTTOM = 216.0, 318.0
CAPTION_Y = 344.0


def figure_pair(x, y, number, unit, anchor='start', number_class='fg', colour=None):
    """A number and its unit as one run.

    Built through `gen_cluster.pair` rather than by hand: a bare <text> holding
    classed tspans is what lets the audit measure every part of the run. A <text>
    that carries the class itself and a loose text node inside it hides the
    number's own size - which is exactly how the first draft of this board
    reported a type ramp with its two largest sizes missing.
    """
    return g.pair(x, y, [(number_class, number, 0, colour), ('un', unit, 9)], anchor=anchor)


def electric_column(v):
    x0, x1 = LEFT_X0, LEFT_X1
    out = [f'<text class="ttl2" x="{f(x0)}" y="{f(CORNER_TITLE_Y)}">ТЕМПЕРАТУРЫ</text>']
    out.append(g.pair(x0, CORNER_ROW_Y, [('rd2', v['pack_c'], 0), ('bd2', '° батарея', 5),
                                         ('rd2', v['inverter_c'], 20), ('bd2', '° инвертор', 5)]))
    out.append(g.pair(x0, CORNER_ROW2_Y, [('rd2', v['motors_c'], 0), ('bd2', '° моторы', 5)]))

    out.append(g.pair(x0, TITLE_Y, [('ttl', 'ЭЛЕКТРИКА', 0),
                                    ('rd2', v['volts'], 24), ('bd2', 'В', 5),
                                    ('rd2', v['spread'], 16), ('bd2', 'мВ разброс', 5)]))
    out.append(figure_pair(x0, FIGURE_Y, v['flow'], 'кВт'))
    out += bars(x0, x1, CHART_TOP, CHART_BOTTOM, v['bars'], v['average_value'])
    out.append(f'<text class="bd" x="{f(x0)}" y="{f(CAPTION_Y)}">{v["chart_caption"]}</text>')
    return out


def engine_column(v):
    x0, x1 = RIGHT_X0, RIGHT_X1
    out = fluids(v['fluids'], x_right=x1, y0=CORNER_TITLE_Y)
    out.append(g.pair(x1, TITLE_Y, [('bd2', 'бак', 0), ('rd2', v['fuel'], 5), ('bd2', '%', 5),
                                    ('ttl', 'ДВИГАТЕЛЬ', 24)], anchor='end'))
    out.append(figure_pair(x1, FIGURE_Y, v['rpm'], 'об/мин', anchor='end'))
    out += area('rpmfill', x0, x1, CHART_TOP, CHART_BOTTOM, v['rpm_trace'], 6000.0,
                'rgba(218,225,235,0.62)')
    out += area('genfill', x0, x1, CHART_TOP, CHART_BOTTOM, v['gen_trace'], 100.0, RETURN, root=True)
    out.append(f'<text class="bd" x="{f(x1)}" y="{f(CAPTION_Y)}" text-anchor="end">'
               f'{v["engine_caption"]}</text>')
    out.append(f'<text class="bd" x="{f(x0)}" y="{f(CAPTION_Y)}">2 минуты</text>')
    return out


# ---------------------------------------------------------------- the corners

FLUID_ROW = 26.0


def fluids(state, x_right=None, y0=30.0):
    """The fluids corner: silent while nothing is wrong, and the fault when it is.

    Eight anonymous dots in a grid was an inventory, and an inventory is what a
    driver's display is not for. A red dot in the fifth position of a grid nobody
    memorised says only "something", and two red dots say only "two somethings",
    while the sentence naming them ran out of the corner. This says the state in
    one line while it is dull, and names the faults - the worst first, up to three
    - the moment it is not.
    """
    x = W - 26.0 if x_right is None else x_right
    out = [f'<text class="ttl2" x="{f(x)}" y="{f(y0)}" text-anchor="end">ЖИДКОСТИ</text>']
    kind, names, answered, total = state
    y = y0 + 30
    if kind == 'ok':
        out.append(f'<text class="bd2" x="{f(x)}" y="{f(y)}" text-anchor="end">'
                   f'все {total} в норме</text>')
    elif kind == 'partial':
        out.append(f'<text class="bd2" x="{f(x)}" y="{f(y)}" text-anchor="end" style="fill:{WARNING}">'
                   f'ответили {answered} из {total}</text>')
    elif kind == 'silent':
        out.append(f'<text class="bd2" x="{f(x)}" y="{f(y)}" text-anchor="end" style="fill:{WARNING}">'
                   f'не ответили</text>')
    else:
        for i, name in enumerate(names[:3]):
            row = y + i * FLUID_ROW
            out.append(f'<circle cx="{f(x - 5)}" cy="{f(row - 5)}" r="4.4" fill="{DANGER}"/>')
            out.append(f'<text class="rd2" x="{f(x - 18)}" y="{f(row)}" text-anchor="end" '
                       f'style="fill:{DANGER}">{name}</text>')
        if len(names) > 3:
            out.append(f'<text class="bd2" x="{f(x)}" y="{f(y + 3 * FLUID_ROW)}" '
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
    .ttl { font-size:13px; font-weight:500; letter-spacing:0.12em; fill:%(muted_deep)s; }
    .ttl2 { font-size:11px; font-weight:500; letter-spacing:0.12em; fill:%(muted_deep)s; }
    .fg { font-family:'Roboto Mono',monospace; font-weight:200; font-size:52px; fill:%(ink)s; }
    .rd { font-family:'Roboto Mono',monospace; font-weight:300; font-size:24px; fill:%(ink)s; }
    .rd2 { font-family:'Roboto Mono',monospace; font-weight:300; font-size:18px; fill:%(ink)s; }
    .un { font-size:18px; fill:%(muted)s; }
    .bd { font-size:18px; fill:%(muted_deep)s; }
    .bd2 { font-size:13px; fill:%(muted_deep)s; }
    .ghost { font-size:11px; letter-spacing:0.12em; fill:%(ghost_ink)s; }
  </style>
</helmet>
"""


def page(body, width=W, height=H, note=None):
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

def trace(points, sleeping=40):
    """Two minutes of a generating engine, deterministic so the board is stable."""
    rpm, gen = [], []
    for i in range(120):
        if i < sleeping:
            rpm.append(None)
            gen.append(None)
            continue
        t = (i - sleeping) / (120.0 - sleeping)
        spin = 900 + 1500 * min(1.0, t * 4) + 120 * ((i * 7) % 5) / 4.0
        rpm.append(round(spin, 1))
        gen.append(round(max(0.0, 11.0 * min(1.0, (t - 0.10) * 6) + 1.4 * ((i * 5) % 4) / 3.0), 2))
    return rpm, gen


def values(window='MEDIUM'):
    bars_, caption, average, _ = g.chart_for(window)
    rpm_trace, gen_trace = trace(None)
    return dict(
        flow='34', volts='550', spread='3',
        bars=bars_, average_value=average, chart_caption=caption,
        rpm='2418', fuel='53',
        rpm_trace=rpm_trace, gen_trace=gen_trace,
        engine_caption='заряжает 12 кВт', fluids=('ok', [], 8, 8),
        pack_c='18', inverter_c='17', motors_c='20 · 25 · 25',
    )


FLUID_NAMES = ['давление масла', 'уровень ОЖ', 'масло КПП', 'уровень масла']


def build():
    v = values()
    body = ghost() + bloom(34.0) + electric_column(v) + engine_column(v)
    open(os.path.join(HERE, 'ClusterNext.dc.html'), 'w').write(page(body))

    # The fluids corner in every state it has, at the size it is really drawn.
    cell_w, cell_h = 380.0, 190.0
    states = [
        ('ok', [], 8, 8),
        ('partial', [], 5, 8),
        ('alert', FLUID_NAMES[:1], 8, 8),
        ('alert', FLUID_NAMES, 8, 8),
    ]
    labels = ['всё в норме', 'часть не ответила', 'одна неисправность', 'четыре']
    body = []
    for i, state in enumerate(states):
        ox, oy = 24 + (i % 2) * (cell_w + 24), 24 + (i // 2) * (cell_h + 24)
        body.append(f'<g transform="translate({f(ox)},{f(oy)})">')
        body.append(f'<rect x="0" y="0" width="{f(cell_w)}" height="{f(cell_h)}" rx="14" '
                    f'fill="#0A0B0D" stroke="rgba(218,225,235,0.10)"/>')
        body += [line.replace(f'x="{f(W - 26.0)}"', f'x="{f(cell_w - 22)}"')
                 for line in fluids(state, x_right=cell_w - 22, y0=34.0)]
        body.append(f'<text class="ghost" x="22" y="{f(cell_h - 16)}">{labels[i]}</text>')
        body.append('</g>')
    total_w, total_h = 24 + 2 * (cell_w + 24), 24 + 2 * (cell_h + 24)
    open(os.path.join(HERE, 'ClusterFluids.dc.html'), 'w').write(
        page(body, width=total_w, height=total_h))
    print('ClusterNext, ClusterFluids')


if __name__ == '__main__':
    build()
