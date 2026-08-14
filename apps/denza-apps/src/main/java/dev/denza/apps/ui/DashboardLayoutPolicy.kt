package dev.denza.apps.ui

/**
 * Chooses the dashboard shell from the Activity's current window width.
 *
 * The live DiLink 5.1 windows are 416 dp (narrow pane), 828 dp (wide pane), and
 * 1280 dp (fullscreen). Keeping the thresholds between those measured sizes
 * makes the decision independent of native root ids and divider side.
 */
internal object DashboardLayoutPolicy {
    const val NARROW_MAX_WIDTH_DP = 599
    const val HORIZONTAL_MAX_WIDTH_DP = 1_099

    fun resolve(widthDp: Int): DashboardLayoutMode = when {
        widthDp <= NARROW_MAX_WIDTH_DP -> DashboardLayoutMode.NARROW
        widthDp <= HORIZONTAL_MAX_WIDTH_DP -> DashboardLayoutMode.HORIZONTAL_SCROLL
        else -> DashboardLayoutMode.WIDE
    }
}

internal enum class DashboardLayoutMode {
    WIDE,
    HORIZONTAL_SCROLL,
    NARROW,
}
