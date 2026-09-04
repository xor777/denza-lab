#!/usr/bin/env python3
"""
The cluster, fourth pass: one recorder, and the light is the instrument.

The shipped panel (`gen_cluster.py`) is five islands of diagnostics on black -
temperatures, fluids, voltage, revolutions, and a dial with a bar chart inside
it. Every fact on it is defensible and the whole is a service screen: nothing on
it changes with the driver's right foot, so nothing rewards being looked at, and
the owner's verdict was the honest one - minimal, and not worth switching on.

The third pass (`gen_next.py`) fixed the composition and kept the census: two
area charts on different axes flanking a dial, five objects, and a top row of
small numbers floating over a void. It also draws outside the apertures - its
horizon sits 8 units below the stock band's edge and its inward variant parks
both corner blocks under the vehicle's own graphics.

This pass throws the census out and keeps one instrument.

  A recorder. One horizon runs the full width. Above it is power leaving the
  pack, below it is power arriving - regeneration, and the engine's generation
  as a plinth against the line. Time runs left to right and now is the right
  edge. The range is not fixed: it snaps to a ladder and the panel prints it,
  the way an instrument does and a decoration does not.

Three rules the whole panel obeys:

  * ink above the line is spending, blue below it is returning;
  * the light is the value - a wide soft bloom over the horizon carries the
    magnitude and the direction to the corner of the eye, so the panel is read
    before it is read;
  * everything else is silent until it has something to say. A stopped engine
    draws nothing. Healthy fluids draw nothing. The rows they would use are
    planned and left empty, so nothing on the panel ever moves.

Four boards:

  ClusterHorizon       the design, spending 34 kW in traffic
  ClusterHorizonDial   the same panel with the live value inside a tick arc -
                       the one decision worth asking, and the one that costs a
                       three-digit reading (see DIAL_R)
  ClusterHorizonStates rest, return, generation, charge, fault
  ClusterHorizonPlan   the apertures, the skeleton, and every number on it

Geometry is computed, never typed: the apertures come out of the same integer
arithmetic `ClusterModels.kt` does for a 2560x720 panel in `FULL`, so a board
can only disagree with the car if that file moved.
"""
import math
import os

import gen_cluster as g

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- the panel

DISPLAY_W, DISPLAY_H = 2560, 720          # measured, docs/instrument-display-findings.md
W, H = 1507.6, 424.0                      # the FULL placement's virtual space, 1.70 on the glass


class Aperture:
    """Where the vehicle still draws, restated from `ClusterModels.kt`'s own arithmetic.

    The dashboard fills its box with black, so these are not holes we leave for
    somebody else's pixels to show through - they are the places the vehicle
    draws *over* us, where anything we put is simply not seen. Same numbers,
    opposite reason, and the integer division is kept because that is what the
    Kotlin does.
    """

    def __init__(self, dw=DISPLAY_W, dh=DISPLAY_H, w=W, h=H):
        top_px = dw * 20 // 100 * 40 // 100 * 4 // 3        # shadeTopRevealHeightPx = 272
        bottom_px = 90 + 60                                 # solid + fade
        self.stock_top = top_px / dh * h
        self.stock_bottom = (1.0 - bottom_px / dh) * h
        self.tl_rx = dw * 24 // 100 / dw * w                # 614 px
        self.tr_rx = dw * 20 // 100 / dw * w                # 512 px
        self.reveal_ry = self.stock_top
        self.lobe_rx = 600 / dw * w
        self.lobe_ry = 600 * 55 // 100 / dh * h
        self.lobe_cy = (1.0 - 120 / dh) * h

    def top_half_width(self, y, right=False):
        """How much room a corner reveal still has at baseline [y]."""
        rx = self.tr_rx if right else self.tl_rx
        t = 1.0 - (y / self.reveal_ry) ** 2
        return rx * math.sqrt(t) if t > 0 else 0.0

    def lobe_half_width(self, y):
        t = 1.0 - ((y - self.lobe_cy) / self.lobe_ry) ** 2
        return self.lobe_rx * math.sqrt(t) if t > 0 else 0.0


A = Aperture()

# ---------------------------------------------------------------- the skeleton

M = 32.0                       # the margin every outer edge keeps - four rhythm steps
STEP = 8.0                     # InstrumentDensity.WIDE.step
X0, X1 = M, W - M              # the recorder's own span, edge to edge

HORIZON = 282.0                # the one line
UP = 112.0                     # room above it: fourteen steps
DOWN = 40.0                    # room below it: five
CEIL_TOP = HORIZON - UP        # 170, ten units clear of the stock band
CEIL_BOTTOM = HORIZON + DOWN   # 322, fourteen clear of the lower one

ROW = (48.0, 80.0, 112.0)      # the corner rows, four steps apart
TONGUE_Y = 398.0               # the readout's baseline, in the lower aperture

CX = W / 2

# The gate the `hero` middle needs: a 104-unit numeral standing on the line has
# the record running through it, so a soft black ellipse takes the trace down to
# a whisper where the digits are. Black on black is invisible everywhere else,
# which is what makes it cheap - one radial shader, no clip paths, and nothing
# lost from the record. It is also the strongest argument against that middle.
GATE_CY, GATE_RX, GATE_RY = 240.0, 196.0, 60.0

