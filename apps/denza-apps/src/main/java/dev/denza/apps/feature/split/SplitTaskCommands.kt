package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.EXIT_SETTLE_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.ROOT_SETTLE_MS
import dev.denza.apps.platform.shell.classpathAssignment
import dev.denza.apps.platform.shell.helperNotLoaded
import dev.denza.apps.platform.shell.shellQuote

/**
 * The commands that change the task tree, each with the settle and the check it has always had:
 * the two launches in a pane's category, the runtime split list, a move, a promote, a resize to the
 * root's bounds, an exact-identity removal through the shell-UID proxy, and the eviction of a
 * user's task out of a panel root.
 *
 * Every recipe of a session is built from these and from [SplitWorld]'s reads; none of them knows
 * which recipe it serves. Commands go through the world's funnel, so the shared topology read is
 * dropped by each of them exactly as before.
 */
internal class SplitTaskCommands(
    private val world: SplitWorld,
    private val apkPath: String,
    /** Where the shell-UID proxy is loaded from; the APK is the always-valid fallback. */
    private val proxyClasspath: SplitProxyClasspath,
) {
    private fun startPickerInPane(
        pane: SplitPane,
        pickerComponent: String,
    ) {
        val category = when (pane) {
            SplitPane.PRIMARY -> PRIMARY_PICKER_CATEGORY
            SplitPane.SECONDARY -> SECONDARY_PICKER_CATEGORY
        }
        world.run(
            "am start -a android.intent.action.MAIN " +
                "-c $category " +
                "-n ${shellQuote(pickerComponent)} " +
                "-f $PICKER_LAUNCH_FLAGS",
        )
    }

    fun launchPickerTask(
        pane: SplitPane,
        pickerComponent: String,
        excludedTaskIds: Set<Int>,
    ): SplitTask {
        // No settle before the poll: the await below already polls, and a blind pause in front of
        // it would be the user waiting out a launch the firmware may have finished (1.13).
        startPickerInPane(pane, pickerComponent)
        return world.awaitTaskMatching { task ->
            task.id !in excludedTaskIds &&
                task.rootId > 0 &&
                task.isDenzaPickerBase() &&
                task.matchesComponent(pickerComponent)
        }
    }

    fun launchPickerInPane(
        pane: SplitPane,
        rootId: Int,
        pickerComponent: String,
    ): SplitTask {
        startPickerInPane(pane, pickerComponent)
        world.pause(PICKER_SETTLE_MS)
        return world.awaitTaskMatching { task ->
            task.rootId == rootId &&
                task.visible &&
                task.isDenzaPickerBase() &&
                task.matchesComponent(pickerComponent) &&
                task.matchesTopComponent(pickerComponent)
        }
    }

    /**
     * The one launch command of the product, in the pane's own category.
     *
     * [secondInstance] is the only thing that decides whether `FLAG_ACTIVITY_MULTIPLE_TASK` is set,
     * and it is true for exactly one situation: the same package being opened a second time while
     * the other pane still holds it (1.5.2). Everywhere else the flag is absent, so the firmware
     * hands back the task the package already has: a restore keeps the app that is playing rather
     * than starting a fresh copy behind a splash screen and orphaning the old one (U2).
     */
    fun startTargetInPane(
        pane: SplitPane,
        target: SplitLaunchTarget,
        secondInstance: Boolean,
    ) {
        val category = when (pane) {
            SplitPane.PRIMARY -> PRIMARY_PICKER_CATEGORY
            SplitPane.SECONDARY -> SECONDARY_PICKER_CATEGORY
        }
        world.run(
            "am start -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-c $category " +
                "-n ${shellQuote(target.componentName)} " +
                "-f " + if (secondInstance) SECOND_INSTANCE_FLAGS else APP_LAUNCH_FLAGS,
        )
    }

    /**
     * Every package a pane receives goes into the firmware's runtime split list, always.
     *
     * The divider's detent map is decided by exactly that list: `isDefaultSecondActivity()` asks
     * whether the package of the wide container's focus task is in `mPrimaryActivityList` (or is
     * the stock list), and nothing else - not the manifest marker, not tx112 (findings, "The
     * divider's detent map, read"). An app that declares `BYD_SUPPORT_SPLIT_ACTIVITY=1` itself is
     * split-capable for tx112 and still outside the list, with "Release to close window" in the
     * wide pane, so the list is extended whatever tx112 says. The same list makes a package
     * split-capable for placement (`startIviWindow` → `isSupportSplit(task)`), so this is also what
     * keeps a pane app's own next screen in its pane.
     *
     * tx125 appends only what the list does not already hold (`setPrimaryListApp`), so a repeat is
     * free for the firmware; every call still reaches the ring as "allowlist extended" (1.12). The
     * tx112 read after it is the postcondition: a firmware that refused the listing is a pane that
     * failed, not a pane that silently lost its divider.
     */
    fun ensureSupported(packageName: String) {
        val quoted = shellQuote(packageName)
        world.callVoid("service call activity_task 125 s16 $quoted")
        check(world.callBoolean("service call activity_task 112 s16 $quoted")) {
            "Прошивка не добавила $packageName в split"
        }
    }

    /**
     * Our own package into the firmware's runtime split list, unconditionally, once per build.
     *
     * Our manifest carries `BYD_SUPPORT_SPLIT_ACTIVITY=1`, so tx112 says yes for us by construction
     * - and the divider's detent map is not decided by the manifest but by the wide pane's package
     * being in the runtime list ([ensureSupported]; findings, "The divider's detent map, read"):
     * listed, the hub in the wide pane gets the full map; merely manifest-capable, it gets the
     * reduced one where everything right of the middle is "Release to close window". The same list
     * makes the package split-capable for placement (`isSupportSplit(task)`).
     *
     * It costs one round trip and reaches the ring as "allowlist extended" (1.12); the trace lasts
     * until a reboot, like that of any selected app. No tx112 check follows: the manifest is ours.
     */
    fun listOwnPackageForTheDivider() {
        world.callVoid("service call activity_task 125 s16 ${shellQuote(SPLIT_HOST_PACKAGE)}")
    }

    fun moveTask(taskId: Int, rootId: Int, toTop: Boolean = true) {
        check(taskId > 0 && rootId > 0)
        world.run("am stack move-task $taskId $rootId $toTop")
    }

    fun promoteTask(task: SplitTask, targetRootId: Int) {
        check(task.id > 0 && targetRootId > 0)
        if (task.rootId == targetRootId) {
            // DiLink treats move-task into the current root as a no-op even with toTop=true.
            // Focus is the firmware-backed operation that promotes a task hidden by the picker.
            world.run("am task focus ${task.id}")
        } else {
            moveTask(task.id, targetRootId)
        }
    }

    /** @return whether the task actually had to be resized. */
    fun normalizeTaskToRoot(taskId: Int, rootId: Int): Boolean {
        val beforeRoot = world.snapshot().root(rootId)
            ?: error("Split-контейнер $rootId исчез")
        val beforeTask = beforeRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Задача приложения $taskId не вошла в split-контейнер")
        if (beforeTask.bounds == beforeRoot.bounds) return false
        check(beforeRoot.bounds.hasArea()) { "Split-контейнер $rootId не имеет размера" }
        val bounds = beforeRoot.bounds
        world.run(
            "am task resize $taskId ${bounds.left} ${bounds.top} " +
                "${bounds.right} ${bounds.bottom}",
        )
        world.pause(ROOT_SETTLE_MS)
        val afterRoot = world.snapshot().root(rootId)
            ?: error("Split-контейнер $rootId исчез после изменения размера")
        val afterTask = afterRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Задача приложения $taskId исчезла после изменения размера")
        check(afterTask.bounds == afterRoot.bounds) {
            "Задача приложения $taskId не приняла размер split-контейнера"
        }
        return true
    }

    fun removeTaskSafely(task: SplitTask) = removeTasksSafely(listOf(task))

    /**
     * Removes exactly these tasks, in this order, with one invocation of the proxy.
     *
     * Each removal is an exact-identity call (task id, base component, and the top component when
     * the task is the top); loading the proxy dominates the cost of a removal by an order of
     * magnitude, so a recipe clearing several tasks starts `app_process` once and settles once.
     *
     * @return whether any of them was actually removed.
     */
    fun removeTasksSafely(tasks: List<SplitTask>): Boolean {
        if (tasks.isEmpty()) return false
        val arguments = tasks.joinToString(" ") { task ->
            val baseActivity = task.activityName ?: error("У задачи ${task.id} нет base activity")
            // `am stack list` repeats the root top component on hidden child lines. It is a valid
            // task-top postcondition only when this task itself is resolved as top.
            val topPackage = task.topPackageName.takeIf { task.isTop } ?: "-"
            val topActivity = task.topActivityName.takeIf { task.isTop } ?: "-"
            "${task.id} ${shellQuote(task.packageName)} ${shellQuote(baseActivity)} " +
                "${shellQuote(topPackage)} ${shellQuote(topActivity)}"
        }
        val classpath = proxyClasspath.entry(world::shell)
        val output = world.shell(
            "${classpathAssignment(classpath, apkPath)} app_process /system/bin " +
                "--nice-name=denza_split_cmd $SPLIT_PROXY_CLASS remove-task $arguments",
        )
        // The class did not load from the kept jar: the next removal asks the car again.
        if (helperNotLoaded(output)) proxyClasspath.forget()
        world.validateOutput(output)
        val removed = parseRemovals(output)
        val refused = tasks.filterNot { task -> removed[task.id] == true }
        if (refused.isNotEmpty()) {
            // A task the proxy would not take is only a failure if it is still there: the firmware
            // may have finished the very dismissal that made us ask.
            val living = world.snapshot().roots.asSequence()
                .flatMap { root -> root.tasks.asSequence() }
                .mapTo(mutableSetOf(), SplitTask::id)
            refused.firstOrNull { task -> task.id in living }?.let { task ->
                error("Не удалось безопасно удалить задачу ${task.id}")
            }
        }
        if (refused.size == tasks.size) return false
        world.pause(ROOT_SETTLE_MS)
        return true
    }

    /** One `DENZA_SPLIT_RESULT:<taskId>=<bool>` line per task the proxy was asked about. */
    private fun parseRemovals(output: String): Map<Int, Boolean> = output.lineSequence()
        .map(String::trim)
        .filter { line -> line.startsWith(SPLIT_PROXY_RESULT_PREFIX) }
        .mapNotNull { line ->
            val result = line.removePrefix(SPLIT_PROXY_RESULT_PREFIX)
            val taskId = result.substringBefore('=').toIntOrNull() ?: return@mapNotNull null
            taskId to (result.substringAfter('=', missingDelimiterValue = "") == "true")
        }
        .toMap()

    /**
     * Takes a user's tasks out of the panel roots, alive (contract, note under 1.5; invariant 3),
     * so that each ends where Home or a caption swipe would leave it: below Home, stopped, never
     * drawn over or behind the scene.
     *
     * One read made before anything moves picks the path of each task ([closableByTheFirmware]):
     * - **the firmware's close.** The application a pane shows, when tx117 `closeApplication(pkg)`
     *   would take exactly that task, leaves by that close (findings, "A displaced pane app", "What
     *   the code does now"). The pane's picker comes up and the task becomes a root of its own below
     *   Home with its pane geometry kept: at once in the narrow pane, at the end of the close
     *   animation in the wide one. Nothing is moved into the full root, resized or raised for it.
     *   A close that is refused, or not seen through within [CLOSE_LOOKS] looks, takes the second
     *   path;
     * - **the eviction.** Every other task - buried under the shown one, of a package with a second
     *   task in a pane, in a scene that is not balanced on the screen - is moved into the full IVI
     *   root with the same `am stack move-task … false` that returns a borrowed task. The full root
     *   holds no background tasks: a task moved there stays drawn while fewer than two opaque pane
     *   containers are above it, until the next placement in a pane or Home sends it below Home. So
     *   it gets that root's bounds ([normalizeTaskToRoot]; a leaf inside `ivi_full` accepts a
     *   resize either way), quietly - a package that will not give up its geometry must not cost
     *   the user the scene - and both panes are raised over it ([raiseBothPanesOverTheEvicted]).
     *
     * The buried tasks go first, so that a pane the firmware closes holds its picker and the
     * closing app alone: the close brings up the next task under the app, and that must be ours.
     *
     * [beforeMove] hears each task and the full root's id before the task is touched, on either
     * path, so an operation can journal the inverse ahead of the mutation (a choice on the pill puts
     * the application it replaced back into its pane if it is unwound, invariant 9). The inverse is
     * the same for both: `move-task` back on top of the pane, which takes a root of its own below
     * Home as readily as a child of the full root (the restore's `stray`, live-proven).
     *
     * [closeShown] false keeps every task on the eviction: the cold degrade has the time of one
     * eviction reserved inside the operation's 10 s, not a close waited out in front of it
     * ([SplitSceneBuilder.EVICTION_BOUND_MS]).
     *
     * @return whether anything actually had to leave.
     */
    fun evictFromPanes(
        tasks: List<SplitTask>,
        closeShown: Boolean = true,
        beforeMove: (SplitTask, Int) -> Unit = { _, _ -> },
    ): Boolean {
        val leaving = tasks.distinctBy(SplitTask::id)
        if (leaving.isEmpty()) return false
        val fullRootId = world.fullIviRootTaskId()
        val roots = world.nativeRootIds()
        val before = if (closeShown) world.snapshot() else null
        val closing = before?.let { state -> closableByTheFirmware(leaving, state, roots) }.orEmpty()
        val closingIds = closing.mapTo(mutableSetOf(), SplitTask::id)
        val moving = leaving.filterNot { task -> task.id in closingIds }
        moving.forEach { task ->
            beforeMove(task, fullRootId)
            moveTask(task.id, fullRootId, toTop = false)
        }
        val stayed = closeLikeTheFirmware(closing, roots.values + fullRootId, fullRootId, beforeMove)
        stayed.forEach { task -> moveTask(task.id, fullRootId, toTop = false) }
        val evicted = moving + stayed
        if (evicted.isEmpty()) return true
        world.pause(ROOT_SETTLE_MS)
        normalizeEvictedTasksToTheirRoots(evicted.mapTo(mutableSetOf(), SplitTask::id))
        raiseBothPanesOverTheEvicted(fullRootId, before?.let { state -> raiseOrder(state, roots) })
        return true
    }

    /**
     * The tasks of [tasks] that tx117 takes out exactly as Home or a caption swipe would, read on
     * [state] before anything moves. `closeApplication(pkg)` closes the top-most task of the
     * package, narrow container first, then wide, then the full root
     * (`BydSmartMultiIviController.getTaskByPkgName`, `BydSmartMultiContainer.getTaskByPkgName`),
     * and `removeChildTask` then brings up the next task of the wide pane or simply reparents in the
     * narrow one. So a task qualifies only when:
     * - the area is balanced: the close is the firmware's own gesture of a split on the screen;
     * - it is the shown (visible top) application of a pane, and not one of our components;
     * - its package has no other task in either pane root, so the lookup cannot take another
     *   window - the hub never qualifies, since the picker bases share its package;
     * - its pane holds nothing else but our picker base once the buried tasks are gone, so the task
     *   the wide pane brings up is our picker (`getNextTask`);
     * - each pane keeps a picker base: the narrow close ends the split when the wide root is left
     *   with no child (`removeChildTask`, `setSplitWindowMode(-1)`).
     */
    private fun closableByTheFirmware(
        tasks: List<SplitTask>,
        state: SplitTaskSnapshot,
        roots: Map<SplitPane, Int>,
    ): List<SplitTask> {
        if (world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) return emptyList()
        val panes = roots.values.mapNotNull(state::root)
        if (panes.size != roots.size) return emptyList()
        val leavingIds = tasks.mapTo(mutableSetOf(), SplitTask::id)
        val everyPaneKeepsItsPicker = panes.all { root ->
            root.tasks.any { task -> task.isDenzaPickerBase() && task.id !in leavingIds }
        }
        if (!everyPaneKeepsItsPicker) return emptyList()
        return tasks.filter { task ->
            val root = panes.firstOrNull { pane -> pane.id == task.rootId } ?: return@filter false
            root.resolvedTopTask()?.id == task.id &&
                !task.isOwnSplitComponent() &&
                panes.sumOf { pane -> pane.tasks.count { it.packageName == task.packageName } } == 1 &&
                root.tasks.all { other ->
                    other.id == task.id ||
                        other.id in leavingIds ||
                        other.isDenzaPickerBase() ||
                        other.isEmptyRootMarker()
                }
        }
    }

    /**
     * tx117 for each of [tasks], then one wait by condition until none of them is in any of
     * [heldBy] - the pane roots and the full root - any more.
     *
     * @return the tasks the firmware did not take out: the close refused, or still in a pane or
     * the full root at the last look. They take the eviction.
     */
    private fun closeLikeTheFirmware(
        tasks: List<SplitTask>,
        heldBy: Collection<Int>,
        fullRootId: Int,
        beforeMove: (SplitTask, Int) -> Unit,
    ): List<SplitTask> {
        if (tasks.isEmpty()) return emptyList()
        val sent = mutableListOf<SplitTask>()
        val refused = mutableListOf<SplitTask>()
        tasks.forEach { task ->
            beforeMove(task, fullRootId)
            val closed = runCatching {
                world.callVoid(
                    "service call activity_task $CLOSE_APPLICATION_TX s16 ${shellQuote(task.packageName)}",
                )
            }
            if (closed.isSuccess) sent += task else refused += task
        }
        if (sent.isEmpty()) return refused
        val roots = heldBy.toSet()
        var held: List<SplitTask> = sent
        world.awaitSnapshotMatching(CLOSE_LOOKS) { state ->
            val inThoseRoots = state.roots.asSequence()
                .filter { root -> root.displayId == MAIN_DISPLAY_ID && root.id in roots }
                .flatMap { root -> root.tasks.asSequence() }
                .mapTo(mutableSetOf(), SplitTask::id)
            held = sent.filter { task -> task.id in inThoseRoots }
            held.isEmpty()
        }
        return refused + held
    }

    /**
     * The order the two panes are raised in, lower first. `am stack list` lists the roots top to
     * bottom (`forAllRootTasks`), and the last focus decides which pane's app is top-resumed, so the
     * pane that was on top before stays there. Unreadable, the narrow pane is raised last, as the
     * one raise of before did.
     */
    private fun raiseOrder(state: SplitTaskSnapshot, roots: Map<SplitPane, Int>): List<SplitPane> {
        val listed = state.roots.filter { root -> root.displayId == MAIN_DISPLAY_ID }.map(SplitRootTask::id)
        val primaryAt = listed.indexOf(roots.getValue(SplitPane.PRIMARY))
        val secondaryAt = listed.indexOf(roots.getValue(SplitPane.SECONDARY))
        return if (primaryAt >= 0 && secondaryAt >= 0 && secondaryAt < primaryAt) {
            listOf(SplitPane.PRIMARY, SplitPane.SECONDARY)
        } else {
            listOf(SplitPane.SECONDARY, SplitPane.PRIMARY)
        }
    }

    /**
     * An evicted task stays visible in the full root, and `am task focus` raises one pane
     * container, not the scene (`Task.moveToFrontInner`; the containers are not adjacent). A root
     * below the pane containers is hidden only when both are above it with an opaque app, so both
     * panes are raised, each by a focus on its top task - the reveal's own command (1.9.4).
     *
     * It is needed when the full root covers the scene (area 4), and at area 3 when the full root
     * is listed above a pane container with something in it: the area compares only the higher pane
     * with the full root, so a full root between the two panes still reads 3. An order that cannot
     * be read counts as covering. Under Home (area 0) Home covers both and nothing is raised; at 1
     * or 2 the one pane is full width and opaque over the full root. The recipe's postcondition is
     * the judge, so the area wait does not throw.
     */
    private fun raiseBothPanesOverTheEvicted(fullRootId: Int, order: List<SplitPane>?) {
        val area = world.callInt("service call activity_task 30")
        if (area != AREA_FULL_IVI && area != AREA_BALANCED_SPLIT) return
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        if (area == AREA_BALANCED_SPLIT && !fullRootAboveAPane(state, roots, fullRootId)) return
        val tops = (order ?: raiseOrder(state, roots)).mapNotNull { pane ->
            val root = state.root(roots.getValue(pane)) ?: return@mapNotNull null
            // Under a cover `am stack list` hides every child of a pane; then the root's component
            // names the top, and failing that the order the firmware lists children in (bottom up).
            (root.resolvedTopTask() ?: root.resolvedCoveredTopTask() ?: root.tasks.lastOrNull())
                ?.takeUnless { task -> task.isEmptyRootMarker() }
        }
        if (tops.isEmpty()) return
        tops.forEach { top -> world.run("am task focus ${top.id}") }
        world.awaitArea(EXIT_SETTLE_MS) { area -> area != AREA_FULL_IVI }
    }

    private fun fullRootAboveAPane(
        state: SplitTaskSnapshot,
        roots: Map<SplitPane, Int>,
        fullRootId: Int,
    ): Boolean {
        val listed = state.roots.filter { root -> root.displayId == MAIN_DISPLAY_ID }
        val fullAt = listed.indexOfFirst { root -> root.id == fullRootId }
        val full = listed.getOrNull(fullAt) ?: return true
        if (full.tasks.all(SplitTask::isEmptyRootMarker)) return false
        val panesAt = roots.values.map { rootId -> listed.indexOfFirst { root -> root.id == rootId } }
        if (panesAt.any { at -> at < 0 }) return true
        return panesAt.any { at -> at > fullAt }
    }

    /**
     * The geometry invariant for what the scene no longer holds: no evicted task keeps bounds
     * other than those of the root it landed in.
     *
     * One read for the whole eviction names who landed where; a task in a root of its own already
     * equals it and costs no command.
     */
    private fun normalizeEvictedTasksToTheirRoots(taskIds: Set<Int>) {
        val landed = world.snapshot()
        landed.roots.asSequence()
            .filter { root -> root.displayId == MAIN_DISPLAY_ID && root.bounds.hasArea() }
            .flatMap { root ->
                root.tasks.asSequence()
                    .filter { task -> task.id in taskIds && task.bounds != root.bounds }
                    .map { task -> task.id to root.id }
            }
            .toList()
            .forEach { (taskId, rootId) -> runCatching { normalizeTaskToRoot(taskId, rootId) } }
    }

    /**
     * A pane is its picker base and at most one application: whatever else the given roots hold
     * leaves them, by the rule of invariant 3 - our own components and tasks this operation
     * provably created ([preexistingTaskIds] does not hold them) are removed, a user's task is
     * evicted alive ([evictFromPanes]). The firmware brings into a pane more than was asked for:
     * a tap on a package with two living tasks can bring both. The usual case sends no command.
     *
     * [residentByRoot] names the application each of these panes shows. A second living task of
     * that package is never surplus (1.5.2): the firmware brought it there itself, the same
     * application is on top either way, and evicting a visible window to root 4 would put it over
     * the whole scene. The build passes it for both panes, the selection for its one.
     */
    fun sweepRootsToBaseAndApp(
        keepByRoot: Map<Int, Set<Int>>,
        preexistingTaskIds: Set<Int>?,
        residentByRoot: Map<Int, String> = emptyMap(),
    ) {
        val state = world.snapshot()
        val surplus = keepByRoot.flatMap { (rootId, keep) ->
            val resident = residentByRoot[rootId]
            state.root(rootId)?.tasks.orEmpty()
                .filterNot { task ->
                    task.id in keep ||
                        task.isEmptyRootMarker() ||
                        (resident != null &&
                            !task.isOwnSplitComponent() &&
                            task.packageName == resident)
                }
        }
        if (surplus.isEmpty()) return
        val (own, foreign) = surplus.partition { task ->
            task.isOwnSplitComponent() ||
                (preexistingTaskIds != null && task.id !in preexistingTaskIds)
        }
        removeTasksSafely(own.distinctBy(SplitTask::id))
        evictFromPanes(foreign)
    }

    internal companion object {
        /**
         * How many looks the firmware's close of a pane's app is waited for, one
         * [SplitWorld.TASK_DISCOVERY_INTERVAL_MS] apart. The narrow pane's close reparents at once;
         * the wide pane's when its close animation ends (`isMovingToBack`, then
         * `DisplayContent.onWindowAnimationFinishedHook`). Seven looks are 600 ms of pauses and
         * seven reads: about 0.95 s on the car at 46-50 ms a read, and
         * [SplitSceneBuilder.CLOSE_WAIT_BOUND_MS] from above. The animation's length is not
         * measured on the car.
         */
        const val CLOSE_LOOKS = 7

        /** `closeApplication(String)`, the firmware's own close of a pane's app. */
        private const val CLOSE_APPLICATION_TX = SplitBinderCall.CLOSE_APPLICATION

        /** `NEW_TASK | RESET_TASK_IF_NEEDED`: the package's own task, whichever one that is. */
        private const val APP_LAUNCH_FLAGS = "0x10200000"

        /** The same plus `MULTIPLE_TASK`: a second, independent copy and nothing else (1.5.2). */
        private const val SECOND_INSTANCE_FLAGS = "0x18200000"
        private const val PICKER_LAUNCH_FLAGS = "0x18010000"
        private const val PRIMARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_PRIMARY"
        private const val SECONDARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_SECOND"
        private const val PICKER_SETTLE_MS = 150L
        private const val SPLIT_PROXY_CLASS = "dev.denza.apps.feature.split.SplitTaskProxyMain"
        private const val SPLIT_PROXY_RESULT_PREFIX = "DENZA_SPLIT_RESULT:"
    }
}
