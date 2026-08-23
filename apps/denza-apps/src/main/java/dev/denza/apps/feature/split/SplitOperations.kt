package dev.denza.apps.feature.split

import java.util.concurrent.atomic.AtomicReference

/**
 * The operations of the product, one per input class of contract section 4.
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
    private val proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
    /** The human name of a package; the package itself is the honest fallback (1.3.2). */
    val appLabel: (String) -> String = { it },
    private val logMirror: () -> List<String> = { emptyList() },
    private val clock: SplitClock,
    private val sleeper: (Long) -> Unit,
    private val diagnostics: SplitDiagnosticLog,
    private val readState: () -> SplitState,
    private val readLive: () -> SplitLiveScene,
    val externalMoveInFlight: () -> Boolean,
    private val publisher: (SplitState, SplitLiveScene, String?) -> Unit,
) : AutoCloseable {

    private val handleLock = Any()
    private var handle: SplitShellHandle? = null

    /** Shared by every read of this operation, dropped by every command that could move a task. */
    private val topology = SplitTopologyCache()

    private var session: SplitPickerShellSession? = null

    val rollback: RollbackExecutor = SplitShellRollbackExecutor(
        shell = ::shell,
        gateLeaseStore = gateLeaseStore,
        leases = leases,
        apkPath = apkPath,
        clock = clock,
        proxyClasspath = proxyClasspath,
    )

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
        // Contract 5, to 1.12: resizeability is the other firmware-global thing a session borrows,
        // and acceptance v17 read a defect out of the product's silence about it. Every write of it
        // - taking the lease and giving it back alike - says so, exactly like the allowlist above.
        if (
            command.contains(RESIZEABILITY_SETTING) &&
            (command.contains("settings put ") || command.contains("settings delete "))
        ) {
            diagnostics.log("firmware resizeability lease: $command")
        }
        // The one place that decides whether the shared topology read survives a command. It sits
        // here rather than inside the session because the leases this operation takes go straight
        // to the raw shell, and a lease that moved a task would otherwise leave a stale read behind.
        if (!SplitTopologyCache.isTopologyRead(command)) topology.invalidate()
        return openedHandle().shell(command)
    }

    fun state(): SplitState = readState()

    fun live(): SplitLiveScene = readLive()

    /** Whether the product currently holds the firmware-global gate lease (contract, to 1.12). */
    fun gateOwned(): Boolean = gateLeaseStore.isOwned()

    fun log(message: String) = diagnostics.log(message)

    /**
     * The live recipes, with the fence woven through every command and every settle pause.
     *
     * One operation gets exactly one session, so the reads of its planning phase and the reads of
     * its mutation phase are the same shared topology - an open used to open three `am stack list`
     * before it sent a single command, because `prepare` and `apply` each built their own.
     */
    fun split(op: SplitOperationContext): SplitPickerShellSession = synchronized(handleLock) {
        session ?: SplitPickerShellSession(
            shell = op.fencedShell(::shell),
            apkPath = apkPath,
            settle = op.fencedPause(sleeper),
            gateLeaseStore = gateLeaseStore,
            topology = topology,
            proxyClasspath = proxyClasspath,
        ).also { built -> session = built }
    }

    fun publish(state: SplitState, live: SplitLiveScene, notice: String?) {
        publisher(state, live, notice)
    }

    override fun close() {
        val open = synchronized(handleLock) {
            session = null
            handle.also { handle = null }
        }
        topology.invalidate()
        open?.let { runCatching(it::close) }
    }

    /**
     * Carries what the product recorded through the one channel this car is proven to accept.
     *
     * It costs one command, and only on an operation that was already talking to the firmware: an
     * operation that decided to do nothing opens no session and mirrors nothing (invariant 1, K6,
     * K7), and one that lost its token sends nothing at all, log line included (invariant 10) -
     * whatever it recorded waits for the next operation that still owns its mutations.
     */
    fun mirrorDiagnostics() {
        val handle = synchronized(handleLock) { handle } ?: return
        val lines = runCatching(logMirror).getOrDefault(emptyList())
        if (lines.isEmpty()) return
        runCatching { handle.shell(SplitDiagnostics.mirrorCommand(lines)) }
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
 * It has a time budget instead: the reason an operation is being unwound is often that the link
 * stopped answering, and an unwind that hangs on a dead link would hold the single worker - and
 * therefore Home and the toggle - hostage. The budget is armed by the first command of the unwind,
 * so an operation that never mutated anything never arms one.
 */
internal class SplitShellRollbackExecutor(
    private val shell: (String) -> String,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val leases: List<SplitLeaseController>,
    private val apkPath: String,
    private val clock: SplitClock,
    private val budgetMs: Long = ROLLBACK_BUDGET_MS,
    private val proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
) : RollbackExecutor {

    private var deadlineAtMs: Long? = null

    /** Contract, to 1.12: a gate is closed only by the lease that opened it. */
    override fun closeGate() {
        if (!gateLeaseStore.isOwned()) return
        budgeted("service call activity_task 126 i32 0")
        gateLeaseStore.setOwned(false)
    }

    override fun openGate() {
        budgeted("service call activity_task 126 i32 1")
    }

    override fun restoreLease(kind: String, prevValue: String?) {
        leases.firstOrNull { lease -> lease.kind == kind }?.restore(::budgeted)
    }

    /**
     * Undo of a [SplitJournalEntry.TaskCreated]: this exact task, by id and by the component the
     * operation launched, and nothing else (invariant 3).
     *
     * The proxy is the same shell-UID helper the recipes use, but the call is deliberately the
     * narrower one: a rollback has no fresh snapshot to assert a top identity from, so it passes
     * none and lets the proxy verify the base identity alone. A proxy that will not confirm the
     * removal is a rollback failure, not a silent success - the journal said we created this task
     * moments ago.
     */
    override fun removeTask(taskId: Int, component: String) {
        val packageName = component.substringBefore('/')
        val activityName = component.substringAfter('/', missingDelimiterValue = "")
        check(taskId > 0 && packageName.isNotBlank() && activityName.isNotBlank()) {
            "task $taskId cannot be addressed by \"$component\""
        }
        val classpath = proxyClasspath.entry(::budgeted)
        val output = budgeted(
            "CLASSPATH=${quoted(classpath)} app_process /system/bin " +
                "--nice-name=denza_split_cmd ${SplitTaskProxyMain::class.java.name} " +
                "remove-task $taskId ${quoted(packageName)} ${quoted(activityName)} '-' '-'",
        )
        val removed = output.lineSequence()
            .map(String::trim)
            .filter { line -> line.startsWith(TASK_PROXY_RESULT_PREFIX) }
            .any { line -> line.removePrefix(TASK_PROXY_RESULT_PREFIX) == "$taskId=true" }
        check(removed) { "task $taskId was not removed: ${output.trim()}" }
    }

    override fun moveTask(taskId: Int, toRootId: Int) {
        budgeted("am stack move-task $taskId $toRootId true")
    }

    private fun budgeted(command: String): String {
        val deadline = deadlineAtMs ?: (clock.nowMs() + budgetMs).also { deadlineAtMs = it }
        check(clock.nowMs() <= deadline) { "rollback budget spent before: $command" }
        return shell(command)
    }

    private fun quoted(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val TASK_PROXY_RESULT_PREFIX = "DENZA_SPLIT_RESULT:"
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

    /**
     * Message the automaton planned for a failure this operation already settled as a fact.
     *
     * It is deliberately separate from [settledNotice]: that one belongs to a committed outcome,
     * and reusing it would make a rejected commit publish the success message.
     */
    protected var plannedFailureNotice: String? = null

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

    /**
     * One step of an operation, in milliseconds since the user asked for it (1.13).
     *
     * The clock starts at submit time rather than at dequeue, because a tap that waited behind
     * another operation waited for the user too; the deadline the actor fixed then is what carries
     * that instant into the run. A handful of these per operation is the whole instrument: enough
     * to say which step of an open the seconds went into, and never a line per command.
     */
    protected fun mark(op: SplitOperationContext, step: String) {
        work.log("$label +${op.clock.nowMs() - requestedAtMs(op)}ms $step")
    }

    /** When the user asked: the actor fixed the deadline one budget after the submit. */
    private fun requestedAtMs(op: SplitOperationContext): Long = op.token.deadlineAtMs - durationMs

    protected fun settle(fact: SplitFact): List<SplitPlan> {
        val reduction = SplitAutomaton.reduce(working, fact)
        working = reduction.state
        return reduction.plans
    }

    /**
     * Takes the global leases the product borrows, recording what each one displaced.
     *
     * Only the leases a rollback is allowed to give back are journalled
     * ([SplitLeaseKind.ROLLBACK_EXEMPT]): an infrastructure lease is idempotent and survives a
     * failed operation, so that a second attempt starts from an observer that is already bound
     * instead of rebuilding what its own predecessor tore down.
     */
    protected fun enableLeases(
        op: SplitOperationContext,
        shell: (String) -> String,
        kinds: Set<String>,
    ) {
        work.leases.filter { lease -> lease.kind in kinds }.forEach { lease ->
            val previous = lease.ownedValue()
            lease.enable(shell)
            if (lease.kind in SplitLeaseKind.ROLLBACK_EXEMPT) return@forEach
            // A lease the product already held is not this operation's to give back: unwinding it
            // would end a session over a scene that is still on screen (1.12, "пока сцена жива -
            // ничего не восстанавливать").
            if (previous != null) return@forEach
            op.journal.record(SplitJournalEntry.LeaseEnabled(lease.kind, previous))
        }
    }

    /** Every removal is irreversible; the journal says so before the recipe that may perform one. */
    protected fun pointOfNoReturn(op: SplitOperationContext, reason: String) {
        op.journal.record(SplitJournalEntry.PointOfNoReturn(reason))
    }

    /**
     * Records a task this operation is proven to have created, so a later failure can take it back.
     *
     * [preexisting] is the set of ids the panes already held when the operation started looking.
     * `null` means the operation could not read it, and then nothing is recorded at all: a recipe
     * may legitimately hand back a task it adopted rather than launched - `openPickers` reuses a
     * still-living picker base, `restoreApp` keeps a still-living app (1.3.2, U2) - and removing one
     * of those on a rollback would destroy exactly what the contract promises to preserve.
     */
    protected fun recordCreated(
        op: SplitOperationContext,
        preexisting: Set<Int>?,
        taskId: Int,
        component: String,
    ) {
        if (preexisting == null || taskId in preexisting) return
        op.journal.record(SplitJournalEntry.TaskCreated(taskId, component))
    }

    /**
     * Records a removal that already happened. It is irreversible by nature (a removed task cannot
     * be recreated), so the entry exists to be reported rather than executed - which is also why
     * the package identity the recipe verified is enough to name it.
     */
    protected fun recordRemoved(op: SplitOperationContext, taskId: Int, identity: String) {
        op.journal.record(SplitJournalEntry.TaskRemoved(taskId, identity))
    }

    /** The ids the two panes hold right now, or `null` when the topology cannot be read at all. */
    protected fun observedTaskIds(split: SplitPickerShellSession): Set<Int>? = runCatching {
        SplitPane.entries.flatMapTo(mutableSetOf()) { pane ->
            split.observePane(pane, SPLIT_PICKER_COMPONENT_SET).observedTaskIds
        }
    }.getOrNull()

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
        // U5, once. When the automaton already answered this exact failure with a plan, that plan
        // is the message; translating the raw reason on top of it would say the same thing twice.
        failed(
            plannedFailureNotice
                ?: SplitCoordinatorCore.friendlyError(
                    reason,
                    SplitCoordinatorCore.fallbackOf(label),
                ),
        )
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
            if (op.token.isAlive(op.clock.nowMs())) workspace.mirrorDiagnostics()
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
        mark(op, "dequeued")
        settle(SplitFact.OpenRequested)
        val restorable = SplitPickerSelectionPolicy.restorablePair(
            primaryPackage = (working.slot(SplitPane.PRIMARY) as? SplitSlot.App)?.packageName,
            secondaryPackage = (working.slot(SplitPane.SECONDARY) as? SplitSlot.App)?.packageName,
            installedPackages = work.catalog.installedPackages(),
        )
        mark(op, "catalog-ready")
        // Read-only: adoption is decided from a live snapshot before a single mutation (1.3.5).
        //
        // Nothing is swallowed here. "There is no scene of ours" is answered with `null` by the
        // recipe itself, so a throw can only be the fence or the link - and both belong on the
        // operation's ordinary error path, where the user is told (U5). A `getOrNull` around this
        // read turned a dead ADB and a cancelled token alike into "no scene" and then rebuilt the
        // screen on that reading.
        val adopted = work.split(op).existingOwnedSession(
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
            expectedApps = SplitCoordinatorCore.expectedApps(liveScene),
        )
        mark(op, if (adopted == null) "scene-read: nothing of ours" else "scene-read: adoptable")
        return SplitOpenPlan(adopted, restorable)
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: SplitOpenPlan) {
        val split = work.split(op)
        enableLeases(op, shell, ALL_LEASES)
        mark(op, "leases-taken")
        val adopted = plan.adopted
        if (adopted != null) {
            adopt(split.revealOwnedSession(adopted, SPLIT_PICKER_COMPONENT_SET))
            mark(op, "revealed")
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
        // One read before the first mutation: whatever the recipes hand back that is not in here
        // is a task this operation created, and therefore a task this operation owes an undo for.
        val preexisting = observedTaskIds(split)
        pointOfNoReturn(op, "building the scene prunes stale picker and app tasks")
        // `openPickers` opens the firmware gate on its way in and takes the lease for it. The entry
        // is recorded before the recipe, not after, so a failure inside the recipe can still close
        // it; the undo consults the lease, so an entry for a gate the recipe never reached costs
        // nothing (contract, to 1.12).
        op.journal.record(SplitJournalEntry.GateOpened(prevOpen = work.gateOwned()))
        val hostTaskIds = split.openPickers(SPLIT_PICKER_COMPONENTS, restorable)
        mark(op, "pickers-launched")
        hostTaskIds.values.forEach { taskId ->
            recordCreated(op, preexisting, taskId, SPLIT_PICKER_COMPONENT)
        }
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
                )
            }.onSuccess { placement ->
                recordCreated(op, preexisting, placement.appTaskId, target.componentName)
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
        if (restorable.isNotEmpty()) mark(op, "apps-restored")
        liveScene = panes
        settle(SplitFact.BuildSceneSucceeded(sceneSlots(panes)))
        settledNotice =
            if (failures.isEmpty()) "" else splitRestoreFailureNotice(failures.map(work.appLabel))
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
        // Only resizeability: a tap in a picker must not drag the accessibility observer's
        // disable/re-enable dance into the middle of a launch the user is watching.
        enableLeases(op, shell, setOf(SplitLeaseKind.RESIZEABILITY))
        val preexisting = observedTaskIds(split)
        pointOfNoReturn(op, "selecting an app clears the tasks above its picker")
        val settled = try {
            split.selectApp(
                pickerTaskId = pickerTaskId,
                target = target,
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
            )
        } catch (error: Throwable) {
            settleLaunchFailure(error)
            throw error
        }
        placement = settled
        recordCreated(op, preexisting, settled.appTaskId, target.componentName)
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

    /**
     * Contract 1.5.7. The recipe has already put the pane back on its picker and kept the
     * neighbour out of it; what was missing was the fact. The automaton answers it with the plan
     * that carries the message, so the text the user reads is produced where every other product
     * decision is produced rather than by this catch block.
     *
     * The pane comes from the live scene the operation started with - a picker task id names its
     * pane and nothing else does - so a failure this process cannot place stays a plain failure.
     */
    private fun settleLaunchFailure(error: Throwable) {
        val pane = liveScene.entries
            .firstOrNull { (_, observed) -> observed.hostTaskId == pickerTaskId }
            ?.key
            ?: return
        val reason = SplitCoordinatorCore.friendlyError(
            error.message,
            SplitCoordinatorCore.SELECT_FAILURE,
        )
        plannedFailureNotice = settle(SplitFact.AppLaunchFailed(pane, reason))
            .filterIsInstance<SplitPlan.Notice>()
            .firstOrNull()
            ?.text
    }

    override fun failed(message: String) = work.notices.publish(message)
}

// endregion

// region package removal

/**
 * Contract 1.5.6: an app was uninstalled while its picker was on screen.
 *
 * The picker's own package receiver is what makes this prompt, and it exists only while a picker
 * is alive; the lazy rule of section 6 stays the safety net for every removal nobody was listening
 * for. The operation sends not one command: an uninstall is a package fact and the panes it
 * touches are durable slots, so the whole operation is a guard plus a fact.
 */
internal class PackageRemovedOperation(
    work: SplitOperationWorkspace,
    private val packageName: String,
) : SplitCoreOperation<Boolean>(
    label = SplitCoordinatorCore.PACKAGE_REMOVED_LABEL,
    priority = SplitInputPriority.HINT,
    durationMs = PACKAGE_REMOVED_BUDGET_MS,
    joinKey = null,
    coalesceKey = PACKAGE_REMOVED_COALESCE_PREFIX + packageName,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean =
        working.enabled && recorded()

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        if (!plan) return
        settle(SplitFact.PackageRemoved(packageName))
    }

    /** Anywhere the product still claims this package: a durable slot or a projected vacancy. */
    private fun recorded(): Boolean =
        working.slots.values.any { slot -> slot is SplitSlot.App && slot.packageName == packageName } ||
            working.vacancyApp.containsValue(packageName)
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
                // `attachPicker` refuses unless the pane's host is the stock bootstrap, so the
                // picker it returns is always one it just launched: no adoption is possible here.
                op.journal.record(SplitJournalEntry.TaskCreated(pickerTaskId, SPLIT_PICKER_COMPONENT))
                liveScene = liveScene + (pane to SplitPickerLivePane(pane, pickerTaskId, null, null))
                settle(SplitFact.EdgeCommitConfirmed(pane))
            }.onFailure { error ->
                work.log("failed to attach the picker in $pane: $error")
            }
        }
    }
}

