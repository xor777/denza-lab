package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.EnergyScale
import dev.denza.apps.design.instrument.InstrumentDensity
import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.EngineTrace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Every coordinate the Contour has, derived the way `tools/design-canvas/gen_contour.py` derives it.
 *
 * The two records are joined by `ContourBoardContractTest`, which parses the boards and holds them
 * against this class in both directions: editing a board without this file breaks it, and so does
 * editing this file without the board. Neither record can move alone.
 *
 * ### Nothing here is typed
 *
 * The apertures are `ClusterMapLayout`'s own integer arithmetic, restated through
 * [ClusterDashboardLayout] so the two cannot drift. Everything else comes out of five decisions and
 * nothing else: **one margin of 48, a rhythm of 8, the rungs of [InstrumentDensity.RAMP], the cap
 * height, and two guards of 24 units off the stock zones, top and bottom.**
 *
 * The two guards are the same number and it is the jury's: three rhythm steps between the hero's cap
 * and the stock speedometer's zone. The review then found the shelves' headings standing 7 units
 * from that boundary and the band's limit labels 10 from the other one - the first things a boundary
 * nobody has photographed would cut off. So [guardTop] and [guardBottom] are two constants and
 * everything hangs off one of them: the hero's cap top, both shelves' cap tops, the engine box's top
 * edge, and the lower edge of the band's body, which clears its guard by 18.
 *
 * ### No coordinate depends on data
 *
 * Every number on the panel lives in a reserve field sized by its maximum digit count times a
 * measured advance, its unit hangs off the field rather than off the string, and neighbours are set
 * against the field's edge. Gaining a digit moves nothing: 34 → 128, 9,3 → 12,4, 42 km → 142 km.
 * That is the structural half of the owner's original complaint, and `ContourPlanTest` states it as
 * an invariant rather than as a hope.
 *
 * Built once when the view is sized, never in a frame.
 */
