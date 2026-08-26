# Design canvas

The twenty artboards behind the Denza Apps redesign, and the tooling that keeps
them honest. Published as a Claude Design canvas; these are the sources it is
seeded from.

## Why this is in the repo

An adversarial review of the first cut of these boards found about sixty defects
and almost none of them were judgement calls. A tick lying along an arc instead
of across it, a label naming 60 kW at an angle that meant 150, twenty-seven type
sizes where six were declared, four sibling boards on four different grids, a
readout with a rule through its own numeral: every one of those is a measurement,
and measurements should not be made by hand twice.

So the boards are not drawn any more. The instrument boards are *computed* from
the same constants the app draws with, and every board is *measured* by a script
that reports what collides.

## The files

| | |
| --- | --- |
| `*.dc.html` | the artboards, one sandboxed document each |
| `canvas.json` | positions, pages and the launch view |
| `gen_cluster.py` | emits the three cluster boards as they stand on the car today |
| `gen_next.py` | emits the proposed cluster: one horizon, two histories, the gauge |
| `gen_kit.py` | emits the two boards that describe the system, from the system |
| `panel_frame.py` | the shared frame for the four instrument pages; rebuilds Energy |
| `normalize.py` | maps type, radii, borders and icon weights onto the scales |
| `audit.py` | opens every board in headless Chrome and reports what collides |

## Running the audit

```bash
python3 audit.py
```

It inlines each board into its own iframe, lets the page measure itself, and
reports four things: text past its artboard, text past the column it was placed
in, text meeting text it has no relationship with, a flat rule crossing a
numeral - plus the type sizes, radii and stroke weights each board actually uses.
Nothing may sit outside the ramps below.

It knows about occlusion: text under a modal scrim is covered, not colliding, and
adjacent cells of a segmented control are meant to touch.

## The two ramps

The cluster and the head unit are different screens at different distances, so
they get different ladders. What they share is the rule: a size not on the ramp
is not available, and no two rungs sit closer than about 1.2x - a difference you
can measure and cannot see is a difference that will be drifted into.

| | rungs | step |
| --- | --- | --- |
| Cluster (virtual units, 1.70 on the panel) | 104 · 52 · 34 · 24 · 18 · 13 · 11 | 8 wide, 6 narrow |
| Head unit (pixels, 1:1) | 82 · 62 · 46 · 34 · 24 · 19 · 15 | 6 |

Radii are `22 · 12 · 6 · 2` plus a track's own half-height. Borders are one pixel
- selection is carried by fill and ink, never by a thicker edge. Icons carry one
optical weight of `2.0`, so a stroke is `2.0 × 24 ÷ rendered size`.

The cluster ramp is `InstrumentDensity.RAMP` in the app and the boards restate
it; `gen_cluster.py` is the one place to check that they still agree. `104` is
the proposal's headline numeral and the only rung added for it - exactly twice
the old top, so the ladder is extended rather than duplicated. Its first draft
ran at `58` and `19` beside the ramp's `52` and `18`, which is the drift the
ramp exists to prevent: a difference you can measure and cannot see.

## Regenerating and republishing

```bash
python3 gen_cluster.py && python3 gen_next.py && python3 gen_kit.py && python3 audit.py
```

Then seed a fresh payload with the `design` skill's `seed-canvas.mjs` and publish
to the existing artifact URL. `normalize.py` and `panel_frame.py` are one-shot
migrations kept for the record; running them again is harmless but unnecessary.
