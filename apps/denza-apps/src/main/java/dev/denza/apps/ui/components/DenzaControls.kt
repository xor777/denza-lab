package dev.denza.apps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet

/**
 * The controls a feature's settings are made of.
 *
 * There is one of each here because there used to be several of each in one file: four ways to draw
 * a row with a switch on it, nine copies of the accent button, two dictionaries translating the same
 * statuses into the same words. Each copy drifted a little - a different corner radius, a caption a
 * size smaller, a disabled state one of them had and the others did not - and none of the drift was
 * a decision anybody made.
 *
 * They read every number from [Sheet] - `spec.json` → `sheet`, the numbers the Luminofor board's
 * `drawSheet()` draws with - so a change there moves the board and all of them at once. That is the
 * only reason a component layer is worth having.
 */

/**
 * A setting that is on or off, on a plate of its own: the stock list row with the stock switch.
 *
 * The whole row toggles - a target that stops at the switch is a target that misses in a moving
 * car - and the row is the board's: one line at [Sheet.Row.SINGLE_BASELINE], or a title and the
 * reason it is what it is at [Sheet.Row.TWO_TITLE] and [Sheet.Row.TWO_SUMMARY].
 */
@Composable
fun DenzaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val p = Sheet.Plate
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(p.RADIUS.dp))
            .background(Color(p.COLOR))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        SheetRow(title = title, summary = subtitle, enabled = enabled) {
            SheetSwitch(checked = checked, enabled = enabled)
        }
    }
}

/**
 * One row of a plate - a title, the line under it, and whatever stands at its end - at the board's
 * heights and baselines. Shared by the switch row and the choice row, so the two kinds of setting in
 * one column are one kind of line.
 *
 * The line under the title may take two lines at [Sheet.Row.SUMMARY_STEP] apart, and the row grows
 * by the second: some of these are warnings - Wi-Fi kept on can flatten the battery - and a warning
 * cut off after «может разрядиться ак…» is not a warning.
 */
