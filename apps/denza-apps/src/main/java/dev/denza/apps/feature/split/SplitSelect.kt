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
        // Правка волны 15: ОКНО, а не мебель продукта (U3, инвариант 3). Пикер-база соседней
        // панели носит наш package, и до сих пор она сюда попадала - но пока каталог отдавал про
        // нас `launchMode` трамплина (`standard`), гард молчал и цены у этого не было. С честным
        // `singleTask` самой `MainActivity` тот же набор запретил бы выбор Denza Apps вообще:
        // соседняя панель всегда держит свою базу (1.5.3, «открылась и работает»).
        val duplicatePeerTasks = before.root(otherRootId)?.tasks.orEmpty()
            .filter { task ->
                !task.isDenzaPickerBase() && task.packageName == target.packageName
            }
        check(duplicatePeerTasks.isEmpty() || target.launchMode < LAUNCH_MODE_SINGLE_TASK) {
            "Это приложение не поддерживает два окна"
        }
        commands.ensureSupported(target.packageName)

        // A picker tap is authoritative proof that this pane is being selected. Free its exact
        // permanent base before requiring the picker to be the root top. Правка W3 волны 8
        // (инвариант 3, примечание контракта под 1.5; диагноз v23 Д2): удаляется только своё по
        // точному компоненту - вторая база или штатный bootstrap, застрявшие в этом корне. Чужая
        // задача в корне - задача пользователя (нативно втянутый хаб - U3: наш package, не наш
        // компонент) и выселяется живой в полноэкранный корень; эта операция ещё ничего не
        // создавала, так что «созданного ею» здесь не бывает.
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
            // Правка W4 (волна 7, контракт 1.5.3): сторона - выбор прошивки, продукт записывает
            // факт. Задача могла встать в другую панель; слот, пикер-хозяин и постусловие берут
            // фактическую сторону вместо того, чтобы объявлять вставшему окну ложный rollback.
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
            // Правка волны 13 (П3, 1.5.2): окном панели становится та задача цели, которая
            // фактически встала сверху, а не та, которую адресовал запуск.
            val settledAppTaskId = settledSelectedAppTaskId(
                rootId = settledRootId,
                pickerHostTaskId = settledPickerHost.id,
                target = target,
                launchedTaskId = launchedTask.id,
            )
            // Правка волны 14 (приёмка v29, дефект D): вторая живая задача ВЫБРАННОГО пакета -
            // законный житель этой панели, а не мусор уборки. Прошивка привела её сюда сама, и
            // выселение живого ВИДИМОГО окна в полноэкранный корень 4 - это не «уехало в фон»:
            // корень 4 фоновых задач не держит вовсе (машинная правда волны 10), так что окно
            // встаёт поверх всей сцены со своими прежними панельными границами. Живьём это
            // давало битый полуэкран и `select outcome=rolled-back reason=В выбранном split-окне
            // нет верхней задачи` - обе настоящие панели становились невидимыми (U5, инвариант 9).
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
     * Запуск цели с live-proven promote в выбранный root - и подтверждением ПО ФАКТУ (правка W4
     * волны 7, контракт 1.5.3). Прошивка кладёт split-способный собственный пакет по СВОИМ
     * правилам стороны и может не отдать promote выбранную панель; прежняя пара «слепая пауза +
     * один снапшот выбранного root» объявляла ложный rollback фактически вставшему окну (live
     * v22 b3: «выбрал Denza Apps → снова пикер, со второго раза открылось»). Успех - задача цели
     * устоялась в ЛЮБОМ из двух панельных root; какой именно, называет её собственный
     * [SplitTask.rootId]. Ошибка - только когда задача реально никуда не встала.
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
     * Какая задача цели стала окном панели - решает мир, а не запуск (правка волны 13, П3).
     *
     * Запуск продукта - `am start` без `MULTIPLE_TASK`, то есть «дай задачу пакета, какая есть»,
     * и прошивка вольна привести в панель не одну (v28: t316 И t532 у Яндекс.Музыки). Верхняя из
     * них - то, что видит пользователь, и именно она становится приложением панели; запущенная
     * задача остаётся ответом только тогда, когда мир не назвал верхней ни одну из задач цели.
     * Собственные компоненты продукта кандидатами не бывают (инвариант 3).
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
     * Постусловие выбора: сверху в панели стоит ЦЕЛЕВОЕ ПРИЛОЖЕНИЕ (правка волны 13, П3).
     *
     * Прежде оно требовало, чтобы верхней стала именно та задача, которую адресовал запуск, - и
     * приёмка v28 показала, чего это стоит: у Яндекс.Музыки две живые задачи (t316, t532),
     * прошивка привела в панель обе, верхней оказалась не запущенная, и продукт объявил ОТКАТ
     * окну, которое пользователь видел открытым. Слот не двигался, выбор не запоминался, первый
     * же Home стирал результат - против 1.5.2 («живая вторая задача пакета - обычный житель
     * мира»), 1.5.3, U5 и инварианта 9. Apple Music с одной задачей коммитилась штатно.
     *
     * Идентичность приложения пользователя доказывает пакет цели, а собственные компоненты
     * продукта в неё не принимаются (инвариант 3, U3): запуск `dev.denza.apps` - это его
     * MainActivity, но никогда не пикер-база. Слот записывается по фактической верхней задаче, и
     * она же становится записанной identity панели.
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
        // Правка волны 14: панель - это её база и ОДНО приложение, а не две задачи. Живая вторая
        // задача выбранного пакета лежит под ним и не мешает ничему (1.5.2); посторонняя задача -
        // мешает, и это по-прежнему отказ.
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
         *  operation's own whole-scene read-back instead (правка A4). */
        const val APP_PLACEMENT_STABLE_SAMPLES = 2
    }
}
