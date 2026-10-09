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
        // usually the very tap on the launcher that asked for this open. On 2026-09-18 18:55:35 an
        // open read such a picker 44 ms before it went. It is left to that trim, and the pane
        // gets a fresh picker; its id is kept out of the launch's discovery so the old one is
        // never mistaken for the new.
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
        // only START_IVI_PRIMARY re-splits (IVI:463-503). It is how a navigator returned from the
        // cluster into a hidden scene leaves it (live 2026-09-23 19:24): the narrow picker it
        // stands on was adopted above, the wide launch landed full screen, and there is no divider
        // shadow for a gesture. The narrow side gets a fresh picker, the one launch that re-splits;
        // the adopted one stays under it.
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
        // nothing (1.13, "не заставлять ждать там, где ждать нечего"). And what did happen is
        // waited out by condition, not by a blind pause: the read that confirms the reparent is,
        // through the shared topology cache, the very read the apps phase decides from (правка A3).
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
        // the reveal's own focus command in the apps phase instead (правка B1).
        if (launchedPicker && world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
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
            // Правка W1 волны 10 (приёмка v25, Д1): живая задача целевого пакета, уже стоящая в
            // корне ЭТОЙ панели, и есть приложение панели. Прежде её не признавал никто - `covered`
            // и `stray` требуют точный записанный id, - и панель уходила в ЗАПУСК поверх живой
            // копии. При двух задачах одного пакета прошивка приносила по `am start` вторую копию,
            // а поиск по пакету называл первую и втаскивал её следом: в корне оказывались обе
            // (живьём v25: `music t316+t532 RELAUNCHED into SECONDARY`, три задачи в панели).
            // Приложение живо - значит панель его показывает, а не запускает копию.
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
                    commands.moveTask(stray.id, rootIds.getValue(pane))
                }
                // Правка W1 волны 10: приложение панели уже стоит в её корне. Оно поднимается тем
                // же focus, что и `covered`, и получает размер панели в общем пакетном ресайзе.
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
        // native ending left in one has to leave before this scene can be proven. It is the rule
        // the two blind `prunePane` calls used to run, now decided from the read above; a clean
        // pane costs nothing at all, and the copy of the package this pane is about to show is
        // kept so that restoring it reuses the task instead of restarting it (U2).
        val stale = SplitPane.entries.flatMap { pane ->
            val target = wanted[pane]?.packageName
            val keep = setOfNotNull(
                hostTaskIds.getValue(pane),
                appTaskIds[pane],
                // Правка W1 волны 10: копия пакета в корне бережётся ради ЗАПУСКА, чтобы он
                // переиспользовал задачу вместо перезапуска (U2).
                if (appTaskIds[pane] == null) {
                    settled.root(rootIds.getValue(pane))?.tasks
                        ?.filter { task -> task.packageName == target }
                        ?.maxByOrNull(SplitTask::id)
                        ?.id
                } else {
                    null
                },
            )
            // Правка 2026-09-04: у панели, чьё приложение уже найдено, вторая живая задача того же
            // пакета - житель панели, а не лишнее (1.5.2, правка волны 14). Волна 10 отправляла её
            // «живой в фон», но корень 4 фона не держит (машинная правда волны 10): окно вставало
            // поверх сцены. Лишним остаётся только чужой пакет и собственные компоненты.
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
        // Правка W3 волны 8 (инвариант 3, примечание контракта под 1.5; диагноз v23 Д1(б)/Д2):
        // членство в панельном корне - не приговор. Удаляется только доказуемо своё - собственные
        // компоненты по exact identity и задачи, СОЗДАННЫЕ этой операцией по её же журнальному
        // чтению (механика W6). Любая другая задача корня - задача пользователя, чем бы она туда
        // ни попала (нативное втягивание, прежний выбор), и выселяется живой в полноэкранный
        // корень - с его геометрией, потому что в фон она там не уходит. Непрочитанное
        // прошлое (`preexistingTaskIds == null`) трактуется как «не наше»: не доказано создание -
        // не удаляем.
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
            // picker and raises the covered scene on the way (правка B1, к 1.9.4). Membership is
            // then confirmed on the read the normalize pass shares.
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
        // base - raises the assembled scene the way the reveal does (правка B1, к 1.9.4); a scene
        // already balanced costs one area read and nothing else.
        if (!launchedPicker && world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            val focusTaskId = appTaskIds.values.firstOrNull()
                ?: hostTaskIds.getValue(SplitPane.PRIMARY)
            world.run("am task focus $focusTaskId")
        }
        onPhase("apps-launched")

        // Правка W3 волны 10 (владелец, 2026-08-25): панель после запусков обязана остаться «база
        // и приложение», и добивается этого сборка, а не постусловие. Всё, что прошивка успела
        // принести в корень сверх пары, - не повод провалить готовую сцену и оставить экран
        // пустым: своё убирается, задача пользователя уезжает живой в полноэкранный корень (в фон
        // она там не уходит, поэтому и получает его геометрию). Предпусковая чистка
        // выше видит мир ДО запусков и до новоприбывших не достаёт.
        sweepPanesToBaseAndApp(rootIds, hostTaskIds, appTaskIds, preexistingTaskIds)
        // Правка 2026-09-04: окном панели становится та задача её приложения, что фактически
        // стоит сверху, - тот же ответ миру, что даёт выбор (`settledSelectedAppTaskId`, правка
        // волны 13). Прошивка вправе привести в панель обе задачи пакета и положить сверху не ту,
        // которую адресовал запуск; уборка выше теперь её не выселяет, значит и постусловие
        // обязано спрашивать про приложение, а не про адресата.
        settleResidentWindows(rootIds, appTaskIds)
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
        // Панель, КОТОРУЮ ПРОСИЛИ, - и только она. Панель, которую назвал мир, читается ниже.
        val requested = linkedMapOf<SplitPane, Int>()
        groups.forEach { group ->
            val started = group.filter { (pane, target) ->
                runCatching { commands.startTargetInPane(pane, target, secondInstanceOf(pane, paneApps)) }
                    .onFailure { failed += pane }
                    .isSuccess
            }
            if (started.isEmpty()) return@forEach
            // One poll answers the whole group from the same reads, and the blind settle that
            // used to precede the waiting is gone: a restore's task already exists, so the very
            // first read finds it (правка A2/A3). A pane keeps its first match - exactly what the
            // per-pane wait did - and a pane the short budget leaves unmatched fails alone,
            // degrading to the working picker of 1.3.2 (правка W5): the red
            // branch of v20 P1.2 burned two twelve-read budgets (~5 с каждый) against a task the
            // firmware refused to hold.
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
                    // Правка W2 волны 10 (приёмка v25, Д1): запуск идёт в КАТЕГОРИИ панели, и
                    // задача, оказавшаяся в её корне, - это и есть ответ прошивки на него. Прежний
                    // «максимальный id по всему дисплею» при двух задачах пакета называл ДРУГУЮ
                    // копию и втаскивал её `promoteTask`-ом поверх той, что запуск уже принёс: в
                    // панели оказывались обе (живьём `music t316+t532 RELAUNCHED into SECONDARY`),
                    // и постусловие валило всю сборку. Место доказывает больше, чем свежесть.
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
        // pane root, on a read the following normalize pass then shares (правка A3). The budget
        // is the restore path's short one (правка W5): a reparent lands on the very next read,
        // and a task the firmware keeps out of the pane is answered by the pane's honest
        // degradation, not by twelve reads of hope.
        world.awaitSnapshotMatching(attempts = RESTORE_DISCOVERY_ATTEMPTS) { state ->
            requested.all { (pane, taskId) ->
                state.root(rootIds.getValue(pane))?.tasks?.any { it.id == taskId } == true
            }
        }
        recordSettledPanes(rootIds, requested, appTaskIds, failed)
    }

    /**
     * Правка волны 15 (дефект 2026-08-27, контракт 1.5.3/1.5.7): сборка записывает ФАКТИЧЕСКУЮ
     * сторону приземления, а не запрошенную.
     *
     * Путь ВЫБОРА умеет это с волны 7 ([selectApp], `settledPane`), путь СБОРКИ не умел: он писал
     * `appTaskIds[запрошенная панель]`, и всё ниже по течению - уборка [sweepPanesToBaseAndApp],
     * геометрия [normalizeSceneToRoots], постусловие [scenePlacement] - ключевалось запрошенной
     * панелью. Задача, которую прошивка посадила в СОСЕДНЮЮ панель, в keep-набор своего корня не
     * попадала, становилась там лишней и уезжала выселением - живое видимое окно владельца
     * (2026-08-27: `dev.denza.apps` шириной 832 px поверх всей сцены).
     *
     * Приоритет стороны - у того, кому прошивка уже отказала: его [SplitTaskCommands.promoteTask] в запрошенный
     * корень мир только что не отдал, и переспорить это нечем (1.5.3, `mPrimaryActivity`
     * персистит). Приложение, вставшее туда, куда просили, ещё подвижно, и панель уступает ему
     * первой - это честный отказ 1.3.2 «панель показывает пикер», а не перетаскивание чужого.
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
        // Сначала те, чью сторону назвала прошивка вопреки запросу: спорить с ней нечем, и
        // запрошенная ими панель честно деградирует в свой пикер (1.3.2, 1.5.7).
        displaced.forEach { (pane, settled) ->
            failed += pane
            if (settled != null && appTaskIds[settled] == null) {
                appTaskIds[settled] = requested.getValue(pane)
            }
        }
        // Затем те, кто встал куда просили. Панель, уже занятая приземлившимся соседом, им не
        // достаётся: панель - это база и ОДНО приложение (инвариант 3, 1.3.2).
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
                task.packageName == expected.packageName &&
                !task.isDenzaPickerBase() &&
                !task.isNativeSplitBootstrap() &&
                task.bounds == root.bounds
        }
    }

    /**
     * Приложение панели, которое уже в ней живёт (правка W1 волны 10).
     *
     * Запуск этого продукта - `am start` без `MULTIPLE_TASK`, то есть «дай задачу пакета, какая
     * есть». Значит панель, в корне которой такая задача уже стоит, ничего не запускает: она её
     * принимает. Точность здесь ровно та же, что у самого запуска - пакет, - но исход доказан, а
     * не заказан: при двух задачах пакета запуск приносил вторую копию поверх первой.
     *
     * Инвариант 3 не ослаблен: собственные компоненты продукта (пикер-база, штатный bootstrap)
     * не могут быть «найденным приложением» даже когда запускается пакет продукта.
     * Из нескольких копий предпочитается записанная этим процессом, иначе - свежайшая.
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
     * Какая задача приложения стала окном каждой панели - решает мир, а не запуск (правка
     * 2026-09-04; зеркало [settledSelectedAppTaskId] для сборки).
     *
     * Запись панели меняется только на задачу ТОГО ЖЕ пакета, что уже записан, и только когда
     * именно она стоит сверху: чужая верхняя задача - по-прежнему отказ постусловия, а не новое
     * приложение панели. Собственные компоненты продукта кандидатами не бывают (инвариант 3).
     * Чтение одно и оно же достаётся [normalizeSceneToRoots] через общий кэш топологии.
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
     * Правка W3 волны 10: последнее слово сборки о составе панелей.
     *
     * Правило то же, что у предпусковой чистки: панель - это её пикер-база и не больше одного
     * приложения; своё (собственные компоненты и созданное этой операцией по её же журнальному
     * чтению) убирается, всё остальное - задача пользователя и уезжает живой в полноэкранный
     * корень. Отличие одно и оно решающее: этот проход видит мир ПОСЛЕ запусков, то есть тех, кого
     * прошивка привела в корень сама. Живьём (v25 Д1) именно они делали панель трёхзадачной, а
     * постусловие превращало готовую сцену в откат в пустоту.
     *
     * Чтение здесь одно и оно же достаётся [normalizeSceneToRoots] через общий кэш топологии,
     * когда двигать не пришлось ничего.
     */
    private fun sweepPanesToBaseAndApp(
        rootIds: Map<SplitPane, Int>,
        hostTaskIds: Map<SplitPane, Int>,
        appTaskIds: Map<SplitPane, Int>,
        preexistingTaskIds: Set<Int>?,
    ) {
        // Обе базы сцены неприкосновенны, в чьём бы корне ни оказались: база не в своей панели -
        // это задача для постусловия, а не повод убить живую базу собственной сцены.
        val bases = hostTaskIds.values.toSet()
        // Приложение панели называет её жильца, и вторая живая задача того же пакета - житель, а
        // не лишнее (1.5.2, правка волны 14 на пути выбора; здесь - правка 2026-09-04). Пакет
        // читается с самой записанной задачи, а не с запроса: прошивка вправе посадить приложение
        // в соседнюю панель (1.5.3), и тогда `wanted[pane]` назвал бы не того.
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
     * The whole-scene edition of [SplitTaskCommands.normalizeTaskToRoot], for the one recipe that sizes four tasks
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
     * Правка W6 (v20 P1.2): удалить можно только задачу, СОЗДАННУЮ этой операцией. Живой
     * пре-существовавший таск кандидата - чужое имущество (инвариант 3, U2): деградация паны
     * его не воскрешает, но и не казнит - он возвращается живым в полноэкранный root тем же
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
            sample.exceptionOrNull()?.let { error ->
                lastError = error
                // Правка W3 волны 10 (§1.13): состав панели рецепт уже закончил менять, и ждать
                // его нечем - доведение безнадёжной сборки было чистым ожиданием. Живьём v25 Д1:
                // 20 проб по 100 мс жгли 7.1-7.5 с поверх готового рецепта и уводили открытие за
                // потолок в 10 с.
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
            // Правка 2026-09-04: панель - это база и ОДНО приложение, а не две задачи. Вторая
            // живая задача того же пакета, что и приложение панели, - его же окно (1.5.2, правка
            // волны 14 на пути выбора) и в счёт не идёт; всё остальное сверх приложения - состав,
            // который рецепт уже закончил менять, а не переходный такт прошивки.
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
                // Не переходный такт прошивки, а состав корня, который рецепт уже закончил менять
                // (правка W3 волны 10): его выметает [sweepPanesToBaseAndApp], а не ожидание.
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
     * Отказ постусловия, который ожиданием не лечится: мир уже устоялся в том виде, в каком его
     * оставил рецепт (правка W3 волны 10). Полл сцены пережидает такты прошивки, а не структуру.
     */
    private class SettledPlacementError(message: String) : IllegalStateException(message)

    private companion object {
        /** The areas of the firmware's single-pane modes, 101 and 102: one pane, no split. */
        val SINGLE_PANE_AREAS = setOf(AREA_PRIMARY_FULL, AREA_SECONDARY_FULL)
        /**
         * Правка W5 (v20 P1.2): ожидания restore-пути отвечают с первого чтения - запущенная
         * задача попадает в `am stack list` сразу, тёплый запуск пикера стоит ~0.9 с вместе с
         * собственным round trip `am start`, а каждое чтение на этой машине само по себе
         * 250-300 мс. Два прохода покрывают честный случай; не-матч - немедленная деградация
         * паны в пикер с нотисом 1.3.2 (~1 c ветки вместо двух сгоревших 12-кратных бюджетов
         * по ~5 с у красной ветки restore).
         */
        const val RESTORE_DISCOVERY_ATTEMPTS = 2
        const val NATIVE_PICKER_SETTLE_MS = 450L
    }
}

/**
 * The phase of a build at which the two panel bases are standing in their roots (правка W10).
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
