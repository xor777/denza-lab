package dev.denza.apps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.ui.dashboard.DashboardGrid
import dev.denza.apps.ui.dashboard.DashboardTile

/**
 * The dashboard itself: the band of features and the strip box under it, laid out for one of the
 * three windows.
 *
 * This is the whole of the page's geometry and it is the only place that has it. The real screen
 * hosts it inside the window's `safeDrawing` insets; the debug fixture harness hosts it inside a
 * fixed frame - a 1280, 828 or 416 by 680 box, with 24 dp of padding at the top of a pane standing
 * for the caption bar - and feeds it a board's tiles. Both get the same page, because there is one
 * page: a harness with a copy of the layout proves the copy, and the copy is exactly what drifts.
 *
 * What it expects of its host:
 *  - [modifier] brings the page's box, already inside whatever the system keeps. Every position
 *    from here down is Luminofor's window dp less the caption bar, which is what `safeDrawing`
 *    subtracts, so a pane's chips land at 40 (or 36) of the window and the strip box ends at 668.
 *  - [layout] is the host's to resolve, from the window's width, because only the host knows it:
 *    the page's own width is the window less its insets.
 *  - [strip] draws the strip into the box it is handed - exactly `stripBox` wide and tall - and may
 *    draw nothing at all (the real screen draws nothing while the ADB gate holds it); the box keeps
 *    its place either way, so nothing above it moves when it comes and goes.
 *
 * Vertically the page degrades instead of overflowing. It used to add up to exactly 680 with no
 * scroll in any of the three widths, so any inset at all pushed the foot of the analyser past the
 * bottom edge in silence. Now a pane's strip takes what is left down to a floor, the full screen's
 * keeps its 1184 x 296 shape, and when either will not fit - a low window, or a car that keeps more
 * of it than this one - the column scrolls.
 */
@Composable
internal fun DashboardBody(
    tiles: List<DashboardTile>,
    layout: DashboardLayoutMode,
    enabled: Boolean,
    onPress: (DashboardTile) -> Unit,
    onHold: (DashboardTile) -> Unit,
    strip: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val margin = DashboardLayoutPolicy.sideMargin(layout)
        val chips = DashboardLayoutPolicy.chips(layout)
        val page = DashboardLayoutPolicy.page(
            mode = layout,
            features = tiles.size,
            contentWidth = (maxWidth - margin * 2).value.coerceAtLeast(1f),
            height = maxHeight,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (page.scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(
                    start = margin,
                    end = margin,
                    top = DashboardLayoutPolicy.topInset(layout),
                    bottom = DashboardLayoutPolicy.bottomMargin(layout),
                ),
        ) {
            DashboardGrid(
                tiles = tiles,
                layout = layout,
                enabled = enabled,
                onPress = onPress,
                onHold = onHold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DashboardLayoutPolicy.bandGap(layout)))
            // The strip draws in a space of its own, and the box it is given has to be that space's
            // shape or the drawing arrives stretched. It used to get whatever height was left over,
            // which on the full screen was about twice its own.
            //
            // So the full screen always asks for a box of the board's shape, and a pane with room
            // takes the remainder as a `weight(1f)` - which its renderer can do, because it lays
            // itself out at one unit to one dp in whatever it is handed, and which is the one
            // arrangement that cannot be wrong. A pane only names a height when the column is
            // scrolling, because a scrolling column has no remainder: an infinite height is what a
            // weight would be measured against there.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (chips && !page.scrolls) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.height(page.panelHeight)
                        },
                    ),
            ) {
                strip(Modifier.fillMaxSize())
            }
            // Any slack on the full screen goes under the strip rather than between it and the
            // tiles. A pane has none: its strip already took it.
            if (!chips && !page.scrolls) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
