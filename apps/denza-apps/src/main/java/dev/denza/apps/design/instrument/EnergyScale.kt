package dev.denza.apps.design.instrument

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Where a power reading sits on an energy gauge.
 *
 * The scale is square-root on both sides, which is not decoration: the car spends almost all of its
 * time under 40 kW while the ceiling is [FULL_DISCHARGE_KW], so a linear needle would live pinned
 * near zero and tell a driver nothing. Both the cluster's pack gauge and its generation trace use
 * the same rule, which lives here once.
 *
 * Zero sits at the top of the arc. Discharge runs one way, regeneration the other, and the two
 * sides carry different spans because the car cannot recover anything like what it can spend.
 */
object EnergyScale {

    /** Full deflection on the discharge side. */
    const val FULL_DISCHARGE_KW: Float = 300f

    /** Full deflection on the regeneration side. */
    const val FULL_REGEN_KW: Float = 100f

    /**
     * Below this the needle stays at zero.
     *
     * The pack reports single-kilowatt resolution and never reads exactly zero at rest, so without
     * a dead band the gauge would twitch at a standstill.
     */
    const val FLOOR_KW: Float = 0.5f

    /** Labelled marks on the discharge side. */
    val DISCHARGE_TICKS_KW: List<Float> = listOf(60f, 150f)

    /** Labelled marks on the regeneration side. */
    val REGEN_TICKS_KW: List<Float> = listOf(20f)

    /**
     * How far along its own side of the arc a reading falls, `0f` at the top and `1f` at the end.
     *
     * Sign is dropped: [isRegenerating] answers which side. Anything past full deflection clamps,
     * because the spans are plausible rather than measured and a needle that runs off the dial
     * would be a lie about a value we cannot bound.
     */
    fun sweepFraction(kilowatts: Float): Float {
        val magnitude = abs(kilowatts)
        if (magnitude <= FLOOR_KW) return 0f
        val span = if (kilowatts < 0f) FULL_REGEN_KW else FULL_DISCHARGE_KW
        return sqrt((magnitude / span).coerceAtMost(1f))
    }

    /**
     * Whether this reading is energy going back into the pack.
     *
     * Positive is discharge on this firmware; the convention lives in `VehicleConvention` and is
     * inferred rather than proven under acceleration, so it is read from there rather than assumed
     * here.
     */
    fun isRegenerating(kilowatts: Float): Boolean =
        kilowatts.sign < 0f && abs(kilowatts) > FLOOR_KW

    /**
     * The angle a reading points at, in degrees, measured counter-clockwise from east.
     *
     * [topDegrees] is where zero sits and [sideSweepDegrees] is how much arc each side gets, so one
     * assembly can hand this a wide dial and another a narrow one without either restating the
     * scale. Discharge decreases the angle, regeneration increases it, which puts discharge on the
     * right of a gauge drawn with the usual screen axes.
     */
    fun angleDegrees(
        kilowatts: Float,
        topDegrees: Float,
        sideSweepDegrees: Float,
    ): Float {
        val travel = sweepFraction(kilowatts) * sideSweepDegrees
        return if (isRegenerating(kilowatts)) topDegrees + travel else topDegrees - travel
    }
}
