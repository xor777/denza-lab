package dev.denza.apps.feature.split

import android.content.Context
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

/**
 * Watches the stock BYD split roots through the already-authorized local ADB connection.
 * Normal fullscreen launches are left untouched; routing runs only while both stock panes
 * are visible.
 */
object SplitScreenCoordinator {
    private const val TAG = "DenzaSplitScreen"
    private const val KEY_COMMENT = "denza-apps@denza"
    private const val POLL_MS = 800L
    private const val EXTERNAL_TASK_BYPASS_MS = 5_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val routingLock = Any()

    @Volatile private var context: Context? = null
    @Volatile private var session = SplitScreenSession()
    @Volatile private var onStateChanged: (() -> Unit)? = null
    @Volatile private var initialized = false
    @Volatile private var generation = 0L
    @Volatile private var routingBlockedUntilMs = 0L
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
        if (enabled) startAsync() else onStateChanged()
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

    fun setEnabled(enabled: Boolean) {
        val app = context ?: return
        SplitScreenSettings.setEnabled(app, enabled)
        if (enabled) {
            startAsync()
            return
        }

        generation += 1
        update(SplitScreenSession(phase = SplitScreenPhase.STARTING, message = "Выключаю маршрутизацию"))
        executor.execute {
            try {
                val adb = LocalAdbClient(app, KEY_COMMENT)
                SplitShellRouter(adb::shell).disable()
                update(SplitScreenSession())
            } catch (error: Throwable) {
                Log.w(TAG, "stop failed", error)
                update(
                    SplitScreenSession(
                        phase = SplitScreenPhase.ERROR,
                        message = friendlyError(error),
                        details = error.toString(),
                    ),
                )
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
            var router: SplitShellRouter? = null
            try {
                val adb = LocalAdbClient(app, KEY_COMMENT)
                router = SplitShellRouter(adb::shell)
                synchronized(routingLock) {
                    activeRouter = router
                }
                var lastSplitVisible = tickUnlessBlocked(router)
                Log.i(TAG, "stock split visible=$lastSplitVisible")
                if (!isCurrent(currentGeneration)) return@execute
                update(SplitScreenSession(enabled = true, phase = SplitScreenPhase.ACTIVE))
                while (isCurrent(currentGeneration)) {
                    Thread.sleep(POLL_MS)
                    if (isCurrent(currentGeneration)) {
                        val splitVisible = tickUnlessBlocked(router)
                        if (splitVisible != lastSplitVisible) {
                            Log.i(TAG, "stock split visible=$splitVisible")
                            lastSplitVisible = splitVisible
                        }
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (!isCurrent(currentGeneration)) return@execute
                Log.w(TAG, "routing failed", error)
                update(
                    SplitScreenSession(
                        enabled = true,
                        phase = SplitScreenPhase.ERROR,
                        message = friendlyError(error),
                        details = error.toString(),
                    ),
                )
            } finally {
                synchronized(routingLock) {
                    if (activeRouter === router) activeRouter = null
                }
            }
        }
    }

    private fun isCurrent(value: Long): Boolean =
        generation == value && SplitScreenSettings.isEnabled(context ?: return false)

    private fun tickUnlessBlocked(router: SplitShellRouter): Boolean {
        return synchronized(routingLock) {
            if (SystemClock.elapsedRealtime() >= routingBlockedUntilMs) {
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

    private fun friendlyError(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("authorization pending", ignoreCase = true) ->
                "Подтвердите ADB-ключ на экране автомобиля"
            text.contains("refused", ignoreCase = true) -> "Включите ADB на машине"
            text.contains("timeout", ignoreCase = true) -> "ADB пока не отвечает"
            else -> "Не удалось включить Split screen"
        }
    }
}
