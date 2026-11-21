package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Band
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.EngineCharge
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import dev.denza.apps.design.luminofor.WideDigits
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The Contour's arithmetic, in the board's own units and with no `Canvas` in it.
 *
 * Every anchor `drawCluster` computes is here once, and the renderer draws at these: the two side
 * groups and their edges, the hero's group centred on the axis, the band's square-root reach, the
 * trace's hundred-point pitch and its two ladders, the engine's block. What depends on a measured
 * string - the hero's «кВт», the trip's caption - takes the width as an argument, so the renderer
 * passes the car's own Jura and a JVM test passes the same TTF measured through AWT.
 *
 * ### The board's own literals
 *
 * spec.json carries the grid; a handful of numbers are written inline in `luminofor.js` instead -
 * the detail line's 19-unit figures and its 7/6/6/28 gaps, the blue dot, the trace's end dot, the
 * hot cell's pool of light, the beam's minimum lengths. They are named below with the line they
 * come from, rather than added to the spec: the spec is the owner's record and these are the
 * drawing code's.
 */
internal object ContourGeometry {

    const val W: Float = Cluster.W
    const val AXIS: Float = Cluster.AXIS_X

    /** The left group runs from the margin to [GROUP_LEFT_END], the right from [GROUP_RIGHT_START]. */
    const val GROUP_LEFT: Float = Cluster.MARGIN
    const val GROUP_LEFT_END: Float = AXIS - Grid.SIDE
    const val GROUP_RIGHT_START: Float = AXIS + Grid.SIDE
    const val GROUP_RIGHT_END: Float = W - Cluster.MARGIN

    /** The detail line under a group: the park line, the spread, the engine's run. */
    const val DETAIL_BASELINE: Float = Grid.BASELINE + Grid.DETAIL_DROP

    // ---- the band

    /**
     * How far along the axis a reading reaches: a square root over [Band.FULL_KW] on either side,
     * clamped at the margins. One scale both ways, so equal power draws equal length whether it
     * flows out of the pack or into it (until 2026-11-19 the return had its own 100 kW, and 14 kW
     * back drew longer than 26 kW out). No dead band of its own: the follower is already snapped to
     * zero under [ContourMotion.FLOOR_KW], and the board draws what it is given.
     */
    fun reach(kilowatts: Float): Float {
        val half = AXIS - Cluster.MARGIN
        val share = sqrt(min(abs(kilowatts), Band.FULL_KW) / Band.FULL_KW)
        return if (kilowatts >= 0f) half * share else -half * share
    }

    /** Whether the reading is past the scale, so the beam's end wears [clipTickX]'s tick. */
    fun clipped(kilowatts: Float): Boolean = abs(kilowatts) > Band.FULL_KW

    /** `luminofor.js` centre(): the clip tick stands its own half-length beyond the head. */
    fun clipTickX(kilowatts: Float): Float {
        val end = AXIS + reach(kilowatts)
        return if (kilowatts >= 0f) end + Band.CLIP_TICK_HALF else end - Band.CLIP_TICK_HALF
    }

    /** `luminofor.js` centre(): no beam, threads or head under two units of reach. */
    const val BEAM_MIN: Float = 2f

    /** And no peak mark under six, where it would stand inside the zero's own tick. */
    const val PEAK_MIN: Float = 6f

    /** The peak's mark fades from 0.6 by 0.07 a second, and never below 0.2. */
    fun peakIntensity(age: Float): Float = max(0.2f, 0.6f - age * 0.07f)

    /**
     * The zero's pool of light: 0.65·(|P| / 300 kW)^1.5, the band's one span either way, so the
     * glow is full exactly when the band is.
     *
     * The curve rises slowly and then fast, the band's square root turned over: the band shows the
     * small numbers, the light only the big ones. Recorded drives spend 90 % of their moving time
     * under 28 kW, where this is at most 4 levels of 255; it reaches 25, the brightest the first,
     * unseen glow ever got, near 90 kW. Out of the pack it is what it was; back into it, it followed
     * a 100 kW span until 2026-11-19 and now follows the same rule, so 14 kW back is under 2 levels
     * and 80 kW back 23 (they were 9 and 119). Until 2026-10-20 it was 0.13·√(|P| / 120 kW), which
     * no one could see on the car; the owner then picked ×6 with `:cluster-glow-probe`, which lit
     * through ordinary driving, and on 2026-10-26 asked for one step down and a curve that starts
     * slow.
     */
    fun glowAlpha(kilowatts: Float): Float =
        Band.GLOW_MAX * min(1f, abs(kilowatts) / Band.FULL_KW).pow(Band.GLOW_EXPONENT)

