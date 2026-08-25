package dev.denza.apps.feature.split

/**
 * The contract automaton: `(state, settled fact) -> (state', plans)`.
 *
 * Pure, synchronous and total. It is the only writer of semantic state (invariant 12) and knows
 * nothing about ADB, time, threads or task ids (contract section 7). A fact that is impossible in
 * the current state is a strict no-op: the very same state instance comes back with no plans, so a
 * duplicated, late or foreign observation can never move the screen (U1, invariant 8).
 */
internal object SplitAutomaton {

    fun reduce(state: SplitState, fact: SplitFact): SplitReduction {
        // Invariant 1: while the toggle is off the product hears nothing but the toggle itself.
        if (!state.enabled && fact !is SplitFact.ToggleChanged) return unchanged(state)
        return when (fact) {
            is SplitFact.ToggleChanged -> toggle(state, fact.enabled)
            SplitFact.OpenRequested -> open(state)
            is SplitFact.BuildSceneSucceeded -> sceneBuilt(state, fact.slots)
            is SplitFact.SelectionRequested -> selectionRequested(state, fact)
            is SplitFact.AppLaunchConfirmed -> launchConfirmed(state, fact)
            is SplitFact.AppClosedSettled -> appClosed(state, fact.pane)
            is SplitFact.PaneCollapsedSettled -> paneCollapsed(state, fact.survivor)
            is SplitFact.PickerPaneClosedSettled -> pickerPaneClosed(state, fact.pane)
            SplitFact.SceneEndedSettled -> sceneEnded(state)
            SplitFact.HomeConfirmed -> home(state)
            SplitFact.SceneRevealed -> sceneRevealed(state)
            is SplitFact.EdgeCommitConfirmed -> edgeCommit(state, fact.vacantPane)
            is SplitFact.ProjectionStarted -> projectionStarted(state, fact.pane)
            SplitFact.ProjectionReturned -> projectionReturned(state)
            is SplitFact.PackageRemoved -> packageRemoved(state, fact.packageName)
        }
    }

    /**
     * Contract 1.2. Enabling only arms the product (1.2.1). Disabling ends the product scene and
     * keeps the selection, so the next open restores the same pair (1.2.2-1.2.5, 1.3.2).
     */
    private fun toggle(state: SplitState, enabled: Boolean): SplitReduction {
        if (state.enabled == enabled) return unchanged(state)
        if (enabled) return settled(state, state.copy(enabled = true))
        val plans = when {
            state.scene == null -> emptyList()
            state.scenePanes().any { state.slot(it) is SplitSlot.Picker } ->
                listOf(SplitPlan.RemoveProductPickers)
            state.scene == SplitScene.Split -> listOf(SplitPlan.EndSplitFocusedFullscreen)
            else -> emptyList()
        }
        return settled(
            state,
            state.copy(
                enabled = false,
                scene = null,
                visibility = SceneVisibility.VISIBLE,
                projectedPane = null,
                vacancyApp = emptyMap(),
            ),
            plans,
        )
    }

    /**
     * Contract 1.3. A live scene is only raised (1.3.5, 1.3.6); otherwise the saved selection is
     * rebuilt, every closed pane joining it as a fresh picker (1.3.2-1.3.4). Slots move only once
     * the build is settled, so a failed or cancelled open leaves the selection untouched.
     */
    private fun open(state: SplitState): SplitReduction {
        if (state.scene != null) return SplitReduction(state, listOf(SplitPlan.RevealScene))
        val desired = SplitPane.entries.associateWith { pane ->
            when (val slot = state.slot(pane)) {
                SplitSlot.Closed -> SplitSlot.Picker
                else -> slot
            }
        }
        return SplitReduction(state, listOf(SplitPlan.BuildScene(desired)))
    }

    private fun sceneBuilt(state: SplitState, slots: Map<SplitPane, SplitSlot>): SplitReduction {
        if (!slots.isWellFormed()) return unchanged(state)
        val scene = sceneFor(slots) ?: return unchanged(state)
        return settled(
            state,
            state
                .withSlots { pane, _ -> slots.getValue(pane) }
                .copy(scene = scene, visibility = SceneVisibility.VISIBLE),
        )
    }

