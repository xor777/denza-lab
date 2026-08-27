package dev.denza.apps.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * One feature in a pane: the tile with its words taken away.
 *
 * A pane is 828 or 416 dp wide and still 680 tall, and ten tiles at the width their names need
 * would spend three quarters of that height on words. In a pane they are not being read: the driver
 * came here with the other two thirds of the screen doing something else, already knows the ten
 * glyphs from the full screen, and what is worth the room is the thing that moves. So the caption
 * goes and the chip keeps what a caption was carrying anyway - is this on - in the border, the ink
 * and a dot.
 *
 * Both gestures survive unchanged, which is the point of it being the same object: a short press
 * does the feature's own action and a long press opens its panel, where every word that was
 * dropped here is written out in full.
 *
 * Square, and as wide as the row gives it: ten across the two-thirds pane is 68.0 dp, five across
 * two rows of the narrow one is 68.8. See `TwoThirds.dc.html` and `OneThird.dc.html`.
 */
@Composable
fun DenzaChip(
    icon: ImageVector,
    tone: DenzaTileTone,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = chipAccent(tone)
    val background by animateColorAsState(
        targetValue = if (tone == DenzaTileTone.LIVE) DenzaColors.Surface else DenzaColors.SurfaceQuiet,
        animationSpec = tween(DenzaMetrics.Motion.TRANSITION_MS),
        label = "chipBackground",
    )
    val edge by animateColorAsState(
        targetValue = accent.copy(alpha = if (tone == DenzaTileTone.IDLE) 0.10f else 0.30f),
        animationSpec = tween(DenzaMetrics.Motion.TRANSITION_MS),
        label = "chipEdge",
    )
    val shape = RoundedCornerShape(DenzaMetrics.Radius.M)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(background, shape)
            .border(BorderStroke(DenzaMetrics.Stroke.HAIRLINE, edge), shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tone == DenzaTileTone.IDLE || !enabled) DenzaColors.Muted else accent,
            modifier = Modifier.size(DenzaMetrics.Component.TILE_ICON),
        )
        if (tone == DenzaTileTone.WORKING) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DenzaMetrics.Component.CHIP_DOT_INSET)
                    .size(DenzaMetrics.Component.CHIP_DOT),
                strokeWidth = BUSY_STROKE,
                color = DenzaColors.Accent,
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DenzaMetrics.Component.CHIP_DOT_INSET)
                    .size(DenzaMetrics.Component.CHIP_DOT)
                    .background(dotColour(tone), CircleShape),
            )
        }
    }
}

/** The border and the glyph, the same four answers the tile gives. */
private fun chipAccent(tone: DenzaTileTone): Color = when (tone) {
    DenzaTileTone.LIVE, DenzaTileTone.WORKING -> DenzaColors.Accent
    DenzaTileTone.IDLE -> DenzaColors.Ink
    DenzaTileTone.ATTENTION -> DenzaColors.Warning
    DenzaTileTone.BROKEN -> DenzaColors.Danger
}

/**
 * The dot, which is the whole of what a chip says.
 *
 * It is deliberately not the same statement as the border. A dark border against a dark page is a
 * difference of a few per cent that the eye finds by comparing one chip with its neighbours; the
 * dot is a lit thing or an unlit thing and is read without comparing anything. That is what makes
 * a row of ten scannable at a glance from a driver's seat, and it is the archived board's own
 * device rather than something invented here.
 */
private fun dotColour(tone: DenzaTileTone): Color = when (tone) {
    DenzaTileTone.LIVE, DenzaTileTone.WORKING -> DenzaColors.Accent
    DenzaTileTone.IDLE -> DenzaColors.MutedDeep
    DenzaTileTone.ATTENTION -> DenzaColors.Warning
    DenzaTileTone.BROKEN -> DenzaColors.Danger
}

private val BUSY_STROKE = 1.5.dp
