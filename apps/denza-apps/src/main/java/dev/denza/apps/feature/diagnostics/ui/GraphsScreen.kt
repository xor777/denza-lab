package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.GraphLane
import dev.denza.apps.feature.diagnostics.scan.GraphsView
import dev.denza.apps.feature.diagnostics.scan.LiveView
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.Sample
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.abs

/*
 * «Потоковые данные · Графики» (architecture, 7.3; `scanner.js` `sysGraphs()`, board
 * `scanner-sys-engine-graphs`): the signals ticked on the table, each its own lane and scale on one 60 s axis -
 * the norm a band - in the order of their squares, and a cursor a tap on the plot puts down, reading every lane
 * at one moment. The legend at the left leads with the table's square and its number, says each signal's value at
 * the cursor (its newest without one) and the minute's extremes, and ends with «×», which takes the signal off; the
 * rest of a legend opens its parameter alone.
 */

private const val WINDOW_S = 60f
private const val WINDOW_NS = 60_000_000_000L

/** Seconds back from [now] of a sample's moment. */
private fun back(now: Long, at: Long): Float = (now - at) / 1e9f

/**
 * What a 60 s window ending at [now] draws of [samples]: those inside it and the last one before it, so a line
 * enters from the window's left edge at the value it had there - the drawing is clipped to the window - instead of
 * starting at the first point inside it and jumping a step right each time the window moves.
 */
internal fun inWindow(samples: List<Sample>, now: Long, windowNs: Long = WINDOW_NS): List<Sample> {
    val start = now - windowNs
    val first = samples.indexOfLast { it.atNanos <= start }.coerceAtLeast(0)
    return samples.subList(first, samples.size).filter { it.atNanos <= now }
}

/**
 * The lanes, their legends and the axis under them, placed in the area right of the rail; [picks] give each
 * legend's number, [onRemove] takes a signal off. The plot alone takes taps - its own area, not the screen's - so
 * the header, the tabs and the switch above it keep theirs.
 */