    /**
     * Contract 1.5. A tap is answered by a launch intent only; the slot follows the confirmation.
     * The pane must be showing a picker, the one exception being the vacancy left by a projected
     * navigator (1.10.2), where the picker is scene content rather than slot content.
     */
    private fun selectionRequested(
        state: SplitState,
        fact: SplitFact.SelectionRequested,
    ): SplitReduction {
        if (state.scene == null || fact.packageName.isBlank()) return unchanged(state)
        val selectable = state.slot(fact.pane) == SplitSlot.Picker || fact.pane == state.projectedPane
        if (!selectable) return unchanged(state)
        return SplitReduction(state, listOf(SplitPlan.LaunchApp(fact.pane, fact.packageName)))
    }

    private fun launchConfirmed(
        state: SplitState,
        fact: SplitFact.AppLaunchConfirmed,
    ): SplitReduction {
        if (state.scene == null || fact.packageName.isBlank()) return unchanged(state)
        if (fact.pane == state.projectedPane) {
            // Section 5, to 1.10: the durable slot stays APP(navigator) for the whole projection,
            // the temporary occupant is an ephemeral record.
            return settled(
                state,
                state.copy(vacancyApp = state.vacancyApp + (fact.pane to fact.packageName)),
            )
        }
        if (state.slot(fact.pane) != SplitSlot.Picker) return unchanged(state)
        return settled(state, state.withSlot(fact.pane, SplitSlot.App(fact.packageName)))
    }

    /**
     * Contract 1.6.2, 1.7.1-1.7.3. Closing an app - by Back, by gesture or by crashing - returns
     * that pane to its picker and never touches the neighbour. In a projected pane only the
     * ephemeral vacancy record disappears; the navigator keeps its slot.
     */
    private fun appClosed(state: SplitState, pane: SplitPane): SplitReduction {
        if (state.scene == null) return unchanged(state)
        if (pane == state.projectedPane) {
            if (!state.vacancyApp.containsKey(pane)) return unchanged(state)
            return settled(state, state.copy(vacancyApp = state.vacancyApp - pane))
        }
        if (state.slot(pane) !is SplitSlot.App) return unchanged(state)
        return settled(state, state.withSlot(pane, SplitSlot.Picker))
    }

    /** Contract 1.8.2: the collapsed pane is closed for good, the survivor fills the screen. */
    private fun paneCollapsed(state: SplitState, survivor: SplitPane): SplitReduction {
        if (state.scene == null || state.slot(survivor) == SplitSlot.Closed) return unchanged(state)
        return settled(
            state,
            state
                .withSlot(survivor.other(), SplitSlot.Closed)
                .copy(scene = SplitScene.Full(survivor)),
        )
    }

    /**
     * Contract 1.6.3, 1.6.4, 1.7.4. Dismissing a pane closes it; the neighbour expands, and when
     * there is no neighbour left the split session is over.
     */
    private fun pickerPaneClosed(state: SplitState, pane: SplitPane): SplitReduction {
        val scene = state.scene ?: return unchanged(state)
        if (state.slot(pane) == SplitSlot.Closed) return unchanged(state)
        val survivor = pane.other()
        val next = state.withSlot(pane, SplitSlot.Closed)
        val sessionOver = scene == SplitScene.Full(pane) || next.slot(survivor) == SplitSlot.Closed
        return settled(
            state,
            if (sessionOver) {
                next.copy(scene = null, visibility = SceneVisibility.VISIBLE)
            } else {
                next.copy(scene = SplitScene.Full(survivor))
            },
        )
    }

    /**
     * Contract 1.7.5: "Clear all" leaves nothing running, so every live app gives its pane back to
     * the picker. The next open shows fresh pickers instead of reviving what the user cleared.
     *
     * A projected navigator is the one exception (scenario 30): its task lives on the instrument
     * cluster, which Recents does not reach, so it was never closed. Its pane keeps `APP(nav)` and
     * the projection survives - otherwise the return would have nowhere to come back to.
     */
    private fun sceneEnded(state: SplitState): SplitReduction {
        state.scene ?: return unchanged(state)
        val projected = state.projectedPane
        return settled(
            state,
            state
                .withSlots { pane, slot ->
                    if (slot is SplitSlot.App && pane != projected) SplitSlot.Picker else slot
                }
                .copy(scene = null, visibility = SceneVisibility.VISIBLE),
        )
    }

    /** Contract 1.9.1 and invariant 5: Home hides the scene, it does not close anything. */
    private fun home(state: SplitState): SplitReduction {
        if (state.scene == null || state.visibility == SceneVisibility.COVERED) {
            return unchanged(state)
        }
        return settled(
            state,
            state.copy(visibility = SceneVisibility.COVERED),
            listOf(SplitPlan.SuspendOwnedGate),
        )
    }

