package dev.denza.apps.feature.adb

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdbAccessCore] against a car made of a few variables: whether adbd answers, whether the key is
 * trusted, the switch, and the moments the driver does something - all on one clock, on the test's
 * own thread. Every rule of who may offer the key, when, and how a request ends is one test here.
 */
class AdbAccessCoreTest {
    private enum class Wake { NONE, APPROVED, EOF }

    /** [meanwhile] events also happen while nothing waits on a transport - during a sleep. */
    private class Event(val at: Long, val meanwhile: Boolean = false, val run: (FakeRequest?) -> Wake)

    private inner class FakeRequest(val alreadyTrustedNow: Boolean) : AdbHeldRequest {
        var closed = false
        override val alreadyTrusted: Boolean get() = alreadyTrustedNow

        override fun awaitApproval(waitMs: Int): Boolean {
            val end = car.elapsed + waitMs
            while (true) {
                if (closed) throw IOException("closed")
                val next = car.events.filter { it.at <= end }.minByOrNull { it.at }
                if (next == null) {
                    car.elapsed = end
                    return false
                }
                car.events.remove(next)
                car.elapsed = maxOf(car.elapsed, next.at)
                when (next.run(this)) {
                    Wake.APPROVED -> return true
                    Wake.EOF -> throw EOFException("adbd closed it")
                    Wake.NONE -> Unit
                }
            }
        }

        override fun close() {
            closed = true
        }
    }

    /** Requests the car holds open at once, at most; one is the rule. */
    private var maxOpen = 0

    private inner class FakeCar : AdbAccessSystem {
        var elapsed = 0L
        var wallBase = 1_000_000_000L
        var portOpen = true
        var switch = AdbSystemSwitch.ENABLED
        var trustedAt: Long? = null
        val events = mutableListOf<Event>()
        val requests = mutableListOf<FakeRequest>()
        var looks = 0
        var trustRecorded = 0
        var requestFails: Exception? = null

        /** A look's own failure: its read timed out, whatever the key's state. */
        var looksTimeOut = false

        /** What happens on another thread while the handshake runs, before the key is written. */
        var duringHandshake: (() -> Unit)? = null

        /** Public keys the car received: a queue entry each. */
        var keysSent = 0

        val trusted: Boolean get() = trustedAt?.let { elapsed >= it } == true

        /** How long a look takes, by when it starts: the car's answer is read as it starts. */
        var lookMs: (Long) -> Long = { 20L }

        /** The next this many looks time out, whatever the key's state. */
        var failLooks = 0

        override fun passiveCheck(): AdbCheckResult {
            looks++
            val failing = looksTimeOut || failLooks > 0
            if (failLooks > 0) failLooks--
            val result = when {
                failing -> AdbCheckResult(AdbCheckOutcome.ERROR, "SocketTimeoutException")
                !portOpen -> AdbCheckResult(AdbCheckOutcome.UNAVAILABLE, "ConnectException", definite = true)
                trusted -> AdbCheckResult(AdbCheckOutcome.TRUSTED)
                else -> AdbCheckResult(AdbCheckOutcome.AUTHORIZATION_REQUIRED)
            }
            elapsed += lookMs(elapsed)
            return result
        }

        override fun openRequest(cancel: AdbRequestCancel): AdbHeldRequest {
            requestFails?.let { throw it }
            if (!portOpen) throw AdbKeyNotSentException(ConnectException("refused"))
            var socketClosed = false
            cancel.onCancel { socketClosed = true }
            duringHandshake?.invoke()
            // LocalAdbClient checks the cancel under the lock it writes the key with.
            if (cancel.isCancelled || socketClosed) {
                throw AdbKeyNotSentException(IOException("cancelled before the key was written"))
            }
            if (!trusted) keysSent++
            return FakeRequest(trusted).also {
                requests += it
                maxOpen = maxOf(maxOpen, requests.count { r -> !r.closed })
            }
        }

        override fun requestFailure(error: Throwable): AdbCheckResult = AdbCheckResult(
            if (error is ConnectException) AdbCheckOutcome.UNAVAILABLE else AdbCheckOutcome.ERROR,
            error.javaClass.simpleName,
        )

        override fun systemSwitch(): AdbSystemSwitch = switch
        /** Wall readings to hand out before the clock's own: a clock that jumps between two of them. */
        val wallScript = ArrayDeque<Long>()

        override fun wallMs(): Long = wallScript.removeFirstOrNull() ?: (wallBase + elapsed)
        override fun elapsedMs(): Long = elapsed
        override fun sleep(ms: Long) {
            elapsed += ms
            while (true) {
                val due = events.filter { it.meanwhile && it.at <= elapsed }.minByOrNull { it.at } ?: return
                events.remove(due)
                due.run(null)
            }
        }
        override fun recordTrusted() {
            trustRecorded++
        }

        /** At [at], the driver answers the request on the screen; [tick] is «always allow». */
        fun approve(at: Long, tick: Boolean, persistLagMs: Long = 0) {
            events += Event(at) {
                if (tick) trustedAt = elapsed + persistLagMs
                Wake.APPROVED
            }
        }

        /** At [at], something happens that the request does not hear: an earlier prompt approved, the switch. */
        fun meanwhile(at: Long, action: () -> Unit) {
            events += Event(at, meanwhile = true) {
                action()
                Wake.NONE
            }
        }
    }

    private class MemoryStore : AdbAskStore {
        var log = AdbAskLog()
        var failSave = false
        override fun load(): AdbAskLog = log
        override fun save(log: AdbAskLog): Boolean {
            if (failSave) return false
            this.log = log
            return true
        }
    }

    private val car = FakeCar()
    private val store = MemoryStore()
    private val published = mutableListOf<AdbRescueSnapshot>()
    private lateinit var core: AdbAccessCore

    /** The idle looks the core armed: when each is due, on the car's clock. */
    private val timers = mutableListOf<Pair<Long, Runnable>>()
    private val timer: (Long, Runnable) -> Unit = { delayMs, task -> timers += (car.elapsed + delayMs) to task }

