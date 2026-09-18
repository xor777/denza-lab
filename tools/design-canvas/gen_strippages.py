#!/usr/bin/env python3
"""Emits `StripPages.dc.html` - the strip's two pages, the swipe between them, and what the
second page looks like in the scenes this car has actually been read in.

The field on the left of the strip is two things and the dots under it say so: the analyser, and
the car's own readings. A horizontal swipe moves between them, the dots move with it, and nothing
else on the screen changes - the three trip figures on the right are not a page and never move.

Two pages, not a pager. `BottomPanelPager` was deleted on 2026-08-27 because it had four hidden
pages and no way to know they were there; what comes back is one gesture, two pages, and an
indicator that is on the screen whether or not anybody swipes.

## Every figure on the car's page, and where it comes from

The first cut of this board printed **371 В** and **−22 А**. Both came off the photograph of
another car's dashboard rather than out of `docs/vehicle-data-findings.md`, and neither is true
here:

  - this pack is 166 LFP cells and reads **550-557 V** - flat across the charge window, which is
    the whole reason the cluster shows pack voltage as context rather than as a gauge;
  - the current cell is gone. The one id that decoded as amps, `0x44400018`, was read **once**,
    parked on an AC charge, at −4.4 A, is named *charge* current, and its sign is not proven;
    `STATISTIC_INSTANTANEOUS_CURRENT` is on that page's own "do not label in a UI until a moving
    capture" list. And on a pack whose voltage barely moves, amps are kilowatts drawn twice: the
    cell was spending the ramp's top rung on a rescaled neighbour.

What stands in its place is `ЗАРЯД`, which is proven (`0x4A505038`, read 43 %), and which is one
of the four things the owner asked the cluster contest for - voltages, battery, revolutions,
consumption. The current can come back the day the moving capture exists.

Each scene below carries the session it was read in, and the one scene that has never been
captured says so on the board itself.
"""

import re
import sys

import gen_panes as gp

MAIN = 'Main.dc.html'
OUT = 'StripPages.dc.html'

STRIP_H = 296                 # TripPanelRenderer.WIDE_VIRTUAL_H
FIELD_W = 832                 # SPECTRUM_RIGHT: what the field has at 1280
CONTENT = 1184                # WIDE_VIRTUAL_W
GROUP = gp.GROUP              # 32
GAP = gp.GAP                  # 12
LEAD = gp.LEAD                # 8
PAGE_TOP = gp.PAGE_TOP        # 20
WINDOW_H = gp.WINDOW_H        # 680
RULE_GAP = 12                 # Space.M either side of a hairline, as `.ruled` spends it
GUTTER = 80                   # the canvas gutter between two frames
PLATE_NAME = 24

# The dots, and the only thing the feature charges the analyser.
#
# 20 dp off the foot of the field - the two dots and the air around them - and the analyser is
# laid out in what is left. Nothing is clipped: `SpectrumRenderer` puts its ticker, bars and
# reflection at fractions of the box it is handed, so a shorter box is the same analyser at a
# smaller size. 203 dp of bars become 186.
#
# At the foot rather than the top because that is where a page indicator belongs, and because the
# top of the field already carries the ticker. Both pages spend the same 20, so the dots do not
# move when the page does.
DOTS = 20
DOT = 8

INK = '#DAE1EB'
MUTED = '#86909B'
MUTED_DEEP = '#7C858F'
ACCENT = '#FEEFAB'
PEAK_INK = '#FFF8DA'
RETURN = '#2D82D7'
RETURN_INK = '#4B9BE0'
WARNING = '#FF9F19'
DANGER = '#FF4046'
TRACK = '#22262E'
TRACK_MARK = '#3F434D'
GROUND = '#07080A'


def dots(active):
    """Two dots under the field: which page this is, and that there is another one."""
    marks = ''.join(
        f'<div style="width:{DOT}px; height:{DOT}px; border-radius:{DOT // 2}px; '
        f'background:{ACCENT if i == active else "rgba(134,144,155,0.45)"};"></div>'
        for i in range(2))
    return (f'      <div style="height:{DOTS}px; flex-shrink:0; display:flex; '
            f'align-items:center; justify-content:center; gap:{LEAD}px;">{marks}</div>')


def sound_page(width=FIELD_W, height=STRIP_H):
    """Page one: the analyser, exactly as the car draws it today, in a box 20 shorter."""

    class Box:
        name = 'StripPages'
        content = width
        analyser_h = height - DOTS
        title = 'M83 · MIDNIGHT CITY'

    return gp.analyser(Box)


# ------------------------------------------------------------------- the marks the row is named by

# The five the cluster's temperature row uses, restated at the head unit's own glyph size. Not
# words, and that is the owner's own verdict rather than a preference of this board: naming the
# three motor positions in Russian was tried on the cluster and thrown out on the sound of it. A
# car seen from above with one block lit says which motor in no language. `ContourGlyphs` draws
# these in the app; the strip calls that class rather than carrying a second copy.
WHEELS = ''.join(f'<rect x="{x}" y="{y}" width="3" height="5" rx="1.5"></rect>'
                 for x in (4, 17) for y in (4.5, 14.5))


def car(part):
    """The body, four hollow wheels, and one filled block on the axle being asked about."""
    return ('<rect x="7" y="2.5" width="10" height="19" rx="3"></rect>' + WHEELS
            + '<g stroke="none" fill="{component}">' + part + '</g>')


