package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The poll loop of `HudGuidanceAccessibilityMonitor`, without the accessibility service. */
class HudRouteFreshnessTest {
    private val store = HudNotificationGuidanceStore()
    private val freshness = HudRouteFreshness(store::resolve)

    @Test
    fun theHeartbeatDoesNotMakeABackgroundRouteYounger() {
        val visible = requireNotNull(freshness.select(visibleGuidance(), previous = null, nowMs = 9_000L))
        assertEquals(9_000L, visible.capturedAtMs)

        // Yandex goes to the background; its notification carries the route from 10 000 on.
        store.post(ROUTE_KEY, routeFields("120 м"), capturedAtMs = 10_000L)
        var now = 10_000L
        while (now <= 10_000L + HUD_LOST_ROUTE_GRACE_MS) {
            val sample = requireNotNull(freshness.select(null, visible.guidance, now)) { "at $now" }
            assertEquals(10_000L, sample.capturedAtMs)
            HudGuidanceRuntime.onGuidance(sample.guidance, sample.capturedAtMs)
            assertFalse(freshness.routeLost(now))
            now += POLL_MS
        }

        // The trip strip stopped trusting the figure four seconds after Yandex posted it, however
        // often it was re-read and re-sent.
        assertNotNull(HudGuidanceRuntime.remaining(13_900L))
        assertNull(HudGuidanceRuntime.remaining(14_001L))

        // Six seconds after the post the route is gone, and the HUD is cleared exactly once.
        assertNull(freshness.select(null, visible.guidance, now))
        assertTrue(freshness.routeLost(now))
        assertFalse(freshness.isHolding)
        assertFalse(freshness.routeLost(now + POLL_MS))
    }

    @Test
    fun anArrivalPostClearsTheHudAtTheGraceFromTheLastRoutePost() {
        val visible = requireNotNull(freshness.select(visibleGuidance(), previous = null, nowMs = 9_000L))
        store.post(ROUTE_KEY, routeFields("50 м"), capturedAtMs = 10_000L)
        assertNotNull(freshness.select(null, visible.guidance, nowMs = 10_100L))

        // Arrived: the route's notification now only says the navigator is running.
        store.post(
            ROUTE_KEY,
            YandexNotificationGuidanceFields(title = "Навигатор запущен"),
            capturedAtMs = 11_000L,
        )

        assertNull(freshness.select(null, visible.guidance, nowMs = 11_100L))
        assertFalse(freshness.routeLost(15_999L))
        assertTrue(freshness.routeLost(10_000L + HUD_LOST_ROUTE_GRACE_MS))
    }

    @Test
    fun visibleGuidanceWinsAndIsAsFreshAsItsRead() {
        store.post(ROUTE_KEY, routeFields("120 м"), capturedAtMs = 10_000L)

        val sample = requireNotNull(freshness.select(visibleGuidance(), previous = null, nowMs = 12_000L))

        assertEquals(400, sample.guidance.maneuverDistanceMeters)
        assertEquals(12_000L, sample.capturedAtMs)
    }

    @Test
    fun aRouteNobodyReconfirmedIsLostAfterTheGrace() {
        freshness.select(visibleGuidance(), previous = null, nowMs = 10_000L)

        assertNull(freshness.select(null, previous = null, nowMs = 15_999L))
        assertFalse(freshness.routeLost(15_999L))
        assertTrue(freshness.routeLost(16_000L))

        freshness.reset()
        assertFalse(freshness.routeLost(30_000L))
    }

    private fun routeFields(title: String) = YandexNotificationGuidanceFields(
        maneuverResourceName = "notification_right_sdl",
        title = title,
    )

    private fun visibleGuidance() = HudGuidance(
        maneuver = HudManeuver.RIGHT,
        roundaboutExitNumber = null,
        instruction = "Поверните направо",
        nextRoadName = "Старая улица",
        maneuverDistanceMeters = 400,
        remainingDistanceMeters = 20_000,
        remainingTimeSeconds = 1_800,
        remainingTimeText = "30 мин",
        eta = "19:54",
    )

    private companion object {
        const val ROUTE_KEY = "0|ru.yandex.yandexnavi|1|null|10123"
        const val POLL_MS = 350L
    }
}
