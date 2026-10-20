package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Tone
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * The chrome every scanner screen shares - `scanner.js`'s `header`, `rail`, `blockHead`, `tabs`,
 * `footer`, `badge` and `button` - each a composable placed where the board places it.
 */

/**
 * How wide the screen's area is, dp: the window for a block's screen, what the rail leaves for the
 * autoscan and a system (the board's `W` inside `shell()`).
 */
internal val LocalScanWidth = androidx.compose.runtime.staticCompositionLocalOf { ScannerSpec.W }

internal data class Crumb(val text: String, val onClick: (() -> Unit)? = null)

/** A figure in a head: its caption, its value, white or the sentence ink, orange and glowing when on. */
internal data class Counter(val label: String, val value: String, val ink: Int = Ink.WHITE, val on: Boolean = false)

internal data class FooterButton(val label: String, val primary: Boolean, val onClick: () -> Unit)

internal sealed interface FooterNote {
    data class Words(val text: String, val mono: Boolean = false) : FooterNote
    data object Divider : FooterNote
}

internal enum class Swatch { OK, HIST, UNREAD, NONE, MECH }

internal enum class BadgeSize { SMALL, NORMAL, BIG }

/** The page: black, the footer at the foot, everything else placed from the top. */
@Composable
internal fun ScannerPage(footer: @Composable () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().plate(Ink.GROUND)) {
        content()
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { footer() }
    }
}

/** The bar: crumbs at the left; the link, the car, the clock and the close button at the right. */
@Composable
internal fun ScanHeader(crumbs: List<Crumb>, link: String, linkDot: Int, glowDot: Boolean, car: String, clock: String, onClose: () -> Unit) {
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
            var r = kx - h.GAP
            r -= width(clock, Type.META_MONO)
            ScanText(clock, Type.META_MONO, Ink.MUTE, Modifier.at(r, (room - Type.META_MONO.lh) / 2f))
            r -= h.GAP + width(car, Type.META)
            ScanText(car, Type.META, Ink.MUTE, Modifier.at(r, (room - Type.META.lh) / 2f))
            r -= h.GAP + width(link, Type.META)
            ScanText(link, Type.META, Ink.DIM, Modifier.at(r, (room - Type.META.lh) / 2f))
            r -= h.DOT_GAP + h.DOT
            Canvas(Modifier.at(r, (room - h.DOT) / 2f).size(h.DOT.dp)) {
                val rr = size.width / 2f
                if (glowDot) glowUnder(linkDot, h.DOT_GLOW) { c, p -> c.drawCircle(rr, rr, rr, p) }
                drawCircle(ink(linkDot), rr)
            }
            Canvas(
                Modifier.at(kx, (room - h.CLOSE_H) / 2f).size(h.CLOSE_W.dp, h.CLOSE_H.dp).tap(onClose),
            ) {
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

/**
 * A block's or a system's head: the title and its aside over the mono line, and at the right
 * either counters (an OBD responder, a system) or the status badge and the DTC count (a body block).
 */
@Composable
internal fun BlockHead(
    title: String,
    aside: String,
    line: String,
    counters: List<Counter> = emptyList(),
    badge: Pair<String, Tone>? = null,
    dtc: String? = null,
) {
    val b = ScannerSpec.Block
    val lab = Type.COUNTER_LABEL
    // a counter is 112 dp, or its widest value and a padding's room after it rounded up to 8 dp
    // (the board's counterWidth())
    val width = rememberWidths()
    val widest = counters.maxOfOrNull { width(it.value, Type.COUNTER) } ?: 0f
    val counterW = maxOf(b.COUNTER_W, kotlin.math.ceil((widest + b.COUNTER_PAD) / b.COUNTER_STEP) * b.COUNTER_STEP)
    Row(Modifier.at(0f, b.TOP).fillMaxWidth().height(b.HEIGHT.dp).padding(horizontal = 24.dp)) {
        val top = (b.HEIGHT - Type.TITLE.lh - b.TITLE_GAP - Type.LINE.lh) / 2f
        Column(Modifier.weight(1f).padding(top = top.dp)) {
            ScanRuns(listOf(Triple("$title ", Type.TITLE, Ink.WHITE), Triple(aside, Type.TITLE_ASIDE, Ink.MUTE)))
            Spacer(Modifier.height(b.TITLE_GAP.dp))
            ScanText(line, Type.LINE, Ink.DIM2)
        }
        if (counters.isNotEmpty()) {
            val ctop = (b.HEIGHT - lab.lh - b.COUNTER_GAP - Type.COUNTER.lh) / 2f
            counters.forEach { c ->
                Box(Modifier.width((counterW + b.COUNTER_PAD + 1f).dp).fillMaxHeight()) {
                    VRule(0f, ctop, lab.lh + b.COUNTER_GAP + Type.COUNTER.lh)
                    ScanText(c.label, lab, Ink.MUTE, Modifier.at(1f + b.COUNTER_PAD, ctop))
                    ScanText(
                        c.value, Type.COUNTER, if (c.on) Ink.ORANGE_INK else Ink.WHITE,
                        Modifier.at(1f + b.COUNTER_PAD, ctop + lab.lh + b.COUNTER_GAP),
                        glow = if (c.on) Ink.COUNTER_GLOW else null, glowBlur = 10f,
                    )
                }
            }
        }
        if (badge != null && dtc != null) {
            Spacer(Modifier.width(b.BADGE_GAP.dp))
            Badge(badge.first, badge.second, BadgeSize.BIG, Modifier.padding(top = ((b.HEIGHT - ScannerSpec.Badge.BIG_H) / 2f).dp))
            Spacer(Modifier.width(b.BADGE_GAP.dp))
            val dtop = (b.HEIGHT - lab.lh - 2f - Type.COUNTER_BIG.lh) / 2f
            Column(Modifier.padding(top = dtop.dp), horizontalAlignment = Alignment.End) {
                ScanText("DTC", lab, Ink.MUTE)
                Spacer(Modifier.height(2.dp))
                ScanText(dtc, Type.COUNTER_BIG, Ink.WHITE)
            }
        }
    }
}

/** The tab row with its rule. */
@Composable
internal fun ScanTabs(items: List<String>, active: Int, onSelect: (Int) -> Unit, alert: Int? = null) {
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
                    glowUnder(Ink.ORANGE, 6f) { c, p -> c.drawCircle(r, r, r, p) }
                    drawCircle(ink(Ink.ORANGE), r)
                }
            }
            x += w + t.GAP
        }
        Box(Modifier.at(24f, t.HEIGHT).size((maxWidth.value - 48f).dp, 1.dp).plate(Ink.RULE))
    }
}

