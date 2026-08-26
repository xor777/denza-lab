#!/usr/bin/env python3
"""
Put a board and the car beside each other and say where they differ.

"Pixel perfect" was asked for, and until now the only instrument for it was
somebody looking. That instrument has a measured failure rate on this project: the
first cut of the head-unit screen carried every number off the board and looked
nothing like it, and it took the owner to say so. Looking finds the difference it
was already expecting.

So this renders the board at the car's own scale, lines the two up, and reports
where they disagree - not pixel by pixel, which would only ever report font
antialiasing, but over a grid of cells whose mean colour has actually moved. A cell
that shifts is a layout or a colour that shifted. A glyph rasterised differently by
Skia than by Chrome is not.

Run: python3 compare.py <Board> --car shot.png [--cell 16] [--worst 12]
     python3 compare.py <Board> --capture          # pulls the screen off the car

`--capture` touches the vehicle. Every other mode is offline and reads a PNG you
already have.

It writes `_shots/<Board>.compare.png` - board, car, and the heat between them -
and prints the cells that moved most, in board coordinates, so a complaint like
"the strip is wrong" comes back as a row range to go and look at.
"""
import argparse
import os
import subprocess
import sys

from PIL import Image

import shot

HERE = shot.HERE
SHOTS = shot.SHOTS
# Anything under this has never once been a real difference on these boards: it is
# Skia and Chrome disagreeing about the same glyph at the same size.
NOISE = 6.0


def capture(path):
    """The car's own framebuffer. The one call in this file that touches the vehicle."""
    png = subprocess.run(['adb', 'exec-out', 'screencap', '-p'],
                         check=True, capture_output=True).stdout
    open(path, 'wb').write(png)
    return path


def cells(image, cell):
    """Mean colour per cell, as a flat list of (col, row, r, g, b)."""
    w, h = image.size
    small = image.resize((max(1, w // cell), max(1, h // cell)), Image.BOX)
    out = []
    for row in range(small.height):
        for col in range(small.width):
            r, g, b = small.getpixel((col, row))[:3]
            out.append((col, row, r, g, b))
    return out, small


def compare(board, car_png, cell, worst):
    car = Image.open(car_png).convert('RGB')
    scale = car.width / frame_width(board)
    shot.shoot(board, scale)
    ref = Image.open(os.path.join(SHOTS, board + '.png')).convert('RGB')

    note = ''
    if ref.size != car.size:
        note = (f'  board {ref.width}x{ref.height} against car {car.width}x{car.height} '
                f'- the car image was scaled to the board to compare at all')
        car = car.resize(ref.size, Image.LANCZOS)

    a, small_a = cells(ref, cell)
    b, small_b = cells(car, cell)

    deltas = []
    for (col, row, r1, g1, b1), (_, _, r2, g2, b2) in zip(a, b):
        d = ((r1 - r2) ** 2 + (g1 - g2) ** 2 + (b1 - b2) ** 2) ** 0.5
        deltas.append((d, col, row))

    moved = [d for d in deltas if d[0] > NOISE]
    deltas.sort(reverse=True)

    heat = Image.new('RGB', small_a.size, (0, 0, 0))
    top = max((d[0] for d in deltas), default=1.0) or 1.0
    for d, col, row in deltas:
        v = 0 if d <= NOISE else int(255 * min(1.0, d / top))
        heat.putpixel((col, row), (v, int(v * 0.35), 0))
    heat = heat.resize(ref.size, Image.NEAREST)

    panel = Image.new('RGB', (ref.width * 3 + 48, ref.height), (7, 8, 10))
    panel.paste(ref, (0, 0))
    panel.paste(car, (ref.width + 24, 0))
    panel.paste(heat, (ref.width * 2 + 48, 0))
    out = os.path.join(SHOTS, f'{board}.compare.png')
    panel.save(out)

    print(f'{board}: {len(moved)} of {len(deltas)} cells moved '
          f'({100.0 * len(moved) / max(1, len(deltas)):.1f}%), cell {cell}px')
    if note:
        print(note)
    print(f'  {out}')
    if not moved:
        print('  nothing above the noise floor - these are the same picture')
        return 0
    print(f'  worst {min(worst, len(deltas))}, in board pixels:')
    for d, col, row in deltas[:worst]:
        if d <= NOISE:
            break
        print(f'    x {col * cell:5d}-{(col + 1) * cell:5d}  '
              f'y {row * cell:5d}-{(row + 1) * cell:5d}   d {d:6.1f}')
    return 0


def frame_width(board):
    src = open(os.path.join(HERE, board + '.dc.html'), encoding='utf-8').read()
    body = src.split('</helmet>', 1)[1].split('</x-dc>', 1)[0]
    return shot.frame(board, body)[0]


if __name__ == '__main__':
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument('board')
    ap.add_argument('--car', help='a PNG of the screen to compare against')
    ap.add_argument('--capture', action='store_true', help='pull it off the car over adb')
    ap.add_argument('--cell', type=int, default=16)
    ap.add_argument('--worst', type=int, default=12)
    args = ap.parse_args()

    if args.capture:
        os.makedirs(SHOTS, exist_ok=True)
        args.car = capture(os.path.join(SHOTS, f'{args.board}.car.png'))
    if not args.car:
        sys.exit('give it --car <png>, or --capture to take one off the vehicle')
    sys.exit(compare(args.board, args.car, args.cell, args.worst))
