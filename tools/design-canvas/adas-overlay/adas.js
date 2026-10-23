/*
 * ADAS status overlay - phase 1 mockups (design only, no product code).
 *
 * The overlay lives inside our Navigator build and is drawn on a Canvas over Navigator's map, so
 * the boards are drawn on a canvas too, in Luminofor's verbs (beam, glowFill, lit words, the wide
 * drawn figures) with Luminofor's colours read from ../luminofor/spec.json. Sizes are the main
 * display's pixels (2560 x 1600, density 2.0).
 *
 * One state object describes what the overlay knows:
 *   noa, icc     'active' | 'available' | 'off'          (service 13: 1 / 2 / 0)
 *   mode         'city' | 'highway' | null                (service 13 0x8005: 2 / 1)
 *   route        null | 'waiting' | 'selected' | 'matched' | 'rejected'
 *                (service 44: our pathId sent / MMTPathSelected 200 / and PathMatch answered)
 *   stale        null | seconds since the last ADAS answer (never drawn as active)
 *   alarm        null | 'stock-empty' (the stock map feeds pathId -1 / road class -1 / status 100
 *                while our route is active) | 'yandex-silent' (our bridge stopped sending)
 *   takeover     null | text (a TOR such as 1302)
 *   expanded     the details line: who feeds ADAS and how old each side is
 *   details      [[label, value, tone], ...] for the details line
 *   narrow       the one-third pane: the secondary chip folds away
 *
 * Exposes window.AdasOverlay = { draw(ctx, state, anchor), oldOverlay(ctx, lines) }.
 */
