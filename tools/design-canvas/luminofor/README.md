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
| `main-sound`, `main-car`, `main-car-engine`, `main-car-hot` | the full dashboard, the strip on each page |
| `two-sound`, `two-car`, `one-sound`, `one-car` | the two-thirds and one-third panes |
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
top-left corner at its own pixels. On 2026-09-23 all eighteen compared at 0.13-0.93 % of their
pixels moved and a mean difference under one level out of 255. What remains is antialiasing and
Roboto: the board's comes from `fonts/`, the app's is the system's, and a right-aligned figure in
Roboto lands a pixel or two apart.

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
