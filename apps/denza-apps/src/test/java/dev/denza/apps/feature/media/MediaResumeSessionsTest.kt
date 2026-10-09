package dev.denza.apps.feature.media

import android.media.session.PlaybackState
import dev.denza.apps.platform.media.FakeMediaSessionSource
import dev.denza.apps.platform.media.FakeSession
import dev.denza.apps.platform.media.MediaSessionSubscriber
import dev.denza.apps.platform.media.fakeHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wheel key's sessions as the shared hub hands them over, with the real policy behind them.
 *
 * `MediaResumeCoreTest` holds the policy to the resume contract over fake targets; this holds the
 * targets to the hub: which sessions become targets, how long they stay, and that a press reads the
 * list and the states as they are. [MediaResumeSessions.press] is the call `MediaResumeController`
 * makes for every DOWN it may take, so these presses are the key's own path.
 */
class MediaResumeSessionsTest {
    private val source = FakeMediaSessionSource()
    private val hub = fakeHub(source)
    private val store = MemoryStore()
    private val core = MediaResumeCore(store)
    private val log = ArrayList<String>()
    private val sessions = MediaResumeSessions(hub, core) { message, _ -> log += message }
    private val commands: List<String>
        get() = log.filter { it.startsWith("direct media command") }

    private val yandex = FakeSession("yandex", "ru.yandex.music", PlaybackState.STATE_PAUSED).apply {
        actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
    }
    private val vk = FakeSession("vk", "com.vk.vkvideo", PlaybackState.STATE_PAUSED).apply {
        actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
    }

    private fun press(command: MediaResumeCommand): MediaResumeDecision = sessions.press(command)

    @Test
    fun aSessionThatLeftTheActiveListIsStillResumedByItsPackage() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        sessions.start()
        assertEquals("ru.yandex.music", store.record?.packageName)

        yandex.report(PlaybackState.STATE_PAUSED)
        source.deliver(vk)

