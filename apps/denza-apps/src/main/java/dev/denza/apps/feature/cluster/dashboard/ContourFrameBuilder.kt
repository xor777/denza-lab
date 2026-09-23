package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.EnergyReadouts
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.min

/**
 * The car's state as one [ContourFrame]: every rule the panel keeps, applied once a frame.
 *
 * The Luminofor board draws from a fixture that already says what to print. Here is where the car
 * says it. Nothing in this class is a new rule - each line asks the one place that already owns
 * the answer and writes the answer down:
 *
 *  - **known against fresh** is [ContourScene]'s. A caption arrives with its quantity's first
 *    reading and stays; a figure leaves one horizon after its last sample. So a field here is set
 *    under `known` and its figure under `fresh`, and a quantity nobody has heard of has neither;
 *  - **the band, the hero, the glow, the peak and the revolutions** are [ContourMotion]'s followers,
 *    with the dead band, the neutral zone's hysteresis, the hero's 4 Hz rounding hysteresis and the
 *    peak's hold and decay all inside them. The hero is blue on [ContourFlow.BACK] and on nothing
 *    else, so the colour cannot flicker on a coast;
 *  - **every energy string** is `EnergyReadouts`' - the consumption and its window, the engine's
 *    cell, its sentence and its window, the volts, the hundred points (`docs/energy-display-
 *    contract.md` §1). The trip's seats and the charge countdown are [ContourFigures]'s, as they
 *    were;
 *  - **which trip seats exist when** is [ContourStage]'s and the ledger's: the trip's cell while it
 *    has answered, «ДАЛ ДВС» while the engine gave something this trip, «● РЕКУПЕРАЦИЯ» on P
 *    alone, and none of them while the engine's box owns the shelf.
 *
 * ### Nothing is formatted here either
 *
 * Every string is a memoised one: the figures come out of [ContourFigures] and `EnergyReadouts`,
 * and the two phrases built around a figure - «42 км · ЗА ПОЕЗДКУ» and «ДВС ДАЁТ 14 кВт» - are
 * rebuilt only when the figure's own string changes, which [ContourFigures] makes an identity test.
 * A steady panel allocates nothing in a frame.
 */
internal class ContourFrameBuilder {

    private val readouts = EnergyReadouts()
    private val figures = ContourFigures()

    private var odometerFor: String? = null
    private var tripCaption: String = ""
    private var generationFor: String? = null

    /** The consumption figure last printed, for [ContourFrame.consumptionHeld]. */
    private var heldConsumption: String? = null
    private var engineCaption: String = ""

    /**
     * Refills [frame] from one snapshot and the scene and motion the view has just stepped.
     *
     * @param seconds the panel's own clock, for the threads and a hot cell's pulse
     */
    fun build(
        frame: ContourFrame,
        telemetry: VehicleTelemetry,
        motion: ContourMotion,
        scene: ContourScene,
        seconds: Float,
    ): ContourFrame {
        val stage = scene.stage
        readouts.read(telemetry, stage.parked)
        frame.clear()
        frame.t = seconds
        frame.unavailable = stage.unavailable
        frame.message = stage.message
        centre(frame, motion, scene)
        left(frame, telemetry, scene)
        right(frame, telemetry, motion, scene, stage)
        trace(frame, telemetry, scene, stage)
        return frame
    }

    private fun centre(frame: ContourFrame, motion: ContourMotion, scene: ContourScene) {
        val fresh = motion.powerReady && scene.fresh(ContourValue.POWER)
        frame.powerFresh = fresh
        if (fresh) {
            frame.powerKw = motion.powerKw
            frame.glowKw = motion.glowKw
            frame.peakKw = motion.peakKw ?: Float.NaN
            frame.peakAge = motion.peakAge
        }
        // The band's own flow state, with its neutral zone and hysteresis, rather than the sign of
        // this frame's reading: a coast swinging two kilowatts either way stays ink.
        frame.into = motion.flow == ContourFlow.BACK
        frame.heroUnit = scene.known(ContourValue.POWER)
        if (scene.fresh(ContourValue.POWER)) {
            frame.heroFigure = motion.figure?.let { figures.whole(ContourFigures.Slot.HERO, it.toDouble()) }
        }
    }

