#!/usr/bin/env python3
"""
One frame for the four instrument pages, and the Energy page rebuilt on it.

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
BUCKET_KM = 0.2


def ru(value, digits):
    return f'{value:.{digits}f}'.replace('.', ',')


def energy_board():
    avg = g.average_consumption(BARS)
    spent = sum(v for v in BARS if v > 0) * BUCKET_KM / 100
    back = sum(-v for v in BARS if v < 0) * BUCKET_KM / 100
    peak = max(BARS)
    caption = f'{ru(avg, 1)} средний за {ru(len(BARS) * BUCKET_KM, 1)} км'
    dial = g.gauge_block(700, 350, 291, 268, g.PANEL, 34.0, BARS, caption)

    row = ('<div style="border-top:1px solid rgba(218,225,235,0.14); padding-top:18px; '
           'display:flex; align-items:baseline; gap:14px;">')
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
    .hero {{ font-size:62px; font-weight:200; color:#DAE1EB; line-height:1; }}
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

  <div style="width:700px; flex-shrink:0; display:flex; align-items:center;">
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

    {row}
      <div class="lbl" style="width:150px;">СРЕДНИЙ</div>
      <div class="val">{ru(avg, 1)}</div>
      <div class="un">за {ru(len(BARS) * BUCKET_KM, 1)} км</div>
    </div>

    {row}
      <div class="lbl" style="width:150px;">ПОТРАЧЕНО</div>
      <div class="val">{ru(spent - back, 2)}</div>
      <div class="un">кВт·ч · вернулось {ru(back, 2)}</div>
    </div>

    {row}
      <div class="lbl" style="width:150px;">ХУДШИЙ УЧАСТОК</div>
      <div class="val">{peak}</div>
      <div class="un">кВт·ч/100 км</div>
    </div>

    {row}
      <div class="lbl" style="width:150px;">ОКНО</div>
      <div class="un" style="color:#C5CDD9;">{len(BARS)} участка по {int(BUCKET_KM * 1000)} м</div>
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
