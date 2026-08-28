package dev.denza.apps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
                    .background(DenzaColors.Scrim)
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
                    // The ground is drawn under the caption bar and the header is not. A panel in a
                    // pane window has BYD's freeform drag handle across its top 24 dp, and a header
                    // laid out from the window's own edge puts the panel's name - and the only way
                    // out of it - underneath that handle. The scrim above keeps filling the window,
                    // so the panel still reaches the glass.
                    .windowInsetsPadding(WindowInsets.safeDrawing)
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
        DenzaSectionLabel(title)
        content()
    }
}

/**
 * The tracked capital on its own, for a panel whose groups are not a tidy nest of columns.
 *
 * The service panel is the one place that needs this: its groups are a long flat run of readings
 * and controls with headings between them, and wrapping each run in a column to get the heading
 * would be re-nesting a hundred lines to change a font.
 */
@Composable
fun DenzaSectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = DenzaColors.Muted,
        modifier = modifier,
    )
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

/**
 * The one surface that is not a panel at the edge: a card in the middle of the screen.
 *
 * It exists for the ADB gate and for the recovery window it opens, and for nothing else. A panel at
 * the edge leaves the dashboard beside it, which is exactly right for configuring a feature and
 * exactly wrong for a gate whose whole statement is that nothing behind it can be used yet.
 *
 * What it takes away from its two callers is the width. They had 0.72 and 0.68 of the screen -
 * indistinguishable at 1280 dp, 30 dp apart in a pane - and the pane is where a fraction of the
 * window stops being a design at all: 0.68 of 416 leaves 40 dp of prose between two 48 dp margins.
 * So the width is [DenzaMetrics.Component.MODAL_WIDTH] as a ceiling, and in a pane the card simply
 * fills what it is given.
 *
 * [onScrimTouch] is null for the gate: its scrim swallows the touch rather than answering it,
 * because there is nothing behind the gate to reach.
 */
@Composable
fun DenzaModalCard(
    compact: Boolean,
    modifier: Modifier = Modifier,
    onScrimTouch: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val taps = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DenzaColors.Scrim)
            .clickable(
                interactionSource = taps,
                indication = null,
                onClick = onScrimTouch ?: {},
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(DenzaMetrics.Space.XL),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = DenzaMetrics.Component.MODAL_WIDTH),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.SurfaceRaised),
        ) {
            Column(
                modifier = Modifier.padding(
                    if (compact) DenzaMetrics.Space.L else DenzaMetrics.Space.XL,
                ),
                verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.L),
                content = content,
            )
        }
    }
}

/**
 * [DenzaModalCard] in a window of its own, for a modal that has to sit above another one.
 *
 * The recovery window is opened from the gate, and the gate is drawn in the activity's own window
 * with a shield across it that swallows every touch. A dialog is the only thing a finger can reach
 * from there.
 */
@Composable
fun DenzaModalDialog(
    compact: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DenzaModalCard(
            compact = compact,
            modifier = modifier,
            onScrimTouch = onDismiss,
            content = content,
        )
    }
}

private val HEADER_ICON = DenzaMetrics.Component.SHEET_HEADER_ICON
private val CLOSE_ICON = DenzaMetrics.Component.SHEET_CLOSE_ICON
