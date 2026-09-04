package dev.denza.apps.feature.defaultapps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppsDirectManifestContractTest {
    @Test
    fun splitPickerOwnsTheSingleInfoEntryAndNavigationProxyIsAbsent() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val splitPicker = manifest.activityBlock(".feature.split.SplitPickerActivity")

        assertEquals(1, Regex("android.intent.category.INFO").findAll(manifest).count())
        assertTrue(splitPicker.contains("android.intent.category.INFO"))
        assertFalse(manifest.contains("DefaultNavigationProxyActivity"))
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
