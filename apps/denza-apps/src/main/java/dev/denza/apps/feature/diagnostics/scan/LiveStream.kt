package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.ObdPids
import dev.denza.apps.feature.diagnostics.protocol.ObdValue
import dev.denza.apps.feature.diagnostics.protocol.PidReading
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.util.Locale

/**
 * One OBD responder's live data (architecture, 6.1): its PIDs in turn, the focus first and more
 * often, each request packed so the answer fits one CAN frame, every value timed by the car.
 *
 * The packing is the frame's arithmetic: a single frame carries seven bytes of UDS, `41` and six
 * more, and each PID in the answer costs its own byte plus its data's length from the protocol
 * table. So `01 0C 0D` (rpm, 2 bytes; speed, 1) is one frame, and three one-byte PIDs are the most
 * one request can carry. Multi-frame `01` waits for the acceptance to show the gateway assembles it
 * fast (6.1); a PID that cannot fit a frame alone is not streamed. A pack the module answers with a
 * negative or a broken reply is split into single PIDs from then on; PID 56, whose answer has no
 * fixed length, is single from the start ([ALONE]).
 *
 * Time is the car's: each sample sits at its reply's [DiagReply.receivedNanos], an exchange with no
 * answer is a gap ([Sample] with a null value), nothing is interpolated, and the header's rate and
 * delay are measured from those same times ([rate]).
 */
