package dev.denza.apps.feature.split

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPickerVisibilityPolicyTest {
    @Test
    fun appIsVisibleWhenShowInAppListIsMissing() {
        assertTrue(
            visible(
                packageName = "ru.yandex.music",
            ),
        )
    }

    @Test
    fun explicitShowInAppListFalseHidesApp() {
        assertFalse(
            visible(
                packageName = "com.byd.wfd.client",
                showInAppList = false,
            ),
        )
    }

    @Test
    fun explicitShowInAppListTrueShowsApp() {
        assertTrue(
            visible(
                packageName = "com.byd.synclink",
                showInAppList = true,
            ),
        )
    }

    @Test
    fun parsesProviderBooleanRepresentations() {
        assertTrue(SplitPickerVisibilityPolicy.parseShowInAppList("true") == true)
        assertTrue(SplitPickerVisibilityPolicy.parseShowInAppList("1") == true)
        assertTrue(SplitPickerVisibilityPolicy.parseShowInAppList("false") == false)
        assertTrue(SplitPickerVisibilityPolicy.parseShowInAppList("0") == false)
        assertTrue(SplitPickerVisibilityPolicy.parseShowInAppList("unknown") == null)
    }

    @Test
    fun denzaControlLauncherIsVisibleButSplitAliasAndStockLauncherStayHidden() {
        assertTrue(
            launcherVisible(
                packageName = "dev.denza.apps",
                activityName = "dev.denza.apps.DenzaLauncherActivity",
                showInAppList = true,
            ),
        )
        assertFalse(
            launcherVisible(
                packageName = "dev.denza.apps",
                activityName = "dev.denza.apps.SplitScreenLauncherAlias",
                showInAppList = true,
            ),
        )
        assertFalse(
            launcherVisible(
                packageName = "com.android.launcher3",
                activityName = "com.android.launcher3.Launcher",
                showInAppList = true,
            ),
        )
    }

    /**
     * Что каталог обязан найти, когда резольвит НАС самих (правка волны 15).
     *
     * `SplitPickerCatalog` подменяет нашу LAUNCHER-запись - оконный трамплин
     * `DenzaLauncherActivity` - тем компонентом, который реально занимает панель, и берёт его
     * `launchMode` там же. Подмена молчалива по построению: `getActivityInfo` отвечает `null`, и
     * каталог возвращается к трамплину, то есть к самому дефекту 2026-08-27 (сторона панели
     * теряется на безкатегорийном старте трамплина). Сам `scan` без `PackageManager` не проверить,
     * а вот его допущение о манифесте - можно, и оно ломается ровно от переименования и от
     * `exported="false"`.
     *
     * Живёт здесь, а не в отдельном файле: это допущение того же каталога, что и его политика
     * видимости.
     */
    @Test
    fun theManifestStillDeclaresThePaneActivityTheCatalogResolvesUsTo() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val declaration = Regex("<activity[^>]*android:name=\"\\.MainActivity\"[^>]*/?>")
            .find(manifest)
        assertTrue("манифест больше не объявляет .MainActivity", declaration != null)
        val activity = declaration!!.value
        assertTrue(
            "MainActivity перестала быть exported",
            activity.contains("android:exported=\"true\""),
        )
        assertTrue(
            "launchMode MainActivity изменился - гард «два окна» в selectApp считает по нему",
            activity.contains("android:launchMode=\"singleTask\""),
        )
    }

    private fun visible(
        packageName: String,
        showInAppList: Boolean? = null,
    ): Boolean = SplitPickerVisibilityPolicy.isVisible(
        packageName = packageName,
        showInAppList = showInAppList,
    )

    private fun launcherVisible(
        packageName: String,
        activityName: String,
        showInAppList: Boolean? = null,
    ): Boolean = SplitPickerVisibilityPolicy.isLauncherVisible(
        packageName = packageName,
        activityName = activityName,
        showInAppList = showInAppList,
    )
}
