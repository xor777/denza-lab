package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/** A record's identity across scans: the module's address and the code as printed. */
internal data class JournalCodeKey(val address: Int, val code: String)

/**
 * One code's life in the journal (architecture, 6.4): since when, until when, and how often of the
 * scans that could have seen it.
 */
internal data class JournalCodeLife(
    val key: JournalCodeKey,
    val abbreviation: String,
    /** The record as the latest scan holding it read it. */
    val record: JournalRecord,
    /** The first scan that held it: when, and the odometer then. */
    val firstAtMillis: Long,
    val firstOdometerKm: Double?,
    /** The latest scan that held it. */
    val lastAtMillis: Long,
    /** Every scan that held it, oldest first: the code screen's dots. */
    val seenAt: List<Long>,
    /** «в 5 сканах из 5»: scans since the first that held it, of those that read the module's memory whole. */
    val seenIn: Int,
    val readIn: Int,
    /**
     * In the scan asked about, and not in the reading of the module before it - the previous scan that
     * read its memory whole. False where there is no such reading: a journal's first scan has nothing new.
     */
    val isNew: Boolean,
)

/** A code the previous reading of its module held and the scan asked about does not. */
internal data class JournalGoneCode(
    val key: JournalCodeKey,
    val abbreviation: String,
    val record: JournalRecord,
    /** The last scan that held it. */
    val lastAtMillis: Long,
)

/** One code between two scans; [from] or [to] is null where that scan did not hold it. */
internal data class JournalCodeChange(
    val key: JournalCodeKey,
    val abbreviation: String,
    val from: JournalRecord?,
    val to: JournalRecord?,
) {
    /** Held by both with another status byte. */
    val statusChanged: Boolean get() = from != null && to != null && from.status != to.status
}

/** A module whose `F194` or `F195` differs between two scans that both read it. */
internal data class JournalSoftwareDiff(
    val address: Int,
    val abbreviation: String,
    val fromF194: String?,
    val toF194: String?,
    val fromF195: String?,
    val toF195: String?,
)

/** One quantity in two scans; [delta] where both have it. */
internal data class JournalDelta(val quantity: JournalQuantity, val from: Double?, val to: Double?) {
    val delta: Double? get() = if (from != null && to != null) to - from else null
}

/** Two scans side by side (the journal's «Сравнение», the summary's «Сравнить с прошлым»). */
internal data class JournalComparison(
    val from: JournalScan,
    val to: JournalScan,
    /** In [to], not in [from], the module's memory read whole in both. */
    val added: List<JournalCodeChange>,
    /** In [from], not in [to], the module's memory read whole in both. */
    val gone: List<JournalCodeChange>,
    /** In both; [JournalCodeChange.statusChanged] says where the status byte moved. */
    val same: List<JournalCodeChange>,
    /** Held by one where the other did not read the module's memory whole: neither new nor gone. */
    val unread: List<JournalCodeChange>,
    val software: List<JournalSoftwareDiff>,
    /** [JournalQuantity.SCAN] in that order. */
    val quantities: List<JournalDelta>,
)

/** One software update the scanner knows of: from the code history (5.5) or from two scans' `F194`/`F195`. */
internal data class JournalSoftwareEvent(
    val key: SoftwareKey,
    val abbreviation: String,
    val oldCode: String,
    val oldVersion: String?,
    val newCode: String,
    val newVersion: String?,
    /** The day it was seen. */
    val day: LocalDate,
    /** The first journal scan that held the new code; null where none did (an opened block saw it). */
    val atMillis: Long?,
)

/** A quantity the journal keeps a series of. */
internal enum class JournalQuantity(val unit: String) {
    LV_VOLTS("В"),
    SOC("%"),
    SOH("%"),
    /** The spread of the BMS's own min and max cell GETs at a scan's end: the car at rest, parked. */
    CELL_SPREAD_MV("мВ"),
    INSULATION_MOHM("МОм"),
    INSULATION_2_MOHM("МОм"),
    PROBE_MIN_C("°C"),
    PROBE_MAX_C("°C"),
    PROBE_SPREAD_C("°C"),
    ODOMETER_KM("км"),
    /** A scan's median exchange time. */
    MEDIAN_MS("мс"),
    /** The spread of every cell snapshot. */
    SNAPSHOT_SPREAD_MV("мВ"),
    /** The spread of the snapshots taken at SOC ≥ 95 % ([JournalCells.fullCharge]). */
    FULL_CHARGE_SPREAD_MV("мВ"),
    /** The spread of the other snapshots: «Здоровье»'s «в покое», what is usual for this car away from a full charge. */
    REST_SPREAD_MV("мВ"),
    /** The probes' spread of every snapshot, max − min of those that are no raw 0. */
    SNAPSHOT_PROBE_SPREAD_C("°C");

