package dev.denza.apps.feature.split

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // region the one transport of the process

    /**
     * 1.13.3: an operation may not pay for an ADB handshake it does not need.
     *
     * Connect, authenticate and open an interactive shell cost the same whether the operation that
     * follows sends two reads or builds a whole scene, and the product used to pay it per operation.
     */
    @Test
    fun everyOperationSharesOneHandshakeAndADeadLinkEndsIt() {
        val handshakes = mutableListOf<CountingTransport>()
        val closed = mutableListOf<CountingTransport>()
        val shell = SplitPersistentShell {
            CountingTransport(closed::add).also(handshakes::add)
        }

        shell.open().let { first ->
            first.shell("am stack list")
            first.shell("service call activity_task 30")
            first.close()
        }
        shell.open().let { second ->
            second.shell("am stack list")
            second.close()
        }

        assertEquals("one handshake, three commands, two operations", 1, handshakes.size)
        assertEquals(3, handshakes.single().commands.size)
        assertEquals("and closing a lease closes nothing", emptyList<CountingTransport>(), closed)

        // 1.11.4: the link stopped answering. A cached dead pipe would make every later operation
        // fail the same way, so the failing command takes the transport with it.
        handshakes.single().failNext = true
        val broken = shell.open()
        assertThrows(IllegalStateException::class.java) { broken.shell("am stack list") }
        assertEquals(listOf(handshakes.single()), closed)

        shell.open().shell("am stack list")
        assertEquals("the next operation reconnects", 2, handshakes.size)

        shell.close()
        assertEquals("and shutdown closes what is left", handshakes.toList(), closed)
    }

    // endregion

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

    // region U5 - the product produces usable states instead of reporting failures

    /**
     * U5, 1.11.4: только мёртвый канал управления доходит до поверхности, и только он.
     *
     * Отказ рецепта чинить нечем и незачем: панель осталась там, где была, и следующий тап
     * работает. Мёртвый канал - другое дело: без него не работает ничто, и починка живёт на
     * своём экране хаба, поэтому вызвавшая поверхность обязана уметь его отличить.
     */
    @Test
    fun onlyADeadChannelIsNamedToTheSurfaceThatAsked() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<SplitActionResult>())

        car.shells.failOn(GATE_OPEN)
        core.openPickerSession(results::add)
        car.barrier()

        assertEquals(
            "обычный отказ рецепта поверхности не поручают",
            listOf(SplitActionResult.SETTLED),
            results.toList(),
        )
        assertEquals("и карточка молчит", SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertEquals("", core.snapshot().message)
    }

    /**
     * 1.11.4: каждая форма, которой эта машина отвечает на мёртвый локальный ADB, доходит до
     * поверхности как мёртвый канал - и ни одна из них не превращается в текст на панели.
     *
     * Формы взяты из живых отказов: неподтверждённый ключ, ключ в ожидании подтверждения,
     * отказ в соединении и переставший отвечать канал. Ни один рецепт их не переживает, и ни
     * один не чинится из панели: чинит их свой экран хаба.
     */
    @Test
    fun everyShapeOfADeadLocalAdbReachesTheSurfaceAsADeadChannel() {
        val shapes = listOf(
            "device unauthorized: authorization required",
            "adb: authorization pending on the head unit",
            "connect failed: connection refused",
            "shell read timeout after 5000ms",
        )
        shapes.forEach { reason ->
            val car = car(FakeShell())
            val core = car.core(SplitDurable(enabled = true))
            core.initialize {}
            val results = Collections.synchronizedList(mutableListOf<SplitActionResult>())

            car.shells.failOn(GATE_OPEN, reason)
            core.openPickerSession(results::add)
            car.barrier()

            assertEquals(reason, listOf(SplitActionResult.CHANNEL_UNAVAILABLE), results.toList())
            assertEquals("и экран об этом молчит", "", core.snapshot().message)
        }
    }

    /** 1.5.6, U5: пакет исчез между кадром и чтением - тап просто закончился. */
    @Test
    fun aSelectionOfAVanishedPackageSettlesQuietlyAndNamesItInTheRing() {
        val car = car(FakeShell().apply { liveProductScene() })
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<SplitActionResult>())

        core.selectApp(PRIMARY_PICKER_TASK, "com.gone.forever", results::add)
        car.barrier()

        assertEquals(listOf(SplitActionResult.SETTLED), results.toList())
        assertEquals("ни одной команды машине", emptyList<String>(), car.commands())
        assertEquals(
            "но ринг называет пакет",
            listOf("select refused: com.gone.forever больше не установлен"),
            car.diagnostics.filter { it.startsWith("select refused") },
        )
        assertEquals("и карточка молчит", "", core.snapshot().message)
    }

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
        assertEquals(SplitScreenPhase.ACTIVE, settled.phase)
        assertEquals("сцена на экране осталась прежней и молчит", "", settled.message)
        assertEquals(
            "но сбой не потерян: он ушёл в диагностический лог",
            listOf("background reconcile failed quietly: $SPLIT_ADB_DROPPED"),
            car.diagnostics.filter { it.startsWith("background ") },
        )

        // U5: тот же оборванный ADB в операции, которую пользователь запросил сам, молчит на
        // экране ровно так же - но говорит вызвавшей поверхности единственное, с чем та может
        // что-то сделать: канал управления мёртв (1.11.4).
        val tapped = car(FakeShell())
        val tappedCore = tapped.core(SplitDurable(enabled = true))
        tappedCore.initialize {}
        val results = Collections.synchronizedList(mutableListOf<SplitActionResult>())

        tapped.shells.failOn(GATE_OPEN, SPLIT_ADB_UNAUTHORIZED)
        tappedCore.openPickerSession(results::add)
        tapped.barrier()

        val visible = tappedCore.snapshot()
        assertEquals("карточка не краснеет и от тапа", SplitScreenPhase.ACTIVE, visible.phase)
        assertEquals("", visible.message)
        assertEquals(
            "но мёртвый канал назван вызвавшей поверхности",
            listOf(SplitActionResult.CHANNEL_UNAVAILABLE),
            results.toList(),
        )
        assertEquals(
            "а recipe-отказ - нет: он ничей не ремонт",
            listOf("open outcome=rolled-back reason=$SPLIT_ADB_UNAUTHORIZED in 0ms"),
            tapped.diagnostics.filter { it.startsWith("open outcome=") },
        )
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
        assertEquals(
            "navigation does not borrow the split card either",
            "",
            core.snapshot().message,
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

    /** One ADB transport: what it was asked, and whether it was ever really closed. */
    private class CountingTransport(private val onClose: (CountingTransport) -> Unit) :
        SplitShellHandle {
        val commands = mutableListOf<String>()

        var failNext = false

        override fun shell(command: String): String {
            if (failNext) {
                failNext = false
                throw IllegalStateException(SPLIT_ADB_DROPPED)
            }
            commands += command
            return ""
        }

        override fun close() = onClose(this)
    }

    private companion object {
        /** The hold, `NAV`, `SELECT` and `OPEN` behind it, each with its armed deadline. */
        const val QUEUED_OPERATIONS = 4

        /** The firmware gate every built scene needs, and therefore a mutation an open cannot skip. */
        const val GATE_OPEN = "service call activity_task 126 i32 1"
    }
}
