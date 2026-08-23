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
    fun openThenHomeCancelsOperationAndForbidsLateMutations() {
        // K1, сценарий §11.7: Home побеждает мгновенно, поздних команд нет, хранилище прежнее
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        car.shells.blockAt(SPLIT_AREA_QUERY)
        core.openPickerSession(results::add)
        assertTrue(car.shells.awaitBlocked())
        core.homeVisible()
        val before = car.commands().size
        car.shells.release()
        car.barrier()

        assertEquals("only the command Home raced was ever sent", before + 1, car.commands().size)
        assertEquals("nothing of the cancelled open was persisted", 0, car.store.commits)
        assertEquals(SplitDurable(enabled = true), car.store.load())
        assertEquals(1, car.overlay.begun.get())
        assertEquals("the waiting window is released, not left over Home", 1, car.overlay.closed())
        assertEquals("Home is what the user asked for, not an error", listOf<String?>(null), results.toList())
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
            "and the user is told, instead of watching a spinner forever (U5)",
            listOf("${SplitCoordinatorCore.OPEN_FAILURE}: превышено время ожидания"),
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
                setGlobal(ACCESS_KEY, "1")
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
            "both borrowed leases are given back, newest first",
            listOf("settings put global $ACCESS_KEY 1", "settings put global $RESIZE_KEY 0"),
            car.commands().filter { it.startsWith("settings put global ") }.takeLast(2),
        )
        assertEquals("a rolled back operation persists nothing", 0, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertTrue("and the starting scene is exactly where it was", car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_PICKER_TASK))
        assertEquals(4, car.fake.area)
        assertEquals(listOf(SplitCoordinatorCore.OPEN_FAILURE), results.toList())
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

    private companion object {
        const val GATE_OPEN = "service call activity_task 126 i32 1"
        const val RESIZE_KEY = "force_resizable_activities"
        const val ACCESS_KEY = "picker_access_enabled"
        const val STOCK_TASK = 55
        const val PROJECTED_NAV_TASK = 88
        const val FOREIGN_TASK = 99
        const val FOREIGN = "com.example.foreign"

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
