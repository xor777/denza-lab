#!/usr/bin/env python3
"""Emit the two pane boards for the dashboard: 2/3 and 1/3.

`Main.dc.html` is the dashboard at 1280. These are the same screen in the two
window widths the DiLink split actually hands an app - 828 and 416 - and they are
generated rather than drawn for one reason: the tiles must be the *same tiles*.
A pane board that quietly drifts a name, a caption or an icon away from the full
screen is worse than no pane board, because it looks like a decision.

So the ten tiles, the tile CSS and the ticker CSS are read out of `Main.dc.html`
and the layout is computed from the numbers below. Editing a tile means editing
`Main.dc.html` and running this.

What the panes decide for themselves, and why:

  side margin   48 is the *screen's* margin, and a pane is not the screen. It
                steps down the same ladder as the window narrows: 48 full
                screen, 20 at two thirds, 12 at one third.

  columns       chosen so the tile keeps the width its words need. The longest
                name on the dashboard, "Экран водителя", measures 145.2 dp at
                19/500, and a tile spends 20 either side of its text - so a tile
                under about 186 loses a word to an ellipsis. 6 columns of 1184
                is 187.3, 4 of 788 is 188.0, 2 of 392 is 190.0. The margin is
                what buys that, which is why it steps down: at 32 and 20 the two
                panes come out at 182 and clip the cluster tile.

                It also means the tile is the same object at every width. Three
                columns at 828 - which is what shipped - made it 236 dp wide, so
                the tile grew as its window shrank.

  the strip     the analyser keeps the ticker over it, as on the full screen.
                What changes is the trip figures: three blocks hung apart down a
                320-wide column at 1280, three label-and-value rows in a pane.
                Same three readings, one line each.
"""
import re
import sys

MAIN = 'Main.dc.html'

# The board's own analyser, for the numbers the panes rescale.
FIELD_MAIN = 198.0          # the bar field on Main.dc.html
BAR_FRACTION = 0.7097       # SpectrumRenderer.BAR_WIDTH_FRACTION
BASELINE_FRACTION = 0.8319  # SpectrumRenderer.BASELINE_FRACTION
STRIP = 52.0                # SpectrumRenderer.STRIP_UNITS - the ticker's band
PEAK = 4.0                  # SpectrumRenderer.PEAK_UNITS
BANDS = 26

BARS = [
    (30, 13), (55, 8), (91, 17), (133, 6), (160, 11), (182, 4),
    (164, 14), (140, 7), (112, 16), (88, 10), (124, 5), (153, 13),
    (177, 4), (159, 10), (131, 17), (98, 7), (78, 12), (110, 5),
    (136, 14), (155, 4), (138, 11), (108, 16), (82, 7), (58, 12),
    (82, 5), (105, 13),
]

TILE_HEIGHT = 164
GAP = 12
PAGE_TOP = 20
PAGE_BOTTOM = 12
WINDOW_H = 680          # what the car actually gives an app, dock and status band removed

# One row of figures, and the block the three of them make.
ROW = 30
ROW_GAP = 12
BLOCK = ROW * 3 + ROW_GAP * 2


