package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME
import dev.denza.apps.feature.split.SplitWorld.Companion.EXIT_SETTLE_MS
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID

/**
 * Explicit, command-driven split session.
 *
 * This class never watches or interprets arbitrary foreground launches. Every mutation starts
 * from either the dedicated launcher or a tap in a picker, so the destination pane and expected
 * component are known before any task is moved.
 */
internal class SplitPickerShellSession(
    shell: (String) -> String,
    apkPath: String,
    settle: (Long) -> Unit = Thread::sleep,
    gateLeaseStore: SplitGateLeaseStore,
    /** The topology reads of the operation this session belongs to ([SplitWorld]). */
    topology: SplitTopologyCache = SplitTopologyCache(),
    /** Where the shell-UID proxy is loaded from; the APK is the always-valid fallback. */
    proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
    /** Milliseconds spent turning an answer into the model ([SplitWorld]). */
    parsed: (Long) -> Unit = {},
) {
    private val world = SplitWorld(shell, settle, topology, parsed)
    private val commands = SplitTaskCommands(world, apkPath, proxyClasspath)
    private val gate = SplitGate(world, gateLeaseStore)
    private val ownedScene = SplitOwnedScene(world)
    private val edge = SplitEdge(world, commands)
    private val builder = SplitSceneBuilder(world, commands, gate, ownedScene, edge)
    private val selection = SplitSelect(world, commands, gate)
    private val collapse = SplitCollapse(world, commands, ownedScene)
    private val navReturn = SplitNavReturn(world, commands, ownedScene, builder)

    /**
     * Какую задачу система считает сфокусированной, если её вообще можно спросить.
     *
     * `dumpsys window` на этой прошивке отвечает `mCurrentFocus=null` и `mFocusedApp=null` - поле,
     * к которому тянется рука первым, здесь пустое (замерено 2026-08-27). Единственный непустой
     * ответ даёт `dumpsys activity activities`, и он ходит за пальцем: тап в узкую панель называл
     * задачу музыки, в широкую - навигатора, обратно - снова музыки.
     *
     * `topResumedActivity` для этого не годится: он есть у каждого корня отдельно, то есть
     * описывает вершину контейнера, а не единственный фокус экрана.
     *
     * Вывод сужается grep'ом на самой машине: полный дамп большой, а [SplitWorld.validateOutput] отвергает
     * любой вывод со словом «Exception» - в дампе всех активностей оно может встретиться по совсем
     * постороннему поводу. Ничего не бросает: не прочиталось - значит не прочиталось.
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
        // Чей это split, и почему это решается не по id.
        //
        // Штатный пикер в панели - наш артефакт ровно тогда, когда сцена наша: прошивка сама
        // занимает им освободившуюся панель НАШЕЙ сцены, и не убрать его значит оставить
        // пользователю штатный пикер в скрытом корне после выключения. Но ровно такая же задача -
        // это чужой split, собранный пользователем штатными средствами, и его выключение нашего
        // тумблера сносить не должно.
        //
        // Совпадением id эти два случая не различить: id штатного пикера мы не владеем никогда - в
        // записанной сцене лежит id НАШЕГО пикера ([SplitOwnedScene.readOwnedSession], [SplitNavReturn.verifyNavigationReturnedOnce]),
        // - поэтому правило «удалять только по записанному id» вырождается в «не удалять никогда».
        // Доказательство берётся не с пикера, а со сцены: жива ли на главном экране хоть одна
        // задача нашей точной identity (package + activity). Чужой split такую задачу содержать не
        // может, а панель, занятая прошивкой в нашей сцене, - вторая наша панель ещё жива.
        //
        // Сторона отказа безопасная: если от нашей сцены не осталось ничего, штатный пикер живёт.
        val sceneIsOurs = mainDisplayTasks.any { task -> task.isDenzaPickerBase() }
        // `com.byd.sr` здесь не трогается вовсе. Вставленный прошивкой bootstrap снимается там, где
        // продукт знает его id и только что видел его своими глазами: [SplitEdge.attachPicker] →
        // [SplitEdge.removeBootstrapIfPresent], сразу после запуска своего пикера в эту панель. К выключению
        // такого доказательства нет, а пакет настоящий, пользовательский; полноэкранный
        // `com.byd.sr` при этом не проходит [eligible], то есть его нельзя было бы даже опознать
        // как то приложение, ради которого сцена разбирается.
        val pickerTasks = mainDisplayTasks
            .filter { task ->
                (task.isStockSplitPicker() && sceneIsOurs) ||
                    pickerComponents.values.any { component -> task.matchesComponent(component) }
            }
        val pickerTaskIds = pickerTasks.mapTo(mutableSetOf(), SplitTask::id)
        // `am stack list` orders roots by z-order, not by product ownership. A visible picker
        // may therefore be reported before the real application in the peer pane. Do not turn
        // that into "no foreground"; keep walking visible root tops until an actual user task
        // is found.
        // Кого пользователь считал открытым - вопрос к системе, а не к порядку контейнеров.
        //
        // Порядок root'ов в `am stack list` - это z-order, что комментарий ниже и говорит. Живьём
        // (2026-08-27) он в обычных сценах идёт следом за фокусом, поэтому догадка обычно
        // угадывает; но угадывать и знать - разное, а полноэкранным остаётся ровно одно
        // приложение, и ошибка здесь видна пользователю сразу (1.2.3).
        //
        // Чтение необязательное. Команда живьём ещё не проверена (тоннель к машине упал раньше,
        // чем до неё дошло), и незачем менять доказанно рабочее выключение на непроверенную
        // команду: не ответила или назвала кого-то, кого мы и так не берём, - работает прежний
        // обход, и причина уходит в журнал.
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

        // Правка W5 волны 7 (приёмочный пропуск DISABLE-sweep): безусловный финальный проход -
        // СВОИ пикеры по exact identity на всём main display, независимо от того, что успело
        // попасть в [pickerTasks] до перемещения foreground. Грязный мир с множественными
        // сиротами добавляет их между снапшотом `before` и уборкой выше, и порядок гонки решал,
        // выживет ли огрызок. Identity собственного постоянного пикера ([isDenzaPickerBase]) не
        // может назвать чужую задачу, поэтому проход безусловен.
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

    fun livingTaskIds(): Set<Int> = world.livingTaskIds()

    fun sceneCovered(): Boolean = world.sceneCovered()

    fun fullIviRootTaskId(): Int = world.fullIviRootTaskId()

    // endregion

    // region the gate the operations drive themselves ([SplitGate])

    fun suspendOwnedGateForHome(displaced: () -> Boolean = { false }): Boolean =
        gate.suspendOwnedGateForHome(displaced)

    fun suspendOwnedGateIfCovered(): Boolean = gate.suspendOwnedGateIfCovered()

    fun resumeOwnedGateIfVisible(): Boolean = gate.resumeOwnedGateIfVisible()

    fun closeOwnedGate(): Boolean = gate.closeOwnedGate()

    // endregion

    // region the reads of our own scene ([SplitOwnedScene])

    fun existingOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): Map<SplitPane, SplitPickerLivePane>? =
        ownedScene.existingOwnedSession(pickerComponents, expectedApps)

    fun readOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitSceneRead = ownedScene.readOwnedSession(pickerComponents, expectedApps)

    fun readOwnedSelection(pickerComponents: Set<String>): SplitSceneRead =
        ownedScene.readOwnedSelection(pickerComponents)

    fun observePickerTask(hostTaskId: Int, pickerComponents: Set<String>): SplitPickerPaneObservation? =
        ownedScene.observePickerTask(hostTaskId, pickerComponents)

    fun visiblePickerTaskIds(pickerComponents: Set<String>): List<Int> =
        ownedScene.visiblePickerTaskIds(pickerComponents)

    fun singleVisiblePickerTaskId(pickerComponents: Set<String>): Int? =
        ownedScene.singleVisiblePickerTaskId(pickerComponents)

    fun allRecordedMembersAlive(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean = ownedScene.allRecordedMembersAlive(scene, pickerComponents)

    fun deadRecordedApps(scene: Map<SplitPane, SplitPickerLivePane>): Set<SplitPane> =
        ownedScene.deadRecordedApps(scene)

    fun confirmSceneEndMembersDead(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean = ownedScene.confirmSceneEndMembersDead(scene, pickerComponents)

    fun confirmDeadRecordedApps(scene: Map<SplitPane, SplitPickerLivePane>): SplitDeadAppsConfirmation =
        ownedScene.confirmDeadRecordedApps(scene)

    // endregion

    // region the edge and the divider ([SplitEdge])

    fun awaitNativePickerCommit(): Boolean = edge.awaitNativePickerCommit()

    fun nativePickerMutationAllowed(): Boolean = edge.nativePickerMutationAllowed()

    fun observePane(pane: SplitPane, pickerComponents: Set<String>): SplitPickerPaneObservation =
        edge.observePane(pane, pickerComponents)

    fun attachPicker(pane: SplitPane, hostTaskId: Int, pickerComponent: String): Int =
        edge.attachPicker(pane, hostTaskId, pickerComponent)

    internal fun dragDividerToBalanced() = edge.dragDividerToBalanced()

    // endregion

    // region the scene of an open ([SplitSceneBuilder])

    fun revealOwnedSession(
        existing: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Map<SplitPane, SplitPickerLivePane> = builder.revealOwnedSession(existing, pickerComponents)

    fun buildScene(
        pickerComponents: Map<SplitPane, String>,
        targets: Map<SplitPane, SplitLaunchTarget>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
        preexistingTaskIds: Set<Int>? = null,
        onPhase: (String) -> Unit = {},
        onTask: (SplitBuiltTask) -> Unit = {},
    ): SplitSceneBuild = builder.buildScene(
        pickerComponents = pickerComponents,
        targets = targets,
        expectedApps = expectedApps,
        preexistingTaskIds = preexistingTaskIds,
        onPhase = onPhase,
        onTask = onTask,
    )

    // endregion

    // region a tap in a picker ([SplitSelect])

    fun selectApp(
        pickerTaskId: Int,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement = selection.selectApp(pickerTaskId, target, pickerComponents)

    // endregion

    // region what the divider left ([SplitCollapse])

    fun reconcileDividerResize(
        pickerComponents: Set<String>,
        previousPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): Map<SplitPane, SplitPickerLivePane>? =
        collapse.reconcileDividerResize(pickerComponents, previousPanes)

    fun readCollapsedSession(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapseRead = collapse.readCollapsedSession(pickerComponents, expectedPanes)

    fun readCollapsedPaneByExistence(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead = collapse.readCollapsedPaneByExistence(pickerComponents, expectedPanes)

    fun collapsedPaneByPanelBounds(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead = collapse.collapsedPaneByPanelBounds(pickerComponents, expectedPanes)

    // endregion

    // region the navigator back from the cluster ([SplitNavReturn])

    fun prepareNavigationReturn(
        originalRootTaskId: Int,
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitNavigationReturnPlan =
        navReturn.prepareNavigationReturn(originalRootTaskId, pickerComponents, expectedApps)

    fun verifyNavigationReturned(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement =
        navReturn.verifyNavigationReturned(plan, taskId, packageName, pickerComponents)

    fun returnRecordedTaskFullscreen(pane: SplitPane, taskId: Int, packageName: String) =
        navReturn.returnRecordedTaskFullscreen(pane, taskId, packageName)

    // endregion

    private companion object {
        /** `mFocusedApp=ActivityRecord{a81ee00 u0 dev.denza.apps/.MainActivity} t332}` */
        val FOCUSED_TASK_PATTERN = Regex("mFocusedApp=ActivityRecord\\{[^}]*\\}\\s+t([0-9]+)\\}")

    }
}
