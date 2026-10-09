package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.EXIT_SETTLE_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.MAX_TASKS_PER_PANE
import dev.denza.apps.feature.split.SplitWorld.Companion.TASK_DISCOVERY_ATTEMPTS
import dev.denza.apps.feature.split.SplitWorld.Companion.TASK_DISCOVERY_INTERVAL_MS

/**
 * The navigator's way back from the instrument display into the scene it left (contract 1.10).
 *
 * One pane is chosen and cleared before the task comes back - a vacancy wins, then the pane it
 * came from - and the return is proven by that pane showing exactly the returned task above its
 * picker. A pane that was collapsed while the navigator was away gets it back fullscreen instead
 * (1.10.6).
 */
internal class SplitNavReturn(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
    private val ownedScene: SplitOwnedScene,
    private val builder: SplitSceneBuilder,
) {
    /**
     * Chooses and clears one exact product pane before navigation returns from another display.
     *
     * Vacancy is authoritative. A single visible picker wins; with two vacancies the original
     * pane wins. If both panes are occupied, the original pane is cleared so the return never
     * creates picker + app + navigator in one root. When the native split is no longer active,
     * the original pane is expanded after the task returns instead.
     */
    fun prepareNavigationReturn(
        originalRootTaskId: Int,
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitNavigationReturnPlan {
        val roots = world.nativeRootIds()
        val originalPane = SplitPane.entries.firstOrNull { pane ->
            roots.getValue(pane) == originalRootTaskId
        }
        val hiddenOwnedSession = ownedScene.existingOwnedSession(pickerComponents, expectedApps)
        if (hiddenOwnedSession != null) {
            builder.revealOwnedSession(hiddenOwnedSession, pickerComponents)
        }
        if (world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            return SplitNavigationReturnPlan(
                pane = originalPane,
                rootTaskId = originalRootTaskId,
                hostTaskId = null,
                fullscreen = true,
            )
        }

        val before = world.snapshot()
        val pickerByPane = SplitPane.entries.associateWith { pane ->
            before.root(roots.getValue(pane))?.tasks?.singleOrNull { task ->
                task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
            }
        }
        val vacantPanes = SplitPane.entries.filter { pane ->
            val picker = pickerByPane[pane] ?: return@filter false
            val root = before.root(roots.getValue(pane)) ?: return@filter false
            val top = root.resolvedTopTask() ?: return@filter false
            top.id == picker.id && top.matchesAnyTopComponent(pickerComponents)
        }
        val targetPane = when {
            vacantPanes.size == 1 -> vacantPanes.single()
            vacantPanes.size == 2 && originalPane != null -> originalPane
            vacantPanes.size == 2 -> SplitPane.SECONDARY
            originalPane != null -> originalPane
            else -> null
        }
        if (targetPane == null) {
            return SplitNavigationReturnPlan(
                pane = null,
                rootTaskId = originalRootTaskId,
                hostTaskId = null,
                fullscreen = true,
            )
        }

        val targetRootId = roots.getValue(targetPane)
        val picker = pickerByPane[targetPane]
            ?: return SplitNavigationReturnPlan(
                pane = originalPane,
                rootTaskId = originalRootTaskId,
                hostTaskId = null,
                fullscreen = true,
            )
        val displacedTasks = before.root(targetRootId)?.tasks.orEmpty()
            .filterNot { it.id == picker.id }
            .map { task -> SplitDisplacedTask(task.id, task.packageName) }
        return SplitNavigationReturnPlan(
            pane = targetPane,
            rootTaskId = targetRootId,
            hostTaskId = picker.id,
            fullscreen = false,
            displacedTasks = displacedTasks,
        )
    }

    fun verifyNavigationReturned(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement {
        var lastError: Throwable? = null
        repeat(TASK_DISCOVERY_ATTEMPTS) { attempt ->
            try {
                return verifyNavigationReturnedOnce(
                    plan = plan,
                    taskId = taskId,
                    packageName = packageName,
                    pickerComponents = pickerComponents,
                )
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < TASK_DISCOVERY_ATTEMPTS) {
                    world.pause(TASK_DISCOVERY_INTERVAL_MS)
                }
            }
        }
        throw lastError ?: IllegalStateException("Навигация не вернулась в split-окно")
    }

    private fun verifyNavigationReturnedOnce(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement {
        val pane = plan.pane ?: error("Не выбран split-контейнер возврата")
        val hostTaskId = plan.hostTaskId ?: error("Не найден пикер окна возврата")
        val root = world.snapshot().root(plan.rootTaskId)
            ?: error("Split-контейнер возврата исчез")
        check(root.tasks.any { task ->
            task.id == hostTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        }) { "Пикер исчез из окна возврата" }
        val top = root.resolvedTopTask()
            ?: error("Навигация не появилась в split-окне")
        check(
            top.id == taskId &&
                top.packageName == packageName &&
                top.bounds == root.bounds,
        ) {
            "Навигация не заняла выбранное split-окно"
        }
        check(root.tasks.size <= MAX_TASKS_PER_PANE) {
            "В окне возврата накопилось больше двух задач"
        }
        return SplitPickerPlacement(pane, hostTaskId, taskId, packageName)
    }

    fun returnRecordedTaskFullscreen(
        pane: SplitPane,
        taskId: Int,
        packageName: String,
    ) {
        val before = world.snapshot()
        val task = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId && it.packageName == packageName }
            ?: return
        val paneRootId = world.nativeRootIds().getValue(pane)
        if (task.rootId != paneRootId) return
        commands.moveTask(task.id, paneRootId)
        world.callVoid(
            "service call activity_task 114 i32 " +
                if (pane == SplitPane.PRIMARY) EXPAND_PRIMARY_MODE else EXPAND_SECONDARY_MODE,
        )
        check(world.awaitArea(EXIT_SETTLE_MS) { it != AREA_BALANCED_SPLIT }) {
            "Возвращённое приложение осталось в закрытом split-контейнере"
        }
    }

    private companion object {
        const val EXPAND_PRIMARY_MODE = 101
        const val EXPAND_SECONDARY_MODE = 102
    }
}