    companion object {
        /** The quantities of a scan, in the order a comparison lists them. */
        val SCAN = listOf(LV_VOLTS, SOC, SOH, CELL_SPREAD_MV, INSULATION_MOHM, INSULATION_2_MOHM, PROBE_SPREAD_C, ODOMETER_KM, MEDIAN_MS)
    }
}

/** A value at a time. */
internal data class JournalPoint(val atMillis: Long, val value: Double)

/** One day of a series: the median of its values, null on a day without one. */
internal data class JournalDay(val day: LocalDate, val value: Double?)

/** «обычно 3–6»: the 10th and 90th percentiles of [count] values. */
internal data class JournalUsual(val p10: Double, val p90: Double, val count: Int)

/** «№ 9 · 7 из 9 снимков»: the cell most often the lowest, by its BMS number from 1. */
internal data class JournalWeakestCell(val cell: Int, val times: Int, val of: Int)

/**
 * What the screens ask of the journal (architecture, 6.4): pure functions of a [JournalData], and of
 * the code history for software. A scan is asked about by itself - the default is the latest - and its
 * neighbours are found by time. Only a module whose memory was read whole ([JournalModule.memoryRead])
 * says anything about a code not being there.
 */
internal object JournalFacts {
    /** The window of «обычно» and of the daily series. */
    const val WINDOW_DAYS = 30
    /** Fewer values than this in the window give no «обычно». */
    const val USUAL_MIN = 5

    // ------------------------------------------------------------------ codes

    /** [key]'s life up to [scan] (the latest by default); null where no scan up to it held it. */
    fun life(data: JournalData, key: JournalCodeKey, scan: JournalScan? = data.latest): JournalCodeLife? {
        if (scan == null) return null
        val upTo = data.scans.filter { it.atMillis <= scan.atMillis }
        val holding = upTo.filter { s -> s.module(key.address)?.let { holds(it, key.code) } == true }
        val first = holding.firstOrNull() ?: return null
        val last = holding.last()
        val readSince = upTo.filter { it.atMillis >= first.atMillis && it.module(key.address)?.memoryRead == true }
            .map { it.atMillis }.toSet()
        val module = last.module(key.address)!!
        val before = previousReading(upTo, scan, key.address)
        return JournalCodeLife(
            key = key,
            abbreviation = module.abbreviation,
            record = module.records.first { it.code == key.code },
            firstAtMillis = first.atMillis,
            firstOdometerKm = first.odometerKm,
            lastAtMillis = last.atMillis,
            seenAt = holding.map { it.atMillis },
            seenIn = holding.count { it.atMillis in readSince },
            readIn = readSince.size,
            isNew = scan.module(key.address)?.let { holds(it, key.code) } == true &&
                before != null && !holds(before.module(key.address)!!, key.code),
        )
    }

    /** The life of every code [scan] holds, in its order: the summary's records with their first reading. */
    fun lives(data: JournalData, scan: JournalScan? = data.latest): List<JournalCodeLife> =
        scan?.modules.orEmpty().flatMap { m -> m.records.map { JournalCodeKey(m.address, it.code) } }.distinct()
            .mapNotNull { life(data, it, scan) }

    /** The codes the previous reading of each module held and [scan] does not, where [scan] read it whole. */
    fun gone(data: JournalData, scan: JournalScan? = data.latest): List<JournalGoneCode> {
        if (scan == null) return emptyList()
        val upTo = data.scans.filter { it.atMillis <= scan.atMillis }
        return scan.modules.filter { it.memoryRead }.flatMap { now ->
            val before = previousReading(upTo, scan, now.address)?.module(now.address) ?: return@flatMap emptyList()
            before.records.distinctBy { it.code }.filter { !holds(now, it.code) }.map { r ->
                val key = JournalCodeKey(now.address, r.code)
                JournalGoneCode(key, now.abbreviation, r, upTo.last { s -> s.module(now.address)?.let { holds(it, r.code) } == true }.atMillis)
            }
        }
    }

    /** The scan before [scan] that read [address]'s memory whole. */
    private fun previousReading(scans: List<JournalScan>, scan: JournalScan, address: Int): JournalScan? =
        scans.lastOrNull { it.atMillis < scan.atMillis && it.module(address)?.memoryRead == true }