        val decision = press(MediaResumeCommand.TOGGLE)
        assertTrue(decision.accepted)
        assertEquals(MediaResumeReason.PLAY, decision.reason)
        assertEquals(1, yandex.plays)
        assertEquals(listOf("direct media command package=ru.yandex.music command=play"), commands)
    }

    @Test
    fun aDestroyedSessionIsNoTargetAndThePressGoesToTheFirmwareNamingThePackage() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        sessions.start()
        yandex.report(PlaybackState.STATE_PAUSED)
        source.active = listOf(vk)

        yandex.destroy()

        val decision = press(MediaResumeCommand.PLAY)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
        assertEquals("ru.yandex.music", decision.packageName)
        assertEquals(0, yandex.plays)
    }

    @Test
    fun aKeyThatStartsListeningDoesNotInheritSessionsTheHubSawGoDormantBeforeIt() {
        store.remember("ru.yandex.music")
        val speakers = MediaSessionSubscriber { _, _ -> }
        source.active = listOf(yandex, vk)
        hub.subscribe(speakers)
        source.deliver(vk)

        sessions.start()

        val decision = press(MediaResumeCommand.PLAY)
        assertFalse("as a new controller, it knows only the active list", decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
        assertEquals(0, yandex.plays)
    }

    @Test
    fun aSessionNewAtThePressIsAddressedAtOnce() {
        store.remember("ru.yandex.music")
        source.active = listOf(vk)
        sessions.start()
        // The player is back; the platform's listener has not fired yet.
        source.active = listOf(yandex, vk)

        val decision = press(MediaResumeCommand.PLAY)

        assertTrue(decision.accepted)
        assertEquals(1, yandex.plays)
    }

    @Test
    fun thePressReadsTheStateTheSessionHasNowNotItsLastReport() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex)
        sessions.start()
        // Paused with no report reaching us yet.
        yandex.state = PlaybackState.STATE_PAUSED

        val decision = press(MediaResumeCommand.TOGGLE)

        assertEquals(MediaResumeReason.PLAY, decision.reason)
        assertEquals(1, yandex.plays)
        assertEquals(0, yandex.pauses)
    }

    @Test
    fun pauseKeepsItsAdvertisedActionGate() {
        yandex.state = PlaybackState.STATE_PLAYING
        yandex.actions = PlaybackState.ACTION_PLAY
        source.active = listOf(yandex)
        sessions.start()

        val refused = press(MediaResumeCommand.TOGGLE)
        assertFalse(refused.accepted)
        assertEquals(MediaResumeReason.PAUSE_UNSUPPORTED, refused.reason)

        yandex.actions = null
        assertEquals(MediaResumeReason.PAUSE_UNSUPPORTED, press(MediaResumeCommand.TOGGLE).reason)

        yandex.actions = PlaybackState.ACTION_PAUSE
        val accepted = press(MediaResumeCommand.TOGGLE)
        assertTrue(accepted.accepted)
        assertEquals(MediaResumeReason.PAUSE, accepted.reason)
        assertEquals(1, yandex.pauses)
        assertEquals(listOf("direct media command package=ru.yandex.music command=pause"), commands)
    }

    @Test
    fun aPlayingReportIsWhatTheKeyRemembers() {
        source.active = listOf(yandex, vk)
        sessions.start()
        assertEquals(null, store.record)

        vk.report(PlaybackState.STATE_PLAYING)

        assertEquals("com.vk.vkvideo", store.record?.packageName)
    }

    @Test
    fun aDormantSessionsReportCountsToo() {
        source.active = listOf(yandex, vk)
        sessions.start()
        source.deliver(vk)

        yandex.report(PlaybackState.STATE_PLAYING)

        assertEquals("ru.yandex.music", store.record?.packageName)
    }

    /** `MediaResumeController.stop()` and `start()`: our service unbound and bound again. */
    @Test
    fun aStoppedKeyForgetsItsTargetsAndAStartedOneKnowsOnlyTheActiveList() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        sessions.start()
        yandex.report(PlaybackState.STATE_PAUSED)
        source.deliver(vk)

        sessions.stop()
        sessions.start()

        val decision = press(MediaResumeCommand.PLAY)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
        assertEquals("ru.yandex.music", decision.packageName)
    }

    @Test
    fun withoutAccessAPressIsLeftToTheFirmwareUntouched() {
        store.remember("ru.yandex.music")
        source.refuseAccess = true
        source.active = listOf(yandex)
        sessions.start()

        assertFalse(sessions.isListening)
        val decision = press(MediaResumeCommand.PLAY)

        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.SESSION_ACCESS, decision.reason)
        assertEquals(0, yandex.plays)
    }

    @Test
    fun aPressWhoseListCannotBeReadIsLeftToTheFirmwareAndSaysWhy() {
        store.remember("ru.yandex.music")
        source.active = listOf(yandex)
        sessions.start()
        source.failReads = true

        val decision = press(MediaResumeCommand.PLAY)

        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.SESSION_ACCESS, decision.reason)
        assertEquals(0, yandex.plays)
        assertTrue(log.contains("could not validate media sessions"))
    }

    /** `MediaKeyRider.requestRefresh` calls `start()` again once the access repair is done. */
    @Test
    fun startingAgainAfterTheGrantCameBackListensAgain() {
        store.remember("ru.yandex.music")
        source.active = listOf(yandex)
        sessions.start()
        source.revoke()
        assertFalse(sessions.isListening)

        source.refuseAccess = false
        sessions.start()

        assertTrue(sessions.isListening)
        assertEquals(MediaResumeReason.PLAY, press(MediaResumeCommand.PLAY).reason)
        assertEquals(1, yandex.plays)
    }

    @Test
    fun playbackStatesReadAsThePolicysWords() {
        assertEquals(MediaResumePlayback.PLAYING, PlaybackState.STATE_PLAYING.toResumePlayback())
        assertEquals(MediaResumePlayback.PAUSED, PlaybackState.STATE_PAUSED.toResumePlayback())
        listOf(
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR,
            null,
        ).forEach { assertEquals("$it", MediaResumePlayback.ENDED, it.toResumePlayback()) }
        listOf(
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
        ).forEach { assertEquals("$it", MediaResumePlayback.TRANSITIONAL, it.toResumePlayback()) }
    }

    private class MemoryStore : MediaLastPlayedStore {
        var record: MediaLastPlayed? = null

        override fun lastPlayed(): MediaLastPlayed? = record

        override fun remember(packageName: String) {
            record = MediaLastPlayed(packageName)
        }
    }
}
