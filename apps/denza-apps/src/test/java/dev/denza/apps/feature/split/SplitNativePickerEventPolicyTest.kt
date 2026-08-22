package dev.denza.apps.feature.split

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitNativePickerEventPolicyTest {
    @Test
    fun onlyWindowsChangedIsATopologyReconciliationHint() {
        assertTrue(
            SplitAccessibilityEventPolicy.isTopologyHint(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            ),
        )
        assertFalse(
            SplitAccessibilityEventPolicy.isTopologyHint(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            ),
        )
        assertFalse(
            SplitAccessibilityEventPolicy.isTopologyHint(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            ),
        )
    }

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
            SplitAccessibilityEventTarget.HOME,
            SplitAccessibilityEventPolicy.target(
                packageName = "com.byd.mycar",
                className = null,
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
        assertEquals(
            SplitAccessibilityEventTarget.PRODUCT_PICKER,
            SplitAccessibilityEventPolicy.target(
                packageName = "dev.denza.apps",
                className = "dev.denza.apps.feature.split.SplitPickerActivity",
            ),
        )
    }

}
