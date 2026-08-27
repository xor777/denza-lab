package dev.denza.apps.feature.split

import dev.denza.apps.TaskMoveOwner
import dev.denza.apps.TaskMoveOwnership
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

/**
 * What the transport spent, in the only division that leads to a different fix (правка Ф1 волны 16).
 *
 * A round trip on the return path cost 89 ms while the command itself cost 8-18 ms on the car and
 * the wrapper 1 ms, and nothing said where the rest went. Queuing behind another speaker, handing
 * the bytes over, and waiting for the car are three different defects with three different cures.
 */
internal data class SplitShellSpend(
    val calls: Long = 0,
    val queuedMs: Long = 0,
    val sentMs: Long = 0,
    val answeredMs: Long = 0,
)

/** One persistent shell for one operation. Closed by the operation lifecycle, never left open. */
internal interface SplitShellHandle : AutoCloseable {
    fun shell(command: String): String

    /** Everything spent since the last call; zero from a transport that cannot say. */
    fun drainSpend(): SplitShellSpend = SplitShellSpend()

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

        override fun drainSpend(): SplitShellSpend =
            synchronized(lock) { transport }?.drainSpend() ?: SplitShellSpend()

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

/**
 * What an explicit action of the user ended as, in the only terms a surface may act on (U5).
 *
 * The product has no channel for telling the user that something inside it failed: after a tap the
 * screen is either a scene with applications or a pane on a picker that is ready to be used, and
 * neither of those needs a sentence. What a surface still has to know is the one thing it can do
 * something about - the control channel being dead, which nothing about split can work without and
 * which is repaired on the hub's own screen (1.11.4). Everything else settles as [SETTLED] and is
 * one line of the diagnostic ring.
 */
internal enum class SplitActionResult {
    SETTLED,
    CHANNEL_UNAVAILABLE,
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
    fun log(message: String, background: Boolean)
}

/**
 * A line of an operation, which is what the ring exists for; the background lanes say so instead.
 *
 * Правка Ф3 волны 16: background work writes whenever it looks at the car, and it must not be
 * able to push the lines of the operation somebody is reading the ring for off the screen.
 */
internal fun SplitDiagnosticLog.log(message: String) = log(message, background = false)

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
    private val catalog: SplitLaunchCatalog,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val leases: List<SplitLeaseController>,
    private val apkPath: String,
    private val proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
    /** The one shell-UID helper of the process, or `null` where there is none to lease. */
    private val resident: SplitResidentProxy? = null,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val log: SplitDiagnosticLog = SplitDiagnosticLog { _, _ -> },
    private val post: (() -> Unit) -> Unit = { action -> action() },
    /** Общее на процесс право двигать задачи; см. [TaskMoveOwnership]. */
    private val ownership: TaskMoveOwnership = TaskMoveOwnership.shared,
) {
    private val stateLock = Any()
    private val recheckLock = Any()
    private val residentLock = Any()

    private var state = SplitState()
    private var live: SplitLiveScene = emptyMap()
    private var residentRelease: SplitCancellable? = null

    /**
     * Panes whose remembered application a refused open left standing on a bare picker (1.3.5).
     *
     * Contract 1.3.5 calls that screen an unfinished restore rather than an open scene, and this is
     * the only thing that tells it apart from a pane the user emptied themselves: the world looks
     * identical either way, and the difference is that this very process knows it just failed to
     * stand the app up. It is ephemeral by construction (invariant 4) - a process death loses it,
     * and then the panes are simply what the car says they are.
     */
    private var unfinished: Map<SplitPane, String> = emptyMap()
    private var busy: Busy? = null
    private var openTicket: SplitTicket? = null
    private var loaded = false

    /** Правка W3: не более одного отложенного повтора сверки на coalesce-ключ. */
    private val pendingRechecks = mutableMapOf<Any, SplitCancellable>()

    /** Правка W8: последняя причина unproven; идентичная подряд пишется в ринг один раз. */
    private val lastReconcileUnproven = AtomicReference<String?>(null)

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
    fun openPickerSession(onComplete: (SplitActionResult) -> Unit = {}) {
        ready()
        // The launcher entry only exists while the toggle is on, so a tap on it is also the
        // authoritative repair of a persisted mismatch. It is a store write and nothing else.
        if (!currentState().enabled) submitEnable()
        val joined = synchronized(stateLock) { openTicket?.takeUnless(SplitTicket::isComplete) }
        if (joined != null) {
            joined.onComplete { outcome -> report(onComplete, outcome) }
            return
        }
        val overlay = overlayOwner.begin()
        markBusy(OPEN_LABEL, enabled = true, message = "Открываю разделение экрана")
        val ticket = submit { work -> OpenOperation(work) }
        synchronized(stateLock) { openTicket = ticket }
        ticket.onComplete { outcome ->
            if (outcome is SplitOutcome.Committed) overlay.close() else overlay.closeImmediately()
            report(onComplete, outcome)
        }
    }

    /** Contract 1.5: a picker tap names its pane, so no foreground inference is ever needed. */
    fun selectApp(
        pickerTaskId: Int,
        packageName: String,
        onComplete: (SplitActionResult) -> Unit = {},
    ) {
        ready()
        val target = catalog.resolve(packageName)
        if (target == null) {
            // 1.5.6: the package went away between the frame the user tapped and this read. The
            // pane keeps its picker, whose own catalogue has already dropped the tile, and the tap
            // is simply over - there is nothing left to open and nothing worth a sentence (U5).
            log.log("select refused: $packageName больше не установлен")
            post { onComplete(SplitActionResult.SETTLED) }
            return
        }
        submit { work -> SelectOperation(work, pickerTaskId, target) }
            .onComplete { outcome -> report(onComplete, outcome) }
    }

    /** Contract 1.6.2, 1.7.1: a revealed picker is a hint that its app may have closed. */
    fun pickerVisible(hostTaskId: Int?) {
        ready()
        submitReconcile(SplitReconcileKind.PickerVisible(hostTaskId))
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
        // обычный HINT, который прочтёт мир заново и решит его правилами W1. Правка W2 волны 8
        // (Ф2): подаёт он её ОТЛОЖЕННЫМ каналом правки W3, а не мгновенным сабмитом, читавшим
        // мир в зубы двухпроходного teardown прошивки. Взвод происходит внутри операции - после
        // submit-времени самого Home, так что сам себя он не снимает; повтор коалесцирован и
        // вытесняется пользовательским вводом, как любой другой. Ни одного нового таймерного
        // цикла: канал тот же.
        submit { work ->
            HomeOperation(work) { armReconcileRecheck(SplitReconcileKind.DividerResized) }
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

    /**
     * Двигает ли задачи кто-то другой прямо сейчас.
     *
     * Раньше здесь жили два собственных поля - пятисекундное окно и долгий hold, - и оба умирали
     * вместе с ядром. Теперь это один вопрос к общему на процесс владению ([TaskMoveOwnership]),
     * который переживает пересоздание координатора и знает не только «занято», но и кем.
     */
    fun externalTaskMutationInFlight(): Boolean = ownership.heldByOther(TaskMoveOwner.SPLIT)

    // endregion

    /** The worker is joined first, so nothing is still holding the transport when it closes. */
    fun shutdown() {
        cancelReconcileRechecks()
        disarmResidentRelease()
        actor.shutdown()
        (shellFactory as? AutoCloseable)?.let { closeable -> runCatching(closeable::close) }
        // Ф4: the helper on the car is a process of ours, and it ends with us. Its channel is the
        // ADB stream it runs on, so closing that is what kills it - nothing is left to reap.
        resident?.let { helper -> runCatching(helper::close) }
    }

    // region internals

    internal fun currentState(): SplitState = synchronized(stateLock) { state }

    internal fun currentLive(): SplitLiveScene = synchronized(stateLock) { live }

    internal fun currentUnfinished(): Map<SplitPane, String> =
        synchronized(stateLock) { unfinished }

    /** Recorded by an open that refused with its bases already standing (invariant 9, 1.3.5). */
    private fun markUnfinished(panes: Map<SplitPane, String>) {
        if (panes.isEmpty()) return
        synchronized(stateLock) { unfinished = unfinished + panes }
        log.log("unfinished restore left standing: ${panes.values.joinToString(", ")}")
    }

    private fun submitEnable(): SplitTicket = submit { work -> EnableOperation(work) }

    private fun submitReconcile(kind: SplitReconcileKind, recheck: Boolean = false): SplitTicket =
        submit { work ->
            ReconcileOperation(work, kind, recheck, ::armReconcileRecheck, ::reportReconcileUnproven)
        }

    /**
     * Правка W8: ринг не спамится штормом одного и того же отказа. Идентичная причина unproven
     * подряд пишется один раз; решённый мир (`null`) сбрасывает подавление, и та же причина
     * после решения - снова новость.
     */
    private fun reportReconcileUnproven(line: String?) {
        if (line == null) {
            lastReconcileUnproven.set(null)
            return
        }
        if (lastReconcileUnproven.getAndSet(line) != line) log.log(line)
    }

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
        // Which lane this operation's lines belong to is known only once it is built, and every
        // line it writes is written later, on the worker - so the lane is resolved before the
        // actor can reach it and read from there (правка Ф3 волны 16).
        val backgroundLane = BooleanArray(1)
        val workspace = SplitOperationWorkspace(
            shellFactory = shellFactory,
            store = store,
            catalog = catalog,
            leases = leases,
            gateLeaseStore = gateLeaseStore,
            apkPath = apkPath,
            proxyClasspath = proxyClasspath,
            resident = resident,
            clock = clock,
            sleeper = sleeper,
            diagnostics = { line, background -> log.log(line, background || backgroundLane[0]) },
            readState = ::currentState,
            readLive = ::currentLive,
            readUnfinished = ::currentUnfinished,
            markUnfinished = ::markUnfinished,
            externalMoveInFlight = ::externalTaskMutationInFlight,
            userInputWaiting = actor::userInputWaiting,
            publisher = ::publishSettled,
        )
        val operation = factory(workspace)
        backgroundLane[0] = operation.label in BACKGROUND_LABELS
        // Ф4: work is starting, so the idle release the last operation armed is not due after all.
        disarmResidentRelease()
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
    private fun publishSettled(next: SplitState, nextLive: SplitLiveScene) {
        synchronized(stateLock) {
            state = next
            live = if (next.scene == null) emptyMap() else nextLive
            // A restore stops being unfinished the moment a settled scene says so: the pane got
            // its application, the user chose something else there, the package went away, or the
            // pane is gone. Without a live scene nothing has been proven about these panes at all,
            // so the note simply waits (1.3.5).
            if (next.scene != null) {
                unfinished = unfinished.filter { (pane, packageName) ->
                    next.slot(pane) == SplitSlot.App(packageName) &&
                        nextLive[pane]?.appPackageName == null
                }
            }
        }
    }

    private fun finishOperation(label: String, outcome: SplitOutcome, elapsedMs: Long) {
        // 1.10.7: a navigation return that failed is navigation's error, on navigation's surface.
        // It must not repaint the split panel as broken, and it never owned a busy label there.
        if (label in NAV_LABELS) {
            publish()
            return
        }
        // U5: the product does not report its own failures to the user. Nobody asked for a
        // reconciliation, an edge hint, a Home teardown or an uninstall sweep, so a failed one is
        // named as background work in the ring and nothing else.
        val quiet = label in BACKGROUND_LABELS
        val reason = reasonOf(outcome)
        if (quiet && reason != null) {
            log.log("background $label failed quietly: $reason", background = true)
        }
        // U5, U6: an action the user performed never ends in silence *in the ring*. Whatever
        // became of it - committed, cancelled, rolled back, refused - exactly one line says so, so
        // that a tap which produced no visible change is still explainable afterwards. What the
        // user gets is the screen the operation left standing, never this line.
        if (label in USER_LABELS) log.log(terminalOf(label, outcome, elapsedMs))
        synchronized(stateLock) {
            busy = busy?.takeIf { it.label != label }
            if (label == OPEN_LABEL) openTicket = null
        }
        armResidentRelease()
        publish()
    }

    /**
     * Ф4: the helper is about 60 MB of PSS on this car, so it may not outlive the work by much.
     *
     * The window is deliberately generous next to an operation's own budget: a user tapping
     * through a scene must never pay to stand a new one up between two of their own taps.
     */
    private fun armResidentRelease() {
        val helper = resident ?: return
        val armed = clock.schedule(RESIDENT_IDLE_MS) { helper.releaseIfUnused() }
        synchronized(residentLock) {
            residentRelease?.cancel()
            residentRelease = armed
        }
    }

    private fun disarmResidentRelease() {
        synchronized(residentLock) {
            residentRelease?.cancel()
            residentRelease = null
        }
    }

    private fun markBusy(label: String, enabled: Boolean, message: String) {
        synchronized(stateLock) { busy = Busy(label, enabled, message) }
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
        return SplitScreenSession(enabled = true, phase = SplitScreenPhase.ACTIVE)
    }

    private fun report(callback: (SplitActionResult) -> Unit, outcome: SplitOutcome) {
        val result = if (isChannelUnavailable(reasonOf(outcome))) {
            SplitActionResult.CHANNEL_UNAVAILABLE
        } else {
            SplitActionResult.SETTLED
        }
        post { callback(result) }
    }

    private data class Busy(val label: String, val enabled: Boolean, val message: String)

    internal companion object {
        /** Ф4: how long the shell-UID helper may stand about with no operation needing it. */
        const val RESIDENT_IDLE_MS = 30_000L

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

        /**
         * Why a navigation return threw, for navigation's own surface and log (1.10.7).
         *
         * It is not a split message: the split panel is never repainted as broken, and the pane
         * the navigator left keeps its working picker. Navigation owns what it says about its own
         * retry, and this is the reason string it is handed.
         */
        const val NAV_FAILURE = "Не удалось вернуть навигацию в окно"
        const val NAV_PLAN_UNAVAILABLE = "$NAV_FAILURE: возврат уже выполняется"

        fun reasonOf(outcome: SplitOutcome): String? = when (outcome) {
            is SplitOutcome.RolledBack -> outcome.reason
            is SplitOutcome.Failed -> outcome.message
            else -> null
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

        /**
         * Whether a failure means the control channel itself is not usable (1.11.4).
         *
         * The four shapes below are what this car answers with when the local ADB link is not
         * there: an unauthorised key, a key whose confirmation is still pending, a refused
         * connection and a link that stopped answering. None of them is a defect of the recipe
         * that met it - every recipe fails the same way - and none of them can be repaired from a
         * pane. They are the one failure a surface is allowed to act on, by opening the screen
         * that repairs the channel; everything else is diagnostics.
         */
        fun isChannelUnavailable(raw: String?): Boolean {
            val text = raw.orEmpty()
            return CHANNEL_FAILURE_MARKERS.any { marker ->
                text.contains(marker, ignoreCase = true)
            }
        }

        private val CHANNEL_FAILURE_MARKERS = listOf(
            "authorization required",
            "authorization pending",
            "refused",
            "timeout",
        )
    }

    // endregion
}
