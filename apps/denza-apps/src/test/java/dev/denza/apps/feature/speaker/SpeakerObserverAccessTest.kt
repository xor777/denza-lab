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

    /**
     * The tile's «Нет доступа» press switches the feature on again: a start command to a service
     * already watching. Until 2026-11-19 that start returned at «already watching» and the failed
     * repair was never tried again.
     */
    @Test
    fun `a start of a watching service looks at access again`() {
        val events = mutableListOf<String>()
        val starts = SpeakerWatchStarts(
            beginWatching = { events += "watch" },
            checkAccess = { first -> events += if (first) "access (first)" else "access (again)" },
        )

        starts.onStart() // the service starts; its first repair fails
        starts.onStart() // the tile's retry

        assertEquals(listOf("watch", "access (first)", "access (again)"), events)
    }
}
