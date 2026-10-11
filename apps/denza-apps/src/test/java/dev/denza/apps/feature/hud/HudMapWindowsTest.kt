package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Where Yandex is on the main screen, and whether anything lies over the cut. */
class HudMapWindowsTest {
    private val yandexWindow = PxRect(0, 0, 2560, 1600)
    private val statusBar = window(null, type = 3, layer = 30, PxRect(0, 0, 2560, 64))
    private val yandex = window(NAVI, type = 1, layer = 10, yandexWindow)

    @Test
    fun yandexAbsentIsNotOnTheMainScreen() {
        val other = window("com.byd.launcher", type = 1, layer = 5, yandexWindow)

        assertEquals(YandexMapPlacement.NotOnMainScreen, HudMapWindows.place(listOf(statusBar, other), NAVI))
        assertEquals(YandexMapPlacement.NotOnMainScreen, HudMapWindows.place(emptyList(), NAVI))
    }

    @Test
    fun onlyAnApplicationWindowOfYandexCounts() {
        // Yandex's floating widget is a system-type window: not where it draws its map.
        val overlay = window(NAVI, type = 3, layer = 40, PxRect(100, 100, 400, 300))

        assertEquals(YandexMapPlacement.NotOnMainScreen, HudMapWindows.place(listOf(overlay), NAVI))
    }

    @Test
    fun theLargestYandexWindowIsTheMapAndWhatIsLayeredOverItIsListed() {
        val dialog = window(NAVI, type = 1, layer = 12, PxRect(800, 400, 1760, 1200))
        val below = window("com.byd.launcher", type = 1, layer = 2, yandexWindow)
        val elsewhere = window(null, type = 3, layer = 40, PxRect(3000, 0, 3100, 100))

        val placement = HudMapWindows.place(listOf(statusBar, dialog, yandex, below, elsewhere), NAVI)

        assertEquals(YandexMapPlacement.MainScreen(yandexWindow, listOf(statusBar, dialog)), placement)
    }

    @Test
    fun theStatusBarOverTheTopDoesNotHideACutBelowIt() {
        val placement = HudMapWindows.place(listOf(statusBar, yandex), NAVI) as YandexMapPlacement.MainScreen
        val crop = HudMapGeometry.crop(placement.window, 2f, 1280, 1136)

        assertNull(HudMapWindows.coverer(crop, placement.above))
    }

    @Test
    fun aWindowReachingIntoTheCutHidesIt() {
        val camera = window("dev.denza.apps", type = 2, layer = 50, PxRect(1600, 700, 2400, 1300))
        val crop = HudMapGeometry.crop(yandexWindow, 2f, 1280, 1136)

        assertSame(camera, HudMapWindows.coverer(crop, listOf(statusBar, camera)))
    }

    @Test
    fun theSplitHandleOverAPaneEdgeDoesNotHideACutPushedAgainstThatEdge() {
        // The car on 2026-10-11, in the half-size mirror: Yandex in the wide pane, the divider's
        // handle - a system window - 38 px into it. The cursor moved left with the map, and the cut
        // followed it to the pane's edge.
        val pane = PxRect(440, 56, 1268, 736)
        val handle = window(null, type = 3, layer = 3, PxRect(390, 0, 478, 800))
        val placement = HudMapWindows.place(listOf(handle, window(NAVI, type = 1, layer = 2, pane)), NAVI)
            as YandexMapPlacement.MainScreen
        val crop = HudMapGeometry.crop(placement.window, 1f, 460, 600)

        assertEquals(pane.left, crop.left)
        assertEquals(listOf(handle), placement.above)
        assertNull(HudMapWindows.coverer(crop, placement.above))
    }

    @Test
    fun aWindowOverTheMiddleOfTheCutHidesItAndOneInItsMarginDoesNot() {
        val crop = PxRect(1000, 500, 1576, 846)
        // The margin is 15 % a side: 86 px of the width, 51 px of the height.
        val inMargin = window(null, type = 3, layer = 9, PxRect(900, 0, 1086, 1600))
        val overMiddle = window(null, type = 3, layer = 9, PxRect(900, 0, 1088, 1600))
        val shade = window(null, type = 3, layer = 9, PxRect(0, 0, 2560, 700))

        assertNull(HudMapWindows.coverer(crop, listOf(inMargin)))
        assertSame(overMiddle, HudMapWindows.coverer(crop, listOf(overMiddle)))
        assertSame(shade, HudMapWindows.coverer(crop, listOf(shade)))
    }

    @Test
    fun aHiddenCutStillLooksForTheCursorWhenASearchIsDue() {
        assertEquals(HudMapPass.SEARCH_ONLY, HudMapPipeline.pass(covered = true, searching = true))
        assertEquals(HudMapPass.SKIP, HudMapPipeline.pass(covered = true, searching = false))
        assertEquals(HudMapPass.FRAME, HudMapPipeline.pass(covered = false, searching = false))
        assertEquals(HudMapPass.FRAME, HudMapPipeline.pass(covered = false, searching = true))
    }

    private fun window(packageName: String?, type: Int, layer: Int, bounds: PxRect) =
        HudMapWindow(packageName, type, layer, bounds)

    private companion object {
        const val NAVI = "ru.yandex.yandexnavi"
    }
}
