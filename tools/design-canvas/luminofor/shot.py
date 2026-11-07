#!/usr/bin/env python3
"""
Render the Luminofor boards to PNGs at the displays' own pixels, and export their fixtures.

    python3 shot.py                  every board -> ../_shots/luminofor/<id>.png
    python3 shot.py main-sound ...   only these
    python3 shot.py --fixtures       write the app's debug fixtures (fixtures.json) from fixtures.js
    python3 shot.py --lang en ...    the same in English: ../_shots/luminofor/en/<id>.png, fixtures.en.json
    python3 shot.py --lang en --stays   the boards' words that have no key and stay Russian

A board is luminofor.js drawing one fixture from fixtures.js with the numbers in spec.json - the
same code the owner approved as a live page, made deterministic. Each board is written into one
self-contained HTML page (the three sources and the fonts in fonts/ inlined) and shot by
headless Chrome at device scale 1 into a canvas the size of the real display: the cluster at
2560 x 720, the head unit at 2560 x 1360 (1280 x 680 dp at 2.0), the panes at 1656 and 832 wide.
Those PNGs are what `compare.py` lays a screenshot of the app against.

English comes from res/values-en/strings.xml through english.js, never from a copy here, and adds
nothing to a Russian page. The English boards are for looking at; only the Russian ones are normative.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, '..', '_shots', 'luminofor')
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
APP_FIXTURES = os.path.join(REPO, 'apps', 'denza-apps', 'src', 'debug', 'assets', 'luminofor', 'fixtures.json')
CHROME = os.environ.get('DC_CHROME', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')

RU, EN = 'ru', 'en'

# The faces are files in fonts/, inlined into each page: Jura is the very file the app ships in
# res/font, Roboto 400 and 500 are Google Fonts' full TrueType, Roboto Mono (the scanner's figures)
# the very files the app ships in res/font. Fetching them per page from Google
# Fonts made the boards flaky - a subset or a weight not there yet when the canvas drew, and a
# Cyrillic caption or a whole title came out in the fallback.
FACES = [('Jura', 500, 'Jura-Medium.ttf'), ('Roboto', 400, 'Roboto-400.ttf'), ('Roboto', 500, 'Roboto-500.ttf'),
         ('Roboto Mono', 400, 'RobotoMono-400.ttf'), ('Roboto Mono', 500, 'RobotoMono-500.ttf')]


def resource_text(raw):
    """A string resource as aapt2 compiles it: ends trimmed, whitespace folded, quotes and escapes gone."""
    out, i, quoted, spaced = [], 0, False, False
    raw = raw.strip(' \t\n\r\f\v')
    while i < len(raw):
        c = raw[i]
        i += 1
        if c in ' \t\n\r\f\v' and not quoted:
            if not spaced:
                out.append(' ')
            spaced = True
            continue
        spaced = False
        if c == '"':
            quoted = not quoted
        elif c == '\\' and i < len(raw):
            e = raw[i]
            i += 1
            if e == 'u':
                out.append(chr(int(raw[i:i + 4], 16)))
                i += 4
            else:
                out.append({'n': '\n', 't': '\t'}.get(e, e))
        elif c != '\\':
            out.append(c)
    return ''.join(out)


def strings_xml(folder):
    """{name: text} and {name: {quantity: text}} of one values folder."""
    root = ET.parse(os.path.join(REPO, 'apps', 'denza-apps', 'src', 'main', 'res', folder, 'strings.xml')).getroot()
    strings = {e.get('name'): resource_text(''.join(e.itertext())) for e in root.findall('string')}
    plurals = {e.get('name'): {i.get('quantity'): resource_text(''.join(i.itertext())) for i in e.findall('item')}
               for e in root.findall('plurals')}
    return strings, plurals


def english_table():
    """Russian to English by key, for english.js. A key either file lacks is not in the table.

    Two keys can share one Russian text and differ in English: the first key in the file wins, and
    `shared` lists them for `--stays`; no two do today. The words `luminofor.js` writes inline are the
    glass picture's, and `drawn` holds their own keys, so a word on the glass takes its `hud_glass_*`
    English even if another key shares its Russian: «Камеры» is `hud_glass_cameras`, and the row under
    the picture, «Камеры и опасности», is `hud_panel_cameras_title`.
    """
    ru, ru_plurals = strings_xml('values')
    en, en_plurals = strings_xml('values-en')
    mark = re.compile(r'%%|%(?:(\d+)\$)?[sd]')

    def pattern(text):
        # A template's literal pieces and one group per argument, the groups numbered by the argument.
        order, parts, at = [], [], 0
        for m in mark.finditer(text):
            parts.append(re.escape(text[at:m.start()]).replace('/', '\\/'))
            at = m.end()
            if m.group(0) == '%%':
                parts.append('%')
            else:
                order.append(int(m.group(1) or 1))
                parts.append('(.+?)' if m.group(0).endswith('s') else '([0-9]+)')
        parts.append(re.escape(text[at:]))
        return ''.join(parts), order

    exact, templates, shared = {}, [], {}
    for key, text in ru.items():
        if key not in en or ru[key] == en[key]:
            continue
        if not mark.search(text):
            if text in exact and exact[text] != en[key]:
                shared.setdefault(text, [exact[text]]).append(en[key])
            exact.setdefault(text, en[key])
        else:
            source, order = pattern(text)
            # JS groups count left to right; the English asks for arguments by number.
            templates.append({'re': source, 'en': renumbered(en[key], order), 'weight': len(mark.sub('', text))})
    for key, forms in ru_plurals.items():
        if key not in en_plurals:
            continue
        for form in forms.values():
            source, order = pattern(form)
            templates.append({'re': source, 'weight': len(mark.sub('', form)),
                              'forms': {'one': renumbered(en_plurals[key].get('one', en_plurals[key]['other']), order),
                                        'other': renumbered(en_plurals[key]['other'], order)}})
    # The longest literal first, so that «%1$s км» cannot answer for «%1$s км · за поездку».
    templates.sort(key=lambda t: -t.pop('weight'))
    drawn = {ru[key]: en[key] for key in ru if key.startswith('hud_glass_') and key in en}
    return {'exact': sorted(exact.items()), 'templates': templates, 'shared': shared, 'drawn': sorted(drawn.items())}


def renumbered(text, order):
    """English text whose %n$s name the groups of a pattern whose arguments came in `order`."""
    position = {n: i + 1 for i, n in enumerate(order)}
    return re.sub(r'%(\d+)\$([sd])', lambda m: '%%%d$%s' % (position.get(int(m.group(1)), int(m.group(1))), m.group(2)), text)


def font_css():
    import base64
    rules = []
    for family, weight, name in FACES:
        with open(os.path.join(HERE, 'fonts', name), 'rb') as f:
            data = base64.b64encode(f.read()).decode('ascii')
        rules.append("@font-face{font-family:'%s';font-weight:%d;font-style:normal;"
                     "src:url(data:font/ttf;base64,%s) format('truetype');}" % (family, weight, data))
    return ''.join(rules)

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


def page(body_script, lang=RU):
    spec = read('spec.json')
    # English goes in after the scenes and before anything is drawn; a Russian page has none of it.
    english = '' if lang == RU else (
        f'<script>window.LUMINOFOR_EN = {json.dumps(english_table(), ensure_ascii=False)};</script>'
        f'<script>{read("english.js")}</script>'
        '<script>Object.assign(window.LUMINOFOR_BOARDS, LUMINOFOR_EN.boards(window.LUMINOFOR_BOARDS));</script>')
    return ('<!doctype html><html><head><meta charset="utf-8">'
            f'<style>{font_css()}html,body{{margin:0;background:#000;overflow:hidden}}canvas{{display:block}}</style>'
            '</head><body><canvas id="c"></canvas>'
            f'<script>window.LUMINOFOR_SPEC = {spec};</script>'
            f'<script>window.SCANNER_LAYOUT = {read("scanner.json")};</script>'
            f'<script>{read("scanner.js")}</script>'
            f'<script>{read("fixtures.js")}</script>'
            f'{english}'
            f'<script>{read("luminofor.js")}</script>'
            f'<script>{body_script}</script></body></html>')


def board_page(bid, bare=False, lang=RU):
    # The scanner's boards have no English: their words are in `feature/diagnostics`, which is parked.
    lang = RU if bid.startswith('scanner') else lang
    install = 'LUMINOFOR_EN.install();' if lang == EN else ''
    return page("""
