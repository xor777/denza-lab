package dev.denza.apps.feature.trip

import dev.denza.apps.R
import dev.denza.apps.core.AppDecimal
import dev.denza.apps.core.AppDuration
import dev.denza.apps.core.AppText
import dev.denza.apps.feature.cluster.dashboard.ContourFlow
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.EnergyReadouts
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The live strip, written into a [StripModel]: what the car is doing, in the words and figures the
 * Luminofor board prints.
 *
 * The renderer draws a model and knows nothing about where one comes from; this is the one place
 * that reads the sources. Its rules are the ones the strip already had - which reading is up, when
 * a figure is missing, which word the pack's flow gets - and only the case of the words has changed.
 *
 * **Every energy string comes from [EnergyReadouts]**, exactly as it did when the car page drew its
 * snapshot itself: the direction and its mark and colour, the power, the volts, the engine's cell,
 * the trip, the consumption and its window. This class places them and formats none of them
 * (`EnergySourceGuardTest` reads this file for a formatter). What it does format is the trip's own
 * time, road and altitude, which are nobody else's - the time through [AppDuration], the one rule
 * every duration on the screens follows.
 *
 * **Nothing here allocates in a frame that changed nothing.** Every printed number is remembered by
 * the value it was printed from, the way `ContourFigures` remembers the cluster's: the time is
 * rebuilt once a second for its first minute and once a minute after, the road once every hundred
 * metres, a temperature when it moves a degree.
 */
internal class StripReadings {

    private val readouts = EnergyReadouts()

    private val elapsed = PrintedDuration()
    private val left = PrintedDuration()
    private val roadFigureText = Printed()
    private val altitude = Printed()
    private val rate = Printed()
    private val temperatures = Array(StripModel.TEMPERATURES) { Printed() }

    // ------------------------------------------------------------------------------ sound page

    /**
     * The track, the trip's readings and the analyser.
     *
     * The readings are the strip's four: how long, how far (or, with a route, how long and how far
     * is left), how high and how fast it climbs, and the next thing the sun does. Each of the first
     * two is a reading of its own with its own caption: the road was once the unit under the clock,
     * and «1:42 128 км» read as one number. The altitude and the sun need a fix and are absent
     * without one; absent takes no room. Without location access at all the hint stands where the
     * altitude would have, which is the place it has always had: it collides with nothing because
     * nothing is there.
     */
    fun sound(
        model: StripModel,
        engine: TripEngine,
        nowPlaying: NowPlayingSource,
        meter: SpectrumMeter,
        locationHint: Boolean,
    ) {
        model.title = if (nowPlaying.hasTrack) nowPlaying.title else null
        model.artist = nowPlaying.artist.orEmpty()
        model.playing = nowPlaying.playing

        var k = 0
        if (engine.guiding) {
            val seconds = engine.remainingSeconds()
            val metres = engine.remainingMeters()
            if (seconds >= 0) duration(model.trip[k++], REMAINING, left.of(seconds.toLong()))
            if (metres >= 0) road(model.trip[k++], TO_GO, metres.toDouble())
        } else {
            duration(model.trip[k++], ON_THE_ROAD, elapsed.of(engine.elapsedSeconds.toLong()))
            road(model.trip[k++], DISTANCE, engine.distanceMeters())
        }

        if (engine.hasAltitude()) {
            val metres = engine.smoothedAltitude().roundToInt()
            val climb = engine.variometer()
            val climbTenths = (abs(climb) * 10.0).roundToInt()
            model.trip[k++].set(
                ALTITUDE,
                altitude.of(metres.toLong()) { metres.toString() },
                METRES,
                rate = rate.of(climbTenths.toLong()) { tenths(climbTenths) },
                rateUp = climb >= 0.0,
                rateUnit = METRES_PER_SECOND,
            )
        } else if (locationHint) {
            model.trip[k++].setHint(BaseTripRenderer.LOCATION_HINT)
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            model.trip[k++].set(if (sun.nextIsSunset) SUNSET else SUNRISE, sun.nextEventLabel)
        }
        for (index in k until StripModel.TRIP_READINGS) model.trip[index].clear()
        model.tripCount = k

        model.levels = meter.bars
        model.crowns = meter.peaks
    }

