package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitScreenToggleControllerTest {
    @Test
    fun disablingUpdatesLauncherAndRuntimeTogether() {
        var launcherVisible = true
        var runtimeEnabled = true
        val events = mutableListOf<String>()

        SplitScreenToggleController.setEnabled(
            enabled = false,
            launcherVisible = { launcherVisible },
            setLauncherVisible = {
                launcherVisible = it
                events += "launcher=$it"
            },
            setRuntimeEnabled = {
                runtimeEnabled = it
                events += "runtime=$it"
            },
        )

        assertFalse(launcherVisible)
        assertFalse(runtimeEnabled)
        assertEquals(listOf("launcher=false", "runtime=false"), events)
    }

    @Test
    fun runtimeFailureRestoresPreviousLauncherState() {
        var launcherVisible = true

        val error = runCatching {
            SplitScreenToggleController.setEnabled(
                enabled = false,
                launcherVisible = { launcherVisible },
                setLauncherVisible = { launcherVisible = it },
                setRuntimeEnabled = { error("runtime failed") },
            )
        }.exceptionOrNull()

        assertEquals("runtime failed", error?.message)
        assertTrue(launcherVisible)
    }

    @Test
    fun disabledLauncherRepairsStaleEnabledRuntime() {
        val runtimeWrites = mutableListOf<Boolean>()

        SplitScreenToggleController.reconcile(
            launcherVisible = false,
            runtimeEnabled = true,
            setRuntimeEnabled = runtimeWrites::add,
        )

        assertEquals(listOf(false), runtimeWrites)
    }

    @Test
    fun matchingPersistedStateNeedsNoRepair() {
        val runtimeWrites = mutableListOf<Boolean>()

        SplitScreenToggleController.reconcile(
            launcherVisible = false,
            runtimeEnabled = false,
            setRuntimeEnabled = runtimeWrites::add,
        )

        assertTrue(runtimeWrites.isEmpty())
    }
}
