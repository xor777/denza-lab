package dev.denza.apps.feature.split

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mandatory coordinator skeleton K1-K15 of appendix B.3, plus the scenarios of section 11 a
 * unit test can honestly close.
 *
 * This is the layer the previous suite did not have at all, and it is the shop window the
 * Definition of Done is checked against: every K of appendix B.3 exists here under its exact name,
 * even where the same property is also proven one layer down in [SplitActorTest] or
 * [SplitOperationRunnerTest] - because what the contract owes the user is the end-to-end
 * consequence, and the only oracle for that is what reached the car.
 *
 * The harness is a fake car ([SplitCarFixture]): the shared `FakeShell` firmware, a hand-driven
 * clock, an atomic store, a counted overlay lease. Nothing below restates the code under test;
 * every assertion reads the ordered command journal, the per-operation shell sessions, the durable
 * snapshot or the notice the user would have seen.
 */
class SplitScenarioTest {

    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun stop() {
        cars.forEach(SplitCarFixture::close)
    }

    // region K1-K4 - one tap, one operation, one window

    @Test
    fun homeCancelsSceneWorkButNeverAUserRequestedOpenWithinBudget() {
        // K1 в редакции §4 п.2 (2ef2e67), сценарий §11.7. Три части одной нормы:
        //  - Home отменяет работу над сценой, от которой пользователь ушёл (SELECT);
        //  - только что запрошенный OPEN он не отменяет: запуск идёт с Home, и Home на экране -
        //    не новость про него, а экран, с которого его и запросили (1.3.9);
        //  - сверх бюджета OPEN убивает его собственный дедлайн, и поверх Home позже ничего
        //    не появляется (1.3.8).

        // 1. Очередь: Home обгоняет и снимает работу над сценой.
        val scene = car(FakeShell().apply { liveProductScene() })
        val living = scene.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        living.initialize {}
        living.openPickerSession()
        scene.barrier()
        val committed = scene.store.commits
        scene.clearCommands()
        val selected = Collections.synchronizedList(mutableListOf<String?>())

        val hold = scene.hold()
        living.selectApp(PRIMARY_PICKER_TASK, WAZE, selected::add)
        living.homeVisible()
        hold.release()
        scene.barrier()

        assertFalse(
            "the selection the user walked away from never reached the car",
            scene.commands().any { it.startsWith("am start ") },
        )
        assertEquals("и это не ошибка: пользователь сам нажал Home", listOf<String?>(null), selected.toList())
        assertEquals(committed, scene.store.commits)
        assertEquals(PICKER_PAIR, scene.store.load().slots)

        // 2. Тот же Home поверх начатого OPEN: сцена, которую заказали, появляется.
        val opening = car(FakeShell())
        val core = opening.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        opening.shells.blockAt(GATE_OPEN)
        core.openPickerSession(results::add)
        assertTrue(opening.shells.awaitBlocked())
        core.homeVisible()
        // И даже Home, дошедший до актора мимо координатора, не отбирает у него право мутировать.
        val home = homeThatReachedTheActor(opening)
        opening.shells.release()
        opening.barrier()

        assertEquals("никто не отменил заказанный запуск", listOf<String?>(null), results.toList())
        assertEquals(SplitOutcome.Committed, home.outcome)
        assertEquals("сцена построена и записана одним коммитом", 1, opening.store.commits)
        assertEquals(PICKER_PAIR, opening.store.load().slots)
        assertEquals(1, opening.fake.taskCount(PRIMARY_ROOT))
        assertEquals(1, opening.fake.taskCount(SECONDARY_ROOT))
        assertEquals(1, opening.overlay.begun.get())
        assertEquals("окно ожидания снято ровно один раз", 1, opening.overlay.closed())

        // 3. Сверх бюджета: дедлайн, и после него на экране не появляется ничего.
        val expiring = car(FakeShell())
        val expired = expiring.core(SplitDurable(enabled = true))
        expired.initialize {}
        val lateResults = Collections.synchronizedList(mutableListOf<String?>())

        expiring.shells.blockAt(GATE_OPEN)
        expired.openPickerSession(lateResults::add)
        assertTrue(expiring.shells.awaitBlocked())
        expiring.clock.advance(PAST_EVERY_BUDGET_MS)
        expiring.shells.release()
        expiring.barrier()

        assertEquals(
            "просроченный запуск говорит пользователю, что произошло (1.3.8, U5)",
            listOf("${SplitCoordinatorCore.OPEN_FAILURE}: превышено время ожидания. Попробуйте ещё раз"),
            lateResults.toList(),
        )
        assertEquals("и не пишет ничего", 0, expiring.store.commits)
        assertEquals("ни одна панель не получила позднего окна", 0, expiring.fake.taskCount(PRIMARY_ROOT))
        assertEquals(0, expiring.fake.taskCount(SECONDARY_ROOT))
        assertFalse("gate, который успели открыть, закрыт откатом", expiring.fake.isGateOpen())
        assertEquals(1, expiring.overlay.closed())
    }

    @Test
    fun toggleOffDuringOpenFencesEveryLateCommand() {
        // K2, сценарий §11.8: выключение посреди запуска - ноль поздних мутаций и записей
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.openPickerSession(results::add)
        assertTrue(car.shells.awaitBlocked())
        core.setEnabled(false)
        val before = car.commands().size
        car.shells.release()
        car.barrier()

        assertEquals("only the command the toggle raced was ever sent", before + 1, car.commands().size)
        assertEquals("and none of it moved a task or a gate", emptyList<String>(), car.mutations())
        assertEquals("the toggle itself is the only durable change", 1, car.store.commits)
        assertFalse(car.store.load().enabled)
        assertEquals(
            "a cancelled open never touches the remembered selection",
            SplitDurable().slots,
            car.store.load().slots,
        )
        assertEquals(1, car.overlay.closed())
        assertEquals(listOf<String?>(null), results.toList())
    }

    @Test
    fun overlayDeadlineCancelsOperationNotOnlyWindow() {
        // K3, сценарий §11.10: дедлайн отменяет операцию, а не только снимает окно (1.3.8)
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.openPickerSession(results::add)
        assertTrue(car.shells.awaitBlocked())
        val before = car.commands().size
        // Один шаг часов дальше бюджета `OPEN`: таймер актора взводится при submit.
        car.clock.advance(PAST_EVERY_BUDGET_MS)
        car.shells.release()
        car.barrier()

        assertEquals("nothing of the expired operation reached the car", before + 1, car.commands().size)
        assertEquals(0, car.store.commits)
        assertEquals("the window is released the moment the deadline fires", 1, car.overlay.closed())
        assertEquals(
            "and the user is told what happened and what to do (U5)",
            listOf("${SplitCoordinatorCore.OPEN_FAILURE}: превышено время ожидания. Попробуйте ещё раз"),
            results.toList(),
        )
    }

    @Test
    fun twoLauncherTapsShareOneOperationAndOneLease() {
        // K4, сценарий §11.11, контракт 1.3.7
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.openPickerSession(results::add)
        assertTrue(car.shells.awaitBlocked())
        core.openPickerSession(results::add)
        car.shells.release()
        car.barrier()

        assertEquals("one waiting window for both taps", 1, car.overlay.begun.get())
        assertEquals("one shell session, so one launch sequence", 1, car.shells.opened.get())
        assertEquals("both callers observe the very same outcome", listOf(null, null), results.toList())
        assertEquals("one operation is one commit", 1, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
    }

    // endregion

    // region live red P1.2 - the reentrant lease, the launch echo and the loop they made

    @Test
    fun reentrantLeaseRebindCannotKillTheOpenItServes() {
        // live red P1.2, триггер 1: OPEN первым делом берёт picker-access; со стёртыми prefs это
        // полный ре-байнд сервиса, а свежий сервис отчитывается координатору синхронно, изнутри
        // той самой операции, которая его и подняла
        val car = car(FakeShell())
        var built: SplitCoordinatorCore? = null
        val observer = ReentrantPickerAccessLease(
            onServiceConnected = { ReboundObserver.report(built!!, stockPickerVisible = false) },
        )
        val core = car.core(
            SplitDurable(enabled = true),
            leases = listOf(FakeLease(SplitLeaseKind.RESIZEABILITY, RESIZE_KEY), observer),
        )
        built = core
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(GATE_OPEN)
        core.openPickerSession(results::add)
        assertTrue(car.shells.awaitBlocked())
        assertEquals("наблюдатель поднят внутри операции", 1, observer.enables.get())
        assertTrue(
            "и это именно ре-байнд сервиса, а не запись флага",
            car.commands().any { it.startsWith("settings put secure ") },
        )
        // И даже Home, дошедший до актора мимо координатора, не отнимает право мутировать (§4 п.2).
        val home = homeThatReachedTheActor(car)
        car.shells.release()
        car.barrier()

        assertEquals("тап довёл сцену до конца", listOf<String?>(null), results.toList())
        assertEquals(SplitOutcome.Committed, home.outcome)
        assertEquals(
            "ноль отмен: единственный терминал операции - committed",
            listOf("${SplitCoordinatorCore.OPEN_LABEL} outcome=committed reason=-"),
            openTerminals(car),
        )
        assertEquals(
            "переподключение наблюдателя вообще не заявляет Home (инвариант 8)",
            emptyList<String>(),
            car.diagnostics.filter { it.contains(SplitCoordinatorCore.HOME_LABEL) },
        )
        assertEquals("сцена построена и записана одним коммитом", 1, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertEquals(1, car.fake.taskCount(PRIMARY_ROOT))
        assertEquals(1, car.fake.taskCount(SECONDARY_ROOT))
        assertTrue("наблюдатель остался нашим", observer.isOwned())
        assertEquals(1, car.overlay.closed())
    }

    @Test
    fun launchEchoHomeHintIsDroppedWhileOpenRuns() {
        // live red P1.2, триггер 6: кнопка живёт на Home (com.byd.mycar), NoDisplay-entry апп-центр
        // не убирает, и оконное эхо запуска приходит спустя сотни миллисекунд после тапа. Это тот
        // же экран, с которого запускали, а не новость про него (1.3.9, §4 п.2)
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(GATE_OPEN)
        core.openPickerSession(results::add)
        assertTrue(car.shells.awaitBlocked())
        repeat(ECHOES) { core.homeVisible() }
        val home = homeThatReachedTheActor(car)
        car.shells.release()
        car.barrier()

        assertEquals(
            "каждое эхо дропнуто одной строкой и ни одной операцией",
            ECHOES,
            car.diagnostics.count { it.startsWith(HOME_HINT_DROPPED) },
        )
        assertEquals(
            "и под подсказку не открыта ни одна сессия - работал только сам запуск",
            1,
            car.shells.opened.get(),
        )
        assertEquals(SplitOutcome.Committed, home.outcome)
        assertEquals("запуск дошёл до сцены", listOf<String?>(null), results.toList())
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertEquals(1, car.store.commits)
        assertEquals(1, car.fake.taskCount(PRIMARY_ROOT))
        assertEquals(1, car.fake.taskCount(SECONDARY_ROOT))
    }

    @Test
    fun openFailureKeepsPickerAccessLease() {
        // live red P1.2, петля 5: провалившийся OPEN отпускал наблюдателя, и следующий тап начинал
        // с полного ре-байнда - то есть с той же воронки. Инфраструктурный lease переживает провал
        val car = car(
            FakeShell().apply {
                liveProductScene()
                setGlobal(RESIZE_KEY, "0")
                // Сцена жива, но накрыта чужим полноэкранным окном: открытие её поднимает.
                area = 4
            },
        )
        var built: SplitCoordinatorCore? = null
        val observer = ReentrantPickerAccessLease(
            onServiceConnected = { ReboundObserver.report(built!!, stockPickerVisible = false) },
        )
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            leases = listOf(FakeLease(SplitLeaseKind.RESIZEABILITY, RESIZE_KEY), observer),
        )
        built = core
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.failOn("am task focus $PRIMARY_PICKER_TASK")
        core.openPickerSession(results::add)
        car.barrier()

        assertEquals(listOf(SplitCoordinatorCore.OPEN_FAILURE), results.toList())
        assertEquals(
            "настройка, которую сцена вытеснила, возвращена",
            listOf("settings put global $RESIZE_KEY 0"),
            car.commands().filter { it.startsWith("settings put global ") }.takeLast(1),
        )
        assertTrue("а наблюдатель остался нашим", observer.isOwned())
        assertEquals("его никто даже не пробовал отпустить", 0, observer.restores.get())

        // Второй тап застаёт наблюдателя поднятым: цикла «ре-байнд на каждый тап» больше нет.
        car.clearCommands()
        core.openPickerSession()
        car.barrier()

        assertEquals(2, observer.enables.get())
        assertEquals(
            "ре-байнда сервиса во втором тапе нет вовсе",
            emptyList<String>(),
            car.commands().filter { it.startsWith("settings put secure ") },
        )
        assertTrue(observer.isOwned())

        // Отпускает его ровно одно событие - выключение тумблера (1.2).
        core.setEnabled(false)
        car.barrier()

        assertFalse("тумблер выключен - наблюдатель отпущен", observer.isOwned())
        assertEquals(1, observer.restores.get())
    }

    @Test
    fun userOpenTerminalsAreAlwaysLogged() {
        // U5, U6: у тапа не бывает молчаливого исхода. Каждый терминал OPEN - ровно одна строка
        val committed = car(FakeShell())
        val succeeding = committed.core(SplitDurable(enabled = true))
        succeeding.initialize {}
        succeeding.openPickerSession()
        // Повторный тап присоединяется к живой операции: один терминал, одна строка (1.3.7).
        succeeding.openPickerSession()
        committed.barrier()
        assertEquals(
            listOf("${SplitCoordinatorCore.OPEN_LABEL} outcome=committed reason=-"),
            openTerminals(committed),
        )

        val expiring = car(FakeShell())
        val expired = expiring.core(SplitDurable(enabled = true))
        expired.initialize {}
        expiring.shells.blockAt(GATE_OPEN)
        expired.openPickerSession()
        assertTrue(expiring.shells.awaitBlocked())
        expiring.clock.advance(PAST_EVERY_BUDGET_MS)
        expiring.shells.release()
        expiring.barrier()
        assertEquals(
            listOf(
                "${SplitCoordinatorCore.OPEN_LABEL} outcome=cancelled " +
                    "reason=${SplitCancelReason.DEADLINE}",
            ),
            openTerminals(expiring),
        )

        val broken = car(FakeShell())
        val failing = broken.core(SplitDurable(enabled = true))
        failing.initialize {}
        broken.shells.failOn(GATE_OPEN)
        failing.openPickerSession()
        broken.barrier()
        assertEquals(
            listOf(
                "${SplitCoordinatorCore.OPEN_LABEL} outcome=rolled-back reason=$SPLIT_ADB_DROPPED",
            ),
            openTerminals(broken),
        )

        val switched = car(FakeShell())
        val toggled = switched.core(SplitDurable(enabled = true))
        toggled.initialize {}
        switched.shells.blockAt(GATE_OPEN)
        toggled.openPickerSession()
        assertTrue(switched.shells.awaitBlocked())
        toggled.setEnabled(false)
        switched.shells.release()
        switched.barrier()
        assertEquals(
            listOf(
                "${SplitCoordinatorCore.OPEN_LABEL} outcome=cancelled " +
                    "reason=${SplitCancelReason.DISABLE}",
            ),
            openTerminals(switched),
        )
        assertTrue(
            "и выключение тумблера тоже отчитывается о себе",
            switched.diagnostics.any {
                it.startsWith("${SplitCoordinatorCore.DISABLE_LABEL} outcome=")
            },
        )
    }

    /**
     * Правка W6 (диагноз v21 Д4-Ф1): shell-зеркало удалено, канал истины фаз - ринг
     * support-экрана (§12.1). Что операция пишет фазы в ринг, по-прежнему закреплено здесь.
     */
    @Test
    fun theRingCarriesTheStepTimingsOfAnOpen() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        assertTrue(
            "фазовые метки открытия дошли до ринга",
            car.diagnostics.any { it.startsWith("${SplitCoordinatorCore.OPEN_LABEL} +") },
        )
        assertFalse(
            "и ни одна команда зеркала не ушла в машину",
            car.commands().any { it.startsWith("log -t ") },
        )
    }