    private fun duration(into: StripReading, caption: String, parts: AppDuration.Parts) =
        into.setDuration(caption, parts.first, parts.firstUnit, parts.second, parts.secondUnit)

    /** «128 км», «12,8 км», «640 м»: the road as a reading of its own, rebuilt once per step of it. */
    private fun road(into: StripReading, caption: String, metres: Double) =
        into.set(caption, roadFigureText.of(roadKey(metres)) { roadFigure(metres) }, distanceUnit(metres))

    /** One key per figure [roadFigure] can print, so a memo is rebuilt only when the figure moves. */
    private fun roadKey(metres: Double): Long = when {
        metres < 1000.0 -> metres.roundToInt().toLong()
        metres < 100_000.0 -> 1_000_000L + (metres / 100.0).roundToInt()
        else -> 2_000_000L + (metres / 1000.0).roundToInt()
    }

    // -------------------------------------------------------------------------------- car page

    /**
     * The car's page: the pack's flow in the middle, its volts and five temperatures to one side,
     * the engine and the trip to the other, and the last ten kilometres under them.
     *
     * The semantics are the page's own and unchanged: a figure that did not arrive is absent and
     * its caption stays; the engine's cell is there only while it has something to say; the chart
     * is the snapshot's own hundred points, a filling one anchored at the right; a closed shell is
     * the hub's words for why, which the page draws as what it has of the car - «Нет доступа»
     * ([VehiclePageRenderer.closedState]) - and nothing else.
     *
     * **A hot reading leaves on the Contour's horizon** ([VehicleTelemetry.hotExpired]). The hub
     * publishes a snapshot when a sweep ends, so a reader that blocks - a helper starting, a shell
     * timing out - leaves the last one standing; the cluster ages it on its own clock and took its
     * figures down after two seconds while this page kept printing «80 кВт» from a sweep the driver
     * had long since lifted off. An expired snapshot is drawn as the dropped read it has become.
     *
     * @param nowMillis `SystemClock.elapsedRealtime()` of this frame, the clock the hub stamps with
     */
    fun car(model: StripModel, snapshot: VehicleTelemetry, nowMillis: Long) {
        val telemetry = if (snapshot.hotExpired(nowMillis)) expired(snapshot) else snapshot
        readouts.read(telemetry, telemetry.parked == true)
        if (telemetry.access == VehicleAccess.UNAVAILABLE) {
            model.closed = true
            model.message = telemetry.message
            return
        }
        model.closed = false
        model.message = ""

        val figure = readouts.powerFigure
        model.power.set(
            readouts.wordSentence,
            figure,
            ContourReadout.UNIT_KW,
            dot = readouts.mark,
            blue = readouts.flow == ContourFlow.BACK,
        )
        model.volts.set(VehiclePageWords.VOLTS, readouts.voltsFigure, VehiclePageWords.UNIT_V)

        val engineFigure = readouts.engineCellFigure
        if (engineFigure != null) {
            model.engine.set(readouts.engineCellCaption, engineFigure, readouts.engineCellUnit)
        } else {
            model.engine.clear()
        }
        val trip = readouts.tripFigure
        if (trip != null) {
            model.tripCell.set(readouts.tripCaption, trip, ContourReadout.UNIT_KWH)
        } else {
            model.tripCell.clear()
        }

        for (index in SENSORS.indices) {
            val sensor = SENSORS[index]
            val celsius = telemetry[sensor.signal]
            if (celsius == null) {
                model.temps[index].set(null, StripHeat.NORMAL)
                continue
            }
            val whole = celsius.roundToInt()
            model.temps[index].set(
                temperatures[index].of(whole.toLong()) { "$whole${ContourReadout.DEGREE}" },
                when (ContourReadout.thermalState(celsius, sensor.band)) {
                    ContourReadout.Level.ALERT -> StripHeat.DANGER
                    ContourReadout.Level.WATCH -> StripHeat.WARNING
                    ContourReadout.Level.NORMAL -> StripHeat.NORMAL
                },
            )
        }

        val points = readouts.chart.values
        model.chart = points
        model.chartCount = points.size

        model.spendWord = VehiclePageWords.SPEND
        model.spendFigure = readouts.consumptionFigure
        model.spendWindow = readouts.window
        model.spendNegative = readouts.consumptionNegative
    }

