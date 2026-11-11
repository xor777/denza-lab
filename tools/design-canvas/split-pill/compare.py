#!/usr/bin/env python3
"""
Lay a screenshot of the app's split pill over a board of the approved page and say where they differ.

    python3 compare.py open app.png                 ../_shots/split-pill/open.png against app.png
    python3 compare.py opening-t160 app.png         a timed frame the same way
    python3 compare.py open app.png --out DIR       the side-by-side goes to DIR instead

The board comes from `shot.py`, the screenshot from the app drawing the same state over the whole
2560 x 1600 screen, so the two images are the same size and the same pixels should be the same.
Writes <out>/<board>.png - the page and the app side by side, and under them the difference four
times amplified beside the two laid half over each other - and prints the share of pixels that
moved, the mean difference, and the worst cells of a 16 px grid in dp, which is where to look.

Only the pill, the handle, the flights and the launch placeholders are the product: what the panes
show, the dock and the home screen are the stand's, and the app's own will differ. Read the cells
on the pill and its icons. Antialiasing is never identical between Chrome's Skia and the car's: a
difference of a few levels along every edge is expected and is not a fault. A cell that moved is.
"""
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'split-pill')

CELL = 16
EDGE = 24  # a pixel differs when a channel moved by more than this
SCALE = 2.0  # px per dp on the car


def main(name, shot, out):
    board = Image.open(os.path.join(SHOTS, '%s.png' % name)).convert('RGB')
    app = Image.open(shot).convert('RGB').crop((0, 0, board.width, board.height))
    a = np.asarray(board, dtype=np.int16)
    b = np.asarray(app, dtype=np.int16)
    diff = np.abs(a - b).max(axis=2)
    h, w = diff.shape
    moved = diff > EDGE
    print('%s: %.2f%% pixels moved, mean %.2f levels, max %d' % (name, moved.mean() * 100, diff.mean(), diff.max()))
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
    path = os.path.join(out, '%s.png' % name)
    sheet.save(path)
    print('   ->', os.path.abspath(path))


if __name__ == '__main__':
    args = sys.argv[1:]
    out = os.path.join(SHOTS, 'compare')
    if '--out' in args:
        i = args.index('--out')
        out = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    main(args[0], args[1], out)
