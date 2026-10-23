package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.Header
import dev.denza.apps.feature.diagnostics.scan.HeadCounter
import dev.denza.apps.feature.diagnostics.scan.LinkState
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Style
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Tone
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.ceil

/*
 * The chrome every scanner screen shares - `scanner.js`'s `header`, `rail`, `blockHead`, `tabs`,
 * `footer`, `sign`, `badge` and `button` - each a composable placed where the board places it.
 */

/**
 * How wide the screen's area is, dp: the window for a block's screen, what the rail leaves for a
 * page beside it (the board's `W` inside `shell()`).
 */
internal val LocalScanWidth = androidx.compose.runtime.staticCompositionLocalOf { ScannerSpec.W }

internal data class Crumb(val text: String, val onClick: (() -> Unit)? = null)

/**
 * A figure in a head: its caption and its value - the figure, and after its last space a unit drawn
 * small beside it («553 В»). [on] red and glowing (MIL on, a record failing now), [out] orange (outside
 * its norm); [mark] a record kind's sign before a count of records.
 */
internal data class Counter(val label: String, val value: String, val on: Boolean = false, val mark: Mark? = null, val out: Boolean = false)

internal fun HeadCounter.counter(): Counter = Counter(label, value, on, mark, out)

/** A footer button; [record] puts the recording's red dot before its label. */
internal data class FooterButton(val label: String, val primary: Boolean, val record: Boolean = false, val onClick: () -> Unit)

internal sealed interface FooterNote {
    data class Words(val text: String, val mono: Boolean = false) : FooterNote
    data object Divider : FooterNote
}

/** The page: black, the footer at the foot, everything else placed from the top. */
@Composable
internal fun ScannerPage(footer: @Composable () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().plate(Ink.GROUND)) {
        content()
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { footer() }
    }
}

/* ------------------------------------------------------------------ signs */

/**
 * A record's sign (`sign()`) with its middle at [cx], [cy], in the drawing's own units: ● active in red,
 * ◌ pending and ○ in history as orange rings - the pending one dashed - a dashed square for a block that
 * did not answer.
 */
internal fun DrawScope.drawSign(mark: Mark, cx: Float, cy: Float, unit: Float = 1f) {
    val s = ScannerSpec.Sign
    when (mark) {
        Mark.ACTIVE -> drawCircle(ink(Ink.RED), s.R * unit, Offset(cx, cy))
        Mark.NO_REPLY -> {
            val q = s.SQUARE * unit
            val h = 0.5f * unit
            drawRoundRect(
                ink(Ink.FAINT), Offset(cx - q / 2 + h, cy - q / 2 + h), Size(q - 2 * h, q - 2 * h), CornerRadius(1.5f * unit),
                style = Stroke(unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s.DASH[0] * unit, s.DASH[1] * unit))),
            )
        }
        Mark.PENDING, Mark.HISTORY -> drawCircle(
            ink(Ink.ORANGE), (s.R - s.STROKE / 2f) * unit, Offset(cx, cy),
            style = Stroke(
                s.STROKE * unit,
                pathEffect = if (mark == Mark.PENDING) PathEffect.dashPathEffect(floatArrayOf(s.DASH[0] * unit, s.DASH[1] * unit)) else null,
            ),
        )
    }
}

/** A sign as a composable, its middle at [cx], [cy] dp of the parent. */
@Composable
internal fun SignAt(mark: Mark, cx: Float, cy: Float) {
    val box = maxOf(2f * ScannerSpec.Sign.R, ScannerSpec.Sign.SQUARE) + 2f
    Canvas(Modifier.at(cx - box / 2f, cy - box / 2f).size(box.dp)) {
        drawSign(mark, size.width / 2f, size.height / 2f, density)
    }
}

/** The room a sign and its gap take before a count: `2 r + mark.gap`. */
internal val SIGN_W = 2f * ScannerSpec.Sign.R + ScannerSpec.Rail.MARK_GAP

/* ----------------------------------------------------------------- header */

/**
 * The bar: crumbs at the left; at the right, before the close button, the car - its name, mileage,
 * mode, 12 V, the link's dot and median exchange, the time - a «·» between them, on one baseline.
 */
