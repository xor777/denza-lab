package dev.denza.apps.feature.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.feature.cloud.CloudLinkReport
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
 * update lives here and runs on every runtime pass with a trusted shell. AdbRestoreManager uses
 * this same grant boundary, then reopens the port through stock wireless debugging when needed.
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

/**
 * What this car keeps port 5555 open with, as its own shell reads it. A null value is unset.
 *
 * Which of these holds the port across a full reboot on the 2605 image is an open question in the
 * findings doc, and a host ADB on the owner's car is not always at hand: the product reads them
 * itself, read-only, once per runtime pass.
 */
data class AdbPortReadout(
    val persistTcpPort: String?,
    val serviceTcpPort: String?,
    /** BYD's own switch for TCP ADB (`init.rc` sets `service.adb.tcp.port` from it). */
    val wirelessSwitch: String?,
    val wirelessSwitchPersisted: String?,
    /** Android's wireless debugging, the path a restore would take. */
    val adbWifiEnabled: String?,
) {
    companion object {
        const val PERSIST_TCP_PORT = "persist.adb.tcp.port"
        const val SERVICE_TCP_PORT = "service.adb.tcp.port"
        const val WIRELESS_SWITCH = "sys.connect.adb.wiress"
        const val WIRELESS_SWITCH_PERSISTED = "persist.sys.adb.wiress.enable"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

        private val PROPERTIES =
            listOf(PERSIST_TCP_PORT, SERVICE_TCP_PORT, WIRELESS_SWITCH, WIRELESS_SWITCH_PERSISTED)

        /** One command for all five, read-only: `getprop` and `settings get`, a `key=value` line each. */
        val COMMAND: String = (
            PROPERTIES.map { "echo \"$it=\$(getprop $it)\"" } +
                "echo \"$ADB_WIFI_ENABLED=\$(settings get global $ADB_WIFI_ENABLED)\""
            ).joinToString("; ")

        /** `getprop` prints nothing for an unset property; `settings get` prints `null`. */
        fun parse(output: String): AdbPortReadout {
            val values = output.lineSequence().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }.toMap()
            fun value(key: String): String? = values[key]?.takeUnless { it.isEmpty() }
            return AdbPortReadout(
                persistTcpPort = value(PERSIST_TCP_PORT),
                serviceTcpPort = value(SERVICE_TCP_PORT),
                wirelessSwitch = value(WIRELESS_SWITCH),
                wirelessSwitchPersisted = value(WIRELESS_SWITCH_PERSISTED),
                adbWifiEnabled = value(ADB_WIFI_ENABLED)?.takeUnless { it == "null" },
            )
        }
    }
}

/** The last runtime pass's outcome, as the service report and a later restore read it. */
data class AdbPortRestoreState(
    val grant: SecureSettingsGrant = SecureSettingsGrant.NOT_TRIED,
    /** Why [SecureSettingsGrant.FAILED]: what `pm grant` said, or the transport failure. */
    val grantFailure: String? = null,
    /** The last readout that came back; kept when a later read fails. */
    val readout: AdbPortReadout? = null,
    /** Why the last read did not come back; null when it did. */
    val readFailure: String? = null,
    /** When [readout] was read, on the elapsedRealtime clock. */
    val readAtMs: Long? = null,
)

/** One pass over a trusted shell, free of Android so every outcome is testable. */
internal object AdbPortRestorePass {
    val PERMISSION: String = Manifest.permission.WRITE_SECURE_SETTINGS

    /** The only command that changes anything, and only when the permission is missing. */
    fun grantCommand(packageName: String): String = "pm grant $packageName $PERMISSION"

    /**
     * Holds the permission if it can, then reads what keeps the port open. Never throws: a step that
     * fails is recorded, not raised, so it can never cost the runtime pass that runs it, and one
     * step failing does not skip the other.
     */
    fun run(
        packageName: String,
        isHeld: () -> Boolean,
        shell: (String) -> String,
        previous: AdbPortRestoreState,
        now: () -> Long,
    ): AdbPortRestoreState = read(shell, grant(packageName, isHeld, shell, previous), now)

