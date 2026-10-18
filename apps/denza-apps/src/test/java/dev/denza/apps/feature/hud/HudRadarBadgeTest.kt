package dev.denza.apps.feature.hud

import dev.denza.hudlink.HudRadarWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glyph and the line of the radar's badge, from the radar's own names for its warnings. */
class HudRadarBadgeTest {
    private fun warning(title: String, meters: Int = 300, limit: Int = 60, section: Boolean = false, average: Int = -1) =
        HudRadarWarning(1L, true, 1, 1, title, meters, limit, 1, section, average)

    @Test
    fun theRadarsNamesGiveTheGlyph() {
        assertEquals(HudRadarBadge.Kind.CAMERA, HudRadarBadge.kind(warning("Стационарная камера или радар")))
        assertEquals(HudRadarBadge.Kind.CAMERA, HudRadarBadge.kind(warning("Комплекс контроля всех ПДД")))
        assertEquals(HudRadarBadge.Kind.CAMERA, HudRadarBadge.kind(warning("Камера на СТОП-линию или красный свет")))
        assertEquals(HudRadarBadge.Kind.PATROL, HudRadarBadge.kind(warning("Мобильная засада или тренога")))
        assertEquals(HudRadarBadge.Kind.PATROL, HudRadarBadge.kind(warning("Стационарный пост ДПС")))
        assertEquals(HudRadarBadge.Kind.SECTION, HudRadarBadge.kind(warning("Секционная камера: начало участка")))
        assertEquals(HudRadarBadge.Kind.HAZARD, HudRadarBadge.kind(warning("Опасный пешеходный переход")))
        assertEquals(HudRadarBadge.Kind.HAZARD, HudRadarBadge.kind(warning("Внимание, дети!")))
    }

    @Test
    fun aSectionIsASectionWhateverItsName() {
        assertEquals(HudRadarBadge.Kind.SECTION, HudRadarBadge.kind(warning("Стационарная камера", section = true, average = 70)))
    }

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
