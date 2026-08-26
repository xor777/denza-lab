package dev.denza.apps.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * The controls a feature's settings are made of.
 *
 * There is one of each here because there used to be several of each in one file: four ways to draw
 * a row with a switch on it, nine copies of the accent button, two dictionaries translating the same
 * statuses into the same words. Each copy drifted a little - a different corner radius, a caption a
 * size smaller, a disabled state one of them had and the others did not - and none of the drift was
 * a decision anybody made.
 *
 * They read colour and type from the theme rather than being handed either, so a change to
 * [dev.denza.apps.design.DenzaTheme] moves all of them at once. That is the only reason a component
 * layer is worth having.
 */

/** A setting that is on or off, with the reason it is what it is written underneath. */
@Composable
fun DenzaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DenzaMetrics.Component.ROW_HEIGHT)
                .padding(horizontal = DenzaMetrics.Space.L, vertical = DenzaMetrics.Space.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) DenzaColors.Ink else DenzaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DenzaColors.Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/** One of a few mutually exclusive choices, all of them visible at once. */
@Composable
fun DenzaSegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier.height(DenzaMetrics.Component.SEGMENT_HEIGHT),
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.medium,
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxSize().padding(SEGMENT_INSET),
            space = SEGMENT_INSET,
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    selected = selected,
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    shape = RoundedCornerShape(DenzaMetrics.Radius.S),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        activeContentColor = MaterialTheme.colorScheme.primary,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = DenzaColors.Ink,
                    ),
                    // Selection is fill and ink, never a thicker edge: a border that grows on
                    // selection shifts its neighbours, and the eye reads the shift, not the choice.
                    border = BorderStroke(0.dp, Color.Transparent),
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

/** The one action a surface exists to offer. */
@Composable
fun DenzaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = DenzaMetrics.Component.SEGMENT_HEIGHT),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** An action beside the main one, weighted so it cannot be mistaken for it. */
@Composable
fun DenzaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = DenzaMetrics.Component.SEGMENT_HEIGHT),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A named reading, for diagnostics and anywhere else a fact needs its label beside it. */
@Composable
fun DenzaKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    if (stacked) {
        Column(modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = DenzaColors.Muted)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = DenzaColors.Ink)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = DenzaColors.Muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = DenzaColors.Ink,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** What a feature is doing, in its own words, in the colour that state deserves. */
@Composable
fun DenzaStatusLine(
    text: String,
    tone: DenzaTileTone,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when (tone) {
            DenzaTileTone.ATTENTION -> DenzaColors.Warning
            DenzaTileTone.BROKEN -> DenzaColors.Danger
            else -> DenzaColors.Muted
        },
        modifier = modifier,
    )
}

private val SEGMENT_INSET = 3.dp
