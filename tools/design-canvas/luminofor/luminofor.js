/*
 * Luminofor - the renderer every Luminofor board is drawn with.
 *
 * The owner approved this design as a live page (2026-09-23); this file is that page's drawing
 * code made deterministic: it draws a board from spec.json (the numbers) and a fixture (the data
 * of one scene), and nothing else. The app is held to the PNGs this renders, and the Kotlin
 * contract tests are held to spec.json.
 *
 * Compositing is additive ('lighter') throughout, exactly as the approved page drew it. On black
 * that is ordinary alpha; over a card or a haze it is not, and the app reproduces it with
 * BlendMode.PLUS on its canvases and with resolved colours on the Compose tiles.
 *
 * Exposes window.Luminofor = { drawBoard(ctx, board, fixture) }.
 */
(function (root) {
  'use strict';
  const S = root.LUMINOFOR_SPEC;
  const JURA = 'Jura, sans-serif';
  const ROBOTO = 'Roboto, sans-serif';

  /* ---------------------------------------------------------------- colour */
  const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
  const pair = p => [hex(p[0]), hex(p[1])];
  const CL = S.colors.cluster, HD = S.colors.head;
  const INK = pair(CL.ink), GREY = pair(CL.grey), BLUE = pair(CL.blue), ORNG = pair(CL.orange), RED = pair(CL.red);
  const WHT = pair(HD.white), HUB = pair(HD.blue);
  const rgba = (c, a) => 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + Math.max(0, Math.min(1, a)).toFixed(3) + ')';

  /* ------------------------------------------------------------ primitives */
  // A stroke drawn by a beam: optional halo (g > 0), then the core.
  function beam(ctx, path, sw, col, I, g) {
    if (I <= 0.01) return;
    g = g || 0;
    const sc = ctx.getTransform().a;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    if (g > 0) {
      ctx.lineWidth = sw * 4.5; ctx.strokeStyle = rgba(col[0], 0.03 * I * g); ctx.stroke(path);
      ctx.lineWidth = sw * 1.8; ctx.strokeStyle = rgba(col[0], 0.14 * I * g);
      ctx.shadowColor = rgba(col[0], 0.7 * I * g); ctx.shadowBlur = Math.min(30, sw * 2.8 * sc); ctx.stroke(path);
      ctx.shadowBlur = 0;
    }
    ctx.lineWidth = sw; ctx.strokeStyle = rgba(col[1], I); ctx.stroke(path);
    ctx.restore();
  }
  // An emissive fill: blurred halo, then the core.
  function glowFill(ctx, path, col, I, blur) {
    if (I <= 0.01) return;
    const sc = ctx.getTransform().a;
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    ctx.shadowColor = rgba(col[0], 0.8 * I); ctx.shadowBlur = Math.min(40, blur * sc);
    ctx.fillStyle = rgba(col[0], 0.55 * I); ctx.fill(path);
    ctx.shadowBlur = 0; ctx.fillStyle = rgba(col[1], 0.85 * I); ctx.fill(path);
    ctx.restore();
  }
  function font(ctx, px, o) { ctx.font = (o.w || 500) + ' ' + px + 'px ' + (o.font || JURA); }
  function text(ctx, str, x, y, px, col, I, o) {
    o = o || {};
    if (I <= 0.01 || !str) return 0;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    font(ctx, px, o);
    ctx.textBaseline = 'alphabetic';
    const track = (o.track || 0) * px;
    ctx.letterSpacing = track + 'px';
    const w = ctx.measureText(str).width - (track ? track : 0);
    let x0 = x;
    if (o.align === 'right') x0 = x - w; else if (o.align === 'center') x0 = x - w / 2;
    ctx.fillStyle = rgba(col[1], I); ctx.fillText(str, x0, y);
    ctx.restore();
    return w;
  }
  function textWidth(ctx, str, px, o) {
    o = o || {};
    ctx.save(); font(ctx, px, o);
    const track = (o.track || 0) * px;
    ctx.letterSpacing = track + 'px';
    const w = ctx.measureText(str).width - (track ? track : 0);
    ctx.restore(); return w;
  }

  /* ---------------------------------------------------------------- digits */
  const D = S.digits;
  const PATHS = {};
  Object.keys(D.glyphs).forEach(k => { PATHS[k] = new Path2D(D.glyphs[k][1]); });
  function numWidth(str, size) {
    const k = size * D.capRatio / D.cap; let w = 0;
    for (let i = 0; i < str.length; i++) { const g = D.glyphs[str[i]]; if (!g) continue; w += g[0] + (i < str.length - 1 ? D.track : 0); }
    return w * k;
  }
  // Figures are flat - a core stroke, no halo - as on the car's charging screen.
  function num(ctx, str, x, base, size, col, I, align, sw) {
    const k = size * D.capRatio / D.cap;
    const w = numWidth(str, size);
    let cx = align === 'right' ? x - w : align === 'center' ? x - w / 2 : x;
    const s = (sw || Math.max(D.strokeMin, size * D.strokePerSize)) / k;
    for (let i = 0; i < str.length; i++) {
      const gl = D.glyphs[str[i]]; if (!gl) continue;
      if (gl[1]) {
        ctx.save(); ctx.translate(cx, base - D.cap * k); ctx.scale(k, k);
        beam(ctx, PATHS[str[i]], s, col, I, 0);
        ctx.restore();
      }
      cx += (gl[0] + D.track) * k;
    }
    return w;
  }

  /* --------------------------------------------------------- temperatures */
  // The five cells: pack, front motor, rear-left motor, rear-right motor, inverter.
  const KINDS = ['pack', 'front', 'rearL', 'rearR', 'inv'];
  function glyph(c, kind, x, base, col, lit, hot) {
    const G = S.cluster.glyph;
    const out = new Path2D(), on = new Path2D();
    const blur = hot ? 6 : 0.01;
    if (kind === 'pack') {
      out.roundRect(x, base - 17, 20, 13, 2.6);
      out.moveTo(x + 22.5, base - 13); out.lineTo(x + 22.5, base - 8);
      on.roundRect(x + 3, base - 14, 9 + 5 * Math.min(1, lit), 7, 1);
      beam(c, out, G.outline, GREY, 0.9, 0); glowFill(c, on, col, lit, blur);
      return;
    }
    if (kind === 'inv') {
      out.roundRect(x, base - 22, 21, 21, 3);
      const w = new Path2D(); w.moveTo(x + 4, base - 11.5);
      for (let i = 0; i <= 26; i++) { const u = i / 26; w.lineTo(x + 4 + u * 13, base - 11.5 - Math.sin(u * Math.PI * 2) * 5); }
      beam(c, out, G.outline, GREY, 0.9, 0); beam(c, w, 1.8, col, lit, hot ? 1 : 0);
      return;
    }
    const bx = x + 4, bw = 13, top = base - 23, bh = 22;
    out.roundRect(bx, top, bw, bh, 4);
    const wh = new Path2D();
    [[bx - 3.4, top + 3], [bx + bw + 0.4, top + 3], [bx - 3.4, top + bh - 9], [bx + bw + 0.4, top + bh - 9]].forEach(([wx, wy]) => wh.roundRect(wx, wy, 3, 6, 1));
    if (kind === 'front') on.roundRect(bx + 2, top + 4.5, bw - 4, 3.2, 1);
    if (kind === 'rearL') on.roundRect(bx + 2, top + bh - 7.5, (bw - 4) / 2 - 0.5, 3.2, 1);
    if (kind === 'rearR') on.roundRect(bx + bw / 2 + 0.5, top + bh - 7.5, (bw - 4) / 2 - 0.5, 3.2, 1);
    beam(c, out, G.outline, GREY, 0.9, 0); beam(c, wh, G.wheel, GREY, 0.7, 0); glowFill(c, on, col, lit, blur);
  }
  // The fixture says which cells are out of line; the thresholds are the app's, not the board's.
  function tempColour(state) {
    if (state === 'danger') return [RED, true];
    if (state === 'warning') return [ORNG, true];
    return [null, false];
  }

  /* ================================================================ cluster */
  const CG = S.cluster.grid, CS = S.cluster.stock, CB = S.cluster.band, CT = S.cluster.trace;
  const W = S.cluster.W, H = S.cluster.H, AX = W / 2, M = S.cluster.margin;

  function keepout(c) {
    c.save();
    const p = new Path2D();
    p.rect(0, 0, W, CS.top);
    p.ellipse(0, 0, CS.leftApertureRx, CS.top, 0, 0, Math.PI * 2);
    p.ellipse(W, 0, CS.rightApertureRx, CS.top, 0, 0, Math.PI * 2);
    p.rect(0, CS.bottom, W, H - CS.bottom);
    p.ellipse(AX, CS.petalCy, CS.petalRx, CS.petalRy, 0, 0, Math.PI * 2);
    c.clip(p, 'evenodd');
    c.strokeStyle = CL.hatch; c.lineWidth = 1.2;
    c.beginPath();
    for (let x = -H; x < W + H; x += 10) { c.moveTo(x, 0); c.lineTo(x + H, H); }
    c.stroke();
    c.restore();
    c.save(); c.font = '500 12px ' + JURA; c.fillStyle = CL.hatchLabel;
    c.letterSpacing = '1.8px';
    c.textAlign = 'center'; c.fillText('ШТАТНЫЕ ПРИБОРЫ', AX, 28);
    c.textAlign = 'left'; c.fillText('ШТАТНАЯ ПОЛОСА', M, (CS.bottom + H) / 2 + 4);
    c.restore();
  }

  function reach(v) {
    const half = AX - M;
    return v >= 0 ? half * Math.sqrt(Math.min(v, CB.outKw) / CB.outKw) : -half * Math.sqrt(Math.min(-v, CB.inKw) / CB.inKw);
  }

  function filament(c, y) {
    const L = M, R = W - M;
    c.save(); c.globalCompositeOperation = 'lighter'; c.lineCap = 'round';
    const fg = a => { const g = c.createLinearGradient(L, 0, R, 0); g.addColorStop(0, rgba(INK[0], 0)); g.addColorStop(0.5, rgba(INK[0], a * 0.85)); g.addColorStop(1, rgba(INK[0], 0)); return g; };
    c.lineWidth = CB.filamentHalo[0]; c.strokeStyle = fg(CB.filamentHalo[1]); c.beginPath(); c.moveTo(L, y); c.lineTo(R, y); c.stroke();
    c.lineWidth = CB.filamentCore[0]; c.strokeStyle = fg(CB.filamentCore[1]); c.beginPath(); c.moveTo(L, y); c.lineTo(R, y); c.stroke();
    c.restore();
    const zt = CB.zeroTick;
    const zero = new Path2D(); zero.moveTo(AX, y - zt[0]); zero.lineTo(AX, y + zt[0]);
    beam(c, zero, zt[1], INK, zt[2], 1);
  }
  function skeleton(c) { filament(c, CG.axis); }

  function centre(c, f) {
    const P = f.power, absP = Math.abs(P), into = P <= -3, out = P >= 3;
    const heroCol = into ? BLUE : INK;
    const y = CG.axis, base = CG.baseline;
    // the zero's own glow: it stays at zero; brightness and colour say how hard
    const ga = CB.glow.max * Math.sqrt(Math.min(1, absP / CB.glow.fullKw));
    if (ga > 0.005) {
      const r = CB.glow.radius;
      c.save(); c.globalCompositeOperation = 'lighter';
      c.translate(AX, y); c.scale(1, (CS.bottom - y + CB.glow.reachBelow) / r);
      const g = c.createRadialGradient(0, 0, 0, 0, 0, r);
      g.addColorStop(0, rgba(heroCol[0], ga)); g.addColorStop(1, rgba(heroCol[0], 0));
      c.fillStyle = g; c.beginPath(); c.arc(0, 0, r, 0, Math.PI * 2); c.fill();
      c.restore();
    }
    // hero: three-digit field right-aligned, the field, gap and unit centred on the axis as one group
    const unitW = textWidth(c, 'кВт', CG.heroUnitSize);
    const fieldW = numWidth('000', CG.heroSize);
    const groupW = fieldW + CG.heroUnitGap + unitW;
    const fieldR = AX + groupW / 2 - CG.heroUnitGap - unitW;
    if (f.powerKnown !== false) num(c, String(Math.round(absP)), fieldR, base, CG.heroSize, heroCol, 1, 'right', D.heroStroke);
    text(c, 'кВт', fieldR + CG.heroUnitGap, base, CG.heroUnitSize, into ? BLUE : GREY, 1);
    // the axis: one filament across the glass, brightest at zero, dying toward both edges
    filament(c, y);
    const len = reach(P), beamCol = P < 0 ? BLUE : INK;
    if (Math.abs(len) > 2) {
      const T = CB.threads;
      c.save(); c.globalCompositeOperation = 'lighter'; c.lineWidth = T.width;
      const n = Math.round(T.base + Math.abs(len) * T.perUnit);
      const amp = T.ampBase + T.ampRange * Math.sqrt(Math.min(1, absP / T.ampKw));
      const t = f.t;
      for (let i = 0; i < n; i++) {
        const u = (i * 0.6180339) % 1;
        const x = AX + len * u;
        const fl = 0.55 + 0.45 * Math.sin(t * (3 + (i % 7)) + i * 1.7);
        const h = amp * (0.25 + 0.75 * ((i * 0.3819) % 1)) * fl * (0.35 + 0.65 * u);
        c.strokeStyle = rgba(beamCol[0], 0.14 + 0.36 * fl * u);
        c.beginPath(); c.moveTo(x, y - h); c.lineTo(x, y + h * 0.55); c.stroke();
      }
      c.restore();
      const b = new Path2D(); b.moveTo(AX, y); b.lineTo(AX + len, y);
      beam(c, b, CB.beamStroke, beamCol, CB.beamIntensity, 1);
      const head = new Path2D(); head.arc(AX + len, y, CB.headRadius, 0, Math.PI * 2);
      glowFill(c, head, beamCol, 1, CB.headBlur);
    }
    const pk = reach(f.peak);
    if (Math.abs(pk) > 6) {
      const tick = new Path2D(); tick.moveTo(AX + pk, y - CB.peakTick[0]); tick.lineTo(AX + pk, y + CB.peakTick[0]);
      beam(c, tick, CB.peakTick[1], f.peak < 0 ? BLUE : INK, Math.max(0.2, 0.6 - f.peakAge * 0.07), 1);
    }
  }

  function trace(c, f) {
    const zero = CT.zero, top = CT.top, drop = CT.drop;
    const bx0 = AX - CT.gapFromAxis - CT.width, bx1 = AX - CT.gapFromAxis;
    const upY = v => zero - (zero - top) * Math.min(1, v / CT.upTo);
    const dnY = v => zero + (drop - zero) * Math.min(1, -v / CT.downTo);
    // one pitch for the hundred points; a filling window is anchored at the right edge
    const ch = f.chart, n = ch.length, st = (bx1 - bx0) / CT.points, x00 = bx1 - n * st;
    if (!n) return traceFigure(c, f);
    c.save(); c.globalCompositeOperation = 'lighter';
    const fUp = new Path2D(), fDn = new Path2D();
    fUp.moveTo(x00, zero); fDn.moveTo(x00, zero);
    ch.forEach((v, i) => {
      const x0 = x00 + i * st, x1 = x0 + st;
      const yu = v > 0 ? upY(v) : zero, yd = v < 0 ? dnY(v) : zero;
      fUp.lineTo(x0, yu); fUp.lineTo(x1, yu); fDn.lineTo(x0, yd); fDn.lineTo(x1, yd);
    });
    fUp.lineTo(bx1, zero); fUp.closePath(); fDn.lineTo(bx1, zero); fDn.closePath();
    const gu = c.createLinearGradient(x00, 0, bx1, 0); gu.addColorStop(0, rgba(INK[0], 0.03)); gu.addColorStop(1, rgba(INK[0], 0.16));
    c.fillStyle = gu; c.fill(fUp);
    const gd = c.createLinearGradient(x00, 0, bx1, 0); gd.addColorStop(0, rgba(BLUE[0], 0.05)); gd.addColorStop(1, rgba(BLUE[0], 0.3));
    c.fillStyle = gd; c.fill(fDn);
    c.restore();
    // ten runs, the older ones dimmer. Spending is one white step line lying on zero through a
    // return; each return is its own blue shape under zero, and nothing blue runs along zero.
    const runs = CT.runs, per = n / runs;
    for (let r = 0; r < runs; r++) {
      const i0 = Math.floor(r * per), i1 = Math.min(n, Math.floor((r + 1) * per));
      const pu = new Path2D(), pd = new Path2D();
      for (let i = i0; i < i1; i++) {
        const v = ch[i], x0 = x00 + i * st, x1 = x0 + st, yu = upY(Math.max(0, v));
        if (i === i0) pu.moveTo(x0, i > 0 ? upY(Math.max(0, ch[i - 1])) : yu);
        pu.lineTo(x0, yu); pu.lineTo(x1, yu);
        if (v < 0) {
          const yd = dnY(v), prevNeg = i > i0 && ch[i - 1] < 0;
          if (!prevNeg) pd.moveTo(x0, zero);
          pd.lineTo(x0, yd); pd.lineTo(x1, yd);
          const nextNeg = i + 1 < i1 && ch[i + 1] < 0;
          if (!nextNeg) pd.lineTo(x1, zero);
        }
      }
      const I = 0.28 + 0.72 * Math.pow((r + 1) / runs, 1.6);
      beam(c, pu, CT.stroke, INK, I, 0); beam(c, pd, CT.stroke, BLUE, I, 0);
    }
    const last = ch[n - 1];
    const dot = new Path2D(); dot.arc(bx1, last >= 0 ? upY(last) : dnY(last), 2.6, 0, Math.PI * 2);
    glowFill(c, dot, last < 0 ? BLUE : INK, 1, 12);
    traceFigure(c, f);
  }
  function traceFigure(c, f) {
    const zero = CT.zero;
    const fx = AX + CT.gapFromAxis;
    const fw = num(c, f.consumption, fx, zero, CT.figureSize, INK, 1, 'left');
    text(c, f.consumptionUnit, fx + fw + CT.unitGap, zero, CT.unitSize, GREY, 1);
  }

  function runLeft(c, xRight, y, parts) {
    let x = xRight;
    parts.forEach(pt => {
      if (pt.gap) { x -= pt.gap; return; }
      if (pt.dot) { const d = new Path2D(); d.arc(x - 4, y - 5, 3.2, 0, Math.PI * 2); glowFill(c, d, BLUE, 0.9, 8); x -= 10; return; }
      if (pt.num) x -= num(c, pt.num, x, y, pt.size || 19, INK, 1, 'right');
      else x -= text(c, pt.text, x, y, pt.size || CG.detailSize, GREY, 1, { track: pt.track || 0, align: 'right' });
      x -= pt.after == null ? 7 : pt.after;
    });
    return x;
  }

  function drawCluster(c, f) {
    if (f.keepout !== false) keepout(c);
    if (f.unavailable) {
      // no access: the axis and its zero, and the reason in the ten kilometres' place - nothing else
      skeleton(c);
      text(c, f.message, AX, CT.zero, CT.unitSize, GREY, 1, { align: 'center' });
      return;
    }
    centre(c, f);
    const GL = M, GR = AX - CG.side, HR = AX + CG.side, RE = W - M;
    const base = CG.baseline;

    // left group: the battery; the five glyphs are the temperatures' captions
    text(c, f.batteryCaption, GL, CG.caption, CG.captionSize, GREY, 1, { track: CG.captionTrack });
    num(c, f.volts, GL, base, CG.figureSize, INK, 1, 'left');
    const tx0 = GR - numWidth('00°', CG.tempSize) - 4 * CG.tempPitch;
    f.temps.forEach((cell, i) => {
      const x = tx0 + i * CG.tempPitch;
      const [hc, hot] = tempColour(cell.state);
      const col = hot ? hc : INK;
      if (hot) {
        c.save(); c.globalCompositeOperation = 'lighter';
        const g = c.createRadialGradient(x + 24, base - 22, 0, x + 24, base - 22, 48);
        g.addColorStop(0, rgba(col[0], 0.10 + 0.04 * Math.sin(f.t * 4))); g.addColorStop(1, rgba(col[0], 0));
        c.fillStyle = g; c.fillRect(x - 30, base - 80, 110, 110); c.restore();
      }
      glyph(c, KINDS[i], x, CG.glyphBase, col, hot ? 1 : 0.85, hot);
      num(c, cell.value + '°', x, base, CG.tempSize, col, 1, 'left');
    });

    // the cell spread, only while it is out of line: one line under the battery, in its colour
    if (f.spread) {
      const [sc] = tempColour(f.spread.state);
      const y2 = base + CG.detailDrop;
      let x = GL + text(c, f.spread.caption, GL, y2, CG.detailSize, sc, 1, { track: CG.detailTrack }) + 8;
      x += num(c, f.spread.value, x, y2, 19, sc, 1, 'left') + 6;
      text(c, f.spread.unit, x, y2, CG.detailSize, sc, 1);
    }

    // right group: the engine at the group's left edge, the trip flush right
    if (f.engineGiving) {
      const E = S.cluster.engineBox;
      const bw = E.width, bx0 = RE - bw, top = base - D.capRatio * CG.figureSize, zeroY = base;
      const sy = v => zeroY - (zeroY - top) * Math.min(1, Math.max(0, v) / E.upTo);
      const step = bw / f.generation.length;
      const edge = new Path2D(), area = new Path2D();
      area.moveTo(bx0, zeroY);
      f.generation.forEach((v, i) => { const yy = sy(v); if (i === 0) edge.moveTo(bx0, yy); else edge.lineTo(bx0 + i * step, yy); edge.lineTo(bx0 + (i + 1) * step, yy); area.lineTo(bx0 + i * step, yy); area.lineTo(bx0 + (i + 1) * step, yy); });
      area.lineTo(RE, zeroY); area.closePath();
      c.save(); c.globalCompositeOperation = 'lighter';
      const g = c.createLinearGradient(0, top, 0, zeroY); g.addColorStop(0, rgba(BLUE[0], 0.2)); g.addColorStop(1, rgba(BLUE[0], 0.02));
      c.fillStyle = g; c.fill(area); c.restore();
      beam(c, edge, E.stroke, BLUE, 0.95, 0);
      const b0 = new Path2D(); b0.moveTo(bx0, zeroY); b0.lineTo(RE, zeroY); beam(c, b0, E.baseStroke, BLUE, 0.35, 0);
      text(c, f.engineCaption, bx0, CG.caption, CG.captionSize, BLUE, 1, { track: CG.cellCaptionTrack });
      text(c, f.engineWindow, bx0, base + CG.detailDrop, CG.detailSize, GREY, 1, { track: CG.cellCaptionTrack });
    } else {
      const uw = textWidth(c, f.tripUnit, CG.unitSize), cw = textWidth(c, f.tripCaption, CG.captionSize, { track: CG.cellCaptionTrack });
      const fw = numWidth(f.tripKwh, CG.figureSize);
      const x = RE - Math.max(cw, fw + CG.unitGap + uw);
      text(c, f.tripCaption, x, CG.caption, CG.captionSize, GREY, 1, { track: CG.cellCaptionTrack });
      num(c, f.tripKwh, x, base, CG.figureSize, INK, 1, 'left');
      text(c, f.tripUnit, x + fw + CG.unitGap, base, CG.unitSize, GREY, 1);
      // the trip's detail: «ДАЛ ДВС» whenever the engine gave this trip (the contract keeps that
      // seat on the move), recuperation on P; one line under the trip, in the fixture's order
      const parts = [];
      if (f.gaveKwh) parts.push({ text: f.gaveCaption, track: CG.detailTrack }, { text: f.tripUnit, after: 6 }, { num: f.gaveKwh, after: 0 });
      if (f.gaveKwh && f.regenKwh) parts.push({ gap: 28 });
      if (f.regenKwh) parts.push({ text: f.regenCaption, track: CG.detailTrack }, { text: f.tripUnit, after: 6 }, { num: f.regenKwh, after: 6 }, { dot: true });
      if (parts.length) runLeft(c, RE, base + CG.detailDrop, parts);
    }
    text(c, f.iceCaption, HR, CG.caption, CG.captionSize, GREY, 1, { track: CG.cellCaptionTrack });
    num(c, f.iceFigure, HR, base, CG.figureSize, INK, 1, 'left');

    trace(c, f);
  }

  /* ============================================================== head unit */
  const lab = (c, s, x, y, px, a, o) => text(c, s, x, y, px, WHT, a == null ? 1 : a, Object.assign({ font: ROBOTO, w: 400 }, o || {}));
  const labW = (c, s, px, o) => textWidth(c, s, px, Object.assign({ font: ROBOTO, w: 400 }, o || {}));
  const RD = S.head.reading;

  function readingW(c, it, size, lpx) {
    let w = numWidth(it.fig, size);
    if (it.unit) w += size * RD.unitGapRatio + labW(c, it.unit, Math.round(size * RD.unitRatio));
    if (it.rate) w += size * RD.rateGapRatio + 14 + numWidth(it.rate, Math.round(size * RD.rateRatio));
    return Math.max(w, labW(c, it.cap, lpx));
  }
  function reading(c, it, x, capY, valY, size, lpx, col) {
    if (it.dot) { const d = new Path2D(); d.arc(x + 4, capY - lpx * 0.35, 3.4, 0, Math.PI * 2); glowFill(c, d, HUB, 1, 6); lab(c, it.cap, x + 14, capY, lpx); }
    else lab(c, it.cap, x, capY, lpx);
    const colour = col === 'blue' ? HUB : WHT;
    let ux = x + num(c, it.fig, x, valY, size, colour, 1, 'left');
    if (it.unit) ux += size * RD.unitGapRatio + text(c, it.unit, ux + size * RD.unitGapRatio, valY, Math.round(size * RD.unitRatio), colour, RD.unitAlpha, { font: ROBOTO, w: 400 });
    if (it.rate) {
      const ax = ux + size * RD.rateGapRatio, h = size * 0.34;
      const ar = new Path2D(); ar.moveTo(ax + 5, valY - 1); ar.lineTo(ax + 5, valY - h); ar.moveTo(ax, valY - h + 5); ar.lineTo(ax + 5, valY - h); ar.lineTo(ax + 10, valY - h + 5);
      beam(c, ar, 1.8, WHT, 0.9, 0);
      num(c, it.rate, ax + 14, valY, Math.round(size * RD.rateRatio), WHT, 0.9, 'left');
    }
  }
  function trackBlock(c, f, x, capY, valY, tpx, lpx) {
    const play = new Path2D(); play.moveTo(x + 1, capY - lpx * 0.72); play.lineTo(x + lpx * 0.6, capY - lpx * 0.38); play.lineTo(x + 1, capY - 1); play.closePath();
    beam(c, play, 1.2, WHT, 0.9, 0);
    lab(c, f.track.artist, x + lpx * 0.95, capY, lpx);
    text(c, f.track.title, x, valY, tpx, WHT, 1, { font: ROBOTO, w: 500 });
  }

  // the analyser, drawn the way the car draws its charging bars: fine vertical lines, colour, a haze
  function spectrumField(c, f, x0, top, w, floor, n) {
    const SP = S.head.spectrum, bw = SP.barWidth;
    const gap = (w - n * bw) / (n - 1), fh = floor - top - SP.headroom;
    const lv = f.spectrum.levels, cr = f.spectrum.crowns, NN = lv.length;
    c.save(); c.globalCompositeOperation = 'lighter';
    const Hz = SP.haze;
    const rx = w * Hz.rx, ry = (floor - top) * Hz.ry, hy = top + (floor - top) * Hz.cy;
    c.save(); c.translate(x0 + w / 2, hy); c.scale(1, ry / rx);
    const hz = c.createRadialGradient(0, 0, 0, 0, 0, rx);
    Hz.stops.forEach(s => hz.addColorStop(s[0], rgba(HUB[0], s[1])));
    c.fillStyle = hz; c.fillRect(-rx, -rx, rx * 2, rx * 2);
    c.restore();
    const g = c.createLinearGradient(0, floor, 0, top);
    g.addColorStop(0, rgba(HUB[0], SP.gradient[0])); g.addColorStop(1, rgba(HUB[1], SP.gradient[1]));
    const glow = rgba(HUB[0], SP.glowAlpha);
    const lines = Math.max(2, Math.round(bw / SP.linePitch)), lw = SP.lineWidth, lgap = (bw - lines * lw) / (lines - 1);
    for (let i = 0; i < n; i++) {
      const si = Math.round(i * (NN - 1) / Math.max(1, n - 1));
      const x = x0 + i * (bw + gap), h = Math.max(4, lv[si] * fh);
      c.fillStyle = glow; c.fillRect(x - SP.glowPad, floor - h - SP.glowPad, bw + 2 * SP.glowPad, h + SP.glowPad);
      c.fillStyle = g;
      for (let k = 0; k < lines; k++) c.fillRect(x + k * (lw + lgap), floor - h, lw, h);
      const cy = floor - Math.min(1, cr[si]) * fh - SP.crownLift;
      c.fillStyle = HD.crown; c.fillRect(x, cy, bw, SP.crownHeight);
    }
    c.restore();
  }
  function carChart(c, f, x0, y0, w, h) {
    const C = S.head.chart;
    const zero = y0 + h * C.zeroAt;
    const up = v => zero - (zero - y0) * Math.min(1, v / C.upTo), dn = v => zero + (y0 + h - zero) * Math.min(1, -v / C.downTo);
    const ch = f.chart, n = ch.length, st = w / n;
    const pu = new Path2D(), fu = new Path2D(), fd = new Path2D();
    fu.moveTo(x0, zero); fd.moveTo(x0, zero);
    ch.forEach((v, i) => {
      const a = x0 + i * st, b = a + st, yu = up(Math.max(0, v)), yd = v < 0 ? dn(v) : zero;
      if (i === 0) pu.moveTo(a, yu);
      pu.lineTo(a, yu); pu.lineTo(b, yu);
      fu.lineTo(a, yu); fu.lineTo(b, yu); fd.lineTo(a, yd); fd.lineTo(b, yd);
    });
    fu.lineTo(x0 + w, zero); fu.closePath(); fd.lineTo(x0 + w, zero); fd.closePath();
    c.save(); c.globalCompositeOperation = 'lighter';
    c.fillStyle = rgba(WHT[0], C.fillUp); c.fill(fu);
    c.fillStyle = rgba(HUB[0], C.fillDown); c.fill(fd);
    c.restore();
    beam(c, pu, C.stroke, WHT, 0.95, 0);
    const z = new Path2D(); z.moveTo(x0, zero); z.lineTo(x0 + w, zero); beam(c, z, C.zeroStroke, WHT, C.zeroAlpha, 0);
    const last = ch[n - 1], d = new Path2D(); d.arc(x0 + w, last >= 0 ? up(last) : dn(last), C.dot, 0, Math.PI * 2);
    glowFill(c, d, last < 0 ? HUB : WHT, 1, 8);
  }
  function tempsRow(c, f, x0, capY, valY, pitch, size) {
    f.temps.forEach((cell, i) => {
      const x = x0 + i * pitch;
      const [hc, hot] = tempColour(cell.state);
      const col = hot ? hc : WHT;
      glyph(c, KINDS[i], x, capY + 5, col, hot ? 1 : 0.9, hot);
      num(c, cell.value + '°', x, valY, size, col, 1, 'left');
    });
  }
  function dots(c, page, cx, y) {
    ['sound', 'car'].forEach((p, i) => {
      const d = new Path2D(); d.arc(cx - 7 + i * 14, y, 3, 0, Math.PI * 2);
      c.save(); c.fillStyle = p === page ? HD.dotOn : HD.dotOff; c.fill(d); c.restore();
    });
  }

  // tiles and chips
  function iconPath(ops) {
    const strokes = new Path2D(), knobs = [];
    ops.forEach(op => {
      if (op[0] === 'p') strokes.addPath(new Path2D(op[1]));
      else if (op[0] === 'c') { strokes.moveTo(op[1] + op[3], op[2]); strokes.arc(op[1], op[2], op[3], 0, Math.PI * 2); }
      else if (op[0] === 'r') strokes.roundRect(op[1], op[2], op[3], op[4], op[5]);
      else if (op[0] === 'k') knobs.push(op);
    });
    return { strokes, knobs };
  }
  function tileFace(c, tile, x, y, w, h, r, full) {
    const T = S.head.full.tiles, IC = S.head.icon, on = tile.on;
    c.save(); c.fillStyle = on ? HD.cardOn : HD.cardOff; c.beginPath(); c.roundRect(x, y, w, h, r); c.fill(); c.restore();
    const isz = full ? T.icon : S.head.two.chips.icon, sc = isz / 24;
    const ix = full ? x + T.iconInset[0] : x + (w - isz) / 2, iy = full ? y + T.iconInset[1] : y + (h - isz) / 2;
    const ic = iconPath(tile.icon);
    c.save(); c.translate(ix, iy); c.scale(sc, sc);
    const col = on ? HUB : WHT, I = on ? 1 : IC.offAlpha;
    beam(c, ic.strokes, IC.stroke, col, I, on ? IC.onGlow : 0);
    ic.knobs.forEach(k => {
      c.save(); c.fillStyle = on ? HD.cardOn : HD.cardOff; c.beginPath(); c.arc(k[1], k[2], k[3] + 1.1, 0, Math.PI * 2); c.fill(); c.restore();
      const p = new Path2D(); p.arc(k[1], k[2], k[3], 0, Math.PI * 2); beam(c, p, IC.stroke, col, I, 0);
    });
    c.restore();
    if (full) {
      lab(c, tile.name, x + T.textInset, y + T.nameBaseline, T.nameSize, on ? 1 : 0.7, { w: 500 });
      lab(c, tile.status, x + T.textInset, y + T.statusBaseline, T.statusSize, on ? 0.62 : 0.4);
    }
  }
  function handle(c, width) {
    const Hn = S.head.handle;
    c.save(); c.fillStyle = HD.handle; c.beginPath(); c.roundRect(width / 2 - Hn.width / 2, Hn.top, Hn.width, Hn.height, Hn.height / 2); c.fill(); c.restore();
  }

  function drawHead(c, mode, f) {
    const page = f.page;
    const cons = f.consumptionCaption;
    if (mode === 'full') {
      const F = S.head.full, T = F.tiles, ST = F.strip, L = F.margin, R = F.size[0] - F.margin;
      const tw = (F.size[0] - 2 * F.margin - T.gap * 5) / 6;
      f.tiles.forEach((tl, i) => tileFace(c, tl, L + (i % 6) * (tw + T.gap), T.top + Math.floor(i / 6) * (T.height + T.gap), tw, T.height, T.radius, true));
      if (page === 'sound') {
        trackBlock(c, f, L, ST.caption, ST.value, ST.titleSize, ST.labelSize);
        let x = R;
        for (let k = f.trip.length - 1; k >= 0; k--) { const w = readingW(c, f.trip[k], ST.valueSize, ST.labelSize); reading(c, f.trip[k], x - w, ST.caption, ST.value, ST.valueSize, ST.labelSize); x -= w + ST.readingGap; }
        spectrumField(c, f, L, ST.spectrumTop, R - L, ST.floor, ST.bars);
      } else {
        const Cc = F.car, mid = F.size[0] / 2;
        const pw = readingW(c, f.power, Cc.heroSize, ST.labelSize);
        reading(c, f.power, mid - pw / 2, ST.caption, ST.value, Cc.heroSize, ST.labelSize, f.power.col);
        const GR = mid - Cc.side, HR = mid + Cc.side;
        reading(c, f.volts, L, ST.caption, ST.value, ST.valueSize, ST.labelSize);
        tempsRow(c, f, GR - numWidth('00°', Cc.tempSize) - 4 * Cc.tempPitch, ST.caption, ST.value, Cc.tempPitch, Cc.tempSize);
        reading(c, f.engine, HR, ST.caption, ST.value, ST.valueSize, ST.labelSize);
        reading(c, f.tripCell, R - readingW(c, f.tripCell, ST.valueSize, ST.labelSize), ST.caption, ST.value, ST.valueSize, ST.labelSize);
        lab(c, cons, L, Cc.chartCaption, Cc.chartCaptionSize, S.head.chart.captionAlpha);
        carChart(c, f, L, Cc.chartTop, R - L, Cc.chartHeight);
      }
      dots(c, page, F.size[0] / 2, ST.dotsY);
      return;
    }
    if (mode === 'two') {
      const P = S.head.two, CH = P.chips, L = P.margin, Wd = P.size[0] - 2 * P.margin;
      handle(c, P.size[0]);
      const g = (Wd - CH.perRow * CH.size) / (CH.perRow - 1);
      f.tiles.forEach((tl, i) => tileFace(c, tl, L + i * (CH.size + g), CH.top, CH.size, CH.size, CH.radius, false));
      if (page === 'sound') {
        const s = P.sound;
        trackBlock(c, f, L, s.trackCaption, s.trackValue, s.titleSize, s.labelSize);
        let x = L; f.trip.forEach(it => { reading(c, it, x, s.caption, s.value, s.valueSize, s.labelSize); x += readingW(c, it, s.valueSize, s.labelSize) + s.gap; });
        spectrumField(c, f, L, s.spectrumTop, Wd, s.floor, s.bars);
      } else {
        const s = P.car; let x = L;
        [[f.power, s.heroSize, f.power.col], [f.engine, s.valueSize], [f.tripCell, s.valueSize]].forEach(([it, sz, col]) => { reading(c, it, x, s.caption, s.value, sz, P.sound.labelSize, col); x += readingW(c, it, sz, P.sound.labelSize) + s.gap; });
        reading(c, f.volts, L, s.row2Caption, s.row2Value, s.voltSize, P.sound.labelSize);
        tempsRow(c, f, L + readingW(c, f.volts, s.voltSize, P.sound.labelSize) + s.gap, s.row2Caption, s.row2Value, s.tempPitch, s.tempSize);
        lab(c, cons, L, s.chartCaption, 15, S.head.chart.captionAlpha);
        carChart(c, f, L, s.chartTop, Wd, s.chartHeight);
      }
      dots(c, page, P.size[0] / 2, P.dotsY);
      return;
    }
    const P = S.head.one, CH = P.chips, L = P.margin, Wd = P.size[0] - 2 * P.margin;
    handle(c, P.size[0]);
    const g = (Wd - CH.perRow * CH.size) / (CH.perRow - 1);
    f.tiles.forEach((tl, i) => tileFace(c, tl, L + (i % CH.perRow) * (CH.size + g), CH.top + Math.floor(i / CH.perRow) * (CH.size + CH.rowGap), CH.size, CH.size, CH.radius, false));
    if (page === 'sound') {
      const s = P.sound;
      trackBlock(c, f, L, s.trackCaption, s.trackValue, s.titleSize, s.labelSize);
      f.trip.forEach((it, k) => {
        const y = s.rowsTop + k * s.rowPitch;
        lab(c, it.cap, L, y, s.labelSize, 0.85);
        const vx = L + s.valueX;
        const ux = vx + num(c, it.fig, vx, y, s.valueSize, WHT, 1, 'left');
        if (it.unit) text(c, it.unit, ux + 6, y, 14, WHT, 0.9, { font: ROBOTO, w: 400 });
        if (it.rate) {
          const ax = ux + 36, ar = new Path2D(); ar.moveTo(ax + 4, y - 1); ar.lineTo(ax + 4, y - 12); ar.moveTo(ax, y - 8); ar.lineTo(ax + 4, y - 12); ar.lineTo(ax + 8, y - 8);
          beam(c, ar, 1.5, WHT, 0.9, 0); num(c, it.rate, ax + 12, y, 14, WHT, 0.9, 'left');
        }
      });
      spectrumField(c, f, L, s.spectrumTop, Wd, s.floor, s.bars);
    } else {
      const s = P.car;
      reading(c, f.power, L, s.caption, s.value, s.heroSize, P.sound.labelSize, f.power.col);
      reading(c, f.volts, L, s.row2Caption, s.row2Value, s.row2Size, P.sound.labelSize);
      reading(c, f.engine, L + readingW(c, f.volts, s.row2Size, P.sound.labelSize) + s.row2Gap, s.row2Caption, s.row2Value, s.row2Size, P.sound.labelSize);
      tempsRow(c, f, L, s.tempsCaption, s.tempsValue, s.tempPitch, s.tempSize);
      lab(c, cons, L, s.chartCaption, s.chartCaptionSize, S.head.chart.captionAlpha);
      carChart(c, f, L, s.chartTop, Wd, s.chartHeight);
    }
    dots(c, page, P.size[0] / 2, P.dotsY);
  }

  /* ================================================================= boards */
  function drawDigits(c) {
    num(c, '0123456789', 60, 150, 150, INK, 1, 'left');
    num(c, '12,3:45°', 60, 262, 72, INK, 1, 'left');
  }

  // board: { kind: 'cluster' | 'head' | 'digits', mode, page }; px scale is applied by the caller
  function drawBoard(c, board, fixture) {
    if (board.kind === 'cluster') drawCluster(c, fixture);
    else if (board.kind === 'head') drawHead(c, board.mode, Object.assign({}, fixture, { page: board.page }));
    else drawDigits(c);
  }

  root.Luminofor = { drawBoard, numWidth, spec: S };
})(window);
