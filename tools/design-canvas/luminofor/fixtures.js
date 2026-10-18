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
  // the restore's row says what it did after the last boot, in its colour when it needs somebody
  const svcMore = (restore, tone) => ({ t: 'group', rows: [
    { kind: 'choice', title: 'Диагностика автомобиля', summary: 'Системы, показания и коды неисправностей' },
    { kind: 'choice', title: 'Восстановление ADB', summary: restore, tone },
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
  const svcState = { tile: 'SERVICE', adb: 'ADB-доступ подтверждён', adbDetails: 'Denza Apps использует уже доверенный ключ',
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
  const service = {
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
    { t: 'group', rows: [hudRow('Повороты', YANDEX, true), hudRow('Карта', YANDEX, true),
      hudRow('Ограничение скорости', 'На знаке машины, из Яндекс Навигатора', true), hudRow('Камеры', 'Из HUD Speed', true)] }
  ], {});
  // YandexHUD through this app's host: the same glass, the lines saying YandexHUD
  sheets.hudYandexHud = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: true },
    { t: 'group', rows: [hudRow('Повороты', YHUD, true), hudRow('Карта', YHUD, true),
      hudRow('Ограничение скорости', 'На знаке машины, из YandexHUD', true), hudRow('Камеры', 'Из HUD Speed', true)] }
  ], { navigator: 'YANDEX_HUD' });
  // the ordinary HUD Speed from the store: said as it is, in the car's orange, no error
  sheets.hudSpeedOfficial = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: false },
    { t: 'group', rows: [hudRow('Повороты', YHUD, true), hudRow('Карта', YHUD, true),
      hudRow('Ограничение скорости', 'На знаке машины, из YandexHUD', true),
      hudRow('Камеры', 'Этот HUD Speed показывает их только у себя на экране. Нужна сборка с адаптером', false, false, 'attention')] }
  ], { navigator: 'YANDEX_HUD', radar: 'OFFICIAL' });
  // no HUD Speed at all
  sheets.hudNoSpeed = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: false },
    { t: 'group', rows: [hudRow('Повороты', YANDEX, true), hudRow('Карта', YANDEX, true),
      hudRow('Ограничение скорости', 'На знаке машины, из Яндекс Навигатора', true),
      hudRow('Камеры', 'Появятся, если поставить HUD Speed с адаптером', false, false)] }
  ], { radar: 'NONE' });
  // no navigator: the glass keeps the car's own speed, and every line says what it waits for
  sheets.hudNoNavigator = hudSheet([
    { t: 'glass', turn: false, sign: false, map: false, cameras: false },
    { t: 'group', rows: [hudRow('Повороты', 'Появятся с Яндекс Навигатором или YandexHUD', true, false),
      hudRow('Карта', 'Появится с Яндекс Навигатором или YandexHUD', true, false),
      hudRow('Ограничение скорости', 'Появится с навигатором', true, false),
      hudRow('Камеры', 'Из HUD Speed, во время маршрута в навигаторе', true, false)] }
  ], { navigator: 'NONE' });
  // a YandexHUD built before the host draws the HUD itself: this app keeps the car's sign, and the
  // sign's switch is the panel's one
  sheets.hudYandexHudAlone = hudSheet([
    { t: 'glass', turn: true, sign: true, map: true, cameras: false },
    { t: 'group', rows: [hudRow('Повороты', SELF, true, false), hudRow('Карта', SELF, true, false),
      hudRow('Ограничение скорости', 'На знаке машины, из YandexHUD', true),
      hudRow('Камеры', 'Эта сборка YandexHUD не оставляет им места. В новой сборке они есть', false, false, 'attention')] }
  ], { navigator: 'YANDEX_HUD_ALONE' });

  // board id -> [board, fixture]; px sizes are the displays' own
  // Explicit display fixtures. Never loaded by the production diagnostic controller.
  const diagnostic = {
    tab:'overview', selected:'body', link:'Телеметрия · 20 сигналов', profile:'Профиль 170', park:'P', running:false,
    dtcRead:true, dtcTime:'Снимок 18.10 12:44:00', lampTime:'Индикаторы: 18.10 12:33:45',
    message:'ECU 0x728 прочитан · остальные не опрошены',
    records:['516006','C29D87','C12187','A14802','A25000','A25B00','C22987','C22A87'].map(code=>({code,status:40})),
    nodes:{
      engine:{value:'ВКЛ',caption:'Индикатор · снимок',tone:'amber',rows:[['Индикатор','ВКЛ'],['FID','28700026']],notes:['Снимок: 18.10 12:33:45','Не заменяет чтение DTC']},
      body:{value:'8 DTC',caption:'Имя блока не уточнено',tone:'muted'},
      motor:{value:'29 °C',caption:'Передний мотор',tone:'blue'},
      srs:{value:'ВЫКЛ',caption:'Индикатор · снимок',tone:'muted'},
      abs:{value:'ВЫКЛ / ВЫКЛ',caption:'Индикатор · снимок',tone:'muted'},
      pack:{value:'624 В',caption:'Напряжение HV',tone:'blue',rows:[['Напряжение','624 В'],['Из батареи','1 кВт'],['Средняя T','25 °C'],['Min ячейки','3280 мВ'],['Max ячейки','3291 мВ']],notes:['Экстремумы, без адресов ячеек','DTC батареи не опрошены']},
      cells:{value:'Δ 11 мВ',caption:'Разница min / max',tone:'blue'},
      temp:{value:'25 °C',caption:'Средняя по батарее',tone:'blue'},
      inverter:{value:'32 °C',caption:'Передний инвертор',tone:'blue'},
      charge:{value:'—',caption:'Нет свежих данных',tone:'muted'}
    }
  };
  const diagnosticEmpty={...diagnostic,link:'Нет свежей телеметрии',profile:'Профиль —',park:'P —',dtcRead:false,records:[],dtcTime:'Ещё не прочитаны',lampTime:'Индикаторы: нет снимка',message:'Нет доверенного ADB-доступа',nodes:{}};
  // Frozen scalar GET capture. Shared fast telemetry is deliberately absent in these scenes.
  const diagnosticFilled = {
  "tab": "chassis",
  "selected": "fl",
  "link": "Снимок · 43 дополнительных сигнала",
  "profile": "Профиль 170",
  "park": "P",
  "running": false,
  "dtcRead": true,
  "dtcTime": "Снимок 18.10 12:44:00",
  "lampTime": "Ответ: 18.10 13:19:36 · проверочный снимок",
  "message": "Два ECU прочитаны отдельно",
  "availability": 255,
  "records": [
    {
      "code": "516006",
      "status": 40
    },
    {
      "code": "C29D87",
      "status": 40
    },
    {
      "code": "C12187",
      "status": 40
    },
    {
      "code": "A14802",
      "status": 40
    },
    {
      "code": "A25000",
      "status": 40
    },
    {
      "code": "A25B00",
      "status": 40
    },
    {
      "code": "C22987",
      "status": 40
    },
    {
      "code": "C22A87",
      "status": 40
    }
  ],
  "nodes": {
    "fl": {
      "value": "2,77 бар",
      "caption": "23 °C",
      "tone": "blue",
      "rows": [
        [
          "Давление",
          "2,77 бар"
        ],
        [
          "Температура",
          "23 °C"
        ],
        [
          "Индикатор TPMS",
          "Нет данных"
        ]
      ],
      "notes": [
        "1016 · FID 99000124",
        "Общий индикатор для всех колёс",
        "Порог давления не задан"
      ]
    },
    "door_fl": {
      "value": "Закрыта",
      "caption": "Стекло: 0 %",
      "tone": "blue",
      "rows": [
        [
          "Дверь",
          "Закрыта"
        ],
        [
          "Замок",
          "Открыт"
        ],
        [
          "Стекло · открыто",
          "0 %"
        ],
        [
          "Обучение",
          "Готово"
        ],
        [
          "Термозащита",
          "Не активна"
        ]
      ],
      "notes": [
        "0% — закрыто; 100% — открыто",
        "Ответ: 18.10 13:19:36",
        "Только показания, без управления"
      ]
    },
    "fr": {
      "value": "2,65 бар",
      "caption": "13 °C",
      "tone": "blue",
      "rows": [
        [
          "Давление",
          "2,65 бар"
        ],
        [
          "Температура",
          "13 °C"
        ],
        [
          "Индикатор TPMS",
          "Нет данных"
        ]
      ],
      "notes": [
        "1016 · FID 99000128",
        "Общий индикатор для всех колёс",
        "Порог давления не задан"
      ]
    },
    "door_fr": {
      "value": "Закрыта",
      "caption": "Стекло: 0 %",
      "tone": "blue",
      "rows": [
        [
          "Дверь",
          "Закрыта"
        ],
        [
          "Замок",
          "Открыт"
        ],
        [
          "Стекло · открыто",
          "0 %"
        ],
        [
          "Обучение",
          "Готово"
        ],
        [
          "Термозащита",
          "Не активна"
        ]
      ],
      "notes": [
        "0% — закрыто; 100% — открыто",
        "Ответ: 18.10 13:19:36",
        "Только показания, без управления"
      ]
    },
    "rl": {
      "value": "2,72 бар",
      "caption": "20 °C",
      "tone": "blue",
      "rows": [
        [
          "Давление",
          "2,72 бар"
        ],
        [
          "Температура",
          "20 °C"
        ],
        [
          "Индикатор TPMS",
          "Нет данных"
        ]
      ],
      "notes": [
        "1016 · FID 9900012C",
        "Общий индикатор для всех колёс",
        "Порог давления не задан"
      ]
    },
    "door_rl": {
      "value": "Закрыта",
      "caption": "Стекло: 0 %",
      "tone": "blue",
      "rows": [
        [
          "Дверь",
          "Закрыта"
        ],
        [
          "Замок",
          "Открыт"
        ],
        [
          "Стекло · открыто",
          "0 %"
        ],
        [
          "Обучение",
          "Готово"
        ],
        [
          "Термозащита",
          "Не активна"
        ]
      ],
      "notes": [
        "0% — закрыто; 100% — открыто",
        "Ответ: 18.10 13:19:36",
        "Только показания, без управления"
      ]
    },
    "rr": {
      "value": "2,67 бар",
      "caption": "12 °C",
      "tone": "blue",
      "rows": [
        [
          "Давление",
          "2,67 бар"
        ],
        [
          "Температура",
          "12 °C"
        ],
        [
          "Индикатор TPMS",
          "Нет данных"
        ]
      ],
      "notes": [
        "1016 · FID 99000130",
        "Общий индикатор для всех колёс",
        "Порог давления не задан"
      ]
    },
    "door_rr": {
      "value": "Закрыта",
      "caption": "Стекло: 0 %",
      "tone": "blue",
      "rows": [
        [
          "Дверь",
          "Закрыта"
        ],
        [
          "Замок",
          "Открыт"
        ],
        [
          "Стекло · открыто",
          "0 %"
        ],
        [
          "Обучение",
          "Готово"
        ],
        [
          "Термозащита",
          "Не активна"
        ]
      ],
      "notes": [
        "0% — закрыто; 100% — открыто",
        "Ответ: 18.10 13:19:36",
        "Только показания, без управления"
      ]
    },
    "cabin": {
      "value": "19 °C",
      "caption": "Салон / наружный воздух",
      "tone": "blue",
      "rows": [
        [
          "В салоне",
          "19 °C"
        ],
        [
          "Снаружи",
          "19 °C"
        ],
        [
          "PM2.5 · салон",
          "17 мкг/м³"
        ],
        [
          "PM2.5 · снаружи",
          "30 мкг/м³"
        ]
      ],
      "notes": [
        "Температура: HVAC SDK · 1000",
        "PM2.5: PM2p5 SDK · 1008",
        "Порог качества воздуха не задан"
      ]
    },
    "lbms": {
      "value": "0 DTC",
      "caption": "Низковольтная батарея",
      "tone": "muted",
      "rows": [],
      "notes": []
    },
    "body": {
      "value": "8 DTC",
      "caption": "Имя блока не уточнено",
      "tone": "muted",
      "rows": [],
      "notes": []
    },
    "lowv": {
      "value": "13,8 В",
      "caption": "Питание низкого напряжения",
      "tone": "blue",
      "rows": [
        [
          "Напряжение",
          "13,8 В"
        ],
        [
          "Память ошибок",
          "LBMS · 0x76A"
        ]
      ],
      "notes": [
        "1001 · FID 43400028",
        "Источник напряжения: Body SDK",
        "DTC низковольтной батареи → 76A"
      ]
    },
    "pack": {
      "value": "—",
      "caption": "SOC 61 % · SoH 99 %",
      "tone": "muted",
      "rows": [
        [
          "Напряжение",
          "—"
        ],
        [
          "Из батареи",
          "—"
        ],
        [
          "Заряд SOC",
          "61,0 %"
        ],
        [
          "SoH · оценка BMS",
          "99 %"
        ],
        [
          "Средняя T",
          "—"
        ]
      ],
      "notes": [
        "SoH не означает исправность",
        "Низкий заряд: Нет данных · снимок",
        "DTC батареи не опрошены"
      ]
    },
    "cells": {
      "value": "—",
      "caption": "Разница min / max",
      "tone": "muted",
      "rows": [
        [
          "Min",
          "—"
        ],
        [
          "Max",
          "—"
        ],
        [
          "Число ячеек",
          "166"
        ],
        [
          "Изоляция · raw",
          "14128"
        ]
      ],
      "notes": [
        "Изоляция: единица не установлена",
        "Адреса ячеек не читаются",
        "Порог дисбаланса не задан"
      ]
    },
    "temp": {
      "value": "—",
      "caption": "Нет свежих данных",
      "tone": "muted",
      "rows": [
        [
          "Минимальная",
          "13 °C"
        ],
        [
          "Средняя",
          "—"
        ],
        [
          "Максимальная",
          "14 °C"
        ]
      ],
      "notes": []
    },
    "inverter": {
      "value": "—",
      "caption": "Нет свежих данных",
      "tone": "muted",
      "rows": [
        [
          "Температура",
          "—"
        ],
        [
          "Шина передняя",
          "556 В"
        ],
        [
          "Шина задняя",
          "555 В"
        ]
      ],
      "notes": []
    },
    "charge": {
      "value": "—",
      "caption": "Нет свежих данных",
      "tone": "muted",
      "rows": [
        [
          "Мощность",
          "—"
        ],
        [
          "Ток · SDK",
          "-5,0 А"
        ]
      ],
      "notes": [
        "1009 · FID 44400018",
        "Знак тока пока не интерпретируем",
        "DTC зарядного блока не опрошены"
      ]
    },
    "uds": {
      "value": "2 ECU",
      "caption": "Чтение 0x19 по кнопке",
      "tone": "muted",
      "rows": [
        [
          "Кузов · ответ",
          "0x728"
        ],
        [
          "LBMS · ответ",
          "0x76A"
        ],
        [
          "Маска",
          "0xFF"
        ]
      ],
      "notes": [
        "Только профиль Z9GT · 170",
        "Перед запросом: P, 0 км/ч",
        "Сеансы и очистка не выполняются"
      ]
    },
    "gateway": {
      "value": "43 сигнала",
      "caption": "autoservice · GET",
      "tone": "blue",
      "rows": [
        [
          "Общий поток",
          "0 сигналов"
        ],
        [
          "Дополнительные",
          "43/43"
        ],
        [
          "Обновление",
          "Каждые 5 с"
        ],
        [
          "Устаревание",
          "15 с"
        ],
        [
          "DTC",
          "По кнопке"
        ]
      ],
      "notes": [
        "Давность ответа, не пакета CAN",
        "Показания исчезают при устаревании",
        "Во время DTC доп. опрос на паузе"
      ]
    },
    "unread": {
      "value": "Не опрошены",
      "caption": "Остальные ECU",
      "tone": "muted"
    },
    "health": {
      "value": "Не определено",
      "caption": "Общее состояние",
      "tone": "muted"
    }
  },
  "lbms": {
    "records": [],
    "dtcRead": true,
    "dtcTime": "Снимок 18.10 13:21:00",
    "availability": 9
  }
};
  // Warning GET capture 10:52 UTC; raw 0 remains unavailable. Not simulated healthy lamps.
  const diagnosticResearch = {...diagnosticFilled, tab:'drive',selected:'engine_warn',
    lampTime:'Лампы: 18.10 13:52:28 · проверочный снимок',message:'Данные разных сохранённых снимков',
    nodes:{...diagnosticFilled.nodes,
      engine:{value:'ВКЛ',caption:'Индикатор · снимок',tone:'amber',rows:[['Индикатор','Включён'],['ДВС','—'],['Обороты','—'],['Мало топлива','Недоступно · 0'],['Топливо','99 %']],notes:['Лампа не сообщает причину отказа','ДВС: память EMS не прочитана','Доп. лампы → Предупреждения ДВС']},
      engine_warn:{value:'1 включено',caption:'Лампы: 1/4 доступны',tone:'amber',rows:[['Двигатель','Включён'],['Перегрев ОЖ','Недоступно · 0'],['Давление масла','Недоступно · 0'],['Фильтр частиц GPF','Недоступно · 0']],notes:['Снимок: 18.10 13:52:28','EMS: DTC не прочитаны','Недоступно ≠ выключено']},
      power_warn:{value:'Нет данных',caption:'Лампы: 0/4 доступны',tone:'muted',rows:[['Неисправность HV','Недоступно · 0'],['Перегрев HV','Недоступно · 0'],['Силовая система','Недоступно · 0'],['Низкий заряд','Недоступно · 0']],notes:['Снимок: 18.10 13:52:28','BMS / приводы: DTC не прочитаны','Недоступно ≠ выключено']},
      abs:{value:'ВЫКЛ / ВЫКЛ',caption:'Индикатор · снимок',tone:'muted',rows:[['ABS · антиблокировка','Выключен'],['ESP · стабилизация','Выключен'],['Тормоза','Выключен'],['Усилитель руля','Выключен'],['Давление шин','Выключен']],notes:['ABS / ESP: DTC не прочитаны','U0121: запись из ECU 0x728','ESP OFF: источник недоступен']},
      unread:{value:'Не опрошены',caption:'EMS / HV BMS / ABS / приводы',tone:'muted',rows:[['ДВС · EMS','Не опрошен'],['HV батарея · BMS','Не опрошена'],['ABS / ESP','Не опрошен'],['Передний привод','Не опрошен'],['Задние приводы','Не опрошены']],notes:['Адреса ДВС / HV / ABS не доказаны','Кандидаты в карте ≠ опрос ECU','Заводской словарь не получен']}
    }};
  const diagnosticEvidenceNodes = {
  "ecu_identity": {
    "value": "Не уточнён",
    "caption": "Имя блока 720 → 728",
    "tone": "muted",
    "rows": [
      [
        "Запрос → ответ",
        "720 → 728"
      ],
      [
        "CDC · вариант 1",
        "F195 / F194"
      ],
      [
        "CDC · вариант 2",
        "C295 / C294"
      ],
      [
        "R_ZCU · вариант 2",
        "C495 / C494"
      ],
      [
        "Точное имя",
        "Не доказано"
      ]
    ],
    "notes": [
      "В карте один адрес у трёх записей",
      "Наборы идентификаторов различны",
      "По адресу нельзя выбрать имя ECU"
    ]
  },
  "ecu_inventory": {
    "value": "225 записей",
    "caption": "Кандидаты из кэша",
    "tone": "muted",
    "rows": [
      [
        "Версионных записей",
        "225"
      ],
      [
        "Версия не получена",
        "49"
      ],
      [
        "Установка в авто",
        "Не доказана"
      ],
      [
        "Адреса в кэше",
        "Отсутствуют"
      ],
      [
        "Дата материалов",
        "18.10.2026"
      ]
    ],
    "notes": [
      "Кэш содержит варианты блоков",
      "Это не 225 опрошенных ECU",
      "Ошибки блоков здесь не читались"
    ]
  },
  "ecu_routes": {
    "value": "8 записей",
    "caption": "Частичная карта · кандидаты",
    "tone": "muted",
    "rows": [
      [
        "Media · CAN",
        "741 → 749"
      ],
      [
        "ASSY · CAN",
        "5E7 → 5EF"
      ],
      [
        "F_ZCU · CAN",
        "722 → 72A"
      ],
      [
        "CDC_SOC · Ethernet",
        "0010 / 0001"
      ],
      [
        "CDC · F195/F194",
        "720 → 728"
      ],
      [
        "CDC · C295/C294",
        "720 → 728"
      ],
      [
        "R_ZCU · F195/F194",
        "747 → 74F"
      ],
      [
        "R_ZCU · C495/C494",
        "720 → 728"
      ]
    ],
    "notes": [
      "Ответ сервера · 18.10.2026",
      "Установка и DTC не подтверждены",
      "ДВС / HV / ABS: адресов здесь нет"
    ]
  }
};
  [diagnostic, diagnosticEmpty, diagnosticFilled, diagnosticResearch].forEach(f => Object.assign(f.nodes, diagnosticEvidenceNodes));
  root.LUMINOFOR_BOARDS = {
    'main-diagnostics-status': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'dtc',selected:'body',code:2,detail:true,statusDetail:true}],
    'main-diagnostics-page2': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'dtc',selected:'body',page:1,code:5}],
    'main-diagnostics-identity': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'sources',selected:'ecu_identity'}],
    'main-diagnostics-inventory': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'sources',selected:'ecu_inventory'}],
    'main-diagnostics-routes': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'sources',selected:'ecu_routes'}],
    'main-diagnostics-routes-page2': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'sources',selected:'ecu_routes',page:1}],
    'main-diagnostics-engine': [{kind:'diagnostics',mode:'full'}, diagnosticResearch],
    'main-diagnostics-power-warnings': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'battery',selected:'power_warn'}],
    'main-diagnostics-abs': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'chassis',selected:'abs'}],
    'main-diagnostics-coverage': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'dtc',selected:'unread'}],
    'main-diagnostics-code': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'dtc',selected:'body',code:2,detail:true}],
    'main-diagnostics-code-unknown': [{kind:'diagnostics',mode:'full'}, {...diagnosticResearch,tab:'dtc',selected:'body',code:0,detail:true}],
    'main-diagnostics-chassis': [{kind:'diagnostics',mode:'full'}, diagnosticFilled],
    'main-diagnostics-body': [{kind:'diagnostics',mode:'full'}, {...diagnosticFilled,tab:'body',selected:'door_fl'}],
    'main-diagnostics-lbms': [{kind:'diagnostics',mode:'full'}, {...diagnosticFilled,tab:'dtc',selected:'lbms'}],
    'main-diagnostics-battery-data': [{kind:'diagnostics',mode:'full'}, {...diagnosticFilled,tab:'battery',selected:'pack'}],
    'main-diagnostics': [{kind:'diagnostics',mode:'full'}, diagnostic],
    'main-diagnostics-battery': [{kind:'diagnostics',mode:'full'}, {...diagnostic,tab:'battery',selected:'pack'}],
    'main-diagnostics-empty': [{kind:'diagnostics',mode:'full'}, diagnosticEmpty],
    'main-diagnostics-stale': [{kind:'diagnostics',mode:'full'}, {...diagnosticEmpty,dtcRead:true,records:diagnostic.records,dtcTime:diagnostic.dtcTime,nodes:{body:diagnostic.nodes.body},message:'ECU не ответил вовремя'}],
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
    'digits':         [{ kind: 'digits' }, {}]
  };
})(window);
