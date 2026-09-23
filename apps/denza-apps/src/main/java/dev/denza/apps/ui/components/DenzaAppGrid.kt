package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaMetrics

/**
 * The grid of applications, wherever the driver is asked to point at one.
 *
 * It used to live inside a picker sheet, which meant only the three pickers that are a whole sheet
 * could have it. The default-app roles need a grid under a row of segments rather than under a
 * header, so they had built their own out of the dashboard's tile grid - and on the same 480 dp
 * panel the two drew the same [DenzaAppTile] five to a row and four to a row, with different gaps,
 * and on a narrow pane one fitted its columns to the width while the other insisted on three.
 *
 * How many fit in a row is [DenzaMetrics.Component.PICKER_COLUMNS] for every chooser. The driver's
 * screen had a row of its own, three larger tiles, while it offered a handful of navigators; it
 * lists everything the car has now, like the rest. On a narrow pane nobody decides - what fits,
 * fits.
 *
 * [bounded] is the other: a grid that is one child of a scrolling panel has to be told how tall it
 * may be before it will measure at all, and a grid that *is* the page takes the height it is given
 * and scrolls inside it. Bounding the second would put one scroll inside another - see
 * [DenzaMetrics.Component.PICKER_HEIGHT].
 *
 * [section] names the group an item belongs to, for a grid that holds two kinds of answer - the
 * driver's screen offers this app's instruments and then the applications. Each run of one group
 * opens with its name as a tracked capital across the row, [DenzaSectionLabel] as a panel's
 * sections have it, and a run after the first stands a section's 32 below the last row: the grid's
 * own 12 and the label's 20. One grid rather than a grid per group, so the page still has exactly one
 * thing that scrolls.
 */
@Composable
fun <T> DenzaAppGrid(
    items: List<T>,
    key: (T) -> Any,
    compact: Boolean,
    modifier: Modifier = Modifier,
    bounded: Boolean = true,
    section: ((T) -> String)? = null,
    item: @Composable (T) -> Unit,
) {
    val runs = remember(items, section) { section?.let { runsOf(items, it) } }
    LazyVerticalGrid(
        // On a narrow pane the column count is whatever fits, because three fixed columns
        // in a 416 dp pane is three unreadable ones.
        columns = if (compact) {
            GridCells.Adaptive(DenzaMetrics.Component.APP_TILE)
        } else {
            GridCells.Fixed(DenzaMetrics.Component.PICKER_COLUMNS)
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (bounded) Modifier.heightIn(max = DenzaMetrics.Component.PICKER_HEIGHT)
                else Modifier,
            ),
        // The board's gap - `AppChooser.dc.html` and `Simulcast.dc.html` draw this grid at a
        // neighbour's rung; the whole-sheet pickers had been a rung tighter with nothing but code
        // behind the choice.
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        if (runs == null) {
            items(items, key = key) { entry -> item(entry) }
        } else {
            runs.forEachIndexed { index, (title, run) ->
                // A package name cannot hold a colon, so no label can take an application's key.
                item(key = "section:$index:$title", span = { GridItemSpan(maxLineSpan) }) {
                    DenzaSectionLabel(
                        title,
                        Modifier.padding(top = if (index == 0) 0.dp else DenzaMetrics.Space.L),
                    )
                }
                items(run, key = key) { entry -> item(entry) }
            }
        }
    }
}

/** Consecutive items of one group, in order, each run under the name [section] gives it. */
internal fun <T> runsOf(items: List<T>, section: (T) -> String): List<Pair<String, List<T>>> {
    val runs = mutableListOf<Pair<String, MutableList<T>>>()
    items.forEach { entry ->
        val title = section(entry)
        if (runs.lastOrNull()?.first != title) runs += title to mutableListOf()
        runs.last().second += entry
    }
    return runs
}