    /**
     * Contract 5, to 1.12: every write of the firmware-global resizeability setting is recorded.
     *
     * Acceptance v17 raised defect 11 - "the product silently changes `force_resizable_activities`"
     * - out of nothing but the product's silence: the lease was taken and given back exactly as the
     * contract says, and no line anywhere said so. The taking is one compound statement now, which
     * is precisely the shape a naive "starts with settings put" filter would miss.
     */
    @Test
    fun everyWriteOfTheBorrowedResizeabilitySettingIsRecorded() {
        val car = car(FakeShell().apply { setGlobal(RESIZE_KEY, "0") })
        val core = car.core(SplitDurable(enabled = true), leases = listOf(ResizeabilityLease()))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        val taken = car.diagnostics.filter { it.startsWith(RESIZEABILITY_LEASE_LINE) }
        assertEquals("одна строка на взятие аренды", 1, taken.size)
        assertTrue(
            "и она называет и настройку, и что с ней сделали",
            taken.single().contains("settings put global $RESIZE_KEY 1"),
        )

        core.setEnabled(false)
        car.barrier()

        val all = car.diagnostics.filter { it.startsWith(RESIZEABILITY_LEASE_LINE) }
        assertEquals("и одна на возврат", 2, all.size)
        assertTrue(all.last().contains("settings put global $RESIZE_KEY 0"))
        assertEquals("а вернули ровно то, что застали", "0", car.fake.globalValue(RESIZE_KEY))
    }

    // endregion

    // region K5-K6 - the passive storm

    @Test
    fun hundredWindowsHintsDoNotDelaySelect() {
        // K5, сценарий §11.22, §4 (ред. 2026-08-24): выбор не ждёт очередь из ста reconcile -
        // сабмит явного тапа отменяет пассивный шум, и проигравшее событие не воспроизводится
        // из очереди: следующая сверка придёт со свежим поводом и перечитает мир сама.
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        val hold = car.hold()
        car.clearCommands()
        repeat(100) { core.dividerResized() }
        core.selectApp(SECONDARY_PICKER_TASK, MUSIC)
        hold.release()
        car.barrier()

        val sessions = car.sessions()
        assertEquals("сто подсказок вытеснены тапом: работает только он", 1, sessions.size)
        assertTrue(
            "and the explicit tap is served at once",
            sessions.single().any { it.startsWith("am start ") && it.contains(MUSIC) },
        )
        assertEquals(SplitSlot.App(MUSIC), car.store.load().slot(SplitPane.SECONDARY))
    }

    /**
     * Правка W2 (§4 ред. 2026-08-24; live v20 D1): оконные эхо первого тапа жеста возврата
     * рождали reconcile, и OPEN второго тапа стоял в очереди ~2 с за его слепым settle. Сабмит
     * OPEN обязан отнять у in-flight сверки токен тем же механизмом, что Home: после сабмита ни
     * одной её команды, воркер достаётся открытию.
     */
    @Test
    fun aUserOpenPreemptsTheInFlightReconcileInsteadOfQueuingBehindIt() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()

        // Сверка в полёте, запаркованная на своей первой команде.
        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.dividerResized()
        assertTrue(car.shells.awaitBlocked())

        core.openPickerSession()
        car.shells.release()
        car.barrier()