internal class ContourPlan(
    val layout: ClusterDashboardLayout,
    private val type: ContourType,
) {

    private val density = InstrumentDensity.WIDE

    // ---------------------------------------------------------------- the panel

    /** The virtual space, 424 units tall, and as wide as the window's own aspect makes it. */
    val height: Float = layout.virtualHeight
    val width: Float =
        if (layout.height <= 0) 0f else height * layout.width / layout.height
    val axis: Float = width / 2f

    /** Where the vehicle's own graphics start and stop, in this space. */
    val stockTop: Float = layout.stockTop * height
    val stockBottom: Float = layout.stockBottom * height

    /** The two corner apertures: quarter-ellipses anchored at the top corners. */
    val leftApertureRx: Float = layout.topLeftRevealX * width
    val rightApertureRx: Float = layout.topRightRevealX * width
    val apertureRy: Float = stockTop

    /** And the petal, which is a half-ellipse hanging below the clear band. */
    val petalRx: Float = layout.bottomRevealX * width
    val petalRy: Float = layout.bottomRevealY * height
    val petalCy: Float = layout.bottomRevealCentreY * height

    // ---------------------------------------------------------------- the skeleton

    val step: Float = density.step
    val margin: Float = density.rhythm(MARGIN_STEPS)
    val leftEdge: Float = margin
    val rightEdge: Float = width - margin

    /** Between a large figure and its unit, and between a small one and its own. */
    val unitGap: Float = density.rhythm(4f)
    val smallGap: Float = density.rhythm(1f)

    val clearance: Float = density.rhythm(GUARD_STEPS)
    val guardTop: Float = stockTop + clearance
    val guardBottom: Float = stockBottom - clearance

    // ---- the hero

    val heroBaseline: Float = guardTop + InstrumentFace.HERO.capHeight
    val heroCapTop: Float = heroBaseline - InstrumentFace.HERO.capHeight

    /**
     * Three digits is the ceiling this scale can produce, so the field is knowable in advance.
     *
     * The digits are right-aligned inside the field, and the field, its gap and its unit are centred
     * on the axis **as one group**: centring the field alone left «кВт» stranded from a two-digit
     * reading and touching a three-digit one.
     */
    val heroFieldWidth: Float = 3 * type.width("0", InstrumentFace.HERO)
    val heroUnitWidth: Float = type.width(ContourReadout.UNIT_KW, InstrumentFace.READING)
    val heroGroupWidth: Float = heroFieldWidth + unitGap + heroUnitWidth
    val heroFieldRight: Float = axis + heroGroupWidth / 2f - unitGap - heroUnitWidth
    val heroFieldLeft: Float = heroFieldRight - heroFieldWidth
    val heroUnitX: Float = heroFieldRight + unitGap

    // ---- the band

    val bandY: Float = heroBaseline + density.rhythm(5f)
    val bandHalf: Float = axis - margin
    val bandBody: Float = BAND_BODY
    val bandHairline: Float = HAIRLINE
    val zeroHalf: Float = BAND_BODY
    val zeroWidth: Float = ZERO_WIDTH

    /** The fallback drawing of generation: a separate line under the body, from zero. */
    val generationLineY: Float = bandY + BAND_BODY / 2f + GENERATION_LINE_H
    val generationLineHeight: Float = GENERATION_LINE_H

    /** Nothing that carries data is thinner than this. */
    val dataLine: Float = DATA_LINE
    val areaEdge: Float = AREA_EDGE

    val glowCentreX: Float = axis
    val glowCentreY: Float = bandY
    val glowRadiusX: Float = GLOW_RX

    /** Reaches zero exactly on the lower edge of the clear band, so it never crosses a boundary. */
    val glowRadiusY: Float = stockBottom - bandY

    // ---- the corners: one heading and one figure, and that is the whole corner

    val cornerTitleBaseline: Float = density.rhythm(GUARD_STEPS)
    val cornerFigureBaseline: Float =
        cornerTitleBaseline + density.rhythm(2f) + density.figure

    /** Volts: three digits, hard against the margin. */
    val voltsFieldRight: Float = leftEdge + 3 * type.width("0", InstrumentFace.FIGURE)

    /** Revolutions: four, hard against the other one. */
    val rpmFieldRight: Float = rightEdge

    // ---- the shelves, which are one family in every respect

    val shelfFigureBaseline: Float = guardTop + InstrumentFace.READING.capHeight
    val shelfCaptionBaseline: Float =
        shelfFigureBaseline + density.rhythm(2f) + density.body

    val cellGap: Float = density.rhythm(2f)

    val markRadius: Float = MARK_RADIUS
    val markGap: Float = density.rhythm(1f)
    val markWidth: Float = 2 * MARK_RADIUS + markGap

    /** Two digits. A hundred and more is an alert and may hang past the field. */
    val temperatureField: Float = 2 * type.width("0", InstrumentFace.READING)
    val degreeWidth: Float = type.width(ContourReadout.DEGREE, InstrumentFace.READING)
    val millivoltWidth: Float =
        type.width(ContourReadout.UNIT_MILLIVOLT, InstrumentFace.READING)

    val spreadPayload: Float = temperatureField + step + millivoltWidth

    /** Where a glyph stands, and it stands *on* the caption baseline rather than hanging from it. */
    val glyphBaseline: Float = shelfCaptionBaseline
    val glyphInset: Float = ContourGlyphs.INSET

    /**
     * **A cell is exactly as wide as the wider of the two things it has to hold** - its payload or
     * the thing that names it - and that width is not rounded up to the rhythm.
     *
     * The rounding was 19.5 units of air across four cells and «РАЗБРОС ЯЧЕЕК» needed every one of
     * them. It was the cheapest thing on the panel to sell: nothing on a shelf is a rectangle, so
     * the rhythm was quantising a distance nobody can see, while the clearance to the hero's field
     * is a distance the reader would have met the moment a three-digit power arrived.
     *
     * Since the ninth pass the thing that names a temperature cell is a glyph rather than a word, so
     * the max is against [ContourGlyphs.WIDTH] and the figure wins it: five identical cells of 50.9,
     * where «ИНВЕРТОР» alone was 110. Each keeps its own «°», which the row can now afford - the
     * three motors shared one sign from the sixth pass to the eighth and those 25 units were exactly
     * what «РАЗБРОС ЯЧЕЕК» needed. The captions the glyphs replaced paid for them twice over.
     */
    val temperatureCell: Float = max(temperatureField + degreeWidth, ContourGlyphs.WIDTH)

    /** And the sixth cell, which is the only one still sized by a caption. */
    val spreadCell: Float = max(caption(ContourReadout.CAPTION_SPREAD), spreadPayload)

    val leftCells: FloatArray = FloatArray(ContourGlyphs.Glyph.entries.size + 1) { index ->
        if (index < ContourGlyphs.Glyph.entries.size) temperatureCell else spreadCell
    }

    /**
     * The exception's seat, which is the last one and exists only while the pack misbehaves.
     *
     * That is the second thing the glyphs bought: a *word* in the temperature row now means
     * something is wrong, because it is the only word left in it.
     */
    val spreadCellIndex: Int = ContourGlyphs.Glyph.entries.size

    /** The widest the shelf ever is - the five glyph cells and the exception behind them. */
    val leftShelfRight: Float = leftEdge + leftCells.sum() + (leftCells.size - 1) * cellGap

    // ---- the right shelf: a phrase, not a ledger

    /** «12,4» at its widest. */
    val tripField: Float = 3 * type.width("0", InstrumentFace.READING) +
        type.width(",", InstrumentFace.READING)

    /** «42» with room for «999», in the caption's own face. */
    val odometerField: Float = 3 * type.width("0", InstrumentFace.UNIT)

    val kilowattHourWidth: Float = type.width(ContourReadout.UNIT_KWH, InstrumentFace.UNIT)
    val kilometreWidth: Float = type.width(ContourReadout.UNIT_KM, InstrumentFace.UNIT)

    val tripPayload: Float = tripField + smallGap + kilowattHourWidth

    /**
     * The kilometres lead the phrase: «42 км · ЗА ПОЕЗДКУ».
     *
     * The odometer's field holds three digits and the printed number is right-aligned inside it
     * either way. Trailing, that reserve stood between the «·» and the number - ten units of nothing
     * after a separator, which reads as a value that failed to arrive. Leading, the same reserve is
     * the caption's own left margin.
     */
    val tripLead: Float = odometerField + smallGap + kilometreWidth + smallGap +
        caption(ContourReadout.CAPTION_TRIP)

    val tripCell: Float = max(tripPayload, tripLead)
    val regenCell: Float =
        max(tripPayload, markWidth + caption(ContourReadout.CAPTION_REGEN))
    val engineCell: Float = max(tripPayload, caption(ContourReadout.CAPTION_ENGINE_GAVE))

    /**
     * Seats, counted right to left from the shelf's own edge, so the first is always against it.
     *
     * They are per state rather than per panel: standing still adds `РЕКУПЕРАЦИЯ` *between* the two,
     * which is a gear change rather than a value arriving, and the seat that exists on both is the
     * same cell in the same place.
     */
    val driveSeats: FloatArray = floatArrayOf(tripCell, engineCell)
    val parkSeats: FloatArray = floatArrayOf(tripCell, regenCell, engineCell)

    /** The widest the shelf ever is, which is also the engine box's own left limit. */
    val rightShelfLeft: Float =
        rightEdge - parkSeats.sum() - (parkSeats.size - 1) * cellGap

    // ---- the engine's own two minutes

    val engineSlots: Int = ENGINE_SLOTS

    /**
     * And the box draws those slots as [ENGINE_BINS] steps of [ENGINE_BIN_SECONDS].
     *
     * A per-second line across this box was 120 points inside 526 units - 4.4 apart, which is 0.9 mm
     * of glass for one sample of a quantity that moves on the scale of a traffic light. A step of
     * five seconds is the mean of the samples that arrived in it, so a poll the shell missed costs
     * resolution rather than a hole, and the steps say what they are: closed buckets, like the
     * petal's hundred metres.
     */
    val engineBins: Int = ENGINE_BINS
    val engineBinSeconds: Int = ENGINE_BIN_SECONDS
    val engineBoxRight: Float = rightEdge
    val engineBoxFullLeft: Float = rightShelfLeft
    val engineBoxWidth: Float = engineBoxRight - engineBoxFullLeft

    /** One step, so a box `n` steps wide starts `n · pitch` left of the shelf's own edge. */
    val enginePitch: Float = engineBoxWidth / ENGINE_BINS

    /**
     * The box takes both of the shelf's rows, not the digits' ink box alone.
     *
     * Two runs inside 24 units came out as a blue thread with a grey thread on top of it - «графики
     * очень сильно сплющены по вертикали», the owner's whole verdict on the fourth drawing, and 24
     * units is 5 mm of this glass for two curves. The top is the same guard everything else hangs
     * off, which is also the shelf figures' own cap top, so the swap moves no neighbour's baseline.
     *
     * On the built panel he said «сплющен» again, of a box that had already taken every unit there
     * is between the two guards. What was flat the second time was the *scale*, and that is what
     * moved: see [ContourReadout.GENERATION_FULL_KW].
     */
    val engineBoxTop: Float = guardTop

    /** The phrase hangs off the band's guard from below, and the box stops a step above its caps. */
    val engineLegendBaseline: Float = bandY - BAND_BODY / 2f - clearance
    val engineBoxBottom: Float =
        engineLegendBaseline - InstrumentFace.CAPTION.capHeight - step

    val engineGenerationFull: Float = ContourReadout.GENERATION_FULL_KW.toFloat()

    /** Two digits of generation, in the phrase's own face. */
    val generationField: Float = 2 * type.width("0", InstrumentFace.UNIT)
    val kilowattWidth: Float = type.width(ContourReadout.UNIT_KW, InstrumentFace.UNIT)

    /** Whether the face in use crowds «ПОСЛЕДНИЕ» out of the phrase. */
    val legendShortened: Boolean = shortWindow()

    /**
     * «● 14 кВт В БАТАРЕЮ · ПОСЛЕДНИЕ 1:22», laid out right to left off the shelf's own edge -
     * and this is the template the phrase is *measured* from, not the string drawn in it.
     *
     * One sentence where the seventh pass had a legend of two words and a figure - the owner could
     * not read the legend, and a display with no room for a key must not need one. The words say
     * what the shape above them is and how far back it goes; the figure says what it is worth now.
     *
     * The figure lives in a reserve field like every other number on this panel, so 9 kW and 14 kW
     * start the phrase in the same place, and when the engine stops the figure and its unit leave
     * together while the words stay - the panel's one rule for a stale reading, applied to a number
     * living inside a sentence. It is the odometer's own arrangement in «42 км · ЗА ПОЕЗДКУ».
     *
     * The window is the box's own reach - «ПОСЛЕДНИЕ 0:40» while the engine has been alive for
     * forty seconds - and every value of it is one width, because the figures are tabular and a
     * «м:сс» is always four glyphs and a mark. So one template decides every anchor in the phrase
     * and the drawn duration moves none of them. [ContourReadout.intoPack] is what is drawn.
     */
    val legendWindow: String =
        if (legendShortened) ContourReadout.LEGEND_INTO_PACK_SHORT else ContourReadout.LEGEND_INTO_PACK
    val legendWindowWidth: Float = caption(legendWindow)
    val legendWindowX: Float = rightEdge - legendWindowWidth
    val legendUnitX: Float = legendWindowX - smallGap - kilowattWidth
    val legendFigureRight: Float = legendUnitX - smallGap
    val legendMarkX: Float = legendFigureRight - generationField - markGap - markRadius

    /**
     * And where the dot stands once the engine has stopped: against the words.
     *
     * A reserve field keeps a phrase still while a *number* changes; when the number leaves there is
     * nothing left for it to reserve, and the eighth pass left it standing anyway - 22 units of hole
     * between the dot and «В БАТАРЕЮ» for the whole two minutes the box outlives the engine. This is
     * one shift per engine stop rather than a jitter, and it is the only coordinate on the panel
     * that depends on whether a value is there rather than on what it is.
     */
    val legendMarkQuietX: Float = legendWindowX - markGap - markRadius

    /** What the whole phrase measures, dot included, which is what decides the window. */
    val legendPhraseWidth: Float = rightEdge - (legendMarkX - markRadius)

    // ---- the petal, and the three kilometres behind its figure

    val petalBaseline: Float = PETAL_BASELINE
    val petalFloor: Float = PETAL_FLOOR
    val petalBuckets: Int = PETAL_BUCKETS

    /** «16,8» and «2:15» are both three digits and one mark, so one field holds either. */
    val petalFieldWidth: Float = 3 * type.width("0", InstrumentFace.FIGURE) +
        max(type.width(",", InstrumentFace.FIGURE), type.width(":", InstrumentFace.FIGURE))

    /** And two digits is what the panel actually prints while the car is moving. */
    val petalPrintedWidth: Float = 2 * type.width("0", InstrumentFace.FIGURE)

    /**
     * The widest the unit ever is, which is the window still filling: «· за 1,2 км».
     *
     * The full form is narrower. Nothing is anchored off this - the unit is left-aligned against
     * the figure's own reserve and there is nothing to its right - so the width is a clearance
     * against the petal's cut-out rather than a coordinate.
     */
    val petalUnitWidth: Float =
        max(
            type.width(ContourReadout.UNIT_PER_100KM, InstrumentFace.UNIT),
            type.width(ContourReadout.UNIT_PER_100KM_FILLING, InstrumentFace.UNIT),
        )

    /**
     * **The figure centres on the axis, and the box hangs off it.**
     *
     * The fifth board centred the whole composition - box, gap, field, gap, unit - so the digits
     * landed 82 units right of the hero's and the box carried the panel's midpoint on its back. Now
     * the *printed* figure is what centres: two digits, right-aligned on a fixed anchor, so standing
     * on P the tenth grows the field leftward and moves neither the unit nor the box.
     *
     * The accident worth keeping: a two-digit 52 centred on the axis ends at 783.00 and the hero's
     * three-digit field ends at 783.04, so the two figures share a right edge and «кВт» and
     * «кВт·ч/100 км» start on the same x.
     */
    val petalFigureRight: Float = axis + petalPrintedWidth / 2f
    val petalUnitX: Float = petalFigureRight + unitGap

    /**
     * The box hangs off the *widest* the field ever gets, so the gap is a floor rather than an
     * average: on P it closes to 26, on the move it opens to 66, and the box does not move between
     * the two.
     */
    val petalBoxGap: Float = density.rhythm(3f)
    val petalBoxRight: Float = petalFigureRight - petalFieldWidth - petalBoxGap
    val petalBoxWidth: Float = density.rhythm(PETAL_BOX_STEPS)
    val petalBoxLeft: Float = petalBoxRight - petalBoxWidth

    /**
     * **The box's zero line is the figure's own baseline**, and its two edges are the numeral's.
     *
     * The owner, on the built panel: «ноль должен быть у цифры». The fifth pass gave this box 56
     * units and a zero line four fifths down, and both numbers were the box's alone - a ladder
     * standing next to a 52 and agreeing with nothing on it. The three lines that bound the history
     * are the three lines of the figure beside it now: spending rises from the baseline through the
     * cap height, a return hangs under it by a descender, and there is nothing left to choose.
     */
    val petalBoxTop: Float = petalBaseline - InstrumentFace.FIGURE.capHeight
    val petalZeroY: Float = petalBaseline
    val petalBoxBottom: Float = petalBaseline + PETAL_DESCENDER_EM * InstrumentFace.FIGURE.size
    val petalBoxHeight: Float = petalBoxBottom - petalBoxTop

    /**
     * A fixed ladder, not an autoscale: 0…30 up the cap and 0…10 back down the descender.
     *
     * Autoscaling to each window's own ceiling meant a bucket changed height when a *different*
     * bucket changed value, so the shape of the last three kilometres was never twice the same
     * shape. On the states board the traffic jam's history is visibly taller than calm driving's,
     * which under the old autoscale it was not. 30 rather than the fifth pass's 40 because the two
     * spans no longer share a divisor: what set 40 was a zero line at four fifths of a box, and the
     * zero line is a baseline now.
     */
    val petalFull: Float = PETAL_FULL
    val petalReturnFull: Float = PETAL_RETURN_FULL

    /** Where a spending bucket's step falls. A bucket that gave energy back sits on the zero. */
    fun petalSpendY(value: Float): Float =
        petalZeroY - min(max(value, 0f) / petalFull, 1f) * (petalZeroY - petalBoxTop)

    /** And where a returning one's does. A bucket that spent sits on the zero in this series. */
    fun petalReturnY(value: Float): Float =
        petalZeroY + min(max(-value, 0f) / petalReturnFull, 1f) * (petalBoxBottom - petalZeroY)

    /** Where one step of the engine's box falls, on its own linear span. */
    fun engineY(kilowatts: Double): Float =
        engineBoxBottom - ContourReadout.generationFraction(kilowatts) *
            (engineBoxBottom - engineBoxTop)

    // ---------------------------------------------------------------- arithmetic

    /** Where the left edge of the [index]th temperature cell falls. */
    fun leftCell(index: Int): Float {
        var left = leftEdge
        for (i in 0 until index) left += leftCells[i] + cellGap
        return left
    }

    /** Where the left edge of the [index]th trip seat falls, counted from the shelf's own edge. */
    fun tripSeat(index: Int, seats: FloatArray): Float {
        var right = rightEdge
        for (i in 0 until index) right -= seats[i] + cellGap
        return right - seats[index]
    }

    /** How much room a corner aperture still has at baseline [y]. */
    fun apertureReach(y: Float, right: Boolean): Float {
        val rx = if (right) rightApertureRx else leftApertureRx
        val t = 1f - (y / apertureRy) * (y / apertureRy)
        return if (t > 0f) rx * sqrt(t) else 0f
    }

    private fun petalReach(y: Float): Float {
        val dy = (y - petalCy) / petalRy
        val t = 1f - dy * dy
        return if (t > 0f) petalRx * sqrt(t) else 0f
    }

    /** Where the petal's cut-out has its left edge at [y] - the history box's own limit. */
    fun petalRoom(y: Float): Float = axis - petalReach(y)

    /** And its right edge, which is what the petal's unit has to stay inside. */
    fun petalEdge(y: Float): Float = axis + petalReach(y)

    /** Where a reading lands on the band. The scale is [EnergyScale]: one square root, two spans. */
    fun bandX(kilowatts: Float): Float {
        val travel = EnergyScale.sweepFraction(kilowatts) * bandHalf
        return if (kilowatts < 0f) axis - travel else axis + travel
    }

    /**
     * How bright the pool of light is: `0.18·√(|P| / 120 kW)`, saturated at 120 kW.
     *
     * The fourth board took the band's own travel fraction, which is a square root over 300 kW out
     * and 100 kW back. That gave the glow two meanings by direction - 42 kW of braking outshone
     * 100 kW of pulling - while leaving calm driving at 0.06 of a scale that only fills at the car's
     * absolute limit. One span, the pedal's own working range, puts calm at 0.10 and saturates an
     * acceleration.
     */
    fun glowAlpha(kilowatts: Float): Float {
        val magnitude = abs(kilowatts)
        if (magnitude <= EnergyScale.FLOOR_KW) return 0f
        return GLOW_MAX * sqrt(min(magnitude / GLOW_FULL_KW, 1f))
    }

    private fun caption(text: String): Float = type.width(text, InstrumentFace.CAPTION)

    /**
     * The long window, unless the face in use crowds the phrase against its own box.
     *
     * Measured rather than assumed, because this is the one string on the panel long enough for the
     * difference between Chrome's Roboto and the car's to matter: the test is the phrase's own width
     * plus one guard against the width of the box it stands under. Nothing else in it can go.
     */
    private fun shortWindow(): Boolean {
        val phrase = markWidth + generationField + smallGap + kilowattWidth + smallGap +
            caption(ContourReadout.LEGEND_INTO_PACK)
        return phrase + clearance > engineBoxWidth
    }

    companion object {
        /** The one outer margin, in rhythm steps: 48. */
        const val MARGIN_STEPS = 6f

        /** And the two guards, which are the same number: 24, the jury's three steps. */
        const val GUARD_STEPS = 3f

        const val BAND_BODY = 14f
        const val ZERO_WIDTH = 1.8f
        const val HAIRLINE = 1.2f
        const val DATA_LINE = 2.5f
        const val AREA_EDGE = 1.8f
        const val GENERATION_LINE_H = 4f

        const val GLOW_RX = 340f
        const val GLOW_MAX = 0.18f

        /** The pedal's own working range, which is what the glow is scaled against. */
        const val GLOW_FULL_KW = 120f

        const val MARK_RADIUS = 3f

        /**
         * The box is exactly as wide as the trace reaches, which is [EngineTrace]'s own window.
         *
         * A literal here was a second copy of a retention decision that lives in the class holding
         * the data: shorten the trace and the board would have gone on drawing a box for two
         * minutes of a history that was ninety seconds long.
         */
        const val ENGINE_SLOTS = EngineTrace.SLOTS

        /** Drawn as twenty-four steps of five seconds, the way the petal draws its buckets. */
        const val ENGINE_BIN_SECONDS = 5
        const val ENGINE_BINS = ENGINE_SLOTS / ENGINE_BIN_SECONDS

        /**
         * The petal's history box is 232 wide, and its height is the figure's own.
         *
         * 232 is the aperture's number rather than a taste. The jury asked for 270; with the figure
         * on the axis the petal's ellipse leaves 238.5 at the box's lower left corner once the
         * 8-unit guard is taken, so the width is the next whole step under that.
         */
        const val PETAL_BOX_STEPS = 29f

        /** Roboto's own descender, which is how far under the baseline a return may hang. */
        const val PETAL_DESCENDER_EM = 0.25f

        /** The petal's baseline, and the floor nothing is drawn below. */
        const val PETAL_BASELINE = 384f
        const val PETAL_FLOOR = 410f

        /**
         * Three kilometres of `ConsumptionLog`'s hundred-metre buckets, which is the window's own
         * count rather than a second statement of it.
         *
         * A `val` rather than a `const val` because [ConsumptionWindow] derives it from the three
         * kilometres and the bucket size, and that derivation is the single fact.
         */
        val PETAL_BUCKETS = ConsumptionWindow.buckets

        const val PETAL_FULL = 30f
        const val PETAL_RETURN_FULL = 10f

        /** The alphas, and every one of them belongs to a fill rather than to a state. */
        const val AREA_ALPHA = 0.55f
        const val LINE_ALPHA = 0.70f

        /** The petal's return, which is the only blue thing under the axis. */
        const val RETURN_AREA_ALPHA = 0.50f
        const val GENERATION_AREA_ALPHA = 0.55f
        const val PEAK_ALPHA = 0.85f
    }
}
