package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.denza.apps.DenzaUiState
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.ui.DashboardLayoutMode
import dev.denza.apps.ui.DashboardLayoutPolicy
import dev.denza.apps.ui.components.DenzaChip
import dev.denza.apps.ui.components.DenzaTile
import dev.denza.apps.ui.components.DenzaTileGrid

/**
 * The dashboard: one tile per feature, all the same size, all pressed the same two ways.
 *
 * There is nothing here but the binding. What each tile says and how it reads is [DashboardTiles],
 * what a press means is [DashboardPress], and how a tile is drawn is
 * [dev.denza.apps.ui.components.DenzaTile]. This file's whole job is to turn the registry's icon
 * names into glyphs and hand the two gestures somewhere.
 *
 * The grid wraps at whatever [DashboardLayoutPolicy] gives this width and pads a short last row,
 * so the row of six the boards draw becomes six and three as tiles are added without any of them
 * changing size. Two rows is what the screen
 * affords: two rows of tiles and the analyser under them fill the 680 dp the app actually gets -
 * the boards are drawn on 800, and the car keeps 56 of that for its status band and 64 for the
 * dock before the window exists.
 *
 * [chips] is the same registry drawn as [DenzaChip] instead - the pane's compression of the tile,
 * with the words dropped. It is a different component rather than a size, and the reasoning is in
 * `DenzaChip`.
 */
@Composable
internal fun DashboardGrid(
    state: DenzaUiState,
    actions: DashboardActions,
    layout: DashboardLayoutMode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Eleven tiles decided from scratch on every recomposition, and this one recomposes on every
    // state publication the runtime makes.
    val tiles = remember(state) { DashboardTiles.of(state) }
    val chips = DashboardLayoutPolicy.chips(layout)
    DenzaTileGrid(
        columns = DashboardLayoutPolicy.columns(layout, tiles.size),
        itemCount = tiles.size,
        modifier = modifier,
    ) { index, cell ->
        val tile = tiles[index]
        val press = { DashboardPress.perform(tile, state, actions) }
        val hold = { actions.onOpenSettings(tile.id) }
        if (chips) {
            DenzaChip(
                glyph = tileGlyph(tile.icon),
                tone = tile.tone,
                radius = DashboardLayoutPolicy.cornerRadius(layout),
                glyphSize = DashboardLayoutPolicy.glyphSize(layout),
                onClick = press,
                onLongClick = hold,
                modifier = cell.aspectRatio(1f),
                enabled = enabled,
            )
        } else {
            DenzaTile(
                glyph = tileGlyph(tile.icon),
                name = tile.name,
                state = tile.state,
                tone = tile.tone,
                onClick = press,
                onLongClick = hold,
                modifier = cell.height(DenzaMetrics.Component.TILE_HEIGHT),
                enabled = enabled,
            )
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
