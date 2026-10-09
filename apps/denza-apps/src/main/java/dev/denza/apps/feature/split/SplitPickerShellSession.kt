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
import dev.denza.apps.feature.split.SplitWorld.Companion.TASK_DISCOVERY_ATTEMPTS
import dev.denza.apps.feature.split.SplitWorld.Companion.TASK_DISCOVERY_INTERVAL_MS

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
        val roots = world.nativeRootIds()
        val originalPane = SplitPane.entries.firstOrNull { pane ->
            roots.getValue(pane) == originalRootTaskId
        }
        val hiddenOwnedSession = ownedScene.existingOwnedSession(pickerComponents, expectedApps)
        if (hiddenOwnedSession != null) {
            builder.revealOwnedSession(hiddenOwnedSession, pickerComponents)
        }
        if (world.callInt("service call activity_task 30") != AREA_BALANCED_SPLIT) {
            return SplitNavigationReturnPlan(
                pane = originalPane,
                rootTaskId = originalRootTaskId,
                hostTaskId = null,
                fullscreen = true,
            )
        }

        val before = world.snapshot()
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
            .map { task -> SplitDisplacedTask(task.id, task.packageName) }
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
                    world.pause(TASK_DISCOVERY_INTERVAL_MS)
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
        val root = world.snapshot().root(plan.rootTaskId)
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
                top.packageName == packageName &&
                top.bounds == root.bounds,
        ) {
            "Навигация не заняла выбранное split-окно"
        }
        check(root.tasks.size <= MAX_TASKS_PER_PANE) {
            "В окне возврата накопилось больше двух задач"
        }
        return SplitPickerPlacement(pane, hostTaskId, taskId, packageName)
    }

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

    fun returnRecordedTaskFullscreen(
        pane: SplitPane,
        taskId: Int,
        packageName: String,
    ) {
        val before = world.snapshot()
        val task = before.roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId && it.packageName == packageName }
            ?: return
        val paneRootId = world.nativeRootIds().getValue(pane)
        if (task.rootId != paneRootId) return
        commands.moveTask(task.id, paneRootId)
        world.callVoid(
            "service call activity_task 114 i32 " +
                if (pane == SplitPane.PRIMARY) EXPAND_PRIMARY_MODE else EXPAND_SECONDARY_MODE,
        )
        check(world.awaitArea(EXIT_SETTLE_MS) { it != AREA_BALANCED_SPLIT }) {
            "Возвращённое приложение осталось в закрытом split-контейнере"
        }
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
        // записанной сцене лежит id НАШЕГО пикера ([SplitOwnedScene.readOwnedSession], [verifyNavigationReturnedOnce]),
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

    private fun expectedSelectionArea(
        pane: SplitPane,
        currentArea: Int,
        otherRootVacant: Boolean,
    ): Int = when {
        currentArea == AREA_BALANCED_SPLIT -> AREA_BALANCED_SPLIT
        currentArea == pane.fullArea && otherRootVacant -> currentArea
        else -> error("Пикер больше не находится в рабочем окне")
    }

    // region the world's reads the operations ask for

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

    private companion object {
        const val EXPAND_PRIMARY_MODE = 101
        const val EXPAND_SECONDARY_MODE = 102
        const val LAUNCH_MODE_SINGLE_TASK = 2
        const val DIVIDER_RECONCILE_SETTLE_MS = 1_500L

        const val APP_LAUNCH_SETTLE_MS = 250L
        /** Only the single-pane selection keeps two samples; a built scene ends in the
         *  operation's own whole-scene read-back instead (правка A4). */
        const val APP_PLACEMENT_STABLE_SAMPLES = 2
        /** `mFocusedApp=ActivityRecord{a81ee00 u0 dev.denza.apps/.MainActivity} t332}` */
        val FOCUSED_TASK_PATTERN = Regex("mFocusedApp=ActivityRecord\\{[^}]*\\}\\s+t([0-9]+)\\}")

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
