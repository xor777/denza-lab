package dev.denza.apps.feature.split.pill

import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The view's reading of a description that decides which row it shows (README, "Repair row"; ревью
 * пилюли 2026-11-19, C8 / B3): a transition into one empty pane brings the repair row up, whatever
 * row is open, so the view must tell one empty pane from two.
 */
class SplitPillViewRulesTest {

    private val chip = PillItem.Chip(SplitPair("nav", "music"), "nav", "music", missing = null, live = true, badge = PillBadge.OUTLINE)
    private val repairChip = PillItem.Chip(SplitPair("nav", "music"), "nav", "music", missing = Side.RIGHT, live = false, badge = null)
    private val apps = listOf(PillItem.Single(SplitPane.PRIMARY, "a"), PillItem.DividerMark, PillItem.Single(SplitPane.SECONDARY, "b"))

    private fun description(home: List<PillItem>, vacant: Boolean) =
        SplitPillDescription(dividerX = 434.0, leftPane = SplitPane.PRIMARY, home = home, apps = apps, vacant = vacant, live = null)

    @Test
    fun bothPanesFullIsNoVacancy() {
        assertEquals(0, pillVacancy(null))
        assertEquals(0, pillVacancy(description(listOf(chip, PillItem.Separator, PillItem.Plus), vacant = false)))
    }

    @Test
    fun theRepairRowIsOneEmptyPane() {
        assertEquals(1, pillVacancy(description(listOf(repairChip, PillItem.Separator, PillItem.Single(SplitPane.SECONDARY, "c")), vacant = true)))
        assertEquals("a repair row with nothing to offer", 1, pillVacancy(description(emptyList(), vacant = true)))
        assertEquals("only apps for the empty pane", 1, pillVacancy(description(listOf(PillItem.Single(SplitPane.SECONDARY, "c")), vacant = true)))
    }

    @Test
    fun pairsOrAppsOverEmptyPanesAreTwo() {
        assertEquals(2, pillVacancy(description(listOf(chip.copy(live = false, badge = null), PillItem.Separator, PillItem.Plus), vacant = true)))
        assertEquals(2, pillVacancy(description(apps, vacant = true)))
        assertEquals("the apps row with nothing but its mark", 2, pillVacancy(description(listOf(PillItem.DividerMark), vacant = true)))
    }
}
