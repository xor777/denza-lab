package dev.denza.apps.feature.mirrors

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who owns AVC's one renderer when our camera closes (OTA image and live run, 2026-09-23). */
class AvcRendererOwnershipTest {
    @After
    fun clear() = MirrorFrameWatch.reset()

    @Test
    fun onlyIdleAndTheTurnCardsLeaveTheRendererToUs() {
        assertFalse(AvcStockMode.bindsRendererItself(AvcStockMode.IDLE))
        listOf(AvcStockMode.PIP_LEFT, AvcStockMode.PIP_RIGHT, AvcStockMode.PIP_RIGHT_PORTRAIT).forEach {
            assertFalse("mode $it", AvcStockMode.bindsRendererItself(it))
        }
        // 5002 is the reverse view the 17:06:42 run blacked; 5097/5098 are the CMS and radar cards.
        listOf(5002, 5030, 5037, 5097, 5098).forEach {
            assertTrue("mode $it", AvcStockMode.bindsRendererItself(it))
        }
    }

    @Test
    fun framesThatStoppedMeanTheRendererDrawsElsewhere() {
        assertFalse("before the first frame nothing is known", MirrorFrameWatch.mustNotFree(1_000L))
        MirrorFrameWatch.frame(1_000L)
        assertFalse(MirrorFrameWatch.mustNotFree(1_000L + MirrorFrameWatch.STALL_MS - 1L))
        assertTrue(MirrorFrameWatch.mustNotFree(1_000L + MirrorFrameWatch.STALL_MS))
        assertEquals(40L, MirrorFrameWatch.ageMs(1_040L))
    }

    @Test
    fun aStockTakeoverForbidsTheFreeUntilTheNextSession() {
        MirrorFrameWatch.frame(1_000L)
        MirrorFrameWatch.stockTakesOver()
        assertTrue(MirrorFrameWatch.mustNotFree(1_010L))
        MirrorFrameWatch.reset()
        assertFalse(MirrorFrameWatch.mustNotFree(1_010L))
    }
}
