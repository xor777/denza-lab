package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.CabinPlace
import dev.denza.apps.feature.diagnostics.scan.CabinPlate
import dev.denza.apps.feature.diagnostics.scan.CabinRow
import dev.denza.apps.feature.diagnostics.scan.CabinSeat
import dev.denza.apps.feature.diagnostics.scan.CabinTone
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.Corner
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.EnginePlace
import dev.denza.apps.feature.diagnostics.scan.EpbLook
import dev.denza.apps.feature.diagnostics.scan.SeatBelt
import dev.denza.apps.feature.diagnostics.scan.FridgeMode
import dev.denza.apps.feature.diagnostics.scan.HvUnit
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.PadState
import dev.denza.apps.feature.diagnostics.scan.RangeView
import dev.denza.apps.feature.diagnostics.scan.Scheme
import dev.denza.apps.feature.diagnostics.scan.Target
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Style
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type
import kotlin.math.cos
import kotlin.math.sin

/*
 * A system's «Состояние»: the mnemonic schemes of `scanner.js` - the car from above (`car()`) with
 * callouts (`callout()`) and the blocks' tiles (`unitTile()`, `hang()`) for Шасси, Кузов,
 * Ассистенты, Электропривод and Безопасность; the cabin through the roof (`sysCabin()`); the HV schema (`hvBody()`); the pack (`sysPack()`, `rangeBar()`); the engine's air
 * path; climate; the 12 V net. The car's readings stand in their words (`said()`) and as light on
 * the line art: lamps, lids, calipers, seats. Line art is drawn on one canvas in window dp, words and
 * tiles are composables over it, so a tile still opens its block.
 */

@Composable
internal fun SchemeContent(model: DiagnosticsModel, scheme: Scheme, onOpen: (Target) -> Unit) {
    when (scheme) {
        is Scheme.Hv -> HvScheme(model, scheme, onOpen)
        // the pack's first tab is «Здоровье» since 7.3 (HealthScreen.kt), not a scheme
        is Scheme.Pack -> Unit
        is Scheme.Drives -> DrivesScheme(scheme, onOpen)
        is Scheme.Engine -> EngineScheme(scheme)
        is Scheme.Chassis -> ChassisScheme(scheme, onOpen)
        is Scheme.Body -> BodyScheme(scheme, onOpen)
        is Scheme.Adas -> AdasScheme(scheme, onOpen)
        is Scheme.Climate -> ClimateScheme(scheme, onOpen)
        is Scheme.Lv -> LvScheme(scheme, onOpen)
        is Scheme.Safety -> SafetyScheme(scheme, onOpen)
        is Scheme.Cabin -> CabinScheme(scheme)
    }
}

/* --------------------------------------------------------------- drawing */

/** Draws in window dp: the board's units. */
@Composable
private fun SchemeCanvas(draw: DrawScope.() -> Unit) {
    Canvas(Modifier.fillMaxSize()) {
        val k = density
        withTransform({ scale(k, k, Offset.Zero) }) { draw() }
    }
}

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

private fun DrawScope.wire(d: String, color: Int, width: Float, dash: FloatArray? = null, round: Boolean = false) =
    wire(path(d), color, width, dash, round)

private fun DrawScope.wire(p: Path, color: Int, width: Float, dash: FloatArray? = null, round: Boolean = false) {
    drawPath(
        p, ink(color),
        style = Stroke(
            width,
            cap = if (round) StrokeCap.Round else StrokeCap.Butt,
            join = if (round) StrokeJoin.Round else StrokeJoin.Miter,
            pathEffect = dash?.let { PathEffect.dashPathEffect(it) },
        ),
    )
}

/** The board's `fill()`. */
private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, color: Int, r: Float = 0f) =
    drawRoundRect(ink(color), Offset(x, y), Size(w, h), CornerRadius(r, r))

/** The board's `edge()`: a 1 dp line on the box's inner edge. */
private fun DrawScope.frame(x: Float, y: Float, w: Float, h: Float, color: Int, r: Float = 0f, dash: Boolean = false) {
    val rr = (r - 0.5f).coerceAtLeast(0f)
    drawRoundRect(
        ink(color), Offset(x + 0.5f, y + 0.5f), Size(w - 1f, h - 1f), CornerRadius(rr, rr),
        style = Stroke(1f, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) else null),
    )
}

private fun DrawScope.dot(x: Float, y: Float, r: Float, color: Int) = drawCircle(ink(color), r, Offset(x, y))

/** A flow arrow between two points, its head at the end (`flow()`). */
private fun DrawScope.flow(x1: Float, y1: Float, x2: Float, y2: Float) {
    val a = kotlin.math.atan2(y2 - y1, x2 - x1)
    val h = 8f
    wire(
        "M$x1 ${y1}L$x2 ${y2}M${x2 - h * cos(a - 0.45f)} ${y2 - h * sin(a - 0.45f)}L$x2 ${y2}L${x2 - h * cos(a + 0.45f)} ${y2 - h * sin(a + 0.45f)}",
        Ink.WIRE4, 1.6f,
    )
}

/* ------------------------------------------------------- the car from above */

/** Where the schemes hang things on the car: its box, the axles, the wheels' outer edges. */
private class CarGeom(val cx: Float, val y0: Float, val h: Float) {
    val w = ScannerSpec.Car.W
    val x0 = cx - w / 2f
    val front = y0 + ScannerSpec.Car.AXLE
    val rear = y0 + h - ScannerSpec.Car.AXLE
    val left = x0 - ScannerSpec.Car.WHEEL_W / 2f
    val right = x0 + w + ScannerSpec.Car.WHEEL_W / 2f
    val mid = y0 + h / 2f
}

/** The body from above, front up, in lines: outline, glass, mirrors and wheels (`car()`); [inside] leaves out the roof. */
private fun DrawScope.car(k: CarGeom, inside: Boolean = false) {
    val c = ScannerSpec.Car
    val body = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(k.x0, k.y0, k.x0 + k.w, k.y0 + k.h),
                CornerRadius(c.NOSE_R), CornerRadius(c.NOSE_R), CornerRadius(c.TAIL_R), CornerRadius(c.TAIL_R),
            ),
        )
    }
    drawPath(body, ink(Ink.PLATE))
    drawPath(body, ink(Ink.WIRE3), style = Stroke(1.6f))
    val ws = k.y0 + c.SHIELD
    val rw = k.y0 + k.h - c.REAR
    val a = k.x0 + c.GLASS_INSET
    val b = k.x0 + k.w - c.GLASS_INSET
    wire("M$a ${ws + 10}Q${k.cx} ${ws - 14} $b ${ws + 10}", Ink.WIRE3, 1.4f)
    wire("M$a ${rw - 8}Q${k.cx} ${rw + 12} $b ${rw - 8}", Ink.WIRE3, 1.4f)
    // the roof; a scheme of the inside draws the cabin in its place
    if (!inside) frame(k.x0 + c.ROOF_INSET, ws + 18, k.w - 2 * c.ROOF_INSET, rw - ws - 34, Ink.LINE3, 14f)
    val mw = c.MIRROR_W
    val mh = c.MIRROR_H
    wire("M${k.x0} ${ws + 4}l${-mw} ${-mh / 2}v${-mh}l$mw ${mh / 2}", Ink.WIRE3, 1.4f)
    wire("M${k.x0 + k.w} ${ws + 4}l$mw ${-mh / 2}v${-mh}l${-mw} ${mh / 2}", Ink.WIRE3, 1.4f)
    listOf(k.x0 to k.front, k.x0 + k.w to k.front, k.x0 to k.rear, k.x0 + k.w to k.rear).forEach { (x, y) ->
        box(x - c.WHEEL_W / 2, y - c.WHEEL_H / 2, c.WHEEL_W, c.WHEEL_H, Ink.PID_FILL, 4f)
        frame(x - c.WHEEL_W / 2, y - c.WHEEL_H / 2, c.WHEEL_W, c.WHEEL_H, Ink.LINE4, 4f)
    }
}

