package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.HudGuidanceReading
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.withReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Карта на проекции», the HUD panel's second switch: it moves only with the hints on and settled,
 * it keeps its own value, and the tile's press never touches it.
 */
class HudMapSwitchTest {
    private val hints = mutableListOf<Boolean>()
    private val map = mutableListOf<Boolean>()

    @Test
    fun theSwitchMovesOnlyWithTheHintsOnAndSettled() {
        assertFalse(hudMapSwitchEnabled(DenzaUiState(hudMapEnabled = true), busy = false))
        val on = DenzaUiState(hudGuidance = FeatureReducer.ready(FeatureId.HUD_GUIDANCE, active = false))
        assertTrue(hudMapSwitchEnabled(on, busy = false))
        assertFalse(hudMapSwitchEnabled(on, busy = true))
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

    @Test
    fun theHudSliceLaysTheMapsValueBesideTheHints() {
        val read = DenzaUiState().withReadings(
            listOf(HudGuidanceReading(FeatureReducer.disabled(FeatureId.HUD_GUIDANCE), mapEnabled = true)),
        )

        assertTrue(read.hudMapEnabled)
        assertFalse(read.hudGuidance.desiredEnabled)
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
