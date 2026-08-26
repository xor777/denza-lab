package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaMetrics

/**
 * Rows of equal tiles, filling the width whatever the last row holds.
 *
 * The grid keeps every tile the same width, which is the point: a half-empty last row that stretches
 * its survivors into wide slabs makes the dashboard look like it is describing importance when it is
 * only describing arithmetic. So the short row is padded with empty weight instead, and the tiles in
 * it stay the size of the tiles above them.
 *
 * Column counts come from [DenzaMetrics.Component] and the pane the app is running in; the DiLink
 * split gives this app 416, 828 or 1280 dp and nothing between.
 */
@Composable
fun DenzaTileGrid(
    columns: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = DenzaMetrics.Space.M,
    item: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val perRow = columns.coerceAtLeast(1)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        var index = 0
        while (index < itemCount) {
            val last = minOf(index + perRow, itemCount)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (cell in index until last) {
                    item(cell, Modifier.weight(1f))
                }
                // The empties that keep the survivors honest.
                repeat(perRow - (last - index)) {
                    Spacer(Modifier.weight(1f))
                }
            }
            index = last
        }
    }
}
