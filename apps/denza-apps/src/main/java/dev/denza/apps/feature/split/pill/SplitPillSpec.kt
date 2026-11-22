package dev.denza.apps.feature.split.pill

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
internal object SplitPillSpec {
    object Stage {
        const val W = 1280.0
        const val H = 800.0
        const val DETENT_NARROW_LEFT = 434.0
        const val DETENT_WIDE_LEFT = 846.0
    }

    object Handle {
        const val W = 32.0
        const val H = 32.0
        const val BOTTOM = 733.0
        const val BEAD = 8.0
        const val ALPHA = 0.35
        const val DIM = 0.2
        const val DIM_GLASS = 0.7
        const val FADE_BY = 6.0
        const val SWELL = 2.0
        const val HIT_W = 96.0
        const val HIT_H = 48.0
    }

    object Pill {
        const val OPEN_H = 88.0
        const val OPEN_BOTTOM = 720.0
        const val MARGIN = 24.0
        const val MIN_W = 4.0
        const val MIN_H = 3.0
        const val SHEEN = 46.0
        const val RIM_W = 1.0
        const val RIM_STOP_TOP = 0.0
        const val RIM_STOP_MID = 42.0
        const val RIM_STOP_BOT = 100.0
        const val LIFT_X = 0.0
        const val LIFT_Y = 14.0
        const val LIFT_BLUR = 44.0
        const val LIFT_TIGHT_X = 0.0
        const val LIFT_TIGHT_Y = 1.0
        const val LIFT_TIGHT_BLUR = 0.0
        /** `pill.lift.0.3`: the colour `lift` this shadow is drawn in. */
        val LIFT_COLOR: Int = Color.LIFT
        /** `pill.lift.1.3`: the colour `lift-tight` this shadow is drawn in. */
        val LIFT_TIGHT_COLOR: Int = Color.LIFT_TIGHT
    }

    object Row {
        const val PAD = 18.0
        const val GAP_CHIP = 22.0
        const val GAP_BTN = 10.0
        const val GAP_SEP = 18.0
        const val GAP_OTHER = 18.0
        const val H_CHIP = 72.0
        const val H_SINGLE = 72.0
        const val H_SEP = 40.0
        const val H_BTN = 56.0
        const val MAX_CHIPS = 5.0
        const val MAX_SINGLES = 3.0
        const val RECENTS = 5.0
    }

    object Icon {
        const val SIZE = 52.0
        const val RADIUS = 14.0
    }

    object Chip {
        const val STACKED = 86.0
        const val OPEN = 116.0
        const val H = 56.0
        const val INSET = 2.0
        const val GAP = 8.0
        const val CUT = 2.0
        const val OVERSHOOT = 1.08
        const val HIT = 5.0
        const val BACK_X = 36.6
        const val BACK_Y = 4.6
        const val BACK_SCALE = 0.9
    }

    object Single {
        const val HIT = 9.0
    }

    object Btn {
        const val SIZE = 56.0
        const val GLYPH = 24.0
        const val STROKE = 1.75
    }

    object Sep {
        const val W = 1.0
    }

    object Mark {
        const val TOP = 69.0
        const val W = 14.0
        const val H = 3.0
        const val R = 2.0
        const val GLOW_NEAR = 3.0
        const val GLOW_FAR = 9.0
    }

    object Ghost {
        const val RIM_INSET = 1.0
        const val STROKE = 2.0
        const val DASH_ON = 6.0
        const val DASH_OFF = 4.4
        const val GLOW = 2.6
        const val BREATHE_LO = 0.5
        const val BREATHE_HI = 1.0
        const val BREATHE_STILL = 0.85
        const val SOLID_FADE = 1.6
        const val RIM_FROM = 0.15
    }