// endregion

// region navigation (contract 1.10)

/**
 * Contract 1.10.1: the navigator went to the instrument cluster.
 *
 * The pane is resolved from the live topology - the task id is the navigation lease's own, and it
 * is checked against a fresh snapshot before it names a pane - and enters the automaton as a
 * settled fact on the worker, like every other fact (invariant 12).
 */
internal class NavProjectionStartedOperation(
    work: SplitOperationWorkspace,
    private val taskId: Int,
) : SplitCoreOperation<Unit>(
    label = SplitCoordinatorCore.NAV_STARTED_LABEL,
    priority = SplitInputPriority.NAV,
    durationMs = NAV_FACT_BUDGET_MS,
    joinKey = null,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String) = Unit

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Unit) {
        // The automaton ignores every fact but the toggle while the product is off, so reading the
        // car here would buy nothing and cost commands (invariant 1).
        if (!working.enabled) return
        settle(SplitFact.ProjectionStarted(resolvePane(op) ?: return))
    }

    private fun resolvePane(op: SplitOperationContext): SplitPane? {
        val recorded = liveScene.entries.firstOrNull { it.value.appTaskId == taskId }?.key
        val settled = runCatching {
            work.split(op).existingOwnedSession(
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
                expectedApps = SplitCoordinatorCore.expectedApps(liveScene),
            )
        }.getOrNull()
            ?.entries
            ?.firstOrNull { it.value.appTaskId == taskId }
            ?.key
        return settled ?: recorded
    }
}

