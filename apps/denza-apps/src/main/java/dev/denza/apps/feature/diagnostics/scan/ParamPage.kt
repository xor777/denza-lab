package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.ObdPids
import dev.denza.apps.feature.diagnostics.protocol.ObdValue
import dev.denza.apps.feature.vehicle.VehiclePoll
import dev.denza.apps.feature.vehicle.VehicleSignal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What a parameter page is about (architecture, 6.2), fixed when the page opens: its words as the
 * row that opened it says them, its norm, and how it is read - a PID over the bus, a hub signal
 * from the hub's sweeps ([hub]), or a GET of its own ([get]).
 */
internal class ParamSpec(
    val key: ParamKey,
    val name: String,
    /** «· PID 0C · RPM», «· 1014·44600010». */
    val aside: String,
    /** The line before what is measured: «7E0 → 7E8 · Mode 01 · только 0C». */
    val line: String,
    val unit: String,
    val decimals: Int,
    val signed: Boolean,
    /** The norm against the car now; null where none is known. */
    val norm: (CarReadings) -> SystemsBuilder.SignalNorm?,
    val recordable: Boolean,
    val hub: VehicleSignal? = null,
    val get: SignalGet? = null,
    /** A two-state signal said in words: «работает», «P». */
    val text: ((Double) -> String)? = null,
) {
    companion object {
        /** The spec of [key], or null when nothing with that key can be read alone. */
        fun of(key: ParamKey): ParamSpec? = when (key) {
            is ParamKey.Pid -> pid(key)
            is ParamKey.Signal -> signal(key)
        }

        private fun pid(key: ParamKey.Pid): ParamSpec? {
            if (EcuCatalog.byRequest(key.request)?.obd != true || !IdentResult.streamable(key.pid)) return null
            val def = ObdPids.of(key.pid) ?: return null
            val abbreviation = LiveStream.abbreviation(key.pid)
            val norm = LiveStream.norm(key.pid)
            return ParamSpec(
                key = key,
                name = def.name,
                aside = "· PID %02X".format(key.pid) + if (abbreviation.isEmpty()) "" else " · $abbreviation",
                line = "${Figures.route(key.request)} · Mode 01 · только %02X".format(key.pid),
                unit = "",
                decimals = 0,
                signed = LiveStream.signed(key.pid),
                norm = { car ->
                    norm?.let { n -> SystemsBuilder.SignalNorm(n.text, n.band, if (n.low == null && n.high == null) null else { v -> n.outside(v, car.engineRunning) }) }
                },
                recordable = true,
            )
        }

        private fun signal(key: ParamKey.Signal): ParamSpec? {
            SystemsBuilder.PACK.firstOrNull { it.key == key.key }?.let { r ->
                return signalSpec(key, r.name, r.source, r.unit, r.decimals, r.extra, r.hub, r.norm ?: { null }, null)
            }
            LiveStream.CarRow.entries.firstOrNull { it.signal.name == key.key }?.let { r ->
                return signalSpec(key, r.title, SystemsBuilder.source(r.signal), r.unit, r.decimals, null, r.signal, { null }, r.text)
            }
            return null
        }

        private fun signalSpec(
            key: ParamKey.Signal,
            name: String,
            source: String,
            unit: String,
            decimals: Int,
            extra: DiagnosticExtra?,
            hub: VehicleSignal?,
            norm: (CarReadings) -> SystemsBuilder.SignalNorm?,
            text: ((Double) -> String)?,
        ): ParamSpec {
            // A hot hub signal already comes with every sweep; anything slower gets a GET of its own.
            val fromHub = hub?.takeIf { it.poll == VehiclePoll.HOT }
            val get = when {
                extra != null -> SignalGet.of(extra)
                fromHub == null && hub != null -> SignalGet.of(hub)
                else -> null
            }
            return ParamSpec(
                key = key,
                name = name,
                aside = "· $source",
                line = if (fromHub != null) LiveStream.CAR_SOURCE else "autoservice · только этот сигнал",
                unit = unit,
                decimals = decimals,
                signed = false,
                norm = norm,
                recordable = false,
                hub = fromHub,
                get = get,
                text = text,
            )
        }
    }
}

/**
 * One parameter's last five minutes (architecture, 6.2): every reading at its time on the head
 * unit's clock - a PID's at its reply's [DiagReply.receivedNanos], a signal's when the app read it -
 * a reading that did not come as a gap, nothing interpolated. [view] is the page: the value as the
 * live table words it, the measured rate, and the 10, 60 and 300 s windows the engine computes.
 */
