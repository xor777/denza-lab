package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitNativePickerEventPolicyTest {
    @Test
    fun stockPickerAndHomeAreRoutedToDifferentAuthoritativeChecks() {
        assertEquals(
            SplitAccessibilityEventTarget.STOCK_PICKER,
            SplitAccessibilityEventPolicy.target(
                packageName = "com.android.launcher3",
                className = "com.android.launcher3.SplitScreenListActivity",
            ),
        )
        assertEquals(
            SplitAccessibilityEventTarget.HOME,
            SplitAccessibilityEventPolicy.target(
                packageName = "com.byd.mycar",
                className = "com.byd.mycar.CarMainActivity",
            ),
        )
        assertEquals(
            SplitAccessibilityEventTarget.IGNORE,
            SplitAccessibilityEventPolicy.target(
                packageName = "com.android.launcher3",
                className = "com.android.launcher3.Launcher",
            ),
        )
        assertEquals(
            SplitAccessibilityEventTarget.IGNORE,
            SplitAccessibilityEventPolicy.target(
                packageName = "ru.yandex.yandexnavi",
                className = "ru.yandex.yandexnavi.core.NavigatorActivity",
            ),
        )
    }
}
