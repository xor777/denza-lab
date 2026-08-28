#!/usr/bin/env python3
"""
Put the head-unit boards on one ramp, one radius scale and one icon weight.

The audit counted what was actually in use: twenty-five distinct type sizes
across twelve boards where six were declared, eighteen radii, and thirteen
optical stroke weights for a single flat-line icon family. None of that was
decided; it accumulated. This maps every value onto the scale it should have
been on and leaves the result for the audit to check.

The head unit gets its own ramp rather than the cluster's. They are different
screens at different distances, and pretending one ladder serves both is how you
end up with rungs 1.06x apart. What they share is the rule: a size not on the
ramp is not available.

Run: python3 normalize.py [Board ...]
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# The head unit's ladder. Never two rungs closer than 1.26x.
#
# 82 was on it in this file, in gen_kit and in the README while `DenzaMetrics.Type.RUNGS` had six
# rungs from 62 down and no active board drew it - the same shape as `104` one level down, and the
# same answer: a constant nothing reads is a promise, not a rung. It is off all three records now,
# and this map sends what used to land on it to 62.
RAMP = [62, 46, 34, 24, 19, 15]

# Where each size in use lands, by the role it was playing rather than by
# arithmetic: a callout on a schematic wants the calm rung, a hero wants the loud
# one, and 30 and 32 were doing different jobs despite being two pixels apart.
SIZE = {
    82: 62, 72: 62, 64: 62, 62: 62, 60: 62, 56: 62,
    52: 46, 46: 46, 44: 46,
    38: 34, 36: 34, 34: 34, 32: 34,
    30: 24, 28: 24, 26: 24, 24: 24, 22: 24,
    20: 19, 19: 19, 18: 19, 17: 19,
    16: 15, 15: 15, 14: 15, 13.5: 15, 13: 15, 12: 15, 11: 15,
}

# Four corner radii and a pill. A meter keeps its own half-height radius, which
# is why tracks are written as 999 and clamped by the browser.
RADII = [2, 6, 12, 22]

# One optical weight for the whole icon family. A 24-unit viewBox rendered at
# W pixels needs this stroke to land on it.
ICON_OPTICAL = 2.0


def nearest(value, scale):
    return min(scale, key=lambda s: abs(s - value))


def map_size(value):
    key = int(value) if float(value).is_integer() else value
    if key in SIZE:
        return SIZE[key]
    return nearest(value, RAMP)


def normalise(src):
    notes = []

    def css_size(m):
        old = float(m.group(1))
        new = map_size(old)
        if new != old:
            notes.append(f'font-size {old:g} -> {new}')
        return f'font-size:{new}px'

    src = re.sub(r'font-size:\s*([\d.]+)px', css_size, src)

    def svg_size(m):
        old = float(m.group(1))
        new = map_size(old)
        if new != old:
            notes.append(f'font-size="{old:g}" -> {new}')
        return f'font-size="{new}"'

    src = re.sub(r'font-size="([\d.]+)"', svg_size, src)

    def css_radius(m):
        old = float(m.group(1))
        if old >= 40:                      # already a pill
            return m.group(0)
        new = nearest(old, RADII)
        if new != old:
            notes.append(f'radius {old:g} -> {new}')
        return f'border-radius:{new}px'

    src = re.sub(r'border-radius:\s*([\d.]+)px(?!\s+[\d.])', css_radius, src)

    def rect_radius(m):
        old = float(m.group(1))
        new = nearest(old, RADII)
        if new != old:
            notes.append(f'rx {old:g} -> {new}')
        return f'rx="{new}"'

    src = re.sub(r'(?<=<rect )([^>]*?)rx="([\d.]+)"',
                 lambda m: m.group(1) + rect_radius(re.match(r'rx="([\d.]+)"', f'rx="{m.group(2)}"')),
                 src)

    # A border is one pixel. Selection is carried by fill and ink, never by a
    # thicker edge - a 2px selected card is two pixels taller than its unselected
    # neighbours, which is what made one row of six sit staggered.
    def border(m):
        old = float(m.group(2))
        if old != 1:
            notes.append(f'border {old:g} -> 1')
        return f'{m.group(1)}:1px'

    src = re.sub(r'(border(?:-top|-bottom|-left|-right)?):\s*([\d.]+)px', border, src)

    # Icons: one optical weight, so the same glyph does not read heavier on a
    # tile than it does in a drawer.
    def icon(m):
        head, size = m.group(0), float(m.group(1))
        if 'viewBox="0 0 24 24"' not in head:
            return head
        want = round(ICON_OPTICAL * 24 / size, 3)

        def sw(mm):
            old = float(mm.group(1))
            if abs(old - want) > 0.001:
                notes.append(f'icon stroke {old:g} -> {want:g} at {size:g}px')
            return f'stroke-width="{want:g}"'

        return re.sub(r'stroke-width="([\d.]+)"', sw, head)

    src = re.sub(r'<svg width="([\d.]+)"[^>]*>', icon, src)
    return src, notes


def main():
    names = sys.argv[1:] or ['Main', 'Attention', 'Config', 'AdbGate', 'Energy',
                             'Battery', 'Thermal', 'Engine', 'TwoThirds', 'OneThird']
    for n in names:
        path = os.path.join(HERE, n + '.dc.html')
        src = open(path).read()
        out, notes = normalise(src)
        open(path, 'w').write(out)
        counts = {}
        for note in notes:
            counts[note] = counts.get(note, 0) + 1
        print(f'{n}: {len(notes)} changes')
        for note, c in sorted(counts.items()):
            print(f'    {c:3d}x {note}')


if __name__ == '__main__':
    main()
