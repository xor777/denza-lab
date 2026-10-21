package dev.denza.apps.feature.diagnostics.protocol

import java.util.Locale

/** One number of one PID: value, unit and how many decimals the screen shows. */
internal data class ObdQuantity(val label: String?, val value: Double, val unit: String, val decimals: Int) {
    /** `16,9 %`, `9 °C`, `1,275 В`: Russian decimal comma, no «-0». */
    val text: String
        get() {
            var s = String.format(Locale.ROOT, "%.${decimals}f", value)
            if (s.trimStart('-').all { it == '0' || it == '.' }) s = s.trimStart('-')
            s = s.replace('.', ',')
            return if (unit.isEmpty()) s else "$s $unit"
        }
}

/** What one PID's data bytes say. */
internal sealed interface ObdValue {
    data class Numbers(val quantities: List<ObdQuantity>, val note: String? = null) : ObdValue
    /**
     * Words; [code] is the short form a table's value column takes («CL»), [note] what stands beside
     * it («замкн. петля») - a whole sentence does not fit a value column (on the car 2026-10-20 it ran
     * over the parameter's name).
     */
    data class Text(val text: String, val code: String? = null, val note: String? = null) : ObdValue
    /** PID 00/20/40/…: the PIDs this ECU offers in the next block of 32. */
    data class Supported(val pids: List<Int>) : ObdValue
    /** PID 01 and 41. */
    data class Monitors(val status: MonitorStatus) : ObdValue
    /** A PID this decoder does not know: only the bytes. */
    data object Raw : ObdValue
}

/** One PID of one `41` answer. */
internal data class PidReading(val pid: Int, val rawHex: String, val value: ObdValue) {
    val name: String get() = ObdPids.of(pid)?.name ?: "PID %02X".format(pid)
}

/** A `41` answer: what came, and which of the asked PIDs the ECU left out (it does not offer them). */
internal data class PidAnswer(val readings: List<PidReading>, val unanswered: List<Int>) {
    operator fun get(pid: Int): PidReading? = readings.firstOrNull { it.pid == pid }
}

/** SAE J1979 PID definitions: Russian name, data length, decoder. */
internal class ObdPid(val pid: Int, val name: String, val length: Int, val decode: (IntArray) -> ObdValue) {
    val hex: String get() = "%02X".format(pid)
}

internal enum class Ignition(val label: String) { SPARK("искровое зажигание"), COMPRESSION("воспламенение от сжатия") }

/** Readiness monitors of PID 01/41, SAE J1979. */
internal enum class Monitor(val label: String) {
    MISFIRE("пропуски воспламенения"),
    FUEL_SYSTEM("топливная система"),
    COMPONENTS("компоненты"),
    CATALYST("катализатор"),
    HEATED_CATALYST("подогрев катализатора"),
    EVAP("EVAP"),
    SECONDARY_AIR("вторичный воздух"),
    /** C4/D4 for spark ignition; older J1979 revisions listed the bit as A/C refrigerant or reserved. */
    GPF("фильтр частиц (GPF)"),
    O2_SENSOR("датчики кислорода"),
    O2_HEATER("подогрев датчиков кислорода"),
    EGR_VVT("EGR/VVT"),
    NMHC_CATALYST("катализатор NMHC"),
    NOX_SCR("NOx/SCR"),
    BOOST_PRESSURE("давление наддува"),
    EXHAUST_SENSOR("датчик ОГ"),
    PM_FILTER("сажевый фильтр"),
}

internal enum class MonitorState(val label: String) {
    READY("готов"),
    NOT_READY("не готов"),
    NOT_SUPPORTED("не поддерживается"),
    /** PID 41: supported, but not enabled in this drive cycle. */
    DISABLED("отключён в этой поездке"),
}

internal data class MonitorReadiness(val monitor: Monitor, val state: MonitorState)

/**
 * PID 01 (since codes were cleared) or 41 ([thisCycle], this drive cycle). In 41 the first byte is
 * reserved, so [mil] and [dtcCount] are null.
 */
internal data class MonitorStatus(
    val mil: Boolean?,
    val dtcCount: Int?,
    val ignition: Ignition,
    val thisCycle: Boolean,
    val monitors: List<MonitorReadiness>,
) {
    fun state(monitor: Monitor): MonitorState? = monitors.firstOrNull { it.monitor == monitor }?.state
}

