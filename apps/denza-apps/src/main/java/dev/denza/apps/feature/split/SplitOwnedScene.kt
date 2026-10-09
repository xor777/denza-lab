package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME

/**
 * The reads that decide whether the scene on the car is the product's own, and what is left of it.
 *
 * All of them are read-only: whether both pane roots hold exactly our permanent base and at most
 * one application ([readOwnedSession]), whether the one pane of a single-pane world does
 * ([readOwnedSelection]), which pane a picker callback belongs to ([observePickerTask]), which of
 * our pickers are visible, and whether the recorded members of a scene are still alive. A refusal
 * names the pane and the predicate that disagreed (U5). The only waits are the one short second
 * read of a scene's end ([confirmSceneEndMembersDead], [confirmDeadRecordedApps]).
 */
internal class SplitOwnedScene(
    private val world: SplitWorld,
) {
    /**
     * Adopts an already-running product scene without mutating the firmware roots.
     *
     * Package replacement restarts Denza Apps but does not remove the two standalone picker
     * tasks. Rebuilding that still-valid scene races SmartMulti's own focus/restore controller.
     * Only accept the scene when both native roots have exactly our permanent base and at most
     * one ordinary app - counted as applications, not as tasks, because a live app may legitimately
     * own several of them (1.5.2); anything less certain falls back to explicit reconstruction.
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
        val area = world.callInt("service call activity_task 30")
        if (area != AREA_BALANCED_SPLIT && area != AREA_FULL_IVI && area != AREA_HOME) {
            return SplitSceneRead(null, "area=$area")
        }
        return readOwnedScene(area, pickerComponents, expectedApps)
    }

    /**
     * Чем сцена оказывается для ЗАВЕРШАЮЩЕГО ЧТЕНИЯ ВЫБОРА, когда панель на экране одна.
     *
     * Правка 2026-09-18 (живая сессия 18:58:27-18:58:36, `read-back: area=2`). Пользователь
     * схлопнул одну панель («пикер | пикер» → `Full(SECONDARY)`, 1.8.2), затем тапнул Навигатор в
     * выжившем полноэкранном пикере. Размещение прошло (`startSplitWindow #68 type=32
     * newMode=102`, area осталась 2, Навигатор виден над пикером), а read-back объявил
     * `rolled-back reason=read-back failed`: [readOwnedSession] принимает только 0/3/4 и требует
     * ОБА панельных корня. Слот не записался, и первый же Home после такого выбора показал бы
     * пикер вместо Навигатора - против 1.3.2 и 1.3.4.
     *
     * Одна панель на весь экран - это законная сцена продукта (`FULL(x)`, ось 2.3), и сам
     * [selectApp] её уже признаёт: при `currentArea == pane.fullArea` и пустом соседнем корне
     * ожидаемая area остаётся 1/2, и постусловие размещения доказывается именно на ней
     * (`expectedSelectionArea`). Поэтому чтение выбора смотрит на выжившую панель ровно теми же
     * предикатами, что [readOwnedSession] применяет к каждой панели живой сцены, - и ничем
     * слабее: своя база ровно одна, жители - не больше одного приложения, ни чужой базы, ни
     * штатного bootstrap, пикер по размеру окна, верхняя задача либо этот пикер, либо приложение
     * в границах корня.
     *
     * Соседний корень обязан не держать НАШЕЙ базы: база там - это двухпанельная сцена, за
     * которой area не успела, и такой мир принадлежит [readOwnedSession], а не этому чтению.
     * Прочие жители соседнего корня - задачи пользователя, отвязанные прошивкой живыми и
     * невидимые (1.8.2, инвариант 3); они ничего не доказывают и ничему не мешают.
     *
     * Это чтение НЕ подменяет [readOwnedSession]: усыновление на открытии (1.3.4) и сверка
     * схлопывания по-прежнему обязаны отказывать на area 1/2 - там закрытая панель должна стать
     * свежим пикером, а не быть усыновлённой как `FULL`.
     */
    fun readOwnedSelection(pickerComponents: Set<String>): SplitSceneRead {
        val area = world.callInt("service call activity_task 30")
        if (area == AREA_BALANCED_SPLIT) {
            return readOwnedScene(area, pickerComponents, emptyMap())
        }
        val survivor = SplitPane.entries.firstOrNull { pane -> pane.fullArea == area }
            ?: return SplitSceneRead(null, "area=$area")
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val other = survivor.other()
        val otherHoldsOwnBase = state.root(roots.getValue(other))?.tasks.orEmpty().any { task ->
            task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
        }
        if (otherHoldsOwnBase) {
            return SplitSceneRead(null, "area=$area, но $other держит базу")
        }
        val root = state.root(roots.getValue(survivor))
            ?: return SplitSceneRead(null, "$survivor: контейнера нет")
        return readOwnedPane(
            pane = survivor,
            root = root,
            area = area,
            sceneOnScreen = true,
            pickerComponents = pickerComponents,
            expected = null,
        )
    }

    /**
     * Обе панели целой сцены - тело [readOwnedSession] уже с прочитанной area.
     *
     * Отдельно от него только ради одного: [readOwnedSelection] на area 3 обязана отвечать тем же
     * самым чтением, но area она уже спросила, а лишний `activity_task 30` в машине стоит времени
     * и, хуже, может застать другой мир, чем тот, из которого принято решение о ветке.
     */
    private fun readOwnedScene(
        area: Int,
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp>,
    ): SplitSceneRead {
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val panes = mutableMapOf<SplitPane, SplitPickerLivePane>()
        SplitPane.entries.forEach { pane ->
            val root = state.root(roots.getValue(pane))
                ?: return SplitSceneRead(null, "$pane: контейнера нет")
            val read = readOwnedPane(
                pane = pane,
                root = root,
                area = area,
                sceneOnScreen = area == AREA_BALANCED_SPLIT,
                pickerComponents = pickerComponents,
                expected = expectedApps[pane],
            )
            panes += read.scene ?: return read
        }
        return SplitSceneRead(panes, "adoptable")
    }

    /**
     * Одна панель теми предикатами, которые делают её НАШЕЙ, - в одном месте на всех читателей.
     *
     * Правила здесь существуют ровно один раз: [readOwnedSession] применяет их к обеим панелям
     * сбалансированной или накрытой сцены, [readOwnedSelection] - к единственной выжившей панели
     * при area 1/2. Успех - карта из одной записи; отказ называет панель и предикат, который
     * не сошёлся (U5).
     *
     * [sceneOnScreen] - это «панель видно»: тогда верхнюю задачу называет то, что видно
     * ([SplitRootTask.resolvedTopTask]). Накрытый мир верхнюю задачу не показывает, и её
     * приходится доказывать записанным id или собственным пикером.
     */
    private fun readOwnedPane(
        pane: SplitPane,
        root: SplitRootTask,
        area: Int,
        sceneOnScreen: Boolean,
        pickerComponents: Set<String>,
        expected: SplitPickerExpectedApp?,
    ): SplitSceneRead {
        val pickers = root.tasks.filter { task ->
            task.isDenzaPickerBase() && task.matchesAnyComponent(pickerComponents)
        }
        val picker = pickers.singleOrNull()
        // Панель - это её база и ОДНО приложение; сколькими живыми задачами прошивка это одно
        // приложение представляет, решает прошивка, а не продукт (1.5.2, машинная правда v28:
        // тап по Яндекс.Музыке привёл в корень И t316, И t532, обе видимые, и панель на экране
        // была правильной). Счёт задач вместо счёта приложений объявлял такую панель чужой, а
        // «чужая панель» - это отказ от адопции: следующее открытие пересобрало бы живую
        // сцену и перезапустило играющее приложение (U2, 1.3.5).
        val residents = root.tasks.filterNot { task ->
            task.id == picker?.id || task.isEmptyRootMarker()
        }
        if (
            picker == null ||
            residents.any { it.isDenzaPickerBase() || it.isNativeSplitBootstrap() } ||
            residents.distinctBy { it.packageName }.size > 1
        ) {
            return SplitSceneRead(
                null,
                "$pane: пикеров ${pickers.size}, задач ${root.tasks.size}",
            )
        }
        if (picker.bounds != root.bounds) {
            return SplitSceneRead(null, "$pane: пикер не по размеру окна")
        }

        val top = when {
            sceneOnScreen -> root.resolvedTopTask()
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
        return SplitSceneRead(
            mapOf(
                pane to SplitPickerLivePane(
                    pane = pane,
                    hostTaskId = picker.id,
                    appTaskId = app?.id,
                    appPackageName = app?.packageName,
                ),
            ),
            "adoptable",
        )
    }

    /** Resolves a picker callback by its task identity; the Activity class never defines a pane. */
    fun observePickerTask(
        hostTaskId: Int,
        pickerComponents: Set<String>,
    ): SplitPickerPaneObservation? {
        val roots = world.nativeRootIds()
        val state = world.snapshot()
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
     * Every product picker currently visible in a panel root, by task id (правка W3 волны 9).
     *
     * The hint that carries no host id is ambiguous only until this list is read: a window event
     * over a scene of ours whose every visible picker is a recorded member of that scene names no
     * single task, and proves the scene all the same. Read-only, and the same two reads
     * [singleVisiblePickerTaskId] already pays for.
     */
    fun visiblePickerTaskIds(pickerComponents: Set<String>): List<Int> {
        val roots = world.nativeRootIds()
        val state = world.snapshot()
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
    }

    /** Resolves a product-picker window hint only when one native root has one visible picker. */
    fun singleVisiblePickerTaskId(pickerComponents: Set<String>): Int? =
        visiblePickerTaskIds(pickerComponents).singleOrNull()

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
        val tasks = world.mainDisplayTasks()
        return scene.values.all { observed ->
            tasks.any { task ->
                task.id == observed.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            } && (observed.appTaskId == null || tasks.any { task ->
                task.id == observed.appTaskId &&
                    task.packageName == observed.appPackageName &&
                    !task.isDenzaPickerBase()
            })
        }
    }

    /**
     * Панели, чьё записанное ПРИЛОЖЕНИЕ больше не живо на main display под его точной identity -
     * task id плюс package, никогда не пикер-база (правка W1 волны 8, диагноз v23 Д1(а); правка
     * 2026-09-18, живая сессия, диагноз «два пикера»).
     *
     * У сцены с записанными приложениями это - якорь её конца. Пикер-базы сознательно не
     * участвуют: выселенная Home-ом база умирает недетерминированно (механизм М2) при живых
     * приложениях пользователя, и её смерть доказывает лишь утрату базы, не конец сцены.
     *
     * Ответ ПОПАНЕЛЬНЫЙ, и это - весь смысл правки 2026-09-18. Здесь стоял предикат «все
     * записанные приложения живы», чьё «нет» вызывающий читал как конец ВСЕЙ сцены: живьём
     * пользователь снял из диспетчера задач одно приложение пары под Home, второе продолжало
     * работать, - и продукт хоронил сцену целиком (оба слота в пикеры, обе базы удалены, живой
     * сосед забыт). Множество мёртвых панелей различает 1.7.3 (умерло одно - освобождается его
     * панель, сосед живёт) и 1.7.5 (умерли все - кончилась сцена). Read-only.
     */
    fun deadRecordedApps(scene: Map<SplitPane, SplitPickerLivePane>): Set<SplitPane> {
        val apps = scene.filterValues { observed -> observed.appTaskId != null }
        check(apps.isNotEmpty()) { "У записанной сцены нет приложений-якорей" }
        val tasks = world.mainDisplayTasks()
        return apps.filterValues { observed ->
            tasks.none { task ->
                task.id == observed.appTaskId &&
                    task.packageName == observed.appPackageName &&
                    !task.isDenzaPickerBase()
            }
        }.keys
    }

    /**
     * Правка W2 волны 8: одно повторное чтение ворот конца сцены через короткую паузу - только
     * на позитивной ветке, перед мутациями уборки. Смерть якоря, увиденная в зубы двухпроходного
     * teardown прошивки, может быть его полутактом (фазовое доказательство v23: 1119 мс между
     * roots и apps-launched у дефектного open); жизнь и нечитаемость второго чтения не требуют.
     *
     * Это - якорь сцены «пикер|пикер», у которой записанных приложений нет вовсе: ответ ему
     * булев, потому что и вопрос булев - живы ли ЧЛЕНЫ. Read-only, ровно одна пауза и ровно одно
     * повторное чтение.
     */
    fun confirmSceneEndMembersDead(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean {
        world.pause(SCENE_END_CONFIRM_SETTLE_MS)
        return !allRecordedMembersAlive(scene, pickerComponents) && world.sceneCovered()
    }

    /**
     * То же второе чтение для якоря-ПРИЛОЖЕНИЙ, но ответ у него попанельный (правка 2026-09-18,
     * живая сессия, диагноз «два пикера»).
     *
     * Булев ответ здесь врал бы дважды: «хоть одно мертво» вызывающий читал как конец всей сцены,
     * а вопрос «накрыта ли она ещё» после паузы приходится задавать в любом случае - и когда
     * мертвы все (1.7.5), и когда мертва одна панель (1.7.3). Поэтому наружу отдаётся то, что
     * второй такт увидел: множество мёртвых панелей И накрытие. Read-only, ровно одна пауза и
     * ровно одно повторное чтение - как у [confirmSceneEndMembersDead].
     */
    fun confirmDeadRecordedApps(
        scene: Map<SplitPane, SplitPickerLivePane>,
    ): SplitDeadAppsConfirmation {
        world.pause(SCENE_END_CONFIRM_SETTLE_MS)
        return SplitDeadAppsConfirmation(
            deadPanes = deadRecordedApps(scene),
            covered = world.sceneCovered(),
        )
    }

    private companion object {
        /**
         * Правка W2 волны 8: пауза второго чтения ворот конца сцены. Короче любого teardown-такта
         * прошивки она быть не обязана - ей достаточно пережить полутакт публикации снапшота;
         * платится она один раз и только на позитивной ветке, у которой впереди мутации уборки.
         */
        const val SCENE_END_CONFIRM_SETTLE_MS = 400L
    }
}

internal data class SplitPickerLivePane(
    val pane: SplitPane,
    val hostTaskId: Int,
    val appTaskId: Int?,
    val appPackageName: String?,
)

/**
 * Что увидел второй такт доказательства у якоря-приложений (правка 2026-09-18, живая сессия,
 * диагноз «два пикера»).
 *
 * [deadPanes] - панели, чьи записанные приложения мертвы и на повторном чтении; [covered] - жива
 * ли ещё причина вообще сюда смотреть (area 0/4, инвариант 5). Пустое [deadPanes] или снятое
 * накрытие означают полутакт прошивки, а не исход: ни одна мутация по такому ответу не идёт.
 */
internal data class SplitDeadAppsConfirmation(
    val deadPanes: Set<SplitPane>,
    val covered: Boolean,
)

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
