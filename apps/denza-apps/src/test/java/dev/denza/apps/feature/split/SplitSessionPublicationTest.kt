package dev.denza.apps.feature.split

import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the split tells the dashboard, and when.
 *
 * Every operation used to end in the dashboard's whole recompute, run synchronously on the actor's
 * worker - and on a tap, on the main thread before the waiting window could draw - whether or not
 * anything the tile shows had moved. The session is published when it changes, and only then.
 */
class SplitSessionPublicationTest {
    private val cars = mutableListOf<SplitCarFixture>()

    @After
    fun stop() {
        cars.forEach(SplitCarFixture::close)
    }

    @Test
    fun anOpenIsPublishedAsItStartsAndAsItSettles() {
        val car = SplitCarFixture(FakeShell()).also(cars::add)
        val core = car.core(SplitDurable(enabled = true))
        val published = Collections.synchronizedList(mutableListOf<SplitScreenPhase>())
        core.initialize { published += core.snapshot().phase }

        core.openPickerSession()
        car.barrier()

        assertEquals(
            listOf(SplitScreenPhase.ACTIVE, SplitScreenPhase.STARTING, SplitScreenPhase.ACTIVE),
            published.toList(),
        )
    }

    @Test
    fun backgroundWorkThatChangesNothingTheTileShowsPublishesNothing() {
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() }).also(cars::add)
        val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
        val published = Collections.synchronizedList(mutableListOf<SplitScreenSession>())
        core.initialize { published += core.snapshot() }
        core.openPickerSession()
        car.barrier()
        val settled = published.toList()

        core.homeKeyPressed()
        car.barrier()

        assertEquals(settled, published.toList())
    }
}
