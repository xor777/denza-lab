package dev.denza.apps.feature.diagnostics

import dev.denza.apps.platform.shell.ServiceCallParcel

/** Qualified scalar GETs from profile 170. Bounds reject sentinels, not diagnose faults. */
internal enum class DiagnosticExtra(
    val key: String, val device: Int, val fid: Int, val transact: Int,
    private val scale: Double, private val offset: Double, private val range: ClosedFloatingPointRange<Double>,
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
    INSULATION("insulation", 1039, 1134559256, 5, 1.0, 0.0, 0.0..65534.0),
    CELL_COUNT("cell_count", 1001, 1134559240, 5, 1.0, 0.0, 1.0..500.0),
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
    INSULATION_2("insulation_2", 1039, 0x26200010, 5, 1.0, 0.0, 0.0..65534.0),
    PROBE_COUNT("probe_count", 1039, 0x43A00010, 5, 1.0, 0.0, 1.0..500.0),
    /** Discharge main contactor state; the values are not decoded. */
    CONTACTOR("contactor", 1032, 0x34400010, 5, 1.0, 0.0, 0.0..254.0),
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
    CURRENT_REAR_RIGHT("current_rear_right", 1039, 0x28500188, 7, 1.0, 0.0, -2000.0..2000.0);
    fun decode(word: Int): Double? {
        // Same native no-data words as the shared AutoserviceShell; -1f is not a current.
        if (word == -10013 || word == -10011 || word == 0xBF800000.toInt()) return null
        val raw = if (transact == 7) Float.fromBits(word).toDouble() else word.toDouble()
        val value = raw * scale + offset
        return value.takeIf { it.isFinite() && it in range }
    }
}

internal data class DiagnosticExtras(
    val values: Map<DiagnosticExtra, Double> = emptyMap(),
    val atMillis: Long = 0, val atElapsed: Long = 0,
) {
    fun fresh(now: Long): Map<DiagnosticExtra, Double> =
        if (now >= atElapsed && now - atElapsed < STALE_MS) values else emptyMap()
    companion object { const val STALE_MS = 15_000L }
}

internal object DiagnosticExtraReader {
    val command = DiagnosticExtra.entries.joinToString("; ") {
        "echo @@extra:${it.key}; service call autoservice ${it.transact} i32 ${it.device} i32 ${it.fid}"
    }
    fun decode(output: String, atMillis: Long, atElapsed: Long): DiagnosticExtras {
        val sections = output.split(Regex("(?m)^@@extra:"))
        val byKey = DiagnosticExtra.entries.associateBy { it.key }
        val values = sections.mapNotNull { section ->
            val signal = byKey[section.lineSequence().firstOrNull()?.trim()] ?: return@mapNotNull null
            val value = word(section)?.let(signal::decode) ?: return@mapNotNull null
            signal to value
        }.toMap()
        return DiagnosticExtras(values, atMillis, atElapsed)
    }

    /** The word of a successful `service call` reply: status 0, then one 32-bit value. */
    fun word(output: String): Int? = output.lineSequence().mapNotNull { ServiceCallParcel.oneLineWords(it) }
        .firstOrNull { it.size == 2 && it[0] == 0 }?.get(1)
}