        val sessions = car.sessions()
        assertEquals("сессия сверки и сессия открытия", 2, sessions.size)
        assertEquals(
            "после сабмита OPEN сверка не отправила больше ни одной команды",
            listOf(SPLIT_AREA_QUERY),
            sessions.first(),
        )
        assertTrue(
            "и открытие получило воркер сразу после её fence",
            sessions.last().isNotEmpty(),
        )
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertEquals(APP_PAIR, car.store.load().slots)
    }

    @Test
    fun foreignWindowsChangedNeverOpensShell() {
        // K6, сценарий §11.22: подсказка, которая не может быть нашей, не открывает даже сессию
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        repeat(100) { core.dividerResized() }
        car.barrier()

        assertEquals("an ambient hint alone is no right to go looking", 0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertEquals(0, car.store.commits)
    }

    /**
     * 1.13: what the user is made to wait for before anything happens at all.
     *
     * An open used to read the whole task topology three times and the two panel roots six times
     * before its first command reached the firmware - `prepare` built one session and `apply` built
     * another, and `observedTaskIds` asked each pane separately. They all describe the same instant
     * and are now read once. The leases of a steady-state open only read their settings, so nothing
     * between the planning read and the recipe can move a task.
     */
    @Test
    fun oneOpenReadsTheWholeTopologyOnceBeforeItsFirstMutation() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        car.clearCommands()

        core.openPickerSession()
        car.barrier()

        val prologue = car.readsBeforeFirstMutation()
        assertEquals(
            "one `am stack list` answers planning, ownership bookkeeping and the recipe alike",
            1,
            prologue.count { it == "am stack list" },
        )
        assertEquals(
            "and the two panel roots are asked for once, not once per reader",
            2,
            prologue.count { it.startsWith("service call activity_task 118 ") },
        )
    }

    /**
     * Правка W10 (1.13, урок красной ветки v20 P1.2): секунды сборки обязаны раскладываться по
     * логу без гаданий. Каждая фаза buildScene оставляет метку с временем ожидания пользователя,
     * тем же счётом от тапа, что и остальные марки операции.
     */
    @Test
    fun anOpenBuildLogsItsPhases() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        val phaseMark = Regex(
            "^open \\+\\d+ms (roots-started|roots-placed|apps-launched|scene-normalized|placement-confirmed)$",
        )
        assertEquals(
            "каждая фаза сборки оставила ровно одну метку, в порядке рецепта",
            listOf(
                "roots-started",
                "roots-placed",
                "apps-launched",
                "scene-normalized",
                "placement-confirmed",
            ),
            car.diagnostics.mapNotNull { line -> phaseMark.find(line)?.groupValues?.get(1) },
        )
    }

    /**
     * Анти-регрессия на доминанту скорости (правка A, измерение v19): один `am stack list` на
     * этой машине стоит 250-300 мс, и пересборка сцены при живых задачах доходила до ~13 таких
     * чтений. Бюджет камеры - восемь: scene-read отказа, before сборки, два ожидания пикеров,
     * одно групповое ожидание запусков, одно подтверждение промоутов, одно подтверждение
     * пакетного ресайза (его же читает постусловие) и финальный read-back операции. Каждый
     * возврат к чтению-на-объект, второму сэмплу или ожиданию-на-панель пробивает его.
     */
    @Test
    fun rebuildingOverLivingTasksStaysWithinTheSnapshotBudget() {
        val car = car(
            FakeShell().apply {
                addTask(PRIMARY_ROOT, PRIMARY_APP_TASK, NAVIGATOR, "$NAVIGATOR.MainActivity")
                addTask(SECONDARY_ROOT, SECONDARY_APP_TASK, MUSIC, "$MUSIC.MainActivity")
                // Возвращённая прошивкой задача не принимает границы панели сама - как на машине,
                // где ресайз пересборки реален, а не нулевой.
                preserveBoundsOnShellMove = true
                area = 0
            },
        )
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        val stackReads = car.commands().count { it == "am stack list" }
        assertTrue(
            "пересборка при живых задачах прочитала топологию $stackReads раз (бюджет 8)",
            stackReads <= 8,
        )
        assertTrue("и не перезапустила живое (U2)", car.fake.hasTask(PRIMARY_APP_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_APP_TASK))
    }

    /** But a settle pause is a promise that the car moved, so what follows it is read again. */
    @Test
    fun aSettlePauseEndsTheSharedTopologyRead() {
        val topology = SplitTopologyCache()
        var reads = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                if (command == "am stack list") reads += 1
                FakeShell().apply { liveProductScene() }.shell(command)
            },
            apkPath = SPLIT_APK_PATH,
            settle = {},
            topology = topology,
        )

        session.observePane(SplitPane.PRIMARY, PICKER_COMPONENTS)
        session.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)
        assertEquals(1, reads)

        topology.invalidate()
        session.observePane(SplitPane.PRIMARY, PICKER_COMPONENTS)

        assertEquals(2, reads)
    }

    // endregion

    // region K7 - the disabled product is invisible

    @Test
    fun disabledInitializePerformsZeroShellCommands() {
        // K7, сценарий §11.14, инвариант 1: выключенный продукт молчит на старте процесса
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = false))

        core.initialize {}
        car.barrier()

        assertEquals("no shell session is opened at all", 0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertTrue("someone else's split is untouched", car.fake.isGateOpen())
        assertEquals(3, car.fake.area)
    }

    // endregion

    // region K8-K9 - the transactional half

    @Test
    fun adbFailureMidOpenRollsBackToStartingScene() {
        // K8, сценарий §11.10: журнал отыгрывается назад, в обратном порядке, до первой мутации
        val car = car(
            FakeShell().apply {
                liveProductScene()
                setGlobal(RESIZE_KEY, "0")
                setGlobal(ACCESS_KEY, "0")
                // Сцена жива, но накрыта чужим полноэкранным окном: открытие её поднимает.
                area = 4
            },
        )
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            leases = listOf(
                FakeLease(SplitLeaseKind.RESIZEABILITY, RESIZE_KEY),
                FakeLease(SplitLeaseKind.PICKER_ACCESS, ACCESS_KEY),
            ),
        )
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.failOn("am task focus $PRIMARY_PICKER_TASK")
        core.openPickerSession(results::add)
        car.barrier()

        assertEquals(
            "the setting the scene displaced is given back exactly as it was found",
            listOf("settings put global $RESIZE_KEY 0"),
            car.commands().filter { it.startsWith("settings put global ") }.takeLast(1),
        )
        assertFalse(
            "а инфраструктурный наблюдатель не отпущен: его отпускает только DISABLE",
            car.commands().contains("settings put global $ACCESS_KEY 0"),
        )
        assertEquals("a rolled back operation persists nothing", 0, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertTrue("and the starting scene is exactly where it was", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals(4, car.fake.area)
        assertEquals(listOf(SplitCoordinatorCore.OPEN_FAILURE), results.toList())

        // Вторая половина K8: сбой уже ПОСЛЕ созданных задач. `OpenOperation` намеренно не падает
        // от неудавшегося восстановления - оно деградирует в уведомление (1.3.2) - поэтому право
        // мутировать теряется единственным способом, каким оно теряется у начатого пользователем
        // запуска после §4 п.2: истёк его собственный бюджет (1.3.8). Отыгрывается тот же журнал.
        // Навигатор уже работает - его задачу восстановление переиспользует, а не создаёт.
        val building = car(FakeShell().apply { addTask(FULL_ROOT, LIVING_APP, NAVIGATOR, "$NAVIGATOR.MainActivity") })
        val rebuilt = building.core(SplitDurable(enabled = true, slots = APP_PAIR))
        rebuilt.initialize {}
        val rebuiltResults = Collections.synchronizedList(mutableListOf<String?>())

        // Оба пикера уже созданы, навигатор уже запрошен у прошивки - и она вернула его же задачу.
        building.shells.blockAt(appLaunch("START_IVI_SECOND", MUSIC))
        rebuilt.openPickerSession(rebuiltResults::add)
        assertTrue(building.shells.awaitBlocked())
        val createdLast = building.fake.topTaskId(SECONDARY_ROOT)!!
        val created = building.fake.taskIds(PRIMARY_ROOT) + building.fake.taskIds(SECONDARY_ROOT)
        assertEquals("открытие успело создать два пикера", 2, created.size)
        assertTrue(
            "и запуск навигатора не создал второй копии - это его прежняя задача",
            building.fake.hasTask(LIVING_APP),
        )
        assertEquals(LIVING_APP, building.fake.taskIds(FULL_ROOT).single())
        assertTrue("и gate открыли именно мы", building.gateLease.isOwned())
        building.clearCommands()

        building.clock.advance(PAST_EVERY_BUDGET_MS)
        building.shells.release()
        building.barrier()

        val removed = building.commands().mapNotNull { command ->
            command.substringAfter(" remove-task ", "").substringBefore(' ').toIntOrNull()
        }
        assertEquals("откат снимает ровно созданное - и ничего сверх", created.toSet(), removed.toSet())
        assertEquals("самое новое уходит первым", createdLast, removed.first())
        assertEquals(2, removed.size)
        assertTrue(
            "U2: приложение, которое операция лишь переиспользовала, откат не трогает",
            building.fake.hasTask(LIVING_APP),
        )
        assertEquals("панели пусты, как до тапа", 0, building.fake.taskCount(PRIMARY_ROOT))
        assertEquals(0, building.fake.taskCount(SECONDARY_ROOT))
        assertFalse("gate, который открыли мы, закрыт", building.fake.isGateOpen())
        assertFalse(building.gateLease.isOwned())
        assertEquals("отменённое открытие не пишет ничего", 0, building.store.commits)
        assertEquals(APP_PAIR, building.store.load().slots)
        assertEquals(
            "а пользователю сказано, что ожидание истекло (1.3.8, U5)",
            listOf("${SplitCoordinatorCore.OPEN_FAILURE}: превышено время ожидания. Попробуйте ещё раз"),
            rebuiltResults.toList(),
        )
    }

    /**
     * The other half of the eleven seconds: a restore used to be two whole selections in a row.
     *
     * Each of them cleared its pane, waited for its own picker to become the root top, launched,
     * and then confirmed *that one pane* - the second one not even started yet. Both launches now
     * go out together and one postcondition covers the pair.
     */
    @Test
    fun restoringAPairLaunchesBothPanesBeforeItWaitsForEither() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        car.clearCommands()

        core.openPickerSession()
        car.barrier()

        val launches = car.commands().withIndex().filter { (_, command) ->
            command.startsWith("am start ") && !command.contains(SPLIT_PICKER_ACTIVITY)
        }
        assertEquals("одно приложение - один запуск", 2, launches.size)
        assertEquals(
            "и оба уходят подряд: между ними продукт ничего не ждёт и ни о чём не спрашивает",
            1,
            launches.last().index - launches.first().index,
        )
        assertFalse(
            "восстановление не расчищает панель и не ждёт, пока её пикер станет верхним",
            car.commands().any { it.contains(" remove-task ") },
        )
        assertEquals(
            "и постусловие одно на обе панели: один полный сэмпл, затем финальный read-back " +
                "операции - вторая независимая проверка той же сцены (правка A4, контракт 7.7)",
            2,
            car.commands().drop(launches.last().index).count { it == SPLIT_AREA_QUERY },
        )
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals("сцена поднялась и это не ошибка", SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    /**
     * Contract 7.7, and the "picker over an application" defect of acceptance v17.
     *
     * The recipe's own postcondition is measured while the scene is still being built; the operation
     * commits only after one more read of the whole thing. A pane that lost its app in between makes
     * the open roll back rather than record a scene that is not there (invariant 9).
     */
    @Test
    fun anOpenCommitsOnlyWhatTheWholeSceneStillReadsBack() {
        val car = car(FakeShell())
        val results = Collections.synchronizedList(mutableListOf<String?>())
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                    SplitPane.SECONDARY to SplitSlot.Picker,
                ),
            ),
        ) { line ->
            // Ровно между постусловием рецепта и финальным read-back панель теряет приложение.
            if (line.contains(SCENE_BUILT)) {
                car.fake.removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
            }
        }
        core.initialize {}

        core.openPickerSession(results::add)
        car.barrier()

        assertEquals("частичная сцена не объявляется успехом", 0, car.store.commits)
        assertEquals(listOf(SplitCoordinatorCore.OPEN_FAILURE), results.toList())
        assertEquals(
            "и выбор панели остаётся тем, что помнил продукт (1.3.2)",
            SplitSlot.App(NAVIGATOR),
            car.store.load().slot(SplitPane.PRIMARY),
        )
        assertEquals("а созданные пикеры сняты откатом", 0, car.fake.taskCount(PRIMARY_ROOT))
        assertEquals(0, car.fake.taskCount(SECONDARY_ROOT))
    }

    /**
     * Contract 1.9.4 and scenario §11.2, the whole point of правка E.
     *
     * Home covers a scene; it does not end one (invariant 5). Acceptance v17 refused every adoption
     * because the firmware area under Home is 0, rebuilt the pair from scratch, and the music the
     * user had left playing restarted every single time they came back to it.
     */
    @Test
    fun openingAfterHomeRaisesTheSameLiveSceneInsteadOfRebuildingIt() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        // Первый тап строит сцену: с этого момента процесс знает её точные задачи, а gate - наш.
        core.openPickerSession()
        car.barrier()
        val living = car.fake.taskIds(PRIMARY_ROOT) + car.fake.taskIds(SECONDARY_ROOT)
        assertEquals(4, living.size)
        car.fake.area = 0
        core.homeVisible()
        car.barrier()
        assertFalse("Home приостановил наш gate (1.9.1)", car.fake.isGateOpen())
        assertTrue("но аренда осталась нашей", car.gateLease.isOwned())
        car.clearCommands()

        core.openPickerSession()
        car.barrier()

        assertFalse(
            "ни одного запуска: приложения те же самые и не перезапускались (U2)",
            car.commands().any { it.startsWith("am start ") },
        )
        assertTrue(
            "подъём собственной сцены - возобновление сессии, и gate снова открыт (к 1.12)",
            car.fake.isGateOpen(),
        )
        assertFalse(car.commands().any { it.contains(" remove-task ") })
        assertEquals(
            "те же самые задачи, все четыре",
            living,
            car.fake.taskIds(PRIMARY_ROOT) + car.fake.taskIds(SECONDARY_ROOT),
        )
        assertEquals("сцена снова на экране", 3, car.fake.area)
        assertTrue(
            car.diagnostics.any { it.contains("scene-read: adoptable") },
        )
    }

    /**
     * Правка B1 (волна 4), машинная правда ground-v18 A и живые S8/S21: после Home прошивка не
     * уничтожает ничего - но может выбросить содержимое одной панели из панельных root'ов, оставив
     * задачи живыми с сохранёнными панельными бордерами. Адопция тогда честно отказывает, и
     * пересборка запускала новый пикер и гнала полный restore-цикл (~7 с, с риском перезапуска).
     *
     * Сборка теперь возвращает выживших РЕПАРЕНТОМ: пикер - по точному компоненту и панельным
     * бордерам, приложение - по точной identity из живой сцены (task id + пакет + границы, как
     * resolveExpectedCoveredApp); поднимает сцену командой reveal. Ни одного запуска.
     */
    @Test
    fun reopeningAfterHomeReassemblesTheSurvivorsWithoutASingleLaunch() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val pickerP = car.fake.taskIds(PRIMARY_ROOT).first()
        val appP = car.fake.taskIds(PRIMARY_ROOT).last()
        val pickerS = car.fake.taskIds(SECONDARY_ROOT).first()
        val appS = car.fake.taskIds(SECONDARY_ROOT).last()

        car.fake.area = 0
        core.homeVisible()
        car.barrier()

        // Что сделала прошивка (ground-v18 A): одна панель выброшена в Tda целиком с бордерами,
        // во второй пикер оказался поверх накрытого приложения.
        car.fake.detachTask(pickerP)
        car.fake.detachTask(appP)
        car.fake.promoteActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)
        car.clearCommands()

        core.openPickerSession()
        car.barrier()

        assertFalse(
            "ни одного запуска: выжившие возвращены, а не пересозданы (U2)",
            car.commands().any { it.startsWith("am start ") },
        )
        assertEquals("та же задача приложения в своей панели", PRIMARY_ROOT, car.fake.taskRoot(appP))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(appS))
        assertEquals("и тот же пикер вернулся в свой контейнер", PRIMARY_ROOT, car.fake.taskRoot(pickerP))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(pickerS))
        assertTrue(
            "выброшенное возвращено live-proven командами: reparent и focus",
            car.commands().any { it.startsWith("am stack move-task $appP ") },
        )
        assertTrue(car.commands().any { it == "am task focus $appP" })
        assertEquals("сцена поднята", 3, car.fake.area)
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertFalse("и ничего не удалялось", car.commands().any { it.contains(" remove-task ") })
    }

    /**
     * Правка B1, инвариант 4: без точной identity реюза приложений нет. Процесс, который ничего
     * не помнит, находит выброшенные задачи с панельными бордерами - и всё равно идёт через
     * честный запуск: пусть прошивка сама решит, чью задачу отдать. Пикеры - другое дело: их
     * identity и есть наш компонент (инвариант 3), выжившие возвращаются в свои панели по
     * бордерам, а не пересоздаются.
     */
    @Test
    fun aProcessThatRemembersNothingDoesNotGuessAtStrandedTasks() {
        val car = car(
            FakeShell().apply {
                liveProductScene(withApps = true)
                // Прошивка выбросила обе панели целиком, сохранив панельные бордеры (ground-v18).
                listOf(
                    PRIMARY_PICKER_TASK,
                    PRIMARY_APP_TASK,
                    SECONDARY_PICKER_TASK,
                    SECONDARY_APP_TASK,
                ).forEach(::detachTask)
                area = 0
            },
        )
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        assertEquals(
            "оба приложения приходят честным запуском, не переносом чужой догадки",
            2,
            car.commands().count { command ->
                command.startsWith("am start ") && !command.contains(SPLIT_PICKER_ACTIVITY)
            },
        )
        // Прошивка сама вправе отдать запуску ту же задачу - это её резолюция, не наша догадка.
        assertEquals(
            "а вот пикеры не пересозданы: их identity - наш собственный компонент",
            0,
            car.commands().count { command ->
                command.startsWith("am start ") && command.contains(SPLIT_PICKER_ACTIVITY)
            },
        )
        assertEquals(
            "и каждый вернулся в панель своих бордеров",
            PRIMARY_ROOT,
            car.fake.taskRoot(PRIMARY_PICKER_TASK),
        )
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(SECONDARY_PICKER_TASK))
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    /**
     * Invariant 4: what makes that adoption safe is the identity, and nothing durable remembers it.
     *
     * After the process died the scene on screen is still ours, but nothing proves which hidden
     * child of a covered root is on top - so the open refuses to adopt and goes the build path, and
     * the log says which pane and which predicate refused instead of "nothing of ours".
     */
    @Test
    fun aSceneNoProcessRemembersIsNotAdoptedFromUnderHome() {
        val car = car(
            FakeShell().apply {
                liveProductScene(withApps = true)
                area = 0
            },
        )
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        assertTrue(
            "марка называет панель и предикат, а не «ничего нашего»",
            car.diagnostics.any { line ->
                line.contains("scene-read: PRIMARY") && line.contains("area=0")
            },
        )
        assertFalse(
            "но и подстановки чужой догадки нет: усыновления не было",
            car.diagnostics.any { it.contains("scene-read: adoptable") },
        )
        // Пересборка при этом не разрушительна: приложения на местах, и их никто не перезапускал.
        assertTrue(car.fake.hasTask(PRIMARY_APP_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_APP_TASK))
        assertEquals(APP_PAIR, car.store.load().slots)
    }

    @Test
    fun persistedSnapshotIsOneAtomicCommit() {
        // K9, сценарий §11.15: успех - ровно одна запись; отказ записи - никакого частичного состояния
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}

        core.selectApp(PRIMARY_PICKER_TASK, NAVIGATOR)
        car.barrier()

        assertEquals("one operation, one write", 1, car.store.commits)
        val committed = car.store.load()
        assertEquals(SplitSlot.App(NAVIGATOR), committed.slot(SplitPane.PRIMARY))
        assertEquals(1L, committed.revision)

        car.store.accept = false
        val results = Collections.synchronizedList(mutableListOf<String?>())
        core.selectApp(SECONDARY_PICKER_TASK, MUSIC, results::add)
        car.barrier()

        assertEquals("the refused write was attempted exactly once", 2, car.store.commits)
        assertEquals("and left no half of itself behind", committed, car.store.load())
        assertEquals(listOf(SplitCoordinatorCore.SELECT_FAILURE), results.toList())
        assertTrue(car.notices.contains(SplitCoordinatorCore.SELECT_FAILURE))
    }

    // endregion

    // region K10 - a reboot has no numbers to trust

    @Test
    fun rebootEpochInvalidatesPersistedTaskIds() {
        // K10, сценарий §11.20: пережившее перезагрузку хранилище не может назвать ни один task id
        val before = car(
            FakeShell(firstTaskId = 7_000).apply {
                area = 3
                addTask(PRIMARY_ROOT, LIVE_PICKER_A, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
                addTask(PRIMARY_ROOT, LIVE_APP_A, NAVIGATOR, "$NAVIGATOR.MainActivity")
                addTask(SECONDARY_ROOT, LIVE_PICKER_B, SPLIT_HOST_PACKAGE, SECONDARY_PICKER_ACTIVITY)
                addTask(SECONDARY_ROOT, LIVE_APP_B, MUSIC, "$MUSIC.MainActivity")
            },
        )
        val living = before.core(SplitDurable(enabled = true, slots = APP_PAIR))
        living.initialize {}
        living.openPickerSession()
        before.barrier()
        assertEquals("the living process really did see this scene", APP_PAIR, before.store.load().slots)

        // Перезагрузка: переживает только хранилище. Процесс и сцена - новые, а старые номера
        // прошивка успела раздать чужим окнам: доверие к ним было бы чисткой чужих задач.
        val after = SplitCarFixture(
            fake = FakeShell(firstTaskId = 9_000).apply {
                area = 4
                addTask(FULL_ROOT, LIVE_PICKER_A, FOREIGN, "$FOREIGN.MainActivity")
                addTask(FULL_ROOT, LIVE_APP_A, FOREIGN, "$FOREIGN.PlayerActivity")
            },
            store = before.store,
        ).also(cars::add)
        val rebooted = after.core(after.store.load())
        rebooted.initialize {}
        after.barrier()
        assertEquals("nothing starts by itself after a reboot (U1)", emptyList<String>(), after.commands())

        rebooted.openPickerSession()
        after.barrier()

        val stale = setOf(LIVE_PICKER_A, LIVE_PICKER_B, LIVE_APP_A, LIVE_APP_B)
        val addressed = after.commands()
            .flatMap { command -> NUMBERS.findAll(command).map { it.value.toInt() } }
            .toSet()
        assertEquals(
            "not one command of the new process names a number of the old one",
            emptySet<Int>(),
            addressed intersect stale,
        )
        val encoded = PreferencesSplitStateStore.encode(after.store.load())
        assertFalse(
            "and there is nowhere in the durable snapshot to have kept one (инвариант 4)",
            stale.any { id -> encoded.contains(id.toString()) },
        )
        assertTrue("чужие окна со старыми номерами целы", after.fake.hasTask(LIVE_PICKER_A))
        assertEquals(FULL_ROOT, after.fake.taskRoot(LIVE_PICKER_A))
        assertTrue(after.fake.hasTask(LIVE_APP_A))
        assertEquals("and the remembered pair is restored by package alone", APP_PAIR, after.store.load().slots)
        assertTrue(after.fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertTrue(after.fake.hasPackage(SECONDARY_ROOT, MUSIC))
    }

    // endregion

    // region K11-K13 - late work never reaches the screen

    @Test
    fun homeDuringSelectKeepsLateTaskOffScreen() {
        // K11, сценарий §11.7: выбранное не выскакивает поверх Home, пикер снова интерактивен
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(GATE_OPEN)
        core.selectApp(PRIMARY_PICKER_TASK, WAZE, results::add)
        assertTrue(car.shells.awaitBlocked())
        core.homeVisible()
        car.shells.release()
        car.barrier()

        assertFalse(
            "the late launch never reached the car",
            car.commands().any { it.startsWith("am start ") },
        )
        assertEquals(0, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertEquals(
            "and its picker is still the top of its pane",
            PRIMARY_PICKER_ACTIVITY,
            car.fake.topActivity(PRIMARY_ROOT),
        )
        assertEquals(listOf<String?>(null), results.toList())
    }

    @Test
    fun edgeDragCancelProducesZeroDenzaMutations() {
        // K12, сценарий §11.4: отменённый edge-drag не оставляет ни мутации, ни окна, ни записи
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val overlays = car.overlay.begun.get()
        val commits = car.store.commits

        // Штатный список уже показан в панели - ровно то, что прикрепило бы наш пикер...
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.addTask(PRIMARY_ROOT, STOCK_TASK, STOCK_PICKER_PACKAGE, STOCK_PICKER_ACTIVITY)
        // ...но палец отпущен вне зоны: прошивка вернулась к одной панели, а не к балансу.
        car.fake.area = 1
        car.clearCommands()

        core.nativePickerVisible()
        car.barrier()

        assertTrue("the cancelled drag is observed at all", car.commands().isNotEmpty())
        assertEquals("but it changes precisely nothing", emptyList<String>(), car.mutations())
        assertTrue("чужое окно жеста не тронуто", car.fake.hasTask(STOCK_TASK))
        assertEquals("the edge path never owns a waiting window (1.3.1)", overlays, car.overlay.begun.get())
        assertEquals(commits, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
    }

    @Test
    fun lateEdgeCallbackFromOldGenerationDoesNotAttach() {
        // K13, сценарий §11.4: callback прошлого поколения не прикрепляет пикер в новую сцену
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        // Edge-drag показал штатный список в панели живой сцены (1.8.5).
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.addTask(PRIMARY_ROOT, STOCK_TASK, STOCK_PICKER_PACKAGE, STOCK_PICKER_ACTIVITY)
        car.clearCommands()

        car.shells.blockAt("dumpsys input")
        core.nativePickerVisible()
        assertTrue(car.shells.awaitBlocked())
        core.homeVisible()
        car.shells.release()
        car.barrier()

        assertFalse(
            "the cancelled generation attaches nothing",
            car.commands().any { it.startsWith("am start ") },
        )
        assertTrue("and it does not take the stock window either", car.fake.hasTask(STOCK_TASK))

        // Контроль: тот же путь без отмены действительно прикрепляет пикер.
        car.clearCommands()
        core.nativePickerVisible()
        car.barrier()

        assertTrue(
            "the very same path does attach when it keeps its token",
            car.commands().any { it.startsWith("am start ") && it.contains(SPLIT_PICKER_ACTIVITY) },
        )
        assertFalse(car.fake.hasTask(STOCK_TASK))
    }

    // endregion

    // region K14-K15 - a failure, and the navigation lease

    @Test
    fun selectFailureLeavesPickerInteractiveAndPairUnchanged() {
        // K14, сценарий §11.9: пикер остаётся интерактивным, пара не тронута, сообщение видно
        val car = car(FakeShell(directTargetLaunchSucceeds = false).apply { liveProductScene() })
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.Picker,
                    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
                ),
            ),
        )
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        core.selectApp(PRIMARY_PICKER_TASK, NAVIGATOR, results::add)
        car.barrier()

        assertEquals(0, car.store.commits)
        assertEquals(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            car.store.load().slots,
        )
        assertEquals(listOf(SplitCoordinatorCore.SELECT_FAILURE), car.notices.toList())
        assertEquals(listOf(SplitCoordinatorCore.SELECT_FAILURE), results.toList())
        assertTrue("the picker of that pane is still there", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertEquals(PRIMARY_PICKER_ACTIVITY, car.fake.topActivity(PRIMARY_ROOT))
    }

    @Test
    fun navigationReturnIsOneTransactionWithoutInterleavedHints() {
        // K15, сценарий §11.17: точный Temp закрыт, сосед жив, ни один reconcile не вклинился
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Навигатор уехал на приборку: его задача покинула панель, пикер снова виден.
        car.fake.removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
        core.projectionStarted(PRIMARY_APP_TASK)
        car.barrier()
        core.selectApp(PRIMARY_PICKER_TASK, WAZE)
        car.barrier()
        val temporary = car.fake.topTaskId(PRIMARY_ROOT)!!
        core.selectApp(SECONDARY_PICKER_TASK, RADIO)
        car.barrier()
        val neighbour = car.fake.topTaskId(SECONDARY_ROOT)!!

        core.holdExternalTaskMoves()
        car.clearCommands()
        repeat(50) { core.dividerResized() }
        core.pickerVisible(hostTaskId = null)
        core.pickerHidden(PRIMARY_PICKER_TASK)
        core.nativePickerVisible()
        car.barrier()
        assertEquals("the held task belongs to navigation", emptyList<String>(), car.commands())

        val plan = core.prepareNavigationReturn(PRIMARY_ROOT)
        car.fake.addTask(PRIMARY_ROOT, RETURNED_NAV_TASK, NAVIGATOR, "$NAVIGATOR.MainActivity")
        core.completeNavigationReturn(plan, RETURNED_NAV_TASK, NAVIGATOR)
        car.barrier()
        core.releaseExternalTaskMoves()

        assertEquals(SplitPane.PRIMARY, plan.pane)
        assertEquals(listOf(temporary), plan.displacedTasks.map(SplitDisplacedTask::taskId))
        assertFalse("the exact vacancy occupant is closed", car.fake.hasTask(temporary))
        assertEquals(
            "and it is the only task the return removes",
            1,
            car.commands().count { it.contains(" remove-task ") },
        )
        assertEquals("two operations, and nothing woven between them", 2, car.sessions().size)
        assertTrue("the navigator is back in its pane", car.fake.hasTask(RETURNED_NAV_TASK))
        assertEquals(RETURNED_NAV_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertTrue("its picker stays the floor of the pane", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertEquals(
            "the new neighbour is kept, not restarted",
            neighbour,
            car.fake.topTaskId(SECONDARY_ROOT),
        )
        assertEquals(
            "the navigator kept its slot and the neighbour got the new one",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.App(RADIO),
            ),
            car.store.load().slots,
        )

        core.dividerResized()
        car.barrier()
        assertTrue("a hint works again the moment navigation lets go", car.commands().isNotEmpty())
    }

    // endregion

    // region section 11 - the scenarios a unit test can close

    @Test
    fun onePackageInBothPanesBecomesTwoIndependentTasks() {
        // сценарий §11.12, контракт 1.5.2
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        core.selectApp(PRIMARY_PICKER_TASK, MUSIC)
        car.barrier()
        val first = car.fake.topTaskId(PRIMARY_ROOT)!!
        core.selectApp(SECONDARY_PICKER_TASK, MUSIC)
        car.barrier()
        val second = car.fake.topTaskId(SECONDARY_ROOT)!!

        assertNotEquals("two windows are two tasks", first, second)
        assertTrue("the first copy is not touched by the second", car.fake.hasTask(first))
        assertEquals(PRIMARY_ROOT, car.fake.taskRoot(first))
        assertEquals(
            "and both panes remember the same package independently",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            car.store.load().slots,
        )
    }

    @Test
    fun denzaAppsIsSelectedAndClosedLikeAnyOtherApp() {
        // сценарий §11.13, контракт U3, 1.4.3, 1.5.3
        assertTrue(
            "каталог не знает исключений про наш package",
            SplitPickerVisibilityPolicy.isLauncherVisible(
                packageName = SPLIT_HOST_PACKAGE,
                activityName = "$SPLIT_HOST_PACKAGE.MainActivity",
                showInAppList = null,
            ),
        )
        assertFalse(
            "исключена только кнопка-алиас, а не приложение",
            SplitPickerVisibilityPolicy.isLauncherVisible(
                packageName = SPLIT_HOST_PACKAGE,
                activityName = "$SPLIT_HOST_PACKAGE.SplitScreenLauncherAlias",
                showInAppList = null,
            ),
        )

        // Одна панель на своём пикере, во второй живёт сосед с музыкой.
        val car = car(
            FakeShell().apply {
                liveProductScene(withApps = true)
                removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
            },
        )
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.Picker,
                    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
                ),
            ),
        )
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val neighbour = car.fake.topTaskId(SECONDARY_ROOT)

        core.selectApp(PRIMARY_PICKER_TASK, SPLIT_HOST_PACKAGE)
        car.barrier()

        assertEquals(
            "Denza Apps занимает панель как обычное приложение",
            SplitSlot.App(SPLIT_HOST_PACKAGE),
            car.store.load().slot(SplitPane.PRIMARY),
        )
        assertEquals("сосед не перестроен", neighbour, car.fake.topTaskId(SECONDARY_ROOT))
        assertTrue(
            "пикеры-основания не съедены запуском собственного пакета (инвариант 3, правка W5)",
            car.fake.hasTask(PRIMARY_PICKER_TASK) && car.fake.hasTask(SECONDARY_PICKER_TASK),
        )

        // Закрыли её обычным Back: панель возвращается к своему пикеру.
        car.fake.removeActivity(PRIMARY_ROOT, "$SPLIT_HOST_PACKAGE.MainActivity")
        car.clearCommands()
        core.pickerVisible(PRIMARY_PICKER_TASK)
        car.barrier()

        assertEquals(SplitSlot.Picker, car.store.load().slot(SplitPane.PRIMARY))
        assertEquals(
            "закрытие Denza Apps ничего не перестраивает",
            SplitSlot.App(MUSIC),
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertEquals(neighbour, car.fake.topTaskId(SECONDARY_ROOT))
    }

    @Test
    fun aClosedAppIsNotRevivedByTheNextOpen() {
        // сценарий §11.15, контракт 1.3.4, инвариант 6
        val living = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        living.barrier()

        // Последний Back в навигаторе: его задача ушла, пикер панели снова виден.
        living.fake.removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
        core.pickerVisible(PRIMARY_PICKER_TASK)
        living.barrier()
        assertEquals(SplitSlot.Picker, living.store.load().slot(SplitPane.PRIMARY))

        // Сцена ушла вместе с процессом; следующий тап строит её заново из хранилища.
        val next = SplitCarFixture(fake = FakeShell(), store = living.store).also(cars::add)
        val restarted = next.core(next.store.load())
        restarted.initialize {}
        restarted.openPickerSession()
        next.barrier()

        assertFalse(
            "закрытое приложение не воскресает",
            next.commands().any { it.startsWith("am start ") && it.contains(NAVIGATOR) },
        )
        assertTrue(
            "а живший сосед восстановлен",
            next.commands().any { it.startsWith("am start ") && it.contains(MUSIC) },
        )
        assertEquals(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            next.store.load().slots,
        )
    }

    @Test
    fun dismissingBothPickersEndsTheSplitSession() {
        // Контракт 1.6.4: последовательные закрытия панелей, наблюдаемые по топологии. Сам
        // сценарий §11.16 в редакции bde631c - роль панели-якоря, обе ветви Back - живёт в
        // wideBackAtPickerPickerIsCleanedUpFromOneHintDespiteTheHomeStorm и
        // narrowBackAtPickerPickerLeavesTheFirmwareOutcomeAlone ниже.
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        car.fake.dismissPane(PRIMARY_ROOT)
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()

        assertEquals(
            "сосед развернулся на весь экран, закрытая панель закрыта",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Closed,
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            car.store.load().slots,
        )

        car.fake.removeActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)
        core.pickerHidden(SECONDARY_PICKER_TASK)
        car.barrier()

        assertEquals(
            "последний пикер закрыт - сессия split завершена",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Closed,
                SplitPane.SECONDARY to SplitSlot.Closed,
            ),
            car.store.load().slots,
        )

        car.clearCommands()
        repeat(10) { core.dividerResized() }
        car.barrier()
        assertEquals("и сцены больше нет: подсказкам нечего усыновлять", emptyList<String>(), car.commands())
    }

    @Test
    fun aCrashedAppFreesItsPaneAndLeavesTheNeighbourAlone() {
        // сценарий §11.23, контракт 1.7.3
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()

        // Приложение упало: его задача исчезла, пикер панели снова наверху.
        car.fake.removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
        core.pickerVisible(PRIMARY_PICKER_TASK)
        car.barrier()

        assertEquals(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            car.store.load().slots,
        )
        assertFalse(
            "автоперезапуска нет (U1)",
            car.commands().any { it.startsWith("am start ") },
        )
        assertEquals("сосед не тронут", SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(SECONDARY_APP_TASK))
    }

    @Test
    fun toggleOffOverTwoAppsKeepsTheFocusedOneFullscreen() {
        // сценарий §11.24, контракт 1.2.3
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        core.setEnabled(false)
        car.barrier()

        assertTrue("the focused app is fullscreen", car.fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertTrue("the neighbour keeps living", car.fake.hasTask(SECONDARY_APP_TASK))
        assertFalse("our pickers are removed by exact id", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertFalse(car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertFalse("a gate we own is closed with the session", car.fake.isGateOpen())
        assertFalse(car.gateLease.isOwned())
        assertEquals("the selection survives the toggle (1.3.2)", APP_PAIR, car.store.load().slots)
        assertFalse(car.store.load().enabled)
    }

    @Test
    fun twoSelectionsInDifferentPanesBothLand() {
        // сценарий §11.29, контракт 1.5.5: два SELECT в разные панели не конфликтуют
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        val hold = car.hold()
        core.selectApp(PRIMARY_PICKER_TASK, NAVIGATOR)
        core.selectApp(SECONDARY_PICKER_TASK, MUSIC)
        hold.release()
        car.barrier()

        assertTrue(car.fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertTrue(car.fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertEquals(
            "оба запуска выполнены, каждый в своей панели",
            APP_PAIR,
            car.store.load().slots,
        )
        assertEquals("и каждый записан своей операцией", 2, car.store.commits)
    }

    /**
     * Правка W4 (волна 7, контракт 1.5.3, live v22 b3): тап по собственному хабу в широком
     * пикере. Прошивка кладёт split-способный собственный пакет по СВОИМ правилам стороны -
     * окно встаёт в другую панель, и это успех тапа, а не ошибка: слот пишется по фактической
     * стороне, нотиса и rollback'а нет, обе панели живы.
     */
    @Test
    fun selectingOwnHubLandsOnTheSideTheFirmwareChoseWithoutARollback() {
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.fake.firmwareChoosesRootFor[SPLIT_HOST_PACKAGE] = PRIMARY_ROOT
        val results = Collections.synchronizedList(mutableListOf<String?>())

        core.selectApp(SECONDARY_PICKER_TASK, SPLIT_HOST_PACKAGE, results::add)
        car.barrier()

        assertEquals("тап удался: ни ошибки, ни нотиса", listOf<String?>(null), results.toList())
        assertEquals(
            "слот записан по фактической стороне прошивки",
            SplitSlot.App(SPLIT_HOST_PACKAGE),
            car.store.load().slot(SplitPane.PRIMARY),
        )
        assertEquals(
            "выбранная панель осталась пикером",
            SplitSlot.Picker,
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertFalse(
            "вставшее окно не казнено ложным rollback'ом",
            car.commands().any { it.contains(" remove-task ") },
        )
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    @Test
    fun clearAllDuringProjectionNeverClosesTheProjectedNavigator() {
        // сценарий §11.30, контракт 1.7.5 и 1.10: задача на приборке не считается закрытой
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        car.fake.removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
        car.fake.addTask(EXTERNAL_ROOT, PROJECTED_NAV_TASK, NAVIGATOR, "$NAVIGATOR.MainActivity")
        core.projectionStarted(PRIMARY_APP_TASK)
        car.barrier()

        // «Очистить всё» уносит с главного дисплея всё, до чего дотягивается Recents.
        listOf(PRIMARY_ROOT, SECONDARY_ROOT).forEach { root ->
            car.fake.dismissPane(root)
        }
        car.fake.area = 4
        car.clearCommands()
        repeat(20) { core.dividerResized() }
        core.pickerVisible(hostTaskId = null)
        car.barrier()

        assertEquals("под пустой сценой подсказки ничего не двигают", emptyList<String>(), car.mutations())
        assertTrue("задача навигатора жива на приборке", car.fake.hasTask(PROJECTED_NAV_TASK))
        assertEquals(
            "его слот и слот соседа не переписаны вслепую",
            SplitSlot.App(NAVIGATOR),
            car.store.load().slot(SplitPane.PRIMARY),
        )

        val plan = core.prepareNavigationReturn(PRIMARY_ROOT)
        assertNotNull("возврат навигатора по-прежнему находит свой план", plan)
        assertTrue("и это возврат на весь экран, а не в исчезнувшую панель", plan.fullscreen)
    }

    @Test
    fun clearAllInRecentsEndsTheSceneAndTheNextOpenShowsFreshPickers() {
        // 1.7.5, §11.30 вторая половина: очищенное не воскресает, следующий тап - два свежих пикера
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        assertEquals(APP_PAIR, car.store.load().slots)

        // «Очистить всё» уносит с главного дисплея всё, до чего дотягивается Recents.
        listOf(PRIMARY_ROOT, SECONDARY_ROOT).forEach(car.fake::dismissPane)
        car.fake.area = 4
        car.clearCommands()
        core.dividerResized()
        car.barrier()

        assertEquals(
            "ни одна панель не оставляет себе закрытое приложение",
            PICKER_PAIR,
            car.store.load().slots,
        )
        assertEquals("и это только наблюдение: ни одной мутации", emptyList<String>(), car.mutations())

        car.clearCommands()
        core.openPickerSession()
        car.barrier()

        val launches = car.commands().filter { it.startsWith("am start ") }
        assertEquals(
            "следующий тап строит ровно два пикера",
            2,
            launches.count { it.contains(SPLIT_PICKER_ACTIVITY) },
        )
        assertFalse(
            "и ничего не воскрешает (инвариант 6)",
            launches.any { it.contains(NAVIGATOR) || it.contains(MUSIC) },
        )
    }

    /**
     * Contract 1.12 and the live defect of the vertical slice, rated РАЗДРАЖАЕТ.
     *
     * The firmware records what a split *was* in four `Settings.System` keys and reads them back
     * when either remembered package is launched from Home. A Denza session left its own pair
     * there, so opening Denza Apps from the dock on a clean Home opened it as a second window next
     * to ADAS - the product initiating a pair nobody asked for (1.9.2, 1.9.3, invariant 11).
     */
    @Test
    fun aNativelyEndedSessionGivesTheFirmwareBackItsRememberedPair() {
        val car = car(
            FakeShell().apply {
                setSystem("byd_smart_multi_primary_activity", LAUNCHER_PACKAGE)
                setSystem("byd_smart_multi_second_activity", STOCK_BOOTSTRAP_PACKAGE)
                setSystem("byd_smart_multi_primary_position", "2")
                setSystem("byd_smart_multi_split_window_mode", "102")
            },
        )
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            leases = listOf(SmartMultiLease()),
        )
        core.initialize {}

        core.openPickerSession()
        car.barrier()
        assertTrue("the session holds the firmware gate", car.fake.isGateOpen())
        // What the firmware itself writes once a split of ours exists.
        car.fake.setSystem("byd_smart_multi_primary_activity", SPLIT_HOST_PACKAGE)

        // While the scene is alive nothing is given back - the pair is still in use (1.12).
        core.dividerResized()
        car.barrier()
        assertEquals(SPLIT_HOST_PACKAGE, car.fake.system("byd_smart_multi_primary_activity"))

        // The user ends the split natively: both pickers dismissed, firmware goes Home (1.6.3).
        listOf(PRIMARY_ROOT, SECONDARY_ROOT).forEach(car.fake::dismissPane)
        car.fake.area = 0
        core.dividerResized()
        car.barrier()

        assertEquals(
            "the pair the session found is the pair the car is left with",
            LAUNCHER_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )
        assertEquals(STOCK_BOOTSTRAP_PACKAGE, car.fake.system("byd_smart_multi_second_activity"))
        assertFalse("and the gate we opened is closed again", car.fake.isGateOpen())
    }

    /**
     * Contract 1.6, 1.6.4: after a native ending the product's one duty is to leave nothing behind.
     *
     * Forgetting the scene is not enough. The vertical slice met a picker of ours that survived
     * Back with narrow bounds outside the panel roots - invisible to the check that decides the
     * scene is over, and still there to spoil the next run.
     */
    @Test
    fun aPickerThatOutlivedANativeEndingIsRemovedWithTheScene() {
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // The firmware ended the split: one picker died with its pane, the other was reparented
        // out of the panel roots and left running.
        car.fake.addTask(FULL_ROOT, FOREIGN_TASK, FOREIGN, "$FOREIGN.MainActivity")
        car.fake.moveTask(PRIMARY_PICKER_TASK, FULL_ROOT)
        car.fake.removeActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)
        car.fake.area = 0
        car.clearCommands()

        core.dividerResized()
        car.barrier()

        assertFalse("the stump does not outlive the scene", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertTrue("and nobody else's task is touched", car.fake.hasTask(FOREIGN_TASK))
        assertEquals("the next open starts from two fresh pickers", PICKER_PAIR, car.store.load().slots)
    }

    /**
     * Contract 1.6, redaction 2026-08-23: наперекор прошивке продукт не идёт.
     *
     * When the firmware ends the split itself - the second native outcome of 1.6.3, `startDockOrHome`
     * plus `removeIviStack` - the product's whole reaction is cleanup. It does not rebuild the
     * scene, does not re-enter split, does not launch anything, and a storm of further hints adds
     * nothing at all (invariant 8, 1.6.4).
     */
    @Test
    fun aSystemTeardownIsAnsweredWithCleanupAndNothingElse() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val commits = car.store.commits
        car.clearCommands()

        // The firmware took the whole scene down and went Home.
        listOf(PRIMARY_ROOT, SECONDARY_ROOT).forEach(car.fake::dismissPane)
        car.fake.area = 0
        repeat(10) { core.dividerResized() }
        car.barrier()

        assertEquals(
            "the panes give themselves back to their pickers and nothing is revived",
            PICKER_PAIR,
            car.store.load().slots,
        )
        assertFalse("nothing is launched", car.commands().any { it.startsWith("am start ") })
        assertFalse(
            "the split is not re-entered",
            car.commands().any { it.startsWith("service call activity_task 115") },
        )
        assertFalse(
            "and no task is moved back into a pane",
            car.commands().any { it.startsWith("am stack move-task ") },
        )
        assertEquals("one settled fact, one commit", commits + 1, car.store.commits)

        car.clearCommands()
        repeat(10) { core.dividerResized() }
        car.barrier()
        // A later hint may still look - a pair is remembered, so a scene of ours could be back on
        // screen - but looking is all it may ever do (invariant 8, 1.4.4).
        assertEquals(
            "and there is nothing left for a later hint to act on",
            emptyList<String>(),
            car.mutations(),
        )
        assertEquals("nor anything left to persist", commits + 1, car.store.commits)
    }

    /**
     * Contract 1.6.3 (редакция bde631c), сценарий §11.16, ветвь широкой панели; машинная правда
     * ground-v18 B2. Back в ШИРОКОМ пикере при «пикер|пикер»: прошивка сама завершает split
     * (`startDockOrHome` → `removeIviStack`), широкий пикер умирает вместе с задачей, узкий
     * выбрасывается из панельных root'ов невидимой задачей, SmartMulti-ключи остаются грязными.
     *
     * Продукту с этого места принадлежит только уборка (1.6.4) - и она обязана состояться от
     * ЕДИНСТВЕННОЙ подсказки, потому что вторая может не прийти вовсе, и посреди шторма оконных
     * событий Home, потому что нативный конец 1.6.3 происходит именно на Home. Живой прогон v18
     * показал ровно обратное: тишину в логе, сироту и грязные ключи - каждое эхо Home строило
     * новую HomeOperation, а её постановка отменяла уборку из очереди (правка C1 волны 4).
     */
    @Test
    fun wideBackAtPickerPickerIsCleanedUpFromOneHintDespiteTheHomeStorm() {
        val car = car(
            FakeShell(initialGate = true).apply {
                liveProductScene()
                setSystem("byd_smart_multi_primary_activity", LAUNCHER_PACKAGE)
                setSystem("byd_smart_multi_second_activity", STOCK_BOOTSTRAP_PACKAGE)
                setSystem("byd_smart_multi_primary_position", "2")
                setSystem("byd_smart_multi_split_window_mode", "102")
            },
        )
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            leases = listOf(SmartMultiLease()),
        )
        // The scene on screen is the one this product built a moment ago: the gate is open on
        // the session's own lease, exactly as the live B2 run had it.
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        // What the firmware itself records while a scene of ours exists.
        car.fake.setSystem("byd_smart_multi_primary_activity", SPLIT_HOST_PACKAGE)

        // Back in the wide picker: its task dies, the narrow picker is stranded outside the
        // panel roots with its bounds kept, the screen is Home.
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.detachTask(SECONDARY_PICKER_TASK)
        car.fake.area = 0

        // The first Home hint is real: it confirms Home, suspends the gate we hold (1.9.1) - and,
        // правка W2 (в редакции волны 8), взводит уборочную сверку отложенным каналом: член
        // сцены мёртв, а hidden-хинт умершего пикера может не прийти вовсе. Таймер повтора
        // дожидается уборки.
        core.homeVisible()
        car.barrier()
        assertFalse(car.fake.isGateOpen())
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS)
        car.barrier()

        assertFalse(
            "сирота-пикер удалена по точной identity, где бы она ни оказалась",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals(
            "ключи SmartMulti возвращены compare-and-restore",
            LAUNCHER_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )
        assertEquals(STOCK_BOOTSTRAP_PACKAGE, car.fake.system("byd_smart_multi_second_activity"))
        assertFalse("gate закрыт по нашей аренде и аренда возвращена", car.gateLease.isOwned())
        assertFalse(car.fake.isGateOpen())

        // The launcher goes on emitting window events, and the late hidden-picker hint still
        // arrives - the world is already clean, and the storm changes nothing at all.
        car.clearCommands()
        val hold = car.hold()
        core.pickerHidden(PRIMARY_PICKER_TASK)
        repeat(ECHOES) { core.homeVisible() }
        hold.release()
        car.barrier()
        assertEquals("шторм над убранным миром ничего не трогает", emptyList<String>(), car.mutations())
        assertTrue(
            "шторм отброшен до очереди (инвариант 8)",
            car.diagnostics.count { it.startsWith("home hint dropped:") } >= ECHOES,
        )

        // 1.6.4: повторный тап после нативного конца - корректное открытие, ничего не воскресло.
        car.clearCommands()
        core.openPickerSession()
        car.barrier()
        val launches = car.commands().filter { it.startsWith("am start ") }
        assertEquals(2, launches.count { it.contains(SPLIT_PICKER_ACTIVITY) })
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    /**
     * Правка W1 волны 7 (b1-CORE, живой протокол 2026-08-25): после Back в широком пикере при
     * «пикер|пикер» area=0 наступает мгновенно, оба tx118 честно > 0 (контейнеры прошивки -
     * вечные объекты), а узкий пикер-сирота НИКОГДА сам не покидает свой панельный root. Прежние
     * ворота «записанные задачи ещё в панельных корнях» ждали состояния, которого на этой
     * прошивке не бывает, и settleSceneEnded не наступал никогда (ядро блокера v22). Конец
     * доказывается накрытием плюс мёртвым членом, и уборка обязана состояться от первой же
     * подсказки: сирота убран прямо из панельного корня, ключи возвращены, gate закрыт.
     */
    @Test
    fun theWideBackOrphanIsCleanedOutOfItsPanelRootFromTheFirstHint() {
        val (car, core) = wideBackWorld()

        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()

        assertFalse(
            "сирота убран по точной identity прямо из панельного корня, который он не покидал",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals(
            "ключи SmartMulti возвращены compare-and-restore",
            LAUNCHER_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )
        assertEquals(STOCK_BOOTSTRAP_PACKAGE, car.fake.system("byd_smart_multi_second_activity"))
        assertFalse("gate закрыт по нашей аренде и аренда возвращена", car.gateLease.isOwned())
        assertFalse(car.fake.isGateOpen())
        assertEquals("слоты соответствуют концу сцены", PICKER_PAIR, car.store.load().slots)
        assertEquals("мир решён с первой подсказки: повтор не нужен", 0, car.clock.pendingTimers())
    }

    /**
     * Обратная сторона правки W1: транспортная ошибка на чтении живости членов - не «конец».
     * Обрыв остаётся fail-closed («не доказано - не убираем»), и уборку доводит следующее
     * честное прочтение.
     */
    @Test
    fun aTransportErrorOnTheCleanupReadStaysFailClosed() {
        val (car, core) = wideBackWorld()

        car.shells.failOn("am stack list")
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()

        assertTrue("не доказано - не убираем", car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals(
            "и ключи не трогаются до честного прочтения",
            SPLIT_HOST_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )

        // Мир не изменился, изменился только транспорт: следующее прочтение доводит уборку.
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()
        assertFalse(car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals(LAUNCHER_PACKAGE, car.fake.system("byd_smart_multi_primary_activity"))
    }

    /**
     * Правка W2 (волна 7, мёртвая точка «б»): сабмит HomeOperation снимает взведённые повторы
     * сверки (cancelReconcileRechecks), и в v22 уборка, чей повтор был снят, замолкала навсегда.
     * Подтверждённый Home обязан сам закончиться финальной read-only проверкой «есть ли мёртвый
     * член» - и при да подать уборку. Правка W2 волны 8 (Ф2): подаёт он её ОТЛОЖЕННЫМ каналом
     * правки W3, не мгновенным сабмитом - мир не читается в зубы двухпроходного teardown.
     * Собственный взвод происходит внутри операции, после submit-времени самого Home, так что
     * сам себя Home не хоронит.
     */
    @Test
    fun aConfirmedHomeOverADeadMemberArmsTheDeferredCleanup() {
        val (car, core) = wideBackWorld()

        // Взведённый повтор прошлого отказа - тот самый, который сабмит Home снимает.
        car.shells.failOn("am stack list")
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()
        assertEquals(1, car.clock.pendingTimers())

        core.homeVisible()
        car.barrier()
        // Второй barrier дренирует всё, что Home мог бы подать мгновенно: до волны 8 здесь
        // уже не было сироты, теперь мир не читается в зубы teardown-а до таймера повтора.
        car.barrier()

        assertTrue(
            "уборка не подана мгновенно: мир не читается в зубы teardown-а",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals(
            "подтверждённый Home взвёл ровно один отложенный повтор",
            1,
            car.clock.pendingTimers(),
        )
        assertTrue(
            "и след решения в ринге",
            car.diagnostics.any { it.startsWith("home confirmed over a dead member") },
        )

        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS)
        car.barrier()

        assertFalse("повтор довёл уборку", car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals(LAUNCHER_PACKAGE, car.fake.system("byd_smart_multi_primary_activity"))
        assertFalse("gate закрыт и аренда возвращена", car.gateLease.isOwned())
        assertEquals(0, car.clock.pendingTimers())
    }

    /**
     * Правка W2 волны 8, вторая половина: смерть якоря подтверждается вторым чтением через
     * короткую паузу - только на позитивной ветке, перед мутациями. Член, «мёртвый» на
     * полутакте двухпроходного teardown и живой вторым чтением, конца не доказывает: уборка
     * не начинается, отказ назван, мир дочитает отложенный повтор.
     */
    @Test
    fun aMemberDeadForHalfABeatDoesNotEndTheScene() {
        val fake = FakeShell(initialGate = true).apply { liveProductScene() }
        val car = car(fake)
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            onDiagnostic = { line ->
                // Шов между двумя тактами доказательства: к второму чтению прошивка доехала
                // до конца перестройки, и «мёртвый» член снова в своём панельном корне.
                if (line.startsWith("scene end:")) {
                    fake.addTask(
                        PRIMARY_ROOT,
                        PRIMARY_PICKER_TASK,
                        SPLIT_HOST_PACKAGE,
                        PRIMARY_PICKER_ACTIVITY,
                    )
                }
            },
        )
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Полутакт teardown: широкая база на одно чтение отсутствует в снапшоте, экран Home.
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.area = 0
        car.clearCommands()

        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()

        assertTrue(
            "уборка не началась: второй такт увидел члена живым",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertTrue(car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertEquals("пара не забыта", PICKER_PAIR, car.store.load().slots)
        assertFalse(car.commands().any { it.contains(" remove-task ") })
        assertTrue(
            "отказ второго чтения назван",
            car.diagnostics.any { it.contains("не подтвердилась вторым чтением") },
        )
        assertEquals("мир дочитает один отложенный повтор", 1, car.clock.pendingTimers())
    }

    /**
     * Правка W2 (волна 7, мёртвая точка «а»): отменённая вытеснением уборочная сверка кидает
     * SplitOperationCancelled ДО хвостового armRecheck - в v22 она молчала навсегда. Отмена
     * обязана перевзводить ровно один отложенный повтор, и повтор доводит уборку.
     */
    @Test
    fun aCancelledCleanupReconcileReArmsItsOwnRecheck() {
        val (car, core) = wideBackWorld()
        // Gate не наш: подтверждённый Home тогда молчит (1.9.1), и перевзвод отмены - единственный
        // путь уборки в этом мире.
        car.gateLease.setOwned(false)

        // Уборочная сверка в полёте, запаркованная на своей первой команде.
        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.dividerResized()
        assertTrue(car.shells.awaitBlocked())

        // Home вытесняет её (§4) - и раньше хоронил уборку навсегда.
        core.homeVisible()
        car.shells.release()
        car.barrier()
        assertTrue("сирота ещё жив: уборку отменили", car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals("отменённая уборка перевзвела ровно один повтор", 1, car.clock.pendingTimers())

        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS)
        car.barrier()

        assertFalse("повтор довёл уборку", car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals(LAUNCHER_PACKAGE, car.fake.system("byd_smart_multi_primary_activity"))
        assertEquals(0, car.clock.pendingTimers())
    }

    /**
     * Правка W1 (1.8.2, диагноз v21 Д1): прошивочный «Release to close» не убивает приложение
     * схлопнутой панели - он отвязывает его живым; убивал продукт (adoptCollapse →
     * removeRecordedApp). Во время двухпроходного teardown полный постусловный набор физической
     * адопции честно недоказуем (здесь: задачи выжившего ещё держат панельные границы под
     * area 1), и в v21 слот вовсе не чистился - «воскрешение Музыки» было честным
     * восстановлением незачищенного слота. Факт обязан доказываться существованием: обе
     * записанные задачи панели покинули панельные root'ы.
     */
    @Test
    fun aNativeCollapseClosesThePaneAndNeverKillsItsApp() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val commits = car.store.commits
        car.clearCommands()

        // Схлопнута панель музыки: её host и app отвязаны живыми, площадь ушла в 1.
        car.fake.detachTask(SECONDARY_PICKER_TASK)
        car.fake.detachTask(SECONDARY_APP_TASK)
        car.fake.area = 1

        core.dividerResized()
        car.barrier()

        assertTrue(
            "таск приложения пользователя жив: его отвязала прошивка, продукт не трогает",
            car.fake.hasTask(SECONDARY_APP_TASK),
        )
        assertFalse(
            "и ни одна команда не адресовала его",
            car.commands().any { it.contains(" remove-task ") && it.contains(" $SECONDARY_APP_TASK ") },
        )
        assertFalse(
            "огрызок СВОЕГО пикера схлопнутой панели убран по точной identity",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals(
            "слот схлопнутой панели закрыт, выживший не переписан",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.Closed,
            ),
            car.store.load().slots,
        )
        assertEquals("и это одна запись", commits + 1, car.store.commits)
    }

    /**
     * Правка W1, различение от 1.7.3: у краха host-пикер жив в панельном root, отсутствует
     * только app - такая панель не схлопнута (сигнатура collapse: host И app оба покинули
     * панельные root'ы при area 1/2), и её слот не закрывается.
     */
    @Test
    fun aDeadAppAloneIsNotACollapseWhileItsPickerHoldsThePanelRoot() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val commits = car.store.commits
        car.clearCommands()

        // Музыка умерла (крах), но пикер её панели держит панельный root; area ушла в 1.
        car.fake.removeActivity(SECONDARY_ROOT, "$MUSIC.MainActivity")
        car.fake.area = 1

        core.dividerResized()
        car.barrier()

        assertNotEquals(
            "панель с живым host-пикером не закрыта: это путь APP→PICKER, не collapse",
            SplitSlot.Closed,
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertTrue("её пикер не тронут", car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals("не доказано - не записано", commits, car.store.commits)
        assertEquals(emptyList<String>(), car.mutations())
    }

    /**
     * Правка W1, регресс воскрешения (1.3.4, 1.8.2): collapse → Home → open. Панель схлопнутой
     * стороны показывает пикер; её живой фоновый таск не втягивается и не перезапускается.
     */
    @Test
    fun theNextOpenAfterACollapseShowsAPickerAndLeavesTheBackgroundTaskAlone() {
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        car.fake.detachTask(SECONDARY_PICKER_TASK)
        car.fake.detachTask(SECONDARY_APP_TASK)
        car.fake.area = 1
        core.dividerResized()
        car.barrier()
        assertEquals(SplitSlot.Closed, car.store.load().slot(SplitPane.SECONDARY))

        car.fake.area = 0
        core.homeVisible()
        car.barrier()

        car.clearCommands()
        core.openPickerSession()
        car.barrier()

        assertTrue("живой фоновый таск музыки продолжает жить", car.fake.hasTask(SECONDARY_APP_TASK))
        assertNotEquals(
            "и не втянут обратно в панель",
            SECONDARY_ROOT,
            car.fake.taskRoot(SECONDARY_APP_TASK),
        )
        assertFalse(
            "и не перезапущен (1.3.4)",
            car.commands().any { it.startsWith("am start ") && it.contains(MUSIC) },
        )
        assertEquals(
            "закрытая панель открывается свежим пикером",
            SplitSlot.Picker,
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    /**
     * Правка W1 волны 9, регресс-форма приёмки v24 (дефект 1): та же норма 1.8.2/1.3.4 в
     * геометрии, где выживший тоже покинул панельные корни.
     *
     * Живьём (детерминированно, 2/2): схлопывается ШИРОКАЯ SECOND справа, а выжившая узкая уходит
     * fullscreen вместе со смертью своей пикер-базы - её приложение стоит уже в полноэкранном
     * корне. Из панельных корней пропадают ОБЕ записанные панели, и до волны 9 доказательство по
     * существованию отказывало здесь безусловно: слот схлопнутой панели не чистился, ворота конца
     * сцены честно говорили «записанные приложения живы - не конец», и следующий open ВОСКРЕШАЛ
     * схлопнутое приложение. При area 1/2 выжившего называет сама area, и схлопнута - другая.
     */
    @Test
    fun aCollapseIsProvenByAreaWhenTheSurvivorAlsoLeftItsPanelRoot() {
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val commits = car.store.commits
        car.clearCommands()

        // Схлопнута широкая SECOND: её host и app прошивка отвязала живыми.
        car.fake.detachTask(SECONDARY_PICKER_TASK)
        car.fake.detachTask(SECONDARY_APP_TASK)
        // Выжившая узкая ушла fullscreen: её база мертва, её приложение - в полноэкранном корне.
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.moveTask(PRIMARY_APP_TASK, FULL_ROOT)
        car.fake.area = 1

        core.dividerResized()
        car.barrier()

        assertEquals(
            "слот схлопнутой панели закрыт, выживший не переписан",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.Closed,
            ),
            car.store.load().slots,
        )
        assertTrue(
            "таск приложения схлопнутой панели жив: прошивка отвязала его живым",
            car.fake.hasTask(SECONDARY_APP_TASK),
        )
        assertFalse(
            "и ни одна команда не адресовала его",
            car.commands().any { it.contains(" remove-task ") && it.contains(" $SECONDARY_APP_TASK ") },
        )
        assertFalse(
            "огрызок СВОЕГО пикера схлопнутой панели убран по точной identity",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals("и это одна запись", commits + 1, car.store.commits)

        // И следующий open даёт в этой панели пикер, а не воскрешение (1.3.4, 1.8.2).
        car.fake.area = 0
        core.homeVisible()
        car.barrier()
        car.clearCommands()
        core.openPickerSession()
        car.barrier()

        assertEquals(
            "закрытая панель открывается свежим пикером",
            SplitSlot.Picker,
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertTrue("живой фоновый таск музыки продолжает жить", car.fake.hasTask(SECONDARY_APP_TASK))
        assertNotEquals(
            "и не втянут обратно в панель",
            SECONDARY_ROOT,
            car.fake.taskRoot(SECONDARY_APP_TASK),
        )
        assertFalse(
            "и не перезапущен",
            car.commands().any { it.startsWith("am start ") && it.contains(MUSIC) },
        )
    }

    /**
     * Contract 1.6.3 (редакция bde631c), сценарий §11.16, ветвь узкой панели; ground-v18 B1.
     * Back в УЗКОМ пикере: прошивка сама разворачивает соседа в широкой на весь экран и сама
     * откатывает свои ключи. Пока эта сцена жива, продукт не убирает ничего и не возвращает
     * арендованное - его очередь наступает только после конца всей сессии (B1b).
     */
    @Test
    fun narrowBackAtPickerPickerLeavesTheFirmwareOutcomeAlone() {
        val car = car(
            FakeShell(initialGate = true).apply {
                liveProductScene()
                setSystem("byd_smart_multi_primary_activity", LAUNCHER_PACKAGE)
                setSystem("byd_smart_multi_second_activity", STOCK_BOOTSTRAP_PACKAGE)
                setSystem("byd_smart_multi_primary_position", "2")
                setSystem("byd_smart_multi_split_window_mode", "102")
            },
        )
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            leases = listOf(SmartMultiLease()),
        )
        // Как и в живом B1: сцену построила эта же сессия, gate открыт по её аренде.
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.fake.setSystem("byd_smart_multi_primary_activity", SPLIT_HOST_PACKAGE)

        // Back в узком: его задача умерла, широкий пикер развёрнут прошивкой на весь экран.
        car.fake.dismissPane(SECONDARY_ROOT)
        car.clearCommands()
        core.pickerHidden(SECONDARY_PICKER_TASK)
        car.barrier()

        assertTrue("выживший пикер прошивки не тронут", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertFalse(
            "продукт ничего не удаляет: исход нативный (1.6)",
            car.commands().any { it.contains(" remove-task ") },
        )
        assertEquals(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.Closed,
            ),
            car.store.load().slots,
        )
        assertTrue("сессия жива - gate остаётся нашим", car.fake.isGateOpen())
        assertEquals(
            "и ключи остаются за живой сценой (1.12)",
            SPLIT_HOST_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )

        // B1b: Back в выжившем полноэкранном пикере - прошивка уводит на Home; вот теперь
        // сессия кончилась, и продукт возвращает всё, что занимал.
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.area = 0
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()

        assertFalse("gate возвращён с концом сессии", car.fake.isGateOpen())
        assertFalse(car.gateLease.isOwned())
        assertEquals(
            "ключи прошивки восстановлены",
            LAUNCHER_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )

        // 1.6.4: повторный тап и после этого исхода открывается корректно.
        car.clearCommands()
        core.openPickerSession()
        car.barrier()
        assertEquals(
            2,
            car.commands().count { it.startsWith("am start ") && it.contains(SPLIT_PICKER_ACTIVITY) },
        )
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    /**
     * Правка W3 в редакции волны 7: единственный честно недоказуемый мир после W1 - нечитаемая
     * машина. Сверка, отказавшая из-за обрыва чтения, взводит ровно один отложенный повтор;
     * повтор перечитывает мир и доводит уборку без тапа пользователя, а удавшийся повтор новых
     * таймеров не оставляет - серия отказов заканчивается решением, не бесконечным повтором (W8).
     */
    @Test
    fun anUnprovenReconcileArmsOneDeferredRecheckThatFinishesTheCleanup() {
        val (car, core) = wideBackWorld()

        car.shells.failOn("am stack list")
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()
        assertTrue(
            "уборка честно отказала: живость членов нечитаема",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals("и взведён ровно один отложенный повтор", 1, car.clock.pendingTimers())

        // Транспорт ожил; мир тот же - b1-CORE, сирота в своём панельном корне.
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS)
        car.barrier()

        assertFalse(
            "повтор довёл уборку без тапа пользователя",
            car.fake.hasTask(SECONDARY_PICKER_TASK),
        )
        assertEquals(
            "ключи SmartMulti возвращены",
            LAUNCHER_PACKAGE,
            car.fake.system("byd_smart_multi_primary_activity"),
        )
        assertFalse("gate закрыт и аренда возвращена", car.gateLease.isOwned())
        assertEquals("новых таймеров после удавшегося повтора нет", 0, car.clock.pendingTimers())
    }

    /** Правка W3: пользовательская операция вытесняет ещё не сработавший повтор по §4. */
    @Test
    fun aUserOperationDisplacesThePendingRecheck() {
        val (car, core) = wideBackWorld()

        car.shells.failOn("am stack list")
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()
        assertEquals(1, car.clock.pendingTimers())

        core.openPickerSession()
        car.barrier()
        assertEquals("сабмит тапа снял таймер повтора", 0, car.clock.pendingTimers())

        val sessions = car.sessions().size
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS * 3)
        car.barrier()
        assertEquals("и после паузы ничего не срабатывает", sessions, car.sessions().size)
    }

    /** Правка W3, U1: отказавший повтор нового не взводит - ни цепочек, ни таймерных циклов. */
    @Test
    fun aFailedRecheckArmsNoThirdAttempt() {
        val (car, core) = wideBackWorld()

        car.shells.failOn("am stack list")
        core.pickerHidden(PRIMARY_PICKER_TASK)
        car.barrier()
        assertEquals(1, car.clock.pendingTimers())

        // Мир не изменился - и транспорт всё ещё мёртв: повтор отказывает так же честно.
        car.shells.failOn("am stack list")
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS)
        car.barrier()
        assertTrue(car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals("отказавший повтор нового не взводит", 0, car.clock.pendingTimers())

        val sessions = car.sessions().size
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS * 5)
        car.barrier()
        assertEquals("третьей попытки нет", sessions, car.sessions().size)
    }

    /** Правка W3: серия отказов коалесцируется - один общий повтор на её ключ. */
    @Test
    fun aStormOfRefusalsCoalescesIntoOneRecheck() {
        val (car, core) = wideBackWorld()

        repeat(5) {
            car.shells.failOn("am stack list")
            core.pickerHidden(PRIMARY_PICKER_TASK)
            car.barrier()
        }
        assertEquals("пять отказов держат один общий таймер", 1, car.clock.pendingTimers())

        val sessions = car.sessions().size
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS)
        car.barrier()
        assertEquals("повтор один", sessions + 1, car.sessions().size)
        assertEquals(0, car.clock.pendingTimers())
    }

    /**
     * Правка W3, обратная сторона: решённый мир повторов не взводит. Накрытая сцена, каждый
     * записанный член которой проверен живым, - полный позитивный ответ, а не отказ сверки.
     */
    @Test
    fun aResolvedCoveredSceneArmsNoRecheck() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Home накрыл живую сцену; оба приложения и оба пикера живы в своих корнях.
        car.fake.area = 0
        core.dividerResized()
        car.barrier()

        assertEquals("мир решён - повтор не нужен", 0, car.clock.pendingTimers())
        assertEquals(APP_PAIR, car.store.load().slots)
        assertTrue(
            "и решённый мир не пишет строку отказа (правка W4)",
            car.diagnostics.none { it.startsWith("reconcile unproven:") },
        )
    }

    /**
     * Правка W3 волны 9 (приёмка v24, Д3): двусмысленный хинт над СВОЕЙ сценой - не отказ.
     *
     * Оконное событие пикера приходит без host-id, и задачу ищет `singleVisiblePickerTaskId`,
     * которому нужен РОВНО ОДИН видимый пикер. Сразу после сборки «пикер|пикер» видимых пикеров
     * ДВА, и каждое открытие оставляло в ринге одну-две строки «видимый пикер не опознан» со
     * взведённым повтором. Все видимые пикеры - записанные члены живой сцены: это доказанное
     * состояние сцены, а не подвешенный мир.
     */
    @Test
    fun anAmbiguousPickerHintOverOurOwnSceneProvesItInsteadOfComplaining() {
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()

        core.pickerVisible(hostTaskId = null)
        car.barrier()

        assertTrue(
            "сцена доказана - строки отказа нет",
            car.diagnostics.none { it.startsWith("reconcile unproven:") },
        )
        assertEquals("и повтор не взведён", 0, car.clock.pendingTimers())
        assertEquals("и ничего не тронуто", emptyList<String>(), car.mutations())
        assertEquals(PICKER_PAIR, car.store.load().slots)
    }

    /**
     * Обратная сторона правки W3 волны 9: когда своего видимого пикера нет вовсе, хинт
     * по-прежнему ничего не доказывает и называет отказавший предикат.
     */
    @Test
    fun aPickerHintWithNoVisiblePickerOfOursStillNamesItsRefusal() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Оба пикера накрыты своими приложениями: видимых пикеров ноль.
        core.pickerVisible(hostTaskId = null)
        car.barrier()

        val lines = car.diagnostics.filter { it.startsWith("reconcile unproven:") }
        assertEquals("одна строка на отказ операции", 1, lines.size)
        assertTrue(
            "и она называет предикат: ${lines.single()}",
            lines.single().contains("picker-visible: видимый пикер не опознан"),
        )
        assertEquals("и взводит один повтор", 1, car.clock.pendingTimers())
    }

    /**
     * И вторая обратная сторона: посторонний пикер среди видимых - не член записанной сцены, и
     * разрешать по нему нечего. Exact identity, как везде (инвариант 3).
     */
    @Test
    fun aVisiblePickerOutsideTheRecordedSceneKeepsTheOldRefusal() {
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Третья пикер-база нашего компонента, которой записанная сцена не знает, встала поверх
        // узкой панели: видимых пикеров снова два, но один из них - не член сцены.
        car.fake.addTask(PRIMARY_ROOT, 62, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
        core.pickerVisible(hostTaskId = null)
        car.barrier()

        val lines = car.diagnostics.filter { it.startsWith("reconcile unproven:") }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("picker-visible: видимый пикер не опознан"))
        assertEquals(1, car.clock.pendingTimers())
    }

    /**
     * Правка W4 (U5, диагноз v21): каждая недоказанная сверка оставляет одну строку с именами
     * отказавших предикатов - строку на отказ операции, не на предикат-в-цикле. v21
     * диагностировался на полной тишине этих веток. Мир здесь честно не сведён и не кончился:
     * area 3, все члены живы, но в панели затесалась третья задача.
     */
    @Test
    fun anUnprovenReconcileNamesItsRefusedPredicatesInOneRingLine() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Чужая задача поверх панели: сцена не читается своей, collapse тоже недоказуем.
        car.fake.addTask(PRIMARY_ROOT, FOREIGN_TASK, FOREIGN, "$FOREIGN.MainActivity")
        core.dividerResized()
        car.barrier()

        val lines = car.diagnostics.filter { it.startsWith("reconcile unproven:") }
        assertEquals("одна строка на отказ операции", 1, lines.size)
        assertTrue(
            "и она называет отказавшие предикаты по именам: ${lines.single()}",
            lines.single().contains("resize: мир не сведён") &&
                lines.single().contains("collapse:"),
        )
    }

    /**
     * Правка W8: шторм одного и того же отказа не спамит ринг - идентичная причина unproven
     * подряд пишется строка-в-строку один раз. Решённый мир сбрасывает подавление: та же
     * причина после решения - снова новость.
     */
    @Test
    fun anIdenticalUnprovenCauseIsWrittenToTheRingOnceUntilTheWorldResolves() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        car.fake.addTask(PRIMARY_ROOT, FOREIGN_TASK, FOREIGN, "$FOREIGN.MainActivity")
        repeat(3) {
            core.dividerResized()
            car.barrier()
        }
        assertEquals(
            "три идентичных отказа подряд - одна строка",
            1,
            car.diagnostics.count { it.startsWith("reconcile unproven:") },
        )

        // Мир решён: чужая задача ушла, сцена снова читается своей.
        car.fake.removeActivity(PRIMARY_ROOT, "$FOREIGN.MainActivity")
        core.dividerResized()
        car.barrier()

        // Та же причина после решения - снова новость.
        car.fake.addTask(PRIMARY_ROOT, FOREIGN_TASK, FOREIGN, "$FOREIGN.MainActivity")
        core.dividerResized()
        car.barrier()
        assertEquals(2, car.diagnostics.count { it.startsWith("reconcile unproven:") })
    }

    /**
     * Правка W5 (1.9.3, диагноз v21 Д3-Б): не подтвердившееся за ~3 с area==0 - строка в ринг,
     * не молчание. Закрыть gate при накрытой сцене обязан продукт; при открытом gate прошивка
     * сама втягивает следующий split-способный запуск в широкую панель, и эта строка -
     * единственный след, по которому причина читается с support-экрана.
     */
    @Test
    fun anUnconfirmedHomeSuspendWritesARingLineInsteadOfSilence() {
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Оконный хинт Home пришёл, а area так и не стала 0: мир завис на живом сплите.
        core.homeVisible()
        car.barrier()

        assertTrue(
            "исчерпание ретраев названо в ринге",
            car.diagnostics.any { it.startsWith("home suspend unconfirmed:") },
        )
        assertTrue("gate не закрыт вслепую", car.fake.isGateOpen())
        assertTrue("и аренда не потеряна", car.gateLease.isOwned())
    }

    /** Правка W5, §4: тап пользователя не ждёт ретраи подтверждения - suspend отдаёт воркер. */
    @Test
    fun aWaitingUserTapDisplacesTheHomeSuspendRetries() {
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()

        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.homeVisible()
        assertTrue(car.shells.awaitBlocked())
        core.openPickerSession()
        car.shells.release()
        car.barrier()

        assertEquals(
            "suspend отдал воркер после одного чтения",
            listOf(SPLIT_AREA_QUERY),
            car.sessions().first(),
        )
        assertTrue(
            car.diagnostics.any { it.startsWith("home suspend displaced by user input") },
        )
        assertEquals("и тап получил экран", SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertTrue("gate остался при живой сцене", car.fake.isGateOpen())
    }

    /**
     * Мир b1-CORE (живой протокол 2026-08-25), общий для проверок правок W1-W3: Back в широком
     * пикере при «пикер|пикер» - его задача мертва, узкий сирота живёт в СВОЁМ панельном корне
     * (он не покидает его никогда), area 0 мгновенно, оба tx118 > 0.
     */
    private fun wideBackWorld(): Pair<SplitCarFixture, SplitCoordinatorCore> {
        val car = car(
            FakeShell(initialGate = true).apply {
                liveProductScene()
                setSystem("byd_smart_multi_primary_activity", LAUNCHER_PACKAGE)
                setSystem("byd_smart_multi_second_activity", STOCK_BOOTSTRAP_PACKAGE)
                setSystem("byd_smart_multi_primary_position", "2")
                setSystem("byd_smart_multi_split_window_mode", "102")
            },
        )
        val core = car.core(
            SplitDurable(enabled = true, slots = PICKER_PAIR),
            leases = listOf(SmartMultiLease()),
        )
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.fake.setSystem("byd_smart_multi_primary_activity", SPLIT_HOST_PACKAGE)

        // Back в широком: его задача умерла, узкий сирота остался в панельном корне, экран Home.
        car.fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        car.fake.area = 0
        return car to core
    }

    /**
     * Contract 1.8.1: the divider comes to rest on the firmware's own detents rather than where the
     * finger let go, and that is the native outcome, not something to correct.
     */
    @Test
    fun aDividerThatStoppedAtAFirmwareDetentIsAcceptedAsItIs() {
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()

        core.dividerResized()
        car.barrier()

        assertFalse(
            "the product never drags the divider back to where the user released it",
            car.commands().any { it.startsWith("input swipe ") },
        )
        assertEquals("both apps keep their exact tasks", PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertEquals(APP_PAIR, car.store.load().slots)
    }

    /**
     * Contract 1.13.1: the tap is answered before anything else happens at all.
     *
     * The waiting window is the visible reply to the gesture, so it is up while the operation is
     * still queued behind another one - the user may not watch a dead screen while the actor is
     * busy elsewhere (1.3.1, U6).
     */
    @Test
    fun theWaitingWindowIsUpBeforeTheOperationSendsAnything() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        val hold = car.hold()

        core.openPickerSession()

        assertEquals("the shield answers the tap, not the worker", 1, car.overlay.begun.get())
        assertEquals("and nothing has reached the car yet", emptyList<String>(), car.commands())

        hold.release()
        car.barrier()
        assertEquals(1, car.overlay.closed())
    }

    /**
     * Инвариант 5 (ред. 2026-08-24), живой красный v20 D1: прошивка на Home опустошает корень
     * сфокусированной панели, отвязывая живой пикер в display area с панельными бордерами. Его
     * hidden-хинт - эхо Home, а не жест dismiss: в v20 продукт убивал этот пикер, ярус выживших
     * к следующему open был пуст, и возврат шёл полной пересборкой с запусками.
     */
    @Test
    fun aHiddenPickerHintOverACoveredSceneIsAHomeEchoNotADismissal() {
        val car = car(FakeShell())
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.Picker,
                    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
                ),
            ),
        )
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val pickerP = car.fake.taskIds(PRIMARY_ROOT).single()
        val commits = car.store.commits

        // Home накрыл сцену, и прошивка выбросила пикерную панель из её корня целиком.
        car.fake.area = 0
        core.homeVisible()
        car.barrier()
        car.fake.detachTask(pickerP)
        car.clearCommands()

        core.pickerHidden(pickerP)
        car.barrier()

        assertTrue("отвязанный пикер накрытой сцены жив: он не сирота", car.fake.hasTask(pickerP))
        assertFalse(
            "ни одной команды удаления",
            car.commands().any { it.contains(" remove-task ") },
        )
        assertEquals("и ни одной мутации вообще", emptyList<String>(), car.mutations())
        assertEquals("слоты панелей не тронуты", commits, car.store.commits)
        assertEquals(SplitSlot.App(MUSIC), car.store.load().slot(SplitPane.SECONDARY))

        // Возврат кнопкой: выживший возвращён в свой корень, ничего не запущено заново (U2).
        car.clearCommands()
        core.openPickerSession()
        car.barrier()

        assertFalse(
            "ярус выживших сработал: ни одного запуска",
            car.commands().any { it.startsWith("am start ") },
        )
        assertEquals(PRIMARY_ROOT, car.fake.taskRoot(pickerP))
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertEquals(3, car.fake.area)
    }

    /**
     * Правка W4 (v20 D1, цель «возврат после Home ~2 с»): точная форма живого возврата. Прошивка
     * на Home опустошила ОБА панельных корня до маркеров, отвязав всех четырёх членов с
     * панельными бордерами; эхо возврата приносит hidden- и divider-хинты. Ярус частичного
     * reveal обязан пережить шум (W1+W3) и собрать сцену обратно одними move/focus: пустой
     * корень получает своего выжившего пикера move-task'ом, приложение возвращает stray-ярус,
     * и ни один участник не запускается заново (U2, 1.9.4).
     */
    @Test
    fun reopeningAfterAFullDetachTakesEverySurvivorBackWithoutALaunch() {
        val car = car(FakeShell(renderEmptyNativeRootMarker = true))
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val pickers = listOf(
            car.fake.taskIds(PRIMARY_ROOT).first(),
            car.fake.taskIds(SECONDARY_ROOT).first(),
        )
        val apps = listOf(
            car.fake.taskIds(PRIMARY_ROOT).last(),
            car.fake.taskIds(SECONDARY_ROOT).last(),
        )

        car.fake.area = 0
        core.homeVisible()
        car.barrier()
        (pickers + apps).forEach(car.fake::detachTask)
        car.clearCommands()

        // Шум накрытой сцены: эхо Home для пикера и оконное эхо жеста возврата.
        core.pickerHidden(pickers.first())
        core.dividerResized()
        car.barrier()

        assertEquals("шум не тронул ни одной задачи", emptyList<String>(), car.mutations())
        (pickers + apps).forEach { taskId ->
            assertTrue("задача $taskId пережила накрытие", car.fake.hasTask(taskId))
        }

        core.openPickerSession()
        car.barrier()

        assertFalse(
            "ярус частичного reveal собирает выживших без единого запуска",
            car.commands().any { it.startsWith("am start ") },
        )
        assertFalse(car.commands().any { it.contains(" remove-task ") })
        assertTrue(
            "пустой корень получил своего пикера live-proven командой move-task",
            car.commands().any { it.startsWith("am stack move-task ${pickers.first()} ") },
        )
        assertEquals("те же пикеры в своих корнях", PRIMARY_ROOT, car.fake.taskRoot(pickers[0]))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(pickers[1]))
        assertEquals("те же приложения в своих панелях", PRIMARY_ROOT, car.fake.taskRoot(apps[0]))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(apps[1]))
        assertEquals("сцена поднята", 3, car.fake.area)
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertTrue(
            "отказ адопции называет опустевший корень с его маркером",
            car.diagnostics.any { it.contains("scene-read:") && it.contains("пикеров 0") },
        )
    }

    @Test
    fun aCoveredSceneIsNeverMistakenForAnEndedOne() {
        // инвариант 5: Home и чужое полноэкранное окно прячут сцену, задачи которой живы в снапшоте
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val commits = car.store.commits

        // Чужое приложение на весь экран: панельные root'ы по-прежнему держат наши задачи.
        car.fake.addTask(FULL_ROOT, FOREIGN_TASK, FOREIGN, "$FOREIGN.MainActivity")
        car.fake.area = 4
        car.clearCommands()
        repeat(5) { core.dividerResized() }
        car.barrier()
        core.pickerVisible(hostTaskId = null)
        car.barrier()

        assertEquals("сцена скрыта, а не закрыта", APP_PAIR, car.store.load().slots)
        assertEquals("скрытая сцена ничего не переписывает", commits, car.store.commits)
        assertEquals(emptyList<String>(), car.mutations())
        assertTrue(car.fake.hasTask(PRIMARY_APP_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_APP_TASK))
    }

    /**
     * Правка W1 волны 8 (приёмка v23, Д1(а); механизм М2): выселенная Home-ом пикер-база SECOND
     * умирает недетерминированно при ЖИВЫХ приложениях пользователя, и ворота конца волны 7
     * считали её «мёртвым членом» сцены: слоты → P|P, пара забыта, возврат давал «пикер|пикер»
     * без сообщения (против 1.9.4/1.3.2). Якорь конца сцены с записанными приложениями - только
     * сами приложения: смерть базы - «база утрачена», сцена и слоты живут, огрызки живой сцены
     * не трогаются, а возврат пересоздаёт одну базу и возвращает ту же пару теми же задачами.
     */
    @Test
    fun aDeadEvictedPickerBaseWithLivingAppsDoesNotEndTheScene() {
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Home выселил детей ШИРОКОЙ панели (инвариант 5); база умерла (М2), приложение живо.
        car.fake.detachTask(SECONDARY_APP_TASK)
        car.fake.removeActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)
        car.fake.area = 0
        car.clearCommands()

        core.homeVisible()
        car.barrier()
        assertFalse("gate приостановлен Home-ом как обычно", car.fake.isGateOpen())
        assertTrue(
            "подтверждённый Home уборку не подал: якорь - живые приложения",
            car.diagnostics.none { it.startsWith("home confirmed over a dead member") },
        )

        // Эхо накрытой сцены: hidden-хинт умершей базы и оконное эхо; плюс их отложенные повторы.
        core.pickerHidden(SECONDARY_PICKER_TASK)
        core.dividerResized()
        car.barrier()
        car.clock.advance(SplitCoordinatorCore.RECONCILE_RECHECK_DELAY_MS * 3)
        car.barrier()

        assertEquals("пара не забыта", APP_PAIR, car.store.load().slots)
        assertTrue("приложения пользователя живы", car.fake.hasTask(PRIMARY_APP_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_APP_TASK))
        assertTrue("огрызки живой сцены не тронуты", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertFalse(
            "ни одна задача не адресована удалением",
            car.commands().any { it.contains(" remove-task ") },
        )

        // Возврат: ровно одна пересозданная база, ни одного перезапуска приложений (1.9.4, U2).
        car.clearCommands()
        core.openPickerSession()
        car.barrier()
        val launches = car.commands().filter { it.startsWith("am start ") }
        assertEquals(
            "пересоздана ровно одна база",
            1,
            launches.count { it.contains(SPLIT_PICKER_ACTIVITY) },
        )
        assertEquals("и это единственный запуск возврата", 1, launches.size)
        assertEquals("те же задачи в своих панелях", PRIMARY_ROOT, car.fake.taskRoot(PRIMARY_APP_TASK))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(SECONDARY_APP_TASK))
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    @Test
    fun anUninstallWhileThePickerIsOpenFreesThatPaneWithoutOneCommand() {
        // 1.5.6 и §6: удалённое приложение не может остаться выбором панели
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val opened = car.shells.opened.get()
        car.clearCommands()

        core.packageRemoved(MUSIC)
        car.barrier()

        assertEquals(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            car.store.load().slots,
        )
        assertEquals("уведомление об удалении не трогает машину", emptyList<String>(), car.commands())
        assertEquals("и не открывает даже сессию", opened, car.shells.opened.get())
        assertTrue("сосед продолжает жить", car.fake.hasTask(PRIMARY_APP_TASK))

        val commits = car.store.commits
        core.packageRemoved(WAZE)
        car.barrier()

        assertEquals("пакета нет ни в одном слоте - писать нечего", commits, car.store.commits)
        assertEquals(emptyList<String>(), car.commands())
    }

    @Test
    fun aFailedSelectionPublishesTheAutomatonNoticeExactlyOnce() {
        // 1.5.7: панель остаётся на пикере, сосед не тронут, сообщение ровно одно
        val car = car(FakeShell(directTargetLaunchSucceeds = false).apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        val neighbour = car.fake.topTaskId(SECONDARY_ROOT)
        val results = Collections.synchronizedList(mutableListOf<String?>())

        core.selectApp(PRIMARY_PICKER_TASK, NAVIGATOR, results::add)
        car.barrier()

        assertEquals(
            "план автомата - единственный источник сообщения, и оно одно",
            listOf(SplitCoordinatorCore.SELECT_FAILURE),
            car.notices.filter(String::isNotBlank),
        )
        assertEquals(listOf(SplitCoordinatorCore.SELECT_FAILURE), results.toList())
        assertEquals("хранилище не тронуто", 0, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertEquals(
            "панель снова на своём пикере",
            PRIMARY_PICKER_ACTIVITY,
            car.fake.topActivity(PRIMARY_ROOT),
        )
        assertEquals("сосед не тронут", neighbour, car.fake.topTaskId(SECONDARY_ROOT))
    }

    @Test
    fun partialRestoreNamesTheMissingApps() {
        // 1.3.2, U5: чего не удалось восстановить - названо по имени и выбирается вручную
        val car = car(FakeShell())
        val core = car.core(
            SplitDurable(enabled = true, slots = APP_PAIR),
            // Ровно то, что подставляет продакшен: имя из PackageManager, а не имя пакета.
            appLabel = { packageName -> if (packageName == MUSIC) MUSIC_LABEL else packageName },
        )
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        // Вторая панель не поднялась: прошивка не ответила на запуск ровно этого пакета.
        car.shells.failOn("service call activity_task 112 s16 '$MUSIC'")
        core.openPickerSession(results::add)
        car.barrier()

        assertEquals(
            "пользователь читает имя приложения и знает, что делать дальше",
            listOf("Не восстановлено: $MUSIC_LABEL. Выберите приложение вручную"),
            car.notices.filter(String::isNotBlank),
        )
        assertEquals("тап удался - это не ошибка операции", listOf<String?>(null), results.toList())
        assertEquals("и карточка не краснеет", SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertEquals(
            "названная панель ждёт ручного выбора, а сосед восстановлен",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            car.store.load().slots,
        )
        assertEquals(
            "и это её собственный пикер, а не чужое окно",
            SECONDARY_PICKER_ACTIVITY,
            car.fake.topActivity(SECONDARY_ROOT),
        )
    }

    /**
     * Правки W5+W6 (живой красный v20 P1.2, open 12.3 с): восстановление пары, чью цель прошивка
     * не удерживает в панели. Пре-существующий полноэкранный таск кандидата затягивается запуском
     * в панель и отказывается принимать её границы. Ветка обязана: не сжечь длинные бюджеты
     * ожиданий, деградировать пану в пикер с нотисом 1.3.2 - и НЕ казнить живой
     * пре-существовавший таск: он возвращается фоном (инвариант 3, 1.3.4 - о не-воскрешении,
     * не о казни фоновых задач). Плюс мина matcher'а: при self-restore поиск по одному пакету
     * предпочёл бы свежесозданный пикер - «восстановленным приложением» не может быть наш
     * собственный компонент.
     */
    @Test
    fun aFailedRestorationDegradesFastAndSparesThePreexistingTask() {
        val car = car(
            FakeShell().apply {
                addTask(FULL_ROOT, HUB_TASK, SPLIT_HOST_PACKAGE, "$SPLIT_HOST_PACKAGE.MainActivity")
                refusePaneBoundsFor += SPLIT_HOST_PACKAGE
                area = 4
            },
        )
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                    SplitPane.SECONDARY to SplitSlot.App(SPLIT_HOST_PACKAGE),
                ),
            ),
            appLabel = { packageName ->
                if (packageName == SPLIT_HOST_PACKAGE) HUB_LABEL else packageName
            },
        )
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        assertTrue("пре-существовавший таск кандидата жив", car.fake.hasTask(HUB_TASK))
        assertEquals(
            "он вернулся фоном в полноэкранный root, а не казнён",
            FULL_ROOT,
            car.fake.taskRoot(HUB_TASK),
        )
        assertEquals(
            "панель кандидата деградировала в свой пикер (1.3.2)",
            SplitSlot.Picker,
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertEquals(
            "сосед восстановлен",
            SplitSlot.App(MUSIC),
            car.store.load().slot(SplitPane.PRIMARY),
        )
        assertEquals(
            listOf("Не восстановлено: $HUB_LABEL. Выберите приложение вручную"),
            car.notices.filter(String::isNotBlank),
        )
        assertTrue(
            "оба пикера-основания живы: свой компонент - не «найденное приложение»",
            SplitPane.entries.all { pane ->
                car.fake.hasActivity(
                    if (pane == SplitPane.PRIMARY) PRIMARY_ROOT else SECONDARY_ROOT,
                    SPLIT_PICKER_ACTIVITY,
                )
            },
        )
        val stackReads = car.commands().count { it == "am stack list" }
        assertTrue(
            "ветка отказа не жжёт длинные бюджеты: $stackReads чтений топологии (потолок 10)",
            stackReads <= 10,
        )
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    /** Правка W5: запуск, чья задача так и не появилась, отвечает коротким бюджетом (1.3.2). */
    @Test
    fun aRestoreWhoseTaskNeverAppearsFailsWithinTheShortBudget() {
        val car = car(FakeShell().apply { swallowLaunchOf += WAZE })
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                    SplitPane.SECONDARY to SplitSlot.App(WAZE),
                ),
            ),
        )
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        assertEquals(
            "панель проглоченного запуска ждёт ручного выбора",
            SplitSlot.Picker,
            car.store.load().slot(SplitPane.SECONDARY),
        )
        assertEquals(SplitSlot.App(MUSIC), car.store.load().slot(SplitPane.PRIMARY))
        assertEquals(
            listOf("Не восстановлено: $WAZE. Выберите приложение вручную"),
            car.notices.filter(String::isNotBlank),
        )
        val stackReads = car.commands().count { it == "am stack list" }
        assertTrue(
            "короткий бюджет: $stackReads чтений топологии (потолок 9, было бы 18 при 12 попытках)",
            stackReads <= 9,
        )
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    @Test
    fun toggleOffDuringProjectionCancelsTheReturnAndKeepsTheNavigatorSlot() {
        // сценарий §11.31, контракт 1.10.8
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        car.gateLease.setOwned(true)
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        car.fake.removeActivity(PRIMARY_ROOT, "$NAVIGATOR.MainActivity")
        car.fake.addTask(EXTERNAL_ROOT, PROJECTED_NAV_TASK, NAVIGATOR, "$NAVIGATOR.MainActivity")
        core.projectionStarted(PRIMARY_APP_TASK)
        car.barrier()

        val plan = core.prepareNavigationReturn(PRIMARY_ROOT)
        car.fake.addTask(PRIMARY_ROOT, RETURNED_NAV_TASK, NAVIGATOR, "$NAVIGATOR.MainActivity")
        val failure = AtomicReference<Throwable?>()
        car.shells.blockAt("am stack list")
        val returning = Thread {
            failure.set(
                runCatching {
                    core.completeNavigationReturn(plan, RETURNED_NAV_TASK, NAVIGATOR)
                }.exceptionOrNull(),
            )
        }
        returning.start()
        assertTrue(car.shells.awaitBlocked())

        core.setEnabled(false)
        car.shells.release()
        returning.join(SPLIT_AWAIT_MS)
        car.barrier()

        assertTrue(
            "возврат отменён и сообщает об этом навигации (1.10.7)",
            failure.get() is SplitNavigationFailure,
        )
        assertTrue("навигатор жив на приборке", car.fake.hasTask(PROJECTED_NAV_TASK))
        assertEquals("его слот цел", SplitSlot.App(NAVIGATOR), car.store.load().slot(SplitPane.PRIMARY))
        assertFalse(car.store.load().enabled)
        assertFalse("сцена на IVI разобрана по правилам 1.2", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertFalse(car.fake.hasTask(SECONDARY_PICKER_TASK))
    }

    // endregion

    private fun car(fake: FakeShell): SplitCarFixture = SplitCarFixture(fake).also(cars::add)

    /** The exact launch a build sends for one pane, so a scenario can park the operation on it. */
    private fun appLaunch(category: String, packageName: String): String =
        "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-c byd.intent.category.$category -n '$packageName/$packageName.MainActivity' " +
            "-f 0x10200000"

    /** Every terminal the diagnostic log recorded for a tap on the launcher button. */
    /** Terminals without their duration: the wait is measured by the acceptance, not by a unit test. */
    private fun openTerminals(car: SplitCarFixture): List<String> = car.diagnostics
        .filter { it.startsWith("${SplitCoordinatorCore.OPEN_LABEL} outcome=") }
        .map { line -> line.substringBefore(" in ") }

    /**
     * A confirmed Home that is already inside the actor, whatever the coordinator decided about the
     * hint that produced it.
     *
     * It is how the priority table of §4 is read end to end: the coordinator may drop a hint before
     * it becomes work, but if one ever does become work, an `OPEN` still keeps its right to mutate.
     */
    private fun homeThatReachedTheActor(car: SplitCarFixture): SplitTicket = car.actor.submit(
        object : SplitOperationSpec {
            override val label: String = SplitCoordinatorCore.HOME_LABEL
            override val priority = SplitInputPriority.HOME
            override val durationMs = 30_000L
            override val joinKey: Any? = null
            override val coalesceKey: Any? = null

            override fun run(op: SplitOperationContext): SplitOutcome = SplitOutcome.Committed
        },
    )

    private companion object {
        const val GATE_OPEN = "service call activity_task 126 i32 1"
        const val RESIZE_KEY = "force_resizable_activities"
        const val RESIZEABILITY_LEASE_LINE = "firmware resizeability lease:"
        const val ACCESS_KEY = "picker_access_enabled"
        const val STOCK_TASK = 55

        /** Сколько оконных эхо апп-центра приходит вслед за одним тапом по кнопке. */
        const val ECHOES = 5

        /** Начало строки, которой координатор объясняет отброшенную подсказку Home. */
        const val HOME_HINT_DROPPED = "home hint dropped:"
        const val PROJECTED_NAV_TASK = 88

        /** An application already running when the tap arrived; a restore reuses it (U2). */
        const val LIVING_APP = 55

        /** The step mark an open records the moment its recipe is done and before the read-back. */
        const val SCENE_BUILT = "scene-built"
        const val FOREIGN_TASK = 99
        const val FOREIGN = "com.example.foreign"

        /** What `PackageManager` calls [MUSIC] on this car; the picker shows exactly this. */
        const val MUSIC_LABEL = "Яндекс Музыка"

        /** Пре-существующий полноэкранный таск хаба из живого красного v20 P1.2. */
        const val HUB_TASK = 99
        const val HUB_LABEL = "Denza Apps"

        /** Дальше любого бюджета операции, так что таймер актора точно сработал. */
        const val PAST_EVERY_BUDGET_MS = 120_000L

        /** Ids сцены, которую видел процесс до перезагрузки. */
        const val LIVE_PICKER_A = 6_001
        const val LIVE_PICKER_B = 6_002
        const val LIVE_APP_A = 7_001
        const val LIVE_APP_B = 7_002

        val NUMBERS = Regex("\\d+")
    }
}
