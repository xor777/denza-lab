package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME

/**
 * The reads that decide whether the scene on the car is the product's own, and what is left of it.
 *
 * All of them are read-only: whether both pane roots hold exactly our permanent base and at most
 * one application ([readOwnedSession]), whether the one pane of a single-pane world does
 * ([readOwnedSelection]), which pane a picker callback belongs to ([observePickerTask]), which of
 * our pickers are visible, and whether the recorded members of a scene are still alive. A refusal
 * names the pane and the predicate that disagreed (U5). The only waits are the one short second
 * read of a scene's end ([confirmSceneEndMembersDead], [confirmDeadRecordedApps]).
 */
internal class SplitOwnedScene(
    private val world: SplitWorld,
) {
    /**
     * Adopts an already-running product scene without mutating the firmware roots.
     *
     * Package replacement restarts Denza Apps but does not remove the two standalone picker
     * tasks. Rebuilding that still-valid scene races SmartMulti's own focus/restore controller.
     * Only accept the scene when both native roots have exactly our permanent base and at most
     * one ordinary app - counted as applications, not as tasks, because a live app may legitimately
     * own several of them (1.5.2); anything less certain falls back to explicit reconstruction.
     */
    fun existingOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): Map<SplitPane, SplitPickerLivePane>? =
        readOwnedSession(pickerComponents, expectedApps).scene

    /**
     * The same read, with the reason it refused: the pane and the predicate that disagreed, so a
     * scene rebuilt over a live one says why it was not adopted (U5, 1.3.2).
     */
    fun readOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitSceneRead {
        val area = world.callInt("service call activity_task 30")
        if (area != AREA_BALANCED_SPLIT && area != AREA_FULL_IVI && area != AREA_HOME) {
            return SplitSceneRead(null, "area=$area")
        }
        return readOwnedScene(area, pickerComponents, expectedApps)
    }

    /**
     * What the scene is for the read-back of a selection when one pane fills the screen.
     *
     * One pane over the whole screen is a legitimate scene of the product (`FULL(x)`, axis 2.3),
     * and the selection already accepts it: with the area at the pane's full area and the other
     * root vacant, its postcondition is proven on area 1/2 ([SplitSelect]). So at area 1/2 this
     * read looks at the surviving pane with exactly the predicates [readOwnedSession] applies to
     * each pane of a live scene, and nothing weaker: one base of ours, at most one application,
     * neither another base nor the stock bootstrap, the picker at the window's size, the top task
     * either that picker or an application within the root's bounds. At area 3 it is
     * [readOwnedSession]'s read.
     *
     * The other root must not hold a base of ours: that would be a two-pane scene the area has
     * not caught up with, which belongs to [readOwnedSession]. Other tasks there are the user's,
     * detached alive and invisible by the firmware (1.8.2, invariant 3); they prove nothing and
     * stand in nothing's way.
     *
     * It does not replace [readOwnedSession]: the open's adoption (1.3.4) and the collapse
     * reconcile still refuse at area 1/2, where the closed pane has to become a fresh picker and
     * not be adopted as `FULL`.
     */
    fun readOwnedSelection(pickerComponents: Set<String>): SplitSceneRead {
        val area = world.callInt("service call activity_task 30")
        if (area == AREA_BALANCED_SPLIT) {
            return readOwnedScene(area, pickerComponents, emptyMap())
        }
        val survivor = SplitPane.entries.firstOrNull { pane -> pane.fullArea == area }
            ?: return SplitSceneRead(null, "area=$area")
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val other = survivor.other()
        val otherHoldsOwnBase = state.root(roots.getValue(other))?.tasks.orEmpty().any { task ->
            task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
        }
        if (otherHoldsOwnBase) {
            return SplitSceneRead(null, "area=$area, но $other держит базу")
        }
        val root = state.root(roots.getValue(survivor))
            ?: return SplitSceneRead(null, "$survivor: контейнера нет")
        return readOwnedPane(
            pane = survivor,
            root = root,
            area = area,
            sceneOnScreen = true,
            pickerComponents = pickerComponents,
            expected = null,
        )
    }

    /**
     * Both panes of a whole scene: the body of [readOwnedSession] with the area already read.
     *
     * It stands apart so that [readOwnedSelection] at area 3 answers with the very same read
     * without asking the area again: one more `activity_task 30` costs time, and may meet another
     * world than the one the branch was decided in.
     */
    private fun readOwnedScene(
        area: Int,
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp>,
    ): SplitSceneRead {
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val panes = mutableMapOf<SplitPane, SplitPickerLivePane>()
        SplitPane.entries.forEach { pane ->
            val root = state.root(roots.getValue(pane))
                ?: return SplitSceneRead(null, "$pane: контейнера нет")
            val read = readOwnedPane(
                pane = pane,
                root = root,
                area = area,
                sceneOnScreen = area == AREA_BALANCED_SPLIT,
                pickerComponents = pickerComponents,
                expected = expectedApps[pane],
            )
            panes += read.scene ?: return read
        }
        return SplitSceneRead(panes, "adoptable")
    }

    /**
     * One pane, by the predicates that make it ours - written once for every reader.
     *
     * [readOwnedSession] applies them to both panes of a balanced or covered scene,
     * [readOwnedSelection] to the one surviving pane at area 1/2. Success is a one-entry map; a
     * refusal names the pane and the predicate that disagreed (U5).
     *
     * [sceneOnScreen] means the pane is visible: then what is visible names the top task
     * ([SplitRootTask.resolvedTopTask]). A covered world shows no top task, and it has to be
     * proven by a recorded id or by our own picker.
     */
    private fun readOwnedPane(
        pane: SplitPane,
        root: SplitRootTask,
        area: Int,
        sceneOnScreen: Boolean,
        pickerComponents: Set<String>,
        expected: SplitPickerExpectedApp?,
    ): SplitSceneRead {
        val pickers = root.tasks.filter { task ->
            task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
        }
        val picker = pickers.singleOrNull()
        // A pane is its base and ONE application; how many living tasks the firmware uses for that
        // application is the firmware's business (1.5.2: one tap can bring two tasks of a package
        // into the root, both visible, with the right pane on screen). Counted as tasks, such a
        // pane would read as foreign, and a refused adoption rebuilds the live scene and restarts
        // the playing app (U2, 1.3.5) - so residents are counted as applications.
        val residents = root.tasks.filterNot { task ->
            task.id == picker?.id || task.isEmptyRootMarker()
        }
        if (
            picker == null ||
            residents.any { it.isDenzaPickerBase() || it.isNativeSplitBootstrap() } ||
            residents.distinctBy { it.packageName }.size > 1
        ) {
            return SplitSceneRead(
                null,
                "$pane: пикеров ${pickers.size}, задач ${root.tasks.size}",
            )
        }
        if (picker.bounds != root.bounds) {
            return SplitSceneRead(null, "$pane: пикер не по размеру окна")
        }

        val top = when {
            sceneOnScreen -> root.resolvedTopTask()
            // The scene is covered, so `am stack list` marks every child hidden and repeats
            // only the old root-top component. The exact task id and package of the app this
            // process itself recorded is the narrow proof that lets it be raised (invariant 4).
            expected != null -> root.resolveExpectedCoveredApp(expected)
            // No app to name. Under a fullscreen window the pane's own picker reporting itself
            // is still accepted; under Home nothing is guessed at all - the root holding one
            // task, our picker, is the proof (decided 2026-08-23).
            area == AREA_HOME -> picker.takeIf { root.tasks.size == 1 }
            else -> root.resolvedCoveredTopTask()?.takeIf { task ->
                task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
            }
        } ?: return SplitSceneRead(
            null,
            "$pane: верхняя задача не подтверждена (area=$area, " +
                "ожидалось ${expected?.taskId ?: "-"})",
        )
        val app = if (top.id == picker.id) {
            if (!picker.matchesAnyTopComponent(pickerComponents)) {
                return SplitSceneRead(null, "$pane: пикер не верхний")
            }
            null
        } else {
            if (
                top.isDenzaPickerBase() ||
                top.isNativeSplitBootstrap() ||
                top.bounds != root.bounds
            ) {
                return SplitSceneRead(null, "$pane: верхняя задача ${top.id} чужая")
            }
            top
        }
        return SplitSceneRead(
            mapOf(
                pane to SplitPickerLivePane(
                    pane = pane,
                    hostTaskId = picker.id,
                    appTaskId = app?.id,
                    appPackageName = app?.packageName,
                ),
            ),
            "adoptable",
        )
    }

    /** Resolves a picker callback by its task identity; the Activity class never defines a pane. */
    fun observePickerTask(
        hostTaskId: Int,
        pickerComponents: Set<String>,
    ): SplitPickerPaneObservation? {
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        // BYD can temporarily strand both permanent picker bases in one root while moving the
        // visible app to the other during divider resize. That is not a picker reveal: emitting
        // one here would erase the recorded APP ownership before reconcileDividerResize repairs
        // the bases. Fail closed until every native root has at most one picker identity.
        if (
            roots.values.mapNotNull(state::root).any { root ->
                root.tasks.count { task ->
                    task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
                } > 1
            }
        ) {
            return null
        }
        val pane = SplitPane.entries.firstOrNull { candidate ->
            state.root(roots.getValue(candidate))?.tasks?.any { task ->
                task.id == hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } == true
        } ?: return null
        val root = state.root(roots.getValue(pane)) ?: return null
        val picker = root.tasks.first { it.id == hostTaskId }
        return SplitPickerPaneObservation(
            pane = pane,
            hostTaskId = picker.id,
            nativeHostVisible = false,
            pickerVisible = picker.visible && picker.matchesAnyTopComponent(pickerComponents),
            observedTaskIds = root.tasks.mapTo(mutableSetOf(), SplitTask::id),
        )
    }

    /**
     * Every product picker currently visible in a panel root, by task id.
     *
     * The hint that carries no host id is ambiguous only until this list is read: a window event
     * over a scene of ours whose every visible picker is a recorded member of that scene names no
     * single task, and proves the scene all the same. Read-only, and the same two reads
     * [singleVisiblePickerTaskId] already pays for.
     */
    fun visiblePickerTaskIds(pickerComponents: Set<String>): List<Int> {
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        return roots.values.asSequence()
            .mapNotNull(state::root)
            .flatMap { root -> root.tasks.asSequence() }
            .filter { task ->
                task.visible &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyTopComponent(pickerComponents)
            }
            .map(SplitTask::id)
            .toList()
    }

    /** Resolves a product-picker window hint only when one native root has one visible picker. */
    fun singleVisiblePickerTaskId(pickerComponents: Set<String>): Int? =
        visiblePickerTaskIds(pickerComponents).singleOrNull()

    /**
     * Whether every recorded member of the scene is still alive on the main display under its
     * exact recorded identity - a picker by task id and our own component, an app by task id and
     * package (invariant 5).
     *
     * On Home the firmware may empty the focused pane's root, detaching living tasks into the
     * display area with their panel bounds kept. A detached member of a live covered scene is not
     * an orphan, so this looks at the whole main display, not at the panel roots, which prove
     * nothing here. A dead member is a native end: Back in the wide picker of "picker | picker"
     * kills its task, a swipe or "clear all" kills them all. Read-only.
     */
    fun allRecordedMembersAlive(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean {
        if (scene.isEmpty()) return false
        val tasks = world.mainDisplayTasks()
        return scene.values.all { observed ->
            tasks.any { task ->
                task.id == observed.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } && (observed.appTaskId == null || tasks.any { task ->
                task.id == observed.appTaskId &&
                    task.packageName == observed.appPackageName &&
                    !task.isDenzaPickerBase()
            })
        }
    }

    /**
     * The panes whose recorded APPLICATION is no longer alive on the main display under its exact
     * identity - task id plus package, never a picker base.
     *
     * For a scene with recorded applications this is the anchor of its end. The picker bases are
     * left out on purpose: a base Home evicted dies nondeterministically while the user's apps
     * live, and its death proves only that the base is gone, not that the scene ended.
     *
     * The answer is per pane: the set of dead panes tells 1.7.3 (one died - its pane is freed,
     * the neighbour lives) from 1.7.5 (all died - the scene ended). Read-only.
     */
    fun deadRecordedApps(scene: Map<SplitPane, SplitPickerLivePane>): Set<SplitPane> {
        val apps = scene.filterValues { observed -> observed.appTaskId != null }
        check(apps.isNotEmpty()) { "У записанной сцены нет приложений-якорей" }
        val tasks = world.mainDisplayTasks()
        return apps.filterValues { observed ->
            tasks.none { task ->
                task.id == observed.appTaskId &&
                    task.packageName == observed.appPackageName &&
                    !task.isDenzaPickerBase()
            }
        }.keys
    }

    /**
     * One second read of a scene's end after a short pause - only on the positive branch, before
     * the mutations of the cleanup. A dead anchor seen in the middle of the firmware's two-pass
     * teardown may be half of it; a living or unreadable answer needs no second read.
     *
     * It is the anchor of a "picker | picker" scene, which has no recorded applications at all:
     * its question is whether the MEMBERS live, so its answer is a boolean. Read-only, exactly
     * one pause and one second read.
     */
    fun confirmSceneEndMembersDead(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean {
        world.pause(SCENE_END_CONFIRM_SETTLE_MS)
        return !allRecordedMembersAlive(scene, pickerComponents) && world.sceneCovered()
    }

    /**
     * The same second read for the anchor of APPLICATIONS, with a per-pane answer.
     *
     * A boolean would lie twice here: "one is dead" is not the end of the whole scene, and whether
     * the scene is still covered has to be asked after the pause either way - when all are dead
     * (1.7.5) and when one pane is (1.7.3). So the answer is what the second read saw: the set of
     * dead panes and the cover. Read-only, exactly one pause and one second read, like
     * [confirmSceneEndMembersDead].
     */
    fun confirmDeadRecordedApps(
        scene: Map<SplitPane, SplitPickerLivePane>,
    ): SplitDeadAppsConfirmation {
        world.pause(SCENE_END_CONFIRM_SETTLE_MS)
        return SplitDeadAppsConfirmation(
            deadPanes = deadRecordedApps(scene),
            covered = world.sceneCovered(),
        )
    }

    private companion object {
        /**
         * The pause before the second read of a scene's end. It need not outlast the firmware's
         * teardown, only half a beat of its snapshot; it is paid once, and only on the positive
         * branch, which has the cleanup's mutations ahead of it.
         */
        const val SCENE_END_CONFIRM_SETTLE_MS = 400L
    }
}

internal data class SplitPickerLivePane(
    val pane: SplitPane,
    val hostTaskId: Int,
    val appTaskId: Int?,
    val appPackageName: String?,
)

/**
 * What the second read of the applications' anchor saw.
 *
 * [deadPanes] are the panes whose recorded applications are dead on the second read too; [covered]
 * says whether the reason to look is still there (area 0/4, invariant 5). An empty [deadPanes] or
 * a cover that has lifted is half a beat of the firmware, not an outcome: no mutation follows.
 */
internal data class SplitDeadAppsConfirmation(
    val deadPanes: Set<SplitPane>,
    val covered: Boolean,
)

/**
 * What one read of the owned scene concluded, and why.
 *
 * [reason] is a diagnostic line, never a user-facing message: it names the predicate and the pane
 * that disagreed, so the next live run says which one it was instead of "nothing of ours".
 */
internal class SplitSceneRead(
    val scene: Map<SplitPane, SplitPickerLivePane>?,
    val reason: String,
)
