package dev.denza.apps.feature.trip

/**
 * One reading as the strip prints it: a caption over a figure, the figure's unit small on its
 * baseline, and - for the altitude alone - the variometer's rate beside it.
 *
 * The Luminofor board's reading, field for field (`luminofor.js`, `reading()`): `cap`, `fig`,
 * `unit`, `rate`, `dot` and `col`. A reading is refreshed in place rather than rebuilt, because the
 * strip reads thirty times a second over values that change once a second at most.
 *
 * **A figure that is not there takes its unit and its rate with it, and the caption stays.** That is
 * the energy contract's one staleness rule (§2.1: «Unavailable is not zero: no figure, the caption
 * stays»), and it holds for every reading on both pages rather than for the energy ones alone.
 */
class StripReading {

    /** Whether the reading is on the strip at all. An absent reading takes no room. */
    var present: Boolean = false
        private set

    var caption: String = ""
        private set

    /** In the wide square figures ([dev.denza.apps.design.luminofor.WideDigits]), or null. */
    var figure: String? = null
        private set

    /** Small Roboto on the figure's baseline, or null. */
    var unit: String? = null
        private set

    /** The variometer's rate in the small figures, or null; [rateUp] says which way it points. */
    var rate: String? = null
        private set

    var rateUp: Boolean = true
        private set

    /** The blue mark in front of the caption: energy is going *into* the pack from a named source. */
    var dot: Boolean = false
        private set

    /** The figure and its unit in the dock's blue: energy coming back. */
    var blue: Boolean = false
        private set

    /**
     * The figure dimmed, because it has no direction to be drawn in: the pack inside its neutral
     * zone (`ContourMotion.NEUTRAL_KW`). The board has no such scene; the contract draws it `MUTED`.
     */
    var dim: Boolean = false
        private set

    /**
     * A caption with no reading under it and none coming - the location hint, standing in a
     * reading's place. Drawn fainter than a caption, so it does not read as one whose figure is late.
     */
    var hint: Boolean = false
        private set

    fun set(
        caption: String,
        figure: String?,
        unit: String? = null,
        rate: String? = null,
        rateUp: Boolean = true,
        dot: Boolean = false,
        blue: Boolean = false,
        dim: Boolean = false,
    ) {
        present = true
        hint = false
        this.caption = caption
        this.figure = figure
        this.unit = if (figure == null) null else unit
        this.rate = if (figure == null) null else rate
        this.rateUp = rateUp
        this.dot = dot
        this.blue = blue
        this.dim = dim
    }

    fun setHint(text: String) {
        set(text, null)
        hint = true
    }

    fun clear() {
        present = false
        hint = false
        caption = ""
        figure = null
        unit = null
        rate = null
        dot = false
        blue = false
        dim = false
    }
}

/** How far a temperature has left its band, which is the only colour on the car page's row. */
enum class StripHeat { NORMAL, WARNING, DANGER }

/** One of the five temperatures: its figure with its degree, or null, and how hot it is. */
class StripTemperature {
    var figure: String? = null
    var heat: StripHeat = StripHeat.NORMAL

    fun set(figure: String?, heat: StripHeat) {
        this.figure = figure
        this.heat = heat
    }
}

/**
 * Everything the strip prints, on both of its pages, and nothing about where.
 *
 * The strip used to be drawn straight off its sources - a [TripEngine], a [SpectrumSource], a
 * `VehicleTelemetry` - which meant the only way to see a scene was to be in it. The Luminofor board
 * draws from frozen scenes (`tools/design-canvas/luminofor/fixtures.js`), and the app is held to the
 * PNGs it renders: so the app draws from a scene too. [StripReadings] fills this from the live
 * sources every frame; the debug build's `StripFixtures` fills it from the board's own fixtures; and
 * [TripPanelRenderer.drawModel] cannot tell the two apart, which is what makes a screenshot of the
 * app and a board comparable at all.
 *
 * Held and refreshed, never rebuilt. The arrays are references: the live path hands over the
 * analyser's own bars and the snapshot's own points, and copying them would be work per frame for
 * nothing.
 */
class StripModel {

    // ---------------------------------------------------------------------------- the sound page

    /** The track's title, or null when nothing is playing: then there is no track block at all. */
    var title: String? = null

    /** On the caption line after the play mark; may be empty for a track with no artist. */
    var artist: String = ""

    /** Whether it is playing now. A paused track is drawn fainter; see [TripPanelRenderer]. */
    var playing: Boolean = true

    /** The trip's readings in order - time and road, altitude, the sun - of which [tripCount] are up. */
    val trip: Array<StripReading> = Array(TRIP_READINGS) { StripReading() }

    var tripCount: Int = 0

    /** The analyser's heights, 0..1, one per band; [BANDS] of them. */
    var levels: FloatArray = FloatArray(BANDS)

    /** Where each band's crown hangs, 0..1: the dynamics' peak-hold. */
    var crowns: FloatArray = FloatArray(BANDS)

    // ------------------------------------------------------------------------------ the car page

    /** The shell is closed to us, and [message] says what to do about it. Nothing else is drawn. */
    var closed: Boolean = false

    var message: String = ""

    val power = StripReading()
    val volts = StripReading()
    val engine = StripReading()
    val tripCell = StripReading()

    /** Pack, front motor, rear-left, rear-right, inverter: the order the glyphs are drawn in. */
    val temps: Array<StripTemperature> = Array(TEMPERATURES) { StripTemperature() }

    /** The chart's points, oldest first, at most a hundred; [chartCount] of them are real. */
    var chart: FloatArray = FloatArray(0)

    var chartCount: Int = 0

    /**
     * The line over the chart, in three runs: «Расход», the figure, and the window it is over -
     * «Расход 16,9 кВт·ч/100 км · за 10 км». Three because the figure is the one signed number on
     * either screen and a negative one is drawn blue (contract §2.2); null [spendFigure], no line.
     */
    var spendWord: String = ""
    var spendFigure: String? = null
    var spendWindow: String = ""
    var spendNegative: Boolean = false

    companion object {
        /** The analyser's columns at full width; the panes sample 24 and 12 of them. */
        const val BANDS: Int = SpectrumSource.BAND_COUNT
        const val TRIP_READINGS = 3
        const val TEMPERATURES = 5
    }
}
