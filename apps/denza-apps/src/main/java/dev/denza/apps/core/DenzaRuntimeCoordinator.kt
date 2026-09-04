package dev.denza.apps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.adb.AdbRescuePhase

/** Restores every enabled feature without requiring an Activity to be opened. */
object DenzaRuntimeCoordinator {
    private const val TAG = "DenzaRuntime"
    private val lock = Any()
    private val cycleState = RuntimeRecoveryCycleState()
    private val completionListeners = mutableMapOf<Long, MutableList<() -> Unit>>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Enters the one process-wide recovery contour.
     *
     * Application, manifest-receiver, and SCREEN_ON signals merge while a cycle is active. A real
     * BOOT_COMPLETED may upgrade that cycle with ACC-whitelist authority; no other cause can.
     */
    internal fun bootstrap(
        context: Context,
        cause: RuntimeStartCause,
        onFinished: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        val userManager = app.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked != true) {
            Log.i(TAG, "recovery skipped while user storage is locked; cause=$cause")
            onFinished?.invoke()
            return
        }

        val decision = synchronized(lock) {
            cycleState.enter(cause).also { entered ->
                if (onFinished != null) {
                    completionListeners.getOrPut(entered.generation) { mutableListOf() }
                        .add(onFinished)
                }
            }
        }
        Log.i(
            TAG,
            "recovery cause=$cause generation=${decision.generation} " +
                "started=${decision.started} accWhitelist=${decision.mayRegisterAccWhitelist}",
        )

        if (!decision.started) {
            // The Application pass may already have proved trust by the time BOOT_COMPLETED is
            // delivered. Register immediately without spending a second handshake.
            onRepositoryChanged(app, decision.generation)
            return
        }

        attemptRecovery(app, decision.generation)
        RuntimeAutostartRetrySchedule.atMillis.drop(1).forEach { atMillis ->
            mainHandler.postDelayed(
                { attemptRecovery(app, decision.generation) },
                atMillis,
            )
        }
        mainHandler.postDelayed(
            { finish(decision.generation, "timeout") },
            RuntimeRecoveryServicePolicy.MAX_DURATION_MILLIS,
        )
    }

    fun recover(context: Context) {
        DenzaAppRepository.recoverEnabledFeatures(context.applicationContext)
    }

    private fun attemptRecovery(context: Context, generation: Long) {
        if (!isActive(generation)) return
        val runtimeAlreadyReconciled = synchronized(lock) {
            cycleState.isRuntimeReconciled(generation)
        }
        if (runtimeAlreadyReconciled) {
            onRepositoryChanged(context, generation)
            return
        }
        try {
            DenzaAppRepository.recoverAutostart(context) {
                onRepositoryChanged(context, generation)
            }
        } catch (error: RuntimeException) {
            // One broken Binder or feature must not abort the finite recovery window.
            Log.w(TAG, "autoload runtime recovery failed", error)
        }
    }

    private fun onRepositoryChanged(context: Context, generation: Long) {
        if (!isActive(generation)) return
        if (AdbRescueCoordinator.snapshot().phase != AdbRescuePhase.TRUSTED) return
        synchronized(lock) {
            cycleState.markRuntimeReconciled(generation)
        }

        val mayRegister = synchronized(lock) {
            cycleState.mayRegisterAccWhitelist(generation)
        }
        if (!mayRegister) {
            finish(generation, "runtime-ready")
            return
        }

        when (AccQuickBootSurvivalRegistrar.state()) {
            AccWhitelistRegistrationState.REGISTERED -> finish(generation, "runtime-and-acc-ready")
            AccWhitelistRegistrationState.REGISTERING -> Unit
            AccWhitelistRegistrationState.NOT_REGISTERED -> {
                AccQuickBootSurvivalRegistrar.ensureRegistered(context) { registered ->
                    if (registered) finish(generation, "runtime-and-acc-ready")
                }
            }
        }
    }

    private fun isActive(generation: Long): Boolean = synchronized(lock) {
        cycleState.isActive(generation)
    }

    private fun finish(generation: Long, reason: String) {
        val listeners = synchronized(lock) {
            if (!cycleState.finish(generation)) return
            completionListeners.remove(generation).orEmpty()
        }
        Log.i(TAG, "recovery finished generation=$generation reason=$reason")
        listeners.forEach { listener ->
            runCatching(listener).onFailure { error ->
                Log.w(TAG, "recovery completion listener failed", error)
            }
        }
    }
}

internal object RuntimeAutostartRetrySchedule {
    /** Absolute moments from cycle start; the bootstrap service itself is bounded to one minute. */
    val atMillis = listOf(0L, 4_000L, 8_000L, 16_000L, 32_000L)
}
