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

Boards are shot by headless Chrome with Jura and Roboto from `fonts/` (OFL and Apache, their
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
| `sheet-cast-apps`, `sheet-defaults` | a panel's page of applications, the default-apps panel |
| `sheet-cloud`, `-factory`, `-custom` and `one-sheet-cloud*` | cloud with factory SIM by default and blank replacement SIM values, at full and one-third widths |
| `modal-cloud-generate`, `one-modal-cloud-generate` | confirmation before generating replacement SIM numbers |
| `sheet-service`, `sheet-service-trouble`, `sheet-service-access`, `one-sheet-service-trouble` | the service panel on a healthy car, with two features needing somebody (wide and in the one-third pane), and with no access to the car |
| `sheet-service-screen`, `sheet-service-technical` | the service's two pages: the instruments' screen, and the technical report with the cloud first |
| `sheet-service-split`, `sheet-service-journal` | the technical report scrolled to its end - the split's section and the row to its journal - and «Журнал работы»: a failed open step by step over the two operations before it |
| `one-sheet-cluster`, `one-sheet-cast-apps` | the same panels filling a one-third pane |
| `modal-adb`, `one-modal-adb` | the ADB gate asking for the car's permission |
| `digits` | the wide figures, for the eye |

## How the app is held to it

- `LuminoforSpecContractTest` holds `LuminoforSpec` to `spec.json` value by value.
- `LuminoforScreenContractTest` holds the tiles and the dashboard's three compositions,
  `StripBoardContractTest` and `StripGeometryTest` the strip, `ContourGeometryTest`,
  `ContourFrameBuilderTest` and `ContourFixturesContractTest` the cluster - including the
  numbers `luminofor.js` writes inline rather than in `spec.json`.
- The renderers draw with `LightPen`, which is this file's verbs (`beam`, `glowFill`, `text`,
  the figures) on an Android canvas; a renderer ported from `luminofor.js` reads line for line
  like it. `Silhouette` is `silhouette()`, the ten-kilometre chart on both screens.
- The debug build can draw any board's fixture at the board's pixel size; `compare.py` lays that
  screenshot over the PNG. A screen is finished when the two agree to antialiasing.

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
