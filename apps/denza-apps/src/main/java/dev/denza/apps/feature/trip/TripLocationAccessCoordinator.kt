package dev.denza.apps.feature.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-heals the trip panel's location permission the same way the HUD self-heals
 * notification access (see feature/hud/HudNotificationAccessCoordinator): on panel
 * start, if ACCESS_FINE_LOCATION is not granted it runs `pm grant` over the local
 * ADB channel via [LocalAdbClient]. It is fail-closed and non-blocking — the panel
 * comes up in IMU-only mode meanwhile and picks up GNSS once the grant lands. If
 * the ADB channel fails, the caller falls back to the runtime permission dialog.
 */
internal enum class TripLocationAccessResult {
    ALREADY_GRANTED,
    GRANTED,
}

/** Pure grant/verify decision logic, JVM-testable with injected callbacks. */
internal class TripLocationAccessRepair(
    private val isGranted: () -> Boolean,
    private val grant: () -> Unit,
) {
    fun ensure(): TripLocationAccessResult {
        if (isGranted()) {
            return TripLocationAccessResult.ALREADY_GRANTED
        }
        grant()
        check(isGranted()) { "Location permission was not granted" }
        return TripLocationAccessResult.GRANTED
    }
}

internal object TripLocationAccessPolicy {
    val FINE: String = Manifest.permission.ACCESS_FINE_LOCATION
    val COARSE: String = Manifest.permission.ACCESS_COARSE_LOCATION

    /** The exact `pm grant` shell commands, fine first then coarse. */
    fun grantCommands(packageName: String): List<String> = listOf(
        "pm grant $packageName $FINE",
        "pm grant $packageName $COARSE",
    )
}

internal enum class TripLocationAccessPhase {
    IDLE,
    REPAIRING,
    GRANTED,
    FAILED,
}

internal data class TripLocationAccessDiagnostics(
    val granted: Boolean,
    val phase: TripLocationAccessPhase,
    val lastFailure: String?,
)

object TripLocationAccessCoordinator {
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
    private val executor = Executors.newSingleThreadExecutor()
    private val repairRunning = AtomicBoolean(false)

    @Volatile
    private var phase = TripLocationAccessPhase.IDLE

    @Volatile
    private var lastFailure: String? = null

    /**
     * Ensure the location permission, healing it over ADB when missing.
     *
     * [onResult] reports the final granted state and may run on a background
     * thread; callers must marshal back to their own thread. When it reports
     * `false`, the caller should fall back to a runtime permission request.
     */
    fun ensureAccess(context: Context, onResult: (granted: Boolean) -> Unit) {
        val app = context.applicationContext
        if (isGranted(app)) {
            phase = TripLocationAccessPhase.GRANTED
            lastFailure = null
            onResult(true)
            return
        }
        if (!repairRunning.compareAndSet(false, true)) {
            // Another heal is already in flight; its result will update the panel.
            return
        }
        phase = TripLocationAccessPhase.REPAIRING
        executor.execute {
            val result = runCatching {
                TripLocationAccessRepair(
                    isGranted = { isGranted(app) },
                    grant = { runGrant(app) },
                ).ensure()
            }
            if (result.isSuccess) {
                phase = TripLocationAccessPhase.GRANTED
                lastFailure = null
            } else {
                phase = TripLocationAccessPhase.FAILED
                lastFailure = result.exceptionOrNull()?.toString()
            }
            repairRunning.set(false)
            onResult(result.isSuccess)
        }
    }

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(TripLocationAccessPolicy.FINE) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(TripLocationAccessPolicy.COARSE) == PackageManager.PERMISSION_GRANTED

    internal fun diagnostics(context: Context): TripLocationAccessDiagnostics =
        TripLocationAccessDiagnostics(
            granted = isGranted(context),
            phase = phase,
            lastFailure = lastFailure,
        )

    private fun runGrant(context: Context) {
        val client = LocalAdbClient(context, ADB_KEY_COMMENT).openPersistentShell()
        try {
            TripLocationAccessPolicy.grantCommands(context.packageName).forEach { command ->
                client.shell(command)
            }
        } finally {
            client.close()
        }
    }
}
