package dev.denza.apps.feature.adb

import java.io.IOException

/** What a passive look found, and the failure's own name for the technical page. */
internal class AdbCheckResult(val outcome: AdbCheckOutcome, val failure: String? = null)

/** A key request whose transport is still open, so adbd can deliver the answer to it. */
internal interface AdbHeldRequest {
    /** The key was trusted already and the handshake completed: nothing was asked. */
    val alreadyTrusted: Boolean

    /** True on adbd's CNXN for this transport, false when [waitMs] passed. A closed transport throws. */
    @Throws(IOException::class)
    fun awaitApproval(waitMs: Int): Boolean

    /** Safe from any thread; a wait in progress ends with an exception. */
    fun close()
}

/** The car, the clocks and the trust record, as [AdbAccessCore] needs them. */
internal interface AdbAccessSystem {
    /** Signs with the key it has and never offers it: no prompt, no queue entry. */
    fun passiveCheck(): AdbCheckResult

    /** Sends this app's public key once and keeps the transport open. */
    @Throws(Exception::class)
    fun openRequest(): AdbHeldRequest

    /** A request that could not be sent, as the phase it leaves: UNAVAILABLE or ERROR. */
    fun requestFailure(error: Throwable): AdbCheckResult

    fun systemSwitch(): AdbSystemSwitch
    fun wallMs(): Long
    fun elapsedMs(): Long
    fun sleep(ms: Long)
    fun recordTrusted()
}

/** Every request sent, so the daily cap survives the process and the reboot. */
internal data class AdbAskLog(
    /** Wall times of the requests of the last day. */
    val times: List<Long> = emptyList(),
    val total: Int = 0,
    val lastAtMs: Long = 0L,
)

internal interface AdbAskStore {
    fun load(): AdbAskLog

    /** Synchronous and durable: a request is never sent unless it is recorded first. */
    fun save(log: AdbAskLog): Boolean
}

internal enum class AdbAskKind {
    /** The one request a foreground opening sends by itself, under the daily cap. */
    AUTO,

    /** The gate's or the service panel's control. */
    USER,

    /** Approved without «always allow»: the same request again, while the driver is at the screen. */
    FOLLOW_UP,
}

/**
 * Who may offer the key to the car, when, and how a request is held until the driver answers.
 *
 * One worker runs everything that talks to adbd, so there is never more than one request
 * outstanding and never a check beside it. A request is sent only from a foreground opening of the
 * app, or from a press: background work - autoload, the screen waking, a feature's channel, the
 * restore - only ever looks passively.
 *
 * - An opening sends at most one request by itself ([AdbAskKind.AUTO]), and only while fewer than
 *   [AdbRescuePolicy.DAILY_ASKS] were sent in the last day. Past that the gate's control asks.
 * - The request is held up to [AdbRescuePolicy.HOLD_MS]. adbd answers an approval on the transport
 *   that asked; a passive look every [AdbRescuePolicy.LOOK_MS] notices an approval of an earlier
 *   request of the same key, whose transport is gone.
 * - An approval whose next connection is still refused was given without «always allow»: the gate
 *   shows that box and the next request goes out at once.
 * - Leaving the foreground, the switch going off, the time running out and the car answering all
 *   close the transport. A configuration change is not leaving.
 */
