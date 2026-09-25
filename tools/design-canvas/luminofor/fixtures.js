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
  const headClosed = Object.assign({}, head, { unavailable: true, message: 'ADB-ключ не подтверждён · Помощь → Диагностика' });

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
        { t: 'note', text: 'На приборную панель за рулём встаёт что-то одно: приборы или любое приложение с машины. Короткое нажатие на плитку ставит его туда и убирает обратно.' }
      ],
      footer: [{ t: 'button', text: 'На приборку' }, { t: 'footnote', text: TAP }]
    }, { tile: 'CLUSTER', navigation: NAV, placements: ['FULL', 'LEFT', 'CENTER', 'RIGHT'], placement: 'FULL', wheel: true, buttonLabel: 'На приборку' }),
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
        { t: 'status', tone: 'broken', text: 'Камеры не отвечают: штатный вид занял видеопоток' },
        { t: 'switch', title: 'Зеркала', on: true },
        { t: 'section', label: 'Где показывать', body: { t: 'segmented', labels: ['По сторонам', 'По центру'], selected: 0 } },
        { t: 'switch', title: 'Улучшение изображения', on: false },
        { t: 'note', text: 'Когда включён поворотник, на экране появляется камера с этой стороны и пропадает вместе с ним. «По центру» показывает обе камеры одну над другой.' }
      ],
      footer: [{ t: 'button', text: 'Проверить камеры' }]
    }, { tile: 'MIRRORS', mirrors: true, position: 'SIDES', processing: false, error: 'Камеры не отвечают: штатный вид занял видеопоток' }),
  };

  // The service panel answers one question - what is wrong - and keeps the rest a row away. On a
  // healthy car it is two quiet rows; a feature that needs somebody is a row in its tile's colour
  // that opens its panel; the car's access grows its buttons only when there is no access. The
  // instruments' screen and the technical readings are pages, and the version is the foot.
  const SVC_VERSION = { t: 'footnote', text: 'Denza Apps 0.6.2 · сборка 53' };
  const SVC_MORE = { t: 'group', rows: [
    { kind: 'choice', title: 'Приборный экран', summary: 'Определён сам: Экран 1 · 1920×720' },
    { kind: 'choice', title: 'Технические сведения', summary: 'Версия, прошивка, состояние функций' }
  ] };
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
  const service = {
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
          { kind: 'choice', title: 'HUD Подсказки', summary: 'Повторите настройку доступа', tone: 'attention' },
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
        SVC_MORE
      ],
      footer: [SVC_VERSION]
    }, Object.assign({}, svcState, { page: 'main', adb: 'Нужно разрешение ADB для Denza Apps', adbDetails: 'Можно вручную отправить ровно один запрос', adbPhase: 'AUTHORIZATION_REQUIRED' })),
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

  // the cloud link: a switch whose reason is a warning, on two lines rather than cut short
  sheets.cloud = sheetOf(10, {
    title: 'Облако',
    blocks: [
      { t: 'switch', title: 'Поддерживать связь с облаком', on: true },
      { t: 'switch', title: 'Держать Wi-Fi включенным', summary: 'На стоянке аккумулятор может разряжаться быстрее', on: false },
      { t: 'note', text: 'Машина выходит в облако через обычный интернет — Wi-Fi или мобильный интернет местной SIM-карты, — и приложение Denza на телефоне видит её заряд и запас хода. «Держать Wi-Fi включенным» не даёт машине выключать Wi-Fi, когда она засыпает, — связь остаётся и на стоянке.' }
    ]
  }, { tile: 'CLOUD', cloud: true, wifi: false });

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
    'sheet-service-technical': [{ kind: 'sheet', mode: 'full' }, service.tech],
    'sheet-service-split':   [{ kind: 'sheet', mode: 'full' }, service.split],
    'sheet-service-journal': [{ kind: 'sheet', mode: 'full' }, service.journal],
    'one-sheet-service-trouble': [{ kind: 'sheet', mode: 'one' }, service.trouble],
    'modal-adb':       [{ kind: 'modal', mode: 'full' }, gate],
    'one-modal-adb':   [{ kind: 'modal', mode: 'one' }, gate],
    'sheet-cloud':     [{ kind: 'sheet', mode: 'full' }, sheets.cloud],
    'one-sheet-cloud': [{ kind: 'sheet', mode: 'one' }, sheets.cloud],
    'one-sheet-cluster':   [{ kind: 'sheet', mode: 'one' }, sheets.cluster],
    'one-sheet-cast-apps': [{ kind: 'sheet', mode: 'one' }, sheets.castApps],
    'digits':         [{ kind: 'digits' }, {}]
  };
})(window);
