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
 *    cell, its run's figure and time, the volts, the hundred points (`docs/energy-display-
 *    contract.md` §1). The trip's seats and the charge countdown are [ContourFigures]'s, as they
 *    were;
 *  - **which trip seats exist when** is [ContourStage]'s and the ledger's: the trip's cell while it
 *    has answered, «ЗАРЯД ОТ ДВС» while the pack took something with the engine running this trip,
 *    «● РЕКУПЕРАЦИЯ» on P alone, and none of them while the engine's block owns the shelf.
 *
 * ### Nothing is formatted here either
 *
 * Every string is a memoised one: the figures come out of [ContourFigures] and `EnergyReadouts`,
 * and every word around a figure is a word alone - since 2026-11-19 no caption carries one.
 * A steady panel allocates nothing in a frame.
 */
internal class ContourFrameBuilder {

    private val readouts = EnergyReadouts()
    private val figures = ContourFigures()

    /** The consumption figure last printed, for [ContourFrame.consumptionHeld]. */
    private var heldConsumption: String? = null

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
        if (scene.known(ContourValue.VOLTS)) frame.batteryCaption = ContourReadout.TITLE_VOLTS
        if (scene.fresh(ContourValue.VOLTS)) {
            frame.volts = readouts.voltsFigure
            if (frame.volts != null) frame.voltsUnit = ContourReadout.UNIT_VOLT
        }

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
                    frame.iceUnit = readouts.engineCellUnit
                }
            }
            // Not beside the block: while it holds after a stop the block's own figure and time
            // are the run's, and the trip's minutes beside them would be a second answer.
            EnergyReadouts.EngineCell.MINUTES -> if (!stage.engineBlock) {
                frame.iceCaption = readouts.engineCellTitle
                if (scene.fresh(ContourValue.ENGINE_MINUTES)) {
                    frame.iceFigure = readouts.engineCellFigure
                    if (frame.iceFigure != null) frame.iceUnit = readouts.engineCellUnit
                }
            }
        }

        if (stage.engineBlock) {
            engineBlock(frame, t)
            return
        }
        if (!scene.known(ContourValue.TRIP_NET)) return

        val trip = t.trip
        // A name over the figure; the road is its own reading on the detail line and leaves alone.
        frame.tripCaption = ContourReadout.CAPTION_TRIP
        if (scene.fresh(ContourValue.TRIP_KM)) {
            frame.tripKm = figures.whole(ContourFigures.Slot.ODOMETER, trip.kilometres)
        }
        if (scene.fresh(ContourValue.TRIP_NET)) frame.tripKwh = figures.seat(0, trip.netKwh)

        // The detail line: what the pack took with the engine running this trip, and on P what
        // came back. A seat's index is its memo slot as well as its place.
        if (scene.known(ContourValue.TRIP_ENGINE)) {
            frame.gaveCaption = ContourReadout.CAPTION_ENGINE_CHARGE
            if (scene.fresh(ContourValue.TRIP_ENGINE)) frame.gaveKwh = figures.seat(1, trip.engineKwh)
        }
        if (stage.parked && scene.known(ContourValue.TRIP_REGEN)) {
            frame.regenCaption = ContourReadout.CAPTION_REGEN
            if (scene.fresh(ContourValue.TRIP_REGEN)) frame.regenKwh = figures.seat(2, trip.recoveredKwh)
        }
    }

    /**
     * The block, in the trip's place, while the engine runs and ten seconds after
     * (`docs/energy-display-contract.md` §2.5): the run's points, its figure and its time. They
     * are the hub's, as the trip is, so they are as fresh as the packet that carried them; after
     * the stop they are the run's last answer and stay as they were.
     */
    private fun engineBlock(frame: ContourFrame, t: VehicleTelemetry) {
        val points = t.engineCharge.points
        val count = min(points.size, frame.charge.size)
        val first = points.size - count
        for (index in 0 until count) frame.charge[index] = points[first + index]
        frame.chargeCount = count
        frame.engineBlock = true
        frame.engineCaption = ContourReadout.CAPTION_ENGINE_CHARGE
        frame.engineKwh = readouts.engineKwh
        frame.engineRun = readouts.engineRun
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
            val left = figures.chargeLeft(minutes)
            frame.consumption = left.first
            frame.consumptionFirstUnit = left.firstUnit
            frame.consumptionSecond = left.second
            frame.consumptionSecondUnit = left.secondUnit
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

    private companion object {
        /** Front, rear left, rear right: `VehicleTelemetry.motorTemps` order. */
        const val MOTORS = 3
    }
}
