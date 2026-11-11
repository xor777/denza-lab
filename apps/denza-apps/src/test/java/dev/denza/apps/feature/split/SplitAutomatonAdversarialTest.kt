package dev.denza.apps.feature.split

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial pressure on the automaton.
 *
 * These tests deliberately hold no expectation about which plan answers which fact: an oracle that
 * restates the reducer line by line cannot fail from a product bug (contract appendix Б.1). They
 * only assert what must be true of every reachable state, plus the two structural promises of the
 * automaton: an unchanged state comes back as the very same instance, and replaying a settled fact
 * moves nothing.
 */
class SplitAutomatonAdversarialTest {

    @Test
    fun randomFactStreamsStayValidAndReplaysAreInert() {
        val seen = mutableSetOf<String>()
        repeat(256) { seed ->
            val random = Random(seed)
            var state = SplitState()
            repeat(200) { step ->
                val fact = randomFact(random, state)
                val where = "seed=$seed step=$step fact=$fact"
                val result = SplitAutomaton.reduce(state, fact)

                assertValid(where, result.state)
                if (fact is SplitFact.PackageRemoved && state.enabled && fact.packageName.isNotBlank()) {
                    val memory = result.state.memory
                    assertTrue(
                        "$where: a removed package stayed in the memory: $memory",
                        (listOfNotNull(memory.last) + memory.recent + memory.pinned)
                            .none { pair -> fact.packageName in pair },
                    )
                }
                if (result.state == state) {
                    assertSame("$where: an unchanged state must be the same instance", state, result.state)
                }
                val replay = SplitAutomaton.reduce(result.state, fact)
                assertSame("$where: replaying a settled fact moved the state", result.state, replay.state)
                assertValid("$where (replay)", replay.state)

                seen += landmarks(result.state)
                seen += transitions(state, fact, result.state)
                state = result.state
            }
        }

        assertEquals(
            "the random stream never reached the interesting states",
            LANDMARKS,
            seen.intersect(LANDMARKS),
        )
    }

    @Test
    fun nothingReachesTheProductAfterTheToggleGoesOff() {
        repeat(128) { seed ->
            val random = Random(seed)
            var state = SplitState()
            repeat(60) { state = SplitAutomaton.reduce(state, randomFact(random)).state }

            val off = SplitAutomaton.reduce(
                SplitAutomaton.reduce(state, SplitFact.ToggleChanged(enabled = true)).state,
                SplitFact.ToggleChanged(enabled = false),
            ).state
            assertNull("seed=$seed: a disabled product kept a scene", off.scene)

            repeat(60) { step ->
                val fact = generateSequence { randomFact(random) }
                    .first { it !is SplitFact.ToggleChanged }
                val result = SplitAutomaton.reduce(off, fact)
                assertSame("seed=$seed step=$step: disabled product moved on $fact", off, result.state)
                assertTrue(
                    "seed=$seed step=$step: disabled product planned a teardown on $fact",
                    !result.teardownRequired,
                )
            }
        }
    }

    private fun assertValid(where: String, state: SplitState) {
        assertEquals("$where: slots must cover both panes", SplitPane.entries.toSet(), state.slots.keys)
        // 1.14: память держит свои правила на любом достижимом состоянии, и пара двух приложений на
        // сцене из двух панелей - всегда последняя.
        assertTrue("$where: the memory broke its rules: ${state.memory}", state.memory.isWellFormed)
        if (state.scene == SplitScene.Split) {
            SplitPair.of(state.slots)?.let { pair ->
                assertEquals("$where: the pair on screen is not the last one", pair, state.memory.last)
            }
        }
        state.slots.forEach { (pane, slot) ->
            if (slot is SplitSlot.App) {
                assertTrue("$where: $pane holds a blank package", slot.packageName.isNotBlank())
            }
        }
        if (!state.enabled) {
            assertNull("$where: a disabled product owns a scene", state.scene)
        }
        when (val scene = state.scene) {
            null -> Unit
            SplitScene.Split -> SplitPane.entries.forEach { pane ->
                assertTrue(
                    "$where: SPLIT over a closed $pane",
                    state.slot(pane) != SplitSlot.Closed,
                )
            }
            is SplitScene.Full -> assertTrue(
                "$where: FULL(${scene.pane}) over a closed pane",
                state.slot(scene.pane) != SplitSlot.Closed,
            )
        }
        val projected = state.projectedPane
        if (projected != null) {
            assertTrue(
                "$where: the projected pane does not hold an app",
                state.slot(projected) is SplitSlot.App,
            )
        }
        assertTrue(
            "$where: vacancy outside the projected pane: ${state.vacancyApp}",
            state.vacancyApp.keys.all { it == projected },
        )
        assertTrue(
            "$where: blank vacancy package",
            state.vacancyApp.values.none(String::isBlank),
        )
    }

