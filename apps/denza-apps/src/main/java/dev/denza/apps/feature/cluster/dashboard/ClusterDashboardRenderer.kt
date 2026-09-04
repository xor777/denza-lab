package dev.denza.apps.feature.cluster.dashboard

import android.graphics.Canvas
import android.graphics.Paint
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.design.instrument.EnergyScale
import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.design.instrument.InstrumentPen
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.VehicleConvention
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Contour, drawn.
 *
 * It won the 2026-09 cluster contest (`docs/cluster-contest-2026-09/`), went to the owner three
 * times, was roasted by an independent review, was redrawn four more times against what came
 * back, and once more against what the owner saw when this file first ran on a bench.
 * `tools/design-canvas/gen_contour.py` is that concept as three boards and this is the same concept
 * as a `Canvas`; `ContourBoardContractTest` is what keeps the two from drifting.
 *
 * Five rules carry the whole panel, and they are worth knowing before changing a line of it.
 *
 * **One heavy thing.** `INK` belongs to the hero and to the petal's figure and to nothing else. Both
 * corners and both shelves are `MUTED`; headings, captions and units are `MUTED_DEEP`; `WARNING` and
 * `DANGER` are the exception only. Five equal 52s were the owner's original complaint wearing a new
 * suit, and size alone was not enough to separate them (M4).
 *
 * **One lit thing, and it stands still.** The glow is centred on zero, its hue is the sign and its
 * brightness is the magnitude. It used to ride the band's tip, which put a pool of light through a
 * centimetre of travel every time the pedal moved in a jam - precisely what peripheral vision is
 * built to catch (M6).
 *
 * **Alpha is not a state channel.** A stale value is removed after two seconds and its caption
 * stays; link loss is that rule applied to every value at once. Nothing here dims anything, and
 * [ContourScene] is where that is decided (M5).
 *
 * **A zero is never drawn.** A quantity that did not happen this trip has no cell. That is the
 * owner's own question - «что означает 0,0 от ДВС, когда ДВС заглушен?» - and the rule it produced
 * outranks the tidy ones: is this understood at first glance, by somebody who has never seen the
 * panel and has no legend?
 *
 * **No coordinate depends on data.** Every anchor is in [ContourPlan]; this file draws at them.
 *
 * ### Nothing is allocated in a frame, and nothing is thrown out of one
 *
 * This runs inside a `Presentation` over the vehicle's live instruments. An exception out of
 * `onDraw` takes that window down, so the panel draws what it has and leaves out what it does not,
 * and there is no case here that ends in a throw. The three history buffers are fields.
 */
internal class ClusterDashboardRenderer {

    private val pen = InstrumentPen()

    /** The temperature row's five marks. Held rather than extended, the way the pen is. */
    private val glyphs = ContourGlyphs()

    private var plan: ContourPlan? = null
    private var planFor: ClusterDashboardLayout? = null

