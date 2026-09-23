#!/usr/bin/env python3
"""
Lay a screenshot of the app's «Бригада» over the approved page's frame and say where they differ.

    python3 compare.py 2150 app.png              ../_shots/split-crew/t2150.png against app.png
    python3 compare.py 2150 app.png --out DIR    the side-by-side goes to DIR instead

The frame comes from `shot.py`, the screenshot from the debug build's fixture activity, which draws
`SplitCrewView` pinned at the same moment over the whole 2560 x 1600 screen, so the two images are
the same size and the same pixels should be the same. Writes <out>/t<ms>.png - the page and the
app side by side, and under them the difference four times amplified beside the two laid half over
each other - and prints the share of pixels that moved, the mean difference, and the worst cells
of a 16 px grid in dp, which is where to look.

Antialiasing is never identical between Chrome's Skia and the car's, and the caption is Roboto from
`../luminofor/fonts` on the page and the system's sans in the app: a difference of a few levels
along every edge is expected and is not a fault. A cell that moved is.
"""
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'split-crew')

CELL = 16
EDGE = 24  # a pixel differs when a channel moved by more than this
SCALE = 2.0  # px per dp on the car


def main(ms, shot, out):
    board = Image.open(os.path.join(SHOTS, 't%d.png' % ms)).convert('RGB')
    app = Image.open(shot).convert('RGB').crop((0, 0, board.width, board.height))
    a = np.asarray(board, dtype=np.int16)
    b = np.asarray(app, dtype=np.int16)
    diff = np.abs(a - b).max(axis=2)
    h, w = diff.shape
    moved = diff > EDGE
    print('t%d: %.2f%% pixels moved, mean %.2f levels, max %d' % (ms, moved.mean() * 100, diff.mean(), diff.max()))
    cells = moved[:h - h % CELL, :w - w % CELL].reshape(h // CELL, CELL, w // CELL, CELL).sum(axis=(1, 3))
    order = np.argsort(cells, axis=None)[::-1][:8]
    for idx in order:
        cy, cx = divmod(int(idx), cells.shape[1])
        if cells[cy, cx] == 0:
            break
        print('   cell at %7.1f, %6.1f dp  (%d px of %d)' % (cx * CELL / SCALE, cy * CELL / SCALE, cells[cy, cx], CELL * CELL))

    heat = Image.fromarray(np.clip(np.abs(a - b) * 4, 0, 255).astype(np.uint8))
    blend = Image.blend(board, app, 0.5)
    sheet = Image.new('RGB', (w * 2, h * 2))
    sheet.paste(board, (0, 0))
    sheet.paste(app, (w, 0))
    sheet.paste(heat, (0, h))
    sheet.paste(blend, (w, h))
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, 't%d.png' % ms)
    sheet.save(path)
    print('   ->', os.path.abspath(path))


if __name__ == '__main__':
    args = sys.argv[1:]
    out = os.path.join(SHOTS, 'compare')
    if '--out' in args:
        i = args.index('--out')
        out = args[i + 1]
        del args[i:i + 2]
    main(int(args[0]), args[1], out)
