package dev.denza.apps.feature.split

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class SplitScreenPhase { OFF, STARTING, ACTIVE, ERROR }

data class SplitScreenSession(
    val enabled: Boolean = false,
    val phase: SplitScreenPhase = SplitScreenPhase.OFF,
    val message: String = "",
    val details: String? = null,
)

/** Owns the explicit two-picker BYD split session and the resizeability lease. */
// The stored value is normalized to applicationContext during initialization.
@SuppressLint("StaticFieldLeak")
object SplitScreenCoordinator {
    private const val TAG = "DenzaSplitScreen"
    private const val RESTORE_FAILURE_NOTICE =
        "Не все приложения восстановлены. Выберите приложение вручную"
    private const val POLL_MS = 200L
    private const val RETRY_MS = 1_500L
    private const val EXTERNAL_TASK_BYPASS_MS = 5_000L
    private const val EXPLICIT_PICKER_MODE = true
    private val executor = Executors.newSingleThreadExecutor()
    private val pickerOpenInFlight = AtomicBoolean(false)
    private val nativePickerEventInFlight = AtomicBoolean(false)
    private val dividerReconcileInFlight = AtomicBoolean(false)
    private val homeGateSuspendInFlight = AtomicBoolean(false)
    private val routingLock = Any()
    private val pickerStateLock = Any()

    @Volatile private var context: Context? = null
    @Volatile private var session = SplitScreenSession()
    @Volatile private var onStateChanged: (() -> Unit)? = null
    @Volatile private var initialized = false
    @Volatile private var generation = 0L
    @Volatile private var routingBlockedUntilMs = 0L
    @Volatile private var externalTaskMovesHeld = false
    @Volatile private var pickerState = SplitPickerAutomatonState()
    @Volatile private var pickerStateLoaded = false
    private var activeRouter: SplitShellRouter? = null

    fun initialize(context: Context, onStateChanged: () -> Unit) {
        this.context = context.applicationContext
        this.onStateChanged = onStateChanged
        if (initialized) {
            onStateChanged()
            return
        }
        initialized = true
        ensurePickerStateLoaded(this.context!!)
        val enabled = SplitScreenSettings.isEnabled(context)
        session = SplitScreenSession(enabled = enabled)
        if (enabled) {
            startAsync()
        } else {
            stopAsync()
        }
    }

    fun snapshot(): SplitScreenSession = session

