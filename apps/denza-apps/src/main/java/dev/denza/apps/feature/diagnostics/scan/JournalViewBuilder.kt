package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.IdentCodec
import java.time.LocalDate

/**
 * The journal's day axis (architecture, 7.3): a label a day from the journal's first entry to its last - a
 * scan, a cell snapshot - and any time as a fraction of it, a day's share by the time of day.
 */
internal class JournalAxis(data: JournalData, private val clock: ScanClock) {
    private val first: LocalDate?
    val days: List<String>
    private val count: Int

    init {
        val times = data.scans.map { it.atMillis } + data.cells.map { it.atMillis }
        val from = times.minOrNull()?.let(clock::day)
        val to = times.maxOrNull()?.let(clock::day)
        first = from
        val list = ArrayList<String>()
        if (from != null && to != null) {
            var d: LocalDate = from
            while (!d.isAfter(to)) {
                list += "%02d.%02d".format(d.dayOfMonth, d.monthValue)
                d = d.plusDays(1)
            }
        }
        days = list
        count = list.size
    }

    /** [millis] on the axis, 0..1. */
    fun at(millis: Long): Float {
        val from = first ?: return 0f
        val t = java.time.Instant.ofEpochMilli(millis).atZone(clock.zone)
        val day = java.time.temporal.ChronoUnit.DAYS.between(from, t.toLocalDate())
        val share = (t.toLocalTime().toSecondOfDay() / 86_400.0)
        return ((day + share) / count).toFloat().coerceIn(0f, 1f)
    }

    /** Noon of [day] on the axis: an update whose scan is not in the journal. */
    fun at(day: LocalDate): Float = first?.let {
        ((java.time.temporal.ChronoUnit.DAYS.between(it, day) + 0.5) / count).toFloat().coerceIn(0f, 1f)
    } ?: 0f
}

/**
 * «Журнал» (architecture 6.4, 7.3; boards `scanner-journal`, `-journal-first`): the scans asked for, the
 * software updates and the cells' snapshots on one day axis, every block's records from their first read,
 * values by scan, and two full scans compared - by default the journal's first and its last, or the two
 * [DiagnosticsActions.compareScans] named.
 */
internal object JournalViewBuilder {
    fun view(data: JournalData, software: SoftwareHistory, clock: ScanClock, compare: Pair<Long?, Long?>?): JournalView? {
        if (data.scans.isEmpty() && data.cells.isEmpty()) return null
        val axis = JournalAxis(data, clock)
        val events = JournalFacts.softwareEvents(data, software, clock.zone)
        val (from, to) = pair(data, compare)
        val comparison = if (from != null && to != null) JournalFacts.compare(from, to) else null
        val latest = data.latest
        return JournalView(
            aside = "· " + listOfNotNull(axis.days.firstOrNull(), axis.days.lastOrNull()).distinct().joinToString(" — "),
            line = listOfNotNull(
                Words.count(data.scans.size, "скан", "скана", "сканов"),
                data.cells.size.takeIf { it > 0 }?.let { Words.count(it, "снимок ячеек", "снимка ячеек", "снимков ячеек") },
                events.size.takeIf { it > 0 }?.let { Words.count(it, "обновление ПО", "обновления ПО", "обновлений ПО") },
            ).joinToString(" · "),
            counters = comparison?.let {
                listOfNotNull(
                    it.added.size.takeIf { n -> n > 0 }?.let { n -> HeadCounter("НОВЫХ КОДОВ", "$n", on = true) },
                    it.gone.size.takeIf { n -> n > 0 }?.let { n -> HeadCounter("УШЛО", "$n") },
                    it.software.size.takeIf { n -> n > 0 }?.let { n -> HeadCounter("ПО ИЗМЕНЕНО", "$n") },
                )
            }.orEmpty(),
            days = axis.days,
            scans = data.scans.map { JournalTick(axis.at(it.atMillis), it.kind == JournalScanKind.FULL, it.atMillis) },
            software = events.map { e ->
                JournalSoftwareTick(e.atMillis?.let(axis::at) ?: axis.at(e.day), "${e.abbreviation} ${version(e.oldVersion, e.oldCode)} → ${version(e.newVersion, e.newCode)}")
            },
            cells = data.cells.map { c ->
                JournalCellTick(axis.at(c.atMillis), c.fullCharge, c.soc?.takeIf { c.fullCharge }?.let { "SOC ${Figures.number(it, 0)} %" })
            },
            codes = latest?.let { codeLanes(data, it, axis) }.orEmpty(),
            quantities = listOf(
                lane(data, axis, "12 В", JournalQuantity.LV_VOLTS, 1, "", 12.5..14.5, pointsOnly = false),
                lane(data, axis, "Δ ячеек, полный заряд", JournalQuantity.FULL_CHARGE_SPREAD_MV, 0, " мВ", 0.0..20.0, pointsOnly = true),
            ),
            compare = when {
                comparison != null -> compareView(comparison, clock)
                to != null -> single(to, clock)
                else -> null
            },
        )
    }

    /** The two scans compared: those named, else the journal's first full scan against its last; one full scan alone. */
    private fun pair(data: JournalData, compare: Pair<Long?, Long?>?): Pair<JournalScan?, JournalScan?> {
        val full = data.scans.filter { it.kind == JournalScanKind.FULL }
        val to = compare?.second?.let { t -> data.scans.lastOrNull { it.atMillis <= t } } ?: full.lastOrNull() ?: data.latest
        val from = when {
            compare == null -> full.firstOrNull()
            compare.first != null -> data.scans.lastOrNull { it.atMillis <= compare.first!! }
            else -> to?.let { JournalFacts.previous(data, it) }
        }
        return (from?.takeIf { to != null && it.atMillis < to.atMillis }) to to
    }

