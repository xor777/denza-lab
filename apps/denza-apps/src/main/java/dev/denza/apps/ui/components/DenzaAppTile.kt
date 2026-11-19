package dev.denza.apps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet

/**
 * One application in a chooser: the board's `apps` cell - a lit plate's colour, the icon or its
 * initial, the name under it, and when chosen the car's own selection badge in the corner (the
 * stock tab layout's `focused_icon_circle`, `#1677D9`, with its white check).
 *
 * Selection is the badge and nothing else: a coloured edge round the chosen one made its neighbours
 * look unchosen by comparison, and a tinted icon well made the icon lie about its own colours.
 *
 * The name wraps to two centred lines and the plate grows a line's step for the second - as tall
 * as its row's tallest name, which [DenzaAppGrid] provides through [LocalAppTileLines].
 *
 * [glyph] is for the one answer that is not an application - this app's own instruments on «Что
 * показывать» - drawn on the square an application without an icon gets its initial on.
 */
@Composable
fun DenzaAppTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconPackage: String? = null,
    enabled: Boolean = true,
    glyph: DenzaGlyph? = null,
) {
    // By the package: the state carries no pictures, and a republished state re-rasterises nothing.
    val bitmap = rememberAppIcon(iconPackage, ICON_PX)
    val a = Sheet.Apps
    val dim = if (enabled) 1f else DISABLED
    val lines = LocalAppTileLines.current.coerceIn(1, a.NAME_LINES)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height((a.TILE + (lines - 1) * a.NAME_STEP).dp)
            .clip(RoundedCornerShape(a.RADIUS.dp))
            .background(Color(Sheet.Plate.COLOR))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = ICON_TOP.dp)) {
            if (glyph != null) {
                // The one answer that is not an application - this app's own instruments on «Что
                // показывать» - on the same square an initial stands on, its glyph where the letter
                // would be.
                GlyphSquare(glyph, a.ICON, dim)
            } else if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(a.ICON.dp),
                    contentScale = ContentScale.Fit,
                    alpha = dim,
                )
            } else {
                LetterIcon(label, a.ICON, dim)
            }
        }
        BaselineText(
            text = label,
            style = appNameStyle(dim),
            baseline = NAME_BASELINE.dp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = NAME_INSET.dp),
            maxLines = a.NAME_LINES,
        )
        if (selected) {
            SelectionBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = a.BADGE_INSET.dp, end = a.BADGE_INSET.dp),
            )
        }
    }
}

/**
 * The car's own selection badge - the stock tab layout's `focused_icon_circle`, `#1677D9`, with its
 * white check - on a chosen application and at the end of a chosen row alike.
 */
@Composable
internal fun SelectionBadge(modifier: Modifier = Modifier) {
    val a = Sheet.Apps
    Canvas(modifier.size(a.BADGE.dp)) {
        drawCircle(Color(a.BADGE_COLOR))
        scale(size.width / a.BADGE, pivot = Offset.Zero) {
            drawPath(
                check,
                Color.White,
                style = Stroke(width = CHECK_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * An application's name as a tile sets it, at [dim] of its light: the board's 14/500 at 0.9, lines
 * [Sheet.Apps.NAME_STEP] apart. [DenzaAppGrid] measures with the same style, so a row grows exactly
 * when a name in it breaks.
 */
internal fun appNameStyle(dim: Float) = Sheet.Apps.let { a ->
    SheetInk.style(a.NAME_SIZE, 500, SheetInk.white(a.NAME_ALPHA * dim), a.NAME_STEP / a.NAME_SIZE)
}

/** The badge's check on its own 20-unit box, scaled to the badge's pixels as it is drawn. */
private val check: Path by lazy { PathParser().parsePathString(SheetGlyphs.CHECK).toPath() }

private const val ICON_PX = 128

/** The icon's top, the name's baseline and how far the name stays off the plate's sides. */
private const val ICON_TOP = 14f
private const val NAME_BASELINE = 82f
internal const val NAME_INSET = 6f
private const val CHECK_STROKE = 2f
