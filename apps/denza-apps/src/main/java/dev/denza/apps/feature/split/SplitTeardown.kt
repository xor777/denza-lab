package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME
import dev.denza.apps.feature.split.SplitWorld.Companion.EXIT_SETTLE_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID

/**
 * Taking down what a scene of ours leaves behind (contract 1.2.3, 1.6, 1.8.2).
 *
 * The whole scene when the toggle goes off: the application the user was in stays fullscreen, the
 * gate closes only if this product opened it, and our pickers go ([closePickers]). And the exact
 * tasks a pane or a scene that ended leaves: a recorded application, a picker base wherever the
 * firmware stranded it. Every removal is by exact identity (invariant 3).
 */
internal class SplitTeardown(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
    private val gate: SplitGate,
) {
    /**
     * Ends native split instead of merely expanding one pane. Firmware modes 101/102 retain the
     * hidden peer root and divider, so disabled means: close the gate, move the exact foreground
     * task to the full IVI root, then remove only picker and unselected host artifacts.
     */
    fun closePickers(pickerComponents: Map<SplitPane, String>) {
        val before = world.snapshot()
        val mainDisplayTasks = before.roots
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap(SplitRootTask::tasks)
        // A stock picker in a pane is ours to remove exactly when the scene is ours: the firmware
        // fills a vacated pane of our scene with it, and leaving it would leave the user a stock
        // picker in a hidden root after the off. The same task can also belong to a split the user
        // built with the stock tools, which our toggle must not take down. Its id cannot tell the
        // two apart - a recorded scene holds the ids of our pickers, never a stock one - so the
        // proof is the scene's: a task of our exact picker identity still alive on the main
        // display. A foreign split cannot hold one. With nothing of our scene left, the stock
        // picker stays.
        val sceneIsOurs = mainDisplayTasks.any { task -> task.isDenzaPickerBase() }
        // `com.byd.sr` is never touched here. The bootstrap the firmware inserts is removed where
        // the product knows its id and has just seen it, in SplitEdge.attachPicker right after its
        // own picker started in that pane. A teardown has no such proof, the package is a real
        // user app, and a fullscreen `com.byd.sr` does not pass `eligible` below anyway.
        val pickerTasks = mainDisplayTasks
            .filter { task ->
                (task.isStockSplitPicker() && sceneIsOurs) ||
                    pickerComponents.values.any { component -> task.matchesComponent(component) }
            }
        val pickerTaskIds = pickerTasks.mapTo(mutableSetOf(), SplitTask::id)
        // The app that stays fullscreen is the one the system calls focused ([focusedTaskId];
        // findings, "The focus read, proven on the product's own channel"): exactly one app is
        // left, and the user sees a wrong guess at once (1.2.3). The order of `am stack list` is
        // z-order, which usually follows focus but is not it, so it is only the fallback when the
        // read answers nothing usable: walk the visible root tops until a real user task is
        // found - a visible picker reported before the app of the peer pane is not "no
        // foreground".
        val eligible = { task: SplitTask ->
            task.id !in pickerTaskIds &&
                !task.isDenzaPickerBase() &&
                !task.isNativeSplitBootstrap()
        }
        val visibleRoots = before.roots.asSequence()
            .filter { root -> root.displayId == MAIN_DISPLAY_ID && root.activityType != "home" }
        val focusedId = focusedTaskId()
        val focused = focusedId?.let { id ->
            visibleRoots.flatMap { it.tasks.asSequence() }.firstOrNull { it.id == id && eligible(it) }
        }
        val foreground = focused
            ?: visibleRoots.mapNotNull(SplitRootTask::resolvedTopTask).firstOrNull(eligible)

        gate.closeOwnedGate()

        if (foreground != null) {
            val fullRootId = world.fullIviRootTaskId()
            if (foreground.rootId != fullRootId) {
                commands.moveTask(foreground.id, fullRootId)
            }
            commands.normalizeTaskToRoot(foreground.id, fullRootId)
            world.pause(EXIT_SETTLE_MS)
        } else {
            world.run("input keyevent KEYCODE_HOME")
            world.pause(EXIT_SETTLE_MS)
        }

        val current = world.snapshot()
        commands.removeTasksSafely(
            pickerTasks.mapNotNull { previous ->
                current.roots.asSequence()
                    .filter { it.displayId == MAIN_DISPLAY_ID }
                    .flatMap { it.tasks.asSequence() }
                    .firstOrNull { it.id == previous.id && it.packageName == previous.packageName }
            },
        )

        // A last pass that asks nothing: our own pickers by exact identity anywhere on the main
        // display, whatever reached `pickerTasks` before the foreground moved. An orphan that
        // appears between the first snapshot and the removal above would otherwise survive or
        // not by the order of a race; our picker's identity ([isDenzaPickerBase]) cannot name
        // anyone else's task.
        commands.removeTasksSafely(
            world.snapshot().roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .filter { task -> task.isDenzaPickerBase() }
                .toList(),
        )

        val after = world.snapshot()
        if (foreground != null) {
            val fullRootId = world.fullIviRootTaskId()
            when (val area = world.callInt("service call activity_task 30")) {
                AREA_HOME -> Unit // The user explicitly left while cleanup was in flight.
                AREA_FULL_IVI -> {
                    val fullRoot = after.root(fullRootId)
                        ?: error("Полноэкранный IVI-контейнер исчез")
                    val moved = fullRoot.tasks.firstOrNull { it.id == foreground.id }
                    if (moved != null) {
                        check(moved.bounds == fullRoot.bounds) {
                            "Выбранное приложение не приняло полноэкранный размер"
                        }
                    } else {
                        // Foreground identity is not stable while the user can press Home or
                        // launch another app. Accept that authoritative replacement only when
                        // it is itself a real, full-size task in the full IVI root.
                        val replacement = fullRoot.resolvedTopTask()
                        check(
                            replacement != null &&
                                !replacement.isDenzaPickerBase() &&
                                !replacement.isNativeSplitBootstrap() &&
                                replacement.bounds == fullRoot.bounds
                        ) { "Полноэкранное приложение исчезло во время выключения" }
                    }
                }
                else -> error("Прошивка сохранила split после выключения: area=$area")
            }
        } else {
            check(world.callInt("service call activity_task 30") == AREA_HOME) {
                "Пустой split не закрылся на домашний экран"
            }
        }
    }

    /**
     * The task the system calls focused, when it can be asked at all.
     *
     * `dumpsys window` answers `mCurrentFocus=null` and `mFocusedApp=null` on this firmware; the
     * one read that names the focus is `dumpsys activity activities`, and it follows the finger
     * from pane to pane (findings, "Where focus actually is"). `topResumedActivity` does not
     * serve: each root has its own, so it names a container's top, not the one focus of the screen.
     *
     * The output is narrowed by `grep` on the car: the full dump is large, and
     * [SplitWorld.validateOutput] rejects any output containing "Exception", which a dump of every
     * activity may carry for unrelated reasons. It throws nothing: an unread focus is `null`.
     */
    private fun focusedTaskId(): Int? = runCatching {
        val dump = world.shell("dumpsys activity activities | grep mFocusedApp")
        FOCUSED_TASK_PATTERN.find(dump)?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    fun removeRecordedTask(taskId: Int, packageName: String): Boolean {
        val task = world.snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId && it.packageName == packageName }
            ?: return false
        check(!task.isDenzaPickerBase()) { "Нельзя удалить host-пикер как приложение" }
        commands.removeTaskSafely(task)
        return true
    }

    /** Removes only the exact permanent picker reparented out of its dismissed native pane. */
    fun removePickerArtifact(taskId: Int, pickerComponents: Set<String>): Boolean =
        removePickerArtifacts(listOf(taskId), pickerComponents).isNotEmpty()

    /**
     * The same exact-identity removal for several of our pickers at once, wherever on the main
     * display they ended up. An id a fresh snapshot cannot find under our own component is simply
     * not in the answer: nothing else is ever removed (invariant 3).
     *
     * @return the ids that were actually removed.
     */
    fun removePickerArtifacts(taskIds: List<Int>, pickerComponents: Set<String>): List<Int> {
        val tasks = world.snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter {
                it.id in taskIds &&
                    it.isDenzaPickerBase() &&
                    it.matchesAnyComponent(pickerComponents)
            }
            .toList()
        commands.removeTasksSafely(tasks)
        return tasks.map(SplitTask::id)
    }

    private companion object {
        /** `mFocusedApp=ActivityRecord{a81ee00 u0 dev.denza.apps/.MainActivity} t332}` */
        val FOCUSED_TASK_PATTERN = Regex("mFocusedApp=ActivityRecord\\{[^}]*\\}\\s+t([0-9]+)\\}")
    }
}
