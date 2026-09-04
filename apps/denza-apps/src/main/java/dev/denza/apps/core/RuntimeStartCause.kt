package dev.denza.apps.core

/** Why the main-process recovery contour was entered. */
internal enum class RuntimeStartCause(
    val mayRegisterAccWhitelist: Boolean,
    val needsForegroundBootstrap: Boolean,
) {
    PROCESS_START(
        mayRegisterAccWhitelist = false,
        needsForegroundBootstrap = false,
    ),
    BOOT_COMPLETED(
        mayRegisterAccWhitelist = true,
        needsForegroundBootstrap = true,
    ),
    PACKAGE_REPLACED(
        mayRegisterAccWhitelist = false,
        needsForegroundBootstrap = true,
    ),
    SCREEN_ON(
        mayRegisterAccWhitelist = false,
        needsForegroundBootstrap = false,
    ),
}

internal data class RuntimeRecoveryCycleDecision(
    val generation: Long,
    val started: Boolean,
    val mayRegisterAccWhitelist: Boolean,
)

/** Pure state behind the process-wide single-flight recovery contour. */
internal class RuntimeRecoveryCycleState {
    private var generation = 0L
    private var active = false
    private var accWhitelistAllowed = false
    private var runtimeReconciled = false

    fun enter(cause: RuntimeStartCause): RuntimeRecoveryCycleDecision {
        val started = !active
        if (started) {
            generation += 1L
            active = true
            accWhitelistAllowed = false
            runtimeReconciled = false
        }
        // Application.onCreate normally runs before the manifest receiver. A genuine boot event
        // must therefore be able to upgrade that same cycle without scheduling another handshake.
        accWhitelistAllowed = accWhitelistAllowed || cause.mayRegisterAccWhitelist
        return RuntimeRecoveryCycleDecision(
            generation = generation,
            started = started,
            mayRegisterAccWhitelist = accWhitelistAllowed,
        )
    }

    fun isActive(expectedGeneration: Long): Boolean =
        active && generation == expectedGeneration

    fun mayRegisterAccWhitelist(expectedGeneration: Long): Boolean =
        isActive(expectedGeneration) && accWhitelistAllowed

    fun markRuntimeReconciled(expectedGeneration: Long): Boolean {
        if (!isActive(expectedGeneration)) return false
        runtimeReconciled = true
        return true
    }

    fun isRuntimeReconciled(expectedGeneration: Long): Boolean =
        isActive(expectedGeneration) && runtimeReconciled

    fun finish(expectedGeneration: Long): Boolean {
        if (!isActive(expectedGeneration)) return false
        active = false
        accWhitelistAllowed = false
        runtimeReconciled = false
        return true
    }
}

internal object RuntimeRecoveryServicePolicy {
    const val MAX_DURATION_MILLIS = 60_000L

    fun shouldStop(recovered: Boolean, elapsedMillis: Long): Boolean =
        recovered || elapsedMillis >= MAX_DURATION_MILLIS
}
