package dev.denza.apps.feature.vehicle

import java.io.File
import java.util.Locale

/**
 * The current trip, on disk, so the shelf still reads the same trip after the app has restarted.
 *
 * ### Why this is not [ConsumptionJournal]
 *
 * They are the same problem with opposite shapes. The consumption journal is a growing list of
 * closed bars and is therefore appended to, because a rewrite cut in half by the ignition would take
 * the whole history. A trip is **one record that keeps changing**, so appending would write a
 * kilobyte a minute of values that are all superseded, and the file would have to be replayed to
 * find the last good line. One record is written whole, through a temporary file and a rename, so an
 * interrupted write leaves the previous record intact rather than half of the new one.
 *
 * ### What it does when the file is wrong
 *
 * It deletes it and starts the trip again, which is [ConsumptionJournal]'s rule for the same reason:
 * this is a figure about the last hour of driving, not a record anybody keeps, and a trip figure
 * that cannot be trusted is worth less than no figure at all. [TripEnergyLedger.restore] applies the
 * second test - whether the odometer says this record is even about the road we are on.
 *
 * ### The record is versioned (2026-11-19)
 *
 * 0.7.1 wrote seven fields, `net,recovered,engine,seconds,km,odometer,armed`, and its third was
 * `∫GENERATION_KW dt`. The same position means the pack's charge while the engine runs now
 * ([TripEnergy]), and a record that does not say which it is cannot be read as either. So the
 * record starts with its version, [VERSION], and carries whether its engine charge is known and
 * whether a trip was opened at all. A seven-field record is read as 0.7.1's: the net, the
 * recuperation, the minutes and the kilometres mean what they meant and are kept, and the engine
 * charge is unknown ([TripEnergy.engineKwhKnown]) until the next trip starts.
 *
 * ### A write that fails leaves the record that was there
 *
 * [JournalFile.replace] keeps the old file when it cannot write the new one - a full disk, a sync
 * that failed. Until 2026-11-19 [save] answered that by deleting the old file, so one failed
 * checkpoint threw away the last good trip, and a restart before the next good one started the
 * trip again. Now it reports the failure and leaves the file; the next checkpoint tries again.
 *
 * ### An unchanged record is not written again
 *
 * Standing in P the trip does not change, and the checkpoint every ten seconds wrote the same bytes
 * and synced them - up to 360 times an hour of a parked charge. [save] keeps the text it last made
 * durable and skips a write of the same text.
 *
 * ### Threading
 *
 * Not thread-safe and does not need to be: every call comes from the telemetry hub's own poll loop,
 * which is one IO dispatcher.
 */