@Composable
internal fun GraphLanes(
    view: GraphsView?,
    picks: List<ParamKey>,
    start: Float?,
    onParam: (ParamKey) -> Unit,
    onRemove: (ParamKey) -> Unit,
) {
    val g = ScannerSpec.Graphs
    val p = ScannerSpec.Pick
    val w = LocalScanWidth.current
    val gx = g.PLOT_X
    val gw = w - 24f - gx
    val width = rememberWidths()
    var cursor by remember(view?.scope) { mutableStateOf(start) }
    val now = view?.nowNanos ?: 0L
    fun xOf(s: Float) = gx + (WINDOW_S - s) / WINDOW_S * gw
    val lanes = view?.lanes.orEmpty().take(g.LANES.size)
    // the cursor on the sample nearest its moment, in the first lane's timing
    val snapped = cursor?.let { c ->
        lanes.firstOrNull()?.samples?.filter { back(now, it.atNanos) in 0f..WINDOW_S }?.minByOrNull { abs(back(now, it.atNanos) - c) }?.let { back(now, it.atNanos) } ?: c
    }
    fun at(lane: GraphLane): Float? {
        val shown = lane.samples.filter { back(now, it.atNanos) in 0f..WINDOW_S }
        val s = if (snapped == null) shown.lastOrNull { it.value != null } else shown.minByOrNull { abs(back(now, it.atNanos) - snapped) }
        return s?.value
    }
    // the plot: the lanes' rules, bands and lines and the cursor, drawn and tapped inside its own area
    val top = g.CURSOR_TOP
    Canvas(
        Modifier.at(gx, top).size(gw.dp, (g.CURSOR_BOTTOM - top).dp).clipToBounds().pointerInput(view?.scope, gw) {
            detectTapGestures { o -> cursor = ((gw - o.x / density) / gw * WINDOW_S).coerceIn(0f, WINDOW_S) }
        },
    ) {
        val k = density
        fun rect(rx: Float, ry: Float, rw: Float, rh: Float, color: Int) = drawRect(ink(color), Offset((rx - gx) * k, (ry - top) * k), Size(rw * k, rh * k))
        lanes.forEachIndexed { i, lane ->
            val lt = g.LANES[i]
            val lh = g.LANE
            val lo = lane.scale.start
            val hi = lane.scale.endInclusive
            fun y(v: Float) = lt + (hi - v) / (hi - lo) * lh
            rect(gx, lt + lh, gw, 1f, Ink.RULE)
            rect(gx, lt, gw, 1f, Ink.HAIR)
            lane.band?.let { b -> rect(gx, y(b.endInclusive), gw, y(b.start) - y(b.endInclusive), Ink.BAND) }
            val path = Path()
            var open = false
            inWindow(lane.samples, now).forEach { s ->
                val v = s.value
                if (v == null) { open = false; return@forEach }
                val px = (xOf(back(now, s.atNanos)) - gx) * k
                val py = (y(v.coerceIn(lo, hi)) - top) * k
                if (open) path.lineTo(px, py) else path.moveTo(px, py)
                open = true
            }
            drawPath(path, ink(Ink.BLUE), style = Stroke(g.TRACE * k, join = StrokeJoin.Round))
        }
        snapped?.let { c ->
            val cx = xOf(c)
            rect(cx - 0.5f, g.CURSOR_TOP, 1f, g.CURSOR_BOTTOM - g.CURSOR_TOP, Ink.WIRE4)
            lanes.forEachIndexed { i, lane ->
                val v = at(lane) ?: return@forEachIndexed
                val lo = lane.scale.start
                val hi = lane.scale.endInclusive
                drawCircle(ink(Ink.WHITE), g.CURSOR_DOT * k, Offset((cx - gx) * k, (g.LANES[i] + (hi - v.coerceIn(lo, hi)) / (hi - lo) * g.LANE - top) * k))
            }
        }
    }
    lanes.forEachIndexed { i, lane ->
        val lt = g.LANES[i]
        ScanText(lane.scaleText.first, Type.SCALE, Ink.FAINT, Modifier.endAt(w - 24f - g.SCALE_INSET, lt + g.SCALE_PAD))
        ScanText(lane.scaleText.second, Type.SCALE, Ink.FAINT, Modifier.endAt(w - 24f - g.SCALE_INSET, lt + g.LANE - Type.SCALE.lh - g.SCALE_PAD))
        // the legend: the table's square and number, name and «×»; under the name the source, the value at the
        // cursor, the minute's extremes
        val lx = 24f + p.SIZE + p.LEGEND_GAP
        val closeX = 24f + g.LEGEND_W - p.CLOSE_SIZE / 2f
        val number = lane.key?.let { picks.indexOf(it) + 1 }?.takeIf { it > 0 } ?: (i + 1)
        PickBox(24f, lt + (Type.TITLE_ASIDE.lh - p.SIZE) / 2f, number, pale = false)
        Box(Modifier.at(24f, lt).size(g.LEGEND_W.dp, g.LANE.dp).tap(lane.key?.let { { onParam(it) } })) {
            var ly = 0f
            ScanText(lane.name, Type.TITLE_ASIDE, Ink.WHITE, Modifier.at(lx - 24f, ly), maxWidth = 24f + g.LEGEND_W - p.CLOSE_SIZE - p.CLOSE_GAP - lx)
            ly += Type.TITLE_ASIDE.lh + g.NAME_GAP
            ScanText(lane.source, Type.SIG, Ink.MUTE, Modifier.at(lx - 24f, ly))
            ly += Type.SIG.lh + g.NAME_GAP + g.VALUE_GAP
            val value = at(lane)?.let { ScannerWords.number(it, lane.decimals, lane.signed) } ?: "—"
            ScanText(value, Type.COUNTER, if (value == "—") Ink.FAINT else Ink.WHITE, Modifier.at(lx - 24f, ly))
            ScanText(lane.unit, Type.UNIT, Ink.DIM, Modifier.at(lx - 24f + width(value, Type.COUNTER) + g.VALUE_GAP, ly + Type.COUNTER.lh - Type.UNIT.lh - 2f))
            ly += Type.COUNTER.lh + g.NAME_GAP
            val values = lane.samples.filter { back(now, it.atNanos) in 0f..WINDOW_S }.mapNotNull { it.value }
            if (values.isNotEmpty()) {
                val span = ScannerWords.number(values.min(), lane.decimals, lane.signed) + "…" + ScannerWords.number(values.max(), lane.decimals, lane.signed)
                ScanText(span, Type.UNIT_TAG, Ink.FAINT, Modifier.at(lx - 24f, ly))
            }
        }
        // «×» over the legend's corner: a target of its own, as tall as the name's line and wider than the mark
        lane.key?.let { key ->
            val cy = lt + Type.TITLE_ASIDE.lh / 2f
            CloseMark(closeX, cy)
            Box(Modifier.at(closeX - CLOSE_TARGET / 2f, cy - CLOSE_TARGET / 2f).size(CLOSE_TARGET.dp).tap { onRemove(key) })
        }
    }
    // the axis: seconds back, the cursor's moment on it
    ScannerWords.GRAPH_TICKS.forEach { t -> ScanText("−$t с", Type.ROW_SIG, Ink.FAINT, Modifier.at(xOf(t.toFloat()), g.AXIS_Y)) }
    ScanText("сейчас", Type.ROW_SIG, Ink.FAINT, Modifier.endAt(w - 24f, g.AXIS_Y))
    val c = cursor
    if (snapped != null && c != null) {
        val cx = xOf(snapped)
        Box(Modifier.at(cx - g.TAG_W / 2f, g.TAG_Y).size(g.TAG_W.dp, g.TAG_H.dp).plate(Ink.QUIET, Ink.QUIET_LINE, g.TAG_R))
        ScanText(ScannerWords.secondsBack(c), Type.UNIT_TAG, Ink.WHITE, Modifier.centreAt(cx, g.TAG_Y + (g.TAG_H - Type.UNIT_TAG.lh) / 2f))
    }
}

