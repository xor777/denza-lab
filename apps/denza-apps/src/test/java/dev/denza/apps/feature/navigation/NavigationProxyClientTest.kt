package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationProxyClientTest {
    @Test
    fun extractsLastMarkedCommandResult() {
        assertEquals(
            "37",
            NavigationProxyClient.resultValue(
                "runtime prelude\nDENZA_RESULT:12\nDENZA_RESULT:37\n",
            ),
        )
    }

    @Test
    fun rejectsOutputWithoutMarkedResult() {
        assertThrows(IllegalStateException::class.java) {
            NavigationProxyClient.resultValue("permission denied")
        }
    }

    @Test
    fun parsesStandaloneProjectionOriginWithoutNegativeShellArguments() {
        assertEquals(
            NavigationProjectionOrigin(
                sourceRootTaskId = 23,
                companionTaskId = 0,
                companionRootTaskId = 0,
            ),
            NavigationProxyClient.projectionOriginValue("DENZA_RESULT:23,0,0"),
        )
    }
}
