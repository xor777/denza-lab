package dev.denza.apps.feature.split

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The pill's three inputs on the fake car (contract 1.15, «К 1.15», ред. 2026-11-19; section 11
 * scenarios 40 and 49-58): a pair chosen is one operation over both panes that commits pane by
 * pane, an app chosen goes into the pane it names and leaves the other alone, the star is a fact
 * committed with the snapshot, and what the pill reads arrives whole and in order.
 *
 * The oracle is what reached the car and the store, never the operations restated.
 */
class SplitPillOperationsTest {

    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun closeCars() = cars.forEach(SplitCarFixture::close)

    // region pairs

    @Test
    fun aPairChosenPutsBothAppsOnScreenInOneOperation() {
        // 49: обе панели меняются, одна операция, один коммит; пара становится последней
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        val committed = car.store.commits
        val results = Collections.synchronizedList(mutableListOf<SplitActionResult>())

        core.choosePair(SplitPair(WAZE, RADIO), results::add)
        car.barrier()

        assertEquals(WAZE, topPackage(car, PRIMARY_ROOT))
        assertEquals(RADIO, topPackage(car, SECONDARY_ROOT))
        assertEquals(
            mapOf(SplitPane.PRIMARY to SplitSlot.App(WAZE), SplitPane.SECONDARY to SplitSlot.App(RADIO)),
            car.store.load().slots,
        )
        assertEquals(SplitPair(WAZE, RADIO), car.store.load().memory.last)
        assertEquals("one operation, one commit", committed + 1, car.store.commits)
        assertEquals(listOf(SplitActionResult.SETTLED), results.toList())
        assertEquals("the replaced apps are alive in the background", FULL_ROOT, car.fake.taskRoot(PRIMARY_APP_TASK))
        assertEquals(FULL_ROOT, car.fake.taskRoot(SECONDARY_APP_TASK))
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
    }

    @Test
    fun theAppAlreadyInItsPaneIsNotTouched() {
        // 40: в паре навигатор уже стоит слева - его задача остаётся той же, его не запускают
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)

