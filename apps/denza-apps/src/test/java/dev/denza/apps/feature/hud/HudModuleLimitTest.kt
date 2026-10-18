package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The module's road limit lives as long as the fields it came in, for the car's own sign. */
class HudModuleLimitTest {
    private var now = 0L
    private val limit = HudModuleLimit { now }

    @Test
    fun aPublishedLimitLivesItsLifetimeAndNoLonger() {
        limit.published(60, 1_500L)
        now = 1_499L
        assertEquals(60, limit.current())
        now = 1_500L
        assertNull(limit.current())
    }

    @Test
    fun withdrawingOrPublishingNoneTakesItAway() {
        limit.published(60, 1_500L)
        limit.withdrawn()
        assertNull(limit.current())

        limit.published(90, 1_500L)
        limit.published(null, 1_500L)
        assertNull(limit.current())
    }
}
