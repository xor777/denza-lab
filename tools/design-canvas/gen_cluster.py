#!/usr/bin/env python3
"""
Emit the cluster artboards from the same constants the app draws with.

Every coordinate on these boards used to be typed. The audit found what that
costs: three tick marks on three different radii, one of them lying along the
arc instead of across it, and labels naming 60/150/300 at angles that meant
150/218/290. Nothing here is typed - the geometry is computed from the values in
InstrumentDensity, EnergyScale, ChartScale, EnergyGauge, ClusterBlockPlan and
ClusterDashboardLayout, so a board can only disagree with the app if this file
is out of date with those, which is one thing to check rather than sixty.

Text is never measured here either. A number and its unit go into one <text>
element as two <tspan>s with a dx gap, and the browser lays them out - the same
division of labour as InstrumentPen.figure(), which measures with Paint.
"""
import math

# ---------------------------------------------------------------- the app's values

class Density:
    def __init__(self, figure, reading, body, title, tick, dial_marks, step,
                 arc_width, tick_length, track_height, bar_width, dot_radius,
                 hairline, lamp_step, lamp_radius):
        self.figure, self.reading, self.body = figure, reading, body
        self.title, self.tick, self.dial_marks = title, tick, dial_marks
        self.step, self.arc_width, self.tick_length = step, arc_width, tick_length
        self.track_height, self.bar_width = track_height, bar_width
        self.dot_radius, self.hairline = dot_radius, hairline
        self.lamp_step, self.lamp_radius = lamp_step, lamp_radius

    def rhythm(self, steps):
        return self.step * steps


WIDE = Density(52, 24, 18, 13, 13, 2, 8, 10, 9, 7, 9, 7.8, 1.2, 21, 5.5)
COMPACT = Density(34, 18, 13, 11, 11, 1, 6, 7, 7, 5, 6, 5.5, 1.2, 16, 4)

# The projected panels are a different screen at a different distance, so they
# get their own ladder - 82/62/46/34/24/19/15 - rather than pretending one set of
# pixel sizes serves a 320 dpi cluster and a head unit at arm's length. What the
# two share is the rule, not the numbers: a size off the ramp is not available.
PANEL = Density(62, 34, 19, 15, 15, 2, 12, 14, 12, 10, 12, 10, 1.5, 30, 7)
PANEL_NARROW = Density(46, 24, 19, 15, 15, 1, 12, 10, 10, 8, 9, 8, 1.5, 26, 6)

TITLE_TRACKING = 0.12

FULL_DISCHARGE_KW, FULL_REGEN_KW, FLOOR_KW = 300.0, 100.0, 0.5
DISCHARGE_TICKS, REGEN_TICKS = [60.0, 150.0], [20.0]

ARC_FROM, ARC_TO, TOP_DEG, SIDE_SWEEP = 200.0, -20.0, 90.0, 110.0
MARK_GAP, BASELINE_NUDGE = 3.0, 0.36
CHART_HALF_WIDTH, CHART_ZERO_ABOVE, CHART_HEIGHT = 0.80, 0.06, 0.52
FIGURE_ABOVE, CAPTION_BELOW = 0.60, 0.30
ABOVE_ZERO_SHARE = 0.74

LEAD_TITLE, LEAD_FIGURE, LEAD_GROUP, LEAD_ROW = 2.0, 3.0, 3.0, 2.0

# The consumption log and its three windows, mirroring ConsumptionWindow.
BUCKET_KM = 0.1
TARGET_BARS = 30
WINDOWS = [('SHORT', 3.0, '3 км'), ('MEDIUM', 10.0, '10 км'), ('LONG', 30.0, '30 км')]

INK = '#DAE1EB'
MUTED = '#86909B'
MUTED_DEEP = '#6E767F'
TRACK = '#22262E'
TRACK_MARK = '#3F434D'
RETURN = '#2D82D7'
RETURN_INK = '#4B9BE0'
DATA_PEAK = '#FFF8DA'
WARNING = '#FF9F19'
BACKGROUND = '#07080A'
# The cluster's own ground since 2026-08-25: the panel is black, and three per cent
# of grey is a visible rectangle there while it is nothing on a board in a browser.
CLUSTER_BG = '#000000'
INK_55 = 'rgba(218,225,235,0.55)'
INK_32 = 'rgba(218,225,235,0.32)'
INK_24 = 'rgba(218,225,235,0.24)'
INK_70 = 'rgba(218,225,235,0.70)'
RETURN_78 = 'rgba(45,130,215,0.78)'
RETURN_60 = 'rgba(45,130,215,0.60)'
RETURN_75 = 'rgba(45,130,215,0.75)'
ACCENT_34 = 'rgba(254,239,171,0.34)'


