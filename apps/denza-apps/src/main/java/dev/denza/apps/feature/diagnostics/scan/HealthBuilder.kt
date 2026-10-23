package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.vehicle.VehicleSignal
import kotlin.math.ceil

/**
 * «Тяговая батарея · Здоровье» (architecture 6.3, 6.4, 7.3; boards `scanner-sys-pack-health`, `-health-first`):
 * each row now, against its reference - a norm, or what is usual for this car (p10–p90 of the journal's 30
 * days, from five values) - and its 30 days; the cells beside it as the open page's sweep draws them. Pure.
 */
internal object HealthBuilder {
    private const val NA = "—"
    private const val BMS = 0x7E7

    fun view(i: V4Inputs): HealthView? {
        val s = i.s
        val car = s.car
        val data = s.journal
        if (car.extra(DiagnosticExtra.SOH) == null && s.packCells == null && data.scans.isEmpty() && data.cells.isEmpty()) return null
        val now = s.nowMillis
        val cells = s.packCells
        val soc = car.extra(DiagnosticExtra.SOC)
        val soh = car.extra(DiagnosticExtra.SOH)
        val volts = car.hub[VehicleSignal.PACK_VOLT]
        val full = JournalFacts.lastFullCharge(data)
        val weakest = JournalFacts.weakestCell(data, now - JournalFacts.WINDOW_DAYS * DAY_MS)
        val sweepMin = cells?.cellMin?.let { cells.cells[it].value }
        val sweepMax = cells?.cellMax?.let { cells.cells[it].value }
        val restNow = if (sweepMin != null && sweepMax != null) (sweepMax - sweepMin).toDouble()
            else car.hub[VehicleSignal.CELL_MAX_MV]?.let { hi -> car.hub[VehicleSignal.CELL_MIN_MV]?.let { hi - it } }
        val probes = cells?.probes?.mapNotNull { it.value }.orEmpty()
        val probeLo = cells?.probeMin?.let { cells.probes[it].value?.toDouble() } ?: car.extra(DiagnosticExtra.TEMP_MIN)
        val probeHi = cells?.probeMax?.let { cells.probes[it].value?.toDouble() } ?: car.extra(DiagnosticExtra.TEMP_MAX)
        val probeCount = probes.size.takeIf { it > 0 } ?: car.extra(DiagnosticExtra.PROBE_COUNT)?.toInt()
        val flags = GbFlags.view(car).groups.flatMap { it.flags }
        val bms = s.results[BMS]
        val bmsRecords = i.records[BMS]
        fun trend(q: JournalQuantity) = trend(i, q)
        return HealthView(
            rows = listOf(
                HealthRow("Состояние, SOH", "по BMS", soh?.let { Figures.number(it, 0) } ?: NA, "%",
                    soh != null && soh < SummaryBuilder.SOH_FLOOR, HealthReference("≥ 80 %", ReferenceKind.NORM),
                    trend(JournalQuantity.SOH), scale(trend(JournalQuantity.SOH), 80.0, 100.0)),
                HealthRow("Разброс ячеек", "в покое" + (soc?.let { " · SOC ${Figures.number(it, 0)} %" } ?: ""),
                    restNow?.let { Figures.number(it, 0) } ?: NA, "мВ", false, usual(i, JournalQuantity.REST_SPREAD_MV, ""),
                    trend(JournalQuantity.REST_SPREAD_MV), spreadScale(trend(JournalQuantity.REST_SPREAD_MV)), trendPoints = true),
                if (full != null) {
                    HealthRow("Разброс ячеек", "при полном заряде · ${i.clock.dated(full.atMillis)}", full.spreadMv?.toString() ?: NA, "мВ",
                        false, null, trend(JournalQuantity.FULL_CHARGE_SPREAD_MV), spreadScale(trend(JournalQuantity.FULL_CHARGE_SPREAD_MV)),
                        trendPoints = true)
                } else {
                    HealthRow("Разброс ячеек", "при полном заряде · снимков нет", NA, "мВ", false, null, emptyList(), null)
                },
                if (weakest != null) {
                    HealthRow("Слабейшая ячейка", "чаще всех минимальная", "№ ${weakest.cell}", "", false,
                        HealthReference("${weakest.times} из ${weakest.of} ${Words.plural(weakest.of, "снимка", "снимков", "снимков")}", ReferenceKind.NOTE),
                        emptyList(), null)
                } else {
                    HealthRow("Слабейшая ячейка", "минимальная в этом обходе", cells?.cellMin?.let { "№ ${it + 1}" } ?: NA, "", false, null, emptyList(), null)
                },
                insulation(i, "канал BMS", DiagnosticExtra.INSULATION, JournalQuantity.INSULATION_MOHM, volts),
                insulation(i, "канал 2", DiagnosticExtra.INSULATION_2, JournalQuantity.INSULATION_2_MOHM, volts),
                HealthRow("Температуры", probeCount?.let { Words.count(it, "датчик", "датчика", "датчиков") } ?: "датчики",
                    if (probeLo != null && probeHi != null) "${Figures.number(probeLo, 0)}…${Figures.number(probeHi, 0)}" else NA, "°C", false,
                    usual(i, JournalQuantity.SNAPSHOT_PROBE_SPREAD_C, "Δ "), emptyList(), null),
                HealthRow("Флаги GB/T 32960", "общие тревоги",
                    if (flags.all { it.state == GbFlagState.NONE }) NA else flags.count { it.state == GbFlagState.ALARM }.toString(),
                    "из ${flags.size}", flags.any { it.state == GbFlagState.ALARM }, null, emptyList(), null),
                HealthRow("Записи BMS", bms?.takeIf { it.memoryRead }?.let { "19 02 %02X".format(it.memoryMask) } ?: "не читали",
                    bmsRecords?.takeIf { bms?.memoryRead == true }?.size?.toString() ?: NA, "", bmsRecords.orEmpty().isNotEmpty(), null, emptyList(), null),
            ),
            cells = cells,
            age = cells?.age ?: "",
        )
    }