    /** Opens the explicit two-picker product flow from its launcher icon. */
    fun openPickerSession(
        context: Context,
        onComplete: (String?) -> Unit = {},
    ) {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        SplitScreenSettings.setEnabled(app, true)
        if (!pickerOpenInFlight.compareAndSet(false, true)) {
            postResult(onComplete, null)
            return
        }
        generation += 1
        update(
            SplitScreenSession(
                enabled = true,
                phase = SplitScreenPhase.STARTING,
                message = "Открываю разделение экрана",
            ),
        )
        executor.execute {
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                val split = pickerSession(app, adb::shell)
                SplitNativePickerAccessController(
                    shell = adb::shell,
                    leaseStore = SplitScreenSettings.nativePickerAccessLeaseStore(app),
                ).enable()
                SplitResizeabilityController(
                    shell = adb::shell,
                    leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
                ).enable()
                val store = SplitScreenSettings.lastPairStore(app)
                val installed = SplitPickerCatalog.load(app).mapTo(mutableSetOf()) {
                    it.packageName
                }
                val restorable = SplitPickerSelectionPolicy.restorablePair(
                    primaryPackage = store.load(SplitPane.PRIMARY),
                    secondaryPackage = store.load(SplitPane.SECONDARY),
                    installedPackages = installed,
                )
                val expectedApps = expectedPickerApps()
                val owned = split.existingOwnedSession(PICKER_COMPONENT_SET, expectedApps)
                val existing = owned
                    ?.let { owned ->
                        split.revealOwnedSession(owned, PICKER_COMPONENT_SET)
                    }
                if (existing != null) {
                    SplitPickerNotice.publish(app, "")
                    clearPickerState()
                    applyPickerEvent(SplitPickerEvent.OpenRequested)
                    SplitPane.entries.forEach { pane ->
                        val live = existing.getValue(pane)
                        applyPickerEvent(
                            SplitPickerEvent.NativePickerObserved(pane, live.hostTaskId),
                        )
                        applyPickerEvent(
                            SplitPickerEvent.PickerAttached(pane, live.hostTaskId),
                        )
                        if (live.appTaskId != null && live.appPackageName != null) {
                            applyPickerEvent(
                                SplitPickerEvent.AppOpened(
                                    pane = pane,
                                    hostTaskId = live.hostTaskId,
                                    taskId = live.appTaskId,
                                    packageName = live.appPackageName,
                                ),
                            )
                        }
                    }
                    syncLastPairFromPickerState(app)
                    SplitScreenSettings.routingStateStore(app).clear()
                    update(
                        SplitScreenSession(
                            enabled = true,
                            phase = SplitScreenPhase.ACTIVE,
                            message = "",
                        ),
                    )
                    Log.i(TAG, "adopted the existing explicit picker session")
                    postResult(onComplete, null)
                    return@execute
                }
                // An explicit launcher tap is the authoritative start of a new picker session.
                // Rebuild task identity from the live roots instead of carrying process- or
                // install-stale host ids into this run. The separately stored last pair remains.
                clearPickerState()
                applyPickerEvent(
                    SplitPickerEvent.OpenRequested,
                )
                val hostTaskIds = split.openPickers(PICKER_COMPONENTS, restorable)
                SplitPane.entries.forEach { pane ->
                    val hostTaskId = hostTaskIds.getValue(pane)
                    applyPickerEvent(
                        SplitPickerEvent.NativePickerObserved(pane, hostTaskId),
                    )
                    applyPickerEvent(
                        SplitPickerEvent.PickerAttached(pane, hostTaskId),
                    )
                }
                // The old foreground router must never resume an intent after the explicit flow.
                SplitScreenSettings.routingStateStore(app).clear()

                val restoreFailures = mutableListOf<String>()
                SplitPane.entries.forEach { pane ->
                    val packageName = restorable[pane] ?: return@forEach
                    val target = SplitPickerCatalog.resolve(app, packageName)
                    if (target == null) {
                        restoreFailures += packageName
                        return@forEach
                    }
                    runCatching {
                        val placement = split.restoreApp(
                            pickerTaskId = hostTaskIds.getValue(pane),
                            target = target,
                            pickerComponents = PICKER_COMPONENT_SET,
                            reservedPackages = restorable.values.toSet(),
                        )
                        applyPickerEvent(
                            SplitPickerEvent.AppOpened(
                                pane = placement.pane,
                                hostTaskId = placement.hostTaskId,
                                taskId = placement.appTaskId,
                                packageName = placement.packageName,
                            ),
                        )
                    }.onFailure {
                        Log.w(TAG, "failed to restore $packageName in $pane", it)
                        runCatching {
                            split.discardFailedRestoration(
                                pane = pane,
                                packageName = packageName,
                                pickerTaskId = hostTaskIds.getValue(pane),
                            )
                        }.onFailure { cleanupError ->
                            Log.w(TAG, "failed to discard stale $packageName in $pane", cleanupError)
                        }
                        restoreFailures += packageName
                    }
                }
                val message = if (restoreFailures.isEmpty()) "" else {
                    RESTORE_FAILURE_NOTICE
                }
                SplitPickerNotice.publish(app, message)
                update(
                    SplitScreenSession(
                        enabled = true,
                        phase = SplitScreenPhase.ACTIVE,
                        message = message,
                    ),
                )
                postResult(onComplete, null)
            } catch (error: Throwable) {
                Log.w(TAG, "failed to open explicit picker session", error)
                runCatching { applyPickerEvent(SplitPickerEvent.HomeObserved) }
                val message = friendlyError(error, "Не удалось открыть разделение экрана")
                runCatching { SplitPickerNotice.publish(app, message) }
                update(
                    SplitScreenSession(
                        enabled = true,
                        phase = SplitScreenPhase.ERROR,
                        message = message,
                        details = error.toString(),
                    ),
                )
                postResult(onComplete, message)
            } finally {
                adb?.close()
                pickerOpenInFlight.set(false)
                // A service event may have been intentionally suppressed while the explicit
                // pair owned the roots. Reconcile once against the settled native scene.
                onNativePickerVisible(app)
            }
        }
    }

    /** A picker tap has an exact pane and therefore needs no foreground inference. */
    fun selectApp(
        context: Context,
        pickerTaskId: Int,
        packageName: String,
        onComplete: (String?) -> Unit = {},
    ) {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        executor.execute {
            val store = SplitScreenSettings.lastPairStore(app)
            val target = SplitPickerCatalog.resolve(app, packageName)
            if (target == null) {
                postResult(onComplete, "Приложение больше не найдено")
                return@execute
            }
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                SplitResizeabilityController(
                    shell = adb::shell,
                    leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
                ).enable()
                val placement = pickerSession(app, adb::shell).selectApp(
                    pickerTaskId = pickerTaskId,
                    target = target,
                    pickerComponents = PICKER_COMPONENT_SET,
                )
                applyPickerEvent(
                    SplitPickerEvent.AppOpened(
                        pane = placement.pane,
                        hostTaskId = placement.hostTaskId,
                        taskId = placement.appTaskId,
                        packageName = placement.packageName,
                    ),
                )
                check(store.save(placement.pane, packageName)) {
                    "Не удалось сохранить последнюю пару"
                }
                SplitPickerNotice.publish(app, "")
                update(SplitScreenSession(enabled = true, phase = SplitScreenPhase.ACTIVE))
                postResult(onComplete, null)
            } catch (error: Throwable) {
                Log.w(TAG, "failed to open $packageName from picker task $pickerTaskId", error)
                val message = friendlyError(error, "Не удалось открыть приложение в этом окне")
                update(
                    SplitScreenSession(
                        enabled = true,
                        phase = SplitScreenPhase.ERROR,
                        message = message,
                        details = error.toString(),
                    ),
                )
                postResult(onComplete, message)
            } finally {
                adb?.close()
            }
        }
    }

    /** Exact picker reveal event; only the recorded app task can be removed. */
    fun onPickerVisible(
        context: Context,
        hostTaskId: Int,
        onComplete: (String?) -> Unit = {},
    ) {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        if (!SplitScreenSettings.isEnabled(app)) {
            postResult(onComplete, null)
            return
        }
        if (externalTaskMutationInFlight()) {
            postResult(onComplete, null)
            return
        }
        executor.execute {
            if (externalTaskMutationInFlight()) {
                postResult(onComplete, null)
                return@execute
            }
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                val split = pickerSession(app, adb::shell)
                if (!reconcileOwnedSession(app, split, "picker visible")) {
                    postResult(onComplete, null)
                    return@execute
                }
                val observation = split.observePickerTask(hostTaskId, PICKER_COMPONENT_SET)
                if (observation == null || !observation.pickerVisible) {
                    postResult(onComplete, null)
                    return@execute
                }
                val pane = observation.pane
                applyPickerEvent(
                    SplitPickerEvent.PickerAttached(pane, hostTaskId),
                )
                val reduction = applyPickerEvent(
                    SplitPickerEvent.PickerBecameTop(
                        pane = pane,
                        hostTaskId = hostTaskId,
                        observedTaskIds = observation.observedTaskIds,
                    ),
                )
                syncLastPairFromPickerState(app)
                executePickerActions(split, reduction.actions)
                postResult(onComplete, null)
            } catch (error: Throwable) {
                Log.w(TAG, "failed to reconcile visible picker", error)
                postResult(
                    onComplete,
                    friendlyError(error, "Не удалось восстановить окна"),
                )
            } finally {
                adb?.close()
            }
        }
    }

    /**
     * BYD may swap only the visible top tasks during a divider resize and strand a hidden picker
     * base in the old root. Wait for the native transition, repair exact recorded ownership when
     * necessary, then adopt the settled topology before later lifecycle events can overwrite it.
     */
    fun onDividerResized(context: Context) {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        if (pickerOpenInFlight.get() ||
            !SplitScreenSettings.isEnabled(app) ||
            externalTaskMutationInFlight() ||
            !dividerReconcileInFlight.compareAndSet(false, true)
        ) {
            return
        }
        try {
            executor.execute {
                var adb: LocalAdbClient.PersistentShellSession? = null
                try {
                    adb = DenzaLocalAdb.client(app).openPersistentShell()
                    reconcileOwnedSession(
                        app = app,
                        split = pickerSession(app, adb::shell),
                        reason = "divider resized",
                        settleDividerResize = true,
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "failed to reconcile resized split", error)
                } finally {
                    runCatching { adb?.close() }
                        .onFailure { Log.w(TAG, "divider resize shell close failed", it) }
                    dividerReconcileInFlight.set(false)
                }
            }
        } catch (error: RuntimeException) {
            dividerReconcileInFlight.set(false)
            Log.w(TAG, "failed to schedule split resize reconciliation", error)
        }
    }

    /**
     * Reconciles a picker that stopped without treating an ordinary app launch as a pane close.
     * A selected app leaves its permanent picker in the same native root; the BYD dismiss
     * gesture reparents that exact picker task outside both roots before expanding its peer pane.
     */
    fun onPickerHidden(context: Context, hostTaskId: Int) {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        if (!SplitScreenSettings.isEnabled(app)) return
        val pane = SplitPane.entries.firstOrNull {
            currentPickerState().slot(it).hostTaskId == hostTaskId
        } ?: return
        executor.execute {
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                val split = pickerSession(app, adb::shell)
                if (split.observePickerTask(hostTaskId, PICKER_COMPONENT_SET) != null) {
                    return@execute
                }
                val reduction = applyPickerEvent(
                    SplitPickerEvent.PickerTaskGone(pane, hostTaskId),
                )
                syncLastPairFromPickerState(app)
                executePickerActions(split, reduction.actions)
            } catch (error: Throwable) {
                Log.w(TAG, "failed to reconcile hidden picker $hostTaskId", error)
            } finally {
                adb?.close()
            }
        }
    }

    /** Accepts only Home from the app-wide observer; all other global window traffic is ignored. */
    @JvmStatic
    fun onGlobalAccessibilityWindowChanged(context: Context, packageName: String?) {
        if (
            SplitAccessibilityEventPolicy.target(packageName, className = null) ==
            SplitAccessibilityEventTarget.HOME
        ) {
            Log.i(TAG, "Home hint received from global accessibility observer")
            onHomeVisible(context)
        }
    }

    /** A Home accessibility event is only a hint; firmware area 0 is the mutation authority. */
    @JvmStatic
    fun onHomeVisible(context: Context) {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        if (!SplitScreenSettings.isEnabled(app) ||
            !homeGateSuspendInFlight.compareAndSet(false, true)
        ) {
            return
        }
        try {
            executor.execute {
                var adb: LocalAdbClient.PersistentShellSession? = null
                try {
                    adb = DenzaLocalAdb.client(app).openPersistentShell()
                    if (pickerSession(app, adb::shell).suspendOwnedGateForHome()) {
                        applyPickerEvent(SplitPickerEvent.HomeObserved)
                        Log.i(TAG, "suspended owned split gate after Home")
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "failed to suspend split gate after Home", error)
                } finally {
                    homeGateSuspendInFlight.set(false)
                    runCatching { adb?.close() }
                        .onFailure { Log.w(TAG, "Home gate shell close failed", it) }
                }
            }
        } catch (error: RuntimeException) {
            homeGateSuspendInFlight.set(false)
            Log.w(TAG, "failed to schedule Home gate suspension", error)
        }
    }

    /** Accessibility event for the stock picker created by dragging a fullscreen pane open. */
    @JvmStatic
    fun onNativePickerVisible(context: Context): Boolean {
        val app = context.applicationContext
        this.context = app
        ensurePickerStateLoaded(app)
        if (pickerOpenInFlight.get() ||
            !SplitScreenSettings.isEnabled(app) ||
            !nativePickerEventInFlight.compareAndSet(false, true)
        ) {
            return false
        }
        try {
            executor.execute {
                var adb: LocalAdbClient.PersistentShellSession? = null
                try {
                    adb = DenzaLocalAdb.client(app).openPersistentShell()
                    val split = pickerSession(app, adb::shell)
                    // Accessibility observes SplitScreenListActivity while the finger is still
                    // moving. Prepare the shell in the background, but do not inspect or mutate
                    // either root until BYD is balanced and the active pointer has been released.
                    if (!split.awaitNativePickerCommit()) return@execute
                    SplitPane.entries.forEach { pane ->
                        val observation = split.observePane(pane, PICKER_COMPONENT_SET)
                        val hostTaskId = observation.hostTaskId ?: return@forEach
                        if (!observation.nativeHostVisible || observation.pickerVisible) {
                            return@forEach
                        }
                        val reduction = applyPickerEvent(
                            SplitPickerEvent.NativePickerObserved(
                                pane,
                                hostTaskId,
                                observation.observedTaskIds,
                            ),
                        )
                        val attachRequested = reduction.actions.any {
                            it is SplitPickerAction.AttachPicker
                        }
                        try {
                            executePickerActions(split, reduction.actions)
                        } catch (error: Throwable) {
                            if (attachRequested) {
                                applyPickerEvent(
                                    SplitPickerEvent.PickerAttachFailed(pane, hostTaskId),
                                )
                            }
                            Log.w(TAG, "failed to attach picker in $pane", error)
                            return@forEach
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "failed to host picker in native vacancy", error)
                } finally {
                    nativePickerEventInFlight.set(false)
                    runCatching { adb?.close() }
                        .onFailure { Log.w(TAG, "native picker shell close failed", it) }
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "failed to schedule native picker replacement", error)
            nativePickerEventInFlight.set(false)
            return false
        }
        return true
    }

    @JvmStatic
    fun onProjectionStarted(taskId: Int) {
        val pane = SplitPane.entries.firstOrNull {
            currentPickerState().slot(it).appTaskId == taskId
        } ?: return
        applyPickerEvent(
            SplitPickerEvent.ProjectionStarted(pane, taskId),
        )
    }

    @JvmStatic
    fun onProjectionReturned(taskId: Int) {
        val pane = SplitPane.entries.firstOrNull {
            currentPickerState().slot(it).appTaskId == taskId
        } ?: return
        val reduction = applyPickerEvent(
            SplitPickerEvent.ProjectionReturned(pane, taskId),
        )
        if (reduction.actions.isEmpty()) return
        val app = context ?: return
        executor.execute {
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                executePickerActions(pickerSession(app, adb::shell), reduction.actions)
            } catch (error: Throwable) {
                Log.w(TAG, "failed to return projected task fullscreen", error)
            } finally {
                adb?.close()
            }
        }
    }

    /**
     * Resolves the live IVI destination before Navigation moves its task back from the cluster.
     * This is synchronous by design: the navigation lease is already held and no router tick may
     * observe a half-cleared pane between this decision and the task move.
     */
    internal fun prepareNavigationReturn(originalRootTaskId: Int): SplitNavigationReturnPlan {
        val app = context ?: error("Split coordinator is not initialized")
        ensurePickerStateLoaded(app)
        val adb = DenzaLocalAdb.client(app).openPersistentShell()
        try {
            val split = pickerSession(app, adb::shell)
            if (!SplitScreenSettings.isEnabled(app)) {
                return SplitNavigationReturnPlan(
                    pane = null,
                    rootTaskId = split.fullIviRootTaskId(),
                    hostTaskId = null,
                    fullscreen = true,
                )
            }
            val plan = split.prepareNavigationReturn(
                originalRootTaskId = originalRootTaskId,
                pickerComponents = PICKER_COMPONENT_SET,
                expectedApps = expectedPickerApps(),
            )
            return plan
        } finally {
            adb.close()
        }
    }

    internal fun completeNavigationReturn(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
    ) {
        val app = context ?: return
        ensurePickerStateLoaded(app)
        if (plan.fullscreen) {
            val pane = plan.pane ?: return
            val adb = DenzaLocalAdb.client(app).openPersistentShell()
            try {
                pickerSession(app, adb::shell).returnRecordedTaskFullscreen(
                    pane = pane,
                    taskId = taskId,
                    packageName = packageName,
                )
                applyPickerEvent(SplitPickerEvent.HomeObserved)
            } finally {
                adb.close()
            }
            return
        }

        val adb = DenzaLocalAdb.client(app).openPersistentShell()
        try {
            val split = pickerSession(app, adb::shell)
            plan.displacedTasks.forEach { displaced ->
                split.removeRecordedTask(displaced.taskId, displaced.packageName)
            }
            val placement = split.verifyNavigationReturned(
                plan = plan,
                taskId = taskId,
                packageName = packageName,
                pickerComponents = PICKER_COMPONENT_SET,
            )
            applyPickerEvent(
                SplitPickerEvent.PickerBecameTop(
                    pane = placement.pane,
                    hostTaskId = placement.hostTaskId,
                    observedTaskIds = emptySet(),
                ),
            )
            applyPickerEvent(
                SplitPickerEvent.AppOpened(
                    pane = placement.pane,
                    hostTaskId = placement.hostTaskId,
                    taskId = placement.appTaskId,
                    packageName = placement.packageName,
                ),
            )
            syncLastPairFromPickerState(app)
        } finally {
            adb.close()
        }
    }

    /**
     * Navigation owns its explicit moves to and from the instrument display.
     * Keep the router out of the way until that move and its configuration
     * changes have settled. The target itself is preserved and reconciled
     * against the post-move scene instead of being discarded mid-transition.
     */
    @JvmStatic
    fun bypassExternalTaskMoves() {
        val blockedUntil = SystemClock.elapsedRealtime() + EXTERNAL_TASK_BYPASS_MS
        synchronized(routingLock) {
            routingBlockedUntilMs = maxOf(routingBlockedUntilMs, blockedUntil)
            activeRouter?.pauseForExternalTaskMoves()
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
            activeRouter?.pauseForExternalTaskMoves()
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
        val expectedHostTaskIds = currentPickerState().slots.values
            .mapNotNullTo(mutableSetOf()) { it.hostTaskId }
        generation += 1
        update(
            SplitScreenSession(
                phase = SplitScreenPhase.STARTING,
                message = "Закрываю разделение экрана",
            ),
        )
        stopAsync(expectedHostTaskIds)
    }

    private fun stopAsync(
        expectedHostTaskIds: Set<Int> = currentPickerState().slots.values
            .mapNotNullTo(mutableSetOf()) { it.hostTaskId },
    ) {
        val app = context ?: return
        val stopGeneration = generation
        executor.execute {
            if (generation != stopGeneration || SplitScreenSettings.isEnabled(app)) {
                return@execute
            }
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                val failures = mutableListOf<Throwable>()
                fun cleanup(step: () -> Unit) {
                    runCatching(step).exceptionOrNull()?.let(failures::add)
                }

                if (EXPLICIT_PICKER_MODE) {
                    cleanup {
                        pickerSession(app, adb::shell)
                            .closePickers(
                                PICKER_COMPONENTS,
                                expectedHostTaskIds,
                            )
                    }
                }
                // These leases are independent. A late foreground change must not leave global
                // resizeability or picker accessibility enabled merely because scene validation
                // failed after the native split had already closed.
                cleanup {
                    SplitResizeabilityController(
                        shell = adb::shell,
                        leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
                    ).restore()
                }
                cleanup {
                    SplitNativePickerAccessController(
                        shell = adb::shell,
                        leaseStore = SplitScreenSettings.nativePickerAccessLeaseStore(app),
                    ).restore()
                }
                cleanup(::clearPickerState)

                failures.firstOrNull()?.let { first ->
                    failures.drop(1).forEach(first::addSuppressed)
                    throw first
                }
                update(SplitScreenSession())
            } catch (error: Throwable) {
                if (generation != stopGeneration || SplitScreenSettings.isEnabled(app)) {
                    return@execute
                }
                Log.w(TAG, "failed to finish split shutdown", error)
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
        if (EXPLICIT_PICKER_MODE) {
            prepareExplicitModeAsync()
            return
        }
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

    private fun prepareExplicitModeAsync() {
        val app = context ?: return
        generation += 1
        val currentGeneration = generation
        update(
            SplitScreenSession(
                enabled = true,
                phase = SplitScreenPhase.STARTING,
                message = "Подготавливаю Split screen",
            ),
        )
        executor.execute {
            var adb: LocalAdbClient.PersistentShellSession? = null
            try {
                if (!isCurrent(currentGeneration)) return@execute
                adb = DenzaLocalAdb.client(app).openPersistentShell()
                SplitNativePickerAccessController(
                    shell = adb::shell,
                    leaseStore = SplitScreenSettings.nativePickerAccessLeaseStore(app),
                ).enable()
                SplitResizeabilityController(
                    shell = adb::shell,
                    leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
                ).enable()
                SplitScreenSettings.routingStateStore(app).clear()
                if (isCurrent(currentGeneration)) {
                    update(SplitScreenSession(enabled = true, phase = SplitScreenPhase.ACTIVE))
                    // Reattach a custom picker if our process was recreated while a stock
                    // vacancy was already visible. Hidden hosts are rejected by observation.
                    onNativePickerVisible(app)
                }
            } catch (error: Throwable) {
                if (!isCurrent(currentGeneration)) return@execute
                Log.w(TAG, "failed to prepare explicit split mode", error)
                update(
                    SplitScreenSession(
                        enabled = true,
                        phase = SplitScreenPhase.ERROR,
                        message = friendlyError(error, "Не удалось подготовить Split screen"),
                        details = error.toString(),
                    ),
                )
            } finally {
                adb?.close()
            }
        }
    }

    private fun runRoutingSession(app: Context, currentGeneration: Long) {
        var router: SplitShellRouter? = null
        var adb: LocalAdbClient.PersistentShellSession? = null
        try {
            if (!isCurrent(currentGeneration)) return
            adb = DenzaLocalAdb.client(app).openPersistentShell()
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
                router.pauseForExternalTaskMoves()
                false
            }
        }
    }

    private fun externalTaskMutationInFlight(): Boolean = synchronized(routingLock) {
        externalTaskMovesHeld || SystemClock.elapsedRealtime() < routingBlockedUntilMs
    }

    private fun update(next: SplitScreenSession) {
        session = next
        onStateChanged?.invoke()
    }

    private fun executePickerActions(
        split: SplitPickerShellSession,
        actions: List<SplitPickerAction>,
    ) {
        actions.forEach { action ->
            when (action) {
                SplitPickerAction.PrepareNativeSession -> Unit
                is SplitPickerAction.AttachPicker -> {
                    val pickerTaskId = split.attachPicker(
                        pane = action.pane,
                        hostTaskId = action.hostTaskId,
                        pickerComponent = PICKER_COMPONENT,
                    )
                    applyPickerEvent(
                        SplitPickerEvent.PickerAttached(action.pane, pickerTaskId),
                    )
                }
                is SplitPickerAction.RemoveExactTask ->
                    split.removeRecordedTask(action.taskId, action.packageName)
                is SplitPickerAction.RemovePickerArtifact ->
                    split.removePickerArtifact(action.hostTaskId, PICKER_COMPONENT_SET)
                is SplitPickerAction.ReturnTaskFullscreen ->
                    split.returnRecordedTaskFullscreen(
                        pane = action.pane,
                        taskId = action.taskId,
                        packageName = action.packageName,
                    )
            }
        }
    }

    private fun reconcileOwnedSession(
        app: Context,
        split: SplitPickerShellSession,
        reason: String,
        settleDividerResize: Boolean = false,
    ): Boolean {
        val before = currentPickerState()
        val live = if (settleDividerResize) {
            split.reconcileDividerResize(
                pickerComponents = PICKER_COMPONENT_SET,
                previousPanes = resizeExpectation(before).orEmpty(),
            )
        } else {
            split.existingOwnedSession(PICKER_COMPONENT_SET)
        }
        val event = if (live != null) {
            SplitPickerEvent.DividerResized(
                panes = live.mapValues { (_, pane) ->
                    SplitPickerObservedPane(
                        hostTaskId = pane.hostTaskId,
                        appTaskId = pane.appTaskId,
                        packageName = pane.appPackageName,
                    )
                },
            )
        } else {
            val expectedTaskIds = SplitPane.entries.associateWith { pane ->
                val slot = before.slot(pane)
                setOfNotNull(slot.hostTaskId, slot.appTaskId)
            }
            val collapsed = split.collapsedOwnedSession(
                pickerComponents = PICKER_COMPONENT_SET,
                expectedTaskIds = expectedTaskIds,
            ) ?: return false
            SplitPickerEvent.PaneCollapsed(
                survivor = collapsed.pane,
                pane = SplitPickerObservedPane(
                    hostTaskId = collapsed.hostTaskId,
                    appTaskId = collapsed.appTaskId,
                    packageName = collapsed.appPackageName,
                ),
            )
        }
        val reduction = applyPickerEvent(event)
        if (reduction.state != before) {
            syncLastPairFromPickerState(app)
            Log.i(TAG, "reconciled explicit picker session after $reason")
        }
        executePickerActions(split, reduction.actions)
        return true
    }

    private fun resizeExpectation(
        state: SplitPickerAutomatonState,
    ): Map<SplitPane, SplitPickerObservedPane>? {
        val panes = mutableMapOf<SplitPane, SplitPickerObservedPane>()
        SplitPane.entries.forEach { pane ->
            val slot = state.slot(pane)
            val hostTaskId = slot.hostTaskId ?: return null
            panes[pane] = when (slot.kind) {
                SplitPickerSlotKind.PICKER -> SplitPickerObservedPane(hostTaskId)
                SplitPickerSlotKind.APP -> SplitPickerObservedPane(
                    hostTaskId = hostTaskId,
                    appTaskId = slot.appTaskId ?: return null,
                    packageName = slot.packageName ?: return null,
                )
                else -> return null
            }
        }
        return panes
    }

    private fun applyPickerEvent(event: SplitPickerEvent): SplitPickerReduction =
        synchronized(pickerStateLock) {
            val reduction = SplitPickerAutomaton.reduce(pickerState, event)
            if (reduction.state != pickerState) {
                val app = context ?: error("Split coordinator is not initialized")
                check(
                    SplitScreenSettings.pickerAutomatonStore(app).save(reduction.state),
                ) { "Не удалось сохранить состояние split-пикера" }
                pickerState = reduction.state
            }
            reduction
        }

    private fun currentPickerState(): SplitPickerAutomatonState =
        synchronized(pickerStateLock) { pickerState }

    private fun expectedPickerApps(): Map<SplitPane, SplitPickerExpectedApp> =
        SplitPane.entries.mapNotNull { pane ->
            val slot = currentPickerState().slot(pane)
            val taskId = slot.appTaskId
            val packageName = slot.packageName
            if (
                slot.kind == SplitPickerSlotKind.APP &&
                taskId != null &&
                packageName != null
            ) {
                pane to SplitPickerExpectedApp(taskId, packageName)
            } else {
                null
            }
        }.toMap()

    /** Last-pair persistence follows the settled IVI scene, never a historical pane label. */
    private fun syncLastPairFromPickerState(app: Context) {
        val packages = SplitPickerSelectionPolicy.settledPair(currentPickerState())
        check(SplitScreenSettings.lastPairStore(app).replace(packages)) {
            "Не удалось сохранить последнюю пару"
        }
    }

    private fun ensurePickerStateLoaded(app: Context) {
        if (pickerStateLoaded) return
        synchronized(pickerStateLock) {
            if (pickerStateLoaded) return
            pickerState = SplitScreenSettings.pickerAutomatonStore(app).load()
            pickerStateLoaded = true
        }
    }

    private fun clearPickerState() {
        synchronized(pickerStateLock) {
            val app = context ?: return
            check(SplitScreenSettings.pickerAutomatonStore(app).clear()) {
                "Не удалось очистить состояние split-пикера"
            }
            pickerState = SplitPickerAutomatonState()
            pickerStateLoaded = true
        }
    }

    private fun postResult(callback: (String?) -> Unit, error: String?) {
        Handler(Looper.getMainLooper()).post { callback(error) }
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
            text.contains("authorization required", ignoreCase = true) ->
                "Откройте ADB Rescue в диагностике"
            text.contains("authorization pending", ignoreCase = true) ->
                "Подтвердите ADB-ключ на экране автомобиля"
            text.contains("refused", ignoreCase = true) -> "Включите ADB на машине"
            text.contains("timeout", ignoreCase = true) -> "ADB пока не отвечает"
            text.startsWith("Приложение уже открыто на другом экране") -> text
            text.startsWith("Сначала верните приложение с другого экрана") -> text
            text.startsWith("Это приложение не поддерживает два окна") -> text
            else -> fallback
        }
    }

    private fun pickerSession(
        context: Context,
        shell: (String) -> String,
    ): SplitPickerShellSession = SplitPickerShellSession(
        shell = shell,
        apkPath = context.applicationInfo.sourceDir,
        gateLeaseStore = SplitScreenSettings.gateLeaseStore(context),
    )

    private val EXCLUDED_PACKAGES = setOf(
        "com.android.launcher3",
        "dev.denza.apps",
    )

    private val PICKER_COMPONENTS = mapOf(
        SplitPane.PRIMARY to PICKER_COMPONENT,
        SplitPane.SECONDARY to PICKER_COMPONENT,
    )

    private const val PICKER_COMPONENT =
        "$SPLIT_HOST_PACKAGE/$SPLIT_PICKER_ACTIVITY"
    private val PICKER_COMPONENT_SET = setOf(PICKER_COMPONENT)
}