    /** «3.48.07» where the version is known, else the software code. */
    private fun version(version: String?, code: String): String =
        version?.let { IdentCodec.softwareVersion(it).substringBefore(" · ") } ?: code

    /** A lane a block with records in [latest]: its count and mark there, a dot each journal scan that held any of them. */
    private fun codeLanes(data: JournalData, latest: JournalScan, axis: JournalAxis): List<JournalCodeLane> {
        val order = EcuCatalog.routed.withIndex().associate { (k, e) -> e.request!! to k }
        return latest.modules.filter { it.records.isNotEmpty() }.map { m ->
            val codes = m.records.map { it.code }.toSet()
            val seen = data.scans.filter { s -> s.atMillis <= latest.atMillis && s.module(m.address)?.records?.any { it.code in codes } == true }
                .map { it.atMillis }
            val mark = BlockRecords.worst(m.records.map { r ->
                r.status?.let { BlockRecords.mark(dev.denza.apps.feature.diagnostics.protocol.DtcStatus(it)) }
                    ?: if (r.source == "07") Mark.PENDING else Mark.ACTIVE
            })!!
            Triple(m, seen, mark)
        }.sortedWith(compareBy({ it.second.first() }, { order[it.first.address] ?: Int.MAX_VALUE })).map { (m, seen, mark) ->
            JournalCodeLane(m.address, "${m.abbreviation} ${Figures.address(m.address)}", m.records.size, mark, seen.map(axis::at))
        }
    }

    private fun lane(
        data: JournalData, axis: JournalAxis, name: String, quantity: JournalQuantity, decimals: Int, unit: String,
        floor: ClosedFloatingPointRange<Double>, pointsOnly: Boolean,
    ): JournalQuantityLane {
        val points = JournalFacts.points(data, quantity)
        val lo = minOf(floor.start, points.minOfOrNull { it.value } ?: floor.start)
        val hi = maxOf(floor.endInclusive, points.maxOfOrNull { it.value } ?: floor.endInclusive)
        return JournalQuantityLane(
            name = name,
            points = points.map { axis.at(it.atMillis) to it.value.toFloat() },
            scale = lo.toFloat()..hi.toFloat(),
            last = points.lastOrNull()?.let { Figures.number(it.value, decimals) + unit } ?: "—",
            pointsOnly = pointsOnly,
        )
    }

    // ------------------------------------------------------------------ «СРАВНЕНИЕ»

    private fun compareView(c: JournalComparison, clock: ScanClock) = ScanCompareView(
        title = "${clock.dated(c.from.atMillis)} → ${clock.dated(c.to.atMillis)}",
        codes = listOf(
            Field("новые", "${c.added.size}"),
            Field("ушли", "${c.gone.size}"),
            Field("без изменений", "${c.same.size}"),
        ) + listOfNotNull(c.unread.size.takeIf { it > 0 }?.let { Field("не прочитаны", "$it", muted = true) }),
        software = c.software.map { d ->
            Field("${d.abbreviation} ${Figures.address(d.address)}", "${softwareText(d.fromF195, d.fromF194)} → ${softwareText(d.toF195, d.toF194)}")
        },
        quantities = QUANTITIES.map { (name, q, decimals) ->
            val a = JournalFacts.value(c.from, q)
            val b = JournalFacts.value(c.to, q)
            Field(name, "${a?.let { Figures.number(it, decimals) } ?: "—"} → ${b?.let { Figures.number(it, decimals) } ?: "—"} ${q.unit}")
        },
    )

    /** The journal's one full scan: its records by kind and its values. */
    private fun single(scan: JournalScan, clock: ScanClock): ScanCompareView {
        val marks = scan.modules.flatMap { m ->
            m.records.map { r -> r.status?.let { BlockRecords.mark(dev.denza.apps.feature.diagnostics.protocol.DtcStatus(it)) } ?: Mark.ACTIVE }
        }
        return ScanCompareView(
            title = clock.dated(scan.atMillis),
            codes = listOf(Mark.ACTIVE to "активные", Mark.PENDING to "ожидающие", Mark.HISTORY to "в истории")
                .mapNotNull { (m, word) -> marks.count { it == m }.takeIf { it > 0 }?.let { Field(word, "$it") } }
                .ifEmpty { listOf(Field("записей", "0")) },
            software = emptyList(),
            quantities = QUANTITIES.map { (name, q, decimals) ->
                Field(name, "${JournalFacts.value(scan, q)?.let { Figures.number(it, decimals) } ?: "—"} ${q.unit}")
            },
        )
    }

    private fun softwareText(f195: String?, f194: String?): String =
        f195?.let { IdentCodec.softwareVersion(it).substringBefore(" · ") } ?: f194 ?: "—"

    private val QUANTITIES = listOf(
        Triple("12 В", JournalQuantity.LV_VOLTS, 1),
        Triple("Δ ячеек, покой", JournalQuantity.CELL_SPREAD_MV, 0),
        Triple("Изоляция", JournalQuantity.INSULATION_MOHM, 1),
        Triple("SOH", JournalQuantity.SOH, 0),
    )
}
