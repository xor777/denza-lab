#!/usr/bin/env python3
"""
Render an artboard to a PNG at real panel pixels, so it can be looked at.

`audit.py` measures a board; this one shows it. Both are needed, and for the same
reason: the first cut of the head-unit screen matched every number on `Main.dc.html`
and still looked nothing like it, because the board hangs a tile's words off the
bottom edge and the code stacked them from the top. No measurement catches that.
Somebody has to put the two pictures side by side.

**The trap this file exists to disarm.** A board is not just its `<x-dc>` body. The
`<script data-dc-script>` block AFTER `</x-dc>` holds the data every `<sc-for>`
loops over - the spectrum's bars, a picker's applications, the cluster's ticks. An
earlier renderer took the body alone and dropped that script, so every templated
element rendered as nothing, and the board came out an empty field. It was not
empty. That renderer was believed over the board, and this session went on to tell
the owner the spectrum analyser had never been designed - while it sat in
`Main.dc.html`, drawn, with its own data. The shim below is the smallest thing that
runs those loops: `DCLogic.renderVals()`, `sc-for` expansion, `{{ }}` substitution.

Each line reports `loops: N left: M` - the loops the board declares, and the ones
still standing in the rendered DOM. `left: 0` is the only good answer; anything
else is flagged HOLES, and the picture is lying to you the way the old one did.
`script: none` is fine on its own: some boards have no data to loop over.

Run: python3 shot.py <Board> [Board ...] [--scale N] [--all]

PNGs land in `_shots/`, which is not tracked. `--scale 2` gives the head unit's
own 2560x1600; scale 1 is the 1280x800 the boards are drawn in.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '_shots')
CHROME = os.environ.get(
    'DC_CHROME', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')

SHIM = r'''
class DCLogic { constructor(){ this.props = {}; } }
__BOARD_SCRIPT__
(function () {
  var vals = {};
  try { vals = new Component().renderVals() || {}; } catch (e) { console.log('renderVals: ' + e); }
  function dig(root, path) {
    var v = root;
    for (var i = 0; i < path.length; i++) {
      if (v == null) return undefined;
      v = v[path[i]];
    }
    return v;
  }
  function fill(html, alias, item) {
    return html.replace(/\{\{\s*([\w.]+)\s*\}\}/g, function (m, expr) {
      var parts = expr.split('.');
      var v;
      if (alias && parts[0] === alias) v = dig(item, parts.slice(1));
      else v = dig(vals, parts);
      return v === undefined || v === null ? m : String(v);
    });
  }
  // Innermost loops first, so a nested sc-for is expanded by its own parent pass.
  var guard = 0;
  while (document.querySelector('sc-for') && guard++ < 20) {
    var nodes = Array.prototype.slice.call(document.querySelectorAll('sc-for'));
    nodes.forEach(function (node) {
      if (node.querySelector('sc-for')) return;
      var list = (node.getAttribute('list') || '').replace(/[{}]/g, '').trim();
      var alias = node.getAttribute('as') || 'it';
      var items = dig(vals, list.split('.'));
      if (!Array.isArray(items)) items = [];
      var tpl = node.innerHTML;
      node.outerHTML = items.map(function (item) { return fill(tpl, alias, item); }).join('');
    });
  }
  document.body.innerHTML = fill(document.body.innerHTML, null, null);
})();
'''


def frame(name, body):
    """The artboard's own size, from the record that lays the canvas out.

    `canvas.json` carries `w`/`h` per artboard, and it is the authority: the first
    `width:Npx; height:Npx` in a board's body is the frame on eighteen of the twenty
    boards and a colour swatch on the other two, which rendered Kit as a 6x12 image
    and said nothing. A tool that silently shows six pixels of a board is the same
    tool that silently showed none of the spectrum.
    """
    try:
        boards = json.load(open(os.path.join(HERE, 'canvas.json'), encoding='utf-8'))
        for a in boards.get('artboards', ()):
            if a.get('file') == name + '.dc.html' and a.get('w') and a.get('h'):
                return float(a['w']), float(a['h'])
    except (OSError, ValueError):
        pass
    m = re.search(r'width:([\d.]+)px;\s*height:([\d.]+)px', body)
    if not m:
        raise SystemExit(f'{name}: no size in canvas.json and none in the board')
    print(f'{name}: not in canvas.json, guessing the frame from the board')
    return float(m.group(1)), float(m.group(2))


def shoot(name, scale):
    """Render one board and return the path written."""
    src = open(os.path.join(HERE, name + '.dc.html'), encoding='utf-8').read()

    helmet = re.search(r'<helmet>(.*?)</helmet>', src, re.S).group(1)
    body = src.split('</helmet>', 1)[1].split('</x-dc>', 1)[0]

    board_script = ''
    m = re.search(r'<script data-dc-script[^>]*>(.*?)</script>', src, re.S)
    if m:
        board_script = m.group(1)

    w, h = frame(name, body)

    shim = SHIM.replace('__BOARD_SCRIPT__', board_script)
    page = f'''<!doctype html><html><head><meta charset="utf-8">{helmet}
<style>html,body{{margin:0;padding:0;background:#000;}}
#wrap{{transform:scale({scale});transform-origin:0 0;}}</style></head>
<body><div id="wrap">{body}</div>
<script>{shim}</script></body></html>'''

    os.makedirs(SHOTS, exist_ok=True)
    tmp = os.path.join(SHOTS, '_shot.html')
    open(tmp, 'w', encoding='utf-8').write(page)
    png = os.path.join(SHOTS, f'{name}.png')
    # --dump-dom rides along with --screenshot in one launch, and it is the only
    # honest way to ask whether the loops ran: the file on disk is the page BEFORE
    # the shim, so counting there reports the source's own loops and calls a good
    # render broken - and a board with no loops clean whether the shim worked or not.
    run = subprocess.run(
        [CHROME, '--headless', '--disable-gpu', '--hide-scrollbars',
         f'--window-size={int(w * scale)},{int(h * scale)}',
         f'--screenshot={png}', '--dump-dom', '--virtual-time-budget=4000',
         'file://' + tmp], check=True, capture_output=True)

    loops = len(re.findall(r'<sc-for', body))
    left = len(re.findall(r'<sc-for', run.stdout.decode('utf-8', 'replace')))
    print(f'{png} {int(w * scale)}x{int(h * scale)} '
          f'script: {"yes" if board_script else "none"} '
          f'loops: {loops} left: {left}{"  <-- HOLES" if left else ""}')
    return png


if __name__ == '__main__':
    args = sys.argv[1:]
    scale = 1.0
    if '--scale' in args:
        i = args.index('--scale')
        scale = float(args[i + 1])
        del args[i:i + 2]
    if '--all' in args:
        args = sorted(f[:-8] for f in os.listdir(HERE) if f.endswith('.dc.html'))
    if not args:
        sys.exit('usage: shot.py <Board> [Board ...] [--scale N] [--all]')
    if not os.path.exists(CHROME):
        sys.exit(f'no Chrome at {CHROME}; set DC_CHROME to its binary')
    for name in args:
        shoot(name, scale)
