package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the analyser's effect is constructed, on a main thread made of a clock and a queue.
 *
 * Until 2026-11-19 the watchdog constructed `Visualizer(0)` every two seconds while the strip was
 * open whether or not `RECORD_AUDIO` was granted, so a fresh install or a grant the ADB heal could
 * not make was an endless main-thread retry; and with the grant a failing construction was retried
 * at the same two seconds forever.
 */
class SpectrumKeeperTest {

    /** A main looper: tasks at times, run in order as the clock passes them. */
    private class Looper {
        var now = 0L
        private val queue = mutableListOf<Pair<Long, Runnable>>()

        fun post(task: Runnable, delayMs: Long) {
            queue += (now + delayMs) to task
        }

        fun remove(task: Runnable) {
            queue.removeAll { it.second === task }
        }

        val pending: Int get() = queue.size

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = queue.filter { it.first <= until }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = next.first
                next.second.run()
            }
            now = until
        }
    }

    private class Effect(var granted: Boolean, var works: Boolean) {
        var attempts = 0
        var attached = false
        val times = mutableListOf<Long>()
    }

    private fun keeper(looper: Looper, effect: Effect) = SpectrumKeeper(
        permitted = { effect.granted },
        attach = {
            effect.attempts++
            effect.times += looper.now
            effect.attached = effect.works
            effect.attached
        },
        release = { effect.attached = false },
        attached = { effect.attached },
        sinceLastFrameMs = { 0L },
        schedule = looper::post,
        unschedule = looper::remove,
    )

    @Test
    fun withoutThePermissionNothingIsConstructedAndNothingIsScheduled() {
        val looper = Looper()
        val effect = Effect(granted = false, works = false)
        val keeper = keeper(looper, effect)
        keeper.start()
        looper.advance(HOUR)
        assertEquals("an hour of the strip open without the grant", 0, effect.attempts)
        assertEquals("and nothing waiting on the main thread", 0, looper.pending)
    }

    @Test
    fun aGrantThatLandsStartsTheCaptureWithoutARestart() {
        val looper = Looper()
        val effect = Effect(granted = false, works = true)
        val keeper = keeper(looper, effect)
        keeper.start()
        looper.advance(30_000L)

        // The ADB heal's result.
        effect.granted = true
        keeper.granted()
        assertTrue("attached the moment the grant landed", effect.attached)
        assertEquals(1, effect.attempts)
        looper.advance(HOUR)
        assertEquals("and a capture that delivers is not built again", 1, effect.attempts)
    }

    @Test
    fun aGrantMadeWhileThePanelWasAwayAttachesOnTheNextStart() {
        val looper = Looper()
        val effect = Effect(granted = false, works = true)
        val keeper = keeper(looper, effect)
        keeper.start()
        keeper.stop()
        effect.granted = true
        keeper.start()
        assertTrue("the panel's resume attaches", effect.attached)
        assertEquals(1, effect.attempts)
    }

    @Test
    fun aFailingConstructionBacksOffToAMinute() {
        val looper = Looper()
        val effect = Effect(granted = true, works = false)
        val keeper = keeper(looper, effect)
        keeper.start()
        looper.advance(10 * 60_000L)
        val gaps = effect.times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 32_000L), gaps.take(4))
        assertTrue("never further apart than a minute: $gaps", gaps.all { it <= SpectrumKeeper.MAX_BACKOFF_MS })
        assertEquals("a minute from the fifth on", SpectrumKeeper.MAX_BACKOFF_MS, gaps.last())
        // 0, 4, 12, 28, 60, then a minute apart to ten minutes: 14, against 300 at two seconds.
        assertEquals(14, effect.attempts)

        // And the first success puts the watchdog back on its own two seconds.
        effect.works = true
        looper.advance(SpectrumKeeper.MAX_BACKOFF_MS)
        assertTrue(effect.attached)
        assertEquals(SpectrumKeeper.WATCHDOG_INTERVAL_MS, keeper.delayMs())
    }

    @Test
    fun theLastOwnerLeavingReleasesAndUnschedules() {
        val looper = Looper()
        val effect = Effect(granted = true, works = true)
        val keeper = keeper(looper, effect)
        keeper.start()
        assertTrue(effect.attached)
        keeper.stop()
        assertFalse(effect.attached)
        assertEquals(0, looper.pending)
        looper.advance(HOUR)
        assertEquals(1, effect.attempts)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
