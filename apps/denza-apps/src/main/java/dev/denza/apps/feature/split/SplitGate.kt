package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitCoordinatorCore.Companion.isCoveredArea
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_POLL_INTERVAL_MS

/*
 * The firmware's split gate (tx126 `setStartToSplit`) and the lease that records that the product
 * opened it (contract, to 1.12; findings, "Placement, read end to end"). The gate is one
 * firmware-global switch: open, every split-capable start lands in split; closed, even a pane
 * category goes fullscreen. One rule closes it - we opened it, so we close it - and the lease is
 * how that rule outlives the process.
 *
 * Every transition of it is in this file, in three classes because they run in three places:
 * - SplitGate: the transitions of a session, inside an operation on the actor's worker;
 * - SplitGateUndo: the inverse of an opening, replayed from a failed operation's journal;
 * - SplitGateAhead: the core's close ahead of the area on Home, and its undo a second later, on
 *   the signal and timer threads, through the in-process SplitGateSwitch.
 * When each one is asked for stays with its caller: the operations, the journal and the core.
 */

/**
 * Every transition a session makes on the gate.
 *
 * A session opens it for a build, a reveal and a selection, suspends it while a cover hides the
 * scene, resumes it when the covered scene is on screen again, and closes it when the scene ends.
 * Each suspension and resumption is decided by a read area and by the lease, never by an event.
 */
internal class SplitGate(
    private val world: SplitWorld,
    private val gateLeaseStore: SplitGateLeaseStore,
) {
    /**
     * Opens the gate and takes the lease for it, whatever the gate was before: the gate's previous
     * state cannot be read on this firmware (below), so the lease is always taken, and a toggle-off
     * or the end of a scene leaves the gate closed. A lease that cannot be recorded closes the gate
     * again at once and fails the recipe.
     */
    fun ensureGateOpen() {
        // On this DiLink 5.1 build tx123 is `isCanSplit()`: for the BYD platform branch it is
        // a constant capability answer, not the current mIsEnterSplit value. Only tx126 changes
        // the mutable gate, and it is idempotent in the firmware.
        world.callVoid("service call activity_task 126 i32 1")
        if (!gateLeaseStore.setOwned(true)) {
            runCatching { world.callVoid("service call activity_task 126 i32 0") }
            error("Не удалось сохранить владение split-gate")
        }
    }

    /**
     * Suspends the gate this product owns once a read area confirms that Home covered the scene.
     *
     * While the gate is open the firmware pulls the next split-capable start into the wide pane,
     * and DiLink resurrects its remembered pair when either member is launched from Home, so a
     * covered scene needs its gate closed (1.9.2, 1.9.3). The lease stays, so the next explicit
     * open reopens the gate; a gate we did not acquire is never touched.
     *
     * The cover is [SplitWorld.sceneCovered] - area 0 or 4: in a transient world the area flips
     * between the two, and a fullscreen window covers the scene as honestly as Home does (1.11.5).
     * It is polled up to [HOME_CONFIRM_BUDGET_MS], and [displaced] gives the worker to a waiting
     * user request at once: an explicit action does not wait for background work (§4).
     */
    fun suspendOwnedGateForHome(
        displaced: () -> Boolean = { false },
    ): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        var waited = 0L
        while (true) {
            if (suspendOwnedGateIfCovered()) return true
            if (waited >= HOME_CONFIRM_BUDGET_MS || displaced()) return false
            val slice = minOf(AREA_POLL_INTERVAL_MS, HOME_CONFIRM_BUDGET_MS - waited)
            world.pause(slice)
            waited += slice
        }
    }

    /**
     * One read, one decision, no waiting: the same suspension as [suspendOwnedGateForHome], for a
     * caller that already has a reason to look at the world and no right to block in it: the
     * reconcile, so that the gate follows a cover even when no Home input ran (1.9.2; findings,
     * "The Home hint arrives twice in eight").
     *
     * The authority is the same as the suspension's: a read area 0/4 ([SplitWorld.sceneCovered],
     * 1.9.1, 1.11.5), never an event, and one question inside a read already planned.
     *
     * @return whether this call is what suspended it.
     */
    fun suspendOwnedGateIfCovered(): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        if (!world.sceneCovered()) return false
        world.callVoid("service call activity_task 126 i32 0")
        return true
    }

    /**
     * The mirror of [suspendOwnedGateIfCovered]: one read, one decision, no waiting.
     *
     * The explicit open and a tap in a picker reopen a suspended gate through [ensureGateOpen]. A
     * scene covered by something other than Home also comes back by itself - the call ends, the
     * camera goes away, the notification's app is closed with Back - with the gate the product
     * closed under the cover still closed, and then an activity start into a pane member's task
     * goes to `startFullWindow` (findings, "Placement, read end to end"): a member of the scene
     * escaping to fullscreen with nobody having asked. This reopens it when the reconcile or a
     * reveal reads the scene visible again.
     *
     * Mutation authority is the same as the suspension's: a read area, never an event. Only a gate
     * this product holds the lease for, and only over a scene the area calls visible (1/2/3).
     *
     * @return whether this call is what resumed it.
     */
    fun resumeOwnedGateIfVisible(): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        if (world.sceneCovered()) return false
        world.callVoid("service call activity_task 126 i32 1")
        return true
    }

    /**
     * The gate is firmware-global, so it is closed by exactly one rule: we opened it, therefore we
     * close it (contract, to 1.12; invariant 1). A gate that was already open when our session
     * started, or that belongs to a stock split the user built themselves, is left alone.
     *
     * @return whether this call is what closed it.
     */
    fun closeOwnedGate(): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        world.callVoid("service call activity_task 126 i32 0")
        check(gateLeaseStore.setOwned(false)) { "Не удалось освободить split-gate" }
        return true
    }

    private companion object {
        /**
         * How long a Home's suspension waits for the cover to be read: long enough that a scene
         * the area calls covered a little late still gets its gate closed (1.9.3).
         */
        const val HOME_CONFIRM_BUDGET_MS = 3_000L
    }
}

