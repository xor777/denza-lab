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
    val leases: List<SplitLeaseController>,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val apkPath: String,
    private val proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
    private val clock: SplitClock,
    private val sleeper: (Long) -> Unit,
    private val diagnostics: SplitDiagnosticLog,
    private val readState: () -> SplitState,
    private val readLive: () -> SplitLiveScene,
    /** Panes a refused open left standing on a bare picker (1.3.5); ephemeral, read-only here. */
    private val readUnfinished: () -> Map<SplitPane, String> = { emptyMap() },
    private val markUnfinished: (Map<SplitPane, String>) -> Unit = {},
    val externalMoveInFlight: () -> Boolean,
    /** Правка W5, §4: явный запрос пользователя ждёт в очереди прямо сейчас. Read-only. */
    val userInputWaiting: () -> Boolean = { false },
    private val publisher: (SplitState, SplitLiveScene) -> Unit,
) : AutoCloseable {

    private val handleLock = Any()
    private var handle: SplitShellHandle? = null

    /** Shared by every read of this operation, dropped by every command that could move a task. */
    private val topology = SplitTopologyCache()

    private val budgetLock = Any()
    private var shellCalls = 0
    private var shellMs = 0L
    private var pauseMs = 0L

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
        val startedAtMs = clock.nowMs()
        try {
            return openedHandle().shell(command)
        } finally {
            synchronized(budgetLock) {
                shellCalls += 1
                shellMs += clock.nowMs() - startedAtMs
            }
        }
    }

    /**
     * Every settle pause of every recipe, for the same reason [shell] is the one funnel of commands.
     *
     * A recipe waits on the firmware far more often than it talks to it, so an operation that is
     * slow because it is waiting and an operation that is slow because it is asking are two
     * different defects. The budget line separates them (правка Ф5 волны 15).
     */
    fun pause(millis: Long) {
        val startedAtMs = clock.nowMs()
        try {
            sleeper(millis)
        } finally {
            synchronized(budgetLock) { pauseMs += clock.nowMs() - startedAtMs }
        }
    }

    /**
     * What this operation cost the car, in the only two currencies it spends: обращения и ожидание.
     *
     * One line per operation, written after the operation is over and its transport released, so a
     * later wave can say whether it made the same scene cheaper instead of guessing. An operation
     * that touched nothing says nothing (invariant 1, K6/K7).
     */
    fun reportBudget(label: String) {
        val calls: Int
        val shell: Long
        val pause: Long
        synchronized(budgetLock) {
            calls = shellCalls
            shell = shellMs
            pause = pauseMs
        }
        if (calls == 0 && pause == 0L) return
        diagnostics.log(
            "$label: обращений $calls, в shell ${seconds(shell)} с, в паузах ${seconds(pause)} с",
        )
    }

    fun state(): SplitState = readState()

    fun live(): SplitLiveScene = readLive()

    fun unfinishedRestore(): Map<SplitPane, String> = readUnfinished()

    fun markUnfinishedRestore(panes: Map<SplitPane, String>) = markUnfinished(panes)

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
            settle = op.fencedPause(::pause),
            gateLeaseStore = gateLeaseStore,
            topology = topology,
            proxyClasspath = proxyClasspath,
        ).also { built -> session = built }
    }

    fun publish(state: SplitState, live: SplitLiveScene) {
        publisher(state, live)
    }

    /**
     * Contract 7.7: a read-back is by definition a read this operation has not taken yet.
     *
     * The area query the recipes end on is a topology *read*, so it deliberately keeps the shared
     * `am stack list` alive - which is right everywhere inside a recipe and wrong for the one read
     * that has to prove the settled scene from the car.
     */
    fun dropSharedReads() = topology.invalidate()

    override fun close() {
        val open = synchronized(handleLock) {
            session = null
            handle.also { handle = null }
        }
        topology.invalidate()
        open?.let { runCatching(it::close) }
    }

    private fun openedHandle(): SplitShellHandle = synchronized(handleLock) {
        handle ?: shellFactory.open().also { handle = it }
    }

    private companion object {
        const val ALLOWLIST_COMMAND_PREFIX = "service call activity_task 125 s16 "

        /** Tenths of a second, without a locale of its own; the ring is read, not parsed. */
        fun seconds(millis: Long): String = "${millis / 1000}.${millis % 1000 / 100}"
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

    /**
     * Panes standing on a bare picker only because a refused open did not finish standing their
     * application up (1.3.5). Read once, at planning time, like every other ephemeral hint.
     */
    protected var unfinishedRestore: Map<SplitPane, String> = emptyMap()

    final override fun plan(op: SplitOperationContext, shell: (String) -> String): P? {
        working = work.state()
        liveScene = work.live()
        unfinishedRestore = work.unfinishedRestore()
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
     * in its own live-proven postcondition (`awaitScenePlacement`, `awaitSelectedAppPlacement`,
     * `attachPicker`'s top check, `closePickers`' area check), measured on a fresh snapshot. The
     * two operations that own a whole scene - `OPEN` and `SELECT` - override this and read it back.
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

    /**
     * Everything already running before this operation's first mutation, for [recordCreated].
     *
     * It is the whole main display rather than the two panes: a restore reuses the task its package
     * already had, and that task usually lives outside the panes - fullscreen, or in the background.
     */
    protected fun preexistingTaskIds(split: SplitPickerShellSession): Set<Int>? =
        runCatching { split.livingTaskIds() }.getOrNull()

    /**
     * The slots a live topology proves - with two exceptions, both of them pickers that are scene
     * content rather than slot content.
     *
     * A projected pane keeps `APP(navigator)` while its picker is on screen, because the navigator
     * is on the cluster and this pane is where it comes back to (1.10.1). A pane of an unfinished
     * restore keeps `APP(package)` for the same reason one step removed: nobody closed anything
     * there, a refused open simply did not get as far as the application, and reading that picker
     * as the user's choice would throw away the very selection the next tap has to finish
     * restoring (1.3.5, invariant 9).
     */
    protected fun sceneSlots(live: SplitLiveScene): Map<SplitPane, SplitSlot> {
        val slots = SplitCoordinatorCore.slotsOf(live).toMutableMap()
        working.projectedPane?.let { projected -> slots[projected] = working.slot(projected) }
        unfinishedRestore.forEach { (pane, packageName) ->
            if (slots[pane] == SplitSlot.Picker) slots[pane] = SplitSlot.App(packageName)
        }
        return slots
    }

    /**
     * Only the permanent picker base of a pane the user closed, by exact identity, wherever it is.
     *
     * The application of that pane is never touched: the firmware detaches it alive ("Release to
     * close"), it keeps playing, and it is the user's (1.8.2, invariant 3).
     */
    protected fun removeCollapsedPicker(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        hostTaskId: Int,
    ) {
        runCatching { split.removePickerArtifact(hostTaskId, SPLIT_PICKER_COMPONENT_SET) }
            .onSuccess { removed ->
                if (removed) recordRemoved(op, hostTaskId, SPLIT_PICKER_COMPONENT)
            }
            .onFailure { error -> work.log("failed to remove the collapsed picker: $error") }
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

    /**
     * Called on the worker once the outcome is known, whatever it is.
     *
     * Only a committed operation publishes: what a failed one has to leave behind is the state the
     * user is already looking at, not a repaint of it (invariant 9, U5). What became of it is one
     * line of the diagnostic ring, written by the coordinator's own terminal.
     */
    open fun finished(outcome: SplitOutcome) {
        if (outcome is SplitOutcome.Committed) work.publish(working, liveScene)
    }
}

/**
 * Wraps one operation so that its shell is always closed, its price is always written and its
 * settled state is always published on the worker thread, immediately after the run, for every
 * outcome.
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
            // Правка Ф5 волны 15: the price of the operation, counted by the funnel every command
            // and every settle pause of it went through, written once the transport is released.
            workspace.reportBudget(operation.label)
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
 * every unknown or uninstalled package degrading to a fresh picker the user can immediately use.
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

    /** Правка волны 11: the bases of this build are standing, so a refusal may not take them. */
    private var rootsPlaced = false

    /** What this open owes the panes: the remembered pair it set out to restore (1.3.2). */
    private var owed: Map<SplitPane, String> = emptyMap()

    override fun prepare(op: SplitOperationContext, shell: (String) -> String): SplitOpenPlan? {
        if (!working.enabled) return null
        mark(op, "dequeued")
        settle(SplitFact.OpenRequested)
        // 1.3.4 is decided here or nowhere: this is the one moment a pane the user closed can be
        // brought back, so it is also the one moment worth asking the car again (правка волны 12).
        settleTheCollapseNobodyRead(op)
        // The durable slots are the selection, and nothing else has to be asked (1.13.1). Scanning
        // the launcher catalogue here cost 815 ms of every open just to filter out a package that
        // is no longer installed - and the pane that meets one degrades to a fresh picker anyway
        // (1.3.2), which is the same answer for one tenth of the wait.
        val restorable = SplitPane.entries.mapNotNull { pane ->
            (working.slot(pane) as? SplitSlot.App)?.let { slot -> pane to slot.packageName }
        }.toMap()
        owed = restorable
        // Read-only: adoption is decided from a live snapshot before a single mutation (1.3.5).
        //
        // Nothing is swallowed here. "There is no scene of ours" is answered with `null` by the
        // recipe itself, so a throw can only be the fence or the link - and both belong on the
        // operation's ordinary error path, where the user is told (U5). A `getOrNull` around this
        // read turned a dead ADB and a cancelled token alike into "no scene" and then rebuilt the
        // screen on that reading.
        val read = work.split(op).readOwnedSession(
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
            expectedApps = SplitCoordinatorCore.expectedApps(liveScene),
        )
        mark(op, "scene-read: ${read.reason}")
        return SplitOpenPlan(adoptable(read.scene, restorable, op), restorable)
    }

    /**
     * Contract 1.3.4: a pane the user closed is never restored - not even when nobody told the
     * product it had been closed (правка волны 12).
     *
     * Схлопывание доказывает сверка, и обычно она успевает. Живьём, однако, целая серия жестов не
     * оставила в ринге ни одной строки за 320 секунд: подсказки о движении дивайдера просто не
     * пришло, доказывать было некому, и слот закрытой панели дожил до этого места как `App(...)`.
     * Восстановить его отсюда - потерять решение пользователя, поэтому мир спрашивают ещё раз,
     * ровно в тот момент, когда ответ впервые начинает что-то значить.
     *
     * Стоит это ноль команд: [SplitPickerShellSession.collapsedPaneByPanelBounds] читает те же
     * два `activity_task 118` и тот же `am stack list`, которые следом читает scene-read, и они
     * общие в пределах операции ([SplitTopologyCache]). Мутаций здесь нет вовсе: огрызок пикера
     * закрытой панели уберёт сама сборка, которая сейчас переложит обе панели заново.
     */
    private fun settleTheCollapseNobodyRead(op: SplitOperationContext) {
        if (working.scene != SplitScene.Split) return
        val expected = SplitCoordinatorCore.collapseExpectation(liveScene)
            ?.takeIf { panes -> panes.keys == SplitPane.entries.toSet() }
            ?: return
        // Правка волны 13 (П2, U5): исключение этого предиката не пропадает молча. Ринг - это
        // место диагностики, и «не прочитано» без имени виновника стоило волне 12 целого цикла.
        val read = runCatching {
            work.split(op).collapsedPaneByPanelBounds(SPLIT_PICKER_COMPONENT_SET, expected)
        }.onFailure { error ->
            mark(op, "collapse before the restore: не прочитано ($error)")
        }.getOrNull() ?: return
        val collapsed = read.collapsed ?: return
        mark(op, "collapse settled before the restore: $collapsed")
        liveScene = liveScene - collapsed
        settle(SplitFact.PaneCollapsedSettled(collapsed.other()))
    }

    /**
     * Contract 1.3.5: "открыта" is about the scene that answers the saved selection.
     *
     * Two pickers standing while the slots still name two applications are an unfinished restore -
     * the shape a refused open leaves behind (invariant 9) - and showing them again as a finished
     * result would quietly turn the user's pair into "two empty panes". Such a scene is not
     * adopted; the tap finishes it. A projected pane is the standing exception: its picker is
     * scene content and its navigator is on the cluster, so it is not missing anything (1.10.1).
     */
    private fun adoptable(
        read: SplitLiveScene?,
        restorable: Map<SplitPane, String>,
        op: SplitOperationContext,
    ): SplitLiveScene? {
        if (read == null) return null
        val unfinished = restorable.keys.filter { pane ->
            pane != working.projectedPane && read[pane]?.appPackageName == null
        }
        if (unfinished.isEmpty()) return read
        mark(op, "unfinished restore: ${unfinished.joinToString(", ")}")
        return null
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: SplitOpenPlan) {
        val split = work.split(op)
        enableLeases(op, shell, ALL_LEASES)
        mark(op, "leases-taken")
        val adopted = plan.adopted
        if (adopted != null) {
            // Raising a covered scene reopens the gate Home suspended (contract, to 1.12), so the
            // entry goes in before the recipe, exactly as it does on the build path below.
            op.journal.record(SplitJournalEntry.GateOpened(prevOpen = work.gateOwned()))
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
    }

    private fun build(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        restorable: Map<SplitPane, String>,
    ) {
        // One read before the first mutation: whatever the recipe hands back that is not in here
        // is a task this operation created, and therefore a task this operation owes an undo for.
        val preexisting = preexistingTaskIds(split)
        // A package the catalogue cannot resolve was uninstalled while the slot remembered it: its
        // pane degrades to a fresh picker, and its name is one line of the ring (1.3.2, U5).
        val unresolved = mutableListOf<String>()
        val targets = restorable.mapNotNull { (pane, packageName) ->
            val target = work.catalog.resolve(packageName)
            if (target == null) {
                unresolved += packageName
                null
            } else {
                pane to target
            }
        }.toMap()
        pointOfNoReturn(op, "building the scene clears what its panes still hold")
        // `buildScene` opens the firmware gate on its way in and takes the lease for it. The entry
        // is recorded before the recipe, not after, so a failure inside the recipe can still close
        // it; the undo consults the lease, so an entry for a gate the recipe never reached costs
        // nothing (contract, to 1.12).
        op.journal.record(SplitJournalEntry.GateOpened(prevOpen = work.gateOwned()))
        val built = split.buildScene(
            pickerComponents = SPLIT_PICKER_COMPONENTS,
            targets = targets,
            // Правка B1: the exact identities this process still holds let the build take a
            // survivor back by reparenting instead of launching; a process that remembers
            // nothing passes nothing, and the build launches honestly (invariant 4).
            expectedApps = SplitCoordinatorCore.expectedApps(liveScene),
            // Правка W6: the same read that decides what this operation owes an undo for also
            // decides what a failed pane may execute - only what the build itself created.
            preexistingTaskIds = preexisting,
            // Правка W10: the build's own phases, stamped with the user's waiting time - the next
            // red branch reads which step the seconds went into straight off the log. Правка
            // волны 11: one of those phases is also a decision - see [keepStandingBases].
            onPhase = { phase ->
                mark(op, phase)
                if (phase == SPLIT_PHASE_ROOTS_PLACED) keepStandingBases(op)
            },
        ) { task ->
            // A task that was already running is one this build only borrowed: its inverse is the
            // root it came from, never a removal (U2). Everything else this build made itself.
            if (preexisting != null && task.taskId in preexisting) {
                if (task.fromRootId != task.toRootId) {
                    op.journal.record(
                        SplitJournalEntry.TaskMoved(task.taskId, task.fromRootId, task.toRootId),
                    )
                }
            } else {
                recordCreated(op, preexisting, task.taskId, task.component)
            }
        }
        mark(op, "scene-built")
        // The build finished and every pane is now what the recipe proved it to be, including a
        // pane it honestly could not restore: that one is a picker of the user's, not an unfinished
        // restore of ours, and 1.3.2 wants it recorded as the picker it is.
        unfinishedRestore = emptyMap()
        val failures = unresolved + built.failed.mapNotNull { pane -> restorable[pane] }
        failures.forEach { packageName -> work.log("failed to restore $packageName") }
        liveScene = built.panes
        settle(SplitFact.BuildSceneSucceeded(sceneSlots(built.panes)))
    }

    /**
     * Invariant 9, and the defect it was rewritten for: a refused open used to leave a blank screen.
     *
     * The rollback walks the journal backwards and stops at the first point of no return, and the
     * only one an open recorded was written *before* the build started - so every picker base the
     * build had just stood up was taken back down again, and a red branch of acceptance v25 ended
     * on "остаток [Brave|music], баз нет": the user tapped a button and got nothing at all.
     *
     * The bases are the part of a scene the recipe has actually proven by this point: both of them
     * are in their panel roots and the firmware is holding the split. A pane on its own picker is a
     * usable state (U5) - the catalogue is there and the tap works - so past this line the open may
     * only go forward. What it launched *above* the bases is still unproven and is still taken back
     * by the unwind, which is what leaves the panes on those pickers.
     */
    private fun keepStandingBases(op: SplitOperationContext) {
        rootsPlaced = true
        pointOfNoReturn(op, "the panel bases are standing; a refusal leaves the user their pickers")
    }

    /**
     * Contract 1.3.5, second half: the pair the refused open did not manage to restore.
     *
     * Nobody closed those panes - this open simply did not get as far as their applications - so
     * the selection is not touched (invariant 9) and the panes are remembered as an unfinished
     * restore, which is what makes the next tap finish it instead of adopting two empty pickers.
     */
    override fun finished(outcome: SplitOutcome) {
        if (outcome !is SplitOutcome.Committed && rootsPlaced) work.markUnfinishedRestore(owed)
        super.finished(outcome)
    }

    /**
     * Step 7 of section 7, and the answer to the "picker over an application" defect of acceptance
     * v17: the whole scene is read once more, from the car, and has to be exactly the scene the
     * recipe just proved.
     *
     * The reveal path already ends in this very read - `revealOwnedSession` refuses unless what came
     * back equals what it raised - so only a build pays for it. A pane that lost its app between its
     * own postcondition and this read makes the operation roll back rather than commit `APP` for
     * something that is no longer there (invariant 9).
     */
    override fun readBack(
        op: SplitOperationContext,
        shell: (String) -> String,
        plan: SplitOpenPlan,
    ): Boolean {
        if (plan.adopted != null) return true
        work.dropSharedReads()
        val settled = runCatching {
            work.split(op).existingOwnedSession(SPLIT_PICKER_COMPONENT_SET)
        }.getOrNull() ?: return false
        mark(op, "read-back")
        return settled == liveScene
    }
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
        val preexisting = preexistingTaskIds(split)
        pointOfNoReturn(op, "selecting an app clears the tasks above its picker")
        // Contract 1.5.7: the recipe has already put the pane back on its picker and kept the
        // neighbour out of it, so a launch that did not happen needs nothing more from the product
        // than to stop - the catalogue is on screen and the next tap works (U5).
        val settled = split.selectApp(
            pickerTaskId = pickerTaskId,
            target = target,
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
        )
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
        work.dropSharedReads()
        runCatching { work.split(op).existingOwnedSession(SPLIT_PICKER_COMPONENT_SET) }
            .getOrNull()
            ?.let { settled ->
                liveScene = settled
                settle(SplitFact.BuildSceneSucceeded(sceneSlots(settled)))
            }
        settleOccupant(chosen.pane, chosen.packageName)
        return true
    }
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
    /**
     * Правка W2 (волна 7): как подать одну уборочную сверку после подтверждённого накрытия.
     * Сабмит HomeOperation сам снимает взведённые повторы сверки (§4), поэтому подтверждённый
     * Home обязан подать один свой - иначе он хоронит проверку, которую больше некому
     * перевзвести. Правка W2 волны 8 (Ф2): канал подачи - отложенный повтор правки W3, не
     * мгновенный сабмит: сверка не читает мир в зубы двухпроходного teardown, коалесцируется и
     * вытесняется пользовательским вводом.
     */
    private val requestCleanup: () -> Unit = {},
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
        val split = work.split(op)
        // The recipe reads the lease before it reads the car: an unowned gate costs no command.
        val suspended = split.suspendOwnedGateForHome(displaced = work.userInputWaiting)
        if (suspended) {
            settle(SplitFact.HomeConfirmed)
            requestCoveredSceneRecheck()
            return
        }
        // Правка W5 (1.9.3, U5): gate наш, а закрыть его не вышло - это не молчание. Втягивание
        // следующего запуска в широкую панель при открытом gate - нативная механика прошивки,
        // и строка ниже - единственный след, по которому её причина читается из ринга.
        if (!work.gateOwned()) return
        work.log(
            if (work.userInputWaiting()) {
                "home suspend displaced by user input: gate остаётся открытым до операции пользователя"
            } else {
                "home suspend unconfirmed: area==0 не подтвердилось за ~3с, gate остался открыт (1.9.3)"
            },
        )
    }

    /**
     * Правка W2 волны 8, расширенная волной 12: подтверждённый Home подаёт ровно одну отложенную
     * сверку накрытого мира - всегда, а не только когда сам успел разглядеть мёртвого члена.
     *
     * Home - единственная подсказка, которая после накрытия приходит гарантированно: hidden-хинт
     * умершего пикера может не прийти вовсе, а дивайдерной подсказки после схлопывания живьём не
     * приходило ни разу за целую серию (протокол 2026-08-25). Пока Home решал сам, читая якорь,
     * он молчал ровно в том случае, ради которого его и звали: приложения пользователя живы,
     * значит «убирать нечего» - а закрытая панель так и оставалась записанной как `App(...)`.
     *
     * Решать - не его дело. Единственный писатель топологических фактов - сверка (§7), и всё,
     * что здесь нужно, это дать ей один взгляд на мир после того, как мир перестал показываться.
     * Канал прежний: один коалесцированный отложенный повтор правки W3, вытесняемый
     * пользовательским вводом, без цепочек и без нового таймера. Заодно исчезло чтение якоря,
     * которое Home платил на каждом накрытии.
     */
    private fun requestCoveredSceneRecheck() {
        if (working.scene == null || liveScene.isEmpty()) return
        work.log("home confirmed: одна отложенная сверка накрытого мира (правка волны 12)")
        requestCleanup()
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
    /** Правка W3: этот запуск - отложенный повтор; отказавший повтор нового не взводит (U1). */
    private val recheck: Boolean = false,
    /** Правка W3: как взвести ровно один отложенный коалесцированный повтор этого же вида. */
    private val armRecheck: (SplitReconcileKind) -> Unit = {},
    /**
     * Правка W8: строка отказа для ринга - с подавлением подряд-дублей на стороне координатора.
     * `null` означает «мир решён»: подавление сбрасывается, и та же причина после решения -
     * снова новость.
     */
    private val reportUnproven: (String?) -> Unit = { line -> line?.let(work::log) },
) : SplitCoreOperation<Boolean>(
    label = SplitCoordinatorCore.RECONCILE_LABEL,
    priority = SplitInputPriority.HINT,
    durationMs = RECONCILE_BUDGET_MS,
    joinKey = null,
    coalesceKey = coalesceKeyOf(kind),
    work = work,
) {
    /**
     * Ветки «не доказано» этой операции, по имени отказавшего предиката (правка W3/W4, U5).
     *
     * Список пуст, когда мир решён: сцена доказана, конец сцены установлен или каждый записанный
     * член проверен живым под накрытием. Непустой список - топология честно недоказуема прямо
     * сейчас (двухпроходный teardown прошивки, диагноз v21 Д1/Д2), и тогда взводится один
     * отложенный повтор.
     */
    private val unproven = mutableListOf<String>()

    /**
     * Одно чтение мира, чья смерть не остаётся тайной (правка волны 13, П2; U5).
     *
     * Живой ринг v28 сказал `по границам корней: НЕ ПРОЧИТАНО` - и это всё, что он мог сказать:
     * `runCatching{}.getOrNull() == null` означает «предикат бросил», но какое именно исключение,
     * не знал никто, и на его поиск ушёл целый цикл приёмки. Наружу пользователю по-прежнему не
     * попадает ничего (U5); внутрь, в ринг, попадает текст исключения - иначе следующая красная
     * ветка снова будет диагностироваться по молчанию.
     */
    private fun <T> probe(refusal: String, read: () -> T): T? =
        runCatching(read).onFailure { error -> unproven += "$refusal ($error)" }.getOrNull()

    /** Тот же отказ для предиката, чей `reason` уже есть: имя причины, либо текст исключения. */
    private fun Result<SplitCollapsedPaneRead>?.refusal(): String =
        this?.fold({ read -> read.reason }, { error -> "не прочитано ($error)" })
            ?: "не спрошено"

    override fun prepare(op: SplitOperationContext, shell: (String) -> String): Boolean {
        if (!working.enabled || work.externalMoveInFlight()) return false
        return working.scene != null || adoptable()
    }

    override fun apply(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
        try {
            applyReconcile(op, shell, plan)
        } catch (cancelled: SplitOperationCancelled) {
            // Правка W2 (волна 7, двойная мёртвая точка перевзвода): отмена вылетает из fence
            // ДО хвостового armRecheck ниже, а сабмит вытеснившей операции уже снял взведённые
            // повторы (cancelReconcileRechecks). Без перевзвода здесь вытесненная уборочная
            // сверка замолкала навсегда. Ровно один отложенный коалесцированный повтор;
            // DISABLE и SHUTDOWN повтора не взводят - им нечего убирать, - и отменённый повтор
            // нового не взводит (U1, никаких цепочек).
            if (
                !recheck &&
                cancelled.reason != SplitCancelReason.DISABLE &&
                cancelled.reason != SplitCancelReason.SHUTDOWN
            ) {
                armRecheck(kind)
            }
            throw cancelled
        } catch (failed: Throwable) {
            // Правка волны 12 (v27 A1): рецепт может не отказать, а УМЕРЕТЬ. Физическая адопция
            // схлопывания возвращает выжившего пикера в его корень и проверяет мир после этого;
            // Home, пришедший в этот момент, роняет проверку - `Split изменился при возврате
            // picker 583` в живом ринге. Ход уже откачен, но исключение вылетало мимо остальных
            // предикатов collapse И мимо взвода повтора: одна операция уносила с собой весь
            // факт закрытия. Умерший рецепт - такая же недоказанная топология, как отказавший,
            // и отвечает ему тот же ровно один коалесцированный повтор (U1, без цепочек).
            if (!recheck) armRecheck(kind)
            throw failed
        }
    }

    private fun applyReconcile(op: SplitOperationContext, shell: (String) -> String, plan: Boolean) {
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
        // first, and each of them proves the scene still exists (1.7.5). A pane-close proof is the
        // one exception: it says nothing about the survivor. The wide-Back ending of 1.6.3 throws
        // the whole scene out of the panel roots at once and may deliver only a single
        // hidden-picker hint (ground-v18 B2), so the remainder is checked by existence anyway - a
        // survivor still living in a panel root passes untouched (1.6.2).
        val ended = if (!proven || kind is SplitReconcileKind.PickerHidden) {
            settleSceneEnded(op, split)
        } else {
            false
        }
        // Правка W3 (диагноз v21 Д1/Д2): после конца перестройки прошивки новых событий нет, и
        // никто не перечитывал мир - отказавшая по недоказуемой топологии сверка молчала вечно.
        // Такая сверка взводит РОВНО ОДИН отложенный коалесцированный повтор своего вида; повтор
        // - обычный HINT, пользовательские операции вытесняют его по §4. Отказавший повтор
        // нового не взводит: никаких цепочек и таймерных циклов (U1).
        if (proven || ended) unproven.clear()
        if (unproven.isNotEmpty()) {
            // Правка W4 (U5): каждая недоказанная сверка оставляет в ринге строку с именами
            // отказавших предикатов - v21 диагностировался на полной тишине этих веток. Правка
            // W8: идентичная причина подряд не повторяется строка-в-строку - шторм одного и
            // того же отказа не заслоняет в ринге ничего нового.
            reportUnproven("reconcile unproven: ${unproven.joinToString("; ")}")
            if (!recheck) armRecheck(kind)
        } else {
            reportUnproven(null)
        }
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
            ?: return ownSceneShowsEveryVisiblePicker(split)
        if (!reconcileScene(op, split, settleResize = false)) return false
        closeRevealedApp(op, split, hostTaskId)
        return true
    }

    /**
     * Правка W3 волны 9 (приёмка v24, Д3): хинт без host-id над СВОЕЙ сценой - не подвешенный мир.
     *
     * Оконное событие пикера приходит без задачи, и её ищет `singleVisiblePickerTaskId` - который
     * требует РОВНО ОДНОГО видимого пикера в панельных корнях. Сразу после сборки сцены
     * «пикер|пикер» видимых пикеров ДВА, и каждое открытие оставляло в ринге одну-две строки
     * «видимый пикер не опознан» со взведённым повтором. Несколько видимых пикеров, и все до
     * одного - записанные члены живой сцены (exact identity), - это доказанное состояние сцены:
     * закрывать нечего (над пикером не стоит приложение), и мир решён.
     *
     * Правка W4 волны 10 (приёмка v25, Д3): волна 9 лечила не тот мир. Строка осталась и приходила
     * ровно по два раза после каждого открытия, приземляющегося в «приложение|приложение», где
     * видимых пикеров НОЛЬ: обе базы живы, но накрыты приложениями пользователя, и разрешение
     * «видимых пикеров два и все свои» к этому миру неприменимо по построению. Сцена, все
     * записанные члены которой живы по exact identity, - доказанное состояние: над пикером стоит
     * приложение, закрывать нечего, мир решён. Второе чтение здесь то же самое, которым ворота
     * конца сцены проверяют живость членов.
     *
     * Прежним «не опознан» остаются: посторонний пикер среди видимых, мёртвый член записанной
     * сцены и нечитаемая машина - тогда хинт действительно ничего не доказывает.
     *
     * @return whether this hint proved the scene. Read-only on every branch.
     */
    private fun ownSceneShowsEveryVisiblePicker(split: SplitPickerShellSession): Boolean {
        val recorded = liveScene.values.mapTo(mutableSetOf(), SplitPickerLivePane::hostTaskId)
        val visible = probe("picker-visible: видимые пикеры нечитаемы") {
            split.visiblePickerTaskIds(SPLIT_PICKER_COMPONENT_SET)
        } ?: return false
        if (recorded.isEmpty() || !recorded.containsAll(visible)) {
            unproven += "picker-visible: видимый пикер не опознан"
            return false
        }
        if (visible.isNotEmpty()) return true
        val membersAlive = probe("picker-visible: живость членов нечитаема") {
            split.allRecordedMembersAlive(liveScene, SPLIT_PICKER_COMPONENT_SET)
        } ?: return false
        if (!membersAlive) {
            unproven += "picker-visible: видимый пикер не опознан"
            return false
        }
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
     * Над накрытой сценой жеста dismiss не бывает (инвариант 5, ред. 2026-08-24): прошивка на
     * Home сама опустошает корень сфокусированной панели, отвязывая живой пикер в display area,
     * и hidden-хинт здесь - эхо Home. Гард стоит до любых мутаций; жива ли накрытая сцена
     * целиком, решает existence-проверка [settleSceneEnded]. В v20 этот хинт принимал
     * Home-накрытие за dismiss и убивал отвязанный пикер - к следующему open ярус выживших
     * был пуст, и возврат шёл полной пересборкой (D1).
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
        if (split.sceneCovered()) return false
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
     * Правка W1 волны 7 (b1-CORE, живой протокол 2026-08-25): конец сцены доказывается двумя
     * чтениями - сцена НАКРЫТА ([SplitPickerShellSession.sceneCovered], area 0/4) И мёртв её
     * ЯКОРЬ по exact identity на всём main display. Правка W1 волны 8 (v23 Д1(а)) сузила якорь:
     * у сцены с записанными приложениями это только сами приложения
     * ([SplitPickerShellSession.allRecordedAppsAlive]) - смерть выселенной пикер-базы при живых
     * приложениях сцену не кончает; у сцены «пикер|пикер» якорь - члены, как в волне 7
     * ([SplitPickerShellSession.allRecordedMembersAlive]). Живая накрытая сцена - ВСЕ члены живы -
     * не убирается никогда (инвариант 5): прошивка на Home опустошает корень панели, отвязывая
     * живые задачи, и отвязанный член живой накрытой сцены - не сирота. Мёртвый член под
     * накрытием - нативный конец: Back в широком пикере при «пикер|пикер», свайп, «очистить всё»
     * (ground-v18 B2).
     *
     * Панельные корни в воротах конца больше не участвуют. Машинная правда волны 7: контейнеры
     * прошивки - вечные объекты, tx118 никогда не отвечает ≤0, а узкий пикер-сирота нативно
     * завершённой сцены НИКОГДА сам не покидает свой панельный root - прежние ворота «записанные
     * задачи ещё в панельных корнях» ждали состояния, которого на этой прошивке не бывает, и
     * уборка не наступала никогда.
     *
     * The automaton then keeps the projected navigator's slot and turns every other `APP` into
     * `PICKER`, which is what makes the next open show fresh pickers instead of resurrecting what
     * the user cleared (1.3.4, invariant 6). Forgetting is not enough, though: the tasks this
     * scene recorded as its own pickers are removed here, by exact id and component, wherever
     * they ended up - including a panel root they never left (1.6, 1.6.4).
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
        val covered = probe("конец сцены: area нечитаема") { split.sceneCovered() }
            ?: return false
        // Нечитаемая машина: не доказано - не убираем.
        val membersAlive = probe("конец сцены: живость членов нечитаема") {
            split.allRecordedMembersAlive(liveScene, SPLIT_PICKER_COMPONENT_SET)
        } ?: return false
        if (membersAlive) {
            if (covered) {
                // Полное позитивное доказательство: каждый член жив под накрытием. Более ранние
                // отказы этой операции - следствие накрытия, а не подвешенного мира (правка W3).
                unproven.clear()
            }
            return false
        }
        // Правка W1 волны 8 (диагноз v23 Д1(а)): у сцены с записанными ПРИЛОЖЕНИЯМИ якорь конца -
        // только они. Выселенная Home-ом пикер-база умирает недетерминированно (механизм М2) при
        // живых приложениях пользователя, и волна 7 засчитывала её смерть концом: слоты → P|P,
        // пара забыта, возврат давал «пикер|пикер» без сообщения (против 1.9.4/1.3.2). Смерть
        // базы при живых приложениях - «база утрачена», НЕ конец: сцена и слоты живут, базу
        // пересоздаст следующий open/reveal (buildScene это уже умеет), и огрызки живой сцены
        // не трогаются. Сцена «пикер|пикер» (приложений нет) кончается смертью баз, как в
        // волне 7, - лечение блокера b1-CORE («очистить всё» из пикеров) сохранено буква в
        // букву. Осознанная асимметрия: смерть записанного ПРИЛОЖЕНИЯ под накрытием остаётся
        // концом, как и прежде (консервативно; «Clear all» этим живёт).
        val appsAnchor = liveScene.values.any { observed -> observed.appTaskId != null }
        if (appsAnchor) {
            val appsAlive = probe("конец сцены: живость приложений нечитаема") {
                split.allRecordedAppsAlive(liveScene)
            } ?: return false
            if (appsAlive) {
                unproven += "конец сцены: база утрачена, записанные приложения живы - не конец"
                return false
            }
        }
        if (!covered) {
            // Мёртвый якорь при видимой или переходной area (1/2/3): collapse и APP→PICKER
            // говорят раньше, а недоказуемый остаток перечитает отложенный повтор.
            unproven += "конец сцены: член мёртв, но сцена не накрыта"
            return false
        }
        // Правка W2 волны 8: позитивная ветка - и только она - подтверждается вторым чтением
        // через короткую паузу: смерть якоря, увиденная в зубы двухпроходного teardown прошивки,
        // может быть его полутактом. Жизнь и нечитаемость выше второго чтения не платят. Строка
        // лога встаёт в ринг ДО второго чтения - это шов между двумя тактами доказательства.
        work.log("scene end: якорь мёртв, подтверждаю вторым чтением (правка W2 волны 8)")
        val confirmed = probe("конец сцены: второе чтение нечитаемо") {
            split.confirmSceneEndAnchorDead(liveScene, SPLIT_PICKER_COMPONENT_SET, appsAnchor)
        } ?: return false
        if (!confirmed) {
            unproven += "конец сцены: смерть якоря не подтвердилась вторым чтением"
            return false
        }

        // Правка W2: конец доказан, дальше - мутации. Уборочные runCatching ниже терпят капризы
        // машины, но отмена - не каприз: raw fence выносит её сюда целой, до полу-исполненного
        // конца, и catch в [apply] перевзводит повтор.
        op.fence()
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
            ).also { repaired -> if (repaired == null) unproven += "resize: мир не сведён" }
        } else {
            val read = split.readOwnedSession(SPLIT_PICKER_COMPONENT_SET)
            if (read.scene == null) unproven += "сцена: ${read.reason}"
            read.scene
        }
        if (settled != null) {
            liveScene = settled
            settle(SplitFact.BuildSceneSucceeded(sceneSlots(settled)))
            return true
        }
        val expected = SplitCoordinatorCore.collapseExpectation(previous)
        if (expected == null) {
            unproven += "collapse: сцена не записана"
            return false
        }
        val physical = split.readCollapsedSession(
            pickerComponents = SPLIT_PICKER_COMPONENT_SET,
            expectedPanes = expected,
        )
        val collapsed = physical.pane
        if (collapsed != null) {
            adoptCollapse(op, split, collapsed, previous)
            return true
        }
        return settleCollapseByExistence(op, split, previous, expected, physical.reason)
    }

    /**
     * Правка W1 (1.8.2, диагноз v21 Д1): факт схлопывания доказывается существованием.
     *
     * Полный постусловный набор физической адопции выше остаётся воротами reattach выжившего
     * пикера, но во время двухпроходного teardown прошивки он честно недоказуем - и в v21 слот
     * схлопнутой панели не чистился вовсе, а «воскрешение Музыки» было честным восстановлением
     * незачищенного слота. Панель, чьи записанные задачи (host И app, по exact identity)
     * отсутствуют в обоих панельных root при area 1/2, схлопнута: её слот закрывается, огрызок
     * её - и только её - пикера убирается по точной identity, где бы он ни оказался. Таск
     * приложения пользователя не трогается: прошивка отвязала его живым (1.8.2), и он остаётся
     * жить в фоне, как при 1.2.3.
     *
     * Правка волны 12: у существования есть цена - оно спрашивает `area`, а её ответ 1/2 живёт
     * меньше секунды. Поэтому за ним стоит третий предикат, читающий геометрию панельных корней
     * ([SplitPickerShellSession.collapsedPaneByPanelBounds]): растянутый контейнер выжившего
     * переживает и Home, и чужое полноэкранное окно, так что решение пользователя больше не
     * зависит от того, успел ли продукт посмотреть в нужную миллисекунду.
     */
    private fun settleCollapseByExistence(
        op: SplitOperationContext,
        split: SplitPickerShellSession,
        previous: SplitLiveScene,
        expected: Map<SplitPane, SplitPickerObservedPane>,
        physicalRefusal: String,
    ): Boolean {
        val byExistence = runCatching {
            split.readCollapsedPaneByExistence(SPLIT_PICKER_COMPONENT_SET, expected)
        }
        // Правка волны 12: третье слово - геометрия панельных корней, единственное, которое
        // накрытие не отнимает. Оба предиката выше читают `area` и при 0/4 слепы, а окно area
        // 1/2 живьём короче секунды; спрашивается оно последним, потому что первые два называют
        // ещё и состав выжившей панели, а это доказательство - только имя закрытой.
        val byBounds = if (byExistence.getOrNull()?.collapsed != null) {
            null
        } else {
            runCatching {
                split.collapsedPaneByPanelBounds(SPLIT_PICKER_COMPONENT_SET, expected)
            }
        }
        val collapsed = byExistence.getOrNull()?.collapsed ?: byBounds?.getOrNull()?.collapsed
        if (collapsed == null) {
            // Правка W4 (U5): все ветви collapse отказали - одна строка называет каждый предикат.
            unproven += "collapse: $physicalRefusal" +
                "; по существованию: ${byExistence.refusal()}" +
                "; по границам корней: ${byBounds.refusal()}"
            return false
        }
        val survivor = collapsed.other()
        pointOfNoReturn(op, "the collapse closed $collapsed for good; its app stays alive")
        previous[collapsed]?.let { pane -> removeCollapsedPicker(op, split, pane.hostTaskId) }
        liveScene = liveScene - collapsed
        settle(SplitFact.PaneCollapsedSettled(survivor))
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
        // Правка W1 (1.8.2): приложения обеих панелей - собственность пользователя, и прошивка
        // на схлопывании их не закрывает («Release to close» отвязывает задачу живой). Продукт
        // убирает только огрызок собственного пикера закрытой панели; убийство записанных app
        // здесь v21 наблюдал как смерть музыки при живой задаче - удалено.
        val closed = previous[previousOwner.other()]
        pointOfNoReturn(op, "the collapse closed the peer of $survivor for good")
        closed?.let { pane -> removeCollapsedPicker(op, split, pane.hostTaskId) }
        liveScene = mapOf(survivor to collapsed)
        settle(SplitFact.PaneCollapsedSettled(survivor))
        settleOccupant(survivor, collapsed.appPackageName)
    }

    internal companion object {
        /**
         * Every passive topology hint collapses to one queued reconcile (section 7, K5), and the
         * deferred re-check of правка W3 coalesces by the very same key: one pending repeat per
         * kind, however many refusals armed it.
         */
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
