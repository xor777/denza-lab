package dev.denza.apps.feature.cluster.dashboard

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Which way the pack is flowing, once the dead band and its hysteresis have had their say.
 *
 * Three states rather than a sign, because the third one is the whole point: inside the dead band
 * the hero and the band's body carry no colour at all. On a coast `POWER_KW` swings a couple of
 * kilowatts either way and a 13 mm numeral in the fovea was flickering between ink and blue.
 */
internal enum class ContourFlow { OUT, NEUTRAL, BACK }

/**
 * One critically damped second-order follower, integrated exactly.
 *
 * The gap between 3 Hz of data and 30 frames a second is not closed by waiting and not by linear
 * interpolation - that reads as "arrived and stopped" - but by a spring with no overshoot in it. A
 * critically damped system is the only one that reaches its target as fast as it can without going
 * past it, which is what a needle should do and what a value that means something should not.
 *
 * **Why the step is analytic rather than Euler.** The band's rise is 120 ms and a frame is 33 ms, so
 * `ω·dt` is above one and explicit Euler diverges - the panel would oscillate harder the faster the
 * pedal moved. Between two samples the target is constant, so the exact solution of the homogeneous
 * equation is available and is what is used:
 *
 *     d(t) = (A + B·t)·e^(-ω·t),  A = x₀ - target,  B = v₀ + ω·A
 *
 * Unconditionally stable, and correct at any frame rate, including the 5 fps the view drops to on a
 * parked car.
 *
 * **The asymmetry** is a VU meter's, and it is the reason there are two constants rather than one:
 * a hit is fast and a release is soft, so the panel answers the pedal immediately and does not
 * twitch when it comes off. Which one applies is decided by magnitude, not by sign - a swing from
 * 40 kW out to 40 kW back is a release followed by a hit, not one long fall.
 *
 * [seconds] means the time to cover nine tenths of a step from rest, which is a statement about the
 * response somebody can measure rather than about a coefficient inside it.
 */
internal class ContourFollower(
    private val riseSeconds: Float,
    private val fallSeconds: Float,
) {

    var value: Float = 0f
        private set

    private var velocity: Float = 0f

    fun step(target: Float, dt: Float) {
        if (dt <= 0f) return
        val seconds = if (abs(target) > abs(value)) riseSeconds else fallSeconds
        val omega = RESPONSE / seconds.coerceAtLeast(MIN_SECONDS)
        val a = value - target
        val b = velocity + omega * a
        val decay = exp(-omega * dt)
        value = target + (a + b * dt) * decay
        velocity = (b - omega * a - omega * b * dt) * decay
    }

    /** Arrive at [target] with no travel, which is what a value appearing from nothing does. */
    fun settle(target: Float) {
        value = target
        velocity = 0f
    }

    companion object {
        /**
         * `ω·t` at which a critically damped step from rest has covered nine tenths of its way:
         * the root of `(1 + u)·e^(-u) = 0.1`.
         */
        const val RESPONSE = 3.8897f

        /** Below this a follower is a teleport, and dividing by it is not worth the guard. */
        private const val MIN_SECONDS = 0.001f
    }
}

/**
 * Everything on the Contour that moves, and the three rules that stop it twitching.
 *
 * The panel is a still picture with four moving parts - the band's length, the hero's figure, the
 * glow's brightness and the engine's revolutions - and every coordinate on it is a constant. What
 * follows a reading is a length, an alpha and a glyph, and nothing else, which is the structural
 * half of the owner's original complaint about numbers jumping left and right.
 *
 * The three rules against jitter:
 *
 *  1. **a dead band of half a kilowatt** at zero, which is [dev.denza.apps.design.instrument
 *     .EnergyScale.FLOOR_KW]. A parked car reports single-kilowatt noise and the band must not
 *     breathe with it;
 *  2. **a neutral zone of three kilowatts** with three kilowatts of hysteresis around its own
 *     boundary, so the colour cannot change twice in a second on a coast (CRITIQUE M12);
 *  3. **rounding hysteresis of half a kilowatt** on the hero's figure, and the figure is rewritten
 *     at 2 Hz at most. A number that changes three times a second is a number nobody reads (m6).
 *
 * Deterministic in `dt`: nothing here reads a clock, so a test states a frame time and gets one
 * answer.
 */
