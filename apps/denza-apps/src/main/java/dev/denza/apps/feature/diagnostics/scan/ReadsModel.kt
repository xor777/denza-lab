package dev.denza.apps.feature.diagnostics.scan

/**
 * The reads of the owner's decision of 2026-10-23 (architecture, 6, 7.3) as data for the screens: a code's
 * snapshot and extended data, the OBD freeze frame and test results, the VIN. Each result keeps the bytes it
 * came from ([ExchangeData]), so a code's screen can say how it was read. These are the screens' inputs, not
 * their views: the words a screen draws are the screen's own.
 */
internal enum class ReadOutcome {
    /** Nothing asked yet. */
    NOT_READ,
    /** Asked; the answer is on its way. */
    READING,
    /** A complete positive answer that holds nothing: no record, no stored frame, no test, a VIN of filler. */
    EMPTY,
    /** A complete positive answer with something in it. */
    READ,
    /** A negative answer: the module refused or does not offer it ([ExchangeData.answer] has the NRC). */
    REFUSED,
    /** No answer: the gateway's 3 or 4, the helper's own wait, a busy gateway. */
    NO_REPLY,
    /** An answer came and does not hold together; its bytes are in the exchange. */
    INCONSISTENT,
}

/** One exchange as it went: what «как прочитан» shows. */
internal data class ExchangeData(
    /** «7E7 → 7EF». */
    val route: String,
    /** «19 04 1A 3A 22 FF». */
    val request: String,
    /** «59 04 1A 3A 22 00», «7F 19 31»; null when no answer came. */
    val answer: String?,
    /** «нет ответа · шлюз 3», «шлюз занят»; null for an answer. */
    val noAnswer: String?,
    /** From the helper's `set()` to the last frame, as the car measured it. */
    val latencyMs: Int,
    /** Wall clock when the exchange ended. */
    val atMillis: Long,
)

/**
 * One kind of a code's records - snapshots (`19 04`) or extended data (`19 06`) - as the module answered
 * for one code (architecture, 4). [records] are the records the answers split; [rest] the bytes they could
 * not, as they came.
 */
internal data class RecordReadData<T>(
    val outcome: ReadOutcome = ReadOutcome.NOT_READ,
    /** The status byte the module echoed with the code; null before a positive answer. */
    val status: Int? = null,
    val records: List<T> = emptyList(),
    /**
     * «02 01 F1 90 31 32 …»: bytes of further records whose bounds no answer gives - the data of a DID or a
     * record has no length on the wire - spaced, as they came; null when there are none.
     */
    val rest: String? = null,
    /** «NRC 31 · вне диапазона» for [ReadOutcome.REFUSED], why for [ReadOutcome.INCONSISTENT]; null otherwise. */
    val why: String? = null,
    /** Every exchange of this read, in order: `FF` (all records), then the first record alone where it had data. */
    val exchanges: List<ExchangeData> = emptyList(),
)

/** One snapshot record (`59 04`). */
internal data class SnapshotRecordData(
    /** «01». */
    val number: String,
    /** How many identifiers the record says it holds. */
    val identifiers: Int,
    /** «F190» for a record with one identifier; null for none or several, whose bytes are all in [data]. */
    val did: String?,
    /** The one DID's data, or every identifier's bytes; spaced hex, «» for none. */
    val data: String,
)

/** One extended data record (`59 06`): its number and its bytes, spaced hex. Their meaning is the module's. */
internal data class ExtendedRecordData(val number: String, val data: String)

/**
 * What opening a code read for it ([DiagnosticsActions.openCode]), in [ModuleView.codeReads] under the code
 * as [DtcRow.code] shows it. Read on each opening, never in the background.
 */
internal data class CodeReadsData(
    /** «P1A3A-22», as [DtcRow.code]. */
    val code: String,
    /** «1A3A22», the three bytes the requests carry. */
    val raw: String,
    val snapshots: RecordReadData<SnapshotRecordData> = RecordReadData(),
    val extended: RecordReadData<ExtendedRecordData> = RecordReadData(),
)

/** One value of the freeze frame, decoded as Mode 01 decodes the same PID. */
internal data class FrameValueData(
    val pid: Int,
    /** «Обороты коленвала». */
    val name: String,
    /** «1 250 об/мин», «B1 −1,6 % · B3 0,0 %», «CL · замкн. петля»; «н/д» for a PID's no-value pattern. */
    val value: String,
    /** The PID's data bytes, spaced hex. */
    val raw: String,
)

/** The OBD freeze frame, Mode 02 frame 0, read once a module's screen is opened (ECM). */
internal data class FreezeFrameData(
    val outcome: ReadOutcome = ReadOutcome.NOT_READ,
    /** «P0420»: the code that stored the frame (PID 02); null before it is read or when none is stored. */
    val dtc: String? = null,
    /** The frame's values in PID order; the maps and PID 02 are not among them. */
    val values: List<FrameValueData> = emptyList(),
    /** «0B», «56»: PIDs the frame's maps list that came without a value - left out of the answer or refused. */
    val missing: List<String> = emptyList(),
    val why: String? = null,
    val exchanges: List<ExchangeData> = emptyList(),
)

/** One test of one monitor, SAE J1979 Mode 06. */
internal data class ObdTestData(
    /** «0B»: the test ID, its meaning the module maker's above 7F. */
    val tid: String,
    /** «0B»: the unit and scaling ID. */
    val uasid: String,
    /** «0,742 В»; the raw number where the scaling is not known. */
    val value: String,
    val min: String,
    val max: String,
    /** Within the module's limits; null for a test that has not run since the codes were cleared (all zero). */
    val pass: Boolean?,
    /** The scaling is known: [value], [min] and [max] have a unit, else they are raw numbers. */
    val scaled: Boolean,
    /** The test's nine bytes as they came, spaced. */
    val raw: String,
)

/** One monitor's tests. */
internal data class ObdMidData(
    /** «21». */
    val mid: String,
    /** «Катализатор, ряд 1». */
    val name: String,
    val outcome: ReadOutcome,
    val tests: List<ObdTestData>,
    val why: String? = null,
    val exchange: ExchangeData? = null,
)

/** OBD Mode 06: the monitors the module lists, then each one's tests; read once a module's screen is opened (ECM). */
internal data class ObdTestsData(
    /** Of the whole read: [ReadOutcome.READ] once the maps came, whatever each monitor answered. */
    val outcome: ReadOutcome = ReadOutcome.NOT_READ,
    /** Every monitor the maps list, «01», «21», …, the maps themselves left out. */
    val supported: List<String> = emptyList(),
    /** Each listed monitor as it was read, in MID order. */
    val monitors: List<ObdMidData> = emptyList(),
    val why: String? = null,
    /** The maps' exchanges; each monitor keeps its own. */
    val exchanges: List<ExchangeData> = emptyList(),
)

/** One module's answer about the VIN. */
internal data class VinSourceData(
    /** «VCU 7E2». */
    val module: String,
    /** «22 F190», «09 02». */
    val service: String,
    val outcome: ReadOutcome,
    /** The 17 characters; null when none came. */
    val vin: String?,
    val why: String? = null,
    val exchange: ExchangeData? = null,
)

/**
 * The VIN of the last scan (architecture, 6): `22 F190` at VCU 7E2 and OBD `09 02` at ECM 7E0. [vin] is the one
 * the sources that gave one agree on - the first such source's where they differ, and then [mismatch] is set.
 */
internal data class VinData(
    val vin: String?,
    val mismatch: Boolean,
    val sources: List<VinSourceData>,
)
