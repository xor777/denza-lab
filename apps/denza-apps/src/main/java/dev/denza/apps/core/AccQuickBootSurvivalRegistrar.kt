package dev.denza.apps.core

import android.content.Context
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors

internal enum class AccWhitelistRegistrationState {
    NOT_REGISTERED,
    REGISTERING,
    REGISTERED,
}

/** Process-local idempotency gate. A failed boot attempt is never turned into command spam. */
internal class AccWhitelistRegistrationGate {
    private var state = AccWhitelistRegistrationState.NOT_REGISTERED
    private var attempted = false

    @Synchronized
    fun begin(): Boolean {
        if (attempted || state != AccWhitelistRegistrationState.NOT_REGISTERED) return false
        attempted = true
        state = AccWhitelistRegistrationState.REGISTERING
        return true
    }

    @Synchronized
    fun complete(success: Boolean): AccWhitelistRegistrationState {
        check(state == AccWhitelistRegistrationState.REGISTERING)
        state = if (success) {
            AccWhitelistRegistrationState.REGISTERED
        } else {
            AccWhitelistRegistrationState.NOT_REGISTERED
        }
        return state
    }

    @Synchronized
    fun snapshot(): AccWhitelistRegistrationState = state
}

internal object AccWhitelistRegistrationPolicy {
    const val COMMAND = "service call accmodemanager 1 s16 dev.denza.apps"

    fun accepted(output: String): Boolean {
        val normalized = output.trim()
        if (normalized.isEmpty()) return false
        if (normalized.contains("exception", ignoreCase = true)) return false
        if (normalized.contains("permission denial", ignoreCase = true)) return false
        if (normalized.contains("not found", ignoreCase = true)) return false
        return normalized.contains("Result: Parcel", ignoreCase = true)
    }
}

/**
 * Registers the package in the firmware's in-memory ACC whitelist through the already trusted,
 * PASSIVE local ADB identity. This is not an ACC lock and does not keep the vehicle awake.
 */
internal object AccQuickBootSurvivalRegistrar {
    private const val TAG = "DenzaAccWhitelist"
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = AccWhitelistRegistrationGate()

    @Volatile
    private var lastFailure: String? = null

    fun state(): AccWhitelistRegistrationState = gate.snapshot()

    fun diagnostic(): String =
        "state=${gate.snapshot().name.lowercase().replace('_', '-')}; " +
            "lastFailure=${lastFailure ?: "—"}"

    fun ensureRegistered(context: Context, onFinished: (Boolean) -> Unit) {
        when (gate.snapshot()) {
            AccWhitelistRegistrationState.REGISTERED -> {
                onFinished(true)
                return
            }
            AccWhitelistRegistrationState.REGISTERING -> {
                onFinished(false)
                return
            }
            AccWhitelistRegistrationState.NOT_REGISTERED -> Unit
        }
        if (!gate.begin()) {
            onFinished(gate.snapshot() == AccWhitelistRegistrationState.REGISTERED)
            return
        }

        val app = context.applicationContext
        executor.execute {
            val success = try {
                val output = DenzaLocalAdb.client(app).shell(AccWhitelistRegistrationPolicy.COMMAND)
                AccWhitelistRegistrationPolicy.accepted(output).also { accepted ->
                    lastFailure = if (accepted) null else "unexpected accmodemanager response"
                }
            } catch (error: Exception) {
                lastFailure = error.javaClass.simpleName.ifBlank { "UnknownFailure" }
                Log.w(TAG, "ACC whitelist registration failed: ${error.javaClass.simpleName}")
                false
            }
            val state = gate.complete(success)
            if (success) {
                Log.i(TAG, "ACC whitelist registration confirmed")
            } else {
                Log.w(TAG, "ACC whitelist registration was not confirmed; state=$state")
            }
            onFinished(success)
        }
    }
}
