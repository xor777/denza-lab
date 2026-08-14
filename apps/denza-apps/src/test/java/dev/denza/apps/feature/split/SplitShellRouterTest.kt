package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitShellRouterTest {
    @Test
    fun firstSnapshotStartsPlaceholderAndPersistsTargetBeforeMutation() {
        val fake = FakeShell(fullscreen(APP_A, 10))
        val store = FakeStateStore()
        val router = router(fake, store)

        assertFalse(router.tick())

        assertEquals(APP_A, store.memory.target?.first?.packageName)
        assertEquals(PLACEHOLDER_PACKAGE, store.memory.target?.second?.packageName)
        assertTrue(fake.commands.any { it == "am start -n $PLACEHOLDER_COMPONENT -f 0x18010000" })
        assertTrue(fake.commands.indexOf("service call activity_task 126 i32 1") < fake.commands.indexOf("am start -n $PLACEHOLDER_COMPONENT -f 0x18010000"))
    }

    @Test
    fun identicalSnapshotDoesNotRepeatSuccessfulMutation() {
        val fake = FakeShell(fullscreen(APP_A, 10))
        fake.varyIrrelevantStackText = true
        val router = router(fake)
        router.tick()
        val startsAfterFirstTick = fake.commands.count { it.startsWith("am start ") }

        repeat(20) { router.tick() }

        assertEquals(startsAfterFirstTick, fake.commands.count { it.startsWith("am start ") })
    }

    @Test
    fun executorConvergesPlacementDividerAndBoundsOneObservedStepAtATime() {
        val fake = FakeShell(fullscreen(APP_A, 10))
        val router = router(fake)
        router.tick()
        fake.commands.clear()

        fake.stack = placeholderAndAppFullscreen()
        router.tick()
        assertTrue(fake.commands.any { it == "am stack move-task 10 3 true" })
        assertTrue(fake.commands.any { it == "am stack move-task 99 2 true" })

        fake.commands.clear()
        fake.area = 4
        fake.stack = pair(APP_A to 10, PLACEHOLDER_PACKAGE to 99, taskBoundsMatchRoots = false)
        fake.stackAfterSwipe = pair(APP_A to 10, PLACEHOLDER_PACKAGE to 99)
        router.tick()
        assertTrue(fake.commands.any { it.startsWith("input swipe ") })

        fake.commands.clear()
        router.tick()
        assertTrue(fake.commands.isNotEmpty())
        assertFalse(fake.commands.any { it.startsWith("am stack move-task ") })

        fake.commands.clear()
        assertTrue(router.tick())
        assertFalse(fake.commands.any { it.startsWith("am start ") })
    }

    @Test
    fun persistedTargetPromotesCorrectTaskAbovePickerWithoutMovingNeighbor() {
        val target = SplitPairTarget(
            first = expected(10, APP_A, 3),
            second = expected(20, APP_B, 2),
        )
        val store = FakeStateStore(SplitRoutingMemory(target = target))
        val fake = FakeShell(targetAppUnderPicker())
        fake.area = 3
        val router = router(fake, store)

        assertFalse(router.tick())

        assertTrue(fake.commands.any { it.contains("SplitTaskProxyMain focus-task 20") })
        assertFalse(fake.commands.any { it == "am stack move-task 10 2 true" })
        assertFalse(fake.commands.any { it == "am stack move-task 10 3 true" })
    }

    @Test
    fun persistedTargetSurvivesRouterRecreation() {
        val store = FakeStateStore()
        val firstFake = FakeShell(fullscreen(APP_A, 10))
        router(firstFake, store).tick()
        assertTrue(store.memory.target != null)

        val recoveredFake = FakeShell(placeholderAndAppFullscreen())
        val recovered = router(recoveredFake, store)
        recovered.tick()

        assertTrue(recoveredFake.commands.any { it == "am stack move-task 10 3 true" })
        assertTrue(recoveredFake.commands.any { it == "am stack move-task 99 2 true" })
    }

    @Test
    fun externalMoveAcceptsNextSnapshotAsBaselineWithoutShellMutation() {
        val fake = FakeShell(fullscreen(APP_A, 10))
        val store = FakeStateStore()
        val router = router(fake, store)
        router.cancelPendingSelection()

        assertFalse(router.tick())

        assertEquals(APP_A, store.memory.anchor?.packageName)
        assertFalse(fake.commands.any { it.startsWith("am start ") })
        assertFalse(fake.commands.any { it.startsWith("am stack move-task ") })
    }

    @Test
    fun disableClosesOnlyGateOwnedByRouterAndClearsPersistedIntent() {
        val fake = FakeShell(fullscreen(APP_A, 10))
        val store = FakeStateStore()
        val router = router(fake, store)
        router.tick()
        fake.commands.clear()

        router.disable()

        assertEquals(listOf("service call activity_task 126 i32 0"), fake.commands)
        assertEquals(SplitRoutingMemory(), store.memory)
        assertTrue(store.cleared)
    }

    @Test
    fun visibilityFlappingHasBoundedPromotionAttemptsAndAbandonsTarget() {
        val store = FakeStateStore(
            SplitRoutingMemory(
                target = SplitPairTarget(
                    first = expected(10, APP_A, 3),
                    second = expected(20, APP_B, 2),
                ),
            ),
        )
        val fake = FakeShell(flappingPair(appAVisible = true, appBVisible = false))
        fake.area = 3
        var now = 0L
        val router = router(fake, store, nowMs = { now })

        repeat(24) { index ->
            fake.stack = flappingPair(
                appAVisible = index % 2 == 0,
                appBVisible = index % 2 != 0,
            )
            router.tick()
            now += 1_100L
        }

        assertTrue(
            fake.commands.count { it.contains("SplitTaskProxyMain focus-task ") } <= 3,
        )
        assertEquals(null, store.memory.target)
    }

    @Test
    fun failedThirdAppReplacementRetainsOriginalStablePair() {
        val stablePair = SplitStablePair(
            first = expected(10, APP_A, 2),
            second = expected(20, APP_B, 3),
            lastFocusedRootId = 2,
        )
        val store = FakeStateStore(
            SplitRoutingMemory(
                target = SplitPairTarget(
                    first = stablePair.first,
                    second = expected(30, APP_C, 3),
                ),
                stablePair = stablePair,
            ),
        )
        val fake = FakeShell(fullscreenWithHiddenPair(APP_C, 30))
        var now = 0L
        val router = router(fake, store, nowMs = { now })

        repeat(4) {
            router.tick()
            now += 1_100L
        }

        assertEquals(stablePair, store.memory.stablePair)
        assertEquals(null, store.memory.target)
        assertEquals(3, fake.commands.count { it == "am stack move-task 30 3 true" })
    }

    @Test
    fun unchangedExpandedBaselineDoesNotRepeatCloseMutationOrEvent() {
        val fake = FakeShell(fullscreen(APP_A, 10))
        val events = mutableListOf<String>()
        val router = SplitShellRouter(
            shell = fake::execute,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            eligibleApps = { mapOf(APP_A to "$APP_A/$APP_A.MainActivity") },
            pause = fake.pauses::add,
            onEvent = events::add,
        )
        router.cancelPendingSelection()
        router.tick()
        fake.commands.clear()

        repeat(20) { router.tick() }

        assertTrue(fake.commands.none { it == "service call activity_task 126 i32 0" })
        assertTrue(events.count { it.startsWith("expanded anchor preserved") } <= 1)
    }

    private fun router(
        fake: FakeShell,
        store: FakeStateStore = FakeStateStore(),
        nowMs: () -> Long = System::currentTimeMillis,
    ) = SplitShellRouter(
        shell = fake::execute,
        apkPath = "/data/app/dev.denza.apps/base.apk",
        eligibleApps = {
            mapOf(
                APP_A to "$APP_A/$APP_A.MainActivity",
                APP_B to "$APP_B/$APP_B.MainActivity",
                APP_C to "$APP_C/$APP_C.MainActivity",
            )
        },
        pause = fake.pauses::add,
        nowMs = nowMs,
        stateStore = store,
    )

    private class FakeStateStore(
        var memory: SplitRoutingMemory = SplitRoutingMemory(),
    ) : SplitRoutingStateStore {
        var cleared = false

        override fun load(): SplitRoutingMemory = memory

        override fun save(memory: SplitRoutingMemory) {
            this.memory = memory
        }

        override fun clear() {
            memory = SplitRoutingMemory()
            cleared = true
        }
    }

    private class FakeShell(var stack: String) {
        var area = 4
        var gate = false
        var stackAfterSwipe: String? = null
        var varyIrrelevantStackText = false
        var stackReadCount = 0
        val supported = mutableSetOf<String>()
        val commands = mutableListOf<String>()
        val pauses = mutableListOf<Long>()

        fun execute(command: String): String {
            commands += command
            return when {
                command == "service call activity_task 118 i32 1" -> intParcel(2)
                command == "service call activity_task 118 i32 2" -> intParcel(3)
                command == "service call activity_task 30" -> intParcel(area)
                command == "service call activity_task 123" -> intParcel(if (gate) 1 else 0)
                command == "service call activity_task 126 i32 1" -> {
                    gate = true
                    voidParcel()
                }
                command == "service call activity_task 126 i32 0" -> {
                    gate = false
                    voidParcel()
                }
                command.startsWith("service call activity_task 112 s16 ") ->
                    intParcel(if (quotedArgument(command) in supported) 1 else 0)
                command.startsWith("service call activity_task 125 s16 ") -> {
                    supported += quotedArgument(command)
                    voidParcel()
                }
                command == "am stack list" -> if (varyIrrelevantStackText) {
                    "$stack\nignored-frame=${stackReadCount++}"
                } else {
                    stack
                }
                command.startsWith("am start ") -> ""
                command.startsWith("am stack move-task ") -> ""
                command.startsWith("am task resize ") -> ""
                command.contains("SplitTaskProxyMain focus-task ") ->
                    "DENZA_SPLIT_RESULT:true"
                command == "dumpsys input" -> DIVIDER
                command.startsWith("input swipe ") -> {
                    area = 3
                    stackAfterSwipe?.let { stack = it }
                    ""
                }
                else -> error("Unexpected command: $command")
            }
        }

        private fun quotedArgument(command: String): String =
            command.substringAfter("s16 '").substringBeforeLast("'")

        private fun intParcel(value: Int): String =
            "Result: Parcel(00000000 ${"%08x".format(value)} '........')"

        private fun voidParcel(): String = "Result: Parcel(00000000 '....')"
    }

    private companion object {
        const val APP_A = "ru.yandex.yandexnavi"
        const val APP_B = "ru.yandex.music"
        const val APP_C = "ru.rutube.app"
        const val PLACEHOLDER_PACKAGE = "dev.denza.apps"
        const val PLACEHOLDER_ACTIVITY =
            "dev.denza.apps.feature.split.SplitPlaceholderActivity"
        const val PLACEHOLDER_COMPONENT = "$PLACEHOLDER_PACKAGE/$PLACEHOLDER_ACTIVITY"
        const val PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
        const val DIVIDER = "name='Embedded{multi-divider-shadow}', frame=[-67,0][108,1600]"

        fun expected(id: Int, packageName: String, rootId: Int) = SplitExpectedTask(
            id = id,
            packageName = packageName,
            activityName = "$packageName.MainActivity",
            preferredRootId = rootId,
        )

        fun fullscreen(packageName: String, taskId: Int): String = """
            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=$taskId: $packageName/$packageName.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{$packageName/$packageName.MainActivity}

            RootTask id=2 bounds=[24,112][856,1472] displayId=0 userId=0
            RootTask id=3 bounds=[880,112][2536,1472] displayId=0 userId=0
        """.trimIndent()

        fun fullscreenWithHiddenPair(packageName: String, taskId: Int): String = """
            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=$taskId: $packageName/$packageName.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{$packageName/$packageName.MainActivity}

            RootTask id=2 bounds=[24,112][856,1472] displayId=0 userId=0
              taskId=10: $APP_A/$APP_A.MainActivity bounds=[24,112][856,1472] userId=0 visible=false topActivity=ComponentInfo{$APP_A/$APP_A.MainActivity}

            RootTask id=3 bounds=[880,112][2536,1472] displayId=0 userId=0
              taskId=20: $APP_B/$APP_B.MainActivity bounds=[880,112][2536,1472] userId=0 visible=false topActivity=ComponentInfo{$APP_B/$APP_B.MainActivity}
        """.trimIndent()

        fun placeholderAndAppFullscreen(): String = """
            RootTask id=4 bounds=[0,0][2560,1600] displayId=0 userId=0
              taskId=99: $PLACEHOLDER_COMPONENT bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{$PLACEHOLDER_COMPONENT}
              taskId=10: $APP_A/$APP_A.MainActivity bounds=[0,0][2560,1600] userId=0 visible=true topActivity=ComponentInfo{$PLACEHOLDER_COMPONENT}

            RootTask id=2 bounds=[24,112][856,1472] displayId=0 userId=0
            RootTask id=3 bounds=[880,112][2536,1472] displayId=0 userId=0
        """.trimIndent()

        fun pair(
            wide: Pair<String, Int>,
            narrow: Pair<String, Int>,
            taskBoundsMatchRoots: Boolean = true,
        ): String {
            val wideBounds = if (taskBoundsMatchRoots) "[880,112][2536,1472]" else "[0,0][2560,1600]"
            val narrowBounds = if (taskBoundsMatchRoots) "[24,112][856,1472]" else "[0,0][2560,1600]"
            val narrowActivity = if (narrow.first == PLACEHOLDER_PACKAGE) {
                PLACEHOLDER_ACTIVITY
            } else {
                "${narrow.first}.MainActivity"
            }
            return """
                RootTask id=2 bounds=[24,112][856,1472] displayId=0 userId=0
                  taskId=${narrow.second}: ${narrow.first}/$narrowActivity bounds=$narrowBounds userId=0 visible=true topActivity=ComponentInfo{${narrow.first}/$narrowActivity}

                RootTask id=3 bounds=[880,112][2536,1472] displayId=0 userId=0
                  taskId=${wide.second}: ${wide.first}/${wide.first}.MainActivity bounds=$wideBounds userId=0 visible=true topActivity=ComponentInfo{${wide.first}/${wide.first}.MainActivity}
            """.trimIndent()
        }

        fun targetAppUnderPicker(): String = """
            RootTask id=2 bounds=[24,112][856,1472] displayId=0 userId=0
              taskId=202: com.android.launcher3/$PICKER_ACTIVITY bounds=[24,112][856,1472] userId=0 visible=true topActivity=ComponentInfo{com.android.launcher3/$PICKER_ACTIVITY}
              taskId=20: $APP_B/$APP_B.MainActivity bounds=[24,112][856,1472] userId=0 visible=true topActivity=ComponentInfo{com.android.launcher3/$PICKER_ACTIVITY}

            RootTask id=3 bounds=[880,112][2536,1472] displayId=0 userId=0
              taskId=10: $APP_A/$APP_A.MainActivity bounds=[880,112][2536,1472] userId=0 visible=true topActivity=ComponentInfo{$APP_A/$APP_A.MainActivity}
        """.trimIndent()

        fun flappingPair(appAVisible: Boolean, appBVisible: Boolean): String = """
            RootTask id=2 bounds=[24,112][856,1472] displayId=0 userId=0
              taskId=20: $APP_B/$APP_B.MainActivity bounds=[24,112][856,1472] userId=0 visible=$appBVisible topActivity=ComponentInfo{$APP_B/$APP_B.MainActivity}

            RootTask id=3 bounds=[880,112][2536,1472] displayId=0 userId=0
              taskId=10: $APP_A/$APP_A.MainActivity bounds=[880,112][2536,1472] userId=0 visible=$appAVisible topActivity=ComponentInfo{$APP_A/$APP_A.MainActivity}
        """.trimIndent()
    }
}
