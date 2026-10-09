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

        assertNull(HudMapWindows.coverer(crop, placement.above, depth = 8))
    }

    @Test
    fun aWindowReachingIntoTheCutHidesIt() {
        val camera = window("dev.denza.apps", type = 2, layer = 50, PxRect(1600, 700, 2400, 1300))
        val crop = HudMapGeometry.crop(yandexWindow, 2f, 1280, 1136)

        assertSame(camera, HudMapWindows.coverer(crop, listOf(statusBar, camera), depth = 8))
    }

    @Test
    fun aDividerGrazingTheEdgeOfAPaneWideCutDoesNotHideIt() {
        val pane = PxRect(1700, 0, 2550, 1600)
        val crop = HudMapGeometry.crop(pane, 2f, 2125, 1136)
        val divider = window(null, type = 5, layer = 20, PxRect(1690, 0, 1706, 1600))

        // 6 px into the cut: under 4 dp at density 2.
        assertNull(HudMapWindows.coverer(crop, listOf(divider), depth = 8))
        assertSame(divider, HudMapWindows.coverer(crop, listOf(divider), depth = 6))
    }

    private fun window(packageName: String?, type: Int, layer: Int, bounds: PxRect) =
        HudMapWindow(packageName, type, layer, bounds)

    private companion object {
        const val NAVI = "ru.yandex.yandexnavi"
    }
}