internal class ContourMotion {

    private val band = ContourFollower(BAND_RISE_S, BAND_FALL_S)
    private val glow = ContourFollower(GLOW_S, GLOW_S)
    private val revolutions = ContourFollower(RPM_RISE_S, RPM_FALL_S)

    private var powerKnown = false
    private var rpmKnown = false

    private var peak = 0f
    private var peakHeldFor = 0f
    private var peakSeeded = false

    private var figureValue = 0
    private var figureSeeded = false
    private var figureAge = 0f

    private var flowState = ContourFlow.NEUTRAL

    /** The smoothed pack power the band's length and the hero's colour are taken from. */
    val powerKw: Float get() = band.value

    /**
     * The same signal, seen by the glow.
     *
     * A separate follower rather than a second reading of the first: at τ 1.5 s the pool of light
     * lags the band by more than a second, which is what makes it an ambient rather than a second
     * bar. The fourth board had it riding the band's own tip at 400 ms, and that put a 73 mm pool
     * through 50-100 mm of travel every time the pedal moved in a jam.
     */
    val glowKw: Float get() = glow.value

    val rpm: Float get() = revolutions.value

    /** Whether the band and the hero have a reading at all. */
    val powerReady: Boolean get() = powerKnown

    val rpmReady: Boolean get() = rpmKnown

    /**
     * The furthest the band's tip has been, or null while there is nothing worth marking.
     *
     * It is the smoothed value's own extreme rather than the raw sample's, so the mark is somewhere
     * the tip actually went: a peak the band never reached would be a claim about a reading nobody
     * saw drawn.
     */
    val peakKw: Float? get() = peak.takeIf { peakSeeded && abs(it) > NEUTRAL_KW }

    /** What the hero prints, in whole kilowatts, or null before the first reading. */
    val figure: Int? get() = figureValue.takeIf { figureSeeded }

    val flow: ContourFlow get() = flowState

    /**
     * One frame.
     *
     * A null reading is not a reading of zero: the followers are dropped rather than driven to
     * zero, so a value that comes back does not sweep in from wherever it happened to be left. The
     * Contour has one rule for a stale reading - it is removed after two seconds and its caption
     * stays - and by the time this sees a null, that rule has already fired.
     */
    fun step(powerKw: Float?, rpm: Float?, dt: Float) {
        if (powerKw == null) {
            forgetPower()
        } else {
            val target = if (abs(powerKw) <= FLOOR_KW) 0f else powerKw
            if (!powerKnown) {
                powerKnown = true
                band.settle(target)
                glow.settle(target)
            } else {
                band.step(target, dt)
                glow.step(target, dt)
            }
            holdPeak(band.value, dt)
            writeFigure(band.value, dt)
            flowState = flowOf(band.value, flowState)
        }

        if (rpm == null) {
            rpmKnown = false
            revolutions.settle(0f)
        } else if (!rpmKnown) {
            rpmKnown = true
            revolutions.settle(rpm)
        } else {
            revolutions.step(rpm, dt)
        }
    }

    private fun forgetPower() {
        powerKnown = false
        band.settle(0f)
        glow.settle(0f)
        peakSeeded = false
        peak = 0f
        peakHeldFor = 0f
        figureSeeded = false
        figureAge = 0f
        flowState = ContourFlow.NEUTRAL
    }