    /** The snapshot [expiredView] was built from, so the view is built once per snapshot, not per frame. */
    private var expiredFrom: VehicleTelemetry? = null
    private var expiredView: VehicleTelemetry? = null

    private fun expired(snapshot: VehicleTelemetry): VehicleTelemetry {
        val held = expiredView
        if (held != null && expiredFrom === snapshot) return held
        expiredFrom = snapshot
        return snapshot.withoutHot().also { expiredView = it }
    }

    /** One printed duration, remembered by [AppDuration.key]: rebuilt only when it would read otherwise. */
    private class PrintedDuration {
        private var key = Long.MIN_VALUE
        private var parts: AppDuration.Parts? = null

        fun of(seconds: Long): AppDuration.Parts {
            val now = AppDuration.key(seconds)
            val held = parts
            if (held != null && now == key) return held
            key = now
            return AppDuration.of(seconds).also { parts = it }
        }
    }

    /** One printed number, remembered by the value it was printed from. */
    private class Printed {
        var key = Long.MIN_VALUE
        var text = ""

        inline fun of(key: Long, print: () -> String): String {
            if (key != this.key) {
                this.key = key
                text = print()
            }
            return text
        }
    }

    private class Sensor(val signal: VehicleSignal, val band: Double)

    companion object {
        val ON_THE_ROAD: String get() = AppText.s(R.string.strip_on_the_road)
        val DISTANCE: String get() = AppText.s(R.string.strip_distance)
        val REMAINING: String get() = AppText.s(R.string.strip_remaining)
        val TO_GO: String get() = AppText.s(R.string.strip_to_go)
        val ALTITUDE: String get() = AppText.s(R.string.strip_altitude)
        val SUNSET: String get() = AppText.s(R.string.strip_sunset)
        val SUNRISE: String get() = AppText.s(R.string.strip_sunrise)
        val METRES: String get() = AppText.s(R.string.strip_unit_metres)
        val KILOMETRES: String get() = AppText.s(R.string.strip_unit_kilometres)
        val METRES_PER_SECOND: String get() = AppText.s(R.string.strip_unit_metres_per_second)

        /**
         * The road's figure: whole metres under a kilometre, a tenth of a kilometre under a hundred,
         * whole kilometres past that - where a tenth is a digit nobody reads, and «128,0 км» is wider
         * than the board's «128 км» for nothing.
         */
        fun roadFigure(metres: Double): String = when {
            metres < 1000.0 -> metres.roundToInt().coerceAtLeast(0).toString()
            metres < 100_000.0 -> tenths((metres / 100.0).roundToInt())
            else -> (metres / 1000.0).roundToInt().toString()
        }

        fun distanceUnit(metres: Double): String = if (metres < 1000.0) METRES else KILOMETRES

        /** A tenth with the mark this app writes one with: 12 → «1,2». */
        fun tenths(tenths: Int): String = AppDecimal.tenths(tenths.toLong())

        /**
         * The five cells in the order the glyphs are drawn - pack, front, rear left, rear right,
         * inverter - with the band each is ordinary inside of. The thresholds are `ContourReadout`'s,
         * so the two screens in this car cannot hold two ideas of hot.
         */
        private val SENSORS = arrayOf(
            Sensor(VehicleSignal.PACK_TEMP_AVG, ContourReadout.PACK_BAND_HIGH_C),
            Sensor(VehicleSignal.MOTOR_FRONT_C, ContourReadout.DRIVE_BAND_HIGH_C),
            Sensor(VehicleSignal.MOTOR_REAR_LEFT_C, ContourReadout.DRIVE_BAND_HIGH_C),
            Sensor(VehicleSignal.MOTOR_REAR_RIGHT_C, ContourReadout.DRIVE_BAND_HIGH_C),
            Sensor(VehicleSignal.INVERTER_C, ContourReadout.INVERTER_WATCH_C),
        )
    }
}