/**
 * «Таблица | Графики · N» at the tabs' right end of a live tab: N the signals ticked; with none «Графики» cannot be
 * chosen. [onGraphs] says which view was picked.
 */
internal fun graphsSwitch(picked: Int, showing: Boolean, onGraphs: (Boolean) -> Unit): Segments =
    Segments(ScannerWords.views(picked), if (showing) 1 else 0, off = if (picked == 0) 1 else null) { onGraphs(it == 1) }

/**
 * The graphs' footer: [lead] (a block's «‹ …»), the pause, and on a block's stream ([onRecord]) «Запись» or «Стоп
 * записи»; the notes the recording under way - its red dot and time - or the last one's length, then [footnote].
 */
@Composable
internal fun GraphsFooter(lead: FooterButton?, pause: FooterButton, live: LiveView?, footnote: String?, onRecord: ((Boolean) -> Unit)?) {
    val recording = live?.recording == true
    val record = onRecord?.let { FooterButton(if (recording) "Стоп записи" else "Запись", false, record = !recording) { it(!recording) } }
    val said = live?.recordingMs?.let { FooterNote.Words(ScannerWords.recording(it), mono = true, rec = true) }
        ?: live?.recordedMs?.let { FooterNote.Words(ScannerWords.recorded(it), mono = true) }
    val note = footnote?.let { FooterNote.Words(it, mono = true) }
    ScanFooter(
        listOfNotNull(lead, pause, record),
        listOfNotNull(said, said?.takeIf { note != null }?.let { FooterNote.Divider }, note),
    )
}

/** «×»'s target: a finger's room around the 9 dp mark. */
private const val CLOSE_TARGET = 32f

/**
 * A signal's square of «Графики» (`pickBox()`) with its top left at [x], [y]: blue with the lane's [number] in black -
 * on the graphs; null, an empty thin frame - not, a tap away; [pale] once four are chosen and no more can be.
 */
@Composable
internal fun PickBox(x: Float, y: Float, number: Int?, pale: Boolean) {
    val p = ScannerSpec.Pick
    Canvas(Modifier.at(x, y).size(p.SIZE.dp)) {
        val r = p.R.dp.toPx()
        if (number != null) {
            drawRoundRect(ink(Ink.BLUE), cornerRadius = CornerRadius(r, r))
        } else {
            val h = p.STROKE.dp.toPx() / 2f
            drawRoundRect(
                ink(if (pale) Ink.QUIET_LINE else Ink.FAINT), Offset(h, h), Size(size.width - 2f * h, size.height - 2f * h),
                CornerRadius(r - h, r - h), style = Stroke(p.STROKE.dp.toPx()),
            )
        }
    }
    if (number != null) ScanText(number.toString(), Type.PICK_NUM, Ink.GROUND, Modifier.centreAt(x + p.SIZE / 2f, y + (p.SIZE - Type.PICK_NUM.lh) / 2f))
}

/** «×» (`closeMark()`), its middle at [cx], [cy]. */
@Composable
private fun CloseMark(cx: Float, cy: Float) {
    val p = ScannerSpec.Pick
    val box = p.CLOSE_SIZE + 2f * p.CLOSE_STROKE
    Canvas(Modifier.at(cx - box / 2f, cy - box / 2f).size(box.dp)) {
        val k = density
        val m = box / 2f * k
        val h = p.CLOSE_SIZE / 2f * k
        val sw = p.CLOSE_STROKE * k
        drawLine(ink(Ink.MUTE), Offset(m - h, m - h), Offset(m + h, m + h), sw, StrokeCap.Round)
        drawLine(ink(Ink.MUTE), Offset(m + h, m - h), Offset(m - h, m + h), sw, StrokeCap.Round)
    }
}
