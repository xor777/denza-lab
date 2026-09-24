package dev.denza.apps.feature.split

import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BYD split transactions an operation sends from the app process instead of over ADB
 * (findings, "No permission guards the BYD split family"; proven from an app UID 2026-09-23).
 */
class SplitInProcessCallsTest {
    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun tearDown() {
        cars.forEach(SplitCarFixture::close)
    }

    @Test
    fun theFiveCallsTheRecipesSendAreRecognisedLetterForLetter() {
        assertEquals(
            SplitBinderCall(30, emptyList(), repliesInt = true),
            SplitBinderCall.of("service call activity_task 30"),
        )
        assertEquals(
            SplitBinderCall(118, listOf(SplitBinderArgument.Int32(2)), repliesInt = true),
            SplitBinderCall.of("service call activity_task 118 i32 2"),
        )
        assertEquals(
            SplitBinderCall(112, listOf(SplitBinderArgument.Utf16(MUSIC)), repliesInt = true),
            SplitBinderCall.of("service call activity_task 112 s16 '$MUSIC'"),
        )
        assertEquals(
            SplitBinderCall(125, listOf(SplitBinderArgument.Utf16("it's.odd")), repliesInt = false),
            SplitBinderCall.of("service call activity_task 125 s16 'it'\\''s.odd'"),
        )
        assertEquals(
            SplitBinderCall(126, listOf(SplitBinderArgument.Int32(0)), repliesInt = false),
            SplitBinderCall.of("service call activity_task 126 i32 0"),
        )
    }

    @Test
    fun anythingElseGoesOnDownTheFunnel() {
        listOf(
            "service call activity_task 114 i32 101",
            "service call activity_task 30 i32 1",
            "service call activity_task 118",
            "service call activity_task 118 s16 '2'",
            "service call activity_task 126 i32 2",
            "service call activity_task 112 s16 ''",
            "service call activity_task 112 s16 \"$MUSIC\"",
            "service call activity 30",
            "am stack list",
            "service call activity_task 30; reboot",
        ).forEach { command -> assertNull(command, SplitBinderCall.of(command)) }
    }

    @Test
    fun anAnswerIsWhatServiceCallWouldHavePrintedAndAFailureIsNoAnswer() {
        val calls = mutableListOf<Pair<Int, List<SplitBinderArgument>>>()
        val firmware = SplitInProcessFirmware(
            object : SplitBinderTransport {
                override fun callInt(code: Int, arguments: List<SplitBinderArgument>): Int {
                    calls += code to arguments
                    if (code == 118) error("binder died")
                    return 3
                }

                override fun callVoid(code: Int, arguments: List<SplitBinderArgument>) {
                    calls += code to arguments
                }
            },
        )

        assertEquals(
            "Result: Parcel(00000000 00000003 '........')",
            firmware.answer("service call activity_task 30"),
        )
        assertEquals(
            "Result: Parcel(00000000 '....')",
            firmware.answer("service call activity_task 126 i32 1"),
        )
        assertNull("a failure sends the command the old way", firmware.answer("service call activity_task 118 i32 1"))
        assertNull("not recognised, not attempted", firmware.answer("service call activity_task 114 i32 101"))
        assertEquals(listOf(30, 126, 118), calls.map { it.first })
    }

