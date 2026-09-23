package dev.denza.apps.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.ui.DashboardLayoutMode
import dev.denza.apps.ui.DashboardLayoutPolicy
import dev.denza.apps.ui.components.DenzaChip
import dev.denza.apps.ui.components.DenzaTile
import kotlin.math.floor

/**
 * The band of features: one tile or one chip per feature, all the same size, all pressed the same
 * two ways.
 *
 * There is nothing here but the binding and the placement. What each tile says and how it reads is
 * [DashboardTiles], what a press means is [DashboardPress] (the caller's [onPress] and [onHold]
 * carry it), and how a face is drawn is [DenzaTile] and [DenzaChip]. This file turns the registry's
 * icon names into glyphs and puts every face where the board puts it.
 *
 * **Placed, not flowed.** The band used to be rows of `weight(1f)` cells, which lands each tile
 * wherever the row's rounding leaves it. It is laid out by [FeatureBand][dev.denza.apps.ui.FeatureBand]
 * instead - `tileFace`'s own arithmetic, cell `i` at column `i % columns`, row `i / columns` - at
 * the board's own fractional position, so no row accumulates the rounding of the ones before it and
 * a face's glyph and words land where the board draws them to the sub-pixel. A short last
 * row keeps its cells the size of the ones above: the row of six the board draws becomes six and
 * five at eleven features, and six and six at twelve, without any of them changing size.
 *
 * Two rows of tiles is what the full screen affords: two rows and the strip under them fill the
 * 680 dp the app actually gets.
 */
@Composable
internal fun DashboardGrid(
    tiles: List<DashboardTile>,
    layout: DashboardLayoutMode,
    enabled: Boolean,
    onPress: (DashboardTile) -> Unit,
    onHold: (DashboardTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = DashboardLayoutPolicy.chips(layout)
    Layout(
        modifier = modifier,
        content = {
            tiles.forEach { tile ->
                key(tile.id) {
                    if (chips) {
                        DenzaChip(
                            glyph = tileGlyph(tile.icon),
                            tone = tile.tone,
                            radius = DashboardLayoutPolicy.cornerRadius(layout),
                            glyphSize = DashboardLayoutPolicy.glyphSize(layout),
                            onClick = { onPress(tile) },
                            onLongClick = { onHold(tile) },
                            enabled = enabled,
                        )
                    } else {
                        DenzaTile(
                            glyph = tileGlyph(tile.icon),
                            name = tile.name,
                            state = tile.state,
                            tone = tile.tone,
                            onClick = { onPress(tile) },
                            onLongClick = { onHold(tile) },
                            enabled = enabled,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val band = DashboardLayoutPolicy.band(layout, measurables.size, constraints.maxWidth.toDp().value)
        val placeables = measurables.map { measurable ->
            measurable.measure(
                Constraints.fixed(
                    width = band.cellWidth.dp.roundToPx(),
                    height = band.cellHeight.dp.roundToPx(),
                ),
            )
        }
        layout(constraints.maxWidth, band.height.dp.roundToPx()) {
            val cellWidth = band.cellWidth.dp.toPx()
            val cellHeight = band.cellHeight.dp.toPx()
            placeables.forEachIndexed { i, placeable ->
                // A layout position is a whole pixel and the board's is not: the second column
                // of tiles starts at 494.67 px. The face is placed on the pixel under it and moved
                // the rest of the way by its layer, so what hangs from it lands where the board
                // draws it rather than a third of a pixel off. A tile hangs its glyph and its words
                // from its left edge, so that edge is the one made exact; a chip hangs its glyph
                // from its centre, and a whole-pixel chip is centred on the board's fractional one.
                var x = band.left(i).dp.toPx()
                var y = band.top(i).dp.toPx()
                if (chips) {
                    x += (cellWidth - placeable.width) / 2f
                    y += (cellHeight - placeable.height) / 2f
                }
                val px = floor(x)
                val py = floor(y)
                placeable.placeWithLayer(px.toInt(), py.toInt()) {
                    translationX = x - px
                    translationY = y - py
                }
            }
        }
    }
}

/** The registry's closed vocabulary of icons, as the glyphs a face draws. */
internal fun tileGlyph(icon: TileIcon): DenzaGlyph = when (icon) {
    TileIcon.CLUSTER -> DenzaIcons.ClusterGlyph
    TileIcon.SIMULCAST -> DenzaIcons.SimulcastGlyph
    TileIcon.MIRRORS -> DenzaIcons.MirrorsGlyph
    TileIcon.SPLIT -> DenzaIcons.SplitGlyph
    TileIcon.HUD -> DenzaIcons.HudGlyph
    TileIcon.WEATHER -> DenzaIcons.WeatherGlyph
    TileIcon.SPEAKER -> DenzaIcons.SpeakerGlyph
    TileIcon.LOCALE -> DenzaIcons.LocaleGlyph
    TileIcon.PASSENGER -> DenzaIcons.PassengerGlyph
    TileIcon.DEFAULT_APPS -> DenzaIcons.ApplicationsGlyph
    TileIcon.SERVICE -> DenzaIcons.ServiceGlyph
}

/** The same glyph as a tinted vector, for a panel's header. */
internal fun tileIcon(icon: TileIcon): ImageVector = tileGlyph(icon).vector
