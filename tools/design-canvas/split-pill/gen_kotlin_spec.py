#!/usr/bin/env python3
"""Writes the app's copy of this board's numbers, SplitPillSpec.kt, from spec.json.

    python3 tools/design-canvas/split-pill/gen_kotlin_spec.py

run from anywhere: the repository is found from this file's place. The Kotlin file is checked in
and `SplitPillSpecContractTest` holds it to spec.json, so a spec.json changed without running this
fails the app's unit tests. Only the keys listed below are written; a key the app does not read
belongs in that test's list of unread keys, with its reason.
"""
import json
import os
import re

repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
spec = json.load(open(os.path.join(repo, "tools/design-canvas/split-pill/spec.json")))
TARGET = "apps/denza-apps/src/main/java/dev/denza/apps/feature/split/pill/SplitPillSpec.kt"


def at(path):
    v = spec
    for k in path.split("."):
        v = v[int(k)] if isinstance(v, list) else v[k]
    return v


def num(v):
    s = repr(float(v))
    if s.endswith(".0"):
        s = s[:-2] + ".0"
    return s


def rgba(text):
    text = text.strip()
    if text.startswith("#"):
        h = text[1:]
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        return 0xFF000000 | int(h, 16)
    m = re.match(r"rgba?\(([^)]*)\)", text)
    p = [float(x) for x in m.group(1).split(",")]
    a = p[3] if len(p) > 3 else 1.0
    return (round(a * 255) << 24) | (int(p[0]) << 16) | (int(p[1]) << 8) | int(p[2])


