package dev.denza.apps.feature.split

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How a piece of the split's work stands in its journal. */
internal enum class SplitWorkState {
    /** Its terminal is written, and [SplitWorkOperation.end] says what became of it. */
    ENDED,

    /** Started, not ended yet, and young enough that the end may still come. */
    RUNNING,

    /**
     * Started and never ended in the journal: the next piece of work began first, or the process did
     * not live to write the end - every sleep of the car force-stops this package.
     */
    CUT,
}

/** One line of an operation: milliseconds after its first line, and what it says. */
internal data class SplitWorkStep(val afterMs: Long, val message: String)

/** The terminal, as [SplitCoordinatorCore.terminalOf] wrote it. */
internal data class SplitWorkEnd(val outcome: String, val reason: String, val elapsedMs: Long)

/** One piece of work somebody asked the split for, read back out of the journal. */
internal data class SplitWorkOperation(
    val label: String,
    /** `HH:mm:ss` of its first line. */
    val startedAt: String,
    val steps: List<SplitWorkStep>,
    val state: SplitWorkState,
    val end: SplitWorkEnd? = null,
)

/** One section of the «Журнал работы» page: a title, or none, over its key-value rows. */
internal data class SplitWorkSection(val title: String?, val rows: List<Pair<String, String>>)

/**
 * The split's work, read back out of its journal on disk ([SplitDiagnosticJournal]), for the
 * service: the technical page's «Последнее открытие» and the «Журнал работы» page behind it.
 *
 * Split Screen stopped working in 0.7.0-alpha on a firmware nobody here can reach with ADB. The
 * journal already held every step of every open - but in a file only `run-as` can read. This cuts
 * it into operations in words a photo carries: an operation starts at `<label> +Nms dequeued` and
 * ends at `<label> outcome=<x> reason=<y> in <n>ms`, and the operation lines between belong to it.
 * Background lines are the reconcile watching the car, never shown. A line that is not the
 * journal's own shape - half a line at the start of a tail, a message that carried a line break -
 * is skipped, never trusted.
 *
 * Pure: the files are read by [read] and everything else is plain lines in, plain values out.
 */
internal object SplitWorkJournal {