internal object ObdPids {
    private fun pct(a: Int) = a * 100.0 / 255
    private fun trim(a: Int) = (a - 128) * 100.0 / 128
    private fun word(b: IntArray, i: Int) = b[i] shl 8 or b[i + 1]
    private fun one(value: Double, unit: String, decimals: Int) = ObdValue.Numbers(listOf(ObdQuantity(null, value, unit, decimals)))
    private fun percent(name: String, pid: Int) = ObdPid(pid, name, 1) { one(pct(it[0]), "%", 1) }
    private fun temperature(name: String, pid: Int) = ObdPid(pid, name, 1) { one(it[0] - 40.0, "°C", 0) }
    private fun fuelTrim(name: String, pid: Int) = ObdPid(pid, name, 1) { one(trim(it[0]), "%", 1) }

    private fun supported(base: Int) = ObdPid(base, "Поддерживаемые PID %02X–%02X".format(base + 1, base + 0x20), 4) { b ->
        val bits = b[0].toLong() shl 24 or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        ObdValue.Supported((0 until 32).filter { bits and (1L shl (31 - it)) != 0L }.map { base + it + 1 })
    }

    private val FUEL_SYSTEM = mapOf(
        0x00 to "не работает",
        0x01 to "разомкнутый контур: не прогрет",
        0x02 to "замкнутый контур по датчику O2",
        0x04 to "разомкнутый контур: нагрузка или торможение двигателем",
        0x08 to "разомкнутый контур: отказ системы",
        0x10 to "замкнутый контур, отказ датчика обратной связи",
    )

    /** PID 03 for a table: the loop's code (SAE J1979: OL open, CL closed) and a few words why. */
    private val FUEL_LOOP = mapOf(
        0x00 to ("—" to "не работает"),
        0x01 to ("OL" to "не прогрет"),
        0x02 to ("CL" to "замкн. петля"),
        0x04 to ("OL" to "нагрузка"),
        0x08 to ("OL" to "отказ системы"),
        0x10 to ("CL" to "отказ датчика"),
    )

    private val OBD_STANDARD = mapOf(
        1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD и OBD-II", 4 to "OBD-I", 5 to "не соответствует OBD",
        6 to "EOBD", 7 to "EOBD и OBD-II", 8 to "EOBD и OBD", 9 to "EOBD, OBD и OBD-II", 10 to "JOBD",
        11 to "JOBD и OBD-II", 12 to "JOBD и EOBD", 13 to "JOBD, EOBD и OBD-II",
    )

