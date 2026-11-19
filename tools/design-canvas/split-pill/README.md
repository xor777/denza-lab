# Пилюля сплита

The split screen's own control. A small round pill of glass, the handle, lies over the joint of
the two panes' bottom corners, on the divider; a tap, or a pull up, turns it into a glass pill that holds the pairs of apps the user
splits with, the pair on screen marked and wearing a star to keep it, and «+», which turns the
row into the apps for either pane, split by the divider. When a pane stands empty the pill opens
by itself and offers what goes back into it.
The owner approved the page on 2026-11-11, and it is normative: `split-pill.html` is the design,
and `spec.json` is its numbers.

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
- **Handle.** A disc 32 across (`handle.w` = `handle.h`, so a capsule of corner h / 2 is the
  disc), centred on the divider and kept 24 from the stage's sides. It lies over the joint of the
  two panes: it covers the 12 gap and about 10 of each pane's bottom corner, its bottom edge 3
  (`handle.rise`) above the panes' bottom edge (736), so at y 733 (`handle.bottom`, centre
  `handle.cy` 717, both derived in SPEC), clear of the band below the panes. It is the pill at
  rest, in the pill's own dense glass: the same fill and rim, and the pill's shadows scaled by
  its height over 88 (0 5.1 16 and 0 0.4 0 at rest). At its centre sits a white bead 8 across at
  0.35 (`handle.bead`, `handle.alpha`). Its touch target is 96 x 48 about the disc. After 3 s
  untouched it goes quiet without going: the bead to 0.2 (`handle.dim`) and the glass, rim and
  shadow to 0.7 (`handle.dimGlass`), still findable on a white pane and not loud on the night
  map. Everything the pill does starts and ends at the disc.
- **Glass.** The open pill is 88 tall with its bottom at y 720 (108 in edit), as wide as its
  row: 18 padding at each end (24 in edit), 22 between chips, 10 between buttons, 18 on either
  side of a separator. Fill `rgba(12, 13, 17, 0.9)` with a white sheen from 0.04 at the top to
  nothing at 46 %; a 1 dp rim, white 0.34 at the top, 0.12 at 42 %, 0.07 at the bottom; two
  shadows, 0 14 44 black 0.55 and 0 1 0 black 0.6. This dense glass is the product's. With
  window blur the fill is `rgba(14, 15, 20, 0.58)` over `blur(22px) saturate(1.3)`, an alternative
  on the page only (`blur-day`): on the car the pill is drawn in a fixed window, and window blur
  there blurs a large square round the pill. The row is clipped to the pill's
  capsule. Separators are 1 x 40, white 0.12; the one button («+», or the check in edit) 56
  round, white 0.075, a 24 glyph at stroke 1.75. There is no star button.
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
- **Pin badge.** The chip of the pair on screen carries one, and in edit every chip does: 24
  round at (69, 3) of the chip's item, on its top-right corner over the corner of the app behind,
  which keeps it 11 from the open pill's top edge (21 in edit) and off the rounded ends wherever
  the chip stands. Touch target 12 beyond the disc, cut 97 from the chip's left, halfway across the
  gap to the next chip, so it never takes that chip's taps. Pin is `#1c1e24` with a 1 dp white 0.8
  ring and a 13 star at stroke 1.7: outline while the pair is not pinned, solid once it is. Unpin
  (edit only) is red `#ff4046` with a white 12 x 2 bar. Both drop 0 1 3 black 0.5. The badge is
  part of its chip's content: it arrives, rises and fades with it.
- **The divider's mark** on the apps row: a vertical line 2 x 56, corner 1, white at the handle's
  0.35, 18 from the apps on either side. It stands on the divider's x: the halves lay out outward
  from it, and when they differ in length the pill is moved off the divider by as much, so that
  the mark stays on it.
- **Long-press ring.** A blue 1.5 line round the stacked chip, 5 out (96 x 66, corner 21),
  drawing itself while the press is held.
- **Flight and placeholder.** The icon at 96, corner 26, shadow 0 14 34 black 0.5. At rest it
  wears a ring 11 out, corner 36, blue 1.5 with a 10 glow outside and 8 inside, pulsing between
  0.35 at 0.98 and 1 at 1.03.
