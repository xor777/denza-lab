package dev.denza.apps.feature.split.pill

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The design's tweens (README, "Motion"): `out`, `outQ` and `inOut` are the page's own functions;
 * every other name is a `cubic-bezier` of the spec's `ease` table or a CSS keyword with its
 * standard curve.
 */
internal object PillEase {
    fun interface Curve {
        fun at(t: Double): Double
    }

    val LINEAR = Curve { t -> t }
    val OUT = Curve { t -> 1 - (1 - t).pow(3) }
    val OUT_Q = Curve { t -> 1 - (1 - t) * (1 - t) }
    val IN_OUT = Curve { t -> if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2 }

    private val table = mapOf(
        "std" to SplitPillSpec.Ease.STD,
        "settle" to SplitPillSpec.Ease.SETTLE,
        "pop" to SplitPillSpec.Ease.POP,
        "expo" to SplitPillSpec.Ease.EXPO,
        "sink" to SplitPillSpec.Ease.SINK,
        "fall" to SplitPillSpec.Ease.FALL,
    )

    private val made = HashMap<String, Curve>()

    /** The ease a spec motion names. Made once per name: nothing is parsed on a frame. */
    fun named(name: String): Curve = synchronized(made) {
        made.getOrPut(name) {
            when (name) {
                "out" -> OUT
                "outQ" -> OUT_Q
                "inOut" -> IN_OUT
                "linear" -> LINEAR
                else -> parse(table[name] ?: name)
            }
        }
    }

    /** A CSS easing: one of the five keywords or `cubic-bezier(x1, y1, x2, y2)`. */
    fun parse(css: String): Curve {
        val c = css.trim().lowercase(Locale.ROOT)
        return when (c) {
            "linear" -> LINEAR
            "ease" -> bezier(0.25, 0.1, 0.25, 1.0)
            "ease-in" -> bezier(0.42, 0.0, 1.0, 1.0)
            "ease-out" -> bezier(0.0, 0.0, 0.58, 1.0)
            "ease-in-out" -> bezier(0.42, 0.0, 0.58, 1.0)
            else -> {
                require(c.startsWith("cubic-bezier(") && c.endsWith(")")) { "ease: $css" }
                val p = c.substring(13, c.length - 1).split(",").map { it.trim().toDouble() }
                require(p.size == 4) { "ease: $css" }
                bezier(p[0], p[1], p[2], p[3])
            }
        }
    }

    /** CSS cubic-bezier: x(s) solved for t by Newton's method with a bisection fallback. */
    fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): Curve {
        val cx = 3 * x1
        val bx = 3 * (x2 - x1) - cx
        val ax = 1 - cx - bx
        val cy = 3 * y1
        val by = 3 * (y2 - y1) - cy
        val ay = 1 - cy - by
        return Curve { t ->
            if (t <= 0) return@Curve 0.0
            if (t >= 1) return@Curve 1.0
            var s = t
            for (k in 0 until 8) {
                val x = ((ax * s + bx) * s + cx) * s - t
                if (abs(x) < 1e-7) return@Curve ((ay * s + by) * s + cy) * s
                val d = (3 * ax * s + 2 * bx) * s + cx
                if (abs(d) < 1e-6) break
                s -= x / d
            }
            var lo = 0.0
            var hi = 1.0
            s = t
            for (k in 0 until 40) {
                val x = ((ax * s + bx) * s + cx) * s
                if (abs(x - t) < 1e-7) break
                if (x < t) lo = s else hi = s
                s = (lo + hi) / 2
            }
            ((ay * s + by) * s + cy) * s
        }
    }
}

/** A spring's constants: ω = (z < 1 ? 4.6 / z : 6.6) / seconds, stiffness ω², damping 2zω. */
internal class PillSpring(val ms: Double, val z: Double) {
    val omega: Double = (if (z < 1) 4.6 / z else 6.6) / (ms / 1000.0)
    val k: Double = omega * omega
    val c: Double = 2 * z * omega

    /** The first peak past the target of an underdamped spring released from rest, as a share. */
    fun overshoot(): Double = if (z >= 1) 0.0 else exp(-Math.PI * z / sqrt(1 - z * z))

    companion object {
        private val made = HashMap<PillSpringSpec, PillSpring>()

        fun of(spec: PillSpringSpec): PillSpring = synchronized(made) {
            made.getOrPut(spec) { PillSpring(spec.ms, spec.z) }
        }
    }
}

/**
 * One moving number, the page's `V`: a spring or a tween can be given at any moment and it leaves
 * from where the value is with the speed it has, so nothing jumps. A spring is integrated by
 * semi-implicit Euler in `ceil(dt * rate)` equal steps; a tween starts from the value at its first
 * step on or after its delay, and a tween given while the value moves carries that speed on and
 * lets it die out over the tween (`v0 · τ · p · (1 − p)²`), so a retarget never kinks. While a delay
 * runs the value holds still.
 */
