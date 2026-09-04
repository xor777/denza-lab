package dev.denza.apps.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics

/** One application on a row's value line: enough to draw it, and the key it is cached under. */
data class DenzaChoiceIcon(val key: Any, val label: String, val drawable: Drawable?)

/**
 * What is chosen, on one row, with the way to change it.
 *
 * This is what a panel shows instead of the choice itself. A grid of every application the car has
 * belongs on a page of its own - see [DenzaAppChooser] - and what belongs in a panel beside a
 * switch and a paragraph is the answer: the three or four icons that are chosen, or a short line
 * saying nothing is.
 *
 * The value line is icons first and words second, deliberately. "Яндекс Навигатор" as text is
 * fifteen characters of a row that also has to hold a title and a chevron, and four applications
 * written out is a line nobody reads; four icons are recognised without being read, which is the
 * one thing an application's icon is genuinely good for.
 *
 * The row draws no surface of its own. Three of them under one switch are one group and one
 * silhouette, and that is [DenzaChoiceGroup]'s job; a row that carried its own background could
 * only ever be three separate cards pretending to be a list.
 */
@Composable
fun DenzaChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icons: List<DenzaChoiceIcon> = emptyList(),
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DenzaMetrics.Component.ROW_HEIGHT)
            // The whole row answers, padding included: a target that stops at the text is a target
            // that misses in a moving car.
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DenzaMetrics.Space.L, vertical = DenzaMetrics.Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        // The board's 4 between the title and the value line. [DenzaSwitchRow] stacks its two lines
        // with nothing between them and gets away with it because both are text and each carries
        // its own leading; here the second line is a row of 24 dp icons, which have none, and on
        // the car they sat against the title's descenders - the owner's word was "слиплись".
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.XS),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) DenzaColors.Ink else DenzaColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (icons.isNotEmpty() || value.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icons.forEach { icon -> ChoiceIcon(icon) }
                    if (value.isNotBlank()) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DenzaColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Icon(
            imageVector = DenzaIcons.Forward,
            contentDescription = null,
            tint = DenzaColors.Muted,
            modifier = Modifier.size(CLOSE_ICON),
        )
    }
}

/**
 * One raised surface with hairlines between its rows.
 *
 * The rows are a list rather than a stack of cards, and a list is one surface. It is the same
 * surface [DenzaSwitchRow] draws itself on, so a switch above a group of rows reads as one column
 * of settings and not as two kinds of control that happen to be adjacent.
 */
@Composable
fun <T> DenzaChoiceGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = DenzaMetrics.Stroke.HAIRLINE,
                        color = DenzaColors.ink(0.12f),
                    )
                }
                row(entry)
            }
        }
    }
}

/** An application on a value line: its own icon, or the initial the board draws in its place. */
@Composable
private fun ChoiceIcon(icon: DenzaChoiceIcon) {
    // Keyed by the package alone, for the reason [DenzaAppTile] gives: a Drawable is a fresh
    // instance on every read of the package manager, and keying on it re-rasterised every icon
    // whenever the state behind the row was republished.
    val bitmap = remember(icon.key) {
        icon.drawable?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = icon.label,
            modifier = Modifier.size(DenzaMetrics.Component.CHOICE_ICON),
            contentScale = ContentScale.Fit,
        )
    } else {
        // A step *down* from the row, not up: the row already sits on the raised surface, so the
        // tile's own raised well would vanish into it and leave a bare letter floating on the line.
        Box(
            modifier = Modifier
                .size(DenzaMetrics.Component.CHOICE_ICON)
                .background(DenzaColors.Surface, RoundedCornerShape(DenzaMetrics.Radius.S)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = icon.label.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = DenzaColors.InkSecondary,
            )
        }
    }
}

private const val ICON_PX = 128
private val CLOSE_ICON = DenzaMetrics.Component.SHEET_CLOSE_ICON
