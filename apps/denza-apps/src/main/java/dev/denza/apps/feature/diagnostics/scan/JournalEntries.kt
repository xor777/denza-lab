package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.IdentDid
import dev.denza.apps.feature.diagnostics.protocol.ObdValue
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.vehicle.VehicleSignal

/**
 * The journal's entries out of what the session holds (architecture, 6.4): a scan out of its modules'
 * results and the car's readings at its end, a cell snapshot out of the sweep «Ячейки» drew. Plain
 * functions of their inputs; the session calls them under its lock and writes the result off it.
 */
internal object JournalEntries {
    /**
     * A scan of [results], in the order given, ended at [atMillis]. [idents] are the session's opened
     * blocks: their `F195` and the ECM's odometer, where read. [vin]: the car's VIN where the scan read it.
     */
    fun scan(
        kind: JournalScanKind,
        results: List<ModuleResult>,
        idents: Map<Int, IdentResult>,
        car: CarReadings,
        atMillis: Long,
        vin: String? = null,
    ): JournalScan {
        val replies = results.flatMap { it.read.replies }
        return JournalScan(
            atMillis = atMillis,
            startedAtMillis = replies.minOfOrNull { it.atMillis - it.latencyMs } ?: atMillis,
            kind = kind,
            odometerKm = odometer(car, idents),
            vin = vin,
            vitals = vitals(car, results),
            modules = results.map { module(it, idents[it.read.request]) },
            exchanges = replies.size,
            medianMs = medianMs(replies),
        )
    }

    fun module(result: ModuleResult, ident: IdentResult?): JournalModule {
        val memory = (result.memory as? Polled.Got)?.value
        val uds = memory?.records.orEmpty().map { r ->
            JournalRecord(r.code.display, r.code.rawHex, r.code.formatId, r.status.byte, "19 02 %02X".format(memory!!.requestMask.byte))
        }
        val obd = ScanRequests.OBD_LISTS.flatMap { (mode, uds) ->
            (result.obdLists[mode] as? Polled.Got)?.value?.codes.orEmpty().map { JournalRecord(it, null, null, null, uds) }
        }
        val f195 = (ident?.dids?.get(IdentDid.F195) as? Polled.Got)?.value?.raw
            ?: (result.read.reply("22" + IdentDid.F195.hex) as? DiagReply.Answer)
                ?.let { (DiagDecoder.identification(it) as? Decoded.Ok)?.value?.raw }
        return JournalModule(
            address = result.read.request,
            abbreviation = result.ecu.abbreviation,
            status = result.status,
            answered = result.answered,
            memoryRead = result.memoryRead,
            requestMask = memory?.requestMask?.byte ?: result.ecu.dtcMask.takeIf { result.ecu.uds && result.memory !is Polled.NotPolled },
            availability = memory?.availability?.byte,
            records = uds + obd,
            f194 = SoftwareReads.of(result).firstOrNull()?.code,
            f195 = f195?.takeIf { v -> v.isNotEmpty() && v.any { it != '0' } },
            medianMs = medianMs(result.read.replies),
            mil = result.mil,
        )
    }

    /** The quantities the section reads without the diagnostic channel, fresh at the scan's end; MIL and readiness from the scan. */
    fun vitals(car: CarReadings, results: List<ModuleResult>): JournalVitals {
        val mils = results.mapNotNull { it.mil }
        val ecm = results.firstOrNull { it.read.request == ECM }
        return JournalVitals(
            lvVolts = car.extra(DiagnosticExtra.LV),
            soc = soc(car),
            soh = car.extra(DiagnosticExtra.SOH),
            packCurrentA = car.extra(DiagnosticExtra.CHARGE_CURRENT),
            cellMinMv = car.hub[VehicleSignal.CELL_MIN_MV],
            cellMaxMv = car.hub[VehicleSignal.CELL_MAX_MV],
            insulationMohm = car.extra(DiagnosticExtra.INSULATION)?.let { it / 1000.0 },
            insulation2Mohm = car.extra(DiagnosticExtra.INSULATION_2)?.let { it / 1000.0 },
            probeMinC = car.extra(DiagnosticExtra.TEMP_MIN),
            probeMaxC = car.extra(DiagnosticExtra.TEMP_MAX),
            mil = if (mils.isEmpty()) null else mils.any { it },
            readiness = (ecm?.monitors as? Polled.Got)?.value?.monitors.orEmpty().associate { it.monitor to it.state },
        )
    }

