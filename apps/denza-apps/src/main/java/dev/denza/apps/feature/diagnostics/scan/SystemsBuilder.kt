package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.DtcFormat
import dev.denza.apps.feature.diagnostics.protocol.IdentDid
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.vehicle.VehicleSignal

/**
 * The system screens (architecture, 1): the high-voltage diagram, the pack, the drives and the
 * driver-assistance modules side by side. Values come from `autoservice` - the shared hub's sweep
 * and the five-second GET batch - and from the modules' own reads; each says where it came from,
 * and a value that did not come is «н/д», not zero. Names follow the boards «ВВ-схема», «ВВБ»,
 * «Электропривод» and «Ассистенты».
 */
internal object SystemsBuilder {
    private const val NA = "н/д"
    private val MUTED = setOf("—", NA, "не читали", "нет адреса", "нет ответа")

    fun build(s: SessionSnapshot, status: (SessionSnapshot, Ecu) -> ModuleStatus): SystemsView = SystemsView(
        hv = hv(s, status),
        pack = pack(s.car),
        drives = drives(s),
        adas = adas(s),
        brakeModules = listOf(0x782, 0x7E5),
    )

    private fun hub(car: CarReadings, signal: VehicleSignal, decimals: Int = 0) =
        car.hub[signal]?.let { Figures.number(it, decimals) }

    private fun extra(car: CarReadings, signal: DiagnosticExtra, decimals: Int = 0) =
        car.extra(signal)?.let { Figures.number(it, decimals) }

    private fun source(signal: DiagnosticExtra) = "%d·%08X".format(signal.device, signal.fid)
    private fun source(signal: VehicleSignal) = "%d·%08X".format(signal.device, signal.fid)

    // ------------------------------------------------------------------ high voltage

