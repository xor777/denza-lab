#!/usr/bin/env python3
"""Emit the two pane boards for the dashboard: 2/3 and 1/3.

`Main.dc.html` is the dashboard at 1280, where a feature is a tile: an icon, a name and a line
saying what it is doing. A pane is not a smaller version of that. It is 828 or 416 dp wide with the
same 680 of height, and eleven tiles at the size their words need would spend three quarters of it on
words nobody is reading - in a pane the driver already knows the icons, and the thing worth the
space is the one that moves.

So a pane compresses the tile to a **chip**: the same icon, the same two gestures, the state carried
by the border, the ink and a dot instead of by a caption. The chips take one band across the top and
everything under them is the strip.

That is the shape the archived boards under these two names had - icons in a row, one large panel
below - and it was right; what was in the panel was four vehicle instruments that have since been
retired. The analyser goes there.

The tiles, their icons and their on/off states are read out of `Main.dc.html` so a pane cannot
quietly disagree with the full screen about what exists. Editing a tile means editing
`Main.dc.html` and running this.

What the panes decide for themselves:

  the caption bar  A pane window is 680 dp tall and the top 24 of it belong to the system: BYD's
                   freeform windowing draws a drag handle there, and `WindowInsets.safeDrawing`
                   reports it. Measured on the car, not assumed - the first cut of these boards
                   spent it, and the foot of the strip was drawn past the bottom edge.

  side margin      48 is the *screen's* margin and a pane is not the screen. 20 at two thirds,
                   12 at one third.

  the chip         square, and as wide as its share of the row. 11 across at 828 is 60.7; 6 across
                   in two rows at 416 is 55.3. Both remain above the touch-target floor.

  the figures      three across with a rule between them where there is width for it, three rows
                   where there is not.
"""
import re
import sys

MAIN = 'Main.dc.html'

FIELD_MAIN = 198.0           # the bar field on Main.dc.html
BAR_FRACTION = 0.7097        # SpectrumRenderer.BAR_WIDTH_FRACTION
BASELINE_FRACTION = 0.8319   # SpectrumRenderer.BASELINE_FRACTION
STRIP = 52.0                 # SpectrumRenderer.STRIP_UNITS - the ticker's band
PEAK = 4.0                   # SpectrumRenderer.PEAK_UNITS
REFLECT = 40.0               # SpectrumRenderer.REFLECT_UNITS - the reflection's own ceiling
BANDS = 26
FEATURES = 11

CAPTION = 24                 # what the system takes off the top of a pane
GAP = 12                     # Space.M
GROUP = 32                   # Space.XL - between two things that are not the same thought
PAGE_TOP = 20                # Space.L
PAGE_BOTTOM = 12
WINDOW_H = 680

CHIP_RADIUS = 12             # Radius.M

# The chip's insides as fractions of the chip, because the chip is a fraction of the row.
#
# At ten features the chip is 68.0 dp at two thirds and 68.8 at one third, and these three come to
# 30, 7 and 9 - which is what they were written as. They are ratios now because the chip shrinks
# when a feature is added: an eleventh puts it at 60.7, and a 30 dp glyph in a 60.7 chip has its
# top-right corner under the dot.
CHIP_ICON_RATIO = 30 / 68
CHIP_DOT_RATIO = 7 / 68
CHIP_DOT_INSET_RATIO = 9 / 68

# The smallest chip this design has: 52 dp, where the glyph is 23. Under it the icon stops reading
# at arm's length and the target stops being comfortable - 48 is the platform's own floor for a
# thing a finger has to hit, and this leaves a little over it. Twelve features fit both panes; a
# thirteenth is where somebody has to decide something rather than where a number quietly gets
# smaller, which is why `DashboardLayoutPolicyTest` fails at that point instead of the screen.
CHIP_MIN = 52

ROW = 30                     # one figure as a row: a 15 label and a 24 reading on one baseline
BLOCK = 76                   # one figure as a block: a 15 label over a 46 figure
LABEL = 15
FIGURE = 46
VALUE = 24
RATE = 19