HEAT_GLYPHS = {
    'pack': ('<rect x="2" y="7" width="16" height="10" rx="2"></rect>'
             '<g stroke="none" fill="{outline}">'
             '<rect x="19" y="10" width="2.5" height="4" rx="1"></rect></g>'
             '<g stroke="none" fill="{component}">'
             '<rect x="4" y="9" width="7" height="6" rx="1"></rect></g>'),
    'front': car('<rect x="8.5" y="5" width="7" height="4.2" rx="0.8"></rect>'),
    'rear_l': car('<rect x="8.5" y="14.8" width="3.4" height="4.4" rx="0.8"></rect>'),
    'rear_r': car('<rect x="12.1" y="14.8" width="3.4" height="4.4" rx="0.8"></rect>'),
    'inverter': ('<rect x="2" y="6" width="20" height="12" rx="2"></rect>'
                 '<path stroke="{component}" d="M5.5 12q2.2-4.5 4.4 0t4.4 0t4.4 0"></path>'),
}

# Where a reading stops being ordinary, and it is not this board's opinion: these are
# `ContourReadout`'s own constants, the ones the cluster has been drawing since the panel shipped.
# The first cut of this page invented 45/120/100 out of nothing, which would have put the head
# unit and the driver's display on two different ideas of "hot" in the same car.
#
#   PACK_BAND_HIGH_C  40   DRIVE_BAND_HIGH_C  70   INVERTER_WATCH_C  70   HOT_MARGIN_C  15
#
# Past the band is WATCH, past the band plus the margin is ALERT, and the track shows both: the
# window runs to band + twice the margin, so the two zones are the last two fifths of it and an
# ordinary reading sits in the clear part with room to spare.
HEAT_BAND = {'pack': 40.0, 'front': 70.0, 'rear_l': 70.0, 'rear_r': 70.0, 'inverter': 70.0}
HOT_MARGIN = 15.0

# The cell spread is a row of the same kind - «они же шкала, которая показывает цветовую
# кодировку… должна двигаться туда-сюда и уходить в оранжевую зону» - with `ContourReadout`'s own
# thresholds and a window built the way a temperature's is: the alert, and as much again past it.
SPREAD_WATCH = 25.0
SPREAD_ALERT = 40.0


def heat_level(kind, value):
    """0 ordinary, 1 worth watching, 2 worth stopping for - `ContourReadout.thermalState`."""
    if value is None:
        return 0
    band = HEAT_BAND[kind]
    return 2 if value > band + HOT_MARGIN else (1 if value > band else 0)


# The Contour's family has no mark for the cell spread - the cluster names it with the one word
# left in its row, because it has no room for anything else. This screen has room for a mark and no
# room for a word in that column, so it draws one in the family's idiom: two cases in the caption's
# ink, and the part that means something in the data's.
CELLS_GLYPH = (
    '<rect x="2.5" y="4.5" width="8" height="15" rx="2"></rect>'
    '<rect x="13.5" y="4.5" width="8" height="15" rx="2"></rect>'
    '<g stroke="none" fill="{component}">'
    '<rect x="4.9" y="12.6" width="3.2" height="4.5" rx="1"></rect>'
    '<rect x="15.9" y="7.6" width="3.2" height="9.5" rx="1"></rect></g>'
)


def spread_glyph(level=0):
    """Two cells at two levels, which is what the reading is."""
    component = (INK, WARNING, DANGER)[level]
    # A square viewBox, like every other mark here: 29 wide against 24 tall drew the two cells
    # stretched, which is the defect the owner spotted in the family's own marks a pass ago.
    return (f'<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="{MUTED}" '
            f'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">'
            f'{CELLS_GLYPH.replace("{component}", component)}</svg>')


def heat_glyph(kind, level=0):
    """One sensor's mark: the case in the caption's ink, the part that reads in the data's."""
    component = (INK, WARNING, DANGER)[level]
    body = HEAT_GLYPHS[kind].replace('{outline}', MUTED).replace('{component}', component)
    return (f'<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="{MUTED}" '
            f'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">{body}</svg>')


def spread_row(millivolts, narrow=False):
    """The sixth row: the same anatomy, the pack's own two thresholds."""
    level = 2 if millivolts > SPREAD_ALERT else (1 if millivolts > SPREAD_WATCH else 0)
    top = SPREAD_ALERT
    colour = (INK, WARNING, DANGER)[level]
    pct = round(min(1.0, millivolts / top) * 100, 1)
    watch = round(SPREAD_WATCH / top * 100, 1)
    fill = ('rgba(218,225,235,0.55)', WARNING, DANGER)[level]
    track = (f'<div class="track">'
             f'<div style="position:absolute; left:{watch:g}%; top:0; bottom:0; right:0; '
             f'border-radius:0 3px 3px 0; background:rgba(255,159,25,0.30);"></div>'
             f'<div style="position:absolute; left:0; top:0; bottom:0; width:{pct:g}%; '
             f'border-radius:3px; background:{fill};"></div></div>')
    return (f'          <div class="temp">{spread_glyph(level)}'
            f'<div class="val" style="color:{colour};">{millivolts:g}'
            f'<span class="un19"> мВ</span></div>{track}</div>')


