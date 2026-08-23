package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transition table of the contract automaton: one row of docs/split-screen-product-contract.md
 * section 1 per test.
 */
class SplitAutomatonTest {

    @Test
    fun enablingTheToggleOnlyArmsTheProduct() {
        // контракт 1.2.1
        val result = SplitAutomaton.reduce(SplitState(), SplitFact.ToggleChanged(enabled = true))

        assertTrue(result.state.enabled)
        assertNull(result.state.scene)
        assertEquals(emptyList<SplitPlan>(), result.plans)
    }

    @Test
    fun disablingWithoutASceneKeepsTheSelectionAndPlansNothing() {
        // контракт 1.2.2
        val armed = state(primary = SplitSlot.App(MUSIC), secondary = SplitSlot.Picker)

        val result = SplitAutomaton.reduce(armed, SplitFact.ToggleChanged(enabled = false))

        assertEquals(emptyList<SplitPlan>(), result.plans)
        assertEquals(armed.slots, result.state.slots)
        assertTrue(!result.state.enabled)
    }

    @Test
    fun disablingTwoLiveAppsEndsSplitIntoTheFocusedApp() {
        // контракт 1.2.3
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val result = SplitAutomaton.reduce(live, SplitFact.ToggleChanged(enabled = false))

        assertEquals(listOf(SplitPlan.EndSplitFocusedFullscreen), result.plans)
        assertEquals(live.slots, result.state.slots)
        assertNull(result.state.scene)
        assertEquals(SceneVisibility.VISIBLE, result.state.visibility)
    }

    @Test
    fun disablingAnAppBesideAPickerRemovesOnlyOurPickers() {
        // контракт 1.2.4
        val live = split(SplitSlot.App(MUSIC), SplitSlot.Picker)

        val result = SplitAutomaton.reduce(live, SplitFact.ToggleChanged(enabled = false))

        assertEquals(listOf(SplitPlan.RemoveProductPickers), result.plans)
        assertEquals(live.slots, result.state.slots)
        assertNull(result.state.scene)
    }

    @Test
    fun disablingTwoPickersRemovesThemAndEndsTheScene() {
        // контракт 1.2.5
        val live = split(SplitSlot.Picker, SplitSlot.Picker)

        val result = SplitAutomaton.reduce(live, SplitFact.ToggleChanged(enabled = false))

        assertEquals(listOf(SplitPlan.RemoveProductPickers), result.plans)
        assertNull(result.state.scene)
    }

    @Test
    fun disablingDropsTheProjectionAndItsVacancy() {
        // контракт 1.2.3, инвариант 4 (эфемерное не переживает выключение)
        val projected = split(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC)).copy(
            projectedPane = SplitPane.PRIMARY,
            vacancyApp = mapOf(SplitPane.PRIMARY to TEMP),
        )

        val result = SplitAutomaton.reduce(projected, SplitFact.ToggleChanged(enabled = false))

