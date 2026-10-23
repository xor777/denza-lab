/*
 * The ADAS overlay boards: which Navigator frame, which state, where.
 *
 * Frames are real screenshots under captures/ (git-ignored): the car's Navigator at night
 * without a route, the car's Navigator with a route in the two-thirds pane, and Navigator with a
 * route full width in the day theme (the disposable emulator - no full-width night frame with a
 * route was captured on the car). The one-third pane is cut from the car's two-thirds frame, the
 * speed pair moved in from its right edge. Anchors are each frame's free corner beside
 * Navigator's own route panel.
 */
(function (root) {
  'use strict';

  const FRAMES = {
    // Navigator on the car, night theme, browsing (no route): toolbar right, suggestions bottom
    car_idle: { src: 'captures/alice-navigator-20261011/live-semantic-button/before.png', w: 2560, h: 1600,
      A: { right: 2376, bottom: 1460 } },
    // Navigator on the car in the two-thirds pane with a route (media in the third)
    car_two: { src: 'captures/alice-navigator-20261011/product-live/screen-first-key.png', w: 2560, h: 1600,
      A: { right: 2512, bottom: 1448 }, B: { left: 904, top: 480 } },
    // the one-third pane: the same frame's left 832 px of Navigator, the speed pair moved in
    car_one: { src: 'captures/alice-navigator-20261011/product-live/screen-first-key.png', w: 832, h: 1358,
      crop: [880, 112, 832, 1358], pastes: [{ from: [2176, 296, 336, 176], to: [472, 184] }],
      A: { right: 808, bottom: 1194 } },
    // black, for laying an emulator screenshot of the overlay over its board (compare.py): no
    // frame, the anchor where the module's emulator fixture puts it
    bare: { src: null, w: 2560, h: 1600, A: { right: 2512, bottom: 1448 } },
    // Navigator full width with a route, day theme (emulator)
    nav_day: { src: 'captures/yandex-adas-prototype-20261023/hud-acceptance-clean/foreground.png', w: 2560, h: 1600,
      A: { right: 2520, bottom: 1510 }, B: { left: 24, top: 378 } },
  };

  const ok = [['Яндекс', '0,4'], ['ответ ADAS', '0,6']];
  const STATES = {
    idle: { noa: 'off', icc: 'available', mode: 'city', route: null },
    waiting: { noa: 'off', icc: 'available', mode: 'city', route: 'waiting' },
    available: { noa: 'available', icc: 'available', mode: 'city', route: 'matched' },
    active: { noa: 'active', icc: 'active', mode: 'city', route: 'matched' },
    activeOpen: { noa: 'active', icc: 'active', mode: 'city', route: 'matched', expanded: true, details: ok },
    iccOnly: { noa: 'off', icc: 'active', mode: 'city', route: 'matched' },
    stockEmpty: { noa: 'off', icc: 'active', mode: 'city', route: 'selected', alarm: 'stock-empty', expanded: true,
      details: [['Яндекс', '0,4'], ['штатная карта', '0,9', 'warn']] },
    yandexSilent: { noa: 'available', icc: 'available', mode: 'city', route: 'selected', alarm: 'yandex-silent', expanded: true,
      details: [['Яндекс', '7', 'warn'], ['ответ ADAS', '0,6']] },
    stale: { noa: 'active', icc: 'active', mode: 'city', route: 'matched', stale: 12, expanded: true,
      details: [['Яндекс', '0,4'], ['ответ ADAS', '12', 'warn']] },
    takeover: { noa: 'active', icc: 'active', mode: 'city', route: 'matched', takeover: 'Руки на руль' },
  };
  const OLD = {
    idle: ['NOA: неактивен', 'ICC: доступен', 'Режим: городской', 'Маршрут: нет ответа', 'Ответ: 0 с назад'],
    active: ['NOA: активен', 'ICC: активен', 'Режим: городской', 'Маршрут: подтверждён', 'Ответ: 0 с назад'],
    icc: ['NOA: неактивен', 'ICC: активен', 'Режим: городской', 'Маршрут: подтверждён', 'Ответ: 0 с назад'],
  };

  // a shot: one frame with an overlay. narrow for the one-third pane.
  const shot = (frame, state, o) => Object.assign({ frame, state }, o || {});
  // the 1:1 crop around an anchor's corner, w x h
  function around(f, place, w, h) {
    const a = FRAMES[f][place || 'A'];
    if (a.right != null) return [Math.min(FRAMES[f].w - w, a.right + 24 - w), Math.min(FRAMES[f].h - h, a.bottom + 22 - h), w, h];
    return [Math.max(0, a.left - 24), Math.max(0, a.top - 22), w, h];
  }
  const OLD_CROP = [1300, 120, 1248, 470];

  const BOARDS = {
    // the main display, full width
    'full-01-idle': { kind: 'full', shot: shot('car_idle', 'idle') },
    'full-02-noa-available': { kind: 'full', shot: shot('nav_day', 'available') },
    'full-03-noa-active': { kind: 'full', shot: shot('nav_day', 'active') },
    'full-04-icc-only': { kind: 'full', shot: shot('nav_day', 'iccOnly') },
    'full-05-stock-empty': { kind: 'full', shot: shot('nav_day', 'stockEmpty') },
    'full-06-stale': { kind: 'full', shot: shot('nav_day', 'stale') },
    'full-07-takeover': { kind: 'full', shot: shot('nav_day', 'takeover') },
    'full-08-yandex-silent': { kind: 'full', shot: shot('nav_day', 'yandexSilent') },
    'full-09-route-waiting': { kind: 'full', shot: shot('nav_day', 'waiting') },
    // split screen: Navigator in the two-thirds pane (the car's frame) and in the third
    'two-03-noa-active': { kind: 'full', shot: shot('car_two', 'active') },
    'two-05-stock-empty': { kind: 'full', shot: shot('car_two', 'stockEmpty') },
    'two-07-takeover': { kind: 'full', shot: shot('car_two', 'takeover') },
    'one-03-noa-active': { kind: 'full', shot: shot('car_one', 'active', { narrow: true }) },
    'one-05-stock-empty': { kind: 'full', shot: shot('car_one', 'stockEmpty', { narrow: true }) },
    'one-07-takeover': { kind: 'full', shot: shot('car_one', 'takeover', { narrow: true }) },
    // now and proposed: the frame, then a 1:1 piece of it
    'pair-1-route': { kind: 'pair', captions: ['Сейчас', 'Станет'],
      shots: [shot('nav_day', null, { old: OLD.active }), shot('nav_day', 'active')],
      crops: [OLD_CROP, around('nav_day', 'A', 1248, 470)] },
    'pair-2-idle': { kind: 'pair', captions: ['Сейчас', 'Станет'],
      shots: [shot('car_idle', null, { old: OLD.idle }), shot('car_idle', 'idle')],
      crops: [OLD_CROP, around('car_idle', 'A', 1248, 470)] },
    'pair-3-split': { kind: 'pair', captions: ['Сейчас', 'Станет'],
      shots: [shot('car_two', null, { old: OLD.active }), shot('car_two', 'active')],
      crops: [OLD_CROP, around('car_two', 'A', 1248, 470)] },
    'pair-4-stock-empty': { kind: 'pair', captions: ['Сейчас', 'Станет'],
      shots: [shot('car_two', null, { old: OLD.icc }), shot('car_two', 'stockEmpty')],
      crops: [OLD_CROP, around('car_two', 'A', 1248, 470)] },
    // every state at 1:1, as the driver sees it
    'sheet-states': { kind: 'sheet', tile: [820, 520], tiles: [
      ['Без маршрута', shot('car_idle', 'idle')],
      ['Маршрут ждёт', shot('nav_day', 'waiting')],
      ['NOA доступен', shot('nav_day', 'available')],
      ['NOA ведёт', shot('nav_day', 'active')],
      ['Только ICC', shot('nav_day', 'iccOnly')],
      ['Руки на руль', shot('nav_day', 'takeover')],
      ['Штатная: пусто', shot('nav_day', 'stockEmpty')],
      ['Яндекс молчит', shot('nav_day', 'yandexSilent')],
      ['ADAS молчит', shot('nav_day', 'stale')],
    ] },
    // every state alone on black at the bare anchor; one-* at one third (narrow)
    ...Object.fromEntries(['idle', 'waiting', 'available', 'active', 'iccOnly', 'stockEmpty', 'yandexSilent', 'stale', 'takeover']
      .map(k => ['bare-' + k, { kind: 'full', shot: shot('bare', k) }])),
    ...Object.fromEntries(['active', 'stockEmpty', 'takeover', 'stale']
      .map(k => ['bare-one-' + k, { kind: 'full', shot: shot('bare', k, { narrow: true }) }])),
    // the owner's choices
    'choice-1-placement': { kind: 'pair', captions: ['А · справа внизу', 'Б · под манёвром'],
      shots: [shot('nav_day', 'active'), shot('nav_day', 'active', { place: 'B' })] },
    'choice-2-detail': { kind: 'crops', captions: ['Свёрнуто', 'С источником'],
      shots: [shot('nav_day', 'active'), shot('nav_day', 'activeOpen')],
      crops: [around('nav_day', 'A', 1248, 470), around('nav_day', 'A', 1248, 470)] },
    'choice-3-idle': { kind: 'pair', captions: ['Тусклая строка', 'Ничего'],
      shots: [shot('car_idle', 'idle'), shot('car_idle', null)],
      crops: [around('car_idle', 'A', 1248, 470), around('car_idle', 'A', 1248, 470)] },
  };

  const SIZE = {
    full: b => [FRAMES[b.shot.frame].w, FRAMES[b.shot.frame].h],
    pair: b => [2560, b.crops ? 1400 : 906],
    crops: () => [2560, 600],
    sheet: () => [2560, 1840],
  };

  root.ADAS_BOARDS = { FRAMES, STATES, BOARDS, SIZE };
})(window);