    private fun core(trustedBefore: Boolean = false): AdbAccessCore {
        core = AdbAccessCore(car, store, { it.run() }, timer, trustedBefore) { published += core.current }
        return core
    }

    /**
     * [ms] pass with the worker idle: the armed looks fire and the car's own events happen, in the
     * order they are due. A look that leads to a request runs it to its end, past [ms] if it must.
     */
    private fun idle(ms: Long) {
        val end = car.elapsed + ms
        while (true) {
            val nextTimer = timers.filter { it.first <= end }.minByOrNull { it.first }
            val nextEvent = car.events.filter { it.meanwhile && it.at <= end }.minByOrNull { it.at }
            if (nextTimer == null && nextEvent == null) {
                car.elapsed = maxOf(car.elapsed, end)
                return
            }
            if (nextEvent != null && (nextTimer == null || nextEvent.at <= nextTimer.first)) {
                car.events.remove(nextEvent)
                car.elapsed = maxOf(car.elapsed, nextEvent.at)
                nextEvent.run(null)
            } else {
                timers.remove(nextTimer!!)
                car.elapsed = maxOf(car.elapsed, nextTimer.first)
                nextTimer.second.run()
            }
        }
    }

    /** The phases the listener heard, each change once. */
    private val steps: List<AdbRescuePhase>
        get() = published.map { it.phase }.fold(emptyList()) { acc, phase -> if (acc.lastOrNull() == phase) acc else acc + phase }

    @Test
    fun `an opening on a refused key asks once and the approval with the tick ends the gate by itself`() {
        core()
        car.approve(at = 5_000, tick = true, persistLagMs = 300)

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertTrue(car.requests.single().closed)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue(core.current.trustedBefore)
        assertEquals(1, car.trustRecorded)
        assertEquals(1, store.log.total)
        // The look found the refusal and the request followed it in one change: never the idle gate.
        assertEquals(listOf(AdbRescuePhase.CHECKING, AdbRescuePhase.AWAITING_CONFIRMATION, AdbRescuePhase.TRUSTED), steps)
    }

