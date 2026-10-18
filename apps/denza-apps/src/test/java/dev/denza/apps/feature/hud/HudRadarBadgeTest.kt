package dev.denza.apps.feature.hud

import dev.denza.hudlink.HudRadarWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line of HUD Speed's warning beside its tiles. */
class HudRadarBadgeTest {
    private fun warning(title: String, meters: Int = 300, limit: Int = 60, section: Boolean = false, average: Int = -1) =
        HudRadarWarning(1L, true, 1, 1, title, meters, limit, 1, section, average)

    @Test
    fun theLineIsMetresToTenKilometresFromOneOrTheSectionsAverage() {
        assertEquals("350 м", HudRadarBadge.caption(warning("Камера", meters = 348)))
        assertEquals("90 м", HudRadarBadge.caption(warning("Камера", meters = 92)))
        assertEquals("1,0 км", HudRadarBadge.caption(warning("Камера", meters = 996)))
        assertEquals("1,2 км", HudRadarBadge.caption(warning("Камера", meters = 1240)))
        assertEquals("Ø 97", HudRadarBadge.caption(warning("Секционная камера", section = true, average = 97)))
    }

    @Test
    fun onlyASectionsAverageAboveItsLimitIsRed() {
        assertTrue(HudRadarBadge.overLimit(warning("Секционная камера", limit = 90, section = true, average = 97)))
        assertFalse(HudRadarBadge.overLimit(warning("Секционная камера", limit = 90, section = true, average = 85)))
        assertFalse(HudRadarBadge.overLimit(warning("Камера", limit = 60, average = 97)))
    }
}