    /** Operations, oldest first, from the tails of the previous journal file and the current one. */
    fun read(directory: File, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<SplitWorkOperation> =
        parse(
            tail(File(directory, SplitDiagnosticJournal.PREVIOUS)) +
                tail(File(directory, SplitDiagnosticJournal.CURRENT)),
            nowMs,
            zone,
        )

    /**
     * The last [maxBytes] of [file], whole lines only: the first one is dropped when the tail starts
     * inside it, and the last when a writer is still in the middle of it.
     */
    fun tail(file: File, maxBytes: Long = TAIL_BYTES): List<String> = runCatching {
        RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            val from = (length - maxBytes).coerceAtLeast(0L)
            val bytes = ByteArray((length - from).toInt())
            handle.seek(from)
            handle.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).split('\n').dropLast(1)
            if (from > 0) lines.drop(1) else lines
        }
    }.getOrDefault(emptyList())

    /**
     * The journal's lines, oldest first, cut into operations, oldest first.
     *
     * [nowMs] does two things. It gives the lines their year - the journal writes `MM-dd` - and it
     * tells a start still waiting for its end from one whose process died before the end: an
     * operation is settled within its budget of seconds, so a start older than [RUNNING_MS] with no
     * terminal will never have one.
     */
    fun parse(lines: List<String>, nowMs: Long, zone: ZoneId): List<SplitWorkOperation> {
        val done = mutableListOf<SplitWorkOperation>()
        var running: Draft? = null
        // Operation lines no start claimed: what a terminal without a start was made of.
        val loose = ArrayDeque<JournalLine>()
        val year = Instant.ofEpochMilli(nowMs).atZone(zone).year
        for (raw in lines) {
            val line = JournalLine.of(raw, year, nowMs, zone) ?: continue
            val start = START.matchEntire(line.message)
            val terminal = if (start == null) TERMINAL.matchEntire(line.message) else null
            when {
                start != null -> {
                    running?.let { done += it.settle(SplitWorkState.CUT, zone) }
                    running = Draft(start.groupValues[1], mutableListOf(line))
                    loose.clear()
                }
                terminal != null -> {
                    val label = terminal.groupValues[1]
                    val end = SplitWorkEnd(
                        outcome = terminal.groupValues[2],
                        reason = terminal.groupValues[3],
                        elapsedMs = terminal.groupValues[4].toLong(),
                    )
                    val open = running
                    if (open != null && open.label == label) {
                        done += open.settle(SplitWorkState.ENDED, zone, end)
                    } else {
                        open?.let { done += it.settle(SplitWorkState.CUT, zone) }
                        // Before 2026-09-24 only an open marked its start. The rest of a select,
                        // an enable or a disable is whatever the operation lane wrote since the tap
                        // the terminal counts from.
                        val tap = line.atMs - end.elapsedMs
                        val since = loose.filter { it.atMs >= tap - SLACK_MS }.toMutableList()
                        done += Draft(label, since, tap).settle(SplitWorkState.ENDED, zone, end)
                    }
                    running = null
                    loose.clear()
                }
                else -> {
                    val open = running
                    if (open != null) {
                        open.lines += line
                    } else {
                        loose += line
                        if (loose.size > LOOSE_LIMIT) loose.removeFirst()
                    }
                }
            }
        }
        running?.let { open ->
            val young = nowMs - open.lines.first().atMs < RUNNING_MS
            done += open.settle(if (young) SplitWorkState.RUNNING else SplitWorkState.CUT, zone)
        }
        return done
    }

    /** «Последнее открытие»: `20:31 · готово · 1,8 с`, `20:34 · не вышло · 3,1 с`, or `не было`. */
    fun lastOpen(work: List<SplitWorkOperation>): String {
        val open = work.lastOrNull { it.label == SplitCoordinatorCore.OPEN_LABEL } ?: return "не было"
        return listOfNotNull(
            open.startedAt.take(HH_MM),
            verdict(open),
            open.end?.let { seconds(it.elapsedMs) },
        ).joinToString(" · ")
    }

    /**
     * The «Журнал работы» page: the last [SHOWN] operations, newest first. The newest is told step
     * by step - that is the one the photo is taken for - and the older ones by their end alone.
     */
    fun page(work: List<SplitWorkOperation>): List<SplitWorkSection> {
        if (work.isEmpty()) return listOf(SplitWorkSection(null, listOf("Операции" to "пока не было")))
        return work.takeLast(SHOWN).asReversed().mapIndexed { index, operation ->
            val rows = if (index == 0) {
                operation.steps.map { step -> "+${step.afterMs} мс" to step.message } +
                    listOfNotNull(outcome(operation))
            } else {
                listOf(outcome(operation) ?: (OUTCOME to "идёт"))
            }
            SplitWorkSection(title(operation), rows)
        }
    }

    /** `Открытие 20:31:17 · готово · 1,8 с`; a running one has no duration yet. */
    fun title(operation: SplitWorkOperation): String =
        listOfNotNull(
            "${kind(operation.label)} ${operation.startedAt}",
            verdict(operation),
            operation.end?.let { seconds(it.elapsedMs) },
        ).joinToString(" · ")

    /** What the work was, in the page's words; a label this page does not know is its own name. */
    fun kind(label: String): String = when (label) {
        SplitCoordinatorCore.OPEN_LABEL -> "Открытие"
        SplitCoordinatorCore.SELECT_LABEL -> "Выбор"
        SplitCoordinatorCore.ENABLE_LABEL -> "Включение"
        SplitCoordinatorCore.DISABLE_LABEL -> "Выключение"
        SplitCoordinatorCore.HOME_LABEL -> "Home"
        SplitCoordinatorCore.EDGE_LABEL -> "Дивайдер у края"
        SplitCoordinatorCore.RECONCILE_LABEL -> "Сверка"
        SplitCoordinatorCore.PACKAGE_REMOVED_LABEL -> "Удаление приложения"
        SplitCoordinatorCore.NAV_STARTED_LABEL -> "Навигация на приборку"
        SplitCoordinatorCore.NAV_RETURNED_LABEL -> "Навигация вернулась"
        SplitCoordinatorCore.NAV_PREPARE_LABEL -> "Подготовка возврата навигации"
        SplitCoordinatorCore.NAV_COMPLETE_LABEL -> "Возврат навигации"
        else -> label
    }

    /** Seconds to one decimal with the Russian comma, rounded: 1831 ms is `1,8 с`. */
    fun seconds(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0L) + 50) / 100
        return "${tenths / 10},${tenths % 10} с"
    }

    private fun verdict(operation: SplitWorkOperation): String = when {
        operation.state == SplitWorkState.RUNNING -> "идёт"
        operation.end?.outcome == COMMITTED -> "готово"
        else -> "не вышло"
    }

    /** The end as the journal wrote it, or that it wrote none; nothing while it may still come. */
    private fun outcome(operation: SplitWorkOperation): Pair<String, String>? = when (operation.state) {
        SplitWorkState.ENDED -> operation.end?.let { OUTCOME to "outcome=${it.outcome} reason=${it.reason}" }
        SplitWorkState.CUT -> OUTCOME to "не записан"
        SplitWorkState.RUNNING -> null
    }

    /** An operation being read: its label and its lines, the start first. */
    private class Draft(
        val label: String,
        val lines: MutableList<JournalLine>,
        /** When it began, for one whose start the journal does not hold. */
        private val startedAtMs: Long? = null,
    ) {
        fun settle(state: SplitWorkState, zone: ZoneId, end: SplitWorkEnd? = null): SplitWorkOperation {
            val first = lines.firstOrNull()?.atMs ?: startedAtMs ?: 0L
            val stamp = "$label +"
            return SplitWorkOperation(
                label = label,
                startedAt = lines.firstOrNull()?.time ?: CLOCK.withZone(zone).format(Instant.ofEpochMilli(first)),
                steps = lines.map { line ->
                    SplitWorkStep(
                        afterMs = (line.atMs - first).coerceAtLeast(0L),
                        message = line.message.removeStamp(stamp),
                    )
                },
                state = state,
                end = end,
            )
        }

        /** `open +121ms scene-read: …` is `scene-read: …`: the page has its own clock. */
        private fun String.removeStamp(stamp: String): String {
            if (!startsWith(stamp)) return this
            val rest = substring(stamp.length)
            val digits = rest.takeWhile(Char::isDigit)
            return if (digits.isNotEmpty() && rest.startsWith("${digits}ms ")) {
                rest.substring(digits.length + "ms ".length)
            } else {
                this
            }
        }
    }

    /** One operation line of the journal: when, and what. Background lines never get this far. */
    private class JournalLine(val atMs: Long, val time: String, val message: String) {
        companion object {
            fun of(raw: String, year: Int, nowMs: Long, zone: ZoneId): JournalLine? {
                val match = LINE.matchEntire(raw) ?: return null
                val g = match.groupValues
                if (g[8].isNotEmpty()) return null
                val at = epochMs(g, year, nowMs, zone) ?: return null
                return JournalLine(at, "${g[3]}:${g[4]}:${g[5]}", g[9])
            }

            /**
             * The line's instant. The journal writes no year, so it is [year] - [nowMs]'s - unless
             * that puts the line more than a day ahead of now: then it was written the year before.
             */
            private fun epochMs(g: List<String>, year: Int, nowMs: Long, zone: ZoneId): Long? = runCatching {
                fun at(year: Int) = LocalDateTime.of(
                    year,
                    g[1].toInt(),
                    g[2].toInt(),
                    g[3].toInt(),
                    g[4].toInt(),
                    g[5].toInt(),
                    g[6].toInt() * 1_000_000,
                ).atZone(zone).toInstant().toEpochMilli()
                val thisYear = at(year)
                if (thisYear > nowMs + DAY_MS) at(year - 1) else thisYear
            }.getOrNull()
        }
    }

    /** `MM-dd HH:mm:ss.SSS <process> [bg ]<message>`, exactly as [SplitDiagnosticJournal] writes. */
    private val LINE = Regex("""(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) (\S+) (bg )?(.*)""")
    private val START = Regex("""([a-z][a-z-]*) \+\d+ms dequeued""")
    private val TERMINAL = Regex("""([a-z][a-z-]*) outcome=(\S+) reason=(.*) in (\d+)ms""")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    private const val COMMITTED = "committed"
    private const val OUTCOME = "итог"
    private const val HH_MM = 5
    private const val DAY_MS = 86_400_000L

    /** The page's three: the one the photo is for, and the two before it. */
    const val SHOWN = 3

    /** Each file's tail: a split open writes a few kilobytes, so this is dozens of operations. */
    const val TAIL_BYTES = 64L * 1024

    /** Past every operation's budget and its rollback: a start this old with no end has none. */
    const val RUNNING_MS = 60_000L

    /** The journal stamps a line when its writer gets to it, a little after it was said. */
    private const val SLACK_MS = 100L

    /** Lines between operations are kept only for a terminal whose start the journal lacks. */
    private const val LOOSE_LIMIT = 64
}
