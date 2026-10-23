#!/usr/bin/env python3
"""
Lay an emulator screenshot of the ADAS overlay over its bare board and say where they differ.

    python3 compare.py bare-active shot.png

The method is luminofor/compare.py's: both images are the display's own pixels (2560 x 1600,
density 2), the board is the overlay alone on black at the bare anchor and the screenshot is the
module's emulator fixture of the same state on the same black at the same anchor (emulator.py
takes them). Only the overlay's neighbourhood is compared - the union of what is lit in either
image, 24 px wider - so a share of moved pixels means a share of the overlay, not of the screen.

Writes captures/adas-overlay-design/compare/<id>.png - the board, the app and a heat map, stacked
- and prints the share of pixels that moved, the mean difference and the worst 16 px cells.
Antialiasing between Chrome's Skia and Android's is never identical: a few levels along every edge
are expected. A cell that moved is not.
"""
import os
import sys

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
DESIGN = os.path.join(REPO, 'captures', 'adas-overlay-design')
OUT = os.path.join(DESIGN, 'compare')
CELL = 16
EDGE = 24     # a pixel differs when a channel moved by more than this
LIT = 12      # a pixel belongs to the overlay when a channel is above this
PAD = 24


def lit_box(im):
    return im.point(lambda v: 255 if v > LIT else 0).convert('L').getbbox()


def compare(bid, shot):
    board = Image.open(os.path.join(DESIGN, bid + '.png')).convert('RGB')
    app = Image.open(shot).convert('RGB').crop((0, 0, board.width, board.height))
    # the app's light is looked for only near the board's: the system bars are lit too
    bb = lit_box(board)
    near = (max(0, bb[0] - 120), max(0, bb[1] - 120), min(board.width, bb[2] + 120), min(board.height, bb[3] + 60))
    ab = lit_box(app.crop(near))
    boxes = [bb] + ([(ab[0] + near[0], ab[1] + near[1], ab[2] + near[0], ab[3] + near[1])] if ab else [])
    x0 = max(0, min(b[0] for b in boxes) - PAD); y0 = max(0, min(b[1] for b in boxes) - PAD)
    x1 = min(board.width, max(b[2] for b in boxes) + PAD); y1 = min(board.height, max(b[3] for b in boxes) + PAD)
    board, app = board.crop((x0, y0, x1, y1)), app.crop((x0, y0, x1, y1))
    diff = ImageChops.difference(board, app)
    px = diff.load()
    w, h = board.size
    moved = total = 0
    cells = {}
    for yy in range(h):
        for xx in range(w):
            m = max(px[xx, yy])
            total += m
            if m > EDGE:
                moved += 1
                key = (xx // CELL, yy // CELL)
                cells[key] = cells.get(key, 0) + 1
    n = w * h
    os.makedirs(OUT, exist_ok=True)
    stack = Image.new('RGB', (w, h * 3))
    stack.paste(board, (0, 0)); stack.paste(app, (0, h)); stack.paste(diff.point(lambda v: min(255, v * 4)), (0, 2 * h))
    out = os.path.join(OUT, bid + '.png')
    stack.save(out)
    worst = sorted(cells.items(), key=lambda kv: -kv[1])[:6]
    near = within_one(board, app)
    print(f'{bid}: {moved / n * 100:.2f}% pixels moved ({near * 100:.2f}% beyond 1 px), mean {total / n:.2f} levels, '
          f'region {x0},{y0} {w}x{h}  ->  {out}')
    for (cx, cy), count in worst:
        print(f'   cell at {x0 + cx * CELL}, {y0 + cy * CELL}  ({count} px of {CELL * CELL})')
    return moved / n, near, total / n, out


def within_one(board, app):
    """The share of pixels that match no board pixel within one pixel either way: a word set a
    pixel wider by the other rasteriser is not a layout fault, a word in the wrong place is."""
    w, h = board.size
    best = None
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            shifted = Image.new('RGB', (w, h))
            shifted.paste(board, (dx, dy))
            d = ImageChops.difference(shifted, app).convert('L')
            best = d if best is None else ImageChops.darker(best, d)
    hist = best.point(lambda v: 255 if v > EDGE else 0).histogram()
    return hist[255] / (w * h)


if __name__ == '__main__':
    compare(sys.argv[1], sys.argv[2])
