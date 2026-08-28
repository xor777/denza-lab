package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaMetrics

/**
 * The grid of applications, wherever the driver is asked to point at one.
 *
 * It used to live inside [DenzaPickerSheet], which meant only the three pickers that are a whole
 * sheet could have it. The default-app roles need a grid under a row of segments rather than under
 * a header, so they had built their own out of [DenzaTileGrid] - and on the same 480 dp panel the
 * two drew the same [DenzaAppTile] five to a row and four to a row, with different gaps, and on a
 * narrow pane one fitted its columns to the width while the other insisted on three.
 *
 * How many fit in a row is the one thing a caller decides, because that is a real difference: the
 * navigators are few and large, the default-app roles are drawn four across on their board, and
 * the projection lists everything the car has. On a narrow pane nobody decides - what fits, fits.
 */
@Composable
fun <T> DenzaAppGrid(
    items: List<T>,
    key: (T) -> Any,
    compact: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = DenzaMetrics.Component.PICKER_COLUMNS,
    item: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        // On a narrow pane the column count is whatever fits, because three fixed columns
        // in a 416 dp pane is three unreadable ones.
        columns = if (compact) {
            GridCells.Adaptive(DenzaMetrics.Component.APP_TILE)
        } else {
            GridCells.Fixed(columns)
        },
        // The sheet behind every caller scrolls, so the grid has to be told how tall it may be
        // before it will measure at all.
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = DenzaMetrics.Component.PICKER_HEIGHT),
        // The board's gap. `DefaultApps.dc.html` is the only board that draws this grid, and it
        // draws it at a neighbour's rung; the whole-sheet pickers had been a rung tighter with
        // nothing but code behind the choice.
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        items(items, key = key) { entry -> item(entry) }
    }
}
