package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-2 finding 11: an automatic report on its way to the car after the switch went off.
 *
 * The policy is asked when the trigger arrives, and the send runs later on the service's worker:
 * a first report queued behind another, or the repeat after its 1.2 s pause. Switching off writes
 * the preference at once, but the worker learns of it only when Android destroys the service.
 * Until 2026-11-19 nothing between the two asked the switch again, so the car could be told
 * «playing» after the driver had switched the automation off. The panel's button is not
 * automation and is sent whatever the switch says.
 */
class SpeakerCoverSendTest {

    private class World(var enabled: Boolean = true) {
        val sent = mutableListOf<String>()
        var onPause: () -> Unit = {}

        fun send(trigger: SpeakerCoverTrigger): Result<Unit>? = SpeakerCoverPolicy.send(
            trigger = trigger,
            featureEnabled = { enabled },
            report = { sent += "report" },
            pause = { ms -> sent += "pause $ms"; onPause() },
            repeatAfterMs = 1_200L,
        )
    }

    private val playback = SpeakerCoverTrigger.Playback("ru.yandex.music")

    @Test
    fun playbackIsReportedAndRepeatedOnce() {
        val world = World()
        assertEquals(Result.success(Unit), world.send(playback))
        assertEquals(listOf("report", "pause 1200", "report"), world.sent)
    }

    @Test
    fun switchingOffDuringTheRepeatsPauseSendsNoSecondReport() {
        val world = World()
        world.onPause = { world.enabled = false }

        world.send(playback)

        assertEquals(listOf("report", "pause 1200"), world.sent)
    }

    @Test
    fun aReportStillQueuedWhenTheSwitchWentOffIsNotSent() {
        // Admitted while on; the worker reaches it after the switch went off.
        val world = World(enabled = false)

        assertNull("nothing was said", world.send(playback))
        assertEquals(emptyList<String>(), world.sent)
        assertNull(world.send(SpeakerCoverTrigger.PlayerOpened("ru.yandex.music")))
        assertEquals(emptyList<String>(), world.sent)
    }

    @Test
    fun theRaiseButtonIsSentWithTheSwitchOff() {
        val world = World(enabled = false)

        assertEquals(Result.success(Unit), world.send(SpeakerCoverTrigger.RaisePressed))
        assertEquals(listOf("report"), world.sent)
    }
}
