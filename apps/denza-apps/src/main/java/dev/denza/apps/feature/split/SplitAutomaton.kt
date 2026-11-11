package dev.denza.apps.feature.split

/**
 * The contract automaton: `(state, settled fact) -> (state', whether a scene needs tearing down)`.
 *
 * Pure, synchronous and total. It is the only writer of semantic state (invariant 12) and knows
 * nothing about ADB, time, threads or task ids (contract section 7). A fact that is impossible in
 * the current state is a strict no-op: the very same state instance comes back with no teardown,
 * so a duplicated, late or foreign observation can never move the screen (U1, invariant 8).
 */
internal object SplitAutomaton {

    fun reduce(state: SplitState, fact: SplitFact): SplitReduction {
        // Invariant 1: while the toggle is off the product hears nothing but the toggle itself.
        if (!state.enabled && fact !is SplitFact.ToggleChanged) return unchanged(state)
        return when (fact) {
            is SplitFact.ToggleChanged -> toggle(state, fact.enabled)
            is SplitFact.BuildSceneSucceeded -> sceneBuilt(state, fact.slots)
            is SplitFact.AppLaunchConfirmed -> launchConfirmed(state, fact)
            is SplitFact.AppClosedSettled -> appClosed(state, fact.pane)
            is SplitFact.PaneCollapsedSettled ->
                paneCollapsed(state, fact.collapsed, fact.survivorPane)
            is SplitFact.PickerPaneClosedSettled -> pickerPaneClosed(state, fact.pane)
            SplitFact.SceneEndedSettled -> sceneEnded(state)
            SplitFact.HomeConfirmed -> home(state)
            SplitFact.SceneRevealed -> sceneRevealed(state)
            is SplitFact.EdgeCommitConfirmed -> edgeCommit(state, fact.vacantPane)
            is SplitFact.ProjectionStarted -> projectionStarted(state, fact.pane)
            SplitFact.ProjectionReturned -> projectionReturned(state)
            is SplitFact.PackageRemoved -> packageRemoved(state, fact.packageName)
            is SplitFact.PairPinned -> pairPinned(state, fact.pair)
            is SplitFact.PairUnpinned -> pairUnpinned(state, fact.pair)
            is SplitFact.PinsReordered -> pinsReordered(state, fact.order)
        }
    }

    /**
     * Contract 1.2. Enabling only arms the product (1.2.1). Disabling ends the product scene and
     * keeps the selection, so the next open restores the same pair (1.2.2-1.2.5, 1.3.2).
     */
    private fun toggle(state: SplitState, enabled: Boolean): SplitReduction {
        if (state.enabled == enabled) return unchanged(state)
        if (enabled) return settled(state, state.copy(enabled = true))
        // Какой именно разбор нужен - решает `closePickers` по живой сцене, и проверяется это его
        // собственными тестами. Здесь остаётся то единственное, что операция и спрашивала: есть ли
        // вообще что разбирать (1.2.3-1.2.5).
        val teardownRequired = when {
            state.scene == null -> false
            state.scenePanes().any { state.slot(it) is SplitSlot.Picker } -> true
            state.scene == SplitScene.Split -> true
            else -> false
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
            teardownRequired,
        )
    }

