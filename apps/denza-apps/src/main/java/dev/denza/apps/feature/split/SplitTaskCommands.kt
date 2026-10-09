package dev.denza.apps.feature.split

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
        // No settle prefix: the await below is already a poll, and the blind pause in front of it
        // was the user waiting out a launch the firmware may have finished (1.13, правка A3).
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
     * hands back the task the package already has - the whole point of a restore, which used to
     * start a fresh copy behind a splash screen and leave the playing one orphaned outside the
     * panes (acceptance v17: music #44 -> #66 -> #81).
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
     * divider's detent map, read"). Asking tx112 first and listing only on "no" left every app that
     * declares `BYD_SUPPORT_SPLIT_ACTIVITY=1` itself outside the list, with "Release to close
     * window" in the wide pane - the hub's defect of 2026-09-11, for anybody's app. The same list
     * makes a package split-capable for placement (`startIviWindow` → `isSupportSplit(task)`), so
     * this is also what keeps a pane app's own next screen in its pane.
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
     * Наш собственный пакет - в runtime-список прошивки, безусловно, один раз на сборку.
     *
     * Здесь стояло `ensureSupported(SPLIT_HOST_PACKAGE)`, и для нашего пакета это было чтение,
     * которое всегда отвечало «да»: манифест несёт `BYD_SUPPORT_SPLIT_ACTIVITY=1`, tx112 верна по
     * построению, и tx125 не звалась никогда (живьём 2026-08-28: tx112 = 1, себя в список не
     * вписываем). Карту детентов дивайдера при этом решает НЕ манифест, а членство пакета
     * широкой панели в runtime-списке (`isDefaultSecondActivity`, изолирующий эксперимент
     * dock-split-v19): навигатор, вписанный сюда через tx125, получает полную карту - ужать,
     * расширить, закрыть; хаб, «уже поддерживаемый» по манифесту, - урезанную, где всё правее
     * середины «Release to close window». Метка, делающая нас split-способными, лишала нас
     * прописки в списке, от которого зависит дивайдер.
     *
     * Путь размещения список тоже читает: `startIviWindow` делит экран, когда `isSupportSplit(task)`,
     * а та первым делом смотрит runtime-список (OTA 2026-09-23; прежнее «не читает вовсе» по корпусу
     * 2026-08-28 было неверно). Так что эта транзакция и делает пакет split-способным для размещения,
     * и даёт ему полную карту детентов; tx112 для нас и так `1`. Она стоит один round trip, как стоило чтение, которое она заменила, и
     * попадает в ринг строкой «allowlist extended» (1.12): след живёт до перезагрузки, как и у
     * любого выбранного приложения. Проверка tx112 после неё не нужна - манифест наш.
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
     * The removals themselves are the same exact-identity calls they have always been; what changed
     * is that a recipe clearing several tasks no longer starts `app_process` several times. Loading
     * the proxy dominates the cost of a removal by an order of magnitude, so a batch of three used
     * to be three whole class loads and three settle pauses for work the firmware does at once.
     */
    /** @return whether any of them was actually removed. */
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
     * Правка W3 волны 8: выселение чужого из панельного корня - живьём, в полноэкранный IVI root
     * (примечание контракта под 1.5, инвариант 3). Команда - то же live-proven семейство
     * `am stack move-task ... false`, которым borrowed-ветка [discardFailedRestoration] возвращала
     * пре-существовавший таск; новых команд у выселения нет.
     *
     * Имя «in background» было ЛОЖЬЮ и удалено (живое измерение 2026-08-28): `am stack move-task
     * <id> 4 false` не убирает задачу в фон - она остаётся в корне 4 со `visible=true`. Это та же
     * машинная правда волны 10 «корень 4 фоновых задач не держит», прочитанная с другой стороны:
     * выселенное окно не уезжает, оно ОСТАЁТСЯ ВИДИМЫМ. Значит его границы обязаны быть границами
     * его корня - иначе оно накрывает всю сцену чужой геометрией (дефект 2026-08-27:
     * `dev.denza.apps` шириной панели 832 px в полноэкранном корне 4 поверх обеих панелей).
     *
     * Прежний вывод «геометрия выселенной задачи принадлежит прошивке, спорить нечем» верен только
     * для задачи, уехавшей в СОБСТВЕННЫЙ корень (`RootTask id=<taskId>`): там границы корня и есть
     * границы задачи, приводить нечего. Для задачи-ЛИСТА внутри `ivi_full` ресайз принимается в обе
     * стороны (живое измерение 2026-08-28, задача 151: `am task resize` в панельные 832 px и
     * обратно в полноэкранные - оба раза принят). Поэтому нормализация здесь - тот же
     * [normalizeTaskToRoot], а не новый механизм, и она молчалива: пакет, чью геометрию прошивка
     * не отдаёт (live v20 P1.2, `resizeableActivity="false"`), не должен стоить пользователю всей
     * сцены. Первый эшелон против этого дефекта - не выселение, а [recordSettledPanes].
     *
     * @return whether anything actually had to leave.
     */
    fun evictToFullRoot(tasks: List<SplitTask>): Boolean {
        if (tasks.isEmpty()) return false
        val fullRootId = world.fullIviRootTaskId()
        tasks.forEach { task -> moveTask(task.id, fullRootId, toTop = false) }
        world.pause(ROOT_SETTLE_MS)
        normalizeEvictedTasksToTheirRoots(tasks.mapTo(mutableSetOf(), SplitTask::id))
        raiseSceneOverTheEvicted()
        return true
    }

    /**
     * Та же машинная правда, дочитанная до конца: раз выселенное окно остаётся видимым, оно
     * встаёт ПОВЕРХ сцены (инцидент владельца 2026-08-27, `dev.denza.apps` во весь экран над обеими
     * панелями), и area отвечает 4. Выселение без этого шага - рецепт, чьё постусловие (area 3)
     * не может сойтись по построению: следом стоял откат «Нативный split не активировался», а
     * пользователь смотрел на выселенное окно. Живьём это и есть «приложение вдруг на весь
     * экран» после тапа в пикере или по кнопке.
     *
     * Сцену поднимает тот же live-proven `am task focus`, которым reveal возвращает накрытую пару
     * поверх чужого полноэкранного окна (1.9.4, приёмка v24 A1: VLC/Brave поверх, 25-60 с). Фокус
     * идёт на ВЕРХНЮЮ задачу панели: порядок в корне он не меняет, а панельные контейнеры возвращает
     * на передний план. Читается одна area: сцена, которую выселение не накрыло, не стоит ни одной
     * команды. Постусловие рецепта судит само - здесь ожидание не бросает.
     */
    private fun raiseSceneOverTheEvicted() {
        if (world.callInt("service call activity_task 30") != AREA_FULL_IVI) return
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val top = SplitPane.entries.firstNotNullOfOrNull { pane ->
            val root = state.root(roots.getValue(pane)) ?: return@firstNotNullOfOrNull null
            // Под накрытием `am stack list` прячет всех детей панели; верхнюю тогда называет
            // компонент корня, а если и его нет - порядок, в котором прошивка перечисляет детей
            // (снизу вверх).
            (root.resolvedTopTask() ?: root.resolvedCoveredTopTask() ?: root.tasks.lastOrNull())
                ?.takeUnless { task -> task.isEmptyRootMarker() }
        } ?: return
        world.run("am task focus ${top.id}")
        world.awaitArea(EXIT_SETTLE_MS) { area -> area != AREA_FULL_IVI }
    }

    /**
     * Инвариант геометрии для того, что сцена больше не держит: ни одна выселенная задача не
     * остаётся с границами, не равными границам корня, в котором она оказалась.
     *
     * Одно чтение на всё выселение называет, кто где приземлился; задача в собственном корне уже
     * равна ему и не стоит ни одной команды.
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
     * Тот же проход для одной панели - его платит и выбор приложения (правка волны 13, П3).
     *
     * Прошивка приводит в панель не только то, что попросили: живьём (v28) тап по пакету с двумя
     * живыми задачами привёл в корень обе. Панель - это её пикер-база и не больше одного
     * приложения, поэтому лишнее уходит по тому же правилу, что и у сборки: своё и созданное
     * этой операцией удаляется, задача пользователя уезжает живой в полноэкранный корень
     * (инвариант 3, U2). Ни одной команды в обычном случае: лишнего нет - выхода нет.
     *
     * [residentByRoot] - приложение, которое каждая из этих панелей показывает. Его вторая живая
     * задача лишней не бывает (1.5.2, правка волны 14): прошивка привела её сюда сама, сверху всё
     * равно стоит то же самое приложение, и панель на экране правильная. Считать её лишней стоило
     * волне 13 приёмки v29 - см. [selectApp].
     *
     * Правка 2026-09-04: то же правило для обеих панелей сборки, а не только для одной панели
     * выбора. Путь ВОССТАНОВЛЕНИЯ этого аргумента не передавал вовсе, и вторая задача пакета
     * уезжала в полноэкранный корень 4 - тот самый, что фоновых задач не держит: окно вставало
     * поверх всей сцены (инцидент владельца 2026-08-27, предсказан и отложен в волне 15).
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
        evictToFullRoot(foreign)
    }

    private companion object {
        /** `NEW_TASK | RESET_TASK_IF_NEEDED`: the package's own task, whichever one that is. */
        const val APP_LAUNCH_FLAGS = "0x10200000"

        /** The same plus `MULTIPLE_TASK`: a second, independent copy and nothing else (1.5.2). */
        const val SECOND_INSTANCE_FLAGS = "0x18200000"
        const val PICKER_LAUNCH_FLAGS = "0x18010000"
        const val PRIMARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_PRIMARY"
        const val SECONDARY_PICKER_CATEGORY = "byd.intent.category.START_IVI_SECOND"
        const val PICKER_SETTLE_MS = 150L
        const val SPLIT_PROXY_CLASS = "dev.denza.apps.feature.split.SplitTaskProxyMain"
        const val SPLIT_PROXY_RESULT_PREFIX = "DENZA_SPLIT_RESULT:"
    }
}