# (Kotlin object, constant, spec path, kind)
NUMS = [
    ("Stage", "W", "stage.w"), ("Stage", "H", "stage.h"),
    ("Stage", "DETENT_NARROW_LEFT", "stage.detents.0"), ("Stage", "DETENT_WIDE_LEFT", "stage.detents.1"),
    ("Handle", "W", "handle.w"), ("Handle", "H", "handle.h"), ("Handle", "BOTTOM", "handle.bottom"),
    ("Handle", "BEAD", "handle.bead"), ("Handle", "ALPHA", "handle.alpha"), ("Handle", "DIM", "handle.dim"),
    ("Handle", "DIM_GLASS", "handle.dimGlass"), ("Handle", "FADE_BY", "handle.fadeBy"),
    ("Handle", "SWELL", "handle.swell"), ("Handle", "HIT_W", "handle.hit.w"), ("Handle", "HIT_H", "handle.hit.h"),
    ("Pill", "OPEN_H", "pill.open.h"), ("Pill", "OPEN_BOTTOM", "pill.open.bottom"), ("Pill", "MARGIN", "pill.margin"),
    ("Pill", "MIN_W", "pill.min.w"), ("Pill", "MIN_H", "pill.min.h"), ("Pill", "SHEEN", "pill.sheen"),
    ("Pill", "RIM_W", "pill.rim.w"), ("Pill", "RIM_STOP_TOP", "pill.rim.stops.0"),
    ("Pill", "RIM_STOP_MID", "pill.rim.stops.1"), ("Pill", "RIM_STOP_BOT", "pill.rim.stops.2"),
    ("Pill", "LIFT_X", "pill.lift.0.0"), ("Pill", "LIFT_Y", "pill.lift.0.1"), ("Pill", "LIFT_BLUR", "pill.lift.0.2"),
    ("Pill", "LIFT_TIGHT_X", "pill.lift.1.0"), ("Pill", "LIFT_TIGHT_Y", "pill.lift.1.1"),
    ("Pill", "LIFT_TIGHT_BLUR", "pill.lift.1.2"),
    ("Row", "PAD", "row.pad"), ("Row", "GAP_CHIP", "row.gap.chip"), ("Row", "GAP_BTN", "row.gap.btn"),
    ("Row", "GAP_SEP", "row.gap.sep"), ("Row", "GAP_OTHER", "row.gap.other"), ("Row", "H_CHIP", "row.h.chip"),
    ("Row", "H_SINGLE", "row.h.single"), ("Row", "H_SEP", "row.h.sep"), ("Row", "H_BTN", "row.h.btn"),
    ("Row", "MAX_CHIPS", "row.maxChips"), ("Row", "MAX_SINGLES", "row.maxSingles"), ("Row", "RECENTS", "row.recents"),
    ("Icon", "SIZE", "icon.size"), ("Icon", "RADIUS", "icon.radius"),
    ("Chip", "STACKED", "chip.stacked"), ("Chip", "OPEN", "chip.open"), ("Chip", "H", "chip.h"),
    ("Chip", "INSET", "chip.inset"), ("Chip", "GAP", "chip.gap"), ("Chip", "CUT", "chip.cut"),
    ("Chip", "OVERSHOOT", "chip.overshoot"), ("Chip", "HIT", "chip.hit"), ("Chip", "BACK_X", "chip.back.x"),
    ("Chip", "BACK_Y", "chip.back.y"), ("Chip", "BACK_SCALE", "chip.back.scale"),
    ("Single", "HIT", "single.hit"),
    ("Btn", "SIZE", "btn.size"), ("Btn", "GLYPH", "btn.glyph"), ("Btn", "STROKE", "btn.stroke"),
    ("Sep", "W", "sep.w"),
    ("Mark", "TOP", "mark.top"), ("Mark", "W", "mark.w"), ("Mark", "H", "mark.h"), ("Mark", "R", "mark.r"),
    ("Mark", "GLOW_NEAR", "mark.glow.0"), ("Mark", "GLOW_FAR", "mark.glow.1"),
    ("Ghost", "RIM_INSET", "ghost.rimInset"), ("Ghost", "STROKE", "ghost.stroke"), ("Ghost", "DASH_ON", "ghost.dash.0"),
    ("Ghost", "DASH_OFF", "ghost.dash.1"), ("Ghost", "GLOW", "ghost.glow"), ("Ghost", "BREATHE_LO", "ghost.breathe.lo"),
    ("Ghost", "BREATHE_HI", "ghost.breathe.hi"), ("Ghost", "BREATHE_STILL", "ghost.breathe.still"),
    ("Ghost", "SOLID_FADE", "ghost.solidFade"), ("Ghost", "RIM_FROM", "ghost.rimFrom"),
    ("Badge", "SIZE", "badge.size"), ("Badge", "X", "badge.x"), ("Badge", "Y", "badge.y"), ("Badge", "HIT", "badge.hit"),
    ("Badge", "STAR", "badge.star"), ("Badge", "STAR_STROKE", "badge.starStroke"), ("Badge", "RING", "badge.ring"),
    ("Badge", "DROP_X", "badge.drop.0"), ("Badge", "DROP_Y", "badge.drop.1"), ("Badge", "DROP_BLUR", "badge.drop.2"),
    ("Apps", "MARK_W", "apps.mark.w"), ("Apps", "MARK_H", "apps.mark.h"), ("Apps", "MARK_R", "apps.mark.r"),
    ("Apps", "MARK_ALPHA", "apps.mark.alpha"), ("Apps", "GAP", "apps.gap"),
    ("Pull", "TRAVEL", "pull.travel"), ("Pull", "ITEMS_FROM", "pull.items.from"),
    ("Pull", "ITEMS_TO", "pull.items.to"), ("Pull", "ITEMS_EACH", "pull.items.each"),
    ("Pull", "BAND_FACTOR", "pull.band.factor"), ("Pull", "BAND_CAP", "pull.band.cap"), ("Pull", "WINDOW", "pull.window"),
    ("Pull", "PROJECT", "pull.project"), ("Pull", "HALF", "pull.half"), ("Pull", "BOUND_WIDTH", "pull.bound.width"),
    ("Pull", "BOUND_HEIGHT", "pull.bound.height"), ("Pull", "BOUND_SHUT", "pull.bound.shut"),
    ("Pull", "DOWN_START", "pull.down.start"), ("Pull", "DOWN_DOMINANCE", "pull.down.dominance"),
    ("Pull", "CATCH_SPAN", "pull.catch.span"), ("Pull", "TAP_VISIBLE", "pull.tap.visible"),
    ("Bloom", "SIZE", "bloom.size"), ("Bloom", "STOP_CORE", "bloom.stops.0"), ("Bloom", "STOP_MID", "bloom.stops.1"),
    ("Bloom", "STOP_CLEAR", "bloom.stops.2"), ("Bloom", "SCALE_FROM", "bloom.scale.0"),
    ("Bloom", "SCALE_PEAK", "bloom.scale.1"), ("Bloom", "SCALE_TO", "bloom.scale.2"), ("Bloom", "PEAK_AT", "bloom.peakAt"),
    ("Press", "SCALE", "press.scale"), ("Press", "NUDGE", "press.nudge"), ("Press", "STAR", "press.star"), ("Press", "BADGE", "press.badge"),
    ("Press", "SLOP", "press.slop"), ("Press", "MOVE", "press.move"),
    ("Enter", "DY", "enter.dy"), ("Enter", "POP", "enter.pop"), ("Enter", "EXIT", "enter.exit"),
    ("Star", "HOLD", "star.hold"), ("Star", "GLOW_NEAR", "star.glow.0"), ("Star", "GLOW_FAR", "star.glow.1"),
    ("Timer", "AUTO", "timer.auto"), ("Timer", "DIM", "timer.dim"), ("Timer", "SELF_OPEN", "timer.selfOpen"),
    ("Timer", "GHOST_OPEN", "timer.ghostOpen"), ("Timer", "TAP", "timer.tap"), ("Timer", "CLEAR", "timer.clear"),
    ("Timer", "CHIP_NUDGE", "timer.chipNudge"), ("Timer", "HOME_RESET", "timer.homeReset"),
    ("Timer", "SPLIT_OPEN", "timer.splitOpen"),
]
COLORS = ["glass", "glass-sheen", "rim-top", "rim-mid", "rim-bot", "lift", "lift-tight", "handle", "sep", "btn",
          "white", "blue", "blue-glow", "blue-haze", "blue-clear", "crown", "badge-dark", "badge-ring", "badge-drop",
          "bloom-core", "bloom-mid"]