internal class TripJournal(
    private val file: File,
    private val onWiped: (String) -> Unit = {},
) {

    /** The text the file was last known to hold, so an unchanged checkpoint costs nothing. */
    private var written: String? = null

    /** The record on disk, or null when there is none this class can trust. */
    fun load(): TripRecord? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrElse {
            wipe("не читается: ${it.javaClass.simpleName}")
            return null
        }
        if (text.length > MAX_BYTES) {
            wipe("слишком большой (${text.length} Б)")
            return null
        }
        val record = parse(text.trim())
        if (record == null) {
            wipe("строка не разобралась")
        } else {
            written = text
        }
        return record
    }

    private fun parse(row: String): TripRecord? {
        val parts = row.split(',')
        return when {
            parts.size == FIELDS && parts[0] == VERSION -> parseCurrent(parts.drop(1))
            parts.size == LEGACY_FIELDS -> parseLegacy(parts)
            else -> null
        }
    }

    /** `net,recovered,engine,seconds,km,odometer,armed,engineKnown,opened`, after the version. */
    private fun parseCurrent(parts: List<String>): TripRecord? {
        val common = parseCommon(parts.take(LEGACY_FIELDS)) ?: return null
        val engineKnown = flag(parts[LEGACY_FIELDS]) ?: return null
        val opened = flag(parts[LEGACY_FIELDS + 1]) ?: return null
        return common.copy(energy = common.energy.copy(engineKwhKnown = engineKnown, opened = opened))
    }

    /**
     * 0.7.1's seven fields. Its third is `∫GENERATION_KW dt`, which is not this field: the engine
     * charge is unknown for the rest of this trip. A trip was opened if the record says the car has
     * moved since its last P or carries any road.
     */
    private fun parseLegacy(parts: List<String>): TripRecord? {
        val record = parseCommon(parts) ?: return null
        return record.copy(
            energy = record.energy.copy(
                engineKwh = 0.0,
                engineKwhKnown = false,
                opened = !record.armed || record.energy.kilometres > 0.0,
            ),
        )
    }

    private fun parseCommon(parts: List<String>): TripRecord? {
        val numbers = parts.take(LEGACY_FIELDS - 1).map { it.toDoubleOrNull() ?: return null }
        if (numbers.any { !it.isFinite() }) return null
        val armed = flag(parts[LEGACY_FIELDS - 1]) ?: return null
        val seconds = numbers[3]
        val kilometres = numbers[4]
        val odometer = numbers[5]
        if (seconds < 0.0 || kilometres < 0.0) return null
        if (odometer < 0.0 || odometer > MAX_ODOMETER_KM) return null
        return TripRecord(
            energy = TripEnergy(numbers[0], numbers[1], numbers[2], seconds, kilometres),
            odometerKm = odometer,
            armed = armed,
        )
    }

    private fun flag(text: String): Boolean? = when (text) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /**
     * Replace the record. Whole, atomically, or not at all.
     *
     * @return whether the record is on the disk: false when the write failed, and the previous
     *   record - the last good one - is still there
     */
    fun save(record: TripRecord): Boolean {
        val energy = record.energy
        val text = String.format(
            Locale.US,
            "%s,%.4f,%.4f,%.4f,%.1f,%.3f,%.1f,%d,%d,%d\n",
            VERSION,
            energy.netKwh,
            energy.recoveredKwh,
            energy.engineKwh,
            energy.engineSeconds,
            energy.kilometres,
            record.odometerKm,
            if (record.armed) 1 else 0,
            if (energy.engineKwhKnown) 1 else 0,
            if (energy.opened) 1 else 0,
        )
        if (text == written && file.exists()) return true
        if (!JournalFile.replace(file, text)) return false
        written = text
        return true
    }

    /** Forget the trip. Cheap, and the answer to every kind of wrong. */
    fun clear() {
        file.delete()
        written = null
    }

    private fun wipe(why: String) {
        clear()
        onWiped(why)
    }

    companion object {
        /** The journal for an app-private directory, named the one way. */
        fun of(directory: File, onWiped: (String) -> Unit = {}): TripJournal =
            TripJournal(File(directory, FILE_NAME), onWiped)

        private const val FILE_NAME = "trip.log"

        /** The record's first field. 0.7.1's record had none. */
        const val VERSION = "2"

        /** The version, five doubles, a distance and three flags. */
        private const val FIELDS = 10

        /** 0.7.1's: five doubles, a distance and a flag. */
        private const val LEGACY_FIELDS = 7

        /** One record is under a hundred bytes; anything past this is not our file. */
        private const val MAX_BYTES = 512

        /** No odometer on this car reaches this; a bigger number is a bad parse. */
        private const val MAX_ODOMETER_KM = OdometerGate.MAX_ODOMETER_KM
    }
}

/**
 * When the hub makes the trip durable: every [intervalMs], and **at once when the trip ends**.
 *
 * The interval is the size of the hole an ignition cut leaves in the figure, and ten seconds of
 * driving is under a hundredth of a kilowatt-hour. The end of a trip is not a figure, it is a
 * boundary: P arms the ledger ([TripRecord.armed]) and the next movement starts a new trip. A
 * driver who selected P and switched off inside those ten seconds left a record with the boundary
 * missing, and the next drive restored the finished trip as open and was added to it. So the first
 * record that says armed is written on the sweep that made it, whatever the interval says.
 *
 * A failed boundary write is retried by the periodic checkpoint, which writes any record that
 * differs from the one on disk ([TripJournal.save]), and not on every sweep: a full disk under a
 * parked car costs one attempt per interval.
 */
internal class TripCheckpoint(private val intervalMs: Long = INTERVAL_MS) {

    private var savedAt = 0L

    /** Whether the current P has had its checkpoint; the next movement clears it. */
    private var boundaryTried = false

    /** Whether [record] is to be written on the sweep at [nowMillis] (`elapsedRealtime`). */
    fun due(record: TripRecord, nowMillis: Long): Boolean =
        (record.armed && !boundaryTried) || nowMillis - savedAt >= intervalMs

    /** [record] was handed to the journal at [nowMillis], whether or not the write succeeded. */
    fun attempted(record: TripRecord, nowMillis: Long) {
        savedAt = nowMillis
        boundaryTried = record.armed
    }

    companion object {
        /** How often an open trip is made durable. */
        const val INTERVAL_MS = 10_000L
    }
}
