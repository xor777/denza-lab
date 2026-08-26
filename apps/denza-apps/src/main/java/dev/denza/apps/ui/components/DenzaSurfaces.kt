package dev.denza.apps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
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
 * This is the board's panel: 480 dp hung off the right edge for the full height, over a scrim, with
 * a lit left border and a shadow thrown back across the dashboard. The first attempt at unifying
 * them settled on one centred dialog, which is tidier than six but still covers the tile it belongs
 * to. A panel at the edge leaves the dashboard beside it, so the thing being configured stays in
 * sight while it is configured - and on a screen 1280 dp wide there is room for both.
 */
@Composable
fun DenzaSheet(
    onDismiss: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    dismissOnOutsideTouch: Boolean = true,
    footer: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnOutsideTouch,
        ),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // The scrim is its own surface rather than the dialog's own dimming, so the panel can
            // sit hard against the edge with nothing between it and the glass.
            val taps = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SCRIM)
                    .clickable(
                        interactionSource = taps,
                        indication = null,
                        enabled = dismissOnOutsideTouch,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .then(
                        if (compact) Modifier.fillMaxWidth()
                        else Modifier.width(DenzaMetrics.Component.SHEET_WIDTH),
                    )
                    .background(DenzaColors.SurfaceQuiet)
                    // A rung lower down the sides than across them: the window is 680 dp tall and
                    // a panel with a header, its settings, an action and a footnote does not fit
                    // 32 top and bottom. Measured on the board, which overflowed by 7 dp.
                    .padding(
                        horizontal = DenzaMetrics.Space.XL,
                        vertical = DenzaMetrics.Space.L,
                    ),
                verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.XL),
            ) {
                // The settings scroll and the action does not: whatever a panel holds, its one
                // action is in the same place under the same thumb.
                //
                // The settings take every pixel above the action. They used to be `weight(1f,
                // fill = false)` with a `Spacer(weight(1f))` under them, which reads like "as tall
                // as they need, then push the action down" and measures as something else
                // entirely: two children of weight 1 split the space in half, so the settings were
                // capped at half the panel whatever their size. On the car that silently hid the
                // mirrors' processing switch and its "check the cameras" button below a scroll
                // nobody could see the need for, and left the projection panel a void.
                Column(
                    modifier = Modifier.weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.XL),
                ) {
                    content()
                }
                footer()
            }
        }
    }
}

/**
 * A panel's name, what it is for, and the one way out of it.
 *
 * The icon repeats the tile the panel came from. On a screen where the panel covers a third of the
 * dashboard, that is the only thing saying which tile was pressed - and the tile it came from may
 * well be the one now underneath it.
 *
 * [onTitleTap] exists for one caller and is null everywhere else: a panel's title answers no touch
 * unless the panel says otherwise. It is deliberately on the title alone and not on the header, so
 * the subtitle and the way out keep answering only what they answer.
 */
@Composable
fun DenzaSheetHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onTitleTap: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DenzaColors.Accent,
                modifier = Modifier.size(HEADER_ICON),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.XS),
        ) {
            val titleTaps = remember { MutableInteractionSource() }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = DenzaColors.Ink,
                modifier = if (onTitleTap == null) {
                    Modifier
                } else {
                    // The whole line, not the glyphs. A title set at 24 sp is under 30 dp tall,
                    // which is already below the touch floor this app holds itself to; taking the
                    // width the column has anyway costs nothing and draws nothing, since the text
                    // stays where it was.
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = titleTaps,
                            indication = null,
                            onClick = onTitleTap,
                        )
                },
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = DenzaColors.Muted)
            }
        }
        val taps = remember { MutableInteractionSource() }
        Icon(
            imageVector = DenzaIcons.Close,
            contentDescription = "Закрыть",
            tint = DenzaColors.Muted,
            modifier = Modifier
                .size(CLOSE_ICON)
                .clickable(interactionSource = taps, indication = null, onClick = onDismiss),
        )
    }
}

/** A group of settings inside a panel, with the tracked capital that says what they share. */
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
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = DenzaColors.Muted,
        )
        content()
    }
}

/**
 * A panel's closing note: what pressing the tile does, said once.
 *
 * The one place on this screen where an explanation is allowed to be a sentence, because it is not
 * explaining a failure - it is telling the driver that the thing they just learned to do the slow
 * way has a fast way, which is the only kind of instruction worth printing.
 */
@Composable
fun DenzaSheetFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = DenzaColors.MutedDeep,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

private val HEADER_ICON = DenzaMetrics.Component.SHEET_HEADER_ICON
private val CLOSE_ICON = DenzaMetrics.Component.SHEET_CLOSE_ICON
private val SCRIM = DenzaColors.Background.copy(alpha = 0.55f)
