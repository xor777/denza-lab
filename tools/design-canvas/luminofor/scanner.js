/*
 * «Сервис → Диагностика», the scanner - the boards of the owner's canvas «Диагностика · инженерное
 * меню», pages «Сканер (v3)» (approved 2026-10-20) and «Концепт для диагноста (v4)» (approved
 * 2026-10-23), made deterministic.
 *
 * The approved page was HTML laid out by a browser; this draws the same page on a canvas from
 * scanner.json (every number: colours, the text styles with their line heights, the chrome, each
 * screen's columns and rows) and one fixture from fixtures.js (the data). A word's baseline is
 * always top + (lh - (ascent + descent) * size) / 2 + ascent * size: a line box of an explicit
 * height with the glyphs centred in it, which is what Compose does with a lineHeight and
 * LineHeightStyle(Center, Trim.None), so a screen can be laid over its board.
 *
 * The flat look of the page is kept: source-over fills, 1 dp rules, glow only where the page had
 * one (the link dot, a set status bit, the live traces, MIL on). «→» is drawn, not typeset:
 * neither Roboto nor Roboto Mono has U+2192, and a fallback face would differ between Chrome and
 * the car.
 *
 * Exposes window.ScannerBoard = { draw(ctx, board, fixture) }; board.screen picks the screen.
 */
