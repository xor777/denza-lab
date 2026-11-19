package dev.denza.apps.feature.vehicle

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The consumption buckets, on disk, so a window of thirty kilometres means
 * something after the app has been restarted.
 *
 * ### Why lines and not JSON
 *
 * This file is written while the car is moving - a bucket closes every hundred
 * metres, which at highway speed is every three or four seconds - and the power
 * goes away without warning when the car is switched off. A JSON document has to
 * be rewritten whole to add one entry, so a write cut in half by the ignition
 * takes the entire history with it. A line appended to the end costs the tail and
 * nothing else, and a half-written last line is recognisable and skippable.
 *
 * So: one line per bucket, `odometer,kwh,km,knownKm`, appended, never rewritten
 * except to trim. `%.1f` on the odometer is exactly the resolution the vehicle
 * reports it at; the three quantities behind it are written far finer than
 * anything drawn from them.
 *
 * ### The old two-column format is refused rather than upgraded
 *
 * Until 2026-09-07 a line was `odometer,value` and a bucket was one number. A
 * bucket carries its road and its known road now
 * (`docs/energy-display-contract.md` §2.6), and those two cannot be invented from
 * a file that never held them: guessing one tick of road per line would put an
 * odometer step nobody watched under a figure as if it had been measured. So a
 * short line is a line this class did not write, which ends where every other
 * kind of wrong here ends - the file goes and the drive up to now is the whole
 * cost.
 *
 * ### What it does when the file is wrong
 *
 * It deletes it and starts again. That is the owner's call and it is the right
 * one: this is a rolling window of the last thirty kilometres, not a record
 * anybody keeps, and the most a wipe costs is the drive up to now. Every failure
 * mode ends there - unreadable, unparseable, an odometer ahead of the car's own,
 * a write that throws - because a journal you cannot trust is worth less than no
 * journal at all.
 *
 * ### Threading
 *
 * Not thread-safe, and does not need to be: every call comes from the telemetry
 * hub's own polling loop, which runs on one IO dispatcher. The sync on each flush
 * is what bounds the loss to the batch, and a batch is a kilometre of road, so
 * the cost is one fsync every half minute of driving.
 *
 * ### Why it does not log
 *
 * [onWiped] reports instead, because `android.util.Log` in here would put the
 * whole class behind an instrumented test for the sake of one line. The same rule
 * [ConsumptionLog] follows: the file handling is plain Kotlin and Java IO, and
 * what to do about a wipe is the caller's business.
 */
