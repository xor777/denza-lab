package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.cluster.ClusterBounds
import dev.denza.apps.feature.cluster.ClusterMapLayout
import dev.denza.apps.feature.cluster.ClusterMapPlacement

/** A rectangle in fractions of the dashboard's own size, `0f` to `1f` on each axis. */
data class DashboardBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Where an app-owned dashboard may draw on the driver's display, and where its blocks go.
 *
 * The cluster is not a canvas we own: the vehicle keeps drawing its own instruments underneath and,
 * on the evidence of the mirroring experiment in `docs/instrument-display-findings.md`, above us as
 * well. So a dashboard here is a set of islands, not a screen.
 *
 * The keep-outs are not invented for this feature. They are read out of [ClusterMapLayout]'s own
 * shade configuration - the numbers that were tuned on the car so a projected map would not cover
 * instrument data. Wherever that shade blacks the map out, something stock lives; wherever it cuts
 * a reveal, the map was allowed through and we may draw. Deriving them here rather than restating
 * them means the two cannot drift apart.
 *
 * One honest limit: those numbers were tuned by eye against live captures, not measured. The exact
 * boundary still wants one capture of the physical cluster to confirm. If it moves inward we gain
 * room; nothing here breaks, because every block is placed against these values rather than
 * against the panel edge.
 */
