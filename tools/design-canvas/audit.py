#!/usr/bin/env python3
"""
Measure the artboards in a real browser and report what collides.

The adversarial review that started this found sixty-odd defects by hand: text
running past a grid column, a tick crossing a numeral, labels a tenth of a pixel
apart, type sizes outside the declared ramp. Every one of those is a measurement,
and measurements should not be done by hand twice.

This inlines each .dc.html body into one page, opens it in headless Chrome, and
has the page measure itself: every piece of text against the artboard it sits in,
against its neighbours, and against the small shapes near it, plus the set of
type sizes, gaps, radii and stroke weights actually used. The report comes back
through --dump-dom.

Run: python3 audit.py [Board ...]
"""
import html
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

FONTS = ('https://fonts.googleapis.com/css2?family=Roboto:wght@200;300;400;500;700'
         '&family=Roboto+Mono:wght@300;400;500;700&display=swap')

AUDIT_JS = r"""
const out = [];
const near = (a, b) => Math.abs(a - b) < 0.5;
const area = r => Math.max(0, r.width) * Math.max(0, r.height);
const overlap = (a, b) => {
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return (w > 0.5 && h > 0.5) ? w * h : 0;
};
const label = el => (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 40);

[...document.querySelectorAll('iframe[data-board]')].forEach(frame => {
  const name = frame.dataset.board;
  const doc = frame.contentDocument;
  const board = doc.body;
  const art = board.firstElementChild.getBoundingClientRect();
  const say = m => out.push(name + ' | ' + m);

  const leaves = [...board.querySelectorAll('text, tspan, div, span, p')]
    .filter(el => {
      if (!label(el)) return false;
      if ([...el.children].some(c => label(c))) return false;
      const cs = doc.defaultView.getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0';
    });

  const covered = el => {
    const r = el.getBoundingClientRect();
    const hit = doc.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return hit && hit !== el && !el.contains(hit) && !hit.contains(el);
  };
  const boxes = leaves.map(el => ({ el, r: el.getBoundingClientRect(), t: label(el) }))
    .filter(b => area(b.r) > 0)
    .map(b => Object.assign(b, { hidden: covered(b.el) }));

  // 1. anything reaching past the artboard
  boxes.forEach(b => {
    const over = [];
    if (b.r.left < art.left - 0.5) over.push('left by ' + (art.left - b.r.left).toFixed(1));
    if (b.r.right > art.right + 0.5) over.push('right by ' + (b.r.right - art.right).toFixed(1));
    if (b.r.top < art.top - 0.5) over.push('top by ' + (art.top - b.r.top).toFixed(1));
    if (b.r.bottom > art.bottom + 0.5) over.push('bottom by ' + (b.r.bottom - art.bottom).toFixed(1));
    if (over.length) say('OVERFLOW "' + b.t + '" past the board ' + over.join(', '));
  });

  // 1b. anything reaching past the box it was placed in - a grid column that
  // clips nothing still lets its text run over the neighbour beside it.
  boxes.forEach(b => {
    const parent = b.el.parentElement;
    if (!parent || parent === board) return;
    const cs = doc.defaultView.getComputedStyle(parent);
    if (cs.display === 'inline' || parent.tagName === 'text') return;
    const p = parent.getBoundingClientRect();
    if (area(p) === 0) return;
    const past = Math.max(b.r.right - p.right, p.left - b.r.left);
    if (past > 0.5) say('SPILL "' + b.t + '" past its column by ' + past.toFixed(1));
  });

  // 1c. a label wider than the control it is printed in.
  //
  // The check this file did not have, and the reason the narrow ADB gate went out with its
  // primary action's words running past both ends of its own pill. Nothing above catches it: the
  // text and the button are one element there, so its rect is inside the artboard, inside its
  // column, and touching nothing. What gives it away is the element's own content being wider
  // than the box it has - which is exactly what scrollWidth against clientWidth says.
  //
  // Only elements that draw a box are asked. A bare label overflowing an invisible grid cell
  // paints into the gutter and is caught by COLLIDE if it reaches anything; a label overflowing a
  // fill or a border is a control whose words are outside it, which is always wrong and is what
  // this is for. SVG text is skipped: clientWidth is 0 for all of it, and what an SVG label sits
  // inside is a sibling rect rather than a parent, which is CROSS's business.
  //
  // An element declaring text-overflow:ellipsis has decided to clip and is left alone.
  boxes.forEach(b => {
    if (b.el.ownerSVGElement) return;
    const cs = doc.defaultView.getComputedStyle(b.el);
    if (cs.textOverflow === 'ellipsis') return;
    const filled = cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent';
    const edged = ['Top', 'Right', 'Bottom', 'Left']
      .some(side => parseFloat(cs['border' + side + 'Width']) > 0);
    if (!filled && !edged) return;
    const over = b.el.scrollWidth - b.el.clientWidth;
    if (over > 1) say('TIGHT "' + b.t + '" is ' + over.toFixed(1) + ' wider than its own control');
  });

  // 2. text on text
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i], b = boxes[j];
      if (a.el.contains(b.el) || b.el.contains(a.el)) continue;
      if (a.hidden || b.hidden) continue;
      const o = overlap(a.r, b.r);
      if (o > 1) say('COLLIDE "' + a.t + '" x "' + b.t + '" over ' + o.toFixed(0) + 'px2');
      else if (o === 0) {
        const gapX = Math.max(a.r.left - b.r.right, b.r.left - a.r.right);
        const gapY = Math.max(a.r.top - b.r.bottom, b.r.top - a.r.bottom);
        const gap = Math.max(gapX, gapY);
        const siblings = a.el.parentElement && b.el.parentElement &&
          a.el.parentElement.parentElement === b.el.parentElement.parentElement;
        if (gap >= 0 && gap < 1.5 && Math.min(gapX, gapY) < 0 && !siblings)
          say('TOUCH "' + a.t + '" and "' + b.t + '" gap ' + gap.toFixed(2));
      }
    }
  }

  // 3. small shapes crossing text - a rule through a numeral reads as a defect
  const shapes = [...board.querySelectorAll('line, rect, circle, path')]
    .map(el => ({ el, r: el.getBoundingClientRect() }))
    .filter(s => area(s.r) > 0 && s.r.width < art.width * 0.6);
  shapes.filter(s => s.r.width <= 3.5 || s.r.height <= 3.5).forEach(s => {
    boxes.filter(b => !b.hidden).forEach(b => {
      const o = overlap(s.r, b.r);
      if (o > 2) say('CROSS ' + s.el.tagName + ' through "' + b.t + '" by ' + o.toFixed(0) + 'px2');
    });
  });

  // 4. the vocabulary actually used
  const sizes = new Set(), weights = new Set(), radii = new Set(), strokes = new Set();
  const gaps = new Set();
  leaves.forEach(el => {
    sizes.add(parseFloat(doc.defaultView.getComputedStyle(el).fontSize));
  });
  board.querySelectorAll('*').forEach(el => {
    const cs = doc.defaultView.getComputedStyle(el);
    // Every gap the board actually spends: a flex or grid gap, a padding, a margin. Nothing here
    // measured them, so the one ladder with no counterpart in the app - the spacing one - was also
    // the one ladder nothing checked, and 30, 14 and 6 sat on the dashboard for a wave.
    //
    // Zero is not a gap and is not reported. Neither is a percentage or an `auto` margin: both
    // come back resolved to whatever the box happened to be, which is a measurement of the layout
    // rather than of a decision. A padding on an element whose box is a shape - a swatch, a pill -
    // is still a decision, so it counts.
    //
    // A generated underlay is skipped: it is a picture of another board, its spacing is that
    // board's and is measured on that board's own row, and its analyser carries a per-column
    // offset as a margin - so counting it here reported twelve bar heights as twelve gaps.
    if (!el.closest('.underlay')) {
      ['rowGap', 'columnGap'].forEach(k => {
        const v = parseFloat(cs[k]);
        if (v > 0 && cs[k].endsWith('px')) gaps.add(+v.toFixed(2));
      });
      ['Top', 'Right', 'Bottom', 'Left'].forEach(side => {
        ['padding', 'margin'].forEach(kind => {
          const raw = el.style[kind + side] || '';
          if (raw && !raw.endsWith('px')) return;
          const v = parseFloat(cs[kind + side]);
          if (v > 0) gaps.add(+v.toFixed(2));
        });
      });
    }
    ['borderTopLeftRadius', 'borderTopRightRadius'].forEach(k => {
      const v = parseFloat(cs[k]);
      if (v > 0 && cs[k].endsWith('px')) radii.add(v);
    });
    // An ellipse's rx is its size, not a corner radius.
    const rx = el.tagName === 'rect' && el.getAttribute('rx');
    if (rx) radii.add(parseFloat(rx));
    const sw = el.getAttribute && el.getAttribute('stroke-width');
    if (sw && el.getAttribute('stroke') && el.getAttribute('stroke') !== 'none') {
      const box = el.getBoundingClientRect();
      // The stroke often sits on the <svg> itself, whose ownerSVGElement is
      // null - so find the nearest svg including this element.
      const svg = el.tagName === 'svg' ? el : el.ownerSVGElement;
      const vb = svg && svg.viewBox.baseVal.width;
      const w = svg ? svg.getBoundingClientRect().width : 0;
      const scale = (vb && w) ? w / vb : 1;
      strokes.add(+(parseFloat(sw) * scale).toFixed(3));
    }
  });
  say('SIZES ' + [...sizes].sort((a, b) => b - a).join(' '));
  say('GAPS ' + [...gaps].sort((a, b) => b - a).join(' '));
  say('RADII ' + [...radii].sort((a, b) => b - a).join(' '));
  say('STROKES ' + [...strokes].sort((a, b) => b - a).join(' '));
});

const pre = document.createElement('pre');
pre.id = 'report';
pre.textContent = out.join('\n');
document.body.appendChild(pre);
"""


