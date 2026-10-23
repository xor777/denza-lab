package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.protocol.Monitor
import dev.denza.apps.feature.diagnostics.protocol.MonitorState
import dev.denza.apps.feature.vehicle.JournalFile
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scan journal on the head unit (docs/vehicle-diagnostics-architecture.md, 6.4): every scan and
 * every cell snapshot the user started - the autoscan on opening the section or «Опросить снова»,
 * «Прочитать снова» on one module after it, «Снять ячейки сейчас» - and nothing else. Nothing is
 * polled or written in the background, on parking or at the end of a charge (owner's decision
 * 2026-10-23, 7.3).
 *
 * The types here are the journal's words, kept small: what each module said, the car's quantities at
 * the time, the cells as drawn. The raw exchanges stay in `last-scan.json` and the report; what the
 * screens ask of the journal - since when a code is there, what changed between two scans, what is
 * usual for this car - is [JournalFacts].
 */

/** What made a journal scan: the autoscan of the whole car, or one module read by hand after it. */
internal enum class JournalScanKind { FULL, MODULE }

/** One DTC record as a scan read it. */
internal data class JournalRecord(
    /** As the screens print it: «U0121-87», «517009»; an OBD list's «P0420». */
    val code: String,
    /** The record's three bytes, «C12187»; null for an OBD list's code. */
    val raw: String?,
    /** The DTCFormatIdentifier the memory gave (`59 01`, or the catalog's for a list-only memory); null for OBD. */
    val format: Int?,
    /** The record's status byte; null for an OBD list's code. */
    val status: Int?,
    /** Where it was read: «19 02 09» (UDS, the request mask) or an OBD Mode, «03», «07», «0A». */
    val source: String,
)

/** One module in one scan. */
internal data class JournalModule(
    /** The request address, 0x720. */
    val address: Int,
    val abbreviation: String,
    val status: ModuleStatus,
    /** Something came back, positive or negative. */
    val answered: Boolean,
    /**
     * The memory was read whole for what was asked ([ModuleResult.memoryRead]): [records] are all the
     * module holds under [requestMask]. False - the records say nothing about what is not there.
     */
    val memoryRead: Boolean,
    /** UDS: the request mask asked, `09`; null for an OBD-only module or nothing read. */
    val requestMask: Int?,
    /** UDS: the availability mask of `59 01`/`59 02`; null where the memory was not read. */
    val availability: Int?,
    val records: List<JournalRecord>,
    /** The software code of `22 F194`, 18 hex digits; null where none was read. */
    val f194: String?,
    /** `22 F195` as hex, where this session read it (an opened block, or a scan that asks it); else null. */
    val f195: String?,
    /** The median time of the module's answered exchanges, ms; null with none. */
    val medianMs: Int?,
    /** PID 01's MIL at an OBD module; null elsewhere. */
    val mil: Boolean?,
)

/** The car's quantities at the end of a scan, as the section read them without the diagnostic channel. */
internal data class JournalVitals(
    /** 12 V network, V (`autoservice` 1001, [dev.denza.apps.feature.diagnostics.DiagnosticExtra.LV]). */
    val lvVolts: Double? = null,
    /** State of charge, %: tenths where the pack's stream page read them, else whole percent. */
    val soc: Double? = null,
    val soh: Double? = null,
    /** Pack current, A; the sign is unproven (architecture, 6, C1). */
    val packCurrentA: Double? = null,
    /** The lowest and highest cell, mV, by the BMS's own GETs. */
    val cellMinMv: Double? = null,
    val cellMaxMv: Double? = null,
    /** Insulation of both channels, MΩ: the GET's value read as kΩ, divided by 1000 (one unit everywhere, 7.3). */
    val insulationMohm: Double? = null,
    val insulation2Mohm: Double? = null,
    /** The pack's coolest and warmest probe, °C. */
    val probeMinC: Double? = null,
    val probeMaxC: Double? = null,
    /** MIL by any OBD module's PID 01: true when one lit it, false when every one read said off. */
    val mil: Boolean? = null,
    /** The ECM's readiness since the codes were cleared (PID 01); empty where it was not read. */
    val readiness: Map<Monitor, MonitorState> = emptyMap(),
) {
    val cellSpreadMv: Double? get() = if (cellMinMv != null && cellMaxMv != null) cellMaxMv - cellMinMv else null
    val probeSpreadC: Double? get() = if (probeMinC != null && probeMaxC != null) probeMaxC - probeMinC else null
}

