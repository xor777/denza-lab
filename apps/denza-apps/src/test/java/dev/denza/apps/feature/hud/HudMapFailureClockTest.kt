package dev.denza.apps.feature.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failure now and then keeps the last good frame; failures that last empty the window. */
class HudMapFailureClockTest {

    @Test
    fun failuresEmptyTheWindowOnlyOnceTheyHaveLastedASecond() {
        val clock = HudMapFailureClock()

        assertFalse(clock.failed(10_000))
        assertFalse(clock.failed(10_999))
        assertTrue(clock.failed(11_000))
        assertTrue(clock.failed(11_083))
    }

    @Test
    fun aGoodFrameStartsTheClockAgain() {
        val clock = HudMapFailureClock()
        clock.failed(10_000)
        clock.failed(10_900)

        clock.succeeded()

        assertFalse(clock.failed(11_000))
        assertFalse(clock.failed(11_900))
        assertTrue(clock.failed(12_000))
    }
}
