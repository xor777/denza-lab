package dev.denza.apps.feature.warning

import dev.denza.apps.feature.vehicle.JournalFile
import java.io.File

/** What happened, as the journal keeps it on disk: the tag is the file's word for it. */
internal enum class WarningEventKind(val tag: String) {
    /** The process started, with the recorder on. */
    START("start"),

    /** The car answered after [START] or [LOST]; the event's code is what its slot held then. */
    LINK("link"),

    /** The slot changed while the car was answering. `0` is the warning going away. */
    CODE("code"),

    /** The car stopped answering at the event's time, or nobody asked it for a while. */
    LOST("lost"),
    ON("on"),
    OFF("off"),
    CLEARED("cleared"),
    ;

    companion object {
        fun of(tag: String): WarningEventKind? = entries.firstOrNull { it.tag == tag }
    }
}

internal data class WarningEvent(val atMs: Long, val kind: WarningEventKind, val code: Int? = null)

/** One code over the whole record: kept after the events that showed it have rotated away. */
internal data class WarningSighting(val code: Int, val firstMs: Long, val lastMs: Long, val count: Int)

internal data class WarningLogState(
    val enabled: Boolean = true,
    /** Where the record begins: its first event since the last clear. */
    val sinceMs: Long? = null,
    /** Whether the car answers: null until its first answer, or until silence has lasted. */
    val linked: Boolean? = null,
    /** When the car last stopped answering, while it has not answered since. */
    val lostAtMs: Long? = null,
    /** The newest events, newest first, at most [WarningJournal.VIEW]. */
    val events: List<WarningEvent> = emptyList(),
    /** Every code seen, the most recent sighting first. */
    val sightings: List<WarningSighting> = emptyList(),
)

/**
 * The cluster's warning slot, written down for as long as the app runs.
 *
 * The slot holds one id at a time and most of the time holds a door or nothing, so the question
 * this answers - did `0x1DD` ever arrive - is a question about a rare event over weeks. Two records
 * serve it. The events are a ring of two files of [HALF_BYTES] each: every change of the slot,
 * every start, and every time the car stopped or started answering, so an absence can be told from
 * a silence. The sightings are one small file, a line a code - first seen, last seen, how often -
 * which outlives the ring, so a code from a month ago is still on the page when its context is not.
 *
 * Nothing is written for a reading that did not change, so the disk sees a line when something
 * happens and never on the sweep's cadence. A line cut by a power loss is skipped when read back.
 *
 * The clocks are the caller's. Wall time is what is written and shown; elapsed time decides what a
 * silence is, because it keeps running while the head unit sleeps and the wall clock may be set
 * under it at boot.
 */