/** Contract 1.10.3: the navigator is back; the projection axis and its vacancy record are spent. */
internal class NavProjectionReturnedOperation(
    work: SplitOperationWorkspace,
) : SplitCoreOperation<Unit>(
    label = SplitCoordinatorCore.NAV_RETURNED_LABEL,
    priority = SplitInputPriority.NAV,
    durationMs = NAV_FACT_BUDGET_MS,
    joinKey = null,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String) = Unit

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Unit) {
        settle(SplitFact.ProjectionReturned)
    }
}

/**
 * Contract 1.10.3-1.10.6, first half of a return: choose the exact IVI destination.
 *
 * The plan is resolved from the live topology immediately before the navigation lease moves the
 * task, never from a remembered pane: vacancy is what the car shows right now.
 */
internal class NavPrepareOperation(
    work: SplitOperationWorkspace,
    private val originalRootTaskId: Int,
    private val prepared: AtomicReference<SplitNavigationReturnPlan?>,
) : SplitCoreOperation<Unit>(
    label = SplitCoordinatorCore.NAV_PREPARE_LABEL,
    priority = SplitInputPriority.NAV,
    durationMs = NAV_PREPARE_BUDGET_MS,
    joinKey = SplitCoordinatorCore.NAV_PREPARE_LABEL,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String) = Unit

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Unit) {
        val split = work.split(op)
        // With the toggle off the product owns no pane, so the navigator is told to come back
        // fullscreen. Reading the full-IVI root moves nothing (invariant 1).
        if (!working.enabled) {
            prepared.set(
                SplitNavigationReturnPlan(
                    pane = null,
                    rootTaskId = split.fullIviRootTaskId(),
                    hostTaskId = null,
                    fullscreen = true,
                ),
            )
            return
        }
        prepared.set(
            split.prepareNavigationReturn(
                originalRootTaskId = originalRootTaskId,
                pickerComponents = SPLIT_PICKER_COMPONENT_SET,
                expectedApps = SplitCoordinatorCore.expectedApps(liveScene),
            ),
        )
    }
}

