package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPickerShellSessionTest {
    @Test
    fun nativePickerReplacementWaitsForCommittedSplitAndReleasedDivider() {
        var areaReads = 0
        var inputReads = 0
        var pauses = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(if (areaReads < 2) 2 else 3)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        if (inputReads == 1) ACTIVE_DIVIDER_TOUCH else NO_ACTIVE_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = { pauses += 1 },
        )

        assertTrue(session.awaitNativePickerCommit())
        assertEquals(11, areaReads)
        assertEquals(11, inputReads)
        assertEquals(10, pauses)
    }

    @Test
    fun cancelledNativePickerDragRejectsTransientBalancedAreaAfterRelease() {
        var areaReads = 0
        var inputReads = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(if (areaReads <= 8) 3 else 2)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        if (inputReads == 1) ACTIVE_DIVIDER_TOUCH else NO_ACTIVE_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = {},
        )

        assertFalse(session.awaitNativePickerCommit())
        assertEquals(13, areaReads)
        assertEquals(13, inputReads)
    }

    @Test
    fun cancelledNativePickerDragDoesNotStartReplacement() {
        var reads = 0
        var pauses = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        reads += 1
                        intParcel(2)
                    }
                    "dumpsys input" -> NO_ACTIVE_TOUCH
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = { pauses += 1 },
        )

        assertFalse(session.awaitNativePickerCommit())
        assertEquals(5, reads)
        assertEquals(reads - 1, pauses)
    }

    @Test
    fun activeDividerCommitWaitIsBoundedWithoutMutation() {
        var areaReads = 0
        var inputReads = 0
        var pauses = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(3)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        ACTIVE_DIVIDER_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = { pauses += 1 },
        )

        assertFalse(session.awaitNativePickerCommit())
        assertEquals(150, areaReads)
        assertEquals(150, inputReads)
        assertEquals(149, pauses)
    }

    @Test
    fun nativePickerMutationIsRejectedWhenAreaChangesAfterCommitWait() {
        var areaReads = 0
        var inputReads = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(if (areaReads <= 10) 3 else 2)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        NO_ACTIVE_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = {},
        )

        assertTrue(session.awaitNativePickerCommit())
        assertFalse(session.nativePickerMutationAllowed())
        assertEquals(11, areaReads)
        assertEquals(10, inputReads)
    }

    @Test
    fun panesHaveStableOpposites() {
        assertEquals(SplitPane.SECONDARY, SplitPane.PRIMARY.other())
        assertEquals(SplitPane.PRIMARY, SplitPane.SECONDARY.other())
    }

    @Test
    fun explicitOpenCreatesTwoPickerBasesAndPrunesOldPaneTasks() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }
        val split = session(fake)

        split.openPickers(PICKERS, preservedPackages = emptyMap())

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertFalse(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertEquals(3, fake.area)
        assertTrue(fake.commands.any { it.contains("remove-task 40") })
        assertTrue(fake.commands.any { it.contains("remove-task 41") })
        assertFalse(fake.commands.any { it == "service call activity_task 115" })
    }

    @Test
    fun liveFirmwarePickerPlusBootstrapAppBecomesTwoPickerBases() {
        val fake = FakeShell(secondaryBootstrapPackage = STOCK_BOOTSTRAP_PACKAGE)

        val hosts = session(fake).openPickers(PICKERS, preservedPackages = emptyMap())

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasPackage(SECONDARY_ROOT, NAVIGATOR))
        assertEquals(1, fake.taskCount(PRIMARY_ROOT))
        assertEquals(1, fake.taskCount(SECONDARY_ROOT))
        assertEquals(2, hosts.values.toSet().size)
        assertTrue(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it.contains("replace-task-base ") })
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("byd.intent.category.START_IVI_PRIMARY") &&
                    command.contains(PRIMARY_PICKER) &&
                    command.endsWith("-f 0x18010000")
            },
        )
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("byd.intent.category.START_IVI_SECOND") &&
                    command.contains(SECONDARY_PICKER) &&
                    command.endsWith("-f 0x18010000")
            },
        )
        assertFalse(fake.commands.any { it.contains("SplitTaskProxyMain start-in-task ") })
        assertFalse(fake.commands.any { it == "am stack move-task 100 $PRIMARY_ROOT true" })
        assertFalse(fake.commands.any { it == "am stack move-task 101 $SECONDARY_ROOT true" })
    }

    @Test
    fun explicitOpenPlacesPickersInNativePaneRootsWithoutSyntheticDividerDrag() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).openPickers(PICKERS, preservedPackages = emptyMap())

        assertFalse(fake.commands.any { it == "service call activity_task 115" })
        SplitPane.entries.forEach { pane ->
            val root = if (pane == SplitPane.PRIMARY) PRIMARY_ROOT else SECONDARY_ROOT
            val picker = PICKERS.getValue(pane)
            assertTrue(
                fake.commands.any { command ->
                    command.startsWith("am start ") &&
                        command.contains(" '$picker' ") &&
                        command.contains("byd.intent.category.START_IVI_")
                },
            )
            assertTrue(fake.hasActivity(root, picker.substringAfter('/')))
            assertEquals(1, fake.taskCount(root))
        }
        assertFalse(fake.hasPackage(PRIMARY_ROOT, STOCK_PICKER_PACKAGE))
        assertFalse(fake.commands.any { it.startsWith("input swipe ") })
        assertFalse(fake.commands.any { it.contains("SplitTaskProxyMain start-in-task ") })
        assertFalse(fake.commands.any { it.contains("replace-task-base ") })
    }

    @Test
    fun fullscreenNativeHostIsReplacedByPickerAlreadyInLiveRootBounds() {
        val fake = FakeShell(nativeBootstrapStartsFullscreen = true)

        val hosts = session(fake).openPickers(PICKERS, preservedPackages = emptyMap())

        assertEquals(PRIMARY_BOUNDS, fake.taskBounds(hosts.getValue(SplitPane.PRIMARY)))
        assertEquals(SECONDARY_BOUNDS, fake.taskBounds(hosts.getValue(SplitPane.SECONDARY)))
        assertFalse(fake.commands.any { it.startsWith("am task resize ") })
    }

    @Test
    fun alreadyRunningOwnedSceneIsAdoptedWithoutTaskMutation() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.commands.clear()

        val existing = split.existingOwnedSession(PICKER_COMPONENTS)

        assertEquals(hosts.getValue(SplitPane.PRIMARY), existing?.get(SplitPane.PRIMARY)?.hostTaskId)
        assertEquals(navigator.appTaskId, existing?.get(SplitPane.PRIMARY)?.appTaskId)
        assertEquals(NAVIGATOR, existing?.get(SplitPane.PRIMARY)?.appPackageName)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), existing?.get(SplitPane.SECONDARY)?.hostTaskId)
        assertEquals(null, existing?.get(SplitPane.SECONDARY)?.appTaskId)
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task focus ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun dividerResizeRepairsPickerBaseStrandedAwayFromItsRecordedApp() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val previous = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                appTaskId = navigator.appTaskId,
                packageName = navigator.packageName,
            ),
        )

        // The live BYD resize keeps the two visible surfaces on their visual sides, but leaves
        // the hidden base of the app in the old root. The result has both picker bases together.
        fake.moveTask(navigator.appTaskId, PRIMARY_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), SECONDARY_ROOT)
        fake.preserveBoundsOnShellMove = true
        fake.commands.clear()

        val repaired = split.reconcileDividerResize(PICKER_COMPONENTS, previous)

        assertEquals(
            hosts.getValue(SplitPane.SECONDARY),
            repaired?.get(SplitPane.PRIMARY)?.hostTaskId,
        )
        assertEquals(navigator.appTaskId, repaired?.get(SplitPane.PRIMARY)?.appTaskId)
        assertEquals(
            hosts.getValue(SplitPane.PRIMARY),
            repaired?.get(SplitPane.SECONDARY)?.hostTaskId,
        )
        assertEquals(null, repaired?.get(SplitPane.SECONDARY)?.appTaskId)
        assertTrue(
            fake.commands.any { command ->
                command == "am stack move-task ${hosts.getValue(SplitPane.SECONDARY)} " +
                    "$PRIMARY_ROOT false"
            },
        )
        assertTrue(
            fake.commands.any { command ->
                command.startsWith(
                    "am task resize ${hosts.getValue(SplitPane.SECONDARY)} ",
                )
            },
        )
    }

    @Test
    fun transientResizeWithBothPickerBasesInOneRootEmitsNoVisiblePickerObservation() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        // Live BYD resize transient: the visible app is alone in one native root while both
        // permanent picker bases temporarily share the other. Treating the top picker as a
        // settled reveal would overwrite the still-live APP slot before resize repair runs.
        fake.moveTask(navigator.appTaskId, PRIMARY_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), SECONDARY_ROOT)

        assertEquals(
            null,
            split.observePickerTask(
                hosts.getValue(SplitPane.PRIMARY),
                PICKER_COMPONENTS,
            ),
        )
    }

    @Test
    fun dividerResizeDoesNotGuessOwnershipWithoutARecordedApp() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val previous = SplitPane.entries.associateWith { pane ->
            SplitPickerObservedPane(hostTaskId = hosts.getValue(pane))
        }
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), SECONDARY_ROOT)
        fake.commands.clear()

        val repaired = split.reconcileDividerResize(PICKER_COMPONENTS, previous)

        assertEquals(null, repaired)
        assertFalse(fake.commands.any { command -> command.startsWith("am stack move-task ") })
        assertFalse(fake.commands.any { command -> command.startsWith("am task resize ") })
    }

    @Test
    fun ownedSceneCoveredByFullscreenIsFocusedInsteadOfRebuilt() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.area = 4
        fake.commands.clear()

        val hidden = checkNotNull(
            split.existingOwnedSession(
                PICKER_COMPONENTS,
                mapOf(
                    SplitPane.PRIMARY to SplitPickerExpectedApp(
                        navigator.appTaskId,
                        navigator.packageName,
                    ),
                    SplitPane.SECONDARY to SplitPickerExpectedApp(
                        music.appTaskId,
                        music.packageName,
                    ),
                ),
            ),
        )
        val revealed = split.revealOwnedSession(hidden, PICKER_COMPONENTS)

        assertEquals(hidden, revealed)
        assertEquals(3, fake.area)
        assertTrue(fake.commands.any { it == "am task focus ${hidden.getValue(SplitPane.PRIMARY).appTaskId}" })
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun sceneMissingOneOwnedBaseIsNotAdopted() {
        val fake = FakeShell().apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        assertEquals(null, session(fake).existingOwnedSession(PICKER_COMPONENTS))
    }

    @Test
    fun nativeEdgeCollapseAdoptsOnlyTheSurvivingOwnedRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.dismissPane(PRIMARY_ROOT)
        fake.commands.clear()

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                    appTaskId = navigator.appTaskId,
                    packageName = navigator.packageName,
                ),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), collapsed?.hostTaskId)
        assertEquals(null, collapsed?.appTaskId)
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task focus ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun nativeEdgeCollapseAcceptsRecordedTaskDetachedOutsideNativeRoots() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val vanishedHost = hosts.getValue(SplitPane.PRIMARY)
        fake.dismissPane(PRIMARY_ROOT)
        fake.addTask(FULL_ROOT, vanishedHost, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(vanishedHost),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hosts.getValue(SplitPane.SECONDARY),
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), collapsed?.hostTaskId)
    }

    @Test
    fun nativeEdgeCollapseRejectsTrackedTaskStillInCollapsedNativeRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val vanishedHost = hosts.getValue(SplitPane.PRIMARY)
        fake.dismissPane(PRIMARY_ROOT)
        fake.addTask(PRIMARY_ROOT, vanishedHost, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(vanishedHost),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hosts.getValue(SplitPane.SECONDARY),
                ),
            ),
        )

        assertEquals(null, collapsed)
    }

    @Test
    fun nativeEdgeCollapseReattachesExactPickerBehindAppMovedToSurvivorRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        // Live DiLink 5.1 trace: area 2/root 3 survived, but BYD moved the app previously
        // recorded in PRIMARY there and detached both permanent picker bases.
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), FULL_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.SECONDARY), FULL_ROOT)
        fake.moveTask(navigator.appTaskId, FULL_ROOT)
        fake.moveTask(music.appTaskId, FULL_ROOT)
        fake.area = 2
        fake.moveTask(music.appTaskId, SECONDARY_ROOT)
        fake.commands.clear()

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                    appTaskId = music.appTaskId,
                    packageName = music.packageName,
                ),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    appTaskId = navigator.appTaskId,
                    packageName = navigator.packageName,
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.PRIMARY), collapsed?.hostTaskId)
        assertEquals(music.appTaskId, collapsed?.appTaskId)
        assertEquals(MUSIC, collapsed?.appPackageName)
        assertEquals(SECONDARY_ROOT, fake.taskRoot(hosts.getValue(SplitPane.PRIMARY)))
        assertEquals(music.appTaskId.toString(), fake.topTaskId(SECONDARY_ROOT)?.toString())
        assertTrue(
            fake.commands.any {
                it == "am stack move-task ${hosts.getValue(SplitPane.PRIMARY)} " +
                    "$SECONDARY_ROOT false"
            },
        )
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am task focus ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun fullscreenAppDismissalAdoptsItsExactPickerAsTheSurvivor() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.dismissPane(PRIMARY_ROOT)
        fake.moveTask(music.appTaskId, FULL_ROOT)
        fake.commands.clear()

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    appTaskId = music.appTaskId,
                    packageName = music.packageName,
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), collapsed?.hostTaskId)
        assertEquals(null, collapsed?.appTaskId)
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun visibleProductPickerHintResolvesOnlyOneExactNativeTask() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())

        assertEquals(null, split.singleVisiblePickerTaskId(PICKER_COMPONENTS))

        split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(
            hosts.getValue(SplitPane.SECONDARY),
            split.singleVisiblePickerTaskId(PICKER_COMPONENTS),
        )
    }

    @Test
    fun collapsedPickerReattachRollsBackWhenNativeAreaChangesDuringMutation() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), FULL_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.SECONDARY), FULL_ROOT)
        fake.moveTask(music.appTaskId, FULL_ROOT)
        fake.area = 2
        fake.moveTask(music.appTaskId, SECONDARY_ROOT)
        fake.destabilizeAreaOnNextShellMove()
        fake.commands.clear()

        val result = runCatching {
            split.collapsedOwnedSession(
                pickerComponents = PICKER_COMPONENTS,
                expectedPanes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(
                        hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                        appTaskId = music.appTaskId,
                        packageName = music.packageName,
                    ),
                    SplitPane.SECONDARY to SplitPickerObservedPane(
                        hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    ),
                ),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(FULL_ROOT, fake.taskRoot(hosts.getValue(SplitPane.PRIMARY)))
        assertTrue(
            fake.commands.any {
                it == "am stack move-task ${hosts.getValue(SplitPane.PRIMARY)} " +
                    "$SECONDARY_ROOT false"
            },
        )
        assertTrue(
            fake.commands.any {
                it == "am stack move-task ${hosts.getValue(SplitPane.PRIMARY)} $FULL_ROOT false"
            },
        )
        assertFalse(fake.commands.any { it.contains(" remove-task ") })
    }

    @Test
    fun existingPairWithoutPickerBasesGetsThemThroughExactPaneCategories() {
        val fake = FakeShell(tx115RequiresHome = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).openPickers(
            PICKERS,
            preservedPackages = mapOf(
                SplitPane.PRIMARY to NAVIGATOR,
                SplitPane.SECONDARY to MUSIC,
            ),
        )

        assertTrue(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it == "input keyevent KEYCODE_HOME" })
        assertFalse(fake.commands.any { it == "service call activity_task 115" })
    }

    @Test(expected = IllegalStateException::class)
    fun explicitOpenFailsClosedWhenNativePaneLaunchIsRejected() {
        val fake = FakeShell(hostingSucceeds = false)

        session(fake).openPickers(PICKERS, preservedPackages = emptyMap())
    }

    @Test
    fun pickerTapPlacesOrdinaryAppTaskAboveItsOwnPicker() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertEquals(SECONDARY_BOUNDS, fake.taskBounds(placement.appTaskId))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        val appLaunch = fake.commands.first { command ->
            command.startsWith("am start ") && command.contains("'$MUSIC/$MUSIC.MainActivity'")
        }
        assertTrue(appLaunch.contains("-f 0x18200000"))
        assertTrue(appLaunch.contains("byd.intent.category.START_IVI_SECOND"))
        assertFalse(fake.commands.any { it.contains("SplitTaskProxyMain start-in-task ") })
        assertEquals("$MUSIC.MainActivity", fake.taskBaseActivity(placement.appTaskId))
    }

    @Test
    fun pickerTapAfterPeerDismissalKeepsSelectedAppFullscreen() {
        val fake = FakeShell(renderEmptyNativeRootMarker = true)
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.dismissPane(PRIMARY_ROOT)

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(2, fake.area)
        assertEquals(FULL, fake.taskBounds(placement.appTaskId))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it == "input keyevent KEYCODE_HOME" })
    }

    /**
     * Live trace 2026-08-16 17:52:09, from the retired host era: Navigator's singleTask launcher
     * could not remain above host #398. It created #399 in the opposite root and made SmartMulti
     * rebuild both panes. That is why a target is launched straight into its own pane.
     */
    @Test
    fun singleTaskLauncherIsPlacedByDirectLaunchIntoItsPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(
                NAVIGATOR,
                "$NAVIGATOR/$NAVIGATOR.MainActivity",
                launchMode = 2,
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(NAVIGATOR, placement.packageName)
        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("'$NAVIGATOR/$NAVIGATOR.MainActivity'") &&
                    command.contains("byd.intent.category.START_IVI_PRIMARY") &&
                    command.contains("-f 0x10200000")
            },
        )
    }

    @Test
    fun redirectLauncherRecordsRealOrdinaryTask() {
        val fake = FakeShell(redirectOnStartPackage = WAZE)
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(WAZE, "$WAZE/$WAZE.FreeMapAppActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(WAZE, placement.packageName)
        assertEquals("$WAZE.MainActivity", fake.taskBaseActivity(placement.appTaskId))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, WAZE))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertEquals(2, fake.taskCount(SECONDARY_ROOT))
        assertTrue(fake.commands.any { command ->
            command.startsWith("am start ") &&
                command.contains("'$WAZE/$WAZE.FreeMapAppActivity'") &&
                command.contains("-f 0x18200000")
        })
    }

    @Test
    fun redirectedOrdinaryTaskStaysInSelectedPane() {
        val fake = FakeShell(redirectOnStartPackage = MUSIC)
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.SplashActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(MUSIC, placement.packageName)
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertEquals(2, fake.taskCount(SECONDARY_ROOT))
    }

    @Test
    fun failedDirectLaunchFailsClosedWithInteractivePicker() {
        val fake = FakeShell(
            directTargetLaunchSucceeds = false,
        )
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        runCatching {
            split.selectApp(
                pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
                target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
                pickerComponents = PICKER_COMPONENTS,
            )
        }.onSuccess { error("Expected direct launch to fail") }

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertEquals(1, fake.taskCount(PRIMARY_ROOT))
    }

    @Test
    fun closeMovesSelectedAppToFullRootAndRemovesOnlyPickerBases() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        split.closePickers(PICKERS)

        assertEquals(4, fake.area)
        assertTrue(fake.hasPackage(FULL_ROOT, MUSIC))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
        assertFalse(fake.isGateOpen())
    }

    /**
     * 1.13: a settle is for something that happened.
     *
     * Two pickers already sitting in their own roots settle nothing, and the whole point of the
     * open path finding them alive is not to pay for them again.
     */
    @Test
    fun anOpenThatMovedNothingWaitsForNothing() {
        val fake = FakeShell().apply { liveProductScene() }
        var settled = 0L
        val topology = SplitTopologyCache()
        fake.carChanged += topology::invalidate
        val split = SplitPickerShellSession(
            shell = fake::shell,
            apkPath = SPLIT_APK_PATH,
            settle = { millis -> settled += millis },
            gateLeaseStore = FakeGateLease(),
            topology = topology,
        )

        split.openPickers(PICKERS, preservedPackages = emptyMap())

        assertEquals(
            "nothing moved, nothing was removed, nothing had to settle",
            0L,
            settled,
        )
    }

    /**
     * 1.13.3: the removals are the same exact-identity calls, but they cost one class load, not one
     * each. Loading the proxy dominates a removal by an order of magnitude on this car.
     */
    @Test
    fun clearingSeveralTasksStartsTheProxyOnce() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val before = fake.commands.size

        split.closePickers(PICKERS)

        val proxyCalls = fake.commands.drop(before).filter { it.contains(" remove-task ") }
        assertEquals("one class load, not one per task", 1, proxyCalls.size)
        val groups = proxyCalls.single().substringAfter(" remove-task ").split(' ').chunked(5)
        assertEquals(
            "both picker bases are named in that one call, five arguments each",
            pickers.values.map(Int::toString).sorted(),
            groups.map { group -> group.first() }.sorted(),
        )
        assertTrue(groups.all { group -> group.size == 5 })
    }

    @Test
    fun disabledProductEndsItsOwnSceneWithoutClosingAGateItNeverOpened() {
        // Обязательство к 1.12 и решение №2: закрывать можно только собственный gate.
        // Завершение сцены - легитимное поведение операции DISABLE (1.2.3), gate - нет.
        val fake = FakeShell(initialGate = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).closePickers(PICKERS)

        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
        assertFalse(fake.commands.any { it == "service call activity_task 126 i32 0" })
        assertTrue(fake.isGateOpen())
        assertTrue(fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertEquals(4, fake.area)
    }

    @Test
    fun expandedSplitWithStockVacancyBecomesTrueFullscreenWhenProductIsOff() {
        val fake = FakeShell(initialGate = true).apply {
            area = 2
            addTask(
                PRIMARY_ROOT,
                427,
                STOCK_PICKER_PACKAGE,
                STOCK_PICKER_ACTIVITY,
            )
            addTask(
                SECONDARY_ROOT,
                440,
                SPLIT_HOST_PACKAGE,
                SPLIT_APP_HOST_ACTIVITY,
            )
            addTask(SECONDARY_ROOT, 452, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        session(fake).closePickers(PICKERS, expectedHostTaskIds = emptySet())

        // The scene ends and our own artifacts go, but the gate stays exactly as we found it.
        assertTrue(fake.isGateOpen())
        assertEquals(4, fake.area)
        assertTrue(fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, STOCK_PICKER_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
    }

    @Test
    fun closeAcceptsNewFullscreenForegroundWhilePickerCleanupIsInFlight() {
        val fake = FakeShell(
            initialGate = true,
            replaceFullForegroundDuringPickerCleanup = true,
        ).apply {
            area = 2
            addTask(
                PRIMARY_ROOT,
                427,
                STOCK_PICKER_PACKAGE,
                STOCK_PICKER_ACTIVITY,
            )
            addTask(SECONDARY_ROOT, 452, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        session(fake).closePickers(PICKERS)

        assertEquals(4, fake.area)
        assertFalse(fake.hasTask(452))
        assertTrue(fake.hasPackage(FULL_ROOT, LAUNCHER_PACKAGE))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, STOCK_PICKER_ACTIVITY))
        assertTrue("a gate this session never opened is not ours to close", fake.isGateOpen())
    }

    @Test
    fun projectedTaskWhosePickerWasClosedReturnsFullscreenInItsRecordedPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        split.returnRecordedTaskFullscreen(
            SplitPane.PRIMARY,
            placement.appTaskId,
            NAVIGATOR,
        )

        assertEquals(1, fake.area)
        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertTrue(fake.commands.any { it == "service call activity_task 114 i32 101" })
    }

    @Test
    fun fullscreenReturnWithWrongTaskIdentityIsANoOp() {
        val fake = FakeShell()
        val split = session(fake)
        split.openPickers(PICKERS, preservedPackages = emptyMap())
        val before = fake.commands.size

        split.returnRecordedTaskFullscreen(SplitPane.PRIMARY, 999, NAVIGATOR)

        assertEquals(3, fake.area)
        assertFalse(
            fake.commands.drop(before).any { it.startsWith("service call activity_task 114 ") },
        )
    }

    @Test
    fun navigationReturnRevealsVerifiedHiddenSplitBeforeChoosingVacantPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.moveTask(navigator.appTaskId, EXTERNAL_ROOT)
        fake.area = 4
        fake.commands.clear()

        val plan = split.prepareNavigationReturn(
            originalRootTaskId = PRIMARY_ROOT,
            pickerComponents = PICKER_COMPONENTS,
            expectedApps = mapOf(
                SplitPane.SECONDARY to SplitPickerExpectedApp(
                    music.appTaskId,
                    music.packageName,
                ),
            ),
        )

        assertFalse(plan.fullscreen)
        assertEquals(SplitPane.PRIMARY, plan.pane)
        assertEquals(PRIMARY_ROOT, plan.rootTaskId)
        assertEquals(pickers.getValue(SplitPane.PRIMARY), plan.hostTaskId)
        assertEquals(3, fake.area)
        assertTrue(fake.commands.any { it == "am task focus ${music.appTaskId}" })
    }

    @Test
    fun navigationReturnDoesNotRevealHiddenSplitWithMismatchedPersistedIdentity() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val navigator = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.moveTask(navigator.appTaskId, EXTERNAL_ROOT)
        fake.area = 4
        fake.commands.clear()

        val plan = split.prepareNavigationReturn(
            originalRootTaskId = PRIMARY_ROOT,
            pickerComponents = PICKER_COMPONENTS,
            expectedApps = mapOf(
                SplitPane.SECONDARY to SplitPickerExpectedApp(
                    music.appTaskId + 1,
                    music.packageName,
                ),
            ),
        )

        assertTrue(plan.fullscreen)
        assertEquals(4, fake.area)
        assertFalse(fake.commands.any { it.startsWith("am task focus ") })
    }

    @Test
    fun engineCreatesIndependentTaskWhenSamePackageAlreadyLivesInOtherPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.addTask(PRIMARY_ROOT, 90, MUSIC, "$MUSIC.MainActivity")

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertTrue(fake.hasTask(90))
        assertTrue(fake.hasPackage(PRIMARY_ROOT, MUSIC))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertTrue(placement.appTaskId != 90)
        assertEquals(2, fake.taskCount(SECONDARY_ROOT))
    }

    @Test
    fun staleFullscreenTaskReplacedDuringLaunchIsNotProtectedAsPeerWindow() {
        val fake = FakeShell(replaceStaleFullscreenTargetOnLaunch = true)
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.addTask(FULL_ROOT, 90, MUSIC, "$MUSIC.MainActivity")

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertFalse(fake.hasTask(90))
        assertEquals(SECONDARY_ROOT, fake.taskRoot(placement.appTaskId))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.commands.any { it.contains("remove-task ${placement.appTaskId} ") })
    }

    @Test
    fun singleTaskDuplicateIsRejectedWithoutTouchingExistingWindow() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.addTask(PRIMARY_ROOT, 90, MUSIC, "$MUSIC.MainActivity")
        fake.commands.clear()

        val error = runCatching {
            split.selectApp(
                pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
                target = SplitLaunchTarget(
                    MUSIC,
                    "$MUSIC/$MUSIC.MainActivity",
                    launchMode = 2,
                ),
                pickerComponents = PICKER_COMPONENTS,
            )
        }.exceptionOrNull()

        assertEquals("Это приложение не поддерживает два окна", error?.message)
        assertTrue(fake.hasTask(90))
        assertEquals(PRIMARY_ROOT, fake.taskRoot(90))
        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.commands.any { it.startsWith("am start ") })
    }

    @Test
    fun transientPostLaunchAreaDoesNotDeleteSuccessfullyPlacedApp() {
        val fake = FakeShell(transientAreaReadsAfterDirectLaunch = 1)
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
                launchMode = 2,
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(SECONDARY_ROOT, fake.taskRoot(placement.appTaskId))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.commands.any { it.contains("remove-task ${placement.appTaskId} ") })
    }

    @Test(expected = IllegalStateException::class)
    fun engineDoesNotReclaimAppProjectedToAnotherDisplay() {
        val fake = FakeShell().apply {
            addTask(EXTERNAL_ROOT, 91, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(
                NAVIGATOR,
                "$NAVIGATOR/$NAVIGATOR.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )
    }

    @Test
    fun projectedMemberDoesNotBlockDifferentAppFromVacantPane() {
        // 1.10.2: занять вакансию временным приложением - обычный выбор; спроецирован не он
        val fake = FakeShell().apply {
            addTask(EXTERNAL_ROOT, 91, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertTrue(fake.hasPackage(EXTERNAL_ROOT, NAVIGATOR))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
    }

    @Test
    fun appHiddenUnderOtherPickerIsPreservedWhenDuplicateOpens() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 70, MUSIC, "$MUSIC.MainActivity")
        }
        val split = session(fake)
        val pickers = split.openPickers(
            PICKERS,
            preservedPackages = mapOf(SplitPane.PRIMARY to MUSIC),
        )

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertTrue(fake.hasPackage(PRIMARY_ROOT, MUSIC))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
    }

    @Test
    fun preservedStandaloneAppIsReplacedByOrdinaryTaskDuringRestoration() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 70, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.openPickers(
            PICKERS,
            preservedPackages = mapOf(SplitPane.PRIMARY to NAVIGATOR),
        )

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertTrue(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.hasTask(70))
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("'$NAVIGATOR/$NAVIGATOR.MainActivity'") &&
                    command.contains("-f 0x18200000")
            },
        )
    }

    @Test
    fun failedRestorationDiscardRemovesOnlyTheStaleAppBelowPicker() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 70, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.openPickers(
            PICKERS,
            preservedPackages = mapOf(SplitPane.PRIMARY to NAVIGATOR),
        )

        split.discardFailedRestoration(
            pane = SplitPane.PRIMARY,
            packageName = NAVIGATOR,
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
        )

        assertFalse(fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertTrue(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertTrue(fake.commands.any { it.contains("remove-task 70 ") })
    }

    @Test
    fun revealedPickerRemovesOnlyTheRecordedDismissedTask() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.promoteActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)

        val observation = split.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)
        assertTrue(observation.pickerVisible)
        assertTrue(split.removeRecordedTask(placement.appTaskId, MUSIC))
        assertFalse(split.removeRecordedTask(placement.appTaskId, MUSIC))

        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasPackage(SECONDARY_ROOT, MUSIC))
    }

    @Test
    fun recordedTaskRemovalAcceptsAConcurrentExactTaskDisappearance() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.disappearOnNextRemove()

        assertTrue(split.removeRecordedTask(placement.appTaskId, MUSIC))
        assertFalse(fake.hasTask(placement.appTaskId))
    }

    @Test
    fun observingHiddenPickerNeverPrunesVisibleApp() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        val observation = split.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)

        assertFalse(observation.pickerVisible)
        assertFalse(observation.nativeHostVisible)
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
    }

    @Test
    fun missingPickerIsNeverRebuiltByObservation() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.removeActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)

        val beforeCommands = fake.commands.size
        val observation = split.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)

        assertEquals(null, observation.hostTaskId)
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(
            fake.commands.drop(beforeCommands).any {
                it.startsWith("am start ") || it.contains("replace-task-base ")
            },
        )
    }

    @Test
    fun disabledProductClosesOnlyTheGateItsOwnLeaseHolds() {
        val ownedFake = FakeShell()
        val ownedLease = FakeGateLease()
        val ownedSession = session(ownedFake, ownedLease)
        ownedSession.openPickers(PICKERS, preservedPackages = emptyMap())

        assertTrue(ownedLease.isOwned())
        assertTrue(ownedFake.isGateOpen())

        ownedSession.closePickers(PICKERS)

        assertFalse(ownedLease.isOwned())
        assertFalse(ownedFake.isGateOpen())

        // Exact DiLink 5.1 contract: tx123 is a split-capability answer and remains true even
        // after tx126(false). It must never be used as a mutable gate postcondition.
        val externalFake = FakeShell(
            initialGate = true,
            capabilityAlwaysTrue = true,
        )
        val externalLease = FakeGateLease()
        val externalSession = session(externalFake, externalLease)
        externalSession.openPickers(PICKERS, preservedPackages = emptyMap())
        externalSession.closePickers(PICKERS)

        assertFalse(externalLease.isOwned())
        assertFalse(externalFake.isGateOpen())
        assertTrue(externalFake.commands.any { it == "service call activity_task 126 i32 0" })

        // And the case the product used to get wrong: a session that never took the lease has no
        // business touching the global gate, whatever else the toggle-off has to clean up.
        val foreignFake = FakeShell(initialGate = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, STOCK_PICKER_PACKAGE, STOCK_PICKER_ACTIVITY)
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }
        val foreignLease = FakeGateLease()

        session(foreignFake, foreignLease).closePickers(PICKERS)

        assertFalse(foreignLease.isOwned())
        assertTrue(foreignFake.isGateOpen())
        assertFalse(foreignFake.commands.any { it == "service call activity_task 126 i32 0" })
    }

    @Test
    fun openingSessionAssertsGateEvenWhenCapabilityIsAlwaysTrue() {
        val fake = FakeShell(
            initialGate = false,
            capabilityAlwaysTrue = true,
        )

        session(fake).openPickers(PICKERS, preservedPackages = emptyMap())

        assertTrue(fake.isGateOpen())
        assertTrue(fake.commands.any { it == "service call activity_task 126 i32 1" })
    }

    @Test
    fun homeSuspendsOwnedGateButKeepsLeaseForExplicitReopen() {
        val fake = FakeShell()
        val lease = FakeGateLease()
        val split = session(fake, lease)
        split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.area = 0

        assertTrue(split.suspendOwnedGateForHome())

        assertFalse(fake.isGateOpen())
        assertTrue(lease.isOwned())

        split.openPickers(PICKERS, preservedPackages = emptyMap())

        assertTrue(fake.isGateOpen())
        assertTrue(lease.isOwned())
    }

    @Test
    fun homeNeverClosesGateOwnedByAnotherComponent() {
        val fake = FakeShell(initialGate = true).apply { area = 0 }
        val lease = FakeGateLease(owned = false)

        assertFalse(session(fake, lease).suspendOwnedGateForHome())

        assertTrue(fake.isGateOpen())
        assertFalse(fake.commands.any { it == "service call activity_task 126 i32 0" })
    }

    @Test
    fun nonHomeEventNeverSuspendsOwnedGate() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)

        assertFalse(session(fake, lease).suspendOwnedGateForHome())

        assertTrue(fake.isGateOpen())
        assertTrue(lease.isOwned())
    }

    @Test
    fun ownedGateIsRecoveredEvenWhenPickerTasksHaveAlreadyGone() {
        val fake = FakeShell(initialGate = true)
        val lease = FakeGateLease(owned = true)

        session(fake, lease).closePickers(PICKERS)

        assertFalse(fake.isGateOpen())
        assertFalse(lease.isOwned())
    }

    @Test
    fun closeRemovesPickerStrandedOutsideNativeRootsAfterPartialOpen() {
        val fake = FakeShell(initialGate = true).apply {
            addTask(
                FULL_ROOT,
                77,
                "dev.denza.apps",
                PRIMARY_PICKER_ACTIVITY,
            )
        }
        val lease = FakeGateLease(owned = true)

        session(fake, lease).closePickers(PICKERS)

        assertFalse(fake.hasActivity(FULL_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.isGateOpen())
        assertFalse(lease.isOwned())
    }

    /**
     * Production always hands the session a gate lease, because the gate is firmware-global and the
     * only rule that may close it is "we opened it". A test that wants to prove the product keeps
     * its hands off someone else's gate therefore passes a lease that owns nothing.
     */
    private fun session(
        fake: FakeShell,
        gateLeaseStore: SplitGateLeaseStore = FakeGateLease(),
    ): SplitPickerShellSession {
        // One session shares its two topology reads for as long as nothing could have moved a
        // task. In the car that means "no command and no settle pause since"; here it additionally
        // means "and the test did not reach into the firmware behind the session's back".
        val topology = SplitTopologyCache()
        fake.carChanged += topology::invalidate
        return SplitPickerShellSession(
            shell = fake::shell,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            settle = {},
            gateLeaseStore = gateLeaseStore,
            topology = topology,
        )
    }

    private fun intParcel(value: Int): String =
        "Result: Parcel(00000000 ${"%08x".format(value)} '........')"
}
