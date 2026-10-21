package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.ecu.EcuGroup
import dev.denza.apps.feature.diagnostics.protocol.DtcStatus
import dev.denza.apps.feature.diagnostics.protocol.IdentDid
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.diagnostics.protocol.StatusBit
import dev.denza.apps.feature.vehicle.VehicleSignal

/**
 * The section around the car's systems (architecture, 7.2): the rail down the left and each
 * system's page - head, tabs, «DTC» across its blocks, «Блоки», and «Состояние» as a [Scheme].
 * Nothing here reads the bus or decodes a byte: it takes what [ModelBuilder] and [SystemsBuilder]
 * already decided (the autoscan's rows, each block's [DtcView], the HV view) and the car's
 * readings. Words and formats are the boards' (`scanner-sys-*`); a value nobody read is «н/д».
 */
internal object SystemPagesBuilder {
    private const val NA = "н/д"
    private const val ECM = 0x7E0

    /** What one build has at hand, decided by [ModelBuilder] before the pages. */
    class Inputs(
        val s: SessionSnapshot,
        val scan: ScanSummary,
        /** The autoscan's rows by request address. */
        val rows: Map<Int, ModuleRowView>,
        /** A block's own screen, where one exists ([DiagnosticsModel.modules]). */
        val modules: Map<Int, ModuleView>,
        val clock: ScanClock,
    )

    /**
     * The blocks of each system by request address, in the order of §7.2's table (the head's line
     * for a system without its own); a block may stand in two systems. BODY, CABIN and OTHER are the
     * catalog's groups, the child-presence radar CPD moved from the cabin to SAFETY. OTHER is empty
     * while every block has a role, and a system without blocks is neither on the rail nor a page.
     */
    val MEMBERS: Map<CarSystem, List<Int>> = CarSystem.entries.associateWith { system ->
        fun group(g: EcuGroup) = EcuCatalog.inGroup(g).mapNotNull { it.request }
        when (system) {
            CarSystem.HV -> listOf(0x7E7, 0x7E3, 0x744, 0x745, 0x7E6, 0x7E0, 0x7E4, 0x766, 0x762)
            CarSystem.PACK -> listOf(0x7E7)
            CarSystem.DRIVES -> listOf(0x7E2, 0x743, 0x7E3, 0x744, 0x745, 0x7E6)
            CarSystem.ENGINE -> listOf(ECM)
            CarSystem.CHASSIS -> listOf(0x782, 0x7E5, 0x783, 0x582, 0x796, 0x597)
            CarSystem.SAFETY -> listOf(0x7F1, 0x742, 0x713, 0x5E0)
            CarSystem.ADAS -> group(EcuGroup.ADAS)
            CarSystem.BODY -> group(EcuGroup.BODY)
            CarSystem.CABIN -> group(EcuGroup.CABIN) - 0x5E0
            CarSystem.CLIMATE -> listOf(0x7B3, 0x7B4, 0x766, 0x590)
            CarSystem.LV -> listOf(0x762)
            CarSystem.OTHER -> group(EcuGroup.UNKNOWN)
        }
    }

    /** The systems that hold a block, in the rail's order. */
    val SHOWN: List<CarSystem> = CarSystem.entries.filter { MEMBERS.getValue(it).isNotEmpty() }

    private val SCREEN_ORDER: Map<Int, Int> = EcuCatalog.routed.withIndex().associate { (i, e) -> e.request!! to i }

    /** A system's blocks as the autoscan lists them. */
    fun blocks(system: CarSystem): List<Ecu> =
        MEMBERS.getValue(system).sortedBy { SCREEN_ORDER[it] ?: Int.MAX_VALUE }.map { EcuCatalog.byRequest(it)!! }

    val TABS: Map<CarSystem, List<SystemTab>> = CarSystem.entries.associateWith { system ->
        when (system) {
            CarSystem.PACK -> listOf(SystemTab.STATE, SystemTab.CELLS, SystemTab.LIVE, SystemTab.GB_FLAGS, SystemTab.DTC, SystemTab.BLOCKS)
            CarSystem.DRIVES, CarSystem.ADAS -> listOf(SystemTab.STATE, SystemTab.COMPARE, SystemTab.DTC, SystemTab.BLOCKS)
            CarSystem.ENGINE -> listOf(SystemTab.STATE, SystemTab.LIVE, SystemTab.DTC_READINESS, SystemTab.BLOCKS)
            CarSystem.OTHER -> listOf(SystemTab.DTC, SystemTab.BLOCKS)
            else -> listOf(SystemTab.STATE, SystemTab.DTC, SystemTab.BLOCKS)
        }
    }

    // ------------------------------------------------------------------ the rail

    /** «Автоскан» with its answered count, then every system with blocks, marked only where its blocks hold codes. */
    fun rail(i: Inputs): List<RailItem> =
        listOf(RailItem(null, "Автоскан", i.scan.answered, RailTone.COUNT)) + SHOWN.map { system ->
            val c = codes(i.s, system)
            when {
                c.active > 0 -> RailItem(system, system.railName, "АКТ ${c.active}", RailTone.ACTIVE)
                c.history > 0 -> RailItem(system, system.railName, "ИСТ ${c.history}", RailTone.HISTORY)
                else -> RailItem(system, system.railName, null, RailTone.NONE)
            }
        }

    /** Active and history records over a system's read memories, as the autoscan's rows count them. */
    private class Codes(val active: Int, val history: Int, val read: Int, val blocks: Int) {
        val any: Boolean get() = read > 0
    }

    private fun codes(s: SessionSnapshot, system: CarSystem): Codes {
        val results = MEMBERS.getValue(system).mapNotNull { s.results[it] }.filter { it.memoryRead }
        return Codes(
            active = results.sumOf { it.active ?: 0 },
            history = results.sumOf { it.history ?: 0 },
            read = results.size,
            blocks = MEMBERS.getValue(system).size,
        )
    }

    // ------------------------------------------------------------------ the pages

    fun pages(i: Inputs): Map<CarSystem, SystemPage> = SHOWN.associateWith { page(i, it) }

