package dev.denza.apps.feature.split

/**
 * The seven operations of the product, one per input class of contract section 4.
 *
 * They are written settled-first: an operation runs the live-proven recipe and feeds the automaton
 * what actually happened, instead of asking the automaton to imagine a plan and then trying to make
 * the firmware agree. The automaton still decides everywhere it owns a decision - which teardown a
 * toggle-off needs, whether an open reveals or rebuilds - and it is still the only writer of
 * semantic state (invariant 12), which is why every fact below is fed on the actor worker and every
 * durable projection is written by exactly one commit at the end of the operation.
 */

/** Everything one operation is allowed to touch, built fresh for each one and closed after it. */
internal class SplitOperationWorkspace(
    private val shellFactory: SplitShellFactory,
    val store: SplitStateStore,
    val catalog: SplitLaunchCatalog,
    val notices: SplitNoticeSink,
    val leases: List<SplitLeaseController>,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val apkPath: String,
    private val sleeper: (Long) -> Unit,
    private val diagnostics: SplitDiagnosticLog,
    private val readState: () -> SplitState,
    private val readLive: () -> SplitLiveScene,
    val externalMoveInFlight: () -> Boolean,
    private val publisher: (SplitState, SplitLiveScene, String?) -> Unit,
) : AutoCloseable {

    private val handleLock = Any()
    private var handle: SplitShellHandle? = null

    val rollback: RollbackExecutor = SplitShellRollbackExecutor(::shell, gateLeaseStore, leases)

    /**
     * The raw shell. It is opened on the first command and never before: an operation that decides
     * to do nothing - a hint without a scene, a toggle-off without a scene - opens no session at all
     * (invariant 1, K6, K7).
     */
    fun shell(command: String): String {
        // Contract 1.12: extending the firmware's split-capable list outlives our session and only
        // a reboot clears it, so every single addition is recorded the moment it happens.
        if (command.startsWith(ALLOWLIST_COMMAND_PREFIX)) {
            diagnostics.log(
                "firmware split allowlist extended: " +
                    command.removePrefix(ALLOWLIST_COMMAND_PREFIX),
            )
        }
        return openedHandle().shell(command)
    }

    fun state(): SplitState = readState()

    fun live(): SplitLiveScene = readLive()

    fun log(message: String) = diagnostics.log(message)

    /** The live recipes, with the fence woven through every command and every settle pause. */
    fun split(op: SplitOperationContext): SplitPickerShellSession = SplitPickerShellSession(
        shell = op.fencedShell(::shell),
        apkPath = apkPath,
        pause = op.fencedPause(sleeper),
        gateLeaseStore = gateLeaseStore,
    )

    fun publish(state: SplitState, live: SplitLiveScene, notice: String?) {
        publisher(state, live, notice)
    }

    override fun close() {
        val open = synchronized(handleLock) { handle.also { handle = null } }
        open?.let { runCatching(it::close) }
    }

    private fun openedHandle(): SplitShellHandle = synchronized(handleLock) {
        handle ?: shellFactory.open().also { handle = it }
    }

    private companion object {
        const val ALLOWLIST_COMMAND_PREFIX = "service call activity_task 125 s16 "
    }
}

/**
 * The inverse of the journal, on the raw shell and with no token of its own (canon: a rollback runs
 * precisely because the operation's token is already dead).
 *
 * Only the entries the operations actually record are undoable here. Task creation and task moves
 * are not journalled by any operation: the recipes that create and move tasks own their local repair
 * and their own postconditions, and re-creating a removed task is not something a rollback can
 * honestly do (invariant 9).
 */
internal class SplitShellRollbackExecutor(
    private val shell: (String) -> String,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val leases: List<SplitLeaseController>,
) : RollbackExecutor {

    /** Contract, to 1.12: a gate is closed only by the lease that opened it. */
    override fun closeGate() {
        if (!gateLeaseStore.isOwned()) return
        shell("service call activity_task 126 i32 0")
        gateLeaseStore.setOwned(false)
    }

    override fun openGate() {
        shell("service call activity_task 126 i32 1")
    }

    override fun restoreLease(kind: String, prevValue: String?) {
        leases.firstOrNull { lease -> lease.kind == kind }?.restore(shell)
    }

    override fun removeTask(taskId: Int, component: String): Unit =
        error("task $taskId belongs to a recipe that owns its own repair")

    override fun moveTask(taskId: Int, toRootId: Int) {
        shell("am stack move-task $taskId $toRootId true")
    }
}

