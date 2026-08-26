package dev.denza.apps.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
 * The panel carries the hidden diagnostics gesture. It is the one large surface on this screen that
 * does nothing when it is touched, which is exactly what a gesture with no affordance needs - the
 * tiles above it spend both of theirs on the feature they name.
 */
@Composable
internal fun SpectrumPanel(
    compactLayout: Boolean,
    onHiddenTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val taps = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.clickable(
            interactionSource = taps,
            indication = null,
            onClick = onHiddenTap,
        ),
    ) {
        AndroidView(
            factory = { context -> TripPanelView(context) },
            update = { view ->
                view.narrowLayout = compactLayout
                view.pageVisible = true
            },
            // A hosted View is positioned through Compose's own view container, where a parent's
            // clip does not reach it on its own.
            modifier = Modifier.fillMaxSize().clipToBounds(),
        )
    }
}
