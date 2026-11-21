package dev.denza.apps.feature.hud

import dev.denza.hudlink.HudFields
import dev.denza.hudlink.HudImage
import dev.denza.hudlink.HudMode
import dev.denza.hudlink.HudProtocol.Road
import dev.denza.hudlink.HudRect
import dev.denza.hudlink.HudRole
import dev.denza.hudlink.HudScene
import dev.denza.hudlink.HudSink
import dev.denza.hudlink.HudSource
import dev.denza.hudlink.HudWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel's switches between the sources and the host's compositor. */
class HudPanelFilterTest {
    private val scene = HudScene()
    private val sink = object : HudSink {
        override fun layer(source: HudSource, id: String, role: HudRole, rect: HudRect, image: HudImage, opacity: Float, ttlMs: Long) =
            scene.layer(source, id, role, rect, image, opacity, ttlMs, 0)
        override fun removeLayer(source: HudSource, id: String) = scene.removeLayer(source, id)
        override fun fields(source: HudSource, role: HudRole, fields: HudFields, ttlMs: Long) =
            scene.fields(source, role, fields, ttlMs, 0)
        override fun withdraw(source: HudSource, role: HudRole) = scene.withdraw(source, role)
        override fun withdraw(source: HudSource) = scene.withdraw(source)
    }

    private fun png(width: Int, height: Int): HudImage = HudImage.png(byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13,
        'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        0, 0, (width shr 8).toByte(), width.toByte(), 0, 0, (height shr 8).toByte(), height.toByte(), 8, 6, 0, 0, 0,
    ))

    /** A hosted route with HUD Speed: the module's map and block, the radar's warning and bars. */
    private fun publish(panel: HudPanelFilter) {
        fun fields(source: HudSource, role: HudRole, values: HudFields) {
            if (panel.admits(source, role)) sink.fields(source, role, values, 10_000)
        }
        fun layer(source: HudSource, id: String, role: HudRole, rect: HudRect, image: HudImage) {
            if (panel.admits(source, role)) sink.layer(source, id, role, rect, image, 1f, 10_000)
        }
        fields(HudSource.MODULE, HudRole.MANEUVER, HudFields.Builder().number(Road.MANEUVER_METERS, 300).build())
        layer(HudSource.MODULE, "map", HudRole.MAP, HudRect.whole(HudWindow.MAP), png(300, 180))
        fields(HudSource.RADAR, HudRole.LANES, HudFields.Builder().number(Road.LANE_COUNT, 3).build())
        layer(HudSource.RADAR, HudRadarSource.GPS, HudRole.MAP_BADGE, HudRadarSource.gpsRect(), png(26, 26))
    }

    @Test
    fun theMapOffDarkensTheWholeMapWindowTheRadarsBarsIncluded() {
        publish(HudPanelFilter(true, false, true, true, true))
        assertEquals(2, scene.compose(0).layers(HudWindow.MAP).size)

        val mapOff = HudPanelFilter(true, false, false, true, true)
        mapOff.withdrawHeldBack(sink)
        assertTrue(scene.compose(0).layers(HudWindow.MAP).isEmpty())

        // And the next publications do not light it again; the warning in the lane window stays.
        publish(mapOff)
        val composed = scene.compose(0)
        assertEquals(HudMode.NAVIGATION, composed.mode)
        assertTrue(composed.layers(HudWindow.MAP).isEmpty())
        assertEquals(HudSource.RADAR, composed.winner(HudRole.LANES))
        assertFalse(mapOff.admits(HudSource.RADAR, HudRole.MAP_BADGE))
        assertTrue(mapOff.admits(HudSource.RADAR, HudRole.LANES))
    }

    /**
     * A fresh install with YandexHUD: «Повороты» never written reads off, and until 2026-11-21 the
     * host then took away the picture the module had drawn by itself before the host came up.
     */
    @Test
    fun yandexHudThroughTheHostPassesWhateverTheStoredTurnsSay() {
        val fresh = HudPanelFilter(false, true, true, true, true)

        assertTrue(fresh.admits(HudSource.MODULE, HudRole.MANEUVER))
        assertTrue(fresh.admits(HudSource.MODULE, HudRole.MAP))
        assertTrue(fresh.admits(HudSource.MODULE, HudRole.ROAD_LIMIT))
        // HUD Speed no longer stands on the stored switch either.
        assertTrue(fresh.camerasShown())
        assertTrue(fresh.admits(HudSource.RADAR, HudRole.LANES))

        publish(fresh)
        fresh.withdrawHeldBack(sink)
        val composed = scene.compose(0)
        assertEquals(HudMode.NAVIGATION, composed.mode)
        assertEquals(HudSource.MODULE, composed.winner(HudRole.MANEUVER))
        assertEquals(1, composed.layers(HudWindow.MAP).filter { it.source == HudSource.MODULE }.size)
    }

    @Test
    fun withYandexHudTheOtherSwitchesStillHoldBackTheirOwn() {
        assertFalse(HudPanelFilter(false, true, false, true, true).admits(HudSource.MODULE, HudRole.MAP))
        assertFalse(HudPanelFilter(false, true, false, true, true).admits(HudSource.MODULE, HudRole.MAP_MARKS))
        assertTrue(HudPanelFilter(false, true, false, true, true).admits(HudSource.MODULE, HudRole.MANEUVER))
        assertFalse(HudPanelFilter(false, true, true, false, true).admits(HudSource.MODULE, HudRole.ROAD_LIMIT))
        assertFalse(HudPanelFilter(false, true, true, true, false).camerasShown())
        assertFalse(HudPanelFilter(false, true, true, true, false).admits(HudSource.RADAR, HudRole.LANES))
    }

    /** The ordinary Yandex: «Повороты» off holds back the module's role and HUD Speed as before. */
    @Test
    fun withoutYandexHudTheStoredTurnsStillDecide() {
        val off = HudPanelFilter(false, false, true, true, true)

        assertFalse(off.admits(HudSource.MODULE, HudRole.MANEUVER))
        assertFalse(off.camerasShown())
        publish(HudPanelFilter(true, false, true, true, true))
        off.withdrawHeldBack(sink)
        val composed = scene.compose(0)
        assertEquals(null, composed.winner(HudRole.MANEUVER))
        assertTrue(composed.layers(HudWindow.MAP).isEmpty())
    }

    @Test
    fun eachOtherSwitchHoldsBackWhatItAlwaysDid() {
        val all = HudPanelFilter(true, false, true, true, true)
        assertTrue(all.admits(HudSource.RADAR, HudRole.MAP_BADGE))
        assertTrue(all.admits(HudSource.MODULE, HudRole.ROAD_LIMIT))
        assertFalse(HudPanelFilter(false, false, true, true, true).admits(HudSource.MODULE, HudRole.MANEUVER))
        assertFalse(HudPanelFilter(false, false, true, true, true).admits(HudSource.RADAR, HudRole.LANES))
        assertFalse(HudPanelFilter(true, false, true, false, true).admits(HudSource.CAPTURE, HudRole.ROAD_LIMIT))
        assertTrue(HudPanelFilter(true, false, true, true, false).admits(HudSource.MODULE, HudRole.MAP))
        assertFalse(HudPanelFilter(true, false, true, true, false).admits(HudSource.RADAR, HudRole.LANES))
        // The capture keeps its own switches.
        assertTrue(HudPanelFilter(true, false, false, true, true).admits(HudSource.CAPTURE, HudRole.MAP))
    }
}
