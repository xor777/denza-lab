package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Band
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.EngineBox
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Grid
import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster.Trace
import dev.denza.apps.design.luminofor.LuminoforSpec.Digits
import dev.denza.apps.design.luminofor.WideDigits
import dev.denza.apps.feature.vehicle.EngineTrace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Contour's arithmetic, in the board's own units and with no `Canvas` in it.
 *
 * Every anchor `drawCluster` computes is here once, and the renderer draws at these: the two side
 * groups and their edges, the hero's group centred on the axis, the band's square-root reach, the
 * trace's hundred-point pitch and its two ladders, the engine box. What depends on a measured
 * string - the hero's «кВт», the trip's caption - takes the width as an argument, so the renderer
 * passes the car's own Jura and a JVM test passes the same TTF measured through AWT.
 *
 * ### The board's own literals
 *
 * spec.json carries the grid; a handful of numbers are written inline in `luminofor.js` instead -
 * the detail line's 19-unit figures and its 7/6/28 gaps, the blue dot, the trace's end dot, the
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

    /** The detail line under a group: the park line, the spread, the engine box's window. */
    const val DETAIL_BASELINE: Float = Grid.BASELINE + Grid.DETAIL_DROP

    // ---- the band

    /**
     * How far along the axis a reading reaches: a square root over 300 kW out and 100 kW back,
     * clamped at the margins. The same two spans as `EnergyScale`, without its dead band: the
     * follower is already snapped to zero under it, and the board draws what it is given.
     */
    fun reach(kilowatts: Float): Float {
        val half = AXIS - Cluster.MARGIN
        return if (kilowatts >= 0f) {
            half * sqrt(min(kilowatts, Band.OUT_KW) / Band.OUT_KW)
        } else {
            -half * sqrt(min(-kilowatts, Band.IN_KW) / Band.IN_KW)
        }
    }

    /** `luminofor.js` centre(): no beam, threads or head under two units of reach. */
    const val BEAM_MIN: Float = 2f

    /** And no peak mark under six, where it would stand inside the zero's own tick. */
    const val PEAK_MIN: Float = 6f

    /** The peak's mark fades from 0.6 by 0.07 a second, and never below 0.2. */
    fun peakIntensity(age: Float): Float = max(0.2f, 0.6f - age * 0.07f)

    /** The zero's pool of light: 0.13·√(|P| / 120), saturated at 120 kW. */
    fun glowAlpha(kilowatts: Float): Float =
        Band.GLOW_MAX * sqrt(min(1f, abs(kilowatts) / Band.GLOW_FULL_KW))

    /** The glow is not drawn under this, which is where the board stops drawing it too. */
    const val GLOW_MIN: Float = 0.005f

    /** How much taller than wide the glow's ellipse is: from the axis to the stock band and forty past. */
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

    /** The first temperature cell: the five end flush with the group's edge at two digits and «°». */
    val TEMPS_LEFT: Float =
        GROUP_LEFT_END - WideDigits.width("00°", Grid.TEMP_SIZE) - (ContourFrame.CELLS - 1) * Grid.TEMP_PITCH

    fun tempX(index: Int): Float = TEMPS_LEFT + index * Grid.TEMP_PITCH

    /** Where a figure's «°» starts when the two are drawn apart: one tracking step after it. */
    fun degreeX(x: Float, value: String): Float =
        x + WideDigits.width(value, Grid.TEMP_SIZE) + Digits.TRACK * WideDigits.k(Grid.TEMP_SIZE)

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
     * The trip is flush right: its caption and its figure share a left edge, and the wider of the
     * two - «42 км · ЗА ПОЕЗДКУ», or «9,3» with its gap and «кВт·ч» - ends on the margin.
     *
     * @param payload the figure's width plus the gap and the unit, or zero while there is no figure
     */
    fun tripLeft(captionWidth: Float, payload: Float): Float =
        GROUP_RIGHT_END - max(captionWidth, payload)

    fun tripPayload(figureWidth: Float, unitWidth: Float): Float =
        figureWidth + Grid.UNIT_GAP + unitWidth

    /** The detail line's figures, and the gaps `runLeft` leaves after a caption, a unit and a figure. */
    const val DETAIL_FIGURE: Float = 19f
    const val DETAIL_AFTER_CAPTION: Float = 7f
    const val DETAIL_AFTER_UNIT: Float = 6f
    const val DETAIL_AFTER_FIGURE: Float = 6f

    /** Between «ДАЛ ДВС»'s figure and «РЕКУПЕРАЦИЯ». */
    const val DETAIL_PAIR_GAP: Float = 28f

    /** The blue mark in front of the recuperation: centred 4 left and 5 up, and it takes ten. */
    const val DOT_RADIUS: Float = 3.2f
    const val DOT_DX: Float = 4f
    const val DOT_DY: Float = 5f
    const val DOT_ADVANCE: Float = 10f
    const val DOT_BLUR: Float = 8f
    const val DOT_INTENSITY: Float = 0.9f

    // ---- the engine box

    /** `EngineTrace`'s two minutes in its own five-second steps. */
    const val ENGINE_BINS: Int = EngineTrace.SLOTS / EngineTrace.BIN_SECONDS

    const val BOX_LEFT: Float = GROUP_RIGHT_END - EngineBox.WIDTH

    /** The box is the figures' own height: zero on their baseline, the top at their cap. */
    const val BOX_ZERO: Float = Grid.BASELINE
    const val BOX_TOP: Float = Grid.BASELINE - Digits.CAP_RATIO * Grid.FIGURE_SIZE

    /**
     * One step. The board divides the box by the bins it was given, which is twenty-four in every
     * fixture; the app divides it by twenty-four always and anchors the steps at the right edge,
     * because the trace grows from the right and is never front-padded - a box forty seconds old
     * under «ПОСЛЕДНИЕ 0:40» is eight steps wide, not twenty-four steps stretched.
     */
    const val BOX_PITCH: Float = EngineBox.WIDTH / ENGINE_BINS

    /** Generation on its linear span to 30 kW, clamped at both ends. */
    fun boxY(kilowatts: Float): Float =
        BOX_ZERO - (BOX_ZERO - BOX_TOP) * min(1f, max(0f, kilowatts) / EngineBox.UP_TO)

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