internal class ConsumptionJournal(
    private val file: File,
    private val onWiped: (String) -> Unit = {},
    /** A write that did not reach the disk; said once per run of failures, not once per bucket. */
    private val onWriteFailed: (String) -> Unit = {},
) {

    private var lines = 0

    /** Whether the last append failed, so a full disk is reported once rather than every bucket. */
    private var failing = false

    /**
     * Everything the file holds, oldest first.
     *
     * A single unparseable last line is a write the ignition interrupted and is
     * dropped in silence; the next [append] cuts it off the file before it writes
     * ([endTornTail]). Anything else means the file is not what this class
     * wrote, and the file goes.
     */
    fun load(): List<ConsumptionSample> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse {
            return wipe("не читается", it)
        }
        if (text.length > MAX_BYTES) return wipe("слишком большой (${text.length} Б)")

        val rows = text.split('\n').filter { it.isNotEmpty() }
        val samples = ArrayList<ConsumptionSample>(rows.size)
        rows.forEachIndexed { index, row ->
            val sample = parse(row)
            if (sample != null) {
                samples.add(sample)
            } else if (index != rows.lastIndex) {
                return wipe("строка ${index + 1} из ${rows.size} не разобралась")
            }
        }
        // A torn tail is one bad line after good ones. A file where nothing
        // parsed is not this class's file at all, whatever its length.
        if (samples.isEmpty() && rows.isNotEmpty()) return wipe("ни одна из ${rows.size} строк не разобралась")
        lines = samples.size
        return samples
    }

    private fun parse(row: String): ConsumptionSample? {
        val fields = row.split(',')
        if (fields.size != COLUMNS) return null
        val odometer = fields[0].toDoubleOrNull() ?: return null
        val kwh = fields[1].toDoubleOrNull() ?: return null
        val km = fields[2].toDoubleOrNull() ?: return null
        val knownKm = fields[3].toDoubleOrNull() ?: return null
        if (!odometer.isFinite() || !kwh.isFinite() || !km.isFinite() || !knownKm.isFinite()) {
            return null
        }
        if (odometer < 0.0 || odometer > MAX_ODOMETER_KM) return null
        // Road is a distance and the known part of it is inside that distance. A file that says
        // otherwise is not describing a bucket this log ever closed.
        if (km <= 0.0 || km > MAX_BUCKET_KM) return null
        if (knownKm < 0.0 || knownKm > km + OdometerGate.KM_EPSILON) return null
        return ConsumptionSample(odometer, kwh, km, knownKm)
    }

    /**
     * Append a batch and make it durable.
     *
     * The batch is what bounds the loss: everything written before the last sync
     * survives the ignition, so the size of a batch is the size of the hole a
     * sudden power cut leaves.
     *
     * A write that fails loses this batch and nothing else. Until 2026-11-19 it
     * wiped the file, so one failed write - a full disk, a sync refused - took
     * thirty kilometres of valid lines with it. Whatever half of a line it may
     * have left is cut off by the next append ([endTornTail]), as a cut by the
     * ignition is; the failure is reported once, until a write succeeds again.
     */
    fun append(samples: List<ConsumptionSample>) {
        if (samples.isEmpty()) return
        if (!endTornTail()) {
            failed("обрывок строки не убрать")
            return
        }
        val text = StringBuilder(samples.size * LINE_BYTES)
        samples.forEach { text.append(line(it)) }
        if (!JournalFile.append(file, text.toString())) {
            failed("запись не удалась")
            return
        }
        failing = false
        lines += samples.size
        if (lines > MAX_LINES + TRIM_SLACK) trim()
    }

    /**
     * Make the file end with a whole line before anything is added to it.
     *
     * [load] forgives one torn last line because it is the last. An append made after it used to
     * join the next line onto it - `1000.4` and `1000.5,0.02…` became one line that is not a
     * bucket - and the append after that made the joined line an interior one, which [load] and
     * [trim] read as a file this class did not write: the whole journal went, up to thirty
     * kilometres, for a cut that had cost one line. The head unit loses power that way every time
     * the car is switched off, so this is the ordinary path, not the rare one.
     *
     * So the tail after the last newline is settled first. A tail that is a whole bucket short only
     * of its newline - the cut came between the two - gets the newline, because [load] has already
     * handed it to the log as a reading; anything else is cut off, and the valid lines before it
     * stay as they are. Read from the end, a few bytes at a time: a line is under forty bytes and
     * this runs before every append.
     *
     * @return whether the file now ends with a whole line (or is empty or absent)
     */
    private fun endTornTail(): Boolean = runCatching {
        if (!file.exists()) return true
        // Read-only for the check every append makes; writable only when there is a tail to settle.
        val whole = RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            length == 0L || run {
                raf.seek(length - 1)
                raf.read() == NEWLINE
            }
        }
        if (whole) return true
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            if (length == 0L) return true
            raf.seek(length - 1)
            if (raf.read() == NEWLINE) return true
            // Back to the newline before the tail, or to the start of the file.
            var start = length
            val buffer = ByteArray(TAIL_SCAN_BYTES)
            search@ while (start > 0) {
                val from = maxOf(0L, start - buffer.size)
                val count = (start - from).toInt()
                raf.seek(from)
                raf.readFully(buffer, 0, count)
                for (index in count - 1 downTo 0) {
                    if (buffer[index].toInt() == NEWLINE) {
                        start = from + index + 1
                        break@search
                    }
                }
                start = from
            }
            val whole = length - start <= LINE_BYTES * 2 && run {
                val tail = ByteArray((length - start).toInt())
                raf.seek(start)
                raf.readFully(tail)
                parse(String(tail)) != null
            }
            if (whole) {
                raf.seek(length)
                raf.write(NEWLINE)
            } else {
                raf.setLength(start)
            }
            raf.fd.sync()
        }
        true
    }.getOrDefault(false)

    /**
     * Rewrite the file with only the newest [MAX_LINES] entries.
     *
     * Through a temporary file and a rename, so an interrupted trim leaves the
     * old journal intact rather than a half-copied one. This runs about once
     * every fifty kilometres, which is why it is allowed to be the expensive
     * path.
     */
    private fun trim() {
        val kept = load().takeLast(MAX_LINES)
        if (kept.isEmpty()) {
            wipe("после подрезки ничего не осталось")
            return
        }
        val text = StringBuilder(kept.size * LINE_BYTES)
        kept.forEach { text.append(line(it)) }
        if (JournalFile.replace(file, text.toString())) {
            lines = kept.size
        } else {
            wipe("подрезка не удалась")
        }
    }

    private fun line(sample: ConsumptionSample): String = String.format(
        Locale.US,
        "%.1f,%.5f,%.4f,%.4f\n",
        sample.odometerKm,
        sample.kwh,
        sample.km,
        sample.knownKm,
    )

    /** Forget everything. Cheap, and the answer to every kind of wrong. */
    fun clear() {
        file.delete()
        lines = 0
    }

    private fun failed(why: String) {
        if (!failing) onWriteFailed(why)
        failing = true
    }

    private fun wipe(why: String, cause: Throwable? = null): List<ConsumptionSample> {
        clear()
        onWiped(if (cause == null) why else "$why: ${cause.javaClass.simpleName}")
        return emptyList()
    }

    companion object {
        /** The journal for an app-private directory, named the one way. */
        fun of(
            directory: File,
            onWiped: (String) -> Unit = {},
            onWriteFailed: (String) -> Unit = {},
        ): ConsumptionJournal = ConsumptionJournal(File(directory, FILE_NAME), onWiped, onWriteFailed)

        private const val FILE_NAME = "consumption.log"

        /** Four columns, and a line with any other count is not this class's line. */
        const val COLUMNS = 4

        /**
         * No bucket carries more road than a re-anchor allows plus the tick that closed it.
         *
         * [OdometerGate.MAX_JUMP_KM] is the longest step that is still road, and a bucket that was
         * already part full when that step arrived closes carrying both. The bound is stated as
         * the sum rather than as the jump alone because that is what the accumulation can reach -
         * exactly, on a bucket one epsilon short of closing.
         */
        private const val MAX_BUCKET_KM = OdometerGate.MAX_JUMP_KM + ConsumptionLog.DEFAULT_BUCKET_KM

        /** The longest window, in buckets. Anything older is not the last thirty km. */
        const val MAX_LINES = ConsumptionLog.DEFAULT_CAPACITY

        /**
         * Room to append past the cap before paying for a rewrite.
         *
         * So the file is bounded by [MAX_LINES] + this, not by [MAX_LINES]: the
         * trim is the expensive path and running it on every append would mean
         * rewriting the whole journal every hundred metres. What a window reads
         * is always the newest [MAX_LINES] regardless.
         */
        const val TRIM_SLACK = 200

        /** What one line costs, which is what a builder is sized from. */
        private const val LINE_BYTES = 36

        private const val NEWLINE = '\n'.code

        /** How much of the end [endTornTail] reads at a time. */
        private const val TAIL_SCAN_BYTES = 256

        /** And anything past a generous multiple of that is not our file. */
        private const val MAX_BYTES = (MAX_LINES + TRIM_SLACK) * 64

        /** No odometer on this car reaches this; a bigger number is a bad parse. */
        private const val MAX_ODOMETER_KM = OdometerGate.MAX_ODOMETER_KM
    }
}
