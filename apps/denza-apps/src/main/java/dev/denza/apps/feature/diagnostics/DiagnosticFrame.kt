package dev.denza.apps.feature.diagnostics

import dev.denza.apps.feature.vehicle.EnergyReadouts
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DiagnosticNode(
    val value: String = "—", val caption: String = "Нет данных", val tone: String = "muted",
    val rows: List<Pair<String, String>> = emptyList(), val notes: List<String> = emptyList(),
)
internal data class DiagnosticFrame(
    val link: String = "Ожидаем телеметрию", val profile: String = "Профиль —", val park: String = "P —",
    val nodes: Map<String, DiagnosticNode> = emptyMap(), val records: List<DtcRecord> = emptyList(),
    val dtcRead: Boolean = false, val dtcTime: String = "Ещё не прочитаны", val lampTime: String = "Индикаторы ещё не прочитаны",
    val message: String = "DTC ещё не прочитаны", val running: Boolean = false,
    val availability: Int = 0, val format: Int = 0,
    val ecu: DtcEcu = DtcEcu.BODY, val lbms: DiagnosticDtcPanel? = null,
) {
    fun forEcu(target: DtcEcu): DiagnosticFrame = if (target == DtcEcu.BODY) this else copy(
        ecu = target, records = lbms?.records ?: emptyList(), dtcRead = lbms != null,
        dtcTime = lbms?.time ?: "Ещё не прочитаны", availability = lbms?.availability ?: 0, format = lbms?.format ?: 0,
    )
}
internal data class DiagnosticDtcPanel(val records: List<DtcRecord>, val time: String, val availability: Int, val format: Int)

/** Same snapshot identity is not a new read. A dropped sweep must never rejuvenate cold data. */
internal class DiagnosticTelemetryCache {
    private var previous: VehicleTelemetry? = null
    private val held = mutableMapOf<VehicleSignal, Pair<Double, Long>>()
    fun read(t: VehicleTelemetry, now: Long): VehicleTelemetry {
        if (t.access != VehicleAccess.READY) held.clear()
        else if (t !== previous && !t.dropped) t.values.forEach { (key, value) -> held[key] = value to now }
        previous = t
        held.entries.removeAll { (key, value) -> now - value.second >= (key.poll.staleSeconds * 1000).toLong() }
        return t.copy(values = held.mapValues { it.value.first })
    }
}

