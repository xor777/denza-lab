package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.vehicle.VehicleSignal

/**
 * One row of a system's «Потоковые данные» (architecture 7.2): a GET of the batch, a hub signal, or a
 * figure computed from the hub's sweep ([calc], «расч.»). A state says the words [CarStates] gives its
 * source; a count says its number; any other number keeps its minute. The parameter page a row opens
 * is titled as the row: a reading has one name in every table it stands in.
 */
internal class StreamReading(
    val name: String,
    val unit: String,
    val decimals: Int = 0,
    val extra: DiagnosticExtra? = null,
    val hub: VehicleSignal? = null,
    /** Its norm against the car now; none where nothing is known. */
    val norm: ((CarReadings) -> SystemsBuilder.SignalNorm)? = null,
    /** A count the car does not change: its value, no trace and no extremes. */
    val count: Boolean = false,
    /** Computed here from each hub sweep: the cells' spread. */
    val calc: ((Map<VehicleSignal, Double>) -> Double?)? = null,
) {
    init {
        require(listOfNotNull(extra, hub, calc).size == 1) { "$name: one source" }
    }

    /** A state's words, its source's; null for a number. */
    val words: StateWords? = extra?.let(CarStates::of) ?: hub?.let(CarStates::of)

    /** The row's [ParamKey.Signal]: the extra's key or the hub signal's name; a calculation has none. */
    val key: String? get() = extra?.key ?: hub?.name

    /** «1014·44600010», «расч.». */
    val source: String get() = extra?.let(SystemsBuilder::source) ?: hub?.let(SystemsBuilder::source) ?: SystemLive.CALCULATION

    /** «1014», the table's first column; «расч.» for a calculation. */
    val device: String get() = (extra?.device ?: hub?.device)?.toString() ?: SystemLive.CALCULATION

    /** «44600010», beside the name. */
    val fid: String get() = (extra?.fid ?: hub?.fid)?.let { "%08X".format(it) } ?: ""

    /** A number that moves: it keeps its minute and its extremes; a state or a count does not. */
    val trace: Boolean get() = words == null && !count
}

/**
 * The rows of every system's «Потоковые данные» (architecture 7.2), the tables of research
 * vehicle-diagnostics/stream-selection-20261021.json: its rows per system in its order and by its names,
 * shortened to fit beside the FID as the pack's are. The pack keeps its board's order, the selection's
 * five rows set in where they read. A state's words, and which of them is a fault, are [CarStates']; a
 * norm only where one is known - the pack's SOH and insulation.
 */
internal object SystemStreams {

    // 500 Ω per volt of the pack, the usual floor for an isolated HV system. kΩ is the closest known
    // unit - the GB/T 32960 field the stock collector fills from this FID - and is not proved on this
    // car (owner's decision 2026-10-21: keep kΩ and this floor; architecture 7.2). A code above 60000
    // never reaches here: DiagnosticExtra refuses it, so it is «н/д» and not judged.
    private val insulation: (CarReadings) -> SystemsBuilder.SignalNorm = { car ->
        val floorKOhm = car.hub[VehicleSignal.PACK_VOLT]?.let { it * 0.5 }
        if (floorKOhm == null) {
            SystemsBuilder.SignalNorm("≥ 500 Ом/В", null, null)
        } else {
            SystemsBuilder.SignalNorm("≥ ${Figures.number(floorKOhm, 0)}", null) { it < floorKOhm }
        }
    }

    // The readings two tables share, named once. A hub signal the engine's stream shows beside its PIDs
    // ([LiveStream.CarRow]) keeps that row's unit and decimals: the pack's power and the generation in
    // tenths of a kW.
    private val packVolts = StreamReading("Напряжение ВВБ", "В", hub = VehicleSignal.PACK_VOLT)
    private val packCurrent = StreamReading("Ток ВВБ", "А", 1, extra = DiagnosticExtra.CHARGE_CURRENT)
    private val packPower = StreamReading("Мощность ВВБ", "кВт", 1, hub = VehicleSignal.POWER_KW)
    private val contactor = StreamReading("Главный контактор, разряд", "", extra = DiagnosticExtra.CONTACTOR)
    private val chargePort = StreamReading("Зарядный разъём", "", hub = VehicleSignal.CHARGE_GUN)
    private val chargePower = StreamReading("Мощность заряда", "кВт", 1, hub = VehicleSignal.CHARGE_KW)
    private val busFront = StreamReading("Шина DC, передний инвертор", "В", extra = DiagnosticExtra.BUS_FRONT)
    private val busRear = StreamReading("Шина DC, задние инверторы", "В", extra = DiagnosticExtra.BUS_REAR)
    private val powerLevel = StreamReading("Режим питания", "", extra = DiagnosticExtra.POWER_LEVEL)
    private val dcdcMode = StreamReading("DC-DC, режим", "", extra = DiagnosticExtra.DCDC_MODE)
    private val speed = StreamReading("Скорость", "км/ч", hub = VehicleSignal.VEHICLE_SPEED)