    /**
     * The odometer, km: the hub's `autoservice` reading (1014 `4A502010`, the one the trip journals
     * use), else the ECM's PID A6 where this session opened the ECM and its maps offer it; else null.
     */
    fun odometer(car: CarReadings, idents: Map<Int, IdentResult>): Double? =
        car.hub[VehicleSignal.ODOMETER_KM] ?: idents[ECM]?.let(::ecmOdometer)

    private fun ecmOdometer(ident: IdentResult): Double? {
        val answer = ident.read.reply(IdentProcedure.ODOMETER_REQUEST) as? DiagReply.Answer ?: return null
        val pids = (DiagDecoder.obdPids(answer) as? Decoded.Ok)?.value ?: return null
        return (pids[IdentProcedure.ODOMETER]?.value as? ObdValue.Numbers)?.quantities?.singleOrNull()?.value
    }

    /**
     * The sweep [pack] has drawn, taken at [atMillis] (the newest drawn page, wall clock), or null
     * before a whole sweep was drawn: half a sweep is two sweeps' cells side by side (6.3).
     */
    fun cells(pack: PackCells, atMillis: Long, car: CarReadings): JournalCells? {
        if (pack.cells.snapshots == 0) return null
        val cells = pack.cells.codes()
        val probes = pack.probes.codes()
        if (cells.none { it != null }) return null
        // The stock aggregate skips a probe's raw 0; it is kept as it came, but is no extreme.
        val temps = probes.map { code -> code?.takeIf { it != PackCells.PROBE_SKIPPED }?.minus(PackCells.PROBE_OFFSET) }
        val soc = soc(car)
        return JournalCells(
            atMillis = atMillis,
            soc = soc,
            currentA = car.extra(DiagnosticExtra.CHARGE_CURRENT),
            odometerKm = car.hub[VehicleSignal.ODOMETER_KM],
            cellsMv = cells,
            probesC = probes.map { it?.minus(PackCells.PROBE_OFFSET) },
            minCell = first(cells) { a, b -> a < b },
            maxCell = first(cells) { a, b -> a > b },
            minProbe = first(temps) { a, b -> a < b },
            maxProbe = first(temps) { a, b -> a > b },
            fullCharge = soc != null && soc >= JournalCells.FULL_SOC,
        )
    }

    /** The BMS's number, from 1, of the first value that [better] than every other; null with none. */
    private fun first(values: List<Int?>, better: (Int, Int) -> Boolean): Int? {
        var at = -1
        values.forEachIndexed { i, v -> if (v != null && (at < 0 || better(v, values[at]!!))) at = i }
        return if (at < 0) null else at + 1
    }

    /** SOC in tenths where the pack's stream page read it, else whole percent. */
    private fun soc(car: CarReadings): Double? = car.extra(DiagnosticExtra.SOC_TENTHS) ?: car.extra(DiagnosticExtra.SOC)

    /** The median time of the answered exchanges, ms; the lower middle of an even count. */
    fun medianMs(replies: List<DiagReply>): Int? {
        val ms = replies.filterIsInstance<DiagReply.Answer>().map { it.latencyMs }.sorted()
        return if (ms.isEmpty()) null else ms[(ms.size - 1) / 2]
    }

    private val ECM: Int = EcuCatalog.all.first { it.abbreviation == "ECM" }.request!!
}