    private fun read(
        shell: (String) -> String,
        state: AdbPortRestoreState,
        now: () -> Long,
    ): AdbPortRestoreState = try {
        val readout = AdbPortReadout.parse(shell(AdbPortReadout.COMMAND))
        state.copy(readout = readout, readFailure = null, readAtMs = now())
    } catch (error: Exception) {
        state.copy(readFailure = failureLabel(error))
    }

    internal fun grant(
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

    fun rows(
        permissionHeld: Boolean,
        state: AdbPortRestoreState,
        nowMs: Long,
    ): List<Pair<String, String>> {
        val readout = state.readout
        fun value(read: AdbPortReadout.() -> String?): String =
            if (readout == null) NOTHING else readout.read() ?: "не задано"
        return listOf(
            "WRITE_SECURE_SETTINGS" to permission(permissionHeld, state),
            AdbPortReadout.PERSIST_TCP_PORT to value { persistTcpPort },
            AdbPortReadout.SERVICE_TCP_PORT to value { serviceTcpPort },
            AdbPortReadout.WIRELESS_SWITCH to value { wirelessSwitch },
            AdbPortReadout.WIRELESS_SWITCH_PERSISTED to value { wirelessSwitchPersisted },
            AdbPortReadout.ADB_WIFI_ENABLED to value { adbWifiEnabled },
            "Прочитано" to read(state, nowMs),
        )
    }

    private fun read(state: AdbPortRestoreState, nowMs: Long): String {
        val ago = state.readAtMs?.let { "${CloudLinkReport.span(nowMs - it)} назад" }
        val failure = state.readFailure ?: return ago ?: "ещё не было"
        return if (ago == null) "ошибка: $failure" else "ошибка: $failure; показано прочитанное $ago"
    }

    private fun permission(held: Boolean, state: AdbPortRestoreState): String = when {
        held && state.grant == SecureSettingsGrant.GRANTED -> "выдано приложением"
        held -> "выдано"
        state.grant == SecureSettingsGrant.FAILED -> "не выдано: ${state.grantFailure ?: "?"}"
        state.grant == SecureSettingsGrant.NOT_TRIED -> "не выдано, ждёт доступа к ADB"
        else -> "не выдано"
    }

    private const val NOTHING = "—"
}

/**
 * Runs [AdbPortRestorePass] with the trusted shell, once per runtime pass, off the caller's thread.
 * The outcome is kept in memory only; the next process reads again.
 */
object AdbPortRestore {
    private const val TAG = "DenzaAdbPortRestore"
    private const val COMMAND_TIMEOUT_MS = 5_000

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val permissionLock = Any()

    @Volatile
    private var current = AdbPortRestoreState()

    fun state(): AdbPortRestoreState = current

    fun isPermissionHeld(context: Context): Boolean =
        context.checkSelfPermission(AdbPortRestorePass.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The restore attempt and runtime preparation share one idempotent grant transaction. */
    internal fun ensurePermission(context: Context, abandoned: () -> Boolean) = synchronized(permissionLock) {
        if (abandoned()) return@synchronized
        current = AdbPortRestorePass.grant(context.packageName,
            { isPermissionHeld(context) },
            { command ->
                check(!abandoned()) { "Permission preparation cancelled" }
                DenzaLocalAdb.client(context).shell(command, COMMAND_TIMEOUT_MS)
            }, current)
    }

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
                    synchronized(permissionLock) { current = AdbPortRestorePass.run(
                        packageName = app.packageName,
                        isHeld = { isPermissionHeld(app) },
                        shell = { command -> session.shell(command, COMMAND_TIMEOUT_MS) },
                        previous = current,
                        now = SystemClock::elapsedRealtime,
                    ) }
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