(function (root) {
  'use strict';
  const L = root.SCANNER_LAYOUT;
  const C = L.colors, T = L.type;
  // W is let: a system's page draws its content at the width the rail leaves (shell())
  let W = L.size[0];
  const H = L.size[1];

  /* ------------------------------------------------------------------ ink */
  const col = n => (n in C ? C[n] : n);
  function style(name) { const s = T[name]; if (!s) throw new Error('no text style ' + name); return s; }
  function face(s) { return s.mono ? L.fonts.mono : L.fonts.roboto; }
  function setFont(c, s) {
    c.font = (s.w || 400) + ' ' + s.size + 'px ' + (s.mono ? '"Roboto Mono"' : 'Roboto');
    c.letterSpacing = ((s.track || 0) * s.size) + 'px';
  }
  const ARROW = '→';
  function arrowW(s) {
    const A = L.fonts.arrow, track = (s.track || 0) * s.size;
    return (s.mono ? A.monoCells * L.fonts.mono.advance : A.roboto) * s.size + track;
  }
  function measure(c, str, s) {
    s = typeof s === 'string' ? style(s) : s;
    setFont(c, s);
    let w = 0;
    String(str).split(ARROW).forEach((p, i) => { if (i) w += arrowW(s); if (p) w += c.measureText(p).width; });
    return w;
  }
  // The baseline of a line box of the style's height whose top is at y.
  function base(y, s) {
    s = typeof s === 'string' ? style(s) : s;
    const f = face(s);
    return y + (s.lh - (f.ascent + f.descent) * s.size) / 2 + f.ascent * s.size;
  }
  function fit(c, str, s, max) {
    str = String(str);
    if (max == null || measure(c, str, s) <= max) return str;
    if (root.SCANNER_CUTS) root.SCANNER_CUTS.push(str); // a debugging hook: what had to be cut
    while (str.length && measure(c, str + '…', s) > max) str = str.slice(0, -1);
    return str.replace(/\s+$/, '') + '…';
  }
  // Draws str with its baseline at by; returns the width drawn.
  function glyphs(c, str, x, by, s, ink) {
    setFont(c, s);
    c.fillStyle = col(ink);
    c.textBaseline = 'alphabetic'; c.textAlign = 'left';
    let cx = x;
    String(str).split(ARROW).forEach((p, i) => {
      if (i) { arrow(c, cx, by, s, ink); cx += arrowW(s); }
      if (p) { c.fillText(p, cx, by); cx += c.measureText(p).width; }
    });
    return cx - x;
  }
  function arrow(c, x, by, s, ink) {
    const A = L.fonts.arrow, z = s.size, w = arrowW(s) - (s.track || 0) * z, pad = 0.07 * z;
    const y = by - A.y * z, x0 = x + pad, x1 = x + w - pad;
    c.save();
    c.strokeStyle = col(ink); c.lineWidth = A.stroke * z; c.lineCap = 'round'; c.lineJoin = 'round';
    c.beginPath(); c.moveTo(x0, y); c.lineTo(x1, y);
    c.moveTo(x1 - A.head * z, y - A.spread * z); c.lineTo(x1, y); c.lineTo(x1 - A.head * z, y + A.spread * z);
    c.stroke(); c.restore();
  }
  /*
   * One line of text in a line box: top is the box's top, the style's lh its height.
   * o.align: 'left' (x is the start) | 'right' (x is the end) | 'center' (x is the middle);
   * o.max: the room - a longer word ends in an ellipsis, as the page's text-overflow did.
   */
  function text(c, str, x, top, sn, ink, o) {
    o = o || {};
    const s = style(sn);
    str = fit(c, str, s, o.max);
    const w = measure(c, str, s);
    const sx = o.align === 'right' ? x - w : o.align === 'center' ? x - w / 2 : x;
    glyphs(c, str, sx, base(top, s), s, ink);
    return w;
  }
  // Runs of different styles on one baseline: [[str, style, ink], ...]; returns the end x.
  function runs(c, parts, x, by, max) {
    let cx = x;
    const end = max == null ? Infinity : x + max;
    for (const [str, sn, ink] of parts) {
      const s = style(sn);
      const room = end - cx;
      if (room <= 0) break;
      const t = fit(c, str, s, isFinite(room) ? room : null);
      cx += glyphs(c, t, cx, by, s, ink);
      if (t !== str) break;
    }
    return cx;
  }
  // Greedy wrap at spaces, as the browser broke the page's paragraphs.
  function wrap(c, str, sn, max) {
    const words = String(str).split(' ');
    const lines = [];
    let line = '';
    for (const w of words) {
      const t = line ? line + ' ' + w : w;
      if (line && measure(c, t, sn) > max) { lines.push(line); line = w; } else line = t;
    }
    if (line) lines.push(line);
    return lines;
  }
  // A paragraph: lines of the style's lh from top; returns its height.
  // At most two lines: what does not fit the first goes on the second, which text() cuts.
  function twoLines(c, str, sn, max) {
    const lines = wrap(c, str, sn, max);
    return lines.length <= 2 ? lines : [lines[0], lines.slice(1).join(' ')];
  }
  function para(c, str, x, top, sn, ink, max) {
    const lines = wrap(c, str, sn, max), lh = style(sn).lh;
    lines.forEach((l, i) => text(c, l, x, top + i * lh, sn, ink));
    return lines.length * lh;
  }

  /* --------------------------------------------------------------- shapes */
  function rrect(c, x, y, w, h, r) { c.beginPath(); c.roundRect(x, y, w, h, r || 0); }
  function fill(c, x, y, w, h, ink, r) { if (!ink || ink === 'transparent') return; rrect(c, x, y, w, h, r); c.fillStyle = col(ink); c.fill(); }
  // A 1 dp line on the box's inner edge: the page's inset box-shadow, or a border.
  function edge(c, x, y, w, h, ink, r, dash) {
    if (!ink) return;
    c.save();
    rrect(c, x + 0.5, y + 0.5, w - 1, h - 1, Math.max(0, (r || 0) - 0.5));
    c.strokeStyle = col(ink); c.lineWidth = 1;
    if (dash) c.setLineDash(dash);
    c.stroke(); c.restore();
  }
  function rule(c, x, y, w, ink) { c.fillStyle = col(ink || 'rule'); c.fillRect(x, y, w, 1); }
  function vrule(c, x, y, h, ink) { c.fillStyle = col(ink || 'rule'); c.fillRect(x, y, 1, h); }
  function glow(c, blur, ink, draw) {
    c.save();
    c.shadowColor = col(ink); c.shadowBlur = blur * c.getTransform().a;
    draw(); c.restore();
  }
  function stroke(c, d, ink, width, dash, cap) {
    c.save();
    c.strokeStyle = col(ink); c.lineWidth = width;
    if (dash) c.setLineDash(dash);
    if (cap) { c.lineCap = cap; c.lineJoin = 'round'; }
    c.stroke(typeof d === 'string' ? new Path2D(d) : d);
    c.restore();
  }
  // Grid columns: numbers are dp, 'Nfr' share what is left; returns [[x, w], ...].
  function columns(spec, x, w, gap) {
    gap = gap || 0;
    const fixed = spec.reduce((a, v) => a + (typeof v === 'number' ? v : 0), 0) + gap * (spec.length - 1);
    const frs = spec.reduce((a, v) => a + (typeof v === 'string' ? parseFloat(v) : 0), 0);
    const unit = frs ? (w - fixed) / frs : 0;
    let cx = x;
    return spec.map(v => { const cw = typeof v === 'number' ? v : parseFloat(v) * unit; const r = [cx, cw]; cx += cw + gap; return r; });
  }

  /* ---------------------------------------------------------------- parts */
  // A status chip: the tone's ink on its fill, or inside its line. size: '' | 'small' | 'big'.
  function badge(c, str, x, top, tone, size) {
    const B = L.badge, g = size ? B[size] : B, sn = size === 'small' ? 'badgeSmall' : size === 'big' ? 'badgeBig' : 'badge';
    const t = L.tones[tone];
    const w = measure(c, str, sn) + 2 * g.pad;
    fill(c, x, top, w, g.h, t.fill, B.r);
    edge(c, x, top, w, g.h, t.line, B.r);
    text(c, str, x + g.pad, top + (g.h - style(sn).lh) / 2, sn, t.ink);
    return w;
  }
  // A footer button; icon 'rec' is the recording's red dot before the label.
  function button(c, str, x, kind, icon) {
    const F = L.footer, b = F.button, top = F.top + 1 + (F.height - 1 - b.h) / 2;
    const iw = icon ? 2 * L.sign.r + L.rail.mark.gap : 0;
    const w = measure(c, str, 'button') + 2 * b.pad + 2 + iw;
    const primary = kind === 'primary';
    fill(c, x, top, w, b.h, primary ? 'rgba(67,159,252,0.10)' : 'quiet', b.r);
    edge(c, x, top, w, b.h, primary ? 'rgba(67,159,252,0.55)' : 'quietLine', b.r);
    if (icon) sign(c, 'active', x + 1 + b.pad + L.sign.r, top + b.h / 2);
    text(c, str, x + 1 + b.pad + iw, top + (b.h - style('button').lh) / 2, 'button', primary ? 'blueInk' : 'text');
    return w;
  }
  function header(c, h) {
    const Hd = L.header, room = Hd.height - 1;
    fill(c, 0, 0, W, Hd.height, 'bar');
    rule(c, 0, Hd.height - 1, W);
    let x = Hd.padLeft;
    h.crumbs.forEach((t, i) => {
      const now = i === h.crumbs.length - 1;
      const sn = now ? 'crumbNow' : 'crumb';
      x += text(c, t, x, (room - style(sn).lh) / 2, sn, now ? 'white' : 'mute') + Hd.gap;
      if (!now) x += text(c, '›', x, (room - style('crumb').lh) / 2, 'crumb', 'sep') + Hd.gap;
    });
    // the close button, then right to left the car: name · mileage · mode · 12 V · the link's median
    // exchange after its dot · the time, a «·» between them
    const K = Hd.close, kx = W - Hd.padRight - K.w, ky = (room - K.h) / 2;
    fill(c, kx, ky, K.w, K.h, 'quiet', K.r);
    const ix = kx + (K.w - K.icon) / 2, iy = ky + (K.h - K.icon) / 2, a = K.icon / 8, b = K.icon - a;
    stroke(c, `M${ix + a} ${iy + a}L${ix + b} ${iy + b}M${ix + b} ${iy + a}L${ix + a} ${iy + b}`, 'text', K.stroke, null, 'round');
    const by = base((room - style('meta').lh) / 2, 'meta');
    const items = [
      [[h.car, 'meta', 'mute']],
      h.odo && [[h.odo, 'metaMono', 'dim']],
      h.mode && [[h.mode, 'meta', 'dim']],
      h.volts && [['12 В', 'meta', 'mute'], [h.volts, 'metaMono', h.voltsOut ? 'orange' : 'dim']],
      h.link && [['dot'], [h.link, 'metaMono', 'dim']],
      [[h.time, 'metaMono', 'mute']],
    ].filter(Boolean);
    const partW = p => p[0] === 'dot' ? Hd.dot + Hd.dotGap - Hd.voltsGap : measure(c, p[0], p[1]);
    const itemW = it => it.reduce((s, p, i) => s + partW(p) + (i ? Hd.voltsGap : 0), 0);
    const sepW = measure(c, '·', 'meta');
    let mx = kx - Hd.gap - items.reduce((s, it) => s + itemW(it), 0) - (items.length - 1) * (2 * Hd.metaGap + sepW);
    items.forEach((it, k) => {
      if (k) { glyphs(c, '·', mx + Hd.metaGap, by, style('meta'), 'sep'); mx += 2 * Hd.metaGap + sepW; }
      it.forEach((p, i) => {
        if (i) mx += Hd.voltsGap;
        if (p[0] === 'dot') {
          glow(c, Hd.dotGlow, 'blue', () => fill(c, mx, (room - Hd.dot) / 2, Hd.dot, Hd.dot, 'blue', Hd.dot / 2));
          mx += Hd.dot + Hd.dotGap - Hd.voltsGap;
          return;
        }
        mx += glyphs(c, p[0], mx, by, style(p[1]), p[2]);
      });
    });
  }

  // A block's or a system's head: title and its aside over the mono line; counters at the right.
  // A counter is as wide as the block's 112 dp, or as its widest value and a padding's room after
  // it, rounded up to 8 dp.
  // A counter's value is its figure and, after the last space, its unit when that has no digit in it:
  // «553 В» is 553 in the figure's style and «В» smaller beside it, «1 250» stays whole.
  function splitUnit(v) {
    const i = String(v).lastIndexOf(' ');
    return i > 0 && !/\d/.test(v.slice(i + 1)) ? [v.slice(0, i), v.slice(i + 1)] : [String(v), ''];
  }
  function counterValueW(c, v) {
    const [n, u] = splitUnit(v);
    return measure(c, n, 'counterBig') + (u ? L.header.voltsGap + measure(c, u, 'unit') : 0);
  }
  // A counter is as wide as the block's 112 dp, or as its widest value - a sign before it, where it
  // has one - and a padding's room after it, rounded up to 8 dp.
  function counterWidth(c, counters) {
    const B = L.block, widest = Math.max(0, ...(counters || []).map(([, v, tone]) => counterValueW(c, v) + (SIGNED[tone] ? signW() : 0)));
    return Math.max(B.counterW, Math.ceil((widest + B.counterPad) / B.counterStep) * B.counterStep);
  }
  // A counter's tone: 'out' outside its norm (orange), 'on' MIL on (red, with a glow), 'hist' / 'pending'
  // / 'active' a count of records after its sign.
  const SIGNED = { hist: 'hist', pending: 'pending', active: 'active' };
  const signW = () => 2 * L.sign.r + L.rail.mark.gap;
  function blockHead(c, b) {
    const B = L.block, cw = counterWidth(c, b.counters);
    const lab = style('counterLabel'), val = style('counterBig');
    let right = W - 24;
    if (b.counters && b.counters.length) {
      const n = b.counters.length, top = B.top + (B.height - lab.lh - B.counterGap - val.lh) / 2;
      const box = cw + B.counterPad + 1;
      right = W - 24 - n * box;
      b.counters.forEach(([label, value, tone], i) => {
        const x = right + i * box;
        vrule(c, x, top, lab.lh + B.counterGap + val.lh);
        text(c, label, x + 1 + B.counterPad, top, 'counterLabel', 'mute');
        let vx = x + 1 + B.counterPad;
        const vt = top + lab.lh + B.counterGap, by = base(vt, val);
        if (SIGNED[tone]) { sign(c, tone, vx + L.sign.r, vt + val.lh / 2); vx += signW(); }
        const [num, unit] = splitUnit(value);
        const ink = tone === 'on' ? 'red' : tone === 'out' ? 'orange' : 'white';
        const draw = () => glyphs(c, num, vx, by, val, ink);
        if (tone === 'on') glow(c, 10, 'rgba(255,64,70,0.7)', draw);
        const w = draw();
        if (unit) glyphs(c, unit, vx + w + L.header.voltsGap, by, style('unit'), tone === 'out' ? 'orange' : 'dim');
      });
    }
    const t = style('title'), ln = style('line');
    const top = B.top + (B.height - t.lh - B.titleGap - ln.lh) / 2;
    const room = right - 24;
    // the title: a word, or runs - a code is its letters in white and its failure type in grey
    const title = Array.isArray(b.title) ? b.title : [[b.title, 'title', b.titleInk || 'white']];
    runs(c, [...title, [' ', 'title', 'white'], [b.aside || '', 'titleAside', 'mute']], 24, base(top, t), room);
    text(c, b.line, 24, top + t.lh + B.titleGap, 'line', 'dim2', { max: room });
  }

  // The signs of a record's kind, drawn: ● active (red), ◌ pending and ○ in history (orange rings,
  // the pending one dashed), a dashed square for a block that did not answer. cx, cy: the middle.
  function sign(c, kind, cx, cy) {
    const S = L.sign;
    if (kind === 'active') { dot(c, cx, cy, S.r, 'red'); return; }
    if (kind === 'none') { const q = S.square; edge(c, cx - q / 2, cy - q / 2, q, q, 'faint', 2, S.dash); return; }
    const p = new Path2D(); p.arc(cx, cy, S.r - S.stroke / 2, 0, 2 * Math.PI);
    stroke(c, p, 'orange', S.stroke, kind === 'pending' ? S.dash : null);
  }
  // A signal's place on «Графики» (L.pick): blue with its lane's number - on the graphs; an empty thin
  // frame - not, a tap away; the frame pale once four are chosen and no more can be. x, y: top left.
  function pickBox(c, x, y, n, pale) {
    const P = L.pick, s = P.size, h = P.stroke / 2;
    if (n) {
      fill(c, x, y, s, s, 'blue', P.r);
      text(c, String(n), x + s / 2, y + (s - style('pickNum').lh) / 2, 'pickNum', 'ground', { align: 'center' });
      return;
    }
    c.save(); rrect(c, x + h, y + h, s - 2 * h, s - 2 * h, P.r - h);
    c.strokeStyle = col(pale ? 'quietLine' : 'faint'); c.lineWidth = P.stroke; c.stroke(); c.restore();
  }
  // «×»: the signal off the graphs; cx, cy its middle.
  function closeMark(c, cx, cy) {
    const K = L.pick.close, k = K.size / 2;
    stroke(c, `M${cx - k} ${cy - k}L${cx + k} ${cy + k}M${cx + k} ${cy - k}L${cx - k} ${cy + k}`, 'mute', K.stroke, null, 'round');
  }
  // A record's kind from its status byte: TF active, else PDTC pending, else in history.
  const kindOf = s => (s & 1) ? 'active' : (s & 4) ? 'pending' : 'hist';
  const RANK = { active: 0, pending: 1, hist: 2 };
  // The most severe kind of [kinds], or null.
  const worst = kinds => kinds.reduce((a, k) => a == null || RANK[k] < RANK[a] ? k : a, null);
  // A chevron in a box of L.chevron at x, y.
  function chevron(c, x, y) {
    const ch = L.chevron, k = ch / 24;
    stroke(c, `M${x + 9 * k} ${y + 5 * k}L${x + 16 * k} ${y + 12 * k}L${x + 9 * k} ${y + 19 * k}`, 'faint', 2 * k, null, 'round');
  }
  // Russian counts: 1 запись, 2 записи, 5 записей.
  function plural(n, one, few, many) {
    const a = Math.abs(n) % 100, b = a % 10;
    return a > 10 && a < 20 ? many : b === 1 ? one : b >= 2 && b <= 4 ? few : many;
  }
  // A code in runs: its letters in [ink], its failure type («-87») in grey.
  function codeRuns(code, sn, ink, typeSn) {
    const i = code.indexOf('-');
    return i < 0 ? [[code, sn, ink]] : [[code.slice(0, i), sn, ink], [code.slice(i), typeSn || sn, 'mute']];
  }

  function tabs(c, t) {
    const N = L.tabs, s = style('tab'), top = N.top + (N.height - s.lh) / 2;
    let x = 24;
    t.items.forEach((label, i) => {
      const on = i === t.active;
      let w = text(c, label, x, top, 'tab', on ? 'white' : 'mute');
      if (t.alert === i) {
        const d = L.header.dot;
        glow(c, 6, 'red', () => fill(c, x + w + N.dotGap, N.top + (N.height - d) / 2, d, d, 'red', d / 2));
        w += N.dotGap + d;
      }
      if (on) fill(c, x, N.top + N.height - N.underline, w, N.underline, 'blue');
      x += w + N.gap;
    });
    rule(c, 24, N.top + N.height, W - 48);
    // a view switch at the row's right end: «Таблица | Графики · 3», the number the signals ticked
    if (t.right) {
      const G = L.segmented, y = N.top + (N.height - G.h) / 2;
      const ws = t.right.items.map(s => measure(c, s, 'segment') + 2 * G.pad);
      let sx = W - 24 - ws.reduce((a, v) => a + v, 0) - (ws.length - 1) * G.gap;
      t.right.items.forEach((s, i) => {
        const on = i === t.right.active;
        if (on) { fill(c, sx, y, ws[i], G.h, 'quiet', G.r); edge(c, sx, y, ws[i], G.h, 'quietLine', G.r); }
        // `off`: an item that cannot be chosen now - «Графики · 0» with nothing ticked - in the faint ink
        text(c, s, sx + G.pad, y + (G.h - style('segment').lh) / 2, 'segment', on ? 'white' : t.right.off === i ? 'faint' : 'mute');
        sx += ws[i] + G.gap;
      });
    }
  }

  // The footer: buttons from the left; notes from the right, '|' a divider.
  function footer(c, f) {
    const F = L.footer;
    fill(c, 0, F.top, W, F.height, 'bar');
    rule(c, 0, F.top, W);
    const room = F.height - 1, top0 = F.top + 1;
    let x = 24;
    (f.buttons || []).forEach(([label, kind, icon]) => { x += button(c, label, x, kind, icon) + F.gap; });
    let r = W - 24;
    for (let i = (f.notes || []).length - 1; i >= 0; i--) {
      const n = f.notes[i];
      if (n === '|') { const [dw, dh] = F.divider; fill(c, r - dw, top0 + (room - dh) / 2, dw, dh, 'divider'); r -= dw + F.gap; continue; }
      // [str, mono, 'rec']: a recording under way - the red dot before its time
      const [str, mono, mark] = Array.isArray(n) ? n : [n, false];
      const sn = mono ? 'noteMono' : 'note';
      r -= text(c, str, r, top0 + (room - style(sn).lh) / 2, sn, mark === 'rec' ? 'dim2' : 'faint', { align: 'right' });
      if (mark === 'rec') { r -= L.rail.mark.gap + L.sign.r; sign(c, 'active', r, top0 + room / 2); r -= L.sign.r; }
      r -= F.gap;
    }
  }

  // A header row of column captions with its rule; returns the y under the rule.
  function headRow(c, cols, labels, top, h, sn, opts) {
    opts = opts || {};
    const s = style(sn || 'colHead');
    labels.forEach((lab, i) => {
      if (!lab) return;
      const [x, w] = cols[i];
      const [t, align, pad] = Array.isArray(lab) ? lab : [lab, 'left', i === 0 ? 12 : 0];
      const tx = align === 'right' ? x + w - pad : align === 'center' ? x + w / 2 : x + pad;
      text(c, t, tx, top + (h - s.lh) / 2, sn || 'colHead', 'mute', { align });
    });
    rule(c, cols[0][0], top + h, opts.width || (cols[cols.length - 1][0] + cols[cols.length - 1][1] - cols[0][0]));
    return top + h + 1;
  }
  const mid = (top, h, sn) => top + (h - style(sn).lh) / 2;

  /* ============================================================== screens */
  const reply = req => req ? req + ' → ' + (parseInt(req, 16) + 8).toString(16).toUpperCase() : '—';
  // A block's records, whatever their kind: numbers or the counts' strings
  const records = b => ['active', 'pending', 'history'].reduce((a, k) => a + (Number(b[k]) || 0), 0);
  const blockKind = b => Number(b.active) ? 'active' : Number(b.pending) ? 'pending' : Number(b.history) ? 'hist' : null;
  // F194 in groups of four: «4B4D 0011 DE01 1406 00»
  const f194 = s => s ? s.replace(/(.{4})/g, '$1 ').trim() : '—';
  // A U-code of the type 87: a message lost between blocks
  const netLoss = code => /^U/.test(code) && /-87$/.test(code);

  /* --------------------------------------------- systems down the left */
  // The section built around the car's systems: «Сводка» and «Блоки» first, the systems, «Журнал»
  // last; the rest of the screen is the chosen one's, its own tabs inside it. A mark says only what
  // the item's look cannot: its records - the count after the sign of the most severe kind - or, on
  // «Блоки», how many there are. Nothing is written while all is well.
  function rail(c, R) {
    const S = L.rail, top = L.header.height, bottom = L.footer.top;
    fill(c, 0, top, S.w, bottom - top, 'bar');
    fill(c, S.w - 1, top, 1, bottom - top, 'rule');
    let y = top + S.padTop;
    R.items.forEach(it => {
      if (it === '|') { y += S.sepGap; rule(c, S.padX, y, S.w - 2 * S.padX); y += S.sepGap + 1; return; }
      const on = it.name === R.active;
      if (on) { fill(c, 0, y, S.w - 1, S.item, 'quiet'); fill(c, 0, y, S.bar, S.item, 'blue'); }
      let r = S.w - S.padX;
      const kind = blockKind(it), n = it.count != null ? it.count : records(it);
      if (kind || it.count != null) {
        r -= text(c, String(n), r, mid(y, S.item, 'railMark'), 'railMark', kind === 'active' ? 'redInk' : kind ? 'ink3' : 'faint', { align: 'right' });
        if (kind) { r -= S.mark.gap + L.sign.r; sign(c, kind, r, y + S.item / 2); r -= L.sign.r; }
        r -= 8;
      }
      text(c, it.name, S.padX, mid(y, S.item, 'railItem'), 'railItem', on ? 'white' : 'ink3', { max: r - S.padX });
      y += S.item;
    });
  }

  function shell(c, f, body) {
    header(c, f.top);
    rail(c, f.rail);
    const full = W;
    W = full - L.rail.w;
    c.save(); c.translate(L.rail.w, 0);
    try { body(); } finally { c.restore(); W = full; }
    footer(c, f.footer);
  }

  // A chip of letters in a thin frame, ending at xr: «СЕТЬ».
  function tagChip(c, str, xr, cy) {
    const G = L.summary.tag, w = measure(c, str, 'tag') + 2 * G.pad;
    edge(c, xr - w, cy - G.h / 2, w, G.h, 'line3', G.r);
    text(c, str, xr - w + G.pad, cy - style('tag').lh / 2, 'tag', 'dim');
    return w;
  }

  /* ------------------------------------------------------------ Сводка */
  // The section's entry: the verdict, the scan, the four values a diagnostician checks first and every
  // record of the last scan by system and block - systems and blocks most severe first, then by how
  // many records; the systems in two columns, each into the shorter one.
  function summary(c, f) {
    shell(c, f, () => {
      const S = L.summary, sc = f.scan;
      const all = f.systems.flatMap(s => s.blocks.flatMap(b => b.codes.map(d => kindOf(d.status))));
      const count = k => all.filter(x => x === k).length, act = count('active');
      blockHead(c, {
        title: act ? act + ' ' + plural(act, 'активная неисправность', 'активные неисправности', 'активных неисправностей') : 'Активных неисправностей нет',
        titleInk: act ? 'red' : 'white',
        line: `скан ${sc.at} · ${sc.answered} из ${sc.blocks} блоков · маска ${sc.mask} · ${sc.seconds} с`,
        counters: f.vitals.map(v => [v.key, v.value + (v.unit ? ' ' + v.unit : ''), v.out ? 'out' : undefined]),
      });
      // the band: one item a kind present, the last with the blocks
      const B = S.band, cy = B.top + B.h / 2, nBlocks = f.systems.reduce((a, s) => a + s.blocks.length, 0);
      const kinds = [['active', 'АКТИВНЫЕ'], ['pending', 'ОЖИДАЮЩИЕ'], ['hist', 'В ИСТОРИИ']].filter(([k]) => count(k));
      let x = 24;
      kinds.forEach(([k, word], i) => {
        sign(c, k, x + L.sign.r, cy); x += 2 * L.sign.r + B.gap;
        const n = count(k);
        let t = `${word} · ${n} ${plural(n, 'ЗАПИСЬ', 'ЗАПИСИ', 'ЗАПИСЕЙ')}`;
        if (i === kinds.length - 1) t += ` · ${nBlocks} ${plural(nBlocks, 'БЛОК', 'БЛОКА', 'БЛОКОВ')}`;
        x += text(c, t, x, mid(B.top, B.h, 'groupTitle'), 'groupTitle', 'dim') + B.itemGap;
      });
      const net = f.systems.flatMap(s => s.blocks.flatMap(b => b.codes)).filter(d => netLoss(d.code)).length;
      if (net) text(c, `в т. ч. ${net} — потеря связи между блоками, U…-87`, W - 24, mid(B.top, B.h, 'note'), 'note', 'mute', { align: 'right' });
      rule(c, 24, B.top + B.h, W - 48);
      // the order: most severe first, then the most records, then the catalog's
      const order = (list, kindsOf, n) => list.map((v, i) => [v, i]).sort((a, b) =>
        (RANK[worst(kindsOf(a[0]))] - RANK[worst(kindsOf(b[0]))]) || (n(b[0]) - n(a[0])) || (a[1] - b[1])).map(p => p[0]);
      const bKinds = b => b.codes.map(d => kindOf(d.status));
      const systems = order(f.systems, s => s.blocks.flatMap(bKinds), s => s.blocks.reduce((a, b) => a + b.codes.length, 0))
        .map(s => Object.assign({}, s, { blocks: order(s.blocks, bKinds, b => b.codes.length) }));
      const colY = [S.top, S.top], colX = [24, 24 + S.colW + S.colGap];
      systems.forEach(s => {
        const k = colY[1] < colY[0] ? 1 : 0, x0 = colX[k];
        let y = colY[k] + (colY[k] > S.top ? S.systemGap : 0);
        const n = s.blocks.reduce((a, b) => a + b.codes.length, 0);
        text(c, `${s.name.toUpperCase()} · ${n}`, x0, y, 'groupTitle', 'mute');
        y += S.label;
        s.blocks.forEach((b, i) => { if (i) y += S.blockGap; y = blockGroup(c, b, x0, y, S.colW); });
        colY[k] = y;
      });
      // the blocks with nothing to say, under the left column
      const quiet = sc.blocks - f.systems.reduce((a, s) => a + s.blocks.length, 0), Q = S.quiet;
      const qt = `${quiet} ${plural(quiet, 'блок', 'блока', 'блоков')} без записей · ` +
        (sc.answered === sc.blocks ? 'все ответили' : `не ответили ${sc.blocks - sc.answered}`);
      const qy = colY[0] + Q.gap, qw = text(c, qt, 24, mid(qy, Q.h, 'unit'), 'unit', 'mute');
      chevron(c, 24 + qw + Q.chevronGap, qy + (Q.h - L.chevron) / 2);
    });
  }

  // A block's records on «Сводка»: the block's head - its sign, abbreviation, address, name, then how
  // many records and since when - and a row a record. A row's sign only where it is not in history.
  function blockGroup(c, b, x, y, w) {
    const S = L.summary, h = S.head, kinds = b.codes.map(d => kindOf(d.status)), kind = worst(kinds);
    sign(c, kind, x + L.sign.r, y + h / 2);
    let tx = x + 2 * L.sign.r + S.headGap;
    tx += text(c, b.abbr, tx, mid(y, h, 'codeSmall'), 'codeSmall', 'white') + 8;
    tx += text(c, b.addr, tx, mid(y, h, 'cellAbbr'), 'cellAbbr', 'faint') + S.headGap;
    const rw = text(c, `${b.codes.length} · с ${b.since}`, x + w, mid(y, h, 'note'), 'note', 'mute', { align: 'right' });
    text(c, b.name, tx, mid(y, h, 'titleAside'), 'titleAside', 'ink3', { max: x + w - rw - S.headGap - tx });
    rule(c, x, y + h, w);
    y += h + 1;
    const cols = columns(S.rowCols, x + S.rowPad, w - S.rowPad, S.rowGap);
    b.codes.forEach((d, i) => {
      const k = kinds[i], rh = S.row;
      if (k !== 'hist') sign(c, k, x + S.rowPad / 2, y + rh / 2);
      runs(c, codeRuns(d.code, 'codeSmall', k === 'active' ? 'red' : 'white', 'cellMono'), cols[0][0], base(mid(y, rh, 'codeSmall'), 'codeSmall'));
      text(c, d.meaning || d.type, cols[1][0], mid(y, rh, 'cellSmall'), 'cellSmall', d.meaning ? 'text' : 'dim', { max: cols[1][1] });
      if (netLoss(d.code)) tagChip(c, 'СЕТЬ', cols[2][0] + cols[2][1], y + rh / 2);
      chevron(c, cols[3][0], y + (rh - L.chevron) / 2);
      rule(c, x, y + rh, w, 'hair');
      y += rh + 1;
    });
    return y;
  }

  /* ------------------------------------------------------------- Блоки */
  // Every block on one table: those with records first, the most severe and the most records first,
  // then the rest in the catalog's order; F194 and the block's median answer. A row opens the block.
  function blocksTable(c, blocks) {
    const M = L.modules, cols = columns(M.cols, 24, W - 48), P = M.numPad, Li = M.list;
    headRow(c, cols, ['', 'БЛОК', 'НАЗНАЧЕНИЕ', 'ЗАПРОС → ОТВЕТ', 'ПРОТОКОЛ', ['ЗАПИСИ', 'right', P], 'НОМЕР ПО · F194', ['ОТВЕТ', 'right', P], ''],
      M.top, M.head);
    const withRec = blocks.map((b, i) => [b, i]).filter(([b]) => records(b) > 0)
      .sort((a, b) => (RANK[blockKind(a[0])] - RANK[blockKind(b[0])]) || (records(b[0]) - records(a[0])) || (a[1] - b[1])).map(p => p[0]);
    const without = blocks.filter(b => !records(b));
    c.save();
    c.beginPath(); c.rect(24, Li.top, W - 48, Li.bottom - Li.top); c.clip();
    let y = Li.top;
    const group = t => {
      text(c, t, 24 + M.groupPad, mid(y, Li.group - 1, 'groupTitle'), 'groupTitle', 'dim');
      rule(c, 24, y + Li.group - 1, W - 48);
      y += Li.group;
    };
    const row = b => {
      const h = Li.row, k = blockKind(b), n = records(b), silent = b.answered === false;
      if (k || silent) sign(c, silent ? 'none' : k, cols[0][0] + cols[0][1] / 2, y + h / 2);
      text(c, b.abbr, cols[1][0], mid(y, h, 'codeSmall'), 'codeSmall', silent ? 'dim' : 'white', { max: cols[1][1] - 8 });
      text(c, b.name, cols[2][0], mid(y, h, 'cell'), 'cell', silent ? 'dim' : 'text', { max: cols[2][1] - 12 });
      text(c, reply(b.req), cols[3][0], mid(y, h, 'cellMono'), 'cellMono', 'dim');
      text(c, b.proto || '—', cols[4][0], mid(y, h, 'cellSmall'), 'cellSmall', 'dim');
      text(c, n ? String(n) : '—', cols[5][0] + cols[5][1] - P, mid(y, h, 'cellNum'), 'cellNum', n ? 'text' : 'faint3', { align: 'right' });
      text(c, f194(b.f194), cols[6][0], mid(y, h, 'sig'), 'sig', b.f194 ? 'dim' : 'faint3');
      text(c, b.ms != null ? b.ms + ' мс' : '—', cols[7][0] + cols[7][1] - P, mid(y, h, 'noteMono'), 'noteMono', b.ms != null ? 'mute' : 'faint3', { align: 'right' });
      if (b.req) chevron(c, cols[8][0] + (cols[8][1] - L.chevron) / 2, y + (h - L.chevron) / 2);
      rule(c, 24, y + h, W - 48, 'hair');
      y += h + Li.rule;
    };
    if (withRec.length) { group(`С ЗАПИСЯМИ · ${withRec.length}`); withRec.forEach(row); }
    if (without.length) { group(`БЕЗ ЗАПИСЕЙ · ${without.length}`); without.forEach(row); }
    c.restore();
  }

  function modules(c, f) {
    shell(c, f, () => {
      const sc = f.scan, blocks = f.groups.flatMap(g => g.ecus);
      const silent = blocks.filter(b => b.answered === false).length, changed = blocks.filter(b => b.swChanged).length;
      const withRec = blocks.filter(b => records(b) > 0).length;
      blockHead(c, { title: 'Блоки', aside: `· ${blocks.length} · ` + (silent ? `не ответили ${silent}` : 'все ответили'),
        line: `скан ${sc.at} · ${sc.exchanges} обменов · ${sc.seconds} с · маска ${sc.mask}` });
      const filter = f.filter || 0;
      tabs(c, { items: [`Все · ${blocks.length}`, `С записями · ${withRec}`, `Без ответа · ${silent}`, `ПО изменено · ${changed}`], active: filter });
      const shown = [() => true, b => records(b) > 0, b => b.answered === false, b => b.swChanged][filter];
      blocksTable(c, blocks.filter(shown));
    });
  }

  function sysLive(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); liveTable(c, f, L.rail.liveCols); });
  }

  // A system's codes, every block's on one table: the block named on its first row, a rule
  // between blocks; a row opens the block with its status byte. An active code is red.
  function sysDtc(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block);
      tabs(c, f.tabs);
      const D = L.rail.dtc, cols = columns(D.cols, 24, W - 48), P = 12;
      let y = headRow(c, cols, ['БЛОК', 'КОД', 'ТИП ОТКАЗА (FTB)', 'ОПИСАНИЕ', 'СТАТУС'], L.ecuLive.top, L.ecuLive.head);
      let last = null;
      for (const r of f.codes) {
        const h = D.row;
        if (r.block !== last && last !== null) rule(c, 24, y - 1, W - 48);
        if (r.active) fill(c, 24, y, W - 48, h, 'redRow');
        if (r.block !== last) {
          text(c, r.block, cols[0][0] + P, mid(y, h, 'cell'), 'cell', 'white');
          text(c, r.addr, cols[0][0] + P + measure(c, r.block, 'cell') + 8, mid(y, h, 'cellMono'), 'cellMono', 'faint');
        }
        last = r.block;
        text(c, r.code, cols[1][0], mid(y, h, 'code'), 'code', r.active ? 'red' : 'white');
        runs(c, [[r.ftb, 'cellNum', 'dim'], [' · ' + r.ftbName, 'cell', 'text']], cols[2][0], base(mid(y, h, 'cell'), 'cell'), cols[2][1] - 12);
        text(c, r.desc, cols[3][0], mid(y, h, 'cell'), 'cell', 'text', { max: cols[3][1] - 16 });
        runs(c, [[r.status, 'cellMono', 'text'], ['  ' + r.word, 'cellSmall', 'dim']], cols[4][0], base(mid(y, h, 'cell'), 'cell'), cols[4][1]);
        chevron(c, cols[5][0] + (cols[5][1] - L.chevron) / 2, y + (h - L.chevron) / 2);
        rule(c, 24, y + h, W - 48, 'hair');
        y += h + 1;
      }
    });
  }

  // A system's «Блоки»: its blocks on the table of «Блоки».
  function sysBlocks(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); blocksTable(c, f.ecus); });
  }

  // A tab with nothing to show yet: one sentence where the table would stand.
  function sysPending(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      text(c, f.pending, L.rail.pending.x, L.rail.pending.y, 'cell', 'dim');
    });
  }

  // «Флаги GB/T 32960»: the general alarm flags the stock reporter computes for GB/T 32960.3, each
  // from one reading of the car (device 1039), in the standard's bit order and grouped - the pack,
  // then the drive and high voltage. A flag in alarm is lit orange; one not read says «н/д».
  function sysGbFlags(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const G = L.gbFlags, T = G.tile, tw = (W - 48 - (G.cols - 1) * G.gap) / G.cols;
      let r = W - 24;
      [...f.counters].reverse().forEach(([k, v, on]) => {
        r -= text(c, v, r, G.headY - 3, 'valueBms', on ? 'orange' : 'white', { align: 'right' }) + 6;
        r -= text(c, k, r, G.headY, 'counterLabel', 'mute', { align: 'right' }) + 22;
      });
      text(c, f.head, 24, G.headY, 'counterLabel', 'mute');
      const ink = { ok: 'dim2', alarm: 'orange', none: 'faint' };
      f.groups.forEach((g, gi) => {
        text(c, g.title, 24, G.groupY[gi], 'counterLabel', 'mute');
        g.flags.forEach((fl, i) => {
          const row = gi === 0 ? Math.floor(i / G.cols) : 2, col = i % G.cols;
          const x = 24 + col * (tw + G.gap), y = G.rowY[row], alarm = fl.state === 'alarm';
          fill(c, x, y, tw, T.h, alarm ? 'orangeFill' : 'ground', T.r);
          edge(c, x, y, tw, T.h, alarm ? 'orange' : fl.state === 'none' ? 'line3' : 'quietLine', T.r);
          text(c, 'бит ' + fl.bit, x + T.padX, y + T.padY, 'counterLabel', 'mute');
          text(c, fl.fid, x + tw - T.padX, y + T.padY, 'tileAddr', 'faint', { align: 'right' });
          text(c, fl.name, x + T.padX, y + T.nameY + T.padY - 4, 'cellSmall', 'white', { max: tw - 2 * T.padX });
          text(c, fl.word, x + T.padX, y + T.stateY, 'cell', ink[fl.state]);
          if (fl.raw != null) text(c, 'код ' + fl.raw, x + tw - T.padX, y + T.stateY + 2, 'tileAddr', 'faint', { align: 'right' });
        });
      });
    });
  }

  // A system's readings as the engine's stream reads - the pack's and every other system's «Потоковые
  // данные»: value, the span seen, the norm and 60 s of it.
  function sysStream(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); liveTable(c, f, L.rail.packLiveCols, 'УСТР.'); });
  }

  // The drives or the driver-assistance blocks side by side.
  function sysDrivesCompare(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); drivesTable(c, f); });
  }
  function sysAdasCompare(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); adasTable(c, f); });
  }

  // The engine's DTC lists and monitor readiness, the block's own screen at the rail's width.
  function sysEngineDtc(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); obdLists(c, f, L.rail.ecuDtc); });
  }

  /* ------------------------------------------------- the car from above */
  // The body from above, front up, in lines: outline, glass, mirrors and wheels. Returns where the
  // schemes hang things: the axles, the sides, the corners.
  function car(c, cx, y0, h, inside) {
    const C = L.car, w = C.w, x0 = cx - w / 2;
    c.save();
    rrect(c, x0, y0, w, h, [C.noseR, C.noseR, C.tailR, C.tailR]);
    c.fillStyle = col('plate'); c.fill();
    c.lineWidth = 1.6; c.strokeStyle = col('wire3'); c.stroke();
    c.restore();
    const g = C.glass, ws = y0 + g.shield, rw = y0 + h - g.rear;
    stroke(c, `M${x0 + g.inset} ${ws + 10}Q${cx} ${ws - 14} ${x0 + w - g.inset} ${ws + 10}`, 'wire3', 1.4);
    stroke(c, `M${x0 + g.inset} ${rw - 8}Q${cx} ${rw + 12} ${x0 + w - g.inset} ${rw - 8}`, 'wire3', 1.4);
    // the roof; a scheme of the inside draws the cabin in its place
    if (!inside) edge(c, x0 + g.roofInset, ws + 18, w - 2 * g.roofInset, rw - ws - 34, 'line3', 14);
    const m = C.mirror;
    stroke(c, `M${x0} ${ws + 4}l${-m[0]} ${-m[1] / 2}v${-m[1]}l${m[0]} ${m[1] / 2}`, 'wire3', 1.4);
    stroke(c, `M${x0 + w} ${ws + 4}l${m[0]} ${-m[1] / 2}v${-m[1]}l${-m[0]} ${m[1] / 2}`, 'wire3', 1.4);
    const [ww, wh] = C.wheel, front = y0 + C.axle, rear = y0 + h - C.axle;
    [[x0, front], [x0 + w, front], [x0, rear], [x0 + w, rear]].forEach(([x, y]) => {
      fill(c, x - ww / 2, y - wh / 2, ww, wh, 'pidFill', 4); edge(c, x - ww / 2, y - wh / 2, ww, wh, 'line4', 4);
    });
    return { x0, y0, w, h, cx, front, rear, left: x0 - ww / 2, right: x0 + w + ww / 2, mid: y0 + h / 2 };
  }

  // A callout: a plate of lines - [text, style, ink] or runs [[t, style, ink], ...] - and its leader
  // to a point on the car, a dot at the end.
  function callout(c, x, y, w, rows, kind, to, bend) {
    const O = L.callout, k = TILE[kind || 'ok'];
    const h = 2 * O.padY + rows.reduce((a, r) => a + style(Array.isArray(r[0]) ? r[0][1] : r[1]).lh, 0) + (rows.length - 1) * O.gap;
    if (to) {
      // the leader bends half way, or at [bend] when the caller orders several bends
      const [tx, ty] = to, sx = tx < x ? x : x + w, sy = y + h / 2;
      stroke(c, ty === sy ? `M${sx} ${sy}H${tx}` : `M${sx} ${sy}H${bend == null ? (sx + tx) / 2 : bend}V${ty}H${tx}`, 'wire4', 1.2);
      c.beginPath(); c.arc(tx, ty, O.dot, 0, 2 * Math.PI); c.fillStyle = col('wire5'); c.fill();
    }
    fill(c, x, y, w, h, k.fill, O.r); edge(c, x, y, w, h, k.line, O.r);
    let ty = y + O.padY;
    rows.forEach(r => {
      if (Array.isArray(r[0])) { runs(c, r, x + O.padX, base(ty, r[0][1]), w - 2 * O.padX); ty += style(r[0][1]).lh + O.gap; }
      else { text(c, r[0], x + O.padX, ty, r[1], r[2], { max: w - 2 * O.padX }); ty += style(r[1]).lh + O.gap; }
    });
    return h;
  }
  const dot = (c, x, y, r, ink) => { c.beginPath(); c.arc(x, y, r, 0, 2 * Math.PI); c.fillStyle = col(ink); c.fill(); };
  function machine(c, x, y, glyph, live) {
    const R = L.callout.machineR;
    c.beginPath(); c.arc(x, y, R, 0, 2 * Math.PI); c.fillStyle = col('ground'); c.fill();
    stroke(c, (() => { const p = new Path2D(); p.arc(x, y, R, 0, 2 * Math.PI); return p; })(), live ? 'wire5' : 'wire4', 1.6, live ? null : [4, 3]);
    const s = style('glyph');
    glyphs(c, glyph, x - measure(c, glyph, s) / 2, y + 5, s, live ? 'text' : 'dim');
  }

  // A tile hung on the car: its leader from the tile's near side to a point on the car - straight
  // when the point is level with the tile's middle, bent half way otherwise - a dot at the end.
  function hang(c, e, x, y, to, cx) {
    if (!e) return;
    const T = L.tile, near = x < cx ? x + T.w : x, my = y + T.h / 2;
    stroke(c, to[1] === my ? `M${near} ${my}H${to[0]}` : `M${near} ${my}H${(near + to[0]) / 2}V${to[1]}H${to[0]}`, 'wire4', 1.2);
    dot(c, to[0], to[1], L.callout.dot, 'wire5');
    unitTile(c, x, y, e);
  }

  // A callout's height for its rows - [text, style, ink] or runs of them - as callout() lays them.
  function calloutH(rows) {
    const O = L.callout;
    return 2 * O.padY + rows.reduce((a, r) => a + style(Array.isArray(r[0]) ? r[0][1] : r[1]).lh, 0) + (rows.length - 1) * O.gap;
  }
  // A row of words - [text, style, ink] or runs of them - its line box's top at top, from x,
  // centred on it ('center') or ending at it ('right'); returns its width.
  function said(c, r, x, top, align) {
    const parts = Array.isArray(r[0]) ? r : [r], sn = parts[0][1];
    const w = parts.reduce((a, [t, s]) => a + measure(c, t, s), 0);
    runs(c, parts, align === 'center' ? x - w / 2 : align === 'right' ? x - w : x, base(top, sn));
    return w;
  }
  const D2R = Math.PI / 180;
  // The arc of the circle at x, y of radius r from a0 to a1 degrees, clockwise on the screen.
  const arcPath = (x, y, r, a0, a1) => { const p = new Path2D(); p.arc(x, y, r, a0 * D2R, a1 * D2R); return p; };
  // A lamp on the body: lit in its ink over a halo of it, dark in line4.
  function lamp(c, p, on, ink, w, halo) {
    if (on) { c.save(); c.globalAlpha = 0.25; stroke(c, p, ink, halo, null, 'round'); c.restore(); }
    stroke(c, p, on ? ink : 'line4', w, null, 'round');
  }

  // Шасси: the wheels' tyres at their corners, the brake and steering blocks where they sit, the
  // rear wheels' steering under their tyres, the motion controller in the middle with the
  // suspension's words under it, the parking brake's calipers in the rear wheels, the tyre-pressure
  // system quiet at the top right.
  function sysChassis(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const cx = (W) / 2, K = car(c, cx, L.car.top, L.car.h), O = L.callout, S = L.chassisScheme, T = L.tile;
      stroke(c, `M${K.x0} ${K.front}H${K.x0 + K.w}M${K.x0} ${K.rear}H${K.x0 + K.w}`, 'wire3', 1.4);
      const lx = O.leftX, rx = W - O.leftX - O.w;
      // each tyre: its pressure and temperature, then the TPMS's judgement of its pressure and its leak
      f.tyres.forEach(t => {
        const left = t.side === 'L', y = t.axle === 'F' ? K.front : K.rear;
        const rows = [[t.label, 'counterLabel', 'mute'], [[t.pressure, 'counter', 'white'], [' бар', 'unit', 'dim2']], [t.temp, 'cellMono', 'dim'], ...(t.states || [])];
        callout(c, left ? lx : rx, y - calloutH(rows) / 2, O.w, rows, 'ok', [left ? K.left : K.right, y]);
      });
      unitTile(c, cx - T.w / 2, K.y0 + 22, f.units.esp);
      stroke(c, `M${K.x0 + 20} ${K.front}H${K.x0 + K.w - 20}`, 'wire2', 3);
      unitTile(c, cx - T.w / 2, K.front - T.h / 2, f.units.eps);
      // the brake system's OBD interface above the left tyres, on its own: no common hardware or
      // software identity with ESP/IPB 782 is known, so no wire joins them
      unitTile(c, lx, L.content.top, f.units.bscm);
      // across from it, quiet: the tyre-pressure system's state and its sensors' batteries
      if (f.tpms) {
        text(c, f.tpms.label, rx, L.content.top, 'counterLabel', 'mute');
        said(c, f.tpms.row, rx, L.content.top + style('counterLabel').lh + O.gap);
      }
      hang(c, f.units.rwsL, lx, K.rear + S.steerDrop, [K.x0, K.rear + S.steerAt], cx);
      hang(c, f.units.rwsR, W - lx - T.w, K.rear + S.steerDrop, [K.x0 + K.w, K.rear + S.steerAt], cx);
      // the suspension's level and its height change between the left tyres, its leader to the motion controller
      if (f.suspension) callout(c, lx, K.mid - calloutH(f.suspension) / 2, O.w, f.suspension, 'ok', [cx - T.w / 2, K.mid]);
      if (f.units.idc) unitTile(c, cx - T.w / 2, K.mid - T.h / 2, f.units.idc);
      // the parking brake: a caliper inside each rear wheel - blue holding, orange at fault - and its word under the axle
      if (f.epb) {
        const P = S.caliper;
        [K.x0 + P.in, K.x0 + K.w - P.in - P.w].forEach(x => {
          const y = K.rear - P.h / 2;
          if (f.epb.look === 'held' || f.epb.look === 'fault') fill(c, x, y, P.w, P.h, f.epb.look === 'held' ? 'text' : 'orange', P.r);
          else if (f.epb.look === 'free') edge(c, x, y, P.w, P.h, 'wire3', P.r);
        });
        said(c, f.epb.row, cx, K.rear + S.epbY, 'center');
      }
    });
  }

  // Кузов: doors, locks and windows on the body; the zone controllers and lights where they sit;
  // the key - its RF receiver, UWB with its four anchors, keyless entry - in the middle; each
  // door's radar beside its door, the right NFC reader at the right B-pillar, the tailgate's
  // projection at the tail. The plates stand at the edges; the radars and the reader in the gaps
  // between the plates and the car.
  function sysBodyState(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const cx = W / 2, K = car(c, cx, L.car.top, L.car.h), O = L.callout, T = L.tile, B = L.bodyScheme;
      const D = L.car.doors, lx = B.x, rx = W - B.x - O.w;
      // the doors as heavy strokes on the sides: closed lies on the body, open would swing out
      f.doors.forEach(d => {
        const left = d.side === 'L', x = left ? K.x0 : K.x0 + K.w;
        const [a, b] = d.axle === 'F' ? D.front : D.rear;
        stroke(c, `M${x} ${K.y0 + a}V${K.y0 + b}`, d.open ? 'orange' : 'text', 3);
        const rows = [[d.label, 'counterLabel', 'mute'], [d.state, 'cell', 'white'], [d.detail, 'cellSmall', 'dim']];
        const h = 2 * O.padY + style('counterLabel').lh + style('cell').lh + style('cellSmall').lh + 2 * O.gap;
        callout(c, left ? lx : rx, K.y0 + (a + b) / 2 - h / 2, O.w, rows, 'ok', [x, K.y0 + (a + b) / 2]);
      });
      // the hood and the tailgate as the doors are, on the nose's and the tail's line: closed
      // blue, open orange, not read dark
      const Lm = B.lamps, nr = L.car.noseR, tr0 = L.car.tailR;
      const lid = open => open === true ? 'orange' : open === false ? 'text' : 'line4';
      if (f.hood) {
        const p = arcPath(K.x0 + nr, K.y0 + nr, nr, 270 - B.hood, 270);
        p.lineTo(K.x0 + K.w - nr, K.y0); p.arc(K.x0 + K.w - nr, K.y0 + nr, nr, -90 * D2R, (B.hood - 90) * D2R);
        stroke(c, p, lid(f.hood.open), 3);
      }
      if (f.trunk) stroke(c, `M${K.x0 + tr0} ${K.y0 + K.h}H${K.x0 + K.w - tr0}`, lid(f.trunk.open), 3);
      // the lamps: the headlamps at the nose's corners, blue on a beam and haloed on the high one;
      // at the tail's corners the stop lamps, orange when lit, and the reversing lamps, blue
      if (f.lamps) {
        const m = f.lamps, nose = [[K.x0 + nr, 1], [K.x0 + K.w - nr, -1]], tail = [[K.x0 + tr0, 1], [K.x0 + K.w - tr0, -1]];
        // an angle of the left corner, mirrored to the right one
        const span = ([a0, a1], dir) => dir > 0 ? [a0, a1] : [180 - a1, 180 - a0];
        nose.forEach(([x, dir]) => {
          const [a0, a1] = span(Lm.head, dir), p = arcPath(x, K.y0 + nr, nr, a0, a1);
          if (m.high) { c.save(); c.globalAlpha = 0.25; stroke(c, p, 'white', 2 * Lm.halo, null, 'round'); c.restore(); }
          lamp(c, p, m.low || m.high, 'white', Lm.w, Lm.halo);
        });
        tail.forEach(([x, dir]) => {
          const y = K.y0 + K.h - tr0;
          lamp(c, arcPath(x, y, tr0, ...span(Lm.stop, dir)), m.stop, 'orange', Lm.w, Lm.halo);
          lamp(c, arcPath(x, y, tr0, ...span(Lm.reverse, dir)), m.reverse, 'white', Lm.w, Lm.halo);
        });
        // the indicators: a mark off each corner, orange on the side that flashes
        const I = Lm.turn;
        [[-1, K.y0 + I.y, m.turnLeft], [-1, K.y0 + K.h - I.y, m.turnLeft], [1, K.y0 + I.y, m.turnRight], [1, K.y0 + K.h - I.y, m.turnRight]].forEach(([dir, y, on]) => {
          const bx = dir < 0 ? K.x0 - I.gap : K.x0 + K.w + I.gap, tx = bx + dir * I.len;
          if (on) { c.save(); c.globalAlpha = 0.25; dot(c, (bx + tx) / 2, y, I.halo, 'orange'); c.restore(); }
          const p = new Path2D(`M${bx} ${y - I.half}L${tx} ${y}L${bx} ${y + I.half}Z`);
          c.fillStyle = col(on ? 'orange' : 'line4'); c.fill(p);
        });
      }
      // UWB anchors at the corners, the key's three modules in the middle, radio as dashed lines
      const U = L.car.anchors, ux = cx - T.w / 2, uy = K.mid - T.h / 2;
      [[K.x0 + U[0], K.y0 + U[1]], [K.x0 + K.w - U[0], K.y0 + U[1]], [K.x0 + U[0], K.y0 + K.h - U[1]], [K.x0 + K.w - U[0], K.y0 + K.h - U[1]]].forEach(([x, y]) => {
        stroke(c, `M${x} ${y}L${cx} ${K.mid}`, 'line3', 1, [3, 3]);
        dot(c, x, y, 4, 'wire5');
      });
      if (f.units.hfrm) unitTile(c, ux, uy - T.pitch, f.units.hfrm);
      unitTile(c, ux, uy, f.units.uwb);
      if (f.units.sec) unitTile(c, ux, uy + T.pitch, f.units.sec);
      unitTile(c, cx - T.w / 2, K.y0 + 22, f.units.front);
      unitTile(c, cx - T.w / 2, K.y0 + K.h - 22 - T.h, f.units.back);
      // the hood's word under the front zone, the tailgate's over the rear one
      if (f.hood) said(c, f.hood.row, cx, K.y0 + B.words[0], 'center');
      if (f.trunk) said(c, f.trunk.row, cx, K.y0 + B.words[1], 'center');
      // the lights' plate under the left headlamp's tile, its leader to that lamp; the tail lamps'
      // plate under the left zone, to the left stop lamp
      if (f.lights) callout(c, lx, K.y0 + B.lights - calloutH(f.lights) / 2, O.w, f.lights, 'ok', [K.x0 + 26, K.y0 + 14]);
      if (f.tail) {
        const a = (Lm.stop[0] + Lm.stop[1]) / 2 * D2R, y = K.y0 + K.h - tr0;
        callout(c, lx, K.y0 + B.tail.y - calloutH(f.tail) / 2, B.tail.w, f.tail, 'ok', [K.x0 + tr0 + tr0 * Math.cos(a), y + tr0 * Math.sin(a)]);
      }
      // the left and right body domains and the headlamps at the edges
      const tr = W - B.x - T.w;
      const side = (e, x, y, to) => {
        stroke(c, `M${x < cx ? x + T.w : x} ${y + T.h / 2}H${to[0]}V${to[1]}`, 'wire4', 1.2);
        dot(c, to[0], to[1], O.dot, 'wire5');
        unitTile(c, x, y, e);
      };
      side(f.units.lampL, lx, L.content.top, [K.x0 + 26, K.y0 + 14]);
      side(f.units.lampR, tr, L.content.top, [K.x0 + K.w - 26, K.y0 + 14]);
      side(f.units.left, lx, K.y0 + B.domainTop, [K.x0, K.y0 + B.domainTop + T.h / 2]);
      side(f.units.right, tr, K.y0 + B.domainTop, [K.x0 + K.w, K.y0 + B.domainTop + T.h / 2]);
      // the gaps between the plates and the car: each door's radar - the front ones over the
      // door's leader, the rear ones under it - and the NFC reader at the B-pillar
      const gl = (lx + O.w + K.x0) / 2 - T.w / 2, gr = (K.x0 + K.w + rx) / 2 - T.w / 2;
      const R = f.doorRadars || {};
      hang(c, R.fl, gl, K.y0 + B.radarFront - T.h / 2, [K.x0, K.y0 + B.radarFront], cx);
      hang(c, R.fr, gr, K.y0 + B.radarFront - T.h / 2, [K.x0 + K.w, K.y0 + B.radarFront], cx);
      hang(c, R.rl, gl, K.y0 + B.radarRear - T.h / 2, [K.x0, K.y0 + B.radarRearAt], cx);
      hang(c, R.rr, gr, K.y0 + B.radarRear - T.h / 2, [K.x0 + K.w, K.y0 + B.radarRearAt], cx);
      const pillar = K.y0 + (D.front[1] + D.rear[0]) / 2;
      hang(c, f.units.nfc, gr, pillar - T.h / 2, [K.x0 + K.w, pillar], cx);
      // the tailgate's projection: level with its tile, on the rear corner's curve
      const ty = K.y0 + B.tailY, dy = ty - (K.y0 + K.h - tr0);
      hang(c, f.units.tailgate, gr, ty - T.h / 2, [K.x0 + K.w - tr0 + Math.sqrt(tr0 * tr0 - dy * dy), ty], cx);
    });
  }

  // Салон: the cabin seen through the roof. Each seat shows who sits (filled), the belt (a strap:
  // blue fastened, orange dashed when someone sits unfastened), heating and ventilation as three
  // pips each - warm and cool - and its reading light; the wheel glows warm when heated; on the
  // console the two charging pads and the fridges. The words are in the plates, a plate's leader
  // to its place; a plate names the block that serves the place where the role says so.
  function sysCabin(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const S = L.cabinScheme, cx = W / 2, K = car(c, cx, L.car.top, L.car.h, true), at = (x, y) => [K.x0 + x, K.y0 + y];
      const P = S.pip, ink = { cold: 'blue', fresh: 'blueSoft', hot: 'orange' };
      // a seat: its cushion, the strap, the pips at its foot, its lamp at the outer front corner
      const seat = (x, y, w, h, r, s, outer) => {
        if (s.occupied) fill(c, x, y, w, h, 'histFill', r);
        edge(c, x, y, w, h, s.occupied ? 'wire4' : 'line3', r);
        if (s.belt === true) stroke(c, `M${x + w - 8} ${y + 6}L${x + 8} ${y + h - 14}`, 'text', 2);
        else if (s.occupied && s.belt === false) stroke(c, `M${x + w - 8} ${y + 6}L${x + 8} ${y + h - 14}`, 'orange', 2, [4, 3]);
        const py = y + h - P.pad - P.s;
        [['heat', 'orange', x + P.pad, 1], ['vent', 'blue', x + w - P.pad - P.s, -1]].forEach(([k, on, x1, dir]) => {
          if (s[k] == null) return;
          for (let i = 0; i < 3; i++) fill(c, x1 + dir * i * (P.s + P.gap), py, P.s, P.s, i < s[k] ? on : 'line3', 1);
        });
        if (s.lamp != null) {
          const lx = outer < 0 ? x + S.lamp.in : x + w - S.lamp.in, ly = y + S.lamp.down;
          if (s.lamp) { c.save(); c.globalAlpha = 0.25; dot(c, lx, ly, S.lamp.glow, 'orange'); c.restore(); }
          dot(c, lx, ly, S.lamp.r, s.lamp ? 'orange' : 'faint3');
        }
      };
      const F = S.front, B = S.bench;
      seat(...at(F.x[0], F.y), F.w, F.h, F.r, f.seats.fl, -1);
      seat(...at(F.x[1], F.y), F.w, F.h, F.r, f.seats.fr, 1);
      seat(...at(B.x[0], B.y), B.w, B.h, B.r, f.seats.rl, -1);
      seat(...at(B.x[1], B.y), B.w, B.h, B.r, f.seats.rc, 0);
      seat(...at(B.x[2], B.y), B.w, B.h, B.r, f.seats.rr, 1);
      // the wheel in front of the driver
      const [wx, wy] = at(S.wheel[0], S.wheel[1]);
      stroke(c, (() => { const p = new Path2D(); p.arc(wx, wy, S.wheel[2], 0, 2 * Math.PI); return p; })(), f.wheelHeat ? 'orange' : 'wire3', 2);
      dot(c, wx, wy, 2.5, f.wheelHeat ? 'orange' : 'wire3');
      // the console: the pads at its front, the fridge in the armrest, the rear fridge behind it
      const Cn = S.console, kx = cx - Cn.w / 2;
      edge(c, kx, K.y0 + Cn.y, Cn.w, Cn.h, 'line3', Cn.r);
      const pad = (x, state) => {
        const y = K.y0 + S.pad.y;
        if (state === 'charging' || state === 'full') fill(c, x, y, S.pad.w, S.pad.h, state === 'charging' ? 'blue' : 'text', 2);
        edge(c, x, y, S.pad.w, S.pad.h, state === 'fault' ? 'orange' : state == null ? 'line3' : 'wire3', 2);
      };
      pad(cx - 1 - S.pad.w, f.pads.left); pad(cx + 1, f.pads.right);
      const box = (y, h, mode) => {
        if (ink[mode]) fill(c, kx + 2, K.y0 + y, Cn.w - 4, h, ink[mode], 2);
        else edge(c, kx + 2, K.y0 + y, Cn.w - 4, h, mode == null ? 'line3' : 'wire3', 2);
      };
      box(S.fridge.y, S.fridge.h, f.fridges.front); box(S.rearFridge.y, S.rearFridge.h, f.fridges.rear);
      // the plates: each column in its order, each plate level with its place where the column
      // has room, pushed down by the one above, and the column lifted if it runs past the bottom
      const O = L.callout, pw = S.plateW, rx = W - S.x - pw;
      const to = {
        cluster: at(S.wheel[0], S.wheel[1] - 18), wheel: [wx - S.wheel[2], wy], fl: at(F.x[0], F.y + F.h / 2),
        rl: at(B.x[0], B.y + B.h / 2), rc: [cx, K.y0 + S.centreLeader],
        pads: [cx + 1 + S.pad.w, K.y0 + S.pad.y - 4], fr: at(F.x[1] + F.w, F.y + F.h / 2),
        fridges: [kx + Cn.w - 2, K.y0 + S.fridge.y + S.fridge.h - 3], rr: at(B.x[2] + B.w, B.y + B.h / 2),
      };
      const tall = rows => 2 * O.padY + rows.reduce((a, r) => a + style(Array.isArray(r[0]) ? r[0][1] : r[1]).lh, 0) + (rows.length - 1) * O.gap;
      [['left', S.x], ['right', rx]].forEach(([side, x]) => {
        const ids = f.plates[side], hs = ids.map(id => tall(f.text[id])), ys = [];
        ids.forEach((id, i) => { ys[i] = Math.max(to[id][1] - hs[i] / 2, i ? ys[i - 1] + hs[i - 1] + S.gap : S.top); });
        const over = ys[ids.length - 1] + hs[ids.length - 1] - S.bottom;
        if (over > 0) ids.forEach((_, i) => { ys[i] = Math.max(S.top, ys[i] - over); });
        for (let i = 1; i < ids.length; i++) ys[i] = Math.max(ys[i], ys[i - 1] + hs[i - 1] + S.gap);
        // the bends between the car and the plates: the leader that climbs or drops the most bends
        // nearest the car, so no leader crosses another
        const edgeX = side === 'left' ? K.left - S.bendIn : K.right + S.bendIn, dir = side === 'left' ? -1 : 1;
        const order = ids.map((id, i) => [Math.abs(to[id][1] - (ys[i] + hs[i] / 2)), i]).sort((a, b) => b[0] - a[0]);
        const bends = [];
        order.forEach(([, i], r) => { bends[i] = edgeX + dir * r * S.bendStep; });
        ids.forEach((id, i) => callout(c, x, ys[i], pw, f.text[id], f.kind && f.kind[id], to[id], bends[i]));
      });
    });
  }

  // Ассистенты: every sensor where it sits on the car, its field of view as a faint wedge.
  function sysAdas(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const A = L.adasCar, cx = W / 2, K = car(c, cx, A.top, A.h), T = L.tile;
      const wedge = (x, y, angle, spread, len) => {
        const a0 = angle - spread / 2, a1 = angle + spread / 2;
        c.save(); c.beginPath(); c.moveTo(x, y); c.arc(x, y, len, a0, a1); c.closePath();
        c.fillStyle = col('band'); c.fill(); c.strokeStyle = col('line4'); c.lineWidth = 1; c.stroke(); c.restore();
        dot(c, x, y, 4, 'wire5');
      };
      const r = Math.PI / 180, cor = A.corner;
      wedge(cx, K.y0 + 2, -90 * r, A.front.spread * r, A.front.len);
      const pts = {
        fl: [K.x0 + cor[0], K.y0 + cor[1], -135], fr: [K.x0 + K.w - cor[0], K.y0 + cor[1], -45],
        rl: [K.x0 + cor[0], K.y0 + K.h - cor[1], 135], rr: [K.x0 + K.w - cor[0], K.y0 + K.h - cor[1], 45],
      };
      Object.values(pts).forEach(([x, y, a]) => wedge(x, y, a * r, A.corners.spread * r, A.corners.len));
      const lx = L.callout.leftX, rx = W - lx - T.w;
      const hang = (e, x, y, to) => {
        stroke(c, `M${x < cx ? x + T.w : x} ${y + T.h / 2}H${(x < cx ? x + T.w + to[0] : x + to[0]) / 2}V${to[1]}H${to[0]}`, 'wire4', 1.2);
        unitTile(c, x, y, e);
      };
      // the front radar's leader runs above the corner fields and drops into its own
      stroke(c, `M${rx} ${A.mrrY + T.h / 2}H${cx}V${K.y0 + 2}`, 'wire4', 1.2);
      unitTile(c, rx, A.mrrY, f.units.mrr);
      hang(f.units.fl, lx, pts.fl[1] - T.h / 2, [pts.fl[0], pts.fl[1]]);
      hang(f.units.fr, rx, pts.fr[1] - T.h / 2 + A.frDrop, [pts.fr[0], pts.fr[1]]);
      hang(f.units.rl, lx, pts.rl[1] - T.h / 2, [pts.rl[0], pts.rl[1]]);
      hang(f.units.rr, rx, pts.rr[1] - T.h / 2, [pts.rr[0], pts.rr[1]]);
      unitTile(c, cx - T.w / 2, K.mid - T.h / 2 - A.adcLift, f.units.adc);
      unitTile(c, cx - T.w / 2, K.y0 + K.h - 22 - T.h - A.ipaLift, f.units.ipa);
    });
  }

  // Электропривод: the engine and generator and the front motor on the front axle, the two rear
  // motors on the rear - SMCU drives the left, SMCU3 the right, and their callouts say so; their
  // readings beside them. The VCU in the middle, the power-domain controller beside it.
  function sysDrives(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const cx = W / 2, K = car(c, cx, L.car.top, L.car.h), O = L.callout, D = L.drivesCar, T = L.tile;
      const gx = cx - D.pair, mx = cx + D.pair;
      // shafts: front motor to the front wheels, rear motors each to its wheel, engine to generator
      stroke(c, `M${K.x0} ${K.front}H${gx - 22}M${mx + 22} ${K.front}H${K.x0 + K.w}M${gx + 22} ${K.front}H${mx - 22}`, 'wire3', 1.6);
      stroke(c, `M${K.x0} ${K.rear}H${cx - D.pair - 22}M${cx + D.pair + 22} ${K.rear}H${K.x0 + K.w}`, 'wire3', 1.6);
      const eng = D.engine;
      fill(c, cx - eng.w / 2, K.y0 + eng.y, eng.w, eng.h, 'ground', 5); edge(c, cx - eng.w / 2, K.y0 + eng.y, eng.w, eng.h, 'quietLine', 5);
      text(c, 'ДВС', cx, K.y0 + eng.y + (eng.h - style('cellAbbr').lh) / 2, 'cellAbbr', 'dim', { align: 'center' });
      stroke(c, `M${gx} ${K.y0 + eng.y + eng.h}V${K.front - 22}`, 'wire3', 1.6);
      // the VCU in the middle, its commands as dashed lines
      const vy = K.mid - T.h / 2;
      [[gx, K.front], [mx, K.front], [cx - D.pair, K.rear], [cx + D.pair, K.rear]].forEach(([x, y]) =>
        stroke(c, `M${cx} ${y < K.mid ? vy : vy + T.h}L${x} ${y < K.mid ? y + 22 : y - 22}`, 'line3', 1, [3, 3]));
      if (f.units.pdc) {
        const px = K.x0 - D.domainGap - T.w;
        stroke(c, `M${px + T.w} ${K.mid}H${cx - T.w / 2}`, 'line3', 1, [3, 3]);
        unitTile(c, px, vy, f.units.pdc);
      }
      unitTile(c, cx - T.w / 2, vy, f.units.vcu);
      machine(c, gx, K.front, 'G', true); machine(c, mx, K.front, 'M', true);
      machine(c, cx - D.pair, K.rear, 'M', true); machine(c, cx + D.pair, K.rear, 'M', true);
      const lx = O.leftX - (D.boxW - O.w), rx = W - lx - D.boxW;
      const box = (d, left, to) => {
        const rows = [[d.label, 'counterLabel', 'mute'], ...d.rows.map(t => [t, 'cellMono', 'text'])];
        const h = 2 * O.padY + style('counterLabel').lh + d.rows.length * style('cellMono').lh + d.rows.length * O.gap;
        callout(c, left ? lx : rx, to[1] - h / 2, D.boxW, rows, 'ok', to);
      };
      box(f.front, false, [mx + 22, K.front]);
      box(f.isg, true, [gx - 22, K.front]);
      box(f.rearLeft, true, [cx - D.pair - 22, K.rear]);
      box(f.rearRight, false, [cx + D.pair + 22, K.rear]);
    });
  }

  function sysHv(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); hvBody(c, f, L.hvRail); });
  }

  // Двигатель: the air's way through it - inlet, throttle, manifold, cylinders, catalyst, oxygen
  // Двигатель: the air's way through it - inlet, throttle, manifold, cylinders, catalyst, oxygen
  // sensor - each reading at its place on the way.
  function sysEngineScheme(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const E = L.engineScheme, y = E.pipeY, hw = E.pipe / 2, Bk = E.block;
      stroke(c, `M${E.x0} ${y - hw}H${Bk.x}M${E.x0} ${y + hw}H${Bk.x}`, 'wire3', 1.6);
      stroke(c, `M${Bk.x + Bk.w} ${y - hw}H${E.x1}M${Bk.x + Bk.w} ${y + hw}H${E.x1}`, 'wire3', 1.6);
      flow(c, E.x0 - 34, y, E.x0 - 6, y);
      flow(c, E.x1 + 6, y, E.x1 + 34, y);
      stroke(c, `M${E.throttle - 9} ${y + hw - 3}L${E.throttle + 9} ${y - hw + 3}`, 'wire5', 2.4);
      dot(c, E.throttle, y, 2.6, 'wire5');
      fill(c, Bk.x, Bk.y, Bk.w, Bk.h, 'ground', 8); edge(c, Bk.x, Bk.y, Bk.w, Bk.h, 'quietLine', 8);
      for (let i = 0; i < 4; i++) {
        const cx = Bk.x + Bk.w * (i + 0.5) / 4;
        c.beginPath(); c.arc(cx, Bk.y + Bk.h / 2, Bk.cyl, 0, 2 * Math.PI); c.strokeStyle = col('wire4'); c.lineWidth = 1.4; c.stroke();
      }
      const Ct = E.cat;
      fill(c, Ct.x, y - Ct.h / 2, Ct.w, Ct.h, 'plate', 6); edge(c, Ct.x, y - Ct.h / 2, Ct.w, Ct.h, 'wire4', 6);
      for (let x = Ct.x + 10; x < Ct.x + Ct.w - 6; x += 10) stroke(c, `M${x} ${y - Ct.h / 2 + 6}V${y + Ct.h / 2 - 6}`, 'line3', 1);
      stroke(c, `M${E.o2} ${y + hw + 16}V${y + 2}`, 'wire5', 2.2);
      fill(c, E.o2 - 5, y + hw + 14, 10, 10, 'quiet', 2); edge(c, E.o2 - 5, y + hw + 14, 10, 10, 'wire4', 2);
      f.points.forEach(p => {
        const [side, x, w, at] = E.points[p.place], up = side === 'up', edgeY = up ? (at || y - hw) : (at || y + hw);
        const rows = [[p.label, 'counterLabel', 'mute'], ...p.rows.map(r => [r, 'cellMono', 'text'])];
        const h = 2 * L.callout.padY + style('counterLabel').lh + p.rows.length * (style('cellMono').lh + L.callout.gap);
        const by = up ? E.upY - h : E.downY;
        stroke(c, `M${x} ${up ? by + h : by}V${edgeY}`, 'wire4', 1.2);
        dot(c, x, edgeY, L.callout.dot, 'wire5');
        callout(c, x - w / 2, by, w, rows, 'ok');
      });
    });
  }

  // A flow arrow between two points, its head at the end.
  function flow(c, x1, y1, x2, y2, ink) {
    const a = Math.atan2(y2 - y1, x2 - x1), h = 8;
    stroke(c, `M${x1} ${y1}L${x2} ${y2}M${x2 - h * Math.cos(a - 0.45)} ${y2 - h * Math.sin(a - 0.45)}L${x2} ${y2}L${x2 - h * Math.cos(a + 0.45)} ${y2 - h * Math.sin(a + 0.45)}`, ink || 'wire4', 1.6);
  }

  // Климат: outside air through the climate unit into the cabin; the HV heater and the thermal
  // module at the unit, the air-quality module under the cabin.
  function sysClimate(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const C = L.climateScheme, T = L.tile;
      const zone = (z, x) => {
        fill(c, x, C.zoneY, C.zoneW, C.zoneH, 'ground', 10);
        edge(c, x, C.zoneY, C.zoneW, C.zoneH, 'quietLine', 10);
        let y = C.zoneY + 20;
        text(c, z.label, x + 22, y, 'counterLabel', 'mute'); y += 22;
        text(c, z.temp, x + 22, y, 'counterBig', 'white'); y += 50;
        text(c, 'PM2.5', x + 22, y, 'counterLabel', 'mute'); y += 18;
        text(c, z.pm, x + 22, y, 'counter', 'text');
      };
      zone(f.outside, C.leftX);
      zone(f.cabin, W - C.leftX - C.zoneW);
      const ux = W / 2 - T.w / 2, uy = C.zoneY + C.zoneH / 2 - T.h / 2;
      flow(c, C.leftX + C.zoneW, uy + T.h / 2, ux - 4, uy + T.h / 2);
      flow(c, ux + T.w, uy + T.h / 2, W - C.leftX - C.zoneW - 4, uy + T.h / 2);
      unitTile(c, ux, uy, f.units.acc);
      stroke(c, `M${W / 2} ${uy + T.h}V${uy + T.h + C.stem}`, 'wire4', 1.2);
      unitTile(c, ux, uy + T.h + C.stem, f.units.ptc);
      stroke(c, `M${W / 2} ${uy}V${uy - C.stem}`, 'wire4', 1.2);
      unitTile(c, ux, uy - C.stem - T.h, f.units.tmim);
      if (f.units.aqdm) {
        const ax = W - C.leftX - C.zoneW / 2;
        stroke(c, `M${ax} ${C.zoneY + C.zoneH}V${C.zoneY + C.zoneH + C.stem}`, 'wire4', 1.2);
        unitTile(c, ax - T.w / 2, C.zoneY + C.zoneH + C.stem, f.units.aqdm);
      }
    });
  }

  // Безопасность: the car from above - the airbag controller in the middle, each front belt's
  // pretensioner at its B-pillar (the driver's left, the passenger's right), the child-presence
  // radar at the rear.
  function sysSafety(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const cx = W / 2, K = car(c, cx, L.car.top, L.car.h), T = L.tile, D = L.car.doors, G = L.safetyScheme.gap;
      const pillar = K.y0 + (D.front[1] + D.rear[0]) / 2;
      hang(c, f.units.driverBelt, K.x0 - G - T.w, pillar - T.h / 2, [K.x0, pillar], cx);
      hang(c, f.units.passengerBelt, K.x0 + K.w + G, pillar - T.h / 2, [K.x0 + K.w, pillar], cx);
      if (f.units.srs) unitTile(c, cx - T.w / 2, K.mid - T.h / 2, f.units.srs);
      if (f.units.cpd) unitTile(c, cx - T.w / 2, K.y0 + K.h - 22 - T.h, f.units.cpd);
      // the seats in the belts' column either side of the car, as the cabin draws them - filled
      // where someone sits, the strap blue buckled and orange dashed on someone unbuckled: the
      // driver over the driver's belt and the second row's left and centre under it, the passenger
      // and the second row's right across; each seat nearest the car, its words outside it
      const S = L.safetyScheme, Z = S.seat, lab = style('counterLabel').lh + L.callout.gap;
      (f.seats || []).forEach(s => {
        const left = s.place === 'fl' || s.place === 'rl' || s.place === 'rc';
        const gx = left ? K.x0 - G - Z.w : K.x0 + K.w + G;
        const y = K.y0 + (s.place === 'fl' || s.place === 'fr' ? S.rows[0] : S.rows[1] + (s.place === 'rc' ? S.step : 0));
        const gy = y + (lab + style('cell').lh - Z.h) / 2;
        if (s.occupied) fill(c, gx, gy, Z.w, Z.h, 'histFill', Z.r);
        edge(c, gx, gy, Z.w, Z.h, s.occupied ? 'wire4' : 'line3', Z.r);
        const strap = `M${gx + Z.w - Z.strapIn} ${gy + Z.strapTop}L${gx + Z.strapIn} ${gy + Z.h - Z.strapFoot}`;
        if (s.belt === true) stroke(c, strap, 'text', 2);
        else if (s.occupied && s.belt === false) stroke(c, strap, 'orange', 2, [4, 3]);
        const tx = left ? gx - S.pad : gx + Z.w + S.pad, align = left ? 'right' : undefined;
        text(c, s.label, tx, y, 'counterLabel', 'mute', { align });
        said(c, s.row, tx, y + lab, align);
      });
    });
  }

  // Сеть 12 В: the DC-DC from the HV bus onto the 12 V line, the battery under it, the body's
  // controllers hanging off it.
  function sysLv(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const N = L.lvScheme, T = L.tile, y = N.busY;
      text(c, 'ШИНА DC', N.x0, y - 44, 'colHead', 'mute');
      text(c, f.hv, N.x0, y - 26, 'hvVolts', 'text');
      stroke(c, `M${N.x0} ${y}H${N.dcdcX}`, 'wire', 2.4);
      unitTile(c, N.dcdcX, y - T.h / 2, f.units.dcdc);
      // the converter's mode under it, as the voltages stand over the line
      if (f.dcdcMode) {
        text(c, 'РЕЖИМ DC-DC', N.dcdcX, y + T.h / 2 + N.modeY, 'colHead', 'mute');
        text(c, f.dcdcMode, N.dcdcX, y + T.h / 2 + N.modeY + 18, 'hvVolts', 'text');
      }
      const lx = N.dcdcX + T.w;
      stroke(c, `M${lx} ${y}H${N.x1}`, 'wire2', 2);
      text(c, '12 В', lx + 16, y - 44, 'colHead', 'mute');
      text(c, f.lv, lx + 16, y - 26, 'hvVolts', 'text');
      stroke(c, `M${N.battX + T.w / 2} ${y}V${y + N.battDrop}`, 'wire2', 2);
      unitTile(c, N.battX, y + N.battDrop, f.units.lbms);
      f.units.loads.forEach((e, i) => {
        const x = N.loadX + i * N.loadStep;
        stroke(c, `M${x + T.w / 2} ${y}V${y + N.loadDrop}`, 'wire3', 1.4);
        dot(c, x + T.w / 2, y, 2.6, 'wire5');
        unitTile(c, x, y + N.loadDrop, e);
      });
    });
  }

  // One parameter on its own page: the reading large, its chart across the page, only it polled.
  // f.series is [t seconds back, value] oldest first; the chart is the window f.window seconds wide.
  function sysParam(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block);
      tabs(c, f.tabs);
      const P = L.param, x0 = P.chart.x, x1 = W - 24, y0 = P.chart.top, y1 = P.chart.bottom;
      // the reading
      text(c, f.label, 24, P.hero.labelY, 'counterLabel', 'mute');
      const big = style('paramValue');
      const draw = () => text(c, f.value, 24, P.hero.valueY, 'paramValue', f.outOfNorm ? 'orangeInk' : 'white');
      glow(c, 18, f.outOfNorm ? 'rgba(255,159,25,0.55)' : 'rgba(67,159,252,0.45)', draw); draw();
      text(c, f.unit, 24, P.hero.valueY + big.lh + 4, 'paramUnit', 'dim');
      let ky = P.hero.statsY;
      f.stats.forEach(([k, v]) => {
        text(c, k, 24, ky, 'counterLabel', 'mute');
        text(c, v, P.hero.statsW, ky - 3, 'valueBms', 'text', { align: 'right' });
        rule(c, 24, ky + 22, P.hero.statsW - 24, 'hair');
        ky += P.hero.statsStep;
      });
      // the chart: scale, grid, norm band, min/max lines, the trace, the last point
      const [lo, hi] = f.scale, yv = v => y1 - (v - lo) / (hi - lo) * (y1 - y0), xt = t => x1 - t / f.window * (x1 - x0);
      if (f.norm) fill(c, x0, yv(f.norm[1]), x1 - x0, yv(f.norm[0]) - yv(f.norm[1]), 'band');
      f.grid.forEach(v => {
        const y = yv(v);
        fill(c, x0, y, x1 - x0, 1, 'hair');
        text(c, f.gridText(v), x0 - 10, y - style('sig').lh / 2, 'sig', 'faint', { align: 'right' });
      });
      f.ticks.forEach(t => {
        const x = xt(t);
        fill(c, x, y0, 1, y1 - y0, 'hair');
        text(c, t ? '−' + t + ' с' : 'сейчас', x, y1 + 10, 'sig', 'faint', { align: t ? 'center' : 'right' });
      });
      [[f.min, 'мин'], [f.max, 'макс']].forEach(([v, k]) => {
        const y = yv(v);
        stroke(c, `M${x0} ${y}H${x1}`, 'line4', 1, [4, 4]);
        text(c, k + ' ' + f.gridText(v), x0 + 8, y + (k === 'мин' ? 4 : -style('sig').lh - 4), 'sig', 'dim2');
      });
      const p = new Path2D();
      f.series.forEach(([t, v], i) => { const x = xt(t), y = yv(v); if (i) p.lineTo(x, y); else p.moveTo(x, y); });
      c.save(); c.lineJoin = 'round';
      glow(c, 8, 'rgba(67,159,252,0.7)', () => stroke(c, p, 'blue', 1.8));
      c.restore();
      const [lt, lv] = f.series[f.series.length - 1];
      glow(c, 10, 'rgba(67,159,252,0.9)', () => { c.beginPath(); c.arc(xt(lt), yv(lv), 4.5, 0, 2 * Math.PI); c.fillStyle = col('blueInk'); c.fill(); });
    });
  }

  // «Тяговая батарея · Ячейки»: every cell's voltage and every probe's temperature, as the BMS
  // sends them in pages of its own stream; numbers are the BMS's (its min/max indices agree).
  function sysPackCells(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const P = L.packCells, A = P.cells, cx0 = A.x, cx1 = W - 24, cy0 = A.top, cy1 = A.bottom;
      // the cells' head: what is drawn, and its extremes at the right
      text(c, f.cellsHead, 24, A.headY, 'counterLabel', 'mute');
      let r = W - 24;
      [...f.cellsStats].reverse().forEach(([k, v]) => {
        r -= text(c, v, r, A.headY - 3, 'valueBms', 'white', { align: 'right' }) + 6;
        r -= text(c, k, r, A.headY, 'counterLabel', 'mute', { align: 'right' }) + 22;
      });
      // the scale: millivolts from the reference, symmetric; the reference labelled with its value.
      // A sweep's cells move together under load, so the chart shows how they stand against each
      // other, not the pack's level; that is in the head.
      const span = f.cellGrid[f.cellGrid.length - 1], yv = d => (cy0 + cy1) / 2 - d / span * (cy1 - cy0) / 2;
      f.cellGrid.forEach((d, k) => {
        fill(c, cx0, yv(d), cx1 - cx0, 1, d === 0 ? 'wire4' : 'hair');
        text(c, f.cellGridText[k], cx0 - 8, yv(d) - style('sig').lh / 2, 'sig', d === 0 ? 'dim2' : 'faint', { align: 'right' });
      });
      // the bars, one a cell, up or down from the reference: a cell that lags or leads stands out
      const n = f.cells.length, pitch = (cx1 - cx0) / n, bw = pitch - A.gap, ym = yv(0);
      // a cell whose page did not come in the last sweep is drawn faint; one not come yet, not at all
      const staleCells = new Set(f.staleCells || []), staleProbes = new Set(f.staleProbes || []);
      f.cells.forEach((v, i) => {
        if (v == null) return;
        const d = v - f.cellRef, x = cx0 + i * pitch, y = yv(d), ext = i === f.minIndex || i === f.maxIndex;
        const top = Math.min(y, ym), h = Math.max(1.5, Math.abs(ym - y));
        fill(c, x, top, bw, h, staleCells.has(i) ? 'sep' : ext ? 'blueInk' : d >= 0 ? 'blue' : 'blueSoft');
      });
      // the sweep under way: a line along the chart's foot up to the page that came last
      if (f.sweep != null) {
        const sx = cx0 + f.sweep * (cx1 - cx0);
        fill(c, cx0, cy1 + 1, sx - cx0, A.sweep, 'blueSoft');
        dot(c, sx, cy1 + 1 + A.sweep / 2, A.sweepDot, 'blue');
      }
      // the extremes named at their bars' ends: the highest above, the lowest below
      [[f.minIndex, 'мин №' + (f.minIndex + 1), 1], [f.maxIndex, 'макс №' + (f.maxIndex + 1), -1]].forEach(([i, t, dir]) => {
        const x = cx0 + i * pitch + bw / 2, y = yv(f.cells[i] - f.cellRef);
        stroke(c, `M${x} ${y + dir * 4}V${y + dir * 12}`, 'wire5', 1);
        text(c, t, x, dir > 0 ? y + 14 : y - 14 - style('sig').lh, 'sig', 'white', { align: 'center' });
      });
      // the axis: numbers every twenty cells
      f.cellTicks.forEach(k => text(c, String(k), cx0 + (k - 1) * pitch + bw / 2, cy1 + 6, 'sig', 'faint', { align: 'center' }));
      // the probes: a tile each, the extremes outlined
      const T = P.probes, n2 = f.probes.length, tw = (W - 48 - (n2 - 1) * T.gap) / n2;
      text(c, f.probesHead, 24, T.headY, 'counterLabel', 'mute');
      let r2 = W - 24;
      [...f.probesStats].reverse().forEach(([k, v]) => {
        r2 -= text(c, v, r2, T.headY - 3, 'valueBms', 'white', { align: 'right' }) + 6;
        r2 -= text(c, k, r2, T.headY, 'counterLabel', 'mute', { align: 'right' }) + 22;
      });
      f.probes.forEach((v, i) => {
        const x = 24 + i * (tw + T.gap), ext = i === f.probeMin || i === f.probeMax, old = staleProbes.has(i);
        fill(c, x, T.top, tw, T.h, 'ground', 6); edge(c, x, T.top, tw, T.h, ext ? 'ink3' : old ? 'line3' : 'quietLine', 6);
        text(c, 'Д' + (i + 1), x + T.padX, T.top + T.padY, 'counterLabel', 'mute');
        text(c, v == null ? '—' : String(v), x + T.padX, T.top + T.padY + T.valueY, 'counter', old || v == null ? 'faint' : 'white');
        text(c, '°C', x + tw - T.padX, T.top + T.padY + T.unitY, 'unit', old ? 'faint' : 'dim2', { align: 'right' });
      });
    });
  }

  // The dark cockpit: a block that answered is a quiet frame, white words; one with records says so
  // with its sign and count in place of its address; one not read is dim, one with no address dashed.
  const TILE = {
    ok:     { fill: 'ground', line: 'quietLine', ink: 'white', sub: 'dim', addr: 'faint' },
    hist:   { fill: 'ground', line: 'quietLine', ink: 'white', sub: 'dim', addr: 'faint' },
    unread: { fill: 'ground', line: 'quietLine', ink: 'dim', sub: 'mute', addr: 'faint' },
    none:   { dash: 'line4', ink: 'faint', sub: 'faint2', addr: 'faint2' },
  };

  // A block on a scheme: abbreviation and address over the short name, its state its look, its
  // records - the sign and the count - in place of the address where it has any.
  function unitTile(c, x, y, e, tile) {
    const Ti = tile || L.tile, k = TILE[e.kind];
    if (k.dash) edge(c, x, y, Ti.w, Ti.h, k.dash, Ti.r, [3, 3]);
    else { fill(c, x, y, Ti.w, Ti.h, k.fill, Ti.r); edge(c, x, y, Ti.w, Ti.h, k.line, Ti.r); }
    const ix = x + 1 + Ti.padX, iw = Ti.w - 2 - 2 * Ti.padX;
    const top = y + (Ti.h - style('tileAbbr').lh - Ti.rowGap - style('tileName').lh) / 2;
    let rw;
    if (e.kind === 'hist') {
      rw = text(c, String(e.history), ix + iw, top, 'tileAddr', 'ink3', { align: 'right' });
      sign(c, 'hist', ix + iw - rw - L.rail.mark.gap + 2 - L.sign.r, top + style('tileAbbr').lh / 2);
      rw += L.rail.mark.gap - 2 + 2 * L.sign.r;
    } else {
      rw = text(c, e.req || 'нет адреса', ix + iw, top, 'tileAddr', k.addr, { align: 'right' });
    }
    text(c, e.abbr, ix, top, 'tileAbbr', k.ink, { max: iw - rw - 6 });
    text(c, e.short, ix, top + style('tileAbbr').lh + Ti.rowGap, 'tileName', k.sub, { max: iw });
  }

  // A UDS block's memory: its codes, each status in words; under them the selected code's status
  // byte from bit 7 to 0 and, beside it, how the memory was read.
  function bodyDtc(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    const B = L.bodyDtc, cols = columns(B.table, 24, W - 48), sel = f.selected || 0;
    let y = headRow(c, cols, ['КОД', 'ТИП ОТКАЗА (FTB)', 'ОПИСАНИЕ', 'СТАТУС'], B.top, B.head, 'colHeadTight');
    f.codes.forEach((d, n) => {
      const h = B.rowMin, active = kindOf(d.status) === 'active';
      if (n === sel) fill(c, 24, y, W - 48, h, 'band');
      text(c, d.code, cols[0][0] + 12, mid(y, h, 'code'), 'code', active ? 'red' : 'white');
      runs(c, [[d.ftb, 'cellNum', 'dim'], [' · ' + d.ftbName, 'cell', 'white']], cols[1][0], base(mid(y, h, 'cell'), 'cell'), cols[1][1]);
      const room = cols[2][1] - 16;
      if (d.desc) {
        // the catalog's description in up to two lines, in place of the group
        const lines = twoLines(c, d.desc, 'cell', room), dt = y + (h - lines.length * style('cell').lh) / 2;
        lines.forEach((l, i) => text(c, l, cols[2][0], dt + i * style('cell').lh, 'cell', 'text', { max: room }));
      } else {
        const dt = y + (h - style('cell').lh - (d.note ? 2 + style('note').lh : 0)) / 2;
        text(c, d.group, cols[2][0], dt, 'cell', 'text', { max: room });
        if (d.note) text(c, d.note, cols[2][0], dt + style('cell').lh + 2, 'note', 'faint', { max: room });
      }
      runs(c, [[hexByte(d.status) + '  ', 'cellNum', 'text'], [statusWords(d.status, f.availability), 'cellSmall', active ? 'red' : 'dim']],
        cols[3][0], base(mid(y, h, 'cell'), 'cell'), cols[3][1]);
      rule(c, 24, y + h, W - 48, 'hair');
      y += h + 1;
    });
    // under the last code, never under a fixed line: a block with eight codes pushed a fixed one
    // over its rows (on the car 2026-10-20); the app scrolls the rest
    const d = f.codes[sel], below = Math.max(B.below, y + B.belowGap), lab = style('groupTitle').lh;
    text(c, `СТАТУС ${d.code} · ${hexByte(d.status)} = ${bin8(d.status)}`, 24, below, 'groupTitle', 'mute');
    const sy = below + lab + B.explainGap;
    bitStrip(c, 24, sy, d.status, f.availability);
    const wy = sy + L.bitStrip.nameY + style('bitHead').lh + B.explainGap + 4;
    text(c, statusWords(d.status, f.availability), 24, wy, 'lead', 'white');
    const unreported = (~f.availability) & 0xFF;
    text(c, (unreported ? '«·» — блок этот бит не сообщает · ' : '') + 'доступность ' + hexByte(f.availability), 24, wy + style('lead').lh, 'small12', 'faint');
    text(c, 'КАК ПРОЧИТАН', B.readX, below, 'groupTitle', 'mute');
    f.reads.forEach((r, i) => readLine(c, B.readX, sy + i * style('raw').lh, r, d.code));
    footer(c, f.footer);
  }

  // A monitor's readiness in words, no chip: ready is the quiet case.
  const MONITOR = { ready: ['готов', 'dim2'], waiting: ['не готов', 'white'], absent: ['не поддерж.', 'faint'] };
  // X: where the two halves stand - the block screen's (L.ecuDtc) or the rail's (L.rail.ecuDtc).
  function monitors(c, list, X) {
    const D = L.ecuDtc, R = (X || D).right, cols = columns((X || D).monCols, R.x, R.w);
    let y = headRow(c, cols, ['ГОТОВНОСТЬ МОНИТОРОВ · С МОМЕНТА СБРОСА DTC', 'ТИП', 'СТАТУС'], D.top, D.head);
    for (const [name, abbr, type, state] of list) {
      const h = D.monRow, off = state === 'absent';
      runs(c, [[name + ' ', 'cell', off ? 'faint2' : 'white'], [abbr, 'cellAbbr', 'faint']], cols[0][0] + 12, base(mid(y, h, 'cell'), 'cell'), cols[0][1] - 12);
      text(c, type, cols[1][0], mid(y, h, 'unit'), 'unit', off ? 'sep' : 'dim2');
      const [word, ink] = MONITOR[state];
      text(c, word, cols[2][0], mid(y, h, 'cellSmall'), 'cellSmall', ink, { max: cols[2][1] });
      rule(c, R.x, y + h, R.w, 'hair');
      y += h + 1;
    }
  }
  // A plate: a caption, a line - broken at spaces where the plate is narrow - and the raw reply;
  // returns its bottom.
  function pidPlate(c, p, x, top, w) {
    const P = L.ecuDtc.plate, lines = wrap(c, p.line, 'plateLine', w - 2 * P.padX);
    const h = 2 * P.padY + style('counterLabel').lh + lines.length * style('plateLine').lh + style('plateMono').lh + 2 * P.gap;
    fill(c, x, top, w, h, 'plate', P.r); edge(c, x, top, w, h, 'rule', P.r);
    let y = top + P.padY;
    text(c, p.label, x + P.padX, y, 'counterLabel', 'mute'); y += style('counterLabel').lh + P.gap;
    lines.forEach(l => { text(c, l, x + P.padX, y, 'plateLine', 'white'); y += style('plateLine').lh; });
    y += P.gap;
    text(c, p.raw, x + P.padX, y, 'plateMono', 'faint');
    return top + h;
  }

  function ecuDtc(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    obdLists(c, f, L.ecuDtc);
    footer(c, f.footer);
  }

  // The OBD lists with PID 01 under them on the left, the monitors on the right.
  function obdLists(c, f, X) {
    const D = L.ecuDtc, Lf = X.left, cols = columns(X.listCols, Lf.x, Lf.w);
    let y = headRow(c, cols, ['КОДЫ НЕИСПРАВНОСТЕЙ', 'СЕРВИС', ['DTC', 'right', 12]], D.top, D.head);
    for (const [name, service, count] of f.lists) {
      const h = D.listRow, num = /^\d+$/.test(count), none = count === '0';
      text(c, name, cols[0][0] + 12, mid(y, h, 'cell'), 'cell', 'white', { max: cols[0][1] - 12 });
      text(c, service, cols[1][0], mid(y, h, 'cellMono'), 'cellMono', 'dim');
      const sn = num ? 'cellNum' : 'normMono';
      text(c, none ? '—' : count, cols[2][0] + cols[2][1] - 12, mid(y, h, sn), sn, num && !none ? 'white' : 'faint', { align: 'right' });
      rule(c, Lf.x, y + h, Lf.w, 'hair');
      y += h + 1;
    }
    pidPlate(c, f.pid01, Lf.x, y + D.plateGap, Lf.w);
    monitors(c, f.monitors, X);
  }

  function ecuFault(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    const D = L.ecuDtc, Fa = L.ecuFault, Lf = D.left, cols = columns(Fa.cols, Lf.x, Lf.w);
    let y = headRow(c, cols, ['КОД', 'ОПИСАНИЕ', 'MIL'], D.top, D.head);
    const d = f.fault, room = cols[1][1] - 12;
    // the title is the catalog's description when it has one: it wraps; the note under it only if there is one
    const titles = wrap(c, d.title, 'faultTitle', room), tl = style('faultTitle').lh;
    const notes = d.note ? wrap(c, d.note, 'faultNote', room) : [];
    const inner = titles.length * tl + (notes.length ? 3 + notes.length * style('faultNote').lh : 0);
    const h = Math.max(Fa.rowMin, 2 * Fa.rowPad + inner);
    fill(c, Lf.x, y, Lf.w, h, 'redRow');
    text(c, d.code, cols[0][0] + 12, mid(y, h, 'codeFault'), 'codeFault', 'red');
    const ty = y + (h - inner) / 2;
    titles.forEach((l, i) => text(c, l, cols[1][0], ty + i * tl, 'faultTitle', 'white', { max: room }));
    notes.forEach((l, i) => text(c, l, cols[1][0], ty + titles.length * tl + 3 + i * style('faultNote').lh, 'faultNote', 'dim2'));
    badge(c, d.mil, cols[2][0], y + (h - L.badge.h) / 2, 'on');
    rule(c, Lf.x, y + h, Lf.w, 'hair');
    y += h + 1 + Fa.listGap;
    // four counts side by side between two rules
    const lc = columns(['1fr', '1fr', '1fr', '1fr'], Lf.x, Lf.w);
    const [py, px] = Fa.listPad;
    const lh = py * 2 + style('counterLabel').lh + Fa.listGapIn + style('listCount').lh + Fa.listGapIn + style('listService').lh;
    rule(c, Lf.x, y, Lf.w);
    f.lists.forEach(([name, count, service], i) => {
      const [x] = lc[i];
      if (i) vrule(c, x, y + 1, lh);
      let ty2 = y + 1 + py;
      text(c, name, x + (i ? 1 : 0) + px, ty2, 'colHeadTight', 'mute'); ty2 += style('counterLabel').lh + Fa.listGapIn;
      text(c, count === '0' ? '—' : count, x + (i ? 1 : 0) + px, ty2, 'listCount', count === '0' ? 'faint' : 'redInk'); ty2 += style('listCount').lh + Fa.listGapIn;
      text(c, service, x + (i ? 1 : 0) + px, ty2, 'listService', 'faint');
    });
    y += 1 + lh;
    rule(c, Lf.x, y, Lf.w);
    pidPlate(c, f.pid01, Lf.x, y + 1 + Fa.listGap, Lf.w);
    monitors(c, f.monitors);
    footer(c, f.footer);
  }

  function ecuLive(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    liveTable(c, f, L.ecuLive.cols);
    footer(c, f.footer);
  }

  // [first] heads the first column: «PID» on an OBD stream, «УСТР.» on a system's readings, whose
  // rows carry the device there and the FID beside the name; a value outside its norm, or a state its
  // card names a fault, is orange. A state's word longer than its column reaches back over what the
  // name leaves free, as far as 12 after it.
  // With f.pick a row with a key - one that opens alone - leads with its square of «Графики» (pickBox) in a
  // column of its own, the first column's padding moved into it; four chosen, the empty squares pale.
  function liveTable(c, f, colSpec, first) {
    const E = L.ecuLive, P = L.pick, px = f.pick ? P.col : 0, cols = columns(colSpec, 24 + px, W - 48 - px), pad = px ? 0 : 12;
    let y = headRow(c, cols, [[first || 'PID', 'left', pad], 'ПАРАМЕТР', ['ЗНАЧЕНИЕ', 'right', 12], 'ЕД.', ['МИН', 'right', 12], ['МАКС', 'right', 12], 'НОРМА', 'ГРАФИК · 60 С'], E.top, E.head);
    if (px) rule(c, 24, y - 1, px);
    const full = f.rows.filter(r => r.pick).length >= 4;
    for (const r of f.rows) {
      const h = E.row, rx = i => cols[i][0] + cols[i][1] - 12;
      if (px && r.key) pickBox(c, 24 + P.inset, y + (h - P.size) / 2, r.pick, full && !r.pick);
      text(c, r.pid, cols[0][0] + pad, mid(y, h, 'normMono'), 'normMono', 'faint');
      const nameEnd = runs(c, [[r.name + ' ', 'cell', 'white'], [r.abbr, 'cellAside', 'faint']], cols[1][0], base(mid(y, h, 'cell'), 'cell'), cols[1][1]);
      const room = Math.max(cols[2][1] - 12, rx(2) - nameEnd - 12);
      text(c, r.value, rx(2), mid(y, h, 'value'), 'value', r.out ? 'orange' : 'white', { align: 'right', max: room });
      text(c, r.unit, cols[3][0], mid(y, h, 'unit'), 'unit', 'dim2');
      text(c, r.min, rx(4), mid(y, h, 'cellMono'), 'cellMono', 'dim2', { align: 'right' });
      text(c, r.max, rx(5), mid(y, h, 'cellMono'), 'cellMono', 'dim2', { align: 'right' });
      text(c, r.norm, cols[6][0], mid(y, h, 'normMono'), 'normMono', 'faint');
      if (r.trace && r.trace.length) {
        const S = E.spark, sx = cols[7][0], sy = y + (h - S.h) / 2, step = Math.min(S.w, cols[7][1] - 8) / (r.trace.length - 1);
        const p = new Path2D();
        r.trace.forEach((v, i) => { const py = sy + S.h - 2 - Math.max(0, Math.min(1, v)) * (S.h - 4); if (i) p.lineTo(sx + i * step, py); else p.moveTo(sx, py); });
        c.save(); c.lineJoin = 'round';
        glow(c, S.glow * 2, 'rgba(67,159,252,0.6)', () => stroke(c, p, 'blue', S.stroke));
        c.restore();
      }
      rule(c, 24, y + h, W - 48, 'hair');
      y += h + 1;
    }
  }

  const TONE = { dim: 'mute', '': 'white' };
  function keyRows(c, rows, cols, y, h, x, w) {
    for (const [key, value, tone] of rows) {
      text(c, key, cols[0][0] + 12, mid(y, h, 'key'), 'key', 'dim2');
      text(c, value, cols[1][0], mid(y, h, 'keyValue'), 'keyValue', tone ? (TONE[tone] || tone) : 'white', { max: cols[1][1] });
      rule(c, x, y + h, w, 'hair');
      y += h + 1;
    }
    return y;
  }

  function ecuIdent(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    // the block's own side is wider than the DTC screen's: «был <версия> · <код>» of an update fits whole
    const D = L.ecuDtc, I = L.ecuIdent, Lf = I.left, R = I.right;
    let cols = columns(I.ownCols, Lf.x, Lf.w);
    let y = headRow(c, cols, ['ОТВЕЧАЕТ БЛОК'], D.top, D.head);
    y = keyRows(c, f.own, cols, y, I.row, Lf.x, Lf.w);
    if (f.note) para(c, f.note, Lf.x + 12, y + I.noteGap, 'small', 'faint', Lf.w - 12);
    cols = columns(I.cacheCols, R.x, R.w);
    y = headRow(c, cols, ['КАК ОПОЗНАН'], D.top, D.head);
    y = keyRows(c, f.cache, cols, y, I.row, R.x, R.w);
    // the cache is the record the role was proved by, not a reference the answer is matched against:
    // no «Сверка с F194» (architecture, 5.1). A block without OBD has no PIDs.
    if (!f.pids || !f.pids.length) { footer(c, f.footer); return; }
    y += I.pidGap;
    // the supported PIDs: caption and count on one baseline, then the grid
    const by = y + Math.max(base(0, 'counterLabel'), base(0, 'pidHead')) + (I.pidHeadH - style('counterLabel').lh) / 2;
    glyphs(c, f.pidsLabel, R.x, by, style('counterLabel'), 'mute');
    const cnt = f.pids.length + ' шт · ' + f.pidsAsked;
    glyphs(c, cnt, R.x + R.w - measure(c, cnt, 'pidHead'), by, style('pidHead'), 'faint');
    y += I.pidHeadH + I.pidGridGap;
    const P = I.pid, cw = (R.w - (P.cols - 1) * P.gap) / P.cols;
    f.pids.forEach((id, i) => {
      const x = R.x + (i % P.cols) * (cw + P.gap), py = y + Math.floor(i / P.cols) * (P.h + P.gap);
      const service = f.servicePids.includes(id);
      if (!service) fill(c, x, py, cw, P.h, 'pidFill', P.r);
      text(c, id, x + cw / 2, mid(py, P.h, 'pid'), 'pid', service ? 'sep' : 'text', { align: 'center' });
    });
    footer(c, f.footer);
  }

  /* ------------------------------------------------------- Mode 06 tests */
  // «Тесты» of the ECM (architecture, 7.3): Mode 06 as the block's screen read it on opening, out of its
  // bytes. A test is nine of them - MID, TID, UASID and three words, signed where the UASID is 80 or above
  // - and one whose three words are zero has not run since the codes were cleared and is not judged. The
  // fixture's strings are the words in their unit; where the value stands between the limits, whether it is
  // in them and which side it left by come from the words.
  const hex2 = v => v.toString(16).toUpperCase().padStart(2, '0');
  function monitorTests(m) {
    const b = m.answer.split(' ').map(h => parseInt(h, 16)), out = [];
    for (let i = 0; 1 + 9 * i < b.length; i++) {
      const o = 1 + 9 * i, ua = b[o + 2];
      const w = k => { const v = (b[o + 3 + 2 * k] << 8) | b[o + 4 + 2 * k]; return ua >= 0x80 && v >= 0x8000 ? v - 0x10000 : v; };
      const v = w(0), lo = w(1), hi = w(2), ran = !!(v || lo || hi);
      const [value, min, max, unit] = m.tests[i];
      const beyond = !ran || hi < lo ? 0 : v < lo ? -1 : v > hi ? 1 : 0;
      const pos = !ran || hi < lo ? null : hi === lo ? (beyond < 0 ? 0 : beyond > 0 ? 1 : 0.5) : Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
      out.push({ tid: hex2(b[o + 1]), value, min, max, unit, pass: ran ? hi >= lo && beyond === 0 : null, pos, beyond });
    }
    return out.sort((a, d) => (a.tid < d.tid ? -1 : a.tid > d.tid ? 1 : 0));
  }
  // A monitor's exchange, two tests a line, a further line under the answer's first test.
  function testLines(ask, answer) {
    const bytes = answer.split(' ');
    if (bytes.length < 10 || (bytes.length - 1) % 9 || bytes[0] !== '46') return [ask + ' → ' + answer];
    const head = ask + ' → ' + bytes[0] + ' ', lines = [];
    for (let i = 1; i < bytes.length; i += 18) lines.push((i === 1 ? head : ' '.repeat(head.length)) + bytes.slice(i, i + 18).join(' '));
    return lines;
  }
  // The monitors as the screen orders them: one with a test outside its limits first, the rest by MID.
  function testGroups(t) {
    const groups = t.monitors.map(m => {
      const tests = monitorTests(m);
      return { mid: m.mid, title: (m.name + ' · MID ' + m.mid).toUpperCase(), tests, out: tests.some(x => x.pass === false), raw: testLines('06 ' + m.mid, m.answer) };
    });
    return groups.filter(g => g.out).concat(groups.filter(g => !g.out));
  }
  // Two columns: the split that leaves the taller the shortest, the first the taller on a tie. A group is
  // its title, a line a test and its exchange's lines, all of one height.
  function testColumns(groups) {
    const n = g => 1 + g.tests.length + g.raw.length, total = groups.reduce((a, g) => a + n(g), 0);
    let best = Infinity, split = 0, left = 0;
    for (let k = 0; k <= groups.length; k++) {
      if (k) left += n(groups[k - 1]);
      const m = Math.max(left, total - left);
      if (m <= best) { best = m; split = k; }
    }
    return [groups.slice(0, split), groups.slice(split)].filter(g => g.length);
  }
  // The scale: a hairline from the minimum to the maximum and the value's mark on it; a value out of the
  // limits stands at the end it left by, a small orange point beyond the end pointing out.
  function testScale(c, x, w, y, h, t) {
    const S = L.ecuTests.scale, x0 = x + S.inset, x1 = x + w - S.inset, cy = y + h / 2;
    fill(c, x0, cy - 0.5, x1 - x0, 1, 'line3');
    fill(c, x0, cy - S.tick / 2, 1, S.tick, 'line3');
    fill(c, x1 - 1, cy - S.tick / 2, 1, S.tick, 'line3');
    const out = t.pass === false, [mw, mh] = S.mark, mx = x0 + t.pos * (x1 - x0);
    fill(c, mx - mw / 2, cy - mh / 2, mw, mh, out ? 'orange' : 'white');
    if (t.beyond) {
      const [pw, ph] = S.out, ex = t.beyond > 0 ? x1 + S.outGap : x0 - S.outGap, tip = ex + t.beyond * pw;
      const p = new Path2D(); p.moveTo(ex, cy - ph / 2); p.lineTo(tip, cy); p.lineTo(ex, cy + ph / 2); p.closePath();
      c.fillStyle = col('orange'); c.fill(p);
    }
  }
  function ecuTests(c, f) {
    header(c, f.top);
    const t = f.tests, E = L.ecuTests, groups = testGroups(t);
    const all = groups.flatMap(g => g.tests), out = all.filter(x => x.pass === false).length;
    // «ВНЕ НОРМЫ n» in the head only when a test is out of its limits
    blockHead(c, Object.assign({}, f.block, { counters: f.block.counters.concat(out ? [['ВНЕ НОРМЫ', String(out), 'out']] : []) }));
    tabs(c, f.tabs);
    const line = t.maps.map(m => m[0]).join(' · ') + ' → ' + [`${groups.length} ${plural(groups.length, 'монитор', 'монитора', 'мониторов')}`,
      `${all.length} ${plural(all.length, 'тест', 'теста', 'тестов')}`, t.at].join(' · ');
    text(c, line, E.x[0], E.line, 'noteMono', 'dim2');
    testColumns(groups).forEach((col, ci) => {
      const x = E.x[ci], w = E.w, cols = columns(E.rowCols, x, w);
      let y = E.top;
      col.forEach(g => {
        text(c, g.title, x, y + E.titleY, 'groupTitle', 'mute', { max: w });
        y += E.title;
        g.tests.forEach(r => {
          const h = E.row, o = r.pass === false, ran = r.pass !== null;
          text(c, 'TID ' + r.tid, cols[0][0], mid(y, h, 'normMono'), 'normMono', 'faint');
          text(c, r.value, cols[1][0] + cols[1][1] - E.valuePad, mid(y, h, 'cellNum'), 'cellNum', o ? 'orange' : ran ? 'white' : 'faint', { align: 'right' });
          text(c, r.unit, cols[2][0], mid(y, h, 'unit'), 'unit', 'dim2', { max: cols[2][1] - 4 });
          if (!ran) {
            text(c, 'не выполнялся', cols[3][0], mid(y, h, 'cellSmall'), 'cellSmall', 'faint');
          } else {
            text(c, r.min, cols[3][0] + cols[3][1] - E.limitPad, mid(y, h, 'cellMono'), 'cellMono', 'dim2', { align: 'right' });
            if (r.pos != null) testScale(c, cols[4][0], cols[4][1], y, h, r);
            text(c, r.max, cols[5][0] + E.limitPad, mid(y, h, 'cellMono'), 'cellMono', 'dim2');
            if (o) text(c, 'вне нормы', cols[6][0], mid(y, h, 'cellSmall'), 'cellSmall', 'orange');
          }
          rule(c, x, y + h - 1, w, 'hair');
          y += h;
        });
        g.raw.forEach(l => { text(c, l, x, y + E.rawY, 'noteMono', 'faint', { max: w }); y += E.raw; });
      });
    });
    footer(c, f.footer);
  }

  const DIM_CELL = ['—', 'н/д', 'нет адреса', 'не читали'];
  function drivesTable(c, f) {
    const D = L.drives, cols = columns(D.cols, 24, W - 48);
    const h0 = D.head;
    text(c, 'ПАРАМЕТР', cols[0][0] + 12, mid(D.top, h0, 'colHead'), 'colHead', 'mute');
    f.units.forEach(([name, tag], i) => {
      const [x, w] = cols[1 + i], top = D.top + (h0 - style('unitName').lh - 2 - style('unitTag').lh) / 2;
      text(c, name, x + w - D.pad, top, 'unitName', 'white', { align: 'right' });
      text(c, tag, x + w - D.pad, top + style('unitName').lh + 2, 'unitTag', 'faint', { align: 'right' });
    });
    text(c, 'ЕД.', cols[5][0], mid(D.top, h0, 'colHead'), 'colHead', 'mute');
    rule(c, 24, D.top + h0, W - 48);
    let y = D.top + h0 + 1;
    for (const [name, sig, unit, cells] of f.rows) {
      const h = D.row, top = y + (h - style('cell').lh - 2 - style('rowSig').lh) / 2;
      text(c, name, cols[0][0] + 12, top, 'cell', 'white', { max: cols[0][1] - 12 });
      text(c, sig, cols[0][0] + 12, top + style('cell').lh + 2, 'rowSig', 'faint3', { max: cols[0][1] - 12 });
      cells.forEach((v, i) => {
        const [x, w] = cols[1 + i], sn = v.length > 12 ? 'driveSmall' : 'driveCell';
        text(c, v, x + w - D.pad, mid(y, h, sn), sn, DIM_CELL.includes(v) ? 'faint2' : 'white', { align: 'right' });
      });
      text(c, unit, cols[5][0], mid(y, h, 'unit'), 'unit', 'dim2');
      rule(c, 24, y + h, W - 48, 'hair');
      y += h + 1;
    }
  }

  function adasTable(c, f) {
    const A = L.adas, cols = columns(A.cols, 24, W - 48);
    text(c, 'ПАРАМЕТР', cols[0][0] + 12, mid(A.top, A.head, 'colHead'), 'colHead', 'mute');
    f.units.forEach(([abbr, name, isRead], i) => {
      const [x, w] = cols[1 + i], top = A.top + (A.head - style('abbr').lh - 2 - style('abbrName').lh) / 2;
      text(c, abbr, x + A.pad, top, 'abbr', isRead ? 'white' : 'ink2');
      text(c, name, x + A.pad, top + style('abbr').lh + 2, 'abbrName', 'mute', { max: w - A.pad });
    });
    rule(c, 24, A.top + A.head, W - 48);
    let y = A.top + A.head + 1;
    for (const r of f.rows) {
      const h = A.row;
      text(c, r.name, cols[0][0] + 12, mid(y, h, 'key'), 'key', 'dim2');
      r.cells.forEach((v, i) => {
        const [x, w] = cols[1 + i];
        if (v.shared) fill(c, x, y, w, h, 'band');
        const sn = 'adas' + String(v.size || 14).replace('.', '_');
        text(c, v.t, x + A.pad, mid(y, h, sn), sn, DIM_CELL.includes(v.t) ? 'faint2' : (v.ink || 'white'), { max: w - A.pad });
      });
      rule(c, 24, y + h, W - 48, 'hair');
      y += h + 1;
    }
  }

  // A UDS block whose memory gave nothing back: what was asked, and which status bits the mask sees.
  function blockMask(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    maskAsks(c, f, L.ecuDtc.left);
    footer(c, f.footer);
  }

  function maskAsks(c, f, Lf) {
    const Bk = L.brakes;
    const cols = columns(Bk.askCols, Lf.x, Lf.w);
    let y = headRow(c, cols, ['ЧТО ЗАПРОШЕНО', 'СЕРВИС', ['DTC', 'right', 12]], Bk.top, Bk.head);
    for (const [name, service, count] of f.asks) {
      const h = Bk.askRow, asked = /^\d+$/.test(count);
      text(c, name, cols[0][0] + 12, mid(y, h, 'cell'), 'cell', asked ? 'white' : 'dim', { max: cols[0][1] - 12 });
      text(c, service, cols[1][0], mid(y, h, 'cellMono'), 'cellMono', 'dim');
      const sn = asked ? 'count16' : 'normMono';
      text(c, count, cols[2][0] + cols[2][1] - 12, mid(y, h, sn), sn, asked ? 'white' : 'faint', { align: 'right' });
      rule(c, Lf.x, y + h, Lf.w, 'hair');
      y += h + 1;
    }
    // the mask plate: the mask's bits from 7 to 0 under its hex and binary, «·» where the block's
    // availability has no bit; what came back
    const P = Bk.plate, iw = Lf.w - 2 * P.padX, top = y + Bk.plateGap, S = L.bitStrip;
    const bodyLines = wrap(c, f.mask.result, 'bodyStrong', iw), stripH = S.nameY + style('bitHead').lh;
    const ph = 2 * P.padY + style('counterLabel').lh + P.gap + stripH + P.gap + bodyLines.length * style('bodyStrong').lh;
    fill(c, Lf.x, top, Lf.w, ph, 'plate', P.r); edge(c, Lf.x, top, Lf.w, ph, 'rule', P.r);
    let py = top + P.padY;
    const px = Lf.x + P.padX;
    text(c, `МАСКА ${hexByte(f.mask.value)} = ${bin8(f.mask.value)} · ДОСТУПНОСТЬ ${hexByte(f.mask.availability)}`, px, py, 'counterLabel', 'mute');
    py += style('counterLabel').lh + P.gap;
    bitStrip(c, px, py, f.mask.value, f.mask.availability, true);
    py += stripH + P.gap;
    bodyLines.forEach(l => { text(c, l, px, py, 'bodyStrong', 'white'); py += style('bodyStrong').lh; });
  }
  // The HV schema at the rail's width in the dark cockpit: quiet frames, white values, an
  // inverter's bus voltage with its difference to the pack («555 В +2»), what is off at half its
  // light; the machines' captions are «Электропривод»'s.
  function hvBody(c, f, V) {
    const K = V.contactor, B = V.bus, p = f.pack;
    stroke(c, K.wire, 'wire', 2.4);
    // the blade across the pins when the BMS says closed, lifted when open or not known
    stroke(c, f.contactorClosed ? K.closed : K.blade, 'wire', 2);
    K.pins.forEach(([x, y]) => { c.beginPath(); c.arc(x, y, K.pin, 0, 2 * Math.PI); c.fillStyle = col('text'); c.fill(); });
    stroke(c, B.trunk, 'wire', 3);
    stroke(c, B.taps, 'wire2', 2);
    stroke(c, B.shafts, 'wire3', 1.6);
    stroke(c, B.coupling, 'wire4', 1.4);
    V.machines.forEach(m => {
      c.beginPath(); c.arc(m.x, m.y, V.machineR, 0, 2 * Math.PI); c.fillStyle = col('ground'); c.fill();
      stroke(c, (() => { const q = new Path2D(); q.arc(m.x, m.y, V.machineR, 0, 2 * Math.PI); return q; })(), m.live ? 'wire5' : 'wire4', 1.6, m.live ? null : [4, 3]);
      const s = style('glyph');
      glyphs(c, m.glyph, m.x - measure(c, m.glyph, s) / 2, m.y + 5, s, m.live ? 'text' : 'dim');
    });
    stroke(c, B.feeds, 'wire3', 1.6, [5, 4]);
    const kh = para(c, f.contactor, K.label[0], K.label[1], 'hvLabel', 'mute', K.label[2]);
    if (f.contactorState) text(c, f.contactorState, K.label[0], K.label[1] + kh, 'hvLabel', 'text');
    // the charge port under the charger, as the contactor's label
    if (f.charge) {
      const [x, y, w] = V.charge, ch = para(c, 'зарядный разъём', x, y, 'hvLabel', 'mute', w);
      text(c, f.charge.state, x, y + ch, 'hvLabel', 'text');
    }
    text(c, f.busLabel, V.busLabel.right, V.busLabel.y, 'colHead', 'mute', { align: 'right' });
    // the pack
    const P = V.pack;
    fill(c, P.x, P.y, P.w, P.h, 'ground', P.r); edge(c, P.x, P.y, P.w, P.h, 'quietLine', P.r);
    const ix = P.x + P.padX, iw = P.w - 2 * P.padX;
    let y = P.y + P.padY;
    const by = y + Math.max(base(0, 'packTitle'), base(0, 'packTag'));
    glyphs(c, p.title, ix, by, style('packTitle'), 'white');
    glyphs(c, p.tag, ix + iw - measure(c, p.tag, 'packTag'), by, style('packTag'), 'faint');
    y += style('packTitle').lh + 2 * P.gap;
    p.rows.forEach(([k, v]) => {
      const bb = y + Math.max(base(0, 'packKey'), base(0, 'packValue'));
      const vw = measure(c, v, 'packValue');
      glyphs(c, fit(c, k, style('packKey'), iw - vw - 10), ix, bb, style('packKey'), 'dim2');
      glyphs(c, v, ix + iw - vw, bb, style('packValue'), 'white');
      y += style('packValue').lh + P.gap;
    });
    // the units on the bus and beside the pack, at their places and widths
    const U = V.unit;
    f.units.forEach(u => {
      const [x, uy, w0] = V.units[u.abbr], w = w0 || U.w;
      c.save();
      if (u.off) c.globalAlpha = 0.45;
      fill(c, x, uy, w, U.h, 'ground', U.r);
      edge(c, x, uy, w, U.h, u.kind === 'none' ? 'line4' : 'quietLine', U.r, u.kind === 'none' ? [3, 3] : null);
      const ux = x + 1 + U.padX, uw = w - 2 - 2 * U.padX;
      const top = uy + (U.h - style('hvAbbr').lh - style('hvName').lh - style('sig').lh - 2 * U.rowGap) / 2;
      text(c, u.abbr, ux, top, 'hvAbbr', 'white');
      text(c, u.addr, ux + uw, top, 'tileAddr', 'faint', { align: 'right' });
      text(c, u.name, ux, top + style('hvAbbr').lh + U.rowGap, 'hvName', 'dim', { max: uw });
      // the reading: a bus voltage and its difference to the pack's, then the rest
      const parts = [];
      if (u.volts != null) {
        const d = u.volts - p.u;
        parts.push([u.volts + ' В', 'sig', 'white'], [' ' + (d > 0 ? '+' : d < 0 ? '−' : '±') + Math.abs(d), 'sig', 'faint']);
      }
      if (u.reading) parts.push([(parts.length ? ' · ' : '') + u.reading, 'sig', u.volts != null ? 'dim' : 'text']);
      if (parts.length) runs(c, parts, ux, base(top + style('hvAbbr').lh + style('hvName').lh + 2 * U.rowGap, 'sig'), uw);
      c.restore();
    });
  }

  /* ------------------------------------------------------- a status byte */
  // The status bits of a DTC from bit 7 to bit 0, the order the hex and the binary read in.
  const STRIP = ['WIR', 'TNCTOC', 'TFSLC', 'TNCSLC', 'CDTC', 'PDTC', 'TFTOC', 'TF'];
  const bin8 = v => { const b = v.toString(2).padStart(8, '0'); return b.slice(0, 4) + ' ' + b.slice(4); };
  // One strip: the bit's number over its cell, its name under it; a set bit filled - red where it
  // says now (TF) or asks the lamp (WIR) - a clear one framed, «·» where the block does not report
  // the bit. plain: a mask, every set bit alike.
  function bitStrip(c, x, y, value, avail, plain) {
    const S = L.bitStrip;
    STRIP.forEach((name, i) => {
      const bit = 7 - i, cx = x + i * (S.cell + S.gap), mx = cx + S.cell / 2, cy = y + S.cellY;
      text(c, String(bit), mx, y, 'bit', 'faint', { align: 'center' });
      const has = (avail >> bit) & 1, on = (value >> bit) & 1;
      let t, ink;
      if (!has) { edge(c, cx, cy, S.cell, S.h, 'quietLine', S.r, [3, 3]); t = '·'; ink = 'faint3'; }
      else if (on) { fill(c, cx, cy, S.cell, S.h, !plain && (bit === 0 || bit === 7) ? 'red' : 'text', S.r); t = '1'; ink = 'ground'; }
      else { edge(c, cx, cy, S.cell, S.h, 'line3', S.r); t = '0'; ink = 'dim'; }
      text(c, t, mx, cy + (S.h - style('driveCell').lh) / 2, 'driveCell', ink, { align: 'center' });
      text(c, name, mx, y + S.nameY, 'bitHead', 'mute', { align: 'center' });
    });
    return 8 * S.cell + 7 * S.gap;
  }
  // A status byte in words: what is set of what the block reports, «сейчас (не) активен» last.
  function statusWords(s, avail) {
    const has = b => (avail >> b) & 1, on = b => has(b) && (s >> b) & 1, w = [];
    if (on(3)) w.push('подтверждён');
    if (on(2)) w.push('ожидающий');
    if (on(5)) w.push('был отказ после сброса');
    if (on(7)) w.push('запрос лампы');
    if (has(0)) w.push(on(0) ? 'сейчас активен' : 'сейчас не активен');
    return w.join(' · ');
  }
  const hexByte = v => '0x' + v.toString(16).toUpperCase().padStart(2, '0');
  // A DTC's three bytes: «U0121-87» is C1 21 87.
  function dtcBytes(code) {
    const m = /^([PCBU])([0-3])([0-9A-F])([0-9A-F]{2})-([0-9A-F]{2})$/.exec(code);
    if (!m) return null;
    const hi = ('PCBU'.indexOf(m[1]) << 6) | (parseInt(m[2], 16) << 4) | parseInt(m[3], 16);
    return [hi.toString(16).toUpperCase().padStart(2, '0'), m[4], m[5]];
  }
  // A read as it went: the ask, «→», the answer - a long one cut to its head and the record of
  // [code], which stands out. Returns the runs and where the record is in them.
  function readLine(c, x, top, r, code) {
    const bytes = r.answer.split(' '), rec = code ? dtcBytes(code) : null;
    let at = -1;
    if (rec) for (let i = 2; i + 3 < bytes.length && at < 0; i++) if (bytes[i] === rec[0] && bytes[i + 1] === rec[1] && bytes[i + 2] === rec[2]) at = i;
    const sn = 'raw', P = L.code.pick;
    const lead = r.ask + ' → ';
    let before, record = '', after = '';
    if (at < 0) before = r.answer;
    else {
      const head = bytes.slice(0, Math.min(3, at)).join(' ');
      before = head + (at > 3 ? ' · … · ' : ' ');
      record = bytes.slice(at, at + 4).join(' ');
      after = at + 4 < bytes.length ? ' · …' : '';
    }
    const by = base(top, sn);
    let cx = x + glyphs(c, lead + before, x, by, style(sn), 'dim');
    if (record) {
      const w = measure(c, record, sn), s = style(sn);
      fill(c, cx - P[0], top + (s.lh - s.size * 1.3) / 2 - P[1], w + 2 * P[0], s.size * 1.3 + 2 * P[1], 'pick', P[2]);
      cx += glyphs(c, record, cx, by, s, 'white');
      glyphs(c, after, cx, by, s, 'dim');
    }
  }

  /* -------------------------------------------------------------- a code */
  // One record from the summary: what it means, its status byte from bit 7 to 0, how it was read;
  // the block it lost - and that block now; the same failure type elsewhere; when it came and went
  // by the journal, its snapshot and its counters, raw where nothing decodes them.
  function codeScreen(c, f) {
    header(c, f.top);
    const Cd = L.code, kind = kindOf(f.status), tl = f.timeline;
    const seen = tl.seen, since = tl.scans.filter(s => when(s) >= when(seen[0]));
    blockHead(c, {
      title: codeRuns(f.code, 'codeTitle', kind === 'active' ? 'red' : 'white'),
      aside: `· ${f.block.abbr} ${f.block.addr} · ${f.block.name}`,
      line: [f.sae.standard, ...f.sae.parts].join(' · '),
      counters: [['СТАТУС', hexByte(f.status), kind === 'active' ? 'on' : undefined], ['ВПЕРВЫЕ', seen[0].slice(0, 5)], ['В СКАНАХ', `${seen.length} из ${since.length}`]],
    });
    rule(c, 24, Cd.rule, W - 48);
    const [x1, x2, x3] = Cd.x, [w1, w2, w3] = Cd.w;
    // the meaning, the status byte, the reads
    text(c, 'ЗНАЧЕНИЕ', x1, Cd.label, 'groupTitle', 'mute');
    text(c, f.meaning.title, x1, Cd.meaning, 'meaning', 'white', { max: w1 });
    if (f.meaning.note) text(c, f.meaning.note, x1, Cd.meaningNote, 'cellSmall', 'dim', { max: w1 });
    text(c, `СТАТУС · ${hexByte(f.status)} = ${bin8(f.status)}`, x1, Cd.statusLabel, 'groupTitle', 'mute');
    bitStrip(c, x1, Cd.strip, f.status, f.availability);
    text(c, statusWords(f.status, f.availability), x1, Cd.words, 'lead', kind === 'active' ? 'red' : 'white', { max: w1 });
    const unreported = (~f.availability) & 0xFF;
    text(c, (unreported ? '«·» — блок этот бит не сообщает · ' : '') + 'доступность ' + hexByte(f.availability), x1, Cd.wordsNote, 'small12', 'faint');
    text(c, 'КАК ПРОЧИТАН', x1, Cd.readLabel, 'groupTitle', 'mute');
    f.reads.forEach((r, i) => readLine(c, x1, Cd.reads + i * style('raw').lh, r, f.code));
    // the block a lost message names, and how it stands now
    let sy = Cd.label;
    if (f.addressee) {
      const a = f.addressee, K = Cd.card;
      text(c, `АДРЕСАТ · ${a.label}`, x2, Cd.label, 'groupTitle', 'mute');
      fill(c, x2, K.top, w2, K.h, 'plate', K.r); edge(c, x2, K.top, w2, K.h, 'rule', K.r);
      const l1 = K.top + K.padY, l2 = l1 + style('abbr').lh + K.gap;
      runs(c, [[a.abbr + '  ', 'abbr', 'white'], [a.addr + '  ', 'cellAbbr', 'faint'], [a.name, 'cellSmall', 'ink3']], x2 + K.padX, base(l1, 'abbr'), w2 - 2 * K.padX - L.chevron - 8);
      chevron(c, x2 + w2 - K.padX - L.chevron, l1 + (style('abbr').lh - L.chevron) / 2);
      runs(c, [[a.answers ? 'сейчас отвечает' : 'сейчас не отвечает', 'cellSmall', a.answers ? 'text' : 'orange'], [' · ', 'cellSmall', 'sep'],
        [a.records ? `записей ${a.records}` : 'записей нет', 'cellSmall', a.records ? 'orange' : 'text'], [' · ', 'cellSmall', 'sep'],
        [a.ms != null ? a.ms + ' мс' : '—', 'normMono', 'dim']], x2 + K.padX, base(l2, 'cellSmall'));
      sy = Cd.sameLabel;
    }
    // the same failure type in other blocks
    const ftb = f.code.split('-')[1];
    text(c, `ТОТ ЖЕ ТИП ОТКАЗА · ${ftb}`, x2, sy, 'groupTitle', 'mute');
    let y = sy + (Cd.same - Cd.sameLabel);
    if (f.same.length) {
      const cols = columns(Cd.sameCols, x2, w2, Cd.sameGap);
      f.same.forEach(s => {
        const h = Cd.sameRow;
        runs(c, codeRuns(s.code, 'codeSmall', 'white', 'cellMono'), cols[0][0], base(mid(y, h, 'codeSmall'), 'codeSmall'));
        text(c, `${s.block} ${s.addr}`, cols[1][0], mid(y, h, 'normMono'), 'normMono', 'dim');
        text(c, 'с ' + s.since, cols[2][0], mid(y, h, 'cellAside'), 'cellAside', 'mute');
        chevron(c, cols[3][0], y + (h - L.chevron) / 2);
        rule(c, x2, y + h, w2, 'hair');
        y += h + 1;
      });
      const n = f.same.length + 1, blocks = new Set([f.block.abbr, ...f.same.map(s => s.block)]).size;
      const what = netLoss(f.code) ? `${plural(n, 'код', 'кода', 'кодов')} потери связи` : `${plural(n, 'код', 'кода', 'кодов')} с типом ${ftb}`;
      text(c, `${n} ${what} в ${blocks} ${blocks === 1 ? 'блоке' : 'блоках'}`, x2, y + Cd.sameNoteGap - 1, 'note', 'mute');
    } else {
      text(c, 'в других блоках нет', x2, y, 'cellSmall', 'dim');
    }
    // when: a dot each scan that saw it, joined from the first to the last
    const T = Cd.timeline;
    text(c, 'ХРОНОЛОГИЯ', x3, Cd.label, 'groupTitle', 'mute');
    const X = dayAxis(c, x3, T.top + T.axis, w3, tl.days);
    const cy = T.top + T.dot * 2;
    c.save(); c.globalAlpha = T.barAlpha;
    fill(c, X(seen[0]), cy - T.bar / 2, X(seen[seen.length - 1]) - X(seen[0]), T.bar, 'orange');
    c.restore();
    seen.forEach(s => dot(c, X(s), cy, T.dot / 2, 'orange'));
    const every = seen.length === since.length;
    text(c, `впервые ${seen[0]} · ` + (every ? `в каждом из ${since.length} ${plural(since.length, 'скана', 'сканов', 'сканов')}` :
      `в ${seen.length} из ${since.length} ${plural(since.length, 'скана', 'сканов', 'сканов')}`), x3, T.top + T.caption, 'note', 'mute');
    // the snapshot: its record decoded, or that the block keeps none - raw
    const sn = f.snapshot;
    text(c, 'СТОП-КАДР · 19 04' + (sn.record ? ' · ЗАПИСЬ ' + sn.record : ''), x3, Cd.snapLabel, 'groupTitle', 'mute');
    y = Cd.snap;
    if (sn.rows) {
      sn.rows.forEach(([k, v, out]) => {
        const h = Cd.snapRow;
        text(c, k, x3, mid(y, h, 'cellSmall'), 'cellSmall', 'ink3', { max: Cd.snapKey - 8 });
        text(c, v, x3 + Cd.snapKey, mid(y, h, 'adas14'), 'adas14', out ? 'orange' : 'text');
        rule(c, x3, y + h, w3, 'hair');
        y += h + 1;
      });
    } else {
      text(c, 'записи нет', x3, y, 'cellSmall', 'text');
      y += style('cellSmall').lh;
      readLine(c, x3, y, sn);
      y += style('raw').lh;
    }
    y += Cd.countGap;
    const ct = f.counts;
    text(c, 'СЧЁТЧИКИ · 19 06', x3, y, 'groupTitle', 'mute');
    y += Cd.countRow;
    if (ct.rows) {
      const parts = [];
      ct.rows.forEach(([k, v], i) => { if (i) parts.push([' · ', 'cellMono', 'sep']); parts.push([`${k} ${v}`, 'cellMono', 'text']); });
      runs(c, parts, x3, base(y, 'cellMono'));
    } else {
      text(c, 'записей нет', x3, y, 'cellSmall', 'text');
      readLine(c, x3, y + style('cellSmall').lh, ct);
    }
    footer(c, f.footer);
  }

  // 'DD.MM HH:MM' as minutes in the year, for ordering; days in one month or across one.
  function when(s) {
    const m = /^(\d\d)\.(\d\d)(?: (\d\d):(\d\d))?/.exec(s);
    return ((+m[2]) * 31 + (+m[1])) * 1440 + (+(m[3] || 0)) * 60 + (+(m[4] || 0));
  }
  // A day axis: a tick and its date at each day's start along [x, x + w]; returns the time's x.
  function dayAxis(c, x, y, w, days) {
    const T = L.code.timeline, n = days.length;
    days.forEach((d, i) => {
      const dx = x + i * w / n;
      fill(c, dx, y, 1, T.tick, 'line3');
      text(c, d, dx + T.labelGap, y - 2, 'rowSig', 'faint');
    });
    return s => {
      const i = days.indexOf(s.slice(0, 5)), m = /(\d\d):(\d\d)$/.exec(s);
      return x + (i + (m ? (+m[1] + m[2] / 60) / 24 : 0)) / n * w;
    };
  }

  /* -------------------------------------------- Тяговая батарея · Здоровье */
  // The pack's health on one table - now, its reference (the norm, or what is usual for this car
  // over the journal's 30 days) and the 30 days - and beside it the cells from their mean and the
  // probes. A mini chart's scale is never narrower than its norm or its resolution × 20.
  function packHealth(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const Hl = L.health, T = Hl.table, cols = columns(T.cols, 24, T.w), P = T.valuePad;
      let y = headRow(c, cols, [['ПОКАЗАТЕЛЬ', 'left', 0], ['СЕЙЧАС', 'right', P], 'ОПОРА', '30 ДНЕЙ'], T.top, T.head);
      f.rows.forEach(r => {
        const h = T.row, nt = y + (h - style('cell').lh - T.subGap - style('small12').lh) / 2;
        text(c, r.name, cols[0][0], nt, 'cell', 'white', { max: cols[0][1] - 8 });
        text(c, r.sub, cols[0][0], nt + style('cell').lh + T.subGap, 'small12', 'mute', { max: cols[0][1] - 8 });
        let xr = cols[1][0] + cols[1][1] - P;
        if (r.unit && r.value !== '—') xr -= text(c, r.unit, xr, mid(y, h, 'cellAside'), 'cellAside', 'dim', { align: 'right' }) + T.unitGap;
        text(c, r.value, xr, mid(y, h, 'value'), 'value', r.value === '—' ? 'faint' : r.out ? 'orange' : 'white', { align: 'right' });
        if (r.norm) text(c, r.norm, cols[2][0], mid(y, h, 'normMono'), 'normMono', 'dim');
        else if (r.usual) runs(c, [['обычно ', 'unit', 'mute'], [r.usual, 'normMono', 'dim']], cols[2][0], base(mid(y, h, 'normMono'), 'normMono'));
        else if (r.note) text(c, r.note, cols[2][0], mid(y, h, 'normMono'), 'normMono', 'dim');
        if (r.trend) spark(c, cols[3][0], y + (h - Hl.spark.h) / 2, r.trend);
        rule(c, 24, y + h, T.w, 'hair');
        y += h + 1;
      });
      // the cells from the sweep's mean, a millivolt to L.health.cells.mv
      const Cc = Hl.cells, x = Cc.x, pw = Cc.w - Cc.scaleW, ym = Cc.top + Cc.h / 2;
      text(c, `ЯЧЕЙКИ · ОТ СРЕДНЕГО ${grouped(f.cellRef)} мВ`, x, Cc.labelY, 'groupTitle', 'mute');
      Cc.grid.forEach(mv => {
        const gy = ym - mv * Cc.mv;
        fill(c, x, gy, pw, 1, 'rule');
        text(c, (mv > 0 ? '+' : '−') + Math.abs(mv), x + Cc.w - 2, gy - style('tick').lh / 2, 'tick', 'faint', { align: 'right' });
      });
      fill(c, x, ym, pw, 1, 'line3');
      const n = f.cells.length, step = (Cc.w - 30) / n;
      const lo = f.cells.indexOf(Math.min(...f.cells)), hi = f.cells.indexOf(Math.max(...f.cells));
      f.cells.forEach((v, i) => {
        const d = v - f.cellRef;
        if (!d) return;
        const ext = i === lo || i === hi;
        c.save(); c.globalAlpha = ext ? 1 : 0.75;
        fill(c, x + i * step, d > 0 ? ym - d * Cc.mv : ym, Math.max(1.2, step - Cc.gap), Math.abs(d) * Cc.mv, ext ? 'white' : 'blue');
        c.restore();
      });
      // live, as «Ячейки»: the sweep under way, a line along the chart's foot to the page that came last
      if (f.sweep != null) {
        const A = L.packCells.cells, sx = x + f.sweep * (Cc.w - 30), fy = Cc.top + Cc.h + 1;
        fill(c, x, fy, sx - x, A.sweep, 'blueSoft');
        dot(c, sx, fy + A.sweep / 2, A.sweepDot, 'blue');
      }
      text(c, `мин № ${lo + 1} · ${grouped(f.cells[lo])}`, x, Cc.statsY, 'sig', 'ink3');
      text(c, `макс № ${hi + 1} · ${grouped(f.cells[hi])}`, x + Cc.w - 30, Cc.statsY, 'sig', 'ink3', { align: 'right' });
      // the probes, the extremes outlined
      const Pr = Hl.probes, pmin = Math.min(...f.probes), pmax = Math.max(...f.probes);
      text(c, 'ДАТЧИКИ, °C', x, Pr.labelY, 'groupTitle', 'mute');
      f.probes.forEach((t, i) => {
        const px = x + (i % Pr.cols) * Pr.stepX, py = Pr.top + Math.floor(i / Pr.cols) * Pr.stepY;
        edge(c, px, py, Pr.w, Pr.h, t === pmin || t === pmax ? 'ink3' : 'quietLine', Pr.r);
        text(c, 'Д' + (i + 1), px + Pr.padX, py + Pr.padY, 'bitHead', 'mute');
        text(c, String(t), px + Pr.padX, py + Pr.h - Pr.padY - style('driveCell').lh, 'driveCell', 'white');
      });
    });
  }
  const grouped = v => String(v).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  // A mini chart: the values on their scale, a line, dots where they are separate snapshots.
  function spark(c, x, y, t) {
    const S = L.health.spark, [lo, hi] = t.scale, n = t.values.length, span = (hi - lo) || 1;
    const pts = t.values.map((v, i) => [x + 1 + i * (S.w - 2) / Math.max(1, n - 1), y + S.h - 2 - (v - lo) / span * (S.h - 4)]);
    const p = new Path2D();
    pts.forEach(([px, py], i) => i ? p.lineTo(px, py) : p.moveTo(px, py));
    c.save(); c.lineJoin = 'round'; stroke(c, p, 'blue', S.stroke); c.restore();
    if (t.points) pts.forEach(([px, py]) => dot(c, px, py, S.dot, 'blue'));
  }

  /* ------------------------------------------- Потоковые данные · Графики */
  // Up to four signals on one time axis, each its own lane and scale - never narrower than its norm
  // or its resolution × 20 - the norm a band; the cursor reads all of them at one moment. The signals
  // are the ones ticked on the table, their lanes in the squares' order (design/graphs, 2026-10-23).
  function sysGraphs(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const G = L.graphs, gx = G.plotX, gw = W - 24 - gx, Cu = G.cursor;
      const n = f.signals[0].values.length, ci = Math.round((f.window - f.cursor) / f.window * (n - 1));
      const cx = gx + ci / (n - 1) * gw;
      f.signals.forEach((s, k) => {
        const top = G.lanes[k], lh = G.lane, [lo, hi] = s.scale, Y = v => top + (hi - v) / (hi - lo) * lh;
        fill(c, gx, top + lh, gw, 1, 'rule');
        fill(c, gx, top, gw, 1, 'hair');
        if (s.norm) fill(c, gx, Y(s.norm[1]), gw, Y(s.norm[0]) - Y(s.norm[1]), 'band');
        const p = new Path2D();
        s.values.forEach((v, i) => { const px = gx + i / (n - 1) * gw; if (i) p.lineTo(px, Y(v)); else p.moveTo(px, Y(v)); });
        c.save(); c.lineJoin = 'round'; stroke(c, p, 'blue', 1.5); c.restore();
        const sc = Object.assign({}, s, { decimals: s.scaleDecimals != null ? s.scaleDecimals : s.decimals });
        text(c, numText(hi, sc), W - 24 - G.scaleInset, top + 2, 'scale', 'faint', { align: 'right' });
        text(c, numText(lo, sc), W - 24 - G.scaleInset, top + lh - style('scale').lh - 2, 'scale', 'faint', { align: 'right' });
        // the legend: the lane's square as the table has it, name and «×» (off the graphs) at the
        // legend's right edge; under the name the PID, the value at the cursor, the minute's extremes
        const P = L.pick, lx = 24 + P.size + P.legendGap, K = P.close;
        let ly = top;
        pickBox(c, 24, ly + (style('titleAside').lh - P.size) / 2, k + 1);
        closeMark(c, 24 + G.legendW - K.size / 2, ly + style('titleAside').lh / 2);
        text(c, s.name, lx, ly, 'titleAside', 'white', { max: 24 + G.legendW - K.size - K.gap - lx });
        ly += style('titleAside').lh + G.nameGap;
        text(c, `PID ${s.pid} · ${s.abbr}`, lx, ly, 'sig', 'mute');
        ly += style('sig').lh + G.nameGap + G.valueGap;
        const vw = text(c, numText(s.values[ci], s), lx, ly, 'counter', 'white');
        text(c, s.unit, lx + vw + G.valueGap, ly + style('counter').lh - style('unit').lh - 2, 'unit', 'dim');
        ly += style('counter').lh + G.nameGap;
        text(c, numText(Math.min(...s.values), s) + '…' + numText(Math.max(...s.values), s), lx, ly, 'unitTag', 'faint');
      });
      fill(c, cx - 0.5, Cu.top, 1, Cu.bottom - Cu.top, 'wire4');
      f.signals.forEach((s, k) => {
        const top = G.lanes[k], [lo, hi] = s.scale;
        dot(c, cx, top + (hi - s.values[ci]) / (hi - lo) * G.lane, Cu.dot, 'white');
      });
      // the axis: seconds back, the cursor's moment on it
      [60, 45, 30, 15].forEach(t => text(c, '−' + t + ' с', gx + (f.window - t) / f.window * gw, G.axisY, 'rowSig', 'faint'));
      text(c, 'сейчас', W - 24, G.axisY, 'rowSig', 'faint', { align: 'right' });
      const Tg = G.tag;
      fill(c, cx - Tg.w / 2, Tg.y, Tg.w, Tg.h, 'quiet', Tg.r); edge(c, cx - Tg.w / 2, Tg.y, Tg.w, Tg.h, 'quietLine', Tg.r);
      text(c, '−' + String(f.cursor).replace('.', ',') + ' с', cx, Tg.y + (Tg.h - style('unitTag').lh) / 2, 'unitTag', 'white', { align: 'center' });
    });
  }
  // A signal's number: its decimals, a comma, «−» for minus, a sign where the signal has one, thousands apart.
  function numText(v, s) {
    const d = s.decimals || 0, a = Math.abs(v).toFixed(d).split('.');
    const t = grouped(a[0]) + (a[1] ? ',' + a[1] : '');
    return (v < 0 && +a.join('') ? '−' : s.signed && v > 0 ? '+' : '') + t;
  }

  /* ------------------------------------------------------------- Журнал */
  // The car's time: the scans asked for, the software updates and the cells' snapshots on one day
  // axis; every code from its first read; values by scan; two scans side by side in the panel.
  function journal(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const J = L.journal, px = J.plotX, pw = J.plotW;
      const X = dayAxis(c, px, J.axisY, pw, f.days);
      for (let i = 0; i <= f.days.length; i++) fill(c, px + i * pw / f.days.length, J.gridTop, 1, J.gridH, 'hair');
      const lane = (name, top, h) => text(c, name, 24, mid(top, h, 'unit'), 'unit', 'ink3', { max: J.laneW });
      // scans: a bar each, a full scan taller and white, a block read again dimmer
      const [ys, yw, yc] = J.lanes;
      lane('Сканы', ys, J.laneH);
      f.scans.forEach(s => {
        const full = s.kind !== 'block';
        fill(c, X(s.at) - 1, ys + (full ? 6 : 7), 2, full ? 16 : 14, full ? 'text' : 'dim');
      });
      lane('Обновления ПО', yw, J.laneH);
      f.software.forEach(u => {
        const ox = X(u.at), oy = yw + J.laneH / 2;
        c.save(); c.translate(ox, oy); c.rotate(Math.PI / 4); fill(c, -6, -6, 12, 12, 'text'); c.restore();
        text(c, `${u.block} ${u.from} → ${u.to}`, ox + 12, mid(yw, J.laneH, 'sig'), 'sig', 'dim');
      });
      lane('Снимки ячеек', yc, J.laneH);
      // a snapshot each dot; those at a full charge (SOC ≥ 95 %), which «Здоровье» judges by, white and named
      // over the dot, where no neighbour stands: beside it the label ran over the next snapshots
      f.cellShots.forEach(s => {
        const sx = X(s.at), sy = yc + J.laneH / 2, full = s.soc >= 95;
        dot(c, sx, sy, J.dot / 2, full ? 'text' : 'sep');
        if (full) text(c, `SOC ${s.soc} %`, sx, sy - J.dot / 2 - J.cellLabel - style('tick').lh, 'tick', 'dim', { align: 'center' });
      });
      J.rules.forEach(ry => fill(c, 24, ry, px + pw - 24, 1, 'rule'));
      // codes: a line from the first read to the last scan that saw them, a dot each read
      text(c, 'КОДЫ · С ПЕРВОГО ПРОЧТЕНИЯ', 24, J.codesLabel, 'groupTitle', 'mute');
      f.codes.forEach((k, i) => {
        const y = J.codesTop + i * J.codeStep, cy = y + J.codeH / 2, ink = k.gone ? 'mute' : 'orange';
        if (!k.gone) sign(c, k.kind || 'hist', 24 + L.sign.r, cy);
        const w = text(c, `${k.block} ${k.addr}`, 24 + 2 * L.sign.r + 8, mid(y, J.codeH, 'cellAbbr'), 'cellAbbr', k.gone ? 'mute' : 'ink3');
        text(c, '×' + k.count, 24 + 2 * L.sign.r + 16 + w, mid(y, J.codeH, 'sig'), 'sig', k.gone ? 'faint' : 'mute');
        const a = X(k.seen[0]), b = X(k.seen[k.seen.length - 1]);
        c.save(); c.globalAlpha = L.code.timeline.barAlpha; fill(c, a, cy - 1, b - a, 2, ink); c.restore();
        k.seen.forEach(s => dot(c, X(s), cy, J.dot / 2, ink));
      });
      // values: 12 V by scan, the cells' spread at a full charge by snapshot
      text(c, 'ВЕЛИЧИНЫ', 24, J.valuesLabel, 'groupTitle', 'mute');
      const [v0, v1] = J.values, V = f.values;
      lane(V.lv.name, v0, J.valueH);
      const [lo, hi] = V.lv.scale, pts = V.lv.points.map(([at, v]) => [X(at), v0 + J.valueH - 4 - (v - lo) / (hi - lo) * (J.valueH - 8), v]);
      if (pts.length > 1) {
        const p = new Path2D(); pts.forEach(([x, y], i) => i ? p.lineTo(x, y) : p.moveTo(x, y));
        stroke(c, p, 'blue', 1.5);
      }
      pts.forEach(([x, y]) => dot(c, x, y, J.point, 'blue'));
      if (pts.length) { const [lx, , lv] = pts[pts.length - 1]; text(c, String(lv).replace('.', ','), lx + 8, mid(v0, J.valueH, 'sig'), 'sig', 'dim'); }
      lane(V.full.name, v1, J.valueH);
      if (!V.full.points.length) text(c, '—', px + 4, mid(v1, J.valueH, 'sig'), 'sig', 'faint');
      V.full.points.forEach(([at, v]) => {
        const x = X(at);
        dot(c, x, v1 + J.valueH / 2, J.dot / 2 - 0.5, 'blue');
        text(c, v + ' мВ', x + 7, mid(v1, J.valueH, 'sig'), 'sig', 'dim');
      });
      journalPanel(c, f.compare);
    });
  }
  // Two scans side by side - or the one scan there is: codes new, gone and kept, software, values.
  function journalPanel(c, m) {
    const P = L.journal.panel, x = P.x, w = W - 24 - x, ix = x + P.padX, iw = w - 2 * P.padX;
    fill(c, x, P.top, w, P.h, 'plate', P.r); edge(c, x, P.top, w, P.h, 'rule', P.r);
    let y = P.top + P.padY;
    const label = (t, gap) => { y += gap || 0; text(c, t, ix, y, 'groupTitle', 'mute'); y += style('groupTitle').lh + 4; };
    const row = (a, b, ink, tagText) => {
      text(c, a, ix, mid(y, P.row, 'unit'), 'unit', 'ink3');
      const bw = text(c, b, ix + iw, mid(y, P.row, 'normMono'), 'normMono', ink || 'text', { align: 'right' });
      if (tagText) tagChip(c, tagText, ix + iw - bw - 8, y + P.row / 2);
      rule(c, ix, y + P.row, iw, 'hair');
      y += P.row + 1 + 4;
    };
    label(m.from ? 'СРАВНЕНИЕ' : 'СКАН');
    text(c, m.from ? `${m.from} → ${m.to}` : m.to, ix, mid(y, P.head, 'normMono'), 'normMono', 'white');
    y += P.head + 4;
    label('КОДЫ', 6);
    m.codes.forEach(([k, v]) => row(k, String(v)));
    if (m.software.length) { label('ПО', P.gap); m.software.forEach(s => row(s.block, `${s.from} → ${s.to}`, 'text', 'ИЗМ.')); }
    label('ВЕЛИЧИНЫ', P.gap);
    m.values.forEach(([k, a, b, unit]) => row(k, (a != null ? `${a} → ${b}` : b) + (unit ? ' ' + unit : '')));
  }

  // «Журнал · Сравнение»: the two scans across the page - their moments, then the codes, the software
  // and the values each on a plate of its own, the panel's rows at the plates' width.
  function journalCompare(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const J = L.journal, C = J.compare, P = J.panel, m = f.compare;
      text(c, m.from ? 'СРАВНЕНИЕ' : 'СКАН', 24, C.top, 'groupTitle', 'mute');
      text(c, m.from ? `${m.from} → ${m.to}` : m.to, 24, mid(C.top + style('groupTitle').lh, C.head, 'keyValue'), 'keyValue', 'white');
      const pw = (W - 48 - (C.cols - 1) * C.gap) / C.cols, top = C.top + style('groupTitle').lh + C.head + C.gap;
      const plates = [
        ['КОДЫ', m.codes.map(([k, v]) => [k, String(v)])],
        ['ПО', m.software.length ? m.software.map(s => [s.block, `${s.from} → ${s.to}`, 'ИЗМ.']) : [['без изменений', '—']]],
        ['ВЕЛИЧИНЫ', m.values.map(([k, a, b, unit]) => [k, (a != null ? `${a} → ${b}` : b) + (unit ? ' ' + unit : '')])],
      ];
      // the plates as tall as the longest: its label and its rows
      const ph = 2 * P.padY + style('groupTitle').lh + 4 + Math.max(...plates.map(p => p[1].length)) * (P.row + 1 + 4) - 4;
      plates.forEach(([label, rows], i) => {
        const x = 24 + i * (pw + C.gap), ix = x + P.padX, iw = pw - 2 * P.padX;
        fill(c, x, top, pw, ph, 'plate', P.r); edge(c, x, top, pw, ph, 'rule', P.r);
        let y = top + P.padY;
        text(c, label, ix, y, 'groupTitle', 'mute');
        y += style('groupTitle').lh + 4;
        rows.forEach(([a, b, tagText]) => {
          text(c, a, ix, mid(y, P.row, 'unit'), 'unit', 'ink3');
          const bw = text(c, b, ix + iw, mid(y, P.row, 'normMono'), 'normMono', b === '—' ? 'faint' : 'text', { align: 'right' });
          if (tagText) tagChip(c, tagText, ix + iw - bw - 8, y + P.row / 2);
          rule(c, ix, y + P.row, iw, 'hair');
          y += P.row + 1 + 4;
        });
      });
    });
  }

  // «Журнал · Коды»: every code the journal has read, a row each - the present ones first with their
  // sign, then the gone ones, muted and with none. A row opens the code's page.
  function journalCodes(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const K = L.journal.codes, cols = columns(K.cols, 24, W - 48), Li = K.list;
      headRow(c, cols, ['', 'КОД', 'БЛОК', 'ВПЕРВЫЕ', 'ПОСЛЕДНИЙ РАЗ', 'ПРОЧТЕНИЙ', 'СЕЙЧАС', ''], K.top, K.head);
      let y = Li.top;
      f.codeRows.forEach(r => {
        const h = Li.row, k = r.gone ? null : kindOf(r.status), ink = t => r.gone ? 'mute' : t;
        if (k) sign(c, k, cols[0][0] + cols[0][1] / 2, y + h / 2);
        runs(c, codeRuns(r.code, 'codeSmall', r.gone ? 'mute' : k === 'active' ? 'red' : 'white', 'cellMono'), cols[1][0], base(mid(y, h, 'codeSmall'), 'codeSmall'));
        text(c, `${r.block} ${r.addr}`, cols[2][0], mid(y, h, 'cellMono'), 'cellMono', ink('text'));
        text(c, r.first, cols[3][0], mid(y, h, 'cellMono'), 'cellMono', ink('dim'));
        text(c, r.last, cols[4][0], mid(y, h, 'cellMono'), 'cellMono', ink('dim'));
        text(c, r.seen, cols[5][0], mid(y, h, 'cellMono'), 'cellMono', ink('text'));
        text(c, r.now, cols[6][0], mid(y, h, 'cell'), 'cell', ink('text'));
        chevron(c, cols[7][0] + (cols[7][1] - L.chevron) / 2, y + (h - L.chevron) / 2);
        rule(c, 24, y + h, W - 48, 'hair');
        y += h + Li.rule;
      });
    });
  }

  /* -------------------------------------------------------------- Отчёт */
  // The report as it will be saved: its first page on the left, what goes in on the right, and where
  // it lands - «Загрузки», a PDF for people and a JSON of the raw answers.
  function report(c, f) {
    header(c, f.top);
    paper(c, f.paper);
    const R = L.report, x = R.x, w = W - 24 - x;
    text(c, 'Отчёт о диагностике', x, R.titleY, 'title', 'white');
    text(c, f.line, x, R.lineY, 'line', 'mute', { max: w });
    text(c, 'ЧТО ВОЙДЁТ', x, R.labelY, 'groupTitle', 'mute');
    let y = R.listTop;
    f.include.forEach(it => {
      const by = y + (R.row - R.box) / 2;
      if (it.on) {
        fill(c, x, by, R.box, R.box, 'blue', R.boxR);
        stroke(c, `M${x + 4.5} ${by + 9.3}L${x + 7.5} ${by + 12.9}L${x + 14.4} ${by + 5.4}`, 'white', 1.8, null, 'round');
      } else {
        c.save(); rrect(c, x + 0.75, by + 0.75, R.box - 1.5, R.box - 1.5, R.boxR); c.strokeStyle = col('line3'); c.lineWidth = 1.5; c.stroke(); c.restore();
      }
      text(c, it.name, x + R.box + R.boxGap, mid(y, R.row, 'item'), 'item', it.on ? 'white' : 'dim');
      text(c, it.detail, x + w, mid(y, R.row, 'unit'), 'unit', 'mute', { align: 'right' });
      rule(c, x, y + R.row, w, 'hair');
      y += R.row + 1;
    });
    text(c, 'КУДА', x, R.destLabel, 'groupTitle', 'mute');
    text(c, f.dest.path, x, R.dest, 'keyValue', 'white', { max: w });
    text(c, f.dest.note, x, R.destNote, 'cellSmall', 'ink3');
    text(c, f.dest.size, x, R.destSize, 'noteMono', 'mute');
    footer(c, f.footer);
  }
  // The first page, small: a sheet of paper with the report's own type.
  function paper(c, p) {
    const P = L.report.paper, x = P.x + P.padX, w = P.w - 2 * P.padX;
    fill(c, P.x, P.y, P.w, P.h, 'paper', P.r);
    let y = P.y + P.padY;
    const by = base(y, 'paperTitle');
    glyphs(c, 'ОТЧЁТ О ДИАГНОСТИКЕ', x, by, style('paperTitle'), 'paperInk');
    glyphs(c, p.by, x + w - measure(c, p.by, 'paperSmall'), by, style('paperSmall'), 'paperGrey');
    y += style('paperTitle').lh + 6;
    text(c, p.car, x, y, 'paper', 'paperInk', { max: w }); y += style('paper').lh;
    text(c, p.when, x, y, 'paperSmall', 'paperGrey', { max: w }); y += style('paperSmall').lh;
    const head = t => {
      y += P.headGap;
      text(c, t, x, y, 'paperHead', 'paperGrey');
      y += style('paperHead').lh + 2;
      fill(c, x, y, w, 1, 'paperRule');
      y += 1 + P.lineGap;
    };
    p.sections.forEach(s => {
      head(s.head);
      if (s.lines) s.lines.forEach(l => { text(c, l, x, y, 'paperSmall', 'paperInk', { max: w }); y += style('paperSmall').lh; });
      if (s.codes) {
        const cols = columns([74, 58, '1fr', 52, 40], x, w), lh = style('paperSmall').lh;
        s.codes.forEach(b => b.codes.forEach((d, i) => {
          if (!i) text(c, `${b.abbr} ${b.addr}`, cols[0][0], y, 'paperMono', 'paperGrey');
          text(c, d.code, cols[1][0], y, 'paperCode', 'paperInk');
          text(c, d.meaning || d.type, cols[2][0], y, 'paperSmall', 'paperInk', { max: cols[2][1] - 4 });
          text(c, { active: 'активный', pending: 'ожидающий', hist: 'история' }[kindOf(d.status)], cols[3][0], y, 'paperSmall', 'paperInk');
          text(c, b.since, cols[4][0], y, 'paperMono', 'paperGrey');
          y += lh;
        }));
      }
      if (s.blocks) {
        const cols = columns([50, 30, '1fr', 40], x, w), lh = style('paperSmall').lh;
        s.blocks.forEach(b => {
          text(c, b.abbr, cols[0][0], y, 'paperCode', 'paperInk');
          text(c, b.req, cols[1][0], y, 'paperMono', 'paperGrey');
          text(c, b.f194 || '—', cols[2][0], y, 'paperMono', 'paperInk');
          text(c, records(b) ? String(records(b)) : '—', cols[3][0], y, 'paperSmall', 'paperGrey');
          y += lh;
        });
        if (s.more) { text(c, `… ещё ${s.more}`, x, y + 2, 'paperSmall', 'paperGrey'); y += lh + 2; }
      }
    });
    // the foot: what was asked, the page
    const fy = P.y + P.h - P.padY - style('paperFoot').lh - 4;
    fill(c, x, fy, w, 1, 'paperRule');
    text(c, p.foot, x, fy + 4, 'paperFoot', 'paperGrey', { max: w });
  }

  const SCREENS = { sysParam, sysPackCells, summary, modules, codeScreen, packHealth, sysGraphs, journal, journalCompare, journalCodes, report, sysLive, sysDtc, sysBlocks, sysPending, sysStream, sysDrivesCompare, sysAdasCompare, sysEngineDtc, sysChassis, sysBodyState, sysAdas, sysDrives, sysSafety, sysHv, sysEngineScheme, sysClimate, sysLv, sysCabin, sysGbFlags, bodyDtc, ecuDtc, ecuFault, ecuTests, ecuLive, ecuIdent, blockMask };
  function draw(c, board, fixture) {
    c.save();
    c.fillStyle = col('ground'); c.fillRect(0, 0, W, H);
    SCREENS[board.screen](c, fixture);
    c.restore();
  }
  root.ScannerBoard = { draw, screens: Object.keys(SCREENS) };
})(window);
