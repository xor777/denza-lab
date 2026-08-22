package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPickerAutomatonTest {
    @Test
    fun nativeVacancyAttachesPickerExactlyOnce() {
        val full = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = closed(),
        )

        val first = SplitPickerAutomaton.reduce(
            full,
            SplitPickerEvent.NativePickerObserved(SplitPane.SECONDARY, hostTaskId = 200),
        )
        val duplicate = SplitPickerAutomaton.reduce(
            first.state,
            SplitPickerEvent.NativePickerObserved(SplitPane.SECONDARY, hostTaskId = 200),
        )

        assertEquals(
            listOf(SplitPickerAction.AttachPicker(SplitPane.SECONDARY, 200)),
            first.actions,
        )
        assertEquals(SplitPickerSlotKind.ATTACHING, first.state.slot(SplitPane.SECONDARY).kind)
        assertTrue(duplicate.actions.isEmpty())
    }

    @Test
    fun pickerResumeWithoutRecordedAppDisappearanceNeverMutatesTasks() {
        val picker = state(
            primary = picker(100),
            secondary = picker(200),
        )

        val result = SplitPickerAutomaton.reduce(
            picker,
            SplitPickerEvent.PickerBecameTop(
                pane = SplitPane.PRIMARY,
                hostTaskId = 100,
                observedTaskIds = setOf(100, 200),
            ),
        )

        assertEquals(picker, result.state)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun revealedPickerRemovesOnlyItsRecordedDismissedApp() {
        val active = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )

        val result = SplitPickerAutomaton.reduce(
            active,
            SplitPickerEvent.PickerBecameTop(
                pane = SplitPane.SECONDARY,
                hostTaskId = 200,
                observedTaskIds = setOf(10, 20, 100, 200, 999),
            ),
        )

        assertEquals(SplitPickerSlotKind.PICKER, result.state.slot(SplitPane.SECONDARY).kind)
        assertEquals(
            listOf(SplitPickerAction.RemoveExactTask(20, MUSIC)),
            result.actions,
        )
        assertFalse(result.actions.any { it == SplitPickerAction.RemoveExactTask(999, "unknown") })

        val repeated = SplitPickerAutomaton.reduce(
            result.state,
            SplitPickerEvent.PickerBecameTop(
                pane = SplitPane.SECONDARY,
                hostTaskId = 200,
                observedTaskIds = setOf(10, 20, 100, 200, 999),
            ),
        )
        assertTrue(repeated.actions.isEmpty())
    }

    @Test
    fun revealedPickerDropsDismissedAppFromTheRestorablePair() {
        val active = state(
            primary = app(140, MUSIC, hostTaskId = 138),
            secondary = app(141, NAVIGATOR, hostTaskId = 139),
        )

        val revealed = SplitPickerAutomaton.reduce(
            active,
            SplitPickerEvent.PickerBecameTop(
                pane = SplitPane.PRIMARY,
                hostTaskId = 138,
                observedTaskIds = setOf(138, 139, 141),
            ),
        )

        assertEquals(
            mapOf(SplitPane.SECONDARY to NAVIGATOR),
            SplitPickerSelectionPolicy.settledPair(revealed.state),
        )
    }

    @Test
    fun swipingPickerClosesPaneAndNeverRecreatesIt() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = picker(200),
        )

        val closed = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.PickerTaskGone(SplitPane.SECONDARY, hostTaskId = 200),
        )

        assertEquals(SplitPickerPhase.FULL, closed.state.phase)
        assertEquals(SplitPickerSlotKind.CLOSED, closed.state.slot(SplitPane.SECONDARY).kind)
        assertEquals(
            listOf(SplitPickerAction.RemovePickerArtifact(200)),
            closed.actions,
        )

        val staleResume = SplitPickerAutomaton.reduce(
            closed.state,
            SplitPickerEvent.PickerBecameTop(
                pane = SplitPane.SECONDARY,
                hostTaskId = 200,
                observedTaskIds = emptySet(),
            ),
        )
        assertTrue(staleResume.actions.isEmpty())
        assertEquals(SplitPickerSlotKind.CLOSED, staleResume.state.slot(SplitPane.SECONDARY).kind)
    }

    @Test
    fun closingOneOfTwoBarePickersLeavesThePeerPickerFullscreen() {
        val split = state(primary = picker(100), secondary = picker(200))

        val result = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.PickerTaskGone(SplitPane.PRIMARY, hostTaskId = 100),
        )

        assertEquals(SplitPickerPhase.FULL, result.state.phase)
        assertEquals(closed(), result.state.slot(SplitPane.PRIMARY))
        assertEquals(picker(200), result.state.slot(SplitPane.SECONDARY))
        assertEquals(listOf(SplitPickerAction.RemovePickerArtifact(100)), result.actions)
    }

    @Test
    fun dividerResizeAdoptsAppsSwappedAcrossLogicalHosts() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )

        val result = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.DividerResized(
                panes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(100, 20, MUSIC),
                    SplitPane.SECONDARY to SplitPickerObservedPane(200, 10, NAVIGATOR),
                ),
            ),
        )

        assertEquals(app(20, MUSIC, hostTaskId = 100), result.state.slot(SplitPane.PRIMARY))
        assertEquals(app(10, NAVIGATOR, hostTaskId = 200), result.state.slot(SplitPane.SECONDARY))
        assertEquals(SplitPickerPhase.SPLIT, result.state.phase)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun dividerResizeAdoptsLivePickerVacancyBeforeItsVisibilityEvent() {
        val stale = state(
            primary = app(20, MUSIC, hostTaskId = 100),
            secondary = app(10, NAVIGATOR, hostTaskId = 200),
        )

        val result = SplitPickerAutomaton.reduce(
            stale,
            SplitPickerEvent.DividerResized(
                panes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(hostTaskId = 100),
                    SplitPane.SECONDARY to SplitPickerObservedPane(200, 20, MUSIC),
                ),
            ),
        )

        assertEquals(picker(100), result.state.slot(SplitPane.PRIMARY))
        assertEquals(app(20, MUSIC, hostTaskId = 200), result.state.slot(SplitPane.SECONDARY))
        assertEquals(
            mapOf(SplitPane.SECONDARY to MUSIC),
            SplitPickerSelectionPolicy.settledPair(result.state),
        )
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun incompleteOrInvalidResizeObservationCannotCorruptTheAutomaton() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )
        val invalidEvents = listOf(
            SplitPickerEvent.DividerResized(
                panes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(100, 20, MUSIC),
                ),
            ),
            SplitPickerEvent.DividerResized(
                panes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(100, 20, MUSIC),
                    SplitPane.SECONDARY to SplitPickerObservedPane(100, 10, NAVIGATOR),
                ),
            ),
            SplitPickerEvent.DividerResized(
                panes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(100, 20, null),
                    SplitPane.SECONDARY to SplitPickerObservedPane(200, 10, NAVIGATOR),
                ),
            ),
        )

        invalidEvents.forEach { event ->
            val result = SplitPickerAutomaton.reduce(split, event)
            assertEquals(split, result.state)
            assertTrue(result.actions.isEmpty())
        }
    }

    @Test
    fun projectionFreesItsPickerPaneForAnotherApp() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )
        val projected = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.ProjectionStarted(SplitPane.PRIMARY, taskId = 10),
        ).state

        val pickerVisible = SplitPickerAutomaton.reduce(
            projected,
            SplitPickerEvent.PickerBecameTop(
                pane = SplitPane.PRIMARY,
                hostTaskId = 100,
                observedTaskIds = setOf(100, 200, 20),
            ),
        )

        assertEquals(SplitPickerSlotKind.PICKER, pickerVisible.state.slot(SplitPane.PRIMARY).kind)
        assertEquals(100, pickerVisible.state.slot(SplitPane.PRIMARY).hostTaskId)
        assertTrue(pickerVisible.actions.isEmpty())
    }

    @Test
    fun projectedPaneClosedByUserReturnsNavigatorFullscreen() {
        val projected = state(
            primary = projected(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )
        val closed = SplitPickerAutomaton.reduce(
            projected,
            SplitPickerEvent.PickerTaskGone(SplitPane.PRIMARY, hostTaskId = 100),
        ).state

        val returned = SplitPickerAutomaton.reduce(
            closed,
            SplitPickerEvent.ProjectionReturned(SplitPane.PRIMARY, taskId = 10),
        )

        assertEquals(
            listOf(
                SplitPickerAction.ReturnTaskFullscreen(
                    SplitPane.PRIMARY,
                    10,
                    NAVIGATOR,
                ),
            ),
            returned.actions,
        )
        assertEquals(SplitPickerSlotKind.CLOSED, returned.state.slot(SplitPane.PRIMARY).kind)
    }

    @Test
    fun reopeningProjectedVacancyReattachesPickerAndRestoresNavigatorToIt() {
        val projected = state(
            primary = projected(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )
        val closed = SplitPickerAutomaton.reduce(
            projected,
            SplitPickerEvent.PickerTaskGone(SplitPane.PRIMARY, hostTaskId = 100),
        ).state

        val reopened = SplitPickerAutomaton.reduce(
            closed,
            SplitPickerEvent.NativePickerObserved(SplitPane.PRIMARY, hostTaskId = 101),
        )
        val returned = SplitPickerAutomaton.reduce(
            reopened.state,
            SplitPickerEvent.ProjectionReturned(SplitPane.PRIMARY, taskId = 10),
        )

        assertEquals(
            listOf(SplitPickerAction.AttachPicker(SplitPane.PRIMARY, 101)),
            reopened.actions,
        )
        assertEquals(SplitPickerSlotKind.APP, returned.state.slot(SplitPane.PRIMARY).kind)
        assertEquals(101, returned.state.slot(SplitPane.PRIMARY).hostTaskId)
        assertTrue(returned.actions.isEmpty())
    }

    @Test
    fun homeCancelsOpeningAndAllPendingMutations() {
        val opening = SplitPickerAutomaton.reduce(
            SplitPickerAutomatonState(),
            SplitPickerEvent.OpenRequested,
        )
        assertEquals(listOf(SplitPickerAction.PrepareNativeSession), opening.actions)

        val home = SplitPickerAutomaton.reduce(opening.state, SplitPickerEvent.HomeObserved)

        assertEquals(SplitPickerPhase.IDLE, home.state.phase)
        assertTrue(home.actions.isEmpty())
        assertEquals(closed(), home.state.slot(SplitPane.PRIMARY))
        assertEquals(closed(), home.state.slot(SplitPane.SECONDARY))
    }

    @Test
    fun nativeBootstrapHostCanBeReplacedByOwnPickerTask() {
        val opening = SplitPickerAutomaton.reduce(
            SplitPickerAutomatonState(),
            SplitPickerEvent.OpenRequested,
        ).state
        val attaching = SplitPickerAutomaton.reduce(
            opening,
            SplitPickerEvent.NativePickerObserved(
                pane = SplitPane.PRIMARY,
                hostTaskId = 100,
            ),
        ).state

        val attached = SplitPickerAutomaton.reduce(
            attaching,
            SplitPickerEvent.PickerAttached(
                pane = SplitPane.PRIMARY,
                hostTaskId = 101,
            ),
        )

        assertEquals(SplitPickerSlotKind.PICKER, attached.state.slot(SplitPane.PRIMARY).kind)
        assertEquals(101, attached.state.slot(SplitPane.PRIMARY).hostTaskId)
        assertTrue(attached.actions.isEmpty())
    }

    @Test
    fun adversarialVisibilityFlappingHasBoundedSemanticActions() {
        var current = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = picker(200),
        )
        val actions = mutableListOf<SplitPickerAction>()

        repeat(50) { tick ->
            val event = if (tick % 2 == 0) {
                SplitPickerEvent.PickerBecameTop(
                    SplitPane.PRIMARY,
                    hostTaskId = 100,
                    observedTaskIds = setOf(10, 100, 200),
                )
            } else {
                SplitPickerEvent.PickerAttached(SplitPane.PRIMARY, hostTaskId = 100)
            }
            val result = SplitPickerAutomaton.reduce(current, event)
            current = result.state
            actions += result.actions
        }

        assertEquals(1, actions.count { it == SplitPickerAction.RemoveExactTask(10, NAVIGATOR) })
        assertTrue(actions.size <= 1)
    }

    @Test
    fun staleIdsAndWrongPaneEventsAreNoOps() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = picker(200),
        )
        val events = listOf(
            SplitPickerEvent.PickerTaskGone(SplitPane.PRIMARY, hostTaskId = 999),
            SplitPickerEvent.ProjectionStarted(SplitPane.PRIMARY, taskId = 999),
            SplitPickerEvent.PickerBecameTop(
                SplitPane.SECONDARY,
                hostTaskId = 999,
                observedTaskIds = setOf(10, 100, 200),
            ),
        )

        events.forEach { event ->
            val result = SplitPickerAutomaton.reduce(split, event)
            assertEquals(split, result.state)
            assertTrue(result.actions.isEmpty())
        }
    }

    @Test
    fun hiddenPickerDestructionCannotClosePaneOrOrphanVisibleApp() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = picker(200),
        )

        val result = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.PickerTaskGone(SplitPane.PRIMARY, hostTaskId = 100),
        )

        assertEquals(split, result.state)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun stockPickerRevealedAfterOurProcessDeathRehostsAndClosesRecordedAppOnce() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )

        val revealed = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.NativePickerObserved(
                SplitPane.PRIMARY,
                hostTaskId = 100,
                observedTaskIds = setOf(10, 20, 100, 200),
            ),
        )
        val duplicate = SplitPickerAutomaton.reduce(
            revealed.state,
            SplitPickerEvent.NativePickerObserved(
                SplitPane.PRIMARY,
                hostTaskId = 100,
                observedTaskIds = setOf(10, 20, 100, 200),
            ),
        )
        val attached = SplitPickerAutomaton.reduce(
            revealed.state,
            SplitPickerEvent.PickerAttached(SplitPane.PRIMARY, hostTaskId = 100),
        )

        assertEquals(
            listOf(
                SplitPickerAction.AttachPicker(SplitPane.PRIMARY, 100),
                SplitPickerAction.RemoveExactTask(10, NAVIGATOR),
            ),
            revealed.actions,
        )
        assertTrue(duplicate.actions.isEmpty())
        assertEquals(SplitPickerSlotKind.PICKER, attached.state.slot(SplitPane.PRIMARY).kind)
    }

    @Test
    fun freshVerifiedNativeHostSupersedesStaleRecordedHost() {
        val split = state(
            primary = app(10, NAVIGATOR, hostTaskId = 100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )

        val result = SplitPickerAutomaton.reduce(
            split,
            SplitPickerEvent.NativePickerObserved(
                SplitPane.PRIMARY,
                hostTaskId = 101,
                observedTaskIds = setOf(10, 20, 101, 200),
            ),
        )

        assertEquals(101, result.state.slot(SplitPane.PRIMARY).hostTaskId)
        assertEquals(
            listOf(
                SplitPickerAction.AttachPicker(SplitPane.PRIMARY, 101),
                SplitPickerAction.RemoveExactTask(10, NAVIGATOR),
            ),
            result.actions,
        )
    }

    @Test
    fun failedPickerAttachHasBoundedRetryBudgetAndNoInfiniteLoop() {
        var state = state(
            primary = picker(100),
            secondary = app(20, MUSIC, hostTaskId = 200),
        )
        val actions = mutableListOf<SplitPickerAction>()

        repeat(8) {
            val attempt = SplitPickerAutomaton.reduce(
                state,
                SplitPickerEvent.NativePickerObserved(SplitPane.PRIMARY, hostTaskId = 100),
            )
            state = attempt.state
            actions += attempt.actions
            state = SplitPickerAutomaton.reduce(
                state,
                SplitPickerEvent.PickerAttachFailed(SplitPane.PRIMARY, hostTaskId = 100),
            ).state
        }

        assertEquals(
            3,
            actions.count { it == SplitPickerAction.AttachPicker(SplitPane.PRIMARY, 100) },
        )
        assertEquals(3, state.slot(SplitPane.PRIMARY).attachAttempts)

        val freshHost = SplitPickerAutomaton.reduce(
            state,
            SplitPickerEvent.NativePickerObserved(SplitPane.PRIMARY, hostTaskId = 101),
        )
        assertEquals(
            listOf(SplitPickerAction.AttachPicker(SplitPane.PRIMARY, 101)),
            freshHost.actions,
        )
        assertEquals(1, freshHost.state.slot(SplitPane.PRIMARY).attachAttempts)
    }

    private fun state(
        primary: SplitPickerSlotState,
        secondary: SplitPickerSlotState,
    ) = SplitPickerAutomatonState(
        armed = true,
        phase = if (
            primary.kind == SplitPickerSlotKind.CLOSED ||
            secondary.kind == SplitPickerSlotKind.CLOSED
        ) {
            SplitPickerPhase.FULL
        } else {
            SplitPickerPhase.SPLIT
        },
        slots = mapOf(
            SplitPane.PRIMARY to primary,
            SplitPane.SECONDARY to secondary,
        ),
    )

    private fun closed() = SplitPickerSlotState()

    private fun picker(hostTaskId: Int) = SplitPickerSlotState(
        kind = SplitPickerSlotKind.PICKER,
        hostTaskId = hostTaskId,
    )

    private fun app(
        taskId: Int,
        packageName: String,
        hostTaskId: Int,
    ) = SplitPickerSlotState(
        kind = SplitPickerSlotKind.APP,
        hostTaskId = hostTaskId,
        appTaskId = taskId,
        packageName = packageName,
    )

    private fun projected(
        taskId: Int,
        packageName: String,
        hostTaskId: Int,
    ) = SplitPickerSlotState(
        kind = SplitPickerSlotKind.PROJECTED,
        hostTaskId = hostTaskId,
        appTaskId = taskId,
        packageName = packageName,
    )

    private companion object {
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val MUSIC = "ru.yandex.music"
    }
}
