package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterProxyMainTest {
    @Test
    fun newEmptyRootIsSelectedOnlyOnProjectionDisplay() {
        assertEquals(
            42,
            ClusterProxyMain.Commands.newEmptyRootId(
                11,
                intArrayOf(2, 3, 41),
                intArrayOf(2, 3, 41, 42, 43),
                intArrayOf(0, 0, 11, 11, 12),
                arrayOf(
                    intArrayOf(23),
                    intArrayOf(19),
                    intArrayOf(41),
                    intArrayOf(42),
                    intArrayOf(43),
                ),
            ),
        )
    }

    @Test
    fun nonEmptyMalformedOrAmbiguousProjectionRootsFailClosed() {
        assertEquals(
            -1,
            ClusterProxyMain.Commands.newEmptyRootId(
                11,
                intArrayOf(2, 3),
                intArrayOf(2, 44),
                intArrayOf(0, 11),
                arrayOf(intArrayOf(23), intArrayOf(44, 25)),
            ),
        )
        assertEquals(
            -1,
            ClusterProxyMain.Commands.newEmptyRootId(
                11,
                intArrayOf(2, 3),
                intArrayOf(2, 44),
                intArrayOf(0, 11),
                arrayOf(intArrayOf(23), null),
            ),
        )
        assertEquals(
            -1,
            ClusterProxyMain.Commands.newEmptyRootId(
                11,
                intArrayOf(2, 3),
                intArrayOf(2, 44),
                intArrayOf(0, 11),
                arrayOf(intArrayOf(23), intArrayOf(45)),
            ),
        )
        assertEquals(
            -1,
            ClusterProxyMain.Commands.newEmptyRootId(
                11,
                intArrayOf(2, 3),
                intArrayOf(44, 45),
                intArrayOf(11, 11),
                arrayOf(intArrayOf(44), intArrayOf(45)),
            ),
        )
        assertEquals(
            -1,
            ClusterProxyMain.Commands.newEmptyRootId(
                11,
                intArrayOf(2, 3),
                intArrayOf(44),
                intArrayOf(),
                arrayOf(intArrayOf(44)),
            ),
        )
    }

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