/**
 * Shared frame of every product operation: the fresh state it reasons about, the facts it feeds and
 * the single publication of what it settled.
 *
 * The state is read when the operation *runs*, never when it was queued: a losing event is not
 * replayed from the queue, it is re-decided against the state the winner left behind (section 4).
 */
internal abstract class SplitCoreOperation<P>(
    label: String,
    priority: SplitInputPriority,
    durationMs: Long,
    joinKey: Any?,
    coalesceKey: Any?,
    protected val work: SplitOperationWorkspace,
) : GuardedOperation<P>(
    label = label,
    priority = priority,
    durationMs = durationMs,
    shell = work::shell,
    store = work.store,
    rollbackExecutor = work.rollback,
    joinKey = joinKey,
    coalesceKey = coalesceKey,
) {
    protected var working: SplitState = SplitState()
        private set

    protected var liveScene: SplitLiveScene = emptyMap()

    /** Published on commit; `null` leaves whatever notice the product already shows. */
    protected var settledNotice: String? = null

    final override fun plan(op: SplitOperationContext, shell: (String) -> String): P? {
        working = work.state()
        liveScene = work.live()
        return prepare(op, shell)
    }

    final override fun mutate(op: SplitOperationContext, shell: (String) -> String, plan: P) {
        apply(op, shell, plan)
    }

    /** Steps 2-4 of section 7: read-only snapshot, preconditions, complete plan. */
    protected abstract fun prepare(op: SplitOperationContext, shell: (String) -> String): P?

    /** Steps 5-6: exact mutations, each one a live-proven recipe, each fenced. */
    protected abstract fun apply(op: SplitOperationContext, shell: (String) -> String, plan: P)

    /**
     * Step 7. The read-back is not re-implemented here: every recipe this layer calls already ends
     * in its own live-proven postcondition (`verifyPickerTasks`, `awaitSelectedAppPlacement`,
     * `attachPicker`'s top check, `closePickers`' area check), measured on a fresh snapshot.
     */
    override fun readBack(op: SplitOperationContext, shell: (String) -> String, plan: P): Boolean =
        true

    /** Step 8: one snapshot, one commit, and only when the durable projection really moved. */
    override fun durable(plan: P, current: SplitDurable): SplitDurable? {
        if (working.enabled == current.enabled && working.slots == current.slots) return null
        return current.copy(
            enabled = working.enabled,
            slots = working.slots,
            revision = current.revision + 1,
        )
    }

    protected fun settle(fact: SplitFact): List<SplitPlan> {
        val reduction = SplitAutomaton.reduce(working, fact)
        working = reduction.state
        return reduction.plans
    }

    /** Takes the global leases the product borrows, recording what each one displaced. */
    protected fun enableLeases(op: SplitOperationContext, shell: (String) -> String) {
        work.leases.forEach { lease ->
            val previous = lease.ownedValue()
            lease.enable(shell)
            op.journal.record(SplitJournalEntry.LeaseEnabled(lease.kind, previous))
        }
    }

    /** Every removal is irreversible; the journal says so before the recipe that may perform one. */
    protected fun pointOfNoReturn(op: SplitOperationContext, reason: String) {
        op.journal.record(SplitJournalEntry.PointOfNoReturn(reason))
    }

    /**
     * The slots a live topology proves - with one exception: a projected pane keeps `APP(navigator)`
     * while its picker is on screen, because that picker is scene content, not slot content (1.10.1).
     */
    protected fun sceneSlots(live: SplitLiveScene): Map<SplitPane, SplitSlot> {
        val slots = SplitCoordinatorCore.slotsOf(live).toMutableMap()
        working.projectedPane?.let { projected -> slots[projected] = working.slot(projected) }
        return slots
    }

    /** Moves a pane to `APP(package)` from whatever it held, or back to its picker. */
    protected fun settleOccupant(pane: SplitPane, packageName: String?) {
        val current = working.slot(pane)
        when {
            packageName == null ->
                if (current is SplitSlot.App) settle(SplitFact.AppClosedSettled(pane))
            current != SplitSlot.App(packageName) -> {
                if (current is SplitSlot.App) settle(SplitFact.AppClosedSettled(pane))
                settle(SplitFact.AppLaunchConfirmed(pane, packageName))
            }
        }
    }

    /** Called on the worker once the outcome is known, whatever it is. */
    open fun finished(outcome: SplitOutcome) {
        if (outcome is SplitOutcome.Committed) {
            work.publish(working, liveScene, settledNotice)
            return
        }
        val reason = when (outcome) {
            is SplitOutcome.RolledBack -> outcome.reason
            is SplitOutcome.Failed -> outcome.message
            else -> return
        }
        failed(SplitCoordinatorCore.friendlyError(reason, SplitCoordinatorCore.fallbackOf(label)))
    }

    /** U5: an operation that owns a user-visible surface says so when it fails. */
    protected open fun failed(message: String) = Unit
}