    private fun landmarks(state: SplitState): Set<String> = buildSet {
        when (state.scene) {
            SplitScene.Split -> add("split")
            is SplitScene.Full -> add("full")
            null -> Unit
        }
        if (state.scene != null && state.visibility == SceneVisibility.COVERED) add("covered")
        if (state.projectedPane != null) add("projected")
        if (state.vacancyApp.isNotEmpty()) add("vacancy")
        if (state.slots.values.count { it is SplitSlot.App } == SplitPane.entries.size) add("pair")
        if (state.memory.recent.size >= 2) add("recent")
        if (state.memory.recent.size == SplitMemory.RECENT_LIMIT) add("recent-full")
        if (state.memory.pinned.size >= 2) add("pins")
        if (state.memory.pinned.isNotEmpty()) add("pinned")
    }

    /**
     * What one step did that no single state shows: the sixth recent pair falling off the end of a
     * full list (1.14.4), and a reorder that really changed the order of the pins (1.15.7).
     */
    private fun transitions(before: SplitState, fact: SplitFact, after: SplitState): Set<String> = buildSet {
        val was = before.memory.recent
        val now = after.memory.recent
        if (
            was.size == SplitMemory.RECENT_LIMIT &&
            now.size == SplitMemory.RECENT_LIMIT &&
            was.last() !in now &&
            was.last() != after.memory.last &&
            was.last() !in after.memory.pinned
        ) {
            add("recent-overflow")
        }
        if (
            fact is SplitFact.PinsReordered &&
            after.memory.pinned != before.memory.pinned &&
            after.memory.pinned.toSet() == before.memory.pinned.toSet()
        ) {
            add("pins-reordered")
        }
    }

    private fun randomFact(random: Random, state: SplitState = SplitState()): SplitFact {
        val pane = SplitPane.entries.random(random)
        val packageName = PACKAGES.random(random)
        val pair = SplitPair(PAIR_PACKAGES.random(random), PAIR_PACKAGES.random(random))
        val pinned = state.memory.pinned
        return when (random.nextInt(20)) {
            0 -> SplitFact.ToggleChanged(enabled = random.nextInt(4) > 0)
            1 -> SplitFact.AppLaunchConfirmed(pane, packageName)
            2 -> SplitFact.AppClosedSettled(pane)
            // Обе формы схлопывания: выживший остался в своей панели и выживший, которого
            // прошивка переставила в другую (решение владельца 2026-09-18).
            3 -> SplitFact.PaneCollapsedSettled(
                collapsed = pane,
                survivorPane = if (random.nextBoolean()) pane.other() else pane,
            )
            4 -> SplitFact.PickerPaneClosedSettled(pane)
            5 -> SplitFact.SceneEndedSettled
            6 -> SplitFact.HomeConfirmed
            7 -> SplitFact.SceneRevealed
            8 -> SplitFact.EdgeCommitConfirmed(pane)
            9 -> SplitFact.ProjectionStarted(pane)
            10 -> SplitFact.ProjectionReturned
            11 -> SplitFact.PackageRemoved(packageName)
            12 -> SplitFact.PairPinned(pair)
            13 -> SplitFact.PairUnpinned(pair)
            // The order the pill sends is the pins it shows, rearranged; now and then a malformed one.
            14 -> if (pinned.size >= 2 && random.nextInt(4) > 0) {
                SplitFact.PinsReordered(pinned.shuffled(random))
            } else {
                SplitFact.PinsReordered(listOf(pair, pair.copy(primary = pair.secondary, secondary = pair.primary)))
            }
            15 -> SplitFact.BuildSceneSucceeded(BUILDS.random(random))
            // A build of any two applications, a quarter of the stream, so that between removals
            // the memory sees enough different pairs to fill its recent list and push the oldest
            // one out (1.14.4).
            else -> SplitFact.BuildSceneSucceeded(
                mapOf(SplitPane.PRIMARY to SplitSlot.App(pair.primary), SplitPane.SECONDARY to SplitSlot.App(pair.secondary)),
            )
        }
    }

    private companion object {
        const val MUSIC = "ru.yandex.music"
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val TEMP = "com.example.temp"

        val PACKAGES = listOf(MUSIC, NAVIGATOR, TEMP, "", "   ")

        /** Pairs need more than two packages, or the recent list could never fill (1.14.4). */
        val PAIR_PACKAGES = listOf(MUSIC, NAVIGATOR, TEMP, "com.example.radio", "")

        val BUILDS: List<Map<SplitPane, SplitSlot>> = listOf(
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.App(MUSIC),
            ),
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Picker,
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                SplitPane.SECONDARY to SplitSlot.Closed,
            ),
            // Malformed builds must be survivable, not throwable.
            mapOf(SplitPane.PRIMARY to SplitSlot.Picker),
            mapOf(
                SplitPane.PRIMARY to SplitSlot.App(""),
                SplitPane.SECONDARY to SplitSlot.Picker,
            ),
            mapOf(
                SplitPane.PRIMARY to SplitSlot.Closed,
                SplitPane.SECONDARY to SplitSlot.Closed,
            ),
        )

        val LANDMARKS = setOf(
            "split", "full", "covered", "projected", "vacancy", "pair", "recent", "pinned",
            "recent-full", "recent-overflow", "pins", "pins-reordered",
        )
    }
}
