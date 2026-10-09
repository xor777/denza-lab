package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The split's shell commands that carry a quoted word, pinned letter for letter.
 *
 * Written on 2026-10-09 against the quoting each split file had of its own, before that quoting
 * moved to the shared shell layer: the move may change where a quote comes from, never a byte the
 * car receives.
 */
class SplitShellCommandPinTest {

    @Test
    fun aSelectionOverPickersSendsTheseQuotedCommands() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 40, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
            addTask(PRIMARY_ROOT, 41, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
            addTask(SECONDARY_ROOT, 42, SPLIT_HOST_PACKAGE, SECONDARY_PICKER_ACTIVITY)
        }
        val session = session(fake)

        val hosts = session.buildPickers()
        session.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget("ru.yandex.music", "ru.yandex.music/.main.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(
            listOf(
                "service call activity_task 125 s16 'dev.denza.apps'",
                "CLASSPATH='/data/app/dev.denza.apps/base.apk' app_process /system/bin " +
                    "--nice-name=denza_split_cmd dev.denza.apps.feature.split.SplitTaskProxyMain " +
                    "remove-task 40 'dev.denza.apps' " +
                    "'dev.denza.apps.feature.split.SplitPickerActivity' '-' '-'",
                "service call activity_task 125 s16 'ru.yandex.music'",
                "service call activity_task 112 s16 'ru.yandex.music'",
                "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                    "-c byd.intent.category.START_IVI_SECOND " +
                    "-n 'ru.yandex.music/.main.MainActivity' -f 0x10200000",
            ),
            fake.commands.filter { "'" in it }.distinct(),
        )
    }

    @Test
    fun buildingBothPickersSendsTheseQuotedCommands() {
        val fake = FakeShell()

        session(fake).buildPickers()

        assertEquals(
            listOf(
                "service call activity_task 125 s16 'dev.denza.apps'",
                "am start -a android.intent.action.MAIN -c byd.intent.category.START_IVI_PRIMARY " +
                    "-n 'dev.denza.apps/dev.denza.apps.feature.split.SplitPickerActivity' " +
                    "-f 0x18010000",
                "am start -a android.intent.action.MAIN -c byd.intent.category.START_IVI_SECOND " +
                    "-n 'dev.denza.apps/dev.denza.apps.feature.split.SplitPickerActivity' " +
                    "-f 0x18010000",
            ),
            fake.commands.filter { "'" in it }.distinct(),
        )
    }

    @Test
    fun aRollbackRemovalSendsThisCommand() {
        val commands = mutableListOf<String>()
        val rollback = SplitShellRollbackExecutor(
            shell = { command -> commands += command; "DENZA_SPLIT_RESULT:42=true" },
            gateLeaseStore = FakeGateLease(),
            leases = emptyList(),
            apkPath = SPLIT_APK_PATH,
            clock = FixedClock,
        )

        rollback.removeTask(42, "ru.yandex.music/.main.MainActivity")

        assertEquals(
            listOf(
                "CLASSPATH='/data/app/dev.denza.apps/base.apk' app_process /system/bin " +
                    "--nice-name=denza_split_cmd dev.denza.apps.feature.split.SplitTaskProxyMain " +
                    "remove-task 42 'ru.yandex.music' '.main.MainActivity' '-' '-'",
            ),
            commands,
        )
    }

    @Test
    fun theResidentHelperIsStartedWithThisCommand() {
        assertEquals(
            "CLASSPATH='/data/local/tmp/denza-split-proxy-60.jar' exec app_process /system/bin " +
                "--nice-name=denza_split_serve dev.denza.apps.feature.split.SplitTaskProxyMain " +
                "serve 5f3a",
            splitServeCommand("/data/local/tmp/denza-split-proxy-60.jar", "5f3a"),
        )
    }

    /** How an `activity_task` int read is read: the second word, after a zero status. */
    @Test
    fun anIntReplyIsReadAsItsSecondWordAfterAZeroStatus() {
        assertEquals(true, readsBalanced("Result: Parcel(00000000 00000003 '........')"))
        assertEquals(false, readsBalanced("Result: Parcel(00000000 00000002 '........')"))
        listOf(
            "Result: Parcel(ffffffb5 0000004c '....L...')",
            "Result: Parcel(00000000 '....')",
            "Result: Parcel(NULL)",
            "service: Service activity_task does not exist",
        ).forEach { reply ->
            assertEquals(reply, null, readsBalanced(reply))
        }
    }

    /** How a void `activity_task` call is read: one zero status word, nothing else required. */
    @Test
    fun aVoidReplyCountsOnlyWithAZeroStatus() {
        assertEquals(true, suspends("Result: Parcel(00000000 '....')"))
        assertEquals(true, suspends("Result: Parcel(00000000 00000001 '........')"))
        listOf(
            "Result: Parcel(fffffffc '....')",
            "Result: Parcel(NULL)",
            "",
        ).forEach { reply ->
            assertEquals(reply, null, suspends(reply))
        }
    }

    /**
     * A multi-line reply (an exception's message) reads as it always has: read whole, its first
     * offset `0x00000000` passes for a zero status, so a void call takes it for success. A known
     * misreading, pinned 2026-10-09 so that changing it is a decision of its own; the product sends
     * these transactions in process first and the shell is the fallback.
     */
    @Test
    fun aMultiLineReplyReadsAsItAlwaysHas() {
        assertEquals(
            true,
            suspends(
                "Result: Parcel(\n" +
                    "  0x00000000: ffffffff 0000004a 00740041 00650074 '....J...A.t.t.e.'\n" +
                    "  0x00000010: 0070006d 00200074 006f0074 00720020 'm.p.t. .t.o. .r.')",
            ),
        )
    }

    /** True or false as the session read area 3; null when it refused the reply. */
    private fun readsBalanced(reply: String): Boolean? = runCatching {
        SplitPickerShellSession(
            shell = { command -> if (command == "dumpsys input") "" else reply },
            apkPath = SPLIT_APK_PATH,
            settle = {},
            gateLeaseStore = FakeGateLease(),
        ).nativePickerMutationAllowed()
    }.getOrNull()

    /** Whether a covered scene's gate was suspended on [reply]; null when the reply was refused. */
    private fun suspends(reply: String): Boolean? = runCatching {
        SplitPickerShellSession(
            shell = { command ->
                if (command == "service call activity_task 30") {
                    "Result: Parcel(00000000 00000000 '........')"
                } else {
                    reply
                }
            },
            apkPath = SPLIT_APK_PATH,
            settle = {},
            gateLeaseStore = FakeGateLease(owned = true),
        ).suspendOwnedGateIfCovered()
    }.getOrNull()

    private fun session(fake: FakeShell) = SplitPickerShellSession(
        shell = fake::shell,
        apkPath = SPLIT_APK_PATH,
        settle = {},
        gateLeaseStore = FakeGateLease(),
    )

    private object FixedClock : SplitClock {
        override fun nowMs(): Long = 0L

        override fun schedule(delayMs: Long, action: () -> Unit): SplitCancellable =
            error("a rollback schedules nothing")
    }
}
