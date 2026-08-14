package dev.denza.apps.feature.simulcast

import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastVideoSizeResolverTest {
    private fun display(
        id: Int,
        name: String,
        width: Int,
        height: Int,
        ownVirtual: Boolean = false,
    ) = ClusterDisplayDescriptor(
        id = id,
        name = name,
        width = width,
        height = height,
        densityDpi = 160,
        type = 0,
        flags = 0,
        isOwnVirtualDisplay = ownVirtual,
    )

    private val iviDisplay = display(0, "ivi_screen", 2560, 1600)

    @Test
    fun matchesRearDisplayAndCopiesItsAspect() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_rse_l",
            listOf(iviDisplay, display(5, "left_rse_screen", 1920, 1200)),
        )

        assertTrue(resolution.matched)
        assertEquals(2560, resolution.videoWidth)
        assertEquals(1600, resolution.videoHeight)
        assertEquals(1920, resolution.viewportWidth)
        assertEquals(1200, resolution.viewportHeight)
    }

    @Test
    fun keepsProvenSizeForSixteenByNinePanels() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_fse",
            listOf(iviDisplay, display(4, "fse_screen", 1920, 1080)),
        )

        assertTrue(resolution.matched)
        assertEquals(2560, resolution.videoWidth)
        assertEquals(1440, resolution.videoHeight)
    }

    @Test
    fun fallsBackWhenNoDisplayMatches() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_rse_l",
            listOf(iviDisplay),
        )

        assertFalse(resolution.matched)
        assertEquals(2560, resolution.videoWidth)
        assertEquals(1440, resolution.videoHeight)
        assertEquals(2560, resolution.viewportWidth)
        assertEquals(1600, resolution.viewportHeight)
    }

    @Test
    fun neverMatchesSourceVirtualOrClusterDisplays() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_tv",
            listOf(
                display(0, "rear_builtin", 1920, 1200),
                display(7, "BYD-Mirror", 1024, 600),
                display(8, "Denza Apps rear helper", 1000, 500, ownVirtual = true),
                display(9, "shared_fission_bg_XDJAScreenProjection_0", 2560, 720),
            ),
        )

        assertFalse(resolution.matched)
        assertEquals(1440, resolution.videoHeight)
    }

    @Test
    fun narrowsRearSideWhenBothPanelsCarrySideMarkers() {
        val displays = listOf(
            iviDisplay,
            display(5, "rse_left", 1920, 1200),
            display(6, "rse_right", 1920, 1080),
        )

        val left = SimulcastVideoSizeResolver.resolve("screen_rse_l", displays)
        val right = SimulcastVideoSizeResolver.resolve("screen_rse_r", displays)

        assertTrue(left.matched)
        assertEquals(1600, left.videoHeight)
        assertTrue(right.matched)
        assertEquals(1440, right.videoHeight)
    }

    @Test
    fun acceptsSingleSidelessRearPanelForEitherRearReceiver() {
        val displays = listOf(iviDisplay, display(5, "rear_screen", 1920, 1200))

        val resolution = SimulcastVideoSizeResolver.resolve("screen_rse_r", displays)

        assertTrue(resolution.matched)
        assertEquals(1600, resolution.videoHeight)
    }

    @Test
    fun acceptsAmbiguousMatchesWithEqualAspect() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_tv",
            listOf(
                iviDisplay,
                display(5, "rear_screen_a", 1920, 1200),
                display(6, "rear_screen_b", 1920, 1200),
            ),
        )

        assertTrue(resolution.matched)
        assertEquals(1600, resolution.videoHeight)
    }

    @Test
    fun fallsBackOnConflictingAspects() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_tv",
            listOf(
                iviDisplay,
                display(5, "rear_screen_a", 1920, 1200),
                display(6, "rear_screen_b", 1920, 1080),
            ),
        )

        assertFalse(resolution.matched)
        assertEquals(1440, resolution.videoHeight)
    }

    @Test
    fun fitsPortraitDisplayInsideProvenLongSide() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_overhead",
            listOf(iviDisplay, display(5, "overhead_screen", 1200, 1920)),
        )

        assertTrue(resolution.matched)
        assertEquals(1600, resolution.videoWidth)
        assertEquals(2560, resolution.videoHeight)
    }

    @Test
    fun alignsDerivedDimensionForTheEncoder() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_fse",
            listOf(iviDisplay, display(4, "fse_screen", 1024, 600)),
        )

        assertTrue(resolution.matched)
        // Raw 2560*600/1024=1500 aligns to the nearest multiple of 8.
        assertEquals(1504, resolution.videoHeight)
        assertEquals(0, resolution.videoHeight % 8)
    }

    @Test
    fun ignoresDegenerateDisplaySizes() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_rse_l",
            listOf(iviDisplay, display(5, "rse_left", 0, 0)),
        )

        assertFalse(resolution.matched)
        assertEquals(1440, resolution.videoHeight)
    }

    @Test
    fun unknownReceiverFallsBack() {
        val displays = listOf(iviDisplay, display(5, "rear_screen", 1920, 1200))

        assertFalse(SimulcastVideoSizeResolver.resolve("screen_ivi", displays).matched)
        assertFalse(SimulcastVideoSizeResolver.resolve(null, displays).matched)
    }

    @Test
    fun matchedTargetCarriesItsOwnViewportInsteadOfIviViewport() {
        val resolution = SimulcastVideoSizeResolver.resolve(
            "screen_overhead",
            listOf(iviDisplay, display(5, "overhead_screen", 2560, 720)),
        )

        assertTrue(resolution.matched)
        assertEquals(2560, resolution.viewportWidth)
        assertEquals(720, resolution.viewportHeight)
    }
}