    private val petalYs = FloatArray(ContourPlan.PETAL_BUCKETS)
    private val returnYs = FloatArray(ContourPlan.PETAL_BUCKETS)
    private val generationYs = FloatArray(ContourPlan.ENGINE_BINS)
    private val span = FloatArray(max(ContourPlan.ENGINE_BINS, ContourPlan.PETAL_BUCKETS))

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        layout: ClusterDashboardLayout,
        telemetry: VehicleTelemetry,
        motion: ContourMotion,
        scene: ContourScene,
    ) {
        if (!layout.supported) return

        // Black rather than the design system's near-black `BACKGROUND`: this sits on a panel whose
        // own ground is black, and three per cent of grey is a visible rectangle there while it is
        // invisible on the boards, which are viewed in a browser page.
        canvas.drawColor(BACKGROUND)

        // The plan is measured through the car's own face, so it is rebuilt when the window's size
        // changes and never inside a frame.
        val moved = pen.size(width, height, layout.virtualHeight)
        val known = plan
        val plan = if (known != null && !moved && planFor == layout) {
            known
        } else {
            ContourPlan(layout, ContourType.of(pen)).also {
                this.plan = it
                planFor = layout
            }
        }

        val stage = scene.stage
        glow(canvas, plan, motion, scene)
        skeleton(canvas, plan)
        band(canvas, plan, telemetry, motion, scene, stage)
        hero(canvas, plan, motion, scene)
        leftCorner(canvas, plan, telemetry, scene)
        rightCorner(canvas, plan, telemetry, motion, scene, stage)
        leftShelf(canvas, plan, telemetry, scene)
        rightShelf(canvas, plan, telemetry, scene, stage)
        petal(canvas, plan, telemetry, scene, stage)
    }

    // ---------------------------------------------------------------- the skeleton

    /**
     * The two lines that are there in every state, including the ones with no data at all.
     *
     * A hairline and a zero mark, and no limit captions: a band whose two directions run on a square
     * root over two different spans is an ambient, not a scale, and «100 кВт / 300 кВт» were two 12′
     * lines saying otherwise (M10).
     */
    private fun skeleton(canvas: Canvas, plan: ContourPlan) {
        val y = pen.v(plan.bandY)
        pen.line(canvas, pen.v(plan.leftEdge), y, pen.v(plan.rightEdge), y, DenzaPalette.MUTED_DEEP, plan.bandHairline)
        val x = pen.v(plan.axis)
        pen.line(
            canvas,
            x,
            y - pen.v(plan.zeroHalf),
            x,
            y + pen.v(plan.zeroHalf),
            DenzaPalette.MUTED_DEEP,
            plan.zeroWidth,
        )
    }

    /**
     * The one pool of light.
     *
     * Its hue comes from the band's own flow state rather than from its own follower, so the panel
     * has one colour at a time: the two are the same reading at two speeds, and during a fast
     * transition their signs can differ for a few frames.
     */
    private fun glow(canvas: Canvas, plan: ContourPlan, motion: ContourMotion, scene: ContourScene) {
        if (!motion.powerReady || !scene.fresh(ContourValue.POWER)) return
        val strength = plan.glowAlpha(motion.glowKw) * NIGHT_DIM
        if (strength <= 0f) return
        pen.glow(
            canvas,
            pen.v(plan.glowCentreX),
            pen.v(plan.glowCentreY),
            plan.glowRadiusX,
            plan.glowRadiusY,
            flowColor(motion.flow),
            strength,
        )
    }

    // ---------------------------------------------------------------- the band

    private fun band(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        motion: ContourMotion,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        if (!motion.powerReady || !scene.fresh(ContourValue.POWER)) return
        val kilowatts = motion.powerKw
        val top = pen.v(plan.bandY - plan.bandBody / 2f)
        val bottom = pen.v(plan.bandY + plan.bandBody / 2f)
        val zeroX = pen.v(plan.axis)
        val tipX = pen.v(plan.bandX(kilowatts))

        if (abs(kilowatts) > EnergyScale.FLOOR_KW) {
            pen.band(canvas, zeroX, tipX, top, bottom, flowColor(motion.flow), edgeColor(motion.flow))
        }

        generation(canvas, plan, t, scene, stage, kilowatts, tipX, top, bottom)

        motion.peakKw?.let { peak ->
            val x = pen.v(plan.bandX(peak))
            pen.line(
                canvas,
                x,
                top - pen.v(PEAK_OVERHANG),
                x,
                bottom + pen.v(PEAK_OVERHANG),
                DenzaPalette.DATA_PEAK,
                PEAK_WIDTH,
                ContourPlan.PEAK_ALPHA,
            )
        }
    }

    /**
     * What the engine is paying, drawn on the band - one of two ways, and the flag decides.
     *
     * The jury's second correction reads the band as `wheels = pack + generation`: ink is what the
     * battery pays, blue is what the engine pays, and the tip is what the wheels asked for. That is
     * only true if `GENERATION_KW` is not already inside `POWER_KW`, and nobody has logged this car
     * on a flat cruise with the engine running (VERDICT check 3, CRITIQUE B1).
     *
     * So [VehicleConvention.GENERATION_INSIDE_PACK_POWER] is true until somebody does, and the same
     * fact is drawn without the claim: a separate blue line under the body, from zero. Flipping that
     * one constant is the whole change, in this one method.
     *
     * **The line is scaled on the return side's own span.** It used to take
     * `sweepFraction(generation)` with a positive argument, which picks the 300 kW discharge span:
     * 14 kW of generation came out 0.216 of the half-band where 14 kW of regeneration on the band
     * above it came out 0.374 - two lengths 1.73 times apart, in the same blue, on the same axis,
     * for the same kilowatts into the same pack. Both are energy arriving, so both are measured on
     * [EnergyScale.FULL_REGEN_KW] and the same square root.
     */
    private fun generation(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
        stage: ContourStage,
        kilowatts: Float,
        tipX: Float,
        top: Float,
        bottom: Float,
    ) {
        if (!stage.engineRunning || !scene.fresh(ContourValue.GENERATION)) return
        val generation = (t.generationKw ?: return).toFloat()
        if (generation <= 0f) return
        if (!VehicleConvention.GENERATION_INSIDE_PACK_POWER) {
            val far = pen.v(plan.bandX(kilowatts + generation))
            pen.rect(canvas, tipX, top, far, bottom, DenzaPalette.RETURN)
        } else {
            val far = pen.v(plan.axis + EnergyScale.sweepFraction(-generation) * plan.bandHalf)
            val lineTop = pen.v(plan.generationLineY)
            pen.rect(
                canvas,
                pen.v(plan.axis),
                lineTop,
                far,
                lineTop + pen.v(plan.generationLineHeight),
                DenzaPalette.RETURN,
            )
        }
    }

    // ---------------------------------------------------------------- the hero

    /**
     * The one figure read on the move, and the one place a unit has to be readable.
     *
     * «кВт» is at 34 - 23′ - because a 12′ unit under the stock speedometer was the only thing
     * telling the driver that «34» was not 34 km/h (M2). It is drawn as soon as the reading has ever
     * arrived and stays when it goes, which is the caption rule applied to a unit.
     */
    private fun hero(canvas: Canvas, plan: ContourPlan, motion: ContourMotion, scene: ContourScene) {
        if (!scene.known(ContourValue.POWER)) return
        pen.text(
            canvas,
            ContourReadout.UNIT_KW,
            pen.v(plan.heroUnitX),
            pen.v(plan.heroBaseline),
            InstrumentFace.READING,
            DenzaPalette.MUTED,
        )
        if (!scene.fresh(ContourValue.POWER)) return
        val figure = motion.figure ?: return
        pen.text(
            canvas,
            figure.toString(),
            pen.v(plan.heroFieldRight),
            pen.v(plan.heroBaseline),
            InstrumentFace.HERO,
            heroColor(motion.flow),
            Paint.Align.RIGHT,
        )
    }

    // ---------------------------------------------------------------- the corners

    /**
     * «БАТАРЕЯ · В» over the pack's volts, and nothing else.
     *
     * The sag rail is deleted (M9). Its reference was an EWMA of the pack at rest, and on a motorway
     * there is no rest - the board electronics pull a kilowatt or two permanently, so after half an
     * hour the reference has aged into the pack's own discharge and «просадка 14 В» is ten per cent
     * of the state of charge wearing a unit it does not have.
     */
    private fun leftCorner(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
    ) {
        if (!scene.known(ContourValue.VOLTS)) return
        pen.text(
            canvas,
            ContourReadout.TITLE_PACK,
            pen.v(plan.leftEdge),
            pen.v(plan.cornerTitleBaseline),
            InstrumentFace.HEADING,
            DenzaPalette.MUTED_DEEP,
        )
        if (!scene.fresh(ContourValue.VOLTS)) return
        val volts = t[VehicleSignal.PACK_VOLT] ?: return
        pen.text(
            canvas,
            ContourReadout.whole(volts),
            pen.v(plan.voltsFieldRight),
            pen.v(plan.cornerFigureBaseline),
            InstrumentFace.FIGURE,
            DenzaPalette.MUTED,
            Paint.Align.RIGHT,
        )
    }

    /**
     * The engine's corner, in the three states it has, one of which is not being there at all.
     *
     * Running, it reads «ДВС · об/мин» over the revolutions. Asleep after running, «ДВС · мин за
     * поездку» over the minutes - the question a hybrid's driver actually asks and the answer
     * nothing on this panel used to give. Never started this trip: **empty**, because a dimmed
     * heading over an empty corner is advertising an instrument that is not there (m2).
     *
     * There is no third line. «● 14 кВт» used to stand under the revolutions and it was a number
     * parked away from its own noun: it is inside the engine box's own sentence now.
     *
     * Since the eighth pass this is also the *only* place the revolutions are drawn. The line the
     * box carried for them was half of what made its legend unreadable, and this is where a driver
     * was reading them anyway.
     */
    private fun rightCorner(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        motion: ContourMotion,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        val edge = pen.v(plan.rightEdge)
        val titleY = pen.v(plan.cornerTitleBaseline)
        val figureY = pen.v(plan.cornerFigureBaseline)
        if (stage.engineRunning && scene.known(ContourValue.RPM)) {
            pen.text(
                canvas,
                ContourReadout.TITLE_ENGINE_RPM,
                edge,
                titleY,
                InstrumentFace.HEADING,
                DenzaPalette.MUTED_DEEP,
                Paint.Align.RIGHT,
            )
            if (!scene.fresh(ContourValue.RPM) || !motion.rpmReady) return
            pen.text(
                canvas,
                ContourReadout.whole(motion.rpm.toDouble()),
                pen.v(plan.rpmFieldRight),
                figureY,
                InstrumentFace.FIGURE,
                DenzaPalette.MUTED,
                Paint.Align.RIGHT,
            )
            return
        }
        if (!scene.known(ContourValue.ENGINE_MINUTES)) return
        pen.text(
            canvas,
            ContourReadout.TITLE_ENGINE_MINUTES,
            edge,
            titleY,
            InstrumentFace.HEADING,
            DenzaPalette.MUTED_DEEP,
            Paint.Align.RIGHT,
        )
        if (!scene.fresh(ContourValue.ENGINE_MINUTES)) return
        pen.text(
            canvas,
            ContourReadout.whole(t.trip.engineMinutes),
            edge,
            figureY,
            InstrumentFace.FIGURE,
            DenzaPalette.MUTED,
            Paint.Align.RIGHT,
        )
    }

    // ---------------------------------------------------------------- the left shelf

    /**
     * Temperatures: five cells named by pictures, and a sixth that only exists on an exception.
     *
     * **No words in this row.** Until the ninth pass it was three cells - `БАТАРЕЯ`, `МОТОРЫ`,
     * `ИНВЕРТОР` - and the middle one carried three figures under one caption, so which motor was
     * which was something the reader had to learn (m11, left standing for five drawings). Naming the
     * positions in Russian was tried and the owner threw it out on the sound of it; a drawing of the
     * car with one axle lit says the same thing in one glance and in no language. See
     * [ContourGlyphs] for the family and why it is all five or none.
     *
     * Each cell is a two-digit figure with its own «°» over a 24-unit glyph standing on the caption
     * baseline. **The glyph is the caption**, so it obeys the caption rule: it arrives with the first
     * reading and stays when the reading goes stale (M5). The exception is the figure changing
     * colour - and the glyph's own component with it, so the cell lights as one object - at the size
     * every other figure on the shelf already is, so noticing it and reading it are one glance (m8).
     */
    private fun leftShelf(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
    ) {
        val captionY = pen.v(plan.shelfCaptionBaseline)
        val figureY = pen.v(plan.shelfFigureBaseline)

        fun cell(
            index: Int,
            glyph: ContourGlyphs.Glyph,
            known: Boolean,
            celsius: Double?,
            bandHigh: Double,
        ) {
            if (!known) return
            val left = plan.leftCell(index)
            val level =
                if (celsius == null) ContourReadout.Level.NORMAL
                else ContourReadout.thermalState(celsius, bandHigh)
            glyphs.draw(
                pen,
                canvas,
                glyph,
                left + plan.glyphInset,
                plan.glyphBaseline,
                DenzaPalette.MUTED,
                componentColor(level),
            )
            if (celsius == null) return
            pen.text(
                canvas,
                ContourReadout.whole(celsius),
                pen.v(left + plan.temperatureField),
                figureY,
                InstrumentFace.READING,
                levelColor(level),
                Paint.Align.RIGHT,
            )
            pen.text(
                canvas,
                ContourReadout.DEGREE,
                pen.v(left + plan.temperatureField),
                figureY,
                InstrumentFace.READING,
                DenzaPalette.MUTED,
            )
        }

        cell(
            0,
            ContourGlyphs.Glyph.PACK,
            scene.known(ContourValue.PACK_TEMP),
            t[VehicleSignal.PACK_TEMP_AVG].takeIf { scene.fresh(ContourValue.PACK_TEMP) },
            ContourReadout.PACK_BAND_HIGH_C,
        )

        // The rear pair is per-side rather than per-axle, so three separate readings is the honest
        // shape - and since the ninth pass three separate cells, because one of them running hotter
        // than the others is exactly what this row is there to show.
        val motors = t.motorTemps
        val motorsFresh = scene.fresh(ContourValue.MOTOR_TEMPS)
        val motorsKnown = scene.known(ContourValue.MOTOR_TEMPS)
        MOTOR_GLYPHS.forEachIndexed { index, glyph ->
            cell(
                index + 1,
                glyph,
                motorsKnown,
                motors.getOrNull(index).takeIf { motorsFresh },
                ContourReadout.DRIVE_BAND_HIGH_C,
            )
        }

        cell(
            4,
            ContourGlyphs.Glyph.INVERTER,
            scene.known(ContourValue.INVERTER_TEMP),
            t[VehicleSignal.INVERTER_C].takeIf { scene.fresh(ContourValue.INVERTER_TEMP) },
            ContourReadout.INVERTER_WATCH_C,
        )

        // The last cell is an exception rather than a row: a pack holding its cells together says
        // nothing worth 167 units of shelf, so it appears with the problem and leaves with it - and
        // it is the only word in this row, which is what makes a word here mean something.
        val spread = t.cellSpreadMv ?: return
        if (!scene.fresh(ContourValue.SPREAD)) return
        val level = ContourReadout.spreadState(spread)
        if (!ContourReadout.spreadIsWorthACell(level)) return
        val left = plan.leftCell(plan.spreadCellIndex)
        pen.text(
            canvas,
            ContourReadout.CAPTION_SPREAD,
            pen.v(left),
            captionY,
            InstrumentFace.CAPTION,
            DenzaPalette.MUTED_DEEP,
        )
        pen.text(
            canvas,
            ContourReadout.whole(spread),
            pen.v(left + plan.temperatureField),
            figureY,
            InstrumentFace.READING,
            levelColor(level),
            Paint.Align.RIGHT,
        )
        pen.text(
            canvas,
            ContourReadout.UNIT_MILLIVOLT,
            pen.v(left + plan.temperatureField + plan.step),
            figureY,
            InstrumentFace.READING,
            DenzaPalette.MUTED,
        )
    }

    // ---------------------------------------------------------------- the right shelf

    private fun rightShelf(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        if (stage.engineBox) {
            engineBox(canvas, plan, t, scene, stage)
            return
        }
        if (!scene.known(ContourValue.TRIP_NET)) return

        val seats = if (stage.parked) plan.parkSeats else plan.driveSeats
        val trip = t.trip

        seat(
            canvas,
            plan,
            plan.tripSeat(0, seats),
            value = trip.netKwh.takeIf { scene.fresh(ContourValue.TRIP_NET) },
            word = ContourReadout.CAPTION_TRIP,
            marked = false,
            odometer = trip.kilometres.takeIf { scene.fresh(ContourValue.TRIP_KM) },
        )
        if (stage.parked && scene.known(ContourValue.TRIP_REGEN)) {
            seat(
                canvas,
                plan,
                plan.tripSeat(1, seats),
                value = trip.recoveredKwh.takeIf { scene.fresh(ContourValue.TRIP_REGEN) },
                word = ContourReadout.CAPTION_REGEN,
                marked = true,
                odometer = null,
            )
        }
        if (scene.known(ContourValue.TRIP_ENGINE)) {
            seat(
                canvas,
                plan,
                plan.tripSeat(seats.size - 1, seats),
                value = trip.engineKwh.takeIf { scene.fresh(ContourValue.TRIP_ENGINE) },
                word = ContourReadout.CAPTION_ENGINE_GAVE,
                marked = false,
                odometer = null,
            )
        }
    }

    /**
     * One cell of the right shelf: a figure with its unit, over a phrase saying what it is *of*.
     *
     * The figure is right-aligned inside its own reserve field and «кВт·ч» hangs off the field's
     * edge rather than off the string, so a tenth or a second digit moves nothing. Everything is
     * left-aligned against the cell, because a phrase is read from its left.
     *
     * The unit belongs to the figure: if the value has gone stale it leaves with it and the words
     * stay, which is why the odometer's «42 км» is drawn only when the odometer has answered and the
     * caption is then «ЗА ПОЕЗДКУ» with nothing in front of it.
     */
    private fun seat(
        canvas: Canvas,
        plan: ContourPlan,
        left: Float,
        value: Double?,
        word: String,
        marked: Boolean,
        odometer: Double?,
    ) {
        val figureY = pen.v(plan.shelfFigureBaseline)
        val captionY = pen.v(plan.shelfCaptionBaseline)
        if (value != null) {
            pen.text(
                canvas,
                ContourReadout.tenth(value),
                pen.v(left + plan.tripField),
                figureY,
                InstrumentFace.READING,
                DenzaPalette.MUTED,
                Paint.Align.RIGHT,
            )
            pen.text(
                canvas,
                ContourReadout.UNIT_KWH,
                pen.v(left + plan.tripField + plan.smallGap),
                figureY,
                InstrumentFace.UNIT,
                DenzaPalette.MUTED_DEEP,
            )
        }
        var x = left
        if (marked) {
            pen.dot(
                canvas,
                pen.v(left + plan.markRadius),
                captionY - pen.v(InstrumentFace.CAPTION.capHeight / 2f),
                plan.markRadius,
                DenzaPalette.RETURN,
            )
            x = left + plan.markWidth
        }
        if (odometer == null) {
            val alone = if (word == ContourReadout.CAPTION_TRIP) ContourReadout.CAPTION_TRIP_ALONE else word
            pen.text(canvas, alone, pen.v(x), captionY, InstrumentFace.CAPTION, DenzaPalette.MUTED_DEEP)
            return
        }
        pen.text(
            canvas,
            ContourReadout.whole(odometer),
            pen.v(x + plan.odometerField),
            captionY,
            InstrumentFace.UNIT,
            DenzaPalette.MUTED_DEEP,
            Paint.Align.RIGHT,
        )
        pen.text(
            canvas,
            ContourReadout.UNIT_KM,
            pen.v(x + plan.odometerField + plan.smallGap),
            captionY,
            InstrumentFace.UNIT,
            DenzaPalette.MUTED_DEEP,
        )
        pen.text(
            canvas,
            word,
            pen.v(x + plan.odometerField + plan.smallGap + plan.kilometreWidth + plan.smallGap),
            captionY,
            InstrumentFace.CAPTION,
            DenzaPalette.MUTED_DEEP,
        )
    }

    /**
     * Two minutes of what the engine put back, where the trip's phrase stands otherwise.
     *
     * **One quantity, one sentence.** It carried two runs until the owner looked at the built panel
     * and said the legend telling them apart was not understandable - which is the game lost, since
     * a display read at 90 km/h does not get to need a key. The revolutions went back to being the
     * number in the corner, where they were being read anyway, and what is left is generation as an
     * area of twenty-four five-second steps under a sentence that names it.
     *
     * The span is linear to 30 kW and clamped, which is the same verdict's other half: at the 14 kW
     * this car ordinarily returns, a root over 100 filled a third of the box and read as flat.
     *
     * While the box is up the trip's cells are hidden, and that is not "куда делся баланс": the box
     * only leaves 120 s after the last live sample, so the phrase comes back once rather than once
     * per engine cycle.
     */
    private fun engineBox(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        val bins = t.engineTrace.bins(plan.engineBinSeconds, plan.engineBins)
        val count = bins.size
        if (count <= 0) return

        val right = plan.engineBoxRight
        val left = right - count * plan.enginePitch
        val bottom = plan.engineBoxBottom
        val pitch = pen.v(plan.enginePitch)

        pen.line(
            canvas,
            pen.v(left),
            pen.v(bottom),
            pen.v(right),
            pen.v(bottom),
            DenzaPalette.MUTED_DEEP,
            plan.bandHairline,
        )

        for (index in 0 until count) {
            val generation = bins[index]
            generationYs[index] =
                if (generation == null) Float.NaN else pen.v(plan.engineY(generation))
        }

        // A bin nothing answered in breaks the area rather than being drawn through: a step across
        // a gap would claim the engine held a steady output through five seconds nobody watched.
        ContourRuns.forEach(count, { !generationYs[it].isNaN() }) { start, length ->
            pen.history(
                canvas,
                pen.v(left + start * plan.enginePitch),
                pitch,
                spanOf(generationYs, start, length),
                length,
                pen.v(bottom),
                DenzaPalette.RETURN,
                1f,
                plan.areaEdge,
                DenzaPalette.RETURN,
                ContourPlan.GENERATION_AREA_ALPHA,
            )
        }

        engineLegend(canvas, plan, t, scene, stage)
    }

    /**
     * «● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН», laid out right to left off the shelf's own edge.
     *
     * The whole phrase is `MUTED_DEEP`, figure included: this is a number living in a sentence
     * rather than a reading of its own, the same way the odometer's «42» lives inside «42 км · ЗА
     * ПОЕЗДКУ» one shelf along. The figure sits in a reserve field, so it and its unit leave
     * together when the engine stops and the words do not move.
     *
     * **And the reserve leaves with them.** The box outlives the engine by two minutes, and for that
     * whole time the eighth pass drew `● ⎵⎵ В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН` - 22 units of hole after a
     * dot, which reads as a value that failed to arrive rather than as a field with room in it. With
     * no figure there is nothing for a reserve to hold a place for, so the dot closes up against the
     * words. That is one shift per engine stop, not a jitter: what a reserve buys is stillness while
     * a *number* changes, and by then there is no number left to change.
     */
    private fun engineLegend(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        val y = pen.v(plan.engineLegendBaseline)
        val dotY = y - pen.v(InstrumentFace.CAPTION.capHeight / 2f)
        pen.text(
            canvas,
            plan.legendWindow,
            pen.v(plan.legendWindowX),
            y,
            InstrumentFace.CAPTION,
            DenzaPalette.MUTED_DEEP,
        )
        val generation =
            if (stage.engineRunning && scene.fresh(ContourValue.GENERATION)) t.generationKw else null
        if (generation == null) {
            pen.dot(canvas, pen.v(plan.legendMarkQuietX), dotY, plan.markRadius, DenzaPalette.RETURN)
            return
        }
        pen.dot(canvas, pen.v(plan.legendMarkX), dotY, plan.markRadius, DenzaPalette.RETURN)
        pen.text(
            canvas,
            ContourReadout.whole(generation),
            pen.v(plan.legendFigureRight),
            y,
            InstrumentFace.UNIT,
            DenzaPalette.MUTED_DEEP,
            Paint.Align.RIGHT,
        )
        pen.text(
            canvas,
            ContourReadout.UNIT_KW,
            pen.v(plan.legendUnitX),
            y,
            InstrumentFace.UNIT,
            DenzaPalette.MUTED_DEEP,
        )
    }

    // ---------------------------------------------------------------- the petal

    /**
     * What the last three kilometres cost - always the last three kilometres.
     *
     * The denominator never changes under the figure: standing on P it is still three kilometres and
     * only the tenth appears, because at 100 km/h a tenth changes three times a second and a figure
     * that flickers is a figure nobody reads. Since the seventh pass the unit says so, and while a
     * gun is in the same seat counts down to full instead.
     *
     * **While the engine runs the figure is `MUTED` and says nothing else.** `ConsumptionLog`
     * integrates pack power alone and nobody has logged whether `GENERATION_KW` is already inside
     * `POWER_KW` (B1), so until that log exists this number is the battery's alone - and
     * «кВт·ч/100 км · батарея» was five characters of footnote at 12′ on the one line of the panel
     * that has to be read in a glance. Colour says the same thing without asking anybody to read it.
     */
    private fun petal(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
        stage: ContourStage,
    ) {
        if (stage.mode == ContourMode.UNAVAILABLE) {
            if (stage.message.isEmpty()) return
            pen.text(
                canvas,
                stage.message,
                pen.v(plan.axis),
                pen.v(plan.petalBaseline),
                InstrumentFace.UNIT,
                DenzaPalette.MUTED,
                Paint.Align.CENTER,
            )
            return
        }
        // The history is the consumption log's, and only the history: gating the whole seat on it
        // left «до полной» and its countdown off the panel for an entire charge, because a car
        // standing on P with a gun in has never moved the odometer and a fresh install, a reset
        // journal or a restore behind the retention window all have no closed buckets at all.
        if (scene.known(ContourValue.PETAL) && scene.fresh(ContourValue.PETAL)) {
            history(canvas, plan, t)
        }

        if (stage.mode == ContourMode.CHARGING) {
            chargeSeat(canvas, plan, t, scene)
            return
        }

        if (!scene.known(ContourValue.PETAL)) return
        petalUnit(canvas, plan, ContourReadout.UNIT_PER_100KM)
        if (!scene.fresh(ContourValue.PETAL)) return
        val average = ContourReadout.averageConsumption(ConsumptionWindow.raw(t.consumption)) ?: return
        petalFigure(canvas, plan, ContourReadout.consumption(average, stage.parked), stage)
    }

    /**
     * The countdown that takes the petal's seat while a gun is in.
     *
     * **The unit comes with the figure here**, which is the one place on the panel where it does.
     * Everywhere else a caption arrives with the first reading and stays when the reading goes,
     * because the caption names a quantity the car has answered at least once; «до полной» over
     * nothing names an estimate the charger may never make - the first ten seconds of a charge, or
     * an AC feed that never reports one - and a word standing alone over a hole is not the caption
     * rule, it is a value that failed to arrive.
     */
    private fun chargeSeat(
        canvas: Canvas,
        plan: ContourPlan,
        t: VehicleTelemetry,
        scene: ContourScene,
    ) {
        if (!scene.fresh(ContourValue.CHARGE_LEFT)) return
        val minutes = t.chargeMinutesLeft ?: return
        petalUnit(canvas, plan, ContourReadout.UNIT_CHARGE_LEFT)
        petalFigure(canvas, plan, ContourReadout.chargeLeft(minutes), scene.stage)
    }

    private fun petalUnit(canvas: Canvas, plan: ContourPlan, unit: String) {
        pen.text(
            canvas,
            unit,
            pen.v(plan.petalUnitX),
            pen.v(plan.petalBaseline),
            InstrumentFace.UNIT,
            DenzaPalette.MUTED_DEEP,
        )
    }

    private fun petalFigure(canvas: Canvas, plan: ContourPlan, text: String, stage: ContourStage) {
        pen.text(
            canvas,
            text,
            pen.v(plan.petalFigureRight),
            pen.v(plan.petalBaseline),
            InstrumentFace.FIGURE,
            if (stage.engineRunning) DenzaPalette.MUTED else DenzaPalette.INK,
            Paint.Align.RIGHT,
        )
    }

    /**
     * Three kilometres of closed buckets, as two series standing on the figure's own baseline.
     *
     * The scale is a fixed ladder - 0…30 up the cap, 0…10 back down the descender - rather than an
     * autoscale, because autoscaling meant one bucket changing value redrew the height of all
     * thirty, so the same three kilometres never came back the same shape. There is no dashed mean:
     * the mean is the figure standing next to the box.
     *
     * **Two series, and the second one is only where it happened.** Spending is one continuous grey
     * field across all thirty buckets - on a bucket that gave energy back it lies on the zero line,
     * because what was spent there is nothing - and the return is a blue shape per run of return
     * buckets, hanging under the zero on its own posts. Until the eighth pass it was one field
     * crossing the zero in one colour, with a blue rule along the whole width whether anything had
     * come back or not: «беспорядочно», which it was.
     */
    private fun history(canvas: Canvas, plan: ContourPlan, t: VehicleTelemetry) {
        val buckets = ConsumptionWindow.raw(t.consumption)
        if (buckets.isEmpty()) return
        val count = min(buckets.size, ContourPlan.PETAL_BUCKETS)
        val zero = plan.petalZeroY
        for (index in 0 until count) {
            val value = buckets[buckets.size - count + index].toFloat()
            petalYs[index] = pen.v(plan.petalSpendY(value))
            returnYs[index] = pen.v(plan.petalReturnY(value))
        }
        // The pitch is the box divided by the window rather than by what has arrived, and the run is
        // anchored at the box's right edge, so a history that is still filling grows leftward into
        // its box instead of stretching across it. Stretched, three hundred metres of road would be
        // drawn as three kilometres; anchored at the left, the newest bucket would sit in the middle
        // and the right of the box would read as data that ran out.
        val bucket = plan.petalBoxWidth / ContourPlan.PETAL_BUCKETS
        val pitch = pen.v(bucket)
        val left = plan.petalBoxLeft + (ContourPlan.PETAL_BUCKETS - count) * bucket
        pen.history(
            canvas,
            pen.v(left),
            pitch,
            petalYs,
            count,
            pen.v(zero),
            DenzaPalette.INK,
            ContourPlan.LINE_ALPHA,
            plan.dataLine,
            DenzaPalette.MUTED_DEEP,
            ContourPlan.AREA_ALPHA,
        )
        val first = buckets.size - count
        ContourRuns.forEach(count, { buckets[first + it] < 0.0 }) { start, length ->
            pen.steps(
                canvas,
                pen.v(left + start * bucket),
                pitch,
                spanOf(returnYs, start, length),
                length,
                pen.v(zero),
                DenzaPalette.RETURN,
                ContourPlan.RETURN_AREA_ALPHA,
                DenzaPalette.RETURN_INK,
                plan.dataLine,
            )
        }
        pen.line(
            canvas,
            pen.v(plan.petalBoxLeft),
            pen.v(zero),
            pen.v(plan.petalBoxLeft + plan.petalBoxWidth),
            pen.v(zero),
            DenzaPalette.MUTED_DEEP,
            plan.bandHairline,
        )
    }

    // ---------------------------------------------------------------- colour, and spans

    /** The band's body and the glow: ink out, blue back, nothing inside the dead band. */
    private fun flowColor(flow: ContourFlow): Int = when (flow) {
        ContourFlow.OUT -> DenzaPalette.INK
        ContourFlow.BACK -> DenzaPalette.RETURN
        ContourFlow.NEUTRAL -> DenzaPalette.MUTED
    }

    /** The tip is the live edge of the data, which is what `DATA_PEAK` is for on this panel. */
    private fun edgeColor(flow: ContourFlow): Int = when (flow) {
        ContourFlow.OUT -> DenzaPalette.DATA_PEAK
        ContourFlow.BACK -> DenzaPalette.RETURN_INK
        ContourFlow.NEUTRAL -> DenzaPalette.MUTED
    }

    /** Text lifts one step where a stroke does not: saturated blue at 12′ on black is unreadable. */
    private fun heroColor(flow: ContourFlow): Int = when (flow) {
        ContourFlow.OUT -> DenzaPalette.INK
        ContourFlow.BACK -> DenzaPalette.RETURN_INK
        ContourFlow.NEUTRAL -> DenzaPalette.MUTED
    }

    private fun levelColor(level: ContourReadout.Level): Int = when (level) {
        ContourReadout.Level.NORMAL -> DenzaPalette.MUTED
        ContourReadout.Level.WATCH -> DenzaPalette.WARNING
        ContourReadout.Level.ALERT -> DenzaPalette.DANGER
    }

    /**
     * The lit part inside a glyph: one step brighter than the figure above it, and hot with it.
     *
     * `INK` on the ordinary shelf is the panel's one exception to "INK is the hero alone", and it is
     * a deliberate one: the component is what tells the eye *what* the number above is about before
     * the number is read, and an outline plus a grey blob inside it read as one grey shape. On an
     * exception it takes the figure's own colour, so the cell lights up as one object.
     */
    private fun componentColor(level: ContourReadout.Level): Int = when (level) {
        ContourReadout.Level.NORMAL -> DenzaPalette.INK
        ContourReadout.Level.WATCH -> DenzaPalette.WARNING
        ContourReadout.Level.ALERT -> DenzaPalette.DANGER
    }

    /**
     * One span, packed to the front of the scratch buffer the pen reads.
     *
     * It moves the values rather than allocating a view of them, and it is the same buffer both
     * runs already live in, so a frame allocates nothing here either.
     */
    private fun spanOf(ys: FloatArray, start: Int, length: Int): FloatArray {
        if (start == 0) return ys
        for (index in 0 until length) span[index] = ys[start + index]
        return span
    }

    private companion object {
        /** The panel's own ground: opaque, and the same black the glass around it is. */
        const val BACKGROUND = 0xFF000000.toInt()

        /** The three cars, in `VehicleTelemetry.motorTemps` order: front, rear left, rear right. */
        val MOTOR_GLYPHS = arrayOf(
            ContourGlyphs.Glyph.MOTOR_FRONT,
            ContourGlyphs.Glyph.MOTOR_REAR_LEFT,
            ContourGlyphs.Glyph.MOTOR_REAR_RIGHT,
        )

        /** How far the peak's mark stands out of the band's body, top and bottom. */
        const val PEAK_OVERHANG = 3f
        const val PEAK_WIDTH = 3f

        /**
         * The night hook, and it is 1.0.
         *
         * There is no night scene: the cluster's own dimmer already darkens our window, and whether
         * it does is a measurement on the car rather than a decision on a board (m7). The multiplier
         * is here so that the answer, when somebody takes it, lands in one place - and so that
         * nobody reaches for alpha again, which used to mean seven things at once.
         */
        const val NIGHT_DIM = 1f
    }
}