data class ClusterDashboardLayout(
    val displayWidth: Int,
    val displayHeight: Int,
    val placement: ClusterMapPlacement,
) {

    private val map = ClusterMapLayout(displayWidth, displayHeight, placement)

    /** Where the dashboard sits on the panel. */
    val bounds: ClusterBounds = map.surfaceBounds

    val width: Int = bounds.right - bounds.left
    val height: Int = bounds.bottom - bounds.top

    /**
     * Whether this placement is offered at all.
     *
     * `CENTER` is refused: it is the one zone with no successful live render on record - Waze came
     * back black at every interval, and no other navigator has been captured there either - and it
     * additionally spends its top 36 percent on a near-opaque gradient. `LEFT` is refused because
     * its keep-out is a quarter-disc rather than a band, which no block in this design fits.
     */
    val supported: Boolean =
        placement == ClusterMapPlacement.FULL || placement == ClusterMapPlacement.RIGHT

    /**
     * Whether this placement is too small for the reveal blocks and their extra lines.
     *
     * It is a property of the placement rather than of the panel: the right third is a third of
     * the width with no reveals to put anything in.
     */
    val compact: Boolean = placement == ClusterMapPlacement.RIGHT

    /**
     * The virtual space this placement is drawn in, matching the design's own boards.
     *
     * Both land on the panel at the same 1.70 scale - 424 into 720 across the full width, 308 into
     * 524 in the right third - which is what lets one type ramp serve both: a size of 13 units is
     * the same number of millimetres on the glass either way.
     *
     * It lives here rather than on the density because a density is a set of sizes and a space is a
     * property of the panel. A block drawn with the narrow ramp inside the wide space - which is
     * what every corner reveal is - has to be measured against 424, not 308.
     */
    val virtualHeight: Float = if (placement == ClusterMapPlacement.RIGHT) 308f else 424f

    /** Stock graphics occupy everything above this, except inside the top reveals. */
    val stockTop: Float =
        if (height <= 0) 0f else map.shadeTopRevealHeightPx.toFloat() / height

    /** Stock graphics occupy everything below this, except inside the bottom reveal. */
    val stockBottom: Float = if (height <= 0) {
        1f
    } else {
        1f - (map.shadeBottomSolidPx + map.shadeBottomFadePx).toFloat() / height
    }

    private val topLeftRevealX: Float =
        if (width <= 0) 0f else map.shadeTopLeftRevealRadiusPx.toFloat() / width
    private val topRightRevealX: Float =
        if (width <= 0) 0f else map.shadeTopRightRevealRadiusPx.toFloat() / width
    private val bottomRevealX: Float =
        if (width <= 0) 0f else map.shadeBottomRevealRadiusPx.toFloat() / width
    private val bottomRevealY: Float = if (height <= 0) {
        0f
    } else {
        map.shadeBottomRevealRadiusPx.toFloat() *
            map.shadeBottomRevealHeightPercent / 100f / height
    }
    private val bottomRevealCentreY: Float = if (height <= 0) {
        1f
    } else {
        1f - map.shadeBottomRevealCenterOffsetPx.toFloat() / height
    }

    /**
     * Whether a point may be drawn on.
     *
     * The band between the two stock edges is always ours. Outside it, only the three reveals are,
     * and a placement with no shade at all - `RIGHT`, whose protection is the crop itself - has no
     * stock edge to be outside of.
     */
    fun isClear(x: Float, y: Float): Boolean = when {
        y in stockTop..stockBottom -> true
        y < stockTop -> insideEllipse(x, y, 0f, 0f, topLeftRevealX, stockTop) ||
            insideEllipse(x, y, 1f, 0f, topRightRevealX, stockTop)
        else -> insideEllipse(x, y, 0.5f, bottomRevealCentreY, bottomRevealX, bottomRevealY)
    }

    /** Whether every corner of a block may be drawn on. */
    fun isClear(box: DashboardBox): Boolean =
        isClear(box.left, box.top) &&
            isClear(box.right, box.top) &&
            isClear(box.left, box.bottom) &&
            isClear(box.right, box.bottom)

    private fun insideEllipse(
        x: Float,
        y: Float,
        centreX: Float,
        centreY: Float,
        radiusX: Float,
        radiusY: Float,
    ): Boolean {
        if (radiusX <= 0f || radiusY <= 0f) return false
        val dx = (x - centreX) / radiusX
        val dy = (y - centreY) / radiusY
        return dx * dx + dy * dy <= 1f
    }

    /** The electric instrument: state of the pack that the car itself never shows. */
    val electricBlock: DashboardBox = band(0.017f, 0.303f)

    /** The combustion instrument: revolutions and what they are putting back. */
    val engineBlock: DashboardBox = band(0.697f, 0.983f)

    /** Temperatures, in the top-left reveal. Absent where there is no reveal to use. */
    val temperatureBlock: DashboardBox? = topReveal(topLeftRevealX, fromLeft = true)

    /** The fluid lamps, in the top-right reveal. */
    val lampBlock: DashboardBox? = topReveal(topRightRevealX, fromLeft = false)

    /**
     * Centre of the energy gauge, and its radius as a fraction of the dashboard's height.
     *
     * The full-width radius is set by the crown, not by taste: the dial's marks stand outward from
     * the arc, and straight up is where the clear band's top edge crosses it. At `0.377` the mark
     * cleared the arc but not the edge - by two pixels, which is exactly the kind of miss that never
     * shows up in a drawing. `ClusterDashboardLayoutTest` holds this against
     * `EnergyGauge.topReach`, so a change to the mark length is caught here rather than on the car.
     */
    val gaugeCentreX: Float = 0.5f
    val gaugeCentreY: Float = if (placement == ClusterMapPlacement.RIGHT) 0.76f else 0.78f
    val gaugeRadius: Float = if (placement == ClusterMapPlacement.RIGHT) 0.383f else 0.372f

    private fun band(left: Float, right: Float): DashboardBox {
        val top = stockTop + (stockBottom - stockTop) * 0.06f
        val bottom = stockBottom - (stockBottom - stockTop) * 0.03f
        return DashboardBox(left, top, right, bottom)
    }

    /**
     * The widest block that fits inside a top reveal, or `null` where the placement has none.
     *
     * A reveal is a quarter-ellipse anchored to its corner, so an inscribed rectangle loses width
     * quickly as it grows downward. The width is therefore solved for the block's *lower* edge
     * rather than chosen: everything above that line is wider still and cannot escape.
     */
    private fun topReveal(radiusX: Float, fromLeft: Boolean): DashboardBox? {
        if (radiusX <= 0f || stockTop <= 0f) return null
        val bottom = stockTop * REVEAL_BOTTOM
        val reach = radiusX * kotlin.math.sqrt(1f - REVEAL_BOTTOM * REVEAL_BOTTOM) - REVEAL_MARGIN
        if (reach <= REVEAL_INSET) return null
        return if (fromLeft) {
            DashboardBox(REVEAL_INSET, stockTop * REVEAL_TOP, reach, bottom)
        } else {
            DashboardBox(1f - reach, stockTop * REVEAL_TOP, 1f - REVEAL_INSET, bottom)
        }
    }

    private companion object {
        /**
         * Where a reveal block starts and ends, as a share of the reveal's own depth.
         *
         * The bottom edge buys height at the cost of width - the reveal is a quarter-ellipse, so the
         * lower the block's floor, the narrower the widest rectangle that still fits under the
         * curve. These two numbers are where the fluid grid and its sentence stop overflowing while
         * the block stays wide enough to hold that sentence; `ClusterBlockPlanTest` is what says so.
         */
        const val REVEAL_TOP = 0.14f
        const val REVEAL_BOTTOM = 0.70f

        /** Kept off the panel edge, and off the curve the reveal is bounded by. */
        const val REVEAL_INSET = 0.017f
        const val REVEAL_MARGIN = 0.008f
    }
}
