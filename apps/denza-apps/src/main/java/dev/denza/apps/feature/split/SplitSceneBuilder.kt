package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.APP_PLACEMENT_CONFIRM_ATTEMPTS
import dev.denza.apps.feature.split.SplitWorld.Companion.APP_PLACEMENT_CONFIRM_INTERVAL_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_PRIMARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_SECONDARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.EXIT_SETTLE_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.MAX_TASKS_PER_PANE
import dev.denza.apps.feature.split.SplitWorld.Companion.ROOT_SETTLE_MS

/**
 * The scene of an open: a covered scene of ours raised as it stands ([revealOwnedSession]), or the
 * whole scene assembled in one recipe - the two permanent picker bases in their roots, the
 * remembered applications above them, and one postcondition over both panes ([buildScene]).
 *
 * A pane whose application does not come back keeps its working picker (1.3.2); only what this
 * build provably created may be removed, and a user's task found in a pane root leaves it alive
 * (invariant 3).
 */
internal class SplitSceneBuilder(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
    private val gate: SplitGate,
    private val ownedScene: SplitOwnedScene,
    private val edge: SplitEdge,
) {
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
        val area = world.callInt("service call activity_task 30")
        if (area == AREA_BALANCED_SPLIT) {
            // A scene already on screen asks the firmware for nothing - with one exception. Its
            // gate may be one a cover suspended and nothing resumed (a call or a camera over the
            // pair, gone again by the time of this tap), and the tap is the explicit resumption of
            // the session either way (to 1.12). Only the lease that suspended it may reopen it: a
            // gate this session never opened is not its to open, and not its to close later.
            gate.resumeOwnedGateIfVisible()
            return existing
        }
        check(area == AREA_FULL_IVI || area == AREA_HOME) {
            "Split-сессия больше не скрыта: area=$area"
        }
        // Contract 5, to 1.12: raising a covered scene of ours is the explicit resumption of this
        // session, and Home suspends exactly the gate this session opened (1.9.1) - without this
        // the return from Home would raise a scene the firmware is no longer holding open.
        gate.ensureGateOpen()

        val focusTaskId = SplitPane.entries.asSequence()
            .mapNotNull { pane -> existing[pane]?.appTaskId }
            .firstOrNull()
            ?: SplitPane.entries.asSequence()
                .mapNotNull { pane -> existing[pane]?.hostTaskId }
                .firstOrNull()
            ?: error("В split-сессии нет задачи для возврата")
        world.run("am task focus $focusTaskId")
        check(world.awaitArea(EXIT_SETTLE_MS) { it == AREA_BALANCED_SPLIT }) {
            "Прошивка не вернула существующий split на экран"
        }
        val revealed = ownedScene.existingOwnedSession(pickerComponents)
            ?: error("Существующая split-сессия изменилась при возврате")
        check(revealed == existing) { "Состав split-сессии изменился при возврате" }
        return revealed
    }

    /**
     * The whole scene in one recipe: the two permanent picker bases and the apps above them.
     *
     * One preamble (the gate, the runtime list), one pass over the roots, the launches back to
     * back, and one postcondition over the whole scene at once ([awaitScenePlacement]); contract
     * 7.7 then adds the operation's own read-back on top. A postcondition measured one pane at a
     * time would pass a pane before the other pane's launch had covered it with its picker.
     *
     * The pickers are the mechanism and the floor: this firmware ignores the pane categories for
     * third-party apps and refuses to hold a split whose root is empty (1.4.1).
     */
    fun buildScene(
        pickerComponents: Map<SplitPane, String>,
        targets: Map<SplitPane, SplitLaunchTarget>,
        /**
         * The exact identities this process recorded for the apps of a still-living scene. A
         * survivor the firmware threw out of the panel roots is taken back by reparenting that
         * exact task instead of launching; anything the map cannot prove exactly falls through to
         * the honest launch below (invariant 4).
         */
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
        /**
         * The ids already living on the main display before this operation's first mutation. It
         * is the operation's own journal knowledge: a failed pane's candidate may be removed only
         * when this build provably created it; a pre-existing task is returned to the background
         * instead. `null` means the past could not be read, and then nothing is ever removed as
         * "created".
         */
        preexistingTaskIds: Set<Int>? = null,
        /**
         * The phases of the build for the diagnostic ring ("roots-started" ...
         * "placement-confirmed"), so that the seconds of a slow build are attributed by the
         * operation itself rather than reconstructed.
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
        gate.ensureGateOpen()
        commands.listOwnPackageForTheDivider()
        val failed = mutableSetOf<SplitPane>()
        val wanted = mutableMapOf<SplitPane, SplitLaunchTarget>()
        targets.forEach { (pane, target) ->
            // A package the firmware will not accept into split is a restore failure of that pane
            // and of nothing else: the neighbour and the pickers are unaffected (1.3.2, U5).
            runCatching { commands.ensureSupported(target.packageName) }
                .onSuccess { wanted[pane] = target }
                .onFailure { failed += pane }
        }
        val rootIds = world.nativeRootIds()
        // Do not enter through activity_task tx115 here. BYD remembers split-capable packages
        // globally and may restore an unrelated OEM companion (notably ADAS) before our launcher
        // gets control. Explicit PRIMARY/SECONDARY categories create and target the same native
        // roots without consulting that remembered OEM pair.
        val before = world.snapshot()

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
        // A picker outside the pane roots is never taken back (findings, "Why the wide picker
        // dies, and who kills it"). Home throws the wide pane's tasks out of their container and a
        // collapse does the same to the closed pane; out there a task that is excluded from
        // recents and lies below Home is trimmed by the firmware at the first new recents task -
        // usually the very tap on the launcher that asked for this open, so an open can read such
        // a picker moments before it goes. It is left to that trim, and the pane gets a fresh
        // picker; its id is kept out of the launch's discovery so the old one is never mistaken
        // for the new.
        val strandedPickerIds = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .filter { task ->
                task.id !in assignedIds &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents.values.toSet())
            }
            .mapTo(mutableSetOf(), SplitTask::id)
        val launchedPanes = mutableSetOf<SplitPane>()
        SplitPane.entries.filterNot(pickerTasks::containsKey).forEach { pane ->
            val picker = commands.launchPickerTask(
                pane = pane,
                pickerComponent = pickerComponents.getValue(pane),
                excludedTaskIds = assignedIds + strandedPickerIds,
            ).also { launchedPanes += pane }
            assignedIds += picker.id
            pickerTasks[pane] = picker
        }
        // A firmware left in a single-pane mode (101/102) keeps a pane launch in its one pane:
        // only START_IVI_PRIMARY re-splits (IVI:463-503). A navigator returned from the cluster
        // into a hidden scene leaves it so (findings, "An open over a single-pane firmware"): the
        // narrow picker was adopted above, the wide launch landed full screen, and there is no
        // divider shadow for a gesture. The narrow side gets a fresh picker, the one launch that
        // re-splits; the adopted one stays under it.
        if (
            launchedPanes.isNotEmpty() &&
            SplitPane.PRIMARY !in launchedPanes &&
            world.callInt("service call activity_task 30") in SINGLE_PANE_AREAS
        ) {
            val picker = commands.launchPickerTask(
                pane = SplitPane.PRIMARY,
                pickerComponent = pickerComponents.getValue(SplitPane.PRIMARY),
                excludedTaskIds = assignedIds + strandedPickerIds,
            )
            launchedPanes += SplitPane.PRIMARY
            assignedIds += picker.id
            pickerTasks[SplitPane.PRIMARY] = picker
            onPhase("resplit-from-single-pane")
        }
        val launchedPicker = launchedPanes.isNotEmpty()
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
                commands.moveTask(picker.id, rootId)
                reparented = true
            }
        }
        // A settle is for something that happened: two pickers already in their roots settle
        // nothing (1.13). What did happen is waited out by condition, not by a blind pause: the
        // read that confirms the reparent is, through the shared topology cache, the very read the
        // apps phase decides from.
        if (reparented) {
            world.awaitSnapshotMatching { state ->
                SplitPane.entries.all { pane ->
                    state.root(rootIds.getValue(pane))?.tasks
                        ?.any { task -> task.id == pickerTasks.getValue(pane).id } == true
                }
            }
        }
        // The synthetic drag backs up exactly one situation: a picker this build launched on a
        // truly empty scene came up as an ordinary fullscreen task. A scene assembled from
        // survivors has no divider on screen to drag - at Home there is none - and is raised by
        // the reveal's own focus command in the apps phase instead.
        if (
            launchedPicker &&
            world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT
        ) {
            edge.dragDividerToBalanced()
            check(world.awaitArea(NATIVE_PICKER_SETTLE_MS) { it == AREA_BALANCED_SPLIT }) {
                "Прошивка не раскрыла native split"
            }
        }
        onPhase(SPLIT_PHASE_ROOTS_PLACED)

        // Phase 3 - the apps. One read decides which pane still needs a launch at all.
        val hostTaskIds = pickerTasks.mapValues { (_, picker) -> picker.id }
        val settled = world.snapshot()
        val appTaskIds = mutableMapOf<SplitPane, Int>()
        val launching = mutableMapOf<SplitPane, SplitLaunchTarget>()
        val adoptedAppIds = mutableListOf<Int>()
        wanted.forEach { (pane, target) ->
            val root = settled.root(rootIds.getValue(pane))
            val top = root?.resolvedTopTask()
            val covered = root?.resolveExpectedCoveredApp(expectedApps[pane])?.takeIf { task ->
                task.id != hostTaskIds.getValue(pane) &&
                    task.packageName == target.packageName &&
                    !task.isDenzaPickerBase() &&
                    !task.isNativeSplitBootstrap()
            }
            val stray = if (covered == null) {
                strayExpectedApp(settled, rootIds, pane, expectedApps[pane], target)
            } else {
                null
            }
            // A living task of the target package already in THIS pane's root is the pane's
            // application, even without the exact recorded id `covered` and `stray` need: the
            // pane shows it rather than launching over it, since with two tasks of a package a
            // launch would bring the second copy and the root would end up holding both.
            val resident = if (covered == null && stray == null) {
                residentAppInPane(settled, rootIds, pane, hostTaskIds, expectedApps[pane], target)
            } else {
                null
            }
            when {
                root != null &&
                    top != null &&
                    top.id != hostTaskIds.getValue(pane) &&
                    top.packageName == target.packageName &&
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
                // U2: the pane still holds the exact recorded task, merely covered or under its
                // picker. It is adopted and later promoted; nothing is launched.
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
                // The firmware threw the exact recorded task out of the panel roots but kept it
                // alive with its panel bounds. Reparenting it back is the whole restore of that
                // pane - the very moves that are already live-proven.
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
                    commands.moveTask(stray.id, rootIds.getValue(pane))
                }
                // The pane's application already stands in its root. It is raised by the same
                // focus as `covered` and takes the pane's size in the batched resize.
                resident != null -> {
                    appTaskIds[pane] = resident.id
                    adoptedAppIds += resident.id
                    onTask(
                        SplitBuiltTask(
                            taskId = resident.id,
                            component = target.componentName,
                            fromRootId = resident.rootId,
                            toRootId = resident.rootId,
                        ),
                    )
                }
                else -> launching[pane] = target
            }
        }
        // A pane is its picker plus at most one app, so whatever else a previous session or a
        // native ending left in one has to leave before this scene can be proven, decided from the
        // read above; a clean pane costs nothing at all.
        val stale = SplitPane.entries.flatMap { pane ->
            val target = wanted[pane]?.packageName
            val keep = setOfNotNull(
                hostTaskIds.getValue(pane),
                appTaskIds[pane],
                // A copy of the package this pane is about to launch is kept, so that the launch
                // reuses the task instead of restarting it (U2).
                if (appTaskIds[pane] == null) {
                    settled.root(rootIds.getValue(pane))?.tasks
                        ?.filter { task -> task.packageName == target }
                        ?.maxByOrNull(SplitTask::id)
                        ?.id
                } else {
                    null
                },
            )
            // In a pane whose application is found, a second living task of the same package is
            // a resident of the pane, not surplus (1.5.2): evicted, it would stand visible in root
            // 4 over the scene. Only a foreign package and our own components are surplus.
            val resident = target?.takeIf { appTaskIds[pane] != null }
            settled.root(rootIds.getValue(pane))?.tasks.orEmpty()
                .filterNot { task ->
                    task.id in keep ||
                        task.isEmptyRootMarker() ||
                        (resident != null &&
                            !task.isOwnSplitComponent() &&
                            task.packageName == resident)
                }
        }
        // Membership of a panel root is no verdict (invariant 3; contract, note under 1.5). Only
        // what is provably ours is removed - our own components by exact identity, and tasks this
        // operation created by its own journal read. Any other task of a root is the user's,
        // however it got there (pulled in natively, an earlier selection), and is evicted alive
        // into the full root, with that root's geometry because it does not go to the background
        // there. An unread past (`preexistingTaskIds == null`) counts as not ours: no proven
        // creation, no removal.
        val (executable, foreign) = stale.partition { task ->
            task.isOwnSplitComponent() ||
                (preexistingTaskIds != null && task.id !in preexistingTaskIds)
        }
        commands.removeTasksSafely(executable.distinctBy(SplitTask::id))
        commands.evictToFullRoot(foreign)
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
            // picker and raises the covered scene on the way (1.9.4). Membership is then confirmed
            // on the read the normalize pass shares.
            adoptedAppIds.forEach { taskId -> world.run("am task focus $taskId") }
            world.awaitSnapshotMatching { state ->
                appTaskIds.all { (pane, taskId) ->
                    state.root(rootIds.getValue(pane))?.tasks?.any { it.id == taskId } == true
                }
            }
        }
        // A build that launched no picker has nothing that asks the firmware for the split: the
        // categories raise it only on our own picker starts, and the synthetic drag has no divider
        // to grab under Home. One focus on an exact owned task - the app if there is one, else a
        // base - raises the assembled scene the way the reveal does (1.9.4); a scene already
        // balanced costs one area read and nothing else.
        if (
            !launchedPicker &&
            world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT
        ) {
            val focusTaskId = appTaskIds.values.firstOrNull()
                ?: hostTaskIds.getValue(SplitPane.PRIMARY)
            world.run("am task focus $focusTaskId")
        }
        onPhase("apps-launched")

        // After the launches a pane is still "base and application", and the build makes it so,
        // not the postcondition: whatever the firmware brought into a root beyond the pair is no
        // reason to fail a finished scene. The cleanup above saw the world before the launches.
        sweepPanesToBaseAndApp(rootIds, hostTaskIds, appTaskIds, preexistingTaskIds)
        // A pane's window is whichever task of its application is actually on top - the same
        // answer the selection takes from the world: the firmware may bring both tasks of a
        // package into the pane and put the other one on top.
        settleResidentWindows(rootIds, appTaskIds)
        // Both bases and both apps take the size of their pane from one read, the divergent ones
        // are resized back to back, and one settle and one more read close the whole batch.
        normalizeSceneToRoots(hostTaskIds, appTaskIds, rootIds, failed)
        // 1.3.2: a pane whose app did not come back keeps its picker - and only what this build
        // itself created may die with the attempt.
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
        // The pane each launch ASKED for, and only that; the pane the world gave is read below.
        val requested = linkedMapOf<SplitPane, Int>()
        groups.forEach { group ->
            val started = group.filter { (pane, target) ->
                runCatching {
                    commands.startTargetInPane(pane, target, secondInstanceOf(pane, paneApps))
                }
                    .onFailure { failed += pane }
                    .isSuccess
            }
            if (started.isEmpty()) return@forEach
            // One poll answers the whole group from the same reads, with no settle before it: a
            // restore's task already exists, so the very first read finds it. A pane keeps its
            // first match, and a pane the short budget leaves unmatched fails alone, degrading to
            // the working picker of 1.3.2 rather than burning reads on a task the firmware will
            // not hold.
            val found = linkedMapOf<SplitPane, SplitTask>()
            world.awaitSnapshotMatching(attempts = RESTORE_DISCOVERY_ATTEMPTS) { state ->
                val tasks = state.roots.asSequence()
                    .filter { it.displayId == MAIN_DISPLAY_ID }
                    .flatMap { it.tasks.asSequence() }
                    .toList()
                started.forEach { (pane, target) ->
                    if (found.containsKey(pane)) return@forEach
                    val candidates = tasks.filter { task ->
                        task.id !in taken &&
                            task.packageName == target.packageName &&
                            !task.isOwnSplitComponent()
                    }
                    // The launch goes in the pane's CATEGORY, and a task that ended in that pane's
                    // root is the firmware's answer to it: place proves more than freshness. With
                    // two tasks of a package, the newest on the whole display may be the OTHER
                    // copy, and promoting it would put both into the pane.
                    candidates.filter { task -> task.rootId == rootIds.getValue(pane) }
                        .ifEmpty { candidates }
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
                commands.promoteTask(task, rootIds.getValue(pane))
                requested[pane] = task.id
            }
        }
        if (requested.isEmpty()) return
        // The promotes are waited out by condition as well: every promoted task listed in its
        // pane root, on a read the following normalize pass then shares. The budget is the
        // restore path's short one: a reparent lands on the very next read, and a task the
        // firmware keeps out of the pane is answered by the pane's honest degradation.
        world.awaitSnapshotMatching(attempts = RESTORE_DISCOVERY_ATTEMPTS) { state ->
            requested.all { (pane, taskId) ->
                state.root(rootIds.getValue(pane))?.tasks?.any { it.id == taskId } == true
            }
        }
        recordSettledPanes(rootIds, requested, appTaskIds, failed)
    }

    /**
     * The build records the side each app actually landed on, not the side it asked for (1.5.3,
     * 1.5.7), as the selection does: everything downstream - the sweep, the geometry, the
     * postcondition - is keyed by pane, and an app the firmware put into the OTHER pane would
     * otherwise be surplus there and evicted, a live visible window over the whole scene.
     *
     * The side goes first to the app the firmware already refused: the world has just declined
     * its [SplitTaskCommands.promoteTask] into the requested root, and there is nothing to argue
     * with (1.5.3, `mPrimaryActivity` persists). An app that landed where it was asked is still
     * movable, and its pane yields first - the honest refusal of 1.3.2, the pane shows its picker.
     */
    private fun recordSettledPanes(
        rootIds: Map<SplitPane, Int>,
        requested: Map<SplitPane, Int>,
        appTaskIds: MutableMap<SplitPane, Int>,
        failed: MutableSet<SplitPane>,
    ) {
        val state = world.snapshot()
        val settledPanes = requested.mapValues { (_, taskId) ->
            SplitPane.entries.firstOrNull { candidate ->
                state.root(rootIds.getValue(candidate))?.tasks?.any { it.id == taskId } == true
            }
        }
        val (displaced, compliant) = settledPanes.entries.partition { (pane, settled) ->
            settled != pane
        }
        // First those whose side the firmware named against the request: there is nothing to
        // argue with, and the pane they asked for honestly degrades to its picker (1.3.2, 1.5.7).
        displaced.forEach { (pane, settled) ->
            failed += pane
            if (settled != null && appTaskIds[settled] == null) {
                appTaskIds[settled] = requested.getValue(pane)
            }
        }
        // Then those that landed where they asked. A pane already taken by a neighbour that
        // landed there is not theirs: a pane is its base and ONE application (invariant 3, 1.3.2).
        compliant.forEach { (pane, _) ->
            if (appTaskIds[pane] == null) {
                appTaskIds[pane] = requested.getValue(pane)
            } else {
                failed += pane
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

    /**
     * The exact recorded app of a pane, alive on the main display outside every panel root.
     * The proof mirrors [resolveExpectedCoveredApp]: the persisted task id, the
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
                task.packageName == expected.packageName &&
                !task.isDenzaPickerBase() &&
                !task.isNativeSplitBootstrap() &&
                task.bounds == root.bounds
        }
    }

    /**
     * The pane's application that already lives in it.
     *
     * The product's launch is `am start` without `MULTIPLE_TASK`, "the package's task, whichever
     * it is", so a pane whose root already holds such a task launches nothing and takes it. The
     * precision is the launch's own - the package - but the outcome is proven, not requested:
     * with two tasks of a package, a launch would bring the second copy over the first.
     *
     * Invariant 3 is not weakened: the product's own components are never "the application
     * found", even when the product's own package is launched. Of several copies, the one this
     * process recorded wins, else the newest.
     */
    private fun residentAppInPane(
        state: SplitTaskSnapshot,
        rootIds: Map<SplitPane, Int>,
        pane: SplitPane,
        hostTaskIds: Map<SplitPane, Int>,
        expected: SplitPickerExpectedApp?,
        target: SplitLaunchTarget,
    ): SplitTask? {
        val root = state.root(rootIds.getValue(pane)) ?: return null
        val candidates = root.tasks.filter { task ->
            task.id != hostTaskIds.getValue(pane) &&
                !task.isEmptyRootMarker() &&
                !task.isOwnSplitComponent() &&
                task.packageName == target.packageName
        }
        return candidates.firstOrNull { task -> task.id == expected?.taskId }
            ?: candidates.maxByOrNull(SplitTask::id)
    }

    /**
     * Which task of its application became each pane's window - the world decides, not the
     * launch; the build's edition of the selection's rule ([SplitSelect]).
     *
     * A pane's record changes only to a task of the SAME package already recorded, and only when
     * that task is on top: a foreign top task is still a refusal of the postcondition, not a new
     * application of the pane. The product's own components are never candidates (invariant 3).
     * The one read here is shared with [normalizeSceneToRoots] through the topology cache.
     */
    private fun settleResidentWindows(
        rootIds: Map<SplitPane, Int>,
        appTaskIds: MutableMap<SplitPane, Int>,
    ) {
        val state = world.snapshot()
        appTaskIds.keys.toList().forEach { pane ->
            val root = state.root(rootIds.getValue(pane)) ?: return@forEach
            val recorded = root.tasks.firstOrNull { task -> task.id == appTaskIds.getValue(pane) }
                ?: return@forEach
            val top = root.resolvedTopTask() ?: return@forEach
            if (
                top.id != recorded.id &&
                !top.isOwnSplitComponent() &&
                top.packageName == recorded.packageName
            ) {
                appTaskIds[pane] = top.id
            }
        }
    }

    /**
     * The build's last word on what its panes hold.
     *
     * The rule is the pre-launch cleanup's: a pane is its picker base and at most one application;
     * what is ours goes, a user's task leaves alive for the full root. The difference is that this
     * pass sees the world AFTER the launches, with whatever the firmware brought into a root by
     * itself, which would otherwise turn a finished scene into a failed postcondition.
     *
     * When nothing had to move, its one read is shared with [normalizeSceneToRoots] through the
     * topology cache.
     */
    private fun sweepPanesToBaseAndApp(
        rootIds: Map<SplitPane, Int>,
        hostTaskIds: Map<SplitPane, Int>,
        appTaskIds: Map<SplitPane, Int>,
        preexistingTaskIds: Set<Int>?,
    ) {
        // Both bases of the scene are untouchable in whichever root they ended: a base outside its
        // pane is a matter for the postcondition, not a reason to kill a live base of our scene.
        val bases = hostTaskIds.values.toSet()
        // The pane's application names its resident, and a second living task of that package is
        // a resident, not surplus (1.5.2). The package is read from the recorded task itself, not
        // from the request: the firmware may put the app into the other pane (1.5.3).
        val state = world.snapshot()
        val residentByRoot = appTaskIds.entries.mapNotNull { (pane, appTaskId) ->
            val rootId = rootIds.getValue(pane)
            state.root(rootId)?.tasks
                ?.firstOrNull { task -> task.id == appTaskId }
                ?.let { app -> rootId to app.packageName }
        }.toMap()
        commands.sweepRootsToBaseAndApp(
            keepByRoot = SplitPane.entries.associate { pane ->
                rootIds.getValue(pane) to (bases + setOfNotNull(appTaskIds[pane]))
            },
            preexistingTaskIds = preexistingTaskIds,
            residentByRoot = residentByRoot,
        )
    }

    /**
     * The whole-scene edition of [SplitTaskCommands.normalizeTaskToRoot], for the one recipe that
     * sizes four tasks at once. Every check is the single-task recipe's own - the same root lookup,
     * the same bounds predicate, the same meaning of a failure - but the snapshot before, the
     * settle and the snapshot after are paid once for the scene instead of once per task.
     *
     * A missing or unresized host is an error of the whole build; an app that is missing or
     * refuses its pane's size degrades only that pane (1.3.2).
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
        val before = world.snapshot()
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
            world.run(
                "am task resize ${resize.taskId} ${resize.bounds.left} ${resize.bounds.top} " +
                    "${resize.bounds.right} ${resize.bounds.bottom}",
            )
        }
        world.pause(ROOT_SETTLE_MS)
        val after = world.snapshot()
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

    /**
     * Clears a failed restoration candidate off the exact picker pane (1.3.2).
     *
     * Only a task this operation CREATED may be removed. A living candidate that existed before
     * is the user's (invariant 3, U2): the pane's degradation neither revives it nor kills it -
     * it goes back alive into the full root by the same reparent that pulled it in. A past the
     * operation did not read (`preexistingTaskIds == null`) counts as not ours: no proven
     * creation, no removal.
     *
     * @return whether the pane actually had to be cleared.
     */
    private fun discardFailedRestoration(
        pane: SplitPane,
        packageName: String,
        pickerTaskId: Int,
        preexistingTaskIds: Set<Int>?,
    ): Boolean {
        val rootId = world.nativeRootIds().getValue(pane)
        val candidates = world.snapshot().root(rootId)?.tasks.orEmpty().filter { task ->
            task.id != pickerTaskId &&
                task.packageName == packageName &&
                !task.isDenzaPickerBase()
        }
        val (created, borrowed) = candidates.partition { task ->
            preexistingTaskIds != null && task.id !in preexistingTaskIds
        }
        val removed = commands.removeTasksSafely(created)
        return commands.evictToFullRoot(borrowed) || removed
    }

    /**
     * The postcondition of a whole built scene: one full agreeing read.
     *
     * BYD publishes task placement before its split-area controller has necessarily committed the
     * same transition, so the loop refuses and retries for as long as any predicate disagrees.
     * One agreeing read says everything at once, for both panes together: the firmware's own area
     * is balanced, each root holds its exact picker base at the root's size with at most one
     * application above it, and the exact expected task is the *visible* top at the root's size.
     * The second, independent observation is the operation's own read-back (contract 7.7,
     * `OpenOperation.readBack`), which re-reads the settled scene from the car after the shared
     * topology is dropped - the guard against a picker left over an application.
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
            sample.exceptionOrNull()?.let { error ->
                lastError = error
                // The recipe has finished changing what the panes hold, and no wait changes it
                // (1.13): polling a hopeless build out would only push the open past its ceiling.
                if (error is SettledPlacementError) throw error
            }
            if (attempt + 1 < APP_PLACEMENT_CONFIRM_ATTEMPTS) {
                world.pause(APP_PLACEMENT_CONFIRM_INTERVAL_MS)
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
        check(world.callInt("service call activity_task 30") == AREA_BALANCED_SPLIT) {
            "Нативный split не активировался"
        }
        val state = world.snapshot()
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
            val appTaskId = appTaskIds[pane]
            // A pane is its base and ONE application, not two tasks: a second living task of the
            // pane application's package is that application's own window (1.5.2) and does not
            // count; anything else beyond the application is what the recipe left, not a
            // transient beat of the firmware.
            val resident = appTaskId?.let { id ->
                root.tasks.firstOrNull { task -> task.id == id }?.packageName
            }
            val occupants = root.tasks.count { task ->
                task.id != hostTaskId &&
                    !task.isEmptyRootMarker() &&
                    !(task.id != appTaskId &&
                        resident != null &&
                        !task.isOwnSplitComponent() &&
                        task.packageName == resident)
            }
            if (occupants > MAX_TASKS_PER_PANE - 1) {
                // Not a transient beat of the firmware but what the recipe left in the root:
                // [sweepPanesToBaseAndApp] clears that, not a wait.
                throw SettledPlacementError("В ${pane.name} накопилось больше двух задач")
            }
            val top = root.resolvedTopTask() ?: error("В ${pane.name} нет верхней задачи")
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
                SplitPickerLivePane(pane, hostTaskId, top.id, top.packageName)
            }
        }
    }

    /**
     * A refusal of the postcondition that no wait cures: the world has settled as the recipe left
     * it. The scene's poll waits out beats of the firmware, not structure.
     */
    private class SettledPlacementError(message: String) : IllegalStateException(message)

    private companion object {
        /** The areas of the firmware's single-pane modes, 101 and 102: one pane, no split. */
        val SINGLE_PANE_AREAS = setOf(AREA_PRIMARY_FULL, AREA_SECONDARY_FULL)
        /**
         * The restore path's waits answer on the first read - a launched task is in
         * `am stack list` at once, and each read on this car costs 250-300 ms by itself. Two
         * passes cover the honest case; no match degrades the pane to its picker at once (1.3.2).
         */
        const val RESTORE_DISCOVERY_ATTEMPTS = 2
        const val NATIVE_PICKER_SETTLE_MS = 450L
    }
}

/**
 * The phase of a build at which the two panel bases are standing in their roots.
 *
 * It is a name an operation compares against rather than free text, because invariant 9 hangs a
 * decision on exactly this instant: past it the panes hold something the recipe has proven, so a
 * refused open leaves them there instead of taking the screen away from the user (1.3.5, U5).
 */
internal const val SPLIT_PHASE_ROOTS_PLACED = "roots-placed"

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

/**
 * What one [SplitSceneBuilder.buildScene] settled.
 *
 * [failed] names the panes whose remembered app did not come back; each of them is on its own
 * working picker, and their names are lines of the diagnostic ring (1.3.2, U5).
 */
internal class SplitSceneBuild(
    val panes: Map<SplitPane, SplitPickerLivePane>,
    val failed: Set<SplitPane>,
)