def temp_row(kind, value, narrow=False):
    """One sensor: the mark, the figure, and a track that says where ordinary stops.

    The first cut drew a plain bar with a one-pixel tick on it, and the owner's verdict was that it
    is «просто какая-то полосочка»: a fill against a range nobody can see is not a scale, it is
    decoration. So the track carries the *zones* - amber from the band, red past the band and its
    margin - and what a glance gets is "the bad part is that end, and I am nowhere near it",
    with no legend and without knowing a single number by heart.

    A zone is the colour at 30 %, the state is the colour itself, and the state takes the fill, the
    figure and the glyph's own component together: the Contour's rule, so a hot cell lights as one
    object rather than as a red number beside a grey picture.
    """
    band = HEAT_BAND[kind]
    top = band + HOT_MARGIN
    level = heat_level(kind, value)
    figure = f'{value}°' if value is not None else '—'
    colour = (INK, WARNING, DANGER)[level] if value is not None else MUTED
    pct = 0 if value is None else round(max(0.0, min(1.0, value / top)) * 100, 1)
    watch = round(band / top * 100, 1)
    fill = (('rgba(218,225,235,0.55)', WARNING, DANGER)[level] if value is not None
            else 'rgba(218,225,235,0.55)')
    # A track with no reading on it draws no zones either: the zones are what *this* reading is
    # being judged against, and painting them over a dash judges nothing.
    zones = ('' if value is None else
             f'<div style="position:absolute; left:{watch:g}%; top:0; bottom:0; right:0; '
             f'border-radius:0 3px 3px 0; background:rgba(255,159,25,0.30);"></div>'
             f'<div style="position:absolute; left:0; top:0; bottom:0; width:{pct:g}%; '
             f'border-radius:3px; background:{fill};"></div>')
    track = f'<div class="track">{zones}</div>'
    if narrow:
        return (f'<div class="temp-col stack">'
                f'<div class="temp stack">{heat_glyph(kind, level)}'
                f'<div class="val" style="color:{colour};">{figure}</div></div>{track}</div>')
    return (f'          <div class="temp">{heat_glyph(kind, level)}'
            f'<div class="val" style="color:{colour};">{figure}</div>{track}</div>')


# ----------------------------------------------------------------------------------- the scenes

# Ten kilometres of the pack's consumption, in twenty steps of 500 m, positive out of the pack.
#
# The same chart the cluster's petal draws, in the same bins and on the same scale - the energy
# display contract (docs/energy-display-contract.md, §2.3): one history of one quantity on both
# screens, and the pixel height is the only thing that differs. It replaced two minutes of pack
# power, which was a second history of a quantity the headline already shows and the reason the two
# screens' graphs could not be the same graph.
#
# The owner's own road, read off the car on 2026-09-18: the hundred trailing kilometres of ten
# kilometres of the journal, in kW·h/100 km, oldest first, the same road `gen_contour.py` draws. A
# town run with a descent that gave energy back, a launch past the ceiling, and a second descent at
# the end. A scene names the average it wants and `history` scales the road to it, so the shape and
# the figure beside it cannot disagree.
CONSUMPTION_SHAPE = [30.6, 33.2, 31.0, 25.0, 25.2, 28.1, 26.4, 27.5, 30.0, 26.5,
                     20.0, 15.1, 12.9, 18.3, 21.0, 15.8, 14.1, 11.1, 15.0, 15.8,
                     17.1, 20.1, 23.3, 19.2, 14.0, 15.6, 17.1, 19.2, 14.4, 11.0,
                     13.2, 12.1, 6.3, 13.4, 14.9, 14.7, 14.1, 13.1, 17.9, 19.2,
                     14.7, 16.3, 23.6, 19.8, 19.7, 23.7, 27.2, 26.4, 28.2, 22.9,
                     27.0, 27.1, 24.6, 26.3, 27.6, 20.9, 12.7, 11.6, 2.4, 1.7,
                     -7.8, -5.4, -6.9, -12.3, -18.4, -16.9, -5.8, -1.1, -1.1, 31.6,
                     50.9, 46.3, 44.3, 46.6, 51.1, 59.1, 58.1, 55.6, 63.5, 37.7,
                     26.0, 24.7, 26.6, 26.6, 26.8, 20.7, 16.3, 13.7, 11.5, 15.0,
                     16.3, 17.5, 17.9, 19.0, 15.0, 8.2, -5.8, -5.8, -10.3, -11.9]
SHAPE_MEAN = sum(CONSUMPTION_SHAPE) / len(CONSUMPTION_SHAPE)


def history(average, launch=False, hole=None):
    """A hundred trailing kilometres whose mean is [average]; a launch past the ceiling; a hole."""
    points = [round(average * m / SHAPE_MEAN, 1) for m in CONSUMPTION_SHAPE]
    if launch:
        # A kilometre of full throttle: past the ceiling, drawn along it with one tick at the
        # centre of the run - one cut, one mark, however long the run is.
        for i in range(len(points) - 16, len(points) - 6):
            points[i] = round(CHART_FULL * 1.2, 1)
    if hole is not None:
        for i in hole:
            points[i] = None
    return points


CHART_H = 130
CHART_H_NARROW = 60           # what is left at 392 once the head, the foot and the marks are in
CHART_POINTS = 100            # 100 m each: ConsumptionWindow.KM / ConsumptionChart.PITCH_KM
CHART_SMOOTH = 10             # and every point is the mean of the kilometre ending at it
CHART_EDGE = 2
CHART_AXIS = 44               # the gutter on the right where the two ceilings stand
CHART_AXIS_BASELINE = 13
# Linear, clamped, marked: ContourPlan.PETAL_FULL and PETAL_RETURN_FULL, the cluster's own ladder.
CHART_FULL = 40
CHART_RETURN_FULL = 20
CHART_TICK = 3


_CLIPS = [0]


def clip_id(prefix):
    """A document-unique id: this board carries six frames and each clips its own shape."""
    _CLIPS[0] += 1
    return f'{prefix}{_CLIPS[0]}'


