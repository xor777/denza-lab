# «Бригада сплита»

The split shield's wait. When the user taps «Разделить экран», the app covers the whole screen
while the split scene is built behind it (usually 0.3-4 s, never more than 15); since 2026-09-23
what it shows there is this page: a construction crew in thin blue light lines moving the border
between the panes. The owner approved it that day in place of the toast-shaped card, and it is
normative: `split-crew.html` is the design, and the app's `SplitCrewScene` is its `drawShield(t)`
ported line for line - the same names, the same numbers, the same paths.

| | |
| --- | --- |
| `split-crew.html` | the approved page, verbatim, plus one addition: `#bare-t<ms>` shows the canvas alone at that moment |
| `shot.py` | renders the bare canvas at given moments to `../_shots/split-crew/t<ms>.png`, 2560 x 1600 |
| `compare.py` | lays a screenshot of the app's fixture over a frame and reports what moved |

Only the canvas is the product. The page around it - the duration buttons, the timeline track, the
notes, the placeholder «здесь появляется сцена» after the end - is there for looking at the
animation, and the app has none of it.

## What the page draws

Everything is in dp on the 1280 x 800 screen, two pixels each on the car, and a pure function of
`t`, the milliseconds since the shield appeared. Every line is stroked with one radial field, bright
at the centre of the screen and dim at the edges (four opaque stops, `CORE` to `HALO` at 0.3), and
added (`lighter`); sparks, the thud and the reveal's ring are the pale crown `#C8E4FF`. Behind it
all one haze, `HALO` from 0.2 at the centre to nothing at 720 dp, squashed to 0.64 vertically.

- **0-650 ms**: the site opens as a circle from the centre, 40 dp to 900, decelerating, a crown
  ring on its edge fading out; the haze comes up in 600 ms, the caption in 160.
- **The divider** hangs from the gantry's trolley. It stands in the middle until 1000 ms, then
  goes round every 4000: to the left third (434 dp, the firmware's narrow pane) in 900 ms, held
  200, to the right third (846) in 1800, held 200, back to the middle in 900. The wait never
  promises which split is coming. Two workers push it, one leaning in, the other backing off,
  their gait and lean from the divider's speed, their hands on its girder's faces.
- **The crane** on the left moves a block from the pile to the stack every 4400 ms, 1100 ms into
  its round when the shield appears, so the wait opens with a block in the air: down to the pile
  by 600, picked at 700, up by 1400, across by 2300, down onto the stack by 2900, set down at 3000
  with a thud, up by 3600, home by 4400. The stack is three tall; the next round fades the old
  one out in 600 ms. The signalman waves it on - down, hold, up, this way - by the crane's phase.
- **The frame** on the right: a blow every 600 ms (the first at −250, so 350, 950, …), the hammer
  raised in 0.7 of the period and struck in 0.3, sparks for 480 ms from the last two blows. Blows
  one to four of each six draw a side each in 180 ms, the sixth flashes the frame and it goes in
  400 ms - a frame every 3.6 s. The holder under it jolts with each blow.
- **The caption** `«Запускаем разделение экрана…»` - the app's `split_launch_overlay_text` - is
  laid on, not added: 18 dp, white at 0.9, centred on y = 748 as the canvas's `middle` baseline puts
  it.

## Rendering and comparing

```bash
python3 shot.py                       # the comparison frames: 300, 1650, 2150, 3300, 5200 ms
python3 shot.py 0 3400 6250 12400     # any others
python3 compare.py 2150 app.png       # -> ../_shots/split-crew/compare/t2150.png
```

A frame is the page opened as `split-crew.html#bare-t<ms>` in headless Chrome, a 1280 x 800
window at device scale 2. The page asks Google Fonts for Roboto; `shot.py` shoots a temporary copy
whose font links are replaced by Roboto 400 from `../luminofor/fonts/`, inlined, so a render never
waits on the network or draws the caption in a fallback face. Nothing else in the copy differs.

The debug build draws the app's own view pinned at any moment, over the whole screen:

```bash
./gradlew :denza-apps:assembleDebug
adb -s emulator-5580 install -r -t apps/denza-apps/build/outputs/apk/debug/*.apk
adb -s emulator-5580 shell am start -S -n dev.denza.apps/.debug.LuminoforFixtureActivity \
    --es board split-crew --el t 2150
adb -s emulator-5580 exec-out screencap -p > app.png
python3 compare.py 2150 app.png
```

An emulator the car's size (2560 x 1600 at 320 dpi) puts one dp on two pixels, as the car does. On
a fresh one, confirm the immersive-mode hint first (`settings put secure
immersive_mode_confirmations confirmed`): it dims everything under it, and the first comparison
came out a third darker for it.

On 2026-09-23 nine moments (0, 300, 1650, 2150, 3300, 3400, 5200, 6250, 12400 ms) compared at
0.01-0.10 % of their pixels moved and a mean difference under 0.6 of a level. Outside the caption
it is 0.007-0.04 %: antialiasing along the lines. The caption's row is 1.4 %: the same Roboto
at the same place, which Chrome on macOS draws a touch heavier than Android does.

## How the app is held to it

- `SplitCrewBoardContractTest` reads this page as text and holds `SplitCrewScene` to it constant
  by constant - the screen and the site, the people, the crane, the ladder and the hammer, the
  three blues, the field's and the haze's stops, the reveal, the caption and the easing - and
  the page's caption to the app's string.
- `SplitCrewTimelineTest` holds what the clocks add up to; `SplitCrewGeometryTest` records frames
  across a whole round of three blocks and holds the crew apart (nobody walks through anybody),
  on the screen, and the pushers' hands on the divider.
- `SplitCrewView` draws the scene with `BlendMode.PLUS`, which is `lighter`. Where Skia and
  Chrome's canvas part, the view does what the canvas does: a line to where the path already is
  adds nothing (Skia would draw a round-capped dot - a spark at the moment of a blow came out as
  a white one), a whole turn of an arc is two halves (Skia's `arcTo` takes 360 degrees as none),
  and the `middle` baseline is the middle of Roboto's em box, a quarter em above the baseline.
