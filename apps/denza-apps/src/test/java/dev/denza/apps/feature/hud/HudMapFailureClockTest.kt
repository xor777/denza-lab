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

    @Test
    fun aProducerThatStopsAfterOneGoodFrameEmptiesTheWindowOnceTheRouteMovesOn() {
        // One good frame, then the source delivers nothing while its token, display and Yandex's
        // window all stay; the hints go on reading the route.
        val staleness = HudMapStaleness()
        staleness.progressed("450/9000")
        staleness.framed(10_000)
        staleness.progressed("400/8950")
        staleness.progressed("350/8900")

        assertFalse(staleness.stale(10_000 + HudMapStaleness.STALE_AFTER_MS - 1))
        assertTrue(staleness.stale(10_000 + HudMapStaleness.STALE_AFTER_MS))
        assertTrue(staleness.stale(60_000))
    }

    @Test
    fun aStillScreenAtAStopKeepsItsFrame() {
        val staleness = HudMapStaleness()
        staleness.progressed("450/9000")
        staleness.framed(10_000)
        staleness.progressed("450/9000")
        staleness.progressed("450/9000")

        assertFalse(staleness.stale(120_000))
    }

    @Test
    fun oneChangeAfterTheLastFrameIsNotEnoughAndANewFrameStartsAgain() {
        val staleness = HudMapStaleness()
        staleness.progressed("450/9000")
        staleness.framed(10_000)
        staleness.progressed("400/8950")
        assertFalse(staleness.stale(30_000))

        staleness.progressed("350/8900")
        staleness.framed(31_000)
        assertFalse(staleness.stale(40_000))
        staleness.reset()
        assertFalse(staleness.stale(40_000))
    }
}
