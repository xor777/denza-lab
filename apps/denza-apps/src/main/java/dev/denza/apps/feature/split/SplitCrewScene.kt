package dev.denza.apps.feature.split

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * «Бригада сплита», the split shield's wait: a construction crew drawn in thin blue light lines
 * moving the border between the panes while the scene is built behind it.
 *
 * `tools/design-canvas/split-crew/split-crew.html`, `drawShield(t)`, line for line. The owner
 * approved that page on 2026-09-23 and it is normative: every number here is the page's, the
 * functions keep its names, and `SplitCrewBoardContractTest` holds the constants to the file. Two
 * workers push the divider hanging from a gantry ⅓ → ½ → ⅔ → ½ every four seconds, so the wait
 * never promises which split is coming; on the left a tower crane stacks blocks while a signalman
 * waves it on; on the right one worker holds a frame and another nails it from a ladder, a side
 * per blow, with sparks. The site opens as a circle from the centre of the screen.
 *
 * The picture is a pure function of `t`, the milliseconds since the shield appeared, drawn in the
 * page's units: dp on the 1280 x 800 screen, two pixels each on the car. Everything is emitted into
 * a [Pen] - the page's canvas verbs and nothing else - so the JVM tests can record a frame and the
 * view can draw it. Each `beginPath … stroke` of the page is one path here, stroked once, and that
 * is not a detail: the strokes are added (`lighter`, `BlendMode.PLUS`), so two segments of one path
 * that cross are lit once where they cross, and two paths that cross are lit twice.
 *
 * The page allocates as it goes (a pose is an object, a hand an array). The view draws a frame
 * sixty times a second, so here the poses, hands and joints are scratch objects of the scene,
 * refilled per frame; the arithmetic is the page's, in the same order and in doubles.
 */
internal class SplitCrewScene(private val pen: Pen, private val caption: String) {

    /** The two strokes of the page: the field gradient, and the pale crown for sparks, thud and ring. */
    enum class Ink { FIELD, CROWN }

    /**
     * The page's canvas, in its units. Paths follow the canvas rules: [arc] joins the current point
     * to its start with a straight line, or starts a subpath when there is none; [rect] is a closed
     * subpath of its own; a [lineTo] the current point adds nothing, so a subpath of only that is
     * not drawn at all. Angles are radians, clockwise on the screen, as the canvas measures them.
     */
    interface Pen {
        /** Black over the whole screen, laid on, not added: the shield hides what is behind it. */
        fun ground()

        /** The haze: [HALO] from the centre, squashed to an ellipse, added at [alpha]. */
        fun haze(alpha: Double)

        fun beginPath()
        fun moveTo(x: Double, y: Double)
        fun lineTo(x: Double, y: Double)
        fun rect(x: Double, y: Double, w: Double, h: Double)
        fun arc(cx: Double, cy: Double, r: Double, start: Double, end: Double)

        /** The current path, added in [ink] at [alpha], [width] units wide, round caps and joins. */
        fun stroke(alpha: Double, width: Double, ink: Ink)

        fun save()
        fun clipCircle(cx: Double, cy: Double, r: Double)
        fun restore()

        /**
         * [text] in white at [alpha], [size] units, laid on rather than added, centred on [x] and on
         * [y] as the canvas's `middle` baseline puts it: the middle of the em box.
         */
        fun caption(text: String, x: Double, y: Double, size: Double, alpha: Double)
    }

    /** A figure's joints, as `pose()` returns them; [walk], [amp] and [nod] are the page's `P.o`. */
    class Pose {
        var f = 1.0
        var amp = 0.0
        var walk = 0.0
        var nod = 0.0
        var lean = 0.0
        val th = DoubleArray(2)
        val kn = DoubleArray(2)
        var hx = 0.0
        var hy = 0.0
        var nx = 0.0
        var ny = 0.0
        var sx = 0.0
        var sy = 0.0
        var cx = 0.0
        var cy = 0.0
    }

    /** Where a hand is sent: the page's `[x, y]` or `[x, y, bend]`; NaN [bend] is the figure's facing. */
    class Hand {
        var x = 0.0
        var y = 0.0
        var bend = Double.NaN