    @Test
    fun `a look started before the opening hands its refusal to the opening's request`() {
        core()
        // The process starts with nobody at the screen; the look is passive.
        car.approve(at = 10_000, tick = true)
        core.check()
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals(0, car.requests.size)

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    @Test
    fun `background work never offers the key`() {
        core()
        repeat(5) { core.check() }
        core.foregroundStarted()
        core.foregroundStopped(changingConfigurations = false)
        repeat(5) { core.check() }

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue(core.current.canAsk)
    }

    /** The invisible-prompt case: nothing ever answers. The gate ends idle with the one control. */
    @Test
    fun `a request nobody answers ends after two minutes in a state with a control, and is not resent`() {
        core()
        core.foregroundStarted()
        core.check()

        assertEquals(1, car.requests.size)
        assertTrue(car.requests.single().closed)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue(core.current.canAsk)
        assertEquals(AdbRescuePolicy.NO_ANSWER, core.current.lastFailure)
        assertTrue(car.elapsed in AdbRescuePolicy.HOLD_MS until AdbRescuePolicy.HOLD_MS + 1_000)
        // Held while waiting: a passive look about every three seconds, no second request.
        assertTrue(car.looks in 35..45)
        // More looks in the same opening never ask again.
        repeat(3) { core.check() }
        assertEquals(1, car.requests.size)
        // The gate's control does.
        core.ask()
        assertEquals(2, car.requests.size)
    }

    @Test
    fun `each opening asks once, three openings a day, and the control past them`() {
        core()
        repeat(3) {
            core.foregroundStarted()
            core.foregroundStopped(changingConfigurations = false)
        }
        // Each opening asked and timed out on its own worker run (the test's worker is synchronous).
        assertEquals(3, car.requests.size)
        assertEquals(3, store.log.times.size)

        core.foregroundStarted()
        assertEquals("the fourth opening of the day sends nothing by itself", 3, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue(core.current.canAsk)

        core.ask()
        assertEquals(4, car.requests.size)
        assertEquals(4, store.log.total)

        // A day later the openings ask again.
        core.foregroundStopped(changingConfigurations = false)
        car.wallBase += AdbRescuePolicy.DAY_MS
        core.foregroundStarted()
        assertEquals(5, car.requests.size)
    }

    @Test
    fun `the cap survives the process`() {
        val now = car.wallMs()
        store.log = AdbAskLog(listOf(now - 3_000_000, now - 2_000_000, now - 1_000_000), total = 9, lastAtMs = now - 1_000_000)
        core()
        assertEquals(3, core.requestsLastDay())
        assertEquals(9, core.current.attemptCount)

        core.foregroundStarted()
        core.check()
        assertEquals(0, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
    }

    /**
     * The day's three spent and an earlier opening's prompt approved with the box while the app was
     * away: the next opening may not ask, so it looks, and the gate leaves without a press.
     */
    @Test
    fun `an opening that may not ask looks, and finds a key an earlier prompt trusted`() {
        val now = car.wallMs()
        store.log = AdbAskLog(listOf(now - 3_000, now - 2_000, now - 1_000), total = 3, lastAtMs = now - 1_000)
        core()
        core.check()
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        car.trustedAt = car.elapsed
        val looks = car.looks

        core.foregroundStarted()

        assertEquals(0, car.requests.size)
        assertEquals(looks + 1, car.looks)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    /** A rotation, a recreation: the opening and its request carry over, and nothing is sent twice. */
    @Test
    fun `a configuration change neither closes the request nor sends a second one`() {
        core()
        car.meanwhile(at = 4_000) {
            core.foregroundStopped(changingConfigurations = true)
            core.foregroundStarted()
            core.check()
            core.ask()
        }
        car.approve(at = 8_000, tick = true)

        core.foregroundStarted()
        core.check()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    @Test
    fun `leaving the app closes the request, and coming back is an opening that asks again`() {
        core()
        car.meanwhile(at = 7_000) { core.foregroundStopped(changingConfigurations = false) }

        core.foregroundStarted()
        core.check()

        assertEquals(1, car.requests.size)
        assertTrue(car.requests.single().closed)
        assertTrue("closed when the app left, not at the timeout", car.elapsed < 10_000)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertNull(core.current.lastFailure)

        car.approve(at = car.elapsed + 3_000, tick = true)
        core.foregroundStarted()
        assertEquals(2, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    @Test
    fun `leaving and coming back while the request is still unwinding asks for the new opening`() {
        core()
        car.meanwhile(at = 7_000) {
            core.foregroundStopped(changingConfigurations = false)
            core.foregroundStarted()
        }
        car.approve(at = 12_000, tick = true)

        core.foregroundStarted()
        core.check()

        assertEquals(2, car.requests.size)
        assertTrue(car.requests[0].closed)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    @Test
    fun `a switch turned off closes the request`() {
        core()
        car.meanwhile(at = 5_000) { car.switch = AdbSystemSwitch.DISABLED }

        core.foregroundStarted()
        core.check()

        assertEquals(1, car.requests.size)
        assertTrue(car.requests.single().closed)
        assertTrue(car.elapsed < 10_000)
        assertEquals(AdbRescuePhase.UNAVAILABLE, core.current.phase)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, core.current.details)
    }

    @Test
    fun `the switch is read again before sending, and an off one sends nothing`() {
        core()
        core.check()
        car.switch = AdbSystemSwitch.DISABLED

        core.foregroundStarted()

        assertEquals(0, car.requests.size)
        assertEquals(0, store.log.total)
        assertEquals(AdbRescuePhase.UNAVAILABLE, core.current.phase)
    }

    /** The driver pressed «Разрешить» without the box: that connection got in, the key did not. */
    @Test
    fun `an approval without the tick shows the box and sends the next request at once`() {
        core()
        car.approve(at = 5_000, tick = false)
        car.approve(at = 20_000, tick = true)

        core.foregroundStarted()
        core.check()

        assertEquals(2, car.requests.size)
        // One at a time: the first was closed before the second was opened.
        assertTrue(car.requests.all { it.closed })
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertFalse(core.current.alwaysAllowMissed)
        // While the second waited, the gate was the box.
        val waitingWithBox = published.filter { it.phase == AdbRescuePhase.AWAITING_CONFIRMATION && it.alwaysAllowMissed }
        assertTrue(waitingWithBox.isNotEmpty())
        assertEquals(2, store.log.total)
    }

    /** The framework stores the key a moment after the answer; one refused look is not a missed tick. */
    @Test
    fun `an approval with the tick stored a moment late is not taken for a missing tick`() {
        core()
        car.approve(at = 5_000, tick = true, persistLagMs = 500)

        core.foregroundStarted()
        core.check()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertFalse(published.any { it.alwaysAllowMissed })
    }

    @Test
    fun `an approval without the tick as the app leaves sends nothing until the next opening`() {
        core()
        car.approve(at = 5_000, tick = false)
        // During the looks after the approval.
        car.meanwhile(at = 5_500) { core.foregroundStopped(changingConfigurations = false) }

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue(core.current.alwaysAllowMissed)

        car.approve(at = car.elapsed + 2_000, tick = true)
        core.foregroundStarted()
        assertEquals(2, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    /**
     * A prompt from an earlier opening may still be queued: its request was closed, its entry was
     * not. Approved with the tick, it trusts the key with no transport to tell; the held request's
     * passive look finds it and ends the wait.
     */
    @Test
    fun `an earlier opening's prompt approved with the tick ends the current wait`() {
        core()
        core.foregroundStarted()
        core.check()
        core.foregroundStopped(changingConfigurations = false)
        assertEquals(1, car.requests.size)

        car.meanwhile(at = car.elapsed + 6_000) { car.trustedAt = car.elapsed }
        core.foregroundStarted()

        assertEquals("one more request, the new opening's, and no more", 2, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue(car.requests.all { it.closed })
    }

    @Test
    fun `nothing is sent unless it is recorded first`() {
        core()
        store.failSave = true

        core.foregroundStarted()
        core.check()

        assertEquals(0, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals(AdbRescuePolicy.NOT_RECORDED, core.current.lastFailure)
        assertTrue(core.current.canAsk)
    }

    @Test
    fun `a request that cannot be sent is the phase it found`() {
        core()
        core.check()
        car.portOpen = false

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.UNAVAILABLE, core.current.phase)
        assertEquals("ConnectException", core.current.lastFailure)

        car.portOpen = true
        core.check()
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals("the opening's request is spent, and not counted: no key went out", 0, store.log.total)
        assertEquals(0, car.keysSent)
        car.requestFails = IllegalStateException("odd")
        core.ask()
        assertEquals(AdbRescuePhase.ERROR, core.current.phase)
        assertEquals("IllegalStateException", core.current.lastFailure)
    }

    @Test
    fun `a transport adbd closed is looked at again, passively`() {
        core()
        car.events += Event(6_000) { Wake.EOF }

        core.foregroundStarted()
        core.check()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals("EOFException", core.current.lastFailure)
    }

    /** Review finding 13: the handshake found the key trusted, so nothing was asked and nothing is counted. */
    @Test
    fun `a key trusted by the time the request goes out needs no answer, and is not counted`() {
        core()
        core.check()
        car.trustedAt = 0

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue(car.requests.single().closed)
        assertEquals(0, car.keysSent)
        assertEquals(AdbAskLog(), store.log)
        assertEquals(0, core.current.attemptCount)
        assertEquals(0, core.requestsLastDay())
    }

    /**
     * Everything that may start work, called while a request is held: a look, the control, and a new
     * opening (a stop and a start). The worker here is synchronous, so without the guard in `check`
     * or `ask` a look or a second request would run inside the block itself.
     */
    @Test
    fun `nothing runs beside a held request`() {
        core()
        var looksInBlock = -1
        var requestsInBlock = -1
        car.meanwhile(at = 4_000) {
            val looks = car.looks
            val requests = car.requests.size
            core.check()
            core.ask()
            core.foregroundStopped(changingConfigurations = false)
            core.foregroundStarted()
            looksInBlock = car.looks - looks
            requestsInBlock = car.requests.size - requests
        }
        car.approve(at = 9_000, tick = true)

        core.foregroundStarted()

        assertEquals(0, looksInBlock)
        assertEquals(0, requestsInBlock)
        assertEquals("one request open at a time", 1, maxOpen)
        // The new opening asked once the first request had unwound.
        assertEquals(2, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    @Test
    fun `a refused key is asked at the opening without a look first`() {
        core()
        core.check()
        val looks = car.looks
        car.approve(at = car.elapsed + 2_000, tick = true)

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        // The looks after it are the approval's own, not one before the request.
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue(car.looks - looks <= 2)
    }

    @Test
    fun `a trusted car is left alone by an opening`() {
        car.trustedAt = 0
        core()
        core.check()
        core.foregroundStarted()

        assertEquals(0, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    @Test
    fun `a look's caller hears its answer once, also when it asked during a request`() {
        core()
        var heard = 0
        core.check { heard++ }
        core.check { heard++ }
        assertEquals(2, heard)
        var heardInBlock = -1
        car.meanwhile(at = 1_000) {
            core.check { heard++ }
            heardInBlock = heard
        }
        core.foregroundStarted()
        assertEquals("not beside the request", 2, heardInBlock)
        assertEquals("after it, once", 3, heard)
        assertEquals(1, car.requests.size)
    }

    /** A listener that counts what the app does on each change: the runtime on TRUSTED, the restore otherwise. */
    private class Heard {
        var runtimeStarts = 0
        var restoreTriggers = 0
        val outcomes = mutableListOf<AdbRescuePhase>()

        fun hear(snapshot: AdbRescueSnapshot) {
            when (snapshot.phase) {
                AdbRescuePhase.TRUSTED -> runtimeStarts++
                AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.ERROR, AdbRescuePhase.AUTHORIZATION_REQUIRED -> restoreTriggers++
                else -> Unit
            }
            if (snapshot.phase != AdbRescuePhase.CHECKING && snapshot.phase != AdbRescuePhase.AWAITING_CONFIRMATION) {
                outcomes += snapshot.phase
            }
        }

        fun clear() {
            outcomes.clear()
            runtimeStarts = 0
            restoreTriggers = 0
        }
    }

    private fun heardFrom(setUp: () -> Unit, act: () -> Unit): Heard {
        val heard = Heard()
        core = AdbAccessCore(car, store, { it.run() }, timer, false) { heard.hear(core.current) }
        setUp()
        heard.clear()
        act()
        return heard
    }

    /**
     * Every outcome of a request reaches the listener once: the runtime starts once on an approval,
     * the restore is told once of a refusal. Each used to be heard twice, from the outcome and again
     * from the worker's release.
     */
    @Test
    fun `every outcome reaches the listener once`() {
        val approved = heardFrom({ car.approve(at = 5_000, tick = true) }) { core.foregroundStarted() }
        assertEquals(listOf(AdbRescuePhase.TRUSTED), approved.outcomes)
        assertEquals(1, approved.runtimeStarts)

        val cases = mapOf<String, () -> Unit>(
            "timeout" to {},
            "switch off" to { car.meanwhile(at = car.elapsed + 5_000) { car.switch = AdbSystemSwitch.DISABLED } },
            "transport lost" to { car.events += Event(car.elapsed + 6_000) { Wake.EOF } },
            "left" to { car.meanwhile(at = car.elapsed + 7_000) { core.foregroundStopped(changingConfigurations = false) } },
            "no box, cap spent" to {
                val now = car.wallMs()
                store.log = AdbAskLog(listOf(now - 2_000, now - 1_000), total = 2, lastAtMs = now - 1_000)
                car.approve(at = car.elapsed + 5_000, tick = false)
            },
            "not sent" to { car.requestFails = IllegalStateException("odd") },
        )
        cases.forEach { (name, arrange) ->
            car.trustedAt = null
            car.switch = AdbSystemSwitch.ENABLED
            car.requestFails = null
            car.events.clear()
            store.log = AdbAskLog()
            val heard = heardFrom({ core.check(); arrange() }) { core.foregroundStarted() }
            assertEquals(name, 1, heard.outcomes.size)
            assertEquals(name, 1, heard.restoreTriggers)
            assertEquals(name, 0, heard.runtimeStarts)
        }
    }

    /**
     * A look that times out while the prompt is on the screen is noise: the request stays held, and
     * the approval that follows is still heard on its transport. Only trust found by a look, the
     * switch, the transport itself or the time end a hold.
     */
    @Test
    fun `a hold ends only on trust, the switch, its transport or the time, never on a noisy look`() {
        core()
        car.meanwhile(at = 4_000) { car.looksTimeOut = true }
        car.meanwhile(at = 8_000) {
            car.looksTimeOut = false
            car.portOpen = false
        }
        car.meanwhile(at = 12_000) { car.portOpen = true }
        car.approve(at = 20_000, tick = true)

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertFalse(published.any { it.phase == AdbRescuePhase.ERROR || it.phase == AdbRescuePhase.UNAVAILABLE })
        assertTrue("the hold reached the approval", car.elapsed >= 20_000)
    }

    /** The car's «Отклонить» sends nothing to the transport: the ring turns to the end, as for no answer. */
    @Test
    fun `a denial behaves as the timeout`() {
        core()
        // The driver denies at 5 s: adbd says nothing to the transport.
        car.meanwhile(at = 5_000) {}

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertTrue(car.requests.single().closed)
        assertTrue(car.elapsed >= AdbRescuePolicy.HOLD_MS)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals(AdbRescuePolicy.NO_ANSWER, core.current.lastFailure)
        assertTrue("the control asks again", core.current.asked)
        assertTrue(core.current.canAsk)
    }

    /** Two approvals without the box: the follow-up goes out once, then the gate keeps the box and its control. */
    @Test
    fun `two approvals in a row without the box give exactly one follow-up`() {
        core()
        car.approve(at = 5_000, tick = false)
        car.approve(at = 15_000, tick = false)
        car.approve(at = 40_000, tick = true)

        core.foregroundStarted()

        assertEquals(2, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue(core.current.alwaysAllowMissed)
        assertTrue(core.current.canAsk)
        // The control still asks, and its approval with the box ends it.
        car.events.clear()
        car.approve(at = car.elapsed + 3_000, tick = true)
        core.ask()
        assertEquals(3, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    /** The follow-up is automatic, so the day's cap holds it back as it does an opening's. */
    @Test
    fun `the follow-up stays under the day's cap`() {
        val now = car.wallMs()
        store.log = AdbAskLog(listOf(now - 2_000, now - 1_000), total = 2, lastAtMs = now - 1_000)
        core()
        car.approve(at = 5_000, tick = false)

        core.foregroundStarted()

        assertEquals("the opening's, the third of the day, and no follow-up", 1, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue(core.current.alwaysAllowMissed)
    }

    /**
     * The framework says OK to adbd before it stores the key, and on a busy unit the store can come
     * two seconds later. That is an approval with the box, not a missed one: no box on the gate and
     * no second prompt.
     */
    @Test
    fun `a key stored two seconds after the approval gives no follow-up`() {
        core()
        car.approve(at = 5_000, tick = true, persistLagMs = 2_000)

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertFalse(published.any { it.alwaysAllowMissed })
    }

    /** Leaving while the request is still being opened stops it before its key reaches the car. */
    @Test
    fun `leaving during the handshake sends nothing`() {
        core()
        car.duringHandshake = { core.foregroundStopped(changingConfigurations = false) }

        core.foregroundStarted()

        assertEquals(0, car.keysSent)
        assertEquals(0, car.requests.size)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertNull("leaving is not a failure", core.current.lastFailure)

        assertEquals("a reservation that sent nothing is not counted", 0, store.log.total)
        assertEquals(0, core.current.attemptCount)

        car.duringHandshake = null
        car.approve(at = car.elapsed + 3_000, tick = true)
        core.foregroundStarted()
        assertEquals(1, car.keysSent)
        assertEquals(1, store.log.total)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    /**
     * A configuration change whose screen never comes back - the activity finished mid-rotation - is
     * leaving, a few seconds late: the request is closed, not held to its two minutes, and the next
     * start is an opening of its own.
     */
    @Test
    fun `a configuration-change stop never followed by a start ends the request and the opening`() {
        core()
        car.meanwhile(at = 4_000) { core.foregroundStopped(changingConfigurations = true) }

        core.foregroundStarted()

        assertEquals(1, car.requests.size)
        assertTrue(car.requests.single().closed)
        assertTrue(
            "closed a few seconds after the stop",
            car.elapsed < 4_000 + AdbRescuePolicy.RECREATE_MS + AdbRescuePolicy.LOOK_MS + 1_000,
        )
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertNull(core.current.lastFailure)

        // Nothing is sent from the background meanwhile.
        core.check()
        assertEquals(1, car.requests.size)

        car.approve(at = car.elapsed + 3_000, tick = true)
        core.foregroundStarted()
        assertEquals("the next start asks for itself", 2, car.requests.size)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
    }

    /** A listener that throws on the worker leaves no ring without a way out: ERROR, with its control. */
    @Test
    fun `a throwing listener releases the worker and ends in an error with its control`() {
        var throwOn: AdbRescuePhase? = AdbRescuePhase.TRUSTED
        core = AdbAccessCore(car, store, { it.run() }, timer, false) {
            published += core.current
            if (core.current.phase == throwOn) {
                throwOn = null
                throw IllegalStateException("listener")
            }
        }
        car.approve(at = 5_000, tick = true)

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.ERROR, core.current.phase)
        assertEquals("IllegalStateException", core.current.lastFailure)
        assertTrue(car.requests.single().closed)
        // Not busy: a look runs, and finds the trusted key.
        val looks = car.looks
        core.check()
        assertEquals(looks + 1, car.looks)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)

        // One that throws on the caller's thread, as a press's ring is announced, still lets the work run.
        car.trustedAt = null
        core.foregroundStopped(changingConfigurations = false)
        core.check()
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        throwOn = AdbRescuePhase.CHECKING
        try {
            core.check(shown = true)
        } catch (expected: IllegalStateException) {
            // The caller hears it; the worker ran regardless.
        }
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        core.check()
        // The look on the trusted key was a window of them.
        assertEquals(looks + 3 + AdbRescuePolicy.LOSS_LOOKS_MS.size, car.looks)
    }

    /**
     * A head unit boots with its clock behind until it syncs. The requests of the real day are then
     * in the clock's future: they still count, so a boot does not reset the cap. A clock that jumps
     * forward past them frees it.
     */
    @Test
    fun `the cap holds across a clock set back, and frees when the day has passed`() {
        val real = car.wallMs()
        store.log = AdbAskLog(listOf(real - 3_000, real - 2_000, real - 1_000), total = 3, lastAtMs = real - 1_000)
        // Booted with the clock a year behind.
        car.wallBase -= 365 * AdbRescuePolicy.DAY_MS
        core()
        assertEquals(3, core.requestsLastDay())

        core.foregroundStarted()
        assertEquals("the boot's clock does not reset the day's three", 0, car.requests.size)
        assertEquals(3, store.log.times.size)

        // The press is never stopped by the cap, and the future entries stay in the log.
        core.ask()
        assertEquals(1, car.requests.size)
        assertEquals(4, store.log.times.size)
        assertTrue(store.log.times.containsAll(listOf(real - 3_000, real - 2_000, real - 1_000)))

        // The clock syncs; a day after the real requests the openings ask again.
        core.foregroundStopped(changingConfigurations = false)
        car.wallBase += 365 * AdbRescuePolicy.DAY_MS + AdbRescuePolicy.DAY_MS
        assertEquals(0, core.requestsLastDay())
        core.foregroundStarted()
        assertEquals(2, car.requests.size)
    }

    @Test
    fun `the log keeps its last ten times, whatever the clock says`() {
        val now = car.wallMs()
        val future = (1..12).map { now + it * 60_000L }
        assertEquals(10, AdbRescuePolicy.keep(future, now).size)
        assertEquals(now, AdbRescuePolicy.keep(future, now).last())
        assertEquals(12, AdbRescuePolicy.recent(future, now).size)
        assertEquals(listOf(now - 1_000), AdbRescuePolicy.recent(listOf(now - AdbRescuePolicy.DAY_MS - 1, now - 1_000), now))
    }

    // Invariant A: the gate cannot outlive access.

    /** Review finding 3: the first look after an approval with the box timed out; the next would have found the key. */
    @Test
    fun `a look after the approval that does not answer is looked at again`() {
        core()
        car.events += Event(5_000) {
            car.trustedAt = car.elapsed
            car.failLooks = 1
            Wake.APPROVED
        }

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertFalse(published.any { it.phase == AdbRescuePhase.ERROR })
        assertEquals(1, car.requests.size)
    }

    /** Every look after the approval times out: their failure is published, and the next idle look finds the key. */
    @Test
    fun `an approval whose looks never answer is not a missed box, and the idle look ends the gate`() {
        core()
        car.events += Event(5_000) {
            car.trustedAt = car.elapsed
            car.failLooks = AdbRescuePolicy.APPROVAL_LOOKS_MS.size
            Wake.APPROVED
        }

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.ERROR, core.current.phase)
        assertFalse(core.current.alwaysAllowMissed)
        assertEquals(1, car.requests.size)

        idle(AdbRescuePolicy.IDLE_LOOK_MS)
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertEquals("no second request", 1, car.requests.size)
    }

    /** Review finding 4: the hold's last look straddles its deadline and the approval lands meanwhile. */
    @Test
    fun `an approval in the last moment of the hold is read before the hold says nobody answered`() {
        core()
        // The hold opens at 20 ms and looks every three seconds; the one at its deadline, 120.02 s, takes 1.5 s.
        car.lookMs = { at -> if (at in 119_000L..120_100L) 1_500L else 20L }
        car.trustedAt = 120_200
        car.events += Event(120_200) { Wake.APPROVED }

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertEquals(1, car.requests.size)
    }

    /** An earlier prompt approved with the box half a second before the deadline, between two looks. */
    @Test
    fun `trust that arrives just before the deadline is found by the last look`() {
        core()
        car.meanwhile(at = AdbRescuePolicy.HOLD_MS - 300) { car.trustedAt = car.elapsed }

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertEquals(1, car.requests.size)
    }

    /**
     * The hold ran out; the prompt stayed on the car's screen and the driver ticked the box a minute
     * later. Nothing is listening on a transport any more: the idle look finds it, without a press.
     */
    @Test
    fun `a prompt approved with the box after the hold took the gate down by itself`() {
        core()
        core.foregroundStarted()
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals(AdbRescuePolicy.NO_ANSWER, core.current.lastFailure)

        car.meanwhile(at = car.elapsed + 60_000) { car.trustedAt = car.elapsed }
        idle(60_000 + AdbRescuePolicy.IDLE_LOOK_MS)

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertEquals(1, car.requests.size)
        assertEquals(1, car.keysSent)
    }

    /** The idle look runs whatever the gate shows: a closed port, an unexpected answer, a refusal. */
    @Test
    fun `every gate that is up looks again until the key is trusted`() {
        val gates = mapOf<String, () -> Unit>(
            "closed port" to { car.portOpen = false },
            "no answer" to { car.looksTimeOut = true },
            "switch off" to { car.switch = AdbSystemSwitch.DISABLED },
        )
        gates.forEach { (name, arrange) ->
            car.portOpen = true
            car.looksTimeOut = false
            car.switch = AdbSystemSwitch.ENABLED
            car.trustedAt = null
            timers.clear()
            core()
            arrange()
            core.foregroundStarted()
            assertTrue(name, core.current.phase != AdbRescuePhase.TRUSTED)
            idle(5 * AdbRescuePolicy.IDLE_LOOK_MS)
            assertTrue(name, core.current.phase != AdbRescuePhase.TRUSTED)

            car.portOpen = true
            car.looksTimeOut = false
            car.switch = AdbSystemSwitch.ENABLED
            car.trustedAt = car.elapsed
            idle(AdbRescuePolicy.IDLE_LOOK_MS)
            assertEquals(name, AdbRescuePhase.TRUSTED, core.current.phase)
            core.foregroundStopped(changingConfigurations = false)
        }
    }

    @Test
    fun `the idle look is passive, foreground only, and stops with the screen`() {
        core()
        val start = car.elapsed
        core.foregroundStarted()
        val keys = car.keysSent
        val looks = car.looks
        idle(10 * 60_000L)
        assertEquals("the opening's one request, nothing more", keys, car.keysSent)
        val idleLooks = car.looks - looks
        val minutes = (car.elapsed - start) / 60_000.0
        assertTrue("about one every ${AdbRescuePolicy.IDLE_LOOK_MS} ms: $idleLooks in $minutes min", idleLooks in 90..130)

        core.foregroundStopped(changingConfigurations = false)
        val stoppedAt = car.looks
        idle(10 * 60_000L)
        assertEquals("nothing looks behind a stopped screen", stoppedAt, car.looks)
    }

    @Test
    fun `a trusted key is not looked at while idle`() {
        car.trustedAt = 0
        core()
        core.foregroundStarted()
        val looks = car.looks
        idle(10 * 60_000L)
        assertEquals(looks, car.looks)
    }

    /**
     * Any run of looks, answers, timeouts, departures and presses that ends with the car trusting
     * the key while a screen is in front leaves the gate gone - and never offers the key more than
     * the cap and the presses allow.
     */
    @Test
    fun `whatever happens, trust in front of the screen ends the gate, and the cap holds`() {
        val seeds = 400
        repeat(seeds) { seed ->
            val random = java.util.Random(seed.toLong())
            car.elapsed = 0
            car.trustedAt = null
            car.portOpen = true
            car.looksTimeOut = false
            car.failLooks = 0
            car.switch = AdbSystemSwitch.ENABLED
            car.events.clear()
            car.requests.clear()
            car.keysSent = 0
            store.log = AdbAskLog()
            timers.clear()
            published.clear()
            core()
            var presses = 0
            var inFront = false
            repeat(12) {
                val at = car.elapsed + random.nextInt(150_000)
                when (random.nextInt(9)) {
                    0 -> car.approve(at, tick = false)
                    1 -> car.events += Event(at) { Wake.EOF }
                    2 -> car.meanwhile(at) { car.portOpen = !car.portOpen }
                    3 -> car.meanwhile(at) { car.looksTimeOut = !car.looksTimeOut }
                    4 -> car.meanwhile(at) { car.failLooks = 1 + random.nextInt(4) }
                    5 -> car.meanwhile(at) {
                        if (inFront) core.foregroundStopped(changingConfigurations = random.nextBoolean())
                        inFront = false
                    }
                    6 -> car.meanwhile(at) {
                        if (!inFront) core.foregroundStarted()
                        inFront = true
                    }
                    7 -> car.meanwhile(at) {
                        if (core.current.canAsk) presses++
                        core.ask()
                    }
                    else -> car.meanwhile(at) { core.check(shown = random.nextBoolean()) }
                }
            }
            core.foregroundStarted()
            inFront = true
            idle(random.nextInt(600_000).toLong())
            // The car comes back and trusts the key; a screen is in front.
            car.events.removeAll { it.meanwhile }
            car.portOpen = true
            car.looksTimeOut = false
            car.failLooks = 0
            car.trustedAt = car.elapsed
            if (!inFront) core.foregroundStarted()
            idle(AdbRescuePolicy.HOLD_MS + 30_000)

            assertEquals("seed $seed", AdbRescuePhase.TRUSTED, core.current.phase)
            assertTrue("seed $seed: ${car.keysSent} keys, $presses presses", car.keysSent <= AdbRescuePolicy.DAILY_ASKS + presses)
            assertTrue("seed $seed: one request at a time", maxOpen <= 1)
        }
    }

    // Invariant B: one place decides and records an automatic request.

    /**
     * Review finding 7: 0.7.1 sent its one request an hour before the upgrade and was still waiting.
     * The first opening of the new release does not put a second prompt behind it; the next does.
     */
    @Test
    fun `the first start after 0_7_1 does not double its pending request`() {
        val now = car.wallMs()
        store.log = AdbAskLegacy.from071(total = 1, lastAtMs = now - 3_600_000, requestPending = true, nowMs = now)
        core()
        core.check()

        core.foregroundStarted()

        assertEquals(0, car.keysSent)
        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertTrue("the control still asks", core.current.canAsk)
        assertFalse("declined once, then forgotten", store.log.legacyPending)
        assertEquals("0.7.1's request counts toward the day", 1, core.requestsLastDay())

        core.foregroundStopped(changingConfigurations = false)
        core.foregroundStarted()
        assertEquals(1, car.keysSent)
    }

    @Test
    fun `0_7_1's keys seed the log once, and an old pending flag is not a prompt on the screen`() {
        val now = 2_000_000_000_000L
        assertEquals(
            AdbAskLog(times = listOf(now - 60_000), total = 4, lastAtMs = now - 60_000, legacyPending = true),
            AdbAskLegacy.from071(total = 4, lastAtMs = now - 60_000, requestPending = true, nowMs = now),
        )
        assertFalse(AdbAskLegacy.from071(4, now - 2 * AdbRescuePolicy.DAY_MS, requestPending = true, nowMs = now).legacyPending)
        assertFalse(AdbAskLegacy.from071(4, now - 60_000, requestPending = false, nowMs = now).legacyPending)
        assertEquals(AdbAskLog(), AdbAskLegacy.from071(0, 0L, requestPending = false, nowMs = now))
    }

    /** A pending 0.7.1 request is answered by trust: nothing is held back afterwards. */
    @Test
    fun `trust clears 0_7_1's pending request`() {
        val now = car.wallMs()
        store.log = AdbAskLegacy.from071(total = 1, lastAtMs = now - 60_000, requestPending = true, nowMs = now)
        car.trustedAt = 0
        core()
        core.check()
        assertFalse(store.log.legacyPending)
    }

    /**
     * Review finding 8: the cap was read at one moment and the request recorded at another. A clock
     * that stepped back between the two kept the three requests that had aged out, and a fourth
     * went in beside them.
     */
    @Test
    fun `the cap and the record are one reading of the clock`() {
        val t = car.wallMs()
        val day = AdbRescuePolicy.DAY_MS
        store.log = AdbAskLog(listOf(t - day - 7_000, t - day - 6_000, t - day - 5_000), 3, t - day - 5_000)
        core()
        core.check()
        car.wallScript.addAll(listOf(t, t - 10_000))

        core.foregroundStarted()

        val saved = store.log
        assertEquals(1, car.keysSent)
        assertTrue(
            "at most three recent requests at the send's own time: ${saved.times}",
            AdbRescuePolicy.recent(saved.times, saved.lastAtMs).size <= AdbRescuePolicy.DAILY_ASKS,
        )
    }

    /** Whatever ends a request before its key is written, the log and the page count only keys that went out. */
    @Test
    fun `the count is the keys that reached the car`() {
        core()
        core.check()
        car.portOpen = false
        core.foregroundStarted()
        car.portOpen = true
        core.foregroundStopped(changingConfigurations = false)

        car.duringHandshake = { core.foregroundStopped(changingConfigurations = false) }
        core.foregroundStarted()
        car.duringHandshake = null
        assertEquals(0, car.keysSent)
        assertEquals(AdbAskLog(), store.log)
        assertEquals(0, core.current.attemptCount)
        assertFalse("nothing went out, so the control asks for the first time", core.current.asked)

        car.approve(at = car.elapsed + 2_000, tick = true)
        core.foregroundStarted()
        assertEquals(1, car.keysSent)
        assertEquals(1, store.log.total)
        assertEquals(1, store.log.times.size)
    }

    /** A failure after the key may have gone out is counted: the car may hold the entry. */
    @Test
    fun `a request that failed after its key may have gone out is counted`() {
        core()
        core.check()
        car.requestFails = IOException("reset after the key")
        core.foregroundStarted()
        assertEquals(1, store.log.total)
        assertEquals(1, core.current.attemptCount)
    }

    // Invariant E: access lost while the app runs.

    private fun trustedCore(): AdbAccessCore {
        car.trustedAt = 0
        core(trustedBefore = true)
        core.check()
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        published.clear()
        return core
    }

    /**
     * Review finding 1: the car stopped trusting the key while the app ran - its features failed,
     * and the phase stayed TRUSTED for as long as the process lived, with no gate and no way back.
     * A feature's refusal now starts one window of looks, and a refusal across it leaves TRUSTED
     * through the listener, as any other change.
     */
    @Test
    fun `a key refused across the window leaves TRUSTED through the listener`() {
        trustedCore()
        car.trustedAt = null
        val looks = car.looks
        val start = car.elapsed

        core.transportLost()

        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, core.current.phase)
        assertEquals(listOf(AdbRescuePhase.AUTHORIZATION_REQUIRED), steps)
        assertEquals(AdbRescuePolicy.LOSS_LOOKS_MS.size, car.looks - looks)
        assertTrue(car.elapsed - start >= AdbRescuePolicy.LOSS_LOOKS_MS.last())
        assertEquals("nothing in the background offers the key", 0, car.keysSent)
        assertEquals("nor records a request", AdbAskLog(), store.log)
    }

    @Test
    fun `a port closed across the window leaves TRUSTED`() {
        trustedCore()
        car.portOpen = false

        core.transportLost()

        assertEquals(listOf(AdbRescuePhase.UNAVAILABLE), steps)
    }

    /** A restart of adbd, or a passing refusal: the window ends at the first look that gets in. */
    @Test
    fun `a passing refusal is no loss, and the gate never shows it`() {
        trustedCore()
        car.trustedAt = null
        car.meanwhile(3_000) { car.trustedAt = car.elapsed }

        core.transportLost()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue("the listener heard nothing: $steps", published.isEmpty())
        assertEquals(3, car.looks - 1)
    }

    @Test
    fun `timeouts are no loss, wherever they are in the window`() {
        trustedCore()
        car.looksTimeOut = true
        core.transportLost()
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)

        car.looksTimeOut = false
        car.trustedAt = null
        car.failLooks = 1
        car.meanwhile(car.elapsed + 6_000) { car.failLooks = 1 }
        core.transportLost()
        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `features failing at once start one window`() {
        trustedCore()
        car.trustedAt = null
        listOf(500L, 1_000L, 2_500L, 4_000L, 9_000L).forEach { at -> car.meanwhile(at) { core.transportLost() } }
        val looks = car.looks

        core.transportLost()
        core.transportLost()

        assertEquals(AdbRescuePolicy.LOSS_LOOKS_MS.size, car.looks - looks)
        assertEquals(listOf(AdbRescuePhase.AUTHORIZATION_REQUIRED), steps)
        // Off TRUSTED, a feature's failure says nothing new.
        core.transportLost()
        assertEquals(AdbRescuePolicy.LOSS_LOOKS_MS.size, car.looks - looks)
    }

    /** Access back by any route - here the port reopened behind the app's back - returns the same way it left. */
    @Test
    fun `access regained is found by a feature that got in, through the listener`() {
        trustedCore()
        car.portOpen = false
        core.transportLost()
        assertEquals(AdbRescuePhase.UNAVAILABLE, core.current.phase)

        car.portOpen = true
        val looks = car.looks
        core.channelReached()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertEquals(listOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.TRUSTED), steps)
        assertEquals(1, car.looks - looks)
        // A trusted key that gets in is no news.
        core.channelReached()
        assertEquals(1, car.looks - looks)
    }

    /** Lost in front of the screen: the opening's one request, and the cap's rules for the rest. */
    @Test
    fun `access lost in front of the screen asks once`() {
        trustedCore()
        core.foregroundStarted()
        car.trustedAt = null

        core.transportLost()
        idle(AdbRescuePolicy.HOLD_MS + 60_000)
        repeat(5) { core.transportLost() }
        idle(10 * 60_000L)

        assertEquals(1, car.keysSent)
        assertTrue(core.current.canAsk)
    }

    /**
     * Any four answers in the window: it leaves TRUSTED exactly when none got in and every one was
     * definite - a refusal or a closed port - and then by the last of them.
     */
    @Test
    fun `only a window of definite answers leaves TRUSTED`() {
        val answers = listOf("trusted", "refused", "closed", "timeout")
        repeat(256) { seed ->
            val window = List(4) { answers[(seed shr (2 * it)) and 3] }
            car.elapsed = 0
            car.portOpen = true
            car.looksTimeOut = false
            car.failLooks = 0
            car.events.clear()
            trustedCore()
            fun set(answer: String) {
                car.trustedAt = if (answer == "trusted") 0 else null
                car.portOpen = answer != "closed"
                car.looksTimeOut = answer == "timeout"
            }
            set(window[0])
            // Between the looks at 0, 2, 5 and 10 seconds.
            listOf(1_000L, 3_500L, 7_500L).forEachIndexed { i, at -> car.meanwhile(car.elapsed + at) { set(window[i + 1]) } }

            core.transportLost()

            val lost = "trusted" !in window && "timeout" !in window
            val expected = when {
                !lost -> AdbRescuePhase.TRUSTED
                window.last() == "closed" -> AdbRescuePhase.UNAVAILABLE
                else -> AdbRescuePhase.AUTHORIZATION_REQUIRED
            }
            assertEquals("$window", expected, core.current.phase)
            assertEquals("$window", if (lost) listOf(expected) else emptyList<AdbRescuePhase>(), steps)
        }
    }
}