    private fun hv(s: SessionSnapshot, status: (SessionSnapshot, Ecu) -> ModuleStatus): HvView {
        val car = s.car
        fun or(v: String?) = v ?: NA
        val cellMin = car.hub[VehicleSignal.CELL_MIN_MV]
        val cellMax = car.hub[VehicleSignal.CELL_MAX_MV]
        val insulation = listOf(DiagnosticExtra.INSULATION, DiagnosticExtra.INSULATION_2).map { e ->
            car.extra(e)?.let { Figures.number(it / 1000.0, 1) } ?: NA
        }
        val pack = listOf(
            Field("Напряжение / ток", "${or(hub(car, VehicleSignal.PACK_VOLT))} В · ${or(extra(car, DiagnosticExtra.CHARGE_CURRENT, 1))} А"),
            Field("SOC / SOH", "${or(extra(car, DiagnosticExtra.SOC))} % · ${or(extra(car, DiagnosticExtra.SOH))} %"),
            Field("Ячейки, мин–макс", "${or(cellMin?.let { Figures.number(it, 0) })}–${or(cellMax?.let { Figures.number(it, 0) })} мВ"),
            Field("Разброс", "${or(car.hub.spread()?.let { Figures.number(it, 0) })} мВ"),
            Field("Температура", "${or(extra(car, DiagnosticExtra.TEMP_MIN))}…${or(extra(car, DiagnosticExtra.TEMP_MAX))} °C"),
            Field("Изоляция", "${insulation.joinToString(" · ")} МОм"),
        )
        fun unit(abbreviation: String, name: String, detail: (ModuleStatus) -> String?): HvUnit {
            val ecu = EcuCatalog.all.first { it.abbreviation == abbreviation }
            val st = status(s, ecu)
            val word = when (st) {
                ModuleStatus.OK -> "OK"
                ModuleStatus.HISTORY -> "история"
                ModuleStatus.ACTIVE -> "DTC активные"
                ModuleStatus.UNREAD -> "ошибки не читали"
                ModuleStatus.POLLING -> "опрос…"
                ModuleStatus.NO_REPLY -> "нет ответа"
                ModuleStatus.REFUSED -> "отказ"
                ModuleStatus.OTHER_ECU -> "другой блок"
                ModuleStatus.INCONSISTENT -> "несогласованный ответ"
                ModuleStatus.NO_ROUTE -> null
            }
            return HvUnit(
                abbreviation = ecu.abbreviation,
                address = ecu.request?.let(Figures::address) ?: "—",
                name = (if (ecu.inferred) "≈ " else "") + name,
                state = listOfNotNull(word, detail(st)).joinToString(" · ").ifEmpty { "нет адреса · в кэше" },
                status = st,
                target = ModelBuilder.target(ecu),
            )
        }
        fun dtcs(abbreviation: String): String? {
            val r = EcuCatalog.all.first { it.abbreviation == abbreviation }.request?.let { s.results[it] } ?: return null
            if (!r.memoryRead) return null
            return "DTC ${(r.active ?: 0) + (r.history ?: 0)}"
        }
        val ecm = s.results[0x7E0]
        val units = listOf(
            unit("FMCU", "Инвертор передний") { car.hub[VehicleSignal.INVERTER_C]?.let { "IPM ${Figures.number(it, 0)} °C" } },
            unit("ISG", "Мотор-генератор") { dtcs("ISG") },
            // Which rear motor each SMCU drives is not established: their boxes carry no side and no
            // per-side signal; the sides' readings are on the machines' lines below.
            unit("SMCU", "Инвертор задний") { dtcs("SMCU") },
            unit("SMCU3", "Инвертор задний") { dtcs("SMCU3") },
            unit("ECM", "ДВС") { ecm?.mil?.let { if (it) "MIL ГОРИТ" else "MIL ВЫКЛ" } },
            unit("PTC", "Нагреватель ВВ") { dtcs("PTC") },
            unit("DCOBC", "Зарядное + DC-DC") { dtcs("DCOBC") },
            unit("LBMS", "АКБ 12 В" + (extra(car, DiagnosticExtra.LV, 1)?.let { " · $it В" } ?: "")) { dtcs("LBMS") },
        )
        val running = car.engineRunning
        val coolant = s.streams[0x7E0]?.view(false, false, running)?.rows?.firstOrNull { it.pid == 0x05 }?.value?.takeIf { it != "—" }
        fun machine(stator: VehicleSignal, rpm: DiagnosticExtra, torque: DiagnosticExtra, igbt: DiagnosticExtra? = null) =
            listOfNotNull(
                "статор ${or(hub(car, stator))} °C",
                igbt?.let { "IGBT ${or(extra(car, it))} °C" },
                "${or(extra(car, rpm))} об/мин",
                "${or(extra(car, torque))} Н·м",
            ).joinToString(" · ")
        return HvView(
            pack = pack,
            packStatus = status(s, EcuCatalog.byRequest(0x7E7)!!),
            busVoltages = listOf(DiagnosticExtra.BUS_FRONT, DiagnosticExtra.BUS_REAR).map { "${or(extra(car, it))} В" },
            units = units,
            machines = listOf(
                Field("Передний мотор", machine(VehicleSignal.MOTOR_FRONT_C, DiagnosticExtra.RPM_FRONT, DiagnosticExtra.TORQUE_FRONT)),
                Field("Задний левый мотор", machine(VehicleSignal.MOTOR_REAR_LEFT_C, DiagnosticExtra.RPM_REAR_LEFT,
                    DiagnosticExtra.TORQUE_REAR_LEFT, DiagnosticExtra.IGBT_REAR_LEFT)),
                Field("Задний правый мотор", machine(VehicleSignal.MOTOR_REAR_RIGHT_C, DiagnosticExtra.RPM_REAR_RIGHT,
                    DiagnosticExtra.TORQUE_REAR_RIGHT, DiagnosticExtra.IGBT_REAR_RIGHT)),
                Field(
                    when (running) { true -> "ДВС работает"; false -> "ДВС не работает"; null -> "ДВС" },
                    "${or(hub(car, VehicleSignal.ENGINE_RPM))} об/мин · ОЖ ${coolant ?: NA} °C",
                ),
            ),
        )
    }

