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

# Two minutes of pack power, one sample every two seconds, positive out of the pack.
#
# `generation` is the 2026-08-23 capture itself, placed so that "now" is inside the run: a minute
# of the car on its battery alone, the engine coming in at −6 and settling at −8 to −10, which is
# what that session measured second by second. `traction` and `hot` have never been captured -
# the pack-power sign in motion is still an open item in the findings - and the board says so on
# the frame rather than here.
TRACES = {
    'traction': [
        2, 3, 6, 14, 26, 38, 47, 52, 49, 41, 33, 28, 24, 21, 19, 18, 17, 15, 12, 8,
        2, -6, -14, -19, -22, -18, -11, -4, 3, 9, 16, 24, 33, 44, 58, 71, 79, 74, 63, 54,
        48, 43, 39, 36, 34, 33, 31, 28, 24, 18, 10, 1, -9, -17, -23, -26, -21, -13, -5, 34,
    ],
    'generation': [
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, -6, -8, -9, -10, -9, -8, -9, -10, -10, -9, -8, -9,
        -10, -9, -9, -8, -9, -10, -9, -8, -8, -9, -10, -9, -8, -9, -9, -8, -8, -9, -8, -8,
    ],
    'hot': [
        44, 51, 58, 62, 57, 49, 44, 48, 55, 61, 66, 62, 55, 48, 44, 47, 53, 59, 64, 68,
        63, 55, 47, 42, 46, 52, 58, 63, 67, 64, 57, 50, 45, 48, 54, 60, 65, 69, 66, 58,
        51, 46, 49, 55, 60, 64, 61, 54, 48, 44, 47, 53, 58, 63, 66, 63, 57, 52, 56, 62,
    ],
    'charging': [-2.4] * 60,
    # A launch and the recovery after it: 200 out, 100 back, which is the pair the owner asked
    # about. Both halves land on 320 and 160, and the shape keeps its own proportions.
    'launch': [
        18, 24, 31, 44, 68, 96, 132, 168, 196, 204, 188, 160, 132, 108, 88, 72, 58, 44, 30, 12,
        -22, -58, -96, -104, -88, -64, -40, -18, 4, 22, 38, 56, 78, 104, 138, 172, 190, 176, 150, 124,
        102, 84, 66, 50, 36, 22, 8, -14, -42, -78, -102, -88, -60, -34, -12, 8, 26, 44, 62, 84,
    ],
}

TRACE_H = 130
TRACE_H_NARROW = 60           # what is left at 392 once the head, the foot and the marks are in
TRACE_BINS = 24               # five seconds each, which is the engine box's own grid on the cluster
TRACE_EDGE = 2
TRACE_AXIS = 44               # the gutter on the right where the two axis figures stand
TRACE_AXIS_BASELINE = 13

# The span steps rather than sliding, and it steps on a ladder.
#
# One fixed span for every scene was the first answer, borrowed from the Contour's engine box - and
# on this page it is the wrong one, because this box holds a quantity that lives in two different
# orders of magnitude. Sixty kilowatts out is right for a climb and squashes an eight-kilowatt
# generation into a sliver against the axis; ten is right for the generation and clamps every
# acceleration flat. The owner's own words about the first drawing were that the box must not be
# «сплющен по вертикали» and that space, where there is space, is to be used.
#
# So the ceiling is the smallest rung that holds the window, and the floor likewise. A ladder
# rather than a fit: a span that follows the data continuously redraws the same drive at a new
# height every second, while four rungs change rarely and visibly, and the foot line says which
# one is up - a figure names the window it is true over, and a shape names the span it is drawn in.
TRACE_RUNGS = (5, 10, 20, 40, 80, 160, 320, 640)


def bins(samples, count=TRACE_BINS):
    """The window as fixed-duration steps, each the mean of the samples that arrived in it.

    Two minutes of pack power was a line of sixty points, and the cluster settled this question
    already: a per-sample line across half a metre of glass is 0.9 mm per sample of a quantity
    that moves faster than the eye follows. Twenty-four steps of five seconds is what the engine
    box draws, and it is what a glance can actually read.
    """
    out = []
    for i in range(count):
        lo = round(i * len(samples) / count)
        hi = max(lo + 1, round((i + 1) * len(samples) / count))
        window = samples[lo:hi]
        out.append(round(sum(window) / len(window), 2))
    return out


