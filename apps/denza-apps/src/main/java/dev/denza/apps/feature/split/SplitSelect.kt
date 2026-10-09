package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.APP_PLACEMENT_CONFIRM_ATTEMPTS
import dev.denza.apps.feature.split.SplitWorld.Companion.APP_PLACEMENT_CONFIRM_INTERVAL_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.ROOT_SETTLE_MS

/**
 * A tap in a picker: the chosen application launched into that picker's pane as a task above the
 * picker, and the selection proven on the scene the firmware actually settled (contract 1.5).
 *
 * The side is the firmware's choice and is recorded, not argued with (1.5.3); the pane's window is
 * whichever task of the chosen package ended on top (1.5.2). A failed attempt removes only what it
 * started and leaves the picker working (1.5.7).
 */
internal class SplitSelect(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
    private val gate: SplitGate,
) {
    fun selectApp(
        pickerTaskId: Int,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement {
        gate.ensureGateOpen()
        val roots = world.nativeRootIds()
        val before = world.snapshot()
        val pane = SplitPane.entries.firstOrNull { candidate ->
            before.root(roots.getValue(candidate))?.tasks?.any { task ->
                task.id == pickerTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } == true
        } ?: error("Пикер больше не находится в split-контейнере")
        val targetRootId = roots.getValue(pane)
        val otherRootId = roots.getValue(pane.other())
        val expectedArea = expectedSelectionArea(
            pane = pane,
            currentArea = world.callInt("service call activity_task 30"),
            otherRootVacant = before.root(otherRootId)
                ?.tasks
                ?.all { it.isEmptyRootMarker() } == true,
        )

        // Another saved-pair member may legitimately be projected to the instrument display.
        // That task no longer reserves either IVI pane. Only selecting the exact same external
        // package is forbidden below.
        check(before.roots.asSequence()
            .filter { it.displayId != MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .none { it.packageName == target.packageName }
        ) {
            "Приложение уже открыто на другом экране"
        }
        val pickerHost = before.root(targetRootId)?.tasks
            ?.firstOrNull {
                it.id == pickerTaskId &&
                    it.isDenzaPickerBase() &&
                    it.matchesAnyComponent(pickerComponents)
            }
        check(pickerHost != null) { "Пикер этого окна больше не найден" }
        // Only the other native pane is a live duplicate. After OEM collapse the old app task
        // can linger briefly in the hidden full-IVI root; a subsequent launch legitimately
        // replaces that stale task and must not be rejected or "preserved" as another window.
        //
        // A duplicate is a WINDOW, never the product's furniture (U3, invariant 3): the other
        // pane's picker base carries our package and is always there, so counted, it would forbid
        // selecting Denza Apps (a `singleTask` MainActivity) in any pane at all (1.5.3).
        val duplicatePeerTasks = before.root(otherRootId)?.tasks.orEmpty()
            .filter { task ->
                !task.isDenzaPickerBase() && task.packageName == target.packageName
            }
        check(duplicatePeerTasks.isEmpty() || target.launchMode < LAUNCH_MODE_SINGLE_TASK) {
            "Это приложение не поддерживает два окна"
        }
        commands.ensureSupported(target.packageName)

        // A picker tap is authoritative proof that this pane is being selected. Free its exact
        // permanent base before requiring the picker to be the root top (invariant 3; contract,
        // note under 1.5): only what is ours by exact component goes - a second base or the stock
        // bootstrap stuck in this root. Any other task of the root is the user's (a hub pulled in
        // natively is our package but not our component, U3) and is evicted alive into the full
        // root; this operation has created nothing yet, so nothing here is "created by it".
        val (ownArtifacts, foreignOccupants) = before.root(targetRootId)?.tasks.orEmpty()
            .filterNot { task -> task.id == pickerHost.id || task.isEmptyRootMarker() }
            .partition { task -> task.isOwnSplitComponent() }
        commands.removeTasksSafely(ownArtifacts)
        commands.evictToFullRoot(foreignOccupants)

        val clearedState = world.snapshot()
        val clearedPicker = clearedState.root(targetRootId)?.tasks?.firstOrNull { task ->
            task.id == pickerHost.id &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        }
        check(
            clearedPicker != null &&
            clearedPicker.visible &&
                clearedPicker.matchesAnyTopComponent(pickerComponents)
        ) { "Пикер этого окна не освободился перед запуском приложения" }
        val baselineTasks = clearedState.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .toList()
        val baselineTaskIds = baselineTasks.mapTo(mutableSetOf(), SplitTask::id)
        val preservedTargetTaskRoots = baselineTasks.asSequence()
            .filter { task ->
                task.rootId == otherRootId &&
                    task.packageName == target.packageName
            }
            .associate { task -> task.id to task.rootId }

        try {
            val launchedTask = launchTargetDirectIntoRoot(
                target = target,
                pane = pane,
                rootIds = roots,
                // 1.5.2, and only here: the other pane still holds this package, so this tap asks
                // for a genuinely independent second window rather than for the task it already has.
                secondInstance = duplicatePeerTasks.isNotEmpty(),
                excludedTaskIds = preservedTargetTaskRoots.keys,
            )
            // The side is the firmware's choice and the product records the fact (1.5.3): the
            // task may have landed in the other pane, and the slot, the host picker and the
            // postcondition take the actual side rather than rolling back a window that stands.
            val settledPane = SplitPane.entries.first { candidate ->
                roots.getValue(candidate) == launchedTask.rootId
            }
            val settledRootId = roots.getValue(settledPane)
            val settledPickerHost = if (settledPane == pane) {
                pickerHost
            } else {
                world.snapshot().root(settledRootId)?.tasks?.firstOrNull { task ->
                    task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
                } ?: error("Пикер панели, куда прошивка поставила приложение, не найден")
            }
            // The pane's window is the task of the target that is actually on top, not the one
            // the launch addressed (1.5.2).
            val settledAppTaskId = settledSelectedAppTaskId(
                rootId = settledRootId,
                pickerHostTaskId = settledPickerHost.id,
                target = target,
                launchedTaskId = launchedTask.id,
            )
            // A second living task of the SELECTED package is a legitimate resident of this pane,
            // not debris: the firmware brought it here itself, and evicting a visible window to
            // root 4 does not send it to the background - root 4 holds none - but puts it over
            // the whole scene with its pane bounds (U5, invariant 9).
            commands.sweepRootsToBaseAndApp(
                keepByRoot = mapOf(settledRootId to setOf(settledPickerHost.id, settledAppTaskId)),
                preexistingTaskIds = baselineTaskIds,
                residentByRoot = mapOf(settledRootId to target.packageName),
            )
            commands.normalizeTaskToRoot(settledAppTaskId, settledRootId)
            world.pause(ROOT_SETTLE_MS)

            return awaitSelectedAppPlacement(
                pane = settledPane,
                rootId = settledRootId,
                pickerHost = settledPickerHost,
                target = target,
                pickerComponents = pickerComponents,
                expectedArea = expectedArea,
                preservedTargetTaskRoots = preservedTargetTaskRoots,
            )
        } catch (error: Throwable) {
            runCatching {
                cleanupLaunchAttempt(
                    packageName = target.packageName,
                    baselineTaskIds = baselineTaskIds,
                    preservedTargetTaskRoots = preservedTargetTaskRoots,
                )
                requirePickerReady(targetRootId, pickerHost.id, pickerComponents)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun expectedSelectionArea(
        pane: SplitPane,
        currentArea: Int,
        otherRootVacant: Boolean,
    ): Int = when {
        currentArea == AREA_BALANCED_SPLIT -> AREA_BALANCED_SPLIT
        currentArea == pane.fullArea && otherRootVacant -> currentArea
        else -> error("Пикер больше не находится в рабочем окне")
    }

    /**
     * The target's launch with the live-proven promote into the chosen root, confirmed BY THE
     * FACT (1.5.3). The firmware places a split-capable package by its own side rules and may not
     * give the promote the chosen pane, so success is the target's task settled in EITHER panel
     * root, the one its own [SplitTask.rootId] names; failure is only a task that settled nowhere.
     */
    private fun launchTargetDirectIntoRoot(
        target: SplitLaunchTarget,
        pane: SplitPane,
        rootIds: Map<SplitPane, Int>,
        secondInstance: Boolean,
        excludedTaskIds: Set<Int> = emptySet(),
    ): SplitTask {
        commands.startTargetInPane(pane, target, secondInstance)
        world.pause(APP_LAUNCH_SETTLE_MS)
        val direct = world.awaitTaskMatching { task ->
            task.id !in excludedTaskIds &&
                task.packageName == target.packageName &&
                !task.isOwnSplitComponent()
        }
        commands.promoteTask(direct, rootIds.getValue(pane))
        var settled: SplitTask? = null
        world.awaitSnapshotMatching { state ->
            settled = rootIds.values.asSequence()
                .mapNotNull(state::root)
                .flatMap { root -> root.tasks.asSequence() }
                .firstOrNull { task ->
                    task.id == direct.id && task.packageName == target.packageName
                }
            settled != null
        }
        return settled ?: error("Прямой запуск не вошёл ни в один split-контейнер")
    }

    /**
     * Which task of the target became the pane's window - the world decides, not the launch.
     *
     * The product's launch is `am start` without `MULTIPLE_TASK`, "the package's task, whichever
     * it is", and the firmware may bring more than one task of the package into the pane. The top
     * one is what the user sees, and it becomes the pane's application; the launched task is the
     * answer only when the world names none of the target's tasks the top. The product's own
     * components are never candidates (invariant 3).
     */
    private fun settledSelectedAppTaskId(
        rootId: Int,
        pickerHostTaskId: Int,
        target: SplitLaunchTarget,
        launchedTaskId: Int,
    ): Int {
        val root = world.snapshot().root(rootId) ?: error("Split-контейнер выбранного окна исчез")
        val candidates = root.tasks.filter { task ->
            task.id != pickerHostTaskId &&
                !task.isEmptyRootMarker() &&
                !task.isOwnSplitComponent() &&
                task.packageName == target.packageName
        }
        val top = root.resolvedTopTask()
            ?.id
            ?.takeIf { topId -> candidates.any { candidate -> candidate.id == topId } }
        return top
            ?: candidates.firstOrNull { it.id == launchedTaskId }?.id
            ?: candidates.maxByOrNull(SplitTask::id)?.id
            ?: launchedTaskId
    }

    /**
     * BYD publishes task placement before its split-area controller has necessarily committed the
     * same transition. Treat the launch as successful only after the complete scene agrees twice;
     * otherwise a single stale area/top sample would make cleanup delete an already visible app.
     */
    private fun awaitSelectedAppPlacement(
        pane: SplitPane,
        rootId: Int,
        pickerHost: SplitTask,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
        expectedArea: Int,
        preservedTargetTaskRoots: Map<Int, Int>,
    ): SplitPickerPlacement {
        var stableSamples = 0
        var lastError: Throwable? = null
        repeat(APP_PLACEMENT_CONFIRM_ATTEMPTS) { attempt ->
            val sample = runCatching {
                selectedAppPlacement(
                    pane = pane,
                    rootId = rootId,
                    pickerHost = pickerHost,
                    target = target,
                    pickerComponents = pickerComponents,
                    expectedArea = expectedArea,
                    preservedTargetTaskRoots = preservedTargetTaskRoots,
                )
            }
            sample.getOrNull()?.let { placement ->
                stableSamples += 1
                if (stableSamples >= APP_PLACEMENT_STABLE_SAMPLES) return placement
            }
            sample.exceptionOrNull()?.let { error ->
                stableSamples = 0
                lastError = error
            }
            if (attempt + 1 < APP_PLACEMENT_CONFIRM_ATTEMPTS) {
                world.pause(APP_PLACEMENT_CONFIRM_INTERVAL_MS)
            }
        }
        throw lastError ?: IllegalStateException(
            "Запуск ${target.packageName} не достиг устойчивого состояния",
        )
    }

    /**
     * The selection's postcondition: the TARGET APPLICATION is on top of the pane - not
     * necessarily the task the launch addressed, since a package with two living tasks may have
     * both brought into the pane (1.5.2, 1.5.3, invariant 9).
     *
     * The user's application is proven by the target's package, and the product's own components
     * never count as it (invariant 3, U3): a launch of `dev.denza.apps` is its MainActivity,
     * never a picker base. The slot is recorded by the actual top task, which also becomes the
     * pane's recorded identity.
     */
    private fun selectedAppPlacement(
        pane: SplitPane,
        rootId: Int,
        pickerHost: SplitTask,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
        expectedArea: Int,
        preservedTargetTaskRoots: Map<Int, Int>,
    ): SplitPickerPlacement {
        val root = world.snapshot().root(rootId)
            ?: error("Split-контейнер выбранного окна исчез")
        check(root.tasks.any {
            it.id == pickerHost.id &&
                it.isDenzaPickerBase() &&
                it.matchesAnyComponent(pickerComponents)
        }) {
            "Пикер был удалён из окна"
        }
        val top = root.resolvedTopTask()
            ?: error("В выбранном split-окне нет верхней задачи")
        check(top.packageName == target.packageName && !top.isOwnSplitComponent()) {
            "Приложение ${target.packageName} не стало верхним в выбранном окне"
        }
        check(top.bounds == root.bounds) {
            "Приложение ${target.packageName} не приняло размер выбранного окна"
        }
        check(world.callInt("service call activity_task 30") == expectedArea) {
            "Split не перешёл в рабочее состояние"
        }
        // A pane is its base and ONE application, not two tasks: a living second task of the
        // selected package lies under it and is in nothing's way (1.5.2); a foreign task is, and
        // is a refusal.
        check(
            root.tasks.none { task ->
                task.id != pickerHost.id &&
                    !task.isEmptyRootMarker() &&
                    (task.isOwnSplitComponent() ||
                        task.packageName != target.packageName)
            }
        ) {
            "В split-контейнере осталась посторонняя задача"
        }
        requirePreservedTargetTasks(target.packageName, preservedTargetTaskRoots)
        return SplitPickerPlacement(
            pane = pane,
            hostTaskId = pickerHost.id,
            appTaskId = top.id,
            packageName = target.packageName,
        )
    }

    private fun cleanupLaunchAttempt(
        packageName: String,
        baselineTaskIds: Set<Int>,
        preservedTargetTaskRoots: Map<Int, Int>,
    ) {
        world.snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task -> task.id !in baselineTaskIds && task.packageName == packageName }
            .toList()
            .let(commands::removeTasksSafely)
        restorePreservedTargetTasks(packageName, preservedTargetTaskRoots)
    }

    private fun restorePreservedTargetTasks(
        packageName: String,
        preservedTaskRoots: Map<Int, Int>,
    ) {
        if (preservedTaskRoots.isEmpty()) return
        val current = world.mainDisplayTasks().associateBy(SplitTask::id)
        preservedTaskRoots.forEach { (taskId, rootId) ->
            val task = current[taskId]
                ?: error("Не удалось сохранить уже открытое окно $packageName")
            check(task.packageName == packageName) {
                "Задача уже открытого окна изменила приложение"
            }
            if (task.rootId != rootId) commands.moveTask(taskId, rootId)
        }
        world.pause(ROOT_SETTLE_MS)
        requirePreservedTargetTasks(packageName, preservedTaskRoots)
    }

    private fun requirePreservedTargetTasks(
        packageName: String,
        preservedTaskRoots: Map<Int, Int>,
    ) {
        if (preservedTaskRoots.isEmpty()) return
        val current = world.mainDisplayTasks().associateBy(SplitTask::id)
        preservedTaskRoots.forEach { (taskId, rootId) ->
            val task = current[taskId]
                ?: error("Уже открытое окно $packageName исчезло")
            check(task.rootId == rootId && task.packageName == packageName) {
                "Уже открытое окно $packageName сменило split-контейнер"
            }
        }
    }

    private fun requirePickerReady(
        rootId: Int,
        pickerTaskId: Int,
        pickerComponents: Set<String>,
    ) {
        val root = world.snapshot().root(rootId)
            ?: error("Split-контейнер исчез после неудачного запуска")
        val picker = root.tasks.singleOrNull { task ->
            task.id == pickerTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        } ?: error("Постоянный пикер потерян после неудачного запуска")
        check(
            root.tasks.size == 1 &&
                picker.visible &&
                picker.matchesAnyTopComponent(pickerComponents)
        ) { "Неудачный запуск не освободил пикер для безопасного fallback" }
    }

    private companion object {
        const val LAUNCH_MODE_SINGLE_TASK = 2
        const val APP_LAUNCH_SETTLE_MS = 250L
        /** Only the single-pane selection keeps two samples; a built scene ends in the
         *  operation's own whole-scene read-back instead. */
        const val APP_PLACEMENT_STABLE_SAMPLES = 2
    }
}