@Composable
internal fun ScanHeader(crumbs: List<Crumb>, head: Header, onClose: () -> Unit) {
    val h = ScannerSpec.Header
    val room = h.HEIGHT - 1f
    Box(Modifier.fillMaxWidth().height(h.HEIGHT.dp).plate(Ink.BAR)) {
        Box(Modifier.at(0f, room).fillMaxWidth().height(1.dp).plate(Ink.RULE))
        val width = rememberWidths()
        var x = h.PAD_LEFT
        crumbs.forEachIndexed { i, c ->
            val now = i == crumbs.lastIndex
            val style = if (now) Type.CRUMB_NOW else Type.CRUMB
            ScanText(c.text, style, if (now) Ink.WHITE else Ink.MUTE, Modifier.at(x, (room - style.lh) / 2f).tap(c.onClick))
            x += width(c.text, style) + h.GAP
            if (!now) {
                ScanText("›", Type.CRUMB, Ink.SEP, Modifier.at(x, (room - Type.CRUMB.lh) / 2f))
                x += width("›", Type.CRUMB) + h.GAP
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val kx = maxWidth.value - h.PAD_RIGHT - h.CLOSE_W
            val items = headerItems(head)
            fun partW(p: HeadPart) = if (p.dot != null) h.DOT + h.DOT_GAP - h.VOLTS_GAP else width(p.text, p.style)
            fun itemW(it: List<HeadPart>) = it.foldIndexed(0f) { i, s, p -> s + partW(p) + if (i > 0) h.VOLTS_GAP else 0f }
            val sepW = width("·", Type.META)
            var mx = kx - h.GAP - items.sumOf { itemW(it).toDouble() }.toFloat() - (items.size - 1) * (2 * h.META_GAP + sepW)
            val top = (room - Type.META.lh) / 2f
            val by = Type.META.baseline
            items.forEachIndexed { k, item ->
                if (k > 0) {
                    ScanText("·", Type.META, Ink.SEP, Modifier.at(mx + h.META_GAP, top), baseline = by)
                    mx += 2 * h.META_GAP + sepW
                }
                item.forEachIndexed { i, p ->
                    if (i > 0) mx += h.VOLTS_GAP
                    val dot = p.dot
                    if (dot != null) {
                        val glow = head.link == LinkState.OK
                        Canvas(Modifier.at(mx, (room - h.DOT) / 2f).size(h.DOT.dp)) {
                            val rr = size.width / 2f
                            if (glow) glowUnder(dot, h.DOT_GLOW) { c, paint -> c.drawCircle(rr, rr, rr, paint) }
                            drawCircle(ink(dot), rr)
                        }
                        mx += h.DOT + h.DOT_GAP - h.VOLTS_GAP
                    } else {
                        ScanText(p.text, p.style, p.ink, Modifier.at(mx, top), baseline = by)
                        mx += width(p.text, p.style)
                    }
                }
            }
            Canvas(Modifier.at(kx, (room - h.CLOSE_H) / 2f).size(h.CLOSE_W.dp, h.CLOSE_H.dp).tap(onClose)) {
                val r = h.CLOSE_R.dp.toPx()
                drawRoundRect(ink(Ink.QUIET), cornerRadius = CornerRadius(r, r))
                val icon = h.CLOSE_ICON.dp.toPx()
                val ix = (size.width - icon) / 2f
                val iy = (size.height - icon) / 2f
                val a = icon / 8f
                val b = icon - a
                val sw = h.CLOSE_STROKE.dp.toPx()
                drawLine(ink(Ink.TEXT), Offset(ix + a, iy + a), Offset(ix + b, iy + b), sw, StrokeCap.Round)
                drawLine(ink(Ink.TEXT), Offset(ix + b, iy + a), Offset(ix + a, iy + b), sw, StrokeCap.Round)
            }
        }
    }
}

/** A part of the header's car: words in a style and ink, or the link's dot in [dot]'s ink. */
private data class HeadPart(val text: String, val style: Style, val ink: Int, val dot: Int? = null)

/** The header's items right to left as the board orders them; an item not known is left out. */
private fun headerItems(head: Header): List<List<HeadPart>> {
    val link = ScannerWords.link(head.link)
    val linkWord = head.latency?.takeIf { head.link == LinkState.OK }
    return listOfNotNull(
        listOf(HeadPart(head.car, Type.META, Ink.MUTE)),
        head.odometer?.let { listOf(HeadPart(it, Type.META_MONO, Ink.DIM)) },
        head.mode?.let { listOf(HeadPart(it, Type.META, Ink.DIM)) },
        head.volts?.let { listOf(HeadPart("12 В", Type.META, Ink.MUTE), HeadPart(it, Type.META_MONO, if (head.voltsOut) Ink.ORANGE else Ink.DIM)) },
        listOf(
            HeadPart("", Type.META, Ink.DIM, dot = link.dot),
            if (linkWord != null) HeadPart(linkWord, Type.META_MONO, Ink.DIM) else HeadPart(link.text, Type.META, Ink.DIM),
        ),
        listOf(HeadPart(head.clock, Type.META_MONO, Ink.MUTE)),
    )
}

/* ------------------------------------------------------------------- head */

/** The widest a counter is: its 112 dp, or its widest value and a padding's room, rounded up to 8 dp (`counterWidth()`). */
@Composable
private fun counterWidth(counters: List<Counter>): Float {
    val b = ScannerSpec.Block
    val width = rememberWidths()
    val widest = counters.maxOfOrNull { c ->
        val (n, u) = ScannerWords.splitUnit(c.value)
        width(n, Type.COUNTER_BIG) + (if (u.isNotEmpty()) ScannerSpec.Header.VOLTS_GAP + width(u, Type.UNIT) else 0f) +
            (if (c.mark != null) SIGN_W else 0f)
    } ?: 0f
    return maxOf(b.COUNTER_W, ceil((widest + b.COUNTER_PAD) / b.COUNTER_STEP) * b.COUNTER_STEP)
}

/**
 * A block's or a system's head (`blockHead()`): the title - a word, or runs such as a code's letters
 * and its failure type - and its aside over the mono line, the counters at the right.
 */
@Composable
internal fun BlockHead(
    title: String,
    aside: String,
    line: String,
    counters: List<Counter> = emptyList(),
    titleInk: Int = Ink.WHITE,
    titleRuns: List<Triple<String, Style, Int>>? = null,
) {
    val b = ScannerSpec.Block
    val w = LocalScanWidth.current
    val lab = Type.COUNTER_LABEL
    val v = Type.COUNTER_BIG
    val width = rememberWidths()
    var right = w - 24f
    if (counters.isNotEmpty()) {
        val cw = counterWidth(counters)
        val top = b.TOP + (b.HEIGHT - lab.lh - b.COUNTER_GAP - v.lh) / 2f
        val box = cw + b.COUNTER_PAD + 1f
        right = w - 24f - counters.size * box
        counters.forEachIndexed { i, c ->
            val x = right + i * box
            VRule(x, top, lab.lh + b.COUNTER_GAP + v.lh)
            ScanText(c.label, lab, Ink.MUTE, Modifier.at(x + 1f + b.COUNTER_PAD, top))
            var vx = x + 1f + b.COUNTER_PAD
            val vt = top + lab.lh + b.COUNTER_GAP
            c.mark?.let { SignAt(it, vx + ScannerSpec.Sign.R, vt + v.lh / 2f); vx += SIGN_W }
            val (num, unit) = ScannerWords.splitUnit(c.value)
            val inkV = when {
                c.on -> Ink.RED
                c.out -> Ink.ORANGE
                else -> Ink.WHITE
            }
            ScanText(num, v, inkV, Modifier.at(vx, vt), glow = if (c.on) Ink.RED_GLOW else null, glowBlur = 10f)
            if (unit.isNotEmpty()) {
                ScanText(unit, Type.UNIT, if (c.out) Ink.ORANGE else Ink.DIM, Modifier.at(vx + width(num, v) + ScannerSpec.Header.VOLTS_GAP, vt), baseline = v.baseline)
            }
        }
    }
    val t = Type.TITLE
    val top = b.TOP + (b.HEIGHT - t.lh - b.TITLE_GAP - Type.LINE.lh) / 2f
    val room = right - 24f
    val runs = (titleRuns ?: listOf(Triple(title, t, titleInk))) + listOf(Triple(" ", t, Ink.WHITE), Triple(aside, Type.TITLE_ASIDE, Ink.MUTE))
    ScanRuns(runs, Modifier.at(24f, top), maxWidth = room, base = t)
    ScanText(line, Type.LINE, Ink.DIM2, Modifier.at(24f, top + t.lh + b.TITLE_GAP), maxWidth = room)
}

/* ------------------------------------------------------------------- tabs */

/** The view switch at the tabs' right end: its items and the one on. */
internal data class Segments(val items: List<String>, val active: Int, val onSelect: (Int) -> Unit)

/** The tab row with its rule; an [alert]ed tab's red dot; a [right] switch at the row's end. */
@Composable
internal fun ScanTabs(items: List<String>, active: Int, onSelect: (Int) -> Unit, alert: Int? = null, right: Segments? = null) {
    val t = ScannerSpec.Tabs
    val top = (t.HEIGHT - Type.TAB.lh) / 2f
    val width = rememberWidths()
    BoxWithConstraints(Modifier.at(0f, t.TOP).fillMaxWidth().height((t.HEIGHT + 1f).dp)) {
        val d = ScannerSpec.Header.DOT
        var x = 24f
        items.forEachIndexed { i, label ->
            val on = i == active
            val tw = width(label, Type.TAB)
            val w = tw + if (alert == i) t.DOT_GAP + d else 0f
            Box(
                Modifier.at(x, 0f).size(w.dp, t.HEIGHT.dp).tap { onSelect(i) }.drawBehind {
                    if (on) {
                        val u = t.UNDERLINE.dp.toPx()
                        drawRect(ink(Ink.BLUE), Offset(0f, size.height - u), Size(size.width, u))
                    }
                },
            )
            ScanText(label, Type.TAB, if (on) Ink.WHITE else Ink.MUTE, Modifier.at(x, top))
            if (alert == i) {
                Canvas(Modifier.at(x + tw + t.DOT_GAP, (t.HEIGHT - d) / 2f).size(d.dp)) {
                    val r = size.width / 2f
                    glowUnder(Ink.RED, 6f) { c, p -> c.drawCircle(r, r, r, p) }
                    drawCircle(ink(Ink.RED), r)
                }
            }
            x += w + t.GAP
        }
        Box(Modifier.at(24f, t.HEIGHT).size((maxWidth.value - 48f).dp, 1.dp).plate(Ink.RULE))
        if (right != null) {
            val g = ScannerSpec.Segmented
            val y = (t.HEIGHT - g.H) / 2f
            val ws = right.items.map { width(it, Type.SEGMENT) + 2f * g.PAD }
            var sx = maxWidth.value - 24f - ws.sum() - (ws.size - 1) * g.GAP
            right.items.forEachIndexed { i, s ->
                val on = i == right.active
                Box(Modifier.at(sx, y).size(ws[i].dp, g.H.dp).plate(if (on) Ink.QUIET else null, if (on) Ink.QUIET_LINE else null, g.R).tap { right.onSelect(i) })
                ScanText(s, Type.SEGMENT, if (on) Ink.WHITE else Ink.MUTE, Modifier.at(sx + g.PAD, y + (g.H - Type.SEGMENT.lh) / 2f))
                sx += ws[i] + g.GAP
            }
        }
    }
}

/* ----------------------------------------------------------------- footer */

/** The foot: buttons from the left; notes from the right, a divider between them. */
@Composable
internal fun ScanFooter(buttons: List<FooterButton>, notes: List<FooterNote>, noteInk: Int = Ink.FAINT) {
    val f = ScannerSpec.Footer
    val room = f.HEIGHT - 1f
    val width = rememberWidths()
    BoxWithConstraints(Modifier.fillMaxWidth().height(f.HEIGHT.dp).plate(Ink.BAR)) {
        Box(Modifier.fillMaxWidth().height(1.dp).plate(Ink.RULE))
        val top0 = 1f
        var x = 24f
        buttons.forEach { b ->
            val bw = width(b.label, Type.BUTTON) + 2f * f.BUTTON_PAD + 2f + if (b.record) SIGN_W else 0f
            ScanButton(b.label, b.primary, b.onClick, Modifier.at(x, top0 + (room - f.BUTTON_H) / 2f).width(bw.dp), b.record)
            x += bw + f.GAP
        }
        var r = maxWidth.value - 24f
        notes.asReversed().forEach { n ->
            when (n) {
                FooterNote.Divider -> {
                    r -= f.DIVIDER_W
                    Box(Modifier.at(r, top0 + (room - f.DIVIDER_H) / 2f).size(f.DIVIDER_W.dp, f.DIVIDER_H.dp).plate(Ink.DIVIDER))
                    r -= f.GAP
                }
                is FooterNote.Words -> {
                    val st = if (n.mono) Type.NOTE_MONO else Type.NOTE
                    r -= width(n.text, st)
                    ScanText(n.text, st, noteInk, Modifier.at(r, top0 + (room - st.lh) / 2f))
                    r -= f.GAP
                }
            }
        }
    }
}

/** A footer button: the stock tester's quiet plate, or the primary in the dock's blue; [record] its red dot first. */
@Composable
internal fun ScanButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, record: Boolean = false) {
    val f = ScannerSpec.Footer
    Box(
        modifier
            .height(f.BUTTON_H.dp)
            .plate(if (primary) Ink.PRIMARY_FILL else Ink.QUIET, if (primary) Ink.PRIMARY_EDGE else Ink.QUIET_LINE, f.BUTTON_R)
            .tap(onClick)
            // the label from the left edge only: the button is the words' unrounded width, and a
            // right padding as well would leave them a fraction of a pixel short and cut them
            .padding(start = (1f + f.BUTTON_PAD).dp),
    ) {
        if (record) SignAt(Mark.ACTIVE, ScannerSpec.Sign.R, f.BUTTON_H / 2f)
        ScanText(
            label, Type.BUTTON, if (primary) Ink.BLUE_INK else Ink.TEXT,
            Modifier.padding(start = (if (record) SIGN_W else 0f).dp, top = ((f.BUTTON_H - Type.BUTTON.lh) / 2f).dp),
        )
    }
}