/** One explicit scan: the whole car ([JournalScanKind.FULL]) or one module after it ([JournalScanKind.MODULE]). */
internal data class JournalScan(
    /** When it ended, wall clock. */
    val atMillis: Long,
    /** When its first exchange was made, wall clock. */
    val startedAtMillis: Long,
    val kind: JournalScanKind,
    /** The odometer, km: the hub's `autoservice` reading, else the ECM's PID A6 where this session read it; null without either. */
    val odometerKm: Double?,
    /** The car's VIN where the scan read it; null otherwise. */
    val vin: String?,
    val vitals: JournalVitals,
    /** In catalog order. */
    val modules: List<JournalModule>,
    /** Exchanges made, and the median time of those answered, ms. */
    val exchanges: Int,
    val medianMs: Int?,
) {
    fun module(address: Int): JournalModule? = modules.firstOrNull { it.address == address }
}

/** One «Снять ячейки сейчас»: the sweep «Ячейки» had drawn, with the charge and current then. */
internal data class JournalCells(
    /** When the newest page drawn came, wall clock. */
    val atMillis: Long,
    val soc: Double?,
    /** Pack current, A, as [JournalVitals.packCurrentA]. */
    val currentA: Double?,
    val odometerKm: Double?,
    /** mV in the BMS's order, 166 on this car; null where the drawn sweep holds no value. */
    val cellsMv: List<Int?>,
    /** °C in the BMS's order, 14 on this car; a raw 0 (−40 °C) is kept as it came. */
    val probesC: List<Int?>,
    /** The BMS's numbers, from 1, of the first lowest and highest cell and probe; a probe's raw 0 is no extreme (6.3). */
    val minCell: Int?,
    val maxCell: Int?,
    val minProbe: Int?,
    val maxProbe: Int?,
    /** SOC at or above [FULL_SOC] when taken: what «разброс при полном заряде» is judged by. */
    val fullCharge: Boolean,
) {
    val minMv: Int? get() = minCell?.let { cellsMv[it - 1] }
    val maxMv: Int? get() = maxCell?.let { cellsMv[it - 1] }
    val spreadMv: Int? get() = minMv?.let { lo -> maxMv?.let { it - lo } }

    companion object {
        const val FULL_SOC = 95.0
    }
}

/** The journal as loaded: scans and cell snapshots, each oldest first. */
internal data class JournalData(
    val scans: List<JournalScan> = emptyList(),
    val cells: List<JournalCells> = emptyList(),
) {
    val latest: JournalScan? get() = scans.lastOrNull()
    val latestFull: JournalScan? get() = scans.lastOrNull { it.kind == JournalScanKind.FULL }

    /** [scan] added in time order, the oldest beyond [JournalLimits.SCANS] let go; one already here is not added again. */
    fun with(scan: JournalScan): JournalData =
        if (scans.any { it.atMillis == scan.atMillis && it == scan }) this
        else copy(scans = (scans + scan).sortedBy { it.atMillis }.takeLast(JournalLimits.SCANS))

    fun with(snapshot: JournalCells): JournalData =
        if (cells.any { it.atMillis == snapshot.atMillis && it == snapshot }) this
        else copy(cells = (cells + snapshot).sortedBy { it.atMillis }.takeLast(JournalLimits.CELLS))
}