BARS = [
    (30, 13), (55, 8), (91, 17), (133, 6), (160, 11), (182, 4),
    (164, 14), (140, 7), (112, 16), (88, 10), (124, 5), (153, 13),
    (177, 4), (159, 10), (131, 17), (98, 7), (78, 12), (110, 5),
    (136, 14), (155, 4), (138, 11), (108, 16), (82, 7), (58, 12),
    (82, 5), (105, 13),
]


class Pane:
    def __init__(self, name, width, margin, chip_columns, figures, title):
        self.name = name
        self.width = width
        self.margin = margin
        self.chip_columns = chip_columns
        self.figures = figures            # 'across' or 'rows'
        self.title = title
        self.content = width - margin * 2
        self.chip_rows = -(-FEATURES // chip_columns)
        self.chip = (self.content - (chip_columns - 1) * GAP) / chip_columns
        self.chips_h = self.chip_rows * self.chip + (self.chip_rows - 1) * GAP

    @property
    def strip_h(self):
        return WINDOW_H - CAPTION - PAGE_TOP - self.chips_h - GROUP - PAGE_BOTTOM

    @property
    def figures_h(self):
        return BLOCK if self.figures == 'across' else ROW * 3 + GAP * 2

    @property
    def analyser_h(self):
        """What the analyser gets: the strip, less the figures under it and the gap between."""
        return self.strip_h - self.figures_h - GROUP


MEDIUM = Pane('TwoThirds', 828, 20, FEATURES, 'across', 'MIDNIGHT CITY')
NARROW = Pane('OneThird', 416, 12, (FEATURES + 1) // 2, 'rows', 'MIDNIGHT CITY')


def main_board():
    return open(MAIN, encoding='utf-8').read()


def tiles(src):
    found = re.findall(r'^    <div class="tile (on|off)">(.*?)\n    </div>', src, re.S | re.M)
    if len(found) != FEATURES:
        sys.exit(f'{MAIN} has {len(found)} tiles, expected {FEATURES}')
    out = []
    for state, body in found:
        svg = re.search(r'<svg .*?</svg>', body, re.S)
        if not svg:
            sys.exit('a tile on the board has no icon')
        out.append((state, svg.group(0)))
    return out


def rules(src, selectors):
    out = []
    for selector in selectors:
        found = re.search(r'^\s*(' + re.escape(selector) + r' \{[^\n]*)$', src, re.M)
        if not found:
            sys.exit(f'{MAIN} has no {selector} rule')
        out.append('    ' + found.group(1).strip())
    return '\n'.join(out)


def chip(state, svg, size, indent='      '):
    """One feature as an icon: the tile's glyph, its state, and nothing to read."""
    # A whole number, so the one optical weight below lands on the ramp exactly rather
    # than a thousandth off it.
    icon = round(size * CHIP_ICON_RATIO)
    svg = re.sub(r'width="[\d.]+" height="[\d.]+"',
                 f'width="{icon:g}" height="{icon:g}"', svg, count=1)
    # One optical weight for every icon in the app, so the stroke follows the size it is drawn at:
    # ICON_WEIGHT * 24 / size. Leaving the tile's own 1.6 on a 30.4 glyph is how `audit.py` came to
    # report two stroke weights on one board.
    svg = re.sub(r'stroke-width="[\d.]+"',
                 f'stroke-width="{round(2.0 * 24 / icon, 3):g}"', svg)
    dot = '#FEEFAB' if state == 'on' else '#3F434D'
    d = round(size * CHIP_DOT_RATIO, 1)
    inset = round(size * CHIP_DOT_INSET_RATIO, 1)
    return (
        f'{indent}<div class="chip {state}">\n'
        f'{indent}  {svg}\n'
        f'{indent}  <div class="dot" style="top:{inset:g}px; right:{inset:g}px; '
        f'width:{d:g}px; height:{d:g}px; background:{dot};"></div>\n'
        f'{indent}</div>'
    )


def chips(pane, src):
    return '\n'.join(chip(state, svg, pane.chip) for state, svg in tiles(src))


def analyser(pane):
    """The ticker, the bars, their segment grid and the cropped reflection."""
    span = pane.content
    box = pane.analyser_h
    baseline = STRIP + (box - STRIP) * BASELINE_FRACTION
    field = round(baseline - STRIP, 2)
    reflect = round(min(box - baseline, REFLECT), 2)

    pitch = span / (BANDS - 1 + BAR_FRACTION)
    bar_w = round(pitch * BAR_FRACTION, 2)
    bar_gap = round(pitch - bar_w, 2)

    # Scaled so the tallest column still has room for its peak marker: the marker floats PEAK
    # above the bar and is PEAK tall itself, which is the pair the board must not clip.
    tallest = max(h + p for h, p in BARS)
    scale = min(field / FIELD_MAIN, (field - PEAK) / tallest)
    data = [{'h': round(h * scale, 2), 'p': round(p * scale, 2)} for h, p in BARS]

    body = f'''      <div style="display:flex; align-items:center; height:{STRIP:g}px;">
        <div class="led">{pane.title}</div>
      </div>
      <div style="position:relative;">
        <div style="display:flex; align-items:flex-end; gap:{bar_gap}px; height:{field}px;">
          <sc-for list="{{{{bars}}}}" as="b" hint-placeholder-count="{BANDS}">
            <div style="width:{bar_w}px; display:flex; flex-direction:column; justify-content:flex-end; height:{field}px;">
              <div style="height:{PEAK:g}px; background:#FFF8DA; border-radius:2px; box-shadow:0 0 7px rgba(254,239,171,0.9); margin-bottom: {{{{b.p}}}}px;"></div>
              <div style="height: {{{{b.h}}}}px; border-radius:2px 2px 0 0; background:linear-gradient(to top, #4A4222, #FEEFAB 62%, #FFF8DA); box-shadow:0 0 16px rgba(254,239,171,0.22);"></div>
            </div>
          </sc-for>
        </div>
        <div style="position:absolute; left:0; right:0; bottom:0; height:{field}px; background:repeating-linear-gradient(to top, rgba(7,8,10,0) 0px, rgba(7,8,10,0) 8px, #07080A 8px, #07080A 11px); pointer-events:none;"></div>
      </div>
      <div style="height:{reflect}px; overflow:hidden; opacity:0.14; transform:scaleY(-1);">
        <div style="display:flex; align-items:flex-end; gap:{bar_gap}px; height:{reflect}px;">
          <sc-for list="{{{{bars}}}}" as="b" hint-placeholder-count="{BANDS}">
            <div style="width:{bar_w}px; height: {{{{b.h}}}}px; background:linear-gradient(to top, #4A4222, #FEEFAB 62%, #FFF8DA);"></div>
          </sc-for>
        </div>
      </div>'''
    return body, data


ARROW = ('<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#FEEFAB" '
         'stroke-width="2.4" stroke-linecap="round"><path d="M12 19V6M6.5 11.5 12 6l5.5 5.5">'
         '</path></svg>')


def figures_across():
    """Three readings side by side with a rule between them, as the archived board set them."""
    return f'''      <div class="fig">
        <div class="cap">В ПУТИ</div>
        <div class="line">
          <div class="num">1:42</div>
          <div class="un">128 км</div>
        </div>
      </div>
      <div class="rule"></div>
      <div class="fig">
        <div class="cap">ВЫСОТА</div>
        <div class="line">
          <div class="num">642</div>
          <div class="un">м</div>
          <div style="display:flex; align-items:center; gap:6px; margin-left:auto;">
            {ARROW}
            <div style="font-size:{RATE}px; color:#FEEFAB;">1,2</div>
          </div>
        </div>
      </div>
      <div class="rule"></div>
      <div class="fig">
        <div class="cap" style="color:#FF9F19;">ЗАКАТ</div>
        <div class="line">
          <div class="num">19:44</div>
        </div>
      </div>'''


def figures_rows():
    """The same three where there is no width to set them side by side."""
    small = ARROW.replace('width="20" height="20"', 'width="14" height="14"') \
                 .replace('stroke-width="2.4"', 'stroke-width="3.429"')
    return f'''      <div class="row">
        <div class="cap">В ПУТИ</div>
        <div class="val">1:42 · 128 км</div>
      </div>
      <div class="row">
        <div class="cap">ВЫСОТА</div>
        <div style="display:flex; align-items:baseline; gap:8px;">
          <div class="val">642 м</div>
          {small}
          <div class="rate">1,2</div>
        </div>
      </div>
      <div class="row">
        <div class="cap" style="color:#FF9F19;">ЗАКАТ</div>
        <div class="val">19:44</div>
      </div>'''


def board(pane, src):
    body, data = analyser(pane)
    css = rules(src, ['.tile', '.on', '.off', '.led'])
    # The chip borrows the tile's two skins and nothing else about it.
    css = css.replace('.tile {', '.chip {').replace(
        'height:164px; box-sizing:border-box; border-radius:22px; padding:20px; '
        'display:flex; flex-direction:column; justify-content:space-between;',
        f'aspect-ratio:1; box-sizing:border-box; border-radius:{CHIP_RADIUS}px; '
        'display:flex; align-items:center; justify-content:center;')
    if 'aspect-ratio' not in css:
        sys.exit('the tile rule on Main.dc.html changed shape; gen_panes.py cannot derive the chip')
    bars = ',\n        '.join('{h:%g,p:%g}' % (b['h'], b['p']) for b in data)

    figures = figures_across() if pane.figures == 'across' else figures_rows()
    figures_class = 'across' if pane.figures == 'across' else 'rows'

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
    body {{ margin:0; background:#07080A; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }}
    a {{ color:#FEEFAB; }} a:hover {{ color:#FFF7D2; }}
{css}
    .dot {{ position:absolute; border-radius:50%; }}
    .across {{ display:flex; align-items:stretch; height:{BLOCK}px; }}
    .fig {{ flex:1; min-width:0; display:flex; flex-direction:column; justify-content:space-between; }}
    .rule {{ width:1px; background:rgba(218,225,235,0.14); margin:0 {GROUP // 2}px; }}
    .line {{ display:flex; align-items:baseline; gap:14px; }}
    .num {{ font-size:{FIGURE}px; font-weight:200; color:#DAE1EB; line-height:1; }}
    .un {{ font-size:{VALUE}px; color:#86909B; }}
    .rows {{ display:flex; flex-direction:column; gap:{GAP}px; }}
    .row {{ height:{ROW}px; line-height:{ROW}px; display:flex; align-items:baseline; justify-content:space-between; }}
    .cap {{ font-size:{LABEL}px; letter-spacing:1.6px; font-weight:500; color:#86909B; }}
    .val {{ font-size:{VALUE}px; font-weight:300; color:#DAE1EB; }}
    .rate {{ font-size:{RATE}px; color:#FEEFAB; }}
  </style>
</helmet>
<div style="width:{pane.width}px; height:{WINDOW_H}px; box-sizing:border-box; background:#07080A; display:flex; flex-direction:column;">

  <!-- what the system takes: BYD freeform draws its drag handle here, and safeDrawing reports it -->
  <div style="height:{CAPTION}px; flex-shrink:0; display:flex; align-items:center; justify-content:center;">
    <div style="width:80px; height:4px; border-radius:2px; background:rgba(218,225,235,0.22);"></div>
  </div>

  <div style="flex-grow:1; min-height:0; box-sizing:border-box; display:flex; flex-direction:column; gap:{GROUP}px; padding:{PAGE_TOP}px {pane.margin}px {PAGE_BOTTOM}px {pane.margin}px;">

    <div style="display:grid; grid-template-columns:repeat({pane.chip_columns}, minmax(0, 1fr)); gap:{GAP}px; flex-shrink:0;">

{chips(pane, src)}

    </div>

    <div style="flex-grow:1; min-height:0; display:flex; flex-direction:column; justify-content:space-between;">
      <div style="display:flex; flex-direction:column;">
{body}
      </div>
      <div class="{figures_class}">
{figures}
      </div>
    </div>

  </div>
</div>
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":{pane.width},"height":{WINDOW_H}}}}}'>
class Component extends DCLogic {{
  renderVals() {{
    return {{
      bars: [
        {bars}
      ]
    }};
  }}
}}
</script>
</body>
</html>
'''


if __name__ == '__main__':
    src = main_board()
    for pane in (MEDIUM, NARROW):
        out = f'{pane.name}.dc.html'
        open(out, 'w', encoding='utf-8').write(board(pane, src))
        print(
            f'{out}: {pane.width}x{WINDOW_H}  content {pane.content:.0f}'
            f'  chip {pane.chip:.1f} x{pane.chip_columns}x{pane.chip_rows}'
            f'  strip {pane.content:.0f}x{pane.strip_h:.1f}'
            f'  analyser {pane.analyser_h:.1f}  figures {pane.figures} {pane.figures_h}'
        )


# --- the density board -------------------------------------------------------

DENSITY_COUNTS = (10, 11, 12, 13)
DENSITY_W = 1360
FUTURE = 'M12 6v12M6 12h12'   # a feature that does not exist yet


def density_row(src, count, columns, width, size, indent):
    """One band of chips at a given count and size."""
    real = tiles(src)
    out = []
    for index in range(count):
        if index < len(real):
            state, svg = real[index]
        else:
            state, svg = 'off', (
                '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#6E767F" '
                f'stroke-width="2" stroke-linecap="round"><path d="{FUTURE}"></path></svg>'
            )
        out.append(chip(state, svg, size, indent))
    return (
        f'{indent[:-2]}<div style="width:{width}px; display:grid; '
        f'grid-template-columns:repeat({columns}, minmax(0, 1fr)); gap:{GAP}px;">\n'
        + '\n'.join(out)
        + f'\n{indent[:-2]}</div>'
    )


def density_board(src):
    """What happens to the chip when a feature is added, at both pane widths.

    A band of icons is a toolbar: it does not wrap, it fits. So the chip is its share of the row,
    which means adding an eleventh feature makes every chip smaller rather than making a new row -
    and the question this board answers is how far that goes before it stops being a chip.
    """
    sections = []
    for count in DENSITY_COUNTS:
        wide = (MEDIUM.content - (count - 1) * GAP) / count
        cols = -(-count // 2)
        narrow = (NARROW.content - (cols - 1) * GAP) / cols
        under = min(wide, narrow) < CHIP_MIN
        sections.append((count, wide, cols, narrow, under))

    body = []
    for count, wide, cols, narrow, under in sections:
        tone = '#FF6B5B' if under else '#86909B'
        note = ' · ниже порога 52' if under else ''
        body.append(f'''  <div style="display:flex; flex-direction:column; gap:14px;">
    <div style="display:flex; align-items:baseline; gap:14px;">
      <div style="font-size:24px; font-weight:500; color:#DAE1EB;">{count} функций</div>
      <div style="font-size:15px; letter-spacing:1.6px; font-weight:500; color:{tone};">2/3 · {wide:.1f}   1/3 · {cols} x {narrow:.1f}{note}</div>
    </div>
    <div style="display:flex; align-items:flex-start; gap:32px;">
{density_row(src, count, count, MEDIUM.content, wide, '        ')}
{density_row(src, count, cols, NARROW.content, narrow, '        ')}
    </div>
  </div>''')

    css = rules(src, ['.tile', '.on', '.off'])
    css = css.replace('.tile {', '.chip {').replace(
        'height:164px; box-sizing:border-box; border-radius:22px; padding:20px; '
        'display:flex; flex-direction:column; justify-content:space-between;',
        f'aspect-ratio:1; box-sizing:border-box; border-radius:{CHIP_RADIUS}px; '
        'display:flex; align-items:center; justify-content:center;')

    return f'''<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@200;300;400;500;700&amp;display=swap" rel="stylesheet">
  <style>
    body {{ margin:0; background:#07080A; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }}
{css}
    .dot {{ position:absolute; border-radius:50%; }}
  </style>
</helmet>
<div style="width:{DENSITY_W}px; box-sizing:border-box; background:#07080A; display:flex; flex-direction:column; gap:48px; padding:48px;">
{chr(10).join(body)}
</div>
</x-dc>
</body>
</html>
'''