    private fun holds(m: JournalModule, code: String) = m.records.any { it.code == code }

    // ------------------------------------------------------------------ two scans

    /** The full scan before [scan]: the comparison's default «from». */
    fun previous(data: JournalData, scan: JournalScan? = data.latest): JournalScan? =
        scan?.let { s -> data.scans.lastOrNull { it.atMillis < s.atMillis && it.kind == JournalScanKind.FULL } }

    fun compare(from: JournalScan, to: JournalScan): JournalComparison {
        val added = ArrayList<JournalCodeChange>()
        val gone = ArrayList<JournalCodeChange>()
        val same = ArrayList<JournalCodeChange>()
        val unread = ArrayList<JournalCodeChange>()
        val addresses = (from.modules.map { it.address } + to.modules.map { it.address }).distinct()
        for (address in addresses) {
            val a = from.module(address)
            val b = to.module(address)
            val name = (b ?: a)!!.abbreviation
            val both = a?.memoryRead == true && b?.memoryRead == true
            val codes = (a?.records.orEmpty() + b?.records.orEmpty()).map { it.code }.distinct()
            for (code in codes) {
                val change = JournalCodeChange(
                    JournalCodeKey(address, code), name,
                    a?.records?.firstOrNull { it.code == code }, b?.records?.firstOrNull { it.code == code },
                )
                when {
                    !both -> unread += change
                    change.from == null -> added += change
                    change.to == null -> gone += change
                    else -> same += change
                }
            }
        }
        val software = addresses.mapNotNull { address ->
            val a = from.module(address) ?: return@mapNotNull null
            val b = to.module(address) ?: return@mapNotNull null
            val code = a.f194 != null && b.f194 != null && a.f194 != b.f194
            val version = a.f195 != null && b.f195 != null && a.f195 != b.f195
            if (code || version) JournalSoftwareDiff(address, b.abbreviation, a.f194, b.f194, a.f195, b.f195) else null
        }
        return JournalComparison(
            from = from, to = to, added = added, gone = gone, same = same, unread = unread, software = software,
            quantities = JournalQuantity.SCAN.map { JournalDelta(it, value(from, it), value(to, it)) },
        )
    }

    // ------------------------------------------------------------------ software

    /**
     * Every software update known, oldest first: each change of the code history (5.5), placed at the
     * first journal scan of its day that held the new code; then each change two neighbouring readings of
     * a module show in `F194` or `F195` that the history does not hold (a history begun after them, or
     * lost).
     */
    fun softwareEvents(data: JournalData, history: SoftwareHistory, zone: ZoneId): List<JournalSoftwareEvent> {
        val events = ArrayList<JournalSoftwareEvent>()
        history.records.forEach { (key, record) ->
            val name = EcuCatalog.byRequest(key.request)?.abbreviation ?: Figures.address(key.request)
            record.changes.forEach { c ->
                val at = if (key.did != null) null else data.scans.firstOrNull { s ->
                    day(s.atMillis, zone) == c.detected && s.module(key.request)?.f194 == c.newCode
                }?.atMillis
                events += JournalSoftwareEvent(key, name, c.oldCode, c.oldVersion, c.newCode, c.newVersion, c.detected, at)
            }
        }
        val known = events.map { it.key to it.newCode }.toSet()
        val last = HashMap<Int, JournalModule>()
        data.scans.forEach { s ->
            s.modules.forEach { m ->
                val before = last[m.address]
                if (m.f194 != null) {
                    if (before?.f194 != null) {
                        val code = before.f194 != m.f194
                        val version = before.f195 != null && m.f195 != null && before.f195 != m.f195
                        val key = SoftwareKey(m.address)
                        if ((code || version) && (key to m.f194) !in known) {
                            events += JournalSoftwareEvent(key, m.abbreviation, before.f194, before.f195, m.f194, m.f195, day(s.atMillis, zone), s.atMillis)
                        }
                    }
                    last[m.address] = if (m.f195 == null && before?.f194 == m.f194) m.copy(f195 = before.f195) else m
                }
            }
        }
        return events.sortedWith(compareBy({ it.day }, { it.atMillis ?: Long.MAX_VALUE }))
    }

    // ------------------------------------------------------------------ quantities

