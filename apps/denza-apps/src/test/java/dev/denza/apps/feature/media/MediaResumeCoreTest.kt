package dev.denza.apps.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResumeCoreTest {
    @Test
    fun `playing session remains remembered after pause`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)

        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `with nothing ever played the press goes to stock`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PAUSED)

        core.reconcile(listOf(session))

        val decision = core.perform(MediaResumeCommand.PLAY)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_HISTORY, decision.reason)
        assertEquals(0, session.plays)
    }

    @Test
    fun `current playing session wins over remembered paused session`() {
        val core = core()
        val commands = mutableListOf<String>()
        val remembered = FakeTarget("yandex", MediaResumePlayback.PLAYING, commands)
        val current = FakeTarget("podcast", MediaResumePlayback.TRANSITIONAL, commands)
        core.reconcile(listOf(remembered, current))
        remembered.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        var deferredTarget: MediaResumeTarget? = null
        var deferredPredecessors = emptyList<MediaResumeTarget>()
        assertTrue(
            core.performed(
                MediaResumeCommand.TOGGLE,
                deferPause = { target, predecessors ->
                    deferredTarget = target
                    deferredPredecessors = predecessors
                    true
                },
            ),
        )
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
        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, current.plays)
    }

    @Test
    fun `latest playing callback resolves multiple playing sessions`() {
        val core = core()
        val first = FakeTarget("first", MediaResumePlayback.PLAYING)
        val latest = FakeTarget("latest", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(first, latest))
        core.onPlayback(latest.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.performed(MediaResumeCommand.PAUSE))
        assertEquals(0, first.pauses)
        assertEquals(1, latest.pauses)
    }

    @Test
    fun `an explicit play key on a playing session dispatches nothing`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))

        val decision = core.perform(MediaResumeCommand.PLAY)
        assertTrue(decision.accepted)
        assertEquals(MediaResumeReason.ALREADY_PLAYING, decision.reason)
        assertEquals(0, session.plays)
        assertEquals(0, session.pauses)
    }

    @Test
    fun `an explicit pause key with nothing playing stays with stock`() {
        val core = core(FakeStore("yandex"))
        val session = FakeTarget("yandex", MediaResumePlayback.PAUSED)
        core.reconcile(listOf(session))

        val decision = core.perform(MediaResumeCommand.PAUSE)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.NO_TARGET, decision.reason)
        assertEquals(0, session.plays)
    }

    @Test
    fun `play needs no advertised play action`() {
        val core = core(FakeStore("yandex"))
        val session = FakeTarget("yandex", MediaResumePlayback.PAUSED).apply { canPause = false }
        core.reconcile(listOf(session))

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `a stopped session of the last played package is started again`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.ENDED

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `an ended callback does not erase the last played package`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))

        core.onPlayback(session.identity, MediaResumePlayback.ENDED)
        session.playback = MediaResumePlayback.PAUSED

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `the last played package survives a new core`() {
        val store = FakeStore()
        val first = MediaResumeCore(store)
        val playing = FakeTarget("token-1", MediaResumePlayback.PLAYING, packageName = "yandex")
        first.reconcile(listOf(playing))

        val restarted = MediaResumeCore(store)
        val afterRestart = FakeTarget("token-2", MediaResumePlayback.PAUSED, packageName = "yandex")
        restarted.reconcile(listOf(afterRestart))

        assertTrue(restarted.performed(MediaResumeCommand.PLAY))
        assertEquals(1, afterRestart.plays)
    }

    @Test
    fun `an old record is still honoured because there is no time limit`() {
        val core = core(FakeStore(MediaLastPlayed("yandex", atMillis = 1L)))
        val session = FakeTarget("token", MediaResumePlayback.PAUSED, packageName = "yandex")
        core.reconcile(listOf(session))

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `playing is remembered for any package including the stock player`() {
        val store = FakeStore()
        val core = MediaResumeCore(store)
        val stock = FakeTarget("stock", MediaResumePlayback.PLAYING, packageName = "mediacenter")
        core.reconcile(listOf(stock))
        assertEquals("mediacenter", store.lastPlayed()?.packageName)

        val bluetooth = FakeTarget("bt", MediaResumePlayback.PAUSED, packageName = "bluetooth")
        core.reconcile(listOf(stock, bluetooth))
        core.onPlayback(bluetooth.identity, MediaResumePlayback.PLAYING)
        assertEquals("bluetooth", store.lastPlayed()?.packageName)
    }

    @Test
    fun `several sessions of one package prefer the one the platform still routes to`() {
        val core = core(FakeStore("yandex"))
        val browser = FakeTarget("browser", MediaResumePlayback.PAUSED, packageName = "yandex")
        val player = FakeTarget("player", MediaResumePlayback.PAUSED, packageName = "yandex")
        core.reconcile(listOf(browser, player))
        core.reconcile(listOf(player))

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, player.plays)
        assertEquals(0, browser.plays)
    }

    @Test
    fun `among equal sessions of one package the last one seen playing wins`() {
        val core = core()
        val older = FakeTarget("older", MediaResumePlayback.PLAYING, packageName = "yandex")
        val newer = FakeTarget("newer", MediaResumePlayback.PAUSED, packageName = "yandex")
        core.reconcile(listOf(older, newer))
        core.onPlayback(newer.identity, MediaResumePlayback.PLAYING)
        older.playback = MediaResumePlayback.PAUSED
        newer.playback = MediaResumePlayback.PAUSED

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, newer.plays)
        assertEquals(0, older.plays)
    }

    @Test
    fun `a session that left the active list still answers play`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED
        core.reconcile(emptyList())

        assertTrue(core.performed(MediaResumeCommand.PLAY))
        assertEquals(1, session.plays)
    }

    @Test
    fun `a destroyed session is reconnected by package`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED

        core.remove(session.identity)

        val asked = mutableListOf<String>()
        val decision = core.perform(MediaResumeCommand.PLAY, reconnect = { packageName ->
            asked += packageName
            MediaResumeDecision(true, MediaResumeReason.RECONNECT_STARTED, packageName)
        })
        assertTrue(decision.accepted)
        assertEquals(MediaResumeReason.RECONNECT_STARTED, decision.reason)
        assertEquals(listOf("yandex"), asked)
        assertEquals(0, session.plays)
    }

    @Test
    fun `a live session is never reconnected`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED
        core.reconcile(emptyList())

        val asked = mutableListOf<String>()
        assertTrue(core.performed(MediaResumeCommand.PLAY, reconnect = { packageName ->
            asked += packageName
            MediaResumeDecision(true, MediaResumeReason.RECONNECT_STARTED, packageName)
        }))
        assertEquals(emptyList<String>(), asked)
        assertEquals(1, session.plays)
    }

    @Test
    fun `a live session of another package is never resumed or reconnected instead`() {
        val core = core()
        val gone = FakeTarget("gone", MediaResumePlayback.PLAYING, packageName = "yandex")
        val other = FakeTarget("other", MediaResumePlayback.PLAYING, packageName = "vk")
        core.reconcile(listOf(other))
        core.reconcile(listOf(other, gone))
        other.playback = MediaResumePlayback.PAUSED
        gone.playback = MediaResumePlayback.PAUSED
        core.remove(gone.identity)

        val asked = mutableListOf<String>()
        core.perform(MediaResumeCommand.PLAY, reconnect = { packageName ->
            asked += packageName
            MediaResumeDecision(true, MediaResumeReason.RECONNECT_STARTED, packageName)
        })
        assertEquals(listOf("yandex"), asked)
        assertEquals(0, other.plays)
    }

    @Test
    fun `a refused reconnect leaves the press and invents no fallback`() {
        val core = core()
        val gone = FakeTarget("gone", MediaResumePlayback.PLAYING, packageName = "yandex")
        val other = FakeTarget("other", MediaResumePlayback.PAUSED, packageName = "vk")
        core.reconcile(listOf(gone, other))
        core.remove(gone.identity)

        val decision = core.perform(MediaResumeCommand.PLAY, reconnect = { packageName ->
            MediaResumeDecision(false, MediaResumeReason.NO_BROWSER_SERVICE, packageName)
        })
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.NO_BROWSER_SERVICE, decision.reason)
        assertEquals(0, other.plays)
        assertEquals(0, gone.plays)
    }

    @Test
    fun `a reconnect that throws is a failure and not a fallback`() {
        val core = core()
        val gone = FakeTarget("gone", MediaResumePlayback.PLAYING, packageName = "yandex")
        val other = FakeTarget("other", MediaResumePlayback.PAUSED, packageName = "vk")
        core.reconcile(listOf(gone, other))
        core.remove(gone.identity)

        val decision = core.perform(MediaResumeCommand.PLAY, reconnect = { error("browser gone") })
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.RECONNECT_FAILED, decision.reason)
        assertEquals(0, other.plays)
    }

    @Test
    fun `with nothing ever played no package is reconnected either`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PAUSED)
        core.reconcile(listOf(session))

        val asked = mutableListOf<String>()
        val decision = core.perform(MediaResumeCommand.PLAY, reconnect = { packageName ->
            asked += packageName
            MediaResumeDecision(true, MediaResumeReason.RECONNECT_STARTED, packageName)
        })
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_HISTORY, decision.reason)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `never-playing paused sibling is not touched before current pause`() {
        val core = core()
        val dormant = FakeTarget("dormant", MediaResumePlayback.PAUSED)
        val current = FakeTarget("current", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(dormant, current))

        assertTrue(core.performed(MediaResumeCommand.PAUSE))
        assertEquals(1, current.pauses)
    }

    @Test
    fun `ended previous session is not cancellation target`() {
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        core.onPlayback(previous.identity, MediaResumePlayback.ENDED)
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.performed(MediaResumeCommand.PAUSE))
        assertEquals(1, current.pauses)
    }

    @Test
    fun `rejected pause preparation leaves current session untouched`() {
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        val decision = core.perform(MediaResumeCommand.PAUSE, deferPause = { _, _ -> false })
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.PAUSE_PREPARATION, decision.reason)
        assertEquals(0, current.pauses)
        assertEquals(0, previous.plays)
    }

    @Test
    fun `pause preparation exception leaves both sessions untouched`() {
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertFalse(core.performed(MediaResumeCommand.PAUSE, deferPause = { _, _ -> error("helper failed") }))
        assertEquals(0, previous.plays)
        assertEquals(0, previous.pauses)
        assertEquals(0, current.pauses)
    }

    @Test
    fun `deferred pause ignores a removed or replaced exact target`() {
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.performed(MediaResumeCommand.PAUSE, deferPause = { _, _ -> true }))
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
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        val newer = FakeTarget("newer", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current, newer))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.performed(MediaResumeCommand.PAUSE, deferPause = { _, _ -> true }))
        newer.playback = MediaResumePlayback.PLAYING
        core.onPlayback(newer.identity, MediaResumePlayback.PLAYING)

        assertEquals(DeferredPauseCompletion.STALE, core.completeDeferredPause(current))
        assertEquals(0, current.pauses)
        assertEquals(0, newer.pauses)
    }

    @Test
    fun `deferred pause already completed elsewhere is not repeated`() {
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        assertTrue(core.performed(MediaResumeCommand.PAUSE, deferPause = { _, _ -> true }))
        current.playback = MediaResumePlayback.PAUSED

        assertEquals(
            DeferredPauseCompletion.ALREADY_PAUSED,
            core.completeDeferredPause(current),
        )
        assertEquals(0, current.pauses)
    }

    @Test
    fun `destroyed target and unsupported pause fail open`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))

        session.live = false
        assertFalse(core.performed(MediaResumeCommand.PAUSE))
        session.live = true
        session.canPause = false
        val decision = core.perform(MediaResumeCommand.PAUSE)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.PAUSE_UNSUPPORTED, decision.reason)
        assertEquals(0, session.pauses)
    }

    @Test
    fun `state and transport exceptions fail open`() {
        val core = core()
        val unreadable = FakeTarget("unreadable", MediaResumePlayback.PLAYING).apply {
            throwOnRead = true
        }
        core.reconcile(listOf(unreadable))
        assertFalse(core.performed(MediaResumeCommand.PAUSE))

        val brokenTransport = FakeTarget("broken", MediaResumePlayback.PLAYING).apply {
            throwOnPause = true
        }
        core.reconcile(listOf(brokenTransport))
        val decision = core.perform(MediaResumeCommand.PAUSE)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.PAUSE_TRANSPORT, decision.reason)
    }

    @Test
    fun `a store that throws is not history`() {
        val core = MediaResumeCore(object : MediaLastPlayedStore {
            override fun lastPlayed(): MediaLastPlayed? = error("preferences unavailable")

            override fun remember(packageName: String) = error("preferences unavailable")
        })
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED

        val decision = core.perform(MediaResumeCommand.PLAY)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_HISTORY, decision.reason)
        assertNull(decision.packageName)
    }

    private fun core(store: MediaLastPlayedStore = FakeStore()) = MediaResumeCore(store)

    /** Most cases only care whether the press was ours; the reason has its own assertions. */
    private fun MediaResumeCore.performed(
        command: MediaResumeCommand,
        deferPause: (MediaResumeTarget, List<MediaResumeTarget>) -> Boolean = { _, _ -> false },
        reconnect: (String) -> MediaResumeDecision = {
            MediaResumeDecision(false, MediaResumeReason.NO_BROWSER_SERVICE, it)
        },
    ): Boolean = perform(command, deferPause, reconnect).accepted

    private class FakeStore(private var record: MediaLastPlayed? = null) : MediaLastPlayedStore {
        constructor(packageName: String) : this(MediaLastPlayed(packageName, 1L))

        private var clock = 1L
        val writes = mutableListOf<String>()

        override fun lastPlayed(): MediaLastPlayed? = record

        override fun remember(packageName: String) {
            writes += packageName
            record = MediaLastPlayed(packageName, ++clock)
        }
    }

    private class FakeTarget(
        override val identity: Any,
        var playback: MediaResumePlayback,
        private val commands: MutableList<String>? = null,
        override val packageName: String = identity.toString(),
    ) : MediaResumeTarget {
        var plays = 0
        var pauses = 0
        var throwOnRead = false
        var throwOnPause = false
        var live = true
        var canPause = true

        override fun playback(): MediaResumePlayback {
            if (throwOnRead) error("destroyed")
            return playback
        }

        override fun isLive(): Boolean = live

        override fun canPause(): Boolean = canPause

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
