package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.HudGuidanceReading
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.hud.HudNavigatorInstall
import dev.denza.apps.feature.hud.HudRadarInstall
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.withReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD panel's rules: «Манёвры» is the feature's switch and the rest stand on it; what is
 * installed is said in the lines and lit in the picture; the tile's press moves the hints alone.
 */
class HudPanelTest {
    private val hints = mutableListOf<Boolean>()
    private val map = mutableListOf<Boolean>()

    private fun panel(
        navigator: HudNavigatorInstall = HudNavigatorInstall.YANDEX,
        radar: HudRadarInstall = HudRadarInstall.ADAPTED,
        hints: Boolean = true,
        map: Boolean = true,
        sign: Boolean = true,
        cameras: Boolean = true,
        busy: Boolean = false,
    ) = HudPanel.of(navigator, radar, hints, map, sign, cameras, busy)

    private fun HudPanel.row(id: HudPanelRow.Id) = rows.first { it.id == id }

    @Test
    fun everythingOnLightsTheWholeGlass() {
        val all = panel()

        assertEquals(HudGlass(turn = true, sign = true, map = true, cameras = true), all.glass)
        assertTrue(all.rows.all { it.checked && it.enabled })
    }

    @Test
    fun theRestStandOnTheTurnsAndKeepTheirValues() {
        val off = panel(hints = false)

        assertTrue(off.row(HudPanelRow.Id.TURNS).enabled)
        listOf(HudPanelRow.Id.MAP, HudPanelRow.Id.SIGN, HudPanelRow.Id.CAMERAS).forEach { id ->
            assertFalse("$id moves with the turns off", off.row(id).enabled)
            assertTrue("$id keeps its value", off.row(id).checked)
        }
        assertEquals(HudGlass(turn = false, sign = false, map = false, cameras = false), off.glass)
    }

    @Test
    fun nothingMovesWhileTheFeatureSettles() {
        assertTrue(panel(busy = true).rows.none { it.enabled })
    }

    @Test
    fun eachSwitchDarkensItsOwnPartOfTheGlass() {
        assertFalse(panel(map = false).glass.map)
        assertFalse(panel(sign = false).glass.sign)
        assertFalse(panel(cameras = false).glass.cameras)
    }

    @Test
    fun thePictureAndTheLinesSayItAllWithoutAParagraph() {
        assertEquals("", helpOf(TileId.HUD))
    }

    @Test
    fun theSourceIsSaidInTheLinesNotInRowsOfItsOwn() {
        assertEquals(listOf("Манёвры", "Карта", "Ограничение скорости", "Камеры и опасности"), panel().rows.map { it.title })
        assertEquals("Из Яндекс Навигатора", panel().row(HudPanelRow.Id.TURNS).summary)
        assertEquals("На штатном месте, из Яндекс Навигатора", panel().row(HudPanelRow.Id.SIGN).summary)
        val hosted = panel(navigator = HudNavigatorInstall.YANDEX_HUD)
        assertEquals("Из YandexHUD", hosted.row(HudPanelRow.Id.MAP).summary)
        assertEquals("На штатном месте, из YandexHUD", hosted.row(HudPanelRow.Id.SIGN).summary)
    }

    @Test
    fun aHudSpeedWithoutTheAdapterIsSaidInOrangeAndLightsNothing() {
        val official = panel(radar = HudRadarInstall.OFFICIAL)
        val cameras = official.row(HudPanelRow.Id.CAMERAS)

        assertEquals(DenzaTileTone.ATTENTION, cameras.tone)
        assertFalse(cameras.enabled)
        assertFalse(cameras.checked)
        assertFalse(official.glass.cameras)
        assertFalse(panel(radar = HudRadarInstall.NONE).glass.cameras)
        assertNull(panel(radar = HudRadarInstall.NONE).row(HudPanelRow.Id.CAMERAS).tone)
    }

    @Test
    fun withoutANavigatorOnlyTheSpeedIsLeft() {
        val none = panel(navigator = HudNavigatorInstall.NONE)

        assertEquals(HudGlass(turn = false, sign = false, map = false, cameras = false), none.glass)
        assertTrue(none.rows.none { it.enabled })
    }

    @Test
    fun aYandexHudThatDrawsItselfLeavesTheSignAsTheOneSwitch() {
        val alone = panel(navigator = HudNavigatorInstall.YANDEX_HUD_ALONE, hints = false)

        assertEquals(HudGlass(turn = true, sign = true, map = true, cameras = false), alone.glass)
        assertEquals(listOf(HudPanelRow.Id.SIGN), alone.rows.filter { it.enabled }.map { it.id })
        // The feature off leaves the sign off whatever its stored value; turning it on turns both.
        assertFalse(alone.row(HudPanelRow.Id.SIGN).checked)
        assertEquals(DenzaTileTone.ATTENTION, alone.row(HudPanelRow.Id.CAMERAS).tone)
    }

    @Test
    fun theHudSliceLaysWhatIsInstalledAndTheSwitches() {
        val read = DenzaUiState().withReadings(
            listOf(
                HudGuidanceReading(
                    FeatureReducer.ready(FeatureId.HUD_GUIDANCE, active = false),
                    mapEnabled = true,
                    navigator = HudNavigatorInstall.YANDEX_HUD,
                    radar = HudRadarInstall.ADAPTED,
                    signEnabled = false,
                    camerasEnabled = false,
                ),
            ),
        )

        assertEquals(HudNavigatorInstall.YANDEX_HUD, read.hudNavigator)
        assertEquals(HudRadarInstall.ADAPTED, read.hudRadar)
        assertFalse(read.hudSignEnabled)
        assertFalse(read.hudCamerasEnabled)
        assertTrue(read.hudMapEnabled)
    }

    @Test
    fun theTilesPressMovesTheHintsAndNeverTheMap() {
        press(DenzaUiState(hudMapEnabled = true))
        press(
            DenzaUiState(
                hudGuidance = FeatureSnapshot(FeatureId.HUD_GUIDANCE, true, FeatureStatus.READY),
                hudMapEnabled = true,
            ),
        )

        assertEquals(listOf(true, false), hints)
        assertEquals(emptyList<Boolean>(), map)
    }

    private fun press(state: DenzaUiState) {
        val tile = DashboardTiles.of(state).first { it.id == TileId.HUD }
        DashboardPress.perform(tile, state, actions)
    }

    private val actions = DashboardActions(
        app = object : IdleActions() {
            override val onToggleHudGuidance: (Boolean) -> Unit = { hints += it }
            override val onSetHudMapEnabled: (Boolean) -> Unit = { map += it }
        },
        onChooseApps = {},
        onChooseNavigationApp = {},
        onChooseFseApp = {},
        onOpenClusterPicker = {},
        onOpenService = {},
        onOpenSettings = {},
    )
}
