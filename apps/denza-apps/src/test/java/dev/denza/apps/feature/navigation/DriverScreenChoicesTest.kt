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

        // And the proxy asks the one rule before it finds a task or touches one.
        val proxy = sources.getValue(sources.keys.first())
        val find = proxy.substringAfter("int findTask(String packageName) {").substringBefore("}")
        val enforce = proxy.substringAfter("private void enforceTask(").substringBefore("for (")
        assertTrue(find.contains("isProjectable(packageName)"))
        assertTrue(enforce.contains("isProjectable(packageName)"))
        assertTrue(proxy.contains("ProjectablePackages.isProjectable(context.getPackageManager(), packageName)"))
    }

    private fun app(packageName: String, label: String) = InstalledDefaultApp(packageName, label, null)
}