internal class ParamTrace(val spec: ParamSpec) {
    private val samples = ArrayDeque<Sample>()
    private val answered = ArrayDeque<Long>()
    private val latencies = ArrayDeque<Int>()
    /** «Сбросить мин/макс»: the windows' figures start at this sample; the trace stays. */
    private var resetAt = Long.MIN_VALUE
    private var unit = spec.unit
    private var decimals = spec.decimals
    private var value = NOT_READ
    private var current: Double? = null

    /** The hub sweep last sampled, for a hub signal. */
    var lastSweep = -1L

    /** The page's PID reply; returns its CSV line, the stream's form. */
    fun accept(reply: DiagReply): List<String> {
        val key = spec.key as ParamKey.Pid
        val at = reply.receivedNanos
        latencies.addLast(reply.latencyMs)
        while (latencies.size > LATENCY_WINDOW) latencies.removeFirst()
        val reading = (reply as? DiagReply.Answer)?.let { (DiagDecoder.obdPids(it) as? Decoded.Ok)?.value }
            ?.readings?.firstOrNull { it.pid == key.pid }
        val v = reading?.value
        when {
            v is ObdValue.Numbers && v.quantities.isNotEmpty() -> {
                val q = v.quantities.first()
                decimals = q.decimals
                unit = q.unit
                put(at, q.value)
                return listOf(LiveStream.csvLine(at, key.request, key.pid, reading.rawHex, LiveStream.plainNumber(q.value)))
            }
            v is ObdValue.Text -> {
                value = v.text
                current = null
                mark(at)
                return listOf(LiveStream.csvLine(at, key.request, key.pid, reading.rawHex, "\"${v.text}\""))
            }
            else -> {
                put(at, null)
                return listOf(LiveStream.csvLine(at, key.request, key.pid, "", ""))
            }
        }
    }

    /** One reading at [at]; null is a gap. */
    fun put(at: Long, v: Double?) {
        if (v == null) {
            if (value != NOT_READ) value = "—"
            current = null
            add(Sample(at, null))
            return
        }
        current = v
        value = spec.text?.invoke(v) ?: Figures.number(v, decimals, signed = spec.signed)
        add(Sample(at, v.toFloat()))
        mark(at)
    }

    /** «Сбросить мин/макс»: the windows start over from the current reading; the chart keeps its trace. */
    fun reset() {
        resetAt = samples.lastOrNull()?.atNanos ?: Long.MIN_VALUE
    }

    /** Readings a second over the last ten seconds; null before two. */
    fun hz(): Double? {
        if (answered.size < 2) return null
        val span = answered.last() - answered.first()
        return if (span <= 0) null else (answered.size - 1) * 1e9 / span
    }

    /** The mean of the last exchanges, for a PID; null for a signal or before one. */
    fun latencyMs(): Int? = if (latencies.isEmpty()) null else latencies.average().toInt()

    fun view(running: Boolean, recording: Boolean, car: CarReadings): ParamView {
        val hz = hz()
        val ms = latencyMs()
        val now = samples.lastOrNull()?.atNanos ?: 0L
        val norm = spec.norm(car)
        val list = samples.toList()
        return ParamView(
            key = spec.key,
            name = spec.name,
            aside = spec.aside,
            line = listOfNotNull(
                spec.line,
                hz?.let { "${Figures.number(it, 1)} Гц" },
                ms?.takeIf { hz != null }?.let { "обмен $it мс" },
            ).joinToString(" · "),
            value = value,
            unit = unit,
            outOfNorm = current?.let { c -> norm?.outside?.invoke(c) } ?: false,
            norm = norm?.band,
            normText = norm?.text ?: "—",
            samples = list,
            nowNanos = now,
            windows = WINDOWS.associateWith { stats(list, it, now, norm?.band) },
            running = running,
            rate = when {
                hz == null -> "—"
                ms == null -> "${Figures.number(hz, 1)} Гц"
                else -> "${Figures.number(hz, 1)} Гц · $ms мс"
            },
            recordable = spec.recordable,
            recording = recording,
        )
    }

