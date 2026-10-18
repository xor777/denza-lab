#!/usr/bin/env python3
"""
Render the proposal boards of proposals/*.js with Luminofor's own renderer, without touching
fixtures.js: each proposal file is appended to fixtures.js in the page, adds its boards to
LUMINOFOR_BOARDS, and ../shot.py shoots them to ../../_shots/luminofor/<id>.png.

    python3 proposals/shot.py hud-system            every board the proposal adds
    python3 proposals/shot.py hud-system sheet-hudsys-yandex
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import shot  # noqa: E402  (../shot.py)


def main():
    name, wanted = sys.argv[1], sys.argv[2:]
    with open(os.path.join(HERE, name + '.js'), encoding='utf-8') as f:
        proposal = f.read()
    read = shot.read

    def patched(file):
        text = read(file)
        if file == 'fixtures.js':
            return text + '\n' + proposal
        # a block the proposal draws itself ('glass'), until it moves into luminofor.js
        if file == 'luminofor.js':
            return text.replace("      case 'note':",
                                "      case 'glass': return root.LUMINOFOR_PROPOSAL.glass(c, b, x, y, w);\n"
                                "      case 'note':", 1)
        return text
    shot.read = patched
    ids = re.findall(r"^\s+'([a-z0-9-]+)': panel\(", proposal, re.M)
    if "'-text'" in proposal:
        ids += [i + '-text' for i in ids]
    ids = wanted or ids
    for bid in ids:
        shot.shoot(bid)


if __name__ == '__main__':
    main()
