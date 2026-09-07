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
# One deterministic road, written as multiples of the window's own mean so a scene names the figure
# it wants and the shape and the figure cannot disagree. One run of returning bins - a descent - and
# nothing else below the zero: the chart needs a zero line rather than a floor.
CONSUMPTION_SHAPE = [1.26, 1.19, 1.31, 1.22, 1.08, 1.16, 1.34, 1.29, 1.02, 0.82,
                     0.61, -0.31, -0.44, -0.18, 0.72, 0.94, 1.03, 0.97, 0.88, 0.96]


def history(average, launch=False, hole=None):
    """Twenty closed bins whose spending mean is [average]; a launch bin past the ceiling; a hole."""
    spending = [m for m in CONSUMPTION_SHAPE if m > 0]
    norm = sum(spending) / len(spending)
    bins = [round(average * m / norm, 1) for m in CONSUMPTION_SHAPE]
    if launch:
        # A half kilometre of full throttle: past the ceiling, drawn to it with a tick.
        bins[-3] = round(CHART_FULL * 1.2, 1)
    if hole is not None:
        for i in hole:
            bins[i] = None
    return bins


CHART_H = 130
CHART_H_NARROW = 60           # what is left at 392 once the head, the foot and the marks are in
CHART_BINS = 20               # 500 m each: ConsumptionWindow.KM / ConsumptionChart.BIN_KM
CHART_EDGE = 2
CHART_AXIS = 44               # the gutter on the right where the two ceilings stand
CHART_AXIS_BASELINE = 13
# Linear, clamped, marked: ContourPlan.PETAL_FULL and PETAL_RETURN_FULL, the cluster's own ladder.
CHART_FULL = 40
CHART_RETURN_FULL = 20
CHART_TICK = 3


def chart_svg(box_w, bins, height=CHART_H):
    """The last ten kilometres as steps, the one shape on this page, and it carries a sign.

    Above the zero is what the road cost, below it is what it gave back - the app's own two inks
    for those and never a third. A step is 500 m; the newest is at the right edge, where new road
    arrives. A bin the log has no energy for is a hole: nothing is drawn, the road under it is
    still the road. A bin past the ceiling is drawn to the ceiling with a tick standing over it.

    **The box says what it holds** in two figures against the edges they belong to, «60» and
    «−20», which are the same two ceilings the cluster's petal clamps at.
    """
    zero = round(height * CHART_FULL / (CHART_FULL + CHART_RETURN_FULL), 2)
    plot = box_w - CHART_AXIS
    w = plot / len(bins)

    def y_spend(v):
        return round(zero - min(max(v, 0.0) / CHART_FULL, 1.0) * zero, 2)

    def y_back(v):
        return round(zero + min(max(-v, 0.0) / CHART_RETURN_FULL, 1.0) * (height - zero), 2)

    def x(i):
        return round(i * w, 2)

    field, edge, back, ticks = [], [], [], []
    i = 0
    while i < len(bins):
        if bins[i] is None:
            i += 1
            continue
        start = i
        while i < len(bins) and bins[i] is not None:
            i += 1
        run = list(range(start, i))
        # One continuous field and edge across the run; a returning bin lies on the zero.
        field.append(f'M{x(start):g} {zero:g} ' + ' '.join(
            f'L{x(j):g} {y_spend(bins[j]):g} L{x(j + 1):g} {y_spend(bins[j]):g}' for j in run)
            + f' L{x(i):g} {zero:g} Z')
        edge.append(' '.join(
            (f'M{x(j):g} {y_spend(bins[j]):g}' if j == start else f'L{x(j):g} {y_spend(bins[j]):g}')
            + f' L{x(j + 1):g} {y_spend(bins[j]):g}' for j in run))
        j = start
        while j < i:
            if bins[j] >= 0:
                j += 1
                continue
            k = j
            while k < i and bins[k] < 0:
                k += 1
            back.append(f'M{x(j):g} {zero:g} ' + ' '.join(
                f'L{x(m):g} {y_back(bins[m]):g} L{x(m + 1):g} {y_back(bins[m]):g}' for m in range(j, k))
                + f' L{x(k):g} {zero:g}')
            j = k
        for j in run:
            if bins[j] >= CHART_FULL:
                cx = round(x(j) + w / 2, 2)
                ticks.append(f'M{cx:g} {-2 - CHART_TICK} V-2')
            elif bins[j] <= -CHART_RETURN_FULL:
                cx = round(x(j) + w / 2, 2)
                ticks.append(f'M{cx:g} {height + 2} V{height + 2 + CHART_TICK}')
    tick_svg = (f'\n            <path d="{" ".join(ticks)}" stroke="{INK}" stroke-width="{CHART_EDGE}"></path>'
                if ticks else '')
    return f'''          <svg width="{box_w:g}" height="{height:g}" viewBox="0 -6 {box_w:g} {height + 12:g}" fill="none" style="overflow:visible">
            <path d="{' '.join(field)}" fill="rgba(218,225,235,0.16)"></path>
            <path d="{' '.join(back)}" fill="rgba(45,130,215,0.26)" stroke="{RETURN}" stroke-width="{CHART_EDGE}" stroke-linejoin="round"></path>
            <path d="M0 {zero:g} H{plot:g}" stroke="{TRACK_MARK}" stroke-width="1"></path>
            <path d="{' '.join(edge)}" stroke="{INK}" stroke-width="{CHART_EDGE}" stroke-linejoin="round" stroke-linecap="square"></path>{tick_svg}
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
        spread=28, spend='27,3', history=history(27.3, hole=(6, 7)),
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
