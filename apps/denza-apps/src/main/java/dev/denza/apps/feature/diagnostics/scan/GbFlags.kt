package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra

/**
 * «Тяговая батарея · Флаги GB/T 32960» (architecture, 7.2): GB/T 32960.3's general alarm flags as
 * this car's stock reporter `gbacqservice` sets them. Each flag is one reading of device 1039; its
 * callback raises the bit for the codes in [Flag.raising] - the reporter's own with the stock
 * simulation off (research gb-alarm-dtc-boundary-20261021.json, `triggerRawValuesSimulationOff`) -
 * and clears it for any other code, so every other code read is «норма». Nothing read, or a read the
 * GET refuses, is «н/д». The standard's other five flags - SOC low, cell over- and undervoltage,
 * insulation, the brake system - this reporter does not set from a reading; they are not shown.
 */
internal object GbFlags {
    private const val NA = "н/д"

    /** One flag: its bit in table 18, the reading it is set from, its name, the codes that raise it. */
    data class Flag(val bit: Int, val extra: DiagnosticExtra, val name: String, val raising: Set<Int>)

    /** The pack's nine, in the standard's bit order. */
    val PACK = listOf(
        Flag(0, DiagnosticExtra.GB_TEMP_DIFF, "Разброс температур", setOf(2)),
        Flag(1, DiagnosticExtra.GB_PACK_HOT, "Высокая температура", setOf(1, 2)),
        Flag(2, DiagnosticExtra.GB_PACK_OVERVOLT, "Перенапряжение", setOf(2)),
        Flag(3, DiagnosticExtra.GB_PACK_UNDERVOLT, "Пониженное напряжение", setOf(2)),
        Flag(7, DiagnosticExtra.GB_SOC_HIGH, "Высокий SOC", setOf(2)),
        Flag(8, DiagnosticExtra.GB_SOC_JUMP, "Скачок SOC", setOf(2)),
        Flag(9, DiagnosticExtra.GB_PACK_MISMATCH, "Несоответствие батареи", setOf(2)),
        Flag(10, DiagnosticExtra.GB_CELL_CONSISTENCY, "Однородность ячеек", setOf(2)),
        Flag(18, DiagnosticExtra.GB_OVERCHARGE, "Перезаряд", setOf(2)),
    )

    /** The drive's and the high voltage's five: bit 12 is the DC-DC's temperature, as table 18 has it. */
    val DRIVE = listOf(
        Flag(12, DiagnosticExtra.GB_DCDC_TEMP, "Температура DC-DC", setOf(1)),
        Flag(14, DiagnosticExtra.GB_DCDC_STATE, "Состояние DC-DC", setOf(2)),
        Flag(15, DiagnosticExtra.GB_CONTROLLER_TEMP, "Температура контроллера", setOf(2)),
        Flag(16, DiagnosticExtra.GB_HVIL, "Блокировка ВВ (HVIL)", setOf(1)),
        Flag(17, DiagnosticExtra.GB_MOTOR_TEMP, "Температура мотора", setOf(2)),
    )

    val ALL = PACK + DRIVE

    /** A code that raises the flag, another code read, or nothing. */
    fun state(flag: Flag, raw: Int?): GbFlagState = when {
        raw == null -> GbFlagState.NONE
        raw in flag.raising -> GbFlagState.ALARM
        else -> GbFlagState.OK
    }

    fun word(state: GbFlagState): String = when (state) {
        GbFlagState.OK -> "норма"
        GbFlagState.ALARM -> "тревога"
        GbFlagState.NONE -> NA
    }

    /** The tab from the batch: a reading only while it is fresh. */
    fun view(car: CarReadings): GbFlagsView {
        fun group(title: String, flags: List<Flag>) = GbFlagGroup(title, flags.map { f ->
            val raw = car.extra(f.extra)?.toInt()
            val state = state(f, raw)
            GbFlagView(f.bit, "%08X".format(f.extra.fid), f.name, raw, state, word(state))
        })
        val groups = listOf(group("БАТАРЕЯ", PACK), group("ПРИВОД И ВЫСОКОЕ НАПРЯЖЕНИЕ", DRIVE))
        val flags = groups.flatMap { it.flags }
        val raised = flags.count { it.state == GbFlagState.ALARM }
        val count = if (flags.all { it.state == GbFlagState.NONE }) NA else raised.toString()
        return GbFlagsView(
            head = "ОБЩИЕ ТРЕВОГИ GB/T 32960.3 · ${flags.size}",
            counters = listOf(HeadCounter("ТРЕВОГ", count, on = raised > 0)),
            groups = groups,
        )
    }
}