/** A status chip (`badge()`). */
@Composable
internal fun Badge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val g = ScannerSpec.Badge
    // as wide as the board's: the words' unrounded width and the padding
    val w = rememberWidths()(text, Type.BADGE) + 2f * g.PAD
    Box(modifier.size(w.dp, g.H.dp).plate(tone.fill, tone.line, g.R).padding(start = g.PAD.dp)) {
        ScanText(text, Type.BADGE, tone.ink, Modifier.padding(top = ((g.H - Type.BADGE.lh) / 2f).dp))
    }
}

/** A row's chevron: the board's 24-unit path at [size]. */
@Composable
internal fun Chevron(modifier: Modifier = Modifier, size: Float = ScannerSpec.CHEVRON) {
    Canvas(modifier.size(size.dp)) {
        val k = this.size.width / 24f
        val c = ink(Ink.FAINT)
        val sw = 2f * k
        drawLine(c, Offset(9 * k, 5 * k), Offset(16 * k, 12 * k), sw, StrokeCap.Round)
        drawLine(c, Offset(16 * k, 12 * k), Offset(9 * k, 19 * k), sw, StrokeCap.Round)
    }
}

/** A chip of letters in a thin frame, ending at [xr] with its middle at [cy] (`tagChip()`): «СЕТЬ». */
@Composable
internal fun TagChip(text: String, xr: Float, cy: Float) {
    val g = ScannerSpec.Summary
    val w = rememberWidths()(text, Type.TAG) + 2f * g.TAG_PAD
    Box(Modifier.at(xr - w, cy - g.TAG_H / 2f).size(w.dp, g.TAG_H.dp).plate(null, Ink.LINE3, g.TAG_R))
    ScanText(text, Type.TAG, Ink.DIM, Modifier.at(xr - w + g.TAG_PAD, cy - Type.TAG.lh / 2f))
}

