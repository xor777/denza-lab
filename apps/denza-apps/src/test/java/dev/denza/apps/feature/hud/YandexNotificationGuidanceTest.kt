package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexNotificationGuidanceTest {
    @Test
    fun parsesRichNotificationIntoBackgroundGuidance() {
        val patch = YandexNotificationGuidanceParser.parse(
            YandexNotificationGuidanceFields(
                maneuverResourceName = "notification_right_sdl",
                title = "250 м",
                description = "Профсоюзная улица",
                remainingDistance = "12 км",
                remainingTime = "18 мин",
                arrivalTime = "19:42",
            ),
        )

        val guidance = requireNotNull(patch).mergeWith(visibleGuidance())

        assertEquals(HudManeuver.RIGHT, guidance.maneuver)
        assertEquals(250, guidance.maneuverDistanceMeters)
        assertEquals("Профсоюзная улица", guidance.nextRoadName)
        assertEquals(12_000, guidance.remainingDistanceMeters)
        assertEquals(1_080, guidance.remainingTimeSeconds)
        assertEquals("19:42", guidance.eta)
    }

    @Test
    fun notificationManeuverCanReplaceTheLastVisibleManeuver() {
        val patch = requireNotNull(
            YandexNotificationGuidanceParser.parse(
                YandexNotificationGuidanceFields(
                    maneuverResourceName = "notification_left_sdl",
                    title = "80 м",
                    description = "Ленинский проспект",
                ),
            ),
        )

        val guidance = patch.mergeWith(visibleGuidance())

        assertEquals(HudManeuver.LEFT, guidance.maneuver)
        assertEquals("Поверните налево", guidance.instruction)
        assertEquals(80, guidance.maneuverDistanceMeters)
    }

    @Test
    fun idleNotificationIsNotTreatedAsAnActiveRoute() {
        assertNull(
            YandexNotificationGuidanceParser.parse(
                YandexNotificationGuidanceFields(
                    title = "Навигатор запущен",
                ),
            ),
        )
    }

    @Test
    fun aRouteLessPostOfTheRouteNotificationEndsTheBackgroundRoute() {
        val store = HudNotificationGuidanceStore()
        assertTrue(store.post(ROUTE_KEY, routeFields("120 м"), capturedAtMs = 10_000L))
        assertEquals(
            120,
            store.resolve(visibleGuidance(), nowMs = 10_100L)?.guidance?.maneuverDistanceMeters,
        )

        // Arrived: the same notification now only says the navigator is running.
        assertFalse(
            store.post(
                ROUTE_KEY,
                YandexNotificationGuidanceFields(title = "Навигатор запущен"),
                capturedAtMs = 10_500L,
            ),
        )

        assertNull(store.resolve(visibleGuidance(), nowMs = 10_600L))
    }

    @Test
    fun anUnreadablePostOfTheRouteNotificationEndsTheBackgroundRouteToo() {
        val store = HudNotificationGuidanceStore()
        store.post(ROUTE_KEY, routeFields("120 м"), capturedAtMs = 10_000L)

        // The foreground notification collapses to no RemoteViews: nothing could be read.
        store.post(ROUTE_KEY, fields = null, capturedAtMs = 10_500L)

        assertNull(store.resolve(visibleGuidance(), nowMs = 10_600L))
    }

    @Test
    fun anotherYandexNotificationIsNotAboutTheRoute() {
        val store = HudNotificationGuidanceStore()
        store.post(ROUTE_KEY, routeFields("120 м"), capturedAtMs = 10_000L)

        store.post(OTHER_KEY, fields = null, capturedAtMs = 10_200L)
        store.remove(OTHER_KEY)

        assertEquals(
            120,
            store.resolve(visibleGuidance(), nowMs = 10_300L)?.guidance?.maneuverDistanceMeters,
        )
        store.remove(ROUTE_KEY)
        assertNull(store.resolve(visibleGuidance(), nowMs = 10_400L))
    }

    @Test
    fun aBackgroundRouteAgesFromItsPostNotFromItsReads() {
        val store = HudNotificationGuidanceStore()
        store.post(ROUTE_KEY, routeFields("120 м"), capturedAtMs = 10_000L)

        // Read on every poll, the route keeps the moment Yandex posted it.
        listOf(10_350L, 12_000L, 16_000L).forEach { now ->
            assertEquals(10_000L, store.resolve(visibleGuidance(), now)?.capturedAtMs)
        }
        assertNull(store.resolve(visibleGuidance(), nowMs = 10_000L + HUD_LOST_ROUTE_GRACE_MS + 1))

        // A repost restates it.
        store.post(ROUTE_KEY, routeFields("40 м"), capturedAtMs = 17_000L)
        assertEquals(
            40,
            store.resolve(visibleGuidance(), nowMs = 17_100L)?.guidance?.maneuverDistanceMeters,
        )
    }

    @Test
    fun aBackgroundRouteStandsAloneOnceTheHudHasLostTheVisibleOne() {
        val store = HudNotificationGuidanceStore()
        store.post(
            ROUTE_KEY,
            YandexNotificationGuidanceFields(
                maneuverResourceName = "notification_left_sdl",
                title = "250 м",
                description = "Ленинский проспект",
                remainingDistance = "12 км",
            ),
            capturedAtMs = 10_000L,
        )

        val guidance = requireNotNull(store.resolve(previous = null, nowMs = 10_100L)).guidance

        assertEquals(HudManeuver.LEFT, guidance.maneuver)
        assertEquals(250, guidance.maneuverDistanceMeters)
        assertEquals("Ленинский проспект", guidance.nextRoadName)
        assertEquals(12_000, guidance.remainingDistanceMeters)
        assertEquals("", guidance.eta)
    }

    @Test
    fun aNotificationReadLaterIsAsOldAsItsPost() {
        // A live post is now.
        assertEquals(
            50_000L,
            notificationCapturedAtMs(uptimeNowMs = 50_000L, wallNowMs = 1_000_000L, postTimeMs = 1_000_000L),
        )
        // Re-read when the listener connects, a post from ten seconds ago is ten seconds old.
        assertEquals(
            40_000L,
            notificationCapturedAtMs(uptimeNowMs = 50_000L, wallNowMs = 1_000_000L, postTimeMs = 990_000L),
        )
        // A wall clock that stepped back does not make a post younger than now.
        assertEquals(
            50_000L,
            notificationCapturedAtMs(uptimeNowMs = 50_000L, wallNowMs = 1_000_000L, postTimeMs = 1_005_000L),
        )
    }

    private fun routeFields(title: String) = YandexNotificationGuidanceFields(
        maneuverResourceName = "notification_right_sdl",
        title = title,
    )

    private companion object {
        const val ROUTE_KEY = "0|ru.yandex.yandexnavi|1|null|10123"
        const val OTHER_KEY = "0|ru.yandex.yandexnavi|7|null|10123"
    }

    @Test
    fun aBackgroundRouteNeverRepeatsTheLastSpeedSign() {
        val patch = YandexNotificationGuidanceParser.parse(
            YandexNotificationGuidanceFields(
                maneuverResourceName = "notification_right_sdl",
                title = "250 м",
            ),
        )

        val guidance = requireNotNull(patch).mergeWith(visibleGuidance().copy(speedLimitKmh = 60))

        assertNull(guidance.speedLimitKmh)
    }

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
}
