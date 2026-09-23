#!/usr/bin/env python3
"""
Render the Luminofor boards to PNGs at the displays' own pixels, and export their fixtures.

    python3 shot.py                  every board -> ../_shots/luminofor/<id>.png
    python3 shot.py main-sound ...   only these
    python3 shot.py --fixtures       write the app's debug fixtures (fixtures.json) from fixtures.js

A board is luminofor.js drawing one fixture from fixtures.js with the numbers in spec.json - the
same code the owner approved as a live page, made deterministic. Each board is written into one
self-contained HTML page (the three sources inlined, Jura and Roboto from Google Fonts) and shot by
headless Chrome at device scale 1 into a canvas the size of the real display: the cluster at
2560 x 720, the head unit at 2560 x 1360 (1280 x 680 dp at 2.0), the panes at 1656 and 832 wide.
Those PNGs are what `compare.py` lays a screenshot of the app against.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'luminofor')
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
APP_FIXTURES = os.path.join(REPO, 'apps', 'denza-apps', 'src', 'debug', 'assets', 'luminofor', 'fixtures.json')
CHROME = os.environ.get('DC_CHROME', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')

FONTS = ('https://fonts.googleapis.com/css2?family=Jura:wght@500;600'
         '&family=Roboto:wght@400;500&display=swap')

# board id -> (css width, css height, scale from layout units to pixels)
SIZES = {
    'cluster': (2560, 720, 720 / 424),
    'full': (2560, 1360, 2.0),
    'two': (1656, 1360, 2.0),
    'one': (832, 1360, 2.0),
    'digits': (1320 * 2, 300 * 2, 2.0),
}


def read(name):
    with open(os.path.join(HERE, name), encoding='utf-8') as f:
        return f.read()


def board_ids():
    """The ids fixtures.js declares, in order, without running it: its last object literal."""
    src = read('fixtures.js')
    block = src[src.index('root.LUMINOFOR_BOARDS'):]
    ids = []
    for line in block.splitlines():
        line = line.strip()
        if line.startswith("'") and "':" in line:
            ids.append(line[1:line.index("'", 1)])
    return ids


def page(body_script):
    spec = read('spec.json')
    return ('<!doctype html><html><head><meta charset="utf-8">'
            f'<link rel="stylesheet" href="{FONTS}">'
            '<style>html,body{margin:0;background:#000;overflow:hidden}canvas{display:block}</style>'
            '</head><body><canvas id="c"></canvas>'
            f'<script>window.LUMINOFOR_SPEC = {spec};</script>'
            f'<script>{read("fixtures.js")}</script>'
            f'<script>{read("luminofor.js")}</script>'
            f'<script>{body_script}</script></body></html>')


def board_page(bid):
    return page("""
(async function () {
  const id = %s;
  const [board, fixture] = window.LUMINOFOR_BOARDS[id];
  const key = board.kind === 'head' ? board.mode : board.kind;
  const size = %s[key];
  const cv = document.getElementById('c');
  cv.width = size[0]; cv.height = size[1];
  cv.style.width = size[0] + 'px'; cv.style.height = size[1] + 'px';
  // Google Fonts splits each face by script and load() fetches only the subsets its sample text
  // touches - a space, by default, which is Latin alone. Every Cyrillic word would then be drawn in
  // the system's sans-serif, which is what the first PNGs showed: the sample names both scripts.
  try {
    await Promise.all(['500 20px Jura', '600 20px Jura', '400 20px Roboto', '500 20px Roboto']
      .map(f => document.fonts.load(f, 'Aa0 АБВабвё·→')));
  } catch (e) {}
  const c = cv.getContext('2d');
  c.fillStyle = '#000'; c.fillRect(0, 0, size[0], size[1]);
  c.setTransform(size[2], 0, 0, size[2], 0, 0);
  window.Luminofor.drawBoard(c, board, fixture);
})();
""" % (json.dumps(bid), json.dumps(SIZES)))


def chrome(args, out=None):
    """Run headless Chrome. Its output goes to a file, never a pipe: a pending Chrome update starts
    GoogleUpdater, which inherits the pipes and keeps them open, and a run that waits for EOF on
    them never returns - which is how the first full render hung for twenty minutes."""
    with open(out or os.devnull, 'w') as sink:
        subprocess.run([CHROME, '--headless=new', '--hide-scrollbars', '--disable-gpu',
                        '--force-device-scale-factor=1', *args],
                       stdout=sink, stderr=subprocess.DEVNULL, timeout=120)


def shoot(bid):
    kind = bid.split('-')[0]
    key = {'main': 'full', 'cluster': 'cluster', 'two': 'two', 'one': 'one', 'digits': 'digits'}[kind]
    w, h, _ = SIZES[key]
    os.makedirs(SHOTS, exist_ok=True)
    out = os.path.join(SHOTS, bid + '.png')
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
        f.write(board_page(bid))
        path = f.name
    try:
        chrome([f'--window-size={w},{h}', '--virtual-time-budget=8000', f'--screenshot={out}', 'file://' + path])
    finally:
        os.unlink(path)
    print(out, f'{w}x{h}')


def export_fixtures():
    html = page("document.body.textContent = JSON.stringify(window.LUMINOFOR_BOARDS);")
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
        f.write(html)
        path = f.name
    dump = path + '.dom'
    try:
        chrome(['--dump-dom', 'file://' + path], out=dump)
        with open(dump, encoding='utf-8') as f:
            dom = f.read()
    finally:
        os.unlink(path)
        if os.path.exists(dump):
            os.unlink(dump)
    start = dom.index('<body>') + len('<body>')
    text = dom[start:dom.index('</body>')]
    text = text.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"')
    data = json.loads(text)
    os.makedirs(os.path.dirname(APP_FIXTURES), exist_ok=True)
    with open(APP_FIXTURES, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print(APP_FIXTURES, len(data), 'boards')


if __name__ == '__main__':
    args = sys.argv[1:]
    if args == ['--fixtures']:
        export_fixtures()
    else:
        for bid in (args or board_ids()):
            shoot(bid)