internal class DiagnosticFrameBuilder {
    private val energy = EnergyReadouts()
    fun build(t: VehicleTelemetry, s: DiagnosticStatus, nowElapsed: Long = 0): DiagnosticFrame {
        val extra = s.extras.fresh(nowElapsed)
        val indicators = if (nowElapsed >= s.extras.atElapsed && nowElapsed - s.extras.atElapsed < DiagnosticExtras.STALE_MS) s.indicators else DiagnosticIndicators()
        fun value(key: String) = extra[DiagnosticExtra.entries.first { it.key == key }]
        fun x(key: String, unit: String = "", digits: Int = 0): String = value(key)?.let {
            String.format(Locale.ROOT, "%.${digits}f", it).replace('.', ',') + if (unit.isEmpty()) "" else " $unit"
        } ?: "—"
        fun source(key: String): String = DiagnosticExtra.entries.first { it.key == key }.let { "${it.device} · FID %08X".format(it.fid) }
        fun sampled(key: String, unit: String, caption: String, digits: Int = 0) = DiagnosticNode(
            x(key, unit, digits), if (value(key) == null) "Нет свежих данных" else caption,
            if (value(key) == null) "muted" else "blue", notes = listOf(source(key), "Ответ: ${time(s.extras.atMillis)}"))
        energy.read(t, t.parked == true)
        fun number(signal: VehicleSignal, unit: String): String = t[signal]?.let {
            String.format(Locale.ROOT, if (it == it.toLong().toDouble()) "%.0f" else "%.1f", it).replace('.', ',') + " $unit"
        } ?: "—"
        fun point(signal: VehicleSignal, unit: String, caption: String) = DiagnosticNode(
            number(signal, unit), if (t[signal] == null) "Нет свежих данных" else caption,
            if (t[signal] == null) "muted" else "blue",
            listOf("Показание" to number(signal, unit), "FID" to "%08X".format(signal.fid), "Устройство" to signal.device.toString()),
            listOf("Источник: autoservice", "Порог неисправности не задан"),
        )
        fun lamp(l: DiagnosticLamp) = DiagnosticNode(indicators.label(l), "Индикатор · снимок", if (indicators.lit(l)) "amber" else "muted",
            listOf("Состояние" to indicators.detailLabel(l), "Ответ · raw" to indicators.raw(l), "FID" to "%08X".format(l.fid)),
            listOf(indicators.quality(l), "Снимок: ${time(indicators.atMillis)}", "Лампа не указывает причину отказа"))
        val n = mutableMapOf<String, DiagnosticNode>()
        n["pack"] = point(VehicleSignal.PACK_VOLT, "В", "Напряжение HV").copy(caption = "SOC ${x("soc", "%")} · SoH ${x("soh", "%")}", rows = listOf(
            "Напряжение" to (energy.voltsFigure?.let { "$it В" } ?: "—"),
            energy.wordSentence to (energy.powerFigure?.let { "$it кВт" } ?: "—"),
            "Заряд SOC" to x("soc", "%", 1),
            "SoH · оценка BMS" to x("soh", "%"),
            "Средняя T" to number(VehicleSignal.PACK_TEMP_AVG, "°C")), notes = listOf("SoH не означает исправность", "Низкий заряд: ${indicators.label(DiagnosticLamp.LOW_PACK)} · снимок", "DTC батареи не опрошены"))
        n["temp"] = point(VehicleSignal.PACK_TEMP_AVG, "°C", "Средняя по батарее").copy(rows = listOf(
            "Минимальная" to x("temp_min", "°C"), "Средняя" to number(VehicleSignal.PACK_TEMP_AVG, "°C"), "Максимальная" to x("temp_max", "°C")))
        n["lowv"] = sampled("lv", "В", "Питание низкого напряжения", 1).copy(rows = listOf(
            "Напряжение" to x("lv", "В", 1), "Память ошибок" to "LBMS · 0x76A"), notes = listOf(source("lv"), "Источник напряжения: Body SDK", "DTC низковольтной батареи → 76A"))
        n["motor"] = point(VehicleSignal.MOTOR_FRONT_C, "°C", "Передний мотор")
        n["inverter"] = point(VehicleSignal.INVERTER_C, "°C", "Передний инвертор").copy(rows = listOf(
            "Температура" to number(VehicleSignal.INVERTER_C, "°C"), "Шина передняя" to x("bus_front", "В"), "Шина задняя" to x("bus_rear", "В")),
            notes = listOf("Шины: GB SDK · dev 1039", "Порог неисправности не задан"))
        n["rearL"] = point(VehicleSignal.MOTOR_REAR_LEFT_C, "°C", "Температура мотора")
        n["rearR"] = point(VehicleSignal.MOTOR_REAR_RIGHT_C, "°C", "Температура мотора")
        val lo = t[VehicleSignal.CELL_MIN_MV]; val hi = t[VehicleSignal.CELL_MAX_MV]
        n["cells"] = DiagnosticNode(if (lo != null && hi != null && hi >= lo) "Δ ${(hi-lo).toInt()} мВ" else "—", "Разница min / max", if(lo != null && hi != null && hi >= lo) "blue" else "muted",
            listOf("Min" to number(VehicleSignal.CELL_MIN_MV, "мВ"), "Max" to number(VehicleSignal.CELL_MAX_MV, "мВ"),
                "Число ячеек" to x("cell_count"), "Изоляция · raw" to x("insulation")),
            listOf("Изоляция: единица не установлена", "Адреса ячеек не читаются", "Порог дисбаланса не задан"))
        n["charge"] = point(VehicleSignal.CHARGE_KW, "кВт", "Вход зарядки").copy(rows = listOf(
            "Мощность" to number(VehicleSignal.CHARGE_KW, "кВт"), "Ток · SDK" to x("charge_current", "А", 1)),
            notes = listOf(source("charge_current"), "Знак тока пока не интерпретируем", "DTC зарядного блока не опрошены"))
        n["generation"] = point(VehicleSignal.GENERATION_KW, "кВт", "От двигателя")
        n["engine"] = lamp(DiagnosticLamp.ENGINE).copy(rows = listOf(
            "Индикатор" to indicators.detailLabel(DiagnosticLamp.ENGINE),
            "ДВС" to when(t.engineRunning) { true -> "Работает"; false -> "Остановлен"; null -> "—" },
            "Обороты" to number(VehicleSignal.ENGINE_RPM, "об/мин"),
            "Мало топлива" to indicators.detailLabel(DiagnosticLamp.LOW_FUEL),
            "Топливо" to x("fuel", "%")), notes = listOf("Лампа не сообщает причину отказа", "ДВС: память EMS не прочитана", "Доп. лампы → Предупреждения ДВС"))
        fun warnings(lamps: List<DiagnosticLamp>, scope: String): DiagnosticNode {
            val known = lamps.count { it in indicators.values }
            val lit = lamps.count { indicators.lit(it) }
            return DiagnosticNode(if (known == 0) "Нет данных" else "$lit включено", "Лампы: $known/${lamps.size} доступны",
                if (lit > 0) "amber" else "muted", lamps.map { it.title to indicators.detailLabel(it) },
                listOf("Снимок: ${time(indicators.atMillis)}", "$scope: DTC не прочитаны", "Недоступно ≠ выключено"))
        }
        n["engine_warn"] = warnings(listOf(DiagnosticLamp.ENGINE, DiagnosticLamp.COOLANT_HIGH, DiagnosticLamp.OIL_PRESSURE, DiagnosticLamp.GPF), "EMS")
        n["power_warn"] = warnings(listOf(DiagnosticLamp.PACK_FAULT, DiagnosticLamp.PACK_HEAT, DiagnosticLamp.POWER_FAULT, DiagnosticLamp.LOW_PACK), "BMS / приводы")
        n["coolant"] = lamp(DiagnosticLamp.COOLANT_HIGH).copy(notes = listOf("Индикатор перегрева ОЖ", "Температура в °C не установлена", "EMS: DTC не прочитаны"))
        n["srs"] = lamp(DiagnosticLamp.SRS)
        n["abs"] = lamp(DiagnosticLamp.ABS).copy(tone = if (listOf(DiagnosticLamp.ABS, DiagnosticLamp.ESP, DiagnosticLamp.BRAKE, DiagnosticLamp.STEERING, DiagnosticLamp.TPMS).any { indicators.lit(it) }) "amber" else "muted", value = listOf(DiagnosticLamp.ABS, DiagnosticLamp.ESP).joinToString(" / ") { if (it in indicators.values) indicators.label(it) else "—" },
            rows = listOf("ABS · антиблокировка" to indicators.detailLabel(DiagnosticLamp.ABS), "ESP · стабилизация" to indicators.detailLabel(DiagnosticLamp.ESP),
                "Тормоза" to indicators.detailLabel(DiagnosticLamp.BRAKE), "Усилитель руля" to indicators.detailLabel(DiagnosticLamp.STEERING), "Давление шин" to indicators.detailLabel(DiagnosticLamp.TPMS)),
            notes = listOf("ABS / ESP: DTC не прочитаны",
                if (s.dtc?.format == 0 && s.dtc.records.any { it.code == "C12187" }) "U0121: запись из ECU 0x728" else "Лампы не заменяют память ECU",
                "ESP OFF: источник недоступен"))
        n["body"] = DiagnosticNode(s.dtc?.let { "${it.records.size} DTC" } ?: "Не опрошен", "Имя блока не уточнено", if(s.dtc?.records?.any{it.active || it.warning} == true) "amber" else "muted")
        n["lbms"] = DiagnosticNode(s.lbms?.let { "${it.records.size} DTC" } ?: "Не опрошен", "Низковольтная батарея", if(s.lbms?.records?.any { it.active || it.warning } == true) "amber" else "muted")
        for (wheel in listOf("fl", "fr", "rl", "rr")) {
            n[wheel] = sampled("${wheel}_pressure", "бар", x("${wheel}_tyre_temp", "°C"), 2).copy(rows = listOf(
                "Давление" to x("${wheel}_pressure", "бар", 2), "Температура" to x("${wheel}_tyre_temp", "°C"), "Индикатор TPMS" to indicators.label(DiagnosticLamp.TPMS)),
                notes = listOf(source("${wheel}_pressure"), "Общий индикатор для всех колёс", "Порог давления не задан"))
            fun state(suffix: String, labels: Map<Int, String>) = value("${wheel}_$suffix")?.toInt()?.let { labels[it] } ?: "—"
            val position = state("position", mapOf(1 to "Открыта", 2 to "Закрыта", 3 to "Приоткрыта"))
            n["door_$wheel"] = sampled("${wheel}_position", "", "Стекло: ${x("${wheel}_window", "%")}").copy(
                value = position, tone = if (value("${wheel}_thermal") == 1.0) "amber" else if(position == "—") "muted" else "blue",
                rows = listOf("Дверь" to position, "Замок" to state("lock", mapOf(1 to "Открыт", 2 to "Заперт")),
                    "Стекло · открыто" to x("${wheel}_window", "%"), "Обучение" to state("init", mapOf(1 to "Готово", 2 to "Требуется")),
                    "Термозащита" to state("thermal", mapOf(0 to "Не активна", 1 to "Активна"))),
                notes = listOf("0% — закрыто; 100% — открыто", "Ответ: ${time(s.extras.atMillis)}", "Только показания, без управления"))
        }
        n["cabin"] = sampled("cabin_temp", "°C", "Салон / наружный воздух").copy(rows = listOf(
            "В салоне" to x("cabin_temp", "°C"), "Снаружи" to x("outside_temp", "°C"),
            "PM2.5 · салон" to x("pm_in", "мкг/м³"), "PM2.5 · снаружи" to x("pm_out", "мкг/м³")),
            notes = listOf("Температура: HVAC SDK · 1000", "PM2.5: PM2p5 SDK · 1008", "Порог качества воздуха не задан"))
        n["uds"] = DiagnosticNode("2 ECU", "Чтение 0x19 по кнопке", "muted", listOf("Блок 720 → 728" to "Имя не доказано", "LBMS · ответ" to "0x76A", "Маска" to "0xFF"), listOf("Только профиль Z9GT · 170", "Перед запросом: P, 0 км/ч", "Сеансы и очистка не выполняются"))
        n["gateway"] = DiagnosticNode("${t.values.size + extra.size} сигналов", "autoservice · GET", if(t.values.isEmpty() && extra.isEmpty()) "muted" else "blue",
            listOf("Общий поток" to "${t.values.size} сигналов", "Дополнительные" to "${extra.size}/43", "Обновление" to "Каждые 5 с", "Устаревание" to "15 с", "DTC" to "По кнопке"),
            listOf("Давность ответа, не пакета CAN", "Показания исчезают при устаревании", "Во время DTC доп. опрос на паузе"))
        n["unread"] = DiagnosticNode("Не опрошены", "EMS / HV BMS / ABS / приводы", rows = listOf(
            "ДВС · EMS" to "Не опрошен", "HV батарея · BMS" to "Не опрошена", "ABS / ESP" to "Не опрошен",
            "Передний привод" to "Не опрошен", "Задние приводы" to "Не опрошены"),
            notes = listOf("Адреса ДВС / HV / ABS не доказаны", "Кандидаты в карте ≠ опрос ECU", "Заводской словарь не получен"))
        n["ecu_identity"] = DiagnosticEvidence.identity
        n["ecu_inventory"] = DiagnosticEvidence.inventory
        n["ecu_routes"] = DiagnosticEvidence.routes
        n["health"] = DiagnosticNode("Не определено", "Общее состояние", notes = listOf("Нет полного опроса ECU", "Отсутствие текущего бита DTC", "не подтверждает исправность"))
        return DiagnosticFrame(
            link = if (t.values.isNotEmpty() || extra.isNotEmpty()) "Показания · ${t.values.size + extra.size} сигналов" else "Нет свежих показаний",
            profile = "Профиль ${s.profile.ifBlank { "—" }}", park = when(t.parked){true -> "P";false -> "Не P";null -> "P —"},
            nodes = n, records = s.dtc?.records ?: emptyList(), dtcRead = s.dtc != null,
            dtcTime = s.dtc?.let { "Снимок ${time(it.atMillis)}" } ?: "Ещё не прочитаны",
            lampTime = "Ответ: ${time(s.extras.atMillis)} · ${s.dataMessage}", message = s.message, running = s.running, availability = s.dtc?.availability ?: 0, format = s.dtc?.format ?: 0,
            lbms = s.lbms?.let { DiagnosticDtcPanel(it.records, "Снимок ${time(it.atMillis)}", it.availability, it.format) })
    }
    private fun time(ms: Long): String = if (ms <= 0) "нет снимка" else SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date(ms))
}