    private fun left(frame: ContourFrame, t: VehicleTelemetry, scene: ContourScene) {
        if (scene.known(ContourValue.VOLTS)) frame.batteryCaption = ContourReadout.TITLE_PACK
        if (scene.fresh(ContourValue.VOLTS)) frame.volts = readouts.voltsFigure

        cell(
            frame,
            0,
            scene.known(ContourValue.PACK_TEMP),
            t[VehicleSignal.PACK_TEMP_AVG].takeIf { scene.fresh(ContourValue.PACK_TEMP) },
            ContourReadout.PACK_BAND_HIGH_C,
        )
        // The rear pair is per side, so three motors are three cells: one of them running hotter
        // than the others is what this row is there to show.
        val motors = t.motorTemps
        val motorsKnown = scene.known(ContourValue.MOTOR_TEMPS)
        val motorsFresh = scene.fresh(ContourValue.MOTOR_TEMPS)
        for (index in 0 until MOTORS) {
            cell(
                frame,
                index + 1,
                motorsKnown,
                motors.getOrNull(index).takeIf { motorsFresh },
                ContourReadout.DRIVE_BAND_HIGH_C,
            )
        }
        cell(
            frame,
            ContourFrame.CELLS - 1,
            scene.known(ContourValue.INVERTER_TEMP),
            t[VehicleSignal.INVERTER_C].takeIf { scene.fresh(ContourValue.INVERTER_TEMP) },
            ContourReadout.INVERTER_WATCH_C,
        )

        // The spread is an exception rather than a cell: it appears with the problem, under the
        // battery, in the problem's colour, and leaves with it.
        val spread = t.cellSpreadMv ?: return
        if (!scene.fresh(ContourValue.SPREAD)) return
        val level = ContourReadout.spreadState(spread)
        if (!ContourReadout.spreadIsWorthACell(level)) return
        frame.spreadCaption = ContourReadout.CAPTION_SPREAD
        frame.spreadValue = figures.whole(ContourFigures.Slot.SPREAD, spread)
        frame.spreadUnit = ContourReadout.UNIT_MILLIVOLT
        frame.spreadLevel = level
    }

    /** One temperature: the glyph once it has ever answered, the figure while it is fresh. */
    private fun cell(frame: ContourFrame, index: Int, known: Boolean, celsius: Double?, bandHigh: Double) {
        if (!known) return
        val cell = frame.temps[index]
        cell.shown = true
        if (celsius == null) return
        cell.value = figures.cell(index, celsius)
        cell.level = ContourReadout.thermalState(celsius, bandHigh)
    }

    private fun right(
        frame: ContourFrame,
        t: VehicleTelemetry,
        motion: ContourMotion,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        // The engine's cell: which of its three states it is in is `EnergyReadouts`', because the
        // car page draws the same cell and used to decide it from something else.
        when (readouts.engineCell) {
            EnergyReadouts.EngineCell.NONE -> Unit
            EnergyReadouts.EngineCell.RPM -> {
                frame.iceCaption = readouts.engineCellTitle
                // The revolutions are followed, so the panel prints its own damped value; the
                // freshness gate is still the scene's.
                if (scene.fresh(ContourValue.RPM) && motion.rpmReady) {
                    frame.iceFigure = figures.whole(ContourFigures.Slot.RPM, motion.rpm.toDouble())
                }
            }
            EnergyReadouts.EngineCell.MINUTES -> {
                frame.iceCaption = readouts.engineCellTitle
                if (scene.fresh(ContourValue.ENGINE_MINUTES)) frame.iceFigure = readouts.engineCellFigure
            }
        }

        if (stage.engineBox) {
            engineBox(frame, t, scene, stage)
            return
        }
        if (!scene.known(ContourValue.TRIP_NET)) return

        val trip = t.trip
        // The separator belongs to the kilometres in front of it, so it leaves with them.
        frame.tripCaption = if (scene.fresh(ContourValue.TRIP_KM)) {
            tripCaption(figures.whole(ContourFigures.Slot.ODOMETER, trip.kilometres))
        } else {
            ContourReadout.CAPTION_TRIP_ALONE
        }
        if (scene.fresh(ContourValue.TRIP_NET)) frame.tripKwh = figures.seat(0, trip.netKwh)

        // The detail line: what the engine gave this trip, and on P what came back. A seat's index
        // is its memo slot as well as its place.
        if (scene.known(ContourValue.TRIP_ENGINE)) {
            frame.gaveCaption = ContourReadout.CAPTION_ENGINE_GAVE
            if (scene.fresh(ContourValue.TRIP_ENGINE)) frame.gaveKwh = figures.seat(1, trip.engineKwh)
        }
        if (stage.parked && scene.known(ContourValue.TRIP_REGEN)) {
            frame.regenCaption = ContourReadout.CAPTION_REGEN
            if (scene.fresh(ContourValue.TRIP_REGEN)) frame.regenKwh = figures.seat(2, trip.recoveredKwh)
        }
    }

