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

FIELD_MAIN = 202.98          # the bar field on Main.dc.html
BAR_FRACTION = 0.7097        # SpectrumRenderer.BAR_WIDTH_FRACTION
BASELINE_FRACTION = 0.8319   # SpectrumRenderer.BASELINE_FRACTION
STRIP = 52.0                 # SpectrumRenderer.STRIP_UNITS - the ticker's band
PEAK = 4.0                   # SpectrumRenderer.PEAK_UNITS
REFLECT = 40.0               # SpectrumRenderer.REFLECT_UNITS - the reflection's own ceiling
REFLECT_FRACTION = 0.2       # SpectrumRenderer.REFLECT_FRACTION - a reflection is a fifth of its bar
REFLECT_FADE_START = 20      # SpectrumRenderer.REFLECT_FADE_START, in percent - where the floor starts
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
BLOCK = 72                   # one figure as a block: a 15 label, 8, a 46 figure - Main's own block
LABEL = 15
FIGURE = 46
VALUE = 24
RATE = 19
LINE = 12                    # Space.M, between a figure and its unit
LEAD = 8                     # Space.S, between a label and the figure under it

# Where a row's reading starts. The widest capital these rows can print is "ОСТАЛОСЬ" - 91.4 dp at
# 15 with the board's tracking - plus Space.L, rounded up. Measured from the drawn labels instead,
# the readings would slide sideways the moment a route started.
LABEL_COLUMN = 112

# The ticker is a still on a board and a marquee in the app, so a board may only print what fits.
LED_SIZE = 34
LED_TRACKING = 5
LED_ADVANCE = 0.6            # Roboto Mono advances 0.6 em, whatever the weight

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


def led_width(title):
    """What the ticker measures at, so a board cannot quietly print past its own edge."""
    return len(title) * (LED_SIZE * LED_ADVANCE + LED_TRACKING) - LED_TRACKING


# The full screen writes "M83 · MIDNIGHT CITY" and so does the app, which uppercases the artist
# and the title into one run. At 828 that run is 483 dp of 788 and the pane can say it. At 416 it
# is 483 of 392 and the app scrolls it - a marquee is not something a still can draw, so the
# narrow board prints the part that fits and says here that it is doing so.
MEDIUM = Pane('TwoThirds', 828, 20, FEATURES, 'across', 'M83 · MIDNIGHT CITY')
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
    # Lit or unlit, and the unlit one is MUTED_DEEP - what `DenzaChip.dotColour` draws for an
    # idle feature. This board had it at the track's own mark colour, which is nothing the code
    # ever draws here.
    dot = '#FEEFAB' if state == 'on' else '#7C858F'
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
    """The ticker, the bars, their segment grid and the fading reflection."""
    span = pane.content
    box = pane.analyser_h
    if led_width(pane.title) > span:
        sys.exit(f'{pane.name}: the ticker "{pane.title}" measures '
                 f'{led_width(pane.title):.0f} in {span:.0f} of content')
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
      <div style="height:{reflect}px; overflow:hidden; opacity:0.14; transform:scaleY(-1); position:relative;">
        <div style="display:flex; align-items:flex-end; gap:{bar_gap}px; height:{reflect}px;">
          <sc-for list="{{{{bars}}}}" as="b" hint-placeholder-count="{BANDS}">
            <div style="width:{bar_w}px; height: calc({{{{b.h}}}}px * {REFLECT_FRACTION:g}); background:linear-gradient(to top, #4A4222, #FEEFAB 62%, #FFF8DA);"></div>
          </sc-for>
        </div>
        <div style="position:absolute; left:0; right:0; top:0; bottom:0; background:linear-gradient(to top, rgba(7,8,10,0) {REFLECT_FADE_START:g}%, #07080A); pointer-events:none;"></div>
      </div>'''
    return body, data


# One arrow, one size, at every width. The narrow pane used to draw this at 14 beside the same 19
# its neighbours set their rate in, so one glyph came out two sizes on one screen.
ARROW = ('<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#FEEFAB" '
         'stroke-width="2.4" stroke-linecap="round"><path d="M12 19V6M6.5 11.5 12 6l5.5 5.5">'
         '</path></svg>')

# The rate sits against the reading it belongs to rather than hung off the far edge of the cell:
# pushed right it lands on the rule between two readings and reads as a column of its own.
RATE_RUN = (f'<div style="display:flex; align-items:center; gap:{LEAD}px;">'
            f'{ARROW}<div class="rate">1,2</div></div>')


def figures_across():
    """Three readings side by side with a rule between them, as the archived board set them.

    One anatomy for all three, and it is the full screen's: a tracked capital, then a 46 figure
    with its unit against it. The sunset used to be a caption in the vehicle's own amber - the
    colour the car draws when it wants a decision from the driver - at a smaller size, so the one
    coloured caption on the screen was the one saying the sun goes down at the usual time.
    """
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
          {RATE_RUN}
        </div>
      </div>
      <div class="rule"></div>
      <div class="fig">
        <div class="cap">ЗАКАТ</div>
        <div class="line">
          <div class="num">19:44</div>
        </div>
      </div>'''