- **Press bloom.** A 90 round glow (pale blue 0.55 at the centre, blue 0.26 at 38 %, nothing at
  70 %), added, wherever a finger lands.

## When it does what

- **The handle opens it.** A tap on the handle, or on the dock's split icon while the panes are
  up, opens the pill on the pairs: pinned pairs first, then recent ones, three chips at most, a
  separator, and «+». The pair on screen is always in the row: if it would not be among the three,
  it takes the third place. Pair memory keeps every pinned pair and the five latest others; a pair
  becomes the latest when both panes are filled with it.
- **Or a pull does.** From the first pixel a finger moves up from the handle, the pill grows out of
  it under the finger as one object inflating: its top edge moves with the finger over the 69
  from the disc's top edge (701) to the open pill's (632), the height and the bottom following
  (32 to 88, 733 to 720), the width in step from the disc's 32 to the row's, and the row drawn at
  the pill's own scale and at its centre, so that at every height the pill is the open pill in
  miniature, nothing cut. The glass is whole from the start, since the disc is glass; the bead
  keeps its size at the centre and fades over the first 6 of growth (p ≈ 0.11), just as the
  first items come in. The row's items fade in one after another from left to right in the first 0.4 of the way
  (back down, they go in reverse). Above the open pill it stretches a quarter of the extra travel,
  never more than 10, the row staying full size on the bottom; below the handle nothing moves. At
  the release the pill opens if where it is, plus what the finger's speed would add in 120 ms, is
  past half way, and closes back into the handle if not; either way it leaves from where the
  finger left it, at the finger's speed, as far as that speed does not carry the width more than
  4 % past the row's or the height more than 10 past the open pill's, nor either under the
  handle's. A release that moved less than 10 is a tap.
- **A pull down puts it away.** On the open pill showing pairs or apps, a finger that lands anywhere on the
  glass, on an item or between them, and leaves 10 from where it landed more down than across
  takes the pill down with it, the same way backwards, and the release decides the same way. A
  chip's tap, its long press and the reorder drag in edit keep their own touches; in edit there is
  no pull down. While a pane is empty the pill stays open, so a pull down only stretches it (a
  quarter of the travel, at most 10) and it springs back; on the apps row it also takes the row
  back to the one «+» was pressed in.
- **A touch catches it.** A touch anywhere on the pill while it opens or closes stops it under the
  finger, and the finger goes on from there as in a pull. Released without moving 10, it was a
  tap: on an item that is at least half shown it is that item's tap (a pair chip's within 200 ms)
  and the pill opens to take it; on the handle the pill opens; on bare glass the pill goes on
  where it was going.
- **It closes by itself only when it was opened by hand.** Showing pairs or apps with both panes
  full, it melts back into the handle 5 s after the last touch on it (for a pull, its release), or
  at once on a touch anywhere outside it.
- **It opens by itself only while a pane stands empty.** 400 ms after a pane is emptied (its
  close cross, or the firmware evicting the app), the pill opens on the repair row, laid out by the
  same rule as the apps row, side by side with the panes: the pair that was on screen as an opened
  chip with the missing app as a contour, a separator, and up to three single apps for the empty
  pane (that pane's own recent apps, then the rest, never the pair's), on the empty pane's side of
  the chip, the most recent next to it - left pane empty: the apps, then the chip; right pane
  empty: the chip, then the apps. No «+», no star. It stays open while any pane is empty, and
  neither a touch outside nor 5 s put it away. Tapping the chip brings the missing app back (the
  contour fills, then the icon flies); tapping a single app launches it into the empty pane. When
  both panes are full again the chip closes back to stacked and the pill melts into the handle. If
  the pill was showing pairs when the pane emptied, the chip of that pair opens up in place.
- **Both panes empty** (the second emptied after the first), the pill shows the ordinary pairs row,
  so a lost pair comes back in one tap: pinned pairs, then recent ones, three chips, a separator
  and «+», with no live mark and no badge, since nothing is on screen. The incomplete pair's chip
  closes back into its place among them. A tap on a pair fills both panes by the choosing flight;
  «+» leads to the apps row as usual, and a touch outside or a pull down there brings the pairs
  row back by the same sequence, since the pill cannot close. Only with no remembered pair at all
  is it the apps row itself, with nothing to go back to.
