package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line a panel opens with, held to the `sheet-broken` board: a broken feature says so first,
 * in the car's red, in the words its tile says.
 */
class PanelStatusTest {

    private fun mirrors(snapshot: FeatureSnapshot): String {
        val state = DenzaUiState(mirrors = snapshot)
        val tile = DashboardTiles.of(state).first { it.id == TileId.MIRRORS }
        return panelStatus(tile, DashboardPress.messageOf(TileId.MIRRORS, state))
    }

    /** It used to be suppressed as a repeat of the caption, so a broken panel said nothing. */
    @Test
    fun aBrokenPanelSaysItsTilesCaption() {
        assertEquals(
            "Не переключилось",
            mirrors(FeatureSnapshot(FeatureId.MIRRORS, true, FeatureStatus.ERROR, message = "Не переключилось")),
        )
        assertEquals("Не переключилось", mirrors(FeatureSnapshot(FeatureId.MIRRORS, true, FeatureStatus.ERROR)))
    }

    @Test
    fun aWaitingPanelSaysWhatItWaitsFor() {
        assertEquals(
            "Экран не найден",
            mirrors(FeatureSnapshot(FeatureId.MIRRORS, true, FeatureStatus.NEEDS_ACTION, message = "Экран не найден")),
        )
    }

    /** Progress is the one thing a caption does not carry. */
    @Test
    fun workUnderWaySaysItsProgressAndAHealthyPanelNothing() {
        val opening = DenzaUiState(
            navigation = FeatureSnapshot(
                FeatureId.NAVIGATION,
                false,
                FeatureStatus.STARTING,
                message = "Переношу на приборку",
            ),
        )
        val tile = DashboardTiles.of(opening).first { it.id == TileId.CLUSTER }
        assertEquals("Переношу на приборку", panelStatus(tile, DashboardPress.messageOf(TileId.CLUSTER, opening)))

        assertEquals("", mirrors(FeatureSnapshot(FeatureId.MIRRORS, true, FeatureStatus.READY, message = "ready")))
    }
}
