package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.cluster.ClusterMapCard.Box
import dev.denza.apps.feature.cluster.ClusterMapCard.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The instruments' right card: found by the stock small map's window on the overlay display, the
 * HUD's map shown over it only while switched on, with a picture and no camera there.
 */
class ClusterMapCardTest {

    @Test
    fun theCardIsTheStockSmallMapsViewWhereItReportsItself() {
        val view = Box(1960, 0, 2560, 720)
        val seen = listOf(
            Seen(ClusterMapCard.STOCK_PACKAGE, null, Box(0, 0, 2560, 720)),
            Seen(ClusterMapCard.STOCK_PACKAGE, ClusterMapCard.MAP_VIEW_ID, view),
        )

        assertEquals(view, ClusterMapCard.find(seen))
    }

    @Test
    fun aStockMapWindowWithoutItsViewStandsAtTheFirmwaresPlace() {
        // The full-size translucent window alone: the layout puts the map 600 px at the right edge.
        assertEquals(
            ClusterMapCard.STOCK_BOX,
            ClusterMapCard.find(listOf(Seen(ClusterMapCard.STOCK_PACKAGE, null, Box(0, 0, 2560, 720)))),
        )
        assertEquals(Box(1960, 0, 2560, 720), ClusterMapCard.STOCK_BOX)
        // An empty view does not count either.
        assertEquals(
            ClusterMapCard.STOCK_BOX,
            ClusterMapCard.find(listOf(Seen(ClusterMapCard.STOCK_PACKAGE, ClusterMapCard.MAP_VIEW_ID, Box(10, 10, 10, 10)))),
        )
    }

    @Test
    fun noStockMapWindowNoCard() {
        assertNull(ClusterMapCard.find(emptyList()))
        // The left camera card on the same display is another app's window.
        assertNull(ClusterMapCard.find(listOf(Seen("com.byd.avc", null, Box(0, 0, 600, 720)))))
        // So is this app's own card window.
        assertNull(ClusterMapCard.find(listOf(Seen("dev.denza.apps", null, Box(0, 0, 2560, 720)))))
    }

    @Test
    fun theMapShowsOnlyWithEverythingInPlaceAndNoCamera() {
        val card = ClusterMapCard.STOCK_BOX
        assertTrue(ClusterMapCard.shows(enabled = true, card = card, cameraOnScreen = false, hasFrame = true))
        assertFalse(ClusterMapCard.shows(enabled = false, card = card, cameraOnScreen = false, hasFrame = true))
        assertFalse(ClusterMapCard.shows(enabled = true, card = null, cameraOnScreen = false, hasFrame = true))
        assertFalse(ClusterMapCard.shows(enabled = true, card = card, cameraOnScreen = true, hasFrame = true))
        // No route on the HUD, no map window: the stock card stays as it is.
        assertFalse(ClusterMapCard.shows(enabled = true, card = card, cameraOnScreen = false, hasFrame = false))
    }

    @Test
    fun theCameraHoldsTheDisplayFromItsStartUntilItHasGone() {
        assertTrue(ClusterMapCard.cameraOnScreen(CameraRuntimePhase.STARTING))
        assertTrue(ClusterMapCard.cameraOnScreen(CameraRuntimePhase.READY))
        assertTrue(ClusterMapCard.cameraOnScreen(CameraRuntimePhase.STOPPING))
        assertFalse(ClusterMapCard.cameraOnScreen(CameraRuntimePhase.IDLE))
        assertFalse(ClusterMapCard.cameraOnScreen(CameraRuntimePhase.FAILED))
    }

    @Test
    fun theHudsPictureFillsTheCardsWidthUncutAndCentred() {
        // The HUD's 300x180 window in the stock 600x720 card: twice its size, in the middle.
        assertEquals(Box(1960, 180, 2560, 540), ClusterMapCard.frameBox(ClusterMapCard.STOCK_BOX, 300, 180))
        // A card lower than the picture keeps all of it: the height decides.
        assertEquals(Box(1985, 0, 2435, 270), ClusterMapCard.frameBox(Box(1910, 0, 2510, 270), 300, 180))
    }
}