    private fun stats(list: List<Sample>, seconds: Int, now: Long, band: ClosedFloatingPointRange<Float>?): ParamStats {
        val from = now - seconds * 1_000_000_000L
        val shown = list.filter { it.atNanos > from }
        val counted = shown.filter { it.atNanos >= resetAt }.mapNotNull { it.value?.toDouble() }
        val drawn = shown.mapNotNull { it.value?.toDouble() }
        val min = counted.minOrNull()
        val max = counted.maxOrNull()
        val scale = ParamScale.of(drawn.minOrNull(), drawn.maxOrNull(), band)
        fun word(v: Double?) = v?.let { Figures.number(it, decimals, signed = spec.signed) } ?: "—"
        return ParamStats(
            min = word(min),
            max = word(max),
            mean = word(if (counted.isEmpty()) null else counted.average()),
            minValue = min?.toFloat(),
            maxValue = max?.toFloat(),
            scale = scale.low.toFloat()..scale.high.toFloat(),
            grid = scale.lines.map(Double::toFloat),
            gridText = scale.lines.map { Figures.number(it, scale.decimals) },
            delta = delta(list),
            points = Figures.number(counted.size.toDouble(), 0),
        )
    }

    /** The change over the last second: the last reading against the one a second or more before it. */
    private fun delta(list: List<Sample>): String {
        val last = list.lastOrNull() ?: return "—"
        val v = last.value ?: return "—"
        val before = list.lastOrNull { it.atNanos <= last.atNanos - 1_000_000_000L } ?: return "—"
        val b = before.value ?: return "—"
        return Figures.number(v.toDouble() - b.toDouble(), decimals, signed = true)
    }

    private fun mark(at: Long) {
        answered.addLast(at)
        while (answered.isNotEmpty() && answered.first() < at - RATE_WINDOW_NS) answered.removeFirst()
    }

    private fun add(sample: Sample) {
        samples.addLast(sample)
        while (samples.isNotEmpty() && samples.first().atNanos <= sample.atNanos - RING_NS) samples.removeFirst()
    }

    companion object {
        /** The page's windows, seconds: «10 с», «60 с», «5 мин». */
        val WINDOWS = listOf(10, 60, 300)
        const val RING_NS = 300_000_000_000L
        private const val RATE_WINDOW_NS = 10_000_000_000L
        private const val LATENCY_WINDOW = 20
        const val NOT_READ = "н/д"
    }
}

/**
 * A chart's vertical scale: the readings and the norm's band, five per cent of room on each side,
 * rounded outward to a grid of at most five lines on a step of 1, 2, 2,5 or 5 times a power of ten.
 */
internal class ParamScale(val low: Double, val high: Double, val step: Double) {
    val lines: List<Double> get() {
        val n = ((high - low) / step).roundToLong().toInt()
        return (0..n).map { clean(low + it * step) }
    }

    /** Decimals the grid's labels need: «2,5» one, «0,25» two. */
    val decimals: Int get() = (0..3).firstOrNull { d -> abs(step * 10.0.pow(d) - (step * 10.0.pow(d)).roundToLong()) < 1e-6 } ?: 3

    companion object {
        const val MAX_LINES = 5

        fun of(min: Double?, max: Double?, band: ClosedFloatingPointRange<Float>?): ParamScale {
            var lo = listOfNotNull(min, band?.start?.toDouble()).minOrNull()
            var hi = listOfNotNull(max, band?.endInclusive?.toDouble()).maxOrNull()
            if (lo == null || hi == null) {
                lo = 0.0
                hi = 1.0
            }
            if (hi - lo < 1e-9) {
                val pad = maxOf(abs(lo) * 0.1, 1.0)
                lo -= pad
                hi += pad
            }
            val room = (hi - lo) * 0.05
            lo -= room
            hi += room
            var k = floor(log10((hi - lo) / MAX_LINES)).toInt() - 1
            while (true) {
                for (m in STEPS) {
                    val step = m * 10.0.pow(k)
                    val a = floor(lo / step + 1e-9) * step
                    val b = ceil(hi / step - 1e-9) * step
                    if (((b - a) / step).roundToLong() + 1 <= MAX_LINES) return ParamScale(clean(a), clean(b), step)
                }
                k++
            }
        }

        private val STEPS = listOf(1.0, 2.0, 2.5, 5.0)

        /** Rounds away the float noise of a multiple: 0.30000000000000004 → 0.3. */
        private fun clean(v: Double): Double = (v * 1e6).roundToLong() / 1e6
    }
}
