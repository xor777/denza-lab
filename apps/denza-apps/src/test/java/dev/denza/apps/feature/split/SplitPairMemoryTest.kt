package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The pair memory of contract 1.14 (ред. 2026-11-11), as the automaton keeps it: one row of 1.14 or
 * of invariant 14 per test.
 *
 * The oracle is the memory a sequence of user facts leaves behind - never the reducer restated.
 */
class SplitPairMemoryTest {

    @Test
    fun bothPanesFilledMakeTheLastPair() {
        // 1.14.2: пара становится последней, когда обе панели заняты ею
        val half = reduce(
            split(SplitSlot.Picker, SplitSlot.Picker),
            SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, MUSIC),
        )
        val whole = reduce(half, SplitFact.AppLaunchConfirmed(SplitPane.SECONDARY, NAVIGATOR))

        assertNull("одна занятая панель - ещё не пара", half.memory.last)
        assertEquals(SplitPair(MUSIC, NAVIGATOR), whole.memory.last)
        assertEquals(emptyList<SplitPair>(), whole.memory.recent)
    }

    @Test
    fun anOpenThatRebuildsThePairMakesItTheLast() {
        // 1.14.2 после перезапуска процесса: память пуста, а открытие собрало пару
        val built = reduce(
            state(),
            SplitFact.BuildSceneSucceeded(slots(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC))),
        )

        assertEquals(SplitPair(NAVIGATOR, MUSIC), built.memory.last)
    }

    @Test
    fun aPaneStillOwedItsRestoreIsNoOccupant() {
        // 1.14.2, ревью 2026-11-19 (находка 5): слот хранит выбор, но в панели пикер - пары нет,
        // и ни один следующий факт над этим долгом её не записывает
        val partial = reduce(
            state(),
            SplitFact.BuildSceneSucceeded(
                slots(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC)),
                owed = setOf(SplitPane.SECONDARY),
            ),
        )
        val covered = reduce(partial, SplitFact.HomeConfirmed)
        val revealed = reduce(covered, SplitFact.SceneRevealed)
        val otherPane = choose(revealed, SplitPane.PRIMARY, RADIO)
        val finished = choose(otherPane, SplitPane.SECONDARY, MUSIC)

        assertEquals(SplitSlot.App(MUSIC), partial.slot(SplitPane.SECONDARY))
        assertNull("частичное восстановление", partial.memory.last)
        assertNull("Home и возврат сцены", revealed.memory.last)
        assertNull("выбор в соседней панели при долге", otherPane.memory.last)
        assertEquals(
            "Музыка встала в свою панель - теперь обе заняты",
            SplitPair(RADIO, MUSIC),
            finished.memory.last,
        )
        assertEquals(emptySet<SplitPane>(), finished.owed)
    }

    @Test
    fun theSameTwoAppsTheOtherWayRoundAreAnotherPair() {
        // 1.14.1: пара - два приложения и панель каждого
        // The facts the selection's read-back settles (1.5.3): the navigator chosen in the first
        // pane is taken from the second one, which is on its picker in the same fact; then the
        // music is chosen there. The pane-by-pane steps of one selection are never a pair.
        val first = remembered(MUSIC to NAVIGATOR)
        val taken = reduce(
            first,
            SplitFact.BuildSceneSucceeded(slots(SplitSlot.App(NAVIGATOR), SplitSlot.Picker)),
        )
        val swapped = reduce(
            taken,
            SplitFact.BuildSceneSucceeded(slots(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC))),
        )

        assertEquals(SplitPair(MUSIC, NAVIGATOR), taken.memory.last)
        assertEquals(SplitPair(NAVIGATOR, MUSIC), swapped.memory.last)
        assertEquals(
            "прямая пара осталась среди недавних - отдельной парой, и только она",
            listOf(SplitPair(MUSIC, NAVIGATOR)),
            swapped.memory.recent,
        )
    }

    @Test
    fun aReplacedPairBecomesTheFirstRecentOne() {
        // 1.14.4: смена последней сдвигает прежнюю в недавние, первой
        val before = remembered(MUSIC to NAVIGATOR)

        val after = choose(before, SplitPane.SECONDARY, RADIO)

        assertEquals(SplitPair(MUSIC, RADIO), after.memory.last)
        assertEquals(listOf(SplitPair(MUSIC, NAVIGATOR)), after.memory.recent)
    }

    @Test
    fun aPairThatComesBackLeavesTheRecentListAndIsNeverInItTwice() {
        // 1.14.1, 2.5: недавние без повторов и без последней
        val a = remembered(MUSIC to NAVIGATOR)
        val b = choose(a, SplitPane.SECONDARY, RADIO)
        val c = choose(b, SplitPane.SECONDARY, MAPS)
        val back = choose(c, SplitPane.SECONDARY, NAVIGATOR)

        assertEquals(SplitPair(MUSIC, NAVIGATOR), back.memory.last)
        assertEquals(
            listOf(SplitPair(MUSIC, MAPS), SplitPair(MUSIC, RADIO)),
            back.memory.recent,
        )
    }

    @Test
    fun theSixthRecentPairPushesTheOldestOut() {
        // 1.14.4: шестая недавняя вытесняет самую старую
        var state = remembered(MUSIC to NAVIGATOR)
        val others = listOf(RADIO, MAPS, TEMP, "a.b.c", "d.e.f", "g.h.i")
        others.forEach { app -> state = choose(state, SplitPane.SECONDARY, app) }

        assertEquals(SplitPair(MUSIC, "g.h.i"), state.memory.last)
        assertEquals(
            listOf("d.e.f", "a.b.c", TEMP, MAPS, RADIO).map { SplitPair(MUSIC, it) },
            state.memory.recent,
        )
    }

    @Test
    fun aPinnedPairThatStopsBeingTheLastDoesNotEnterTheRecentList() {
        // 2.5: недавние никогда не держат закреплённую
        val pinned = reduce(remembered(MUSIC to NAVIGATOR), SplitFact.PairPinned(SplitPair(MUSIC, NAVIGATOR)))

        val replaced = choose(pinned, SplitPane.SECONDARY, RADIO)

        assertEquals(SplitPair(MUSIC, RADIO), replaced.memory.last)
        assertEquals(emptyList<SplitPair>(), replaced.memory.recent)
        assertEquals(listOf(SplitPair(MUSIC, NAVIGATOR)), replaced.memory.pinned)
    }

    @Test
    fun whatTheUserDidNotChooseLeavesTheMemoryAlone() {
        // 1.14.3, 1.14.6: закрытие на глазах, схлопывание, конец сцены, Home, выключение
        val pair = SplitPair(MUSIC, NAVIGATOR)
        val live = remembered(MUSIC to NAVIGATOR)
        val expected = SplitMemory(last = pair)

        val closed = reduce(live, SplitFact.AppClosedSettled(SplitPane.SECONDARY))
        val collapsed = reduce(
            live,
            SplitFact.PaneCollapsedSettled(collapsed = SplitPane.PRIMARY, survivorPane = SplitPane.SECONDARY),
        )
        val ended = reduce(live, SplitFact.SceneEndedSettled)
        val home = reduce(live, SplitFact.HomeConfirmed)
        val off = reduce(live, SplitFact.ToggleChanged(enabled = false))
        val backOn = reduce(off, SplitFact.ToggleChanged(enabled = true))

        listOf(closed, collapsed, ended, home, off, backOn).forEach { after ->
            assertEquals(expected, after.memory)
        }
        assertEquals(
            "панель на пикере, пара в памяти (1.14.6)",
            SplitSlot.Picker,
            closed.slot(SplitPane.SECONDARY),
        )
    }

    @Test
    fun theVacancyOccupantOfAProjectedPaneIsNotAChoice() {
        // раздел 5 «К 1.10»: слот проецируемой панели - навигатор, а не временный жилец
        val projected = reduce(remembered(NAVIGATOR to MUSIC), SplitFact.ProjectionStarted(SplitPane.PRIMARY))
        val occupied = reduce(projected, SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, TEMP))
        val neighbour = choose(occupied, SplitPane.SECONDARY, RADIO)

        assertEquals(SplitPair(NAVIGATOR, MUSIC), occupied.memory.last)
        assertEquals(SplitPair(NAVIGATOR, RADIO), neighbour.memory.last)
    }

    @Test
    fun aPinJoinsTheEndOfThePinsAndLeavesTheRecentList() {
        // 1.15.7: звезда у последней или у недавней пары
        val a = remembered(MUSIC to NAVIGATOR)
        val b = choose(a, SplitPane.SECONDARY, RADIO)
        val c = choose(b, SplitPane.SECONDARY, MAPS)

        val pinnedLast = reduce(c, SplitFact.PairPinned(SplitPair(MUSIC, MAPS)))
        val pinnedRecent = reduce(pinnedLast, SplitFact.PairPinned(SplitPair(MUSIC, NAVIGATOR)))

        assertEquals(SplitPair(MUSIC, MAPS), pinnedRecent.memory.last)
        assertEquals(listOf(SplitPair(MUSIC, RADIO)), pinnedRecent.memory.recent)
        assertEquals(
            listOf(SplitPair(MUSIC, MAPS), SplitPair(MUSIC, NAVIGATOR)),
            pinnedRecent.memory.pinned,
        )
    }

    @Test
    fun anUnpinnedPairBecomesTheFirstRecentOneUnlessItIsTheLast() {
        // 1.14.4, 1.15.8
        var state = remembered(MUSIC to NAVIGATOR)
        state = reduce(state, SplitFact.PairPinned(SplitPair(MUSIC, NAVIGATOR)))
        listOf(RADIO, MAPS, TEMP, "a.b.c", "d.e.f", "g.h.i").forEach { app ->
            state = choose(state, SplitPane.SECONDARY, app)
        }
        state = reduce(state, SplitFact.PairPinned(SplitPair(MUSIC, "g.h.i")))
        assertEquals(5, state.memory.recent.size)

        val unpinnedOld = reduce(state, SplitFact.PairUnpinned(SplitPair(MUSIC, NAVIGATOR)))
        val unpinnedLast = reduce(state, SplitFact.PairUnpinned(SplitPair(MUSIC, "g.h.i")))

        assertEquals(SplitPair(MUSIC, NAVIGATOR), unpinnedOld.memory.recent.first())
        assertEquals("пять недавних, самая старая ушла", 5, unpinnedOld.memory.recent.size)
        assertEquals(state.memory.recent.dropLast(1), unpinnedOld.memory.recent.drop(1))
        assertEquals(listOf(SplitPair(MUSIC, "g.h.i")), unpinnedOld.memory.pinned)
        assertEquals("последняя не встаёт в недавние", state.memory.recent, unpinnedLast.memory.recent)
        assertEquals(SplitPair(MUSIC, "g.h.i"), unpinnedLast.memory.last)
        assertEquals(listOf(SplitPair(MUSIC, NAVIGATOR)), unpinnedLast.memory.pinned)
    }

    @Test
    fun theUserReordersThePins() {
        // 1.15.8
        val pins = listOf(SplitPair(MUSIC, NAVIGATOR), SplitPair(MAPS, RADIO), SplitPair(TEMP, MUSIC))
        val pinned = state(memory = SplitMemory(pinned = pins))

        val reordered = reduce(pinned, SplitFact.PinsReordered(pins.reversed()))

        assertEquals(pins.reversed(), reordered.memory.pinned)
    }

    @Test
    fun aRemovedPackageLeavesEveryPlaceTheMemoryHasForIt() {
        // 1.14.4, сц. 45: пары с удалённым приложением исчезают отовсюду
        val memory = SplitMemory(
            last = SplitPair(MUSIC, NAVIGATOR),
            recent = listOf(SplitPair(RADIO, MAPS), SplitPair(NAVIGATOR, RADIO)),
            pinned = listOf(SplitPair(MAPS, NAVIGATOR), SplitPair(MAPS, RADIO)),
        )
        val before = state(memory = memory)

        val after = reduce(before, SplitFact.PackageRemoved(NAVIGATOR))

        assertNull(after.memory.last)
        assertEquals(listOf(SplitPair(RADIO, MAPS)), after.memory.recent)
        assertEquals(listOf(SplitPair(MAPS, RADIO)), after.memory.pinned)
    }

    @Test
    fun aDisabledProductKeepsTheMemoryAndHearsNoPinFact() {
        // инвариант 1: выключенный продукт слышит только тумблер; 1.14.3: выключение не стирает
        val memory = SplitMemory(
            last = SplitPair(MUSIC, NAVIGATOR),
            recent = listOf(SplitPair(RADIO, MAPS)),
            pinned = listOf(SplitPair(MAPS, NAVIGATOR)),
        )
        val disabled = state(enabled = false, memory = memory)

        listOf(
            SplitFact.PairPinned(SplitPair(RADIO, MAPS)),
            SplitFact.PairUnpinned(SplitPair(MAPS, NAVIGATOR)),
            SplitFact.PinsReordered(emptyList()),
            SplitFact.PackageRemoved(MUSIC),
        ).forEach { fact ->
            assertSame("$fact", disabled, SplitAutomaton.reduce(disabled, fact).state)
        }
    }

    @Test
    fun impossibleMemoryFactsReturnTheSameStateInstance() {
        // инвариант 8: факт, которого пилюля не могла послать, ничего не двигает
        val memory = SplitMemory(
            last = SplitPair(MUSIC, NAVIGATOR),
            recent = listOf(SplitPair(RADIO, MAPS)),
            pinned = listOf(SplitPair(MAPS, NAVIGATOR), SplitPair(TEMP, MUSIC)),
        )
        val before = state(memory = memory)

        listOf(
            SplitFact.PairPinned(SplitPair(TEMP, RADIO)),
            SplitFact.PairPinned(SplitPair(MAPS, NAVIGATOR)),
            SplitFact.PairPinned(SplitPair("", NAVIGATOR)),
            SplitFact.PairUnpinned(SplitPair(RADIO, MAPS)),
            SplitFact.PairUnpinned(SplitPair(MUSIC, NAVIGATOR)),
            SplitFact.PinsReordered(memory.pinned),
            SplitFact.PinsReordered(memory.pinned.take(1)),
            SplitFact.PinsReordered(listOf(SplitPair(MAPS, NAVIGATOR), SplitPair(MAPS, NAVIGATOR))),
            SplitFact.PinsReordered(listOf(SplitPair(TEMP, MUSIC), SplitPair(RADIO, MAPS))),
            SplitFact.PackageRemoved("com.example.unrelated"),
        ).forEach { fact ->
            val result = SplitAutomaton.reduce(before, fact)
            assertSame("state moved on $fact", before, result.state)
        }
    }

    @Test
    fun aChoiceInAnOwedPaneReplacesTheApplicationItWasOwed() {
        // «К 1.14»: выбор в панели незавершённого восстановления - обычный SELECT; повтор и
        // поздний факт ничего не двигают
        val owed = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val chosen = reduce(owed, SplitFact.AppLaunchConfirmed(SplitPane.SECONDARY, RADIO))
        val repeated = SplitAutomaton.reduce(chosen, SplitFact.AppLaunchConfirmed(SplitPane.SECONDARY, RADIO))
        val ended = reduce(chosen, SplitFact.SceneEndedSettled)
        val late = SplitAutomaton.reduce(ended, SplitFact.AppLaunchConfirmed(SplitPane.SECONDARY, MAPS))

        assertEquals(SplitSlot.App(RADIO), chosen.slot(SplitPane.SECONDARY))
        assertEquals(SplitPair(MUSIC, RADIO), chosen.memory.last)
        assertSame("повтор того же подтверждения", chosen, repeated.state)
        assertSame("подтверждение после конца сцены", ended, late.state)
    }

    private fun remembered(pair: Pair<String, String>): SplitState =
        reduce(
            state(),
            SplitFact.BuildSceneSucceeded(slots(SplitSlot.App(pair.first), SplitSlot.App(pair.second))),
        )

    private fun choose(state: SplitState, pane: SplitPane, packageName: String): SplitState =
        reduce(state, SplitFact.AppLaunchConfirmed(pane, packageName))

    private fun reduce(state: SplitState, fact: SplitFact): SplitState =
        SplitAutomaton.reduce(state, fact).state

    private companion object {
        const val MUSIC = "ru.yandex.music"
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val RADIO = "com.example.radio"
        const val MAPS = "ru.yandex.yandexmaps"
        const val TEMP = "com.example.temp"

        fun slots(primary: SplitSlot, secondary: SplitSlot): Map<SplitPane, SplitSlot> =
            mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary)

        fun state(
            enabled: Boolean = true,
            slots: Map<SplitPane, SplitSlot> = slots(SplitSlot.Closed, SplitSlot.Closed),
            scene: SplitScene? = null,
            memory: SplitMemory = SplitMemory(),
        ) = SplitState(enabled = enabled, slots = slots, scene = scene, memory = memory)

        fun split(primary: SplitSlot, secondary: SplitSlot) =
            state(slots = slots(primary, secondary), scene = SplitScene.Split)
    }
}
