package dev.denza.apps.feature.diagnostics

import dev.denza.apps.platform.shell.ServiceCallParcel

/**
 * Qualified scalar GETs from profile 170. Bounds reject sentinels, not diagnose faults.
 *
 * [pages]: read only while one of them is in front ([DiagnosticExtraReader.command]) - a reading two
 * pages need rides both - and empty for the batch every system screen shares; [codes], where a range
 * would admit a code the firmware does not name, the only codes taken; [floatNoData], whether the
 * float word −1.0 is the HAL's no-data fallback here (it is, unless the converter's own range holds a
 * real −1.0).
 */
internal enum class DiagnosticExtra(
    val key: String, val device: Int, val fid: Int, val transact: Int,
    private val scale: Double, private val offset: Double, private val range: ClosedFloatingPointRange<Double>,
    val pages: Set<ExtraPage> = emptySet(), private val codes: Set<Int>? = null,
    private val floatNoData: Boolean = true,
) {
    SOC("soc", 1014, 1246777400, 7, 1.0, 0.0, 0.0..100.0),
    SOH("soh", 1014, 1145045032, 5, 1.0, 0.0, 0.0..100.0),
    TEMP_MIN("temp_min", 1014, 1148190736, 5, 1.0, -40.0, -39.0..150.0),
    TEMP_MAX("temp_max", 1014, 1148190752, 5, 1.0, -40.0, -39.0..150.0),
    LV("lv", 1001, 1128267816, 7, 1.0, 0.0, 1.0..30.0),
    FUEL("fuel", 1014, 1246785600, 5, 1.0, 0.0, 0.0..100.0),
    // The DC buses before the front and the rear inverters, V. Medium (research stream-selection-20261021.json):
    // volts by the GB/T 32960 field the stock gbacqservice fills from these FIDs, as INSULATION's kΩ.
    BUS_FRONT("bus_front", 1039, 1178628128, 5, 1.0, 0.0, 1.0..1000.0),
    BUS_REAR("bus_rear", 1039, 1178628112, 5, 1.0, 0.0, 1.0..1000.0),
    // The pack's current, A, on the raw channel `CHARGING_CHARGE_CURRENT_NOT_CONVERT` (correction C1 of
    // research stream-selection-20261021.json; stream-powertrain-20261021.json chargingCurrent): the HAL
    // hands the CAN 0x444 wire as float32(wire), unsigned, so −1.0 can only be its no-data word, and the
    // app applies the stock `currentcompat` arithmetic wire × 0.1 − 500 itself. The converted channel
    // 1009·44400018 it replaces held a real −1.0 A at wire 4990, which the no-data word refused. The
    // converter cannot go below −500 A; above 1500 A is no pack current here (pack power ±600 kW), and
    // wire FFFF (6053.5 A), whose meaning is unproven, is refused with it. Medium: the sign is unproven.
    CHARGE_CURRENT("charge_current", 1009, 0xC4400018.toInt(), 7, 0.1, -500.0, -500.0..1500.0),
    // The stock GB/T 32960 collector reads this FID and caps it at 60000 (gbacqservice 1381C..13850;
    // research insulation-consumer-20261021.json): above that is the firmware's invalid code, FFFE and
    // FFFF among them, not a resistance - «н/д», never 60000 and never judged against the norm.
    INSULATION("insulation", 1039, 1134559256, 5, 1.0, 0.0, 0.0..60000.0),
    // 254 and 255 are the firmware's invalid counts: the stock reader takes 1..253 (gbdataservice
    // E9A0..EA2C; research battery-validity-followup-20261021.json). PROBE_COUNT likewise.
    CELL_COUNT("cell_count", 1001, 1134559240, 5, 1.0, 0.0, 1.0..253.0),
    CABIN_TEMP("cabin_temp", 1000, 1031798832, 5, 1.0, 0.0, -39.0..150.0),
    // The outside temperature, `AC_TEMP_OUT`, in the unit TEMP_UNIT names (C2, research
    // stream-selection-20261021.json; ExtraGates): the stock domains are −40…50 °C and −40…122 °F, so
    // this range takes either and the gate makes it °C or «н/д». Medium.
    OUTSIDE_TEMP("outside_temp", 1000, 1077936184, 5, 1.0, 0.0, -40.0..122.0),
    /** `AC_TEMPERATURE_UNIT`: 1 °C, 0 °F (`BYDAutoAcDevice.AC_TEMPERATURE_UNIT_OC`, `_OF`); read wherever OUTSIDE_TEMP is. */
    TEMP_UNIT("temp_unit", 1000, 0x40400027, 5, 1.0, 0.0, 0.0..1.0),
    // PM2.5 inside and out: only the climate's scheme shows them, so only it pays for them.
    PM_IN("pm_in", 1008, 1331691536, 5, 1.0, 0.0, 0.0..999.0, pages = on(ExtraPage.CLIMATE_STATE)),
    PM_OUT("pm_out", 1008, 1331691548, 5, 1.0, 0.0, 0.0..999.0, pages = on(ExtraPage.CLIMATE_STATE)),
    FL_PRESSURE("fl_pressure", 1016, -1728052956, 5, 0.01, 0.0, 0.0..40.94),
    FR_PRESSURE("fr_pressure", 1016, -1728052952, 5, 0.01, 0.0, 0.0..40.94),
    RL_PRESSURE("rl_pressure", 1016, -1728052948, 5, 0.01, 0.0, 0.0..40.94),
    RR_PRESSURE("rr_pressure", 1016, -1728052944, 5, 0.01, 0.0, 0.0..40.94),
    FL_TYRE_TEMP("fl_tyre_temp", 1007, 1246797848, 5, 1.0, 0.0, -39.0..150.0),
    FR_TYRE_TEMP("fr_tyre_temp", 1007, 1246797860, 5, 1.0, 0.0, -39.0..150.0),
    RL_TYRE_TEMP("rl_tyre_temp", 1007, 1246797872, 5, 1.0, 0.0, -39.0..150.0),
    RR_TYRE_TEMP("rr_tyre_temp", 1007, 1246797884, 5, 1.0, 0.0, -39.0..150.0),
    FL_WINDOW("fl_window", 1001, 947912728, 5, 1.0, 0.0, 0.0..100.0),
    FL_INIT("fl_init", 1001, 1222639632, 5, 1.0, 0.0, 1.0..2.0),
    FL_THERMAL("fl_thermal", 1001, 1222639646, 5, 1.0, 0.0, 0.0..1.0),
    FR_WINDOW("fr_window", 1001, 1267728400, 5, 1.0, 0.0, 0.0..100.0),
    FR_INIT("fr_init", 1001, 1267728392, 5, 1.0, 0.0, 1.0..2.0),
    FR_THERMAL("fr_thermal", 1001, 947912768, 5, 1.0, 0.0, 0.0..1.0),
    RL_WINDOW("rl_window", 1001, 947912736, 5, 1.0, 0.0, 0.0..100.0),
    RL_INIT("rl_init", 1001, 1222639634, 5, 1.0, 0.0, 1.0..2.0),
    RL_THERMAL("rl_thermal", 1001, 1222639647, 5, 1.0, 0.0, 0.0..1.0),
    RR_WINDOW("rr_window", 1001, 947912752, 5, 1.0, 0.0, 0.0..100.0),
    RR_INIT("rr_init", 1001, 1222639638, 5, 1.0, 0.0, 1.0..2.0),
    RR_THERMAL("rr_thermal", 1001, 947912769, 5, 1.0, 0.0, 0.0..1.0),
    FL_POSITION("fl_position", 1001, 957350000, 5, 1.0, 0.0, 1.0..3.0),
    FL_LOCK("fl_lock", 1041, -1066401784, 5, 1.0, 0.0, 1.0..2.0),
    FR_POSITION("fr_position", 1001, 957350002, 5, 1.0, 0.0, 1.0..3.0),
    FR_LOCK("fr_lock", 1041, -1066401782, 5, 1.0, 0.0, 1.0..2.0),
    RL_POSITION("rl_position", 1001, 957350004, 5, 1.0, 0.0, 1.0..3.0),
    RL_LOCK("rl_lock", 1041, -1066401780, 5, 1.0, 0.0, 1.0..2.0),
    RR_POSITION("rr_position", 1001, 957350006, 5, 1.0, 0.0, 1.0..3.0),
    RR_LOCK("rr_lock", 1041, -1066401778, 5, 1.0, 0.0, 1.0..2.0),

    // The system screens of «Диагностика» (docs/vehicle-diagnostics-architecture.md, 6). Types are
    // those the Z9GT catalog sweep of 2026-10-20 answered (captures/diagnostics-sweep-20261020):
    // integers on 5, floats on 7 where the integer read was the no-data word. Parked they read 0,
    // 5 °C IGBTs, and 0xFFFF for the rear-left rotation speed, which the range refuses as «н/д».
    INSULATION_2("insulation_2", 1039, 0x26200010, 5, 1.0, 0.0, 0.0..60000.0),
    PROBE_COUNT("probe_count", 1039, 0x43A00010, 5, 1.0, 0.0, 1.0..253.0),
    /** Discharge main contactor: 0 open, 1 closed (`BYDAutoOtaDevice.CONTACTOR_STATE_*`; architecture 6.3). */
    CONTACTOR("contactor", 1032, 0x34400010, 5, 1.0, 0.0, 0.0..254.0),
    // Named by the stock getters' constants (BYDAutoChargingDevice; research 2026-10-21,
    // powertrain-telemetry-20261021.json): integers on 5; their words are SystemsBuilder.PACK's.
    PACK_CHARGE_STATE("pack_charge_state", 1009, 0x34400018, 5, 1.0, 0.0, 0.0..254.0),
    CHARGER_STATE("charger_state", 1009, 0x27C0000A, 5, 1.0, 0.0, 0.0..254.0),
    CHARGER_FAULT("charger_fault", 1009, 0x27C00008, 5, 1.0, 0.0, 0.0..254.0),
    // The rear inverters' IGBTs, °C: the HAL's byte − 40, so FE/FF arrive as 214/215 and byte 0 as −40,
    // all outside the range. Medium: the unit by the GB/T 32960 field, as INSULATION's kΩ.
    IGBT_REAR_LEFT("igbt_rear_left", 1039, 0x2D000180, 5, 1.0, 0.0, -39.0..150.0),
    IGBT_REAR_RIGHT("igbt_rear_right", 1039, 0x2D000188, 5, 1.0, 0.0, -39.0..150.0),
    // Low (stream-selection-20261021.json): a mode field switches this FID's source; the unit by the name.
    RPM_FRONT("rpm_front", 1012, 0x44100008, 5, 1.0, 0.0, -20000.0..20000.0),
    // The rear motors' speed, torque and DC current are unsigned fields (C4; stream-powertrain-20261021.json
    // fieldAcceptanceMatrix): speed unsigned16 as it comes, torque and current unsigned16 × 0.1. No
    // negative - no regeneration, no reverse - can arrive: a wrapped one reads 32768+ rpm or 3276.8+, FFFF
    // 65535 or 6553.5, and the ranges refuse them as «н/д». Medium: the units by the GB/T 32960 fields the
    // stock collector fills, as INSULATION's kΩ; the sign is to be seen in a drive.
    RPM_REAR_LEFT("rpm_rear_left", 1039, 0x0FC000D0, 5, 1.0, 0.0, 0.0..20000.0),
    RPM_REAR_RIGHT("rpm_rear_right", 1039, 0x0FC000E0, 5, 1.0, 0.0, 0.0..20000.0),
    // The front drive's torque and DC current (C1): the HAL converts wire 0..10000 to wire × 0.1 − 500 and
    // wire ≤ 20000 to wire × 0.1 − 1000, so these ranges are the converters' whole range and −1.0 (wire
    // 4990, 9990) is a value, not the no-data word; no raw alias is known for them. A wire the HAL
    // rejects keeps its previous cache, so a repeated value is not proof of a fresh one.
    TORQUE_FRONT("torque_front", 1012, 0x44100018, 7, 1.0, 0.0, -500.0..500.0, floatNoData = false),
    TORQUE_REAR_LEFT("torque_rear_left", 1039, 0x2850002C, 7, 1.0, 0.0, 0.0..3000.0),
    TORQUE_REAR_RIGHT("torque_rear_right", 1039, 0x2850003C, 7, 1.0, 0.0, 0.0..3000.0),
    CURRENT_FRONT("current_front", 1009, 0x46C00008, 7, 1.0, 0.0, -1000.0..1000.0, floatNoData = false),
    CURRENT_REAR_LEFT("current_rear_left", 1039, 0x28500178, 7, 1.0, 0.0, 0.0..3000.0),
    CURRENT_REAR_RIGHT("current_rear_right", 1039, 0x28500188, 7, 1.0, 0.0, 0.0..3000.0),

    /**
     * The OEM warning the instrument cluster is asked to show: `BYDAutoInstrumentDevice.getTextInfo`,
     * an integer on 5, the 10-bit ID of CAN 0x261 unscaled, 0 for none (research
     * oem-warning-mappings-20261021.json). Above 1023 is not an ID. Its words: SystemsBuilder.clusterWarning.
     */
    OEM_WARNING("oem_warning", 1007, 0x26100008, 5, 1.0, 0.0, 0.0..1023.0),

    // «Салон · Состояние» (architecture 7.2): the status FIDs of research cabin-signals-20261021.json,
    // integers on 5, named by DiCarServer; raw codes, words and arithmetic in SystemPagesBuilder.cabin.
    // 65535 is the firmware's no-signal word on all of them and outside every range here.
    /** Seat heating and ventilation, the three-level path: 1 off, 2/3/4 levels 1/2/3. */
    SEAT_HEAT_FL("seat_heat_fl", 1023, 0x4FA0000B, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_HEAT_FR("seat_heat_fr", 1023, 0x4FA00013, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_HEAT_RL("seat_heat_rl", 1023, 0x4860000B, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_HEAT_RR("seat_heat_rr", 1023, 0x48600013, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_VENT_FL("seat_vent_fl", 1023, 0x4FA00008, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_VENT_FR("seat_vent_fr", 1023, 0x4FA00010, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_VENT_RL("seat_vent_rl", 1023, 0x48600008, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    SEAT_VENT_RR("seat_vent_rr", 1023, 0x48600010, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    /** Massage mode: 1 off, 2..6 the programmes, 8 and 9 the hot stones; 7 is not named. */
    MASSAGE_MODE_FL("massage_mode_fl", 1023, 0x4FA00030, 5, 1.0, 0.0, 1.0..9.0, pages = on(ExtraPage.CABIN), codes = MASSAGE_MODES),
    MASSAGE_MODE_FR("massage_mode_fr", 1023, 0x4FA00038, 5, 1.0, 0.0, 1.0..9.0, pages = on(ExtraPage.CABIN), codes = MASSAGE_MODES),
    MASSAGE_MODE_RL("massage_mode_rl", 1023, 0x4FA00020, 5, 1.0, 0.0, 1.0..9.0, pages = on(ExtraPage.CABIN), codes = MASSAGE_MODES),
    MASSAGE_MODE_RR("massage_mode_rr", 1023, 0x4FA00028, 5, 1.0, 0.0, 1.0..9.0, pages = on(ExtraPage.CABIN), codes = MASSAGE_MODES),
    /** Massage level: 0 invalid, 1..3 weak, medium, strong, 4 off. */
    MASSAGE_LEVEL_FL("massage_level_fl", 1023, 0x4FA00034, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CABIN)),
    MASSAGE_LEVEL_FR("massage_level_fr", 1023, 0x4FA0003C, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CABIN)),
    MASSAGE_LEVEL_RL("massage_level_rl", 1023, 0x4FA00024, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CABIN)),
    MASSAGE_LEVEL_RR("massage_level_rr", 1023, 0x4FA0002C, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CABIN)),
    // The belts and who sits are also «Безопасность»'s, its scheme's and its table's (research
    // stream-selection-20261021.json, SAFETY): the same GETs, never a second entry for a FID.
    /** The front belts on the cluster's device: 1 fastened, 0 not; 2 and 3 of the 2-bit field «н/д». */
    BELT_FL("belt_fl", 1007, 0x29400018, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    BELT_FR("belt_fr", 1007, 0x2610001B, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    /** The second row's belts: 2 fastened, 1 not. */
    BELT_RL("belt_rl", 1023, 0x3120000A, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    BELT_RR("belt_rr", 1023, 0x3120000C, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    BELT_RC("belt_rc", 1023, 0x3120000E, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    /** Who sits: 2 seated, any other code not, as DiCarServer reads it (the framework's constants disagree; research). */
    SEATED_FL("seated_fl", 1042, 0x31200040, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    SEATED_FR("seated_fr", 1042, 0x31200016, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    SEATED_RL("seated_rl", 1042, 0x31200018, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    SEATED_RR("seated_rr", 1042, 0x3120001C, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    SEATED_RC("seated_rc", 1042, 0x3120001A, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CABIN, ExtraPage.SAFETY_STATE, ExtraPage.SAFETY_LIVE)),
    /** The steering wheel's heating, the feedback: 1 off, 2 on; 0 invalid. */
    WHEEL_HEAT("wheel_heat", 1023, 0x4290000E, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN)),
    /** What a fridge does: 1 cold, 2 hot, 3 off, 4 fresh. */
    FRIDGE_FRONT("fridge_front", 1023, 0x48C00018, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_REAR("fridge_rear", 1023, 0x3AE00008, 5, 1.0, 0.0, 1.0..4.0, pages = on(ExtraPage.CABIN)),
    /** A mode's set point, raw in °C units: cold raw 1..34 (raw − 19), hot 1..16 (raw + 34), fresh 1..3 (raw + 7). */
    FRIDGE_FRONT_COLD("fridge_front_cold", 1023, 0x3D60002A, 5, 1.0, 0.0, 1.0..34.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_FRONT_HOT("fridge_front_hot", 1023, 0x3D600030, 5, 1.0, 0.0, 1.0..16.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_FRONT_FRESH("fridge_front_fresh", 1023, 0x3D600035, 5, 1.0, 0.0, 1.0..3.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_REAR_COLD("fridge_rear_cold", 1023, 0x3AE00010, 5, 1.0, 0.0, 1.0..34.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_REAR_HOT("fridge_rear_hot", 1023, 0x3AE00018, 5, 1.0, 0.0, 1.0..16.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_REAR_FRESH("fridge_rear_fresh", 1023, 0x3AE0001D, 5, 1.0, 0.0, 1.0..3.0, pages = on(ExtraPage.CABIN)),
    /** A fridge's popup reminder: 0 none, 1 no link, 2 long work, 3 heat mode, 4 door not closed. */
    FRIDGE_FRONT_REMIND("fridge_front_remind", 1023, 0x48C00029, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CABIN)),
    FRIDGE_REAR_REMIND("fridge_rear_remind", 1023, 0x3AE00038, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CABIN)),
    /** The wireless charging pads: 0 charging, 1 full, 2 fault, 3 standby. */
    PAD_LEFT("pad_left", 1009, 0x41E00009, 5, 1.0, 0.0, 0.0..3.0, pages = on(ExtraPage.CABIN)),
    PAD_RIGHT("pad_right", 1009, 0xC1E00012.toInt(), 5, 1.0, 0.0, 0.0..3.0, pages = on(ExtraPage.CABIN)),
    /** The reading lights, front left and right, then the second row's (LM, RM): 2 on, 1 off. */
    READING_FL("reading_fl", 1004, 0x3FE0000A, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN)),
    READING_FR("reading_fr", 1004, 0x3FE0000C, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN)),
    READING_RL("reading_rl", 1004, 0x3FE0000E, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN)),
    READING_RR("reading_rr", 1004, 0x3FE00010, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN)),
    /** The HUD's switch, the feedback: 1 on, 2 off. */
    HUD("hud", 1023, 0x38B0001C, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.CABIN)),

    // «Тяговая батарея · Флаги GB/T 32960» (architecture 7.2): the 14 device-1039 readings from which the
    // stock reporter gbacqservice sets GB/T 32960.3's general alarm flags (research
    // gb-alarm-dtc-boundary-20261021.json, alarmInputs), integers on 5 as the sweep of 2026-10-20 read
    // them, in the standard's bit order. Raw codes; which raise a flag is scan/GbFlags. The reporter's
    // predicate was qualified on 0..255; 255 is taken as the byte's no-data code and, with 65535, refused.
    /** Bit 0, the pack's temperature difference. */
    GB_TEMP_DIFF("gb_temp_diff", 1039, 0x43800008, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bit 1, the pack's high temperature. */
    GB_PACK_HOT("gb_pack_hot", 1039, 0x2440000C, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bits 2 and 3, the pack's over- and undervoltage. */
    GB_PACK_OVERVOLT("gb_pack_overvolt", 1039, 0x4380000E, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    GB_PACK_UNDERVOLT("gb_pack_undervolt", 1039, 0x43800010, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bits 7 and 8, SOC high and SOC jump. */
    GB_SOC_HIGH("gb_soc_high", 1039, 0x43800014, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    GB_SOC_JUMP("gb_soc_jump", 1039, 0x43800016, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bits 9 and 10, the pack's mismatch and the cells' poor consistency. */
    GB_PACK_MISMATCH("gb_pack_mismatch", 1039, 0x4380000C, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    GB_CELL_CONSISTENCY("gb_cell_consistency", 1039, 0x4380000A, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bits 12 and 14, the DC-DC's temperature and state. */
    GB_DCDC_TEMP("gb_dcdc_temp", 1039, 0x26C00010, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    GB_DCDC_STATE("gb_dcdc_state", 1039, 0x26C00022, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bit 15, the motor controller's temperature. */
    GB_CONTROLLER_TEMP("gb_controller_temp", 1039, 0x2400000C, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bit 16, the high-voltage interlock. */
    GB_HVIL("gb_hvil", 1039, 0x2440003E, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bit 17, the drive motor's temperature. */
    GB_MOTOR_TEMP("gb_motor_temp", 1039, 0x2400000A, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),
    /** Bit 18, the pack's overcharge. */
    GB_OVERCHARGE("gb_overcharge", 1039, 0x43800012, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.GB_FLAGS)),

    // The systems' «Потоковые данные» and the readings their schemes add (architecture 6, 7.2): each a
    // row of research stream-selection-20261021.json, the source table the scanner cites, with its
    // device, FID, transaction, scale and offset; its words are scan.CarStates'. A reading rides its
    // system's live page (`*_LIVE`) and, where the scheme draws it, the «Состояние» page (`*_STATE`);
    // none rides the shared batch. Integers on 5 unless said. A range takes the codes the row names
    // and refuses its invalid encodings - 65535 (the stock DEVICE_THE_FEATURE_LINK_ERROR) on every one;
    // where the row says another code stays a code («код N»), 0..254, refusing the byte's 255 as the
    // states above do. «Medium»: the row's unit or words rest on the GB/T 32960 field the stock collector
    // fills or on the stock getter's constants, not on a card - as INSULATION's kΩ; the owner takes
    // them (2026-10-21). «Low»: the row's own word for a weaker basis, said where it is.

    // ---- high voltage and 12 V
    /**
     * Power mode, `BYDAutoBodyworkDevice.getPowerLevel`: 0 off, 1 ACC, 2 ON, 3 OK, 4 FAKE_OK (the stock
     * name; never seen); 255 its INVALID. Medium: the stock constants, no card. ON both passes of the sweep.
     */
    POWER_LEVEL("power_level", 1001, 0x12D0002A, 5, 1.0, 0.0, 0.0..4.0,
        pages = on(ExtraPage.HV_STATE, ExtraPage.LV_STATE, ExtraPage.HV_LIVE, ExtraPage.LV_LIVE)),
    /**
     * DC-DC mode, `ENERGY_DC_WORK_MODE` (stream-powertrain logicalFields[264]): 0 off, 1 boost, 2 step
     * down. The HAL passes a 2-bit 0..3; 3 has no stock name and stays «код 3». Step down on the sweep.
     */
    DCDC_MODE("dcdc_mode", 1006, 0x36D00030, 5, 1.0, 0.0, 0.0..3.0,
        pages = on(ExtraPage.LV_STATE, ExtraPage.HV_LIVE, ExtraPage.LV_LIVE)),
    /**
     * DC-DC low-side voltage, a float on 7 (logicalFields[278]): the HAL's byte × 0.1, 0..25.5. Low: volts
     * by the name and by agreeing with the 12 V channel (13.8 on the sweep), no labelled consumer.
     */
    DCDC_VOLTAGE("dcdc_voltage", 1006, 0x36D00020, 7, 1.0, 0.0, 0.0..25.5, pages = on(ExtraPage.LV_LIVE)),

    // ---- the pack
    /**
     * SOC in tenths of a percent, `STATISTIC_REMAINING_BATTERY_POWER` × 0.1 (logicalFields[20]): SOC
     * reads whole percent here, the tenths move inside a minute's chart (739 → 741 on the sweep).
     * Medium: the percent is an inherited qualification, not re-proved.
     */
    SOC_TENTHS("soc_tenths", 1014, 0x44700028, 5, 0.1, 0.0, 0.0..100.0, pages = on(ExtraPage.PACK_LIVE)),

    // ---- drives
    /**
     * The selector, `BYDAutoGearboxDevice.getGearboxAutoModeType`: 1 P, 2 R, 3 N, 4 D, 5 M, 6 S, another
     * code «код N»; the hub's GEARBOX_PARK says only P or not. Medium: the stock constants, no card.
     */
    GEAR_SELECTOR("gear_selector", 1011, 0x21200038, 5, 1.0, 0.0, 0.0..254.0,
        pages = on(ExtraPage.DRIVES_STATE, ExtraPage.DRIVES_LIVE)),
    /**
     * Accelerator travel, % 0..100 (`SPEED_ACCELERATOR_S`), counted only while ACCEL_PEDAL_VALID reads 1
     * (ExtraGates). Medium: the brake pedal's card and DiCar's 0..100, not a card of its own.
     */
    ACCEL_PEDAL("accel_pedal", 1013, 0x34200008, 5, 1.0, 0.0, 0.0..100.0, pages = on(ExtraPage.DRIVES_LIVE)),
    /** Its validity flag, `BYDAutoSpeedDevice.DATA_FLAG_*`: 1 valid, 0 not. */
    ACCEL_PEDAL_VALID("accel_pedal_valid", 1013, 0x34200018, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.DRIVES_LIVE)),

    // ---- chassis
    /**
     * Brake pedal travel, % 0..100 (stream-chassis records[278]) - travel, never hydraulic pressure -
     * counted only while BRAKE_PEDAL_VALID (records[279]) reads 1 (ExtraGates).
     */
    BRAKE_PEDAL("brake_pedal", 1013, 0x34200010, 5, 1.0, 0.0, 0.0..100.0, pages = on(ExtraPage.CHASSIS_LIVE)),
    /** Its validity flag: 1 valid, 0 not. */
    BRAKE_PEDAL_VALID("brake_pedal_valid", 1013, 0x34200019, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CHASSIS_LIVE)),
    /**
     * The brake switch, `getBrakePedalState` (records[187]) as the stock CAN-FD mapping reads it: 0 and 3
     * not pressed, 1 and 2 pressed. Medium: 1 and 2 are the mapping's else-branch, not named one by one.
     */
    BRAKE_SWITCH("brake_switch", 1011, 0x29400022, 5, 1.0, 0.0, 0.0..3.0, pages = on(ExtraPage.CHASSIS_LIVE)),
    /** The parking brake, `getEPBState`: 0 releasing, 1 released, 2 applying, 3 applied, 4 fault. Medium: the stock constants. */
    EPB("epb", 1011, 0x21800011, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    /** The suspension's height change (records[142], DiCar `HeightAdjustmentProperty`): 1 raising, 2 lowering, 3 done, 4 aborted. */
    SUSPENSION_PROCESS("suspension_process", 1023, 0x2580002C, 5, 1.0, 0.0, 1.0..4.0,
        pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    /** Its actual level (records[146], `DiSusHeightLevelProperty`): 1 low, 2 middle, 3 high - the feedback, not the setting. */
    SUSPENSION_LEVEL("suspension_level", 1023, 0x25800044, 5, 1.0, 0.0, 1.0..3.0,
        pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    /** TPMS, `TYRE_SYSTEM_STATE` (stream-body-comfort signals[456]): 0 normal, 1 self-test, 2 no signal, 3 fault, 4 off. */
    TPMS_STATE("tpms_state", 1016, 0x3CD0000D, 5, 1.0, 0.0, 0.0..4.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    /** The wheel sensors' batteries, `TYRE_BATTERY_STATE` (signals[430]): 0 normal, 1 low. */
    TPMS_BATTERY("tpms_battery", 1016, 0x3CD00046, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    /** The tyres' temperature, all four in one, `TYRE_TEMPERATURE_STATE` (signals[457]): 0 normal, 1 very high, 2 high, 3 sensors asleep. */
    TYRE_TEMP_STATE("tyre_temp_state", 1016, 0x3CD00044, 5, 1.0, 0.0, 0.0..3.0, pages = on(ExtraPage.CHASSIS_LIVE)),
    /** A wheel's air leak, `getTyreAirLeakState` (signals[426..429]): 0 none, 1 quick, 2 slow; another code «код N». */
    FL_LEAK("fl_leak", 1016, 0x99000122.toInt(), 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    FR_LEAK("fr_leak", 1016, 0x99000126.toInt(), 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    RL_LEAK("rl_leak", 1016, 0x9900012A.toInt(), 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    RR_LEAK("rr_leak", 1016, 0x9900012E.toInt(), 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    /**
     * A wheel's pressure judgement, `TYRE_PRESSURE_STATE_*` (signals[435..438]): 0 normal, 1 high, 2 low;
     * the stock listener also passes 3, which it does not name - «код 3», as any other code.
     */
    FL_PRESSURE_STATE("fl_pressure_state", 1016, 0x99000123.toInt(), 5, 1.0, 0.0, 0.0..254.0,
        pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    FR_PRESSURE_STATE("fr_pressure_state", 1016, 0x99000127.toInt(), 5, 1.0, 0.0, 0.0..254.0,
        pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    RL_PRESSURE_STATE("rl_pressure_state", 1016, 0x9900012B.toInt(), 5, 1.0, 0.0, 0.0..254.0,
        pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),
    RR_PRESSURE_STATE("rr_pressure_state", 1016, 0x9900012F.toInt(), 5, 1.0, 0.0, 0.0..254.0,
        pages = on(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE)),

    // ---- body
    /**
     * The turn indicators, `getTurnLightFlashState` (signals[278]): 1 off, 2 left, 3 left fast, 4 right,
     * 5 right fast, 6 hazard, 7 emergency braking, 8 rear-end threat, 9 fast flash; 0 its INVALID.
     * 1..6 are the mirrors' (TurnSignalDecoder, live), 7..9 stock names only.
     */
    TURN_LIGHTS("turn_lights", 1004, 0x38A0002C, 5, 1.0, 0.0, 1.0..9.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),
    /** The stop lamps, `getStopLightState`: 1 on, 2 flashing, 3 off; 0 INVALID. Medium: the getter class's constants, no card enum. */
    STOP_LAMP("stop_lamp", 1004, 0x38A00012, 5, 1.0, 0.0, 1.0..3.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),
    /** The reversing lamp, `getReversingLightState`: 1 on, 2 off; 0 INVALID. Medium, as the stop lamps. */
    REVERSE_LAMP("reverse_lamp", 1004, 0x38A00014, 5, 1.0, 0.0, 1.0..2.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),
    /** Low and high beam (signals[273], [270]): 0 off, 1 on; another code «код N». */
    LOW_BEAM("low_beam", 1004, 0x38A0000A, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),
    HIGH_BEAM("high_beam", 1004, 0x38A0000C, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),
    /** Side lights (signals[277]): 0 off, 1 on; another code «код N». Only the table: the scheme draws the beams. */
    SIDE_LIGHTS("side_lights", 1004, 0x38A00008, 5, 1.0, 0.0, 0.0..254.0, pages = on(ExtraPage.BODY_LIVE)),
    /** The hood and the tailgate, `getDoorState` (signals[84], [90]): 0 closed, 1 open; 255 its UNDEFINED. */
    HOOD("hood", 1001, 0x2940001C, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),
    TRUNK("trunk", 1001, 0x2940001A, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.BODY_STATE, ExtraPage.BODY_LIVE)),

    // ---- climate
    /**
     * The climate's power, compressor and air intake, `getAcStartState`, `getAcCompressorMode`,
     * `getAcCycleMode` (signals[181], [171], [173]): 0 off / off / outside air, 1 on / on / recirculation.
     * Medium: the getters' constants, the cards' enums are empty.
     */
    AC_POWER("ac_power", 1000, 0x40400010, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    AC_COMPRESSOR("ac_compressor", 1000, 0x40400009, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    AC_RECIRCULATION("ac_recirculation", 1000, 0x40400014, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    /** The blower's step 0..7, `AC_WIND_LEVEL` (signals[210], `AC_WINDLEVEL_0..7`): an ordinal, no words. Medium. */
    AC_FAN("ac_fan", 1000, 0x4040001C, 5, 1.0, 0.0, 0.0..7.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    /** Outside air quality, the stock `getAQSLevel` raw − 1 (signals[63]): step 0..5, not a concentration; raw outside 1..6 refused. */
    AQS_OUTSIDE("aqs_outside", 1000, 0x35B0000B, 5, 1.0, -1.0, 0.0..5.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    /** Outside CO₂, step 1..6 (signals[65]), not ppm. Medium. */
    CO2_OUTSIDE("co2_outside", 1000, 0x35B00012, 5, 1.0, 0.0, 1.0..6.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    /** The humidity and sun sensors' hardware failure (signals[246], [257]): 0 sound, 1 failed. */
    HUMIDITY_SENSOR("humidity_sensor", 1043, 0x3B400047, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CLIMATE_LIVE)),
    SUN_SENSOR("sun_sensor", 1043, 0x3B400046, 5, 1.0, 0.0, 0.0..1.0, pages = on(ExtraPage.CLIMATE_LIVE));

    fun decode(word: Int): Double? {
        // Same native no-data words as the shared AutoserviceShell; -1f is no data unless it is a value here.
        if (word == -10013 || word == -10011 || (floatNoData && word == FLOAT_MINUS_ONE)) return null
        val raw = if (transact == 7) Float.fromBits(word).toDouble() else word.toDouble()
        val value = raw * scale + offset
        return value.takeIf { it.isFinite() && it in range && (codes == null || it.toInt() in codes) }
    }
}

/** The float −1.0, the HAL's no-data fallback for a float GET. */
private val FLOAT_MINUS_ONE = (-1f).toBits()

/** The massage modes the firmware names: off, wave, pulse, stretch, smoothing, slack, the two hot stones. */
private val MASSAGE_MODES = setOf(1, 2, 3, 4, 5, 6, 8, 9)

/** The pages a GET rides ([DiagnosticExtra.pages]). */
private fun on(vararg pages: ExtraPage): Set<ExtraPage> = pages.toSet()

internal data class DiagnosticExtras(
    val values: Map<DiagnosticExtra, Double> = emptyMap(),
    /** When the batch's answer was in: the wall clock and `elapsedRealtime`, ms. */
    val atMillis: Long = 0, val atElapsed: Long = 0,
    /** How long the batch took, from sending it to its answer, ms; null when not measured. */
    val tookMs: Long? = null,
) {
    fun fresh(now: Long): Map<DiagnosticExtra, Double> =
        if (now >= atElapsed && now - atElapsed < STALE_MS) values else emptyMap()
    companion object { const val STALE_MS = 15_000L }
}

/**
 * A page in front that asks the batch for more (architecture, 6): GETs of its own, which join the
 * batch only while it is in front so no other page pays for them ([DiagnosticExtra.pages]), or a
 * quicker round ([periodMs]).
 */
internal enum class ExtraPage(
    /**
     * While the page is in front, a batch every [periodMs] from the start of one to the start of the
     * next - back to back when the batch is slower; the quickest page shown wins. Null keeps the
     * shared five seconds between one batch's answer and the next.
     */
    val periodMs: Long? = null,
) {
    // A system's «Состояние» (`*_STATE`) asks for what its scheme draws, every five seconds; its
    // «Потоковые данные» (`*_LIVE`) for its table's readings, round after round as the pack's (6.3) -
    // the floor keeps an instant answer from spinning. Which tab asks which: scan.extraPage.

    /** «Высоковольтная система · Состояние». */
    HV_STATE,
    /** «Высоковольтная система · Потоковые данные». */
    HV_LIVE(periodMs = 100),
    /** «Тяговая батарея · Потоковые данные»: the pack's readings are the shared batch's, and SOC in tenths. */
    PACK_LIVE(periodMs = 100),
    /** «Тяговая батарея · Флаги GB/T 32960». */
    GB_FLAGS,
    /** «Электропривод · Состояние». */
    DRIVES_STATE,
    /** «Электропривод · Потоковые данные». */
    DRIVES_LIVE(periodMs = 100),
    /** «Шасси · Состояние». */
    CHASSIS_STATE,
    /** «Шасси · Потоковые данные». */
    CHASSIS_LIVE(periodMs = 100),
    /** «Безопасность · Состояние». */
    SAFETY_STATE,
    /** «Безопасность · Потоковые данные». */
    SAFETY_LIVE(periodMs = 100),
    /** «Кузов · Состояние». */
    BODY_STATE,
    /** «Кузов · Потоковые данные». */
    BODY_LIVE(periodMs = 100),
    /** «Салон · Состояние». */
    CABIN,
    /** «Климат · Состояние». */
    CLIMATE_STATE,
    /** «Климат · Потоковые данные». */
    CLIMATE_LIVE(periodMs = 100),
    /** «Сеть 12 В · Состояние». */
    LV_STATE,
    /** «Сеть 12 В · Потоковые данные». */
    LV_LIVE(periodMs = 100),
}

internal object DiagnosticExtraReader {
    /** The batch of the system screens, every five seconds unless a page asks for quicker ([ExtraPage.periodMs]). */
    val command = command(emptySet())

    /**
     * The same with the GETs of [pages], those in front: after the shared ones, in the entries' order,
     * each once however many of its pages show.
     */
    fun command(pages: Set<ExtraPage>): String = batch(
        DiagnosticExtra.entries.filter { it.pages.isEmpty() } +
            DiagnosticExtra.entries.filter { e -> e.pages.any { it in pages } },
    )

    fun batch(gets: List<DiagnosticExtra>) = gets.joinToString("; ") {
        "echo @@extra:${it.key}; service call autoservice ${it.transact} i32 ${it.device} i32 ${it.fid}"
    }

    /** The batch's answer: each GET decoded, then each gated reading resolved by its gate ([ExtraGates]). */
    fun decode(output: String, atMillis: Long, atElapsed: Long, tookMs: Long? = null): DiagnosticExtras {
        val sections = output.split(Regex("(?m)^@@extra:"))
        val byKey = DiagnosticExtra.entries.associateBy { it.key }
        val values = sections.mapNotNull { section ->
            val signal = byKey[section.lineSequence().firstOrNull()?.trim()] ?: return@mapNotNull null
            val value = word(section)?.let(signal::decode) ?: return@mapNotNull null
            signal to value
        }.toMap()
        return DiagnosticExtras(ExtraGates.apply(values), atMillis, atElapsed, tookMs)
    }

    /** The word of a successful `service call` reply: status 0, then one 32-bit value. */
    fun word(output: String): Int? = output.lineSequence().mapNotNull { ServiceCallParcel.oneLineWords(it) }
        .firstOrNull { it.size == 2 && it[0] == 0 }?.get(1)
}

/**
 * Readings whose meaning another GET of the same batch decides, as their cards in research
 * stream-selection-20261021.json say. A gate rides every batch its reading rides - the shared one, or
 * every page of the reading - and a reading polled alone is asked with its gate (`SignalGet`); a gate
 * that did not come, or says no, makes its reading «н/д».
 */
internal object ExtraGates {
    private val GATES: Map<DiagnosticExtra, DiagnosticExtra> = mapOf(
        DiagnosticExtra.OUTSIDE_TEMP to DiagnosticExtra.TEMP_UNIT,
        // a pedal's travel counts only while its validity flag reads 1, `DATA_FLAG_VALID`
        DiagnosticExtra.ACCEL_PEDAL to DiagnosticExtra.ACCEL_PEDAL_VALID,
        DiagnosticExtra.BRAKE_PEDAL to DiagnosticExtra.BRAKE_PEDAL_VALID,
    )

    /** [reading]'s gate, or null. */
    fun gate(reading: DiagnosticExtra): DiagnosticExtra? = GATES[reading]

    /** [values] with every gated reading resolved by its gate's value among them. */
    fun apply(values: Map<DiagnosticExtra, Double>): Map<DiagnosticExtra, Double> = buildMap {
        values.forEach { (reading, value) ->
            val gate = GATES[reading]
            if (gate == null) put(reading, value) else resolve(reading, value, values[gate])?.let { put(reading, it) }
        }
    }

    private fun resolve(reading: DiagnosticExtra, value: Double, gate: Double?): Double? = when (reading) {
        DiagnosticExtra.OUTSIDE_TEMP -> celsius(value, gate)
        else -> value.takeIf { gate == 1.0 }
    }

    /**
     * C2: `AC_TEMP_OUT` is in the unit `AC_TEMPERATURE_UNIT` names, 1 °C and 0 °F. The screens say °C,
     * so a Fahrenheit reading is converted; the stock Celsius domain is −40…50, and −40, the byte's
     * zero, is no sensor here as on every temperature.
     */
    private fun celsius(value: Double, unit: Double?): Double? {
        val c = when (unit) {
            1.0 -> value
            0.0 -> (value - 32.0) * 5.0 / 9.0
            else -> return null
        }
        return c.takeIf { it in OUTSIDE_CELSIUS }
    }

    private val OUTSIDE_CELSIUS = -39.0..50.0
}