    /**
     * Contract 1.3. A live scene is only raised (1.3.5, 1.3.6); otherwise the saved selection is
     * rebuilt, every closed pane joining it as a fresh picker (1.3.2-1.3.4). Slots move only once
     * the build is settled, so a failed or cancelled open leaves the selection untouched - which is
     * also why an open *request* is not a fact: it moved nothing, and the two request facts the
     * automaton used to hear returned the very same state on every branch.
     */
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
     * Contract 1.5. The slot follows the confirmed launch. The vacancy left by a projected navigator
     * is the exception (1.10.2): its picker is scene content rather than slot content.
     *
     * A pane that still holds `APP(other)` takes the launch too (ред. 2026-11-11). That is the third
     * of the four reasons invariant 14 lets a slot lose its package - another application chosen
     * there - and the pane it happens in is the one an open could not finish restoring: its picker
     * is on screen while the slot still names the application the user left there (1.3.11). The
     * proof of the launch is the operation's, made on the scene; the automaton only refuses what
     * cannot be one: no scene, a closed pane, a blank package, or the package the pane already
     * holds (a repeated or late confirmation, identity).
     */
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
        val current = state.slot(fact.pane)
        if (current == SplitSlot.Closed || current == SplitSlot.App(fact.packageName)) {
            return unchanged(state)
        }
        return settled(state, state.withSlot(fact.pane, SplitSlot.App(fact.packageName)))
    }

    /**
     * Contract 1.6.2, 1.7.1-1.7.3, 1.14.6. Closing an app on a scene the user is looking at - by
     * Back, by gesture or by crashing - returns that pane to its picker and never touches the
     * neighbour. In a projected pane only the ephemeral vacancy record disappears; the navigator
     * keeps its slot. Its producers send it only for a visible scene (invariant 14); the pair it
     * breaks stays in the memory as the last one (1.14.6).
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

    /**
     * Contract 1.8.2: the collapsed pane is closed for good, the survivor fills the screen.
     *
     * Выживший поселяется в [survivorPane] - панели, в которой его оставила прошивка, - а не в той,
     * которой он был помечен до жеста (решение владельца 2026-09-18, раздел 5 «К 1.8»). При
     * `survivorPane == collapsed.other()` это ровно прежнее поведение.
     */
    private fun paneCollapsed(
        state: SplitState,
        collapsed: SplitPane,
        survivorPane: SplitPane,
    ): SplitReduction {
        val survivorSlot = state.slot(collapsed.other())
        if (state.scene == null || survivorSlot == SplitSlot.Closed) return unchanged(state)
        val projected = state.projectedPane
        val carried = when {
            projected == null -> state
            // Проекция принадлежит панели, а не стороне: навигатор ВЫЖИВШЕЙ панели переезжает
            // вместе с ней, и вместе с ним - эфемерная запись вакансии (1.10, «К 1.10»).
            projected == collapsed.other() -> state.copy(
                projectedPane = survivorPane,
                vacancyApp = state.vacancyApp[projected]
                    ?.let { occupant -> mapOf(survivorPane to occupant) }
                    .orEmpty(),
            )
            // 1.10.6: схлопнули панель самого навигатора - её место отдано, ничто не воскресает.
            else -> state.copy(projectedPane = null, vacancyApp = emptyMap())
        }
        return settled(
            state,
            carried
                .withSlots { pane, _ ->
                    if (pane == survivorPane) survivorSlot else SplitSlot.Closed
                }
                .copy(scene = SplitScene.Full(survivorPane)),
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
     * Contract 1.7.5 (ред. 2026-11-11): the scene is gone, the choice is not. "Clear all", a
     * firmware unload and a sleep all end the scene without the user leaving any pane on a scene
     * they were looking at, and the product cannot tell one from another - so every slot stays as
     * it was, and the next tap brings the same pair back (1.3.11, invariant 14, scenario 36).
     *
     * A projected navigator keeps its projection as before (scenario 30): its task lives on the
     * instrument cluster, which Recents does not reach, and its return needs a pane to come to.
     *
     * Until 2026-11-11 every live application gave its pane back to the picker here, and the next
     * open showed two fresh pickers.
     */
    private fun sceneEnded(state: SplitState): SplitReduction {
        state.scene ?: return unchanged(state)
        return settled(state, state.copy(scene = null, visibility = SceneVisibility.VISIBLE))
    }

    /**
     * Contract 1.9.1 and invariant 5: Home hides the scene, it does not close anything.
     *
     * Правка 2026-09-18 (перезапуск процесса над живой сессией): накрытие записывается и тогда,
     * когда сцены в памяти нет. Ось видимости независима от оси сцены (раздел 2), и у неё есть
     * собственный смысл без сцены: gate, который держит эта сессия, обязан следовать за накрытием
     * мира, а не за тем, что процесс успел о нём запомнить (1.12). Живьём gate остался открытым
     * после переустановки пакета над живой парой, и запуск с рабочего стола ушёл в split с
     * `com.byd.sr` - против 1.9.2. Записанное накрытие - это то, чем подвеска доказывает себе, что
     * она уже случилась: следующая накрытая подсказка не отправляет `126 i32 0` второй раз.
     *
     * Обратно ось возвращает только доказанная сцена ([sceneBuilt] ставит VISIBLE): видимый мир,
     * который продукту не принадлежит, gate не открывает.
     */
    private fun home(state: SplitState): SplitReduction {
        if (state.visibility == SceneVisibility.COVERED) return unchanged(state)
        return settled(
            state,
            state.copy(visibility = SceneVisibility.COVERED),
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

    /**
     * Contract 1.5.6, 1.14.4 and section 6: an uninstalled package cannot stay in any pane, and no
     * pair that names it stays in the memory - not as the last pair, not among the recent ones and
     * not among the pins.
     */
    private fun packageRemoved(state: SplitState, packageName: String): SplitReduction {
        if (packageName.isBlank()) return unchanged(state)
        val next = state.withSlots { _, slot ->
            if (slot is SplitSlot.App && slot.packageName == packageName) SplitSlot.Picker else slot
        }
        val memory = state.memory
        return settled(
            state,
            next.copy(
                vacancyApp = next.vacancyApp.filterValues { it != packageName },
                memory = SplitMemory(
                    last = memory.last?.takeUnless { packageName in it },
                    recent = memory.recent.filterNot { packageName in it },
                    pinned = memory.pinned.filterNot { packageName in it },
                ),
            ),
        )
    }

    /**
     * Contract 1.15.7: the last pair or a recent one joins the pins, at the end. A pair that is
     * neither, or is pinned already, is nothing the pill could have offered a star for.
     */
    private fun pairPinned(state: SplitState, pair: SplitPair): SplitReduction {
        val memory = state.memory
        if (!pair.isWellFormed || pair in memory.pinned) return unchanged(state)
        if (pair != memory.last && pair !in memory.recent) return unchanged(state)
        return settled(
            state,
            state.copy(memory = memory.copy(recent = memory.recent - pair, pinned = memory.pinned + pair)),
        )
    }

    /**
     * Contract 1.14.4, 1.15.8: an unpinned pair becomes the first recent one - unless it is the
     * last pair, which never stands among the recent ones.
     */
    private fun pairUnpinned(state: SplitState, pair: SplitPair): SplitReduction {
        val memory = state.memory
        if (pair !in memory.pinned) return unchanged(state)
        val recent = if (pair == memory.last) memory.recent else listOf(pair) + memory.recent
        return settled(
            state,
            state.copy(
                memory = memory.copy(
                    recent = recent.take(SplitMemory.RECENT_LIMIT),
                    pinned = memory.pinned - pair,
                ),
            ),
        )
    }

    /** Contract 1.15.8: the same pins in another order; anything else is not a reorder. */
    private fun pinsReordered(state: SplitState, order: List<SplitPair>): SplitReduction {
        val pinned = state.memory.pinned
        if (order.size != pinned.size || order.toSet() != pinned.toSet()) return unchanged(state)
        return settled(state, state.copy(memory = state.memory.copy(pinned = order)))
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
     *
     * Every changing reduction ends here, and so does the one rule of the memory that follows the
     * panes rather than a fact of its own ([remembered]): the memory moves in the same reduction,
     * and so in the same commit, as the slots that moved it.
     */
    private fun settled(
        previous: SplitState,
        next: SplitState,
        teardownRequired: Boolean = false,
    ): SplitReduction {
        val remembered = remembered(next)
        return SplitReduction(if (remembered == previous) previous else remembered, teardownRequired)
    }

    /**
     * Contract 1.14.2, 1.14.4: the last pair is the one that stood in both panes most recently.
     *
     * It changes when a reduction leaves a two-pane scene with an application in both durable
     * slots, and those two are not the last pair already - whatever the fact was: an open that
     * rebuilt the pair, a choice in a picker, a projected navigator's pane (its slot is still the
     * navigator's; the vacancy's occupant is not a choice, section 5 «К 1.10»). The pair it replaces
     * becomes the first recent one unless it is pinned; the recent list keeps no duplicate, never the
     * last pair nor a pin, and the sixth falls off. A scene that is not two applications - a picker
     * in a pane, a collapse - leaves the memory alone: the pair it broke stays the last one (1.14.6).
     */
    private fun remembered(state: SplitState): SplitState {
        if (state.scene != SplitScene.Split) return state
        val pair = SplitPair.of(state.slots) ?: return state
        val memory = state.memory
        if (pair == memory.last) return state
        val previous = listOfNotNull(memory.last)
        val recent = (previous + memory.recent)
            .filter { candidate -> candidate != pair && candidate !in memory.pinned }
            .distinct()
            .take(SplitMemory.RECENT_LIMIT)
        return state.copy(memory = memory.copy(last = pair, recent = recent))
    }
}
