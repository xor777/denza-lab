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
