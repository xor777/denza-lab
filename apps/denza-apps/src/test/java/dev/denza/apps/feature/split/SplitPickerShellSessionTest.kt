package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPickerShellSessionTest {
    @Test
    fun panesHaveStableOpposites() {
        assertEquals(SplitPane.SECONDARY, SplitPane.PRIMARY.other())
        assertEquals(SplitPane.PRIMARY, SplitPane.SECONDARY.other())
    }

    @Test
    fun restorationDropsMissingAndDuplicatePackages() {
        assertEquals(
            mapOf(SplitPane.PRIMARY to NAVIGATOR),
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
            mapOf(SplitPane.PRIMARY to MUSIC),
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
    fun sceneMissingOneOwnedBaseIsNotAdopted() {
        val fake = FakeShell().apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        assertEquals(null, session(fake).existingOwnedSession(PICKER_COMPONENTS))
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
    fun pickerTapPlacesOnlyChosenAppAboveItsOwnPicker() {
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
            command.startsWith("am start ") && command.contains("$MUSIC/$MUSIC.MainActivity")
        }
        assertTrue(appLaunch.contains("-f 0x10200000"))
        assertTrue(appLaunch.contains("byd.intent.category.START_IVI_SECOND"))
    }

    @Test
    fun closeExpandsSelectedPaneAndRemovesOnlyPickerBases() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        split.closePickers(PICKERS)

        assertEquals(2, fake.area)
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertTrue(fake.commands.any { it == "service call activity_task 114 i32 102" })
    }

    @Test
    fun closeLeavesUnrelatedNativeSplitUntouchedWithoutOurPicker() {
        val fake = FakeShell().apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).closePickers(PICKERS)

        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
        assertTrue(fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
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

    @Test(expected = IllegalStateException::class)
    fun engineFailsClosedWhenSamePackageAlreadyLivesInOtherPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.openPickers(PICKERS, preservedPackages = emptyMap())
        fake.addTask(PRIMARY_ROOT, 90, MUSIC, "$MUSIC.MainActivity")

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )
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
    fun appHiddenUnderOtherPickerCanMoveToChosenPane() {
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
        assertFalse(fake.hasPackage(PRIMARY_ROOT, MUSIC))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
    }

    @Test
    fun preservedAppInSamePaneIsPromotedAbovePickerDuringRestoration() {
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
        assertTrue(
            fake.commands.joinToString("\n"),
            fake.commands.any { it == "am task focus 70" },
        )
        assertFalse(fake.commands.any { it == "am stack move-task 70 $PRIMARY_ROOT true" })
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
    fun sessionClosesOnlyGateItOpened() {
        val ownedFake = FakeShell()
        val ownedLease = FakeGateLease()
        val ownedSession = session(ownedFake, ownedLease)
        ownedSession.openPickers(PICKERS, preservedPackages = emptyMap())

        assertTrue(ownedLease.isOwned())
        assertTrue(ownedFake.isGateOpen())

        ownedSession.closePickers(PICKERS)

        assertFalse(ownedLease.isOwned())
        assertFalse(ownedFake.isGateOpen())

        val externalFake = FakeShell(initialGate = true)
        val externalLease = FakeGateLease()
        val externalSession = session(externalFake, externalLease)
        externalSession.openPickers(PICKERS, preservedPackages = emptyMap())
        externalSession.closePickers(PICKERS)

        assertFalse(externalLease.isOwned())
        assertTrue(externalFake.isGateOpen())
        assertFalse(
            externalFake.commands.any { it == "service call activity_task 126 i32 0" },
        )
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
        private val hostingSucceeds: Boolean = true,
        private val secondaryBootstrapPackage: String? = null,
        private val tx115RequiresHome: Boolean = false,
        private val nativeBootstrapStartsFullscreen: Boolean = false,
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
        private var gate = initialGate
        private var homeVisible = false
        private var nextTaskId = 100
        private val supported = mutableSetOf<String>()
        private val tasks = mutableListOf<Task>()

        fun addTask(rootId: Int, id: Int, packageName: String, activityName: String) {
            tasks += Task(id, packageName, activityName, rootId, bounds(rootId))
        }

        fun hasPackage(rootId: Int, packageName: String): Boolean =
            tasks.any { it.rootId == rootId && it.packageName == packageName }

        fun hasActivity(rootId: Int, activityName: String): Boolean =
            tasks.any {
                it.rootId == rootId &&
                    (it.activityName == activityName || it.hostedComponent?.endsWith(activityName) == true)
            }

        fun taskCount(rootId: Int): Int = tasks.count { it.rootId == rootId }

        fun taskBounds(taskId: Int): SplitBounds = tasks.first { it.id == taskId }.bounds

        fun topActivity(rootId: Int): String? = tasks.lastOrNull { it.rootId == rootId }
            ?.let { task ->
                task.hostedComponent?.substringAfter('/') ?: task.activityName
            }

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

        fun shell(command: String): String {
            commands += command
            return when {
                command == "service call activity_task 123" -> intParcel(if (gate) 1 else 0)
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
                command == "service call activity_task 30" -> intParcel(area)
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
                    val pickerRoot = when {
                        command.contains("byd.intent.category.START_IVI_PRIMARY") -> PRIMARY_ROOT
                        command.contains("byd.intent.category.START_IVI_SECOND") -> SECONDARY_ROOT
                        else -> null
                    }
                    if (pickerRoot != null) {
                        area = 3
                        tasks.removeAll {
                            it.rootId == pickerRoot &&
                                (it.packageName == STOCK_PICKER_PACKAGE ||
                                    it.packageName == STOCK_BOOTSTRAP_PACKAGE)
                        }
                    } else {
                        tasks.removeAll { it.activityName == activityName }
                    }
                    tasks += Task(
                        id = nextTaskId++,
                        packageName = packageName,
                        activityName = activityName,
                        rootId = pickerRoot ?: FULL_ROOT,
                        bounds = pickerRoot?.let(::bounds) ?: FULL,
                    )
                    "Starting: Intent"
                }
                command.startsWith("am stack move-task ") -> {
                    val parts = command.split(' ')
                    val taskId = parts[3].toInt()
                    val rootId = parts[4].toInt()
                    val task = tasks.first { it.id == taskId }
                    val changedRoot = task.rootId != rootId
                    if (changedRoot) {
                        tasks.remove(task)
                        task.rootId = rootId
                        tasks += task
                    }
                    ""
                }
                command.startsWith("am task focus ") -> {
                    val taskId = command.substringAfter("am task focus ").toInt()
                    val task = tasks.first { it.id == taskId }
                    tasks.remove(task)
                    tasks += task
                    ""
                }
                command.contains("SplitTaskProxyMain remove-task ") -> {
                    val taskId = command.substringAfter("remove-task ").substringBefore(' ').toInt()
                    val removed = tasks.removeAll { it.id == taskId }
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
            PRIMARY_ROOT -> PRIMARY_BOUNDS
            SECONDARY_ROOT -> SECONDARY_BOUNDS
            else -> FULL
        }

        private fun quotedArgument(command: String): String =
            command.substringAfter("s16 '").substringBeforeLast("'")

        private fun intParcel(value: Int): String =
            "Result: Parcel(00000000 ${"%08x".format(value)} '........')"

        private fun voidParcel(): String = "Result: Parcel(00000000 '....')"
    }

    private companion object {
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val MUSIC = "ru.yandex.music"
        const val PRIMARY_ROOT = 2
        const val SECONDARY_ROOT = 3
        const val FULL_ROOT = 4
        const val EXTERNAL_ROOT = 9
        const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
        const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
        const val STOCK_BOOTSTRAP_PACKAGE = "com.byd.sr"
        const val PRIMARY_PICKER_ACTIVITY =
            "dev.denza.apps.feature.split.SplitPrimaryPickerActivity"
        const val SECONDARY_PICKER_ACTIVITY =
            "dev.denza.apps.feature.split.SplitSecondaryPickerActivity"
        const val PRIMARY_PICKER = "dev.denza.apps/$PRIMARY_PICKER_ACTIVITY"
        const val SECONDARY_PICKER = "dev.denza.apps/$SECONDARY_PICKER_ACTIVITY"
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