    /**
     * The box, in the trip's place, while the engine gives (`docs/energy-display-contract.md`
     * §2.5). Its sentence names the figure only while the flag is up and the reading fresh: the box
     * holds ten seconds after the flag drops, and a figure that stopped arriving is not a figure,
     * so the words close up instead.
     */
    private fun engineBox(frame: ContourFrame, t: VehicleTelemetry, scene: ContourScene, stage: ContourStage) {
        val bins = t.engineTrace.bins
        val count = min(bins.size, ContourFrame.GENERATION_BINS)
        val newest = bins.size - count
        for (index in 0 until count) frame.generation[index] = bins[newest + index]
        frame.generationCount = count
        frame.engineGiving = true
        val figure =
            if (stage.engineRunning && scene.fresh(ContourValue.GENERATION)) readouts.engineFigure else null
        frame.engineCaption = engineCaption(figure)
        frame.engineWindow = readouts.engineWindow
    }

    private fun trace(frame: ContourFrame, t: VehicleTelemetry, scene: ContourScene, stage: ContourStage) {
        // The chart is closed road, so it is drawn while the petal is *known*: ten kilometres of
        // road are still ten kilometres two seconds after the bus goes quiet.
        if (scene.known(ContourValue.PETAL)) {
            val values = readouts.chart.values
            val count = min(values.size, frame.chart.size)
            val first = values.size - count
            for (index in 0 until count) frame.chart[index] = values[first + index]
            frame.chartCount = count
        }

        if (stage.charging) {
            // The countdown takes the figure's seat, and its unit comes with it: «до полной» over
            // nothing names an estimate the charger may never make.
            if (!scene.fresh(ContourValue.CHARGE_LEFT)) return
            val minutes = t.chargeMinutesLeft ?: return
            frame.consumption = figures.chargeLeft(minutes)
            frame.consumptionUnit = ContourReadout.UNIT_CHARGE_LEFT
            frame.consumptionTone = if (stage.engineRunning) ContourFrame.Tone.GREY else ContourFrame.Tone.INK
            return
        }

        if (!scene.known(ContourValue.PETAL)) return
        // «за 3,7 км» until the window is full: the road the figure is the mean of.
        frame.consumptionUnit = readouts.window
        if (!scene.fresh(ContourValue.PETAL)) {
            frame.consumptionHeld = heldConsumption
            return
        }
        frame.consumption = readouts.consumptionFigure
        heldConsumption = frame.consumption
        // While the engine runs the figure is the battery's alone - `ConsumptionLog` integrates pack
        // power, and whether `GENERATION_KW` is inside it is not recorded - so it goes grey rather
        // than carry a footnote. Otherwise a minus is the one signed figure on either screen, and
        // it is blue because it is the same thing the trace's blue is.
        frame.consumptionTone = when {
            stage.engineRunning -> ContourFrame.Tone.GREY
            readouts.consumptionNegative -> ContourFrame.Tone.BLUE
            else -> ContourFrame.Tone.INK
        }
    }

    /** «42 км · ЗА ПОЕЗДКУ», rebuilt when the odometer's own string does. */
    private fun tripCaption(odometer: String): String {
        if (odometer !== odometerFor) {
            odometerFor = odometer
            tripCaption = odometer + " " + ContourReadout.UNIT_KM + " " + ContourReadout.CAPTION_TRIP
        }
        return tripCaption
    }

    /** «ДВС ДАЁТ 14 кВт», or «ДВС ДАЁТ» closed up when there is no figure. */
    private fun engineCaption(figure: String?): String {
        if (figure == null) return readouts.enginePrefix
        if (figure !== generationFor) {
            generationFor = figure
            engineCaption = readouts.enginePrefix + " " + figure + " " + ContourReadout.UNIT_KW
        }
        return engineCaption
    }

    private companion object {
        /** Front, rear left, rear right: `VehicleTelemetry.motorTemps` order. */
        const val MOTORS = 3
    }
}