/**
 * How much the journal keeps. A full scan is about 14 KB of JSON (68 modules under 200 bytes each and
 * their records), so 500 of them are about 7 MB - several explicit scans a day for half a year and
 * more, where the screens look 30 days back. A snapshot is about 1.3 KB; 200 of them are one a day for
 * more than half a year.
 * The byte budgets bound a file whatever its lines hold; a tenth more lines are let in before a file is
 * rewritten, so a full journal is not rewritten on every scan.
 */
internal object JournalLimits {
    const val SCANS = 500
    const val CELLS = 200
    const val SCAN_BYTES = 16L shl 20
    const val CELL_BYTES = 1L shl 20
}

/** Where the journal lives (architecture, 6.4): loaded once, added to one entry at a time. */
internal interface JournalStore {
    fun load(): JournalData
    fun add(scan: JournalScan)
    fun add(cells: JournalCells)
}

/** A [JournalStore] in memory, for a session without files and for the tests. */
internal class MemoryJournalStore(var data: JournalData = JournalData()) : JournalStore {
    override fun load(): JournalData = data
    override fun add(scan: JournalScan) { data = data.with(scan) }
    override fun add(cells: JournalCells) { data = data.with(cells) }
}

/**
 * The journal as two files of JSON lines in `<dir>`, `scans.jsonl` and `cells.jsonl`, one entry a line.
 *
 * An entry is appended and synced ([JournalFile.append]); the car cuts its power without warning, so
 * the last line may be cut short. Reading skips any line that does not parse, and an append after such
 * a tail starts on a line of its own, so a cut line costs only itself. A file grown past its count by
 * a tenth or past its byte budget is rewritten whole with its newest entries through a
 * temporary file and a rename ([JournalFile.replace]), which leaves the previous file on a cut.
 */
internal class FileJournalStore(
    private val dir: File,
    maxScans: Int = JournalLimits.SCANS,
    maxCells: Int = JournalLimits.CELLS,
    scanBytes: Long = JournalLimits.SCAN_BYTES,
    cellBytes: Long = JournalLimits.CELL_BYTES,
) : JournalStore {
    private val scans = Lines(File(dir, SCANS), maxScans, scanBytes, JournalJson::decodeScan)
    private val cells = Lines(File(dir, CELLS), maxCells, cellBytes, JournalJson::decodeCells)

    @Synchronized
    override fun load(): JournalData = JournalData(
        scans = scans.read().sortedBy { it.atMillis },
        cells = cells.read().sortedBy { it.atMillis },
    )

    @Synchronized
    override fun add(scan: JournalScan) {
        dir.mkdirs()
        scans.append(JournalJson.encode(scan))
    }

    @Synchronized
    override fun add(cells: JournalCells) {
        dir.mkdirs()
        this.cells.append(JournalJson.encode(cells))
    }

    private class Lines<T>(val file: File, val max: Int, val budget: Long, val decode: (String) -> T?) {
        /** Lines in the file, counted on the first append. */
        private var count: Int? = null

        fun read(): List<T> = texts().mapNotNull(decode).takeLast(max)

        private fun texts(): List<String> =
            runCatching { if (file.isFile) file.readLines() else emptyList() }.getOrDefault(emptyList()).filter { it.isNotBlank() }

        fun append(line: String) {
            val n = (count ?: texts().size) + 1
            // A tail cut by a power cut ends without a newline: this entry starts a line of its own.
            check(JournalFile.append(file, (if (endsCut()) "\n" else "") + line + "\n")) { "${file.name} not written" }
            count = n
            if (n > max + maxOf(1, max / 10) || file.length() > budget) compact()
        }

        private fun endsCut(): Boolean = runCatching {
            file.isFile && file.length() > 0 && RandomAccessFile(file, "r").use { f ->
                f.seek(f.length() - 1)
                f.read() != '\n'.code
            }
        }.getOrDefault(false)

        /** The newest [max] entries that parse, within half the budget so the next rewrite is far. */
        private fun compact() {
            val kept = ArrayList<String>()
            var bytes = 0L
            for (text in texts().filter { decode(it) != null }.asReversed()) {
                if (kept.size >= max || bytes + text.length + 1 > budget / 2) break
                kept += text
                bytes += text.length + 1
            }
            kept.reverse()
            check(JournalFile.replace(file, kept.joinToString("") { it + "\n" })) { "${file.name} not compacted" }
            count = kept.size
        }
    }

    companion object {
        const val DIRECTORY = "journal"
        const val SCANS = "scans.jsonl"
        const val CELLS = "cells.jsonl"
    }
}

