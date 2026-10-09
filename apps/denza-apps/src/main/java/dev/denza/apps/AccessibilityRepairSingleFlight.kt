package dev.denza.apps

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes every read/modify/rebind transaction on the shared accessibility setting. */
internal object AccessibilitySettingsMutationLock {
    private val lock = ReentrantLock(true)

    /**
     * True while the lock is held by a repair that ends with the split picker's service enabled and
     * owned - the very state a split open's picker lease asks for.
     *
     * Live 2026-09-24: after a sleep the process starts cold, the process-start recovery heals the
     * crashed services with three writes and 1 + 2 + 1 s of pauses, and the open that started the
     * process queued behind it for 4.6 of its 7.2 s (twice that day past its 10 s budget, leaving
     * two pickers and no apps). A lease that finds this set has nothing to wait for.
     */
    @Volatile
    var repairingSplitAccess: Boolean = false
        private set

    fun <T> withLock(ensuresSplitAccess: Boolean = false, block: () -> T): T = lock.withLock {
        if (!ensuresSplitAccess) return@withLock block()
        repairingSplitAccess = true
        try {
            block()
        } finally {
            repairingSplitAccess = false
        }
    }
}

/** Joins all current accessibility owners to one serialized system rebind. */
internal class AccessibilityRepairSingleFlight {
    private val lock = Any()
    private val callbacks = mutableListOf<(Throwable?) -> Unit>()
    private val wishes = mutableListOf<() -> Boolean>()

    @Volatile
    private var running = false

    fun join(callback: (Throwable?) -> Unit): Boolean = join(callback, { true })

    /**
     * Joins the repair under way, or starts one (true). A repair starting marks the slice that
     * shows it - the steering wheel's row reads [isRunning].
     */
    fun join(callback: (Throwable?) -> Unit, stillWanted: () -> Boolean): Boolean {
        val starting = synchronized(lock) {
            callbacks += callback
            wishes += stillWanted
            if (running) {
                false
            } else {
                running = true
                true
            }
        }
        if (starting) StateMarks.mark(StateSlice.NAVIGATION, "access repair")
        return starting
    }

    fun isStillWanted(): Boolean = synchronized(lock) { wishes.any { it() } }

    /**
     * Ends the repair. The slice that shows it is marked once it no longer counts as running and
     * before any owner's callback, so the read finds it settled and an owner's answer lands after.
     * The projection and HUD guidance are not marked here: each owner publishes its own outcome -
     * «Восстанавливаю доступ» while it runs, its outcome when it fails - and a read of the bare
     * setting in between would put the setting's own outcome over it, with its own press. The service
     * connecting or going marks them all ([StateMarks.accessibilityChanged]).
     */
    fun complete(failure: Throwable?) {
        val waiting = synchronized(lock) {
            running = false
            wishes.clear()
            callbacks.toList().also { callbacks.clear() }
        }
        StateMarks.mark(StateSlice.NAVIGATION, "access repaired")
        waiting.forEach { callback ->
            runCatching { callback(failure) }
        }
    }

    fun isRunning(): Boolean = running
}