    private fun sceneRevealed(state: SplitState): SplitReduction {
        if (state.scene == null || state.visibility == SceneVisibility.VISIBLE) {
            return unchanged(state)
        }
        return settled(state, state.copy(visibility = SceneVisibility.VISIBLE))
    }

    /** Contract 1.8.5: the fullscreen app keeps running and a picker joins it in the free pane. */
    private fun edgeCommit(state: SplitState, vacantPane: SplitPane): SplitReduction {
        if (state.scene != SplitScene.Full(vacantPane.other())) return unchanged(state)
        return settled(
            state,
            state.withSlot(vacantPane, SplitSlot.Picker).copy(scene = SplitScene.Split),
            listOf(SplitPlan.AttachPicker(vacantPane)),
        )
    }

    /**
     * Contract 1.10.1. The navigator left for the instrument cluster; its pane shows a picker, but
     * that picker is scene content. The slot keeps the navigator so the pane can take it back.
     */
    private fun projectionStarted(state: SplitState, pane: SplitPane): SplitReduction {
        if (state.slot(pane) !is SplitSlot.App) return unchanged(state)
        if (state.projectedPane != null && state.projectedPane != pane) return unchanged(state)
        return settled(state, state.copy(projectedPane = pane))
    }

    /** Contract 1.10.3, 1.10.4: the navigator is home again and the vacancy record is spent. */
    private fun projectionReturned(state: SplitState): SplitReduction {
        if (state.projectedPane == null) return unchanged(state)
        return settled(state, state.copy(projectedPane = null, vacancyApp = emptyMap()))
    }

    /** Contract 1.5.6 and section 6: an uninstalled package cannot stay in any pane. */
    private fun packageRemoved(state: SplitState, packageName: String): SplitReduction {
        if (packageName.isBlank()) return unchanged(state)
        val next = state.withSlots { _, slot ->
            if (slot is SplitSlot.App && slot.packageName == packageName) SplitSlot.Picker else slot
        }
        return settled(
            state,
            next.copy(vacancyApp = next.vacancyApp.filterValues { it != packageName }),
        )
    }

    private fun SplitState.scenePanes(): List<SplitPane> = when (val scene = scene) {
        null -> emptyList()
        SplitScene.Split -> SplitPane.entries
        is SplitScene.Full -> listOf(scene.pane)
    }

    private fun SplitState.withSlot(pane: SplitPane, slot: SplitSlot): SplitState =
        withSlots { candidate, current -> if (candidate == pane) slot else current }

    /**
     * Rewrites both slots and keeps the projection axis honest: a pane that no longer holds an app
     * cannot be the one whose navigator sits on the cluster, and a spent projection carries no
     * vacancy (contract 1.10.6).
     */
    private fun SplitState.withSlots(
        transform: (SplitPane, SplitSlot) -> SplitSlot,
    ): SplitState {
        val updated = SplitPane.entries.associateWith { pane -> transform(pane, slot(pane)) }
        val projected = projectedPane?.takeIf { updated.getValue(it) is SplitSlot.App }
        return copy(
            slots = updated,
            projectedPane = projected,
            vacancyApp = if (projected == null) emptyMap() else vacancyApp.filterKeys { it == projected },
        )
    }

    private fun Map<SplitPane, SplitSlot>.isWellFormed(): Boolean =
        keys == SplitPane.entries.toSet() &&
            values.none { it is SplitSlot.App && it.packageName.isBlank() }

    private fun sceneFor(slots: Map<SplitPane, SplitSlot>): SplitScene? {
        val live = SplitPane.entries.filter { slots.getValue(it) != SplitSlot.Closed }
        return when (live.size) {
            SplitPane.entries.size -> SplitScene.Split
            1 -> SplitScene.Full(live.single())
            else -> null
        }
    }

    private fun unchanged(state: SplitState) = SplitReduction(state)

    /**
     * Guarantees the no-op identity rule: when a fact leaves the state semantically untouched the
     * caller gets the original instance back, never an equal copy.
     */
    private fun settled(
        previous: SplitState,
        next: SplitState,
        plans: List<SplitPlan> = emptyList(),
    ) = SplitReduction(if (next == previous) previous else next, plans)
}
