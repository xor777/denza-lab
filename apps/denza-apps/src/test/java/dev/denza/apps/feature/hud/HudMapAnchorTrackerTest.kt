package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/** Found, else remembered for the same window, else Yandex's usual place - and light smoothing. */
class HudMapAnchorTrackerTest {
    private val mainScreen = PxRect(0, 0, 2560, 1600)
    private val density = 2f

    @Test
    fun neverFoundIsTheDefaultPlace() {
        val tracker = HudMapAnchorTracker()

        assertEquals(HudMapAnchor(1280, 1136, HudMapAnchorSource.DEFAULT), tracker.resolve(mainScreen, density, null, null))
        assertEquals(HudMapAnchor(1280, 1136, HudMapAnchorSource.DEFAULT), tracker.current(mainScreen))
    }

    @Test
    fun aPortraitWindowDefaultsLower() {
        val pane = PxRect(0, 0, 800, 1600)

        assertEquals(HudMapAnchor(400, 1200, HudMapAnchorSource.DEFAULT), HudMapAnchorTracker().current(pane))
    }

    @Test
    fun foundThenLostIsRememberedForTheSameWindow() {
        val tracker = HudMapAnchorTracker()
        tracker.resolve(mainScreen, density, 1300f, 1000f)

        assertEquals(HudMapAnchor(1300, 1000, HudMapAnchorSource.REMEMBERED), tracker.resolve(mainScreen, density, null, null))
        // Between searches the last answer stands, remembered as it was.
        assertEquals(HudMapAnchor(1300, 1000, HudMapAnchorSource.REMEMBERED), tracker.current(mainScreen))
    }

    @Test
    fun anotherWindowForgetsWhatTheFirstOneFound() {
        val tracker = HudMapAnchorTracker()
        tracker.resolve(mainScreen, density, 1300f, 1000f)
        // A one-third pane, taller than wide.
        val pane = PxRect(1700, 0, 2550, 1600)

        assertEquals(HudMapAnchorSource.DEFAULT, tracker.current(pane).source)
        assertEquals(HudMapAnchor(2125, 1200, HudMapAnchorSource.DEFAULT), tracker.resolve(pane, density, null, null))
    }

    @Test
    fun jitterIsIgnoredAModestMoveGoesHalfwayAndALargeOneAtOnce() {
        val tracker = HudMapAnchorTracker()
        tracker.resolve(mainScreen, density, 1300f, 1000f)

        // 2 px at density 2 is 1 dp: under the 3 dp dead band.
        assertEquals(HudMapAnchor(1300, 1000, HudMapAnchorSource.DETECTED), tracker.resolve(mainScreen, density, 1302f, 1000f))
        // 40 px (20 dp): half of it.
        assertEquals(HudMapAnchor(1320, 1000, HudMapAnchorSource.DETECTED), tracker.resolve(mainScreen, density, 1340f, 1000f))
        // 400 px (200 dp): Yandex recentred, taken whole.
        assertEquals(HudMapAnchor(1720, 1000, HudMapAnchorSource.DETECTED), tracker.resolve(mainScreen, density, 1720f, 1000f))
    }

    @Test
    fun resetForgetsEverything() {
        val tracker = HudMapAnchorTracker()
        tracker.resolve(mainScreen, density, 1300f, 1000f)
        tracker.reset()

        assertEquals(HudMapAnchorSource.DEFAULT, tracker.resolve(mainScreen, density, null, null).source)
    }
}
