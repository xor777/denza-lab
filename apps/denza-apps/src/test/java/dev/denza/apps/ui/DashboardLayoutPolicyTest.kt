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
    fun `measured two-thirds pane fits the dashboard into its own width`() {
        // Правка W8: холст 1280 dp в горизонтальном скролле прятал ~904 px за краем панели 828 dp.
        assertEquals(
            DashboardLayoutMode.MEDIUM,
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
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP + 1),
        )
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP + 1),
        )
    }

    @Test
    fun `card rows follow the mode - whole group, pairs, single column`() {
        assertEquals(3, DashboardLayoutPolicy.rowCapacity(DashboardLayoutMode.WIDE, 3))
        assertEquals(2, DashboardLayoutPolicy.rowCapacity(DashboardLayoutMode.MEDIUM, 3))
        assertEquals(1, DashboardLayoutPolicy.rowCapacity(DashboardLayoutMode.NARROW, 3))
        assertEquals("пустая группа не делится на ноль", 1, DashboardLayoutPolicy.rowCapacity(DashboardLayoutMode.WIDE, 0))
    }
}
