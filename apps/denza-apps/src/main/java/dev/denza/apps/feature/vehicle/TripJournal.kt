package dev.denza.apps.feature.vehicle

import java.io.File
import java.io.FileOutputStream
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
 * ### Threading
 *
 * Not thread-safe and does not need to be: every call comes from the telemetry hub's own poll loop,
 * which is one IO dispatcher.
 */
internal class TripJournal(
    private val file: File,
    private val onWiped: (String) -> Unit = {},
) {

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
        if (record == null) wipe("строка не разобралась")
        return record
    }

    private fun parse(row: String): TripRecord? {
        val parts = row.split(',')
        if (parts.size != FIELDS) return null
        val numbers = parts.take(FIELDS - 1).map { it.toDoubleOrNull() ?: return null }
        if (numbers.any { !it.isFinite() }) return null
        val armed = when (parts[FIELDS - 1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
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

    /** Replace the record. Whole, atomically, or not at all. */
    fun save(record: TripRecord) {
        val energy = record.energy
        val text = String.format(
            Locale.US,
            "%.4f,%.4f,%.4f,%.1f,%.3f,%.1f,%d\n",
            energy.netKwh,
            energy.recoveredKwh,
            energy.engineKwh,
            energy.engineSeconds,
            energy.kilometres,
            record.odometerKm,
            if (record.armed) 1 else 0,
        )
        val temp = File(file.parentFile, file.name + ".tmp")
        val ok = runCatching {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
            check(temp.renameTo(file)) { "переименование не удалось" }
        }.isSuccess
        if (!ok) {
            temp.delete()
            wipe("запись не удалась")
        }
    }

    /** Forget the trip. Cheap, and the answer to every kind of wrong. */
    fun clear() {
        file.delete()
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

        /** Five doubles, a distance and a flag. */
        private const val FIELDS = 7

        /** One record is under a hundred bytes; anything past this is not our file. */
        private const val MAX_BYTES = 512

        /** No odometer on this car reaches this; a bigger number is a bad parse. */
        private const val MAX_ODOMETER_KM = 2_000_000.0
    }
}