    object Badge {
        const val SIZE = 24.0
        const val X = 69.0
        const val Y = 3.0
        const val HIT = 12.0
        const val STAR = 13.0
        const val STAR_STROKE = 1.7
        const val RING = 1.0
        const val DROP_X = 0.0
        const val DROP_Y = 1.0
        const val DROP_BLUR = 3.0
    }

    object Apps {
        const val MARK_W = 2.0
        const val MARK_H = 56.0
        const val MARK_R = 1.0
        const val MARK_ALPHA = 0.35
        const val GAP = 18.0
    }

    object Pull {
        const val TRAVEL = 69.0
        const val ITEMS_FROM = 0.04
        const val ITEMS_TO = 0.4
        const val ITEMS_EACH = 0.2
        const val BAND_FACTOR = 0.25
        const val BAND_CAP = 10.0
        const val WINDOW = 80.0
        const val PROJECT = 120.0
        const val HALF = 0.5
        const val BOUND_WIDTH = 0.04
        const val BOUND_HEIGHT = 10.0
        const val BOUND_SHUT = 0.0
        const val DOWN_START = 10.0
        const val DOWN_DOMINANCE = 1.0
        const val CATCH_SPAN = 24.0
        const val TAP_VISIBLE = 0.5
    }

    object Bloom {
        const val SIZE = 90.0
        const val STOP_CORE = 0.0
        const val STOP_MID = 38.0
        const val STOP_CLEAR = 70.0
        const val SCALE_FROM = 0.55
        const val SCALE_PEAK = 1.0
        const val SCALE_TO = 1.22
        const val PEAK_AT = 0.14
    }

    object Press {
        const val SCALE = 0.93
        const val NUDGE = 1.06
        const val STAR = 1.14
        const val BADGE = 0.86
        const val SLOP = 14.0
        const val MOVE = 10.0
    }

    object Enter {
        const val DY = 8.0
        const val POP = 0.6
        const val EXIT = 0.8
    }

    object Star {
        const val HOLD = 0.18
        const val GLOW_NEAR = 5.0
        const val GLOW_FAR = 12.0
    }

    object Timer {
        const val AUTO = 5000.0
        const val DIM = 3000.0
        const val SELF_OPEN = 400.0
        const val GHOST_OPEN = 220.0
        const val TAP = 200.0
        const val CLEAR = 420.0
        const val CHIP_NUDGE = 120.0
        const val HOME_RESET = 170.0
        const val SPLIT_OPEN = 240.0
    }

    /** `color`, as ARGB: a CSS alpha a is round(a * 255). */
    object Color {
        val GLASS: Int = 0xE60C0D11.toInt()
        val GLASS_SHEEN: Int = 0x0AFFFFFF.toInt()
        val RIM_TOP: Int = 0x57FFFFFF.toInt()
        val RIM_MID: Int = 0x1FFFFFFF.toInt()
        val RIM_BOT: Int = 0x12FFFFFF.toInt()
        val LIFT: Int = 0x8C000000.toInt()
        val LIFT_TIGHT: Int = 0x99000000.toInt()
        val HANDLE: Int = 0xFFFFFFFF.toInt()
        val SEP: Int = 0x1FFFFFFF.toInt()
        val BTN: Int = 0x13FFFFFF.toInt()
        val WHITE: Int = 0xFFFFFFFF.toInt()
        val BLUE: Int = 0xFF439FFC.toInt()
        val BLUE_GLOW: Int = 0xE6439FFC.toInt()
        val BLUE_HAZE: Int = 0x73439FFC.toInt()
        val BLUE_CLEAR: Int = 0x00439FFC.toInt()
        val CROWN: Int = 0xFFC8E4FF.toInt()
        val BADGE_DARK: Int = 0xFF1C1E24.toInt()
        val BADGE_RING: Int = 0xCCFFFFFF.toInt()
        val BADGE_DROP: Int = 0x80000000.toInt()
        val BLOOM_CORE: Int = 0x8CC8E4FF.toInt()
        val BLOOM_MID: Int = 0x42439FFC.toInt()
    }