    /** The glow is not drawn under this, which is where the board stops drawing it too. */
    const val GLOW_MIN: Float = 0.005f

    /** How much taller than wide the glow's ellipse is: from the axis to the stock band and 63.9 past. */
    const val GLOW_SQUASH: Float =
        (Cluster.STOCK_BOTTOM - Grid.AXIS + Band.GLOW_REACH_BELOW) / Band.GLOW_RADIUS

    // ---- the hero

    /** Three digits is the widest the band's scale can print, so the field is known in advance. */
    val HERO_FIELD: Float = WideDigits.width("000", Grid.HERO_SIZE)

    /**
     * The right edge of the hero's field: field, gap and «кВт» are centred on the axis as one group,
     * so a two-digit reading does not leave the unit stranded and a three-digit one does not touch it.
     */
    fun heroFieldRight(unitWidth: Float): Float {
        val group = HERO_FIELD + Grid.HERO_UNIT_GAP + unitWidth
        return AXIS + group / 2f - Grid.HERO_UNIT_GAP - unitWidth
    }

    // ---- the left group

    /**
     * The widest temperature the row prints: a motor at 150°, which reaches as far as a pack at
     * −40° once its one hangs ([WideDigits.lead]). [Grid.TEMP_PITCH] holds it clear of the next
     * reading by twice the air a reading has inside it, so five readings past a hundred stay five.
     */
    const val TEMPERATURE_WIDEST: String = "150°"

    /**
     * The first temperature cell, [Grid.TEMPS_LEFT]: further from «НАПРЯЖЕНИЕ» than two readings
     * stand from each other. A reading past a hundred in the last cell reaches past the group's edge
     * into the air before the hero's field and stops well short of it. Until 2026-11-19 the row
     * ended on the group's edge at two figures, 15 units after the caption, at a pitch of 80.
     */
    const val TEMPS_LEFT: Float = Grid.TEMPS_LEFT

    /** The left edge of the row's [slot]th cell, in `ThermalGlyphs.ROW`'s order. */
    fun tempX(slot: Int): Float = TEMPS_LEFT + slot * Grid.TEMP_PITCH

    /** Where a figure's «°» starts when the two are drawn apart: one tracking step after it. */
    fun degreeX(x: Float, value: String): Float =
        x + WideDigits.extent(value, Grid.TEMP_SIZE) + Digits.TRACK * WideDigits.k(Grid.TEMP_SIZE)

    /** `luminofor.js` drawCluster(): the hot cell's pool, centred 24 right and 22 up of the figure. */
    const val HOT_DX: Float = 24f
    const val HOT_DY: Float = 22f
    const val HOT_RADIUS: Float = 48f

    /** It breathes: 0.10 + 0.04·sin(4t). */
    fun hotAlpha(t: Float): Float = 0.10f + 0.04f * kotlin.math.sin(t * 4f)

    /** The spread's line: caption, eight, the figure at 19, six, the unit. */
    const val SPREAD_CAPTION_GAP: Float = 8f
    const val SPREAD_UNIT_GAP: Float = 6f

    // ---- the right group

    /**
     * The trip is flush right, all three of its lines: the caption, «9,3» with its gap and «кВт·ч»,
     * and the detail line under them each end on the margin.
     */
    fun tripCaptionLeft(captionWidth: Float): Float = GROUP_RIGHT_END - captionWidth

    /** @param payload the figure's extent plus the gap and the unit */
    fun tripFigureLeft(payload: Float): Float = GROUP_RIGHT_END - payload

    fun tripPayload(figureWidth: Float, unitWidth: Float): Float =
        figureWidth + Grid.UNIT_GAP + unitWidth

    /**
     * The detail line's figures, and the gaps `runLeft` leaves in a reading: caption, seven, the
     * figure, six, the unit - and six from the recuperation's caption to its blue mark.
     */
    const val DETAIL_FIGURE: Float = 19f
    const val DETAIL_CAPTION_GAP: Float = 7f
    const val DETAIL_UNIT_GAP: Float = 6f
    const val DETAIL_BEFORE_DOT: Float = 6f

    /** Between two readings of the line: the road and «ЗАРЯД ОТ ДВС», that and «РЕКУПЕРАЦИЯ». */
    const val DETAIL_PAIR_GAP: Float = 28f