SPRINGS = [("RELEASE", "release"), ("OPEN", "open"), ("OPEN_H", "openH"), ("CLOSE", "close"), ("RESIZE", "resize"),
           ("SLIDE", "slide"), ("FORM", "form")]
TWEENS = [("GLASS_IN", "glassIn"), ("ROW_IN", "rowIn"), ("ROW_OUT", "rowOut"), ("GLASS_OUT", "glassOut"),
          ("DIM", "dim"), ("WAKE", "wake"), ("HIDE", "hide"), ("SHOW", "show"), ("ENTER", "enter"), ("POP", "pop"),
          ("EXIT", "exit"), ("PRESS", "press"), ("NUDGE", "nudge"), ("GHOST_IN", "ghostIn"), ("GHOST_OUT", "ghostOut"),
          ("MARK_FADE", "markFade"), ("BREATHE", "breathe"), ("BLOOM", "bloom"), ("STAR_FLASH", "starFlash"),
          ("BADGE_PRESS", "badgePress"), ("BADGE_RELEASE", "badgeRelease"), ("BADGE_IN", "badgeIn"),
          ("BADGE_OUT", "badgeOut"), ("SWAP_OUT", "swapOut"), ("SWAP_MARK", "swapMark"), ("SWAP_IN", "swapIn")]
EASES = [("STD", "std"), ("SETTLE", "settle"), ("POP", "pop"), ("EXPO", "expo"), ("SINK", "sink"), ("FALL", "fall")]

objects = {}
for obj, name, path in NUMS:
    objects.setdefault(obj, []).append(f"        const val {name} = {num(at(path))}")
keys = [f'        "{path}" to {obj}.{name},' for obj, name, path in NUMS]

colors = []
for c in COLORS:
    const = c.upper().replace("-", "_")
    colors.append(f"        val {const}: Int = 0x{rgba(at('color.' + c)):08X}.toInt()")
    keys.append(f'        "color.{c}" to Color.{const},')

# The shadows name their colour (`pill.lift[i][3]`): the code draws the colour the name resolves to.
for const, path in [("LIFT_COLOR", "pill.lift.0.3"), ("LIFT_TIGHT_COLOR", "pill.lift.1.3")]:
    name = at(path)
    objects["Pill"].append(f"        /** `{path}`: the colour `{name}` this shadow is drawn in. */")
    objects["Pill"].append(f"        val {const}: Int = Color.{name.upper().replace('-', '_')}")
    keys.append(f'        "{path}" to PillColorRef("{name}", Pill.{const}),')

springs = []
for const, k in SPRINGS:
    ms, z = at("spring." + k)
    springs.append(f"        val {const} = PillSpringSpec({num(ms)}, {num(z)})")
    keys.append(f'        "spring.{k}" to Spring.{const},')