def sweep_fraction(kw):
    m = abs(kw)
    if m <= FLOOR_KW:
        return 0.0
    span = FULL_REGEN_KW if kw < 0 else FULL_DISCHARGE_KW
    return math.sqrt(min(m / span, 1.0))


def angle_degrees(kw):
    travel = sweep_fraction(kw) * SIDE_SWEEP
    return TOP_DEG + travel if kw < 0 and abs(kw) > FLOOR_KW else TOP_DEG - travel


def ceilings(values):
    high = max([v for v in values] + [0.0])
    low = min([v for v in values] + [0.0])
    pos = max(10.0, math.ceil(high / 10.0) * 10.0)
    neg = max(5.0, math.ceil(abs(low) / 5.0) * 5.0)
    return pos, neg


def window_by_name(name):
    for n, km, label in WINDOWS:
        if n == name:
            return km, label
    raise KeyError(name)


def fold(all_values, window_km):
    """The bars a window draws, grouped from the newest end - as the app does."""
    buckets = int(round(window_km / BUCKET_KM))
    tail = all_values[-buckets:] if len(all_values) > buckets else list(all_values)
    per = max(1, -(-buckets // TARGET_BARS))
    if per == 1 or not tail:
        return tail
    bars, end = [], len(tail)
    while end > 0:
        start = max(0, end - per)
        bars.append(sum(tail[start:end]) / (end - start))
        end = start
    bars.reverse()
    return bars


def covered_km(all_values, window_km):
    buckets = int(round(window_km / BUCKET_KM))
    return min(len(all_values), buckets) * BUCKET_KM


def average_consumption(values):
    spending = [v for v in values if v >= 0]
    return sum(spending) / len(spending) if spending else None


# ---------------------------------------------------------------- the layout

class Layout:
    def __init__(self, placement, display_w=2560, display_h=720):
        self.placement = placement
        full = placement == 'FULL'
        self.virtual_h = 424.0 if full else 308.0
        if full:
            self.px_w, self.px_h = float(display_w), float(display_h)
        else:
            self.px_w, self.px_h = 1023.0, 524.0
        self.w = self.virtual_h * self.px_w / self.px_h
        self.h = self.virtual_h

        top_reveal_h = (display_w * 20 // 100) * 40 // 100 * 4 // 3 if full else 0
        bottom = (90 + 60) if full else 0
        self.stock_top = top_reveal_h / self.px_h
        self.stock_bottom = 1.0 - bottom / self.px_h
        self.left_reveal_x = (display_w * 24 // 100) / self.px_w if full else 0.0
        self.right_reveal_x = (display_w * 20 // 100) / self.px_w if full else 0.0
        self.bottom_reveal_x = 600 / self.px_w if full else 0.0
        self.bottom_reveal_y = (600 * 55 / 100) / self.px_h if full else 0.0
        self.bottom_reveal_cy = 1.0 - 120 / self.px_h if full else 1.0

        self.gauge_cx = 0.5
        self.gauge_cy = 0.76 if not full else 0.78
        self.gauge_r = 0.383 if not full else 0.372

        span = self.stock_bottom - self.stock_top
        self.band_top = self.stock_top + span * 0.06
        self.band_bottom = self.stock_bottom - span * 0.03
        self.electric = (0.017, self.band_top, 0.303, self.band_bottom)
        self.engine = (0.697, self.band_top, 0.983, self.band_bottom)

        self.temperature = self._reveal(self.left_reveal_x, True) if full else None
        self.lamps = self._reveal(self.right_reveal_x, False) if full else None

    def _reveal(self, radius_x, from_left):
        if radius_x <= 0 or self.stock_top <= 0:
            return None
        bottom = self.stock_top * 0.70
        reach = radius_x * math.sqrt(1 - 0.70 ** 2) - 0.008
        top = self.stock_top * 0.14
        return (0.017, top, reach, bottom) if from_left else (1 - reach, top, 0.983, bottom)

    def box(self, frac):
        """A box in fractions -> (left, top, right, bottom) in virtual units."""
        l, t, r, b = frac
        return l * self.w, t * self.h, r * self.w, b * self.h


# ---------------------------------------------------------------- the row plan

class Column:
    """ClusterBlockPlan + Column, in one place: anchors, centred in the box."""

    def __init__(self, layout, frac, density, rows):
        self.left, top, self.right, bottom = layout.box(frac)
        self.d = density
        content = sum(density.rhythm(lead) + body for _, body, lead in rows)
        cursor = top + max(0.0, ((bottom - top) - content) / 2.0)
        self.anchors, self.kinds = [], []
        for kind, body, lead in rows:
            cursor += density.rhythm(lead)
            if kind == 'text':
                cursor += body
                self.anchors.append(cursor)
            elif kind == 'rule':
                cursor += body / 2.0
                self.anchors.append(cursor)
                cursor += body / 2.0
            else:  # dots: body = rows * step
                step = density.lamp_step
                cursor += step / 2.0
                self.anchors.append(cursor)
                cursor += body - step / 2.0
            self.kinds.append(kind)
        self.taken = 0

    def next(self):
        a = self.anchors[min(self.taken, len(self.anchors) - 1)]
        self.taken += 1
        return a


def text_row(d, size, lead=0.0):
    return ('text', size, lead)


def rule_row(d, lead):
    return ('rule', d.track_height, lead)


def dots_row(d, rows, lead):
    return ('dots', rows * d.lamp_step, lead)


# ---------------------------------------------------------------- svg helpers

def f(x):
    return f'{x:.1f}'.rstrip('0').rstrip('.') if abs(x) > 1e-9 else '0'


def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def arc_path(cx, cy, r, a0, a1):
    x0, y0 = polar(cx, cy, r, a0)
    x1, y1 = polar(cx, cy, r, a1)
    large = 1 if abs(a1 - a0) > 180 else 0
    sweep = 1 if a1 < a0 else 0
    return f'M{f(x0)} {f(y0)} A{f(r)} {f(r)} 0 {large} {sweep} {f(x1)} {f(y1)}'


def pair(x, baseline, parts, anchor='start'):
    """One <text> holding a number and its unit; the browser measures the run."""
    spans = []
    for i, part in enumerate(parts):
        cls, text, dx = part[0], part[1], part[2]
        colour = part[3] if len(part) > 3 else None
        attr = f' dx="{f(dx)}"' if i else ''
        style = f' style="fill:{colour}"' if colour else ''
        spans.append(f'<tspan class="{cls}"{attr}{style}>{text}</tspan>')
    a = f' text-anchor="{anchor}"' if anchor != 'start' else ''
    return f'<text x="{f(x)}" y="{f(baseline)}"{a}>{"".join(spans)}</text>'


# ---------------------------------------------------------------- the gauge

def gauge(layout, d, kw, bars, caption):
    cx, cy = layout.gauge_cx * layout.w, layout.gauge_cy * layout.h
    r = layout.gauge_r * layout.h
    out = []

    out.append(f'<path d="{arc_path(cx, cy, r, ARC_FROM, ARC_TO)}" fill="none" '
               f'stroke="{TRACK}" stroke-width="{f(d.arc_width)}" stroke-linecap="round"/>')

    def mark(kw_mark, mark_color, text_color):
        deg = angle_degrees(kw_mark)
        i0, i1 = r + MARK_GAP, r + MARK_GAP + d.tick_length
        x0, y0 = polar(cx, cy, i0, deg)
        x1, y1 = polar(cx, cy, i1, deg)
        out.append(f'<path d="M{f(x0)} {f(y0)} L{f(x1)} {f(y1)}" stroke="{mark_color}" '
                   f'stroke-width="1.8" stroke-linecap="round"/>')
        if text_color:
            lx, ly = polar(cx, cy, r + MARK_GAP + d.tick_length + d.step + d.tick / 2, deg)
            out.append(f'<text class="tk" x="{f(lx)}" y="{f(ly + d.tick * BASELINE_NUDGE)}" '
                       f'text-anchor="middle" style="fill:{text_color}">'
                       f'{int(abs(kw_mark))}</text>')

    for m in DISCHARGE_TICKS[:d.dial_marks]:
        mark(m, TRACK_MARK, MUTED_DEEP)
    for m in REGEN_TICKS:
        mark(-m, RETURN_60, RETURN_75)
    mark(0.0, MUTED_DEEP, None)

    if kw is not None and sweep_fraction(kw) > 0:
        deg = angle_degrees(kw)
        colour = RETURN if kw < 0 else INK
        out.append(f'<path d="{arc_path(cx, cy, r, TOP_DEG, deg)}" fill="none" '
                   f'stroke="{colour}" stroke-width="{f(d.arc_width)}" stroke-linecap="round"/>')
        px, py = polar(cx, cy, r, deg)
        out.append(f'<circle cx="{f(px)}" cy="{f(py)}" r="{f(d.dot_radius)}" fill="{DATA_PEAK}"/>')

    if bars:
        zero_y = cy - r * CHART_ZERO_ABOVE
        height = r * CHART_HEIGHT
        top = zero_y - height * ABOVE_ZERO_SHARE
        half = r * CHART_HALF_WIDTH
        left, right = cx - half, cx + half
        out.append(f'<line x1="{f(left)}" y1="{f(zero_y)}" x2="{f(right)}" y2="{f(zero_y)}" '
                   f'stroke="{INK_24}" stroke-width="{f(d.hairline)}"/>')
        pos, neg = ceilings(bars)
        step = (right - left) / len(bars)
        bw = min(d.bar_width, step * 0.78)
        for i, v in enumerate(bars):
            room = (zero_y - top) if v >= 0 else (top + height - zero_y)
            bh = min(abs(v) / (pos if v >= 0 else neg), 1.0) * room
            if bh <= 0:
                continue
            x = left + step * i + (step - bw) / 2
            y = zero_y - bh if v >= 0 else zero_y
            c = RETURN_78 if v < 0 else (DATA_PEAK if i == len(bars) - 1 else INK_55)
            out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(bw)}" height="{f(bh)}" fill="{c}"/>')
        avg = average_consumption(bars)
        if avg:
            ay = zero_y - min(avg / pos, 1.0) * (zero_y - top)
            out.append(f'<line x1="{f(left)}" y1="{f(ay)}" x2="{f(right)}" y2="{f(ay)}" '
                       f'stroke="{ACCENT_34}" stroke-width="{f(d.hairline)}" '
                       f'stroke-dasharray="5 5"/>')

    reading = '—' if kw is None else f'{abs(kw):.0f}'
    out.append(pair(cx, cy - r * FIGURE_ABOVE,
                    [('fg', reading, 0), ('un', 'кВт', d.rhythm(1))], anchor='middle'))
    out.append(f'<text class="bd" x="{f(cx)}" y="{f(cy + r * CAPTION_BELOW)}" '
               f'text-anchor="middle">{caption}</text>')
    return out


# ---------------------------------------------------------------- the blocks

def track(left, right, cy, d, fraction, colour, with_track=True):
    out = []
    h = d.track_height
    if with_track:
        out.append(f'<rect x="{f(left)}" y="{f(cy - h / 2)}" width="{f(right - left)}" '
                   f'height="{f(h)}" rx="{f(h / 2)}" fill="{TRACK}"/>')
    if fraction and fraction > 0:
        out.append(f'<rect x="{f(left)}" y="{f(cy - h / 2)}" width="{f((right - left) * fraction)}" '
                   f'height="{f(h)}" rx="{f(h / 2)}" fill="{colour}"/>')
    return out


def bloom(layout, kw):
    """The pool of light around the dial, at the reach and strength that shipped.

    Found by accident when the old scrim ran over the vehicle's own lit
    background; kept on purpose. 2.4 by 1.85 radii at 0.16 alpha of INK.
    """
    cx, cy = layout.gauge_cx * layout.w, layout.gauge_cy * layout.h
    r = layout.gauge_r * layout.h
    return [
        '<defs><radialGradient id="dialglow">'
        f'<stop offset="0" stop-color="{INK}" stop-opacity="0.16"/>'
        f'<stop offset="0.5" stop-color="{INK}" stop-opacity="0.072"/>'
        f'<stop offset="1" stop-color="{INK}" stop-opacity="0"/>'
        '</radialGradient></defs>',
        f'<ellipse cx="{f(cx)}" cy="{f(cy)}" rx="{f(r * 2.4)}" ry="{f(r * 1.85)}" '
        'fill="url(#dialglow)"/>',
    ]


def engine_trace(x0, x1, baseline, height, rpm, gen):
    """The sparkline beside the revolutions, as shipped: two runs, no scale."""
    out = []
    for values, ceiling, colour, root in ((rpm, 6000.0, INK_55, False),
                                          (gen, 100.0, RETURN, True)):
        run = []
        for i, value in enumerate(values):
            if value is None:
                continue
            share = min(1.0, max(0.0, value / ceiling))
            if root:
                share = share ** 0.5
            run.append((x0 + (x1 - x0) * i / (len(values) - 1), baseline - height * share))
        if len(run) < 2:
            continue
        d = 'M ' + ' L '.join(f'{f(x)} {f(y)}' for x, y in run)
        out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="1.9" '
                   'stroke-linejoin="round" stroke-linecap="round"/>')
        out.append(f'<circle cx="{f(run[-1][0])}" cy="{f(run[-1][1])}" r="3.9" fill="{colour}"/>')
    return out


def engine_history(sleeping=40, slots=120):
    """Two minutes of a generating engine. Deterministic, so a board is stable."""
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


def electric_wide(layout, d, v):
    rows = [text_row(d, d.title), text_row(d, d.figure, LEAD_TITLE),
            rule_row(d, LEAD_FIGURE), text_row(d, d.body, LEAD_GROUP)]
    c = Column(layout, layout.electric, d, rows)
    out = [f'<text class="ttl" x="{f(c.left)}" y="{f(c.next())}">ЭЛЕКТРИКА</text>']
    y = c.next()
    out.append(pair(c.left, y, [('fg', v['volts'], 0), ('un', 'В', d.rhythm(1))]))
    out.append(pair(c.right, y, [('rd', v['spread'], 0, v.get('spread_colour')),
                                 ('bd', 'мВ разброс', d.rhythm(1))], anchor='end'))
    out += track(c.left, c.right, c.next(), d, v['volt_fraction'], INK)
    out.append(f'<text class="bd" x="{f(c.left)}" y="{f(c.next())}">{v["pack_line"]}</text>')
    return out


def engine_wide(layout, d, v):
    """As shipped after the first live run: the tank and the sentence are gone.

    Both were on the vehicle's own cluster a few centimetres away. The room the
    tank leaves goes to a trace of where the revolutions and the generation have
    just been, drawn on the figure's own baseline and the height of its digits -
    no row is added, and the row reads as one object.
    """
    rows = [text_row(d, d.title), text_row(d, d.figure, LEAD_TITLE),
            rule_row(d, LEAD_FIGURE), text_row(d, d.body, LEAD_GROUP)]
    c = Column(layout, layout.engine, d, rows)
    out = [f'<text class="ttl" x="{f(c.right)}" y="{f(c.next())}" text-anchor="end">ДВИГАТЕЛЬ</text>']
    y = c.next()
    out.append(pair(c.right, y, [('fg', v['rpm'], 0), ('un', 'об/мин', d.rhythm(1))], anchor='end'))
    # The figure's own width, so the trace stops where the digits begin.
    figure_w = len(v['rpm']) * d.figure * 0.6 + d.rhythm(1) + len('об/мин') * d.body * 0.52
    rpm_trace, gen_trace = engine_history()
    out += engine_trace(c.left, c.right - figure_w - d.rhythm(2), y, d.figure * 0.72,
                        rpm_trace, gen_trace)
    rule = c.next()
    out += track(c.left, c.right, rule, d, v['rpm_fraction'], INK_32)
    if v['generation_fraction']:
        out += track(c.left, c.right, rule, d, v['generation_fraction'], RETURN, with_track=False)
    # The last row is planned and usually empty: it speaks only for the exception.
    if v.get('engine_line'):
        out.append(f'<text class="bd" x="{f(c.right)}" y="{f(c.next())}" '
                   f'text-anchor="end">{v["engine_line"]}</text>')
    return out


def electric_narrow(layout, d, v):
    rows = [text_row(d, d.title), text_row(d, d.figure, LEAD_TITLE), rule_row(d, LEAD_FIGURE),
            text_row(d, d.reading, LEAD_GROUP), text_row(d, d.reading, LEAD_ROW),
            text_row(d, d.body, LEAD_GROUP)]
    c = Column(layout, layout.electric, d, rows)
    out = [f'<text class="ttl" x="{f(c.left)}" y="{f(c.next())}">ЭЛЕКТРИКА</text>']
    out.append(pair(c.left, c.next(), [('fg', v['volts'], 0), ('un', 'В', d.rhythm(1))]))
    out += track(c.left, c.right, c.next(), d, v['volt_fraction'], INK)
    out.append(pair(c.left, c.next(), [('rd', v['spread'], 0, v.get('spread_colour')),
                                       ('bd', 'мВ разброс', d.rhythm(1))]))
    out.append(pair(c.left, c.next(), [('rd', v['soh'], 0), ('bd', '% ресурс', d.rhythm(1))]))
    out.append(f'<text class="bd" x="{f(c.left)}" y="{f(c.next())}">{v["pack_line_brief"]}</text>')
    return out


def engine_narrow(layout, d, v):
    rows = [text_row(d, d.title), text_row(d, d.figure, LEAD_TITLE), rule_row(d, LEAD_FIGURE),
            text_row(d, d.reading, LEAD_GROUP), rule_row(d, LEAD_ROW),
            text_row(d, d.body, LEAD_GROUP)]
    c = Column(layout, layout.engine, d, rows)
    out = [f'<text class="ttl" x="{f(c.right)}" y="{f(c.next())}" text-anchor="end">ДВИГАТЕЛЬ</text>']
    out.append(pair(c.right, c.next(), [('fg', v['rpm'], 0), ('un', 'об/мин', d.rhythm(1))],
                    anchor='end'))
    rule = c.next()
    out += track(c.left, c.right, rule, d, v['rpm_fraction'], INK_32)
    if v['generation_fraction']:
        out += track(c.left, c.right, rule, d, v['generation_fraction'], RETURN, with_track=False)
    out.append(pair(c.right, c.next(), [('rd', v['fuel'], 0, v.get('fuel_colour')),
                                        ('bd', '% бак', d.rhythm(1))], anchor='end'))
    out += track(c.left, c.right, c.next(), d, v['fuel_fraction'], INK_70)
    out.append(f'<text class="bd" x="{f(c.right)}" y="{f(c.next())}" '
               f'text-anchor="end">{v["lamp_line_brief"]}</text>')
    return out


def temperatures(layout, v):
    d = COMPACT
    rows = [text_row(d, d.title), text_row(d, d.reading, LEAD_ROW), text_row(d, d.reading, LEAD_ROW)]
    c = Column(layout, layout.temperature, d, rows)
    out = [f'<text class="ttl2" x="{f(c.left)}" y="{f(c.next())}">ТЕМПЕРАТУРЫ</text>']
    out.append(pair(c.left, c.next(), [
        ('rd2', v['pack_c'], 0, v.get('pack_c_colour')), ('bd2', 'батарея', d.rhythm(1)),
        ('rd2', v['inverter_c'], d.rhythm(LEAD_GROUP)), ('bd2', 'инвертор', d.rhythm(1))]))
    out.append(pair(c.left, c.next(), [('rd2', v['motors_c'], 0), ('bd2', 'моторы', d.rhythm(1))]))
    return out


def lamps(layout, v):
    d = COMPACT
    rows = [text_row(d, d.title), dots_row(d, 2, LEAD_TITLE), text_row(d, d.body, LEAD_ROW)]
    c = Column(layout, layout.lamps, d, rows)
    out = [f'<text class="ttl2" x="{f(c.right)}" y="{f(c.next())}" text-anchor="end">ЖИДКОСТИ</text>']
    first = c.next()
    for i, state in enumerate(v['lamps']):
        x = c.right - d.lamp_step * (3 - i % 4)
        y = first + d.lamp_step * (i // 4)
        if state == 'alert':
            out.append(f'<circle cx="{f(x)}" cy="{f(y)}" r="{f(d.lamp_radius)}" fill="#FF4046"/>')
        elif state == 'ok':
            out.append(f'<circle cx="{f(x)}" cy="{f(y)}" r="{f(d.lamp_radius)}" fill="{INK_55}"/>')
        else:
            out.append(f'<circle cx="{f(x)}" cy="{f(y)}" r="{f(d.lamp_radius)}" fill="none" '
                       f'stroke="rgba(218,225,235,0.35)" stroke-width="1.4"/>')
    colour = '#FF4046' if v['lamp_alert'] else MUTED_DEEP
    out.append(f'<text class="bd2" x="{f(c.right)}" y="{f(c.next())}" text-anchor="end" '
               f'style="fill:{colour}">{v["lamp_line"]}</text>')
    return out


# ---------------------------------------------------------------- keep-out drawing

def keepout(layout):
    w, h = layout.w, layout.h
    if layout.stock_top <= 0:
        return []
    top_h = layout.stock_top * h
    bot_y = layout.stock_bottom * h
    lx, rx = layout.left_reveal_x * w, layout.right_reveal_x * w
    bx, by = layout.bottom_reveal_x * w, layout.bottom_reveal_y * h
    bcy = layout.bottom_reveal_cy * h
    return [
        '<defs>',
        '<pattern id="hatch" width="10" height="10" patternUnits="userSpaceOnUse" '
        'patternTransform="rotate(45)">'
        '<line x1="0" y1="0" x2="0" y2="10" stroke="rgba(218,225,235,0.07)" stroke-width="1.4"/>'
        '</pattern>',
        f'<mask id="topmask"><rect x="0" y="0" width="{f(w)}" height="{f(top_h)}" fill="#fff"/>'
        f'<ellipse cx="0" cy="0" rx="{f(lx)}" ry="{f(top_h)}" fill="#000"/>'
        f'<ellipse cx="{f(w)}" cy="0" rx="{f(rx)}" ry="{f(top_h)}" fill="#000"/></mask>',
        f'<mask id="botmask"><rect x="0" y="{f(bot_y)}" width="{f(w)}" height="{f(h - bot_y)}" '
        f'fill="#fff"/><ellipse cx="{f(w / 2)}" cy="{f(bcy)}" rx="{f(bx)}" ry="{f(by)}" '
        f'fill="#000"/></mask>',
        '</defs>',
        f'<rect x="0" y="0" width="{f(w)}" height="{f(top_h)}" fill="url(#hatch)" '
        f'mask="url(#topmask)"/>',
        f'<rect x="0" y="{f(bot_y)}" width="{f(w)}" height="{f(h - bot_y)}" fill="url(#hatch)" '
        f'mask="url(#botmask)"/>',
        f'<text class="keep" x="{f(w / 2)}" y="26" text-anchor="middle">ШТАТНЫЕ ПРИБОРЫ</text>',
        # Left of the bottom reveal, so the words naming the stock strip sit on the stock strip.
        f'<text class="keep" x="{f(layout.electric[0] * w)}" y="{f(h - 16)}">ШТАТНАЯ ПОЛОСА</text>',
    ]


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
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500&amp;family=Roboto+Mono:wght@300;400&amp;display=swap" rel="stylesheet">
  <style>
    body { margin:0; background:%(bg)s; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    a { color:#FEEFAB; } a:hover { color:#FFF7D2; }
    .ttl { font-size:%(title)spx; font-weight:500; letter-spacing:%(tracking)sem; fill:%(muted_deep)s; }
    .ttl2 { font-size:%(title2)spx; font-weight:500; letter-spacing:%(tracking)sem; fill:%(muted_deep)s; }
    .fg { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(figure)spx; fill:%(ink)s; }
    .rd { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(reading)spx; fill:%(ink)s; }
    .rd2 { font-family:'Roboto Mono',monospace; font-weight:300; font-size:%(reading2)spx; fill:%(ink)s; }
    .un { font-size:%(body)spx; fill:%(muted)s; }
    .bd { font-size:%(body)spx; fill:%(muted_deep)s; }
    .bd2 { font-size:%(body2)spx; fill:%(muted_deep)s; }
    .tk { font-family:'Roboto Mono',monospace; font-size:%(tick)spx; fill:%(muted_deep)s; }
    .keep { font-size:%(title2)spx; letter-spacing:%(tracking)sem; fill:#3F434D; }
  </style>
</helmet>
"""


def page(layout, density, body):
    css = HEAD % dict(bg=CLUSTER_BG, ink=INK, muted=MUTED, muted_deep=MUTED_DEEP,
                      title=f(density.title), title2=f(COMPACT.title), tracking=TITLE_TRACKING,
                      figure=f(density.figure), reading=f(density.reading),
                      reading2=f(COMPACT.reading), body=f(density.body), body2=f(COMPACT.body),
                      tick=f(density.tick))
    w, h = f(layout.w), f(layout.h)
    return (css +
            f'<div style="width:{w}px; height:{h}px; background:{CLUSTER_BG}; position:relative;">\n'
            f'  <svg width="{w}" height="{h}" viewBox="0 0 {w} {h}">\n    ' +
            '\n    '.join(body) +
            f'\n  </svg>\n</div>\n</x-dc>\n'
            f'<script data-dc-script data-props=\'{{"$preview":{{"width":{w},"height":{h}}}}}\'>\n'
            'class Component extends DCLogic { renderVals() { return {}; } }\n'
            '</script>\n</body>\n</html>\n')


def gauge_block(width, cx, cy, r, d, kw, bars, caption):
    """A standalone <svg> holding the energy control, for splicing into a panel.

    The panels draw the same instrument as the cluster and used to draw it by
    hand: on the two-thirds board the zero mark ran through the '4' of its own
    readout and the caption underneath touched the numeral. Same component, same
    arithmetic, one place.
    """
    class Box:
        pass

    layout = Box()
    layout.w, layout.h = width, cy + r * 0.342 + d.arc_width
    layout.gauge_cx, layout.gauge_cy, layout.gauge_r = cx / width, cy / layout.h, r / layout.h
    body = gauge(layout, d, kw, bars, caption)
    h = f(layout.h)
    return (f'<svg width="{f(width)}" height="{h}" viewBox="0 0 {f(width)} {h}">\n        ' +
            '\n        '.join(body) + '\n      </svg>')


# ---------------------------------------------------------------- the three boards

# Thirty kilometres of road at one odometer tick a bar, the way the log keeps it.
# Deterministic rather than random so a regenerated board is the same board: a
# long swell for terrain, a short one for traffic, and a recovery dip every
# couple of kilometres where a descent or a stop gives energy back.
DRIVING_BARS = [
    round(20.5 + 11.0 * math.sin(i * 0.37) + 6.0 * math.sin(i * 0.11)
          - (26.0 if (i + 7) % 23 == 0 else 0.0), 2)
    for i in range(300)
]

DRIVING = dict(
    volts='552', spread='4', soh='99', volt_fraction=(552 - 500) / 100,
    pack_line='ресурс 99 % · изоляция 13,1 МОм',
    pack_line_brief='изоляция 13,1 МОм',
    rpm='1321', fuel='53', rpm_fraction=1321 / 6000,
    generation_fraction=math.sqrt(10 / 100),
    fuel_fraction=0.53,
    engine_line='заряжает 10 кВт',
    lamp_line='все в норме', lamp_line_brief='все в норме', lamp_alert=False,
    lamps=['ok'] * 8,
    pack_c='28°', inverter_c='32°', motors_c='31 · 29 · 31°',
)

RESTING = dict(
    volts='548', spread='4', soh='99', volt_fraction=(548 - 500) / 100,
    pack_line='заряжается · осталось 2 ч 15 мин',
    pack_line_brief='осталось 2 ч 15 мин',
    rpm='0', fuel='53', rpm_fraction=0.0,
    generation_fraction=None,
    fuel_fraction=0.53,
    engine_line='',
    lamp_line='в норме 6 из 8', lamp_line_brief='в норме 6 из 8', lamp_alert=False,
    lamps=['ok'] * 6 + ['unknown'] * 2,
    pack_c='—', pack_c_colour='rgba(218,225,235,0.40)',
    inverter_c='26°', motors_c='24 · 24 · 25°',
)


def board_full(values, kw, caption, bars):
    layout = Layout('FULL')
    body = keepout(layout)
    body += bloom(layout, kw)
    body += gauge(layout, WIDE, kw, bars, caption)
    body += electric_wide(layout, WIDE, values)
    body += engine_wide(layout, WIDE, values)
    body += temperatures(layout, values)
    body += lamps(layout, values)
    return page(layout, WIDE, body)


def board_right(values, kw, caption, bars):
    layout = Layout('RIGHT')
    body = [f'<rect x="0.5" y="0.5" width="{f(layout.w - 1)}" height="{f(layout.h - 1)}" '
            f'fill="none" stroke="rgba(218,225,235,0.10)"/>',
            f'<text class="keep" x="{f(layout.w / 2)}" y="18" text-anchor="middle">'
            f'КРОП · ШТАТНОЙ ГРАФИКИ НЕТ</text>']
    body += bloom(layout, kw)
    body += gauge(layout, COMPACT, kw, bars, caption)
    body += electric_narrow(layout, COMPACT, values)
    body += engine_narrow(layout, COMPACT, values)
    return page(layout, COMPACT, body)


def chart_for(window_name, all_values=None):
    """The bars and the sentence a window produces, from one shared dataset."""
    values = DRIVING_BARS if all_values is None else all_values
    km, label = window_by_name(window_name)
    tail = values[-int(round(km / BUCKET_KM)):]
    avg = average_consumption(tail)
    covered = covered_km(values, km)
    caption = (f'{avg:.1f}'.replace('.', ',') +
               ' средний за ' + f'{covered:.1f}'.replace('.', ',') + ' км')
    return fold(values, km), caption, avg, label


if __name__ == '__main__':
    bars, caption, avg, label = chart_for('SHORT')

    open('ClusterFull.dc.html', 'w').write(board_full(DRIVING, 34.0, caption, bars))
    open('ClusterRight.dc.html', 'w').write(board_right(DRIVING, 34.0, caption, bars))
    open('ClusterRest.dc.html', 'w').write(board_full(RESTING, -2.4, 'стоим', []))

    print('window', label, 'bars', len(bars), 'average', round(avg, 2))
    for name, km, lbl in WINDOWS:
        b, c, a, _ = chart_for(name)
        print(f'  {lbl:6} -> {len(b):3} bars of {km / len(b):.2f} km, "{c}"')
    for name, kw in (('60', 60.0), ('150', 150.0), ('-20', -20.0), ('34', 34.0)):
        print(f'  {name} kW -> {angle_degrees(float(kw)):.2f} deg, '
              f'fraction {sweep_fraction(float(kw)):.3f}')
