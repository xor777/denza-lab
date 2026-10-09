package dev.denza.apps.feature.trip

import android.media.session.PlaybackState
import dev.denza.apps.platform.media.FakeMediaSessionSource
import dev.denza.apps.platform.media.FakeSession
import dev.denza.apps.platform.media.MediaTrack
import dev.denza.apps.platform.media.fakeHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip's track line follows one session: the one playing when the list is read, else the
 * first, and between reads only that one.
 */
class NowPlayingSourceTest {
    private val source = FakeMediaSessionSource()
    private val hub = fakeHub(source)
    private val nowPlaying = NowPlayingSource()

    private val yandex = FakeSession("yandex", "ru.yandex.music", PlaybackState.STATE_PAUSED).apply {
        track = MediaTrack("Yesterday", "The Beatles")
    }
    private val bluetooth = FakeSession("bt", "com.byd.mediacenter", PlaybackState.STATE_PAUSED).apply {
        track = MediaTrack("Podcast", "Host")
    }

    @Test
    fun thePlayingSessionIsFollowedWhereverItStandsInTheList() {
        bluetooth.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex, bluetooth)

        nowPlaying.start(hub)

        assertEquals("Podcast", nowPlaying.title)
        assertEquals("Host", nowPlaying.artist)
        assertTrue(nowPlaying.playing)
    }

    @Test
    fun withNothingPlayingTheFirstSessionStillShowsItsTitle() {
        source.active = listOf(yandex, bluetooth)

        nowPlaying.start(hub)

        assertEquals("Yesterday", nowPlaying.title)
        assertFalse(nowPlaying.playing)
        assertTrue(nowPlaying.hasTrack)
    }

    @Test
    fun betweenReadsOfTheListItStaysWithTheSessionItFollows() {
        source.active = listOf(yandex, bluetooth)
        nowPlaying.start(hub)

        bluetooth.report(PlaybackState.STATE_PLAYING)
        assertEquals("another session's report moves nothing", "Yesterday", nowPlaying.title)
        assertFalse(nowPlaying.playing)

        yandex.report(PlaybackState.STATE_PLAYING)
        assertTrue(nowPlaying.playing)

        yandex.reportMetadata(MediaTrack("Help!", "The Beatles"))
        assertEquals("Help!", nowPlaying.title)

        bluetooth.reportMetadata(MediaTrack("Other", "Host"))
        assertEquals("Help!", nowPlaying.title)
    }

    @Test
    fun aReadOfTheListChoosesAgain() {
        source.active = listOf(yandex, bluetooth)
        nowPlaying.start(hub)
        bluetooth.state = PlaybackState.STATE_PLAYING

        source.deliver(bluetooth, yandex)

        assertEquals("Podcast", nowPlaying.title)
        assertTrue(nowPlaying.playing)
    }

    @Test
    fun theFollowedSessionDyingMovesToWhatIsLeft() {
        source.active = listOf(yandex, bluetooth)
        nowPlaying.start(hub)
        source.active = listOf(bluetooth)

        yandex.destroy()

        assertEquals("Podcast", nowPlaying.title)
    }

    @Test
    fun noSessionsIsNoTrack() {
        nowPlaying.start(hub)

        assertNull(nowPlaying.title)
        assertFalse(nowPlaying.hasTrack)
        assertFalse(nowPlaying.playing)
    }

    @Test
    fun withoutAccessThereIsNoTrackAndNothingFails() {
        source.refuseAccess = true
        source.active = listOf(yandex)

        nowPlaying.start(hub)

        assertFalse(nowPlaying.hasTrack)
    }

    @Test
    fun stopForgetsTheTrackAndLetsGoOfTheHub() {
        yandex.state = PlaybackState.STATE_PLAYING
        source.active = listOf(yandex)
        nowPlaying.start(hub)

        nowPlaying.stop()

        assertNull(nowPlaying.title)
        assertNull(nowPlaying.artist)
        assertFalse(nowPlaying.playing)
        assertFalse("the strip was the hub's only subscriber", hub.isListening)
        assertEquals(0, yandex.callbacks.size)

        nowPlaying.start(hub)
        assertEquals("Yesterday", nowPlaying.title)
    }
}
