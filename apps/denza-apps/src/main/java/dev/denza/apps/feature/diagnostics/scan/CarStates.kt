package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.vehicle.VehicleSignal

/**
 * A state's words: the codes the firmware names, short and Russian as the boards write them, and what
 * any other code the reading admits says - «код N» unless [other] says otherwise.
 */
internal class StateWords(val named: Map<Int, String>, private val other: String? = null) {
    /** [value]'s word; «н/д» when nothing came. */
    fun word(value: Double?): String {
        val code = value?.toInt() ?: return NA
        return named[code] ?: other ?: "код $code"
    }

    private companion object {
        const val NA = "н/д"
    }
}

/**
 * The words of every enumerated reading the scanner shows (architecture 6, 7.2) - the GETs of the
 * batch and the hub's states - as research/vehicle-diagnostics/stream-selection-20261021.json names
 * them; [CarReadings.word] says a reading in them. A code the card does not name is «н/д» where the
 * reading's range refuses it, «код N» where it admits it. Numbers - the blower's step, the air's
 * quality steps - have no words.
 */
internal object CarStates {
    private const val NA = "н/д"

    private fun words(vararg named: Pair<Int, String>, other: String? = null) = StateWords(named.toMap(), other)

    // ---- high voltage and 12 V
    val POWER_LEVEL = words(0 to "выкл", 1 to "ACC", 2 to "ON", 3 to "OK (готов)", 4 to "OK (ложный)")
    val DCDC_MODE = words(0 to "выключен", 1 to "повышение", 2 to "понижение")
    /** The hub's charge port, `CHARGING_GUN_STATE_CONNECTED_*`: 1..5; the hub's wider gate lets 0 and 6..8 by, which are none. */
    val CHARGE_GUN = words(1 to "не подключён", 2 to "AC", 3 to "DC", 4 to "AC + DC", 5 to "V2L", other = NA)
    /** The energy system's generation, the hub's; 0 is the stock INVALID, never «off» ([SystemPagesBuilder.GENERATION_WORDS]). */
    val GENERATION = StateWords(SystemPagesBuilder.GENERATION_WORDS, other = NA)

    // ---- drives and chassis
    val GEAR_SELECTOR = words(1 to "P", 2 to "R", 3 to "N", 4 to "D", 5 to "M", 6 to "S")
    val BRAKE_SWITCH = words(0 to "не нажата", 1 to "нажата", 2 to "нажата", 3 to "не нажата")
    val EPB = words(0 to "отпускается", 1 to "отпущен", 2 to "затягивается", 3 to "затянут", 4 to "отказ")
    val SUSPENSION_PROCESS = words(1 to "подъём", 2 to "опускание", 3 to "завершено", 4 to "прервано")
    val SUSPENSION_LEVEL = words(1 to "низкий", 2 to "средний", 3 to "высокий")
    val TPMS_STATE = words(0 to "норма", 1 to "самопроверка", 2 to "нет сигнала", 3 to "отказ", 4 to "отключена")
    val TPMS_BATTERY = words(0 to "норма", 1 to "разряжены")
    val TYRE_TEMP_STATE = words(0 to "норма", 1 to "очень высокая", 2 to "высокая", 3 to "датчики спят")
    val LEAK = words(0 to "норма", 1 to "быстрая утечка", 2 to "медленная утечка")
    val PRESSURE_STATE = words(0 to "норма", 1 to "давление высокое", 2 to "давление низкое")

    // ---- safety
    val BELT_FRONT = words(0 to "не пристёгнут", 1 to "пристёгнут")
    val BELT_REAR = words(1 to "не пристёгнут", 2 to "пристёгнут")
    /** Who sits, as DiCarServer reads it: 2 seated, any other code not. */
    val SEATED = words(2 to "сидит", other = "нет")

    // ---- body
    val TURN_LIGHTS = words(
        1 to "выкл", 2 to "левый", 3 to "левый, быстро", 4 to "правый", 5 to "правый, быстро", 6 to "аварийная",
        7 to "экстренное торможение", 8 to "угроза сзади", 9 to "быстрое мигание",
    )
    val STOP_LAMP = words(1 to "горит", 2 to "мигает", 3 to "не горит")
    val REVERSE_LAMP = words(1 to "горит", 2 to "не горит")
    val LAMP = words(0 to "выкл", 1 to "вкл")
    val LID = words(0 to "закрыт", 1 to "открыт")

