package dev.denza.apps.feature.split

/**
 * The coordinator, without Android.
 *
 * Every input of appendix A.1 enters here and becomes exactly one actor operation; the actor
 * decides what runs, what waits and what dies (contract section 4), the automaton decides what the
 * product means by it (invariant 12), and the shell recipes stay the live-proven layer they already
 * are. Android lives only in the thin `SplitScreenCoordinator` facade that builds this class, which
 * is what makes the K-scenarios of appendix B.3 ordinary unit tests.
 */

internal const val SPLIT_PICKER_COMPONENT = "$SPLIT_HOST_PACKAGE/$SPLIT_PICKER_ACTIVITY"

internal val SPLIT_PICKER_COMPONENTS: Map<SplitPane, String> =
    SplitPane.entries.associateWith { SPLIT_PICKER_COMPONENT }

internal val SPLIT_PICKER_COMPONENT_SET: Set<String> = setOf(SPLIT_PICKER_COMPONENT)

/** One persistent shell for one operation. Closed by the operation lifecycle, never left open. */
internal interface SplitShellHandle : AutoCloseable {
    fun shell(command: String): String

    override fun close()
}

internal fun interface SplitShellFactory {
    fun open(): SplitShellHandle
}

/** The waiting window of one `OPEN` (1.3.1). Closing twice is inert. */
internal interface SplitOverlayLease {
    fun close()

    fun closeImmediately()
}

internal fun interface SplitOverlayOwner {
    fun begin(): SplitOverlayLease
}

/** Where a user-visible short message goes (U5). */
internal fun interface SplitNoticeSink {
    fun publish(message: String)
}

/** The launch catalog, rebuilt from the current package state on every read (1.4.2). */
internal interface SplitLaunchCatalog {
    fun installedPackages(): Set<String>

    fun resolve(packageName: String): SplitLaunchTarget?
}

/**
 * One firmware-global setting the product borrows while its scene is alive.
 *
 * [ownedValue] is what the journal records so a rollback can put back exactly what was displaced;
 * `null` means the product does not hold this lease, and both [enable] and [restore] are then
 * expected to be side-effect free.
 */
internal object SplitLeaseKind {
    /** Android's global `force_resizable_activities`, needed by every pane the product fills. */
    const val RESIZEABILITY = "resizeability"

    /** The accessibility observer that reports the stock picker of an edge drag (1.8.5). */
    const val PICKER_ACCESS = "picker-access"
}

internal interface SplitLeaseController {
    val kind: String

    fun ownedValue(): String?

    fun enable(shell: (String) -> String)

    fun restore(shell: (String) -> String)
}

/** Diagnostic sink for facts the contract requires to be recorded, notably tx125 (1.12). */
internal fun interface SplitDiagnosticLog {
    fun log(message: String)
}

/**
 * The ephemeral live topology of the product scene: which task is the permanent picker base of a
 * pane and which task sits above it.
 *
 * It is deliberately **not** durable (invariant 4): a fresh process starts with none of it, so a
 * reboot or a process death can never make the product act on someone else's numbers. Within a
 * living process it is a hint and nothing more - every recipe that consumes it (`existingOwnedSession`,
 * `reconcileDividerResize`, `collapsedOwnedSession`) re-verifies each id against a fresh snapshot by
 * exact component and bounds identity before it moves anything, exactly like `vacancyApp`.
 */
internal typealias SplitLiveScene = Map<SplitPane, SplitPickerLivePane>

internal const val SPLIT_RESTORE_FAILURE_NOTICE =
    "Не все приложения восстановлены. Выберите приложение вручную"