(async function () {
  %s
  const id = %s;
  const [board, scene] = window.LUMINOFOR_BOARDS[id];
  // bare: the cluster without the stock zones' hatching, which is board furniture the app never draws
  const fixture = %s ? Object.assign({}, scene, { keepout: false }) : scene;
  const key = ['head', 'sheet', 'modal', 'scanner'].includes(board.kind) ? board.mode : board.kind;
  const size = %s[key];
  const cv = document.getElementById('c');
  cv.width = size[0]; cv.height = size[1];
  cv.style.width = size[0] + 'px'; cv.style.height = size[1] + 'px';
  // Google Fonts splits each face by script and load() fetches only the subsets its sample text
  // touches - a space, by default, which is Latin alone. Every Cyrillic word would then be drawn in
  // the system's sans-serif, which is what the first PNGs showed: the sample names both scripts.
  // and load() can return before a subset is usable, so the board waits until check() says both
  // scripts of every face are there - a board once came out with its Cyrillic in the fallback.
  const faces = ['500 20px Jura', '400 20px Roboto', '500 20px Roboto', '400 20px "Roboto Mono"', '500 20px "Roboto Mono"'];
  const sample = 'Aa0 АБВабвё·→';
  for (let i = 0; i < 60; i++) {
    try { await Promise.all(faces.map(f => document.fonts.load(f, sample))); await document.fonts.ready; } catch (e) {}
    if (faces.every(f => document.fonts.check(f, sample))) break;
    await new Promise(r => setTimeout(r, 100));
  }
  const c = cv.getContext('2d');
  c.fillStyle = '#000'; c.fillRect(0, 0, size[0], size[1]);
  c.setTransform(size[2], 0, 0, size[2], 0, 0);
  window.Luminofor.drawBoard(c, board, fixture);
})();
""" % (install, json.dumps(bid), 'true' if bare else 'false', json.dumps(SIZES)), lang)


def chrome(args, out=None):
    """Run headless Chrome. Its output goes to a file, never a pipe: a pending Chrome update starts
    GoogleUpdater, which inherits the pipes and keeps them open, and a run that waits for EOF on
    them never returns - which is how the first full render hung for twenty minutes."""
    with open(out or os.devnull, 'w') as sink:
        subprocess.run([CHROME, '--headless=new', '--hide-scrollbars', '--disable-gpu',
                        '--force-device-scale-factor=1', *args],
                       stdout=sink, stderr=subprocess.DEVNULL, timeout=120)


def shoot(bid, lang=RU):
    kind = bid.split('-')[0]
    key = {'main': 'full', 'sheet': 'full', 'modal': 'full', 'cluster': 'cluster', 'two': 'two', 'one': 'one', 'digits': 'digits', 'scanner': 'full'}[kind]
    w, h, _ = SIZES[key]
    shots = SHOTS if lang == RU else os.path.join(SHOTS, lang)
    os.makedirs(shots, exist_ok=True)
    # a cluster board is also shot bare, without the hatching, for compare.py
    for bare in ([False, True] if kind == 'cluster' else [False]):
        out = os.path.join(shots, bid + ('.bare' if bare else '') + '.png')
        with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
            f.write(board_page(bid, bare, lang))
            path = f.name
        try:
            chrome([f'--window-size={w},{h}', '--virtual-time-budget=8000', f'--screenshot={out}', 'file://' + path])
        finally:
            os.unlink(path)
        print(out, f'{w}x{h}')


def dumped(script, lang=RU):
    """What `script` leaves as the body's text, as Chrome saw it."""
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8') as f:
        f.write(page(script, lang))
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
    return json.loads(text.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"'))


def export_fixtures(lang=RU):
    """fixtures.json, and in English fixtures.en.json without the scanner's boards, which have none."""
    pick = 'window.LUMINOFOR_BOARDS' if lang == RU else (
        'Object.fromEntries(Object.entries(window.LUMINOFOR_BOARDS).filter(e => !e[0].startsWith("scanner")))')
    data = dumped('document.body.textContent = JSON.stringify(%s);' % pick, lang)
    target = APP_FIXTURES if lang == RU else APP_FIXTURES.replace('fixtures.json', 'fixtures.%s.json' % lang)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print(target, len(data), 'boards')


def print_stays():
    """What the English boards leave in Russian: the scenes' words with no key, and the keys that clash."""
    for text in dumped('document.body.textContent = JSON.stringify([...LUMINOFOR_EN.stays].sort());', EN):
        print(text)
    for text, english in english_table()['shared'].items():
        print('two keys, one Russian text:', text, english)


if __name__ == '__main__':
    args = sys.argv[1:]
    lang = RU
    if args[:2] == ['--lang', EN]:
        lang, args = EN, args[2:]
    if args == ['--fixtures']:
        export_fixtures(lang)
    elif args == ['--stays']:
        print_stays()
    else:
        for bid in (args or board_ids()):
            shoot(bid, lang)