# The dial variant's radius. It cannot grow: the crown already sits on the
# recorder's ceiling, and the ceiling is ten units under the vehicle's own
# graphics. That fixes the interior at 112, so a three-digit reading does not
# fit inside the arc - which is the argument the pair of boards exists to have.
DIAL_R = UP

# ---------------------------------------------------------------- the colour

BLACK = '#000000'
INK = g.INK
ACCENT = '#FEEFAB'             # vc_denza_progress_blue - this skin's accent is champagne
PEAK = g.DATA_PEAK
MUTED = g.MUTED
MUTED_DEEP = g.MUTED_DEEP
RETURN = g.RETURN
RETURN_INK = g.RETURN_INK
TRACK = g.TRACK
WARNING = g.WARNING
DANGER = '#FF4046'
GHOST = '#2C3138'

f = g.f

# The two ranges are ladders rather than the physics: this car spends 300 kW and
# recovers 100, and a panel scaled to either spends every ordinary minute as a
# ripple along the bottom of a tall empty box. The recorder picks the lowest rung
# that holds the window's own peak and prints it.
LADDER_UP = (30.0, 60.0, 120.0, 240.0, 300.0)
LADDER_DOWN = (20.0, 40.0, 60.0, 100.0)
FLOOR_KW = 0.6                 # under this a reading is rounding, not the car working


def rung(peak, ladder):
    for step in ladder:
        if peak <= step:
            return step
    return ladder[-1]


# ---------------------------------------------------------------- the ground

def defs():
    """Two blurs, one fade, two clips, and the gradients each band is filled with.

    The blurs are the only material the black has. They came out of an accident
    on the first live run - a halo the dial threw while the surface was still
    transparent - and the owner kept it, so they are deliberate now. On the car
    they are a `BlurMaskFilter` on the same `Paint`, which is what makes them
    worth designing with rather than admiring in a browser.
    """
    return [
        '<defs>',
        '<filter id="soft" x="-40%" y="-40%" width="180%" height="180%">'
        '<feGaussianBlur stdDeviation="3.2"/></filter>',
        '<filter id="bloom" x="-60%" y="-60%" width="220%" height="220%">'
        '<feGaussianBlur stdDeviation="13"/></filter>',

        # The oldest end of the record fades out rather than being cut off. A
        # trace that stops at a margin claims the drive started there.
        f'<linearGradient id="agegrad" gradientUnits="userSpaceOnUse" '
        f'x1="{f(X0)}" y1="0" x2="{f(X0 + 210)}" y2="0">'
        f'<stop offset="0" stop-color="#000000"/>'
        f'<stop offset="1" stop-color="#ffffff"/></linearGradient>',
        f'<mask id="age"><rect x="0" y="0" width="{f(W)}" height="{f(H)}" '
        f'fill="url(#agegrad)"/></mask>',

        f'<clipPath id="above"><rect x="0" y="{f(CEIL_TOP - 2)}" width="{f(W)}" '
        f'height="{f(UP + 2)}"/></clipPath>',
        f'<clipPath id="below"><rect x="0" y="{f(HORIZON)}" width="{f(W)}" '
        f'height="{f(DOWN)}"/></clipPath>',

        f'<linearGradient id="spend" gradientUnits="userSpaceOnUse" '
        f'x1="0" y1="{f(HORIZON)}" x2="0" y2="{f(CEIL_TOP)}">'
        f'<stop offset="0" stop-color="{ACCENT}" stop-opacity="0.20"/>'
        f'<stop offset="0.45" stop-color="{ACCENT}" stop-opacity="0.07"/>'
        f'<stop offset="1" stop-color="{ACCENT}" stop-opacity="0"/></linearGradient>',
        f'<linearGradient id="give" gradientUnits="userSpaceOnUse" '
        f'x1="0" y1="{f(HORIZON)}" x2="0" y2="{f(CEIL_BOTTOM)}">'
        f'<stop offset="0" stop-color="{RETURN}" stop-opacity="0.34"/>'
        f'<stop offset="0.6" stop-color="{RETURN}" stop-opacity="0.14"/>'
        f'<stop offset="1" stop-color="{RETURN}" stop-opacity="0.03"/></linearGradient>',
        '</defs>',
    ]


def bloom(kw, strength=1.0):
    """The panel's own light, and the whole reason to leave it switched on.

    Magnitude is brightness and direction is hue, spread wide enough over the
    horizon that it is read by the part of the eye that cannot read numerals.
    Nothing here is a number twice: the bloom says *hard, and which way* before
    the figure says *thirty-four*.
    """
    if abs(kw) < FLOOR_KW:
        return []
    tint = RETURN_INK if kw < 0 else ACCENT
    share = min(1.0, abs(kw) / (120.0 if kw >= 0 else 60.0)) ** 0.6
    peak = 0.05 + 0.17 * share * strength
    return [
        '<defs>',
        f'<radialGradient id="lit">'
        f'<stop offset="0" stop-color="{tint}" stop-opacity="{peak:.3f}"/>'
        f'<stop offset="0.45" stop-color="{tint}" stop-opacity="{peak * 0.34:.3f}"/>'
        f'<stop offset="1" stop-color="{tint}" stop-opacity="0"/></radialGradient>',
        '</defs>',
        f'<ellipse cx="{f(CX)}" cy="{f(HORIZON - (34 if kw >= 0 else -14))}" '
        f'rx="{f(W * 0.46)}" ry="{f(126 if kw >= 0 else 74)}" fill="url(#lit)"/>',
        f'<ellipse cx="{f(CX)}" cy="{f(HORIZON)}" rx="{f(W * 0.34)}" ry="{f(21)}" '
        f'fill="url(#lit)"/>',
    ]


