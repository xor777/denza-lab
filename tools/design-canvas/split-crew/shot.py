#!/usr/bin/env python3
"""
Render the split wait, «Бригада», at given moments to PNGs at the car's own pixels.

    python3 shot.py                  the comparison frames -> ../_shots/split-crew/t<ms>.png
    python3 shot.py 0 650 2150       these moments, in ms since the shield appeared

A frame is split-crew.html opened as `#bare-t<ms>`: the approved page's own drawShield(t) at that
moment, with the page around the canvas hidden, in a 1280 x 800 CSS-px window at device scale 2 -
2560 x 1600, the head unit's pixels. Those PNGs are what `compare.py` lays a screenshot of the
app's fixture against.

The page asks Google Fonts for Roboto. A render that waits on the network is flaky (Luminofor's
first boards came out with the caption in the fallback face), so the page is shot from a temporary
copy whose Google Fonts links are replaced by the same Roboto 400 from `../luminofor/fonts/`,
inlined. Nothing else in the copy differs from the file.
"""
import base64
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'split-crew')
ROBOTO = os.path.join(HERE, '..', 'luminofor', 'fonts', 'Roboto-400.ttf')
CHROME = os.environ.get('DC_CHROME', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')

# The moments compared with the app: the reveal still opening, the pushers mid-walk towards the
# left third, a blow landing as the divider leaves the left third, the right third on its way, and
# one full divider cycle later.
FRAMES = [300, 1650, 2150, 3300, 5200]


def page():
    with open(os.path.join(HERE, 'split-crew.html'), encoding='utf-8') as f:
        html = f.read()
    with open(ROBOTO, 'rb') as f:
        face = base64.b64encode(f.read()).decode('ascii')
    local = ("<style>@font-face{font-family:'Roboto';font-weight:400;font-style:normal;"
             "src:url(data:font/ttf;base64,%s) format('truetype');}</style>" % face)
    links = re.compile(r'<link[^>]*fonts\.(?:googleapis|gstatic)\.com[^>]*>\n?')
    if len(links.findall(html)) != 3:
        sys.exit('split-crew.html: expected the three Google Fonts links the approved page has')
    return links.sub('', html, count=3).replace('<style>', local + '\n<style>', 1)


def chrome(args):
    """Headless Chrome, its output to a file, never a pipe: a pending Chrome update starts
    GoogleUpdater, which inherits the pipes and keeps them open, and a run that waits for EOF on
    them never returns (see ../luminofor/shot.py)."""
    with open(os.devnull, 'w') as sink:
        subprocess.run([CHROME, '--headless=new', '--hide-scrollbars', '--disable-gpu',
                        '--force-device-scale-factor=2', *args],
                       stdout=sink, stderr=subprocess.DEVNULL, timeout=120)


def shoot(ms, path):
    out = os.path.join(SHOTS, 't%d.png' % ms)
    chrome(['--window-size=1280,800', '--virtual-time-budget=3000',
            '--screenshot=' + out, 'file://%s#bare-t%d' % (path, ms)])
    print(out)


if __name__ == '__main__':
    times = [int(a) for a in sys.argv[1:]] or FRAMES
    os.makedirs(SHOTS, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
        f.write(page())
        path = f.name
    try:
        for ms in times:
            shoot(ms, path)
    finally:
        os.unlink(path)