    private fun page(i: Inputs, system: CarSystem): SystemPage {
        val blocks = blocks(system)
        val members = MEMBERS.getValue(system).map { EcuCatalog.byRequest(it)!! }
        val c = codes(i.s, system)
        val car = i.s.car
        val total = if (c.any) (c.active + c.history).toString() else "—"
        val dtc = HeadCounter("DTC", total, on = c.active > 0)
        val read = HeadCounter("ПРОЧИТАНО", "${c.read}/${c.blocks}")
        val generic = listOf(
            HeadCounter("DTC АКТ.", if (c.any) c.active.toString() else "—", on = c.active > 0),
            HeadCounter("DTC ИСТ.", if (c.any) c.history.toString() else "—"),
            read,
        )
        val ecmStream = i.modules[ECM]?.obd?.live?.rows.orEmpty().associateBy { it.pid }
        val (aside, line, counters) = when (system) {
            CarSystem.HV -> Triple(
                "· ВВБ, привод, мотор-генератор",
                plainLine(members),
                listOf(HeadCounter("U ВВБ", volts(car)), HeadCounter("SOC", percent(car, DiagnosticExtra.SOC)), dtc),
            )
            CarSystem.PACK -> Triple(
                single(members),
                "напряжение и ток ${VehicleSignal.PACK_VOLT.device} · SOC и SOH ${DiagnosticExtra.SOC.device} · " +
                    "ячейки и модули ${VehicleSignal.CELL_MIN_MV.device} · изоляция ${DiagnosticExtra.INSULATION.device}",
                listOf(HeadCounter("U", volts(car)), HeadCounter("SOC", percent(car, DiagnosticExtra.SOC)), dtc),
            )
            CarSystem.DRIVES -> Triple(
                count(members),
                plainLine(members),
                listOf(HeadCounter("ШИНА ПЕРЕД", unit(car.extra(DiagnosticExtra.BUS_FRONT), 0, "В")),
                    HeadCounter("ШИНА ЗАД", unit(car.extra(DiagnosticExtra.BUS_REAR), 0, "В")), dtc),
            )
            CarSystem.ENGINE -> {
                val ecm = EcuCatalog.byRequest(ECM)!!
                val hz = i.s.streams[ECM]?.hz()?.takeIf { i.s.live == ECM && i.s.streaming }
                val rpm = last(ecmStream[0x0C]) ?: car.hub[VehicleSignal.ENGINE_RPM]
                val mil = i.s.results[ECM]?.mil
                Triple(
                    single(members),
                    listOfNotNull(Figures.route(ECM), ModelBuilder.longProtocol(ecm), "Mode 01",
                        hz?.let { "опрос ${Figures.number(it, 0)} Гц" }).joinToString(" · "),
                    listOf(
                        HeadCounter("ОБОРОТЫ", rpm?.let { Figures.number(it, 0) } ?: NA),
                        HeadCounter("MIL", when (mil) { true -> "ГОРИТ"; false -> "ВЫКЛ"; null -> NA }, on = mil == true),
                        HeadCounter("ГОТОВН.", i.modules[ECM]?.obd?.readiness?.takeIf { it != "—" } ?: NA),
                    ),
                )
            }
            CarSystem.CHASSIS -> {
                val pressures = PRESSURES.values.mapNotNull { car.extra(it) }
                val range = when {
                    pressures.isEmpty() -> NA
                    pressures.min() == pressures.max() -> bar(pressures.min())
                    else -> "${bar(pressures.min())}–${bar(pressures.max())}"
                }
                Triple(
                    count(members),
                    "${tag(0x782)} · ${tag(0x7E5)} · ${tag(0x783)} · RWS ${Figures.address(0x582)} ${Figures.address(0x796)} · ${tag(0x597)} · " +
                        "шины: давление ${DiagnosticExtra.FL_PRESSURE.device}, температура ${DiagnosticExtra.FL_TYRE_TEMP.device}",
                    listOf(HeadCounter("ДАВЛЕНИЕ", range), dtc, read),
                )
            }
            CarSystem.ADAS -> Triple(
                count(members),
                "${tag(0x712)} · ${tag(0x7F2)} · угловые радары ${listOf(0x760, 0x761, 0x7C2, 0x7D4).joinToString(" ") { Figures.address(it) }} · ${tag(0x701)}",
                listOf(dtc, read),
            )
            CarSystem.BODY -> Triple(
                count(members),
                "${tag(0x720)} · ${tag(0x747)} · ${tag(0x722)} · ${tag(0x726)} · фары ${Figures.address(0x770)} ${Figures.address(0x771)} · " +
                    "UWB-ключ ${Figures.address(0x7F3)} и 4 антенны · радары дверей ${DOOR_RADARS.values.joinToString(" ") { Figures.address(it) }} · " +
                    "${tag(0x754)} · ${tag(0x7D6)} · ${tag(0x595)} · ${tag(0x724)}",
                generic,
            )
            CarSystem.CLIMATE -> Triple(
                count(members),
                plainLine(members) + " · температуры ${DiagnosticExtra.CABIN_TEMP.device} · PM2.5 ${DiagnosticExtra.PM_IN.device}",
                listOf(HeadCounter("САЛОН", unit(car.extra(DiagnosticExtra.CABIN_TEMP), 0, "°C")),
                    HeadCounter("УЛИЦА", unit(car.extra(DiagnosticExtra.OUTSIDE_TEMP), 0, "°C")), dtc),
            )
            CarSystem.LV -> Triple(
                single(members),
                "DC-DC в ${tag(0x7E4)} · АКБ ${tag(0x762)} · напряжение ${DiagnosticExtra.LV.device}",
                listOf(HeadCounter("12 В", unit(car.extra(DiagnosticExtra.LV), 1, "В")), dtc),
            )
            CarSystem.CABIN -> Triple(count(members), CABIN_LINE, generic)
            CarSystem.SAFETY, CarSystem.OTHER -> Triple(count(members), plainLine(members), generic)
        }
        return SystemPage(
            system = system,
            aside = aside,
            line = line,
            counters = counters,
            tabs = TABS.getValue(system),
            blocks = blocks.mapNotNull { e -> e.request?.let { i.rows[it] } },
            dtc = blocks.flatMap { dtcRows(i, it) },
            dtcFootnote = footnote(i, blocks),
            scheme = scheme(i, system, ecmStream),
        )
    }

