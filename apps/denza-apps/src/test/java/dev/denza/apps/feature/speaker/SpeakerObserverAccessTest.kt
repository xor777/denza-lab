package dev.denza.apps.feature.speaker

import dev.denza.apps.platform.accessibility.AccessibilityHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerObserverAccessTest {
    private fun repairsFor(health: AccessibilityHealth): Int {
        var repairs = 0
        SpeakerObserverAccess.ensure(health) { repairs++ }
        return repairs
    }

    /** The fix of 2026-10-09: on but not bound hears no foreground app, and is repaired. */
    @Test
    fun `an observer that is on but not bound is repaired`() {
        assertEquals(1, repairsFor(AccessibilityHealth(enabled = true, connected = false)))
    }

    @Test
    fun `an observer that is off is repaired, as it always was`() {
        assertEquals(1, repairsFor(AccessibilityHealth(enabled = false, connected = false)))
        assertEquals(1, repairsFor(AccessibilityHealth(enabled = false, connected = true)))
    }

    @Test
    fun `a ready observer is left alone`() {
        assertEquals(0, repairsFor(AccessibilityHealth(enabled = true, connected = true)))
    }
}