    /** The cells' spread: the maximum less the minimum of the same sweep. */
    const val SPREAD_NAME = "Разброс ячеек"

    /**
     * The pack's readings in the board's order (`scanner-sys-pack-live`), named as the board names
     * them; the spread follows the cells' maximum, the selection's power, SOC in tenths, charge power
     * and charge port stand by their kin, the four states the firmware names stay last.
     */
    val PACK: List<StreamReading> = listOf(
        packVolts,
        packCurrent,
        packPower,
        StreamReading("Уровень заряда (SOC)", "%", extra = DiagnosticExtra.SOC),
        StreamReading("Уровень заряда, 0,1 %", "%", 1, extra = DiagnosticExtra.SOC_TENTHS),
        StreamReading("Состояние (SOH), BMS", "%", extra = DiagnosticExtra.SOH,
            norm = { SystemsBuilder.SignalNorm("≥ 80", 80f..100f) { it < 80.0 } }),
        StreamReading("Ячейка, мин.", "мВ", hub = VehicleSignal.CELL_MIN_MV),
        StreamReading("Ячейка, макс.", "мВ", hub = VehicleSignal.CELL_MAX_MV),
        StreamReading(SPREAD_NAME, "мВ", calc = { hub ->
            val min = hub[VehicleSignal.CELL_MIN_MV]
            val max = hub[VehicleSignal.CELL_MAX_MV]
            if (min != null && max != null) max - min else null
        }),
        StreamReading("Температура модулей, мин.", "°C", extra = DiagnosticExtra.TEMP_MIN),
        StreamReading("Температура модулей, макс.", "°C", extra = DiagnosticExtra.TEMP_MAX),
        StreamReading("Температура модулей, сред.", "°C", hub = VehicleSignal.PACK_TEMP_AVG),
        StreamReading("Изоляция, канал BMC", "кОм", extra = DiagnosticExtra.INSULATION, norm = insulation),
        StreamReading("Изоляция, канал 2", "кОм", extra = DiagnosticExtra.INSULATION_2, norm = insulation),
        chargePower,
        StreamReading("Ячеек последовательно", "шт", extra = DiagnosticExtra.CELL_COUNT, count = true),
        StreamReading("Датчиков температуры", "шт", extra = DiagnosticExtra.PROBE_COUNT, count = true),
        chargePort,
        contactor,
        StreamReading("Состояние заряда батареи", "", extra = DiagnosticExtra.PACK_CHARGE_STATE),
        StreamReading("Зарядное устройство, состояние", "", extra = DiagnosticExtra.CHARGER_STATE),
        StreamReading("Зарядное устройство, отказ", "", extra = DiagnosticExtra.CHARGER_FAULT),
    )

    val HV: List<StreamReading> = listOf(
        packVolts,
        packPower,
        packCurrent,
        contactor,
        busFront,
        busRear,
        StreamReading("Мощность от ДВС в ВВБ", "кВт", 1, hub = VehicleSignal.GENERATION_KW),
        StreamReading("Генерация, состояние", "", hub = VehicleSignal.GENERATION_STATE),
        powerLevel,
        chargePort,
        chargePower,
        dcdcMode,
    )