/**
 * One journal entry as one line of JSON. An entry that does not parse - a field missing or of the wrong
 * kind, a format this code does not know - is no entry; a field that is only absent is null.
 */
internal object JournalJson {
    private const val FORMAT = 1

    fun encode(scan: JournalScan): String = JSONObject().apply {
        put("format", FORMAT)
        put("atMillis", scan.atMillis)
        put("startedAtMillis", scan.startedAtMillis)
        put("kind", scan.kind.name)
        scan.odometerKm?.let { put("odometerKm", it) }
        scan.vin?.let { put("vin", it) }
        put("exchanges", scan.exchanges)
        scan.medianMs?.let { put("medianMs", it) }
        put("vitals", vitals(scan.vitals))
        put("modules", JSONArray().apply { scan.modules.forEach { put(module(it)) } })
    }.toString()

    fun decodeScan(line: String): JournalScan? = runCatching {
        val o = JSONObject(line)
        require(o.getInt("format") == FORMAT)
        val modules = o.getJSONArray("modules")
        JournalScan(
            atMillis = o.getLong("atMillis"),
            startedAtMillis = o.getLong("startedAtMillis"),
            kind = JournalScanKind.valueOf(o.getString("kind")),
            odometerKm = o.double("odometerKm"),
            vin = o.string("vin"),
            vitals = vitals(o.getJSONObject("vitals")),
            modules = (0 until modules.length()).map { module(modules.getJSONObject(it)) },
            exchanges = o.getInt("exchanges"),
            medianMs = o.int("medianMs"),
        )
    }.getOrNull()

    fun encode(cells: JournalCells): String = JSONObject().apply {
        put("format", FORMAT)
        put("atMillis", cells.atMillis)
        cells.soc?.let { put("soc", it) }
        cells.currentA?.let { put("currentA", it) }
        cells.odometerKm?.let { put("odometerKm", it) }
        put("cellsMv", ints(cells.cellsMv))
        put("probesC", ints(cells.probesC))
        cells.minCell?.let { put("minCell", it) }
        cells.maxCell?.let { put("maxCell", it) }
        cells.minProbe?.let { put("minProbe", it) }
        cells.maxProbe?.let { put("maxProbe", it) }
        put("fullCharge", cells.fullCharge)
    }.toString()

    fun decodeCells(line: String): JournalCells? = runCatching {
        val o = JSONObject(line)
        require(o.getInt("format") == FORMAT)
        val cells = ints(o.getJSONArray("cellsMv"))
        val probes = ints(o.getJSONArray("probesC"))
        fun index(key: String, size: Int) = o.int(key)?.also { require(it in 1..size) }
        JournalCells(
            atMillis = o.getLong("atMillis"),
            soc = o.double("soc"),
            currentA = o.double("currentA"),
            odometerKm = o.double("odometerKm"),
            cellsMv = cells,
            probesC = probes,
            minCell = index("minCell", cells.size),
            maxCell = index("maxCell", cells.size),
            minProbe = index("minProbe", probes.size),
            maxProbe = index("maxProbe", probes.size),
            fullCharge = o.getBoolean("fullCharge"),
        )
    }.getOrNull()

