package dev.denza.apps.feature.diagnostics.report

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * «Отчёт» (architecture, 7.4): what «Сохранить» can put in `Download/Denza/`, section by section. The
 * PDF always has its head, its summary and its footer; [RAW] adds the JSON of the scan's exchanges,
 * [STREAM] a copy of the last recording beside them.
 */
internal enum class ReportSection(val title: String) {
    CODES("Коды и статусы"),
    BLOCKS("Блоки и номера ПО"),
    PACK("Тяговая батарея"),
    ENGINE("Двигатель"),
    RAW("Сырые ответы"),
    STREAM("Запись потока"),
}

/**
 * Everything the report says, as plain data (architecture, 7.4): built by [ReportInputs] from the
 * model, the shown scan's exchanges and the car's readings, with what the journal and the further
 * reads add where they are connected. The PDF ([ReportDocument]) and the JSON ([ReportJson]) are made
 * from it and nothing else.
 */
internal data class ReportInput(
    /** «Denza Z9 GT DM». */
    val car: String,
    /** `22 F190` or OBD `09 02` where read; null otherwise, printed «—». */
    val vin: String?,
    val odometerKm: Double?,
    /** When the shown scan finished; null before any scan. */
    val scanAt: ZonedDateTime?,
    /** When the report was put together. */
    val madeAt: ZonedDateTime,
    /** P by the hub: true, false, or null not known. */
    val parked: Boolean?,
    /** The 12 V line, V. */
    val lowVoltage: Double?,
    val summary: ReportSummary,
    /** Every record of the scan, block by block in the autoscan's order. */
    val codes: List<ReportCode>,
    val pack: ReportPack,
    /** The engine's ECM; null when it was neither scanned nor opened. */
    val engine: ReportEngine?,
    /** Every catalogued block in the autoscan's order. */
    val blocks: List<ReportBlock>,
    val exchanges: ReportExchanges,
    /** The newest stream recording; null when there is none. */
    val recording: ReportRecording? = null,
    /** What the journal adds; null while it is not connected. */
    val journal: ReportJournal? = null,
)

/** «68 из 68 блоков ответили · активных 0 · ожидающих 0 · в истории 18 записей · блоков с записями 6». */
internal data class ReportSummary(
    val answered: Int,
    val catalogued: Int,
    /** Memories read, of [answered]. */
    val memoriesRead: Int,
    val active: Int,
    val pending: Int,
    val history: Int,
    /** Blocks with at least one record. */
    val blocksWithRecords: Int,
)

/** How a record reads: the v4 marks ● active, ◌ pending, ○ in history. */
internal enum class ReportCodeKind(val word: String) {
    ACTIVE("активный"),
    PENDING("ожидающий"),
    HISTORY("история"),
    /** A record with none of those bits: one outside the request mask. */
    OTHER("запись"),
}

/** A code as the journal keys it: the block's request address and the code as the screens print it («C1160-06»). */
internal data class ReportCodeKey(val request: Int, val code: String)

internal data class ReportCode(
    val request: Int,
    /** «LA_BCM». */
    val block: String,
    /** «720». */
    val address: String,
    /** «C1160-06». */
    val code: String,
    /** «06», «—». */
    val failureType: String,
    /** «Отказ алгоритма». */
    val failureName: String,
    /** The public catalog's heading; null where it has none. */
    val description: String?,
    /** The ISO 14229-1 status byte. */
    val status: Int,
    val kind: ReportCodeKind,
    /** The day the journal first read it; null without a journal or a record of it. */
    val firstSeen: LocalDate? = null,
)

/** The pack as the `autoservice` batch and the hub read it when the report was made. */
internal data class ReportPack(
    val sohPercent: Double?,
    val socPercent: Double?,
    val cellMinMv: Double?,
    val cellMaxMv: Double?,
    /** kΩ by the GB/T 32960 field: the BMC channel and the second one; printed in MΩ. */
    val insulationBmcKohm: Double?,
    val insulationSecondKohm: Double?,
    /** The modules' temperatures, °C. */
    val probeMinC: Double?,
    val probeMaxC: Double?,
    /** The cells' spread at full charge, from the journal's snapshots at SOC ≥ 95 %; null when it has none. */
    val fullChargeSpreadMv: Double? = null,
) {
    val cellSpreadMv: Double? get() = if (cellMinMv != null && cellMaxMv != null) cellMaxMv - cellMinMv else null
    val any: Boolean
        get() = listOf(sohPercent, socPercent, cellMinMv, cellMaxMv, insulationBmcKohm, insulationSecondKohm, probeMinC, probeMaxC, fullChargeSpreadMv)
            .any { it != null }
}

/** One named value: a freeze frame's PID, a field. */
internal data class ReportField(val name: String, val value: String)

internal data class ReportEngine(
    /** PID 01's MIL; null not read. */
    val mil: Boolean?,
    /** Monitors ready and supported; null when PID 01 was not read. */
    val ready: Int?,
    val supported: Int?,
    /** «4.00.10 · 19031900» (F195) or the F194 code; null not read. */
    val software: String?,
    /** Mode 09 04; null not read. */
    val calibration: String?,
    /** Mode 02 frame 0 where read; null otherwise. */
    val freezeFrame: List<ReportField>? = null,
    /** Mode 06 where the ECM's screen read it; null otherwise. */
    val tests: ReportTests? = null,
)

