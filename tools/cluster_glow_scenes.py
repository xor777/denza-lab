#!/usr/bin/env python3
"""
Render the Contour scenes the cluster glow probe shows, without their glow, into its assets.

    python3 tools/cluster_glow_scenes.py

A probe, not a production candidate. `experiments/cluster-glow-probe` puts these pictures on the
driver's display and draws the zero's glow over them itself, at the strength, shape and size the
owner picks on the head unit (docs/instrument-display-findings.md, "The glow is below what the
cluster shows"). Each picture is a Luminofor board as `shot.py` renders it - bare, the way the app
draws it - with `cluster.band.glow.max` set to zero and nothing else changed. The glow is additive
(`lighter` on the board, `PLUS` in the app), so drawing it last gives the board's own pixels.

Writes `scenes/<board>.png` and `scenes.json` (each board's power, from the app's exported
fixtures) under the probe's `src/main/assets/`, which git ignores. Needs Chrome, as shot.py does.
"""
import json
import os
import shutil
import sys
import tempfile

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
LUMINOFOR = os.path.join(REPO, 'tools', 'design-canvas', 'luminofor')
FIXTURES = os.path.join(REPO, 'apps', 'denza-apps', 'src', 'debug', 'assets', 'luminofor', 'fixtures.json')
ASSETS = os.path.join(REPO, 'experiments', 'cluster-glow-probe', 'src', 'main', 'assets')

# Full throttle (past the glow's 120 kW cap), city driving, and energy coming back (the blue glow).
BOARDS = ['cluster-launch', 'cluster-city', 'cluster-regen']

sys.path.insert(0, LUMINOFOR)
import shot  # noqa: E402

_read = shot.read


def read_without_glow(name):
    text = _read(name)
    if name != 'spec.json':
        return text
    spec = json.loads(text)
    spec['cluster']['band']['glow']['max'] = 0
    return json.dumps(spec)


def main():
    with open(FIXTURES, encoding='utf-8') as f:
        fixtures = json.load(f)
    shot.read = read_without_glow
    out = os.path.join(ASSETS, 'scenes')
    os.makedirs(out, exist_ok=True)
    scenes = []
    with tempfile.TemporaryDirectory() as tmp:
        shot.SHOTS = tmp
        for bid in BOARDS:
            shot.shoot(bid)
            shutil.copyfile(os.path.join(tmp, bid + '.bare.png'), os.path.join(out, bid + '.png'))
            scenes.append({'board': bid, 'powerKw': fixtures[bid][1]['power']})
    # What the probe needs to draw the glow where luminofor.js centre() draws it, from the same file.
    spec = json.loads(_read('spec.json'))
    cluster, colors = spec['cluster'], spec['colors']['cluster']
    geometry = {
        'W': cluster['W'], 'H': cluster['H'],
        'axis': cluster['grid']['axis'], 'stockBottom': cluster['stock']['bottom'],
        'glow': cluster['band']['glow'],
        'ink': colors['ink'][0], 'blue': colors['blue'][0],
    }
    with open(os.path.join(ASSETS, 'scenes.json'), 'w', encoding='utf-8') as f:
        json.dump({'geometry': geometry, 'scenes': scenes}, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print(out, len(scenes), 'scenes')


if __name__ == '__main__':
    main()
