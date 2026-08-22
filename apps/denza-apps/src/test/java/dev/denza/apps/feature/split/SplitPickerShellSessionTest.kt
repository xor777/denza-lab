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
            pause = { pauses += 1 },
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
            pause = {},
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
            pause = { pauses += 1 },
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
            pause = { pauses += 1 },
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
            pause = {},
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
    fun restorationDropsMissingPackagesAndKeepsDuplicatePackages() {
        assertEquals(
            mapOf(
                SplitPane.PRIMARY to NAVIGATOR,
                SplitPane.SECONDARY to NAVIGATOR,
            ),
            SplitPickerSelectionPolicy.restorablePair(
                primaryPackage = NAVIGATOR,
                secondaryPackage = NAVIGATOR,
                installedPackages = setOf(NAVIGATOR),
            ),
        )
        assertEquals(
            mapOf(SplitPane.SECONDARY to MUSIC),
            SplitPickerSelectionPolicy.restorablePair(
                primaryPackage = "missing.app",
                secondaryPackage = MUSIC,
                installedPackages = setOf(MUSIC),
            ),
        )
    }

    @Test
    fun savingOnePanePreservesDifferentPackageInOtherPane() {
        val afterPrimary = SplitPickerSelectionPolicy.updatedPair(
            primaryPackage = null,
            secondaryPackage = null,
            selectedPane = SplitPane.PRIMARY,
            selectedPackage = NAVIGATOR,
        )
        val afterSecondary = SplitPickerSelectionPolicy.updatedPair(
            primaryPackage = afterPrimary[SplitPane.PRIMARY],
            secondaryPackage = afterPrimary[SplitPane.SECONDARY],
            selectedPane = SplitPane.SECONDARY,
            selectedPackage = MUSIC,
        )

        assertEquals(
            mapOf(
                SplitPane.PRIMARY to NAVIGATOR,
                SplitPane.SECONDARY to MUSIC,
            ),
            afterSecondary,
        )
        assertEquals(
            mapOf(
                SplitPane.PRIMARY to MUSIC,
                SplitPane.SECONDARY to MUSIC,
            ),
            SplitPickerSelectionPolicy.updatedPair(
                primaryPackage = NAVIGATOR,
                secondaryPackage = MUSIC,
                selectedPane = SplitPane.PRIMARY,
                selectedPackage = MUSIC,
            ),
        )
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
    fun explicitOpenHostsPickersInNativeTasksWithoutSyntheticDividerDrag() {
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
    fun nativeEdgeCollapseRehostsExactPickerBehindAppMovedToSurvivorRoot() {
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
                SplitPane.PRIMARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                ),
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
    fun collapsedPickerRehostRollsBackWhenNativeAreaChangesDuringMutation() {
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
    fun hostlessExistingPairIsHostedThroughExactPaneCategories() {
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
        assertFalse(fake.commands.any { it.contains(SPLIT_APP_HOST_COMPONENT) })
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
     * Live trace 2026-08-16 17:52:09: Navigator's singleTask launcher could not remain above
     * host #398. It created #399 in the opposite root and made SmartMulti rebuild both panes.
     */
    @Test
    fun singleTaskLauncherBypassesHostAndUsesDirectLaunch() {
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
        assertFalse(fake.commands.any { it.contains(SPLIT_APP_HOST_COMPONENT) })
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
    fun nextPickerTapRecoversTransparentHostLeftByInterruptedProcess() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.addTask(
            PRIMARY_ROOT,
            90,
            SPLIT_HOST_PACKAGE,
            SPLIT_APP_HOST_ACTIVITY,
        )

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertTrue(fake.commands.any { it.contains("remove-task 90 ") })
        assertEquals(NAVIGATOR, placement.packageName)
        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertEquals(2, fake.taskCount(PRIMARY_ROOT))
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

    @Test
    fun disabledProductClosesGateAndMovesForegroundEvenWithoutOurPicker() {
        val fake = FakeShell(initialGate = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).closePickers(PICKERS)

        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
        assertFalse(fake.isGateOpen())
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

        assertFalse(fake.isGateOpen())
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
        assertFalse(fake.isGateOpen())
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
        val fake = FakeShell().apply {
            addTask(EXTERNAL_ROOT, 91, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
            reservedPackages = setOf(NAVIGATOR),
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
    fun disabledProductClosesGateEvenWhenItWasAlreadyOpen() {
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

    private fun session(
        fake: FakeShell,
        gateLeaseStore: SplitGateLeaseStore? = null,
    ) = SplitPickerShellSession(
        shell = fake::shell,
        apkPath = "/data/app/dev.denza.apps/base.apk",
        pause = {},
        gateLeaseStore = gateLeaseStore,
    )

    private fun intParcel(value: Int): String =
        "Result: Parcel(00000000 ${"%08x".format(value)} '........')"

    private class FakeGateLease(
        private var owned: Boolean = false,
    ) : SplitGateLeaseStore {
        override fun isOwned(): Boolean = owned

        override fun setOwned(owned: Boolean): Boolean {
            this.owned = owned
            return true
        }
    }

    private class FakeShell(
        initialGate: Boolean = false,
        private val capabilityAlwaysTrue: Boolean = false,
        private val replaceFullForegroundDuringPickerCleanup: Boolean = false,
        private val hostingSucceeds: Boolean = true,
        private val hostTargetLaunchSucceeds: Boolean = true,
        private val hostMovesToOppositeRootOnTargetStart: Boolean = false,
        private val directTargetLaunchSucceeds: Boolean = true,
        private val reuseExistingTargetOnLaunch: Boolean = false,
        private val redirectOnStartPackage: String? = null,
        private val redirectHostMovesToOppositeRoot: Boolean = false,
        private val secondaryBootstrapPackage: String? = null,
        private val tx115RequiresHome: Boolean = false,
        private val nativeBootstrapStartsFullscreen: Boolean = false,
        private val renderEmptyNativeRootMarker: Boolean = false,
        private val transientAreaReadsAfterDirectLaunch: Int = 0,
        private val replaceStaleFullscreenTargetOnLaunch: Boolean = false,
    ) {
        data class Task(
            val id: Int,
            var packageName: String,
            var activityName: String,
            var rootId: Int,
            var bounds: SplitBounds,
            var hostedComponent: String? = null,
        )

        val commands = mutableListOf<String>()
        var area = 4
        var preserveBoundsOnShellMove = false
        private var gate = initialGate
        private var homeVisible = false
        private var nextTaskId = 100
        private var fullForegroundReplaced = false
        private var disappearOnNextRemove = false
        private var destabilizeAreaOnNextShellMove = false
        private var transientAreaReadsRemaining = 0
        private val supported = mutableSetOf<String>()
        private val tasks = mutableListOf<Task>()

        fun addTask(rootId: Int, id: Int, packageName: String, activityName: String) {
            tasks += Task(id, packageName, activityName, rootId, bounds(rootId))
        }

        fun hasPackage(rootId: Int, packageName: String): Boolean =
            tasks.any {
                it.rootId == rootId &&
                    (it.packageName == packageName ||
                        it.hostedComponent?.substringBefore('/') == packageName)
            }

        fun hasTask(taskId: Int): Boolean = tasks.any { it.id == taskId }

        fun hasActivity(rootId: Int, activityName: String): Boolean =
            tasks.any {
                it.rootId == rootId &&
                    (it.activityName == activityName || it.hostedComponent?.endsWith(activityName) == true)
            }

        fun taskCount(rootId: Int): Int = tasks.count { it.rootId == rootId }

        fun taskBounds(taskId: Int): SplitBounds = tasks.first { it.id == taskId }.bounds

        fun taskRoot(taskId: Int): Int = tasks.first { it.id == taskId }.rootId

        fun moveTask(taskId: Int, rootId: Int) {
            val task = tasks.first { it.id == taskId }
            tasks.remove(task)
            task.rootId = rootId
            task.bounds = bounds(rootId)
            tasks += task
        }

        fun taskBaseActivity(taskId: Int): String = tasks.first { it.id == taskId }.activityName

        fun topActivity(rootId: Int): String? = tasks.lastOrNull { it.rootId == rootId }
            ?.let { task ->
                task.hostedComponent?.substringAfter('/') ?: task.activityName
            }

        fun topTaskId(rootId: Int): Int? = tasks.lastOrNull { it.rootId == rootId }?.id

        fun isGateOpen(): Boolean = gate

        fun promoteActivity(rootId: Int, activityName: String) {
            val task = tasks.first {
                it.rootId == rootId &&
                    (it.activityName == activityName ||
                        it.hostedComponent?.substringAfter('/') == activityName)
            }
            tasks.remove(task)
            tasks += task
        }

        fun removeActivity(rootId: Int, activityName: String) {
            tasks.removeAll {
                it.rootId == rootId &&
                    (it.activityName == activityName ||
                        it.hostedComponent?.substringAfter('/') == activityName)
            }
        }

        fun dismissPane(rootId: Int) {
            tasks.removeAll { it.rootId == rootId }
            val remainingRoot = when (rootId) {
                PRIMARY_ROOT -> SECONDARY_ROOT
                SECONDARY_ROOT -> PRIMARY_ROOT
                else -> error("Not a split pane: $rootId")
            }
            area = if (remainingRoot == PRIMARY_ROOT) 1 else 2
            tasks.filter { it.rootId == remainingRoot }.forEach { it.bounds = FULL }
        }

        fun disappearOnNextRemove() {
            disappearOnNextRemove = true
        }

        fun destabilizeAreaOnNextShellMove() {
            destabilizeAreaOnNextShellMove = true
        }

        fun shell(command: String): String {
            commands += command
            return when {
                command == "service call activity_task 123" ->
                    intParcel(if (capabilityAlwaysTrue || gate) 1 else 0)
                command == "service call activity_task 126 i32 1" -> {
                    gate = true
                    voidParcel()
                }
                command == "service call activity_task 126 i32 0" -> {
                    gate = false
                    voidParcel()
                }
                command.startsWith("service call activity_task 114 i32 ") -> {
                    area = if (command.endsWith("101")) 1 else 2
                    voidParcel()
                }
                command == "service call activity_task 115" -> {
                    if (tx115RequiresHome && !homeVisible) {
                        return voidParcel()
                    }
                    homeVisible = false
                    area = 3
                    tasks.removeAll {
                        it.packageName == STOCK_PICKER_PACKAGE &&
                            it.activityName == STOCK_PICKER_ACTIVITY
                    }
                    tasks += Task(
                        id = nextTaskId++,
                        packageName = STOCK_PICKER_PACKAGE,
                        activityName = STOCK_PICKER_ACTIVITY,
                        rootId = PRIMARY_ROOT,
                        bounds = if (nativeBootstrapStartsFullscreen) FULL else bounds(PRIMARY_ROOT),
                    )
                    tasks += Task(
                        id = nextTaskId++,
                        packageName = secondaryBootstrapPackage ?: STOCK_PICKER_PACKAGE,
                        activityName = secondaryBootstrapPackage?.let { "$it.MainActivity" }
                            ?: STOCK_PICKER_ACTIVITY,
                        rootId = SECONDARY_ROOT,
                        bounds = if (nativeBootstrapStartsFullscreen) FULL else bounds(SECONDARY_ROOT),
                    )
                    voidParcel()
                }
                command == "service call activity_task 118 i32 1" -> intParcel(PRIMARY_ROOT)
                command == "service call activity_task 118 i32 2" -> intParcel(SECONDARY_ROOT)
                command == "service call activity_task 118 i32 4" -> intParcel(FULL_ROOT)
                command == "service call activity_task 30" -> {
                    if (transientAreaReadsRemaining > 0) {
                        transientAreaReadsRemaining -= 1
                        intParcel(2)
                    } else {
                        intParcel(area)
                    }
                }
                command.startsWith("service call activity_task 112 s16 ") ->
                    intParcel(if (quotedArgument(command) in supported) 1 else 0)
                command.startsWith("service call activity_task 125 s16 ") -> {
                    supported += quotedArgument(command)
                    voidParcel()
                }
                command.startsWith("am start ") -> {
                    val component = command.substringAfter("-n '").substringBefore("'")
                    val packageName = component.substringBefore('/')
                    val rawActivity = component.substringAfter('/')
                    val activityName = if (rawActivity.startsWith('.')) {
                        packageName + rawActivity
                    } else {
                        rawActivity
                    }
                    if (!hostingSucceeds && component in PICKERS.values) {
                        return "Error: picker launch rejected"
                    }
                    if (
                        !directTargetLaunchSucceeds &&
                        component !in PICKERS.values &&
                        component != SPLIT_APP_HOST_COMPONENT
                    ) {
                        return "Error: direct target launch rejected"
                    }
                    val pickerRoot = when {
                        command.contains("byd.intent.category.START_IVI_PRIMARY") -> PRIMARY_ROOT
                        command.contains("byd.intent.category.START_IVI_SECOND") -> SECONDARY_ROOT
                        else -> null
                    }
                    if (pickerRoot != null) {
                        if (area != fullArea(pickerRoot)) area = 3
                        tasks.removeAll {
                            it.rootId == pickerRoot &&
                                (it.packageName == STOCK_PICKER_PACKAGE ||
                                    it.packageName == STOCK_BOOTSTRAP_PACKAGE)
                        }
                    } else {
                        tasks.removeAll { it.activityName == activityName }
                    }
                    if (
                        reuseExistingTargetOnLaunch &&
                        component !in PICKERS.values &&
                        component != SPLIT_APP_HOST_COMPONENT
                    ) {
                        val reused = tasks.firstOrNull { task ->
                            task.effectivePackageName() == packageName
                        }
                        if (reused != null) {
                            tasks.remove(reused)
                            reused.rootId = pickerRoot ?: FULL_ROOT
                            reused.bounds = pickerRoot?.let(::bounds) ?: FULL
                            tasks += reused
                            return "Starting: Intent"
                        }
                    }
                    if (
                        replaceStaleFullscreenTargetOnLaunch &&
                        component !in PICKERS.values &&
                        component != SPLIT_APP_HOST_COMPONENT
                    ) {
                        tasks.removeAll { task ->
                            task.rootId == FULL_ROOT &&
                                task.effectivePackageName() == packageName
                        }
                    }
                    val launchedTask = Task(
                        id = nextTaskId++,
                        packageName = packageName,
                        activityName = activityName,
                        rootId = pickerRoot ?: FULL_ROOT,
                        bounds = pickerRoot?.let(::bounds) ?: FULL,
                    )
                    tasks += launchedTask
                    if (
                        component !in PICKERS.values &&
                        component != SPLIT_APP_HOST_COMPONENT
                    ) {
                        transientAreaReadsRemaining = transientAreaReadsAfterDirectLaunch
                    }
                    if (
                        component != SPLIT_APP_HOST_COMPONENT &&
                        packageName == redirectOnStartPackage
                    ) {
                        tasks.remove(launchedTask)
                        tasks += Task(
                            id = nextTaskId++,
                            packageName = packageName,
                            activityName = "$packageName.MainActivity",
                            rootId = launchedTask.rootId,
                            bounds = launchedTask.bounds,
                        )
                    }
                    if (
                        component == SPLIT_APP_HOST_COMPONENT &&
                        hostTargetLaunchSucceeds
                    ) {
                        val targetPackage = stringExtra(
                            command,
                            SPLIT_HOST_TARGET_PACKAGE_EXTRA,
                        )
                        val rawTargetActivity = stringExtra(
                            command,
                            SPLIT_HOST_TARGET_ACTIVITY_EXTRA,
                        )
                        val targetActivity = if (rawTargetActivity.startsWith('.')) {
                            targetPackage + rawTargetActivity
                        } else {
                            rawTargetActivity
                        }
                        val reused = if (reuseExistingTargetOnLaunch) {
                            tasks.firstOrNull { task ->
                                task.id != launchedTask.id &&
                                    task.effectivePackageName() == targetPackage
                            }
                        } else {
                            null
                        }
                        if (reused != null) {
                            tasks.remove(reused)
                            reused.rootId = launchedTask.rootId
                            reused.bounds = bounds(launchedTask.rootId)
                            tasks += reused
                        } else if (targetPackage == redirectOnStartPackage) {
                            val selectedRoot = launchedTask.rootId
                            val oppositeRoot = when (selectedRoot) {
                                PRIMARY_ROOT -> SECONDARY_ROOT
                                SECONDARY_ROOT -> PRIMARY_ROOT
                                else -> selectedRoot
                            }
                            if (redirectHostMovesToOppositeRoot) {
                                launchedTask.rootId = oppositeRoot
                                launchedTask.bounds = bounds(oppositeRoot)
                            }
                            val targetRoot = if (redirectHostMovesToOppositeRoot) {
                                selectedRoot
                            } else {
                                oppositeRoot
                            }
                            tasks += Task(
                                id = nextTaskId++,
                                packageName = targetPackage,
                                activityName = "$targetPackage.MainActivity",
                                rootId = targetRoot,
                                bounds = bounds(targetRoot),
                            )
                        } else {
                            launchedTask.hostedComponent = "$targetPackage/$targetActivity"
                            if (hostMovesToOppositeRootOnTargetStart) {
                                launchedTask.rootId = when (launchedTask.rootId) {
                                    PRIMARY_ROOT -> SECONDARY_ROOT
                                    SECONDARY_ROOT -> PRIMARY_ROOT
                                    else -> launchedTask.rootId
                                }
                                launchedTask.bounds = bounds(launchedTask.rootId)
                            }
                        }
                    }
                    "Starting: Intent"
                }
                command.startsWith("am stack move-task ") -> {
                    val parts = command.split(' ')
                    val taskId = parts[3].toInt()
                    val rootId = parts[4].toInt()
                    val toTop = parts[5].toBoolean()
                    val task = tasks.first { it.id == taskId }
                    val changedRoot = task.rootId != rootId
                    if (changedRoot) {
                        tasks.remove(task)
                        task.rootId = rootId
                        if (!preserveBoundsOnShellMove) task.bounds = bounds(rootId)
                        if (toTop) {
                            tasks += task
                        } else {
                            val firstInRoot = tasks.indexOfFirst { it.rootId == rootId }
                            if (firstInRoot >= 0) tasks.add(firstInRoot, task) else tasks += task
                        }
                    }
                    if (destabilizeAreaOnNextShellMove) {
                        destabilizeAreaOnNextShellMove = false
                        area = 3
                    }
                    if (rootId == FULL_ROOT) area = 4
                    ""
                }
                command.startsWith("am task focus ") -> {
                    val taskId = command.substringAfter("am task focus ").toInt()
                    val task = tasks.first { it.id == taskId }
                    tasks.remove(task)
                    tasks += task
                    if (task.rootId == PRIMARY_ROOT || task.rootId == SECONDARY_ROOT) {
                        if (area != fullArea(task.rootId)) area = 3
                    }
                    ""
                }
                command.contains("SplitTaskProxyMain remove-task ") -> {
                    val taskId = command.substringAfter("remove-task ").substringBefore(' ').toInt()
                    if (disappearOnNextRemove) {
                        disappearOnNextRemove = false
                        tasks.removeAll { it.id == taskId }
                        return "DENZA_SPLIT_RESULT:false"
                    }
                    val removedTask = tasks.firstOrNull { it.id == taskId }
                    val removed = tasks.removeAll { it.id == taskId }
                    if (
                        replaceFullForegroundDuringPickerCleanup &&
                        !fullForegroundReplaced &&
                        removedTask?.packageName == STOCK_PICKER_PACKAGE
                    ) {
                        tasks.removeAll { it.rootId == FULL_ROOT }
                        tasks += Task(
                            id = nextTaskId++,
                            packageName = LAUNCHER_PACKAGE,
                            activityName = LAUNCHER_ACTIVITY,
                            rootId = FULL_ROOT,
                            bounds = FULL,
                        )
                        area = 4
                        fullForegroundReplaced = true
                    }
                    "DENZA_SPLIT_RESULT:$removed"
                }
                command.startsWith("am task resize ") -> {
                    val parts = command.split(' ')
                    tasks.first { it.id == parts[3].toInt() }.bounds = SplitBounds(
                        parts[4].toInt(),
                        parts[5].toInt(),
                        parts[6].toInt(),
                        parts[7].toInt(),
                    )
                    ""
                }
                command == "am stack list" -> renderStack()
                command == "dumpsys input" ->
                    "name='Embedded{multi-divider-shadow}', frame=[-67,0][108,1600]"
                command.startsWith("input swipe ") -> {
                    area = 3
                    ""
                }
                command == "input keyevent KEYCODE_HOME" -> {
                    area = 0
                    homeVisible = true
                    ""
                }
                else -> error("Unexpected command: $command")
            }
        }

        private fun renderStack(): String = buildString {
            listOf(FULL_ROOT, PRIMARY_ROOT, SECONDARY_ROOT, EXTERNAL_ROOT).forEach { rootId ->
                val rootTasks = tasks.filter { it.rootId == rootId }
                val rootBounds = bounds(rootId)
                appendLine(
                    "RootTask id=$rootId bounds=[${rootBounds.left},${rootBounds.top}]" +
                        "[${rootBounds.right},${rootBounds.bottom}] " +
                        "displayId=${if (rootId == EXTERNAL_ROOT) 2 else 0} userId=0",
                )
                if (
                    renderEmptyNativeRootMarker &&
                    rootTasks.isEmpty() &&
                    rootId in setOf(PRIMARY_ROOT, SECONDARY_ROOT)
                ) {
                    appendLine(
                        "  taskId=$rootId: unknown bounds=[${rootBounds.left},${rootBounds.top}]" +
                            "[${rootBounds.right},${rootBounds.bottom}] userId=0 visible=false",
                    )
                }
                val top = rootTasks.lastOrNull()
                val topPackage = top?.hostedComponent?.substringBefore('/') ?: top?.packageName
                val topActivity = top?.hostedComponent?.substringAfter('/') ?: top?.activityName
                rootTasks.forEach { task ->
                    append("  taskId=${task.id}: ${task.packageName}/${task.activityName} ")
                    append(
                        "bounds=[${task.bounds.left},${task.bounds.top}]" +
                            "[${task.bounds.right},${task.bounds.bottom}] userId=0 ",
                    )
                    append("visible=${task.id == top?.id}")
                    if (topPackage != null && topActivity != null) {
                        append(
                            " topActivity=ComponentInfo{$topPackage/$topActivity}",
                        )
                    }
                    appendLine()
                }
            }
        }

        private fun bounds(rootId: Int): SplitBounds = when (rootId) {
            PRIMARY_ROOT -> if (area == 1) FULL else PRIMARY_BOUNDS
            SECONDARY_ROOT -> if (area == 2) FULL else SECONDARY_BOUNDS
            else -> FULL
        }

        private fun fullArea(rootId: Int): Int = when (rootId) {
            PRIMARY_ROOT -> 1
            SECONDARY_ROOT -> 2
            else -> -1
        }

        private fun quotedArgument(command: String): String =
            command.substringAfter("s16 '").substringBeforeLast("'")

        private fun stringExtra(command: String, key: String): String =
            Regex("--es '${Regex.escape(key)}' '([^']*)'")
                .find(command)
                ?.groupValues
                ?.get(1)
                ?: error("Missing $key in $command")

        private fun Task.effectivePackageName(): String =
            hostedComponent?.substringBefore('/') ?: packageName

        private fun intParcel(value: Int): String =
            "Result: Parcel(00000000 ${"%08x".format(value)} '........')"

        private fun voidParcel(): String = "Result: Parcel(00000000 '....')"
    }

    private companion object {
        const val ACTIVE_DIVIDER_TOUCH = """
            Input Dispatcher State:
              TouchStatesByDisplay:
                0: down=true, split=true, deviceId=-1, source=0x00001002
                  Windows:
                    0: name='Embedded{multi-divider-shadow}'
        """
        const val NO_ACTIVE_TOUCH = """
            Input Dispatcher State:
              TouchStates: <no displays touched>
        """
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val MUSIC = "ru.yandex.music"
        const val WAZE = "com.waze"
        const val PRIMARY_ROOT = 2
        const val SECONDARY_ROOT = 3
        const val FULL_ROOT = 4
        const val EXTERNAL_ROOT = 9
        const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
        const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
        const val LAUNCHER_PACKAGE = "com.android.launcher3"
        const val LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"
        const val STOCK_BOOTSTRAP_PACKAGE = "com.byd.sr"
        const val PRIMARY_PICKER_ACTIVITY = SPLIT_PICKER_ACTIVITY
        const val SECONDARY_PICKER_ACTIVITY = PRIMARY_PICKER_ACTIVITY
        const val PRIMARY_PICKER = "$SPLIT_HOST_PACKAGE/$PRIMARY_PICKER_ACTIVITY"
        const val SECONDARY_PICKER = "$SPLIT_HOST_PACKAGE/$SECONDARY_PICKER_ACTIVITY"
        val PICKERS = mapOf(
            SplitPane.PRIMARY to PRIMARY_PICKER,
            SplitPane.SECONDARY to SECONDARY_PICKER,
        )
        val PICKER_COMPONENTS = PICKERS.values.toSet()
        val FULL = SplitBounds(0, 0, 2560, 1600)
        val PRIMARY_BOUNDS = SplitBounds(24, 112, 856, 1472)
        val SECONDARY_BOUNDS = SplitBounds(880, 112, 2536, 1472)
    }
}