    private val DEFINITIONS: List<ObdPid> = listOf(
        supported(0x00), supported(0x20), supported(0x40), supported(0x60),
        supported(0x80), supported(0xA0), supported(0xC0), supported(0xE0),
        ObdPid(0x01, "Мониторы с момента сброса кодов", 4) { ObdValue.Monitors(Monitors.decode(it, thisCycle = false)) },
        ObdPid(0x03, "Состояние топливной системы", 2) { b ->
            fun name(v: Int) = FUEL_SYSTEM[v] ?: "код %02X".format(v)
            fun code(v: Int) = FUEL_LOOP[v]?.first ?: "%02X".format(v)
            fun note(v: Int) = FUEL_LOOP[v]?.second ?: "код %02X".format(v)
            if (b[1] == 0) ObdValue.Text(name(b[0]), code(b[0]), note(b[0]))
            else ObdValue.Text("1: ${name(b[0])}; 2: ${name(b[1])}", "${code(b[0])}/${code(b[1])}", "1: ${note(b[0])}; 2: ${note(b[1])}")
        },
        percent("Расчётная нагрузка", 0x04),
        temperature("Температура ОЖ", 0x05),
        fuelTrim("Краткосрочная коррекция B1", 0x06),
        fuelTrim("Долгосрочная коррекция B1", 0x07),
        ObdPid(0x0B, "Абс. давление во впуске", 1) { one(it[0].toDouble(), "кПа", 0) },
        ObdPid(0x0C, "Обороты коленвала", 2) { one(word(it, 0) / 4.0, "об/мин", 0) },
        ObdPid(0x0D, "Скорость автомобиля", 1) { one(it[0].toDouble(), "км/ч", 0) },
        ObdPid(0x0E, "Угол опережения зажигания", 1) { one(it[0] / 2.0 - 64, "°", 1) },
        temperature("Температура воздуха на впуске", 0x0F),
        percent("Положение дросселя", 0x11),
        ObdPid(0x13, "Датчики кислорода", 1) { b ->
            val present = (0 until 8).filter { b[0] and (1 shl it) != 0 }.map { "B%dS%d".format(it / 4 + 1, it % 4 + 1) }
            ObdValue.Text(if (present.isEmpty()) "нет" else present.joinToString(", "))
        },
        ObdPid(0x15, "Датчик кислорода B1S2", 2) { b ->
            val volts = ObdQuantity("напряжение", b[0] * 0.005, "В", 3)
            if (b[1] == 0xFF) ObdValue.Numbers(listOf(volts), "в коррекции не участвует")
            else ObdValue.Numbers(listOf(volts, ObdQuantity("коррекция", trim(b[1]), "%", 1)))
        },
        ObdPid(0x1C, "Стандарт OBD", 1) { b -> ObdValue.Text(OBD_STANDARD[b[0]]?.let { "$it (${b[0]})" } ?: "тип ${b[0]}") },
        ObdPid(0x1F, "Время с пуска двигателя", 2) { one(word(it, 0).toDouble(), "с", 0) },
        ObdPid(0x21, "Пробег с горящей MIL", 2) { one(word(it, 0).toDouble(), "км", 0) },
        ObdPid(0x23, "Давление в топливной рампе", 2) { one(word(it, 0) * 10.0, "кПа", 0) },
        percent("Заданная рециркуляция EGR", 0x2C),
        fuelTrim("Ошибка EGR", 0x2D),
        percent("Заданная продувка адсорбера", 0x2E),
        percent("Уровень топлива", 0x2F),
        ObdPid(0x30, "Прогревы после сброса кодов", 1) { one(it[0].toDouble(), "", 0) },
        ObdPid(0x31, "Пробег после сброса кодов", 2) { one(word(it, 0).toDouble(), "км", 0) },
        ObdPid(0x33, "Атмосферное давление", 1) { one(it[0].toDouble(), "кПа", 0) },
        ObdPid(0x34, "Датчик кислорода B1S1, широкополосный", 4) { b ->
            ObdValue.Numbers(listOf(
                ObdQuantity("λ", word(b, 0) * 2.0 / 65536, "", 3),
                ObdQuantity("ток", word(b, 2) / 256.0 - 128, "мА", 2),
            ))
        },
        ObdPid(0x3C, "Температура катализатора B1S1", 2) { one(word(it, 0) / 10.0 - 40, "°C", 1) },
        ObdPid(0x41, "Мониторы этой поездки", 4) { ObdValue.Monitors(Monitors.decode(it, thisCycle = true)) },
        ObdPid(0x42, "Напряжение ЭБУ", 2) { one(word(it, 0) / 1000.0, "В", 2) },
        ObdPid(0x43, "Абсолютная нагрузка", 2) { one(word(it, 0) * 100.0 / 255, "%", 1) },
        ObdPid(0x44, "Заданный коэффициент λ", 2) { one(word(it, 0) * 2.0 / 65536, "", 3) },
        percent("Относительное положение дросселя", 0x45),
        temperature("Температура окружающего воздуха", 0x46),
        percent("Положение дросселя B", 0x47),
        percent("Положение педали D", 0x49),
        percent("Положение педали E", 0x4A),
        percent("Заданное положение дросселя", 0x4C),
        ObdPid(0x54, "Давление паров EVAP", 2) { one(word(it, 0).toShort().toDouble(), "Па", 0) },
        ObdPid(0x56, "Долгосрочная коррекция по 2-му датчику O2", 2) { b ->
            ObdValue.Numbers(listOf(ObdQuantity("B1", trim(b[0]), "%", 1), ObdQuantity("B3", trim(b[1]), "%", 1)))
        },
        ObdPid(0x67, "Температура ОЖ, датчики", 3) { b ->
            ObdValue.Numbers(listOfNotNull(
                if (b[0] and 1 != 0) ObdQuantity("датчик 1", b[1] - 40.0, "°C", 0) else null,
                if (b[0] and 2 != 0) ObdQuantity("датчик 2", b[2] - 40.0, "°C", 0) else null,
            ))
        },
        ObdPid(0x6B, "Температура EGR", 5) { b ->
            if (b[0] and 0xF0 != 0) ObdValue.Raw
            else ObdValue.Numbers(listOf("B1S1", "B1S2", "B2S1", "B2S2").mapIndexedNotNull { i, label ->
                if (b[0] and (1 shl i) != 0) ObdQuantity(label, b[i + 1] - 40.0, "°C", 0) else null
            })
        },
        ObdPid(0x7A, "Фильтр частиц B1", 7) { b ->
            ObdValue.Numbers(listOfNotNull(
                if (b[0] and 1 != 0) ObdQuantity("перепад", word(b, 1).toShort() / 100.0, "кПа", 2) else null,
                if (b[0] and 2 != 0) ObdQuantity("на входе", word(b, 3) / 100.0, "кПа", 2) else null,
                if (b[0] and 4 != 0) ObdQuantity("на выходе", word(b, 5) / 100.0, "кПа", 2) else null,
            ))
        },
    )

