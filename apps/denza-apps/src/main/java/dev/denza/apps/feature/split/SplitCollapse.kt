package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_PRIMARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_SECONDARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.MAX_TASKS_PER_PANE
import dev.denza.apps.feature.split.SplitWorld.Companion.ROOT_SETTLE_MS

/**
 * What a divider gesture left behind, read from the car (contract 1.8; findings, "The divider and
 * the stock picker, read end to end").
 *
 * A resize may leave the two permanent bases in the wrong roots under the right applications, and
 * [reconcileDividerResize] puts them back. A collapse closes one pane, and three reads prove it,
 * each where the others are blind: the surviving root adopted by exact identity
 * ([readCollapsedSession], the only one of them that may move a picker back), the recorded tasks
 * of one pane gone from both panel roots while the area names the survivor
 * ([readCollapsedPaneByExistence]), and the panel root the firmware stretched over the whole
 * screen, which outlives Home and a fullscreen window ([collapsedPaneByPanelBounds]). The
 * reconcile asks them in that order; the open asks the last one alone.
 */
internal class SplitCollapse(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
    private val ownedScene: SplitOwnedScene,
) {
    /**
     * Waits for BYD's divider transition, then repairs only recorded picker/app ownership.
     *
     * DiLink can keep the two visible surfaces on their visual sides while moving only the top
     * app tasks between native roots. A hidden permanent picker base can consequently remain in
     * the old root beside the other picker. The previous automaton state is the narrow proof of
     * which exact host belongs under which exact app; anything incomplete or changed fails closed.
     */
    fun reconcileDividerResize(
        pickerComponents: Set<String>,
        previousPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): Map<SplitPane, SplitPickerLivePane>? {
        // The area is read BEFORE the pause. Over a covered scene - Home (0) or a fullscreen
        // window (4) - there is no divider and no settle to wait for, and the single worker is not
        // held for nothing while an open waits behind it. Whether a covered scene still exists is
        // the caller's question (invariant 5); visible areas keep the divider's settle.
        val area = world.callInt("service call activity_task 30")
        if (area == AREA_HOME || area == AREA_FULL_IVI) return null
        world.pause(DIVIDER_RECONCILE_SETTLE_MS)
        ownedScene.existingOwnedSession(pickerComponents)?.let { return it }
        if (world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) return null
        if (previousPanes.keys != SplitPane.entries.toSet()) return null

        val observed = SplitPane.entries.map { pane -> previousPanes.getValue(pane) }
        if (
            observed.any { pane ->
                pane.hostTaskId <= 0 ||
                    ((pane.appTaskId == null) != (pane.packageName == null))
            } ||
            observed.map { pane -> pane.hostTaskId }.distinct().size != observed.size ||
            observed.mapNotNull { pane -> pane.appTaskId }.let { ids ->
                ids.isEmpty() || ids.distinct().size != ids.size
            }
        ) {
            return null
        }

        val roots = world.nativeRootIds()
        val nativeRootIds = roots.values.toSet()
        val state = world.snapshot()
        val mainTasks = state.roots.asSequence()
            .filter { root -> root.displayId == MAIN_DISPLAY_ID }
            .flatMap { root -> root.tasks.asSequence() }
            .toList()
        val hosts = previousPanes.mapValues { (_, pane) ->
            mainTasks.singleOrNull { task ->
                task.id == pane.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } ?: return null
        }

        val desiredRoots = mutableMapOf<SplitPane, Int>()
        previousPanes.forEach { (pane, previous) ->
            val appTaskId = previous.appTaskId ?: return@forEach
            val packageName = previous.packageName ?: return null
            val app = mainTasks.singleOrNull { task ->
                task.id == appTaskId &&
                    task.rootId in nativeRootIds &&
                    task.packageName == packageName
            } ?: return null
            val root = state.root(app.rootId) ?: return null
            if (app.bounds != root.bounds) return null
            desiredRoots[pane] = app.rootId
        }
        if (desiredRoots.values.distinct().size != desiredRoots.size) return null

        val vacantPanes = SplitPane.entries.filterNot(desiredRoots::containsKey)
        val vacantRoots = nativeRootIds - desiredRoots.values.toSet()
        if (vacantPanes.size != vacantRoots.size) return null
        if (vacantPanes.size == 1) {
            desiredRoots[vacantPanes.single()] = vacantRoots.single()
        }
        if (desiredRoots.values.toSet() != nativeRootIds) return null

        var moved = false
        SplitPane.entries.forEach { pane ->
            val host = hosts.getValue(pane)
            val targetRootId = desiredRoots.getValue(pane)
            if (host.rootId != targetRootId) {
                commands.moveTask(host.id, targetRootId, toTop = false)
                moved = true
            }
        }
        if (moved) world.pause(ROOT_SETTLE_MS)
        SplitPane.entries.forEach { pane ->
            commands.normalizeTaskToRoot(
                taskId = hosts.getValue(pane).id,
                rootId = desiredRoots.getValue(pane),
            )
        }
        return ownedScene.existingOwnedSession(pickerComponents)
    }

    /**
     * Adopts the one owned root left by a native edge collapse, with the reason it refused (U5):
     * every fail-closed predicate names itself, so a refusal during the firmware's two-pass
     * teardown says which one disagreed.
     *
     * Area 1/2 identifies the surviving native pane, but not the previous logical owner. DiLink
     * may move the surviving app across the two native roots and detach both permanent picker
     * bases while it collapses the divider. Match the survivor by exact recorded task identities,
     * reattach only that app's exact picker base when necessary, and require the other native root
     * to be empty. BYD may retain the dismissed tasks as detached hidden roots; the coordinator
     * removes only those exact recorded artifacts after adoption. This is deliberately separate
     * from [SplitOwnedScene.existingOwnedSession], whose callers require an intact two-root scene.
     */
    fun readCollapsedSession(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapseRead {
        val area = world.callInt("service call activity_task 30")
        val survivor = when (area) {
            AREA_PRIMARY_FULL -> SplitPane.PRIMARY
            AREA_SECONDARY_FULL -> SplitPane.SECONDARY
            else -> return SplitCollapseRead(null, "area=$area")
        }
        if (expectedPanes.isEmpty() || !SplitPane.entries.toSet().containsAll(expectedPanes.keys)) {
            return SplitCollapseRead(null, "запись сцены неполна")
        }
        if (expectedPanes.values.any { expected ->
                expected.hostTaskId <= 0 ||
                    ((expected.appTaskId == null) != (expected.packageName == null)) ||
                    (expected.appTaskId != null &&
                        (expected.appTaskId <= 0 || expected.packageName.isNullOrBlank()))
            }
        ) {
            return SplitCollapseRead(null, "запись сцены неполна")
        }

        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val collapsedRoot = state.root(roots.getValue(survivor.other()))
        if (collapsedRoot?.tasks.orEmpty().any { task -> !task.isEmptyRootMarker() }) {
            return SplitCollapseRead(null, "в схлопнутом корне остались задачи")
        }

        val nativeRootIds = roots.values.toSet()
        val survivorRootId = roots.getValue(survivor)
        val root = state.root(survivorRootId)
            ?: return SplitCollapseRead(null, "корень выжившего не читается")
        val previousOwners = expectedPanes.filter { (_, expected) ->
            val hostMatches = root.tasks.any { task ->
                task.id == expected.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            }
            val appMatches = expected.appTaskId?.let { expectedAppTaskId ->
                root.tasks.any { task ->
                    task.id == expectedAppTaskId &&
                        task.packageName == expected.packageName &&
                        !task.isDenzaPickerBase()
                }
            } ?: false
            hostMatches || appMatches
        }
        if (previousOwners.size != 1) {
            return SplitCollapseRead(null, "выживший не опознан: совпадений ${previousOwners.size}")
        }
        val (previousOwner, expected) = previousOwners.entries.single()
        val liveAppPresent = expected.appTaskId?.let { expectedAppTaskId ->
            root.tasks.any { task ->
                task.id == expectedAppTaskId &&
                    task.packageName == expected.packageName &&
                    !task.isDenzaPickerBase()
            }
        } == true
        val survivorExpected = if (liveAppPresent) {
            expected
        } else {
            SplitPickerObservedPane(hostTaskId = expected.hostTaskId)
        }

        val closedIds = expectedPanes[previousOwner.other()]?.let { pane ->
            setOfNotNull(pane.hostTaskId, pane.appTaskId)
        }.orEmpty()
        val closedTasksInNativeRoots = state.roots.asSequence()
            .filter { candidate -> candidate.displayId == MAIN_DISPLAY_ID }
            .flatMap { candidate -> candidate.tasks.asSequence() }
            .any { task -> task.id in closedIds && task.rootId in nativeRootIds }
        if (closedTasksInNativeRoots) {
            return SplitCollapseRead(null, "задачи закрытой панели ещё в панельных корнях")
        }

        val expectedIds = setOfNotNull(
            survivorExpected.hostTaskId,
            survivorExpected.appTaskId,
        )
        if (root.tasks.any { task -> !task.isEmptyRootMarker() && task.id !in expectedIds }) {
            return SplitCollapseRead(null, "в корне выжившего лишние задачи")
        }

        val pickerInRoot = root.tasks.singleOrNull { task ->
            task.id == expected.hostTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        }
        var reattachedFromRootId: Int? = null
        if (pickerInRoot == null) {
            val detachedPicker = state.roots.asSequence()
                .filter { candidate -> candidate.displayId == MAIN_DISPLAY_ID }
                .flatMap { candidate -> candidate.tasks.asSequence() }
                .singleOrNull { task ->
                    task.id == expected.hostTaskId &&
                        task.rootId !in nativeRootIds &&
                        task.isDenzaPickerBase() &&
                        task.matchesAnyComponent(pickerComponents)
                }
                ?: return SplitCollapseRead(null, "пикер выжившего не найден по identity")
            val originalRootId = detachedPicker.rootId
            try {
                commands.moveTask(detachedPicker.id, survivorRootId, toTop = false)
                reattachedFromRootId = originalRootId
                world.pause(ROOT_SETTLE_MS)
                check(world.callInt("service call activity_task 30") == survivor.fullArea) {
                    "Split изменился при возврате picker ${detachedPicker.id}"
                }
                commands.normalizeTaskToRoot(detachedPicker.id, survivorRootId)
            } catch (error: Throwable) {
                runCatching { commands.moveTask(detachedPicker.id, originalRootId, toTop = false) }
                    .onFailure(error::addSuppressed)
                throw error
            }
        }

        val settled = settledCollapsedPane(
            survivor = survivor,
            rootId = survivorRootId,
            expected = survivorExpected,
            pickerComponents = pickerComponents,
        )
        if (settled != null) return SplitCollapseRead(settled, "adopted")
        val rollbackRootId = reattachedFromRootId
            ?: return SplitCollapseRead(null, "выживший не устоялся после наблюдения")
        val error = IllegalStateException(
            "Split изменился после возврата picker ${survivorExpected.hostTaskId}",
        )
        runCatching {
            commands.moveTask(survivorExpected.hostTaskId, rollbackRootId, toTop = false)
        }
            .onFailure(error::addSuppressed)
        throw error
    }

    private fun settledCollapsedPane(
        survivor: SplitPane,
        rootId: Int,
        expected: SplitPickerObservedPane,
        pickerComponents: Set<String>,
    ): SplitPickerLivePane? {
        val settledRoot = world.snapshot().root(rootId) ?: return null
        val picker = settledRoot.tasks.singleOrNull { task ->
            task.id == expected.hostTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        } ?: return null
        if (picker.bounds != settledRoot.bounds) return null
        val app = expected.appTaskId?.let { appTaskId ->
            settledRoot.tasks.singleOrNull { task ->
                task.id == appTaskId &&
                    task.packageName == expected.packageName &&
                    !task.isDenzaPickerBase() &&
                    !task.isNativeSplitBootstrap() &&
                    task.bounds == settledRoot.bounds
            } ?: return null
        }
        if (settledRoot.tasks.size != if (app == null) 1 else MAX_TASKS_PER_PANE) return null
        val top = settledRoot.resolvedTopTask() ?: return null
        if (top.id != (app?.id ?: picker.id)) return null
        if (app == null && !picker.matchesAnyTopComponent(pickerComponents)) return null
        return SplitPickerLivePane(
            pane = survivor,
            hostTaskId = picker.id,
            appTaskId = app?.id,
            appPackageName = app?.packageName,
        )
    }

    /**
     * Which recorded pane a native collapse closed, proven by existence alone (1.8.2). Read-only.
     *
     * The firmware's "Release to close" detaches tasks alive, and during its two-pass teardown the
     * full postcondition of [settledCollapsedPane] cannot honestly be proven: that set stays the
     * gate of physically reattaching the survivor's picker ([readCollapsedSession]), not of
     * forgetting a selection. The collapse itself is proven by existence: at area 1/2 over a
     * recorded two-pane scene, a pane whose recorded tasks - host AND app, by exact identity - are
     * missing from BOTH panel roots has been closed.
     *
     * That signature tells a collapse from the crash of 1.7.3, where the host picker lives in its
     * root and only the app is gone - that pane is not collapsed, it goes APP→PICKER. A pane with
     * no recorded app is collapsed when its host is gone.
     *
     * WHICH pane collapsed is the area's word alone: the tx30 map names the survivor - area 1 is
     * PRIMARY, area 2 SECONDARY - and the other one is closed. Existence is the condition of the
     * fact, not the source of the name. So at area 1/2 there are three answers. Both panes left
     * the roots: the area names the survivor (a narrow survivor that went fullscreen with the
     * death of its picker base leaves the panel roots too). One left, and it is the one the area
     * calls closed: the same fact, confirmed. One left, but the area calls it the SURVIVOR: two
     * reads of one world disagree (a transient beat, a task of the closed pane lingering in a
     * root), and the answer is a refusal - closing a slot on it would forget the user's choice,
     * and the delayed retry reads the world again.
     *
     * The end of a scene has nothing to do with this: it lives under a cover (area 0/4), which
     * never reaches these lines.
     */
    fun readCollapsedPaneByExistence(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead {
        val area = world.callInt("service call activity_task 30")
        val survivorByArea = when (area) {
            AREA_PRIMARY_FULL -> SplitPane.PRIMARY
            AREA_SECONDARY_FULL -> SplitPane.SECONDARY
            else -> return SplitCollapsedPaneRead(null, null, "area=$area")
        }
        if (expectedPanes.keys != SplitPane.entries.toSet()) {
            return SplitCollapsedPaneRead(null, null, "сцена не записана двухпанельной")
        }
        if (!expectedPanes.isCompleteTwoPaneRecord()) {
            return SplitCollapsedPaneRead(null, null, "запись сцены неполна")
        }
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val panelTasks = roots.values.mapNotNull(state::root).flatMap(SplitRootTask::tasks)
        val absent = SplitPane.entries.filter { pane ->
            val expected = expectedPanes.getValue(pane)
            val hostPresent = panelTasks.any { task ->
                task.id == expected.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            }
            val appPresent = expected.appTaskId?.let { appTaskId ->
                panelTasks.any { task ->
                    task.id == appTaskId &&
                        task.packageName == expected.packageName &&
                        !task.isDenzaPickerBase()
                }
            } == true
            !hostPresent && !appPresent
        }
        // The area alone names the collapsed pane; existence confirms that answer, it does not
        // replace it. A pane gone from the roots that the area calls the SURVIVOR is two reads
        // disagreeing, and the answer is a refusal (see the KDoc).
        val collapsedByArea = survivorByArea.other()
        return when {
            absent.isEmpty() -> SplitCollapsedPaneRead(
                null,
                null,
                "ни одна панель не покинула панельные корни целиком",
            )
            absent.size > 1 -> SplitCollapsedPaneRead(
                collapsedByArea,
                survivorByArea,
                "collapsed: выживший назван area=$area",
            )
            absent.single() == collapsedByArea ->
                SplitCollapsedPaneRead(collapsedByArea, survivorByArea, "collapsed")
            else -> SplitCollapsedPaneRead(
                null,
                null,
                "панель, покинувшая корни, названа выжившей area=$area",
            )
        }
    }

    /**
     * The collapse proof that outlives the cover: the panel containers keep the geometry the
     * firmware gave them.
     *
     * Both other proofs start from the `area` and are blind at 0/4, and the area holds 1/2 for
     * less than a second: a collapsing swipe answers 3→2 after about 0.8 s, and Home takes it to 0
     * about 0.2 s after the touch. A reconcile that starts with the divider's settle
     * ([DIVIDER_RECONCILE_SETTLE_MS]) never looks inside that window.
     *
     * The firmware leaves a trace the cover does not erase: a collapse STRETCHES the survivor's
     * panel container over the whole screen and leaves it so. Measured on this car:
     * - collapsed, then Home: roots (tx118) `[1704,112][2536,1472]` and `[0,0][2560,1600]`;
     * - an ordinary Home over a live pair: `[1704,112][2536,1472]` and `[24,112][1680,1472]`, no
     *   root inside the other.
     * A false close is strictly worse than a missed one, so the nesting of the roots is the
     * firmware's mandatory first word here, not a guess of the product's.
     *
     * The surviving pane is NOT named by the `area`: on this firmware a collapse always answers
     * area 2 and moves the survivor into the SECONDARY container, from either side of the divider.
     * It is named by the exact identity of the WINDOW the firmware stretched - the pane's app, or
     * its picker base when the pane has no app ([isRecordedWindowOf]); with an app, the base keeps
     * its panel bounds under the cover. A pane counts as closed only when its recorded tasks have
     * left both panel roots. Read-only.
     *
     * The read returns BOTH names: `collapsed` is the logical pane whose selection is closed
     * (`survivor.other()`), `survivorPane` the stretched container, the pane where the survivor
     * physically stayed (contract section 5, "К 1.8").
     */
    fun collapsedPaneByPanelBounds(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead {
        if (expectedPanes.keys != SplitPane.entries.toSet()) {
            return SplitCollapsedPaneRead(null, null, "сцена не записана двухпанельной")
        }
        if (!expectedPanes.isCompleteTwoPaneRecord()) {
            return SplitCollapsedPaneRead(null, null, "запись сцены неполна")
        }
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val paneBounds = SplitPane.entries.associateWith { pane ->
            state.root(roots.getValue(pane))?.bounds
                ?: return SplitCollapsedPaneRead(null, null, "$pane: контейнера нет")
        }
        val stretched = SplitPane.entries.singleOrNull { pane ->
            paneBounds.getValue(pane).strictlyContains(paneBounds.getValue(pane.other()))
        } ?: return SplitCollapsedPaneRead(null, null, "панельные корни не вложены")
        val stretchedBounds = paneBounds.getValue(stretched)
        val tasks = world.mainDisplayTasks()
        val survivors = SplitPane.entries.filter { pane ->
            val expected = expectedPanes.getValue(pane)
            tasks.any { task ->
                task.bounds == stretchedBounds &&
                    task.isRecordedWindowOf(expected, pickerComponents)
            }
        }
        val survivor = survivors.singleOrNull()
            ?: return SplitCollapsedPaneRead(
                null,
                null,
                "растянутое окно не названо панелью: совпадений ${survivors.size}",
            )
        val collapsed = survivor.other()
        val panelTaskIds = roots.values.mapNotNull(state::root)
            .flatMap(SplitRootTask::tasks)
            .mapTo(mutableSetOf(), SplitTask::id)
        val expectedCollapsed = expectedPanes.getValue(collapsed)
        if (
            expectedCollapsed.hostTaskId in panelTaskIds ||
            expectedCollapsed.appTaskId?.let { appTaskId -> appTaskId in panelTaskIds } == true
        ) {
            return SplitCollapsedPaneRead(
                null,
                null,
                "задачи закрытой панели ещё в панельных корнях",
            )
        }
        val proof = if (expectedPanes.getValue(survivor).appTaskId == null) {
            "пикер выжившего растянут на весь экран"
        } else {
            "приложение выжившего растянуто на весь экран"
        }
        // The stretched CONTAINER names the survivor's pane, not its old label: it is the pane
        // the firmware left the survivor in (contract section 5, "К 1.8").
        return SplitCollapsedPaneRead(collapsed, stretched, "collapsed: $proof")
    }

    /**
     * The pane's recorded WINDOW: its application, or its picker base when no app is recorded.
     *
     * It is the one thing the firmware stretches on a collapse, proven by the same exact identity
     * as everywhere: task id plus package for an app, task id plus our own component for a base
     * (invariants 3, 4).
     */
    private fun SplitTask.isRecordedWindowOf(
        expected: SplitPickerObservedPane,
        pickerComponents: Set<String>,
    ): Boolean = when (val appTaskId = expected.appTaskId) {
        null -> id == expected.hostTaskId &&
            isDenzaPickerBase() &&
            matchesAnyComponent(pickerComponents)
        else -> id == appTaskId &&
            packageName == expected.packageName &&
            !isDenzaPickerBase()
    }

    /**
     * A recorded scene the world can be judged by at all: every pane has a base, and an app is
     * recorded either whole (task AND package) or not at all (U5).
     */
    private fun Map<SplitPane, SplitPickerObservedPane>.isCompleteTwoPaneRecord(): Boolean =
        values.none { expected ->
            expected.hostTaskId <= 0 ||
                ((expected.appTaskId == null) != (expected.packageName == null)) ||
                (expected.appTaskId != null &&
                    (expected.appTaskId <= 0 || expected.packageName.isNullOrBlank()))
        }

    private companion object {
        const val DIVIDER_RECONCILE_SETTLE_MS = 1_500L
    }
}

/**
 * What one read of a collapsed owned session concluded, and why.
 *
 * [reason] names the fail-closed predicate that refused - a diagnostic line, never a user-facing
 * message - so an unproven collapse says which gate disagreed instead of a silent `null` (U5).
 */
internal class SplitCollapseRead(
    val pane: SplitPickerLivePane?,
    val reason: String,
)

/**
 * The existence proof's edition of [SplitCollapseRead]: which pane fell, or why it is unknown.
 *
 * [collapsed] is the LOGICAL pane whose selection is closed; [survivorPane] is the PHYSICAL pane
 * the firmware left the survivor in, set exactly when [collapsed] is. On this firmware they need
 * not be opposite: a collapse moves the survivor into the SECONDARY container from either side
 * (contract section 5, "К 1.8").
 */
internal class SplitCollapsedPaneRead(
    val collapsed: SplitPane?,
    val survivorPane: SplitPane?,
    val reason: String,
)