    private fun Map<VehicleSignal, Double>.spread(): Double? {
        val min = this[VehicleSignal.CELL_MIN_MV] ?: return null
        val max = this[VehicleSignal.CELL_MAX_MV] ?: return null
        return max - min
    }

    // ------------------------------------------------------------------ pack

    private fun pack(car: CarReadings): List<SignalRow> {
        val stale = !car.extrasFresh
        fun hubRow(signal: VehicleSignal, name: String, unit: String, decimals: Int = 0) =
            SignalRow(source(signal), name, hub(car, signal, decimals) ?: "—", unit, "—", null, false)
        fun extraRow(signal: DiagnosticExtra, name: String, unit: String, decimals: Int = 0, norm: String = "—", ok: ((Double) -> Boolean)? = null): SignalRow {
            val v = car.extras[signal]
            return SignalRow(
                source = source(signal),
                name = name,
                value = v?.let { Figures.number(it, decimals) } ?: "—",
                unit = unit,
                norm = norm,
                ok = if (v != null && ok != null && !stale) ok(v) else null,
                stale = v != null && stale,
            )
        }
        val volts = car.hub[VehicleSignal.PACK_VOLT]
        // 500 Ω per volt of the pack, the usual floor for an isolated HV system; the field's unit
        // is not proven on this car (likely kΩ, GB/T 32960), which the board's «¹» says.
        val floorKOhm = volts?.let { it * 0.5 }
        val insulationNorm = floorKOhm?.let { "≥ 500 Ом/В (≥ ${Figures.number(it, 0)} кОм)" } ?: "≥ 500 Ом/В"
        val insulationOk: ((Double) -> Boolean)? = floorKOhm?.let { floor -> { v: Double -> v >= floor } }
        val spread = car.hub.spread()
        return listOf(
            hubRow(VehicleSignal.PACK_VOLT, "Напряжение ВВБ", "В"),
            extraRow(DiagnosticExtra.CHARGE_CURRENT, "Ток ВВБ", "А", 1),
            extraRow(DiagnosticExtra.SOC, "Уровень заряда (SOC)", "%"),
            extraRow(DiagnosticExtra.SOH, "Состояние (SOH), оценка BMS", "%", norm = "≥ 80", ok = { it >= 80.0 }),
            hubRow(VehicleSignal.CELL_MIN_MV, "Напряжение ячейки, мин.", "мВ"),
            hubRow(VehicleSignal.CELL_MAX_MV, "Напряжение ячейки, макс.", "мВ"),
            SignalRow("расчёт", "Разброс напряжений ячеек", spread?.let { Figures.number(it, 0) } ?: "—", "мВ", "—", null, false),
            extraRow(DiagnosticExtra.TEMP_MIN, "Температура модулей, мин.", "°C"),
            extraRow(DiagnosticExtra.TEMP_MAX, "Температура модулей, макс.", "°C"),
            hubRow(VehicleSignal.PACK_TEMP_AVG, "Температура модулей, сред.", "°C"),
            extraRow(DiagnosticExtra.INSULATION, "Сопротивление изоляции, канал BMC", "кОм¹", norm = insulationNorm, ok = insulationOk),
            extraRow(DiagnosticExtra.INSULATION_2, "Сопротивление изоляции, канал 2", "кОм¹", norm = insulationNorm, ok = insulationOk),
            extraRow(DiagnosticExtra.CELL_COUNT, "Ячеек последовательно", "шт"),
            extraRow(DiagnosticExtra.PROBE_COUNT, "Датчиков температуры", "шт"),
            extraRow(DiagnosticExtra.CONTACTOR, "Главный контактор, разряд", "сост.", norm = "значения не расшифрованы"),
        )
    }

    // ------------------------------------------------------------------ drives

    private fun cell(text: String, shared: Boolean = false, warn: Boolean = false) = CompareCell(text, text in MUTED, shared, warn)

