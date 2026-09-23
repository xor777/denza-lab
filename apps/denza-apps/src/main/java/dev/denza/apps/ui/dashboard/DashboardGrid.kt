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
 * instead - `tileFace`'s own arithmetic, cell `i` at column `i % columns`, row `i / columns` - and
 * each edge is snapped to the pixel nearest the board's, so no face is ever more than half a pixel
 * from where the board draws it and no row accumulates the error of the ones before it. A short last
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
        // Both edges of a cell are rounded, not its position and its width separately: a width
        // rounded on its own drifts the next cell by the rounding of every cell before it.
        fun edge(dp: Float): Int = dp.dp.roundToPx()
        val placeables = measurables.mapIndexed { i, measurable ->
            val left = band.left(i)
            val top = band.top(i)
            measurable.measure(
                Constraints.fixed(
                    width = edge(left + band.cellWidth) - edge(left),
                    height = edge(top + band.cellHeight) - edge(top),
                ),
            )
        }
        layout(constraints.maxWidth, edge(band.height)) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(edge(band.left(i)), edge(band.top(i)))
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