    /** The blue mark in front of the recuperation: centred 4 left and 5 up, and it takes ten. */
    const val DOT_RADIUS: Float = 3.2f
    const val DOT_DX: Float = 4f
    const val DOT_DY: Float = 5f
    const val DOT_ADVANCE: Float = 10f
    const val DOT_BLUR: Float = 8f
    const val DOT_INTENSITY: Float = 0.9f

    // ---- the engine's block

    /**
     * The block is flush right, [EngineCharge.WIDTH] wide: the line from its left edge to the
     * figure's seat, the seat [EngineCharge.COLUMN] wide at the right end. Between them two of the
     * trace's gaps, the room the ten kilometres leave between their line and their figure.
     */
    const val BLOCK_LEFT: Float = GROUP_RIGHT_END - EngineCharge.WIDTH
    const val CHARGE_LEFT: Float = BLOCK_LEFT
    const val CHARGE_RIGHT: Float = GROUP_RIGHT_END - EngineCharge.COLUMN - 2f * Trace.GAP_FROM_AXIS
    const val CHARGE_FIGURE_X: Float = GROUP_RIGHT_END - EngineCharge.COLUMN

    /** A point a second: a full window runs edge to edge, a younger run grows from the right. */
    const val CHARGE_PITCH: Float = (CHARGE_RIGHT - CHARGE_LEFT) / (EngineCharge.POINTS - 1)

    /**
     * Where a pause's seam stands: halfway between the last point before it and the first after it,
     * [index] being the latter's among [count] points drawn from the right edge.
     */
    fun chargeMarkX(index: Int, count: Int): Float = CHARGE_RIGHT - (count - index - 0.5f) * CHARGE_PITCH

    /** The line's height is the figures': zero on their baseline, the ceiling at their cap. */
    const val CHARGE_ZERO: Float = Grid.BASELINE
    const val CHARGE_TOP: Float = Grid.BASELINE - Digits.CAP_RATIO * Grid.FIGURE_SIZE

    /** The block's fill under its line, the ten kilometres' blue below their zero: oldest, newest. */
    const val CHARGE_FILL_OLD: Float = 0.05f
    const val CHARGE_FILL_NEW: Float = 0.3f

    /** And its zero, a hairline across the line's width. */
    const val CHARGE_BASE_INTENSITY: Float = 0.35f

    // ---- the ten kilometres

    const val TRACE_LEFT: Float = AXIS - Trace.GAP_FROM_AXIS - Trace.WIDTH
    const val TRACE_RIGHT: Float = AXIS - Trace.GAP_FROM_AXIS

    /**
     * One pitch for the hundred points: a full window runs edge to edge and a filling one grows
     * leftward from the right edge at the same pitch, so it is as wide as its road.
     */
    const val TRACE_PITCH: Float = Trace.WIDTH / (Trace.POINTS - 1)

    /** The ten runs brighten toward the present: 0.28 + 0.72·((r + 1) / 10)^1.6. */
    fun runIntensity(run: Int): Float =
        (0.28 + 0.72 * Math.pow((run + 1).toDouble() / Trace.RUNS, 1.6)).toFloat()

    /** The newest point's dot. */
    const val TRACE_DOT: Float = 2.6f
    const val TRACE_DOT_BLUR: Float = 12f

    /** The figure starts one gap right of the axis; its unit follows it. */
    const val TRACE_FIGURE_X: Float = AXIS + Trace.GAP_FROM_AXIS

    // ---- the stock cut-out the trace's figure and unit stand in

    /** The petal's right edge at [y], or the axis where the ellipse does not reach. */
    fun petalRight(y: Float): Float {
        val dy = (y - Cluster.PETAL_CY) / Cluster.PETAL_RY
        val t = 1f - dy * dy
        return AXIS + if (t > 0f) Cluster.PETAL_RX * sqrt(t) else 0f
    }

    fun petalLeft(y: Float): Float = 2f * AXIS - petalRight(y)

    /**
     * Whether the frame has anything that moves with time alone - the threads along a beam, a hot
     * cell's pulse - and so has to be drawn at the fast pace even when no reading is travelling.
     */
    fun flickers(frame: ContourFrame): Boolean {
        if (frame.unavailable) return false
        if (frame.powerFresh && abs(reach(frame.powerKw)) > BEAM_MIN) return true
        return frame.temps.any { it.shown && it.level != ContourReadout.Level.NORMAL }
    }
}