internal class WarningJournal(
    private val dir: File,
    private val halfBytes: Long = HALF_BYTES,
    private val onFailure: (String) -> Unit = {},
) {
    private val current = File(dir, "events.log")
    private val previous = File(dir, "events.old.log")
    private val summary = File(dir, "summary.tsv")
    private val paused = File(dir, "paused")

    private var enabled = true
    private var sinceMs: Long? = null
    private val recent = ArrayDeque<WarningEvent>()
    private val sightings = LinkedHashMap<Int, WarningSighting>()

    private var linked: Boolean? = null
    private var lostAtMs: Long? = null
    /** The slot as last read, or null when nothing has been read since the last silence. */
    private var slot: Int? = null
    private var lastAskedElapsed = NEVER
    private var lastAnswerWall = 0L
    private var missingSinceElapsed = NEVER
    private var missingSinceWall = 0L

    /** Read what the disk holds, and say that the process has started. */
    @Synchronized
    fun open(wallMs: Long): WarningLogState {
        dir.mkdirs()
        enabled = !paused.exists()
        // A line the power cut in half is skipped on reading; the next one must not be glued to it.
        if (endsMidLine(current)) JournalFile.append(current, "\n")
        readSummary()
        readEvents(VIEW).forEach(recent::addLast)
        if (sinceMs == null) sinceMs = recent.firstOrNull()?.atMs
        if (enabled) record(WarningEvent(wallMs, WarningEventKind.START))
        return state()
    }

    /**
     * One sweep's reading of the slot: the code, or null when the car did not answer it.
     *
     * A car that answered is linked at once. One that did not is lost only after [LOSS_MS] of not
     * answering, stamped from its first silence, so a single slow sweep is not an event. A gap of
     * [GAP_MS] between two calls is the process not having asked - the head unit asleep, the
     * process frozen - and is a loss from the last answer, because nobody saw what the slot did.
     */
    @Synchronized
    fun sample(code: Int?, wallMs: Long, elapsedMs: Long): Boolean {
        if (!enabled) return false
        var changed = false
        if (linked == true && lastAskedElapsed != NEVER && elapsedMs - lastAskedElapsed > GAP_MS) {
            lose(lastAnswerWall)
            changed = true
        }
        lastAskedElapsed = elapsedMs
        if (code == null) {
            if (linked == false) return changed
            if (missingSinceElapsed == NEVER) {
                missingSinceElapsed = elapsedMs
                missingSinceWall = wallMs
            }
            if (elapsedMs - missingSinceElapsed >= LOSS_MS) {
                lose(missingSinceWall)
                changed = true
            }
            return changed
        }
        missingSinceElapsed = NEVER
        lastAnswerWall = wallMs
        val kind = when {
            linked != true -> WarningEventKind.LINK
            code != slot -> WarningEventKind.CODE
            else -> return changed
        }
        linked = true
        lostAtMs = null
        slot = code
        record(WarningEvent(wallMs, kind, code))
        if (code != 0) sight(code, wallMs)
        return true
    }

    @Synchronized
    fun setEnabled(on: Boolean, wallMs: Long) {
        if (on == enabled) return
        if (on) {
            paused.delete()
            enabled = true
            forgetLink()
            record(WarningEvent(wallMs, WarningEventKind.ON))
        } else {
            record(WarningEvent(wallMs, WarningEventKind.OFF))
            enabled = false
            forgetLink()
            if (!runCatching { paused.createNewFile() || paused.exists() }.getOrDefault(false)) {
                onFailure("не записан выключатель")
            }
        }
    }

    /** Everything goes; the record starts again from this moment and the slot's next answer. */
    @Synchronized
    fun clear(wallMs: Long) {
        current.delete()
        previous.delete()
        summary.delete()
        recent.clear()
        sightings.clear()
        sinceMs = null
        forgetLink()
        record(WarningEvent(wallMs, WarningEventKind.CLEARED))
    }

    @Synchronized
    fun state(): WarningLogState = WarningLogState(
        enabled = enabled,
        sinceMs = sinceMs,
        linked = linked,
        lostAtMs = lostAtMs,
        events = recent.reversed(),
        sightings = sightings.values.sortedByDescending { it.lastMs },
    )

    /** Every event both files still hold, oldest first: what an export writes out. */
    @Synchronized
    fun allEvents(): List<WarningEvent> = readEvents(Int.MAX_VALUE)

    val isEnabled: Boolean @Synchronized get() = enabled

    private fun lose(atMs: Long) {
        linked = false
        lostAtMs = atMs
        slot = null
        missingSinceElapsed = NEVER
        record(WarningEvent(atMs, WarningEventKind.LOST))
    }

    /** After a switch or a clear the next answer is a fresh look at the slot, not a change of it. */
    private fun forgetLink() {
        linked = null
        lostAtMs = null
        slot = null
        lastAskedElapsed = NEVER
        missingSinceElapsed = NEVER
    }

    private fun record(event: WarningEvent) {
        recent.addLast(event)
        while (recent.size > VIEW) recent.removeFirst()
        if (current.length() >= halfBytes) {
            previous.delete()
            if (!current.renameTo(previous)) onFailure("журнал не перенесён")
        }
        if (!JournalFile.append(current, line(event))) onFailure("событие не записано")
        if (sinceMs == null) {
            sinceMs = event.atMs
            writeSummary()
        }
    }

    private fun sight(code: Int, atMs: Long) {
        val seen = sightings[code]
        sightings[code] = if (seen == null) {
            WarningSighting(code, atMs, atMs, 1)
        } else {
            seen.copy(lastMs = atMs, count = seen.count + 1)
        }
        writeSummary()
    }

    private fun writeSummary() {
        val text = buildString {
            append(SINCE).append('\t').append(sinceMs ?: return).append('\n')
            sightings.values.forEach { s ->
                append(hex(s.code)).append('\t').append(s.firstMs).append('\t')
                    .append(s.lastMs).append('\t').append(s.count).append('\n')
            }
        }
        if (!JournalFile.replace(summary, text)) onFailure("сводка не записана")
    }

    private fun readSummary() {
        if (!summary.exists()) return
        runCatching { summary.readLines() }.getOrDefault(emptyList()).forEach { raw ->
            val parts = raw.split('\t')
            if (parts.size == 2 && parts[0] == SINCE) {
                sinceMs = parts[1].toLongOrNull()
                return@forEach
            }
            if (parts.size != 4) return@forEach
            val code = parts[0].toIntOrNull(16) ?: return@forEach
            val first = parts[1].toLongOrNull() ?: return@forEach
            val last = parts[2].toLongOrNull() ?: return@forEach
            val count = parts[3].toIntOrNull() ?: return@forEach
            sightings[code] = WarningSighting(code, first, last, count)
        }
    }

    private fun endsMidLine(file: File): Boolean = runCatching {
        if (file.length() == 0L) return false
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - 1)
            raf.read() != '\n'.code
        }
    }.getOrDefault(false)

    /** The last [limit] readable events of both files, oldest first. */
    private fun readEvents(limit: Int): List<WarningEvent> {
        val tail = ArrayDeque<WarningEvent>()
        listOf(previous, current).forEach { file ->
            if (!file.exists()) return@forEach
            runCatching {
                file.forEachLine { raw ->
                    parse(raw)?.let {
                        tail.addLast(it)
                        if (tail.size > limit) tail.removeFirst()
                    }
                }
            }
        }
        return tail.toList()
    }

    companion object {
        /** Events the page keeps in memory and shows. */
        const val VIEW = 200

        /** Each of the ring's two files: four megabytes together, years of a slot that changes. */
        const val HALF_BYTES = 2L * 1024 * 1024

        /** How long the car may not answer before that is an event: two hub timeouts and more. */
        const val LOSS_MS = 10_000L

        /** A wait between two calls that no sweep cadence or backoff explains while linked. */
        const val GAP_MS = 30_000L

        private const val NEVER = Long.MIN_VALUE
        private const val SINCE = "since"

        fun hex(code: Int): String = Integer.toHexString(code).uppercase()

        fun line(event: WarningEvent): String =
            "${event.atMs}\t${event.kind.tag}\t${event.code?.let(::hex).orEmpty()}\n"

        fun parse(raw: String): WarningEvent? {
            val parts = raw.split('\t')
            if (parts.size != 3) return null
            val at = parts[0].toLongOrNull() ?: return null
            val kind = WarningEventKind.of(parts[1]) ?: return null
            val code = if (parts[2].isEmpty()) null else parts[2].toIntOrNull(16) ?: return null
            if ((kind == WarningEventKind.LINK || kind == WarningEventKind.CODE) != (code != null)) return null
            return WarningEvent(at, kind, code)
        }
    }
}
