package dev.denza.apps.feature.vehicle

import java.io.File
import java.util.Locale

/**
 * The consumption bars, on disk, so a window of thirty kilometres means
 * something after the app has been restarted.
 *
 * ### Why lines and not JSON
 *
 * This file is written while the car is moving - a bar closes every hundred
 * metres, which at highway speed is every three or four seconds - and the power
 * goes away without warning when the car is switched off. A JSON document has to
 * be rewritten whole to add one entry, so a write cut in half by the ignition
 * takes the entire history with it. A line appended to the end costs the tail and
 * nothing else, and a half-written last line is recognisable and skippable.
 *
 * So: one line per bar, `odometer,value`, appended, never rewritten except to
 * trim. `%.1f` on the odometer is exactly the resolution the vehicle reports it
 * at; `%.3f` on the value is far finer than anything drawn from it.
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
) {

    private var lines = 0

    /**
     * Everything the file holds, oldest first.
     *
     * A single unparseable last line is a write the ignition interrupted and is
     * dropped in silence. Anything else means the file is not what this class
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
        val comma = row.indexOf(',')
        if (comma <= 0) return null
        val odometer = row.substring(0, comma).toDoubleOrNull() ?: return null
        val value = row.substring(comma + 1).toDoubleOrNull() ?: return null
        if (!odometer.isFinite() || !value.isFinite()) return null
        if (odometer < 0.0 || odometer > MAX_ODOMETER_KM) return null
        return ConsumptionSample(odometer, value)
    }

    /**
     * Append a batch and make it durable.
     *
     * The batch is what bounds the loss: everything written before the last sync
     * survives the ignition, so the size of a batch is the size of the hole a
     * sudden power cut leaves.
     */
    fun append(samples: List<ConsumptionSample>) {
        if (samples.isEmpty()) return
        val text = StringBuilder(samples.size * 20)
        samples.forEach {
            text.append(String.format(Locale.US, "%.1f,%.3f\n", it.odometerKm, it.value))
        }
        if (!JournalFile.append(file, text.toString())) {
            wipe("запись не удалась")
            return
        }
        lines += samples.size
        if (lines > MAX_LINES + TRIM_SLACK) trim()
    }

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
        val text = StringBuilder(kept.size * 20)
        kept.forEach {
            text.append(String.format(Locale.US, "%.1f,%.3f\n", it.odometerKm, it.value))
        }
        if (JournalFile.replace(file, text.toString())) {
            lines = kept.size
        } else {
            wipe("подрезка не удалась")
        }
    }

    /** Forget everything. Cheap, and the answer to every kind of wrong. */
    fun clear() {
        file.delete()
        lines = 0
    }

    private fun wipe(why: String, cause: Throwable? = null): List<ConsumptionSample> {
        clear()
        onWiped(if (cause == null) why else "$why: ${cause.javaClass.simpleName}")
        return emptyList()
    }

    companion object {
        /** The journal for an app-private directory, named the one way. */
        fun of(directory: File, onWiped: (String) -> Unit = {}): ConsumptionJournal =
            ConsumptionJournal(File(directory, FILE_NAME), onWiped)

        private const val FILE_NAME = "consumption.log"

        /** The longest window, in bars. Anything older is not the last thirty km. */
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

        /** A line is about twenty bytes; anything past this is not our file. */
        private const val MAX_BYTES = (MAX_LINES + TRIM_SLACK) * 64

        /** No odometer on this car reaches this; a bigger number is a bad parse. */
        private const val MAX_ODOMETER_KM = OdometerGate.MAX_ODOMETER_KM
    }
}
