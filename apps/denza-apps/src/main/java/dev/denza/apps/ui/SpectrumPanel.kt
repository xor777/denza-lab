package dev.denza.apps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.denza.apps.feature.trip.TripPanelLayout
import dev.denza.apps.feature.trip.TripPanelView
import kotlin.math.roundToInt

/**
 * The strip under the dashboard: the sound page and the car page, one swipe apart.
 *
 * One view, [TripPanelView], laid over the Luminofor strip box the caller sizes and places this
 * composable on. It answers one gesture - a horizontal swipe anywhere on it turns the page - and
 * nothing else: a tap does nothing, and a vertical drag belongs to whatever scrolls above it.
 *
 * **The view is a little larger than the box it is given**, by [TripPanelView.OVERHANG_DP] on the
 * left, the right and the foot, and is placed so the box itself is exactly where the caller put it.
 * The board draws a few things past the box's edge - the chart's newest point on the right edge
 * with its glow round it, the analyser's outer glow, the haze - and a view the box's own size cut
 * them. The layout this reports is still the box: nothing around the strip moves, and the overhang
 * lands on the page margin, which every composition has at least that wide.
 */
@Composable
internal fun SpectrumPanel(
    layout: TripPanelLayout,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> TripPanelView(context) },
        update = { view ->
            view.layout = layout
            // The same whole pixels the layout below adds, so the box lands where it was placed.
            view.overhang = (TripPanelView.OVERHANG_DP * view.resources.displayMetrics.density).roundToInt().toFloat()
        },
        modifier = modifier.layout { measurable, constraints ->
            val out = TripPanelView.OVERHANG_DP.dp.roundToPx()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
            val placeable = measurable.measure(Constraints.fixed(width + 2 * out, height + out))
            layout(width, height) { placeable.place(-out, 0) }
        },
    )
}
