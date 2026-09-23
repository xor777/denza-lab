package dev.denza.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.DenzaUiState
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.ui.components.LocalStillFrame
import dev.denza.apps.ui.dashboard.DashboardTile
import dev.denza.apps.ui.dashboard.DashboardTiles
import org.json.JSONObject

/**
 * The Luminofor boards' tiles, as the dashboard's own tiles. Debug builds only.
 *
 * `fixtures.json` (exported from `tools/design-canvas/luminofor/fixtures.js` by `shot.py
 * --fixtures`) maps a board id to `[board, fixture]`. A head-unit fixture carries eleven `tiles`,
 * each a `name`, a `status`, whether it is `on`, and the board's own icon drawing. This turns them
 * into [DashboardTile]s so the real dashboard can draw the board's scene: the words are the
 * fixture's, character for character, and the rest comes from the registry.
 *
 * The icon ops are not read. The registry's tile at the same position already names the glyph -
 * the board's eleven are the registry's eleven, in [DashboardTiles.of]'s order - and the app draws
 * its own [dev.denza.apps.design.DenzaIcons], which is the thing a screenshot laid over the board is
 * checking. A tile's `tone` - live, idle, working, attention, broken - is its face; a scene that
 * names none has `on` for live and off for idle.
 */
object TileFixtures {

    fun tiles(fixture: JSONObject): List<DashboardTile> {
        val drawn = fixture.getJSONArray("tiles")
        val registry = DashboardTiles.of(DenzaUiState())
        require(drawn.length() == registry.size) {
            "the fixture draws ${drawn.length()} tiles and the dashboard has ${registry.size}"
        }
        return registry.mapIndexed { i, tile ->
            val board = drawn.getJSONObject(i)
            tile.copy(
                name = board.getString("name"),
                state = board.getString("status"),
                tone = tone(board),
            )
        }
    }

    /** `tone` when the scene names one - `working`, `attention`, `broken` - or else `on`. */
    private fun tone(board: JSONObject): DenzaTileTone = when (board.optString("tone")) {
        "working" -> DenzaTileTone.WORKING
        "attention" -> DenzaTileTone.ATTENTION
        "broken" -> DenzaTileTone.BROKEN
        "idle" -> DenzaTileTone.IDLE
        "live" -> DenzaTileTone.LIVE
        else -> if (board.getBoolean("on")) DenzaTileTone.LIVE else DenzaTileTone.IDLE
    }

    /** The window a board is drawn in: `full`, `two` or `one`, from `board.mode`. */
    internal fun layout(board: JSONObject): DashboardLayoutMode = when (val mode = board.getString("mode")) {
        "full" -> DashboardLayoutMode.WIDE
        "two" -> DashboardLayoutMode.MEDIUM
        "one" -> DashboardLayoutMode.NARROW
        else -> error("no head-unit window called $mode")
    }
}

/**
 * The dashboard exactly as the board frames it: a [DashboardBody] in a box the size of the board's
 * window, on the board's black, fed the fixture's tiles.
 *
 * The box is the window, not the screen: 1280, 828 or 416 by 680 dp. A pane's top 24 dp stand for
 * the caption bar BYD's freeform windowing keeps - the board draws its handle there, the app never
 * does - and are padded off the way `safeDrawing` pads them on the car, so what lands below is the
 * same page the car gets. At 2.0 px per dp the result is the board's PNG pixel for pixel in size.
 *
 * [strip] draws the strip into its box, as on the real screen; pass nothing to leave the box black.
 */
@Composable
internal fun DashboardFixtureFrame(
    board: JSONObject,
    fixture: JSONObject,
    strip: @Composable (Modifier) -> Unit = {},
) {
    val layout = TileFixtures.layout(board)
    val (width, bar) = when (layout) {
        DashboardLayoutMode.WIDE -> Head.Full.WIDTH to 0f
        DashboardLayoutMode.MEDIUM -> Head.Two.WIDTH to Head.Two.CAPTION_BAR
        DashboardLayoutMode.NARROW -> Head.One.WIDTH to Head.One.CAPTION_BAR
    }
    // A board is a still: the working ring holds at twelve o'clock, where the board draws it.
    CompositionLocalProvider(LocalStillFrame provides true) {
        Box(Modifier.size(width.dp, Head.Full.HEIGHT.dp).background(DenzaColors.Ground)) {
            DashboardBody(
                tiles = TileFixtures.tiles(fixture),
                layout = layout,
                enabled = true,
                onPress = {},
                onHold = {},
                strip = strip,
                modifier = Modifier.fillMaxSize().padding(top = bar.dp),
            )
        }
    }
}
