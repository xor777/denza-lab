package dev.denza.apps.feature.vehicle

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The sweep the hub already performs, written down, for the drives nobody can attach a host to.
 *
 * `tools/vehicle_log.py` records these same ids a row a second from a laptop over ADB, and the
 * three open rows of `docs/energy-display-contract.md` §8 - what generation means in motion, the
 * sign of pack power under acceleration, whether the rpm id says anything with the engine off -
 * are all waiting on one recorded drive. A laptop cannot make that drive: the car is on the road,
 * the phone hotspot drops, and the owner is not going to hold a terminal open at speed. The car
 * can. It is already asking the questions once a second for the ledger
 * (`VehicleWatcher.LEDGER`, contract §2.7); this writes the answers to a file.
 *
 * ### Why this is not a probe
 *
 * CLAUDE.md sends new "poke the car" code to `tools/` or to a `…​.probe` package, and this is in
 * the product. It is not that kind of code: it opens no channel, issues no transact, names no
 * feature id and adds nothing to the batch. It is handed the map the hub was going to publish to
 * the panel anyway and turns it into a line of text. The car cannot tell whether it is on. The
 * precedent is `MediaKeyDiagnostics`, a bounded record inside the product for a car nobody can
 * attach a debugger to; the difference from a probe is that a probe asks the car something new,
 * and this asks nothing.
 *
 * And it is off. There is no setting, no tile and no broadcast - the switch is a file the host
 * creates before a drive and deletes after it:
 *
 * ```text
 * adb shell run-as dev.denza.apps mkdir -p files/vehicle-capture
 * adb shell run-as dev.denza.apps touch files/vehicle-capture/ENABLED
 * ```
 *
 * With the marker absent nothing is written and the directory is not even created, so the cost of
 * shipping this to a car that never uses it is one `File.exists()` a minute.
 *
 * ### The file
 *
 * `vehicle-<yyyyMMdd-HHmmss>.csv`, the host recorder's own name, with the header as its first
 * line and one row per second of elapsed realtime. The columns the two recorders share carry the
 * host's names exactly ([VehicleCaptureColumns]), so `VehicleLogReplayTest` reads a pulled file
 * without knowing which recorder wrote it. The `raw_` half of the host's file is not here: the
 * host keeps the parcel word so a decoding argument can be settled afterwards, and the car has
 * already decided - `AutoserviceShell.decode` ran before this saw anything, and what this writes
 * is exactly what the panel was drawing.
 *
 * The values are the ids' own numbers, not the panel's. `VehicleConvention.load` may one day flip
 * the sign of pack power, and a recording written through it would flip with it - which is
 * precisely the claim the recording exists to settle. The host recorder writes the id's value and
 * the replay test applies the convention itself.
 *
 * ### The bounds
 *
 * A row is about 140 bytes, so 2 MB is some four hours of driving and the four files are most of
 * a day. Past that the oldest goes: this is a flight recorder, not an archive, and a car whose
 * marker was left in place must not fill its own data partition.
 *
 * Every row is `fsync`ed before the call returns, for the reason both journals are
 * ([JournalFile]): this car cuts its own power without warning, and a row in the page cache when
 * the ignition goes is a row that was never written. A row a second is one `fsync` a second,
 * which is what the consumption journal already pays.
 *
 * ### Threading, and why it never throws
 *
 * Called from the poll loop's thread and from nowhere else, like [ConsumptionJournal], so nothing
 * here is synchronised and [SimpleDateFormat] is safe to keep. Every disk path goes through
 * [JournalFile], which returns a flag rather than throwing; a failure reports through [onFailed]
 * once and switches the capture off until the next marker check. The loop must not lose a sweep
 * of the ledger because a recording nobody asked for could not write its line.
 *
 * Free of `android.*` on purpose - the clocks are handed in - so the whole of it is a plain JVM
 * test.
 */
