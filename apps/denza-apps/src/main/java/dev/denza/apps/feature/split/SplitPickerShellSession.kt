package dev.denza.apps.feature.split

/**
 * Explicit, command-driven split session.
 *
 * This class never watches or interprets arbitrary foreground launches. Every mutation starts
 * from either the dedicated launcher or a tap in a picker, so the destination pane and expected
 * component are known before any task is moved.
 */
internal class SplitPickerShellSession(
    shell: (String) -> String,
    private val apkPath: String,
    private val settle: (Long) -> Unit = Thread::sleep,
    private val gateLeaseStore: SplitGateLeaseStore? = null,
    /**
     * The topology reads of the operation this session belongs to. The default is a private one,
     * which makes a stand-alone session share reads only within itself.
     */
    private val topology: SplitTopologyCache = SplitTopologyCache(),
    /** Where the shell-UID proxy is loaded from; the APK is the always-valid fallback. */
    private val proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
) {
    private val send = shell

    /**
     * Every command of every recipe, in the order the recipe sends it - unchanged, and the one
     * place that decides whether the shared topology read may outlive it (deny by default).
     */
    private fun shell(command: String): String {
        if (!SplitTopologyCache.isTopologyRead(command)) topology.invalidate()
        return send(command)
    }

    /**
     * Every settle pause of every recipe. It drops the shared topology read first: a recipe that
     * waits is a recipe that expects the car to have changed underneath it.
     */
    private fun pause(millis: Long) {
        topology.invalidate()
        settle(millis)
    }

    /**
     * Waits read-only while the user is dragging the native divider.
     *
     * BYD exposes the stock picker Activity before the drop is accepted and can report balanced
     * area 3 while the pointer is still down. No task operation may run until both signals settle
     * because launching our picker would steal the still-active divider gesture.
     */
    fun awaitNativePickerCommit(): Boolean {
        var releasedBalancedSamples = 0
        var releasedNonBalancedSamples = 0
        repeat(NATIVE_PICKER_COMMIT_ATTEMPTS) { attempt ->
            val balanced = callInt("service call activity_task 30") == AREA_BALANCED_SPLIT
            val pointerActive = hasActivePointer(shell("dumpsys input"))
            if (pointerActive) {
                releasedBalancedSamples = 0
                releasedNonBalancedSamples = 0
            } else if (balanced) {
                releasedBalancedSamples += 1
                releasedNonBalancedSamples = 0
            } else {
                releasedBalancedSamples = 0
                releasedNonBalancedSamples += 1
            }
            if (releasedBalancedSamples >= NATIVE_PICKER_RELEASED_SAMPLES) return true
            if (releasedNonBalancedSamples >= NATIVE_PICKER_CANCELLED_SAMPLES) return false
            if (attempt + 1 < NATIVE_PICKER_COMMIT_ATTEMPTS) {
                pause(NATIVE_PICKER_COMMIT_INTERVAL_MS)
            }
        }
        return false
    }

    /** Final read-only guard immediately before a stock-picker observation becomes a mutation. */
    fun nativePickerMutationAllowed(): Boolean =
        callInt("service call activity_task 30") == AREA_BALANCED_SPLIT &&
            !hasActivePointer(shell("dumpsys input"))

    /**
     * Closes only the split gate owned by this product after Home is authoritative.
     *
     * DiLink retains a separate global "last split pair" and otherwise resurrects that OEM pair
     * when the user launches either remembered member from Home. Keep the lease so the next
     * explicit Split Screen launch can reopen the gate, but never touch a gate we did not acquire.
     */
    fun suspendOwnedGateForHome(): Boolean {
        val store = gateLeaseStore ?: return false
        if (!store.isOwned()) return false
        repeat(HOME_CONFIRM_ATTEMPTS) { attempt ->
            if (callInt("service call activity_task 30") == AREA_HOME) {
                callVoid("service call activity_task 126 i32 0")
                return true
            }
            if (attempt + 1 < HOME_CONFIRM_ATTEMPTS) pause(HOME_CONFIRM_INTERVAL_MS)
        }
        return false
    }

    private fun hasActivePointer(inputDump: String): Boolean {
        val stateStart = inputDump.indexOf("TouchStatesByDisplay:")
        if (stateStart < 0) return false
        val stateEnd = inputDump.indexOf("\n  Display:", startIndex = stateStart)
            .takeIf { it >= 0 }
            ?: inputDump.length
        return inputDump.substring(stateStart, stateEnd).contains("down=true")
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
    ): Map<SplitPane, SplitPickerLivePane>? =
        readOwnedSession(pickerComponents, expectedApps).scene

    /**
     * The same read, with the reason it refused.
     *
     * Acceptance v17 logged `scene-read: nothing of ours` on every single open and there was no way
     * to tell which predicate of which pane had disagreed - so the product rebuilt a scene that was
     * alive, restarted the music, and the evidence said nothing about why (U5, 1.3.2).
     */
    fun readOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitSceneRead {
        val area = callInt("service call activity_task 30")
        if (area != AREA_BALANCED_SPLIT && area != AREA_FULL_IVI && area != AREA_HOME) {
            return SplitSceneRead(null, "area=$area")
        }
        val roots = nativeRootIds()
        val state = snapshot()
        val panes = mutableMapOf<SplitPane, SplitPickerLivePane>()
        SplitPane.entries.forEach { pane ->
            val root = state.root(roots.getValue(pane))
                ?: return SplitSceneRead(null, "$pane: контейнера нет")
            val pickers = root.tasks.filter { task ->
                task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
            }
            if (pickers.size != 1 || root.tasks.size !in 1..MAX_TASKS_PER_PANE) {
                return SplitSceneRead(
                    null,
                    "$pane: пикеров ${pickers.size}, задач ${root.tasks.size}",
                )
            }
            val picker = pickers.single()
            if (picker.bounds != root.bounds) {
                return SplitSceneRead(null, "$pane: пикер не по размеру окна")
            }

            val expected = expectedApps[pane]
            val top = when {
                area == AREA_BALANCED_SPLIT -> root.resolvedTopTask()
                // The scene is covered, so `am stack list` marks every child hidden and repeats
                // only the old root-top component. The exact task id and package of the app this
                // process itself recorded is the narrow proof that lets it be raised (invariant 4).
                expected != null -> root.resolveExpectedCoveredApp(expected)
                // No app to name. Under a fullscreen window the pane's own picker reporting itself
                // is still accepted; under Home nothing is guessed at all - the root holding one
                // task, our picker, is the proof (правка E1, owner decision 2026-08-23).
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
            panes[pane] = SplitPickerLivePane(
                pane = pane,
                hostTaskId = picker.id,
                appTaskId = app?.id,
                appPackageName = app?.effectivePackageName(),
            )
        }
        return SplitSceneRead(panes, "adoptable")
    }

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
        // Правка W1 (v20 D1): area читается ДО паузы. Над накрытой сценой - Home (0) или чужое
        // fullscreen-окно (4) - дивайдера нет и settle ждать нечего: оконные эхо жеста возврата
        // рождали этот реконсил над area 0, слепая pause(1500) держала единственного воркера, и
        // следующий OPEN стоял за ним ~2 с очереди. Существование накрытой сцены проверяет
        // вызывающий (инвариант 5); дивайдерный settle остаётся неизменным для живых area.
        val area = callInt("service call activity_task 30")
        if (area == AREA_HOME || area == AREA_FULL_IVI) return null
        pause(DIVIDER_RECONCILE_SETTLE_MS)
        existingOwnedSession(pickerComponents)?.let { return it }
        if (callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) return null
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

        val roots = nativeRootIds()
        val nativeRootIds = roots.values.toSet()
        val state = snapshot()
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
                    task.effectivePackageName() == packageName
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
                moveTask(host.id, targetRootId, toTop = false)
                moved = true
            }
        }
        if (moved) pause(ROOT_SETTLE_MS)
        SplitPane.entries.forEach { pane ->
            normalizeTaskToRoot(
                taskId = hosts.getValue(pane).id,
                rootId = desiredRoots.getValue(pane),
            )
        }
        return existingOwnedSession(pickerComponents)
    }

    /**
     * Adopts the one owned root left by a native edge collapse.
     *
     * Area 1/2 identifies the surviving native pane, but not the previous logical owner. DiLink
     * may move the surviving app across the two native roots and detach both permanent picker
     * bases while it collapses the divider. Match the survivor by exact recorded task identities,
     * reattach only that app's exact picker base when necessary, and require the other native root
     * to be empty. BYD may retain the dismissed tasks as detached hidden roots; the coordinator
     * removes only those exact recorded artifacts after adoption.
     * This is deliberately separate from [existingOwnedSession], whose callers require an intact
     * two-root scene.
     */
    fun collapsedOwnedSession(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitPickerLivePane? {
        val survivor = when (callInt("service call activity_task 30")) {
            AREA_PRIMARY_FULL -> SplitPane.PRIMARY
            AREA_SECONDARY_FULL -> SplitPane.SECONDARY
            else -> return null
        }
        if (expectedPanes.isEmpty() || !SplitPane.entries.toSet().containsAll(expectedPanes.keys)) {
            return null
        }
        if (expectedPanes.values.any { expected ->
                expected.hostTaskId <= 0 ||
                    ((expected.appTaskId == null) != (expected.packageName == null)) ||
                    (expected.appTaskId != null &&
                        (expected.appTaskId <= 0 || expected.packageName.isNullOrBlank()))
            }
        ) {
            return null
        }

        val roots = nativeRootIds()
        val state = snapshot()
        val collapsedRoot = state.root(roots.getValue(survivor.other()))
        if (collapsedRoot?.tasks.orEmpty().any { task -> !task.isEmptyRootMarker() }) return null

        val nativeRootIds = roots.values.toSet()
        val survivorRootId = roots.getValue(survivor)
        val root = state.root(survivorRootId) ?: return null
        val previousOwners = expectedPanes.filter { (_, expected) ->
            val hostMatches = root.tasks.any { task ->
                task.id == expected.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            }
            val appMatches = expected.appTaskId?.let { expectedAppTaskId ->
                root.tasks.any { task ->
                    task.id == expectedAppTaskId &&
                        task.effectivePackageName() == expected.packageName &&
                        !task.isDenzaPickerBase()
                }
            } ?: false
            hostMatches || appMatches
        }
        if (previousOwners.size != 1) return null
        val (previousOwner, expected) = previousOwners.entries.single()
        val liveAppPresent = expected.appTaskId?.let { expectedAppTaskId ->
            root.tasks.any { task ->
                task.id == expectedAppTaskId &&
                    task.effectivePackageName() == expected.packageName &&
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
        if (closedTasksInNativeRoots) return null

        val expectedIds = setOfNotNull(
            survivorExpected.hostTaskId,
            survivorExpected.appTaskId,
        )
        if (root.tasks.any { task -> !task.isEmptyRootMarker() && task.id !in expectedIds }) {
            return null
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
                ?: return null
            val originalRootId = detachedPicker.rootId
            try {
                moveTask(detachedPicker.id, survivorRootId, toTop = false)
                reattachedFromRootId = originalRootId
                pause(ROOT_SETTLE_MS)
                check(callInt("service call activity_task 30") == survivor.fullArea) {
                    "Split изменился при возврате picker ${detachedPicker.id}"
                }
                normalizeTaskToRoot(detachedPicker.id, survivorRootId)
            } catch (error: Throwable) {
                runCatching { moveTask(detachedPicker.id, originalRootId, toTop = false) }
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
        if (settled != null) return settled
        val rollbackRootId = reattachedFromRootId ?: return null
        val error = IllegalStateException(
            "Split изменился после возврата picker ${survivorExpected.hostTaskId}",
        )
        runCatching { moveTask(survivorExpected.hostTaskId, rollbackRootId, toTop = false) }
            .onFailure(error::addSuppressed)
        throw error
    }

    private fun settledCollapsedPane(
        survivor: SplitPane,
        rootId: Int,
        expected: SplitPickerObservedPane,
        pickerComponents: Set<String>,
    ): SplitPickerLivePane? {
        val settledRoot = snapshot().root(rootId) ?: return null
        val picker = settledRoot.tasks.singleOrNull { task ->
            task.id == expected.hostTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        } ?: return null
        if (picker.bounds != settledRoot.bounds) return null
        val app = expected.appTaskId?.let { appTaskId ->
            settledRoot.tasks.singleOrNull { task ->
                task.id == appTaskId &&
                    task.effectivePackageName() == expected.packageName &&
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
            appPackageName = app?.effectivePackageName(),
        )
    }

    /**
     * Brings an exact owned pair back above Home or a fullscreen window without rebuilding a pane.
     *
     * Home is a covered scene like any other (invariant 5, 1.9.1): the pair is alive in the two
     * panel roots and one focus command is what the contract asks for at 1.9.4, not a rebuild that
     * restarts the music the user left playing.
     */
    fun revealOwnedSession(
        existing: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Map<SplitPane, SplitPickerLivePane> {
        val area = callInt("service call activity_task 30")
        if (area == AREA_BALANCED_SPLIT) return existing
        check(area == AREA_FULL_IVI || area == AREA_HOME) {
            "Split-сессия больше не скрыта: area=$area"
        }
        // Contract 5, to 1.12: raising a covered scene of ours is the explicit resumption of this
        // session, and Home suspends exactly the gate this session opened (1.9.1) - without this
        // the return from Home would raise a scene the firmware is no longer holding open. A scene
        // that is already on screen returns above and asks the firmware for nothing at all.
        ensureGateOpen()

        val focusTaskId = SplitPane.entries.asSequence()
            .mapNotNull { pane -> existing[pane]?.appTaskId }
            .firstOrNull()
            ?: SplitPane.entries.asSequence()
                .mapNotNull { pane -> existing[pane]?.hostTaskId }
                .firstOrNull()
            ?: error("В split-сессии нет задачи для возврата")
        run("am task focus $focusTaskId")
        check(awaitArea(EXIT_SETTLE_MS) { it == AREA_BALANCED_SPLIT }) {
            "Прошивка не вернула существующий split на экран"
        }
        val revealed = existingOwnedSession(pickerComponents)
            ?: error("Существующая split-сессия изменилась при возврате")
        check(revealed == existing) { "Состав split-сессии изменился при возврате" }
        return revealed
    }

    /**
     * The whole scene in one recipe: the two permanent picker bases and the apps above them.
     *
     * It used to be two - `openPickers`, then one `restoreApp` per pane, which fell through to the
     * same `selectApp` a user tap runs - and the two of them repeated everything: the gate, the
     * roots, the snapshot, and a postcondition that measured one pane at a time and only up to the
     * moment the *other* pane had not been launched yet. That is the "picker over an app" defect of
     * acceptance v17, and it is why restoring a saved pair took eleven seconds where a fresh open
     * took three.
     *
     * Every command it sends was already sent before; what is new is the order. One preamble, one
     * pass over the roots, both launches back to back, and one postcondition measured over the
     * whole scene twice (contract 7.7 then adds the operation's own read-back on top).
     *
     * The pickers stay the mechanism and the floor: this firmware ignores the pane categories for
     * third-party apps and refuses to hold a split whose root is empty (1.4.1, findings), so the
     * phases go, not the pickers.
     */
    fun buildScene(
        pickerComponents: Map<SplitPane, String>,
        targets: Map<SplitPane, SplitLaunchTarget>,
        /**
         * The exact identities this process recorded for the apps of a still-living scene
         * (правка B1, ground-v18 A). A survivor the firmware threw out of the panel roots is
         * taken back by reparenting that exact task instead of launching; anything the map
         * cannot prove exactly falls through to the honest launch below (invariant 4).
         */
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
        /**
         * The ids already living on the main display before this operation's first mutation
         * (правка W6). It is the operation's own journal knowledge: a failed pane's candidate may
         * be removed only when this build provably created it; a pre-existing task is returned to
         * the background instead. `null` means the past could not be read, and then nothing is
         * ever removed as "created".
         */
        preexistingTaskIds: Set<Int>? = null,
        /**
         * Правка W10: фазовые метки сборки для диагностического лога. Следующая красная ветка
         * обязана раскладываться по логу без гаданий: какому шагу достались секунды, говорит
         * сама операция ("roots-started" ... "placement-confirmed"), а не реконструкция.
         */
        onPhase: (String) -> Unit = {},
        /**
         * Every task the recipe took charge of, reported the moment it has one rather than at the
         * end: a build the fence stops halfway still owes an undo for what it already did
         * (invariant 10). The caller decides which of them it created and which it only moved.
         */
        onTask: (SplitBuiltTask) -> Unit = {},
    ): SplitSceneBuild {
        check(pickerComponents.keys == SplitPane.entries.toSet()) {
            "Нужны оба split-пикера"
        }
        // Phase 1 - the preamble, once for the whole scene.
        ensureGateOpen()
        ensureSupported(SPLIT_HOST_PACKAGE)
        val failed = mutableSetOf<SplitPane>()
        val wanted = mutableMapOf<SplitPane, SplitLaunchTarget>()
        targets.forEach { (pane, target) ->
            // A package the firmware will not accept into split is a restore failure of that pane
            // and of nothing else: the neighbour and the pickers are unaffected (1.3.2, U5).
            runCatching { ensureSupported(target.packageName) }
                .onSuccess { wanted[pane] = target }
                .onFailure { failed += pane }
        }
        val rootIds = nativeRootIds()
        // Do not enter through activity_task tx115 here. BYD remembers split-capable packages
        // globally and may restore an unrelated OEM companion (notably ADAS) before our launcher
        // gets control. Explicit PRIMARY/SECONDARY categories create and target the same native
        // roots without consulting that remembered OEM pair.
        val before = snapshot()

        // Phase 2 - the roots. A picker already in its pane is adopted, never rebuilt.
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
        var launchedPicker = false
        SplitPane.entries.filterNot(pickerTasks::containsKey).forEach { pane ->
            // A survivor goes back to its own pane: the panel bounds the firmware preserved on it
            // name the side it lived on (правка B1, ground-v18). Any remaining survivor still
            // beats a launch, and only an empty pool launches a fresh picker.
            val paneBounds = before.root(rootIds.getValue(pane))?.bounds
            val survivor = reusableTasks.firstOrNull { task -> task.bounds == paneBounds }
                ?: reusableTasks.firstOrNull()
            val picker = survivor?.also(reusableTasks::remove)
                ?: launchPickerTask(
                    pane = pane,
                    pickerComponent = pickerComponents.getValue(pane),
                    excludedTaskIds = assignedIds,
                ).also { launchedPicker = true }
            assignedIds += picker.id
            pickerTasks[pane] = picker
        }
        pickerTasks.forEach { (pane, picker) ->
            onTask(
                SplitBuiltTask(
                    taskId = picker.id,
                    component = pickerComponents.getValue(pane),
                    fromRootId = picker.rootId,
                    toRootId = rootIds.getValue(pane),
                ),
            )
        }

        onPhase("roots-started")
        // Categories are authoritative once the native scene exists. On a truly empty scene
        // this firmware first creates ordinary fullscreen tasks, so explicitly reparent those
        // exact tasks into the already-known OEM roots and reveal the real divider once.
        var reparented = false
        SplitPane.entries.forEach { pane ->
            val picker = pickerTasks.getValue(pane)
            val rootId = rootIds.getValue(pane)
            if (picker.rootId != rootId) {
                moveTask(picker.id, rootId)
                reparented = true
            }
        }
        // A settle is for something that happened: two pickers already in their roots settle
        // nothing (1.13, "не заставлять ждать там, где ждать нечего"). And what did happen is
        // waited out by condition, not by a blind pause: the read that confirms the reparent is,
        // through the shared topology cache, the very read the apps phase decides from (правка A3).
        if (reparented) {
            awaitSnapshotMatching { state ->
                SplitPane.entries.all { pane ->
                    state.root(rootIds.getValue(pane))?.tasks
                        ?.any { task -> task.id == pickerTasks.getValue(pane).id } == true
                }
            }
        }
        // The synthetic drag backs up exactly one situation: a picker this build launched on a
        // truly empty scene came up as an ordinary fullscreen task. A scene assembled from
        // survivors has no divider on screen to drag - at Home there is none - and is raised by
        // the reveal's own focus command in the apps phase instead (правка B1).
        if (launchedPicker && callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            dragDividerToBalanced()
            check(awaitArea(NATIVE_PICKER_SETTLE_MS) { it == AREA_BALANCED_SPLIT }) {
                "Прошивка не раскрыла native split"
            }
        }
        onPhase("roots-placed")

        // Phase 3 - the apps. One read decides which pane still needs a launch at all.
        val hostTaskIds = pickerTasks.mapValues { (_, picker) -> picker.id }
        val settled = snapshot()
        val appTaskIds = mutableMapOf<SplitPane, Int>()
        val launching = mutableMapOf<SplitPane, SplitLaunchTarget>()
        val adoptedAppIds = mutableListOf<Int>()
        wanted.forEach { (pane, target) ->
            val root = settled.root(rootIds.getValue(pane))
            val top = root?.resolvedTopTask()
            val covered = root?.resolveExpectedCoveredApp(expectedApps[pane])?.takeIf { task ->
                task.id != hostTaskIds.getValue(pane) &&
                    task.effectivePackageName() == target.packageName &&
                    !task.isDenzaPickerBase() &&
                    !task.isNativeSplitBootstrap()
            }
            val stray = if (covered == null) {
                strayExpectedApp(settled, rootIds, pane, expectedApps[pane], target)
            } else {
                null
            }
            when {
                root != null &&
                    top != null &&
                    top.id != hostTaskIds.getValue(pane) &&
                    top.effectivePackageName() == target.packageName &&
                    top.bounds == root.bounds -> {
                    // U2, 1.3.2: this pane is already showing exactly that app. Nothing is
                    // relaunched over a living one - the postcondition still has to prove it.
                    appTaskIds[pane] = top.id
                    onTask(
                        SplitBuiltTask(
                            taskId = top.id,
                            component = target.componentName,
                            fromRootId = top.rootId,
                            toRootId = top.rootId,
                        ),
                    )
                }
                // Правка B1, U2: the pane still holds the exact recorded task, merely covered or
                // under its picker. It is adopted and later promoted; nothing is launched.
                covered != null -> {
                    appTaskIds[pane] = covered.id
                    adoptedAppIds += covered.id
                    onTask(
                        SplitBuiltTask(
                            taskId = covered.id,
                            component = target.componentName,
                            fromRootId = covered.rootId,
                            toRootId = covered.rootId,
                        ),
                    )
                }
                // Правка B1: the firmware threw the exact recorded task out of the panel roots
                // (ground-v18 A) but kept it alive with its panel bounds. Reparenting it back is
                // the whole restore of that pane - the very moves that are already live-proven.
                stray != null -> {
                    appTaskIds[pane] = stray.id
                    adoptedAppIds += stray.id
                    onTask(
                        SplitBuiltTask(
                            taskId = stray.id,
                            component = target.componentName,
                            fromRootId = stray.rootId,
                            toRootId = rootIds.getValue(pane),
                        ),
                    )
                    moveTask(stray.id, rootIds.getValue(pane))
                }
                else -> launching[pane] = target
            }
        }
        // A pane is its picker plus at most one app, so whatever else a previous session or a
        // native ending left in one has to go before this scene can be proven. It is the rule the
        // two blind `prunePane` calls used to run, now decided from the read above and sent as a
        // single call: a clean pane costs nothing at all, and the copy of the package this pane is
        // about to show is kept so that restoring it reuses the task instead of restarting it (U2).
        val stale = SplitPane.entries.flatMap { pane ->
            val target = wanted[pane]?.packageName
            val keep = setOfNotNull(
                hostTaskIds.getValue(pane),
                appTaskIds[pane],
                settled.root(rootIds.getValue(pane))?.tasks
                    ?.filter { task -> task.effectivePackageName() == target }
                    ?.maxByOrNull(SplitTask::id)
                    ?.id,
            )
            settled.root(rootIds.getValue(pane))?.tasks.orEmpty()
                .filterNot { task -> task.id in keep || task.isEmptyRootMarker() }
        }
        // And our own retired host Activity, wherever an older version of the product left one.
        // It is the only task outside the panes whose ownership is provable - by our exact
        // component, never by the package of the app inside it (invariant 3, 1.9.2).
        val strayHosts = settled.roots.asSequence()
            .filter { root -> root.displayId == MAIN_DISPLAY_ID }
            .flatMap { root -> root.tasks.asSequence() }
            .filter { task -> task.isDenzaAppHost() }
        removeTasksSafely((stale + strayHosts).distinctBy(SplitTask::id))
        if (launching.isNotEmpty()) {
            launchApps(
                rootIds = rootIds,
                launching = launching,
                // A pane is in `appTaskIds` only because its top already *is* that target, so the
                // package each pane will hold is simply the one its slot named.
                paneApps = SplitPane.entries.associateWith { pane -> wanted[pane]?.packageName },
                appTaskIds = appTaskIds,
                failed = failed,
                onTask = onTask,
            )
        }
        if (adoptedAppIds.isNotEmpty()) {
            // The reveal's own command, per adopted pane: it orders the exact task above its
            // picker and raises the covered scene on the way (правка B1, к 1.9.4). Membership is
            // then confirmed on the read the normalize pass shares.
            adoptedAppIds.forEach { taskId -> run("am task focus $taskId") }
            awaitSnapshotMatching { state ->
                appTaskIds.all { (pane, taskId) ->
                    state.root(rootIds.getValue(pane))?.tasks?.any { it.id == taskId } == true
                }
            }
        }
        // A build that launched no picker has nothing that asks the firmware for the split: the
        // categories raise it only on our own picker starts, and the synthetic drag has no divider
        // to grab under Home. One focus on an exact owned task - the app if there is one, else a
        // base - raises the assembled scene the way the reveal does (правка B1, к 1.9.4); a scene
        // already balanced costs one area read and nothing else.
        if (!launchedPicker && callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            val focusTaskId = appTaskIds.values.firstOrNull()
                ?: hostTaskIds.getValue(SplitPane.PRIMARY)
            run("am task focus $focusTaskId")
        }
        onPhase("apps-launched")

        // Both bases and both apps take the size of their pane from one read, the divergent ones
        // are resized back to back, and one settle and one more read close the whole batch.
        normalizeSceneToRoots(hostTaskIds, appTaskIds, rootIds, failed)
        // 1.3.2: a pane whose app did not come back keeps its picker - and only what this build
        // itself created may die with the attempt (правка W6).
        failed.forEach { pane ->
            val packageName = targets[pane]?.packageName ?: return@forEach
            runCatching {
                discardFailedRestoration(
                    pane = pane,
                    packageName = packageName,
                    pickerTaskId = hostTaskIds.getValue(pane),
                    preexistingTaskIds = preexistingTaskIds,
                )
            }
        }
        onPhase("scene-normalized")
        val panes = awaitScenePlacement(
            pickerComponents = pickerComponents,
            rootIds = rootIds,
            hostTaskIds = hostTaskIds,
            appTaskIds = appTaskIds,
        )
        onPhase("placement-confirmed")
        return SplitSceneBuild(panes = panes, failed = failed)
    }

    /**
     * Both launches, back to back, and one settle for the pair.
     *
     * The only case that cannot be batched is the same package in both panes: after the fact both
     * launches answer to the same predicate, so there is no way to tell which task belongs to which
     * pane. That one is launched a pane at a time.
     */
    private fun launchApps(
        rootIds: Map<SplitPane, Int>,
        launching: Map<SplitPane, SplitLaunchTarget>,
        paneApps: Map<SplitPane, String?>,
        appTaskIds: MutableMap<SplitPane, Int>,
        failed: MutableSet<SplitPane>,
        onTask: (SplitBuiltTask) -> Unit,
    ) {
        val separable = launching.values.distinctBy(SplitLaunchTarget::packageName).size ==
            launching.size
        val groups = if (separable) {
            listOf(launching)
        } else {
            launching.entries.map { (pane, target) -> mapOf(pane to target) }
        }
        val taken = mutableSetOf<Int>()
        groups.forEach { group ->
            val started = group.filter { (pane, target) ->
                runCatching { startTargetInPane(pane, target, secondInstanceOf(pane, paneApps)) }
                    .onFailure { failed += pane }
                    .isSuccess
            }
            if (started.isEmpty()) return@forEach
            // One poll answers the whole group from the same reads, and the blind settle that
            // used to precede the waiting is gone: a restore's task already exists, so the very
            // first read finds it (правка A2/A3). A pane keeps its first match - exactly what the
            // per-pane wait did - and a pane the short budget leaves unmatched fails alone,
            // degrading to its picker with the visible notice of 1.3.2 (правка W5): the red
            // branch of v20 P1.2 burned two twelve-read budgets (~5 с каждый) against a task the
            // firmware refused to hold.
            val found = linkedMapOf<SplitPane, SplitTask>()
            awaitSnapshotMatching(attempts = RESTORE_DISCOVERY_ATTEMPTS) { state ->
                val tasks = state.roots.asSequence()
                    .filter { it.displayId == MAIN_DISPLAY_ID }
                    .flatMap { it.tasks.asSequence() }
                    .toList()
                started.forEach { (pane, target) ->
                    if (found.containsKey(pane)) return@forEach
                    tasks.filter { task ->
                        task.id !in taken &&
                            task.packageName == target.packageName &&
                            !task.isOwnSplitComponent()
                    }
                        .maxByOrNull(SplitTask::id)
                        ?.let { task ->
                            taken += task.id
                            found[pane] = task
                        }
                }
                found.size == started.size
            }
            started.forEach { (pane, target) ->
                val task = found[pane]
                if (task == null) {
                    failed += pane
                    return@forEach
                }
                onTask(
                    SplitBuiltTask(
                        taskId = task.id,
                        component = target.componentName,
                        fromRootId = task.rootId,
                        toRootId = rootIds.getValue(pane),
                    ),
                )
                promoteTask(task, rootIds.getValue(pane))
                appTaskIds[pane] = task.id
            }
        }
        // The promotes are waited out by condition as well: every promoted task listed in its
        // pane root, on a read the following normalize pass then shares (правка A3). The budget
        // is the restore path's short one (правка W5): a reparent lands on the very next read,
        // and a task the firmware keeps out of the pane is answered by the pane's honest
        // degradation, not by twelve reads of hope.
        if (appTaskIds.isNotEmpty()) {
            awaitSnapshotMatching(attempts = RESTORE_DISCOVERY_ATTEMPTS) { state ->
                appTaskIds.all { (pane, taskId) ->
                    state.root(rootIds.getValue(pane))?.tasks?.any { it.id == taskId } == true
                }
            }
        }
    }

    /**
     * Whether this launch has to become a task of its own (1.5.2).
     *
     * Everything else reuses the task the package already has, which is exactly what makes a
     * restore keep the app that is already playing (U2) instead of leaving an orphan behind it.
     * `PRIMARY` is launched first, so it is `SECONDARY` that needs the second instance.
     */
    private fun secondInstanceOf(pane: SplitPane, paneApps: Map<SplitPane, String?>): Boolean =
        pane == SplitPane.SECONDARY &&
            paneApps[SplitPane.PRIMARY] != null &&
            paneApps[SplitPane.PRIMARY] == paneApps[SplitPane.SECONDARY]

    fun selectApp(
        pickerTaskId: Int,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
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
        // Only the other native pane is a live duplicate. After OEM collapse the old app task
        // can linger briefly in the hidden full-IVI root; a subsequent launch legitimately
        // replaces that stale task and must not be rejected or "preserved" as another window.
        val duplicatePeerTasks = before.root(otherRootId)?.tasks.orEmpty()
            .filter { task -> task.effectivePackageName() == target.packageName }
        check(duplicatePeerTasks.isEmpty() || target.launchMode < LAUNCH_MODE_SINGLE_TASK) {
            "Это приложение не поддерживает два окна"
        }
        ensureSupported(target.packageName)

        // A picker tap is authoritative proof that this pane is being selected. Clear every
        // task above its exact permanent base before requiring the picker to be the root top.
        // This also recovers a transparent SplitAppHostActivity left by an interrupted launch;
        // otherwise that input window can remain focused over the visible picker forever.
        removeTasksSafely(
            before.root(targetRootId)?.tasks.orEmpty().filterNot { it.id == pickerHost.id },
        )

        val clearedState = snapshot()
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
                    task.effectivePackageName() == target.packageName
            }
            .associate { task -> task.id to task.rootId }

        try {
            val launchedTask = launchTargetDirectIntoRoot(
                target = target,
                pane = pane,
                rootId = targetRootId,
                // 1.5.2, and only here: the other pane still holds this package, so this tap asks
                // for a genuinely independent second window rather than for the task it already has.
                secondInstance = duplicatePeerTasks.isNotEmpty(),
                excludedTaskIds = preservedTargetTaskRoots.keys,
            )
            normalizeTaskToRoot(launchedTask.id, targetRootId)
            pause(ROOT_SETTLE_MS)

            return awaitSelectedAppPlacement(
                pane = pane,
                rootId = targetRootId,
                pickerHost = pickerHost,
                launchedTask = launchedTask,
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

    /**
     * BYD publishes task placement before its split-area controller has necessarily committed the
     * same transition. Treat the launch as successful only after the complete scene agrees twice;
     * otherwise a single stale area/top sample would make cleanup delete an already visible app.
     */
    private fun awaitSelectedAppPlacement(
        pane: SplitPane,
        rootId: Int,
        pickerHost: SplitTask,
        launchedTask: SplitTask,
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
                    launchedTask = launchedTask,
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
                pause(APP_PLACEMENT_CONFIRM_INTERVAL_MS)
            }
        }
        throw lastError ?: IllegalStateException(
            "Запуск ${target.packageName} не достиг устойчивого состояния",
        )
    }

    private fun selectedAppPlacement(
        pane: SplitPane,
        rootId: Int,
        pickerHost: SplitTask,
        launchedTask: SplitTask,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
        expectedArea: Int,
        preservedTargetTaskRoots: Map<Int, Int>,
    ): SplitPickerPlacement {
        val root = snapshot().root(rootId)
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
        requirePreservedTargetTasks(target.packageName, preservedTargetTaskRoots)
        return SplitPickerPlacement(
            pane = pane,
            hostTaskId = pickerHost.id,
            appTaskId = top.id,
            packageName = target.packageName,
        )
    }

    private fun launchTargetDirectIntoRoot(
        target: SplitLaunchTarget,
        pane: SplitPane,
        rootId: Int,
        secondInstance: Boolean,
        excludedTaskIds: Set<Int> = emptySet(),
    ): SplitTask {
        startTargetInPane(pane, target, secondInstance)
        pause(APP_LAUNCH_SETTLE_MS)
        val direct = awaitTaskMatching { task ->
            task.id !in excludedTaskIds &&
                task.packageName == target.packageName &&
                !task.isOwnSplitComponent()
        }
        promoteTask(direct, rootId)
        pause(ROOT_SETTLE_MS)
        return snapshot().root(rootId)?.tasks?.firstOrNull { task ->
            task.id == direct.id && task.packageName == target.packageName
        } ?: error("Прямой запуск не вошёл в выбранное окно")
    }

    /**
     * The one launch command of the product, in the pane's own category.
     *
     * [secondInstance] is the only thing that decides whether `FLAG_ACTIVITY_MULTIPLE_TASK` is set,
     * and it is true for exactly one situation: the same package being opened a second time while
     * the other pane still holds it (1.5.2). Everywhere else the flag is absent, so the firmware
     * hands back the task the package already has - the whole point of a restore, which used to
     * start a fresh copy behind a splash screen and leave the playing one orphaned outside the
     * panes (acceptance v17: music #44 -> #66 -> #81).
     */
    private fun startTargetInPane(
        pane: SplitPane,
        target: SplitLaunchTarget,
        secondInstance: Boolean,
    ) {
        val category = when (pane) {
            SplitPane.PRIMARY -> PRIMARY_PICKER_CATEGORY
            SplitPane.SECONDARY -> SECONDARY_PICKER_CATEGORY
        }
        run(
            "am start -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-c $category " +
                "-n ${shellQuote(target.componentName)} " +
                "-f " + if (secondInstance) SECOND_INSTANCE_FLAGS else APP_LAUNCH_FLAGS,
        )
    }

    private fun cleanupLaunchAttempt(
        packageName: String,
        baselineTaskIds: Set<Int>,
        preservedTargetTaskRoots: Map<Int, Int>,
    ) {
        snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.id !in baselineTaskIds &&
                    (task.effectivePackageName() == packageName ||
                        (task.isDenzaAppHost() && task.topPackageName == packageName)
                    )
            }
            .toList()
            .let(::removeTasksSafely)
        restorePreservedTargetTasks(packageName, preservedTargetTaskRoots)
    }

    private fun restorePreservedTargetTasks(
        packageName: String,
        preservedTaskRoots: Map<Int, Int>,
    ) {
        if (preservedTaskRoots.isEmpty()) return
        val current = mainDisplayTasks().associateBy(SplitTask::id)
        preservedTaskRoots.forEach { (taskId, rootId) ->
            val task = current[taskId]
                ?: error("Не удалось сохранить уже открытое окно $packageName")
            check(task.effectivePackageName() == packageName) {
                "Задача уже открытого окна изменила приложение"
            }
            if (task.rootId != rootId) moveTask(taskId, rootId)
        }
        pause(ROOT_SETTLE_MS)
        requirePreservedTargetTasks(packageName, preservedTaskRoots)
    }

    private fun requirePreservedTargetTasks(
        packageName: String,
        preservedTaskRoots: Map<Int, Int>,
    ) {
        if (preservedTaskRoots.isEmpty()) return
        val current = mainDisplayTasks().associateBy(SplitTask::id)
        preservedTaskRoots.forEach { (taskId, rootId) ->
            val task = current[taskId]
                ?: error("Уже открытое окно $packageName исчезло")
            check(task.rootId == rootId && task.effectivePackageName() == packageName) {
                "Уже открытое окно $packageName сменило split-контейнер"
            }
        }
    }

    /**
     * Every task the main display holds right now.
     *
     * A mutating operation reads it before its first command, because a launch without
     * `MULTIPLE_TASK` hands back the task the package already had - wherever on the screen that
     * was. Journalling one of those as "created" would let an unwind close an application the user
     * was already running (invariant 3, U2).
     */
    fun livingTaskIds(): Set<Int> = mainDisplayTasks().mapTo(mutableSetOf(), SplitTask::id)

    /**
     * The task ids the two panel containers hold, for the cleanup path alone (правка W2, диагноз
     * v21 Д2/К1).
     *
     * `removeIviStack(clearTotally=true)` - Back в широком пикере при «пикер|пикер» (1.6.3) -
     * распускает ОБА панельных контейнера, и tx118 с этого момента честно отвечает ≤0. Это не
     * отказ чтения, а факт «панельных контейнеров нет»: живых задач в панелях ноль, и уборке
     * можно идти к проверке членов. Строгий [nativeRootIds] с его `check(rootId > 0)` остаётся
     * воротами каждого строительного рецепта - сборке контейнеры обязательны.
     *
     * Исходы различаются намеренно:
     *  - оба tx118 ≤ 0 -> пустое множество (доказанная пустота);
     *  - оба > 0 -> задачи обоих контейнеров, как раньше;
     *  - смешанный ответ -> `null`: прошивка посреди перестройки, ничего не доказано;
     *  - транспортная ошибка (обрыв, мусор в parcel) пробрасывается - вызывающий остаётся
     *    fail-closed, как и до правки.
     */
    fun paneTaskIdsOrDisbanded(): Set<Int>? {
        val roots = SplitPane.entries.map { pane ->
            callInt("service call activity_task 118 i32 ${pane.areaId}")
        }
        if (roots.all { rootId -> rootId <= 0 }) return emptySet()
        if (roots.any { rootId -> rootId <= 0 }) return null
        val state = snapshot()
        return roots
            .mapNotNull(state::root)
            .flatMap(SplitRootTask::tasks)
            .mapTo(mutableSetOf(), SplitTask::id)
    }

    private fun mainDisplayTasks(): List<SplitTask> = snapshot().roots.asSequence()
        .filter { it.displayId == MAIN_DISPLAY_ID }
        .flatMap { it.tasks.asSequence() }
        .toList()

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
     * Whether the firmware currently covers the scene: Home (area 0) and a foreign fullscreen
     * window (area 4) hide it without ending it (инвариант 5, 1.9.1, 1.11.5). Read-only.
     */
    fun sceneCovered(): Boolean =
        callInt("service call activity_task 30").let { it == AREA_HOME || it == AREA_FULL_IVI }

    /**
     * Whether every recorded member of the scene is still alive on the main display under its
     * exact recorded identity - a picker by task id and our own component, an app by task id and
     * package (инвариант 5, ред. 2026-08-24).
     *
     * На Home прошивка может опустошить корень сфокусированной панели, отвязав живые задачи в
     * display area с сохранёнными панельными границами. Отвязанный член живой накрытой сцены -
     * не сирота, поэтому эта проверка обязана смотреть весь main display, а не только панельные
     * корни (панельные корни здесь ничего не доказывают). Мёртвый член - нативный конец: Back в
     * широком пикере при «пикер|пикер» убивает его задачу (ground-v18 B2), свайп и «очистить всё»
     * убивают их все. Read-only.
     */
    fun allRecordedMembersAlive(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean {
        if (scene.isEmpty()) return false
        val tasks = mainDisplayTasks()
        return scene.values.all { observed ->
            tasks.any { task ->
                task.id == observed.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } && (observed.appTaskId == null || tasks.any { task ->
                task.id == observed.appTaskId &&
                    task.effectivePackageName() == observed.appPackageName &&
                    !task.isDenzaPickerBase()
            })
        }
    }

    /** Resolves a product-picker window hint only when one native root has one visible picker. */
    fun singleVisiblePickerTaskId(pickerComponents: Set<String>): Int? {
        val roots = nativeRootIds()
        val state = snapshot()
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
            .singleOrNull()
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
        check(nativePickerMutationAllowed()) {
            "Нативный split изменился перед запуском picker"
        }
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
        val tasks = snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter {
                it.id in taskIds &&
                    it.isDenzaPickerBase() &&
                    it.matchesAnyComponent(pickerComponents)
            }
            .toList()
        removeTasksSafely(tasks)
        return tasks.map(SplitTask::id)
    }

    /**
     * Clears a failed restoration candidate off the exact picker pane (1.3.2).
     *
     * Правка W6 (v20 P1.2): удалить можно только задачу, СОЗДАННУЮ этой операцией. Живой
     * пре-существовавший таск кандидата - чужое имущество (инвариант 3, U2): деградация паны
     * его не воскрешает, но и не казнит - он возвращается фоном в полноэкранный root тем же
     * live-proven reparent'ом, которым его втянули (1.3.4 запрещает воскрешение, а не казнь
     * фоновых задач). Прошлое, которого операция не читала (`preexistingTaskIds == null`),
     * трактуется как «не наше»: не доказано создание - не удаляем.
     *
     * @return whether the pane actually had to be cleared.
     */
    private fun discardFailedRestoration(
        pane: SplitPane,
        packageName: String,
        pickerTaskId: Int,
        preexistingTaskIds: Set<Int>?,
    ): Boolean {
        val rootId = nativeRootIds().getValue(pane)
        val candidates = snapshot().root(rootId)?.tasks.orEmpty().filter { task ->
            task.id != pickerTaskId &&
                task.effectivePackageName() == packageName &&
                !task.isDenzaPickerBase()
        }
        val (created, borrowed) = candidates.partition { task ->
            preexistingTaskIds != null && task.id !in preexistingTaskIds
        }
        val removed = removeTasksSafely(created)
        if (borrowed.isEmpty()) return removed
        val fullRootId = fullIviRootTaskId()
        borrowed.forEach { task -> moveTask(task.id, fullRootId, toTop = false) }
        pause(ROOT_SETTLE_MS)
        return true
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
        check(awaitArea(EXIT_SETTLE_MS) { it != AREA_BALANCED_SPLIT }) {
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

        closeOwnedGate()

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
        removeTasksSafely(
            (pickerTasks + hostArtifacts).distinctBy(SplitTask::id).mapNotNull { previous ->
                current.roots.asSequence()
                    .filter { it.displayId == MAIN_DISPLAY_ID }
                    .flatMap { it.tasks.asSequence() }
                    .firstOrNull { it.id == previous.id && it.packageName == previous.packageName }
            },
        )

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
        // No settle prefix: the await below is already a poll, and the blind pause in front of it
        // was the user waiting out a launch the firmware may have finished (1.13, правка A3).
        startPickerInPane(pane, pickerComponent)
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

    /**
     * Polls the whole topology until [matches] agrees, within the discovery budget (правка A3).
     *
     * It replaces the blind settle a mutation used to sleep out: the first read usually already
     * agrees - `am stack move-task` reparents synchronously on this firmware - and then the read
     * doubles, through the shared topology cache, as the next phase's snapshot. A timeout is not
     * an error here: the recipes that use it end in their own postcondition, which is the honest
     * judge of whether the car really settled.
     */
    private fun awaitSnapshotMatching(
        attempts: Int = TASK_DISCOVERY_ATTEMPTS,
        matches: (SplitTaskSnapshot) -> Boolean,
    ): Boolean {
        repeat(attempts) { attempt ->
            if (matches(snapshot())) return true
            if (attempt + 1 < attempts) pause(TASK_DISCOVERY_INTERVAL_MS)
        }
        return false
    }

    /**
     * The postcondition of a whole built scene: one full agreeing read (правка A4).
     *
     * BYD publishes task placement before its split-area controller has necessarily committed the
     * same transition, so the loop refuses and retries for as long as any predicate disagrees -
     * that part is unchanged. What one agreeing read now has to say is everything at once, for
     * both panes together: the firmware's own area is balanced, each root holds its exact picker
     * base at the root's size with at most one task above it, and the exact expected task is the
     * *visible* top at the root's size. The second independent observation this recipe used to
     * take itself is the operation's own read-back (contract 7.7, `OpenOperation.readBack`), which
     * re-reads the settled scene from the car after the shared topology is dropped - the guard
     * that answers the "picker over an application" defect class of acceptance v17.
     */
    private fun awaitScenePlacement(
        pickerComponents: Map<SplitPane, String>,
        rootIds: Map<SplitPane, Int>,
        hostTaskIds: Map<SplitPane, Int>,
        appTaskIds: Map<SplitPane, Int>,
    ): Map<SplitPane, SplitPickerLivePane> {
        var lastError: Throwable? = null
        repeat(APP_PLACEMENT_CONFIRM_ATTEMPTS) { attempt ->
            val sample = runCatching {
                scenePlacement(pickerComponents, rootIds, hostTaskIds, appTaskIds)
            }
            sample.getOrNull()?.let { placement -> return placement }
            sample.exceptionOrNull()?.let { error -> lastError = error }
            if (attempt + 1 < APP_PLACEMENT_CONFIRM_ATTEMPTS) {
                pause(APP_PLACEMENT_CONFIRM_INTERVAL_MS)
            }
        }
        throw lastError ?: IllegalStateException("Сцена не достигла устойчивого состояния")
    }

    /** One sample: one area read and one `am stack list` for both panes together. */
    private fun scenePlacement(
        pickerComponents: Map<SplitPane, String>,
        rootIds: Map<SplitPane, Int>,
        hostTaskIds: Map<SplitPane, Int>,
        appTaskIds: Map<SplitPane, Int>,
    ): Map<SplitPane, SplitPickerLivePane> {
        // The area first: it is the cheapest predicate and the one still moving right after a
        // launch, so a polling attempt fails before it pays for a whole topology parse.
        check(callInt("service call activity_task 30") == AREA_BALANCED_SPLIT) {
            "Нативный split не активировался"
        }
        val state = snapshot()
        return SplitPane.entries.associateWith { pane ->
            val root = state.root(rootIds.getValue(pane))
                ?: error("Split-контейнер ${pane.name} исчез")
            check(root.bounds.hasArea()) { "Split-контейнер ${pane.name} не имеет размера" }
            val hostTaskId = hostTaskIds.getValue(pane)
            val picker = root.tasks.firstOrNull { task ->
                task.id == hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesComponent(pickerComponents.getValue(pane))
            } ?: error("Пикер ${pane.name} исчез")
            check(picker.bounds == root.bounds) {
                "Пикер ${pane.name} не принял размер split-контейнера"
            }
            check(root.tasks.size <= MAX_TASKS_PER_PANE) {
                "В ${pane.name} накопилось больше двух задач"
            }
            val top = root.resolvedTopTask() ?: error("В ${pane.name} нет верхней задачи")
            val appTaskId = appTaskIds[pane]
            if (appTaskId == null) {
                check(
                    top.id == hostTaskId &&
                        picker.matchesTopComponent(pickerComponents.getValue(pane)),
                ) { "Пикер ${pane.name} перекрыт посторонней задачей" }
                SplitPickerLivePane(pane, hostTaskId, null, null)
            } else {
                check(top.id == appTaskId) {
                    "Приложение не стало верхним в ${pane.name}"
                }
                check(top.bounds == root.bounds) {
                    "Приложение не приняло размер ${pane.name}"
                }
                SplitPickerLivePane(pane, hostTaskId, top.id, top.effectivePackageName())
            }
        }
    }

    private fun removeTaskSafely(task: SplitTask) = removeTasksSafely(listOf(task))

    /**
     * Removes exactly these tasks, in this order, with one invocation of the proxy.
     *
     * The removals themselves are the same exact-identity calls they have always been; what changed
     * is that a recipe clearing several tasks no longer starts `app_process` several times. Loading
     * the proxy dominates the cost of a removal by an order of magnitude, so a batch of three used
     * to be three whole class loads and three settle pauses for work the firmware does at once.
     */
    /** @return whether any of them was actually removed. */
    private fun removeTasksSafely(tasks: List<SplitTask>): Boolean {
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
        val classpath = proxyClasspath.entry(::shell)
        val output = shell(
            "CLASSPATH=${shellQuote(classpath)} app_process /system/bin " +
                "--nice-name=denza_split_cmd $SPLIT_PROXY_CLASS remove-task $arguments",
        ).also(::validateOutput)
        val removed = parseRemovals(output)
        val refused = tasks.filterNot { task -> removed[task.id] == true }
        if (refused.isNotEmpty()) {
            // A task the proxy would not take is only a failure if it is still there: the firmware
            // may have finished the very dismissal that made us ask.
            val living = snapshot().roots.asSequence()
                .flatMap { root -> root.tasks.asSequence() }
                .mapTo(mutableSetOf(), SplitTask::id)
            refused.firstOrNull { task -> task.id in living }?.let { task ->
                error("Не удалось безопасно удалить задачу ${task.id}")
            }
        }
        if (refused.size == tasks.size) return false
        pause(ROOT_SETTLE_MS)
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

    private fun moveTask(taskId: Int, rootId: Int, toTop: Boolean = true) {
        check(taskId > 0 && rootId > 0)
        run("am stack move-task $taskId $rootId $toTop")
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

    /**
     * The whole-scene edition of [normalizeTaskToRoot], for the one recipe that sizes four tasks
     * at once (правка A1). Every check is the single-task recipe's own - the same root lookup, the
     * same bounds predicate, the same meaning of a failure - but the snapshot before, the settle
     * and the snapshot after are paid once for the scene instead of once per task, which on the
     * car was up to eight `am stack list` and four settles describing the same instant.
     *
     * A missing or unresized host is still an error of the whole build; an app that is missing or
     * refuses its pane's size degrades only that pane, exactly as before (1.3.2).
     *
     * @return whether anything actually had to be resized.
     */
    private fun normalizeSceneToRoots(
        hostTaskIds: Map<SplitPane, Int>,
        appTaskIds: MutableMap<SplitPane, Int>,
        rootIds: Map<SplitPane, Int>,
        failed: MutableSet<SplitPane>,
    ): Boolean {
        class Resize(val pane: SplitPane, val taskId: Int, val bounds: SplitBounds, val host: Boolean)

        val divergent = mutableListOf<Resize>()
        val before = snapshot()
        SplitPane.entries.forEach { pane ->
            val rootId = rootIds.getValue(pane)
            val root = before.root(rootId) ?: error("Split-контейнер $rootId исчез")
            val hostTaskId = hostTaskIds.getValue(pane)
            val host = root.tasks.firstOrNull { it.id == hostTaskId }
                ?: error("Задача приложения $hostTaskId не вошла в split-контейнер")
            if (host.bounds != root.bounds) {
                check(root.bounds.hasArea()) { "Split-контейнер $rootId не имеет размера" }
                divergent += Resize(pane, hostTaskId, root.bounds, host = true)
            }
            val appTaskId = appTaskIds[pane] ?: return@forEach
            val app = root.tasks.firstOrNull { it.id == appTaskId }
            when {
                app == null -> {
                    appTaskIds -= pane
                    failed += pane
                }
                app.bounds != root.bounds -> {
                    check(root.bounds.hasArea()) { "Split-контейнер $rootId не имеет размера" }
                    divergent += Resize(pane, appTaskId, root.bounds, host = false)
                }
            }
        }
        if (divergent.isEmpty()) return false
        divergent.forEach { resize ->
            run(
                "am task resize ${resize.taskId} ${resize.bounds.left} ${resize.bounds.top} " +
                    "${resize.bounds.right} ${resize.bounds.bottom}",
            )
        }
        pause(ROOT_SETTLE_MS)
        val after = snapshot()
        divergent.forEach { resize ->
            val rootId = rootIds.getValue(resize.pane)
            val root = after.root(rootId)
            val task = root?.tasks?.firstOrNull { it.id == resize.taskId }
            if (task != null && task.bounds == root.bounds) return@forEach
            if (resize.host) {
                error(
                    when {
                        root == null -> "Split-контейнер $rootId исчез после изменения размера"
                        task == null ->
                            "Задача приложения ${resize.taskId} исчезла после изменения размера"
                        else ->
                            "Задача приложения ${resize.taskId} не приняла размер split-контейнера"
                    },
                )
            }
            appTaskIds -= resize.pane
            failed += resize.pane
        }
        return true
    }

    /** @return whether the task actually had to be resized. */
    private fun normalizeTaskToRoot(taskId: Int, rootId: Int): Boolean {
        val beforeRoot = snapshot().root(rootId)
            ?: error("Split-контейнер $rootId исчез")
        val beforeTask = beforeRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Задача приложения $taskId не вошла в split-контейнер")
        if (beforeTask.bounds == beforeRoot.bounds) return false
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
        return true
    }

    /**
     * Waits for the firmware's own split area to reach a state, and not one slice longer.
     *
     * The recipes used to sleep out a whole settle before looking even once, which on a transition
     * the firmware had already finished was the user waiting for nothing at all (1.13). The budget
     * and the mutation that precedes it are unchanged; what is gone is the sleeping through it.
     */
    private fun awaitArea(budgetMs: Long, matches: (Int) -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (matches(callInt("service call activity_task 30"))) return true
            if (waited >= budgetMs) return false
            val slice = minOf(AREA_POLL_INTERVAL_MS, budgetMs - waited)
            pause(slice)
            waited += slice
        }
    }

    private fun nativeRootIds(): Map<SplitPane, Int> = topology.roots {
        SplitPane.entries.associateWith { pane ->
            callInt("service call activity_task 118 i32 ${pane.areaId}").also { rootId ->
                check(rootId > 0) { "Прошивка не вернула split-контейнер ${pane.areaId}" }
            }
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

    /**
     * The gate is firmware-global, so it is closed by exactly one rule: we opened it, therefore we
     * close it (contract, to 1.12; invariant 1). A gate that was already open when our session
     * started, or that belongs to a stock split the user built themselves, is left alone.
     *
     * @return whether this call is what closed it.
     */
    fun closeOwnedGate(): Boolean {
        val store = gateLeaseStore ?: return false
        if (!store.isOwned()) return false
        callVoid("service call activity_task 126 i32 0")
        check(store.setOwned(false)) { "Не удалось освободить split-gate" }
        return true
    }

    private fun ensureSupported(packageName: String) {
        val quoted = shellQuote(packageName)
        if (callBoolean("service call activity_task 112 s16 $quoted")) return
        callVoid("service call activity_task 125 s16 $quoted")
        check(callBoolean("service call activity_task 112 s16 $quoted")) {
            "Прошивка не добавила $packageName в split"
        }
    }

    private fun snapshot(): SplitTaskSnapshot = topology.state {
        SplitTaskSnapshot.parse(shell("am stack list").also(::validateOutput))
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

    /**
     * The exact recorded app of a pane, alive on the main display outside every panel root
     * (правка B1). The proof mirrors [resolveExpectedCoveredApp]: the persisted task id, the
     * package identity and the preserved panel bounds equal to the destination root's - anything
     * less exact returns nothing and the pane is launched honestly (invariant 4).
     */
    private fun strayExpectedApp(
        state: SplitTaskSnapshot,
        rootIds: Map<SplitPane, Int>,
        pane: SplitPane,
        expected: SplitPickerExpectedApp?,
        target: SplitLaunchTarget,
    ): SplitTask? {
        expected ?: return null
        if (expected.packageName != target.packageName) return null
        val root = state.root(rootIds.getValue(pane)) ?: return null
        val nativeRootIds = rootIds.values.toSet()
        val task = state.roots.asSequence()
            .filter { candidate -> candidate.displayId == MAIN_DISPLAY_ID }
            .flatMap { candidate -> candidate.tasks.asSequence() }
            .singleOrNull { candidate -> candidate.id == expected.taskId }
            ?: return null
        return task.takeIf {
            task.rootId !in nativeRootIds &&
                task.effectivePackageName() == expected.packageName &&
                !task.isDenzaPickerBase() &&
                !task.isNativeSplitBootstrap() &&
                task.bounds == root.bounds
        }
    }

    /**
     * Инвариант 3: package сам по себе identity не доказывает. Собственные компоненты продукта -
     * постоянные пикеры, retired host, штатный bootstrap - не могут быть «найденным приложением»,
     * даже когда запускается пакет самого продукта (U3). Живая мина v20 P1.2: при self-restore
     * matcher по одному пакету предпочёл бы свежесозданный пикер пре-существующему таску хаба.
     */
    private fun SplitTask.isOwnSplitComponent(): Boolean =
        isDenzaPickerBase() || isDenzaAppHost() || isNativeSplitBootstrap()

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
        /** `NEW_TASK | RESET_TASK_IF_NEEDED`: the package's own task, whichever one that is. */
        const val APP_LAUNCH_FLAGS = "0x10200000"

        /** The same plus `MULTIPLE_TASK`: a second, independent copy and nothing else (1.5.2). */
        const val SECOND_INSTANCE_FLAGS = "0x18200000"
        const val PICKER_LAUNCH_FLAGS = "0x18010000"
        const val PRIMARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_PRIMARY"
        const val SECONDARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_SECOND"
        const val MAX_TASKS_PER_PANE = 2
        const val LAUNCH_MODE_SINGLE_TASK = 2
        const val TASK_DISCOVERY_ATTEMPTS = 12
        const val TASK_DISCOVERY_INTERVAL_MS = 100L

        /**
         * Правка W5 (v20 P1.2): ожидания restore-пути отвечают с первого чтения - запущенная
         * задача попадает в `am stack list` сразу, тёплый запуск пикера стоит ~0.9 с вместе с
         * собственным round trip `am start`, а каждое чтение на этой машине само по себе
         * 250-300 мс. Два прохода покрывают честный случай; не-матч - немедленная деградация
         * паны в пикер с нотисом 1.3.2 (~1 c ветки вместо двух сгоревших 12-кратных бюджетов
         * по ~5 с у красной ветки restore).
         */
        const val RESTORE_DISCOVERY_ATTEMPTS = 2
        const val NATIVE_PICKER_COMMIT_ATTEMPTS = 150
        const val NATIVE_PICKER_COMMIT_INTERVAL_MS = 100L
        // Two 100 ms samples were live-proven insufficient: edge collapse can expose area 3 for
        // substantially longer before settling to area 1/2. One second keeps this path read-only
        // through that firmware transition without drawing a window over the user's gesture.
        const val NATIVE_PICKER_RELEASED_SAMPLES = 10
        const val NATIVE_PICKER_CANCELLED_SAMPLES = 5
        const val HOME_CONFIRM_ATTEMPTS = 6
        const val HOME_CONFIRM_INTERVAL_MS = 100L
        const val DIVIDER_RECONCILE_SETTLE_MS = 1_500L
        const val PICKER_SETTLE_MS = 150L
        const val NATIVE_PICKER_SETTLE_MS = 450L
        const val APP_LAUNCH_SETTLE_MS = 250L
        const val APP_PLACEMENT_CONFIRM_ATTEMPTS = 20
        const val APP_PLACEMENT_CONFIRM_INTERVAL_MS = 100L

        /** Only the single-pane selection keeps two samples; a built scene ends in the
         *  operation's own whole-scene read-back instead (правка A4). */
        const val APP_PLACEMENT_STABLE_SAMPLES = 2
        const val ROOT_SETTLE_MS = 120L
        const val EXIT_SETTLE_MS = 650L
        const val AREA_POLL_INTERVAL_MS = 100L
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

/**
 * What one [SplitPickerShellSession.buildScene] settled.
 *
 * [failed] names the panes whose remembered app did not come back; each of them is on its own
 * picker, and the operation turns them into the one notice the user reads (1.3.2, U5).
 */
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

/**
 * A task a build took charge of, and where it was when the build found it.
 *
 * [fromRootId] is what makes an unwind exact for a task the build did not create: a restore reuses
 * the task its package already had and reparents it into a pane, and putting that one back where it
 * came from is the honest inverse (contract 7.6, invariant 9).
 */
internal data class SplitBuiltTask(
    val taskId: Int,
    val component: String,
    val fromRootId: Int,
    val toRootId: Int,
)

internal class SplitSceneBuild(
    val panes: Map<SplitPane, SplitPickerLivePane>,
    val failed: Set<SplitPane>,
)
