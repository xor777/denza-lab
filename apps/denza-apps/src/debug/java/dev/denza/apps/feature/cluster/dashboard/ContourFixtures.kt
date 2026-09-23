package dev.denza.apps.feature.cluster.dashboard

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Luminofor board's fixture as a [ContourFrame], for the debug build's fixture mode.
 *
 * `tools/design-canvas/luminofor/shot.py --fixtures` exports `fixtures.js` to the debug assets as
 * `luminofor/fixtures.json`: board id -> `[board, fixture]`. This reads the second element of a
 * `cluster-*` pair - exactly the object `drawCluster(c, f)` is given - into a frame, and
 * `ClusterDashboardRenderer.drawFrame` draws it, so a screenshot of the app can be laid over
 * `_shots/luminofor/<id>.png`:
 *
 *     val boards = JSONObject(assets.open("luminofor/fixtures.json").reader().readText())
 *     val frame = ContourFixtures.frame(boards.getJSONArray("cluster-city").getJSONObject(1))
 *     ClusterDashboardRenderer(context).drawFrame(canvas, 2560, 720, frame)
 *
 * **The board's rules, not the app's.** A fixture already says what is printed, so nothing here
 * asks the scene or the followers: the band is drawn at the fixture's power, the hero is blue at
 * −3 kW and past as the board has it, every caption is drawn, and the consumption is ink unless it
 * carries a minus. The app's own rules - known against fresh, the neutral zone's hysteresis, a
 * grey consumption while the engine runs - are [ContourFrameBuilder]'s and are not exercised here.
 */
internal object ContourFixtures {

    /** The board's own line between blue and ink: `into = P <= -3` in `centre()`. */
    private const val INTO_KW = -3.0

    fun frame(fixture: JSONObject): ContourFrame = fill(ContourFrame(), fixture)

    fun fill(frame: ContourFrame, f: JSONObject): ContourFrame {
        frame.clear()
        frame.t = f.optDouble("t", 0.0).toFloat()
        if (f.optBoolean("unavailable", false)) {
            frame.unavailable = true
            frame.message = f.text("message").orEmpty()
            return frame
        }

        // the centre
        val power = f.optDouble("power", 0.0)
        frame.powerFresh = f.optBoolean("powerFresh", true)
        frame.powerKw = power.toFloat()
        frame.glowKw = power.toFloat()
        frame.into = power <= INTO_KW
        frame.heroUnit = f.optBoolean("heroUnit", true)
        if (f.optBoolean("powerKnown", true)) frame.heroFigure = abs(power).roundToInt().toString()
        frame.peakKw = f.optDouble("peak", Double.NaN).toFloat()
        frame.peakAge = f.optDouble("peakAge", 0.0).toFloat()

        // the left group
        frame.batteryCaption = f.text("batteryCaption")
        frame.volts = f.text("volts")
        f.optJSONArray("temps")?.let { temps ->
            for (index in 0 until minOf(temps.length(), ContourFrame.CELLS)) {
                val cell = temps.getJSONObject(index)
                frame.temps[index].shown = true
                frame.temps[index].value = cell.text("value")
                frame.temps[index].level = level(cell.text("state"))
            }
        }
        f.optJSONObject("spread")?.let { spread ->
            frame.spreadCaption = spread.text("caption")
            frame.spreadValue = spread.text("value")
            frame.spreadUnit = spread.text("unit")
            frame.spreadLevel = level(spread.text("state"))
        }

        // the right group
        frame.iceCaption = f.text("iceCaption")
        frame.iceFigure = f.text("iceFigure")
        if (f.optBoolean("engineGiving", false)) {
            frame.engineGiving = true
            frame.generationCount = floats(f.optJSONArray("generation"), frame.generation)
            frame.engineCaption = f.text("engineCaption").orEmpty()
            frame.engineWindow = f.text("engineWindow").orEmpty()
        }
        frame.tripCaption = f.text("tripCaption")
        frame.tripKwh = f.text("tripKwh")
        f.text("tripUnit")?.let { frame.tripUnit = it }
        // What the fixture carries is what is printed: «ДАЛ ДВС» on the move too, as the app has it.
        frame.gaveCaption = f.text("gaveCaption")
        frame.gaveKwh = f.text("gaveKwh")
        frame.regenCaption = f.text("regenCaption")
        frame.regenKwh = f.text("regenKwh")

        // the ten kilometres
        frame.chartCount = floats(f.optJSONArray("chart"), frame.chart)
        frame.consumption = f.text("consumption")
        frame.consumptionHeld = f.text("consumptionHeld")
        frame.consumptionUnit = f.text("consumptionUnit")
        frame.consumptionTone =
            if (frame.consumption?.startsWith('-') == true) ContourFrame.Tone.BLUE else ContourFrame.Tone.INK
        return frame
    }

    /** The board's three states: `danger` is red, `warning` orange, anything else ink. */
    private fun level(state: String?): ContourReadout.Level = when (state) {
        "danger" -> ContourReadout.Level.ALERT
        "warning" -> ContourReadout.Level.WATCH
        else -> ContourReadout.Level.NORMAL
    }

    /** The newest values of [array] into [into], right-aligned the way the app keeps them. */
    private fun floats(array: JSONArray?, into: FloatArray): Int {
        if (array == null) return 0
        val count = minOf(array.length(), into.size)
        val first = array.length() - count
        for (index in 0 until count) into[index] = array.getDouble(first + index).toFloat()
        return count
    }

    private fun JSONObject.text(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