/**
 * Wraps one operation so that its shell is always closed and its settled state is always published
 * on the worker thread, immediately after the run, for every outcome.
 */
internal class SplitOperationLifecycle(
    private val operation: SplitCoreOperation<*>,
    private val workspace: SplitOperationWorkspace,
) : SplitOperationSpec {
    override val label: String get() = operation.label
    override val priority: SplitInputPriority get() = operation.priority
    override val durationMs: Long get() = operation.durationMs
    override val joinKey: Any? get() = operation.joinKey
    override val coalesceKey: Any? get() = operation.coalesceKey

    override fun run(op: SplitOperationContext): SplitOutcome {
        val outcome = try {
            operation.run(op)
        } finally {
            workspace.close()
        }
        operation.finished(outcome)
        return outcome
    }
}

// region toggle

/** Contract 1.2.1: enabling writes one snapshot and sends nothing to the car. */
internal class EnableOperation(
    work: SplitOperationWorkspace,
) : SplitCoreOperation<Unit>(
    label = SplitCoordinatorCore.ENABLE_LABEL,
    priority = SplitInputPriority.DISABLE,
    durationMs = TOGGLE_BUDGET_MS,
    joinKey = SplitCoordinatorCore.ENABLE_LABEL,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String) {
        settle(SplitFact.ToggleChanged(enabled = true))
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Unit) = Unit
}

/**
 * Contract 1.2.2-1.2.6. The automaton decides which teardown the live scene needs; the recipe
 * performs it, keeping the focused app fullscreen and the neighbour alive, and the gate is closed
 * only if this product opened it. The selection survives (1.3.2).
 */