    /** `spring`, and the catch's settle (`pull.catch.settle`). */
    object Spring {
        val RELEASE = PillSpringSpec(240.0, 0.55)
        val OPEN = PillSpringSpec(340.0, 0.74)
        val OPEN_H = PillSpringSpec(300.0, 1.0)
        val CLOSE = PillSpringSpec(200.0, 1.0)
        val RESIZE = PillSpringSpec(280.0, 0.9)
        val SLIDE = PillSpringSpec(230.0, 1.0)
        val FORM = PillSpringSpec(260.0, 0.82)
        val CATCH = PillSpringSpec(160.0, 1.0)
    }

    /** `motion`: the tweens and the delays the pill uses. */
    object Motion {
        val GLASS_IN = PillTween(150.0, "out", 0.0, 0.0)
        val ROW_IN = PillTween(140.0, "out", 0.0, 0.0)
        val ROW_OUT = PillTween(90.0, "outQ", 0.0, 0.0)
        val GLASS_OUT = PillTween(160.0, "inOut", 60.0, 0.0)
        val DIM = PillTween(700.0, "inOut", 0.0, 0.0)
        val WAKE = PillTween(160.0, "out", 0.0, 0.0)
        val HIDE = PillTween(160.0, "outQ", 0.0, 0.0)
        val SHOW = PillTween(120.0, "out", 0.0, 0.0)
        val ENTER = PillTween(280.0, "settle", 60.0, 28.0)
        val POP = PillTween(240.0, "pop", 0.0, 0.0)
        val EXIT = PillTween(140.0, "ease-in", 0.0, 0.0)
        val PRESS = PillTween(70.0, "outQ", 0.0, 0.0)
        val NUDGE = PillTween(90.0, "out", 0.0, 0.0)
        val GHOST_IN = PillTween(300.0, "inOut", 0.0, 0.0)
        val GHOST_OUT = PillTween(180.0, "out", 0.0, 0.0)
        val MARK_FADE = PillTween(220.0, "ease", 0.0, 0.0)
        val BREATHE = PillTween(2400.0, "ease-in-out", 0.0, 0.0)
        val BLOOM = PillTween(300.0, "ease-out", 0.0, 0.0)
        val STAR_FLASH = PillTween(680.0, "ease-out", 0.0, 0.0)
        val BADGE_PRESS = PillTween(70.0, "ease-out", 0.0, 0.0)
        val BADGE_RELEASE = PillTween(160.0, "pop", 0.0, 0.0)
        val BADGE_IN = PillTween(180.0, "ease-out", 0.0, 30.0)
        val BADGE_OUT = PillTween(120.0, "ease-in", 0.0, 0.0)
        val SWAP_OUT = PillTween(90.0, "ease-out", 0.0, 0.0)
        val SWAP_MARK = PillTween(160.0, "settle", 40.0, 0.0)
        val SWAP_IN = PillTween(150.0, "settle", 70.0, 0.0)
        const val SHRINK_DELAY = 70.0
        const val SWAP_OUT_SCALE = 0.92
        const val SWAP_IN_SPREAD = 60.0
        const val SWAP_IN_FROM = 12.0
        const val SWAP_IN_SCALE = 0.8
    }

    /** `ease`: the named cubic-beziers ([PillEase.named] reads them). */
    object Ease {
        const val STD = "cubic-bezier(.4,0,.2,1)"
        const val SETTLE = "cubic-bezier(.2,.8,.2,1)"
        const val POP = "cubic-bezier(.34,1.56,.64,1)"
        const val EXPO = "cubic-bezier(.16,1,.3,1)"
        const val SINK = "cubic-bezier(.5,0,.75,0)"
        const val FALL = "cubic-bezier(.4,0,1,1)"
    }

    /** `rate`: integration steps a second. */
    const val RATE = 240.0

