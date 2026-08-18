package dev.denza.apps.feature.hud

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class HudNotificationAccessRepairResult {
    ALREADY_ENABLED,
    GRANTED,
}

internal class HudNotificationAccessRepair(
    private val isEnabled: () -> Boolean,
    private val grant: () -> Unit,
) {
    fun ensure(): HudNotificationAccessRepairResult {
        if (isEnabled()) {
            return HudNotificationAccessRepairResult.ALREADY_ENABLED
        }
        grant()
        check(isEnabled()) { "Notification listener access was not enabled" }
        return HudNotificationAccessRepairResult.GRANTED
    }
}

internal object HudNotificationAccessPolicy {
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

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"
}

internal enum class HudNotificationAccessPhase {
    IDLE,
    REPAIRING,
    ENABLED,
    FAILED,
}

internal data class HudNotificationAccessDiagnostics(
    val accessEnabled: Boolean,
    val phase: HudNotificationAccessPhase,
    val lastFailure: String?,
)

object HudNotificationAccessCoordinator {
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private val executor = Executors.newSingleThreadExecutor()
    private val repairRunning = AtomicBoolean(false)

    @Volatile
    private var phase = HudNotificationAccessPhase.IDLE

    @Volatile
    private var lastFailure: String? = null

    fun ensureAccess(context: Context, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (
            !YANDEX_NOTIFICATION_ARTWORK_ENABLED ||
            !HudGuidanceSettings.isEnabled(app)
        ) {
            phase = HudNotificationAccessPhase.IDLE
            onComplete?.invoke()
            return
        }
        if (isAccessEnabled(app)) {
            phase = HudNotificationAccessPhase.ENABLED
            lastFailure = null
            onComplete?.invoke()
            return
        }
        if (!repairRunning.compareAndSet(false, true)) {
            return
        }

        phase = HudNotificationAccessPhase.REPAIRING
        executor.execute {
            val result = runCatching {
                val component = listenerComponent(app)
                HudNotificationAccessRepair(
                    isEnabled = { isAccessEnabled(app) },
                    grant = {
                        DenzaLocalAdb.client(app).shell(
                            HudNotificationAccessPolicy.allowCommand(
                                component.flattenToString(),
                            ),
                        )
                    },
                ).ensure()
            }
            if (result.isSuccess) {
                phase = HudNotificationAccessPhase.ENABLED
                lastFailure = null
            } else {
                phase = HudNotificationAccessPhase.FAILED
                lastFailure = result.exceptionOrNull()?.toString()
            }
            repairRunning.set(false)
            onComplete?.invoke()
        }
    }

    fun isAccessEnabled(context: Context): Boolean {
        val component = listenerComponent(context)
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS,
            )
        }.getOrNull()
        return HudNotificationAccessPolicy.isEnabled(
            enabledListeners = enabled,
            packageName = component.packageName,
            className = component.className,
        )
    }

    internal fun diagnostics(context: Context): HudNotificationAccessDiagnostics =
        HudNotificationAccessDiagnostics(
            accessEnabled = isAccessEnabled(context),
            phase = phase,
            lastFailure = lastFailure,
        )

    private fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, YandexNotificationArtworkListener::class.java)
}