def chart_svg(box_w, points, height=CHART_H):
    """The last ten kilometres as one line, the one shape on this page, and it carries a sign.

    A point stands on every hundred metres of the odometer's grid and is the mean of the kilometre
    ending at it, so the shape is a continuous function of the road and a line is what says that.
    Above the zero is what that kilometre cost, below it is what it gave back - the app's own two
    inks for those and never a third - and the one silhouette crosses the zero wherever a kilometre
    gave back more than it took. The newest point is at the right edge, where new road arrives. A
    point whose kilometre is mostly unknown is a hole: the line breaks, and the road under it keeps
    its place on the axis. A run held along a ceiling is drawn along it with one tick at the run's
    centre.

    **The box says what it holds** in two figures against the edges they belong to, which are the
    same two ceilings the cluster's petal clamps at.
    """
    zero = round(height * CHART_FULL / (CHART_FULL + CHART_RETURN_FULL), 2)
    plot = box_w - CHART_AXIS
    pitch = plot / CHART_POINTS
    n = len(points)

    def x(i):
        return round(plot - (n - 1 - i) * pitch, 2)

    def y(v):
        if v >= 0:
            return round(zero - min(v / CHART_FULL, 1.0) * zero, 2)
        return round(zero + min(-v / CHART_RETURN_FULL, 1.0) * (height - zero), 2)

    def stretches(keep):
        out, i = [], 0
        while i < n:
            if not keep(points[i]):
                i += 1
                continue
            start = i
            while i < n and keep(points[i]):
                i += 1
            out.append((start, i))
        return out

    above, below = clip_id('up'), clip_id('down')
    shapes, ticks = [], []
    for start, stop in stretches(lambda v: v is not None):
        ys = [y(v) for v in points[start:stop]]
        if stop - start == 1:
            # One reading between two holes: a polyline of one point is nothing at all.
            colour = INK if points[start] >= 0 else RETURN_INK
            shapes.append(f'<circle cx="{x(start):g}" cy="{ys[0]:g}" '
                          f'r="{CHART_EDGE / 2:g}" fill="{colour}"></circle>')
            continue
        outline = 'M' + ' L'.join(f'{x(i):g} {ys[i - start]:g}' for i in range(start, stop))
        field = f'{outline} L{x(stop - 1):g} {zero:g} L{x(start):g} {zero:g} Z'
        shapes.append(
            f'<g clip-path="url(#{above})">'
            f'<path d="{field}" fill="rgba(218,225,235,0.16)"></path>'
            f'<path d="{outline}" fill="none" stroke="{INK}" stroke-width="{CHART_EDGE}" '
            f'stroke-linejoin="round"></path></g>')
        shapes.append(
            f'<g clip-path="url(#{below})">'
            f'<path d="{field}" fill="rgba(45,130,215,0.26)"></path>'
            f'<path d="{outline}" fill="none" stroke="{RETURN_INK}" stroke-width="{CHART_EDGE}" '
            f'stroke-linejoin="round"></path></g>')
    for start, stop in stretches(lambda v: v is not None and v >= CHART_FULL):
        cx = round((x(start) + x(stop - 1)) / 2, 2)
        ticks.append(f'M{cx:g} {-2 - CHART_TICK} V-2')
    for start, stop in stretches(lambda v: v is not None and v <= -CHART_RETURN_FULL):
        cx = round((x(start) + x(stop - 1)) / 2, 2)
        ticks.append(f'M{cx:g} {height + 2} V{height + 2 + CHART_TICK}')
    tick_svg = (f'\n            <path d="{" ".join(ticks)}" stroke="{INK}" stroke-width="{CHART_EDGE}"></path>'
                if ticks else '')
    body = '\n            '.join(shapes)
    return f'''          <svg width="{box_w:g}" height="{height:g}" viewBox="0 -6 {box_w:g} {height + 12:g}" fill="none" style="overflow:visible">
            <clipPath id="{above}"><rect x="{-CHART_EDGE:g}" y="{-CHART_EDGE - 6:g}" width="{plot + 2 * CHART_EDGE:g}" height="{zero + CHART_EDGE + 6:g}"></rect></clipPath>
            <clipPath id="{below}"><rect x="{-CHART_EDGE:g}" y="{zero:g}" width="{plot + 2 * CHART_EDGE:g}" height="{height - zero + CHART_EDGE + 6:g}"></rect></clipPath>
            <path d="M0 {zero:g} H{plot:g}" stroke="{TRACK_MARK}" stroke-width="1"></path>
            {body}{tick_svg}
            <text x="{box_w:g}" y="{CHART_AXIS_BASELINE}" text-anchor="end" font-size="15" font-weight="500" letter-spacing="1.6" fill="{MUTED_DEEP}">{CHART_FULL}</text>
            <text x="{box_w:g}" y="{height:g}" text-anchor="end" font-size="15" font-weight="500" letter-spacing="1.6" fill="{MUTED_DEEP}">−{CHART_RETURN_FULL}</text>
          </svg>'''


