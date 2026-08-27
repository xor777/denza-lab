#!/usr/bin/env python3
"""
Historical frame for the four retired head-unit instrument concepts, and the
archived Energy board rebuilt on it.

The audit measured the four 1192x400 boards a reader flips between and found
four different grids: root gaps of 28/26/20/24, right insets of 8/0/6/0, band
heights of 330/300/310/300, and a 29-pixel spread in where the first label
starts - so the headers jump when you turn the page. Two of them had no
horizontal padding at all, which put `высоковольтный контур цел` exactly on the
board's edge.

This states the frame once and rewrites each board's root against it. Energy is
rebuilt outright: its dial was the one the audit found internally impossible -
two marks at mirrored angles both labelled 60, tick radii crossing a 14-pixel
track at three different depths, and a "zero" mark drawn horizontal 231 pixels
off the track, floating inside the chart.
"""
import os
import re
import sys

import gen_cluster as g

HERE = os.path.dirname(os.path.abspath(__file__))

PANEL_W, PANEL_H, PAD, GAP = 1192, 400, 24, 24

ROOT = (f'<div style="width:{PANEL_W}px; height:{PANEL_H}px; box-sizing:border-box; '
        f'background:#07080A; display:flex; align-items:stretch; gap:{GAP}px; '
        f'padding:{PAD}px;">')

BARS = g.DRIVING_BARS
BUCKET_KM = g.BUCKET_KM

# The archived Energy concept showed a selector and is drawn at its widest
# historical choice. The current cluster has one fixed 3 km window instead.
WINDOW = 'LONG'


def chips(selected):
    """The archived window selector: one row, one selected, every chip the same height.

    The selected chip is filled rather than thicker, which is the rule the whole
    set follows now - a two-pixel border made the selected card two pixels taller
    than its neighbours and staggered the row it sat in.
    """
    out = []
    for name, _km, label in g.WINDOWS:
        on = name == selected
        style = ('background:#FEEFAB; color:#262D33; font-weight:500;' if on
                 else 'background:#212429; color:#C5CDD9;')
        out.append(f'<div style="border-radius:12px; padding:10px 18px; font-size:19px; '
                   f'border:1px solid rgba(218,225,235,0.16); {style}">{label}</div>')
    return ''.join(out)


def ru(value, digits):
    return f'{value:.{digits}f}'.replace('.', ',')


def energy_board():
    km, label = g.window_by_name(WINDOW)
    tail = BARS[-int(round(km / BUCKET_KM)):]
    bars, caption, avg, _ = g.chart_for(WINDOW)
    spent = sum(v for v in tail if v > 0) * BUCKET_KM / 100
    back = sum(-v for v in tail if v < 0) * BUCKET_KM / 100
    peak = max(tail)
    dial = g.gauge_block(640, 320, 291, 268, g.PANEL, 34.0, bars, caption)

    def stat(title, value, unit):
        return (f'<div style="border-top:1px solid rgba(218,225,235,0.14); padding-top:12px; '
                f'display:flex; flex-direction:column; gap:6px;">'
                f'<div class="lbl">{title}</div>'
                f'<div style="display:flex; align-items:baseline; gap:10px;">'
                f'<div class="val">{value}</div><div class="un">{unit}</div></div></div>')
    return f'''<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@200;300;400;500&amp;family=Roboto+Mono:wght@300;400&amp;display=swap" rel="stylesheet">
  <style>
    body {{ margin:0; background:#07080A; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }}
    a {{ color:#FEEFAB; }} a:hover {{ color:#FFF7D2; }}
    .lbl {{ font-size:15px; letter-spacing:0.12em; font-weight:500; color:#86909B; }}
    .hero {{ font-size:46px; font-weight:200; color:#DAE1EB; line-height:1; }}
    .val {{ font-size:34px; font-weight:200; color:#DAE1EB; line-height:1; }}
    .un {{ font-size:19px; color:#86909B; }}
    .fg {{ font-family:'Roboto Mono',monospace; font-weight:300; font-size:62px; fill:#DAE1EB; }}
    .rd {{ font-family:'Roboto Mono',monospace; font-weight:300; font-size:34px; fill:#DAE1EB; }}
    .un-s {{ font-size:19px; fill:#86909B; }}
    .bd {{ font-size:19px; fill:#6E767F; }}
    .tk {{ font-family:'Roboto Mono',monospace; font-size:15px; fill:#6E767F; }}
  </style>
</helmet>
{ROOT}

  <div style="width:640px; flex-shrink:0; display:flex; align-items:center;">
    {dial}
  </div>

  <div style="flex-grow:1; display:flex; flex-direction:column; justify-content:space-between;">

    <div style="display:flex; flex-direction:column; gap:8px;">
      <div class="lbl">РАСХОД · ПОСЛЕДНИЕ 300 М</div>
      <div style="display:flex; align-items:baseline; gap:12px;">
        <div class="hero">16,8</div>
        <div class="un">кВт·ч/100 км</div>
      </div>
    </div>

    {stat('СРЕДНИЙ ЗА ОКНО', ru(avg, 1), 'кВт·ч/100 км')}
    {stat('ПОТРАЧЕНО', ru(spent - back, 2), f'кВт·ч · вернулось {ru(back, 2)}')}
    {stat('ХУДШИЙ УЧАСТОК', ru(peak, 0), 'кВт·ч/100 км')}

    <div style="border-top:1px solid rgba(218,225,235,0.14); padding-top:12px;
      display:flex; flex-direction:column; gap:8px;">
      <div class="lbl">ОКНО · {len(bars)} × {ru(km / len(bars), 1)} км</div>
      <div style="display:flex; gap:10px;">{chips(WINDOW)}</div>
    </div>
  </div>
</div>
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":{PANEL_W},"height":{PANEL_H}}}}}'>
class Component extends DCLogic {{ renderVals() {{ return {{}}; }} }}
</script>
</body>
</html>
'''


def reframe(name):
    """Put an existing panel board on the shared frame."""
    path = os.path.join(HERE, name + '.dc.html')
    src = open(path).read()
    src = re.sub(r'<div style="width:1192px; height:400px;[^"]*">', ROOT, src, count=1)
    # The two boards that carried no horizontal padding ran their ink onto the
    # board edge; the frame supplies it now, so their own right inset must go.
    src = re.sub(r'\s*padding-right:\s*\d+px;', '', src)
    src = re.sub(r'height:3[0-9]0px;\s*', '', src)
    open(path, 'w').write(src)


if __name__ == '__main__':
    open(os.path.join(HERE, 'Energy.dc.html'), 'w').write(energy_board())
    for n in ('Battery', 'Thermal', 'Engine'):
        reframe(n)
    print('Energy rebuilt; Battery, Thermal, Engine reframed')
