package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.CompareCell
import dev.denza.apps.feature.diagnostics.scan.CompareView
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.SignalRow
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * A system's tables beside the rail: the pack's readings (`bmsTable()`), the drives side by side
 * (`drivesTable()`) and the driver-assistance blocks side by side (`adasTable()`).
 */

@Composable
internal fun PackTable(rows: List<SignalRow>, onParam: ((ParamKey) -> Unit)? = null) {
    val b = ScannerSpec.Bms
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(b.COLS, 24f, w)
    HeadRow(
        cols,
        listOf(HeadLabel("СИГНАЛ"), HeadLabel("ПАРАМЕТР"), HeadLabel("ЗНАЧЕНИЕ", end = true, pad = b.VALUE_PAD), HeadLabel("ЕД."), HeadLabel("НОРМА"), HeadLabel("ОЦЕНКА")),
        b.TOP, b.HEAD,
    )
    // the rows scroll under the captions between them and the footer, at their board places
    ScrollRows(b.TOP + b.HEAD + 1f) {
        Box(Modifier.fillMaxWidth().height((rows.size * (b.ROW + 1f)).dp)) { PackRows(rows, cols, w, onParam) }
    }
}

@Composable
private fun PackRows(rows: List<SignalRow>, cols: List<Pair<Float, Float>>, w: Float, onParam: ((ParamKey) -> Unit)?) {
    val b = ScannerSpec.Bms
    var y = 0f
    rows.forEach { r ->
        val h = b.ROW
        // a reading with a key opens alone; a calculation has none
        val key = r.key
        if (key != null && onParam != null) Box(Modifier.at(24f, y).size(w.dp, h.dp).tap { onParam(key) })
        ScanText(r.source, Type.SIG, Ink.FAINT2, Modifier.at(cols[0].first + 12f, mid(y, h, Type.SIG)))
        ScanText(r.name, Type.CELL14, Ink.WHITE, Modifier.at(cols[1].first, mid(y, h, Type.CELL14)), maxWidth = cols[1].second)
        ScanText(r.value, Type.VALUE_BMS, if (r.stale) Ink.FAINT else Ink.WHITE, Modifier.endAt(cols[2].first + cols[2].second - b.VALUE_PAD, mid(y, h, Type.VALUE_BMS)), maxWidth = cols[2].second - b.VALUE_PAD)
        ScanText(r.unit, Type.UNIT, Ink.DIM2, Modifier.at(cols[3].first, mid(y, h, Type.UNIT)))
        ScanText(r.norm, Type.NORM, Ink.FAINT, Modifier.at(cols[4].first, mid(y, h, Type.NORM)), maxWidth = cols[4].second)
        val mark = when {
            r.stale -> "УСТАРЕЛО" to ScannerSpec.Tones.UNREAD
            r.ok == true -> "OK" to ScannerSpec.Tones.OK
            r.ok == false -> "ВНЕ НОРМЫ" to ScannerSpec.Tones.ON
            else -> null
        }
        if (mark != null) Badge(mark.first, mark.second, BadgeSize.SMALL, Modifier.at(cols[5].first, y + (h - ScannerSpec.Badge.SMALL_H) / 2f))
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}

@Composable
internal fun DrivesTable(view: CompareView) {
    val d = ScannerSpec.Drives
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(d.COLS, 24f, w)
    val h0 = d.HEAD
    ScanText("ПАРАМЕТР", Type.COL_HEAD, Ink.MUTE, Modifier.at(cols[0].first + 12f, mid(d.TOP, h0, Type.COL_HEAD)))
    view.columns.take(4).forEachIndexed { i, c ->
        val (x, cw) = cols[1 + i]
        val top = d.TOP + (h0 - Type.UNIT_NAME.lh - 2f - Type.UNIT_TAG.lh) / 2f
        // a column is headed by its block, its role and side under it («Задний левый»)
        ScanText(ScannerWords.columnTag(c), Type.UNIT_NAME, Ink.WHITE, Modifier.endAt(x + cw - d.PAD, top))
        ScanText(c.name, Type.UNIT_TAG, Ink.FAINT, Modifier.endAt(x + cw - d.PAD, top + Type.UNIT_NAME.lh + 2f))
    }
    ScanText("ЕД.", Type.COL_HEAD, Ink.MUTE, Modifier.at(cols[5].first, mid(d.TOP, h0, Type.COL_HEAD)))
    Rule(24f, d.TOP + h0, w)
    var y = d.TOP + h0 + 1f
    view.rows.forEach { r ->
        val h = d.ROW
        val top = y + (h - Type.CELL.lh - 2f - Type.ROW_SIG.lh) / 2f
        ScanText(r.name, Type.CELL, Ink.WHITE, Modifier.at(cols[0].first + 12f, top), maxWidth = cols[0].second - 12f)
        ScanText(r.source, Type.ROW_SIG, Ink.FAINT3, Modifier.at(cols[0].first + 12f, top + Type.CELL.lh + 2f), maxWidth = cols[0].second - 12f)
        r.cells.take(4).forEachIndexed { i, v ->
            val (x, cw) = cols[1 + i]
            val st = if (v.text.length > 12) Type.DRIVE_SMALL else Type.DRIVE_CELL
            ScanText(v.text, st, if (v.muted) Ink.FAINT2 else Ink.WHITE, Modifier.endAt(x + cw - d.PAD, mid(y, h, st)))
        }
        ScanText(r.unit, Type.UNIT, Ink.DIM2, Modifier.at(cols[5].first, mid(y, h, Type.UNIT)))
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}

/** A comparison cell's type and ink: by the row it stands in, as the page set them. */
private fun adasLook(row: String, cell: CompareCell): Pair<ScannerSpec.Style, Int> {
    val size = when {
        cell.muted && cell.text != "—" -> Type.ADAS12_5
        row.startsWith("DTC") -> Type.ADAS16
        row.startsWith("Запрос") || row.startsWith("Формат") -> Type.ADAS13
        "F194" in row -> Type.ADAS11
        "Идентификатор" in row -> Type.ADAS12_5
        else -> Type.ADAS14
    }
    val inkOf = when {
        cell.muted -> Ink.FAINT2
        cell.warn -> Ink.ORANGE_INK
        row.startsWith("Запрос") -> Ink.DIM
        cell.shared && "F194" in row -> Ink.BLUE_INK
        cell.shared && "Идентификатор" in row -> Ink.DIM
        else -> Ink.WHITE
    }
    return size to inkOf
}

@Composable
internal fun AdasTable(model: DiagnosticsModel, view: CompareView) {
    val a = ScannerSpec.Adas
    val w = LocalScanWidth.current - 48f
    val cols = ScannerSpec.columns(a.COLS, 24f, w)
    ScanText("ПАРАМЕТР", Type.COL_HEAD, Ink.MUTE, Modifier.at(cols[0].first + 12f, mid(a.TOP, a.HEAD, Type.COL_HEAD)))
    view.columns.take(cols.size - 1).forEachIndexed { i, c ->
        val (x, cw) = cols[1 + i]
        val top = a.TOP + (a.HEAD - Type.ABBR.lh - 2f - Type.ABBR_NAME.lh) / 2f
        val read = c.request?.let { ScannerWords.row(model, it) }?.let { ScannerWords.read(it.status) } == true
        ScanText(c.abbreviation, Type.ABBR, if (read) Ink.WHITE else Ink.INK2, Modifier.at(x + a.PAD, top))
        ScanText(c.name, Type.ABBR_NAME, Ink.MUTE, Modifier.at(x + a.PAD, top + Type.ABBR.lh + 2f), maxWidth = cw - a.PAD)
    }
    Rule(24f, a.TOP + a.HEAD, w)
    var y = a.TOP + a.HEAD + 1f
    view.rows.forEach { r ->
        val h = a.ROW
        ScanText(r.name, Type.KEY, Ink.DIM2, Modifier.at(cols[0].first + 12f, mid(y, h, Type.KEY)))
        r.cells.take(cols.size - 1).forEachIndexed { i, v ->
            val (x, cw) = cols[1 + i]
            if (v.shared) Box(Modifier.at(x, y).size(cw.dp, h.dp).plate(Ink.BLUE_SHARE))
            val (st, color) = adasLook(r.name, v)
            ScanText(v.text, st, color, Modifier.at(x + a.PAD, mid(y, h, st)), maxWidth = cw - a.PAD)
        }
        Rule(24f, y + h, w, Ink.HAIR)
        y += h + 1f
    }
}
