package dev.denza.apps.feature.split

/**
 * Explicit, command-driven split session.
 *
 * Unlike [SplitShellRouter], this class never watches or interprets arbitrary foreground
 * launches. Every mutation starts from either the dedicated launcher or a tap in a picker,
 * so the destination pane and expected component are known before any task is moved.
 */
internal class SplitPickerShellSession(
    private val shell: (String) -> String,
    private val apkPath: String,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val gateLeaseStore: SplitGateLeaseStore? = null,
) {
    /**
     * Adopts an already-running product scene without mutating the firmware roots.
     *
     * Package replacement restarts Denza Apps but does not remove the two standalone picker
     * tasks. Rebuilding that still-valid scene races SmartMulti's own focus/restore controller.
     * Only accept the scene when both native roots have exactly our permanent base and at most
     * one ordinary app; anything less certain falls back to the explicit reconstruction path.
     */
    fun existingOwnedSession(
        pickerComponents: Set<String>,
    ): Map<SplitPane, SplitPickerLivePane>? {
        if (callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) return null
        val roots = nativeRootIds()
        val state = snapshot()
        return SplitPane.entries.associateWith { pane ->
            val root = state.root(roots.getValue(pane)) ?: return null
            val pickers = root.tasks.filter { task ->
                task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
            }
            if (pickers.size != 1 || root.tasks.size !in 1..MAX_TASKS_PER_PANE) return null
            val picker = pickers.single()
            if (picker.bounds != root.bounds) return null

            val top = root.resolvedTopTask() ?: return null
            val app = if (top.id == picker.id) {
                if (!picker.matchesAnyTopComponent(pickerComponents)) return null
                null
            } else {
                if (
                    top.isDenzaPickerBase() ||
                    top.isNativeSplitBootstrap() ||
                    top.bounds != root.bounds
                ) {
                    return null
                }
                top
            }
            SplitPickerLivePane(
                pane = pane,
                hostTaskId = picker.id,
                appTaskId = app?.id,
                appPackageName = app?.packageName,
            )
        }
    }

    fun openPickers(
        pickerComponents: Map<SplitPane, String>,
        preservedPackages: Map<SplitPane, String>,
    ): Map<SplitPane, Int> {
        check(pickerComponents.keys == SplitPane.entries.toSet()) {
            "Нужны оба split-пикера"
        }
        ensureGateOpen()
        ensureSupported(SPLIT_HOST_PACKAGE)
        val rootIds = nativeRootIds()
        // Do not enter through activity_task tx115 here. BYD remembers split-capable packages
        // globally and may restore an unrelated OEM companion (notably ADAS) before our launcher
        // gets control. Explicit PRIMARY/SECONDARY categories create and target the same native
        // roots without consulting that remembered OEM pair.
        val before = snapshot()
        val existingPickerTasks = SplitPane.entries.associateWith { pane ->
            val rootId = rootIds.getValue(pane)
            before.root(rootId)?.tasks
                ?.filter { task ->
                    task.isDenzaPickerBase() &&
                        task.matchesComponent(pickerComponents.getValue(pane))
                }
                ?.maxByOrNull(SplitTask::id)
        }
        val pickerTasks = existingPickerTasks
            .filterValues { it != null }
            .mapValuesTo(mutableMapOf()) { (_, task) -> task!! }
        val assignedIds = pickerTasks.values.mapTo(mutableSetOf(), SplitTask::id)
        val reusableTasks = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.id !in assignedIds &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents.values.toSet())
            }
            .sortedByDescending(SplitTask::id)
            .toMutableList()
        SplitPane.entries.filterNot(pickerTasks::containsKey).forEach { pane ->
            val picker = reusableTasks.removeFirstOrNull()
                ?: launchPickerTask(
                    pane = pane,
                    pickerComponent = pickerComponents.getValue(pane),
                    excludedTaskIds = assignedIds,
                )
            assignedIds += picker.id
            pickerTasks[pane] = picker
        }

        // Categories are authoritative once the native scene exists. On a truly empty scene
        // this firmware first creates ordinary fullscreen tasks, so explicitly reparent those
        // exact tasks into the already-known OEM roots and reveal the real divider once.
        SplitPane.entries.forEach { pane ->
            val picker = pickerTasks.getValue(pane)
            val rootId = rootIds.getValue(pane)
            if (picker.rootId != rootId) moveTask(picker.id, rootId)
        }
        pause(ROOT_SETTLE_MS)
        if (callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            dragDividerToBalanced()
            pause(NATIVE_PICKER_SETTLE_MS)
        }
        check(callInt("service call activity_task 30") == AREA_BALANCED_SPLIT) {
            "Прошивка не раскрыла native split"
        }
        SplitPane.entries.forEach { pane ->
            normalizeTaskToRoot(pickerTasks.getValue(pane).id, rootIds.getValue(pane))
        }
        // A picker is the permanent base of its pane. Never remove the last visible base before
        // the replacement exists: BYD collapses the native roots immediately and restores its
        // own remembered companion on the next launch.
        SplitPane.entries.forEach { pane ->
            val rootId = rootIds.getValue(pane)
            val picker = pickerTasks.getValue(pane)
            prunePane(
                rootId = rootId,
                hostTaskId = picker.id,
                preservedPackage = preservedPackages[pane],
            )
        }
        removeUnkeptPickerTasks(
            pickerComponents = pickerComponents.values.toSet() + LEGACY_PICKER_COMPONENTS,
            keptTaskIds = pickerTasks.values.mapTo(mutableSetOf(), SplitTask::id),
        )
        pause(ROOT_SETTLE_MS)
        verifyPickerTasks(
            pickerComponents = pickerComponents,
            rootIds = rootIds,
            hostTaskIds = pickerTasks.mapValues { it.value.id },
            preservedPackages = preservedPackages,
        )
        return pickerTasks.mapValues { it.value.id }
    }

    private fun currentBootstrapTasks(
        rootIds: Map<SplitPane, Int>,
    ): Map<SplitPane, SplitTask>? {
        if (callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) return null
        val state = snapshot()
        val tasks = SplitPane.entries.associateWith { pane ->
            state.root(rootIds.getValue(pane))
                ?.tasks
                ?.firstOrNull { it.isNativeSplitBootstrap() }
        }
        if (tasks.values.any { it == null }) return null
        return tasks.mapValues { (_, task) -> task!! }
    }

    fun selectApp(
        pickerTaskId: Int,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
        reservedPackages: Set<String> = emptySet(),
    ): SplitPickerPlacement {
        ensureGateOpen()
        val roots = nativeRootIds()
        val before = snapshot()
        val pane = SplitPane.entries.firstOrNull { candidate ->
            before.root(roots.getValue(candidate))?.tasks?.any { task ->
                task.id == pickerTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } == true
        } ?: error("Пикер больше не находится в split-контейнере")
        val targetRootId = roots.getValue(pane)
        val otherRootId = roots.getValue(pane.other())

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
                    it.matchesAnyComponent(pickerComponents) &&
                    it.matchesAnyTopComponent(pickerComponents)
            }
        check(pickerHost != null &&
            pickerHost.visible &&
            pickerHost.matchesAnyTopComponent(pickerComponents)
        ) {
            "Пикер этого окна больше не найден"
        }
        check(before.root(otherRootId)?.resolvedTopTask()?.packageName != target.packageName) {
            "Одно приложение нельзя открыть в двух окнах"
        }
        ensureSupported(target.packageName)

        val existingCandidates = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { it.packageName == target.packageName }
            .sortedByDescending(SplitTask::id)
            .toList()
        val selected = existingCandidates.firstOrNull()

        before.root(targetRootId)?.tasks.orEmpty()
            .filterNot { it.id == pickerHost.id }
            .filterNot { selected != null && it.id == selected.id }
            .forEach(::removeTaskSafely)

        val targetTask = selected ?: run {
            launchTarget(target, pane)
            awaitPackageTask(target.packageName)
        }
        promoteTask(targetTask, targetRootId)
        normalizeTaskToRoot(targetTask.id, targetRootId)
        pause(ROOT_SETTLE_MS)

        val after = snapshot()
        val root = after.root(targetRootId)
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
        check(top.packageName == target.packageName) {
            "Приложение ${target.packageName} не стало верхним в выбранном окне"
        }
        check(top.bounds == root.bounds) {
            "Приложение ${target.packageName} не приняло размер выбранного окна"
        }
        check(callInt("service call activity_task 30") == AREA_BALANCED_SPLIT) {
            "Split не перешёл в рабочее состояние"
        }
        check(root.tasks.size <= MAX_TASKS_PER_PANE) {
            "В split-контейнере накопилось больше двух задач"
        }
        return SplitPickerPlacement(
            pane = pane,
            hostTaskId = pickerHost.id,
            appTaskId = top.id,
            packageName = target.packageName,
        )
    }

    /**
     * A projected saved member makes its native pane reservation ambiguous. Reopening or
     * replacing either pane while that task is on another Android display would make the later
     * navigation return race the new scene. Keep the native roots untouched until return.
     */
    fun requireNoExternalReservedPackages(reservedPackages: Set<String>) {
        if (reservedPackages.isEmpty()) return
        val projectedReservedPackage = snapshot().roots.asSequence()
            .filter { it.displayId != MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .map(SplitTask::packageName)
            .firstOrNull(reservedPackages::contains)
        check(projectedReservedPackage == null) {
            "Сначала верните приложение с другого экрана"
        }
    }

    fun restoreApp(
        pickerTaskId: Int,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
        reservedPackages: Set<String> = emptySet(),
    ): SplitPickerPlacement {
        val roots = nativeRootIds()
        val before = snapshot()
        val pane = SplitPane.entries.firstOrNull { candidate ->
            before.root(roots.getValue(candidate))?.tasks?.any { task ->
                task.id == pickerTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } == true
        } ?: error("Пикер больше не находится в split-контейнере")
        val root = before.root(roots.getValue(pane))
            ?: error("Split-контейнер сохранённого приложения исчез")
        val top = root.resolvedTopTask()
        if (
            top != null &&
            top.packageName == target.packageName &&
            top.id != pickerTaskId &&
            top.bounds == root.bounds
        ) {
            return SplitPickerPlacement(
                pane = pane,
                hostTaskId = pickerTaskId,
                appTaskId = top.id,
                packageName = top.packageName,
            )
        }
        return selectApp(
            pickerTaskId = pickerTaskId,
            target = target,
            pickerComponents = pickerComponents,
            reservedPackages = reservedPackages,
        )
    }

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
    ): SplitNavigationReturnPlan {
        val roots = nativeRootIds()
        val originalPane = SplitPane.entries.firstOrNull { pane ->
            roots.getValue(pane) == originalRootTaskId
        }
        if (callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            return SplitNavigationReturnPlan(
                pane = originalPane,
                rootTaskId = originalRootTaskId,
                hostTaskId = null,
                fullscreen = true,
            )
        }

        val before = snapshot()
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
                    pause(TASK_DISCOVERY_INTERVAL_MS)
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
        val root = snapshot().root(plan.rootTaskId)
            ?: error("Split-контейнер возврата исчез")
        check(root.tasks.any { task ->
            task.id == hostTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        }) { "Пикер исчез из окна возврата" }
        val top = root.resolvedTopTask()
            ?: error("Навигация не появилась в split-окне")
        check(top.id == taskId && top.packageName == packageName && top.bounds == root.bounds) {
            "Навигация не заняла выбранное split-окно"
        }
        check(root.tasks.size <= MAX_TASKS_PER_PANE) {
            "В окне возврата накопилось больше двух задач"
        }
        return SplitPickerPlacement(pane, hostTaskId, taskId, packageName)
    }

    fun observePane(
        pane: SplitPane,
        pickerComponents: Set<String>,
    ): SplitPickerPaneObservation {
        val roots = nativeRootIds()
        val root = snapshot().root(roots.getValue(pane))
        val nativeHost = root?.tasks?.firstOrNull { it.isNativeSplitBootstrap() }
        val pickerHost = nativeHost?.takeIf { it.matchesAnyTopComponent(pickerComponents) }
            ?: root?.tasks?.firstOrNull { it.matchesAnyComponent(pickerComponents) }
        val pickerVisible = pickerHost?.visible == true &&
            pickerHost.matchesAnyTopComponent(pickerComponents)
        val nativeVisible = nativeHost?.visible == true &&
            nativeHost.matchesOwnTopComponent()
        val host = when {
            pickerVisible -> pickerHost
            nativeVisible -> nativeHost
            else -> pickerHost ?: nativeHost
        }
        return SplitPickerPaneObservation(
            pane = pane,
            hostTaskId = host?.id,
            nativeHostVisible = nativeVisible,
            pickerVisible = pickerVisible,
            observedTaskIds = root?.tasks?.mapTo(mutableSetOf(), SplitTask::id).orEmpty(),
        )
    }

    /** Resolves a picker callback by its task identity; the Activity class never defines a pane. */
    fun observePickerTask(
        hostTaskId: Int,
        pickerComponents: Set<String>,
    ): SplitPickerPaneObservation? {
        val roots = nativeRootIds()
        val state = snapshot()
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

    fun attachPicker(
        pane: SplitPane,
        hostTaskId: Int,
        pickerComponent: String,
    ): Int {
        val rootId = nativeRootIds().getValue(pane)
        val host = snapshot().root(rootId)?.tasks
            ?.firstOrNull { it.id == hostTaskId && it.isNativeSplitBootstrap() }
            ?: error("Штатный host выбранного окна исчез")
        val picker = launchPickerInPane(pane, rootId, pickerComponent)
        removeBootstrapIfPresent(host)
        pause(ROOT_SETTLE_MS)
        val observed = observePane(pane, setOf(pickerComponent))
        check(observed.hostTaskId == picker.id && observed.pickerVisible) {
            "Пикер не стал верхним в выбранном окне"
        }
        return picker.id
    }

    fun removeRecordedTask(taskId: Int, packageName: String): Boolean {
        val task = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId && it.packageName == packageName }
            ?: return false
        check(!task.isDenzaPickerBase()) { "Нельзя удалить host-пикер как приложение" }
        removeTaskSafely(task)
        return true
    }

    /** Removes only a failed restoration candidate left below the exact picker pane. */
    fun discardFailedRestoration(
        pane: SplitPane,
        packageName: String,
        pickerTaskId: Int,
    ) {
        val rootId = nativeRootIds().getValue(pane)
        snapshot().root(rootId)?.tasks.orEmpty()
            .filter { task ->
                task.id != pickerTaskId &&
                    task.packageName == packageName &&
                    !task.isDenzaPickerBase()
            }
            .forEach(::removeTaskSafely)
    }

    fun closeHostedPickerAndGoHome(hostTaskId: Int) {
        val host = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == hostTaskId && it.isDenzaPickerBase() }
            ?: return
        removeTaskSafely(host)
        run("input keyevent KEYCODE_HOME")
        pause(EXIT_SETTLE_MS)
    }

    fun returnRecordedTaskFullscreen(
        pane: SplitPane,
        taskId: Int,
        packageName: String,
    ) {
        val before = snapshot()
        val task = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId && it.packageName == packageName }
            ?: return
        val paneRootId = nativeRootIds().getValue(pane)
        if (task.rootId != paneRootId) return
        moveTask(task.id, paneRootId)
        callVoid(
            "service call activity_task 114 i32 " +
                if (pane == SplitPane.PRIMARY) EXPAND_PRIMARY_MODE else EXPAND_SECONDARY_MODE,
        )
        pause(EXIT_SETTLE_MS)
        check(callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            "Возвращённое приложение осталось в закрытом split-контейнере"
        }
    }

    /**
     * Ends only a session that still contains one of our picker base tasks. The selected app is
     * expanded through the firmware's own 101/102 mode transition; unrelated native split state
     * is left untouched.
     */
    fun closePickers(
        pickerComponents: Map<SplitPane, String>,
        expectedHostTaskIds: Set<Int>? = null,
    ) {
        val before = snapshot()
        val pickerTasks = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                (task.isNativeSplitBootstrap() &&
                    (expectedHostTaskIds == null || task.id in expectedHostTaskIds)) ||
                    pickerComponents.values.any { component -> task.matchesComponent(component) } ||
                    LEGACY_PICKER_COMPONENTS.any { component -> task.matchesComponent(component) }
            }
            .toList()
        if (pickerTasks.isEmpty()) {
            closeOwnedGate()
            return
        }
        val roots = nativeRootIds()

        val focusedRootId = before.foregroundTask()
            ?.takeIf { task ->
                pickerComponents.values.none { component -> task.matchesComponent(component) }
            }
            ?.rootId
        val selectedPane = SplitPane.entries.firstOrNull { pane ->
            roots.getValue(pane) == focusedRootId
        } ?: SplitPane.entries.firstOrNull { pane ->
            before.root(roots.getValue(pane))
                ?.resolvedTopTask()
                ?.let { top ->
                    pickerComponents.values.none { component -> top.matchesComponent(component) }
                }
                ?: false
        }
        if (selectedPane != null) {
            val expandedMode = when (selectedPane) {
                SplitPane.PRIMARY -> EXPAND_PRIMARY_MODE
                SplitPane.SECONDARY -> EXPAND_SECONDARY_MODE
            }
            callVoid("service call activity_task 114 i32 $expandedMode")
            pause(EXIT_SETTLE_MS)
        } else {
            // With two bare pickers there is no user app to promote.
            run("input keyevent KEYCODE_HOME")
            pause(EXIT_SETTLE_MS)
        }

        val current = snapshot()
        pickerTasks.forEach { previous ->
            current.roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .firstOrNull { it.id == previous.id && it.packageName == previous.packageName }
                ?.let(::removeTaskSafely)
        }
        closeOwnedGate()
        check(callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            "Прошивка не закрыла split-сессию"
        }
    }

    private fun prunePane(
        rootId: Int,
        hostTaskId: Int,
        preservedPackage: String?,
    ) {
        val tasks = snapshot().root(rootId)?.tasks.orEmpty()
        val preserved = tasks
            .filter { it.packageName == preservedPackage && it.id != hostTaskId }
            .maxByOrNull(SplitTask::id)
        tasks.asSequence()
            .filterNot { it.id == hostTaskId }
            .filterNot { preserved != null && it.id == preserved.id }
            .forEach(::removeTaskSafely)
    }

    private fun launchPickerInPane(
        pane: SplitPane,
        rootId: Int,
        pickerComponent: String,
    ): SplitTask {
        startPickerInPane(pane, pickerComponent)
        pause(PICKER_SETTLE_MS)
        return awaitTaskMatching { task ->
            task.rootId == rootId &&
                task.visible &&
                task.isDenzaPickerBase() &&
                task.matchesComponent(pickerComponent) &&
                task.matchesTopComponent(pickerComponent)
        }
    }

    private fun launchPickerTask(
        pane: SplitPane,
        pickerComponent: String,
        excludedTaskIds: Set<Int>,
    ): SplitTask {
        startPickerInPane(pane, pickerComponent)
        pause(PICKER_SETTLE_MS)
        return awaitTaskMatching { task ->
            task.id !in excludedTaskIds &&
                task.rootId > 0 &&
                task.isDenzaPickerBase() &&
                task.matchesComponent(pickerComponent)
        }
    }

    private fun startPickerInPane(
        pane: SplitPane,
        pickerComponent: String,
    ) {
        val category = when (pane) {
            SplitPane.PRIMARY -> PRIMARY_PICKER_CATEGORY
            SplitPane.SECONDARY -> SECONDARY_PICKER_CATEGORY
        }
        run(
            "am start -a android.intent.action.MAIN " +
                "-c $category " +
                "-n ${shellQuote(pickerComponent)} " +
                "-f $PICKER_LAUNCH_FLAGS",
        )
    }

    private fun dragDividerToBalanced() {
        val inputState = shell("dumpsys input").also(::validateOutput)
        val dividerLine = inputState.lineSequence().firstOrNull { line ->
            line.contains("multi-divider-shadow") && line.contains("frame=[")
        } ?: error("Нативный drag control не появился")
        val divider = DIVIDER_FRAME_PATTERN.find(dividerLine)
            ?: error("Нативный drag control не появился")
        val left = divider.groupValues[1].toInt()
        val right = divider.groupValues[2].toInt()
        val startX = ((left + right) / 2).coerceIn(EDGE_INSET, DISPLAY_WIDTH - EDGE_INSET)
        val endX = if (startX < DISPLAY_WIDTH / 2) LEFT_DIVIDER_X else RIGHT_DIVIDER_X
        run("input swipe $startX $DIVIDER_Y $endX $DIVIDER_Y $DIVIDER_DRAG_MS")
    }

    private fun removeBootstrapIfPresent(previous: SplitTask) {
        val current = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == previous.id && it.isNativeSplitBootstrap() }
            ?: return
        removeTaskSafely(current)
    }

    private fun removeUnkeptPickerTasks(
        pickerComponents: Set<String>,
        keptTaskIds: Set<Int>,
    ) {
        snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.id !in keptTaskIds &&
                    pickerComponents.any { component -> task.matchesComponent(component) }
            }
            .toList()
            .forEach(::removeTaskSafely)
    }

    private fun launchTarget(target: SplitLaunchTarget, pane: SplitPane) {
        val category = when (pane) {
            SplitPane.PRIMARY -> PRIMARY_PICKER_CATEGORY
            SplitPane.SECONDARY -> SECONDARY_PICKER_CATEGORY
        }
        run(
            "am start -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-c $category " +
                "-n ${shellQuote(target.componentName)} " +
                "-f $APP_LAUNCH_FLAGS",
        )
        pause(APP_LAUNCH_SETTLE_MS)
    }

    private fun awaitBootstrapTask(rootId: Int): SplitTask {
        repeat(TASK_DISCOVERY_ATTEMPTS) { attempt ->
            val root = snapshot().root(rootId)
            val task = root?.tasks?.firstOrNull { it.isNativeSplitBootstrap() }
            if (task != null && task.visible) return task
            if (attempt + 1 < TASK_DISCOVERY_ATTEMPTS) pause(TASK_DISCOVERY_INTERVAL_MS)
        }
        error("Прошивка не создала стартовую задачу split-контейнера")
    }

    private fun awaitPackageTask(packageName: String): SplitTask = awaitTaskMatching {
        it.packageName == packageName
    }

    private fun awaitTaskMatching(predicate: (SplitTask) -> Boolean): SplitTask {
        repeat(TASK_DISCOVERY_ATTEMPTS) { attempt ->
            snapshot().roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .filter(predicate)
                .maxByOrNull(SplitTask::id)
                ?.let { return it }
            if (attempt + 1 < TASK_DISCOVERY_ATTEMPTS) pause(TASK_DISCOVERY_INTERVAL_MS)
        }
        error("Запущенная задача не появилась в ActivityTaskManager")
    }

    private fun verifyPickerTasks(
        pickerComponents: Map<SplitPane, String>,
        rootIds: Map<SplitPane, Int>,
        hostTaskIds: Map<SplitPane, Int>,
        preservedPackages: Map<SplitPane, String>,
    ) {
        val state = snapshot()
        SplitPane.entries.forEach { pane ->
            val root = state.root(rootIds.getValue(pane))
                ?: error("Split-контейнер ${pane.name} исчез")
            val picker = root.tasks.firstOrNull {
                it.id == hostTaskIds.getValue(pane) &&
                    it.isDenzaPickerBase() &&
                    it.matchesComponent(pickerComponents.getValue(pane))
            } ?: error("Пикер ${pane.name} исчез")
            check(picker.bounds == root.bounds) {
                "Пикер ${pane.name} не принял размер split-контейнера"
            }
            if (preservedPackages[pane] == null) {
                check(
                    picker.visible &&
                        picker.matchesTopComponent(pickerComponents.getValue(pane)),
                ) { "Пикер ${pane.name} перекрыт посторонней задачей" }
            }
            check(root.tasks.size <= MAX_TASKS_PER_PANE) {
                "В ${pane.name} накопилось больше двух задач"
            }
        }
        check(callInt("service call activity_task 30") == AREA_BALANCED_SPLIT) {
            "Нативный split не активировался"
        }
    }

    private fun removeTaskSafely(task: SplitTask) {
        val baseActivity = task.activityName ?: error("У задачи ${task.id} нет base activity")
        // `am stack list` repeats the root top component on hidden child lines. It is a valid
        // task-top postcondition only when this task itself is resolved as top.
        val topPackage = task.topPackageName.takeIf { task.isTop } ?: "-"
        val topActivity = task.topActivityName.takeIf { task.isTop } ?: "-"
        val output = shell(
            "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin " +
                "--nice-name=denza_split_cmd $SPLIT_PROXY_CLASS remove-task ${task.id} " +
                "${shellQuote(task.packageName)} ${shellQuote(baseActivity)} " +
                "${shellQuote(topPackage)} ${shellQuote(topActivity)}",
        ).also(::validateOutput)
        val result = output.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith(SPLIT_PROXY_RESULT_PREFIX) }
            ?.removePrefix(SPLIT_PROXY_RESULT_PREFIX)
        check(result == "true") { "Не удалось безопасно удалить задачу ${task.id}" }
        pause(ROOT_SETTLE_MS)
    }

    private fun moveTask(taskId: Int, rootId: Int) {
        check(taskId > 0 && rootId > 0)
        run("am stack move-task $taskId $rootId true")
    }

    private fun promoteTask(task: SplitTask, targetRootId: Int) {
        check(task.id > 0 && targetRootId > 0)
        if (task.rootId == targetRootId) {
            // DiLink treats move-task into the current root as a no-op even with toTop=true.
            // Focus is the firmware-backed operation that promotes a task hidden by the picker.
            run("am task focus ${task.id}")
        } else {
            moveTask(task.id, targetRootId)
        }
    }

    private fun normalizeTaskToRoot(taskId: Int, rootId: Int) {
        val beforeRoot = snapshot().root(rootId)
            ?: error("Split-контейнер $rootId исчез")
        val beforeTask = beforeRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Задача приложения $taskId не вошла в split-контейнер")
        if (beforeTask.bounds == beforeRoot.bounds) return
        check(beforeRoot.bounds.hasArea()) { "Split-контейнер $rootId не имеет размера" }
        val bounds = beforeRoot.bounds
        run(
            "am task resize $taskId ${bounds.left} ${bounds.top} " +
                "${bounds.right} ${bounds.bottom}",
        )
        pause(ROOT_SETTLE_MS)
        val afterRoot = snapshot().root(rootId)
            ?: error("Split-контейнер $rootId исчез после изменения размера")
        val afterTask = afterRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Задача приложения $taskId исчезла после изменения размера")
        check(afterTask.bounds == afterRoot.bounds) {
            "Задача приложения $taskId не приняла размер split-контейнера"
        }
    }

    private fun nativeRootIds(): Map<SplitPane, Int> = SplitPane.entries.associateWith { pane ->
        callInt("service call activity_task 118 i32 ${pane.areaId}").also { rootId ->
            check(rootId > 0) { "Прошивка не вернула split-контейнер ${pane.areaId}" }
        }
    }

    private fun ensureGateOpen() {
        if (callBoolean("service call activity_task 123")) return
        callVoid("service call activity_task 126 i32 1")
        check(callBoolean("service call activity_task 123")) {
            "Прошивка не разрешила открыть split"
        }
        val store = gateLeaseStore ?: return
        if (!store.setOwned(true)) {
            runCatching { callVoid("service call activity_task 126 i32 0") }
            error("Не удалось сохранить владение split-gate")
        }
    }

    private fun closeOwnedGate() {
        val store = gateLeaseStore
        if (store != null && !store.isOwned()) return
        if (callBoolean("service call activity_task 123")) {
            callVoid("service call activity_task 126 i32 0")
            check(!callBoolean("service call activity_task 123")) {
                "Прошивка не закрыла split-gate"
            }
        }
        if (store != null) {
            check(store.setOwned(false)) { "Не удалось освободить split-gate" }
        }
    }

    private fun ensureSupported(packageName: String) {
        val quoted = shellQuote(packageName)
        if (callBoolean("service call activity_task 112 s16 $quoted")) return
        callVoid("service call activity_task 125 s16 $quoted")
        check(callBoolean("service call activity_task 112 s16 $quoted")) {
            "Прошивка не добавила $packageName в split"
        }
    }

    private fun snapshot(): SplitTaskSnapshot {
        val output = shell("am stack list").also(::validateOutput)
        return SplitTaskSnapshot.parse(output)
    }

    private fun callBoolean(command: String): Boolean = callInt(command) != 0

    private fun callInt(command: String): Int {
        val output = shell(command).also(::validateOutput)
        val parcel = PARCEL_PATTERN.find(output)?.groupValues?.get(1)
            ?: error("Некорректный ответ activity_task")
        val words = WORD_PATTERN.findAll(parcel).map { it.value }.toList()
        check(words.size >= 2 && words[0].toLong(16) == 0L) {
            "Ошибка activity_task: ${output.trim()}"
        }
        return words[1].toLong(16).toInt()
    }

    private fun callVoid(command: String) {
        val output = shell(command).also(::validateOutput)
        val parcel = PARCEL_PATTERN.find(output)?.groupValues?.get(1)
            ?: error("Некорректный ответ activity_task")
        val words = WORD_PATTERN.findAll(parcel).map { it.value }.toList()
        check(words.isNotEmpty() && words[0].toLong(16) == 0L) {
            "Ошибка activity_task: ${output.trim()}"
        }
    }

    private fun run(command: String) {
        validateOutput(shell(command))
    }

    private fun validateOutput(output: String) {
        check(
            !output.contains("Error:", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true) &&
                !output.contains("UNKNOWN_TRANSACTION", ignoreCase = true),
        ) { output.trim().ifBlank { "shell command failed" } }
    }

    private fun SplitTask.matchesComponent(flattenedComponent: String): Boolean {
        val separator = flattenedComponent.indexOf('/')
        if (separator <= 0 || separator == flattenedComponent.lastIndex) return false
        val expectedPackage = flattenedComponent.substring(0, separator)
        val rawClass = flattenedComponent.substring(separator + 1)
        val expectedClass = if (rawClass.startsWith('.')) expectedPackage + rawClass else rawClass
        val actualClass = activityName?.let { name ->
            if (name.startsWith('.')) packageName + name else name
        }
        return packageName == expectedPackage && actualClass == expectedClass
    }

    private fun SplitTask.matchesTopComponent(flattenedComponent: String): Boolean {
        val separator = flattenedComponent.indexOf('/')
        if (separator <= 0 || separator == flattenedComponent.lastIndex) return false
        val expectedPackage = flattenedComponent.substring(0, separator)
        val rawClass = flattenedComponent.substring(separator + 1)
        val expectedClass = if (rawClass.startsWith('.')) expectedPackage + rawClass else rawClass
        return topPackageName == expectedPackage && topActivityName == expectedClass
    }

    private fun SplitTask.matchesAnyComponent(components: Set<String>): Boolean =
        components.any { component -> matchesComponent(component) }

    private fun SplitTask.matchesAnyTopComponent(components: Set<String>): Boolean =
        components.any { component -> matchesTopComponent(component) }

    private fun SplitTask.isNativeSplitBootstrap(): Boolean =
        (packageName == STOCK_PICKER_PACKAGE && activityName == STOCK_PICKER_ACTIVITY) ||
            (packageName == STOCK_BOOTSTRAP_PACKAGE && activityName == STOCK_BOOTSTRAP_ACTIVITY)

    private fun SplitTask.matchesOwnTopComponent(): Boolean =
        topPackageName == packageName &&
            topActivityName == activityName

    private fun SplitTask.isDenzaPickerBase(): Boolean =
        (packageName == SPLIT_HOST_PACKAGE && activityName == SPLIT_PICKER_ACTIVITY) ||
            (packageName == LEGACY_PICKER_PACKAGE &&
                activityName in setOf(LEGACY_PRIMARY_PICKER_ACTIVITY, LEGACY_SECONDARY_PICKER_ACTIVITY))

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val SPLIT_HOST_PACKAGE = "dev.denza.split"
        const val MAIN_DISPLAY_ID = 0
        const val AREA_HOME = 0
        const val AREA_BALANCED_SPLIT = 3
        const val EXPAND_PRIMARY_MODE = 101
        const val EXPAND_SECONDARY_MODE = 102
        const val APP_LAUNCH_FLAGS = "0x10200000"
        const val PICKER_LAUNCH_FLAGS = "0x18010000"
        const val PRIMARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_PRIMARY"
        const val SECONDARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_SECOND"
        const val MAX_TASKS_PER_PANE = 2
        const val TASK_DISCOVERY_ATTEMPTS = 12
        const val TASK_DISCOVERY_INTERVAL_MS = 100L
        const val PICKER_SETTLE_MS = 150L
        const val HOME_SETTLE_MS = 650L
        const val NATIVE_PICKER_SETTLE_MS = 450L
        const val APP_LAUNCH_SETTLE_MS = 250L
        const val ROOT_SETTLE_MS = 120L
        const val EXIT_SETTLE_MS = 650L
        const val DISPLAY_WIDTH = 2_560
        const val EDGE_INSET = 50
        const val LEFT_DIVIDER_X = 856
        const val RIGHT_DIVIDER_X = 1_704
        const val DIVIDER_Y = 800
        const val DIVIDER_DRAG_MS = 400
        const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
        const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
        const val STOCK_BOOTSTRAP_PACKAGE = "com.byd.sr"
        const val STOCK_BOOTSTRAP_ACTIVITY = "com.byd.sr.MainActivity"
        const val SPLIT_PICKER_ACTIVITY = "dev.denza.split.SplitPickerActivity"
        const val LEGACY_PICKER_PACKAGE = "dev.denza.apps"
        const val LEGACY_PRIMARY_PICKER_ACTIVITY =
            "dev.denza.apps.feature.split.SplitPrimaryPickerActivity"
        const val LEGACY_SECONDARY_PICKER_ACTIVITY =
            "dev.denza.apps.feature.split.SplitSecondaryPickerActivity"
        val LEGACY_PICKER_COMPONENTS = setOf(
            "$LEGACY_PICKER_PACKAGE/$LEGACY_PRIMARY_PICKER_ACTIVITY",
            "$LEGACY_PICKER_PACKAGE/$LEGACY_SECONDARY_PICKER_ACTIVITY",
        )
        const val SPLIT_PROXY_CLASS = "dev.denza.apps.feature.split.SplitTaskProxyMain"
        const val SPLIT_PROXY_RESULT_PREFIX = "DENZA_SPLIT_RESULT:"
        val PARCEL_PATTERN = Regex("Parcel\\(([^']+)")
        val WORD_PATTERN = Regex("[0-9a-fA-F]{8}")
        val DIVIDER_FRAME_PATTERN = Regex(
            "frame=\\[(-?[0-9]+),-?[0-9]+]\\[(-?[0-9]+),-?[0-9]+]",
        )
    }
}

internal data class SplitPickerLivePane(
    val pane: SplitPane,
    val hostTaskId: Int,
    val appTaskId: Int?,
    val appPackageName: String?,
)