internal class DisableOperation(
    work: SplitOperationWorkspace,
) : SplitCoreOperation<Boolean>(
    label = SplitCoordinatorCore.DISABLE_LABEL,
    priority = SplitInputPriority.DISABLE,
    durationMs = TOGGLE_BUDGET_MS,
    joinKey = SplitCoordinatorCore.DISABLE_LABEL,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean {
        // No scene means no teardown: a cold process that finds the toggle off, or a repaired
        // mismatch at startup, sends not one command (A.3.1, invariant 1, scenarios 14 and 19).
        val plans = settle(SplitFact.ToggleChanged(enabled = false))
        return plans.isNotEmpty()
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        if (plan) {
            pointOfNoReturn(op, "toggle-off removes the product pickers")
            work.split(op).closePickers(SPLIT_PICKER_COMPONENTS)
        }
        // Independent of the scene, and independent of each other: every restore is a no-op unless
        // this product still owns that lease, so a toggle-off with nothing borrowed stays silent.
        val failures = mutableListOf<Throwable>()
        work.leases.forEach { lease ->
            runCatching { lease.restore(shell) }.exceptionOrNull()?.let(failures::add)
        }
        liveScene = emptyMap()
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

// endregion

// region open

internal class SplitOpenPlan(
    val adopted: SplitLiveScene?,
    val restorable: Map<SplitPane, String>,
)

/**
 * Contract 1.3. A live scene is adopted and raised; otherwise the remembered pair is rebuilt, with
 * every unknown or uninstalled package degrading to a fresh picker and one visible notice.
 */
internal class OpenOperation(
    work: SplitOperationWorkspace,
) : SplitCoreOperation<SplitOpenPlan>(
    label = SplitCoordinatorCore.OPEN_LABEL,
    priority = SplitInputPriority.OPEN,
    durationMs = OPEN_BUDGET_MS,
    joinKey = SplitCoordinatorCore.OPEN_LABEL,
    coalesceKey = null,
    work = work,
) {
    override val refusal: String get() = "Split screen выключен"

    override fun prepare(op: SplitOperationContext, shell: (String) -> String): SplitOpenPlan? {
        if (!working.enabled) return null
        settle(SplitFact.OpenRequested)
        val restorable = SplitPickerSelectionPolicy.restorablePair(
            primaryPackage = (working.slot(SplitPane.PRIMARY) as? SplitSlot.App)?.packageName,
            secondaryPackage = (working.slot(SplitPane.SECONDARY) as? SplitSlot.App)?.packageName,
            installedPackages = work.catalog.installedPackages(),
        )
        // Read-only: adoption is decided from a live snapshot before a single mutation (1.3.5).
        val adopted = runCatching {
            work.split(op).existingOwnedSession(
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
                expectedApps = SplitCoordinatorCore.expectedApps(liveScene),
            )
        }.getOrNull()
        return SplitOpenPlan(adopted, restorable)
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: SplitOpenPlan) {
        val split = work.split(op)
        enableLeases(op, shell)
        val adopted = plan.adopted
        if (adopted != null) {
            adopt(split.revealOwnedSession(adopted, SPLIT_PICKER_COMPONENT_SET))
            return
        }
        build(op, split, plan.restorable)
    }

    private fun adopt(revealed: SplitLiveScene) {
        liveScene = revealed
        settle(SplitFact.BuildSceneSucceeded(sceneSlots(revealed)))
        settle(SplitFact.SceneRevealed)
        settledNotice = ""
    }

    private fun build(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        restorable: Map<SplitPane, String>,
    ) {
        pointOfNoReturn(op, "building the scene prunes stale picker and app tasks")
        val hostTaskIds = split.openPickers(SPLIT_PICKER_COMPONENTS, restorable)
        val panes = SplitPane.entries.associateWithTo(mutableMapOf()) { pane ->
            SplitPickerLivePane(pane, hostTaskIds.getValue(pane), null, null)
        }
        val failures = mutableListOf<String>()
        SplitPane.entries.forEach { pane ->
            val packageName = restorable[pane] ?: return@forEach
            val target = work.catalog.resolve(packageName)
            if (target == null) {
                failures += packageName
                return@forEach
            }
            runCatching {
                split.restoreApp(
                    pickerTaskId = hostTaskIds.getValue(pane),
                    target = target,
                    pickerComponents = SPLIT_PICKER_COMPONENT_SET,
                    reservedPackages = restorable.values.toSet(),
                )
            }.onSuccess { placement ->
                panes[placement.pane] = SplitPickerLivePane(
                    pane = placement.pane,
                    hostTaskId = placement.hostTaskId,
                    appTaskId = placement.appTaskId,
                    appPackageName = placement.packageName,
                )
            }.onFailure { error ->
                work.log("failed to restore $packageName in $pane: $error")
                runCatching {
                    split.discardFailedRestoration(pane, packageName, hostTaskIds.getValue(pane))
                }
                failures += packageName
            }
        }
        liveScene = panes
        settle(SplitFact.BuildSceneSucceeded(sceneSlots(panes)))
        settledNotice = if (failures.isEmpty()) "" else SPLIT_RESTORE_FAILURE_NOTICE
    }

    override fun failed(message: String) = work.notices.publish(message)
}

// endregion

// region select

/** Contract 1.5: one pane, one launch, and a failure that leaves the picker interactive (K14). */
internal class SelectOperation(
    work: SplitOperationWorkspace,
    private val pickerTaskId: Int,
    private val target: SplitLaunchTarget,
) : SplitCoreOperation<Unit>(
    label = SplitCoordinatorCore.SELECT_LABEL,
    priority = SplitInputPriority.SELECT,
    durationMs = SELECT_BUDGET_MS,
    joinKey = SELECT_JOIN_PREFIX + pickerTaskId,
    coalesceKey = null,
    work = work,
) {
    override val refusal: String get() = "Split screen выключен"

    private var placement: SplitPickerPlacement? = null

    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Unit? =
        if (working.enabled) Unit else null

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Unit) {
        val split = work.split(op)
        enableLeases(op, shell)
        pointOfNoReturn(op, "selecting an app clears the tasks above its picker")
        val settled = split.selectApp(
            pickerTaskId = pickerTaskId,
            target = target,
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
        )
        placement = settled
        settle(SplitFact.SelectionRequested(settled.pane, settled.packageName))
        liveScene = liveScene + (
            settled.pane to SplitPickerLivePane(
                pane = settled.pane,
                hostTaskId = settled.hostTaskId,
                appTaskId = settled.appTaskId,
                appPackageName = settled.packageName,
            )
            )
    }

    /**
     * One fresh read of the settled topology closes the operation.
     *
     * It is what makes a tap in a picker that outlived our own process work (1.11.3): the scene the
     * user is looking at is proven again from the car, not remembered, so the pane that just
     * launched an app lands in the durable snapshot even when this process learned about the scene
     * for the first time a moment ago.
     */
    override fun readBack(op: SplitOperationContext, shell: (String) -> String, plan: Unit): Boolean {
        val chosen = placement ?: return false
        runCatching { work.split(op).existingOwnedSession(SPLIT_PICKER_COMPONENT_SET) }
            .getOrNull()
            ?.let { settled ->
                liveScene = settled
                settle(SplitFact.BuildSceneSucceeded(sceneSlots(settled)))
            }
        settleOccupant(chosen.pane, chosen.packageName)
        settledNotice = ""
        return true
    }

    override fun failed(message: String) = work.notices.publish(message)
}

// endregion

// region home

/** Contract 1.9.1: Home hides the scene and suspends only a gate this product opened. */
internal class HomeOperation(
    work: SplitOperationWorkspace,
) : SplitCoreOperation<Boolean>(
    label = SplitCoordinatorCore.HOME_LABEL,
    priority = SplitInputPriority.HOME,
    durationMs = HOME_BUDGET_MS,
    joinKey = SplitCoordinatorCore.HOME_LABEL,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean =
        working.enabled

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        if (!plan) return
        // The recipe reads the lease before it reads the car: an unowned gate costs no command.
        if (work.split(op).suspendOwnedGateForHome()) settle(SplitFact.HomeConfirmed)
    }
}

