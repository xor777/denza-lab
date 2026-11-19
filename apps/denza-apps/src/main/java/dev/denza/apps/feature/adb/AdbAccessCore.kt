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

    /**
     * A request that could not be sent, as the phase it leaves: UNAVAILABLE or ERROR. An opening
     * that ended before its public key was written throws [AdbKeyNotSentException].
     */
    fun requestFailure(error: Throwable): AdbCheckResult

    fun systemSwitch(): AdbSystemSwitch
    fun wallMs(): Long
    fun elapsedMs(): Long
    fun sleep(ms: Long)
    fun recordTrusted()
}

/** A request that ended before its public key was written: nothing is in the car's queue. */
internal class AdbKeyNotSentException(cause: Throwable) : IOException(cause)

/** Every request sent, so the daily cap survives the process and the reboot. */
internal data class AdbAskLog(
    /** Wall times of the recent requests, oldest first: [AdbRescuePolicy.recent] keeps them. */
    val times: List<Long> = emptyList(),
    val total: Int = 0,
    val lastAtMs: Long = 0L,
    /**
     * 0.7.1 left a request out in the last day, and its prompt may still be on the car's screen:
     * the next automatic request is not sent on top of it. Read once from 0.7.1's own keys.
     */
    val legacyPending: Boolean = false,
)

/** 0.7.1 kept the last request's time, a count and a pending flag; the log starts from them, once. */
internal object AdbAskLegacy {
    fun from071(total: Int, lastAtMs: Long, requestPending: Boolean, nowMs: Long): AdbAskLog {
        val times = if (lastAtMs > 0L) listOf(lastAtMs) else emptyList()
        return AdbAskLog(
            times = times,
            total = total,
            lastAtMs = lastAtMs,
            legacyPending = requestPending && AdbRescuePolicy.recent(times, nowMs).isNotEmpty(),
        )
    }
}

internal interface AdbAskStore {
    fun load(): AdbAskLog

    /** Synchronous and durable: a request is never sent unless it is recorded first. */
    fun save(log: AdbAskLog): Boolean
}

private typealias Change = (AdbRescueSnapshot) -> AdbRescueSnapshot

/**
 * Who may offer the key to the car, when, and how a request is held until the driver answers.
 *
 * One loop on one worker runs everything that talks to adbd, so there is never more than one
 * request outstanding and never a check beside it. Callers only say what they want - a look, a
 * request, an opening - and the loop picks the next job from what is wanted and what the phase
 * is. A request is sent only from a foreground opening of the app, or from a press: background
 * work - autoload, the screen waking, a feature's channel, the restore - only ever looks passively.
 *
 * - Every request is recorded before it is sent and counts toward [AdbRescuePolicy.DAILY_ASKS].
 *   The cap stops the automatic ones - an opening's own, and the one follow-up an opening may send
 *   after an approval without the box - and never a press of the gate's or the panel's control.
 * - The request is held up to [AdbRescuePolicy.HOLD_MS]. adbd answers an approval on the transport
 *   that asked; a passive look every [AdbRescuePolicy.LOOK_MS] notices an approval of an earlier
 *   request of the same key, whose transport is gone, and one more look at the deadline notices
 *   the approval that came in its last seconds. A look that times out is noise, not an answer.
 * - An approval is followed by looks over [AdbRescuePolicy.APPROVAL_LOOKS_MS]. A look that does
 *   not answer is looked at again; only a refusal from [AdbRescuePolicy.MISSED_BOX_FROM_MS] on is
 *   an approval without «always allow»: the gate shows that box, and the next request goes out at
 *   once - once an opening.
 * - While a screen is in front and the key is not trusted, a passive look repeats every
 *   [AdbRescuePolicy.IDLE_LOOK_MS], whatever the gate shows: trust that arrives by any route - an
 *   earlier prompt approved with the box, the port coming back - takes the gate down by itself.
 *   It stops when the screen does.
 * - Leaving the foreground, the switch going off, the time running out and the car answering all
 *   close the transport; leaving during the handshake stops it before the key is written. A
 *   configuration change is not leaving.
 * - The listener hears each change of [current] once, and the worker is released whatever throws.
 */
