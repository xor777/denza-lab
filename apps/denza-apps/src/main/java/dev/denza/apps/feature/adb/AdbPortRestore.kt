package dev.denza.apps.feature.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Keeping classic ADB (port 5555) reachable after a reboot of the head unit.
 *
 * Owners report that builds from mid-2026 close 5555 after every reboot, and everything behind the
 * startup gate talks to adbd over 5555 alone. Android's wireless debugging can reopen it, but only
 * for an app that already holds WRITE_SECURE_SETTINGS, and that permission can only be granted
 * while the old port still answers (docs/adb-authorization-recovery.md, "Port 5555 after a reboot,
 * and reopening it through wireless debugging"). So the part that has to be in place before such an
 * update lives here and runs on every runtime pass with a trusted shell. Nothing here reopens the
 * port yet.
 */

/** What this process did about [Manifest.permission.WRITE_SECURE_SETTINGS]. */
enum class SecureSettingsGrant {
    /** Not looked at yet: no runtime pass has had a trusted shell in this process. */
    NOT_TRIED,

    /** Held before this process asked. */
    ALREADY_HELD,

    /** Granted by this process with `pm grant` over its own shell. */
    GRANTED,

    /** `pm grant` failed or answered, and the permission is still not held. */
    FAILED,
}

/** The last runtime pass's outcome, as the service report and a later restore read it. */
data class AdbPortRestoreState(
    val grant: SecureSettingsGrant = SecureSettingsGrant.NOT_TRIED,
    /** Why [SecureSettingsGrant.FAILED]: what `pm grant` said, or the transport failure. */
    val grantFailure: String? = null,
)

/** One pass over a trusted shell, free of Android so every outcome is testable. */
internal object AdbPortRestorePass {
    val PERMISSION: String = Manifest.permission.WRITE_SECURE_SETTINGS

    /** The only command that changes anything, and only when the permission is missing. */
    fun grantCommand(packageName: String): String = "pm grant $packageName $PERMISSION"

    /**
     * Holds the permission if it can. Never throws: a step that fails is recorded, not raised, so
     * it can never cost the runtime pass that runs it.
     */
    fun run(
        packageName: String,
        isHeld: () -> Boolean,
        shell: (String) -> String,
        previous: AdbPortRestoreState,
    ): AdbPortRestoreState = grant(packageName, isHeld, shell, previous)

    private fun grant(
        packageName: String,
        isHeld: () -> Boolean,
        shell: (String) -> String,
        previous: AdbPortRestoreState,
    ): AdbPortRestoreState {
        if (isHeld()) {
            // Granted by an earlier pass of this process: it stays the answer, not "held before".
            val grant = if (previous.grant == SecureSettingsGrant.GRANTED) {
                SecureSettingsGrant.GRANTED
            } else {
                SecureSettingsGrant.ALREADY_HELD
            }
            return previous.copy(grant = grant, grantFailure = null)
        }
        val answer = try {
            shell(grantCommand(packageName))
        } catch (error: Exception) {
            return previous.copy(grant = SecureSettingsGrant.FAILED, grantFailure = failureLabel(error))
        }
        return if (isHeld()) {
            previous.copy(grant = SecureSettingsGrant.GRANTED, grantFailure = null)
        } else {
            previous.copy(
                grant = SecureSettingsGrant.FAILED,
                grantFailure = firstLine(answer) ?: "pm grant ничего не ответил",
            )
        }
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)?.take(MAX_ANSWER)

    internal fun failureLabel(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        return root.javaClass.simpleName.ifBlank { "UnknownFailure" }
    }

    private const val MAX_ANSWER = 120
}

/** The rows of the service report's section, in the words the page shows. */
object AdbPortRestoreReport {
    const val TITLE = "Восстановление порта ADB"

    fun rows(permissionHeld: Boolean, state: AdbPortRestoreState): List<Pair<String, String>> =
        listOf("WRITE_SECURE_SETTINGS" to permission(permissionHeld, state))

    private fun permission(held: Boolean, state: AdbPortRestoreState): String = when {
        held && state.grant == SecureSettingsGrant.GRANTED -> "выдано приложением"
        held -> "выдано"
        state.grant == SecureSettingsGrant.FAILED -> "не выдано: ${state.grantFailure ?: "?"}"
        state.grant == SecureSettingsGrant.NOT_TRIED -> "не выдано, ждёт доступа к ADB"
        else -> "не выдано"
    }
}

/** Runs [AdbPortRestorePass] with the trusted shell, once per runtime pass, off the caller's thread. */
object AdbPortRestore {
    private const val TAG = "DenzaAdbPortRestore"
    private const val COMMAND_TIMEOUT_MS = 5_000

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    @Volatile
    private var current = AdbPortRestoreState()

    fun state(): AdbPortRestoreState = current

    fun isPermissionHeld(context: Context): Boolean =
        context.checkSelfPermission(AdbPortRestorePass.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * One pass, for a caller that already holds a trusted key; a pass already running absorbs this
     * one. [onChanged] runs on this object's thread when the pass is over.
     */
    fun prepare(context: Context, onChanged: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                val session = DenzaLocalAdb.client(app).openPersistentShell()
                try {
                    current = AdbPortRestorePass.run(
                        packageName = app.packageName,
                        isHeld = { isPermissionHeld(app) },
                        shell = { command -> session.shell(command, COMMAND_TIMEOUT_MS) },
                        previous = current,
                    )
                } finally {
                    session.close()
                }
            } catch (error: Exception) {
                Log.w(TAG, "ADB port restore pass failed", error)
            } finally {
                running.set(false)
            }
            onChanged()
        }
    }
}
