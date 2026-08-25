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
                val fact = randomFact(random)
                val where = "seed=$seed step=$step fact=$fact"
                val result = SplitAutomaton.reduce(state, fact)

                assertValid(where, result.state)
                if (result.state == state) {
                    assertSame("$where: an unchanged state must be the same instance", state, result.state)
                }
                val replay = SplitAutomaton.reduce(result.state, fact)
                assertSame("$where: replaying a settled fact moved the state", result.state, replay.state)
                assertValid("$where (replay)", replay.state)

                seen += landmarks(result.state)
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
                    "seed=$seed step=$step: disabled product planned $fact -> ${result.plans}",
                    result.plans.isEmpty(),
                )
            }
        }
    }

    private fun assertValid(where: String, state: SplitState) {
        assertEquals("$where: slots must cover both panes", SplitPane.entries.toSet(), state.slots.keys)
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
    }

    private fun randomFact(random: Random): SplitFact {
        val pane = SplitPane.entries.random(random)
        val packageName = PACKAGES.random(random)
        return when (random.nextInt(15)) {
            0 -> SplitFact.ToggleChanged(enabled = random.nextInt(4) > 0)
            1 -> SplitFact.OpenRequested
            2 -> SplitFact.SelectionRequested(pane, packageName)
            3 -> SplitFact.AppLaunchConfirmed(pane, packageName)
            4 -> SplitFact.AppClosedSettled(pane)
            5 -> SplitFact.PaneCollapsedSettled(pane)
            6 -> SplitFact.PickerPaneClosedSettled(pane)
            7 -> SplitFact.SceneEndedSettled
            8 -> SplitFact.HomeConfirmed
            9 -> SplitFact.SceneRevealed
            10 -> SplitFact.EdgeCommitConfirmed(pane)
            11 -> SplitFact.ProjectionStarted(pane)
            12 -> SplitFact.ProjectionReturned
            13 -> SplitFact.PackageRemoved(packageName)
            else -> SplitFact.BuildSceneSucceeded(BUILDS.random(random))
        }
    }

    private companion object {
        const val MUSIC = "ru.yandex.music"
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val TEMP = "com.example.temp"

        val PACKAGES = listOf(MUSIC, NAVIGATOR, TEMP, "", "   ")

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

        val LANDMARKS = setOf("split", "full", "covered", "projected", "vacancy", "pair")
    }
}
