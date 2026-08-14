package dev.denza.apps.feature.split

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors

enum class SplitScreenPhase { OFF, STARTING, ACTIVE, ERROR }

data class SplitScreenSession(
    val enabled: Boolean = false,
    val phase: SplitScreenPhase = SplitScreenPhase.OFF,
    val message: String = "",
    val details: String? = null,
)

/** Watches ordinary app launches and opens the second one in native BYD split. */
// The stored value is normalized to applicationContext during initialization.
@SuppressLint("StaticFieldLeak")
object SplitScreenCoordinator {
    private const val TAG = "DenzaSplitScreen"
    private const val KEY_COMMENT = "denza-apps@denza"
    private const val POLL_MS = 200L
    private const val RETRY_MS = 1_500L
    private const val EXTERNAL_TASK_BYPASS_MS = 5_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val routingLock = Any()

    @Volatile private var context: Context? = null
    @Volatile private var session = SplitScreenSession()
    @Volatile private var onStateChanged: (() -> Unit)? = null
    @Volatile private var initialized = false
    @Volatile private var generation = 0L
    @Volatile private var routingBlockedUntilMs = 0L
    @Volatile private var externalTaskMovesHeld = false
    private var activeRouter: SplitShellRouter? = null

    fun initialize(context: Context, onStateChanged: () -> Unit) {
        this.context = context.applicationContext
        this.onStateChanged = onStateChanged
        if (initialized) {
            onStateChanged()
            return
        }
        initialized = true
        val enabled = SplitScreenSettings.isEnabled(context)
        session = SplitScreenSession(enabled = enabled)
        if (enabled) {
            startAsync()
        } else if (SplitScreenSettings.resizeabilityLeaseStore(context).loadOriginal() != null) {
            stopAsync()
        } else {
            onStateChanged()
        }
    }

    fun snapshot(): SplitScreenSession = session

    /**
     * Navigation owns its explicit moves to and from the instrument display.
     * Forget any pending stock-picker selection and keep the router out of the
     * way until that move and its configuration changes have settled.
     */
    @JvmStatic
    fun bypassExternalTaskMoves() {
        val blockedUntil = SystemClock.elapsedRealtime() + EXTERNAL_TASK_BYPASS_MS
        synchronized(routingLock) {
            routingBlockedUntilMs = maxOf(routingBlockedUntilMs, blockedUntil)
            activeRouter?.cancelPendingSelection()
        }
    }

    /**
     * Keeps routing suspended while an external-display task move is actually
     * in flight. Navigation releases this after the projected central scene is
     * stable, then acquires it again before returning the task to display 0.
     */
    @JvmStatic
    fun holdExternalTaskMoves() {
        synchronized(routingLock) {
            if (!externalTaskMovesHeld) Log.i(TAG, "external task routing held")
            externalTaskMovesHeld = true
            activeRouter?.cancelPendingSelection()
        }
    }

    /**
     * Releases the long-lived handoff hold. The operation that acquired the
     * lease also calls [bypassExternalTaskMoves] before starting, so its settle
     * deadline is already in force. Extending it again here would leave the
     * main display unable to form a new pair for several seconds after a
     * successful navigation projection.
     */
    @JvmStatic
    fun releaseExternalTaskMoves() {
        synchronized(routingLock) {
            if (externalTaskMovesHeld) Log.i(TAG, "external task routing released")
            externalTaskMovesHeld = false
            activeRouter?.cancelPendingSelection()
        }
    }

    fun setEnabled(enabled: Boolean) {
        val app = context ?: return
        SplitScreenSettings.setEnabled(app, enabled)
        if (enabled) {
            startAsync()
            return
        }

        SplitScreenSettings.routingStateStore(app).clear()
        generation += 1
        update(SplitScreenSession(phase = SplitScreenPhase.STARTING, message = "Выключаю маршрутизацию"))
        stopAsync()
    }

