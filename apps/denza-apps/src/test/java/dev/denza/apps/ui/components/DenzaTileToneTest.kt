package dev.denza.apps.ui.components

import dev.denza.apps.ui.components.DenzaTileTone.ATTENTION
import dev.denza.apps.ui.components.DenzaTileTone.BROKEN
import dev.denza.apps.ui.components.DenzaTileTone.IDLE
import dev.denza.apps.ui.components.DenzaTileTone.LIVE
import dev.denza.apps.ui.components.DenzaTileTone.WORKING
import org.junit.Assert.assertEquals
import org.junit.Test

/** A summary takes the colour of its worst row, and nothing grey can make it louder. */
class DenzaTileToneTest {

    @Test
    fun oneBrokenRowMakesTheSummaryBroken() {
        assertEquals(BROKEN, DenzaTileTone.summary(listOf(ATTENTION, BROKEN)))
        assertEquals(BROKEN, DenzaTileTone.summary(listOf(BROKEN, ATTENTION, LIVE)))
        assertEquals(BROKEN, DenzaTileTone.summary(listOf(BROKEN)))
    }

    @Test
    fun waitingRowsWithNoneBrokenMakeItAmber() {
        assertEquals(ATTENTION, DenzaTileTone.summary(listOf(ATTENTION, ATTENTION)))
        assertEquals(ATTENTION, DenzaTileTone.summary(listOf(LIVE, ATTENTION, WORKING, IDLE)))
    }

    @Test
    fun nothingNeedingAnybodyIsQuiet() {
        assertEquals(IDLE, DenzaTileTone.summary(emptyList()))
        assertEquals(IDLE, DenzaTileTone.summary(listOf(LIVE, IDLE, WORKING)))
    }
}
