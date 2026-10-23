package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.CodeView
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.RawLine
import dev.denza.apps.feature.diagnostics.scan.ReadSection
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * One record on its own screen (architecture, 7.3; `scanner.js` `codeScreen()`, boards `scanner-code`,
 * `-code-plain`), over the whole window: what it means, its status byte from bit 7 to 0 and how it
 * was read; the block a lost message names and how it stands now, the same failure type elsewhere;
 * its chronology by the journal, its snapshot and its counters - raw where nothing decodes them. The
 * screen reads its snapshot and counters as it opens; «Прочитать снова» reads them again.
 */

@Composable
internal fun CodePage(
    model: DiagnosticsModel,
    route: ScannerRoute.Code,
    header: @Composable () -> Unit,
    backLabel: String,
    onBack: () -> Unit,
    actions: DiagnosticsActions,
    onModule: (Int) -> Unit,
    onCode: (Int, String) -> Unit,
) {
    val view = model.code?.takeIf { it.request == route.request && it.code == route.code }
    val abbr = ScannerWords.row(model, route.request)?.abbreviation
        ?: model.blocks?.groups?.flatMap { it.rows }?.firstOrNull { it.request == route.request }?.abbreviation
    ScannerPage(footer = {
        ScanFooter(
            listOfNotNull(
                FooterButton(backLabel, false, onClick = onBack),
                abbr?.let { FooterButton("Блок $it", false) { onModule(route.request) } },
                FooterButton("Прочитать снова", true) {
                    actions.openCode(route.request, null)
                    actions.openCode(route.request, route.code)
                },
            ),
            listOfNotNull(view?.footnote?.takeIf { it.isNotEmpty() }?.let { FooterNote.Words(it, mono = true) }),
        )
    }) {
        header()
        val active = view?.status?.byte?.let { ScannerWords.markOf(it) } == Mark.ACTIVE
        BlockHead(
            route.code, view?.aside.orEmpty(), view?.line.orEmpty(),
            counters = view?.counters.orEmpty().map { it.counter() },
            titleRuns = codeRuns(route.code, Type.CODE_TITLE, if (active) Ink.RED else Ink.WHITE),
        )
        if (view != null) CodeBody(view, active, onModule, onCode)
    }
}

