package dev.denza.apps.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * An application offered for choosing: its own icon in a well, its own name, and whether it is
 * picked.
 *
 * There were four of these, in three files, differing by 114 against 116 against 126 dp of height,
 * 50 against 54 dp of icon and 12 against 13 sp of label - none of which anybody chose, and all of
 * which were visible when two pickers opened one after the other. One tile, one set of numbers,
 * all from [DenzaMetrics.Component] and all measured off `Config.dc.html`.
 *
 * The icon sits in a rounded well rather than floating on the tile. Application icons come in every
 * shape a vendor felt like - square, round, transparent, letterboxed - and a well gives a row of
 * them one silhouette. When the tile is picked the well takes the accent behind the icon, so the
 * choice is legible from the icon itself and not only from the border.
 *
 * Selection never thickens the edge: it is drawn at the hairline picked or not, so choosing an app
 * does not nudge its neighbours by a pixel.
 */
@Composable
fun DenzaAppTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Drawable? = null,
    iconKey: Any? = label,
) {
    val bitmap = remember(iconKey, icon) { icon?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap() }
    val shape = RoundedCornerShape(DenzaMetrics.Radius.L)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(DenzaMetrics.Component.APP_TILE)
            .background(DenzaColors.Surface, shape)
            .border(
                BorderStroke(
                    DenzaMetrics.Stroke.HAIRLINE,
                    if (selected) DenzaColors.Accent else DenzaColors.ink(0.12f),
                ),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = DenzaMetrics.Space.M, horizontal = DenzaMetrics.Space.S),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S),
    ) {
        Box(
            modifier = Modifier
                .size(DenzaMetrics.Component.SHEET_APP_ICON)
                .background(
                    if (selected) DenzaColors.accent(0.16f) else DenzaColors.SurfaceRaised,
                    RoundedCornerShape(DenzaMetrics.Radius.M),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(DenzaMetrics.Space.XS),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // No icon to be had: the initial, which is what the board draws for the ones it
                // cannot show. Better than a generic glyph repeated down a column of six.
                Text(
                    text = label.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) DenzaColors.Accent else DenzaColors.InkSecondary,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) DenzaColors.Accent else DenzaColors.InkSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private const val ICON_PX = 128