def board_body(path):
    src = open(path).read()
    style = ''
    m = re.search(r'<style>(.*?)</style>', src, re.S)
    if m:
        style = m.group(1)
    m = re.search(r'</helmet>\s*(.*?)\s*</x-dc>', src, re.S)
    body = m.group(1) if m else ''
    # sc-for is a runtime binding; drop the template rows rather than render junk.
    body = re.sub(r'<sc-for.*?</sc-for>', '', body, flags=re.S)
    return style, body


def build(names):
    frames = []
    for n in names:
        style, body = board_body(os.path.join(HERE, n + '.dc.html'))
        doc = ('<!doctype html><meta charset="utf-8">'
               f'<link href="{html.escape(FONTS)}" rel="stylesheet">'
               f'<style>{style}</style><body style="margin:0">{body}</body>')
        frames.append(f'<iframe data-board="{n}" width="2600" height="1000" '
                      f'style="border:0;display:block" srcdoc="{html.escape(doc, quote=True)}">'
                      '</iframe>')
    return ('<!doctype html><meta charset="utf-8">'
            f'<link href="{html.escape(FONTS)}" rel="stylesheet">'
            '<body style="margin:0;background:#000">' + '\n'.join(frames) +
            '<script>window.addEventListener("load", () => { setTimeout(() => {'
            + AUDIT_JS + '}, 800); });</script></body>')


def main():
    names = sys.argv[1:] or sorted(
        f[:-8] for f in os.listdir(HERE) if f.endswith('.dc.html'))
    page = os.path.join(HERE, '_audit.html')
    open(page, 'w').write(build(names))
    dom = subprocess.run(
        [CHROME, '--headless', '--disable-gpu', '--hide-scrollbars', '--dump-dom',
         '--virtual-time-budget=4000', '--window-size=2600,1400', page],
        capture_output=True, text=True).stdout
    m = re.search(r'<pre id="report">(.*?)</pre>', dom, re.S)
    if not m:
        print('no report - the page did not run', file=sys.stderr)
        sys.exit(1)
    print(html.unescape(m.group(1)))


if __name__ == '__main__':
    main()
