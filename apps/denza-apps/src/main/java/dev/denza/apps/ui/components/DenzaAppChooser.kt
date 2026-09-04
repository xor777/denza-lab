package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * The one way this app asks the driver to point at an application.
 *
 * There were two shapes of that question and they were the same question: three pickers that took
 * a whole sheet, and two panels that hung the same grid under a switch and a heading with
 * [DenzaMetrics.Component.PICKER_HEIGHT] over it. The second shape is the one that failed. A capped
 * grid inside a panel that scrolls is two scrolls in one another - the list moves until it runs
 * out, then drags the panel under it - and on the roles' panel it left about two rows of
 * applications visible under a switch, a row of segments and a status line, inside a window 680 dp
 * tall.
 *
 * So a chooser is a **page**: it takes the sheet, the sheet stops scrolling, and the grid is the
 * one thing that scrolls. A panel that has a choice to offer shows what is chosen on a
 * [DenzaChoiceRow] and opens this when the row is pressed.
 *
 * The carry-over was the other half of the same defect. One grid whose items were swapped kept its
 * [androidx.compose.foundation.lazy.grid.LazyGridState] and the panel's own scroll offset, so
 * changing the role opened the next list four rows in, at whatever the previous one had been left
 * at. A page is composed when it opens and leaves when it closes, so its grid state is new every
 * time and every list opens at its first row - without anything having to remember to reset it.
 */
@Composable
fun <T> ColumnScope.DenzaAppChooser(
    title: String,
    subtitle: String,
    items: List<T>,
    key: (T) -> Any,
    compact: Boolean,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    emptyText: String = "Ничего не найдено",
    columns: Int = DenzaMetrics.Component.PICKER_COLUMNS,
    item: @Composable (T) -> Unit,
) {
    DenzaSheetHeader(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        onBack = onBack,
    )
    if (items.isEmpty()) {
        // A wait, not a verdict, and the caller picks the words: the car is perfectly capable of
        // answering the next read, and this page is asking for one.
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyLarge,
            color = DenzaColors.Muted,
        )
    } else {
        DenzaAppGrid(
            items = items,
            key = key,
            compact = compact,
            modifier = Modifier.weight(1f),
            columns = columns,
            bounded = false,
            item = item,
        )
    }
}

/**
 * The same chooser as a whole sheet, for a choice opened straight from a tile.
 *
 * No way back, because there is nothing behind it: the driver pressed a tile whose feature is
 * waiting on this answer, so the header carries the way out and nothing else.
 */
@Composable
fun <T> DenzaAppChooserSheet(
    title: String,
    subtitle: String,
    items: List<T>,
    key: (T) -> Any,
    compact: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Ничего не найдено",
    columns: Int = DenzaMetrics.Component.PICKER_COLUMNS,
    footer: @Composable () -> Unit = {},
    item: @Composable (T) -> Unit,
) {
    DenzaSheet(
        onDismiss = onDismiss,
        compact = compact,
        modifier = modifier,
        scrolls = false,
        footer = footer,
    ) {
        DenzaAppChooser(
            title = title,
            subtitle = subtitle,
            items = items,
            key = key,
            compact = compact,
            onDismiss = onDismiss,
            emptyText = emptyText,
            columns = columns,
            item = item,
        )
    }
}