internal class AdbAccessCore(
    private val system: AdbAccessSystem,
    private val store: AdbAskStore,
    private val worker: (Runnable) -> Unit,
    /** Runs a task on the worker after a delay: the idle look's clock. */
    private val timer: (Long, Runnable) -> Unit,
    trustedBefore: Boolean,
    private val listener: () -> Unit,
) {
    private val lock = Any()

    /** What the listener has heard: [state] as of the last announcement. */
    @Volatile
    var current: AdbRescueSnapshot
        private set

    /** The loop's own reading, ahead of [current] between a job's steps. */
    private var state: AdbRescueSnapshot

    /** The loop is on the worker. */
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

    /** The opening an approval without the box left a follow-up request due in. */
    private var followUpIn = -1

    /** What callers want of the loop; each is taken when the loop picks it. */
    private var askWanted = false
    private var lookWanted = false
    private var pressed = false
    private val afters = mutableListOf<() -> Unit>()

    /** The idle look armed last; any older one finds itself stale and does nothing. */
    private var idleTicket = 0

    init {
        val log = store.load()
        state = AdbRescueSnapshot(attemptCount = log.total, lastAttemptAtMillis = log.lastAtMs, trustedBefore = trustedBefore)
        current = state
    }

    /** Requests recorded in the last 24 hours, counted now: the technical page's. */
    fun requestsLastDay(): Int = AdbRescuePolicy.recent(store.load().times, system.wallMs()).size

    /**
     * A passive look; [after] runs once the loop has nothing left to do, so it always sees a
     * settled phase. Looks asked for while one is in flight are one more look, never dropped.
     * [shown] is a press: the gate turns its ring for it.
     */
    fun check(shown: Boolean = false, after: (() -> Unit)? = null) {
        synchronized(lock) {
            lookWanted = true
            after?.let { afters += it }
            if (shown && state.phase != AdbRescuePhase.TRUSTED && state.phase != AdbRescuePhase.AWAITING_CONFIRMATION) {
                pressed = true
                state = AdbRescuePolicy.checking(state)
            }
        }
        announceThenPump()
    }

    /** The gate's control: one request now, whatever the cap, while nothing else is out. */
    fun ask() {
        synchronized(lock) {
            if (!state.canAsk || held != null || opening != null) return
            askWanted = true
        }
        pump()
    }

    /**
     * The app came to the front. A new opening owns one request of its own on a refused key, and
     * looks at every other phase but TRUSTED.
     */
    fun foregroundStarted() {
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
            if (state.phase != AdbRescuePhase.TRUSTED) lookWanted = true
        }
        pump()
    }

    /**
     * The app left the front. A request in flight is closed - the driver cannot answer a prompt
     * they are not looking at, and the next opening asks again - and one still being opened is
     * stopped before its key is written. The idle look stops. A configuration change keeps both the
     * opening and the request: a rotation never sends a second one.
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
            idleTicket++
            if (!busy) return
            stopped = true
            closing = held
            cancelling = opening
        }
        cancelling?.cancel()
        closing?.close()
    }

    /** Under [lock]: a screen is in front, or a configuration change is still bringing one back. */
    private fun inFront(): Boolean = foreground && !(keepOpening && keepExpired())

    /** The change made on the caller's thread reaches the listener, and the loop runs even if the listener throws. */
    private fun announceThenPump() {
        try {
            announce()
        } finally {
            pump()
        }
    }

    /** [state] reaches the listener if it is not what the listener heard last. */
    private fun announce() {
        val changed = synchronized(lock) {
            if (state == current) {
                false
            } else {
                current = state
                true
            }
        }
        if (changed) listener()
    }

    /** Starts the loop unless it runs: a running loop picks up whatever was just wanted. */
    private fun pump() {
        synchronized(lock) {
            if (busy) return
            busy = true
        }
        worker(Runnable { guarded { loop() } })
    }

    private enum class Job { ASK_AUTO, ASK_PRESSED, LOOK }

    /**
     * Under [lock]: the next job, in the order the gate needs them. A request the opening is owed
     * comes first - its own handshake is the look - then a press, then a look. Whether an
     * automatic request may go out under the cap is not decided here but in [reserve].
     */
    private fun pick(): Job? {
        stopped = false
        if (state.canAsk && inFront()) {
            if (askedIn != openings) {
                askedIn = openings
                return Job.ASK_AUTO
            }
            if (followUpIn == openings) {
                followUpIn = -1
                return Job.ASK_AUTO
            }
        }
        if (askWanted) {
            askWanted = false
            if (state.canAsk) {
                // A press is this opening's request: the opening sends none of its own after it.
                askedIn = openings
                return Job.ASK_PRESSED
            }
        }
        if (lookWanted) {
            lookWanted = false
            if (state.phase == AdbRescuePhase.UNKNOWN || pressed) state = AdbRescuePolicy.checking(state)
            pressed = false
            return Job.LOOK
        }
        return null
    }

    /**
     * The worker's loop: apply a job's outcome and pick the next in one step, so a refusal that
     * leads straight to a request is one change of the gate - the ring - and never the idle gate
     * for a frame first. A request announces itself when it is sent.
     */
    private fun loop() {
        var change: Change? = null
        while (true) {
            var then: List<() -> Unit> = emptyList()
            val job = synchronized(lock) {
                change?.let { state = it(state) }
                held = null
                opening = null
                pick().also {
                    if (it == null) {
                        busy = false
                        then = afters.toList()
                        afters.clear()
                    }
                }
            }
            if (job != Job.ASK_AUTO && job != Job.ASK_PRESSED) announce()
            if (job == null) {
                then.forEach { it() }
                armIdle()
                return
            }
            change = when (job) {
                Job.ASK_AUTO -> askOnce(automatic = true)
                Job.ASK_PRESSED -> askOnce(automatic = false)
                Job.LOOK -> classify(system.passiveCheck())
            }
        }
    }

    /** The next idle look, while a screen is in front of a key that is not trusted. */
    private fun armIdle() {
        val ticket = synchronized(lock) {
            if (busy || !inFront() || state.phase == AdbRescuePhase.TRUSTED) return
            ++idleTicket
        }
        timer(AdbRescuePolicy.IDLE_LOOK_MS, Runnable { idleLook(ticket) })
    }

    private fun idleLook(ticket: Int) {
        synchronized(lock) {
            if (ticket != idleTicket || !inFront() || state.phase == AdbRescuePhase.TRUSTED) return
            lookWanted = true
        }
        pump()
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
                state = AdbRescuePolicy.afterCheck(state, AdbCheckOutcome.ERROR, state.systemSwitch,
                    AdbRescuePolicy.failureLabel(error))
            }
            runCatching { closing?.close() }
            // The listener may be what threw; the snapshot is right either way.
            runCatching { announce() }
            runCatching { armIdle() }
        }
    }

    /** One request, from the switch reading to its end: its outcome. */
    private fun askOnce(automatic: Boolean): Change {
        if (leaving()) return { AdbRescuePolicy.refused(it) }
        // Re-read at the moment of sending: a request is a queue entry the car keeps whatever
        // happens to this app, and one that can never be drawn is only a stuck queue.
        val switch = system.systemSwitch()
        if (switch == AdbSystemSwitch.DISABLED) return { AdbRescuePolicy.systemSwitchOff(it) }
        val made = when (val reserved = reserve(automatic)) {
            Reserved.Declined -> return { AdbRescuePolicy.refused(it) }
            Reserved.NotRecorded -> return { AdbRescuePolicy.refused(it).copy(lastFailure = AdbRescuePolicy.NOT_RECORDED) }
            is Reserved.Made -> reserved
        }
        val cancel = AdbRequestCancel()
        val leftAlready = synchronized(lock) {
            // The request's own handshake is the look a caller wanted.
            lookWanted = false
            if (!stopped) opening = cancel
            stopped
        }
        if (leftAlready) {
            release(made)
            return { AdbRescuePolicy.refused(it) }
        }
        publish { AdbRescuePolicy.sent(it, switch, made.log) }
        val request = try {
            system.openRequest(cancel)
        } catch (error: Exception) {
            val cause = if (error is AdbKeyNotSentException) {
                release(made)
                error.cause ?: error
            } else {
                error
            }
            // Left during the handshake: the opening was stopped, and that is not a failure.
            if (leaving() || cancel.isCancelled) return { AdbRescuePolicy.refused(it) }
            return classify(system.requestFailure(cause))
        }
        if (request.alreadyTrusted) {
            request.close()
            release(made)
            return classify(AdbCheckResult(AdbCheckOutcome.TRUSTED))
        }
        val leaving = synchronized(lock) {
            opening = null
            if (!stopped) held = request
            stopped
        }
        if (leaving) {
            request.close()
            return { AdbRescuePolicy.refused(it) }
        }
        val ending = try {
            hold(request)
        } finally {
            request.close()
            synchronized(lock) { held = null }
        }
        return when (ending) {
            Ending.Approved -> afterApproval()
            is Ending.Trusted -> classify(ending.look)
            Ending.SwitchOff -> { snapshot -> AdbRescuePolicy.systemSwitchOff(snapshot) }
            Ending.TimedOut -> { snapshot -> AdbRescuePolicy.refused(snapshot).copy(lastFailure = AdbRescuePolicy.NO_ANSWER) }
            Ending.Left -> { snapshot -> AdbRescuePolicy.refused(snapshot) }
            is Ending.Lost -> {
                val look = system.passiveCheck()
                classify(
                    if (look.outcome == AdbCheckOutcome.AUTHORIZATION_REQUIRED) {
                        AdbCheckResult(look.outcome, AdbRescuePolicy.failureLabel(ending.error))
                    } else {
                        look
                    },
                )
            }
        }
    }

    /**
     * The request's transport is held until the driver's answer arrives on it, an approval of an
     * earlier request is found by a look, the switch goes off, adbd closes it, the app leaves or
     * the time runs out. A look that does not find the key trusted - refused, or a timeout of its
     * own - changes nothing: the prompt may still be on the screen. At the deadline the transport
     * is read once more and the key looked at once more before the hold says nobody answered.
     */
    private fun hold(request: AdbHeldRequest): Ending {
        val deadline = system.elapsedMs() + AdbRescuePolicy.HOLD_MS
        var nextLook = system.elapsedMs() + AdbRescuePolicy.LOOK_MS
        while (true) {
            if (leaving()) return Ending.Left
            val now = system.elapsedMs()
            if (now >= deadline) return lastLook(request)
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

    /** The deadline: an answer already on the transport, or trust a look finds, before «no answer». */
    private fun lastLook(request: AdbHeldRequest): Ending {
        val approved = try {
            request.awaitApproval(1)
        } catch (error: IOException) {
            return if (leaving()) Ending.Left else Ending.Lost(error)
        }
        if (approved) return Ending.Approved
        val look = system.passiveCheck()
        return if (look.outcome == AdbCheckOutcome.TRUSTED) Ending.Trusted(look) else Ending.TimedOut
    }

    /**
     * adbd let the asking transport in. Only «always allow» makes the key trusted on the next
     * connection, and the framework stores it after the dialog's answer - late on a busy unit. A look
     * that finds it trusted ends this; a look that does not answer is looked at again; a refusal
     * means the box was missed only from [AdbRescuePolicy.MISSED_BOX_FROM_MS] on. A window whose
     * looks never answer leaves the last of them, and the idle looks go on from there.
     */
    private fun afterApproval(): Change {
        val start = system.elapsedMs()
        var last = AdbCheckResult(AdbCheckOutcome.ERROR)
        for (at in AdbRescuePolicy.APPROVAL_LOOKS_MS) {
            val wait = start + at - system.elapsedMs()
            if (wait > 0) system.sleep(wait)
            val look = system.passiveCheck()
            if (look.outcome == AdbCheckOutcome.TRUSTED) return classify(look)
            if (look.outcome == AdbCheckOutcome.AUTHORIZATION_REQUIRED && at >= AdbRescuePolicy.MISSED_BOX_FROM_MS) {
                synchronized(lock) {
                    // The follow-up is the loop's to send, once an opening, if a screen is still in front.
                    if (followedIn != openings) {
                        followedIn = openings
                        followUpIn = openings
                    }
                }
                val switch = system.systemSwitch()
                return { AdbRescuePolicy.afterCheck(it, look.outcome, switch, look.failure).copy(alwaysAllowMissed = true) }
            }
            last = look
        }
        return classify(last)
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

    private sealed interface Reserved {
        /** The request's entry, recorded; [previous] is the log without it, [wasAsked] the snapshot's flag. */
        class Made(val atMs: Long, val log: AdbAskLog, val previous: AdbAskLog, val wasAsked: Boolean) : Reserved
        data object Declined : Reserved
        data object NotRecorded : Reserved
    }

    /**
     * The one place a request is decided and recorded: for an automatic one the cap and a 0.7.1
     * request still on the car's screen, then the time and the log's entry - all against one
     * reading of the clock, on the worker. Nothing goes out that is not recorded first.
     */
    private fun reserve(automatic: Boolean): Reserved {
        val now = system.wallMs()
        val previous = store.load()
        if (automatic) {
            if (previous.legacyPending) {
                store.save(previous.copy(legacyPending = false))
                return Reserved.Declined
            }
            if (AdbRescuePolicy.recent(previous.times, now).size >= AdbRescuePolicy.DAILY_ASKS) return Reserved.Declined
        }
        val log = AdbAskLog(AdbRescuePolicy.keep(previous.times, now), previous.total + 1, now)
        if (!store.save(log)) return Reserved.NotRecorded
        val wasAsked = synchronized(lock) { state.asked }
        return Reserved.Made(now, log, previous, wasAsked)
    }

    /**
     * A reservation whose key never reached the car - already trusted, the opening stopped or
     * failed before the write - leaves the log, the count and the snapshot as they were.
     */
    private fun release(made: Reserved.Made) {
        val log = store.load()
        val times = log.times.toMutableList().apply { remove(made.atMs) }
        val released = log.copy(times = times, total = maxOf(0, log.total - 1), lastAtMs = made.previous.lastAtMs)
        if (!store.save(released)) return
        synchronized(lock) {
            state = state.copy(attemptCount = released.total, lastAttemptAtMillis = released.lastAtMs, asked = made.wasAsked)
        }
    }

    /** A look's answer as a change, classified with a switch reading taken after it. */
    private fun classify(look: AdbCheckResult): Change {
        if (look.outcome == AdbCheckOutcome.TRUSTED) {
            system.recordTrusted()
            // Whatever 0.7.1 left out was answered or no longer matters.
            store.load().takeIf { it.legacyPending }?.let { store.save(it.copy(legacyPending = false)) }
        }
        val switch = system.systemSwitch()
        return { AdbRescuePolicy.afterCheck(it, look.outcome, switch, look.failure) }
    }

    /** A step the gate shows while the worker goes on: the request recorded and on its way. */
    private fun publish(change: Change) {
        synchronized(lock) { state = change(state) }
        announce()
    }

    private sealed interface Ending {
        data object Approved : Ending
        class Trusted(val look: AdbCheckResult) : Ending
        data object SwitchOff : Ending
        data object TimedOut : Ending
        data object Left : Ending
        class Lost(val error: IOException) : Ending
    }
}
