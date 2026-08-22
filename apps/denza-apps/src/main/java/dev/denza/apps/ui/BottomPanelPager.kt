package dev.denza.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.denza.apps.feature.trip.TripPanelView
import dev.denza.apps.feature.vehicle.VehiclePanelView

/**
 * The bottom panel, swiped as a whole.
 *
 * Page one is the trip page that has always been there (spectrum analyser plus
 * the journey figures); page two reads the car itself over the local ADB shell.
 * Both pages keep their virtual layouts, so the narrow split pane reflows them
 * exactly as before.
 *
 * `beyondViewportPageCount = 1` is not a scrolling nicety here: it keeps both
 * views attached, which is what keeps each page's data collection running while
 * the other page is on screen. Rendering, on the other hand, follows visibility
 * — each view is told whether it is the page being looked at, and the invisible
 * one stops drawing.
 */
@Composable
internal fun BottomPanelPager(
    compactLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val settledPage = pagerState.currentPage

    Box(modifier) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                TRIP_PAGE -> AndroidView(
                    factory = { context -> TripPanelView(context) },
                    update = { view ->
                        view.narrowLayout = compactLayout
                        view.pageVisible = settledPage == TRIP_PAGE
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                else -> AndroidView(
                    factory = { context -> VehiclePanelView(context) },
                    update = { view ->
                        view.narrowLayout = compactLayout
                        view.pageVisible = settledPage == VEHICLE_PAGE
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PageDots(
            count = PAGE_COUNT,
            current = settledPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
        )
    }
}

/**
 * The only chrome the panel gets: two dots, so a second page is discoverable at
 * all. Both renderers leave this strip clear at the bottom of their layouts.
 */
@Composable
private fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 7.dp else 6.dp)
                    .background(
                        color = if (index == current) DotActive else DotIdle,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private val DotActive = Color(0xE673E0BD)
private val DotIdle = Color(0x529AA7AD)

private const val PAGE_COUNT = 2
private const val TRIP_PAGE = 0
private const val VEHICLE_PAGE = 1
