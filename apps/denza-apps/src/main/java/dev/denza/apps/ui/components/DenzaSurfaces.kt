package dev.denza.apps.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet

/**
 * The surface a feature's settings arrive on, and the header that names them.
 *
 * The Luminofor board's `drawSheet()` (`tools/design-canvas/luminofor/`): a panel [Sheet.Panel.WIDTH]
 * dp wide hung off the right edge for the full height, over a black scrim at [Sheet.SCRIM], its
 * ground an idle plate's colour with a hairline down its left edge - or, in a pane, the whole window
 * below the caption bar. A panel at the edge leaves the dashboard beside it, so the thing being
 * configured stays in sight while it is configured.
 *
 * The settings scroll and the footer does not: whatever a panel holds, its one action is in the
 * same place under the same thumb. [scrolls] is off for a page whose body is one long list, which
 * scrolls itself - a list inside a scrolling column drags the page under it. [scrollState] is for a
 * panel of several pages: each keeps its own place, so the page a row opened comes back where the
 * row was, and a short page is not opened at the offset a long one was left at.
 *
 * Under [LocalStillFrame] - the debug build's fixture mode - the panel is drawn in place rather than
 * in a dialog window of its own, so a screenshot of it is the dashboard with the panel over it, as
 * the board draws them.
 */
@Composable
fun DenzaSheet(
    onDismiss: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    dismissOnOutsideTouch: Boolean = true,
    scrolls: Boolean = true,
    scrollState: ScrollState? = null,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        SheetPanel(onDismiss, compact, modifier, dismissOnOutsideTouch, scrolls, scrollState, footer, content)
    }
    if (LocalStillFrame.current) {
        body()
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = dismissOnOutsideTouch,
            ),
            content = body,
        )
    }
}

@Composable
private fun SheetPanel(
    onDismiss: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
    dismissOnOutsideTouch: Boolean,
    scrolls: Boolean,
    scrollState: ScrollState?,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = scrollState ?: rememberScrollState()
    val p = Sheet.Panel
    val k = Sheet.Compact
    val padX = if (compact) k.PAD_X else p.PAD_X
    val padTop = if (compact) k.PAD_TOP else p.PAD_TOP
    val padBottom = if (compact) k.PAD_BOTTOM else p.PAD_BOTTOM
    val gap = if (compact) k.GAP else p.GAP
    Box(modifier = modifier.fillMaxSize()) {
        // The scrim is its own surface rather than the dialog's own dimming, so the panel can sit
        // hard against the edge with nothing between it and the glass.
        val taps = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = Sheet.SCRIM))
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
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(p.WIDTH.dp))
                // The ground starts under a pane's caption bar, not behind it: BYD's freeform
                // windowing keeps its drag handle across the window's top 24 dp.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(Color(p.GROUND))
                .then(
                    if (compact) Modifier
                    else Modifier.drawBehind {
                        drawRect(SheetInk.white(p.EDGE_ALPHA), size = size.copy(width = 1.dp.toPx()))
                    },
                )
                // A tap on the panel's own ground is the panel's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = padX.dp, end = padX.dp, top = padTop.dp, bottom = padBottom.dp),
            verticalArrangement = Arrangement.spacedBy(gap.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f)
                    .then(if (scrolls) Modifier.verticalScroll(scroll) else Modifier),
                verticalArrangement = Arrangement.spacedBy(gap.dp),
                content = content,
            )
            footer()
        }
    }
}

/**
 * A panel's name, and the one way out of it: the board's header row.
 *
 * [glyph] repeats the tile the panel came from, white and centred on its own ink - it names the
 * panel and says nothing about the feature's state, which is the status line's job. [onBack] turns
 * the same header into a page's: the leading slot becomes the way back to the panel this page was
 * opened from. [subtitle], on a page, says what the choice is capped at and where it stands.
 *
 * [onTitleTap] exists for one caller and is null everywhere else: a panel's title answers no touch
 * unless the panel says otherwise.
 */
