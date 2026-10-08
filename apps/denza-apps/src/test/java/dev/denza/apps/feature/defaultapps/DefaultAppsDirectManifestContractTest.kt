package dev.denza.apps.feature.defaultapps

import dev.denza.apps.appManifest
import dev.denza.apps.component
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppsDirectManifestContractTest {
    @Test
    fun splitPickerOwnsTheSingleInfoEntryAndNavigationProxyIsAbsent() {
        val manifest = appManifest()
        val splitPicker = manifest.component("activity", ".feature.split.SplitPickerActivity")

        assertEquals(1, Regex("android.intent.category.INFO").findAll(manifest).count())
        assertTrue(splitPicker.contains("android.intent.category.INFO"))
        assertFalse(manifest.contains("DefaultNavigationProxyActivity"))
    }
}