def horizon():
    """One hairline, full width, brightest under the driver and gone at the doors.

    It is light rather than a border, so it has no ends: the ramp is what says
    the panel is lit from the middle, and it is the only gradient on the board
    that is not carrying data.
    """
    return [
        '<defs>',
        f'<linearGradient id="hz" gradientUnits="userSpaceOnUse" x1="0" y1="0" '
        f'x2="{f(W)}" y2="0">'
        f'<stop offset="0" stop-color="{INK}" stop-opacity="0.06"/>'
        f'<stop offset="0.5" stop-color="{INK}" stop-opacity="0.58"/>'
        f'<stop offset="1" stop-color="{INK}" stop-opacity="0.06"/></linearGradient>',
        '</defs>',
        f'<rect x="0" y="{f(HORIZON - 0.6)}" width="{f(W)}" height="1.2" fill="url(#hz)"/>',
    ]


# ---------------------------------------------------------------- the recorder

def runs(values, floor=FLOOR_KW):
    """Index ranges where something was happening, each reaching one sample into rest.

    The reach is what brings a stroke down to the line instead of starting it in
    mid-air, and the gaps are what keep a champagne line off the horizon while
    the car is standing still. An exact nought is a reading; it is drawn as the
    line it already is.
    """
    spans, start = [], None
    for i, v in enumerate(values):
        live = v is not None and v > floor
        if live and start is None:
            start = i
        elif not live and start is not None:
            spans.append((max(0, start - 1), i))
            start = None
    if start is not None:
        spans.append((max(0, start - 1), len(values) - 1))
    return spans


class Recorder:
    """The window: two non-negative series on one baseline, plus the range each is on.

    Two series rather than one signed one, because the car does both at once -
    an engine generating 12 kW while the pack still spends 40 is a plinth under
    the line and a hill over it in the same second, and a single signed trace
    has to lie about one of them.
    """

    def __init__(self, spend, give, gen=None):
        self.spend = spend
        self.give = give
        self.gen = gen or [0.0] * len(spend)
        self.ceil_up = rung(max([v for v in spend if v is not None] or [0.0]), LADDER_UP)
        below = [(self.give[i] or 0.0) + (self.gen[i] or 0.0) for i in range(len(spend))]
        self.ceil_down = rung(max(below or [0.0]), LADDER_DOWN)

    @property
    def now(self):
        """What the panel reads: spending, or returning as a magnitude."""
        s, gv = self.spend[-1] or 0.0, self.give[-1] or 0.0
        return s if s > gv else -gv

    def x(self, i):
        return X0 + (X1 - X0) * i / (len(self.spend) - 1.0)

    def up(self, kw):
        return HORIZON - UP * min(1.0, (kw or 0.0) / self.ceil_up)

    def down(self, kw):
        return HORIZON + DOWN * min(1.0, (kw or 0.0) / self.ceil_down)


def band(rec, values, upward, colour, gradient, base=None, stroke=2.2, dot=True):
    """One band of the recorder: an area anchored on the line and a lit boundary.

    `base` stacks this band on top of another one, which is what puts
    regeneration outside the engine's plinth rather than through it: both are
    energy arriving, and the depth of the two together is the honest total.
    """
    y = (rec.up if upward else rec.down)
    base = base or [0.0] * len(values)
    pts = [(rec.x(i), y((values[i] or 0.0) + base[i])) for i in range(len(values))]
    floor = [(rec.x(i), y(base[i])) for i in range(len(values))]

    area = ('M ' + ' L '.join(f'{f(px)} {f(py)}' for px, py in pts) + ' '
            + ' '.join(f'L {f(px)} {f(py)}' for px, py in reversed(floor)) + ' Z')
    out = [f'<path d="{area}" fill="url(#{gradient})" '
           f'clip-path="url(#{"above" if upward else "below"})"/>']

    for a, b in runs(values):
        d = 'M ' + ' L '.join(f'{f(px)} {f(py)}' for px, py in pts[a:b + 1])
        if stroke:
            out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{f(stroke)}" '
                       f'stroke-linejoin="round" stroke-linecap="round" opacity="0.55" '
                       f'filter="url(#soft)"/>')
            out.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{f(stroke)}" '
                       f'stroke-linejoin="round" stroke-linecap="round"/>')
    if dot and (values[-1] or 0.0) > FLOOR_KW:
        px, py = pts[-1]
        out.append(f'<circle cx="{f(px)}" cy="{f(py)}" r="9" fill="{colour}" opacity="0.30" '
                   f'filter="url(#soft)"/>')
        out.append(f'<circle cx="{f(px)}" cy="{f(py)}" r="4.6" fill="{PEAK if upward else colour}"/>')
    return out


DIVISIONS = 6                  # thirty seconds each, across three minutes


def grid():
    """Half-minute divisions, at the edge of visible.

    Without them the record is a painting of a curve; with them it is a curve
    over a duration, and the eye can tell a hill that took ten seconds from one
    that took a minute. They are the faintest marks on the panel by a long way -
    a grid you can read at a glance is a grid competing with its own data.
    """
    return [f'<rect x="{f(X0 + (X1 - X0) * i / DIVISIONS - 0.6)}" y="{f(CEIL_TOP)}" '
            f'width="1.2" height="{f(UP + DOWN)}" fill="{INK}" opacity="0.055"/>'
            for i in range(1, DIVISIONS)]