- **Choosing a pair.** Tapping a chip puts its pair on screen; tapping the chip of the pair
  already there only nudges it. For each pane whose app changes,
  the icon flies from the chip to the pane's centre on an arc 80 above the higher end, rests
  there with its ring while the old screen dims to 0.3, and dissolves into the app's screen. The
  pill melts into the handle at the tap.
- **Edit.** A long press (450 ms) on a chip in the pairs row: the ring draws, the chip lifts, and
  the pill grows to 108 with every pinned pair in it (at least three chips, and the pair on screen
  after them if it is not one) and a badge on each: unpin on pinned pairs, pin on the others. The
  pair on screen keeps the badge it had, in place: its outline star stays, its solid star becomes
  the red unpin. A pinned chip dragged sideways (beyond 6) reorders the
  pins, 14 of give past the end slots. The check button, a touch outside, or 10 s untouched
  leaves edit.
- **The star lives on the pair.** The badge on the chip of the pair on screen pins or unpins it.
  Pinning flashes the star (crown-blue and lit), pops it to 1.14, turns it solid, nudges the chip,
  and the chip slides to its place among the pins - the last of them. Unpinning empties the star
  and the chip slides to the first place after the pins. An incomplete pair has no star.
- **«+» opens the apps.** It switches the pill in place to the apps row, in sequence rather than
  across: the pairs leave first and fast (a fade and a slight shrink, gone before the apps can be
  read), the divider's mark grows from its centre on the divider's x, and the apps arrive from the
  mark outward, the nearest first, while the pill's width springs as in any row change. The way
  back to a row without the mark is the same sequence: while a pane is empty, from a touch outside
  or a pull down on the apps row (the pull springs back open and the row changes as it lets go);
  otherwise, should the pill reopen on the pairs while it is still closing from the apps. The apps row: up to three single apps for the left pane on the left, up to three for
  the right pane on the right, and between them the divider's mark. Each pane's own recent apps
  come first, then the rest, never an app on screen in either pane; each half lays out outward
  from the mark, the most recent next to it. No words: the side is the meaning. Tapping an app
  sends it into the pane of its side by the flight for one pane (the icon to the pane's centre,
  the old screen dimming, the placeholder, the dissolve; the pill melts into the handle at the
  tap), and the other pane is not touched. Nothing is closed before the choice: with both panes
  full, a touch outside, the pull down, or 5 s untouched puts the pill away with the panes as they
  were; with a pane empty, the first two take the pill back to the row «+» was pressed in.
- **Home** fades the pill out with the panes; it comes back as the handle. The dock's split icon
  brings the panes back and opens the pill 240 ms later.
- **Divider detents.** The pill follows the divider's centre as the panes change between 434 and
  846, kept 24 from the stage's sides.
