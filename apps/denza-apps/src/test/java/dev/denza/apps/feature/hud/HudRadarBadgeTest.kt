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
    fun theNumberIsMetresToTenKilometresFromOneOrTheSectionsAverageItsUnitApart() {
        val camera = warning("Камера", meters = 348)
        assertEquals("350", HudRadarBadge.amount(camera))
        assertEquals("м", HudRadarBadge.unit(camera))
        assertEquals("90", HudRadarBadge.amount(warning("Камера", meters = 92)))
        assertEquals("1,0", HudRadarBadge.amount(warning("Камера", meters = 996)))
        val far = warning("Камера", meters = 1240)
        assertEquals("1,2", HudRadarBadge.amount(far))
        assertEquals("км", HudRadarBadge.unit(far))
        val section = warning("Секционная камера", section = true, average = 97)
        assertEquals("Ø 97", HudRadarBadge.amount(section))
        assertEquals("", HudRadarBadge.unit(section))
    }

    @Test
    fun onlyASectionsAverageAboveItsLimitIsRed() {
        assertTrue(HudRadarBadge.overLimit(warning("Секционная камера", limit = 90, section = true, average = 97)))
        assertFalse(HudRadarBadge.overLimit(warning("Секционная камера", limit = 90, section = true, average = 85)))
        assertFalse(HudRadarBadge.overLimit(warning("Камера", limit = 60, average = 97)))
    }
}
