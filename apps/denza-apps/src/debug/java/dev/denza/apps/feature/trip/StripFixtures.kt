package dev.denza.apps.feature.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.denza.apps.design.luminofor.LuminoforSpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The Luminofor board's scenes as strip models, so the app can draw exactly what a board PNG shows.
 *
 * `tools/design-canvas/luminofor/fixtures.js` is the data every board is drawn with, and
 * `shot.py --fixtures` exports it to this build's `assets/luminofor/fixtures.json` as
 * `board id -> [board, fixture]`: `main-sound`, `main-car`, `main-car-engine`, `main-car-hot`,
 * `two-sound`, `two-car`, `one-sound`, `one-car` for the strip. This turns one of those fixtures into
 * the [StripModel] the renderer draws, field for field - the strings are the board's own and are not
 * reformatted - and draws one onto a bitmap the size of the board's PNG, the strip at its box.
 *
 * Debug only: nothing in the product build reads a fixture.
 *
 * Entry points:
 *  - [model] - a fixture object (the second element of a board's entry) as a model;
 *  - [load], [layout], [page] - the whole file, and what a board id says about width and page;
 *  - [render] - a board id drawn onto a black bitmap of the board PNG's own size, for laying one over
 *    the other; the tiles, the chips and the handle are not the strip's and are not drawn;
 *  - [TripPanelView.fixture] with [TripPanelView.page] - the live view held on a model.
 */
object StripFixtures {

    private const val ASSET = "luminofor/fixtures.json"

    /** `fixtures.json`, as the board exported it. */
    fun load(context: Context): JSONObject =
        JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    /** The board's descriptor for [id]: `{ kind, mode, page }`. */
    fun board(all: JSONObject, id: String): JSONObject = all.getJSONArray(id).getJSONObject(0)

    /** The fixture [id] is drawn with. */
    fun fixture(all: JSONObject, id: String): JSONObject = all.getJSONArray(id).getJSONObject(1)

    /** `full`, `two` or `one`, as the strip's own three compositions. */
    fun layout(board: JSONObject): TripPanelLayout = when (board.getString("mode")) {
        "full" -> TripPanelLayout.WIDE
        "two" -> TripPanelLayout.MEDIUM
        else -> TripPanelLayout.NARROW
    }

    fun page(board: JSONObject): StripPage =
        if (board.optString("page") == "car") StripPage.VEHICLE else StripPage.SOUND

    /** One of the board's scenes as the model the strip draws. */
    fun model(fixture: JSONObject): StripModel {
        val model = StripModel()

        fixture.optJSONObject("track")?.let { track ->
            model.title = track.getString("title")
            model.artist = track.optString("artist")
            model.playing = true
        }
        fixture.optJSONArray("trip")?.let { trip ->
            val count = minOf(trip.length(), StripModel.TRIP_READINGS)
            for (k in 0 until count) reading(model.trip[k], trip.getJSONObject(k))
            model.tripCount = count
        }
        fixture.optJSONObject("spectrum")?.let { spectrum ->
            model.levels = floats(spectrum.getJSONArray("levels"))
            model.crowns = floats(spectrum.getJSONArray("crowns"))
        }

        fixture.optJSONObject("power")?.let { reading(model.power, it) }
        fixture.optJSONObject("volts")?.let { reading(model.volts, it) }
        fixture.optJSONObject("engine")?.let { reading(model.engine, it) }
        fixture.optJSONObject("tripCell")?.let { reading(model.tripCell, it) }
        fixture.optJSONArray("temps")?.let { temps ->
            for (index in 0 until minOf(temps.length(), StripModel.TEMPERATURES)) {
                val cell = temps.getJSONObject(index)
                val heat = when (cell.optString("state")) {
                    "danger" -> StripHeat.DANGER
                    "warning" -> StripHeat.WARNING
                    else -> StripHeat.NORMAL
                }
                model.temps[index].set(cell.getString("value") + "°", heat)
            }
        }
        fixture.optJSONArray("chart")?.let { chart ->
            model.chart = floats(chart)
            model.chartCount = model.chart.size
        }
        // «Расход 16,9 кВт·ч/100 км · за 10 км»: the word, the figure, and the window it is over.
        fixture.optString("consumptionCaption").takeIf { it.isNotEmpty() }?.let { caption ->
            val word = caption.substringBefore(' ')
            val rest = caption.substringAfter(' ')
            model.spendWord = word
            model.spendFigure = rest.substringBefore(' ')
            model.spendWindow = rest.substringAfter(' ')
            model.spendNegative = rest.startsWith('-')
        }
        model.closed = fixture.optBoolean("unavailable", false)
        model.message = fixture.optString("message")
        return model
    }

    /**
     * Board [id] drawn the way `shot.py` renders it: a black bitmap the size of the board's PNG
     * (the head unit's window at [density], 2560 x 1360 at 2.0), the strip drawn at its box.
     *
     * Only the strip: the tiles, the chips and a pane's handle are drawn by the dashboard, not
     * here. The canvas is the whole window, so what the board draws past the strip box - the
     * chart's end dot and its glow, the analyser's outer glow and haze - is drawn too.
     */
    fun render(context: Context, id: String, density: Float = 2f): Bitmap {
        val all = load(context)
        val board = board(all, id)
        val layout = layout(board)
        val (windowW, windowH) = when (layout) {
            TripPanelLayout.WIDE -> LuminoforSpec.Head.Full.WIDTH to LuminoforSpec.Head.Full.HEIGHT
            TripPanelLayout.MEDIUM -> LuminoforSpec.Head.Two.WIDTH to LuminoforSpec.Head.Two.HEIGHT
            TripPanelLayout.NARROW -> LuminoforSpec.Head.One.WIDTH to LuminoforSpec.Head.One.HEIGHT
        }
        val bitmap = Bitmap.createBitmap(
            (windowW * density).roundToInt(),
            (windowH * density).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(LuminoforSpec.BACKGROUND)
        val box = TripPanelRenderer.box(layout)
        canvas.translate(box.left * density, box.top * density)
        TripPanelRenderer().drawModel(
            canvas,
            (box.right - box.left) * density,
            (box.bottom - box.top) * density,
            density,
            layout,
            page(board),
            model(fixture(all, id)),
        )
        return bitmap
    }

    private fun reading(into: StripReading, o: JSONObject) {
        into.set(
            caption = o.getString("cap"),
            figure = if (o.has("fig")) o.getString("fig") else null,
            unit = if (o.has("unit")) o.getString("unit") else null,
            rate = if (o.has("rate")) o.getString("rate") else null,
            rateUp = true,
            dot = o.optBoolean("dot", false),
            blue = o.optString("col") == "blue",
        )
    }

    private fun floats(array: JSONArray): FloatArray = FloatArray(array.length()) { array.getDouble(it).toFloat() }
}