internal class LiveStream(val request: Int, offered: List<Int>, carRows: Boolean) {
    /** The streamed PIDs in screen order: the board's table first, the rest by number. */
    val pids: List<Int> = offered.filter(IdentResult::streamable).distinct()
        .sortedWith(compareBy({ BOARD_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))

    private val series = LinkedHashMap<Int, Series>().apply { pids.forEach { put(it, Series(it)) } }
    private val car = LinkedHashMap<CarRow, Series>().apply { if (carRows) CarRow.entries.forEach { put(it, Series(it.pid)) } }

    var focus: List<Int> = emptyList()
        private set
    private val singles = HashSet(ALONE)
    private var focusPacks: List<List<Int>> = emptyList()
    private var tablePacks: List<List<Int>> = emptyList()
    private var focusCursor = 0
    private var tableCursor = 0
    private var tableTurn = false
    private val latencies = ArrayDeque<Int>()
    private var lastSweep = -1L

    init {
        repack()
    }

    /** Every request the stream cycles through: focus packs, then table packs. */
    val packs: List<List<Int>> get() = focusPacks + tablePacks

    /** «Выбор PID»: up to [MAX_FOCUS] of the streamed PIDs, polled every other exchange. */
    fun setFocus(pids: List<Int>) {
        focus = pids.filter { it in series }.distinct().take(MAX_FOCUS)
        repack()
    }

    /** The next request: a whole round of the focus, then one table pack, and again. */
    fun next(): DiagRequest? {
        val pack = when {
            focusPacks.isEmpty() || (tableTurn && tablePacks.isNotEmpty()) -> {
                tableTurn = false
                if (tablePacks.isEmpty()) return null
                tableCursor %= tablePacks.size
                tablePacks[tableCursor++]
            }
            else -> {
                focusCursor %= focusPacks.size
                val p = focusPacks[focusCursor++]
                if (focusCursor == focusPacks.size) tableTurn = true
                p
            }
        }
        return DiagRequest(request, byteArrayOf(0x01) + ByteArray(pack.size) { pack[it].toByte() })
    }

    /** Takes one stream reply; returns its CSV lines (time, address, PID, raw bytes, value). */
    fun accept(reply: DiagReply): List<String> {
        val asked = reply.request.uds.drop(1).map { it.toInt() and 0xFF }
        val at = reply.receivedNanos
        latencies.addLast(reply.latencyMs)
        while (latencies.size > LATENCY_WINDOW) latencies.removeFirst()
        val lines = ArrayList<String>()
        fun gap(pid: Int) {
            series[pid]?.gap(at)
            lines += csv(at, pid, "", "")
        }
        if (reply is DiagReply.Answer) {
            when (val d = DiagDecoder.obdPids(reply)) {
                is Decoded.Ok -> {
                    d.value.readings.forEach { r ->
                        val s = series[r.pid] ?: return@forEach
                        s.put(at, r)
                        lines += csv(at, r.pid, r.rawHex, s.plain)
                    }
                    d.value.unanswered.forEach(::gap)
                }
                is Decoded.Negative, is Decoded.Malformed -> {
                    if (asked.size > 1) {
                        singles += asked
                        repack()
                    }
                    asked.forEach(::gap)
                }
            }
        } else {
            asked.forEach(::gap)
        }
        return lines
    }

    /** One sample of each fast car signal per new hub sweep; returns its CSV lines. */
    fun carTick(readings: CarReadings): List<String> {
        if (car.isEmpty() || readings.sweep == lastSweep || readings.sweep == 0L) return emptyList()
        lastSweep = readings.sweep
        return car.map { (row, s) ->
            val v = readings.hub[row.signal]
            s.putCar(readings.atNanos, v, row)
            "${readings.atNanos},autoservice,${row.signal.name},,${v?.let { plainNumber(it) } ?: ""}"
        }
    }

    /** «Сбросить мин/макс», and what opening the tab does. */
    fun resetMinMax() {
        series.values.forEach { it.resetMinMax() }
        car.values.forEach { it.resetMinMax() }
    }

    /** Samples per second of the fastest PID over the last ten seconds; null before two samples. */
    fun hz(): Double? = series.values.mapNotNull { it.rate() }.maxOrNull()

    /** Samples per second of the fastest PID over the last ten seconds, and the mean exchange time. */
    fun rate(): String {
        val hz = hz() ?: return "—"
        val ms = if (latencies.isEmpty()) return "—" else latencies.average().toInt()
        return "${Figures.number(hz, 1)} Гц · $ms мс"
    }

    fun view(running: Boolean, recording: Boolean, engineRunning: Boolean?): LiveView = LiveView(
        running = running,
        rate = rate(),
        focus = focus,
        rows = series.values.map { it.row(engineRunning, ParamKey.Pid(request, it.pid)) } + car.map { (row, s) -> s.carRow(row) },
        recording = recording,
    )

    private fun repack() {
        focusPacks = pack(focus)
        tablePacks = pack(pids.filter { it !in focus })
        focusCursor = 0
        tableCursor = 0
        tableTurn = false
    }

    /** First fit, in order: each PID into the first request that still has room in the frame. */
    private fun pack(list: List<Int>): List<List<Int>> {
        val packs = ArrayList<MutableList<Int>>()
        val used = ArrayList<Int>()
        for (pid in list) {
            val size = cost(pid)
            val i = if (pid in singles) -1 else packs.indices.firstOrNull { k ->
                packs[k].none { it in singles } && used[k] + size <= FRAME_DATA && packs[k].size < MAX_PIDS
            } ?: -1
            if (i < 0) {
                packs += mutableListOf(pid)
                used += size
            } else {
                packs[i] += pid
                used[i] += size
            }
        }
        return packs
    }

    private fun csv(at: Long, pid: Int, raw: String, value: String) = csvLine(at, request, pid, raw, value)

    /** One PID's or fast car signal's row: the shared [LiveSeries], its unit, its CSV words and its rate. */
    private class Series(val pid: Int) : LiveSeries(signed = pid in SIGNED) {
        private val answered = ArrayDeque<Long>()
        var unit = ""
        var plain = ""
        /** A text PID's few words beside its code, in the norm's column. */
        var note: String? = null

        fun put(at: Long, r: PidReading) {
            when (val v = r.value) {
                is ObdValue.Numbers -> {
                    val q = v.quantities.firstOrNull()
                    if (q == null) {
                        gap(at)
                        return
                    }
                    decimals = q.decimals
                    unit = q.unit
                    number(at, q.value)
                }
                is ObdValue.Text -> {
                    value = v.code ?: v.text
                    note = v.note
                    plain = "\"${v.text}\""
                    current = null
                    mark(at)
                }
                // An answer, but no number: «н/д» in the table, a gap in the trace.
                ObdValue.NoValue -> {
                    gap(at, ObdValue.NoValue.TEXT)
                    mark(at)
                }
                else -> gap(at)
            }
        }

        fun putCar(at: Long, v: Double?, row: CarRow) {
            if (v == null) {
                gap(at)
                return
            }
            decimals = row.decimals
            unit = row.unit
            number(at, v)
            row.text?.let { value = it(v) }
        }

        override fun number(at: Long, v: Double) {
            super.number(at, v)
            plain = plainNumber(v)
            mark(at)
        }

        override fun gap(at: Long, text: String) {
            super.gap(at, text)
            plain = ""
        }

        private fun mark(at: Long) {
            answered.addLast(at)
            while (answered.isNotEmpty() && answered.first() < at - RATE_WINDOW_NS) answered.removeFirst()
        }

        fun rate(): Double? {
            if (answered.size < 2) return null
            val span = answered.last() - answered.first()
            return if (span <= 0) null else (answered.size - 1) * 1e9 / span
        }

        fun row(engineRunning: Boolean?, key: ParamKey): LiveRow {
            val def = ObdPids.of(pid)
            val norm = NORMS[pid]
            return LiveRow(
                pid = pid,
                name = def?.name ?: "PID %02X".format(pid),
                abbreviation = ABBREVIATIONS[pid] ?: "",
                value = value,
                unit = unit,
                min = minText,
                max = maxText,
                norm = note ?: norm?.text ?: "—",
                outOfNorm = norm?.outside(current, engineRunning) ?: false,
                source = "PID",
                samples = samples.toList(),
                key = key,
            )
        }

        fun carRow(row: CarRow) = LiveRow(
            pid = pid,
            name = row.title,
            abbreviation = row.abbreviation,
            value = value,
            unit = unit,
            min = if (row.text == null) minText else "—",
            max = if (row.text == null) maxText else "—",
            norm = "—",
            outOfNorm = false,
            source = CAR_SOURCE,
            samples = samples.toList(),
            key = ParamKey.Signal(row.signal.name),
        )
    }

    /**
     * A norm where one is known (SAE J1979 ranges and the plain physics of a warm petrol engine),
     * never invented for the rest. [running] limits the judgement to a running engine: a parked,
     * cold car reads 1,275 В on its downstream O2 sensor, and that is not a fault. [band] is what
     * the parameter page draws (architecture, 6.2): the coolant's 80…105 although only overheating
     * is judged, none for the intake pressure that is said and not judged.
     */
    class Norm(
        val text: String,
        val low: Double?,
        val high: Double?,
        val running: Boolean = false,
        val band: ClosedFloatingPointRange<Float>? = null,
    ) {
        fun outside(v: Double?, engineRunning: Boolean?): Boolean {
            if (v == null) return false
            if (running && engineRunning != true) return false
            return (low != null && v < low) || (high != null && v > high)
        }
    }

    /** The fast car signals of the shared hub, shown beside the engine's PIDs with their source. */
    enum class CarRow(
        val signal: VehicleSignal,
        val title: String,
        val abbreviation: String,
        val unit: String,
        val decimals: Int,
        val text: ((Double) -> String)? = null,
    ) {
        RPM(VehicleSignal.ENGINE_RPM, "Обороты ДВС", "RPM", "об/мин", 0),
        RUNNING(VehicleSignal.ENGINE_RUNNING, "Работа ДВС", "ENG", "", 0, { if (it >= 1.0) "работает" else "стоит" }),
        GENERATION(VehicleSignal.GENERATION_KW, "Генерация", "GEN", "кВт", 1),
        POWER(VehicleSignal.POWER_KW, "Мощность ВВБ", "PWR", "кВт", 1),
        PACK_VOLT(VehicleSignal.PACK_VOLT, "Напряжение ВВБ", "U ВВБ", "В", 0),
        SPEED(VehicleSignal.VEHICLE_SPEED, "Скорость", "VSS", "км/ч", 0),
        PARK(VehicleSignal.GEARBOX_PARK, "Селектор", "P", "", 0, { if (it >= 1.0) "P" else "не P" }),
        ODOMETER(VehicleSignal.ODOMETER_KM, "Одометр", "ODO", "км", 1);

        /** Negative: not a PID. The screen tells the two apart by [LiveRow.source]. */
        val pid: Int get() = -(ordinal + 1)
    }

    companion object {
        /** A single CAN frame's data after `41`: seven bytes of UDS, one of them the service. */
        const val FRAME_DATA = 6
        const val MAX_PIDS = 6
        const val MAX_FOCUS = 4
        const val WINDOW_NS = LiveSeries.WINDOW_NS
        private const val RATE_WINDOW_NS = 10_000_000_000L
        private const val LATENCY_WINDOW = 20
        const val CAR_SOURCE = "autoservice · 100 мс"

        /** The order of the board «Потоковые данные». */
        private val BOARD_ORDER = listOf(0x05, 0x0F, 0x0B, 0x0C, 0x04, 0x11, 0x0E, 0x06, 0x07, 0x03, 0x15, 0x3C, 0x42)

        /**
         * Asked alone from the first request. The ECM answers PID 56 with one byte (`41 56 80`,
         * 2026-10-21): its bank-3 byte is conditional (J1979 table B67), so inside a pack the answer
         * cannot be split, the pack comes back Malformed and every PID in it would go single for the
         * rest of the session.
         */
        private val ALONE = setOf(0x56)

        /** Fuel trims and errors are read with their sign. */
        private val SIGNED = setOf(0x06, 0x07, 0x2D, 0x56)

        /** The table's words for one PID, which the parameter page repeats (architecture, 6.2). */
        fun signed(pid: Int): Boolean = pid in SIGNED
        fun norm(pid: Int): Norm? = NORMS[pid]
        fun abbreviation(pid: Int): String = ABBREVIATIONS[pid] ?: ""

        /** One line of the stream's CSV: time, address, PID, raw bytes, value. */
        fun csvLine(at: Long, request: Int, pid: Int, raw: String, value: String) =
            "$at,${Figures.address(request)},%02X,$raw,$value".format(pid)

        private fun cost(pid: Int) = 1 + (ObdPids.of(pid)?.length ?: FRAME_DATA)

        fun plainNumber(v: Double) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

        private val NORMS = mapOf(
            // Warm-up below 80 °C is no fault, so only overheating is flagged.
            0x05 to Norm("80…105", null, 105.0, band = 80f..105f),
            0x06 to Norm("±10", -10.0, 10.0, band = -10f..10f),
            0x07 to Norm("±10", -10.0, 10.0, band = -10f..10f),
            // The 12 V side with the DC-DC or the alternator charging it.
            0x42 to Norm("13,2…14,8", 13.2, 14.8, band = 13.2f..14.8f),
            0x15 to Norm("0,1…0,9", 0.1, 0.9, running = true, band = 0.1f..0.9f),
            // Idle only, and the session cannot tell idle from a light load: said, not judged.
            0x0B to Norm("ХХ 25…45", null, null),
        )

        private val ABBREVIATIONS = mapOf(
            0x03 to "FUEL SYS", 0x04 to "LOAD", 0x05 to "ECT", 0x06 to "STFT", 0x07 to "LTFT", 0x0B to "MAP",
            0x0C to "RPM", 0x0D to "VSS", 0x0E to "УОЗ", 0x0F to "IAT", 0x11 to "TPS", 0x15 to "O2S12",
            0x1F to "RUNTM", 0x21 to "MIL DIST", 0x23 to "FRP", 0x2C to "EGR", 0x2D to "EGR ERR",
            0x2E to "EVAP PCT", 0x2F to "FLI", 0x30 to "WARM UPS", 0x31 to "CLR DIST", 0x33 to "BARO",
            0x34 to "O2S11", 0x3C to "CATT", 0x42 to "VPWR", 0x43 to "LOAD ABS", 0x44 to "λ CMD",
            0x45 to "TP R", 0x46 to "AAT", 0x47 to "TP B", 0x49 to "APP D", 0x4A to "APP E", 0x4C to "TAC",
            0x54 to "EVAP VP", 0x56 to "LTFT O2", 0x67 to "ECT 1/2", 0x6B to "EGRT",
        )
    }
}

/**
 * One row of a live table (architecture 6.1, 6.3): its last 60 s on the car's clock, its value as
 * the table words it and its extremes since the table was shown or since «Сбросить мин/макс». The
 * engine's PIDs and fast car signals and the pack's readings keep their rows in it alike; a null
 * value in [samples] is a gap the chart leaves open, nothing is interpolated.
 */
internal open class LiveSeries(private val signed: Boolean = false) {
    val samples = ArrayDeque<Sample>()
    var value = "—"
    var current: Double? = null
        protected set
    var decimals = 0
    private var min: Double? = null
    private var max: Double? = null

    /** A number at [at] on the car's clock: the value, the extremes and a point of the trace. */
    open fun number(at: Long, v: Double) {
        current = v
        value = Figures.number(v, decimals, signed)
        min = min?.coerceAtMost(v) ?: v
        max = max?.coerceAtLeast(v) ?: v
        add(Sample(at, v.toFloat()))
    }

    /** No reading at [at]: [text] in the table, a gap in the trace; the extremes stay. */
    open fun gap(at: Long, text: String = "—") {
        value = text
        current = null
        add(Sample(at, null))
    }

    /** «Сбросить мин/макс», and what showing the table does: the extremes start at the value now. */
    fun resetMinMax() {
        min = current
        max = current
    }

    /** «1 180»; «—» before a number. */
    val minText: String get() = extreme(min)
    val maxText: String get() = extreme(max)

    private fun extreme(v: Double?) = v?.let { Figures.number(it, decimals, signed) } ?: "—"

    private fun add(sample: Sample) {
        samples.addLast(sample)
        while (samples.isNotEmpty() && samples.first().atNanos < sample.atNanos - WINDOW_NS) samples.removeFirst()
    }

    companion object {
        /** A row keeps the last minute. */
        const val WINDOW_NS = 60_000_000_000L
    }
}