class Pane:
    def __init__(self, name, width, margin, columns, column_width, panel_h, title):
        self.name = name
        self.width = width
        self.margin = margin
        self.columns = columns
        self.column_width = column_width   # the figures column; None stacks them under the analyser
        self.panel_h = panel_h
        self.title = title
        self.content = width - margin * 2
        self.rows = -(-10 // columns)
        self.tiles_h = self.rows * TILE_HEIGHT + (self.rows - 1) * GAP

    @property
    def analyser_w(self):
        if self.column_width is None:
            return self.content
        return self.content - self.column_width - 32

    @property
    def analyser_h(self):
        if self.column_width is None:
            return self.panel_h - BLOCK - 20
        return self.panel_h

    @property
    def height(self):
        return PAGE_TOP + self.tiles_h + GAP + self.panel_h + PAGE_BOTTOM


MEDIUM = Pane('TwoThirds', 828, 20, 4, 264, 120, 'MIDNIGHT CITY')
NARROW = Pane('OneThird', 416, 12, 2, None, 376, 'MIDNIGHT CITY')


def main_board():
    return open(MAIN, encoding='utf-8').read()


def tiles(src):
    found = re.findall(r'^    <div class="tile (?:on|off)">.*?\n    </div>', src, re.S | re.M)
    if len(found) != 10:
        sys.exit(f'{MAIN} has {len(found)} tiles, expected 10')
    return '\n\n'.join(found)


def rules(src, selectors):
    out = []
    for selector in selectors:
        found = re.search(r'^\s*(' + re.escape(selector) + r' \{[^\n]*)$', src, re.M)
        if not found:
            sys.exit(f'{MAIN} has no {selector} rule')
        out.append('    ' + found.group(1).strip())
    return '\n'.join(out)


def analyser(pane):
    """The ticker, the bars, their segment grid and the cropped reflection."""
    span = pane.analyser_w
    bar_top = STRIP
    bottom = pane.analyser_h
    baseline = bar_top + (bottom - bar_top) * BASELINE_FRACTION
    field = round(baseline - bar_top, 2)
    reflect = round(bottom - baseline, 2)

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


ARROW = ('<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#FEEFAB" '
         'stroke-width="3.429" stroke-linecap="round"><path d="M12 19V6M6.5 11.5 12 6l5.5 5.5">'
         '</path></svg>')


def figures():
    """Three readings, one line each: the pane's answer to the full screen's hung blocks."""
    return f'''      <div class="row">
        <div class="cap">В ПУТИ</div>
        <div class="val">1:42 · 128 км</div>
      </div>
      <div class="row">
        <div class="cap">ВЫСОТА</div>
        <div style="display:flex; align-items:baseline; gap:8px;">
          <div class="val">642 м</div>
          {ARROW}
          <div class="rate">1,2</div>
        </div>
      </div>
      <div class="row">
        <div class="cap" style="color:#FF9F19;">ЗАКАТ</div>
        <div class="val">19:44</div>
      </div>'''


def board(pane, src):
    body, data = analyser(pane)
    css = rules(src, ['.tile', '.on', '.off', '.nm', '.st', '.led'])
    bars = ',\n        '.join(
        '{h:%g,p:%g}' % (b['h'], b['p']) for b in data
    )

    if pane.column_width is None:
        panel = f'''  <div style="flex-grow:1; display:flex; flex-direction:column; gap:20px; min-height:0;">
    <div style="display:flex; flex-direction:column;">
{body}
    </div>
    <div class="rows">
{figures()}
    </div>
  </div>'''
        fold = f'''
  <div style="position:absolute; left:0; top:{WINDOW_H}px; width:{pane.margin}px; height:1px; background:rgba(254,239,171,0.35);"></div>
  <div style="position:absolute; right:0; top:{WINDOW_H}px; width:{pane.margin}px; height:1px; background:rgba(254,239,171,0.35);"></div>'''
    else:
        panel = f'''  <div style="flex-grow:1; display:flex; gap:32px; min-height:0;">
    <div style="flex-grow:1; display:flex; flex-direction:column; min-width:0;">
{body}
    </div>
    <div class="rows" style="width:{pane.column_width}px; flex-shrink:0; justify-content:center;">
{figures()}
    </div>
  </div>'''
        fold = ''

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
    .rows {{ display:flex; flex-direction:column; gap:{ROW_GAP}px; }}
    .row {{ height:{ROW}px; line-height:{ROW}px; display:flex; align-items:baseline; justify-content:space-between; }}
    .cap {{ font-size:15px; letter-spacing:1.6px; font-weight:500; color:#86909B; }}
    .val {{ font-size:24px; font-weight:300; color:#DAE1EB; }}
    .rate {{ font-size:19px; color:#FEEFAB; }}
  </style>
</helmet>
<div style="width:{pane.width}px; height:{pane.height}px; box-sizing:border-box; background:#07080A; display:flex; flex-direction:column; gap:{GAP}px; padding:{PAGE_TOP}px {pane.margin}px {PAGE_BOTTOM}px {pane.margin}px; position:relative;">

  <div style="display:grid; grid-template-columns:repeat({pane.columns}, minmax(0, 1fr)); gap:{GAP}px;">

{tiles(src)}

  </div>

{panel}{fold}
</div>
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":{pane.width},"height":{pane.height}}}}}'>
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
        fits = pane.height <= WINDOW_H
        print(
            f'{out}: {pane.width}x{pane.height}  content {pane.content}'
            f'  tile {(pane.content - (pane.columns - 1) * GAP) / pane.columns:.1f}'
            f'  {pane.columns}x{pane.rows}'
            f'  panel {pane.analyser_w:.0f}x{pane.panel_h}'
            f'  {"fits" if fits else f"scrolls {pane.height - WINDOW_H}"}'
        )