// endregion

// region edge

/**
 * Contract 1.8.5. Until BYD is balanced with no active pointer this path is read-only, and it only
 * ever touches a pane of a live product scene (invariant 2).
 */
internal class EdgeOperation(
    work: SplitOperationWorkspace,
) : SplitCoreOperation<Boolean>(
    label = SplitCoordinatorCore.EDGE_LABEL,
    priority = SplitInputPriority.EDGE,
    durationMs = EDGE_BUDGET_MS,
    joinKey = SplitCoordinatorCore.EDGE_LABEL,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean =
        working.enabled && working.scene != null && !work.externalMoveInFlight()

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        if (!plan) return
        val split = work.split(op)
        if (!split.awaitNativePickerCommit()) return
        SplitPane.entries.forEach { pane ->
            if (!split.nativePickerMutationAllowed()) return
            val observation = split.observePane(pane, SPLIT_PICKER_COMPONENT_SET)
            val hostTaskId = observation.hostTaskId ?: return@forEach
            if (!observation.nativeHostVisible || observation.pickerVisible) return@forEach
            // The snapshot can race the final phase of an edge collapse: check again, and this
            // time immediately before the command that would steal the user's gesture.
            if (!split.nativePickerMutationAllowed()) return
            pointOfNoReturn(op, "attaching a picker removes the stock bootstrap of $pane")
            runCatching {
                split.attachPicker(pane, hostTaskId, SPLIT_PICKER_COMPONENT)
            }.onSuccess { pickerTaskId ->
                liveScene = liveScene + (pane to SplitPickerLivePane(pane, pickerTaskId, null, null))
                settle(SplitFact.EdgeCommitConfirmed(pane))
            }.onFailure { error ->
                work.log("failed to attach the picker in $pane: $error")
            }
        }
    }
}

