package dev.denza.apps.feature.simulcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DiShare receivers the share dialog may be driven to, by their protocol ids and card names.
 *
 * `SimulcastDialogGeometry` matches these against the dialog's own nodes, which an Android-free
 * test cannot build, so the list itself is the boundary here: the ids are DiShare's keys, and a
 * wrong one is a card the app never finds.
 */
class ScreenTargetTest {
    @Test
    fun neverTreatsIviAsReceiver() {
        // The head unit is the source: a dialog that offered it as a receiver would cast it to itself.
        assertFalse(ScreenTarget.SUPPORTED.any { it.receiverId == "screen_ivi" })
    }

    @Test
    fun includesBothRearAndOverheadLayoutFamilies() {
        val receiverIds = ScreenTarget.SUPPORTED.map { it.receiverId }.toSet()

        assertTrue(receiverIds.contains("screen_rse_l"))
        assertTrue(receiverIds.contains("screen_rse_r"))
        assertTrue(receiverIds.contains("screen_overhead"))
        assertTrue(receiverIds.contains("screen_tv"))
    }

    @Test
    fun mapsSingleRearTvReceiverToOverheadCard() {
        assertEquals("overhead_screen", ScreenTarget.SUPPORTED.single { it.receiverId == "screen_tv" }.viewResourceName)
    }
}
