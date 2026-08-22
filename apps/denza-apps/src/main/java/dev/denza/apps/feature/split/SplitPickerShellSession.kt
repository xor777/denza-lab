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
    /** Waits until a foreground control task has actually left the native organizer. */
    fun awaitTaskRemoved(taskId: Int) {
        check(taskId > 0) { "Control task id is unavailable" }
        repeat(TASK_REMOVAL_ATTEMPTS) { attempt ->
            val present = snapshot().roots.any { root -> root.tasks.any { it.id == taskId } }
            if (!present) return
            if (attempt + 1 < TASK_REMOVAL_ATTEMPTS) pause(TASK_REMOVAL_INTERVAL_MS)
        }
        error("Control-задача $taskId не закрылась")
    }

    /** Establishes the only live-proven neutral state before rebuilding a consumed split pair. */
    fun prepareControlReturn(taskId: Int) {
        awaitTaskRemoved(taskId)
        run("input keyevent KEYCODE_HOME")
        pause(HOME_SETTLE_MS)
        check(callInt("service call activity_task 30") == AREA_HOME) {
            "Прошивка не перешла на домашний экран перед восстановлением split"
        }
    }

    /** Removes picker identities invalidated when BYD consumed the old pair for control UI. */
    fun discardInvalidatedPickerBases(pickerComponents: Set<String>) {
        val invalidated = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
            }
            .toList()
        invalidated.forEach(::removeTaskSafely)
        val remainingIds = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .mapTo(mutableSetOf(), SplitTask::id)
        check(invalidated.none { it.id in remainingIds }) {
            "Прошивка сохранила недействительный picker-task"
        }
    }

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
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): Map<SplitPane, SplitPickerLivePane>? {
        val area = callInt("service call activity_task 30")
        if (area != AREA_BALANCED_SPLIT && area != AREA_FULL_IVI) return null
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

            val top = if (area == AREA_BALANCED_SPLIT) {
                root.resolvedTopTask()
            } else {
                root.resolvedCoveredTopTask()
                    ?: root.resolveExpectedCoveredApp(expectedApps[pane])
            } ?: return null
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
                appPackageName = app?.effectivePackageName(),
            )
        }
    }

    /** Brings an exact owned pair back above Home/fullscreen without rebuilding either pane. */
    fun revealOwnedSession(
        existing: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Map<SplitPane, SplitPickerLivePane> {
        val area = callInt("service call activity_task 30")
        if (area == AREA_BALANCED_SPLIT) return existing
        check(area == AREA_FULL_IVI) { "Split-сессия больше не скрыта полноэкранным окном" }

        val focusTaskId = SplitPane.entries.asSequence()
            .mapNotNull { pane -> existing[pane]?.appTaskId }
            .firstOrNull()
            ?: SplitPane.entries.asSequence()
                .mapNotNull { pane -> existing[pane]?.hostTaskId }
                .firstOrNull()
            ?: error("В split-сессии нет задачи для возврата")
        run("am task focus $focusTaskId")
        pause(EXIT_SETTLE_MS)
        check(callInt("service call activity_task 30") == AREA_BALANCED_SPLIT) {
            "Прошивка не вернула существующий split на экран"
        }
        val revealed = existingOwnedSession(pickerComponents)
            ?: error("Существующая split-сессия изменилась при возврате")
        check(revealed == existing) { "Состав split-сессии изменился при возврате" }
        return revealed
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
        val expectedArea = expectedSelectionArea(
            pane = pane,
            currentArea = callInt("service call activity_task 30"),
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
            .none { it.effectivePackageName() == target.packageName }
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
        check(
            before.root(otherRootId)
                ?.resolvedTopTask()
                ?.effectivePackageName() != target.packageName,
        ) {
            "Одно приложение нельзя открыть в двух окнах"
        }
        ensureSupported(target.packageName)

        val existingCandidates = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { it.effectivePackageName() == target.packageName }
            .sortedByDescending(SplitTask::id)
            .toList()

        // A picker tap is authoritative proof that this pane is being selected. Clear every
        // task above its exact permanent base before requiring the picker to be the root top.
        // This also recovers a transparent SplitAppHostActivity left by an interrupted launch;
        // otherwise that input window can remain focused over the visible picker forever.
        before.root(targetRootId)?.tasks.orEmpty()
            .filterNot { it.id == pickerHost.id }
            .forEach(::removeTaskSafely)
        existingCandidates.asSequence()
            .filterNot { candidate -> candidate.rootId == targetRootId }
            .forEach(::removeTaskSafely)

        val clearedPicker = snapshot().root(targetRootId)?.tasks?.firstOrNull { task ->
            task.id == pickerHost.id &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        }
        check(
            clearedPicker != null &&
                clearedPicker.visible &&
                clearedPicker.matchesAnyTopComponent(pickerComponents)
        ) { "Пикер этого окна не освободился перед запуском приложения" }

        var targetTask: SplitTask? = null
        try {
            val launchedTask = launchTargetWithFallback(
                target = target,
                pane = pane,
                rootId = targetRootId,
                pickerTaskId = pickerHost.id,
                pickerComponents = pickerComponents,
            )
            targetTask = launchedTask
            normalizeTaskToRoot(launchedTask.id, targetRootId)
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
            check(top.id == launchedTask.id && top.effectivePackageName() == target.packageName) {
                "Приложение ${target.packageName} не стало верхним в выбранном окне"
            }
            check(top.bounds == root.bounds) {
                "Приложение ${target.packageName} не приняло размер выбранного окна"
            }
            check(callInt("service call activity_task 30") == expectedArea) {
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
        } catch (error: Throwable) {
            targetTask?.let { failedTask ->
                runCatching {
                    snapshot().roots.asSequence()
                        .filter { it.displayId == MAIN_DISPLAY_ID }
                        .flatMap { it.tasks.asSequence() }
                        .firstOrNull { task -> task.id == failedTask.id }
                        ?.let(::removeTaskSafely)
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    /**
     * Stable-host launch is an optional upgrade over the live-proven direct BYD launch.
     * Never let an incomplete host attempt weaken the baseline: remove its exact artifacts,
     * prove the permanent picker is interactive again, then fall back to the old command.
     */
    private fun launchTargetWithFallback(
        target: SplitLaunchTarget,
        pane: SplitPane,
        rootId: Int,
        pickerTaskId: Int,
        pickerComponents: Set<String>,
    ): SplitTask {
        // singleTask/singleInstance launchers cannot join the host task by Android contract.
        // Trying still lets SmartMulti place their new task in the opposite pane, causing a
        // global split rebalance before we can correct it. Keep those launchers on the proven
        // direct BYD path from the outset; this is a platform property, not an app allowlist.
        if (target.launchMode >= LAUNCH_MODE_SINGLE_TASK) {
            return launchTargetDirectIntoRoot(target, pane, rootId)
        }

        val hostResult = runCatching {
            launchTargetInStableHost(target, pane, rootId, pickerComponents)
        }
        hostResult.getOrNull()?.let { return it }
        val hostError = hostResult.exceptionOrNull()
            ?: error("Host-запуск завершился без результата")

        try {
            removeTargetPackageTasks(target.packageName)
            requirePickerReady(rootId, pickerTaskId, pickerComponents)
        } catch (cleanupError: Throwable) {
            cleanupError.addSuppressed(hostError)
            throw cleanupError
        }

        return try {
            launchTargetDirectIntoRoot(target, pane, rootId)
        } catch (directError: Throwable) {
            runCatching { removeTargetPackageTasks(target.packageName) }
                .exceptionOrNull()
                ?.let(directError::addSuppressed)
            runCatching { requirePickerReady(rootId, pickerTaskId, pickerComponents) }
                .exceptionOrNull()
                ?.let(directError::addSuppressed)
            directError.addSuppressed(hostError)
            throw directError
        }
    }

    private fun launchTargetDirectIntoRoot(
        target: SplitLaunchTarget,
        pane: SplitPane,
        rootId: Int,
    ): SplitTask {
        val direct = launchTargetDirect(target, pane)
        promoteTask(direct, rootId)
        pause(ROOT_SETTLE_MS)
        return snapshot().root(rootId)?.tasks?.firstOrNull { task ->
            task.id == direct.id && task.packageName == target.packageName
        } ?: error("Прямой запуск не вошёл в выбранное окно")
    }

    private fun launchTargetDirect(
        target: SplitLaunchTarget,
        pane: SplitPane,
    ): SplitTask {
        ensureGateOpen()
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
        return awaitTaskMatching { task -> task.packageName == target.packageName }
    }

    private fun removeTargetPackageTasks(packageName: String) {
        snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.packageName == packageName ||
                    (task.isDenzaAppHost() && task.topPackageName == packageName)
            }
            .toList()
            .forEach(::removeTaskSafely)
    }

    private fun requirePickerReady(
        rootId: Int,
        pickerTaskId: Int,
        pickerComponents: Set<String>,
    ) {
        val root = snapshot().root(rootId)
            ?: error("Split-контейнер исчез после неудачного host-запуска")
        val picker = root.tasks.singleOrNull { task ->
            task.id == pickerTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        } ?: error("Постоянный пикер потерян после неудачного host-запуска")
        check(
            root.tasks.size == 1 &&
                picker.visible &&
                picker.matchesAnyTopComponent(pickerComponents)
        ) { "Host-запуск не освободил пикер для безопасного fallback" }
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
            .map { task -> task.effectivePackageName() }
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
            top.effectivePackageName() == target.packageName &&
            top.id != pickerTaskId &&
            top.bounds == root.bounds
        ) {
            return SplitPickerPlacement(
                pane = pane,
                hostTaskId = pickerTaskId,
                appTaskId = top.id,
                packageName = top.effectivePackageName(),
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
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitNavigationReturnPlan {
        val roots = nativeRootIds()
        val originalPane = SplitPane.entries.firstOrNull { pane ->
            roots.getValue(pane) == originalRootTaskId
        }
        val hiddenOwnedSession = existingOwnedSession(pickerComponents, expectedApps)
        if (hiddenOwnedSession != null) {
            revealOwnedSession(hiddenOwnedSession, pickerComponents)
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
            .map { task -> SplitDisplacedTask(task.id, task.effectivePackageName()) }
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
        check(
            top.id == taskId &&
                top.effectivePackageName() == packageName &&
                top.bounds == root.bounds,
        ) {
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
            .firstOrNull {
                it.id == taskId &&
                    (it.effectivePackageName() == packageName || it.isDenzaAppHost())
            }
            ?: return false
        check(!task.isDenzaPickerBase()) { "Нельзя удалить host-пикер как приложение" }
        removeTaskSafely(task)
        return true
    }

    /** Removes only the exact permanent picker reparented out of its dismissed native pane. */
    fun removePickerArtifact(taskId: Int, pickerComponents: Set<String>): Boolean {
        val task = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull {
                it.id == taskId &&
                    it.isDenzaPickerBase() &&
                    it.matchesAnyComponent(pickerComponents)
            }
            ?: return false
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
                    task.effectivePackageName() == packageName &&
                    !task.isDenzaPickerBase()
            }
            .forEach(::removeTaskSafely)
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
            .firstOrNull { it.id == taskId && it.effectivePackageName() == packageName }
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
     * Ends native split instead of merely expanding one pane. Firmware modes 101/102 retain the
     * hidden peer root and divider, so disabled means: close the gate, move the exact foreground
     * task to the full IVI root, then remove only picker and unselected host artifacts.
     */
    fun closePickers(
        pickerComponents: Map<SplitPane, String>,
        expectedHostTaskIds: Set<Int>? = null,
    ) {
        val before = snapshot()
        val expectedNativeHosts = expectedHostTaskIds.orEmpty()
        val pickerTasks = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.isStockSplitPicker() ||
                    (task.isStockSplitBootstrap() && task.id in expectedNativeHosts) ||
                    pickerComponents.values.any { component -> task.matchesComponent(component) } ||
                    LEGACY_PICKER_COMPONENTS.any { component -> task.matchesComponent(component) }
            }
            .toList()
        val pickerTaskIds = pickerTasks.mapTo(mutableSetOf(), SplitTask::id)
        // `am stack list` orders roots by z-order, not by product ownership. A visible picker
        // may therefore be reported before the real application in the peer pane. Do not turn
        // that into "no foreground"; keep walking visible root tops until an actual user task
        // is found.
        val foreground = before.roots.asSequence()
            .filter { root -> root.displayId == MAIN_DISPLAY_ID && root.activityType != "home" }
            .mapNotNull(SplitRootTask::resolvedTopTask)
            .firstOrNull { task ->
                task.id !in pickerTaskIds &&
                    !task.isDenzaPickerBase() &&
                    !task.isNativeSplitBootstrap()
            }
        val hostArtifacts = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task -> task.isDenzaAppHost() && task.id != foreground?.id }
            .toList()

        closeGateForDisabledProduct()

        if (foreground != null) {
            val fullRootId = fullIviRootTaskId()
            if (foreground.rootId != fullRootId) {
                moveTask(foreground.id, fullRootId)
            }
            normalizeTaskToRoot(foreground.id, fullRootId)
            pause(EXIT_SETTLE_MS)
        } else {
            run("input keyevent KEYCODE_HOME")
            pause(EXIT_SETTLE_MS)
        }

        val current = snapshot()
        (pickerTasks + hostArtifacts).distinctBy(SplitTask::id).forEach { previous ->
            current.roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .firstOrNull { it.id == previous.id && it.packageName == previous.packageName }
                ?.let(::removeTaskSafely)
        }

        val after = snapshot()
        if (foreground != null) {
            val fullRootId = fullIviRootTaskId()
            when (val area = callInt("service call activity_task 30")) {
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
            check(callInt("service call activity_task 30") == AREA_HOME) {
                "Пустой split не закрылся на домашний экран"
            }
        }
    }

    fun fullIviRootTaskId(): Int =
        callInt("service call activity_task 118 i32 $AREA_FULL_IVI").also { rootId ->
            check(rootId > 0) { "Прошивка не вернула полноэкранный IVI-контейнер" }
        }

    private fun prunePane(
        rootId: Int,
        hostTaskId: Int,
        preservedPackage: String?,
    ) {
        val tasks = snapshot().root(rootId)?.tasks.orEmpty()
        val preserved = tasks
            .filter {
                it.effectivePackageName() == preservedPackage && it.id != hostTaskId
            }
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

    private fun launchTargetInStableHost(
        target: SplitLaunchTarget,
        pane: SplitPane,
        rootId: Int,
        pickerComponents: Set<String>,
    ): SplitTask {
        // The short-lived launcher entry is itself a fullscreen task. On this firmware it can
        // close mIsEnterSplit after openPickers() established the native roots, so the gate must
        // be reasserted at the actual split launch boundary.
        ensureGateOpen()
        val category = when (pane) {
            SplitPane.PRIMARY -> PRIMARY_PICKER_CATEGORY
            SplitPane.SECONDARY -> SECONDARY_PICKER_CATEGORY
        }
        val existingHostIds = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task -> task.isDenzaAppHost() }
            .mapTo(mutableSetOf(), SplitTask::id)
        var host: SplitTask? = null
        try {
            val targetClass = target.componentName.substringAfter('/', missingDelimiterValue = "")
            check(targetClass.isNotBlank()) { "Некорректный компонент приложения" }
            run(
                "am start -a android.intent.action.MAIN " +
                    "-c $category " +
                    "-n ${shellQuote(SPLIT_APP_HOST_COMPONENT)} " +
                    "--es ${shellQuote(SPLIT_HOST_TARGET_PACKAGE_EXTRA)} " +
                    "${shellQuote(target.packageName)} " +
                    "--es ${shellQuote(SPLIT_HOST_TARGET_ACTIVITY_EXTRA)} " +
                    "${shellQuote(targetClass)} " +
                    "-f $PICKER_LAUNCH_FLAGS",
            )
            pause(HOST_LAUNCH_SETTLE_MS)
            host = awaitTaskMatching { task ->
                task.id !in existingHostIds && task.isDenzaAppHost()
            }
            pause(APP_LAUNCH_SETTLE_MS)
            var launched = awaitLaunchedTarget(host.id, target.packageName)
            if (launched.id != host.id) {
                // Activity-context launchers with singleTask/redirect semantics may create their
                // real task in the opposite native root. Move the exact observed task before
                // removing the host; dropping the host first can collapse SmartMulti's pane.
                if (launched.rootId != rootId) {
                    moveTask(launched.id, rootId)
                    pause(ROOT_SETTLE_MS)
                    launched = snapshot().root(rootId)?.tasks?.firstOrNull { task ->
                        task.id == launched.id && task.packageName == target.packageName
                    } ?: error("Запущенная задача ${launched.id} не вошла в выбранное окно")
                }
                val displacedHostRootId = host.rootId.takeIf { it != rootId }
                removeAppHostIfPresent(host.id)
                if (displacedHostRootId != null) {
                    // BYD may report the newly exposed task as visible/top while leaving its
                    // surface black after removing a host that raced into the other pane.
                    // Briefly focus that exact exposed task, then return focus to the selected
                    // app. This asks SmartMulti to redraw both roots without moving either task.
                    val exposed = snapshot().root(displacedHostRootId)?.resolvedTopTask()
                        ?: error("Другое split-окно не восстановилось после удаления host")
                    check(
                        !exposed.isDenzaAppHost() &&
                            (!exposed.isDenzaPickerBase() ||
                                exposed.matchesAnyComponent(pickerComponents)),
                    ) { "В другом split-окне появился неизвестный host" }
                    run("am task focus ${exposed.id}")
                    pause(ROOT_SETTLE_MS)
                    run("am task focus ${launched.id}")
                    pause(ROOT_SETTLE_MS)
                }
                host = null
            } else {
                // SmartMulti can reparent the host before the first shell snapshot. Keep
                // observing long enough for a redirect-owned task to appear, but never accept
                // a still-hosted app in the opposite pane: that case remains on the proven
                // cleanup + direct-launch fallback path.
                check(launched.rootId == rootId) {
                    "Host-задача приложения оказалась в другом split-окне"
                }
            }
            return launched
        } catch (error: Throwable) {
            val failedHostIds = buildSet {
                host?.id?.let(::add)
                snapshot().roots.asSequence()
                    .filter { it.displayId == MAIN_DISPLAY_ID }
                    .flatMap { it.tasks.asSequence() }
                    .filter { task -> task.id !in existingHostIds && task.isDenzaAppHost() }
                    .mapTo(this, SplitTask::id)
            }
            failedHostIds.forEach { taskId ->
                runCatching { removeAppHostIfPresent(taskId) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun removeAppHostIfPresent(taskId: Int) {
        snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { task -> task.id == taskId && task.isDenzaAppHost() }
            ?.let(::removeTaskSafely)
    }

    /**
     * Normal launcher Activities can be added above the host. Redirect launchers such as Waze
     * may instead finish before attachment and let their real Activity create a new task. Prefer
     * that real package-owned task, but retain the exact host as a fallback after the redirect
     * window has elapsed. Existing tasks for the package were removed before launch, so a
     * package-owned candidate is unambiguous here.
     */
    private fun awaitLaunchedTarget(hostTaskId: Int, packageName: String): SplitTask {
        var hostedFallback: SplitTask? = null
        repeat(TASK_DISCOVERY_ATTEMPTS) { attempt ->
            val tasks = snapshot().roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .toList()
            tasks.asSequence()
                .filter { task ->
                    task.id != hostTaskId && task.packageName == packageName
                }
                .maxByOrNull(SplitTask::id)
                ?.let { return it }
            tasks.firstOrNull { task ->
                task.id == hostTaskId &&
                    task.isDenzaAppHost() &&
                    task.topPackageName == packageName
            }?.let { hostedFallback = it }
            if (attempt + 1 < TASK_DISCOVERY_ATTEMPTS) pause(TASK_DISCOVERY_INTERVAL_MS)
        }
        return hostedFallback
            ?: error("Запущенная задача $packageName не появилась в ActivityTaskManager")
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
        if (result != "true") {
            val taskStillExists = snapshot().roots.asSequence()
                .flatMap { it.tasks.asSequence() }
                .any { it.id == task.id }
            check(!taskStillExists) { "Не удалось безопасно удалить задачу ${task.id}" }
            return
        }
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
        // On this DiLink 5.1 build tx123 is `isCanSplit()`: for the BYD platform branch it is
        // a constant capability answer, not the current mIsEnterSplit value. Only tx126 changes
        // the mutable gate, and it is idempotent in the firmware.
        callVoid("service call activity_task 126 i32 1")
        val store = gateLeaseStore ?: return
        if (!store.setOwned(true)) {
            runCatching { callVoid("service call activity_task 126 i32 0") }
            error("Не удалось сохранить владение split-gate")
        }
    }

    private fun closeGateForDisabledProduct() {
        val store = gateLeaseStore
        callVoid("service call activity_task 126 i32 0")
        if (store != null && store.isOwned()) {
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
        isStockSplitPicker() || isStockSplitBootstrap()

    private fun SplitTask.isStockSplitPicker(): Boolean =
        packageName == STOCK_PICKER_PACKAGE && activityName == STOCK_PICKER_ACTIVITY

    private fun SplitTask.isStockSplitBootstrap(): Boolean =
        packageName == STOCK_BOOTSTRAP_PACKAGE && activityName == STOCK_BOOTSTRAP_ACTIVITY

    private fun SplitTask.matchesOwnTopComponent(): Boolean =
        topPackageName == packageName &&
            topActivityName == activityName

    /** Resolves a native root hidden by area=4, where `am stack list` marks every child hidden. */
    private fun SplitRootTask.resolvedCoveredTopTask(): SplitTask? {
        val exact = tasks.filter { task -> task.matchesOwnTopComponent() }
        if (exact.isNotEmpty()) return exact.first()
        return tasks.filter { task -> task.packageName == task.topPackageName }.singleOrNull()
    }

    /**
     * `am stack list` hides every child when a fullscreen root covers split and repeats only the
     * old root-top component. Exact persisted task id plus package identity is the narrow proof
     * that lets us reveal that owned scene without guessing which hidden child was top.
     */
    private fun SplitRootTask.resolveExpectedCoveredApp(
        expected: SplitPickerExpectedApp?,
    ): SplitTask? {
        expected ?: return null
        val task = tasks.singleOrNull { candidate -> candidate.id == expected.taskId }
            ?: return null
        val identityMatches = task.packageName == expected.packageName ||
            (task.isDenzaAppHost() && task.topPackageName == expected.packageName)
        return task.takeIf { identityMatches && it.bounds == bounds }
    }

    private fun SplitTask.isDenzaPickerBase(): Boolean =
        (packageName == SPLIT_HOST_PACKAGE && activityName == SPLIT_PICKER_ACTIVITY) ||
            (packageName == LEGACY_PICKER_PACKAGE &&
                activityName in setOf(LEGACY_PRIMARY_PICKER_ACTIVITY, LEGACY_SECONDARY_PICKER_ACTIVITY))

    private fun SplitTask.isDenzaAppHost(): Boolean =
        packageName == SPLIT_HOST_PACKAGE && activityName == SPLIT_APP_HOST_ACTIVITY

    private fun SplitTask.effectivePackageName(): String =
        if (isDenzaAppHost()) topPackageName ?: packageName else packageName

    private fun expectedSelectionArea(
        pane: SplitPane,
        currentArea: Int,
        otherRootVacant: Boolean,
    ): Int = when {
        currentArea == AREA_BALANCED_SPLIT -> AREA_BALANCED_SPLIT
        currentArea == pane.fullArea && otherRootVacant -> currentArea
        else -> error("Пикер больше не находится в рабочем окне")
    }

    private fun SplitTask.isEmptyRootMarker(): Boolean =
        id == rootId && packageName == "unknown" && activityName == null

    private val SplitPane.fullArea: Int
        get() = when (this) {
            SplitPane.PRIMARY -> AREA_PRIMARY_FULL
            SplitPane.SECONDARY -> AREA_SECONDARY_FULL
        }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val MAIN_DISPLAY_ID = 0
        const val AREA_HOME = 0
        const val AREA_PRIMARY_FULL = 1
        const val AREA_SECONDARY_FULL = 2
        const val AREA_BALANCED_SPLIT = 3
        const val AREA_FULL_IVI = 4
        const val EXPAND_PRIMARY_MODE = 101
        const val EXPAND_SECONDARY_MODE = 102
        const val APP_LAUNCH_FLAGS = "0x10200000"
        const val PICKER_LAUNCH_FLAGS = "0x18010000"
        const val PRIMARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_PRIMARY"
        const val SECONDARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_SECOND"
        const val MAX_TASKS_PER_PANE = 2
        const val LAUNCH_MODE_SINGLE_TASK = 2
        const val TASK_DISCOVERY_ATTEMPTS = 12
        const val TASK_DISCOVERY_INTERVAL_MS = 100L
        const val TASK_REMOVAL_ATTEMPTS = 30
        const val TASK_REMOVAL_INTERVAL_MS = 100L
        const val PICKER_SETTLE_MS = 150L
        const val HOME_SETTLE_MS = 650L
        const val NATIVE_PICKER_SETTLE_MS = 450L
        const val APP_LAUNCH_SETTLE_MS = 250L
        const val HOST_LAUNCH_SETTLE_MS = 100L
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
