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

/**
 * Stops a request while it is still being opened. Until the public key is written the opening can
 * be abandoned with nothing in the car's queue; [cancel] from any thread closes the socket being
 * connected and keeps the key from being written after it.
 */
internal class AdbRequestCancel {
    private val lock = Any()
    private var cancelled = false
    private var hook: (() -> Unit)? = null

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    fun cancel() {
        val run = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            hook
        }
        run?.invoke()
    }

    /** [action] runs on [cancel], or at once if the cancel came first. */
    fun onCancel(action: () -> Unit) {
        val now = synchronized(lock) {
            if (!cancelled) hook = action
            cancelled
        }
        if (now) action()
    }
}

/** The car, the clocks and the trust record, as [AdbAccessCore] needs them. */
internal interface AdbAccessSystem {
    /** Signs with the key it has and never offers it: no prompt, no queue entry. */
    fun passiveCheck(): AdbCheckResult

    /**
     * Sends this app's public key once and keeps the transport open. [cancel] may stop it from
     * another thread until the key is written; a cancelled opening throws and sends nothing.
     */
    @Throws(Exception::class)
    fun openRequest(cancel: AdbRequestCancel): AdbHeldRequest

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
    /** Wall times of the recent requests, oldest first: [AdbRescuePolicy.recent] keeps them. */
    val times: List<Long> = emptyList(),
    val total: Int = 0,
    val lastAtMs: Long = 0L,
)

internal interface AdbAskStore {
    fun load(): AdbAskLog

    /** Synchronous and durable: a request is never sent unless it is recorded first. */
    fun save(log: AdbAskLog): Boolean
}