- **Touch.** A press scales the touched item to 0.93 and blooms under the finger. On the handle
  the press blooms, wakes the disc and swells it by 2; the pill opens at the release or as the finger pulls. On the
  pill a tap is a release that moved less than 10 (on a pair chip, also within 200 ms: a longer
  hold is a long press in the making, and moving cancels it); on a badge, within 14, and a tap on
  the badge is never its chip's. One finger
  at a time drives the pill.

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
| Pill opens | height spring 300 ms z 1; width spring 340 ms z 0.74; the glass is the disc's already (the 150 ms `out` glass tween shows only from a quiet disc, from 0.7) |
| Row items arrive (opening) | rise 8 and fade in, 280 ms `settle`, at 60 ms + 28 ms per item |
| Pill closes | row fades 90 ms `outQ`; width and height spring 200 ms z 1 from 70 ms down to the disc, whose glass stays (the 160 ms `inOut` glass tween from 60 ms shows only towards a quiet disc); items dropped at 420 ms |
| Reopened while closing | row back in 140 ms `out`, width on the opening spring |
| Row changes width | spring 280 ms z 0.9; kept items slide on a spring 230 ms z 1 |
| New item in an open row | from 0.6, 240 ms `pop`; a leaving one to 0.8 in 140 ms `ease-in` |
| Edit in and out | height spring 260 ms z 0.78; badges pop to 1.22 at 62 %, 180 ms `ease-out`, 30 ms per chip; leave in 120 ms `ease-in` |
| Unpinned chip leaves | shrinks into its badge, 220 ms `cubic-bezier(.5,0,.75,0)`; the badge 240 ms from 60 ms; the chip after it enters at 160 ms |
| Chip opens or closes its form | spring 260 ms z 0.82; contour in 300 ms `inOut`, out 180 ms `out`; opening by itself starts 220 ms after the pill |
| Contour breathing | 0.5 to 1 and back, 2.4 s `ease-in-out` |
| Handle | goes quiet 3 s after the last touch, the bead to 0.2 and the glass with it to 0.7, in 700 ms `inOut`; wakes in 160 ms `out`; a finger down on it swells it by 2 about its centre in 70 ms `outQ` (the press), given back over the first 4 of growth as (1 − growth / 4)², or on the release spring if it does not grow |
| Press | 0.93 in 70 ms `outQ`; long press lifts to 1.08 from 120 ms over 330 ms `inOut`; release on a spring 240 ms z 0.55 |
| Long-press ring | draws in 370 ms linear from 80 ms; fades in 200 ms |
| Nudge | to 1.06 in 90 ms `out`, back on the release spring |
| Star | on the badge: glow 5 and 12, crown-blue held for 18 %, 680 ms `ease-out`; the badge to 1.14 in 90 ms, back on the release spring; the chip nudges at 120 ms and slides on the row's slide spring |
| To the apps and back | the outgoing items to 0.92 and nothing in 90 ms `ease-out`; the mark from scale 0 about its centre, 160 ms `settle` from 40 ms; each incoming item from 0.8, 12 closer to the divider and nothing, 150 ms `settle` from 70 ms, by its distance from the divider: the nearest at once, the farthest 60 ms later (280 ms in all, the width's spring); the width on the row-change spring, the pill's offset from the divider on the same |
| Drag | lifts to 1.06 on a spring 220 ms z 0.7 |
| Flight | 380 ms `expo` along the arc, growing to 96; the old screen to 0.3 in 260 ms from 40 % of the flight |
| Placeholder | rests 650 ms after landing, ring pulsing every 1.3 s alternate; then the app's screen fades in 240 ms and the icon dissolves to 1.15 in 240 ms |
| Contour fills before a flight | 180 ms `out`, then the icon flies |
| Pill on Home | out in 160 ms `outQ`, reset at 170 ms; back in 120 ms `out` |
| Press bloom | 0.55 to 1 at 14 % to 1.22, fading, 300 ms `ease-out` |
| Pull, under the finger | p = travel / 69, 0 the disc, 1 the open pill; height 32 + 56 p and the bottom from 733 to 720 with it, so the top edge moves 1:1; width 32 + (row − 32) · p; the row drawn at min(1, height / 88, width / row's width) at the pill's centre, and on its bottom past 88; the glass whole throughout, its shadows scaled by height / 88; the bead 8 at the centre, fading over the first 6 of height; item i of n rises 8 (scaled with the row) and fades in over p 0.04 + i · s to that + 0.2, s = 0.16 / (n − 1) |
| Pull, past the ends | above p 1 the top edge goes on by 10 · tanh(0.25 x / 10) for x of travel; below p 0 nothing; on the repair row, below where it was taken, down by the same |
| Pull, release | the finger's speed from a least-squares parabola through the last 80 ms of samples (a line under four), taken at the release; opens when p + speed · 120 ms / 69 ≥ 0.5, else closes; height and width on the opening (300 ms z 1, 340 ms z 0.74) or closing (200 ms z 1, no delay) springs from where they are, at the speed they had under the finger, cut to the most the spring can take without carrying the width over 1.04 of the row's or the height over 98 (opening), or either under the handle's (closing); the glass, the row's scale and the items keep following the height until it rests |
| Catch | the spring is dropped where it is and p read back from the height; the width line bends through where it was caught, so it moves only with the finger; the glass, the row's scale and opacity and the items that differ from the pull's mapping there fade into it over the first 24 of travel, and after the release on a spring 160 ms z 1; a touch on an item presses it (0.93) until it moves 10 |

With reduced motion every value goes straight to its target, the contour holds at 0.85 and the
ring at full. A pull still follows the finger; its release goes straight to open or closed.

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
| `open` | the pill open on the pairs, night map; the pair on screen pinned (solid star) |
| `open-day` | the same over the day map |
| `open-browser` | the pill open with a light browser in the wide pane, music and browser on screen, that pair not pinned (outline star) |
| `open-wide-left` | the divider at 846, the pill open |
| `vacant-right` | the right pane emptied: picker, repair row, the map as a contour |
| `vacant-left` | the left pane emptied, music as a contour |
| `vacant-both` | both emptied: the pairs row, no live mark, no badge |
| `edit` | edit with badges, night map |
| `edit-day` | edit over the day map |
| `launch` | a pair chosen that changes both panes, 900 ms on: both icons resting at the pane centres with their rings, the old screens dimmed, the pill melted into the handle |
| `home` | the home screen, the pill gone |
| `blur-day` | the open pill in window blur over the day map |
| `apps` | «+» tapped: the apps row, its mark on the divider at 434 |
| `apps-wide-left` | the same with the divider at 846 |

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
| `adding` | «+» on the open pill, as on `apps`, and 700 ms later the messenger (next to the mark) into the left pane: the flight for one pane, the map untouched | 0, 40, 60, 80, 100, 140, 200, 280, 700, 760, 860, 1000, 1400, 1800, 2100 |
| `pulling` | a finger down on the handle, as booted: up 60 over 200 ms, held to 300 (250 shows it), back to 30 by 420, held to 480 (450 shows it), a flick up to 70 from 480 to 550 and the release there (touch events at 60 Hz) | 0, 60, 120, 200, 250, 360, 420, 450, 520, 550, 580, 620, 700, 900 |

Any other moment works the same: `python3 shot.py choosing-t500`.

`shot.py` shoots a temporary copy whose Google Fonts link is replaced by Roboto 400 and 500 from
`../luminofor/fonts/`, inlined, in headless Chrome at a 1280 x 800 window and device scale 2 with
`--run-all-compositor-stages-before-draw`; every rerun is byte-identical. The page itself still
runs for looking at: `window.__stand` drives it (`open`, `close`, `evict`, `closePane`, `edit`,
`day`, `blur`, `home`, `split`, `detent`, `plus`, `pin` (the badge on the pair on screen),
`choose(i)`, `app(id, side)` (a single app of the apps or repair row), `reset`, `state`; `pill`
reads the pill as drawn, and on a bare page `to(ms)` and `now` step and read the virtual clock, so
a script can send pointer events and read the result step by step), and `#stand=day,open` runs
those in order.

`compare.py` works as the split-crew one does: the board beside the app's screenshot, the
difference four times amplified, the two blended, the share of pixels that moved and the worst
16 px cells in dp. Only cells on the pill, its flights and placeholders count; the panes, the dock
and the home screen are the stand's.

## Not yet true

- The app draws the pill as a first slice (`feature/split/pill`, 2026-11-19): the handle, tap and
  pull, the pairs row with the pin badge, the apps row, the repair row; a tap is an operation of
  the split actor and the badge sends the pin facts. Not in code yet: the edit mode (long press,
  badges on every chip, reorder), the flights and the launch placeholders, the grow on tap, the
  badge's pop to its peak, the contour's tail and its glyph fading in. None of it has run on the
  car.
- `docs/split-screen-product-contract.md` carries pair memory (1.14, in code as `SplitMemory` in
  `split_state_v3`, not run on the car) and the pill (1.15, amended on 2026-11-19 for this page:
  the star on the pair, «+» as the apps row, the repair row, the disc; known limits in 1.17).
- The car's probe answered how the pill can live there: a touch that goes down on the handle is
  the overlay's to the end and the pull works on the real divider; the pill must be drawn inside a
  fixed window (resizing the window drops about a tenth of the frames); window blur in a fixed
  window blurs a large square round the pill. So the dense glass is the product's and `blur-day`
  is a page-only alternative.
- The apps row's halves are never of different lengths on this stand (six apps, at most two on
  screen), so the pill standing off the divider to keep the mark on it has been checked only by
  arithmetic.
- The pull was tried on the car through the probe, with the old 44 x 5 bar for a handle; the
  glass disc, its swell under the press and the bead have been judged on the page's frames only.
