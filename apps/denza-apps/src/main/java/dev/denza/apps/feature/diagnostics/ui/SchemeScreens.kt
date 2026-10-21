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
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.Corner
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.EnginePlace
import dev.denza.apps.feature.diagnostics.scan.HvUnit
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
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
 * Ассистенты, Электропривод and Безопасность; the HV schema (`hvBody()`); the pack (`sysPack()`, `rangeBar()`); the engine's air
 * path; climate; the 12 V net. Line art is drawn on one canvas in window dp, words and tiles are
 * composables over it, so a tile still opens its block.
 */

@Composable
internal fun SchemeContent(model: DiagnosticsModel, scheme: Scheme, onOpen: (Target) -> Unit) {
    when (scheme) {
        Scheme.Hv -> HvScheme(model, onOpen)
        is Scheme.Pack -> PackScheme(scheme)
        is Scheme.Drives -> DrivesScheme(scheme, onOpen)
        is Scheme.Engine -> EngineScheme(scheme)
        is Scheme.Chassis -> ChassisScheme(scheme, onOpen)
        is Scheme.Body -> BodyScheme(scheme, onOpen)
        is Scheme.Adas -> AdasScheme(scheme, onOpen)
        is Scheme.Climate -> ClimateScheme(scheme, onOpen)
        is Scheme.Lv -> LvScheme(scheme, onOpen)
        is Scheme.Safety -> SafetyScheme(scheme, onOpen)
        // drawn by the next change: the cabin seen through the roof
        is Scheme.Cabin -> Unit
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

/** The body from above, front up, in lines: outline, glass, mirrors and wheels (`car()`). */
private fun DrawScope.car(k: CarGeom) {
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
    frame(k.x0 + c.ROOF_INSET, ws + 18, k.w - 2 * c.ROOF_INSET, rw - ws - 34, Ink.LINE3, 14f)
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

/** A callout's leader: from the plate's near side to a point on the car, a dot at the end. */
private fun DrawScope.leader(x: Float, y: Float, w: Float, h: Float, tx: Float, ty: Float) {
    val sx = if (tx < x) x else x + w
    val sy = y + h / 2f
    wire(if (ty == sy) "M$sx ${sy}H$tx" else "M$sx ${sy}H${(sx + tx) / 2f}V${ty}H$tx", Ink.WIRE4, 1.2f)
    dot(tx, ty, ScannerSpec.Callout.DOT, Ink.WIRE5)
}

/** A plate of lines (`callout()`): the OK tile's fill and edge. */
@Composable
private fun Plate(x: Float, y: Float, w: Float, lines: List<Line>) {
    val o = ScannerSpec.Callout
    Box(Modifier.at(x, y).size(w.dp, calloutH(lines).dp).plate(Ink.TILE_OK, Ink.BLUE_EDGE, o.R)) {
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

/** A scheme's tile: the topology's full size; nothing where the catalog has no such block. */
@Composable
private fun SchemeTile(row: ModuleRowView?, x: Float, y: Float, onOpen: (Target) -> Unit) {
    if (row != null) Tile(row, x, y, TileSize.SCHEME, onOpen)
}

private val TILE_W = ScannerSpec.Topology.TILE_W
private val TILE_H = ScannerSpec.Topology.TILE_H

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
 * Шасси: the tyres at their corners, the brake and steering blocks where they sit, the rear wheels'
 * steering under their tyres, the motion controller in the middle (`sysChassis()`).
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
        )
        val h = calloutH(lines)
        Triple(Offset(if (left) lx else rx, y - h / 2f), lines, Offset(if (left) k.left else k.right, y))
    }
    val by = k.y0 + 22f + TILE_H / 2f
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
        // the brake system's OBD interface above the left tyres, joined to its block
        wire("M${lx + TILE_W} ${top + TILE_H / 2}H${(lx + TILE_W + cx - TILE_W / 2) / 2}V${by}H${cx - TILE_W / 2}", Ink.WIRE4, 1.2f, floatArrayOf(4f, 3f))
        steer.forEach { hangLeader(it, cx) }
    }
    plates.forEach { (at, lines, _) -> Plate(at.x, at.y, o.W, lines) }
    SchemeTile(s.esp, cx - TILE_W / 2, k.y0 + 22f, onOpen)
    SchemeTile(s.eps, cx - TILE_W / 2, k.front - TILE_H / 2, onOpen)
    SchemeTile(s.bscm, lx, top, onOpen)
    steer.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
    SchemeTile(s.motion, cx - TILE_W / 2, k.mid - TILE_H / 2, onOpen)
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
    val sides = listOf(
        Side(s.lampLeft, lx, ScannerSpec.Content.TOP, Offset(k.x0 + 26f, k.y0 + 14f)),
        Side(s.lampRight, tr, ScannerSpec.Content.TOP, Offset(k.x0 + k.w - 26f, k.y0 + 14f)),
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
    SchemeCanvas {
        car(k)
        doors.forEach { d ->
            wire("M${d.side} ${k.y0 + d.a}V${k.y0 + d.b}", if (d.open) Ink.ORANGE else Ink.BLUE_SOFT, 3f)
            leader(d.x, d.y, o.W, calloutH(d.lines), d.side, k.y0 + (d.a + d.b) / 2f)
        }
        anchors.forEachIndexed { i, p ->
            wire("M${p.x} ${p.y}L$cx ${k.mid}", Ink.LINE3, 1f, floatArrayOf(3f, 3f))
            val st = s.anchors.getOrNull(i)
            dot(p.x, p.y, 4f, if (st == ModuleStatus.NO_ROUTE || st == ModuleStatus.NO_REPLY) Ink.LINE4 else Ink.BLUE)
        }
        sides.forEach { sd ->
            if (sd.row == null) return@forEach
            wire("M${if (sd.x < cx) sd.x + TILE_W else sd.x} ${sd.y + TILE_H / 2}H${sd.to.x}V${sd.to.y}", Ink.WIRE4, 1.2f)
            dot(sd.to.x, sd.to.y, o.DOT, Ink.WIRE5)
        }
        hung.forEach { hangLeader(it, cx) }
    }
    doors.forEach { Plate(it.x, it.y, o.W, it.lines) }
    val uy = k.mid - TILE_H / 2
    SchemeTile(s.keyReceiver, cx - TILE_W / 2, uy - ScannerSpec.Topology.TILE_PITCH, onOpen)
    SchemeTile(s.uwb, cx - TILE_W / 2, uy, onOpen)
    SchemeTile(s.smartEntry, cx - TILE_W / 2, uy + ScannerSpec.Topology.TILE_PITCH, onOpen)
    SchemeTile(s.front, cx - TILE_W / 2, k.y0 + 22f, onOpen)
    SchemeTile(s.back, cx - TILE_W / 2, k.y0 + k.h - 22f - TILE_H, onOpen)
    sides.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
    hung.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
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
            drawPath(p, ink(Ink.BLUE_SHARE))
            drawPath(p, ink(Ink.BLUE_EDGE), style = Stroke(1f))
            dot(x, y, 4f, Ink.BLUE)
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
        box(cx - d.ENGINE_W / 2, k.y0 + d.ENGINE_Y, d.ENGINE_W, d.ENGINE_H, Ink.TILE_OK, 5f)
        frame(cx - d.ENGINE_W / 2, k.y0 + d.ENGINE_Y, d.ENGINE_W, d.ENGINE_H, Ink.LINE4, 5f)
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

/* -------------------------------------------------------------------- pack */

/**
 * Тяговая батарея: the pack with its charge, its two poles to the DC bus, the insulation measured
 * from them to the body; the cells' and modules' ranges beside it (`sysPack()`, `rangeBar()`).
 */
@Composable
private fun PackScheme(s: Scheme.Pack) {
    val p = ScannerSpec.PackScheme
    val ih = p.H - 2f * p.PAD
    val fh = ih * (s.soc ?: 0f).coerceIn(0f, 100f) / 100f
    val tx = p.X + p.PAD + 14f
    val px = p.X + p.W
    val (yp, ym) = p.POLES
    val bx = p.BUS_X
    val ix = p.ISO_X
    val iy = (yp + ym) / 2f - p.ISO_H / 2f
    val mx = ix + p.ISO_W / 2f
    val gx = ix + p.ISO_W + 30f
    val gy = iy + p.ISO_H / 2f
    SchemeCanvas {
        box(p.X, p.Y, p.W, p.H, Ink.PLATE, p.R)
        frame(p.X, p.Y, p.W, p.H, Ink.BLUE_EDGE, p.R)
        if (fh > 0f) {
            box(p.X + p.PAD, p.Y + p.PAD + ih - fh, p.W - 2f * p.PAD, fh, Ink.BLUE_SHARE, 4f)
            box(p.X + p.PAD, p.Y + p.PAD + ih - fh, p.W - 2f * p.PAD, 1.5f, Ink.BLUE_EDGE)
        }
        box(px, yp - 8f, 8f, 16f, Ink.LINE4, 2f)
        box(px, ym - 8f, 8f, 16f, Ink.LINE4, 2f)
        wire("M${px + 8} ${yp}H${bx}M${px + 8} ${ym}H$bx", Ink.WIRE, 2.4f)
        // insulation: from both poles to the body
        wire("M$mx ${yp}V${iy}M$mx ${ym}V${iy + p.ISO_H}", Ink.WIRE3, 1.6f)
        dot(mx, yp, 3f, Ink.WIRE5)
        dot(mx, ym, 3f, Ink.WIRE5)
        box(ix, iy, p.ISO_W, p.ISO_H, Ink.TILE_OK, 6f)
        frame(ix, iy, p.ISO_W, p.ISO_H, Ink.BLUE_EDGE, 6f)
        wire(
            "M${ix + p.ISO_W} ${gy}H${gx}V${gy + 8}M${gx - 12} ${gy + 8}H${gx + 12}M${gx - 8} ${gy + 13}H${gx + 8}M${gx - 4} ${gy + 18}H${gx + 4}",
            Ink.WIRE3, 1.6f,
        )
    }
    ScanText("SOC", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(tx, p.Y + p.PAD + 14f))
    ScanText(s.socText, Type.COUNTER_BIG, Ink.WHITE, Modifier.at(tx, p.Y + p.PAD + 32f))
    // SOC in the empty part above the charge, SOH and the cells at the bottom, inside it
    ScanText("SOH", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(tx, p.Y + p.H - p.PAD - 100f))
    ScanText(s.soh, Type.COUNTER, Ink.TEXT, Modifier.at(tx, p.Y + p.H - p.PAD - 82f))
    ScanText(s.cells, Type.CELL_SMALL, Ink.DIM, Modifier.at(tx, p.Y + p.H - p.PAD - 32f))
    ScanText("HV+", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(px + 18f, yp - 22f))
    ScanText("HV−", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(px + 18f, ym + 8f))
    ScanText("ШИНА DC", Type.COL_HEAD, Ink.MUTE, Modifier.endAt(bx, yp - 22f))
    ScanText(s.volts, Type.HV_VOLTS, Ink.TEXT, Modifier.endAt(bx, yp + 10f))
    ScanText(s.current, Type.CELL_MONO, Ink.DIM, Modifier.endAt(bx, yp + 32f))
    var y = iy + 12f
    ScanText("ИЗОЛЯЦИЯ НА КОРПУС", Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(ix + 14f, y))
    y += 22f
    s.insulation.forEach { f ->
        ScanText(f.key, Type.CELL_SMALL, Ink.DIM, Modifier.at(ix + 14f, y))
        ScanText(f.value, Type.CELL_MONO, Ink.WHITE, Modifier.endAt(ix + p.ISO_W - 14f, y))
        y += 22f
    }
    ScanText(s.isoNorm, Type.SIG, Ink.FAINT, Modifier.at(ix + 14f, y + 4f))
    ScanText("корпус", Type.SIG, Ink.FAINT, Modifier.centreAt(gx, gy + 26f))
    s.ranges.forEachIndexed { i, r -> RangeBarView(p.RANGES_X, p.RANGES_Y + i * p.RANGES_STEP, p.RANGES_W, r) }
}

/** A range on a scale: the bar, the span the readings take on it, the scale's ends under it. */
@Composable
private fun RangeBarView(x: Float, y: Float, w: Float, r: RangeView) {
    val g = ScannerSpec.RangeBar
    fun at(v: Float) = x + (v - r.scale.start) / (r.scale.endInclusive - r.scale.start) * w
    val by = y + g.HEAD_GAP
    Canvas(Modifier.fillMaxSize()) {
        val k = density
        drawRoundRect(ink(Ink.BIT_OFF), Offset(x * k, by * k), Size(w * k, g.H * k), CornerRadius(g.H / 2 * k))
        r.span?.let { span ->
            val a = at(span.start)
            val b = maxOf(at(span.endInclusive), a + g.MIN_SPAN)
            val rr = 2f * k
            glowUnder(Ink.RANGE_GLOW, 6f) { c, paint -> c.drawRoundRect(a * k, (by - 2f) * k, b * k, (by + g.H + 2f) * k, rr, rr, paint) }
            drawRoundRect(ink(Ink.BLUE), Offset(a * k, (by - 2f) * k), Size((b - a) * k, (g.H + 4f) * k), CornerRadius(rr))
        }
        r.mark?.let { m -> drawRect(ink(Ink.WHITE), Offset((at(m) - 0.75f) * k, (by - 5f) * k), Size(1.5f * k, (g.H + 10f) * k)) }
    }
    ScanText(r.head, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(x, y))
    ScanText(r.value, Type.VALUE_BMS, Ink.WHITE, Modifier.endAt(x + w, y - 3f))
    ScanText(r.lo, Type.SIG, Ink.FAINT, Modifier.at(x, by + g.H + g.TICK_GAP))
    ScanText(r.hi, Type.SIG, Ink.FAINT, Modifier.endAt(x + w, by + g.H + g.TICK_GAP))
    if (r.note.isNotEmpty()) ScanText(r.note, Type.SIG, Ink.DIM2, Modifier.centreAt(x + w / 2f, by + g.H + g.TICK_GAP))
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
        box(e.BLOCK_X, e.BLOCK_Y, e.BLOCK_W, e.BLOCK_H, Ink.TILE_OK, 8f)
        frame(e.BLOCK_X, e.BLOCK_Y, e.BLOCK_W, e.BLOCK_H, Ink.BLUE_EDGE, 8f)
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
            box(x, c.ZONE_Y, c.ZONE_W, c.ZONE_H, if (z.inside) Ink.TILE_OK else Ink.PLATE, 10f)
            frame(x, c.ZONE_Y, c.ZONE_W, c.ZONE_H, if (z.inside) Ink.BLUE_EDGE else Ink.LINE4, 10f)
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
    SchemeCanvas {
        car(k)
        belts.forEach { hangLeader(it, cx) }
    }
    belts.forEach { SchemeTile(it.row, it.x, it.y, onOpen) }
    SchemeTile(s.airbags, cx - TILE_W / 2, k.mid - TILE_H / 2, onOpen)
    SchemeTile(s.childRadar, cx - TILE_W / 2, k.y0 + k.h - 22f - TILE_H, onOpen)
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
    SchemeTile(s.battery, n.BATT_X, y + n.BATT_DROP, onOpen)
    s.loads.forEachIndexed { i, r -> SchemeTile(r, n.LOAD_X + i * n.LOAD_STEP, y + n.LOAD_DROP, onOpen) }
}

/* ---------------------------------------------------------------------- hv */

private data class UnitLook(val fill: Int, val line: Int, val dash: Boolean, val ink: Int, val sub: Int, val state: Int)

private fun unitLook(status: ModuleStatus): UnitLook = when (ScannerWords.chip(status).look) {
    ScannerWords.Look.OK -> UnitLook(Ink.TILE_OK, Ink.BLUE_EDGE, false, Ink.WHITE, Ink.DIM, Ink.BLUE_SOFT)
    ScannerWords.Look.HIST -> UnitLook(Ink.TILE_HIST, Ink.SEP, false, Ink.WHITE, Ink.DIM, Ink.INK4)
    ScannerWords.Look.ON -> UnitLook(Ink.TILE_HIST, Ink.ORANGE, false, Ink.WHITE, Ink.DIM, Ink.ORANGE_INK)
    ScannerWords.Look.UNREAD -> UnitLook(Ink.TILE_UNREAD, Ink.LINE3, false, Ink.INK2, Ink.DIM, Ink.DIM2)
    ScannerWords.Look.NONE -> UnitLook(Ink.GROUND, Ink.LINE4, true, Ink.DIM, Ink.MUTE, Ink.FAINT)
}

/** Высоковольтная система: the HV schema at the rail's width (`hvBody()` on `hvRail`); the pack opens «Тяговая батарея». */
@Composable
private fun HvScheme(model: DiagnosticsModel, onOpen: (Target) -> Unit) {
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
    ScanText("ШИНА DC", Type.COL_HEAD, Ink.MUTE, Modifier.endAt(v.BUS_LABEL_RIGHT, v.BUS_LABEL_Y))
    hv.busVoltages.take(v.VOLTS.size).forEachIndexed { i, t -> ScanText(t, Type.HV_VOLTS, Ink.TEXT, Modifier.at(v.VOLTS[i].first, v.VOLTS[i].second)) }

    // the pack
    val pack = model.systemPages[CarSystem.PACK]?.blocks?.firstOrNull { it.request != null }
    val chip = ScannerWords.chip(hv.packStatus)
    Box(
        Modifier.at(v.PACK_X, v.PACK_Y).size(v.PACK_W.dp, v.PACK_H.dp).plate(Ink.TILE_OK, Ink.BLUE_EDGE, v.PACK_R)
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
            Spacer(Modifier.height(v.PACK_GAP.dp))
            val codes = pack?.takeIf { ScannerWords.read(it.status) }?.let { (it.active.toIntOrNull() ?: 0) + (it.history.toIntOrNull() ?: 0) }
            ScanText("${chip.text} · DTC ${codes ?: "—"}", Type.PACK_STATE, ScannerWords.tone(chip.look).ink)
            Spacer(Modifier.height(v.PACK_GAP.dp))
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

@Composable
private fun HvUnitBox(u: HvUnit, w: Float, modifier: Modifier, onOpen: (Target) -> Unit) {
    val v = ScannerSpec.Hv
    val k = unitLook(u.status)
    Box(modifier.size(w.dp, v.UNIT_H.dp).plate(k.fill, k.line, v.UNIT_R, k.dash).tap(u.target?.let { { onOpen(it) } })) {
        val ux = 1f + v.UNIT_PAD_X
        val uw = w - 2f - 2f * v.UNIT_PAD_X
        val top = (v.UNIT_H - Type.HV_ABBR.lh - Type.HV_NAME.lh - Type.HV_STATE.lh - 2f * v.UNIT_ROW_GAP) / 2f
        ScanText(u.abbreviation, Type.HV_ABBR, k.ink, Modifier.at(ux, top))
        ScanText(u.address, Type.TILE_ADDR, Ink.FAINT, Modifier.endAt(ux + uw, top))
        ScanText(u.name, Type.HV_NAME, k.sub, Modifier.at(ux, top + Type.HV_ABBR.lh + v.UNIT_ROW_GAP), maxWidth = uw)
        ScanText(u.state, Type.HV_STATE, k.state, Modifier.at(ux, top + Type.HV_ABBR.lh + Type.HV_NAME.lh + 2f * v.UNIT_ROW_GAP), maxWidth = uw)
    }
}