    /** «ECM 7E0». */
    private fun tag(request: Int): String = "${EcuCatalog.byRequest(request)!!.abbreviation} ${Figures.address(request)}"

    private fun plainLine(members: List<Ecu>) = members.joinToString(" · ") { tag(it.request!!) }

    /** «· BMS 7E7», «· ECM 7E0 · UAES». */
    private fun single(members: List<Ecu>): String {
        val e = members.single()
        return "· ${tag(e.request!!)}" + (e.supplier?.let { " · $it" } ?: "")
    }

    /** «· 11 блоков». */
    private fun count(members: List<Ecu>) = "· ${blocksWord(members.size)}"

    /** «1 блок», «3 блока», «11 блоков». */
    fun blocksWord(n: Int): String = "$n " + plural(n, "блок", "блока", "блоков")

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val tens = n % 100
        val last = n % 10
        return when {
            tens in 11..14 -> many
            last == 1 -> one
            last in 2..4 -> few
            else -> many
        }
    }

    // ------------------------------------------------------------------ «DTC»

    private fun dtcRows(i: Inputs, ecu: Ecu): List<SystemDtcRow> {
        val request = ecu.request ?: return emptyList()
        val view = i.modules[request]?.dtc ?: return emptyList()
        return view.records.map { r ->
            val status = DtcStatus(r.status)
            SystemDtcRow(
                request = request,
                block = ecu.abbreviation,
                address = Figures.address(request),
                code = r.code,
                failureType = r.failureType,
                failureName = r.failureName,
                description = r.description ?: r.group,
                status = "0x%02X".format(r.status),
                word = statusWord(status),
                active = StatusBit.TF in status,
            )
        }
    }

    /** The status byte in a word: what a diagnostician reads first off the bits. */
    fun statusWord(status: DtcStatus): String {
        val tf = StatusBit.TF in status
        val confirmed = StatusBit.CDTC in status
        return when {
            tf && confirmed -> "активный, подтверждённый"
            confirmed -> "подтверждённый"
            tf -> "активный"
            StatusBit.PDTC in status -> "ожидающий"
            else -> "—"
        }
    }

    /**
     * What the system's read memories were asked, then the latest of those reads: «19 01 09 · 19 02 09 ·
     * снимок 19:31:29»; «не читали» while none of its memories is read.
     */
    private fun footnote(i: Inputs, blocks: List<Ecu>): String {
        val asked = LinkedHashSet<String>()
        var latest: Long? = null
        blocks.forEach { ecu ->
            // A memory that did not come back is not part of the system's read.
            val r = ecu.request?.let { i.s.results[it] }?.takeIf { it.memoryRead } ?: return@forEach
            if (!ecu.uds && ecu.obd) {
                ScanRequests.OBD_LISTS.forEach { (_, uds) -> if (r.read.reply(uds) != null) asked += "Mode $uds" }
            } else {
                val mask = "%02X".format(ecu.dtcMask)
                if (r.read.reply(ScanRequests.count(ecu.dtcMask)) != null) asked += "19 01 $mask"
                if (r.read.reply(ScanRequests.list(ecu.dtcMask)) != null) asked += "19 02 $mask"
            }
            latest = maxOf(latest ?: r.read.atMillis, r.read.atMillis)
        }
        if (asked.isEmpty()) return "не читали"
        val ordered = asked.sortedBy { if (it.startsWith("19")) 0 else 1 }
        return (ordered + listOfNotNull(latest?.let { "снимок ${i.clock.seconds(it)}" })).joinToString(" · ")
    }

    // ------------------------------------------------------------------ «Состояние»

    private fun scheme(i: Inputs, system: CarSystem, ecm: Map<Int, LiveRow>): Scheme? = when (system) {
        CarSystem.HV -> Scheme.Hv
        CarSystem.PACK -> pack(i.s.car)
        CarSystem.DRIVES -> drives(i)
        CarSystem.ENGINE -> engine(ecm)
        CarSystem.CHASSIS -> chassis(i)
        CarSystem.ADAS -> Scheme.Adas(
            front = i.rows[0x7F2],
            corners = mapOf(Corner.FL to 0x760, Corner.FR to 0x761, Corner.RR to 0x7C2, Corner.RL to 0x7D4)
                .mapNotNull { (corner, r) -> i.rows[r]?.let { corner to it } }.toMap(),
            controller = i.rows[0x712],
            parking = i.rows[0x701],
        )
        CarSystem.BODY -> body(i)
        CarSystem.CLIMATE -> {
            val car = i.s.car
            Scheme.Climate(
                outside = ClimateZone("УЛИЦА", unit(car.extra(DiagnosticExtra.OUTSIDE_TEMP), 0, "°C"),
                    car.extra(DiagnosticExtra.PM_OUT)?.let { Figures.number(it, 0) } ?: NA, inside = false),
                cabin = ClimateZone("САЛОН", unit(car.extra(DiagnosticExtra.CABIN_TEMP), 0, "°C"),
                    car.extra(DiagnosticExtra.PM_IN)?.let { Figures.number(it, 0) } ?: NA, inside = true),
                unit = i.rows[0x7B3],
                heater = i.rows[0x766],
                thermal = i.rows[0x7B4],
                air = i.rows[0x590],
            )
        }
        CarSystem.LV -> Scheme.Lv(
            hv = volts(i.s.car),
            lv = unit(i.s.car.extra(DiagnosticExtra.LV), 1, "В"),
            dcdc = i.rows[0x7E4],
            battery = i.rows[0x762],
            loads = listOf(0x722, 0x720, 0x747, 0x726).mapNotNull { i.rows[it] },
        )
        CarSystem.SAFETY -> Scheme.Safety(
            airbags = i.rows[0x7F1],
            driverBelt = i.rows[0x742],
            passengerBelt = i.rows[0x713],
            childRadar = i.rows[0x5E0],
        )
        CarSystem.CABIN -> cabin(i.s.car)
        CarSystem.OTHER -> null
    }

    /** «553 В»: the pack's voltage, as the HV schema's pack box has it. */
    private fun volts(car: CarReadings) = unit(car.hub[VehicleSignal.PACK_VOLT], 0, "В")

    private fun percent(car: CarReadings, e: DiagnosticExtra) = unit(car.extra(e), 0, "%")

    /** «553 В», «н/д». */
    private fun unit(v: Double?, decimals: Int, unit: String, signed: Boolean = false): String =
        v?.let { "${Figures.number(it, decimals, signed)} $unit" } ?: NA

    /** «8 °C», «н/д °C»: a value inside a row of values keeps its unit, so the row still reads. */
    private fun cell(v: Double?, decimals: Int, unit: String, signed: Boolean = false): String =
        "${v?.let { Figures.number(it, decimals, signed) } ?: NA} $unit"

    private fun bar(v: Double) = Figures.number(v, 2)

    private fun pack(car: CarReadings): Scheme.Pack {
        val soc = car.extra(DiagnosticExtra.SOC)
        val volts = car.hub[VehicleSignal.PACK_VOLT]
        val cellMin = car.hub[VehicleSignal.CELL_MIN_MV]
        val cellMax = car.hub[VehicleSignal.CELL_MAX_MV]
        val tMin = car.extra(DiagnosticExtra.TEMP_MIN)
        val tMax = car.extra(DiagnosticExtra.TEMP_MAX)
        val tMean = car.hub[VehicleSignal.PACK_TEMP_AVG]
        val cells = car.extra(DiagnosticExtra.CELL_COUNT)
        val probes = car.extra(DiagnosticExtra.PROBE_COUNT)
        fun n(v: Double?) = v?.let { Figures.number(it, 0) } ?: NA
        fun span(lo: Double?, hi: Double?) = if (lo != null && hi != null) lo.toFloat()..hi.toFloat() else null
        fun range(lo: Double?, hi: Double?) = if (lo != null && hi != null) "${n(lo)}–${n(hi)}" else NA
        val insulation = listOf("канал BMC" to DiagnosticExtra.INSULATION, "канал 2" to DiagnosticExtra.INSULATION_2).map { (key, e) ->
            val v = car.extra(e)
            Field(key, v?.let { "${Figures.number(it / 1000.0, 1)} МОм" } ?: NA, muted = v == null)
        }
        return Scheme.Pack(
            soc = soc?.toFloat(),
            socText = unit(soc, 0, "%"),
            soh = percent(car, DiagnosticExtra.SOH),
            cells = "${n(cells)} ${plural(cells?.toInt() ?: 0, "ячейка", "ячейки", "ячеек")} · " +
                "${n(probes)} ${plural(probes?.toInt() ?: 0, "датчик", "датчика", "датчиков")}",
            volts = unit(volts, 0, "В"),
            current = unit(car.extra(DiagnosticExtra.CHARGE_CURRENT), 1, "А"),
            insulation = insulation,
            // 500 Ω per volt of the pack, the floor of SystemsBuilder's pack rows.
            isoNorm = "норма ≥ 500 Ом/В" + (volts?.let { " · ${Figures.number(it * 0.5, 0)} кОм" } ?: ""),
            ranges = listOf(
                RangeView(
                    head = "ЯЧЕЙКИ, мВ", value = range(cellMin, cellMax), scale = 3000f..3700f, span = span(cellMin, cellMax),
                    mark = null, lo = "3 000", hi = "3 700",
                    note = "разброс ${if (cellMin != null && cellMax != null) Figures.number(cellMax - cellMin, 0) else NA} мВ",
                ),
                RangeView(
                    head = "ТЕМПЕРАТУРА МОДУЛЕЙ, °C", value = range(tMin, tMax), scale = -20f..60f, span = span(tMin, tMax),
                    mark = tMean?.toFloat(), lo = Figures.number(-20.0, 0), hi = "60", note = "средняя ${n(tMean)}",
                ),
            ),
        )
    }

    private fun drives(i: Inputs): Scheme.Drives {
        val s = i.s
        val car = s.car
        fun hub(v: VehicleSignal) = car.hub[v]
        fun motor(stator: Double?, igbt: Double?, rpm: DiagnosticExtra, torque: DiagnosticExtra, current: DiagnosticExtra) = listOf(
            "статор ${cell(stator, 0, "°C")} · IGBT ${cell(igbt, 0, "°C")}",
            "${cell(car.extra(rpm), 0, "об/мин")} · ${cell(car.extra(torque), 1, "Н·м")} · ${cell(car.extra(current), 1, "А")}",
        )
        val isg = EcuCatalog.byRequest(0x7E6)!!
        val isgResult = s.results[0x7E6]
        val isgDtc = isgResult?.takeIf { it.memoryRead }?.let { ((it.active ?: 0) + (it.history ?: 0)).toString() } ?: "не читали"
        val software = (s.idents[0x7E6]?.dids?.get(IdentDid.F195) as? Polled.Got)?.value?.shown
            ?: (isgResult?.identity as? Polled.Got)?.value
            ?: NA
        val running = car.engineRunning
        return Scheme.Drives(
            vcu = i.rows[0x7E2],
            powerDomain = i.rows[0x743],
            front = Callout("ПЕРЕДНИЙ МОТОР · ${tag(0x7E3)}", motor(hub(VehicleSignal.MOTOR_FRONT_C), hub(VehicleSignal.INVERTER_C),
                DiagnosticExtra.RPM_FRONT, DiagnosticExtra.TORQUE_FRONT, DiagnosticExtra.CURRENT_FRONT)),
            isg = Callout("МОТОР-ГЕНЕРАТОР · ${tag(isg.request!!)}", listOf(
                "DTC $isgDtc · ПО $software",
                when (running) { true -> "ДВС работает"; false -> "ДВС не работает"; null -> "ДВС $NA" },
                "энергосистема: ${generation(car.hub[VehicleSignal.GENERATION_STATE])}",
            )),
            // autoservice names the rear signals by side; SMCU drives the left rear motor, SMCU3 the right.
            rearLeft = Callout("ЗАДНИЙ ЛЕВЫЙ · ${tag(0x744)}", motor(hub(VehicleSignal.MOTOR_REAR_LEFT_C), car.extra(DiagnosticExtra.IGBT_REAR_LEFT),
                DiagnosticExtra.RPM_REAR_LEFT, DiagnosticExtra.TORQUE_REAR_LEFT, DiagnosticExtra.CURRENT_REAR_LEFT)),
            rearRight = Callout("ЗАДНИЙ ПРАВЫЙ · ${tag(0x745)}", motor(hub(VehicleSignal.MOTOR_REAR_RIGHT_C), car.extra(DiagnosticExtra.IGBT_REAR_RIGHT),
                DiagnosticExtra.RPM_REAR_RIGHT, DiagnosticExtra.TORQUE_REAR_RIGHT, DiagnosticExtra.CURRENT_REAR_RIGHT)),
            engineRunning = running == true,
        )
    }

    /**
     * The energy system's generation state `1006·34F0000A`, as the stock `BYDAutoEnergyDevice` names
     * it (`ENERGY_POWER_GENERATING` 1, `_GENERATION_END` 2, `_GENERATION_ERROR` 3; research
     * isg-source-boundary-20261021.json). It is the energy system's word, not ISG 7E6's own, so the
     * generator's callout says «энергосистема». 0 is the stock INVALID, not «off»: it, any other code
     * and no reading are «н/д».
     */
    val GENERATION_WORDS = mapOf(1 to "генерирует", 2 to "завершено", 3 to "ошибка")

    fun generation(value: Double?): String = value?.toInt()?.let { GENERATION_WORDS[it] } ?: NA

    /** The latest sample of a stream row: null where it never came or the last exchange was a gap. */
    private fun last(row: LiveRow?): Double? = row?.samples?.lastOrNull()?.value?.toDouble()

    /**
     * The air's way through the engine, each value the latest sample of ECM's stream; the stream runs
     * while the scheme is on screen, which asks for it as «Потоковые данные» does (`setLiveVisible`).
     */
    private fun engine(rows: Map<Int, LiveRow>): Scheme.Engine {
        fun v(pid: Int, decimals: Int, signed: Boolean = false) = last(rows[pid])?.let { Figures.number(it, decimals, signed) }
        fun named(name: String, pid: Int, decimals: Int, unit: String, signed: Boolean = false) =
            v(pid, decimals, signed)?.let { "$name $it$unit" } ?: "$name $NA"
        val loop = rows[0x03]?.value?.takeIf { it != "—" }?.let { text ->
            when {
                text.startsWith("CL") -> "петля замкнута (CL)"
                text.startsWith("OL") -> "петля разомкнута (OL)"
                else -> text
            }
        } ?: "петля $NA"
        return Scheme.Engine(listOf(
            EnginePoint(EnginePlace.INLET, "ВОЗДУХ НА ВПУСКЕ", listOf(named("IAT", 0x0F, 0, " °C"))),
            EnginePoint(EnginePlace.THROTTLE, "ДРОССЕЛЬ", listOf(named("TPS", 0x11, 1, " %"))),
            EnginePoint(EnginePlace.MANIFOLD, "КОЛЛЕКТОР", listOf(named("MAP", 0x0B, 0, " кПа"))),
            EnginePoint(EnginePlace.BLOCK_TOP, "ДВС", listOf(
                "${v(0x0C, 0) ?: NA} об/мин · ${named("LOAD", 0x04, 0, " %")}",
                "${named("УОЗ", 0x0E, 1, "°")} · ${named("ОЖ", 0x05, 0, " °C")}",
            )),
            EnginePoint(EnginePlace.BLOCK_BOTTOM, "ТОПЛИВО", listOf(
                "${named("STFT", 0x06, 1, " %", signed = true)} · ${named("LTFT", 0x07, 1, " %", signed = true)}",
                loop,
            )),
            EnginePoint(EnginePlace.CATALYST, "КАТАЛИЗАТОР", listOf(named("B1S1", 0x3C, 0, " °C"))),
            EnginePoint(EnginePlace.OXYGEN, "ДАТЧИК O2 B1S2", listOf(v(0x15, 2)?.let { "$it В" } ?: NA)),
        ))
    }

    private val PRESSURES = mapOf(
        Corner.FL to DiagnosticExtra.FL_PRESSURE, Corner.FR to DiagnosticExtra.FR_PRESSURE,
        Corner.RL to DiagnosticExtra.RL_PRESSURE, Corner.RR to DiagnosticExtra.RR_PRESSURE,
    )
    private val TYRE_TEMPS = mapOf(
        Corner.FL to DiagnosticExtra.FL_TYRE_TEMP, Corner.FR to DiagnosticExtra.FR_TYRE_TEMP,
        Corner.RL to DiagnosticExtra.RL_TYRE_TEMP, Corner.RR to DiagnosticExtra.RR_TYRE_TEMP,
    )
    private val CORNER_NAMES = mapOf(Corner.FL to "ПЛ", Corner.FR to "ПП", Corner.RL to "ЗЛ", Corner.RR to "ЗП")

    private fun chassis(i: Inputs): Scheme.Chassis {
        val car = i.s.car
        return Scheme.Chassis(
            tyres = Corner.entries.map { c ->
                Tyre(c, "ШИНА ${CORNER_NAMES.getValue(c)}", car.extra(PRESSURES.getValue(c))?.let(::bar) ?: NA,
                    unit(car.extra(TYRE_TEMPS.getValue(c)), 0, "°C"))
            },
            esp = i.rows[0x782],
            eps = i.rows[0x783],
            bscm = i.rows[0x7E5],
            rearSteerLeft = i.rows[0x582],
            rearSteerRight = i.rows[0x796],
            motion = i.rows[0x597],
        )
    }

    private class DoorSignals(val position: DiagnosticExtra, val lock: DiagnosticExtra, val window: DiagnosticExtra)

    private val DOORS = mapOf(
        Corner.FL to DoorSignals(DiagnosticExtra.FL_POSITION, DiagnosticExtra.FL_LOCK, DiagnosticExtra.FL_WINDOW),
        Corner.FR to DoorSignals(DiagnosticExtra.FR_POSITION, DiagnosticExtra.FR_LOCK, DiagnosticExtra.FR_WINDOW),
        Corner.RL to DoorSignals(DiagnosticExtra.RL_POSITION, DiagnosticExtra.RL_LOCK, DiagnosticExtra.RL_WINDOW),
        Corner.RR to DoorSignals(DiagnosticExtra.RR_POSITION, DiagnosticExtra.RR_LOCK, DiagnosticExtra.RR_WINDOW),
    )

    /** The four UWB anchors by their route names: front left, front right, rear left, rear right. */
    private val ANCHORS = mapOf(Corner.FL to "UWBLFA", Corner.FR to "UWBRFA", Corner.RL to "UWBLRA", Corner.RR to "UWBRRA")

    /** The doors' obstacle radars by their route names: FL_SDR, FR_SDR, RL_SDR, RR_SDR. */
    private val DOOR_RADARS = mapOf(Corner.FL to 0x5B2, Corner.FR to 0x5B1, Corner.RL to 0x5B0, Corner.RR to 0x5A7)

    private fun body(i: Inputs): Scheme.Body {
        val car = i.s.car
        val doors = Corner.entries.map { c ->
            val d = DOORS.getValue(c)
            val position = car.extra(d.position)?.toInt()
            val lock = when (car.extra(d.lock)?.toInt()) { 1 -> "не заперта"; 2 -> "заперта"; else -> "замок $NA" }
            Door(
                corner = c,
                label = "ДВЕРЬ ${CORNER_NAMES.getValue(c)}",
                state = when (position) { 1 -> "открыта"; 2 -> "закрыта"; 3 -> "приоткрыта"; else -> NA },
                detail = "$lock · окно ${unit(car.extra(d.window), 0, "%")}",
                open = position == 1 || position == 3,
            )
        }
        return Scheme.Body(
            doors = doors,
            front = i.rows[0x722],
            back = i.rows[0x726],
            left = i.rows[0x720],
            right = i.rows[0x747],
            lampLeft = i.rows[0x770],
            lampRight = i.rows[0x771],
            uwb = i.rows[0x7F3],
            anchors = Corner.entries.map { c ->
                val request = EcuCatalog.all.first { it.abbreviation == ANCHORS.getValue(c) }.request
                request?.let { i.rows[it]?.status } ?: ModuleStatus.NO_ROUTE
            },
            doorRadars = DOOR_RADARS.mapNotNull { (corner, r) -> i.rows[r]?.let { corner to it } }.toMap(),
            keyReceiver = i.rows[0x754],
            smartEntry = i.rows[0x7D6],
            nfc = i.rows[0x595],
            tailgate = i.rows[0x724],
        )
    }

    // ------------------------------------------------------------------ the cabin

    /** «Салон»'s head line: every block by what it serves, as the board writes it (`cabinBlock.line`). */
    val CABIN_LINE: String = run {
        fun a(vararg r: Int) = r.joinToString(" ") { Figures.address(it) }
        "приборы ${a(0x723)} · ${tag(0x7B0)} · руль ${a(0x711, 0x725)} · ГУ ${a(0x741)} · пасс. ПК ${a(0x5E7)} · " +
            "сиденья ${a(0x750, 0x7B5, 0x7D2)} · селектор ${a(0x7A7)} · холодильник ${a(0x5A0, 0x5F4, 0x7C6)} · " +
            "${tag(0x733)} · ${tag(0x5E4)} · ${tag(0x772)} · ${tag(0x751)} · ${tag(0x790)}"
    }

    /** One seat's GETs; the centre has neither climate, massage nor a lamp. */
    private class SeatGets(
        val label: String,
        val seated: DiagnosticExtra,
        val belt: DiagnosticExtra,
        /** The belt's fastened and unfastened codes: 1 and 0 at the front, 2 and 1 behind. */
        val fastened: Int,
        val unfastened: Int,
        val heat: DiagnosticExtra? = null,
        val vent: DiagnosticExtra? = null,
        val mode: DiagnosticExtra? = null,
        val level: DiagnosticExtra? = null,
        val lamp: DiagnosticExtra? = null,
    )

    private val SEATS: Map<CabinPlace, SeatGets> by lazy {
        linkedMapOf(
            CabinPlace.FL to SeatGets("ВОДИТЕЛЬ · ${tag(0x750)}", DiagnosticExtra.SEATED_FL, DiagnosticExtra.BELT_FL, 1, 0,
                DiagnosticExtra.SEAT_HEAT_FL, DiagnosticExtra.SEAT_VENT_FL, DiagnosticExtra.MASSAGE_MODE_FL, DiagnosticExtra.MASSAGE_LEVEL_FL, DiagnosticExtra.READING_FL),
            CabinPlace.FR to SeatGets("ПАССАЖИР · ${tag(0x7B5)}", DiagnosticExtra.SEATED_FR, DiagnosticExtra.BELT_FR, 1, 0,
                DiagnosticExtra.SEAT_HEAT_FR, DiagnosticExtra.SEAT_VENT_FR, DiagnosticExtra.MASSAGE_MODE_FR, DiagnosticExtra.MASSAGE_LEVEL_FR, DiagnosticExtra.READING_FR),
            CabinPlace.RL to SeatGets("2-Й РЯД ЛЕВЫЙ · ${tag(0x7D2)}", DiagnosticExtra.SEATED_RL, DiagnosticExtra.BELT_RL, 2, 1,
                DiagnosticExtra.SEAT_HEAT_RL, DiagnosticExtra.SEAT_VENT_RL, DiagnosticExtra.MASSAGE_MODE_RL, DiagnosticExtra.MASSAGE_LEVEL_RL, DiagnosticExtra.READING_RL),
            CabinPlace.RC to SeatGets("2-Й РЯД ЦЕНТР", DiagnosticExtra.SEATED_RC, DiagnosticExtra.BELT_RC, 2, 1),
            CabinPlace.RR to SeatGets("2-Й РЯД ПРАВЫЙ", DiagnosticExtra.SEATED_RR, DiagnosticExtra.BELT_RR, 2, 1,
                DiagnosticExtra.SEAT_HEAT_RR, DiagnosticExtra.SEAT_VENT_RR, DiagnosticExtra.MASSAGE_MODE_RR, DiagnosticExtra.MASSAGE_LEVEL_RR, DiagnosticExtra.READING_RR),
        )
    }

    /** The plates' columns as the board stacks them. */
    val CABIN_LEFT = listOf(CabinPlace.CLUSTER, CabinPlace.WHEEL, CabinPlace.FL, CabinPlace.RL, CabinPlace.RC)
    val CABIN_RIGHT = listOf(CabinPlace.PADS, CabinPlace.FR, CabinPlace.FRIDGES, CabinPlace.RR)

    /** The massage programmes by the stock's names: wave, pulse, stretch, smoothing, slack, the hot stones. */
    val MASSAGE_MODES = mapOf(
        2 to "волна", 3 to "пульс", 4 to "растяжка", 5 to "разглаживание", 6 to "расслабление",
        8 to "горячие камни · плечи", 9 to "горячие камни · поясница",
    )

    /** INTENSITY_WEAK, _MEDIUM, _STRONG. */
    val MASSAGE_LEVELS = mapOf(1 to "слабо", 2 to "средне", 3 to "сильно")

    val PAD_WORDS = mapOf(PadState.CHARGING to "заряжает", PadState.FULL to "заряжен", PadState.FAULT to "отказ", PadState.STANDBY to "ожидание")

    val FRIDGE_WORDS = mapOf(FridgeMode.COLD to "холод", FridgeMode.HOT to "тепло", FridgeMode.OFF to "выкл", FridgeMode.FRESH to "свежесть")

    /** The stock's popup reminders of a fridge; 0 is none. */
    val FRIDGE_REMINDERS = mapOf(1 to "нет связи", 2 to "долго работает", 3 to "режим тепла", 4 to "дверь не закрыта")

    /** A three-level seat's heating or ventilation: 1 off (0), 2/3/4 levels 1..3; any other code null. */
    fun seatLevel(raw: Double?): Int? = raw?.toInt()?.takeIf { it in 1..4 }?.minus(1)

    /** Who sits: 2 seated, any other code not, as DiCarServer reads it; null nothing came. */
    fun seated(raw: Double?): Boolean? = raw?.let { it.toInt() == 2 }

    /** A two-state code: [on], [off], else null. */
    fun twoState(raw: Double?, on: Int, off: Int): Boolean? = when (raw?.toInt()) {
        on -> true
        off -> false
        else -> null
    }

    fun pad(raw: Double?): PadState? = when (raw?.toInt()) {
        0 -> PadState.CHARGING
        1 -> PadState.FULL
        2 -> PadState.FAULT
        3 -> PadState.STANDBY
        else -> null
    }

    fun fridge(raw: Double?): FridgeMode? = when (raw?.toInt()) {
        1 -> FridgeMode.COLD
        2 -> FridgeMode.HOT
        3 -> FridgeMode.OFF
        4 -> FridgeMode.FRESH
        else -> null
    }

    /**
     * A mode's set point, °C (research: cold raw − 19 for raw 1..34, hot raw + 34 for 1..16, fresh
     * raw + 7 for 1..3); null off, not known, or a raw code out of its range.
     */
    fun setPoint(mode: FridgeMode?, raw: Double?): Double? {
        val r = raw?.toInt() ?: return null
        return when (mode) {
            FridgeMode.COLD -> (r - 19).takeIf { r in 1..34 }
            FridgeMode.HOT -> (r + 34).takeIf { r in 1..16 }
            FridgeMode.FRESH -> (r + 7).takeIf { r in 1..3 }
            FridgeMode.OFF, null -> null
        }?.toDouble()
    }

    /**
     * «массаж: волна · средне» while it massages - a programme and a level 1..3, as the stock reads
     * it - «массаж выкл» for mode off or a level off or INVALID, «массаж н/д» where either is not known.
     */
    fun massage(mode: Double?, level: Double?): String {
        val m = mode?.toInt() ?: return "массаж $NA"
        if (m == 1) return "массаж выкл"
        val name = MASSAGE_MODES[m] ?: return "массаж $NA"
        val l = level?.toInt() ?: return "массаж $NA"
        return MASSAGE_LEVELS[l]?.let { "массаж: $name · $it" } ?: if (l == 0 || l == 4) "массаж выкл" else "массаж $NA"
    }

    /** A level in words: «выкл», «2», «н/д». */
    private fun levelWord(n: Int?): String = when (n) {
        null -> NA
        0 -> "выкл"
        else -> n.toString()
    }

    /**
     * The seat's first row: «сидит · пристёгнут», «сидит · » with «не пристёгнут» in orange, «пусто»
     * dim (with «· пристёгнут» when a belt is buckled behind nobody); occupancy unknown, «н/д» dim.
     */
    fun presence(occupied: Boolean?, belt: Boolean?): CabinRow = CabinRow(
        when (occupied) {
            true -> when (belt) {
                true -> listOf(CabinRun("сидит · пристёгнут"))
                false -> listOf(CabinRun("сидит · "), CabinRun("не пристёгнут", CabinTone.ALERT))
                null -> listOf(CabinRun("сидит · ремень $NA"))
            }
            false -> listOf(CabinRun(if (belt == true) "пусто · пристёгнут" else "пусто", CabinTone.QUIET))
            null -> listOf(CabinRun(NA + when (belt) { true -> " · пристёгнут"; false -> " · не пристёгнут"; null -> "" }, CabinTone.QUIET))
        },
        small = false,
    )

    private fun row(text: String, small: Boolean, tone: CabinTone = CabinTone.PLAIN) = CabinRow(listOf(CabinRun(text, tone)), small)

    /** «левая: заряжает», a fault in orange. */
    private fun padRow(side: String, state: PadState?, small: Boolean): CabinRow = when (state) {
        null -> row("$side: $NA", small)
        PadState.FAULT -> CabinRow(listOf(CabinRun("$side: "), CabinRun(PAD_WORDS.getValue(state), CabinTone.ALERT)), small)
        else -> row("$side: ${PAD_WORDS.getValue(state)}", small)
    }

    /** «передний: холод · 0 °C», «задний: выкл»; a reminder after it in orange. */
    private fun fridgeRow(side: String, mode: FridgeMode?, set: DiagnosticExtra?, remind: Double?, car: CarReadings, small: Boolean): CabinRow {
        val head = when (mode) {
            null -> "$side: $NA"
            FridgeMode.OFF -> "$side: ${FRIDGE_WORDS.getValue(mode)}"
            else -> "$side: ${FRIDGE_WORDS.getValue(mode)} · ${cell(setPoint(mode, set?.let(car::extra)), 0, "°C")}"
        }
        val reminder = remind?.toInt()?.let { FRIDGE_REMINDERS[it] } ?: return row(head, small)
        return CabinRow(listOf(CabinRun("$head · "), CabinRun(reminder, CabinTone.ALERT)), small)
    }

    private fun setGet(mode: FridgeMode?, cold: DiagnosticExtra, hot: DiagnosticExtra, fresh: DiagnosticExtra): DiagnosticExtra? = when (mode) {
        FridgeMode.COLD -> cold
        FridgeMode.HOT -> hot
        FridgeMode.FRESH -> fresh
        FridgeMode.OFF, null -> null
    }

    /**
     * «Салон · Состояние» from the cabin's GETs (architecture 7.2; research cabin-signals-20261021.json):
     * the seats, the wheel, the pads and the fridges as drawn, and the plates' words as the boards
     * `scanner-sys-cabin` and `-parked` write them. The cluster's plate says the OEM warning
     * ([SystemsBuilder.clusterWarning]) and the HUD's switch.
     */
    fun cabin(car: CarReadings): Scheme.Cabin {
        fun x(e: DiagnosticExtra) = car.extra(e)
        val seats = SEATS.mapValues { (_, g) ->
            CabinSeat(
                occupied = seated(x(g.seated)),
                belt = twoState(x(g.belt), g.fastened, g.unfastened),
                heat = g.heat?.let { seatLevel(x(it)) },
                vent = g.vent?.let { seatLevel(x(it)) },
                lamp = g.lamp?.let { twoState(x(it), 2, 1) },
            )
        }
        val wheel = twoState(x(DiagnosticExtra.WHEEL_HEAT), 2, 1)
        val hud = twoState(x(DiagnosticExtra.HUD), 1, 2)
        val padLeft = pad(x(DiagnosticExtra.PAD_LEFT))
        val padRight = pad(x(DiagnosticExtra.PAD_RIGHT))
        val front = fridge(x(DiagnosticExtra.FRIDGE_FRONT))
        val rear = fridge(x(DiagnosticExtra.FRIDGE_REAR))
        fun onOff(v: Boolean?) = when (v) { true -> "вкл"; false -> "выкл"; null -> NA }
        val plates = buildMap {
            put(CabinPlace.CLUSTER, CabinPlate(CabinPlace.CLUSTER, "ПРИБОРЫ · ${tag(0x723)} · ${tag(0x7B0)}", listOf(
                row(SystemsBuilder.clusterWarning(x(DiagnosticExtra.OEM_WARNING)).text, small = false),
                row("проекция ${onOff(hud)}", small = true),
            )))
            put(CabinPlace.WHEEL, CabinPlate(CabinPlace.WHEEL, "РУЛЬ · ${tag(0x711)}", listOf(
                row("подогрев ${onOff(wheel)}", small = false, tone = if (wheel == true) CabinTone.WARM else CabinTone.QUIET),
            )))
            SEATS.forEach { (place, g) ->
                val seat = seats.getValue(place)
                val rows = mutableListOf(presence(seat.occupied, seat.belt))
                if (g.heat != null && g.mode != null && g.level != null) {
                    rows += row("подогрев ${levelWord(seat.heat)} · вентиляция ${levelWord(seat.vent)}", small = true)
                    rows += row(massage(x(g.mode), x(g.level)) + if (seat.lamp == true) " · свет для чтения" else "", small = true)
                }
                put(place, CabinPlate(place, g.label, rows))
            }
            put(CabinPlace.PADS, CabinPlate(CabinPlace.PADS, "БЕСПРОВОДНАЯ ЗАРЯДКА · ${tag(0x733)}", listOf(
                padRow("левая", padLeft, small = false),
                padRow("правая", padRight, small = true),
            )))
            put(CabinPlace.FRIDGES, CabinPlate(CabinPlace.FRIDGES, "ХОЛОДИЛЬНИКИ · ${tag(0x5A0)} · ${tag(0x7C6)}", listOf(
                fridgeRow("передний", front, setGet(front, DiagnosticExtra.FRIDGE_FRONT_COLD, DiagnosticExtra.FRIDGE_FRONT_HOT, DiagnosticExtra.FRIDGE_FRONT_FRESH),
                    x(DiagnosticExtra.FRIDGE_FRONT_REMIND), car, small = false),
                fridgeRow("задний", rear, setGet(rear, DiagnosticExtra.FRIDGE_REAR_COLD, DiagnosticExtra.FRIDGE_REAR_HOT, DiagnosticExtra.FRIDGE_REAR_FRESH),
                    x(DiagnosticExtra.FRIDGE_REAR_REMIND), car, small = true),
            )))
        }
        return Scheme.Cabin(
            seats = seats,
            wheelHeat = wheel,
            padLeft = padLeft,
            padRight = padRight,
            fridgeFront = front,
            fridgeRear = rear,
            left = CABIN_LEFT.map(plates::getValue),
            right = CABIN_RIGHT.map(plates::getValue),
        )
    }
}
