package dev.denza.apps.feature.simulcast

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulcastVideoBoundsResolverTest {
    @Test
    fun centersSixteenByNineFrameInsideSixteenByTenViewport() {
        val bounds = SimulcastVideoBoundsResolver.resolve(
            videoWidth = 2_560,
            videoHeight = 1_600,
            viewportWidth = 2_560,
            viewportHeight = 1_600,
        )

        assertEquals(0, bounds.left)
        assertEquals(80, bounds.top)
        assertEquals(2_560, bounds.right)
        assertEquals(1_520, bounds.bottom)
        assertEquals(2_560, bounds.width)
        assertEquals(1_440, bounds.height)
    }

    @Test
    fun centersInTargetPanelPixelsForRearDisplay() {
        val bounds = SimulcastVideoBoundsResolver.resolve(
            videoWidth = 2_560,
            videoHeight = 1_600,
            viewportWidth = 1_920,
            viewportHeight = 1_200,
        )

        assertEquals(0, bounds.left)
        assertEquals(60, bounds.top)
        assertEquals(1_920, bounds.right)
        assertEquals(1_140, bounds.bottom)
        assertEquals(1_920, bounds.width)
        assertEquals(1_080, bounds.height)
    }

    @Test
    fun leavesSixteenByNineViewportUncropped() {
        val bounds = SimulcastVideoBoundsResolver.resolve(
            videoWidth = 2_560,
            videoHeight = 1_440,
            viewportWidth = 1_920,
            viewportHeight = 1_080,
        )

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(1_920, bounds.right)
        assertEquals(1_080, bounds.bottom)
    }

    @Test
    fun centersNarrowerFrameHorizontallyInsideExtraWideViewport() {
        val bounds = SimulcastVideoBoundsResolver.resolve(
            videoWidth = 2_560,
            videoHeight = 1_440,
            viewportWidth = 2_560,
            viewportHeight = 720,
        )

        assertEquals(640, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(1_920, bounds.right)
        assertEquals(720, bounds.bottom)
    }

    @Test
    fun fallsBackToRequestedVideoBoundsForInvalidViewport() {
        val bounds = SimulcastVideoBoundsResolver.resolve(
            videoWidth = 2_560,
            videoHeight = 1_440,
            viewportWidth = 0,
            viewportHeight = 0,
        )

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(2_560, bounds.right)
        assertEquals(1_440, bounds.bottom)
    }
}
