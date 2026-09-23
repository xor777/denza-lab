package dev.denza.apps.feature.trip

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
 * clock, road and altitude, which are nobody else's.
 *
 * **Nothing here allocates in a frame that changed nothing.** Every printed number is remembered by
 * the value it was printed from, the way `ContourFigures` remembers the cluster's: the clock is
 * rebuilt once a second, the road once every hundred metres, a temperature when it moves a degree.
 */
internal class StripReadings {

    private val readouts = EnergyReadouts()

    private val elapsed = Printed()
    private val roadText = Printed()
    private val roadFigureText = Printed()
    private val left = Printed()
    private val altitude = Printed()
    private val rate = Printed()
    private val temperatures = Array(StripModel.TEMPERATURES) { Printed() }

    // ------------------------------------------------------------------------------ sound page

    /**
     * The track, the trip's readings and the analyser.
     *
     * The readings are the strip's three: how long and how far (or, with a route, how much is
     * left), how high and which way, and the next thing the sun does. The altitude and the sun need
     * a fix and are absent without one; absent takes no room. Without location access at all the
     * hint stands where the altitude would have, which is the place it has always had: it collides
     * with nothing because nothing is there.
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
            if (seconds >= 0) {
                model.trip[k++].set(
                    REMAINING,
                    left.of(seconds / 60L) { BaseTripRenderer.clockHm(seconds.toLong()) },
                    if (metres >= 0) road(metres.toDouble()) else null,
                )
            } else {
                model.trip[k++].set(REMAINING, distanceFigure(metres.toDouble()), distanceUnit(metres.toDouble()))
            }
        } else {
            val seconds = engine.elapsedSeconds
            model.trip[k++].set(
                ON_THE_ROAD,
                elapsed.of(seconds.toLong()) {
                    if (seconds >= 3600) BaseTripRenderer.clockHm(seconds.toLong()) else BaseTripRenderer.clockMs(seconds)
                },
                road(engine.distanceMeters()),
            )
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

    /** «128 км», «12,8 км», «640 м»: the road as the unit under a clock, once per step of it. */
    private fun road(metres: Double): String = roadText.of(roadKey(metres)) { roadLabel(metres) }

    private fun distanceFigure(metres: Double): String =
        roadFigureText.of(roadKey(metres)) { roadFigure(metres) }

    /** One key per string [roadLabel] can print, so a memo is rebuilt only when the string moves. */
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
     * the instruction and nothing else.
     */
    fun car(model: StripModel, telemetry: VehicleTelemetry) {
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
            dim = readouts.flow == ContourFlow.NEUTRAL,
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
        const val ON_THE_ROAD = "В пути"
        const val REMAINING = "Осталось"
        const val ALTITUDE = "Высота"
        const val SUNSET = "Закат"
        const val SUNRISE = "Рассвет"
        const val METRES = "м"
        const val KILOMETRES = "км"

        /** The road with its unit: «640 м», «12,8 км», «128 км». */
        fun roadLabel(metres: Double): String = roadFigure(metres) + " " + distanceUnit(metres)

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

        /** A tenth with the comma this app writes one with: 12 → «1,2». */
        fun tenths(tenths: Int): String {
            val sign = if (tenths < 0) "-" else ""
            val v = abs(tenths)
            return "$sign${v / 10},${v % 10}"
        }

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