/**
 * Who may offer the key to the car, when, and how a request is held until the driver answers.
 *
 * One worker runs everything that talks to adbd, so there is never more than one request
 * outstanding and never a check beside it. A request is sent only from a foreground opening of the
 * app, or from a press: background work - autoload, the screen waking, a feature's channel, the
 * restore - only ever looks passively.
 *
 * - Every request is recorded before it is sent and counts toward [AdbRescuePolicy.DAILY_ASKS].
 *   The cap stops the automatic ones - an opening's own, and the one follow-up an opening may send
 *   after an approval without the box - and never a press of the gate's or the panel's control.
 * - The request is held up to [AdbRescuePolicy.HOLD_MS]. adbd answers an approval on the transport
 *   that asked; a passive look every [AdbRescuePolicy.LOOK_MS] notices an approval of an earlier
 *   request of the same key, whose transport is gone. Only that approval or the switch going off
 *   ends the hold early: a look that times out is noise, not an answer.
 * - An approval whose next connections are still refused was given without «always allow»: the
 *   gate shows that box, and the next request goes out at once - once an opening.
 * - Leaving the foreground, the switch going off, the time running out and the car answering all
 *   close the transport; leaving during the handshake stops it before the key is written. A
 *   configuration change is not leaving.
 * - Every outcome reaches the listener once, and the worker is released whatever throws.
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

    /** The request being opened, until its transport is [held]. */
    private var opening: AdbRequestCancel? = null
    private var stopped = false
    private var foreground = false
    private var started = 0
    private var keepOpening = false

    /** When the configuration change that set [keepOpening] stopped the screen. */
    private var keptAtMs = 0L
    private var openings = 0
    private var askedIn = -1
    private var followedIn = -1

    init {
        val log = store.load()
        current = AdbRescueSnapshot(attemptCount = log.total, lastAttemptAtMillis = log.lastAtMs, trustedBefore = trustedBefore)
    }

    /** Requests recorded in the last 24 hours, counted now: the technical page's. */
    fun requestsLastDay(): Int = AdbRescuePolicy.recent(store.load().times, system.wallMs()).size

    /**
     * A passive look; [after] runs once it has answered. A look or a request in flight is not
     * doubled - and then [after] is dropped, not queued: every caller passes one that only acts
     * on a settled phase, and the work in flight settles it and notifies the listener anyway.
     */
    fun check(after: (() -> Unit)? = null) {
        synchronized(lock) {
            // [after] is dropped here, not queued (see above): harmless, and known.
            if (busy) return
            busy = true
            current = AdbRescuePolicy.checking(current)
        }
        announceThen { look(after) }
    }

    /** The gate's control: one request now, whatever the cap, while nothing else is out. */
    fun ask() {
        synchronized(lock) {
            if (busy || !current.canAsk) return
            busy = true
            askedIn = openings
            current = AdbRescuePolicy.asking(current)
        }
        announceThen { release(askChain()) }
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
            if (keepOpening && !keepExpired()) {
                keepOpening = false
                return
            }
            keepOpening = false
            foreground = true
            openings++
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
        announceThen { if (looks) look(null) else release(askChain()) }
    }

    /**
     * The app left the front. A request in flight is closed - the driver cannot answer a prompt
     * they are not looking at, and the next opening asks again - and one still being opened is
     * stopped before its key is written. A configuration change keeps both the opening and the
     * request: a rotation never sends a second one.
     */
    fun foregroundStopped(changingConfigurations: Boolean) {
        val closing: AdbHeldRequest?
        val cancelling: AdbRequestCancel?
        synchronized(lock) {
            started = maxOf(0, started - 1)
            if (started > 0) return
            if (changingConfigurations) {
                keepOpening = true
                keptAtMs = system.elapsedMs()
                return
            }
            keepOpening = false
            foreground = false
            if (!busy) return
            stopped = true
            closing = held
            cancelling = opening
        }
        cancelling?.cancel()
        closing?.close()
    }

    /** Under [lock]: an automatic request is due for this opening and the day's cap allows it. */
    private fun autoAllowed(): Boolean = inFront() && askedIn != openings && underCap()

    /** Under [lock]: a screen is in front, or a configuration change is still bringing one back. */
    private fun inFront(): Boolean = foreground && !(keepOpening && keepExpired())

    private fun underCap(): Boolean =
        AdbRescuePolicy.recent(store.load().times, system.wallMs()).size < AdbRescuePolicy.DAILY_ASKS

    /** Under [lock]: the opening's request is spent, and the gate turns to the ring at once. */
    private fun startAuto() {
        askedIn = openings
        current = AdbRescuePolicy.asking(current)
    }

    /**
     * The change made under the lock reaches the listener, and [body] goes to the worker even if
     * the listener throws: a worker never scheduled would leave [busy] set for good.
     */
    private fun announceThen(body: () -> Unit) {
        try {
            listener()
        } finally {
            worker(Runnable { guarded(body) })
        }
    }

    /**
     * Whatever throws on the worker - the car's adapter, the listener - the worker is released and
     * the phase is ERROR, whose gate has its control; never `busy` with a ring and no way out.
     */
    private fun guarded(body: () -> Unit) {
        try {
            body()
        } catch (error: Throwable) {
            val closing: AdbHeldRequest?
            synchronized(lock) {
                closing = held
                held = null
                opening = null
                stopped = false
                busy = false
                current = AdbRescuePolicy.afterCheck(current, AdbCheckOutcome.ERROR, current.systemSwitch,
                    AdbRescuePolicy.failureLabel(error))
            }
            runCatching { closing?.close() }
            // The listener may be what threw; the snapshot is right either way.
            runCatching { listener() }
        }
    }

    /** On the worker: a passive look, and the opening's own request straight after a refusal. */
    private fun look(after: (() -> Unit)?) {
        release(classify(system.passiveCheck()), after)
    }

    /**
     * The worker is done: free it, or start the opening's own request if it is still due. [change]
     * lands in the same step as that decision and both reach the listener in one call, so a
     * refusal followed by the request is one change of the gate - the ring - and never the idle
     * gate for a frame first. The listener then misses that refusal; the gate is what has to be
     * right, and [AdbStartupGatePolicy.overlay] puts the request's gate above every restore state.
     */
    private fun release(change: (AdbRescueSnapshot) -> AdbRescueSnapshot, after: (() -> Unit)? = null) {
        var pending: ((AdbRescueSnapshot) -> AdbRescueSnapshot)? = change
        var then = after
        while (true) {
            val auto = synchronized(lock) {
                pending?.let { current = it(current) }
                pending = null
                held = null
                opening = null
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
            pending = askChain()
        }
    }

    /** A request, and the follow-up if one is due: the last one's outcome. */
    private fun askChain(): (AdbRescueSnapshot) -> AdbRescueSnapshot {
        var next: Next = askOnce()
        while (next is Next.FollowUp) next = askOnce()
        return (next as Next.Done).change
    }

    private sealed interface Next {
        class Done(val change: (AdbRescueSnapshot) -> AdbRescueSnapshot) : Next
        data object FollowUp : Next
    }

    private fun done(change: (AdbRescueSnapshot) -> AdbRescueSnapshot) = Next.Done(change)

    /** One request, from the switch reading to its end: its outcome, or the follow-up. */
    private fun askOnce(): Next {
        if (leaving()) return done { AdbRescuePolicy.refused(it) }
        // Re-read at the moment of sending: a request is a queue entry the car keeps whatever
        // happens to this app, and one that can never be drawn is only a stuck queue.
        val switch = system.systemSwitch()
        if (switch == AdbSystemSwitch.DISABLED) return done { AdbRescuePolicy.systemSwitchOff(it) }
        val now = system.wallMs()
        val before = store.load()
        val log = AdbAskLog(AdbRescuePolicy.keep(before.times, now), before.total + 1, now)
        if (!store.save(log)) {
            return done { AdbRescuePolicy.refused(it).copy(lastFailure = AdbRescuePolicy.NOT_RECORDED) }
        }
        val cancel = AdbRequestCancel()
        val leftAlready = synchronized(lock) {
            if (!stopped) opening = cancel
            stopped
        }
        if (leftAlready) return done { AdbRescuePolicy.refused(it) }
        publish { AdbRescuePolicy.sent(it, switch, log) }
        val request = try {
            system.openRequest(cancel)
        } catch (error: Exception) {
            // Left during the handshake: the opening was stopped, and that is not a failure.
            if (leaving() || cancel.isCancelled) return done { AdbRescuePolicy.refused(it) }
            return done(classify(system.requestFailure(error)))
        }
        val leaving = synchronized(lock) {
            opening = null
            if (!stopped) held = request
            stopped
        }
        if (leaving) {
            request.close()
            return done { AdbRescuePolicy.refused(it) }
        }
        if (request.alreadyTrusted) {
            request.close()
            return done(classify(AdbCheckResult(AdbCheckOutcome.TRUSTED)))
        }
        val ending = try {
            hold(request)
        } finally {
            request.close()
            synchronized(lock) { held = null }
        }
        return when (ending) {
            Ending.Approved -> afterApproval()
            is Ending.Trusted -> done(classify(ending.look))
            Ending.SwitchOff -> done { AdbRescuePolicy.systemSwitchOff(it) }
            Ending.TimedOut -> done { AdbRescuePolicy.refused(it).copy(lastFailure = AdbRescuePolicy.NO_ANSWER) }
            Ending.Left -> done { AdbRescuePolicy.refused(it) }
            is Ending.Lost -> {
                val look = system.passiveCheck()
                done(
                    classify(
                        if (look.outcome == AdbCheckOutcome.AUTHORIZATION_REQUIRED) {
                            AdbCheckResult(look.outcome, AdbRescuePolicy.failureLabel(ending.error))
                        } else {
                            look
                        },
                    ),
                )
            }
        }
    }

    /**
     * The request's transport is held until the driver's answer arrives on it, an approval of an
     * earlier request is found by a look, the switch goes off, adbd closes it, the app leaves or
     * the time runs out. A look that does not find the key trusted - refused, or a timeout of its
     * own - changes nothing: the prompt may still be on the screen.
     */
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
            if (look.outcome == AdbCheckOutcome.TRUSTED) return Ending.Trusted(look)
        }
    }

    /**
     * adbd let the asking transport in. Only «always allow» makes the key trusted on the next
     * connection, and the framework stores it after the dialog's answer - late on a busy unit - so
     * the looks run until [AdbRescuePolicy.APPROVAL_LOOKS_MS]' last before the box counts as missed.
     */
    private fun afterApproval(): Next {
        val start = system.elapsedMs()
        for (at in AdbRescuePolicy.APPROVAL_LOOKS_MS) {
            val wait = start + at - system.elapsedMs()
            if (wait > 0) system.sleep(wait)
            val result = system.passiveCheck()
            if (result.outcome != AdbCheckOutcome.AUTHORIZATION_REQUIRED) return done(classify(result))
        }
        val again = synchronized(lock) {
            val due = inFront() && !stopped && followedIn != openings && underCap()
            if (due) {
                followedIn = openings
                current = AdbRescuePolicy.asking(AdbRescuePolicy.refused(current).copy(alwaysAllowMissed = true))
            }
            due
        }
        // The follow-up's own «sent» is what the listener hears next; an outcome is heard once.
        return if (again) Next.FollowUp else done { AdbRescuePolicy.refused(it).copy(alwaysAllowMissed = true) }
    }

    /**
     * The app has left: a stop that was not a configuration change, or one that was and no screen
     * came back within [AdbRescuePolicy.RECREATE_MS] - an activity finished mid-rotation would
     * otherwise hold a request nobody watches for the full two minutes and keep the next opening
     * from counting as one.
     */
    private fun leaving(): Boolean = synchronized(lock) {
        if (!stopped && keepOpening && keepExpired()) {
            keepOpening = false
            foreground = false
            stopped = true
        }
        stopped
    }

    /** Under [lock]. */
    private fun keepExpired(): Boolean = system.elapsedMs() - keptAtMs > AdbRescuePolicy.RECREATE_MS

    /** A look's answer as a change, classified with a switch reading taken after it. */
    private fun classify(look: AdbCheckResult): (AdbRescueSnapshot) -> AdbRescueSnapshot {
        if (look.outcome == AdbCheckOutcome.TRUSTED) system.recordTrusted()
        val switch = system.systemSwitch()
        return { AdbRescuePolicy.afterCheck(it, look.outcome, switch, look.failure) }
    }

    /** A step the gate shows while the worker goes on: the request recorded and on its way. */
    private fun publish(change: (AdbRescueSnapshot) -> AdbRescueSnapshot) {
        synchronized(lock) { current = change(current) }
        listener()
    }

    private sealed interface Ending {
        data object Approved : Ending
        class Trusted(val look: AdbCheckResult) : Ending
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
