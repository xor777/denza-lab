package dev.denza.apps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import androidx.compose.ui.unit.dp

/**
 * A box and its words on a quiet plate, inside a modal card: the board's `drawModal` box.
 *
 * Ticked, the box is the stock switch's blue with a white tick; unticked, the switch's off track and
 * no tick. [onToggle] null makes it a picture - the ADB gate's copy of the box on the car's own
 * prompt, which is the car's to tick - and otherwise the whole plate is the box's touch.
 */
@Composable
internal fun DenzaTickPlate(
    label: String,
    ticked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null,
) {
    val m = Sheet.Modal
    val taps = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = m.BOX_ROW.dp)
            .clip(RoundedCornerShape(Sheet.Button.RADIUS.dp))
            .background(SheetInk.white(Sheet.Button.SECONDARY_ALPHA))
            .then(
                if (onToggle == null) Modifier
                else Modifier.clickable(interactionSource = taps, indication = null, role = Role.Checkbox, onClick = onToggle),
            )
            .padding(horizontal = Sheet.Row.PAD_X.dp, vertical = m.BOX_PAD.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.BOX_GAP.dp),
    ) {
        Canvas(Modifier.size(m.BOX.dp)) {
            val unit = size.width / m.BOX
            val corner = CornerRadius(m.BOX_RADIUS * unit)
            if (!ticked) {
                drawRoundRect(Color(Sheet.Switch.OFF).copy(alpha = Sheet.Switch.OFF_ALPHA), cornerRadius = corner)
                return@Canvas
            }
            drawRoundRect(Color(Sheet.Switch.ON), cornerRadius = corner)
            val t = m.BOX_TICK
            val tick = Path().apply {
                moveTo(t[0] * unit, t[1] * unit)
                lineTo(t[2] * unit, t[3] * unit)
                lineTo(t[4] * unit, t[5] * unit)
            }
            drawPath(
                tick,
                Color.White,
                style = Stroke(width = m.BOX_STROKE * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        ParagraphText(
            label,
            style = SheetInk.style(m.TEXT_SIZE, 400, SheetInk.white(Sheet.Row.TITLE_ALPHA), Sheet.Note.LEADING),
            size = m.TEXT_SIZE,
            modifier = Modifier.weight(1f),
        )
    }
}