internal class SplitCoordinatorCore(
    private val shellFactory: SplitShellFactory,
    private val clock: SplitClock,
    private val store: SplitStateStore,
    private val actor: SplitActor,
    private val overlayOwner: SplitOverlayOwner,
    private val notices: SplitNoticeSink,
    private val catalog: SplitLaunchCatalog,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val leases: List<SplitLeaseController>,
    private val apkPath: String,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val log: SplitDiagnosticLog = SplitDiagnosticLog {},
    private val post: (() -> Unit) -> Unit = { action -> action() },
) {
    private val stateLock = Any()
    private val routingLock = Any()

    private var state = SplitState()
    private var live: SplitLiveScene = emptyMap()
    private var notice: String = ""
    private var busy: Busy? = null
    private var failure: Failure? = null
    private var openTicket: SplitTicket? = null
    private var routingBlockedUntilMs = 0L
    private var externalTaskMovesHeld = false
    private var loaded = false

    @Volatile
    private var session = SplitScreenSession()

    @Volatile
    private var onStateChanged: (() -> Unit)? = null

    // region product inputs (appendix A.1)

    /**
     * Contract 1.11, K7: cold initialisation is strictly read-only. It loads the durable snapshot,
     * publishes the session and stops - no scene is restored, no lease is taken, no command is sent,
     * whether the toggle is on or off.
     */
    fun initialize(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        ready()
        publish()
    }

    fun snapshot(): SplitScreenSession {
        ready()
        return session
    }

    /**
     * Loads the durable snapshot once, on whichever input arrives first.
     *
     * An accessibility service can reach the product before the repository has initialised it, and
     * a coordinator that answered such an event from a default state would act as if the toggle
     * were off. Loading is a preferences read: it is not a mutation and never becomes one.
     */
    private fun ready() {
        synchronized(stateLock) {
            if (loaded) return
            loaded = true
            val durable = store.load()
            state = SplitState(enabled = durable.enabled, slots = durable.slots)
            session = sessionOf()
        }
    }

    /** Contract 1.2: enabling only arms the product, disabling ends the scene and keeps the pair. */
    fun setEnabled(enabled: Boolean) {
        ready()
        if (enabled) {
            submitEnable()
            return
        }
        markBusy(DISABLE_LABEL, enabled = false, message = "Закрываю разделение экрана")
        submit { work -> DisableOperation(work) }
    }

    /**
     * Contract 1.3. One tap is one cancellable `OPEN` with one waiting window; a second tap joins
     * the live one and gets its outcome instead of a premature success (1.3.7, K4).
     */
    fun openPickerSession(onComplete: (String?) -> Unit = {}) {
        ready()
        // The launcher entry only exists while the toggle is on, so a tap on it is also the
        // authoritative repair of a persisted mismatch. It is a store write and nothing else.
        if (!currentState().enabled) submitEnable()
        val joined = synchronized(stateLock) { openTicket?.takeUnless(SplitTicket::isComplete) }
        if (joined != null) {
            joined.onComplete { outcome -> report(onComplete, outcome, OPEN_FAILURE) }
            return
        }
        val overlay = overlayOwner.begin()
        markBusy(OPEN_LABEL, enabled = true, message = "Открываю разделение экрана")
        val ticket = submit { work -> OpenOperation(work) }
        synchronized(stateLock) { openTicket = ticket }
        ticket.onComplete { outcome ->
            if (outcome is SplitOutcome.Committed) overlay.close() else overlay.closeImmediately()
            report(onComplete, outcome, OPEN_FAILURE)
        }
    }

    /** Contract 1.5: a picker tap names its pane, so no foreground inference is ever needed. */
    fun selectApp(pickerTaskId: Int, packageName: String, onComplete: (String?) -> Unit = {}) {
        ready()
        val target = catalog.resolve(packageName)
        if (target == null) {
            post { onComplete("Приложение больше не найдено") }
            return
        }
        submit { work -> SelectOperation(work, pickerTaskId, target) }
            .onComplete { outcome -> report(onComplete, outcome, SELECT_FAILURE) }
    }

    /** Contract 1.6.2, 1.7.1: a revealed picker is a hint that its app may have closed. */
    fun pickerVisible(hostTaskId: Int?, onComplete: (String?) -> Unit = {}) {
        ready()
        submitReconcile(SplitReconcileKind.PickerVisible(hostTaskId))
            .onComplete { outcome -> report(onComplete, outcome, RECONCILE_FAILURE) }
    }

    /** Contract 1.6.3, 1.7.4: a picker that left the panel roots is a dismissed pane. */
    fun pickerHidden(hostTaskId: Int) {
        ready()
        submitReconcile(SplitReconcileKind.PickerHidden(hostTaskId))
    }

    /** Contract 1.8.1-1.8.3: the settled topology after a divider move, coalesced to one. */
    fun dividerResized() {
        ready()
        submitReconcile(SplitReconcileKind.DividerResized)
    }

    /** Contract 1.9.1: Home is the priority event that cancels the work the user walked away from. */
    fun homeVisible() {
        ready()
        submit { work -> HomeOperation(work) }
    }

    /** Contract 1.8.5: the stock picker of an edge drag, only inside a live product scene. */
    fun nativePickerVisible(): Boolean {
        ready()
        submit { work -> EdgeOperation(work) }
        return true
    }

    // endregion

    // region navigation bridge (temporary, wave 5b)

    /**
     * TODO(wave 5b): actor-ify the navigation path under [SplitInputPriority.NAV].
     *
     * Navigation still owns its own synchronous lifecycle exactly as it does today (A.3.9): it holds
     * the routing lease across the move and cannot afford to queue behind a passive hint. The single
     * change made here is that the pane is resolved from a live snapshot instead of a persisted task
     * id, and the result enters the automaton as a settled fact.
     */
    fun projectionStarted(taskId: Int) {
        ready()
        val pane = resolveProjectedPane(taskId) ?: return
        applyBridgeFact(SplitFact.ProjectionStarted(pane))
    }

    /** TODO(wave 5b): see [projectionStarted]. */
    @Suppress("UNUSED_PARAMETER")
    fun projectionReturned(taskId: Int) {
        ready()
        applyBridgeFact(SplitFact.ProjectionReturned)
    }

    /** TODO(wave 5b): see [projectionStarted]. */
    fun prepareNavigationReturn(originalRootTaskId: Int): SplitNavigationReturnPlan {
        ready()
        return withBridgeShell { split ->
            if (!currentState().enabled) {
                return@withBridgeShell SplitNavigationReturnPlan(
                    pane = null,
                    rootTaskId = split.fullIviRootTaskId(),
                    hostTaskId = null,
                    fullscreen = true,
                )
            }
            split.prepareNavigationReturn(
                originalRootTaskId = originalRootTaskId,
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
                expectedApps = expectedApps(currentLive()),
            )
        }
    }

    /** TODO(wave 5b): see [projectionStarted]. */
    fun completeNavigationReturn(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
    ) {
        withBridgeShell { split ->
            if (plan.fullscreen) {
                val pane = plan.pane ?: return@withBridgeShell
                split.returnRecordedTaskFullscreen(pane, taskId, packageName)
                applyBridgeFact(SplitFact.HomeConfirmed)
                return@withBridgeShell
            }
            // 1.10.4: the vacancy occupant is removed by exact identity, never by package.
            plan.displacedTasks.forEach { displaced ->
                split.removeRecordedTask(displaced.taskId, displaced.packageName)
            }
            val placement = split.verifyNavigationReturned(
                plan = plan,
                taskId = taskId,
                packageName = packageName,
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
            )
            synchronized(stateLock) {
                live = live + (
                    placement.pane to SplitPickerLivePane(
                        pane = placement.pane,
                        hostTaskId = placement.hostTaskId,
                        appTaskId = placement.appTaskId,
                        appPackageName = placement.packageName,
                    )
                    )
            }
            applyBridgeFact(SplitFact.ProjectionReturned)
        }
    }

    /** Navigation owns the navigator task while it moves; passive reconciliation stands aside. */
    fun bypassExternalTaskMoves() {
        val blockedUntil = clock.nowMs() + EXTERNAL_TASK_BYPASS_MS
        synchronized(routingLock) {
            routingBlockedUntilMs = maxOf(routingBlockedUntilMs, blockedUntil)
        }
    }

    fun holdExternalTaskMoves() {
        synchronized(routingLock) { externalTaskMovesHeld = true }
    }

    fun releaseExternalTaskMoves() {
        synchronized(routingLock) { externalTaskMovesHeld = false }
    }

    fun externalTaskMutationInFlight(): Boolean = synchronized(routingLock) {
        externalTaskMovesHeld || clock.nowMs() < routingBlockedUntilMs
    }

    // endregion

    fun shutdown() {
        actor.shutdown()
    }

    // region internals

    internal fun currentState(): SplitState = synchronized(stateLock) { state }

    internal fun currentLive(): SplitLiveScene = synchronized(stateLock) { live }

    private fun submitEnable(): SplitTicket = submit { work -> EnableOperation(work) }

    private fun submitReconcile(kind: SplitReconcileKind): SplitTicket =
        submit { work -> ReconcileOperation(work, kind) }

    /**
     * Builds one workspace per operation and wraps the operation in the lifecycle that closes that
     * workspace and publishes its state, on the worker thread, whatever the outcome.
     */
    private fun submit(factory: (SplitOperationWorkspace) -> SplitCoreOperation<*>): SplitTicket {
        val workspace = SplitOperationWorkspace(
            shellFactory = shellFactory,
            store = store,
            catalog = catalog,
            notices = notices,
            leases = leases,
            gateLeaseStore = gateLeaseStore,
            apkPath = apkPath,
            sleeper = sleeper,
            diagnostics = log,
            readState = ::currentState,
            readLive = ::currentLive,
            externalMoveInFlight = ::externalTaskMutationInFlight,
            publisher = ::publishSettled,
        )
        val operation = factory(workspace)
        val ticket = actor.submit(SplitOperationLifecycle(operation, workspace))
        ticket.onComplete { outcome -> finishOperation(operation.label, outcome) }
        return ticket
    }

    /** The single writer of semantic state outside the automaton itself: the worker, on commit. */
    private fun publishSettled(next: SplitState, nextLive: SplitLiveScene, nextNotice: String?) {
        synchronized(stateLock) {
            state = next
            live = if (next.scene == null) emptyMap() else nextLive
            if (nextNotice != null) notice = nextNotice
        }
        nextNotice?.let(notices::publish)
    }

    private fun finishOperation(label: String, outcome: SplitOutcome) {
        synchronized(stateLock) {
            busy = busy?.takeIf { it.label != label }
            val fallback = fallbackOf(label)
            failure = when (outcome) {
                is SplitOutcome.Committed -> null
                is SplitOutcome.Cancelled -> failure
                is SplitOutcome.RolledBack ->
                    Failure(friendlyError(outcome.reason, fallback), outcome.reason)
                is SplitOutcome.Failed ->
                    Failure(friendlyError(outcome.message, fallback), outcome.message)
            }
            if (label == OPEN_LABEL) openTicket = null
        }
        publish()
    }

    private fun markBusy(label: String, enabled: Boolean, message: String) {
        synchronized(stateLock) {
            busy = Busy(label, enabled, message)
            failure = null
        }
        publish()
    }

    private fun publish() {
        session = synchronized(stateLock) { sessionOf() }
        onStateChanged?.invoke()
    }

    private fun sessionOf(): SplitScreenSession {
        busy?.let { pending ->
            return SplitScreenSession(
                enabled = pending.enabled,
                phase = SplitScreenPhase.STARTING,
                message = pending.message,
            )
        }
        if (!state.enabled) return SplitScreenSession()
        failure?.let { seen ->
            return SplitScreenSession(
                enabled = true,
                phase = SplitScreenPhase.ERROR,
                message = seen.message,
                details = seen.details,
            )
        }
        return SplitScreenSession(
            enabled = true,
            phase = SplitScreenPhase.ACTIVE,
            message = notice,
        )
    }

    private fun report(callback: (String?) -> Unit, outcome: SplitOutcome, fallback: String) {
        post { callback(errorOf(outcome, fallback)) }
    }

    /**
     * A user who pressed Home or turned the toggle off got exactly what they asked for; only a
     * deadline or a real failure is an error worth a message (1.3.8, U5).
     */
    private fun errorOf(outcome: SplitOutcome, fallback: String): String? = when (outcome) {
        is SplitOutcome.Committed -> null
        is SplitOutcome.Cancelled -> when (outcome.reason) {
            SplitCancelReason.DEADLINE -> "$fallback: превышено время ожидания"
            else -> null
        }
        is SplitOutcome.RolledBack -> friendlyError(outcome.reason, fallback)
        is SplitOutcome.Failed -> friendlyError(outcome.message, fallback)
    }

    private fun resolveProjectedPane(taskId: Int): SplitPane? {
        val fromLive = currentLive().entries.firstOrNull { it.value.appTaskId == taskId }?.key
        val resolved = runCatching {
            withBridgeShell { split ->
                split.existingOwnedSession(SPLIT_PICKER_COMPONENT_SET, expectedApps(currentLive()))
                    ?.entries
                    ?.firstOrNull { it.value.appTaskId == taskId }
                    ?.key
            }
        }.getOrNull()
        return resolved ?: fromLive
    }

    private fun <T> withBridgeShell(block: (SplitPickerShellSession) -> T): T {
        val handle = shellFactory.open()
        return try {
            block(
                SplitPickerShellSession(
                    shell = handle::shell,
                    apkPath = apkPath,
                    pause = sleeper,
                    gateLeaseStore = gateLeaseStore,
                ),
            )
        } finally {
            runCatching(handle::close)
        }
    }

    private fun applyBridgeFact(fact: SplitFact) {
        synchronized(stateLock) {
            val reduction = SplitAutomaton.reduce(state, fact)
            state = reduction.state
        }
        publish()
    }

    private data class Busy(val label: String, val enabled: Boolean, val message: String)

    private data class Failure(val message: String, val details: String)

    internal companion object {
        const val ENABLE_LABEL = "enable"
        const val DISABLE_LABEL = "disable"
        const val OPEN_LABEL = "open"
        const val SELECT_LABEL = "select"
        const val HOME_LABEL = "home"
        const val EDGE_LABEL = "edge"
        const val RECONCILE_LABEL = "reconcile"

        const val EXTERNAL_TASK_BYPASS_MS = 5_000L

        const val OPEN_FAILURE = "Не удалось открыть разделение экрана"
        const val SELECT_FAILURE = "Не удалось открыть приложение в этом окне"
        const val RECONCILE_FAILURE = "Не удалось восстановить окна"
        const val DISABLE_FAILURE = "Не удалось выключить Split screen"

        fun fallbackOf(label: String): String = when (label) {
            SELECT_LABEL -> SELECT_FAILURE
            RECONCILE_LABEL -> RECONCILE_FAILURE
            DISABLE_LABEL -> DISABLE_FAILURE
            else -> OPEN_FAILURE
        }

        fun expectedApps(live: SplitLiveScene): Map<SplitPane, SplitPickerExpectedApp> =
            live.entries.mapNotNull { (pane, observed) ->
                val taskId = observed.appTaskId ?: return@mapNotNull null
                val packageName = observed.appPackageName ?: return@mapNotNull null
                pane to SplitPickerExpectedApp(taskId, packageName)
            }.toMap()

        /** Both panes must be known before the resize repair may move a picker base. */
        fun resizeExpectation(live: SplitLiveScene): Map<SplitPane, SplitPickerObservedPane>? =
            SplitPane.entries.associateWith { pane ->
                val observed = live[pane] ?: return null
                SplitPickerObservedPane(
                    hostTaskId = observed.hostTaskId,
                    appTaskId = observed.appTaskId,
                    packageName = observed.appPackageName,
                )
            }

        fun collapseExpectation(live: SplitLiveScene): Map<SplitPane, SplitPickerObservedPane>? =
            live.mapValues { (_, observed) ->
                SplitPickerObservedPane(
                    hostTaskId = observed.hostTaskId,
                    appTaskId = observed.appTaskId,
                    packageName = observed.appPackageName,
                )
            }.takeIf(Map<SplitPane, SplitPickerObservedPane>::isNotEmpty)

        fun slotsOf(live: SplitLiveScene): Map<SplitPane, SplitSlot> =
            SplitPane.entries.associateWith { pane ->
                val observed = live[pane] ?: return@associateWith SplitSlot.Closed
                observed.appPackageName?.let(SplitSlot::App) ?: SplitSlot.Picker
            }

        fun friendlyError(raw: String?, fallback: String): String {
            val text = raw.orEmpty()
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
    }

    // endregion
}