        fun set(x: Double, y: Double, bend: Double = Double.NaN): Hand {
            this.x = x
            this.y = y
            this.bend = bend
            return this
        }
    }

    /** What `crane(t)` returns: the round [c], the time [u] into it, the hook and the load. */
    class Crane {
        var c = 0
        var u = 0.0
        var onStack = 0
        var tx = 0.0
        var hy = 0.0
        var carrying = false
    }

    private val pusher = Pose()
    private val signalman = Pose()
    private val hammerer = Pose()
    private val holder = Pose()
    private val standing = Pose()
    private val handA = Hand()
    private val handB = Hand()
    private val swing = Hand()
    private val joint = DoubleArray(4)
    private val edges = DoubleArray(16)
    private val craneState = Crane()

    // ---- Drawing verbs. ----

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double) {
        pen.moveTo(x0, y0)
        pen.lineTo(x1, y1)
    }

    private fun stroke(alpha: Double, width: Double) = pen.stroke(alpha, width, Ink.FIELD)

    /** A lattice girder in an axis-aligned box: two chords along the long side, a zigzag between. */
    fun truss(x0: Double, y0: Double, x1: Double, y1: Double, pitch: Double) {
        pen.beginPath()
        val horiz = (x1 - x0) >= (y1 - y0)
        pen.rect(x0, y0, x1 - x0, y1 - y0)
        if (horiz) {
            val n = max(1, jsRound((x1 - x0) / pitch))
            pen.moveTo(x0, y1)
            for (i in 1..n) pen.lineTo(x0 + (x1 - x0) * i / n, if (i % 2 != 0) y0 else y1)
        } else {
            val n = max(1, jsRound((y1 - y0) / pitch))
            pen.moveTo(x0, y0)
            for (i in 1..n) pen.lineTo(if (i % 2 != 0) x1 else x0, y0 + (y1 - y0) * i / n)
        }
    }

    /** A block: a rectangle with a cross brace. */
    fun block(x: Double, y: Double, w: Double, h: Double) {
        pen.rect(x, y, w, h)
        line(x, y, x + w, y + h)
        line(x + w, y, x, y + h)
    }

    // ---- People: stick figures in hard hats. ----

    fun drawPerson(P: Pose, hand0: Hand?, hand1: Hand?) {
        val f = P.f
        val hx = P.hx
        val hy = P.hy
        val walk = P.walk
        val amp = P.amp
        pen.beginPath()
        for (l in 0..1) {
            val th = P.th[l]
            val kn = P.kn[l]
            val kx = hx + THIGH * sin(th) * f
            val ky = hy + THIGH * cos(th)
            val fx = kx + SHIN * sin(th - kn) * f
            val fy = ky + SHIN * cos(th - kn)
            pen.moveTo(hx, hy); pen.lineTo(kx, ky); pen.lineTo(fx, fy); pen.lineTo(fx + 0.07 * U * f, fy)
        }
        line(hx, hy, P.nx, P.ny)
        for (i in 0..1) {
            val h = (if (i == 0) hand0 else hand1) ?: run {
                val sw = sin(walk + (if (i != 0) 0.0 else PI)) * 0.4 * amp + 0.1
                swing.set(P.sx + sin(sw) * f * REACH * 0.94, P.sy + cos(sw) * REACH * 0.94)
            }
            val j = ik(P.sx, P.sy, h.x, h.y, if (h.bend.isNaN()) f else h.bend, joint)
            pen.moveTo(P.sx, P.sy); pen.lineTo(j[0], j[1]); pen.lineTo(j[2], j[3])
        }
        stroke(1.0, 2.2)
        // Head and hard hat.
        pen.beginPath()
        pen.arc(P.cx, P.cy, HEAD_R, 0.0, 2 * PI)
        stroke(1.0, 2.0)
        // The page draws the hat under translate(cx, cy) and rotate(up × f) and strokes it after
        // restore(); the pen has no transform, so the hat's points are carried through it here.
        val up = P.lean + P.nod
        val rot = up * f
        val c = cos(rot)
        val s = sin(rot)
        val lift = -0.25 * HEAD_R
        pen.beginPath()
        pen.arc(P.cx - lift * s, P.cy + lift * c, HEAD_R + 1.6, PI + rot, 2 * PI + rot)
        val back = -(HEAD_R + 3) * f
        val front = (HEAD_R + 6) * f
        line(
            P.cx + back * c - lift * s, P.cy + back * s + lift * c,
            P.cx + front * c - lift * s, P.cy + front * s + lift * c,
        )
        stroke(1.0, 2.2)
    }

    // ---- The gantry and the divider hanging from it. ----

    fun drawGantry() {
        truss(12.0, RAIL_T, 1268.0, RAIL_B, 16.0); stroke(0.7, 1.1)
        truss(12.0, RAIL_B, 24.0, FLOOR, 16.0); stroke(0.6, 1.1)
        truss(1256.0, RAIL_B, 1268.0, FLOOR, 16.0); stroke(0.6, 1.1)
        // Ground: a line and the drawing's hatching under it.
        pen.beginPath()
        line(0.0, FLOOR, W, FLOOR)
        var x = 4.0
        while (x < W) {
            line(x, FLOOR + 1, x - 8, FLOOR + 9)
            x += 14
        }
        stroke(0.55, 1.0)
    }

    fun drawDivider(xd: Double) {
        pen.beginPath()
        pen.rect(xd - 16, RAIL_B + 2, 32.0, 8.0)
        pen.moveTo(xd - 6, RAIL_B); pen.arc(xd - 9, RAIL_B, 3.0, 0.0, 2 * PI)
        pen.moveTo(xd + 12, RAIL_B); pen.arc(xd + 9, RAIL_B, 3.0, 0.0, 2 * PI)
        stroke(0.9, 1.3)
        truss(xd - 6, RAIL_B + 10, xd + 6, FLOOR - 6, 16.0)
        stroke(0.95, 1.5)
    }

    fun drawPushers(t: Double, xd: Double) {
        val v = (dividerAt(t + 8) - dividerAt(t - 8)) / 16
        val amp = smooth(abs(v) / 0.15)
        for (f in SIDES) {
            val pushing = sign(v) == f
            val lean = amp * (if (pushing) 0.45 else -0.2)
            val face = xd - f * 6
            val x = face - f * (0.24 * U + TORSO * sin(lean))
            val P = pose(pusher, x = x, f = f, lean = lean, amp = amp, walk = f * xd / 14, bob = 0.0)
            val hy = FLOOR - 0.6 * U
            drawPerson(P, handA.set(face, hy), handB.set(face, hy + 8))
        }
    }

    // ---- The tower crane on the left and its signalman. ----

    fun drawCrane(t: Double): Crane {
        val k = crane(t, craneState)
        // Mast, jib, counter-jib, apex and ties, counterweight, cab.
        truss(MAST - 7, JIB_B, MAST + 7, FLOOR, 14.0); stroke(0.75, 1.1)
        truss(28.0, JIB_T, 400.0, JIB_B, 14.0); stroke(0.75, 1.1)
        pen.beginPath()
        line(MAST, 160.0, MAST - 7, JIB_T); line(MAST, 160.0, MAST + 7, JIB_T)
        line(MAST, 160.0, 400.0, JIB_T); line(MAST, 160.0, 28.0, JIB_T)
        pen.rect(30.0, JIB_B, 22.0, 18.0)
        pen.rect(MAST + 7, JIB_B, 16.0, 14.0)
        stroke(0.7, 1.1)
        // Trolley, cable, hook.
        pen.beginPath()
        pen.rect(k.tx - 8, JIB_B, 16.0, 6.0)
        line(k.tx, JIB_B + 6, k.tx, k.hy)
        line(k.tx, k.hy, k.tx, k.hy + 4)
        pen.moveTo(k.tx + 3, k.hy + 7); pen.arc(k.tx, k.hy + 7, 3.0, 0.0, PI)
        stroke(0.9, 1.2)
        // Load, slings, pile and stack.
        pen.beginPath()
        if (k.carrying) {
            val by = k.hy + 6 + SLING
            line(k.tx, k.hy + 6, k.tx - BW / 2, by); line(k.tx, k.hy + 6, k.tx + BW / 2, by)
            block(k.tx - BW / 2, by, BW, BH)
        }
        val settled = if (k.u >= 3000) k.onStack + 1 else k.onStack
        for (i in 0 until settled) block(STACK_X - BW / 2, FLOOR - BH * (i + 1), BW, BH)
        stroke(0.95, 1.4)
        if (k.u < 700) {
            pen.beginPath(); block(PILE_X - BW / 2, FLOOR - BH, BW, BH); stroke(0.95, 1.4)
        } else if (k.u > 3600) {
            pen.beginPath(); block(PILE_X - BW / 2, FLOOR - BH, BW, BH); stroke(0.95 * clamp01((k.u - 3600) / 400), 1.4)
        }
        // The previous stack of three, fading as the next round begins.
        if (k.onStack == 0 && k.c > 0 && k.u < 600) {
            pen.beginPath()
            for (i in 0 until 3) block(STACK_X - BW / 2, FLOOR - BH * (i + 1), BW, BH)
            stroke(0.95 * (1 - k.u / 600), 1.4)
        }
        // A thud where the block lands.
        val thud = k.u - 2900
        if (thud >= 0 && thud < 320) {
            val y = FLOOR - BH * k.onStack
            val s = decelerate(thud / 320)
            pen.beginPath()
            for (d in SIDES) {
                line(STACK_X + d * (BW / 2 + 4 + 14 * s), y - 2, STACK_X + d * (BW / 2 + 12 + 14 * s), y - 6 - 4 * s)
                line(STACK_X + d * (BW / 2 + 4 + 18 * s), y - 1, STACK_X + d * (BW / 2 + 14 + 18 * s), y - 1)
            }
            pen.stroke(0.9 * (1 - thud / 320), 1.2, Ink.CROWN)
        }
        return k
    }

    fun drawSignalman(t: Double, k: Crane) {
        val u = k.u
        val towardStack = u >= 1400 && u < 2300
        val f = if (towardStack) -1.0 else 1.0
        val P = pose(signalman, x = SIGNALMAN_X, f = f, lean = 0.0, nod = -0.12)
        val a = if ((u < 600) || (u >= 2300 && u < 2900)) {
            1.0 + 0.18 * sin(t / 80) // down, down
        } else if ((u >= 600 && u < 800) || (u >= 2900 && u < 3100)) {
            -1.45 // hold
        } else if ((u >= 800 && u < 1400) || (u >= 3100 && u < 3600)) {
            -1.25 + 0.35 * sin(t / 70) // up, up
        } else {
            -0.25 + 0.2 * sin(t / 110) // this way
        }
        val hand = handA.set(P.sx + cos(a) * f * REACH * 0.95, P.sy + sin(a) * REACH * 0.95)
        drawPerson(P, hand, null)
    }

    // ---- The frame on the right: one holds it, one hammers it from a ladder. ----

    fun drawRightCrew(t: Double) {
        val f = -1.0
        val P = pose(hammerer, x = LADDER_X, f = f, surface = RUNG, lean = 0.12)
        val impactX = tipX(P, B_HIT)
        val impactY = tipY(P, B_HIT)
        val right = impactX - 3
        val fw = FRAME_W
        val fb = pose(standing, x = 0.0, f = 1.0).sy - REACH * 0.92
        val ft = fb - FRAME_H
        val left = right - fw

        // Ladder.
        pen.beginPath()
        line(LADDER_X - LADDER_HALF, FLOOR, LADDER_X, LADDER_TOP)
        line(LADDER_X + LADDER_HALF, FLOOR, LADDER_X, LADDER_TOP)
        for (y in RUNGS) {
            val s = LADDER_HALF * (y - LADDER_TOP) / (FLOOR - LADDER_TOP)
            line(LADDER_X - s, y, LADDER_X + s, y)
        }
        stroke(0.8, 1.2)

        // Hits so far, and the frame they have built this round.
        val n = floor((t - T_HAM) / HAM_PERIOD).toInt()
        val lastHit = hitAt(n)
        pen.beginPath()
        pen.rect(left, fb, fw, 6.0)
        stroke(0.95, 1.4)
        if (n >= 1) {
            val c = floor((n - 1) / 6.0).toInt()
            val k = n - 6 * c
            val fade = if (k == 6) 1 - clamp01((t - hitAt(6 * c + 6)) / 400) else 1.0
            val flash = if (k == 6) exp(-(t - hitAt(6 * c + 6)) / 120) else 0.0
            edge(0, right, fb, right, ft)
            edge(1, right, ft, left, ft)
            edge(2, left, ft, left, fb)
            edge(3, left, fb, right, ft)
            pen.beginPath()
            for (i in 0 until 4) {
                if (i >= min(k, 4)) continue
                val p = prog(t, hitAt(6 * c + 1 + i), 180.0, decelerate)
                val e = 4 * i
                line(edges[e], edges[e + 1], lerp(edges[e], edges[e + 2], p), lerp(edges[e + 1], edges[e + 3], p))
            }
            stroke(fade * (0.95 + 0.05 * flash), 1.6 + 1.4 * flash)
        }

        // The holder, under the frame, jolted by each blow.
        val jolt = 2.5 * exp(-(t - lastHit) / 70)
        val H = pose(holder, x = (left + right) / 2 - 4, f = 1.0, nod = -0.25)
        drawPerson(
            H,
            handA.set((left + right) / 2 - 12, fb + 3 + jolt, 1.0),
            handB.set((left + right) / 2 + 6, fb + 3 + jolt, 1.0),
        )

        // The hammerer: the swing, the handle and the head.
        val b = hammerAngle(t)
        val handX = atX(P, b)
        val handY = atY(P, b)
        val endX = tipX(P, b)
        val endY = tipY(P, b)
        drawPerson(P, handA.set(handX, handY), handB.set(LADDER_X + 6, LADDER_TOP + 6))
        val nx = -sin(b) * f // perpendicular to the handle, for the head
        val ny = cos(b)
        pen.beginPath()
        line(handX, handY, endX, endY)
        stroke(1.0, 1.8)
        pen.beginPath()
        line(endX - nx * 6, endY - ny * 6, endX + nx * 6, endY + ny * 6)
        stroke(1.0, 4.0)

        // Sparks from the last two blows.
        for (back in 0..1) {
            val m = n - back // the page's `for (const m of [n, n - 1])`
            val age = t - hitAt(m)
            if (m < 1 || age < 0 || age > 480) continue
            pen.beginPath()
            for (i in 0 until 11) {
                val a = -0.8 + (hash((m * 17 + i).toDouble()) - 0.5) * 2.4
                val sp = 0.08 + 0.16 * hash((m * 31 + i * 7).toDouble())
                val vx = cos(a) * sp
                val vy = sin(a) * sp
                val g = 0.0009
                val px = impactX + vx * age
                val py = impactY + vy * age + 0.5 * g * age * age
                val qa = max(0.0, age - 28)
                line(impactX + vx * qa, impactY + vy * qa + 0.5 * g * qa * qa, px, py)
            }
            pen.stroke((1 - age / 480).pow(1.5), 1.3, Ink.CROWN)
        }
    }

    private fun edge(i: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        edges[4 * i] = x0
        edges[4 * i + 1] = y0
        edges[4 * i + 2] = x1
        edges[4 * i + 3] = y1
    }

    // The page's at(b) and tip(b): the hand, and the hammer's head, for a swing angle b.
    private fun atX(P: Pose, b: Double) = P.sx + cos(b) * P.f * HAND_R
    private fun atY(P: Pose, b: Double) = P.sy + sin(b) * HAND_R
    private fun tipX(P: Pose, b: Double) = P.sx + cos(b) * P.f * (HAND_R + HANDLE)
    private fun tipY(P: Pose, b: Double) = P.sy + sin(b) * (HAND_R + HANDLE)

    // ---- The whole shield. ----

    fun drawShield(t: Double) {
        val xd = dividerAt(t)
        pen.ground()

        pen.haze(prog(t, 0.0, HAZE_IN_MS, decelerate))

        // The site opens as a circle from the centre of the screen.
        val open = prog(t, 0.0, REVEAL_MS, decelerate)
        val rr = REVEAL_FROM + REVEAL_GROWTH * open
        pen.save()
        if (open < 1) pen.clipCircle(CX, CY, rr)
        drawGantry()
        val k = drawCrane(t)
        drawSignalman(t, k)
        drawRightCrew(t)
        drawDivider(xd)
        drawPushers(t, xd)
        pen.restore()
        if (open < 1) {
            pen.beginPath()
            pen.arc(CX, CY, rr, 0.0, 2 * PI)
            pen.stroke(0.8 * (1 - open), 1.5, Ink.CROWN)
        }

        pen.caption(caption, W / 2, CAPTION_Y, CAPTION_SIZE, CAPTION_WHITE * prog(t, 0.0, CAPTION_IN_MS))
    }

    /** `cubicBezier(x1, y1, x2, y2)` of the page: CSS easing, solved by 28 bisection steps. */
    class CubicBezier(x1: Double, y1: Double, x2: Double, y2: Double) {
        private val cx = 3 * x1
        private val bx = 3 * (x2 - x1) - cx
        private val ax = 1 - cx - bx
        private val cy = 3 * y1
        private val by = 3 * (y2 - y1) - cy
        private val ay = 1 - cy - by

        private fun sx(t: Double) = ((ax * t + bx) * t + cx) * t
        private fun sy(t: Double) = ((ay * t + by) * t + cy) * t

        operator fun invoke(x: Double): Double {
            if (x <= 0) return 0.0
            if (x >= 1) return 1.0
            var lo = 0.0
            var hi = 1.0
            var t = x
            for (i in 0 until BISECTIONS) {
                if (sx(t) < x) lo = t else hi = t
                t = (lo + hi) / 2
            }
            return sy(t)
        }
    }

    companion object {
        // ---- The car's screen, in dp (2560 x 1600 px at density 2). ----
        const val W = 1280.0
        const val H = 800.0
        const val FLOOR = 660.0
        const val RAIL_T = 92.0
        const val RAIL_B = 102.0
        const val CX = 640.0
        const val CY = 400.0

        /** The firmware's narrow pane on either side. */
        const val NARROW_LEFT = 434.0
        const val NARROW_RIGHT = 846.0

        /** The caption: `fillText(CAPTION, W / 2, 748)`, 18 units, white at 0.9, after 160 ms. */
        const val CAPTION_Y = 748.0
        const val CAPTION_SIZE = 18.0
        const val CAPTION_WHITE = 0.9
        const val CAPTION_IN_MS = 160.0

        /** The page's three blues, as its `HALO`, `CORE` and `CROWN` arrays. */
        val HALO = intArrayOf(67, 159, 252)
        val CORE = intArrayOf(128, 190, 255)
        val CROWN = intArrayOf(200, 228, 255)

        /** Half the screen's diagonal: the field gradient's radius. */
        val HALF_DIAG = hypot(W / 2, H / 2)

        /**
         * The field every line is stroked with: bright at the centre of the screen, dim at the
         * edges, four opaque stops from the centre out to [HALF_DIAG].
         */
        val FIELD_STOPS = doubleArrayOf(0.0, 0.3, 0.6, 1.0)
        val FIELD_COLORS = intArrayOf(
            rgb(CORE, 1.0),
            rgb(mix(CORE, HALO, 0.5), 0.92),
            rgb(HALO, 0.62),
            rgb(HALO, 0.3),
        )

        /** The haze: [HALO] at these alphas out to [HAZE_RADIUS], squashed vertically to [HAZE_SQUASH]. */
        const val HAZE_RADIUS = 720.0
        const val HAZE_SQUASH = 0.64
        const val HAZE_IN_MS = 600.0
        val HAZE_STOPS = doubleArrayOf(0.0, 0.55, 1.0)
        val HAZE_ALPHAS = doubleArrayOf(0.2, 0.07, 0.0)

        /** The reveal: a circle from the centre, [REVEAL_FROM] + [REVEAL_GROWTH] x the eased progress. */
        const val REVEAL_MS = 650.0
        const val REVEAL_FROM = 40.0
        const val REVEAL_GROWTH = 860.0

        /** The moment a still frame shows when animations are off: a blow landing, the divider on the left third. */
        const val STILL_T = 2150.0

        // ---- The divider: born in the middle, then ⅓ → ½ → ⅔ → ½ every four seconds. ----
        const val T_MOVE = 1000.0
        const val CYCLE = 4000.0

        // ---- People. ----
        const val U = 104.0
        const val THIGH = 0.25 * U
        const val SHIN = 0.24 * U
        const val TORSO = 0.3 * U
        const val NECK = 0.045 * U
        const val HEAD_R = 0.075 * U
        const val UPPER = 0.17 * U
        const val FORE = 0.17 * U
        const val REACH = UPPER + FORE

        // ---- The tower crane on the left and its signalman. ----
        const val PILE_X = 335.0
        const val STACK_X = 150.0
        const val BW = 64.0
        const val BH = 40.0
        const val SLING = 22.0
        const val HOOK_TOP = 290.0
        const val JIB_T = 200.0
        const val JIB_B = 212.0
        const val MAST = 64.0
        const val CRANE_LEAD = 1100.0
        const val CRANE_CYCLE = 4400.0
        const val SIGNALMAN_X = 245.0

        // ---- The frame on the right. ----
        const val LADDER_X = 1170.0
        const val LADDER_TOP = 500.0
        const val RUNG = 530.0
        const val LADDER_HALF = 30.0
        val RUNGS = doubleArrayOf(640.0, 618.0, 596.0, 574.0, 552.0, RUNG)
        const val T_HAM = -250.0
        const val HAM_PERIOD = 600.0
        const val B_HIT = 0.35
        const val B_UP = -2.1
        const val HAND_R = 0.29 * U
        const val HANDLE = 0.22 * U
        const val FRAME_W = 150.0
        const val FRAME_H = 150.0

        const val BISECTIONS = 28

        private val SIDES = doubleArrayOf(1.0, -1.0)

        fun clamp01(v: Double) = max(0.0, min(1.0, v))

        fun smooth(x0: Double): Double {
            val x = clamp01(x0)
            return x * x * (3 - 2 * x)
        }

        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

        fun hash(n: Double): Double {
            val s = sin(n * 12.9898) * 43758.5453
            return s - floor(s)
        }

        /** JavaScript's `Math.round`: halves go up. */
        private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

        private fun mix(a: IntArray, b: IntArray, t: Double) = DoubleArray(3) { a[it] + (b[it] - a[it]) * t }

        private fun rgb(c: IntArray, k: Double) = rgb(DoubleArray(3) { c[it].toDouble() }, k)

        /** The page's `rgb(c, k)`: each channel times [k], rounded as JavaScript rounds, opaque. */
        private fun rgb(c: DoubleArray, k: Double): Int =
            (0xFF shl 24) or (jsRound(c[0] * k) shl 16) or (jsRound(c[1] * k) shl 8) or jsRound(c[2] * k)

        val standard = CubicBezier(0.4, 0.0, 0.2, 1.0)
        val decelerate = CubicBezier(0.05, 0.7, 0.1, 1.0)

        fun prog(t: Double, t0: Double, d: Double, ease: CubicBezier = standard) = ease(clamp01((t - t0) / d))

        /** Where the divider stands [t] ms into the wait. */
        fun dividerAt(t: Double): Double {
            if (t < T_MOVE) return CX
            val u = (t - T_MOVE) % CYCLE
            fun leg(a: Double, b: Double, t0: Double, d: Double) = a + (b - a) * standard(clamp01((u - t0) / d))
            if (u < 900) return leg(CX, NARROW_LEFT, 0.0, 900.0)
            if (u < 1100) return NARROW_LEFT
            if (u < 2900) return leg(NARROW_LEFT, NARROW_RIGHT, 1100.0, 1800.0)
            if (u < 3100) return NARROW_RIGHT
            return leg(NARROW_RIGHT, CX, 3100.0, 900.0)
        }

        /**
         * Where the crane is [t] ms into the wait: down to the pile, up with a block, across, down
         * onto the stack, up and back, every [CRANE_CYCLE]; the stack is three blocks tall.
         */
        fun crane(t: Double, k: Crane = Crane()): Crane {
            val tc = t + CRANE_LEAD
            val c = floor(tc / CRANE_CYCLE).toInt()
            val u = tc % CRANE_CYCLE
            val onStack = c % 3
            val pickY = FLOOR - BH - SLING - 6
            val placeY = FLOOR - BH * (onStack + 1) - SLING - 6
            var tx = PILE_X
            var hy = HOOK_TOP
            fun seg(a: Double, b: Double, t0: Double, d: Double) = lerp(a, b, standard(clamp01((u - t0) / d)))
            if (u < 600) {
                hy = seg(HOOK_TOP, pickY, 0.0, 600.0)
            } else if (u < 800) {
                hy = pickY
            } else if (u < 1400) {
                hy = seg(pickY, HOOK_TOP, 800.0, 600.0)
            } else if (u < 2300) {
                tx = seg(PILE_X, STACK_X, 1400.0, 900.0)
            } else if (u < 3100) {
                tx = STACK_X
                hy = if (u < 2900) seg(HOOK_TOP, placeY, 2300.0, 600.0) else placeY
            } else if (u < 3600) {
                tx = STACK_X
                hy = seg(placeY, HOOK_TOP, 3100.0, 500.0)
            } else {
                tx = seg(STACK_X, PILE_X, 3600.0, 800.0)
            }
            k.c = c
            k.u = u
            k.onStack = onStack
            k.tx = tx
            k.hy = hy
            k.carrying = u >= 700 && u < 3000
            return k
        }

        /** The hammer's swing [t] ms into the wait: up in 0.7 of a period, down in 0.3, a blow each [HAM_PERIOD]. */
        fun hammerAngle(t: Double): Double {
            val ph = (((t - T_HAM) % HAM_PERIOD) + HAM_PERIOD) % HAM_PERIOD / HAM_PERIOD
            return if (ph < 0.7) {
                lerp(B_HIT, B_UP, standard(ph / 0.7))
            } else {
                lerp(B_UP, B_HIT, ((ph - 0.7) / 0.3).pow(2))
            }
        }

        /** When blow [m] lands. */
        fun hitAt(m: Int): Double = T_HAM + m * HAM_PERIOD

        /** An arm from the shoulder to a hand: the elbow and the hand, the hand pulled in to the arm's reach. */
        fun ik(
            sx: Double,
            sy: Double,
            tx0: Double,
            ty0: Double,
            bend: Double,
            out: DoubleArray = DoubleArray(4),
        ): DoubleArray {
            var tx = tx0
            var ty = ty0
            val dx = tx - sx
            val dy = ty - sy
            var d = hypot(dx, dy)
            val max = REACH - 0.2
            if (d > max) {
                tx = sx + dx / d * max
                ty = sy + dy / d * max
                d = max
            }
            if (d < 1e-3) d = 1e-3
            val ang = atan2(ty - sy, tx - sx)
            val A = acos(max(-1.0, min(1.0, (UPPER * UPPER + d * d - FORE * FORE) / (2 * UPPER * d))))
            val e = ang + bend * A
            out[0] = sx + UPPER * cos(e)
            out[1] = sy + UPPER * sin(e)
            out[2] = tx
            out[3] = ty
            return out
        }

        /**
         * Joints of a figure: hip at [x], feet on [surface], leaning [lean] rad forward, legs in gait
         * [walk] x [amp].
         */
        fun pose(
            P: Pose,
            x: Double,
            f: Double,
            lean: Double = 0.0,
            amp: Double = 0.0,
            walk: Double = 0.0,
            bob: Double = 0.0,
            nod: Double = 0.0,
            surface: Double = FLOOR,
        ): Pose {
            P.f = f
            P.amp = amp
            P.walk = walk
            P.nod = nod
            P.lean = lean
            for (l in 0..1) {
                val off = if (l == 0) 0.0 else PI
                P.th[l] = 0.48 * amp * sin(walk + off)
                P.kn[l] = 0.8 * amp * max(0.0, sin(walk + off + 1.2))
            }
            val drop0 = THIGH * cos(P.th[0]) + SHIN * cos(P.th[0] - P.kn[0])
            val drop1 = THIGH * cos(P.th[1]) + SHIN * cos(P.th[1] - P.kn[1])
            P.hx = x
            P.hy = surface - max(drop0, drop1) + bob
            P.nx = P.hx + TORSO * sin(lean) * f
            P.ny = P.hy - TORSO * cos(lean)
            val hd = lean + nod
            P.sx = P.nx
            P.sy = P.ny + 0.035 * U
            P.cx = P.nx + (NECK + HEAD_R) * sin(hd) * f
            P.cy = P.ny - (NECK + HEAD_R) * cos(hd)
            return P
        }
    }
}
