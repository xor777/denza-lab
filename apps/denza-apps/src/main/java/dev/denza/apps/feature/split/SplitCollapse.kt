package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_FULL_IVI
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_HOME
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_PRIMARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_SECONDARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.MAX_TASKS_PER_PANE
import dev.denza.apps.feature.split.SplitWorld.Companion.ROOT_SETTLE_MS

/**
 * What a divider gesture left behind, read from the car (contract 1.8; findings, "The divider and
 * the stock picker, read end to end").
 *
 * A resize may leave the two permanent bases in the wrong roots under the right applications, and
 * [reconcileDividerResize] puts them back. A collapse closes one pane, and three reads prove it,
 * each where the others are blind: the surviving root adopted by exact identity
 * ([readCollapsedSession], the only one of them that may move a picker back), the recorded tasks
 * of one pane gone from both panel roots while the area names the survivor
 * ([readCollapsedPaneByExistence]), and the panel root the firmware stretched over the whole
 * screen, which outlives Home and a fullscreen window ([collapsedPaneByPanelBounds]). The
 * reconcile asks them in that order; the open asks the last one alone.
 */
internal class SplitCollapse(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
    private val ownedScene: SplitOwnedScene,
) {
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
        val area = world.callInt("service call activity_task 30")
        if (area == AREA_HOME || area == AREA_FULL_IVI) return null
        world.pause(DIVIDER_RECONCILE_SETTLE_MS)
        ownedScene.existingOwnedSession(pickerComponents)?.let { return it }
        if (world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) return null
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

        val roots = world.nativeRootIds()
        val nativeRootIds = roots.values.toSet()
        val state = world.snapshot()
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
                    task.packageName == packageName
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
                commands.moveTask(host.id, targetRootId, toTop = false)
                moved = true
            }
        }
        if (moved) world.pause(ROOT_SETTLE_MS)
        SplitPane.entries.forEach { pane ->
            commands.normalizeTaskToRoot(
                taskId = hosts.getValue(pane).id,
                rootId = desiredRoots.getValue(pane),
            )
        }
        return ownedScene.existingOwnedSession(pickerComponents)
    }

    /**
     * Adopts the one owned root left by a native edge collapse, with the reason it refused
     * (правка W4, U5).
     *
     * Area 1/2 identifies the surviving native pane, but not the previous logical owner. DiLink
     * may move the surviving app across the two native roots and detach both permanent picker
     * bases while it collapses the divider. Match the survivor by exact recorded task identities,
     * reattach only that app's exact picker base when necessary, and require the other native root
     * to be empty. BYD may retain the dismissed tasks as detached hidden roots; the coordinator
     * removes only those exact recorded artifacts after adoption.
     * This is deliberately separate from [SplitOwnedScene.existingOwnedSession], whose callers require an intact
     * two-root scene.
     *
     * Диагноз v21 жил на полной тишине этих веток: во время двухпроходного teardown каждый
     * fail-closed предикат отказывал честно, и ни одна строка нигде не говорила, который. Команды
     * рецепта не изменены - имена получили только `null`-ветки.
     */
    fun readCollapsedSession(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapseRead {
        val area = world.callInt("service call activity_task 30")
        val survivor = when (area) {
            AREA_PRIMARY_FULL -> SplitPane.PRIMARY
            AREA_SECONDARY_FULL -> SplitPane.SECONDARY
            else -> return SplitCollapseRead(null, "area=$area")
        }
        if (expectedPanes.isEmpty() || !SplitPane.entries.toSet().containsAll(expectedPanes.keys)) {
            return SplitCollapseRead(null, "запись сцены неполна")
        }
        if (expectedPanes.values.any { expected ->
                expected.hostTaskId <= 0 ||
                    ((expected.appTaskId == null) != (expected.packageName == null)) ||
                    (expected.appTaskId != null &&
                        (expected.appTaskId <= 0 || expected.packageName.isNullOrBlank()))
            }
        ) {
            return SplitCollapseRead(null, "запись сцены неполна")
        }

        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val collapsedRoot = state.root(roots.getValue(survivor.other()))
        if (collapsedRoot?.tasks.orEmpty().any { task -> !task.isEmptyRootMarker() }) {
            return SplitCollapseRead(null, "в схлопнутом корне остались задачи")
        }

        val nativeRootIds = roots.values.toSet()
        val survivorRootId = roots.getValue(survivor)
        val root = state.root(survivorRootId)
            ?: return SplitCollapseRead(null, "корень выжившего не читается")
        val previousOwners = expectedPanes.filter { (_, expected) ->
            val hostMatches = root.tasks.any { task ->
                task.id == expected.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            }
            val appMatches = expected.appTaskId?.let { expectedAppTaskId ->
                root.tasks.any { task ->
                    task.id == expectedAppTaskId &&
                        task.packageName == expected.packageName &&
                        !task.isDenzaPickerBase()
                }
            } ?: false
            hostMatches || appMatches
        }
        if (previousOwners.size != 1) {
            return SplitCollapseRead(null, "выживший не опознан: совпадений ${previousOwners.size}")
        }
        val (previousOwner, expected) = previousOwners.entries.single()
        val liveAppPresent = expected.appTaskId?.let { expectedAppTaskId ->
            root.tasks.any { task ->
                task.id == expectedAppTaskId &&
                    task.packageName == expected.packageName &&
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
        if (closedTasksInNativeRoots) {
            return SplitCollapseRead(null, "задачи закрытой панели ещё в панельных корнях")
        }

        val expectedIds = setOfNotNull(
            survivorExpected.hostTaskId,
            survivorExpected.appTaskId,
        )
        if (root.tasks.any { task -> !task.isEmptyRootMarker() && task.id !in expectedIds }) {
            return SplitCollapseRead(null, "в корне выжившего лишние задачи")
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
                ?: return SplitCollapseRead(null, "пикер выжившего не найден по identity")
            val originalRootId = detachedPicker.rootId
            try {
                commands.moveTask(detachedPicker.id, survivorRootId, toTop = false)
                reattachedFromRootId = originalRootId
                world.pause(ROOT_SETTLE_MS)
                check(world.callInt("service call activity_task 30") == survivor.fullArea) {
                    "Split изменился при возврате picker ${detachedPicker.id}"
                }
                commands.normalizeTaskToRoot(detachedPicker.id, survivorRootId)
            } catch (error: Throwable) {
                runCatching { commands.moveTask(detachedPicker.id, originalRootId, toTop = false) }
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
        if (settled != null) return SplitCollapseRead(settled, "adopted")
        val rollbackRootId = reattachedFromRootId
            ?: return SplitCollapseRead(null, "выживший не устоялся после наблюдения")
        val error = IllegalStateException(
            "Split изменился после возврата picker ${survivorExpected.hostTaskId}",
        )
        runCatching { commands.moveTask(survivorExpected.hostTaskId, rollbackRootId, toTop = false) }
            .onFailure(error::addSuppressed)
        throw error
    }

    private fun settledCollapsedPane(
        survivor: SplitPane,
        rootId: Int,
        expected: SplitPickerObservedPane,
        pickerComponents: Set<String>,
    ): SplitPickerLivePane? {
        val settledRoot = world.snapshot().root(rootId) ?: return null
        val picker = settledRoot.tasks.singleOrNull { task ->
            task.id == expected.hostTaskId &&
                task.isDenzaPickerBase() &&
                task.matchesAnyComponent(pickerComponents)
        } ?: return null
        if (picker.bounds != settledRoot.bounds) return null
        val app = expected.appTaskId?.let { appTaskId ->
            settledRoot.tasks.singleOrNull { task ->
                task.id == appTaskId &&
                    task.packageName == expected.packageName &&
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
            appPackageName = app?.packageName,
        )
    }

    /**
     * Which recorded pane a native collapse closed, proven by existence alone (правка W1, 1.8.2,
     * диагноз v21 Д1). Read-only.
     *
     * Прошивочный «Release to close» отвязывает задачи живыми, не убивая, и во время его
     * двухпроходного teardown полный постусловный набор [settledCollapsedPane] честно
     * недоказуем - тот набор остаётся воротами физического reattach выжившего пикера
     * ([readCollapsedSession]), но не воротами чистки памяти. Сам факт схлопывания доказывается
     * существованием: при area 1/2 и записанной двухпанельной сцене схлопнута панель, чьи
     * записанные задачи - host И app, по exact identity - отсутствуют в ОБОИХ панельных root.
     * Выживший - другая панель.
     *
     * Сигнатура отличает схлопывание от краха 1.7.3: у краха host-пикер жив в панельном root и
     * отсутствует только app - такая панель не схлопнута, это путь APP→PICKER. Панель без
     * записанного app схлопнута, когда отсутствует её host.
     *
     * Правка W1 волны 9 (приёмка v24, Д1): КАКАЯ панель схлопнута, решает area, и только она.
     * Карта tx30 (findings) называет выжившую панель поимённо - AREA_PRIMARY_FULL это PRIMARY,
     * AREA_SECONDARY_FULL это SECONDARY, - а схлопнута другая. Существование остаётся условием
     * факта («панель ушла из панельных корней целиком»), но не источником имени: имя, взятое из
     * `absent`, врало бы о мире ровно там, где два чтения расходятся.
     *
     * Отсюда три ответа при area 1/2. Ушли обе - выжившую назвала area: живая геометрия дефекта,
     * где схлопывается широкая SECOND справа, а выжившая узкая уходит fullscreen вместе со
     * смертью своей пикер-базы, - её приложение стоит уже в полноэкранном корне, и из панельных
     * корней пропадают обе. Ушла одна, и это названная area схлопнутая, - тот же факт с
     * подтверждением. Ушла одна, но area называет её ВЫЖИВШЕЙ, - противоречие (переходный такт,
     * задержавшаяся в корне задача схлопнутой панели), и ответ на него fail-closed: закрыть по
     * нему слот значило бы забыть выбор пользователя, а перечитает мир отложенный повтор.
     *
     * Конец сцены здесь ни при чём: он живёт под накрытием (area 0/4), а до этих строк накрытие
     * не доходит вовсе.
     */
    fun readCollapsedPaneByExistence(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead {
        val area = world.callInt("service call activity_task 30")
        val survivorByArea = when (area) {
            AREA_PRIMARY_FULL -> SplitPane.PRIMARY
            AREA_SECONDARY_FULL -> SplitPane.SECONDARY
            else -> return SplitCollapsedPaneRead(null, null, "area=$area")
        }
        if (expectedPanes.keys != SplitPane.entries.toSet()) {
            return SplitCollapsedPaneRead(null, null, "сцена не записана двухпанельной")
        }
        if (!expectedPanes.isCompleteTwoPaneRecord()) {
            return SplitCollapsedPaneRead(null, null, "запись сцены неполна")
        }
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val panelTasks = roots.values.mapNotNull(state::root).flatMap(SplitRootTask::tasks)
        val absent = SplitPane.entries.filter { pane ->
            val expected = expectedPanes.getValue(pane)
            val hostPresent = panelTasks.any { task ->
                task.id == expected.hostTaskId &&
                    task.isDenzaPickerBase() &&
                    task.matchesAnyComponent(pickerComponents)
            }
            val appPresent = expected.appTaskId?.let { appTaskId ->
                panelTasks.any { task ->
                    task.id == appTaskId &&
                        task.packageName == expected.packageName &&
                        !task.isDenzaPickerBase()
                }
            } == true
            !hostPresent && !appPresent
        }
        // Схлопнутую панель называет area, и только она: existence подтверждает этот ответ, а не
        // заменяет его. Панель, покинувшая корни, но названная area ВЫЖИВШЕЙ, - противоречие двух
        // чтений одного мира (переходный такт, задержавшаяся в корне задача схлопнутой панели), и
        // ответ на него - отказ: закрыть по нему слот значило бы забыть выбор пользователя.
        val collapsedByArea = survivorByArea.other()
        return when {
            absent.isEmpty() -> SplitCollapsedPaneRead(
                null,
                null,
                "ни одна панель не покинула панельные корни целиком",
            )
            absent.size > 1 -> SplitCollapsedPaneRead(
                collapsedByArea,
                survivorByArea,
                "collapsed: выживший назван area=$area",
            )
            absent.single() == collapsedByArea ->
                SplitCollapsedPaneRead(collapsedByArea, survivorByArea, "collapsed")
            else -> SplitCollapsedPaneRead(
                null,
                null,
                "панель, покинувшая корни, названа выжившей area=$area",
            )
        }
    }

    /**
     * The collapse proof that outlives the cover: the panel containers keep the geometry the
     * firmware gave them (диагноз волны 12, живой протокол 2026-08-25).
     *
     * Оба прежних доказательства начинают с `area` и слепы при 0/4, а окно, в котором area держит
     * 1/2, живьём длится меньше секунды: свайп схлопывания отвечает area 3→2 через ~0.8 с, Home
     * уводит её в 0 через ~0.2 с после касания. Сверка, чей рецепт начинается со слепой паузы
     * [DIVIDER_RECONCILE_SETTLE_MS], в это окно не смотрит ни разу - и выбор пользователя
     * теряется навсегда (v27 A1-A6, 5 из 5).
     *
     * Прошивка, однако, оставляет след, которого накрытие не стирает: схлопывание РАСТЯГИВАЕТ
     * панельный контейнер выжившего на весь экран и оставляет его таким. Измерено на этой машине:
     * - схлопнуто, затем Home: корни (tx118) `[1704,112][2536,1472]` и `[0,0][2560,1600]`, оба
     *   пусты; пикер-база выжившего жива на весь экран, база закрытой панели мертва или осталась
     *   панельного размера;
     * - обычный Home над живой парой: `[1704,112][2536,1472]` и `[24,112][1680,1472]` - ни один
     *   корень не вложен в другой. Ложное закрытие строго хуже пропущенного, и вложенность
     *   корней здесь - обязательное первое слово прошивки, а не догадка продукта.
     *
     * Выжившую панель называет НЕ `area`: на этой прошивке схлопывание всегда отвечает area=2 и
     * переносит выжившего в контейнер SECONDARY, какой бы стороной он ни был до жеста (живьём:
     * обе стороны дивайдера, обе назвали SECONDARY). Называет её exact identity ОКНА, которое
     * прошивка растянула, - и закрытой панель признаётся только тогда, когда её записанные задачи
     * покинули оба панельных корня. Read-only.
     *
     * Правка волны 13 (П1, приёмка v28: 4 из 4 схлопываний SECONDARY не доказаны): растягивается
     * ОКНО панели, а не её база. У панели с приложением это приложение, и её база остаётся на
     * панельных границах - `v28-targeted` A1 под Home: базы `[24,112][856,1472]` и
     * `[880,112][2536,1472]`, приложение выжившего t520 `[0,0][2560,1600]` в растянутом корне
     * `[0,0][2560,1600]`. Волна 12 искала на растянутых границах базу и потому не находила там
     * никого никогда. У панели БЕЗ приложения окно - сама база, и она растягивается: v25-B(iii),
     * база выжившего t569 `[0,0][2560,1600] visible=true` при базе закрытой панели t570 на
     * панельных `[880,112][2536,1472]` (1.8.2: «выживший-пикер - тоже нормальный случай»).
     * Свежее схлопывание до накрытия растягивает в корне и то и другое (`21-after-dragL`), так
     * что признак «окно панели на границах растянутого корня» верен в обоих измеренных мирах.
     *
     * Правка 2026-09-18 (решение владельца, раздел 5 «К 1.8»): чтение отдаёт ОБА имени. `collapsed`
     * - логическая панель, чей выбор закрыт (`survivor.other()`), `survivorPane` - растянутый
     * контейнер, то есть панель, в которой выживший физически остался. Раньше продукт знал только
     * первое и селил выжившего по старой метке: живьём 18:55 музыка узкой панели после закрытия
     * широкой возвращалась в узкую, хотя прошивка оставила её в широкой.
     */
    fun collapsedPaneByPanelBounds(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead {
        if (expectedPanes.keys != SplitPane.entries.toSet()) {
            return SplitCollapsedPaneRead(null, null, "сцена не записана двухпанельной")
        }
        if (!expectedPanes.isCompleteTwoPaneRecord()) {
            return SplitCollapsedPaneRead(null, null, "запись сцены неполна")
        }
        val roots = world.nativeRootIds()
        val state = world.snapshot()
        val paneBounds = SplitPane.entries.associateWith { pane ->
            state.root(roots.getValue(pane))?.bounds
                ?: return SplitCollapsedPaneRead(null, null, "$pane: контейнера нет")
        }
        val stretched = SplitPane.entries.singleOrNull { pane ->
            paneBounds.getValue(pane).strictlyContains(paneBounds.getValue(pane.other()))
        } ?: return SplitCollapsedPaneRead(null, null, "панельные корни не вложены")
        val stretchedBounds = paneBounds.getValue(stretched)
        val tasks = world.mainDisplayTasks()
        val survivors = SplitPane.entries.filter { pane ->
            val expected = expectedPanes.getValue(pane)
            tasks.any { task ->
                task.bounds == stretchedBounds &&
                    task.isRecordedWindowOf(expected, pickerComponents)
            }
        }
        val survivor = survivors.singleOrNull()
            ?: return SplitCollapsedPaneRead(
                null,
                null,
                "растянутое окно не названо панелью: совпадений ${survivors.size}",
            )
        val collapsed = survivor.other()
        val panelTaskIds = roots.values.mapNotNull(state::root)
            .flatMap(SplitRootTask::tasks)
            .mapTo(mutableSetOf(), SplitTask::id)
        val expectedCollapsed = expectedPanes.getValue(collapsed)
        if (
            expectedCollapsed.hostTaskId in panelTaskIds ||
            expectedCollapsed.appTaskId?.let { appTaskId -> appTaskId in panelTaskIds } == true
        ) {
            return SplitCollapsedPaneRead(
                null,
                null,
                "задачи закрытой панели ещё в панельных корнях",
            )
        }
        val proof = if (expectedPanes.getValue(survivor).appTaskId == null) {
            "пикер выжившего растянут на весь экран"
        } else {
            "приложение выжившего растянуто на весь экран"
        }
        // Выжившего называет растянутый КОНТЕЙНЕР, а не прежняя метка: он и есть та панель, в
        // которой прошивка оставила выжившего (решение владельца 2026-09-18).
        return SplitCollapsedPaneRead(collapsed, stretched, "collapsed: $proof")
    }

    /**
     * Записанное ОКНО панели: её приложение, а если приложения в записи нет - её пикер-база.
     *
     * Это единственное, что прошивка растягивает на схлопывании (правка волны 13, П1), и
     * доказывается оно тем же exact identity, что и везде: task id плюс пакет для приложения,
     * task id плюс собственный компонент для базы (инвариант 3, 4).
     */
    private fun SplitTask.isRecordedWindowOf(
        expected: SplitPickerObservedPane,
        pickerComponents: Set<String>,
    ): Boolean = when (val appTaskId = expected.appTaskId) {
        null -> id == expected.hostTaskId &&
            isDenzaPickerBase() &&
            matchesAnyComponent(pickerComponents)
        else -> id == appTaskId &&
            packageName == expected.packageName &&
            !isDenzaPickerBase()
    }

    /**
     * Записанная сцена, по которой вообще можно судить о мире: у каждой панели есть база, а
     * приложение записано либо целиком (задача И пакет), либо никак (правка W4, U5).
     */
    private fun Map<SplitPane, SplitPickerObservedPane>.isCompleteTwoPaneRecord(): Boolean =
        values.none { expected ->
            expected.hostTaskId <= 0 ||
                ((expected.appTaskId == null) != (expected.packageName == null)) ||
                (expected.appTaskId != null &&
                    (expected.appTaskId <= 0 || expected.packageName.isNullOrBlank()))
        }

    private companion object {
        const val DIVIDER_RECONCILE_SETTLE_MS = 1_500L
    }
}

/**
 * What one read of a collapsed owned session concluded, and why (правка W4).
 *
 * [reason] names the fail-closed predicate that refused - a diagnostic line, never a user-facing
 * message - so an unproven collapse says which gate disagreed instead of a silent `null` (U5).
 */
internal class SplitCollapseRead(
    val pane: SplitPickerLivePane?,
    val reason: String,
)

/**
 * The existence proof's edition of [SplitCollapseRead]: which pane fell, or why it is unknown.
 *
 * [collapsed] - ЛОГИЧЕСКАЯ панель, чей выбор закрыт; [survivorPane] - ФИЗИЧЕСКАЯ панель, в которой
 * прошивка оставила выжившего, и она непуста ровно тогда, когда непуст [collapsed]. На этой
 * прошивке они не обязаны быть противоположны: схлопывание переносит выжившего в контейнер
 * SECONDARY с любой стороны (решение владельца 2026-09-18, раздел 5 «К 1.8»).
 */
internal class SplitCollapsedPaneRead(
    val collapsed: SplitPane?,
    val survivorPane: SplitPane?,
    val reason: String,
)
