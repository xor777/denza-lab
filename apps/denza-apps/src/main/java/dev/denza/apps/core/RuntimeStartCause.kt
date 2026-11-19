package dev.denza.apps.core

/** Why the main-process recovery contour was entered. */
internal enum class RuntimeStartCause(
    val needsForegroundBootstrap: Boolean,
) {
    PROCESS_START(needsForegroundBootstrap = false),
    BOOT_COMPLETED(needsForegroundBootstrap = true),
    PACKAGE_REPLACED(needsForegroundBootstrap = true),
    SCREEN_ON(needsForegroundBootstrap = false),
}

internal data class RuntimeRecoveryCycleDecision(
    val generation: Long,
    val started: Boolean,
)

/** Pure state behind the process-wide single-flight recovery contour. */
internal class RuntimeRecoveryCycleState {
    private var generation = 0L
    private var active = false

    fun enter(): RuntimeRecoveryCycleDecision {
        val started = !active
        if (started) {
            generation += 1L
            active = true
        }
        return RuntimeRecoveryCycleDecision(generation = generation, started = started)
    }

    fun isActive(expectedGeneration: Long): Boolean =
        active && generation == expectedGeneration

    /** The cycle running now, if one is. */
    fun activeGeneration(): Long? = generation.takeIf { active }

    fun finish(expectedGeneration: Long): Boolean {
        if (!isActive(expectedGeneration)) return false
        active = false
        return true
    }
}

/**
 * One startup pass at a time, and the recovery cycle that was running when it began ends when it is
 * done: whoever started the pass - the cycle's own attempt, or the listener that heard the key
 * trusted - there is exactly one pass a cycle, and no attempt after it runs another.
 */
internal class RuntimePasses(
    private val runningCycle: () -> Long?,
    private val cycleDone: (Long) -> Unit,
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs [pass] unless one is running; false when it did not. */
    fun run(pass: () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val cycle = runningCycle()
        try {
            pass()
        } finally {
            running.set(false)
            cycle?.let(cycleDone)
        }
        return true
    }
}

internal object RuntimeRecoveryServicePolicy {
    const val MAX_DURATION_MILLIS = 60_000L
}