def figures_rows():
    """The same three where there is no width to set them side by side.

    The readings share a left edge. They used to be hung off the right of the pane, which lines the
    last character of a clock up with the last character of a distance and puts the digits that
    matter in three different places - three readings the eye cannot compare, which is the one
    thing a stack of rows is for.
    """
    return f'''      <div class="row">
        <div class="cap">В ПУТИ</div>
        <div class="line">
          <div class="val">1:42 · 128 км</div>
        </div>
      </div>
      <div class="row">
        <div class="cap">ВЫСОТА</div>
        <div class="line">
          <div class="val">642 м</div>
          {RATE_RUN}
        </div>
      </div>
      <div class="row">
        <div class="cap">ЗАКАТ</div>
        <div class="line">
          <div class="val">19:44</div>
        </div>
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
    .fig {{ flex:1; min-width:0; display:flex; flex-direction:column; gap:{LEAD}px; }}
    /* Half the group gap either side, so the hairline has Space.XL of clear air across it. */
    .rule {{ width:1px; background:rgba(218,225,235,0.14); margin:0 {GROUP // 2}px; }}
    .line {{ display:flex; align-items:baseline; gap:{LINE}px; }}
    .num {{ font-size:{FIGURE}px; font-weight:200; color:#DAE1EB; line-height:1; }}
    .un {{ font-size:{VALUE}px; color:#86909B; }}
    .rows {{ display:flex; flex-direction:column; gap:{GAP}px; }}
    .row {{ height:{ROW}px; line-height:{ROW}px; display:flex; align-items:baseline; gap:{LINE}px; }}
    .cap {{ font-size:{LABEL}px; letter-spacing:1.6px; font-weight:500; color:#86909B; }}
    /* The label's box plus the row's own gap is where every reading starts: {LABEL_COLUMN}. */
    .row .cap {{ width:{LABEL_COLUMN - LINE}px; flex-shrink:0; }}
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


# --- the dashboard behind a sheet --------------------------------------------
#
# A settings panel and the default-apps sheet are drawn over the dashboard, and both boards used to
# carry their own copy of it. Config's was a real copy of `Main.dc.html` at the moment it was
# pasted; DefaultApps' was a mock - three tiles in the wrong order, three empty boxes where the
# other eight go, no second row, and three grey bars where the analyser is - so the board answering
# "what does this cover" was showing a screen that has never existed.
#
# So the underlay is emitted here, from the same file the panes are emitted from, into a marked
# region of each sheet board. What sits over it is still drawn by hand: only the thing behind the
# scrim is generated, and it can no longer disagree with the screen it is a picture of.

SHEETS = ('Config.dc.html', 'DefaultApps.dc.html')

CSS_BEGIN = '    /* underlay: generated from Main.dc.html by gen_panes.py */'
CSS_END = '    /* end underlay */'
BODY_BEGIN = '  <!-- underlay: generated from Main.dc.html by gen_panes.py -->'
BODY_END = '  <!-- end underlay -->'

# One recipe for one scrim, and it is `DenzaColors.Scrim` - the ground at 0.72, which is what every
# modal surface in the app is drawn over. The two boards had two: 0.30 opacity under black at 0.55,
# and 0.48 under black at 0.54. Three depths of dark mean the screen behind a window changes
# brightness depending on which window opened, which reads as the dashboard flickering.
SCRIM = 'rgba(7,8,10,0.72)'

UNDERLAY_CLASSES = ('.tile', '.on', '.off', '.nm', '.st', '.led',
                    '.cap', '.num', '.un', '.rate', '.fig', '.line', '.ruled')


def expand_loops(body):
    """Run the board's own `sc-for` over `BARS`.

    The underlay is a picture behind a scrim, so it carries its columns already expanded rather
    than a loop and a data block: a sheet board then needs no `data-dc-script` of its own, and
    `shot.py` cannot report holes in something that has no loops.
    """
    def one(match):
        template = match.group(1)
        rows = []
        for h, p in BARS:
            row = template.replace('{{b.h}}', f'{h:g}').replace('{{b.p}}', f'{p:g}')
            rows.append(row.strip())
        return '\n            '.join(rows)

    return re.sub(r'<sc-for[^>]*>(.*?)</sc-for>', one, body, flags=re.S)


def underlay(src):
    """The dashboard as it stands, scoped to `.underlay`, plus the one scrim over it."""
    rules_out = []
    for selector in UNDERLAY_CLASSES:
        found = re.search(r'^\s*(' + re.escape(selector) + r') \{([^\n]*)$', src, re.M)
        if not found:
            sys.exit(f'{MAIN} has no {selector} rule; gen_panes.py cannot scope the underlay')
        rules_out.append(f'    .underlay {selector} {{{found.group(2).strip()}')
    css = '\n'.join(rules_out) + (
        '\n    .underlay { position:absolute; left:0; top:0; right:0; bottom:0; }'
        f'\n    .underlay-scrim {{ position:absolute; left:0; top:0; right:0; bottom:0; '
        f'background:{SCRIM}; }}')

    body = re.search(r'</helmet>\s*(<div style="width:1280px.*?)\s*</x-dc>', src, re.S)
    if not body:
        sys.exit(f'{MAIN} has no artboard div; gen_panes.py cannot lift the underlay')
    art = expand_loops(body.group(1))
    art = '\n'.join('  ' + line if line.strip() else line for line in art.split('\n'))
    return css, f'  <div class="underlay">\n{art}\n  </div>\n  <div class="underlay-scrim"></div>'


def splice(text, begin, end, block, path):
    start = text.find(begin)
    stop = text.find(end)
    if start < 0 or stop < 0 or stop < start:
        sys.exit(f'{path} has no {begin.strip()} region')
    return text[:start] + begin + '\n' + block + '\n' + text[stop:]


def write_underlays(src):
    css, body = underlay(src)
    for path in SHEETS:
        text = open(path, encoding='utf-8').read()
        text = splice(text, CSS_BEGIN, CSS_END, css, path)
        text = splice(text, BODY_BEGIN, BODY_END, body, path)
        open(path, 'w', encoding='utf-8').write(text)
        print(f'{path}: underlay from {MAIN}, scrim {SCRIM}')


# --- the density board -------------------------------------------------------

DENSITY_COUNTS = (10, 11, 12, 13)
# The frame, kept in step with `canvas.json`. Declared rather than left to the content, because
# `shot.py` otherwise falls back to the first `width:Npx; height:Npx` in the body - which on this
# board is a 7 px dot.
DENSITY_W, DENSITY_H = 1360, 899
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
                '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#7C858F" '
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
        body.append(f'''  <div style="display:flex; flex-direction:column; gap:{GAP}px;">
    <div style="display:flex; align-items:baseline; gap:{GAP}px;">
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
<div style="width:{DENSITY_W}px; height:{DENSITY_H}px; box-sizing:border-box; background:#07080A; display:flex; flex-direction:column; gap:48px; padding:48px;">
{chr(10).join(body)}
</div>
</x-dc>
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
    write_underlays(src)
    open('ChipDensity.dc.html', 'w', encoding='utf-8').write(density_board(src))
    print(f'ChipDensity.dc.html: {DENSITY_COUNTS} features, both pane widths')