def recorder(rec):
    """The one instrument, masked so the oldest end of the window fades out."""
    body = grid()
    body += band(rec, rec.spend, True, ACCENT, 'spend')
    # The plinth first, so the boundary that ends up drawn is the total's.
    body += band(rec, rec.give, False, RETURN, 'give', base=rec.gen)
    body += band(rec, rec.gen, False, RETURN, 'give', stroke=1.2, dot=False)
    return [f'<g mask="url(#age)">'] + body + ['</g>']


def cursor():
    """Now, marked once. The record ends here because this is when it is."""
    return [f'<rect x="{f(X1 - 0.6)}" y="{f(CEIL_TOP)}" width="1.2" '
            f'height="{f(UP + DOWN)}" fill="{INK}" opacity="0.17"/>']


def gate(cy, rx, ry):
    """Black over the record, under whatever stands in it.

    Invisible on black and everywhere else on this panel, which is the whole
    reason it is affordable; what it costs is the part of the record it sits on,
    and it only exists for the two middles that put something in the recorder's
    own space.
    """
    return [
        '<defs>',
        f'<radialGradient id="gate{int(rx)}">'
        f'<stop offset="0" stop-color="{BLACK}" stop-opacity="0.97"/>'
        f'<stop offset="0.62" stop-color="{BLACK}" stop-opacity="0.88"/>'
        f'<stop offset="1" stop-color="{BLACK}" stop-opacity="0"/></radialGradient>',
        '</defs>',
        f'<ellipse cx="{f(CX)}" cy="{f(cy)}" rx="{f(rx)}" ry="{f(ry)}" '
        f'fill="url(#gate{int(rx)})"/>',
    ]


def scale(rec):
    """The range the recorder is on, printed. This is the difference between an
    instrument and a decoration.

    Two figures and two ticks, at the old end of the window where the record has
    already faded to nothing, so the scale never sits on data. Both are needed
    and neither can be inferred from the other: the car spends three hundred
    kilowatts and recovers about a hundred, so equal deflection means very
    different figures above and below the line.
    """
    out = []
    for y, kw in ((CEIL_TOP, rec.ceil_up), (CEIL_BOTTOM, rec.ceil_down)):
        out.append(f'<rect x="{f(X0)}" y="{f(y - 0.6)}" width="14" height="1.2" '
                   f'fill="{MUTED_DEEP}" opacity="0.7"/>')
        out.append(g.pair(X0, y + (17 if y < HORIZON else -7),
                          [('sc', f'{kw:.0f}', 0), ('sc', 'КВТ', 5)]))
    out.append(g.pair(X0, HORIZON - 9, [('sc', '3 МИН', 0)]))
    return out


# ---------------------------------------------------------------- the figures

def lit(run, opacity=0.5):
    """The same text once more, blurred underneath itself. Type is light here too."""
    return [run.replace('<text ', f'<text filter="url(#soft)" opacity="{opacity}" ', 1), run]


def hero(reading, kw):
    """The 104-unit numeral, standing on the line - the `hero` middle only.

    It takes the direction's colour rather than a sign. A minus in front of a
    figure this size is a mark the size of a word carrying one bit, and the
    picture it stands in has already said which way the energy is going.
    """
    colour = RETURN_INK if kw < 0 else INK
    return lit(g.pair(CX, HORIZON, [('hero', reading, 0, colour), ('un', 'кВт', 13)],
                      anchor='middle'))


def reading(reading_text, kw, cy=None):
    """The live value at 52 - the top of the ramp the app already draws with.

    The stock cluster is showing the speed in enormous type a hand's width
    above this window, and a second enormous numeral under it reads as two
    instruments stacked rather than one panel. So the recorder is the picture
    and the figure is its readout: the largest rung on the existing ladder,
    which also means this design needs no rung that is not already there.
    """
    colour = RETURN_INK if kw < 0 else INK
    return lit(g.pair(CX, cy or TONGUE_Y,
                      [('fig', reading_text, 0, colour), ('un', 'кВт', 11)], anchor='middle'),
               opacity=0.42)


def tongue(parts):
    """The lower aperture: one line, and never two.

    It is the only place on the panel with depth to spare and the temptation is
    to fill it. What belongs here is the readout - the live value, and the
    number the recorder cannot say, which is the average over the window it is
    drawing - and, when the car has something else to report about itself, that
    sentence instead of the average.
    """
    return [g.pair(CX, TONGUE_Y, parts, anchor='middle')] if parts else []


def divider(x, half=15.0):
    return [f'<rect x="{f(x)}" y="{f(TONGUE_Y - 12 - half)}" width="1.2" '
            f'height="{f(half * 2)}" fill="{INK}" opacity="0.16"/>']


def thermal(v):
    """Top left: the five temperatures nothing else on this car reports.

    No title. `28° батарея` labels itself, and a tracked capital over it would
    be a third type size spent saying what the row already says. The rag follows
    the aperture - the reveal is a quarter ellipse, so every row down has less
    room than the one above, and the block is set to that rather than against it.
    """
    out = [
        g.pair(M, ROW[0], [('rd', v['pack_c'] + '°', 0), ('bd', 'батарея', 7),
                           ('rd', v['inverter_c'] + '°', 26), ('bd', 'инвертор', 7)]),
        g.pair(M, ROW[1], [('rd', v['m1_c'] + '°', 0), ('bd', '·', 7),
                           ('rd', v['m2_c'] + '°', 7), ('bd', '·', 7),
                           ('rd', v['m3_c'] + '°', 7), ('bd', 'моторы', 9)]),
    ]
    return out + health(v)


