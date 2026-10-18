// Proposal, 2026-10-18: the HUD panel as the whole HUD system (docs/hud-output-architecture.md).
// Not normative yet - fixtures.js still holds today's sheet-hud and sheet-hud-navigator, and the
// app's code and SheetBoardContractTest follow those. When the owner accepts these boards they
// move into fixtures.js together with the panel's code, and the 'glass' block into luminofor.js,
// in one change.
//
// The panel opens with the glass: the projection as it looks during a route, in BYD's own layout
// (BydHud assets/resources/layout/sz_vechicleCfg.json from the FSE OTA: speed, limit sign, turn,
// lanes and map rectangles), each part named by a thin line. Under it one row for each named part,
// in the same words, saying where it comes from. A part that is off or has no source is an empty
// dashed outline in the picture and a row that says what it needs.
(function (root) {
  const [board, base] = root.LUMINOFOR_BOARDS['sheet-hud'];
  const panel = (blocks) => [board, Object.assign({}, base, {
    sheet: Object.assign({}, base.sheet, {
      title: 'HUD Подсказки', subtitle: 'Так выглядит проекция во время маршрута', blocks, footer: []
    })
  })];

  /* ------------------------------------------------------------ the glass */
  // Logical scene pixels of the SZ profile; the picture is the part of it the navigation uses.
  const L = {
    turn: [219, 360, 44, 44], distance: [269, 361, 109, 50], lanes: [176, 400, 276, 48],
    sign: [435, 334, 112, 112], speed: [563, 338, 155, 110], unit: [608, 459, 65, 32],
    map: [850, 269, 300, 180]
  };
  const VIEW = [150, 250, 1030, 262];        // x, y, w, h of the scene the picture shows
  const LABEL = 13, LABEL_ROOM = 22, BLUE = '#3388FF';
  const font = (w, px) => `${w} ${px}px Roboto, sans-serif`;
  function draw(c, fn) { c.save(); c.globalCompositeOperation = 'source-over'; fn(); c.restore(); }
  function dashed(c, r) {
    draw(c, () => {
      c.setLineDash([3, 3]); c.lineWidth = 1; c.strokeStyle = 'rgba(255,255,255,0.22)';
      c.beginPath(); c.roundRect(r[0] + 0.5, r[1] + 0.5, r[2] - 1, r[3] - 1, 3); c.stroke();
    });
  }
  // HUD Speed's warning as Denza Apps paints it: its tile, its frame with the limit, the metres
  function warning(c, x, y, h) {
    const t = h, g = h * 0.12;
    draw(c, () => {
      [0, t + g].forEach((dx, i) => {
        c.fillStyle = '#FFFFFF'; c.beginPath(); c.roundRect(x + dx, y, t, t, t * 0.18); c.fill();
        c.strokeStyle = '#FF2A2A'; c.lineWidth = Math.max(1, t * 0.09);
        c.beginPath(); c.roundRect(x + dx + c.lineWidth / 2, y + c.lineWidth / 2, t - c.lineWidth, t - c.lineWidth, t * 0.18); c.stroke();
        c.fillStyle = '#111111';
        if (i === 0) {                                   // a camera, as HUD Speed's tile has it
          c.fillRect(x + t * 0.22, y + t * 0.36, t * 0.56, t * 0.36);
          c.fillRect(x + t * 0.38, y + t * 0.27, t * 0.22, t * 0.1);
          c.fillStyle = '#FFFFFF'; c.beginPath(); c.arc(x + t * 0.5, y + t * 0.54, t * 0.11, 0, 7); c.fill();
        } else {
          c.font = font(700, t * 0.5); c.textAlign = 'center'; c.textBaseline = 'middle';
          c.fillText('60', x + dx + t / 2, y + t * 0.54);
        }
      });
      c.font = font(700, t * 0.46); c.textAlign = 'left'; c.textBaseline = 'middle';
      c.fillStyle = '#FFFFFF'; c.fillText('350 м', x + 2 * (t + g) + g, y + t * 0.54);
    });
  }
  function gps(c, x, y, s) {
    draw(c, () => {
      for (let i = 0; i < 4; i++) {
        const bh = s * (0.3 + 0.7 * (i + 1) / 4);
        c.fillStyle = '#1FD1C1'; c.fillRect(x + i * s * 0.26, y + s - bh, s * 0.18, bh);
      }
    });
  }
  function map(c, r, k, marks) {
    draw(c, () => {
      c.save(); c.beginPath(); c.roundRect(r[0], r[1], r[2], r[3], 3); c.clip();
      c.fillStyle = '#000000'; c.fillRect(r[0], r[1], r[2], r[3]);
      const X = v => r[0] + v * k, Y = v => r[1] + v * k;
      c.strokeStyle = 'rgba(190,196,204,0.55)'; c.lineWidth = 7 * k; c.lineCap = 'round';
      [[[0, 70], [300, 52]], [[40, 0], [70, 180]], [[230, 0], [260, 180]], [[0, 150], [120, 132]], [[190, 120], [300, 112]]]
        .forEach(([a, b]) => { c.beginPath(); c.moveTo(X(a[0]), Y(a[1])); c.lineTo(X(b[0]), Y(b[1])); c.stroke(); });
      c.strokeStyle = '#22E06A'; c.lineWidth = 10 * k;     // the route, ahead of the cursor
      c.beginPath(); c.moveTo(X(150), Y(180)); c.lineTo(X(150), Y(110)); c.quadraticCurveTo(X(150), Y(62), X(190), Y(58)); c.lineTo(X(300), Y(50)); c.stroke();
      if (marks) {                                         // Yandex's own camera mark, on the road ahead
        c.fillStyle = '#FFFFFF'; c.beginPath(); c.arc(X(214), Y(30), 15 * k, 0, 7); c.fill();
        c.strokeStyle = '#FF2A2A'; c.lineWidth = 3.4 * k; c.beginPath(); c.arc(X(214), Y(30), 13 * k, 0, 7); c.stroke();
        c.fillStyle = '#111111'; c.font = font(700, 13 * k); c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('60', X(214), Y(31));
      }
      c.fillStyle = '#FF7A1A'; c.beginPath();            // the cursor
      c.moveTo(X(150), Y(118)); c.lineTo(X(166), Y(162)); c.lineTo(X(150), Y(152)); c.lineTo(X(134), Y(162)); c.closePath(); c.fill();
      c.restore();
    });
  }
  // b: { turn, map, sign, cameras: 'map' | 'lanes' | false, gps }  - what is on the glass now
  function glass(c, b, x, y, w) {
    const k = w / VIEW[2], h = VIEW[3] * k, gy = y + LABEL_ROOM;
    const R = r => [x + (r[0] - VIEW[0]) * k, gy + (r[1] - VIEW[1]) * k, r[2] * k, r[3] * k];
    draw(c, () => { c.fillStyle = '#000000'; c.beginPath(); c.roundRect(x, gy, w, h, 12); c.fill(); });
    // the car's own speed, always there
    const sp = R(L.speed), un = R(L.unit);
    draw(c, () => {
      c.fillStyle = '#FFFFFF'; c.textAlign = 'center'; c.textBaseline = 'alphabetic';
      c.font = font(500, sp[3] * 0.78); c.fillText('64', sp[0] + sp[2] / 2, sp[1] + sp[3] * 0.84);
      c.globalAlpha = 0.7; c.font = font(400, un[3] * 0.72); c.fillText('км/ч', un[0] + un[2] / 2, un[1] + un[3] * 0.72);
    });
    const parts = [];
    const sg = R(L.sign);
    if (b.sign) draw(c, () => {
      const cx = sg[0] + sg[2] / 2, cy = sg[1] + sg[3] / 2, rr = sg[2] * 0.42;
      c.fillStyle = '#FFFFFF'; c.beginPath(); c.arc(cx, cy, rr, 0, 7); c.fill();
      c.strokeStyle = '#FF2A2A'; c.lineWidth = rr * 0.2; c.beginPath(); c.arc(cx, cy, rr * 0.9, 0, 7); c.stroke();
      c.fillStyle = '#111111'; c.font = font(700, rr * 0.95); c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('60', cx, cy + rr * 0.05);
    }); else dashed(c, sg);
    parts.push({ text: 'Ограничение', at: sg, up: true, on: !!b.sign });
    const tn = R(L.turn), ds = R(L.distance), turnBox = [tn[0], tn[1], ds[0] + ds[2] - tn[0], ds[3]];
    if (b.turn) draw(c, () => {
      c.strokeStyle = '#FFFFFF'; c.lineWidth = tn[2] * 0.14; c.lineCap = 'round'; c.lineJoin = 'round';
      c.beginPath(); c.moveTo(tn[0] + tn[2] * 0.3, tn[1] + tn[3]); c.lineTo(tn[0] + tn[2] * 0.3, tn[1] + tn[3] * 0.38);
      c.lineTo(tn[0] + tn[2] * 0.82, tn[1] + tn[3] * 0.38); c.stroke();
      c.beginPath(); c.moveTo(tn[0] + tn[2] * 0.62, tn[1] + tn[3] * 0.16); c.lineTo(tn[0] + tn[2] * 0.86, tn[1] + tn[3] * 0.38);
      c.lineTo(tn[0] + tn[2] * 0.62, tn[1] + tn[3] * 0.6); c.stroke();
      c.fillStyle = '#FFFFFF'; c.font = font(500, ds[3] * 0.62); c.textAlign = 'left'; c.textBaseline = 'middle';
      c.fillText('350 м', ds[0], ds[1] + ds[3] * 0.42);
    }); else dashed(c, turnBox);
    parts.push({ text: 'Поворот', at: turnBox, up: true, on: !!b.turn });
    const mp = R(L.map);
    if (b.map) map(c, mp, k, true); else dashed(c, mp);
    parts.push({ text: 'Карта', at: mp, up: true, on: !!b.map });
    // the cameras: at the bottom of the map window, or in the lanes window under the turn
    if (b.cameras === 'lanes') {
      const ln = R(L.lanes);
      warning(c, ln[0] + 6 * k, ln[1] + ln[3] - 34 * k, 34 * k);
      parts.push({ text: 'Камеры', at: [ln[0], ln[1], 124 * k, ln[3]], up: false, on: true });
    } else if (b.cameras === 'map') {
      warning(c, mp[0] + 6 * k, mp[1] + 140 * k, 34 * k);
      parts.push({ text: 'Камеры', at: [mp[0], mp[1] + 140 * k, 124 * k, 34 * k], up: false, on: true });
    } else {
      parts.push({ text: 'Камеры', at: [mp[0], mp[1] + 140 * k, 124 * k, 34 * k], up: false, on: false, dash: true });
      dashed(c, [mp[0] + 6 * k, mp[1] + 140 * k, 118 * k, 34 * k]);
    }
    if (b.gps && b.cameras) gps(c, mp[0] + 272 * k, mp[1] + 152 * k, 22 * k);
    // each part named by a thin line, above the glass or below it
    parts.forEach(p => {
      const cx = p.at[0] + p.at[2] / 2, a = p.on ? 0.9 : 0.4;
      const ly = p.up ? y + LABEL * 0.93 : gy + h + LABEL_ROOM - LABEL * 0.25;
      const from = p.up ? y + LABEL + 4 : gy + h + 2, to = p.up ? p.at[1] - 2 : p.at[1] + p.at[3] + 2;
      draw(c, () => {
        c.strokeStyle = BLUE; c.globalAlpha = p.on ? 0.8 : 0.35; c.lineWidth = 1;
        c.beginPath(); c.moveTo(cx + 0.5, from); c.lineTo(cx + 0.5, to); c.stroke();
        c.beginPath(); c.arc(cx + 0.5, to, 1.6, 0, 7); c.fillStyle = BLUE; c.fill();
        c.globalAlpha = a; c.fillStyle = '#FFFFFF'; c.font = font(500, LABEL); c.textAlign = 'center'; c.textBaseline = 'alphabetic';
        c.fillText(p.text, cx, ly);
      });
    });
    return LABEL_ROOM + h + LABEL_ROOM;
  }
  root.LUMINOFOR_PROPOSAL = { glass };

  /* ------------------------------------------------------------ the rows */
  const row = (title, summary, on, enabled, tone) =>
    Object.assign({ kind: 'switch', title, summary, on }, enabled === false ? { enabled } : {}, tone ? { tone } : {});
  const placement = (selected) => ({ t: 'section', label: 'Где показывать камеры',
    body: { t: 'segmented', labels: ['Внизу карты', 'Под поворотом'], selected } });
  const YANDEX = 'Из Яндекс Навигатора', HUD = 'Из YandexHUD';

  const boards = {
    // ordinary Yandex and HUD Speed with its adapter: everything on the glass
    'sheet-hudsys-yandex': panel([
      { t: 'glass', turn: true, sign: true, map: true, cameras: 'map', gps: true },
      { t: 'group', rows: [
        row('Повороты', YANDEX, true), row('Карта', YANDEX, true),
        row('Ограничение скорости', 'На знаке машины, из Яндекс Навигатора', true),
        row('Камеры', 'Из HUD Speed', true)] },
      placement(0)
    ]),
    // YandexHUD through Denza Apps, cameras under the turn
    'sheet-hudsys-yandexhud': panel([
      { t: 'glass', turn: true, sign: true, map: true, cameras: 'lanes', gps: true },
      { t: 'group', rows: [
        row('Повороты', HUD, true), row('Карта', HUD, true),
        row('Ограничение скорости', 'На знаке машины, из YandexHUD', true),
        row('Камеры', 'Из HUD Speed', true)] },
      placement(1),
      { t: 'note', text: 'Когда навигатор показывает полосы, камеры на это время уходят вниз карты.' }
    ]),
    // the ordinary HUD Speed from the store: it keeps its warnings on its own screen
    'sheet-hudsys-speed-official': panel([
      { t: 'glass', turn: true, sign: true, map: true, cameras: false },
      { t: 'group', rows: [
        row('Повороты', HUD, true), row('Карта', HUD, true),
        row('Ограничение скорости', 'На знаке машины, из YandexHUD', true),
        row('Камеры', 'Этот HUD Speed показывает их только у себя на экране. Нужна сборка с адаптером', false, false, 'attention')] }
    ]),
    // ordinary Yandex, no HUD Speed at all
    'sheet-hudsys-no-speed': panel([
      { t: 'glass', turn: true, sign: true, map: true, cameras: false },
      { t: 'group', rows: [
        row('Повороты', YANDEX, true), row('Карта', YANDEX, true),
        row('Ограничение скорости', 'На знаке машины, из Яндекс Навигатора', true),
        row('Камеры', 'Появятся, если поставить HUD Speed с адаптером', false, false)] }
    ]),
    // no navigator: the glass keeps the car's own speed, nothing to add to it
    'sheet-hudsys-no-navigator': panel([
      { t: 'glass', turn: false, sign: false, map: false, cameras: false },
      { t: 'group', rows: [
        row('Повороты', 'Появятся с Яндекс Навигатором или YandexHUD', true, false),
        row('Карта', 'Появится с Яндекс Навигатором или YandexHUD', true, false),
        row('Ограничение скорости', 'Появится с навигатором', true, false),
        row('Камеры', 'Из HUD Speed, во время маршрута в навигаторе', true, false)] }
    ]),
    // YandexHUD built before Denza Apps took the glass: it draws everything itself
    'sheet-hudsys-yandexhud-alone': panel([
      { t: 'glass', turn: true, sign: true, map: true, cameras: false },
      { t: 'group', rows: [
        row('Повороты', 'YandexHUD выводит сам', true, false), row('Карта', 'YandexHUD выводит сам', true, false),
        row('Ограничение скорости', 'На знаке машины, из YandexHUD', true),
        row('Камеры', 'Эта сборка YandexHUD не оставляет им места. В новой сборке они есть', false, false, 'attention')] }
    ])
  };
  // each state twice: with the glass, and as text and switches alone (<id>-text), to choose between
  Object.keys(boards).forEach(id => {
    const [b, scene] = boards[id];
    root.LUMINOFOR_BOARDS[id] = boards[id];
    root.LUMINOFOR_BOARDS[id + '-text'] = [b, Object.assign({}, scene, { sheet: Object.assign({}, scene.sheet, {
      subtitle: undefined, blocks: scene.sheet.blocks.filter(k => k.t !== 'glass') }) })];
  });
})(window);
