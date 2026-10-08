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
 * The keep-outs are not invented for this feature. They are [ClusterMapLayout]'s own shade
 * configuration - the numbers that were tuned on the car so a projected map would not cover
 * instrument data. Wherever that shade blacks the map out, something stock lives; wherever it cuts
 * a reveal, the map was allowed through and we may draw. The panel is laid out against the same
 * zones in the cluster's own units (`cluster.stock` in the Luminofor spec), and
 * `ClusterDashboardLayoutTest` holds the spec's numbers to the shade's.
 *
 * One honest limit: those numbers were tuned by eye against live captures, not measured. The exact
 * boundary still wants one capture of the physical cluster to confirm.
 */
data class ClusterDashboardLayout(
    val displayWidth: Int,
    val displayHeight: Int,
    val placement: ClusterMapPlacement,
) {

    /** Where the dashboard sits on the panel. */
    val bounds: ClusterBounds = ClusterMapLayout(displayWidth, displayHeight, placement).surfaceBounds

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

    // There was an `isClear(x, y)` here, answering whether a point falls inside the clear band or
    // one of the three apertures, and its own documentation said `ContourPlanTest` measured the
    // panel's boxes against it. Nothing did. The panel is held inside the curves by asking where
    // its widest strings end against the petal's own ellipse (`ContourGeometryTest`), which is a
    // different and stronger statement than a point test - it is about the unit's last glyph, and
    // about the descender under a baseline - so the predicate had no production reader and one test
    // checking that it agreed with itself.
}
