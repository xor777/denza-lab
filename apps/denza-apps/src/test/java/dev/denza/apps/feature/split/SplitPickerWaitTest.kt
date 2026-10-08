package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5, 1.5.9: the picker's wait for a selection always ends.
 *
 * The scenario is a main process that died between taking the select and answering it: no answer
 * ever comes. The grid used to stay dimmed under a spinner with every tap refused, forever.
 */
class SplitPickerWaitTest {

    @Test
    fun aSelectionNobodyAnswersEndsAtTheLimitAndTheGridTakesTapsAgain() {
        val wait = SplitPickerWait()

        assertNotNull(wait.begin(MUSIC, nowMs = 0))
        assertEquals(MUSIC, wait.packageName)

        assertFalse(
            "the coordinator may still answer within its own deadline",
            wait.expire(SELECT_BUDGET_MS),
        )
        assertNull("and a tap meanwhile starts nothing", wait.begin(NAVIGATOR, SELECT_BUDGET_MS))
        assertEquals(MUSIC, wait.packageName)

        assertTrue(wait.expire(SplitPickerWait.LIMIT_MS))
        assertNull("the spinner is gone", wait.packageName)
        assertNotNull("and the next tap is taken", wait.begin(NAVIGATOR, SplitPickerWait.LIMIT_MS))
    }

    /** A tap after the limit needs no timer to have fired first: the stale wait ends right there. */
    @Test
    fun aTapAfterTheLimitIsTakenEvenIfTheTimerNeverFired() {
        val wait = SplitPickerWait()
        wait.begin(MUSIC, nowMs = 0)

        assertNotNull(wait.begin(NAVIGATOR, SplitPickerWait.LIMIT_MS + 1))
        assertEquals(NAVIGATOR, wait.packageName)
    }

    @Test
    fun theAnswerEndsTheWait() {
        val wait = SplitPickerWait()
        val id = checkNotNull(wait.begin(MUSIC, nowMs = 0))

        assertTrue(wait.answered(id))
        assertNull(wait.packageName)
        assertFalse("a second answer of the same wait is nothing", wait.answered(id))
        assertFalse("and there is nothing left to expire", wait.expire(SplitPickerWait.LIMIT_MS))
    }

    /** The answer of a wait that already expired may come after all; it must not end a newer one. */
    @Test
    fun aLateAnswerDoesNotEndANewerWait() {
        val wait = SplitPickerWait()
        val first = checkNotNull(wait.begin(MUSIC, nowMs = 0))
        wait.expire(SplitPickerWait.LIMIT_MS)
        val second = checkNotNull(wait.begin(NAVIGATOR, SplitPickerWait.LIMIT_MS))

        assertFalse(wait.answered(first))
        assertEquals("the newer spinner stays", NAVIGATOR, wait.packageName)
        assertTrue(wait.answered(second))
        assertNull(wait.packageName)
    }

    @Test
    fun aCommandThatWasNeverTakenEndsItsWaitAtOnce() {
        val wait = SplitPickerWait()
        val id = checkNotNull(wait.begin(MUSIC, nowMs = 0))

        // Delivery failed: the Activity answers the wait itself.
        assertTrue(wait.answered(id))
        assertNotNull(wait.begin(MUSIC, nowMs = 1))
    }
}
