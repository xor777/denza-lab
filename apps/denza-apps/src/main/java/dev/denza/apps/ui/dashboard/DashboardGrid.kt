package dev.denza.apps.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.denza.apps.DenzaUiState
import dev.denza.apps.design.DenzaIcons
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
 * names into vectors and hand the two gestures somewhere - which is why it is the only file in the
 * dashboard that has to know what a vector is.
 *
 * The grid wraps at whatever [DashboardLayoutPolicy] gives this width and pads a short last row, so the row of six the boards draw becomes
 * six and three as tiles are added without any of them changing size. Two rows is what the screen
 * affords: two rows of tiles and the analyser under them is exactly 800 dp.
 *
 * [chips] is the same registry drawn as [DenzaChip] instead - the pane's compression of the tile,
 * with the caption dropped and the state carried by a dot. It is a different component rather than
 * a size, and the reasoning is in `DenzaChip`.
 */
@Composable
internal fun DashboardGrid(
    state: DenzaUiState,
    actions: DashboardActions,
    layout: DashboardLayoutMode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tiles = DashboardTiles.of(state)
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
                icon = tileIcon(tile.icon),
                tone = tile.tone,
                onClick = press,
                onLongClick = hold,
                modifier = cell,
                enabled = enabled,
            )
        } else {
            DenzaTile(
                icon = tileIcon(tile.icon),
                name = tile.name,
                state = tile.state,
                tone = tile.tone,
                caption = tile.caption,
                onClick = press,
                onLongClick = hold,
                modifier = cell,
                enabled = enabled,
            )
        }
    }
}

/** The registry's closed vocabulary of icons, drawn as the boards draw them. */
internal fun tileIcon(icon: TileIcon): ImageVector = when (icon) {
    TileIcon.CLUSTER -> DenzaIcons.Cluster
    TileIcon.SIMULCAST -> DenzaIcons.Simulcast
    TileIcon.MIRRORS -> DenzaIcons.Mirrors
    TileIcon.SPLIT -> DenzaIcons.Split
    TileIcon.HUD -> DenzaIcons.Hud
    TileIcon.WEATHER -> DenzaIcons.Weather
    TileIcon.SPEAKER -> DenzaIcons.Speaker
    TileIcon.LOCALE -> DenzaIcons.Locale
    TileIcon.PASSENGER -> DenzaIcons.Passenger
    TileIcon.DEFAULT_APPS -> DenzaIcons.Applications
    TileIcon.SERVICE -> DenzaIcons.Service
}