def health(v):
    """The third row of the left corner, planned and usually empty.

    A driver's display shows exceptions, not an inventory: eight lamps and the
    words `все в норме` are the panel telling you it has nothing to tell you.
    The row is reserved so that the day one of them speaks, nothing above it
    moves.
    """
    kind, names, answered, total = v['fluids']
    if kind == 'ok':
        return []
    if kind == 'silent':
        return [f'<text class="bd" x="{f(M)}" y="{f(ROW[2])}" '
                f'style="fill:{WARNING}">жидкости молчат</text>']
    if kind == 'partial':
        return [f'<text class="bd" x="{f(M)}" y="{f(ROW[2])}" '
                f'style="fill:{WARNING}">жидкости · {answered} из {total}</text>']
    text = names[0] if len(names) == 1 else f'{names[0]} +{len(names) - 1}'
    return [
        f'<circle cx="{f(M + 4.4)}" cy="{f(ROW[2] - 4.4)}" r="4.4" fill="{DANGER}"/>',
        f'<text class="rd" x="{f(M + 17)}" y="{f(ROW[2])}" style="fill:{DANGER}">{text}</text>',
    ]


def pack(v):
    """Top right: the pack, and the engine underneath it when it is doing something.

    Mirrored anatomy, not a mirrored picture - same three baselines, same two
    sizes, hung off the other edge. The engine's row is the same reserved row as
    the left corner's exception: an engine that is off says nothing, and its
    appearance is itself the news.
    """
    out = [
        g.pair(X1, ROW[0], [('rd', v['volts'], 0), ('ul', 'В', 6),
                            ('rd', v['spread'], 24), ('bd', 'мВ разброс', 7)], anchor='end'),
        g.pair(X1, ROW[1], [('rd', v['insulation'], 0), ('bd', 'МОм', 7),
                            ('bd', '·', 9), ('rd', v['health'], 9), ('bd', '%', 5)],
               anchor='end'),
    ]
    if v.get('engine'):
        out.append(g.pair(X1, ROW[2], [('rd', v['rpm'], 0, RETURN_INK), ('bd', 'об/мин', 7)]
                          + ([('rd', v['gen_kw'], 20, RETURN_INK), ('bd', 'кВт заряд', 7)]
                             if v.get('gen_kw') else []),
                          anchor='end'))
    return out


# ---------------------------------------------------------------- the middle, twice

TICKS = 37


def dial(rec):
    """The one decision the pair of boards is asking about.

    A ring of fine marks lit to the value reads as an instrument and gives the
    black a texture that type alone cannot. What it costs is the interior: the
    crown already rests on the recorder's ceiling and the ceiling is ten units
    under the vehicle's graphics, so the radius is fixed at 112 and a
    three-digit reading does not fit between the arms. It is drawn here at the
    same 34 kW as its sibling so the choice is the only thing being asked.
    """
    kw = rec.now
    colour = RETURN_INK if kw < 0 else ACCENT
    if kw >= 0:
        deg = 90.0 - 90.0 * min(1.0, kw / rec.ceil_up)
    else:
        deg = 90.0 + 90.0 * min(1.0, -kw / rec.ceil_down)
    out, lit = [], []
    for i in range(TICKS):
        a = 180.0 - 180.0 * i / (TICKS - 1)
        on = (90.0 >= a >= deg) if kw >= 0 else (90.0 <= a <= deg)
        top = abs(a - 90.0) < 0.5
        x0, y0 = g.polar(CX, HORIZON, DIAL_R - (17 if on else (13 if top else 9)), a)
        x1, y1 = g.polar(CX, HORIZON, DIAL_R, a)
        mark = (f'<path d="M{f(x0)} {f(y0)} L{f(x1)} {f(y1)}" '
                f'stroke="{colour if on else TRACK}" '
                f'stroke-width="{3.4 if on else 1.8}" stroke-linecap="round"/>')
        (lit if on else out).append(mark)
    if lit:
        out.append(f'<g filter="url(#bloom)" opacity="0.75">{"".join(lit)}</g>')
        out += lit
    return out


# ---------------------------------------------------------------- the apertures

