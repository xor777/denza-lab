package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import kotlin.math.floor

/**
 * The grid of applications, wherever the driver is asked to point at one.
 *
 * It used to live inside a picker sheet, which meant only the three pickers that are a whole sheet
 * could have it. The default-app roles need a grid under a row of segments rather than under a
 * header, so they had built their own out of the dashboard's tile grid - and on the same 480 dp
 * panel the two drew the same [DenzaAppTile] five to a row and four to a row, with different gaps,
 * and on a narrow pane one fitted its columns to the width while the other insisted on three.
 *
 * How many fit in a row is [DenzaMetrics.Component.PICKER_COLUMNS] for every chooser - four in the
 * full panel. On a narrow pane nobody decides - what fits, fits: three in the one-third pane.
 *
 * A name is read whole. It wraps to two centred lines ([Sheet.Apps.NAME_LINES]) and only past them
 * is it cut; a row grows by a line's step when one of its names needs the second line, and only
 * that row, so «Яндекс Навигатор» is not «Яндекс Нав…» and a row of short names keeps the plain
 * tile. Each tile learns its row's height from [LocalAppTileLines]: a lazy grid measures its cells
 * one by one and never stretches one to its neighbour, so the grid measures the names itself first,
 * at the width the tiles give them.
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
 * thing that scrolls; its rows are counted run by run, as they are laid out.
 */
@Composable
fun <T> DenzaAppGrid(
    items: List<T>,
    key: (T) -> Any,
    label: (T) -> String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    bounded: Boolean = true,
    section: ((T) -> String)? = null,
    item: @Composable (T) -> Unit,
) {
    val runs = remember(items, section) { section?.let { runsOf(items, it) } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val a = Sheet.Apps
        val columns = appColumns(maxWidth.value, compact)
        val cell = (maxWidth.value - (columns - 1) * a.GAP) / columns
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val lines: Map<Any, Int> = remember(items, runs, columns, cell, density) {
            val room = with(density) { (cell - 2 * NAME_INSET).dp.roundToPx() }.coerceAtLeast(1)
            val style = appNameStyle(1f)
            fun linesOf(entry: T) = measurer.measure(
                label(entry), style, maxLines = a.NAME_LINES, constraints = Constraints(maxWidth = room),
            ).lineCount
            val groups = runs?.map { it.second } ?: listOf(items)
            buildMap {
                groups.forEach { run ->
                    val rows = rowLines(run.map(::linesOf), columns)
                    run.forEachIndexed { index, entry -> put(key(entry), rows[index]) }
                }
            }
        }
        val tile: @Composable (T) -> Unit = { entry ->
            CompositionLocalProvider(LocalAppTileLines provides (lines[key(entry)] ?: 1)) { item(entry) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (bounded) Modifier.heightIn(max = DenzaMetrics.Component.PICKER_HEIGHT)
                    else Modifier,
                ),
            // The board's gap - `AppChooser.dc.html` and `Simulcast.dc.html` draw this grid at a
            // neighbour's rung; the whole-sheet pickers had been a rung tighter with nothing but code
            // behind the choice.
            horizontalArrangement = Arrangement.spacedBy(a.GAP.dp),
            verticalArrangement = Arrangement.spacedBy(a.GAP.dp),
        ) {
            if (runs == null) {
                items(items, key = key) { entry -> tile(entry) }
            } else {
                runs.forEachIndexed { index, (title, run) ->
                    // A package name cannot hold a colon, so no label can take an application's key.
                    item(key = "section:$index:$title", span = { GridItemSpan(maxLineSpan) }) {
                        DenzaSectionLabel(
                            title,
                            Modifier.padding(top = if (index == 0) 0.dp else DenzaMetrics.Space.L),
                        )
                    }
                    items(run, key = key) { entry -> tile(entry) }
                }
            }
        }
    }
}

/**
 * How many lines of name the tile's row holds: the board's `apps()` says every tile of a row is as
 * tall as its tallest name needs. Provided by [DenzaAppGrid]; a tile outside one has a line.
 */
internal val LocalAppTileLines = staticCompositionLocalOf { 1 }

/**
 * The columns at a content [width]: the panel's four, or on a narrow pane as many tiles as fit with
 * their gaps - the board's `Math.floor((w + gap) / (tile + gap))`, and [GridCells.Adaptive]'s own.
 */
internal fun appColumns(width: Float, compact: Boolean): Int {
    val a = Sheet.Apps
    return if (compact) floor((width + a.GAP) / (a.TILE + a.GAP)).toInt().coerceAtLeast(1)
    else DenzaMetrics.Component.PICKER_COLUMNS
}

/**
 * Each tile's row height in lines, from each name's own: a row takes its tallest name's count, at
 * least one, at most [Sheet.Apps.NAME_LINES]. [counts] is one run in order, [columns] to a row.
 */
internal fun rowLines(counts: List<Int>, columns: Int): List<Int> =
    counts.chunked(columns.coerceAtLeast(1)).flatMap { row ->
        val lines = row.max().coerceIn(1, Sheet.Apps.NAME_LINES)
        List(row.size) { lines }
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
