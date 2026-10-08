package dev.denza.apps.feature.navigation

import dev.denza.apps.between
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract only: this proves the coordinator wiring without pretending a host unit test is
 * an Android task/display runtime.
 */
class NavigationOneTapSourceContractTest {
    private val source =
        File("src/main/java/dev/denza/apps/feature/navigation/NavigationCoordinator.kt").readText()

    @Test
    fun primaryProjectionLaunchesAMissingTaskAndContinuesProjectionAfterDiscovery() {
        val primary = source.between("fun performPrimaryAction()", "fun onClusterDisplaySelected()")
        val project = source.between("private fun projectToCluster()", "private fun returnToCentralDisplay(")
        val missingTask = project.between("if (taskId < 0)", "if (session.taskId")
        val discovery = source.between("private fun discoverLaunchedTask(", "private fun projectToCluster()")

        assertTrue(primary.contains("NavigationPrimaryAction.PROJECT"))
        assertTrue(primary.contains("projectToCluster()"))
        assertTrue(missingTask.contains("pendingProjectionAfterOpen = true"))
        assertTrue(missingTask.contains("openSelectedApp()"))
        assertTrue(discovery.contains("if (pendingProjectionAfterOpen)"))
        assertTrue(discovery.contains("projectToCluster()"))
        assertTrue(discovery.contains("val packageName = launchAttempt.packageName"))
        assertTrue(
            discovery.indexOf("launchFence.accepts(launchAttempt, selectedPackage)") in
                0 until discovery.indexOf("NavigationProxyClient.findTask"),
        )
    }

    @Test
    fun changingSelectionCancelsTheQueuedLaunchAndItsTransferState() {
        val selection = source.between("fun selectPackage(packageName: String)", "fun performPrimaryAction()")
        val cancellation = source.between("private fun cancelPendingLaunch()", "private fun beginTransfer(")

        assertTrue(selection.contains("cancelPendingLaunch()"))
        assertTrue(cancellation.contains("launchFence.invalidate()"))
        assertTrue(cancellation.contains("splitRoutingLease.release()"))
        assertTrue(cancellation.contains("finishTransfer()"))
    }
}