    val DRIVES: List<StreamReading> = listOf(
        StreamReading("Селектор", "", extra = DiagnosticExtra.GEAR_SELECTOR),
        StreamReading("Педаль акселератора", "%", extra = DiagnosticExtra.ACCEL_PEDAL),
        speed,
        StreamReading("Обороты, передний мотор", "об/мин", extra = DiagnosticExtra.RPM_FRONT),
        StreamReading("Обороты, задний левый", "об/мин", extra = DiagnosticExtra.RPM_REAR_LEFT),
        StreamReading("Обороты, задний правый", "об/мин", extra = DiagnosticExtra.RPM_REAR_RIGHT),
        StreamReading("Момент, передний мотор", "Н·м", 1, extra = DiagnosticExtra.TORQUE_FRONT),
        StreamReading("Момент, задний левый", "Н·м", 1, extra = DiagnosticExtra.TORQUE_REAR_LEFT),
        StreamReading("Момент, задний правый", "Н·м", 1, extra = DiagnosticExtra.TORQUE_REAR_RIGHT),
        StreamReading("Ток DC, передний инвертор", "А", 1, extra = DiagnosticExtra.CURRENT_FRONT),
        StreamReading("Ток DC, задний левый", "А", 1, extra = DiagnosticExtra.CURRENT_REAR_LEFT),
        StreamReading("Ток DC, задний правый", "А", 1, extra = DiagnosticExtra.CURRENT_REAR_RIGHT),
        busFront,
        busRear,
        StreamReading("IPM, передний инвертор", "°C", hub = VehicleSignal.INVERTER_C),
        StreamReading("IGBT, задний левый", "°C", extra = DiagnosticExtra.IGBT_REAR_LEFT),
        StreamReading("IGBT, задний правый", "°C", extra = DiagnosticExtra.IGBT_REAR_RIGHT),
        StreamReading("Статор, передний мотор", "°C", hub = VehicleSignal.MOTOR_FRONT_C),
        StreamReading("Статор, задний левый", "°C", hub = VehicleSignal.MOTOR_REAR_LEFT_C),
        StreamReading("Статор, задний правый", "°C", hub = VehicleSignal.MOTOR_REAR_RIGHT_C),
    )

    val CHASSIS: List<StreamReading> = listOf(
        StreamReading("Педаль тормоза, ход", "%", extra = DiagnosticExtra.BRAKE_PEDAL),
        StreamReading("Педаль тормоза, выключатель", "", extra = DiagnosticExtra.BRAKE_SWITCH),
        StreamReading("Стояночный тормоз (EPB)", "", extra = DiagnosticExtra.EPB),
        speed,
        StreamReading("Подвеска, процесс", "", extra = DiagnosticExtra.SUSPENSION_PROCESS),
        StreamReading("Подвеска, уровень", "", extra = DiagnosticExtra.SUSPENSION_LEVEL),
        StreamReading("TPMS, система", "", extra = DiagnosticExtra.TPMS_STATE),
        StreamReading("TPMS, батареи датчиков", "", extra = DiagnosticExtra.TPMS_BATTERY),
        StreamReading("Шины, температура", "", extra = DiagnosticExtra.TYRE_TEMP_STATE),
        StreamReading("Утечка воздуха, ПЛ", "", extra = DiagnosticExtra.FL_LEAK),
        StreamReading("Утечка воздуха, ПП", "", extra = DiagnosticExtra.FR_LEAK),
        StreamReading("Утечка воздуха, ЗЛ", "", extra = DiagnosticExtra.RL_LEAK),
        StreamReading("Утечка воздуха, ЗП", "", extra = DiagnosticExtra.RR_LEAK),
        StreamReading("Давление, оценка ПЛ", "", extra = DiagnosticExtra.FL_PRESSURE_STATE),
        StreamReading("Давление, оценка ПП", "", extra = DiagnosticExtra.FR_PRESSURE_STATE),
        StreamReading("Давление, оценка ЗЛ", "", extra = DiagnosticExtra.RL_PRESSURE_STATE),
        StreamReading("Давление, оценка ЗП", "", extra = DiagnosticExtra.RR_PRESSURE_STATE),
    )

    val SAFETY: List<StreamReading> = listOf(
        StreamReading("Ремень, водитель", "", extra = DiagnosticExtra.BELT_FL),
        StreamReading("Ремень, пассажир", "", extra = DiagnosticExtra.BELT_FR),
        StreamReading("Ремень, 2-й ряд слева", "", extra = DiagnosticExtra.BELT_RL),
        StreamReading("Ремень, 2-й ряд центр", "", extra = DiagnosticExtra.BELT_RC),
        StreamReading("Ремень, 2-й ряд справа", "", extra = DiagnosticExtra.BELT_RR),
        StreamReading("Место занято, водитель", "", extra = DiagnosticExtra.SEATED_FL),
        StreamReading("Место занято, пассажир", "", extra = DiagnosticExtra.SEATED_FR),
        StreamReading("Место занято, 2-й ряд слева", "", extra = DiagnosticExtra.SEATED_RL),
        StreamReading("Место занято, 2-й ряд центр", "", extra = DiagnosticExtra.SEATED_RC),
        StreamReading("Место занято, 2-й ряд справа", "", extra = DiagnosticExtra.SEATED_RR),
    )

