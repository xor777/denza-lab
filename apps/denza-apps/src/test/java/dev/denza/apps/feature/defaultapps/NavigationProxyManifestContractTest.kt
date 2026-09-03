package dev.denza.apps.feature.defaultapps

import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationProxyManifestContractTest {
    @Test
    fun proxyOwnsTheSingleInfoEntryInsideDenzaApps() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val proxyActivity = manifest.activityBlock(
            ".feature.defaultapps.DefaultNavigationProxyActivity",
        )
        val splitPicker = manifest.activityBlock(".feature.split.SplitPickerActivity")

        assertEquals(1, Regex("android.intent.category.INFO").findAll(manifest).count())
        assertTrue(proxyActivity.contains("android.intent.category.INFO"))
        assertFalse(splitPicker.contains("android.intent.category.INFO"))
    }

    private fun String.activityBlock(activityName: String): String {
        val openingTag = checkNotNull(
            Regex(
                "<activity\\s+[^>]*android:name=\\\"${Regex.escape(activityName)}\\\"[^>]*>",
            ).find(this),
        ) { "Activity $activityName is missing from the manifest" }
        if (openingTag.value.trimEnd().endsWith("/>")) return openingTag.value

        val close = indexOf("</activity>", startIndex = openingTag.range.last + 1)
        check(close >= 0) { "Activity $activityName has no closing tag" }
        return substring(openingTag.range.first, close + "</activity>".length)
    }
}

class DefaultNavigationProxyEntryPolicyTest {
    @Test
    fun plainPackageInfoLaunchIsNavigation() {
        assertEquals(
            DefaultNavigationProxyEntryRoute.NAVIGATION,
            DefaultNavigationProxyEntryPolicy.route(
                Intent.ACTION_MAIN,
                setOf(Intent.CATEGORY_INFO),
                null,
            ),
        )
    }

    @Test
    fun bydPaneCategoryKeepsTheSplitPickerRoute() {
        assertEquals(
            DefaultNavigationProxyEntryRoute.SPLIT_PICKER,
            DefaultNavigationProxyEntryPolicy.route(
                Intent.ACTION_MAIN,
                setOf(Intent.CATEGORY_INFO, "byd.intent.category.START_IVI_PRIMARY"),
                null,
            ),
        )
        assertEquals(
            DefaultNavigationProxyEntryRoute.SPLIT_PICKER,
            DefaultNavigationProxyEntryPolicy.route(
                Intent.ACTION_MAIN,
                setOf(Intent.CATEGORY_INFO, "byd.intent.category.START_IVI_SECOND"),
                null,
            ),
        )
    }

    @Test
    fun ordinaryAutoVoiceOpenKeepsTheDenzaAppsRoute() {
        assertEquals(
            DefaultNavigationProxyEntryRoute.DENZA_APPS,
            DefaultNavigationProxyEntryPolicy.route(
                Intent.ACTION_MAIN,
                setOf(Intent.CATEGORY_INFO),
                "com.byd.autovoice",
            ),
        )
        assertEquals(
            DefaultNavigationProxyEntryRoute.DENZA_APPS,
            DefaultNavigationProxyEntryPolicy.route(
                Intent.ACTION_MAIN,
                setOf(Intent.CATEGORY_LAUNCHER),
                null,
            ),
        )
        assertEquals(
            DefaultNavigationProxyEntryRoute.DENZA_APPS,
            DefaultNavigationProxyEntryPolicy.route(
                Intent.ACTION_VIEW,
                setOf(
                    Intent.CATEGORY_INFO,
                    "byd.intent.category.START_IVI_PRIMARY",
                ),
                null,
            ),
        )
    }
}