# One scene is one sentence about the powertrain, and every cell in it is a reading this car has
# answered. Four things the first cut printed are gone, and none of them for a design reason:
#
#   - the 12 V rail: «бессмысленное значение, никакой пользы не несёт»;
#   - the charge, the range and the fuel: the car's own displays carry all three, and this page
#     exists for what they do not show;
#   - the current, which is not proven on this firmware and, on a pack whose voltage does not
#     move, is kilowatts drawn twice.
#
# What is left is what no stock display shows: what the pack is doing now, what it has been doing
# over the last ten kilometres, how warm five components are, and what those kilometres cost.
# `engine` is the cell's whole contract, and it is the Contour's own arrangement one screen along:
# the unit lives in the caption and the figure is bare, the cell says revolutions while the engine
# turns and how long it ran this trip once it stops, and it is **absent** when the engine has not
# run at all - a quantity that did not happen has no cell, and a zero is never drawn.
SCENES = {
    'traction': dict(
        headline='ИЗ БАТАРЕИ', power='34', colour=INK, volts='548',
        engine=('ДВС · МИН ЗА ПОЕЗДКУ', '14'),
        temps=[('pack', 33), ('front', 52), ('rear_l', 51), ('rear_r', 49), ('inverter', 42)],
        spread=6, spend='19,8', history=history(19.8),
    ),
    'generation': dict(
        headline='В БАТАРЕЮ ОТ ДВС', power='8', colour=RETURN_INK, volts='553',
        engine=('ДВС · ОБ/МИН', '1321'),
        temps=[('pack', 32), ('front', 47), ('rear_l', 46), ('rear_r', 44), ('inverter', 39)],
        spread=6, spend='19,4', history=history(19.4),
    ),
    'charging': dict(
        headline='В БАТАРЕЮ ОТ ЗАРЯДКИ', power='2,4', colour=RETURN_INK, volts='550',
        engine=None,
        temps=[('pack', 28), ('front', 31), ('rear_l', 29), ('rear_r', 31), ('inverter', 26)],
        # The road does not leave when the car stops: ten kilometres of history and their figure
        # stay on P and on charge, to the tenth, as the cluster's petal has them (contract §4).
        spread=4, spend='18,9', history=history(18.9),
    ),
    # Read against `ContourReadout`'s bands rather than against a number chosen to look alarming:
    # the front motor is past 85 and is DANGER, the other three are past 70 and are WATCH, and the
    # pack at 42 has just crossed its own 40. The first cut printed 126 °C on a motor, which is not
    # a temperature this drivetrain reaches before it protects itself.
    # «Что будет при расходе 200 кВт и заряде 100 кВт?» - this, and the board says it rather than
    # a paragraph: the ladder reaches 320, the two halves take their own rungs, and anything past
    # the last rung would be drawn flat against the edge rather than over the hero.
    'launch': dict(
        headline='ИЗ БАТАРЕИ', power='196', colour=INK, volts='531',
        engine=('ДВС · ОБ/МИН', '3980'),
        temps=[('pack', 44), ('front', 79), ('rear_l', 77), ('rear_r', 76), ('inverter', 74)],
        spread=9, spend='31,6', history=history(31.6, launch=True),
    ),
    'hot': dict(
        headline='ИЗ БАТАРЕИ', power='62', colour=INK, volts='544',
        engine=('ДВС · ОБ/МИН', '1420'),
        temps=[('pack', 42), ('front', 88), ('rear_l', 76), ('rear_r', 74), ('inverter', 73)],
        spread=28, spend='27,3', history=history(27.3, hole=range(38, 52)),
    ),
}

# One thought per column - on the left what the pack is doing and has been doing, on the right how
# warm it is getting - and the split is a ratio rather than a width. A fixed 500 is 60 % of the
# full screen's field and 63 % of a two-thirds pane's, which left the consumption line wrapping in
# the pane; 1.7 to 1 holds the same proportion at both widths.
LEFT_SHARE = 1.7


def headline(text):
    """The sentence, with the mark that means «into the pack» where the app always draws it."""
    if text.startswith('В БАТАРЕЮ'):
        return (f'<span style="color:{RETURN};">●</span> '
                f'<span style="color:{MUTED};">{text}</span>')
    return f'<span style="color:{MUTED};">{text}</span>'


def spend_line(scene, narrow=False):
    """What the last ten kilometres cost, under the shape that is those ten kilometres.

    «Как водитель, не очень интересен… ему больше места где-то под графиком» - so it is here
    rather than on the shelf, where it was the one row that had nothing to do with heat. Named,
    because a figure with no name and no place is exactly what the voltage was. The window rides
    on the unit, the cluster's own arrangement for this very figure - «кВт·ч/100 км · за 10 км» -
    and it is never a whole-number rounding of a filling window; the narrow pane drops the word
    «РАСХОД», never the figure or its window (contract §5).
    """
    figure = f'<span class="spend-figure">{scene["spend"]}</span>'
    if narrow:
        return f'<span class="spend">{figure} кВт·ч/100 км · 10 КМ</span>'
    return f'<span class="spend">РАСХОД {figure} кВт·ч/100 км · ЗА 10 КМ</span>'