internal class PillValue(var v: Double, private val eps: Double) {
    var vel = 0.0
        private set
    private var mode = IDLE
    private var target = v
    private var k = 0.0
    private var c = 0.0
    private var at = 0.0
    private var ms = 1.0
    private var from = 0.0
    private var seed = 0.0
    private var startedAt = Double.NaN
    private var curve: PillEase.Curve = PillEase.LINEAR

    val active: Boolean get() = mode != IDLE

    /** Where it is going: the target while it moves, else where it stands. */
    val goal: Double get() = if (mode != IDLE) target else v

    fun set(to: Double): PillValue {
        v = to
        vel = 0.0
        mode = IDLE
        target = to
        return this
    }

    fun spring(to: Double, spring: PillSpring, delayMs: Double, nowMs: Double, velocity: Double? = null): PillValue {
        mode = SPRING
        target = to
        k = spring.k
        c = spring.c
        at = nowMs + delayMs
        if (velocity != null) vel = velocity
        return this
    }

    fun tween(to: Double, tween: PillTween, nowMs: Double, delayMs: Double = tween.delay): PillValue {
        // A delayed tween holds the value still first, so only an immediate one inherits its speed.
        seed = if (delayMs <= 0 && mode != IDLE) vel else 0.0
        mode = TWEEN
        target = to
        ms = max(1.0, tween.ms)
        curve = PillEase.named(tween.ease)
        at = nowMs + delayMs
        startedAt = Double.NaN
        return this
    }

    /** Stop where it is: the value stays, its speed is dropped. */
    fun hold(): PillValue {
        vel = 0.0
        mode = IDLE
        target = v
        return this
    }

    /** One frame: [dt] seconds since the last, [nowMs] the frame's time. */
    fun step(dt: Double, nowMs: Double) {
        if (mode == IDLE || nowMs < at) return
        if (mode == SPRING) {
            val n = max(1, ceil(dt * SplitPillSpec.RATE).toInt())
            val h = dt / n
            for (i in 0 until n) {
                val a = -k * (v - target) - c * vel
                vel += a * h
                v += vel * h
            }
            if (abs(v - target) < eps && abs(vel) < eps * 8) {
                v = target
                vel = 0.0
                mode = IDLE
            }
        } else {
            if (startedAt.isNaN()) {
                startedAt = nowMs
                from = v
            }
            val p = min(1.0, (nowMs - startedAt) / ms)
            val q = 1 - p
            val nv = from + (target - from) * curve.at(p) + seed * (ms / 1000) * p * q * q
            vel = if (dt > 0) (nv - v) / dt else 0.0
            v = nv
            if (p >= 1) {
                vel = 0.0
                mode = IDLE
            }
        }
    }

    private companion object {
        const val IDLE = 0
        const val SPRING = 1
        const val TWEEN = 2
    }
}

/**
 * The pull's arithmetic (README, "Pull, under the finger", "past the ends", "release"), pure so
 * that a test can hold it to the page. Lengths are dp; `p` is 0 at the handle and 1 at the open
 * pill.
 */
internal object PillPull {
    private val H0 = SplitPillSpec.Handle.H
    private val H1 = SplitPillSpec.Pill.OPEN_H
    private val W0 = SplitPillSpec.Handle.W

    /** Above the open pill the top edge goes on by cap · tanh(factor · x / cap); below p 0 nothing. */
    fun band(extra: Double): Double {
        if (extra <= 0) return 0.0
        val cap = SplitPillSpec.Pull.BAND_CAP
        return cap * tanh(SplitPillSpec.Pull.BAND_FACTOR * extra / cap)
    }

    /**
     * The pill's progress for the finger's [raw] progress (the page's `pOf`): banded past 1; below
     * [floor] held there, or banded below it when the pill may [give] (a pull down over an empty
     * pane, which springs back).
     */
    fun progress(raw: Double, floor: Double, give: Boolean): Double {
        val travel = SplitPillSpec.Pull.TRAVEL
        if (raw > 1) return 1 + band((raw - 1) * travel) / travel
        if (raw < floor) return if (give) floor - band((floor - raw) * travel) / travel else floor
        return raw
    }

    /** The height at progress [q] (the page's `hOf`): in step to the open pill, then a dp per dp. */
    fun heightOf(q: Double): Double =
        if (q <= 1) H0 + (H1 - H0) * q else H1 + (q - 1) * SplitPillSpec.Pull.TRAVEL

    /**
     * The width at progress [q] toward a row [rowW] wide (the page's `wAt`): in step from the
     * handle's; after a catch at ([anchorQ], [anchorW]) the line bends through where it was caught.
     */
    fun widthOf(q: Double, rowW: Double, anchorQ: Double = Double.NaN, anchorW: Double = 0.0): Double {
        val e = q.coerceIn(0.0, 1.0)
        if (anchorQ.isNaN()) return W0 + (rowW - W0) * e
        val e0 = anchorQ.coerceIn(0.0, 1.0)
        if (e >= e0) return if (e0 >= 1) anchorW else anchorW + (rowW - anchorW) * (e - e0) / (1 - e0)
        return W0 + (anchorW - W0) * e / e0
    }