    private fun drives(s: SessionSnapshot): CompareView {
        val car = s.car
        val drives = listOf("FMCU", "SMCU", "SMCU3", "ISG").map { a -> EcuCatalog.all.first { it.abbreviation == a } }
        fun h(signal: VehicleSignal) = hub(car, signal) ?: NA
        fun e(signal: DiagnosticExtra, decimals: Int = 0) = extra(car, signal, decimals) ?: NA
        fun row(name: String, source: String, unit: String, vararg values: String) =
            CompareRow(name, source, unit, values.map { cell(it) })
        fun dtc(ecu: Ecu): String {
            val r = s.results[ecu.request] ?: return "не читали"
            return if (r.memoryRead) ((r.active ?: 0) + (r.history ?: 0)).toString() else ModelBuilder.word(r.memory)
        }
        fun f194(ecu: Ecu) = s.results[ecu.request]?.identity?.let { (it as? Polled.Got)?.value ?: ModelBuilder.word(it) } ?: "не читали"
        fun f195(ecu: Ecu) = s.idents[ecu.request]?.dids?.get(IdentDid.F195)?.let(ModelBuilder::identText) ?: "не читали"
        return CompareView(
            // Named by abbreviation: which rear motor each SMCU drives is not established.
            columns = drives.map { CompareColumn(it.abbreviation, DRIVE_NAMES.getValue(it.abbreviation), it.request) },
            rows = listOf(
                // autoservice names its rear signals by side; left under SMCU and right under SMCU3 is
                // an assumption, and this row says so.
                row("Сторона сигналов autoservice", "", "", "передний", "задний левый ≈", "задний правый ≈", "—"),
                row("Температура статора", "1039 · 46406018 · 285001A8 · 285001B0", "°C",
                    h(VehicleSignal.MOTOR_FRONT_C), h(VehicleSignal.MOTOR_REAR_LEFT_C), h(VehicleSignal.MOTOR_REAR_RIGHT_C), "—"),
                row("Температура силовых ключей (IGBT/IPM)", "1039 · 46406010 · 2D000180 · 2D000188", "°C",
                    h(VehicleSignal.INVERTER_C), e(DiagnosticExtra.IGBT_REAR_LEFT), e(DiagnosticExtra.IGBT_REAR_RIGHT), "—"),
                row("Напряжение шины DC", "1039 · 46407020 · 46407010", "В",
                    e(DiagnosticExtra.BUS_FRONT), e(DiagnosticExtra.BUS_REAR), e(DiagnosticExtra.BUS_REAR), "—"),
                row("Обороты", "1012 · 44100008 · 1039 · 0FC000D0 · 0FC000E0", "об/мин",
                    e(DiagnosticExtra.RPM_FRONT), e(DiagnosticExtra.RPM_REAR_LEFT), e(DiagnosticExtra.RPM_REAR_RIGHT), "—"),
                row("Крутящий момент, факт.", "1012 · 44100018 · 1039 · 2850002C · 2850003C", "Н·м",
                    e(DiagnosticExtra.TORQUE_FRONT, 1), e(DiagnosticExtra.TORQUE_REAR_LEFT, 1), e(DiagnosticExtra.TORQUE_REAR_RIGHT, 1), "—"),
                row("Ток DC", "1009 · 46C00008 · 1039 · 28500178 · 28500188", "А",
                    e(DiagnosticExtra.CURRENT_FRONT, 1), e(DiagnosticExtra.CURRENT_REAR_LEFT, 1), e(DiagnosticExtra.CURRENT_REAR_RIGHT, 1), "—"),
                row("Коды неисправностей", "UDS 19 01 %02X".format(drives[0].dtcMask), "DTC", *drives.map(::dtc).toTypedArray()),
                row("Номер ПО поставщика", "UDS 22 F194", "", *drives.map(::f194).toTypedArray()),
                row("Версия ПО", "UDS 22 F195", "", *drives.map(::f195).toTypedArray()),
            ),
            notes = listOf(
                "Какой задний мотор у SMCU, а какой у SMCU3, не установлено: левые сигналы autoservice стоят под SMCU, правые — под SMCU3 по допущению (≈).",
                "Ток и момент — со знаком, как их отдаёт autoservice; на стоянке 0.",
            ),
        )
    }

