package dev.denza.apps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import dev.denza.apps.feature.trip.TripPanelView

/**
 * The strip under the dashboard: the spectrum analyser and the journey's figures.
 *
 * This is intentionally one non-interactive view. The former pager and its
 * vehicle pages are not mounted in the current screen.
 *
 * It answers no touch at all. It carried the hidden diagnostics gesture for exactly one wave, on
 * the reasoning that it was the largest surface on the screen that did nothing when touched - which
 * is true, and is also the argument for a door being findable rather than for it being here.
 * Service is a tile now.
 */
@Composable
internal fun SpectrumPanel(
    compactLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> TripPanelView(context) },
        update = { view ->
            view.narrowLayout = compactLayout
        },
        // A hosted View is positioned through Compose's own view container, where a parent's clip
        // does not reach it on its own.
        modifier = modifier.clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )
}
