/*
 * «Сервис → Диагностика», the scanner - the twelve boards of the owner's canvas «Диагностика ·
 * инженерное меню», page «Сканер (v3)» (approved 2026-10-20), made deterministic.
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
  function button(c, str, x, kind) {
    const F = L.footer, b = F.button, top = F.top + 1 + (F.height - 1 - b.h) / 2;
    const w = measure(c, str, 'button') + 2 * b.pad + 2;
    const primary = kind === 'primary';
    fill(c, x, top, w, b.h, primary ? 'rgba(67,159,252,0.10)' : 'quiet', b.r);
    edge(c, x, top, w, b.h, primary ? 'rgba(67,159,252,0.55)' : 'quietLine', b.r);
    text(c, str, x + 1 + b.pad, top + (b.h - style('button').lh) / 2, 'button', primary ? 'blueInk' : 'text');
    return w;
  }
  const SWATCH = {
    ok: c => ['tileOk', 'blueEdge'], hist: () => ['tileHist', 'sep'], unread: () => ['tileUnread', 'line3'],
  };
  function swatch(c, kind, x, y) {
    const S = L.footer.legend;
    if (kind === 'none') { edge(c, x, y, S.w, S.h, 'line4', S.r, [3, 3]); return; }
    if (kind === 'mech') {
      c.fillStyle = col('dim2'); c.fillRect(x, y + 2, S.w, 1.4); c.fillRect(x, y + 6.6, S.w, 1.4); return;
    }
    const [f, e] = SWATCH[kind](c);
    fill(c, x, y, S.w, S.h, f, S.r); edge(c, x, y, S.w, S.h, e, S.r);
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
    // the close button, then right to left: the time, the car, the link
    const K = Hd.close, kx = W - Hd.padRight - K.w, ky = (room - K.h) / 2;
    fill(c, kx, ky, K.w, K.h, 'quiet', K.r);
    const ix = kx + (K.w - K.icon) / 2, iy = ky + (K.h - K.icon) / 2, a = K.icon / 8, b = K.icon - a;
    stroke(c, `M${ix + a} ${iy + a}L${ix + b} ${iy + b}M${ix + b} ${iy + a}L${ix + a} ${iy + b}`, 'text', K.stroke, null, 'round');
    let r = kx - Hd.gap;
    r -= text(c, h.time, r, (room - style('metaMono').lh) / 2, 'metaMono', 'mute', { align: 'right' }) + Hd.gap;
    r -= text(c, h.car, r, (room - style('meta').lh) / 2, 'meta', 'mute', { align: 'right' }) + Hd.gap;
    r -= text(c, h.link, r, (room - style('meta').lh) / 2, 'meta', 'dim', { align: 'right' }) + Hd.dotGap;
    glow(c, Hd.dotGlow, 'blue', () => fill(c, r - Hd.dot, (room - Hd.dot) / 2, Hd.dot, Hd.dot, 'blue', Hd.dot / 2));
  }

  // A block's or a system's head: title and its aside over the mono line; counters at the right.
  // A counter is as wide as the block's 112 dp, or as its widest value and a padding's room after
  // it, rounded up to 8 dp.
  function counterWidth(c, counters) {
    const B = L.block, widest = Math.max(0, ...(counters || []).map(([, v]) => measure(c, v, 'counter')));
    return Math.max(B.counterW, Math.ceil((widest + B.counterPad) / B.counterStep) * B.counterStep);
  }
  function blockHead(c, b) {
    const B = L.block, cw = counterWidth(c, b.counters);
    const lab = style('counterLabel'), val = style('counter');
    let right = W - 24;
    if (b.counters) {
      const n = b.counters.length, top = B.top + (B.height - lab.lh - B.counterGap - val.lh) / 2;
      const box = cw + B.counterPad + 1;
      right = W - 24 - n * box;
      b.counters.forEach(([label, value, tone], i) => {
        const x = right + i * box;
        vrule(c, x, top, lab.lh + B.counterGap + val.lh);
        text(c, label, x + 1 + B.counterPad, top, 'counterLabel', 'mute');
        const ink = tone === 'on' ? 'orangeInk' : 'white';
        const draw = () => text(c, value, x + 1 + B.counterPad, top + lab.lh + B.counterGap, 'counter', ink);
        if (tone === 'on') glow(c, 10, 'rgba(255,159,25,0.7)', draw);
        draw();
      });
    }
    if (b.dtc) {
      // a body block: its history badge, then the DTC count right-aligned
      const big = style('counterBig'), top = B.top + (B.height - lab.lh - 2 - big.lh) / 2;
      const wl = Math.max(measure(c, b.dtc[0], lab), measure(c, b.dtc[1], big));
      text(c, b.dtc[0], W - 24, top, 'counterLabel', 'mute', { align: 'right' });
      text(c, b.dtc[1], W - 24, top + lab.lh + 2, 'counterBig', 'white', { align: 'right' });
      right = W - 24 - wl - B.badgeGap;
      const bw = measure(c, b.badge[1], 'badgeBig') + 2 * L.badge.big.pad;
      right -= bw;
      badge(c, b.badge[1], right, B.top + (B.height - L.badge.big.h) / 2, b.badge[0], 'big');
      right -= B.badgeGap;
    }
    const t = style('title'), ln = style('line');
    const top = B.top + (B.height - t.lh - B.titleGap - ln.lh) / 2;
    const room = right - 24;
    runs(c, [[b.title + ' ', 'title', 'white'], [b.aside, 'titleAside', 'mute']], 24, base(top, t), room);
    text(c, b.line, 24, top + t.lh + B.titleGap, 'line', 'dim2', { max: room });
  }

  function tabs(c, t) {
    const N = L.tabs, s = style('tab'), top = N.top + (N.height - s.lh) / 2;
    let x = 24;
    t.items.forEach((label, i) => {
      const on = i === t.active;
      let w = text(c, label, x, top, 'tab', on ? 'white' : 'mute');
      if (t.alert === i) {
        const d = L.header.dot;
        glow(c, 6, 'orange', () => fill(c, x + w + N.dotGap, N.top + (N.height - d) / 2, d, d, 'orange', d / 2));
        w += N.dotGap + d;
      }
      if (on) fill(c, x, N.top + N.height - N.underline, w, N.underline, 'blue');
      x += w + N.gap;
    });
    rule(c, 24, N.top + N.height, W - 48);
  }

  // The footer: buttons from the left, then a legend; notes from the right, '|' a divider.
  // present: the tile kinds the board draws - a legend entry for a kind nobody shows is left out.
  const TILE_KINDS = ['ok', 'hist', 'unread', 'none'];
  function footer(c, f, present) {
    const F = L.footer;
    fill(c, 0, F.top, W, F.height, 'bar');
    rule(c, 0, F.top, W);
    const room = F.height - 1, top0 = F.top + 1;
    let x = 24;
    (f.buttons || []).forEach(([label, kind]) => { x += button(c, label, x, kind) + F.gap; });
    const gap = f.legendGap || F.gap;
    if (f.legend) x += gap - F.gap;
    (f.legend || []).filter(([kind]) => !present || !TILE_KINDS.includes(kind) || present.has(kind)).forEach(([kind, label]) => {
      const S = F.legend;
      swatch(c, kind, x, top0 + (room - S.h) / 2);
      x += S.w + S.gap;
      x += text(c, label, x, top0 + (room - style('note').lh) / 2, 'note', 'dim2') + gap;
    });
    let r = W - 24;
    for (let i = (f.notes || []).length - 1; i >= 0; i--) {
      const n = f.notes[i];
      if (n === '|') { const [dw, dh] = F.divider; fill(c, r - dw, top0 + (room - dh) / 2, dw, dh, 'divider'); r -= dw + F.gap; continue; }
      const [str, mono] = Array.isArray(n) ? n : [n, false];
      const sn = mono ? 'noteMono' : 'note';
      r -= text(c, str, r, top0 + (room - style(sn).lh) / 2, sn, 'faint', { align: 'right' }) + F.gap;
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
  const KIND_BADGE = { ok: 'OK', hist: 'ИСТОРИЯ', unread: 'DTC НЕ ЧИТАЛИ', none: 'НЕТ АДРЕСА' };
  const reply = req => req ? req + ' → ' + (parseInt(req, 16) + 8).toString(16).toUpperCase() : '—';
  const read = e => e.kind === 'ok' || e.kind === 'hist';
  function groupSummary(g) {
    const n = k => g.ecus.filter(e => e.kind === k).length;
    const parts = [g.ecus.length + ' бл.'];
    if (n('ok') + n('hist')) parts.push('прочитано ' + (n('ok') + n('hist')));
    if (n('hist')) parts.push('история ' + n('hist'));
    if (n('unread')) parts.push('DTC не читали ' + n('unread'));
    if (n('none')) parts.push('нет адреса ' + n('none'));
    return parts.join(' · ');
  }

  function scanTable(c, f, colSpec) {
    const S = L.scan, cols = columns(colSpec, 24, W - 48);
    const P = S.table.pad, NP = S.table.numPad;
    headRow(c, cols, ['СИСТЕМА', 'БЛОК', 'ЗАПРОС → ОТВЕТ', 'ПРОТОКОЛ', ['DTC АКТ.', 'right', NP], ['DTC ИСТ.', 'right', NP], 'СТАТУС'],
      S.table.top, S.table.head);
    const Li = S.list;
    c.save();
    c.beginPath(); c.rect(24, Li.top, W - 48, Li.bottom - Li.top); c.clip();
    let y = Li.top;
    for (const g of f.groups) {
      if (y >= Li.bottom) break;
      const gt = style('groupTitle');
      const w = text(c, g.title, 24 + P, mid(y, Li.group - 1, 'groupTitle'), 'groupTitle', 'dim');
      text(c, groupSummary(g), 24 + P + w + Li.groupGap, mid(y, Li.group - 1, 'groupSummary'), 'groupSummary', 'faint2');
      rule(c, 24, y + Li.group - 1, W - 48);
      y += Li.group;
      for (const e of g.ecus) {
        if (y >= Li.bottom) break;
        y = scanRow(c, e, cols, y);
      }
    }
    c.restore();
  }

  // The autoscan's row of one block: name, unit, route, protocol, both counts, the chip, a chevron.
  function scanRow(c, e, cols, y) {
    const S = L.scan, P = S.table.pad, NP = S.table.numPad, Li = S.list, h = Li.row;
    const ink = e.kind === 'none' ? 'faint' : e.kind === 'unread' ? 'ink3' : 'white';
    text(c, e.name, cols[0][0] + P, mid(y, h, 'cell'), 'cell', ink, { max: cols[0][1] - P });
    text(c, e.unit, cols[1][0], mid(y, h, 'cellSmall'), 'cellSmall', 'dim', { max: cols[1][1] });
    text(c, reply(e.req), cols[2][0], mid(y, h, 'cellMono'), 'cellMono', 'dim');
    text(c, e.proto || '—', cols[3][0], mid(y, h, 'cellSmall'), 'cellSmall', 'dim');
    text(c, read(e) ? e.active : '—', cols[4][0] + cols[4][1] - NP, mid(y, h, 'cellNum'), 'cellNum', ink, { align: 'right' });
    text(c, read(e) ? e.history : '—', cols[5][0] + cols[5][1] - NP, mid(y, h, 'cellNum'), 'cellNum', ink, { align: 'right' });
    badge(c, KIND_BADGE[e.kind], cols[6][0], y + (h - L.badge.h) / 2, e.kind);
    if (e.opens) {
      const k = Li.chevron / 24, cx = cols[7][0] + (cols[7][1] - Li.chevron) / 2, cy = y + (h - Li.chevron) / 2;
      stroke(c, `M${cx + 9 * k} ${cy + 5 * k}L${cx + 16 * k} ${cy + 12 * k}L${cx + 9 * k} ${cy + 19 * k}`, 'faint', 2 * k, null, 'round');
    }
    rule(c, 24, y + h, W - 48, 'hair');
    return y + h + Li.rule;
  }

  /* --------------------------------------------- systems down the left */
  // The section built around the car's systems, as Tesla's service mode is: the systems down the
  // left, one system a tab, the autoscan of every block first; the rest of the screen is the
  // chosen one's, its own tabs inside it. A system's mark says only what its look cannot: codes.
  function rail(c, R) {
    const S = L.rail, top = L.header.height, bottom = L.footer.top;
    fill(c, 0, top, S.w, bottom - top, 'bar');
    fill(c, S.w - 1, top, 1, bottom - top, 'rule');
    let y = top + S.padTop;
    R.items.forEach(it => {
      if (it === '|') { y += S.sepGap; rule(c, S.padX, y, S.w - 2 * S.padX); y += S.sepGap + 1; return; }
      const on = it.name === R.active;
      if (on) { fill(c, 0, y, S.w - 1, S.item, 'pidFill'); fill(c, 0, y, S.bar, S.item, 'blue'); }
      let r = S.w - S.padX;
      if (it.mark) {
        const [t, ink] = it.mark;
        r -= text(c, t, r, mid(y, S.item, 'railMark'), 'railMark', ink, { align: 'right' }) + 8;
      }
      text(c, it.name, S.padX, mid(y, S.item, 'railItem'), 'railItem', on ? 'white' : 'dim', { max: r - S.padX });
      y += S.item;
    });
  }

  function shell(c, f, body, present) {
    header(c, f.top);
    rail(c, f.rail);
    const full = W;
    W = full - L.rail.w;
    c.save(); c.translate(L.rail.w, 0);
    try { body(); } finally { c.restore(); W = full; }
    footer(c, f.footer, present && present());
  }

  function sysScan(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); scanTable(c, f, L.rail.scanCols); });
  }

  function sysLive(c, f) {
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); liveTable(c, f, L.rail.liveCols); });
  }

  // A system's codes, every block's on one table: the block named on its first row, a rule
  // between blocks; a row opens the block with its status bits.
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
        if (r.active) fill(c, 24, y, W - 48, h, 'orangeRow');
        if (r.block !== last) {
          text(c, r.block, cols[0][0] + P, mid(y, h, 'cell'), 'cell', 'white');
          text(c, r.addr, cols[0][0] + P + measure(c, r.block, 'cell') + 8, mid(y, h, 'cellMono'), 'cellMono', 'faint');
        }
        last = r.block;
        text(c, r.code, cols[1][0], mid(y, h, 'code'), 'code', r.active ? 'orangeInk' : 'white');
        runs(c, [[r.ftb, 'cellNum', 'dim'], [' · ' + r.ftbName, 'cell', 'text']], cols[2][0], base(mid(y, h, 'cell'), 'cell'), cols[2][1] - 12);
        text(c, r.desc, cols[3][0], mid(y, h, 'cell'), 'cell', 'text', { max: cols[3][1] - 16 });
        runs(c, [[r.status, 'cellMono', 'text'], ['  ' + r.word, 'cellSmall', 'dim']], cols[4][0], base(mid(y, h, 'cell'), 'cell'), cols[4][1]);
        const ch = L.scan.list.chevron, k = ch / 24, cx = cols[5][0] + (cols[5][1] - ch) / 2, cy = y + (h - ch) / 2;
        stroke(c, `M${cx + 9 * k} ${cy + 5 * k}L${cx + 16 * k} ${cy + 12 * k}L${cx + 9 * k} ${cy + 19 * k}`, 'faint', 2 * k, null, 'round');
        rule(c, 24, y + h, W - 48, 'hair');
        y += h + 1;
      }
    });
  }

  // A system's «Блоки»: its blocks as the autoscan's rows, each opening the block.
  function sysBlocks(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const S = L.scan, cols = columns(L.rail.scanCols, 24, W - 48), NP = S.table.numPad;
      let y = headRow(c, cols, ['БЛОК', '', 'ЗАПРОС → ОТВЕТ', 'ПРОТОКОЛ', ['DTC АКТ.', 'right', NP], ['DTC ИСТ.', 'right', NP], 'СТАТУС'],
        S.table.top, S.table.head);
      for (const e of f.ecus) y = scanRow(c, e, cols, y);
    });
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
          fill(c, x, y, tw, T.h, alarm ? 'orangeFill' : fl.state === 'none' ? 'tileUnread' : 'tileOk', T.r);
          edge(c, x, y, tw, T.h, alarm ? 'orange' : fl.state === 'none' ? 'line3' : 'blueEdge', T.r);
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
    const T = L.topology.tile, near = x < cx ? x + T.w : x, my = y + T.h / 2;
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
      const cx = (W) / 2, K = car(c, cx, L.car.top, L.car.h), O = L.callout, S = L.chassisScheme, T = L.topology.tile;
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
          if (f.epb.look === 'held' || f.epb.look === 'fault') fill(c, x, y, P.w, P.h, f.epb.look === 'held' ? 'blue' : 'orange', P.r);
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
      const cx = W / 2, K = car(c, cx, L.car.top, L.car.h), O = L.callout, T = L.topology.tile, B = L.bodyScheme;
      const D = L.car.doors, lx = B.x, rx = W - B.x - O.w;
      // the doors as heavy strokes on the sides: closed lies on the body, open would swing out
      f.doors.forEach(d => {
        const left = d.side === 'L', x = left ? K.x0 : K.x0 + K.w;
        const [a, b] = d.axle === 'F' ? D.front : D.rear;
        stroke(c, `M${x} ${K.y0 + a}V${K.y0 + b}`, d.open ? 'orange' : 'blueSoft', 3);
        const rows = [[d.label, 'counterLabel', 'mute'], [d.state, 'cell', 'white'], [d.detail, 'cellSmall', 'dim']];
        const h = 2 * O.padY + style('counterLabel').lh + style('cell').lh + style('cellSmall').lh + 2 * O.gap;
        callout(c, left ? lx : rx, K.y0 + (a + b) / 2 - h / 2, O.w, rows, 'ok', [x, K.y0 + (a + b) / 2]);
      });
      // the hood and the tailgate as the doors are, on the nose's and the tail's line: closed
      // blue, open orange, not read dark
      const Lm = B.lamps, nr = L.car.noseR, tr0 = L.car.tailR;
      const lid = open => open === true ? 'orange' : open === false ? 'blueSoft' : 'line4';
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
          if (m.high) { c.save(); c.globalAlpha = 0.25; stroke(c, p, 'blue', 2 * Lm.halo, null, 'round'); c.restore(); }
          lamp(c, p, m.low || m.high, 'blue', Lm.w, Lm.halo);
        });
        tail.forEach(([x, dir]) => {
          const y = K.y0 + K.h - tr0;
          lamp(c, arcPath(x, y, tr0, ...span(Lm.stop, dir)), m.stop, 'orange', Lm.w, Lm.halo);
          lamp(c, arcPath(x, y, tr0, ...span(Lm.reverse, dir)), m.reverse, 'blueSoft', Lm.w, Lm.halo);
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
        dot(c, x, y, 4, 'blue');
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
        if (s.occupied) fill(c, x, y, w, h, 'blueShare', r);
        edge(c, x, y, w, h, s.occupied ? 'blueEdge' : 'line3', r);
        if (s.belt === true) stroke(c, `M${x + w - 8} ${y + 6}L${x + 8} ${y + h - 14}`, 'blue', 2);
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
        if (state === 'charging' || state === 'full') fill(c, x, y, S.pad.w, S.pad.h, state === 'charging' ? 'blue' : 'blueSoft', 2);
        edge(c, x, y, S.pad.w, S.pad.h, state === 'fault' ? 'orange' : state == null ? 'line3' : 'blueEdge', 2);
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
      const A = L.adasCar, cx = W / 2, K = car(c, cx, A.top, A.h), T = L.topology.tile;
      const wedge = (x, y, angle, spread, len) => {
        const a0 = angle - spread / 2, a1 = angle + spread / 2;
        c.save(); c.beginPath(); c.moveTo(x, y); c.arc(x, y, len, a0, a1); c.closePath();
        c.fillStyle = col('blueShare'); c.fill(); c.strokeStyle = col('blueEdge'); c.lineWidth = 1; c.stroke(); c.restore();
        dot(c, x, y, 4, 'blue');
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
      const cx = W / 2, K = car(c, cx, L.car.top, L.car.h), O = L.callout, D = L.drivesCar, T = L.topology.tile;
      const gx = cx - D.pair, mx = cx + D.pair;
      // shafts: front motor to the front wheels, rear motors each to its wheel, engine to generator
      stroke(c, `M${K.x0} ${K.front}H${gx - 22}M${mx + 22} ${K.front}H${K.x0 + K.w}M${gx + 22} ${K.front}H${mx - 22}`, 'wire3', 1.6);
      stroke(c, `M${K.x0} ${K.rear}H${cx - D.pair - 22}M${cx + D.pair + 22} ${K.rear}H${K.x0 + K.w}`, 'wire3', 1.6);
      const eng = D.engine;
      fill(c, cx - eng.w / 2, K.y0 + eng.y, eng.w, eng.h, 'tileOk', 5); edge(c, cx - eng.w / 2, K.y0 + eng.y, eng.w, eng.h, 'line4', 5);
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

  // A range on a scale: the bar, the span the readings take on it, the scale's ends under it.
  function rangeBar(c, x, y, w, scale, span, mark, labels) {
    const R = L.rangeBar, at = v => x + (v - scale[0]) / (scale[1] - scale[0]) * w;
    text(c, labels.head, x, y, 'counterLabel', 'mute');
    text(c, labels.value, x + w, y - 3, 'valueBms', 'white', { align: 'right' });
    const by = y + R.headGap;
    fill(c, x, by, w, R.h, 'bitOff', R.h / 2);
    const a = at(span[0]), b = Math.max(at(span[1]), a + R.minSpan);
    glow(c, 6, 'rgba(67,159,252,0.7)', () => fill(c, a, by - 2, b - a, R.h + 4, 'blue', 2));
    if (mark != null) fill(c, at(mark) - 0.75, by - 5, 1.5, R.h + 10, 'white');
    text(c, labels.lo, x, by + R.h + R.tickGap, 'sig', 'faint');
    text(c, labels.hi, x + w, by + R.h + R.tickGap, 'sig', 'faint', { align: 'right' });
    if (labels.note) text(c, labels.note, x + w / 2, by + R.h + R.tickGap, 'sig', 'dim2', { align: 'center' });
  }

  // Тяговая батарея: the pack with its charge, its two poles to the DC bus, the insulation
  // measured from them to the body; the cells' and modules' ranges beside it.
  function sysPack(c, f) {
    shell(c, f, () => {
      blockHead(c, f.block); tabs(c, f.tabs);
      const P = L.packScheme, p = f.pack;
      fill(c, P.x, P.y, P.w, P.h, 'plate', P.r); edge(c, P.x, P.y, P.w, P.h, 'blueEdge', P.r);
      const ih = P.h - 2 * P.pad, fh = ih * p.soc / 100;
      fill(c, P.x + P.pad, P.y + P.pad + ih - fh, P.w - 2 * P.pad, fh, 'blueShare', 4);
      fill(c, P.x + P.pad, P.y + P.pad + ih - fh, P.w - 2 * P.pad, 1.5, 'blueEdge');
      const tx = P.x + P.pad + 14;
      text(c, 'SOC', tx, P.y + P.pad + 14, 'counterLabel', 'mute');
      text(c, p.socText, tx, P.y + P.pad + 32, 'counterBig', 'white');
      // SOC in the empty part above the charge, SOH and the cells at the bottom, inside it
      text(c, 'SOH', tx, P.y + P.h - P.pad - 100, 'counterLabel', 'mute');
      text(c, p.soh, tx, P.y + P.h - P.pad - 82, 'counter', 'text');
      text(c, p.cells, tx, P.y + P.h - P.pad - 32, 'cellSmall', 'dim');
      const px = P.x + P.w, [yp, ym] = P.poles, bx = P.busX;
      fill(c, px, yp - 8, 8, 16, 'line4', 2); fill(c, px, ym - 8, 8, 16, 'line4', 2);
      stroke(c, `M${px + 8} ${yp}H${bx}M${px + 8} ${ym}H${bx}`, 'wire', 2.4);
      text(c, 'HV+', px + 18, yp - 22, 'counterLabel', 'mute');
      text(c, 'HV−', px + 18, ym + 8, 'counterLabel', 'mute');
      text(c, 'ШИНА DC', bx, yp - 22, 'colHead', 'mute', { align: 'right' });
      text(c, p.volts, bx, yp + 10, 'hvVolts', 'text', { align: 'right' });
      text(c, p.current, bx, yp + 32, 'cellMono', 'dim', { align: 'right' });
      // insulation: from both poles to the body
      const I = P.iso, ix = I.x, iy = (yp + ym) / 2 - I.h / 2, mx = ix + I.w / 2;
      stroke(c, `M${mx} ${yp}V${iy}M${mx} ${ym}V${iy + I.h}`, 'wire3', 1.6);
      dot(c, mx, yp, 3, 'wire5'); dot(c, mx, ym, 3, 'wire5');
      fill(c, ix, iy, I.w, I.h, 'tileOk', 6); edge(c, ix, iy, I.w, I.h, 'blueEdge', 6);
      let y = iy + 12;
      text(c, 'ИЗОЛЯЦИЯ НА КОРПУС', ix + 14, y, 'counterLabel', 'mute'); y += 22;
      p.insulation.forEach(([k, v]) => { text(c, k, ix + 14, y, 'cellSmall', 'dim'); text(c, v, ix + I.w - 14, y, 'cellMono', 'white', { align: 'right' }); y += 22; });
      text(c, p.isoNorm, ix + 14, y + 4, 'sig', 'faint');
      const gx = ix + I.w + 30, gy = iy + I.h / 2;
      stroke(c, `M${ix + I.w} ${gy}H${gx}V${gy + 8}M${gx - 12} ${gy + 8}H${gx + 12}M${gx - 8} ${gy + 13}H${gx + 8}M${gx - 4} ${gy + 18}H${gx + 4}`, 'wire3', 1.6);
      text(c, 'корпус', gx, gy + 26, 'sig', 'faint', { align: 'center' });
      const G = P.ranges;
      f.ranges.forEach((r, i) => rangeBar(c, G.x, G.y + i * G.step, G.w, r.scale, r.span, r.mark, r));
    });
  }

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
      fill(c, Bk.x, Bk.y, Bk.w, Bk.h, 'tileOk', 8); edge(c, Bk.x, Bk.y, Bk.w, Bk.h, 'blueEdge', 8);
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
      const C = L.climateScheme, T = L.topology.tile;
      const zone = (z, x) => {
        fill(c, x, C.zoneY, C.zoneW, C.zoneH, z.inside ? 'tileOk' : 'plate', 10);
        edge(c, x, C.zoneY, C.zoneW, C.zoneH, z.inside ? 'blueEdge' : 'line4', 10);
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
      const cx = W / 2, K = car(c, cx, L.car.top, L.car.h), T = L.topology.tile, D = L.car.doors, G = L.safetyScheme.gap;
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
        if (s.occupied) fill(c, gx, gy, Z.w, Z.h, 'blueShare', Z.r);
        edge(c, gx, gy, Z.w, Z.h, s.occupied ? 'blueEdge' : 'line3', Z.r);
        const strap = `M${gx + Z.w - Z.strapIn} ${gy + Z.strapTop}L${gx + Z.strapIn} ${gy + Z.h - Z.strapFoot}`;
        if (s.belt === true) stroke(c, strap, 'blue', 2);
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
      const N = L.lvScheme, T = L.topology.tile, y = N.busY;
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
      if (f.norm) fill(c, x0, yv(f.norm[1]), x1 - x0, yv(f.norm[0]) - yv(f.norm[1]), 'blueShare');
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
        fill(c, x, T.top, tw, T.h, old ? 'tileUnread' : 'tileOk', 6); edge(c, x, T.top, tw, T.h, ext ? 'white' : old ? 'line3' : 'blueEdge', 6);
        text(c, 'Д' + (i + 1), x + T.padX, T.top + T.padY, 'counterLabel', 'mute');
        text(c, v == null ? '—' : String(v), x + T.padX, T.top + T.padY + T.valueY, 'counter', old || v == null ? 'faint' : 'white');
        text(c, '°C', x + tw - T.padX, T.top + T.padY + T.unitY, 'unit', old ? 'faint' : 'dim2', { align: 'right' });
      });
    });
  }

  const TILE = {
    ok:     { fill: 'tileOk', line: 'blueEdge', ink: 'white', sub: 'dim', state: 'blueSoft', addr: 'faint' },
    hist:   { fill: 'tileHist', line: 'sep', ink: 'white', sub: 'dim', state: 'ink4', addr: 'faint' },
    unread: { fill: 'tileUnread', line: 'line3', ink: 'ink2', sub: 'dim', state: 'dim2', addr: 'faint' },
    none:   { dash: 'line4', ink: 'faint', sub: 'faint2', state: 'faint2', addr: 'faint2' },
  };
  // The rail's autoscan: the same nine columns, narrower tiles at the width the rail leaves.
  function sysTopology(c, f) {
    let kinds;
    shell(c, f, () => { blockHead(c, f.block); tabs(c, f.tabs); kinds = topologyBody(c, f, Object.assign({}, L.topology, L.rail.topology, { tile: Object.assign({}, L.topology.tile, L.rail.topology.tile) })); }, () => kinds);
  }

  // The bus and the tiles in a layout; returns the tile kinds drawn, for the legend.
  function topologyBody(c, f, P) {
    const G = {};
    f.groups.forEach(g => { G[g.id] = g; });
    const Ti = P.tile;
    // the trunk runs from the head unit to the last column that opens a group; a column that
    // continues a group hangs from the one before it, under that group's caption
    const opens = P.columns.map(parts => !parts[0][3]);
    const lastOpen = opens.lastIndexOf(true);
    let bus = `M${P.head.x + P.head.w} ${P.trunkY}H${P.left + lastOpen * P.column + P.busInset}`, live = '', dead = '';
    const labels = [], tiles = [];
    P.columns.forEach((parts, k) => {
      const x = P.left + k * P.column, bx = x + P.busInset;
      let y = P.firstLabel, last = 0;
      parts.forEach(([id, from, to, cont], gi) => {
        const g = G[id], ecus = g.ecus.slice(from || 0, to == null ? g.ecus.length : to);
        if (gi) y += P.groupGap;
        if (!cont) labels.push([x + Ti.inset, y, g.topo + ' · ' + g.ecus.length]);
        y += P.labelH;
        ecus.forEach(e => {
          tiles.push([x + Ti.inset, y, e]);
          const m = y + Ti.h / 2, stub = `M${bx} ${m}H${x + Ti.inset}`;
          if (e.kind === 'none') dead += stub; else live += stub;
          last = m;
          y += Ti.pitch;
        });
      });
      bus += opens[k] ? `M${bx} ${k ? P.trunkY : P.trunkFirstY}V${last}` : `M${bx - P.column} ${P.tailY}H${bx}V${last}`;
    });
    stroke(c, bus, 'bus', 1.5);
    stroke(c, live, 'busLive', 1.5);
    stroke(c, dead, 'line4', 1.5, [3, 3]);
    const Hb = P.head;
    fill(c, Hb.x, Hb.y, Hb.w, Hb.h, 'pidFill', Hb.r); edge(c, Hb.x, Hb.y, Hb.w, Hb.h, 'line4', Hb.r);
    text(c, f.headUnit[0], Hb.x + Hb.pad, mid(Hb.y, Hb.h, 'headUnit'), 'headUnit', 'white');
    text(c, f.headUnit[1], Hb.x + Hb.w - Hb.pad, mid(Hb.y, Hb.h, 'headUnitHops'), 'headUnitHops', 'faint', { align: 'right' });
    labels.forEach(([x, y, t]) => text(c, t, x, y, 'topoLabel', 'dim2'));
    // a tile: its abbreviation and address over its short name; its state is its look, and a block
    // with history says how many codes in place of the address
    tiles.forEach(([x, y, e]) => unitTile(c, x, y, e, Ti));
    return new Set(tiles.map(([, , e]) => e.kind));
  }

  // A block as the topology draws it: abbreviation and address over the short name, its state its
  // look, «ИСТ n» in place of the address where it has history.
  function unitTile(c, x, y, e, tile) {
    const Ti = tile || L.topology.tile, k = TILE[e.kind];
    if (k.dash) edge(c, x, y, Ti.w, Ti.h, k.dash, Ti.r, [3, 3]);
    else { fill(c, x, y, Ti.w, Ti.h, k.fill, Ti.r); edge(c, x, y, Ti.w, Ti.h, k.line, Ti.r); }
    const ix = x + 1 + Ti.padX, iw = Ti.w - 2 - 2 * Ti.padX;
    const top = y + (Ti.h - style('tileAbbr').lh - Ti.rowGap - style('tileName').lh) / 2;
    const right = e.kind === 'hist' ? ['ИСТ ' + e.history, 'tileState', k.state] : [e.req || 'нет адреса', 'tileAddr', k.addr];
    const rw = text(c, right[0], ix + iw, top + (style('tileAbbr').lh - style(right[1]).lh) / 2, right[1], right[2], { align: 'right' });
    text(c, e.abbr, ix, top, 'tileAbbr', k.ink, { max: iw - rw - 6 });
    text(c, e.short, ix, top + style('tileAbbr').lh + Ti.rowGap, 'tileName', k.sub, { max: iw });
  }

  // The status bits of a DTC, bit 0 first, as ISO 14229-1 names them.
  const BITS = ['TF', 'TFTOC', 'PDTC', 'CDTC', 'TNCSLC', 'TFSLC', 'TNCTOC', 'WIR'];
  const hex2 = v => '0x' + v.toString(16).toUpperCase().padStart(2, '0');

  function bodyDtc(c, f) {
    header(c, f.top);
    blockHead(c, f.block);
    tabs(c, f.tabs);
    const B = L.bodyDtc, cols = columns(B.cols, 24, W - 48);
    let y = headRow(c, cols, ['КОД', 'ТИП ОТКАЗА (FTB)', 'ОПИСАНИЕ', 'СТАТУС'], B.top, B.head, 'colHeadTight');
    BITS.forEach((b, i) => { const [x, w] = cols[4 + i]; text(c, b, x + w / 2, mid(B.top, B.head, 'bitHead'), 'bitHead', 'mute', { align: 'center' }); });
    for (const d of f.codes) {
      const h = B.rowMin;
      text(c, d.code, cols[0][0] + 12, mid(y, h, 'code'), 'code', 'white');
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
      text(c, hex2(d.status), cols[3][0], mid(y, h, 'cellNum'), 'cellNum', 'text');
      BITS.forEach((_, i) => {
        const [x, w] = cols[4 + i], on = (d.status >> i) & 1, s = B.bit;
        const draw = () => fill(c, x + (w - s) / 2, y + (h - s) / 2, s, s, on ? 'blue' : 'bitOff', B.bitR);
        if (on) glow(c, 6, 'rgba(67,159,252,0.8)', draw); else draw();
      });
      rule(c, 24, y + h, W - 48, 'hair');
      y += h + 1;
    }
    // the explanation and the legend stand under the last code, never under a fixed line: a block
    // with eight codes pushed them over its rows (on the car 2026-10-20); the app scrolls the rest
    const E = f.explain, xw = B.explainW, below = Math.max(B.below, y + B.belowGap);
    let ey = below;
    text(c, E.head, 24, ey, 'counterLabel', 'mute');
    ey += style('counterLabel').lh + B.explainGap;
    ey += para(c, E.lead, 24, ey, 'lead', 'white', xw) + B.explainGap;
    ey += para(c, E.body, 24, ey, 'body', 'dim', xw) + B.explainGap;
    if (E.aside) para(c, E.aside, 24, ey, 'small', 'faint', xw);
    const G = B.legend;
    f.bits.forEach((ru, i) => {
      const ly = below + i * (style('legend').lh + G.rowGap);
      text(c, BITS[i], G.x, ly, 'legendKey', 'dim');
      text(c, 'бит ' + i + ' · ' + ru, G.x + G.key + G.gap, ly, 'legend', 'mute');
    });
    footer(c, f.footer);
  }

  const MONITOR = { ready: ['ГОТОВ', 'ready'], waiting: ['НЕ ГОТОВ', 'waiting'], absent: ['НЕ ПОДДЕРЖ.', 'absent'] };
  // X: where the two halves stand - the block screen's (L.ecuDtc) or the rail's (L.rail.ecuDtc).
  function monitors(c, list, X) {
    const D = L.ecuDtc, R = (X || D).right, cols = columns((X || D).monCols, R.x, R.w);
    let y = headRow(c, cols, ['ГОТОВНОСТЬ МОНИТОРОВ · С МОМЕНТА СБРОСА DTC', 'ТИП', 'СТАТУС'], D.top, D.head);
    for (const [name, abbr, type, state] of list) {
      const h = D.monRow, off = state === 'absent';
      runs(c, [[name + ' ', 'cell', off ? 'faint2' : 'white'], [abbr, 'cellAbbr', 'faint']], cols[0][0] + 12, base(mid(y, h, 'cell'), 'cell'), cols[0][1] - 12);
      text(c, type, cols[1][0], mid(y, h, 'unit'), 'unit', off ? 'sep' : 'dim2');
      const [label, tone] = MONITOR[state];
      badge(c, label, cols[2][0], y + (h - L.badge.h) / 2, tone);
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
      const h = D.listRow, num = /^\d+$/.test(count);
      text(c, name, cols[0][0] + 12, mid(y, h, 'cell'), 'cell', 'white', { max: cols[0][1] - 12 });
      text(c, service, cols[1][0], mid(y, h, 'cellMono'), 'cellMono', 'dim');
      const sn = num ? 'cellNum' : 'normMono';
      text(c, count, cols[2][0] + cols[2][1] - 12, mid(y, h, sn), sn, num ? 'white' : 'faint', { align: 'right' });
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
    fill(c, Lf.x, y, Lf.w, h, 'orangeRow');
    text(c, d.code, cols[0][0] + 12, mid(y, h, 'codeFault'), 'codeFault', 'orangeInk');
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
      text(c, count, x + (i ? 1 : 0) + px, ty2, 'listCount', count === '0' ? 'dim2' : 'orangeInk'); ty2 += style('listCount').lh + Fa.listGapIn;
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
  function liveTable(c, f, colSpec, first) {
    const E = L.ecuLive, cols = columns(colSpec, 24, W - 48);
    let y = headRow(c, cols, [first || 'PID', 'ПАРАМЕТР', ['ЗНАЧЕНИЕ', 'right', 12], 'ЕД.', ['МИН', 'right', 12], ['МАКС', 'right', 12], 'НОРМА', 'ГРАФИК · 60 С'], E.top, E.head);
    for (const r of f.rows) {
      const h = E.row, rx = i => cols[i][0] + cols[i][1] - 12;
      text(c, r.pid, cols[0][0] + 12, mid(y, h, 'normMono'), 'normMono', 'faint');
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
        if (v.shared) fill(c, x, y, w, h, 'blueShare');
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
    // the mask plate: which status bits the mask sees, what came back
    const P = Bk.plate, iw = Lf.w - 2 * P.padX, top = y + Bk.plateGap;
    const bodyLines = wrap(c, f.mask.result, 'bodyStrong', iw);
    const ph = 2 * P.padY + style('counterLabel').lh + P.gap + Bk.bits.h + P.gap + bodyLines.length * style('bodyStrong').lh;
    fill(c, Lf.x, top, Lf.w, ph, 'plate', P.r); edge(c, Lf.x, top, Lf.w, ph, 'rule', P.r);
    let py = top + P.padY;
    const px = Lf.x + P.padX;
    text(c, f.mask.label, px, py, 'counterLabel', 'mute'); py += style('counterLabel').lh + P.gap;
    const bc = columns(BITS.map(() => '1fr'), px, iw, Bk.bits.gap);
    BITS.forEach((b, i) => {
      const [bx, bw] = bc[i], on = (f.mask.value >> i) & 1;
      fill(c, bx, py, bw, Bk.bits.h, on ? 'blueTint' : 'pidFill', Bk.bits.r);
      if (on) edge(c, bx, py, bw, Bk.bits.h, 'blueEdge', Bk.bits.r);
      text(c, b, bx + bw / 2, mid(py, Bk.bits.h, 'bit'), 'bit', on ? 'blueInk' : 'faint2', { align: 'center' });
    });
    py += Bk.bits.h + P.gap;
    bodyLines.forEach(l => { text(c, l, px, py, 'bodyStrong', 'white'); py += style('bodyStrong').lh; });
  }

  const HV_UNIT = {
    ok:     { fill: 'tileOk', line: 'blueEdge', ink: 'white', sub: 'dim', state: 'blueSoft' },
    unread: { fill: 'tileUnread', line: 'line3', ink: 'ink2', sub: 'dim', state: 'dim2' },
    none:   { fill: 'ground', dash: 'line4', ink: 'dim', sub: 'mute', state: 'faint' },
  };
  // The HV schema at the rail's width, without the machines' captions (their readings are
  // «Электропривод»'s).
  function hvBody(c, f, V) {
    const K = V.contactor, B = V.bus;
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
      stroke(c, (() => { const p = new Path2D(); p.arc(m.x, m.y, V.machineR, 0, 2 * Math.PI); return p; })(), m.live ? 'wire5' : 'wire4', 1.6, m.live ? null : [4, 3]);
      const s = style('glyph');
      glyphs(c, m.glyph, m.x - measure(c, m.glyph, s) / 2, m.y + 5, s, m.live ? 'text' : 'dim');
    });
    stroke(c, B.feeds, 'wire3', 1.6, [5, 4]);
    const kh = para(c, f.contactor, K.label[0], K.label[1], 'hvLabel', 'mute', K.label[2]);
    if (f.contactorState) text(c, f.contactorState, K.label[0], K.label[1] + kh, 'hvLabel', 'text');
    // the charge port under the charger, as the contactor's label: its word blue while a plug is in
    if (f.charge) {
      const [x, y, w] = V.charge, ch = para(c, 'зарядный разъём', x, y, 'hvLabel', 'mute', w);
      text(c, f.charge.state, x, y + ch, 'hvLabel', f.charge.plugged ? 'blueSoft' : 'text');
    }
    text(c, f.busLabel, V.busLabel.right, V.busLabel.y, 'colHead', 'mute', { align: 'right' });
    f.volts.forEach((v, i) => text(c, v, V.volts[i][0], V.volts[i][1], 'hvVolts', 'text'));
    // the pack
    const P = V.pack, p = f.pack;
    fill(c, P.x, P.y, P.w, P.h, 'tileOk', P.r); edge(c, P.x, P.y, P.w, P.h, 'blueEdge', P.r);
    const ix = P.x + P.padX, iw = P.w - 2 * P.padX;
    let y = P.y + P.padY;
    let by = y + Math.max(base(0, 'packTitle'), base(0, 'packTag'));
    glyphs(c, p.title, ix, by, style('packTitle'), 'white');
    glyphs(c, p.tag, ix + iw - measure(c, p.tag, 'packTag'), by, style('packTag'), 'faint');
    y += style('packTitle').lh + P.gap;
    text(c, p.state, ix, y, 'packState', 'blueSoft');
    y += style('packState').lh + P.gap;
    p.rows.forEach(([k, v]) => {
      const bb = y + Math.max(base(0, 'packKey'), base(0, 'packValue'));
      const vw = measure(c, v, 'packValue');
      glyphs(c, fit(c, k, style('packKey'), iw - vw - 10), ix, bb, style('packKey'), 'dim2');
      glyphs(c, v, ix + iw - vw, bb, style('packValue'), 'white');
      y += style('packValue').lh + P.gap;
    });
    // the units on the bus and beside the pack
    const U = V.unit;
    // a unit's place, and its width where it is not the common one (the inverters on the bus, wide
    // enough for «Инвертор задний правый»)
    f.units.forEach(u => {
      const [x, uy, w0] = V.units[u.abbr], k = HV_UNIT[u.kind], w = w0 || U.w;
      fill(c, x, uy, w, U.h, k.fill, U.r);
      if (k.dash) edge(c, x, uy, w, U.h, k.dash, U.r, [3, 3]); else edge(c, x, uy, w, U.h, k.line, U.r);
      const ux = x + 1 + U.padX, uw = w - 2 - 2 * U.padX;
      const top = uy + (U.h - style('hvAbbr').lh - style('hvName').lh - style('hvState').lh - 2 * U.rowGap) / 2;
      text(c, u.abbr, ux, top, 'hvAbbr', k.ink);
      text(c, u.addr, ux + uw, top, 'tileAddr', 'faint', { align: 'right' });
      text(c, u.name, ux, top + style('hvAbbr').lh + U.rowGap, 'hvName', k.sub, { max: uw });
      text(c, u.state, ux, top + style('hvAbbr').lh + style('hvName').lh + 2 * U.rowGap, 'hvState', k.state, { max: uw });
    });
  }

  const SCREENS = { sysParam, sysPackCells, sysScan, sysTopology, sysLive, sysDtc, sysBlocks, sysPending, sysStream, sysDrivesCompare, sysAdasCompare, sysEngineDtc, sysChassis, sysBodyState, sysAdas, sysDrives, sysSafety, sysHv, sysPack, sysEngineScheme, sysClimate, sysLv, sysCabin, sysGbFlags, bodyDtc, ecuDtc, ecuFault, ecuLive, ecuIdent, blockMask };
  function draw(c, board, fixture) {
    c.save();
    c.fillStyle = col('ground'); c.fillRect(0, 0, W, H);
    SCREENS[board.screen](c, fixture);
    c.restore();
  }
  root.ScannerBoard = { draw, screens: Object.keys(SCREENS) };
})(window);