internal class AdbAccessCore(
    private val system: AdbAccessSystem,
    private val store: AdbAskStore,
    private val worker: (Runnable) -> Unit,
    trustedBefore: Boolean,
    private val listener: () -> Unit,
) {
    private val lock = Any()

    @Volatile
    var current: AdbRescueSnapshot
        private set

    /** A check or a request is on the worker. */
    private var busy = false
    private var held: AdbHeldRequest? = null
    private var stopped = false
    private var foreground = false
    private var started = 0
    private var keepOpening = false
    private var opening = 0
    private var askedIn = -1

    init {
        val log = store.load()
        current = AdbRescueSnapshot(
            attemptCount = log.total,
            lastAttemptAtMillis = log.lastAtMs,
            trustedBefore = trustedBefore,
            requestsToday = AdbRescuePolicy.lastDay(log.times, system.wallMs()).size,
        )
    }

    /** A passive look; [after] runs once it has answered. A look or a request in flight is not doubled. */
    fun check(after: (() -> Unit)? = null) {
        synchronized(lock) {
            if (busy) return
            busy = true
            current = AdbRescuePolicy.checking(current)
        }
        listener()
        worker { look(after) }
    }

    /** The gate's control: one request now, whatever the cap, while nothing else is out. */
    fun ask() {
        synchronized(lock) {
            if (busy || !current.canAsk) return
            busy = true
            askedIn = opening
            current = AdbRescuePolicy.asking(current)
        }
        listener()
        worker { askLoop(AdbAskKind.USER) }
    }

    /**
     * The app came to the front. A new opening owns one request of its own: sent at once on a
     * refused key, or after the look this starts, or after whatever is on the worker ends.
     */
    fun foregroundStarted() {
        val looks: Boolean
        synchronized(lock) {
            // A second screen of the same opening, or the one a configuration change recreated.
            if (++started > 1) return
            if (keepOpening) {
                keepOpening = false
                return
            }
            foreground = true
            opening++
            if (busy) return
            // A refused key the opening may ask for is asked for at once: the request's own handshake
            // is the look. One it may not ask for - the day's three spent - is looked at, since an
            // earlier prompt approved with the box while the app was away trusts it with nobody
            // watching, and a look costs no prompt.
            val asks = current.phase == AdbRescuePhase.AUTHORIZATION_REQUIRED && autoAllowed()
            looks = !asks && current.phase in LOOKED_AT_ON_OPENING
            if (!looks && !asks) return
            busy = true
            if (looks) current = AdbRescuePolicy.checking(current) else startAuto()
        }
        listener()
        if (looks) worker { look(null) } else worker { askLoop(AdbAskKind.AUTO) }
    }

    /**
     * The app left the front. A request in flight is closed - the driver cannot answer a prompt
     * they are not looking at, and the next opening asks again. A configuration change keeps both
     * the opening and the request: a rotation never sends a second one.
     */
    fun foregroundStopped(changingConfigurations: Boolean) {
        val closing: AdbHeldRequest?
        synchronized(lock) {
            started = maxOf(0, started - 1)
            if (started > 0) return
            if (changingConfigurations) {
                keepOpening = true
                return
            }
            keepOpening = false
            foreground = false
            if (!busy) return
            stopped = true
            closing = held
        }
        closing?.close()
    }

    private fun autoAllowed(): Boolean = foreground && askedIn != opening &&
        AdbRescuePolicy.lastDay(store.load().times, system.wallMs()).size < AdbRescuePolicy.DAILY_ASKS

    /** Under [lock]: the opening's request is spent, and the gate turns to the ring at once. */
    private fun startAuto() {
        askedIn = opening
        current = AdbRescuePolicy.asking(current)
    }

    /** On the worker: a passive look, and the opening's own request straight after a refusal. */
    private fun look(after: (() -> Unit)?) {
        val look = system.passiveCheck()
        if (look.outcome == AdbCheckOutcome.TRUSTED) system.recordTrusted()
        val switch = system.systemSwitch()
        release({ AdbRescuePolicy.afterCheck(it, look.outcome, switch, look.failure) }, after)
    }

    /**
     * The worker is done: free it, or start the opening's own request if it is still due. [change]
     * lands in the same step as that decision, so a refusal followed by the request is one change
     * of the gate - the ring - and never the idle gate for a frame first.
     */
    private fun release(
        change: ((AdbRescueSnapshot) -> AdbRescueSnapshot)? = null,
        after: (() -> Unit)? = null,
    ) {
        var pending = change
        var then = after
        while (true) {
            val auto = synchronized(lock) {
                pending?.let { current = it(current) }
                pending = null
                held = null
                stopped = false
                if (current.phase == AdbRescuePhase.AUTHORIZATION_REQUIRED && autoAllowed()) {
                    startAuto()
                    true
                } else {
                    busy = false
                    false
                }
            }
            listener()
            then?.invoke()
            then = null
            if (!auto) return
            askLoop(AdbAskKind.AUTO, release = false)
        }
    }

    private fun askLoop(first: AdbAskKind, release: Boolean = true) {
        var kind: AdbAskKind? = first
        while (kind != null) kind = askOnce()
        if (release) release()
    }

    /** One request, from the switch reading to its end. Returns the follow-up, if one is due. */
    private fun askOnce(): AdbAskKind? {
        if (leaving()) {
            publish { AdbRescuePolicy.refused(it) }
            return null
        }
        // Re-read at the moment of sending: a request is a queue entry the car keeps whatever
        // happens to this app, and one that can never be drawn is only a stuck queue.
        val switch = system.systemSwitch()
        if (switch == AdbSystemSwitch.DISABLED) {
            publish { AdbRescuePolicy.systemSwitchOff(it) }
            return null
        }
        val now = system.wallMs()
        val before = store.load()
        val log = AdbAskLog(AdbRescuePolicy.lastDay(before.times, now) + now, before.total + 1, now)
        if (!store.save(log)) {
            publish { AdbRescuePolicy.refused(it).copy(lastFailure = AdbRescuePolicy.NOT_RECORDED) }
            return null
        }
        publish { AdbRescuePolicy.sent(it, switch, log) }
        val request = try {
            system.openRequest()
        } catch (error: Exception) {
            settle(system.requestFailure(error))
            return null
        }
        val leaving = synchronized(lock) {
            if (!stopped) held = request
            stopped
        }
        if (leaving) {
            request.close()
            publish { AdbRescuePolicy.refused(it) }
            return null
        }
        if (request.alreadyTrusted) {
            request.close()
            settle(AdbCheckResult(AdbCheckOutcome.TRUSTED))
            return null
        }
        val ending = try {
            hold(request)
        } finally {
            request.close()
            synchronized(lock) { held = null }
        }
        return when (ending) {
            is Ending.Approved -> afterApproval()
            is Ending.Looked -> {
                settle(ending.look)
                null
            }
            Ending.SwitchOff -> {
                publish { AdbRescuePolicy.systemSwitchOff(it) }
                null
            }
            Ending.TimedOut -> {
                publish { AdbRescuePolicy.refused(it).copy(lastFailure = AdbRescuePolicy.NO_ANSWER) }
                null
            }
            Ending.Left -> {
                publish { AdbRescuePolicy.refused(it) }
                null
            }
            is Ending.Lost -> {
                val look = system.passiveCheck()
                settle(
                    if (look.outcome == AdbCheckOutcome.AUTHORIZATION_REQUIRED) {
                        AdbCheckResult(look.outcome, AdbRescuePolicy.failureLabel(ending.error))
                    } else {
                        look
                    },
                )
                null
            }
        }
    }

    private fun hold(request: AdbHeldRequest): Ending {
        val deadline = system.elapsedMs() + AdbRescuePolicy.HOLD_MS
        var nextLook = system.elapsedMs() + AdbRescuePolicy.LOOK_MS
        while (true) {
            if (leaving()) return Ending.Left
            val now = system.elapsedMs()
            if (now >= deadline) return Ending.TimedOut
            val wait = (minOf(nextLook, deadline) - now).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val approved = try {
                request.awaitApproval(wait)
            } catch (error: IOException) {
                return if (leaving()) Ending.Left else Ending.Lost(error)
            }
            if (approved) return Ending.Approved
            if (system.elapsedMs() < nextLook) continue
            nextLook = system.elapsedMs() + AdbRescuePolicy.LOOK_MS
            if (leaving()) return Ending.Left
            if (system.systemSwitch() == AdbSystemSwitch.DISABLED) return Ending.SwitchOff
            val look = system.passiveCheck()
            if (look.outcome != AdbCheckOutcome.AUTHORIZATION_REQUIRED) return Ending.Looked(look)
        }
    }

    /**
     * adbd let the asking transport in. Only «always allow» makes the key trusted on the next
     * connection; the framework stores it a moment after the answer, hence a second look.
     */
    private fun afterApproval(): AdbAskKind? {
        for (look in 0 until AdbRescuePolicy.APPROVAL_LOOKS) {
            if (look > 0) system.sleep(AdbRescuePolicy.APPROVAL_LOOK_GAP_MS)
            val result = system.passiveCheck()
            if (result.outcome != AdbCheckOutcome.AUTHORIZATION_REQUIRED) {
                settle(result)
                return null
            }
        }
        val again = synchronized(lock) {
            current = AdbRescuePolicy.refused(current).copy(alwaysAllowMissed = true)
            foreground && !stopped
        }
        if (!again) {
            listener()
            return null
        }
        publish { AdbRescuePolicy.asking(it) }
        return AdbAskKind.FOLLOW_UP
    }

    private fun leaving(): Boolean = synchronized(lock) { stopped }

    /** A look's answer, classified with a switch reading taken after it. */
    private fun settle(look: AdbCheckResult) {
        if (look.outcome == AdbCheckOutcome.TRUSTED) system.recordTrusted()
        val switch = system.systemSwitch()
        publish { AdbRescuePolicy.afterCheck(it, look.outcome, switch, look.failure) }
    }

    private fun publish(change: (AdbRescueSnapshot) -> AdbRescueSnapshot) {
        synchronized(lock) { current = change(current) }
        listener()
    }

    private sealed interface Ending {
        data object Approved : Ending
        class Looked(val look: AdbCheckResult) : Ending
        data object SwitchOff : Ending
        data object TimedOut : Ending
        data object Left : Ending
        class Lost(val error: IOException) : Ending
    }

    private companion object {
        /** The phases an opening looks at again, when it does not ask. */
        val LOOKED_AT_ON_OPENING = setOf(
            AdbRescuePhase.UNKNOWN,
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.ERROR,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
        )
    }
}