    private fun insulation(i: V4Inputs, sub: String, e: DiagnosticExtra, q: JournalQuantity, volts: Double?): HealthRow {
        val v = Insulation.mohm(i.s.car.extra(e))
        val t = trend(i, q)
        return HealthRow("Изоляция", sub, v?.let(Insulation::text) ?: NA, Insulation.UNIT, Insulation.low(v, volts),
            HealthReference(Insulation.normText(Insulation.floorMohm(volts)), ReferenceKind.NORM), t,
            t.takeIf { it.isNotEmpty() }?.let { 0f..ceil5(maxOf(2.0, (it.filterNotNull().max() * 1.25))) })
    }

    /** «4–5», «Δ 4–7»: the usual range of [q] over 30 days, from five values; null with fewer. */
    private fun usual(i: V4Inputs, q: JournalQuantity, prefix: String): HealthReference? {
        val u = JournalFacts.usual(i.s.journal, q, i.s.nowMillis) ?: return null
        val lo = Figures.number(u.p10, 0)
        val hi = Figures.number(u.p90, 0)
        return HealthReference(prefix + if (lo == hi) lo else "$lo–$hi", ReferenceKind.USUAL)
    }

    /** A value a day for 30 days; empty until two days have one. */
    private fun trend(i: V4Inputs, q: JournalQuantity): List<Float?> {
        val days = JournalFacts.daily(i.s.journal, q, i.s.nowMillis, i.clock.zone).map { it.value?.toFloat() }
        return if (days.count { it != null } >= 2) days else emptyList()
    }

    /** Not narrower than the norm: [lo]…[hi], widened to what the trend holds. */
    private fun scale(t: List<Float?>, lo: Double, hi: Double): ClosedFloatingPointRange<Float>? {
        val v = t.filterNotNull().takeIf { it.isNotEmpty() } ?: return null
        return minOf(lo.toFloat(), v.min())..maxOf(hi.toFloat(), v.max())
    }

    /** 0 to 20 mV at least - 20 × a millivolt - and to the next 5 above the widest. */
    private fun spreadScale(t: List<Float?>): ClosedFloatingPointRange<Float>? {
        val v = t.filterNotNull().takeIf { it.isNotEmpty() } ?: return null
        return 0f..maxOf(20f, ceil5(v.max().toDouble()))
    }

    private fun ceil5(v: Double): Float = (ceil(v / 5.0) * 5.0).toFloat()

    private const val DAY_MS = 24 * 60 * 60_000L
}
