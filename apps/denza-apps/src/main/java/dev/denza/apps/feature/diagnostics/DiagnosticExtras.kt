package dev.denza.apps.feature.diagnostics

import dev.denza.apps.platform.shell.ServiceCallParcel

/**
 * Qualified scalar GETs from profile 170. Bounds reject sentinels, not diagnose faults.
 *
 * [page]: read only while that page is in front ([DiagnosticExtraReader.command]), null for the batch
 * every system screen shares; [codes], where a range would admit a code the firmware does not name,
 * the only codes taken.
 */
internal enum class DiagnosticExtra(
    val key: String, val device: Int, val fid: Int, val transact: Int,
    private val scale: Double, private val offset: Double, private val range: ClosedFloatingPointRange<Double>,
    val page: ExtraPage? = null, private val codes: Set<Int>? = null,
) {
    SOC("soc", 1014, 1246777400, 7, 1.0, 0.0, 0.0..100.0),
    SOH("soh", 1014, 1145045032, 5, 1.0, 0.0, 0.0..100.0),
    TEMP_MIN("temp_min", 1014, 1148190736, 5, 1.0, -40.0, -39.0..150.0),
    TEMP_MAX("temp_max", 1014, 1148190752, 5, 1.0, -40.0, -39.0..150.0),
    LV("lv", 1001, 1128267816, 7, 1.0, 0.0, 1.0..30.0),
    FUEL("fuel", 1014, 1246785600, 5, 1.0, 0.0, 0.0..100.0),
    BUS_FRONT("bus_front", 1039, 1178628128, 5, 1.0, 0.0, 1.0..1000.0),
    BUS_REAR("bus_rear", 1039, 1178628112, 5, 1.0, 0.0, 1.0..1000.0),
    CHARGE_CURRENT("charge_current", 1009, 1145045016, 7, 1.0, 0.0, -1000.0..1000.0),
    // The stock GB/T 32960 collector reads this FID and caps it at 60000 (gbacqservice 1381C..13850;
    // research insulation-consumer-20261021.json): above that is the firmware's invalid code, FFFE and
    // FFFF among them, not a resistance - «н/д», never 60000 and never judged against the norm.
    INSULATION("insulation", 1039, 1134559256, 5, 1.0, 0.0, 0.0..60000.0),
    // 254 and 255 are the firmware's invalid counts: the stock reader takes 1..253 (gbdataservice
    // E9A0..EA2C; research battery-validity-followup-20261021.json). PROBE_COUNT likewise.
    CELL_COUNT("cell_count", 1001, 1134559240, 5, 1.0, 0.0, 1.0..253.0),
    CABIN_TEMP("cabin_temp", 1000, 1031798832, 5, 1.0, 0.0, -39.0..150.0),
    OUTSIDE_TEMP("outside_temp", 1000, 1077936184, 5, 1.0, 0.0, -39.0..150.0),
    PM_IN("pm_in", 1008, 1331691536, 5, 1.0, 0.0, 0.0..999.0),
    PM_OUT("pm_out", 1008, 1331691548, 5, 1.0, 0.0, 0.0..999.0),
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
    IGBT_REAR_LEFT("igbt_rear_left", 1039, 0x2D000180, 5, 1.0, 0.0, -39.0..150.0),
    IGBT_REAR_RIGHT("igbt_rear_right", 1039, 0x2D000188, 5, 1.0, 0.0, -39.0..150.0),
    RPM_FRONT("rpm_front", 1012, 0x44100008, 5, 1.0, 0.0, -20000.0..20000.0),
    RPM_REAR_LEFT("rpm_rear_left", 1039, 0x0FC000D0, 5, 1.0, 0.0, 0.0..20000.0),
    RPM_REAR_RIGHT("rpm_rear_right", 1039, 0x0FC000E0, 5, 1.0, 0.0, 0.0..20000.0),
    TORQUE_FRONT("torque_front", 1012, 0x44100018, 7, 1.0, 0.0, -5000.0..5000.0),
    TORQUE_REAR_LEFT("torque_rear_left", 1039, 0x2850002C, 7, 1.0, 0.0, -5000.0..5000.0),
    TORQUE_REAR_RIGHT("torque_rear_right", 1039, 0x2850003C, 7, 1.0, 0.0, -5000.0..5000.0),
    CURRENT_FRONT("current_front", 1009, 0x46C00008, 7, 1.0, 0.0, -2000.0..2000.0),
    CURRENT_REAR_LEFT("current_rear_left", 1039, 0x28500178, 7, 1.0, 0.0, -2000.0..2000.0),
    CURRENT_REAR_RIGHT("current_rear_right", 1039, 0x28500188, 7, 1.0, 0.0, -2000.0..2000.0),

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
    SEAT_HEAT_FL("seat_heat_fl", 1023, 0x4FA0000B, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_HEAT_FR("seat_heat_fr", 1023, 0x4FA00013, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_HEAT_RL("seat_heat_rl", 1023, 0x4860000B, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_HEAT_RR("seat_heat_rr", 1023, 0x48600013, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_VENT_FL("seat_vent_fl", 1023, 0x4FA00008, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_VENT_FR("seat_vent_fr", 1023, 0x4FA00010, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_VENT_RL("seat_vent_rl", 1023, 0x48600008, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    SEAT_VENT_RR("seat_vent_rr", 1023, 0x48600010, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    /** Massage mode: 1 off, 2..6 the programmes, 8 and 9 the hot stones; 7 is not named. */
    MASSAGE_MODE_FL("massage_mode_fl", 1023, 0x4FA00030, 5, 1.0, 0.0, 1.0..9.0, page = ExtraPage.CABIN, codes = MASSAGE_MODES),
    MASSAGE_MODE_FR("massage_mode_fr", 1023, 0x4FA00038, 5, 1.0, 0.0, 1.0..9.0, page = ExtraPage.CABIN, codes = MASSAGE_MODES),
    MASSAGE_MODE_RL("massage_mode_rl", 1023, 0x4FA00020, 5, 1.0, 0.0, 1.0..9.0, page = ExtraPage.CABIN, codes = MASSAGE_MODES),
    MASSAGE_MODE_RR("massage_mode_rr", 1023, 0x4FA00028, 5, 1.0, 0.0, 1.0..9.0, page = ExtraPage.CABIN, codes = MASSAGE_MODES),
    /** Massage level: 0 invalid, 1..3 weak, medium, strong, 4 off. */
    MASSAGE_LEVEL_FL("massage_level_fl", 1023, 0x4FA00034, 5, 1.0, 0.0, 0.0..4.0, page = ExtraPage.CABIN),
    MASSAGE_LEVEL_FR("massage_level_fr", 1023, 0x4FA0003C, 5, 1.0, 0.0, 0.0..4.0, page = ExtraPage.CABIN),
    MASSAGE_LEVEL_RL("massage_level_rl", 1023, 0x4FA00024, 5, 1.0, 0.0, 0.0..4.0, page = ExtraPage.CABIN),
    MASSAGE_LEVEL_RR("massage_level_rr", 1023, 0x4FA0002C, 5, 1.0, 0.0, 0.0..4.0, page = ExtraPage.CABIN),
    /** The front belts on the cluster's device: 1 fastened, 0 not. */
    BELT_FL("belt_fl", 1007, 0x29400018, 5, 1.0, 0.0, 0.0..1.0, page = ExtraPage.CABIN),
    BELT_FR("belt_fr", 1007, 0x2610001B, 5, 1.0, 0.0, 0.0..1.0, page = ExtraPage.CABIN),
    /** The second row's belts: 2 fastened, 1 not. */
    BELT_RL("belt_rl", 1023, 0x3120000A, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    BELT_RR("belt_rr", 1023, 0x3120000C, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    BELT_RC("belt_rc", 1023, 0x3120000E, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    /** Who sits: 2 seated, any other code not, as DiCarServer reads it (the framework's constants disagree; research). */
    SEATED_FL("seated_fl", 1042, 0x31200040, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.CABIN),
    SEATED_FR("seated_fr", 1042, 0x31200016, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.CABIN),
    SEATED_RL("seated_rl", 1042, 0x31200018, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.CABIN),
    SEATED_RR("seated_rr", 1042, 0x3120001C, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.CABIN),
    SEATED_RC("seated_rc", 1042, 0x3120001A, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.CABIN),
    /** The steering wheel's heating, the feedback: 1 off, 2 on; 0 invalid. */
    WHEEL_HEAT("wheel_heat", 1023, 0x4290000E, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    /** What a fridge does: 1 cold, 2 hot, 3 off, 4 fresh. */
    FRIDGE_FRONT("fridge_front", 1023, 0x48C00018, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    FRIDGE_REAR("fridge_rear", 1023, 0x3AE00008, 5, 1.0, 0.0, 1.0..4.0, page = ExtraPage.CABIN),
    /** A mode's set point, raw in °C units: cold raw 1..34 (raw − 19), hot 1..16 (raw + 34), fresh 1..3 (raw + 7). */
    FRIDGE_FRONT_COLD("fridge_front_cold", 1023, 0x3D60002A, 5, 1.0, 0.0, 1.0..34.0, page = ExtraPage.CABIN),
    FRIDGE_FRONT_HOT("fridge_front_hot", 1023, 0x3D600030, 5, 1.0, 0.0, 1.0..16.0, page = ExtraPage.CABIN),
    FRIDGE_FRONT_FRESH("fridge_front_fresh", 1023, 0x3D600035, 5, 1.0, 0.0, 1.0..3.0, page = ExtraPage.CABIN),
    FRIDGE_REAR_COLD("fridge_rear_cold", 1023, 0x3AE00010, 5, 1.0, 0.0, 1.0..34.0, page = ExtraPage.CABIN),
    FRIDGE_REAR_HOT("fridge_rear_hot", 1023, 0x3AE00018, 5, 1.0, 0.0, 1.0..16.0, page = ExtraPage.CABIN),
    FRIDGE_REAR_FRESH("fridge_rear_fresh", 1023, 0x3AE0001D, 5, 1.0, 0.0, 1.0..3.0, page = ExtraPage.CABIN),
    /** A fridge's popup reminder: 0 none, 1 no link, 2 long work, 3 heat mode, 4 door not closed. */
    FRIDGE_FRONT_REMIND("fridge_front_remind", 1023, 0x48C00029, 5, 1.0, 0.0, 0.0..4.0, page = ExtraPage.CABIN),
    FRIDGE_REAR_REMIND("fridge_rear_remind", 1023, 0x3AE00038, 5, 1.0, 0.0, 0.0..4.0, page = ExtraPage.CABIN),
    /** The wireless charging pads: 0 charging, 1 full, 2 fault, 3 standby. */
    PAD_LEFT("pad_left", 1009, 0x41E00009, 5, 1.0, 0.0, 0.0..3.0, page = ExtraPage.CABIN),
    PAD_RIGHT("pad_right", 1009, 0xC1E00012.toInt(), 5, 1.0, 0.0, 0.0..3.0, page = ExtraPage.CABIN),
    /** The reading lights, front left and right, then the second row's (LM, RM): 2 on, 1 off. */
    READING_FL("reading_fl", 1004, 0x3FE0000A, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    READING_FR("reading_fr", 1004, 0x3FE0000C, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    READING_RL("reading_rl", 1004, 0x3FE0000E, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    READING_RR("reading_rr", 1004, 0x3FE00010, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),
    /** The HUD's switch, the feedback: 1 on, 2 off. */
    HUD("hud", 1023, 0x38B0001C, 5, 1.0, 0.0, 1.0..2.0, page = ExtraPage.CABIN),

    // «Тяговая батарея · Флаги GB/T 32960» (architecture 7.2): the 14 device-1039 readings from which the
    // stock reporter gbacqservice sets GB/T 32960.3's general alarm flags (research
    // gb-alarm-dtc-boundary-20261021.json, alarmInputs), integers on 5 as the sweep of 2026-10-20 read
    // them, in the standard's bit order. Raw codes; which raise a flag is scan/GbFlags. The reporter's
    // predicate was qualified on 0..255; 255 is taken as the byte's no-data code and, with 65535, refused.
    /** Bit 0, the pack's temperature difference. */
    GB_TEMP_DIFF("gb_temp_diff", 1039, 0x43800008, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bit 1, the pack's high temperature. */
    GB_PACK_HOT("gb_pack_hot", 1039, 0x2440000C, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bits 2 and 3, the pack's over- and undervoltage. */
    GB_PACK_OVERVOLT("gb_pack_overvolt", 1039, 0x4380000E, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    GB_PACK_UNDERVOLT("gb_pack_undervolt", 1039, 0x43800010, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bits 7 and 8, SOC high and SOC jump. */
    GB_SOC_HIGH("gb_soc_high", 1039, 0x43800014, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    GB_SOC_JUMP("gb_soc_jump", 1039, 0x43800016, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bits 9 and 10, the pack's mismatch and the cells' poor consistency. */
    GB_PACK_MISMATCH("gb_pack_mismatch", 1039, 0x4380000C, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    GB_CELL_CONSISTENCY("gb_cell_consistency", 1039, 0x4380000A, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bits 12 and 14, the DC-DC's temperature and state. */
    GB_DCDC_TEMP("gb_dcdc_temp", 1039, 0x26C00010, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    GB_DCDC_STATE("gb_dcdc_state", 1039, 0x26C00022, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bit 15, the motor controller's temperature. */
    GB_CONTROLLER_TEMP("gb_controller_temp", 1039, 0x2400000C, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bit 16, the high-voltage interlock. */
    GB_HVIL("gb_hvil", 1039, 0x2440003E, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bit 17, the drive motor's temperature. */
    GB_MOTOR_TEMP("gb_motor_temp", 1039, 0x2400000A, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS),
    /** Bit 18, the pack's overcharge. */
    GB_OVERCHARGE("gb_overcharge", 1039, 0x43800012, 5, 1.0, 0.0, 0.0..254.0, page = ExtraPage.GB_FLAGS);

    fun decode(word: Int): Double? {
        // Same native no-data words as the shared AutoserviceShell; -1f is not a current.
        if (word == -10013 || word == -10011 || word == 0xBF800000.toInt()) return null
        val raw = if (transact == 7) Float.fromBits(word).toDouble() else word.toDouble()
        val value = raw * scale + offset
        return value.takeIf { it.isFinite() && it in range && (codes == null || it.toInt() in codes) }
    }
}

/** The massage modes the firmware names: off, wave, pulse, stretch, smoothing, slack, the two hot stones. */
private val MASSAGE_MODES = setOf(1, 2, 3, 4, 5, 6, 8, 9)

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
 * batch only while it is in front so no other page pays for them ([DiagnosticExtra.page]), or a
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
    /** «Салон · Состояние». */
    CABIN,
    /** «Тяговая батарея · Флаги GB/T 32960». */
    GB_FLAGS,
    /**
     * «Тяговая батарея · Потоковые данные»: no GETs of its own - the pack's are the shared batch's -
     * but read as a stream, round after round (6.3). The floor keeps an instant answer from spinning.
     */
    PACK_LIVE(periodMs = 100),
}

internal object DiagnosticExtraReader {
    /** The batch of the system screens, every five seconds unless a page asks for quicker ([ExtraPage.periodMs]). */
    val command = command(emptySet())

    /** The same with the GETs of [pages], those in front: in the entries' order, after the shared ones. */
    fun command(pages: Set<ExtraPage>): String = batch(DiagnosticExtra.entries.filter { it.page == null || it.page in pages })

    private fun batch(gets: List<DiagnosticExtra>) = gets.joinToString("; ") {
        "echo @@extra:${it.key}; service call autoservice ${it.transact} i32 ${it.device} i32 ${it.fid}"
    }

    fun decode(output: String, atMillis: Long, atElapsed: Long, tookMs: Long? = null): DiagnosticExtras {
        val sections = output.split(Regex("(?m)^@@extra:"))
        val byKey = DiagnosticExtra.entries.associateBy { it.key }
        val values = sections.mapNotNull { section ->
            val signal = byKey[section.lineSequence().firstOrNull()?.trim()] ?: return@mapNotNull null
            val value = word(section)?.let(signal::decode) ?: return@mapNotNull null
            signal to value
        }.toMap()
        return DiagnosticExtras(values, atMillis, atElapsed, tookMs)
    }

    /** The word of a successful `service call` reply: status 0, then one 32-bit value. */
    fun word(output: String): Int? = output.lineSequence().mapNotNull { ServiceCallParcel.oneLineWords(it) }
        .firstOrNull { it.size == 2 && it[0] == 0 }?.get(1)
}