    /** The finger's progress that holds the pill at [height] (the page's `rawOf`), past 1 unbanded. */
    fun rawOfHeight(height: Double): Double {
        if (height <= H1) return ((height - H0) / (H1 - H0)).coerceIn(0.0, 1.0)
        val cap = SplitPillSpec.Pull.BAND_CAP
        val b = ((height - H1) / cap).coerceIn(0.0, 0.999)
        val back = cap / SplitPillSpec.Pull.BAND_FACTOR * 0.5 * kotlin.math.ln((1 + b) / (1 - b))
        return 1 + back / SplitPillSpec.Pull.TRAVEL
    }

    /** Item [i] of [n] fades in over p from + i · s to that + each, s = (to - from - each) / (n - 1). */
    fun revealOf(i: Int, n: Int, p: Double): Double {
        val from = SplitPillSpec.Pull.ITEMS_FROM
        val each = SplitPillSpec.Pull.ITEMS_EACH
        val s = if (n > 1) (SplitPillSpec.Pull.ITEMS_TO - from - each) / (n - 1) else 0.0
        val start = from + i * s
        return ((p - start) / each).coerceIn(0.0, 1.0)
    }

    /** The row drawn at the pill's own scale: min(1, height / open, width / row). */
    fun rowScale(h: Double, w: Double, rowW: Double): Double = min(1.0, min(h / H1, w / rowW))

    /** Whether a release opens: p plus what the speed (dp/s up) adds over `project` ms is past half. */
    fun opens(p: Double, speedDpPerS: Double): Boolean =
        p + speedDpPerS / SplitPillSpec.Pull.TRAVEL * SplitPillSpec.Pull.PROJECT / 1000 >=
            SplitPillSpec.Pull.HALF

    /**
     * The finger's speed up, dp/s: a least-squares parabola through the samples of the last
     * `window` ms before the last one, its slope at the last one; a line while there are fewer than
     * four. [t] (ms) and [y] (dp, down) are parallel, oldest first; [n] of them are valid. The
     * page's `fingerSpeed`, number for number.
     */
    fun fingerSpeed(t: DoubleArray, y: DoubleArray, n: Int): Double {
        if (n < 2) return 0.0
        val tl = t[n - 1]
        var first = n - 1
        while (first > 0 && tl - t[first - 1] <= SplitPillSpec.Pull.WINDOW) first--
        if (n - first < 2) first = n - 2
        val m = n - first
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var y0 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in first until n) {
            val x = (t[i] - tl) / 1000
            val v = -y[i]
            val x2 = x * x
            s1 += x
            s2 += x2
            s3 += x2 * x
            s4 += x2 * x2
            y0 += v
            y1 += x * v
            y2 += x2 * v
        }
        if (m >= 4) {
            val det = m * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s2 * s3) + s2 * (s1 * s3 - s2 * s2)
            if (abs(det) > 1e-18) {
                return (m * (y1 * s4 - s3 * y2) - y0 * (s1 * s4 - s2 * s3) + s2 * (s1 * y2 - s2 * y1)) / det
            }
        }
        val dl = m * s2 - s1 * s1
        return if (abs(dl) > 1e-12) (m * y1 - s1 * y0) / dl else 0.0
    }

    /**
     * The most of [v] a spring from [x] to [target] can be handed without passing [edge], found on
     * the springs' own integrator: a bound on the overshoot, not on the drawn value. The page's
     * `boundVel`, number for number.
     */
    fun boundVel(v: Double, x: Double, target: Double, spring: PillSpring, edge: Double): Double {
        val dir = sign(edge - target).takeIf { it != 0.0 } ?: sign(target - x).takeIf { it != 0.0 } ?: 1.0
        if (v * dir <= 0) return v
        fun past(u: Double): Double {
            var yy = x
            var vel = u * dir
            var far = yy * dir
            val hh = 1 / SplitPillSpec.RATE
            var i = 0
            while (i < SplitPillSpec.RATE * 2) {
                vel += (-spring.k * (yy - target) - spring.c * vel) * hh
                yy += vel * hh
                if (yy * dir > far) far = yy * dir else if (vel * dir < 0 && i > 2) break
                i++
            }
            return far - edge * dir
        }
        if (past(abs(v)) <= 0) return v
        if (past(0.0) > 0) return 0.0
        var lo = 0.0
        var hi = abs(v)
        repeat(30) {
            val mid = (lo + hi) / 2
            if (past(mid) <= 0) lo = mid else hi = mid
        }
        return lo * dir
    }
}