@Composable
internal fun SheetRow(
    title: String,
    summary: String?,
    enabled: Boolean,
    icons: (@Composable () -> Unit)? = null,
    end: @Composable () -> Unit,
) {
    val r = Sheet.Row
    val dim = if (enabled) 1f else DISABLED
    val two = !summary.isNullOrBlank()
    val titleBaseline = when {
        icons != null -> r.ICONS_TITLE
        two -> r.TWO_TITLE
        else -> r.SINGLE_BASELINE
    }
    val room = Modifier.padding(end = (Sheet.Switch.WIDTH + r.PAD_X).dp)
    Layout(
        modifier = Modifier.fillMaxWidth().padding(horizontal = r.PAD_X.dp),
        content = {
            BaselineText(
                text = title,
                style = SheetInk.style(r.TITLE_SIZE, 400, SheetInk.white(r.TITLE_ALPHA * dim)),
                baseline = titleBaseline.dp,
                modifier = room,
            )
            when {
                icons != null -> Box { icons() }
                two -> Text(
                    text = summary.orEmpty(),
                    style = SheetInk.style(r.SUMMARY_SIZE, 400, SheetInk.white(r.SUMMARY_ALPHA * dim))
                        .copy(lineHeight = r.SUMMARY_STEP.sp),
                    maxLines = SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = room,
                )
                else -> Box {}
            }
            Box { end() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val titleP = measurables[0].measure(loose)
        val lineP = measurables[1].measure(loose)
        val endP = measurables[2].measure(loose)
        // The summary's first baseline on the board's line; each further line a step lower, and
        // the row as much taller.
        var extra = 0
        var lineTop = 0
        if (icons != null) {
            lineTop = r.ICONS_TOP.dp.roundToPx()
        } else if (two) {
            val first = lineP[FirstBaseline]
            val last = lineP[LastBaseline]
            lineTop = r.TWO_SUMMARY.dp.roundToPx() - first
            extra = last - first
        }
        val height = when {
            icons != null -> r.ICONS_HEIGHT
            two -> r.TWO_HEIGHT
            else -> r.SINGLE_HEIGHT
        }.dp.roundToPx() + extra
        layout(constraints.maxWidth, height) {
            titleP.place(0, 0)
            lineP.place(0, lineTop)
            endP.place(constraints.maxWidth - endP.width, (height - endP.height) / 2)
        }
    }
}

/** Two lines under a title, no more: a third is a paragraph, and paragraphs go under the plate. */
private const val SUMMARY_LINES = 2

/**
 * A choice of two to four, all visible at once: the stock tab layout - a track of white at 0.1, the
 * chosen cell a white pill at 0.8 with its word dark on it.
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
    val g = Sheet.Segmented
    val a = if (enabled) 1f else DISABLED
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(g.HEIGHT.dp)
            .clip(RoundedCornerShape(g.RADIUS.dp))
            .background(SheetInk.white(g.TRACK_ALPHA))
            .padding(g.PAD.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape((g.RADIUS - g.PAD).dp))
                    .background(if (selected) SheetInk.white(g.PILL_ALPHA * a) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(index) },
            ) {
                BaselineText(
                    text = label,
                    style = SheetInk.style(
                        g.SIZE,
                        if (selected) 500 else 400,
                        if (selected) Color.Black.copy(alpha = g.ON_TEXT_ALPHA * a) else SheetInk.white(g.OFF_TEXT_ALPHA * a),
                    ),
                    baseline = centredBaseline(g.HEIGHT / 2f - g.PAD, g.SIZE),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * What a feature is and how it behaves, in the driver's words: a paragraph in the stock list's
 * summary grey, at the foot of the panel's settings.
 */
@Composable
fun DenzaNote(text: String, modifier: Modifier = Modifier) {
    val n = Sheet.Note
    ParagraphText(
        text = text,
        style = SheetInk.style(n.SIZE, 400, SheetInk.white(n.ALPHA), n.LEADING),
        size = n.SIZE,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The one action a surface exists to offer: the stock large primary button. */
@Composable
fun DenzaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val b = Sheet.Button
    val a = if (enabled) 1f else b.DISABLED_ALPHA
    SheetButton(text, onClick, modifier, enabled, b.HEIGHT, Color(b.PRIMARY).copy(alpha = a), b.SIZE, SheetInk.white(a))
}

/**
 * An action beside the main one, quiet enough not to be mistaken for it: white at 0.06.
 *
 * [attention] sets its word in the car's orange - the door to a recovery flow, the one action that
 * answers something waiting on the driver.
 */
@Composable
fun DenzaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    attention: Boolean = false,
) {
    val b = Sheet.Button
    val a = if (enabled) 1f else b.DISABLED_ALPHA
    SheetButton(
        text, onClick, modifier, enabled, b.SECONDARY_HEIGHT, SheetInk.white(b.SECONDARY_ALPHA), b.SECONDARY_SIZE,
        if (attention) Color(ClusterInk.ORANGE.halo).copy(alpha = a) else SheetInk.white(SECONDARY_TEXT * a),
    )
}

@Composable
private fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Float,
    fill: Color,
    size: Float,
    ink: Color,
) {
    // As wide as the caller makes it - the panels' footers fill the panel, two buttons in a row
    // share it - and never narrower than its word with a row's padding either side.
    Box(
        modifier = modifier
            .height(height.dp)
            .clip(RoundedCornerShape(Sheet.Button.RADIUS.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Sheet.Row.PAD_X.dp),
    ) {
        BaselineText(
            text = text,
            style = SheetInk.style(size, 500, ink),
            baseline = centredBaseline(height / 2f, size),
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * A named reading, for the service page and anywhere else a fact needs its label: the label in the
 * summary grey over the value, as the board's `reading` block stacks them. [stacked] is kept for the
 * callers that asked for it; a reading is always stacked now.
 */
@Composable
fun DenzaKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") stacked: Boolean = true,
) {
    val r = Sheet.Reading
    val ro = Sheet.Roboto
    Column(modifier.fillMaxWidth()) {
        BaselineText(
            text = label,
            style = SheetInk.style(r.LABEL_SIZE, 400, SheetInk.white(r.LABEL_ALPHA)),
            baseline = (r.LABEL_SIZE * ro.ASCENT).dp,
        )
        BaselineText(
            text = value,
            style = SheetInk.style(r.VALUE_SIZE, 400, SheetInk.white(r.VALUE_ALPHA)),
            baseline = (r.GAP + r.VALUE_SIZE * ro.ASCENT).dp,
            maxLines = 3,
        )
    }
}

/**
 * What a feature is doing, in its own words, in the colour that state deserves: the car's orange
 * when it waits on the driver, its red when it is broken - whole, as the tile's status is - and the
 * summary grey otherwise.
 *
 * Two lines, always: some of these words come from the car, and a line that can reflow by four
 * lines leaves nothing under it a settled place to be.
 */
@Composable
fun DenzaStatusLine(
    text: String,
    tone: DenzaTileTone,
    modifier: Modifier = Modifier,
    maxLines: Int = STATUS_LINES,
) {
    if (text.isBlank()) return
    val st = Sheet.Status
    ParagraphText(
        text = text,
        size = st.SIZE,
        style = SheetInk.style(
            st.SIZE,
            400,
            when (tone) {
                DenzaTileTone.ATTENTION -> Color(ClusterInk.ORANGE.halo)
                DenzaTileTone.BROKEN -> Color(ClusterInk.RED.halo)
                else -> SheetInk.white(Sheet.Note.ALPHA)
            },
            st.LEADING,
        ),
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
    )
}

private const val STATUS_LINES = 2

/** A control nothing can be done to, at half its light - the stock kit's disabled rows. */
internal const val DISABLED = 0.5f

/** The quiet button's word, at the stock list title's 0.9. */
private const val SECONDARY_TEXT = 0.9f
