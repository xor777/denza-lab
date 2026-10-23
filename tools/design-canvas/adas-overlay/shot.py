#!/usr/bin/env python3
"""
Render the ADAS overlay boards (phase 1: mockups) to captures/adas-overlay-design/<id>.png.

    python3 shot.py                  every board
    python3 shot.py full-03-noa-active pair-1-route

The overlay is drawn by adas.js in Luminofor's verbs and colours (../luminofor/spec.json) over
real Navigator frames from captures/ (git-ignored); boards.js says which frame, which state and
where, compose.js lays a board out. Fonts and headless Chrome are luminofor/shot.py's.
"""
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
OUT = os.path.join(REPO, 'captures', 'adas-overlay-design')
sys.path.insert(0, os.path.join(HERE, '..', 'luminofor'))
import shot as lum  # noqa: E402  font_css(), chrome()


def read(*parts):
    with open(os.path.join(HERE, *parts), encoding='utf-8') as f:
        return f.read()


def board_ids():
    src = read('boards.js')
    block = src[src.index('const BOARDS'):src.index('const SIZE')]
    return [line.strip()[1:line.strip().index("'", 1)] for line in block.splitlines()
            if line.strip().startswith("'") and "':" in line]


def page(bid):
    return ('<!doctype html><html><head><meta charset="utf-8">'
            f'<style>{lum.font_css()}html,body{{margin:0;background:#000;overflow:hidden}}canvas{{display:block}}</style>'
            '</head><body><canvas id="c"></canvas>'
            f'<script>window.LUMINOFOR_SPEC = {read("..", "luminofor", "spec.json")};</script>'
            f'<script>{read("adas.js")}</script>'
            f'<script>{read("boards.js")}</script>'
            f'<script>{read("compose.js")}</script>'
            '<script>(async function () {'
            "  const faces = ['400 20px Roboto', '500 20px Roboto'], sample = 'Aa0 АБВабвё·→';"
            '  for (let i = 0; i < 60; i++) {'
            '    try { await Promise.all(faces.map(f => document.fonts.load(f, sample))); await document.fonts.ready; } catch (e) {}'
            '    if (faces.every(f => document.fonts.check(f, sample))) break;'
            '    await new Promise(r => setTimeout(r, 100));'
            '  }'
            f"  await window.composeBoard(document.getElementById('c'), {json.dumps(bid)}, {json.dumps('file://' + REPO)});"
            '})();</script></body></html>')


def size(bid):
    # mirror of SIZE in boards.js, so the window matches the canvas
    if bid.startswith(('full-', 'two-')):
        return 2560, 1600
    if bid.startswith('one-'):
        return 832, 1358
    if bid == 'sheet-states':
        return 2560, 1840
    if bid == 'choice-2-detail':
        return 2560, 600
    if bid == 'choice-1-placement':
        return 2560, 906
    return 2560, 1400


def shoot(bid):
    w, h = size(bid)
    os.makedirs(OUT, exist_ok=True)
    out = os.path.join(OUT, bid + '.png')
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
        f.write(page(bid))
        path = f.name
    try:
        lum.chrome(['--allow-file-access-from-files', f'--window-size={w},{h}', '--virtual-time-budget=10000',
                    f'--screenshot={out}', 'file://' + path])
    finally:
        os.unlink(path)
    print(out, f'{w}x{h}')


if __name__ == '__main__':
    for bid in (sys.argv[1:] or board_ids()):
        shoot(bid)