    // ------------------------------------------------------------------ driver assistance

    private val DRIVE_NAMES = mapOf("FMCU" to "Передний", "SMCU" to "Задний", "SMCU3" to "Задний", "ISG" to "Мотор-генератор")

    private val ADAS = listOf(0x712, 0x7F2, 0x760, 0x761, 0x7C2, 0x7D4, 0x701)
    private val RADARS = setOf(0x760, 0x761, 0x7C2, 0x7D4)

    /** Where a corner radar sits, by the stock route names; the module itself does not say. */
    private val POSITION = mapOf(0x7F2 to "перед", 0x760 to "ПЛ ≈", 0x761 to "ПП ≈", 0x7C2 to "ЗП ≈", 0x7D4 to "ЗЛ ≈")

    private fun adas(s: SessionSnapshot): CompareView {
        val ecus = ADAS.map { EcuCatalog.byRequest(it)!! }
        fun shared(values: List<String>): List<CompareCell> {
            val radarValues = ecus.indices.filter { ecus[it].request in RADARS }.map { values[it] }.toSet()
            val same = radarValues.size == 1 && radarValues.first() !in MUTED
            return ecus.mapIndexed { i, e -> cell(values[i], shared = same && e.request in RADARS) }
        }
        fun cacheName(e: Ecu): String = when {
            e.aliases.size > 3 -> "${e.aliases.size} вариантов"
            e.aliases.size > 1 -> "${e.abbreviation} · ${e.aliases.size} вар."
            e.aliases.size == 1 -> e.aliases[0].ecuId.removePrefix("HTH-2021_").replace(Regex("_\\d{8}$"), "")
            else -> "—"
        }
        val results = ecus.map { s.results[it.request] }
        val memories = results.map { (it?.memory as? Polled.Got)?.value }
        return CompareView(
            columns = ecus.map { CompareColumn(it.abbreviation, (if (it.inferred) "≈ " else "") + it.shortName, it.request) },
            rows = listOf(
                CompareRow("Запрос → ответ", "", "", ecus.map { cell(Figures.route(it.request!!)) }),
                CompareRow("Номер ПО · F194", "UDS 22 F194", "", shared(results.map { r ->
                    r?.identity?.let { (it as? Polled.Got)?.value ?: ModelBuilder.word(it) } ?: "не читали"
                })),
                CompareRow("Имя в кэше BYD", "", "", shared(ecus.map(::cacheName))),
                CompareRow("Версия ПО · кэш", "", "", shared(ecus.map { it.aliases.firstOrNull()?.softwareVersion ?: "—" })),
                CompareRow("Положение", "", "", ecus.map { cell(POSITION[it.request] ?: "—") }),
                // ADC also has a DoIP route in the stock route map; the rest are CAN only.
                CompareRow("Связь", "", "", ecus.map { if (it.request == 0x712) cell("CAN · DoIP", warn = true) else cell("CAN") }),
                CompareRow("Доступность DTC", "UDS 19 01", "", memories.map { cell(it?.availability?.hex ?: "—") }),
                CompareRow("Формат DTC", "UDS 19 01", "", memories.map { m ->
                    when (m?.format) {
                        null -> cell("—")
                        DtcFormat.SAE_J2012_00 -> cell("00 · SAE")
                        DtcFormat.ISO_14229_1 -> cell("01 · не SAE", warn = true)
                        else -> cell("%02X".format(m.format.id.coerceAtLeast(0)), warn = true)
                    }
                }),
                CompareRow("DTC · маска 09", "UDS 19 02 09", "DTC", results.mapIndexed { i, r ->
                    cell(memories[i]?.records?.size?.toString() ?: r?.memory?.let(ModelBuilder::word) ?: "не читали")
                }),
            ),
            notes = listOf(
                "Четыре угловых радара отвечают одним номером ПО: положение каждого — по маршрутам стока, сам блок его не называет.",
                "У ADC формат DTC 01 (ISO 14229-1): коды без буквы системы, их нельзя читать как P/C/B/U.",
            ),
        )
    }
}
