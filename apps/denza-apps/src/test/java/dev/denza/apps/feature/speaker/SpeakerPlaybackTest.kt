package dev.denza.apps.feature.speaker

import android.media.session.PlaybackState
import dev.denza.apps.platform.media.FakeMediaSessionSource
import dev.denza.apps.platform.media.FakeSession
import dev.denza.apps.platform.media.MediaSessionSubscriber
import dev.denza.apps.platform.media.MediaTrack
import dev.denza.apps.platform.media.fakeHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the covers hear as playing, from the shared hub: every active session that plays when the
 * list is read, and a session's own PLAYING report while it is in the list. Nothing from a dormant
 * session, nothing from a pause, nothing from a track change, and nothing from a list that changed
 * nothing about the active sessions.
 *
 * Every name heard here is a report written to the car once the service's repeat guard allows it.
 */
class SpeakerPlaybackTest {
    private val source = FakeMediaSessionSource()
    private val hub = fakeHub(source)
    private val heard = ArrayList<String>()
    private val observer = SpeakerMediaSessionObserver(hub) { heard += it }

    private val yandex = FakeSession("yandex", "ru.yandex.music", PlaybackState.STATE_PAUSED)
    private val vk = FakeSession("vk", "com.vk.vkvideo", PlaybackState.STATE_PAUSED)
    private val stock = FakeSession("stock", "com.byd.mediacenter", PlaybackState.STATE_STOPPED)

    @Test
    fun aPlayerAlreadyGoingWhenTheSwitchGoesOnIsHeardAtOnceAndSilenceStaysSilent() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(stock, yandex, vk)

        observer.start()

        assertEquals(listOf("ru.yandex.music"), heard)
    }

    @Test
    fun nothingPlayingIsNothingHeard() {
        source.active = listOf(stock, yandex, vk)

        observer.start()

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun everyListReadThatChangesTheActiveSessionsNamesEveryActivePlayerInThePlatformsOrder() {
        source.active = listOf(yandex, vk)
        observer.start()
        yandex.state = PlaybackState.STATE_PLAYING
        vk.state = PlaybackState.STATE_PLAYING

        source.deliver(vk, yandex, stock)

        assertEquals(listOf("com.vk.vkvideo", "ru.yandex.music"), heard)
    }

    /** As before: a player opening its session in front of one that plays re-reports the latter. */
    @Test
    fun aNewSessionInTheListStillNamesWhatPlays() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex)
        observer.start()
        heard.clear()

        source.deliver(vk, yandex)

        assertEquals(listOf("ru.yandex.music"), heard)
    }

    @Test
    fun aListThatChangesNothingAboutTheActiveSessionsNamesNobody() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        observer.start()
        heard.clear()

        source.deliver(yandex, vk)
        source.deliver(yandex, vk)

        assertEquals(emptyList<String>(), heard)
    }

    /**
     * A paused player in the background dies: the hub reads the list for its death and the firmware
     * pushes the same list again. Neither is a player starting, and neither may write to the car.
     */
    @Test
    fun aDormantSessionDyingReportsNothing() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        observer.start()
        source.deliver(yandex)
        heard.clear()

        vk.destroy()
        source.deliver(yandex)

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aPlayingReportIsHeardOnlyFromAnActiveSession() {
        source.active = listOf(yandex, vk)
        observer.start()

        vk.report(PlaybackState.STATE_PLAYING)
        assertEquals(listOf("com.vk.vkvideo"), heard)

        vk.report(PlaybackState.STATE_PAUSED)
        vk.report(PlaybackState.STATE_BUFFERING)
        assertEquals("a pause and a buffer are not playback", listOf("com.vk.vkvideo"), heard)

        source.deliver(vk)
        heard.clear()
        yandex.report(PlaybackState.STATE_PLAYING)
        assertEquals("a dormant session is not the covers' business", emptyList<String>(), heard)
    }

    @Test
    fun aTrackChangeIsNotPlayback() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex)
        observer.start()
        heard.clear()

        yandex.reportMetadata(MediaTrack("Next", "Band"))

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun anActiveSessionDyingReadsTheListAndNamesWhatStillPlays() {
        vk.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, vk)
        observer.start()
        heard.clear()
        source.active = listOf(vk)

        yandex.destroy()

        assertEquals(listOf("com.vk.vkvideo"), heard)
    }

    /**
     * A wheel press reads the list for itself. Were it a list read for everybody, a player that has
     * been going for a minute would be reported again on every press - a pause included.
     */
    @Test
    fun theWheelKeysReadTellsTheCoversNothing() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex)
        observer.start()
        heard.clear()
        source.active = listOf(vk, yandex)

        hub.refresh()

        assertEquals(emptyList<String>(), heard)
    }

    /** `SpeakerCoverService` restarts the observer when an access repair completes. */
    @Test
    fun aRestartHearsWhatPlaysAgainAsAFreshStartWould() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex)
        observer.start()

        observer.restart()

        assertEquals(listOf("ru.yandex.music", "ru.yandex.music"), heard)
        assertTrue(hub.isListening)
    }

    /**
     * The grant is taken away while the wheel key holds the hub, and the repair gives it back. The
     * observer's restart after the repair must leave the hub listening on the platform again, as the
     * observer's own listener used to be put back - otherwise the covers stay deaf until the process
     * dies.
     */
    @Test
    fun aRestartAfterTheGrantCameBackListensToThePlatformAgain() {
        val key = MediaSessionSubscriber { _, _ -> }
        source.active = listOf(yandex, vk)
        hub.subscribe(key)
        observer.start()

        source.revoke()
        source.refuseAccess = false
        observer.restart()

        assertTrue(hub.isListening)
        assertNotNull(source.listener)
        heard.clear()
        stock.state = PlaybackState.STATE_PLAYING
        source.deliver(stock, yandex, vk)
        assertEquals(listOf("com.byd.mediacenter"), heard)
    }

    @Test
    fun stopLetsGoOfTheHub() {
        source.active = listOf(yandex)
        observer.start()

        observer.stop()
        yandex.report(PlaybackState.STATE_PLAYING)

        assertFalse("the observer was the hub's only subscriber", hub.isListening)
        assertEquals(emptyList<String>(), heard)
    }
}
