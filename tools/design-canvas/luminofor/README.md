# Luminofor

The design of the head-unit dashboard (tiles, both panes, the strip's two pages) and of the
cluster's Contour since 2026-09-23. The owner approved it as a live page after a day of
iterations; this folder is that page's drawing code made deterministic, and it is normative: the
app is held to what these files draw.

| | |
| --- | --- |
| `spec.json` | every number: colours, type, the wide figures' paths, the cluster grid, the strip boxes and positions in window dp for all three widths |
| `luminofor.js` | the drawing code - the approved page's renderer, reading `spec.json` and one fixture |
| `fixtures.js` | the frozen scenes: the data and the exact strings each board is drawn with |
| `scanner.json`, `scanner.js` | «Сервис → Диагностика», the scanner: its numbers (colours, text styles with their line heights, each screen's columns and rows) and its drawing code |
| `shot.py` | renders every board to `../_shots/luminofor/<id>.png` at the displays' own pixels; `--fixtures` exports the scenes to the app's debug assets |
| `compare.py` | lays a screenshot of the app over its board and reports what moved |

## What the design says

The language comes from the car's own screens, not from a reference board: the charging screen
(black ground; large, wide, square figures with small units on their baseline; white
sentence-case labels in a plain sans; live graphics in colour, made of fine lines, with a glow and
a soft haze), the dock (the active icon is `#439FFC`) and SystemUI's surfaces. Everything calm is
flat; only live graphics glow.

- **Figures** are drawn, not typeset: one stroke of constant weight on a 100-unit cap, advance
  close to the cap (`spec.json` → `digits`). The same figures on both screens.
- **Words**: Jura on the cluster (close to the stock cluster's face), the system sans on the head
  unit (as the stock head unit).
- **Compositing is additive** everywhere (`lighter` here, `BlendMode.PLUS` in the app). On black
  that is ordinary alpha; over a card or a haze it is not, and the approved look is the additive
  one - a tile's live icon, for instance, comes out light blue over its card.
- **Cluster**: three groups on one caption line and one baseline - the battery (voltage and five
  temperatures whose captions are the glyphs), the power, the engine and the trip - with one axis
  across the glass and the ten kilometres hanging under it. The corners are left to the stock
  instruments. Exceptions (spread, parked trip detail, the engine giving) are one line under
  their group.
- **Head unit**: flat cards in the car's tones, the live icon blue; the strip is one panel - a
  row of captions and values, and under it the instrument: the analyser as blue columns of fine
  lines on the sound page, the ten-kilometre chart on the car page.

## Rendering and comparing

```bash
python3 shot.py                  # every board
python3 shot.py main-sound       # one
python3 shot.py --fixtures       # after editing fixtures.js
python3 compare.py main-sound app.png
```

Boards are shot by headless Chrome with Jura, Roboto and Roboto Mono from `fonts/` (OFL, their
licences beside them), inlined into the page so a render never waits on the network or falls back
to a system face mid-word. The cluster board is 2560 x 720, the head unit 2560 x 1360 (1280 x 680
dp at 2.0), the panes 1656 and 832 wide - the pixels the car has. A cluster board is also written
as `<id>.bare.png` without the keep-out hatching, which is what the app is compared with.

| boards | |
| --- | --- |
| `cluster-city`, `-launch`, `-regen`, `-engine`, `-hot` | the panel on the move: out, hard out with a run cut at 60, back with a run cut at −20, the engine giving, a hot inverter |
| `cluster-park`, `-charging` | on P: the trip's detail line; the countdown in the figure's place |
| `cluster-spread`, `-filling`, `-unavailable` | the cells drifting apart; 37 points under «за 3,7 км»; no access |
| `cluster-stale`, `-waking` | the link lost - figures gone, captions and the ten kilometres kept; the first seconds, the axis alone |
| `main-sound`, `main-car`, `main-car-engine`, `main-car-hot` | the full dashboard, the strip on each page |
| `main-first`, `two-first` | a fresh install: tiles working, waiting on the driver and broken, no track, the analyser on its floor, the location hint |
| `main-paused` | the track paused: half its light, the pause's bars |
| `main-car-neutral`, `-charging`, `-closed`, `one-car-closed` | «Батарея» in white; on the charger; the shell closed, at two widths |
| `two-sound`, `two-car`, `one-sound`, `one-car` | the two-thirds and one-third panes |
| `sheet-cluster`, `-mirrors`, `-simulcast`, `-speakers`, `-locale`, `-broken` | a tile's settings panel over the dashboard |
| `sheet-hud` | the HUD panel: the glass during a route (the `glass` block), then «Манёвры», «Карта», «Ограничение скорости», «Камеры и опасности» from ordinary Yandex and HUD Speed with its adapter, the cameras in the lane window under the turn and only their GPS bars on the map |
| `sheet-hud-yandexhud` | the same with YandexHUD through the host |
| `sheet-hud-speed-official` | HUD Speed from the store: «Камеры и опасности» off, said in orange |
| `sheet-hud-no-speed` | no HUD Speed: «Камеры и опасности» off, grey |
| `sheet-hud-no-navigator` | no Yandex: only the speed on the glass, every line saying what it waits for |
| `sheet-hud-yandexhud-alone` | a YandexHUD built before the host: it draws turns and map itself, the sign is the one switch |
| `sheet-cast-apps`, `sheet-defaults` | a panel's page of applications, the default-apps panel |
| `sheet-service`, `sheet-service-trouble`, `sheet-service-access`, `one-sheet-service-trouble` | the service panel on a healthy car, with two features needing somebody (wide and in the one-third pane), and with no access to the car |
| `sheet-service-screen`, `sheet-service-technical` | the service's two pages: the instruments' screen, and the technical report with the cloud first |
| `sheet-service-mobile`, `-off`, `-unknown`, `-absent`, `-failed`, `-busy`, `one-sheet-service-mobile`, `-off` | «Мобильный интернет»: the actual stock traffic-monitor package state, one disable button and a verified result or error; unknown/absent has no mutation action, already disabled/busy makes the button inactive |
| `sheet-service-warnings`, `-off`, `one-sheet-service-warnings` | «Предупреждения машины»: the cluster's warning slot as the app records it - since when, the switch, every code with its last sighting and how often under the car's name, «Экспортировать» and «Очистить», the newest events to the second; recording, switched off, and the car not answering in the one-third pane (orange). `WarningBoardContractTest` holds every word to `WarningText` |
| `sheet-service-split`, `sheet-service-journal` | the technical report scrolled to its end - the split's section and the row to its journal - and «Журнал работы»: a failed open step by step over the two operations before it |
| `one-sheet-cluster`, `one-sheet-cast-apps` | the same panels filling a one-third pane |
| `modal-adb`, `one-modal-adb` | the ADB gate asking for the car's permission |
| `sheet-service-restore`, `sheet-service-restored`, `sheet-service-restore-wifi`, `one-sheet-service-restore`, `modal-adb-wifi` | automatic ADB restoration: its page after the last boot - the port open at the first look, closed and opened again by the app (wide and in the one-third pane), closed with no Wi-Fi - a sentence, the switch and the boot's three rows; and the neutral Wi-Fi waiting gate |
| `scanner-summary`, `-summary-active` | «Сводка», the section's entry since v4 (2026-10-23): the verdict, the scan's line, 12 V, insulation, the cells' spread and SOH in the head, the band of record kinds, every record of the last scan by system and block - most severe first, then the most records, the systems into the shorter of two columns - and «62 блока без записей · все ответили»; the parked scan of 2026-10-20 19:31 (`summary()`), and the same with IDC's C3200-7B active and R_ZCU's B130E-01 pending and 12 V low, illustrative: the verdict red, ● and ◌ |
| `scanner-blocks` | «Блоки»: every block on one table - with records first, then the catalog's order - request → reply, protocol, records, F194 and the block's median answer of the scan of 20.10 19:31 (`blocksTable()`), the filters «Все / С записями / Без ответа / ПО изменено» as tabs. Its `groups` are the catalog's blocks every other board shares |
| `scanner-code`, `-code-plain` | a record opened from «Сводка» (`codeScreen()`), full width: the meaning, the status byte from bit 7 to 0 with «·» on the bits the block does not report, how it was read with the record picked out of the raw answer; the block a lost message names and how it stands now; the same failure type elsewhere; the chronology by the journal, the snapshot (19 04) and the counters (19 06). U0121-87 of LA_BCM, its snapshot and counters illustrative; C1170-09 of R_ZCU with no addressee, its meaning its failure type alone, 19 04 and 19 06 answered with no record |
| `scanner-sys-pack-health`, `-health-first` | «Тяговая батарея · Здоровье», the pack's first tab: a table of now, the reference (the norm, or «обычно» - this car's p10-p90 over the journal's 30 days from five values) and 30 days of mini charts whose scale is never narrower than the norm or the resolution × 20; the spread at a full charge from the snapshots at SOC ≥ 95 %; beside it the live cells from their mean, the sweep under way, and the probes (`packHealth()`); the journal illustrative. On the first scan «обычно» and «30 ДНЕЙ» are empty and the full charge «—» |
| `scanner-sys-engine-graphs` | «Потоковые данные · Графики»: four PIDs, one lane and scale each on one 60 s axis, the norm as a band, a cursor reading all four at one moment (`sysGraphs()`); «Таблица / Графики» at the tabs' right end, «Метка» and «Запись» in the footer; illustrative idle with a blip |
| `scanner-journal`, `-journal-first` | «Журнал»: the scans asked for (a block read alone dimmer), the software updates and the cells' snapshots on one day axis, every code from its first read, 12 V by scan and the cells' spread at a full charge by snapshot, the first and the last scan compared in the panel (`journal()`); a full charge's «SOC 97 %» over its dot in the small mono, where no neighbour stands (beside it, until 2026-10-23, the label ran over the next snapshots); illustrative, and one scan with nothing to compare |
| `scanner-journal-compare`, `-journal-records` | the journal's other two tabs on the same five days: «Сравнение» across the page - the two scans' moments, then the codes, the software and the values each on a plate as tall as the longest (`journalCompare()`); «Коды» - a row a code, `JournalCodeRow`'s: sign · КОД · БЛОК · ВПЕРВЫЕ · ПОСЛЕДНИЙ РАЗ · ПРОЧТЕНИЙ · СЕЙЧАС and a chevron, the present ones first with their sign, the gone ones after them muted and with none (`journalCodes()`; it replaced the per-block «Записи» on 2026-10-23). The boards' journal holds one gone code, EPS's U0100-87 of 21.10: its lane in «Хронология» ends there, it is the last row of «Коды» |
| `scanner-report` | «Отчёт» of the scan of 20.10 19:31: its PDF's first page, what goes in, and where it lands - «Сохранить в Загрузки», `Download/Denza/scan-20261020-1931.pdf · .json`; no QR (`report()`) |
| `scanner-sys-chassis`, `-body-state`, `-adas`, `-drives`, `-safety` | «Состояние» on the car from above: tyres, the brake and steering blocks, the rear-wheel steering and the motion controller; doors, zones, lamps, the key's three modules, the door radars, the right NFC reader and the tailgate projection; every sensor and its field; the engine, generator and three motors with their readings, each rear callout naming its side and block, the generator's callout ending with the energy system's generation state (parked: «н/д»), PDC-VCU beside the VCU; the airbags, the belt pretensioners at the B-pillars and the child-presence radar. Since 2026-10-21 the car's readings of the parked sweep in their words and as light (`said()`, `lamp()`): each tyre's pressure judgement and leak, TPMS quiet at the top right, «ПОДВЕСКА» to the motion controller, the parking brake's calipers in the rear wheels; the hood and tailgate on the nose's and tail's line, headlamps, stop and reversing lamps on the corners' arcs, the indicators as marks off the corners, «СВЕТ» and «ЗАДНИЕ ФОНАРИ»; «СЕЛЕКТОР» at the drives' head; the seats beside the belts' tiles as the cabin draws them |
| `scanner-sys-body-state-lit`, `scanner-sys-safety-trip` | the same schemes lit, illustrative: both beams, the hazard lights, stop and reversing lamps and the tailgate open; the cabin trip's seats, the driver buckled and the second row's left sitting unbuckled. `SchemeReadingsTest` holds these and the parked boards to what the builder makes of their codes |
| `scanner-sys-cabin`, `-cabin-parked` | «Салон · Состояние», the car without its roof (`car(c, cx, y0, h, true)`): who sits and the belt, heating and ventilation as warm and cool pips, the reading lights, the wheel, the pads and fridges on the console, the plates level with their places and their bends ordered so no leader crosses another (`cabinScheme`); a trip, illustrative, and the parked sweep of 2026-10-20, which `CabinSchemeTest` holds to the research's values |
| `scanner-sys-hv`, `-engine-scheme`, `-climate`, `-lv` | «Состояние» as schemes: the HV schema at the rail's width, each inverter's bus voltage with its difference to the pack's («555 В +2»), what is off at half its light, the rear inverters sided, the main contactor closed as the BMS reported it (blade across the pins, «замкнут» under its label), the charge port's word under the charger and «ПИТАНИЕ» at the head; the engine's air path (illustrative idle); outside air through the climate unit, the air-quality module under the cabin; the 12 V net with the DC-DC's mode under it and «ПИТАНИЕ» at the head |
| `scanner-sys-body`, `scanner-sys-body-blocks` | a system's DTC across its blocks (the body's ten); its nineteen blocks on the table of «Блоки» |
| `scanner-sys-engine`, `scanner-sys-engine-dtc` | the engine's live data and its DTC lists and readiness at the rail's width |
| `scanner-sys-pack-live` | the pack's readings as the engine's stream reads them (`sysStream()`: `liveTable()` with «УСТР.» heading the device, `rail.packLiveCols`): the device, the name with its FID, the value - orange outside its norm - the minute's min and max, the norm and 60 s of trace; since 2026-10-21 also the selection's power, SOC in tenths, charge power and charge port; the contactor, the charge port, the pack's charge state and the charger's state and fault in words and the two counts with no trace; «Пауза», «Сбросить мин/макс» and the last batch's length; the rows scrolling under the footer. `PackLiveTest` holds every word to what `SystemLive` makes of a minute through the board's extremes |
| `scanner-sys-hv-live`, `-drives-live`, `-chassis-live`, `-safety-live`, `-body-live`, `-climate-live`, `-lv-live` | every other system's «Потоковые данные» on the pack's table (`sysStream()`): its rows of the stream selection in its order, the parked sweep of 2026-10-20 - the first pass the value, as the heads and schemes say it, a reading that moved by the second with both in its extremes, a still one flat; a state in words, orange where its card names the code a fault or an alarm, a word longer than its column reaching back over the name's free room; «обход 0,4 с» illustrative. The tab stands after «Состояние» on these systems' other boards too. `SystemLiveTest` holds each to what its table makes of a minute through the board's extremes and, where the capture is local, of the sweep itself |
| `scanner-sys-pack-flags`, `-flags-alarm` | «Флаги GB/T 32960»: the 14 general alarm flags the stock reporter sets from one device-1039 reading each, a tile a flag in GB/T 32960.3 table 18's bit order - the pack's nine on two rows, the drive's and high voltage's five on the third (`gbFlags`) - each with its bit, FID, name, word and the code read, a raised one lit orange and counted at the head's right; the parked sweep of 2026-10-20 with no flag raised, and the same with the interlock open (illustrative). `GbFlagsTest` holds both to what the builder makes of their codes |
| `scanner-sys-pack-cells`, `-partial`, `-paused` | «Ячейки», a sweep at a time: one complete sweep of the BMS's own pages on the owner's car - 166 cells up or down from a reference held to a whole millivolt on a symmetric grid, the extremes by the BMS's numbers, 14 probes as tiles, the next sweep 34 pages of 56 in as a line along the chart's foot; a sweep drawn that missed its last pages, 16 cells and two probes from the sweep before drawn faint, the next 50 pages in; paused, «Продолжить» and the snapshot's time. `PackCellsBoardContractTest` feeds each board's values to the engine as pages |
| `scanner-sys-engine-rpm`, `-ect`, `scanner-sys-pack-cell` | one parameter on its own page (`sysParam()`), opened from a row of the live data: engine speed with a throttle blip, coolant with its norm as a band, the pack's cell minimum flat at 10 Hz (a signal of the car: no «Запись») |
| `scanner-sys-drives-compare`, `scanner-sys-adas-compare` | the drives and the seven driver-assistance blocks side by side at the rail's width |
| `scanner-body-dtc` | a body block's memory: two historical DTC, each status in words; under them the selected code's status byte as one strip from bit 7 to 0 and, beside it, the read with its record picked out |
| `scanner-ecu-dtc`, `scanner-ecu-fault` | the ECM: DTC lists and monitor readiness as read; the same with an illustrative P0420 and MIL on |
| `scanner-ecu-live`, `scanner-ecu-ident` | the ECM's live data (illustrative idle, 60 s traces); its identification and the cache's, the supported PIDs - no «Сверка с F194» since 2026-10-21: the cache is the record the role was proved by, not a reference. F195 and F193 as the car's updater reads them («4.00.10 · 19031900», «1.0.0»); the identification's own side is wider than the DTC screen's (`ecuIdent.left`/`right`) |
| `scanner-ecu-ident-updated` | a UDS block's identification after an update: the BMS with «ПО обновлено · <дата>» - «был <версия> · <код>» under its F195, the code history's row (illustrative new code, version and day). `SoftwareBoardContractTest` builds it from the session |
| `scanner-esp-dtc` | ESP/IPB 782 opened from «Шасси»: what was asked, the mask's bits as the status strip from 7 to 0 under its availability, nothing came back |
| `digits` | the wide figures, for the eye |

## How the app is held to it

- `LuminoforSpecContractTest` holds `LuminoforSpec` to `spec.json` value by value.
- `LuminoforScreenContractTest` holds the tiles and the dashboard's three compositions,
  `StripBoardContractTest` and `StripGeometryTest` the strip, `ContourGeometryTest`,
  `ContourFrameBuilderTest` and `ContourFixturesContractTest` the cluster - including the
  numbers `luminofor.js` writes inline rather than in `spec.json`.
- `SheetBoardContractTest` holds the paragraph of every tile's own panel board to the words the
  app writes in `helpOf`.
- The renderers draw with `LightPen`, which is this file's verbs (`beam`, `glowFill`, `text`,
  the figures) on an Android canvas; a renderer ported from `luminofor.js` reads line for line
  like it. `Silhouette` is `silhouette()`, the ten-kilometre chart on both screens.
- The debug build can draw any board's fixture at the board's pixel size; `compare.py` lays that
  screenshot over the PNG. A screen is finished when the two agree to antialiasing.

Which Kotlin draws what a board function draws (paths under
`apps/denza-apps/src/main/java/dev/denza/apps/`):

| `luminofor.js` | Kotlin |
| --- | --- |
| `beam`, `glowFill`, `text`, `num` | `LightPen` (`design/luminofor/`); the figures' shapes are `WideDigits`, which `drawDigits` shows |
| `silhouette` | `Silhouette` (`design/luminofor/`) |
| `drawCluster`, `centre`, `keepout`, `trace` | `ClusterDashboardRenderer`, on `ContourGeometry`; its `f` is `ContourFrame`, built by `ContourFrameBuilder` (`feature/cluster/dashboard/`) |
| `drawHead`: the tiles, `tileFace` | `DashboardGrid` (`ui/dashboard/`) places them; `TileFacePainter` and `TileFace` (`ui/components/`) draw one |
| `drawHead`: the strip's sound page, `trackBlock`, `spectrumField`, `reading`, `dots` | `TripPanelRenderer`, `SpectrumRenderer`, `StripInk`, `StripGeometry`, `StripModel` (`feature/trip/`) |
| `drawHead`: the car page, `carChart`, `tempsRow`, `closedPage` | `VehiclePageRenderer` (`feature/trip/`) |
| `drawSheet`: rows, `toggle`, `segmented`, `apps`, `button` | `DenzaSheet` and `SheetInk` (`ui/components/`); each tile's panel is in `ui/dashboard/FeatureSheets.kt` |
| `drawSheet`: `glass`, the HUD panel's picture of the projection | `HudGlassPicture` (`ui/dashboard/`), what it lights from `HudPanel` |
| `drawModal` | `DenzaModalDialog` (`ui/components/DenzaSurfaces.kt`) |
| `spec.json` | `LuminoforSpec` (`design/luminofor/`), held to it by `LuminoforSpecContractTest` |

Which tile is which feature, and where each one's panel, runtime and tests are:
[`docs/feature-map.md`](../../../docs/feature-map.md).

```bash
./gradlew :denza-apps:assembleDebug
adb -s emulator-5580 install -r -t apps/denza-apps/build/outputs/apk/debug/*.apk
adb -s emulator-5580 shell am start -n dev.denza.apps/.debug.LuminoforFixtureActivity --es board main-car
adb -s emulator-5580 exec-out screencap -p > app.png
python3 compare.py main-car app.png
```

An emulator the car's size is enough (2560 x 1600 at 320 dpi, API 35): the board is drawn in the
top-left corner at its own pixels. On 2026-09-23 the twenty-seven dashboard and cluster boards
compared at 0-0.93 % of their pixels moved and a mean difference under one level out of 255. What remains is antialiasing and
Roboto: the board's comes from `fonts/`, the app's is the system's, and a right-aligned figure in
Roboto lands a pixel or two apart.

## The tiles' five tones

Live and working are the lit plate with the dock's blue glyph added onto it; idle is the dark
plate; a tile nothing can be done to is idle. Waiting on the driver and broken keep the lit plate
and light the glyph and the status in the car's own orange `#FF9F19` and red `#FF4046`, **laid
over whole** (`beam(..., over)`, `text(..., { over })`): added onto the plate, as the blue is, the
orange came out yellow and the red pink. Working turns a quarter-circle ring in the glyph's blue
and weight beside the glyph (`spec.json` → `head.icon.ring`); a board holds it at twelve o'clock,
and so does the app under `LocalStillFrame`.

## Settings

The panels a long press opens, the pages they turn into, the service panel and the ADB gate are
the car's own BYD widget kit - read out of CarSettingPlatform's `byd_pvt_*` dark resources - in
Luminofor's grounds (`spec.json` → `sheet`, `drawSheet()` and `drawModal()`):

- the panel is an idle plate's colour (`#17161B`) 480 dp wide at the right edge, or a pane's whole
  window under its caption bar; groups stand on a lit plate's colour (`#2C2B33`, radius 12) with
  hairlines of white at 0.08 between their rows;
- a row is the stock list row: 64 dp for one line, 72 for a title and the reason under it, 80 for a
  title over the chosen applications' icons; titles at the stock 90 % white, summaries at 54 %;
- the stock switch (`#3388FF` track, `#F8F9FA` thumb), the stock tab layout (a white pill at 0.8 on
  a track at 0.1, the chosen word dark on it), the stock large primary button (`#296DCC`, radius
  12, 56 dp) and a quiet one at white 0.06; a quiet button that opens a recovery says so in orange;
- a chosen application wears the stock selection badge (`#1677D9` with a white check), nothing
  else - no coloured edge, no tinted icon well;
- words over a group are sentence case at 54 %, never a tracked capital; a panel's glyph is white
  and centred on its own ink, because it names the panel - its state is the status line's, in the
  car's orange or red;
- the ADB gate is the stock dialog: the one action across the card, the quiet ones under it side by
  side at equal widths, each on its own line in a pane;
- the service panel answers what is wrong and nothing else: «Все функции работают» and the car's
  access on a healthy car; otherwise a status line in the service tile's words and one row per
  feature that needs somebody, its summary in the tile's orange or red (a row's `tone`), opening
  that feature's panel. The access buttons appear only when there is no access. The instruments'
  screen is a page of rows with the stock badge on the chosen one (`kind: 'chosen'`), the report is
  a page of dense key-value rows (`sheet.pair`) one section a feature - `[Раздел]` then `key=value`
  lines, the rule `techBlocks()` and the app's `TechnicalReadings` share - and the version stands at
  the panel's foot;
