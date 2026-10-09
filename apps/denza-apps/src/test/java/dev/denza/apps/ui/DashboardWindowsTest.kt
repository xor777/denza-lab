package dev.denza.apps.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.denza.apps.ui.dashboard.DenzaActions
import dev.denza.apps.ui.dashboard.IdleActions
import dev.denza.apps.ui.dashboard.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the root does with the windows a tile opened: when they stand, and when a chooser closes.
 *
 * The windows are saved with the screen, so they come back after the process died - behind the
 * startup gate, before the runtime has started. A chooser brought straight back read the car and
 * ran its feature over the gate; these hold it back until the gate drops, and hold a chooser to
 * closing exactly when the app says the choice was taken.
 */
class DashboardWindowsTest {

    @Test
    fun aChooserBroughtBackWithTheScreenWaitsForTheStartupGate() {
        val restored = restoredAfterProcessDeath(
            DashboardWindows(
                settingsFor = TileId.CLUSTER,
                clusterPicker = true,
                apps = true,
                navigationApp = true,
                fseApp = true,
            ),
        )

        val underTheGate = restored.shown(gateUp = true)
        assertFalse(underTheGate.clusterPicker)
        assertFalse(underTheGate.apps)
        assertFalse(underTheGate.navigationApp)
        assertFalse(underTheGate.fseApp)
        // «Сервис», which the gate leaves the driver, opens panels on purpose; a panel stands.
        assertEquals(TileId.CLUSTER, underTheGate.settingsFor)

        assertEquals(
            DashboardWindows.Shown(
                settingsFor = TileId.CLUSTER,
                clusterPicker = true,
                apps = true,
                navigationApp = true,
                fseApp = true,
            ),
            restored.shown(gateUp = false),
        )
    }

    @Test
    fun aPanelOfATileThisBuildDoesNotHaveIsNotRestored() {
        val saved = with(DashboardWindows.Saver) { scope.save(DashboardWindows(settingsFor = TileId.WEATHER)) }
        val renamed = (saved as List<*>).toMutableList().apply { set(0, "GONE") }

        assertEquals(null, DashboardWindows.Saver.restore(renamed)?.settingsFor)
    }

    @Test
    fun theDriversScreenChooserClosesExactlyWhenTheChoiceWasTaken() {
        val windows = DashboardWindows(navigationApp = true)

        windows.chooseNavigationApp("ru.yandex.yandexnavi", answering(navigation = false))
        assertTrue("a choice nothing took closed the window", windows.navigationApp)

        windows.chooseNavigationApp("ru.yandex.yandexnavi", answering(navigation = true))
        assertFalse("a choice the app took left the window open", windows.navigationApp)
    }

    @Test
    fun thePassengerChooserClosesExactlyWhenAnInstallStarted() {
        val windows = DashboardWindows(fseApp = true)

        windows.installFseApp("ru.app", answering(install = false))
        assertTrue("a tap that started nothing closed the chooser", windows.fseApp)

        windows.installFseApp("ru.app", answering(install = true))
        assertFalse("an install that started left the chooser open", windows.fseApp)
    }

    /** It opens drawn, on a list already read, and not at all while an install is under way. */
    @Test
    fun thePassengerChooserOpensOnlyOnAListItCouldRead() {
        val windows = DashboardWindows()

        windows.openFseChooser(answering(load = false))
        assertFalse(windows.fseApp)

        windows.openFseChooser(answering(load = true))
        assertTrue(windows.fseApp)
    }

    /** One read for a chooser that came back without its list, and none for one opened here. */
    @Test
    fun aPassengerChooserReadsItsListAgainOnlyIfTheProcessLostIt() {
        val reads = mutableListOf<Unit>()
        val reading = object : IdleActions() {
            override val onLoadFseApps: () -> Boolean = {
                reads += Unit
                true
            }
        }
        val windows = DashboardWindows(fseApp = true)

        windows.readFseAppsIfLost(read = true, reading)
        assertEquals("a list already read - even an empty one - was read again", 0, reads.size)

        windows.readFseAppsIfLost(read = false, reading)
        assertEquals(1, reads.size)
        assertTrue(windows.fseApp)

        // The new process finds an install under way: the chooser shuts, as a press would leave it.
        windows.readFseAppsIfLost(read = false, answering(load = false))
        assertFalse(windows.fseApp)
    }

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun restoredAfterProcessDeath(windows: DashboardWindows): DashboardWindows {
        val saved = with(DashboardWindows.Saver) { scope.save(windows) }
        return requireNotNull(DashboardWindows.Saver.restore(requireNotNull(saved)))
    }

    private fun answering(
        navigation: Boolean = false,
        install: Boolean = false,
        load: Boolean = false,
    ): DenzaActions = object : IdleActions() {
        override val onSelectNavigationApp: (String) -> Boolean = { navigation }
        override val onInstallFseApp: (String) -> Boolean = { install }
        override val onLoadFseApps: () -> Boolean = { load }
    }
}
