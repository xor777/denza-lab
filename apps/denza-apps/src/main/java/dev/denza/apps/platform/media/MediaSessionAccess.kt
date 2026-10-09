package dev.denza.apps.platform.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.shellQuote
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MediaSessionAccessRepairResult {
    ALREADY_ENABLED,
    GRANTED,
}

internal class MediaSessionAccessRepair(
    private val isEnabled: () -> Boolean,
    private val grant: () -> Unit,
) {
    fun ensure(): MediaSessionAccessRepairResult {
        if (isEnabled()) {
            return MediaSessionAccessRepairResult.ALREADY_ENABLED
        }
        grant()
        check(isEnabled()) { "Notification listener access was not enabled" }
        return MediaSessionAccessRepairResult.GRANTED
    }
}

internal object MediaSessionAccessPolicy {
    fun isEnabled(
        enabledListeners: String?,
        packageName: String,
        className: String,
    ): Boolean = enabledListeners
        .orEmpty()
        .split(':')
        .any { entry ->
            val separator = entry.indexOf('/')
            if (separator <= 0 || separator == entry.lastIndex) {
                return@any false
            }
            val entryPackage = entry.substring(0, separator)
            val rawClass = entry.substring(separator + 1)
            val entryClass = if (rawClass.startsWith('.')) entryPackage + rawClass else rawClass
            entryPackage == packageName && entryClass == className
        }

    fun allowCommand(componentName: String): String =
        "cmd notification allow_listener ${shellQuote(componentName)}"
}

internal enum class MediaSessionAccessPhase {
    IDLE,
    REPAIRING,
    ENABLED,
    FAILED,
}

internal data class MediaSessionAccessDiagnostics(
    val accessEnabled: Boolean,
    val phase: MediaSessionAccessPhase,
    val lastFailure: String?,
)

/**
 * The app's access to other apps' media sessions, and its repair.
 *
 * `MediaSessionManager` answers a caller that names an **enabled notification listener** of its
 * own, and this app has exactly one: [LISTENER_CLASS], declared in the HUD package because the HUD's
 * turn arrows were the first thing to need it. The wheel's Play/Pause key, the speaker covers and the
 * strip's track line all read sessions through that grant, and the HUD reads notifications through
 * it, so the grant and its repair live here rather than in any one of them. Each asks; none owns it.
 *
 * The repair is one idempotent `cmd notification allow_listener` over the local ADB shell, run once
 * at a time however many owners ask, and every asker hears the outcome. A repair that granted the
 * listener has [MediaSessionHub] listen again before any asker hears it: losing the grant made the
 * platform drop the hub's listener.
 */
object MediaSessionAccess {
    /**
     * The listener's fully-qualified class name, which is what the car records in
     * `enabled_notification_listeners`. Renaming or moving that class loses the grant on every car
     * until the repair runs again, so it is named here as the string the car holds, and
     * `MediaSessionAccessTest` holds the class to it.
     */
    const val LISTENER_CLASS = "dev.denza.apps.feature.hud.YandexNotificationArtworkListener"

    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private val executor = Executors.newSingleThreadExecutor()
    private val repairRunning = AtomicBoolean(false)
    private val callbackLock = Any()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var phase = MediaSessionAccessPhase.IDLE

    @Volatile
    private var lastFailure: String? = null

    /** The component every `MediaSessionManager` call names. */
    fun component(context: Context): ComponentName =
        ComponentName(context.packageName, LISTENER_CLASS)

    /**
     * Makes sure the listener is enabled, repairing it if not; [onComplete] runs once the answer is
     * known - at once when it already was, else on the repair's thread.
     */
    fun ensure(context: Context, onComplete: (() -> Unit)? = null) {
        ensureListenerAccess(context.applicationContext, onComplete)
    }

    /**
     * An owner that does not want the listener at all just now - the HUD with guidance off. Nothing
     * is repaired, and the report's repair row reads idle.
     */
    fun notWanted(onComplete: (() -> Unit)? = null) {
        phase = MediaSessionAccessPhase.IDLE
        onComplete?.invoke()
    }

    private fun ensureListenerAccess(context: Context, onComplete: (() -> Unit)?) {
        if (isEnabled(context)) {
            phase = MediaSessionAccessPhase.ENABLED
            lastFailure = null
            onComplete?.invoke()
            return
        }
        if (onComplete != null) {
            synchronized(callbackLock) { pendingCallbacks += onComplete }
        }
        if (!repairRunning.compareAndSet(false, true)) {
            return
        }

        phase = MediaSessionAccessPhase.REPAIRING
        executor.execute {
            val result = runCatching {
                val component = component(context)
                MediaSessionAccessRepair(
                    isEnabled = { isEnabled(context) },
                    grant = {
                        DenzaLocalAdb.client(context).shell(
                            MediaSessionAccessPolicy.allowCommand(
                                component.flattenToString(),
                            ),
                        )
                    },
                ).ensure()
            }
            if (result.isSuccess) {
                phase = MediaSessionAccessPhase.ENABLED
                lastFailure = null
                if (result.getOrNull() == MediaSessionAccessRepairResult.GRANTED) {
                    // Posted ahead of the askers' own callbacks, which post to the same looper.
                    Handler(Looper.getMainLooper()).post { MediaSessionHub.get(context).relisten() }
                }
            } else {
                phase = MediaSessionAccessPhase.FAILED
                lastFailure = result.exceptionOrNull()?.toString()
            }
            repairRunning.set(false)
            // «Динамики» reads whether the listener is enabled; it is read again before any
            // owner hears the outcome.
            StateMarks.mark(StateSlice.SPEAKER_COVERS, "notification access")
            val callbacks = synchronized(callbackLock) {
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
            callbacks.forEach { it.invoke() }
        }
    }

    fun isEnabled(context: Context): Boolean {
        val component = component(context)
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS,
            )
        }.getOrNull()
        return MediaSessionAccessPolicy.isEnabled(
            enabledListeners = enabled,
            packageName = component.packageName,
            className = component.className,
        )
    }

    internal fun diagnostics(context: Context): MediaSessionAccessDiagnostics =
        MediaSessionAccessDiagnostics(
            accessEnabled = isEnabled(context),
            phase = phase,
            lastFailure = lastFailure,
        )
}
