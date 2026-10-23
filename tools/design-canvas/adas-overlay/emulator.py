#!/usr/bin/env python3
"""
Shoot every bare board's state in the real overlay on an emulator and compare it with its board.

    python3 shot.py bare                              the boards first
    python3 emulator.py --serial emulator-5612        then the screenshots and the comparison
    python3 emulator.py --serial emulator-5612 --scene live-stock-empty='{"inject": {...}}'
                                                      one fixture over Navigator's live map, shot only

The emulator runs our Navigator build (tools/yandex_hud_build) with Navigator's map open. For each
state this writes the module's emulator fixture - files/denza-adas-overlay.json in Navigator's
private files: the boards.js state, a black backdrop and the bare anchor - waits for the overlay
to pick it up, takes a screenshot to captures/adas-overlay-design/emulator/<id>.png and runs
compare.py. The fixture is read only on emulator hardware (ranchu/goldfish); nothing is fired.
The file is removed at the end and the overlay goes back to its live source.

A scene is any fixture without the black backdrop: {"inject": ...} runs synthetic observations
(pilot [dnp, icc, takeover], mode, selected [pathId, code], match [pathIds], alarm [reason,
notice], stockEmptyAgeMs, pilotAgeMs, feed {mode, pathId, routeActive, sendAgeMs}) through the
module's real decoder and rules (AdasOverlay.injected), drawn where the live overlay would be.
"""
import argparse
import json
import os
import subprocess
import time

import compare

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE = 'ru.yandex.yandexnavi'
SHOTS = os.path.join(compare.DESIGN, 'emulator')


def boards():
    src = open(os.path.join(HERE, 'boards.js'), encoding='utf-8').read()
    js = 'const window = {};' + src + ';process.stdout.write(JSON.stringify(window.ADAS_BOARDS));'
    return json.loads(subprocess.check_output(['node', '-e', js]))


def push(adb, fixture):
    subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'sh', '-c', "'cat > files/denza-adas-overlay.json'"],
                   input=fixture.encode('utf-8'), check=True)


def scenes(adb, items):
    os.makedirs(SHOTS, exist_ok=True)
    try:
        for item in items:
            name, fixture = item.split('=', 1)
            json.loads(fixture)
            push(adb, fixture)
            time.sleep(1.6)
            png = os.path.join(SHOTS, name + '.png')
            with open(png, 'wb') as f:
                f.write(subprocess.check_output(adb + ['exec-out', 'screencap', '-p']))
            print(png)
    finally:
        subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'rm', '-f', 'files/denza-adas-overlay.json'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--scene', action='append', default=[], help='name=fixture JSON, shot over the live map')
    parser.add_argument('ids', nargs='*')
    args = parser.parse_args()
    if args.scene:
        return scenes(['adb', '-s', args.serial], args.scene)
    data = boards()
    anchor = data['FRAMES']['bare']['A']
    ids = args.ids or [k for k in data['BOARDS'] if k.startswith('bare-')]
    adb = ['adb', '-s', args.serial]
    os.makedirs(SHOTS, exist_ok=True)
    results = []
    try:
        for bid in ids:
            shot = data['BOARDS'][bid]['shot']
            state = dict(data['STATES'][shot['state']], narrow=bool(shot.get('narrow')))
            fixture = json.dumps({'state': state, 'backdrop': 'black', 'anchor': [anchor['right'], anchor['bottom']]},
                                 ensure_ascii=False)
            push(adb, fixture)
            time.sleep(1.6)
            png = os.path.join(SHOTS, bid + '.png')
            with open(png, 'wb') as f:
                f.write(subprocess.check_output(adb + ['exec-out', 'screencap', '-p']))
            results.append((bid,) + compare.compare(bid, png))
    finally:
        subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'rm', '-f', 'files/denza-adas-overlay.json'])
    print()
    for bid, moved, near, mean, out in results:
        print(f'{bid:24s} {moved * 100:5.2f}% moved  {near * 100:5.2f}% beyond 1 px  mean {mean:5.2f}')


if __name__ == '__main__':
    main()