        core.choosePair(SplitPair(NAVIGATOR, WAZE))
        car.barrier()

        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(WAZE, topPackage(car, SECONDARY_ROOT))
        assertFalse(
            "nothing started the navigator again",
            car.commands().any { it.startsWith("am start") && it.contains(NAVIGATOR) },
        )
        assertFalse(
            "nothing moved its task",
            car.commands().any { it.startsWith("am stack move-task $PRIMARY_APP_TASK ") },
        )
        assertEquals("music went to the background alive", FULL_ROOT, car.fake.taskRoot(SECONDARY_APP_TASK))
        assertEquals(SplitPair(NAVIGATOR, WAZE), car.store.load().memory.last)
    }

    @Test
    fun aRunningAppOfThePairIsBroughtIntoItsPane() {
        // 50: одно приложение пары уже живёт вне панелей (тёплое), другое холодное
        val car = car(
            FakeShell().apply {
                liveProductScene(withApps = true)
                addTask(FULL_ROOT, WARM_TASK, WAZE, "$WAZE.MainActivity")
            },
        )
        val core = living(car, APP_PAIR)

        core.choosePair(SplitPair(WAZE, RADIO))
        car.barrier()

        assertEquals("the running task is the one brought in", WARM_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(RADIO, topPackage(car, SECONDARY_ROOT))
        assertEquals(
            mapOf(SplitPane.PRIMARY to SplitSlot.App(WAZE), SplitPane.SECONDARY to SplitSlot.App(RADIO)),
            car.store.load().slots,
        )
    }

    @Test
    fun theSwapOfThePairOnScreenMovesTheTwoLiveTasks() {
        // 56: «Музыка | Навигатор» поверх «Навигатор | Музыка» - те же две задачи меняются местами,
        // ни одного запуска
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        car.clearCommands()

        core.choosePair(SplitPair(MUSIC, NAVIGATOR))
        car.barrier()

        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertFalse("no launcher start", car.commands().any { it.startsWith("am start") })
        assertEquals(car.fake.rootBounds(PRIMARY_ROOT), car.fake.taskBounds(SECONDARY_APP_TASK))
        assertEquals(car.fake.rootBounds(SECONDARY_ROOT), car.fake.taskBounds(PRIMARY_APP_TASK))
        assertEquals(
            mapOf(SplitPane.PRIMARY to SplitSlot.App(MUSIC), SplitPane.SECONDARY to SplitSlot.App(NAVIGATOR)),
            car.store.load().slots,
        )
        assertEquals(SplitPair(MUSIC, NAVIGATOR), car.store.load().memory.last)
    }

    @Test
    fun aPairOfOneAppInBothPanesKeepsTheCopyAlreadyStanding() {
        // 56: «Музыка | Музыка» поверх «Навигатор | Музыка» - правая копия стоит, левой нужна
        // вторая, а не та же самая
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)

        core.choosePair(SplitPair(MUSIC, MUSIC))
        car.barrier()

        assertEquals("the standing copy is untouched", SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        val left = car.fake.topTaskId(PRIMARY_ROOT)
        assertNotEquals(SECONDARY_APP_TASK, left)
        assertEquals(MUSIC, left?.let(car.fake::taskPackage))
        assertEquals(
            mapOf(SplitPane.PRIMARY to SplitSlot.App(MUSIC), SplitPane.SECONDARY to SplitSlot.App(MUSIC)),
            car.store.load().slots,
        )
        assertEquals(emptySet<SplitPane>(), core.currentState().owed)
    }

    @Test
    fun aPairThatPlacedOneAppCommitsItAndOwesTheOther() {
        // 53 (ред. 2026-11-19), 1.3.11: правое не встало - левая панель с новым приложением,
        // правая на пикере, выбор за ней сохранён; один тап ряда починки заканчивает пару
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        val lastBefore = car.store.load().memory.last
        car.fake.swallowLaunchOf += RADIO

        core.choosePair(SplitPair(WAZE, RADIO))
        car.barrier()

        assertEquals(WAZE, topPackage(car, PRIMARY_ROOT))
        assertEquals(SECONDARY_PICKER_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertEquals(
            mapOf(SplitPane.PRIMARY to SplitSlot.App(WAZE), SplitPane.SECONDARY to SplitSlot.App(RADIO)),
            car.store.load().slots,
        )
        assertEquals(setOf(SplitPane.SECONDARY), core.currentState().owed)
        assertEquals("a pair that never stood is not the last one", lastBefore, car.store.load().memory.last)

        car.fake.swallowLaunchOf.clear()
        core.selectIntoPane(SplitPane.SECONDARY, RADIO)
        car.barrier()

        assertEquals(RADIO, topPackage(car, SECONDARY_ROOT))
        assertEquals(emptySet<SplitPane>(), core.currentState().owed)
        assertEquals(SplitPair(WAZE, RADIO), car.store.load().memory.last)
    }

    @Test
    fun aPairThatPlacedNothingPutsThePairOnScreenBackAsItWas() {
        // 53, инвариант 14: ни одно не встало - обе задачи вернулись наверх своих панелей и
        // своего размера, слоты и память те же; повтор, который может встать, встаёт
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        car.fake.preserveBoundsOnShellMove = true
        car.fake.swallowLaunchOf += setOf(WAZE, RADIO)
        val committed = car.store.commits
        val stored = car.store.load()
        val state = core.currentState()
        car.clearCommands()

        core.choosePair(SplitPair(WAZE, RADIO))
        car.barrier()

        val commands = car.commands()
        val evictLeft = commands.indexOfFirst { it.startsWith("am stack move-task $PRIMARY_APP_TASK $FULL_ROOT ") }
        val evictRight = commands.indexOfFirst { it.startsWith("am stack move-task $SECONDARY_APP_TASK $FULL_ROOT ") }
        val launch = commands.indexOfFirst { it.startsWith("am start") && it.contains(WAZE) }
        val backLeft = commands.indexOfFirst { it == "am stack move-task $PRIMARY_APP_TASK $PRIMARY_ROOT true" }
        val backRight = commands.indexOfFirst { it == "am stack move-task $SECONDARY_APP_TASK $SECONDARY_ROOT true" }
        assertTrue("both evicted, then the launch, then both inverses: $commands", evictLeft in 0 until launch && evictRight in 0 until launch)
        assertTrue(backLeft > launch && backRight > launch)
        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertEquals("C1: back at its pane's size", car.fake.rootBounds(PRIMARY_ROOT), car.fake.taskBounds(PRIMARY_APP_TASK))
        assertEquals(car.fake.rootBounds(SECONDARY_ROOT), car.fake.taskBounds(SECONDARY_APP_TASK))
        assertEquals(committed, car.store.commits)
        assertEquals(stored, car.store.load())
        assertEquals(state, core.currentState())

        car.fake.swallowLaunchOf.clear()
        core.choosePair(SplitPair(WAZE, RADIO))
        car.barrier()
        assertEquals(WAZE, topPackage(car, PRIMARY_ROOT))
        assertEquals(RADIO, topPackage(car, SECONDARY_ROOT))
    }

    @Test
    fun aPairThatCannotBeLaunchedIsRefusedWithNothingSent() {
        // 57, A3: Waze стоит, но лаунчер его не запускает - тап не трогает ни одной панели
        val catalog = object : SplitLaunchCatalog {
            override fun resolve(packageName: String) =
                if (packageName == WAZE) null else FakeCatalog.resolve(packageName)

            override fun presence(packageName: String) = SplitPackagePresence.INSTALLED
        }
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR, catalog = catalog)
        val committed = car.store.commits
        car.clearCommands()

        core.choosePair(SplitPair(WAZE, RADIO))
        core.selectIntoPane(SplitPane.SECONDARY, WAZE)
        car.barrier()

        assertEquals(emptyList<String>(), car.mutations())
        assertEquals(committed, car.store.commits)
        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
    }

    @Test
    fun aPairOfAnUninstalledAppIsRefusedAndItsPairsForgotten() {
        // 57, раздел 6: удаления никто не слышал - выбор отказан, но удаление записано своим коммитом
        val gone = "com.example.gone"
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val pinned = SplitPair(gone, RADIO)
        val core = living(car, APP_PAIR, SplitMemory(last = SplitPair(NAVIGATOR, MUSIC), pinned = listOf(pinned)))
        val committed = car.store.commits
        car.clearCommands()

        core.choosePair(pinned)
        car.barrier()

        assertEquals(emptyList<String>(), car.mutations())
        assertEquals(emptyList<SplitPair>(), car.store.load().memory.pinned)
        assertEquals("one commit, the uninstall's", committed + 1, car.store.commits)
        assertEquals(APP_PAIR, car.store.load().slots)
    }

    // endregion

    // region one app into one pane

    @Test
    fun anAppChosenForAnOccupiedPaneReplacesOnlyThatPane() {
        // 51: справа музыка уступает место; навигатор слева не тронут ни одной командой
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        car.clearCommands()

        core.selectIntoPane(SplitPane.SECONDARY, WAZE)
        car.barrier()

        assertEquals(WAZE, topPackage(car, SECONDARY_ROOT))
        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertTrue("music still runs", car.fake.hasTask(SECONDARY_APP_TASK))
        assertFalse("but not in the pane", car.fake.taskRoot(SECONDARY_APP_TASK) == SECONDARY_ROOT)
        val peer = car.mutations().filter { command ->
            command.contains(" $PRIMARY_APP_TASK") ||
                command.contains(" $PRIMARY_PICKER_TASK") ||
                command.contains("START_IVI_PRIMARY") ||
                Regex("""^am stack move-task \d+ $PRIMARY_ROOT """).containsMatchIn(command)
        }
        assertEquals("no command reached the other pane", emptyList<String>(), peer)
        assertEquals(
            mapOf(SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR), SplitPane.SECONDARY to SplitSlot.App(WAZE)),
            car.store.load().slots,
        )
        assertEquals(SplitPair(NAVIGATOR, WAZE), car.store.load().memory.last)
    }

    @Test
    fun anAppChosenForTheEmptyPaneFillsIt() {
        // 52: ряд починки - пустая левая панель получает приложение, пара снова целая
        val car = car(
            FakeShell().apply {
                liveProductScene()
                addTask(SECONDARY_ROOT, SECONDARY_APP_TASK, MUSIC, "$MUSIC.MainActivity")
            },
        )
        val core = living(car, mapOf(SplitPane.PRIMARY to SplitSlot.Picker, SplitPane.SECONDARY to SplitSlot.App(MUSIC)))

        core.selectIntoPane(SplitPane.PRIMARY, NAVIGATOR)
        car.barrier()

        assertEquals(NAVIGATOR, topPackage(car, PRIMARY_ROOT))
        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(SplitPair(NAVIGATOR, MUSIC), car.store.load().memory.last)
    }

    @Test
    fun anAppTheFirmwarePutsInTheOtherPaneIsUndoneAndBothPanesStay() {
        // 58, A1: правая панель названа, прошивка ставит Waze влево - запуск снят до того, как
        // навигатор слева тронут; музыка снова справа
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        car.fake.firmwareChoosesRootFor[WAZE] = PRIMARY_ROOT
        val committed = car.store.commits
        val stored = car.store.load()

        core.selectIntoPane(SplitPane.SECONDARY, WAZE)
        car.barrier()

        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertTrue("the navigator never left its pane", car.commands().none { it.startsWith("am stack move-task $PRIMARY_APP_TASK ") })
        assertFalse(car.fake.hasPackage(PRIMARY_ROOT, WAZE))
        assertEquals(committed, car.store.commits)
        assertEquals(stored, car.store.load())
    }

    @Test
    fun aFailedChoiceForAnOccupiedPanePutsItsAppBack() {
        // 53, инвариант 14: запуск не удался - музыка снова наверху своей панели и своего размера,
        // слоты и память те же; повтор с другим приложением встаёт
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        car.fake.preserveBoundsOnShellMove = true
        car.fake.swallowLaunchOf += WAZE
        val committed = car.store.commits
        val stored = car.store.load()
        val results = Collections.synchronizedList(mutableListOf<SplitActionResult>())
        car.clearCommands()

        core.selectIntoPane(SplitPane.SECONDARY, WAZE, results::add)
        car.barrier()

        val commands = car.commands()
        val evict = commands.indexOfFirst { it.startsWith("am stack move-task $SECONDARY_APP_TASK $FULL_ROOT ") }
        val launch = commands.indexOfFirst { it.startsWith("am start") && it.contains(WAZE) }
        val back = commands.indexOfFirst { it == "am stack move-task $SECONDARY_APP_TASK $SECONDARY_ROOT true" }
        assertTrue("evicted, launched, put back: $commands", evict in 0 until launch && back > launch)
        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertEquals(car.fake.rootBounds(SECONDARY_ROOT), car.fake.taskBounds(SECONDARY_APP_TASK))
        assertEquals(PRIMARY_APP_TASK, car.fake.topTaskId(PRIMARY_ROOT))
        assertEquals(committed, car.store.commits)
        assertEquals(stored, car.store.load())
        assertEquals(listOf(SplitActionResult.SETTLED), results.toList())
        assertEquals("and the card says nothing", "", core.snapshot().message)

        core.selectIntoPane(SplitPane.SECONDARY, RADIO)
        car.barrier()
        assertEquals(RADIO, topPackage(car, SECONDARY_ROOT))
    }

    @Test
    fun aBorrowedTaskIsTakenBackOutOfThePaneWhenTheSelectionUnwinds() {
        // A10: Waze уже жил в полноэкранном корне; выбор не записался - Waze уходит из панели живым,
        // музыка возвращается
        val car = car(
            FakeShell().apply {
                liveProductScene(withApps = true)
                addTask(FULL_ROOT, WARM_TASK, WAZE, "$WAZE.MainActivity")
            },
        )
        val core = living(car, APP_PAIR)
        car.store.accept = false

        core.selectIntoPane(SplitPane.SECONDARY, WAZE)
        car.barrier()

        assertEquals(SECONDARY_APP_TASK, car.fake.topTaskId(SECONDARY_ROOT))
        assertTrue("alive", car.fake.hasTask(WARM_TASK))
        assertFalse("out of the pane", car.fake.taskRoot(WARM_TASK) in setOf(PRIMARY_ROOT, SECONDARY_ROOT))
    }

    // endregion

    // region the star

    @Test
    fun theStarIsAFactCommittedWithTheSnapshot() {
        // 54, 1.15.7: звезда на паре на экране - закреплена в памяти, снята - снова недавняя;
        // ни одной команды машине, по коммиту на каждый переход
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR, SplitMemory(last = SplitPair(NAVIGATOR, MUSIC)))
        val pair = SplitPair(NAVIGATOR, MUSIC)
        val committed = car.store.commits
        car.clearCommands()

        core.setPairPinned(pair, pinned = true)
        car.barrier()
        assertEquals(listOf(pair), car.store.load().memory.pinned)
        assertEquals(committed + 1, car.store.commits)
        assertEquals("no command reached the car", emptyList<String>(), car.commands())

        core.setPairPinned(pair, pinned = false)
        car.barrier()
        assertEquals(emptyList<SplitPair>(), car.store.load().memory.pinned)
        assertEquals(pair, car.store.load().memory.last)
        assertEquals(committed + 2, car.store.commits)
        assertEquals(emptyList<String>(), car.commands())
    }

    @Test
    fun aStarForAPairNotOnTheScreenWritesNothing() {
        // A13: звезда пары, которой на экране уже нет, и звезда после Home ничего не пишут
        // The open builds the scene, so the gate is ours and Home is confirmed (1.9.1).
        val car = car(FakeShell())
        val other = SplitPair(WAZE, RADIO)
        val core = living(car, APP_PAIR, SplitMemory(last = SplitPair(NAVIGATOR, MUSIC), recent = listOf(other)))
        val committed = car.store.commits

        core.setPairPinned(other, pinned = true)
        car.barrier()
        assertEquals(committed, car.store.commits)

        car.fake.area = 0
        core.homeKeyPressed()
        car.barrier()
        val covered = car.store.commits
        car.clearCommands()
        core.setPairPinned(SplitPair(NAVIGATOR, MUSIC), pinned = true)
        car.barrier()
        assertEquals(covered, car.store.commits)
        assertEquals(emptyList<SplitPair>(), car.store.load().memory.pinned)
        assertEquals(emptyList<String>(), car.commands())
    }

    // endregion

    // region no scene, a cover in between

    @Test
    fun aChoiceWithNoSceneOnScreenTouchesNothing() {
        // 55, 1.15.9: пилюли нет без сцены на экране; запоздалый тап после Home ничего не двигает
        // The open builds the scene, so the gate is ours and Home is confirmed (1.9.1).
        val car = car(FakeShell())
        val core = living(car, APP_PAIR)
        car.fake.area = 0
        core.homeKeyPressed()
        car.barrier()
        assertEquals(SceneVisibility.COVERED, core.currentState().visibility)
        car.clearCommands()
        val committed = car.store.commits

        core.choosePair(SplitPair(WAZE, RADIO))
        core.selectIntoPane(SplitPane.PRIMARY, WAZE)
        car.barrier()

        assertEquals(emptyList<String>(), car.commands())
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(committed, car.store.commits)
    }

    @Test
    fun inputsQueuedBeforeACoverSendNothingWhenTheyRun() {
        // 55, A7: тапы встали в очередь, потом пришло накрытие - ни одна команда, ни аренда, ни гейт
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR, SplitMemory(last = SplitPair(NAVIGATOR, MUSIC)))
        val committed = car.store.commits
        val stored = car.store.load()
        car.clearCommands()
        val hold = car.hold()

        core.choosePair(SplitPair(WAZE, RADIO))
        core.selectIntoPane(SplitPane.SECONDARY, WAZE)
        core.setPairPinned(SplitPair(NAVIGATOR, MUSIC), pinned = true)
        car.fake.area = 4
        core.areaChanged(4)
        hold.release()
        car.barrier()

        assertEquals(emptyList<String>(), car.commands())
        assertEquals(committed, car.store.commits)
        assertEquals(stored, car.store.load())
    }

    // endregion

    // region what the pill reads

    @Test
    fun theReadingsArriveWholeAndInOrderTheFirstOneIncluded() {
        // A14: одна ревизия на чтение, растёт строго; первое чтение идёт тем же путём
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR, SplitMemory(last = SplitPair(NAVIGATOR, MUSIC)))
        val seen = Collections.synchronizedList(mutableListOf<SplitSettled>())

        core.observeSettled(seen::add)
        assertEquals("the first reading is handed at once", 1, seen.size)
        core.setPairPinned(SplitPair(NAVIGATOR, MUSIC), pinned = true)
        car.barrier()

        val revisions = seen.map(SplitSettled::revision)
        assertEquals(revisions.sorted().distinct(), revisions)
        assertEquals(listOf(SplitPair(NAVIGATOR, MUSIC)), seen.last().state.memory.pinned)
        assertEquals(car.fake.topTaskId(PRIMARY_ROOT), seen.last().live[SplitPane.PRIMARY]?.appTaskId)
    }

    @Test
    fun offWithdrawsThePillBeforeTheTeardownRuns() {
        // B4: пилюля уходит, как только выключение попрошено, а не когда сцена разобрана
        val car = car(FakeShell().apply { liveProductScene(withApps = true) })
        val core = living(car, APP_PAIR)
        val seen = Collections.synchronizedList(mutableListOf<SplitSettled>())
        core.observeSettled(seen::add)
        val hold = car.hold()

        core.setEnabled(false)

        assertTrue("withdrawn while the worker is still busy", seen.last().withdrawn)
        hold.release()
        car.barrier()
        assertTrue(seen.last().withdrawn)
    }

    @Test
    fun theDockButtonOverAFullPairAsksThePillToOpen() {
        // 1.15.2, 1.15.10: кнопка по стоящей паре и после Home - одна просьба открыть пилюлю;
        // после Home с задержкой доски
        // The open builds the scene, so the gate is ours and Home is confirmed (1.9.1).
        val car = car(FakeShell())
        val core = living(car, APP_PAIR)
        val seen = Collections.synchronizedList(mutableListOf<SplitSettled>())
        core.observeSettled(seen::add)

        core.openPickerSession()
        car.barrier()
        val onScreen = seen.mapNotNull(SplitSettled::open)
        assertEquals(1, onScreen.size)
        assertFalse(onScreen.single().afterHome)

        car.fake.area = 0
        core.homeKeyPressed()
        car.barrier()
        core.openPickerSession()
        car.barrier()
        val requests = seen.mapNotNull(SplitSettled::open)
        assertEquals(2, requests.size)
        assertTrue(requests.last().afterHome)
        assertTrue("one id per request", requests.last().id > requests.first().id)
    }

    @Test
    fun theDockButtonThatMakesTheSceneAnewDoesNotOpenThePill() {
        val car = car(FakeShell())
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        val seen = Collections.synchronizedList(mutableListOf<SplitSettled>())
        core.observeSettled(seen::add)

        core.openPickerSession()
        car.barrier()

        assertNull(seen.firstNotNullOfOrNull(SplitSettled::open))
    }

    // endregion

    private fun car(fake: FakeShell): SplitCarFixture = SplitCarFixture(fake).also(cars::add)

    /** A product scene on screen, adopted from the car, with [slots] and [memory] durable. */
    private fun living(
        car: SplitCarFixture,
        slots: Map<SplitPane, SplitSlot>,
        memory: SplitMemory = SplitMemory(),
        catalog: SplitLaunchCatalog = FakeCatalog,
    ): SplitCoordinatorCore {
        val core = car.core(SplitDurable(enabled = true, slots = slots, memory = memory), catalog = catalog)
        core.initialize {}
        core.openPickerSession()
        car.barrier()
        return core
    }

    private fun topPackage(car: SplitCarFixture, rootId: Int): String? =
        car.fake.topTaskId(rootId)?.let(car.fake::taskPackage)

    private companion object {
        const val WARM_TASK = 120
    }
}