(function (root) {
  'use strict';
  const S = root.LUMINOFOR_SPEC;
  const ROBOTO = 'Roboto, sans-serif';
  const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
  const pair = p => [hex(p[0]), hex(p[1])];
  const CL = S.colors.cluster, HD = S.colors.head;
  const BLUE = pair(HD.blue), WHITE = pair(HD.white), GREY = pair(CL.grey);
  const ORANGE = pair(CL.orange), RED = pair(CL.red), INK = pair(CL.ink);
  // alarm words keep their hue: the cluster's light cores read yellow and pink at this size
  const ORANGE_W = [hex(CL.orange[0]), hex('#FFA42E')], RED_W = [hex(CL.red[0]), hex('#FF5A5F')];
  const rgba = (c, a) => 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + Math.max(0, Math.min(1, a)).toFixed(3) + ')';

  // The overlay's own numbers (px on the 2560 display).
  const K = {
    h: 136,            // the row: as tall as Navigator's route panel beside it
    pad: 30,           // plate's inner side padding
    radius: 30,
    gap: 16,           // between stacked plates
    glyph: 80,         // the pilot's glyph box
    big: 68,           // the main word: cap ~48 px
    mid: 40,           // secondary words: cap ~28 px
    small: 34,         // the one details line
    sep: 26,           // padding either side of a separator
    detailsH: 62,
    alarmH: 168,
  };

  /* ------------------------------------------------------------ primitives */
  function beam(ctx, path, sw, col, I, g, dash) {
    if (I <= 0.01) return;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    if (dash) ctx.setLineDash(dash);
    if (g > 0) {
      ctx.lineWidth = sw * 4.5; ctx.strokeStyle = rgba(col[0], 0.04 * I * g); ctx.stroke(path);
      ctx.lineWidth = sw * 1.8; ctx.strokeStyle = rgba(col[0], 0.18 * I * g);
      ctx.shadowColor = rgba(col[0], 0.8 * I * g); ctx.shadowBlur = Math.min(34, sw * 3.2); ctx.stroke(path);
      ctx.shadowBlur = 0;
    }
    ctx.lineWidth = sw; ctx.strokeStyle = rgba(col[1], I); ctx.stroke(path);
    ctx.restore();
  }
  function glowFill(ctx, path, col, I, blur) {
    if (I <= 0.01) return;
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    ctx.shadowColor = rgba(col[0], 0.85 * I); ctx.shadowBlur = blur;
    ctx.fillStyle = rgba(col[0], 0.6 * I); ctx.fill(path);
    ctx.shadowBlur = 0; ctx.fillStyle = rgba(col[1], 0.9 * I); ctx.fill(path);
    ctx.restore();
  }
  function font(px, w) { return (w || 500) + ' ' + px + 'px ' + ROBOTO; }
  function tw(ctx, str, px, w) { ctx.save(); ctx.font = font(px, w); const m = ctx.measureText(str).width; ctx.restore(); return m; }
  // A word; lit words glow in their colour, calm ones are flat.
  function word(ctx, str, x, base, px, col, I, glow, w) {
    if (I <= 0.01 || !str) return 0;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.font = font(px, w);
    ctx.textBaseline = 'alphabetic';
    if (glow) {
      ctx.shadowColor = rgba(col[0], 0.9 * I * glow); ctx.shadowBlur = 26;
      ctx.fillStyle = rgba(col[0], 0.55 * I); ctx.fillText(str, x, base);
      ctx.shadowBlur = 0;
      // the core over its own halo, not added to it: a lit word keeps its hue
      ctx.globalCompositeOperation = 'source-over';
    }
    ctx.fillStyle = rgba(col[1], I); ctx.fillText(str, x, base);
    const m = ctx.measureText(str).width;
    ctx.restore();
    return m;
  }

  // The wide drawn figures of spec.json, flat, as everywhere in Luminofor.
  const D = S.digits;
  const PATHS = {};
  Object.keys(D.glyphs).forEach(k => { PATHS[k] = new Path2D(D.glyphs[k][1]); });
  function numWidth(str, size) {
    const k = size * D.capRatio / D.cap; let w = 0;
    for (let i = 0; i < str.length; i++) { const g = D.glyphs[str[i]]; if (!g) continue; w += g[0] + (i < str.length - 1 ? D.track : 0); }
    return w * k;
  }
  function num(ctx, str, x, base, size, col, I) {
    const k = size * D.capRatio / D.cap;
    const s = Math.max(D.strokeMin, size * D.strokePerSize) / k;
    let cx = x;
    for (let i = 0; i < str.length; i++) {
      const gl = D.glyphs[str[i]]; if (!gl) continue;
      if (gl[1]) {
        ctx.save(); ctx.translate(cx, base - D.cap * k); ctx.scale(k, k);
        beam(ctx, PATHS[str[i]], s, col, I, 0);
        ctx.restore();
      }
      cx += (gl[0] + D.track) * k;
    }
    return numWidth(str, size);
  }
  // A figure with its unit on the baseline: "12 с", "0,4 с".
  function figure(ctx, value, unit, x, base, size, col, I, measureOnly) {
    const w1 = numWidth(value, size), gap = size * 0.22, w2 = tw(ctx, unit, size * 0.9, 400);
    if (!measureOnly) { num(ctx, value, x, base, size, col, I); word(ctx, unit, x + w1 + gap, base, size * 0.9, col, I * 0.85, 0, 400); }
    return w1 + gap + w2;
  }

  /* --------------------------------------------------------------- glyphs */
  // The pilot: the lane's two edges in perspective and the car's chevron between them. NOA adds
  // the route bending away ahead, ICC the car it follows. Lit = filled and glowing, available =
  // drawn in outline, off = the edges alone, dim; no answer = the edges broken.
  function pilotGlyph(ctx, kind, state, x, cy, G) {
    const u = G / 88, X = v => x + v * u, Y = v => cy - G / 2 + v * u;
    const edges = new Path2D();
    edges.moveTo(X(8), Y(84)); edges.lineTo(X(31), Y(6));
    edges.moveTo(X(80), Y(84)); edges.lineTo(X(57), Y(6));
    const chev = new Path2D();
    chev.moveTo(X(44), Y(40)); chev.lineTo(X(63), Y(74)); chev.lineTo(X(44), Y(63)); chev.lineTo(X(25), Y(74)); chev.closePath();
    const extra = new Path2D();
    if (kind === 'noa') { extra.moveTo(X(44), Y(34)); extra.bezierCurveTo(X(44), Y(22), X(46), Y(15), X(54), Y(6)); }
    else { extra.roundRect(X(36), Y(10), 16 * u, 15 * u, 3 * u); }
    if (state === 'active') {
      beam(ctx, edges, 3.6, BLUE, 0.85, 0.7);
      glowFill(ctx, chev, BLUE, 1, 22);
      if (kind === 'noa') beam(ctx, extra, 4.5, BLUE, 1, 1); else glowFill(ctx, extra, BLUE, 1, 14);
    } else if (state === 'available') {
      beam(ctx, edges, 3, WHITE, 0.55, 0);
      beam(ctx, chev, 3.2, WHITE, 0.95, 0);
      beam(ctx, extra, 3, WHITE, 0.6, 0, kind === 'noa' ? [2, 9] : null);
    } else if (state === 'off') {
      beam(ctx, edges, 3, GREY, 0.4, 0);
    } else {                                  // stale
      beam(ctx, edges, 3, GREY, 0.4, 0, [9, 11]);
    }
  }
  // A state mark in front of a secondary word: lit dot, white ring, grey dash.
  function stateMark(ctx, state, x, cy, r) {
    const p = new Path2D();
    if (state === 'active') { p.arc(x + r, cy, r * 0.62, 0, Math.PI * 2); glowFill(ctx, p, BLUE, 1, 14); }
    else if (state === 'available') { p.arc(x + r, cy, r * 0.6, 0, Math.PI * 2); beam(ctx, p, 3, WHITE, 0.9, 0); }
    else if (state === 'off') { p.moveTo(x + r * 0.35, cy); p.lineTo(x + r * 1.65, cy); beam(ctx, p, 3.5, GREY, 0.55, 0); }
    else { p.arc(x + r, cy, r * 0.6, 0, Math.PI * 2); beam(ctx, p, 3, GREY, 0.4, 0, [4, 6]); }
  }
  // Our route as ADAS took it: a line from the car to the destination, then the receipt -
  // a clock while we wait, one tick for ADAS's selection of our pathId (200), two once its
  // path-match answered, a cross for a refusal.
  function routeGlyph(ctx, route, stale, x, cy) {
    const s = 60, X = v => x + v, Y = v => cy - s / 2 + v;
    const line = new Path2D(); line.moveTo(X(8), Y(52)); line.bezierCurveTo(X(8), Y(26), X(50), Y(40), X(50), Y(12));
    const start = new Path2D(); start.arc(X(8), Y(52), 5.5, 0, Math.PI * 2);
    const end = new Path2D(); end.arc(X(50), Y(10), 7.5, 0, Math.PI * 2);
    const col = stale ? GREY : route === 'matched' ? BLUE : route === 'rejected' ? ORANGE : WHITE;
    const I = stale ? 0.4 : 1;
    const g = !stale && route === 'matched' ? 1 : 0;
    beam(ctx, line, 4, col, I * (route === 'waiting' ? 0.75 : 1), g, route === 'waiting' ? [3, 11] : null);
    beam(ctx, start, 3, col, I * 0.9, 0);
    if (route === 'waiting') beam(ctx, end, 3, col, I * 0.8, 0); else glowFill(ctx, end, col, I, g ? 16 : 0.01);
    // the receipt
    const rx = x + s + 12, ry = cy;
    const t = new Path2D();
    if (route === 'waiting') {
      t.arc(rx + 20, ry, 16, 0, Math.PI * 2); t.moveTo(rx + 20, ry - 9); t.lineTo(rx + 20, ry); t.lineTo(rx + 27, ry + 5);
      beam(ctx, t, 3.4, col, I * 0.85, 0);
      return s + 12 + 40;
    }
    if (route === 'rejected') {
      t.moveTo(rx + 6, ry - 14); t.lineTo(rx + 34, ry + 14); t.moveTo(rx + 34, ry - 14); t.lineTo(rx + 6, ry + 14);
      beam(ctx, t, 5, col, I, 1);
      return s + 12 + 40;
    }
    t.moveTo(rx + 2, ry + 1); t.lineTo(rx + 13, ry + 13); t.lineTo(rx + 36, ry - 14);
    if (route === 'matched') { t.moveTo(rx + 24, ry + 11); t.lineTo(rx + 26, ry + 13); t.lineTo(rx + 49, ry - 14); }
    beam(ctx, t, 5, col, I, g);
    return s + 12 + (route === 'matched' ? 50 : 38);
  }
  function triangle(ctx, x, cy, G, col) {
    const u = G / 80, X = v => x + v * u, Y = v => cy - G / 2 + v * u;
    const t = new Path2D(); t.moveTo(X(40), Y(6)); t.lineTo(X(77), Y(72)); t.lineTo(X(3), Y(72)); t.closePath();
    const bang = new Path2D(); bang.moveTo(X(40), Y(30)); bang.lineTo(X(40), Y(50));
    const dot = new Path2D(); dot.arc(X(40), Y(61), 3.6 * u, 0, Math.PI * 2);
    beam(ctx, t, 5, col, 1, 1); beam(ctx, bang, 6, col, 1, 0.6); glowFill(ctx, dot, col, 1, 8);
  }
  function wheel(ctx, x, cy, G, col) {
    const u = G / 88, cx = x + 44 * u;
    const rim = new Path2D(); rim.arc(cx, cy, 36 * u, 0, Math.PI * 2);
    const hub = new Path2D(); hub.arc(cx, cy, 9 * u, 0, Math.PI * 2);
    const sp = new Path2D();
    sp.moveTo(cx - 35 * u, cy + 2 * u); sp.lineTo(cx - 9 * u, cy + 2 * u);
    sp.moveTo(cx + 9 * u, cy + 2 * u); sp.lineTo(cx + 35 * u, cy + 2 * u);
    sp.moveTo(cx, cy + 9 * u); sp.lineTo(cx, cy + 35 * u);
    beam(ctx, rim, 6, col, 1, 1); beam(ctx, hub, 4, col, 1, 0.5); beam(ctx, sp, 4, col, 0.9, 0.4);
    // the hands at ten and two
    [-1, 1].forEach(sd => {
      const h = new Path2D();
      ctx.save();
      const a = -Math.PI / 2 + sd * 0.95;
      h.ellipse(cx + Math.cos(a) * 36 * u, cy + Math.sin(a) * 36 * u, 12 * u, 7 * u, a + Math.PI / 2, 0, Math.PI * 2);
      glowFill(ctx, h, col, 1, 18);
      ctx.restore();
    });
  }

  /* ---------------------------------------------------------------- plates */
  function plate(ctx, x, y, w, h, edge, edgeA, glow) {
    ctx.save();
    ctx.globalCompositeOperation = 'source-over';
    const p = new Path2D(); p.roundRect(x, y, w, h, K.radius);
    ctx.shadowColor = 'rgba(0,0,0,0.45)'; ctx.shadowBlur = 28; ctx.shadowOffsetY = 6;
    ctx.fillStyle = 'rgba(7,8,11,0.95)'; ctx.fill(p);
    ctx.shadowColor = 'transparent'; ctx.shadowBlur = 0; ctx.shadowOffsetY = 0;
    ctx.restore();
    const e = new Path2D(); e.roundRect(x + 1, y + 1, w - 2, h - 2, K.radius - 1);
    if (edge) beam(ctx, e, 2.4, edge, edgeA, glow || 0);
    else { ctx.save(); ctx.lineWidth = 2; ctx.strokeStyle = 'rgba(255,255,255,0.10)'; ctx.stroke(e); ctx.restore(); }
  }
  function haze(ctx, x, cy, r, col, a) {
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    const g = ctx.createRadialGradient(x, cy, 0, x, cy, r);
    g.addColorStop(0, rgba(col[0], a)); g.addColorStop(1, rgba(col[0], 0));
    ctx.fillStyle = g; ctx.fillRect(x - r, cy - r, 2 * r, 2 * r);
    ctx.restore();
  }

  /* ------------------------------------------------------------ the row */
  // The row's elements, measured then drawn: the pilot, the other function, the route.
  function rowElements(ctx, st) {
    const stale = st.stale != null;
    const primary = st.noa !== 'active' && st.icc === 'active' ? 'icc' : 'noa';
    const other = primary === 'noa' ? 'icc' : 'noa';
    const pState = stale ? 'stale' : st[primary];
    const els = [];
    // the pilot
    {
      const name = primary === 'noa' ? 'NOA' : 'ICC';
      const sub = stale ? null : primary === 'noa' && st.noa !== 'off' && st.mode ? (st.mode === 'city' ? 'город' : 'трасса') : null;
      const wName = tw(ctx, name, K.big, 500);
      const wSub = stale ? figure(ctx, String(st.stale), 'с', 0, 0, K.mid, WHITE, 1, true) : sub ? tw(ctx, sub, K.mid, 400) : 0;
      const w = K.glyph + 20 + wName + (wSub ? 18 + wSub : 0);
      els.push({ w, draw(x, cy) {
        pilotGlyph(ctx, primary, pState, x, cy, K.glyph);
        const bx = x + K.glyph + 20, base = cy + 24;
        const col = pState === 'active' ? BLUE : pState === 'available' ? WHITE : GREY;
        const I = pState === 'active' || pState === 'available' ? 1 : pState === 'off' ? 0.6 : 0.42;
        word(ctx, name, bx, base, K.big, col, I, pState === 'active' ? 1 : 0, 500);
        if (stale) figure(ctx, String(st.stale), 'с', bx + wName + 18, base, K.mid, WHITE, 0.95);
        else if (sub) word(ctx, sub, bx + wName + 18, base, K.mid, pState === 'active' ? BLUE : WHITE, pState === 'active' ? 0.95 : 0.8, 0, 400);
      } });
    }
    // the other function, folded away in the narrow pane
    if (!st.narrow) {
      const name = other === 'noa' ? 'NOA' : 'ICC';
      const s2 = stale ? 'stale' : st[other];
      const w = 28 + 12 + tw(ctx, name, K.mid, 500);
      els.push({ sep: true });
      els.push({ w, draw(x, cy) {
        stateMark(ctx, s2, x, cy, 14);
        const col = s2 === 'active' ? BLUE : s2 === 'available' ? WHITE : GREY;
        word(ctx, name, x + 40, cy + 14, K.mid, col, s2 === 'active' ? 1 : s2 === 'available' ? 0.9 : s2 === 'off' ? 0.6 : 0.42, s2 === 'active' ? 0.7 : 0, 500);
      } });
    }
    // our route as ADAS took it; nothing without a route
    if (st.route) {
      const w = 60 + 12 + (st.route === 'matched' ? 50 : st.route === 'selected' ? 38 : 40);
      els.push({ sep: true });
      els.push({ w, draw(x, cy) { routeGlyph(ctx, st.route, stale, x, cy); } });
    }
    return els;
  }
  function rowWidth(els) {
    let w = 2 * K.pad;
    els.forEach(e => { w += e.sep ? 2 * K.sep + 2 : e.w; });
    return w;
  }
  function drawRow(ctx, els, x, cy) {
    let cx = x + K.pad;
    els.forEach(e => {
      if (e.sep) {
        ctx.save(); ctx.fillStyle = 'rgba(255,255,255,0.12)'; ctx.fillRect(cx + K.sep, cy - 34, 2, 68); ctx.restore();
        cx += 2 * K.sep + 2;
      } else { e.draw(cx, cy); cx += e.w; }
    });
  }
  // The details line: who feeds ADAS and how old each side is. Figures drawn, words small.
  function detailsWidth(ctx, items) {
    let w = 0;
    items.forEach((it, i) => {
      w += tw(ctx, it[0], K.small, 400) + 12 + figure(ctx, it[1], 'с', 0, 0, K.small, WHITE, 1, true);
      if (i < items.length - 1) w += 44;
    });
    return w;
  }
  function drawDetails(ctx, items, x, base) {
    let cx = x;
    items.forEach((it, i) => {
      const tone = it[2] === 'warn' ? ORANGE_W : WHITE;
      cx += word(ctx, it[0], cx, base, K.small, tone, it[2] === 'warn' ? 1 : 0.62, 0, 400) + 12;
      cx += figure(ctx, it[1], 'с', cx, base, K.small, tone, it[2] === 'warn' ? 1 : 0.9);
      if (i < items.length - 1) {
        const d = new Path2D(); d.arc(cx + 22, base - 11, 2.6, 0, Math.PI * 2);
        ctx.save(); ctx.fillStyle = 'rgba(255,255,255,0.35)'; ctx.fill(d); ctx.restore();
        cx += 44;
      }
    });
  }

  /* ------------------------------------------------------- stacked plates */
  function alarmPlate(ctx, st) {
    const stock = st.alarm === 'stock-empty';
    const big = stock ? 'Штатная карта' : 'Яндекс молчит';
    const small = stock ? 'шлёт пустой маршрут' : 'ADAS без нашего маршрута';
    const w = 2 * K.pad + 80 + 26 + Math.max(tw(ctx, big, K.big, 500), tw(ctx, small, K.mid, 400));
    return { w, h: K.alarmH, draw(x, y) {
      plate(ctx, x, y, w, K.alarmH, ORANGE, 0.55, 0.6);
      haze(ctx, x + K.pad + 40, y + K.alarmH / 2, 120, ORANGE, 0.16);
      triangle(ctx, x + K.pad, y + K.alarmH / 2, 80, ORANGE);
      const tx = x + K.pad + 80 + 26;
      word(ctx, big, tx, y + 34 + 48, K.big, ORANGE_W, 1, 0.8, 500);
      word(ctx, small, tx, y + 34 + 48 + 22 + 30, K.mid, WHITE, 0.88, 0, 400);
    } };
  }
  function takeoverPlate(ctx, st) {
    const t = st.takeover;
    const w = 2 * K.pad + 88 + 24 + tw(ctx, t, K.big, 500);
    return { w, h: K.h + 16, draw(x, y) {
      const h = K.h + 16;
      plate(ctx, x, y, w, h, RED, 0.85, 1);
      haze(ctx, x + K.pad + 44, y + h / 2, 150, RED, 0.22);
      wheel(ctx, x + K.pad, y + h / 2, 88, RED);
      word(ctx, t, x + K.pad + 88 + 24, y + h / 2 + 24, K.big, RED_W, 1, 1, 500);
    } };
  }

  // Draw the whole overlay at an anchor: {right, bottom} stacks upward and right-aligned (the
  // default, bottom right), {left, top} stacks downward and left-aligned (under the turn card).
  function draw(ctx, st, anchor) {
    const els = rowElements(ctx, st);
    let w = rowWidth(els);
    let h = K.h;
    const details = st.expanded && st.details ? st.details : null;
    if (details) { w = Math.max(w, 2 * K.pad + detailsWidth(ctx, details)); h += K.detailsH; }
    const capsule = { w, h, draw(x, y) {
      plate(ctx, x, y, w, h, st.stale != null ? null : null, 0);
      drawRow(ctx, els, x, y + K.h / 2);
      if (details) {
        ctx.save(); ctx.fillStyle = 'rgba(255,255,255,0.10)'; ctx.fillRect(x + K.pad, y + K.h - 2, w - 2 * K.pad, 2); ctx.restore();
        drawDetails(ctx, details, x + K.pad, y + K.h + 42);
      }
    } };
    const stack = [capsule];
    if (st.alarm) stack.push(alarmPlate(ctx, st));
    if (st.takeover) stack.push(takeoverPlate(ctx, st));
    if (anchor.right != null) {
      let y = anchor.bottom;
      stack.forEach(p => { y -= p.h; p.draw(anchor.right - p.w, y); y -= K.gap; });
    } else {
      let y = anchor.top;
      stack.forEach(p => { p.draw(anchor.left, y); y += p.h + K.gap; });
    }
  }

  // The probe's overlay as it is today (build-06): TYPE_APPLICATION_OVERLAY top right of the
  // screen, 720 px wide, 40 px type, five lines, black at about half.
  function oldOverlay(ctx, lines) {
    ctx.save();
    ctx.globalCompositeOperation = 'source-over';
    ctx.fillStyle = 'rgba(0,0,0,0.55)'; ctx.fillRect(1820, 162, 720, 306);
    ctx.font = '400 40px ' + ROBOTO; ctx.fillStyle = '#FFFFFF'; ctx.textBaseline = 'alphabetic';
    lines.forEach((l, i) => ctx.fillText(l, 1844, 224 + 53 * i));
    ctx.restore();
  }

  root.AdasOverlay = { draw, oldOverlay, word, num, figure, K, colors: { BLUE, WHITE, GREY, ORANGE, RED, INK } };
})(window);