/**
 * The inverse of [SplitJournalEntry.GateOpened], on the rollback's own budgeted transport (contract
 * 7.10): an opening by our hand is undone by closing the gate, an opening over a gate that was
 * already open by our hand by opening it again.
 */
internal class SplitGateUndo(
    private val gateLeaseStore: SplitGateLeaseStore,
    private val send: (String) -> String,
) {
    /** Contract, to 1.12: a gate is closed only by the lease that opened it. */
    fun close() {
        if (!gateLeaseStore.isOwned()) return
        send("service call activity_task 126 i32 0")
        gateLeaseStore.setOwned(false)
    }

    fun open() {
        send("service call activity_task 126 i32 1")
    }
}

/**
 * The core's move on the gate ahead of the area (К 1.9): the race between a Home and the next tap
 * in the dock, which a round trip loses.
 *
 * `homekey` and a pushed covered area may close a gate this session owns over a scene not yet
 * recorded covered, in this process and in a millisecond; one area read [CHECK_MS] later puts it
 * back if no cover followed. The core decides when to ask - never while an operation that uses the
 * gate this instant is queued or running - and this decides whether the gate is ours to move.
 */
internal class SplitGateAhead(
    private val gate: SplitGateSwitch,
    private val gateLeaseStore: SplitGateLeaseStore,
    private val clock: SplitClock,
    private val readArea: () -> Int?,
    private val log: SplitDiagnosticLog,
    /** Whether the scene is already recorded covered: then its cover has suspended the gate. */
    private val coverRecorded: () -> Boolean,
    /** Whether an operation that uses the gate this instant is queued or running. */
    private val keepersPending: () -> Boolean,
) {
    private val checkLock = Any()
    private var pendingCheck: SplitCancellable? = null

    /**
     * One in-process transaction on the gate, and only on ours: a gate this session never opened is
     * not its to close (to 1.12), and a cover already recorded has suspended it already.
     */
    fun close(cause: String) {
        if (!gateLeaseStore.isOwned()) return
        if (coverRecorded()) return
        val startedAtMs = clock.nowMs()
        val closed = runCatching { gate.set(open = false) }
            .onFailure { error -> log.log("gate на опережение не закрылся ($cause): $error") }
            .isSuccess
        if (!closed) return
        log.log("gate закрыт на опережение ($cause) за ${clock.nowMs() - startedAtMs} мс")
        synchronized(checkLock) {
            pendingCheck?.cancel()
            pendingCheck = clock.schedule(CHECK_MS) { checkAgainstTheArea(cause) }
        }
    }

    /**
     * The undo of [close], decided by one area read. A covered area means the close was right and
     * the Home input owns the rest. A visible one with no cover recorded means the Home never
     * happened - the firmware swallowed the key, or a push was one of the transients - and a
     * visible scene with our gate closed is a pane app escaping to fullscreen on its next screen
     * (findings, "Placement, read end to end"), so the gate goes back.
     */
    private fun checkAgainstTheArea(cause: String) {
        synchronized(checkLock) { pendingCheck = null }
        val area = runCatching(readArea).getOrNull() ?: return
        if (area.isCoveredArea()) return
        if (!gateLeaseStore.isOwned()) return
        if (coverRecorded()) return
        if (keepersPending()) return
        runCatching { gate.set(open = true) }
            .onSuccess { log.log("gate возвращён: $cause без накрытия, area=$area") }
            .onFailure { error -> log.log("gate не возвращён после $cause: $error") }
    }

    fun cancelCheck() {
        synchronized(checkLock) {
            pendingCheck?.cancel()
            pendingCheck = null
        }
    }

    companion object {
        /**
         * When a gate closed ahead of the area is checked against it. The push of a real Home
         * arrived 111 ms after the key live, and flip to delivery was at most 145 ms over six
         * captured Homes (findings 2026-09-23); a second is several times that, and short enough
         * that a swallowed Home leaves a visible scene with a closed gate for no longer than it.
         */
        const val CHECK_MS = 1_000L
    }
}
