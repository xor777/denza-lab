package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.cluster.ClusterBounds
import dev.denza.apps.feature.cluster.ClusterMapLayout
import dev.denza.apps.feature.cluster.ClusterMapPlacement

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
     * Whether this placement is offered at all, and only `FULL` is.
     *
     * `CENTER` is refused: it is the one zone with no successful live render on record - Waze came
     * back black at every interval, and no other navigator has been captured there either - and it
     * additionally spends its top 36 percent on a near-opaque gradient. `LEFT` is refused because
     * its keep-out is a quarter-disc rather than a band.
     *
     * `RIGHT` is refused as of the Contour, and this is the one that changed. The panel is a single
     * composition measured against the whole width: one hero on the axis, one band from margin to
     * margin, two corners inside the stock apertures and two shelves in the clear band's flanks. A
     * third of that is not a smaller version of this instrument, it is a different instrument, and
     * this product does not offer that one. Nothing in the app asked for it either -
     * `NavigationPlacementPolicy.offered` has returned `FULL` alone for the dashboard since it
     * shipped, so the narrow composition was code with no caller and one more thing to keep true.
     */
    val supported: Boolean = placement == ClusterMapPlacement.FULL

    /**
     * The virtual space the panel is drawn in, matching the design's own boards.
     *
     * 424 units into 720 pixels is a factor of 1.70, and every size on the ramp is stated in these
     * units: at the measured 320 mm of glass one unit is 0.2123 mm, which is what turns a rung into
     * a number of arc minutes from the driver's seat.
     *
     * It lives here rather than on the density because a density is a set of sizes and a space is a
     * property of the panel.
     */
    val virtualHeight: Float = 424f

    /** Stock graphics occupy everything above this, except inside the top reveals. */
    val stockTop: Float =
        if (height <= 0) 0f else map.shadeTopRevealHeightPx.toFloat() / height

    /** Stock graphics occupy everything below this, except inside the bottom reveal. */
    val stockBottom: Float = if (height <= 0) {
        1f
    } else {
        1f - (map.shadeBottomSolidPx + map.shadeBottomFadePx).toFloat() / height
    }

    /**
     * The three apertures, as fractions of the panel.
     *
     * Public because the Contour is placed against them rather than against the panel edge: the two
     * corners are quarter-ellipses anchored at `y = 0` with these as their horizontal radii and
     * [stockTop] as their vertical one, and the petal is a half-ellipse centred at
     * [bottomRevealCentreY]. Every anchor the panel has is either a guard off [stockTop] /
     * [stockBottom] or a clearance from one of these curves, and [ContourPlan] is where that
     * arithmetic lives.
     */
    val topLeftRevealX: Float =
        if (width <= 0) 0f else map.shadeTopLeftRevealRadiusPx.toFloat() / width
    val topRightRevealX: Float =
        if (width <= 0) 0f else map.shadeTopRightRevealRadiusPx.toFloat() / width
    val bottomRevealX: Float =
        if (width <= 0) 0f else map.shadeBottomRevealRadiusPx.toFloat() / width
    val bottomRevealY: Float = if (height <= 0) {
        0f
    } else {
        map.shadeBottomRevealRadiusPx.toFloat() *
            map.shadeBottomRevealHeightPercent / 100f / height
    }
    val bottomRevealCentreY: Float = if (height <= 0) {
        1f
    } else {
        1f - map.shadeBottomRevealCenterOffsetPx.toFloat() / height
    }

    /**
     * Whether a point may be drawn on.
     *
     * The band between the two stock edges is always ours; outside it, only the three apertures
     * are. It is what `ContourPlanTest` measures the panel's own boxes against, which is the reason
     * a fraction rather than a unit: this is a fact about the window, and the panel is drawn in a
     * space of its own that the window is fitted to.
     */
    fun isClear(x: Float, y: Float): Boolean = when {
        y in stockTop..stockBottom -> true
        y < stockTop -> insideEllipse(x, y, 0f, 0f, topLeftRevealX, stockTop) ||
            insideEllipse(x, y, 1f, 0f, topRightRevealX, stockTop)
        else -> insideEllipse(x, y, 0.5f, bottomRevealCentreY, bottomRevealX, bottomRevealY)
    }

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
}
