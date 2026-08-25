package dev.denza.apps.feature.cluster.dashboard

import java.util.Locale
import kotlin.math.sqrt

/**
 * Every decision the cluster dashboard makes about a number before it becomes a shape.
 *
 * The renderer below draws; this decides. Keeping the two apart is what makes the dashboard
 * testable at all - the module has no Robolectric and no screenshot harness, so a `Canvas` call is
 * unverifiable by construction while a threshold, a span and a string are not.
 */
internal object ClusterReadout {

    /**
     * The traction pack's working window.
     *
     * A pack sits between roughly 500 and 600 V in use, so a gauge spanning zero would be a flat
     * line at four fifths of its length. The vehicle page already narrows it for the same reason.
     */
    const val PACK_VOLT_LOW = 500.0
    const val PACK_VOLT_HIGH = 600.0

    /** Cell spread the pack is expected to hold, and where it stops being ordinary. */
    const val SPREAD_WATCH_MV = 25.0
    const val SPREAD_ALERT_MV = 40.0

    /** The engine's own span. A generating engine should visibly sit low in it. */
    const val RPM_FULL = 6000.0

    /** Generation's ceiling. Idle generation is around 10 kW, so this dial is square-root too. */
    const val GENERATION_FULL_KW = 100.0

    /** The only thermal reading the generation path has; there is no coolant temperature here. */
    const val INVERTER_WATCH_C = 70.0
    const val INVERTER_ALERT_C = 85.0

    /** What the pack is comfortable at, and how far past it counts as hot rather than warm. */
    const val PACK_BAND_LOW_C = 15.0
    const val PACK_BAND_HIGH_C = 40.0
    const val DRIVE_BAND_HIGH_C = 70.0
    const val HOT_MARGIN_C = 15.0

    /** How many lamps the firmware folds the sixteen fluid ids into. */
    const val LAMP_COUNT = 8

    /** Where a pack voltage falls on its own window, or `null` when nothing answered. */
    fun voltFraction(volts: Double?): Float? {
        if (volts == null) return null
        val span = PACK_VOLT_HIGH - PACK_VOLT_LOW
        return ((volts - PACK_VOLT_LOW) / span).coerceIn(0.0, 1.0).toFloat()
    }

    /** Where revolutions fall on the engine's span. Linear: a driver reads rpm linearly. */
    fun rpmFraction(rpm: Double?): Float? {
        if (rpm == null) return null
        return (rpm / RPM_FULL).coerceIn(0.0, 1.0).toFloat()
    }

    /** Where generation falls, square-root so the ordinary 10 kW is visible rather than a stub. */
    fun generationFraction(kilowatts: Double?): Float? {
        if (kilowatts == null || kilowatts <= 0.0) return null
        return sqrt((kilowatts / GENERATION_FULL_KW).coerceIn(0.0, 1.0)).toFloat()
    }

    /**
     * The mean of a run of consumption bars, over the spending ones only.
     *
     * Averaging the recovery in would answer a question nobody asks - what the car spent net of
     * what a hill gave back - and would read lower than any bar on the chart.
     */
    fun averageConsumption(bars: List<Double>): Double? {
        val spending = bars.filter { it >= 0.0 }
        if (spending.isEmpty()) return null
        return spending.average()
    }

    /** How far the chart reaches back, given how many bars closed. */
    fun chartDistanceKm(bars: List<Double>, bucketKm: Double): Double = bars.size * bucketKm

    /**
     * The one line the cluster gives the fluid lamps.
     *
     * A driver's display shows exceptions, not an inventory: the eight names live on the engine
     * page. So this is silent praise while everything answers and healthy, and names the fault the
     * moment one does not - and says so plainly when the lamps simply did not answer, because a
     * lamp that never reported is not the same as a lamp reporting good news.
     */
    fun lampLine(alerts: List<String>, answered: Int): String = when {
        alerts.isNotEmpty() -> alerts.joinToString(" · ")
        answered <= 0 -> "жидкости не ответили"
        answered < LAMP_COUNT -> "в норме $answered из $LAMP_COUNT"
        else -> "все восемь в норме"
    }

    /** Whether a temperature has left its band, and by enough to stop being merely warm. */
    fun thermalState(celsius: Double?, bandHigh: Double, bandLow: Double = Double.NEGATIVE_INFINITY): Thermal = when {
        celsius == null -> Thermal.UNKNOWN
        celsius > bandHigh + HOT_MARGIN_C -> Thermal.HOT
        celsius > bandHigh -> Thermal.WARM
        celsius < bandLow -> Thermal.COLD
        else -> Thermal.NORMAL
    }

    /** Whether a cell spread is ordinary, worth watching, or worth stopping for. */
    fun spreadState(millivolts: Double?): Thermal = when {
        millivolts == null -> Thermal.UNKNOWN
        millivolts >= SPREAD_ALERT_MV -> Thermal.HOT
        millivolts >= SPREAD_WATCH_MV -> Thermal.WARM
        else -> Thermal.NORMAL
    }

    enum class Thermal { UNKNOWN, COLD, NORMAL, WARM, HOT }

    /** A number with a comma, the way every other panel in this app writes one. */
    fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value).replace('.', ',')

    /** A whole number, or a dash where nothing answered. Never a zero standing in for absence. */
    fun whole(value: Double?): String =
        if (value == null) DASH else String.format(Locale.US, "%.0f", value)

    /** What a reading that never arrived looks like. */
    const val DASH = "—"
}