    val BODY: List<StreamReading> = listOf(
        StreamReading("Указатели поворота", "", extra = DiagnosticExtra.TURN_LIGHTS),
        StreamReading("Стоп-сигнал", "", extra = DiagnosticExtra.STOP_LAMP),
        StreamReading("Фонарь заднего хода", "", extra = DiagnosticExtra.REVERSE_LAMP),
        StreamReading("Ближний свет", "", extra = DiagnosticExtra.LOW_BEAM),
        StreamReading("Дальний свет", "", extra = DiagnosticExtra.HIGH_BEAM),
        StreamReading("Габариты", "", extra = DiagnosticExtra.SIDE_LIGHTS),
        StreamReading("Капот", "", extra = DiagnosticExtra.HOOD),
        StreamReading("Багажник", "", extra = DiagnosticExtra.TRUNK),
    )

    val CLIMATE: List<StreamReading> = listOf(
        StreamReading("Улица", "°C", extra = DiagnosticExtra.OUTSIDE_TEMP),
        StreamReading("Салон", "°C", extra = DiagnosticExtra.CABIN_TEMP),
        StreamReading("Климат, питание", "", extra = DiagnosticExtra.AC_POWER),
        StreamReading("Компрессор", "", extra = DiagnosticExtra.AC_COMPRESSOR),
        StreamReading("Обдув, ступень", "", extra = DiagnosticExtra.AC_FAN),
        StreamReading("Рециркуляция", "", extra = DiagnosticExtra.AC_RECIRCULATION),
        StreamReading("AQS снаружи, ступень", "", extra = DiagnosticExtra.AQS_OUTSIDE),
        StreamReading("CO₂ снаружи, ступень", "", extra = DiagnosticExtra.CO2_OUTSIDE),
        StreamReading("Датчик влажности", "", extra = DiagnosticExtra.HUMIDITY_SENSOR),
        StreamReading("Датчик солнца", "", extra = DiagnosticExtra.SUN_SENSOR),
    )

    val LV: List<StreamReading> = listOf(
        StreamReading("Напряжение 12 В", "В", 1, extra = DiagnosticExtra.LV),
        dcdcMode,
        StreamReading("DC-DC, напряжение выхода", "В", 1, extra = DiagnosticExtra.DCDC_VOLTAGE),
        powerLevel,
    )

    /** Every system with «Потоковые данные» of readings, in the rail's order; the engine's is ECM's stream. */
    val ROWS: Map<CarSystem, List<StreamReading>> = linkedMapOf(
        CarSystem.HV to HV,
        CarSystem.PACK to PACK,
        CarSystem.DRIVES to DRIVES,
        CarSystem.CHASSIS to CHASSIS,
        CarSystem.SAFETY to SAFETY,
        CarSystem.BODY to BODY,
        CarSystem.CLIMATE to CLIMATE,
        CarSystem.LV to LV,
    )

    /** The reading a [ParamKey.Signal] names, in whichever table; null when none. */
    fun reading(key: String): StreamReading? = ROWS.values.asSequence().flatten().firstOrNull { it.key == key }
}

/**
 * A system's «Потоковые данные» read as the engine's stream (architecture 6, 7.2; boards
 * `scanner-sys-pack-live`, `scanner-sys-<system>-live`). While the tab is in front
 * ([DiagnosticsActions.setSystemLiveVisible]) every number keeps its row in a [LiveSeries]: the last
 * minute on the car's clock and the extremes since shown or since «Сбросить мин/макс». A hub signal -
 * and a figure computed from the hub, the cells' spread - takes a point per new hub sweep at the
 * sweep's time, a GET a point per new batch at the batch's time. States and counts say their value and
 * keep no trace. A batch past its horizon is a gap and «н/д», never a held value.
 *
 * «Пауза» ([pause]) takes nothing more: the table, the traces, the norms and the round stand as they
 * were. The norms are judged against the readings last taken - the insulation's floor is the pack's
 * voltage of the same moment. A state is said in orange when its card names its code a fault or an
 * alarm ([StateWords.alerts]); a number only outside a known norm.
 */
