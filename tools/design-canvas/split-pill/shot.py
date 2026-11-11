#!/usr/bin/env python3
"""
Render the split pill's boards and timed frames to PNGs at the car's own pixels, and write its numbers.

    python3 shot.py                         every board and the default timed frames
                                            -> ../_shots/split-pill/<id>.png
    python3 shot.py open edit-day opening-t160    these ones
    python3 shot.py --spec                  SPEC, as the page holds it, -> spec.json beside the page

A board is split-pill.html opened as `#bare-<board>`, a timed frame as `#bare-<motion>-t<ms>`: the
approved page's own code run on its virtual clock to that state or moment, every animation held
still, with the stand around the stage hidden, in a 1280 x 800 CSS-px window at device scale 2 -
2560 x 1600, the head unit's pixels. Those PNGs are what `compare.py` lays a screenshot of the app
against.

The page asks Google Fonts for Roboto. A render that waits on the network is flaky (Luminofor's
first boards came out with the caption in the fallback face), so the page is shot from a temporary
copy whose Google Fonts link is replaced by the same Roboto 400 and 500 from `../luminofor/fonts/`,
inlined. Nothing else in the copy differs from the file.
"""
import base64
import html
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'split-pill')
FONTS = os.path.join(HERE, '..', 'luminofor', 'fonts')
CHROME = os.environ.get('DC_CHROME', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')

# The boards, one settled state each (the README says what each shows).
BOARDS = ['handle', 'handle-dim', 'open', 'open-day', 'open-browser', 'open-wide-left',
          'vacant-right', 'vacant-left', 'vacant-both', 'edit', 'edit-day', 'launch', 'home', 'blur-day']

# The timed frames shot by default, in ms after each motion starts: the open morph through its
# entrances; a pair chosen through the flights, the rest, the launch and the dissolve; a pane
# vacated through the picker, the pill opening by itself and the pair opening up.
FRAMES = {
    'opening': [0, 40, 80, 120, 160, 240, 320, 480],
    'choosing': [0, 60, 120, 200, 300, 380, 700, 1030, 1150, 1300],
    'vacating': [0, 130, 270, 400, 460, 520, 620, 760, 900, 1200],
}


def page():
    with open(os.path.join(HERE, 'split-pill.html'), encoding='utf-8') as f:
        src = f.read()
    faces = ''
    for weight in (400, 500):
        with open(os.path.join(FONTS, 'Roboto-%d.ttf' % weight), 'rb') as f:
            face = base64.b64encode(f.read()).decode('ascii')
        faces += ("@font-face{font-family:'Roboto';font-weight:%d;font-style:normal;"
                  "src:url(data:font/ttf;base64,%s) format('truetype');}" % (weight, face))
    links = re.compile(r'<link[^>]*fonts\.(?:googleapis|gstatic)\.com[^>]*>\n?')
    if len(links.findall(src)) != 1:
        sys.exit('split-pill.html: expected the one Google Fonts link the approved page has')
    return links.sub('', src, count=1).replace('<style>', '<style>%s</style>\n<style>' % faces, 1)


def chrome(args, stdout=None):
    """Headless Chrome, its output to a file, never a pipe: a pending Chrome update starts
    GoogleUpdater, which inherits the pipes and keeps them open, and a run that waits for EOF on
    them never returns (see ../luminofor/shot.py)."""
    with open(os.devnull, 'w') as sink:
        subprocess.run([CHROME, '--headless=new', '--hide-scrollbars', '--disable-gpu',
                        '--force-device-scale-factor=2', *args],
                       stdout=stdout or sink, stderr=subprocess.DEVNULL, timeout=120)


def shoot(name, path):
    """--run-all-compositor-stages-before-draw: without it about one shot in three of a board
    whose panes were re-laid (open-wide-left) caught a raster tile still holding an earlier frame,
    a dark block in the bottom-right corner; with it every rerun is byte-identical."""
    out = os.path.join(SHOTS, '%s.png' % name)
    chrome(['--window-size=1280,800', '--virtual-time-budget=3000', '--run-all-compositor-stages-before-draw',
            '--screenshot=' + out, 'file://%s#bare-%s' % (path, name)])
    print(out)


def spec(path):
    """SPEC from the page's `#spec` mode, which prints JSON.stringify(SPEC, null, 2) into a <pre>."""
    with tempfile.TemporaryFile('w+', encoding='utf-8') as dom:
        chrome(['--virtual-time-budget=1000', '--dump-dom', 'file://%s#spec' % path], stdout=dom)
        dom.seek(0)
        m = re.search(r'<pre id="spec">(.*?)</pre>', dom.read(), re.S)
    if not m:
        sys.exit('split-pill.html#spec printed no SPEC')
    text = html.unescape(m.group(1))
    json.loads(text)
    out = os.path.join(HERE, 'spec.json')
    with open(out, 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    print(out)


if __name__ == '__main__':
    args = sys.argv[1:]
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
        f.write(page())
        path = f.name
    try:
        if args == ['--spec']:
            spec(path)
        else:
            names = args or BOARDS + ['%s-t%d' % (m, t) for m, ts in FRAMES.items() for t in ts]
            os.makedirs(SHOTS, exist_ok=True)
            for name in names:
                shoot(name, path)
    finally:
        os.unlink(path)
