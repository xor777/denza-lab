package dev.denza.apps.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk

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
 * **It is Luminofor's `tileFace()`, drawn the same way.** A flat plate with no border - lit or dark,
 * see [TileFace] - the glyph at `iconInset` added onto it through the board's own beam, and the two
 * lines of words hung on baselines 116 and 142 from the plate's top edge, 20 in from its left. The
 * words are placed by their baselines rather than stacked from an edge because that is how the board
 * places them: the first cut of this tile carried every number off the old board and still looked
 * nothing like it, because it stacked from the top what the board hung from the bottom. A baseline
 * cannot be stacked wrong.
 *
 * **The name is one line and so is the state.** Both used to take two if they needed them, and a
 * caption growing to two lines shoved the name upward - so switching the mirrors on moved the word
 * "Зеркала". Eleven tiles able to do that at different moments is a screen that twitches, and it
 * did. Anything longer is elided; the registry writes captions that fit, and the panel behind the
 * long press is where the long version lives.
 *
 * [tone] carries the state before any word is read; see [DenzaTileTone] and [TileFace].
 */
@Composable
fun DenzaTile(
    glyph: DenzaGlyph,
    name: String,
    state: String,
    tone: DenzaTileTone,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shown = DenzaTileTone.shown(tone, enabled)
    val face = TileFace.of(shown)
    val plate by animatedInk(face.plate, "tilePlate")
    val nameInk by animatedInk(face.name, "tileName")
    val statusInk by animatedInk(face.status, "tileStatus")
    val painter = remember { TileFacePainter() }
    val t = DenzaMetrics.Tile
    val glyphSize = DenzaMetrics.Component.TILE_ICON
    val shape = RoundedCornerShape(t.RADIUS)

    Box(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .drawBehind {
                painter.draw(
                    scope = this,
                    face = face,
                    plate = plate,
                    glyph = glyph,
                    glyphAt = Offset(t.GLYPH_LEFT.toPx(), t.GLYPH_TOP.toPx()),
                    glyphSize = glyphSize.toPx(),
                )
            },
    ) {
        TileLine(name, FontWeight(t.NAME_WEIGHT), t.NAME_SIZE, t.NAME_BASELINE, nameInk)
        TileLine(state, FontWeight(t.STATUS_WEIGHT), t.STATUS_SIZE, t.STATUS_BASELINE, statusInk)
        if (shown == DenzaTileTone.WORKING) {
            // Centred on the glyph's row and flush with the words' right margin. The board has no
            // working state; this is the app's, stroked with the glyph's own line.
            val ring = DenzaMetrics.Component.BUSY_DOT
            WorkingRing(
                size = ring,
                stroke = glyphStroke(glyphSize),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = t.GLYPH_TOP + (glyphSize - ring) / 2, end = t.TEXT_INSET),
            )
        }
    }
}

/**
 * One of the tile's two lines, on its baseline.
 *
 * Set as the board sets it: Roboto - the system sans-serif, which on this car is Roboto - at 500
 * for the name and 400 for the state, with no tracking. It takes no role from the theme: a role
 * brings a leading, a size in sp and, in Material's own scale, tracking of 0.15 and 0.25 sp, and
 * the tile wants none of them - so the style says `letterSpacing = 0` out loud rather than trusting
 * whatever a theme merges in. The size is pinned to dp rather than sp for the same reason the
 * board's is a pixel size: the tile is a fixed 164 and its baselines are fixed with it, and a
 * system font scale would push the words off the plate they are measured against.
 */
@Composable
private fun TileLine(text: String, weight: FontWeight, size: Float, baseline: Dp, ink: Color) {
    val fontScale = LocalDensity.current.fontScale
    Text(
        text = text,
        modifier = Modifier
            .padding(horizontal = DenzaMetrics.Tile.TEXT_INSET)
            .paddingFromBaseline(top = baseline),
        color = ink,
        style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = weight,
            fontSize = (size / fontScale).sp,
            letterSpacing = 0.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            // Advances as the font gives them, not hinted to whole pixels - the board's Chrome sets
            // text that way, and hinted Roboto ran a few per cent short of it.
            textMotion = TextMotion.Animated,
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An ARGB face colour, crossfaded when the tone changes. */
@Composable
internal fun animatedInk(argb: Int, label: String) = animateColorAsState(
    targetValue = Color(argb),
    animationSpec = tween(DenzaMetrics.Motion.TRANSITION_MS),
    label = label,
)

/** The glyph's line at [glyph] size: [DenzaMetrics.Stroke.ICON] grid units, in dp. */
internal fun glyphStroke(glyph: Dp): Dp = glyph * (DenzaMetrics.Stroke.ICON / DenzaIcons.VIEWPORT)

/**
 * The ring that says a feature is starting or recovering: a turning arc in the lit glyph's own
 * blue, the same weight as the glyph beside it.
 */
@Composable
internal fun WorkingRing(size: Dp, stroke: Dp, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = stroke,
        color = Color(HeadInk.BLUE.core),
        trackColor = Color.Transparent,
    )
}

// The corner mark is gone for the second time, and this grave is the one to read before digging
// it up. Attempt one was an 8 dp wedge floating 8 dp off the corner: rolled back off the car as
// something stuck to the tile rather than drawn with it. Attempt two learned that lesson - a fold
// flush in the corner, clipped by the tile's own radius, one quiet ink on all eleven - and was
// rolled back off the car too: too large at 40, and at 32 the owner's verdict was "чёрные
// ленточки на фотографиях". On a dark surface a dark diagonal in a corner reads as a mourning
// ribbon, and no size or alpha fixes a connotation. If the hold gesture ever gets an affordance
// again, it will not be a corner shape.
