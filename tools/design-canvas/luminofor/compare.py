#!/usr/bin/env python3
"""
Lay a screenshot of the app over its Luminofor board and say where they differ.

    python3 compare.py main-sound app.png              the board's own size, from the top-left
    python3 compare.py cluster-city app.png --at 0,0   where the board sits in the screenshot

The screenshot comes from the debug fixture activity, which draws a board's fixture at the
board's exact pixel size in the top-left corner of the display, so the two images are the same
size and the same pixels should be the same. Writes ../_shots/luminofor/compare/<id>.png - the
board, the app and a heat map of the difference, stacked - and prints the share of pixels that
moved, the mean difference, and the worst cells of a 16 px grid in board units (dp on the head
unit, cluster units on the cluster), which is where to look.

Antialiasing is never identical between Chrome's Skia and the car's: a difference of a few
levels along every edge is expected and is not a layout fault. A cell that moved is.
"""
import os
import sys

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'luminofor')
OUT = os.path.join(SHOTS, 'compare')

CELL = 16
EDGE = 24  # a pixel differs when a channel moved by more than this


def unit_scale(bid):
    return 720 / 424 if bid.startswith('cluster') else 2.0


def main(bid, shot, at=(0, 0)):
    # a cluster is compared with its bare board: the hatching is the board's, not the app's
    bare = os.path.join(SHOTS, bid + '.bare.png')
    board = Image.open(bare if os.path.exists(bare) else os.path.join(SHOTS, bid + '.png')).convert('RGB')
    app = Image.open(shot).convert('RGB')
    x, y = at
    app = app.crop((x, y, x + board.width, y + board.height))
    diff = ImageChops.difference(board, app)
    px = diff.load()
    w, h = board.size
    moved = 0
    total = 0
    cells = {}
    for yy in range(h):
        for xx in range(w):
            r, g, b = px[xx, yy]
            m = max(r, g, b)
            total += m
            if m > EDGE:
                moved += 1
                key = (xx // CELL, yy // CELL)
                cells[key] = cells.get(key, 0) + 1
    n = w * h
    heat = diff.point(lambda v: min(255, v * 4))
    os.makedirs(OUT, exist_ok=True)
    stack = Image.new('RGB', (w, h * 3))
    stack.paste(board, (0, 0))
    stack.paste(app, (0, h))
    stack.paste(heat, (0, 2 * h))
    out = os.path.join(OUT, bid + '.png')
    stack.save(out)
    s = unit_scale(bid)
    print(f'{bid}: {moved / n * 100:.2f}% pixels moved, mean {total / n:.2f} levels  ->  {out}')
    worst = sorted(cells.items(), key=lambda kv: -kv[1])[:12]
    for (cx, cy), count in worst:
        print(f'   cell at {cx * CELL / s:7.1f}, {cy * CELL / s:6.1f}  ({count} px of {CELL * CELL})')


if __name__ == '__main__':
    args = sys.argv[1:]
    at = (0, 0)
    if '--at' in args:
        i = args.index('--at')
        at = tuple(int(v) for v in args[i + 1].split(','))
        del args[i:i + 2]
    main(args[0], args[1], at)
