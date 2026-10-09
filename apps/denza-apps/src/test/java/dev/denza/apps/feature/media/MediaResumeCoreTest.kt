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

        assertTrue(core.performed(MediaResumeCommand.TOGGLE))
        assertEquals(0, remembered.plays)
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
        val core = core(FakeStore(MediaLastPlayed("yandex")))
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

    /**
     * Every sleep of the car (quickboot) unloads the player, and its session with it. That is the
     * car's stock state, so the press is the firmware's: nothing is played and nothing is consumed.
     */
    @Test
    fun `a destroyed session leaves the press to the firmware and names the package`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))
        session.playback = MediaResumePlayback.PAUSED

        core.remove(session.identity)

        listOf(MediaResumeCommand.PLAY, MediaResumeCommand.TOGGLE).forEach { command ->
            val decision = core.perform(command)
            assertFalse(decision.accepted)
            assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
            assertEquals("yandex", decision.packageName)
        }
        assertEquals(0, session.plays)
    }

    @Test
    fun `a player unloaded while it played leaves the next press to the firmware`() {
        val core = core()
        val session = FakeTarget("yandex", MediaResumePlayback.PLAYING)
        core.reconcile(listOf(session))

        core.remove(session.identity)

        val decision = core.perform(MediaResumeCommand.TOGGLE)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
        assertEquals(0, session.pauses)
        assertEquals(0, session.plays)
    }

    /** Our own process dies with the same sleep; the record alone is not a session to command. */
    @Test
    fun `a record with no session after a restart leaves the press to the firmware`() {
        val core = core(FakeStore("yandex"))

        val decision = core.perform(MediaResumeCommand.TOGGLE)

        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
        assertEquals("yandex", decision.packageName)
    }

    @Test
    fun `a live session of another package is never resumed instead`() {
        val core = core()
        val gone = FakeTarget("gone", MediaResumePlayback.PLAYING, packageName = "yandex")
        val other = FakeTarget("other", MediaResumePlayback.PLAYING, packageName = "vk")
        core.reconcile(listOf(other))
        core.reconcile(listOf(other, gone))
        other.playback = MediaResumePlayback.PAUSED
        gone.playback = MediaResumePlayback.PAUSED
        core.remove(gone.identity)

        val decision = core.perform(MediaResumeCommand.PLAY)
        assertFalse(decision.accepted)
        assertEquals(MediaResumeReason.STOCK_NO_LIVE_SESSION, decision.reason)
        assertEquals("yandex", decision.packageName)
        assertEquals(0, other.plays)
        assertEquals(0, gone.plays)
    }

    /** Once the driver opens the player again, its new session is the last-played package's. */
    @Test
    fun `a player opened again by hand is resumed by package`() {
        val core = core()
        val before = FakeTarget("before", MediaResumePlayback.PLAYING, packageName = "yandex")
        core.reconcile(listOf(before))
        before.playback = MediaResumePlayback.PAUSED
        core.remove(before.identity)
        assertFalse(core.performed(MediaResumeCommand.TOGGLE))

        val after = FakeTarget("after", MediaResumePlayback.PAUSED, packageName = "yandex")
        core.reconcile(listOf(after))

        val decision = core.perform(MediaResumeCommand.TOGGLE)
        assertTrue(decision.accepted)
        assertEquals(MediaResumeReason.PLAY, decision.reason)
        assertEquals(1, after.plays)
        assertEquals(0, before.plays)
    }

    /** What `SimulcastAccessibilityService.onKeyEvent` gets back for both halves of that press. */
    @Test
    fun `the wheel key for an unloaded player is released untouched, down and up`() {
        val core = core(FakeStore("yandex"))
        val interceptor = MediaResumeKeyInterceptor()
        val perform: (MediaResumeCommand) -> Boolean = { core.perform(it).accepted }

        assertFalse(interceptor.onKeyEvent(386, 0, 0, true, perform))
        assertFalse(interceptor.onKeyEvent(386, 1, 0, true, perform))
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

    /**
     * The rule since 2026-09-18: a pause is a pause. A session paused over a predecessor that once
     * played gets the ordinary pause, exactly as it would with no predecessor at all. The
     * predecessor is not commanded; whether it resumes by itself is the platform's business.
     */
    @Test
    fun `a pause with a paused predecessor is an ordinary pause`() {
        val core = core()
        val previous = FakeTarget("previous", MediaResumePlayback.PLAYING)
        val current = FakeTarget("current", MediaResumePlayback.TRANSITIONAL)
        core.reconcile(listOf(previous, current))
        previous.playback = MediaResumePlayback.PAUSED
        current.playback = MediaResumePlayback.PLAYING
        core.onPlayback(current.identity, MediaResumePlayback.PLAYING)

        val decision = core.perform(MediaResumeCommand.PAUSE)
        assertTrue(decision.accepted)
        assertEquals(MediaResumeReason.PAUSE, decision.reason)
        assertEquals(1, current.pauses)
        assertEquals(0, previous.plays)
        assertEquals(0, previous.pauses)
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
    private fun MediaResumeCore.performed(command: MediaResumeCommand): Boolean =
        perform(command).accepted

    private class FakeStore(private var record: MediaLastPlayed? = null) : MediaLastPlayedStore {
        constructor(packageName: String) : this(MediaLastPlayed(packageName))

        val writes = mutableListOf<String>()

        override fun lastPlayed(): MediaLastPlayed? = record

        override fun remember(packageName: String) {
            writes += packageName
            record = MediaLastPlayed(packageName)
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
