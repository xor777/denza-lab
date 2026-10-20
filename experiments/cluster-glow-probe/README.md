# Cluster glow probe

Disposable. It answers one question: how bright the Contour's glow round zero has to be to show on
the driver's display. On 2026-10-20 the owner reported that it never shows, full throttle included,
while the app draws it exactly as the board does
([instrument-display-findings.md](../../docs/instrument-display-findings.md), "The glow is below
what the cluster shows").

The probe puts a Luminofor scene on `shared_fission_bg_XDJAScreenProjection_0`, over Denza Apps'
instruments, and draws the glow over it with the board's own geometry from `spec.json`. On the head
unit the owner sets:

- **Сила**: the design's alpha times 0, 1 (now), 1.5, 2, 2.5, 3, 4, 5, 6 or 7 (7 is the cap);
- **Форма**: the design's linear fall-off, or a soft one, 1 - smoothstep, with no peak at the centre
  and no crease at the rim;
- **Размер**: both radii times 1, 1.3 or 1.6;
- **Сцена**: full throttle (196 kW, past the 120 kW cap), city (26 kW), energy coming back
  (38 kW, the blue glow).

The status line says what is on the driver's display and the glow's centre in levels of 255. The
picture is up only while the probe's screen is in front. The scenes are boards, not readings, so
use it parked.

## Outcome (2026-10-20)

On the car the owner picked ×6 and size ×1.6 in the design's shape. `spec.json` took it the same day
(`max 0.78`, `radius 544`, `reachBelow 87.8`). The strengths are relative to the spec the scenes were
rendered from, so scenes rendered after that change make ×1 the new glow.

## Build

```bash
python3 tools/cluster_glow_scenes.py
./gradlew -Pexperiments :cluster-glow-probe:assembleDebug
```

The first command renders `cluster-launch`, `cluster-city` and `cluster-regen` without their glow
into `src/main/assets/`, which git ignores. It needs Chrome, as `shot.py` does.

## Checked on the emulator (2026-10-20)

- With `--ez preview true --ef strength 1 --ei scene 0` the picture matched the
  `cluster-launch` board to within one level over the whole glow.
- With `--es display 'Overlay #1'` on a 2560 x 720 overlay display, the controls changed the
  picture and leaving the screen dismissed it.
