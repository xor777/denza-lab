package dev.denza.apps.feature.diagnostics.scan

import kotlin.math.abs
import kotlin.math.pow

/**
 * «Графики» kept by the session while [DiagnosticsActions.setGraphs] holds them (architecture, 7.3): up to four
 * keys of one scope, the last five minutes of each on the car's clock, and «Метка» presses. Fed from the rows
 * the stream or the system's table already keep - a minute each - so nothing more is read than the table
 * reads; the five minutes are this trace's own.
 */
internal class GraphTraces(val scope: GraphScope, val keys: List<ParamKey>) {
    private val traces: Map<ParamKey, ArrayDeque<Sample>> = keys.associateWith { ArrayDeque() }
    private val marks = ArrayList<Long>()

    /** What [rows] hold that the traces do not yet: each key's samples after its last. */
    fun take(rows: List<LiveRow>) {
        keys.forEach { key ->
            val row = rows.firstOrNull { it.key == key } ?: return@forEach
            val trace = traces.getValue(key)
            val last = trace.lastOrNull()?.atNanos ?: Long.MIN_VALUE
            row.samples.filter { it.atNanos > last }.forEach { trace.addLast(it) }
            val newest = trace.lastOrNull()?.atNanos ?: return@forEach
            while (trace.isNotEmpty() && trace.first().atNanos <= newest - WINDOW_NS) trace.removeFirst()
        }
    }

    fun mark(atNanos: Long) {
        marks += atNanos
        marks.removeAll { it < atNanos - WINDOW_NS }
    }

    fun snapshot(): GraphsSnapshot = GraphsSnapshot(scope, keys, keys.associateWith { traces.getValue(it).toList() }, marks.toList())

    companion object {
        const val MAX_KEYS = 4
        const val WINDOW_NS = 300_000_000_000L
    }
}

/** [GraphTraces] at one moment, for the model. */
internal data class GraphsSnapshot(
    val scope: GraphScope,
    val keys: List<ParamKey>,
    val traces: Map<ParamKey, List<Sample>>,
    val marks: List<Long>,
)

/** «Графики» (board `scanner-sys-engine-graphs`) out of the traces and the rows they follow. Pure. */
internal object GraphsBuilder {
    /** The window the screen shows and the footer counts in, ns. */
    const val SHOWN_NS = 60_000_000_000L

    /**
     * [rows] are the scope's table now - the stream's or the system's - for each lane's words and norm; [line]
     * what reads them, «4 PID одним запросом · 8,3 Гц».
     */
    fun view(g: GraphsSnapshot, rows: List<LiveRow>, line: String, car: CarReadings): GraphsView {
        val lanes = g.keys.mapNotNull { key -> rows.firstOrNull { it.key == key }?.let { lane(key, it, g.traces[key].orEmpty(), car) } }
        val now = g.traces.values.mapNotNull { it.lastOrNull()?.atNanos }.maxOrNull() ?: 0L
        // a lane's own minute up to its last reading: lanes read in different requests end a request apart
        val points = lanes.firstOrNull()?.samples?.let { s ->
            val last = s.lastOrNull()?.atNanos ?: 0L
            s.count { it.value != null && it.atNanos > last - SHOWN_NS }
        } ?: 0
        return GraphsView(
            scope = g.scope,
            lanes = lanes,
            line = line,
            nowNanos = now,
            marks = g.marks,
            footnote = "окно ${SHOWN_NS / 1_000_000_000L} с · ${Figures.number(points.toDouble(), 0)} " +
                Words.plural(points, "точка", "точки", "точек") + " на сигнал",
        )
    }

    private fun lane(key: ParamKey, row: LiveRow, samples: List<Sample>, car: CarReadings): GraphLane {
        val decimals = row.value.substringAfter(',', "").takeWhile { it.isDigit() }.length
        val band = when (key) {
            is ParamKey.Pid -> LiveStream.norm(key.pid)?.band
            is ParamKey.Signal -> SystemStreams.reading(key.key)?.norm?.invoke(car)?.band
        }
        val values = samples.mapNotNull { it.value?.toDouble() }
        val scale = scale(values.minOrNull(), values.maxOrNull(), band, decimals)
        return GraphLane(
            name = row.name,
            source = when (key) {
                is ParamKey.Pid -> "PID %02X".format(key.pid) + row.abbreviation.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
                is ParamKey.Signal -> listOfNotNull(row.device, row.abbreviation.takeIf { it.isNotEmpty() }).joinToString(" · ")
            },
            unit = row.unit,
            samples = samples,
            scale = scale.low.toFloat()..scale.high.toFloat(),
            scaleText = Figures.number(scale.high, scale.decimals) to Figures.number(scale.low, scale.decimals),
            band = band,
            decimals = decimals,
            key = key,
            signed = key is ParamKey.Pid && LiveStream.signed(key.pid),
        )
    }

    /** The readings and the norm's band, never narrower than 20 × the resolution, rounded out to a grid ([ParamScale]). */
    fun scale(min: Double?, max: Double?, band: ClosedFloatingPointRange<Float>?, decimals: Int): ParamScale {
        val least = 20 * 10.0.pow(-decimals)
        var lo = listOfNotNull(min, band?.start?.toDouble()).minOrNull()
        var hi = listOfNotNull(max, band?.endInclusive?.toDouble()).maxOrNull()
        if (lo != null && hi != null && hi - lo < least) {
            val mid = (lo + hi) / 2
            lo = mid - least / 2
            hi = mid + least / 2
            if (min != null && min >= 0 && lo < 0 && abs(lo) < least) {
                hi -= lo
                lo = 0.0
            }
        }
        return ParamScale.of(lo, hi, null)
    }
}
