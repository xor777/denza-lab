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
        clusterWarning = clusterWarning(s.car.extra(DiagnosticExtra.OEM_WARNING)),
    )

    private fun hub(car: CarReadings, signal: VehicleSignal, decimals: Int = 0) =
        car.hub[signal]?.let { Figures.number(it, decimals) }

    private fun extra(car: CarReadings, signal: DiagnosticExtra, decimals: Int = 0) =
        car.extra(signal)?.let { Figures.number(it, decimals) }

    fun source(signal: DiagnosticExtra) = "%d·%08X".format(signal.device, signal.fid)
    fun source(signal: VehicleSignal) = "%d·%08X".format(signal.device, signal.fid)

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
                ModuleStatus.UNIDENTIFIED -> "не опознан"
                ModuleStatus.INCONSISTENT -> "несогласованный ответ"
                ModuleStatus.NO_ROUTE -> null
            }
            return HvUnit(
                abbreviation = ecu.abbreviation,
                address = ecu.request?.let(Figures::address) ?: "—",
                name = name,
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
            // SMCU drives the left rear motor, SMCU3 the right; the sides' readings are on the machines' lines below.
            unit("SMCU", "Инвертор задний левый") { dtcs("SMCU") },
            unit("SMCU3", "Инвертор задний правый") { dtcs("SMCU3") },
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
            contactorClosed = when (car.extra(DiagnosticExtra.CONTACTOR)?.toInt()) { 1 -> true; 0 -> false; else -> null },
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
        val rows = PACK.map { r ->
            if (r.hub != null) {
                SignalRow(source(r.hub), r.name, hub(car, r.hub, r.decimals) ?: NA, r.unit, "—", null, false, ParamKey.Signal(r.key))
            } else if (r.words != null) {
                val v = car.extras[r.extra!!]
                SignalRow(source(r.extra), r.name, word(r.words, v), r.unit, "—", null, v != null && stale, ParamKey.Signal(r.key))
            } else {
                val v = car.extras[r.extra!!]
                val norm = r.norm?.invoke(car)
                SignalRow(
                    source = source(r.extra),
                    name = r.name,
                    // Nothing came, or a code the extra refused as the firmware's invalid: «н/д», not judged.
                    value = v?.let { Figures.number(it, r.decimals) } ?: NA,
                    unit = r.unit,
                    norm = norm?.text ?: "—",
                    ok = if (v != null && norm?.outside != null && !stale) !norm.outside.invoke(v) else null,
                    stale = v != null && stale,
                    key = ParamKey.Signal(r.key),
                )
            }
        }
        val spread = car.hub.spread()
        val calc = SignalRow("расчёт", "Разброс напряжений ячеек", spread?.let { Figures.number(it, 0) } ?: "—", "мВ", "—", null, false)
        val at = PACK.indexOfFirst { it.hub == VehicleSignal.CELL_MAX_MV } + 1
        return rows.take(at) + calc + rows.drop(at)
    }

    /**
     * One reading of the pack's «Потоковые данные»: a hub signal or a GET of the batch, its words,
     * and its norm against the car now. The parameter page names a reading the way its row does.
     */
    class PackReading(
        val name: String,
        val unit: String,
        val decimals: Int = 0,
        val extra: DiagnosticExtra? = null,
        val hub: VehicleSignal? = null,
        val norm: ((CarReadings) -> SignalNorm)? = null,
        /** A state: its codes' words as the firmware names them; a code not here is «код N». */
        val words: Map<Int, String>? = null,
    ) {
        /** The row's [ParamKey.Signal]: the extra's key or the hub signal's name. */
        val key: String get() = extra?.key ?: hub!!.name
        /** «1014·44600010». */
        val source: String get() = extra?.let { source(it) } ?: source(hub!!)
    }

    /** A norm: its words, the band a chart draws (null for none), what is outside it (null: not judged). */
    class SignalNorm(val text: String, val band: ClosedFloatingPointRange<Float>?, val outside: ((Double) -> Boolean)?)

    // 500 Ω per volt of the pack, the usual floor for an isolated HV system. kΩ is the closest known
    // unit - the GB/T 32960 field the stock collector fills from this FID - and is not proved on this
    // car (owner's decision 2026-10-21: keep kΩ and this floor; architecture 7.2). A code above 60000
    // never reaches here: DiagnosticExtra refuses it, so it is «н/д» and not judged.
    private val insulation: (CarReadings) -> SignalNorm = { car ->
        val floorKOhm = car.hub[VehicleSignal.PACK_VOLT]?.let { it * 0.5 }
        if (floorKOhm == null) {
            SignalNorm("≥ 500 Ом/В", null, null)
        } else {
            SignalNorm("≥ 500 Ом/В (≥ ${Figures.number(floorKOhm, 0)} кОм)", null) { it < floorKOhm }
        }
    }

    // The states' words (architecture 6.3), by the stock constants: BYDAutoOtaDevice.CONTACTOR_STATE_*,
    // BYDAutoChargingDevice.CHARGING_BATTERY_STATE_*, CHARGING_WORK_STATE_*, CHARGING_FAULT_STATE_*.
    val CONTACTOR_WORDS = mapOf(0 to "разомкнут", 1 to "замкнут")
    val PACK_CHARGE_WORDS = mapOf(
        0 to "готов", 1 to "заряд", 2 to "заряд завершён", 3 to "разряд", 4 to "заряд прерван",
        5 to "отказ C10", 6 to "отказ пистолета", 7 to "отказ зарядного", 8 to "отказ сети AC",
        9 to "по расписанию", 10 to "разряд CBU", 11 to "тайм-аут", 12 to "разряд завершён", 13 to "заряд на паузе",
    )
    val CHARGER_STATE_WORDS = mapOf(1 to "готово", 2 to "старт", 3 to "завершено", 4 to "прервано")
    val CHARGER_FAULT_WORDS = mapOf(1 to "норма", 2 to "лёгкий отказ", 3 to "тяжёлый отказ")

    /** The pack's readings in the board's order; the cells' spread («расчёт») follows the maximum. */
    val PACK: List<PackReading> = listOf(
        PackReading("Напряжение ВВБ", "В", hub = VehicleSignal.PACK_VOLT),
        PackReading("Ток ВВБ", "А", 1, extra = DiagnosticExtra.CHARGE_CURRENT),
        PackReading("Уровень заряда (SOC)", "%", extra = DiagnosticExtra.SOC),
        PackReading("Состояние (SOH), оценка BMS", "%", extra = DiagnosticExtra.SOH,
            norm = { SignalNorm("≥ 80", 80f..100f) { it < 80.0 } }),
        PackReading("Напряжение ячейки, мин.", "мВ", hub = VehicleSignal.CELL_MIN_MV),
        PackReading("Напряжение ячейки, макс.", "мВ", hub = VehicleSignal.CELL_MAX_MV),
        PackReading("Температура модулей, мин.", "°C", extra = DiagnosticExtra.TEMP_MIN),
        PackReading("Температура модулей, макс.", "°C", extra = DiagnosticExtra.TEMP_MAX),
        PackReading("Температура модулей, сред.", "°C", hub = VehicleSignal.PACK_TEMP_AVG),
        PackReading("Сопротивление изоляции, канал BMC", "кОм", extra = DiagnosticExtra.INSULATION, norm = insulation),
        PackReading("Сопротивление изоляции, канал 2", "кОм", extra = DiagnosticExtra.INSULATION_2, norm = insulation),
        PackReading("Ячеек последовательно", "шт", extra = DiagnosticExtra.CELL_COUNT),
        PackReading("Датчиков температуры", "шт", extra = DiagnosticExtra.PROBE_COUNT),
        PackReading("Главный контактор, разряд", "", extra = DiagnosticExtra.CONTACTOR, words = CONTACTOR_WORDS),
        PackReading("Состояние заряда батареи", "", extra = DiagnosticExtra.PACK_CHARGE_STATE, words = PACK_CHARGE_WORDS),
        PackReading("Зарядное устройство, состояние", "", extra = DiagnosticExtra.CHARGER_STATE, words = CHARGER_STATE_WORDS),
        PackReading("Зарядное устройство, отказ", "", extra = DiagnosticExtra.CHARGER_FAULT, words = CHARGER_FAULT_WORDS),
    )

    /**
     * The OEM warnings with a text, by ID (research oem-warning-mappings-20261021.json, the stock
     * popup dictionary `popup_nads.json` 20250408): our translation of the stock Chinese, not a
     * factory Russian text. An ID says what the cluster is asked to show, not which block failed.
     */
    val OEM_WARNING_WORDS = mapOf(
        0x1B to "Неисправность тормозной системы; безопасно остановитесь и обратитесь в сервис.",
        0x1E to "Слишком высокая температура охлаждающей жидкости ДВС; безопасно остановитесь и обратитесь в сервис.",
        0x20 to "Недостаточное давление масла; безопасно остановитесь и обратитесь в сервис.",
        0x21 to "Неисправность системы; безопасно остановитесь и обратитесь в сервис.",
        0x46 to "Проверьте систему ABS.",
        0x47 to "Проверьте систему полного привода.",
        0x5D to "Низкий заряд тяговой батареи; мощность привода ограничена.",
        0x358 to "Высокая температура батареи; ускорение ухудшено.",
    )

    /**
     * The cluster's OEM warning (`1007·26100008`) in words: 0 «нет сообщений», an ID with a text its
     * text, any other ID «сообщение 0x2A3»; above 1023 or a read that failed - [DiagnosticExtra.OEM_WARNING]
     * refuses both - «н/д».
     */
    fun clusterWarning(value: Double?): ClusterWarningView {
        val id = value?.toInt()?.takeIf { it in 0..1023 } ?: return ClusterWarningView(NA, null)
        val text = when (id) {
            0 -> "нет сообщений"
            else -> OEM_WARNING_WORDS[id] ?: "сообщение 0x%03X".format(id)
        }
        return ClusterWarningView(text, id)
    }

    /** A state's word: the firmware's name, «код N» for a code it does not name, «н/д» when nothing came. */
    fun word(words: Map<Int, String>, value: Double?): String {
        val code = value?.toInt() ?: return NA
        return words[code] ?: "код $code"
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
            columns = drives.map { CompareColumn(it.abbreviation, DRIVE_NAMES.getValue(it.abbreviation), it.request) },
            rows = listOf(
                // autoservice names its rear signals by side: left under SMCU, right under SMCU3, the motors they drive.
                row("Температура статора", "1039·46406018 · Л 285001A8 · П 285001B0", "°C",
                    h(VehicleSignal.MOTOR_FRONT_C), h(VehicleSignal.MOTOR_REAR_LEFT_C), h(VehicleSignal.MOTOR_REAR_RIGHT_C), "—"),
                row("Температура силовых ключей (IGBT/IPM)", "1039·46406010 · Л 2D000180 · П 2D000188", "°C",
                    h(VehicleSignal.INVERTER_C), e(DiagnosticExtra.IGBT_REAR_LEFT), e(DiagnosticExtra.IGBT_REAR_RIGHT), "—"),
                row("Напряжение шины DC", "1039·46407020 · задн. 46407010", "В",
                    e(DiagnosticExtra.BUS_FRONT), e(DiagnosticExtra.BUS_REAR), e(DiagnosticExtra.BUS_REAR), "—"),
                row("Обороты", "1012·44100008 · 1039 Л 0FC000D0 · П 0FC000E0", "об/мин",
                    e(DiagnosticExtra.RPM_FRONT), e(DiagnosticExtra.RPM_REAR_LEFT), e(DiagnosticExtra.RPM_REAR_RIGHT), "—"),
                row("Крутящий момент, факт.", "1012·44100018 · 1039 Л 2850002C · П 2850003C", "Н·м",
                    e(DiagnosticExtra.TORQUE_FRONT, 1), e(DiagnosticExtra.TORQUE_REAR_LEFT, 1), e(DiagnosticExtra.TORQUE_REAR_RIGHT, 1), "—"),
                row("Ток DC", "1009·46C00008 · 1039 Л 28500178 · П 28500188", "А",
                    e(DiagnosticExtra.CURRENT_FRONT, 1), e(DiagnosticExtra.CURRENT_REAR_LEFT, 1), e(DiagnosticExtra.CURRENT_REAR_RIGHT, 1), "—"),
                row("Коды неисправностей", "UDS 19 01 %02X · 19 02 %02X".format(drives[0].dtcMask, drives[0].dtcMask), "DTC", *drives.map(::dtc).toTypedArray()),
                row("Номер ПО поставщика", "UDS 22 F194", "", *drives.map(::f194).toTypedArray()),
                row("Версия ПО", "UDS 22 F195", "", *drives.map(::f195).toTypedArray()),
            ),
            notes = emptyList(),
        )
    }

    // ------------------------------------------------------------------ driver assistance

    private val DRIVE_NAMES = mapOf("FMCU" to "Передний", "SMCU" to "Задний левый", "SMCU3" to "Задний правый", "ISG" to "Мотор-генератор")

    private val ADAS = listOf(0x712, 0x7F2, 0x760, 0x761, 0x7C2, 0x7D4, 0x701)
    private val RADARS = setOf(0x760, 0x761, 0x7C2, 0x7D4)

    /** Where a corner radar sits, by the stock route names; the module itself does not say. */
    private val POSITION = mapOf(0x7F2 to "перед", 0x760 to "ПЛ", 0x761 to "ПП", 0x7C2 to "ЗП", 0x7D4 to "ЗЛ")

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
            columns = ecus.map { CompareColumn(it.abbreviation, it.shortName, it.request) },
            rows = listOf(
                CompareRow("Запрос → ответ", "", "", ecus.map { cell(Figures.route(it.request!!)) }),
                CompareRow("Номер ПО · F194", "UDS 22 F194", "", shared(results.map { r ->
                    r?.identity?.let { (it as? Polled.Got)?.value ?: ModelBuilder.word(it) } ?: "не читали"
                })),
                // the updater's identifier, which an update does not change; the snapshot's software
                // version would contradict an updated block, so it is not shown (5.1)
                CompareRow("Идентификатор BYD", "", "", shared(ecus.map(::cacheName))),
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
