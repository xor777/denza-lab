package dev.denza.apps

import dev.denza.apps.SimulcastDialogVisibilityTracker.Command
import dev.denza.apps.SimulcastDialogVisibilityTracker.Observation
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulcastDialogVisibilityTrackerTest {
    private val tracker = SimulcastDialogVisibilityTracker()

    @Test
    fun `confirmed open hides exit exactly once`() {
        assertEquals(Command.HIDE_EXIT, tracker.observe(Observation.OPEN))
        assertEquals(Command.NONE, tracker.observe(Observation.OPEN))
    }

    @Test
    fun `transient accessibility gap preserves hidden state`() {
        assertEquals(Command.HIDE_EXIT, tracker.observe(Observation.OPEN))
        assertEquals(Command.NONE, tracker.observe(Observation.UNKNOWN))
        assertEquals(Command.NONE, tracker.observe(Observation.OPEN))
    }

    @Test
    fun `confirmed close restores exit exactly once`() {
        assertEquals(Command.HIDE_EXIT, tracker.observe(Observation.OPEN))
        assertEquals(Command.RESTORE_EXIT, tracker.observe(Observation.CLOSED_CONFIRMED))
        assertEquals(Command.NONE, tracker.observe(Observation.CLOSED_CONFIRMED))
    }

    @Test
    fun `absence without a prior dialog does not create an overlay`() {
        assertEquals(Command.NONE, tracker.observe(Observation.UNKNOWN))
        assertEquals(Command.NONE, tracker.observe(Observation.CLOSED_CONFIRMED))
    }
}