// endregion

// region reconcile

internal sealed interface SplitReconcileKind {
    data class PickerVisible(val hostTaskId: Int?) : SplitReconcileKind

    data class PickerHidden(val hostTaskId: Int) : SplitReconcileKind

    data object DividerResized : SplitReconcileKind
}

/**
 * Contract 1.6-1.8: the passive class of inputs.
 *
 * It is the only operation allowed to decide it has nothing to do - and it decides that before
 * opening a shell, so a hundred window hints over a disabled product, or over a scene that does not
 * exist, cost exactly zero commands (K6, invariant 8). What it does do is translate the settled
 * topology the recipes report into facts; the removal rules below are the ones the previous picker
 * automaton earned on the car and are kept verbatim in meaning.
 */
internal class ReconcileOperation(
    work: SplitOperationWorkspace,
    private val kind: SplitReconcileKind,
) : SplitCoreOperation<Boolean>(
    label = SplitCoordinatorCore.RECONCILE_LABEL,
    priority = SplitInputPriority.HINT,
    durationMs = RECONCILE_BUDGET_MS,
    joinKey = null,
    coalesceKey = coalesceKeyOf(kind),
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean =
        working.enabled && working.scene != null && !work.externalMoveInFlight()

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        if (!plan) return
        val split = work.split(op)
        when (kind) {
            SplitReconcileKind.DividerResized -> reconcileScene(op, split, settleResize = true)
            is SplitReconcileKind.PickerVisible -> pickerVisible(op, split, kind.hostTaskId)
            is SplitReconcileKind.PickerHidden -> pickerHidden(op, split, kind.hostTaskId)
        }
    }

    /**
     * A revealed picker means the task that covered it is gone (1.6.2, 1.7.1-1.7.3). The recorded
     * app is removed only when this very snapshot still lists it in that root: that is the rule the
     * old automaton used, and it is what keeps a crash and a Back indistinguishable and safe.
     */
    private fun pickerVisible(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        requestedHostTaskId: Int?,
    ) {
        val hostTaskId = requestedHostTaskId
            ?: split.singleVisiblePickerTaskId(SPLIT_PICKER_COMPONENT_SET)
            ?: return
        if (!reconcileScene(op, split, settleResize = false)) return
        val observation = split.observePickerTask(hostTaskId, SPLIT_PICKER_COMPONENT_SET) ?: return
        if (!observation.pickerVisible) return
        val pane = observation.pane
        val observed = liveScene[pane] ?: return
        val appTaskId = observed.appTaskId ?: return
        val packageName = observed.appPackageName ?: return
        if (appTaskId !in observation.observedTaskIds) return
        pointOfNoReturn(op, "the app of $pane left its pane")
        split.removeRecordedTask(appTaskId, packageName)
        liveScene = liveScene + (pane to observed.copy(appTaskId = null, appPackageName = null))
        settle(SplitFact.AppClosedSettled(pane))
    }

    /**
     * A picker that left both panel roots is the dismiss gesture, and only then (1.6.3, 1.7.4).
     * A hidden picker under a live app is Android reclaiming an invisible Activity, never a close.
     */
    private fun pickerHidden(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        hostTaskId: Int,
    ) {
        val pane = liveScene.entries
            .firstOrNull { (_, observed) -> observed.hostTaskId == hostTaskId }
            ?.key
            ?: return
        if (liveScene.getValue(pane).appTaskId != null) return
        if (split.observePickerTask(hostTaskId, SPLIT_PICKER_COMPONENT_SET) != null) return
        pointOfNoReturn(op, "removing the dismissed picker of $pane")
        split.removePickerArtifact(hostTaskId, SPLIT_PICKER_COMPONENT_SET)
        liveScene = liveScene - pane
        settle(SplitFact.PickerPaneClosedSettled(pane))
    }

    /**
     * Adopts the settled topology, either as an intact two-pane scene or as the single pane a
     * native collapse left (1.8.2, 1.8.3). Both recipes verify every recorded id against a fresh
     * snapshot first; anything they cannot prove fails closed and changes nothing.
     */
    private fun reconcileScene(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        settleResize: Boolean,
    ): Boolean {
        val previous = liveScene
        val settled = if (settleResize) {
            split.reconcileDividerResize(
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
                previousPanes = SplitCoordinatorCore.resizeExpectation(previous).orEmpty(),
            )
        } else {
            split.existingOwnedSession(SPLIT_PICKER_COMPONENT_SET)
        }
        if (settled != null) {
            liveScene = settled
            settle(SplitFact.BuildSceneSucceeded(sceneSlots(settled)))
            return true
        }
        val expected = SplitCoordinatorCore.collapseExpectation(previous) ?: return false
        val collapsed = split.collapsedOwnedSession(
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
            expectedPanes = expected,
        ) ?: return false
        adoptCollapse(op, split, collapsed, previous)
        return true
    }

    private fun adoptCollapse(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        collapsed: SplitPickerLivePane,
        previous: SplitLiveScene,
    ) {
        val survivor = collapsed.pane
        // Which logical pane owned the surviving permanent base decides what the collapse closed.
        val previousOwner = previous.entries
            .firstOrNull { (_, observed) -> observed.hostTaskId == collapsed.hostTaskId }
            ?.key
            ?: return
        val closed = previous[previousOwner.other()]
        pointOfNoReturn(op, "the collapse closed the peer of $survivor for good")
        if (collapsed.appTaskId == null) {
            // The survivor kept its base but lost its app: that exact task is what collapsed away.
            previous[previousOwner]?.let { owner -> removeRecordedApp(split, owner) }
        }
        closed?.let { pane ->
            removeRecordedApp(split, pane)
            runCatching { split.removePickerArtifact(pane.hostTaskId, SPLIT_PICKER_COMPONENT_SET) }
                .onFailure { error -> work.log("failed to remove the collapsed picker: $error") }
        }
        liveScene = mapOf(survivor to collapsed)
        settle(SplitFact.PaneCollapsedSettled(survivor))
        settleOccupant(survivor, collapsed.appPackageName)
    }

    private fun removeRecordedApp(split: SplitPickerShellSession, observed: SplitPickerLivePane) {
        val taskId = observed.appTaskId ?: return
        val packageName = observed.appPackageName ?: return
        runCatching { split.removeRecordedTask(taskId, packageName) }
            .onFailure { error -> work.log("failed to remove the collapsed app task: $error") }
    }

    private companion object {
        /** Every passive topology hint collapses to one queued reconcile (section 7, K5). */
        fun coalesceKeyOf(kind: SplitReconcileKind): Any = when (kind) {
            is SplitReconcileKind.PickerHidden -> "reconcile-hidden-${kind.hostTaskId}"
            else -> "reconcile"
        }
    }
}

// endregion

private const val SELECT_JOIN_PREFIX = "select-"
private const val TOGGLE_BUDGET_MS = 30_000L
private const val OPEN_BUDGET_MS = 15_000L
private const val SELECT_BUDGET_MS = 20_000L
private const val HOME_BUDGET_MS = 10_000L
private const val EDGE_BUDGET_MS = 25_000L
private const val RECONCILE_BUDGET_MS = 30_000L
