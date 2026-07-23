package dev.denza.apps.feature.navigation

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.cluster.MapSurfaceConsumer
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// The stored value is normalized to applicationContext during initialization.
@SuppressLint("StaticFieldLeak")
object NavigationCoordinator {
    private const val TAG = "DenzaNavigation"
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
    private const val AUTOMATIC_POLL_SECONDS = 1L
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var context: Context? = null
    @Volatile private var session = NavigationSession()
    @Volatile private var onStateChanged: (() -> Unit)? = null
    @Volatile private var initialized = false
    @Volatile private var automaticEnabled = false
    @Volatile private var selectedPackage = NavigationAppPolicy.DEFAULT_PACKAGE
    @Volatile private var selectedPlacement = ClusterMapPlacement.FULL
    private var stockModeDetector: StockClusterModeDetector? = null
    private var lastStockMapVisible: Boolean? = null
    private var automaticProjectionActive = false
    private var pendingAutomaticProjection = false
    private var pendingAutomaticReturn = false
    private var pendingProjectionAfterOpen = false

    fun initialize(context: Context, onStateChanged: () -> Unit) {
        val app = context.applicationContext
        this.context = app
        this.onStateChanged = onStateChanged
        if (initialized) {
            onStateChanged()
            return
        }
        initialized = true
        selectedPackage = NavigationSettings.selectedPackage(app)
        selectedPlacement = NavigationSettings.placement(app)
        val adb = LocalAdbClient(app, ADB_KEY_COMMENT)
        stockModeDetector = StockClusterModeDetector(adb::shell)
        executor.execute(::discoverTask)
        executor.scheduleWithFixedDelay(::verifyActiveSession, 5L, 5L, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay(
            ::reconcileAutomaticMode,
            AUTOMATIC_POLL_SECONDS,
            AUTOMATIC_POLL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    fun snapshot(): NavigationSession = session

    fun automaticEnabled(): Boolean = automaticEnabled

    fun selectedPackage(): String = selectedPackage

    fun placement(): ClusterMapPlacement = selectedPlacement

    fun selectPlacement(placement: ClusterMapPlacement) {
        val app = context ?: return
        executor.execute {
            if (selectedPlacement == placement) {
                onStateChanged?.invoke()
                return@execute
            }
            if (
                session.phase == NavigationPhase.OPENING ||
                session.phase == NavigationPhase.PROJECTING ||
                session.phase == NavigationPhase.RETURNING ||
                session.phase == NavigationPhase.RECOVERING
            ) {
                return@execute
            }
            val wasProjected = session.phase == NavigationPhase.PROJECTED
            val wasAutomatic = automaticProjectionActive
            NavigationSettings.setPlacement(app, placement)
            selectedPlacement = placement
            onStateChanged?.invoke()
            if (!wasProjected) return@execute

            val shouldReproject = !wasAutomatic ||
                (automaticEnabled && lastStockMapVisible == true)
            automaticProjectionActive = wasAutomatic && shouldReproject
            returnToCentralDisplay(focusTask = false, reprojectAfterReturn = shouldReproject)
        }
    }

    fun selectPackage(packageName: String) {
        val app = context ?: return
        if (!NavigationAppPolicy.isAllowed(packageName)) return
        if (!NavigationSettings.isInstalled(app, packageName)) return
        executor.execute {
            if (selectedPackage == packageName) {
                onStateChanged?.invoke()
                return@execute
            }
            automaticProjectionActive = false
            pendingAutomaticProjection = false
            pendingAutomaticReturn = false
            pendingProjectionAfterOpen = false
            if (session.phase == NavigationPhase.PROJECTED) {
                returnToCentralDisplay(focusTask = false)
            }
            NavigationSettings.setSelectedPackage(app, packageName)
            selectedPackage = packageName
            lastStockMapVisible = null
            discoverTask()
            if (automaticEnabled) reconcileAutomaticMode()
        }
    }

    fun setAutomaticEnabled(enabled: Boolean) {
        automaticEnabled = enabled
        onStateChanged?.invoke()
        executor.execute {
            lastStockMapVisible = null
            pendingAutomaticProjection = false
            pendingAutomaticReturn = false
            if (enabled) {
                reconcileAutomaticMode()
            } else if (automaticProjectionActive) {
                automaticProjectionActive = false
                if (session.phase == NavigationPhase.PROJECTED) {
                    returnToCentralDisplay(focusTask = false)
                }
            }
        }
    }

    fun performPrimaryAction() {
        SplitScreenCoordinator.bypassExternalTaskMoves()
        executor.execute {
            when (session.phase) {
                NavigationPhase.PROJECTED -> {
                    automaticProjectionActive = false
                    pendingAutomaticReturn = false
                    returnToCentralDisplay()
                }
                NavigationPhase.RETURNING -> Unit
                NavigationPhase.PROJECTING, NavigationPhase.OPENING, NavigationPhase.RECOVERING -> Unit
                else -> if (session.taskId == null) openSelectedApp() else projectToCluster()
            }
        }
    }

    private fun discoverTask() {
        val app = context ?: return
        val packageName = selectedPackage
        if (!NavigationSettings.isInstalled(app, packageName)) {
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Установите выбранный навигатор",
                ),
            )
            return
        }
        try {
            val task = NavigationProxyClient.findAllowedTask(app, packageName)
            update(NavigationSession(taskId = task.takeIf { it >= 0 }))
        } catch (error: Exception) {
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = friendlyProxyError(error),
                    details = error.toString(),
                ),
            )
        }
    }

    private fun openSelectedApp() {
        SplitScreenCoordinator.bypassExternalTaskMoves()
        val app = context ?: return
        val packageName = selectedPackage
        val launch = app.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            pendingProjectionAfterOpen = false
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Установите выбранный навигатор",
                ),
            )
            return
        }
        update(session.copy(phase = NavigationPhase.OPENING, message = "Открываю на центральном экране"))
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(0)
            app.startActivity(launch, options.toBundle())
            executor.schedule({ discoverLaunchedTask(5) }, 900L, TimeUnit.MILLISECONDS)
        } catch (error: RuntimeException) {
            pendingProjectionAfterOpen = false
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Не удалось открыть навигатор",
                    details = error.toString(),
                ),
            )
        }
    }

    private fun discoverLaunchedTask(attemptsRemaining: Int) {
        val app = context ?: return
        val packageName = selectedPackage
        try {
            val task = NavigationProxyClient.findAllowedTask(app, packageName)
            if (task >= 0) {
                update(NavigationSession(taskId = task))
                if (pendingProjectionAfterOpen) {
                    pendingProjectionAfterOpen = false
                    projectToCluster()
                } else if (
                    pendingAutomaticProjection &&
                    automaticEnabled &&
                    lastStockMapVisible == true
                ) {
                    pendingAutomaticProjection = false
                    automaticProjectionActive = true
                    projectToCluster()
                }
            } else if (attemptsRemaining > 0) {
                executor.schedule(
                    { discoverLaunchedTask(attemptsRemaining - 1) },
                    700L,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                pendingAutomaticProjection = false
                pendingProjectionAfterOpen = false
                update(
                    NavigationSession(
                        phase = NavigationPhase.NEEDS_ACTION,
                        message = "Дождитесь запуска навигатора",
                    ),
                )
            }
        } catch (error: Exception) {
            pendingAutomaticProjection = false
            pendingProjectionAfterOpen = false
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = friendlyProxyError(error),
                    details = error.toString(),
                ),
            )
        }
    }

    private fun projectToCluster() {
        SplitScreenCoordinator.bypassExternalTaskMoves()
        val app = context ?: return
        val packageName = selectedPackage
        val taskId = try {
            NavigationProxyClient.findAllowedTask(app, packageName)
        } catch (error: Exception) {
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = friendlyProxyError(error),
                    details = error.toString(),
                ),
            )
            return
        }
        if (taskId < 0) {
            pendingProjectionAfterOpen = true
            update(NavigationSession(message = "Повторно открываю навигатор"))
            openSelectedApp()
            return
        }
        if (session.taskId != taskId) update(session.copy(taskId = taskId))
        val selected = ClusterDisplayResolver.resolve(app)
        if (selected !is ClusterDisplaySelection.Selected) {
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = if (selected is ClusterDisplaySelection.NeedsVerification) {
                        "Выберите приборный экран в разделе помощи"
                    } else {
                        "Приборный экран пока не найден"
                    },
                ),
            )
            return
        }
        try {
            LocalAdbClient(app, "denza-apps@denza").shell(
                "cmd appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow",
            )
        } catch (error: Exception) {
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = friendlyProxyError(error),
                    details = error.toString(),
                ),
            )
            return
        }
        update(session.copy(phase = NavigationPhase.PROJECTING, message = "Переношу на приборку"))
        val consumed = AtomicBoolean(false)
        ClusterSceneService.showMap(app, selectedPlacement, MapSurfaceConsumer { surface, width, height, density ->
            if (!consumed.compareAndSet(false, true)) return@MapSurfaceConsumer
            executor.execute {
                try {
                    val displayId = NavigationProxyClient.createVirtualDisplay(
                        app,
                        surface,
                        width,
                        height,
                        density,
                    )
                    check(displayId >= 0) { "virtual display creation failed" }
                    check(
                        NavigationProxyClient.projectTask(
                            app,
                            packageName,
                            taskId,
                            displayId,
                            width,
                            height,
                        ),
                    ) { "task projection failed" }
                    update(
                        NavigationSession(
                            phase = NavigationPhase.PROJECTED,
                            taskId = taskId,
                            virtualDisplayId = displayId,
                        ),
                    )
                    if (
                        pendingAutomaticReturn ||
                        (automaticProjectionActive && lastStockMapVisible == false)
                    ) {
                        pendingAutomaticReturn = false
                        automaticProjectionActive = false
                        returnToCentralDisplay(focusTask = false)
                    }
                } catch (error: Exception) {
                    automaticProjectionActive = false
                    pendingAutomaticReturn = false
                    NavigationProxyClient.releaseVirtualDisplay()
                    ClusterSceneService.hideMap(app)
                    update(
                        NavigationSession(
                            phase = NavigationPhase.NEEDS_ACTION,
                            taskId = taskId,
                            message = "Не удалось перенести навигацию",
                            details = error.toString(),
                        ),
                    )
                }
            }
        })
    }

    private fun returnToCentralDisplay(
        focusTask: Boolean = true,
        reprojectAfterReturn: Boolean = false,
    ) {
        SplitScreenCoordinator.bypassExternalTaskMoves()
        val app = context ?: return
        val taskId = session.taskId
        val packageName = selectedPackage
        update(session.copy(phase = NavigationPhase.RETURNING, message = "Возвращаю на главный экран"))
        try {
            if (taskId != null) {
                NavigationProxyClient.returnTask(
                    app,
                    packageName,
                    taskId,
                    focusNavigation = focusTask,
                )
            }
        } catch (_: Exception) {
            // Releasing the display below is still the safest available fallback.
        } finally {
            NavigationProxyClient.releaseVirtualDisplay()
            ClusterSceneService.hideMap(app)
            update(NavigationSession(taskId = taskId))
            if (focusTask || reprojectAfterReturn) {
                executor.schedule(
                    {
                        settleReturnedTask(
                            packageName = packageName,
                            focusTask = focusTask,
                            reprojectAfterReturn = reprojectAfterReturn,
                        )
                    },
                    900L,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun settleReturnedTask(
        packageName: String,
        focusTask: Boolean,
        reprojectAfterReturn: Boolean,
    ) {
        val app = context ?: return
        if (selectedPackage != packageName) return
        val liveTask = try {
            NavigationProxyClient.findAllowedTask(app, packageName)
        } catch (error: Exception) {
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = friendlyProxyError(error),
                    details = error.toString(),
                ),
            )
            return
        }
        if (liveTask >= 0) {
            update(NavigationSession(taskId = liveTask))
            if (reprojectAfterReturn) projectToCluster()
            return
        }
        if (!focusTask && !reprojectAfterReturn) {
            update(NavigationSession())
            return
        }
        pendingProjectionAfterOpen = reprojectAfterReturn
        update(NavigationSession(message = "Повторно открываю навигатор"))
        openSelectedApp()
    }

    private fun verifyActiveSession() {
        val app = context ?: return
        val current = session
        if (current.phase != NavigationPhase.PROJECTED) return
        val taskId = current.taskId ?: return
        val expectedDisplay = current.virtualDisplayId ?: return
        val packageName = selectedPackage
        try {
            val actualDisplay = NavigationProxyClient.taskDisplayId(app, packageName, taskId)
            if (actualDisplay != expectedDisplay) {
                automaticProjectionActive = false
                pendingAutomaticReturn = false
                NavigationProxyClient.releaseVirtualDisplay()
                ClusterSceneService.hideMap(app)
                update(NavigationSession(taskId = taskId.takeIf { actualDisplay >= 0 }))
            }
        } catch (_: Exception) {
            handleCommandFailure()
        }
    }

    private fun handleCommandFailure() {
        val app = context ?: return
        val previous = session
        automaticProjectionActive = false
        pendingAutomaticProjection = false
        pendingAutomaticReturn = false
        pendingProjectionAfterOpen = false
        update(NavigationRecovery.proxyLost(previous))
        ClusterSceneService.hideMap(app)
        executor.schedule({
            NavigationProxyClient.disconnect()
            discoverTask()
        }, 800L, TimeUnit.MILLISECONDS)
    }

    private fun reconcileAutomaticMode() {
        if (!automaticEnabled) return
        val app = context ?: return
        val selected = ClusterDisplayResolver.resolve(app)
        if (selected !is ClusterDisplaySelection.Selected) return
        val resolvedPackage = NavigationSettings.selectedPackage(app)
        if (resolvedPackage != selectedPackage && session.phase != NavigationPhase.PROJECTED) {
            selectedPackage = resolvedPackage
            discoverTask()
        }
        val detector = stockModeDetector ?: return
        val mapVisible = try {
            detector.isMapVisible(selected.display.id)
        } catch (error: Exception) {
            Log.d(TAG, "stock cluster mode check failed", error)
            return
        }
        if (lastStockMapVisible == mapVisible) return
        lastStockMapVisible = mapVisible
        Log.i(TAG, "stock map visible=$mapVisible display=${selected.display.id}")
        if (mapVisible) onStockMapEntered() else onStockMapExited()
    }

    private fun onStockMapEntered() {
        pendingAutomaticReturn = false
        when (session.phase) {
            NavigationPhase.READY, NavigationPhase.NEEDS_ACTION -> {
                if (session.taskId != null) {
                    automaticProjectionActive = true
                    projectToCluster()
                } else {
                    pendingAutomaticProjection = true
                    openSelectedApp()
                }
            }
            else -> Unit
        }
    }

    private fun onStockMapExited() {
        pendingAutomaticProjection = false
        if (!automaticProjectionActive) return
        when (session.phase) {
            NavigationPhase.PROJECTED -> {
                automaticProjectionActive = false
                returnToCentralDisplay(focusTask = false)
            }
            NavigationPhase.PROJECTING -> pendingAutomaticReturn = true
            else -> automaticProjectionActive = false
        }
    }

    private fun update(next: NavigationSession) {
        session = next
        onStateChanged?.invoke()
    }

    private fun friendlyProxyError(error: Exception): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("authorization pending", ignoreCase = true) ->
                "Подтвердите ADB-ключ на экране автомобиля"
            text.contains("refused", ignoreCase = true) -> "Включите ADB на машине"
            text.contains("timeout", ignoreCase = true) -> "ADB пока не отвечает"
            else -> "Навигации нужен доступ к машине"
        }
    }
}