    private fun stopAsync() {
        val app = context ?: return
        val stopGeneration = generation
        executor.execute {
            if (generation != stopGeneration || SplitScreenSettings.isEnabled(app)) {
                return@execute
            }
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = LocalAdbClient(app, KEY_COMMENT).openPersistentShell()
                SplitResizeabilityController(
                    shell = adb::shell,
                    leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
                ).restore()
                update(SplitScreenSession())
            } catch (error: Throwable) {
                if (generation != stopGeneration || SplitScreenSettings.isEnabled(app)) {
                    return@execute
                }
                Log.w(TAG, "failed to restore resizeability", error)
                update(
                    SplitScreenSession(
                        phase = SplitScreenPhase.ERROR,
                        message = friendlyError(error, "Не удалось выключить Split screen"),
                        details = error.toString(),
                    ),
                )
            } finally {
                adb?.close()
            }
        }
    }

    private fun startAsync() {
        val app = context ?: return
        generation += 1
        val currentGeneration = generation
        update(
            SplitScreenSession(
                enabled = true,
                phase = SplitScreenPhase.STARTING,
                message = "Включаю маршрутизацию",
            ),
        )
        executor.execute {
            while (isCurrent(currentGeneration)) {
                try {
                    runRoutingSession(app, currentGeneration)
                    return@execute
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                } catch (error: Throwable) {
                    if (!isCurrent(currentGeneration)) return@execute
                    Log.w(TAG, "routing failed; retrying", error)
                    update(
                        SplitScreenSession(
                            enabled = true,
                            phase = SplitScreenPhase.ERROR,
                            message = friendlyError(error, "Восстанавливаю Split screen"),
                            details = error.toString(),
                        ),
                    )
                    try {
                        Thread.sleep(RETRY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                    if (isCurrent(currentGeneration)) {
                        update(
                            SplitScreenSession(
                                enabled = true,
                                phase = SplitScreenPhase.STARTING,
                                message = "Восстанавливаю маршрутизацию",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun runRoutingSession(app: Context, currentGeneration: Long) {
        var router: SplitShellRouter? = null
        var adb: LocalAdbClient.PersistentShellSession? = null
        try {
            if (!isCurrent(currentGeneration)) return
            adb = LocalAdbClient(app, KEY_COMMENT).openPersistentShell()
            SplitResizeabilityController(
                shell = adb::shell,
                leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
            ).enable()
            if (!isCurrent(currentGeneration)) return
            val apps = launchableApps(app)
            router = SplitShellRouter(
                shell = adb::shell,
                apkPath = app.applicationInfo.sourceDir,
                eligibleApps = { apps },
                onEvent = { Log.i(TAG, it) },
                stateStore = SplitScreenSettings.routingStateStore(app),
            )
            synchronized(routingLock) {
                activeRouter = router
            }
            var lastSplitVisible = tickUnlessBlocked(router)
            Log.i(TAG, "native split visible=$lastSplitVisible")
            if (!isCurrent(currentGeneration)) return
            update(SplitScreenSession(enabled = true, phase = SplitScreenPhase.ACTIVE))
            while (isCurrent(currentGeneration)) {
                Thread.sleep(POLL_MS)
                if (isCurrent(currentGeneration)) {
                    val splitVisible = tickUnlessBlocked(router)
                    if (splitVisible != lastSplitVisible) {
                        Log.i(TAG, "native split visible=$splitVisible")
                        lastSplitVisible = splitVisible
                    }
                }
            }
        } finally {
            runCatching {
                if (SplitScreenSettings.isEnabled(app)) {
                    router?.closeForRestart()
                } else {
                    router?.disable()
                }
            }.onFailure { Log.w(TAG, "failed to close split gate", it) }
            synchronized(routingLock) {
                if (activeRouter === router) activeRouter = null
            }
            adb?.close()
        }
    }

    private fun isCurrent(value: Long): Boolean =
        generation == value && SplitScreenSettings.isEnabled(context ?: return false)

    private fun tickUnlessBlocked(router: SplitShellRouter): Boolean {
        return synchronized(routingLock) {
            if (!externalTaskMovesHeld && SystemClock.elapsedRealtime() >= routingBlockedUntilMs) {
                router.tick()
            } else {
                router.cancelPendingSelection()
                false
            }
        }
    }

    private fun update(next: SplitScreenSession) {
        session = next
        onStateChanged?.invoke()
    }

    private fun launchableApps(context: Context): Map<String, String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info -> info.activityInfo?.packageName }
            .distinct()
            .filterNot { it in EXCLUDED_PACKAGES }
            .mapNotNull { packageName ->
                val component = context.packageManager.getLaunchIntentForPackage(packageName)
                ?.component
                ?.flattenToString()
                ?: return@mapNotNull null
                packageName to component
            }
            .toMap()
    }

    private fun friendlyError(error: Throwable, fallback: String): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("authorization pending", ignoreCase = true) ->
                "Подтвердите ADB-ключ на экране автомобиля"
            text.contains("refused", ignoreCase = true) -> "Включите ADB на машине"
            text.contains("timeout", ignoreCase = true) -> "ADB пока не отвечает"
            else -> fallback
        }
    }

    private val EXCLUDED_PACKAGES = setOf(
        "com.android.launcher3",
        "dev.denza.apps",
    )
}
