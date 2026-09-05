package dev.denza.apps.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResumeCoreTest {
    @Test
    fun `playing session remains remembered after pause`() {
        val core = MediaResumeCore()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)

        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED

        assertTrue(core.perform(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `initial paused session is not guessed as resume target`() {
        val core = MediaResumeCore()
        val session = FakeTarget("yandex", MediaResumePlayback.PAUSED)

        core.reconcile(listOf(session))

        assertFalse(core.perform(MediaResumeCommand.PLAY))
        assertEquals(0, session.plays)
    }

    @Test
    fun `current playing session wins over remembered paused session`() {
        val core = MediaResumeCore()
        val remembered = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        val current = FakeTarget("podcast", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(remembered, current))
        remembered.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING

        assertTrue(core.perform(MediaResumeCommand.TOGGLE))
        assertEquals(0, remembered.plays)
        assertEquals(1, current.pauses)
    }

    @Test
    fun `latest playing callback resolves multiple playing sessions`() {
        val core = MediaResumeCore()
        val first = FakeTarget("first", MediaResumePlayback.PLAYING)
        val latest = FakeTarget("latest", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(first, latest))
        core.onPlayback(latest.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.perform(MediaResumeCommand.PAUSE))
        assertEquals(0, first.pauses)
        assertEquals(1, latest.pauses)
    }

    @Test
    fun `removed remembered session is not commanded`() {
        val core = MediaResumeCore()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED

        core.remove(session.identity)

        assertFalse(core.perform(MediaResumeCommand.PLAY))
    }

    @Test
    fun `stopped remembered session is not resurrected`() {
        val core = MediaResumeCore()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.ENDED

        assertFalse(core.perform(MediaResumeCommand.PLAY))
        session.playback = MediaResumePlayback.PAUSED
        assertFalse(core.perform(MediaResumeCommand.PLAY))
        assertEquals(0, session.plays)
    }

    @Test
    fun `ended callback forgets a session before it later reports paused`() {
        val core = MediaResumeCore()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))

        core.onPlayback(session.identity, MediaResumePlayback.ENDED)
        session.playback = MediaResumePlayback.PAUSED

        assertFalse(core.perform(MediaResumeCommand.PLAY))
    }

    @Test
    fun `destroyed target and unsupported action fail open`() {
        val core = MediaResumeCore()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))

        session.live = false
        assertFalse(core.perform(MediaResumeCommand.PAUSE))
        session.live = true
        session.canPause = false
        assertFalse(core.perform(MediaResumeCommand.PAUSE))
        assertEquals(0, session.pauses)
    }

    @Test
    fun `state and transport exceptions fail open`() {
        val core = MediaResumeCore()
        val unreadable = FakeTarget("unreadable", MediaResumePlayback.PLAYING).apply {
            throwOnRead = true
        }
        core.reconcile(listOf(unreadable))
        assertFalse(core.perform(MediaResumeCommand.PAUSE))

        val brokenTransport = FakeTarget("broken", MediaResumePlayback.PLAYING).apply {
            throwOnPause = true
        }
        core.reconcile(listOf(brokenTransport))
        assertFalse(core.perform(MediaResumeCommand.PAUSE))
    }

    private class FakeTarget(
        override val identity: Any,
        var playback: MediaResumePlayback,
    ) : MediaResumeTarget {
        var plays = 0
        var pauses = 0
        var throwOnRead = false
        var throwOnPause = false
        var live = true
        var canPlay = true
        var canPause = true

        override fun playback(): MediaResumePlayback {
            if (throwOnRead) error("destroyed")
            return playback
        }

        override fun isLive(): Boolean = live

        override fun supports(command: MediaResumeCommand): Boolean = when (command) {
            MediaResumeCommand.PLAY -> canPlay
            MediaResumeCommand.PAUSE -> canPause
            MediaResumeCommand.TOGGLE -> false
        }

        override fun play() {
            plays += 1
        }

        override fun pause() {
            if (throwOnPause) error("transport unavailable")
            pauses += 1
        }
    }
}
