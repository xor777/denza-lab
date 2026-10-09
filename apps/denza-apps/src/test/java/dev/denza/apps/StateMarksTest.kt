package dev.denza.apps

import dev.denza.apps.feature.cloud.CloudLinkController
import dev.denza.apps.feature.cloud.CloudLinkRuntime
import dev.denza.apps.feature.defaultapps.DefaultAppsCatalogCache
import dev.denza.apps.feature.hud.HudGuidance
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudManeuver
import dev.denza.apps.feature.simulcast.SimulcastIntegration
import dev.denza.apps.feature.speaker.SpeakerCoverRuntime
import dev.denza.apps.platform.accessibility.AccessibilityRepairSingleFlight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A write the dashboard shows marks its slice, as part of the write.
 *
 * A slice is read again only when it is marked, so a writer that forgets leaves its tile frozen
 * until the next resume - and no test of the publisher or of the readings can see that. These hold
 * the writers that run on the JVM to their marks: each is the field or the call the slice reads,
 * written as the app writes it, with a recorder where the publisher would be. The writers that
 * need Android to run are listed in docs/feature-map.md, "Shared plumbing".
 */
class StateMarksTest {
    private val marks = mutableListOf<Pair<Set<StateSlice>, String>>()

    @Before
    fun record() {
        StateMarks.connect { slices, cause -> synchronized(marks) { marks += slices to cause } }
    }

    @After
    fun disconnect() {
        StateMarks.connect(null)
        SimulcastIntegration.clearLastTargetPackage()
        SpeakerCoverRuntime.reporting = false
        CloudLinkRuntime.busy = false
        HudGuidanceRuntime.onStopped()
    }

    private fun marked(): List<Set<StateSlice>> = synchronized(marks) { marks.map { it.first } }

    @Test
    fun `a share starting and ending marks the projection, and a repeat of either does not`() {
        SimulcastIntegration.setLastTargetPackage("ru.rutube.app")
        SimulcastIntegration.setLastTargetPackage("ru.rutube.app")
        SimulcastIntegration.clearLastTargetPackage()
        SimulcastIntegration.clearLastTargetPackage()

        assertEquals(listOf(setOf(StateSlice.SIMULCAST), setOf(StateSlice.SIMULCAST)), marked())
    }

    @Test
    fun `a speaker report going out and landing marks the speakers`() {
        SpeakerCoverRuntime.reporting = true
        SpeakerCoverRuntime.reporting = true
        SpeakerCoverRuntime.reporting = false

        assertEquals(List(2) { setOf(StateSlice.SPEAKER_COVERS) }, marked())
    }

    @Test
    fun `every pass of the cloud link marks the cloud, and so does a press`() {
        CloudLinkController.publish()
        CloudLinkRuntime.busy = true

        assertEquals(List(2) { setOf(StateSlice.CLOUD_LINK) }, marked())
    }

    @Test
    fun `a package change marks everything that names an installed application`() {
        DefaultAppsCatalogCache.invalidate()

        assertEquals(listOf(StateSlice.PACKAGES), marked())
        assertEquals(
            setOf(
                StateSlice.SIMULCAST,
                StateSlice.HUD_GUIDANCE,
                StateSlice.NAVIGATION,
                StateSlice.SPLIT_SCREEN,
            ),
            StateSlice.PACKAGES,
        )
    }

    @Test
    fun `guidance starting and stopping marks the HUD tile, and its samples do not`() {
        val guidance = HudGuidanceFixtures.sample()
        HudGuidanceRuntime.onGuidance(guidance, capturedAtMs = 1_000L)
        HudGuidanceRuntime.onGuidance(guidance, capturedAtMs = 1_350L)
        HudGuidanceRuntime.onWaiting()

        assertEquals(List(2) { setOf(StateSlice.HUD_GUIDANCE) }, marked())
    }

    @Test
    fun `an accessibility repair marks the wheel row as it starts and before its owners hear`() {
        val repair = AccessibilityRepairSingleFlight()
        val heard = mutableListOf<List<Set<StateSlice>>>()

        val started = repair.join({ heard += marked() }, { true })
        repair.join({}, { true })
        repair.complete(null)

        assertEquals(true, started)
        val navigation = setOf(StateSlice.NAVIGATION)
        assertEquals(listOf(navigation, navigation), marked())
        assertEquals("the owner hears after the settled mark", listOf(listOf(navigation, navigation)), heard)
    }

    /** One guidance sample, as the HUD monitor parses it. */
    private object HudGuidanceFixtures {
        fun sample() = HudGuidance(
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
}
