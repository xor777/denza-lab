package dev.denza.apps.feature.mirrors

/**
 * The monitor's one lock, and the run that holds it.
 *
 * Every decision that can issue a camera command is taken under this gate for one [MirrorRun]: a
 * poll's transition, a lamp or lever event's teardown, the deadline's teardown, the final hide of
 * a stop. A run is the monitor between one start and its stop. A thread of a stopped run - a poll
 * whose shell read came back after the stop, a lamp event already queued - finds its run gone and
 * changes nothing, not even the freshness of its successor's deadline.
 *
 * Freshness belongs to the run and is recorded only here, in the critical section that applies
 * what the read saw ([observe]). The deadline is looked at only here, with the clock sampled inside
 * the lock ([checkDeadline]). The watchdog therefore sees a poll's transition and its freshness
 * together or not at all: it can no longer take the timestamp of the read before a poll whose
 * transition is already applied, close that camera and say «Нет доступа» (round-2 finding 5,
 * 2026-11-19: the poll applied under the gate, released it, and recorded the read on the next
 * line).
 */
internal class MirrorTransitionGate {
    private val lock = Any()
    private var runs = 0L

    @Volatile private var current: MirrorRun? = null

    /** The current run's freshness; touched only under [lock], and only for [current]. */
    private var watch: MirrorObservationWatch? = null

    val isRunning: Boolean get() = current != null

    /** A new run whose deadline counts from [startedAtMs], or null while one is running. */
    fun start(startedAtMs: Long): MirrorRun? = synchronized(lock) {
        if (current != null) return@synchronized null
        runs += 1
        watch = MirrorObservationWatch(startedAtMs)
        MirrorRun(runs, this).also { current = it }
    }

    /** Ends the current run; [finalAction] is the last thing done under the lock. */
    fun stop(finalAction: () -> Unit) = synchronized(lock) {
        current = null
        watch = null
        finalAction()
    }

    fun isCurrent(run: MirrorRun): Boolean = current === run

    /** Runs [action] under the lock if [run] is still the current run. */
    fun runIf(run: MirrorRun, action: () -> Unit): Boolean = synchronized(lock) {
        if (current !== run) return@synchronized false
        action()
        true
    }

    /**
     * A read of the car that came back at [nowMs]: [apply] what it saw and record the read, in one
     * critical section. Nothing for a run that has stopped.
     */
    fun observe(run: MirrorRun, nowMs: Long, apply: () -> Unit): Boolean = synchronized(lock) {
        if (current !== run) return@synchronized false
        apply()
        watch?.read(nowMs)
        true
    }

    /**
     * A read that failed at [nowMs]. [withinDeadline] runs (under the lock) while the deadline has
     * not passed; past it, the status is the deadline's.
     */
    fun readFailed(run: MirrorRun, error: String, nowMs: Long, withinDeadline: () -> Unit): Boolean =
        synchronized(lock) {
            if (current !== run) return@synchronized false
            val runWatch = watch ?: return@synchronized false
            runWatch.failed(error)
            if (!runWatch.lost(nowMs)) withinDeadline()
            true
        }

    /**
     * The watchdog's look. [clock] is sampled once, inside the lock; [onLost] runs under the lock
     * with the last read's error when nothing has been read for [MirrorObservationWatch.DEADLINE_MS].
     */
    fun checkDeadline(run: MirrorRun, clock: () -> Long, onLost: (lastError: String?) -> Unit): Boolean =
        synchronized(lock) {
            if (current !== run) return@synchronized false
            val runWatch = watch ?: return@synchronized false
            if (!runWatch.lost(clock())) return@synchronized false
            onLost(runWatch.lastError)
            true
        }

    fun <T> read(action: () -> T): T = synchronized(lock, action)
}

/** One monitor run: from a start to its stop. */
internal class MirrorRun internal constructor(
    val id: Long,
    private val gate: MirrorTransitionGate,
) {
    fun isCurrent(): Boolean = gate.isCurrent(this)

    override fun toString(): String = "run $id"
}
