package dev.denza.apps.ui.components

import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import org.junit.Assert.assertEquals
import org.junit.Test

/** The board's `apps()` arithmetic: columns at a width, and a row as tall as its tallest name. */
class DenzaAppGridTest {

    @Test
    fun fourInThePanelThreeInTheOneThirdPane() {
        assertEquals(4, appColumns(Sheet.Panel.WIDTH - 2 * Sheet.Panel.PAD_X, compact = false))
        assertEquals(3, appColumns(Head.One.WIDTH - 2 * Sheet.Compact.PAD_X, compact = true))
        assertEquals(1, appColumns(10f, compact = true))
    }

    @Test
    fun aRowGrowsOnlyWhenOneOfItsNamesBreaks() {
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2, 2, 1), rowLines(listOf(1, 1, 1, 1, 1, 2, 1, 1, 1), 4))
        assertEquals(listOf(2, 2, 2, 1, 1), rowLines(listOf(1, 1, 2, 1, 1), 3))
    }

    @Test
    fun aRowNeverHoldsMoreThanTwoLinesOrFewerThanOne() {
        assertEquals(listOf(Sheet.Apps.NAME_LINES, Sheet.Apps.NAME_LINES), rowLines(listOf(5, 1), 4))
        assertEquals(listOf(1), rowLines(listOf(0), 4))
        assertEquals(emptyList<Int>(), rowLines(emptyList(), 4))
    }
}
