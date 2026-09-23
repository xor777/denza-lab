package dev.denza.apps.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LuminoforSpec

/**
 * One feature in a pane: the tile with its words taken away.
 *
 * A pane is 828 or 416 dp wide and still 680 tall, and eleven tiles at the width their names need
 * would spend three quarters of that height on words. In a pane they are not being read: the driver
 * came here with the other two thirds of the screen doing something else, already knows the eleven
 * glyphs from the full screen, and what is worth the room is the thing that moves. So the words go
 * and the chip keeps what they were carrying anyway - is this on - in the plate and the glyph's
 * light, which is the tile's own [TileFace] and nothing else.
 *
 * Both gestures survive unchanged, which is the point of it being the same object: a short press
 * does the feature's own action and a long press opens its panel, where every word that was
 * dropped here is written out in full.
 *
 * Square, and as wide as the row gives it: eleven across the two-thirds pane is 60.7 dp, six across
 * two rows of the narrow one is 55.3. The glyph is a fixed [glyphSize] - 26 in both panes - centred
 * as a box, the way Luminofor's `tileFace()` centres it: the box and not the ink, so a glyph hung on
 * the shared left edge sits a little left of the chip's centre, on the board as here.
 *
 * **No dot.** The older chip carried its state three times - a border, a tinted glyph and a lit dot
 * in the corner - because a dark border on a dark page is a difference the eye finds only by
 * comparing neighbours. Luminofor's lit plate and blue glyph against a dark plate and a grey one are
 * read without comparing anything, which was the whole of the dot's job, and the board has none.
 */
@Composable
fun DenzaChip(
    glyph: DenzaGlyph,
    tone: DenzaTileTone,
    radius: Dp,
    glyphSize: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shown = DenzaTileTone.shown(tone, enabled)
    val face = TileFace.of(shown)
    val plate by animatedInk(face.plate, "chipPlate")
    val painter = remember { TileFacePainter() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .drawBehind {
                val box = glyphSize.toPx()
                painter.draw(
                    scope = this,
                    face = face,
                    plate = plate,
                    glyph = glyph,
                    glyphAt = Offset((size.width - box) / 2f, (size.height - box) / 2f),
                    glyphSize = box,
                )
            },
    ) {
        if (shown == DenzaTileTone.WORKING) {
            // In the corner the dot used to hold, clear of a 26 glyph in a chip down to 52.
            WorkingRing(
                size = LuminoforSpec.Head.Icon.Ring.CHIP_SIZE.dp,
                stroke = glyphStroke(glyphSize),
                modifier = Modifier.align(Alignment.TopEnd).padding(CHIP_RING_INSET),
            )
        }
    }
}

/** How far the chip's working ring stands in from its corner - clear of a 14 or 16 dp radius. */
private val CHIP_RING_INSET = LuminoforSpec.Head.Icon.Ring.CHIP_INSET.dp
