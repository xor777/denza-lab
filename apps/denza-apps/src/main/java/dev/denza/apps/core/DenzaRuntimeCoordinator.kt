package dev.denza.apps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import dev.denza.apps.DenzaAppRepository
import java.util.concurrent.atomic.AtomicBoolean

/** Restores the desired runtime without requiring an Activity to be opened. */
object DenzaRuntimeCoordinator {
    private const val TAG = "DenzaRuntime"
    private val retryWindowActive = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Entry point for Android and BYD autoload.
     *
     * The first pass is immediate. Later passes are deliberately finite: they cover services that
     * become ready shortly after Android starts without turning this into a permanent keep-alive.
     */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        val userManager = app.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked != true) return

        attemptAutostartRecovery(app)
        if (retryWindowActive.compareAndSet(false, true)) {
            scheduleRetry(app, index = 0)
        }
    }

    fun recover(context: Context) {
        DenzaAppRepository.recoverEnabledFeatures(context.applicationContext)
    }

    private fun scheduleRetry(context: Context, index: Int) {
        val delays = RuntimeAutostartRetrySchedule.delaysMillis
        if (index >= delays.size) {
            retryWindowActive.set(false)
            return
        }
        val accepted = mainHandler.postDelayed(
            {
                attemptAutostartRecovery(context)
                scheduleRetry(context, index + 1)
            },
            delays[index],
        )
        if (!accepted) retryWindowActive.set(false)
    }

    private fun attemptAutostartRecovery(context: Context) {
        try {
            DenzaAppRepository.recoverAutostart(context)
        } catch (error: RuntimeException) {
            Log.w(TAG, "autoload runtime recovery failed", error)
        }
    }
}

internal object RuntimeAutostartRetrySchedule {
    /** Relative delays: the final recovery pass happens one minute after process start. */
    val delaysMillis = listOf(4_000L, 8_000L, 16_000L, 32_000L)
}
