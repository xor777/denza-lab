package dev.denza.apps.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics

/**
 * An application offered for choosing: its own icon, its own name, and whether it is picked.
 *
 * There were four of these, in three files, differing by 114 against 116 against 126 dp of height,
 * 50 against 54 dp of icon and 12 against 13 sp of label - none of which anybody chose, and all of
 * which were visible when two pickers opened one after the other. One tile, one set of numbers,
 * both from [DenzaMetrics.Component].
 *
 * Selection is carried by ink and edge, and the edge does not thicken: it is drawn at the hairline
 * whether picked or not, so picking an app does not nudge its neighbours.
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(DenzaMetrics.Component.APP_TILE)
            .border(
                BorderStroke(
                    DenzaMetrics.Stroke.HAIRLINE,
                    if (selected) DenzaColors.Accent else DenzaColors.ink(0.10f),
                ),
                MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(DenzaMetrics.Space.S),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.XS, Alignment.CenterVertically),
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(DenzaMetrics.Component.APP_TILE_ICON),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(DenzaMetrics.Component.APP_TILE_ICON),
                    tint = DenzaColors.Muted,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) DenzaColors.Accent else DenzaColors.Ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private const val ICON_PX = 128
