package dev.denza.apps.feature.split

import java.util.concurrent.atomic.AtomicReference

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

/**
 * One ADB transport for the whole process, leased to one operation at a time.
 *
 * Opening an interactive shell on this car is a full handshake - connect, authenticate, open - and
 * the product used to pay it per operation: a reconcile that sent two reads paid exactly what a
 * whole open paid. So the transport outlives the operation, and what an operation gets is a handle
 * whose `close` releases nothing. Nothing about the fence changes: every command still goes through
 * the operation's own token check before it is sent (contract 7, invariant 10).
 *
 * A command that fails takes the transport down with it. The usual reason one fails is that the
 * link stopped answering, and a cached dead pipe would turn a single broken operation into every
 * later one failing the same way (1.11.4); the next lease performs a fresh handshake instead.
 */
internal class SplitPersistentShell(
    private val connect: SplitShellFactory,
) : SplitShellFactory, AutoCloseable {

    private val lock = Any()
    private var transport: SplitShellHandle? = null

    override fun open(): SplitShellHandle = object : SplitShellHandle {
        override fun shell(command: String): String {
            val live = synchronized(lock) {
                transport ?: connect.open().also { opened -> transport = opened }
            }
            return try {
                live.shell(command)
            } catch (error: Throwable) {
                discard(live)
                throw error
            }
        }

        /** The transport belongs to the process, not to this operation. */
        override fun close() = Unit
    }

    override fun close() {
        val live = synchronized(lock) { transport.also { transport = null } }
        live?.let { runCatching(it::close) }
    }

    private fun discard(dead: SplitShellHandle) {
        val closing = synchronized(lock) {
            transport.takeIf { live -> live === dead }?.also { transport = null }
        }
        closing?.let { runCatching(it::close) }
    }
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

/**
 * The launch catalog, rebuilt from the current package state on every read (1.4.2).
 *
 * It answers one package at a time on purpose: an open needs the two the slots name and nothing
 * else, and asking for the whole launcher list used to wake every launchable process on the car
 * before the first command of the recipe was sent.
 */
internal interface SplitLaunchCatalog {
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

    /** The pair SmartMulti remembers, displaced for as long as a product scene exists (1.12). */
    const val SMART_MULTI = "smart-multi"

    /** What a session borrows for its own lifetime and gives back the moment that scene ends. */
    val SESSION_SCOPED: Set<String> = setOf(SMART_MULTI)

    /**
     * Infrastructure leases: idempotent, invisible to the user, and released by `DISABLE` alone.
     *
     * They are deliberately kept out of the operation journal. Taking [PICKER_ACCESS] re-binds the
     * accessibility service, and giving it back on every failed operation made each attempt undo
     * the observer the next attempt would have to build again - a loop the user sees as a button
     * that does nothing at all. Nothing on screen depends on this lease, so keeping it costs the
     * user nothing; the toggle-off path still gives back every lease the product holds (1.2).
     */
    val ROLLBACK_EXEMPT: Set<String> = setOf(PICKER_ACCESS)
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

/**
 * What a partial restore leaves on screen (1.3.2, U5).
 *
 * It names the apps that did not come back, because the pane the user is looking at is also the
 * place they can repair it: the picker of that pane is right there, with those apps in its list.
 */
internal fun splitRestoreFailureNotice(labels: List<String>): String =
    "Не восстановлено: ${labels.joinToString(", ")}. Выберите приложение вручную"

/**
 * What a navigation return throws when the split side of it did not happen (1.10.7).
 *
 * It is an [IllegalStateException] on purpose: navigation already treats a thrown return as "stay
 * on the cluster, show the error, offer a retry", and that contract does not change here.
 */
internal class SplitNavigationFailure(message: String) : IllegalStateException(message)

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
    private val proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
    /** The human name of a package, for the one surface that names apps to the user (1.3.2). */
    private val appLabel: (String) -> String = { it },
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val log: SplitDiagnosticLog = SplitDiagnosticLog {},
    private val post: (() -> Unit) -> Unit = { action -> action() },
) {
    private val stateLock = Any()
    private val routingLock = Any()
    private val recheckLock = Any()

    private var state = SplitState()
    private var live: SplitLiveScene = emptyMap()
    private var notice: String = ""
    private var busy: Busy? = null
    private var failure: Failure? = null
    private var openTicket: SplitTicket? = null
    private var routingBlockedUntilMs = 0L
    private var externalTaskMovesHeld = false
    private var loaded = false

    /** Правка W3: не более одного отложенного повтора сверки на coalesce-ключ. */
    private val pendingRechecks = mutableMapOf<Any, SplitCancellable>()

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
            joined.onComplete { outcome -> report(onComplete, OPEN_LABEL, outcome) }
            return
        }
        val overlay = overlayOwner.begin()
        markBusy(OPEN_LABEL, enabled = true, message = "Открываю разделение экрана")
        val ticket = submit { work -> OpenOperation(work) }
        synchronized(stateLock) { openTicket = ticket }
        ticket.onComplete { outcome ->
            if (outcome is SplitOutcome.Committed) overlay.close() else overlay.closeImmediately()
            report(onComplete, OPEN_LABEL, outcome)
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
            .onComplete { outcome -> report(onComplete, SELECT_LABEL, outcome) }
    }

    /** Contract 1.6.2, 1.7.1: a revealed picker is a hint that its app may have closed. */
    fun pickerVisible(hostTaskId: Int?, onComplete: (String?) -> Unit = {}) {
        ready()
        submitReconcile(SplitReconcileKind.PickerVisible(hostTaskId))
            .onComplete { outcome -> report(onComplete, RECONCILE_LABEL, outcome) }
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

    /**
     * Contract 1.5.6: an uninstall observed by a picker that is on screen right now.
     *
     * It is a hint like any other and it costs no command; section 6 keeps the lazy rule as the
     * safety net for a removal that happened while nothing of ours was listening.
     */
    fun packageRemoved(packageName: String) {
        ready()
        if (packageName.isBlank()) return
        submit { work -> PackageRemovedOperation(work, packageName) }
    }

    /**
     * Contract 1.9.1: Home is the priority event that cancels the scene work the user walked away
     * from - and it is a *hint* until this guard says otherwise (contract 4.2, invariant 8).
     *
     * Three hints never become a Home event at all. One is the window echo of a launch: the button
     * lives on Home, so the app centre is on screen for a few hundred milliseconds after the tap
     * that asked to leave it - Home there is not news, it is the screen the open started from
     * (1.3.9). The second is any Home hint with nothing of ours to suspend: no scene and no gate we
     * borrowed means there is nothing this operation could do but open a shell session and read.
     * The third is a Home the automaton has already confirmed - the scene is covered, the gate is
     * suspended, and the launcher keeps emitting window events for as long as it is on screen.
     * Each of those used to submit a fresh `HomeOperation`, and submitting one cancels every
     * queued and in-flight passive hint (section 4) - including the `PICKER_HIDDEN` cleanup that
     * contract 1.6.3 requires to run exactly there, because the wide-Back ending lands *on* Home
     * (ground-v18 B2). A cancelled background operation reports nothing, which is why the product
     * log was silent while the orphan picker and the dirty SmartMulti keys stayed behind.
     */
    fun homeVisible() {
        ready()
        val openInFlight = synchronized(stateLock) { openTicket?.isComplete == false }
        if (openInFlight) {
            log.log("home hint dropped: a user-requested open is running (contract 4.2)")
            return
        }
        val state = currentState()
        if (state.scene == null && !gateLeaseStore.isOwned()) {
            log.log("home hint dropped: no live scene and no gate lease of ours (invariant 8)")
            return
        }
        if (state.scene != null && state.visibility == SceneVisibility.COVERED) {
            log.log("home hint dropped: the scene is already covered (invariant 8)")
            return
        }
        // Правка W2 (волна 7): сабмит Home снимает взведённые повторы сверки ниже, поэтому
        // подтверждённый Home с мёртвым членом обязан сам подать одну уборочную сверку -
        // обычный HINT, который прочтёт мир заново и решит его правилами W1.
        submit { work ->
            HomeOperation(work) { submitReconcile(SplitReconcileKind.DividerResized) }
        }
    }

    /** Contract 1.8.5: the stock picker of an edge drag, only inside a live product scene. */
    fun nativePickerVisible(): Boolean {
        ready()
        submit { work -> EdgeOperation(work) }
        return true
    }

    // endregion

    // region navigation (contract 1.10, priority NAV)

    /**
     * Contract 1.10.1. A projection notice is a fact like any other: it is settled on the actor
     * worker, never on the navigation thread that observed it (invariant 12).
     */
    fun projectionStarted(taskId: Int) {
        ready()
        submit { work -> NavProjectionStartedOperation(work, taskId) }
    }

    /** Contract 1.10.3: the navigator came back by itself; the projection axis is spent. */
    @Suppress("UNUSED_PARAMETER")
    fun projectionReturned(taskId: Int) {
        ready()
        submit { work -> NavProjectionReturnedOperation(work) }
    }

    /**
     * Contract 1.10.3-1.10.6, first half of a return.
     *
     * Navigation holds its routing lease across the whole move and therefore keeps a synchronous
     * contract, but the work itself is an ordinary `NAV` operation: it overtakes a queued selection
     * or open and it is fenced, journalled and deadlined like everything else.
     */
    fun prepareNavigationReturn(originalRootTaskId: Int): SplitNavigationReturnPlan {
        ready()
        val prepared = AtomicReference<SplitNavigationReturnPlan?>()
        val ticket = submit { work -> NavPrepareOperation(work, originalRootTaskId, prepared) }
        awaitNavigation(ticket, NAV_PREPARE_BUDGET_MS)
        return prepared.get() ?: throw SplitNavigationFailure(NAV_PLAN_UNAVAILABLE)
    }

    /** Contract 1.10.3-1.10.6, second half: the return is verified, then settled. */
    fun completeNavigationReturn(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
    ) {
        ready()
        val ticket = submit { work -> NavCompleteOperation(work, plan, taskId, packageName) }
        awaitNavigation(ticket, NAV_COMPLETE_BUDGET_MS)
    }

    /**
     * Turns one actor outcome back into the failure contract navigation already had (1.10.7): a
     * return that did not happen throws, so the navigator stays on the cluster and the error is
     * shown and retried by navigation's own surface, not by the split panel.
     *
     * The wait is the operation's own budget plus a margin, because the budget starts at submit
     * time and the deadline settles the ticket by itself.
     */
    private fun awaitNavigation(ticket: SplitTicket, budgetMs: Long) {
        val outcome = ticket.await(budgetMs + NAV_AWAIT_MARGIN_MS)
            ?: throw SplitNavigationFailure("$NAV_FAILURE: операция не завершилась")
        when (outcome) {
            is SplitOutcome.Committed -> Unit
            is SplitOutcome.Cancelled ->
                throw SplitNavigationFailure("$NAV_FAILURE: ${outcome.reason}")
            is SplitOutcome.RolledBack -> throw SplitNavigationFailure(outcome.reason)
            is SplitOutcome.Failed -> throw SplitNavigationFailure(outcome.message)
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

    /** The worker is joined first, so nothing is still holding the transport when it closes. */
    fun shutdown() {
        cancelReconcileRechecks()
        actor.shutdown()
        (shellFactory as? AutoCloseable)?.let { closeable -> runCatching(closeable::close) }
    }

    // region internals

    internal fun currentState(): SplitState = synchronized(stateLock) { state }

    internal fun currentLive(): SplitLiveScene = synchronized(stateLock) { live }

    private fun submitEnable(): SplitTicket = submit { work -> EnableOperation(work) }

    private fun submitReconcile(kind: SplitReconcileKind, recheck: Boolean = false): SplitTicket =
        submit { work -> ReconcileOperation(work, kind, recheck, ::armReconcileRecheck) }

    /**
     * Правка W3 (диагноз v21 Д1/Д2): сверка, отказавшая из-за недоказуемой топологии, взводит
     * РОВНО ОДИН отложенный повтор своего вида (~2 с), коалесцированный тем же ключом: серия
     * отказов держит один общий таймер. Повтор - обычный HINT: подача явного пользовательского
     * ввода и подтверждённого Home вытесняет его ещё таймером ([submit]), очередную и полётную
     * формы - актор по §4. Отказавший повтор нового не взводит (U1, `recheck` в
     * [ReconcileOperation]) - никаких таймерных циклов.
     */
    private fun armReconcileRecheck(kind: SplitReconcileKind) {
        synchronized(recheckLock) {
            val key = ReconcileOperation.coalesceKeyOf(kind)
            if (pendingRechecks.containsKey(key)) return
            pendingRechecks[key] = clock.schedule(RECONCILE_RECHECK_DELAY_MS) {
                synchronized(recheckLock) { pendingRechecks.remove(key) }
                submitReconcile(kind, recheck = true)
            }
        }
    }

    private fun cancelReconcileRechecks() {
        val cancelled = synchronized(recheckLock) {
            pendingRechecks.values.toList().also { pendingRechecks.clear() }
        }
        cancelled.forEach(SplitCancellable::cancel)
    }

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
            proxyClasspath = proxyClasspath,
            appLabel = appLabel,
            clock = clock,
            sleeper = sleeper,
            diagnostics = log,
            readState = ::currentState,
            readLive = ::currentLive,
            externalMoveInFlight = ::externalTaskMutationInFlight,
            userInputWaiting = actor::userInputWaiting,
            publisher = ::publishSettled,
        )
        val operation = factory(workspace)
        // Правка W3, §4: явный ввод пользователя и подтверждённый Home вытесняют отложенный
        // повтор сверки так же, как актор вытесняет его очередную и полётную формы - таймер
        // есть та же HINT-работа, просто ещё не поданная.
        if (operation.priority in RECHECK_DISPLACING_PRIORITIES) cancelReconcileRechecks()
        val requestedAtMs = clock.nowMs()
        val ticket = actor.submit(SplitOperationLifecycle(operation, workspace))
        ticket.onComplete { outcome ->
            finishOperation(operation.label, outcome, clock.nowMs() - requestedAtMs)
        }
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

    private fun finishOperation(label: String, outcome: SplitOutcome, elapsedMs: Long) {
        // 1.10.7: a navigation return that failed is navigation's error, on navigation's surface.
        // It must not repaint the split panel as broken, and it never owned a busy label there.
        if (label in NAV_LABELS) {
            publish()
            return
        }
        // U5: an error is shown where the user acted. Nobody asked for a reconciliation, an edge
        // hint, a Home teardown or an uninstall sweep, so a failed one paints no error card and
        // publishes no notice - it becomes one line of the diagnostic log and nothing else.
        val quiet = label in BACKGROUND_LABELS
        val reason = when (outcome) {
            is SplitOutcome.RolledBack -> outcome.reason
            is SplitOutcome.Failed -> outcome.message
            else -> null
        }
        if (quiet && reason != null) log.log("background $label failed quietly: $reason")
        // U5, U6: an action the user performed never ends in silence. Whatever became of it -
        // committed, cancelled, rolled back, refused - exactly one line says so, so that a tap
        // which produced no visible change is still explainable afterwards.
        if (label in USER_LABELS) log.log(terminalOf(label, outcome, elapsedMs))
        synchronized(stateLock) {
            busy = busy?.takeIf { it.label != label }
            failure = when {
                outcome is SplitOutcome.Committed -> null
                // A cancellation is what the user asked for, and a quiet failure is nobody's error:
                // both leave whatever the panel already showed exactly as it was (1.3.8, U5).
                reason == null || quiet -> failure
                else -> Failure(friendlyError(reason, fallbackOf(label)), reason)
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

    private fun report(callback: (String?) -> Unit, label: String, outcome: SplitOutcome) {
        post { callback(errorOf(label, outcome)) }
    }

    /**
     * A user who turned the toggle off got exactly what they asked for, and a Home over scene work
     * is answered by the screen itself (1.5.8); everything else that ends an action the user
     * performed is worth a message (1.3.8, U5).
     */
    private fun errorOf(label: String, outcome: SplitOutcome): String? {
        val fallback = fallbackOf(label)
        return when (outcome) {
            is SplitOutcome.Committed -> null
            is SplitOutcome.Cancelled -> when (outcome.reason) {
                SplitCancelReason.DEADLINE ->
                    "$fallback: превышено время ожидания. Попробуйте ещё раз"
                // The user asked for both of these, and the process going away is nobody's error.
                SplitCancelReason.DISABLE, SplitCancelReason.SHUTDOWN -> null
                // Unreachable for an open since contract 4.2: only its own deadline or the toggle
                // may end it. Should one ever get through again, the user reads an error instead
                // of watching a tap do nothing at all - silence is the one outcome U5 forbids.
                else -> if (label == OPEN_LABEL) fallback else null
            }
            is SplitOutcome.RolledBack -> friendlyError(outcome.reason, fallback)
            is SplitOutcome.Failed -> friendlyError(outcome.message, fallback)
        }
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
        const val PACKAGE_REMOVED_LABEL = "package-removed"
        const val NAV_STARTED_LABEL = "nav-started"
        const val NAV_RETURNED_LABEL = "nav-returned"
        const val NAV_PREPARE_LABEL = "nav-prepare"
        const val NAV_COMPLETE_LABEL = "nav-complete"

        val NAV_LABELS = setOf(
            NAV_STARTED_LABEL,
            NAV_RETURNED_LABEL,
            NAV_PREPARE_LABEL,
            NAV_COMPLETE_LABEL,
        )

        /** Work nobody requested: its failures are diagnostics, never an error card (U5). */
        val BACKGROUND_LABELS = setOf(
            RECONCILE_LABEL,
            EDGE_LABEL,
            HOME_LABEL,
            PACKAGE_REMOVED_LABEL,
        )

        /** Work a person performed: every terminal of it is recorded, whatever it was (U5, U6). */
        val USER_LABELS = setOf(
            ENABLE_LABEL,
            DISABLE_LABEL,
            OPEN_LABEL,
            SELECT_LABEL,
        )

        /**
         * One line, one terminal: what the user asked for, what became of it, why - and how long
         * they waited for it, counted from the tap rather than from the dequeue (1.13).
         */
        fun terminalOf(label: String, outcome: SplitOutcome, elapsedMs: Long): String {
            val (settled, reason) = when (outcome) {
                is SplitOutcome.Committed -> "committed" to null
                is SplitOutcome.Cancelled -> "cancelled" to outcome.reason
                is SplitOutcome.RolledBack -> "rolled-back" to outcome.reason
                is SplitOutcome.Failed -> "failed" to outcome.message
            }
            return "$label outcome=$settled reason=${reason ?: "-"} in ${elapsedMs}ms"
        }

        const val EXTERNAL_TASK_BYPASS_MS = 5_000L

        /**
         * Правка W3: пауза одного отложенного повтора сверки - за неё двухпроходный teardown
         * прошивки успевает доехать до конца (диагноз v21: триггеры опережали его на доли
         * секунды), а пользовательскому вводу она ничего не стоит - повтор вытесняется.
         */
        const val RECONCILE_RECHECK_DELAY_MS = 2_000L

        /** Чей сабмит снимает отложенный повтор сверки: явный ввод и подтверждённый Home (§4). */
        val RECHECK_DISPLACING_PRIORITIES = setOf(
            SplitInputPriority.DISABLE,
            SplitInputPriority.HOME,
            SplitInputPriority.SELECT,
            SplitInputPriority.OPEN,
        )

        /** Slack over the operation budget: the deadline itself settles the ticket first. */
        const val NAV_AWAIT_MARGIN_MS = 5_000L

        const val NAV_FAILURE = "Не удалось вернуть навигацию в окно"
        const val NAV_PLAN_UNAVAILABLE = "$NAV_FAILURE: возврат уже выполняется"

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
                text.contains("timeout", ignoreCase = true) ->
                    "ADB пока не отвечает. Попробуйте ещё раз"
                text.startsWith("Приложение уже открыто на другом экране") -> text
                text.startsWith("Это приложение не поддерживает два окна") -> text
                else -> fallback
            }
        }
    }

    // endregion
}