internal class SystemLive(val system: CarSystem) {
    private val readings: List<StreamReading> = SystemStreams.ROWS.getValue(system)
    private val series: Map<StreamReading, LiveSeries> =
        readings.associateWith { r -> LiveSeries(initial = NA).also { it.decimals = r.decimals } }
    /** A state's last code, for its alert. */
    private val codes = HashMap<StreamReading, Double?>()
    private var lastSweep: Long? = null
    private var lastBatch: Long? = null
    private var batchGone = false
    private var held = CarReadings()
    private var tookMs: Long? = null

    /** «Пауза»: nothing is taken until [resume]. */
    var paused = false
        private set

    /**
     * The tab shown again: the extremes start over, the next [take] samples what is there now - not
     * only what is new - and a pause left with the tab is over. The traces stay.
     */
    fun show() {
        paused = false
        series.values.forEach { it.clearMinMax() }
        lastSweep = null
        lastBatch = null
        batchGone = false
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** «Сбросить мин/макс»: the extremes start at each value now; the traces stay. */
    fun resetMinMax() = series.values.forEach { it.resetMinMax() }

    /** What [car] brings that this table has not taken yet: a new hub sweep, a new batch, a batch gone stale. */
    fun take(car: CarReadings) {
        if (paused) return
        held = car
        if (car.sweep != lastSweep) {
            lastSweep = car.sweep
            series.forEach { (r, s) ->
                when {
                    r.calc != null -> put(r, s, car.atNanos, r.calc.invoke(car.hub))
                    r.hub != null -> put(r, s, car.atNanos, car.hub[r.hub])
                }
            }
        }
        if (car.extrasFresh) {
            if (car.extrasAtNanos != lastBatch) {
                lastBatch = car.extrasAtNanos
                batchGone = false
                tookMs = car.extrasTookMs ?: tookMs
                series.forEach { (r, s) -> r.extra?.let { e -> put(r, s, car.extrasAtNanos, car.extras[e]) } }
            }
        } else if (!batchGone) {
            batchGone = true
            series.forEach { (r, s) -> if (r.extra != null) put(r, s, car.atNanos, null) }
        }
    }

    /** A state says its word, a count its number; a reading takes a point, a gap where nothing came. */
    private fun put(r: StreamReading, s: LiveSeries, at: Long, v: Double?) {
        val words = r.words
        when {
            words != null -> {
                s.value = words.word(v)
                codes[r] = v
            }
            !r.trace -> s.value = v?.let { Figures.number(it, r.decimals) } ?: NA
            v == null -> s.gap(at, NA)
            else -> s.number(at, v)
        }
    }

    fun view(): SystemLiveView = SystemLiveView(
        rows = readings.map(::row),
        paused = paused,
        round = tookMs?.let { "обход ${Figures.number(it / 1000.0, 1)} с" },
    )

    private fun row(r: StreamReading): LiveRow {
        val s = series.getValue(r)
        val norm = r.norm?.invoke(held)
        val current = s.current
        // Nothing came, or a code the extra refused as the firmware's invalid: not judged.
        val outside = current != null && norm?.outside?.invoke(current) == true
        return LiveRow(
            pid = NOT_A_PID,
            name = r.name,
            abbreviation = r.fid,
            value = s.value,
            unit = r.unit,
            min = if (r.trace) s.minText else "",
            max = if (r.trace) s.maxText else "",
            norm = norm?.text ?: "—",
            outOfNorm = outside || r.words?.alert(codes[r]) == true,
            source = r.source,
            samples = if (r.trace) s.samples.toList() else emptyList(),
            key = r.key?.let(ParamKey::Signal),
            device = r.device,
        )
    }

    companion object {
        private const val NA = "н/д"
        /** A row of a system's table is not a PID: its first column is its device ([LiveRow.device]). */
        const val NOT_A_PID = -1
        /** A calculation's device and source: computed here, not read. */
        const val CALCULATION = "расч."

        /** The table as one look at [car] makes it: what the tab shows before it keeps a minute. */
        fun of(system: CarSystem, car: CarReadings): SystemLiveView = SystemLive(system).also { it.take(car) }.view()
    }
}