    /** Every key read, by its path in `spec.json`. */
    val KEYS: List<Pair<String, Any>> by lazy {
        listOf(
            "stage.w" to Stage.W,
            "stage.h" to Stage.H,
            "stage.detents.0" to Stage.DETENT_NARROW_LEFT,
            "stage.detents.1" to Stage.DETENT_WIDE_LEFT,
            "handle.w" to Handle.W,
            "handle.h" to Handle.H,
            "handle.bottom" to Handle.BOTTOM,
            "handle.bead" to Handle.BEAD,
            "handle.alpha" to Handle.ALPHA,
            "handle.dim" to Handle.DIM,
            "handle.dimGlass" to Handle.DIM_GLASS,
            "handle.fadeBy" to Handle.FADE_BY,
            "handle.swell" to Handle.SWELL,
            "handle.hit.w" to Handle.HIT_W,
            "handle.hit.h" to Handle.HIT_H,
            "pill.open.h" to Pill.OPEN_H,
            "pill.open.bottom" to Pill.OPEN_BOTTOM,
            "pill.margin" to Pill.MARGIN,
            "pill.min.w" to Pill.MIN_W,
            "pill.min.h" to Pill.MIN_H,
            "pill.sheen" to Pill.SHEEN,
            "pill.rim.w" to Pill.RIM_W,
            "pill.rim.stops.0" to Pill.RIM_STOP_TOP,
            "pill.rim.stops.1" to Pill.RIM_STOP_MID,
            "pill.rim.stops.2" to Pill.RIM_STOP_BOT,
            "pill.lift.0.0" to Pill.LIFT_X,
            "pill.lift.0.1" to Pill.LIFT_Y,
            "pill.lift.0.2" to Pill.LIFT_BLUR,
            "pill.lift.1.0" to Pill.LIFT_TIGHT_X,
            "pill.lift.1.1" to Pill.LIFT_TIGHT_Y,
            "pill.lift.1.2" to Pill.LIFT_TIGHT_BLUR,
            "row.pad" to Row.PAD,
            "row.gap.chip" to Row.GAP_CHIP,
            "row.gap.btn" to Row.GAP_BTN,
            "row.gap.sep" to Row.GAP_SEP,
            "row.gap.other" to Row.GAP_OTHER,
            "row.h.chip" to Row.H_CHIP,
            "row.h.single" to Row.H_SINGLE,
            "row.h.sep" to Row.H_SEP,
            "row.h.btn" to Row.H_BTN,
            "row.maxChips" to Row.MAX_CHIPS,
            "row.maxSingles" to Row.MAX_SINGLES,
            "row.recents" to Row.RECENTS,
            "icon.size" to Icon.SIZE,
            "icon.radius" to Icon.RADIUS,
            "chip.stacked" to Chip.STACKED,
            "chip.open" to Chip.OPEN,
            "chip.h" to Chip.H,
            "chip.inset" to Chip.INSET,
            "chip.gap" to Chip.GAP,
            "chip.cut" to Chip.CUT,
            "chip.overshoot" to Chip.OVERSHOOT,
            "chip.hit" to Chip.HIT,
            "chip.back.x" to Chip.BACK_X,
            "chip.back.y" to Chip.BACK_Y,
            "chip.back.scale" to Chip.BACK_SCALE,
            "single.hit" to Single.HIT,
            "btn.size" to Btn.SIZE,
            "btn.glyph" to Btn.GLYPH,
            "btn.stroke" to Btn.STROKE,
            "sep.w" to Sep.W,
            "mark.top" to Mark.TOP,
            "mark.w" to Mark.W,
            "mark.h" to Mark.H,
            "mark.r" to Mark.R,
            "mark.glow.0" to Mark.GLOW_NEAR,
            "mark.glow.1" to Mark.GLOW_FAR,
            "ghost.rimInset" to Ghost.RIM_INSET,
            "ghost.stroke" to Ghost.STROKE,
            "ghost.dash.0" to Ghost.DASH_ON,
            "ghost.dash.1" to Ghost.DASH_OFF,
            "ghost.glow" to Ghost.GLOW,
            "ghost.breathe.lo" to Ghost.BREATHE_LO,
            "ghost.breathe.hi" to Ghost.BREATHE_HI,
            "ghost.breathe.still" to Ghost.BREATHE_STILL,
            "ghost.solidFade" to Ghost.SOLID_FADE,
            "ghost.rimFrom" to Ghost.RIM_FROM,
            "badge.size" to Badge.SIZE,
            "badge.x" to Badge.X,
            "badge.y" to Badge.Y,
            "badge.hit" to Badge.HIT,
            "badge.star" to Badge.STAR,
            "badge.starStroke" to Badge.STAR_STROKE,
            "badge.ring" to Badge.RING,
            "badge.drop.0" to Badge.DROP_X,
            "badge.drop.1" to Badge.DROP_Y,
            "badge.drop.2" to Badge.DROP_BLUR,
            "apps.mark.w" to Apps.MARK_W,
            "apps.mark.h" to Apps.MARK_H,
            "apps.mark.r" to Apps.MARK_R,
            "apps.mark.alpha" to Apps.MARK_ALPHA,
            "apps.gap" to Apps.GAP,
            "pull.travel" to Pull.TRAVEL,
            "pull.items.from" to Pull.ITEMS_FROM,
            "pull.items.to" to Pull.ITEMS_TO,
            "pull.items.each" to Pull.ITEMS_EACH,
            "pull.band.factor" to Pull.BAND_FACTOR,
            "pull.band.cap" to Pull.BAND_CAP,
            "pull.window" to Pull.WINDOW,
            "pull.project" to Pull.PROJECT,
            "pull.half" to Pull.HALF,
            "pull.bound.width" to Pull.BOUND_WIDTH,
            "pull.bound.height" to Pull.BOUND_HEIGHT,
            "pull.bound.shut" to Pull.BOUND_SHUT,
            "pull.down.start" to Pull.DOWN_START,
            "pull.down.dominance" to Pull.DOWN_DOMINANCE,
            "pull.catch.span" to Pull.CATCH_SPAN,
            "pull.tap.visible" to Pull.TAP_VISIBLE,
            "bloom.size" to Bloom.SIZE,
            "bloom.stops.0" to Bloom.STOP_CORE,
            "bloom.stops.1" to Bloom.STOP_MID,
            "bloom.stops.2" to Bloom.STOP_CLEAR,
            "bloom.scale.0" to Bloom.SCALE_FROM,
            "bloom.scale.1" to Bloom.SCALE_PEAK,
            "bloom.scale.2" to Bloom.SCALE_TO,
            "bloom.peakAt" to Bloom.PEAK_AT,
            "press.scale" to Press.SCALE,
            "press.nudge" to Press.NUDGE,
            "press.star" to Press.STAR,
            "press.badge" to Press.BADGE,
            "press.slop" to Press.SLOP,
            "press.move" to Press.MOVE,
            "enter.dy" to Enter.DY,
            "enter.pop" to Enter.POP,
            "enter.exit" to Enter.EXIT,
            "star.hold" to Star.HOLD,
            "star.glow.0" to Star.GLOW_NEAR,
            "star.glow.1" to Star.GLOW_FAR,
            "timer.auto" to Timer.AUTO,
            "timer.dim" to Timer.DIM,
            "timer.selfOpen" to Timer.SELF_OPEN,
            "timer.ghostOpen" to Timer.GHOST_OPEN,
            "timer.tap" to Timer.TAP,
            "timer.clear" to Timer.CLEAR,
            "timer.chipNudge" to Timer.CHIP_NUDGE,
            "timer.homeReset" to Timer.HOME_RESET,
            "timer.splitOpen" to Timer.SPLIT_OPEN,
            "color.glass" to Color.GLASS,
            "color.glass-sheen" to Color.GLASS_SHEEN,
            "color.rim-top" to Color.RIM_TOP,
            "color.rim-mid" to Color.RIM_MID,
            "color.rim-bot" to Color.RIM_BOT,
            "color.lift" to Color.LIFT,
            "color.lift-tight" to Color.LIFT_TIGHT,
            "color.handle" to Color.HANDLE,
            "color.sep" to Color.SEP,
            "color.btn" to Color.BTN,
            "color.white" to Color.WHITE,
            "color.blue" to Color.BLUE,
            "color.blue-glow" to Color.BLUE_GLOW,
            "color.blue-haze" to Color.BLUE_HAZE,
            "color.blue-clear" to Color.BLUE_CLEAR,
            "color.crown" to Color.CROWN,
            "color.badge-dark" to Color.BADGE_DARK,
            "color.badge-ring" to Color.BADGE_RING,
            "color.badge-drop" to Color.BADGE_DROP,
            "color.bloom-core" to Color.BLOOM_CORE,
            "color.bloom-mid" to Color.BLOOM_MID,
            "pill.lift.0.3" to PillColorRef("lift", Pill.LIFT_COLOR),
            "pill.lift.1.3" to PillColorRef("lift-tight", Pill.LIFT_TIGHT_COLOR),
            "spring.release" to Spring.RELEASE,
            "spring.open" to Spring.OPEN,
            "spring.openH" to Spring.OPEN_H,
            "spring.close" to Spring.CLOSE,
            "spring.resize" to Spring.RESIZE,
            "spring.slide" to Spring.SLIDE,
            "spring.form" to Spring.FORM,
            "pull.catch.settle" to Spring.CATCH,
            "motion.glassIn" to Motion.GLASS_IN,
            "motion.rowIn" to Motion.ROW_IN,
            "motion.rowOut" to Motion.ROW_OUT,
            "motion.glassOut" to Motion.GLASS_OUT,
            "motion.dim" to Motion.DIM,
            "motion.wake" to Motion.WAKE,
            "motion.hide" to Motion.HIDE,
            "motion.show" to Motion.SHOW,
            "motion.enter" to Motion.ENTER,
            "motion.pop" to Motion.POP,
            "motion.exit" to Motion.EXIT,
            "motion.press" to Motion.PRESS,
            "motion.nudge" to Motion.NUDGE,
            "motion.ghostIn" to Motion.GHOST_IN,
            "motion.ghostOut" to Motion.GHOST_OUT,
            "motion.markFade" to Motion.MARK_FADE,
            "motion.breathe" to Motion.BREATHE,
            "motion.bloom" to Motion.BLOOM,
            "motion.starFlash" to Motion.STAR_FLASH,
            "motion.badgePress" to Motion.BADGE_PRESS,
            "motion.badgeRelease" to Motion.BADGE_RELEASE,
            "motion.badgeIn" to Motion.BADGE_IN,
            "motion.badgeOut" to Motion.BADGE_OUT,
            "motion.swapOut" to Motion.SWAP_OUT,
            "motion.swapMark" to Motion.SWAP_MARK,
            "motion.swapIn" to Motion.SWAP_IN,
            "motion.shrink.delay" to Motion.SHRINK_DELAY,
            "motion.swapOut.scale" to Motion.SWAP_OUT_SCALE,
            "motion.swapIn.spread" to Motion.SWAP_IN_SPREAD,
            "motion.swapIn.from" to Motion.SWAP_IN_FROM,
            "motion.swapIn.scale" to Motion.SWAP_IN_SCALE,
            "ease.std" to Ease.STD,
            "ease.settle" to Ease.SETTLE,
            "ease.pop" to Ease.POP,
            "ease.expo" to Ease.EXPO,
            "ease.sink" to Ease.SINK,
            "ease.fall" to Ease.FALL,
            "rate" to RATE,
        )
    }
}

/** A colour named by another key: the [name] in `color` and the ARGB it resolves to. */
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
