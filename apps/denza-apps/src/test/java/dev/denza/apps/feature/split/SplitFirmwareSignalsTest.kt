package dev.denza.apps.feature.split

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * К 1.9: Home and the split area heard from the firmware in the app process.
 *
 * The live numbers these scenarios stand on (findings, "The three calls, live from an app UID"):
 * `homekey` 9 ms after the key and before Home starts, the area push 111 ms, a gate flip 1 ms.
 * The race they close is 2026-09-18 19:51:52 - a tap in the dock a second after Home, pulled into
 * split next to `com.byd.sr` because the product's first area read landed 0.1 s too late (1.9.2).
 */
class SplitFirmwareSignalsTest {
    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun tearDown() {
        cars.forEach(SplitCarFixture::close)
    }

    @Test
    fun homeKeyClosesOurGateInProcessBeforeTheWorkerRunsAnything() {
        val car = car()
        val core = liveScene(car)
        car.clearCommands()
        car.fake.area = 0

        core.homeKeyPressed()

        assertEquals("закрыт сразу, на потоке сигнала, без ADB", listOf(false), car.fake.binderGateFlips)
        assertFalse(car.fake.isGateOpen())
        car.barrier()
        assertEquals(
            "Home подтверждён прочитанной area и записан (1.9.1)",
            SceneVisibility.COVERED,
            core.currentState().visibility,
        )
        assertTrue("аренда осталась нашей: следующий тап откроет его снова", car.gateLease.isOwned())

        car.clock.advance(SplitCoordinatorCore.GATE_AHEAD_CHECK_MS)

        assertEquals("накрытие настоящее - обратно не открыт", listOf(false), car.fake.binderGateFlips)
        assertFalse(car.fake.isGateOpen())
    }

    @Test
    fun aHomeTheFirmwareSwallowedGivesTheGateBackOneSecondLater() {
        val car = car()
        val core = liveScene(car)
        // Клавиша была, Home не случился: сцена на экране, area осталась 3.

        core.homeKeyPressed()
        car.barrier()

        assertFalse("до проверки gate закрыт на опережение", car.fake.isGateOpen())
        assertEquals(SceneVisibility.VISIBLE, core.currentState().visibility)

        car.clock.advance(SplitCoordinatorCore.GATE_AHEAD_CHECK_MS)

        assertEquals(listOf(false, true), car.fake.binderGateFlips)
        assertTrue(
            "видимая сцена с закрытым gate - приложение панели, уходящее на весь экран",
            car.fake.isGateOpen(),
        )
        assertTrue(car.diagnostics.any { it.startsWith("gate возвращён: homekey без накрытия, area=3") })
    }

    @Test
    fun homeKeyLeavesTheGateToAnOpenThatIsUsingIt() {
        val car = car()
        val core = liveScene(car)
        val open = PriorityHold(SplitInputPriority.OPEN)
        car.actor.submit(open)
        check(open.entered.await(SPLIT_AWAIT_MS, TimeUnit.MILLISECONDS))
        car.fake.area = 0

        core.homeKeyPressed()

        assertTrue("OPEN с Home не отменяется и gate держит сам (1.3.9)", car.fake.binderGateFlips.isEmpty())
        assertTrue(car.fake.isGateOpen())
        assertTrue(car.diagnostics.any { it.startsWith("homekey: gate оставлен операции OPEN") })
        open.release()
        car.barrier()
    }

    @Test
    fun anAreaPushOverAnIdleActorIsAHomeWithoutAKey() {
        val car = car()
        val core = liveScene(car)
        // Back в широкой панели, задетый заголовок, последний finish - Home без клавиши.
        car.fake.area = 0

        core.areaChanged(0)
        car.barrier()

        assertEquals(listOf(false), car.fake.binderGateFlips)
        assertFalse(car.fake.isGateOpen())
        assertEquals(SceneVisibility.COVERED, core.currentState().visibility)
    }

    @Test
    fun anAreaPushWhileOurOwnOperationRunsIsLeftToThatOperation() {
        val car = car()
        val core = liveScene(car)
        // Наша же операция снимает фокусную задачу: прошивка на миг поднимает Home (area 0).
        val ours = PriorityHold(SplitInputPriority.SELECT)
        car.actor.submit(ours)
        check(ours.entered.await(SPLIT_AWAIT_MS, TimeUnit.MILLISECONDS))

        core.areaChanged(0)

        assertTrue("переходный ноль не закрывает gate", car.fake.binderGateFlips.isEmpty())
        assertTrue(car.fake.isGateOpen())
        assertTrue(car.diagnostics.any { it.startsWith("area push 0: мир у операции SELECT") })
        ours.release()
        car.barrier()
        assertEquals(
            "и не становится Home, который отменил бы эту операцию",
            SceneVisibility.VISIBLE,
            core.currentState().visibility,
        )
    }

    @Test
    fun aVisibleAreaPushLetsTheReconcileResumeTheGateOverOurRevealedScene() {
        val car = car()
        val core = liveScene(car)
        car.fake.area = 0
        core.areaChanged(0)
        car.barrier()
        assertFalse(car.fake.isGateOpen())
        // Звонок кончился сам: сцена снова наверху, никто ничего не нажимал.
        car.fake.area = 3

        core.areaChanged(3)
        car.barrier()

        assertTrue("возобновила сверка, доказав сцену своей (1.11.5)", car.fake.isGateOpen())
        assertEquals(SceneVisibility.VISIBLE, core.currentState().visibility)
        assertEquals("push сам gate не открывает", listOf(false), car.fake.binderGateFlips)
    }

    @Test
    fun withTheToggleOffNothingOfOursListens() {
        val car = car()
        val core = car.core(SplitDurable(enabled = false, slots = APP_PAIR))
        core.initialize {}
        car.gateLease.setOwned(true)
        car.fake.area = 0

        core.homeKeyPressed()
        core.areaChanged(0)
        car.barrier()

        assertTrue("U4: выключено значит невидимо", car.fake.binderGateFlips.isEmpty())
        assertTrue(car.commands().isEmpty())
    }

    @Test
    fun aGateThisSessionDoesNotOwnIsNotClosedAhead() {
        val car = car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        core.homeKeyPressed()
        car.barrier()

        assertTrue("чужой штатный сплит - не наш gate (к 1.12)", car.fake.binderGateFlips.isEmpty())
        assertTrue(car.fake.isGateOpen())
    }

    private fun car(fake: FakeShell = FakeShell()): SplitCarFixture =
        SplitCarFixture(fake).also(cars::add)

    /** Our pair on screen with our gate open - the moment a Home or a push arrives in. */
    private fun liveScene(car: SplitCarFixture): SplitCoordinatorCore {
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        check(car.fake.area == 3 && car.fake.isGateOpen() && car.gateLease.isOwned()) {
            "the scene did not come up: area=${car.fake.area} gate=${car.fake.isGateOpen()}"
        }
        check(core.currentState().visibility == SceneVisibility.VISIBLE)
        return core
    }

    /** Occupies the worker as an operation of [priority] until released. */
    private class PriorityHold(override val priority: SplitInputPriority) : SplitOperationSpec {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override val label = "hold-$priority"
        override val durationMs = 120_000L
        override val joinKey: Any? = null
        override val coalesceKey: Any? = null

        fun release() = released.countDown()

        override fun run(op: SplitOperationContext): SplitOutcome {
            entered.countDown()
            released.await(SPLIT_AWAIT_MS, TimeUnit.MILLISECONDS)
            return SplitOutcome.Committed
        }
    }
}