    private val BY_PID = DEFINITIONS.associateBy { it.pid }

    val all: List<ObdPid> get() = DEFINITIONS.sortedBy { it.pid }

    fun of(pid: Int): ObdPid? = BY_PID[pid]

    /** Whether [pid] is one of the bitmap PIDs 00, 20, 40, … E0. */
    fun isBitmap(pid: Int) = pid and 0x1F == 0
}

internal object Monitors {
    private val CONTINUOUS = listOf(Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS)
    private val SPARK = listOf(
        Monitor.CATALYST, Monitor.HEATED_CATALYST, Monitor.EVAP, Monitor.SECONDARY_AIR,
        Monitor.GPF, Monitor.O2_SENSOR, Monitor.O2_HEATER, Monitor.EGR_VVT,
    )
    private val COMPRESSION = listOf(
        Monitor.NMHC_CATALYST, Monitor.NOX_SCR, null, Monitor.BOOST_PRESSURE,
        null, Monitor.EXHAUST_SENSOR, Monitor.PM_FILTER, Monitor.EGR_VVT,
    )

    /**
     * Bytes A-D of PID 01 or 41. B: bits 0-2 offered (enabled), bit 3 compression ignition, bits 4-6
     * not complete; C: offered (enabled); D: not complete.
     */
    fun decode(b: IntArray, thisCycle: Boolean): MonitorStatus {
        val ignition = if (b[1] and 0x08 != 0) Ignition.COMPRESSION else Ignition.SPARK
        fun state(offered: Boolean, incomplete: Boolean) = when {
            !offered -> if (thisCycle) MonitorState.DISABLED else MonitorState.NOT_SUPPORTED
            incomplete -> MonitorState.NOT_READY
            else -> MonitorState.READY
        }
        val continuous = CONTINUOUS.mapIndexed { i, m ->
            MonitorReadiness(m, state(b[1] and (1 shl i) != 0, b[1] and (0x10 shl i) != 0))
        }
        val others = (if (ignition == Ignition.SPARK) SPARK else COMPRESSION).mapIndexedNotNull { i, m ->
            m?.let { MonitorReadiness(it, state(b[2] and (1 shl i) != 0, b[3] and (1 shl i) != 0)) }
        }
        return MonitorStatus(
            mil = if (thisCycle) null else b[0] and 0x80 != 0,
            dtcCount = if (thisCycle) null else b[0] and 0x7F,
            ignition = ignition,
            thisCycle = thisCycle,
            monitors = continuous + others,
        )
    }
}

/** OBD modes 03, 07 and 0A. */
internal enum class ObdDtcMode(val service: Int, val label: String) {
    STORED(0x03, "сохранённые"),
    PENDING(0x07, "ожидающие"),
    PERMANENT(0x0A, "постоянные");

    companion object {
        fun ofService(service: Int): ObdDtcMode? = entries.firstOrNull { it.service == service }
    }
}

/** Codes of one OBD mode, SAE J2012 two-byte form (`P0420`). */
internal data class ObdDtcs(val mode: ObdDtcMode, val codes: List<String>) : MaybeEmpty {
    override val isEmpty: Boolean get() = codes.isEmpty()
}