def keepout(labels=True):
    """What the vehicle draws over us, hatched. Not on the two hero boards.

    A driver never sees hatching, so a board meant to answer "what does this
    look like" must not show any. A board answering "where may it go" must.
    """
    out = [
        '<defs>',
        '<pattern id="hatch" width="10" height="10" patternUnits="userSpaceOnUse" '
        'patternTransform="rotate(45)">'
        '<line x1="0" y1="0" x2="0" y2="10" stroke="rgba(218,225,235,0.075)" '
        'stroke-width="1.4"/></pattern>',
        f'<mask id="topmask"><rect x="0" y="0" width="{f(W)}" height="{f(A.stock_top)}" '
        f'fill="#fff"/>'
        f'<ellipse cx="0" cy="0" rx="{f(A.tl_rx)}" ry="{f(A.reveal_ry)}" fill="#000"/>'
        f'<ellipse cx="{f(W)}" cy="0" rx="{f(A.tr_rx)}" ry="{f(A.reveal_ry)}" '
        f'fill="#000"/></mask>',
        f'<mask id="botmask"><rect x="0" y="{f(A.stock_bottom)}" width="{f(W)}" '
        f'height="{f(H - A.stock_bottom)}" fill="#fff"/>'
        f'<ellipse cx="{f(CX)}" cy="{f(A.lobe_cy)}" rx="{f(A.lobe_rx)}" '
        f'ry="{f(A.lobe_ry)}" fill="#000"/></mask>',
        '</defs>',
        f'<rect x="0" y="0" width="{f(W)}" height="{f(A.stock_top)}" fill="url(#hatch)" '
        f'mask="url(#topmask)"/>',
        f'<rect x="0" y="{f(A.stock_bottom)}" width="{f(W)}" '
        f'height="{f(H - A.stock_bottom)}" fill="url(#hatch)" mask="url(#botmask)"/>',
    ]
    if labels:
        out += [
            f'<text class="keep" x="{f(CX)}" y="28" text-anchor="middle">ШТАТНЫЕ ПРИБОРЫ</text>',
            f'<text class="keep" x="{f(M)}" y="{f((A.stock_bottom + H) / 2 + 4)}">'
            f'ШТАТНАЯ ПОЛОСА</text>',
        ]
    return out


