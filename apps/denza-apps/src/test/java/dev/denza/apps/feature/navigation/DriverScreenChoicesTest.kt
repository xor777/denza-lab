package dev.denza.apps.feature.navigation

import dev.denza.apps.feature.defaultapps.InstalledDefaultApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the driver's display may be given, without a list.
 *
 * The owner's rule: any application the car has, or the instruments - no filter written into the
 * code. These hold the two exclusions to exactly two, the order to the projection chooser's, and
 * the six-navigator list to staying gone on both sides of the shell boundary.
 */
class DriverScreenChoicesTest {
    @Test
    fun onlyThisAppAndTheHomeScreenAreLeftOut() {
        val home = "com.byd.mycar"

        assertTrue(ProjectablePackages.isExcluded(NavigationAppPolicy.DASHBOARD_PACKAGE, home))
        assertTrue(ProjectablePackages.isExcluded(home, home))
        // Everything else is an application like any other - a navigator, a player, the car's own
        // settings, and the one no list would ever have named.
        listOf(
            "ru.yandex.yandexnavi",
            "org.videolan.vlc",
            "com.byd.carsettings",
            "com.byd.launchermap",
            "com.example.somethingnew",
        ).forEach { packageName ->
            assertFalse(packageName, ProjectablePackages.isExcluded(packageName, home))
        }
        // A car that answers HOME with nothing leaves only this app out.
        assertFalse(ProjectablePackages.isExcluded("com.byd.mycar", null))
    }

    @Test
    fun theApplicationsStandByNameWithTheExclusionsGone() {
        val launchable = listOf(
            app("ru.yandex.yandexnavi", "Навигатор"),
            app(NavigationAppPolicy.DASHBOARD_PACKAGE, "Denza Apps"),
            app("org.videolan.vlc", "VLC"),
            app("com.launcher.home", "Home"),
            app("com.bilibili.bilithings", "bilibili"),
            app("com.waze", "Waze"),
            app("com.apple.android.music", "Apple Music"),
        )

        val offered = NavigationApplications.of(launchable, homePackage = "com.launcher.home")

        // String.CASE_INSENSITIVE_ORDER, as the projection's chooser sorts: "bilibili" among the
        // B's rather than after every capital, and Cyrillic after Latin.
        assertEquals(
            listOf("Apple Music", "bilibili", "VLC", "Waze", "Навигатор"),
            offered.map(InstalledDefaultApp::label),
        )
    }

    @Test
    fun twoApplicationsOfOneNameKeepAFixedOrder() {
        val offered = NavigationApplications.of(
            listOf(app("b.maps", "Карты"), app("a.maps", "Карты")),
            homePackage = null,
        )

        assertEquals(listOf("a.maps", "b.maps"), offered.map(InstalledDefaultApp::packageName))
    }

    /**
     * The six-navigator list stays gone from both sides of the shell boundary.
     *
     * Read off the source on purpose: the owner's rule is that no list of packages is written into
     * the code, so the text is the contract. Behaviour cannot show a list's absence - the tests
     * above take a few packages, and a list that happened to hold them would pass.
     */
    @Test
    fun theNavigatorListStaysGoneOnBothSidesOfTheShellBoundary() {
        val sources = listOf(
            "src/main/java/dev/denza/apps/feature/navigation/ClusterProxyMain.java",
            "src/main/java/dev/denza/apps/feature/navigation/NavigationModels.kt",
            "src/main/java/dev/denza/apps/feature/navigation/NavigationSettings.kt",
            "src/main/java/dev/denza/apps/feature/navigation/NavigationCoordinator.kt",
        ).associateWith { File(it).readText() }
        val formerList = listOf(
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexmaps",
            "com.google.android.apps.maps",
            "app.morphe.android.apps.maps",
            "com.waze",
            "ru.dublgis.dgismobile",
        )
        sources.forEach { (path, source) ->
            formerList.forEach { packageName ->
                assertFalse("$path names $packageName", source.contains("\"$packageName\""))
            }
        }
    }

    /**
     * And the shell-side proxy asks the one rule before it reads or touches a task, in every
     * command that names a package.
     *
     * The commands are built with no context at all: one that reached for the task list before the
     * rule would fail on that instead of refusing. Until 2026-10-09 this was read off the proxy's
     * source, which a renamed method or a moved line could pass.
     */
    @Test
    fun theProxyRefusesWhatTheRuleRefusesBeforeItLooksAtATask() {
        val asked = mutableListOf<String>()
        val proxy = ClusterProxyMain.Commands(null) { packageName ->
            asked += packageName
            false
        }
        val refused = "com.example.refused"

        assertEquals("find-task", -1, proxy.findTask(refused))
        listOf<Pair<String, () -> Unit>>(
            "project-task" to { proxy.projectTask(refused, 7, 11, 3, 1920, 720) },
            "return-task" to { proxy.returnTask(refused, 7, 2, -1, -1, true) },
            "restore-task" to { proxy.returnTask(refused, 7, 2, -1, -1, false) },
            "projection-origin" to { proxy.projectionOrigin(refused, 7) },
            "task-display" to { proxy.taskDisplayId(refused, 7) },
        ).forEach { (command, run) ->
            val error = runCatching(run).exceptionOrNull()
            assertTrue("$command did not refuse: $error", error is SecurityException)
        }
        assertEquals("every command asked the rule about its package", List(6) { refused }, asked)
    }

    private fun app(packageName: String, label: String) = InstalledDefaultApp(packageName, label, null)
}