/** OBD mode 09 answers the whitelist allows. */
internal sealed interface VehicleInfo {
    /** `09 00`: info types offered, 01-20. */
    data class Supported(val infoTypes: List<Int>) : VehicleInfo
    /** `09 04`: calibration identifiers, 16 ASCII bytes each. */
    data class CalibrationIds(val ids: List<String>) : VehicleInfo
    /** `09 0A`: the ECU's own name, 20 ASCII bytes. */
    data class EcuName(val name: String) : VehicleInfo
}

/** Bytes of OBD answers, positive SID included. */
internal object ObdCodec {
    /** `41 <pid> <data> [<pid> <data> …]`: one or several PIDs. */
    fun pids(uds: ByteArray): Decoded<List<PidReading>> {
        val b = uds.unsigned()
        if (b.isEmpty() || b[0] != 0x41) return Decoded.Malformed("not 41")
        if (b.size < 2) return Decoded.Malformed("41 without a PID")
        val out = ArrayList<PidReading>()
        var i = 1
        while (i < b.size) {
            val pid = b[i]
            val def = ObdPids.of(pid)
            if (def == null) {
                if (out.isNotEmpty() || i != 1) {
                    return Decoded.Malformed("PID %02X of unknown length inside a multi-PID answer".format(pid))
                }
                out += PidReading(pid, uds.copyOfRange(i + 1, b.size).hexString(), ObdValue.Raw)
                break
            }
            val end = i + 1 + def.length
            if (end > b.size) return Decoded.Malformed("PID %02X needs %d bytes, %d came".format(pid, def.length, b.size - i - 1))
            val data = b.copyOfRange(i + 1, end)
            out += PidReading(pid, uds.copyOfRange(i + 1, end).hexString(), def.decode(data))
            i = end
        }
        if (out.map { it.pid }.toSet().size != out.size) return Decoded.Malformed("a PID repeats")
        return Decoded.Ok(out)
    }

    /** `43|47|4A <n> <code>×n`. */
    fun dtcs(uds: ByteArray): Decoded<ObdDtcs> {
        val b = uds.unsigned()
        val mode = b.firstOrNull()?.let { ObdDtcMode.ofService(it - 0x40) } ?: return Decoded.Malformed("not 43, 47 or 4A")
        if (b.size < 2) return Decoded.Malformed("%02X without a count".format(b[0]))
        val n = b[1]
        if (b.size != 2 + 2 * n) return Decoded.Malformed("%02X counts %d codes in %d bytes".format(b[0], n, b.size - 2))
        val codes = (0 until n).map { J2012.base(b[2 + 2 * it] shl 8 or b[3 + 2 * it]) }
        if (codes.toSet().size != codes.size) return Decoded.Malformed("a code repeats")
        return Decoded.Ok(ObdDtcs(mode, codes))
    }

    /** `49 00 <bitmap>`, `49 04 <n> <16 bytes>×n`, `49 0A 01 <20 bytes>`. */
    fun vehicleInfo(uds: ByteArray): Decoded<VehicleInfo> {
        val b = uds.unsigned()
        if (b.size < 2 || b[0] != 0x49) return Decoded.Malformed("not 49")
        return when (b[1]) {
            0x00 -> {
                if (b.size != 6) return Decoded.Malformed("49 00 is ${b.size} bytes, not 6")
                val bits = b[2].toLong() shl 24 or (b[3].toLong() shl 16) or (b[4].toLong() shl 8) or b[5].toLong()
                Decoded.Ok(VehicleInfo.Supported((0 until 32).filter { bits and (1L shl (31 - it)) != 0L }.map { it + 1 }))
            }
            0x04 -> {
                if (b.size < 3 || b[2] == 0 || b.size != 3 + 16 * b[2]) return Decoded.Malformed("49 04 is not n × 16 bytes")
                val ids = (0 until b[2]).map { uds.copyOfRange(3 + 16 * it, 19 + 16 * it) }
                    .map { Ascii.trimmed(it) ?: return Decoded.Malformed("49 04 is not ASCII") }
                Decoded.Ok(VehicleInfo.CalibrationIds(ids))
            }
            0x0A -> {
                if (b.size != 23 || b[2] != 1) return Decoded.Malformed("49 0A is not one 20-byte name")
                val name = Ascii.trimmed(uds.copyOfRange(3, 23)) ?: return Decoded.Malformed("49 0A is not ASCII")
                Decoded.Ok(VehicleInfo.EcuName(name))
            }
            else -> Decoded.Malformed("info type %02X not asked".format(b[1]))
        }
    }
}