- the split's section of the report ends in a plate of its own, a label's gap under its readings:
  one choice row, «Журнал работы», opening a page of the same pair rows in the same format - the
  split's last three operations from its journal, the newest step by step (`+N мс` after its first
  line), the two before it by their `итог` alone. No new kit: an owner on a firmware nobody here can
  reach photographs both pages and sends them.

A scene may scroll its panel (`scroll`: dp, or `'end'`). The header scrolls with the rows then, as
it does in the app, where it is the first row of the same column; the debug build opens the page at
the same end (`ServicePanel`'s `firstPageAtEnd`). A value wraps as Android's breaker wraps it: at
spaces, after a slash or a hyphen with no digit after it when a word is wider than its room - a
firmware's fingerprint is `BYD/IVI/` over `DiLink5_1:13/…/1:user/` over `release-keys` on both - and
where the room runs out when even that is not enough.

Every word is placed by its baseline - Roboto's ascent, descent and centring offset are in the
spec - so a panel can be laid over its board: the debug build's `SheetFixtures` builds the real
panel (`FeatureSheet`, `DefaultAppsSheet`, the service panel, the ADB gate) from the scene's
`state`, over the real dashboard. The applications in the scenes have no icons, and both draw the
initial in their place. On 2026-09-23 the thirteen settings boards compared at 0.58-1.91 %; the
residue is a star glyph (`★`) that Roboto does not have and each engine fills from a different
fallback, and baselines Android snaps to whole pixels. On 2026-09-24 the report scrolled to the
split's section compared at 0.93 % and the journal at 1.16 %, the technical report's first screen
still at 1.05 %.

## The chart and the engine's box

The approved page drew the ten kilometres in steps, with the cluster's ceilings at 30 and 10, and
the engine's box in blue. The energy contract had closed all three the other way (§2.3, §2.5), and
where the contract and a board disagree the contract wins and the board moves - which is why this
one did:

- one line through the hundred points and the field under it, split at the zero by clipping -
  ink or white above, blue below - with the ceilings at 60 and 20 and one tick just outside the box
  over each run that was cut. A full window runs edge to edge; a filling one grows leftward from
  the right edge at the same pitch. On the cluster the line keeps the page's persistence, ten runs
  each a step dimmer than the next;
- the engine's box in the history's colours - an ink line, a faint ink field, the sentence grey -
  because blue on the panel means «into the pack» and what the engine's id means in motion is
  still open;
- the car page has no «60» / «−20» gutter: the approved page did not draw one.

## Vehicle diagnostics, the first window

The first diagnostic window - one screen of subsystem nodes, two DTC read targets and the warning
lamps, with its `main-diagnostics-*` boards, `diagnostics.js` and `diagnostics.json` - was removed
on 2026-10-20 together with its code, when «Сервис → Диагностика» became the scanner below
([docs/vehicle-diagnostics-architecture.md](../../../docs/vehicle-diagnostics-architecture.md), 10).
Git history keeps the boards.

## The scanner

The «Сервис → Диагностика» section ([docs/vehicle-diagnostics-architecture.md](../../../docs/vehicle-diagnostics-architecture.md))
is drawn from the owner's canvas «Диагностика · инженерное меню», page «Сканер (v3)», approved
2026-10-20. That page was HTML laid out by a browser; `scanner.js` draws its boards on a canvas
from `scanner.json` and the `scanner-*` fixtures, so a Compose screen can be laid over a board with
`compare.py`. The same evening the section was rebuilt around the car's systems
([architecture](../../../docs/vehicle-diagnostics-architecture.md), 7.2): the `scanner-sys-*` boards
put the systems down the left (`rail()`, `shell()`) and give every tab a board; the full-width
autoscan and system boards (`scanner-scan`, `-topology`, `-bms`, `-drives`, `-hv`, `-brakes`,
`-adas`) went, their drawing kept where a system board reuses it (`scanTable`, `topologyBody`,
`hvBody`, `drivesTable`, `adasTable`, `maskAsks`; `bmsTable` went on 2026-10-21, when the pack's
readings became a live table). On 2026-10-20 the boards compared with the page itself (its HTML rendered
with the same font files at 2x) at 0.5–1.8 % of pixels moved, mean under 2.1 levels, where nothing
was changed on purpose; `scanner-topology`, `-body-dtc`, `-bms` and `-hv` moved further by the
corrections below.

Concept v4 «прибор для диагноста» ([architecture](../../../docs/vehicle-diagnostics-architecture.md), 7.3),
approved 2026-10-23, came over on 2026-10-23: «Сводка», «Блоки», the code, the pack's «Здоровье»,
«Графики», «Журнал» and «Отчёт» are new boards; `scanner-sys-scan`, `-sys-topology` and `-sys-pack`
went (their drawing too: the autoscan's rows, the topology, the pack scheme and its range bars). The
JSON's `scan`, `topology`, `packScheme`, `rangeBar`, `bodyDtc.cols` and `.legend`, the footer's legend
and the colours and styles nothing draws went with the screens on 2026-10-23: what «Блоки» and the
schemes' tiles still need of them is `modules` (its `top`, `head`, `list`) and `tile`, and
`bodyDtc.readX` is where «КАК ПРОЧИТАН» stands. Its rules on every board:

- The rail: «Сводка», «Блоки», the eleven systems, «Журнал». A mark is the count of records after
  the sign of the most severe kind - ● active (red), ◌ pending, ○ in history (orange rings, `sign()`)
  - or, on «Блоки», how many there are; «68/68» is not written while every block answered.
- The header: the car · mileage · mode · 12 V · the link's dot and median exchange · the time.
- Colour is meaning: red now (an active record, MIL on), orange past or out of norm, white a value,
  grey a caption, blue only touch and what is live (a selection, a button, the link, a chart's line).
  «OK» chips and the blue frames of what is well went: the norm says nothing, or «—». A counter in a
  head says only what is an exception; a counter's unit stands small beside its figure.
- The schemes in the dark cockpit: quiet frames (`TILE`), white values, what is off at half its light,
  a deviation orange; a block with records says its sign and count where its address stood.
- A status byte is one strip from bit 7 to 0 under its hex and binary, «·» on the bits outside the
  block's availability (`bitStrip()`); in a table's rows the status is words (`statusWords()`).
- Insulation in MΩ, the norm «≥ 0,28» - except the pack's «Потоковые данные», which still says kΩ
  because the table is `SystemLive`'s and the code reads kΩ (`PackLiveTest`).
- A mini chart's scale is never narrower than its norm or its resolution × 20 (`live()` in
  `fixtures.js` lays the live tables' traces so).
- Every page reads as it opens; a button restarts the read («Опросить снова», «Обновить»).

Before v4:

- Its own language, not the dashboard's: an engineering tool in the stock tester's sense - flat
  plates (`#0A0C0F` bars, `#1A1E24` rules), white and greys, the dock's `#439FFC` for what answered,
  the car's orange only for MIL on. Glow only where the page had one: the link dot, a set status
  bit, the live traces, MIL on.
- Every text style in `scanner.json` → `type` has a size, a weight and a line height; a word's
  baseline is `top + (lh - (ascent + descent) * size) / 2 + ascent * size` with the face's own
  ascent and descent (`fonts`), which is a Compose `lineHeight` with
  `LineHeightStyle(Center, Trim.None)`. Figures, addresses and raw bytes are Roboto Mono, the same
  files as the app's `res/font/roboto_mono_*.ttf`.
- «→» is drawn (`fonts.arrow`: one mono cell, or 0.8 em in Roboto), because neither Roboto nor
  Roboto Mono has U+2192 and a fallback face differs between Chrome and the car. A screen draws it
  the same way.
- The fixtures are data, not strings laid out: an ECU is its name, unit, abbreviation, short
  name, request address, protocol, kind (`ok`, `hist`, `unread`, `none`) and counts. The board
  derives what the page derived - the reply address (+8), a system's summary line, the badge, the
  topology tile's state - so a screen derives the same from its model. The live traces are 61
  points of 0..1, the last 60 s.
- Where the page had a layout fault the board was corrected, and the screen follows the board:
  the FTB column of a body block is 260 dp, not 220 (`01 · Общий электрический отказ` wrapped and
  made its row taller), and the explanation under the codes starts at 328, not 316 (it sat 7 dp
  under the table); the ECM's DTC column is 110 dp with «нет ответа» in 13 dp mono (it broke in
  two lines); a BMS row is 27 dp, not 28 (the fifteenth row ran under the footer); the HV pack is
  280 dp wide at x 240 (two of its labels wrapped under their values), and «ШИНА DC» stands left
  of the bus at its top (it ran under the FMCU tile); a footer's back link is 36 dp like the
  buttons beside it (it was 38: a content-box link with a border); a counter box is its 112 dp plus
  its padding and rule, as the page had it.
- The blocks are `ecu/EcuCatalog.kt`'s 68, in its order and words: 741 and 5E7 are the head
  unit's and the passenger computer's MCUs, and 22 factory-map candidates that answered F194 join
  «Кузов» (the UWB key and its four anchors), «Салон» (ASSY, HUD, ETC, CPD) and the unassigned 25.
  `scanner-sys-topology` (gone with concept v4, 2026-10-23) laid them in nine columns of 112 dp beside the rail - Силовая | Шасси + Климат |
  Ассистенты | Кузов 9 | Кузов 2 + 12 В | Салон | Прочие 9 / 8 / 8 - of 100 x 36 tiles at a 40 dp pitch
  (the full-width board had 137 dp and 124 x 36): the
  abbreviation and address over the short name in 11 dp. A tile's state is its look alone; a block
  with history says «ИСТ n» in place of its address. A column that continues a group hangs from
  the column before it under that group's caption; the trunk ends at the last column that opens one.
- The rear drives and DCOBC answer on their factory routes (SMCU 744, SMCU3 745, DCOBC 7E4; findings,
  «Complete factory map»): every address answers, 19 memories read, and no tile is dashed. A legend entry
  for a tile kind no tile on the board has is left out (`footer`'s `present`), so the topology and
  the HV schematic no longer explain «адрес не найден». ~~Which rear motor each SMCU drives is not
  established~~ - superseded 2026-10-21: the model's scanner catalog names SMCU 744 the left rear
  and SMCU3 745 the right rear, and each one's `19 0A` registry equals its own side's section of
  the manual's index (findings, «ECU roles and reverse catalog crosswalk»; architecture §4); the
  drives' callouts name side and block («ЗАДНИЙ ЛЕВЫЙ · SMCU 744»).
- Screen text without caveats (owner, 2026-10-20): data and names only - no ≈, footnotes,
  «только чтение» or «Пример»; the one source line is «Описания: каталог мануала Z9GT PHEV,
  перевод» in the footer of a codes screen that shows such descriptions (`scanner-ecu-fault`).
- The screens are `feature/diagnostics/ui/` (`DiagnosticsScreen`): `ScannerSpec` is this JSON in
  Kotlin, held by `ScannerSpecContractTest`; the debug build's `ScannerFixtures` turns the `scanner-*`
  scenes into the screens' model - the rail and every system's page from `scanner-sys-*` - and
  `LuminoforFixtureActivity` draws the real section on it at the board's screen. On 2026-10-20,
  before the text pass, the twelve full-width boards compared at 1.39–1.96 % of pixels moved, mean
  0.9–1.3 levels: Chrome lays about 15 % more ink on a glyph than Android does from the same font
  files. The systems' 19 boards and the six block boards compared that evening at 0.65–2.15 %
  (mean 0.40–1.36 levels); the topology's 68 small tiles are the most, the schemes 0.65–1.19 %.
  The three parameter pages (`ParamScreen.kt`) compared on 2026-10-21 at 0.73–0.79 % (mean
  0.49–0.56 levels); the live tables they open from, their rows now tappable, at 1.60–1.73 %.
  After v4, on 2026-10-23 (`emulator-5590`, AVD `denza_hu_scanner`), again after the model's fields:
  all 55 `scanner-*` boards at 0.73–2.14 % (mean 0.47–1.40 levels) but «Отчёт»: 3.45 % whole (mean 3.65), 0.99 % (0.66) with its
  sheet left out (`compare.py scanner-report app.png --skip 24,64,414,616`) - the sheet on the board is
  the board's sketch, on the screen the report's real first page by `ReportPainter`; outside it only
  «0,3 МБ» under «Куда» differs, a size the screen cannot know before a save. The new screens:
  «Сводка» 1.99 and 2.04 %, «Блоки» 1.98, the code 1.47 and 1.08, «Здоровье» 1.51 and 1.45,
  «Графики» 1.06, «Журнал» 1.17 and 0.84, its «Сравнение» 0.82 and «Коды» 2.07 (nineteen rows of
  mono text, as dense as «Блоки»), the HV scheme 1.34.
- Corrected while the system boards were ported: «→» in text had been drawn by the schemes' flow
  arrow, a second function of the same name, so every route on the system boards lost its arrow
  (the flow arrow is `flow()` now); a counter is as wide as its value and a padding's room, rounded
  up to 8 dp (`counterWidth()`; the chassis pressure ran into the next rule); the PID 01 plate
  wraps where it is narrow; the engine's points stand at places named in `engineScheme.points`.
