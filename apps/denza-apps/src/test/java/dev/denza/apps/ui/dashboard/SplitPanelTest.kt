package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.SplitScreenReading
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.withReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split panel's rules: «Значок на рабочем столе» is the feature's switch, «Пилюля» stands on
 * it the way the HUD panel's rows stand on «Манёвры», and the state carries the pill's own value.
 */
class SplitPanelTest {

    private fun List<SplitPanelRow>.row(id: SplitPanelRow.Id) = first { it.id == id }

    @Test
    fun bothSwitchesMoveWhileTheSplitIsOn() {
        val rows = SplitPanel.of(icon = true, pill = false, busy = false)

        assertEquals(listOf(SplitPanelRow.Id.ICON, SplitPanelRow.Id.PILL), rows.map { it.id })
        assertTrue(rows.all { it.enabled })
        assertFalse(rows.row(SplitPanelRow.Id.PILL).checked)
    }

    @Test
    fun thePillGreysWithTheSplitOffAndKeepsItsValue() {
        listOf(true, false).forEach { pill ->
            val rows = SplitPanel.of(icon = false, pill = pill, busy = false)

            assertTrue(rows.row(SplitPanelRow.Id.ICON).enabled)
            assertFalse("the pill moves with the split off", rows.row(SplitPanelRow.Id.PILL).enabled)
            assertEquals("the pill keeps its value", pill, rows.row(SplitPanelRow.Id.PILL).checked)
        }
    }

    @Test
    fun nothingMovesWhileTheSplitSettles() {
        val rows = SplitPanel.of(icon = true, pill = true, busy = true)

        assertTrue(rows.none { it.enabled })
        assertTrue(rows.all { it.checked })
    }

    @Test
    fun thePillSaysWhatItIsInItsLine() {
        val pill = SplitPanel.of(icon = true, pill = true, busy = false).row(SplitPanelRow.Id.PILL)

        assertEquals("Пилюля", pill.title)
        assertEquals("Сохранённые пары приложений — кружок между панелями", pill.summary)
    }

    @Test
    fun thePanelReadsThePillFromTheSplitsSlice() {
        val split = FeatureReducer.disabled(FeatureId.SPLIT_SCREEN)

        assertTrue("on for a fresh state", DenzaUiState().splitPillEnabled)
        val off = DenzaUiState().withReadings(listOf(SplitScreenReading(split, pillEnabled = false)))
        assertFalse(splitPanelOf(off, busy = false).row(SplitPanelRow.Id.PILL).checked)
        val on = off.withReadings(listOf(SplitScreenReading(split, pillEnabled = true)))
        assertTrue(splitPanelOf(on, busy = false).row(SplitPanelRow.Id.PILL).checked)
    }
}
