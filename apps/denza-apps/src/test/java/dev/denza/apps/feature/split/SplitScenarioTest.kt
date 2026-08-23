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
     * U5 and the live blank of the vertical slice: two whole open runs, not one line of the tag.
     *
     * `Log.i` from this application cannot be proven to reach anyone on this firmware, while the
     * same tag written from the shell does. So an operation that is talking to the car anyway
     * carries what the product recorded over that channel too - once, at the end, never a session
     * of its own, and never past the fence.
     */
    @Test
    fun whatTheProductRecordsAlsoLeavesByAChannelTheCarAccepts() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        val mirrored = car.commands().filter { it.startsWith("log -t ${SplitDiagnostics.TAG} ") }
        assertEquals("one command, at the end of the operation", 1, mirrored.size)
        assertTrue(
            "and it carries the step timings the diagnosis needs",
            mirrored.single().contains("${SplitCoordinatorCore.OPEN_LABEL} +"),
        )
        assertTrue(
            car.diagnostics.any { it.startsWith("${SplitCoordinatorCore.OPEN_LABEL} +") },
        )

        // A second operation carries what the first could not: its own terminal, logged after the
        // first shell had already closed.
        car.clearCommands()
        core.openPickerSession()
        car.barrier()

        assertTrue(
            "the terminal of the first open is not lost, only late",
            car.commands()
                .filter { it.startsWith("log -t ") }
                .any { it.contains("${SplitCoordinatorCore.OPEN_LABEL} outcome=committed") },
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

    /** Invariant 10: a cancelled operation sends nothing more - not even a diagnostic line. */
    @Test
    fun anExpiredOperationDoesNotEvenMirrorItsOwnDiagnostics() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        car.shells.blockAt(GATE_OPEN)
        core.openPickerSession()
        assertTrue(car.shells.awaitBlocked())
        car.clock.advance(PAST_EVERY_BUDGET_MS)
        car.shells.release()
        car.barrier()

        assertEquals(
            emptyList<String>(),
            car.commands().filter { it.startsWith("log -t ") },
        )
    }

    // endregion

    // region K5-K6 - the passive storm

    @Test
    fun hundredWindowsHintsDoNotDelaySelect() {
        // K5, сценарий §11.22: выбор не ждёт очередь из ста reconcile, и они схлопнуты до одного
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
        assertEquals("a hundred hints collapse into one reconcile", 2, sessions.size)
        assertTrue(
            "and the explicit tap is served before it",
            sessions.first().any { it.startsWith("am start ") && it.contains(MUSIC) },
        )
        assertEquals(
            "the reconcile that follows only reads the settled scene",
            emptyList<String>(),
            sessions.last().filter { it.startsWith("am start ") || it.contains(" remove-task ") },
        )
        assertEquals(SplitSlot.App(MUSIC), car.store.load().slot(SplitPane.SECONDARY))
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
            "и постусловие одно на обе панели: два устойчивых сэмпла и финальный read-back",
            3,
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
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        // Первый тап усыновляет живую сцену: с этого момента процесс знает её точные задачи.
        core.openPickerSession()
        car.barrier()
        car.fake.area = 0
        core.homeVisible()
        car.barrier()
        car.clearCommands()

        core.openPickerSession()
        car.barrier()

        assertFalse(
            "ни одного запуска: приложения те же самые и не перезапускались (U2)",
            car.commands().any { it.startsWith("am start ") },
        )
        assertFalse(car.commands().any { it.contains(" remove-task ") })
        assertTrue(car.fake.hasTask(PRIMARY_APP_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_APP_TASK))
        assertEquals("сцена снова на экране", 3, car.fake.area)
        assertTrue(
            car.diagnostics.any { it.contains("scene-read: adoptable") },
        )
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
        // сценарий §11.16, контракт 1.6.3 и 1.6.4
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