    private fun vitals(v: JournalVitals) = JSONObject().apply {
        v.lvVolts?.let { put("lvVolts", it) }
        v.soc?.let { put("soc", it) }
        v.soh?.let { put("soh", it) }
        v.packCurrentA?.let { put("packCurrentA", it) }
        v.cellMinMv?.let { put("cellMinMv", it) }
        v.cellMaxMv?.let { put("cellMaxMv", it) }
        v.insulationMohm?.let { put("insulationMohm", it) }
        v.insulation2Mohm?.let { put("insulation2Mohm", it) }
        v.probeMinC?.let { put("probeMinC", it) }
        v.probeMaxC?.let { put("probeMaxC", it) }
        v.mil?.let { put("mil", it) }
        if (v.readiness.isNotEmpty()) put("readiness", JSONObject().apply { v.readiness.forEach { (m, s) -> put(m.name, s.name) } })
    }

    private fun vitals(o: JSONObject): JournalVitals {
        val readiness = o.optJSONObject("readiness")
        return JournalVitals(
            lvVolts = o.double("lvVolts"),
            soc = o.double("soc"),
            soh = o.double("soh"),
            packCurrentA = o.double("packCurrentA"),
            cellMinMv = o.double("cellMinMv"),
            cellMaxMv = o.double("cellMaxMv"),
            insulationMohm = o.double("insulationMohm"),
            insulation2Mohm = o.double("insulation2Mohm"),
            probeMinC = o.double("probeMinC"),
            probeMaxC = o.double("probeMaxC"),
            mil = if (o.has("mil")) o.getBoolean("mil") else null,
            // A monitor or a state this code no longer knows is left out, not the scan.
            readiness = readiness?.keys()?.asSequence()?.mapNotNull { k ->
                val m = Monitor.entries.firstOrNull { it.name == k }
                val s = MonitorState.entries.firstOrNull { it.name == readiness.getString(k) }
                if (m != null && s != null) m to s else null
            }?.toMap().orEmpty(),
        )
    }

    private fun module(m: JournalModule) = JSONObject().apply {
        put("address", Figures.address(m.address))
        put("abbreviation", m.abbreviation)
        put("status", m.status.name)
        put("answered", m.answered)
        put("memoryRead", m.memoryRead)
        m.requestMask?.let { put("requestMask", "%02X".format(it)) }
        m.availability?.let { put("availability", "%02X".format(it)) }
        if (m.records.isNotEmpty()) put("records", JSONArray().apply { m.records.forEach { put(record(it)) } })
        m.f194?.let { put("f194", it) }
        m.f195?.let { put("f195", it) }
        m.medianMs?.let { put("medianMs", it) }
        m.mil?.let { put("mil", it) }
    }

    private fun module(o: JSONObject): JournalModule {
        val records = o.optJSONArray("records") ?: JSONArray()
        return JournalModule(
            address = o.getString("address").toInt(16),
            abbreviation = o.getString("abbreviation"),
            // A status this code no longer knows reads as unread: the records still say what was there.
            status = ModuleStatus.entries.firstOrNull { it.name == o.getString("status") } ?: ModuleStatus.UNREAD,
            answered = o.getBoolean("answered"),
            memoryRead = o.getBoolean("memoryRead"),
            requestMask = o.string("requestMask")?.toInt(16),
            availability = o.string("availability")?.toInt(16),
            records = (0 until records.length()).map { record(records.getJSONObject(it)) },
            f194 = o.string("f194"),
            f195 = o.string("f195"),
            medianMs = o.int("medianMs"),
            mil = if (o.has("mil")) o.getBoolean("mil") else null,
        )
    }

    private fun record(r: JournalRecord) = JSONObject().apply {
        put("code", r.code)
        r.raw?.let { put("raw", it) }
        r.format?.let { put("format", it) }
        r.status?.let { put("status", "%02X".format(it)) }
        put("source", r.source)
    }

    private fun record(o: JSONObject) = JournalRecord(
        code = o.getString("code"),
        raw = o.string("raw"),
        format = o.int("format"),
        status = o.string("status")?.toInt(16),
        source = o.getString("source"),
    )

    private fun ints(values: List<Int?>) = JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }
    private fun ints(a: JSONArray): List<Int?> = (0 until a.length()).map { if (a.isNull(it)) null else a.getInt(it) }

    private fun JSONObject.double(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.string(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
