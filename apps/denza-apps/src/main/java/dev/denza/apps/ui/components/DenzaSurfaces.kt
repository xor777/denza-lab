package dev.denza.apps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * The surface a feature's settings arrive on, and the header that names them.
 *
 * There were six dialog surfaces in the screen this replaces, at six different widths - 0.56, 0.68,
 * 0.72 and 0.92 of the screen, twice each - and three ways of drawing their headers, one of which
 * was shared and two of which were copies made because the shared one did not quite fit. A width
 * chosen per dialog is not a design; it is six people's guesses stacked up, and it shows as the
 * dialogs move about under the finger as you go between features.
 *
 * One surface, one header, one width for the wide pane and one for the narrow.
 */
@Composable
fun DenzaSheet(
    onDismiss: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    dismissOnOutsideTouch: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnOutsideTouch,
        ),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(if (compact) COMPACT_WIDTH else WIDE_WIDTH)
                .padding(DenzaMetrics.Space.M),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(DenzaMetrics.Space.XL),
                verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.L),
            ) {
                content()
            }
        }
    }
}

/**
 * A sheet's name, what it is for, and the one way out of it.
 *
 * The action sits on the same line as the title on a wide pane and under it on a narrow one, because
 * that is the only difference the two panes actually justify.
 */
@Composable
fun DenzaSheetHeader(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val copy: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = DenzaColors.Ink)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = DenzaColors.Muted)
        }
    }
    val action: @Composable () -> Unit = {
        TextButton(onClick = onAction) {
            Text(actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }

    if (compact) {
        Column(modifier.fillMaxWidth()) {
            copy()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column { copy() }
            Spacer(Modifier.weight(1f))
            action()
        }
    }
}

/** A group of settings inside a sheet, with the heading that says what they have in common. */
@Composable
fun DenzaSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = DenzaColors.Muted)
        content()
    }
}

private const val WIDE_WIDTH = 0.68f
private const val COMPACT_WIDTH = 0.92f
