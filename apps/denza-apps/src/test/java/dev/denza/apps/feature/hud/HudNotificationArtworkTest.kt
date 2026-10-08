package dev.denza.apps.feature.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudNotificationArtworkTest {
    @Test
    fun disabledFlagAlwaysUsesBuiltInArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { false })
        store.update("route", byteArrayOf(1, 2, 3), capturedAtMs = 9_900L)

        assertNull(store.resolve(guidance(), nowMs = 10_000L))
        assertEquals(HudArtworkSource.BUILT_IN, store.diagnostics().source)
        assertEquals("flag-disabled", store.diagnostics().detail)
    }

    @Test
    fun missingArtworkUsesBuiltInArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })

        assertNull(store.resolve(guidance(), nowMs = 10_000L))
        assertEquals(HudArtworkSource.BUILT_IN, store.diagnostics().source)
        assertEquals("no-artwork", store.diagnostics().detail)
    }

    @Test
    fun freshArtworkIsSelected() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        val png = byteArrayOf(4, 5, 6)
        store.update("route", png, capturedAtMs = 10_000L)

        assertArrayEquals(png, store.resolve(guidance(), nowMs = 10_000L))
        assertEquals(HudArtworkSource.NOTIFICATION, store.diagnostics().source)
    }

    @Test
    fun distanceOnlyUpdateKeepsArtworkEligible() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        val png = byteArrayOf(7, 8, 9)
        store.update("route", png, capturedAtMs = 10_000L)
        assertArrayEquals(png, store.resolve(guidance(distanceMeters = 120), nowMs = 10_000L))

        assertArrayEquals(
            png,
            store.resolve(guidance(distanceMeters = 40), nowMs = 30_000L),
        )
    }

    @Test
    fun guidanceObservationAnchorsFreshnessBeforeDelayedHudConnection() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        val png = byteArrayOf(8, 9, 10)
        store.update("route", png, capturedAtMs = 10_000L)

        store.observe(guidance(), nowMs = 10_000L)

        assertArrayEquals(png, store.resolve(guidance(), nowMs = 12_000L))
    }

    @Test
    fun changedManeuverRejectsOlderArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        val png = byteArrayOf(10, 11, 12)
        store.update("route", png, capturedAtMs = 10_000L)
        assertArrayEquals(png, store.resolve(guidance(), nowMs = 10_000L))

        assertNull(
            store.resolve(
                guidance(
                    maneuver = HudManeuver.LEFT,
                    instruction = "Поверните налево",
                ),
                nowMs = 12_000L,
            ),
        )
        assertEquals("stale-artwork", store.diagnostics().detail)
    }

    @Test
    fun artworkCapturedBeforeAManeuverChangeIsNeverDrawnForTheNewManeuver() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        store.update("route", byteArrayOf(20), capturedAtMs = 10_000L)
        assertArrayEquals(byteArrayOf(20), store.resolve(guidance(), nowMs = 10_000L))
        // Yandex reposts the right turn half a second before the left turn shows up.
        store.update("route", byteArrayOf(21), capturedAtMs = 11_500L)

        store.observe(
            guidance(maneuver = HudManeuver.LEFT, instruction = "Поверните налево"),
            nowMs = 12_000L,
        )

        assertNull(
            store.resolve(
                guidance(maneuver = HudManeuver.LEFT, instruction = "Поверните налево"),
                nowMs = 12_000L,
            ),
        )
        assertEquals("stale-artwork", store.diagnostics().detail)
    }

    @Test
    fun artworkFromBeforeTheFirstObservedManeuverWaitsForTheNextPost() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        store.update("route", byteArrayOf(23), capturedAtMs = 9_000L)

        assertNull(store.resolve(guidance(), nowMs = 10_000L))

        store.update("route", byteArrayOf(24), capturedAtMs = 10_400L)
        assertArrayEquals(byteArrayOf(24), store.resolve(guidance(), nowMs = 10_500L))
    }

    @Test
    fun theNotificationThatAnnouncesAManeuverCarriesItsOwnArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        store.observe(guidance(), nowMs = 10_000L)
        val left = guidance(maneuver = HudManeuver.LEFT, instruction = "Поверните налево")

        // In the background the guidance and the artwork come from the same post, and the
        // maneuver is observed at that post's time.
        store.update("route", byteArrayOf(22), capturedAtMs = 12_000L)
        store.observe(left, nowMs = 12_000L)

        assertArrayEquals(byteArrayOf(22), store.resolve(left, nowMs = 12_350L))
    }

    @Test
    fun notificationRemovalClearsArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        store.update("route", byteArrayOf(13), capturedAtMs = 10_000L)
        assertArrayEquals(byteArrayOf(13), store.resolve(guidance(), nowMs = 10_000L))

        store.clear("route", "notification-removed")

        assertNull(store.resolve(guidance(), nowMs = 10_100L))
        assertEquals("no-artwork", store.diagnostics().detail)
        assertEquals("notification-removed", store.diagnostics().lastFailure)
    }

    @Test
    fun transientExtractionFailureKeepsLastCompatibleArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        val png = byteArrayOf(13, 14)
        store.update("route", png, capturedAtMs = 10_000L)
        assertArrayEquals(png, store.resolve(guidance(), nowMs = 10_000L))

        store.reject("route", "no-remote-views")

        assertArrayEquals(png, store.resolve(guidance(), nowMs = 10_100L))
        assertEquals("no-remote-views", store.diagnostics().lastFailure)
    }

    @Test
    fun unknownManeuverNeverUsesNotificationArtwork() {
        val store = HudNotificationArtworkStore(featureEnabled = { true })
        store.update("route", byteArrayOf(14), capturedAtMs = 9_900L)

        assertNull(
            store.resolve(
                guidance(
                    maneuver = HudManeuver.UNKNOWN,
                    instruction = "Неизвестный манёвр",
                ),
                nowMs = 10_000L,
            ),
        )
        assertEquals("unknown-maneuver", store.diagnostics().detail)
    }

    @Test
    fun candidateSelectionPrefersKnownManeuverNameAndFailsClosedOnTies() {
        val candidates = listOf(
            candidate("app_icon", token = "app"),
            candidate("primaryicontinted", token = "primary"),
            candidate("next_turn_icon", token = "turn"),
        )

        assertEquals("primary", HudArtworkCandidatePolicy.select(candidates)?.token)
        assertNull(
            HudArtworkCandidatePolicy.select(
                listOf(
                    candidate("nextmaneuver_left", token = "left"),
                    candidate("nextmaneuver_right", token = "right"),
                ),
            ),
        )
        assertNull(
            HudArtworkCandidatePolicy.select(
                listOf(candidate("album_cover", token = "album")),
            ),
        )
    }

    private fun candidate(
        resourceName: String,
        token: String,
        width: Int = 48,
        height: Int = 48,
        opaqueRectangularBackground: Boolean = false,
    ) = HudArtworkCandidate(
        token = token,
        resourceName = resourceName,
        width = width,
        height = height,
        opaqueRectangularBackground = opaqueRectangularBackground,
    )

    private fun guidance(
        maneuver: HudManeuver = HudManeuver.RIGHT,
        instruction: String = "Поверните направо",
        distanceMeters: Int = 120,
    ) = HudGuidance(
        maneuver = maneuver,
        roundaboutExitNumber = null,
        instruction = instruction,
        nextRoadName = "Профсоюзная улица",
        maneuverDistanceMeters = distanceMeters,
        remainingDistanceMeters = 5_000,
        remainingTimeSeconds = 600,
        remainingTimeText = "10 мин",
        eta = "19:30",
    )
}
