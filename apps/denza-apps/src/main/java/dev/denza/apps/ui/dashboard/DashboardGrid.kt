package dev.denza.apps.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.denza.apps.DenzaUiState
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
 */
@Composable
fun DashboardGrid(
    state: DenzaUiState,
    actions: DashboardActions,
    columns: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tiles = DashboardTiles.of(state)
    DenzaTileGrid(
        columns = columns,
        itemCount = tiles.size,
        modifier = modifier,
    ) { index, cell ->
        val tile = tiles[index]
        DenzaTile(
            icon = iconOf(tile.icon),
            name = tile.name,
            state = tile.state,
            tone = tile.tone,
            onClick = { DashboardPress.perform(tile, state, actions) },
            onLongClick = { actions.onOpenSettings(tile.id) },
            modifier = cell,
            enabled = enabled,
        )
    }
}

/**
 * The registry's closed vocabulary of icons, drawn.
 *
 * Speed rather than a map for the driver's screen: what goes there is now as often our own
 * instruments as somebody's navigation, and an icon that says "map" would be arguing with the
 * caption underneath it half the time.
 */
private fun iconOf(icon: TileIcon): ImageVector = when (icon) {
    TileIcon.CLUSTER -> Icons.Outlined.Speed
    TileIcon.SIMULCAST -> Icons.Outlined.CastConnected
    TileIcon.MIRRORS -> Icons.Outlined.Videocam
    TileIcon.SPLIT -> Icons.Outlined.VerticalSplit
    TileIcon.HUD -> Icons.Outlined.Map
    TileIcon.PASSENGER -> Icons.Outlined.InstallMobile
}
