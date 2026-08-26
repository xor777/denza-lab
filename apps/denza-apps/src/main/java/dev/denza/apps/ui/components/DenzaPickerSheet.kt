package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * A grid of things to choose from, on the one sheet every sheet uses.
 *
 * The three pickers this replaces were the same dialog written three times: 0.92, 0.68 and 0.92 of
 * the screen, 360, 260 and 380 dp of grid, six columns, three columns and six again, and three
 * tiles that differed by two pixels of height and a point of type. None of those differences was
 * chosen; they were simply three days' work that never got compared side by side.
 *
 * What genuinely differs between pickers is the words and how many fit in a row, so those are the
 * parameters and nothing else is.
 */
@Composable
fun <T> DenzaPickerSheet(
    title: String,
    subtitle: String,
    items: List<T>,
    key: (T) -> Any,
    compact: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    note: String = "",
    emptyText: String = "Ничего не найдено",
    columns: Int = DenzaMetrics.Component.PICKER_COLUMNS,
    item: @Composable (T) -> Unit,
) {
    DenzaSheet(onDismiss = onDismiss, compact = compact, modifier = modifier) {
        DenzaSheetHeader(title = title, subtitle = subtitle, onDismiss = onDismiss)
        if (note.isNotBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = DenzaColors.Warning,
            )
        }
        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = DenzaColors.Muted,
            )
        } else {
            LazyVerticalGrid(
                // On a narrow pane the column count is whatever fits, because three fixed columns
                // in a 416 dp pane is three unreadable ones.
                columns = if (compact) {
                    GridCells.Adaptive(DenzaMetrics.Component.APP_TILE)
                } else {
                    GridCells.Fixed(columns)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DenzaMetrics.Component.PICKER_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S),
                verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S),
            ) {
                items(items, key = key) { entry -> item(entry) }
            }
        }
    }
}