def plan(rec):
    """The skeleton, with every number that made it on the picture.

    Two records of one decision is how this directory got sixty defects the
    first time. This board is the second record: the apertures as the Kotlin
    computes them, the three baselines, the two ranges and the one line.
    """
    dash = 'stroke-dasharray="6 5"'
    out = keepout() + [
        # the apertures, outlined
        f'<path d="M{f(A.tl_rx)} 0 A{f(A.tl_rx)} {f(A.reveal_ry)} 0 0 1 0 {f(A.reveal_ry)}" '
        f'fill="none" stroke="{RETURN}" stroke-width="1.2" opacity="0.55" {dash}/>',
        f'<path d="M{f(W - A.tr_rx)} 0 A{f(A.tr_rx)} {f(A.reveal_ry)} 0 0 0 {f(W)} '
        f'{f(A.reveal_ry)}" fill="none" stroke="{RETURN}" stroke-width="1.2" '
        f'opacity="0.55" {dash}/>',
        f'<ellipse cx="{f(CX)}" cy="{f(A.lobe_cy)}" rx="{f(A.lobe_rx)}" ry="{f(A.lobe_ry)}" '
        f'fill="none" stroke="{RETURN}" stroke-width="1.2" opacity="0.55" {dash}/>',
        f'<line x1="0" y1="{f(A.stock_top)}" x2="{f(W)}" y2="{f(A.stock_top)}" '
        f'stroke="{RETURN}" stroke-width="1.2" opacity="0.55" {dash}/>',
        f'<line x1="0" y1="{f(A.stock_bottom)}" x2="{f(W)}" y2="{f(A.stock_bottom)}" '
        f'stroke="{RETURN}" stroke-width="1.2" opacity="0.55" {dash}/>',
    ]
    out += horizon()
    # the recorder's own box
    out += [
        f'<rect x="{f(X0)}" y="{f(CEIL_TOP)}" width="{f(X1 - X0)}" height="{f(UP + DOWN)}" '
        f'fill="none" stroke="{INK}" stroke-width="1.2" opacity="0.16"/>',
        f'<rect x="{f(CX - GATE_RX)}" y="{f(GATE_CY - GATE_RY)}" width="{f(GATE_RX * 2)}" '
        f'height="{f(GATE_RY * 2)}" rx="22" fill="none" stroke="{INK}" stroke-width="1.2" '
        f'opacity="0.14"/>',
    ]
    for y in ROW:
        out.append(f'<line x1="0" y1="{f(y)}" x2="{f(A.top_half_width(y))}" y2="{f(y)}" '
                   f'stroke="{INK}" stroke-width="1.2" opacity="0.13"/>')
        out.append(f'<line x1="{f(W - A.top_half_width(y, right=True))}" y1="{f(y)}" '
                   f'x2="{f(W)}" y2="{f(y)}" stroke="{INK}" stroke-width="1.2" opacity="0.13"/>')
        out.append(f'<text class="sc" x="{f(A.top_half_width(y) + 10)}" y="{f(y + 4)}">'
                   f'{f(y)} · {f(A.top_half_width(y))}</text>')
    out.append(f'<line x1="{f(CX - A.lobe_half_width(TONGUE_Y))}" y1="{f(TONGUE_Y)}" '
               f'x2="{f(CX + A.lobe_half_width(TONGUE_Y))}" y2="{f(TONGUE_Y)}" '
               f'stroke="{INK}" stroke-width="1.2" opacity="0.13"/>')

    notes = [
        (X0 + 18, CEIL_TOP + 17, f'ВЕРХ {f(UP)} · {rec.ceil_up:.0f} КВТ'),
        (X0 + 18, HORIZON - 9, f'ГОРИЗОНТ {f(HORIZON)}'),
        (X0 + 18, CEIL_BOTTOM - 7, f'НИЗ {f(DOWN)} · {rec.ceil_down:.0f} КВТ'),
        (CX - GATE_RX, GATE_CY - GATE_RY - 8, f'ГЕЙТ {f(GATE_RX * 2)}x{f(GATE_RY * 2)}'),
        (CX - 140, TONGUE_Y + 22, f'НИЖНЕЕ ОКНО {f(TONGUE_Y)} · '
                                  f'{f(A.lobe_half_width(TONGUE_Y) * 2)}'),
        (X1 - 210, CEIL_TOP - 12, f'СЕЙЧАС {f(X1)} · ШАГ {f((X1 - X0) / 179)}'),
        (M, A.stock_top - 14, f'ОКНО {f(A.stock_top)} … {f(A.stock_bottom)}'),
    ]
    for x, y, text in notes:
        out.append(f'<text class="sc" x="{f(x)}" y="{f(y)}">{text}</text>')
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
    body { margin:0; background:#000; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    a { color:#FEEFAB; } a:hover { color:#FFF7D2; }
    text { font-feature-settings:'tnum' 1; font-variant-numeric:tabular-nums; }
    .hero { font-weight:200; font-size:104px; fill:%(ink)s; letter-spacing:-0.025em; }
    .fig  { font-weight:200; font-size:34px;  fill:%(ink)s; }
    .un   { font-weight:300; font-size:24px;  fill:%(muted)s; }
    .rd   { font-weight:300; font-size:18px;  fill:%(ink)s; }
    .ul   { font-weight:400; font-size:13px;  fill:%(muted)s; }
    .bd   { font-weight:400; font-size:13px;  fill:%(deep)s; }
    .sc   { font-weight:500; font-size:11px;  fill:%(deep)s; letter-spacing:0.15em; }
    .keep { font-weight:400; font-size:11px;  fill:%(ghost)s; letter-spacing:0.15em; }
  </style>
</helmet>
"""


def page(body, width=W, height=H):
    css = HEAD % dict(ink=INK, muted=MUTED, deep=MUTED_DEEP, ghost=GHOST)
    w, h = f(width), f(height)
    return (css +
            f'<div style="width:{w}px; height:{h}px; background:{BLACK}; position:relative;">\n'
            f'  <svg width="{w}" height="{h}" viewBox="0 0 {w} {h}">\n    ' +
            '\n    '.join(body) +
            f'\n  </svg>\n</div>\n</x-dc>\n'
            f'<script data-dc-script data-props=\'{{"$preview":{{"width":{w},"height":{h}}}}}\'>\n'
            'class Component extends DCLogic { renderVals() { return {}; } }\n'
            '</script>\n</body>\n</html>\n')


def tongue_parts(v, middle, kw):
    """What the lower aperture says, which depends on what the middle already said.

    The average is the window's own number and belongs to the recorder, so it
    stays wherever the live value is not. A car with something to report about
    itself - standing still, on a charge - takes the average's place rather than
    adding a line, because two lines here is where a readout turns into a page.
    """
    colour = RETURN_INK if kw < 0 else INK
    if v.get('caption'):
        tail = [('bd', v['caption'], 22, v.get('avg_tint'))]
    elif v.get('avg'):
        tail = [('bd', '·', 22), ('rd', v['avg'], 22), ('bd', v['avg_unit'], 8)]
    else:
        tail = []
    if middle == 'none':
        return [('fig', v['kw'], 0, colour), ('un', 'кВт', 11)] + tail
    if not tail:
        return []
    if v.get('caption'):
        return [('rd', v['caption'], 0, v.get('avg_tint') or MUTED)]
    return [('fig', v['avg'], 0), ('ul', v['avg_unit'], 12)]


def panel(rec, v, middle='none', ghost=False):
    """One whole cluster, in one state. Every board is a list of these.

    Draw order is the design: the light first, the record over it, the line over
    that, and type last. The gate goes between the record and the line so the
    horizon stays crisp through it - the one thing on the panel that is never
    dimmed by anything is the line everything else is measured from.
    """
    out = list(defs())
    out += bloom(rec.now)
    out += recorder(rec)
    out += cursor()
    if middle == 'hero':
        out += gate(GATE_CY, GATE_RX, GATE_RY)
    elif middle == 'dial':
        out += gate(HORIZON - 30, 128.0, 86.0)
    out += horizon()
    out += scale(rec)
    if middle == 'dial':
        out += dial(rec)
        out += reading(v['kw'], rec.now, cy=HORIZON - 16)
    elif middle == 'hero':
        out += hero(v['kw'], rec.now)
    out += tongue(tongue_parts(v, middle, rec.now))
    out += thermal(v)
    out += pack(v)
    if ghost:
        out += keepout()
    return out


# ---------------------------------------------------------------- the data

def drive(n=180):
    """Three minutes of city traffic, composed rather than sampled.

    A mock-up of a recorder is a landscape and it has to be drawn like one: a
    cruise, a stop, a hard pull, two lift-offs shaped like the braking that
    causes them, and a last climb that ends on the number the panel reads. Noise
    is in it because the real signal has noise; it is 1 Hz on the car, and the
    renderer means over three samples before it draws.
    """
    keys = [(0, 24), (14, 27), (20, 6), (26, -28), (33, -6), (38, 0), (46, 0),
            (52, 74), (58, 112), (66, 58), (74, 41), (92, 44), (100, 12),
            (106, -34), (114, -9), (120, 46), (130, 78), (138, 52), (146, -16),
            (152, -44), (160, -8), (166, 52), (174, 40), (179, 34)]
    out = []
    for i in range(n):
        t = i * (keys[-1][0] / (n - 1.0))
        for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
            if t0 <= t <= t1:
                u = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
                u = u * u * (3 - 2 * u)                    # smoothstep, so a pull is a pull
                v = v0 + (v1 - v0) * u
                break
        else:
            v = keys[-1][1]
        v += 1.6 * math.sin(i * 0.7) + 0.9 * math.sin(i * 1.9)
        out.append(round(v, 2))
    out[-1] = 34.0
    return out


def split(signed):
    """One signed run into the two the recorder draws."""
    return ([max(0.0, v) for v in signed], [max(0.0, -v) for v in signed])


def generation(n=180, start=104, level=12.4):
    out = []
    for i in range(n):
        if i < start:
            out.append(0.0)
            continue
        ramp = min(1.0, (i - start) / 9.0)
        out.append(round(max(0.0, level * ramp + 0.7 * math.sin(i * 0.5)), 2))
    return out


BASE = dict(
    pack_c='28', inverter_c='32', m1_c='31', m2_c='29', m3_c='31',
    volts='552', spread='4', insulation='13,1', health='99',
    rpm='1321', gen_kw='11', engine=False,
    fluids=('ok', [], 8, 8),
    avg='16,8', avg_unit='кВт·ч/100 км', caption=None, avg_tint=None,
)


def state(**over):
    v = dict(BASE)
    v.update(over)
    return v


FLUIDS = ['давление масла', 'уровень ОЖ', 'масло КПП']


def build():
    signed = drive()
    spend, give = split(signed)

    driving = Recorder(spend, give)
    v = state(kw='34')

    quiet = Recorder([0.0] * 180, [0.0] * 180)
    give_now = list(signed)
    for i in range(len(give_now) - 16, len(give_now)):
        u = (i - (len(give_now) - 17)) / 16.0
        give_now[i] = -(8.0 + 38.0 * u)
    rs, rg = split(give_now)

    gen = generation()
    charge = [0.0] * 180
    for i in range(60, 180):
        charge[i] = round(min(6.9, 0.9 + 0.11 * (i - 60)) + 0.05 * math.sin(i * 0.6), 2)

    boards = [
        ('ClusterHorizon', (driving, v, 'none'), W, H),
        ('ClusterHorizonHero', (driving, v, 'hero'), W, H),
        ('ClusterHorizonDial', (driving, v, 'dial'), W, H),
        ('ClusterHorizonPlan', None, W, H),
    ]
    for name, row, width, height in boards:
        if row is None:
            body = defs() + plan(driving)
        else:
            rec, data, middle = row
            body = panel(rec, data, middle)
        open(os.path.join(HERE, name + '.dc.html'), 'w').write(page(body, width, height))

    states = [
        ('ПОКОЙ · ДВИГАТЕЛЬ ЗАГЛУШЕН · ЖУРНАЛ ПУСТ',
         quiet, state(kw='0', avg=None, caption='стоим')),
        ('ВОЗВРАТ · ТОРМОЖЕНИЕ ДВИГАТЕЛЕМ',
         Recorder(rs, rg), state(kw='46', avg='9,4')),
        ('ГЕНЕРАЦИЯ · ДВС ЗАРЯЖАЕТ ПАКЕТ',
         Recorder(spend, give, gen), state(kw='34', engine=True)),
        ('ЗАРЯД · ПИСТОЛЕТ В РАЗЪЁМЕ',
         Recorder([0.0] * 180, [0.0] * 180, charge),
         state(kw='7', avg=None, caption='заряжается · осталось 2 ч 15 мин',
               avg_tint=RETURN_INK)),
        ('НЕИСПРАВНОСТЬ · ДАВЛЕНИЕ МАСЛА И ПЕРЕГРЕВ ИНВЕРТОРА',
         driving, state(kw='34', fluids=('alert', FLUIDS, 8, 8), inverter_c='96')),
    ]
    gap, pad = 60.0, 34.0
    body, y = [], pad
    for label, rec, data in states:
        body.append(f'<text class="sc" x="{f(M)}" y="{f(y - 14)}">{label}</text>')
        body.append(f'<g transform="translate(0,{f(y)})">')
        body += panel(rec, data, 'none', ghost=True)
        body.append('</g>')
        y += H + gap
    open(os.path.join(HERE, 'ClusterHorizonStates.dc.html'), 'w').write(
        page(body, W, y - gap + pad))

    print('ClusterHorizon, ClusterHorizonDial, ClusterHorizonPlan, ClusterHorizonStates')
    print(f'  aperture: band {A.stock_top:.1f} … {A.stock_bottom:.1f}  '
          f'corners {A.tl_rx:.1f} / {A.tr_rx:.1f} x {A.reveal_ry:.1f}  '
          f'lobe {A.lobe_rx:.1f} x {A.lobe_ry:.1f} @ {A.lobe_cy:.1f}')
    print(f'  recorder: {f(X0)} … {f(X1)}  up {f(UP)} to {driving.ceil_up:.0f} kW  '
          f'down {f(DOWN)} to {driving.ceil_down:.0f} kW  step {(X1 - X0) / 179:.2f}')
    for y in ROW:
        print(f'  row {y:.0f}: left {A.top_half_width(y):.1f}  '
              f'right {A.top_half_width(y, right=True):.1f}')
    print(f'  tongue at {TONGUE_Y:.0f}: {A.lobe_half_width(TONGUE_Y) * 2:.1f} wide')


if __name__ == '__main__':
    build()
