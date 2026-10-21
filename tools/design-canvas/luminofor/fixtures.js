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
    { name: 'Shortcuts', status: '3 настроены', on: true, icon: [['r', 2, 3, 7, 7, 2], ['r', 13, 3, 7, 7, 2], ['r', 2, 14, 7, 7, 2], ['p', 'm13.5 17.5 2 2 4-5']] },
    { name: 'Облако', status: 'На связи', on: true, icon: [['p', 'M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24'], ['p', 'M12 13v8'], ['p', 'M8 17l4-4 4 4']] },
    { name: 'Сервис', status: 'Всё в норме', on: false, icon: [['p', 'M2 7h16M2 12h16M2 17h16'], ['k', 13, 7, 2], ['k', 6, 12, 2], ['k', 15, 17, 2]] }
  ];

  // A first run on a car that has not been set up: what the emulator shows on a fresh install.
  // Weather is fetching (working), Shortcuts could not be checked (broken), Service has a
  // function waiting on the driver (attention); the rest are off.
  const tone = (t, status, k) => Object.assign({}, t, { status, on: k !== 'idle', tone: k });
  const TILES_FIRST = [
    tone(TILES[0], 'Google Maps', 'idle'), tone(TILES[1], 'Выбрано 1', 'idle'), tone(TILES[2], 'Выключены', 'idle'),
    tone(TILES[3], 'Выключено', 'idle'), tone(TILES[4], 'Выключены', 'idle'), tone(TILES[5], 'Данных ещё нет', 'working'),
    tone(TILES[6], 'Выключена', 'idle'), tone(TILES[7], 'Русский язык', 'idle'), tone(TILES[8], 'Не выбрано', 'idle'),
    tone(TILES[9], 'Не проверено', 'broken'), tone(TILES[10], 'Выключено', 'idle'),
    tone(TILES[11], '1 функция ждёт', 'attention')
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
    // the last three hundred metres were the launch itself, far past the ceiling: one tick above
    launch: Object.assign({}, clusterBase, { power: 196, peak: 196, peakAge: 0, volts: '532', temps: temps([30, 44, 41, 46, 51]),
                                             chart: chart.slice(0, 97).concat([74, 118, 131]) }),
    // and a long regen under the floor: one tick below
    regen:  Object.assign({}, clusterBase, { power: -38, peak: -40, peakAge: 0.5, volts: '554', temps: temps([28, 31, 29, 31, 32]),
                                             chart: chart.slice(0, 97).concat([-14, -27, -33]) }),
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
    // the link lost past every horizon: the figures have left and their captions stayed, the
    // glyphs stand without their degrees, the beam is gone and the ten kilometres are still there -
    // closed road does not go stale - with their unit where the last figure left it
    stale: Object.assign({}, clusterBase, { power: 0, peak: 0, peakAge: 9, powerFresh: false, powerKnown: false,
                                            volts: null, temps: [0, 1, 2, 3, 4].map(() => ({ value: null, state: 'normal' })),
                                            iceFigure: null, tripCaption: 'ЗА ПОЕЗДКУ', tripKwh: null,
                                            consumption: null, consumptionHeld: '17' }),
    // the first seconds: nothing has answered yet, so there is nothing to caption - the axis alone
    waking: { t: 1.3, power: 0, peak: 0, peakAge: 9, powerFresh: false, powerKnown: false, heroUnit: false, temps: [], chart: [] },
    // no access to the car: the skeleton and the message in the petal's place, nothing else
    unavailable: { t: 1.3, unavailable: true, message: 'ADB-ключ не подтверждён · откройте Denza Apps', power: 0, peak: 0, peakAge: 9, chart: [] }
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

  // the analyser with nothing playing: every band on the floor, every crown down with it
  const silence = { levels: levels.map(() => 0), crowns: crowns.map(() => 0) };
  // first run: no track, silence, the trip just started and no location access
  const headFirst = Object.assign({}, head, {
    tiles: TILES_FIRST, track: null, spectrum: silence,
    trip: [{ cap: 'В пути', fig: '0:00', unit: '0 м' }, { cap: 'Нет доступа к геолокации', hint: true }]
  });
  // the track paused: its block at half its light behind the pause's bars, and the analyser down
  const headPaused = Object.assign({}, head, { track: { artist: 'M83', title: 'Midnight City', playing: false }, spectrum: silence });
  // coasting: no direction to name, so «Батарея» - and the figure stays white, as calm is
  const headNeutral = Object.assign({}, head, { power: { cap: 'Батарея', fig: '1', unit: 'кВт' } });
  // on an AC charger, parked
  const headCharging = Object.assign({}, head, {
    power: { cap: 'В батарею от зарядки', fig: '7', unit: 'кВт', dot: true, col: 'blue' },
    volts: { cap: 'Напряжение', fig: '561', unit: 'В' },
    temps: temps([27, 26, 25, 25, 29])
  });
  // the shell closed to us
  const headClosed = Object.assign({}, head, { unavailable: true, message: 'ADB-ключ не подтверждён · откройте Denza Apps' });

  // A feature's settings over the dashboard it came from. `sheet` is what the panel draws, block by
  // block, in the words the app prints for the state the debug build's SheetFixtures builds from
  // `state` - the two are one scene, and compare.py is what says so.
  const TAP = 'Короткое нажатие на плитку делает то же самое';
  const sheetOf = (tile, sheet, state) => Object.assign({}, head, { sheet: Object.assign({ icon: TILES[tile].icon, tone: 'live' }, sheet), state });
  // the driver's screen: this app's instruments, then every application the car can open
  const DIAL = [['p', 'M4 15a8 8 0 0 1 16 0'], ['p', 'M12 15l4.2-4.6'], ['p', 'M12.9 15a0.9 0.9 0 1 1-1.8 0a0.9 0.9 0 1 1 1.8 0']];
  const NAV = [{ name: 'Приборы', instruments: true, glyph: DIAL }, { name: 'Яндекс Навигатор', selected: true },
    { name: '2ГИС' }, { name: 'Waze' }, { name: 'Google Maps' }, { name: 'VK Видео' }, { name: 'Telegram' }];
  const CAST = [{ name: 'VK Видео', selected: true }, { name: 'Rutube', selected: true }, { name: 'YouTube' },
    { name: 'Кинопоиск' }, { name: 'Okko' }, { name: 'Wink' }, { name: 'Telegram' }, { name: 'Яндекс Музыка' }];
  const sheets = {
    cluster: sheetOf(0, {
      title: 'Экран водителя',
      blocks: [
        { t: 'group', rows: [{ kind: 'choice', title: 'Что показывать', icons: ['Яндекс Навигатор'], value: 'Яндекс Навигатор' }] },
        { t: 'section', label: 'Размещение', body: { t: 'segmented', labels: ['Полный', 'Слева', 'Центр', 'Справа'], selected: 0 } },
        { t: 'switch', title: 'Кнопка ★ на руле', on: true },
        { t: 'switch', title: 'Карта в правом окне', summary: 'Карта с HUD вместо штатной, когда правое окно приборки показывает карту', on: false },
        { t: 'note', text: 'На приборную панель за рулём встаёт что-то одно: приборы или любое приложение с машины. Короткое нажатие на плитку ставит его туда и убирает обратно.' }
      ],
      footer: [{ t: 'button', text: 'На приборку' }, { t: 'footnote', text: TAP }]
    }, { tile: 'CLUSTER', navigation: NAV, placements: ['FULL', 'LEFT', 'CENTER', 'RIGHT'], placement: 'FULL', wheel: true, mapCard: false, buttonLabel: 'На приборку' }),
    mirrors: sheetOf(2, {
      title: 'Зеркала',
      blocks: [
        { t: 'switch', title: 'Зеркала', on: true },
        { t: 'section', label: 'Где показывать', body: { t: 'segmented', labels: ['По сторонам', 'По центру'], selected: 0 } },
        { t: 'switch', title: 'Улучшение изображения', on: false },
        { t: 'note', text: 'Когда включён поворотник, на экране появляется камера с этой стороны и пропадает вместе с ним. «По центру» показывает обе камеры одну над другой.' }
      ],
      footer: [{ t: 'button', text: 'Проверить камеры' }]
    }, { tile: 'MIRRORS', mirrors: true, position: 'SIDES', processing: false }),
    simulcast: sheetOf(1, {
      title: 'Трансляция',
      blocks: [
        { t: 'switch', title: 'Поддержка трансляции', on: true },
        { t: 'group', rows: [{ kind: 'choice', title: 'Что транслировать', icons: ['VK Видео', 'Rutube'] }] },
        { t: 'note', text: 'Выбранные приложения показываются на пассажирском экране и на экране сзади. Запуск открывает их там сразу.' }
      ],
      footer: [{ t: 'button', text: 'Запустить' }, { t: 'footnote', text: TAP }]
    }, { tile: 'SIMULCAST', simulcast: true, apps: CAST }),
    // «Что показывать», the page the driver's screen row opens: the instruments over the car's
    // applications, one grid, a name over each run
    driverApps: sheetOf(0, {
      title: 'Что показывать', back: true,
      blocks: [{ t: 'appSections', runs: [
        { label: 'Функции приборов', items: NAV.filter(n => n.instruments) },
        { label: 'Приложения', items: NAV.filter(n => !n.instruments) }
      ] }]
      // no «Готово»: one answer at a time, and the tap that chooses is the tap that returns
    }, { tile: 'CLUSTER', navigation: NAV, placements: ['FULL', 'LEFT', 'CENTER', 'RIGHT'], placement: 'FULL', wheel: true, buttonLabel: 'На приборку', page: 'apps' }),
    castApps: sheetOf(1, {
      title: 'Что транслировать', back: true, subtitle: 'Можно выбрать до 6 · выбрано 2',
      blocks: [{ t: 'apps', items: CAST }],
      footer: [{ t: 'button', text: 'Готово' }]
    }, { tile: 'SIMULCAST', simulcast: true, apps: CAST, page: 'apps' }),
    speakers: sheetOf(6, {
      title: 'Динамики',
      blocks: [
        { t: 'switch', title: 'Автоуправление динамиками', on: true },
        { t: 'button', kind: 'secondary', text: 'Поднять' },
        { t: 'note', text: 'Динамики выезжают, когда играет музыка или открыт плеер — в том числе из приложений, которые машина своими не считает (Яндекс Музыка, Spotify, YouTube, Кинопоиск, штатный плеер). Убирает их машина сама. «Поднять» выдвигает их снова, если машина убрала их в простое.' }
      ]
    }, { tile: 'SPEAKERS', speakers: true }),
    defaults: sheetOf(9, {
      title: 'Приложения по умолчанию',
      blocks: [
        { t: 'switch', title: 'Заменять приложения', summary: 'Команды открывают выбранные приложения', on: true },
        { t: 'group', rows: [
          { kind: 'choice', title: 'Навигация', icons: ['Яндекс Навигатор'], value: 'Яндекс Навигатор' },
          { kind: 'choice', title: 'Музыка', icons: ['Яндекс Музыка'], value: 'Яндекс Музыка' },
          { kind: 'choice', title: 'Видео', icons: ['VK Видео'], value: 'VK Видео' }
        ] },
        { t: 'note', text: 'Штатные сценарии открывают приложения по команде: «Открыть навигацию» и «Открыть видео» запускают выбранные здесь. Музыку на этой прошивке начинает «Продолжить воспроизведение», когда ничего не играет; «Открыть музыку» всегда открывает штатный плеер.' }
      ],
      footer: [{ t: 'button', text: 'Готово' }]
    }, { tile: 'DEFAULT_APPS', substituting: true, roles: { NAVIGATION: 'Яндекс Навигатор', MUSIC: 'Яндекс Музыка', VIDEO: 'VK Видео' } }),
    locale: sheetOf(7, {
      title: 'Язык системы', tone: 'idle',
      blocks: [{ t: 'note', text: 'Язык меняется у всей машины, а не у приложения: список открывает сама машина, в нём сорок языков, и выбранный применяется сразу, без перезагрузки.' }],
      footer: [{ t: 'button', text: 'Выбрать язык' }, { t: 'footnote', text: TAP }]
    }, { tile: 'LOCALE' }),
    // a broken feature says so first, in the car's red, and keeps its switches under it
    broken: sheetOf(2, {
      title: 'Зеркала',
      blocks: [
        { t: 'status', tone: 'broken', text: 'Не переключилось' },
        { t: 'switch', title: 'Зеркала', on: true },
        { t: 'section', label: 'Где показывать', body: { t: 'segmented', labels: ['По сторонам', 'По центру'], selected: 0 } },
        { t: 'switch', title: 'Улучшение изображения', on: false },
        { t: 'note', text: 'Когда включён поворотник, на экране появляется камера с этой стороны и пропадает вместе с ним. «По центру» показывает обе камеры одну над другой.' }
      ],
      footer: [{ t: 'button', text: 'Проверить камеры' }]
    }, { tile: 'MIRRORS', mirrors: true, position: 'SIDES', processing: false, error: 'Не переключилось' }),
  };

  // The service panel answers one question - what is wrong - and keeps the rest a row away. On a
  // healthy car it is two quiet rows; a feature that needs somebody is a row in its tile's colour
  // that opens its panel; the car's access grows its buttons only when there is no access. The
  // instruments' screen and the technical readings are pages, and the version is the foot.
  const SVC_VERSION = { t: 'footnote', text: 'Denza Apps 0.6.2 · сборка 53' };
  // the restore's row says what it did after the last boot, in its colour when it needs somebody.
  // «Диагностика» is parked: its row exists only in a `-Pdiagnostics` build, not here.
  const svcMore = (restore, tone) => ({ t: 'group', rows: [
    { kind: 'choice', title: 'Восстановление ADB', summary: restore, tone },
    { kind: 'choice', title: 'Мобильный интернет', summary: 'Штатный контроль трафика' },
    { kind: 'choice', title: 'Предупреждения машины', summary: 'Последнее: 13F · сегодня, 14:01' },
    { kind: 'choice', title: 'Приборный экран', summary: 'Определён сам: Экран 1 · 1920×720' },
    { kind: 'choice', title: 'Технические сведения', summary: 'Версия, прошивка, состояние функций' }
  ] });
  const SVC_MORE = svcMore('После перезагрузки не понадобилось');
  const SVC_ACCESS = { title: 'Доступ к машине', summary: 'ADB-доступ подтверждён' };
  // The report the technical page is read from - SupportDiagnostics, the cloud first: a line
  // `[Название]` opens a section, every other line is `key=value` in it, split on the first '='.
  // TechnicalReadings.parse in the app is this function.
  const SVC_TECHNICAL = [
    '[Облако]',
    'Связь=включена, плитка «На связи»',
    'Отказ=нет',
    'Сеть=Wi-Fi, интернет проверен',
    'Wi-Fi / сотовая=да / нет',
    'SIM=нет',
    'Профиль=double_apn, сборки triple_apn, APN1 выключен',
    'Сотовая BYD=нет',
    'cloudmanager=PID 113, TCP 1',
    'Wi-Fi во сне=да',
    'Шлюз=OPENED, попыток 1',
    'Последний ready=4 мин назад',
    'Без связи=—',
    'Прочитано=12 с назад',
    '[Приложение]',
    'Версия=0.6.2 · сборка 53',
    'Android=13 · SDK 33',
    'Прошивка=BYD/IVI/DiLink5_1:13/34.1.33.2605218/1:user/release-keys',
    '[Доступ к машине]',
    'Состояние=trusted',
    'Отладка ADB в машине=включено',
    'Запрос ждёт ответа=нет',
    // the sections between - projection, mirrors, the driver's screen - are left out, as the ones
    // after are. The split's: a car on another firmware whose open did not go, the in-process calls
    // refused (2026-09-24)
    '[Разделение экрана]',
    'Состояние=active',
    'Последнее открытие=20:34 · не вышло · 3,1 с',
    'Сплит прошивки=две панели · область 0',
    'Сигналы прошивки=Home да · область да · вызовы нет'
  ];
  // Blocks from the report. `under` puts blocks beneath a section's readings, a label's gap below
  // them in its column - the way to the split's journal is a pressed row, not a reading.
  const techBlocks = (lines, under) => {
    const blocks = []; let section = null;
    lines.forEach(line => {
      const m = /^\[(.+)\]$/.exec(line);
      if (m) {
        const rows = { t: 'group', rows: [] }, more = (under && under[m[1]]) || [];
        blocks.push({ t: 'section', label: m[1], body: more.length ? [rows].concat(more) : rows });
        section = rows; return;
      }
      if (!section) { section = { t: 'group', rows: [] }; blocks.push(section); }
      const i = line.indexOf('='), key = i < 0 ? line : line.slice(0, i), value = i < 0 ? '—' : (line.slice(i + 1) || '—');
      section.rows.push({ kind: 'pair', title: key, value });
    });
    return blocks;
  };
  const SVC_JOURNAL_ROW = { 'Разделение экрана': [{ t: 'group', rows: [
    { kind: 'choice', title: 'Журнал работы', summary: 'Три последние операции, по шагам' }
  ] }] };
  // «Журнал работы»: the split's last three operations from its journal, newest first - the newest
  // step by step, milliseconds after its first line, the two before it by their end alone - in the
  // report's format, drawn by the report's rows (SplitWorkJournal.page in the app)
  const SVC_JOURNAL = [
    '[Открытие 20:34:02 · не вышло · 3,1 с]',
    '+0 мс=dequeued',
    '+131 мс=scene-read: area=0',
    '+433 мс=leases-taken',
    '+590 мс=roots-started',
    '+3104 мс=open: обращений 23, в shell 2.4 с, транспорт (очередь 0.0, отправка 0.1, ответ 2.2), разбор 0.0 с, в паузах 0.3 с',
    'итог=outcome=rolled-back reason=Прошивка не раскрыла native split',
    '[Выбор 20:33:40 · готово · 1,2 с]',
    'итог=outcome=committed reason=-',
    '[Открытие 20:33:21 · готово · 1,9 с]',
    'итог=outcome=committed reason=-'
  ];
  // «Предупреждения машины»: the cluster's warning slot as the app records it - every change, every
  // start of the app, every time the car stopped answering - and every code over the whole record,
  // the latest first. The board's year is 2026 and the panel is opened at 21.10 14:02:11; the names
  // are the car's dictionary through warnings/popup_ru.tsv. WarningText in the app writes every word
  // drawn below from this record (WarningBoardContractTest).
  const WARN_NAMES = {
    '13F': 'Состояние автомобиля (люк, двери, капот и багажник, окна, ремни безопасности)',
    '7D': 'Ключ не обнаружен, убедитесь, что ключ находится в автомобиле'
  };
  const WARN_EVENTS = [
    ['21.10 14:01:37', 'code', '0'],
    ['21.10 14:01:22', 'code', '13F'],
    ['21.10 14:00:05', 'link', '0'],
    ['21.10 13:59:58', 'start', ''],
    ['20.10 19:40:11', 'lost', ''],
    ['20.10 19:12:30', 'code', '0'],
    ['20.10 19:12:24', 'code', '7D'],
    ['20.10 19:05:16', 'link', '0'],
    ['20.10 19:05:09', 'start', '']
  ];
  const WARN = { now: '21.10 14:02:11', enabled: true, since: '19.10 08:02:10', linked: true, lostAt: '',
    sightings: [['13F', '19.10 08:05:40', '21.10 14:01:22', 14], ['7D', '20.10 08:40:02', '20.10 19:12:24', 2]],
    events: WARN_EVENTS, names: WARN_NAMES };
  const svcState = { tile: 'SERVICE', warnings: WARN, adb: 'ADB-доступ подтверждён', adbDetails: 'Denza Apps использует уже доверенный ключ',
    cluster: 'Определён сам: Экран 1 · 1920×720', displays: [[2, 1920, 720]], version: '0.6.2', build: 53, technical: SVC_TECHNICAL,
    // every scene of the panel carries the journal, so the debug build's row opens the page
    journal: SVC_JOURNAL };
  // «Восстановление ADB»: the answer, the switch, and what the last boot did - the port open at the
  // first look; closed and opened again by the app at 08:11; closed, with no Wi-Fi to restore over.
  // `adbRestore` is the record in times of the day, «Сервис» opened at 14:02:11 (SheetFixtures).
  const restorePage = (status, rows, tone, adbRestore) => sheetOf(11, {
    title: 'Восстановление ADB', back: true,
    blocks: [
      { t: 'status', text: status, tone },
      { t: 'switch', title: 'Восстанавливать автоматически', on: true,
        summary: 'Если после перезагрузки машина закроет доступ, приложение вернёт его через Wi-Fi' },
      { t: 'section', label: 'Последняя перезагрузка', body: { t: 'group', rows: rows.map(([title, value]) => ({ kind: 'pair', title, value })) } }
    ]
  }, Object.assign({}, svcState, { page: 'restore', adbRestore }));
  const mobilePage = (state, error, busy = false, result) => sheetOf(11, {
    title: 'Мобильный интернет', back: true,
    blocks: [
      { t: 'group', rows: [{ title: 'Штатный контроль трафика',
        summary: busy ? (['ENABLED', 'DISABLED'].includes(state) ? 'Выполняем команду…' : 'Проверяем состояние…')
          : state === 'ENABLED' ? 'Монитор включён' : state === 'DISABLED' ? 'Монитор отключён'
          : state === 'ABSENT' ? 'Недоступен на этой прошивке' : 'Не удалось проверить' }] },
      ...(['ENABLED', 'DISABLED'].includes(state) ? [{ t: 'button', kind: 'secondary',
        text: 'Отключить', enabled: state === 'ENABLED' && !busy }] : []),
      ...(state === 'UNKNOWN' && !busy ? [{ t: 'button', kind: 'secondary', text: 'Проверить состояние' }] : []),
      ...(error ? [{ t: 'status', text: error, tone: 'broken' }] : result ? [{ t: 'status', text: result }] : [])
    ]
  }, Object.assign({}, svcState, { page: 'mobile', trafficMonitor: { state, error, busy, result } }));
  const warnWords = (kind, code) => kind === 'start' ? 'Приложение запущено'
    : kind === 'lost' ? 'Машина не отвечает' : kind === 'off' ? 'Запись выключена' : kind === 'on' ? 'Запись включена'
    : kind === 'cleared' ? 'Журнал очищен'
    : kind === 'link' ? (code === '0' ? 'Машина отвечает, предупреждений нет' : 'Машина отвечает · ' + code + ' · ' + WARN_NAMES[code])
    : code === '0' ? 'Предупреждение снято' : code + ' · ' + WARN_NAMES[code];
  // the status, the switch, the codes, the record's two buttons, then the events newest first
  const warningsPage = (status, tone, record) => sheetOf(11, {
    title: 'Предупреждения машины', back: true,
    blocks: [
      { t: 'status', text: status, tone },
      { t: 'switch', title: 'Записывать предупреждения', on: record.enabled,
        summary: 'Сообщения приборной панели и связь с машиной, пока работает приложение' },
      { t: 'section', label: 'Коды', body: { t: 'group', rows: [
        { title: '13F · сегодня, 14:01', summary: '14 раз · ' + WARN_NAMES['13F'] },
        { title: '7D · вчера, 19:12', summary: '2 раза · ' + WARN_NAMES['7D'] }
      ] } },
      { t: 'stack', gap: 12, items: [
        { t: 'button', kind: 'secondary', text: 'Экспортировать' },
        { t: 'button', kind: 'secondary', text: 'Очистить' }
      ] },
      { t: 'section', label: 'События', body: { t: 'group',
        rows: record.events.map(([at, kind, code]) => ({ kind: 'pair', title: at, value: warnWords(kind, code) })) } }
    ]
  }, Object.assign({}, svcState, { page: 'warnings', warnings: record }));
  const service = {
    warnings: warningsPage('Запись идёт с 19.10, 08:02', undefined, WARN),
    warningsOff: warningsPage('Запись выключена', undefined, Object.assign({}, WARN, { enabled: false,
      events: [['21.10 14:01:50', 'off', '']].concat(WARN_EVENTS) })),
    warningsLost: warningsPage('Машина не отвечает с 14:01', 'attention', Object.assign({}, WARN, { linked: false,
      lostAt: '21.10 14:01:45', events: [['21.10 14:01:45', 'lost', '']].concat(WARN_EVENTS) })),
    mobile: mobilePage('ENABLED'),
    mobileOff: mobilePage('DISABLED', undefined, false, 'Отключение подтверждено'),
    mobileUnknown: mobilePage('UNKNOWN'),
    mobileAbsent: mobilePage('ABSENT'),
    mobileFailed: mobilePage('ENABLED', 'Не удалось отключить монитор'),
    mobileBusy: mobilePage('ENABLED', undefined, true),
    restore: restorePage('Доступ есть, восстанавливать не нужно',
      [['Когда', 'сегодня, 08:10'], ['Доступ после неё', 'был сразу'], ['Восстановление', 'не понадобилось']]),
    restored: restorePage('Доступ есть: после перезагрузки его вернуло приложение',
      [['Когда', 'сегодня, 08:10'], ['Доступ после неё', 'был закрыт'], ['Восстановление', 'удалось в 08:11, за 23 с']],
      undefined, { look: 'CLOSED', outcome: 'RESTORED', closed: '08:10:40', outcomeAt: '08:11:03', tls: 37451, dialog: 'allowed' }),
    restoreWifi: restorePage('Доступа нет. Вернётся, как только машина подключится к Wi-Fi',
      [['Когда', 'сегодня, 08:10'], ['Доступ после неё', 'был закрыт'], ['Восстановление', 'ждёт Wi-Fi']], 'attention',
      { state: 'WaitingWifi', look: 'CLOSED', outcome: 'WAITING_WIFI', closed: '08:10:40', outcomeAt: '08:10:41', wifi: false }),
    ok: sheetOf(11, {
      title: 'Сервис',
      blocks: [{ t: 'group', rows: [{ title: 'Все функции работают' }, SVC_ACCESS] }, SVC_MORE],
      footer: [SVC_VERSION]
    }, Object.assign({}, svcState, { page: 'main' })),
    trouble: sheetOf(11, {
      title: 'Сервис',
      blocks: [
        { t: 'status', tone: 'attention', text: '2 функции ждут' },
        { t: 'group', rows: [
          { kind: 'choice', title: 'HUD Подсказки', summary: 'Нет доступа', tone: 'attention' },
          { kind: 'choice', title: 'Облако', summary: 'Не включилось', tone: 'broken' },
          SVC_ACCESS
        ] },
        SVC_MORE
      ],
      footer: [SVC_VERSION]
    }, Object.assign({}, svcState, { page: 'main', trouble: true })),
    access: sheetOf(11, {
      title: 'Сервис',
      blocks: [
        { t: 'status', tone: 'broken', text: 'Нет доступа к машине, без него большинство функций не работает' },
        { t: 'group', rows: [{ title: 'Доступ к машине', summary: 'Нужно разрешение ADB для Denza Apps', tone: 'broken' }] },
        { t: 'note', text: 'Можно вручную отправить ровно один запрос' },
        { t: 'stack', gap: 12, items: [
          { t: 'button', text: 'Отправить один запрос' },
          { t: 'button', kind: 'secondary', text: 'Проверить доступ' }
        ] },
        // a key the car refuses is one the restore cannot use either
        svcMore('Не может вернуть доступ', 'broken')
      ],
      footer: [SVC_VERSION]
    }, Object.assign({}, svcState, { page: 'main', adb: 'Нужно разрешение ADB для Denza Apps', adbDetails: 'Можно вручную отправить ровно один запрос', adbPhase: 'AUTHORIZATION_REQUIRED',
      adbRestore: { state: 'NeedsActivation', look: 'REFUSED', outcome: 'NEEDS_ACTIVATION', closed: '08:10:40', outcomeAt: '08:10:40' } })),
    screen: sheetOf(11, {
      title: 'Приборный экран', back: true,
      blocks: [
        { t: 'group', rows: [
          { kind: 'chosen', title: 'Определять автоматически', summary: 'Сейчас: Экран 1 · 1920×720', chosen: true },
          { kind: 'chosen', title: 'Экран 1 · 1920×720' }
        ] },
        { t: 'note', text: 'Приложение само находит экран за рулём. Выберите другой, если приборы ушли не туда.' }
      ]
    }, Object.assign({}, svcState, { page: 'screen' })),
    tech: sheetOf(11, { title: 'Технические сведения', back: true, subtitle: 'Denza Apps 0.6.2 · сборка 53', blocks: techBlocks(SVC_TECHNICAL, SVC_JOURNAL_ROW) },
      Object.assign({}, svcState, { page: 'technical' })),
    // the same page scrolled to its end, where the split's section and the way to its journal are:
    // what an owner on another firmware photographs (the header scrolls away with the rows, as the
    // app's column does)
    split: sheetOf(11, { title: 'Технические сведения', back: true, subtitle: 'Denza Apps 0.6.2 · сборка 53', blocks: techBlocks(SVC_TECHNICAL, SVC_JOURNAL_ROW), scroll: 'end' },
      Object.assign({}, svcState, { page: 'technical', scroll: 'end' })),
    journal: sheetOf(11, { title: 'Журнал работы', back: true, subtitle: 'Разделение экрана', blocks: techBlocks(SVC_JOURNAL) },
      Object.assign({}, svcState, { page: 'journal' }))
  };

  // the ADB gate asking for the car's permission: the service's glyph in orange because there is a
  // recovery to offer, the request across the card, the recovery and the explainer under it
  const gate = Object.assign({}, head, {
    modal: {
      icon: TILES[11].icon, tone: 'attention', title: 'Подтвердите доступ к ADB',
      message: 'Для работы Denza Apps разрешите системный запрос ADB на экране автомобиля',
      details: 'Отладка по ADB включена в системе автомобиля',
      primary: 'Запросить доступ',
      quiet: [{ text: 'Восстановить ADB', attention: true }, { text: 'Что такое ADB' }]
    },
    state: { gate: 'AUTHORIZATION_REQUIRED', systemSwitch: 'ENABLED' }
  });
  const wifiGate = Object.assign({}, head, {
    modal: {
      icon: TILES[11].icon, tone: 'idle', title: 'Ожидание Wi-Fi',
      message: 'Нужен Wi-Fi для восстановления доступа',
      details: 'Android восстанавливает беспроводную отладку при подключении к Wi-Fi',
      quiet: [{ text: 'Что такое ADB' }]
    },
    state: { gate: 'UNAVAILABLE', restore: 'WaitingWifi' }
  });

  // the cloud link: a switch whose reason is a warning, on two lines rather than cut short
  sheets.cloud = sheetOf(10, {
    title: 'Облако',
    blocks: [
      { t: 'switch', title: 'Поддерживать связь с облаком', on: true },
      { t: 'switch', title: 'Держать Wi-Fi включенным', summary: 'На стоянке аккумулятор может разряжаться быстрее', on: false },
      { t: 'note', text: 'Машина выходит в облако через Wi-Fi или мобильный интернет, и приложение Denza на телефоне работает с ней как обычно: показывает состояние и управляет машиной. Связь восстанавливается, если модем видит китайский ICCID — от родной eSIM или от rSIM. «Держать Wi-Fi включенным» не даёт машине выключать Wi-Fi, когда она засыпает, — связь остаётся и на стоянке.' }
    ]
  }, { tile: 'CLOUD', cloud: true, wifi: false });

  // «HUD Подсказки» (docs/hud-output-architecture.md, 12): the glass as it looks during a route,
  // then one switch for each part of it, saying where it comes from. state carries what the panel
  // is made of - what is installed and the switches - and HudPanelBoardContractTest holds every one
  // of these boards to HudPanel.of(state).
  const hudRow = (title, summary, on, enabled, tone) =>
    Object.assign({ kind: 'switch', title, summary, on }, enabled === false ? { enabled } : {}, tone ? { tone } : {});
  const hudSheet = (blocks, state) => sheetOf(4, { title: 'HUD Подсказки', subtitle: 'Так выглядит проекция во время маршрута', blocks },
    Object.assign({ tile: 'HUD', hud: true, map: true, sign: true, cameras: true, navigator: 'YANDEX', radar: 'ADAPTED' }, state));
  const YANDEX = 'Из Яндекс Навигатора', YHUD = 'Из YandexHUD', SELF = 'YandexHUD выводит сам';
  // ordinary Yandex and HUD Speed with its adapter: everything on the glass, the cameras under the
  // turn and only their GPS bars on the map
  sheets.hud = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: true },
    { t: 'group', rows: [hudRow('Манёвры', YANDEX, true), hudRow('Карта', YANDEX, true),
      hudRow('Ограничение скорости', 'На штатном месте, из Яндекс Навигатора', true), hudRow('Камеры и опасности', 'Из HUD Speed', true)] }
  ], {});
  // YandexHUD through this app's host: the same glass, the lines saying YandexHUD
  sheets.hudYandexHud = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: true },
    { t: 'group', rows: [hudRow('Манёвры', YHUD, true), hudRow('Карта', YHUD, true),
      hudRow('Ограничение скорости', 'На штатном месте, из YandexHUD', true), hudRow('Камеры и опасности', 'Из HUD Speed', true)] }
  ], { navigator: 'YANDEX_HUD' });
  // the ordinary HUD Speed from the store: said as it is, in the car's orange, no error
  sheets.hudSpeedOfficial = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: false },
    { t: 'group', rows: [hudRow('Манёвры', YHUD, true), hudRow('Карта', YHUD, true),
      hudRow('Ограничение скорости', 'На штатном месте, из YandexHUD', true),
      hudRow('Камеры и опасности', 'Этот HUD Speed показывает их только у себя на экране. Нужна сборка с адаптером', false, false, 'attention')] }
  ], { navigator: 'YANDEX_HUD', radar: 'OFFICIAL' });
  // no HUD Speed at all
  sheets.hudNoSpeed = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: false },
    { t: 'group', rows: [hudRow('Манёвры', YANDEX, true), hudRow('Карта', YANDEX, true),
      hudRow('Ограничение скорости', 'На штатном месте, из Яндекс Навигатора', true),
      hudRow('Камеры и опасности', 'Появятся, если поставить HUD Speed с адаптером', false, false)] }
  ], { radar: 'NONE' });
  // no navigator: the glass keeps the car's own speed, and every line says what it waits for
  sheets.hudNoNavigator = hudSheet([
    { t: 'glass', turn: false, sign: false, map: false, cameras: false },
    { t: 'group', rows: [hudRow('Манёвры', 'Появятся с Яндекс Навигатором или YandexHUD', true, false),
      hudRow('Карта', 'Появится с Яндекс Навигатором или YandexHUD', true, false),
      hudRow('Ограничение скорости', 'Появится с навигатором', true, false),
      hudRow('Камеры и опасности', 'Из HUD Speed, во время маршрута в навигаторе', true, false)] }
  ], { navigator: 'NONE' });
  // a YandexHUD built before the host draws the HUD itself: this app keeps the car's sign, and the
  // sign's switch is the panel's one
  sheets.hudYandexHudAlone = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: false },
    { t: 'group', rows: [hudRow('Манёвры', SELF, true, false), hudRow('Карта', SELF, true, false),
      hudRow('Ограничение скорости', 'На штатном месте, из YandexHUD', true),
      hudRow('Камеры и опасности', 'Эта сборка YandexHUD не оставляет им места. В новой сборке они есть', false, false, 'attention')] }
  ], { navigator: 'YANDEX_HUD_ALONE' });

  /*
   * «Сервис → Диагностика», the scanner (scanner.js): the scenes of «Сканер (v3)». The autoscan,
   * the blocks and the systems are the car's own reads of 2026-10-18/20
   * (research/vehicle-diagnostics/obd-20261020.json, remaining-ecus-20261020.json, the parked
   * sweep of 2026-10-20); the ECM's live data and its fault are illustrative and say so in their
   * footers. ≈ marks a role read from the update cache's abbreviation, not from the block itself.
   */
  const scanTop = (crumbs, time, car) => ({ crumbs: ['Диагностика'].concat(crumbs), link: 'Связь OK', car: car || 'Z9 GT DM · P · 0 км/ч', time });
  // [name, unit, abbr, short, request address, protocol, kind, active, history]; a block with an
  // address opens its own screen
  const ecu = (name, unit, abbr, short, req, proto, kind, active, history) => {
    const e = { name, unit, abbr, short, req: req || null, proto: proto || null, kind };
    if (kind === 'ok' || kind === 'hist') { e.active = active == null ? '0' : String(active); e.history = history == null ? '0' : String(history); }
    if (req) e.opens = true;
    return e;
  };
  // the parked scan of 2026-10-20 19:31: all 68 answered and all 68 memories read; history in
  // LA_BCM 8, R_ZCU 2, INS 2, Media 1, CCS/SCPA 1 and IDC 4. Names and groups are ecu/EcuCatalog.kt's
  // after the roles of 2026-10-21 (findings, «ECU roles and reverse catalog crosswalk»).
  const uds = (name, unit, abbr, short, req, history) => ecu(name, unit, abbr, short, req, 'UDS', history ? 'hist' : 'ok', 0, history || 0);
  const autoscan = {
    top: scanTop(['Автоскан'], '19:31:29'),
    counters: [['ОТВЕТИЛО', '68/68', 'white'], ['DTC АКТИВНЫХ', '0', 'white'], ['DTC В ИСТОРИИ', '18', 'text'], ['ПАМЯТЬ ПРОЧИТАНА', '68/68', 'text'], ['MIL', 'ВЫКЛ', 'text']],
    tabs: ['Список', 'Топология'],
    headUnit: ['ГУ · диагностика', '+8'],
    groups: [
      { id: 'power', title: 'СИЛОВАЯ УСТАНОВКА', short: 'Силовая', topo: 'СИЛОВАЯ', ecus: [
        ecu('ЭБУ двигателя', 'ECM · UAES', 'ECM', 'Двигатель', '7E0', 'OBD-II, UDS', 'ok', 0, 0),
        ecu('Контроллер силовой установки', 'VCU', 'VCU', 'Силовая уст.', '7E2', 'OBD-II, UDS', 'ok', 0, 0),
        uds('Контроллер силового домена', 'PDC-VCU', 'PDC-VCU', 'Силовой домен', '743'),
        uds('Контроллер переднего мотора', 'FMCU', 'FMCU', 'Перед. мотор', '7E3'),
        uds('Контроллер мотор-генератора', 'ISG', 'ISG', 'Мотор-генер.', '7E6'),
        uds('Система управления ВВБ', 'BMS', 'BMS', 'ВВБ', '7E7'),
        // SMCU drives the left rear motor, SMCU3 the right (findings, 2026-10-21)
        uds('Контроллер заднего левого мотора', 'SMCU', 'SMCU', 'Задн. левый', '744'),
        uds('Контроллер заднего правого мотора', 'SMCU3', 'SMCU3', 'Задн. правый', '745'),
        uds('Бортовое зарядное и DC-DC', 'DCOBC', 'DCOBC', 'OBC + DC-DC', '7E4'),
      ] },
      { id: 'chassis', title: 'ШАССИ И БЕЗОПАСНОСТЬ', short: 'Шасси', topo: 'ШАССИ', ecus: [
        uds('Тормозная система, стабилизация', 'ESP/IPB · Bosch', 'ESP/IPB', 'Тормоза, ESP', '782'),
        ecu('Тормозная система · интерфейс OBD', 'BSCM', 'BSCM', 'Тормоза · OBD', '7E5', 'OBD-II', 'ok', 0, '—'),
        uds('Электроусилитель руля', 'EPS', 'EPS', 'Рулевое', '783'),
        uds('Подруливание задних колёс, левое', 'RWS-L', 'RWS-L', 'Задн. руль Л', '582'),
        uds('Подруливание задних колёс, правое', 'RWS-R', 'RWS-R', 'Задн. руль П', '796'),
        uds('Контроллер движения кузова', 'IDC', 'IDC', 'Подвеска', '597', 4),
        uds('Подушки безопасности', 'SRS · Veoneer', 'SRS', 'Подушки', '7F1'),
        uds('Натяжитель ремня водителя', 'MSRZ', 'MSRZ', 'Ремень вод.', '742'),
        uds('Натяжитель ремня пассажира', 'MSRF', 'MSRF', 'Ремень пасс.', '713'),
      ] },
      { id: 'assist', title: 'АССИСТЕНТЫ ВОДИТЕЛЯ', short: 'Ассистенты', topo: 'АССИСТЕНТЫ', ecus: [
        uds('Контроллер ассистентов', 'ADC', 'ADC', 'Ассистенты', '712'),
        uds('Радар передний', 'MRR', 'MRR', 'Радар перед.', '7F2'),
        uds('Угловой радар передний левый', 'LFCR', 'LFCR', 'Радар ПЛ', '760'),
        uds('Угловой радар передний правый', 'RFCR', 'RFCR', 'Радар ПП', '761'),
        uds('Угловой радар задний правый', 'RRCR', 'RRCR', 'Радар ЗП', '7C2'),
        uds('Угловой радар задний левый', 'LRCR', 'LRCR', 'Радар ЗЛ', '7D4'),
        uds('Парковочный ассистент', 'IPA · Bosch', 'IPA', 'Парковка', '701'),
      ] },
      { id: 'body', title: 'КУЗОВ', short: 'Кузов', topo: 'КУЗОВ', ecus: [
        uds('Кузовной контроллер левый', 'LA_BCM', 'LA_BCM', 'Кузов слева', '720', 8),
        uds('Кузов, зона справа', 'R_ZCU', 'R_ZCU', 'Зона справа', '747', 2),
        uds('Кузов, зона спереди', 'F_ZCU', 'F_ZCU', 'Зона спереди', '722'),
        uds('Кузов, зона сзади', 'B_ZCU', 'B_ZCU', 'Зона сзади', '726'),
        uds('Фара левая', 'FL_L', 'FL_L', 'Фара Л', '770'),
        uds('Фара правая', 'FL_R', 'FL_R', 'Фара П', '771'),
        uds('Цифровой ключ UWB', 'UWBMC', 'UWBMC', 'UWB-ключ', '7F3'),
        uds('Антенна UWB передняя левая', 'UWBLFA', 'UWBLFA', 'UWB ПЛ', '7F4'),
        uds('Антенна UWB передняя правая', 'UWBRFA', 'UWBRFA', 'UWB ПП', '7F5'),
        uds('Антенна UWB задняя левая', 'UWBLRA', 'UWBLRA', 'UWB ЗЛ', '7F6'),
        uds('Антенна UWB задняя правая', 'UWBRRA', 'UWBRRA', 'UWB ЗП', '7F7'),
        uds('Проекция у двери багажника', 'PDCMB', 'PDCMB', 'Проекция багажн.', '724'),
        uds('Приёмник радиоключа', 'HFRM', 'HFRM', 'Радиоключ', '754'),
        uds('Бесключевой доступ', 'SEC', 'SEC', 'Бескл. доступ', '7D6'),
        uds('NFC правый', 'R-NCM', 'R-NCM', 'NFC правый', '595'),
        uds('Радар двери передней левой', 'FL_SDR', 'FL_SDR', 'Радар ПЛ', '5B2'),
        uds('Радар двери передней правой', 'FR_SDR', 'FR_SDR', 'Радар ПП', '5B1'),
        uds('Радар двери задней левой', 'RL_SDR', 'RL_SDR', 'Радар ЗЛ', '5B0'),
        uds('Радар двери задней правой', 'RR_SDR', 'RR_SDR', 'Радар ЗП', '5A7'),
      ] },
      // 741 and 5E7 are the head unit's and the passenger computer's own MCUs
      { id: 'salon', title: 'САЛОН', short: 'Салон', topo: 'САЛОН', ecus: [
        uds('Комбинация приборов', 'INS', 'INS', 'Приборы', '723', 2),
        uds('Усилитель звука', 'AMP', 'AMP', 'Усилитель', '751'),
        uds('Беспроводная зарядка', 'WCT', 'WCT', 'Беспр. ЗУ', '733'),
        uds('Кнопки руля', 'SWS', 'SWS', 'Кнопки руля', '711'),
        uds('Головное устройство (MCU)', 'Media', 'Media', 'ГУ · MCU', '741', 1),
        uds('Пассажирский компьютер (MCU)', 'ASSY', 'ASSY', 'Пасс. ПК · MCU', '5E7'),
        uds('Проекционный дисплей', 'HUD', 'HUD', 'HUD', '7B0'),
        uds('Модуль оплаты проезда (ETC)', 'ETC', 'ETC', 'ETC', '790'),
        uds('Радар присутствия детей, задний', 'CPD', 'CPD', 'Радар детей', '5E0'),
        uds('Подрулевые переключатели', 'CS', 'CS', 'Подрулевые', '725'),
        uds('Блок водителя', 'DUCU', 'DUCU', 'Блок вод.', '750'),
        uds('Блок пассажира', 'PUCU', 'PUCU', 'Блок пасс.', '7B5'),
        uds('Блок среднего ряда слева', 'MLUCU', 'MLUCU', 'Ср. ряд Л', '7D2'),
        uds('Панель селектора передач', 'CCS/SCPA', 'CCS/SCPA', 'Селектор', '7A7', 1),
        uds('Панель заднего холодильника', 'RRCP', 'RRCP', 'Холод. зад.', '7C6'),
        uds('Панель холодильника', 'RCP', 'RCP', 'Холод. панель', '5F4'),
        uds('Холодильник', 'VR', 'VR', 'Холодильник', '5A0'),
        uds('Пульт среднего ряда', 'MCPA', 'MCPA', 'Пульт ср. ряда', '5E4'),
        uds('Передний плафон', 'FRL', 'FRL', 'Плафон пер.', '772'),
      ] },
      { id: 'climate', title: 'КЛИМАТ И ТЕРМОРЕГУЛИРОВАНИЕ', short: 'Климат', topo: 'КЛИМАТ', ecus: [
        uds('Климат-контроль', 'ACC', 'ACC', 'Климат', '7B3'),
        uds('Модуль терморегулирования', 'TMIM · Tuopu', 'TMIM', 'Терморег.', '7B4'),
        uds('Высоковольтный нагреватель', 'PTC · HGTECH', 'PTC', 'Нагреватель', '766'),
        uds('Датчик качества воздуха', 'AQDM', 'AQDM', 'Кач. воздуха', '590'),
      ] },
      { id: 'lv', title: 'ЭЛЕКТРОСЕТЬ 12 В', short: '12 В', topo: '12 В', ecus: [
        uds('АКБ 12 В', 'LBMS', 'LBMS', 'АКБ 12 В', '762'),
      ] },
    ],
  };

  const back = ['‹ Автоскан', 'quiet'];
  const ecmCounters = [['MIL', 'ВЫКЛ'], ['DTC', '0'], ['ГОТОВН.', '4/9']];
  const ecmTabs = active => ({ items: ['DTC и готовность', 'Потоковые данные', 'Идентификация'], active });
  const ecmBlock = (line, counters) => ({ title: 'ЭБУ двигателя', aside: '· ECM · UAES', line, counters: counters || ecmCounters });
  // F195 as the car's updater reads it: bytes 0-1 as D.DD.DD, the other four as they came (architecture 5.5)
  const ecmLine = '7E0 → 7E8 · OBD-II (SAE J1979) + UDS · ПО (F195) 4.00.10 · 19031900 · стандарт OBD (PID 1C) 0x29';
  // [name, abbr, type, ready | waiting | absent]
  const monitor = (state, rows) => rows.map(([name, abbr, type], i) => [name, abbr, type, state[i]]);
  const MONITORS = [['Пропуски воспламенения', 'MIS', 'непрерывный'], ['Топливная система', 'FUEL', 'непрерывный'], ['Контроль компонентов', 'CCM', 'непрерывный'],
    ['Катализатор', 'CAT', 'разовый'], ['Система улавливания паров', 'EVAP', 'разовый'], ['Фильтр частиц бензиновый', 'GPF', 'разовый'],
    ['Датчики кислорода', 'O2S', 'разовый'], ['Подогрев датчиков кислорода', 'O2S HTR', 'разовый'], ['Рециркуляция ОГ / фазы ГРМ', 'EGR/VVT', 'разовый'],
    ['Подогрев катализатора', 'HCAT', '—'], ['Вторичный воздух', 'AIR', '—']];
  const at = names => names.map(n => MONITORS.find(m => m[1] === n));

  const scannerBodyDtc = {
    top: scanTop(['Автоскан', 'Кузов, зона справа'], '15:42:08'),
    block: { title: 'Кузов, зона справа', aside: '· R_ZCU', badge: ['hist', 'ИСТОРИЯ'], dtc: ['DTC', '2'],
      line: '747 → 74F · UDS · маска доступности FF · формат DTC 00 (SAE J2012)' },
    tabs: { items: ['Ошибки DTC', 'Идентификация'], active: 0 },
    // live read 2026-10-20: 19 02 FF from 747 -> 517009/28, 930E01/28
    codes: [
      { code: 'C1170-09', ftb: '09', ftbName: 'Отказ компонента', group: 'Шасси · код производителя (C1xxx)', status: 0x28 },
      { code: 'B130E-01', ftb: '01', ftbName: 'Общий электрический отказ', group: 'Кузов · код производителя (B1xxx)', status: 0x28 },
    ],
    explain: {
      head: 'СТАТУС 0x28 = 0010 1000',
      lead: 'Подтверждённый, неактивный (исторический).',
      body: 'CDTC = 1 и TFSLC = 1: отказ был зафиксирован после последнего сброса. TF = 0, TFTOC = 0 и TNCTOC = 0: в текущем цикле тест выполнен, отказа нет. WIR = 0: лампу не запрашивает.'
    },
    bits: ['отказ сейчас', 'отказ в текущем цикле', 'ожидающий', 'подтверждённый', 'тест не выполнен после сброса', 'отказ после сброса', 'тест не выполнен в текущем цикле', 'запрос лампы'],
    footer: { buttons: [back, ['Прочитать DTC', 'primary']], notes: [['19 02 FF · снимок 15:42:08', true]] },
  };

  const scannerEcuDtc = {
    top: scanTop(['Автоскан', 'ЭБУ двигателя'], '15:47:31', 'Z9 GT DM · P · ДВС не запущен'),
    block: ecmBlock(ecmLine),
    tabs: ecmTabs(0),
    // ECM 7E0, read 2026-10-20
    lists: [['Сохранённые (Stored)', 'Mode 03', '0'], ['Ожидающие (Pending)', 'Mode 07', '0'], ['Постоянные (Permanent)', 'Mode 0A', '0'],
      ['Подтверждённые, UDS', '19 02 08', '0'], ['Ожидающие, UDS', '19 02 04', 'нет ответа']],
    pid01: { label: 'PID 01 · СОСТОЯНИЕ', line: 'MIL выключена · DTC, включивших MIL: 0 · зажигание искровое', raw: '41 01 00 07 F5 F4' },
    monitors: monitor(['ready', 'ready', 'ready', 'ready', 'waiting', 'waiting', 'waiting', 'waiting', 'waiting', 'absent', 'absent'], MONITORS),
    footer: { buttons: [back, ['Прочитать DTC', 'primary']] },
  };

  // An illustrative fault, not a reading of this car.
  const scannerEcuFault = {
    top: scanTop(['Автоскан', 'ЭБУ двигателя'], '15:52:44', 'Z9 GT DM · P · ДВС работает'),
    block: ecmBlock('7E0 → 7E8 · OBD-II (SAE J1979) + UDS · ПО (F195) 4.00.10 · 19031900', [['MIL', 'ВКЛ', 'on'], ['DTC', '1'], ['ГОТОВН.', '7/9']]),
    tabs: Object.assign(ecmTabs(0), { alert: 0 }),
    // title: the catalog's heading of P042000 (engine_ecm), as the screen draws a description
    fault: { code: 'P0420', title: 'Старение трёхкомпонентного катализатора: снижена кислородная ёмкость', mil: 'ВКЛ',
      note: 'Сохранённый (Mode 03) · постоянный (Mode 0A) · UDS статус 0xAF: активный, подтверждённый, запрос MIL' },
    lists: [['СОХРАНЁННЫЕ', '1', 'Mode 03'], ['ОЖИДАЮЩИЕ', '0', 'Mode 07'], ['ПОСТОЯННЫЕ', '1', 'Mode 0A'], ['UDS ПОДТВ.', '1', '19 02 08']],
    pid01: { label: 'PID 01 · СОСТОЯНИЕ', line: 'MIL включена · DTC, включивших MIL: 1 · зажигание искровое', raw: '41 01 81 07 F5 30' },
    monitors: monitor(['ready', 'ready', 'ready', 'ready', 'ready', 'ready', 'ready', 'waiting', 'waiting', 'absent', 'absent'],
      at(['MIS', 'FUEL', 'CCM', 'CAT', 'EVAP', 'O2S HTR', 'EGR/VVT', 'GPF', 'O2S', 'HCAT', 'AIR'])),
    footer: { buttons: [back, ['Прочитать DTC', 'primary']], notes: ['Описания: каталог мануала Z9GT PHEV, перевод'] },
  };

  // An illustrative warm idle: how live data reads, not a recording. The traces are the last 60 s,
  // 0..1 of their own range, from the approved page's formula.
  const trace = (seed, amp, drift) => {
    const out = [];
    for (let i = 0; i <= 60; i++) {
      const v = 0.5 + drift * (i / 60 - 0.5) + amp * (Math.sin(i * 0.37 + seed) * 0.6 + Math.sin(i * 1.13 + seed * 2) * 0.4);
      out.push(+Math.max(0, Math.min(1, v)).toFixed(3));
    }
    return out;
  };
  const live = (pid, name, abbr, value, unit, min, max, norm, t) => ({ pid, name, abbr, value, unit, min, max, norm, trace: t ? trace(...t) : [] });
  const scannerEcuLive = {
    top: scanTop(['Автоскан', 'ЭБУ двигателя'], '15:51:02', 'Z9 GT DM · P · ДВС работает'),
    block: ecmBlock('7E0 → 7E8 · Mode 01 · 13 PID · опрос 4 Гц · график за 60 с'),
    tabs: ecmTabs(1),
    rows: [
      live('05', 'Температура ОЖ', 'ECT', '91', '°C', '88', '92', '80…105', [1, 0.05, 0.3]),
      live('0F', 'Температура воздуха на впуске', 'IAT', '24', '°C', '22', '25', '—', [2, 0.04, 0.2]),
      live('0B', 'Абс. давление во впуске', 'MAP', '38', 'кПа', '31', '64', 'ХХ 25…45', [3, 0.25, -0.2]),
      live('0C', 'Обороты коленвала', 'RPM', '1 250', 'об/мин', '1 180', '2 140', '—', [4, 0.22, -0.25]),
      live('04', 'Расчётная нагрузка', 'LOAD', '28', '%', '21', '47', '—', [5, 0.22, -0.2]),
      live('11', 'Положение дросселя', 'TPS', '6,3', '%', '5,1', '18,0', '—', [6, 0.2, -0.2]),
      live('0E', 'Угол опережения зажигания', 'УОЗ', '14,0', '°', '9,5', '22,5', '—', [7, 0.18, 0]),
      live('06', 'Краткосрочная коррекция B1', 'STFT', '+3,1', '%', '−4,7', '+5,5', '±10', [8, 0.3, 0]),
      live('07', 'Долгосрочная коррекция B1', 'LTFT', '−2,3', '%', '−2,3', '−1,6', '±10', [9, 0.03, -0.05]),
      live('03', 'Топливная система B1', 'FUEL SYS', 'CL', '', '', '', 'замкн. петля'),
      live('15', 'Датчик кислорода B1S2', 'O2S12', '0,68', 'В', '0,61', '0,74', '0,1…0,9', [10, 0.12, 0]),
      live('3C', 'Температура катализатора B1S1', 'CATT', '512', '°C', '486', '530', '—', [11, 0.06, 0.25]),
      live('42', 'Напряжение ЭБУ', 'VPWR', '14,1', 'В', '13,9', '14,2', '13,2…14,8', [12, 0.05, 0]),
    ],
    footer: { buttons: [back, ['Пауза', 'primary'], ['Выбор PID', 'quiet'], ['Сбросить мин/макс', 'quiet']] },
  };

  const scannerEcuIdent = {
    top: scanTop(['Автоскан', 'ЭБУ двигателя'], '15:48:02', 'Z9 GT DM · P · ДВС не запущен'),
    block: ecmBlock(ecmLine),
    tabs: ecmTabs(2),
    // [key, value, tone: '' white | 'dim']
    // 09 00, 09 04, 09 0A and 01 A6 as ECM answered them on 2026-10-21 (ecm-completion, ecm-obd-telemetry)
    own: [['Запрос → ответ', '7E0 → 7E8', ''], ['Протоколы', 'OBD-II · UDS (ISO 14229)', ''], ['Поставщик · 22 F18A', 'UAES', ''],
      ['Номер ПО поставщика · F194', '32440011E004000000', ''], ['Версия ПО · 22 F195', '4.00.10 · 19031900', ''],
      ['Имя блока · 09 0A', 'ECM-EngineControl', ''], ['Калибровка · 09 04', 'Z31L07AB01', ''],
      ['Стандарт OBD · PID 1C', '0x29', ''], ['Пробег (ECM) · PID A6', '13 199,7 км', ''],
      ['Формат DTC · 19 01', '00 · SAE J2012', '']],
    cache: [['Идентификатор', 'HTH-2021_ECM_UAES_01200001', ''], ['Версия железа', '1.0.0', '']],
    pidsLabel: 'ПОДДЕРЖИВАЕМЫЕ PID · MODE 01',
    pidsAsked: '01 00, 01 20, 01 40, 01 60, 01 80, 01 A0',
    pids: ('01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F 20 21 23 2C 2D 2E 2F 30 31 33 34 3C 40 41 42 43 44 45 46 47 49 4A 4C 54 56 60 67 6B 7A 80 A0 A6').split(' '),
    servicePids: ['20', '40', '60', '80', 'A0'],
    footer: { buttons: [back, ['Прочитать снова', 'primary']],
      notes: [['22 F18A · F194 · F195', true]] },
  };

  // «Идентификация» of a UDS block after an over-the-air update (architecture 5.5): the BMS with the
  // row the code history leaves under its F195. Illustrative: the new F194 and F195 and the day are made
  // up; the old version and code are what the BMS answered on 2026-10-20 and the update cache kept on
  // 2026-10-18, F18A and F197 as it answered them then.
  const scannerEcuIdentUpdated = {
    top: scanTop(['Автоскан', 'Система управления ВВБ'], '10:14:05'),
    block: { title: 'Система управления ВВБ', aside: '· BMS', badge: ['ok', 'OK'], dtc: ['DTC', '0'],
      line: '7E7 → 7EF · UDS · маска доступности 09 · формат DTC 00 (SAE J2012) · ПО (F195) 3.48.07 · 1A0B0C03' },
    tabs: { items: ['Ошибки DTC', 'Идентификация'], active: 1 },
    // [key, value, tone: '' white | 'dim']
    own: [['Запрос → ответ', '7E7 → 7EF', ''], ['Протоколы', 'UDS (ISO 14229)', ''], ['Поставщик · 22 F18A', 'нет ответа', 'dim'],
      ['Номер ПО поставщика · 22 F194', '4A520011E00D00048C', ''], ['Версия ПО · 22 F195', '3.48.07 · 1A0B0C03', ''],
      ['ПО обновлено · 12.11.2026', 'был 3.48.07 · 1A011402 · 4A520011E00D00047B', ''],
      ['Имя системы · 22 F197', 'не отдаёт · NRC 31', 'dim'], ['Формат DTC · 19 01', '00 · SAE J2012', '']],
    cache: [['Основание имени', '22 F194', ''], ['Идентификатор', 'HTH-2021_BMS_BYD_03300001', ''], ['Версия железа', '0.0.3', '']],
    pids: [],
    footer: { buttons: [back, ['Прочитать снова', 'primary']], notes: [['22 F18A · F194 · F195', true]] },
  };

  // The parked sweep of 2026-10-20 (readings in docs/vehicle-data-findings.md): the pack's readings.
  const packReadings = {
    // [signal, parameter, value, unit, norm, mark]
    rows: [
      ['1009·44400008', 'Напряжение ВВБ', '553', 'В', '—', ''],
      ['1009·44400018', 'Ток ВВБ', '−4,5', 'А', '—', ''],
      ['1014·44400030', 'Уровень заряда (SOC)', '74', '%', '—', ''],
      ['1014·44400028', 'Состояние (SOH), оценка BMS', '99', '%', '≥ 80', 'OK'],
      ['1014·44600010', 'Напряжение ячейки, мин.', '3 333', 'мВ', '—', ''],
      ['1014·44600030', 'Напряжение ячейки, макс.', '3 337', 'мВ', '—', ''],
      ['расчёт', 'Разброс напряжений ячеек', '4', 'мВ', '—', ''],
      ['1014·44700010', 'Температура модулей, мин.', '13', '°C', '—', ''],
      ['1014·44700020', 'Температура модулей, макс.', '18', '°C', '—', ''],
      ['1014·44700038', 'Температура модулей, сред.', '14', '°C', '—', ''],
      ['1039·43A00018', 'Сопротивление изоляции, канал BMC', '14 429', 'кОм', '≥ 500 Ом/В (≥ 277 кОм)', 'OK'],
      ['1039·26200010', 'Сопротивление изоляции, канал 2', '19 675', 'кОм', '≥ 500 Ом/В (≥ 277 кОм)', 'OK'],
      ['1001·43A00008', 'Ячеек последовательно', '166', 'шт', '—', ''],
      ['1039·43A00010', 'Датчиков температуры', '14', 'шт', '—', ''],
      ['1032·34400010', 'Главный контактор, разряд', 'замкнут', '', '—', ''],
      ['1009·34400018', 'Состояние заряда батареи', 'разряд', '', '—', ''],
      ['1009·27C0000A', 'Зарядное устройство, состояние', 'готово', '', '—', ''],
      ['1009·27C00008', 'Зарядное устройство, отказ', 'норма', '', '—', ''],
    ],
  };

  // the drives side by side
  const drivesSide = {
    units: [['FMCU · 7E3', 'передний'], ['SMCU · 744', 'задний левый'], ['SMCU3 · 745', 'задний правый'], ['ISG · 7E6', 'мотор-генератор']],
    // [parameter, signals, unit, [front, rear left, rear right, motor-generator]]
    rows: [
      ['Температура статора', '1039·46406018 · Л 285001A8 · П 285001B0', '°C', ['8', '7', '7', '—']],
      ['Температура силовых ключей (IGBT/IPM)', '1039·46406010 · Л 2D000180 · П 2D000188', '°C', ['9', '5', '5', '—']],
      ['Напряжение шины DC', '1039·46407020 · задн. 46407010', 'В', ['555', '556', '556', '—']],
      ['Обороты', '1012·44100008 · 1039 Л 0FC000D0 · П 0FC000E0', 'об/мин', ['0', 'н/д', '0', '—']],
      ['Крутящий момент, факт.', '1012·44100018 · 1039 Л 2850002C · П 2850003C', 'Н·м', ['0,0', '0,0', '0,0', '—']],
      ['Ток DC', '1009·46C00008 · 1039 Л 28500178 · П 28500188', 'А', ['0,0', '0,0', '0,0', '—']],
      ['Коды неисправностей', 'UDS 19 01 09 · 19 02 09', 'DTC', ['0', '0', '0', '0']],
      ['Номер ПО поставщика', 'UDS 22 F194', '', ['48370011E075000000', '48370011E086001100', '48370011E088001100', '48370011E0EA000000']],
      ['Версия ПО', 'UDS 22 F195', '', ['4.03.12 · 190A190C', '—', '—', '4.03.13 · 190A1E0D']],
    ],
  };

  // the HV schema
  const hvSchema = {
    block: { title: 'Высоковольтная система', aside: '· ВВБ, привод, мотор-генератор', counters: [['U ВВБ', '553 В'], ['SOC', '74 %'], ['DTC', '0']] },
    // the main contactor as the BMS reports it (1032·34400010): 1, closed; its word under the label
    contactor: 'главный контактор', contactorClosed: true, contactorState: 'замкнут',
    busLabel: 'ШИНА DC',
    volts: ['555 В', '556 В'],
    pack: { title: 'ВВБ', tag: 'BMS · 7E7', state: 'OK · DTC 0', rows: [['Напряжение / ток', '553 В · −4,5 А'], ['SOC / SOH', '74 % · 99 %'],
      ['Ячейки, мин–макс', '3 333–3 337 мВ'], ['Разброс', '4 мВ'], ['Температура', '13…18 °C'], ['Изоляция', '14,4 · 19,7 МОм']] },
    // 766 answered F194 as PTC_HGTECH; DCOBC is one cached unit with no route found
    units: [
      { abbr: 'FMCU', addr: '7E3', name: 'Инвертор передний', kind: 'ok', state: 'OK · IPM 9 °C' },
      { abbr: 'ISG', addr: '7E6', name: 'Мотор-генератор', kind: 'ok', state: 'OK · DTC 0' },
      { abbr: 'SMCU', addr: '744', name: 'Инвертор задний левый', kind: 'ok', state: 'OK · DTC 0' },
      { abbr: 'SMCU3', addr: '745', name: 'Инвертор задний правый', kind: 'ok', state: 'OK · DTC 0' },
      { abbr: 'ECM', addr: '7E0', name: 'ДВС', kind: 'ok', state: 'OK · MIL ВЫКЛ' },
      { abbr: 'PTC', addr: '766', name: 'Нагреватель ВВ', kind: 'ok', state: 'OK · DTC 0' },
      { abbr: 'DCOBC', addr: '7E4', name: 'Зарядное + DC-DC', kind: 'ok', state: 'OK · DTC 0' },
      { abbr: 'LBMS', addr: '762', name: 'АКБ 12 В · 13,7 В', kind: 'ok', state: 'OK · DTC 0' },
    ],
    machines: [
      { name: 'Передний мотор', values: 'статор 8 °C · 0 об/мин · 0 Н·м', live: true },
      { name: 'ДВС не работает', values: '0 об/мин · ОЖ 9 °C' },
      { name: 'Задний мотор левый', values: 'статор 7 °C · н/д об/мин · 0 Н·м', live: true },
      { name: 'Задний мотор правый', values: 'статор 7 °C · 0 об/мин · 0 Н·м', live: true },
    ],
  };

  // 782 read 2026-10-20: a UDS block whose memory gave nothing back, opened from «Шасси»
  const scannerEspDtc = {
    top: scanTop(['Шасси', 'Тормозная система, стабилизация'], '16:31:52'),
    block: { title: 'Тормозная система, стабилизация', aside: '· ESP/IPB · Bosch', badge: ['ok', 'OK'], dtc: ['DTC', '0'],
      line: '782 → 78A · UDS · доступность DTC FF · формат 00 (SAE J2012) · F194 32330011DF0B000101' },
    tabs: { items: ['Ошибки DTC', 'Идентификация'], active: 0 },
    asks: [['Отказ сейчас или подтверждённые', '19 02 09', '0'], ['Счётчик по той же маске', '19 01 09', '0'],
      ['Ожидающие', '19 02 04', 'не запрашивали'], ['Все записи, с непроверенными', '19 02 FF', 'не запрашивали']],
    mask: { label: 'МАСКА 09 · БИТЫ СТАТУСА', value: 0x09, result: 'Записей нет' },
    footer: { buttons: [['‹ Шасси', 'quiet'], ['Прочитать DTC', 'primary']], notes: [['19 01 09 · 19 02 09', true]] },
  };

  // read 2026-10-20 (the parked scan of 19:31 for the memories); cache versions from the update
  // cache. shared: the four corner radars' one answer.
  const cellA = (t, o) => Object.assign({ t }, o || {});
  const four = (t, o) => [0, 1, 2, 3].map(() => cellA(t, o));
  const adasSide = {
    units: [['ADC', 'Контроллер ADAS', true], ['MRR', 'Радар передний', true], ['LFCR', 'Угловой ПЛ', true], ['RFCR', 'Угловой ПП', true],
      ['RRCR', 'Угловой ЗП', true], ['LRCR', 'Угловой ЗЛ', true], ['IPA', 'Парковка', true]],
    rows: [
      { name: 'Запрос → ответ', cells: ['712 → 71A', '7F2 → 7FA', '760 → 768', '761 → 769', '7C2 → 7CA', '7D4 → 7DC', '701 → 709'].map(t => cellA(t, { ink: 'dim', size: 13 })) },
      { name: 'Номер ПО · F194', cells: [cellA('48450011DF83000000', { size: 11 }), cellA('474E0011DF70000000', { size: 11 }),
        ...four('474E0011DF82000000', { size: 11, shared: true, ink: 'blueInk' }), cellA('32330011DF42000000', { size: 11 })] },
      { name: 'Идентификатор BYD', cells: [cellA('ADC_BYD', { size: 12.5 }), cellA('MRR · 3 вар.', { size: 12.5 }),
        ...four('12 вариантов', { size: 12.5, shared: true, ink: 'dim' }), cellA('IPA_BOSCH', { size: 12.5 })] },
      { name: 'Положение', cells: ['—', 'перед', 'ПЛ', 'ПП', 'ЗП', 'ЗЛ', '—'].map(t => cellA(t)) },
      { name: 'Связь', cells: [cellA('CAN · DoIP', { ink: 'orangeInk' }), cellA('CAN'), ...four('CAN'), cellA('CAN')] },
      { name: 'Доступность DTC', cells: [cellA('09'), cellA('09'), ...four('29', { shared: true }), cellA('09')] },
      { name: 'Формат DTC', cells: [cellA('01 · не SAE', { ink: 'orangeInk', size: 13 }), cellA('00 · SAE', { size: 13 }), ...four('00 · SAE', { size: 13, shared: true }), cellA('00 · SAE', { size: 13 })] },
      { name: 'DTC · маска 09', cells: [cellA('0', { size: 16 }), cellA('0', { size: 16 }), ...four('0', { size: 16 }), cellA('0', { size: 16 })] },
    ],
  };
  const scanner = screen => ({ kind: 'scanner', mode: 'full', screen });

  // board id -> [board, fixture]; px sizes are the displays' own
  // The systems down the left (Tesla's service mode): the autoscan first, then one system a tab.
  // A mark only where codes are: «ИСТ n» history, «АКТ n» active.
  const sysRail = active => ({ active, items: [
    { name: 'Автоскан', mark: ['68/68', 'faint'] }, '|',
    { name: 'ВВ-система' }, { name: 'Тяговая батарея' }, { name: 'Электропривод' }, { name: 'Двигатель' },
    { name: 'Шасси', mark: ['ИСТ 4', 'ink4'] }, { name: 'Безопасность' }, { name: 'Ассистенты' },
    { name: 'Кузов', mark: ['ИСТ 10', 'ink4'] }, { name: 'Салон', mark: ['ИСТ 4', 'ink4'] }, { name: 'Климат' }, { name: 'Сеть 12 В' },
  ] });
  const sysTop = (crumb, time, car) => scanTop([crumb], time, car);

  const scannerSysScan = Object.assign({}, autoscan, {
    top: sysTop('Автоскан', '19:31:29'),
    rail: sysRail('Автоскан'),
    block: { title: 'Автоскан', aside: '· 68 блоков', line: 'опрос 19:31 · маска DTC 09',
      counters: [['ОТВЕТИЛО', '68/68'], ['DTC АКТ.', '0'], ['DTC ИСТ.', '18'], ['ПРОЧИТАНО', '68/68']] },
    tabs: { items: ['Список', 'Топология'], active: 0 },
    footer: { buttons: [['Опросить снова', 'primary'], ['Только с DTC', 'quiet']] },
  });

  const scannerSysTopology = Object.assign({}, scannerSysScan, {
    tabs: { items: ['Список', 'Топология'], active: 1 },
    footer: { buttons: [['Опросить снова', 'primary']], legendGap: 16,
      legend: [['ok', 'OK'], ['hist', 'DTC в истории'], ['unread', 'ответил, ошибки не читали'], ['none', 'адрес не найден']] },
  });

  // The engine's page: one head on every tab. «Состояние» and «Потоковые данные» are the
  // illustrative warm idle; «DTC и готовность» is ECM as read 2026-10-20.
  const engineTop = sysTop('Двигатель', '15:51:02', 'Z9 GT DM · P · ДВС работает');
  const engineBlock = { title: 'Двигатель', aside: '· ECM 7E0 · UAES', line: '7E0 → 7E8 · OBD-II (SAE J1979) + UDS · Mode 01 · опрос 4 Гц',
    counters: [['ОБОРОТЫ', '1 250'], ['MIL', 'ВЫКЛ'], ['ГОТОВН.', '4/9']] };
  const engineTabs = active => ({ items: ['Состояние', 'Потоковые данные', 'DTC и готовность', 'Блоки'], active });
  const scannerSysEngine = {
    top: engineTop, rail: sysRail('Двигатель'), block: engineBlock, tabs: engineTabs(1),
    rows: scannerEcuLive.rows,
    footer: { buttons: [['Пауза', 'primary'], ['Выбор PID', 'quiet'], ['Сбросить мин/макс', 'quiet']] },
  };
  const scannerSysEngineDtc = {
    top: engineTop, rail: sysRail('Двигатель'), block: engineBlock, tabs: engineTabs(2),
    lists: scannerEcuDtc.lists, pid01: scannerEcuDtc.pid01, monitors: scannerEcuDtc.monitors,
    footer: { buttons: [['Прочитать DTC', 'primary']] },
  };

  // the body's codes as the car gave them on 2026-10-20 (19 02 09: status masked to 0x08)
  const bodyCode = (block, addr, code, ftb, ftbName, desc) => ({ block, addr, code, ftb, ftbName, desc, status: '0x08', word: 'подтверждённый' });
  const scannerSysBody = {
    top: sysTop('Кузов', '19:31:29'),
    rail: sysRail('Кузов'),
    block: { title: 'Кузов', aside: '· 19 блоков', line: 'LA_BCM 720 · R_ZCU 747 · F_ZCU 722 · B_ZCU 726 · фары 770 771 · UWB-ключ 7F3 и 4 антенны · ' +
      'радары дверей 5B2 5B1 5B0 5A7 · HFRM 754 · SEC 7D6 · R-NCM 595 · PDCMB 724',
      counters: [['DTC АКТ.', '0'], ['DTC ИСТ.', '10'], ['ПРОЧИТАНО', '19/19']] },
    tabs: { items: ['Состояние', 'DTC', 'Блоки'], active: 1 },
    codes: [
      bodyCode('LA_BCM', '720', 'C1160-06', '06', 'Отказ по алгоритму', 'Шасси · код производителя (C1xxx)'),
      bodyCode('LA_BCM', '720', 'U029D-87', '87', 'Нет сообщения', 'Сеть · общий код SAE (U0xxx)'),
      bodyCode('LA_BCM', '720', 'U0121-87', '87', 'Нет сообщения', 'Сеть · общий код SAE (U0xxx)'),
      bodyCode('LA_BCM', '720', 'B2148-02', '02', 'Общий отказ сигнала', 'Кузов · код производителя (B2xxx)'),
      bodyCode('LA_BCM', '720', 'B2250-00', '00', 'Подтип не указан', 'Кузов · код производителя (B2xxx)'),
      bodyCode('LA_BCM', '720', 'B225B-00', '00', 'Подтип не указан', 'Кузов · код производителя (B2xxx)'),
      bodyCode('LA_BCM', '720', 'U0229-87', '87', 'Нет сообщения', 'Сеть · общий код SAE (U0xxx)'),
      bodyCode('LA_BCM', '720', 'U022A-87', '87', 'Нет сообщения', 'Сеть · общий код SAE (U0xxx)'),
      bodyCode('R_ZCU', '747', 'C1170-09', '09', 'Отказ компонента', 'Шасси · код производителя (C1xxx)'),
      bodyCode('R_ZCU', '747', 'B130E-01', '01', 'Общий электрический отказ', 'Кузов · код производителя (B1xxx)'),
    ],
    footer: { buttons: [['Прочитать DTC', 'primary']], notes: [['19 01 09 · 19 02 09 · снимок 19:31:29', true]] },
  };

  // The systems' «Состояние» as schemes, the parked snapshot of 2026-10-20 (sweep and autoscan).
  const u = (abbr, req, short, kind, history) => ({ abbr, req, short, kind: kind || 'ok', history });
  const sysTabs = active => ({ items: ['Состояние', 'DTC', 'Блоки'], active });
  const scannerSysChassis = {
    top: sysTop('Шасси', '19:31:29'), rail: sysRail('Шасси'),
    block: { title: 'Шасси', aside: '· 6 блоков', line: 'ESP/IPB 782 · BSCM 7E5 · EPS 783 · RWS 582 796 · IDC 597 · шины: давление 1016, температура 1007',
      counters: [['ДАВЛЕНИЕ', '2,60–2,72'], ['DTC', '4'], ['ПРОЧИТАНО', '6/6']] },
    tabs: sysTabs(0),
    tyres: [
      { side: 'L', axle: 'F', label: 'ШИНА ПЛ', pressure: '2,72', temp: '19 °C' },
      { side: 'R', axle: 'F', label: 'ШИНА ПП', pressure: '2,60', temp: '8 °C' },
      { side: 'L', axle: 'R', label: 'ШИНА ЗЛ', pressure: '2,70', temp: '17 °C' },
      { side: 'R', axle: 'R', label: 'ШИНА ЗП', pressure: '2,62', temp: '8 °C' },
    ],
    units: { esp: u('ESP/IPB', '782', 'Тормоза, ESP'), eps: u('EPS', '783', 'Рулевое'), bscm: u('BSCM', '7E5', 'Тормоза · OBD'),
      rwsL: u('RWS-L', '582', 'Задн. руль Л'), rwsR: u('RWS-R', '796', 'Задн. руль П'), idc: u('IDC', '597', 'Подвеска', 'hist', 4) },
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };
  const door = (side, axle, label) => ({ side, axle, label, state: 'закрыта', detail: 'не заперта · окно 0 %' });
  const scannerSysBodyState = {
    top: sysTop('Кузов', '19:31:29'), rail: sysRail('Кузов'),
    block: scannerSysBody.block,
    tabs: sysTabs(0),
    doors: [door('L', 'F', 'ДВЕРЬ ПЛ'), door('R', 'F', 'ДВЕРЬ ПП'), door('L', 'R', 'ДВЕРЬ ЗЛ'), door('R', 'R', 'ДВЕРЬ ЗП')],
    units: {
      uwb: u('UWBMC', '7F3', 'UWB-ключ'), front: u('F_ZCU', '722', 'Зона спереди'), back: u('B_ZCU', '726', 'Зона сзади'),
      lampL: u('FL_L', '770', 'Фара Л'), lampR: u('FL_R', '771', 'Фара П'),
      left: u('LA_BCM', '720', 'Кузов слева', 'hist', 8), right: u('R_ZCU', '747', 'Зона справа', 'hist', 2),
      hfrm: u('HFRM', '754', 'Радиоключ'), sec: u('SEC', '7D6', 'Бескл. доступ'), nfc: u('R-NCM', '595', 'NFC правый'),
      tailgate: u('PDCMB', '724', 'Проекция багажн.'),
    },
    doorRadars: { fl: u('FL_SDR', '5B2', 'Радар ПЛ'), fr: u('FR_SDR', '5B1', 'Радар ПП'), rl: u('RL_SDR', '5B0', 'Радар ЗЛ'), rr: u('RR_SDR', '5A7', 'Радар ЗП') },
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };
  // Салон. The cabin's state from the car's own properties (autoservice GETs; research of 2026-10-21,
  // values named by DiCarServer): who sits and the belt, heating and ventilation in three levels,
  // massage, the reading lights, the wheel's heating, the charging pads, the fridges' modes and set
  // points, the cluster's message and the HUD. A trip, illustrative: the driver sits belted with the
  // seat warmed to 2 and a massage, the rear left passenger sits unbelted with the reading light on,
  // the left pad charges, the front fridge cools to 0 °C; the parked board is the sweep of 2026-10-20.
  const P = (label, rows) => [[label, 'counterLabel', 'mute'], ...rows];
  const st = (t, ink) => [t, 'cell', ink || 'white'], sm = (t, ink) => [t, 'cellSmall', ink || 'dim'];
  const cabinBlock = { title: 'Салон', aside: '· 18 блоков', line: 'приборы 723 · HUD 7B0 · руль 711 725 · ГУ 741 · пасс. ПК 5E7 · ' +
    'сиденья 750 7B5 7D2 · селектор 7A7 · холодильник 5A0 5F4 7C6 · WCT 733 · MCPA 5E4 · FRL 772 · AMP 751 · ETC 790',
    counters: [['DTC АКТ.', '0'], ['DTC ИСТ.', '4'], ['ПРОЧИТАНО', '18/18']] };
  const scannerSysCabin = {
    top: sysTop('Салон', '19:31:29'), rail: sysRail('Салон'), block: cabinBlock, tabs: sysTabs(0),
    seats: {
      fl: { occupied: true, belt: true, heat: 2, vent: 0, lamp: false },
      fr: { occupied: false, belt: false, heat: 0, vent: 0, lamp: false },
      rl: { occupied: true, belt: false, heat: 0, vent: 0, lamp: true },
      rc: { occupied: false, belt: false },
      rr: { occupied: false, belt: false, heat: null, vent: null, lamp: false },
    },
    wheelHeat: true, pads: { left: 'charging', right: 'standby' }, fridges: { front: 'cold', rear: 'off' },
    plates: { left: ['cluster', 'wheel', 'fl', 'rl', 'rc'], right: ['pads', 'fr', 'fridges', 'rr'] },
    text: {
      cluster: P('ПРИБОРЫ · INS 723 · HUD 7B0', [st('нет сообщений'), sm('проекция вкл')]),
      wheel: P('РУЛЬ · SWS 711', [st('подогрев вкл', 'orangeInk')]),
      fl: P('ВОДИТЕЛЬ · DUCU 750', [st('сидит · пристёгнут'), sm('подогрев 2 · вентиляция выкл'), sm('массаж: волна · средне')]),
      rl: P('2-Й РЯД ЛЕВЫЙ · MLUCU 7D2', [[['сидит · ', 'cell', 'white'], ['не пристёгнут', 'cell', 'orange']], sm('подогрев выкл · вентиляция выкл'), sm('массаж выкл · свет для чтения')]),
      rc: P('2-Й РЯД ЦЕНТР', [st('пусто', 'dim')]),
      pads: P('БЕСПРОВОДНАЯ ЗАРЯДКА · WCT 733', [st('левая: заряжает'), sm('правая: ожидание')]),
      fr: P('ПАССАЖИР · PUCU 7B5', [st('пусто', 'dim'), sm('подогрев выкл · вентиляция выкл'), sm('массаж выкл')]),
      fridges: P('ХОЛОДИЛЬНИКИ · VR 5A0 · RRCP 7C6', [st('передний: холод · 0 °C'), sm('задний: выкл')]),
      rr: P('2-Й РЯД ПРАВЫЙ', [st('пусто', 'dim'), sm('подогрев н/д · вентиляция н/д'), sm('массаж выкл')]),
    },
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };
  // parked and empty, as the sweep of 2026-10-20 read it: nothing on, the right rear's climate not
  // answering (65535), the fridges off with their set points kept, the HUD on
  const scannerSysCabinParked = Object.assign({}, scannerSysCabin, {
    top: sysTop('Салон', '07:36:12'),
    seats: {
      fl: { occupied: false, belt: false, heat: 0, vent: 0, lamp: false },
      fr: { occupied: false, belt: false, heat: 0, vent: 0, lamp: false },
      rl: { occupied: false, belt: false, heat: 0, vent: 0, lamp: false },
      rc: { occupied: false, belt: false },
      rr: { occupied: false, belt: false, heat: null, vent: null, lamp: false },
    },
    wheelHeat: false, pads: { left: 'standby', right: 'standby' }, fridges: { front: 'off', rear: 'off' },
    text: Object.assign({}, scannerSysCabin.text, {
      wheel: P('РУЛЬ · SWS 711', [st('подогрев выкл', 'dim')]),
      fl: P('ВОДИТЕЛЬ · DUCU 750', [st('пусто', 'dim'), sm('подогрев выкл · вентиляция выкл'), sm('массаж выкл')]),
      rl: P('2-Й РЯД ЛЕВЫЙ · MLUCU 7D2', [st('пусто', 'dim'), sm('подогрев выкл · вентиляция выкл'), sm('массаж выкл')]),
      pads: P('БЕСПРОВОДНАЯ ЗАРЯДКА · WCT 733', [st('левая: ожидание'), sm('правая: ожидание')]),
      fridges: P('ХОЛОДИЛЬНИКИ · VR 5A0 · RRCP 7C6', [st('передний: выкл'), sm('задний: выкл')]),
    }),
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 07:36:12', true]] },
  });
  const scannerSysAdas = {
    top: sysTop('Ассистенты', '19:31:29'), rail: sysRail('Ассистенты'),
    block: { title: 'Ассистенты', aside: '· 7 блоков', line: 'ADC 712 · MRR 7F2 · угловые радары 760 761 7C2 7D4 · IPA 701',
      counters: [['DTC', '0'], ['ПРОЧИТАНО', '7/7']] },
    tabs: { items: ['Состояние', 'Сравнение', 'DTC', 'Блоки'], active: 0 },
    units: {
      mrr: u('MRR', '7F2', 'Радар передний'), fl: u('LFCR', '760', 'Угловой ПЛ'), fr: u('RFCR', '761', 'Угловой ПП'),
      rl: u('LRCR', '7D4', 'Угловой ЗЛ'), rr: u('RRCR', '7C2', 'Угловой ЗП'), adc: u('ADC', '712', 'Контроллер ADAS'), ipa: u('IPA', '701', 'Парковка'),
    },
    footer: { buttons: [['Прочитать DTC', 'primary']], notes: [['19 01 09 · 19 02 09 · снимок 19:31:29', true]] },
  };
  const scannerSysDrives = {
    top: sysTop('Электропривод', '19:31:29'), rail: sysRail('Электропривод'),
    block: { title: 'Электропривод', aside: '· 6 блоков', line: 'VCU 7E2 · PDC-VCU 743 · FMCU 7E3 · SMCU 744 · SMCU3 745 · ISG 7E6',
      counters: [['ШИНА ПЕРЕД', '555 В'], ['ШИНА ЗАД', '556 В'], ['DTC', '0']] },
    tabs: { items: ['Состояние', 'Сравнение', 'DTC', 'Блоки'], active: 0 },
    units: { vcu: u('VCU', '7E2', 'Силовая установка'), pdc: u('PDC-VCU', '743', 'Силовой домен') },
    front: { label: 'ПЕРЕДНИЙ МОТОР · FMCU 7E3', rows: ['статор 8 °C · IGBT 9 °C', '0 об/мин · 0,0 Н·м · 0,0 А'] },
    // the energy system's generation state 1006·34F0000A, not ISG's own: parked it reads 0, the stock INVALID
    isg: { label: 'МОТОР-ГЕНЕРАТОР · ISG 7E6', rows: ['DTC 0 · ПО 4.03.13 · 190A1E0D', 'ДВС не работает', 'энергосистема: н/д'] },
    rearLeft: { label: 'ЗАДНИЙ ЛЕВЫЙ · SMCU 744', rows: ['статор 7 °C · IGBT 5 °C', 'н/д об/мин · 0,0 Н·м · 0,0 А'] },
    rearRight: { label: 'ЗАДНИЙ ПРАВЫЙ · SMCU3 745', rows: ['статор 7 °C · IGBT 5 °C', '0 об/мин · 0,0 Н·м · 0,0 А'] },
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };

  const scannerSysHv = Object.assign({}, hvSchema, {
    top: sysTop('ВВ-система', '19:31:29'), rail: sysRail('ВВ-система'),
    block: Object.assign({}, hvSchema.block, { line: 'BMS 7E7 · FMCU 7E3 · SMCU 744 · SMCU3 745 · ISG 7E6 · ECM 7E0 · DCOBC 7E4 · PTC 766 · LBMS 762' }),
    pack: Object.assign({}, hvSchema.pack, { rows: [['U / I', '553 В · −4,5 А'], ['SOC / SOH', '74 % · 99 %'], ['Ячейки', '3 333–3 337 мВ'],
      ['Разброс', '4 мВ'], ['Температура', '13…18 °C'], ['Изоляция', '14,4 · 19,7 МОм']] }),
    tabs: sysTabs(0),
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  });
  const scannerSysPack = {
    top: sysTop('Тяговая батарея', '19:31:29'), rail: sysRail('Тяговая батарея'),
    block: { title: 'Тяговая батарея', aside: '· BMS 7E7', line: 'напряжение и ток 1009 · SOC и SOH 1014 · ячейки и модули 1014 · изоляция 1039',
      counters: [['U', '553 В'], ['SOC', '74 %'], ['DTC', '0']] },
    tabs: { items: ['Состояние', 'Ячейки', 'Потоковые данные', 'Флаги GB/T 32960', 'DTC', 'Блоки'], active: 0 },
    pack: { soc: 74, socText: '74 %', soh: '99 %', cells: '166 ячеек · 14 датчиков', volts: '553 В', current: '−4,5 А',
      insulation: [['канал BMC', '14,4 МОм'], ['канал 2', '19,7 МОм']], isoNorm: 'норма ≥ 500 Ом/В · 277 кОм' },
    ranges: [
      { head: 'ЯЧЕЙКИ, мВ', value: '3 333–3 337', scale: [3000, 3700], span: [3333, 3337], lo: '3 000', hi: '3 700', note: 'разброс 4 мВ' },
      { head: 'ТЕМПЕРАТУРА МОДУЛЕЙ, °C', value: '13–18', scale: [-20, 60], span: [13, 18], mark: 14, lo: '−20', hi: '60', note: 'средняя 14' },
    ],
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };
  // the illustrative warm idle of «Потоковые данные», at its places on the air's way
  const pt = (place, label, rows) => ({ place, label, rows });
  const scannerSysEngineScheme = {
    top: engineTop, rail: sysRail('Двигатель'), block: engineBlock, tabs: engineTabs(0),
    points: [
      pt('inlet', 'ВОЗДУХ НА ВПУСКЕ', ['IAT 24 °C']),
      pt('throttle', 'ДРОССЕЛЬ', ['TPS 6,3 %']),
      pt('manifold', 'КОЛЛЕКТОР', ['MAP 38 кПа']),
      pt('blockTop', 'ДВС', ['1 250 об/мин · LOAD 28 %', 'УОЗ 14,0° · ОЖ 91 °C']),
      pt('blockBottom', 'ТОПЛИВО', ['STFT +3,1 % · LTFT −2,3 %', 'петля замкнута (CL)']),
      pt('catalyst', 'КАТАЛИЗАТОР', ['B1S1 512 °C']),
      pt('oxygen', 'ДАТЧИК O2 B1S2', ['0,68 В']),
    ],
    footer: { buttons: [['Пауза', 'primary'], ['Выбор PID', 'quiet']] },
  };
  const scannerSysClimate = {
    top: sysTop('Климат', '19:31:29'), rail: sysRail('Климат'),
    block: { title: 'Климат', aside: '· 4 блока', line: 'ACC 7B3 · TMIM 7B4 · PTC 766 · AQDM 590 · температуры 1000 · PM2.5 1008',
      counters: [['САЛОН', '21 °C'], ['УЛИЦА', '12 °C'], ['DTC', '0']] },
    tabs: sysTabs(0),
    outside: { label: 'УЛИЦА', temp: '12 °C', pm: '18' },
    cabin: { label: 'САЛОН', temp: '21 °C', pm: '5', inside: true },
    units: { acc: u('ACC', '7B3', 'Климат-контроль'), ptc: u('PTC', '766', 'Нагреватель ВВ'), tmim: u('TMIM', '7B4', 'Терморегул.'),
      aqdm: u('AQDM', '590', 'Кач. воздуха') },
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };
  const scannerSysLv = {
    top: sysTop('Сеть 12 В', '19:31:29'), rail: sysRail('Сеть 12 В'),
    block: { title: 'Сеть 12 В', aside: '· LBMS 762', line: 'DC-DC в DCOBC 7E4 · АКБ LBMS 762 · напряжение 1001',
      counters: [['12 В', '13,7 В'], ['DTC', '0']] },
    tabs: sysTabs(0),
    hv: '553 В', lv: '13,7 В',
    units: { dcdc: u('DCOBC', '7E4', 'DC-DC'), lbms: u('LBMS', '762', 'АКБ 12 В'),
      loads: [u('F_ZCU', '722', 'Зона спереди'), u('LA_BCM', '720', 'Кузов слева', 'hist', 8), u('R_ZCU', '747', 'Зона справа', 'hist', 2), u('B_ZCU', '726', 'Зона сзади')] },
    footer: { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] },
  };

  // The tabs a board above shows only one of, from the same parked snapshot.
  const snapshot = { buttons: [['Обновить', 'primary']], notes: [['снимок 19:31:29', true]] };
  const codesFooter = { buttons: [['Прочитать DTC', 'primary']], notes: [['19 01 09 · 19 02 09 · снимок 19:31:29', true]] };
  // the airbags and the belts: the scheme is the blocks, so its footer reads their memories
  const scannerSysSafety = {
    top: sysTop('Безопасность', '19:31:29'), rail: sysRail('Безопасность'),
    block: { title: 'Безопасность', aside: '· 4 блока', line: 'SRS 7F1 · MSRZ 742 · MSRF 713 · CPD 5E0',
      counters: [['DTC АКТ.', '0'], ['DTC ИСТ.', '0'], ['ПРОЧИТАНО', '4/4']] },
    tabs: sysTabs(0),
    units: { srs: u('SRS', '7F1', 'Подушки'), driverBelt: u('MSRZ', '742', 'Ремень вод.'), passengerBelt: u('MSRF', '713', 'Ремень пасс.'),
      cpd: u('CPD', '5E0', 'Радар детей') },
    footer: codesFooter,
  };
  const scannerSysBodyBlocks = {
    top: sysTop('Кузов', '19:31:29'), rail: sysRail('Кузов'), block: scannerSysBody.block, tabs: sysTabs(2),
    ecus: autoscan.groups.find(g => g.id === 'body').ecus,
    footer: codesFooter,
  };
  const scannerSysPackLive = Object.assign({}, packReadings, {
    top: scannerSysPack.top, rail: scannerSysPack.rail, block: scannerSysPack.block,
    tabs: Object.assign({}, scannerSysPack.tabs, { active: 2 }), footer: snapshot,
  });
  // «Флаги GB/T 32960»: the 14 general alarm flags the stock reporter (gbacqservice) sets from one
  // reading of device 1039 each, in GB/T 32960.3 table 18's bit order; the codes that raise a flag
  // are the reporter's own (research gb-alarm-dtc-boundary-20261021.json). The values are the parked
  // sweep of 2026-10-20: no flag raised.
  const gbFlag = (bit, fid, name, raw, state) => ({ bit, fid, name, raw, state: state || 'ok', word: { ok: 'норма', alarm: 'тревога', none: 'н/д' }[state || 'ok'] });
  const gbGroups = alarmBit => [
    { title: 'БАТАРЕЯ', flags: [
      gbFlag(0, '43800008', 'Разброс температур', 1), gbFlag(1, '2440000C', 'Высокая температура', 0),
      gbFlag(2, '4380000E', 'Перенапряжение', 1), gbFlag(3, '43800010', 'Пониженное напряжение', 1),
      gbFlag(7, '43800014', 'Высокий SOC', 1), gbFlag(8, '43800016', 'Скачок SOC', 1),
      gbFlag(9, '4380000C', 'Несоответствие батареи', 1), gbFlag(10, '4380000A', 'Однородность ячеек', 1),
      gbFlag(18, '43800012', 'Перезаряд', 1),
    ] },
    { title: 'ПРИВОД И ВЫСОКОЕ НАПРЯЖЕНИЕ', flags: [
      gbFlag(12, '26C00010', 'Температура DC-DC', 0), gbFlag(14, '26C00022', 'Состояние DC-DC', 1),
      gbFlag(15, '2400000C', 'Температура контроллера', 0), alarmBit === 16 ? gbFlag(16, '2440003E', 'Блокировка ВВ (HVIL)', 1, 'alarm') : gbFlag(16, '2440003E', 'Блокировка ВВ (HVIL)', 0),
      gbFlag(17, '2400000A', 'Температура мотора', 0),
    ] },
  ];
  const scannerSysPackFlags = {
    top: scannerSysPack.top, rail: scannerSysPack.rail, block: scannerSysPack.block,
    tabs: Object.assign({}, scannerSysPack.tabs, { active: 3 }),
    head: 'ОБЩИЕ ТРЕВОГИ GB/T 32960.3 · 14', counters: [['ТРЕВОГ', '0']],
    groups: gbGroups(null),
    footer: { buttons: [['Обновить', 'primary']], notes: [['1039 · gbacqservice', true], '|', ['снимок 19:31:29', true]] },
  };
  // one flag raised, illustrative: the high-voltage interlock open
  const scannerSysPackFlagsAlarm = Object.assign({}, scannerSysPackFlags, {
    counters: [['ТРЕВОГ', '1', true]], groups: gbGroups(16),
  });
  const scannerSysDrivesCompare = Object.assign({}, drivesSide, {
    top: scannerSysDrives.top, rail: scannerSysDrives.rail, block: scannerSysDrives.block,
    tabs: Object.assign({}, scannerSysDrives.tabs, { active: 1 }), footer: snapshot,
  });
  const scannerSysAdasCompare = Object.assign({}, adasSide, {
    top: scannerSysAdas.top, rail: scannerSysAdas.rail, block: scannerSysAdas.block,
    tabs: Object.assign({}, scannerSysAdas.tabs, { active: 1 }), footer: codesFooter,
  });

  // A parameter on its own page: only it polled, the gap down to 20 ms; an illustrative warm idle
  // with one blip of the throttle, 24 samples a second for 60 s.
  const idleRpm = (() => {
    const out = [];
    for (let i = 0; i <= 1440; i++) {
      const t = 60 - i / 24, blip = t < 38 && t > 30 ? 890 * Math.exp(-(38 - t) * 0.55) * Math.min(1, (38 - t) * 3) : 0;
      out.push([+t.toFixed(3), 1250 + 18 * Math.sin(i * 0.21) + 9 * Math.sin(i * 0.77 + 1) + 6 * Math.sin(i * 2.3) + blip]);
    }
    return out;
  })();
  const warmEct = (() => {
    const out = [];
    for (let i = 0; i <= 1440; i++) { const t = 60 - i / 24; out.push([+t.toFixed(3), 88 + 3 * (1 - Math.exp(-(60 - t) / 25)) + 0.3 * Math.sin(i * 0.17)]); }
    return out;
  })();
  const paramTabs = { items: ['10 с', '60 с', '5 мин'], active: 1 };
  const num = v => String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  const scannerSysEngineRpm = {
    top: sysTop('Двигатель', '15:51:02', 'Z9 GT DM · P · ДВС работает'), rail: sysRail('Двигатель'),
    block: { title: 'Обороты коленвала', aside: '· PID 0C · RPM', line: '7E0 → 7E8 · Mode 01 · только 0C · 24,1 Гц · обмен 18 мс',
      counters: [['МИН', num(Math.min(...idleRpm.map(p => p[1])))], ['МАКС', num(Math.max(...idleRpm.map(p => p[1])))],
        ['СРЕДН.', num(idleRpm.reduce((a, p) => a + p[1], 0) / idleRpm.length)]] },
    tabs: paramTabs,
    label: 'СЕЙЧАС', value: '1 250', unit: 'об/мин', outOfNorm: false,
    stats: [['НОРМА', '—'], ['ЗА 1 С', '+12'], ['ТОЧЕК', '1 441']],
    series: idleRpm, window: 60, scale: [1000, 2500], grid: [1000, 1500, 2000, 2500], gridText: num,
    ticks: [60, 45, 30, 15, 0], min: Math.min(...idleRpm.map(p => p[1])), max: Math.max(...idleRpm.map(p => p[1])),
    footer: { buttons: [['‹ Потоковые данные', 'quiet'], ['Пауза', 'primary'], ['Сбросить мин/макс', 'quiet'], ['Запись', 'quiet']],
      notes: [['24,1 Гц · 18 мс', true]] },
  };
  const scannerSysEngineEct = Object.assign({}, scannerSysEngineRpm, {
    block: { title: 'Температура ОЖ', aside: '· PID 05 · ECT', line: '7E0 → 7E8 · Mode 01 · только 05 · 24,0 Гц · обмен 17 мс',
      counters: [['МИН', '88'], ['МАКС', '91'], ['СРЕДН.', '90']] },
    value: '91', unit: '°C', stats: [['НОРМА', '80…105'], ['ЗА 1 С', '+0'], ['ТОЧЕК', '1 441']],
    series: warmEct, scale: [70, 110], grid: [70, 80, 90, 100, 110], gridText: v => String(Math.round(v)), norm: [80, 105],
    min: Math.min(...warmEct.map(p => p[1])), max: Math.max(...warmEct.map(p => p[1])),
    footer: { buttons: [['‹ Потоковые данные', 'quiet'], ['Пауза', 'primary'], ['Сбросить мин/макс', 'quiet'], ['Запись', 'quiet']],
      notes: [['24,0 Гц · 17 мс', true]] },
  });
  // A pack signal on its own page: one GET of autoservice every 100 ms, the rest of the batch
  // standing; the cell minimum of the parked snapshot, flat at 3 333 mV for the last 60 s.
  const flatCell = (() => {
    const out = [];
    for (let i = 0; i <= 600; i++) out.push([+(60 - i / 10).toFixed(3), 3333]);
    return out;
  })();
  const scannerSysPackCell = {
    top: scannerSysPack.top, rail: scannerSysPack.rail,
    block: { title: 'Напряжение ячейки, мин.', aside: '· 1014·44600010', line: 'autoservice · только этот сигнал · 10,0 Гц',
      counters: [['МИН', '3 333'], ['МАКС', '3 333'], ['СРЕДН.', '3 333']] },
    tabs: paramTabs,
    label: 'СЕЙЧАС', value: '3 333', unit: 'мВ', outOfNorm: false,
    stats: [['НОРМА', '—'], ['ЗА 1 С', '0'], ['ТОЧЕК', '601']],
    series: flatCell, window: 60, scale: [3320, 3350], grid: [3320, 3330, 3340, 3350], gridText: num,
    ticks: [60, 45, 30, 15, 0], min: 3333, max: 3333,
    footer: { buttons: [['‹ Потоковые данные', 'quiet'], ['Пауза', 'primary'], ['Сбросить мин/макс', 'quiet']],
      notes: [['10,0 Гц', true]] },
  };

  // «Ячейки»: one complete sweep the BMS sent on the owner's car (2026-10-21, 1039/9900001F pages
  // 43D and 43C; research/vehicle-diagnostics/battery-page-telemetry-20261021.json), 5.5 s a sweep
  const packCells = [3284, 3283, 3284, 3283, 3282, 3282, 3282, 3282, 3280, 3281, 3280, 3281, 3281, 3282, 3282, 3281, 3282, 3282, 3282, 3283, 3283, 3282, 3282, 3282, 3282, 3282, 3282, 3283, 3283, 3282, 3283, 3283, 3283, 3283, 3284, 3283, 3283, 3283, 3284, 3283, 3284, 3284, 3283, 3283, 3283, 3284, 3283, 3283, 3283, 3284, 3282, 3283, 3283, 3284, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3282, 3282, 3282, 3282, 3282, 3281, 3282, 3282, 3283, 3283, 3284, 3282, 3284, 3285, 3282, 3284, 3283, 3284, 3283, 3284, 3283, 3282, 3280, 3281, 3282, 3283, 3281, 3282, 3281, 3283, 3283, 3283, 3282, 3282, 3283, 3283, 3283, 3283, 3281, 3282, 3282, 3284, 3283, 3282, 3282, 3282, 3281, 3281, 3283, 3282, 3283, 3282, 3282, 3282, 3283, 3282, 3282, 3282, 3282, 3283, 3282, 3282, 3282, 3283, 3283, 3283, 3283, 3283, 3283, 3283, 3282, 3283, 3282, 3283, 3282, 3282, 3283, 3282, 3282, 3282, 3282, 3283, 3280, 3283, 3280, 3281, 3281, 3281, 3282, 3281, 3281, 3282, 3282, 3283, 3283, 3284, 3284, 3282];
  const packProbes = [10, 13, 13, 11, 14, 13, 16, 10, 14, 14, 15, 14, 13, 15];
  const scannerSysPackCells = {
    top: sysTop('Тяговая батарея', '07:36:12'), rail: sysRail('Тяговая батарея'),
    // the pack's head as on its other tabs, its counters at the sweep's moment
    block: Object.assign({}, scannerSysPack.block, { counters: [['U', '545 В'], ['SOC', '74 %'], ['DTC', '0']] }),
    tabs: Object.assign({}, scannerSysPack.tabs, { active: 1 }),
    // the chart: mV from the reference 3 282 (the mean held to a whole millivolt), ±2 steps of 2 mV
    cells: packCells, cellRef: 3282, cellGrid: [-4, -2, 0, 2, 4], cellGridText: ['−4', '−2', '3 282', '+2', '+4'],
    // the sweep under way: 34 pages of 56 came since the one drawn
    sweep: 34 / 56,
    cellMean: packCells.reduce((a, v) => a + v, 0) / packCells.length, cellMeanText: '3 282,5',
    minIndex: packCells.indexOf(Math.min(...packCells)), maxIndex: packCells.indexOf(Math.max(...packCells)),
    cellsHead: 'НАПРЯЖЕНИЕ ЯЧЕЕК, мВ · 166',
    cellsStats: [['МИН', '3 280'], ['МАКС', '3 285'], ['СРЕДН.', '3 282,5'], ['РАЗБРОС', '5']],
    cellTicks: [1, 20, 40, 60, 80, 100, 120, 140, 160],
    cellsAge: 'обход 5,5 с · 0,4 с назад',
    probes: packProbes, probeMin: packProbes.indexOf(Math.min(...packProbes)), probeMax: packProbes.indexOf(Math.max(...packProbes)),
    probesHead: 'ТЕМПЕРАТУРА ДАТЧИКОВ · 14',
    probesStats: [['МИН', '10 °C'], ['МАКС', '16 °C'], ['СРЕДН.', '13,2 °C']],
    footer: { buttons: [['Пауза', 'primary']], notes: [['обход 5,5 с · 0,4 с назад', true], '|', ['1039 · 9900001F · 43D 43C', true]] },
  };
  // the sweep drawn missed its last pages: the cells' last six (16 cells, the last page holds one)
  // and the probes' last (two) keep the sweep before's values, drawn faint; the next sweep 50 pages in
  const scannerSysPackCellsPartial = Object.assign({}, scannerSysPackCells, {
    staleCells: Array.from({ length: 16 }, (_, k) => 150 + k), staleProbes: [12, 13],
    cellsAge: 'обход 5,5 с · 2,6 с назад', sweep: 50 / 56,
    footer: { buttons: [['Пауза', 'primary']], notes: [['обход 5,5 с · 2,6 с назад', true], '|', ['1039 · 9900001F · 43D 43C', true]] },
  });
  // paused: the drawn sweep stays, nothing is read, the button resumes
  const scannerSysPackCellsPaused = Object.assign({}, scannerSysPackCells, {
    sweep: null, cellsAge: 'пауза · снимок 07:36:12',
    footer: { buttons: [['Продолжить', 'primary']], notes: [['пауза · снимок 07:36:12', true], '|', ['1039 · 9900001F · 43D 43C', true]] },
  });

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
    'cluster-stale':       [{ kind: 'cluster' }, scenes.stale],
    'cluster-waking':      [{ kind: 'cluster' }, scenes.waking],
    'cluster-unavailable': [{ kind: 'cluster' }, scenes.unavailable],
    'main-sound':      [{ kind: 'head', mode: 'full', page: 'sound' }, head],
    'main-car':        [{ kind: 'head', mode: 'full', page: 'car' }, head],
    'main-car-engine': [{ kind: 'head', mode: 'full', page: 'car' }, headEngine],
    'main-car-hot':    [{ kind: 'head', mode: 'full', page: 'car' }, headHot],
    'main-first':      [{ kind: 'head', mode: 'full', page: 'sound' }, headFirst],
    'main-paused':     [{ kind: 'head', mode: 'full', page: 'sound' }, headPaused],
    'main-car-neutral':  [{ kind: 'head', mode: 'full', page: 'car' }, headNeutral],
    'main-car-charging': [{ kind: 'head', mode: 'full', page: 'car' }, headCharging],
    'main-car-closed':   [{ kind: 'head', mode: 'full', page: 'car' }, headClosed],
    'two-first':      [{ kind: 'head', mode: 'two', page: 'sound' }, headFirst],
    'one-car-closed': [{ kind: 'head', mode: 'one', page: 'car' }, headClosed],
    'two-sound':      [{ kind: 'head', mode: 'two', page: 'sound' }, head],
    'two-car':        [{ kind: 'head', mode: 'two', page: 'car' }, head],
    'one-sound':      [{ kind: 'head', mode: 'one', page: 'sound' }, head],
    'one-car':        [{ kind: 'head', mode: 'one', page: 'car' }, head],
    'sheet-cluster':   [{ kind: 'sheet', mode: 'full' }, sheets.cluster],
    'sheet-mirrors':   [{ kind: 'sheet', mode: 'full' }, sheets.mirrors],
    'sheet-simulcast': [{ kind: 'sheet', mode: 'full' }, sheets.simulcast],
    'sheet-cast-apps': [{ kind: 'sheet', mode: 'full' }, sheets.castApps],
    'sheet-driver-apps': [{ kind: 'sheet', mode: 'full' }, sheets.driverApps],
    'one-sheet-driver-apps': [{ kind: 'sheet', mode: 'one' }, sheets.driverApps],
    'sheet-speakers':  [{ kind: 'sheet', mode: 'full' }, sheets.speakers],
    'sheet-defaults':  [{ kind: 'sheet', mode: 'full' }, sheets.defaults],
    'sheet-locale':    [{ kind: 'sheet', mode: 'full' }, sheets.locale],
    'sheet-broken':    [{ kind: 'sheet', mode: 'full' }, sheets.broken],
    'sheet-service':          [{ kind: 'sheet', mode: 'full' }, service.ok],
    'sheet-service-trouble':  [{ kind: 'sheet', mode: 'full' }, service.trouble],
    'sheet-service-access':   [{ kind: 'sheet', mode: 'full' }, service.access],
    'sheet-service-screen':   [{ kind: 'sheet', mode: 'full' }, service.screen],
    'sheet-service-restore':  [{ kind: 'sheet', mode: 'full' }, service.restore],
    'sheet-service-restored': [{ kind: 'sheet', mode: 'full' }, service.restored],
    'sheet-service-restore-wifi': [{ kind: 'sheet', mode: 'full' }, service.restoreWifi],
    'sheet-service-warnings': [{ kind: 'sheet', mode: 'full' }, service.warnings],
    'sheet-service-warnings-off': [{ kind: 'sheet', mode: 'full' }, service.warningsOff],
    'one-sheet-service-warnings': [{ kind: 'sheet', mode: 'one' }, service.warningsLost],
    'sheet-service-mobile': [{ kind: 'sheet', mode: 'full' }, service.mobile],
    'sheet-service-mobile-off': [{ kind: 'sheet', mode: 'full' }, service.mobileOff],
    'sheet-service-mobile-unknown': [{ kind: 'sheet', mode: 'full' }, service.mobileUnknown],
    'sheet-service-mobile-absent': [{ kind: 'sheet', mode: 'full' }, service.mobileAbsent],
    'sheet-service-mobile-failed': [{ kind: 'sheet', mode: 'full' }, service.mobileFailed],
    'sheet-service-mobile-busy': [{ kind: 'sheet', mode: 'full' }, service.mobileBusy],
    'one-sheet-service-mobile': [{ kind: 'sheet', mode: 'one' }, service.mobile],
    'one-sheet-service-mobile-off': [{ kind: 'sheet', mode: 'one' }, service.mobileOff],
    'one-sheet-service-restore': [{ kind: 'sheet', mode: 'one' }, service.restored],
    'sheet-service-technical': [{ kind: 'sheet', mode: 'full' }, service.tech],
    'sheet-service-split':   [{ kind: 'sheet', mode: 'full' }, service.split],
    'sheet-service-journal': [{ kind: 'sheet', mode: 'full' }, service.journal],
    'one-sheet-service-trouble': [{ kind: 'sheet', mode: 'one' }, service.trouble],
    'modal-adb':       [{ kind: 'modal', mode: 'full' }, gate],
    'one-modal-adb':   [{ kind: 'modal', mode: 'one' }, gate],
    'modal-adb-wifi':  [{ kind: 'modal', mode: 'full' }, wifiGate],
    'sheet-cloud':     [{ kind: 'sheet', mode: 'full' }, sheets.cloud],
    'one-sheet-cloud': [{ kind: 'sheet', mode: 'one' }, sheets.cloud],
    'sheet-hud':       [{ kind: 'sheet', mode: 'full' }, sheets.hud],
    'sheet-hud-yandexhud': [{ kind: 'sheet', mode: 'full' }, sheets.hudYandexHud],
    'sheet-hud-speed-official': [{ kind: 'sheet', mode: 'full' }, sheets.hudSpeedOfficial],
    'sheet-hud-no-speed': [{ kind: 'sheet', mode: 'full' }, sheets.hudNoSpeed],
    'sheet-hud-no-navigator': [{ kind: 'sheet', mode: 'full' }, sheets.hudNoNavigator],
    'sheet-hud-yandexhud-alone': [{ kind: 'sheet', mode: 'full' }, sheets.hudYandexHudAlone],
    'one-sheet-cluster':   [{ kind: 'sheet', mode: 'one' }, sheets.cluster],
    'one-sheet-cast-apps': [{ kind: 'sheet', mode: 'one' }, sheets.castApps],
    'scanner-sys-scan':  [scanner('sysScan'), scannerSysScan],
    'scanner-sys-topology': [scanner('sysTopology'), scannerSysTopology],
    'scanner-sys-engine': [scanner('sysLive'), scannerSysEngine],
    'scanner-sys-body':  [scanner('sysDtc'), scannerSysBody],
    'scanner-sys-chassis': [scanner('sysChassis'), scannerSysChassis],
    'scanner-sys-body-state': [scanner('sysBodyState'), scannerSysBodyState],
    'scanner-sys-adas':  [scanner('sysAdas'), scannerSysAdas],
    'scanner-sys-drives': [scanner('sysDrives'), scannerSysDrives],
    'scanner-sys-safety': [scanner('sysSafety'), scannerSysSafety],
    'scanner-sys-hv':    [scanner('sysHv'), scannerSysHv],
    'scanner-sys-pack':  [scanner('sysPack'), scannerSysPack],
    'scanner-sys-pack-cells': [scanner('sysPackCells'), scannerSysPackCells],
    'scanner-sys-pack-cells-partial': [scanner('sysPackCells'), scannerSysPackCellsPartial],
    'scanner-sys-pack-cells-paused': [scanner('sysPackCells'), scannerSysPackCellsPaused],
    'scanner-sys-engine-scheme': [scanner('sysEngineScheme'), scannerSysEngineScheme],
    'scanner-sys-engine-rpm': [scanner('sysParam'), scannerSysEngineRpm],
    'scanner-sys-engine-ect': [scanner('sysParam'), scannerSysEngineEct],
    'scanner-sys-pack-cell': [scanner('sysParam'), scannerSysPackCell],
    'scanner-sys-climate': [scanner('sysClimate'), scannerSysClimate],
    'scanner-sys-lv':    [scanner('sysLv'), scannerSysLv],
    'scanner-sys-cabin': [scanner('sysCabin'), scannerSysCabin],
    'scanner-sys-cabin-parked': [scanner('sysCabin'), scannerSysCabinParked],
    'scanner-sys-engine-dtc': [scanner('sysEngineDtc'), scannerSysEngineDtc],
    'scanner-sys-body-blocks': [scanner('sysBlocks'), scannerSysBodyBlocks],
    'scanner-sys-pack-live': [scanner('sysPackLive'), scannerSysPackLive],
    'scanner-sys-pack-flags': [scanner('sysGbFlags'), scannerSysPackFlags],
    'scanner-sys-pack-flags-alarm': [scanner('sysGbFlags'), scannerSysPackFlagsAlarm],
    'scanner-sys-drives-compare': [scanner('sysDrivesCompare'), scannerSysDrivesCompare],
    'scanner-sys-adas-compare': [scanner('sysAdasCompare'), scannerSysAdasCompare],
    'scanner-body-dtc':  [scanner('bodyDtc'), scannerBodyDtc],
    'scanner-ecu-dtc':   [scanner('ecuDtc'), scannerEcuDtc],
    'scanner-ecu-live':  [scanner('ecuLive'), scannerEcuLive],
    'scanner-ecu-fault': [scanner('ecuFault'), scannerEcuFault],
    'scanner-ecu-ident': [scanner('ecuIdent'), scannerEcuIdent],
    'scanner-ecu-ident-updated': [scanner('ecuIdent'), scannerEcuIdentUpdated],
    'scanner-esp-dtc':   [scanner('blockMask'), scannerEspDtc],
    'digits':         [{ kind: 'digits' }, {}]
  };
})(window);