def trace_span(steps):
    """The two rungs this window needs, which together are one uniform scale.

    Each half gets the smallest rung that holds it and the axis is placed between them at
    `top / (top + bottom)`, which makes the kilowatts-per-pixel identical above and below - so
    reading one half against the other is honest, and the box is full whichever way the pack has
    been working. A symmetric span would leave half the box empty on a climb, where nothing goes
    back into the pack for two minutes; a fixed one leaves an eight-kilowatt generation as a
    sliver against the axis, which is the «сплющен» the Contour was told about twice.
    """
    top = next((r for r in TRACE_RUNGS if max(steps) <= r), TRACE_RUNGS[-1])
    bottom = next((r for r in TRACE_RUNGS if -min(steps) <= r), TRACE_RUNGS[-1])
    return top, bottom


def trace_svg(box_w, samples, height=TRACE_H):
    """Two minutes of pack power as steps, and the only thing here that carries a sign as a shape.

    Above the axis is what left the pack, below it is what came back - the app's own two inks for
    those and never a third. A step is five seconds; the newest is at the right edge, where new
    data arrives, and the oldest is whatever the window has left of its own.

    **The box says what it holds**, in two figures against the edges they belong to. The span was a
    phrase on the line underneath until the owner read it and said «тоже не интуитивно»: it was a
    legend, and a legend is what this page spent four drawings getting rid of.

    **And a step past the last rung is drawn flat against the edge.** *«Что будет при расходе 200
    кВт и заряде 100 кВт?»* - with the ladder stopping at 160 and nothing clamping, the answer was
    that the box drew them over the figure above it.
    """
    steps = bins(samples)
    top, bottom = trace_span(steps)
    zero = round(height * top / (top + bottom), 2)
    plot = box_w - TRACE_AXIS
    w = plot / len(steps)

    def y_of(kw):
        held = max(-bottom, min(top, kw))
        return round(zero - held * (zero if held > 0 else (height - zero)) / (top if held > 0 else bottom), 2)

    def rects(above):
        out = []
        for i, kw in enumerate(steps):
            if (kw > 0) != above or kw == 0:
                continue
            x = round(i * w, 2)
            out.append(f'M{x:g} {zero:g} H{round(x + w, 2):g} V{y_of(kw):g} H{x:g} Z')
        return ' '.join(out)

    edge = []
    for i, kw in enumerate(steps):
        y = y_of(kw)
        x, x2 = round(i * w, 2), round((i + 1) * w, 2)
        edge.append((f'M{x:g} {y:g}' if not edge else f'L{x:g} {y:g}') + f' L{x2:g} {y:g}')

    return f'''          <svg width="{box_w:g}" height="{height:g}" viewBox="0 0 {box_w:g} {height:g}" fill="none">
            <path d="{rects(True)}" fill="rgba(218,225,235,0.16)"></path>
            <path d="{rects(False)}" fill="rgba(45,130,215,0.26)"></path>
            <path d="M0 {zero:g} H{plot:g}" stroke="{TRACK_MARK}" stroke-width="1"></path>
            <path d="{' '.join(edge)}" stroke="{INK}" stroke-width="{TRACE_EDGE}" stroke-linejoin="round" stroke-linecap="square"></path>
            <text x="{box_w:g}" y="{TRACE_AXIS_BASELINE}" text-anchor="end" font-size="15" font-weight="500" letter-spacing="1.6" fill="{MUTED_DEEP}">{top} кВт</text>
            <text x="{box_w:g}" y="{height:g}" text-anchor="end" font-size="15" font-weight="500" letter-spacing="1.6" fill="{MUTED_DEEP}">{bottom}</text>
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
# for two minutes, how warm five components are, and what the last three kilometres cost.
# `engine` is the cell's whole contract, and it is the Contour's own arrangement one screen along:
# the unit lives in the caption and the figure is bare, the cell says revolutions while the engine
# turns and how long it ran this trip once it stops, and it is **absent** when the engine has not
# run at all - a quantity that did not happen has no cell, and a zero is never drawn.
SCENES = {
    'traction': dict(
        headline='ИЗ БАТАРЕИ', power='34', colour=INK, volts='548',
        engine=('ДВС · МИН ЗА ПОЕЗДКУ', '14'),
        temps=[('pack', 33), ('front', 52), ('rear_l', 51), ('rear_r', 49), ('inverter', 42)],
        spread=6, spend='19,8', trace='traction',
    ),
    'generation': dict(
        headline='В БАТАРЕЮ ОТ ДВС', power='-8', colour=RETURN_INK, volts='553',
        engine=('ДВС · ОБ/МИН', '1321'),
        temps=[('pack', 32), ('front', 47), ('rear_l', 46), ('rear_r', 44), ('inverter', 39)],
        spread=6, spend='19,4', trace='generation',
    ),
    'charging': dict(
        headline='В БАТАРЕЮ ОТ ЗАРЯДКИ', power='-2,4', colour=RETURN_INK, volts='550',
        engine=None,
        temps=[('pack', 28), ('front', 31), ('rear_l', 29), ('rear_r', 31), ('inverter', 26)],
        # No consumption cell while the car is standing: kWh/100 km has no value at zero speed,
        # and a quantity that did not happen is not drawn as a zero.
        spread=4, spend='', trace='charging',
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
        spread=9, spend='31,6', trace='launch',
    ),
    'hot': dict(
        headline='ИЗ БАТАРЕИ', power='62', colour=INK, volts='544',
        engine=('ДВС · ОБ/МИН', '1420'),
        temps=[('pack', 42), ('front', 88), ('rear_l', 76), ('rear_r', 74), ('inverter', 73)],
        spread=28, spend='27,3', trace='hot',
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


def spend_run(scene):
    """What the last three kilometres cost, hung off the right of the shape's own caption.

    «Как водитель, не очень интересен… ему больше места где-то под графиком» - so it is here
    rather than on the shelf, where it was the one row that had nothing to do with heat. Named,
    because a figure with no name and no place is exactly what the voltage was.
    """
    if not scene['spend']:
        return ''
    return ('<span class="spend">РАСХОД <span class="spend-figure">'
            f'{scene["spend"]}</span> кВт·ч/100 ЗА 10 КМ</span>')


def vehicle_page(scene='generation', shape='wide', width=FIELD_W):
    """Page two: the powertrain as a panel, not as a table of numbers.

    The first cut was four captions over four figures and a line of supporting values, and the
    owner's verdict was that it is «просто какой-то набор цифр». The Contour's own passes are the
    answer, and three of its rules do the work here:

      - **one quantity, one sentence.** The headline says what is happening in words - `ИЗ
        БАТАРЕИ`, `● В БАТАРЕЮ ОТ ДВС · 1321 об/мин` - and the figure under it says how much. A
        minus in front of a number is not a direction anybody reads at a glance, and here the
        direction matters more than the sign;
      - **a figure names the window it is true over.** `ПОСЛЕДНИЕ 2 МИНУТЫ` under the trace,
        `ЗА 10 КМ` after the consumption: a number integrated over an interval that does not say
        which one is read against the interval the reader has in mind, which is never the right
        one;
      - **a zero is never drawn, and a quantity that did not happen has no cell.** Revolutions
        appear inside the headline only while the engine turns, consumption is absent while the
        car is standing, and nothing prints a `0` to hold a seat.

    And the page has a shape, which is what the table was missing: two minutes of the same
    quantity the headline names, so the figure and its history are one object rather than two.
    """
    s = SCENES[scene]
    narrow = shape == 'narrow'
    temps = ''.join(temp_row(kind, value, narrow) for kind, value in s['temps'])
    if not narrow:
        temps += spread_row(s['spread'])
    # The trace is drawn at the width the left column will have, which the flex ratio decides.
    trace_w = width if narrow else round((width - GROUP - 1) * LEFT_SHARE / (LEFT_SHARE + 1), 2)

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
    # The shape names the span it is drawn in, the way every figure on this page names the window
    # it is true over. Two rungs and one scale: «5 ↑ 10 ↓» is the box's own ceiling and floor.
    # The pack's voltage leaves the narrow pane rather than wrapping the line: at 392 dp the
    # window, the span and a third reading are three lines' worth of words on one line, and of the
    # three the span is the one the shape above it cannot be read without.
    # And the window shortens before anything else goes, which is the Contour's own rule for the
    # same line one screen along: «ПОСЛЕДНИЕ 2 МИНУТЫ» becomes «2 МИН» at 392 dp, and the span
    # stays, because the shape above cannot be read without it.
    window = '2 МИН' if narrow else 'ПОСЛЕДНИЕ 2 МИНУТЫ'
    # The two arrows are drawn rather than typed, on both records. A still in Chrome has a font it
    # can check and the car does not: this panel's own variometer is three strokes for exactly that
    # reason, and a board that types what the app draws is a board that cannot be compared with a
    # photograph of the screen.
    trace = (f'{trace_svg(trace_w, TRACES[s["trace"]], TRACE_H_NARROW if narrow else TRACE_H)}\n'
             f'          <div class="foot">{window}{spend_run(s)}</div>')

    if narrow:
        # `Space.L` between the shape and the marks rather than the group's own 32: at 392 dp the
        # page is 304 units of content in 303 of window with 32 there, and of the two numbers the
        # one that may give is the gap.
        return f'''      <div class="page narrow">
        <div class="left">
{head}
{trace}
        </div>
        <div class="temps">{temps}</div>
      </div>'''

    spend = ''
    return f'''      <div class="page split">
        <div class="left" style="width:{trace_w:g}px; flex-shrink:0;">
{head}
{trace}
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
