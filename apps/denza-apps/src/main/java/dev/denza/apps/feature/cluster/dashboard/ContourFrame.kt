package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.LuminoforSpec

/**
 * Everything one picture of the Contour is drawn from, and nothing else.
 *
 * The Luminofor board draws the cluster from a fixture - `drawCluster(c, f)` in
 * `tools/design-canvas/luminofor/luminofor.js` - and this is that `f`, field for field: the printed
 * strings, the kilowatts the band is drawn at, the hundred points, the engine's bins, and the
 * colour decisions the board makes from data. [ClusterDashboardRenderer] draws a frame and asks
 * nothing else, so a frame built from the car and a frame read off a fixture
 * (`ContourFixtures`, debug builds only) go through the same drawing code, and a screenshot of the
 * one can be laid over the PNG of the other.
 *
 * ### Who decides what
 *
 * Every *rule* stays where it was: [ContourScene] decides known against fresh, [ContourMotion]
 * the followers, the peak and the hero's hysteresis, `EnergyReadouts` every energy string, and
 * [ContourFrameBuilder] turns their answers into this. A field is null where the board would draw
 * nothing: a caption before its quantity has ever arrived, a figure after its horizon. That is the
 * caption rule - «a value is removed one horizon after its last sample and its caption stays» -
 * written as data rather than as early returns inside a renderer.
 *
 * ### One instance, refilled
 *
 * The panel redraws at up to thirty frames a second over the vehicle's own instruments, so this is
 * not an immutable value built per frame: the builder owns one and refills it, the arrays are sized
 * once, and every string in it is a memoised one. A fixture gets its own instance.
 */
internal class ContourFrame {

    /** How a figure the board colours by data is coloured. */
    enum class Tone { INK, BLUE, GREY }

    /** One cell of the temperature row: its glyph, its figure, and whether it is out of line. */
    class Temperature {
        /** Whether the glyph - the cell's caption - is drawn at all. */
        var shown: Boolean = false

        /** The figure without its «°», which is drawn after it; null once the reading is stale. */
        var value: String? = null
        var level: ContourReadout.Level = ContourReadout.Level.NORMAL

        fun clear() {
            shown = false
            value = null
            level = ContourReadout.Level.NORMAL
        }
    }

    /**
     * Seconds of real time, for the two things on the panel that move with nothing else: the
     * threads along the beam and the pulse behind a hot cell. Wrapped by whoever keeps it; every
     * rate those two use is a whole number of radians a second, so the wrap is invisible.
     */
    var t: Float = 0f

    // ---- the panel as a whole

    /** No access: the skeleton and [message], and nothing else is drawn. */
    var unavailable: Boolean = false
    var message: String = ""

    // ---- the centre

    /** Whether the band has a reading it may draw: the beam, its threads, its head, the glow and the peak. */
    var powerFresh: Boolean = false

    /** Pack power as the band draws it, signed, positive out of the pack: the beam's length and side. */
    var powerKw: Float = 0f

    /** And as the glow sees it: the same reading on a slower follower. */
    var glowKw: Float = 0f

    /** Energy is going into the pack: the hero, its unit and the glow are blue. */
    var into: Boolean = false

    /** Whether «кВт» stands beside the hero - it arrives with the first reading and stays. */
    var heroUnit: Boolean = false

    /** `|P|` in whole kilowatts, or null while there is no fresh reading. */
    var heroFigure: String? = null

    /** The furthest the band has been lately, signed, or `NaN` when there is no mark to draw. */
    var peakKw: Float = Float.NaN

    /** How long the peak has stood where it is, which is what fades its mark. */
    var peakAge: Float = 0f

    // ---- the left group

    var batteryCaption: String? = null
    var volts: String? = null
    val temps: Array<Temperature> = Array(CELLS) { Temperature() }

    /** The cell spread, only while it is out of line: «РАЗБРОС ЯЧЕЕК 32 мВ» under the battery. */
    var spreadCaption: String? = null
    var spreadValue: String? = null
    var spreadUnit: String? = null
    var spreadLevel: ContourReadout.Level = ContourReadout.Level.NORMAL

    // ---- the right group

    /** The engine's cell at the group's left edge: «ДВС · об/мин» or «ДВС · мин за поездку». */
    var iceCaption: String? = null
    var iceFigure: String? = null

    /** The engine gives: its box stands where the trip is, and [generation] is what it draws. */
    var engineGiving: Boolean = false

    /** The box's bins, oldest first, right-anchored; `NaN` where nothing answered. */
    val generation: FloatArray = FloatArray(GENERATION_BINS)
    var generationCount: Int = 0

    /** «ДВС ДАЁТ 14 кВт», or the words alone while there is no figure. */
    var engineCaption: String = ""

    /** «ПОСЛЕДНИЕ 1:22», under the box. */
    var engineWindow: String = ""

    /** «42 км · ЗА ПОЕЗДКУ», or null while the trip has not answered: then the shelf is empty. */
    var tripCaption: String? = null
    var tripKwh: String? = null
    var tripUnit: String = ContourReadout.UNIT_KWH

    /** The detail line under the trip, right to left: «1,1 кВт·ч ДАЛ ДВС», then «● 3,1 кВт·ч РЕКУПЕРАЦИЯ». */
    var gaveCaption: String? = null
    var gaveKwh: String? = null
    var regenCaption: String? = null
    var regenKwh: String? = null

    // ---- the ten kilometres

    /** The hundred points, oldest first; fewer while the window fills, and they grow from the right. */
    val chart: FloatArray = FloatArray(LuminoforSpec.Cluster.Trace.POINTS)
    var chartCount: Int = 0

    /** The figure beside the trace: the consumption, or the charge countdown in its seat. */
    var consumption: String? = null
    /**
     * The last figure printed while the consumption is stale: not drawn, only measured, so the unit
     * stays where that figure left it instead of sliding onto the empty seat.
     */
    var consumptionHeld: String? = null

    var consumptionUnit: String? = null
    var consumptionTone: Tone = Tone.INK

    /** Back to a panel that has heard nothing, which is also what a builder starts every frame from. */
    fun clear() {
        t = 0f
        unavailable = false
        message = ""
        powerFresh = false
        powerKw = 0f
        glowKw = 0f
        into = false
        heroUnit = false
        heroFigure = null
        peakKw = Float.NaN
        peakAge = 0f
        batteryCaption = null
        volts = null
        temps.forEach { it.clear() }
        spreadCaption = null
        spreadValue = null
        spreadUnit = null
        spreadLevel = ContourReadout.Level.NORMAL
        iceCaption = null
        iceFigure = null
        engineGiving = false
        generationCount = 0
        engineCaption = ""
        engineWindow = ""
        tripCaption = null
        tripKwh = null
        tripUnit = ContourReadout.UNIT_KWH
        gaveCaption = null
        gaveKwh = null
        regenCaption = null
        regenKwh = null
        chartCount = 0
        consumption = null
        consumptionHeld = null
        consumptionUnit = null
        consumptionTone = Tone.INK
    }

    companion object {
        /** Pack, front motor, rear left, rear right, inverter - `ThermalGlyphs.Cell`'s order. */
        const val CELLS = 5

        /** The engine's box: twenty-four steps of five seconds, `EngineTrace`'s own two minutes. */
        const val GENERATION_BINS = ContourGeometry.ENGINE_BINS
    }
}