    /**
     * The service's «Сигналы прошивки» (2026-09-24): on a firmware whose binder refuses these calls
     * every open quietly pays the shell instead, and the last call's result is how a photo says so.
     */
    @Test
    fun theLastCallThisProcessSentIsWrittenDownAndACommandItDidNotSendIsNot() {
        var refuse = false
        val health = SplitInProcessHealth()
        val firmware = SplitInProcessFirmware(
            object : SplitBinderTransport {
                override fun callInt(code: Int, arguments: List<SplitBinderArgument>): Int {
                    if (refuse) throw SecurityException("tx$code")
                    return 3
                }

                override fun callVoid(code: Int, arguments: List<SplitBinderArgument>) {
                    if (refuse) throw SecurityException("tx$code")
                }
            },
            health,
        )

        assertNull("не было ни одного вызова", health.lastCallOk)
        firmware.answer("am stack list")
        assertNull("чужая команда о binder ничего не говорит", health.lastCallOk)

        firmware.answer("service call activity_task 30")
        assertEquals(true, health.lastCallOk)

        refuse = true
        firmware.answer("service call activity_task 126 i32 1")
        assertEquals(false, health.lastCallOk)

        firmware.answer("service call activity_task 114 i32 101")
        assertEquals("нераспознанная команда не стирает отказ", false, health.lastCallOk)

        refuse = false
        firmware.answer("service call activity_task 118 i32 2")
        assertEquals(true, health.lastCallOk)
    }

    @Test
    fun theFirmwareRowsSayWhatWasReadAndAQuestionMarkForWhatWasNot() {
        assertEquals(
            "две панели · область 3",
            SplitFirmwareReading(100, 3, homeKeyHeard = true, areaHeard = true, callsOk = true).split(),
        )
        assertEquals("одна узкая · область 1", SplitFirmwareReading(101, 1, true, true, true).split())
        assertEquals("одна широкая · область 0", SplitFirmwareReading(102, 0, true, true, true).split())
        assertEquals("режим ? · область 2", SplitFirmwareReading(103, 2, true, true, true).split())
        assertEquals("режим ? · область ?", SplitFirmwareReading(null, null, true, true, true).split())

        assertEquals(
            "Home да · область да · вызовы да",
            SplitFirmwareReading(100, 3, homeKeyHeard = true, areaHeard = true, callsOk = true).signals(),
        )
        assertEquals(
            "Home нет · область да · вызовы нет",
            SplitFirmwareReading(100, 3, homeKeyHeard = false, areaHeard = true, callsOk = false).signals(),
        )
        assertEquals(
            "Home да · область нет · вызовы не было",
            SplitFirmwareReading(null, null, homeKeyHeard = true, areaHeard = false, callsOk = null).signals(),
        )
    }

    @Test
    fun anOpenSendsNoneOfThemOverAdbAndEndsExactlyAsBefore() {
        val car = SplitCarFixture(FakeShell()).also(cars::add)
        val inProcess = Collections.synchronizedList(mutableListOf<String>())
        val core = car.core(
            SplitDurable(enabled = true, slots = APP_PAIR),
            inProcessCalls = SplitInProcessCalls { command ->
                SplitBinderCall.of(command)?.let {
                    inProcess += command
                    car.fake.shell(command)
                }
            },
        )
        core.initialize {}

        core.openPickerSession()
        car.barrier()

        val overAdb = car.sessions().flatten()
        assertTrue(
            "ни area, ни корни, ни gate, ни список не ушли в ADB: $overAdb",
            overAdb.none { command -> SplitBinderCall.of(command) != null },
        )
        assertTrue(inProcess.contains("service call activity_task 126 i32 1"))
        assertTrue(inProcess.any { it.startsWith("service call activity_task 30") })
        assertTrue(inProcess.any { it.startsWith("service call activity_task 118 ") })
        assertEquals(SplitScreenPhase.ACTIVE, core.snapshot().phase)
        assertEquals(APP_PAIR, car.store.load().slots)
        assertEquals(3, car.fake.area)
        assertTrue(car.fake.isGateOpen())
        assertTrue(
            "строка бюджета называет, сколько обращений не покидали процесс",
            car.diagnostics.any { it.matches(Regex("open: обращений \\d+ \\(в процессе \\d+\\), .*")) },
        )
        assertTrue(
            "tx125 всё так же в ринге (1.12)",
            car.diagnostics.any { it.startsWith("firmware split allowlist extended: '$MUSIC'") },
        )
    }
}