def vehicle_page(scene='generation', shape='wide', width=FIELD_W):
    """Page two: the powertrain as a panel, not as a table of numbers.

    The first cut was four captions over four figures and a line of supporting values, and the
    owner's verdict was that it is «просто какой-то набор цифр». The Contour's own passes are the
    answer, and three of its rules do the work here:

      - **one quantity, one sentence.** The headline says what is happening in words - `ИЗ
        БАТАРЕИ`, `● В БАТАРЕЮ ОТ ДВС · 1321 об/мин` - and the figure under it says how much. A
        minus in front of a number is not a direction anybody reads at a glance, and here the
        direction matters more than the sign;
      - **a figure names the window it is true over.** `ЗА 10 КМ` after the consumption, and the
        shape above it *is* those ten kilometres: a number integrated over an interval that does
        not say which one is read against the interval the reader has in mind, which is never
        the right one;
      - **a zero is never drawn, and a quantity that did not happen has no cell.** Revolutions
        appear inside the headline only while the engine turns, consumption is absent while the
        car is standing, and nothing prints a `0` to hold a seat.

    And the page has a shape, which is what the table was missing: the last ten kilometres of the
    pack's consumption, the same twenty bins on the same scale the cluster's petal draws, so the
    two screens show one history and the figure under it is its mean (contract §2.3).
    """
    s = SCENES[scene]
    narrow = shape == 'narrow'
    temps = ''.join(temp_row(kind, value, narrow) for kind, value in s['temps'])
    if not narrow:
        temps += spread_row(s['spread'])
    # The chart is drawn at the width the left column will have, which the flex ratio decides.
    chart_w = width if narrow else round((width - GROUP - 1) * LEFT_SHARE / (LEFT_SHARE + 1), 2)

    cells = [f'''            <div class="cell">
              <div class="cap">НАПРЯЖЕНИЕ</div>
              <div class="line"><div class="val">{s['volts']}</div><div class="un19">В</div></div>
            </div>''']
    if s['engine']:
        caption, figure = s['engine']
        cells.append(f'''            <div class="cell">
              <div class="cap">{caption}</div>
              <div class="line"><div class="val">{figure}</div></div>
            </div>''')
    engine = '\n'.join(cells)
    head = f'''          <div class="head">
            <div class="cell">
              <div class="cap">{headline(s['headline'])}</div>
              <div class="line">
                <div class="hero" style="color:{s['colour']};">{s['power']}</div>
                <div class="un">кВт</div>
              </div>
            </div>
{engine}
          </div>'''
    # The shape names its ceilings in the gutter, «60» and «−20», the cluster's own ladder; the
    # line under it names the figure and its window. At 392 dp the word «РАСХОД» goes and nothing
    # else: the figure and «10 КМ» are what the shape cannot be read without.
    chart = (f'{chart_svg(chart_w, s["history"], CHART_H_NARROW if narrow else CHART_H)}\n'
             f'          <div class="foot">{spend_line(s, narrow)}</div>')

    if narrow:
        # `Space.L` between the shape and the marks rather than the group's own 32: at 392 dp the
        # page is 304 units of content in 303 of window with 32 there, and of the two numbers the
        # one that may give is the gap.
        return f'''      <div class="page narrow">
        <div class="left">
{head}
{chart}
        </div>
        <div class="temps">{temps}</div>
      </div>'''

    spend = ''
    return f'''      <div class="page split">
        <div class="left" style="width:{chart_w:g}px; flex-shrink:0;">
{head}
{chart}
        </div>
        <div class="vrule"></div>
        <div class="right">
{temps}{spend}
        </div>
      </div>'''


def closed_page():
    """The shell is shut: no hero, no shape, and one instruction where the hero was.

    Not an error. `VehicleTelemetryHub`'s own words are something a driver can act on, and the
    Contour prints them in the place its own figure would have stood. The temperatures keep their
    marks and lose their readings, which is what this app draws for a value it does not have.
    """
    temps = ''.join(temp_row(kind, None) for kind in HEAT_BAND)
    return f'''      <div class="page split">
        <div class="left" style="flex:{LEFT_SHARE}; min-width:0; justify-content:center;">
          <div class="cap">ПИТАНИЕ ОТ МАШИНЫ</div>
          <div class="instruction">ADB-ключ не подтверждён · Помощь → Диагностика</div>
        </div>
        <div class="vrule"></div>
        <div class="right">
{temps}
        </div>
      </div>'''


# ------------------------------------------------------------------------------------ the frames

def field(body, active, width=FIELD_W, grow=False):
    """The field and the dots under it.

    [grow] is for a pane, where the field is a row of a column and has to take what the chips left
    - without it the page sits at its own content height and the dots land in the middle of the
    window with the trip figures far below. At 1280 the field is a column *in a row*, where
    growing would stretch its width instead, and it is 832 by measurement.
    """
    grew = 'flex-grow:1; min-height:0; ' if grow else ''
    return (f'    <div style="width:{width:g}px; {grew}display:flex; flex-direction:column; '
            f'min-width:0;">\n{body}\n{dots(active)}\n    </div>')


def column(src):
    """Main's own right-hand column, lifted whole: these three are not a page."""
    found = re.search(r'    <div style="width:320px;.*?\n    </div>', src, re.S)
    if not found:
        sys.exit(f'{MAIN} has no 320 column; gen_strippages.py cannot build a strip')
    return found.group(0)


def tiles(src):
    found = re.findall(r'^    <div class="tile (?:on|off)">.*?\n    </div>', src, re.S | re.M)
    if len(found) != gp.FEATURES:
        sys.exit(f'{MAIN} has {len(found)} tiles, expected {gp.FEATURES}')
    return '\n\n'.join(found)


def strip(field_html, src):
    return (f'  <div style="flex-grow:1; display:flex; gap:{GROUP}px; min-height:0;">\n'
            f'{field_html}\n{column(src)}\n  </div>')


def screen(field_html, src):
    return f'''<div class="frame" style="width:1280px; height:{WINDOW_H}px; box-sizing:border-box; background:{GROUND}; display:flex; flex-direction:column; gap:{GAP}px; padding:{PAGE_TOP}px 48px 12px 48px;">

  <div style="display:grid; grid-template-columns:repeat(6, minmax(0, 1fr)); gap:{GAP}px;">

{tiles(src)}

  </div>

{strip(field_html, src)}
</div>'''


