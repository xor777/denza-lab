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
    fun denzaAndStockLauncherStayHiddenEvenWhenProviderSaysVisible() {
        assertFalse(
            visible(
                packageName = "dev.denza.apps",
                showInAppList = true,
            ),
        )
        assertFalse(
            visible(
                packageName = "com.android.launcher3",
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
}
