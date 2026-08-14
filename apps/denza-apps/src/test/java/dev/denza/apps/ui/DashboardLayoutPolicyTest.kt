package dev.denza.apps.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutPolicyTest {
    @Test
    fun `measured one-third pane uses vertical layout`() {
        assertEquals(
            DashboardLayoutMode.NARROW,
            DashboardLayoutPolicy.resolve(416),
        )
    }

    @Test
    fun `measured two-thirds pane keeps full dashboard in horizontal scroll`() {
        assertEquals(
            DashboardLayoutMode.HORIZONTAL_SCROLL,
            DashboardLayoutPolicy.resolve(828),
        )
    }

    @Test
    fun `measured fullscreen width keeps the existing layout`() {
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(1_280),
        )
    }

    @Test
    fun `thresholds have no ambiguous width`() {
        assertEquals(
            DashboardLayoutMode.NARROW,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.HORIZONTAL_SCROLL,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP + 1),
        )
        assertEquals(
            DashboardLayoutMode.HORIZONTAL_SCROLL,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.HORIZONTAL_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.HORIZONTAL_MAX_WIDTH_DP + 1),
        )
    }
}
