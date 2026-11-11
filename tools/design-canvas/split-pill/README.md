# Пилюля сплита

The split screen's own control. A short white handle sits under the panes, centred on the
divider; a tap turns it into a glass pill that holds the pairs of apps the user splits with, the
pair on screen marked, and two buttons: «+» to change one pane, the star to keep the pair. When a
pane stands empty the pill opens by itself and offers what goes back into it. The owner approved
the page on 2026-11-11, and it is normative: `split-pill.html` is the design, and `spec.json` is
its numbers.

| | |
| --- | --- |
| `split-pill.html` | the approved page, plus `SPEC` (every number, one object), `#bare-<board>` and `#bare-<motion>-t<ms>` (the stage alone, frozen), and `#spec` (SPEC as JSON) |
| `spec.json` | SPEC as the page holds it, written by `shot.py --spec`; read it, never edit it |
| `shot.py` | renders the boards and timed frames to `../_shots/split-pill/<id>.png`, 2560 x 1600, and writes `spec.json` |
| `compare.py` | lays a screenshot of the app over a board and reports what moved |

## Product and stand

The product is the pill, the handle, the flights (an app's icon travelling from the pill to its
pane) and the launch placeholders (the icon resting at the pane's centre with its ring until the
app's screen comes up). Everything they are drawn and moved with is in `SPEC`; the page's
stylesheet reads it through custom properties and its script reads it directly, so no pill number
is written anywhere else.

The rest is the stand, there to judge the pill in context, and the app has none of it: what the
panes show (the player, map, browser, messenger, video and podcasts), the sample icons' colours
and glyphs, the picker grid in an empty pane and the pane's close cross, the dock, the home
screen, and the buttons and caption under the stage. The pane geometry and the divider's detents
are the firmware's, written into `SPEC.stage` because the pill is placed by them; the stage's own
motions (`SPEC.stage.motion`: detent change, panes folding to Home and back, a pane re-laid or
emptied) imitate the car and are not ours to build.

## The pill

All lengths are dp on the 1280 x 800 screen, two pixels each on the car.

- **Stage.** Panes from y 56, 680 tall, 12 in from the sides, 12 apart, corner 10. The divider's
  centre stands at 434 (wide right pane) or 846 (wide left pane).
- **Handle.** 44 x 5, white at 0.35, its bottom edge at y 727, centred on the divider and kept 24
  from the stage's sides. Its touch target is 96 x 48. After 3 s untouched it dims to 0.2.
- **Glass.** The open pill is 88 tall with its bottom at y 720 (108 in edit), as wide as its
  row: 18 padding at each end (24 in edit), 22 between chips, 10 between buttons, 18 on either
  side of a separator. Fill `rgba(12, 13, 17, 0.9)` with a white sheen from 0.04 at the top to
  nothing at 46 %; a 1 dp rim, white 0.34 at the top, 0.12 at 42 %, 0.07 at the bottom; two
  shadows, 0 14 44 black 0.55 and 0 1 0 black 0.6. With window blur the fill is
  `rgba(14, 15, 20, 0.58)` over `blur(22px) saturate(1.3)`. The row is clipped to the pill's
  capsule. Separators are 1 x 40, white 0.12; buttons 56 round, white 0.075, a 24 glyph at
  stroke 1.75.
- **A pair chip** sits in a 72-tall item, its face 56 tall. Apps are 52 icons, corner 14.
  *Stacked* (86 wide, a whole pair): the left pane's app at (2, 2), the right pane's behind it at
  (36.6, 4.6) and 0.9, cut round by a 2 dp ring that follows the front icon (56 square, corner
  16). *Opened* (116 wide, an incomplete pair): both at full size side by side, 8 apart, the
  right one at (62, 2). The missing app is a breathing contour: a blue dashed outline (6 on, 4.4
  off, stroke 2, 1 inset, corner 13) and the app's glyph in blue, with a 2.6 glow, its opacity
  0.5 to 1 and back every 2.4 s. A chip at most 1.08 of the way between forms on a spring's
  overshoot.
- **Live mark.** Under the chip whose pair is on screen: a blue bar 14 x 3, corner 2, at y 69 of
  the item, glowing 3 and 9; centred under the whole chip, 43 from its left when stacked, 58 when
  opened, gliding between them with the form. It fades in and out in 220 ms.
- **Badges** in edit: 24 round at (-8, 0) of the chip, touch target 8 beyond. Unpin is red
  `#ff4046` with a white 12 x 2 bar; pin is `#1c1e24` with a 1 dp white 0.8 ring and a 13
  outline star at stroke 1.7. Both drop 0 1 3 black 0.5.
- **Long-press ring.** A blue 1.5 line round the stacked chip, 5 out (96 x 66, corner 21),
  drawing itself while the press is held.
- **Flight and placeholder.** The icon at 96, corner 26, shadow 0 14 34 black 0.5. At rest it
  wears a ring 11 out, corner 36, blue 1.5 with a 10 glow outside and 8 inside, pulsing between
  0.35 at 0.98 and 1 at 1.03.
- **Press bloom.** A 90 round glow (pale blue 0.55 at the centre, blue 0.26 at 38 %, nothing at
  70 %), added, wherever a finger lands.

## When it does what

- **The handle opens it.** A tap on the handle, or on the dock's split icon while the panes are
  up, opens the pill on the pairs: pinned pairs first, then recent ones, three chips at most, then
  «+» and the star. Pair memory keeps every pinned pair and the five latest others; a pair becomes
  the latest when both panes are filled with it.
- **It closes by itself only when it was opened by hand.** Showing pairs, it melts back into the
  handle 5 s after the last touch on it, or at once on a touch anywhere outside it.
- **It opens by itself only while a pane stands empty.** 400 ms after a pane is emptied (its
  close cross, or the firmware evicting the app), the pill opens on the repair row: the pair that
  was on screen as an opened chip with the missing app as a contour; a separator; up to three
  single apps for the empty pane (that pane's own recent apps, then the rest, never the pair's);
  a separator; «+»; the star. It stays open while any pane is empty. Tapping the chip brings the
  missing app back (the contour fills, then the icon flies); tapping a single app launches it
  into the empty pane - the pane last touched, else the wide one, else the other. When both panes
  are full again the chip closes back to stacked and the pill melts into the handle. If the pill
  was showing pairs when the pane emptied, the chip of that pair opens up in place.
- **Choosing a pair.** Tapping a chip puts its pair on screen. For each pane whose app changes,
  the icon flies from the chip to the pane's centre on an arc 80 above the higher end, rests
  there with its ring while the old screen dims to 0.3, and dissolves into the app's screen. The
  pill melts into the handle at the tap.
- **Edit.** A long press (450 ms) on a chip in the pairs row: the ring draws, the chip lifts, and
  the pill grows to 108 with every pinned pair in it (at least three chips) and a badge on each:
  unpin on pinned pairs, pin on the others. A pinned chip dragged sideways (beyond 6) reorders the
  pins, 14 of give past the end slots. The check button, a touch outside, or 10 s untouched
  leaves edit.
- **The star** pins the pair on screen: it flashes, turns solid, and the pair's chip nudges. A
  solid star means the pair on screen is pinned; tapping it then only nudges that chip.
- **«+»** empties the pane last touched (else the wide one) so another app can go in, and the
  pill switches to the repair row in place. With that pane already empty it only nudges.
- **Home** fades the pill out with the panes; it comes back as the handle. The dock's split icon
  brings the panes back and opens the pill 240 ms later.
- **Divider detents.** The pill follows the divider's centre as the panes change between 434 and
  846, kept 24 from the stage's sides.
- **Touch.** A press scales the touched item to 0.93 and blooms under the finger. On the pill a
  tap is a release that moved less than 10 (on a pair chip, also within 200 ms: a longer hold is a
  long press in the making, and moving cancels it); on a badge, within 14.

## Motion

Every moving value leaves from where it is, with the speed it has: a new target redirects it in
mid-flight, a spring keeping its velocity and a tween starting from the current value, so any
gesture can interrupt any motion and nothing jumps. A spring is given as the time it settles in
and its damping ratio z: ω = (z < 1 ? 4.6 / z : 6.6) / seconds, stiffness ω², damping 2zω,
integrated at 240 steps a second. Tweens: `out` is 1 − (1 − t)³, `outQ` 1 − (1 − t)², `inOut` the
cubic in-out, `std` `cubic-bezier(.4,0,.2,1)`, `settle` `cubic-bezier(.2,.8,.2,1)`, `pop`
`cubic-bezier(.34,1.56,.64,1)`, `expo` `cubic-bezier(.16,1,.3,1)`; others are CSS's own.

| What | Motion |
| --- | --- |
| Pill opens | height spring 300 ms z 1; width spring 340 ms z 0.74; glass in 150 ms `out` |
| Row items arrive (opening) | rise 8 and fade in, 280 ms `settle`, at 60 ms + 28 ms per item |
| Pill closes | row fades 90 ms `outQ`; width and height spring 200 ms z 1 from 70 ms; glass out 160 ms `inOut` from 60 ms; items dropped at 420 ms |
| Reopened while closing | row back in 140 ms `out`, width on the opening spring |
| Row changes width | spring 280 ms z 0.9; kept items slide on a spring 230 ms z 1 |
| New item in an open row | from 0.6, 240 ms `pop`; a leaving one to 0.8 in 140 ms `ease-in` |
| Edit in and out | height spring 260 ms z 0.78; badges pop to 1.22 at 62 %, 180 ms `ease-out`, 30 ms per chip; leave in 120 ms `ease-in` |
| Unpinned chip leaves | shrinks into its badge, 220 ms `cubic-bezier(.5,0,.75,0)`; the badge 240 ms from 60 ms; the chip after it enters at 160 ms |
| Chip opens or closes its form | spring 260 ms z 0.82; contour in 300 ms `inOut`, out 180 ms `out`; opening by itself starts 220 ms after the pill |
| Contour breathing | 0.5 to 1 and back, 2.4 s `ease-in-out` |
| Handle | dims 3 s after the last touch, to 0.2 in 700 ms `inOut`; wakes in 160 ms `out` |
| Press | 0.93 in 70 ms `outQ`; long press lifts to 1.08 from 120 ms over 330 ms `inOut`; release on a spring 240 ms z 0.55 |
| Long-press ring | draws in 370 ms linear from 80 ms; fades in 200 ms |
| Nudge | to 1.06 (1.08 on «+») in 90 ms `out`, back on the release spring |
| Star | glow 5 and 12, crown-blue held for 18 %, 680 ms `ease-out`; the star to 1.14 in 90 ms; the pinned chip nudges at 120 ms |
| Drag | lifts to 1.06 on a spring 220 ms z 0.7 |
| Flight | 380 ms `expo` along the arc, growing to 96; the old screen to 0.3 in 260 ms from 40 % of the flight |
| Placeholder | rests 650 ms after landing, ring pulsing every 1.3 s alternate; then the app's screen fades in 240 ms and the icon dissolves to 1.15 in 240 ms |
| Contour fills before a flight | 180 ms `out`, then the icon flies |
| Pill on Home | out in 160 ms `outQ`, reset at 170 ms; back in 120 ms `out` |
| Press bloom | 0.55 to 1 at 14 % to 1.22, fading, 300 ms `ease-out` |

With reduced motion every value goes straight to its target, the contour holds at 0.85 and the
ring at full.

## Rendering and comparing

```bash
python3 shot.py                          # every board and the default timed frames
python3 shot.py open edit-day opening-t160
python3 shot.py --spec                   # -> spec.json
python3 compare.py open app.png          # -> ../_shots/split-pill/compare/open.png
```

A board is the page opened as `split-pill.html#bare-<board>`. The page runs its own code from boot
on a virtual clock that moves only when stepped, through the board's actions and waits, and holds
every animation still; the stage is alone at scale 1, 1280 x 800 at the window's top-left, with
nothing of the stand around it. Boards show the contour at its brightest and a resting
placeholder's ring at its peak. Each starts from the stage as the page boots: music on the left,
the map on the right, the divider at 434.

| Board | |
| --- | --- |
| `handle` | the panes with the handle, as booted |
| `handle-dim` | the handle dimmed after 3 s untouched |
| `open` | the pill open on the pairs, night map |
| `open-day` | the same over the day map |
| `open-browser` | the pill open with a light browser in the wide pane, music and browser on screen |
| `open-wide-left` | the divider at 846, the pill open |
| `vacant-right` | the right pane emptied: picker, repair row, the map as a contour |
| `vacant-left` | the left pane emptied, music as a contour |
| `vacant-both` | both emptied: the chip all contour |
| `edit` | edit with badges, night map |
| `edit-day` | edit over the day map |
| `launch` | a pair chosen that changes both panes, 900 ms on: both icons resting at the pane centres with their rings, the old screens dimmed, the pill melted into the handle |
| `home` | the home screen, the pill gone |
| `blur-day` | the open pill in window blur over the day map |

A timed frame is `#bare-<motion>-t<ms>`: <ms> after the motion starts, every value stepped from
0 in steps of 1/240 s with the same integrator the page runs on, timers firing on the first step
at or after their time, WAAPI animations held at their own elapsed time and CSS animations at
their time since they appeared. It is a pure function of t; a press before the start (the bloom of
a tap on a chip or a pane's cross) is not in it.

| Motion | Starts with | Default frames (ms) |
| --- | --- | --- |
| `opening` | a tap on the handle, as booted (the bloom included) | 0, 40, 80, 120, 160, 240, 320, 480 |
| `choosing` | the pair that changes both panes chosen, as on `launch` | 0, 60, 120, 200, 300, 380, 700, 1030, 1150, 1300 |
| `vacating` | the right pane emptied | 0, 130, 270, 400, 460, 520, 620, 760, 900, 1200 |

Any other moment works the same: `python3 shot.py choosing-t500`.

`shot.py` shoots a temporary copy whose Google Fonts link is replaced by Roboto 400 and 500 from
`../luminofor/fonts/`, inlined, in headless Chrome at a 1280 x 800 window and device scale 2 with
`--run-all-compositor-stages-before-draw`; every rerun is byte-identical. The page itself still
runs for looking at: `window.__stand` drives it (`open`, `close`, `evict`, `closePane`, `edit`,
`day`, `blur`, `home`, `split`, `detent`, `plus`, `pin`, `choose(i)`, `reset`, `state`), and
`#stand=day,open` runs those in order.

`compare.py` works as the split-crew one does: the board beside the app's screenshot, the
difference four times amplified, the two blended, the share of pixels that moved and the worst
16 px cells in dp. Only cells on the pill, its flights and placeholders count; the panes, the dock
and the home screen are the stand's.

## Not yet true

- No app code draws the pill. `feature/split` has no handle, no pill and no pair memory yet.
- `docs/split-screen-product-contract.md` is not amended for the pill or for pair memory; until it
  is, that contract still describes the split as it ships.
- Two things only the car can answer. Whether a window from an accessibility overlay can sit above
  the firmware's divider and leave its drag alone, which decides where the handle can live. And
  whether window blur is available there; the default is the opaque glass for that reason, and
  `blur-day` shows the other.
