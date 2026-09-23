package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.LuminoforSpec
import dev.denza.apps.feature.vehicle.ConsumptionChart

/**
 * The ten-kilometre chart's ladder, which both screens draw on.
 *
 * This was the Contour's whole geometry - every coordinate derived from `gen_contour.py`'s five
 * decisions, joined to its boards by `ContourBoardContractTest`. The Luminofor board replaced that
 * composition and [ContourGeometry] is its arithmetic now; what is left here is what the car page
 * reads from the cluster, because the two screens draw one chart on one ladder
 * (`docs/energy-display-contract.md` §2.3) and a second record of it would be two numbers that
 * have to agree by hand.
 */
internal object ContourPlan {

    /**
     * A hundred points on a hundred metres of recorded road each, which is [ConsumptionChart]'s
     * own count rather than a second statement of it.
     */
    val PETAL_POINTS = ConsumptionChart.POINTS

    /**
     * A fixed ladder, not an autoscale: 0…60 up and 0…20 back down, clamped.
     *
     * Autoscaling to each window's own ceiling meant a point changed height when a *different*
     * point changed value. Over a kilometre of the owner's road 2 % passes 60 and nothing passes
     * −20, and on the cluster's cap and descender the two are nearly one slope, so the line crosses
     * the zero without a kink. The Luminofor spec states the same two numbers
     * (`cluster.trace.upTo` / `downTo`) and `ContourGeometryTest` holds them together.
     */
    const val PETAL_FULL = LuminoforSpec.Cluster.Trace.UP_TO
    const val PETAL_RETURN_FULL = LuminoforSpec.Cluster.Trace.DOWN_TO

    /**
     * And what a gutter writes them as, which is the car page's axis and nobody else's.
     *
     * The floor carries a typographic minus, U+2212, rather than the hyphen every figure in this
     * app is printed with: it is a label on an axis, and a hyphen beside «60» reads as a dash
     * between two numbers.
     */
    val PETAL_FULL_LABEL: String = ContourReadout.whole(PETAL_FULL.toDouble())
    val PETAL_RETURN_FULL_LABEL: String = "−" + ContourReadout.whole(PETAL_RETURN_FULL.toDouble())

    /**
     * A cut run's mark on the car page: three units just outside the edge it was cut against.
     *
     * The Luminofor cluster draws no tick - its trace is clamped silently at the figure's cap and
     * descender - so these are the car page's until that page is redrawn too.
     */
    const val PETAL_TICK = 3f
    const val PETAL_TICK_GAP = 2f

    /**
     * The old panel's data weight, in its units, which `ContourGlyphs` still strokes with while the
     * car page draws those glyphs. The Luminofor cluster draws `ThermalGlyphs` instead.
     */
    const val DATA_LINE = 2.5f
}
