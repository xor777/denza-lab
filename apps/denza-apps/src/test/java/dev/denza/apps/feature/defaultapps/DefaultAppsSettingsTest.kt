package dev.denza.apps.feature.defaultapps

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The confirmed selection stores a package and works its label out when read, so that no word of
 * ours outlives a language change. Installs from before that still hold the label they stored.
 */
class DefaultAppsSettingsTest {
    private val role = DefaultAppRole.NAVIGATION
    private val context = FakeContext()

    @Test
    fun anOldInstallKeepsItsStockSelectionUnderTheSameWords() {
        context.prefs.values["confirmed_package_navigation"] = role.stockPackageName
        context.prefs.values["confirmed_label_navigation"] = "Штатная навигация"

        val selection = DefaultAppsSettings.confirmedSelection(context, role)

        assertEquals(role.stockPackageName, selection?.selectedPackageName)
        assertEquals("Штатная навигация", selection?.selectedLabel)
    }

    @Test
    fun aStoredLabelInAnotherLanguageIsNotShown() {
        context.prefs.values["confirmed_package_navigation"] = role.stockPackageName
        context.prefs.values["confirmed_label_navigation"] = "Stock navigation"

        assertEquals(
            "Штатная навигация",
            DefaultAppsSettings.confirmedSelection(context, role)?.selectedLabel,
        )
    }

    @Test
    fun aKnownApplicationAndAnUnknownOneAreNamedWithoutTheStoredLabel() {
        context.prefs.values["confirmed_package_navigation"] = "ru.yandex.yandexnavi"
        assertEquals(
            "Яндекс Навигатор",
            DefaultAppsSettings.confirmedSelection(context, role)?.selectedLabel,
        )

        context.prefs.values["confirmed_package_navigation"] = "example.removed"
        assertEquals(
            "example.removed",
            DefaultAppsSettings.confirmedSelection(context, role)?.selectedLabel,
        )
    }

    @Test
    fun aPackageStoredWithoutAnyLabelIsStillASelection() {
        DefaultAppsSettings.rememberConfirmedSelection(context, role, "ru.yandex.yandexnavi")

        assertEquals("ru.yandex.yandexnavi", DefaultAppsSettings.confirmedPackage(context, role))
    }

    @Test
    fun theNextConfirmationDropsTheOldLabelAndStoresNoWord() {
        context.prefs.values["confirmed_package_navigation"] = role.stockPackageName
        context.prefs.values["confirmed_label_navigation"] = "Штатная навигация"

        DefaultAppsSettings.rememberConfirmedSelection(context, role, "ru.yandex.yandexnavi")

        assertEquals(mapOf("confirmed_package_navigation" to "ru.yandex.yandexnavi"), context.prefs.values)
    }

    @Test
    fun aBlankOrAbsentPackageIsNoSelection() {
        assertNull(DefaultAppsSettings.confirmedSelection(context, role))

        DefaultAppsSettings.rememberConfirmedSelection(context, role, " ")
        assertFalse(context.prefs.values.containsKey("confirmed_package_navigation"))

        context.prefs.values["confirmed_package_navigation"] = " "
        assertNull(DefaultAppsSettings.confirmedSelection(context, role))
    }

    /** Hydration runs on the main thread before the first frame: preferences and nothing else. */
    @Test
    fun hydrationNeverAsksForPackageManager() {
        listOf(role.stockPackageName, "ru.yandex.yandexnavi", "example.unknown").forEach { packageName ->
            context.prefs.values["confirmed_package_navigation"] = packageName
            DefaultAppsSettings.confirmedSelection(context, role)
        }

        assertEquals(0, context.packageManagerRequests)
    }

    /** A disabled stock map is installed and not launchable: the word `label()` gives it, unasked. */
    @Test
    fun aProviderReadNamesAPackageOutsideTheCatalogAsTheCatalogWill() {
        assertEquals(
            DefaultAppsCatalog.label(role, role.stockPackageName, emptyList()),
            DefaultAppsCatalog.labelNow(context, role, role.stockPackageName, launchable = emptySet()),
        )
        assertEquals(0, context.packageManagerRequests)
    }

    @Test
    fun aProviderReadAsksForTheNameOfALaunchablePackage() {
        val packageName = "example.launchable"

        assertEquals(packageName, DefaultAppsCatalog.labelNow(context, role, packageName, setOf(packageName)))
        assertEquals(1, context.packageManagerRequests)
    }

    /** Counts every request for a PackageManager and answers none, so a caught failure still shows. */
    private class FakeContext : ContextWrapper(null) {
        val prefs = Prefs()
        var packageManagerRequests = 0

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs

        override fun getPackageManager(): PackageManager {
            packageManagerRequests++
            error("no packages in a unit test")
        }
    }

    /** Strings only: all these keys hold. */
    private class Prefs : SharedPreferences {
        val values = HashMap<String, String>()

        override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { values[key] = checkNotNull(value) }

            override fun remove(key: String): SharedPreferences.Editor = apply { values.remove(key) }

            override fun apply() = Unit

            override fun commit(): Boolean = true

            override fun clear(): SharedPreferences.Editor = unused()
            override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor = unused()
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = unused()
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = unused()
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = unused()
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = unused()
        }

        override fun getAll(): MutableMap<String, *> = unused()
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = unused()
        override fun getInt(key: String, defValue: Int): Int = unused()
        override fun getLong(key: String, defValue: Long): Long = unused()
        override fun getFloat(key: String, defValue: Float): Float = unused()
        override fun getBoolean(key: String, defValue: Boolean): Boolean = unused()
        override fun contains(key: String): Boolean = unused()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private fun unused(): Nothing = error("not used by DefaultAppsSettings' confirmed selection")
    }
}
