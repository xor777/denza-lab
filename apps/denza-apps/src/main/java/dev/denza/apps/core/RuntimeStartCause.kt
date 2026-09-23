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

    fun finish(expectedGeneration: Long): Boolean {
        if (!isActive(expectedGeneration)) return false
        active = false
        return true
    }
}

internal object RuntimeRecoveryServicePolicy {
    const val MAX_DURATION_MILLIS = 60_000L

    fun shouldStop(recovered: Boolean, elapsedMillis: Long): Boolean =
        recovered || elapsedMillis >= MAX_DURATION_MILLIS
}
