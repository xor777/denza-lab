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
  function beam(ctx, path, sw, col, I, g, over) {
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
    if (over) ctx.globalCompositeOperation = 'source-over';
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
    ctx.globalCompositeOperation = o.over ? 'source-over' : 'lighter';
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
    const fresh = f.powerFresh !== false;
    if (fresh && ga > 0.005) {
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
    if (f.heroUnit !== false) text(c, 'кВт', fieldR + CG.heroUnitGap, base, CG.heroUnitSize, into ? BLUE : GREY, 1);
    // the axis: one filament across the glass, brightest at zero, dying toward both edges
    filament(c, y);
    if (!fresh) return;
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

  // The ten-kilometre chart as the energy contract draws it (§2.3), on both screens: one line
  // through the points - a hundred kilometre-means, one per hundred metres of recorded road, the
  // newest on the right edge, a full window edge to edge and a filling one growing leftward at the
  // same pitch, so «за 3,7 км» is as wide as its road - and the field between the line
  // and zero. White above zero and blue under it, split by zero itself, so nothing is stroked along
  // the zero. A run of points past a ceiling lies along it, with one tick just outside the box at
  // the run's centre, so the reader sees it was cut.
  function silhouette(c, ch, o) {
    const n = ch.length;
    const xOf = i => o.right - (n - 1 - i) * o.pitch;
    const yOf = v => v >= 0 ? o.zero - (o.zero - o.top) * Math.min(1, v / o.upTo) : o.zero + (o.bottom - o.zero) * Math.min(1, -v / o.downTo);
    const x0 = xOf(0), x1 = xOf(n - 1);
    const line = new Path2D();
    ch.forEach((v, i) => i ? line.lineTo(xOf(i), yOf(v)) : line.moveTo(xOf(i), yOf(v)));
    const field = new Path2D(line); field.lineTo(x1, o.zero); field.lineTo(x0, o.zero); field.closePath();
    const above = new Path2D(); above.rect(x0 - 20, o.top - 20, x1 - x0 + 40, o.zero - o.top + 20);
    const below = new Path2D(); below.rect(x0 - 20, o.zero, x1 - x0 + 40, o.bottom - o.zero + 20);
    c.save(); c.globalCompositeOperation = 'lighter';
    c.save(); c.clip(above); o.fillUp(c, field, x0, x1); c.restore();
    c.save(); c.clip(below); o.fillDown(c, field, x0, x1); c.restore();
    c.restore();
    // One stroke, lit in runs: the window is cut into equal runs of x and each is drawn a step
    // dimmer than the one after it - the beam's persistence - by a stepped gradient along x rather
    // than a path per run, whose round caps would meet and add up into a bright bead at every seam.
    const runs = o.runs;
    const lit = col => {
      const g = c.createLinearGradient(x0, 0, x1, 0);
      for (let r = 0; r < runs; r++) {
        const I = o.intensity(r, runs);
        g.addColorStop(r / runs, rgba(col[1], I)); g.addColorStop((r + 1) / runs, rgba(col[1], I));
      }
      return g;
    };
    [[above, o.upLight], [below, o.downLight]].forEach(([clip, col]) => {
      c.save(); c.clip(clip); c.globalCompositeOperation = 'lighter';
      c.lineCap = 'round'; c.lineJoin = 'round'; c.lineWidth = o.stroke; c.strokeStyle = lit(col); c.stroke(line);
      c.restore();
    });
    for (let i = 0; i < n;) {
      const up = ch[i] > o.upTo, dn = ch[i] < -o.downTo;
      if (!up && !dn) { i++; continue; }
      let j = i;
      while (j + 1 < n && (up ? ch[j + 1] > o.upTo : ch[j + 1] < -o.downTo)) j++;
      const xm = (xOf(i) + xOf(j)) / 2, t = new Path2D();
      if (up) { t.moveTo(xm, o.top - 1); t.lineTo(xm, o.top - 1 - o.tick); }
      else { t.moveTo(xm, o.bottom + 1); t.lineTo(xm, o.bottom + 1 + o.tick); }
      beam(c, t, o.stroke, up ? o.upLight : o.downLight, 1, 0);
      i = j + 1;
    }
    return { x: x1, y: yOf(ch[n - 1]), last: ch[n - 1] };
  }

  function trace(c, f) {
    const ch = f.chart;
    if (!ch.length) return traceFigure(c, f);
    const end = silhouette(c, ch, {
      right: AX - CT.gapFromAxis, pitch: CT.width / (CT.points - 1), zero: CT.zero, top: CT.top, bottom: CT.drop,
      upTo: CT.upTo, downTo: CT.downTo, upLight: INK, downLight: BLUE, stroke: CT.stroke, tick: CT.tick, runs: CT.runs,
      // ten runs, the older ones dimmer - the beam's persistence
      intensity: (r, runs) => 0.28 + 0.72 * Math.pow((r + 1) / runs, 1.6),
      fillUp: (cc, field, x0, x1) => {
        const g = cc.createLinearGradient(x0, 0, x1, 0); g.addColorStop(0, rgba(INK[0], 0.03)); g.addColorStop(1, rgba(INK[0], 0.16));
        cc.fillStyle = g; cc.fill(field);
      },
      fillDown: (cc, field, x0, x1) => {
        const g = cc.createLinearGradient(x0, 0, x1, 0); g.addColorStop(0, rgba(BLUE[0], 0.05)); g.addColorStop(1, rgba(BLUE[0], 0.3));
        cc.fillStyle = g; cc.fill(field);
      }
    });
    const dot = new Path2D(); dot.arc(end.x, end.y, 2.6, 0, Math.PI * 2);
    glowFill(c, dot, end.last < 0 ? BLUE : INK, 1, 12);
    traceFigure(c, f);
  }
  function traceFigure(c, f) {
    const zero = CT.zero;
    const fx = AX + CT.gapFromAxis;
    // a figure that stopped arriving leaves, and its unit stays where the last one put it
    const fw = f.consumption ? num(c, f.consumption, fx, zero, CT.figureSize, INK, 1, 'left')
      : f.consumptionHeld ? numWidth(f.consumptionHeld, CT.figureSize) : 0;
    if (f.consumptionUnit) text(c, f.consumptionUnit, fx + fw + CT.unitGap, zero, CT.unitSize, GREY, 1);
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
    if (f.volts) num(c, f.volts, GL, base, CG.figureSize, INK, 1, 'left');
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
      if (cell.value != null) num(c, cell.value + '°', x, base, CG.tempSize, col, 1, 'left');
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
      // the history's colours, not the return's: what G is in motion is open (contract §2.5), and
      // blue on this panel means «into the pack» and nothing else
      const g = c.createLinearGradient(0, top, 0, zeroY); g.addColorStop(0, rgba(INK[0], 0.16)); g.addColorStop(1, rgba(INK[0], 0.02));
      c.fillStyle = g; c.fill(area); c.restore();
      beam(c, edge, E.stroke, INK, 0.95, 0);
      const b0 = new Path2D(); b0.moveTo(bx0, zeroY); b0.lineTo(RE, zeroY); beam(c, b0, E.baseStroke, INK, 0.35, 0);
      text(c, f.engineCaption, bx0, CG.caption, CG.captionSize, GREY, 1, { track: CG.cellCaptionTrack });
      text(c, f.engineWindow, bx0, base + CG.detailDrop, CG.detailSize, GREY, 1, { track: CG.cellCaptionTrack });
    } else if (f.tripCaption) {
      const uw = textWidth(c, f.tripUnit, CG.unitSize), cw = textWidth(c, f.tripCaption, CG.captionSize, { track: CG.cellCaptionTrack });
      const fw = f.tripKwh ? numWidth(f.tripKwh, CG.figureSize) : 0;
      const x = RE - Math.max(cw, f.tripKwh ? fw + CG.unitGap + uw : 0);
      text(c, f.tripCaption, x, CG.caption, CG.captionSize, GREY, 1, { track: CG.cellCaptionTrack });
      if (f.tripKwh) {
        num(c, f.tripKwh, x, base, CG.figureSize, INK, 1, 'left');
        text(c, f.tripUnit, x + fw + CG.unitGap, base, CG.unitSize, GREY, 1);
      }
      // the trip's detail: «ДАЛ ДВС» whenever the engine gave this trip (the contract keeps that
      // seat on the move), recuperation on P; one line under the trip, in the fixture's order
      const parts = [];
      if (f.gaveKwh) parts.push({ text: f.gaveCaption, track: CG.detailTrack }, { text: f.tripUnit, after: 6 }, { num: f.gaveKwh, after: 0 });
      if (f.gaveKwh && f.regenKwh) parts.push({ gap: 28 });
      if (f.regenKwh) parts.push({ text: f.regenCaption, track: CG.detailTrack }, { text: f.tripUnit, after: 6 }, { num: f.regenKwh, after: 6 }, { dot: true });
      if (parts.length) runLeft(c, RE, base + CG.detailDrop, parts);
    }
    text(c, f.iceCaption, HR, CG.caption, CG.captionSize, GREY, 1, { track: CG.cellCaptionTrack });
    if (f.iceFigure) num(c, f.iceFigure, HR, base, CG.figureSize, INK, 1, 'left');

    trace(c, f);
  }

  /* ============================================================== head unit */
  const lab = (c, s, x, y, px, a, o) => text(c, s, x, y, px, WHT, a == null ? 1 : a, Object.assign({ font: ROBOTO, w: 400 }, o || {}));
  const labW = (c, s, px, o) => textWidth(c, s, px, Object.assign({ font: ROBOTO, w: 400 }, o || {}));
  const RD = S.head.reading;

  function readingW(c, it, size, lpx) {
    if (it.hint) return labW(c, it.cap, lpx);
    let w = numWidth(it.fig, size);
    if (it.unit) w += size * RD.unitGapRatio + labW(c, it.unit, Math.round(size * RD.unitRatio));
    if (it.rate) w += size * RD.rateGapRatio + 14 + numWidth(it.rate, Math.round(size * RD.rateRatio));
    return Math.max(w, labW(c, it.cap, lpx));
  }
  function reading(c, it, x, capY, valY, size, lpx, col) {
    // a caption with no reading under it and none coming - the location hint - at 0.6, alone
    if (it.hint) { lab(c, it.cap, x, capY, lpx, 0.6); return; }
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
  // Nothing playing, no block. Paused, the same block at half its light, and the mark is the
  // pause's two bars: the mark says what the player is doing, as the car's own media card does.
  function trackBlock(c, f, x, capY, valY, tpx, lpx) {
    if (!f.track) return;
    const I = f.track.playing === false ? 0.5 : 1;
    if (I < 1) {
      const pause = new Path2D();
      [0.14, 0.46].forEach(u => { pause.moveTo(x + lpx * u, capY - lpx * 0.68); pause.lineTo(x + lpx * u, capY - lpx * 0.04); });
      beam(c, pause, 2, WHT, 0.9 * I, 0);
    } else {
      const play = new Path2D(); play.moveTo(x + 1, capY - lpx * 0.72); play.lineTo(x + lpx * 0.6, capY - lpx * 0.38); play.lineTo(x + 1, capY - 1); play.closePath();
      beam(c, play, 1.2, WHT, 0.9, 0);
    }
    lab(c, f.track.artist, x + lpx * 0.95, capY, lpx, I);
    text(c, f.track.title, x, valY, tpx, WHT, I, { font: ROBOTO, w: 500 });
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
    const C = S.head.chart, zero = y0 + h * C.zeroAt, ch = f.chart;
    if (!ch.length) return;
    const z = new Path2D(); z.moveTo(x0, zero); z.lineTo(x0 + w, zero); beam(c, z, C.zeroStroke, WHT, C.zeroAlpha, 0);
    const end = silhouette(c, ch, {
      right: x0 + w, pitch: w / (C.points - 1), zero, top: y0, bottom: y0 + h,
      upTo: C.upTo, downTo: C.downTo, upLight: WHT, downLight: HUB, stroke: C.stroke, tick: C.tick, runs: 1,
      intensity: () => 0.95,
      fillUp: (cc, field) => { cc.fillStyle = rgba(WHT[0], C.fillUp); cc.fill(field); },
      fillDown: (cc, field) => { cc.fillStyle = rgba(HUB[0], C.fillDown); cc.fill(field); }
    });
    const d = new Path2D(); d.arc(end.x, end.y, C.dot, 0, Math.PI * 2);
    glowFill(c, d, end.last < 0 ? HUB : WHT, 1, 8);
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
  // A tile's tone. Live and working are the lit plate with the dock's blue glyph, idle the dark
  // plate. Waiting on the driver and broken keep the lit plate and light the glyph and the status
  // in the car's own two alarm colours, laid over whole rather than added: added onto the plate the
  // car's orange came out yellow and its red came out pink. Working turns a ring beside the glyph.
  // A tile nothing can be done to is idle, whatever it was.
  const ALARMS = { attention: ORNG, broken: RED };
  // Where a glyph's ink actually is inside its 24-unit box: every point its stroke covers, asked of
  // the canvas at a twentieth of a unit. The glyphs hang on one left edge for the tiles, where a
  // column of words starts under them, so a chip centres this rather than the box - or a narrow
  // glyph, the speaker's, stands four units left of its chip's middle. The app's
  // DenzaGlyph.inkBounds rasterises the same stroke at the same resolution.
  const INK_BOXES = new Map();
  function inkBox(ops, sw) {
    if (INK_BOXES.has(ops)) return INK_BOXES.get(ops);
    const p = new Path2D();
    ops.forEach(op => {
      if (op[0] === 'p') p.addPath(new Path2D(op[1]));
      else if (op[0] === 'r') p.roundRect(op[1], op[2], op[3], op[4], op[5]);
      else { p.moveTo(op[1] + op[3], op[2]); p.arc(op[1], op[2], op[3], 0, Math.PI * 2); }
    });
    const cx = document.createElement('canvas').getContext('2d');
    cx.lineWidth = sw; cx.lineCap = 'round'; cx.lineJoin = 'round';
    let x0 = 99, y0 = 99, x1 = -99, y1 = -99;
    for (let i = -40; i <= 520; i++) for (let j = -40; j <= 520; j++) {
      const x = (i + 0.5) / 20, y = (j + 0.5) / 20;
      if (cx.isPointInStroke(p, x, y)) { x0 = Math.min(x0, i); x1 = Math.max(x1, i + 1); y0 = Math.min(y0, j); y1 = Math.max(y1, j + 1); }
    }
    const box = [x0 / 20, y0 / 20, x1 / 20, y1 / 20];
    INK_BOXES.set(ops, box);
    return box;
  }
  function tileFace(c, tile, x, y, w, h, r, full) {
    const T = S.head.full.tiles, IC = S.head.icon;
    const tone = tile.tone || (tile.on ? 'live' : 'idle'), on = tone !== 'idle', alarm = ALARMS[tone];
    c.save(); c.fillStyle = on ? HD.cardOn : HD.cardOff; c.beginPath(); c.roundRect(x, y, w, h, r); c.fill(); c.restore();
    const isz = full ? T.icon : S.head.two.chips.icon, sc = isz / 24;
    const ix = full ? x + T.iconInset[0] : x + (w - isz) / 2, iy = full ? y + T.iconInset[1] : y + (h - isz) / 2;
    const ic = iconPath(tile.icon);
    c.save(); c.translate(ix, iy); c.scale(sc, sc);
    if (!full) { const k = inkBox(tile.icon, IC.stroke); c.translate(12 - (k[0] + k[2]) / 2, 12 - (k[1] + k[3]) / 2); }
    const col = alarm ? [alarm[0], alarm[0]] : on ? HUB : WHT, I = on ? 1 : IC.offAlpha;
    beam(c, ic.strokes, IC.stroke, col, I, on ? IC.onGlow : 0, !!alarm);
    ic.knobs.forEach(k => {
      c.save(); c.fillStyle = on ? HD.cardOn : HD.cardOff; c.beginPath(); c.arc(k[1], k[2], k[3] + 1.1, 0, Math.PI * 2); c.fill(); c.restore();
      const p = new Path2D(); p.arc(k[1], k[2], k[3], 0, Math.PI * 2); beam(c, p, IC.stroke, col, I, 0, !!alarm);
    });
    c.restore();
    if (tone === 'working') {
      const R = IC.ring, d = full ? R.size : R.chipSize, sw = IC.stroke * sc;
      if (full) ring(c, x + w - T.textInset - d / 2, iy + isz / 2, d, sw);
      else ring(c, x + w - R.chipInset - d / 2, y + R.chipInset + d / 2, d, sw);
    }
    if (full) {
      lab(c, tile.name, x + T.textInset, y + T.nameBaseline, T.nameSize, on ? 1 : 0.7, { w: 500 });
      if (alarm) text(c, tile.status, x + T.textInset, y + T.statusBaseline, T.statusSize, col, 1, { font: ROBOTO, w: 400, over: true });
      else lab(c, tile.status, x + T.textInset, y + T.statusBaseline, T.statusSize, on ? 0.62 : 0.4);
    }
  }
  // The working ring: a quarter of a circle in the glyph's blue and weight, inside a d-square box.
  // A board holds it at twelve o'clock; the app turns it once a second.
  function ring(c, cx, cy, d, sw) {
    const p = new Path2D(); p.arc(cx, cy, (d - sw) / 2, -Math.PI / 2, -Math.PI / 2 + S.head.icon.ring.sweep * Math.PI / 180);
    beam(c, p, sw, HUB, 1, 0);
  }
  // The car's page when the shell is closed to us: what the page would have shown, and under it
  // the instruction in the page's title size, broken at its spaces to the strip's width.
  function closedPage(c, f, L, R, capY, valY, label, size) {
    lab(c, 'Питание от машины', L, capY, label);
    const lines = []; let line = '';
    String(f.message).split(' ').forEach(word => {
      const next = line ? line + ' ' + word : word;
      if (line && labW(c, next, size) > R - L) { lines.push(line); line = word; } else line = next;
    });
    if (line) lines.push(line);
    lines.forEach((ln, i) => lab(c, ln, L, valY + i * size * 1.25, size));
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
      } else if (f.unavailable) {
        closedPage(c, f, L, R, ST.caption, ST.value, ST.labelSize, ST.titleSize);
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
      } else if (f.unavailable) {
        closedPage(c, f, L, L + Wd, P.car.caption, P.car.value, P.sound.labelSize, P.sound.titleSize);
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
        lab(c, it.cap, L, y, s.labelSize, it.hint ? 0.6 : 0.85);
        if (it.hint) return;
        const vx = L + s.valueX;
        const ux = vx + num(c, it.fig, vx, y, s.valueSize, WHT, 1, 'left');
        if (it.unit) text(c, it.unit, ux + 6, y, 14, WHT, 0.9, { font: ROBOTO, w: 400 });
        if (it.rate) {
          const ax = ux + 36, ar = new Path2D(); ar.moveTo(ax + 4, y - 1); ar.lineTo(ax + 4, y - 12); ar.moveTo(ax, y - 8); ar.lineTo(ax + 4, y - 12); ar.lineTo(ax + 8, y - 8);
          beam(c, ar, 1.5, WHT, 0.9, 0); num(c, it.rate, ax + 12, y, 14, WHT, 0.9, 'left');
        }
      });
      spectrumField(c, f, L, s.spectrumTop, Wd, s.floor, s.bars);
    } else if (f.unavailable) {
      closedPage(c, f, L, L + Wd, P.car.caption, P.car.value, P.sound.labelSize, P.sound.titleSize);
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

  /* ================================================================ settings */
  // A feature's settings, drawn from the car's own BYD widget kit (CarSettingPlatform, byd_pvt_*
  // dark) in Luminofor's grounds: the stock switch, list row, segmented tab, primary button and
  // selection badge, on a panel the colour of an idle plate with groups on the lit plate's colour.
  // Every text is placed by its baseline, as the app places it, so the two can be laid over.
  const SH = S.sheet;
  const hexA = (h, a) => rgba(hex(h), a);
  function over(c, fn) { c.save(); c.globalCompositeOperation = 'source-over'; fn(); c.restore(); }
  function fillRound(c, x, y, w, h, r, style) {
    over(c, () => { c.fillStyle = style; c.beginPath(); c.roundRect(x, y, w, h, r); c.fill(); });
  }
  // words over a surface are drawn over it, not added: white at 0.9 over a plate is what Compose's
  // text lays down, and the plate under a panel is not black
  function words(c, str, x, y, px, a, o) {
    o = o || {};
    return text(c, str, x, y, px, o.col || WHT, a, Object.assign({ font: ROBOTO, w: o.w || 400, over: true }, o));
  }
  function wordsW(c, str, px, w) { return textWidth(c, str, px, { font: ROBOTO, w: w || 400 }); }
  function wrap(c, str, px, w, room) {
    const lines = []; let line = '';
    String(str).split(' ').forEach(word => {
      const next = line ? line + ' ' + word : word;
      if (line && wordsW(c, next, px, w) > room) { lines.push(line); line = word; } else line = next;
    });
    if (line) lines.push(line);
    return lines;
  }
  const CLOSE = [['p', 'M6 6l12 12M18 6L6 18']], BACK = [['p', 'M15 5l-7 7 7 7']], FORWARD = [['p', 'M9 5l7 7-7 7']];
  const CHECK = new Path2D('M5.5 10.2l3 3 6-6.2');
  // a glyph in a box, its ink centred - the tile's glyph when it stands on its own
  function glyphAt(c, ops, x, y, size, a, col, ground) {
    const k = inkBox(ops, S.head.icon.stroke), sc = size / 24, ic = iconPath(ops), ink = col || WHT[1];
    c.save(); c.translate(x, y); c.scale(sc, sc); c.translate(12 - (k[0] + k[2]) / 2, 12 - (k[1] + k[3]) / 2);
    over(c, () => { c.lineWidth = S.head.icon.stroke; c.lineCap = 'round'; c.lineJoin = 'round'; c.strokeStyle = rgba(ink, a); c.stroke(ic.strokes); });
    ic.knobs.forEach(k2 => over(c, () => {
      c.fillStyle = ground || S.sheet.panel.ground; c.beginPath(); c.arc(k2[1], k2[2], k2[3] + 1.1, 0, Math.PI * 2); c.fill();
      c.lineWidth = S.head.icon.stroke; c.strokeStyle = rgba(ink, a); c.beginPath(); c.arc(k2[1], k2[2], k2[3], 0, Math.PI * 2); c.stroke();
    }));
    c.restore();
  }
  function lineGlyph(c, ops, x, y, size, a) {
    const ic = iconPath(ops), sc = size / 24;
    c.save(); c.translate(x, y); c.scale(sc, sc);
    over(c, () => { c.lineWidth = S.head.icon.stroke; c.lineCap = 'round'; c.lineJoin = 'round'; c.strokeStyle = rgba(WHT[1], a); c.stroke(ic.strokes); });
    c.restore();
  }
  function toggle(c, x, y, on, enabled) {
    const W = SH.switch, a = enabled === false ? W.disabledAlpha : 1;
    fillRound(c, x, y, W.width, W.height, W.height / 2, on ? hexA(W.on, a) : hexA(W.off, W.offAlpha * a));
    const pad = (W.height - W.thumb) / 2, tx = on ? x + W.width - pad - W.thumb : x + pad;
    over(c, () => { c.fillStyle = hexA(W.thumbColor, a); c.beginPath(); c.arc(tx + W.thumb / 2, y + W.height / 2, W.thumb / 2, 0, Math.PI * 2); c.fill(); });
  }
  function letterIcon(c, name, x, y, size) {
    fillRound(c, x, y, size, size, size * 0.27, rgba([255, 255, 255], 0.1));
    words(c, name.slice(0, 1).toUpperCase(), x + size / 2, centred(y + size / 2, size * 0.45), size * 0.45, 0.9, { w: 500, align: 'center' });
  }
  // the rows of one plate: a switch, a choice, or a reading
  const RB = SH.roboto, R1 = SH.row.single, R2 = SH.row.twoLine, R3 = SH.row.withIcons;
  const centred = (cy, px) => cy + RB.centre * px;
  function rowHeight(r) { return r.icons && r.icons.length ? R3[0] : r.summary || (r.kind === 'choice' && r.value) ? R2[0] : R1[0]; }
  function drawRow(c, r, x, y, w) {
    const R = SH.row, h = rowHeight(r), dim = r.enabled === false ? 0.5 : 1;
    const right = x + w - R.padX;
    const icons = r.icons && r.icons.length, two = r.summary || (r.kind === 'choice' && r.value);
    words(c, r.title, x + R.padX, y + (icons ? R3[1] : two ? R2[1] : R1[1]), R.titleSize, R.titleAlpha * dim);
    if (icons) {
      let ix = x + R.padX;
      r.icons.forEach(n => { letterIcon(c, n, ix, y + R3[2], R.choiceIcon); ix += R.choiceIcon + R.choiceGap; });
      if (r.value) words(c, r.value, ix, y + R3[3], R.summarySize, R.summaryAlpha * dim);
    } else if (two) words(c, r.summary || r.value, x + R.padX, y + R2[2], R.summarySize, R.summaryAlpha * dim);
    if (r.kind === 'switch') toggle(c, right - SH.switch.width, y + (h - SH.switch.height) / 2, r.on, r.enabled);
    if (r.kind === 'choice') lineGlyph(c, FORWARD, right - R.chevron, y + (h - R.chevron) / 2, R.chevron, SH.header.closeAlpha);
    return h;
  }
  function plate(c, rows, x, y, w) {
    const P = SH.plate;
    const hs = rows.map(rowHeight), total = hs.reduce((a, b) => a + b, 0);
    fillRound(c, x, y, w, total, P.radius, P.color);
    let yy = y;
    rows.forEach((r, i) => {
      if (i) over(c, () => { c.fillStyle = rgba([255, 255, 255], P.hairlineAlpha); c.fillRect(x + P.hairlineInset, yy, w - 2 * P.hairlineInset, 1); });
      drawRow(c, r, x, yy, w); yy += hs[i];
    });
    return total;
  }
  function segmented(c, b, x, y, w) {
    const G = SH.segmented, n = b.labels.length, a = b.enabled === false ? 0.5 : 1;
    fillRound(c, x, y, w, G.height, G.radius, rgba([255, 255, 255], G.trackAlpha));
    const cw = (w - 2 * G.pad) / n;
    b.labels.forEach((l, i) => {
      const cx = x + G.pad + i * cw, on = i === b.selected;
      if (on) fillRound(c, cx, y + G.pad, cw, G.height - 2 * G.pad, G.radius - G.pad, rgba([255, 255, 255], G.pillAlpha * a));
      words(c, l, cx + cw / 2, centred(y + G.height / 2, G.size), G.size, (on ? G.onTextAlpha : G.offTextAlpha) * a,
        { w: on ? 500 : 400, align: 'center', col: on ? [[0, 0, 0], [0, 0, 0]] : WHT });
    });
    return G.height;
  }
  // The app's grid: a fixed count of columns on the full screen (four, three for the navigators),
  // and in a pane as many 96-wide columns as fit - Compose's Adaptive - each stretched to share the
  // width, so a cell is wider than tall and the tile is its width by 96.
  function apps(c, b, x, y, w, compact) {
    const A = SH.apps;
    const cols = compact ? Math.max(1, Math.floor((w + A.gap) / (A.tile + A.gap))) : (b.columns || 4);
    const cw = (w - (cols - 1) * A.gap) / cols;
    b.items.forEach((it, i) => {
      const tx = x + (i % cols) * (cw + A.gap), ty = y + Math.floor(i / cols) * (A.tile + A.gap);
      const dim = it.enabled === false ? 0.5 : 1;
      fillRound(c, tx, ty, cw, A.tile, A.radius, SH.plate.color);
      letterIcon(c, it.name, tx + (cw - A.icon) / 2, ty + 14, A.icon);
      let nm = it.name;
      while (wordsW(c, nm, A.nameSize, 500) > cw - 12 && nm.length > 1) nm = nm.slice(0, -2) + '…';
      words(c, nm, tx + cw / 2, ty + 82, A.nameSize, A.nameAlpha * dim, { w: 500, align: 'center' });
      if (it.selected) {
        const bx = tx + cw - A.badgeInset - A.badge, by = ty + A.badgeInset;
        over(c, () => {
          c.fillStyle = A.badgeColor; c.beginPath(); c.arc(bx + A.badge / 2, by + A.badge / 2, A.badge / 2, 0, Math.PI * 2); c.fill();
          c.translate(bx, by); c.lineWidth = 2; c.lineCap = 'round'; c.lineJoin = 'round'; c.strokeStyle = '#FFFFFF'; c.stroke(CHECK);
        });
      }
    });
    const rows = Math.ceil(b.items.length / cols);
    return rows * A.tile + (rows - 1) * A.gap;
  }
  function paragraph(c, str, x, y, w, size, leading, a, col) {
    const lines = wrap(c, str, size, 400, w), lh = size * leading;
    lines.forEach((l, i) => words(c, l, x, y + size * RB.ascent + i * lh, size, a, { col: col }));
    return lines.length ? size * RB.ascent + (lines.length - 1) * lh + size * RB.descent : 0;
  }
  function button(c, b, x, y, w) {
    const B = SH.button, primary = b.kind !== 'secondary', h = primary ? B.height : B.secondaryHeight;
    const a = b.enabled === false ? B.disabledAlpha : 1, size = primary ? B.size : B.secondarySize;
    fillRound(c, x, y, w, h, B.radius, primary ? hexA(B.primary, a) : rgba([255, 255, 255], B.secondaryAlpha));
    // a quiet button that answers something waiting on the driver says so in the car's orange
    const ink = b.attention ? [hex('#FF9F19'), hex('#FF9F19')] : WHT;
    words(c, b.text, x + w / 2, centred(y + h / 2, size), size, (primary || b.attention ? 1 : 0.9) * a, { w: 500, align: 'center', col: ink });
    return h;
  }
  let COMPACT = false;
  function block(c, b, x, y, w) {
    switch (b.t) {
      case 'status': {
        const col = b.tone === 'attention' ? [hex('#FF9F19'), hex('#FF9F19')] : b.tone === 'broken' ? [hex('#FF4046'), hex('#FF4046')] : null;
        return paragraph(c, b.text, x, y, w, SH.status.size, SH.status.leading, col ? 1 : SH.note.alpha, col || WHT);
      }
      case 'group': return plate(c, b.rows, x, y, w);
      case 'switch': return plate(c, [Object.assign({ kind: 'switch' }, b)], x, y, w);
      // a section's words over its body - one block, or several a label's gap apart, as
      // DenzaSection's column spaces everything under its label
      case 'section': {
        words(c, b.label, x, y + SH.label.size * RB.ascent, SH.label.size, SH.label.alpha, { w: 500 });
        let yy = y + SH.label.size * (RB.ascent + RB.descent);
        (Array.isArray(b.body) ? b.body : [b.body]).forEach(k => { yy += SH.label.gap; yy += block(c, k, x, yy, w); });
        return yy - y;
      }
      // blocks stacked at a gap of their own: the display choices' column of buttons
      case 'stack': {
        let yy = y;
        b.items.forEach((k, i) => { if (i) yy += b.gap; yy += block(c, k, x, yy, w); });
        return yy - y;
      }
      case 'segmented': return segmented(c, b, x, y, w);
      case 'apps': return apps(c, b, x, y, w, COMPACT);
      case 'note': return paragraph(c, b.text, x, y, w, SH.note.size, SH.note.leading, SH.note.alpha);
      case 'button': return button(c, b, x, y, w);
      case 'footnote':
        words(c, b.text, x + w / 2, y + SH.footnote.size * RB.ascent, SH.footnote.size, SH.footnote.alpha, { align: 'center' });
        return SH.footnote.size * (RB.ascent + RB.descent);
      case 'reading': {
        const R = SH.reading;
        const lh = R.labelSize * (RB.ascent + RB.descent);
        words(c, b.label, x, y + R.labelSize * RB.ascent, R.labelSize, R.labelAlpha);
        words(c, b.value, x, y + lh + R.gap + R.valueSize * RB.ascent, R.valueSize, R.valueAlpha, { w: 400 });
        return lh + R.gap + R.valueSize * (RB.ascent + RB.descent);
      }
    }
    return 0;
  }
  // the panel: at the right edge on the full screen, the whole window in a pane
  function drawSheet(c, mode, f) {
    const sh = f.sheet, full = mode === 'full', size = full ? S.head.full.size : mode === 'two' ? S.head.two.size : S.head.one.size;
    drawHead(c, mode, f);
    const P = SH.panel, K = full ? P : SH.compact, bar = full ? 0 : S.head[mode].captionBar;
    // the scrim covers the window under a pane's caption bar: the bar is the system's
    over(c, () => { c.fillStyle = rgba([0, 0, 0], SH.scrim); c.fillRect(0, bar, size[0], size[1] - bar); });
    COMPACT = !full;
    const px0 = full ? size[0] - P.width : 0, pw = full ? P.width : size[0];
    over(c, () => { c.fillStyle = P.ground; c.fillRect(px0, bar, pw, size[1] - bar); });
    if (full) over(c, () => { c.fillStyle = hexA(P.edge, P.edgeAlpha); c.fillRect(px0, 0, 1, size[1]); });
    const x = px0 + K.padX, w = pw - 2 * K.padX;
    let y = bar + K.padTop;
    // the header: the tile's glyph or the way back, the title, the way out
    const Hh = SH.header;
    if (sh.back) lineGlyph(c, BACK, x, y + (Hh.height - Hh.close) / 2, Hh.close, Hh.closeAlpha);
    // the tile's glyph names the panel - white, ink-centred, no state: the status line says that
    else if (sh.icon) glyphAt(c, sh.icon, x, y + (Hh.height - Hh.glyph) / 2, Hh.glyph, Hh.glyphAlpha);
    const tx = x + (sh.back ? Hh.close : sh.icon ? Hh.glyph : -Hh.glyphGap) + Hh.glyphGap;
    words(c, sh.title, tx, centred(y + Hh.height / 2, Hh.titleSize), Hh.titleSize, 1, { w: 500 });
    lineGlyph(c, CLOSE, x + w - Hh.close, y + (Hh.height - Hh.close) / 2, Hh.close, Hh.closeAlpha);
    // a page's subtitle, under its title: what the choice is capped at, and where it stands
    if (sh.subtitle) {
      words(c, sh.subtitle, tx, y + Hh.height + Hh.subtitleSize * RB.ascent, Hh.subtitleSize, Hh.subtitleAlpha);
      y += Hh.subtitleSize * (RB.ascent + RB.descent);
    }
    y += Hh.height + K.gap;
    // the footer stands on the panel's foot, whatever is above it, and the settings scroll in what
    // is left - a board shows them at the top of their scroll, cut where the viewport ends
    const foot = sh.footer || [];
    const hs = foot.map(b => b.t === 'button' ? (b.kind === 'secondary' ? SH.button.secondaryHeight : SH.button.height) : SH.footnote.size * (RB.ascent + RB.descent));
    const footTop = foot.length ? size[1] - K.padBottom - hs.reduce((a, b) => a + b, 0) - (foot.length - 1) * SH.footnote.gap : size[1] - K.padBottom;
    c.save(); c.beginPath(); c.rect(px0, y, pw, (foot.length ? footTop - K.gap : footTop) - y); c.clip();
    (sh.blocks || []).forEach(b => { y += block(c, b, x, y, w) + K.gap; });
    c.restore();
    let fy = footTop;
    foot.forEach((b, i) => { block(c, b, x, fy, w); fy += hs[i] + SH.footnote.gap; });
  }

  // The one surface that is not a panel at the edge: a card in the middle of the window, for the
  // ADB gate - the stock dialog, a lit plate's colour with a hairline round it. Its actions are
  // the stock dialog's: the one it exists for across the card, the quiet ones under it at equal
  // widths side by side, or each on its own line in a pane.
  function drawModal(c, mode, f) {
    const m = f.modal, full = mode === 'full', size = full ? S.head.full.size : S.head[mode].size;
    drawHead(c, mode, f);
    const bar = full ? 0 : S.head[mode].captionBar, M = SH.modal;
    over(c, () => { c.fillStyle = rgba([0, 0, 0], SH.scrim); c.fillRect(0, bar, size[0], size[1] - bar); });
    const outer = SH.compact.padX, pad = full ? M.pad : SH.compact.padX;
    const cw = Math.min(M.width, size[0] - 2 * outer), iw = cw - 2 * pad;
    const para = (str, px, a) => {
      const n = wrap(c, str, px, 400, iw).length;
      return { str, px, a, h: n ? px * RB.ascent + (n - 1) * px * SH.note.leading + px * RB.descent : 0 };
    };
    const msg = para(m.message, M.textSize, M.textAlpha), det = m.details ? para(m.details, SH.note.size, SH.note.alpha) : null;
    const quiet = m.quiet || [];
    // in a pane the quiet ones are the card's own lines, at the card's own gap
    const quietH = quiet.length ? (full ? SH.button.secondaryHeight : quiet.length * SH.button.secondaryHeight + (quiet.length - 1) * M.gap) : 0;
    const parts = [M.icon, msg.h].concat(det ? [det.h] : []).concat(m.primary ? [SH.button.height] : []).concat(quiet.length ? [quietH] : []);
    const ch = 2 * pad + parts.reduce((a, b) => a + b, 0) + (parts.length - 1) * M.gap;
    const cx = (size[0] - cw) / 2, cy = bar + (size[1] - bar - ch) / 2;
    fillRound(c, cx, cy, cw, ch, M.radius, SH.plate.color);
    over(c, () => { c.strokeStyle = rgba([255, 255, 255], SH.panel.edgeAlpha); c.lineWidth = 1; c.beginPath(); c.roundRect(cx + 0.5, cy + 0.5, cw - 1, ch - 1, M.radius - 0.5); c.stroke(); });
    const x = cx + pad; let y = cy + pad;
    const col = m.tone === 'attention' ? hex('#FF9F19') : [255, 255, 255];
    glyphAt(c, m.icon, x, y, M.icon, m.tone === 'attention' ? 1 : SH.header.closeAlpha, col, SH.plate.color);
    const ts = full ? M.titleSize : M.compactTitleSize;
    words(c, m.title, x + M.icon + M.iconGap, centred(y + M.icon / 2, ts), ts, 1, { w: 500 });
    y += M.icon + M.gap;
    y += paragraph(c, msg.str, x, y, iw, msg.px, SH.note.leading, msg.a) + M.gap;
    if (det) y += paragraph(c, det.str, x, y, iw, det.px, SH.note.leading, det.a) + M.gap;
    if (m.primary) y += button(c, { text: m.primary }, x, y, iw) + M.gap;
    if (quiet.length) {
      if (full) {
        const qw = (iw - (quiet.length - 1) * SH.footnote.gap) / quiet.length;
        quiet.forEach((q, i) => button(c, Object.assign({ kind: 'secondary' }, q), x + i * (qw + SH.footnote.gap), y, qw));
      } else quiet.forEach((q, i) => button(c, Object.assign({ kind: 'secondary' }, q), x, y + i * (SH.button.secondaryHeight + M.gap), iw));
    }
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
    else if (board.kind === 'sheet') drawSheet(c, board.mode, Object.assign({}, fixture, { page: board.page || 'sound' }));
    else if (board.kind === 'modal') drawModal(c, board.mode, Object.assign({}, fixture, { page: board.page || 'sound' }));
    else drawDigits(c);
  }

  root.Luminofor = { drawBoard, numWidth, spec: S };
})(window);