    /** [quantity]'s values, oldest first: scans' for a scan quantity, snapshots' for a snapshot one. */
    fun points(data: JournalData, quantity: JournalQuantity): List<JournalPoint> = when (quantity) {
        JournalQuantity.SNAPSHOT_SPREAD_MV -> data.cells.mapNotNull { c -> c.spreadMv?.let { JournalPoint(c.atMillis, it.toDouble()) } }
        JournalQuantity.FULL_CHARGE_SPREAD_MV ->
            data.cells.filter { it.fullCharge }.mapNotNull { c -> c.spreadMv?.let { JournalPoint(c.atMillis, it.toDouble()) } }
        JournalQuantity.REST_SPREAD_MV ->
            data.cells.filter { !it.fullCharge }.mapNotNull { c -> c.spreadMv?.let { JournalPoint(c.atMillis, it.toDouble()) } }
        JournalQuantity.SNAPSHOT_PROBE_SPREAD_C -> data.cells.mapNotNull { c ->
            val lo = c.minProbe?.let { c.probesC[it - 1] }
            val hi = c.maxProbe?.let { c.probesC[it - 1] }
            if (lo != null && hi != null) JournalPoint(c.atMillis, (hi - lo).toDouble()) else null
        }
        else -> data.scans.mapNotNull { s -> value(s, quantity)?.let { JournalPoint(s.atMillis, it) } }
    }

    /** [quantity] in [scan]; null for a snapshot quantity or where the scan did not have it. */
    fun value(scan: JournalScan, quantity: JournalQuantity): Double? = with(scan.vitals) {
        when (quantity) {
            JournalQuantity.LV_VOLTS -> lvVolts
            JournalQuantity.SOC -> soc
            JournalQuantity.SOH -> soh
            JournalQuantity.CELL_SPREAD_MV -> cellSpreadMv
            JournalQuantity.INSULATION_MOHM -> insulationMohm
            JournalQuantity.INSULATION_2_MOHM -> insulation2Mohm
            JournalQuantity.PROBE_MIN_C -> probeMinC
            JournalQuantity.PROBE_MAX_C -> probeMaxC
            JournalQuantity.PROBE_SPREAD_C -> probeSpreadC
            JournalQuantity.ODOMETER_KM -> scan.odometerKm
            JournalQuantity.MEDIAN_MS -> scan.medianMs?.toDouble()
            JournalQuantity.SNAPSHOT_SPREAD_MV, JournalQuantity.FULL_CHARGE_SPREAD_MV, JournalQuantity.REST_SPREAD_MV,
            JournalQuantity.SNAPSHOT_PROBE_SPREAD_C -> null
        }
    }

    /**
     * «обычно» of this car: the 10th and 90th percentiles of [quantity] over the [WINDOW_DAYS] days
     * before [nowMillis], linear between ranks; null with fewer than [USUAL_MIN] values.
     */
    fun usual(data: JournalData, quantity: JournalQuantity, nowMillis: Long): JournalUsual? {
        val since = nowMillis - WINDOW_DAYS * DAY_MS
        val values = points(data, quantity).filter { it.atMillis in since..nowMillis }.map { it.value }.sorted()
        if (values.size < USUAL_MIN) return null
        return JournalUsual(percentile(values, 0.10), percentile(values, 0.90), values.size)
    }

    /** [quantity] by day over the [WINDOW_DAYS] days up to [nowMillis]'s, oldest first: each day's median, null without a value. */
    fun daily(data: JournalData, quantity: JournalQuantity, nowMillis: Long, zone: ZoneId): List<JournalDay> {
        val today = day(nowMillis, zone)
        val byDay = points(data, quantity).groupBy { day(it.atMillis, zone) }
        return (WINDOW_DAYS - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            JournalDay(d, byDay[d]?.map { it.value }?.sorted()?.let { percentile(it, 0.5) })
        }
    }

    // ------------------------------------------------------------------ cells

    /** The cell most often the lowest in the snapshots since [sinceMillis] (all by default); ties go to the lower number. */
    fun weakestCell(data: JournalData, sinceMillis: Long = Long.MIN_VALUE): JournalWeakestCell? {
        val mins = data.cells.filter { it.atMillis >= sinceMillis }.mapNotNull { it.minCell }
        if (mins.isEmpty()) return null
        val (cell, times) = mins.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ -it.value }, { it.key })).first().toPair()
        return JournalWeakestCell(cell, times, mins.size)
    }

    /** The latest snapshot at full charge: «конец зарядки · 22.10 23:40». */
    fun lastFullCharge(data: JournalData): JournalCells? = data.cells.lastOrNull { it.fullCharge }

    // ------------------------------------------------------------------ helpers

    private const val DAY_MS = 24 * 60 * 60_000L

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Linear between the closest ranks of [sorted], as numpy's default. */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        val pos = (sorted.size - 1) * q
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
