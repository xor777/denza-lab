package dev.denza.apps.feature.hud

import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet.Glass
import dev.denza.hudlink.HudWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The HUD panel's picture of the glass shows HUD Speed where the host puts it and at the size it
 * paints it: the lane window's picture is `HudRadarPainter`'s, and the GPS bars stand on the map
 * where `HudRadarSource` lays them. `LuminoforSpecContractTest` holds `LuminoforSpec` to
 * `spec.json`; this holds the two to the host, so a move on the glass moves the board with it.
 */
class HudGlassSpecLinkTest {

    @Test
    fun theLanePictureIsTheOneThePainterPaints() {
        assertEquals(HudRadarPainter.LANE_WIDTH.toFloat(), Glass.Warning.WIDTH)
        assertEquals(HudRadarPainter.LANE_HEIGHT.toFloat(), Glass.Warning.HEIGHT)
        assertEquals(HudRadarPainter.LANE_TILE.toFloat(), Glass.Warning.TILE)
        assertEquals(HudRadarPainter.GAP.toFloat(), Glass.Warning.GAP)
        assertEquals(HudRadarPainter.LANE_DIGITS, Glass.Warning.DIGITS)
    }

    @Test
    fun theGpsBarsStandWhereTheHostPutsThem() {
        val bars = HudRadarSource.gpsRect()
        assertEquals(bars.x.toFloat(), Glass.Gps.X)
        assertEquals(bars.y.toFloat(), Glass.Gps.Y)
        assertEquals(bars.width.toFloat(), Glass.Gps.SIZE)
        assertEquals(bars.height.toFloat(), Glass.Gps.SIZE)
        assertEquals(HudRadarPainter.GPS.toFloat(), Glass.Gps.SIZE)
    }

    @Test
    fun theMapInThePictureIsTheHudsMapWindow() {
        // gps is in the window's own pixels, so the window must be the one the host draws into.
        assertEquals(HudWindow.MAP.width.toFloat(), Glass.MAP[2])
        assertEquals(HudWindow.MAP.height.toFloat(), Glass.MAP[3])
    }
}