    // ---- climate
    val ON_OFF = words(0 to "выкл", 1 to "вкл")
    val AIR_INTAKE = words(0 to "наружный воздух", 1 to "рециркуляция")
    val SENSOR = words(0 to "исправен", 1 to "отказ")

    private val BY_EXTRA: Map<DiagnosticExtra, StateWords> = buildMap {
        put(DiagnosticExtra.POWER_LEVEL, POWER_LEVEL)
        put(DiagnosticExtra.DCDC_MODE, DCDC_MODE)
        put(DiagnosticExtra.CONTACTOR, StateWords(SystemsBuilder.CONTACTOR_WORDS))
        put(DiagnosticExtra.PACK_CHARGE_STATE, StateWords(SystemsBuilder.PACK_CHARGE_WORDS))
        put(DiagnosticExtra.CHARGER_STATE, StateWords(SystemsBuilder.CHARGER_STATE_WORDS))
        put(DiagnosticExtra.CHARGER_FAULT, StateWords(SystemsBuilder.CHARGER_FAULT_WORDS))
        put(DiagnosticExtra.GEAR_SELECTOR, GEAR_SELECTOR)
        put(DiagnosticExtra.BRAKE_SWITCH, BRAKE_SWITCH)
        put(DiagnosticExtra.EPB, EPB)
        put(DiagnosticExtra.SUSPENSION_PROCESS, SUSPENSION_PROCESS)
        put(DiagnosticExtra.SUSPENSION_LEVEL, SUSPENSION_LEVEL)
        put(DiagnosticExtra.TPMS_STATE, TPMS_STATE)
        put(DiagnosticExtra.TPMS_BATTERY, TPMS_BATTERY)
        put(DiagnosticExtra.TYRE_TEMP_STATE, TYRE_TEMP_STATE)
        listOf(DiagnosticExtra.FL_LEAK, DiagnosticExtra.FR_LEAK, DiagnosticExtra.RL_LEAK, DiagnosticExtra.RR_LEAK).forEach { put(it, LEAK) }
        listOf(DiagnosticExtra.FL_PRESSURE_STATE, DiagnosticExtra.FR_PRESSURE_STATE, DiagnosticExtra.RL_PRESSURE_STATE, DiagnosticExtra.RR_PRESSURE_STATE)
            .forEach { put(it, PRESSURE_STATE) }
        listOf(DiagnosticExtra.BELT_FL, DiagnosticExtra.BELT_FR).forEach { put(it, BELT_FRONT) }
        listOf(DiagnosticExtra.BELT_RL, DiagnosticExtra.BELT_RC, DiagnosticExtra.BELT_RR).forEach { put(it, BELT_REAR) }
        listOf(DiagnosticExtra.SEATED_FL, DiagnosticExtra.SEATED_FR, DiagnosticExtra.SEATED_RL, DiagnosticExtra.SEATED_RC, DiagnosticExtra.SEATED_RR)
            .forEach { put(it, SEATED) }
        put(DiagnosticExtra.TURN_LIGHTS, TURN_LIGHTS)
        put(DiagnosticExtra.STOP_LAMP, STOP_LAMP)
        put(DiagnosticExtra.REVERSE_LAMP, REVERSE_LAMP)
        listOf(DiagnosticExtra.LOW_BEAM, DiagnosticExtra.HIGH_BEAM, DiagnosticExtra.SIDE_LIGHTS).forEach { put(it, LAMP) }
        listOf(DiagnosticExtra.HOOD, DiagnosticExtra.TRUNK).forEach { put(it, LID) }
        listOf(DiagnosticExtra.AC_POWER, DiagnosticExtra.AC_COMPRESSOR).forEach { put(it, ON_OFF) }
        put(DiagnosticExtra.AC_RECIRCULATION, AIR_INTAKE)
        listOf(DiagnosticExtra.HUMIDITY_SENSOR, DiagnosticExtra.SUN_SENSOR).forEach { put(it, SENSOR) }
    }

    private val BY_HUB: Map<VehicleSignal, StateWords> = mapOf(
        VehicleSignal.CHARGE_GUN to CHARGE_GUN,
        VehicleSignal.GENERATION_STATE to GENERATION,
    )

    /** The words of an enumerated GET; null for a number. */
    fun of(extra: DiagnosticExtra): StateWords? = BY_EXTRA[extra]

    /** The words of a hub state; null for a number. */
    fun of(signal: VehicleSignal): StateWords? = BY_HUB[signal]
}
