package dev.denza.apps.feature.cluster.dashboard

import java.util.Locale
import kotlin.math.sqrt

/**
 * Every decision the cluster dashboard makes about a number before it becomes a shape.
 *
 * The renderer draws; this decides. Keeping the two apart is what makes the dashboard testable at
 * all - the module has no Robolectric and no screenshot harness, so a `Canvas` call is unverifiable
 * by construction while a threshold, a span and a string are not.
 *
 * Everything here takes plain numbers rather than a telemetry snapshot, so a test states the case it
 * means instead of assembling a car around it.
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

    /**
     * Where a tank stops being comfortable, for the amber that precedes the car's own alarm.
     *
     * The alarm itself decides [Level.ALERT]; this only decides when to start looking. A threshold
     * of ours that fired *after* the vehicle's would be worse than none.
     */
    const val FUEL_WATCH_PERCENT = 15.0

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

    /** How full the tank is, straight through: a fuel gauge is the one place linear is honest. */
    fun fuelFraction(percent: Double?): Float? {
        if (percent == null) return null
        return (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The mean of a run of consumption bars, over the spending ones only.
     *
     * Averaging the recovery in would answer a question nobody asks - what the car spent net of what
     * a hill gave back - and would read lower than any bar on the chart.
     */
    fun averageConsumption(bars: List<Double>): Double? {
        val spending = bars.filter { it >= 0.0 }
        if (spending.isEmpty()) return null
        return spending.average()
    }

    /**
     * The sentence under the dial.
     *
     * There are three cases and they are genuinely different, which is why the empty chart does not
     * simply keep the words of the full one: a car that has not moved yet is not a car whose
     * consumption failed, and neither is a car that has moved less than one bucket.
     */
    fun chartCaption(average: Double?, distanceKm: Double, stationary: Boolean): String = when {
        average != null -> "${fmt(average, 1)} средний за ${fmt(distanceKm, 1)} км"
        stationary -> "стоим"
        else -> "считаю расход"
    }

    /**
     * What the pack is doing while a gun is in.
     *
     * This replaces the resting detail line rather than joining it: a driver watching a charge wants
     * to know how long, and insulation resistance can go back to being interesting afterwards.
     *
     * The rate is deliberately absent. Charging is drawn as energy arriving, so while a gun is in
     * the dial's own figure already reads the kilowatts, and repeating them here would be the same
     * mistake as putting the state of charge on a cluster that already shows one.
     *
     * [brief] drops the verb for the narrow layout, where the block is 172 units wide and the sole
     * question left is how long.
     */
    fun chargeLine(minutesLeft: Int?, brief: Boolean = false): String {
        val left = minutesLeft?.takeIf { it > 0 } ?: return "заряжается"
        return if (brief) "осталось ${duration(left)}" else "заряжается · осталось ${duration(left)}"
    }

    /** Hours and minutes, dropping the half that would read as zero. */
    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours <= 0 -> "$rest мин"
            rest == 0 -> "$hours ч"
            else -> "$hours ч $rest мин"
        }
    }

    /**
     * The combustion half in one line.
     *
     * Most of a hybrid's life the engine is off, and "заглушен" alone spends a line saying nothing.
     * The range left on the tank is what a stopped engine is actually worth, so that is what the
     * line carries when there is nothing happening.
     */
    fun engineLine(
        generating: Boolean,
        generationKw: Double?,
        running: Boolean?,
        fuelRangeKm: Double?,
    ): String = when {
        generating && generationKw != null -> "заряжает ${whole(generationKw)} кВт"
        generating -> "заряжает батарею"
        running == true -> "работает"
        running == false && fuelRangeKm != null -> "заглушен · ${whole(fuelRangeKm)} км на бензине"
        running == false -> "заглушен"
        else -> "двигатель не ответил"
    }

    /**
     * The one line the cluster gives the fluid lamps.
     *
     * A driver's display shows exceptions, not an inventory: the names live on the engine page. So
     * this is silent praise while everything answers and is healthy, and names the fault the moment
     * one does not - and says so plainly when the lamps simply did not answer, because a lamp that
     * never reported is not the same as a lamp reporting good news.
     *
     * [total] is passed rather than written into the sentence: the count is a property of the lamp
     * catalog, and a spelled-out number here went stale the first time that catalog grew.
     *
     * [brief] is for the narrow layout, where two joined fault names would run past the block. It
     * names the first and counts the rest rather than dropping any: a line that silently shows one
     * of three faults is worse than one that says there are three.
     */
    fun lampLine(alerts: List<String>, answered: Int, total: Int, brief: Boolean = false): String = when {
        alerts.size > 1 && brief -> "${alerts.first()} +${alerts.size - 1}"
        alerts.isNotEmpty() -> alerts.joinToString(" · ")
        answered <= 0 -> "жидкости не ответили"
        answered < total -> "в норме $answered из $total"
        else -> "все в норме"
    }

    /** Whether a temperature has left its band, and by enough to stop being merely warm. */
    fun thermalState(
        celsius: Double?,
        bandHigh: Double,
        bandLow: Double = Double.NEGATIVE_INFINITY,
    ): Level = when {
        celsius == null -> Level.UNKNOWN
        celsius > bandHigh + HOT_MARGIN_C -> Level.ALERT
        celsius > bandHigh -> Level.WATCH
        celsius < bandLow -> Level.LOW
        else -> Level.NORMAL
    }

    /** Whether a cell spread is ordinary, worth watching, or worth stopping for. */
    fun spreadState(millivolts: Double?): Level = when {
        millivolts == null -> Level.UNKNOWN
        millivolts >= SPREAD_ALERT_MV -> Level.ALERT
        millivolts >= SPREAD_WATCH_MV -> Level.WATCH
        else -> Level.NORMAL
    }

    /**
     * Whether the tank is worth a glance.
     *
     * Never an alert. The id read here as the vehicle's low-fuel alarm turned out to flip while the
     * tank did not, and it is gone; the stock cluster keeps its own low-fuel lamp a few centimetres
     * away, so this figure raises a watch on its own percentage and leaves the alarm to the car.
     */
    fun fuelState(percent: Double?): Level = when {
        percent == null -> Level.UNKNOWN
        percent <= FUEL_WATCH_PERCENT -> Level.WATCH
        else -> Level.NORMAL
    }

    /** Where a reading sits against the band it is expected to stay in. */
    enum class Level { UNKNOWN, LOW, NORMAL, WATCH, ALERT }

    /** A number with a comma, the way every other panel in this app writes one. */
    fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value).replace('.', ',')

    /** A whole number, or a dash where nothing answered. Never a zero standing in for absence. */
    fun whole(value: Double?): String =
        if (value == null) DASH else String.format(Locale.US, "%.0f", value)

    /** What a reading that never arrived looks like. */
    const val DASH = "—"
}
