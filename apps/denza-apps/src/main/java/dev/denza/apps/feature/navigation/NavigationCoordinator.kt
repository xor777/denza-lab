package dev.denza.apps.feature.navigation

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.denza.apps.TaskMoveLease
import dev.denza.apps.TaskMoveOwner
import dev.denza.apps.TaskMoveOwnership
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.cluster.MapSurfaceConsumer
import dev.denza.apps.feature.split.SplitNavigationReturnPlan
import dev.denza.apps.feature.split.SplitScreenCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// The stored value is normalized to applicationContext during initialization.
@SuppressLint("StaticFieldLeak")
object NavigationCoordinator {
    private const val TAG = "DenzaNavigation"
    private const val PROJECTION_SURFACE_TIMEOUT_MS = 5_000L
    private const val PROJECTION_ROUTING_SETTLE_MS = 900L
    private const val RETURN_SETTLE_MS = 900L
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var context: Context? = null
    @Volatile private var session = NavigationSession()
    @Volatile private var onStateChanged: (() -> Unit)? = null
    @Volatile private var initialized = false
    @Volatile private var selectedPackage = NavigationAppPolicy.DEFAULT_PACKAGE
    @Volatile private var selectedPlacement = ClusterMapPlacement.FULL
    @Volatile private var dashboardOnCluster = false
    private var pendingProjectionAfterOpen = false
    private var projectedOrigin: NavigationProjectionOrigin? = null
    private val projectionHealth = NavigationProjectionHealthTracker()
    private val primaryActionPending = AtomicBoolean(false)
    private val splitRoutingLease = NavigationSplitRoutingLease()

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
        executor.execute(::discoverTask)
        executor.scheduleWithFixedDelay(::verifyActiveSession, 5L, 5L, TimeUnit.SECONDS)
    }

    fun snapshot(): NavigationSession = session

    fun selectedPackage(): String = selectedPackage

    fun placement(): ClusterMapPlacement = selectedPlacement

    fun selectPlacement(placement: ClusterMapPlacement) {
        val app = context ?: return
        executor.execute {
            // The dashboard is drawn for the whole panel and offers no placement row, so a
            // placement arriving while it is chosen belongs to nothing on screen. Refusing it here
            // also keeps the navigator's own saved choice intact for when one is picked again.
            if (dashboardSelected()) return@execute
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
            NavigationSettings.setPlacement(app, placement)
            selectedPlacement = placement
            onStateChanged?.invoke()
            if (!wasProjected) return@execute
            returnToCentralDisplay(focusTask = false, reprojectAfterReturn = true)
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
            pendingProjectionAfterOpen = false
            if (session.phase == NavigationPhase.PROJECTED) {
                // Whatever is on the cluster belongs to the outgoing choice, and comes off the way
                // that choice put it there. Read before the new selection is stored.
                if (dashboardSelected()) {
                    hideDashboardOnCluster()
                } else {
                    returnToCentralDisplay(focusTask = false)
                }
            }
            NavigationSettings.setSelectedPackage(app, packageName)
            selectedPackage = packageName
            discoverTask()
        }
    }

    fun performPrimaryAction(): Boolean {
        val app = context ?: return false
        val selectedAppInstalled = NavigationSettings.isInstalled(app, selectedPackage)
        if (NavigationPrimaryActionPolicy.action(
            initialized = initialized,
            hasContext = true,
            selectedAppInstalled = selectedAppInstalled,
            actionPending = primaryActionPending.get(),
            session = session,
        ) == null) return false
        if (!primaryActionPending.compareAndSet(false, true)) return false
        val action = NavigationPrimaryActionPolicy.action(
            initialized = initialized,
            hasContext = true,
            selectedAppInstalled = selectedAppInstalled,
            actionPending = false,
            session = session,
        ) ?: run {
            primaryActionPending.set(false)
            return false
        }
        // Only the projection path moves tasks between displays; the dashboard moves none, so it
        // has no reason to reach into split routing at all.
        if (!dashboardSelected()) TaskMoveOwnership.pulse(TaskMoveOwner.NAVIGATION)
        executor.execute {
            try {
                when (action) {
                    NavigationPrimaryAction.RETURN -> {
                        if (dashboardSelected()) {
                            hideDashboardOnCluster()
                        } else {
                            returnToCentralDisplay()
                        }
                    }
                    NavigationPrimaryAction.OPEN -> openSelectedApp()
                    NavigationPrimaryAction.PROJECT -> if (dashboardSelected()) {
                        showDashboardOnCluster()
                    } else {
                        projectToCluster()
                    }
                }
            } finally {
                primaryActionPending.set(false)
            }
        }
        return true
    }

    fun onClusterDisplaySelected() {
        executor.execute {
            if (!NavigationRecovery.shouldRetryAfterClusterSelection(session)) return@execute
            if (dashboardSelected()) showDashboardOnCluster() else projectToCluster()
        }
    }

    private fun dashboardSelected(): Boolean = NavigationAppPolicy.isDashboard(selectedPackage)

    /**
     * Puts this app's own instruments on the driver's display.
     *
     * The display is resolved here rather than left to the scene service because this is the one
     * place that can answer for it: a service that cannot find the cluster writes a line in its own
     * notification and stops, which the card would never hear about. Resolving first leaves exactly
     * two outcomes - the instruments on the panel, or the display picker - which is what the
     * projection path already offers and what the driver is owed either way.
     */
    private fun showDashboardOnCluster() {
        val app = context ?: return
        val selected = ClusterDisplayResolver.resolve(app)
        if (selected !is ClusterDisplaySelection.Selected) {
            val needsSelection = selected is ClusterDisplaySelection.NeedsVerification
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = if (needsSelection) {
                        "Выберите приборный экран"
                    } else {
                        "Повторите поиск приборного экрана"
                    },
                    resolution = if (needsSelection) {
                        FeatureResolution.SELECT_CLUSTER_DISPLAY
                    } else {
                        FeatureResolution.RETRY
                    },
                ),
            )
            return
        }
        // The scene's base presentation is a system window. Every feature of this app that opens
        // one grants itself the appop first, over the same local ADB this card already needs.
        try {
            DenzaLocalAdb.client(app).shell(
                "cmd appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow",
            )
        } catch (error: Exception) {
            val problem = friendlyProxyProblem(error)
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = problem.message,
                    details = error.toString(),
                    resolution = problem.resolution,
                ),
            )
            return
        }
        ClusterSceneService.showDashboard(
            app,
            NavigationPlacementPolicy.offered(selectedPackage).first(),
        )
        dashboardOnCluster = true
        update(NavigationSession(phase = NavigationPhase.PROJECTED))
    }

    private fun hideDashboardOnCluster() {
        val app = context ?: return
        ClusterSceneService.hideDashboard(app)
        dashboardOnCluster = false
        update(NavigationSession())
    }

    private fun discoverTask() {
        val app = context ?: return
        if (dashboardSelected()) {
            // Nothing to discover: the dashboard is a view of ours, not somebody else's task. The
            // only thing worth restating is whether it is currently on the panel.
            update(
                if (dashboardOnCluster) {
                    NavigationSession(phase = NavigationPhase.PROJECTED)
                } else {
                    NavigationSession()
                },
            )
            return
        }
        val packageName = selectedPackage
        if (!NavigationSettings.isInstalled(app, packageName)) {
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Выберите установленный навигатор",
                    resolution = FeatureResolution.SELECT_NAVIGATION_APP,
                ),
            )
            return
        }
        try {
            val task = NavigationProxyClient.findAllowedTask(app, packageName)
            update(NavigationSession(taskId = task.takeIf { it >= 0 }))
        } catch (error: Exception) {
            val problem = friendlyProxyProblem(error)
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = problem.message,
                    details = error.toString(),
                    resolution = problem.resolution,
                ),
            )
        }
    }

    private fun openSelectedApp() {
        TaskMoveOwnership.pulse(TaskMoveOwner.NAVIGATION)
        val app = context ?: return
        val packageName = selectedPackage
        val launch = app.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            pendingProjectionAfterOpen = false
            splitRoutingLease.release()
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Выберите установленный навигатор",
                    resolution = FeatureResolution.SELECT_NAVIGATION_APP,
                ),
            )
            finishTransfer()
            return
        }
        update(
            session.copy(
                phase = NavigationPhase.OPENING,
                message = "Открываю на центральном экране",
                resolution = null,
            ),
        )
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(0)
            app.startActivity(launch, options.toBundle())
            executor.schedule({ discoverLaunchedTask(5) }, 900L, TimeUnit.MILLISECONDS)
        } catch (error: RuntimeException) {
            pendingProjectionAfterOpen = false
            splitRoutingLease.release()
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Повторите запуск навигатора",
                    details = error.toString(),
                    resolution = FeatureResolution.RETRY,
                ),
            )
            finishTransfer()
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
                } else {
                    finishTransfer()
                }
            } else if (attemptsRemaining > 0) {
                executor.schedule(
                    { discoverLaunchedTask(attemptsRemaining - 1) },
                    700L,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                pendingProjectionAfterOpen = false
                splitRoutingLease.release()
                update(
                    NavigationSession(
                        phase = NavigationPhase.NEEDS_ACTION,
                        message = "Дождитесь запуска навигатора и повторите",
                        resolution = FeatureResolution.RETRY,
                    ),
                )
                finishTransfer()
            }
        } catch (error: Exception) {
            pendingProjectionAfterOpen = false
            splitRoutingLease.release()
            val problem = friendlyProxyProblem(error)
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = problem.message,
                    details = error.toString(),
                    resolution = problem.resolution,
                ),
            )
            finishTransfer()
        }
    }

    private fun projectToCluster() {
        TaskMoveOwnership.pulse(TaskMoveOwner.NAVIGATION)
        val app = context ?: return
        beginTransfer(app)
        val packageName = selectedPackage
        val taskId = try {
            NavigationProxyClient.findAllowedTask(app, packageName)
        } catch (error: Exception) {
            val problem = friendlyProxyProblem(error)
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = problem.message,
                    details = error.toString(),
                    resolution = problem.resolution,
                ),
            )
            finishTransfer()
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
            val needsSelection = selected is ClusterDisplaySelection.NeedsVerification
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = if (needsSelection) {
                        "Выберите приборный экран"
                    } else {
                        "Повторите поиск приборного экрана"
                    },
                    resolution = if (needsSelection) {
                        FeatureResolution.SELECT_CLUSTER_DISPLAY
                    } else {
                        FeatureResolution.RETRY
                    },
                ),
            )
            finishTransfer()
            return
        }
        try {
            DenzaLocalAdb.client(app).shell(
                "cmd appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow",
            )
        } catch (error: Exception) {
            val problem = friendlyProxyProblem(error)
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = problem.message,
                    details = error.toString(),
                    resolution = problem.resolution,
                ),
            )
            finishTransfer()
            return
        }
        NavigationTransferOverlay.refresh(app)
        splitRoutingLease.acquire()
        projectionHealth.reset()
        update(
            session.copy(
                phase = NavigationPhase.PROJECTING,
                message = "Переношу на приборку",
                resolution = null,
            ),
        )
        val consumed = AtomicBoolean(false)
        try {
            ClusterSceneService.showMap(
                app,
                selectedPlacement,
                MapSurfaceConsumer { surface, width, height, density ->
                    if (!consumed.compareAndSet(false, true)) return@MapSurfaceConsumer
                    executor.execute {
                        try {
                            val origin = NavigationProxyClient.projectionOrigin(
                                app,
                                packageName,
                                taskId,
                            )
                            check(origin.sourceRootTaskId > 0) {
                                "navigation source root unavailable"
                            }
                            projectedOrigin = origin
                            val displayId = NavigationProxyClient.createVirtualDisplay(
                                app,
                                surface,
                                width,
                                height,
                                density,
                            )
                            check(displayId >= 0) { "virtual display creation failed" }
                            val projectionRootTaskId = if (origin.sourceRootTaskId == taskId) {
                                0
                            } else {
                                NavigationProxyClient.createProjectionRoot(app, displayId)
                                    .also { check(it > 0) { "projection root creation failed" } }
                            }
                            check(
                                NavigationProxyClient.projectTask(
                                    app,
                                    packageName,
                                    taskId,
                                    projectionRootTaskId,
                                    displayId,
                                    width,
                                    height,
                                ),
                            ) { "task projection failed" }
                            SplitScreenCoordinator.onProjectionStarted(taskId)
                            update(
                                NavigationSession(
                                    phase = NavigationPhase.PROJECTED,
                                    taskId = taskId,
                                    virtualDisplayId = displayId,
                                ),
                            )
                            projectionHealth.reset()
                            executor.schedule(
                                {
                                    if (session.phase == NavigationPhase.PROJECTED) {
                                        splitRoutingLease.release()
                                        finishTransfer()
                                    }
                                },
                                PROJECTION_ROUTING_SETTLE_MS,
                                TimeUnit.MILLISECONDS,
                            )
                        } catch (error: Exception) {
                            failProjection(app, taskId, error)
                        }
                    }
                },
            )
        } catch (error: RuntimeException) {
            consumed.set(true)
            failProjection(app, taskId, error)
            return
        }
        executor.schedule(
            {
                if (!consumed.compareAndSet(false, true)) return@schedule
                failProjection(
                    app,
                    taskId,
                    IllegalStateException("navigation surface timed out"),
                )
            },
            PROJECTION_SURFACE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun returnToCentralDisplay(
        focusTask: Boolean = true,
        reprojectAfterReturn: Boolean = false,
    ) {
        TaskMoveOwnership.pulse(TaskMoveOwner.NAVIGATION)
        val app = context ?: return
        beginTransfer(app)
        splitRoutingLease.acquire()
        val taskId = session.taskId
        val packageName = selectedPackage
        val origin = projectedOrigin ?: NavigationProjectionOrigin(
            sourceRootTaskId = taskId ?: -1,
            companionTaskId = 0,
            companionRootTaskId = 0,
        )
        update(
            session.copy(
                phase = NavigationPhase.RETURNING,
                message = "Возвращаю на главный экран",
                resolution = null,
            ),
        )
        var returnedPlan: SplitNavigationReturnPlan? = null
        var splitReconciliationError: Throwable? = null
        try {
            if (taskId != null && origin.sourceRootTaskId > 0) {
                val returnPlan = SplitScreenCoordinator.prepareNavigationReturn(
                    origin.sourceRootTaskId,
                )
                // Once projection has settled the IVI pair is independent. Replaying the
                // companion captured at projection time would overwrite a later picker choice.
                val liveOrigin = origin.copy(
                    sourceRootTaskId = returnPlan.rootTaskId,
                    companionTaskId = 0,
                    companionRootTaskId = 0,
                )
                check(
                    NavigationProxyClient.returnTask(
                        app,
                        packageName,
                        taskId,
                        liveOrigin,
                        focusNavigation = focusTask,
                    ),
                ) { "navigation task return failed" }
                returnedPlan = returnPlan
                splitReconciliationError = runCatching {
                    SplitScreenCoordinator.completeNavigationReturn(
                        plan = returnPlan,
                        taskId = taskId,
                        packageName = packageName,
                    )
                }.exceptionOrNull()
            }
        } catch (error: Exception) {
            Log.w(TAG, "navigation task return failed", error)
            update(
                session.copy(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = "Повторите возврат навигации",
                    details = error.toString(),
                    resolution = FeatureResolution.RETRY,
                ),
            )
            finishTransfer()
            return
        }

        projectedOrigin = null
        projectionHealth.reset()
        NavigationProxyClient.releaseVirtualDisplay()
        ClusterSceneService.hideMap(app)
        update(NavigationSession(taskId = taskId))
        if (splitReconciliationError != null && taskId != null && returnedPlan != null) {
            Log.w(
                TAG,
                "navigation returned but split reconciliation is delayed",
                splitReconciliationError,
            )
            val retryPlan = returnedPlan
            executor.schedule(
                {
                    runCatching {
                        SplitScreenCoordinator.completeNavigationReturn(
                            plan = retryPlan,
                            taskId = taskId,
                            packageName = packageName,
                        )
                    }.onFailure { retryError ->
                        Log.w(TAG, "delayed split reconciliation failed", retryError)
                    }
                },
                RETURN_SETTLE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        if (focusTask || reprojectAfterReturn) {
            executor.schedule(
                {
                    settleReturnedTask(
                        packageName = packageName,
                        focusTask = focusTask,
                        reprojectAfterReturn = reprojectAfterReturn,
                    )
                },
                RETURN_SETTLE_MS,
                TimeUnit.MILLISECONDS,
            )
        } else {
            executor.schedule(
                {
                    splitRoutingLease.release()
                    finishTransfer()
                },
                RETURN_SETTLE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun settleReturnedTask(
        packageName: String,
        focusTask: Boolean,
        reprojectAfterReturn: Boolean,
    ) {
        val app = context
        if (app == null || selectedPackage != packageName) {
            splitRoutingLease.release()
            finishTransfer()
            return
        }
        val liveTask = try {
            NavigationProxyClient.findAllowedTask(app, packageName)
        } catch (error: Exception) {
            val problem = friendlyProxyProblem(error)
            update(
                NavigationSession(
                    phase = NavigationPhase.NEEDS_ACTION,
                    message = problem.message,
                    details = error.toString(),
                    resolution = problem.resolution,
                ),
            )
            splitRoutingLease.release()
            finishTransfer()
            return
        }
        if (liveTask >= 0) {
            update(NavigationSession(taskId = liveTask))
            if (reprojectAfterReturn) {
                projectToCluster()
            } else {
                splitRoutingLease.release()
                finishTransfer()
            }
            return
        }
        if (!focusTask && !reprojectAfterReturn) {
            update(NavigationSession())
            splitRoutingLease.release()
            finishTransfer()
            return
        }
        pendingProjectionAfterOpen = reprojectAfterReturn
        update(NavigationSession(message = "Повторно открываю навигатор"))
        openSelectedApp()
    }

    private fun verifyActiveSession() {
        val app = context ?: return
        val current = session
        // The dashboard has no task and no display of its own to lose track of; it is a view in a
        // window this process owns, and it is there for exactly as long as the scene keeps it.
        if (current.target == NavigationTarget.DASHBOARD) return
        if (current.phase != NavigationPhase.PROJECTED) return
        val taskId = current.taskId ?: return
        val expectedDisplay = current.virtualDisplayId ?: return
        val packageName = selectedPackage
        if (!NavigationProxyClient.isVirtualDisplayAlive(expectedDisplay)) {
            Log.w(TAG, "projection display disappeared display=$expectedDisplay task=$taskId")
            finishExternallyEndedProjection(app, taskId = null)
            return
        }
        val actualDisplay = try {
            NavigationProxyClient.taskDisplayId(app, packageName, taskId)
        } catch (error: Exception) {
            projectionHealth.reset()
            Log.w(
                TAG,
                "projection health check failed; preserving display=$expectedDisplay task=$taskId",
                error,
            )
            NavigationProxyClient.disconnectShell()
            return
        }
        when (val decision = projectionHealth.observe(actualDisplay, expectedDisplay)) {
            NavigationProjectionHealthDecision.Healthy -> Unit
            is NavigationProjectionHealthDecision.Uncertain -> Log.w(
                TAG,
                "projection health uncertain expected=$expectedDisplay " +
                    "actual=${decision.actualDisplayId} " +
                    "confirmation=${decision.confirmationCount}; preserving display",
            )
            is NavigationProjectionHealthDecision.ConfirmedElsewhere -> {
                Log.i(
                    TAG,
                    "projection ended externally task=$taskId " +
                        "display=${decision.actualDisplayId}",
                )
                finishExternallyEndedProjection(app, taskId)
            }
        }
    }

    private fun finishExternallyEndedProjection(app: Context, taskId: Int?) {
        projectedOrigin = null
        projectionHealth.reset()
        NavigationProxyClient.releaseVirtualDisplay()
        ClusterSceneService.hideMap(app)
        update(NavigationSession(taskId = taskId))
        finishTransfer()
        executor.schedule({
            splitRoutingLease.release()
        }, RETURN_SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun failProjection(app: Context, taskId: Int, error: Exception) {
        Log.w(TAG, "navigation projection failed", error)
        val origin = projectedOrigin
        val ownedDisplayId = NavigationProxyClient.currentVirtualDisplayId()
        val ownedDisplayAlive = ownedDisplayId?.let(
            NavigationProxyClient::isVirtualDisplayAlive,
        ) == true
        val actualTaskDisplayId = if (ownedDisplayAlive) {
            try {
                NavigationProxyClient.taskDisplayId(app, selectedPackage, taskId)
            } catch (locationError: Exception) {
                Log.w(
                    TAG,
                    "projection cleanup location unknown; preserving display=$ownedDisplayId " +
                        "task=$taskId",
                    locationError,
                )
                NavigationProxyClient.disconnectShell()
                null
            }
        } else {
            null
        }

        val cleanupDecision = navigationProjectionCleanupDecision(
            ownedDisplayId = ownedDisplayId,
            ownedDisplayAlive = ownedDisplayAlive,
            actualTaskDisplayId = actualTaskDisplayId,
        )
        val safeToRelease = when (cleanupDecision) {
            NavigationProjectionCleanupDecision.RELEASE -> true
            NavigationProjectionCleanupDecision.RETURN_THEN_RELEASE -> {
                if (origin == null) {
                    false
                } else {
                    try {
                        NavigationProxyClient.returnTask(
                            app,
                            selectedPackage,
                            taskId,
                            origin,
                            focusNavigation = false,
                        )
                    } catch (returnError: Exception) {
                        Log.w(TAG, "projection cleanup return failed; preserving display", returnError)
                        NavigationProxyClient.disconnectShell()
                        false
                    }
                }
            }
            NavigationProjectionCleanupDecision.PRESERVE -> false
        }

        if (!safeToRelease) {
            projectionHealth.reset()
            splitRoutingLease.release()
            update(
                NavigationSession(
                    phase = NavigationPhase.PROJECTED,
                    taskId = taskId,
                    virtualDisplayId = ownedDisplayId,
                    message = "Навигация сохранена на приборке; повторите возврат",
                    details = error.toString(),
                    resolution = FeatureResolution.RETRY,
                ),
            )
            finishTransfer()
            return
        }

        SplitScreenCoordinator.onProjectionReturned(taskId)
        projectedOrigin = null
        projectionHealth.reset()
        NavigationProxyClient.releaseVirtualDisplay()
        ClusterSceneService.hideMap(app)
        splitRoutingLease.release()
        update(
            NavigationSession(
                phase = NavigationPhase.NEEDS_ACTION,
                taskId = taskId,
                message = "Повторите перенос навигации",
                details = error.toString(),
                resolution = FeatureResolution.RETRY,
            ),
        )
        finishTransfer()
    }

    /**
     * The target is a property of the selection, not of the step being reported, so it is stamped
     * once here instead of being repeated at every call site that builds a fresh session - and no
     * future one can forget it and quietly hand the driver a navigator's button.
     */
    private fun update(next: NavigationSession) {
        session = next.copy(
            target = if (dashboardSelected()) {
                NavigationTarget.DASHBOARD
            } else {
                NavigationTarget.NAVIGATOR
            },
        )
        onStateChanged?.invoke()
    }

    private fun beginTransfer(app: Context) {
        NavigationTransferOverlay.setTransferActive(app, true)
    }

    private fun finishTransfer() {
        context?.let { NavigationTransferOverlay.setTransferActive(it, false) }
    }

    private data class NavigationProblem(
        val message: String,
        val resolution: FeatureResolution,
    )

    private fun friendlyProxyProblem(error: Exception): NavigationProblem {
        val text = error.message.orEmpty()
        return when {
            text.contains("authorization required", ignoreCase = true) ->
                NavigationProblem(
                    "Откройте ADB Rescue в диагностике",
                    FeatureResolution.CONFIRM_ON_CAR,
                )
            text.contains("authorization pending", ignoreCase = true) ->
                NavigationProblem(
                    "Подтвердите запрос на экране автомобиля",
                    FeatureResolution.CONFIRM_ON_CAR,
                )
            text.contains("refused", ignoreCase = true) ->
                NavigationProblem(
                    "Включите отладку USB в настройках автомобиля",
                    FeatureResolution.ENABLE_CAR_DEBUGGING,
                )
            text.contains("timeout", ignoreCase = true) ->
                NavigationProblem(
                    "Система автомобиля не отвечает",
                    FeatureResolution.RETRY,
                )
            else ->
                NavigationProblem(
                    "Повторите подключение навигации",
                    FeatureResolution.RETRY,
                )
        }
    }
}

/**
 * Долгое владение задачами на время переброса навигатора на приборку и обратно.
 *
 * Раньше это был `hold`/`release` внутри ядра split, то есть на незапущенном split - тихий no-op
 * (`core?.`), и без срока: потерянный `release` держал сверку выключенной до конца процесса. Теперь
 * это общее владение с потолком [TaskMoveOwnership.HANDOFF_MS], который и есть страховка от
 * потерянного отпускания.
 */
internal class NavigationSplitRoutingLease(
    private val ownership: TaskMoveOwnership = TaskMoveOwnership.shared,
) {
    private var lease: TaskMoveLease? = null

    fun acquire() {
        if (lease != null) return
        lease = ownership.acquire(TaskMoveOwner.NAVIGATION, TaskMoveOwnership.HANDOFF_MS)
    }

    fun release() {
        lease?.release()
        lease = null
    }
}
