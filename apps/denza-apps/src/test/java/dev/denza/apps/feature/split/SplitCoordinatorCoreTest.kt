package dev.denza.apps.feature.split

import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The passive half of the coordinator: what a hint, an adoption and a toggle are allowed to do.
 *
 * The mandatory scenario skeleton K1-K15 lives in [SplitScenarioTest]; this class covers the
 * neighbouring behaviour that skeleton does not name - ambient hints, re-adoption of a scene that
 * outlived the process, and the gate rules of a toggle. The harness is the same fake car
 * ([SplitCarFixture]) and the oracle is always what reached it.
 */
class SplitCoordinatorCoreTest {

    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun stop() {
        cars.forEach(SplitCarFixture::close)
    }

    // region cold initialisation is read-only

    @Test
    fun enabledInitialisePerformsZeroShellCommands() {
        // K7 и U1: даже включённый продукт ничего не восстанавливает сам
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))

        core.initialize {}
        car.barrier()

        assertEquals(0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertTrue(core.snapshot().enabled)
    }

    @Test
    fun toggleOffWithoutASceneSendsNoCommandEither() {
        // A.3.1: холодная починка рассогласования тумблера не должна ничего ломать на экране
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        core.setEnabled(false)
        car.barrier()

        assertEquals(0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertTrue(car.fake.isGateOpen())
        assertFalse(car.store.load().enabled)
    }

    @Test
    fun enablingWritesTheToggleAndNothingElse() {
        // 1.2.1: включение - это одна запись и появившаяся кнопка
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = false))
        core.initialize {}

        core.setEnabled(true)
        car.barrier()

        assertEquals(emptyList<String>(), car.commands())
        assertTrue(car.store.load().enabled)
        assertEquals(1, car.store.commits)
    }

    // endregion

    // region passive hints without a scene

    @Test
    fun hintsWithoutALiveSceneNeverMutateAnything() {
        // сценарий 22: сто подсказок не дают ни одной мутации. Продуктовая подсказка
        // «пикер виден» теперь имеет право посмотреть, есть ли наша сцена (1.11.3) - и только.
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        repeat(100) { core.dividerResized() }
        core.pickerHidden(hostTaskId = 60)
        core.nativePickerVisible()
        car.barrier()

        assertEquals("an ambient hint without a pair is not work", 0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())

        core.pickerVisible(hostTaskId = null)
        car.barrier()

        assertEquals("and the hint that may look, only looks", emptyList<String>(), car.mutations())
        assertEquals(0, car.store.commits)
    }

    @Test
    fun hintsWhileDisabledNeverOpenAShell() {
        // инвариант 1: при выключенном тумблере продукт не реагирует ни на что
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = false))
        core.initialize {}

        repeat(50) { core.dividerResized() }
        core.homeVisible()
        core.nativePickerVisible()
        car.barrier()

        assertEquals(0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
    }

    // endregion

    // region 1.11.3 - a scene that outlived the process

    @Test
    fun aPickerHintReAdoptsTheOwnedSceneAndUnblocksTheEdgePath() {
        // 1.11.3: процесс умер под живой сценой; пикер сообщает о себе - сцена усыновлена
        val car = car(FakeShell(initialGate = true).apply { sceneLeftByADeadProcess() })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        core.nativePickerVisible()
        car.barrier()
        assertEquals(
            "without a scene the edge path is still silent",
            emptyList<String>(),
            car.commands(),
        )

        core.pickerVisible(hostTaskId = null)
        car.barrier()

        assertEquals(
            "adoption observes and accepts; it never launches or moves a task",
            emptyList<String>(),
            car.mutations(),
        )
        assertEquals(
            "the panes the car actually shows become the pair",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            car.store.load().slots,
        )
        assertEquals(1, car.store.commits)
        assertTrue(car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_APP_TASK))

        car.clearCommands()
        core.nativePickerVisible()
        car.barrier()

        assertTrue("and the edge path reaches the car again", car.commands().isNotEmpty())
    }

    @Test
    fun aDividerHintWithARememberedPairReAdoptsWithoutMovingATask() {
        // сценарий 19: оба приложения продолжают работать, ничего не двигается
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        assertEquals(emptyList<String>(), car.commands())

        core.dividerResized()
        car.barrier()

        assertEquals(emptyList<String>(), car.mutations())
        assertEquals("the remembered pair was already the truth", 0, car.store.commits)
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(PRIMARY_ROOT, car.fake.taskRoot(PRIMARY_APP_TASK))
        assertEquals(SECONDARY_ROOT, car.fake.taskRoot(SECONDARY_APP_TASK))

        car.clearCommands()
        core.nativePickerVisible()
        car.barrier()

        assertTrue("the scene axis is back", car.commands().isNotEmpty())
    }

    @Test
    fun aHintOverSomeoneElsesSplitAdoptsNothing() {
        // инвариант 2: чужой split не наш, сколько бы подсказок ни пришло
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}

        core.pickerVisible(hostTaskId = null)
        core.dividerResized()
        car.barrier()

        assertEquals(emptyList<String>(), car.mutations())
        assertEquals(0, car.store.commits)
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(1, car.fake.taskCount(PRIMARY_ROOT))
        assertEquals(1, car.fake.taskCount(SECONDARY_ROOT))
        assertEquals(3, car.fake.area)
        assertTrue(car.fake.isGateOpen())
    }

    @Test
    fun hintsWhileDisabledAdoptNothingEvenWithARememberedPair() {
        // инвариант 1, K7: выключенный продукт не усыновляет ничего и ни по какому поводу
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = false, slots = APP_PAIR))
        core.initialize {}

        core.pickerVisible(hostTaskId = null)
        core.pickerHidden(PRIMARY_PICKER_TASK)
        repeat(20) { core.dividerResized() }
        core.nativePickerVisible()
        car.barrier()

        assertEquals(0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertEquals(0, car.store.commits)
    }

    // endregion

    // region U5 - an error belongs where the user acted

    @Test
    fun backgroundReconcileFailureNeverPaintsTheErrorCard() {
        // U5: сбой фоновой сверки не трогает ни экран, ни карточку - только диагностический лог
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        car.clearCommands()

        // Связь обрывается посреди сверки, о которой пользователь не просил.
        car.shells.failOn("am stack list")
        core.dividerResized()
        car.barrier()

        val settled = core.snapshot()
        assertNotEquals("никто не просил эту работу", SplitScreenPhase.ERROR, settled.phase)
        assertEquals(SplitScreenPhase.ACTIVE, settled.phase)
        assertEquals("сцена на экране осталась прежней и молчит", "", settled.message)
        assertNull(settled.details)
        assertEquals(
            "и ни одного сообщения пользователю",
            emptyList<String>(),
            car.notices.filter(String::isNotBlank),
        )
        assertEquals(
            "но сбой не потерян: он ушёл в диагностический лог",
            listOf("background reconcile failed quietly: $SPLIT_ADB_DROPPED"),
            car.diagnostics.filter { it.startsWith("background ") },
        )

        // Контраст: тот же оборванный ADB в операции, которую пользователь запросил сам.
        val tapped = car(FakeShell())
        val tappedCore = tapped.core(SplitDurable(enabled = true))
        tappedCore.initialize {}

        tapped.shells.failOn(GATE_OPEN)
        tappedCore.openPickerSession()
        tapped.barrier()

        val visible = tappedCore.snapshot()
        assertEquals("тап по кнопке - действие пользователя", SplitScreenPhase.ERROR, visible.phase)
        assertEquals(SplitCoordinatorCore.OPEN_FAILURE, visible.message)
        assertEquals(SPLIT_ADB_DROPPED, visible.details)
        assertTrue(tapped.notices.contains(SplitCoordinatorCore.OPEN_FAILURE))
    }

    // endregion

    // region the firmware allowlist and the gate (contract 1.12)

    @Test
    fun aSelectionRecordsEveryFirmwareAllowlistAddition() {
        // контракт 1.12: каждое расширение split-списка прошивки попадает в диагностический лог
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        core.selectApp(PRIMARY_PICKER_TASK, NAVIGATOR)
        car.barrier()

        assertEquals(
            listOf("firmware split allowlist extended: '$NAVIGATOR'"),
            car.diagnostics.filter { it.startsWith("firmware split allowlist") },
        )
        assertEquals(SplitSlot.App(NAVIGATOR), car.store.load().slot(SplitPane.PRIMARY))
    }

    @Test
    fun toggleOffLeavesAGateThisProductNeverOpened() {
        // решение №2 и обязательство к 1.12: чужой gate не закрывается никогда
        val car = car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        core.setEnabled(false)
        car.barrier()

        assertTrue("the gate belongs to whoever opened it", car.fake.isGateOpen())
        assertFalse(car.commands().any { it == "service call activity_task 126 i32 0" })
        assertTrue("the product still ends its own scene", car.fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertFalse(car.fake.hasTask(PRIMARY_PICKER_TASK))
    }

    // endregion

    // region navigation (contract 1.10, priority NAV)

    @Test
    fun aFailedNavigationReturnThrowsAndLeavesTheSplitPanelAlone() {
        // 1.10.7: навигатор остаётся на приборке, ошибку показывает навигация, а не панель split
        val car = car(FakeShell(initialGate = true).apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        val plan = core.prepareNavigationReturn(PRIMARY_ROOT)
        val failure = assertThrows(SplitNavigationFailure::class.java) {
            core.completeNavigationReturn(plan, taskId = 999, packageName = NAVIGATOR)
        }
        car.barrier()

        assertEquals("Навигация не заняла выбранное split-окно", failure.message)
        assertEquals(
            "the split panel is not repainted as broken",
            SplitScreenPhase.ACTIVE,
            core.snapshot().phase,
        )
        assertTrue(
            "navigation does not borrow the split notice either",
            car.notices.none(String::isNotBlank),
        )
        assertEquals(0, car.store.commits)
        assertEquals(PICKER_PAIR, car.store.load().slots)
        assertTrue(car.fake.hasTask(PRIMARY_PICKER_TASK))
        assertTrue(car.fake.hasTask(SECONDARY_PICKER_TASK))
    }

    @Test
    fun aNavigationReturnOvertakesAQueuedSelectionAndOpen() {
        // раздел 4: очередь приоритетная, а не FIFO - NAV идёт раньше SELECT и OPEN
        val car = car(FakeShell(initialGate = true).apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        // Воркер занят, пока все три операции не встанут в очередь: их дедлайны видны на часах.
        val armed = car.clock.pendingTimers()
        car.hold { car.clock.pendingTimers() >= armed + QUEUED_OPERATIONS }
        car.clearCommands()
        core.selectApp(SECONDARY_PICKER_TASK, MUSIC)
        core.openPickerSession()
        val prepared = AtomicReference<SplitNavigationReturnPlan?>()
        val navigation = Thread { prepared.set(core.prepareNavigationReturn(PRIMARY_ROOT)) }
        navigation.start()
        navigation.join(SPLIT_AWAIT_MS)
        car.barrier()

        val commands = car.commands()
        assertNotNull("the navigation return finished", prepared.get())
        assertEquals(
            "the navigation lease reads its pane before the queue is served",
            "service call activity_task 118 i32 1",
            commands.first(),
        )
        assertTrue(
            "and the queued selection only opens the gate afterwards",
            commands.indexOf("service call activity_task 126 i32 1") > 0,
        )
    }

    // endregion

    private fun car(fake: FakeShell): SplitCarFixture = SplitCarFixture(fake).also(cars::add)

    private companion object {
        /** The hold, `NAV`, `SELECT` and `OPEN` behind it, each with its armed deadline. */
        const val QUEUED_OPERATIONS = 4

        /** The firmware gate every built scene needs, and therefore a mutation an open cannot skip. */
        const val GATE_OPEN = "service call activity_task 126 i32 1"
    }
}
