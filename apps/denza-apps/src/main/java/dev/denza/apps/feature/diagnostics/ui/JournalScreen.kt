package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.JournalQuantityLane
import dev.denza.apps.feature.diagnostics.scan.JournalView
import dev.denza.apps.feature.diagnostics.scan.ScanCompareView
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Журнал» (architecture, 6.4, 7.3; `scanner.js` `journal()`, `journalPanel()`, boards
 * `scanner-journal`, `-journal-first`): the car's time on one day axis - the scans asked for (a block
 * read alone dimmer), the software updates, the cells' snapshots, a full charge named over its dot -
 * every code from its first read, the values by scan; two scans side by side in the panel at the
 * right. «Выбрать сканы» takes two scans off the axis for the panel.
 */

@Composable
internal fun JournalPage(
    model: DiagnosticsModel,
    tab: Int,
    frame: RailFrame,
    onTab: (Int) -> Unit,
    actions: DiagnosticsActions,
    onReport: () -> Unit,
) {
    val view = model.journal
    // «Выбрать сканы»: the scans tapped on the axis, by their end; two make the comparison
    var picking by remember { mutableStateOf<List<Long>?>(null) }
    val pick = { millis: Long ->
        val next = (picking.orEmpty() + millis).distinct()
        if (next.size >= 2) {
            val (a, b) = next.sorted().take(2)
            actions.compareScans(a, b)
            picking = null
        } else {
            picking = next
        }
    }
    val choosable = (view?.scans?.count { it.full && it.atMillis > 0 } ?: 0) >= 2
    RailPage(frame, footer = {
        ScanFooter(
            listOfNotNull(
                when {
                    picking != null -> FooterButton("Отмена", true) { picking = null }
                    choosable -> FooterButton("Выбрать сканы", true) { picking = emptyList() }
                    else -> null
                },
                FooterButton("Экспорт журнала", false, onClick = onReport),
            ),
            emptyList(),
        )
    }) {
        BlockHead("Журнал", view?.aside.orEmpty(), view?.line.orEmpty(), counters = view?.counters.orEmpty().map { it.counter() })
        ScanTabs(JOURNAL_TABS, tab, onTab)
        if (view == null) return@RailPage
        when (tab) {
            0 -> {
                Chronology(view, picking, pick)
                view.compare?.let { ComparePanel(it, ScannerSpec.Journal.PANEL_X, LocalScanWidth.current - 24f - ScannerSpec.Journal.PANEL_X) }
            }
            1 -> view.compare?.let { CompareAcross(it) }
            else -> Records(view)
        }
    }
}

