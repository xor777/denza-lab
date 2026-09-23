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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet

/** One application on a row's value line: enough to draw it, and the key it is cached under. */
data class DenzaChoiceIcon(val key: Any, val label: String, val drawable: Drawable?)

/**
 * What is chosen, on one row, with the way to change it.
 *
 * This is what a panel shows instead of the choice itself. A grid of every application the car has
 * belongs on a page of its own - see [DenzaAppChooser] - and what belongs in a panel beside a
 * switch and a paragraph is the answer: the icons that are chosen, then a short line, or the line
 * alone when nothing is.
 *
 * The row draws no surface of its own: rows under one switch are one group and one silhouette, and
 * that is [DenzaChoiceGroup]'s job.
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
    val r = Sheet.Row
    val dim = if (enabled) 1f else DISABLED
    Box(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        SheetRow(
            title = title,
            summary = if (icons.isEmpty()) value else null,
            enabled = enabled,
            icons = if (icons.isEmpty()) null else {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(r.CHOICE_GAP.dp)) {
                        icons.forEach { icon -> ChoiceIcon(icon) }
                        if (value.isNotBlank()) {
                            BaselineText(
                                text = value,
                                style = SheetInk.style(r.SUMMARY_SIZE, 400, SheetInk.white(r.SUMMARY_ALPHA * dim)),
                                baseline = (r.ICONS_VALUE - r.ICONS_TOP).dp,
                                modifier = Modifier.padding(end = (r.CHEVRON + r.PAD_X).dp),
                            )
                        }
                    }
                }
            },
        ) {
            LineGlyph(SheetGlyphs.FORWARD, r.CHEVRON.dp, Sheet.Header.CLOSE_ALPHA)
        }
    }
}

/**
 * One plate with hairlines between its rows: a list is one surface, and it is the surface a switch
 * row stands on, so a switch above a group of choices reads as one column of settings.
 */
@Composable
fun <T> DenzaChoiceGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    val p = Sheet.Plate
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(p.RADIUS.dp))
            .background(Color(p.COLOR)),
    ) {
        items.forEachIndexed { index, entry ->
            // The hairline is drawn over the row's top rather than above it, as the board draws it,
            // so every row keeps its full height and the plate is the sum of its rows.
            Box(
                Modifier.drawWithContent {
                    drawContent()
                    if (index > 0) {
                        val inset = p.HAIRLINE_INSET.dp.toPx()
                        drawRect(
                            SheetInk.white(p.HAIRLINE_ALPHA),
                            topLeft = Offset(inset, 0f),
                            size = Size(size.width - 2 * inset, 1.dp.toPx()),
                        )
                    }
                },
            ) { row(entry) }
        }
    }
}

/** An application on a value line: its own icon, or its initial on a square of white at 0.1. */
@Composable
private fun ChoiceIcon(icon: DenzaChoiceIcon) {
    // Keyed by the package alone, for the reason [DenzaAppTile] gives: a Drawable is a fresh
    // instance on every read of the package manager.
    val bitmap = remember(icon.key) {
        icon.drawable?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap()
    }
    val size = Sheet.Row.CHOICE_ICON
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = icon.label,
            modifier = Modifier.size(size.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        LetterIcon(icon.label, size)
    }
}

/**
 * The initial the board draws where an application has no icon: a square of white at 0.1 rounded at
 * 0.27 of its side, the letter at 0.45 of it, centred.
 */
@Composable
internal fun LetterIcon(label: String, size: Float, alpha: Float = 1f) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * LETTER_RADIUS).dp))
            .background(SheetInk.white(LETTER_GROUND * alpha)),
    ) {
        val letter = size * LETTER_SIZE
        BaselineText(
            text = label.take(1).uppercase(),
            style = SheetInk.style(letter, 500, SheetInk.white(LETTER_INK * alpha)),
            baseline = centredBaseline(size / 2f, letter),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val ICON_PX = 128
private const val LETTER_RADIUS = 0.27f
private const val LETTER_SIZE = 0.45f
private const val LETTER_GROUND = 0.1f
private const val LETTER_INK = 0.9f