@Composable
private fun CodeBody(v: CodeView, active: Boolean, onModule: (Int) -> Unit, onCode: (Int, String) -> Unit) {
    val c = ScannerSpec.Code
    val w = LocalScanWidth.current
    val width = rememberWidths()
    Rule(24f, c.RULE, w - 48f)
    val (x1, x2, x3) = c.X
    val (w1, w2, w3) = c.W

    // the meaning, the status byte, the reads
    ScanText("ЗНАЧЕНИЕ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x1, c.LABEL))
    ScanText(v.meaning, Type.MEANING, Ink.WHITE, Modifier.at(x1, c.MEANING), maxWidth = w1)
    v.meaningNote?.let { ScanText(it, Type.CELL_SMALL, Ink.DIM, Modifier.at(x1, c.MEANING_NOTE), maxWidth = w1) }
    ScanText(v.status.head, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x1, c.STATUS_LABEL))
    BitStrip(x1, c.STRIP, v.status.byte, v.status.availability)
    ScanText(v.status.words, Type.LEAD, if (active) Ink.RED else Ink.WHITE, Modifier.at(x1, c.WORDS), maxWidth = w1)
    v.status.legend?.let { ScanText(it, Type.SMALL12, Ink.FAINT, Modifier.at(x1, c.WORDS_NOTE)) }
    ScanText("КАК ПРОЧИТАН", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x1, c.READ_LABEL))
    v.raw.forEachIndexed { i, r -> RawRead(r, x1, c.READS + i * Type.RAW.lh) }

    // the block a lost message names, and how it stands now
    var sy = c.LABEL
    v.addressee?.let { a ->
        ScanText("АДРЕСАТ · ${a.label}", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x2, c.LABEL))
        Box(Modifier.at(x2, c.CARD_TOP).size(w2.dp, c.CARD_H.dp).plate(Ink.PLATE, Ink.RULE, c.CARD_R).tap { onModule(a.request) })
        val l1 = c.CARD_TOP + c.CARD_PAD_Y
        val l2 = l1 + Type.ABBR.lh + c.CARD_GAP
        ScanRuns(
            listOf(Triple(a.abbreviation + "  ", Type.ABBR, Ink.WHITE), Triple(a.address + "  ", Type.CELL_ABBR, Ink.FAINT), Triple(a.name, Type.CELL_SMALL, Ink.INK3)),
            Modifier.at(x2 + c.CARD_PAD_X, l1), maxWidth = w2 - 2f * c.CARD_PAD_X - ScannerSpec.CHEVRON - 8f, base = Type.ABBR,
        )
        Chevron(Modifier.at(x2 + w2 - c.CARD_PAD_X - ScannerSpec.CHEVRON, l1 + (Type.ABBR.lh - ScannerSpec.CHEVRON) / 2f))
        val parts = a.state.split(" · ")
        val runs = parts.flatMapIndexed { i, p ->
            val ms = p.endsWith(" мс") || p == "—"
            val ink = when {
                i == 0 && "не отвечает" in p -> Ink.ORANGE
                i == 1 && a.mark != null -> Ink.ORANGE
                ms -> Ink.DIM
                else -> Ink.TEXT
            }
            listOfNotNull(Triple(" · ", Type.CELL_SMALL, Ink.SEP).takeIf { i > 0 }, Triple(p, if (ms) Type.NORM_MONO else Type.CELL_SMALL, ink))
        }
        ScanRuns(runs, Modifier.at(x2 + c.CARD_PAD_X, l2), base = Type.CELL_SMALL)
        sy = c.SAME_LABEL
    }
    // the same failure type in other blocks
    ScanText(v.sameTypeTitle, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x2, sy))
    var y = sy + (c.SAME - c.SAME_LABEL)
    if (v.sameType.isNotEmpty()) {
        val cols = ScannerSpec.columns(c.SAME_COLS, x2, w2, c.SAME_GAP)
        v.sameType.forEach { s ->
            val h = c.SAME_ROW
            Box(Modifier.at(x2, y).size(w2.dp, h.dp).tap { onCode(s.request, s.code) })
            ScanRuns(codeRuns(s.code, Type.CODE_SMALL, Ink.WHITE, Type.CELL_MONO), Modifier.at(cols[0].first, mid(y, h, Type.CODE_SMALL)), base = Type.CODE_SMALL)
            ScanText(s.block, Type.NORM_MONO, Ink.DIM, Modifier.at(cols[1].first, mid(y, h, Type.NORM_MONO)))
            s.since?.let { ScanText(it, Type.CELL_ASIDE, Ink.MUTE, Modifier.at(cols[2].first, mid(y, h, Type.CELL_ASIDE))) }
            Chevron(Modifier.at(cols[3].first, y + (h - ScannerSpec.CHEVRON) / 2f))
            Rule(x2, y + h, w2, Ink.HAIR)
            y += h + 1f
        }
        v.sameTypeNote?.let { ScanText(it, Type.NOTE, Ink.MUTE, Modifier.at(x2, y + c.SAME_NOTE_GAP - 1f)) }
    } else {
        ScanText("в других блоках нет", Type.CELL_SMALL, Ink.DIM, Modifier.at(x2, y))
    }

    // when: a dot each read that saw it, joined from the first to the last
    ScanText("ХРОНОЛОГИЯ", Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x3, c.LABEL))
    v.timeline?.let { t ->
        DayAxis(x3, c.TIME_TOP + c.TIME_AXIS, w3, t.days)
        val cy = c.TIME_TOP + c.TIME_DOT * 2f
        Canvas(Modifier.fillMaxSize()) {
            val k = density
            val xs = t.seen.map { x3 + it * w3 }
            if (xs.isNotEmpty()) {
                drawRect(ink(Ink.ORANGE), Offset(xs.first() * k, (cy - c.TIME_BAR / 2f) * k), Size((xs.last() - xs.first()) * k, c.TIME_BAR * k), alpha = c.TIME_BAR_ALPHA)
                xs.forEach { drawCircle(ink(Ink.ORANGE), c.TIME_DOT / 2f * k, Offset(it * k, cy * k)) }
            }
        }
        ScanText(t.line, Type.NOTE, Ink.MUTE, Modifier.at(x3, c.TIME_TOP + c.TIME_CAPTION))
    }
    // the snapshot: its record decoded, or that the block keeps none - raw
    ScanText(v.snapshot.title, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x3, c.SNAP_LABEL))
    y = c.SNAP
    if (v.snapshot.rows.isNotEmpty()) {
        v.snapshot.rows.forEach { f ->
            val h = c.SNAP_ROW
            ScanText(f.key, Type.CELL_SMALL, Ink.INK3, Modifier.at(x3, mid(y, h, Type.CELL_SMALL)), maxWidth = c.SNAP_KEY - 8f)
            ScanText(f.value, Type.ADAS14, if (f.key in v.snapshot.lit) Ink.ORANGE else Ink.TEXT, Modifier.at(x3 + c.SNAP_KEY, mid(y, h, Type.ADAS14)))
            Rule(x3, y + h, w3, Ink.HAIR)
            y += h + 1f
        }
    } else {
        y = unread(v.snapshot, x3, y)
    }
    y += c.COUNT_GAP
    ScanText(v.extended.title, Type.GROUP_TITLE, Ink.MUTE, Modifier.at(x3, y))
    y += c.COUNT_ROW
    if (v.extended.rows.isNotEmpty()) {
        val runs = v.extended.rows.flatMapIndexed { i, f ->
            listOfNotNull(Triple(" · ", Type.CELL_MONO, Ink.SEP).takeIf { i > 0 }, Triple("${f.key} ${f.value}", Type.CELL_MONO, if (f.key in v.extended.lit) Ink.ORANGE else Ink.TEXT))
        }
        ScanRuns(runs, Modifier.at(x3, y), maxWidth = w3)
    } else {
        unread(v.extended, x3, y)
    }
}