    /**
     * Three seconds where it landed, then sixty kilowatts a second back toward the tip.
     *
     * A peak on the wrong side of zero is not decayed across it: braking after pulling is a new
     * event, and a mark crawling through zero would draw a reading that never happened.
     */
    private fun holdPeak(value: Float, dt: Float) {
        val sameSide = peak >= 0f == value >= 0f
        when {
            !peakSeeded || !sameSide || abs(value) > abs(peak) -> {
                peak = value
                peakHeldFor = 0f
                peakSeeded = true
            }

            else -> {
                peakHeldFor += dt
                if (peakHeldFor > PEAK_HOLD_S) {
                    val fall = PEAK_DECAY_KW_S * dt
                    peak = if (abs(peak - value) <= fall) {
                        value
                    } else if (peak > value) {
                        peak - fall
                    } else {
                        peak + fall
                    }
                }
            }
        }
    }

    /**
     * The hero's glyph: at most twice a second, and never for half a kilowatt.
     *
     * Together the two rules mean the number changes when the driving changed. Either alone is not
     * enough - a 2 Hz figure with no hysteresis still alternates 33 and 34 on the boundary, and
     * hysteresis at frame rate is a number moving faster than anybody can read it.
     */
    private fun writeFigure(value: Float, dt: Float) {
        figureAge += dt
        if (figureSeeded && figureAge < FIGURE_INTERVAL_S) return
        figureAge = 0f
        val magnitude = abs(value)
        if (!figureSeeded) {
            figureValue = magnitude.roundToInt()
            figureSeeded = true
            return
        }
        // Ordinary rounding turns over half a kilowatt from the printed integer; the hysteresis is
        // the other half, so the reading has to be a whole kilowatt away before the glyph changes.
        if (abs(magnitude - figureValue) >= 0.5f + FIGURE_HYSTERESIS_KW) {
            figureValue = magnitude.roundToInt()
        }
    }

    companion object {
        /** The band and the hero: a hit is fast, a release is soft. */
        const val BAND_RISE_S = 0.120f
        const val BAND_FALL_S = 0.300f

        /** The glow is deliberately a second and a half behind, in both directions (M6). */
        const val GLOW_S = 1.5f

        const val RPM_RISE_S = 0.250f
        const val RPM_FALL_S = 0.400f

        /** [dev.denza.apps.design.instrument.EnergyScale.FLOOR_KW], restated as this class's own. */
        const val FLOOR_KW = 0.5f

        /** Inside this the panel carries no colour. */
        const val NEUTRAL_KW = 3f

        /**
         * The width of the hysteresis band around the neutral boundary.
         *
         * Colour is taken above `NEUTRAL + HYSTERESIS/2` and given up below `NEUTRAL - HYSTERESIS/2`,
         * so the static rule is still "neutral at three kilowatts" and a reading has to travel three
         * kilowatts to change the panel's mind. What that buys is the coast: `POWER_KW` swinging
         * ±2 kW never reaches either edge, so the hero cannot flicker between ink and blue. It is
         * not a lock - braking hard and then pulling hard crosses the whole band inside one frame,
         * and the colour changes, because that is a change worth drawing.
         */
        const val FLOW_HYSTERESIS_KW = 3f

        /** The hero is rewritten at most twice a second (m6). */
        const val FIGURE_INTERVAL_S = 0.5f

        /** And never for less than this past the rounding boundary it is sitting on. */
        const val FIGURE_HYSTERESIS_KW = 0.5f

        /** The peak stays where it landed for this long before it starts coming back. */
        const val PEAK_HOLD_S = 3f

        /** And then falls at this rate, which crosses the band in five seconds. */
        const val PEAK_DECAY_KW_S = 60f

        /**
         * Which way a reading counts, given where the panel already was.
         *
         * Pure, so the hysteresis is a function of the previous state rather than of a clock.
         */
        fun flowOf(kilowatts: Float, previous: ContourFlow): ContourFlow {
            val take = NEUTRAL_KW + FLOW_HYSTERESIS_KW / 2f
            val give = NEUTRAL_KW - FLOW_HYSTERESIS_KW / 2f
            return when {
                kilowatts > take -> ContourFlow.OUT
                kilowatts < -take -> ContourFlow.BACK
                abs(kilowatts) < give -> ContourFlow.NEUTRAL
                else -> previous
            }
        }
    }
}
