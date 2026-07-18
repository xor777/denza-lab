package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterDisplayResolverTest {
    @Test
    fun exactKnownDisplayWinsOverRearAndOtherVirtualDisplays() {
        val cluster = candidate(4, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY, 1920, 720)
        val result = ClusterDisplayResolver.select(
            listOf(
                candidate(0, "Built-in Screen", 1920, 1080),
                candidate(2, "left_rse_screen", 1920, 1080),
                candidate(3, "OpenBYD virtual fission", 1920, 720, own = true),
                cluster,
                candidate(5, "overhead_screen", 1920, 1080),
            ),
        )

        assertEquals(cluster, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun manualOverrideWinsWithoutHardcodedFallback() {
        val selected = candidate(8, "Driver instrument panel", 1560, 540)
        val result = ClusterDisplayResolver.select(listOf(selected), manualOverrideId = 8)
        assertEquals(selected, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun equalPlausibleDisplaysRequireVerification() {
        val result = ClusterDisplayResolver.select(
            listOf(
                candidate(6, "vendor_cluster_left", 1920, 720),
                candidate(7, "vendor_cluster_right", 1920, 720),
            ),
        )
        assertTrue(result is ClusterDisplaySelection.NeedsVerification)
    }

    @Test
    fun unknownWideDisplayIsNotGuessed() {
        val result = ClusterDisplayResolver.select(listOf(candidate(9, "HDMI", 1920, 720)))
        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    @Test
    fun cameraOverlayUsesSecondNamedFissionDisplay() {
        val base = candidate(3, ClusterDisplayResolver.KNOWN_DENZA_DISPLAY, 2560, 720)
        val overlay = candidate(
            4,
            ClusterDisplayResolver.KNOWN_DENZA_CAMERA_OVERLAY_DISPLAY,
            2560,
            720,
        )

        val result = ClusterDisplayResolver.selectCameraOverlay(listOf(base, overlay))

        assertEquals(overlay, (result as ClusterDisplaySelection.Selected).display)
    }

    @Test
    fun cameraOverlayDoesNotGuessAnotherWideDisplay() {
        val result = ClusterDisplayResolver.selectCameraOverlay(
            listOf(candidate(7, "vendor_cluster_overlay", 2560, 720)),
        )

        assertTrue(result is ClusterDisplaySelection.Missing)
    }

    private fun candidate(
        id: Int,
        name: String,
        width: Int,
        height: Int,
        own: Boolean = false,
    ) = ClusterDisplayDescriptor(
        id = id,
        name = name,
        width = width,
        height = height,
        densityDpi = 240,
        type = ClusterDisplayResolver.DISPLAY_TYPE_VIRTUAL,
        flags = 0,
        isOwnVirtualDisplay = own,
    )
}
