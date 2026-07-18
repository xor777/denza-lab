package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterLayoutTest {
    @Test
    fun mapAlwaysOccupiesTheWholeDisplay() {
        val layout = ClusterLayout(1920, 720, ClusterCameraPosition.CENTER)
        assertEquals(ClusterBounds(0, 0, 1920, 720), layout.mapBounds)
    }

    @Test
    fun cameraUsesOneThirdPlusTwentyPercentAtEachPosition() {
        val left = ClusterLayout(1920, 720, ClusterCameraPosition.LEFT)
        val center = ClusterLayout(1920, 720, ClusterCameraPosition.CENTER)
        val right = ClusterLayout(1920, 720, ClusterCameraPosition.RIGHT)

        assertEquals(768, left.cameraWidth)
        assertEquals(ClusterBounds(0, 0, 768, 720), left.cameraBounds)
        assertEquals(ClusterBounds(576, 0, 1344, 720), center.cameraBounds)
        assertEquals(ClusterBounds(1152, 0, 1920, 720), right.cameraBounds)
    }

    @Test
    fun navigationPlacementsReuseMirrorWidthAndProtectInstrumentData() {
        assertEquals(
            ClusterBounds(0, 0, 2560, 720),
            ClusterMapLayout(2560, 720, ClusterMapPlacement.FULL).surfaceBounds,
        )
        assertEquals(
            ClusterBounds(768, 0, 1791, 720),
            ClusterMapLayout(2560, 720, ClusterMapPlacement.CENTER).surfaceBounds,
        )
        assertEquals(
            ClusterBounds(0, 0, 1023, 609),
            ClusterMapLayout(2560, 720, ClusterMapPlacement.LEFT).surfaceBounds,
        )
        assertEquals(
            ClusterBounds(1537, 100, 2560, 609),
            ClusterMapLayout(2560, 720, ClusterMapPlacement.RIGHT).surfaceBounds,
        )
    }

    @Test
    fun navigationShadeOnlyCoversEdgesThatCanMeetInstrumentData() {
        val full = ClusterMapLayout(2560, 720, ClusterMapPlacement.FULL)
        val center = ClusterMapLayout(2560, 720, ClusterMapPlacement.CENTER)
        val left = ClusterMapLayout(2560, 720, ClusterMapPlacement.LEFT)
        val right = ClusterMapLayout(2560, 720, ClusterMapPlacement.RIGHT)

        assertEquals(false, full.shadeTop)
        assertEquals(true, full.shadeBottom)
        assertEquals(90, full.shadeHeightDp)
        assertEquals(204, full.shadeTopAlpha)
        assertEquals(255, full.shadeBottomAlpha)
        assertEquals(242, full.shadeBottomTopAlpha)
        assertEquals(60, full.shadeBottomFadePx)
        assertEquals(90, full.shadeBottomSolidPx)
        assertEquals(600, full.shadeBottomRevealRadiusPx)
        assertEquals(55, full.shadeBottomRevealHeightPercent)
        assertEquals(120, full.shadeBottomRevealCenterOffsetPx)
        assertEquals(614, full.shadeTopLeftRevealRadiusPx)
        assertEquals(512, full.shadeTopRightRevealRadiusPx)
        assertEquals(272, full.shadeTopRevealHeightPx)
        assertEquals(272, full.shadeCenterTopFadePx)
        assertEquals(85, full.densityScalePercent)
        assertEquals(true, center.shadeTop)
        assertEquals(false, center.shadeBottom)
        assertEquals(130, center.shadeHeightDp)
        assertEquals(250, center.shadeTopAlpha)
        assertEquals(100, center.densityScalePercent)
        assertEquals(false, left.shadeTop)
        assertEquals(false, left.shadeBottom)
        assertEquals(192, left.shadeHeightDp)
        assertEquals(250, left.shadeTopAlpha)
        assertEquals(ClusterShadeCorner.TOP_RIGHT, left.shadeCorner)
        assertEquals(null, right.shadeCorner)
        assertEquals(0, right.shadeHeightDp)
        assertEquals(0, right.shadeTopAlpha)
        assertEquals(85, left.densityScalePercent)
    }
}
