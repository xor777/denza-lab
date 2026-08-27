package dev.denza.apps.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * One feature on the dashboard: what it is, what it is doing, and two ways to touch it.
 *
 * Every tile is the same tile. The screen this replaces had three different cards - one 314 dp tall
 * with its own switch in the header, two 96 dp ones that were near-duplicates of each other - and a
 * feature's importance was expressed by which card it happened to get. That is a layout decision
 * masquerading as a product decision, and it does not survive a feature being added.
 *
 * So the tile carries no controls at all. A short press does the feature's own main action - put the
 * instruments on the cluster, start the projection, raise the speakers - and a long press opens its
 * settings. Nothing on the face of the tile can be pressed by accident on a moving car.
 *
 * The composition is the board's: the icon at the top edge, the words at the bottom edge, and the
 * slack between them rather than under them. The first cut stacked all three from the top and left
 * the bottom third of every tile empty - the same numbers as the board, in the wrong order, which
 * is how a screen ends up looking nothing like its design while matching it on paper.
 *
 * **The name is one line and so is the state.** Both used to take two if they needed them, and
 * since the block is anchored to the bottom edge, a caption growing to two lines shoved the name
 * upward - so switching the mirrors on moved the word "Зеркала". Eleven tiles able to do that at
 * different moments is a screen that twitches, and it did. Anything longer is elided; the
 * registry writes captions that fit, and the panel behind the long press is where the long version
 * lives.
 *
 * [tone] carries the state before any word is read; see [DenzaTileTone]. [caption] decides whether
 * the line under the name is worth the accent; see [DenzaTileCaption].
 */
@Composable
fun DenzaTile(
    icon: ImageVector,
    name: String,
    state: String,
    tone: DenzaTileTone,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: DenzaTileCaption = DenzaTileCaption.SETTING,
    enabled: Boolean = true,
) {
    val accent = toneAccent(tone)
    val background by animateColorAsState(
        targetValue = if (tone == DenzaTileTone.LIVE) DenzaColors.Surface else DenzaColors.SurfaceQuiet,
        animationSpec = tween(DenzaMetrics.Motion.TRANSITION_MS),
        label = "tileBackground",
    )
    val edge by animateColorAsState(
        targetValue = accent.copy(alpha = if (tone == DenzaTileTone.IDLE) 0.10f else 0.30f),
        animationSpec = tween(DenzaMetrics.Motion.TRANSITION_MS),
        label = "tileEdge",
    )
    val shape = RoundedCornerShape(DenzaMetrics.Radius.L)

    Box(
        modifier = modifier
            .height(DenzaMetrics.Component.TILE_HEIGHT)
            .background(background, shape)
            .border(BorderStroke(DenzaMetrics.Stroke.HAIRLINE, edge), shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(DenzaMetrics.Space.L),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (tone == DenzaTileTone.IDLE) DenzaColors.Muted else accent,
                    modifier = Modifier.size(DenzaMetrics.Component.TILE_ICON),
                )
                if (tone == DenzaTileTone.WORKING) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(BUSY_DOT),
                            strokeWidth = BUSY_STROKE,
                            color = DenzaColors.Accent,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) DenzaColors.Ink else DenzaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state,
                    style = MaterialTheme.typography.bodyMedium,
                    color = captionColor(tone, caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The edge and the icon. Amber and coral come from the vehicle, so a warning here is the same
 * warning the car itself would draw.
 */
private fun toneAccent(tone: DenzaTileTone): Color = when (tone) {
    DenzaTileTone.LIVE, DenzaTileTone.WORKING -> DenzaColors.Accent
    DenzaTileTone.IDLE -> DenzaColors.Ink
    DenzaTileTone.ATTENTION -> DenzaColors.Warning
    DenzaTileTone.BROKEN -> DenzaColors.Danger
}

/**
 * The state line.
 *
 * Only two things earn a colour other than grey here: a live tile whose caption is a reading of
 * what the feature is doing, and a state the driver has to act on. A caption that shouts on a
 * healthy car teaches the driver to stop reading captions, and a screen where most captions shout
 * has no way left to say that one of them matters.
 */
private fun captionColor(tone: DenzaTileTone, caption: DenzaTileCaption): Color = when (tone) {
    DenzaTileTone.ATTENTION -> DenzaColors.Warning
    DenzaTileTone.BROKEN -> DenzaColors.Danger
    DenzaTileTone.LIVE ->
        if (caption == DenzaTileCaption.READING) DenzaColors.Accent else DenzaColors.Muted
    DenzaTileTone.IDLE, DenzaTileTone.WORKING -> DenzaColors.Muted
}

// The corner mark that used to sit here is gone. It marked the four tiles whose long press led
// somewhere, in the convention a phone keyboard uses for a key with more characters under it - and
// on the car it read as something stuck to the tile rather than drawn with it. Every tile has a
// panel now, so a mark distinguishing them would be on all eleven and distinguish nothing.

private val BUSY_DOT = 18.dp
private val BUSY_STROKE = 2.dp