/**
 * A table's head row (`headRow()`): captions in [cols], a rule under it across them; [labels]
 * hold the caption, `end` for a right-aligned one and its padding.
 */
internal data class HeadLabel(val text: String, val end: Boolean = false, val pad: Float? = null, val centre: Boolean = false)

@Composable
internal fun HeadRow(cols: List<Pair<Float, Float>>, labels: List<HeadLabel?>, top: Float, h: Float, style: Style = Type.COL_HEAD, ruleW: Float? = null) {
    val y = top + (h - style.lh) / 2f
    labels.forEachIndexed { i, lab ->
        if (lab == null) return@forEachIndexed
        val (x, w) = cols[i]
        val pad = lab.pad ?: if (i == 0) 12f else 0f
        when {
            lab.end -> ScanText(lab.text, style, Ink.MUTE, Modifier.endAt(x + w - pad, y))
            lab.centre -> ScanText(lab.text, style, Ink.MUTE, Modifier.centreAt(x + w / 2f, y))
            else -> ScanText(lab.text, style, Ink.MUTE, Modifier.at(x + pad, y))
        }
    }
    val x0 = cols.first().first
    Rule(x0, top + h, ruleW ?: (cols.last().first + cols.last().second - x0))
}

/** The board's `mid()`: a line box of [style] centred in a row of [h] whose top is [top]. */
internal fun mid(top: Float, h: Float, style: Style): Float = top + (h - style.lh) / 2f