def band(field_html, src):
    """One strip on its own, at the width it has inside the 1280 screen."""
    return (f'<div class="frame" style="width:{CONTENT}px; height:{STRIP_H}px; '
            f'background:{GROUND}; display:flex; gap:{GROUP}px;">\n'
            f'{field_html}\n{column(src)}\n</div>')


def pane(which, src):
    """A pane on page two, on the geometry `gen_panes` derives for that width."""
    p = gp.MEDIUM if which == 'medium' else gp.NARROW
    shape = 'wide' if which == 'medium' else 'narrow'
    body = field(vehicle_page(shape=shape, width=p.content), 1, p.content, grow=True)
    figures = gp.figures_across() if p.figures == 'across' else gp.figures_rows()
    return f'''<div class="frame" style="width:{p.width}px; height:{WINDOW_H}px; box-sizing:border-box; background:{GROUND}; display:flex; flex-direction:column;">

  <!-- what the system takes: BYD freeform draws its drag handle here, and safeDrawing reports it -->
  <div style="height:{gp.CAPTION}px; flex-shrink:0; display:flex; align-items:center; justify-content:center;">
    <div style="width:80px; height:4px; border-radius:2px; background:rgba(218,225,235,0.22);"></div>
  </div>

  <div style="flex-grow:1; min-height:0; box-sizing:border-box; display:flex; flex-direction:column; gap:{GROUP}px; padding:{PAGE_TOP}px {p.margin}px {gp.PAGE_BOTTOM}px {p.margin}px;">

    <div style="display:grid; grid-template-columns:repeat({p.chip_columns}, minmax(0, 1fr)); gap:{GAP}px; flex-shrink:0;">

{gp.chips(p, src)}

    </div>

    <div style="flex-grow:1; min-height:0; display:flex; flex-direction:column; justify-content:space-between;">
{body}
      <div class="{'across' if p.figures == 'across' else 'rows'}">
{figures}
      </div>
    </div>

  </div>
</div>'''


def plate(caption, frame):
    return (f'<div class="plate">\n  <div class="plate-name">{caption}</div>\n'
            f'{frame}\n</div>')


def chip_rule(src):
    """The chip's shape, derived from Main's tile the way `gen_panes.board` derives it."""
    css = gp.rules(src, ['.tile'])
    css = css.replace('.tile {', '.chip {').replace(
        'height:164px; box-sizing:border-box; border-radius:22px; padding:20px; '
        'display:flex; flex-direction:column; justify-content:space-between;',
        f'aspect-ratio:1; box-sizing:border-box; border-radius:{gp.CHIP_RADIUS}px; '
        'display:flex; align-items:center; justify-content:center;')
    if 'aspect-ratio' not in css:
        sys.exit('the tile rule on Main.dc.html changed shape; gen_strippages.py cannot derive '
                 'the chip')
    return css