/**
 * Contract 1.10.3-1.10.6, second half: clear the chosen pane, verify the return and settle it.
 *
 * The vacancy occupant of the plan is only a hint (invariant 4): `removeRecordedTask` re-reads the
 * car and removes that exact task id and package or nothing at all (1.10.4). Removal is
 * irreversible, so the journal says so before the first one.
 */
internal class NavCompleteOperation(
    work: SplitOperationWorkspace,
    private val returnPlan: SplitNavigationReturnPlan,
    private val taskId: Int,
    private val packageName: String,
) : SplitCoreOperation<Unit>(
    label = SplitCoordinatorCore.NAV_COMPLETE_LABEL,
    priority = SplitInputPriority.NAV,
    durationMs = NAV_COMPLETE_BUDGET_MS,
    joinKey = SplitCoordinatorCore.NAV_COMPLETE_LABEL,
    coalesceKey = null,
    work = work,
) {
    override fun prepare(op: SplitOperationContext, shell: (String) -> String) = Unit

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Unit) {
        val split = work.split(op)
        // 1.10.6: the pane was collapsed while the navigator was away, so it comes back fullscreen
        // instead of the product guessing a new split destination.
        if (returnPlan.fullscreen) {
            val pane = returnPlan.pane ?: return
            split.returnRecordedTaskFullscreen(pane, taskId, packageName)
            settle(SplitFact.HomeConfirmed)
            return
        }
        if (returnPlan.displacedTasks.isNotEmpty()) {
            pointOfNoReturn(op, "the navigator takes ${returnPlan.pane} back from its occupant")
            returnPlan.displacedTasks.forEach { displaced ->
                if (split.removeRecordedTask(displaced.taskId, displaced.packageName)) {
                    recordRemoved(op, displaced.taskId, displaced.packageName)
                }
            }
        }
        val placement = split.verifyNavigationReturned(
            plan = returnPlan,
            taskId = taskId,
            packageName = packageName,
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
        )
        liveScene = liveScene + (
            placement.pane to SplitPickerLivePane(
                pane = placement.pane,
                hostTaskId = placement.hostTaskId,
                appTaskId = placement.appTaskId,
                appPackageName = placement.packageName,
            )
            )
        settle(SplitFact.ProjectionReturned)
        // Normally a no-op: the pane kept `APP(navigator)` for the whole projection (to 1.10). It
        // matters when the firmware handed the navigator the other pane, and then it is the one
        // durable change this operation commits.
        settleOccupant(placement.pane, placement.packageName)
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
 * Contract 1.6-1.8, 1.11.3: the passive class of inputs.
 *
 * It is the only operation allowed to decide it has nothing to do - and it decides that before
 * opening a shell, so a hundred window hints over a disabled product cost exactly zero commands
 * (K6, invariant 8). What it does do is translate the settled topology the recipes report into
 * facts; the removal rules below are the ones the previous picker automaton earned on the car and
 * are kept verbatim in meaning.
 *
 * It is also the only way back after a process death. The scene is not durable and must not be, so
 * a restarted process starts with none - but the pickers on the screen are still ours, and a hint
 * that can only come from them is allowed to prove that scene again and take it back.
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
    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean {
        if (!working.enabled || work.externalMoveInFlight()) return false
        return working.scene != null || adoptable()
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        if (!plan) return
        val split = work.split(op)
        // Nothing below means anything without a scene axis. A hint that can only come from a live
        // product scene is allowed to prove one first (1.11.3); everything else fails closed here.
        if (working.scene == null && !adoptOwnedScene(split)) return
        val proven = when (kind) {
            SplitReconcileKind.DividerResized -> reconcileScene(op, split, settleResize = true)
            is SplitReconcileKind.PickerVisible -> pickerVisible(op, split, kind.hostTaskId)
            is SplitReconcileKind.PickerHidden -> pickerHidden(op, split, kind.hostTaskId)
        }
        // Only once no recipe could prove anything about this scene is "it is gone" a candidate
        // explanation at all - a collapse, a resize repair and a revealed picker all get to speak
        // first, and each of them proves the scene still exists (1.7.5).
        if (!proven) settleSceneEnded(op, split)
        // Contract 1.6: the outcome of Back and of a close is the firmware's, and the product's one
        // duty afterwards is to leave nothing of its own behind - no borrowed firmware setting and
        // no gate we opened - so that the next tap opens cleanly (1.6.4). Nothing is rebuilt here.
        if (working.scene == null) endSession(op, shell, split)
    }

    /**
     * Gives back what this session borrowed from the firmware, and only that.
     *
     * The gate closes by its own single rule ("we opened it"), and the session-scoped leases are
     * put back by compare-and-restore. What is deliberately *not* released is the infrastructure:
     * resizeability and the observer belong to the toggle being on, and a session that ended
     * natively is followed by a button the user can press again (1.2 owns those).
     */
    private fun endSession(
        op: SplitOperationContext,
        shell: (String) -> String,
        split: SplitPickerShellSession,
    ) {
        runCatching { split.closeOwnedGate() }
            .onFailure { error -> work.log("failed to close our gate after the scene ended: $error") }
        work.leases
            .filter { lease -> lease.kind in SplitLeaseKind.SESSION_SCOPED }
            .forEach { lease ->
                runCatching { lease.restore(shell) }.onFailure { error ->
                    work.log("failed to give back ${lease.kind} after the scene ended: $error")
                }
            }
    }

    /**
     * Which hints may look for a scene this process never built (contract 1.11.3, scenario 19).
     *
     * Our own picker reporting itself visible can only happen inside a live product scene, so it
     * carries the claim by itself. A divider move is ambient, so it may only try while a pair is
     * still remembered. A hidden picker is the dismiss gesture (1.6.3) and adopts nothing.
     */
    private fun adoptable(): Boolean = when (kind) {
        is SplitReconcileKind.PickerVisible -> true
        SplitReconcileKind.DividerResized ->
            working.slots.values.any { slot -> slot != SplitSlot.Closed }
        is SplitReconcileKind.PickerHidden -> false
    }

    /**
     * Re-adopts the scene a dead process left running on the screen.
     *
     * Nothing is remembered across the death and nothing may be (invariant 4): the scene is proven
     * again from the car by our exact picker components and the live root topology, never by a task
     * id. `existingOwnedSession` is read-only, so a foreign split - or anything it cannot fully
     * prove - simply yields nothing and the operation commits without a single mutation
     * (invariant 2). Adoption observes and accepts; it never launches or moves a task.
     */
    private fun adoptOwnedScene(split: SplitPickerShellSession): Boolean {
        val settled = runCatching { split.existingOwnedSession(SPLIT_PICKER_COMPONENT_SET) }
            .getOrNull()
            ?: return false
        liveScene = settled
        settle(SplitFact.BuildSceneSucceeded(sceneSlots(settled)))
        return working.scene != null
    }

    /**
     * A revealed picker means the task that covered it is gone (1.6.2, 1.7.1-1.7.3). The recorded
     * app is removed only when this very snapshot still lists it in that root: that is the rule the
     * old automaton used, and it is what keeps a crash and a Back indistinguishable and safe.
     *
     * @return whether the scene was proven to still exist by this hint.
     */
    private fun pickerVisible(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        requestedHostTaskId: Int?,
    ): Boolean {
        val hostTaskId = requestedHostTaskId
            ?: split.singleVisiblePickerTaskId(SPLIT_PICKER_COMPONENT_SET)
            ?: return false
        if (!reconcileScene(op, split, settleResize = false)) return false
        closeRevealedApp(op, split, hostTaskId)
        return true
    }

    private fun closeRevealedApp(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        hostTaskId: Int,
    ) {
        val observation = split.observePickerTask(hostTaskId, SPLIT_PICKER_COMPONENT_SET) ?: return
        if (!observation.pickerVisible) return
        val pane = observation.pane
        val observed = liveScene[pane] ?: return
        val appTaskId = observed.appTaskId ?: return
        val packageName = observed.appPackageName ?: return
        if (appTaskId !in observation.observedTaskIds) return
        pointOfNoReturn(op, "the app of $pane left its pane")
        if (split.removeRecordedTask(appTaskId, packageName)) {
            recordRemoved(op, appTaskId, packageName)
        }
        liveScene = liveScene + (pane to observed.copy(appTaskId = null, appPackageName = null))
        settle(SplitFact.AppClosedSettled(pane))
    }

    /**
     * A picker that left both panel roots is the dismiss gesture, and only then (1.6.3, 1.7.4).
     * A hidden picker under a live app is Android reclaiming an invisible Activity, never a close.
     *
     * @return whether this hint closed a pane. Everything else it can observe - an unknown host, a
     * live app above the picker, a picker still in its panel root - is left to the scene check.
     */
    private fun pickerHidden(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        hostTaskId: Int,
    ): Boolean {
        val pane = liveScene.entries
            .firstOrNull { (_, observed) -> observed.hostTaskId == hostTaskId }
            ?.key
            ?: return false
        if (liveScene.getValue(pane).appTaskId != null) return false
        if (split.observePickerTask(hostTaskId, SPLIT_PICKER_COMPONENT_SET) != null) return false
        pointOfNoReturn(op, "removing the dismissed picker of $pane")
        if (split.removePickerArtifact(hostTaskId, SPLIT_PICKER_COMPONENT_SET)) {
            recordRemoved(op, hostTaskId, SPLIT_PICKER_COMPONENT)
        }
        liveScene = liveScene - pane
        settle(SplitFact.PickerPaneClosedSettled(pane))
        return true
    }

    /**
     * Contract 1.7.5 and scenario 30: "Clear all" in Recents, and every other way a whole scene can
     * stop existing while this process is not looking at it.
     *
     * The proof is **existence**, never visibility (invariant 5). Home, Recents and a foreign
     * fullscreen app all *cover* a scene whose tasks are still listed in the two panel roots, so
     * none of them can ever reach the fact below; only a fresh snapshot in which not one task of
     * the verified scene is left in either panel root can. Nothing is remembered beyond the
     * ephemeral live hint the operations already keep (invariant 4), and the check reads: no
     * mutation, no point of no return, one settled fact.
     *
     * The automaton then keeps the projected navigator's slot and turns every other `APP` into
     * `PICKER`, which is what makes the next open show fresh pickers instead of resurrecting what
     * the user cleared (1.3.4, invariant 6).
     *
     * Forgetting is not enough, though: a picker of ours that left the panel roots without dying is
     * exactly what this check cannot see, and the vertical slice met one - a narrow-bounds stump
     * that survived Back and spoiled the next run. So the tasks this scene recorded as its own
     * pickers are removed here, by exact id and component, wherever they ended up (1.6, 1.6.4).
     */
    private fun settleSceneEnded(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
    ): Boolean {
        if (working.scene == null) return false
        val recorded = liveScene.values.flatMapTo(mutableSetOf()) { observed ->
            listOfNotNull(observed.hostTaskId, observed.appTaskId)
        }
        if (recorded.isEmpty()) return false
        val living = observedTaskIds(split) ?: return false
        if (recorded.any { taskId -> taskId in living }) return false
        val ownPickers = liveScene.values.map(SplitPickerLivePane::hostTaskId)
        liveScene = emptyMap()
        settle(SplitFact.SceneEndedSettled)
        removeOwnPickerStumps(op, split, ownPickers)
        return true
    }

    /**
     * The apps are the user's and are never touched here (1.6.2): only the permanent picker bases
     * this scene created, and only where a fresh snapshot still finds that exact id under our own
     * component. A removal cannot be undone, so the journal says so before the first one.
     */
    private fun removeOwnPickerStumps(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        taskIds: List<Int>,
    ) {
        if (taskIds.isEmpty()) return
        pointOfNoReturn(op, "a natively ended scene leaves no picker of ours behind")
        runCatching { split.removePickerArtifacts(taskIds, SPLIT_PICKER_COMPONENT_SET) }
            .onSuccess { removed ->
                removed.forEach { taskId -> recordRemoved(op, taskId, SPLIT_PICKER_COMPONENT) }
            }
            .onFailure { error -> work.log("failed to remove a picker left behind: $error") }
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
            previous[previousOwner]?.let { owner -> removeRecordedApp(op, split, owner) }
        }
        closed?.let { pane ->
            removeRecordedApp(op, split, pane)
            runCatching { split.removePickerArtifact(pane.hostTaskId, SPLIT_PICKER_COMPONENT_SET) }
                .onSuccess { removed ->
                    if (removed) recordRemoved(op, pane.hostTaskId, SPLIT_PICKER_COMPONENT)
                }
                .onFailure { error -> work.log("failed to remove the collapsed picker: $error") }
        }
        liveScene = mapOf(survivor to collapsed)
        settle(SplitFact.PaneCollapsedSettled(survivor))
        settleOccupant(survivor, collapsed.appPackageName)
    }

    private fun removeRecordedApp(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        observed: SplitPickerLivePane,
    ) {
        val taskId = observed.appTaskId ?: return
        val packageName = observed.appPackageName ?: return
        runCatching { split.removeRecordedTask(taskId, packageName) }
            .onSuccess { removed -> if (removed) recordRemoved(op, taskId, packageName) }
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

private val ALL_LEASES = setOf(
    SplitLeaseKind.RESIZEABILITY,
    SplitLeaseKind.PICKER_ACCESS,
    SplitLeaseKind.SMART_MULTI,
)
private const val SELECT_JOIN_PREFIX = "select-"
private const val PACKAGE_REMOVED_COALESCE_PREFIX = "package-removed-"
private const val TOGGLE_BUDGET_MS = 30_000L
private const val OPEN_BUDGET_MS = 15_000L
private const val SELECT_BUDGET_MS = 20_000L
private const val HOME_BUDGET_MS = 10_000L
private const val EDGE_BUDGET_MS = 25_000L
private const val RECONCILE_BUDGET_MS = 30_000L

/** No shell at all, so the only thing this budget covers is waiting out the queue ahead of it. */
private const val PACKAGE_REMOVED_BUDGET_MS = 30_000L

/**
 * How long an unwind may keep the single worker while it puts things back.
 *
 * It is short on purpose: an operation is usually being unwound because the link stopped
 * answering, and Home and the toggle are waiting behind this thread.
 */
private const val ROLLBACK_BUDGET_MS = 10_000L

/**
 * Navigation budgets (canon: an operation's deadline is fixed at submit time).
 *
 * `NAV` outranks `SELECT` and `OPEN` in the queue but never preempts one already in flight, so both
 * budgets have to cover waiting out the longest predecessor that can be running - a `SELECT` - on
 * top of the recipe's own worst case.
 */
private const val NAV_FACT_BUDGET_MS = 10_000L
internal const val NAV_PREPARE_BUDGET_MS = 20_000L
internal const val NAV_COMPLETE_BUDGET_MS = 35_000L
