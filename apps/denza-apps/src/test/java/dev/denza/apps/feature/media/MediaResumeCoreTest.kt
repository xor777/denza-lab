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
        val commands = mutableListOf<String>()
        val remembered = FakeTarget("yandex", MediaResumePlayback.PLAYING, commands)
        val current = FakeTarget("podcast", MediaResumePlayback.TRANSITIONAL, commands)
        core.reconcile(listOf(remembered, current))
        remembered.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        var deferredTarget: MediaResumeTarget? = null
        var deferredPredecessors = emptyList<MediaResumeTarget>()
        assertTrue(core.perform(MediaResumeCommand.TOGGLE) { target, predecessors ->
            deferredTarget = target
            deferredPredecessors = predecessors
            true
        })
        assertEquals(0, remembered.plays)
        assertEquals(current, deferredTarget)
        assertEquals(listOf(remembered), deferredPredecessors)
        assertEquals(0, current.pauses)

        assertEquals(
            DeferredPauseCompletion.DISPATCHED,
            core.completeDeferredPause(current),
        )
        assertEquals(1, current.pauses)
        assertEquals(listOf("podcast:pause"), commands)

        current.playback = MediaResumePlayback.PAUSED
        assertTrue(core.perform(MediaResumeCommand.PLAY))
        assertEquals(1, current.plays)
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
    fun `never-playing paused sibling is not touched before current pause`() {
        val core = MediaResumeCore()
        val dormant = FakeTarget("dormant", MediaResumePlayback.PAUSED)
        val current = FakeTarget("current", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(dormant, current))

        assertTrue(core.perform(MediaResumeCommand.PAUSE))
        assertEquals(1, current.pauses)
    }

    @Test
    fun `ended previous session is not cancellation target`() {
        val core = MediaResumeCore()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        core.onPlayback(previous.identity, MediaResumePlayback.ENDED)
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.perform(MediaResumeCommand.PAUSE))
        assertEquals(1, current.pauses)
    }

    @Test
    fun `rejected pause preparation leaves current session untouched`() {
        val core = MediaResumeCore()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertFalse(core.perform(MediaResumeCommand.PAUSE) { _, _ -> false })
        assertEquals(0, current.pauses)
        assertEquals(0, previous.plays)
    }

    @Test
    fun `pause preparation exception leaves both sessions untouched`() {
        val core = MediaResumeCore()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertFalse(core.perform(MediaResumeCommand.PAUSE) { _, _ -> error("helper failed") })
        assertEquals(0, previous.plays)
        assertEquals(0, previous.pauses)
        assertEquals(0, current.pauses)
    }

    @Test
    fun `deferred pause ignores a removed or replaced exact target`() {
        val core = MediaResumeCore()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.perform(MediaResumeCommand.PAUSE) { _, _ -> true })
        core.reconcile(
            listOf(
                previous,
                FakeTarget("current", MediaResumePlayback.PLAYING),
            ),
        )

        assertEquals(DeferredPauseCompletion.STALE, core.completeDeferredPause(current))
        assertEquals(0, current.pauses)
    }

    @Test
    fun `deferred pause does not pause old target after another session starts`() {
        val core = MediaResumeCore()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        val newer = FakeTarget("newer", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current, newer))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.perform(MediaResumeCommand.PAUSE) { _, _ -> true })
        newer.playback = MediaResumePlayback.PLAYING
        core.onPlayback(newer.identity, MediaResumePlayback.PLAYING)

        assertEquals(DeferredPauseCompletion.STALE, core.completeDeferredPause(current))
        assertEquals(0, current.pauses)
        assertEquals(0, newer.pauses)
    }

    @Test
    fun `deferred pause already completed elsewhere is not repeated`() {
        val core = MediaResumeCore()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.perform(MediaResumeCommand.PAUSE) { _, _ -> true })
        current.playback = MediaResumePlayback.PAUSED

        assertEquals(
            DeferredPauseCompletion.ALREADY_PAUSED,
            core.completeDeferredPause(current),
        )
        assertEquals(0, current.pauses)
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
        private val commands: MutableList<String>? = null,
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
            commands?.add("$identity:play")
        }

        override fun pause() {
            if (throwOnPause) error("transport unavailable")
            pauses += 1
            commands?.add("$identity:pause")
        }
    }
}
