package dev.denza.apps.feature.split

import java.io.File
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The split's work read back out of its journal (2026-09-24): the service's «Последнее открытие»
 * and its «Журнал работы» page, which are what an owner on a foreign firmware can photograph and
 * send when Split Screen does not work and nobody can reach the car with ADB.
 */
class SplitWorkJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun tearDown() {
        cars.forEach(SplitCarFixture::close)
    }

    @Test
    fun anOpenIsItsLinesFromTheStartToTheTerminalAndTheBackgroundIsNotInIt() {
        val work = parse(COMMITTED_OPEN)

        assertEquals(1, work.size)
        val open = work.single()
        assertEquals("open", open.label)
        assertEquals("20:31:17", open.startedAt)
        assertEquals(SplitWorkState.ENDED, open.state)
        assertEquals(SplitWorkEnd("committed", "-", 1831), open.end)
        assertEquals(
            "the wall clock of each line after the first, and the line without its own stamp",
            listOf(
                SplitWorkStep(0, "dequeued"),
                SplitWorkStep(125, "scene-read: SECONDARY: пикеров 0, задач 1"),
                SplitWorkStep(286, "firmware split allowlist extended: 'dev.denza.apps'"),
                SplitWorkStep(846, "roots-started"),
                SplitWorkStep(1832, "open: обращений 28 (в процессе 17), в shell 1.6 с, разбор 0.0 с"),
            ),
            open.steps,
        )
    }

    @Test
    fun thePageTellsTheNewestStepByStepAndTheTwoBeforeItByTheirEnd() {
        val work = parse(
            SELECT_THEN_OPEN_BEFORE +
                COMMITTED_OPEN +
                listOf(
                    "09-23 20:34:02.100 main open +0ms dequeued",
                    "09-23 20:34:02.231 main open +129ms scene-read: area=0",
                    "09-23 20:34:02.240 main bg reconcile: мир не изменился",
                    "09-23 20:34:02.533 main open +431ms leases-taken",
                    "09-23 20:34:02.690 main open +588ms roots-started",
                    "09-23 20:34:05.211 main open outcome=rolled-back reason=Прошивка не раскрыла native split in 3112ms",
                ),
        )

        assertEquals("четыре операции в журнале, старые первыми", listOf("open", "select", "open", "open"), work.map { it.label })
        assertEquals(
            listOf(
                SplitWorkSection(
                    "Открытие 20:34:02 · не вышло · 3,1 с",
                    listOf(
                        "+0 мс" to "dequeued",
                        "+131 мс" to "scene-read: area=0",
                        "+433 мс" to "leases-taken",
                        "+590 мс" to "roots-started",
                        "итог" to "outcome=rolled-back reason=Прошивка не раскрыла native split",
                    ),
                ),
                SplitWorkSection("Открытие 20:31:17 · готово · 1,8 с", listOf("итог" to "outcome=committed reason=-")),
                SplitWorkSection("Выбор 20:30:40 · готово · 1,2 с", listOf("итог" to "outcome=committed reason=-")),
            ),
            SplitWorkJournal.page(work),
        )
        assertEquals("20:34 · не вышло · 3,1 с", SplitWorkJournal.lastOpen(work))
    }

    @Test
    fun aStartWithNoEndYetIsStillRunningAndHasNoOutcomeRow() {
        val work = parse(
            COMMITTED_OPEN + listOf(
                "09-23 20:39:50.000 main open +0ms dequeued",
                "09-23 20:39:50.120 main open +118ms scene-read: area=0",
            ),
        )

        assertEquals(SplitWorkState.RUNNING, work.last().state)
        assertEquals(
            SplitWorkSection("Открытие 20:39:50 · идёт", listOf("+0 мс" to "dequeued", "+120 мс" to "scene-read: area=0")),
            SplitWorkJournal.page(work).first(),
        )
        assertEquals("20:39 · идёт", SplitWorkJournal.lastOpen(work))
    }

    /** Every sleep of the car force-stops the product: an open it was in the middle of never ends. */
    @Test
    fun aStartThatNeverEndedIsCutAndSaysItsEndWasNeverWritten() {
        val stale = parse(listOf("09-23 20:31:17.688 main open +0ms dequeued"))
        assertEquals("минута без терминала - терминала не будет", SplitWorkState.CUT, stale.single().state)
        assertEquals(
            SplitWorkSection("Открытие 20:31:17 · не вышло", listOf("+0 мс" to "dequeued", "итог" to "не записан")),
            SplitWorkJournal.page(stale).single(),
        )
        assertEquals("20:31 · не вышло", SplitWorkJournal.lastOpen(stale))

        val superseded = parse(
            listOf(
                "09-23 20:39:40.000 main open +0ms dequeued",
                "09-23 20:39:41.000 main open +1000ms roots-started",
                "09-23 20:39:50.000 main disable +0ms dequeued",
                "09-23 20:39:50.300 main disable outcome=committed reason=- in 300ms",
            ),
        )
        assertEquals(listOf(SplitWorkState.CUT, SplitWorkState.ENDED), superseded.map { it.state })
        assertEquals(
            SplitWorkSection("Открытие 20:39:40 · не вышло", listOf("итог" to "не записан")),
            SplitWorkJournal.page(superseded)[1],
        )
    }

    @Test
    fun aLineThatIsNotTheJournalsOwnShapeIsSkippedNotTrusted() {
        val work = parse(
            listOf(
                "",
                "garbage",
                "3 20:31:17.600 main open +0ms dequeued",
                "13-45 20:31:17.600 main open +0ms dequeued",
                "09-23 25:31:17.600 main open +0ms dequeued",
                "09-23 20:31:17 main open +0ms dequeued",
                "09-23 20:31:17.600 main",
            ) + COMMITTED_OPEN.take(2) +
                listOf("  продолжение сообщения, в котором был перевод строки") +
                COMMITTED_OPEN.drop(2),
        )

        assertEquals(1, work.size)
        assertEquals(5, work.single().steps.size)
        assertEquals("20:31:17", work.single().startedAt)
    }

    /**
     * A journal written before 2026-09-24: only an open marked its start, and a select was a
     * terminal. Its lines are the ones written since the tap it counts from, not everything since the
     * last operation.
     */
    @Test
    fun aTerminalWithoutAStartIsTheLinesSinceItsTap() {
        val work = parse(
            COMMITTED_OPEN + listOf(
                "09-23 20:32:00.000 main firmware resizeability lease: старое, не этого выбора",
                "09-23 20:33:10.050 main select +40ms read-back начат",
                "09-23 20:33:10.300 main select +290ms read-back: area=3",
                "09-23 20:33:10.320 main select outcome=committed reason=- in 330ms",
            ),
        )

        val select = work.last()
        assertEquals("select", select.label)
        assertEquals("20:33:10", select.startedAt)
        assertEquals(
            listOf(SplitWorkStep(0, "read-back начат"), SplitWorkStep(250, "read-back: area=3")),
            select.steps,
        )
        assertEquals("Выбор 20:33:10 · готово · 0,3 с", SplitWorkJournal.title(select))
    }

    @Test
    fun anEmptyJournalSaysSoInOneRow() {
        assertEquals(
            listOf(SplitWorkSection(null, listOf("Операции" to "пока не было"))),
            SplitWorkJournal.page(emptyList()),
        )
        assertEquals("не было", SplitWorkJournal.lastOpen(emptyList()))
        assertEquals("а выбор - не открытие", "не было", SplitWorkJournal.lastOpen(parse(SELECT_THEN_OPEN_BEFORE.drop(3))))
    }

    @Test
    fun secondsAreOneDecimalWithTheRussianCommaAndAnUnknownLabelIsItsOwnName() {
        assertEquals("1,8 с", SplitWorkJournal.seconds(1831))
        assertEquals("1,9 с", SplitWorkJournal.seconds(1850))
        assertEquals("0,0 с", SplitWorkJournal.seconds(0))
        assertEquals("12,3 с", SplitWorkJournal.seconds(12_345))
        assertEquals("Открытие", SplitWorkJournal.kind("open"))
        assertEquals("Выбор", SplitWorkJournal.kind("select"))
        assertEquals("Выключение", SplitWorkJournal.kind("disable"))
        assertEquals("swap", SplitWorkJournal.kind("swap"))
    }

    /** Not needed for the page, and cheap: the journal writes no year, and a line keeps its own. */
    @Test
    fun aLineFromLastYearIsLastYearsAndTheClockRunsOnAcrossMidnight() {
        val work = SplitWorkJournal.parse(
            listOf(
                "12-31 23:59:59.900 main open +0ms dequeued",
                "01-01 00:00:00.150 main open +250ms roots-started",
                "01-01 00:00:01.000 main open outcome=committed reason=- in 1100ms",
            ),
            JAN_1_00_00_30,
            UTC,
        )

        assertEquals(listOf(SplitWorkStep(0, "dequeued"), SplitWorkStep(250, "roots-started")), work.single().steps)
    }

    /** The tail of a file is whole lines: half a line at the cut and half a line being written are not. */
    @Test
    fun aTailIsWholeLinesOnly() {
        val file = folder.newFile()
        file.writeText("first line\nsecond line\nthird line\nhalf of a fou")

        assertEquals(listOf("first line", "second line", "third line"), SplitWorkJournal.tail(file))
        assertEquals(listOf("third line"), SplitWorkJournal.tail(file, maxBytes = "d line\nthird line\nhalf of a fou".length.toLong()))
        assertEquals(emptyList<String>(), SplitWorkJournal.tail(File(folder.root, "absent.log")))
    }

    /** The file the journal writes is the file the page reads, the previous one before the current. */
    @Test
    fun whatTheJournalWritesIsWhatThePageReads() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val dir = folder.newFolder()
            var clock = SEP_23_20_31_17_688
            // Three lines are 143 bytes: the fourth goes into a fresh file.
            val journal = SplitDiagnosticJournal(dir, "main", capBytes = 100, now = { clock })
            journal.append("open +0ms dequeued", background = false)
            clock += 120
            journal.append("open +120ms scene-read: area=0", background = false)
            clock += 30
            journal.append("reconcile: area 3", background = true)
            clock += 1_700
            journal.append("open outcome=committed reason=- in 1850ms", background = false)

            assertTrue("the cap turned the journal over", File(dir, SplitDiagnosticJournal.PREVIOUS).exists())
            val work = SplitWorkJournal.read(dir, NOW, UTC)
            assertEquals(
                SplitWorkOperation(
                    label = "open",
                    startedAt = "20:31:17",
                    steps = listOf(SplitWorkStep(0, "dequeued"), SplitWorkStep(120, "scene-read: area=0")),
                    state = SplitWorkState.ENDED,
                    end = SplitWorkEnd("committed", "-", 1850),
                ),
                work.single(),
            )
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    /**
     * What the coordinator writes for each piece of work a person asks for: a start and a terminal.
     * Before 2026-09-24 only an open had the start, and a disable or an enable was a terminal alone.
     */
    @Test
    fun everyOperationAPersonAsksForStartsAndEndsInTheJournal() {
        val car = SplitCarFixture(FakeShell()).also(cars::add)
        val core = car.core(SplitDurable(enabled = true, slots = APP_PAIR))
        core.initialize {}
        car.barrier()
        car.diagnostics.clear()
        car.backgroundDiagnostics.clear()

        core.openPickerSession()
        car.barrier()
        core.setEnabled(false)
        car.barrier()
        // The launcher entry on a car whose toggle is off: the enable first, then the open.
        core.openPickerSession()
        car.barrier()

        // The journal's own shape, a line every ten milliseconds, with the lane each was written in.
        val background = car.backgroundDiagnostics.toMutableList()
        val lines = car.diagnostics.toList().mapIndexed { index, message ->
            val lane = if (background.remove(message)) "bg " else ""
            "09-23 20:39:%02d.%03d main $lane$message".format(index / 100, index % 100 * 10)
        }
        val work = parse(lines)

        assertEquals(listOf("open", "disable", "enable", "open"), work.map { it.label })
        assertEquals(List(4) { SplitWorkState.ENDED }, work.map { it.state })
        assertEquals(List(4) { "committed" }, work.map { it.end?.outcome })
        assertTrue(
            "каждая начинается своей отметкой: ${work.map { it.steps.firstOrNull() }}",
            work.all { operation -> operation.steps.first().message == "dequeued" },
        )
    }

    private fun parse(lines: List<String>): List<SplitWorkOperation> = SplitWorkJournal.parse(lines, NOW, UTC)

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 2026-09-23 20:40:00 UTC. */
        const val NOW = 1_790_196_000_000L

        /** 2026-09-23 20:31:17.688 UTC. */
        const val SEP_23_20_31_17_688 = 1_790_195_477_688L

        /** 2027-01-01 00:00:30 UTC. */
        const val JAN_1_00_00_30 = 1_798_761_630_000L

        /** The sample the owner was shown, written by build 54 on the car, 2026-09-23. */
        val COMMITTED_OPEN = listOf(
            "09-23 20:31:17.688 main open +0ms dequeued",
            "09-23 20:31:17.813 main open +121ms scene-read: SECONDARY: пикеров 0, задач 1",
            "09-23 20:31:17.974 main firmware split allowlist extended: 'dev.denza.apps'",
            "09-23 20:31:18.370 main bg area push 3: мир у операции HINT, OPEN",
            "09-23 20:31:18.534 main open +801ms roots-started",
            "09-23 20:31:19.520 main open: обращений 28 (в процессе 17), в shell 1.6 с, разбор 0.0 с",
            "09-23 20:31:19.527 main open outcome=committed reason=- in 1831ms",
        )

        val SELECT_THEN_OPEN_BEFORE = listOf(
            "09-23 20:30:10.000 main open +0ms dequeued",
            "09-23 20:30:11.900 main open outcome=committed reason=- in 1900ms",
            "09-23 20:30:12.000 main bg reconcile: мир не изменился",
            "09-23 20:30:40.000 main select +0ms dequeued",
            "09-23 20:30:41.200 main select outcome=committed reason=- in 1200ms",
        )
    }
}