internal class VehicleCapture(
    private val directory: File,
    /** Elapsed realtime in milliseconds: the cadence, and the `mono_s` column. */
    private val elapsedMillis: () -> Long,
    /** Wall clock in milliseconds: the file's name and the `time` column. */
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val onFailed: (String) -> Unit = {},
) {

    private val stamp = SimpleDateFormat(NAME_PATTERN, Locale.US)
    private val moment = SimpleDateFormat(TIME_PATTERN, Locale.US)

    private var file: File? = null
    private var enabled = false
    private var checked = false
    private var checkedAt = 0L
    private var rowAt = 0L
    private var firstRow = true

    /**
     * Look at the marker on the next sample, whatever the interval says.
     *
     * Called when the poll loop starts, which is the one moment the minute between checks would
     * be a lie: a process that has just come up has never looked.
     */
    fun recheck() {
        checked = false
    }

    /**
     * One sweep, as the hub was about to publish it.
     *
     * [values] is the snapshot's own map - this sweep's hot answers over the last cold sweep's
     * rebuilt ones - so a signal absent from it is a signal that did not answer or was not asked,
     * and an absent signal is an empty cell. It is never a zero.
     */
    fun sample(values: Map<VehicleSignal, Double>) {
        val now = elapsedMillis()
        if (!checked || now - checkedAt >= MARKER_MS) {
            checked = true
            checkedAt = now
            refresh()
        }
        if (!enabled) return
        // A hundred-millisecond cadence is ten of these a second and one row of them. The first
        // row of a file does not wait for the floor: it is the moment the capture was enabled.
        if (!firstRow && now - rowAt < ROW_MS) return
        val target = file ?: return
        firstRow = false
        rowAt = now
        if (!JournalFile.append(target, row(now, values))) {
            fail("строка не записалась")
            return
        }
        if (target.length() > MAX_BYTES) rotate()
    }

    /** Let go of the current file; the next enable opens a new one. */
    fun close() {
        file = null
        enabled = false
        checked = false
    }

    /**
     * The marker, and what it means since the last look.
     *
     * Present and not already recording - a fresh enable, a process that has just started, or a
     * recovery from a failure - opens a new file. Absent closes whatever is open. A file per
     * enable rather than a file appended to across them: a gap in a recording is a thing the
     * reader has to notice, and a new name is the loudest way to say it.
     */
    private fun refresh() {
        val present = runCatching { File(directory, MARKER).exists() }.getOrDefault(false)
        if (!present) {
            file = null
            enabled = false
            return
        }
        // Already recording, or recording again after a failure switched it off - which is the one
        // other way [enabled] is false with the marker in place.
        if (enabled) return
        // The first row of an enable is the moment it was enabled and does not wait for the
        // floor. A rotation is not an enable: it happens between two rows of one recording, and
        // resetting the floor there would let a second row through inside the same second.
        firstRow = true
        open()
    }

    private fun open() {
        val opened = unique() ?: run {
            fail("файл не открылся")
            return
        }
        if (!JournalFile.append(opened, VehicleCaptureColumns.HEADER.joinToString(",", postfix = "\n"))) {
            fail("заголовок не записался")
            return
        }
        file = opened
        enabled = true
        prune(opened)
    }

    /**
     * A name nothing else in the directory has.
     *
     * Two files of the same second happen when a rotation lands inside one - four hours of rows
     * apart in the ordinary case, and never at all on a car, but a test drives it in milliseconds
     * and a capture that quietly appended its second header to somebody else's file would be
     * worse than a suffix.
     */
    private fun unique(): File? {
        val base = "vehicle-${stamp.format(Date(wallMillis()))}"
        repeat(MAX_SAME_SECOND) { index ->
            val name = if (index == 0) "$base.csv" else "$base-${index + 1}.csv"
            val candidate = File(directory, name)
            if (!runCatching { candidate.exists() }.getOrDefault(true)) return candidate
        }
        return null
    }

    /** Past the bound, into a new file, and the oldest of them goes. */
    private fun rotate() {
        file = null
        open()
    }

    /**
     * Keep the newest [MAX_FILES], [current] among them.
     *
     * Oldest by the disk's own clock, with the name as the tie-break, because two files of one
     * second sort by name the way the seconds do. The current file is never a candidate: it is
     * the one the next row is going into.
     */
    private fun prune(current: File) {
        val files = runCatching {
            directory.listFiles { candidate ->
                candidate.isFile && candidate.name.startsWith(PREFIX) && candidate.name.endsWith(SUFFIX)
            }
        }.getOrNull() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedWith(compareBy({ it.lastModified() }, { it.name }))
            .filter { it != current }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun row(now: Long, values: Map<VehicleSignal, Double>): String {
        val text = StringBuilder(ROW_BYTES)
        text.append(moment.format(Date(wallMillis())))
        text.append(',')
        text.append(String.format(Locale.US, "%.3f", now / 1000.0))
        VehicleSignal.entries.forEach { signal ->
            text.append(',')
            values[signal]?.let { text.append(VehicleCaptureColumns.format(it)) }
        }
        text.append('\n')
        return text.toString()
    }

    /** Off until the next marker check, and said once. */
    private fun fail(why: String) {
        file = null
        enabled = false
        onFailed(why)
    }

    companion object {
        /** Under the app's own files, which `run-as` can read without a single permission. */
        const val DIRECTORY = "vehicle-capture"

        /** The switch. Nothing creates it but the host. */
        const val MARKER = "ENABLED"

        /** One row per second of elapsed realtime, whatever the sweep's cadence is. */
        const val ROW_MS = 1_000L

        /** How often the marker is looked at while the loop runs. */
        const val MARKER_MS = 60_000L

        /** Two megabytes, which is about four hours of rows. */
        const val MAX_BYTES = 2L * 1024L * 1024L

        /** And four of those, which is most of a day. */
        const val MAX_FILES = 4

        private const val PREFIX = "vehicle-"
        private const val SUFFIX = ".csv"

        private const val NAME_PATTERN = "yyyyMMdd-HHmmss"

        /** Local ISO-8601 with milliseconds, which is what `datetime.isoformat` writes. */
        private const val TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS"

        /** A guess at a row, for the builder. Twenty columns and two clocks. */
        private const val ROW_BYTES = 160

        /** How many files of one second the naming will put up with before giving up. */
        private const val MAX_SAME_SECOND = 64

        /** The capture for an app-private directory, named the one way. */
        fun of(
            filesDir: File,
            elapsedMillis: () -> Long,
            onFailed: (String) -> Unit = {},
        ): VehicleCapture = VehicleCapture(File(filesDir, DIRECTORY), elapsedMillis, onFailed = onFailed)
    }
}

/**
 * What each column is called, which is the host recorder's name wherever there is one.
 *
 * `tools/vehicle_log.py` and this class record overlapping sets of the same ids, and
 * `VehicleLogReplayTest` reads either file by looking its columns up by name. The moment the two
 * recorders disagree about a name, a file from one of them replays as a drive with no power in
 * it - a silence, not a failure. So the eleven shared names are written here once and
 * `VehicleCaptureTest` reads `tools/vehicle_log.py` and asserts each of them is still a `Signal`
 * in it, the way a board and its renderer are held together.
 *
 * Everything else is the enum's own name, lowercased. Those columns are the car's alone: the host
 * recorder has no temperature in it.
 */
internal object VehicleCaptureColumns {

    /** The clock columns, both of them the host's. */
    const val TIME = "time"
    const val MONO = "mono_s"

    /**
     * The ids both recorders write, and the one spelling of each.
     *
     * Read `tools/vehicle_log.py` beside this: every name here is a `Signal('<name>', …)` there,
     * on the same device and feature id.
     */
    val SHARED: Map<VehicleSignal, String> = mapOf(
        VehicleSignal.POWER_KW to "power_kw",
        VehicleSignal.GENERATION_KW to "generation_kw",
        VehicleSignal.GENERATION_STATE to "generation_state",
        VehicleSignal.ENGINE_RUNNING to "engine_running",
        VehicleSignal.ENGINE_RPM to "engine_rpm",
        VehicleSignal.VEHICLE_SPEED to "speed_kmh",
        VehicleSignal.ODOMETER_KM to "odometer_km",
        VehicleSignal.PACK_VOLT to "pack_volt",
        VehicleSignal.GEARBOX_PARK to "park",
        VehicleSignal.CHARGE_GUN to "charge_gun",
        VehicleSignal.CHARGE_KW to "charge_kw",
    )

    fun of(signal: VehicleSignal): String = SHARED[signal] ?: signal.name.lowercase(Locale.US)

    /** The two clocks and then every signal, in the order the enum declares them. */
    val HEADER: List<String> = buildList {
        add(TIME)
        add(MONO)
        VehicleSignal.entries.forEach { add(of(it)) }
    }

    /**
     * A number a `toDoubleOrNull` reads back, and nothing a reader has to undo.
     *
     * `%.3f` rather than `toString()`: a double's own text reaches for an exponent on a small
     * enough charge current, and `1.0E-4` in a CSV column is a cell half the readers of this file
     * would get wrong. Three decimals is what the host recorder keeps for its float ids and more
     * than the integer ids can carry, and the trailing zeros come off because `0` is easier to
     * read past than `0.000` in a column twenty wide.
     */
    fun format(value: Double): String {
        if (!value.isFinite()) return ""
        val text = String.format(Locale.US, "%.3f", value)
        return text.trimEnd('0').trimEnd('.')
    }
}