/** The foot: buttons from the left, then a legend; notes from the right, a divider between them. */
@Composable
internal fun ScanFooter(
    buttons: List<FooterButton>,
    notes: List<FooterNote>,
    legend: List<Pair<Swatch, String>> = emptyList(),
    legendGap: Float = ScannerSpec.Footer.GAP,
    noteInk: Int = Ink.FAINT,
) {
    val f = ScannerSpec.Footer
    val room = f.HEIGHT - 1f
    val width = rememberWidths()
    BoxWithConstraints(Modifier.fillMaxWidth().height(f.HEIGHT.dp).plate(Ink.BAR)) {
        Box(Modifier.fillMaxWidth().height(1.dp).plate(Ink.RULE))
        val top0 = 1f
        var x = 24f
        buttons.forEach { b ->
            val bw = width(b.label, Type.BUTTON) + 2f * f.BUTTON_PAD + 2f
            ScanButton(b.label, b.primary, b.onClick, Modifier.at(x, top0 + (room - f.BUTTON_H) / 2f).width(bw.dp))
            x += bw + f.GAP
        }
        if (legend.isNotEmpty()) x += legendGap - f.GAP
        legend.forEach { (kind, label) ->
            LegendSwatch(kind, Modifier.at(x, top0 + (room - f.LEGEND_H) / 2f))
            x += f.LEGEND_W + f.LEGEND_GAP
            ScanText(label, Type.NOTE, Ink.DIM2, Modifier.at(x, top0 + (room - Type.NOTE.lh) / 2f))
            x += width(label, Type.NOTE) + legendGap
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

/** A footer button: the stock tester's quiet plate, or the primary in the dock's blue. */
@Composable
internal fun ScanButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        ScanText(label, Type.BUTTON, if (primary) Ink.BLUE_INK else Ink.TEXT, Modifier.padding(top = ((f.BUTTON_H - Type.BUTTON.lh) / 2f).dp))
    }
}

/** A status chip (`badge()`). */
@Composable
internal fun Badge(text: String, tone: Tone, size: BadgeSize = BadgeSize.NORMAL, modifier: Modifier = Modifier) {
    val g = ScannerSpec.Badge
    val (h, pad, style) = when (size) {
        BadgeSize.SMALL -> Triple(g.SMALL_H, g.SMALL_PAD, Type.BADGE_SMALL)
        BadgeSize.NORMAL -> Triple(g.H, g.PAD, Type.BADGE)
        BadgeSize.BIG -> Triple(g.BIG_H, g.BIG_PAD, Type.BADGE_BIG)
    }
    // as wide as the board's: the words' unrounded width and the padding
    val w = rememberWidths()(text, style) + 2f * pad
    Box(modifier.size(w.dp, h.dp).plate(tone.fill, tone.line, g.R).padding(start = pad.dp)) {
        ScanText(text, style, tone.ink, Modifier.padding(top = ((h - style.lh) / 2f).dp))
    }
}

/** A legend's swatch: a tile's look in 16 x 10. */
@Composable
internal fun LegendSwatch(kind: Swatch, modifier: Modifier = Modifier) {
    val f = ScannerSpec.Footer
    Canvas(modifier.size(f.LEGEND_W.dp, f.LEGEND_H.dp)) {
        val r = f.LEGEND_R.dp.toPx()
        fun tile(fill: Int, line: Int) {
            drawRoundRect(ink(fill), cornerRadius = CornerRadius(r, r))
            edge(line, f.LEGEND_R)
        }
        when (kind) {
            Swatch.OK -> tile(Ink.TILE_OK, Ink.BLUE_EDGE)
            Swatch.HIST -> tile(Ink.TILE_HIST, Ink.SEP)
            Swatch.UNREAD -> tile(Ink.TILE_UNREAD, Ink.LINE3)
            Swatch.NONE -> edge(Ink.LINE4, f.LEGEND_R, dash = true)
            Swatch.MECH -> {
                val bar = 1.4.dp.toPx()
                drawRect(ink(Ink.DIM2), Offset(0f, 2.dp.toPx()), Size(size.width, bar))
                drawRect(ink(Ink.DIM2), Offset(0f, 6.6.dp.toPx()), Size(size.width, bar))
            }
        }
    }
}

/** A row's chevron: the board's 24-unit path at [size]. */
@Composable
internal fun Chevron(modifier: Modifier = Modifier, size: Float = ScannerSpec.Scan.CHEVRON) {
    Canvas(modifier.size(size.dp)) {
        val k = this.size.width / 24f
        val c = ink(Ink.FAINT)
        val sw = 2f * k
        drawLine(c, Offset(9 * k, 5 * k), Offset(16 * k, 12 * k), sw, StrokeCap.Round)
        drawLine(c, Offset(16 * k, 12 * k), Offset(9 * k, 19 * k), sw, StrokeCap.Round)
    }
}

/**
 * A table's head row (`headRow()`): captions in [cols], a rule under it across them; [labels]
 * hold the caption, `end` for a right-aligned one and its padding.
 */
internal data class HeadLabel(val text: String, val end: Boolean = false, val pad: Float? = null, val centre: Boolean = false)

@Composable
internal fun HeadRow(cols: List<Pair<Float, Float>>, labels: List<HeadLabel?>, top: Float, h: Float, style: ScannerSpec.Style = Type.COL_HEAD) {
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
    Rule(x0, top + h, cols.last().first + cols.last().second - x0)
}

/** The board's `mid()`: a line box of [style] centred in a row of [h] whose top is [top]. */
internal fun mid(top: Float, h: Float, style: ScannerSpec.Style): Float = top + (h - style.lh) / 2f
