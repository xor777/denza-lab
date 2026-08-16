package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRoutingReducerTest {
    @Test
    fun orphanPlaceholderNeverKeepsAnEligibleAppHidden() {
        val decision = reduce(
            SplitRoutingMemory(),
            observation(
                area = 3,
                roots = listOf(
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_A_TASK.copy(
                            rootId = ROOT_WIDE,
                            bounds = WIDE,
                            visible = false,
                        ),
                        task(
                            id = 99,
                            packageName = PLACEHOLDER_PACKAGE,
                            activityName = PLACEHOLDER_ACTIVITY,
                            rootId = ROOT_WIDE,
                            bounds = WIDE,
                            visible = true,
                        ),
                    ),
                    root(
                        ROOT_NARROW,
                        NARROW,
                        picker(ROOT_NARROW),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                SplitRoutingAction.RemoveTask(
                    taskId = 99,
                    packageName = PLACEHOLDER_PACKAGE,
                    activityName = PLACEHOLDER_ACTIVITY,
                    topPackageName = PLACEHOLDER_PACKAGE,
                    topActivityName = PLACEHOLDER_ACTIVITY,
                ),
            ),
            decision.actions,
        )
        assertEquals(SplitRoutingMemory(), decision.memory)
    }

    @Test
    fun firstAppCreatesAppAndPlaceholderTarget() {
        val decision = reduce(
            memory = SplitRoutingMemory(),
            observation = expanded(APP_A_TASK),
        )

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.first?.preferredRootId)
        assertEquals(PLACEHOLDER_PACKAGE, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.second?.preferredRootId)
        assertEquals(listOf(SplitRoutingAction.LaunchPlaceholder), decision.actions)
    }

    @Test
    fun targetCompletesOnlyAfterPlacementTopAreaAndBoundsAllMatch() {
        val initial = reduce(SplitRoutingMemory(), expanded(APP_A_TASK)).memory
        val placeholderFull = task(
            id = 99,
            packageName = PLACEHOLDER_PACKAGE,
            activityName = PLACEHOLDER_ACTIVITY,
            rootId = ROOT_FULL,
            bounds = FULL,
        )
        val placement = reduce(
            initial,
            observation(
                area = 4,
                roots = listOf(root(ROOT_FULL, FULL, placeholderFull, APP_A_TASK)),
            ),
        )
        assertEquals(
            setOf(
                SplitRoutingAction.PlaceTask(10, ROOT_WIDE, promoteInPlace = false),
                SplitRoutingAction.PlaceTask(99, ROOT_NARROW, promoteInPlace = false),
            ),
            placement.actions.toSet(),
        )

        val balanced = reduce(
            placement.memory,
            pairObservation(
                area = 4,
                narrow = placeholderFull.copy(rootId = ROOT_NARROW),
                wide = APP_A_TASK.copy(rootId = ROOT_WIDE),
                taskBoundsMatchRoots = false,
            ),
        )
        assertEquals(listOf(SplitRoutingAction.BalanceDivider), balanced.actions)

        val resized = reduce(
            balanced.memory,
            pairObservation(
                area = 3,
                narrow = placeholderFull.copy(rootId = ROOT_NARROW),
                wide = APP_A_TASK.copy(rootId = ROOT_WIDE),
                taskBoundsMatchRoots = false,
            ),
        )
        assertEquals(2, resized.actions.filterIsInstance<SplitRoutingAction.ResizeTask>().size)

        val complete = reduce(
            resized.memory,
            pairObservation(
                area = 3,
                narrow = placeholderFull.copy(rootId = ROOT_NARROW),
                wide = APP_A_TASK.copy(rootId = ROOT_WIDE),
            ),
        )
        assertTrue(complete.splitVisible)
        assertNull(complete.memory.target)
        assertEquals(APP_A, complete.memory.anchor?.packageName)
        assertEquals(ROOT_NARROW, complete.memory.vacancy?.rootId)
    }

    @Test
    fun stockPickerCountsAsVisibleTopForPlaceholderTarget() {
        val expected = target(
            APP_A_TASK,
            ROOT_WIDE,
            placeholder(ROOT_NARROW),
            ROOT_NARROW,
        )
        val hiddenPlaceholder = placeholder(ROOT_NARROW).copy(
            topPackageName = LAUNCHER,
            topActivityName = PICKER_ACTIVITY,
        )
        val decision = reduce(
            SplitRoutingMemory(target = expected),
            observation(
                area = 3,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, picker(ROOT_NARROW), hiddenPlaceholder),
                    root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertTrue(decision.splitVisible)
        assertTrue(decision.actions.isEmpty())
        assertNull(decision.memory.target)
        assertEquals(APP_A, decision.memory.anchor?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.vacancy?.rootId)
    }

    @Test
    fun fullscreenAppSupersedesUnfinishedPlaceholderTarget() {
        val expected = target(
            APP_A_TASK,
            ROOT_WIDE,
            placeholder(ROOT_NARROW),
            ROOT_NARROW,
        )
        val decision = reduce(
            SplitRoutingMemory(target = expected),
            observation(
                area = 4,
                roots = listOf(
                    root(ROOT_FULL, FULL, APP_B_TASK),
                    root(
                        ROOT_NARROW,
                        NARROW,
                        picker(ROOT_NARROW).copy(visible = false),
                        placeholder(ROOT_NARROW).copy(visible = false),
                    ),
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE, visible = false),
                    ),
                ),
            ),
        )

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(APP_B, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(20, ROOT_NARROW, promoteInPlace = false)),
            decision.actions,
        )
        assertFalse(decision.actions.any { action ->
            action is SplitRoutingAction.PlaceTask && action.taskId == 10
        })
    }

    @Test
    fun foregroundLaunchSupersedesStalePickerTargetAndUsesPickerRoot() {
        val expected = target(APP_C_TASK, ROOT_NARROW, APP_A_TASK, ROOT_WIDE)
        val decision = reduce(
            SplitRoutingMemory(target = expected),
            observation(
                area = 3,
                roots = listOf(
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE),
                        APP_A_TASK.copy(
                            rootId = ROOT_WIDE,
                            bounds = WIDE,
                            topPackageName = APP_B,
                        ),
                    ),
                    root(
                        ROOT_NARROW,
                        NARROW,
                        picker(ROOT_NARROW),
                        APP_C_TASK.copy(
                            rootId = ROOT_NARROW,
                            bounds = NARROW,
                            topPackageName = LAUNCHER,
                            topActivityName = PICKER_ACTIVITY,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.first?.preferredRootId)
        assertEquals(APP_B, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(20, ROOT_NARROW, promoteInPlace = false)),
            decision.actions,
        )
        assertFalse(decision.actions.any { action ->
            action is SplitRoutingAction.PlaceTask && action.taskId == 10
        })
    }

    @Test
    fun areaThreeWithExpectedTaskUnderPickerPromotesItInSameRoot() {
        val target = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW)
        val picker = picker(ROOT_NARROW)
        val decision = reduce(
            SplitRoutingMemory(target = target),
            observation(
                area = 3,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, picker, APP_B_TASK.copy(rootId = ROOT_NARROW)),
                    root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertFalse(decision.splitVisible)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(20, ROOT_NARROW, promoteInPlace = true)),
            decision.actions,
        )
    }

    @Test
    fun pickerWaitsUntilNewTaskAppearsThenTargetsOnlyItsRoot() {
        val pickerSnapshot = observation(
            area = 3,
            roots = listOf(
                root(ROOT_NARROW, NARROW, picker(ROOT_NARROW), APP_B_TASK.copy(rootId = ROOT_NARROW)),
                root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
            ),
        )
        val waiting = reduce(SplitRoutingMemory(), pickerSnapshot)
        assertTrue(waiting.actions.isEmpty())
        assertEquals(setOf(20), waiting.memory.vacancy?.baselineTaskIds)

        val chosenUnderPicker = reduce(
            waiting.memory,
            observation(
                area = 3,
                roots = listOf(
                    root(
                        ROOT_NARROW,
                        NARROW,
                        picker(ROOT_NARROW),
                        APP_C_TASK.copy(rootId = ROOT_NARROW),
                        APP_B_TASK.copy(rootId = ROOT_NARROW),
                    ),
                    root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertEquals(APP_A, chosenUnderPicker.memory.target?.first?.packageName)
        assertEquals(APP_C, chosenUnderPicker.memory.target?.second?.packageName)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(30, ROOT_NARROW, promoteInPlace = true)),
            chosenUnderPicker.actions,
        )
    }

    @Test
    fun reopeningExistingPaneAppOverCompanionMovesOnlyCrossedTask() {
        val waiting = reduce(
            SplitRoutingMemory(),
            observation(
                area = 3,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, picker(ROOT_NARROW), APP_B_TASK.copy(rootId = ROOT_NARROW)),
                    root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )
        val decision = reduce(
            waiting.memory,
            observation(
                area = 3,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, placeholder(ROOT_NARROW)),
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE),
                        APP_A_TASK.copy(
                            rootId = ROOT_WIDE,
                            bounds = WIDE,
                            visible = true,
                            topPackageName = APP_B,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(20, ROOT_NARROW, promoteInPlace = false)),
            decision.actions,
        )
        assertFalse(decision.actions.any { action ->
            action is SplitRoutingAction.PlaceTask && action.taskId == 10
        })
    }

    @Test
    fun closingPickerKeepsAnchorFullscreenAndNextAppFillsVacancy() {
        val waiting = reduce(
            SplitRoutingMemory(),
            observation(
                area = 3,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, picker(ROOT_NARROW)),
                    root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )
        val collapsed = reduce(
            waiting.memory,
            observation(
                area = 4,
                roots = listOf(root(ROOT_WIDE, FULL, APP_A_TASK.copy(rootId = ROOT_WIDE))),
            ),
        )
        assertNull(collapsed.memory.target)
        assertEquals(APP_A, collapsed.memory.anchor?.packageName)
        assertFalse(collapsed.memory.vacancy?.restorePlaceholderAfterRecovery ?: true)

        val rutubeLaunch = reduce(
            collapsed.memory,
            observation(
                area = 4,
                roots = listOf(
                    root(ROOT_FULL, FULL, APP_C_TASK),
                    root(
                        ROOT_WIDE,
                        FULL,
                        APP_A_TASK.copy(rootId = ROOT_WIDE, visible = false),
                    ),
                ),
            ),
        )
        assertEquals(APP_A, rutubeLaunch.memory.target?.first?.packageName)
        assertEquals(APP_C, rutubeLaunch.memory.target?.second?.packageName)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(30, ROOT_NARROW, promoteInPlace = false)),
            rutubeLaunch.actions,
        )
    }

    @Test
    fun physicalRootSwapDoesNotChangeTaskOwnership() {
        val expected = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW)
        val swappedGeometry = observation(
            area = 3,
            roots = listOf(
                root(
                    ROOT_NARROW,
                    WIDE,
                    APP_B_TASK.copy(rootId = ROOT_NARROW, bounds = WIDE),
                ),
                root(
                    ROOT_WIDE,
                    NARROW,
                    APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = NARROW),
                ),
            ),
        )

        val decision = reduce(SplitRoutingMemory(target = expected), swappedGeometry)

        assertTrue(decision.splitVisible)
        assertTrue(decision.actions.isEmpty())
        assertNull(decision.memory.target)
    }

    @Test
    fun stablePairRemembersBothRootsAndTheLastFocusedPane() {
        val decision = reduce(
            SplitRoutingMemory(),
            observation(
                area = 3,
                // am stack list puts the focused root first.
                roots = listOf(
                    root(ROOT_WIDE, WIDE, APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                    root(ROOT_NARROW, NARROW, APP_A_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW)),
                ),
            ),
        )

        assertTrue(decision.splitVisible)
        assertEquals(APP_A, decision.memory.stablePair?.pane(ROOT_NARROW)?.packageName)
        assertEquals(APP_B, decision.memory.stablePair?.pane(ROOT_WIDE)?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.stablePair?.lastFocusedRootId)
    }

    @Test
    fun fullscreenThirdAppReplacesPaneOppositeLastFocusWithoutPlaceholder() {
        val memory = stableMemory(lastFocusedRootId = ROOT_NARROW)
        val decision = reduce(
            memory,
            fullscreenThirdAppObservation(APP_C_TASK),
        )

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.first?.preferredRootId)
        assertEquals(APP_C, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(APP_C_TASK.id, ROOT_WIDE, promoteInPlace = false)),
            decision.actions,
        )
        assertFalse(decision.actions.any { it is SplitRoutingAction.LaunchPlaceholder })
        assertFalse(decision.actions.any { action ->
            action is SplitRoutingAction.PlaceTask && action.taskId == APP_A_TASK.id
        })
    }

    @Test
    fun fullscreenThirdAppPreservesFocusedWidePaneAndReplacesNarrowPane() {
        val memory = stableMemory(lastFocusedRootId = ROOT_WIDE)
        val decision = reduce(
            memory,
            fullscreenThirdAppObservation(APP_C_TASK),
        )

        assertEquals(APP_B, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.first?.preferredRootId)
        assertEquals(APP_C, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(APP_C_TASK.id, ROOT_NARROW, promoteInPlace = false)),
            decision.actions,
        )
        assertFalse(decision.actions.any { it is SplitRoutingAction.LaunchPlaceholder })
    }

    @Test
    fun fullscreenThirdAppUsesWidePaneWhenFocusIsUnknown() {
        val decision = reduce(
            stableMemory(lastFocusedRootId = null),
            fullscreenThirdAppObservation(APP_C_TASK),
        )

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.first?.preferredRootId)
        assertEquals(ROOT_WIDE, decision.memory.target?.second?.preferredRootId)
    }

    @Test
    fun firmwarePlacementDoesNotOverrideOppositeLastFocusPolicy() {
        val decision = reduce(
            stableMemory(lastFocusedRootId = ROOT_NARROW),
            observation(
                area = 3,
                roots = listOf(
                    root(
                        ROOT_NARROW,
                        NARROW,
                        APP_C_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW),
                        APP_A_TASK.copy(
                            rootId = ROOT_NARROW,
                            bounds = NARROW,
                            topPackageName = APP_C,
                        ),
                    ),
                    root(ROOT_WIDE, WIDE, APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.first?.preferredRootId)
        assertEquals(APP_C, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(APP_C_TASK.id, ROOT_WIDE, false)),
            decision.actions,
        )
    }

    @Test
    fun explicitPickerPaneWinsOverLastFocusForThirdApp() {
        val pickerFrame = observation(
            area = 3,
            roots = listOf(
                root(
                    ROOT_NARROW,
                    NARROW,
                    picker(ROOT_NARROW),
                    APP_A_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW),
                ),
                root(ROOT_WIDE, WIDE, APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
            ),
        )
        val waiting = reduce(stableMemory(lastFocusedRootId = ROOT_NARROW), pickerFrame)
        val chosen = reduce(
            waiting.memory,
            observation(
                area = 4,
                roots = listOf(
                    root(ROOT_FULL, FULL, APP_C_TASK),
                    root(
                        ROOT_NARROW,
                        NARROW,
                        picker(ROOT_NARROW),
                        APP_A_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW),
                    ),
                    root(ROOT_WIDE, WIDE, APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertEquals(APP_B, chosen.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, chosen.memory.target?.first?.preferredRootId)
        assertEquals(APP_C, chosen.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, chosen.memory.target?.second?.preferredRootId)
        assertFalse(chosen.actions.any { it is SplitRoutingAction.LaunchPlaceholder })
    }

    @Test
    fun pickerRecoversStableCompanionHiddenBehindTheVisiblePane() {
        val memory = stableMemory(lastFocusedRootId = ROOT_WIDE).copy(
            anchor = APP_A_TASK.toExpected(ROOT_WIDE),
            vacancy = SplitVacancy(
                rootId = ROOT_NARROW,
                baselineTaskIds = emptySet(),
                restorePlaceholderAfterRecovery = false,
            ),
        )
        val observation = observation(
            area = 3,
            roots = listOf(
                root(ROOT_NARROW, NARROW, picker(ROOT_NARROW)),
                root(
                    ROOT_WIDE,
                    WIDE,
                    APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE),
                    APP_B_TASK.copy(
                        rootId = ROOT_WIDE,
                        bounds = WIDE,
                        visible = false,
                        topPackageName = APP_A,
                    ),
                ),
            ),
        )

        val decision = reduce(memory, observation)

        assertEquals(APP_A, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.first?.preferredRootId)
        assertEquals(APP_B, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(APP_B_TASK.id, ROOT_NARROW, false)),
            decision.actions,
        )
    }

    @Test
    fun rememberedPickerPaneWinsIfPickerClosesAlongsideThirdAppLaunch() {
        val pickerFrame = observation(
            area = 3,
            roots = listOf(
                root(
                    ROOT_NARROW,
                    NARROW,
                    picker(ROOT_NARROW),
                    APP_A_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW),
                ),
                root(ROOT_WIDE, WIDE, APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
            ),
        )
        val waiting = reduce(stableMemory(lastFocusedRootId = ROOT_NARROW), pickerFrame)
        val chosen = reduce(waiting.memory, fullscreenThirdAppObservation(APP_C_TASK))

        assertEquals(APP_B, chosen.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, chosen.memory.target?.first?.preferredRootId)
        assertEquals(APP_C, chosen.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, chosen.memory.target?.second?.preferredRootId)
    }

    @Test
    fun stableAppOpenedInOppositePaneReturnsToItsRememberedRoot() {
        val decision = reduce(
            stableMemory(lastFocusedRootId = ROOT_WIDE),
            observation(
                area = 3,
                roots = listOf(
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE),
                        APP_B_TASK.copy(
                            rootId = ROOT_WIDE,
                            bounds = WIDE,
                            topPackageName = APP_A,
                        ),
                    ),
                    root(ROOT_NARROW, NARROW),
                ),
            ),
        )

        assertEquals(APP_B, decision.memory.target?.first?.packageName)
        assertEquals(ROOT_WIDE, decision.memory.target?.first?.preferredRootId)
        assertEquals(APP_A, decision.memory.target?.second?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(APP_A_TASK.id, ROOT_NARROW, false)),
            decision.actions,
        )
    }

    @Test
    fun disappearingThirdAppRestoresTheOriginalStablePairIntent() {
        val replacing = reduce(
            stableMemory(lastFocusedRootId = ROOT_NARROW),
            fullscreenThirdAppObservation(APP_C_TASK),
        )
        val recovered = reduce(
            replacing.memory,
            observation(
                area = 3,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, APP_A_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW)),
                    root(ROOT_WIDE, WIDE, APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertNull(recovered.memory.target)
        assertEquals(APP_A, recovered.memory.stablePair?.pane(ROOT_NARROW)?.packageName)
        assertEquals(APP_B, recovered.memory.stablePair?.pane(ROOT_WIDE)?.packageName)
        assertTrue(recovered.actions.isEmpty())
    }

    @Test
    fun latestFullscreenLaunchSupersedesEarlierThirdAppForTheSamePane() {
        val replacingC = reduce(
            stableMemory(lastFocusedRootId = ROOT_NARROW),
            fullscreenThirdAppObservation(APP_C_TASK),
        )
        val appD = task(40, APP_D, "$APP_D.MainActivity", ROOT_FULL, FULL)
        val replacingD = reduce(
            replacingC.memory,
            fullscreenThirdAppObservation(appD, extraHiddenTask = APP_C_TASK),
        )

        assertEquals(APP_A, replacingD.memory.target?.first?.packageName)
        assertEquals(APP_D, replacingD.memory.target?.second?.packageName)
        assertEquals(ROOT_WIDE, replacingD.memory.target?.second?.preferredRootId)
        assertEquals(
            listOf(SplitRoutingAction.PlaceTask(appD.id, ROOT_WIDE, promoteInPlace = false)),
            replacingD.actions,
        )
    }

    @Test
    fun launcherFramesPreserveAnchorAndNeverMoveTasks() {
        var memory = SplitRoutingMemory(
            anchor = APP_A_TASK.toExpected(ROOT_WIDE),
            vacancy = SplitVacancy(ROOT_NARROW, emptySet(), false),
        )
        val launcher = task(1, LAUNCHER, "com.android.launcher3.Launcher", ROOT_FULL, FULL)
        val frame = observation(
            area = 4,
            roots = listOf(
                root(ROOT_FULL, FULL, launcher, activityType = "home"),
                root(ROOT_WIDE, FULL, APP_A_TASK.copy(rootId = ROOT_WIDE, visible = false)),
            ),
        )

        repeat(20) {
            val decision = reduce(memory, frame)
            assertEquals(APP_A, decision.memory.anchor?.packageName)
            assertFalse(decision.actions.any { it is SplitRoutingAction.PlaceTask })
            memory = decision.memory
        }
    }

    @Test
    fun recreatedTaskWithSamePackageContinuesPersistedTarget() {
        val expected = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW)
        val recreated = APP_B_TASK.copy(id = 21, rootId = ROOT_NARROW, bounds = NARROW)
        val decision = reduce(
            SplitRoutingMemory(target = expected),
            pairObservation(area = 3, narrow = recreated, wide = APP_A_TASK),
        )

        assertTrue(decision.splitVisible)
        assertNull(decision.memory.target)
    }

    @Test
    fun unknownPickerAfterRestartWaitsWithoutGuessingOrMoving() {
        val decision = reduce(
            SplitRoutingMemory(),
            observation(
                area = 3,
                recovering = true,
                roots = listOf(
                    root(ROOT_NARROW, NARROW, picker(ROOT_NARROW), APP_B_TASK.copy(rootId = ROOT_NARROW)),
                    root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
                ),
            ),
        )

        assertTrue(decision.actions.isEmpty())
        assertNull(decision.memory.target)
        assertEquals(APP_A, decision.memory.anchor?.packageName)
        assertEquals(ROOT_NARROW, decision.memory.vacancy?.rootId)
    }

    @Test
    fun missingTargetAppNeverMovesCorrectNeighborEvenAfterManyFrames() {
        var memory = SplitRoutingMemory(
            target = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW),
        )
        val frame = observation(
            area = 3,
            roots = listOf(
                root(ROOT_NARROW, NARROW, placeholder(ROOT_NARROW)),
                root(ROOT_WIDE, WIDE, APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE)),
            ),
        )

        repeat(20) {
            val decision = reduce(memory, frame)
            assertFalse(decision.actions.any { action ->
                action is SplitRoutingAction.PlaceTask && action.taskId == 10
            })
            assertNull(decision.memory.target)
            assertEquals(APP_A, decision.memory.anchor?.packageName)
            memory = decision.memory
        }
    }

    @Test
    fun freshFullscreenAppCancelsHiddenStalePairWithoutPromotingIt() {
        val staleTarget = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW)
        val decision = reduce(
            SplitRoutingMemory(target = staleTarget),
            observation(
                area = 4,
                roots = listOf(
                    root(ROOT_FULL, FULL, APP_C_TASK),
                    root(
                        ROOT_NARROW,
                        NARROW,
                        APP_B_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW, visible = false),
                    ),
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE, visible = false),
                    ),
                ),
            ),
        )

        assertNull(decision.memory.target)
        assertEquals(APP_C, decision.memory.anchor?.packageName)
        assertFalse(decision.actions.any { it is SplitRoutingAction.PlaceTask })
    }

    @Test
    fun freshFullscreenAppCancelsTargetWhenOneMemberDisappeared() {
        val staleTarget = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW)
        val decision = reduce(
            SplitRoutingMemory(target = staleTarget),
            observation(
                area = 4,
                roots = listOf(
                    root(ROOT_FULL, FULL, APP_C_TASK),
                    root(ROOT_NARROW, NARROW),
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE, visible = false),
                    ),
                ),
            ),
        )

        assertNull(decision.memory.target)
        assertEquals(APP_C, decision.memory.anchor?.packageName)
        assertFalse(decision.actions.any { it is SplitRoutingAction.PlaceTask })
    }

    @Test
    fun homeCancelsUnfinishedPairWithoutRestoringHiddenMembers() {
        val launcher = task(1, LAUNCHER, "com.android.launcher3.Launcher", ROOT_FULL, FULL)
        val decision = reduce(
            SplitRoutingMemory(
                target = target(APP_A_TASK, ROOT_WIDE, APP_B_TASK, ROOT_NARROW),
            ),
            observation(
                area = 4,
                roots = listOf(
                    root(ROOT_FULL, FULL, launcher, activityType = "home"),
                    root(
                        ROOT_NARROW,
                        NARROW,
                        APP_B_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW, visible = false),
                    ),
                    root(
                        ROOT_WIDE,
                        WIDE,
                        APP_A_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE, visible = false),
                    ),
                ),
            ),
        )

        assertNull(decision.memory.target)
        assertFalse(decision.actions.any { it is SplitRoutingAction.PlaceTask })
    }

    private fun reduce(
        memory: SplitRoutingMemory,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision = SplitRoutingReducer.reduce(memory, observation)

    private fun expanded(app: SplitTask): SplitRoutingObservation = observation(
        area = 4,
        roots = listOf(
            root(ROOT_FULL, FULL, app),
            root(ROOT_NARROW, NARROW),
            root(ROOT_WIDE, WIDE),
        ),
    )

    private fun pairObservation(
        area: Int,
        narrow: SplitTask,
        wide: SplitTask,
        taskBoundsMatchRoots: Boolean = true,
    ): SplitRoutingObservation = observation(
        area = area,
        roots = listOf(
            root(
                ROOT_NARROW,
                NARROW,
                narrow.copy(
                    rootId = ROOT_NARROW,
                    bounds = if (taskBoundsMatchRoots) NARROW else FULL,
                ),
            ),
            root(
                ROOT_WIDE,
                WIDE,
                wide.copy(
                    rootId = ROOT_WIDE,
                    bounds = if (taskBoundsMatchRoots) WIDE else FULL,
                ),
            ),
        ),
    )

    private fun stableMemory(lastFocusedRootId: Int?): SplitRoutingMemory = SplitRoutingMemory(
        stablePair = SplitStablePair(
            first = APP_A_TASK.toExpected(ROOT_NARROW),
            second = APP_B_TASK.toExpected(ROOT_WIDE),
            lastFocusedRootId = lastFocusedRootId,
        ),
    )

    private fun fullscreenThirdAppObservation(
        app: SplitTask,
        extraHiddenTask: SplitTask? = null,
    ): SplitRoutingObservation = observation(
        area = 4,
        roots = listOfNotNull(
            root(ROOT_FULL, FULL, app),
            extraHiddenTask?.let { hidden ->
                root(
                    ROOT_FULL + 1,
                    FULL,
                    hidden.copy(rootId = ROOT_FULL + 1, visible = false),
                )
            },
            root(
                ROOT_NARROW,
                NARROW,
                APP_A_TASK.copy(rootId = ROOT_NARROW, bounds = NARROW, visible = false),
            ),
            root(
                ROOT_WIDE,
                WIDE,
                APP_B_TASK.copy(rootId = ROOT_WIDE, bounds = WIDE, visible = false),
            ),
        ),
    )

    private fun observation(
        area: Int,
        roots: List<SplitRootTask>,
        recovering: Boolean = false,
    ) = SplitRoutingObservation(
        area = area,
        firstNativeRootId = ROOT_NARROW,
        secondNativeRootId = ROOT_WIDE,
        snapshot = SplitTaskSnapshot(roots),
        eligiblePackages = setOf(APP_A, APP_B, APP_C, APP_D),
        recovering = recovering,
    )

    private fun root(
        id: Int,
        bounds: SplitBounds,
        vararg tasks: SplitTask,
        activityType: String? = null,
    ) = SplitRootTask(id, bounds, 0, activityType, tasks.toList())

    private fun task(
        id: Int,
        packageName: String,
        activityName: String,
        rootId: Int,
        bounds: SplitBounds,
        visible: Boolean = true,
        topPackageName: String? = packageName,
    ) = SplitTask(
        id = id,
        packageName = packageName,
        activityName = activityName,
        bounds = bounds,
        visible = visible,
        rootId = rootId,
        topPackageName = topPackageName,
        topActivityName = activityName,
    )

    private fun picker(rootId: Int) = task(
        202,
        LAUNCHER,
        PICKER_ACTIVITY,
        rootId,
        NARROW,
    )

    private fun placeholder(rootId: Int) = task(
        99,
        PLACEHOLDER_PACKAGE,
        PLACEHOLDER_ACTIVITY,
        rootId,
        NARROW,
    )

    private fun target(
        first: SplitTask,
        firstRoot: Int,
        second: SplitTask,
        secondRoot: Int,
    ) = SplitPairTarget(
        first = first.toExpected(firstRoot),
        second = second.toExpected(secondRoot),
    )

    private fun SplitTask.toExpected(rootId: Int) = SplitExpectedTask(
        id,
        packageName,
        activityName,
        rootId,
    )

    private companion object {
        const val ROOT_NARROW = 2
        const val ROOT_WIDE = 3
        const val ROOT_FULL = 4
        const val APP_A = "ru.yandex.yandexnavi"
        const val APP_B = "ru.yandex.music"
        const val APP_C = "ru.rutube.app"
        const val APP_D = "com.vk.vkvideo"
        const val LAUNCHER = "com.android.launcher3"
        const val PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
        const val PLACEHOLDER_PACKAGE = "dev.denza.apps"
        const val PLACEHOLDER_ACTIVITY =
            "dev.denza.apps.feature.split.SplitPlaceholderActivity"
        val FULL = SplitBounds(0, 0, 2560, 1600)
        val NARROW = SplitBounds(24, 112, 856, 1472)
        val WIDE = SplitBounds(880, 112, 2536, 1472)
        val APP_A_TASK = taskStatic(10, APP_A)
        val APP_B_TASK = taskStatic(20, APP_B)
        val APP_C_TASK = taskStatic(30, APP_C)

        private fun taskStatic(id: Int, packageName: String) = SplitTask(
            id = id,
            packageName = packageName,
            activityName = "$packageName.MainActivity",
            bounds = FULL,
            visible = true,
            rootId = ROOT_FULL,
            topPackageName = packageName,
            topActivityName = "$packageName.MainActivity",
        )
    }
}
