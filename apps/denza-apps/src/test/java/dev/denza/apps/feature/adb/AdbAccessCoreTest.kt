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

        val trusted: Boolean get() = trustedAt?.let { elapsed >= it } == true

        override fun passiveCheck(): AdbCheckResult {
            looks++
            elapsed += 20
            return when {
                !portOpen -> AdbCheckResult(AdbCheckOutcome.UNAVAILABLE, "ConnectException")
                trusted -> AdbCheckResult(AdbCheckOutcome.TRUSTED)
                else -> AdbCheckResult(AdbCheckOutcome.AUTHORIZATION_REQUIRED)
            }
        }

        override fun openRequest(): AdbHeldRequest {
            requestFails?.let { throw it }
            if (!portOpen) throw ConnectException("refused")
            return FakeRequest(trusted).also { requests += it }
        }

        override fun requestFailure(error: Throwable): AdbCheckResult = AdbCheckResult(
            if (error is ConnectException) AdbCheckOutcome.UNAVAILABLE else AdbCheckOutcome.ERROR,
            error.javaClass.simpleName,
        )

        override fun systemSwitch(): AdbSystemSwitch = switch
        override fun wallMs(): Long = wallBase + elapsed
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

    private fun core(trustedBefore: Boolean = false): AdbAccessCore {
        core = AdbAccessCore(car, store, { it.run() }, trustedBefore) { published += core.current }
        return core
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
        assertEquals(3, core.current.requestsToday)
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
        assertEquals("the opening's request is spent", 1, store.log.total)
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

    @Test
    fun `a key trusted by the time the request goes out needs no answer`() {
        core()
        core.check()
        car.trustedAt = 0

        core.foregroundStarted()

        assertEquals(AdbRescuePhase.TRUSTED, core.current.phase)
        assertTrue(car.requests.single().closed)
    }

    @Test
    fun `nothing runs beside a held request`() {
        core()
        var looksDuringHold = -1
        car.meanwhile(at = 4_000) {
            val before = car.looks
            core.check()
            core.ask()
            core.foregroundStarted()
            looksDuringHold = car.looks - before
        }

        core.foregroundStarted()
        core.check()

        assertEquals(0, looksDuringHold)
        assertEquals(1, car.requests.size)
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
    fun `a look's caller hears its answer once`() {
        core()
        var heard = 0
        core.check { heard++ }
        core.check { heard++ }
        assertEquals(2, heard)
        car.meanwhile(at = 1_000) { core.check { heard++ } }
        core.foregroundStarted()
        assertEquals("a look asked for during a request is not run, and not answered", 2, heard)
    }
}
