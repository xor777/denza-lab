package dev.denza.apps.feature.split

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scenario tests of the coordinator (appendix B.3).
 *
 * They are the layer the previous test suite did not have at all: the harness is a fake car - the
 * shared `FakeShell` firmware, a hand-driven clock, an atomic in-memory store, a counted overlay -
 * and every oracle is what actually reached that car, never a restatement of the code. Each test
 * below can fail from a real class of failure: a command sent while the product is off, a mutation
 * that outlived a cancel, a second lease, a store written by a failed operation.
 */
class SplitCoordinatorCoreTest {

    private val clock = FakeSplitClock()
    private var actor = SplitActor(clock)
    private var core: SplitCoordinatorCore? = null

    @After
    fun stop() {
        core?.shutdown()
        actor.shutdown()
    }

    // region K7 - cold initialisation is read-only

    @Test
    fun disabledInitialisePerformsZeroShellCommands() {
        // K7, сценарий 14, инвариант 1: выключенный продукт молчит на старте процесса
        val car = Car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = false))

        core.initialize {}

        assertEquals("no shell session is opened at all", 0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertTrue("someone else's split is untouched", car.fake.isGateOpen())
        assertEquals(3, car.fake.area)
    }

    @Test
    fun enabledInitialisePerformsZeroShellCommands() {
        // K7 и U1: даже включённый продукт ничего не восстанавливает сам
        val car = Car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
                ),
            ),
        )

        core.initialize {}

        assertEquals(0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertTrue(core.snapshot().enabled)
    }

    @Test
    fun toggleOffWithoutASceneSendsNoCommandEither() {
        // A.3.1: холодная починка рассогласования тумблера не должна ничего ломать на экране
        val car = Car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
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
        val car = Car(FakeShell())
        val core = car.core(SplitDurable(enabled = false))
        core.initialize {}

        core.setEnabled(true)
        car.barrier()

        assertEquals(emptyList<String>(), car.commands())
        assertTrue(car.store.load().enabled)
        assertEquals(1, car.store.commits)
    }

    // endregion

    // region K6 - passive hints without a scene

    @Test
    fun hintsWithoutALiveSceneNeverOpenAShell() {
        // K6, сценарий 22: пассивные события не порождают работу до фильтрации
        val car = Car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}

        repeat(100) { core.dividerResized() }
        core.pickerVisible(hostTaskId = null)
        core.pickerHidden(hostTaskId = 60)
        core.nativePickerVisible()
        car.barrier()

        assertEquals("no scene means no work at all", 0, car.shells.opened.get())
        assertEquals(emptyList<String>(), car.commands())
        assertEquals(0, car.store.commits)
    }

    @Test
    fun hintsWhileDisabledNeverOpenAShell() {
        // инвариант 1: при выключенном тумблере продукт не реагирует ни на что
        val car = Car(FakeShell(initialGate = true).apply { stockSplitOfSomeoneElse() })
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

    // region K1/K4 - one tap, one operation, one window

    @Test
    fun homeCancelsTheOpenAndForbidsItsLateMutations() {
        // K1, сценарий 7: Home побеждает мгновенно, поздних команд нет, хранилище прежнее
        val car = Car(FakeShell(), blockAt = AREA_QUERY)
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        core.openPickerSession(results::add)
        assertTrue(car.shells.reached.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        core.homeVisible()
        val before = car.commands().size
        car.shells.release()
        car.barrier()

        assertEquals(
            "only the command Home raced was ever sent",
            before + 1,
            car.commands().size,
        )
        assertEquals("nothing of the cancelled open was persisted", 0, car.store.commits)
        assertEquals(SplitDurable(enabled = true), car.store.load())
        assertEquals(1, car.overlay.begun.get())
        assertEquals("the waiting window is released, not left over Home", 1, car.overlay.closed())
        assertEquals(listOf<String?>(null), results.toList())
    }

    @Test
    fun twoLauncherTapsShareOneOperationOneLeaseAndOneResult() {
        // K4, сценарий 11, контракт 1.3.7
        val car = Car(FakeShell(), blockAt = AREA_QUERY)
        val core = car.core(SplitDurable(enabled = true))
        core.initialize {}
        val results = Collections.synchronizedList(mutableListOf<String?>())

        core.openPickerSession(results::add)
        assertTrue(car.shells.reached.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        core.openPickerSession(results::add)
        car.shells.release()
        car.barrier()

        assertEquals("one waiting window for both taps", 1, car.overlay.begun.get())
        assertEquals("one shell session, so one launch sequence", 1, car.shells.opened.get())
        assertEquals("both callers observe the very same outcome", listOf(null, null), results.toList())
        assertEquals("one operation is one commit", 1, car.store.commits)
        assertEquals(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            car.store.load().slots,
        )
    }

    // endregion

    // region K14 - a failed selection

    @Test
    fun aFailedSelectionLeavesTheStoreAloneAndShowsANotice() {
        // K14, сценарий 9: пикер остаётся интерактивным, пара не тронута, сообщение видно
        val car = Car(FakeShell(directTargetLaunchSucceeds = false).apply { liveProductScene() })
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
    }

    @Test
    fun aSelectionRecordsEveryFirmwareAllowlistAddition() {
        // контракт 1.12: каждое расширение split-списка прошивки попадает в диагностический лог
        val car = Car(FakeShell().apply { liveProductScene() })
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

    // endregion

    // region toggle off over a live scene (1.2.3, to 1.12)

    @Test
    fun toggleOffOverTwoAppsKeepsTheFocusedOneFullscreenAndClosesOurGate() {
        // 1.2.3, сценарий 24: фокусное приложение на весь экран, сосед жив, пикеры удалены
        val car = Car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
                ),
            ),
        )
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
        assertEquals(
            "the selection survives the toggle (1.3.2)",
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            car.store.load().slots,
        )
        assertFalse(car.store.load().enabled)
    }

    @Test
    fun toggleOffLeavesAGateThisProductNeverOpened() {
        // решение №2 и обязательство к 1.12: чужой gate не закрывается никогда
        val car = Car(FakeShell(initialGate = true).apply { liveProductScene(withApps = true) })
        val core = car.core(
            SplitDurable(
                enabled = true,
                slots = mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
                ),
            ),
        )
        core.initialize {}
        core.openPickerSession()
        car.barrier()

        core.setEnabled(false)
        car.barrier()

        assertTrue("the gate belongs to whoever opened it", car.fake.isGateOpen())
        assertFalse(
            car.commands().any { it == "service call activity_task 126 i32 0" },
        )
        assertTrue("the product still ends its own scene", car.fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertFalse(car.fake.hasTask(PRIMARY_PICKER_TASK))
    }

    // endregion

    // region harness

    /** One fake car: the firmware fixture plus every seam the coordinator is built from. */
    private inner class Car(val fake: FakeShell, blockAt: String? = null) {
        val shells = RecordingShellFactory(fake, blockAt)
        val store = CountingStore()
        val overlay = CountingOverlay()
        val gateLease = FakeGateLease()
        val notices = Collections.synchronizedList(mutableListOf<String>())
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())

        fun core(initial: SplitDurable): SplitCoordinatorCore {
            store.seed(initial)
            return SplitCoordinatorCore(
                shellFactory = shells,
                clock = clock,
                store = store,
                actor = actor,
                overlayOwner = overlay,
                notices = notices::add,
                catalog = FakeCatalog,
                gateLeaseStore = gateLease,
                leases = emptyList(),
                apkPath = APK_PATH,
                sleeper = {},
                log = diagnostics::add,
            ).also { built -> core = built }
        }

        fun commands(): List<String> = synchronized(fake) { fake.commands.toList() }

        /** The single worker is the barrier: what it finishes last, it finished after everything. */
        fun barrier() {
            assertNotNull(
                "the actor did not drain in time",
                actor.submit(BarrierSpec).await(AWAIT_MS),
            )
        }
    }

    private object BarrierSpec : SplitOperationSpec {
        override val label = "barrier"
        override val priority = SplitInputPriority.HINT
        override val durationMs = 60_000L
        override val joinKey: Any? = null
        override val coalesceKey: Any? = null
        override fun run(op: SplitOperationContext): SplitOutcome = SplitOutcome.Committed
    }

    private class RecordingShellFactory(
        private val fake: FakeShell,
        private val blockAt: String?,
    ) : SplitShellFactory {
        val opened = AtomicInteger()
        val reached = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        @Volatile
        private var blocked = false

        fun release() = gate.countDown()

        override fun open(): SplitShellHandle {
            opened.incrementAndGet()
            return object : SplitShellHandle {
                override fun shell(command: String): String {
                    if (blockAt != null && !blocked && command == blockAt) {
                        blocked = true
                        reached.countDown()
                        gate.await(AWAIT_MS, TimeUnit.MILLISECONDS)
                    }
                    return synchronized(fake) { fake.shell(command) }
                }

                override fun close() = Unit
            }
        }
    }

    private class CountingStore : SplitStateStore {
        @Volatile
        private var current = SplitDurable()

        @Volatile
        var commits: Int = 0
            private set

        fun seed(snapshot: SplitDurable) {
            current = snapshot
            commits = 0
        }

        override fun load(): SplitDurable = current

        override fun commit(next: SplitDurable): Boolean {
            commits += 1
            current = next
            return true
        }
    }

    private class CountingOverlay : SplitOverlayOwner {
        val begun = AtomicInteger()
        private val released = AtomicInteger()

        fun closed(): Int = released.get()

        override fun begin(): SplitOverlayLease {
            begun.incrementAndGet()
            return object : SplitOverlayLease {
                private val done = AtomicInteger()

                override fun close() {
                    if (done.compareAndSet(0, 1)) released.incrementAndGet()
                }

                override fun closeImmediately() = close()
            }
        }
    }

    private object FakeCatalog : SplitLaunchCatalog {
        override fun installedPackages(): Set<String> = setOf(NAVIGATOR, MUSIC, WAZE)

        override fun resolve(packageName: String): SplitLaunchTarget? =
            if (packageName in installedPackages()) {
                SplitLaunchTarget(packageName, "$packageName/$packageName.MainActivity")
            } else {
                null
            }
    }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val APK_PATH = "/data/app/dev.denza.apps/base.apk"
        const val AREA_QUERY = "service call activity_task 30"
        const val PRIMARY_PICKER_TASK = 60
        const val SECONDARY_PICKER_TASK = 61
        const val SECONDARY_APP_TASK = 71

        /** A stock split the user built themselves: nothing here belongs to the product. */
        fun FakeShell.stockSplitOfSomeoneElse() {
            area = 3
            addTask(PRIMARY_ROOT, 40, STOCK_PICKER_PACKAGE, STOCK_PICKER_ACTIVITY)
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        /** A live product scene: one permanent picker base per pane, optionally with its app. */
        fun FakeShell.liveProductScene(withApps: Boolean = false) {
            area = 3
            addTask(PRIMARY_ROOT, PRIMARY_PICKER_TASK, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
            if (withApps) addTask(PRIMARY_ROOT, 70, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(
                SECONDARY_ROOT,
                SECONDARY_PICKER_TASK,
                SPLIT_HOST_PACKAGE,
                SECONDARY_PICKER_ACTIVITY,
            )
            if (withApps) addTask(SECONDARY_ROOT, SECONDARY_APP_TASK, MUSIC, "$MUSIC.MainActivity")
        }
    }

    // endregion
}