def build():
    src = open(MAIN, encoding='utf-8').read()
    css = gp.rules(src, ['.cap', '.num', '.un', '.rate', '.fig', '.line', '.ruled',
                         '.tile', '.on', '.off', '.nm', '.st', '.led'])
    body, bars = sound_page()
    bar_data = ',\n        '.join('{h:%g,p:%g}' % (b['h'], b['p']) for b in bars)

    rows = [
        ('', [
            ('СТРАНИЦА 1 · ЗВУК — КАК СЕЙЧАС', screen(field(body, 0), src)),
            ('СТРАНИЦА 2 · МАШИНА — ГЕНЕРАЦИЯ, СНЯТО 23.08', screen(field(vehicle_page(), 1), src)),
        ]),
        ('', [
            ('ЭЛЕКТРОТЯГА · ДВС ВЫКЛЮЧИЛСЯ МИНУТУ НАЗАД, СЦЕНА НЕ СНЯТА',
             band(field(vehicle_page('traction'), 1), src)),
            ('ЗАРЯДКА · СНЯТО 22.08 — ДВС ВЫКЛЮЧЕН, ЯЧЕЙКИ ОБОРОТОВ НЕТ',
             band(field(vehicle_page('charging'), 1), src)),
            ('ДЛИННЫЙ ПОДЪЁМ · ПОРОГИ ИЗ ПРИБОРКИ, СЦЕНА НЕ СНЯТА',
             band(field(vehicle_page('hot'), 1), src)),
        ]),
        ('', [
            ('РАЗГОН · 200 кВт ИЗ ПАКЕТА И 100 ОБРАТНО, СЦЕНА НЕ СНЯТА',
             band(field(vehicle_page('launch'), 1), src)),
        ]),
        ('', [
            ('НЕТ ДОСТУПА К МАШИНЕ', band(field(closed_page(), 1), src)),
            ('2/3 · 828', pane('medium', src)),
            ('1/3 · 416 — ГЕРОЙ, ГРАФИК, ЗНАЧКИ НАД ЦИФРАМИ',
             pane('narrow', src)),
        ]),
    ]
    board_w = GUTTER * 2 + max(
        sum(1280 for _ in range(2)) + GUTTER,
        CONTENT * 2 + GUTTER,
        CONTENT * 3 + GUTTER * 2,
        CONTENT + gp.MEDIUM.width + gp.NARROW.width + GUTTER * 2)
    board_h = (GUTTER * 2 + GUTTER * 3
               + (PLATE_NAME + GAP + WINDOW_H) * 2
               + (PLATE_NAME + GAP + STRIP_H) * 2)
    body_rows = '\n'.join(
        '<div class="row-band">\n' + '\n'.join(plate(c, f) for c, f in items) + '\n</div>'
        for _, items in rows)

    return f'''<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@200;300;400;500;700&amp;family=Roboto+Mono:wght@500;700&amp;display=swap" rel="stylesheet">
  <style>
    body {{ margin:0; background:{GROUND}; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }}
    a {{ color:{ACCENT}; }} a:hover {{ color:#FFF7D2; }}
{css}
{chip_rule(src)}
    .dot {{ position:absolute; border-radius:50%; }}
    /* The pane's three trip figures, exactly as `gen_panes` sets them. */
    .across {{ display:flex; align-items:stretch; height:{gp.BLOCK}px; }}
    .across .fig {{ flex:1; min-width:0; }}
    .rule {{ width:1px; background:rgba(218,225,235,0.14); margin:0 {GROUP // 2}px; }}
    .rows {{ display:flex; flex-direction:column; gap:{GAP}px; }}
    .row {{ height:{gp.ROW}px; line-height:{gp.ROW}px; display:flex; align-items:baseline; gap:{GAP}px; }}
    .row .cap {{ width:{gp.LABEL_COLUMN - GAP}px; flex-shrink:0; }}

    /* The car's page: one thought per column. */
    .page {{ flex-grow:1; min-height:0; display:flex; flex-direction:column;
             justify-content:center; gap:{GROUP}px; }}
    .page.split {{ flex-direction:row; align-items:stretch; gap:0; }}
    .page.narrow {{ gap:{PAGE_TOP}px; }}
    .left {{ display:flex; flex-direction:column; justify-content:center; gap:{PAGE_TOP}px; }}
    /* The hero and the engine share one line: the pack's own figure at the ramp's top rung, and
       what the engine is doing at the shelf's, so the two never read as a pair of equals. */
    .head {{ display:flex; align-items:flex-start; justify-content:space-between; gap:{GROUP}px; }}
    .head .cell {{ flex:none; display:flex; flex-direction:column; gap:{LEAD}px; }}
    .head .val {{ font-size:34px; }}
    .right {{ flex:1; min-width:0; display:flex; flex-direction:column;
              justify-content:center; gap:{LEAD}px; }}
    /* The ramp's top rung, and the first board to spend it. */
    .hero {{ font-size:62px; font-weight:200; color:{INK}; line-height:1; }}
    .vrule {{ width:1px; align-self:stretch; background:rgba(218,225,235,0.14); margin:0 {GROUP // 2}px; }}
    /* Two runs on one line, and they stand on one baseline. A flex row aligns its children by
       their boxes, and a 19 figure makes a taller box than a 15 caption - so the window and the
       consumption sat a couple of units apart and the line read as dancing. The app draws both
       from the same baseline; this is the board saying the same thing. */
    .foot {{ font-size:15px; letter-spacing:1.6px; font-weight:500; color:{MUTED_DEEP};
             display:flex; align-items:baseline; justify-content:space-between; }}
    .spend-figure {{ font-size:19px; color:{INK}; }}
    .foot svg {{ vertical-align:baseline; }}
    .instruction {{ font-size:19px; color:{INK}; }}
    .temps {{ display:flex; align-items:flex-end; justify-content:space-between; }}
    /* Under the marks: the pack's own two readings, each with its name. A value with no name at
       the foot of a column is a value nobody can place, which is what the voltage was. */
    /* A figure's unit is the rung under it, the way a 62 figure's is 24. */
    .un19 {{ font-size:19px; color:{MUTED}; letter-spacing:0; }}
    .temp {{ display:flex; align-items:center; gap:{GAP}px; height:30px; }}
    .temp .val {{ width:64px; flex-shrink:0; }}
    .temp-col {{ display:flex; flex-direction:column; gap:4px; }}
    .temp.stack {{ flex-direction:column; align-items:flex-start; gap:4px; height:auto; }}
    .temp.stack .val {{ font-size:24px; width:auto; }}
    /* The track carries the zone: everything past this sensor's own limit is drawn in the mark
       colour, so where ordinary stops is visible without a legend. */
    .track {{ position:relative; flex:1; min-width:0; height:6px; border-radius:3px; background:{TRACK}; }}
    .temp-col .track {{ width:56px; flex:none; height:4px; border-radius:2px; }}
    .val {{ font-size:34px; font-weight:300; color:{INK}; line-height:1; }}

    /* Board furniture, not screen. The size is on the element because `audit.py` takes the body's
       first child as the artboard and `shot.py` reads the first size in the body. */
    .board {{ box-sizing:border-box; display:flex; flex-direction:column; gap:{GUTTER}px;
              padding:{GUTTER}px; background:{GROUND}; }}
    .row-band {{ display:flex; align-items:flex-start; gap:{GUTTER}px; }}
    .plate {{ display:flex; flex-direction:column; gap:{GAP}px; }}
    .plate-name {{ height:{PLATE_NAME}px; line-height:{PLATE_NAME}px; font-size:15px;
                   letter-spacing:1.6px; font-weight:500; color:{MUTED_DEEP}; }}
    .frame {{ overflow:hidden; flex-shrink:0; }}
  </style>
</helmet>
<div class="board" style="width:{board_w}px; height:{board_h}px;">
{body_rows}
</div>
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":1280,"height":680}}}}'>
class Component extends DCLogic {{
  renderVals() {{
    return {{
      bars: [
        {bar_data}
      ]
    }};
  }}
}}
</script>
</body>
</html>
'''


if __name__ == '__main__':
    open(OUT, 'w', encoding='utf-8').write(build())
    print(f'wrote {OUT}')