/** «Сравнение» across the page (`journalCompare()`): the two moments, then the codes, the software and the values each on a plate. */
@Composable
private fun CompareAcross(m: ScanCompareView) {
    val j = ScannerSpec.Journal
    val w = LocalScanWidth.current
    val width = rememberWidths()
    val one = "→" !in m.title
    ScanText(if (one) "СКАН" else "СРАВНЕНИЕ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(24f, j.COMPARE_TOP))
    ScanText(m.title, Type.KEY_VALUE, Ink.WHITE, Modifier.at(24f, mid(j.COMPARE_TOP + Type.GROUP_TITLE.lh, j.COMPARE_HEAD, Type.KEY_VALUE)))
    val pw = (w - 48f - (j.COMPARE_COLS - 1) * j.COMPARE_GAP) / j.COMPARE_COLS
    val top = j.COMPARE_TOP + Type.GROUP_TITLE.lh + j.COMPARE_HEAD + j.COMPARE_GAP
    data class Line(val key: String, val value: String, val tag: String? = null)
    val plates = listOf(
        "КОДЫ" to m.codes.map { Line(it.key, it.value) },
        "ПО" to m.software.map { Line(it.key, it.value, "ИЗМ.") }.ifEmpty { listOf(Line("без изменений", "—")) },
        "ВЕЛИЧИНЫ" to m.quantities.map { Line(it.key, it.value) },
    )
    // the plates as tall as the longest: its label and its rows
    val ph = 2f * j.PANEL_PAD_Y + Type.GROUP_TITLE.lh + 4f + (plates.maxOfOrNull { it.second.size } ?: 0) * (j.PANEL_ROW + 1f + 4f) - 4f
    plates.forEachIndexed { i, (label, rows) ->
        val x = 24f + i * (pw + j.COMPARE_GAP)
        val ix = x + j.PANEL_PAD_X
        val iw = pw - 2f * j.PANEL_PAD_X
        Box(Modifier.at(x, top).size(pw.dp, ph.dp).plate(Ink.PLATE, Ink.RULE, j.PANEL_R))
        var y = top + j.PANEL_PAD_Y
        ScanText(label, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(ix, y))
        y += Type.GROUP_TITLE.lh + 4f
        rows.forEach { r ->
            ScanText(r.key, Type.UNIT, Ink.INK3, Modifier.at(ix, mid(y, j.PANEL_ROW, Type.UNIT)))
            ScanText(r.value, Type.NORM_MONO, if (r.value == "—") Ink.FAINT else Ink.TEXT, Modifier.endAt(ix + iw, mid(y, j.PANEL_ROW, Type.NORM_MONO)))
            r.tag?.let { TagChip(it, ix + iw - width(r.value, Type.NORM_MONO) - 8f, y + j.PANEL_ROW / 2f) }
            Rule(ix, y + j.PANEL_ROW, iw, Ink.HAIR)
            y += j.PANEL_ROW + 1f + 4f
        }
    }
}

/**
 * «Записи» (`journalRecords()`): every block with records on the whole width of the day axis - its sign,
 * its records, a line from its first read to its last and a dot each read - and how many reads found them.
 */
@Composable
private fun Records(view: JournalView) {
    val j = ScannerSpec.Journal
    val w = LocalScanWidth.current
    val px = j.RECORDS_PLOT_X
    val pw = w - 24f - j.RECORDS_COUNT_W - px
    fun x(at: Float) = px + at * pw
    val sign = ScannerSpec.Sign.R
    val width = rememberWidths()
    DayAxis(px, j.RECORDS_AXIS_Y, pw, view.days)
    ScanText("БЛОК", Type.COL_HEAD, Ink.MUTE, Modifier.at(24f, j.RECORDS_AXIS_Y - 2f))
    ScanText("ПРОЧТЕНИЙ", Type.COL_HEAD, Ink.MUTE, Modifier.endAt(w - 24f, j.RECORDS_AXIS_Y - 2f))
    Canvas(Modifier.fillMaxSize()) {
        val k = density
        // the days' lines down to the last block's row
        val gh = j.RECORDS_TOP + view.codes.size * j.RECORDS_STEP - j.RECORDS_GRID_TOP
        val n = view.days.size.coerceAtLeast(1)
        for (i in 0..view.days.size) drawRect(ink(Ink.HAIR), Offset((px + i * pw / n) * k, j.RECORDS_GRID_TOP * k), Size(k, gh * k))
        view.codes.forEachIndexed { i, c ->
            val cy = j.RECORDS_TOP + i * j.RECORDS_STEP + j.RECORDS_H / 2f
            if (c.seen.isNotEmpty()) {
                val a = x(c.seen.first())
                val b = x(c.seen.last())
                drawRect(ink(Ink.ORANGE), Offset(a * k, (cy - 1f) * k), Size((b - a) * k, 2f * k), alpha = ScannerSpec.Code.TIME_BAR_ALPHA)
                c.seen.forEach { drawCircle(ink(Ink.ORANGE), j.DOT / 2f * k, Offset(x(it) * k, cy * k)) }
            }
        }
    }
    view.codes.forEachIndexed { i, c ->
        val y = j.RECORDS_TOP + i * j.RECORDS_STEP
        SignAt(c.mark, 24f + sign, y + j.RECORDS_H / 2f)
        ScanText(c.block, Type.CELL_ABBR, Ink.INK3, Modifier.at(24f + 2f * sign + 8f, mid(y, j.RECORDS_H, Type.CELL_ABBR)))
        ScanText("×${c.count}", Type.SIG, Ink.MUTE, Modifier.at(24f + 2f * sign + 16f + width(c.block, Type.CELL_ABBR), mid(y, j.RECORDS_H, Type.SIG)))
        ScanText(c.seen.size.toString(), Type.NORM_MONO, Ink.TEXT, Modifier.endAt(w - 24f, mid(y, j.RECORDS_H, Type.NORM_MONO)))
        Rule(24f, y + j.RECORDS_H + (j.RECORDS_STEP - j.RECORDS_H) / 2f, w - 48f, Ink.HAIR)
    }
}

/** The day axis and its lanes (`journal()` left of the panel). */
@Composable
private fun Chronology(view: JournalView, picking: List<Long>?, onPick: (Long) -> Unit) {
    val j = ScannerSpec.Journal
    val px = j.PLOT_X
    val pw = j.PLOT_W
    fun x(at: Float) = px + at * pw
    val (ys, yw, yc) = j.LANES
    val (v0, v1) = j.VALUES
    val sign = ScannerSpec.Sign.R
    val width = rememberWidths()
    DayAxis(px, j.AXIS_Y, pw, view.days)
    Canvas(Modifier.fillMaxSize()) {
        val k = density
        fun rect(rx: Float, ry: Float, rw: Float, rh: Float, color: Int, alpha: Float = 1f) =
            drawRect(ink(color), Offset(rx * k, ry * k), Size(rw * k, rh * k), alpha = alpha)
        val n = view.days.size.coerceAtLeast(1)
        for (i in 0..view.days.size) rect(px + i * pw / n, j.GRID_TOP, 1f, j.GRID_H, Ink.HAIR)
        // scans: a bar each, a full scan taller and white, a block read again dimmer; a picked one blue
        view.scans.forEach { s ->
            val picked = s.atMillis > 0 && picking?.contains(s.atMillis) == true
            rect(x(s.at) - 1f, ys + if (s.full) 6f else 7f, 2f, if (s.full) 16f else 14f, if (picked) Ink.BLUE else if (s.full) Ink.TEXT else Ink.DIM)
        }
        view.software.forEach { u ->
            val ox = x(u.at) * k
            val oy = (yw + j.LANE_H / 2f) * k
            rotate(45f, Offset(ox, oy)) { drawRect(ink(Ink.TEXT), Offset(ox - 6f * k, oy - 6f * k), Size(12f * k, 12f * k)) }
        }
        view.cells.forEach { s -> drawCircle(ink(if (s.full) Ink.TEXT else Ink.SEP), j.DOT / 2f * k, Offset(x(s.at) * k, (yc + j.LANE_H / 2f) * k)) }
        j.RULES.forEach { ry -> rect(24f, ry, px + pw - 24f, 1f, Ink.RULE) }
        // codes: a line from the first read to the last that saw them, a dot each read
        view.codes.forEachIndexed { i, c ->
            val cy = j.CODES_TOP + i * j.CODE_STEP + j.CODE_H / 2f
            if (c.seen.isNotEmpty()) {
                val a = x(c.seen.first())
                val b = x(c.seen.last())
                rect(a, cy - 1f, b - a, 2f, Ink.ORANGE, ScannerSpec.Code.TIME_BAR_ALPHA)
                c.seen.forEach { drawCircle(ink(Ink.ORANGE), j.DOT / 2f * k, Offset(x(it) * k, cy * k)) }
            }
        }
        // values: a line with its points by scan, or points alone on the lane's middle
        view.quantities.take(j.VALUES.size).forEachIndexed { i, q ->
            val top = j.VALUES[i]
            if (q.pointsOnly) {
                q.points.forEach { (at, _) -> drawCircle(ink(Ink.BLUE), (j.DOT / 2f - 0.5f) * k, Offset(x(at) * k, (top + j.VALUE_H / 2f) * k)) }
            } else {
                val pts = q.points.map { (at, v) -> Offset(x(at) * k, quantityY(q, top, v) * k) }
                if (pts.size > 1) {
                    val path = Path().apply { pts.forEachIndexed { n2, p -> if (n2 == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                    drawPath(path, ink(Ink.BLUE), style = Stroke(1.5f * k))
                }
                pts.forEach { drawCircle(ink(Ink.BLUE), j.POINT * k, it) }
            }
        }
    }
    @Composable
    fun lane(name: String, top: Float, h: Float) = ScanText(name, Type.UNIT, Ink.INK3, Modifier.at(24f, mid(top, h, Type.UNIT)), maxWidth = j.LANE_W)
    if (view.scans.isNotEmpty() || view.days.isNotEmpty()) {
        lane("Сканы", ys, j.LANE_H)
        lane("Обновления ПО", yw, j.LANE_H)
        lane("Снимки ячеек", yc, j.LANE_H)
    }
    if (picking != null) {
        view.scans.filter { it.atMillis > 0 }.forEach { s -> Box(Modifier.at(x(s.at) - 8f, ys).size(16.dp, j.LANE_H.dp).tap { onPick(s.atMillis) }) }
    }
    view.software.forEach { u -> ScanText(u.text, Type.SIG, Ink.DIM, Modifier.at(x(u.at) + 12f, mid(yw, j.LANE_H, Type.SIG))) }
    // a full charge named over its dot, where no neighbour stands
    view.cells.forEach { s ->
        s.label?.let { ScanText(it, Type.TICK, Ink.DIM, Modifier.centreAt(x(s.at), yc + j.LANE_H / 2f - j.DOT / 2f - j.CELL_LABEL - Type.TICK.lh)) }
    }
    ScanText("КОДЫ · С ПЕРВОГО ПРОЧТЕНИЯ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(24f, j.CODES_LABEL))
    view.codes.forEachIndexed { i, c ->
        val y = j.CODES_TOP + i * j.CODE_STEP
        SignAt(c.mark, 24f + sign, y + j.CODE_H / 2f)
        ScanText(c.block, Type.CELL_ABBR, Ink.INK3, Modifier.at(24f + 2f * sign + 8f, mid(y, j.CODE_H, Type.CELL_ABBR)))
        ScanText("×${c.count}", Type.SIG, Ink.MUTE, Modifier.at(24f + 2f * sign + 16f + width(c.block, Type.CELL_ABBR), mid(y, j.CODE_H, Type.SIG)))
    }
    if (view.quantities.isNotEmpty()) ScanText("ВЕЛИЧИНЫ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(24f, j.VALUES_LABEL))
    view.quantities.take(j.VALUES.size).forEachIndexed { i, q ->
        val top = j.VALUES[i]
        lane(q.name, top, j.VALUE_H)
        if (q.points.isEmpty()) {
            ScanText("—", Type.SIG, Ink.FAINT, Modifier.at(px + 4f, mid(top, j.VALUE_H, Type.SIG)))
        } else if (q.pointsOnly && q.pointLabels.size == q.points.size) {
            q.points.forEachIndexed { n, (at, _) -> ScanText(q.pointLabels[n], Type.SIG, Ink.DIM, Modifier.at(x(at) + 7f, mid(top, j.VALUE_H, Type.SIG))) }
        } else {
            val (at, _) = q.points.last()
            ScanText(q.last, Type.SIG, Ink.DIM, Modifier.at(x(at) + if (q.pointsOnly) 7f else 8f, mid(top, j.VALUE_H, Type.SIG)))
        }
    }
}

private fun quantityY(q: JournalQuantityLane, top: Float, v: Float): Float {
    val h = ScannerSpec.Journal.VALUE_H
    val span = (q.scale.endInclusive - q.scale.start).takeIf { it != 0f } ?: 1f
    return top + h - 4f - (v - q.scale.start) / span * (h - 8f)
}

/** The comparison (`journalPanel()`): two scans - or the one there is - their codes, software and values. */
@Composable
private fun ComparePanel(m: ScanCompareView, x: Float, w: Float) {
    val p = ScannerSpec.Journal
    val ix = x + p.PANEL_PAD_X
    val iw = w - 2f * p.PANEL_PAD_X
    val width = rememberWidths()
    Box(Modifier.at(x, p.PANEL_TOP).size(w.dp, p.PANEL_H.dp).plate(Ink.PLATE, Ink.RULE, p.PANEL_R))
    var y = p.PANEL_TOP + p.PANEL_PAD_Y
    @Composable
    fun label(t: String, gap: Float = 0f) {
        y += gap
        ScanText(t, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(ix, y))
        y += Type.GROUP_TITLE.lh + 4f
    }
    @Composable
    fun row(f: Field, tag: String? = null) {
        ScanText(f.key, Type.UNIT, Ink.INK3, Modifier.at(ix, mid(y, p.PANEL_ROW, Type.UNIT)))
        ScanText(f.value, Type.NORM_MONO, Ink.TEXT, Modifier.endAt(ix + iw, mid(y, p.PANEL_ROW, Type.NORM_MONO)))
        if (tag != null) TagChip(tag, ix + iw - width(f.value, Type.NORM_MONO) - 8f, y + p.PANEL_ROW / 2f)
        Rule(ix, y + p.PANEL_ROW, iw, Ink.HAIR)
        y += p.PANEL_ROW + 1f + 4f
    }
    label(if ("→" in m.title) "СРАВНЕНИЕ" else "СКАН")
    ScanText(m.title, Type.NORM_MONO, Ink.WHITE, Modifier.at(ix, mid(y, p.PANEL_HEAD, Type.NORM_MONO)))
    y += p.PANEL_HEAD + 4f
    label("КОДЫ", 6f)
    m.codes.forEach { row(it) }
    if (m.software.isNotEmpty()) {
        label("ПО", p.PANEL_GAP)
        m.software.forEach { row(it, "ИЗМ.") }
    }
    label("ВЕЛИЧИНЫ", p.PANEL_GAP)
    m.quantities.forEach { row(it) }
}