@Composable
fun DenzaSheetHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: DenzaGlyph? = null,
    onTitleTap: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val h = Sheet.Header
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(h.HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                val backTaps = remember { MutableInteractionSource() }
                LineGlyph(
                    paths = SheetGlyphs.BACK,
                    size = h.CLOSE.dp,
                    alpha = h.CLOSE_ALPHA,
                    modifier = Modifier.clickable(interactionSource = backTaps, indication = null, onClick = onBack),
                )
                Spacer(Modifier.width(h.GLYPH_GAP.dp))
            } else if (glyph != null) {
                NamedGlyph(glyph, h.GLYPH.dp, h.GLYPH_ALPHA, Color(Sheet.Panel.GROUND))
                Spacer(Modifier.width(h.GLYPH_GAP.dp))
            }
            val titleTaps = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .then(
                        if (onTitleTap == null) Modifier
                        else Modifier.clickable(interactionSource = titleTaps, indication = null, onClick = onTitleTap),
                    ),
            ) {
                BaselineText(
                    text = title,
                    style = SheetInk.style(h.TITLE_SIZE, 500),
                    baseline = centredBaseline(h.HEIGHT / 2f, h.TITLE_SIZE),
                )
            }
            val taps = remember { MutableInteractionSource() }
            LineGlyph(
                paths = SheetGlyphs.CLOSE,
                size = h.CLOSE.dp,
                alpha = h.CLOSE_ALPHA,
                modifier = Modifier.clickable(interactionSource = taps, indication = null, onClick = onDismiss),
            )
        }
        if (subtitle.isNotBlank()) {
            val lead = when {
                onBack != null -> h.CLOSE + h.GLYPH_GAP
                glyph != null -> h.GLYPH + h.GLYPH_GAP
                else -> 0f
            }
            BaselineText(
                text = subtitle,
                style = SheetInk.style(h.SUBTITLE_SIZE, 400, SheetInk.white(h.SUBTITLE_ALPHA)),
                baseline = (h.SUBTITLE_SIZE * Sheet.Roboto.ASCENT).dp,
                modifier = Modifier.padding(start = lead.dp),
            )
        }
    }
}

/** A group of settings inside a panel, under the words that say what they share. */
@Composable
fun DenzaSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Sheet.Label.GAP.dp),
    ) {
        DenzaSectionLabel(title)
        content()
    }
}

/**
 * The words over a group, on their own: sentence case, the stock list's summary grey. The old
 * panels set these as a tracked capital; the car's own settings never shout a heading.
 */
@Composable
fun DenzaSectionLabel(title: String, modifier: Modifier = Modifier) {
    val l = Sheet.Label
    BaselineText(
        text = title,
        style = SheetInk.style(l.SIZE, 500, SheetInk.white(l.ALPHA)),
        baseline = (l.SIZE * Sheet.Roboto.ASCENT).dp,
        modifier = modifier,
    )
}

/**
 * A panel's closing note: what pressing the tile does, said once, under the action it describes.
 */
@Composable
fun DenzaSheetFootnote(text: String, modifier: Modifier = Modifier) {
    val f = Sheet.Footnote
    BaselineText(
        text = text,
        style = SheetInk.style(f.SIZE, 400, SheetInk.white(f.ALPHA)),
        baseline = (f.SIZE * Sheet.Roboto.ASCENT).dp,
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
    val m = Sheet.Modal
    val taps = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = Sheet.SCRIM))
            .clickable(
                interactionSource = taps,
                indication = null,
                onClick = onScrimTouch ?: {},
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(Sheet.Compact.PAD_X.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The stock dialog (`systemsettings_common_dialog_bg_*`): a lit plate's colour, a hairline
        // round it at the white's 0.08, the large radius.
        val shape = RoundedCornerShape(m.RADIUS.dp)
        Column(
            modifier = Modifier
                // The ceiling before the fill: a fill first fixes the width to the window's, and a
                // ceiling after it has nothing left to limit.
                .widthIn(max = m.WIDTH.dp)
                .fillMaxWidth()
                .background(Color(Sheet.Plate.COLOR), shape)
                .border(BorderStroke(1.dp, SheetInk.white(Sheet.Panel.EDGE_ALPHA)), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(if (compact) Sheet.Compact.PAD_X.dp else m.PAD.dp),
            verticalArrangement = Arrangement.spacedBy(m.GAP.dp),
            content = content,
        )
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