/** A machine: a circle with its letter, dashed where it stands still (`machine()`). */
private fun DrawScope.machine(x: Float, y: Float, live: Boolean, r: Float = ScannerSpec.Callout.MACHINE_R) {
    drawCircle(ink(Ink.GROUND), r, Offset(x, y))
    drawCircle(
        ink(if (live) Ink.WIRE5 else Ink.WIRE4), r, Offset(x, y),
        style = Stroke(1.6f, pathEffect = if (live) null else PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
    )
}

@Composable
private fun MachineGlyph(x: Float, y: Float, glyph: String, live: Boolean) {
    ScanText(glyph, Type.GLYPH, if (live) Ink.TEXT else Ink.DIM, Modifier.centreAt(x, y + 5f - Type.GLYPH.baseline))
}

/* ------------------------------------------------------------- callouts */

/** A line of a callout: words in one style, or runs on one baseline. */
private sealed interface Line {
    val style: Style
}

private data class Words(val text: String, override val style: Style, val ink: Int) : Line

private data class Runs(val runs: List<Triple<String, Style, Int>>) : Line {
    override val style: Style get() = runs.first().second
}

private fun calloutH(lines: List<Line>): Float {
    val o = ScannerSpec.Callout
    return 2f * o.PAD_Y + lines.sumOf { it.style.lh.toDouble() }.toFloat() + (lines.size - 1) * o.GAP
}

/** A callout's leader: from the plate's near side to a point on the car, a dot at the end; bent half way, or at [bend]. */
private fun DrawScope.leader(x: Float, y: Float, w: Float, h: Float, tx: Float, ty: Float, bend: Float? = null) {
    val sx = if (tx < x) x else x + w
    val sy = y + h / 2f
    wire(if (ty == sy) "M$sx ${sy}H$tx" else "M$sx ${sy}H${bend ?: ((sx + tx) / 2f)}V${ty}H$tx", Ink.WIRE4, 1.2f)
    dot(tx, ty, ScannerSpec.Callout.DOT, Ink.WIRE5)
}

/** A plate of lines (`callout()`): the OK tile's fill and edge. */
@Composable
private fun Plate(x: Float, y: Float, w: Float, lines: List<Line>) {
    val o = ScannerSpec.Callout
    Box(Modifier.at(x, y).size(w.dp, calloutH(lines).dp).plate(Ink.GROUND, Ink.QUIET_LINE, o.R)) {
        var ty = o.PAD_Y
        lines.forEach { l ->
            when (l) {
                is Words -> ScanText(l.text, l.style, l.ink, Modifier.at(o.PAD_X, ty), maxWidth = w - 2f * o.PAD_X)
                is Runs -> ScanRuns(l.runs, Modifier.at(o.PAD_X, ty), maxWidth = w - 2f * o.PAD_X)
            }
            ty += l.style.lh + o.GAP
        }
    }
}

/** A row of words in the plates' style: the first-row style unless [CabinRow.small], each run in its tone's ink. */
private fun rowLine(r: CabinRow): Line {
    val style = if (r.small) Type.CELL_SMALL else Type.CELL
    return if (r.runs.size == 1) Words(r.runs[0].text, style, cabinInk(r.runs[0].tone, r.small))
    else Runs(r.runs.map { Triple(it.text, style, cabinInk(it.tone, r.small)) })
}

/** A plate of words: its label over its rows. */
private fun plateLines(label: String, rows: List<CabinRow>): List<Line> = listOf<Line>(Words(label, Type.COUNTER_LABEL, Ink.MUTE)) + rows.map(::rowLine)

/** Where a row of words stands on its x (`said()`): from it, centred on it, ending at it. */
private enum class Align { START, CENTRE, END }

/** A row of words with its line box's top at [y] (`said()`). */
@Composable
private fun SaidRow(r: CabinRow, x: Float, y: Float, align: Align = Align.START) {
    val m = when (align) {
        Align.START -> Modifier.at(x, y)
        Align.CENTRE -> Modifier.centreAt(x, y)
        Align.END -> Modifier.endAt(x, y)
    }
    when (val l = rowLine(r)) {
        is Words -> ScanText(l.text, l.style, l.ink, m)
        is Runs -> ScanRuns(l.runs, m)
    }
}

/** A scheme's tile: the topology's full size; nothing where the catalog has no such block. */
@Composable
private fun SchemeTile(row: ModuleRowView?, x: Float, y: Float, onOpen: (Target) -> Unit) {
    if (row != null) Tile(row, x, y, onOpen)
}

private val TILE_W = ScannerSpec.Tile.W
private val TILE_H = ScannerSpec.Tile.H

/** A tile hung on the car (`hang()`): where it stands and the point on the car its leader ends at. */
private data class Hung(val row: ModuleRowView?, val x: Float, val y: Float, val to: Offset)

/** [h]'s leader from the tile's near side: straight when the point is level with the tile's middle, bent half way otherwise. */
private fun DrawScope.hangLeader(h: Hung, cx: Float) {
    if (h.row == null) return
    val near = if (h.x < cx) h.x + TILE_W else h.x
    val my = h.y + TILE_H / 2f
    wire(if (h.to.y == my) "M$near ${my}H${h.to.x}" else "M$near ${my}H${(near + h.to.x) / 2f}V${h.to.y}H${h.to.x}", Ink.WIRE4, 1.2f)
    dot(h.to.x, h.to.y, ScannerSpec.Callout.DOT, Ink.WIRE5)
}

/* ---------------------------------------------------------------- chassis */

/**
 * Шасси: the tyres at their corners with the TPMS's judgement of each, the brake and steering blocks
 * where they sit, the rear wheels' steering under their tyres, the motion controller in the middle
 * with the suspension's plate, the parking brake's calipers in the rear wheels, the tyre-pressure
 * system quiet at the top right (`sysChassis()`).
 */
@Composable
private fun ChassisScheme(s: Scheme.Chassis, onOpen: (Target) -> Unit) {
    val o = ScannerSpec.Callout
    val w = LocalScanWidth.current
    val cx = w / 2f
    val k = CarGeom(cx, ScannerSpec.Car.TOP, ScannerSpec.Car.H)
    val lx = o.LEFT_X
    val rx = w - o.LEFT_X - o.W
    val plates = s.tyres.map { t ->
        val left = t.corner == Corner.FL || t.corner == Corner.RL
        val y = if (t.corner == Corner.FL || t.corner == Corner.FR) k.front else k.rear
        val lines = listOf(
            Words(t.label, Type.COUNTER_LABEL, Ink.MUTE),
            Runs(listOf(Triple(t.pressure, Type.COUNTER, Ink.WHITE), Triple(" бар", Type.UNIT, Ink.DIM2))),
            Words(t.temp, Type.CELL_MONO, Ink.DIM),
        ) + t.states.map(::rowLine)
        val h = calloutH(lines)
        Triple(Offset(if (left) lx else rx, y - h / 2f), lines, Offset(if (left) k.left else k.right, y))
    }
    // the suspension's plate between the left tyres, its leader to the motion controller
    val suspension = s.suspension?.let { plateLines(it.label, it.rows) }
    val top = ScannerSpec.Content.TOP
    val cs = ScannerSpec.ChassisScheme
    val steer = listOf(
        Hung(s.rearSteerLeft, lx, k.rear + cs.STEER_DROP, Offset(k.x0, k.rear + cs.STEER_AT)),
        Hung(s.rearSteerRight, w - lx - TILE_W, k.rear + cs.STEER_DROP, Offset(k.x0 + k.w, k.rear + cs.STEER_AT)),
    )
    SchemeCanvas {
        car(k)
        wire("M${k.x0} ${k.front}H${k.x0 + k.w}M${k.x0} ${k.rear}H${k.x0 + k.w}", Ink.WIRE3, 1.4f)
        plates.forEach { (at, lines, to) -> leader(at.x, at.y, o.W, calloutH(lines), to.x, to.y) }
        wire("M${k.x0 + 20} ${k.front}H${k.x0 + k.w - 20}", Ink.WIRE2, 3f)
        steer.forEach { hangLeader(it, cx) }
        suspension?.let { leader(lx, k.mid - calloutH(it) / 2f, o.W, calloutH(it), cx - TILE_W / 2, k.mid) }
        // the parking brake: a caliper inside each rear wheel - blue holding, orange at fault
        s.epb?.look?.let { look ->
            listOf(k.x0 + cs.CALIPER_IN, k.x0 + k.w - cs.CALIPER_IN - cs.CALIPER_W).forEach { x ->
                val y = k.rear - cs.CALIPER_H / 2
                when (look) {
                    EpbLook.HELD, EpbLook.FAULT ->
                        box(x, y, cs.CALIPER_W, cs.CALIPER_H, if (look == EpbLook.HELD) Ink.TEXT else Ink.ORANGE, cs.CALIPER_R)
                    EpbLook.FREE -> frame(x, y, cs.CALIPER_W, cs.CALIPER_H, Ink.WIRE3, cs.CALIPER_R)
                }
            }
        }
    }
    plates.forEach { (at, lines, _) -> Plate(at.x, at.y, o.W, lines) }
    SchemeTile(s.esp, cx - TILE_W / 2, k.y0 + 22f, onOpen)
    SchemeTile(s.eps, cx - TILE_W / 2, k.front - TILE_H / 2, onOpen)
    // the brake system's OBD interface above the left tyres, on its own: no common hardware or
    // software identity with ESP/IPB 782 is known, so no wire joins them
    SchemeTile(s.bscm, lx, top, onOpen)
    // across from it, quiet: the tyre-pressure system's state and its sensors' batteries
    s.tpms?.let { t ->
        ScanText(t.label, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(rx, top))
        t.rows.firstOrNull()?.let { SaidRow(it, rx, top + Type.COUNTER_LABEL.lh + o.GAP) }
    }
    steer.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
    SchemeTile(s.motion, cx - TILE_W / 2, k.mid - TILE_H / 2, onOpen)
    suspension?.let { Plate(lx, k.mid - calloutH(it) / 2f, o.W, it) }
    s.epb?.let { SaidRow(it.row, cx, k.rear + cs.EPB_Y, Align.CENTRE) }
}

/* ------------------------------------------------------------------- body */

/**
 * Кузов: doors, locks and windows on the body; zones and lamps at the edges; the key - its RF
 * receiver, UWB with its four anchors, keyless entry - in the middle; each door's radar and the
 * right NFC reader in the gaps between the plates and the car; the tailgate's projection at the
 * tail (`sysBodyState()`).
 */
@Composable
private fun BodyScheme(s: Scheme.Body, onOpen: (Target) -> Unit) {
    val o = ScannerSpec.Callout
    val c = ScannerSpec.Car
    val b = ScannerSpec.BodyScheme
    val w = LocalScanWidth.current
    val cx = w / 2f
    val k = CarGeom(cx, c.TOP, c.H)
    val lx = b.X
    val rx = w - b.X - o.W
    data class DoorPlate(val x: Float, val y: Float, val lines: List<Line>, val side: Float, val a: Float, val b: Float, val open: Boolean)
    val doors = s.doors.map { d ->
        val left = d.corner == Corner.FL || d.corner == Corner.RL
        val (a, e) = if (d.corner == Corner.FL || d.corner == Corner.FR) c.DOORS_FRONT else c.DOORS_REAR
        val lines = listOf(Words(d.label, Type.COUNTER_LABEL, Ink.MUTE), Words(d.state, Type.CELL, Ink.WHITE), Words(d.detail, Type.CELL_SMALL, Ink.DIM))
        DoorPlate(if (left) lx else rx, k.y0 + (a + e) / 2f - calloutH(lines) / 2f, lines, if (left) k.x0 else k.x0 + k.w, a, e, d.open)
    }
    val (ax, ay) = c.ANCHORS
    val anchors = listOf(
        Offset(k.x0 + ax, k.y0 + ay), Offset(k.x0 + k.w - ax, k.y0 + ay),
        Offset(k.x0 + ax, k.y0 + k.h - ay), Offset(k.x0 + k.w - ax, k.y0 + k.h - ay),
    )
    // the left and right body domains and the headlamps at the edges
    data class Side(val row: ModuleRowView?, val x: Float, val y: Float, val to: Offset)
    val tr = w - b.X - TILE_W
    val (lampX, lampY) = b.LAMP_AT
    val sides = listOf(
        Side(s.lampLeft, lx, ScannerSpec.Content.TOP, Offset(k.x0 + lampX, k.y0 + lampY)),
        Side(s.lampRight, tr, ScannerSpec.Content.TOP, Offset(k.x0 + k.w - lampX, k.y0 + lampY)),
        Side(s.left, lx, k.y0 + b.DOMAIN_TOP, Offset(k.x0, k.y0 + b.DOMAIN_TOP + TILE_H / 2)),
        Side(s.right, tr, k.y0 + b.DOMAIN_TOP, Offset(k.x0 + k.w, k.y0 + b.DOMAIN_TOP + TILE_H / 2)),
    )
    // the gaps between the plates and the car: each door's radar - the front ones over the door's
    // leader, the rear ones under it - and the NFC reader at the B-pillar; the tailgate's projection
    // level with its tile, on the rear corner's curve
    val gl = (lx + o.W + k.x0) / 2f - TILE_W / 2f
    val gr = (k.x0 + k.w + rx) / 2f - TILE_W / 2f
    val r = s.doorRadars
    val pillar = k.y0 + (c.DOORS_FRONT.second + c.DOORS_REAR.first) / 2f
    val tail = k.y0 + b.TAIL_Y
    val dy = tail - (k.y0 + k.h - c.TAIL_R)
    val hung = listOf(
        Hung(r[Corner.FL], gl, k.y0 + b.RADAR_FRONT - TILE_H / 2, Offset(k.x0, k.y0 + b.RADAR_FRONT)),
        Hung(r[Corner.FR], gr, k.y0 + b.RADAR_FRONT - TILE_H / 2, Offset(k.x0 + k.w, k.y0 + b.RADAR_FRONT)),
        Hung(r[Corner.RL], gl, k.y0 + b.RADAR_REAR - TILE_H / 2, Offset(k.x0, k.y0 + b.RADAR_REAR_AT)),
        Hung(r[Corner.RR], gr, k.y0 + b.RADAR_REAR - TILE_H / 2, Offset(k.x0 + k.w, k.y0 + b.RADAR_REAR_AT)),
        Hung(s.nfc, gr, pillar - TILE_H / 2, Offset(k.x0 + k.w, pillar)),
        Hung(s.tailgate, gr, tail - TILE_H / 2, Offset(k.x0 + k.w - c.TAIL_R + kotlin.math.sqrt(c.TAIL_R * c.TAIL_R - dy * dy), tail)),
    )
    // «СВЕТ» under the left headlamp's tile, its leader to that lamp; «ЗАДНИЕ ФОНАРИ» under the left
    // zone, its leader to the left stop lamp
    val lights = s.lights?.let { plateLines(it.label, it.rows) }
    val tailPlate = s.tail?.let { plateLines(it.label, it.rows) }
    val stopAt = run {
        val a = Math.toRadians(((b.LAMP_STOP.first + b.LAMP_STOP.second) / 2f).toDouble())
        Offset(k.x0 + c.TAIL_R + c.TAIL_R * cos(a).toFloat(), k.y0 + k.h - c.TAIL_R + c.TAIL_R * sin(a).toFloat())
    }
    SchemeCanvas {
        car(k)
        doors.forEach { d ->
            wire("M${d.side} ${k.y0 + d.a}V${k.y0 + d.b}", if (d.open) Ink.ORANGE else Ink.TEXT, 3f)
            leader(d.x, d.y, o.W, calloutH(d.lines), d.side, k.y0 + (d.a + d.b) / 2f)
        }
        bodyLights(k, s)
        anchors.forEachIndexed { i, p ->
            wire("M${p.x} ${p.y}L$cx ${k.mid}", Ink.LINE3, 1f, floatArrayOf(3f, 3f))
            val st = s.anchors.getOrNull(i)
            dot(p.x, p.y, 4f, if (st == ModuleStatus.NO_ROUTE || st == ModuleStatus.NO_REPLY) Ink.LINE4 else Ink.WIRE5)
        }
        sides.forEach { sd ->
            if (sd.row == null) return@forEach
            wire("M${if (sd.x < cx) sd.x + TILE_W else sd.x} ${sd.y + TILE_H / 2}H${sd.to.x}V${sd.to.y}", Ink.WIRE4, 1.2f)
            dot(sd.to.x, sd.to.y, o.DOT, Ink.WIRE5)
        }
        hung.forEach { hangLeader(it, cx) }
        lights?.let { leader(lx, k.y0 + b.LIGHTS - calloutH(it) / 2f, o.W, calloutH(it), k.x0 + lampX, k.y0 + lampY) }
        tailPlate?.let { leader(lx, k.y0 + b.TAIL_PLATE_Y - calloutH(it) / 2f, b.TAIL_PLATE_W, calloutH(it), stopAt.x, stopAt.y) }
    }
    doors.forEach { Plate(it.x, it.y, o.W, it.lines) }
    val uy = k.mid - TILE_H / 2
    SchemeTile(s.keyReceiver, cx - TILE_W / 2, uy - ScannerSpec.Tile.PITCH, onOpen)
    SchemeTile(s.uwb, cx - TILE_W / 2, uy, onOpen)
    SchemeTile(s.smartEntry, cx - TILE_W / 2, uy + ScannerSpec.Tile.PITCH, onOpen)
    SchemeTile(s.front, cx - TILE_W / 2, k.y0 + 22f, onOpen)
    SchemeTile(s.back, cx - TILE_W / 2, k.y0 + k.h - 22f - TILE_H, onOpen)
    // the hood's word under the front zone, the tailgate's over the rear one
    s.hood?.let { SaidRow(it.row, cx, k.y0 + b.WORDS.first, Align.CENTRE) }
    s.trunk?.let { SaidRow(it.row, cx, k.y0 + b.WORDS.second, Align.CENTRE) }
    lights?.let { Plate(lx, k.y0 + b.LIGHTS - calloutH(it) / 2f, o.W, it) }
    tailPlate?.let { Plate(lx, k.y0 + b.TAIL_PLATE_Y - calloutH(it) / 2f, b.TAIL_PLATE_W, it) }
    sides.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
    hung.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
}

/** The arc of the circle at [x], [y] of radius [r] from [a0] to [a1] degrees, clockwise on the screen (`arcPath()`). */
private fun arcPath(x: Float, y: Float, r: Float, a0: Float, a1: Float): Path =
    Path().apply { arcTo(Rect(x - r, y - r, x + r, y + r), a0, a1 - a0, true) }

/** A lamp on the body (`lamp()`): lit in its ink over a halo of it, dark in line4. */
private fun DrawScope.lamp(p: Path, on: Boolean, color: Int) {
    val b = ScannerSpec.BodyScheme
    if (on) drawPath(p, ink(color), alpha = b.HALO_ALPHA, style = Stroke(b.LAMP_HALO, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(p, ink(if (on) color else Ink.LINE4), style = Stroke(b.LAMP_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The body's lids and lamps (`sysBodyState()`): the hood on the nose's line and the tailgate on the
 * tail's - closed blue, open orange, not read dark; the headlamps at the nose's corners, blue on a
 * beam and haloed on the high one; at the tail's corners the stop lamps, orange, and the reversing
 * lamps, blue; the indicators as marks off the four corners, orange on the side that flashes.
 */
private fun DrawScope.bodyLights(k: CarGeom, s: Scheme.Body) {
    val b = ScannerSpec.BodyScheme
    val nr = ScannerSpec.Car.NOSE_R
    val tr = ScannerSpec.Car.TAIL_R
    fun lid(open: Boolean?) = when (open) { true -> Ink.ORANGE; false -> Ink.TEXT; null -> Ink.LINE4 }
    s.hood?.let { h ->
        val p = arcPath(k.x0 + nr, k.y0 + nr, nr, 270f - b.HOOD, 270f).apply {
            lineTo(k.x0 + k.w - nr, k.y0)
            arcTo(Rect(k.x0 + k.w - 2 * nr, k.y0, k.x0 + k.w, k.y0 + 2 * nr), -90f, b.HOOD, false)
        }
        wire(p, lid(h.open), 3f)
    }
    s.trunk?.let { t -> wire("M${k.x0 + tr} ${k.y0 + k.h}H${k.x0 + k.w - tr}", lid(t.open), 3f) }
    val m = s.lamps ?: return
    // an angle of the left corner, mirrored to the right one
    fun span(a: Pair<Float, Float>, left: Boolean) = if (left) a else (180f - a.second) to (180f - a.first)
    listOf(k.x0 + nr to true, k.x0 + k.w - nr to false).forEach { (x, left) ->
        val (a0, a1) = span(b.LAMP_HEAD, left)
        val p = arcPath(x, k.y0 + nr, nr, a0, a1)
        if (m.high == true) drawPath(p, ink(Ink.WHITE), alpha = b.HALO_ALPHA, style = Stroke(2 * b.LAMP_HALO, cap = StrokeCap.Round, join = StrokeJoin.Round))
        lamp(p, m.low == true || m.high == true, Ink.WHITE)
    }
    listOf(k.x0 + tr to true, k.x0 + k.w - tr to false).forEach { (x, left) ->
        val y = k.y0 + k.h - tr
        val (s0, s1) = span(b.LAMP_STOP, left)
        lamp(arcPath(x, y, tr, s0, s1), m.stop == true, Ink.ORANGE)
        val (r0, r1) = span(b.LAMP_REVERSE, left)
        lamp(arcPath(x, y, tr, r0, r1), m.reverse == true, Ink.WHITE)
    }
    listOf(
        Triple(-1f, k.y0 + b.TURN_Y, m.turnLeft), Triple(-1f, k.y0 + k.h - b.TURN_Y, m.turnLeft),
        Triple(1f, k.y0 + b.TURN_Y, m.turnRight), Triple(1f, k.y0 + k.h - b.TURN_Y, m.turnRight),
    ).forEach { (dir, y, on) ->
        val bx = if (dir < 0) k.x0 - b.TURN_GAP else k.x0 + k.w + b.TURN_GAP
        val tx = bx + dir * b.TURN_LEN
        if (on) drawCircle(ink(Ink.ORANGE), b.TURN_HALO, Offset((bx + tx) / 2f, y), alpha = b.HALO_ALPHA)
        drawPath(path("M$bx ${y - b.TURN_HALF}L$tx ${y}L$bx ${y + b.TURN_HALF}Z"), ink(if (on) Ink.ORANGE else Ink.LINE4))
    }
}

/* ------------------------------------------------------------------- adas */

/** Ассистенты: every sensor where it sits, its field of view as a faint wedge (`sysAdas()`). */
@Composable
private fun AdasScheme(s: Scheme.Adas, onOpen: (Target) -> Unit) {
    val a = ScannerSpec.AdasCar
    val w = LocalScanWidth.current
    val cx = w / 2f
    val k = CarGeom(cx, a.TOP, a.H)
    val (c0, c1) = a.CORNER
    val pts = mapOf(
        Corner.FL to Triple(k.x0 + c0, k.y0 + c1, -135f), Corner.FR to Triple(k.x0 + k.w - c0, k.y0 + c1, -45f),
        Corner.RL to Triple(k.x0 + c0, k.y0 + k.h - c1, 135f), Corner.RR to Triple(k.x0 + k.w - c0, k.y0 + k.h - c1, 45f),
    )
    val lx = ScannerSpec.Callout.LEFT_X
    val rx = w - lx - TILE_W
    fun place(corner: Corner): Offset {
        val (_, y, _) = pts.getValue(corner)
        val left = corner == Corner.FL || corner == Corner.RL
        return Offset(if (left) lx else rx, y - TILE_H / 2 + if (corner == Corner.FR) a.FR_DROP else 0f)
    }
    SchemeCanvas {
        car(k)
        fun wedge(x: Float, y: Float, angle: Float, spread: Float, len: Float) {
            val p = Path().apply {
                moveTo(x, y)
                arcTo(Rect(x - len, y - len, x + len, y + len), angle - spread / 2f, spread, false)
                close()
            }
            drawPath(p, ink(Ink.BAND))
            drawPath(p, ink(Ink.LINE4), style = Stroke(1f))
            dot(x, y, 4f, Ink.WIRE5)
        }
        wedge(cx, k.y0 + 2f, -90f, a.FRONT_SPREAD, a.FRONT_LEN)
        pts.values.forEach { (x, y, ang) -> wedge(x, y, ang, a.CORNERS_SPREAD, a.CORNERS_LEN) }
        // the front radar's leader runs above the corner fields and drops into its own
        if (s.front != null) wire("M$rx ${a.MRR_Y + TILE_H / 2}H${cx}V${k.y0 + 2f}", Ink.WIRE4, 1.2f)
        pts.forEach { (corner, p) ->
            if (s.corners[corner] == null) return@forEach
            val at = place(corner)
            val near = if (at.x < cx) at.x + TILE_W else at.x
            wire("M$near ${at.y + TILE_H / 2}H${(near + p.first) / 2f}V${p.second}H${p.first}", Ink.WIRE4, 1.2f)
        }
    }
    SchemeTile(s.front, rx, a.MRR_Y, onOpen)
    s.corners.forEach { (corner, row) -> place(corner).let { SchemeTile(row, it.x, it.y, onOpen) } }
    SchemeTile(s.controller, cx - TILE_W / 2, k.mid - TILE_H / 2 - a.ADC_LIFT, onOpen)
    SchemeTile(s.parking, cx - TILE_W / 2, k.y0 + k.h - 22f - TILE_H - a.IPA_LIFT, onOpen)
}

/* ----------------------------------------------------------------- drives */

/**
 * Электропривод: the engine, the generator and the front motor on the front axle, the two rear
 * motors on the rear - SMCU the left, SMCU3 the right, as their callouts say - their readings beside
 * them; the VCU in the middle, the power-domain controller beside it (`sysDrives()`).
 */
@Composable
private fun DrivesScheme(s: Scheme.Drives, onOpen: (Target) -> Unit) {
    val o = ScannerSpec.Callout
    val d = ScannerSpec.DrivesCar
    val w = LocalScanWidth.current
    val cx = w / 2f
    val k = CarGeom(cx, ScannerSpec.Car.TOP, ScannerSpec.Car.H)
    val gx = cx - d.PAIR
    val mx = cx + d.PAIR
    val vy = k.mid - TILE_H / 2
    val lx = o.LEFT_X - (d.BOX_W - o.W)
    val rx = w - lx - d.BOX_W
    fun lines(c: dev.denza.apps.feature.diagnostics.scan.Callout): List<Line> =
        listOf<Line>(Words(c.label, Type.COUNTER_LABEL, Ink.MUTE)) + c.rows.map { Words(it, Type.CELL_MONO, Ink.TEXT) }
    data class Box2(val lines: List<Line>, val x: Float, val y: Float, val to: Offset)
    fun box(c: dev.denza.apps.feature.diagnostics.scan.Callout, left: Boolean, to: Offset): Box2 {
        val l = lines(c)
        return Box2(l, if (left) lx else rx, to.y - calloutH(l) / 2f, to)
    }
    val boxes = listOf(
        box(s.front, false, Offset(mx + 22f, k.front)),
        box(s.isg, true, Offset(gx - 22f, k.front)),
        box(s.rearLeft, true, Offset(cx - d.PAIR - 22f, k.rear)),
        box(s.rearRight, false, Offset(cx + d.PAIR + 22f, k.rear)),
    )
    val machines = listOf(Triple(gx, k.front, "G"), Triple(mx, k.front, "M"), Triple(cx - d.PAIR, k.rear, "M"), Triple(cx + d.PAIR, k.rear, "M"))
    val px = k.x0 - d.DOMAIN_GAP - TILE_W
    SchemeCanvas {
        car(k)
        // shafts: front motor to the front wheels, rear motors each to its wheel, engine to generator
        wire("M${k.x0} ${k.front}H${gx - 22}M${mx + 22} ${k.front}H${k.x0 + k.w}M${gx + 22} ${k.front}H${mx - 22}", Ink.WIRE3, 1.6f)
        wire("M${k.x0} ${k.rear}H${cx - d.PAIR - 22}M${cx + d.PAIR + 22} ${k.rear}H${k.x0 + k.w}", Ink.WIRE3, 1.6f)
        box(cx - d.ENGINE_W / 2, k.y0 + d.ENGINE_Y, d.ENGINE_W, d.ENGINE_H, Ink.GROUND, 5f)
        frame(cx - d.ENGINE_W / 2, k.y0 + d.ENGINE_Y, d.ENGINE_W, d.ENGINE_H, Ink.QUIET_LINE, 5f)
        wire("M$gx ${k.y0 + d.ENGINE_Y + d.ENGINE_H}V${k.front - 22}", Ink.WIRE3, 1.6f)
        // the VCU in the middle, its commands as dashed lines
        machines.forEach { (x, y, _) ->
            wire("M$cx ${if (y < k.mid) vy else vy + TILE_H}L$x ${if (y < k.mid) y + 22 else y - 22}", Ink.LINE3, 1f, floatArrayOf(3f, 3f))
        }
        if (s.powerDomain != null) wire("M${px + TILE_W} ${k.mid}H${cx - TILE_W / 2}", Ink.LINE3, 1f, floatArrayOf(3f, 3f))
        machines.forEach { (x, y, _) -> machine(x, y, true) }
        boxes.forEach { b -> leader(b.x, b.y, d.BOX_W, calloutH(b.lines), b.to.x, b.to.y) }
    }
    ScanText("ДВС", Type.CELL_ABBR, Ink.DIM, Modifier.centreAt(cx, k.y0 + d.ENGINE_Y + (d.ENGINE_H - Type.CELL_ABBR.lh) / 2f))
    SchemeTile(s.powerDomain, px, vy, onOpen)
    SchemeTile(s.vcu, cx - TILE_W / 2, vy, onOpen)
    machines.forEach { (x, y, g) -> MachineGlyph(x, y, g, true) }
    boxes.forEach { Plate(it.x, it.y, d.BOX_W, it.lines) }
}

/* ------------------------------------------------------------------ engine */

private fun EnginePlace.key(): String = when (this) {
    EnginePlace.INLET -> "inlet"
    EnginePlace.THROTTLE -> "throttle"
    EnginePlace.MANIFOLD -> "manifold"
    EnginePlace.BLOCK_TOP -> "blockTop"
    EnginePlace.BLOCK_BOTTOM -> "blockBottom"
    EnginePlace.CATALYST -> "catalyst"
    EnginePlace.OXYGEN -> "oxygen"
}

/** Двигатель: the air's way through it, each reading at its place on the way (`sysEngineScheme()`). */
@Composable
private fun EngineScheme(s: Scheme.Engine) {
    val e = ScannerSpec.EngineScheme
    val y = e.PIPE_Y
    val hw = e.PIPE / 2f
    data class Placed(val x: Float, val w: Float, val top: Float, val h: Float, val up: Boolean, val edgeY: Float, val lines: List<Line>)
    val placed = s.points.mapNotNull { p ->
        val g = e.POINTS[p.place.key()] ?: return@mapNotNull null
        val lines = listOf<Line>(Words(p.label, Type.COUNTER_LABEL, Ink.MUTE)) + p.rows.map { Words(it, Type.CELL_MONO, Ink.TEXT) }
        val h = calloutH(lines)
        Placed(g.x, g.w, if (g.up) e.UP_Y - h else e.DOWN_Y, h, g.up, g.at ?: if (g.up) y - hw else y + hw, lines)
    }
    SchemeCanvas {
        wire("M${e.X0} ${y - hw}H${e.BLOCK_X}M${e.X0} ${y + hw}H${e.BLOCK_X}", Ink.WIRE3, 1.6f)
        wire("M${e.BLOCK_X + e.BLOCK_W} ${y - hw}H${e.X1}M${e.BLOCK_X + e.BLOCK_W} ${y + hw}H${e.X1}", Ink.WIRE3, 1.6f)
        flow(e.X0 - 34f, y, e.X0 - 6f, y)
        flow(e.X1 + 6f, y, e.X1 + 34f, y)
        wire("M${e.THROTTLE - 9} ${y + hw - 3}L${e.THROTTLE + 9} ${y - hw + 3}", Ink.WIRE5, 2.4f)
        dot(e.THROTTLE, y, 2.6f, Ink.WIRE5)
        box(e.BLOCK_X, e.BLOCK_Y, e.BLOCK_W, e.BLOCK_H, Ink.GROUND, 8f)
        frame(e.BLOCK_X, e.BLOCK_Y, e.BLOCK_W, e.BLOCK_H, Ink.QUIET_LINE, 8f)
        for (i in 0 until 4) {
            drawCircle(ink(Ink.WIRE4), e.CYL, Offset(e.BLOCK_X + e.BLOCK_W * (i + 0.5f) / 4f, e.BLOCK_Y + e.BLOCK_H / 2f), style = Stroke(1.4f))
        }
        box(e.CAT_X, y - e.CAT_H / 2, e.CAT_W, e.CAT_H, Ink.PLATE, 6f)
        frame(e.CAT_X, y - e.CAT_H / 2, e.CAT_W, e.CAT_H, Ink.WIRE4, 6f)
        var x = e.CAT_X + 10f
        while (x < e.CAT_X + e.CAT_W - 6f) {
            wire("M$x ${y - e.CAT_H / 2 + 6}V${y + e.CAT_H / 2 - 6}", Ink.LINE3, 1f)
            x += 10f
        }
        wire("M${e.O2} ${y + hw + 16}V${y + 2}", Ink.WIRE5, 2.2f)
        box(e.O2 - 5f, y + hw + 14f, 10f, 10f, Ink.QUIET, 2f)
        frame(e.O2 - 5f, y + hw + 14f, 10f, 10f, Ink.WIRE4, 2f)
        placed.forEach { p ->
            wire("M${p.x} ${if (p.up) p.top + p.h else p.top}V${p.edgeY}", Ink.WIRE4, 1.2f)
            dot(p.x, p.edgeY, ScannerSpec.Callout.DOT, Ink.WIRE5)
        }
    }
    placed.forEach { Plate(it.x - it.w / 2f, it.top, it.w, it.lines) }
}

/* ----------------------------------------------------------------- climate */

/** Климат: outside air through the climate unit into the cabin; the heater and the thermal module at the unit, air quality under the cabin. */
@Composable
private fun ClimateScheme(s: Scheme.Climate, onOpen: (Target) -> Unit) {
    val c = ScannerSpec.ClimateScheme
    val w = LocalScanWidth.current
    val ux = w / 2f - TILE_W / 2
    val uy = c.ZONE_Y + c.ZONE_H / 2f - TILE_H / 2
    val zones = listOf(s.outside to c.LEFT_X, s.cabin to w - c.LEFT_X - c.ZONE_W)
    val ax = w - c.LEFT_X - c.ZONE_W / 2f
    SchemeCanvas {
        zones.forEach { (z, x) ->
            box(x, c.ZONE_Y, c.ZONE_W, c.ZONE_H, Ink.GROUND, 10f)
            frame(x, c.ZONE_Y, c.ZONE_W, c.ZONE_H, Ink.QUIET_LINE, 10f)
        }
        flow(c.LEFT_X + c.ZONE_W, uy + TILE_H / 2, ux - 4f, uy + TILE_H / 2)
        flow(ux + TILE_W, uy + TILE_H / 2, w - c.LEFT_X - c.ZONE_W - 4f, uy + TILE_H / 2)
        if (s.heater != null) wire("M${w / 2} ${uy + TILE_H}V${uy + TILE_H + c.STEM}", Ink.WIRE4, 1.2f)
        if (s.thermal != null) wire("M${w / 2} ${uy}V${uy - c.STEM}", Ink.WIRE4, 1.2f)
        if (s.air != null) wire("M$ax ${c.ZONE_Y + c.ZONE_H}V${c.ZONE_Y + c.ZONE_H + c.STEM}", Ink.WIRE4, 1.2f)
    }
    zones.forEach { (z, x) ->
        var y = c.ZONE_Y + 20f
        ScanText(z.label, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(x + 22f, y)); y += 22f
        ScanText(z.temp, Type.COUNTER_BIG, Ink.WHITE, Modifier.at(x + 22f, y)); y += 50f
        ScanText("PM2.5", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(x + 22f, y)); y += 18f
        ScanText(z.pm, Type.COUNTER, Ink.TEXT, Modifier.at(x + 22f, y))
    }
    SchemeTile(s.unit, ux, uy, onOpen)
    SchemeTile(s.heater, ux, uy + TILE_H + c.STEM, onOpen)
    SchemeTile(s.thermal, ux, uy - c.STEM - TILE_H, onOpen)
    SchemeTile(s.air, ax - TILE_W / 2, c.ZONE_Y + c.ZONE_H + c.STEM, onOpen)
}

/* ------------------------------------------------------------------ safety */

/**
 * Безопасность: the car from above - the airbag controller in the middle, each front belt's
 * pretensioner at its B-pillar (the driver's left, the passenger's right), the child-presence radar
 * at the rear (`sysSafety()`).
 */
@Composable
private fun SafetyScheme(s: Scheme.Safety, onOpen: (Target) -> Unit) {
    val c = ScannerSpec.Car
    val g = ScannerSpec.SafetyScheme.GAP
    val cx = LocalScanWidth.current / 2f
    val k = CarGeom(cx, c.TOP, c.H)
    val pillar = k.y0 + (c.DOORS_FRONT.second + c.DOORS_REAR.first) / 2f
    val belts = listOf(
        Hung(s.driverBelt, k.x0 - g - TILE_W, pillar - TILE_H / 2, Offset(k.x0, pillar)),
        Hung(s.passengerBelt, k.x0 + k.w + g, pillar - TILE_H / 2, Offset(k.x0 + k.w, pillar)),
    )
    // the seats in the belts' column, each nearest the car with its words outside it: the driver
    // over the driver's belt, the second row's left and centre under it; the passenger and the
    // second row's right across
    val z = ScannerSpec.SafetyScheme
    val lab = Type.COUNTER_LABEL.lh + ScannerSpec.Callout.GAP
    data class Placed(val seat: SeatBelt, val left: Boolean, val gx: Float, val y: Float, val gy: Float)
    val seats = s.seats.map { st ->
        val left = st.place == CabinPlace.FL || st.place == CabinPlace.RL || st.place == CabinPlace.RC
        val gx = if (left) k.x0 - g - z.SEAT_W else k.x0 + k.w + g
        val y = k.y0 + when (st.place) {
            CabinPlace.FL, CabinPlace.FR -> z.ROWS.first
            CabinPlace.RC -> z.ROWS.second + z.STEP
            else -> z.ROWS.second
        }
        Placed(st, left, gx, y, y + (lab + Type.CELL.lh - z.SEAT_H) / 2f)
    }
    SchemeCanvas {
        car(k)
        belts.forEach { hangLeader(it, cx) }
        val cs = ScannerSpec.CabinScheme
        seats.forEach { p ->
            val occupied = p.seat.seat.occupied == true
            if (occupied) box(p.gx, p.gy, z.SEAT_W, z.SEAT_H, Ink.HIST_FILL, z.SEAT_R)
            frame(p.gx, p.gy, z.SEAT_W, z.SEAT_H, if (occupied) Ink.WIRE4 else Ink.LINE3, z.SEAT_R)
            val strap = "M${p.gx + z.SEAT_W - z.STRAP_IN} ${p.gy + z.STRAP_TOP}L${p.gx + z.STRAP_IN} ${p.gy + z.SEAT_H - z.STRAP_FOOT}"
            if (p.seat.seat.belt == true) wire(strap, Ink.TEXT, cs.STRAP)
            else if (occupied && p.seat.seat.belt == false) wire(strap, Ink.ORANGE, cs.STRAP, cs.STRAP_DASH)
        }
    }
    belts.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
    SchemeTile(s.airbags, cx - TILE_W / 2, k.mid - TILE_H / 2, onOpen)
    SchemeTile(s.childRadar, cx - TILE_W / 2, k.y0 + k.h - 22f - TILE_H, onOpen)
    seats.forEach { p ->
        val tx = if (p.left) p.gx - z.PAD else p.gx + z.SEAT_W + z.PAD
        val align = if (p.left) Align.END else Align.START
        ScanText(p.seat.label, Type.COUNTER_LABEL, Ink.MUTE, if (p.left) Modifier.endAt(tx, p.y) else Modifier.at(tx, p.y))
        SaidRow(p.seat.row, tx, p.y + lab, align)
    }
}

/* ------------------------------------------------------------------- cabin */

/**
 * The plates of one side stacked as `sysCabin()` stacks them: each level with its place where the
 * column has room, pushed down by the one above, the column lifted when it runs past [bottom];
 * and the bends of their leaders from the car's [edge] outward - the leader that climbs or drops
 * the most bends nearest the car, so no leader crosses another.
 */
internal object CabinStack {
    /** The plates' tops for their places' [targets] and their [heights]. */
    fun tops(targets: List<Float>, heights: List<Float>, top: Float, bottom: Float, gap: Float): List<Float> {
        val ys = FloatArray(targets.size)
        targets.indices.forEach { i ->
            ys[i] = maxOf(targets[i] - heights[i] / 2f, if (i > 0) ys[i - 1] + heights[i - 1] + gap else top)
        }
        val over = if (ys.isEmpty()) 0f else ys.last() + heights.last() - bottom
        if (over > 0f) ys.indices.forEach { ys[it] = maxOf(top, ys[it] - over) }
        for (i in 1 until ys.size) ys[i] = maxOf(ys[i], ys[i - 1] + heights[i - 1] + gap)
        return ys.toList()
    }

    /** Each leader's bend: [edge] out by [step] a rank, the rank by how far the leader climbs or drops, the most first. */
    fun bends(targets: List<Float>, tops: List<Float>, heights: List<Float>, edge: Float, step: Float, dir: Float): List<Float> {
        val order = targets.indices.sortedByDescending { kotlin.math.abs(targets[it] - (tops[it] + heights[it] / 2f)) }
        val bends = FloatArray(targets.size)
        order.forEachIndexed { rank, i -> bends[i] = edge + dir * rank * step }
        return bends.toList()
    }
}

/** A cabin word's ink: the row's own - white on the first row, dim under it - or its tone's. */
private fun cabinInk(tone: CabinTone, small: Boolean): Int = when (tone) {
    CabinTone.PLAIN -> if (small) Ink.DIM else Ink.WHITE
    CabinTone.QUIET -> Ink.DIM
    CabinTone.ALERT -> Ink.ORANGE
    CabinTone.WARM -> Ink.ORANGE_INK
}

private fun cabinLines(p: CabinPlate): List<Line> = plateLines(p.label, p.rows)

/**
 * A seat seen through the roof (`sysCabin()`'s `seat()`): filled when someone sits, the strap across
 * it - blue fastened, orange and dashed when someone sits unfastened - heating's warm pips from its
 * left foot and ventilation's cool ones from its right, the reading light at its outer front corner.
 */
private fun DrawScope.seat(x: Float, y: Float, w: Float, h: Float, r: Float, s: CabinSeat, outer: Int) {
    val c = ScannerSpec.CabinScheme
    if (s.occupied == true) box(x, y, w, h, Ink.HIST_FILL, r)
    frame(x, y, w, h, if (s.occupied == true) Ink.WIRE4 else Ink.LINE3, r)
    val strap = "M${x + w - c.STRAP_IN} ${y + c.STRAP_TOP}L${x + c.STRAP_IN} ${y + h - c.STRAP_FOOT}"
    if (s.belt == true) wire(strap, Ink.TEXT, c.STRAP)
    else if (s.occupied == true && s.belt == false) wire(strap, Ink.ORANGE, c.STRAP, c.STRAP_DASH)
    val py = y + h - c.PIP_PAD - c.PIP
    listOf(Triple(s.heat, Ink.ORANGE, x + c.PIP_PAD to 1f), Triple(s.vent, Ink.BLUE, x + w - c.PIP_PAD - c.PIP to -1f)).forEach { (level, on, from) ->
        if (level == null) return@forEach
        val (x1, dir) = from
        for (i in 0 until 3) box(x1 + dir * i * (c.PIP + c.PIP_GAP), py, c.PIP, c.PIP, if (i < level) on else Ink.LINE3, c.PIP_R)
    }
    val lamp = s.lamp ?: return
    val lx = if (outer < 0) x + c.LAMP_IN else x + w - c.LAMP_IN
    val ly = y + c.LAMP_DOWN
    if (lamp) drawCircle(ink(Ink.ORANGE), c.LAMP_GLOW, Offset(lx, ly), alpha = c.LAMP_GLOW_ALPHA)
    dot(lx, ly, c.LAMP_R, if (lamp) Ink.ORANGE else Ink.FAINT3)
}

/**
 * Салон: the cabin seen through the roof (`sysCabin()`) - the seats, the wheel, the console with
 * the pads and the fridges - and the plates down both sides, each level with its place where the
 * column has room, their bends ordered so no leader crosses another.
 */
@Composable
private fun CabinScheme(s: Scheme.Cabin) {
    val c = ScannerSpec.CabinScheme
    val w = LocalScanWidth.current
    val cx = w / 2f
    val k = CarGeom(cx, ScannerSpec.Car.TOP, ScannerSpec.Car.H)
    val kx = cx - c.CONSOLE_W / 2f
    val (wx0, wy0, wr) = c.WHEEL
    val wx = k.x0 + wx0
    val wy = k.y0 + wy0
    val to = mapOf(
        CabinPlace.CLUSTER to Offset(wx, wy - c.CLUSTER_LIFT),
        CabinPlace.WHEEL to Offset(wx - wr, wy),
        CabinPlace.FL to Offset(k.x0 + c.FRONT_X[0], k.y0 + c.FRONT_Y + c.FRONT_H / 2f),
        CabinPlace.RL to Offset(k.x0 + c.BENCH_X[0], k.y0 + c.BENCH_Y + c.BENCH_H / 2f),
        CabinPlace.RC to Offset(cx, k.y0 + c.CENTRE_LEADER),
        CabinPlace.PADS to Offset(cx + c.PAD_GAP + c.PAD_W, k.y0 + c.PAD_Y - c.PADS_LIFT),
        CabinPlace.FR to Offset(k.x0 + c.FRONT_X[1] + c.FRONT_W, k.y0 + c.FRONT_Y + c.FRONT_H / 2f),
        CabinPlace.FRIDGES to Offset(kx + c.CONSOLE_W - c.BOX_INSET, k.y0 + c.FRIDGE_Y + c.FRIDGE_H - c.FRIDGE_LIFT),
        CabinPlace.RR to Offset(k.x0 + c.BENCH_X[2] + c.BENCH_W, k.y0 + c.BENCH_Y + c.BENCH_H / 2f),
    )
    data class Placed(val x: Float, val y: Float, val h: Float, val lines: List<Line>, val to: Offset, val bend: Float)
    fun column(plates: List<CabinPlate>, x: Float, edge: Float, dir: Float): List<Placed> {
        val lines = plates.map(::cabinLines)
        val heights = lines.map(::calloutH)
        val targets = plates.map { to.getValue(it.place).y }
        val tops = CabinStack.tops(targets, heights, c.TOP, c.BOTTOM, c.GAP)
        val bends = CabinStack.bends(targets, tops, heights, edge, c.BEND_STEP, dir)
        return plates.indices.map { Placed(x, tops[it], heights[it], lines[it], to.getValue(plates[it].place), bends[it]) }
    }
    val placed = column(s.left, c.X, k.left - c.BEND_IN, -1f) + column(s.right, w - c.X - c.PLATE_W, k.right + c.BEND_IN, 1f)
    SchemeCanvas {
        car(k, inside = true)
        val sat = { place: CabinPlace -> s.seats[place] ?: CabinSeat(null, null, null, null, null) }
        seat(k.x0 + c.FRONT_X[0], k.y0 + c.FRONT_Y, c.FRONT_W, c.FRONT_H, c.FRONT_R, sat(CabinPlace.FL), -1)
        seat(k.x0 + c.FRONT_X[1], k.y0 + c.FRONT_Y, c.FRONT_W, c.FRONT_H, c.FRONT_R, sat(CabinPlace.FR), 1)
        seat(k.x0 + c.BENCH_X[0], k.y0 + c.BENCH_Y, c.BENCH_W, c.BENCH_H, c.BENCH_R, sat(CabinPlace.RL), -1)
        seat(k.x0 + c.BENCH_X[1], k.y0 + c.BENCH_Y, c.BENCH_W, c.BENCH_H, c.BENCH_R, sat(CabinPlace.RC), 0)
        seat(k.x0 + c.BENCH_X[2], k.y0 + c.BENCH_Y, c.BENCH_W, c.BENCH_H, c.BENCH_R, sat(CabinPlace.RR), 1)
        // the wheel in front of the driver, warm when heated
        val rim = if (s.wheelHeat == true) Ink.ORANGE else Ink.WIRE3
        drawCircle(ink(rim), wr, Offset(wx, wy), style = Stroke(c.WHEEL_STROKE))
        dot(wx, wy, c.WHEEL_DOT, rim)
        // the console: the pads at its front, the fridge in the armrest, the rear fridge behind it
        frame(kx, k.y0 + c.CONSOLE_Y, c.CONSOLE_W, c.CONSOLE_H, Ink.LINE3, c.CONSOLE_R)
        listOf(cx - c.PAD_GAP - c.PAD_W to s.padLeft, cx + c.PAD_GAP to s.padRight).forEach { (x, state) ->
            val y = k.y0 + c.PAD_Y
            if (state == PadState.CHARGING || state == PadState.FULL) {
                box(x, y, c.PAD_W, c.PAD_H, if (state == PadState.CHARGING) Ink.BLUE else Ink.TEXT, c.PAD_R)
            }
            frame(x, y, c.PAD_W, c.PAD_H, when (state) { PadState.FAULT -> Ink.ORANGE; null -> Ink.LINE3; else -> Ink.WIRE3 }, c.PAD_R)
        }
        listOf(Triple(c.FRIDGE_Y, c.FRIDGE_H, s.fridgeFront), Triple(c.REAR_FRIDGE_Y, c.REAR_FRIDGE_H, s.fridgeRear)).forEach { (y, h, mode) ->
            val fill = when (mode) { FridgeMode.COLD -> Ink.BLUE; FridgeMode.FRESH -> Ink.BLUE_SOFT; FridgeMode.HOT -> Ink.ORANGE; else -> null }
            val bx = kx + c.BOX_INSET
            val bw = c.CONSOLE_W - 2f * c.BOX_INSET
            if (fill != null) box(bx, k.y0 + y, bw, h, fill, c.BOX_R)
            else frame(bx, k.y0 + y, bw, h, if (mode == null) Ink.LINE3 else Ink.WIRE3, c.BOX_R)
        }
        placed.forEach { p -> leader(p.x, p.y, c.PLATE_W, p.h, p.to.x, p.to.y, p.bend) }
    }
    placed.forEach { Plate(it.x, it.y, c.PLATE_W, it.lines) }
}

/* ---------------------------------------------------------------------- lv */

/** Сеть 12 В: the DC-DC from the HV bus onto the 12 V line, the battery under it, the body's controllers on it. */
@Composable
private fun LvScheme(s: Scheme.Lv, onOpen: (Target) -> Unit) {
    val n = ScannerSpec.LvScheme
    val y = n.BUS_Y
    val lx = n.DCDC_X + TILE_W
    SchemeCanvas {
        wire("M${n.X0} ${y}H${n.DCDC_X}", Ink.WIRE, 2.4f)
        wire("M$lx ${y}H${n.X1}", Ink.WIRE2, 2f)
        wire("M${n.BATT_X + TILE_W / 2} ${y}V${y + n.BATT_DROP}", Ink.WIRE2, 2f)
        s.loads.indices.forEach { i ->
            val x = n.LOAD_X + i * n.LOAD_STEP
            wire("M${x + TILE_W / 2} ${y}V${y + n.LOAD_DROP}", Ink.WIRE3, 1.4f)
            dot(x + TILE_W / 2, y, 2.6f, Ink.WIRE5)
        }
    }
    ScanText("ШИНА DC", Type.COL_HEAD, Ink.MUTE, Modifier.at(n.X0, y - 44f))
    ScanText(s.hv, Type.HV_VOLTS, Ink.TEXT, Modifier.at(n.X0, y - 26f))
    ScanText("12 В", Type.COL_HEAD, Ink.MUTE, Modifier.at(lx + 16f, y - 44f))
    ScanText(s.lv, Type.HV_VOLTS, Ink.TEXT, Modifier.at(lx + 16f, y - 26f))
    SchemeTile(s.dcdc, n.DCDC_X, y - TILE_H / 2, onOpen)
    // the converter's mode under it, as the voltages stand over the line
    ScanText("РЕЖИМ DC-DC", Type.COL_HEAD, Ink.MUTE, Modifier.at(n.DCDC_X, y + TILE_H / 2 + n.MODE_Y))
    ScanText(s.dcdcMode, Type.HV_VOLTS, Ink.TEXT, Modifier.at(n.DCDC_X, y + TILE_H / 2 + n.MODE_Y + n.MODE_LINE))
    SchemeTile(s.battery, n.BATT_X, y + n.BATT_DROP, onOpen)
    s.loads.forEachIndexed { i, r -> SchemeTile(r, n.LOAD_X + i * n.LOAD_STEP, y + n.LOAD_DROP, onOpen) }
}

/* ---------------------------------------------------------------------- hv */

/**
 * Высоковольтная система: the HV schema at the rail's width in the dark cockpit (`hvBody()` on
 * `hvRail`): quiet frames, white values, an inverter's bus voltage with its difference to the pack's
 * («555 В +2»), what is off at half its light; the pack opens «Тяговая батарея».
 */
@Composable
private fun HvScheme(model: DiagnosticsModel, s: Scheme.Hv, onOpen: (Target) -> Unit) {
    val v = ScannerSpec.Hv
    val hv = model.systems.hv
    SchemeCanvas {
        wire(v.CONTACTOR_WIRE, Ink.WIRE, 2.4f)
        // the blade across the pins when the BMS says closed, lifted when open or not known
        wire(if (hv.contactorClosed == true) v.CONTACTOR_CLOSED else v.CONTACTOR_BLADE, Ink.WIRE, 2f)
        v.CONTACTOR_PINS.forEach { (x, y) -> dot(x, y, v.CONTACTOR_PIN, Ink.TEXT) }
        wire(v.TRUNK, Ink.WIRE, 3f)
        wire(v.TAPS, Ink.WIRE2, 2f)
        wire(v.SHAFTS, Ink.WIRE3, 1.6f)
        wire(v.COUPLING, Ink.WIRE4, 1.4f)
        v.MACHINES.forEach { m -> machine(m.x, m.y, m.live, v.MACHINE_R) }
        wire(v.FEEDS, Ink.WIRE3, 1.6f, floatArrayOf(5f, 4f))
    }
    v.MACHINES.forEach { m -> MachineGlyph(m.x, m.y, m.glyph, m.live) }
    val (lx, ly, lw) = v.CONTACTOR_LABEL
    Column(Modifier.at(lx, ly)) {
        ScanParagraph("главный контактор", Type.HV_LABEL, Ink.MUTE, lw)
        // its state on the line under the label
        ScannerWords.contactor(hv.contactorClosed)?.let { ScanText(it, Type.HV_LABEL, Ink.TEXT) }
    }
    // the charge port under the charger, as the contactor's label
    val (chx, chy, chw) = v.CHARGE_LABEL
    Column(Modifier.at(chx, chy)) {
        ScanParagraph("зарядный разъём", Type.HV_LABEL, Ink.MUTE, chw)
        ScanText(s.chargePort, Type.HV_LABEL, Ink.TEXT)
    }
    ScanText("ШИНА DC", Type.COL_HEAD, Ink.MUTE, Modifier.endAt(v.BUS_LABEL_RIGHT, v.BUS_LABEL_Y))

    // the pack
    val pack = model.systemPages[CarSystem.PACK]?.blocks?.firstOrNull { it.request != null }
    Box(
        Modifier.at(v.PACK_X, v.PACK_Y).size(v.PACK_W.dp, v.PACK_H.dp).plate(Ink.GROUND, Ink.QUIET_LINE, v.PACK_R)
            .tap { onOpen(Target.System(CarSystem.PACK)) },
    ) {
        val iw = v.PACK_W - 2f * v.PACK_PAD_X
        Column(Modifier.padding(horizontal = v.PACK_PAD_X.dp, vertical = v.PACK_PAD_Y.dp)) {
            val tb = maxOf(Type.PACK_TITLE.baseline, Type.PACK_TAG.baseline)
            Box(Modifier.width(iw.dp).height(Type.PACK_TITLE.lh.dp)) {
                ScanText("ВВБ", Type.PACK_TITLE, Ink.WHITE, baseline = tb)
                val tag = pack?.let { "${it.abbreviation} · ${ScannerWords.hex(it.request!!)}" } ?: ""
                ScanText(tag, Type.PACK_TAG, Ink.FAINT, Modifier.endAt(iw, 0f), baseline = tb)
            }
            Spacer(Modifier.height((2f * v.PACK_GAP).dp))
            val rb = maxOf(Type.PACK_KEY.baseline, Type.PACK_VALUE.baseline)
            hv.pack.forEach { f ->
                Row(Modifier.width(iw.dp).height(Type.PACK_VALUE.lh.dp)) {
                    Box(Modifier.weight(1f).padding(end = 10.dp)) { ScanText(f.key, Type.PACK_KEY, Ink.DIM2, baseline = rb) }
                    ScanText(f.value, Type.PACK_VALUE, Ink.WHITE, baseline = rb)
                }
                Spacer(Modifier.height(v.PACK_GAP.dp))
            }
        }
    }
    hv.units.forEach { u -> v.UNITS[u.abbreviation]?.let { p -> HvUnitBox(u, p.w ?: v.UNIT_W, Modifier.at(p.x, p.y), onOpen) } }
}

/** A unit on the HV schema: its abbreviation and address, its name, then its bus voltage and what else it says. */
@Composable
private fun HvUnitBox(u: HvUnit, w: Float, modifier: Modifier, onOpen: (Target) -> Unit) {
    val v = ScannerSpec.Hv
    val none = u.status == ModuleStatus.NO_ROUTE
    Box(
        modifier.size(w.dp, v.UNIT_H.dp).alpha(if (u.off) HALF else 1f)
            .plate(Ink.GROUND, if (none) Ink.LINE4 else Ink.QUIET_LINE, v.UNIT_R, dash = none).tap(u.target?.let { { onOpen(it) } }),
    ) {
        val ux = 1f + v.UNIT_PAD_X
        val uw = w - 2f - 2f * v.UNIT_PAD_X
        val top = (v.UNIT_H - Type.HV_ABBR.lh - Type.HV_NAME.lh - Type.SIG.lh - 2f * v.UNIT_ROW_GAP) / 2f
        ScanText(u.abbreviation, Type.HV_ABBR, Ink.WHITE, Modifier.at(ux, top))
        ScanText(u.address, Type.TILE_ADDR, Ink.FAINT, Modifier.endAt(ux + uw, top))
        ScanText(u.name, Type.HV_NAME, Ink.DIM, Modifier.at(ux, top + Type.HV_ABBR.lh + v.UNIT_ROW_GAP), maxWidth = uw)
        // the reading: a bus voltage and its difference to the pack's, then the rest
        val volts = u.busVolts
        val reading = u.reading ?: u.state.takeIf { volts == null && it.isNotEmpty() }
        val runs = listOfNotNull(
            volts?.let { Triple(it, Type.SIG, Ink.WHITE) },
            volts?.let { u.busDelta?.let { d -> Triple(" $d", Type.SIG, Ink.FAINT) } },
            reading?.let { Triple((if (volts != null) " · " else "") + it, Type.SIG, if (volts != null) Ink.DIM else Ink.TEXT) },
        )
        if (runs.isNotEmpty()) ScanRuns(runs, Modifier.at(ux, top + Type.HV_ABBR.lh + Type.HV_NAME.lh + 2f * v.UNIT_ROW_GAP), maxWidth = uw)
    }
}

/** What is off is drawn at this much of its light. */
private const val HALF = 0.45f

/* -------------------------------------------------------------------- tiles */

/**
 * A block on a scheme (`unitTile()`), in the dark cockpit: a quiet frame and white words for a block
 * that answered, dim for one not read, dashed for one with no address; its records - the sign and the
 * count - in place of its address where it has any, a word where the look cannot say what went wrong.
 */
@Composable
internal fun Tile(r: ModuleRowView, x: Float, y: Float, onOpen: (Target) -> Unit) {
    val t = ScannerSpec.Tile
    val dashed = r.status == ModuleStatus.NO_ROUTE || r.status == ModuleStatus.NO_REPLY
    val unread = !dashed && !ScannerWords.read(r.status)
    val width = rememberWidths()
    Box(
        Modifier.at(x, y).size(t.W.dp, t.H.dp)
            .plate(if (dashed) null else Ink.GROUND, if (dashed) Ink.LINE4 else Ink.QUIET_LINE, t.R, dashed)
            .tap(r.target?.let { { onOpen(it) } }),
    ) {
        val ix = 1f + t.PAD_X
        val iw = t.W - 2f - 2f * t.PAD_X
        val top = (t.H - Type.TILE_ABBR.lh - t.ROW_GAP - Type.TILE_NAME.lh) / 2f
        val right = ScannerWords.tileRight(r)
        val mark = right.mark
        val rw = if (mark != null) {
            val n = mark.count.toString()
            ScanText(n, Type.TILE_ADDR, Ink.INK3, Modifier.endAt(ix + iw, top))
            val nw = width(n, Type.TILE_ADDR)
            SignAt(mark.mark, ix + iw - nw - ScannerSpec.Rail.MARK_GAP + 2f - ScannerSpec.Sign.R, top + Type.TILE_ABBR.lh / 2f)
            nw + ScannerSpec.Rail.MARK_GAP - 2f + 2f * ScannerSpec.Sign.R
        } else {
            ScanText(right.word, Type.TILE_ADDR, if (dashed) Ink.FAINT2 else Ink.FAINT, Modifier.endAt(ix + iw, top))
            width(right.word, Type.TILE_ADDR)
        }
        val ink = when {
            dashed -> Ink.FAINT
            unread -> Ink.DIM
            else -> Ink.WHITE
        }
        val sub = when {
            dashed -> Ink.FAINT2
            unread -> Ink.MUTE
            else -> Ink.DIM
        }
        ScanText(r.abbreviation, Type.TILE_ABBR, ink, Modifier.at(ix, top), maxWidth = iw - rw - 6f)
        ScanText(r.short, Type.TILE_NAME, sub, Modifier.at(ix, top + Type.TILE_ABBR.lh + t.ROW_GAP), maxWidth = iw)
    }
}
