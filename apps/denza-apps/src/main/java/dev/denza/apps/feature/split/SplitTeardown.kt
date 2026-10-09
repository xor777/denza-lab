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

    private companion object {
        /** `mFocusedApp=ActivityRecord{a81ee00 u0 dev.denza.apps/.MainActivity} t332}` */
        val FOCUSED_TASK_PATTERN = Regex("mFocusedApp=ActivityRecord\\{[^}]*\\}\\s+t([0-9]+)\\}")
    }
}
