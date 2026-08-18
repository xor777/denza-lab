package dev.denza.apps.feature.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-heals the spectrum analyser's audio permission exactly the way
 * [TripLocationAccessCoordinator] heals location: `pm grant` over the local ADB
 * channel, fail-closed and non-blocking.
 *
 * `RECORD_AUDIO` is required to attach a [android.media.audiofx.Visualizer] —
 * without it the effect refuses to initialise with error -3. It is nonetheless
 * the wrong prompt to show a driver, because the analyser never opens the
 * microphone: the op the platform actually records is `RECORD_AUDIO_OUTPUT`, no
 * microphone access is noted, and the mic privacy indicator stays dark. Healing
 * the grant over ADB keeps that mismatch out of the driver's face.
 *
 * `MODIFY_AUDIO_SETTINGS` is an install-time permission and needs no grant.
 */
internal enum class TripAudioAccessResult {
    ALREADY_GRANTED,
    GRANTED,
}

/** Pure grant/verify decision logic, JVM-testable with injected callbacks. */
internal class TripAudioAccessRepair(
    private val isGranted: () -> Boolean,
    private val grant: () -> Unit,
) {
    fun ensure(): TripAudioAccessResult {
        if (isGranted()) {
            return TripAudioAccessResult.ALREADY_GRANTED
        }
        grant()
        check(isGranted()) { "Audio permission was not granted" }
        return TripAudioAccessResult.GRANTED
    }
}

internal object TripAudioAccessPolicy {
    val RECORD: String = Manifest.permission.RECORD_AUDIO

    /** The exact `pm grant` shell command the repair runs. */
    fun grantCommands(packageName: String): List<String> = listOf(
        "pm grant $packageName $RECORD",
    )
}

internal enum class TripAudioAccessPhase {
    IDLE,
    REPAIRING,
    GRANTED,
    FAILED,
}

internal data class TripAudioAccessDiagnostics(
    val granted: Boolean,
    val phase: TripAudioAccessPhase,
    val lastFailure: String?,
)

object TripAudioAccessCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val repairRunning = AtomicBoolean(false)

    @Volatile
    private var phase = TripAudioAccessPhase.IDLE

    @Volatile
    private var lastFailure: String? = null

    /**
     * Ensure the audio permission, healing it over ADB when missing.
     *
     * [onResult] may run on a background thread; callers must marshal back to
     * their own thread.
     */
    fun ensureAccess(context: Context, onResult: (granted: Boolean) -> Unit) {
        val app = context.applicationContext
        if (isGranted(app)) {
            phase = TripAudioAccessPhase.GRANTED
            lastFailure = null
            onResult(true)
            return
        }
        if (!repairRunning.compareAndSet(false, true)) {
            // Another heal is already in flight; its result will update the panel.
            return
        }
        phase = TripAudioAccessPhase.REPAIRING
        executor.execute {
            val result = runCatching {
                TripAudioAccessRepair(
                    isGranted = { isGranted(app) },
                    grant = { runGrant(app) },
                ).ensure()
            }
            if (result.isSuccess) {
                phase = TripAudioAccessPhase.GRANTED
                lastFailure = null
            } else {
                phase = TripAudioAccessPhase.FAILED
                lastFailure = result.exceptionOrNull()?.toString()
            }
            repairRunning.set(false)
            onResult(result.isSuccess)
        }
    }

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(TripAudioAccessPolicy.RECORD) == PackageManager.PERMISSION_GRANTED

    internal fun diagnostics(context: Context): TripAudioAccessDiagnostics =
        TripAudioAccessDiagnostics(
            granted = isGranted(context),
            phase = phase,
            lastFailure = lastFailure,
        )

    private fun runGrant(context: Context) {
        val client = DenzaLocalAdb.client(context).openPersistentShell()
        try {
            TripAudioAccessPolicy.grantCommands(context.packageName).forEach { command ->
                client.shell(command)
            }
        } finally {
            client.close()
        }
    }
}