/** A read with nothing decoded: its word - «записи нет», «читаю…» - and what came back, raw; returns the y under it. */
@Composable
private fun unread(s: ReadSection, x: Float, top: Float): Float {
    var y = top
    s.word?.let { ScanText(it, Type.CELL_SMALL, Ink.TEXT, Modifier.at(x, y)); y += Type.CELL_SMALL.lh }
    s.raw.forEach { RawRead(RawLine(it), x, y); y += Type.RAW.lh }
    return y
}

/**
 * A read as it went (`readLine()`): the ask, «→», the answer cut to its head, and the record of the
 * screen standing out on a plate.
 */
@Composable
internal fun RawRead(line: RawLine, x: Float, top: Float) {
    val c = ScannerSpec.Code
    val width = rememberWidths()
    val (before, record, after) = ScannerWords.pieces(line)
    ScanText(before, Type.RAW, Ink.DIM, Modifier.at(x, top))
    if (record.isEmpty()) return
    val rx = x + width(before, Type.RAW)
    val rw = width(record, Type.RAW)
    val s = Type.RAW
    val ph = s.size * 1.3f
    Box(Modifier.at(rx - c.PICK_X, top + (s.lh - ph) / 2f - c.PICK_Y).size((rw + 2f * c.PICK_X).dp, (ph + 2f * c.PICK_Y).dp).plate(Ink.PICK, null, c.PICK_R))
    ScanText(record, Type.RAW, Ink.WHITE, Modifier.at(rx, top))
    if (after.isNotEmpty()) ScanText(after, Type.RAW, Ink.DIM, Modifier.at(rx + rw, top))
}

/**
 * A status byte as one strip from bit 7 to 0 (`bitStrip()`): the bit's number over its cell, its name
 * under it; a set bit filled - red where it says now (TF) or asks the lamp (WIR) - a clear one framed,
 * «·» where the block does not report the bit. [plain]: a mask, every set bit alike.
 */
@Composable
internal fun BitStrip(x: Float, y: Float, value: Int, availability: Int, plain: Boolean = false) {
    val s = ScannerSpec.BitStrip
    ScannerWords.STRIP.forEachIndexed { i, name ->
        val bit = 7 - i
        val cx = x + i * (s.CELL + s.GAP)
        val mx = cx + s.CELL / 2f
        val cy = y + s.CELL_Y
        ScanText(bit.toString(), Type.BIT, Ink.FAINT, Modifier.centreAt(mx, y))
        val has = (availability shr bit) and 1 == 1
        val on = (value shr bit) and 1 == 1
        val (t, inkT) = when {
            !has -> {
                Box(Modifier.at(cx, cy).size(s.CELL.dp, s.H.dp).plate(null, Ink.QUIET_LINE, s.R, dash = true))
                "·" to Ink.FAINT3
            }
            on -> {
                Box(Modifier.at(cx, cy).size(s.CELL.dp, s.H.dp).plate(if (!plain && (bit == 0 || bit == 7)) Ink.RED else Ink.TEXT, null, s.R))
                "1" to Ink.GROUND
            }
            else -> {
                Box(Modifier.at(cx, cy).size(s.CELL.dp, s.H.dp).plate(null, Ink.LINE3, s.R))
                "0" to Ink.DIM
            }
        }
        ScanText(t, Type.DRIVE_CELL, inkT, Modifier.centreAt(mx, cy + (s.H - Type.DRIVE_CELL.lh) / 2f))
        ScanText(name, Type.BIT_HEAD, Ink.MUTE, Modifier.centreAt(mx, y + s.NAME_Y))
    }
}

/** A day axis (`dayAxis()`): a tick and its date at each day's start along [x], [x] + [w]. */
@Composable
internal fun DayAxis(x: Float, y: Float, w: Float, days: List<String>) {
    val c = ScannerSpec.Code
    val n = days.size.coerceAtLeast(1)
    days.forEachIndexed { i, d ->
        val dx = x + i * w / n
        Box(Modifier.at(dx, y).size(1.dp, c.TIME_TICK.dp).plate(Ink.LINE3))
        ScanText(d, Type.ROW_SIG, Ink.FAINT, Modifier.at(dx + c.TIME_LABEL_GAP, y - 2f))
    }
}
