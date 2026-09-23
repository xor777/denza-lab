/*
 * The data each Luminofor board is drawn with. Deterministic: the same seed as the approved page,
 * frozen scenes instead of a simulation. The app's debug fixture mode feeds these same values to
 * its renderers (fixtures.json is generated from this file by shot.py), so a screenshot of the app
 * and the PNG of a board show the same numbers and can be laid one over the other.
 *
 * Strings are what the app prints for these values - the energy contract's words.
 */
(function (root) {
  'use strict';

  // the owner-shaped road: the approved page's seeded wander around 17 kWh/100 km
  let r = 7;
  const rnd = () => { r = (r * 16807) % 2147483647; return r / 2147483647; };
  const chart = [];
  let v = 16;
  for (let i = 0; i < 100; i++) {
    v += (rnd() - 0.5) * 6; v = v * 0.86 + 17 * 0.14;
    chart.push(+(v + (rnd() < 0.08 ? -rnd() * 26 : 0) + (i > 58 && i < 68 ? 14 : 0)).toFixed(2));
  }
  const generation = [];
  for (let i = 0; i < 24; i++) generation.push(+(10 + Math.sin(i / 3) * 3 + rnd() * 3).toFixed(2));

  // the analyser at one instant: the approved board's 26 heights spread over 36 bands
  const seed26 = [30, 55, 91, 133, 160, 182, 164, 140, 112, 88, 124, 153, 177, 159, 131, 98, 78, 110, 136, 155, 138, 108, 82, 58, 82, 105];
  const levels = [], crowns = [];
  for (let i = 0; i < 36; i++) {
    const p = i * 25 / 35, a = Math.floor(p), b = Math.min(25, a + 1), t = p - a;
    const h = (seed26[a] * (1 - t) + seed26[b] * t) / 203;
    levels.push(+h.toFixed(4));
    crowns.push(+Math.min(1, h + 0.03 + 0.07 * ((i * 0.618) % 1)).toFixed(4));
  }

  const TILES = [
    { name: 'Экран водителя', status: 'Приборы', on: true, icon: [['c', 11, 12, 9], ['c', 11, 12, 3], ['p', 'M2.2 10.5 L8.1 10.9 M19.8 10.5 L13.9 10.9 M11 15 L11 21']] },
    { name: 'Трансляция', status: 'Выбрано 6', on: true, icon: [['p', 'M2 20h0.01'], ['p', 'M2 16a4 4 0 0 1 4 4'], ['p', 'M2 12a8 8 0 0 1 8 8'], ['p', 'M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6']] },
    { name: 'Зеркала', status: 'Включены', on: true, icon: [['p', 'M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z'], ['c', 12, 12, 3]] },
    { name: 'Разделение', status: 'Включено', on: true, icon: [['r', 2, 4.5, 18, 15, 2], ['p', 'M11 4.5v15']] },
    { name: 'HUD Подсказки', status: 'Включены', on: true, icon: [['p', 'M3 13.5l6-6 6 6'], ['p', 'M2 19h14']] },
    { name: 'Погода', status: '+14°', on: true, icon: [['c', 7.6, 8, 3.2], ['p', 'M7.6 2.4v1.4M7.6 12.2v1.4M2 8h1.4M11.8 8h1.4M4.2 4.6l1 1M11 4.6l-1 1M4.2 11.4l1-1'], ['p', 'M16.6 20.5H9.1a3.5 3.5 0 0 1 0-7 4.6 4.6 0 0 1 8.7-1 3.9 3.9 0 0 1-1.2 8z']] },
    { name: 'Динамики', status: 'Включена', on: true, icon: [['r', 2, 3, 12, 18, 2], ['c', 8, 14, 3.2], ['c', 8, 7, 1.2]] },
    { name: 'Язык системы', status: 'Русский язык', on: false, icon: [['c', 11, 12, 9], ['p', 'M2.4 9.5h17.2M2.4 14.5h17.2'], ['p', 'M11 3a15 15 0 0 0 0 18 15 15 0 0 0 0-18z']] },
    { name: 'Экран справа', status: 'Не выбрано', on: false, icon: [['r', 2, 2.5, 14, 19, 2], ['p', 'M9 6.5v7'], ['p', 'M6 10.5l3 3 3-3'], ['p', 'M6 17.5h6']] },
    { name: 'Shortcuts', status: '3 настроены', on: true, icon: [['r', 3, 3, 7, 7, 2], ['r', 14, 3, 7, 7, 2], ['r', 3, 14, 7, 7, 2], ['p', 'm14.5 17.5 2 2 4-5']] },
    { name: 'Сервис', status: 'Всё в норме', on: false, icon: [['p', 'M2 7h16M2 12h16M2 17h16'], ['k', 13, 7, 2], ['k', 6, 12, 2], ['k', 15, 17, 2]] }
  ];

  const temps = (vals, states) => vals.map((x, i) => ({ value: String(x), state: (states || {})[i] || 'normal' }));

  // what the cluster prints, scene by scene
  const clusterBase = {
    t: 1.3,
    batteryCaption: 'БАТАРЕЯ · В',
    tripCaption: '42 км · ЗА ПОЕЗДКУ', tripKwh: '9,3', tripUnit: 'кВт·ч',
    iceCaption: 'ДВС · мин за поездку', iceFigure: '6',
    consumption: '17', consumptionUnit: 'кВт·ч/100 км · за 10 км',
    chart, generation
  };
  const scenes = {
    city:   Object.assign({}, clusterBase, { power: 26, peak: 34, peakAge: 1.0, volts: '549', temps: temps([28, 31, 29, 31, 32]) }),
    launch: Object.assign({}, clusterBase, { power: 196, peak: 196, peakAge: 0, volts: '532', temps: temps([30, 44, 41, 46, 51]) }),
    regen:  Object.assign({}, clusterBase, { power: -38, peak: -40, peakAge: 0.5, volts: '554', temps: temps([28, 31, 29, 31, 32]) }),
    engine: Object.assign({}, clusterBase, { power: -14, peak: -15, peakAge: 2.0, volts: '553', temps: temps([29, 38, 35, 36, 44]),
                                             iceCaption: 'ДВС · об/мин', iceFigure: '1650',
                                             engineGiving: true, engineCaption: 'ДВС ДАЁТ 14 кВт', engineWindow: 'ПОСЛЕДНИЕ 2:00' }),
    hot:    Object.assign({}, clusterBase, { power: 62, peak: 71, peakAge: 1.5, volts: '546',
                                             temps: temps([36, 88, 61, 63, 74], { 1: 'danger', 4: 'warning' }) }),
    park:   Object.assign({}, clusterBase, { power: 1, peak: 1, peakAge: 9, volts: '552', temps: temps([28, 31, 29, 31, 32]),
                                             consumption: '16,8', parked: true,
                                             gaveCaption: 'ДАЛ ДВС', gaveKwh: '1,1', regenCaption: 'РЕКУПЕРАЦИЯ', regenKwh: '3,1' }),
    // on the charger: power is -|CHARGE_KW|, the petal's figure becomes the countdown
    charging: Object.assign({}, clusterBase, { power: -7, peak: -7, peakAge: 9, volts: '561', temps: temps([27, 26, 25, 25, 29]),
                                               parked: true, gaveCaption: 'ДАЛ ДВС', gaveKwh: '1,1', regenCaption: 'РЕКУПЕРАЦИЯ', regenKwh: '3,1',
                                               consumption: '2:15', consumptionUnit: 'до полной' }),
    // the pack's cells drift apart: the spread appears under the battery, in its level's colour
    spread: Object.assign({}, clusterBase, { power: 31, peak: 38, peakAge: 1.2, volts: '547', temps: temps([34, 41, 39, 40, 46]),
                                             spread: { caption: 'РАЗБРОС ЯЧЕЕК', value: '32', unit: 'мВ', state: 'warning' } }),
    // a window still filling after a reset: 37 points, «за 3,7 км»
    filling: Object.assign({}, clusterBase, { power: 22, peak: 30, peakAge: 1.0, volts: '550', temps: temps([28, 31, 29, 31, 32]),
                                              chart: chart.slice(63), consumptionUnit: 'кВт·ч/100 км · за 3,7 км' }),
    // no access to the car: the skeleton and the message in the petal's place, nothing else
    unavailable: { t: 1.3, unavailable: true, message: 'ADB-ключ не подтверждён · Помощь → Диагностика', power: 0, peak: 0, peakAge: 9, chart: [] }
  };

  // what the head unit prints
  const head = {
    tiles: TILES,
    track: { artist: 'M83', title: 'Midnight City' },
    trip: [
      { cap: 'В пути', fig: '1:42', unit: '128 км' },
      { cap: 'Высота', fig: '642', unit: 'м', rate: '1,2' },
      { cap: 'Закат', fig: '19:44' }
    ],
    spectrum: { levels, crowns },
    power: { cap: 'Из батареи', fig: '26', unit: 'кВт' },
    volts: { cap: 'Напряжение', fig: '549', unit: 'В' },
    engine: { cap: 'ДВС за поездку', fig: '6', unit: 'мин' },
    tripCell: { cap: '42 км · за поездку', fig: '9,3', unit: 'кВт·ч' },
    temps: temps([28, 31, 29, 31, 32]),
    chart,
    consumptionCaption: 'Расход 16,9 кВт·ч/100 км · за 10 км'
  };
  const headEngine = Object.assign({}, head, {
    power: { cap: 'В батарею от ДВС', fig: '14', unit: 'кВт', dot: true, col: 'blue' },
    volts: { cap: 'Напряжение', fig: '553', unit: 'В' },
    engine: { cap: 'ДВС', fig: '1650', unit: 'об/мин' },
    temps: temps([29, 38, 35, 36, 44])
  });
  const headHot = Object.assign({}, head, {
    power: { cap: 'Из батареи', fig: '62', unit: 'кВт' },
    volts: { cap: 'Напряжение', fig: '546', unit: 'В' },
    temps: temps([36, 88, 61, 63, 74], { 1: 'danger', 4: 'warning' })
  });

  // board id -> [board, fixture]; px sizes are the displays' own
  root.LUMINOFOR_BOARDS = {
    'cluster-city':   [{ kind: 'cluster' }, scenes.city],
    'cluster-launch': [{ kind: 'cluster' }, scenes.launch],
    'cluster-regen':  [{ kind: 'cluster' }, scenes.regen],
    'cluster-engine': [{ kind: 'cluster' }, scenes.engine],
    'cluster-hot':    [{ kind: 'cluster' }, scenes.hot],
    'cluster-park':   [{ kind: 'cluster' }, scenes.park],
    'cluster-charging':    [{ kind: 'cluster' }, scenes.charging],
    'cluster-spread':      [{ kind: 'cluster' }, scenes.spread],
    'cluster-filling':     [{ kind: 'cluster' }, scenes.filling],
    'cluster-unavailable': [{ kind: 'cluster' }, scenes.unavailable],
    'main-sound':      [{ kind: 'head', mode: 'full', page: 'sound' }, head],
    'main-car':        [{ kind: 'head', mode: 'full', page: 'car' }, head],
    'main-car-engine': [{ kind: 'head', mode: 'full', page: 'car' }, headEngine],
    'main-car-hot':    [{ kind: 'head', mode: 'full', page: 'car' }, headHot],
    'two-sound':      [{ kind: 'head', mode: 'two', page: 'sound' }, head],
    'two-car':        [{ kind: 'head', mode: 'two', page: 'car' }, head],
    'one-sound':      [{ kind: 'head', mode: 'one', page: 'sound' }, head],
    'one-car':        [{ kind: 'head', mode: 'one', page: 'car' }, head],
    'digits':         [{ kind: 'digits' }, {}]
  };
})(window);
