package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterProxyMainTest {
    @Test
    fun splitChildResolvesToItsContainingNativeRoot() {
        assertEquals(
            2,
            ClusterProxyMain.Commands.containingRootId(
                23,
                intArrayOf(2, 3, 4),
                arrayOf(intArrayOf(25, 24, 23), intArrayOf(27), intArrayOf(15)),
            ),
        )
    }

    @Test
    fun standaloneTaskKeepsItsOwnRootId() {
        assertEquals(
            131,
            ClusterProxyMain.Commands.containingRootId(
                131,
                intArrayOf(2, 131),
                arrayOf(intArrayOf(25), intArrayOf(131)),
            ),
        )
    }

    @Test
    fun missingOrMalformedRootSnapshotFailsClosed() {
        assertEquals(
            -1,
            ClusterProxyMain.Commands.containingRootId(
                23,
                intArrayOf(2, 3),
                arrayOf(intArrayOf(25)),
            ),
        )
        assertEquals(
            -1,
            ClusterProxyMain.Commands.containingRootId(
                23,
                intArrayOf(2, 3),
                arrayOf(intArrayOf(25), intArrayOf(27)),
            ),
        )
    }
}
