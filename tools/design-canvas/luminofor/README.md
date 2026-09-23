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

Boards are shot by headless Chrome with Jura and Roboto from Google Fonts (network needed). The
cluster board is 2560 x 720, the head unit 2560 x 1360 (1280 x 680 dp at 2.0), the panes 1656 and
832 wide - the pixels the car has.

## How the app is held to it

- `LuminoforSpecContractTest` holds `LuminoforSpec` to `spec.json` value by value.
- The renderers draw with `LightPen`, which is this file's verbs (`beam`, `glowFill`, `text`,
  the figures) on an Android canvas; a renderer ported from `luminofor.js` reads line for line
  like it.
- The debug build can draw any board's fixture at the board's pixel size; `compare.py` lays that
  screenshot over the PNG. A screen is finished when the two agree to antialiasing.

The chart keeps the energy contract's ceilings, 60 up and 20 down, on both screens; the approved
page had drawn the cluster's at 30 and 10. Where the contract and a board disagree, the contract
wins and the board moves - which is why this one did.
