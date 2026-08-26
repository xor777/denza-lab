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
 * This used to be a pager over three pages - the spectrum, the car's electrical side and its
 * combustion side. The other two are being redrawn against the design boards, and one page behind a
 * pager is worse than no pager at all: the strip still takes the horizontal drag, so a finger that
 * meant to swipe gets a shrug instead of a page. So it is one view again, and the pager comes back
 * when there is something to page to.
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
            view.pageVisible = true
        },
        // A hosted View is positioned through Compose's own view container, where a parent's clip
        // does not reach it on its own.
        modifier = modifier.clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )
}
