package dev.denza.apps.feature.diagnostics.report

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.DtcStatus
import dev.denza.apps.feature.diagnostics.protocol.IdentCodec
import dev.denza.apps.feature.diagnostics.protocol.StatusBit
import dev.denza.apps.feature.diagnostics.scan.CarReadings
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.Figures
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.ModuleResult
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.MonitorState
import dev.denza.apps.feature.diagnostics.scan.ScanRequests
import dev.denza.apps.feature.diagnostics.scan.SoftwareHistory
import dev.denza.apps.feature.diagnostics.scan.SoftwareKey
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.time.Instant
import java.time.ZoneId

/**
 * [ReportInput] out of what the section already knows (architecture, 7.4): the codes, their words and
 * descriptions, the blocks' states and the engine's readiness as the model says them; the software codes
 * and the exchanges from the shown scan's own replies; the pack from the car's readings; the journal and
 * the further reads where they are connected. Nothing is asked of the car.
 */
internal object ReportInputs {
    const val CAR = "Denza Z9 GT DM"

    fun of(
        model: DiagnosticsModel,
        /** The shown scan's modules, by request address: the replies the report's raw part is. */
        results: Map<Int, ModuleResult>,
        finishedAtMillis: Long?,
        car: CarReadings,
        software: SoftwareHistory,
        nowMillis: Long,
        zone: ZoneId,
        recording: ReportRecording? = null,
        journal: ReportJournal? = null,
        reads: ReportReads? = null,
    ): ReportInput {
        val codes = codes(model, journal)
        val ecm = model.modules[ECM]
        return ReportInput(
            car = CAR,
            vin = reads?.vin?.takeIf { it.isNotBlank() },
            odometerKm = car.hub[VehicleSignal.ODOMETER_KM],
            scanAt = finishedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) },
            madeAt = Instant.ofEpochMilli(nowMillis).atZone(zone),
            parked = car.parked,
            lowVoltage = car.extra(DiagnosticExtra.LV),
            summary = ReportSummary(
                answered = results.values.count { it.answered },
                catalogued = EcuCatalog.all.size,
                memoriesRead = results.values.count { it.memoryRead },
                active = results.values.sumOf { it.active ?: 0 },
                pending = codes.count { it.kind == ReportCodeKind.PENDING },
                history = results.values.sumOf { it.history ?: 0 },
                blocksWithRecords = codes.map { it.request }.distinct().size,
            ),
            codes = codes,
            pack = ReportPack(
                sohPercent = car.extra(DiagnosticExtra.SOH),
                socPercent = car.extra(DiagnosticExtra.SOC),
                cellMinMv = car.hub[VehicleSignal.CELL_MIN_MV],
                cellMaxMv = car.hub[VehicleSignal.CELL_MAX_MV],
                insulationBmcKohm = car.extra(DiagnosticExtra.INSULATION),
                insulationSecondKohm = car.extra(DiagnosticExtra.INSULATION_2),
                probeMinC = car.extra(DiagnosticExtra.TEMP_MIN),
                probeMaxC = car.extra(DiagnosticExtra.TEMP_MAX),
                fullChargeSpreadMv = journal?.fullChargeSpreadMv,
            ),
            engine = ecm?.let { m ->
                val obd = m.obd
                val read = obd != null && obd.monitors.isNotEmpty()
                ReportEngine(
                    mil = when (obd?.mil) { MilState.ON -> true; MilState.OFF -> false; else -> null },
                    ready = obd?.monitors?.count { it.state == MonitorState.READY }?.takeIf { read },
                    supported = obd?.monitors?.count { it.state != MonitorState.UNSUPPORTED }?.takeIf { read },
                    software = field(m.ident.own, "Версия ПО") ?: f195(ECM, results, software) ?: f194(results[ECM]),
                    calibration = field(m.ident.own, "Калибровка"),
                    freezeFrame = reads?.engineFreezeFrame?.takeIf { it.isNotEmpty() },
                )
            },
            blocks = model.groups.flatMap { it.rows }.map { row ->
                val request = row.request
                val result = request?.let(results::get)
                val f194 = f194(result)
                ReportBlock(
                    request = request,
                    abbreviation = row.abbreviation,
                    address = request?.let(Figures::address) ?: "—",
                    f194 = f194,
                    f195 = request?.let { r ->
                        model.modules[r]?.ident?.own?.let { field(it, "Версия ПО") } ?: f195(r, results, software)
                    },
                    status = statusWord(row.status, result),
                    statusCode = row.status.name,
                )
            },
            exchanges = exchanges(results),
            recording = recording,
            journal = journal,
        )
    }

    /** «что войдёт»: each section, whether it has anything, and its figures (architecture, 7.4). */
    fun options(input: ReportInput): List<ReportOption> = ReportSection.entries.map { section ->
        val (available, detail) = when (section) {
            ReportSection.CODES -> {
                val s = input.summary
                (s.memoriesRead > 0) to when {
                    s.memoriesRead == 0 -> "не читали"
                    input.codes.isEmpty() -> "записей нет"
                    else -> "${count(input.codes.size, "запись", "записи", "записей")} · " +
                        count(s.blocksWithRecords, "блок", "блока", "блоков")
                }
            }
            ReportSection.BLOCKS -> (input.summary.answered > 0) to input.blocks.size.toString()
            ReportSection.PACK -> {
                val p = input.pack
                val parts = listOfNotNull(
                    "SOH".takeIf { p.sohPercent != null },
                    "ячейки".takeIf { p.cellSpreadMv != null },
                    "изоляция".takeIf { p.insulationBmcKohm != null || p.insulationSecondKohm != null },
                    "датчики".takeIf { p.probeMinC != null || p.probeMaxC != null },
                    "полный заряд".takeIf { p.fullChargeSpreadMv != null },
                )
                parts.isNotEmpty() to parts.joinToString(" · ").ifEmpty { "н/д" }
            }
            ReportSection.ENGINE -> {
                val e = input.engine
                val parts = listOfNotNull(
                    "MIL".takeIf { e?.mil != null },
                    "готовность".takeIf { e?.ready != null },
                    "калибровка".takeIf { e?.calibration != null },
                    "стоп-кадр".takeIf { e?.freezeFrame != null },
                )
                (e != null && (parts.isNotEmpty() || e.software != null)) to parts.joinToString(" · ").ifEmpty { if (e?.software != null) "ПО" else "не читали" }
            }
            ReportSection.RAW -> (input.exchanges.count > 0) to count(input.exchanges.count, "обмен", "обмена", "обменов")
            ReportSection.STREAM -> input.recording.let { r ->
                (r != null) to (r?.let { "${ReportText.clock(it.durationMs)} · ${count(it.signals, "сигнал", "сигнала", "сигналов")}" } ?: "нет записи")
            }
        }
        ReportOption(section, available, detail, byDefault = available && section != ReportSection.STREAM)
    }

    // ------------------------------------------------------------------ parts

    private fun codes(model: DiagnosticsModel, journal: ReportJournal?): List<ReportCode> =
        EcuCatalog.routed.flatMap { ecu ->
            val request = ecu.request!!
            val obdOnly = !ecu.uds && ecu.obd
            model.modules[request]?.dtc?.records.orEmpty().map { r ->
                val status = DtcStatus(r.status)
                val kind = when {
                    // An OBD-only block's Mode 03 and 0A codes count as active, as its row does; Mode 07 is pending.
                    obdOnly -> if (StatusBit.PDTC in status) ReportCodeKind.PENDING else ReportCodeKind.ACTIVE
                    StatusBit.TF in status -> ReportCodeKind.ACTIVE
                    StatusBit.PDTC in status -> ReportCodeKind.PENDING
                    StatusBit.CDTC in status -> ReportCodeKind.HISTORY
                    else -> ReportCodeKind.OTHER
                }
                ReportCode(
                    request = request,
                    block = ecu.abbreviation,
                    address = Figures.address(request),
                    code = r.code,
                    failureType = r.failureType,
                    failureName = r.failureName,
                    description = r.description,
                    status = r.status,
                    kind = kind,
                    firstSeen = journal?.firstSeen?.get(ReportCodeKey(request, r.code)),
                )
            }
        }

    /** A value of an identification row whose key starts with [prefix], where it was read. */
    private fun field(fields: List<Field>, prefix: String): String? =
        fields.firstOrNull { it.key.startsWith(prefix) }?.takeIf { !it.muted }?.value?.takeIf { it.isNotBlank() }

    /** The scan's `22 F194` as its software code. */
    private fun f194(result: ModuleResult?): String? =
        ((result?.read?.reply(ScanRequests.F194) as? DiagReply.Answer)?.let(DiagDecoder::softwareCode) as? Decoded.Ok)?.value

    /** The F195 the code history keeps for [request], while the code it goes with is still the one read. */
    private fun f195(request: Int, results: Map<Int, ModuleResult>, software: SoftwareHistory): String? {
        val seen = software.record(SoftwareKey(request))?.seen ?: return null
        val version = seen.version ?: return null
        val read = f194(results[request])
        return IdentCodec.softwareVersion(version).takeIf { read == null || read == seen.code }
    }

    private fun statusWord(status: ModuleStatus, result: ModuleResult?): String = when (status) {
        ModuleStatus.OK -> "—"
        ModuleStatus.HISTORY -> "история ${result?.history ?: ""}".trim()
        ModuleStatus.ACTIVE -> "активные ${result?.active ?: ""}".trim()
        ModuleStatus.UNREAD -> "память не читали"
        ModuleStatus.POLLING -> "опрос"
        ModuleStatus.NO_REPLY -> "нет ответа"
        ModuleStatus.REFUSED -> "отказ"
        ModuleStatus.UNIDENTIFIED -> "не опознан"
        ModuleStatus.INCONSISTENT -> "несогласованный ответ"
        ModuleStatus.NO_ROUTE -> "нет адреса"
    }

    private fun exchanges(results: Map<Int, ModuleResult>): ReportExchanges {
        val replies = EcuCatalog.routed.mapNotNull { e -> results[e.request] }.flatMap { it.read.replies }
        val services = LinkedHashSet<String>()
        val obd = LinkedHashSet<String>()
        replies.forEach { r ->
            val u = r.request.uds
            if (u.isEmpty()) return@forEach
            val b = u.map { it.toInt() and 0xFF }
            when (b[0]) {
                0x22 -> services += "22 " + b.drop(1).joinToString("") { "%02X".format(it) }
                0x19 -> services += b.joinToString(" ") { "%02X".format(it) }
                else -> {
                    obd += "%02X".format(b[0])
                    services += OBD
                }
            }
        }
        // Identification first, then the memory, then OBD: each kind in the order first asked.
        val labels = services.sortedBy { when { it.startsWith("22") -> 0; it.startsWith("19") -> 1; else -> 2 } }
            .map { if (it == OBD) "OBD " + obd.sorted().joinToString("/") else it }
        val start = replies.minOfOrNull { it.atMillis - it.latencyMs }
        val end = replies.maxOfOrNull { it.atMillis }
        return ReportExchanges(labels, replies.size, if (start != null && end != null) (end - start).coerceAtLeast(0) else null)
    }

    /** «18 записей»: a count and its noun in the right form. */
    fun count(n: Int, one: String, few: String, many: String): String {
        val tens = n % 100
        val last = n % 10
        val word = when {
            tens in 11..14 -> many
            last == 1 -> one
            last in 2..4 -> few
            else -> many
        }
        return "$n $word"
    }

    private const val OBD = "\u0000obd"
    private val ECM: Int = EcuCatalog.all.first { it.abbreviation == "ECM" }.request!!
}