        assertNull(result.state.projectedPane)
        assertEquals(emptyMap<SplitPane, String>(), result.state.vacancyApp)
        assertEquals(projected.slots, result.state.slots)
    }

    @Test
    fun theSelectionSurvivesTheToggleAndComesBackOnTheNextOpen() {
        // контракт 1.2 (примечание после таблицы), 1.3.2
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val off = SplitAutomaton.reduce(live, SplitFact.ToggleChanged(enabled = false)).state
        val on = SplitAutomaton.reduce(off, SplitFact.ToggleChanged(enabled = true))
        val open = SplitAutomaton.reduce(on.state, SplitFact.OpenRequested)
        val rebuilt = SplitAutomaton.reduce(
            on.state,
            SplitFact.BuildSceneSucceeded(live.slots),
        )

        assertEquals(emptyList<SplitPlan>(), on.plans)
        assertEquals(listOf(SplitPlan.BuildScene(live.slots)), open.plans)
        assertEquals(live.slots, rebuilt.state.slots)
        assertEquals(SplitScene.Split, rebuilt.state.scene)
    }

    @Test
    fun openWithASavedPairAsksToRebuildTheSamePackages() {
        // контракт 1.3.2
        val saved = state(primary = SplitSlot.App(MUSIC), secondary = SplitSlot.App(NAVIGATOR))

        val result = SplitAutomaton.reduce(saved, SplitFact.OpenRequested)

        assertEquals(listOf(SplitPlan.BuildScene(saved.slots)), result.plans)
        assertSame(saved, result.state)
    }

    @Test
    fun openWithoutASelectionAsksForTwoPickers() {
        // контракт 1.3.3
        val result = SplitAutomaton.reduce(state(), SplitFact.OpenRequested)

        assertEquals(
            listOf(
                SplitPlan.BuildScene(
                    mapOf(
                        SplitPane.PRIMARY to SplitSlot.Picker,
                        SplitPane.SECONDARY to SplitSlot.Picker,
                    ),
                ),
            ),
            result.plans,
        )
    }

    @Test
    fun openWithOneClosedPaneKeepsTheAppAndAddsAFreshPicker() {
        // контракт 1.3.4
        val half = state(primary = SplitSlot.App(MUSIC), secondary = SplitSlot.Closed)

        val result = SplitAutomaton.reduce(half, SplitFact.OpenRequested)

        assertEquals(
            listOf(
                SplitPlan.BuildScene(
                    mapOf(
                        SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                        SplitPane.SECONDARY to SplitSlot.Picker,
                    ),
                ),
            ),
            result.plans,
        )
        assertSame("the closed pane only becomes a picker once the build settles", half, result.state)
    }

    @Test
    fun openOverALiveSceneOnlyRevealsIt() {
        // контракт 1.3.5, 1.3.6
        val covered = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))
            .copy(visibility = SceneVisibility.COVERED)

        val result = SplitAutomaton.reduce(covered, SplitFact.OpenRequested)

        assertEquals(listOf(SplitPlan.RevealScene), result.plans)
        assertSame(covered, result.state)
    }

    @Test
    fun aSettledBuildDerivesTheSceneFromTheBuiltSlots() {
        // контракт 1.3.4, 2.3
        val armed = state()

        val result = SplitAutomaton.reduce(
            armed,
            SplitFact.BuildSceneSucceeded(
                mapOf(
                    SplitPane.PRIMARY to SplitSlot.App(MUSIC),
                    SplitPane.SECONDARY to SplitSlot.Closed,
                ),
            ),
        )

        assertEquals(SplitScene.Full(SplitPane.PRIMARY), result.state.scene)
        assertEquals(SplitSlot.Closed, result.state.slot(SplitPane.SECONDARY))
        assertEquals(emptyList<SplitPlan>(), result.plans)
    }

    @Test
    fun tappingAnAppLaunchesItAndLeavesTheNeighbourAlone() {
        // контракт 1.5.1
        val live = split(SplitSlot.Picker, SplitSlot.App(NAVIGATOR))

        val tapped = SplitAutomaton.reduce(
            live,
            SplitFact.SelectionRequested(SplitPane.PRIMARY, MUSIC),
        )
        val confirmed = SplitAutomaton.reduce(
            tapped.state,
            SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, MUSIC),
        )

        assertEquals(listOf(SplitPlan.LaunchApp(SplitPane.PRIMARY, MUSIC)), tapped.plans)
        assertSame("the slot follows the confirmation, not the tap", live, tapped.state)
        assertEquals(SplitSlot.App(MUSIC), confirmed.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.App(NAVIGATOR), confirmed.state.slot(SplitPane.SECONDARY))
        assertEquals(SplitScene.Split, confirmed.state.scene)
    }

    @Test
    fun theSamePackageRunsInBothPanesIndependently() {
        // контракт 1.5.2
        val live = split(SplitSlot.Picker, SplitSlot.Picker)

        val first = SplitAutomaton.reduce(
            live,
            SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, MUSIC),
        ).state
        val second = SplitAutomaton.reduce(
            first,
            SplitFact.AppLaunchConfirmed(SplitPane.SECONDARY, MUSIC),
        ).state
        val closedOne = SplitAutomaton.reduce(
            second,
            SplitFact.AppClosedSettled(SplitPane.PRIMARY),
        ).state

        assertEquals(SplitSlot.App(MUSIC), second.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.App(MUSIC), second.slot(SplitPane.SECONDARY))
        assertEquals(SplitSlot.Picker, closedOne.slot(SplitPane.PRIMARY))
        assertEquals("the twin copy is untouched", SplitSlot.App(MUSIC), closedOne.slot(SplitPane.SECONDARY))
    }

    @Test
    fun aFailedLaunchLeavesThePickerAndShowsANotice() {
        // контракт 1.5.7
        val live = split(SplitSlot.Picker, SplitSlot.App(NAVIGATOR))

        val result = SplitAutomaton.reduce(
            live,
            SplitFact.AppLaunchFailed(SplitPane.PRIMARY, "Не удалось открыть"),
        )

        assertEquals(listOf(SplitPlan.Notice("Не удалось открыть")), result.plans)
        assertSame(live, result.state)
    }

    @Test
    fun uninstallingAPackageReturnsEveryPaneRunningIt() {
        // контракт 1.5.6
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(MUSIC))

        val result = SplitAutomaton.reduce(live, SplitFact.PackageRemoved(MUSIC))

        assertEquals(SplitSlot.Picker, result.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.Picker, result.state.slot(SplitPane.SECONDARY))
        assertEquals(SplitScene.Split, result.state.scene)
        assertEquals(emptyList<SplitPlan>(), result.plans)
    }

    @Test
    fun theLastBackReturnsOnlyItsPaneToThePicker() {
        // контракт 1.6.2
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val result = SplitAutomaton.reduce(live, SplitFact.AppClosedSettled(SplitPane.SECONDARY))

        assertEquals(SplitSlot.App(MUSIC), result.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.Picker, result.state.slot(SplitPane.SECONDARY))
        assertEquals(SplitScene.Split, result.state.scene)
    }

    @Test
    fun aCrashedPaneFallsBackToItsPickerWithoutRestartingAnything() {
        // контракт 1.7.1, 1.7.2, 1.7.3: краш приходит тем же settled-фактом, что и закрытие
        val live = split(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC))

        val crashed = SplitAutomaton.reduce(live, SplitFact.AppClosedSettled(SplitPane.PRIMARY))
        val settled = SplitAutomaton.reduce(
            crashed.state,
            SplitFact.AppClosedSettled(SplitPane.PRIMARY),
        )

        assertEquals(SplitSlot.Picker, crashed.state.slot(SplitPane.PRIMARY))
        assertEquals("the neighbour keeps playing", SplitSlot.App(MUSIC), crashed.state.slot(SplitPane.SECONDARY))
        assertEquals("no auto restart", emptyList<SplitPlan>(), crashed.plans)
        assertSame("a repeated closure observation is inert", crashed.state, settled.state)
    }

    @Test
    fun dismissingAPickerPaneExpandsTheSurvivor() {
        // контракт 1.6.3, 1.7.4
        val live = split(SplitSlot.Picker, SplitSlot.App(NAVIGATOR))

        val result = SplitAutomaton.reduce(
            live,
            SplitFact.PickerPaneClosedSettled(SplitPane.PRIMARY),
        )

        assertEquals(SplitSlot.Closed, result.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitScene.Full(SplitPane.SECONDARY), result.state.scene)
    }

    @Test
    fun dismissingTheLastFullscreenPickerEndsTheSession() {
        // контракт 1.6.4
        val last = state(
            primary = SplitSlot.Closed,
            secondary = SplitSlot.Picker,
            scene = SplitScene.Full(SplitPane.SECONDARY),
        )

        val result = SplitAutomaton.reduce(
            last,
            SplitFact.PickerPaneClosedSettled(SplitPane.SECONDARY),
        )

        assertNull(result.state.scene)
        assertEquals(SplitSlot.Closed, result.state.slot(SplitPane.SECONDARY))
        assertEquals(SceneVisibility.VISIBLE, result.state.visibility)
    }

    @Test
    fun clearAllReturnsEveryLiveAppToItsPicker() {
        // контракт 1.7.5
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val cleared = SplitAutomaton.reduce(live, SplitFact.SceneEndedSettled).state
        val reopened = SplitAutomaton.reduce(cleared, SplitFact.OpenRequested)

        assertNull(cleared.scene)
        assertEquals(
            "the next open shows two fresh pickers, nothing revives",
            listOf(
                SplitPlan.BuildScene(
                    mapOf(
                        SplitPane.PRIMARY to SplitSlot.Picker,
                        SplitPane.SECONDARY to SplitSlot.Picker,
                    ),
                ),
            ),
            reopened.plans,
        )
    }

    @Test
    fun collapsingAPaneClosesItAndExpandsTheSurvivor() {
        // контракт 1.8.2
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val result = SplitAutomaton.reduce(
            live,
            SplitFact.PaneCollapsedSettled(survivor = SplitPane.SECONDARY),
        )

        assertEquals(SplitSlot.Closed, result.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.App(NAVIGATOR), result.state.slot(SplitPane.SECONDARY))
        assertEquals(SplitScene.Full(SplitPane.SECONDARY), result.state.scene)
    }

    @Test
    fun anEdgeCommitAttachesAPickerBesideTheLiveApp() {
        // контракт 1.8.5
        val full = state(
            primary = SplitSlot.App(MUSIC),
            secondary = SplitSlot.Closed,
            scene = SplitScene.Full(SplitPane.PRIMARY),
        )

        val result = SplitAutomaton.reduce(
            full,
            SplitFact.EdgeCommitConfirmed(vacantPane = SplitPane.SECONDARY),
        )

        assertEquals(listOf(SplitPlan.AttachPicker(SplitPane.SECONDARY)), result.plans)
        assertEquals("the live app is not restarted", SplitSlot.App(MUSIC), result.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.Picker, result.state.slot(SplitPane.SECONDARY))
        assertEquals(SplitScene.Split, result.state.scene)
    }

    @Test
    fun homeOnlyCoversTheSceneAndSuspendsOurGate() {
        // контракт 1.9.1, инвариант 5
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val home = SplitAutomaton.reduce(live, SplitFact.HomeConfirmed)
        val again = SplitAutomaton.reduce(home.state, SplitFact.HomeConfirmed)

        assertEquals(listOf(SplitPlan.SuspendOwnedGate), home.plans)
        assertEquals(SceneVisibility.COVERED, home.state.visibility)
        assertEquals("Home never clears slots", live.slots, home.state.slots)
        assertEquals(SplitScene.Split, home.state.scene)
        assertSame(home.state, again.state)
        assertEquals(emptyList<SplitPlan>(), again.plans)
    }

    @Test
    fun revealingAfterHomeRestoresTheSameScene() {
        // контракт 1.9.4, 1.3.6
        val live = split(SplitSlot.App(MUSIC), SplitSlot.App(NAVIGATOR))

        val covered = SplitAutomaton.reduce(live, SplitFact.HomeConfirmed).state
        val revealed = SplitAutomaton.reduce(covered, SplitFact.SceneRevealed)

        assertEquals(SceneVisibility.VISIBLE, revealed.state.visibility)
        assertEquals(live.slots, revealed.state.slots)
        assertEquals(SplitScene.Split, revealed.state.scene)
    }

    @Test
    fun sendingTheNavigatorToTheClusterKeepsItsSlot() {
        // контракт 1.10.1
        val live = split(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC))

        val result = SplitAutomaton.reduce(live, SplitFact.ProjectionStarted(SplitPane.PRIMARY))

        assertEquals(SplitPane.PRIMARY, result.state.projectedPane)
        assertEquals(SplitSlot.App(NAVIGATOR), result.state.slot(SplitPane.PRIMARY))
        assertEquals(emptyList<SplitPlan>(), result.plans)
    }

    @Test
    fun theVacancyCycleForgetsTheTempAppAndKeepsBothSlots() {
        // контракт 1.10.2, 1.10.4, 1.10.5
        val live = split(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC))

        val projected = SplitAutomaton.reduce(
            live,
            SplitFact.ProjectionStarted(SplitPane.PRIMARY),
        ).state
        val tapped = SplitAutomaton.reduce(
            projected,
            SplitFact.SelectionRequested(SplitPane.PRIMARY, TEMP),
        )
        val occupied = SplitAutomaton.reduce(
            tapped.state,
            SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, TEMP),
        ).state
        val neighbourClosed = SplitAutomaton.reduce(
            occupied,
            SplitFact.AppClosedSettled(SplitPane.SECONDARY),
        ).state
        val neighbourReplaced = SplitAutomaton.reduce(
            neighbourClosed,
            SplitFact.AppLaunchConfirmed(SplitPane.SECONDARY, RADIO),
        ).state
        val returned = SplitAutomaton.reduce(neighbourReplaced, SplitFact.ProjectionReturned).state

        assertEquals(listOf(SplitPlan.LaunchApp(SplitPane.PRIMARY, TEMP)), tapped.plans)
        assertEquals(mapOf(SplitPane.PRIMARY to TEMP), occupied.vacancyApp)
        assertEquals(
            "the vacancy never overwrites the navigator's slot",
            SplitSlot.App(NAVIGATOR),
            occupied.slot(SplitPane.PRIMARY),
        )
        assertEquals(emptyMap<SplitPane, String>(), returned.vacancyApp)
        assertNull(returned.projectedPane)
        assertEquals(SplitSlot.App(NAVIGATOR), returned.slot(SplitPane.PRIMARY))
        assertEquals("the new neighbour is kept", SplitSlot.App(RADIO), returned.slot(SplitPane.SECONDARY))
    }

    @Test
    fun closingTheTempAppOnlyClearsTheVacancyRecord() {
        // контракт 1.10.2, раздел 5 (к 1.10)
        val occupied = split(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC)).copy(
            projectedPane = SplitPane.PRIMARY,
            vacancyApp = mapOf(SplitPane.PRIMARY to TEMP),
        )

        val result = SplitAutomaton.reduce(occupied, SplitFact.AppClosedSettled(SplitPane.PRIMARY))

        assertEquals(emptyMap<SplitPane, String>(), result.state.vacancyApp)
        assertEquals(SplitSlot.App(NAVIGATOR), result.state.slot(SplitPane.PRIMARY))
        assertEquals(SplitPane.PRIMARY, result.state.projectedPane)
    }

    @Test
    fun collapsingTheProjectedPaneGivesUpItsPlace() {
        // контракт 1.10.6
        val projected = split(SplitSlot.App(NAVIGATOR), SplitSlot.App(MUSIC)).copy(
            projectedPane = SplitPane.PRIMARY,
            vacancyApp = mapOf(SplitPane.PRIMARY to TEMP),
        )

        val result = SplitAutomaton.reduce(
            projected,
            SplitFact.PaneCollapsedSettled(survivor = SplitPane.SECONDARY),
        )

        assertEquals(SplitSlot.Closed, result.state.slot(SplitPane.PRIMARY))
        assertNull("nothing revives what the user closed", result.state.projectedPane)
        assertEquals(emptyMap<SplitPane, String>(), result.state.vacancyApp)
    }

    @Test
    fun everyFactButTheToggleIsInertWhileDisabled() {
        // контракт 1.2.7, U4, инвариант 1
        val off = state(
            enabled = false,
            primary = SplitSlot.App(MUSIC),
            secondary = SplitSlot.App(NAVIGATOR),
        )

        everyFact().filterNot { it is SplitFact.ToggleChanged }.forEach { fact ->
            val result = SplitAutomaton.reduce(off, fact)
            assertSame("disabled product reacted to $fact", off, result.state)
            assertTrue("disabled product planned $fact -> ${result.plans}", result.plans.isEmpty())
        }
    }

    @Test
    fun impossibleFactsReturnTheSameStateInstance() {
        // контракт 1.4.4, инвариант 8: устаревший или невозможный факт ничего не двигает
        val live = split(SplitSlot.App(MUSIC), SplitSlot.Picker)
        val fullscreen = state(
            primary = SplitSlot.App(MUSIC),
            secondary = SplitSlot.Closed,
            scene = SplitScene.Full(SplitPane.PRIMARY),
        )
        val sceneless = state(primary = SplitSlot.App(MUSIC), secondary = SplitSlot.App(NAVIGATOR))

        val impossible = listOf(
            live to SplitFact.SelectionRequested(SplitPane.PRIMARY, NAVIGATOR),
            live to SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, NAVIGATOR),
            live to SplitFact.SelectionRequested(SplitPane.SECONDARY, " "),
            live to SplitFact.EdgeCommitConfirmed(SplitPane.SECONDARY),
            live to SplitFact.ProjectionStarted(SplitPane.SECONDARY),
            live to SplitFact.ProjectionReturned,
            live to SplitFact.SceneRevealed,
            live to SplitFact.BuildSceneSucceeded(mapOf(SplitPane.PRIMARY to SplitSlot.Picker)),
            fullscreen to SplitFact.PickerPaneClosedSettled(SplitPane.SECONDARY),
            fullscreen to SplitFact.EdgeCommitConfirmed(SplitPane.PRIMARY),
            sceneless to SplitFact.PaneCollapsedSettled(SplitPane.PRIMARY),
            sceneless to SplitFact.SceneEndedSettled,
            sceneless to SplitFact.HomeConfirmed,
            sceneless to SplitFact.AppClosedSettled(SplitPane.PRIMARY),
        )

        impossible.forEach { (before, fact) ->
            val result = SplitAutomaton.reduce(before, fact)
            assertSame("state moved on $fact", before, result.state)
            assertTrue("plans emitted on $fact: ${result.plans}", result.plans.isEmpty())
        }
    }

    private companion object {
        const val MUSIC = "ru.yandex.music"
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val RADIO = "com.example.radio"
        const val TEMP = "com.example.temp"

        fun state(
            enabled: Boolean = true,
            primary: SplitSlot = SplitSlot.Closed,
            secondary: SplitSlot = SplitSlot.Closed,
            scene: SplitScene? = null,
            visibility: SceneVisibility = SceneVisibility.VISIBLE,
        ) = SplitState(
            enabled = enabled,
            slots = mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary),
            scene = scene,
            visibility = visibility,
        )

        fun split(primary: SplitSlot, secondary: SplitSlot) =
            state(primary = primary, secondary = secondary, scene = SplitScene.Split)

        fun everyFact(): List<SplitFact> = listOf(
            SplitFact.ToggleChanged(enabled = true),
            SplitFact.OpenRequested,
            SplitFact.SelectionRequested(SplitPane.PRIMARY, MUSIC),
            SplitFact.AppLaunchConfirmed(SplitPane.PRIMARY, MUSIC),
            SplitFact.AppLaunchFailed(SplitPane.PRIMARY, "boom"),
            SplitFact.AppClosedSettled(SplitPane.PRIMARY),
            SplitFact.PaneCollapsedSettled(SplitPane.PRIMARY),
            SplitFact.PickerPaneClosedSettled(SplitPane.PRIMARY),
            SplitFact.SceneEndedSettled,
            SplitFact.HomeConfirmed,
            SplitFact.SceneRevealed,
            SplitFact.EdgeCommitConfirmed(SplitPane.SECONDARY),
            SplitFact.ProjectionStarted(SplitPane.PRIMARY),
            SplitFact.ProjectionReturned,
            SplitFact.PackageRemoved(MUSIC),
            SplitFact.BuildSceneSucceeded(
                mapOf(
                    SplitPane.PRIMARY to SplitSlot.Picker,
                    SplitPane.SECONDARY to SplitSlot.Picker,
                ),
            ),
        )
    }
}
