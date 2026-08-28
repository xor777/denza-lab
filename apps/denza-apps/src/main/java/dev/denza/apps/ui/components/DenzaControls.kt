package dev.denza.apps.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
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

/**
 * A choice of three or four, all visible at once, in one bordered strip.
 *
 * The board draws it as a single rounded rectangle cut into cells by hairlines, with the chosen
 * cell filled solid in the accent and its text in the ink that sits on the accent. What this
 * replaces was Material's own segmented row - pills floating inside a container, each with its own
 * gap - which at this size read as four separate buttons that happened to be adjacent, and spent
 * three different greys saying which one was chosen.
 *
 * Selection is fill, never a thicker edge: an edge that grows on selection shifts its neighbours by
 * a pixel and the eye reads the shift rather than the choice.
 */
@Composable
fun DenzaSegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(DenzaMetrics.Radius.M)
    Row(
        modifier = modifier
            .height(DenzaMetrics.Component.SEGMENT_HEIGHT)
            .clip(shape)
            .border(BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.ink(0.18f)), shape),
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(DenzaMetrics.Stroke.HAIRLINE)
                        .background(DenzaColors.ink(0.18f)),
                )
            }
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) DenzaColors.Accent else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        selected -> DenzaColors.OnAccent
                        enabled -> DenzaColors.MutedDeep
                        else -> DenzaColors.ink(0.25f)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A sentence explaining what a choice above it will actually do.
 *
 * The only prose this screen allows itself. It is not an apology for a failure - the app never
 * writes one of those - it is the part of a setting that cannot be inferred from its name, which on
 * a car is usually the part that matters.
 */
@Composable
fun DenzaNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        Icon(
            imageVector = DenzaIcons.Note,
            contentDescription = null,
            tint = DenzaColors.MutedDeep,
            modifier = Modifier.size(NOTE_ICON).padding(top = DenzaMetrics.Space.XS / 2),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = DenzaColors.MutedDeep,
        )
    }
}

private val NOTE_ICON = 18.dp


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
    maxLines: Int = Int.MAX_VALUE,
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
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private val SEGMENT_INSET = 3.dp