ms, z = at("pull.catch.settle")
springs.append(f"        val CATCH = PillSpringSpec({num(ms)}, {num(z)})")
keys.append('        "pull.catch.settle" to Spring.CATCH,')

tweens = []
for const, k in TWEENS:
    m = at("motion." + k)
    tweens.append(
        f"        val {const} = PillTween({num(m['ms'])}, \"{m['ease']}\", "
        f"{num(m.get('delay', 0))}, {num(m.get('stagger', 0))})")
    keys.append(f'        "motion.{k}" to Motion.{const},')
tweens.append(f"        const val SHRINK_DELAY = {num(at('motion.shrink.delay'))}")
keys.append('        "motion.shrink.delay" to Motion.SHRINK_DELAY,')
for const, path in [("SWAP_OUT_SCALE", "motion.swapOut.scale"), ("SWAP_IN_SPREAD", "motion.swapIn.spread"),
                    ("SWAP_IN_FROM", "motion.swapIn.from"), ("SWAP_IN_SCALE", "motion.swapIn.scale")]:
    tweens.append(f"        const val {const} = {num(at(path))}")
    keys.append(f'        "{path}" to Motion.{const},')

eases = []
for const, k in EASES:
    eases.append(f"        const val {const} = \"{at('ease.' + k)}\"")
    keys.append(f'        "ease.{k}" to Ease.{const},')

out = []
out.append("""package dev.denza.apps.feature.split.pill

/**
 * Every number the pill is drawn and moved with, and nothing else.
 *
 * The page `tools/design-canvas/split-pill/` is normative and `spec.json` is its numbers; this
 * object is the app's checked-in copy of the keys it reads, written by
 * `python3 tools/design-canvas/split-pill/gen_kotlin_spec.py` and held to that file by
 * `SplitPillSpecContractTest` through [KEYS] - a value changed on one side only fails the build.
 * Do not edit it by hand: change spec.json and run the generator. No pill number is written
 * anywhere else in the app. Lengths are dp on the 1280 x 800 screen, times milliseconds.
 */
internal object SplitPillSpec {""")
for obj in ["Stage", "Handle", "Pill", "Row", "Icon", "Chip", "Single", "Btn", "Sep", "Mark", "Ghost", "Badge", "Apps",
            "Pull", "Bloom", "Press", "Enter", "Star", "Timer"]:
    out.append(f"    object {obj} {{")
    out.extend(objects[obj])
    out.append("    }")
    out.append("")
out.append("    /** `color`, as ARGB: a CSS alpha a is round(a * 255). */")
out.append("    object Color {")
out.extend(colors)
out.append("    }")
out.append("")
out.append("    /** `spring`, and the catch's settle (`pull.catch.settle`). */")
out.append("    object Spring {")
out.extend(springs)
out.append("    }")
out.append("")
out.append("    /** `motion`: the tweens and the delays the pill uses. */")
out.append("    object Motion {")
out.extend(tweens)
out.append("    }")
out.append("")
out.append("    /** `ease`: the named cubic-beziers ([PillEase.named] reads them). */")
out.append("    object Ease {")
out.extend(eases)
out.append("    }")
out.append("")
out.append(f"    /** `rate`: integration steps a second. */")
out.append(f"    const val RATE = {num(at('rate'))}")
keys.append('        "rate" to RATE,')
out.append("")
out.append("    /** Every key read, by its path in `spec.json`. */")
out.append("    val KEYS: List<Pair<String, Any>> by lazy {")
out.append("        listOf(")
out.extend("    " + k for k in keys)
out.append("        )")
out.append("    }")
out.append("}")
out.append("")
out.append("""/** A colour named by another key: the [name] in `color` and the ARGB it resolves to. */
internal data class PillColorRef(val name: String, val argb: Int)

/** A spring given as the time it settles in and its damping ratio (README, "Motion"). */
internal data class PillSpringSpec(val ms: Double, val z: Double)

/** A tween: duration, the ease's name, delay and the stagger between items where one applies. */
internal data class PillTween(
    val ms: Double,
    val ease: String,
    val delay: Double = 0.0,
    val stagger: Double = 0.0,
)
""")
open(os.path.join(repo, TARGET), "w").write("\n".join(out))
print("ok", len(keys), "keys")
