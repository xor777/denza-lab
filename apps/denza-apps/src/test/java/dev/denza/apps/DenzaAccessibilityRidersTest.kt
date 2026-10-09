package dev.denza.apps

import dev.denza.apps.platform.accessibility.RiderDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The riders of the shared accessibility service, held to the order and the routing the service's
 * own body had before it became a host (2026-10-09, `onServiceConnected`, `onAccessibilityEvent`,
 * `onKeyEvent`, `onUnbind`/`onDestroy` of `SimulcastAccessibilityService`).
 */
class DenzaAccessibilityRidersTest {
    private val riders = DenzaAccessibilityRiders.create()

    @Test
    fun `the riders are registered in one fixed order`() {
        assertEquals(
            listOf(
                "wifi-debugging-dialog",
                "speaker-foreground",
                "native-weather",
                "hud-guidance",
                "media-key",
                "steering-wheel-key",
                "simulcast-overlay",
                "runtime-recovery",
            ),
            riders.map { it.name },
        )
    }

    /** Play/Pause had the key first and ★ only what Play/Pause left; no other feature saw a key. */
    @Test
    fun `a key is offered to play pause first, to the star second and to nobody else`() {
        assertEquals(listOf("media-key", "steering-wheel-key"), riders.filter { it.takesKeys }.map { it.name })
    }

    /**
     * Who is handed which event, as the body handed it: the wireless-debugging dialog, the speakers,
     * the weather, HUD guidance and the overlay, in that order. The dialog's and the speakers' own
     * checks stay inside them: the dialog matches SystemUI's exact window, the speakers want a
     * package (an event with none was never passed to them, and is dropped there).
     */
    @Test
    fun `each event reaches the riders the service body gave it to, in the same order`() {
        fun handed(type: Int, packageName: String?): List<String> =
            riders.filter { RiderDispatch.wants(it, type, packageName) }.map { it.name }

        assertEquals(
            listOf("wifi-debugging-dialog", "speaker-foreground", "hud-guidance", "simulcast-overlay"),
            handed(STATE, "ru.yandex.yandexnavi"),
        )
        assertEquals(
            listOf("wifi-debugging-dialog", "speaker-foreground", "native-weather", "hud-guidance", "simulcast-overlay"),
            handed(STATE, "com.byd.weatherdata"),
        )
        assertEquals(listOf("native-weather", "hud-guidance", "simulcast-overlay"), handed(CONTENT, "com.byd.weatherdata"))
        assertEquals(listOf("hud-guidance", "simulcast-overlay"), handed(CONTENT, "ru.yandex.yandexnavi"))
        assertEquals(listOf("hud-guidance", "simulcast-overlay"), handed(WINDOWS, null))
        assertEquals(
            listOf("wifi-debugging-dialog", "speaker-foreground", "hud-guidance", "simulcast-overlay"),
            handed(STATE, null),
        )
        // A type the service's configuration never asks for: the weather and HUD guidance took
        // any type, the overlay only its three.
        assertEquals(listOf("native-weather", "hud-guidance"), handed(CLICKED, "com.byd.weatherdata"))
    }

    @Test
    fun `every service instance gets riders of its own`() {
        val other = DenzaAccessibilityRiders.create()

        riders.zip(other).forEach { (one, two) -> assertNotSame(one.name, one, two) }
    }

    private companion object {
        // AccessibilityEvent's bits, by value: the test runs without Android.
        const val STATE = 0x20
        const val CONTENT = 0x800
        const val WINDOWS = 0x400000
        const val CLICKED = 0x1
    }
}