/**
 * The ECM's Mode 06 results as «Тесты» showed them (architecture, 7.3, 7.4): on the sheet the tests out of their
 * limits and a count of the rest, in the JSON every monitor with its exchange and every test with its bytes.
 */
internal data class ReportTests(
    val passed: Int,
    val notRun: Int,
    val monitors: List<ReportTestMonitor>,
) {
    /** Each test out of its limits with its monitor. */
    val out: List<Pair<ReportTestMonitor, ReportTestRow>> get() = monitors.flatMap { m -> m.tests.filter { it.pass == false }.map { m to it } }
}

internal data class ReportTestMonitor(
    /** «21». */
    val mid: String,
    /** «Катализатор, ряд 1». */
    val name: String,
    /** «не отдаёт», «нет ответа»; null where it gave its tests. */
    val word: String?,
    /** The exchange as the screen shows it: «06 21 → 46 21 80 03 00 3E 00 1E 00 64», a line two tests. */
    val exchange: List<String>,
    val tests: List<ReportTestRow>,
)

internal data class ReportTestRow(
    val tid: String,
    val uasid: String,
    /** The words in their unit, «0,21»; «—» for a test that has not run. */
    val value: String,
    val min: String,
    val max: String,
    /** «мс», «» for a count, «ед. блока» outside J1979's table. */
    val unit: String,
    /** Null for a test that has not run. */
    val pass: Boolean?,
    /** The nine bytes, spaced. */
    val raw: String,
)

internal data class ReportBlock(
    /** Null for a block without a route. */
    val request: Int?,
    val abbreviation: String,
    /** «7E0», «—». */
    val address: String,
    /** The scan's `22 F194` software code, 18 hex digits; null where none came. */
    val f194: String?,
    /** «4.00.10 · 19031900»; null where it was not read. */
    val f195: String?,
    /** «—» for a block whose memory was read clean, «история 8», «нет ответа», … */
    val status: String,
    /** The ModuleStatus name, for programs. */
    val statusCode: String,
)

/** What the scan asked: the services in the footer, how many exchanges and how long. */
internal data class ReportExchanges(
    /** «22 F194», «19 01 09», «19 02 09», «OBD 01/03/07/09/0A»: identification, memory, OBD, each in the order first asked. */
    val services: List<String>,
    val count: Int,
    /** From the first exchange's start to the last one's end; null without exchanges. */
    val durationMs: Long?,
)

/** The newest stream recording (architecture, 6.1): its file, its length and how many signals it holds. */
internal data class ReportRecording(
    /** Absolute path of `stream-<time>.csv` under the app's files. */
    val path: String,
    val durationMs: Long?,
    val signals: Int,
)

/**
 * What the journal adds to the report; its wave fills it, the report reads only these fields (7.4).
 * Every field is optional: without it the report says what it knows without the journal.
 */
internal data class ReportJournal(
    /** The day each code was first read. */
    val firstSeen: Map<ReportCodeKey, LocalDate> = emptyMap(),
    /** The cells' spread at full charge, mV (snapshots at SOC ≥ 95 %). */
    val fullChargeSpreadMv: Double? = null,
    /** The journal's excerpt for the JSON, as JSON text (an object or an array); null for none. */
    val excerptJson: String? = null,
)

/** What the further reads add (VIN, the engine's freeze frame); their wave fills it (7.4). */
internal data class ReportReads(
    val vin: String? = null,
    /** Mode 02 frame 0 of the ECM, in the order read. */
    val engineFreezeFrame: List<ReportField>? = null,
    /** Mode 06 of the ECM where its screen read it. */
    val engineTests: ReportTests? = null,
)

/** One line of «что войдёт»: the section, whether there is anything to put in it, and its figures in words. */
internal data class ReportOption(
    val section: ReportSection,
    val available: Boolean,
    /** «18 записей · 6 блоков», «68», «148 обменов», «00:42 · 4 сигнала». */
    val detail: String,
    /** Ticked when the screen opens: every available section but the recording. */
    val byDefault: Boolean,
)

/** What «Сохранить» came to. A failure is no message: the button is there again (architecture, 7). */
internal sealed interface ReportOutcome {
    data object Idle : ReportOutcome
    data object Saving : ReportOutcome
    data class Saved(
        /** «Download/Denza». */
        val folder: String,
        /** The files' names as the provider gave them, PDF first. */
        val names: List<String>,
        val pages: Int,
    ) : ReportOutcome
    data object Failed : ReportOutcome
}

/** The report as the model carries it: the input it would be made of, its options, and the last save. */
internal data class ReportState(
    /** Null while the section has nothing to report: no scan and no reading. */
    val input: ReportInput? = null,
    val options: List<ReportOption> = emptyList(),
    val outcome: ReportOutcome = ReportOutcome.Idle,
)
